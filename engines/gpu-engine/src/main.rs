use std::borrow::Cow;
use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::Arc;

use bytemuck::{Pod, Zeroable};
use serde_json::{json, Value};
use tokio::sync::RwLock;
use tonic::{Request, Response, Status};

pub mod gpu {
    tonic::include_proto!("emergentterrain.gpu.v1");
}

mod config;
mod eval_expr;
mod pipelines;

use config::WorldConfig;
// Phase 20.9 — shader constants live under pipelines/{world,patch,bots}.rs.
// main.rs imports them by their canonical SHADER name from each module
// (e.g. pipelines::world::SHADER) to keep the per-pipeline source
// colocated. Build/dispatch logic still lives in GpuComputeEngine for now;
// follow-up commits will migrate it to a ComputePipeline trait.
use pipelines::{world, patch, bots};

use gpu::gpu_terrain_service_server::{GpuTerrainService, GpuTerrainServiceServer};
use gpu::{
    BoolGrid, BotState, EvalExprRequest, EvalExprResponse, GenerateRegionRequest,
    GenerateRegionResponse, PipelineStats, Progress, RebuildPatchRequest, RebuildPatchResponse,
    SimulateBotsRequest, SimulateBotsResponse, SimulateBotsUpdate, TileEdit, TileEncoding, TileGrid,
};

// Phase 20.9 — shader constants extracted to pipelines/{world,patch,bots}.rs.
// Aliased here to preserve the existing WORLD_SHADER / PATCH_SHADER /
// BOT_SHADER names used by GpuComputeEngine::new without further main.rs
// edits. The aliases let follow-up commits (which will migrate the
// build/dispatch logic into a ComputePipeline trait) move incrementally.
const WORLD_SHADER: &str = world::SHADER;
const PATCH_SHADER: &str = patch::SHADER;
const BOT_SHADER: &str = bots::SHADER;

#[repr(C)]
#[derive(Clone, Copy, Pod, Zeroable)]
struct WorldUniform {
    width: u32,
    height: u32,
    seed_lo: u32,
    seed_hi: u32,
    forest_threshold: u32,
    water_threshold: u32,
    industry_threshold: u32,
    spacing: u32,
}

#[repr(C)]
#[derive(Clone, Copy, Pod, Zeroable)]
struct PatchUniform {
    source_width: u32,
    source_height: u32,
    patch_x: i32,
    patch_y: i32,
    patch_width: u32,
    patch_height: u32,
    _pad0: u32,
    _pad1: u32,
}

#[repr(C)]
#[derive(Clone, Copy, Pod, Zeroable)]
struct BotUniform {
    width: u32,
    height: u32,
    bot_count: u32,
    step: u32,
}

#[repr(C)]
#[derive(Clone, Copy, Pod, Zeroable)]
struct GpuBotState {
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
    _pad2: f32,
}

struct GpuComputeEngine {
    device: wgpu::Device,
    queue: wgpu::Queue,
    world_pipeline: wgpu::ComputePipeline,
    world_layout: wgpu::BindGroupLayout,
    patch_pipeline: wgpu::ComputePipeline,
    patch_layout: wgpu::BindGroupLayout,
    bot_pipeline: wgpu::ComputePipeline,
    bot_layout: wgpu::BindGroupLayout,
    adapter_name: String,
    backend_name: String,
    // Phase 20.10 — world-generation tunables loaded from gpu.toml at
    // startup. Default-constructed when the file is absent or malformed,
    // matching the pre-Phase-20.10 hardcoded values bit-for-bit.
    world_config: WorldConfig,
}

struct GeneratedWorld {
    tiles: Vec<u8>,
    highways: Vec<u8>,
    debug_json: String,
}

struct GeneratedPatch {
    tiles: Vec<u8>,
    highways: Vec<u8>,
    debug_json: String,
}

struct SimulatedBots {
    bots: Vec<BotState>,
    debug_json: String,
}

const INVALID_BOT_POS: i32 = i32::MIN;

#[derive(Clone, Default)]
struct BotMotionMemory {
    last_x: Option<i32>,
    last_y: Option<i32>,
    heading_x: i32,
    heading_y: i32,
    reverse_lock: i32,
    plan: Vec<(i32, i32)>,
}

#[derive(Clone, Default)]
struct HiddenParams {
    pressure: f64,
    humidity: f64,
    density: f64,
    energy: f64,
}

#[derive(Clone)]
struct GpuEngineService {
    worlds: Arc<RwLock<HashMap<String, CachedWorld>>>,
    gpu: Arc<GpuComputeEngine>,
}

#[derive(Clone)]
struct CachedWorld {
    width: u32,
    height: u32,
    tiles: Vec<u8>,
    highways: Vec<u8>,
    gpu_tiles: Arc<wgpu::Buffer>,
    gpu_highways: Arc<wgpu::Buffer>,
}

