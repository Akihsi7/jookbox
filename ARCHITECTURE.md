# Jookbox Backend Architecture & Flow

## Overview
- **Stack:** Spring Boot 3.5 (MVC + STOMP WebSocket), Security (JWT), JPA (PostgreSQL), Redis (playback state/cache), Flyway (migrations), Gradle build.
- **Purpose:** Multi-user room for synchronized YouTube playback with shared queue, permissions, votes, and real-time updates.

## Modules & Packages
- `config/`
  - `SecurityConfig` — Stateless JWT auth; permits `/ws`, `/rooms` create/join; everything else authenticated.
  - `WebSocketConfig` — STOMP endpoint `/ws`, broker prefix `/topic`, app prefix `/app`.
  - `JwtProperties` — JWT secret/issuer/expiry bound from `application.yaml`.
- `security/`
  - `JwtService` — Issue/parse JWTs containing membership/room info + capabilities.
  - `JwtAuthenticationFilter` — Extract Bearer token, set `MemberAuthentication` in security context.
  - `AuthenticatedMember`/`MemberAuthentication` — Principal and authorities (capability-based).
- `domain/` (JPA entities & enums)
  - `User`, `Room`, `Membership`, `QueueItem`, `Vote`; enums: `Role`, `RoomStatus`, `QueueItemStatus`, `VoteType`, `Capability` (bitmask).
  - `PlaybackState` — Snapshot stored in Redis (roomId, nowPlaying, positionMs, playing, timestamp).
- `repository/`
  - Spring Data JPA repos for each entity (User/Room/Membership/QueueItem/Vote).
- `service/`
  - `RoomService` — Create/join room, generate room codes, enforce room limit, build JWT.
  - `QueueService` — Enqueue/move/remove items, position management, broadcast queue over `/topic/rooms/{code}/queue`.
  - `PlaybackService` — Play/pause/seek; persists state in Redis; broadcasts `/topic/rooms/{code}/playback`.
  - `VoteService` — Vote skip/remove; threshold logic; applies outcome and rebroadcasts queue.
  - `PermissionService` — Host grants capabilities to memberships.
- `web/rest/` (Controllers)
  - `RoomController` — `/rooms` create/join, get queue.
  - `QueueController` — `/rooms/{code}/queue` add/move/remove.
  - `PlaybackController` — `/rooms/{code}/playback` get/play/pause/seek.
  - `VoteController` — Vote skip/remove endpoints.
  - `PermissionController` — Update member capabilities.
- `web/dto/`
  - Request/response models for API payloads (room create/join, queue add/move, playback play/seek, permission updates, queue views, playback state).
- `web/ApiExceptionHandler` + exceptions — Consistent error responses for 400/403/404.
- `resources/`
  - `application.yaml` — DB/Redis/JWT config; JPA/Flyway settings; logging overrides.
  - `db/migration/V1__init.sql` — Flyway migration creating all tables + constraints.

## Data Flow (Happy Path)
1) **Create Room** (`POST /rooms`): `RoomService` creates User(host), Room(code), Membership(host with full capabilities), returns JWT.
2) **Join Room** (`POST /rooms/{code}/join`): creates guest User + Membership (no caps), returns JWT.
3) **Queue Add** (`POST /rooms/{code}/queue`): Auth via JWT; create QueueItem with next position; broadcast queue to `/topic/rooms/{code}/queue`.
4) **Playback Control** (`POST /rooms/{code}/playback/play|pause|seek`): Requires `PLAYBACK_CONTROL`; updates Redis playback state; broadcasts to `/topic/rooms/{code}/playback`.
5) **Vote Skip/Remove** (`POST /rooms/{code}/queue/{itemId}/vote-*`): Stores vote; threshold → mark item played/removed, reindex queue, broadcast queue.
6) **Permissions** (`POST /rooms/{code}/permissions/{membershipId}`): Host assigns capabilities; stored as bitmask on membership.

## Real-Time Channels
- STOMP over `/ws`, broker `/topic`.
- Subscriptions:
  - `/topic/rooms/{code}/queue` — queue snapshots on add/move/remove/vote outcome.
  - `/topic/rooms/{code}/playback` — playback state broadcasts on play/pause/seek.

## Persistence & State
- **PostgreSQL**: Users, Rooms, Memberships (role + capabilities mask), QueueItems (positioned, status), Votes (unique per user/type/item).
- **Redis**: Playback state per room (authoritative position/flag/timestamp).

## Validation & Security
- JWT carries room scope; controllers rely on `@AuthenticationPrincipal AuthenticatedMember`.
- Capabilities enforced in services (e.g., reorder/remove/playback_control).
- Validation via `spring-boot-starter-validation` on DTOs; exceptions mapped to 4xx responses.

## Run/Build Notes
- Toolchain in `build.gradle` targets Java 25; override with `JAVA_HOME` or change toolchain if you want Java 21 by default.
- `./gradlew.bat clean build` runs tests (uses Testcontainers for Postgres/Redis).
- `./gradlew.bat bootRun` starts the app (config from env/app yaml). Remove `PORT` override to default to 8080.
