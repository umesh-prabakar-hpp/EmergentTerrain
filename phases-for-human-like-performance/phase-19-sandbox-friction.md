# Phase 19 — Sandbox Friction (wishlist P2 promotion)

**Status:** CLOSED PENDING REFRESH (2026-05-18). All Layer 1 + Layer 2
code is shipped on `main`; the rollback flag is in `application.yml`;
Open Question 1's named multi-value surface is implemented. The two
remaining acceptance criteria (corpus refresh ≤6 rejections/run; 678 +
683 replay with ≥1 successful sandbox call each) are operator-action
items waiting on the next REVIEW_REPORT run under the new binary.

**Shipped commits (latest first):**

| Commit    | Scope                                                       |
|-----------|-------------------------------------------------------------|
| `8fafa4f` | Open Q1 — `namedKeys`/`namedValues` on `SandboxResult`      |
| `e3a30f8` | `app.research.sandbox.relaxed-grammar.enabled` flag (default `true`) |
| `1112f2a` | Layer 1c (array variant) — `return [a, b, c];` rewrite      |
| `1b3df9b` | Layer 1f — comma-separated `var` declarations               |
| `e3e524e` | Layer 1e — `break` / `continue` as real keywords            |
| `3d87b32` | Layer 1d — single-statement body after `if`/`while`/`for`   |
| `aae2e5c` | Layer 1c — return-position object-literal rewrite           |
| `bf053b4` | Layer 1b — `<ident>.length` → `len(<ident>)` rewrite        |
| `8ab45b0` | Layer 1a — `Math.<builtin>` → flat builtin rewrite          |
| `e7e4800` | Layer 2 — per-rejection rewrite hints                       |
| `cbb1155` | Tier 5 flag flip + scope wishlist P2 → this doc             |

**Layers 1d–1f are pure grammar additions beyond the original Layer 1
a/b/c scope** — added during implementation when the same corpus
sample showed JS-reflex syntax (`if (cond) return x;`, `break` inside
unbraced ifs, `var a = 1, b = 2;`) hitting rejects the rewrite-only
plan didn't cover. No rollback contract (flag-off does not revert
them) because they're strict supersets of the prior grammar.

**Open Question 1 — implementation note.** The shipped path keeps the
existing `recordSample` aggregates code (no semantic regression for
single-scalar tools) and side-channels the rewrite's key list to the
interpreter, which maintains a fixed-size ring buffer of the trailing
`k` recordSample values (where `k = namedKeys.size()`). End-of-run
pairing is exact even when the user's code emits its own
`recordSample` calls. `SandboxResult.namedKeys` / `namedValues` carry
the source-named labels for object returns and positional labels
(`"0"`, `"1"`, …) for array returns; `MathToolSuite` formats them as
a trailing `named: k=v, …` line in the LLM-facing prose.

**Pre-shipping snapshot of the scoping doc is preserved below for
reference.** Pull this doc into `archive/` once the two acceptance
refreshes confirm the closing condition.

**Why now:** REVIEW_REPORT refresh on 2026-05-18 logged 13 `sandbox code
rejected by 4b review: REJECTED (static)` events across 4 of 6 papers in
the run. The breakdown — 6 `member access not supported` (`.length`,
`.sqrt`, `.abs`, `.push`), 5 `parse failed` (block/string-literal),
2 `object literals not supported` — matches the wishlist's headline
failure cluster, and the new full-snippet trace from commit `cbdef7d`
will give per-refresh quantitative breakdowns going forward.

**Scope:** P2 layers 1 + 2 only. **Layer 3 (Python fallback) is explicitly
deferred** — see Open question 3.

---

## Goal

Drop sandbox-rejection rate by **≥50%** across a 30-paper batch after
shipping (matches the wishlist acceptance bar). Specifically:

- Object-literal returns (`return {mean: m, std: s}`) compile cleanly.
- Array returns (`return [a, b, c]`) compile cleanly.
- `Math.sqrt`, `Math.PI`, `Math.exp`, `Math.log`, `Math.sin`, `Math.cos`,
  `Math.E` resolve to the existing flat builtins.
- `arr.length` (zero-arg property access on identifier-typed arrays)
  compiles.
- Per-rule rejection message includes a concrete one-line rewrite the
  LLM can use without inventing.

Out of scope this phase: `.map / .filter / .reduce`, `for…of`, `let`,
arrow functions, destructuring, template literals, Python fallback.

---

## Files touched

