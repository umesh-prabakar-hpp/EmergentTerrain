# =====================================================================
# literature_grounding.jl — Phase 32 §32.5 step 3: offline BM25 retrieval
# over the local number-theory corpus (POST /ground/claim)
# =====================================================================
#
# Runtime-included INTO PyCallSidecar by pycall_sidecar.jl — deliberately
# NOT part of the JuliaMathSidecar package (no precompile impact; editing
# this file needs only a py-sidecar restart, never a package rebuild).
# Pure Julia, PyCall-free, fully OFFLINE on the request path: the corpus
# is the abstracts.jsonl built by EmergentTerrain's
# scripts/build-nt-corpus.sh (OpenAlex, build-time only).
#
# v1 SEMANTICS (deliberate split from the §32.5 doc contract): this
# endpoint does RETRIEVAL only — verdict ∈ {EVIDENCE, SILENT}. The
# contracrow trichotomy classification (SUPPORTED/CONTRADICTED/SILENT)
# happens JVM-side in §32.5 step 4, where the LLM pools live: the sidecar
# has no LLM access and must stay deterministic. SILENT preserves the
# honest "no references found" path; EVIDENCE carries scored snippets +
# citation-key metadata for the caller to classify and cite from
# (cite-only-provided-keys, 32.6).
#
# Index: BM25 (k1=1.2, b=0.75) over title+abstract, built once at
# py-sidecar startup (lg_init_from_env!) so the single-threaded server
# never starves /healthz on a first-request build. ~105k docs ≈ tens of
# seconds + a few hundred MB — acceptable on the otherwise-idle 8089.
# =====================================================================

using JSON3

# ─── corpus + index state ────────────────────────────────────────────

struct LgDoc
    key::String          # OpenAlex short id, e.g. "W2003357399" — THE citation key
    title::String
    year::Int
    venue::String
    authors::String
    doi::String
    abstract_::String
end

struct LgIndex
    docs::Vector{LgDoc}
    postings::Dict{String,Vector{Tuple{Int32,Int16}}}   # term -> [(doc, tf)]
    doclen::Vector{Int32}
    avglen::Float64
end

const LG_INDEX = Ref{Union{Nothing,LgIndex}}(nothing)
const LG_K1 = 1.2
const LG_B  = 0.75

# Minimal English stopword set — enough to keep postings lean without
# hurting technical-phrase recall.
const LG_STOPWORDS = Set([
    "the", "a", "an", "of", "and", "or", "in", "on", "to", "for", "is", "are",
    "was", "were", "be", "been", "by", "with", "as", "at", "from", "that",
    "this", "these", "those", "it", "its", "we", "our", "which", "their",
    "there", "than", "then", "into", "over", "under", "between", "if", "not",
    "no", "can", "may", "such", "also", "has", "have", "had", "but", "all",
    "any", "some", "one", "two", "when", "where", "how", "what", "who", "will",
    "would", "let", "each", "both", "more", "most", "other", "paper", "show",
    "prove", "results", "result", "using", "used", "use", "new", "given", "obtained"])

function lg_tokenize(text::AbstractString)
    tokens = String[]
    buf = IOBuffer()
    for c in lowercase(text)
        if isletter(c) || isdigit(c)
            print(buf, c)
        else
            tok = String(take!(buf))
            if length(tok) >= 2 && !(tok in LG_STOPWORDS)
                push!(tokens, tok)
            end
        end
    end
    tok = String(take!(buf))
    if length(tok) >= 2 && !(tok in LG_STOPWORDS)
        push!(tokens, tok)
    end
    return tokens
end

