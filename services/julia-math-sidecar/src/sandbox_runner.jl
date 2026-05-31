# =====================================================================
# sandbox_runner.jl — fork 2b Phase 1: sandboxed execution of
# LLM-authored numeric Julia, gated by an AST allowlist + an isolated
# subprocess under OS resource limits.
#
# Scope: phases-for-human-like-performance/phase-30-conjecture/
#        runtime-self-verifying-execution.md
#
# DORMANT / flag-gated OFF. This file is NOT `include`d by
# src/JuliaMathSidecar.jl, so loading it does not recompile the package
# and the live server is unaffected. It runs only from its test harness
# (test/sandbox_runner_test.jl) until the Phase-2 `/discover/validate`
# endpoint wires it in behind the Java `app.research.discovery.
# runtimeExecEnabled` flag.
#
# Safety model (see scope §1–§2): the proposing model is NOT adversarial,
# but its code is untrusted — assume worst-case CRASHES (hang, OOM,
# segfault, FS/net/eval). Two layers:
#   1. AST allowlist (cheap pre-flight) — reject obvious abuse.
#   2. Subprocess isolation (THE boundary) — a fresh `julia` under
#      `timeout` (wall-clock) + `ulimit` (CPU, no file writes, no core).
#      If a candidate hangs/OOMs/crashes, only the child dies; the parent
#      (this process) is never in the blast radius.
#
# Contract (scope §3): the candidate EMITS raw values; the trusted side
# JUDGES. The candidate defines `candidate(x)::Integer`; a fixed driver
# prints `RESULT:[[k,v],...]`; the parent compares to the oracle. The
# candidate never sees the oracle or computes a verdict.
# =====================================================================
module SandboxRunner

export run_sandboxed, moat_judge, ast_violations, SandboxResult

# --- AST allowlist -----------------------------------------------------
# Forbidden leaf names (matched anywhere in the AST, qualified or not),
# forbidden expression heads, and forbidden macro names. Conservative on
# purpose: a numeric M(x) needs none of these, and the subprocess is the
# real boundary — this just rejects the obvious before paying a spawn.
const FORBIDDEN_NAMES = Set{Symbol}([
    :eval, :include, :include_string, :ccall, :cglobal, :pointer, :Ptr,
    :run, :Cmd, :pipeline, :open, :read, :readline, :readlines, :readuntil,
    :readchomp, :readbytes!, :eachline, :write, :download, :Base, # Base.* qualifier path is risky enough to flag
    :Core, :Main, :ENV, :exit, :atexit, :exec, :Libc, :Libdl, :Mmap, :mmap,
    :cd, :rm, :mkdir, :mkpath, :touch, :cp, :mv, :chmod, :chown, :symlink,
    :Sockets, :connect, :listen, :bind, :accept, :recv, :send, :TCPSocket,
    :Task, :schedule, :yield, :yieldto, :Channel, :Threads, :finalizer,
    :setenv, :addenv, :getpid, :Sys, :Pkg,
])
const FORBIDDEN_HEADS = Set{Symbol}([:using, :import, :module, :export, :toplevel_using])
const FORBIDDEN_MACROS = Set{Symbol}([
    Symbol("@ccall"), Symbol("@async"), Symbol("@spawn"), Symbol("@threads"),
    Symbol("@eval"), Symbol("@cfunction"), Symbol("@gc_preserve"),
])

_isforbidden_name(s::Symbol) =
    s in FORBIDDEN_NAMES || startswith(String(s), "unsafe_")

function _walk!(viol::Set{String}, ex)
    if ex isa Symbol
        _isforbidden_name(ex) && push!(viol, String(ex))
    elseif ex isa QuoteNode
        _walk!(viol, ex.value)
    elseif ex isa Expr
        ex.head in FORBIDDEN_HEADS && push!(viol, String(ex.head))
        if ex.head === :macrocall && !isempty(ex.args) && ex.args[1] isa Symbol
            ex.args[1] in FORBIDDEN_MACROS && push!(viol, String(ex.args[1]))
        end
        for a in ex.args
            _walk!(viol, a)
        end
    end
    return nothing
end

"""
    ast_violations(code) -> Vector{String}

Parse `code` and return the sorted list of forbidden tokens it contains
(empty = clean). A parse failure yields `["parse_error"]`.
"""
function _has_parse_error(ex)
    if ex isa Expr
        (ex.head === :error || ex.head === :incomplete) && return true
        for a in ex.args
            _has_parse_error(a) && return true
        end
    end
    return false
end

function ast_violations(code::AbstractString)
    viol = Set{String}()
    local parsed
    try
        parsed = Meta.parseall(String(code))
    catch
        return ["parse_error"]
    end
    _has_parse_error(parsed) && return ["parse_error"]
    _walk!(viol, parsed)
    return sort!(collect(viol))
end