impl GpuComputeEngine {
    async fn new() -> Result<Self, Box<dyn std::error::Error>> {
        let instance = wgpu::Instance::default();
        let adapter = instance
            .request_adapter(&wgpu::RequestAdapterOptions {
                power_preference: wgpu::PowerPreference::HighPerformance,
                compatible_surface: None,
                force_fallback_adapter: false,
            })
            .await
            .ok_or("no GPU adapter available")?;
        let adapter_info = adapter.get_info();
        let (device, queue) = adapter
            .request_device(
                &wgpu::DeviceDescriptor {
                    label: Some("emergent-terrain-gpu-device"),
                    required_features: wgpu::Features::empty(),
                    required_limits: wgpu::Limits::downlevel_defaults(),
                },
                None,
            )
            .await?;

        let world_shader = device.create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("world-shader"),
            source: wgpu::ShaderSource::Wgsl(Cow::Borrowed(WORLD_SHADER)),
        });
        let patch_shader = device.create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("patch-shader"),
            source: wgpu::ShaderSource::Wgsl(Cow::Borrowed(PATCH_SHADER)),
        });
        let bot_shader = device.create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("bot-shader"),
            source: wgpu::ShaderSource::Wgsl(Cow::Borrowed(BOT_SHADER)),
        });

        let world_layout = create_bind_group_layout(&device, "world-layout", &[storage_layout(0, false), storage_layout(1, false), uniform_layout(2)]);
        let patch_layout = create_bind_group_layout(&device, "patch-layout", &[storage_layout(0, true), storage_layout(1, true), storage_layout(2, false), storage_layout(3, false), uniform_layout(4)]);
        let bot_layout = create_bind_group_layout(&device, "bot-layout", &[storage_layout(0, true), storage_layout(1, true), storage_layout(2, true), storage_layout(3, false), uniform_layout(4)]);

        Ok(Self {
            world_pipeline: create_compute_pipeline(&device, "world-pipeline", &world_layout, &world_shader),
            patch_pipeline: create_compute_pipeline(&device, "patch-pipeline", &patch_layout, &patch_shader),
            bot_pipeline: create_compute_pipeline(&device, "bot-pipeline", &bot_layout, &bot_shader),
            world_layout,
            patch_layout,
            bot_layout,
            device,
            queue,
            adapter_name: adapter_info.name,
            backend_name: format!("{:?}", adapter_info.backend),
            world_config: WorldConfig::load(),
        })
    }

    fn adapter_summary(&self) -> String {
        format!("{} via {}", self.adapter_name, self.backend_name)
    }

    fn generate_region(&self, seed: u64, width: u32, height: u32, params_json: &str) -> Result<GeneratedWorld, Status> {
        // Phase 20.10 — density derivation coefficients + hidden-param
        // defaults + highway spacing constants are all sourced from
        // self.world_config (loaded from gpu.toml at startup; defaults
        // reproduce the pre-Phase-20.10 hardcoded values bit-for-bit).
        let params = parse_hidden_params(params_json, &self.world_config.hidden_defaults);
        let highway_ratio = parse_highway_ratio(params_json, self.world_config.highways.ratio_default);
        let forest = &self.world_config.density.forest;
        let water = &self.world_config.density.water;
        let industry = &self.world_config.density.industry;
        let forest_density = forest.base + forest.humidity_coef * params.humidity + forest.density_coef * params.density;
        let water_density = water.base + water.humidity_coef * params.humidity + water.energy_coef * params.energy;
        let industry_density = industry.base + industry.pressure_coef * params.pressure;
        let uniform = WorldUniform {
            width,
            height,
            seed_lo: seed as u32,
            seed_hi: (seed >> 32) as u32,
            forest_threshold: threshold_u32(forest_density),
            water_threshold: threshold_u32(water_density),
            industry_threshold: threshold_u32(industry_density),
            spacing: highway_spacing(highway_ratio, &self.world_config.highways),
        };

        let size = grid_size(width, height)?;
        let bytes = u32_buffer_bytes(size);
        let tiles_buffer = create_storage_buffer(&self.device, "world-tiles", bytes);
        let highways_buffer = create_storage_buffer(&self.device, "world-highways", bytes);
        let uniform_buffer = create_uniform_buffer(&self.device, &self.queue, "world-uniform", &uniform);
        let bind_group = self.device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("world-bind-group"),
            layout: &self.world_layout,
            entries: &[bind_entry(0, &tiles_buffer), bind_entry(1, &highways_buffer), bind_entry(2, &uniform_buffer)],
        });
        let read_tiles = create_readback_buffer(&self.device, "world-tiles-readback", bytes);
        let read_highways = create_readback_buffer(&self.device, "world-highways-readback", bytes);

        let mut encoder = self.device.create_command_encoder(&wgpu::CommandEncoderDescriptor { label: Some("world-encoder") });
        {
            let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor { label: Some("world-pass"), timestamp_writes: None });
            pass.set_pipeline(&self.world_pipeline);
            pass.set_bind_group(0, &bind_group, &[]);
            pass.dispatch_workgroups(width.div_ceil(8), height.div_ceil(8), 1);
        }
        encoder.copy_buffer_to_buffer(&tiles_buffer, 0, &read_tiles, 0, bytes);
        encoder.copy_buffer_to_buffer(&highways_buffer, 0, &read_highways, 0, bytes);
        self.queue.submit(Some(encoder.finish()));

        let tiles = u32_to_u8(&map_pod_buffer::<u32>(&self.device, &read_tiles, size)?);
        let highways = u32_to_bool_bytes(&map_pod_buffer::<u32>(&self.device, &read_highways, size)?);
        let non_zero_tiles = tiles.iter().filter(|&&tile| tile != 0).count();
        let highway_count = highways.iter().filter(|&&cell| cell != 0).count();

        Ok(GeneratedWorld {
            tiles,
            highways,
            debug_json: json!({
                "backend": "wgpu-world",
                "adapter": self.adapter_summary(),
                "forestDensity": forest_density,
                "waterDensity": water_density,
                "industryDensity": industry_density,
                "highwayRatio": highway_ratio,
                "nonZeroTiles": non_zero_tiles,
                "highwayCount": highway_count
            }).to_string(),
        })
    }

    fn create_world_buffers(&self, tiles: &[u8], highways: &[u8]) -> (Arc<wgpu::Buffer>, Arc<wgpu::Buffer>) {
        (
            Arc::new(create_initialized_storage_buffer(&self.device, &self.queue, "cached-world-tiles", &u8_to_u32(tiles))),
            Arc::new(create_initialized_storage_buffer(&self.device, &self.queue, "cached-world-highways", &u8_to_u32(highways))),
        )
    }

    fn rewrite_world_buffers(&self, world: &CachedWorld) {
        let tile_values = u8_to_u32(&world.tiles);
        let highway_values = u8_to_u32(&world.highways);
        self.queue.write_buffer(&world.gpu_tiles, 0, bytemuck::cast_slice(&tile_values));
        self.queue.write_buffer(&world.gpu_highways, 0, bytemuck::cast_slice(&highway_values));
    }

    fn rebuild_patch(&self, width: u32, height: u32, source_tiles: &[u8], source_highways: &[u8], patch: &gpu::Rect) -> Result<GeneratedPatch, Status> {
        let source_size = grid_size(width, height)?;
        if source_tiles.len() != source_size || source_highways.len() != source_size {
            return Err(Status::invalid_argument("source world buffer size mismatch"));
        }
        let patch_size = grid_size(patch.width, patch.height)?;
        let source_tiles_buffer = create_initialized_storage_buffer(&self.device, &self.queue, "patch-source-tiles", &u8_to_u32(source_tiles));
        let source_highways_buffer = create_initialized_storage_buffer(&self.device, &self.queue, "patch-source-highways", &u8_to_u32(source_highways));
        let patch_bytes = u32_buffer_bytes(patch_size);
        let patch_tiles_buffer = create_storage_buffer(&self.device, "patch-tiles", patch_bytes);
        let patch_highways_buffer = create_storage_buffer(&self.device, "patch-highways", patch_bytes);
        let uniform = PatchUniform {
            source_width: width,
            source_height: height,
            patch_x: patch.x,
            patch_y: patch.y,
            patch_width: patch.width,
            patch_height: patch.height,
            _pad0: 0,
            _pad1: 0,
        };
        let uniform_buffer = create_uniform_buffer(&self.device, &self.queue, "patch-uniform", &uniform);
        let bind_group = self.device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("patch-bind-group"),
            layout: &self.patch_layout,
            entries: &[
                bind_entry(0, &source_tiles_buffer),
                bind_entry(1, &source_highways_buffer),
                bind_entry(2, &patch_tiles_buffer),
                bind_entry(3, &patch_highways_buffer),
                bind_entry(4, &uniform_buffer),
            ],
        });
        let read_tiles = create_readback_buffer(&self.device, "patch-tiles-readback", patch_bytes);
        let read_highways = create_readback_buffer(&self.device, "patch-highways-readback", patch_bytes);

        let mut encoder = self.device.create_command_encoder(&wgpu::CommandEncoderDescriptor { label: Some("patch-encoder") });
        {
            let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor { label: Some("patch-pass"), timestamp_writes: None });
            pass.set_pipeline(&self.patch_pipeline);
            pass.set_bind_group(0, &bind_group, &[]);
            pass.dispatch_workgroups(patch.width.div_ceil(8), patch.height.div_ceil(8), 1);
        }
        encoder.copy_buffer_to_buffer(&patch_tiles_buffer, 0, &read_tiles, 0, patch_bytes);
        encoder.copy_buffer_to_buffer(&patch_highways_buffer, 0, &read_highways, 0, patch_bytes);
        self.queue.submit(Some(encoder.finish()));

        Ok(GeneratedPatch {
            tiles: u32_to_u8(&map_pod_buffer::<u32>(&self.device, &read_tiles, patch_size)?),
            highways: u32_to_bool_bytes(&map_pod_buffer::<u32>(&self.device, &read_highways, patch_size)?),
            debug_json: json!({
                "backend": "wgpu-patch",
                "adapter": self.adapter_summary(),
                "patchWidth": patch.width,
                "patchHeight": patch.height
            }).to_string(),
        })
    }

    fn simulate_bots_cached(&self, world: &CachedWorld, bots: &[BotState], steps: u32) -> Result<SimulatedBots, Status> {
        self.simulate_bots_with_buffers(
            world.width,
            world.height,
            world.gpu_tiles.as_ref(),
            world.gpu_highways.as_ref(),
            bots,
            steps,
        )
    }

    fn simulate_bots_with_buffers(&self,
                                  width: u32,
                                  height: u32,
                                  tiles_buffer: &wgpu::Buffer,
                                  highways_buffer: &wgpu::Buffer,
                                  bots: &[BotState],
                                  steps: u32) -> Result<SimulatedBots, Status> {
        if bots.is_empty() {
            return Ok(SimulatedBots {
                bots: Vec::new(),
                debug_json: json!({
                    "backend": "wgpu-bot-sim",
                    "adapter": self.adapter_summary(),
                    "steps": steps.max(1),
                    "botCount": 0
                }).to_string(),
            });
        }

        let bot_a = create_initialized_storage_buffer(&self.device, &self.queue, "bot-a", &bots_to_gpu(bots));
        let bot_b = create_storage_buffer(&self.device, "bot-b", (bots.len() * std::mem::size_of::<GpuBotState>()) as u64);
        let uniform_buffer = self.device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("bot-uniform"),
            size: std::mem::size_of::<BotUniform>() as u64,
            usage: wgpu::BufferUsages::UNIFORM | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });

        let mut current_in = &bot_a;
        let mut current_out = &bot_b;
        for step in 0..steps.max(1) {
            let uniform = BotUniform { width, height, bot_count: bots.len() as u32, step };
            self.queue.write_buffer(&uniform_buffer, 0, bytemuck::bytes_of(&uniform));
            let bind_group = self.device.create_bind_group(&wgpu::BindGroupDescriptor {
                label: Some("bot-bind-group"),
                layout: &self.bot_layout,
                entries: &[bind_entry(0, tiles_buffer), bind_entry(1, highways_buffer), bind_entry(2, current_in), bind_entry(3, current_out), bind_entry(4, &uniform_buffer)],
            });
            let mut encoder = self.device.create_command_encoder(&wgpu::CommandEncoderDescriptor { label: Some("bot-encoder") });
            {
                let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor { label: Some("bot-pass"), timestamp_writes: None });
                pass.set_pipeline(&self.bot_pipeline);
                pass.set_bind_group(0, &bind_group, &[]);
                pass.dispatch_workgroups((bots.len() as u32).div_ceil(64), 1, 1);
            }
            self.queue.submit(Some(encoder.finish()));
            std::mem::swap(&mut current_in, &mut current_out);
        }

        let bot_bytes = (bots.len() * std::mem::size_of::<GpuBotState>()) as u64;
        let read_bots = create_readback_buffer(&self.device, "bot-readback", bot_bytes);
        let mut encoder = self.device.create_command_encoder(&wgpu::CommandEncoderDescriptor { label: Some("bot-readback-encoder") });
        encoder.copy_buffer_to_buffer(current_in, 0, &read_bots, 0, bot_bytes);
        self.queue.submit(Some(encoder.finish()));

        let gpu_bots = map_pod_buffer::<GpuBotState>(&self.device, &read_bots, bots.len())?;
        let mut updated_bots = bots.to_vec();
        for (bot, gpu_bot) in updated_bots.iter_mut().zip(gpu_bots.iter()) {
            bot.x = gpu_bot.x;
            bot.y = gpu_bot.y;
            bot.state = encode_bot_memory(gpu_bot);
        }
        let moved_count = bots.iter().zip(updated_bots.iter()).filter(|(before, after)| before.x != after.x || before.y != after.y).count();

        Ok(SimulatedBots {
            bots: updated_bots,
            debug_json: json!({
                "backend": "wgpu-bot-sim",
                "adapter": self.adapter_summary(),
                "steps": steps.max(1),
                "botCount": bots.len(),
                "movedBots": moved_count
            }).to_string(),
        })
    }
}

