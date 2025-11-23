# Jookbox

A collaborative, real-time synchronized video playback platform that allows multiple users to watch YouTube videos together in shared rooms with democratic voting, queue management, and role-based permissions.

## Overview

Jookbox enables seamless group video watching experiences by combining:
- **Real-time synchronization** via WebSocket (STOMP)
- **Shared playback control** with role-based permissions
- **Democratic queue management** with skip/remove voting
- **Fine-grained access control** via capability-based permissions

Perfect for watch parties, team meetings, or collaborative entertainment!

## Tech Stack

### Backend
- **Framework**: Spring Boot 3.5.7
- **Language**: Java 21
- **API**: REST + WebSocket (STOMP)
- **Authentication**: JWT (stateless)
- **Database**: PostgreSQL
- **Caching**: Redis
- **Migrations**: Flyway
- **Build**: Gradle

### Key Dependencies
- Spring Data JPA
- Spring Security
- Spring WebSocket
- JWT (JJWT)
- Lombok
- Testcontainers (testing)

## Project Structure

```
src/
├── main/
│   ├── java/com/dev/jookbox/
│   │   ├── config/              # Configuration (JWT, Security, WebSocket)
│   │   ├── domain/              # JPA entities and enums
│   │   ├── repository/          # Spring Data JPA repositories
│   │   ├── security/            # JWT and authentication logic
│   │   ├── service/             # Business logic (Room, Queue, Playback, Vote, Permission)
│   │   └── web/                 # REST controllers, DTOs, and exception handling
│   └── resources/
│       ├── application.yaml     # Configuration
│       └── db/migration/        # Flyway migrations
└── test/                        # Integration and unit tests
```

## Core Features

### 1. Room Management
- **Create Room**: Host generates a 6-character room code
- **Join Room**: Guests join using room code (max 10 members per room)
- **Room Status**: ACTIVE (accepting operations) or ENDED (closed)

### 2. Queue Management
- **Add Items**: Members enqueue YouTube videos with metadata
- **Reorder**: Members with `REORDER_QUEUE` capability can move items
- **Remove**: Members with `REMOVE_ITEMS` capability can delete items
- **Real-time Sync**: All queue changes broadcast via WebSocket

### 3. Playback Control
- **Playback State**: Stored in Redis for low-latency access
- **Play/Pause/Seek**: Requires `PLAYBACK_CONTROL` capability
- **Host Control**: Host always has full playback control
- **Real-time Sync**: All members see synchronized playback state

### 4. Democratic Voting
- **Vote Types**: SKIP (mark as played) or REMOVE (delete from queue)
- **Voting Threshold**: Requires majority vote (50% + 1 of room members)
- **Host Bypass**: Host votes automatically apply outcomes
- **Vote Limits**: One vote per user per type per item

### 5. Permission Management
- **Roles**: HOST (room creator) or GUEST (members)
- **Capabilities**: Bitmask-based fine-grained permissions
  - `PLAYBACK_CONTROL` - Play/pause/seek
  - `REORDER_QUEUE` - Move items in queue
  - `REMOVE_ITEMS` - Delete items from queue
  - `SKIP_OVERRIDE` - Force skip capability
- **Host-Only**: Only hosts can grant/revoke capabilities

## Database Design

### Tables
- **Users**: Display name and creation metadata
- **Rooms**: Room code, host reference, and status
- **Memberships**: User-room mappings with role and capabilities (bitmask)
- **QueueItems**: Video metadata, position, status, and enqueued timestamp
- **Votes**: Skip/remove votes with user and item tracking

### Storage Strategy
- **PostgreSQL**: All persistent data (users, rooms, memberships, queue, votes)
- **Redis**: Playback state (for real-time synchronization and performance)

### Indexing
- Room code lookup (unique)
- Membership lookup (room + user composite)
- Queue ordering (room + position composite)
- Vote uniqueness (item + user + type composite)

## API Endpoints

### Authentication
All endpoints except room creation/joining require JWT Bearer token:
```
Authorization: Bearer <JWT_TOKEN>
```

### Room Management
- `POST /rooms` - Create room
- `POST /rooms/{code}/join` - Join existing room
- `GET /rooms/{code}/queue` - Get queue (no auth)
- `GET /rooms/{code}/playback` - Get playback state (no auth)

### Queue Operations
- `POST /rooms/{code}/queue` - Add item
- `PUT /rooms/{code}/queue/{itemId}/move` - Reorder item
- `DELETE /rooms/{code}/queue/{itemId}` - Remove item
- `POST /rooms/{code}/queue/{itemId}/vote-skip` - Vote skip
- `POST /rooms/{code}/queue/{itemId}/vote-remove` - Vote remove

