# Jookbox REST API Documentation

## Overview
The Jookbox API provides REST endpoints for managing collaborative video rooms with real-time synchronization via WebSocket (STOMP). All endpoints (except room creation/joining) require JWT authentication.

**Base URL**: `http://localhost:8080`

**Authentication**: Bearer Token (JWT) in `Authorization` header
```
Authorization: Bearer <JWT_TOKEN>
```

---

## Authentication & Security

### JWT Token Structure
The JWT contains embedded membership information:
```json
{
  "membershipId": "550e8400-e29b-41d4-a716-446655440000",
  "userId": "550e8400-e29b-41d4-a716-446655440001",
  "roomId": "550e8400-e29b-41d4-a716-446655440002",
  "roomCode": "ABC123",
  "role": "HOST",
  "capabilities": ["PLAYBACK_CONTROL", "REORDER_QUEUE", "REMOVE_ITEMS", "SKIP_OVERRIDE"]
}
```

### Security Features
- **JWT Secret**: Configured via `JOOKBOX_JWT_SECRET` environment variable
- **Stateless Auth**: No session storage; token self-contained
- **Scoped Access**: Each token tied to specific membership and room
- **Capability-Based Access Control (CBAC)**: Fine-grained permissions via capabilities
- **Role-Based Access Control (RBAC)**: HOST vs GUEST roles

### Endpoints Requiring Authentication
- ✅ All `/rooms/{code}/queue/*` endpoints
- ✅ All `/rooms/{code}/playback/*` endpoints (except GET)
- ✅ All `/rooms/{code}/queue/{itemId}/*` endpoints
- ✅ All `/rooms/{code}/permissions/*` endpoints
- ❌ `POST /rooms` (create room)
- ❌ `POST /rooms/{code}/join` (join room)
- ❌ `GET /rooms/{code}/queue` (get queue)
- ❌ `GET /rooms/{code}/playback` (get playback state)

### Error Response Format
All error responses follow this format:
```json
{
  "message": "Error description",
  "timestamp": "2025-11-23T10:30:00Z",
  "status": 400
}
```

---

## API Endpoints

### Room Management

#### 1. Create Room
Creates a new room with the requesting user as the host.

```http
POST /rooms
Content-Type: application/json

{
  "hostDisplayName": "John Doe"
}
```

**Request Body**
| Field | Type | Required | Constraints | Description |
|-------|------|----------|-------------|-------------|
| `hostDisplayName` | String | ✅ | 1-120 chars, non-blank | Display name of the room host |

**Response** - `201 Created`
```json
{
  "roomCode": "ABC123",
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "role": "HOST",
  "capabilities": [
    "PLAYBACK_CONTROL",
    "REORDER_QUEUE",
    "REMOVE_ITEMS",
    "SKIP_OVERRIDE"
  ]
}
```

**Response Fields**
| Field | Type | Description |
|-------|------|-------------|
| `roomCode` | String | 6-character room code for joining |
| `token` | String | JWT token for authenticated requests |
| `role` | String | Always `HOST` for room creator |
| `capabilities` | Array[String] | Granted capabilities (all 4 for host) |

**Error Responses**
| Status | Error | Reason |
|--------|-------|--------|
| 400 | Bad Request | Invalid or blank `hostDisplayName` |
| 500 | Internal Server Error | Unexpected server error |

**Example Usage**
```bash
curl -X POST http://localhost:8080/rooms \
  -H "Content-Type: application/json" \
  -d '{"hostDisplayName": "John Doe"}'
```

---

#### 2. Join Room
Joins an existing room as a guest member.

```http
POST /rooms/{code}/join
Content-Type: application/json

{
  "displayName": "Jane Smith"
}
```

**Path Parameters**
| Parameter | Type | Description |
|-----------|------|-------------|
| `code` | String | 6-character room code |

**Request Body**
| Field | Type | Required | Constraints | Description |
|-------|------|----------|-------------|-------------|
| `displayName` | String | ✅ | 1-120 chars, non-blank | Guest's display name |

**Response** - `200 OK`
```json
{
  "roomCode": "ABC123",
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "role": "GUEST",
  "capabilities": []
}
```

**Response Fields** - Same as create room (role is GUEST, capabilities empty by default)

