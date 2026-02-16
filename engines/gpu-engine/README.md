# EmergentTerrain GPU Engine

Rust gRPC service (tonic) that will host the Vulkan compute engine.

## Rust Toolchain (Windows)
Install via winget:
```powershell
winget install --id Rustlang.Rustup --source winget --accept-package-agreements --accept-source-agreements --silent
```

Ensure Rust is on PATH (new shell or run):
```powershell
setx PATH "%USERPROFILE%\.cargo\bin;%PATH%"
```

Verify:
```powershell
rustup --version
rustc --version
cargo --version
```

### Windows C++ Build Tools (required)
Rust on Windows uses the MSVC toolchain by default, so you need the C++ build tools installed.
Install either Visual Studio Build Tools or Visual Studio Community with the C++ workload:
```powershell
winget install --id Microsoft.VisualStudio.2022.BuildTools --source winget --accept-package-agreements --accept-source-agreements --silent
```

If `winget` finishes but you still do not have `link.exe`, install the workload manually:
1. Open **Visual Studio Installer**.
2. Find **Visual Studio Build Tools 2022** and click **Modify**.
3. Select **Desktop development with C++**.
4. Install and then open a new terminal.

In the installer, select the **Desktop development with C++** workload.
After install, open a new terminal so `link.exe` is on PATH.

### Protobuf Compiler (protoc)
`build.rs` uses `protoc` to compile `proto/terrain_gpu.proto`.
Install via winget:
```powershell
winget install --id Google.Protobuf --source winget --accept-package-agreements --accept-source-agreements --silent
```

If `protoc` is not on PATH in the current shell, either open a new terminal or set `PROTOC`:
```powershell
where.exe protoc
# If not found, set PROTOC to the full path:
$env:PROTOC="C:\\path\\to\\protoc.exe"
```

### Maintenance
If Rust tooling or build requirements change, update this README so it remains the source of truth for setup.

## Build / Run
```powershell
cargo build
```

```powershell
# Default listens on 127.0.0.1:50051
cargo run

# Custom address
$env:GPU_ENGINE_ADDR="127.0.0.1:50052"
cargo run
```

## Protobuf
The contract lives at `proto/terrain_gpu.proto` and is compiled by `build.rs` (tonic-build).
