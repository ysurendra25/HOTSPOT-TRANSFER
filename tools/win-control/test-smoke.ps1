param([Parameter(Mandatory=$true)][string]$PackageDirectory)
$ErrorActionPreference = 'Stop'
$exe = Join-Path $PackageDirectory 'PocketBridge-Windows-Control.exe'
if (-not (Test-Path $exe)) { $exe = Join-Path $PackageDirectory 'pc-remote.exe' }
if (-not (Test-Path $exe)) { throw "Missing helper: $exe" }

# Hide FFmpeg from this test process so the offline JPEG branch is exercised.
$start = [Diagnostics.ProcessStartInfo]::new()
$start.FileName = $exe
$start.WorkingDirectory = (Resolve-Path $PackageDirectory).Path
$start.UseShellExecute = $false
$start.EnvironmentVariables['PATH'] = 'C:\Windows\System32'
$process = [Diagnostics.Process]::Start($start)
try {
    $configPath = Join-Path $PackageDirectory 'config.json'
    $logPath = Join-Path $PackageDirectory 'pc-remote.log'
    $ready = $false
    for ($i = 0; $i -lt 30; $i++) {
        Start-Sleep -Milliseconds 300
        if (Test-Path $logPath) {
            $log = Get-Content $logPath -Raw
            if ($log -match 'listening on :7070') { $ready = $true; break }
        }
        if ($process.HasExited) { throw 'The helper exited during startup.' }
    }
    if (-not $ready) { throw 'The helper did not open port 7070.' }

    $token = (Get-Content $configPath -Raw | ConvertFrom-Json).token
    $base = 'http://127.0.0.1:7070'
    $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $page = Invoke-WebRequest -Uri "$base/?t=$token" -WebSession $session -TimeoutSec 10
    $settings = (Invoke-WebRequest -Uri "$base/settings" -WebSession $session -TimeoutSec 10).Content | ConvertFrom-Json
    if ($page.StatusCode -ne 200 -or -not $page.Content.Contains('screenj')) { throw 'The browser control page did not load its JPEG view.' }
    if ($settings.h264.available) { throw 'The test did not disable FFmpeg as intended.' }
    $inputCheck = Invoke-WebRequest -Uri "$base/input?t=$token" -Method Post -ContentType 'application/json' -Body '{"type":"noop"}' -TimeoutSec 10
    if ([int]$inputCheck.StatusCode -ne 204) { throw 'The authenticated input endpoint did not respond.' }
    $socket = [Net.WebSockets.ClientWebSocket]::new()
    $socket.ConnectAsync([Uri]"ws://127.0.0.1:7070/ws?t=$token", [Threading.CancellationToken]::None).GetAwaiter().GetResult()
    if ($socket.State -ne [Net.WebSockets.WebSocketState]::Open) { throw 'The authenticated input socket did not open.' }
    $socket.CloseAsync([Net.WebSockets.WebSocketCloseStatus]::NormalClosure, 'smoke test', [Threading.CancellationToken]::None).GetAwaiter().GetResult()
    $socket.Dispose()

    $request = [Net.HttpWebRequest]::Create("$base/jpeg?t=$token")
    $request.Timeout = 15000
    $request.ReadWriteTimeout = 15000
    $jpegResponse = $request.GetResponse()
    $stream = $jpegResponse.GetResponseStream()
    $buffer = New-Object byte[] 8192
    $response = [IO.MemoryStream]::new()
    $jpegLength = 0
    $jpegOffset = -1
    while ($response.Length -lt 5000000) {
        $read = $stream.Read($buffer, 0, $buffer.Length)
        if ($read -le 0) { break }
        $response.Write($buffer, 0, $read)
        $all = $response.ToArray()
        $text = [Text.Encoding]::ASCII.GetString($all)
        $match = [regex]::Match($text, 'Content-Type: image/jpeg\r\nContent-Length: (\d+)\r\n\r\n')
        if ($match.Success) {
            $jpegLength = [int]$match.Groups[1].Value
            $jpegOffset = $match.Index + $match.Length
            if ($all.Length -ge $jpegOffset + $jpegLength) { break }
        }
    }
    $stream.Dispose()
    $jpegResponse.Dispose()
    if ($jpegOffset -lt 0 -or $response.Length -lt $jpegOffset + $jpegLength) { throw 'No complete JPEG frame arrived.' }

    $imageBytes = New-Object byte[] $jpegLength
    [Array]::Copy($all, $jpegOffset, $imageBytes, 0, $jpegLength)
    if ($imageBytes[0] -ne 255 -or $imageBytes[1] -ne 216 -or $imageBytes[$jpegLength - 2] -ne 255 -or $imageBytes[$jpegLength - 1] -ne 217) {
        throw ('The JPEG frame has unexpected markers: start=' + [BitConverter]::ToString($imageBytes, 0, 4) + ' end=' + [BitConverter]::ToString($imageBytes, $jpegLength - 4, 4) + ' offset=' + $jpegOffset + ' length=' + $jpegLength + ' total=' + $all.Length)
    }
    $imagePath = Join-Path $env:TEMP 'pocketbridge-control-smoke.jpg'
    [IO.File]::WriteAllBytes($imagePath, $imageBytes)
    try {
        $image = [Drawing.Image]::FromFile($imagePath)
        try { $width = $image.Width; $height = $image.Height } finally { $image.Dispose() }
    } finally { Remove-Item -LiteralPath $imagePath -ErrorAction SilentlyContinue }

    Write-Output "PASS: page HTTP $($page.StatusCode), input POST and WebSocket, H.264 disabled, JPEG frame ${width}x${height} ($jpegLength bytes)."
} finally {
    if (-not $process.HasExited) { $process.Kill(); $process.WaitForExit() }
}