### Playback Control
- `POST /rooms/{code}/playback/play` - Start playback
- `POST /rooms/{code}/playback/pause` - Pause playback
- `POST /rooms/{code}/playback/seek` - Seek to position

### Permissions
- `POST /rooms/{code}/permissions/{membershipId}` - Update capabilities (HOST only)

### WebSocket Topics
- `/topic/rooms/{code}/queue` - Queue updates
- `/topic/rooms/{code}/playback` - Playback state updates

For detailed API documentation, see [API_DOCUMENTATION.md](API_DOCUMENTATION.md)

## Getting Started

### Prerequisites
- Java 21+
- PostgreSQL 12+
- Redis 6+
- Gradle 8+

### Local Development

#### Setup
```bash
# Clone repository
git clone https://github.com/Akihsi7/jookbox.git
cd jookbox

# Set environment variables
export JOOKBOX_JWT_SECRET=your-secret-key-here
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/jookbox
export SPRING_DATASOURCE_USERNAME=postgres
export SPRING_DATASOURCE_PASSWORD=your-password
export SPRING_REDIS_HOST=localhost
export SPRING_REDIS_PORT=6379
```

#### Using Docker Compose
```bash
# Start PostgreSQL and Redis
docker-compose up -d

# Build and run application
./gradlew clean build
./gradlew bootRun
```

#### Manual Setup
```bash
# Ensure PostgreSQL and Redis are running, then:
./gradlew clean build
./gradlew bootRun
```

The application will start on `http://localhost:8080`

### Testing
```bash
# Run all tests (uses Testcontainers for PostgreSQL/Redis)
./gradlew test

# Run specific test class
./gradlew test --tests RoomServiceTest
```

## Configuration

### Application Properties
See `src/main/resources/application.yaml` for full configuration:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/jookbox
    username: postgres
    password: ${SPRING_DATASOURCE_PASSWORD}
  redis:
    host: ${SPRING_REDIS_HOST:localhost}
    port: ${SPRING_REDIS_PORT:6379}
  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: false

jookbox:
  jwt:
    secret: ${JOOKBOX_JWT_SECRET}
    issuer: jookbox
    expiry-minutes: 1440
