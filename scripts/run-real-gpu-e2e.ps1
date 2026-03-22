param(
  [string]$JavaHome = "",
  [string]$MavenCmd = "",
  [Alias("WindowsGpuRepo")][string]$GpuRepo = "",
  [int]$AppPort = 8080,
  [int]$GpuPort = 50051,
  [int]$StartupTimeoutSeconds = 180,
  [int]$BotPollSeconds = 30,
  [int]$MaintenanceIntervalMs = 500,
  [switch]$RequireGpuPatch,
  [switch]$ReuseExistingGpuEngine,
  [switch]$ReuseExistingApp
)

$ErrorActionPreference = "Stop"
$pathSeparator = [System.IO.Path]::PathSeparator
$shellCmd = if ($IsWindows) { "powershell.exe" } else { "pwsh" }

if ([string]::IsNullOrWhiteSpace($MavenCmd)) {
  $MavenCmd = $env:MAVEN_CMD
}
if ([string]::IsNullOrWhiteSpace($MavenCmd)) {
  $MavenCmd = if ($IsWindows) { "mvn.cmd" } else { "mvn" }
}
if ([string]::IsNullOrWhiteSpace($JavaHome)) {
  if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    $JavaHome = $env:JAVA_HOME
  } elseif ($IsWindows) {
    $JavaHome = "C:\dev\lang\java\amazon-corretto-21.0.6.7.1-windows-x64-jdk\jdk21.0.6_7"
  } else {
    $JavaHome = "/usr/lib/jvm/java-21-openjdk"
  }
}
if ([string]::IsNullOrWhiteSpace($GpuRepo)) {
  $GpuRepo = if ($IsWindows) { "C:\dev\ide\eclipse\workspace-web\EmergentTerrainGPUEngine" } else { "/opt/apps/ide/eclipse/workspace-web/EmergentTerrainGPUEngine" }
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$outputDir = Join-Path $repoRoot "sample-output/gpu-e2e-$timestamp"
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
$gpuLog = Join-Path $outputDir "gpu-engine.log"
$gpuErrLog = Join-Path $outputDir "gpu-engine.err.log"
$appLog = Join-Path $outputDir "java-app.log"
$appErrLog = Join-Path $outputDir "java-app.err.log"
$proposalAttemptLog = Join-Path $outputDir "proposal-attempts.jsonl"

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

function Assert-LogContains {
  param(
    [string]$LogPath,
    [string[]]$Patterns
  )

  if (-not (Test-Path $LogPath)) {
    throw "Expected log file was not found: $LogPath"
  }

  foreach ($pattern in $Patterns) {
    $match = Select-String -Path $LogPath -Pattern $pattern -Quiet
    if (-not $match) {
      throw "Expected pattern '$pattern' was not found in $LogPath"
    }
  }
}

function Test-LogContains {
  param(
    [string]$LogPath,
    [string]$Pattern
  )

  if (-not (Test-Path $LogPath)) {
    return $false
  }
  return Select-String -Path $LogPath -Pattern $Pattern -Quiet
}

function Copy-TileGrid {
  param($Tiles)

  $copy = @()
  foreach ($row in @($Tiles)) {
    $copy += ,([int[]](@($row) | ForEach-Object { [int]$_ }))
  }
  return ,$copy
}

function Get-ProposalCandidates {
  param(
    $RoomState,
    [string]$ParticipantId
  )

  $participants = @($RoomState.participants)
  $participant = $participants | Where-Object { $_.id -eq $ParticipantId } | Select-Object -First 1
  if (-not $participant) {
    $participant = $participants | Select-Object -First 1
  }
  if (-not $participant) {
    throw "Could not locate a participant for proposal testing."
  }

  $tiles = @($RoomState.tiles)
  $highways = @($RoomState.highways)
  $height = $tiles.Count
  $width = if ($height -gt 0) { @($tiles[0]).Count } else { 0 }
  $offsets = @(
    @{ dx = 0; dy = 0 },
    @{ dx = 1; dy = 0 },
    @{ dx = 0; dy = 1 },
    @{ dx = -1; dy = 0 },
    @{ dx = 0; dy = -1 }
  )

  $candidates = @()
  foreach ($offset in $offsets) {
    $x = [Math]::Max(0, [Math]::Min($width - 1, [int]$participant.x + [int]$offset.dx))
    $y = [Math]::Max(0, [Math]::Min($height - 1, [int]$participant.y + [int]$offset.dy))
    $currentTile = [int]$tiles[$y][$x]
    $currentHighway = [bool]$highways[$y][$x]

    $preferredTypes = @()
    if (-not $currentHighway) {
      $preferredTypes += 4
    }
    if ($currentTile -eq 1) {
      $preferredTypes += 0
    } else {
      $preferredTypes += 1
    }
    $preferredTypes += 2
    $preferredTypes += 3

    foreach ($type in $preferredTypes | Select-Object -Unique) {
      $expectedTile = if ($type -eq 4) { 0 } else { $type }
      $expectedHighway = ($type -eq 4)
      if ($currentTile -eq $expectedTile -and $currentHighway -eq $expectedHighway) {
        continue
      }
      $candidates += [pscustomobject]@{
        x = $x
        y = $y
        type = $type
        expectedTile = $expectedTile
        expectedHighway = $expectedHighway
      }
    }
  }
  return $candidates
}

function Test-ProposalApplied {
  param(
    $RoomState,
    [int]$X,
    [int]$Y,
    [int]$ExpectedTile,
    [bool]$ExpectedHighway
  )

  if (-not $RoomState) {
    return $false
  }
  return ([int]$RoomState.tiles[$Y][$X] -eq $ExpectedTile) -and ([bool]$RoomState.highways[$Y][$X] -eq $ExpectedHighway)
}

function Get-ChildProcessIds {
  param([int]$ParentId)

  if ($IsWindows) {
    $children = @(Get-CimInstance Win32_Process -Filter "ParentProcessId = $ParentId" -ErrorAction SilentlyContinue)
  } else {
    $children = @()
    try {
      $psChildren = & ps -o pid= --ppid $ParentId 2>$null
      foreach ($child in @($psChildren)) {
        $trimmed = "$child".Trim()
        if (-not [string]::IsNullOrWhiteSpace($trimmed)) {
          $children += [pscustomobject]@{ ProcessId = [int]$trimmed }
        }
      }
    } catch {
      $children = @()
    }
  }
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
    if ($IsWindows) {
      try {
        Stop-Process -Id $id -Force -ErrorAction Stop
      } catch {
      }
      continue
    }

    try {
      if (Get-Process -Id $id -ErrorAction SilentlyContinue) {
        & kill -KILL $id 2>$null
      }
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
    if (-not (Test-Path $GpuRepo)) {
      throw "GPU repo path not found: $GpuRepo"
    }

    $cargoBinDir = if ($IsWindows) { Join-Path $env:USERPROFILE ".cargo\bin" } else { Join-Path $env:HOME ".cargo/bin" }
    $gpuCommand = @"
`$env:PATH = '$cargoBinDir$pathSeparator' + `$env:PATH
`$env:GPU_ENGINE_ADDR = '127.0.0.1:$GpuPort'
Set-Location '$GpuRepo'
& cargo run
"@
    $gpuProcess = Start-Process -FilePath $shellCmd `
      -ArgumentList @("-NoProfile", "-Command", $gpuCommand) `
      -WorkingDirectory $GpuRepo `
      -RedirectStandardOutput $gpuLog `
      -RedirectStandardError $gpuErrLog `
      -PassThru
    $startedGpu = $true
  }

  Wait-TcpPort -Address "127.0.0.1" -Port $GpuPort -TimeoutSeconds $StartupTimeoutSeconds -Label "GPU engine"

  if (-not $ReuseExistingApp) {
    $javaBinDir = Join-Path $JavaHome "bin"
    $javaCommand = @"
`$env:JAVA_HOME = '$JavaHome'
`$env:PATH = '$javaBinDir$pathSeparator' + `$env:PATH
`$env:APP_E2E_ENABLED = 'true'
`$env:APP_GPU_ENABLED = 'true'
`$env:APP_GPU_WORLD_ENABLED = 'true'
`$env:APP_GPU_BOTS_ENABLED = 'true'
`$env:APP_GPU_BOTS_APPLYEDITS = 'true'
`$env:APP_GPU_BOTS_CACHEWORLD = 'true'
`$env:APP_GPU_BOTS_STEPS = '10'
`$env:APP_GPU_BOTS_MINBOTCOUNT = '1'
`$env:APP_GPU_BOTS_MINWORKITEMS = '1'
`$env:APP_GPU_PATCH_MINAREA = '1'
`$env:APP_BOTS_INITIALROOMCREATETARGET = '1000'
`$env:APP_GPU_HOST = '127.0.0.1'
`$env:APP_GPU_PORT = '$GpuPort'
`$env:APP_CONSENSUS_MAINTENANCEINTERVALMS = '$MaintenanceIntervalMs'
`$env:APP_CONSENSUS_MINVOTESTORESOLVE = '1'
`$env:APP_CONSENSUS_MINNEIGHBORS = '1'
`$env:APP_CONSENSUS_QUORUMCAP = '1'
& '$MavenCmd' spring-boot:run
"@
    $appProcess = Start-Process -FilePath $shellCmd `
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
  if ($worldStats.NonZeroTileCount -le 0 -or $worldStats.HighwayCount -le 0) {
    throw "Expected the GPU engine to yield a non-stub world with non-zero tiles and highways, got nonZeroTiles=$($worldStats.NonZeroTileCount) highwayCount=$($worldStats.HighwayCount)."
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

  $proposalApplied = $false
  $proposalX = $null
  $proposalY = $null
  $proposalType = $null
  $proposalState = Invoke-RestMethod -Uri "$baseUrl/room/$roomId/state" `
    -Method Post `
    -ContentType "application/json" `
    -Body (@{
        token = $token
        roomId = $roomId
      } | ConvertTo-Json)
  $proposalCandidates = Get-ProposalCandidates -RoomState $proposalState -ParticipantId $roomCreate.participantId

  foreach ($candidate in $proposalCandidates) {
    $proposedTiles = Copy-TileGrid -Tiles $proposalState.tiles
    $proposedTiles[$candidate.y][$candidate.x] = $candidate.type

    $proposalResponse = Invoke-RestMethod -Uri "$baseUrl/room/$roomId/propose" `
      -Method Post `
      -ContentType "application/json" `
      -Body (@{
          token = $token
          tiles = $proposedTiles
          delta = @{
            x = $candidate.x
            y = $candidate.y
            type = $candidate.type
          }
          note = "gpu-e2e-proposal-$timestamp-$($candidate.x)-$($candidate.y)-$($candidate.type)"
        } | ConvertTo-Json -Depth 100)

    [pscustomobject]@{
      candidate_x = $candidate.x
      candidate_y = $candidate.y
      candidate_type = $candidate.type
      expected_tile = $candidate.expectedTile
      expected_highway = $candidate.expectedHighway
      response_tile = [int]$proposalResponse.tiles[$candidate.y][$candidate.x]
      response_highway = [bool]$proposalResponse.highways[$candidate.y][$candidate.x]
      response_proposals = @($proposalResponse.proposals | ForEach-Object {
        [pscustomobject]@{
          id = $_.id
          status = $_.status
          tileType = $_.tileType
          anchorX = $_.anchorX
          anchorY = $_.anchorY
          approvals = $_.approvals
          rejections = $_.rejections
          requiredVotes = $_.requiredVotes
        }
      })
    } | ConvertTo-Json -Depth 10 -Compress | Add-Content -Path $proposalAttemptLog

    $proposalDeadline = (Get-Date).AddSeconds(10)
    while ((Get-Date) -lt $proposalDeadline) {
      Start-Sleep -Milliseconds 1000
      $proposalState = Invoke-RestMethod -Uri "$baseUrl/room/$roomId/state" `
        -Method Post `
        -ContentType "application/json" `
        -Body (@{
            token = $token
            roomId = $roomId
          } | ConvertTo-Json)
      if (Test-ProposalApplied -RoomState $proposalState -X $candidate.x -Y $candidate.y -ExpectedTile $candidate.expectedTile -ExpectedHighway $candidate.expectedHighway) {
        $proposalApplied = $true
        $proposalX = $candidate.x
        $proposalY = $candidate.y
        $proposalType = $candidate.type
        break
      }
    }
    if ($proposalApplied) {
      break
    }
  }

  if (-not $proposalApplied) {
    throw "No proposal candidate was applied within the allotted time."
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

  $requiredPatterns = @(
    "generate_region request_id=",
    "simulate_bots request_id=",
    '"backend":"wgpu-world"',
    '"backend":"wgpu-bot-sim"'
  )
  if ($RequireGpuPatch) {
    $requiredPatterns += "rebuild_patch request_id="
    $requiredPatterns += '"backend":"wgpu-patch"'
  }
  Assert-LogContains -LogPath $gpuLog -Patterns $requiredPatterns
  $gpuPatchObserved = (Test-LogContains -LogPath $gpuLog -Pattern "rebuild_patch request_id=") -and
    (Test-LogContains -LogPath $gpuLog -Pattern '"backend":"wgpu-patch"')

  [pscustomobject]@{
    world_width = $worldStats.Width
    world_height = $worldStats.Height
    non_zero_tiles = $worldStats.NonZeroTileCount
    highway_count = $worldStats.HighwayCount
    room_id = $roomId
    initial_bot_count = $initialBots.Count
    bot_move_detected = $moved
    proposal_applied = $proposalApplied
    proposal_x = $proposalX
    proposal_y = $proposalY
    proposal_type = $proposalType
    gpu_patch_observed = $gpuPatchObserved
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