**Error Responses**
| Status | Error | Reason |
|--------|-------|--------|
| 400 | Bad Request | Invalid/blank display name; room full (>10 members); room not active |
| 404 | Not Found | Room code doesn't exist |

**Validation Rules**
- Room must exist and be `ACTIVE` status
- Room must have fewer than 10 members
- Display name must be non-blank (1-120 chars)

**Example Usage**
```bash
curl -X POST http://localhost:8080/rooms/ABC123/join \
  -H "Content-Type: application/json" \
  -d '{"displayName": "Jane Smith"}'
```

---

#### 3. Get Queue
Retrieves the current queue for a room (no auth required).

```http
GET /rooms/{code}/queue
```

**Path Parameters**
| Parameter | Type | Description |
|-----------|------|-------------|
| `code` | String | 6-character room code |

**Query Parameters** - None

**Response** - `200 OK`
```json
{
  "items": [
    {
      "id": "550e8400-e29b-41d4-a716-446655440001",
      "videoId": "dQw4w9WgXcQ",
      "title": "Rick Astley - Never Gonna Give You Up",
      "durationSeconds": 213,
      "thumbUrl": "https://img.youtube.com/vi/dQw4w9WgXcQ/maxresdefault.jpg",
      "position": 0,
      "status": "PLAYING",
      "enqueuedAt": "2025-11-23T10:00:00Z",
      "addedBy": "John Doe"
    },
    {
      "id": "550e8400-e29b-41d4-a716-446655440002",
      "videoId": "9bZkp7q19f0",
      "title": "PSY - GANGNAM STYLE",
      "durationSeconds": 253,
      "thumbUrl": "https://img.youtube.com/vi/9bZkp7q19f0/maxresdefault.jpg",
      "position": 1,
      "status": "QUEUED",
      "enqueuedAt": "2025-11-23T10:04:00Z",
      "addedBy": "Jane Smith"
    }
  ]
}
```

**Response Fields**
| Field | Type | Description |
|-------|------|-------------|
| `items` | Array[QueueItemView] | List of queue items (QUEUED or PLAYING only) |

**QueueItemView Object**
| Field | Type | Description |
|-------|------|-------------|
| `id` | UUID | Unique item ID |
| `videoId` | String | External video ID (e.g., YouTube) |
| `title` | String | Video title |
| `durationSeconds` | Integer | Duration in seconds |
| `thumbUrl` | String | Video thumbnail URL (nullable) |
| `position` | Integer | Position in queue (0-based) |
| `status` | String | `QUEUED` or `PLAYING` |
| `enqueuedAt` | ISO8601 | Timestamp when added |
| `addedBy` | String | Display name of user who added it |

**Error Responses**
| Status | Error | Reason |
|--------|-------|--------|
| 404 | Not Found | Room doesn't exist |

**Notes**
- Returns only items with status `QUEUED` or `PLAYING`
- Items are ordered by position
- Real-time updates available via WebSocket `/topic/rooms/{code}/queue`

**Example Usage**
```bash
curl http://localhost:8080/rooms/ABC123/queue
```

---

### Queue Management

#### 4. Add Item to Queue
Adds a video to the queue (requires authentication).

```http
POST /rooms/{code}/queue
Authorization: Bearer <JWT_TOKEN>
Content-Type: application/json

{
  "videoId": "dQw4w9WgXcQ",
  "title": "Rick Astley - Never Gonna Give You Up",
  "durationSeconds": 213,
  "thumbUrl": "https://img.youtube.com/vi/dQw4w9WgXcQ/maxresdefault.jpg"
}
```

**Path Parameters**
| Parameter | Type | Description |
|-----------|------|-------------|
| `code` | String | 6-character room code |

**Request Body**
| Field | Type | Required | Constraints | Description |
|-------|------|----------|-------------|-------------|
| `videoId` | String | ✅ | Non-blank | External video ID |
| `title` | String | ✅ | Non-blank, max 300 chars | Video title |
| `durationSeconds` | Integer | ✅ | >= 1 | Video duration |
| `thumbUrl` | String | ❌ | Max 500 chars | Thumbnail URL |

