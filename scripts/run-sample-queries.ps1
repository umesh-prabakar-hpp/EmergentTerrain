param(
  [int]$Count = 3,
  [int]$IntervalMs = 60000,
  [string]$RoomId = "",
  [switch]$FailOnError,
  [string]$OutputDir = ""
)

$mvn = $env:MAVEN_CMD
if ([string]::IsNullOrWhiteSpace($mvn)) {
  $mvn = "C:\dev\build\maven\apache-maven-3.8.6\bin\mvn.cmd"
}

$props = @(
  "-Dtest=RoomStateSamplingIntegrationTest",
  "-Dspring.profiles.active=sample",
  "-Dsample.count=$Count",
  "-Dsample.intervalMs=$IntervalMs"
)

if (-not [string]::IsNullOrWhiteSpace($RoomId)) {
  $props += "-Dsample.roomId=$RoomId"
}
if ($FailOnError.IsPresent) {
  $props += "-Dsample.failOnError=true"
}
if (-not [string]::IsNullOrWhiteSpace($OutputDir)) {
  $props += "-Dsample.outputDir=$OutputDir"
}

& $mvn @props test
if ($LASTEXITCODE -ne 0) {
  throw "Sample query test failed with exit code $LASTEXITCODE"
}
