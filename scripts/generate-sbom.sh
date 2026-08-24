#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
sbom_dir="$repo_root/sbom"
mkdir -p "$sbom_dir"

mvn -q -f "$repo_root/pom.xml" org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeAggregateBom \
  -DoutputDirectory="$sbom_dir" \
  -DoutputName=maven.cdx \
  -DoutputFormat=json

cargo metadata --manifest-path "$repo_root/engines/gpu-engine/Cargo.toml" --locked --format-version 1 \
  | python3 "$repo_root/scripts/generate-cargo-sbom.py" > "$sbom_dir/cargo.cdx.json"

julia --project="$repo_root/services/julia-math-sidecar" -e '
using Pkg
deps = Pkg.dependencies()
for (uuid, dep) in sort!(collect(deps); by = pair -> lowercase(pair[2].name))
    println("[[component]]")
    println("name = \"", replace(dep.name, "\\\"" => "\\\\\\\""), "\"")
    println("uuid = \"", uuid, "\"")
    dep.version === nothing || println("version = \"", dep.version, "\"")
    println("is_direct_dep = ", dep.is_direct_dep)
    source_kind = dep.is_tracking_registry ? "registry" : dep.is_tracking_repo ? "repository" : dep.is_tracking_path ? "path" : "stdlib"
    println("source_kind = \"", source_kind, "\"")
    license_names = dep.source === nothing ? String[] : filter(name -> occursin(r"(?i)^(license|copying|notice)", name) && isfile(joinpath(dep.source, name)), readdir(dep.source))
    license_file = isempty(license_names) ? "" : first(license_names)
    isempty(license_file) || println("license_file = \"", license_file, "\"")
end
' > "$sbom_dir/julia-dependencies.toml"

npm --prefix "$repo_root/scripts/legacy-ai-gateway" ls --all --package-lock-only --json \
  > "$sbom_dir/node-legacy-ai-gateway.json"

echo "Wrote dependency inventories to $sbom_dir"
