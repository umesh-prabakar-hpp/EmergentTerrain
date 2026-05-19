//! World-generation compute shader. Per-pixel noise hash decides
//! tile type (forest / water / industry / clear) and stamps a regular
//! highway grid at the configured spacing. The thresholds and spacing
//! are passed via a uniform buffer (see `WorldUniform` in main.rs);
//! the Phase 20.10 `gpu.toml` config controls their default values
//! through that uniform.

pub const SHADER: &str = r#"
struct Params { width: u32, height: u32, seed_lo: u32, seed_hi: u32, forest_threshold: u32, water_threshold: u32, industry_threshold: u32, spacing: u32 }
@group(0) @binding(0) var<storage, read_write> tiles: array<u32>;
@group(0) @binding(1) var<storage, read_write> highways: array<u32>;
@group(0) @binding(2) var<uniform> params: Params;
fn mix_hash(x: u32, y: u32, seed_lo: u32, seed_hi: u32, salt: u32) -> u32 {
  var n = seed_lo ^ (seed_hi * 1664525u) ^ (x * 374761393u) ^ (y * 668265263u) ^ salt;
  n = (n ^ (n >> 13u)) * 1274126177u;
  return n ^ (n >> 16u);
}
@compute @workgroup_size(8, 8, 1)
fn main(@builtin(global_invocation_id) gid: vec3<u32>) {
  let x = gid.x; let y = gid.y;
  if (x >= params.width || y >= params.height) { return; }
  let idx = y * params.width + x;
  let forest_noise = mix_hash(x, y, params.seed_lo, params.seed_hi, 1u);
  let water_noise = mix_hash(x, y, params.seed_lo, params.seed_hi, 1337u);
  let industry_noise = mix_hash(x, y, params.seed_lo, params.seed_hi, 2718u);
  var tile: u32 = 0u;
  if (water_noise < params.water_threshold) { tile = 2u; }
  else if (industry_noise < params.industry_threshold) { tile = 3u; }
  else if (forest_noise < params.forest_threshold) { tile = 1u; }
  let spacing = max(params.spacing, 1u);
  tiles[idx] = tile;
  highways[idx] = select(0u, 1u, (x % spacing == 0u) || (y % spacing == 0u));
}
"#;