#[tonic::async_trait]
impl GpuTerrainService for GpuEngineService {
    async fn generate_region(&self, request: Request<GenerateRegionRequest>) -> Result<Response<GenerateRegionResponse>, Status> {
        let req = request.into_inner();
        let region = req.region.unwrap_or_default();
        println!("generate_region.enter request_id={} size={}x{} seed={} adapter={}",
                 req.request_id, region.width, region.height, req.seed, self.gpu.adapter_summary());
        let t0 = std::time::Instant::now();
        let generated = self.gpu.generate_region(req.seed, region.width, region.height, &req.params_json)?;
        let latency_us = t0.elapsed().as_micros() as u64;
        // Phase 20.7 — WORLD pipeline cost model.
        //   bytes_read: ~uniform (32B) is negligible vs the output writes.
        //   bytes_written: 2 storage buffers (tiles, highways), each W*H*4 bytes.
        //   flops_estimate: WORLD_SHADER's per-pixel body is ~3 mix_hash calls
        //                   (each ~7 ops: 4 XORs + 2 muls + 1 shift) + a few
        //                   compares + 1 select + 1 modulo pair → ~25 ops/pixel.
        let pixels = (region.width as u64) * (region.height as u64);
        let bytes_written = pixels * 4 * 2;
        let flops_estimate = pixels * 25;
        let stats = build_pipeline_stats("WORLD", latency_us, 32, bytes_written, flops_estimate);
        println!("generate_region.exit request_id={} size={}x{} ms={} latency_us={} mem_bw_gbps={:.2} flops_tflops={:.4} debug={}",
                 req.request_id, region.width, region.height,
                 t0.elapsed().as_millis(), latency_us, stats.mem_bw_gbps, stats.flops_tflops, generated.debug_json);
        Ok(Response::new(GenerateRegionResponse {
            request_id: req.request_id,
            tiles: Some(TileGrid { width: region.width, height: region.height, tiles: generated.tiles, encoding: TileEncoding::U8 as i32 }),
            highways: Some(BoolGrid { width: region.width, height: region.height, values: generated.highways }),
            debug_json: generated.debug_json,
            stats: Some(stats),
        }))
    }

