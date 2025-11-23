# Jookbox Database Design Documentation

## Overview
The Jookbox backend uses **PostgreSQL** for persistent data storage and **Redis** for transient playback state. The database schema is managed using **Flyway** migrations.

---

## Database Schema

### 1. **Users Table**
Stores user information for both hosts and guests.

```sql
CREATE TABLE users (
    id UUID PRIMARY KEY,
    display_name VARCHAR(120) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

#### Columns
| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `id` | UUID | PRIMARY KEY | Unique user identifier |
| `display_name` | VARCHAR(120) | NOT NULL | User's display name (1-120 chars) |
| `created_at` | TIMESTAMPTZ | NOT NULL, DEFAULT NOW() | Account creation timestamp |

#### Indexing
- Primary key on `id` (automatic)

#### Relationships
- Referenced by `memberships.user_id` (foreign key)
- Referenced by `queue_items.added_by` (foreign key)
- Referenced by `votes.user_id` (foreign key)

#### Notes
- Each user created is associated with exactly one membership per room
- A user can join multiple rooms by creating new user records with the same display name
- Immutable after creation

---

### 2. **Rooms Table**
Represents collaborative video playback sessions.

```sql
CREATE TABLE rooms (
    id UUID PRIMARY KEY,
    code VARCHAR(12) NOT NULL UNIQUE,
    host_id UUID NOT NULL REFERENCES users (id),
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

#### Columns
| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `id` | UUID | PRIMARY KEY | Unique room identifier |
| `code` | VARCHAR(12) | UNIQUE, NOT NULL | 6-character alphanumeric code (A-Z 0-9) |
| `host_id` | UUID | FOREIGN KEY, NOT NULL | References the host user |
| `status` | VARCHAR(20) | NOT NULL | Enum: `ACTIVE` or `ENDED` |
| `created_at` | TIMESTAMPTZ | NOT NULL, DEFAULT NOW() | Room creation timestamp |

#### Indexing
- Primary key on `id` (automatic)
- Unique constraint on `code` (automatic)

#### Enum Values: `RoomStatus`
- `ACTIVE` - Room is currently accepting members and queue operations
- `ENDED` - Room is closed; no new operations allowed

#### Relationships
- Foreign key to `users.id` on `host_id`
- Referenced by `memberships.room_id` (foreign key)
- Referenced by `queue_items.room_id` (foreign key)

#### Notes
- Room code is randomly generated using `ABCDEFGHJKLMNPQRSTUVWXYZ23456789` character set
- Room code is 6 characters long (collision probability negligible)
- Each room has exactly one host (the creator)
- Rooms are immutable except for status transitions

---

### 3. **Memberships Table**
Links users to rooms with role and permission assignment.

```sql
CREATE TABLE memberships (
    id UUID PRIMARY KEY,
    room_id UUID NOT NULL REFERENCES rooms (id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role VARCHAR(20) NOT NULL,
    capabilities INTEGER NOT NULL,
    joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_membership UNIQUE (room_id, user_id)
);
```

#### Columns
| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `id` | UUID | PRIMARY KEY | Unique membership identifier |
| `room_id` | UUID | FOREIGN KEY, NOT NULL, ON DELETE CASCADE | References room |
| `user_id` | UUID | FOREIGN KEY, NOT NULL, ON DELETE CASCADE | References user |
| `role` | VARCHAR(20) | NOT NULL | Enum: `HOST` or `GUEST` |
| `capabilities` | INTEGER | NOT NULL | Bitmask of granted capabilities |
| `joined_at` | TIMESTAMPTZ | NOT NULL, DEFAULT NOW() | Membership creation timestamp |

#### Unique Constraints
- `UNIQUE (room_id, user_id)` - A user can have at most one membership per room

#### Indexing
- Primary key on `id` (automatic)
- Unique constraint on `(room_id, user_id)`

#### Enum Values: `Role`
- `HOST` - Room creator; full permissions to manage room and members
- `GUEST` - Regular member; limited permissions based on capabilities

#### Capabilities Bitmask
Capabilities are stored as a 32-bit integer using bitwise operations:

| Capability | Bit Mask | Value | Description |
|------------|----------|-------|-------------|
| `PLAYBACK_CONTROL` | 1 | 0x1 | Can play/pause/seek playback |
| `REORDER_QUEUE` | 2 | 0x2 | Can reorder queue items |
| `REMOVE_ITEMS` | 4 | 0x4 | Can remove items from queue |
| `SKIP_OVERRIDE` | 8 | 0x8 | Can force skip via vote (host only) |

**Example Combinations:**
- Host: `15` (0xF = all capabilities: 1 + 2 + 4 + 8)
- Guest (default): `0` (no capabilities)
- DJ Guest: `3` (1 + 2 = playback + reorder)

#### Relationships
- Foreign key to `rooms.id` on `room_id`
- Foreign key to `users.id` on `user_id`
- Referenced by `votes.user_id` (indirectly)

#### Notes
- Cascade delete: removing a room or user removes associated memberships
- Host is created as a member during room creation with full capabilities
- Guests can have capabilities granted by the host

---

### 4. **Queue Items Table**
Represents videos in a room's playback queue.

```sql
CREATE TABLE queue_items (
    id UUID PRIMARY KEY,
    room_id UUID NOT NULL REFERENCES rooms (id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    video_id VARCHAR(64) NOT NULL,
    title VARCHAR(300) NOT NULL,
    duration_seconds INTEGER NOT NULL,
    thumb_url VARCHAR(500),
    added_by UUID NOT NULL REFERENCES users (id),
    status VARCHAR(20) NOT NULL,
    enqueued_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_queue_room_position ON queue_items(room_id, position);
```

#### Columns
| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `id` | UUID | PRIMARY KEY | Unique queue item identifier |
| `room_id` | UUID | FOREIGN KEY, NOT NULL, ON DELETE CASCADE | References room |
| `position` | INTEGER | NOT NULL | Ordinal position in queue (0-based); -1 if removed |
| `video_id` | VARCHAR(64) | NOT NULL | External video identifier (e.g., YouTube ID) |
| `title` | VARCHAR(300) | NOT NULL | Video title |
| `duration_seconds` | INTEGER | NOT NULL | Video duration in seconds |
| `thumb_url` | VARCHAR(500) | NULLABLE | Video thumbnail URL |
| `added_by` | UUID | FOREIGN KEY, NOT NULL | User who added the item |
| `status` | VARCHAR(20) | NOT NULL | Enum: `QUEUED`, `PLAYING`, `PLAYED`, `REMOVED` |
| `enqueued_at` | TIMESTAMPTZ | NOT NULL, DEFAULT NOW() | Item creation timestamp |

#### Indexing
- Primary key on `id` (automatic)
- Composite index on `(room_id, position)` for efficient queue ordering and filtering

#### Enum Values: `QueueItemStatus`
- `QUEUED` - Waiting in queue to be played
- `PLAYING` - Currently playing
- `PLAYED` - Successfully finished or skipped
- `REMOVED` - Removed by vote or user action

#### Relationships
- Foreign key to `rooms.id` on `room_id`
- Foreign key to `users.id` on `added_by`
- Referenced by `votes.queue_item_id` (foreign key)

#### Notes
- Position is contiguous (0, 1, 2, ...) for active items (QUEUED/PLAYING)
- Position is -1 for inactive items (PLAYED/REMOVED)
- When items are removed, positions are reindexed to maintain order
- Title and duration are immutable snapshots at enqueue time

---

### 5. **Votes Table**
Tracks skip and remove votes on queue items.

```sql
CREATE TABLE votes (
    id UUID PRIMARY KEY,
    queue_item_id UUID NOT NULL REFERENCES queue_items (id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_vote_per_user UNIQUE (queue_item_id, user_id, type)
);
```

#### Columns
| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `id` | UUID | PRIMARY KEY | Unique vote identifier |
| `queue_item_id` | UUID | FOREIGN KEY, NOT NULL, ON DELETE CASCADE | References queue item |
| `user_id` | UUID | FOREIGN KEY, NOT NULL, ON DELETE CASCADE | References voting user |
| `type` | VARCHAR(20) | NOT NULL | Enum: `SKIP` or `REMOVE` |
| `created_at` | TIMESTAMPTZ | NOT NULL, DEFAULT NOW() | Vote creation timestamp |

#### Unique Constraints
- `UNIQUE (queue_item_id, user_id, type)` - A user can vote once per type per item

#### Indexing
- Primary key on `id` (automatic)
- Unique constraint on `(queue_item_id, user_id, type)`

#### Enum Values: `VoteType`
- `SKIP` - Vote to skip to next item
- `REMOVE` - Vote to remove item from queue

#### Voting Threshold Logic
- **Threshold** = `MAX(1, CEIL(total_members / 2))`
- When votes >= threshold, the vote outcome is applied
- Host votes automatically apply outcome (threshold bypass)

#### Relationships
- Foreign key to `queue_items.id` on `queue_item_id`
- Foreign key to `users.id` on `user_id`

#### Notes
- Cascade delete: removing queue items or users removes associated votes
- One user cannot vote multiple times for the same type on the same item
- Host can bypass voting by directly performing actions (playback_control capability)

---

## Indexes & Query Performance

### Indexes Summary
| Table | Index | Columns | Purpose |
|-------|-------|---------|---------|
| users | PK | id | Primary lookup |
| rooms | PK | id | Primary lookup |
| rooms | UQ | code | Room code lookup (room joining) |
| memberships | PK | id | Primary lookup |
| memberships | UQ | (room_id, user_id) | Membership lookup and duplicate prevention |
| queue_items | PK | id | Primary lookup |
| queue_items | Composite | (room_id, position) | Queue ordering and filtering |
| votes | PK | id | Primary lookup |
| votes | UQ | (queue_item_id, user_id, type) | Vote validation and lookup |

### Common Queries
1. **Get room by code** → Uses index on `rooms.code`
2. **Get user's membership in room** → Uses composite index on `memberships(room_id, user_id)`
3. **Get queue for room** → Uses composite index on `queue_items(room_id, position)`
4. **Check if vote exists** → Uses composite index on `votes(queue_item_id, user_id, type)`

---

## Foreign Key Cascades

| FK Relationship | Delete Behavior |
|-----------------|-----------------|
| `memberships.room_id` → `rooms.id` | CASCADE - Remove all memberships when room deleted |
| `memberships.user_id` → `users.id` | CASCADE - Remove membership when user deleted |
| `queue_items.room_id` → `rooms.id` | CASCADE - Remove queue items when room deleted |
| `queue_items.added_by` → `users.id` | RESTRICT (implicit) - Cannot delete user if queue items exist |
| `votes.queue_item_id` → `queue_items.id` | CASCADE - Remove votes when item deleted |
| `votes.user_id` → `users.id` | CASCADE - Remove votes when user deleted |

**Note**: When a room is deleted, cascading deletes remove all memberships and queue items, which in turn cascade delete all votes.

---

## Data Integrity Constraints

### Uniqueness Constraints
1. **Room Code Uniqueness** - Each room must have a unique, non-nullable code
2. **One Membership Per Room/User** - Enforced by unique constraint on `(room_id, user_id)`
3. **One Vote Per User Per Type Per Item** - Enforced by unique constraint on `(queue_item_id, user_id, type)`

### Non-Null Constraints
- All `_id` foreign keys are NOT NULL
- All timestamp columns are NOT NULL
- `position` is NOT NULL for all queue items
- `capabilities` is NOT NULL (defaults to 0)

### Enum Validation
- Enums are stored as VARCHAR and validated at application level
- Valid values enforced by Spring JPA enum mapping

---

## Redis Storage

### Playback State
Playback state is stored in Redis for real-time synchronization and reduced database load.

**Key Pattern**: `playback:{roomCode}`

**Value** (JSON):
```json
{
  "roomId": "550e8400-e29b-41d4-a716-446655440000",
  "nowPlayingQueueItemId": "550e8400-e29b-41d4-a716-446655440001",
  "positionMs": 12345,
  "playing": true,
  "lastUpdateTs": 1234567890000
}
```

**TTL**: No explicit TTL (persists until room deleted or playback updated)
**Consistency**: Source of truth for playback state; not persisted to database

---

## Migration Strategy

### Flyway Migrations
- Location: `src/main/resources/db/migration/`
- Naming: `V{version}__{description}.sql`
- Current version: `V1__init.sql`

### V1__init.sql Contents
- Creates all 5 tables with appropriate constraints
- Defines foreign key relationships with cascade deletes
- Creates composite index on `queue_items(room_id, position)`

### Future Migrations
Example pattern for adding columns:
```sql
-- V2__add_room_description.sql
ALTER TABLE rooms ADD COLUMN description VARCHAR(500);
```

---

## Data Types Rationale

| Type | Usage | Rationale |
|------|-------|-----------|
| UUID | All PKs and FKs | Better distribution than sequential IDs; no central sequence required |
| VARCHAR(n) | Code, names, enums | Fixed-width string columns with constraints |
| INTEGER | Position, capabilities, duration | Efficient for numeric operations and bitmasks |
| TIMESTAMPTZ | Timestamps | Timezone-aware; consistent across deployments |
| Boolean (INTEGER) | Stored as 0/1 in votes | PostgreSQL has native BOOLEAN but INTEGER used for simplicity |

---

## Backup & Recovery

### Recommended Backup Strategy
1. **Full backups**: Daily PostgreSQL dump
2. **Incremental WAL archiving**: Continuous write-ahead logs
3. **Point-in-time recovery**: Restore from full backup + WAL files

### Data Retention
- **Soft deletes**: Not implemented; data is hard-deleted on cascade
- **Audit logging**: Not implemented; consider adding `updated_at` timestamp for future audits

---

## Scaling Considerations

### Current Limitations
- Room member limit: 10 (enforced in `RoomService`)
- Room code length: 6 characters (~1.7 billion possible codes)
- Queue item title: 300 characters max

### Optimization Opportunities
1. **Partitioning**: Partition `queue_items` by `room_id` if many large queues
2. **Archival**: Archive old rooms (status=ENDED) to separate schema
3. **Materialized views**: For room statistics (member count, queue length)
4. **Redis clustering**: For multi-node playback state distribution

### Projected Capacity
- **Rooms**: ~100M (UUID space)
- **Members per room**: 10 (enforced)
- **Queue items per room**: Unlimited (no constraint)
- **Storage**: ~1KB per queue item; 10M items = ~10GB

---

## Summary

The Jookbox database schema is optimized for:
- ✅ Real-time queue and playback synchronization
- ✅ Permission and role-based access control (RBAC)
- ✅ Voting threshold calculations
- ✅ Efficient queue ordering and filtering
- ✅ Data integrity via cascading deletes and unique constraints

The combination of PostgreSQL (persistent) and Redis (transient state) provides a balance between durability and performance.
