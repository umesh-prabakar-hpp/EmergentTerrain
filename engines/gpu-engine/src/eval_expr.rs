// Phase 8 Tier 4 — formula parser + WGSL codegen + wgpu dispatch for the
// EvalExpr RPC. Self-contained module so main.rs stays gRPC-glue only.
//
// Pipeline:
//   formula string
//     ─ parse(...)        → AST (Expr)
//     ─ validate(...)     → reject unknown identifiers and unknown functions
//     ─ to_wgsl(...)      → WGSL compute shader source
//     ─ run_on_gpu(...)   → compile + dispatch + readback + CPU aggregate
//     → EvalAggregate
//
// Safety boundary (this module enforces): formula length ≤ MAX_FORMULA_LEN,
// AST node count ≤ MAX_NODES, end − start ≤ MAX_RANGE. The only identifiers
// in scope are `n` (bound variable per sample) plus the constants `pi` and
// `e`. Only the whitelisted function names map to a WGSL implementation;
// every other Call is rejected at validate-time so codegen sees only
// known nodes.

use std::time::Instant;

use bytemuck::{Pod, Zeroable};
use tonic::Status;

pub const MAX_RANGE:        i64   = 10_000_000;
pub const MAX_FORMULA_LEN:  usize = 1024;
pub const MAX_NODES:        usize = 512;
const     WORKGROUP_SIZE:   u32   = 64;

#[derive(Debug, Clone, PartialEq)]
pub enum Expr {
    Lit(f64),
    Var(String),
    Neg(Box<Expr>),
    Bin(BinOp, Box<Expr>, Box<Expr>),
    Call(String, Vec<Expr>),
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub enum BinOp {
    Add, Sub, Mul, Div, Mod, Pow,
}

#[derive(Debug, Clone, Copy)]
pub struct EvalAggregate {
    pub n:            u64,
    pub finite_count: u32,
    pub sum:          f64,
    pub mean:         f64,
    pub std_dev:      f64,
    pub min:          f64,
    pub max:          f64,
    pub millis:       u64,
}

// ── Tokenizer ────────────────────────────────────────────────────────────────

#[derive(Debug, Clone, PartialEq)]
enum Tok {
    Num(f64),
    Ident(String),
    Plus, Minus, Star, Slash, Percent, StarStar,
    LPar, RPar, Comma,
}

fn tokenize(src: &str) -> Result<Vec<Tok>, String> {
    let bytes = src.as_bytes();
    let mut i = 0usize;
    let mut out = Vec::new();
    while i < bytes.len() {
        let c = bytes[i];
        if c.is_ascii_whitespace() { i += 1; continue; }
        // Number (incl. scientific notation: 1.5e-3, 1e9, .5)
        if c.is_ascii_digit() || (c == b'.' && i + 1 < bytes.len() && bytes[i + 1].is_ascii_digit()) {
            let start = i;
            while i < bytes.len() && (bytes[i].is_ascii_digit() || bytes[i] == b'.') { i += 1; }
            if i < bytes.len() && (bytes[i] == b'e' || bytes[i] == b'E') {
                i += 1;
                if i < bytes.len() && (bytes[i] == b'+' || bytes[i] == b'-') { i += 1; }
                while i < bytes.len() && bytes[i].is_ascii_digit() { i += 1; }
            }
            let s = std::str::from_utf8(&bytes[start..i])
                .map_err(|_| "invalid utf8 in number".to_string())?;
            let v: f64 = s.parse()
                .map_err(|_| format!("invalid numeric literal '{s}'"))?;
            out.push(Tok::Num(v));
            continue;
        }
        // Identifier (letters + underscores; digits allowed after first char)
        if c.is_ascii_alphabetic() || c == b'_' {
            let start = i;
            while i < bytes.len() && (bytes[i].is_ascii_alphanumeric() || bytes[i] == b'_') { i += 1; }
            let s = std::str::from_utf8(&bytes[start..i])
                .map_err(|_| "invalid utf8 in identifier".to_string())?
                .to_string();
            out.push(Tok::Ident(s));
            continue;
        }
        // Two-char operators
        if c == b'*' && i + 1 < bytes.len() && bytes[i + 1] == b'*' {
            out.push(Tok::StarStar); i += 2; continue;
        }
        // Single-char
        let t = match c {
            b'+' => Tok::Plus,
            b'-' => Tok::Minus,
            b'*' => Tok::Star,
            b'/' => Tok::Slash,
            b'%' => Tok::Percent,
            b'(' => Tok::LPar,
            b')' => Tok::RPar,
            b',' => Tok::Comma,
            _    => return Err(format!("unexpected character '{}' at position {i}", c as char)),
        };
        out.push(t); i += 1;
    }
    Ok(out)
}

// ── Parser (precedence climbing) ─────────────────────────────────────────────

struct Parser {
    tokens: Vec<Tok>,
    pos: usize,
    node_count: usize,
}

impl Parser {
    fn peek(&self) -> Option<&Tok> { self.tokens.get(self.pos) }
    fn advance(&mut self) -> Option<Tok> { let t = self.tokens.get(self.pos).cloned(); if t.is_some() { self.pos += 1; } t }
    fn at(&self, t: &Tok) -> bool { self.peek() == Some(t) }
    fn bump_node(&mut self) -> Result<(), String> {
        self.node_count += 1;
        if self.node_count > MAX_NODES {
            Err(format!("expression too complex (> {MAX_NODES} AST nodes)"))
        } else { Ok(()) }
    }

