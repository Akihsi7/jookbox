# Jookbox Service Layer Documentation

## Overview
The service layer contains the core business logic for Jookbox. It orchestrates repository operations, enforces validation rules, manages transactional consistency, and broadcasts real-time updates via WebSocket.

---

## Architecture Pattern

```
Controller → Service → Repository → Database
      ↓
  WebSocket Broadcast
```

- **Controllers**: HTTP request routing and validation (via DTOs)
- **Services**: Business logic, transactions, authorization, WebSocket publishing
- **Repositories**: Data access layer (Spring Data JPA)
- **Database**: PostgreSQL persistence

---

## Services Overview

| Service | Purpose | Key Methods |
|---------|---------|------------|
| `RoomService` | Room creation, joining, code generation | `createRoom()`, `joinRoom()` |
| `QueueService` | Queue operations (add, move, remove) | `enqueue()`, `move()`, `removeItem()`, `getQueue()` |
| `PlaybackService` | Playback state management | `play()`, `pause()`, `seek()`, `getState()` |
| `VoteService` | Voting logic, threshold calculation | `vote()`, `applyOutcome()` |
| `PermissionService` | Capability management | `updateCapabilities()` |

---

## 1. RoomService

### Purpose
Manages room lifecycle: creation, joining, member limits, and JWT generation.

### Dependencies
```java
private final RoomRepository roomRepository;
private final UserRepository userRepository;
private final MembershipRepository membershipRepository;
private final JwtService jwtService;
```

### Method: createRoom()

**Signature**
```java
@Transactional
public MembershipTokenResponse createRoom(RoomCreationRequest request)
```

**Parameters**
| Parameter | Type | Description |
|-----------|------|-------------|
| `request` | RoomCreationRequest | Contains `hostDisplayName` |

**Returns**
`MembershipTokenResponse` containing:
- `roomCode` - 6-character code
- `token` - JWT with full capabilities
- `role` - Always `HOST`
- `capabilities` - All 4 capabilities enabled

**Business Logic**
1. Generate timestamp: `OffsetDateTime.now()`
2. Create User entity (host) with random UUID
3. Generate unique 6-character room code
4. Create Room entity with generated code and ACTIVE status
5. Create Membership with HOST role and all capabilities:
   - `PLAYBACK_CONTROL` (1)
   - `REORDER_QUEUE` (2)
   - `REMOVE_ITEMS` (4)
   - `SKIP_OVERRIDE` (8)
   - Total bitmask: `15` (0xF)
6. Generate JWT token containing membership info
7. Return token response

**Transactions**
- `@Transactional` ensures atomicity
- All 3 entities (User, Room, Membership) created together
- If any operation fails, all are rolled back

**Error Handling**
- No explicit error handling (validation done in controller)
- Exceptions propagated to controller for handling

**Room Code Generation**
```java
private String generateUniqueCode() {
    String code;
    do {
        code = randomCode();
    } while (roomRepository.existsByCode(code));
    return code;
}

private String randomCode() {
    StringBuilder sb = new StringBuilder(6);
    for (int i = 0; i < 6; i++) {
        int idx = secureRandom.nextInt(ROOM_CODE_CHARS.length());
        sb.append(ROOM_CODE_CHARS.charAt(idx));
    }
    return sb.toString();
}
```

**Character Set**: `ABCDEFGHJKLMNPQRSTUVWXYZ23456789` (34 chars, no ambiguous chars)
**Collision Probability**: ~1 in 1.4 billion (for 6 digits with 34 chars)

**Example Flow**
```
Request: hostDisplayName = "John Doe"
1. Create User: id=UUID1, displayName="John Doe", createdAt=NOW
2. Generate code: "ABC123"
3. Create Room: id=UUID2, code="ABC123", hostId=UUID1, status=ACTIVE
4. Create Membership: id=UUID3, userId=UUID1, roomId=UUID2, role=HOST, capabilities=15
5. Generate JWT: {membershipId: UUID3, userId: UUID1, roomId: UUID2, roomCode: "ABC123", ...}
6. Return: {roomCode: "ABC123", token: "eyJh...", role: HOST, capabilities: [...]}
```

