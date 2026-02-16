use std::net::SocketAddr;

use tonic::{Request, Response, Status};

pub mod gpu {
    tonic::include_proto!("emergentterrain.gpu.v1");
}

use gpu::gpu_terrain_service_server::{GpuTerrainService, GpuTerrainServiceServer};
use gpu::{
    BoolGrid, GenerateRegionRequest, GenerateRegionResponse, RebuildPatchRequest,
    RebuildPatchResponse, SimulateBotsRequest, SimulateBotsResponse, TileEncoding, TileGrid,
};

#[derive(Default)]
struct GpuEngineService;

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
            encoding: TileEncoding::TileEncodingU8 as i32,
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
            encoding: TileEncoding::TileEncodingU8 as i32,
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
        let response = SimulateBotsResponse {
            request_id: req.request_id,
            bots: req.bots,
            edits: Vec::new(),
            debug_json: "{}".to_string(),
        };
        Ok(Response::new(response))
    }
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
