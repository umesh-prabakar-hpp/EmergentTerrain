//! Phase 20.9 — per-pipeline source organization.
//!
//! Step 1 of the trait split: each compute pipeline's WGSL shader moves
//! into its own file. Future steps will lift the build/dispatch logic
//! (currently inlined in `GpuComputeEngine::new` and `generate_region` /
//! `rebuild_patch` / `simulate_bots_with_buffers` in `main.rs`) into a
//! `ComputePipeline` trait so adding a fourth pipeline (e.g., the
//! flow-field / distance-transform candidate from
//! `changes-for-gpu-based-app.md`) becomes a one-file addition rather
//! than an edit to `main.rs`.
//!
//! Target shape (TBD in follow-up commits):
//!
//! ```ignore
//! pub trait ComputePipeline {
//!     type Request;
//!     type Response;
//!     fn build(device: &wgpu::Device, queue: &wgpu::Queue) -> Self;
//!     fn dispatch(&self, req: Self::Request) -> Result<Self::Response, tonic::Status>;
//!     fn kind() -> PipelineKind;
//! }
//! ```
//!
//! Current step 1 deliverable: shader constants live next to where
//! their bind-group layouts and uniform structs eventually will. No
//! behavior change — `main.rs` imports the shader constants from these
//! modules instead of declaring them inline.

pub mod world;
pub mod patch;
pub mod bots;
