# EmergentTerrain

Spring Boot POC that turns a user profile into a signed QR payload and a deterministic, editable 2D game world.

## Stack
- Java 21, Spring Boot 3.x, Spring Security, Spring Data JPA (Hibernate)
- H2 (dev)
- Thymeleaf + Material Web (M3) components + minimal JS
- ZXing (QR generation)

## Features
- User registration + login (session-based)
- Profile creation (age, sex, location, yes/no preferences)
- Deterministic hidden physical properties derived from profile
- QR payload signing (HMAC), gzip + base64url encoding
- Negotiation-first multiplayer terrain (no solo mode)
- Neighbor-based consensus voting for 2x2 terrain changes
- Bots with persistent profiles and visible positions
- High-speed highway corridors (precomputed)

## Run
```powershell
mvn spring-boot:run
```
Open `http://localhost:8080/`

## H2 Console
- URL: `http://localhost:8080/h2`
- JDBC URL: `jdbc:h2:file:C:/dev/ide/eclipse/workspace-web/EmergentTerrain/data/emergentdb`
- User: `sa`
- Password: (blank)

## UI Assets
Material Web, Roboto, and Material Symbols are loaded from CDN in the templates.

## Configuration
See `src/main/resources/application.yml` for app settings:
- `app.qr.hmacSecret`
- `app.world.auto`, `app.world.maxTiles`, `app.world.aspectRatio`, `app.world.highwayRatio`
- `app.consensus.neighborRadius`, `app.consensus.voteWindowSeconds`, `app.consensus.minNeighbors`
- `app.consensus.noParticipationLockSeconds`
- `app.density.areaSize`, `app.density.maxOccupants`
- `app.bots.populationFraction`

## Routes (summary)
- `GET /register`, `POST /register`
- `GET /login`, `POST /login`
- `GET /profile/new`, `POST /profile`
- `GET /qr?token=...`
- `GET /game?token=...`
- `POST /game/state` (world seed + regions)
- `POST /room/create`
- `POST /room/join`
- `POST /room/{roomId}/state`
- `POST /room/{roomId}/move`
- `POST /room/{roomId}/propose`
- `POST /room/{roomId}/vote`
- `POST /room/{roomId}/apply` (returns error: auto-apply on majority)
- `POST /room/{roomId}/memory`
- `POST /room/{roomId}/bot`
- `POST /room/{roomId}/resonance`

## Controls
- Move: `WASD` or arrow keys
- Highways: moving while on a highway tile jumps 4 tiles per keypress
- Propose change: click a tile to mark a 2x2 area, choose a tool, then press `Apply Changes`
- Vote: select a proposal then use `Approve` or `Reject`
- Bots: open the Companions panel and select a bot to get a prompt

## Consensus Rules
- Only neighbors within `neighborRadius` can vote
- At least `minNeighbors` must be nearby to submit a proposal
- Non-participating neighbors count as NO after the vote window
- Two missed votes locks a player from play for `noParticipationLockSeconds`

## Notes
- This is a POC focused on end-to-end flow, not production hardening.
- The QR payload is signed (tamper detection) but not encrypted.