"""Load abstracts.jsonl and build the BM25 index. Returns doc count."""
function lg_init!(corpus_path::AbstractString)
    docs = LgDoc[]
    postings = Dict{String,Vector{Tuple{Int32,Int16}}}()
    doclen = Int32[]
    open(corpus_path, "r") do io
        for line in eachline(io)
            isempty(strip(line)) && continue
            row = try
                JSON3.read(line)
            catch
                continue
            end
            id = String(get(row, :id, ""))
            key = last(split(id, '/'))     # "https://openalex.org/W123" -> "W123"
            isempty(key) && continue
            title = String(get(row, :title, ""))
            abstract_ = String(get(row, :abstract, ""))
            authors = try
                join(String.(get(row, :authors, String[])), ", ")
            catch
                ""
            end
            doc = LgDoc(key, title, Int(get(row, :year, 0)),
                        String(get(row, :venue, "")), authors,
                        String(get(row, :doi, "")), abstract_)
            push!(docs, doc)
            docid = Int32(length(docs))
            counts = Dict{String,Int}()
            ntok = 0
            for tok in lg_tokenize(title * " " * abstract_)
                counts[tok] = get(counts, tok, 0) + 1
                ntok += 1
            end
            push!(doclen, Int32(ntok))
            for (tok, tf) in counts
                push!(get!(postings, tok, Tuple{Int32,Int16}[]),
                      (docid, Int16(min(tf, typemax(Int16)))))
            end
        end
    end
    avglen = isempty(doclen) ? 1.0 : sum(Float64, doclen) / length(doclen)
    LG_INDEX[] = LgIndex(docs, postings, doclen, avglen)
    return length(docs)
end

"""Startup hook: build the index when the corpus exists; degrade to a clear
   'not configured' response otherwise (never fail the server boot)."""
function lg_init_from_env!()
    path = get(ENV, "ET_GROUND_CORPUS",
               joinpath(homedir(), ".emergentterrain", "corpus-nt", "abstracts.jsonl"))
    if !isfile(path)
        @info "literature grounding: corpus not found — /ground/claim will answer 'not_configured'" path
        return 0
    end
    t0 = time_ns()
    n = try
        lg_init!(path)
    catch e
        @warn "literature grounding: corpus index build FAILED" error=sprint(showerror, e)
        return 0
    end
    elapsed_s = round((time_ns() - t0) / 1e9; digits=1)
    @info "literature grounding: BM25 index ready" docs=n terms=length(LG_INDEX[].postings) elapsed_s
    return n
end

# ─── scoring ─────────────────────────────────────────────────────────

"""BM25 over the query tokens; returns (top docids, raw scores, normalized top score).
   The normalized score divides by Σ idf(q) so it is comparable across query lengths
   (≈ fraction of the query's information mass matched, times the tf saturation ≤ k1+1)."""
function lg_search(query::AbstractString, topk::Int)
    index = LG_INDEX[]
    index === nothing && return (Int32[], Float64[], 0.0)
    qtokens = unique(lg_tokenize(query))
    isempty(qtokens) && return (Int32[], Float64[], 0.0)
    ndocs = length(index.docs)
    scores = Dict{Int32,Float64}()
    idfsum = 0.0
    for tok in qtokens
        plist = get(index.postings, tok, nothing)
        plist === nothing && continue
        df = length(plist)
        idf = log(1.0 + (ndocs - df + 0.5) / (df + 0.5))
        idfsum += idf
        for (docid, tf) in plist
            denom = tf + LG_K1 * (1.0 - LG_B + LG_B * index.doclen[docid] / index.avglen)
            scores[docid] = get(scores, docid, 0.0) + idf * (tf * (LG_K1 + 1.0)) / denom
        end
    end
    isempty(scores) && return (Int32[], Float64[], 0.0)
    order = sort(collect(scores); by=kv -> -kv[2])
    top = order[1:min(topk, length(order))]
    nscore = idfsum > 0 ? top[1][2] / (idfsum * (LG_K1 + 1.0)) : 0.0
    return (Int32[kv[1] for kv in top], Float64[kv[2] for kv in top], nscore)
end

