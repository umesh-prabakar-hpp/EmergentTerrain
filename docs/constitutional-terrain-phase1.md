# Constitutional Terrain Phase 1

## Repo Discovery Summary
- Spring Boot remains the authoritative simulation layer.
- [`src/main/java/com/emergentterrain/service/RoomService.java`](C:/dev/ide/eclipse/workspace-web/EmergentTerrain/src/main/java/com/emergentterrain/service/RoomService.java) already owns room creation, bot maintenance, cached room state, and the room/web payloads.
- The browser already has a live room panel and websocket refresh path in [`src/main/resources/static/js/game.js`](C:/dev/ide/eclipse/workspace-web/EmergentTerrain/src/main/resources/static/js/game.js).
- The Rust GPU engine is not changed in this phase; this slice stays CPU-authoritative and stores new civic/government state in PostgreSQL JSON text columns.

## Architecture Fit Assessment
- Added government state as serialized JSON on `room_state`.
- Added civic state as serialized JSON on `room_bots`.
- Reused room maintenance as the deterministic government clock.
- Reused the room response payload as the first visibility surface.
- Reused append-only file output for auditable history under `simulation-history/`.

## Government To Game Mapping
| Real concept | Phase 1 game model |
| --- | --- |
| Federal government | `GovernmentState.federal` |
| States | `GovernmentState.states` |
| Parties | `GovernmentState.parties` |
| Bills | `GovernmentState.bills` |
| Elections | `GovernmentState.elections` |
| Citizens / households | `BotCivicState` on each bot |
| Budgets | `GovernmentState.BudgetState` |
| Constitution | `src/main/resources/government/constitution-rules.json` |

## Constitution Rule Table
- Machine-readable rules now live in [`src/main/resources/government/constitution-rules.json`](C:/dev/ide/eclipse/workspace-web/EmergentTerrain/src/main/resources/government/constitution-rules.json).
- Coverage in this phase:
  - Articles I-VII
  - Amendments I-XXVII
- Each rule stores:
  - `ruleId`
  - `articleOrAmendment`
  - `category`
  - `trigger`
  - `enforcement`
  - `affectedSystems`

## Bot Characteristics Delta
- Reused:
  - bot id
  - room id
  - type
  - position
  - terrain preference profile
- Added persistent civic fields:
  - household id
  - district id
  - state id
  - legal status
  - age tier
  - employment status
  - occupation sector
  - wage / burden / savings / debt bands
  - party id
  - ideology vector
  - rights satisfaction
  - institutional trust
  - safety perception
  - service access
  - civic participation
  - voter eligibility
  - office / term / procedural / sanction states

## New Domain Model
- [`src/main/java/com/emergentterrain/government/BotCivicState.java`](C:/dev/ide/eclipse/workspace-web/EmergentTerrain/src/main/java/com/emergentterrain/government/BotCivicState.java)
- [`src/main/java/com/emergentterrain/government/GovernmentState.java`](C:/dev/ide/eclipse/workspace-web/EmergentTerrain/src/main/java/com/emergentterrain/government/GovernmentState.java)
- [`src/main/java/com/emergentterrain/government/GovernmentSimulationService.java`](C:/dev/ide/eclipse/workspace-web/EmergentTerrain/src/main/java/com/emergentterrain/government/GovernmentSimulationService.java)
- [`src/main/java/com/emergentterrain/government/SimulationHistoryWriter.java`](C:/dev/ide/eclipse/workspace-web/EmergentTerrain/src/main/java/com/emergentterrain/government/SimulationHistoryWriter.java)
- [`src/main/java/com/emergentterrain/web/GovernmentSummary.java`](C:/dev/ide/eclipse/workspace-web/EmergentTerrain/src/main/java/com/emergentterrain/web/GovernmentSummary.java)

## Spring Boot Change Plan
### Implemented in this phase
- Modify [`src/main/java/com/emergentterrain/domain/RoomState.java`](C:/dev/ide/eclipse/workspace-web/EmergentTerrain/src/main/java/com/emergentterrain/domain/RoomState.java)
  - adds `governmentStateJson`
- Modify [`src/main/java/com/emergentterrain/domain/RoomBot.java`](C:/dev/ide/eclipse/workspace-web/EmergentTerrain/src/main/java/com/emergentterrain/domain/RoomBot.java)
  - adds `civicStateJson`
