param(
  [int]$Iterations = 2,
  [int]$BotSimulationIterations = 10,
  [string]$JavaHome = "",
  [string]$MavenCmd = "",
  [string]$JavaRepo = "",
  [string]$GpuRepo = "",
  [switch]$BuildArtifacts
)

$ErrorActionPreference = "Stop"
$pathSeparator = [System.IO.Path]::PathSeparator

if ([string]::IsNullOrWhiteSpace($JavaHome)) {
  if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    $JavaHome = $env:JAVA_HOME
  } elseif ($IsWindows) {
    $JavaHome = "C:\dev\lang\java\amazon-corretto-21.0.6.7.1-windows-x64-jdk\jdk21.0.6_7"
  } else {
    $JavaHome = "/usr/lib/jvm/java-21-openjdk"
  }
}
if ([string]::IsNullOrWhiteSpace($MavenCmd)) {
  if (-not [string]::IsNullOrWhiteSpace($env:MAVEN_CMD)) {
    $MavenCmd = $env:MAVEN_CMD
  } elseif ($IsWindows) {
    $MavenCmd = "mvn.cmd"
  } else {
    $MavenCmd = "mvn"
  }
}
if ([string]::IsNullOrWhiteSpace($JavaRepo)) {
  $JavaRepo = if ($IsWindows) { "C:\dev\ide\eclipse\workspace-web\EmergentTerrain" } else { "/opt/apps/ide/eclipse/workspace-web/EmergentTerrain" }
}
if ([string]::IsNullOrWhiteSpace($GpuRepo)) {
  $GpuRepo = if ($IsWindows) { "C:\dev\ide\eclipse\workspace-web\EmergentTerrainGPUEngine" } else { "/opt/apps/ide/eclipse/workspace-web/EmergentTerrainGPUEngine" }
}

$javaBinDir = Join-Path $JavaHome "bin"
$javaExeName = if ($IsWindows) { "java.exe" } else { "java" }
$jarExeName = if ($IsWindows) { "jar.exe" } else { "jar" }
$javaCmd = Join-Path $javaBinDir $javaExeName
$jarCmd = Join-Path $javaBinDir $jarExeName
$gpuBinary = if ($IsWindows) { "emergent_terrain_gpu_engine.exe" } else { "emergent_terrain_gpu_engine" }

$env:JAVA_HOME = $JavaHome
$env:PATH = "$javaBinDir$pathSeparator$env:PATH"

$jarPath = Join-Path $JavaRepo "target/emergent-terrain-0.0.1-SNAPSHOT.jar"
$gpuExe = Join-Path $GpuRepo "target/release/$gpuBinary"
$outputRoot = Join-Path $JavaRepo "sample-output"
New-Item -ItemType Directory -Path $outputRoot -Force | Out-Null

function Test-TcpPort {
  param([string]$Address, [int]$Port)
  $client = New-Object System.Net.Sockets.TcpClient
  try {
    $async = $client.BeginConnect($Address, $Port, $null, $null)
    if (-not $async.AsyncWaitHandle.WaitOne(500)) { return $false }
    $client.EndConnect($async) | Out-Null
    return $true
  } catch {
    return $false
  } finally {
    $client.Dispose()
  }
}

function Wait-TcpPort {
  param([string]$Address, [int]$Port, [int]$TimeoutSeconds, [string]$Label)
  $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
  while ((Get-Date) -lt $deadline) {
    if (Test-TcpPort -Address $Address -Port $Port) { return }
    Start-Sleep -Milliseconds 250
  }
  throw "$Label did not open $Address`:$Port within $TimeoutSeconds seconds."
}

function Get-TestToken {
  param([string]$BaseUrl, [int]$TimeoutSeconds)
  $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
  while ((Get-Date) -lt $deadline) {
    try {
      $response = Invoke-RestMethod -Uri "$BaseUrl/game/test-token" -UseBasicParsing
      if ($response.token) { return $response.token }
    } catch {
    }
    Start-Sleep -Milliseconds 250
  }
  throw "Could not obtain a token from $BaseUrl within $TimeoutSeconds seconds."
}

