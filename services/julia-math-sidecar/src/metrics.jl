# =====================================================================
# metrics.jl — per-endpoint call counts + latency tracking
# =====================================================================
#
# Tiny in-process metrics ring. No Prometheus dependency — we expose
# the counters via the `/metrics` HTTP endpoint as JSON. EmergentTerrain
# can poll it from its own observability stack if useful.
#
# Tracked per endpoint:
#   - count_total        — every request that reached the handler
#   - count_ok           — handler returned 200
#   - count_safety_reject — rejected at the safety filter
#   - count_error        — handler threw or returned ≥500
#   - count_timeout      — exceeded the per-call timeout
#   - latency_ms_total   — cumulative (for avg latency)
#   - latency_ms_max     — peak seen
#
# Thread-safety: counters use `Threads.Atomic` so concurrent handler
# threads can increment without locking. The atomic ops are cheap
# (CAS); the metrics surface is read-mostly anyway.
# =====================================================================

using Base.Threads: Atomic, atomic_add!, atomic_max!

# One record per endpoint name. The handler bumps these via the helper
# functions below; the `/metrics` handler serialises them to JSON.
mutable struct EndpointMetrics
    name::String
    count_total::Atomic{Int}
    count_ok::Atomic{Int}
    count_safety_reject::Atomic{Int}
    count_error::Atomic{Int}
    count_timeout::Atomic{Int}
    latency_ms_total::Atomic{Int}
    latency_ms_max::Atomic{Int}
end

EndpointMetrics(name::String) = EndpointMetrics(
    name,
    Atomic{Int}(0), Atomic{Int}(0), Atomic{Int}(0),
    Atomic{Int}(0), Atomic{Int}(0),
    Atomic{Int}(0), Atomic{Int}(0),
)

# Registry of metrics, keyed by endpoint name. Lazily populated.
const METRICS_REGISTRY = Dict{String,EndpointMetrics}()
const METRICS_LOCK = ReentrantLock()  # protects only insert; reads are safe

# Process-wide start timestamp — used by /metrics to report uptime.
const PROCESS_START_NANOS = Ref(0)

function init_metrics!()
    PROCESS_START_NANOS[] = time_ns()
    return nothing
end

"""
    get_or_create_endpoint_metrics(name)

Look up the metrics record for `name`, creating it on first call. Safe
to call from any thread; uses a lock only for the rare insert path.
"""
function get_or_create_endpoint_metrics(name::AbstractString)::EndpointMetrics
    # Fast path — registry already has the record.
    if haskey(METRICS_REGISTRY, name)
        return METRICS_REGISTRY[name]
    end
    # Slow path — create + insert under lock to avoid duplicate entries
    # when two threads race the first request to a new endpoint.
    lock(METRICS_LOCK) do
        if !haskey(METRICS_REGISTRY, name)
            METRICS_REGISTRY[name] = EndpointMetrics(name)
        end
        return METRICS_REGISTRY[name]
    end
end

"""
    record_call!(name, elapsed_ms, outcome)

Bump the per-endpoint counters. `outcome` is one of `:ok`, `:safety_reject`,
`:timeout`, `:error`. `elapsed_ms` is the wall-clock duration of the
handler — counted toward `latency_ms_total` regardless of outcome so
the avg-latency reading reflects all paths, not just successes.
"""
function record_call!(name::AbstractString, elapsed_ms::Int, outcome::Symbol)
    m = get_or_create_endpoint_metrics(name)
    atomic_add!(m.count_total, 1)
    atomic_add!(m.latency_ms_total, elapsed_ms)
    atomic_max!(m.latency_ms_max, elapsed_ms)
    if outcome === :ok
        atomic_add!(m.count_ok, 1)
    elseif outcome === :safety_reject
        atomic_add!(m.count_safety_reject, 1)
    elseif outcome === :timeout
        atomic_add!(m.count_timeout, 1)
    else  # :error
        atomic_add!(m.count_error, 1)
    end
    return nothing
end

"""
    metrics_snapshot() -> Dict

Return a JSON-serialisable view of all tracked endpoints plus
process-level info (uptime, version).
"""
function metrics_snapshot()
    endpoints = Dict{String,Any}()
    for (name, m) in METRICS_REGISTRY
        total = m.count_total[]
        latency_avg = total == 0 ? 0.0 : m.latency_ms_total[] / total
        endpoints[name] = Dict(
            "count_total"          => total,
            "count_ok"             => m.count_ok[],
            "count_safety_reject"  => m.count_safety_reject[],
            "count_error"          => m.count_error[],
            "count_timeout"        => m.count_timeout[],
            "latency_ms_total"     => m.latency_ms_total[],
            "latency_ms_max"       => m.latency_ms_max[],
            "latency_ms_avg"       => round(latency_avg, digits=2),
        )
    end
    uptime_s = PROCESS_START_NANOS[] == 0
        ? 0.0
        : round((time_ns() - PROCESS_START_NANOS[]) / 1e9, digits=2)
    return Dict(
        "endpoints" => endpoints,
        "uptime_s"  => uptime_s,
        "version"   => "0.1.0",
    )
end