**Response** - `201 Created`
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440003",
  "videoId": "dQw4w9WgXcQ",
  "title": "Rick Astley - Never Gonna Give You Up",
  "durationSeconds": 213,
  "thumbUrl": "https://img.youtube.com/vi/dQw4w9WgXcQ/maxresdefault.jpg",
  "position": 2,
  "status": "QUEUED",
  "enqueuedAt": "2025-11-23T10:10:00Z",
  "addedBy": "John Doe"
}
```

**Response Fields** - Same as QueueItemView (see above)

**Triggers**
- WebSocket broadcast to `/topic/rooms/{code}/queue` with updated queue

**Error Responses**
| Status | Error | Reason |
|--------|-------|--------|
| 400 | Bad Request | Missing/invalid fields; room not active; membership not in room |
| 401 | Unauthorized | Missing/invalid JWT token |
| 404 | Not Found | Room or membership not found |
| 403 | Forbidden | Membership not associated with this room |

**Access Control**
- Requires valid JWT token
- User must be member of the room

**Example Usage**
```bash
curl -X POST http://localhost:8080/rooms/ABC123/queue \
  -H "Authorization: Bearer <JWT_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{
    "videoId": "dQw4w9WgXcQ",
    "title": "Rick Astley - Never Gonna Give You Up",
    "durationSeconds": 213,
    "thumbUrl": "https://img.youtube.com/vi/dQw4w9WgXcQ/maxresdefault.jpg"
  }'
```

---

#### 5. Move Queue Item
Reorders a queue item to a new position (requires `REORDER_QUEUE` capability).

```http
PUT /rooms/{code}/queue/{itemId}/move
Authorization: Bearer <JWT_TOKEN>
Content-Type: application/json

{
  "newPosition": 0
}
```

**Path Parameters**
| Parameter | Type | Description |
|-----------|------|-------------|
| `code` | String | 6-character room code |
| `itemId` | UUID | Queue item ID to move |

**Request Body**
| Field | Type | Required | Constraints | Description |
|-------|------|----------|-------------|-------------|
| `newPosition` | Integer | ✅ | >= 0 | New position in queue (0-based) |

**Response** - `200 OK`
```json
{
  "items": [
    {
      "id": "550e8400-e29b-41d4-a716-446655440003",
      "videoId": "dQw4w9WgXcQ",
      "title": "Rick Astley - Never Gonna Give You Up",
      "durationSeconds": 213,
      "thumbUrl": "https://img.youtube.com/vi/dQw4w9WgXcQ/maxresdefault.jpg",
      "position": 0,
      "status": "QUEUED",
      "enqueuedAt": "2025-11-23T10:10:00Z",
      "addedBy": "John Doe"
    },
    {
      "id": "550e8400-e29b-41d4-a716-446655440001",
      "videoId": "9bZkp7q19f0",
      "title": "PSY - GANGNAM STYLE",
      "durationSeconds": 253,
      "thumbUrl": "https://img.youtube.com/vi/9bZkp7q19f0/maxresdefault.jpg",
      "position": 1,
      "status": "QUEUED",
      "enqueuedAt": "2025-11-23T10:04:00Z",
      "addedBy": "Jane Smith"
    }
  ]
}
```

**Response Fields** - QueueResponse with reordered items

**Triggers**
- All items affected by reordering are updated
- WebSocket broadcast to `/topic/rooms/{code}/queue` with new queue

**Error Responses**
| Status | Error | Reason |
|--------|-------|--------|
| 400 | Bad Request | Invalid position; item not in room; room not active |
| 401 | Unauthorized | Missing/invalid JWT token |
| 403 | Forbidden | User lacks `REORDER_QUEUE` capability |
| 404 | Not Found | Item or room not found |

**Access Control**
- Requires JWT token with `REORDER_QUEUE` capability
- User must be member of room

**Behavior**
- If `newPosition` > current queue length, item moves to end
- All other items are reindexed automatically
- PLAYING item can be reordered

**Example Usage**
```bash
curl -X PUT http://localhost:8080/rooms/ABC123/queue/550e8400-e29b-41d4-a716-446655440003/move \
  -H "Authorization: Bearer <JWT_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{"newPosition": 0}'
