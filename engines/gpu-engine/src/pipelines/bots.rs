//! Bot-simulation compute shader. Per-bot one workgroup-thread; each
//! thread chooses the best of four neighbor tiles via a per-bot
//! preference score (forest/water/industry/clear weights), applies
//! Phase-7 reverse penalty + heading bias + plan bias, and emits an
//! updated Bot struct + a 1-2 step lookahead plan. Bigger constants
//! (REVERSE_PENALTY, HEADING_BIAS, etc.) live inline here; the
//! per-pipeline uniform carries the per-call width/height/bot_count/step.

pub const SHADER: &str = r#"
const REVERSE_PENALTY: f32 = 0.6;
const HEADING_BIAS: f32 = 0.18;
const PLAN_BIAS: f32 = 0.14;
const REVERSE_LOCK_TICKS: i32 = 2;
const INVALID_POS: i32 = -2147483648;

struct Bot {
  x: i32,
  y: i32,
  prev_x: i32,
  prev_y: i32,
  heading_x: i32,
  heading_y: i32,
  reverse_lock: i32,
  plan_len: i32,
  plan0_x: i32,
  plan0_y: i32,
  plan1_x: i32,
  plan1_y: i32,
  forest: f32,
  water: f32,
  industry: f32,
  clear: f32,
  mobility: f32,
  _pad0: f32,
  _pad1: f32,
  _pad2: f32
}
struct Params { width: u32, height: u32, bot_count: u32, step: u32 }
@group(0) @binding(0) var<storage, read> tiles: array<u32>;
@group(0) @binding(1) var<storage, read> highways: array<u32>;
@group(0) @binding(2) var<storage, read> bots_in: array<Bot>;
@group(0) @binding(3) var<storage, read_write> bots_out: array<Bot>;
@group(0) @binding(4) var<uniform> params: Params;

struct MoveChoice {
  x: i32,
  y: i32,
  score: f32,
  dx: i32,
  dy: i32
}

fn score_tile(tile: u32, highway: bool, bot: Bot) -> f32 {
  var score = bot.clear;
  if (tile == 1u) { score = bot.forest; }
  else if (tile == 2u) { score = bot.water; }
  else if (tile == 3u) { score = bot.industry; }
  if (highway || tile == 4u) { score = score + 0.2; }
  return score;
}

fn adjusted_score(bot: Bot, from_x: i32, from_y: i32, nx: i32, ny: i32, tile: u32, highway: bool) -> f32 {
  var score = score_tile(tile, highway, bot);
  let dx = nx - from_x;
  let dy = ny - from_y;
  if (bot.prev_x != INVALID_POS && bot.prev_y != INVALID_POS && bot.prev_x == nx && bot.prev_y == ny) {
    score = score - REVERSE_PENALTY;
    if (bot.reverse_lock > 0) {
      score = score - REVERSE_PENALTY;
    }
  }
  if (bot.heading_x != 0 || bot.heading_y != 0) {
    if (dx == bot.heading_x && dy == bot.heading_y) {
      score = score + HEADING_BIAS;
    } else if (dx == -bot.heading_x && dy == -bot.heading_y) {
      score = score - HEADING_BIAS;
    }
  }
  if (bot.plan_len > 0 && bot.plan0_x == nx && bot.plan0_y == ny) {
    score = score + PLAN_BIAS;
  }
  return score;
}

fn move_hash(step: u32, idx: u32, x: i32, y: i32) -> u32 {
  var seed = step * 1103515245u + idx * 2654435761u;
  seed = seed + bitcast<u32>(x) * 31u + bitcast<u32>(y) * 17u;
  return seed;
}

