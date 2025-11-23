param(
    [string]$Task = "bootRun",
    [string]$EnvFile = ".env"
)

# Load environment variables from .env if present (ignore comments/blank lines)
if (Test-Path $EnvFile) {
    Get-Content $EnvFile | Where-Object { $_ -and $_ -notmatch '^\s*#' } | ForEach-Object {
        $pair = $_ -split '=', 2
        if ($pair.Length -eq 2) {
            $name = $pair[0].Trim()
            $value = $pair[1].Trim()
            if ($name) { [Environment]::SetEnvironmentVariable($name, $value, 'Process') }
        }
    }
}

Write-Host "Running ./gradlew.bat $Task with env from $EnvFile (if present)..." -ForegroundColor Cyan
& "./gradlew.bat" $Task