```

---

#### 6. Remove Queue Item
Removes an item from the queue (requires `REMOVE_ITEMS` capability or HOST role).

```http
DELETE /rooms/{code}/queue/{itemId}
Authorization: Bearer <JWT_TOKEN>
```

**Path Parameters**
| Parameter | Type | Description |
|-----------|------|-------------|
| `code` | String | 6-character room code |
| `itemId` | UUID | Queue item ID to remove |

**Response** - `204 No Content`

**Triggers**
- Item status set to `REMOVED`
- Remaining items reindexed
- WebSocket broadcast to `/topic/rooms/{code}/queue` with updated queue

**Error Responses**
| Status | Error | Reason |
|--------|-------|--------|
| 400 | Bad Request | Item not in room; room not active |
| 401 | Unauthorized | Missing/invalid JWT token |
| 403 | Forbidden | User lacks `REMOVE_ITEMS` capability and is not HOST |
| 404 | Not Found | Item or room not found |

**Access Control**
- Requires JWT token with `REMOVE_ITEMS` capability OR HOST role
- User must be member of room

**Example Usage**
```bash
curl -X DELETE http://localhost:8080/rooms/ABC123/queue/550e8400-e29b-41d4-a716-446655440003 \
  -H "Authorization: Bearer <JWT_TOKEN>"
```

---

### Playback Control

#### 7. Get Playback State
Retrieves current playback state for a room (no auth required).

```http
GET /rooms/{code}/playback
```

**Path Parameters**
| Parameter | Type | Description |
|-----------|------|-------------|
| `code` | String | 6-character room code |

**Response** - `200 OK`
```json
{
  "nowPlayingQueueItemId": "550e8400-e29b-41d4-a716-446655440001",
  "positionMs": 45000,
  "playing": true,
  "lastUpdateTs": 1700727000000
}
```

**Response Fields**
| Field | Type | Description |
|-------|------|-------------|
| `nowPlayingQueueItemId` | UUID | ID of currently playing item |
| `positionMs` | Integer | Current playback position (milliseconds) |
| `playing` | Boolean | Whether playback is active |
| `lastUpdateTs` | Long | Timestamp of last update (Unix millis) |

**Error Responses**
| Status | Error | Reason |
|--------|-------|--------|
| 200 | null | No playback state (room just started) |
| 404 | Not Found | Room doesn't exist |

**Notes**
- Returns `null` if no playback has been started yet
- State stored in Redis; source of truth for real-time synchronization
- Real-time updates available via WebSocket `/topic/rooms/{code}/playback`

**Example Usage**
```bash
curl http://localhost:8080/rooms/ABC123/playback
```

---

#### 8. Play Video
Starts playback of a queue item (requires `PLAYBACK_CONTROL` capability).

```http
POST /rooms/{code}/playback/play
Authorization: Bearer <JWT_TOKEN>
Content-Type: application/json

{
  "queueItemId": "550e8400-e29b-41d4-a716-446655440001",
  "positionMs": 0
}
```

**Path Parameters**
| Parameter | Type | Description |
|-----------|------|-------------|
| `code` | String | 6-character room code |

**Request Body**
| Field | Type | Required | Constraints | Description |
|-------|------|----------|-------------|-------------|
| `queueItemId` | UUID | ✅ | Valid UUID | ID of item to play |
| `positionMs` | Integer | ✅ | >= 0 | Starting position (milliseconds) |

**Response** - `202 Accepted`
```json
{
  "nowPlayingQueueItemId": "550e8400-e29b-41d4-a716-446655440001",
  "positionMs": 0,
  "playing": true,
  "lastUpdateTs": 1700727000000
}
```

**Response Fields** - Same as Get Playback State

**Triggers**
- Playback state stored in Redis
- WebSocket broadcast to `/topic/rooms/{code}/playback` with new state

**Error Responses**
| Status | Error | Reason |
|--------|-------|--------|
| 400 | Bad Request | Invalid position; item not in room; room not active |
| 401 | Unauthorized | Missing/invalid JWT token |
| 403 | Forbidden | User lacks `PLAYBACK_CONTROL` capability; membership not in room |
| 404 | Not Found | Item or room not found |

**Access Control**
- Requires JWT token with `PLAYBACK_CONTROL` capability
- User must be member of room
- Token must match room code

**Example Usage**
```bash
curl -X POST http://localhost:8080/rooms/ABC123/playback/play \
  -H "Authorization: Bearer <JWT_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{
    "queueItemId": "550e8400-e29b-41d4-a716-446655440001",
    "positionMs": 0
  }'