- Modify [`src/main/java/com/emergentterrain/service/RoomService.java`](C:/dev/ide/eclipse/workspace-web/EmergentTerrain/src/main/java/com/emergentterrain/service/RoomService.java)
  - initializes civic/government state on room creation
  - ticks government state during room maintenance
  - persists append-only history indirectly through `GovernmentSimulationService`
  - exposes `GovernmentSummary` and `BotCivicState` through room responses
- Modify [`src/main/java/com/emergentterrain/web/RoomStateResponse.java`](C:/dev/ide/eclipse/workspace-web/EmergentTerrain/src/main/java/com/emergentterrain/web/RoomStateResponse.java)
- Modify [`src/main/java/com/emergentterrain/web/RoomCreateResponse.java`](C:/dev/ide/eclipse/workspace-web/EmergentTerrain/src/main/java/com/emergentterrain/web/RoomCreateResponse.java)
- Modify [`src/main/java/com/emergentterrain/web/RoomBotInfo.java`](C:/dev/ide/eclipse/workspace-web/EmergentTerrain/src/main/java/com/emergentterrain/web/RoomBotInfo.java)

### Deferred
- Explicit court case entities and APIs
- State/federal conflict resolution queues
- Constitutional amendment gameplay
- Enforcement agencies and warrants

## Rust GPU Change Plan
- Deferred to a later phase.
- The future GPU slice should move large-scale civic scoring, election aggregation, and service-field generation into packed buffers once the Java-side rules stabilize.

## Frontend / WebGPU Change Plan
### Implemented in this phase
- [`src/main/resources/templates/game.html`](C:/dev/ide/eclipse/workspace-web/EmergentTerrain/src/main/resources/templates/game.html)
  - adds a Government panel section
- [`src/main/resources/static/js/game.js`](C:/dev/ide/eclipse/workspace-web/EmergentTerrain/src/main/resources/static/js/game.js)
  - renders `GovernmentSummary`
  - renders civic-state badges and trust/rights/service/participation bars per bot
- [`src/main/resources/static/css/app.css`](C:/dev/ide/eclipse/workspace-web/EmergentTerrain/src/main/resources/static/css/app.css)
  - adds civic meta styling

### Deferred
- WebGPU historical timeline replay
- WebGPU district borders / capitol overlays
- Government institution map markers

## Event Flow
1. Room created
2. Government state bootstrapped from constitutional rules
3. Bots receive civic-state bootstrap
4. Room maintenance ticks government on its slower cadence
5. Party counts, budgets, trust, services, and elections update deterministically
6. Bill lifecycle advances through staged machine states
7. Major events and elections write immutable JSON files under `simulation-history/`

## Persistence / Schema Changes
- `room_state.government_state_json`
- `room_bots.civic_state_json`
- `simulation-history/constitution`
- `simulation-history/events`
- `simulation-history/elections`
- `simulation-history/courts`
- `simulation-history/legislation`

## Config Changes
- `app.government.enabled`
- `app.government.maintenanceIntervalMs`
- `app.government.electionIntervalMs`
- `app.government.billIntervalMs`
- `app.government.stateCount`
- `app.simulationHistory.root`

## API Payload Changes
- `RoomStateResponse.governmentSummary`
- `RoomCreateResponse.governmentSummary`
- `RoomBotInfo.civicState`

## Ordered Implementation Phases
1. Foundation model and append-only history
2. Deterministic party, budget, election, and bill skeleton
3. Frontend visibility
4. State/federal disputes and courts
5. Economy and services deeper loops
6. GPU acceleration of stable civic kernels

## Acceptance Tests
- Build passes with new columns auto-created by Hibernate
- Fresh room creation produces `governmentStateJson`
- Existing rooms without civic/government JSON self-heal on next load or maintenance cycle
- `simulation-history/` receives immutable JSON files for bootstrap, elections, and legislation
- Browser room panel shows government summary and civic badges

## Risks, Deferrals, And Simplifications
- This phase does not yet add explicit court dockets, warrants, or agency actors.
- Constitutional rules are loaded and recorded, but only a subset is actively enforced by runtime logic so far.
- The Rust GPU engine remains unchanged; this is intentionally a server-first foundation slice.
