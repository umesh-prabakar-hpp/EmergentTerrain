# Research API v1

`openapi.yaml` is the stable game-to-research read contract introduced by Phase 42. It is stored
beside the shared DTO module so Java types and the language-neutral HTTP contract evolve together.

During the compatibility stage, the game process serves both the legacy browser paths and the v1
paths through an in-process adapter. Set `app.research-api.mode=remote` and
`RESEARCH_API_BASE_URL` only when a separately deployed research service serves this exact v1
contract. The existing game UI remains on its legacy routes until that service cutover.

Research tick submission remains behind `ResearchTickGateway` while it depends on live game bot
and government objects. Its external action/snapshot contract must be added before remote mode is
used for game ticks; remote mode currently supports the two documented read operations.