```

---

#### 9. Pause Playback
Pauses playback (requires `PLAYBACK_CONTROL` capability).

```http
POST /rooms/{code}/playback/pause
Authorization: Bearer <JWT_TOKEN>
```

**Path Parameters**
| Parameter | Type | Description |
|-----------|------|-------------|
| `code` | String | 6-character room code |

**Response** - `202 Accepted`
```json
{
  "nowPlayingQueueItemId": "550e8400-e29b-41d4-a716-446655440001",
  "positionMs": 45000,
  "playing": false,
  "lastUpdateTs": 1700727005000
}
```

**Triggers**
- Playback state updated in Redis (playing = false)
- WebSocket broadcast to `/topic/rooms/{code}/playback`

**Error Responses**
| Status | Error | Reason |
|--------|-------|--------|
| 401 | Unauthorized | Missing/invalid JWT token |
| 403 | Forbidden | User lacks `PLAYBACK_CONTROL` capability |
| 404 | Not Found | Playback state not found |

**Access Control**
- Requires JWT token with `PLAYBACK_CONTROL` capability

**Example Usage**
```bash
curl -X POST http://localhost:8080/rooms/ABC123/playback/pause \
  -H "Authorization: Bearer <JWT_TOKEN>"
```

---

#### 10. Seek in Playback
Seeks to a specific position (requires `PLAYBACK_CONTROL` capability).

```http
POST /rooms/{code}/playback/seek
Authorization: Bearer <JWT_TOKEN>
Content-Type: application/json

{
  "positionMs": 90000
}
```

**Path Parameters**
| Parameter | Type | Description |
|-----------|------|-------------|
| `code` | String | 6-character room code |

**Request Body**
| Field | Type | Required | Constraints | Description |
|-------|------|----------|-------------|-------------|
| `positionMs` | Integer | ✅ | >= 0 | New position (milliseconds) |

**Response** - `202 Accepted`
```json
{
  "nowPlayingQueueItemId": "550e8400-e29b-41d4-a716-446655440001",
  "positionMs": 90000,
  "playing": true,
  "lastUpdateTs": 1700727010000
}
```

**Triggers**
- Playback state updated in Redis
- WebSocket broadcast to `/topic/rooms/{code}/playback`

**Error Responses**
| Status | Error | Reason |
|--------|-------|--------|
| 401 | Unauthorized | Missing/invalid JWT token |
| 403 | Forbidden | User lacks `PLAYBACK_CONTROL` capability |
| 404 | Not Found | Playback state not found |

**Access Control**
- Requires JWT token with `PLAYBACK_CONTROL` capability

**Notes**
- No validation on max position (client responsible for bounds checking)
- Works whether playback is playing or paused

**Example Usage**
```bash
curl -X POST http://localhost:8080/rooms/ABC123/playback/seek \
  -H "Authorization: Bearer <JWT_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{"positionMs": 90000}'
```

---

### Voting

#### 11. Vote Skip
Votes to skip the current item (requires authentication).

```http
POST /rooms/{code}/queue/{itemId}/vote-skip
Authorization: Bearer <JWT_TOKEN>
```

**Path Parameters**
| Parameter | Type | Description |
|-----------|------|-------------|
| `code` | String | 6-character room code |
| `itemId` | UUID | Queue item ID to skip |

**Response** - `202 Accepted`
```json
{
  "applied": false
}
```

**Response Fields**
| Field | Type | Description |
|-------|------|-------------|
| `applied` | Boolean | Whether vote outcome was applied |

**Vote Logic**
- **Non-host**: Vote recorded if not already voted
- **Host**: Vote automatically applied (bypasses threshold)
- **Threshold**: `MAX(1, CEIL(total_members / 2))`
- **Applied**: When votes >= threshold, item marked as PLAYED, queue reindexed

**Triggers**
- If `applied=true`: WebSocket broadcast to `/topic/rooms/{code}/queue` with updated queue
- If `applied=false`: Vote recorded but no broadcast yet

**Error Responses**
| Status | Error | Reason |
|--------|-------|--------|
| 400 | Bad Request | Item not in room; room not active; already voted |
| 401 | Unauthorized | Missing/invalid JWT token |
| 403 | Forbidden | Room not active or membership not found |
| 404 | Not Found | Item or room not found |

**Access Control**
- Requires JWT token
- User must be member of room
- Host votes immediately apply outcome

**Example Usage**
```bash
curl -X POST http://localhost:8080/rooms/ABC123/queue/550e8400-e29b-41d4-a716-446655440001/vote-skip \
  -H "Authorization: Bearer <JWT_TOKEN>"