    fn parse_expr(&mut self) -> Result<Expr, String> { self.parse_add() }

    fn parse_add(&mut self) -> Result<Expr, String> {
        let mut lhs = self.parse_mul()?;
        loop {
            let op = match self.peek() {
                Some(Tok::Plus)  => BinOp::Add,
                Some(Tok::Minus) => BinOp::Sub,
                _ => return Ok(lhs),
            };
            self.advance();
            let rhs = self.parse_mul()?;
            self.bump_node()?;
            lhs = Expr::Bin(op, Box::new(lhs), Box::new(rhs));
        }
    }

    fn parse_mul(&mut self) -> Result<Expr, String> {
        let mut lhs = self.parse_unary()?;
        loop {
            let op = match self.peek() {
                Some(Tok::Star)    => BinOp::Mul,
                Some(Tok::Slash)   => BinOp::Div,
                Some(Tok::Percent) => BinOp::Mod,
                _ => return Ok(lhs),
            };
            self.advance();
            let rhs = self.parse_unary()?;
            self.bump_node()?;
            lhs = Expr::Bin(op, Box::new(lhs), Box::new(rhs));
        }
    }

    fn parse_unary(&mut self) -> Result<Expr, String> {
        if self.at(&Tok::Minus) {
            self.advance();
            let inner = self.parse_unary()?;
            self.bump_node()?;
            return Ok(Expr::Neg(Box::new(inner)));
        }
        if self.at(&Tok::Plus) { self.advance(); return self.parse_unary(); }
        self.parse_pow()
    }

    // Right-associative: a**b**c == a**(b**c). Matches SandboxEngine / JS / Python.
    fn parse_pow(&mut self) -> Result<Expr, String> {
        let lhs = self.parse_primary()?;
        if self.at(&Tok::StarStar) {
            self.advance();
            let rhs = self.parse_unary()?;
            self.bump_node()?;
            return Ok(Expr::Bin(BinOp::Pow, Box::new(lhs), Box::new(rhs)));
        }
        Ok(lhs)
    }