    async fn rebuild_patch(&self, request: Request<RebuildPatchRequest>) -> Result<Response<RebuildPatchResponse>, Status> {
        let req = request.into_inner();
        let patch = req.patch.unwrap_or_default();
        println!("rebuild_patch.enter request_id={} patch={}x{} edits={} adapter={}",
                 req.request_id, patch.width, patch.height, req.applied_edits.len(),
                 self.gpu.adapter_summary());
        let t0 = std::time::Instant::now();
        let tile_grid = req.tiles.as_ref().ok_or_else(|| Status::invalid_argument("tiles required"))?;
        let highway_grid = req.highways.as_ref().ok_or_else(|| Status::invalid_argument("highways required"))?;
        let (width, height, tiles) = decode_tile_grid(tile_grid)?;
        let highways = decode_bool_grid(highway_grid, width, height)?;
        let mut world = CachedWorld::new(self.gpu.as_ref(), width, height, tiles, highways);
        if !req.applied_edits.is_empty() {
            apply_tile_edits(&mut world, &req.applied_edits);
        }
        let rebuilt = self.gpu.rebuild_patch(width, height, &world.tiles, &world.highways, &patch)?;
        let latency_us = t0.elapsed().as_micros() as u64;
        // Phase 20.7 — PATCH pipeline cost model.
        //   bytes_read: source tile + highway grids (2 * W*H*4) + uniform.
        //   bytes_written: 2 patch buffers (patch_tiles, patch_highways), each P*4.
        //   flops_estimate: per-patch-pixel ~3 ops (bounds check + index + copy).
        //                   This kernel is memory-bound by design.
        let patch_pixels = (patch.width as u64) * (patch.height as u64);
        let source_pixels = (width as u64) * (height as u64);
        let bytes_read = source_pixels * 4 * 2 + 32;
        let bytes_written = patch_pixels * 4 * 2;
        let flops_estimate = patch_pixels * 3;
        let stats = build_pipeline_stats("PATCH", latency_us, bytes_read, bytes_written, flops_estimate);
        println!("rebuild_patch.exit request_id={} patch={}x{} ms={} latency_us={} mem_bw_gbps={:.2} flops_tflops={:.4} debug={}",
                 req.request_id, patch.width, patch.height,
                 t0.elapsed().as_millis(), latency_us, stats.mem_bw_gbps, stats.flops_tflops, rebuilt.debug_json);
        Ok(Response::new(RebuildPatchResponse {
            request_id: req.request_id,
            patch: Some(patch.clone()),
            patch_tiles: Some(TileGrid { width: patch.width, height: patch.height, tiles: rebuilt.tiles, encoding: TileEncoding::U8 as i32 }),
            patch_highways: Some(BoolGrid { width: patch.width, height: patch.height, values: rebuilt.highways }),
            debug_json: rebuilt.debug_json,
            stats: Some(stats),
        }))
    }

    /// Phase 20.8 — server-streaming variant of simulate_bots. Emits a
    /// "dispatch" Progress at the start, then one Progress per simulated
    /// step (when steps > 1), then the terminal Done frame carrying the
    /// same SimulateBotsResponse shape as the unary variant. The unary
    /// simulate_bots stays in place for existing clients; this is purely
    /// additive.
    ///
    /// Today's per-step granularity is best-effort: the underlying
    /// simulate_bots_cached runs the whole multi-step loop inside one
    /// GPU dispatch sequence. Per-step progress arrives in a single
    /// burst at submission time rather than as the GPU actually completes
    /// each step. A real per-step hookup needs a callback into
    /// simulate_bots_with_buffers' step loop — separate follow-up when
    /// there's a real long-pole SimulateBots worth instrumenting.
    type SimulateBotsV2Stream = std::pin::Pin<Box<
        dyn tokio_stream::Stream<Item = Result<SimulateBotsUpdate, Status>> + Send>>;