---

### Method: joinRoom()

**Signature**
```java
@Transactional
public MembershipTokenResponse joinRoom(String roomCode, JoinRoomRequest request)
```

**Parameters**
| Parameter | Type | Description |
|-----------|------|-------------|
| `roomCode` | String | 6-character room code |
| `request` | JoinRoomRequest | Contains `displayName` |

**Returns**
`MembershipTokenResponse` containing:
- `roomCode` - Same as input
- `token` - JWT with no capabilities
- `role` - Always `GUEST`
- `capabilities` - Empty set

**Business Logic**
1. Look up Room by code
   - Throws `ResourceNotFoundException` if not found
2. Verify room status is `ACTIVE`
   - Throws `BadRequestException` if not active
3. Count current members
   - Throws `BadRequestException` if >= 10 (room full)
4. Create User entity (guest) with random UUID
5. Create Membership with:
   - `role = GUEST`
   - `capabilities = 0` (no permissions)
6. Generate JWT token with guest scope
7. Return token response

**Validation Rules**
- Room must exist
- Room must be ACTIVE (not ENDED)
- Room must have < 10 members (hard limit)
- Display name must be non-blank (validated in controller)

**Error Scenarios**
| Scenario | Exception | HTTP Status |
|----------|-----------|-------------|
| Room not found | `ResourceNotFoundException` | 404 |
| Room is ENDED | `BadRequestException` | 400 |
| Room full (10+ members) | `BadRequestException` | 400 |

**Transactions**
- `@Transactional` ensures atomicity
- User + Membership created together
- If count check passes, join always succeeds

**Example Flow**
```
Request: roomCode = "ABC123", displayName = "Jane Smith"
1. Look up Room by code "ABC123" → found
2. Check status = ACTIVE ✓
3. Count members → 2 (< 10) ✓
4. Create User: id=UUID4, displayName="Jane Smith", createdAt=NOW
5. Create Membership: id=UUID5, userId=UUID4, roomId=UUID2, role=GUEST, capabilities=0
6. Generate JWT: {membershipId: UUID5, userId: UUID4, roomId: UUID2, roomCode: "ABC123", role: GUEST, capabilities: []}
7. Return: {roomCode: "ABC123", token: "eyJh...", role: GUEST, capabilities: []}
```

---

## 2. QueueService

### Purpose
Manages queue operations: adding, moving, removing items with real-time synchronization.

### Dependencies
```java
private final RoomRepository roomRepository;
private final QueueItemRepository queueItemRepository;
private final MembershipRepository membershipRepository;
private final SimpMessagingTemplate messagingTemplate;
```

### Method: getQueue()

**Signature**
```java
@Transactional
public QueueResponse getQueue(String roomCode)
```

**Parameters**
| Parameter | Type | Description |
|-----------|------|-------------|
| `roomCode` | String | Room code |

**Returns**
`QueueResponse` with filtered list of QueueItemView objects

**Business Logic**
1. Look up Room by code
   - Throws `ResourceNotFoundException` if not found
2. Get all queue items for room ordered by position
3. Filter to only QUEUED or PLAYING items
4. Convert to QueueItemView objects
5. Return wrapped in QueueResponse

**Ordering**
- Items sorted by `position` ascending (0, 1, 2, ...)
- PLAYING item typically at position 0
- QUEUED items follow

**Filtering**
- Excludes PLAYED items (status = PLAYED)
- Excludes REMOVED items (status = REMOVED)
- These have `position = -1` in database

---

### Method: enqueue()

**Signature**
```java
@Transactional
public QueueItemView enqueue(String roomCode, AuthenticatedMember member, QueueAddRequest request)
```

**Parameters**
| Parameter | Type | Description |
|-----------|------|-------------|
| `roomCode` | String | Room code |
| `member` | AuthenticatedMember | Authenticated user from JWT |
| `request` | QueueAddRequest | Video details to add |

**Returns**
`QueueItemView` of newly created queue item

**Business Logic**
1. Look up Room by code
   - Throws `ResourceNotFoundException` if not found
2. Verify room status is ACTIVE
   - Throws `BadRequestException` if not active
