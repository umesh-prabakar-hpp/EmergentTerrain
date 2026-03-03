use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::Arc;

use tokio::sync::RwLock;
use tonic::{Request, Response, Status};

pub mod gpu {
    tonic::include_proto!("emergentterrain.gpu.v1");
}

use gpu::gpu_terrain_service_server::{GpuTerrainService, GpuTerrainServiceServer};
use gpu::{
    BoolGrid, BotState, GenerateRegionRequest, GenerateRegionResponse, RebuildPatchRequest,
    RebuildPatchResponse, SimulateBotsRequest, SimulateBotsResponse, TileEdit, TileEncoding, TileGrid,
};

#[derive(Clone, Default)]
struct GpuEngineService {
    worlds: Arc<RwLock<HashMap<String, CachedWorld>>>,
}

#[derive(Clone)]
struct CachedWorld {
    width: u32,
    height: u32,
    tiles: Vec<u8>,
    highways: Vec<u8>,
}

impl CachedWorld {
    fn size(&self) -> usize {
        (self.width as usize).saturating_mul(self.height as usize)
    }
}

#[tonic::async_trait]
impl GpuTerrainService for GpuEngineService {
    async fn generate_region(
        &self,
        request: Request<GenerateRegionRequest>,
    ) -> Result<Response<GenerateRegionResponse>, Status> {
        let req = request.into_inner();
        let region = req.region.unwrap_or_default();
        let size = (region.width as usize).saturating_mul(region.height as usize);
        let tiles = TileGrid {
            width: region.width,
            height: region.height,
            tiles: vec![0; size],
            encoding: TileEncoding::U8 as i32,
        };
        let highways = BoolGrid {
            width: region.width,
            height: region.height,
            values: vec![0; size],
        };
        let response = GenerateRegionResponse {
            request_id: req.request_id,
            tiles: Some(tiles),
            highways: Some(highways),
            debug_json: "{}".to_string(),
        };
        Ok(Response::new(response))
    }

    async fn rebuild_patch(
        &self,
        request: Request<RebuildPatchRequest>,
    ) -> Result<Response<RebuildPatchResponse>, Status> {
        let req = request.into_inner();
        let patch = req.patch.unwrap_or_default();
        let size = (patch.width as usize).saturating_mul(patch.height as usize);
        let patch_tiles = TileGrid {
            width: patch.width,
            height: patch.height,
            tiles: vec![0; size],
            encoding: TileEncoding::U8 as i32,
        };
        let patch_highways = BoolGrid {
            width: patch.width,
            height: patch.height,
            values: vec![0; size],
        };
        let response = RebuildPatchResponse {
            request_id: req.request_id,
            patch: Some(patch),
            patch_tiles: Some(patch_tiles),
            patch_highways: Some(patch_highways),
            debug_json: "{}".to_string(),
        };
        Ok(Response::new(response))
    }

    async fn simulate_bots(
        &self,
        request: Request<SimulateBotsRequest>,
    ) -> Result<Response<SimulateBotsResponse>, Status> {
        let req = request.into_inner();
        let steps = if req.steps == 0 { 1 } else { req.steps };
        let (width, height, tiles, highways) = if !req.world_id.is_empty() {
            if let (Some(tile_grid), Some(highway_grid)) = (req.tiles.as_ref(), req.highways.as_ref()) {
                let (w, h, tiles) = decode_tile_grid(tile_grid)?;
                let highways = decode_bool_grid(highway_grid, w, h)?;
                let mut world = CachedWorld {
                    width: w,
                    height: h,
                    tiles,
                    highways,
                };
                if !req.tile_edits.is_empty() {
                    apply_tile_edits(&mut world, &req.tile_edits);
                }
                self.worlds.write().await.insert(req.world_id.clone(), world);
            }
            if !req.tile_edits.is_empty() && req.tiles.is_none() {
                let mut worlds = self.worlds.write().await;
                let world = worlds
                    .get_mut(&req.world_id)
                    .ok_or_else(|| Status::not_found("world_id not found"))?;
                apply_tile_edits(world, &req.tile_edits);
            }
            let worlds = self.worlds.read().await;
            let world = worlds
                .get(&req.world_id)
                .ok_or_else(|| Status::not_found("world_id not found"))?;
            (world.width, world.height, world.tiles.clone(), world.highways.clone())
        } else {
            let tile_grid = req
                .tiles
                .as_ref()
                .ok_or_else(|| Status::invalid_argument("tiles required when world_id is empty"))?;
            let highway_grid = req
                .highways
                .as_ref()
                .ok_or_else(|| Status::invalid_argument("highways required when world_id is empty"))?;
            let (w, h, mut tiles) = decode_tile_grid(tile_grid)?;
            let mut highways = decode_bool_grid(highway_grid, w, h)?;
            if !req.tile_edits.is_empty() {
                let mut world = CachedWorld {
                    width: w,
                    height: h,
                    tiles,
                    highways,
                };
                apply_tile_edits(&mut world, &req.tile_edits);
                tiles = world.tiles;
                highways = world.highways;
            }
            (w, h, tiles, highways)
        };

        let mut bots = req.bots;
        if !bots.is_empty() {
            let width_i = width as i32;
            let height_i = height as i32;
            for step in 0..steps {
                for (idx, bot) in bots.iter_mut().enumerate() {
                    let (forest, water, industry, clear, mobility) = bot_preferences(bot);
                    if !should_move(step, idx, bot.x, bot.y, mobility) {
                        continue;
                    }
                    let mut best_x = bot.x;
                    let mut best_y = bot.y;
                    let mut best_score = f32::MIN;
                    let dirs = [(1, 0), (-1, 0), (0, 1), (0, -1)];
                    for (dx, dy) in dirs {
                        let nx = bot.x + dx;
                        let ny = bot.y + dy;
                        if nx < 0 || ny < 0 || nx >= width_i || ny >= height_i {
                            continue;
                        }
                        let index = (ny as usize) * (width as usize) + (nx as usize);
                        let tile = tiles.get(index).copied().unwrap_or(0);
                        let mut score = match tile {
                            1 => forest,
                            2 => water,
                            3 => industry,
                            4 => clear,
                            _ => clear,
                        };
                        let highway = highways.get(index).copied().unwrap_or(0) != 0;
                        if highway || tile == 4 {
                            score += 0.2;
                        }
                        if score > best_score {
                            best_score = score;
                            best_x = nx;
                            best_y = ny;
                        }
                    }
                    bot.x = best_x;
                    bot.y = best_y;
                }
            }
        }

        let response = SimulateBotsResponse {
            request_id: req.request_id,
            bots,
            edits: Vec::new(),
            debug_json: "{}".to_string(),
        };
        Ok(Response::new(response))
    }
}