    fn parse_primary(&mut self) -> Result<Expr, String> {
        match self.advance() {
            Some(Tok::Num(v))  => { self.bump_node()?; Ok(Expr::Lit(v)) }
            Some(Tok::LPar)    => {
                let e = self.parse_expr()?;
                if !self.at(&Tok::RPar) { return Err("expected ')'".into()); }
                self.advance();
                Ok(e)
            }
            Some(Tok::Ident(name)) => {
                if self.at(&Tok::LPar) {
                    self.advance();
                    let mut args = Vec::new();
                    if !self.at(&Tok::RPar) {
                        args.push(self.parse_expr()?);
                        while self.at(&Tok::Comma) {
                            self.advance();
                            args.push(self.parse_expr()?);
                        }
                    }
                    if !self.at(&Tok::RPar) { return Err(format!("expected ')' in call to {name}")); }
                    self.advance();
                    self.bump_node()?;
                    Ok(Expr::Call(name, args))
                } else {
                    self.bump_node()?;
                    Ok(Expr::Var(name))
                }
            }
            other => Err(format!("expected expression, got {other:?}")),
        }
    }
}

pub fn parse(formula: &str) -> Result<Expr, String> {
    if formula.len() > MAX_FORMULA_LEN {
        return Err(format!("formula too long ({} > {MAX_FORMULA_LEN})", formula.len()));
    }
    let tokens = tokenize(formula)?;
    if tokens.is_empty() { return Err("empty formula".into()); }
    let mut parser = Parser { tokens, pos: 0, node_count: 0 };
    let expr = parser.parse_expr()?;
    if parser.pos < parser.tokens.len() {
        return Err(format!("unexpected token at position {} (after expression complete)", parser.pos));
    }
    validate(&expr)?;
    Ok(expr)
}

// ── Allowed identifiers / functions ─────────────────────────────────────────

const ALLOWED_VARS: &[&str] = &["n", "pi", "e"];

struct FnSpec { name: &'static str, arity: usize }

const ALLOWED_FNS: &[FnSpec] = &[
    FnSpec { name: "sqrt",   arity: 1 }, FnSpec { name: "cbrt",   arity: 1 },
    FnSpec { name: "log",    arity: 1 }, FnSpec { name: "log2",   arity: 1 },
    FnSpec { name: "log10",  arity: 1 }, FnSpec { name: "exp",    arity: 1 },
    FnSpec { name: "pow",    arity: 2 }, FnSpec { name: "hypot",  arity: 2 },
    FnSpec { name: "sin",    arity: 1 }, FnSpec { name: "cos",    arity: 1 },
    FnSpec { name: "tan",    arity: 1 },
    FnSpec { name: "asin",   arity: 1 }, FnSpec { name: "acos",   arity: 1 },
    FnSpec { name: "atan",   arity: 1 }, FnSpec { name: "atan2",  arity: 2 },
    FnSpec { name: "sinh",   arity: 1 }, FnSpec { name: "cosh",   arity: 1 },
    FnSpec { name: "tanh",   arity: 1 },
    FnSpec { name: "abs",    arity: 1 }, FnSpec { name: "sign",   arity: 1 },
    FnSpec { name: "floor",  arity: 1 }, FnSpec { name: "ceil",   arity: 1 },
    FnSpec { name: "round",  arity: 1 },
    FnSpec { name: "min",    arity: 2 }, FnSpec { name: "max",    arity: 2 },
];

fn allowed_fn(name: &str) -> Option<&'static FnSpec> {
    ALLOWED_FNS.iter().find(|f| f.name == name)
}

fn validate(expr: &Expr) -> Result<(), String> {
    match expr {
        Expr::Lit(_) => Ok(()),
        Expr::Var(name) => {
            if ALLOWED_VARS.contains(&name.as_str()) { Ok(()) }
            else { Err(format!("unknown identifier '{name}' (allowed: n, pi, e)")) }
        }
        Expr::Neg(inner) => validate(inner),
        Expr::Bin(_, l, r) => { validate(l)?; validate(r) }
        Expr::Call(name, args) => {
            let spec = allowed_fn(name).ok_or_else(||
                format!("unknown function '{name}' (allowed: sqrt cbrt log log2 log10 exp pow hypot sin cos tan asin acos atan atan2 sinh cosh tanh abs sign floor ceil round min max)"))?;
            if args.len() != spec.arity {
                return Err(format!("function '{name}' expects {} arg(s), got {}", spec.arity, args.len()));
            }
            for a in args { validate(a)?; }
            Ok(())
        }
    }
}

// ── WGSL emission ────────────────────────────────────────────────────────────

fn emit(expr: &Expr) -> String {
    match expr {
        // Always emit literal as f32 with explicit decimal to satisfy WGSL.
        Expr::Lit(v) => format_f32_literal(*v),
        Expr::Var(name) => match name.as_str() {
            "n"  => "n".to_string(),
            "pi" => "_PI".to_string(),
            "e"  => "_E".to_string(),
            _    => unreachable!("validate() guards this"),
        },
        Expr::Neg(inner) => format!("(-{})", emit(inner)),
        Expr::Bin(op, l, r) => {
            let ls = emit(l); let rs = emit(r);
            match op {
                BinOp::Add => format!("({ls} + {rs})"),
                BinOp::Sub => format!("({ls} - {rs})"),
                BinOp::Mul => format!("({ls} * {rs})"),
                BinOp::Div => format!("({ls} / {rs})"),
                // WGSL has % only for integers; use the IEEE remainder identity:
                //   a - b * trunc(a / b)
                BinOp::Mod => format!("({ls} - {rs} * trunc({ls} / {rs}))"),
                BinOp::Pow => format!("pow({ls}, {rs})"),
            }
        }
        Expr::Call(name, args) => {
            let a: Vec<String> = args.iter().map(emit).collect();
            match name.as_str() {
                "sqrt"|"exp"|"sin"|"cos"|"tan"|"asin"|"acos"|"atan"|"sinh"|"cosh"|"tanh"
                |"abs"|"sign"|"floor"|"ceil"|"round"|"log"|"log2"|"pow"|"atan2"|"min"|"max"
                    => format!("{name}({})", a.join(", ")),
                "log10" => format!("_log10({})", a[0]),
                "cbrt"  => format!("_cbrt({})",  a[0]),
                "hypot" => format!("_hypot({}, {})", a[0], a[1]),
                _ => unreachable!("validate() guards this"),
            }
        }
    }
}

fn format_f32_literal(v: f64) -> String {
    // WGSL distinguishes int from float literals — `1` is i32, `1.0` is f32.
    // Always emit with a decimal point so the shader compiler treats it as f32.
    if v.is_nan() { return "f32(0.0/0.0)".into(); }
    if v.is_infinite() {
        return if v > 0.0 { "f32(1.0/0.0)".into() } else { "f32(-1.0/0.0)".into() };
    }
    let s = format!("{v}");
    if s.contains('.') || s.contains('e') || s.contains('E') { s } else { format!("{s}.0") }
}

pub fn to_wgsl(expr: &Expr) -> String {
    let body = emit(expr);
    let wg = WORKGROUP_SIZE;
    format!(r#"
const _PI: f32 = 3.14159265358979323846;
const _E:  f32 = 2.71828182845904523846;

struct Params {{
    start:  i32,
    length: u32,
    pad0:   u32,
    pad1:   u32,
}}

@group(0) @binding(0) var<storage, read_write> outBuf: array<f32>;
@group(0) @binding(1) var<uniform>             params: Params;

fn _log10(x: f32) -> f32 {{ return log(x) / log(10.0); }}
fn _cbrt(x: f32)  -> f32 {{ return sign(x) * pow(abs(x), 1.0 / 3.0); }}
fn _hypot(a: f32, b: f32) -> f32 {{ return sqrt(a * a + b * b); }}

@compute @workgroup_size({wg}, 1, 1)
fn main(@builtin(global_invocation_id) gid: vec3<u32>) {{
    let i = gid.x;
    if (i >= params.length) {{ return; }}
    let n: f32 = f32(i32(i) + params.start);
    outBuf[i] = {body};
}}
"#)
}

// ── Uniform layout for the shader ───────────────────────────────────────────

#[repr(C)]
#[derive(Copy, Clone, Pod, Zeroable)]
struct EvalParams {
    start:  i32,
    length: u32,
    pad0:   u32,
    pad1:   u32,
}

// ── Dispatch + aggregate ────────────────────────────────────────────────────

pub fn run_on_gpu(device: &wgpu::Device, queue: &wgpu::Queue,
                  formula: &str, start: i32, end: i32,
                  request_id: &str) -> Result<EvalAggregate, Status> {
    if end <= start {
        return Err(Status::invalid_argument(format!("range must be non-empty: start={start}, end={end}")));
    }
    let length_i64 = (end as i64) - (start as i64);
    if length_i64 > MAX_RANGE {
        return Err(Status::invalid_argument(
            format!("range too large: {length_i64} samples (max {MAX_RANGE})")));
    }
    let length = length_i64 as u32;

    let t_parse = Instant::now();
    let expr = parse(formula).map_err(|e|
        Status::invalid_argument(format!("formula parse failed: {e}")))?;
    let shader_src = to_wgsl(&expr);
    let parse_ms = t_parse.elapsed().as_micros();
    println!("eval_expr.parse request_id={request_id} formula_len={} ast_nodes_approx={} wgsl_len={} parse_us={parse_ms}",
             formula.len(), count_nodes(&expr), shader_src.len());

    let t0 = Instant::now();

    let t_compile = Instant::now();
    let module = device.create_shader_module(wgpu::ShaderModuleDescriptor {
        label:  Some("eval_expr_shader"),
        source: wgpu::ShaderSource::Wgsl(std::borrow::Cow::Owned(shader_src)),
    });
    let layout = crate::create_bind_group_layout(device, "eval_expr_bgl",
        &[crate::storage_layout(0, false), crate::uniform_layout(1)]);
    let pipeline = crate::create_compute_pipeline(device, "eval_expr_pipeline", &layout, &module);
    let compile_ms = t_compile.elapsed().as_millis();

    let out_bytes = (length as u64) * std::mem::size_of::<f32>() as u64;
    let storage_buf = crate::create_storage_buffer(device, "eval_expr_out", out_bytes);
    let params = EvalParams { start, length, pad0: 0, pad1: 0 };
    let uniform_buf = crate::create_uniform_buffer(device, queue, "eval_expr_params", &params);
    let readback_buf = crate::create_readback_buffer(device, "eval_expr_readback", out_bytes);

    let bind_group = device.create_bind_group(&wgpu::BindGroupDescriptor {
        label: Some("eval_expr_bg"),
        layout: &layout,
        entries: &[crate::bind_entry(0, &storage_buf), crate::bind_entry(1, &uniform_buf)],
    });

    let groups = (length + WORKGROUP_SIZE - 1) / WORKGROUP_SIZE;
    println!("eval_expr.dispatch request_id={request_id} length={length} workgroups={groups} buf_bytes={out_bytes} compile_ms={compile_ms}");

    let t_dispatch = Instant::now();
    let mut encoder = device.create_command_encoder(&wgpu::CommandEncoderDescriptor {
        label: Some("eval_expr_encoder"),
    });
    {
        let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
            label: Some("eval_expr_pass"),
            timestamp_writes: None,
        });
        pass.set_pipeline(&pipeline);
        pass.set_bind_group(0, &bind_group, &[]);
        pass.dispatch_workgroups(groups, 1, 1);
    }
    encoder.copy_buffer_to_buffer(&storage_buf, 0, &readback_buf, 0, out_bytes);
    queue.submit(std::iter::once(encoder.finish()));
    let dispatch_ms = t_dispatch.elapsed().as_millis();