3. Look up Membership by ID from authenticated member
   - Throws `ForbiddenOperationException` if not found
4. Verify membership belongs to this room
   - Throws `ForbiddenOperationException` if mismatch
5. Calculate next position:
   - Count all active items (QUEUED or PLAYING)
   - Next position = count
6. Create QueueItem with:
   - `position = calculated`
   - `status = QUEUED`
   - `addedBy = membership.user`
   - `enqueuedAt = NOW`
7. Broadcast queue update to `/topic/rooms/{code}/queue`
8. Return QueueItemView of new item

**Position Calculation Example**
```
Current queue:
0: PLAYING - Video A
1: QUEUED - Video B
2: QUEUED - Video C

New item position = 3 (count of active items)

After enqueue:
0: PLAYING - Video A
1: QUEUED - Video B
2: QUEUED - Video C
3: QUEUED - Video D (new)
```

**Validation Rules**
- Room must exist and be ACTIVE
- User must be member of room
- All video details must be provided (validated in controller)

**Broadcast**
```
Topic: /topic/rooms/ABC123/queue
Payload: QueueResponse with all queue items
```

---

### Method: move()

**Signature**
```java
@Transactional
public QueueResponse move(String roomCode, UUID itemId, QueueMoveRequest request, AuthenticatedMember member)
```

**Parameters**
| Parameter | Type | Description |
|-----------|------|-------------|
| `roomCode` | String | Room code |
| `itemId` | UUID | Item to move |
| `request` | QueueMoveRequest | New position |
| `member` | AuthenticatedMember | Authenticated user |

**Returns**
`QueueResponse` with reordered queue

**Business Logic**
1. Verify user has `REORDER_QUEUE` capability
   - Throws `ForbiddenOperationException` if not
2. Look up Room by code
   - Throws `ResourceNotFoundException` if not found
3. Verify room status is ACTIVE
   - Throws `BadRequestException` if not active
4. Verify membership exists and belongs to room
   - Throws `ForbiddenOperationException` if not
5. Look up queue item by ID
   - Throws `ResourceNotFoundException` if not found
6. Verify item belongs to this room
   - Throws `BadRequestException` if not
7. Get all queue items, sorted by position
8. Find current index of target item in list
9. Remove item from current position
10. Insert item at new position (clamped to 0..size)
11. Reindex all items (0, 1, 2, ...)
12. Save all items to database
13. Broadcast queue update
14. Return updated QueueResponse

**Position Clamping**
```java
int newIndex = Math.min(request.newPosition(), items.size());
```

**Example Reordering**
```
Before: A(0), B(1), C(2), D(3)
Move D to position 1

1. Remove D from position 3: A(0), B(1), C(2)
2. Insert D at position 1: A(0), D, B(1), C(2)
3. Reindex: A(0), D(1), B(2), C(3)

After: A(0), D(1), B(2), C(3)
```

**Broadcast**
```
Topic: /topic/rooms/ABC123/queue
Payload: QueueResponse with reordered items
```

---

### Method: removeItem()

**Signature**
```java
@Transactional
public void removeItem(String roomCode, UUID itemId, AuthenticatedMember member)
```

**Parameters**
| Parameter | Type | Description |
|-----------|------|-------------|
| `roomCode` | String | Room code |
| `itemId` | UUID | Item to remove |
| `member` | AuthenticatedMember | Authenticated user |

**Returns**
Void (204 No Content)

**Business Logic**
1. Look up Room by code
   - Throws `ResourceNotFoundException` if not found
2. Verify room status is ACTIVE
   - Throws `BadRequestException` if not active
3. Verify membership exists and belongs to room
   - Throws `ForbiddenOperationException` if not
4. Look up queue item by ID
   - Throws `ResourceNotFoundException` if not found
5. Verify item belongs to this room
   - Throws `BadRequestException` if not
6. Check authorization:
   - User must have `REMOVE_ITEMS` capability OR be HOST
   - Throws `ForbiddenOperationException` if neither
7. Get all queue items for room, sorted by position
8. Set target item:
   - `status = REMOVED`
   - `position = -1`