fn decode_tile_grid(grid: &TileGrid) -> Result<(u32, u32, Vec<u8>), Status> {
    let width = grid.width;
    let height = grid.height;
    let size = (width as usize).saturating_mul(height as usize);
    if size == 0 {
        return Err(Status::invalid_argument("tiles grid is empty"));
    }
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
                let clamped = value.clamp(0, 255) as u8;
                tiles.push(clamped);
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
    let size = (width as usize).saturating_mul(height as usize);
    if grid.values.len() != size {
        return Err(Status::invalid_argument("highways buffer size mismatch"));
    }
    Ok(grid.values.clone())
}

fn apply_tile_edits(world: &mut CachedWorld, edits: &[TileEdit]) {
    let width = world.width as i32;
    let height = world.height as i32;
    for edit in edits {
        let x = edit.x;
        let y = edit.y;
        if x < 0 || y < 0 || x >= width || y >= height {
            continue;
        }
        let index = (y as usize) * (world.width as usize) + (x as usize);
        if edit.r#type == 4 {
            world.tiles[index] = 0;
            if index < world.highways.len() {
                world.highways[index] = 1;
            }
        } else {
            world.tiles[index] = edit.r#type as u8;
            if index < world.highways.len() {
                world.highways[index] = 0;
            }
        }
    }
}

fn bot_preferences(bot: &BotState) -> (f32, f32, f32, f32, f32) {
    let all_zero = bot.forest == 0.0
        && bot.water == 0.0
        && bot.industry == 0.0
        && bot.clear == 0.0
        && bot.mobility == 0.0;
    if all_zero {
        (0.5, 0.5, 0.5, 0.5, 0.5)
    } else {
        (
            clamp01(bot.forest),
            clamp01(bot.water),
            clamp01(bot.industry),
            clamp01(bot.clear),
            clamp01(bot.mobility),
        )
    }
}

fn clamp01(value: f32) -> f32 {
    if value < 0.0 {
        0.0
    } else if value > 1.0 {
        1.0
    } else {
        value
    }
}

fn should_move(step: u32, idx: usize, x: i32, y: i32, mobility: f32) -> bool {
    let seed = (step as u64)
        .wrapping_mul(1_103_515_245)
        .wrapping_add((idx as u64).wrapping_mul(1_442_695_040_888_963_407))
        .wrapping_add((x as u64).wrapping_mul(31))
        .wrapping_add((y as u64).wrapping_mul(17));
    let bucket = (seed % 10_000) as f32 / 10_000.0;
    bucket <= mobility
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let addr: SocketAddr = std::env::var("GPU_ENGINE_ADDR")
        .unwrap_or_else(|_| "127.0.0.1:50051".to_string())
        .parse()?;

    println!("GPU engine gRPC server listening on {}", addr);

    tonic::transport::Server::builder()
        .add_service(GpuTerrainServiceServer::new(GpuEngineService::default()))
        .serve(addr)
        .await?;

    Ok(())
}