```

### JWT Configuration
- **Secret**: Set via `JOOKBOX_JWT_SECRET` environment variable
- **Issuer**: `jookbox`
- **Expiry**: 24 hours (configurable)
- **Claims**: Membership ID, User ID, Room ID, Room Code, Role, Capabilities

## Service Layer Architecture

### Services
1. **RoomService** - Room creation, joining, member limits, JWT generation
2. **QueueService** - Queue operations (add, move, remove) with real-time sync
3. **PlaybackService** - Playback state management (play, pause, seek) via Redis
4. **VoteService** - Voting logic with threshold-based outcomes
5. **PermissionService** - Capability management (HOST only)

### Transaction Management
- All services are transactional (`@Transactional`)
- Database operations are atomic with rollback support
- WebSocket broadcasts occur after successful transaction commit

### Broadcasting Pattern
- Queue changes → `/topic/rooms/{code}/queue`
- Playback changes → `/topic/rooms/{code}/playback`
- Real-time client updates via STOMP subscription

For detailed service documentation, see [SERVICE_DOCUMENTATION.md](SERVICE_DOCUMENTATION.md)

## Architecture

### Authentication Flow
1. Client creates/joins room (unauthenticated)
2. Server generates JWT containing membership info
3. Client includes JWT in `Authorization: Bearer` header for subsequent requests
4. `JwtAuthenticationFilter` extracts and validates token
5. `AuthenticatedMember` principal set in security context

### Authorization Model
- **Role-Based** (RBAC): HOST vs GUEST roles
- **Capability-Based** (CBAC): Fine-grained permissions (bitmask)
- **Scoped**: Each token tied to specific membership and room

### Real-Time Synchronization
- STOMP WebSocket connection on `/ws` endpoint
- Topic-based message routing (`/topic/rooms/{code}/*`)
- Clients subscribe to rooms for live updates
- Services broadcast after state changes

For full architecture details, see [ARCHITECTURE.md](ARCHITECTURE.md)

## Database Schema

### Design Principles
- UUID primary keys for distributed compatibility
- Cascading deletes maintain referential integrity
- Composite indexes for efficient queue and vote queries
- Playback state in Redis (not database) for performance

### Key Relationships
```
User ──┬─→ Membership ──→ Room
       ├─→ QueueItem ──→ Room
       └─→ Vote ──→ QueueItem
```

For full schema documentation, see [DATABASE_DESIGN.md](DATABASE_DESIGN.md)

## Performance Considerations

### Optimizations
- **Redis Caching**: Playback state for <100ms latency
- **Composite Indexes**: Efficient queue and vote lookups
- **Lazy Loading Prevention**: Careful query design to avoid N+1 problems
- **Connection Pooling**: HikariCP for database connections

### Scalability Limits (Current)
- Room member limit: 10 (enforced)
- Queue item title: 300 characters
- Room code: 6 characters (~1.7 billion possible codes)

### Capacity Estimates
- ~100M possible rooms (UUID space)
- ~10GB storage per 10M queue items
- Real-time sync latency: <100ms average

## Development Workflow

### Code Organization
- Controllers handle HTTP mapping and validation
- Services contain business logic and transactions
- Repositories provide data access
- DTOs encapsulate request/response payloads
- Exceptions mapped to HTTP status codes

### Testing Strategy
- Unit tests mock repositories and services
- Integration tests use Testcontainers (PostgreSQL + Redis)
- Test utilities in `TestcontainersConfiguration`
- Tests run with `UTC` timezone for consistency

### Building & Running

```bash
# Build
./gradlew clean build

# Run tests
./gradlew test

# Start application
./gradlew bootRun

# Package as JAR
./gradlew build -x test
```

## Running via PowerShell

```powershell
# Windows PowerShell startup
./run-local.ps1

# Or manually
./gradlew.bat bootRun
```

## Contributing

1. Create a feature branch (`git checkout -b feature/amazing-feature`)
2. Make changes and test locally (`./gradlew test`)
3. Commit changes (`git commit -m 'Add amazing feature'`)
4. Push to branch (`git push origin feature/amazing-feature`)
5. Open a pull request

## Key Files

| File | Purpose |
|------|---------|
| `ARCHITECTURE.md` | High-level architecture and data flow |
| `SERVICE_DOCUMENTATION.md` | Detailed service layer documentation |
| `API_DOCUMENTATION.md` | Complete REST + WebSocket API reference |
| `DATABASE_DESIGN.md` | Schema, indexing, and data design |
| `build.gradle` | Gradle configuration and dependencies |
| `src/main/resources/application.yaml` | Application configuration |
| `src/main/resources/db/migration/V1__init.sql` | Database initialization |

## Troubleshooting

### Common Issues

**"Room not found"**
- Verify room code is correct (case-sensitive, 6 characters)
- Check room hasn't been ended

**"Unauthorized (401)"**
- Ensure JWT token is in `Authorization: Bearer <token>` format
- Check token hasn't expired (24-hour default)
- Verify token matches room code

**"Permission denied (403)"**
- Verify membership has required capability
- For admin operations, ensure user is HOST

**WebSocket connection fails**
- Check `/ws` endpoint is accessible
- Verify firewall allows WebSocket connections
- Check browser console for error details

**Database connection error**
- Verify PostgreSQL is running and accessible
- Check `SPRING_DATASOURCE_URL` and credentials
- Ensure database exists (Flyway will create tables)

**Redis connection error**
- Verify Redis is running
- Check `SPRING_REDIS_HOST` and `SPRING_REDIS_PORT`
- Verify network connectivity to Redis server

## Future Enhancements

### Planned Features
- [ ] Room password protection
- [ ] User authentication/login (OAuth2)
- [ ] Persistent user accounts
- [ ] YouTube API integration for video validation
- [ ] Room history and replay functionality
- [ ] Recommendation engine
- [ ] Advanced voting mechanisms
- [ ] Playlist support

### Known Limitations
- Single deployment (no horizontal scaling)
- No video format validation
- No room persistence after shutdown
- Limited to 10 members per room

## License

This project is part of the Jookbox platform. See repository for license details.

## Support & Documentation

For more information:
- 📚 [Full API Documentation](API_DOCUMENTATION.md)
- 🏗️ [Architecture Overview](ARCHITECTURE.md)
- 📋 [Service Documentation](SERVICE_DOCUMENTATION.md)
- 🗄️ [Database Design](DATABASE_DESIGN.md)
- 📖 [Runbook](RUNBOOK.md)

## Contact

GitHub: [@Akihsi7](https://github.com/Akihsi7)
Repository: [jookbox](https://github.com/Akihsi7/jookbox)