9. Remove target item from list and reindex remaining items (0, 1, 2, ...)
10. Save target item and all remaining items
11. Broadcast queue update
12. Return void

**Authorization Rules**
```java
boolean canRemove = member.capabilities().contains(Capability.REMOVE_ITEMS.name()) 
                 || member.role() == Role.HOST;
```

**Example Removal**
```
Before: A(0), B(1), C(2), D(3)
Remove B

1. Set B: status=REMOVED, position=-1
2. Remove B from active list: A(0), C(1), D(2)
3. Reindex: A(0), C(1), D(2)

After (active): A(0), C(1), D(2)
After (database): A(0), B(REMOVED, -1), C(1), D(2)
```

**Broadcast**
```
Topic: /topic/rooms/ABC123/queue
Payload: QueueResponse with remaining items (B not included)
```

---

### Helper: broadcastQueue()

**Signature**
```java
private void broadcastQueue(String roomCode)
```

**Implementation**
```java
private void broadcastQueue(String roomCode) {
    QueueResponse payload = getQueue(roomCode);
    messagingTemplate.convertAndSend("/topic/rooms/" + roomCode + "/queue", payload);
}
```

**Purpose**
- Centralized WebSocket broadcasting
- Ensures all queue changes are synchronized to connected clients

---

### Helper: toView()

**Signature**
```java
private QueueItemView toView(QueueItem item)
```

**Transformation**
Converts JPA entity to DTO:
- Maps all fields
- Extracts `addedBy.displayName` as string
- Handles null checks

---

## 3. PlaybackService

### Purpose
Manages playback state stored in Redis with real-time broadcasting.

### Dependencies
```java
private final RoomRepository roomRepository;
private final QueueItemRepository queueItemRepository;
private final StringRedisTemplate redisTemplate;
private final ObjectMapper objectMapper;
private final SimpMessagingTemplate messagingTemplate;
```

### Playback State Model

**Java Entity**
```java
@Value
@Builder
public class PlaybackState {
    UUID roomId;
    UUID nowPlayingQueueItemId;
    int positionMs;
    boolean playing;
    Instant lastUpdateTs;
}
```

**Redis Key**: `playback:{roomCode}`

**Redis Value** (JSON serialized)
```json
{
  "roomId": "550e8400-e29b-41d4-a716-446655440000",
  "nowPlayingQueueItemId": "550e8400-e29b-41d4-a716-446655440001",
  "positionMs": 12345,
  "playing": true,
  "lastUpdateTs": 1700727000
}
```

**No TTL**: State persists indefinitely until explicitly updated or room deleted

---

### Method: getState()

**Signature**
```java
public Optional<PlaybackStateResponse> getState(String roomCode)
```

**Parameters**
| Parameter | Type | Description |
|-----------|------|-------------|
| `roomCode` | String | Room code |

**Returns**
`Optional<PlaybackStateResponse>` - empty if no playback started

**Business Logic**
1. Read state from Redis using key `playback:{roomCode}`
2. If not found, return empty Optional
3. If found, deserialize JSON to PlaybackState
4. Convert to PlaybackStateResponse DTO
5. Return Optional

**Response Format**
```json
{
  "nowPlayingQueueItemId": "550e8400-e29b-41d4-a716-446655440001",
  "positionMs": 45000,
  "playing": true,
  "lastUpdateTs": 1700727000000
}
```

---

### Method: play()

**Signature**
```java
@Transactional
public PlaybackStateResponse play(String roomCode, UUID queueItemId, 
                                   AuthenticatedMember member, int positionMs)
```

**Parameters**
| Parameter | Type | Description |
|-----------|------|-------------|
| `roomCode` | String | Room code |
| `queueItemId` | UUID | Item to play |
| `member` | AuthenticatedMember | Authenticated user |
| `positionMs` | Integer | Starting position (ms) |

**Returns**
`PlaybackStateResponse` with new playback state

**Business Logic**
1. Verify user has `PLAYBACK_CONTROL` capability
   - Throws `ForbiddenOperationException` if not
2. Verify user's token matches room code
   - Throws `ForbiddenOperationException` if not