    async fn simulate_bots_v2(&self, request: Request<SimulateBotsRequest>)
            -> Result<Response<Self::SimulateBotsV2Stream>, Status> {
        let req = request.into_inner();
        let steps = req.steps.max(1);
        println!("simulate_bots_v2.enter request_id={} bots={} steps={} adapter={}",
                 req.request_id, req.bots.len(), steps, self.gpu.adapter_summary());

        // Resolve the world up front so any not-found error surfaces as a
        // pre-stream Status (cleaner than emitting via the stream).
        let world = self.resolve_world_for_bot_request(&req).await?;
        let gpu = self.gpu.clone();
        let request_id = req.request_id.clone();
        let bots = req.bots.clone();

        let (tx, rx) = tokio::sync::mpsc::channel::<Result<SimulateBotsUpdate, Status>>(8);
        tokio::spawn(async move {
            // "dispatch" — emitted before the GPU work starts.
            let _ = tx.send(Ok(SimulateBotsUpdate {
                payload: Some(gpu::simulate_bots_update::Payload::Progress(Progress {
                    completed: 0,
                    total: steps,
                    current_op: "dispatch".to_string(),
                })),
            })).await;

            // Per-step Progress placeholders. simulate_bots_cached runs the
            // full multi-step loop in one shot today; we emit per-step
            // markers in a single burst after the dispatch returns, which is
            // honest about today's granularity (caller sees "dispatch"
            // → all-steps → "done"). A future change to expose a per-step
            // callback would turn these into real-time updates.
            let t0 = std::time::Instant::now();
            let simulated = match gpu.simulate_bots_cached(&world, &bots, steps) {
                Ok(s) => s,
                Err(status) => {
                    let _ = tx.send(Err(status)).await;
                    return;
                }
            };
            let latency_us = t0.elapsed().as_micros() as u64;

            for step in 0..steps {
                let _ = tx.send(Ok(SimulateBotsUpdate {
                    payload: Some(gpu::simulate_bots_update::Payload::Progress(Progress {
                        completed: step + 1,
                        total: steps,
                        current_op: format!("step:{}", step),
                    })),
                })).await;
            }

            // Final stats — same model as the unary handler.
            let bot_count = simulated.bots.len() as u64;
            let world_pixels = (world.width as u64) * (world.height as u64);
            let bot_struct_bytes = 80u64;
            let bytes_read = world_pixels * 4 * 2 + bot_count * bot_struct_bytes + 32;
            let bytes_written = bot_count * bot_struct_bytes;
            let flops_estimate = bot_count * (steps as u64) * 80;
            let stats = build_pipeline_stats("BOT", latency_us, bytes_read, bytes_written, flops_estimate);

            println!("simulate_bots_v2.exit request_id={} bots={} steps={} latency_us={} mem_bw_gbps={:.2} flops_tflops={:.4}",
                     request_id, bots.len(), steps, latency_us, stats.mem_bw_gbps, stats.flops_tflops);

            let done = SimulateBotsUpdate {
                payload: Some(gpu::simulate_bots_update::Payload::Done(SimulateBotsResponse {
                    request_id,
                    bots: simulated.bots,
                    edits: Vec::new(),
                    debug_json: simulated.debug_json,
                    stats: Some(stats),
                })),
            };
            let _ = tx.send(Ok(done)).await;
        });

        let stream = tokio_stream::wrappers::ReceiverStream::new(rx);
        Ok(Response::new(Box::pin(stream)))
    }

    async fn simulate_bots(&self, request: Request<SimulateBotsRequest>) -> Result<Response<SimulateBotsResponse>, Status> {
        let req = request.into_inner();
        let steps = req.steps.max(1);
        println!("simulate_bots.enter request_id={} bots={} steps={} cached_world={} world_id=\"{}\" adapter={}",
                 req.request_id, req.bots.len(), steps, !req.world_id.is_empty(),
                 req.world_id, self.gpu.adapter_summary());
        let t0 = std::time::Instant::now();
        let world = self.resolve_world_for_bot_request(&req).await?;
        let simulated = self.gpu.simulate_bots_cached(&world, &req.bots, steps)?;
        let latency_us = t0.elapsed().as_micros() as u64;
        // Phase 20.7 — BOT pipeline cost model.
        //   bytes_read: tile + highway grids + bots_in (each bot ~80B of state).
        //   bytes_written: bots_out (same shape as bots_in).
        //   flops_estimate: per bot per step the kernel does ~80 ops (tile
        //                   preference scoring, 4 neighbor checks, RNG hash,
        //                   plan-position decoding, reverse-lock decay).
        let bot_count = simulated.bots.len() as u64;
        let world_pixels = (world.width as u64) * (world.height as u64);
        let bot_struct_bytes = 80u64;
        let bytes_read = world_pixels * 4 * 2 + bot_count * bot_struct_bytes + 32;
        let bytes_written = bot_count * bot_struct_bytes;
        let flops_estimate = bot_count * (steps as u64) * 80;
        let stats = build_pipeline_stats("BOT", latency_us, bytes_read, bytes_written, flops_estimate);
        println!("simulate_bots.exit request_id={} bots={} steps={} ms={} latency_us={} mem_bw_gbps={:.2} flops_tflops={:.4} debug={}",
                 req.request_id, req.bots.len(), steps,
                 t0.elapsed().as_millis(), latency_us, stats.mem_bw_gbps, stats.flops_tflops, simulated.debug_json);
        Ok(Response::new(SimulateBotsResponse {
            request_id: req.request_id,
            bots: simulated.bots,
            edits: Vec::new(),
            debug_json: simulated.debug_json,
            stats: Some(stats),
        }))
    }

    // Phase 8 Tier 4 — P4.2 real implementation. Parses the formula into an
    // AST, generates WGSL, dispatches a compute shader over [start, end), and
    // aggregates the f32 output buffer on the CPU. Strict subset of
    // SandboxEngine syntax (no control flow, no user functions); see
    // eval_expr::parse for the grammar.
    async fn eval_expr(&self, request: Request<EvalExprRequest>) -> Result<Response<EvalExprResponse>, Status> {
        let req = request.into_inner();
        println!("eval_expr.enter request_id={} formula=\"{}\" range=[{},{}) adapter={}",
                 req.request_id, req.formula, req.start, req.end, self.gpu.adapter_summary());
        let agg = eval_expr::run_on_gpu(&self.gpu.device, &self.gpu.queue,
                                         &req.formula, req.start, req.end,
                                         &req.request_id)?;
        println!("eval_expr.exit request_id={} ms={} finite={}/{} mean={:.6} std={:.6} min={:.6} max={:.6} sum={:.6}",
                 req.request_id, agg.millis, agg.finite_count, agg.n,
                 agg.mean, agg.std_dev, agg.min, agg.max, agg.sum);
        let debug = serde_json::json!({
            "millis": agg.millis,
            "adapter": self.gpu.adapter_summary(),
        }).to_string();
        Ok(Response::new(EvalExprResponse {
            request_id: req.request_id,
            mean: agg.mean,
            std_dev: agg.std_dev,
            min: agg.min,
            max: agg.max,
            sum: agg.sum,
            n: agg.n,
            finite_count: agg.finite_count,
            debug_json: debug,
        }))
    }
}