fn choose_best(bot: Bot, from_x: i32, from_y: i32) -> MoveChoice {
  var best = MoveChoice(from_x, from_y, -999999.0, 0, 0);

  let nx0 = from_x + 1; let ny0 = from_y;
  if (!(nx0 < 0 || ny0 < 0 || nx0 >= i32(params.width) || ny0 >= i32(params.height))) {
    let idx0 = u32(ny0) * params.width + u32(nx0);
    let score0 = adjusted_score(bot, from_x, from_y, nx0, ny0, tiles[idx0], highways[idx0] != 0u);
    if (score0 > best.score) { best = MoveChoice(nx0, ny0, score0, 1, 0); }
  }

  let nx1 = from_x - 1; let ny1 = from_y;
  if (!(nx1 < 0 || ny1 < 0 || nx1 >= i32(params.width) || ny1 >= i32(params.height))) {
    let idx1 = u32(ny1) * params.width + u32(nx1);
    let score1 = adjusted_score(bot, from_x, from_y, nx1, ny1, tiles[idx1], highways[idx1] != 0u);
    if (score1 > best.score) { best = MoveChoice(nx1, ny1, score1, -1, 0); }
  }

  let nx2 = from_x; let ny2 = from_y + 1;
  if (!(nx2 < 0 || ny2 < 0 || nx2 >= i32(params.width) || ny2 >= i32(params.height))) {
    let idx2 = u32(ny2) * params.width + u32(nx2);
    let score2 = adjusted_score(bot, from_x, from_y, nx2, ny2, tiles[idx2], highways[idx2] != 0u);
    if (score2 > best.score) { best = MoveChoice(nx2, ny2, score2, 0, 1); }
  }

  let nx3 = from_x; let ny3 = from_y - 1;
  if (!(nx3 < 0 || ny3 < 0 || nx3 >= i32(params.width) || ny3 >= i32(params.height))) {
    let idx3 = u32(ny3) * params.width + u32(nx3);
    let score3 = adjusted_score(bot, from_x, from_y, nx3, ny3, tiles[idx3], highways[idx3] != 0u);
    if (score3 > best.score) { best = MoveChoice(nx3, ny3, score3, 0, -1); }
  }
  return best;
}

@compute @workgroup_size(64, 1, 1)
fn main(@builtin(global_invocation_id) gid: vec3<u32>) {
  let idx = gid.x;
  if (idx >= params.bot_count) { return; }
  let bot = bots_in[idx];
  let bucket = f32(move_hash(params.step, idx, bot.x, bot.y) % 10000u) / 10000.0;
  if (bucket > bot.mobility) {
    var idle = bot;
    if (idle.reverse_lock > 0) {
      idle.reverse_lock = idle.reverse_lock - 1;
    }
    bots_out[idx] = idle;
    return;
  }
  let first = choose_best(bot, bot.x, bot.y);
  if (first.score <= -999998.0) {
    bots_out[idx] = bot;
    return;
  }
  var updated = bot;
  updated.prev_x = bot.x;
  updated.prev_y = bot.y;
  updated.x = first.x;
  updated.y = first.y;
  updated.heading_x = first.dx;
  updated.heading_y = first.dy;
  updated.reverse_lock = REVERSE_LOCK_TICKS;
  updated.plan_len = 0;
  updated.plan0_x = INVALID_POS;
  updated.plan0_y = INVALID_POS;
  updated.plan1_x = INVALID_POS;
  updated.plan1_y = INVALID_POS;

  let second = choose_best(updated, updated.x, updated.y);
  if (second.score > -999998.0) {
    updated.plan0_x = second.x;
    updated.plan0_y = second.y;
    updated.plan_len = 1;
    var lookahead = updated;
    lookahead.prev_x = updated.x;
    lookahead.prev_y = updated.y;
    lookahead.x = second.x;
    lookahead.y = second.y;
    lookahead.heading_x = second.dx;
    lookahead.heading_y = second.dy;
    lookahead.reverse_lock = REVERSE_LOCK_TICKS;
    let third = choose_best(lookahead, lookahead.x, lookahead.y);
    if (third.score > -999998.0) {
      updated.plan1_x = third.x;
      updated.plan1_y = third.y;
      updated.plan_len = 2;
    }
  }
  bots_out[idx] = updated;
}
"#;