3. Ensure room exists
   - Throws `ResourceNotFoundException` if not
4. Look up queue item by ID
   - Throws `ResourceNotFoundException` if not found
5. Verify item belongs to this room
   - Throws `ResourceNotFoundException` if not
6. Create new PlaybackState:
   - `roomId = item.room.id`
   - `nowPlayingQueueItemId = queueItemId`
   - `positionMs = provided`
   - `playing = true`
   - `lastUpdateTs = Instant.now()`
7. Write state to Redis
8. Broadcast state to `/topic/rooms/{code}/playback`
9. Return response

**Broadcast**
```
Topic: /topic/rooms/ABC123/playback
Payload: PlaybackStateResponse
```

---

### Method: pause()

**Signature**
```java
@Transactional
public PlaybackStateResponse pause(String roomCode, AuthenticatedMember member)
```

**Parameters**
| Parameter | Type | Description |
|-----------|------|-------------|
| `roomCode` | String | Room code |
| `member` | AuthenticatedMember | Authenticated user |

**Returns**
`PlaybackStateResponse` with paused state

**Business Logic**
1. Verify user has `PLAYBACK_CONTROL` capability
2. Get current state from Redis
   - Throws `ResourceNotFoundException` if not found
3. Create new PlaybackState:
   - Copy `roomId`, `nowPlayingQueueItemId`, `positionMs` from current
   - Set `playing = false`
   - Update `lastUpdateTs = Instant.now()`
4. Write state to Redis
5. Broadcast state
6. Return response

**State Preservation**
- Position and item remain unchanged
- Only toggle playing flag

---

### Method: seek()

**Signature**
```java
@Transactional
public PlaybackStateResponse seek(String roomCode, int positionMs, AuthenticatedMember member)
```

**Parameters**
| Parameter | Type | Description |
|-----------|------|-------------|
| `roomCode` | String | Room code |
| `positionMs` | Integer | New position (ms) |
| `member` | AuthenticatedMember | Authenticated user |

**Returns**
`PlaybackStateResponse` with new position

**Business Logic**
1. Verify user has `PLAYBACK_CONTROL` capability
2. Get current state from Redis
   - Throws `ResourceNotFoundException` if not found
3. Create new PlaybackState:
   - Copy `roomId`, `nowPlayingQueueItemId` from current
   - Set `positionMs = provided`
   - Preserve `playing` flag
   - Update `lastUpdateTs = Instant.now()`
4. Write state to Redis
5. Broadcast state
6. Return response

**No Bounds Checking**
- Position can exceed video duration (client responsible)
- Negative positions allowed (treated as 0)

---

### Helper: writeState()

**Signature**
```java
private void writeState(String roomCode, PlaybackState state)
```

**Implementation**
```java
private void writeState(String roomCode, PlaybackState state) {
    try {
        String json = objectMapper.writeValueAsString(state);
        redisTemplate.opsForValue().set(playbackKey(roomCode), json);
    } catch (JsonProcessingException e) {
        throw new IllegalStateException("Failed to serialize playback state", e);
    }
}
```

**Serialization**
- Jackson ObjectMapper converts PlaybackState to JSON
- Set in Redis as string (no TTL)

---

### Helper: readState()

**Signature**
```java
private Optional<PlaybackState> readState(String roomCode)
```

**Implementation**
```java
private Optional<PlaybackState> readState(String roomCode) {
    String json = redisTemplate.opsForValue().get(playbackKey(roomCode));
    if (json == null) {
        return Optional.empty();
    }
    try {
        return Optional.of(objectMapper.readValue(json, PlaybackState.class));
    } catch (JsonProcessingException e) {
        return Optional.empty();
    }
}
```

**Deserialization**
- Returns empty Optional if key not found
- Returns empty Optional if JSON parsing fails
- Silently handles JSON errors (defensive programming)

---

## 4. VoteService

### Purpose
Manages voting on queue items with threshold-based outcomes.

### Dependencies
```java
private final VoteRepository voteRepository;
private final QueueItemRepository queueItemRepository;
private final RoomRepository roomRepository;
private final MembershipRepository membershipRepository;
private final SimpMessagingTemplate messagingTemplate;
```

