param(
  [Parameter(Mandatory = $true)][string]$RunA,
  [Parameter(Mandatory = $true)][string]$RunB
)

function Read-Summary {
  param([string]$Path)
  if (-not (Test-Path $Path)) {
    throw "summary.csv not found: $Path"
  }
  $rows = Import-Csv $Path
  $grouped = $rows | Group-Object label
  $result = @{}
  foreach ($group in $grouped) {
    $label = $group.Name
    $avgGap = ($group.Group | Where-Object { $_.avg_gap_seconds -ne "" } | Measure-Object -Property avg_gap_seconds -Average).Average
    $maxGap = ($group.Group | Where-Object { $_.max_gap_seconds -ne "" } | Measure-Object -Property max_gap_seconds -Maximum).Maximum
    $totalChanges = ($group.Group | Where-Object { $_.total_changes -ne "" } | Measure-Object -Property total_changes -Sum).Sum
    $avgChangesPerFlush = ($group.Group | Where-Object { $_.avg_changes_per_flush -ne "" } | Measure-Object -Property avg_changes_per_flush -Average).Average
    $avgChangesPerMinute = ($group.Group | Where-Object { $_.avg_changes_per_minute -ne "" } | Measure-Object -Property avg_changes_per_minute -Average).Average

    $result[$label] = [PSCustomObject]@{
      Label = $label
      AvgGapSeconds = [math]::Round($avgGap, 2)
      MaxGapSeconds = [math]::Round($maxGap, 2)
      TotalChanges = $totalChanges
      AvgChangesPerFlush = [math]::Round($avgChangesPerFlush, 2)
      AvgChangesPerMinute = [math]::Round($avgChangesPerMinute, 2)
    }
  }
  return $result
}

$summaryA = Read-Summary -Path $RunA
$summaryB = Read-Summary -Path $RunB

"Comparison (A vs B)"
""
foreach ($label in @("gapStats", "avgChangesPerMinute")) {
  if ($summaryA.ContainsKey($label) -and $summaryB.ContainsKey($label)) {
    $a = $summaryA[$label]
    $b = $summaryB[$label]
    "Label: $label"
    "A: AvgGap=$($a.AvgGapSeconds) MaxGap=$($a.MaxGapSeconds) TotalChanges=$($a.TotalChanges) AvgChangesPerFlush=$($a.AvgChangesPerFlush) AvgChangesPerMinute=$($a.AvgChangesPerMinute)"
    "B: AvgGap=$($b.AvgGapSeconds) MaxGap=$($b.MaxGapSeconds) TotalChanges=$($b.TotalChanges) AvgChangesPerFlush=$($b.AvgChangesPerFlush) AvgChangesPerMinute=$($b.AvgChangesPerMinute)"
    ""
  }
}