| File | Why |
|------|-----|
| `src/main/java/com/emergentterrain/research/SandboxStaticChecker.java` | Loosen `preScanForJavascriptIdioms` MEMBER_ACCESS regex; add the `Math.*` rewrite and the `.length` allowlist; loosen the object/array-return reject. |
| `src/main/java/com/emergentterrain/research/SandboxEngine.java` | Lexer + parser support for the new constructs: tokenise `.` after an identifier as a property accessor (currently a parse error); accept `{key: value}` and `[a, b]` in return position; add `Math` namespace dispatch. New `Expr.Prop` and `Expr.ObjLit` / extension to `Expr.ArrayLit`-in-return AST nodes. |
| `src/main/java/com/emergentterrain/research/SandboxResult.java` (or wherever the result wrapper lives — verify) | Accept multi-scalar return; serialise object/array returns into the existing scalar-or-stats-summary shape. |
| `src/main/java/com/emergentterrain/service/MathToolSuite.java` | Update the in-prompt grammar reference (`SandboxStaticChecker.helpText()` call site at ~L577) to list the new allowed forms; the rejection message at L569 already carries the rule text via Phase 17 P8a's expansion. |
| `src/main/java/com/emergentterrain/research/SandboxStaticCheckerTest.java` (new tests in existing file) | Pin each newly-allowed form against an example that historically rejected; pin the unchanged forms still reject. |
| `src/main/resources/application.yml` | `app.research.sandbox.relaxed-grammar.enabled` flag, default **true** for fresh rooms / **false** for backfill rooms (see Open question 2 for the backfill semantic). |

No changes to `MathToolSuite.runSandboxedCode` ordering, `LlmTraceWriter`,
`SandboxEngine.MAX_ITERATIONS` (kept at the existing cap), or to the LLM
"intent" review stage (stage 2 of the existing two-stage review remains
advisory).

---

## Layered plan

### Layer 1 — Grammar relaxation (~3 days)

Add these to `SandboxStaticChecker.preScanForJavascriptIdioms`:

- **Allow `Math.<flatBuiltin>` rewrite.** When MEMBER_ACCESS matches and
  the receiver is the identifier `Math` AND the method name is in
  `SandboxEngine.BUILTINS`, return `Approved` (the lexer change in
  Layer 1.b rewrites the call to the flat form before parse). Keeps the
  existing rejection for unsupported `Math.*` names.
- **Allow `.length` property access on identifiers.** Property-access
  parse path returns a length value for array-typed locals; reject when
  the identifier is not array-typed (compile-time, not runtime).
- **Allow object-literal and array-literal returns in terminal-expression
  position only.** `return {a: x, b: y}` and `return [x, y, z]` parse;
  `var foo = {a: x};` does NOT (object identity for non-return positions
  is the bigger surface and stays deferred).

Add these to `SandboxEngine`:

- **Lexer change.** Treat `.` as a delimiter that begins a property
  accessor when it appears after an identifier token. New token
  `Token.DOT`. Existing `Token.NUMBER` keeps its `.` for decimals (the
  lexer already disambiguates via the preceding token class).
- **Parser change.** New `Expr.Prop(Expr receiver, String name)`;
  produced only for `<ident>.<ident>` sequences. New
  `Expr.ObjLit(List<String> keys, List<Expr> values)` and an extended
  `Expr.ArrayLit` accepted in `parseReturn()` only.
- **Eval change.** `Math.<name>(args)` rewrites to a `Call(name, args)`
  at parse time; `.length` on an array evaluates to the array's
  element count; `{a: x, b: y}` in return position serialises to the
  existing scalar-or-stats shape via SandboxResult's multi-field path
  (see Open question 1).

### Layer 2 — Per-failure correction template (~2 days)

Today's rejection messages already contain the rule text (Phase 17 P8a
expansion). Layer 2 adds a **one-line concrete rewrite** to each
remaining rejection class:

| Current rejection text                                    | Layer 2 addition                                                          |
|-----------------------------------------------------------|---------------------------------------------------------------------------|
| `member access not supported: '.push'`                    | `Rewrite: use a fixed-size array — declare `var xs = [0, 0, 0, …];` and assign `xs[i] = v` directly.` |
| `member access not supported: '.map'`                     | `Rewrite: replace `arr.map(f)` with an explicit `for (var i=0; i<len(arr); i++) { result[i] = f(arr[i]); }` loop.` |
| `parse failed: Expected LBRACE got 'break' line N`        | `Rewrite: 'break' must appear inside a `{` block. Wrap the conditional: `if (cond) { break; }` not `if (cond) break;`.` |
| `parse failed: Expected EQ got ',' line N`                | `Rewrite: declare one var per line. `var a = 1, b = 2;` is not allowed; use `var a = 1; var b = 2;`.` |
| (object-literal cases now allowed in return position) | — (Layer 1 closes this) |

Source the addition from a `SandboxStaticChecker.rewriteHintFor(String
rejectionReason)` static method so the rewrites stay in one place and the
existing `MathToolSuite:577` call (`com.emergentterrain.research.SandboxStaticChecker.helpText()`)
gets an overload that appends the per-rejection rewrite when the verdict
matches a known pattern. Falls back to the existing generic help text
when no specific rewrite applies.

### Layer 3 — Python-syntax fallback runtime (DEFERRED)

See Open question 3. **Do not implement this phase.** Revisit only after
Layer 1+2 ship and a refresh confirms the rejection rate floor.

---

## Open architecture questions (resolved)

### 1. Multi-scalar return serialisation: how does `recordSample` interact with object-literal returns?

**Question:** The existing sandbox returns a single scalar (`return v;`)
plus optional `recordSample(value)` aggregates (n/mean/std/min/max). When
the LLM returns `{rcrit: x, omega: y, qcrit: z}`, where does each value
go in the output shape?

**Resolution:** Object-literal return values flatten into a new
`{keys: [...], values: [...]}` field on `SandboxResult`. The existing
single-scalar `result=` line in `MathToolSuite`'s response prose is
replaced with a multi-line key=value listing when the returned shape is
an object. `recordSample` aggregates stay separate (they sit alongside,
not inside, the object). Array returns flatten to `result=[a, b, c]`
inline. No change to the existing single-scalar path.

### 2. Per-room flag semantics: do existing rooms inherit the relaxed grammar?

**Question:** The wishlist names
`app.research.sandbox.relaxed-grammar.enabled` as defaulting on for new
rooms / off for backfill. But the grammar lives in `SandboxEngine`, a
process-wide singleton. There is no per-room sandbox instance today.

**Resolution:** Flag is process-wide. **Default: true.** The "off for
backfill" wishlist note was written before Phase 14 settled the
replay-vs-fresh-corpus distinction; this codebase replays via re-running
the bots against the existing room and there is no rollback-to-old-prose
mode. Operators who want the old behaviour can flip the flag false
process-wide; per-room granularity is not needed.

### 3. Python fallback: should layer 3 ship?

**Question:** The wishlist names PyExecJS / Jython / RestrictedPython
as candidates with the explicit caveat "the static review can't bound
resource usage on Python the same way it can on the JS-flavoured grammar."

**Resolution:** **Defer indefinitely.** Three reasons:

1. **Resource bound is structural, not effort.** The sandbox grammar is
   small specifically so `SandboxEngine.MAX_ITERATIONS` and `MAX_STMTS`
   are statically enforceable. None of the candidate Python runtimes
   bound CPU/memory the same way; bounding them externally (process
   isolation + cgroups) is a different ops contract than what the
   sandbox provides today.
2. **The corpus says it isn't needed.** REVIEW_REPORT refresh #5 §7
   counted 13 sandbox rejections in this run; 11 of 13 are JS-flavoured
   constructs Layer 1+2 fix. The Python-syntax cases are <2 per refresh
   — a single-digit-of-percent improvement for a multi-thousand-line
   runtime swap. Wrong leverage.
3. **Layer 3 is the riskiest piece of the whole P2 plan.** The wishlist
   itself rates it "needs a sandbox-hardening review before shipping"
   and explicitly invites the operator to "ship layers 1+2 and skip
   layer 3" — which is what this scoping doc does.

Reopen only if a future refresh logs ≥10 Python-shaped sandbox attempts
in a single run (today: ~1 per run).

---

## Acceptance

After Layer 1+2 ship:

1. **Quantitative:** Next REVIEW_REPORT refresh under the new code shows
   `sandbox code rejected by 4b review` count ≤ ~6 per refresh (≥50%
   drop from today's 13).
2. **Per-paper:** The two RLC/VdW class papers in today's batch
   (P-a328-678, P-a328-683) when replayed produce at least one
   *successful* sandbox call (today: 0 successes on 678's two attempts;
   1 success on 683's five attempts after three rejections).
3. **No new false positives:** existing-grammar code that compiles today
   (e.g. the Phase 14 fixture corpus in
   `SandboxStaticCheckerTest.java`) continues to compile.

---

## Risk

**Medium.** Largest risks:

- **Lexer disambiguation of `.` between decimal and property accessor.**
  The existing `0.5` literal and the new `arr.length` access share a
  character. Existing parser already disambiguates via preceding-token
  class (digit-before-`.` is decimal; identifier-before-`.` is property).
  Verify in tests.
- **Object-literal return serialisation breaking downstream parsers.**
  `MathToolSuite` formats the sandbox result into a markdown string for
  the LLM prompt; downstream `NarrationResult` parsing reads `result=`
  from the prose. Multi-key returns change the `result=...` line shape.
  Confirm `NarrationResult` parsing is tolerant or update it.
- **`Math.sqrt` rewrite collision with a user function named `sqrt`.**
  The existing rejection message says "call `sqrt` flat"; if a user
  function `sqrt(x)` were declared, the rewrite would shadow it. The
  whitelist already reserves builtin names; verify the user-function
  declaration path rejects builtin names.

**Rollback:** Flip `app.research.sandbox.relaxed-grammar.enabled = false`
in `application.yml`. The static checker's `preScanForJavascriptIdioms`
reads the flag and reverts to today's stricter rules. SandboxEngine's
new parser nodes are still in the binary but only produced via the
relaxed-grammar path; under flag-off they don't fire.

---

## Cost

- Layer 1: ~3 dev-days. Mostly lexer + parser + one new AST node;
  serialisation change is small.
- Layer 2: ~2 dev-days. Rewrite-hint table + the new `rewriteHintFor`
  method + tests pinning each hint against an example.
- Tests: ~1 dev-day. ~15 new fixtures in `SandboxStaticCheckerTest`,
  ~5 in a new `SandboxEngineRelaxedGrammarTest`.

**Total: ~6 dev-days (1 week of focused work).** Below the wishlist's
~2-week estimate because layer 3 is dropped.

**Token-context cost:** zero — the help-text update is a few extra
sentences in the sandbox-rule prompt section; the rewrite hints fire
per-rejection (already a path that costs tokens today).

---

## What this phase deliberately does NOT include

- **`.map / .filter / .reduce` allowlist.** Needs iteration-cap
  propagation through the lambda body; that's a separate ~3-day chunk.
  Add to Phase 19 follow-on if a future refresh shows ≥5 attempts per
  run.
- **`for…of` over arrays.** Same iteration-cap issue; deferred.
- **`let` / arrow functions / destructuring.** Cosmetic JS niceties that
  the LLM emits, but rewriting to `var foo = function(x) { … };` adds
  parser complexity without changing what the LLM can compute. Deferred
  indefinitely.
- **Python fallback runtime.** See Open question 3.
- **A new sandbox-grammar reference page for the operator.** The
  in-prompt `helpText()` already covers everything an operator needs;
  documentation duplication invites drift.

---

## Closing condition

This phase closes when:

1. ✅ **Met (2026-05-18).** Layer 1 shipped (`8ab45b0` … `1112f2a`)
   plus 1d–1f bonus grammar; `SandboxStaticCheckerTest` (77 tests) and
   `SandboxEngineWhitelistTest`'s Phase-19 block (4 tests) pass. No
   separate `SandboxEngineRelaxedGrammarTest` was needed — the
   end-to-end coverage landed alongside the existing whitelist suite.
2. ✅ **Met (2026-05-18).** Layer 2 shipped (`e7e4800`);
   `SandboxStaticChecker.rewriteHintFor` and `MathToolSuite:582–585`
   wire the per-rule rewrite into the rejection message for the
   member-access (`.push`/`.pop`/…/`.map`/`.length`/`Math.*`),
   object-literal, parse-failed (`break` / `let` / comma-decl /
   string), and shadowed-builtin rejection classes — covers the five
   classes named in the Layer 2 table and four extras.
3. ⏳ **Operator action.** One REVIEW_REPORT refresh under the new
   binary shows `sandbox code rejected by 4b review` count ≤ ~6 per
   refresh (down from the 2026-05-18T07:21Z baseline of 13). The
   commit graph since `cbb1155` covers all four rejection clusters
   the baseline named; the refresh measures impact. Pair with the
   per-paper crit (P-a328-678 and P-a328-683 each produce ≥1
   successful sandbox call on replay) to fully discharge the
   acceptance bar.

Status: **CLOSED PENDING REFRESH.** Move this doc to `archive/` and
mark the wishlist's P2 row "SHIPPED — phase-19-sandbox-friction.md"
once condition 3 lands.