### Method: vote()

**Signature**
```java
@Transactional
public boolean vote(String roomCode, UUID itemId, VoteType type, AuthenticatedMember member)
```

**Parameters**
| Parameter | Type | Description |
|-----------|------|-------------|
| `roomCode` | String | Room code |
| `itemId` | UUID | Item to vote on |
| `type` | VoteType | SKIP or REMOVE |
| `member` | AuthenticatedMember | Authenticated user |

**Returns**
`boolean` - true if vote outcome was applied, false if pending

**Business Logic**

**Phase 1: Validation**
1. Look up Room by code
   - Throws `ResourceNotFoundException` if not found
2. Verify room status is ACTIVE
   - Throws `ForbiddenOperationException` if not active
3. Look up queue item by ID
   - Throws `ResourceNotFoundException` if not found
4. Verify item belongs to this room
   - Throws `BadRequestException` if not

**Phase 2: Check if Host**
```java
if (member.role() == Role.HOST && member.roomId().equals(room.getId())) {
    applyOutcome(type, item, roomCode);
    return true;
}
```
- If user is HOST of this room, automatically apply outcome
- HOST bypasses voting threshold

**Phase 3: Guest Voting**
1. Look up Membership for user in room
   - Throws `ForbiddenOperationException` if not found
2. Verify membership belongs to this room
   - Throws `ForbiddenOperationException` if not
3. Check if vote already exists for user/type/item
   - Throws `ForbiddenOperationException` if already voted
4. Create and save Vote entity
5. Count total votes for this item/type
6. Calculate threshold:
   ```java
   long required = Math.max(1, (totalMembers / 2) + 1);
   ```
   Examples:
   - 1 member: threshold = 1
   - 2 members: threshold = 2
   - 3 members: threshold = 2
   - 4 members: threshold = 3
   - 5 members: threshold = 3
   - 10 members: threshold = 6
7. If votes >= threshold, apply outcome
   ```java
   if (votes >= required) {
       applyOutcome(type, item, roomCode);
       return true;
   }
   ```
8. Return false (vote recorded but not applied)

**Threshold Logic**
- Requires strict majority (> 50%)
- Minimum threshold of 1
- Ensures outcome requires at least half the room + 1

---

### Method: applyOutcome()

**Signature**
```java
private void applyOutcome(VoteType type, QueueItem item, String roomCode)
```

**Parameters**
| Parameter | Type | Description |
|-----------|------|-------------|
| `type` | VoteType | SKIP or REMOVE |
| `item` | QueueItem | Item affected |
| `roomCode` | String | Room code (for broadcast) |

**Business Logic**

**Step 1: Update Item Status**
```java
if (type == VoteType.SKIP) {
    item.setStatus(QueueItemStatus.PLAYED);
} else {
    item.setStatus(QueueItemStatus.REMOVED);
}
item.setPosition(-1);
```

**Step 2: Get Remaining Items**
```java
var remaining = queueItemRepository.findByRoomOrderByPosition(item.getRoom()).stream()
    .filter(q -> !q.getId().equals(item.getId()))
    .filter(q -> q.getStatus() == QueueItemStatus.QUEUED || q.getStatus() == QueueItemStatus.PLAYING)
    .toList();
```

**Step 3: Reindex Remaining Items**
```java
for (int i = 0; i < remaining.size(); i++) {
    remaining.get(i).setPosition(i);
}
```

**Step 4: Save All**
```java
queueItemRepository.save(item);
queueItemRepository.saveAll(remaining);
```

**Step 5: Broadcast Update**
- Convert remaining items to QueueItemView list
- Send QueueResponse to `/topic/rooms/{roomCode}/queue`

**Example Outcome**
```
Before:
0: PLAYING - Video A
1: QUEUED - Video B (voted skip)
2: QUEUED - Video C

Vote applied (type=SKIP):
- Video B status → PLAYED, position → -1
- Video A position → 0 (unchanged)
- Video C position → 2 → 1 (reindexed)

After (database):
0: PLAYING - Video A
1: QUEUED - Video C
-1: PLAYED - Video B (not included in queue)

Broadcast QueueResponse:
- Item A (position 0)
- Item C (position 1)
```