    let t_readback = Instant::now();
    let values: Vec<f32> = crate::map_pod_buffer(device, &readback_buf, length as usize)?;
    let readback_ms = t_readback.elapsed().as_millis();

    let agg = aggregate(&values);
    let millis = t0.elapsed().as_millis() as u64;
    println!("eval_expr.done request_id={request_id} total_ms={millis} dispatch_ms={dispatch_ms} readback_ms={readback_ms} finite={}/{} mean={:.6} std={:.6}",
             agg.finite_count, agg.n, agg.mean, agg.std_dev);
    Ok(EvalAggregate { millis, ..agg })
}

/// Cheap recursive node counter for log-only visibility into formula
/// complexity. Not used for validation; the AST-node cap lives in the
/// parser's bump_node hook.
fn count_nodes(e: &Expr) -> usize {
    match e {
        Expr::Lit(_) | Expr::Var(_) => 1,
        Expr::Neg(inner) => 1 + count_nodes(inner),
        Expr::Bin(_, l, r) => 1 + count_nodes(l) + count_nodes(r),
        Expr::Call(_, args) => 1 + args.iter().map(count_nodes).sum::<usize>(),
    }
}

pub fn aggregate(values: &[f32]) -> EvalAggregate {
    let n_total = values.len() as u64;
    let mut sum: f64    = 0.0;
    let mut sum_sq: f64 = 0.0;
    let mut min:  f64   =  f64::INFINITY;
    let mut max:  f64   = -f64::INFINITY;
    let mut finite_count: u32 = 0;
    for &v in values {
        let vd = v as f64;
        if !vd.is_finite() { continue; }
        finite_count += 1;
        sum    += vd;
        sum_sq += vd * vd;
        if vd < min { min = vd; }
        if vd > max { max = vd; }
    }
    let fc = finite_count as f64;
    let (mean, std_dev) = if finite_count == 0 {
        (f64::NAN, f64::NAN)
    } else if finite_count == 1 {
        (sum, 0.0)
    } else {
        let mean = sum / fc;
        let var  = (sum_sq / fc) - mean * mean;
        (mean, var.max(0.0).sqrt())
    };
    if finite_count == 0 { min = f64::NAN; max = f64::NAN; }
    EvalAggregate { n: n_total, finite_count, sum, mean, std_dev, min, max, millis: 0 }
}

