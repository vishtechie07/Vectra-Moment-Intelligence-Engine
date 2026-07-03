# Record a browser demo video of VectraMoment (Playwright).
# Prereqs: docker compose up (app on http://localhost:5173), OPENAI_KEY, short .mp4 in test_videos/
# Output: demo-recordings/*.mp4

$ErrorActionPreference = "Stop"
$ProjectRoot = $PSScriptRoot
$ScriptDir = Join-Path $ProjectRoot "scripts\demo-record"
. (Join-Path $ProjectRoot "scripts\load-env.ps1") -EnvFile (Join-Path $ProjectRoot ".env")
$AppUrl = if ($env:APP_URL) { $env:APP_URL } else { "http://localhost:5173" }

Write-Host "=== VectraMoment demo recorder ===" -ForegroundColor Cyan

try {
    $null = Invoke-WebRequest -Uri "$AppUrl/" -UseBasicParsing -TimeoutSec 5
} catch {
    Write-Host "App not reachable at $AppUrl. Run: docker compose up -d --build" -ForegroundColor Red
    exit 1
}

$OpenAIKey = $env:OPENAI_KEY
if ([string]::IsNullOrWhiteSpace($OpenAIKey)) {
    $secureKey = Read-Host -AsSecureString "OpenAI API key (for demo run only; not saved)"
    $BSTR = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureKey)
    try { $OpenAIKey = [System.Runtime.InteropServices.Marshal]::PtrToStringAuto($BSTR) }
    finally { [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($BSTR) }
}
if ([string]::IsNullOrWhiteSpace($OpenAIKey)) { exit 1 }

$env:OPENAI_KEY = $OpenAIKey
$env:APP_URL = $AppUrl

# Apply demo settings from .env (load-env skips vars already set in the shell)
$EnvPath = Join-Path $ProjectRoot ".env"
foreach ($line in Get-Content $EnvPath) {
    if ($line -match '^\s*DEMO_SEARCH_QUERIES=(.*)$') {
        $env:DEMO_SEARCH_QUERIES = $matches[1].Trim().Trim('"').Trim("'")
    }
}
if (-not ($env:DEMO_SEARCH_QUERIES)) {
    Remove-Item env:DEMO_SEARCH_QUERIES -ErrorAction SilentlyContinue
}

Set-Location $ScriptDir
if (-not (Test-Path "node_modules\playwright")) {
    Write-Host "Installing Playwright (first run)..." -ForegroundColor Yellow
    npm install --no-fund --no-audit
    npx playwright install chromium
} elseif (-not (Test-Path "node_modules\ffmpeg-static")) {
    Write-Host "Installing ffmpeg-static..." -ForegroundColor Yellow
    npm install --no-fund --no-audit
}
npm run record

Write-Host "`nDone. See demo-recordings/*.mp4" -ForegroundColor Green
