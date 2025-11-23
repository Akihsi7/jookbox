# Jookbox Backend Runbook

Step-by-step to bring up dependencies, run the backend, and exercise the APIs locally.

## Prereqs
- Docker Desktop installed and running.
- Java 21+ (we downloaded JDK 21 in `C:\Users\KIIT\Downloads\jdk21\jdk-21.0.9+10`).
- PowerShell.

## 1) Start Postgres and Redis
From the project root:
```powershell
docker compose up -d
docker compose ps   # both services should be healthy on 5432 (Postgres) and 6379 (Redis)
```

## 2) Set environment variables for this session
```powershell
$env:JAVA_HOME="C:\Users\KIIT\Downloads\jdk21\jdk-21.0.9+10"
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
$env:SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:5432/jookbox"
$env:SPRING_DATASOURCE_USERNAME="postgres"
$env:SPRING_DATASOURCE_PASSWORD="postgres"
$env:SPRING_REDIS_HOST="localhost"
$env:SPRING_REDIS_PORT="6379"
$env:JOOKBOX_JWT_SECRET="a-very-long-random-string-change-me"
# Optional: set a port if 8080 is busy
# $env:PORT="8081"
```

## 3) Build (runs tests)
```powershell
./gradlew.bat clean build
```

## 4) Run the app
```powershell
./gradlew.bat bootRun
# or keep it in the background:
# Start-Process -FilePath './gradlew.bat' -ArgumentList 'bootRun' -RedirectStandardOutput bootrun.log -RedirectStandardError bootrun.err
```
If port 8080 is already taken (e.g., by an Oracle listener), either stop that service or set `PORT=8081` before `bootRun`.

## 5) Smoke-test with curl (PowerShell Invoke-RestMethod)
```powershell
# Create a room (host)
$host = Invoke-RestMethod -Method Post -Uri "http://localhost:8080/rooms" `
  -ContentType 'application/json' -Body '{"hostDisplayName":"Alice"}'
$room   = $host.roomCode
$hostTk = $host.token

# Join as guest
$guest = Invoke-RestMethod -Method Post -Uri "http://localhost:8080/rooms/$room/join" `
  -ContentType 'application/json' -Body '{"displayName":"Bob"}'
$guestTk = $guest.token

# Add to queue (host token)
$queueBody = @{ videoId='dQw4w9WgXcQ'; title='Never Gonna Give You Up'; durationSeconds=213;
                thumbUrl='https://img.youtube.com/vi/dQw4w9WgXcQ/hqdefault.jpg' } | ConvertTo-Json
$queued = Invoke-RestMethod -Method Post -Uri "http://localhost:8080/rooms/$room/queue" `
  -Headers @{Authorization="Bearer $hostTk"} -ContentType 'application/json' -Body $queueBody

# Get queue (guest token)
$queue = Invoke-RestMethod -Uri "http://localhost:8080/rooms/$room/queue" `
  -Headers @{Authorization="Bearer $guestTk"}

# Start playback (host token)
$playBody = @{ queueItemId=$queued.id; positionMs=0 } | ConvertTo-Json
$play = Invoke-RestMethod -Method Post -Uri "http://localhost:8080/rooms/$room/playback/play" `
  -Headers @{Authorization="Bearer $hostTk"} -ContentType 'application/json' -Body $playBody
```
Replace `8080` with the port you run on (e.g., `8081`) if overridden.

## 6) Verify DB contents (Postgres in Docker)
```powershell
docker exec -i jookbox-postgres psql -U postgres -d jookbox -c "select * from rooms;"
docker exec -i jookbox-postgres psql -U postgres -d jookbox -c "select id, room_id, role, capabilities from memberships;"
docker exec -i jookbox-postgres psql -U postgres -d jookbox -c "select id, room_id, video_id, title, position, status from queue_items;"
```

## 7) Stop services
```powershell
# Stop the app (Ctrl+C if foreground; or stop the process if backgrounded)
docker compose down   # stops and removes Postgres/Redis containers
```

## Troubleshooting
- **Port 8080 in use**: stop the conflicting service (e.g., Oracle TNS listener) or set `PORT=8081` env var before `bootRun`.
- **Gradle “Unsupported class file major version 69”**: ensure `JAVA_HOME` points to JDK 21, not JDK 25.
- **Validation classes missing**: ensure `spring-boot-starter-validation` is present (already in `build.gradle`).
- **JWT 403s**: include `Authorization: Bearer <token>` header from the room/join responses. Tokens are room-scoped. 
