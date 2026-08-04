# =====================================================================
# src/conjecture.jl — Phase 28 §28.3 — Dalmatian-style conjecture seed/update
# =====================================================================
#
# Port of `com.emergentterrain.research.conjecture.ConjectureEngine` —
# pearson correlation, near-identity filter, tightestKC sweep, and the
# update-with-witness loop. Identical algorithm; byte-identical output
# is asserted by the §28.3 parity test.
#
# Constants kept in lockstep with ConjectureEngine.java. When you bump
# K_GRID or TOL or NEAR_IDENTITY_CORR there, bump them here in the same
# commit — the parity test will catch any divergence on the next CI run.

const CONJ_TOL              = 0.02
const NEAR_IDENTITY_CORR    = 0.98
const K_GRID                = [-2.0, -1.0, -0.5, 0.0, 0.25, 0.5, 0.75, 1.0, 1.5, 2.0]

"""
    conj_pearson(xs, ys) -> Float64

Pearson correlation coefficient. Returns 0.0 for short series (n < 3)
or when either variance is zero. Mirrors ConjectureEngine.pearson —
the parity test pins this byte-identical.
"""
function conj_pearson(xs::Vector{Float64}, ys::Vector{Float64})::Float64
    n = length(xs)
    if n < 3
        return 0.0
    end
    mx = 0.0
    my = 0.0
    @inbounds for i in 1:n
        mx += xs[i]
        my += ys[i]
    end
    mx /= n
    my /= n
    num = 0.0
    dx  = 0.0
    dy  = 0.0
    @inbounds for i in 1:n
        xd = xs[i] - mx
        yd = ys[i] - my
        num += xd * yd
        dx  += xd * xd
        dy  += yd * yd
    end
    if dx == 0.0 || dy == 0.0
        return 0.0
    end
    return num / sqrt(dx * dy)
end

"""
    tightest_kc(lhs, rhs) -> Union{Nothing, NTuple{2,Float64}}

For each k in K_GRID, computes the minimum c such that
`lhs[i] <= k * rhs[i] + c` holds for all i. Returns the (k, c) pair
that minimises c. Returns `nothing` for empty / mismatched series.
"""
function tightest_kc(lhs::Vector{Float64}, rhs::Vector{Float64})
    if isempty(lhs) || length(lhs) != length(rhs)
        return nothing
    end
    best::Union{Nothing, NTuple{2,Float64}} = nothing
    @inbounds for k in K_GRID
        c = -Inf
        for i in 1:length(lhs)
            v = lhs[i] - k * rhs[i]
            if v > c
                c = v
            end
        end
        if best === nothing || c < best[2]
            best = (k, c)
        end
    end
    return best
end

"""
    conjecture_seed(samples, vocabulary) -> Vector{Dict{String,Any}}

Port of ConjectureEngine.seed. For each ordered pair of vocabulary
scalar names, compute pearson; skip pairs with |ρ| ≥ NEAR_IDENTITY_CORR
as near-identities; emit an Inequality dict for each survivor with
fields {lhs, rhs, k, c, confirmedRuns, confirmedConfigs, refutedBy,
p99Slack, slackByConfig, droppedByKnob} — the same wire shape the
JVM-side Inequality serialises to.

Iteration order mirrors the JVM: outer lhs sweep over vocabulary
(insertion order preserved), inner rhs sweep over the same. Pairs
with lhs == rhs are skipped. This produces the same output ordering
on both paths, which the parity test pins.
"""
function conjecture_seed(samples::Vector, vocabulary::Vector{String})::Vector{Dict{String,Any}}
    out = Vector{Dict{String,Any}}()
    if isempty(samples)
        return out
    end
    # Build per-name series in vocabulary order. Missing keys default
    # to 0.0 — same as JVM's `getOrDefault(name, 0.0)`.
    series = Dict{String, Vector{Float64}}()
    n_samples = length(samples)
    for name in vocabulary
        arr = Vector{Float64}(undef, n_samples)
        @inbounds for i in 1:n_samples
            arr[i] = get(samples[i], name, 0.0)
        end
        series[name] = arr
    end
    for lhs in vocabulary
        for rhs in vocabulary
            lhs == rhs && continue
            xs = series[lhs]
            ys = series[rhs]
            if abs(conj_pearson(xs, ys)) >= NEAR_IDENTITY_CORR
                continue
            end
            fit = tightest_kc(xs, ys)
            fit === nothing && continue
            push!(out, Dict{String,Any}(
                "lhs"               => lhs,
                "rhs"               => rhs,
                "k"                 => fit[1],
                "c"                 => fit[2],
                "confirmedRuns"     => 0,
                "confirmedConfigs"  => String[],
                "refutedBy"         => Dict{String,Any}[],
                "p99Slack"          => 0.0,
                "slackByConfig"     => Dict{String,Float64}(),
                "droppedByKnob"     => nothing,
            ))
        end
    end
    return out
