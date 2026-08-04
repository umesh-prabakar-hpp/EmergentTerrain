# =====================================================================
# Dockerfile — container build for julia-math-sidecar
# =====================================================================
#
# Two-stage build:
#   1. `builder` — full Julia image, runs Pkg.instantiate +
#      precompilation against the project. Produces ~2GB intermediate.
#   2. `runtime` — same Julia base (slim image isn't published for
#      Julia), but only the depot + source are carried over. The
#      build context is purged.
#
# Build:
#   docker build -t julia-math-sidecar:0.1 .
#
# Run (production-hardened):
#   docker run --rm \
#       --name julia-math-sidecar \
#       -p 127.0.0.1:8088:8088 \
#       --read-only \
#       --tmpfs /tmp \
#       --memory=512m \
#       --cpus=2 \
#       --network=bridge \
#       --cap-drop=ALL \
#       --security-opt=no-new-privileges \
#       julia-math-sidecar:0.1
#
# Notes:
#   - `--read-only` + `--tmpfs /tmp` blocks any FS write outside /tmp.
#   - `--memory=512m` matches the systemd unit's MemoryMax.
#   - `--network=bridge` + the 127.0.0.1 publish binds the host
#     listener to localhost only. To allow LAN access (NOT
#     recommended), drop the 127.0.0.1 prefix on `-p`.
#   - `--cap-drop=ALL` strips every Linux capability — Julia doesn't
#     need any of them. If a future release adds e.g. SCTP support,
#     reintroduce the specific cap with `--cap-add=`.
# =====================================================================

# Stage 1 — builder. Pulls Symbolics.jl + transitive deps and
# precompiles them so the runtime image starts fast.
FROM julia:1.10 AS builder

# Pin the depot path so we can copy it into the runtime stage without
# guessing where Julia decided to install packages.
ENV JULIA_DEPOT_PATH=/opt/julia-depot
ENV JULIA_NUM_PRECOMPILE_TASKS=4

WORKDIR /build

# Copy only the manifest first so Pkg.instantiate caches efficiently
# when only source files change. (Standard layered-image trick — same
# as `npm ci` before `COPY src/`.)
COPY Project.toml Manifest.toml ./

# Instantiate + precompile in one step. We use `Pkg.precompile()`
# explicitly so the image surfaces compile errors at build time, not
# at first request.
RUN julia --project=. -e 'using Pkg; Pkg.instantiate(); Pkg.precompile()'

# Now copy the source. Changes to source files only invalidate this
# layer (and downstream), not the heavy precompile step.
COPY src/ ./src/
COPY scripts/ ./scripts/

# Verify the module loads. Fail the build if precompile broke.
RUN julia --project=. -e 'using JuliaMathSidecar; println("module loaded ok")'

# Stage 2 — runtime. Same Julia base (Julia doesn't publish slim
# images currently). Strip everything that isn't the depot + source.
FROM julia:1.10 AS runtime

# Create a non-root service user. Julia + HTTP.jl don't need any
# elevated capabilities; running as root would defeat --cap-drop.
RUN groupadd -r julia-math && \
    useradd -r -g julia-math -d /app -s /usr/sbin/nologin julia-math

ENV JULIA_DEPOT_PATH=/opt/julia-depot
ENV JULIA_NUM_THREADS=2

# Copy the pre-instantiated depot + project from the builder stage.
COPY --from=builder /opt/julia-depot /opt/julia-depot
COPY --from=builder /build /app

# Ownership — the service user needs read access to /app and read+
# execute on the depot. The depot also needs to be writable for
# Julia to write per-session caches if precompile state evolves, but
# since we precompiled at build time it's typically read-only at
# runtime. If you tighten further, mount a writable tmpfs at
# /opt/julia-depot/logs.
RUN chown -R julia-math:julia-math /app /opt/julia-depot

USER julia-math
WORKDIR /app

# Document the listener port. `EXPOSE` is metadata only; the actual
# binding comes from start.sh's HOST/PORT env vars.
EXPOSE 8088

# Health check — Docker's built-in. Useful when running under
# `docker compose` or Kubernetes-style orchestration.
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
    CMD curl -fsS http://127.0.0.1:${PORT:-8088}/healthz || exit 1

# Default command. start.sh sources HOST/PORT from env; override at
# `docker run` time if needed.
CMD ["./scripts/start.sh"]
