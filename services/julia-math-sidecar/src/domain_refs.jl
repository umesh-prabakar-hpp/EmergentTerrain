# =====================================================================
# domain_refs.jl — §32.5d: OEIS + DLMF domain-reference lookup
# =====================================================================
# Two offline lookups over the domain-reference stores built by EmergentTerrain's
# scripts/build-oeis.py and build-dlmf.py (corpus-nt/domain-refs/):
#   POST /lookup/oeis  {terms:[...], topK}  → integer-sequence identification
#   POST /lookup/dlmf  {query, topK}        → NIST DLMF special-function sections (BM25)
# Runtime-included by pycall_sidecar.jl (edit + py-sidecar restart, no package rebuild).
# Pure Julia, fully offline. Reuses lg_tokenize/BM25 constants from literature_grounding.jl.
# =====================================================================

using JSON3

# ─── OEIS: integer-sequence lookup ───────────────────────────────────
struct OeisSeq
    id::String                     # A-number, e.g. "A000045"
    name::String
    terms::Vector{String}          # terms as strings (OEIS terms overflow Int64)
end
const OEIS = Ref{Union{Nothing,Vector{OeisSeq}}}(nothing)
# from-start signature: (t1,t2,t3) → seq indices — the common "does my sequence start like a known one" query
const OEIS_SIG = Ref{Dict{Tuple{String,String,String},Vector{Int32}}}(Dict())

function dr_oeis_init!(path::AbstractString)
    seqs = OeisSeq[]
    sig = Dict{Tuple{String,String,String},Vector{Int32}}()
    open(path, "r") do io
        for line in eachline(io)
            isempty(strip(line)) && continue
            row = try JSON3.read(line) catch; continue end
            id = String(get(row, :id, "")); isempty(id) && continue
            allt = try String.(collect(get(row, :terms, String[]))) catch; String[] end
            isempty(allt) && continue
            terms = length(allt) > 20 ? allt[1:20] : allt   # leading terms only: bounds RAM (~1.6GB→~0.5GB) + load time; 20 is ample for lookup
            push!(seqs, OeisSeq(id, String(get(row, :name, "")), terms))
            if length(terms) >= 3
                push!(get!(sig, (terms[1], terms[2], terms[3]), Int32[]), Int32(length(seqs)))
            end
        end
    end
    OEIS[] = seqs; OEIS_SIG[] = sig
    return length(seqs)
end

"""Is `q` a contiguous sub-run of `t`?"""
function dr_is_contig(q::Vector{String}, t::Vector{String})
    n, m = length(q), length(t)
    n == 0 && return true
    n > m && return false
    @inbounds for s in 0:(m - n)
        ok = true
        for i in 1:n
            t[s + i] != q[i] && (ok = false; break)
        end
        ok && return true
    end
    return false
end

function dr_oeis_lookup(query::Vector{String}, topk::Int)
    seqs = OEIS[]
    (seqs === nothing || isempty(query)) && return OeisSeq[]
    out = OeisSeq[]
    if length(query) >= 3                                   # fast path: from-start signature
        for si in get(OEIS_SIG[], (query[1], query[2], query[3]), Int32[])
            dr_is_contig(query, seqs[si].terms) && push!(out, seqs[si])
            length(out) >= topk && return out
        end
    end
    if isempty(out)                                         # fallback: bounded scan (mid-seq / short queries)
        scanned = 0
        for s in seqs
            scanned += 1; scanned > 200_000 && break
            if dr_is_contig(query, s.terms)
                push!(out, s); length(out) >= topk && break
            end
        end
    end
    return out
end

# ─── DLMF: BM25 over special-function sections ───────────────────────
struct DlmfSec
    id::String                     # e.g. "25.10"
    title::String
    text::String
end
const DLMF = Ref{Union{Nothing,Vector{DlmfSec}}}(nothing)
const DLMF_POST = Ref{Dict{String,Vector{Tuple{Int32,Int16}}}}(Dict())
const DLMF_LEN = Ref{Vector{Int32}}(Int32[])
const DLMF_AVG = Ref{Float64}(1.0)

function dr_dlmf_init!(path::AbstractString)
    secs = DlmfSec[]
    postings = Dict{String,Vector{Tuple{Int32,Int16}}}()
    doclen = Int32[]
    open(path, "r") do io
        for line in eachline(io)
            isempty(strip(line)) && continue
            row = try JSON3.read(line) catch; continue end
            push!(secs, DlmfSec(String(get(row, :id, "")), String(get(row, :title, "")),
                                String(get(row, :text, ""))))
            did = Int32(length(secs))
            counts = Dict{String,Int}(); ntok = 0
            for tok in lg_tokenize(String(get(row, :title, "")) * " " * String(get(row, :text, "")))
                counts[tok] = get(counts, tok, 0) + 1; ntok += 1
            end
            push!(doclen, Int32(ntok))
            for (tok, tf) in counts
                push!(get!(postings, tok, Tuple{Int32,Int16}[]), (did, Int16(min(tf, typemax(Int16)))))
            end
        end
    end
    DLMF[] = secs; DLMF_POST[] = postings; DLMF_LEN[] = doclen
    DLMF_AVG[] = isempty(doclen) ? 1.0 : sum(Float64, doclen) / length(doclen)
    return length(secs)
