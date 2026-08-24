# Contributing to EmergentTerrain

Thank you for helping improve EmergentTerrain. This repository is an experimental terrain-simulation
and computational-research project; changes should make its behavior, evidence, and operational
boundaries easier to understand and verify.

## Before you begin

Read the [README](README.md) for the project scope and supported startup paths. Use
[RUN-MINIMAL.md](RUN-MINIMAL.md) for concise commands and [RUN.md](RUN.md) for optional services
and data workflows. Java 21 and PostgreSQL are required for the main services; the Rust GPU engine
and Julia sidecars are optional unless your change uses them.

For a non-trivial feature, migration, or architecture change, open an issue or discussion first.
This avoids implementing a proposal that conflicts with an in-progress phase plan.

## Submit a change

1. Fork the repository and create a focused branch from `main`.
2. Make the smallest coherent change; do not mix refactoring, generated output, and behavior changes
   in one pull request.
3. Add or update tests that demonstrate changed behavior.
4. Run the relevant checks:

   ```bash
   mvn -f apps/game-service/pom.xml -DskipTests package
   mvn -f apps/game-service/pom.xml test -DtrimStackTrace=false
   cargo test --manifest-path engines/gpu-engine/Cargo.toml  # when changing the GPU engine
   python3 scripts/check-literature-release.py  # before a source archive/release
   ```

5. Explain the problem, approach, verification, and any configuration or migration impact in the
   pull request. Link the related issue where applicable.

## Project-specific expectations

- Do not commit credentials, API keys, downloaded corpora, papers, model weights, logs, Maven
  `target/` directories, `sample-output/`, or generated runtime/research data.
- Update `README.md` and `AGENTS.md` together when a supported behavior, setup workflow, or
  performance workflow changes.
- For changes across the game/research boundary, keep the OpenAPI contract, shared DTOs, validation,
  and contract tests aligned. Do not introduce game persistence types into `libs/research-contract/`.
- Research claims must remain traceable to executed tools and recorded evidence. Do not turn model
  narration, downloaded reviewer context, or a planning result into evidence without the documented
  validation path.
- Preserve third-party notices and dataset-specific terms. A repository-level code license does not
  replace a dataset, paper, or dependency license.

## Contribution license

By submitting a pull request, you represent that you have the right to submit it and license your
contribution under the repository's [AGPL-3.0-or-later license](LICENSE). No contributor license
agreement is required.

## Conduct and security

Follow [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md). Do not disclose suspected vulnerabilities in a
public issue; use the private reporting instructions in [SECURITY.md](SECURITY.md).
