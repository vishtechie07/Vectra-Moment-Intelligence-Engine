# VectraMoment AI test: uploads a video from test_videos and runs search.
# Prompts for OpenAI API key (input is hidden). Requires backend on :8081 (local profile) and Docker (Kafka, OpenSearch).

$ErrorActionPreference = "Stop"
$ProjectRoot = $PSScriptRoot
$TestVideosDir = Join-Path $ProjectRoot "test_videos"
$BackendBase = "http://localhost:8081/api"

Write-Host "=== VectraMoment AI test ===" -ForegroundColor Cyan
Write-Host ""

# 1. OpenAI key: use env OPENAI_KEY if set, else prompt (hidden)
$OpenAIKey = $env:OPENAI_KEY
if ([string]::IsNullOrWhiteSpace($OpenAIKey)) {
    $secureKey = Read-Host -AsSecureString "Enter your OpenAI API key (key will not be echoed)"
    $BSTR = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureKey)
    try {
        $OpenAIKey = [System.Runtime.InteropServices.Marshal]::PtrToStringAuto($BSTR)
    } finally {
        [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($BSTR)
    }
}
if ([string]::IsNullOrWhiteSpace($OpenAIKey)) {
    Write-Host "No key provided. Set OPENAI_KEY or enter when prompted. Exiting." -ForegroundColor Red
    exit 1
}

# 2. Find a video in test_videos
$video = Get-ChildItem -Path $TestVideosDir -Filter "*.mp4" -File -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $video) {
    Write-Host "No .mp4 file found in test_videos. Add a video and run again." -ForegroundColor Red
    exit 1
}
Write-Host "Using video: $($video.Name)" -ForegroundColor Green

# 3. Check backend
try {
    $null = Invoke-RestMethod -Uri "$BackendBase/health" -Method Get -TimeoutSec 5
} catch {
    Write-Host "Backend not reachable at $BackendBase. Start it (local profile on :8081) and Docker (Kafka, OpenSearch), then run again." -ForegroundColor Red
    exit 1
}
Write-Host "Backend OK" -ForegroundColor Green

# 4. Upload (curl for multipart; -Form not in all PowerShell)
$headers = @{ "X-OpenAI-Key" = $OpenAIKey }
Write-Host "Uploading..." -ForegroundColor Yellow
$uploadJson = $null
try {
    $uploadJson = curl.exe -s -X POST -H "X-OpenAI-Key: $OpenAIKey" -F "file=@$($video.FullName)" "$BackendBase/videos/upload" 2>&1
} catch {
    Write-Host "Upload failed: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
if ($LASTEXITCODE -ne 0) { Write-Host "Upload failed (curl exit $LASTEXITCODE): $uploadJson" -ForegroundColor Red; exit 1 }
$uploadResp = $uploadJson | ConvertFrom-Json
$videoId = $uploadResp.videoId
if (-not $videoId) { Write-Host "Upload response missing videoId: $uploadJson" -ForegroundColor Red; exit 1 }
Write-Host "Uploaded. videoId: $videoId" -ForegroundColor Green

# 5. Playback URL
$playbackResp = Invoke-RestMethod -Uri "$BackendBase/videos/$videoId/playback-url" -Method Get
$playbackUrl = $playbackResp.url
Write-Host "Playback URL: $playbackUrl" -ForegroundColor Gray

# 6. Wait for processing (frame extract + vision/embed)
$waitSec = 90
Write-Host "Waiting ${waitSec}s for frame extraction and AI indexing..." -ForegroundColor Yellow
Start-Sleep -Seconds $waitSec

# 7. Search
$query = "person or scene"
Write-Host "Searching: `"$query`" (videoId=$videoId)..." -ForegroundColor Yellow
try {
    $searchUri = "$BackendBase/search?q=" + [uri]::EscapeDataString($query) + "&videoId=" + [uri]::EscapeDataString($videoId)
$searchResp = Invoke-RestMethod -Uri $searchUri -Method Get -Headers $headers -TimeoutSec 30
} catch {
    Write-Host "Search failed: $($_.Exception.Message)" -ForegroundColor Red
    if ($_.ErrorDetails.Message) { Write-Host $_.ErrorDetails.Message }
    exit 1
}

$hits = $searchResp.hits
if ($hits -and $hits.Count -gt 0) {
    Write-Host "Search results ($($hits.Count) hits):" -ForegroundColor Green
    foreach ($h in $hits) {
        Write-Host "  $($h.timestampSeconds)s - $($h.snippet)" -ForegroundColor White
    }
} else {
    Write-Host "No search results yet. Try again in a minute or run search from the UI." -ForegroundColor Yellow
}

Write-Host ""
Write-Host "Done. Open http://localhost:5173 and load video $videoId to use Time Machine search." -ForegroundColor Cyan