end

"""
    conjecture_update(store, run_key, config_hash, samples) -> NamedTuple

Port of ConjectureEngine.update. For each inequality in store:

  1. Find the sample with the worst margin (lhs − (k*rhs + c)).
  2. If worst > 2·TOL → retire: append a Witness to refutedBy,
     remove from the surviving store.
  3. Otherwise: tighten c by half the slack (only when worst < 0),
     increment confirmedRuns, append config_hash to confirmedConfigs
     if not already present, update p99Slack to max(p99Slack, worst),
     and merge into slackByConfig (max).

The returned named tuple matches what the JVM JuliaMathClient.conjectureUpdate
parses: (confirmed::Int, retiredItems::Vector{Dict}, store::Vector{Dict}).
The surviving store is returned (not mutated) so the JVM can replace its
local copy verbatim — same semantics as the in-process JVM update which
mutates `store` in place.

The "_tick" magic key mirrors JVM behavior: if the sample carrying the
worst margin includes a "_tick" field, that integer is the witness's
tick; otherwise the sample index (1-based here, but the wire shape
preserves 0-based to match JVM).
"""
function conjecture_update(store::Vector,
                            run_key::String,
                            config_hash::String,
                            samples::Vector)
    confirmed = 0
    retired_items = Dict{String,Any}[]
    surviving = Dict{String,Any}[]
    @inbounds for ineq in store
        lhs_name = String(ineq["lhs"])
        rhs_name = String(ineq["rhs"])
        k        = Float64(ineq["k"])
        c        = Float64(ineq["c"])
        worst_idx = 0          # 0-based to match JVM
        worst     = -Inf
        for (i, s) in enumerate(samples)
            lhs_val = Float64(get(s, lhs_name, 0.0))
            rhs_val = Float64(get(s, rhs_name, 0.0))
            margin  = lhs_val - (k * rhs_val + c)
            if margin > worst
                worst     = margin
                worst_idx = i - 1
            end
        end
        if worst > 2.0 * CONJ_TOL
            # Retire. Read tick from _tick field of the worst sample,
            # else fall back to the worst index itself (same as JVM:
            # `samples.get(worstIdx).getOrDefault("_tick", (double) worstIdx).longValue()`).
            worst_sample = samples[worst_idx + 1]
            tick_raw = get(worst_sample, "_tick", Float64(worst_idx))
            tick = Int64(floor(Float64(tick_raw)))
            witness = Dict{String,Any}(
                "runKey" => run_key,
                "tick"   => tick,
                "margin" => worst,
            )
            # Copy + append the witness so we don't mutate the caller's input.
            refuted_by = copy(ineq["refutedBy"])
            push!(refuted_by, witness)
            retired = Dict{String,Any}(
                "lhs"               => lhs_name,
                "rhs"               => rhs_name,
                "k"                 => k,
                "c"                 => c,
                "confirmedRuns"     => ineq["confirmedRuns"],
                "confirmedConfigs"  => copy(ineq["confirmedConfigs"]),
                "refutedBy"         => refuted_by,
                "p99Slack"          => Float64(ineq["p99Slack"]),
                "slackByConfig"     => copy(ineq["slackByConfig"]),
                "droppedByKnob"     => get(ineq, "droppedByKnob", nothing),
            )
            push!(retired_items, retired)
            continue
        end
        # Confirmed branch: tighten c on positive slack (worst < 0),
        # bump runs, append config_hash, update p99 + slack-by-config.
        new_c = worst < 0.0 ? c + worst * 0.5 : c
        confirmed_configs = copy(ineq["confirmedConfigs"])
        if !(config_hash in confirmed_configs)
            push!(confirmed_configs, config_hash)
        end
        new_p99 = max(Float64(ineq["p99Slack"]), worst)
        slack_by_config = copy(ineq["slackByConfig"])
        if haskey(slack_by_config, config_hash)
            slack_by_config[config_hash] = max(Float64(slack_by_config[config_hash]), worst)
        else
            slack_by_config[config_hash] = worst
        end
        push!(surviving, Dict{String,Any}(
            "lhs"               => lhs_name,
            "rhs"               => rhs_name,
            "k"                 => k,
            "c"                 => new_c,
            "confirmedRuns"     => Int(ineq["confirmedRuns"]) + 1,
            "confirmedConfigs"  => confirmed_configs,
            "refutedBy"         => copy(ineq["refutedBy"]),
            "p99Slack"          => new_p99,
            "slackByConfig"     => slack_by_config,
            "droppedByKnob"     => get(ineq, "droppedByKnob", nothing),
        ))
        confirmed += 1
    end
    return (confirmed = confirmed,
            retiredItems = retired_items,
            store = surviving)
end
