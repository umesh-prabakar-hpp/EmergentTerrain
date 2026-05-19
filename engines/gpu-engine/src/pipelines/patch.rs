//! Patch-rebuild compute shader. Copies a sub-rectangle (source_x ..
//! source_x+patch_width × source_y .. source_y+patch_height) from the
//! cached world's tiles/highways buffers into the patch output buffers.
//! Memory-bound by design — used after a tile edit consensus applies,
//! so the Java side can re-render only the changed region rather than
//! re-fetching the whole world.

pub const SHADER: &str = r#"
struct Params { source_width: u32, source_height: u32, patch_x: i32, patch_y: i32, patch_width: u32, patch_height: u32, _pad0: u32, _pad1: u32 }
@group(0) @binding(0) var<storage, read> source_tiles: array<u32>;
@group(0) @binding(1) var<storage, read> source_highways: array<u32>;
@group(0) @binding(2) var<storage, read_write> patch_tiles: array<u32>;
@group(0) @binding(3) var<storage, read_write> patch_highways: array<u32>;
@group(0) @binding(4) var<uniform> params: Params;
@compute @workgroup_size(8, 8, 1)
fn main(@builtin(global_invocation_id) gid: vec3<u32>) {
  let px = gid.x; let py = gid.y;
  if (px >= params.patch_width || py >= params.patch_height) { return; }
  let sx = params.patch_x + i32(px); let sy = params.patch_y + i32(py);
  let out_idx = py * params.patch_width + px;
  if (sx < 0 || sy < 0 || sx >= i32(params.source_width) || sy >= i32(params.source_height)) {
    patch_tiles[out_idx] = 0u; patch_highways[out_idx] = 0u; return;
  }
  let src_idx = u32(sy) * params.source_width + u32(sx);
  patch_tiles[out_idx] = source_tiles[src_idx];
  patch_highways[out_idx] = source_highways[src_idx];
}
"#;