impl GpuEngineService {
    async fn resolve_world_for_bot_request(&self, req: &SimulateBotsRequest) -> Result<CachedWorld, Status> {
        if !req.world_id.is_empty() {
            if let (Some(tile_grid), Some(highway_grid)) = (req.tiles.as_ref(), req.highways.as_ref()) {
                let (w, h, tiles) = decode_tile_grid(tile_grid)?;
                let highways = decode_bool_grid(highway_grid, w, h)?;
                let mut world = CachedWorld::new(self.gpu.as_ref(), w, h, tiles, highways);
                if !req.tile_edits.is_empty() {
                    apply_tile_edits(&mut world, &req.tile_edits);
                    self.gpu.rewrite_world_buffers(&world);
                }
                self.worlds.write().await.insert(req.world_id.clone(), world.clone());
            }
            if !req.tile_edits.is_empty() && req.tiles.is_none() {
                let mut guard = self.worlds.write().await;
                let world = guard.get_mut(&req.world_id).ok_or_else(|| Status::not_found("world_id not found"))?;
                apply_tile_edits(world, &req.tile_edits);
                self.gpu.rewrite_world_buffers(world);
                return Ok(world.clone());
            }
            let guard = self.worlds.read().await;
            let world = guard.get(&req.world_id).ok_or_else(|| Status::not_found("world_id not found"))?;
            return Ok(world.clone());
        }

        let tile_grid = req.tiles.as_ref().ok_or_else(|| Status::invalid_argument("tiles required when world_id is empty"))?;
        let highway_grid = req.highways.as_ref().ok_or_else(|| Status::invalid_argument("highways required when world_id is empty"))?;
        let (w, h, tiles) = decode_tile_grid(tile_grid)?;
        let highways = decode_bool_grid(highway_grid, w, h)?;
        let mut world = CachedWorld::new(self.gpu.as_ref(), w, h, tiles, highways);
        if !req.tile_edits.is_empty() {
            apply_tile_edits(&mut world, &req.tile_edits);
            self.gpu.rewrite_world_buffers(&world);
        }
        Ok(world)
    }
}

impl CachedWorld {
    fn new(gpu: &GpuComputeEngine, width: u32, height: u32, tiles: Vec<u8>, highways: Vec<u8>) -> Self {
        let (gpu_tiles, gpu_highways) = gpu.create_world_buffers(&tiles, &highways);
        Self {
            width,
            height,
            tiles,
            highways,
            gpu_tiles,
            gpu_highways,
        }
    }
}

pub(crate) fn create_bind_group_layout(device: &wgpu::Device, label: &str, entries: &[wgpu::BindGroupLayoutEntry]) -> wgpu::BindGroupLayout {
    device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor { label: Some(label), entries })
}

pub(crate) fn create_compute_pipeline(device: &wgpu::Device, label: &str, layout: &wgpu::BindGroupLayout, shader: &wgpu::ShaderModule) -> wgpu::ComputePipeline {
    let pipeline_layout = device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
        label: Some(label),
        bind_group_layouts: &[layout],
        push_constant_ranges: &[],
    });
    device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
        label: Some(label),
        layout: Some(&pipeline_layout),
        module: shader,
        entry_point: "main",
        compilation_options: Default::default(),
    })
}

pub(crate) fn storage_layout(binding: u32, read_only: bool) -> wgpu::BindGroupLayoutEntry {
    wgpu::BindGroupLayoutEntry {
        binding,
        visibility: wgpu::ShaderStages::COMPUTE,
        ty: wgpu::BindingType::Buffer {
            ty: wgpu::BufferBindingType::Storage { read_only },
            has_dynamic_offset: false,
            min_binding_size: None,
        },
        count: None,
    }
}

pub(crate) fn uniform_layout(binding: u32) -> wgpu::BindGroupLayoutEntry {
    wgpu::BindGroupLayoutEntry {
        binding,
        visibility: wgpu::ShaderStages::COMPUTE,
        ty: wgpu::BindingType::Buffer {
            ty: wgpu::BufferBindingType::Uniform,
            has_dynamic_offset: false,
            min_binding_size: None,
        },
        count: None,
    }
}

pub(crate) fn create_storage_buffer(device: &wgpu::Device, label: &str, size: u64) -> wgpu::Buffer {
    device.create_buffer(&wgpu::BufferDescriptor {
        label: Some(label),
        size,
        usage: wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_SRC | wgpu::BufferUsages::COPY_DST,
        mapped_at_creation: false,
    })
}

fn create_initialized_storage_buffer<T: Pod>(device: &wgpu::Device, queue: &wgpu::Queue, label: &str, values: &[T]) -> wgpu::Buffer {
    let buffer = create_storage_buffer(device, label, std::mem::size_of_val(values) as u64);
    queue.write_buffer(&buffer, 0, bytemuck::cast_slice(values));
    buffer
}

pub(crate) fn create_uniform_buffer<T: Pod>(device: &wgpu::Device, queue: &wgpu::Queue, label: &str, value: &T) -> wgpu::Buffer {
    let buffer = device.create_buffer(&wgpu::BufferDescriptor {
        label: Some(label),
        size: std::mem::size_of::<T>() as u64,
        usage: wgpu::BufferUsages::UNIFORM | wgpu::BufferUsages::COPY_DST,
        mapped_at_creation: false,
    });
    queue.write_buffer(&buffer, 0, bytemuck::bytes_of(value));
    buffer
}

pub(crate) fn create_readback_buffer(device: &wgpu::Device, label: &str, size: u64) -> wgpu::Buffer {
    device.create_buffer(&wgpu::BufferDescriptor {
        label: Some(label),
        size,
        usage: wgpu::BufferUsages::MAP_READ | wgpu::BufferUsages::COPY_DST,
        mapped_at_creation: false,
    })
}

pub(crate) fn bind_entry<'a>(binding: u32, buffer: &'a wgpu::Buffer) -> wgpu::BindGroupEntry<'a> {
    wgpu::BindGroupEntry { binding, resource: buffer.as_entire_binding() }
}

fn grid_size(width: u32, height: u32) -> Result<usize, Status> {
    let size = (width as usize).saturating_mul(height as usize);
    if size == 0 {
        return Err(Status::invalid_argument("grid size must be non-zero"));
    }
    Ok(size)
}

