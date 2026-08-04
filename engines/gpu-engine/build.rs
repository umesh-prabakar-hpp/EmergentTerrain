fn main() {
    tonic_build::compile_protos("proto/terrain_gpu.proto")
        .expect("failed to compile gRPC proto");
}