end

function dr_dlmf_lookup(query::AbstractString, topk::Int)
    secs = DLMF[]
    secs === nothing && return DlmfSec[]
    qtokens = unique(lg_tokenize(query))
    isempty(qtokens) && return DlmfSec[]
    ndocs = length(secs); scores = Dict{Int32,Float64}()
    for tok in qtokens
        plist = get(DLMF_POST[], tok, nothing); plist === nothing && continue
        df = length(plist); idf = log(1.0 + (ndocs - df + 0.5) / (df + 0.5))
        for (did, tf) in plist
            denom = tf + LG_K1 * (1.0 - LG_B + LG_B * DLMF_LEN[][did] / DLMF_AVG[])
            scores[did] = get(scores, did, 0.0) + idf * (tf * (LG_K1 + 1.0)) / denom
        end
    end
    isempty(scores) && return DlmfSec[]
    order = sort(collect(scores); by = kv -> -kv[2])
    return [secs[kv[1]] for kv in order[1:min(topk, length(order))]]
end

# ─── startup ─────────────────────────────────────────────────────────
function dr_init_from_env!()
    dir = get(ENV, "ET_DOMAIN_REFS",
              joinpath(homedir(), ".emergentterrain", "corpus-nt", "domain-refs"))
    no, nd = 0, 0
    op = joinpath(dir, "oeis.jsonl")
    if isfile(op)
        no = try dr_oeis_init!(op) catch e; @warn "OEIS index failed" error=sprint(showerror, e); 0 end
    end
    dp = joinpath(dir, "dlmf.jsonl")
    if isfile(dp)
        nd = try dr_dlmf_init!(dp) catch e; @warn "DLMF index failed" error=sprint(showerror, e); 0 end
    end
    (no > 0 || nd > 0) && @info "domain refs ready" oeis=no dlmf=nd
    return no, nd
end

# ─── HTTP handlers ───────────────────────────────────────────────────
function handle_lookup_oeis(req::HTTP.Request)
    t0 = time_ns(); ems() = round(Int, (time_ns() - t0) / 1_000_000)
    body = safe_parse_body(req)
    body === nothing && (record_call!("lookup_oeis", ems(), :error);
        return error_response("bad_request", "Body is not valid JSON", ems()))
    OEIS[] === nothing && (record_call!("lookup_oeis", ems(), :error);
        return error_response("not_configured", "OEIS not loaded (build-oeis.py; set ET_DOMAIN_REFS)", ems()))
    terms = [strip(String(t)) for t in get(body, :terms, String[])]
    terms = String[t for t in terms if !isempty(t)]
    topk = clamp(Int(get(body, :topK, 5)), 1, 20)
    matches = dr_oeis_lookup(terms, topk)
    value = Dict{String,Any}("query_terms" => terms, "count" => length(matches),
        "matches" => [Dict("id" => m.id, "name" => m.name,
                           "terms_preview" => join(m.terms[1:min(12, length(m.terms))], ", "),
                           "url" => "https://oeis.org/" * m.id) for m in matches],
        "note" => "OEIS sequence identification (from-start signature + bounded scan). "
                  * "A match means this integer sequence is a KNOWN one — cite the A-number.")
    record_call!("lookup_oeis", ems(), :ok)
    return ok_response(ems(), value)
end

function handle_lookup_dlmf(req::HTTP.Request)
    t0 = time_ns(); ems() = round(Int, (time_ns() - t0) / 1_000_000)
    body = safe_parse_body(req)
    body === nothing && (record_call!("lookup_dlmf", ems(), :error);
        return error_response("bad_request", "Body is not valid JSON", ems()))
    DLMF[] === nothing && (record_call!("lookup_dlmf", ems(), :error);
        return error_response("not_configured", "DLMF not loaded (build-dlmf.py; set ET_DOMAIN_REFS)", ems()))
    query = String(get(body, :query, ""))
    topk = clamp(Int(get(body, :topK, 3)), 1, 10)
    secs = dr_dlmf_lookup(query, topk)
    value = Dict{String,Any}("query" => query, "count" => length(secs),
        "sections" => [Dict("id" => s.id, "title" => s.title,
                            "snippet" => first(s.text, min(400, lastindex(s.text))),
                            "url" => "https://dlmf.nist.gov/" * s.id) for s in secs],
        "note" => "NIST DLMF special-function reference (authoritative; formulas quotable as \$LaTeX\$). "
                  * "Cite as 'DLMF §<id>'.")
    record_call!("lookup_dlmf", ems(), :ok)
    return ok_response(ems(), value)
end
