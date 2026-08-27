# Pure-mathematics physical-mechanism audit routing — 2026-07-25

The `physical_mechanism` audit gap is produced by an LLM. It is a useful warning for
non-mathematics papers, but it cannot by itself prove that a pure mathematics catalog paper makes
a physical claim. P-b72c-77627904 was an exact finite census with explicit anti-physical caveats
and was nevertheless persisted as `REJECTED` solely because it was `PaperKind.DOMAIN` rather
than `CONJECTURE_INVESTIGATION`.

The hard audit route now excludes every `MATHEMATICS` paper. The deterministic
`CrossDomainHypothesisGate` remains unchanged and still rejects a mathematics hypothesis that
uses unbridged materials or physics markers. Thus the change removes a probabilistic false
reject without weakening the deterministic cross-domain protection.