"""Snippet: a ±160-char window around the first query-term hit in the abstract."""
function lg_snippet(doc::LgDoc, query::AbstractString)
    text = isempty(doc.abstract_) ? doc.title : doc.abstract_
    lower = lowercase(text)
    at = 1
    for tok in lg_tokenize(query)
        hit = findfirst(tok, lower)
        if hit !== nothing
            at = first(hit)
            break
        end
    end
    lo = max(1, at - 160)
    hi = min(lastindex(text), at + 160)
    lo = max(1, thisind(text, lo))
    hi = min(lastindex(text), thisind(text, hi))
    frag = strip(text[lo:hi])
    return (lo > 1 ? "…" : "") * frag * (hi < lastindex(text) ? "…" : "")
end

# ─── HTTP handler ────────────────────────────────────────────────────

# SILENT below this normalized-score threshold (env-tunable). Calibrated on the
# 20-claim §32.5 probe set 2026-07-04: known/contradiction claims score ≥0.283,
# genuinely-open claims ≤0.250 — the 20/20 plateau is 0.25–0.29, default = its
# center. The margin is thin (~0.03) and claim-wording-sensitive; re-run
# EmergentTerrain/scripts/run-ground-probes.py after any corpus rebuild.
lg_silent_threshold() = parse(Float64, get(ENV, "ET_GROUND_SILENT_THRESHOLD", "0.27"))

function handle_ground_claim(req::HTTP.Request)
    t0 = time_ns()
    body = safe_parse_body(req)
    if body === nothing
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("ground_claim", elapsed_ms, :error)
        return error_response("bad_request", "Body is not valid JSON", elapsed_ms)
    end
    claim = String(get(body, :claim, ""))
    topk = clamp(Int(get(body, :topK, 5)), 1, 20)
    if isempty(strip(claim))
        elapsed_ms = round(Int, (time_ns() - t0) / 1_000_000)
        record_call!("ground_claim", elapsed_ms, :error)
        return error_response("bad_request", "Missing 'claim'", elapsed_ms)
    end
    index = LG_INDEX[]
    elapsedms() = round(Int, (time_ns() - t0) / 1_000_000)
    if index === nothing
        record_call!("ground_claim", elapsedms(), :error)
        return error_response("not_configured",
            "Grounding corpus not loaded (build it with EmergentTerrain's "
            * "scripts/build-nt-corpus.sh, or set ET_GROUND_CORPUS)", elapsedms())
    end
    docids, scores, nscore = lg_search(claim, topk)
    threshold = lg_silent_threshold()
    verdict = (isempty(docids) || nscore < threshold) ? "SILENT" : "EVIDENCE"
    # Evidence is returned for BOTH verdicts: under SILENT the entries are the
    # NEAR MISSES — "related work exists but does not state this claim" — which
    # is richer input for both the narrator (situating) and the 32.5b panel
    # novelty read (a SILENT with strong near-misses is positive novelty
    # evidence over a real corpus, not a can't-assess).
    evidence = Vector{Dict{String,Any}}()
    for (i, docid) in pairs(docids)
        doc = index.docs[docid]
        push!(evidence, Dict{String,Any}(
            "key" => doc.key,
            "title" => doc.title,
            "year" => doc.year,
            "venue" => doc.venue,
            "authors" => doc.authors,
            "doi" => doc.doi,
            "score" => round(scores[i]; digits=3),
            "snippet" => lg_snippet(doc, claim)))
    end
    value = Dict{String,Any}(
        "verdict" => verdict,
        "confidence" => round(nscore; digits=4),
        "threshold" => threshold,
        "evidence" => evidence,
        "corpus_docs" => length(index.docs),
        "note" => "v1 retrieval verdict: EVIDENCE|SILENT (SILENT still lists "
                  * "near-miss references). SUPPORTED/CONTRADICTED classification "
                  * "is the JVM caller's contracrow step over these snippets; "
                  * "cite ONLY the provided keys.")
    record_call!("ground_claim", elapsedms(), :ok)
    return ok_response(elapsedms(), value)
end