fn u32_buffer_bytes(len: usize) -> u64 {
    (len * std::mem::size_of::<u32>()) as u64
}

pub(crate) fn map_pod_buffer<T: Pod>(device: &wgpu::Device, buffer: &wgpu::Buffer, expected_len: usize) -> Result<Vec<T>, Status> {
    let slice = buffer.slice(..);
    let (tx, rx) = std::sync::mpsc::sync_channel(1);
    slice.map_async(wgpu::MapMode::Read, move |result| {
        let _ = tx.send(result);
    });
    device.poll(wgpu::Maintain::Wait);
    rx.recv()
        .map_err(|_| Status::internal("failed to read GPU buffer"))?
        .map_err(|e| Status::internal(format!("failed to map GPU buffer: {e}")))?;
    let data = slice.get_mapped_range();
    let values = bytemuck::cast_slice::<u8, T>(&data).to_vec();
    drop(data);
    buffer.unmap();
    if values.len() != expected_len {
        return Err(Status::internal("GPU buffer length mismatch"));
    }
    Ok(values)
}

fn u8_to_u32(values: &[u8]) -> Vec<u32> {
    values.iter().map(|&value| value as u32).collect()
}

fn u32_to_u8(values: &[u32]) -> Vec<u8> {
    values.iter().map(|&value| value.min(255) as u8).collect()
}

fn u32_to_bool_bytes(values: &[u32]) -> Vec<u8> {
    pack_bool_bytes(values.iter().map(|&value| value != 0))
}

fn pack_bool_bytes<I>(values: I) -> Vec<u8>
where
    I: IntoIterator<Item = bool>,
{
    let mut packed = Vec::new();
    let mut byte = 0u8;
    let mut bit_index = 0usize;
    for value in values {
        if value {
            byte |= 1 << bit_index;
        }
        bit_index += 1;
        if bit_index == 8 {
            packed.push(byte);
            byte = 0;
            bit_index = 0;
        }
    }
    if bit_index > 0 {
        packed.push(byte);
    }
    packed
}

fn bots_to_gpu(bots: &[BotState]) -> Vec<GpuBotState> {
    bots.iter().map(|bot| {
        let (forest, water, industry, clear, mobility) = bot_preferences(bot);
        let memory = decode_bot_memory(bot);
        GpuBotState {
            x: bot.x,
            y: bot.y,
            prev_x: memory.last_x.unwrap_or(INVALID_BOT_POS),
            prev_y: memory.last_y.unwrap_or(INVALID_BOT_POS),
            heading_x: memory.heading_x,
            heading_y: memory.heading_y,
            reverse_lock: memory.reverse_lock,
            plan_len: memory.plan.len().min(2) as i32,
            plan0_x: memory.plan.first().map(|step| step.0).unwrap_or(INVALID_BOT_POS),
            plan0_y: memory.plan.first().map(|step| step.1).unwrap_or(INVALID_BOT_POS),
            plan1_x: memory.plan.get(1).map(|step| step.0).unwrap_or(INVALID_BOT_POS),
            plan1_y: memory.plan.get(1).map(|step| step.1).unwrap_or(INVALID_BOT_POS),
            forest,
            water,
            industry,
            clear,
            mobility,
            _pad0: 0.0,
            _pad1: 0.0,
            _pad2: 0.0,
        }
    }).collect()
}

fn decode_bot_memory(bot: &BotState) -> BotMotionMemory {
    if bot.state.len() < 40 {
        return BotMotionMemory::default();
    }
    let bytes = &bot.state;
    let read_i32 = |offset: usize| -> i32 {
        i32::from_le_bytes(bytes[offset..offset + 4].try_into().unwrap())
    };
    let last_x = read_i32(0);
    let last_y = read_i32(4);
    let mut memory = BotMotionMemory {
        last_x: if last_x == INVALID_BOT_POS { None } else { Some(last_x) },
        last_y: if last_y == INVALID_BOT_POS { None } else { Some(last_y) },
        heading_x: read_i32(8),
        heading_y: read_i32(12),
        reverse_lock: read_i32(16).max(0),
        plan: Vec::new(),
    };
    let plan_len = read_i32(20).clamp(0, 2) as usize;
    let plan0_x = read_i32(24);
    let plan0_y = read_i32(28);
    let plan1_x = read_i32(32);
    let plan1_y = read_i32(36);
    if plan_len > 0 && plan0_x != INVALID_BOT_POS && plan0_y != INVALID_BOT_POS {
        memory.plan.push((plan0_x, plan0_y));
    }
    if plan_len > 1 && plan1_x != INVALID_BOT_POS && plan1_y != INVALID_BOT_POS {
        memory.plan.push((plan1_x, plan1_y));
    }
    memory
}

fn encode_bot_memory(gpu_bot: &GpuBotState) -> Vec<u8> {
    let mut bytes = Vec::with_capacity(40);
    bytes.extend_from_slice(&gpu_bot.prev_x.to_le_bytes());
    bytes.extend_from_slice(&gpu_bot.prev_y.to_le_bytes());
    bytes.extend_from_slice(&gpu_bot.heading_x.to_le_bytes());
    bytes.extend_from_slice(&gpu_bot.heading_y.to_le_bytes());
    bytes.extend_from_slice(&gpu_bot.reverse_lock.max(0).to_le_bytes());
    bytes.extend_from_slice(&gpu_bot.plan_len.clamp(0, 2).to_le_bytes());
    bytes.extend_from_slice(&gpu_bot.plan0_x.to_le_bytes());
    bytes.extend_from_slice(&gpu_bot.plan0_y.to_le_bytes());
    bytes.extend_from_slice(&gpu_bot.plan1_x.to_le_bytes());
    bytes.extend_from_slice(&gpu_bot.plan1_y.to_le_bytes());
    bytes
}

fn decode_tile_grid(grid: &TileGrid) -> Result<(u32, u32, Vec<u8>), Status> {
    let width = grid.width;
    let height = grid.height;
    let size = grid_size(width, height)?;
    let encoding = TileEncoding::try_from(grid.encoding).unwrap_or(TileEncoding::U8);
    match encoding {
        TileEncoding::U8 => {
            if grid.tiles.len() != size {
                return Err(Status::invalid_argument("tiles buffer size mismatch"));
            }
            Ok((width, height, grid.tiles.clone()))
        }
        TileEncoding::I32 => {
            if grid.tiles.len() != size * 4 {
                return Err(Status::invalid_argument("tiles buffer size mismatch"));
            }
            let mut tiles = Vec::with_capacity(size);
            for chunk in grid.tiles.chunks_exact(4) {
                let value = i32::from_le_bytes([chunk[0], chunk[1], chunk[2], chunk[3]]);
                tiles.push(value.clamp(0, 255) as u8);
            }
            Ok((width, height, tiles))
        }
        _ => {
            if grid.tiles.len() != size {
                return Err(Status::invalid_argument("tiles buffer size mismatch"));
            }
            Ok((width, height, grid.tiles.clone()))
        }
    }
}