---

## 5. PermissionService

### Purpose
Manages capability assignment for room members (HOST only).

### Dependencies
```java
private final MembershipRepository membershipRepository;
private final RoomRepository roomRepository;
```

### Method: updateCapabilities()

**Signature**
```java
@Transactional
public Set<String> updateCapabilities(String roomCode, UUID membershipId, 
                                       PermissionUpdateRequest request, AuthenticatedMember actor)
```

**Parameters**
| Parameter | Type | Description |
|-----------|------|-------------|
| `roomCode` | String | Room code |
| `membershipId` | UUID | Membership to update |
| `request` | PermissionUpdateRequest | New capabilities |
| `actor` | AuthenticatedMember | Authenticated user (must be HOST) |

**Returns**
`Set<String>` of updated capability names

**Business Logic**

**Phase 1: Authorization**
1. Look up Room by code
   - Throws `ResourceNotFoundException` if not found
2. Verify actor is HOST
   - Throws `ForbiddenOperationException` if not HOST
3. Verify actor's room matches room code
   - Throws `ForbiddenOperationException` if not

**Phase 2: Update**
1. Look up Membership by ID
   - Throws `ResourceNotFoundException` if not found
2. Convert capability strings to enum set:
   ```java
   Set<Capability> caps = request.capabilities().stream()
       .map(String::toUpperCase)
       .map(Capability::valueOf)
       .collect(Collectors.toSet());
   ```
   - Throws IllegalArgumentException if invalid capability name
3. Convert enum set to bitmask:
   ```java
   membership.setCapabilities(Capability.toMask(caps));
   ```
4. Save membership
5. Return updated capability names

**Capability Enum to Bitmask Conversion**
```java
public static int toMask(Set<Capability> capabilities) {
    return capabilities.stream()
        .mapToInt(Capability::getMask)
        .reduce(0, (a, b) -> a | b);
}
```

Example:
- `[PLAYBACK_CONTROL]` → `0001` = 1
- `[PLAYBACK_CONTROL, REORDER_QUEUE]` → `0011` = 3
- `[REORDER_QUEUE, REMOVE_ITEMS]` → `0110` = 6
- `[]` (empty) → `0000` = 0

**Error Handling**
- Invalid capability names → IllegalArgumentException (becomes 400 Bad Request)
- Membership not found → ResourceNotFoundException (becomes 404 Not Found)
- Authorization failure → ForbiddenOperationException (becomes 403 Forbidden)

---

## 6. Security & JWT Service

### AuthenticatedMember Record

**Structure**
```java
public record AuthenticatedMember(
    UUID membershipId,
    UUID userId,
    UUID roomId,
    String roomCode,
    Role role,
    Set<String> capabilities
)
```

**Source**: JWT token embedded in request
**Extracted by**: JwtAuthenticationFilter
**Used by**: Service methods for authorization

### JwtService

**Key Methods**
- `generateToken(Membership)` - Create JWT from membership
- `parseToken(String)` - Extract AuthenticatedMember from JWT

**JWT Claims**
```json
{
  "membershipId": "...",
  "userId": "...",
  "roomId": "...",
  "roomCode": "...",
  "role": "HOST|GUEST",
  "capabilities": ["PLAYBACK_CONTROL", ...],
  "iat": 1700727000,
  "exp": 1700727000 + TTL
}
```

**Expiration**: Configured via `JOOKBOX_JWT_SECRET` and `application.yaml`

---

## Transaction Management

### @Transactional Scope

```java
@Service
@Transactional  // Class-level default
public class RoomService {
    
    @Transactional  // Method-level override
    public MembershipTokenResponse createRoom(RoomCreationRequest request) {
        // All DB operations atomic
    }
}
```

**Behavior**
- All repository operations within method are part of same transaction
- If any operation fails, entire transaction is rolled back
- If method completes successfully, transaction is committed

**Example: Room Creation Rollback**
```
Request: hostDisplayName = "John Doe"
1. ✓ User created
2. ✓ Room created
3. ✗ Membership creation fails (unexpected DB error)
→ ROLLBACK: All 3 entities deleted from DB
→ Exception thrown to controller
→ Client receives 500 error
```

