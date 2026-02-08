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
- In-app game rendering from QR payload
- Terrain editing with explanation + confirmation capture

## Run
```powershell
mvn spring-boot:run
```
Open `http://localhost:8080/`

## H2 Console
- URL: `http://localhost:8080/h2`
- JDBC URL: `jdbc:h2:mem:emergentdb`
- User: `sa`
- Password: (blank)

## UI Assets
Material Web, Roboto, and Material Symbols are loaded from CDN in the templates.

## Configuration
See `src/main/resources/application.yml` for app settings:
- `app.qr.hmacSecret`
- `app.world.width`, `app.world.height`

## Routes (summary)
- `GET /register`, `POST /register`
- `GET /login`, `POST /login`
- `GET /profile/new`, `POST /profile`
- `GET /qr?token=...`
- `GET /game?token=...`
- `POST /game/state`
- `POST /game/update`
- `POST /game/confirm`

## Notes
- This is a POC focused on end-to-end flow, not production hardening.
- The QR payload is signed (tamper detection) but not encrypted.
