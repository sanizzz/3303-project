# Firefighting Drone Swarm (SYSC 3303) - Iteration 3

## Overview

This project simulates wildfire response with three subsystems:

- `Scheduler`
- `Drone subsystem`
- `Fire Incident subsystem`

Iteration 3 keeps the GUI from earlier iterations, but the subsystem implementation now supports:

- separate runnable programs for Scheduler, Drone, and Fire Incident subsystems
- UDP-only cross-subsystem communication
- multiple independent drone processes
- workload-aware scheduling across drones
- waiting-time-aware dispatching
- same-severity rerouting when a newly reported zone lies earlier on the path of an en-route drone
- drone status and location reporting back to the Scheduler

## Project Layout

- `src/` application source
- `tests/` JUnit tests for Iteration 3 behavior
- `sampleData/` sample zone and fire CSV files
- `lib/` third-party jars used by the project

## GUI

`Main` still provides the earlier GUI workflow:

1. launch the GUI
2. load an incident CSV
3. press `Start`

The GUI path was preserved to avoid breaking earlier demo behavior.

## Prerequisites

- JDK 11 or newer
- PowerShell

## Compile

From the `project` folder:

```powershell
$sources = Get-ChildItem -Recurse -Path src -Filter *.java | Select-Object -ExpandProperty FullName
javac -cp "lib\commons-lang3-3.20.0.jar;lib\opencsv-5.12.0.jar" -d bin $sources
```

## Run The GUI

```powershell
java -cp "bin;lib\commons-lang3-3.20.0.jar;lib\opencsv-5.12.0.jar" Main
```

## Run UDP Mode

Open the `project` folder in each terminal first.

Scheduler:

```powershell
java -cp "bin;lib\commons-lang3-3.20.0.jar;lib\opencsv-5.12.0.jar" Scheduler.SchedulerMain --zoneCsv sampleData/sample_zone_file.csv --drones 2 --firePort 5001 --droneStatusPort 5002 --droneHost localhost --droneCommandBasePort 5003 --fireHost localhost --completionPort 5008
```

Drone 1:

```powershell
java -cp "bin;lib\commons-lang3-3.20.0.jar;lib\opencsv-5.12.0.jar" Drone_subsystem.DroneSubsystemMain --droneId 1 --commandPort 5003 --schedulerHost localhost --schedulerStatusPort 5002 --zoneCsv sampleData/sample_zone_file.csv --timeScale 20
```

Drone 2:

```powershell
java -cp "bin;lib\commons-lang3-3.20.0.jar;lib\opencsv-5.12.0.jar" Drone_subsystem.DroneSubsystemMain --droneId 2 --commandPort 5004 --schedulerHost localhost --schedulerStatusPort 5002 --zoneCsv sampleData/sample_zone_file.csv --timeScale 20
```

Fire Incident subsystem:

```powershell
java -cp "bin;lib\commons-lang3-3.20.0.jar;lib\opencsv-5.12.0.jar" fire_incident_subsystem.FireIncidentSubsystemMain --csv sampleData/test_consecutive_missions.csv --schedulerHost localhost --schedulerPort 5001 --completionPort 5008 --timeScale 20
```

## Tests

The Iteration 3 tests live in:

- `tests/scheduler/SchedulerDispatchPriorityTest.java`
- `tests/scheduler/SchedulerReroutePolicyTest.java`
- `tests/scheduler/SchedulerWaitingTimeOptimizationTest.java`
- `tests/scheduler/SchedulerResourceDecisionTest.java`
- `tests/scheduler/SchedulerGuiCompatibilityTest.java`
- `tests/drone_subsystem/DroneLifecycleStateTest.java`
- `tests/drone_subsystem/DroneSubsystemIntegrationTest.java`
- `tests/fire_incident_subsystem/FireIncidentSubsystemTest.java`
- `tests/udp/UdpSupportTest.java`
- `tests/support/SchedulerTestSupport.java` helper for scheduler-facing tests

To run them, place `junit-platform-console-standalone-1.10.2.jar` in `lib/`, then use:

```powershell
$sources = Get-ChildItem -Recurse -Path src -Filter *.java | Select-Object -ExpandProperty FullName
javac -cp "lib\commons-lang3-3.20.0.jar;lib\opencsv-5.12.0.jar" -d bin $sources

$testSources = Get-ChildItem -Recurse -Path tests -Filter *.java | Select-Object -ExpandProperty FullName
javac -cp "bin;lib\commons-lang3-3.20.0.jar;lib\opencsv-5.12.0.jar;lib\junit-platform-console-standalone-1.10.2.jar" -d test-bin $testSources

java -jar "lib\junit-platform-console-standalone-1.10.2.jar" --class-path "bin;test-bin;lib\commons-lang3-3.20.0.jar;lib\opencsv-5.12.0.jar" --scan-class-path
```

## Best Demo Scenarios

- `sampleData/test_consecutive_missions.csv` with two drones to show load balancing and independent drone processes
- `sampleData/test_mixed_scenario.csv` to show queueing and scheduler decisions over time
- `SchedulerReroutePolicyTest.reroutesDroneToHigherSeverityFireWhileAlreadyEnRoute` for the TA's redirect-to-higher-priority scenario
- `SchedulerReroutePolicyTest.reroutesDroneToSameSeverityFireThatAppearsEarlierOnItsPath` for the TA's same-severity on-path hint
- `FireIncidentSubsystemTest.sendsUdpFireRequestsAndWaitsForAllCompletionAcknowledgements` to show the fire subsystem's UDP request/completion behavior