function Get-BotPositions {
  param($Bots)
  $positions = @{}
  foreach ($bot in @($Bots)) { $positions[$bot.id] = "$($bot.x),$($bot.y)" }
  return $positions
}

function Test-AnyBotMoved {
  param([hashtable]$Initial, [hashtable]$Current)
  foreach ($id in $Initial.Keys) {
    if ($Current.ContainsKey($id) -and $Current[$id] -ne $Initial[$id]) { return $true }
  }
  return $false
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
  param($RoomState, [string]$ParticipantId)
  $participants = @($RoomState.participants)
  $participant = $participants | Where-Object { $_.id -eq $ParticipantId } | Select-Object -First 1
  if (-not $participant) { $participant = $participants | Select-Object -First 1 }
  if (-not $participant) { throw "Could not locate a participant for proposal testing." }

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
    if (-not $currentHighway) { $preferredTypes += 4 }
    if ($currentTile -eq 1) { $preferredTypes += 0 } else { $preferredTypes += 1 }
    $preferredTypes += 2
    $preferredTypes += 3

    foreach ($type in $preferredTypes | Select-Object -Unique) {
      $expectedTile = if ($type -eq 4) { 0 } else { $type }
      $expectedHighway = ($type -eq 4)
      if ($currentTile -eq $expectedTile -and $currentHighway -eq $expectedHighway) { continue }
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
  param($RoomState, [int]$X, [int]$Y, [int]$ExpectedTile, [bool]$ExpectedHighway)
  if (-not $RoomState) { return $false }
  return ([int]$RoomState.tiles[$Y][$X] -eq $ExpectedTile) -and ([bool]$RoomState.highways[$Y][$X] -eq $ExpectedHighway)
}

function Count-WorldStats {
  param($StateResponse)
  $tileRows = @($StateResponse.tiles)
  $highwayRows = @($StateResponse.highways)
  $nonZeroTileCount = 0
  $highwayCount = 0
  foreach ($row in $tileRows) { foreach ($tile in @($row)) { if ([int]$tile -ne 0) { $nonZeroTileCount++ } } }
  foreach ($row in $highwayRows) { foreach ($cell in @($row)) { if ([bool]$cell) { $highwayCount++ } } }
  [pscustomobject]@{
    Width = if ($tileRows.Count -gt 0) { @($tileRows[0]).Count } else { 0 }
    Height = $tileRows.Count
    NonZeroTileCount = $nonZeroTileCount
    HighwayCount = $highwayCount
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
    try { Stop-Process -Id $id -Force -ErrorAction Stop } catch {}
  }
}

function Start-DirectProcess {
  param(
    [string]$FilePath,
    [string[]]$ArgumentList,
    [string]$WorkingDirectory,
    [string]$StdoutPath,
    [string]$StderrPath,
    [hashtable]$EnvironmentOverrides = @{},
    [string[]]$EnvironmentRemovals = @()
  )

  $savedValues = @{}
  foreach ($name in $EnvironmentRemovals) {
    $savedValues[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
    Remove-Item "Env:$name" -ErrorAction SilentlyContinue
  }
  foreach ($entry in $EnvironmentOverrides.GetEnumerator()) {
    $savedValues[$entry.Key] = [Environment]::GetEnvironmentVariable($entry.Key, "Process")
    [Environment]::SetEnvironmentVariable($entry.Key, [string]$entry.Value, "Process")
  }

  try {
    if ($ArgumentList -and $ArgumentList.Count -gt 0) {
      return Start-Process -FilePath $FilePath `
        -ArgumentList $ArgumentList `
        -WorkingDirectory $WorkingDirectory `
        -RedirectStandardOutput $StdoutPath `
        -RedirectStandardError $StderrPath `
        -PassThru
    }
    return Start-Process -FilePath $FilePath `
      -WorkingDirectory $WorkingDirectory `
      -RedirectStandardOutput $StdoutPath `
      -RedirectStandardError $StderrPath `
      -PassThru
  } finally {
    foreach ($name in $EnvironmentRemovals) {
      [Environment]::SetEnvironmentVariable($name, $savedValues[$name], "Process")
    }
    foreach ($entry in $EnvironmentOverrides.GetEnumerator()) {
      [Environment]::SetEnvironmentVariable($entry.Key, $savedValues[$entry.Key], "Process")
    }
  }
}

function Build-Artifacts {
  $needsBootJar = $true
  if (Test-Path $jarPath) {
    $jarEntries = & $jarCmd tf $jarPath 2>$null
    if ($LASTEXITCODE -eq 0 -and ($jarEntries | Select-String -Pattern '^BOOT-INF/' -Quiet)) {
      $needsBootJar = $false
    }
  }
  if ($needsBootJar) {
    & $MavenCmd -DskipTests package spring-boot:repackage
    if ($LASTEXITCODE -ne 0) { throw "Java boot jar build failed." }
  }
  if (-not (Test-Path $gpuExe)) {
    & cargo build --release
    if ($LASTEXITCODE -ne 0) { throw "GPU release build failed." }
  }
}

function Run-Scenario {
  param([string]$Mode, [int]$Iteration)

  $appPort = 8080
  $gpuPort = 50051
  if (Test-TcpPort -Address "127.0.0.1" -Port $appPort) { throw "Port 8080 already in use." }
  if ($Mode -eq "gpu" -and (Test-TcpPort -Address "127.0.0.1" -Port $gpuPort)) { throw "Port 50051 already in use." }

  $tag = "$Mode-$Iteration-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
  $outputDir = Join-Path $outputRoot "perf-release-$tag"
  New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
  $appLog = Join-Path $outputDir "java-app.log"
  $appErrLog = Join-Path $outputDir "java-app.err.log"
  $gpuLog = Join-Path $outputDir "gpu-engine.log"
  $gpuErrLog = Join-Path $outputDir "gpu-engine.err.log"

  $gpuProcess = $null
  $appProcess = $null

  try {
    $startupTimer = [System.Diagnostics.Stopwatch]::StartNew()
    if ($Mode -eq "gpu") {
      $gpuProcess = Start-DirectProcess `
        -FilePath $gpuExe `
        -ArgumentList @() `
        -WorkingDirectory $GpuRepo `
        -StdoutPath $gpuLog `
        -StderrPath $gpuErrLog `
        -EnvironmentOverrides @{ GPU_ENGINE_ADDR = "127.0.0.1:$gpuPort" } `
        -EnvironmentRemovals @("DEBUG")
      Wait-TcpPort -Address "127.0.0.1" -Port $gpuPort -TimeoutSeconds 120 -Label "GPU engine"
    }
    $gpuReadyMs = $startupTimer.ElapsedMilliseconds

    $javaArgs = @(
      "-XX:TieredStopAtLevel=1",
      "-XX:CICompilerCount=2",
      "-Xshare:auto",
      "-Xms256m",
      "-Xmx512m",
      "-Djava.awt.headless=true",
      "-jar",
      $jarPath,
      "--spring.profiles.active=perf",
      "--app.e2e.enabled=true",
      "--app.consensus.minVotesToResolve=1",
      "--app.consensus.minNeighbors=1",
      "--app.consensus.quorumCap=1"
    )
    if ($Mode -eq "gpu") {
      $javaArgs += @(
        "--app.gpu.enabled=true",
        "--app.gpu.world.enabled=true",
        "--app.gpu.bots.enabled=true",
        "--app.gpu.bots.applyEdits=true",
        "--app.gpu.bots.cacheWorld=true",
        "--app.gpu.bots.steps=1",
        "--app.gpu.host=127.0.0.1",
        "--app.gpu.port=$gpuPort"
      )
    } else {
      $javaArgs += @(
        "--app.gpu.enabled=false",
        "--app.gpu.world.enabled=false",
        "--app.gpu.bots.enabled=false",
        "--app.gpu.bots.applyEdits=false"
      )
    }
    $appProcess = Start-DirectProcess `
      -FilePath $javaCmd `
      -ArgumentList $javaArgs `
      -WorkingDirectory $JavaRepo `
      -StdoutPath $appLog `
      -StderrPath $appErrLog `
      -EnvironmentRemovals @("DEBUG")
    Wait-TcpPort -Address "127.0.0.1" -Port $appPort -TimeoutSeconds 180 -Label "Java app"
    $appReadyMs = $startupTimer.ElapsedMilliseconds

    $baseUrl = "http://127.0.0.1:$appPort"
    $warmToken = Get-TestToken -BaseUrl $baseUrl -TimeoutSeconds 60
    Invoke-RestMethod -Uri "$baseUrl/game/state" -Method Post -ContentType "application/json" -Body (@{ token = $warmToken } | ConvertTo-Json) | Out-Null

    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $token = Get-TestToken -BaseUrl $baseUrl -TimeoutSeconds 60
    $tokenMs = $sw.ElapsedMilliseconds

    $sw.Restart()
    $worldState = Invoke-RestMethod -Uri "$baseUrl/game/state" -Method Post -ContentType "application/json" -Body (@{ token = $token } | ConvertTo-Json)
    $worldMs = $sw.ElapsedMilliseconds
    $worldStats = Count-WorldStats -StateResponse $worldState

    $sw.Restart()
    $roomCreate = Invoke-RestMethod -Uri "$baseUrl/room/create" -Method Post -ContentType "application/json" -Body (@{ token = $token; name = "Perf Release $tag" } | ConvertTo-Json)
    $roomCreateMs = $sw.ElapsedMilliseconds
    $roomId = $roomCreate.roomId

    $proposalState = $roomCreate
    $proposalCandidates = Get-ProposalCandidates -RoomState $proposalState -ParticipantId $roomCreate.participantId

    $proposalApplied = $false
    $proposalMs = $null
    $proposalType = $null
    foreach ($candidate in $proposalCandidates) {
      $proposedTiles = Copy-TileGrid -Tiles $proposalState.tiles
      $proposedTiles[$candidate.y][$candidate.x] = $candidate.type
      $sw.Restart()
      $proposalResponse = Invoke-RestMethod -Uri "$baseUrl/room/$roomId/propose" -Method Post -ContentType "application/json" -Body (@{
          token = $token
          tiles = $proposedTiles
          delta = @{ x = $candidate.x; y = $candidate.y; type = $candidate.type }
          note = "perf-release-$tag-$($candidate.x)-$($candidate.y)-$($candidate.type)"
        } | ConvertTo-Json -Depth 100)
      if (Test-ProposalApplied -RoomState $proposalResponse -X $candidate.x -Y $candidate.y -ExpectedTile $candidate.expectedTile -ExpectedHighway $candidate.expectedHighway) {
        $proposalApplied = $true
        $proposalMs = $sw.ElapsedMilliseconds
        $proposalType = $candidate.type
        break
      }
      $proposalDeadline = (Get-Date).AddSeconds(10)
      while ((Get-Date) -lt $proposalDeadline) {
        Start-Sleep -Milliseconds 100
        $proposalState = Invoke-RestMethod -Uri "$baseUrl/room/$roomId/state" -Method Post -ContentType "application/json" -Body (@{ token = $token; roomId = $roomId } | ConvertTo-Json)
        if (Test-ProposalApplied -RoomState $proposalState -X $candidate.x -Y $candidate.y -ExpectedTile $candidate.expectedTile -ExpectedHighway $candidate.expectedHighway) {
          $proposalApplied = $true
          $proposalMs = $sw.ElapsedMilliseconds
          $proposalType = $candidate.type
          break
        }
      }
      if ($proposalApplied) { break }
    }
    if (-not $proposalApplied) { throw "No proposal applied in $Mode mode." }

    $sw.Restart()
    $populateResponse = Invoke-RestMethod -Uri "$baseUrl/perf/room/$roomId/populate" -Method Post -ContentType "application/json" -Body "{}"
    $populateMs = $sw.ElapsedMilliseconds

    $sw.Restart()
    $botSimulationResponse = Invoke-RestMethod -Uri "$baseUrl/perf/room/$roomId/bot-simulation" -Method Post -ContentType "application/json" -Body (@{
        iterations = $BotSimulationIterations
        clearGpuWorldCache = $true
      } | ConvertTo-Json)
    $botSimulationClientMs = $sw.ElapsedMilliseconds

    $gpuPatchObserved = $false
    if ($Mode -eq "gpu") {
      $gpuPatchObserved = (Select-String -Path $gpuLog -Pattern "rebuild_patch request_id=" -Quiet) -and
        (Select-String -Path $gpuLog -Pattern '"backend":"wgpu-patch"' -Quiet)
    }

    return [pscustomobject]@{
      mode = $Mode
      iteration = $Iteration
      gpu_start_ms = if ($Mode -eq "gpu") { $gpuReadyMs } else { 0 }
      app_ready_ms = $appReadyMs
      token_ms = $tokenMs
      world_ms = $worldMs
      room_create_ms = $roomCreateMs
      proposal_apply_ms = $proposalMs
      populate_ms = $populateMs
      bot_sim_client_ms = $botSimulationClientMs
      bot_sim_server_total_ms = [long]$botSimulationResponse.totalMs
      bot_sim_server_avg_ms = [double]$botSimulationResponse.averageMs
      request_flow_ms = ($tokenMs + $worldMs + $roomCreateMs + $proposalMs + $populateMs + $botSimulationClientMs)
      total_ms = ($appReadyMs + $tokenMs + $worldMs + $roomCreateMs + $proposalMs + $populateMs + $botSimulationClientMs)
      gpu_patch_observed = $gpuPatchObserved
      world_non_zero_tiles = $worldStats.NonZeroTileCount
      world_highway_count = $worldStats.HighwayCount
      initial_bot_count = (@($roomCreate.bots)).Count
      populated_bot_count = [int]$populateResponse.botCount
      proposal_type = $proposalType
      output_dir = $outputDir
    }
  } finally {
    if ($appProcess) { Stop-ProcessTree -RootProcessId $appProcess.Id }
    if ($gpuProcess) { Stop-ProcessTree -RootProcessId $gpuProcess.Id }
    Start-Sleep -Seconds 1
  }
}

if ($BuildArtifacts) {
  Push-Location $JavaRepo
  try { & $MavenCmd -DskipTests package spring-boot:repackage } finally { Pop-Location }
  Push-Location $GpuRepo
  try { & cargo build --release } finally { Pop-Location }
} else {
  Build-Artifacts
}

$runs = @()
foreach ($mode in @("cpu", "gpu")) {
  foreach ($iteration in 1..$Iterations) {
    $runs += Run-Scenario -Mode $mode -Iteration $iteration
  }
}

$summary = foreach ($mode in @("cpu", "gpu")) {
  $items = @($runs | Where-Object { $_.mode -eq $mode })
  [pscustomobject]@{
    mode = $mode
    app_ready_ms_avg = [math]::Round((($items | Measure-Object app_ready_ms -Average).Average), 2)
    gpu_start_ms_avg = [math]::Round((($items | Measure-Object gpu_start_ms -Average).Average), 2)
    token_ms_avg = [math]::Round((($items | Measure-Object token_ms -Average).Average), 2)
    world_ms_avg = [math]::Round((($items | Measure-Object world_ms -Average).Average), 2)
    room_create_ms_avg = [math]::Round((($items | Measure-Object room_create_ms -Average).Average), 2)
    proposal_apply_ms_avg = [math]::Round((($items | Measure-Object proposal_apply_ms -Average).Average), 2)
    populate_ms_avg = [math]::Round((($items | Measure-Object populate_ms -Average).Average), 2)
    bot_sim_client_ms_avg = [math]::Round((($items | Measure-Object bot_sim_client_ms -Average).Average), 2)
    bot_sim_server_total_ms_avg = [math]::Round((($items | Measure-Object bot_sim_server_total_ms -Average).Average), 2)
    bot_sim_server_avg_ms_avg = [math]::Round((($items | Measure-Object bot_sim_server_avg_ms -Average).Average), 2)
    request_flow_ms_avg = [math]::Round((($items | Measure-Object request_flow_ms -Average).Average), 2)
    total_ms_avg = [math]::Round((($items | Measure-Object total_ms -Average).Average), 2)
    gpu_patch_observed_count = (@($items | Where-Object { $_.gpu_patch_observed }).Count)
    world_non_zero_tiles_avg = [math]::Round((($items | Measure-Object world_non_zero_tiles -Average).Average), 2)
    world_highway_count_avg = [math]::Round((($items | Measure-Object world_highway_count -Average).Average), 2)
    initial_bot_count_avg = [math]::Round((($items | Measure-Object initial_bot_count -Average).Average), 2)
    populated_bot_count_avg = [math]::Round((($items | Measure-Object populated_bot_count -Average).Average), 2)
  }
}

$cpu = $summary | Where-Object { $_.mode -eq "cpu" }
$gpu = $summary | Where-Object { $_.mode -eq "gpu" }
$diff = [pscustomobject]@{
  app_ready_ms_delta = [math]::Round(($gpu.app_ready_ms_avg - $cpu.app_ready_ms_avg), 2)
  world_ms_delta = [math]::Round(($gpu.world_ms_avg - $cpu.world_ms_avg), 2)
  room_create_ms_delta = [math]::Round(($gpu.room_create_ms_avg - $cpu.room_create_ms_avg), 2)
  proposal_apply_ms_delta = [math]::Round(($gpu.proposal_apply_ms_avg - $cpu.proposal_apply_ms_avg), 2)
  populate_ms_delta = [math]::Round(($gpu.populate_ms_avg - $cpu.populate_ms_avg), 2)
  bot_sim_client_ms_delta = [math]::Round(($gpu.bot_sim_client_ms_avg - $cpu.bot_sim_client_ms_avg), 2)
  bot_sim_server_total_ms_delta = [math]::Round(($gpu.bot_sim_server_total_ms_avg - $cpu.bot_sim_server_total_ms_avg), 2)
  request_flow_ms_delta = [math]::Round(($gpu.request_flow_ms_avg - $cpu.request_flow_ms_avg), 2)
  total_ms_delta = [math]::Round(($gpu.total_ms_avg - $cpu.total_ms_avg), 2)
  world_ms_pct = if ($cpu.world_ms_avg -ne 0) { [math]::Round((($gpu.world_ms_avg - $cpu.world_ms_avg) / $cpu.world_ms_avg) * 100, 2) } else { $null }
  room_create_ms_pct = if ($cpu.room_create_ms_avg -ne 0) { [math]::Round((($gpu.room_create_ms_avg - $cpu.room_create_ms_avg) / $cpu.room_create_ms_avg) * 100, 2) } else { $null }
  proposal_apply_ms_pct = if ($cpu.proposal_apply_ms_avg -ne 0) { [math]::Round((($gpu.proposal_apply_ms_avg - $cpu.proposal_apply_ms_avg) / $cpu.proposal_apply_ms_avg) * 100, 2) } else { $null }
  populate_ms_pct = if ($cpu.populate_ms_avg -ne 0) { [math]::Round((($gpu.populate_ms_avg - $cpu.populate_ms_avg) / $cpu.populate_ms_avg) * 100, 2) } else { $null }
  bot_sim_client_ms_pct = if ($cpu.bot_sim_client_ms_avg -ne 0) { [math]::Round((($gpu.bot_sim_client_ms_avg - $cpu.bot_sim_client_ms_avg) / $cpu.bot_sim_client_ms_avg) * 100, 2) } else { $null }
  bot_sim_server_total_ms_pct = if ($cpu.bot_sim_server_total_ms_avg -ne 0) { [math]::Round((($gpu.bot_sim_server_total_ms_avg - $cpu.bot_sim_server_total_ms_avg) / $cpu.bot_sim_server_total_ms_avg) * 100, 2) } else { $null }
  request_flow_ms_pct = if ($cpu.request_flow_ms_avg -ne 0) { [math]::Round((($gpu.request_flow_ms_avg - $cpu.request_flow_ms_avg) / $cpu.request_flow_ms_avg) * 100, 2) } else { $null }
  total_ms_pct = if ($cpu.total_ms_avg -ne 0) { [math]::Round((($gpu.total_ms_avg - $cpu.total_ms_avg) / $cpu.total_ms_avg) * 100, 2) } else { $null }
}

[pscustomobject]@{
  runs = $runs
  summary = $summary
  diff = $diff
} | ConvertTo-Json -Depth 6