// ── Tests ────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    fn parses(s: &str) -> Expr { parse(s).expect(s) }

    #[test]
    fn tokenizer_handles_scientific_notation() {
        assert_eq!(parses("1.5e-3"), Expr::Lit(1.5e-3));
        assert_eq!(parses(".5"),     Expr::Lit(0.5));
        assert_eq!(parses("1e9"),    Expr::Lit(1e9));
    }

    #[test]
    fn precedence_multiplicative_binds_tighter_than_additive() {
        let e = parses("1 + 2 * 3");
        assert_eq!(e, Expr::Bin(BinOp::Add,
            Box::new(Expr::Lit(1.0)),
            Box::new(Expr::Bin(BinOp::Mul, Box::new(Expr::Lit(2.0)), Box::new(Expr::Lit(3.0))))));
    }

    #[test]
    fn power_is_right_associative() {
        let e = parses("2 ** 3 ** 2");
        assert_eq!(e, Expr::Bin(BinOp::Pow,
            Box::new(Expr::Lit(2.0)),
            Box::new(Expr::Bin(BinOp::Pow, Box::new(Expr::Lit(3.0)), Box::new(Expr::Lit(2.0))))));
    }

    #[test]
    fn unary_minus_binds_to_pow() {
        // `-2**2` parses as `-(2**2) = -4`, mirroring Python (not JavaScript).
        let e = parses("-2 ** 2");
        assert_eq!(e, Expr::Neg(Box::new(Expr::Bin(BinOp::Pow,
            Box::new(Expr::Lit(2.0)), Box::new(Expr::Lit(2.0))))));
    }

    #[test]
    fn allowed_identifiers_parse() {
        for v in &["n", "pi", "e"] {
            assert_eq!(parses(v), Expr::Var(v.to_string()));
        }
    }

    #[test]
    fn unknown_identifier_rejected() {
        assert!(parse("foo").is_err());
        assert!(parse("x").is_err()); // even single letters not on the allow list
    }

    #[test]
    fn unknown_function_rejected() {
        let err = parse("zorp(1)").unwrap_err();
        assert!(err.contains("unknown function"));
    }

    #[test]
    fn wrong_arity_rejected() {
        let err = parse("pow(2)").unwrap_err();
        assert!(err.contains("expects 2"));
        let err2 = parse("sqrt(1, 2)").unwrap_err();
        assert!(err2.contains("expects 1"));
    }

    #[test]
    fn complexity_cap_trips() {
        // Each "+1" contributes 2 AST nodes (Add + Lit); pick the count so
        // the formula stays well below MAX_FORMULA_LEN but overshoots
        // MAX_NODES. Confirms the node cap, not the length cap, is the
        // binding constraint here.
        let terms = (MAX_NODES / 2) + 5;          // → ~MAX_NODES+9 AST nodes
        let formula: String = "1".to_string() + &"+1".repeat(terms);
        assert!(formula.len() < MAX_FORMULA_LEN,
                "test built a formula longer than MAX_FORMULA_LEN; node cap not exercised");
        let err = parse(&formula).unwrap_err();
        assert!(err.contains("too complex"),
                "expected 'too complex' error, got: {err}");
    }

    #[test]
    fn wgsl_emits_named_constants() {
        let wgsl = to_wgsl(&parses("pi * n + e"));
        assert!(wgsl.contains("_PI"));
        assert!(wgsl.contains("_E"));
        assert!(wgsl.contains("n"));
    }

    #[test]
    fn wgsl_emits_helper_for_log10_cbrt_hypot() {
        assert!(to_wgsl(&parses("log10(n)")).contains("_log10"));
        assert!(to_wgsl(&parses("cbrt(n)")).contains("_cbrt"));
        assert!(to_wgsl(&parses("hypot(n, 1)")).contains("_hypot"));
    }

    #[test]
    fn literals_always_have_decimal_in_wgsl() {
        // `1` would be parsed by WGSL as i32, breaking f32 arithmetic. The
        // emitter must add `.0` to all bare integers.
        let wgsl = to_wgsl(&parses("1 + 2"));
        assert!(wgsl.contains("1.0"));
        assert!(wgsl.contains("2.0"));
    }

    #[test]
    fn aggregate_handles_empty_slice() {
        let agg = aggregate(&[]);
        assert_eq!(agg.finite_count, 0);
        assert!(agg.mean.is_nan());
        assert!(agg.std_dev.is_nan());
    }

    #[test]
    fn aggregate_ignores_non_finite_values() {
        let vals: Vec<f32> = vec![1.0, 2.0, f32::NAN, 3.0, f32::INFINITY, 4.0];
        let agg = aggregate(&vals);
        assert_eq!(agg.finite_count, 4);
        assert!((agg.mean - 2.5).abs() < 1e-6);
        assert!((agg.sum  - 10.0).abs() < 1e-6);
        assert!((agg.min  - 1.0).abs() < 1e-6);
        assert!((agg.max  - 4.0).abs() < 1e-6);
    }

    #[test]
    fn aggregate_std_dev_known_dataset() {
        // 1, 2, 3, 4, 5 → mean 3, variance 2 (population), std ≈ √2 ≈ 1.414
        let agg = aggregate(&[1.0, 2.0, 3.0, 4.0, 5.0]);
        assert!((agg.mean - 3.0).abs() < 1e-6);
        assert!((agg.std_dev - (2.0f64).sqrt()).abs() < 1e-6);
    }
}
