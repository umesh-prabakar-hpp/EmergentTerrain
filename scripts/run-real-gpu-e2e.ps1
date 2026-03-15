param(
  [string]$JavaHome = "C:\dev\lang\java\amazon-corretto-21.0.6.7.1-windows-x64-jdk\jdk21.0.6_7",
  [string]$MavenCmd = "",
  [string]$WslDistro = "Ubuntu",
  [string]$WslGpuRepo = "/home/umesh/workspace/EmergentTerrainGPUEngine",
  [int]$AppPort = 8080,
  [int]$GpuPort = 50051,
  [int]$StartupTimeoutSeconds = 180,
  [int]$BotPollSeconds = 20,
  [int]$MaintenanceIntervalMs = 500,
  [switch]$ReuseExistingGpuEngine,
  [switch]$ReuseExistingApp
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($MavenCmd)) {
  $MavenCmd = $env:MAVEN_CMD
}
if ([string]::IsNullOrWhiteSpace($MavenCmd)) {
  $MavenCmd = "C:\dev\build\maven\apache-maven-3.8.6\bin\mvn.cmd"
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$outputDir = Join-Path $repoRoot "sample-output\gpu-e2e-$timestamp"
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
$gpuLog = Join-Path $outputDir "gpu-engine.log"
$gpuErrLog = Join-Path $outputDir "gpu-engine.err.log"
$appLog = Join-Path $outputDir "java-app.log"
$appErrLog = Join-Path $outputDir "java-app.err.log"

$gpuProcess = $null
$appProcess = $null
$startedGpu = $false
$startedApp = $false

function Test-TcpPort {
  param(
    [string]$Address,
    [int]$Port
  )

  $client = New-Object System.Net.Sockets.TcpClient
  try {
    $async = $client.BeginConnect($Address, $Port, $null, $null)
    if (-not $async.AsyncWaitHandle.WaitOne(500)) {
      return $false
    }
    $client.EndConnect($async) | Out-Null
    return $true
  } catch {
    return $false
  } finally {
    $client.Dispose()
  }
}

function Wait-TcpPort {
  param(
    [string]$Address,
    [int]$Port,
    [int]$TimeoutSeconds,
    [string]$Label
  )

  $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
  while ((Get-Date) -lt $deadline) {
    if (Test-TcpPort -Address $Address -Port $Port) {
      return
    }
    Start-Sleep -Milliseconds 500
  }
  throw "$Label did not open $Address`:$Port within $TimeoutSeconds seconds."
}

function Wait-HttpToken {
  param(
    [string]$Url,
    [int]$TimeoutSeconds
  )

  $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
  while ((Get-Date) -lt $deadline) {
    try {
      $response = Invoke-WebRequest -Uri $Url -UseBasicParsing
      $match = [regex]::Match($response.Content, 'window\.APP_TOKEN = "([^"]+)"')
      if ($match.Success) {
        return $match.Groups[1].Value
      }
    } catch {
    }
    Start-Sleep -Milliseconds 500
  }
  throw "Could not obtain a token from $Url within $TimeoutSeconds seconds."
}

function Count-WorldStats {
  param($StateResponse)

  $tileRows = @($StateResponse.tiles)
  $highwayRows = @($StateResponse.highways)
  $tileCount = 0
  $nonZeroTileCount = 0
  $highwayCount = 0

  foreach ($row in $tileRows) {
    foreach ($tile in @($row)) {
      $tileCount++
      if ([int]$tile -ne 0) {
        $nonZeroTileCount++
      }
    }
  }
  foreach ($row in $highwayRows) {
    foreach ($cell in @($row)) {
      if ([bool]$cell) {
        $highwayCount++
      }
    }
  }

  [pscustomobject]@{
    Width = if ($tileRows.Count -gt 0) { @($tileRows[0]).Count } else { 0 }
    Height = $tileRows.Count
    TileCount = $tileCount
    NonZeroTileCount = $nonZeroTileCount
    HighwayCount = $highwayCount
  }
}

function Get-BotPositions {
  param($Bots)

  $positions = @{}
  foreach ($bot in @($Bots)) {
    $positions[$bot.id] = "$($bot.x),$($bot.y)"
  }
  return $positions
}

function Test-AnyBotMoved {
  param(
    [hashtable]$Initial,
    [hashtable]$Current
  )

  foreach ($id in $Initial.Keys) {
    if ($Current.ContainsKey($id) -and $Current[$id] -ne $Initial[$id]) {
      return $true
    }
  }
  return $false
}

function Assert-NoGpuFallbackWarnings {
  param([string[]]$LogPaths)

  $existing = @($LogPaths | Where-Object { Test-Path $_ })
  if ($existing.Count -eq 0) {
    return
  }
  $warnings = Select-String -Path $existing -Pattern "gpu bot simulation failed", "gpu world generation failed"
  if ($warnings) {
    throw "GPU fallback warnings were found in app logs under $outputDir"
  }
}

function Get-ChildProcessIds {
  param([int]$ParentId)

  $children = @(Get-CimInstance Win32_Process -Filter "ParentProcessId = $ParentId" -ErrorAction SilentlyContinue)
  $ids = New-Object System.Collections.Generic.List[int]
  foreach ($child in $children) {
    $ids.Add([int]$child.ProcessId)
    foreach ($descendant in Get-ChildProcessIds -ParentId ([int]$child.ProcessId)) {
      $ids.Add([int]$descendant)
    }
  }
  return $ids
}

function Stop-ProcessTree {
  param([int]$RootProcessId)

  $allIds = New-Object System.Collections.Generic.List[int]
  foreach ($childId in Get-ChildProcessIds -ParentId $RootProcessId) {
    $allIds.Add([int]$childId)
  }
  $allIds.Add($RootProcessId)

  foreach ($id in ($allIds | Select-Object -Unique | Sort-Object -Descending)) {
    try {
      Stop-Process -Id $id -Force -ErrorAction Stop
    } catch {
    }
  }
}

try {
  if (-not $ReuseExistingGpuEngine -and (Test-TcpPort -Address "127.0.0.1" -Port $GpuPort)) {
    throw "Port 127.0.0.1:$GpuPort is already in use. Use -ReuseExistingGpuEngine if that is intentional."
  }
  if (-not $ReuseExistingApp -and (Test-TcpPort -Address "127.0.0.1" -Port $AppPort)) {
    throw "Port 127.0.0.1:$AppPort is already in use. Use -ReuseExistingApp if that is intentional."
  }

  if (-not $ReuseExistingGpuEngine) {
    & wsl.exe -d $WslDistro bash -lc "test -d '$WslGpuRepo'"
    if ($LASTEXITCODE -ne 0) {
      throw "WSL GPU repo path not found: $WslGpuRepo"
    }

    $gpuCommand = 'source "$HOME/.cargo/env" 2>/dev/null || true; export PATH="$HOME/.cargo/bin:$PATH"; export GPU_ENGINE_ADDR="127.0.0.1:{0}"; cd "{1}"; cargo run' -f $GpuPort, $WslGpuRepo
    $gpuProcess = Start-Process -FilePath "wsl.exe" `
      -ArgumentList @("-d", $WslDistro, "bash", "-lc", $gpuCommand) `
      -RedirectStandardOutput $gpuLog `
      -RedirectStandardError $gpuErrLog `
      -PassThru
    $startedGpu = $true
  }

  Wait-TcpPort -Address "127.0.0.1" -Port $GpuPort -TimeoutSeconds $StartupTimeoutSeconds -Label "GPU engine"

  if (-not $ReuseExistingApp) {
    $javaCommand = @"
`$env:JAVA_HOME = '$JavaHome'
`$env:PATH = '$JavaHome\bin;' + `$env:PATH
`$env:APP_E2E_ENABLED = 'true'
`$env:APP_GPU_ENABLED = 'true'
`$env:APP_GPU_WORLD_ENABLED = 'true'
`$env:APP_GPU_BOTS_ENABLED = 'true'
`$env:APP_GPU_BOTS_APPLYEDITS = 'true'
`$env:APP_GPU_BOTS_CACHEWORLD = 'false'
`$env:APP_GPU_BOTS_STEPS = '1'
`$env:APP_GPU_HOST = '127.0.0.1'
`$env:APP_GPU_PORT = '$GpuPort'
`$env:APP_CONSENSUS_MAINTENANCEINTERVALMS = '$MaintenanceIntervalMs'
& '$MavenCmd' spring-boot:run
"@
    $appProcess = Start-Process -FilePath "powershell.exe" `
      -ArgumentList @("-NoProfile", "-Command", $javaCommand) `
      -WorkingDirectory $repoRoot `
      -RedirectStandardOutput $appLog `
      -RedirectStandardError $appErrLog `
      -PassThru
    $startedApp = $true
  }

  Wait-TcpPort -Address "127.0.0.1" -Port $AppPort -TimeoutSeconds $StartupTimeoutSeconds -Label "Java app"

  $baseUrl = "http://127.0.0.1:$AppPort"
  $token = Wait-HttpToken -Url "$baseUrl/game/test" -TimeoutSeconds $StartupTimeoutSeconds

  $worldState = Invoke-RestMethod -Uri "$baseUrl/game/state" `
    -Method Post `
    -ContentType "application/json" `
    -Body (@{ token = $token } | ConvertTo-Json)
  $worldStats = Count-WorldStats -StateResponse $worldState

  if ($worldStats.Width -le 0 -or $worldStats.Height -le 0) {
    throw "World state did not include a valid tile grid."
  }
  if ($worldStats.NonZeroTileCount -ne 0 -or $worldStats.HighwayCount -ne 0) {
    throw "Expected the current Rust stub engine to yield an all-zero world and zero highways, got nonZeroTiles=$($worldStats.NonZeroTileCount) highwayCount=$($worldStats.HighwayCount)."
  }

  $roomCreate = Invoke-RestMethod -Uri "$baseUrl/room/create" `
    -Method Post `
    -ContentType "application/json" `
    -Body (@{
        token = $token
        name = "Real GPU E2E Room $timestamp"
      } | ConvertTo-Json)

  $roomId = $roomCreate.roomId
  if ([string]::IsNullOrWhiteSpace($roomId)) {
    throw "Room creation did not return a roomId."
  }

  $initialBots = Get-BotPositions -Bots $roomCreate.bots
  if ($initialBots.Count -eq 0) {
    throw "Room creation did not return any bots."
  }

  $deadline = (Get-Date).AddSeconds($BotPollSeconds)
  $moved = $false
  $lastState = $null
  while ((Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 1000
    $lastState = Invoke-RestMethod -Uri "$baseUrl/room/$roomId/state" `
      -Method Post `
      -ContentType "application/json" `
      -Body (@{
          token = $token
          roomId = $roomId
        } | ConvertTo-Json)
    $currentBots = Get-BotPositions -Bots $lastState.bots
    if (Test-AnyBotMoved -Initial $initialBots -Current $currentBots) {
      $moved = $true
      break
    }
  }

  if (-not $moved) {
    throw "No bot positions changed within $BotPollSeconds seconds."
  }

  if ($startedApp) {
    Stop-ProcessTree -RootProcessId $appProcess.Id
    $startedApp = $false
    Start-Sleep -Seconds 1
    Assert-NoGpuFallbackWarnings -LogPaths @($appLog, $appErrLog)
  }
  if ($startedGpu) {
    Stop-ProcessTree -RootProcessId $gpuProcess.Id
    $startedGpu = $false
  }

  [pscustomobject]@{
    world_width = $worldStats.Width
    world_height = $worldStats.Height
    non_zero_tiles = $worldStats.NonZeroTileCount
    highway_count = $worldStats.HighwayCount
    room_id = $roomId
    initial_bot_count = $initialBots.Count
    bot_move_detected = $moved
    gpu_log = $gpuLog
    gpu_err_log = $gpuErrLog
    app_log = $appLog
    app_err_log = $appErrLog
  } | Format-List | Out-String | Write-Host
} finally {
  if ($startedApp -and $appProcess) {
    Stop-ProcessTree -RootProcessId $appProcess.Id
  }
  if ($startedGpu -and $gpuProcess) {
    Stop-ProcessTree -RootProcessId $gpuProcess.Id
  }
}