---

## WebSocket Broadcasting Pattern

### Centralized Broadcast Methods

Each service that modifies data also broadcasts:

```java
// QueueService
private void broadcastQueue(String roomCode) {
    QueueResponse payload = getQueue(roomCode);
    messagingTemplate.convertAndSend("/topic/rooms/" + roomCode + "/queue", payload);
}

// PlaybackService
private void broadcast(String roomCode, PlaybackState state) {
    messagingTemplate.convertAndSend("/topic/rooms/" + roomCode + "/playback", toResponse(state));
}
```

### Topic Naming Convention

```
/topic/rooms/{roomCode}/queue       ← Queue updates
/topic/rooms/{roomCode}/playback    ← Playback state updates
```

### Broadcast Triggers

| Event | Topic | Service |
|-------|-------|---------|
| Item added | queue | QueueService.enqueue() |
| Item moved | queue | QueueService.move() |
| Item removed | queue | QueueService.removeItem() |
| Vote outcome applied | queue | VoteService.applyOutcome() |
| Playback started | playback | PlaybackService.play() |
| Playback paused | playback | PlaybackService.pause() |
| Seek performed | playback | PlaybackService.seek() |

---

## Error Handling Strategy

### Exception Hierarchy

```
RuntimeException
├── ForbiddenOperationException (403 Forbidden)
├── ResourceNotFoundException (404 Not Found)
├── BadRequestException (400 Bad Request)
└── (framework exceptions mapped to 4xx/5xx)
```

### Service Exception Throwing

```java
// Check authorization
if (!member.capabilities().contains("REORDER_QUEUE")) {
    throw new ForbiddenOperationException("Insufficient permissions");
}

// Check existence
var membership = membershipRepository.findById(membershipId)
    .orElseThrow(() -> new ResourceNotFoundException("Membership not found"));

// Check business logic
if (room.getStatus() != RoomStatus.ACTIVE) {
    throw new BadRequestException("Room is not active");
}
```

### ApiExceptionHandler

Controller-level handler converts exceptions to HTTP responses:
```json
{
  "message": "Error message",
  "timestamp": "2025-11-23T10:30:00Z",
  "status": 403
}
```

---

## Performance Considerations

### Database Indexing

Services rely on repository queries optimized by database indexes:
- `rooms.code` → Fast room lookup
- `memberships(room_id, user_id)` → Fast membership verification
- `queue_items(room_id, position)` → Fast queue ordering

### Redis Caching

Playback state stored in Redis (not database) for:
- ✅ Fast writes (playback updates frequent)
- ✅ Reduced database load
- ✅ Lower latency for clients

### N+1 Query Prevention

Services use careful query design:
```java
// Good: Single query with join
List<QueueItem> items = queueItemRepository.findByRoomOrderByPosition(room);

// Avoid: Separate query per item
for (QueueItem item : items) {
    item.getRoom().getName();  // Lazy loading → N+1
}
```

---

## Testing Strategy

### Unit Test Scope

Test business logic in isolation:
- Mock repositories
- Mock messaging template
- Focus on method logic, not database

### Integration Test Scope

Test end-to-end flows:
- Real database (Testcontainers)
- Real Redis (Testcontainers)
- Full transaction lifecycle

---

## Summary

The Jookbox service layer provides:

| Aspect | Implementation |
|--------|-----------------|
| **Room Lifecycle** | Creation, joining, code generation, member limits |
| **Queue Management** | Add, move, remove with real-time sync |
| **Playback Control** | Play, pause, seek with Redis persistence |
| **Voting** | Threshold-based outcomes with host bypass |
| **Permissions** | Capability-based access control |
| **Transactions** | Atomic operations with rollback support |
| **Real-Time** | WebSocket broadcasting for all changes |
| **Security** | JWT-based authentication + CBAC |
| **Error Handling** | Consistent exception mapping to HTTP |

Each service is focused, testable, and responsible for specific business domain while maintaining data consistency and providing real-time updates to clients.