# --- sandboxed run -----------------------------------------------------
struct SandboxResult
    status::Symbol                         # :ok :ast_reject :timeout :oom :crash :error
    detail::Any                            # violations / stderr tail / reason
    values::Union{Nothing,Dict{Int,Int}}   # candidate M(k), only when :ok
    elapsed_s::Float64
end

function _driver(code::AbstractString, inputs::Vector{Int})
    lits = join(string.(inputs), ",")
    return string(code, "\n\n",
        "let __inputs = Int[", lits, "]\n",
        "    __parts = String[]\n",
        "    for __k in __inputs\n",
        "        push!(__parts, string(\"[\", __k, \",\", Int(candidate(__k)), \"]\"))\n",
        "    end\n",
        "    println(string(\"RESULT:[\", join(__parts, \",\"), \"]\"))\n",
        "end\n")
end

"""
    run_sandboxed(code, inputs; cpu_seconds, wall_seconds, mem_kb, heap_hint)
        -> SandboxResult

Run untrusted candidate `code` (which must define `candidate(x)`) in an
isolated `julia` subprocess and return its `M(k)` for each `k` in
`inputs`. AST-rejected code never spawns. A hang/OOM/crash kills only the
child; this function always returns.
"""
function run_sandboxed(code::AbstractString, inputs::Vector{<:Integer};
                       cpu_seconds::Int = 10, wall_seconds::Int = 15,
                       mem_kb::Int = 0, heap_hint::String = "1G")
    viol = ast_violations(code)
    if !isempty(viol)
        return SandboxResult(:ast_reject, viol, nothing, 0.0)
    end

    ins = Int.(collect(inputs))
    script = _driver(code, ins)
    path, io = mktemp()
    try
        write(io, script); close(io)
        ulimits = "ulimit -t $(cpu_seconds) -f 0 -c 0; "
        mem_kb > 0 && (ulimits *= "ulimit -v $(mem_kb); ")
        inner = ulimits *
            "exec julia --startup-file=no --history-file=no -O0 " *
            "--heap-size-hint=$(heap_hint) $(path)"
        cmd = `timeout -s KILL $(wall_seconds) bash -c $inner`

        out = IOBuffer(); err = IOBuffer()
        t0 = time()
        proc = run(pipeline(ignorestatus(cmd); stdout=out, stderr=err); wait=true)
        elapsed = time() - t0
        code_exit = proc.exitcode
        sout = String(take!(out)); serr = String(take!(err))

        # Prefer a RESULT line if present (candidate emitted values).
        result_line = nothing
        for ln in split(sout, '\n')
            startswith(ln, "RESULT:") && (result_line = ln)
        end
        if result_line !== nothing
            vals = Dict{Int,Int}()
            for m in eachmatch(r"\[(-?\d+),(-?\d+)\]", result_line)
                vals[parse(Int, m.captures[1])] = parse(Int, m.captures[2])
            end
            return SandboxResult(:ok, nothing, vals, elapsed)
        end

        # No RESULT — classify the failure. A time limit (wall `timeout` →
        # 124/137, or CPU `ulimit -t` → SIGXCPU, which Julia may turn into a
        # ~limit-time exit) is detected by code OR by elapsed reaching ~90% of
        # the tighter of the two limits.
        errtail = serr[max(1, end-400):end]
        time_budget = min(cpu_seconds, wall_seconds)
        if code_exit == 124 || code_exit == 137 || elapsed >= 0.9 * time_budget
            return SandboxResult(:timeout, "time limit (cpu=$(cpu_seconds)s wall=$(wall_seconds)s)", nothing, elapsed)
        elseif occursin("OutOfMemoryError", serr) || occursin("StackOverflowError", serr)
            return SandboxResult(:oom, errtail, nothing, elapsed)
        elseif code_exit > 128
            return SandboxResult(:crash, "signal $(code_exit - 128); $(errtail)", nothing, elapsed)
        else
            return SandboxResult(:error, errtail, nothing, elapsed)
        end
    finally
        try; rm(path; force=true); catch; end
    end
end

# --- the moat judge (trusted side) ------------------------------------
"""
    moat_judge(res, oracle, oeis) -> (verdict::Symbol, firstfail)

Trusted-side verdict: `:accept` iff `res.status == :ok` and the candidate's
values match `oracle` (overlap) and `oeis` (beyond-range anchors) at every
key. Returns `(:reject, (k, got, want))` on the first mismatch, or
`(:reject, (:nonok, status, detail))` if the candidate didn't even run.
"""
function moat_judge(res::SandboxResult, oracle::Dict{Int,Int}, oeis::Dict{Int,Int})
    res.status === :ok || return (:reject, (:nonok, res.status, res.detail))
    for (k, want) in oracle
        got = get(res.values, k, nothing)
        (got === nothing || got != want) && return (:reject, (k, got, want))
    end
    for (k, want) in oeis
        got = get(res.values, k, nothing)
        (got === nothing || got != want) && return (:reject, (k, got, want))
    end
    return (:accept, nothing)
end

end # module SandboxRunner
