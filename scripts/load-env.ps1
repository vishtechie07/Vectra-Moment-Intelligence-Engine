# Load KEY=VALUE lines from .env into process env (does not override existing vars).
param([string]$EnvFile)

if (-not $EnvFile) { return }
if (-not (Test-Path $EnvFile)) { return }

Get-Content $EnvFile | ForEach-Object {
    $line = $_.Trim()
    if ($line -eq '' -or $line.StartsWith('#')) { return }
    if ($line -match '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
        $name = $matches[1]
        $val = $matches[2].Trim().Trim('"').Trim("'")
        if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) {
            Set-Item -Path "env:$name" -Value $val
        }
    }
}