```

---

#### 12. Vote Remove
Votes to remove an item from queue (requires authentication).

```http
POST /rooms/{code}/queue/{itemId}/vote-remove
Authorization: Bearer <JWT_TOKEN>
```

**Path Parameters** - Same as Vote Skip

**Response** - `202 Accepted` with same format as Vote Skip

**Vote Logic** - Same as Vote Skip except:
- When votes >= threshold, item marked as REMOVED instead of PLAYED

**Error Responses** - Same as Vote Skip

**Example Usage**
```bash
curl -X POST http://localhost:8080/rooms/ABC123/queue/550e8400-e29b-41d4-a716-446655440001/vote-remove \
  -H "Authorization: Bearer <JWT_TOKEN>"
```

---

### Permissions

#### 13. Update Member Capabilities
Updates granted capabilities for a member (HOST only).

```http
POST /rooms/{code}/permissions/{membershipId}
Authorization: Bearer <JWT_TOKEN>
Content-Type: application/json

{
  "capabilities": ["PLAYBACK_CONTROL", "REORDER_QUEUE"]
}
```

**Path Parameters**
| Parameter | Type | Description |
|-----------|------|-------------|
| `code` | String | 6-character room code |
| `membershipId` | UUID | Membership ID to update |

**Request Body**
| Field | Type | Required | Constraints | Description |
|-------|------|----------|-------------|-------------|
| `capabilities` | Array[String] | ✅ | Valid capability names | New capabilities |

**Valid Capabilities**
- `PLAYBACK_CONTROL` - Play/pause/seek
- `REORDER_QUEUE` - Move items in queue
- `REMOVE_ITEMS` - Remove items from queue
- `SKIP_OVERRIDE` - Force skip (host-only, rarely used)

**Response** - `202 Accepted`
```json
{
  "capabilities": ["PLAYBACK_CONTROL", "REORDER_QUEUE"]
}
```

**Response Fields**
| Field | Type | Description |
|-------|------|-------------|
| `capabilities` | Array[String] | Updated capabilities |

**Triggers**
- Membership capabilities bitmask updated in database
- Next JWT refresh reflects new capabilities

**Error Responses**
| Status | Error | Reason |
|--------|-------|--------|
| 400 | Bad Request | Invalid capability names |
| 401 | Unauthorized | Missing/invalid JWT token |
| 403 | Forbidden | User is not HOST or not in this room |
| 404 | Not Found | Membership or room not found |

**Access Control**
- Only HOST can update capabilities
- User must be HOST of the room
- Token must match room code

**Notes**
- Capabilities are case-insensitive in request
- Empty array removes all capabilities
- HOST capabilities are read-only (always full)

**Example Usage**
```bash
curl -X POST http://localhost:8080/rooms/ABC123/permissions/550e8400-e29b-41d4-a716-446655440000 \
  -H "Authorization: Bearer <JWT_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{
    "capabilities": ["PLAYBACK_CONTROL", "REORDER_QUEUE"]
  }'
```

---

## WebSocket Real-Time Updates

### Overview
Jookbox uses STOMP over WebSocket for real-time synchronization. Connect to `/ws` and subscribe to topics to receive live updates.

### Connection
```javascript
// Example using SockJS + Stomp.js
const socket = new SockJS('http://localhost:8080/ws');
const stompClient = Stomp.over(socket);

