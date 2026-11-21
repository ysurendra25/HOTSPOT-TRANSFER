$ErrorActionPreference = 'Stop'
$source = Join-Path $PSScriptRoot 'upstream-v0.1.0\web-remote-desktop-0.1.0'
$dist = Join-Path $PSScriptRoot 'dist'
New-Item -ItemType Directory -Force -Path $dist | Out-Null
Push-Location $source
try {
    go test ./...
    if ($LASTEXITCODE -ne 0) { throw 'Go tests failed.' }
    go build -trimpath -ldflags '-s -w' -o (Join-Path $dist 'pc-remote.exe') .
    if ($LASTEXITCODE -ne 0) { throw 'Windows helper build failed.' }
} finally {
    Pop-Location
}
