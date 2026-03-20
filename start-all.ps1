# VectraMoment - Start all services (Docker, Backend, Frontend)
$ErrorActionPreference = "Stop"
$ProjectRoot = $PSScriptRoot

Write-Host "=== 1. Docker (Kafka, Zookeeper, OpenSearch) ===" -ForegroundColor Cyan
Set-Location $ProjectRoot
docker-compose down 2>$null
docker-compose up -d
Write-Host "Waiting 15s for Kafka/OpenSearch to be ready..." -ForegroundColor Yellow
Start-Sleep -Seconds 15

Write-Host "`n=== 2. Backend (Spring Boot :8081) ===" -ForegroundColor Cyan
Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd '$ProjectRoot\backend'; `$env:SPRING_PROFILES_ACTIVE='local'; mvn spring-boot:run"
$deadline = (Get-Date).AddSeconds(90)
do {
    Start-Sleep -Seconds 4
    try {
        $r = Invoke-WebRequest -Uri "http://localhost:8081/api/health" -UseBasicParsing -TimeoutSec 3
        if ($r.StatusCode -eq 200) { Write-Host "Backend UP on http://localhost:8081" -ForegroundColor Green; break }
    } catch {
        try { $r2 = Invoke-WebRequest -Uri "http://localhost:8081/api/search?q=x" -UseBasicParsing -TimeoutSec 2; if ($r2.StatusCode -eq 401) { Write-Host "Backend UP on http://localhost:8081" -ForegroundColor Green; break } } catch {}
    }
    if ((Get-Date) -gt $deadline) { Write-Host "Backend did not start in time. Check the backend PowerShell window." -ForegroundColor Red; exit 1 }
} while ($true)

Write-Host "`n=== 3. Frontend (Vite :5173) ===" -ForegroundColor Cyan
Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd '$ProjectRoot\frontend'; npm run dev"
Start-Sleep -Seconds 6
Write-Host "Frontend window opened. Proxy -> http://localhost:8081" -ForegroundColor Green

Write-Host "`n=== Ready ===" -ForegroundColor Green
Write-Host "  App:     http://localhost:5173" -ForegroundColor White
Write-Host "  Backend: http://localhost:8081" -ForegroundColor White
Write-Host "  (Close the Backend and Frontend PowerShell windows to stop them.)" -ForegroundColor DarkGray
Write-Host ""