stompClient.connect({}, frame => {
  console.log('Connected: ' + frame.command);
});
```

### Subscription Topics

#### Queue Updates
Subscribe to queue changes for a specific room:
```javascript
stompClient.subscribe('/topic/rooms/ABC123/queue', message => {
  const queueResponse = JSON.parse(message.body);
  console.log('Queue updated:', queueResponse.items);
});
```

**Broadcast Triggers**
- Item added to queue
- Item moved in queue
- Item removed from queue
- Vote outcome applied (skip/remove)

**Payload Format**
```json
{
  "items": [
    {
      "id": "550e8400-e29b-41d4-a716-446655440001",
      "videoId": "dQw4w9WgXcQ",
      "title": "Rick Astley - Never Gonna Give You Up",
      "durationSeconds": 213,
      "thumbUrl": "https://img.youtube.com/vi/dQw4w9WgXcQ/maxresdefault.jpg",
      "position": 0,
      "status": "PLAYING",
      "enqueuedAt": "2025-11-23T10:00:00Z",
      "addedBy": "John Doe"
    }
  ]
}
```

#### Playback State Updates
Subscribe to playback changes:
```javascript
stompClient.subscribe('/topic/rooms/ABC123/playback', message => {
  const playbackState = JSON.parse(message.body);
  console.log('Playback updated:', playbackState);
});
```

**Broadcast Triggers**
- Play started
- Playback paused
- Seek performed

**Payload Format**
```json
{
  "nowPlayingQueueItemId": "550e8400-e29b-41d4-a716-446655440001",
  "positionMs": 45000,
  "playing": true,
  "lastUpdateTs": 1700727000000
}
```

---

## Status Codes & Error Handling

### HTTP Status Codes
| Code | Meaning | Typical Cause |
|------|---------|---------------|
| 200 | OK | Successful GET request |
| 201 | Created | Successful resource creation (POST) |
| 202 | Accepted | Asynchronous operation (playback, votes) |
| 204 | No Content | Successful DELETE |
| 400 | Bad Request | Invalid input, business logic violation |
| 401 | Unauthorized | Missing/invalid JWT token |
| 403 | Forbidden | Insufficient permissions/capabilities |
| 404 | Not Found | Resource doesn't exist |
| 500 | Internal Server Error | Unexpected server error |

### Common Error Scenarios

**Missing Authentication**
```json
{
  "message": "Unauthorized",
  "status": 401
}
```

**Insufficient Permissions**
```json
{
  "message": "You do not have permission to reorder the queue",
  "status": 403
}
```

**Resource Not Found**
```json
{
  "message": "Room not found",
  "status": 404
}
```

**Business Logic Violation**
```json
{
  "message": "Room is full",
  "status": 400
}
```

---

## Rate Limiting & Throttling

Currently, no rate limiting is implemented. Future versions may include:
- Per-room request throttling
- Vote cooldown periods
- Playback update frequency limiting

---

## API Response Times

**Typical latencies** (on modern hardware):
- Room create/join: 50-100ms
- Queue add: 30-50ms
- Playback control: 10-30ms
- Vote: 20-50ms (depends on threshold calculation)
- WebSocket broadcast: < 100ms

---

## Integration Examples

### Postman Collection
[See POSTMAN_COLLECTION.json in repository]

### Client Libraries
- JavaScript/TypeScript: axios + @stomp/stompjs
- Java: RestTemplate + Spring WebSocket
- Python: requests + websocket-client

---

## Deprecated & Planned Features

### Currently Not Implemented
- Room password protection
- User authentication/login
- Persistent user accounts
- Video validation (YouTube API integration)
- Room history/replay
- Recommendation engine

### Planned for Future Versions
- v2.0: OAuth2 authentication
- v2.1: Room persistence and analytics
- v2.2: Advanced voting mechanisms
- v2.3: Playlist support

---

## Support & Troubleshooting

### Common Issues

**"Room not found"**
- Check room code spelling (case-sensitive)
- Verify room hasn't been deleted/ended

**"Unauthorized"**
- Ensure JWT token is in `Authorization: Bearer <token>` format
- Check token hasn't expired

**"Permission denied"**
- Verify your membership has required capability
- For management endpoints, ensure you're HOST

**WebSocket connection fails**
- Check `/ws` endpoint is accessible
- Verify firewall allows WebSocket (WSS for HTTPS)

---

## Summary

The Jookbox API provides a comprehensive REST + WebSocket interface for:
- ✅ Room lifecycle management
- ✅ Queue management with real-time sync
- ✅ Synchronized playback control
- ✅ Democratic voting mechanisms
- ✅ Fine-grained permission management
- ✅ Real-time updates via WebSocket/STOMP

All endpoints are well-secured with JWT and capability-based access control.
