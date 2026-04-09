param(
    [int]$DroneCount = 5,
    [int]$TimeScale = 200,
    [int]$FirePort = 5001,
    [int]$DroneStatusPort = 5002,
    [int]$DroneCommandBasePort = 5100,
    [int]$CompletionPort = 5200,
    [string]$SchedulerHost = "localhost",
    [string]$FireHost = "localhost",
    [string]$DroneHost = "localhost",
    [string]$ZoneCsv = "project\sampleData\Final_zone_file_w26.csv",
    [string]$EventCsv = "project\sampleData\Final_event_file_w26.csv",
    [switch]$SkipCompile
)

$ErrorActionPreference = "Stop"

function Escape-SingleQuotes([string]$text) {
    return $text -replace "'", "''"
}

function Resolve-FromRoot([string]$root, [string]$pathValue) {
    if ([System.IO.Path]::IsPathRooted($pathValue)) {
        return $pathValue
    }
    return [System.IO.Path]::GetFullPath((Join-Path $root $pathValue))
}

function Start-DemoWindow([string]$title, [string]$command) {
    $windowCommand = "`$host.UI.RawUI.WindowTitle = '$title'; Set-Location '$($script:EscapedRoot)'; $command"
    Start-Process powershell -ArgumentList @(
        "-NoExit",
        "-ExecutionPolicy", "Bypass",
        "-Command", $windowCommand
    ) | Out-Null
}

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$EscapedRoot = Escape-SingleQuotes $Root
$ZoneCsvAbs = Resolve-FromRoot $Root $ZoneCsv
$EventCsvAbs = Resolve-FromRoot $Root $EventCsv

if (-not (Test-Path $ZoneCsvAbs)) {
    throw "Zone CSV not found: $ZoneCsvAbs"
}
if (-not (Test-Path $EventCsvAbs)) {
    throw "Event CSV not found: $EventCsvAbs"
}

$BuildDir = Join-Path $Root "project\build_udp_demo"
$LibDir = Join-Path $Root "project\lib"
$SourceDir = Join-Path $Root "project\src"
$CompileCp = (Join-Path $LibDir "commons-lang3-3.20.0.jar") + ";" + (Join-Path $LibDir "opencsv-5.12.0.jar")
$RunCp = (Escape-SingleQuotes $BuildDir) + ";" +
    (Escape-SingleQuotes (Join-Path $LibDir "commons-lang3-3.20.0.jar")) + ";" +
    (Escape-SingleQuotes (Join-Path $LibDir "opencsv-5.12.0.jar"))

if (-not $SkipCompile) {
    Write-Host "Compiling sources into $BuildDir ..."
    if (-not (Test-Path $BuildDir)) {
        New-Item -ItemType Directory -Path $BuildDir | Out-Null
    }
    $sources = Get-ChildItem -Recurse -Path $SourceDir -Filter *.java | Select-Object -ExpandProperty FullName
    & javac -cp $CompileCp -d $BuildDir $sources
    Write-Host "Compile complete."
}

$schedulerCmd = "java -cp '$RunCp' Scheduler.SchedulerMain --zoneCsv '$((Escape-SingleQuotes $ZoneCsvAbs))' --drones $DroneCount --timeScale $TimeScale --fireHost $FireHost --droneHost $DroneHost --firePort $FirePort --droneStatusPort $DroneStatusPort --droneCommandBasePort $DroneCommandBasePort --completionPort $CompletionPort"
Start-DemoWindow "UDP Scheduler" $schedulerCmd

Start-Sleep -Seconds 1

for ($droneId = 1; $droneId -le $DroneCount; $droneId++) {
    $commandPort = $DroneCommandBasePort + ($droneId - 1)
    $droneCmd = "java -cp '$RunCp' Drone_subsystem.DroneSubsystemMain --droneId $droneId --commandPort $commandPort --schedulerHost $SchedulerHost --schedulerStatusPort $DroneStatusPort --zoneCsv '$((Escape-SingleQuotes $ZoneCsvAbs))' --timeScale $TimeScale"
    Start-DemoWindow "UDP Drone $droneId" $droneCmd
    Start-Sleep -Milliseconds 300
}

Start-Sleep -Seconds 2

$fireCmd = "java -cp '$RunCp' fire_incident_subsystem.FireIncidentSubsystemMain --csv '$((Escape-SingleQuotes $EventCsvAbs))' --schedulerHost $SchedulerHost --schedulerPort $FirePort --completionPort $CompletionPort --timeScale $TimeScale"
Start-DemoWindow "UDP Fire" $fireCmd

Write-Host "Launched scheduler, $DroneCount drone window(s), and fire subsystem."
Write-Host "Zone CSV: $ZoneCsvAbs"
Write-Host "Event CSV: $EventCsvAbs"