fn decode_bool_grid(grid: &BoolGrid, width: u32, height: u32) -> Result<Vec<u8>, Status> {
    let size = grid_size(width, height)?;
    if grid.values.len() == size {
        return Ok(grid.values.clone());
    }
    let packed_len = size.div_ceil(8);
    if grid.values.len() == packed_len {
        let mut unpacked = vec![0u8; size];
        for (index, cell) in unpacked.iter_mut().enumerate() {
            let byte = grid.values[index / 8];
            *cell = if (byte >> (index % 8)) & 1 == 0 { 0 } else { 1 };
        }
        return Ok(unpacked);
    }
    Err(Status::invalid_argument("highways buffer size mismatch"))
}

fn apply_tile_edits(world: &mut CachedWorld, edits: &[TileEdit]) {
    let width = world.width as i32;
    let height = world.height as i32;
    for edit in edits {
        if edit.x < 0 || edit.y < 0 || edit.x >= width || edit.y >= height {
            continue;
        }
        let index = (edit.y as usize) * (world.width as usize) + (edit.x as usize);
        if edit.r#type == 4 {
            world.tiles[index] = 0;
            world.highways[index] = 1;
        } else {
            world.tiles[index] = edit.r#type as u8;
            world.highways[index] = 0;
        }
    }
}

fn bot_preferences(bot: &BotState) -> (f32, f32, f32, f32, f32) {
    let all_zero = bot.forest == 0.0 && bot.water == 0.0 && bot.industry == 0.0 && bot.clear == 0.0 && bot.mobility == 0.0;
    if all_zero {
        (0.5, 0.5, 0.5, 0.5, 0.5)
    } else {
        (clamp01(bot.forest), clamp01(bot.water), clamp01(bot.industry), clamp01(bot.clear), clamp01(bot.mobility))
    }
}

fn clamp01(value: f32) -> f32 {
    if value < 0.0 { 0.0 } else if value > 1.0 { 1.0 } else { value }
}

fn parse_hidden_params(params_json: &str, defaults: &config::HiddenParamDefaults) -> HiddenParams {
    let value = serde_json::from_str::<Value>(params_json).unwrap_or(Value::Null);
    let hidden = value.get("hidden").cloned().unwrap_or(Value::Null);
    HiddenParams {
        pressure: read_f64(&hidden, "pressure", defaults.pressure),
        humidity: read_f64(&hidden, "humidity", defaults.humidity),
        density: read_f64(&hidden, "density", defaults.density),
        energy: read_f64(&hidden, "energy", defaults.energy),
    }
}

fn parse_highway_ratio(params_json: &str, default_ratio: f64) -> f64 {
    let value = serde_json::from_str::<Value>(params_json).unwrap_or(Value::Null);
    read_f64(&value, "highwayRatio", default_ratio)
}

fn read_f64(value: &Value, key: &str, default: f64) -> f64 {
    value.get(key).and_then(|v| v.as_f64()).unwrap_or(default)
}

fn threshold_u32(probability: f64) -> u32 {
    (probability.clamp(0.0, 1.0) * (u32::MAX as f64)) as u32
}

/// Phase 20.7 — assemble a PipelineStats proto from raw counters.
///
/// `mem_bw_gbps` and `flops_tflops` are derived: bandwidth in GB/s =
/// (bytes_read + bytes_written) / (latency_us * 1e3), TFLOPS =
/// flops_estimate / (latency_us * 1e6). Both yield 0 when latency_us is
/// zero (vanishingly rare; protects against div-by-zero in tests).
///
/// The handler-side cost model is rough by design — workgroup-size
/// changes show up as a percentage drop in the right metric, which is
/// what we want from regression detection. Use GetEngineCapabilities
/// (future RPC) plus the absolute peak rates to derive MFU / MBU %.
fn build_pipeline_stats(name: &str, latency_us: u64, bytes_read: u64, bytes_written: u64, flops_estimate: u64) -> PipelineStats {
    let total_bytes = bytes_read + bytes_written;
    let (mem_bw_gbps, flops_tflops) = if latency_us == 0 {
        (0.0, 0.0)
    } else {
        let lat = latency_us as f64;
        // bytes / (us * 1e3) = bytes / ns = GB/s
        let mem_bw = (total_bytes as f64) / (lat * 1_000.0);
        // flops / (us * 1e6) = flops / s / 1e12 = TFLOPS
        let flops = (flops_estimate as f64) / (lat * 1_000_000.0);
        (mem_bw, flops)
    };
    PipelineStats {
        pipeline_name: name.to_string(),
        latency_us,
        bytes_read,
        bytes_written,
        flops_estimate,
        mem_bw_gbps,
        flops_tflops,
    }
}

fn highway_spacing(highway_ratio: f64, cfg: &config::HighwayConfig) -> u32 {
    (2.0 / highway_ratio.clamp(cfg.ratio_min_clamp, cfg.ratio_max_clamp))
        .sqrt()
        .round()
        .max(cfg.min_spacing) as u32
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let addr: SocketAddr = std::env::var("GPU_ENGINE_ADDR").unwrap_or_else(|_| "127.0.0.1:50051".to_string()).parse()?;
    println!("startup.init pid={} bind={}", std::process::id(), addr);
    let t_init = std::time::Instant::now();
    let gpu = Arc::new(GpuComputeEngine::new().await?);
    println!("startup.adapter init_ms={} {}",
             t_init.elapsed().as_millis(), gpu.adapter_summary());
    println!("startup.ready listening_on={} services=[GenerateRegion,RebuildPatch,SimulateBots,EvalExpr]", addr);
    tonic::transport::Server::builder()
        .add_service(GpuTerrainServiceServer::new(GpuEngineService {
            worlds: Arc::new(RwLock::new(HashMap::new())),
            gpu,
        }))
        .serve(addr)
        .await?;
    Ok(())
}
