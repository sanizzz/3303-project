# SYSC 3303 Firefighting Drone Swarm - Iteration 3

## Project Overview

This project simulates a wildfire-response system with three main subsystems:

- `Scheduler`
- `Drone Subsystem`
- `Fire Incident Subsystem`

Iteration 3 adds a separate-process UDP deployment path while keeping the earlier GUI demo path working. The codebase now supports multiple independent drones, workload-aware scheduling, travel-time-aware dispatching, and rerouting of an in-flight drone when a better mission appears.

## Iteration 3 Features

- Separate runnable programs for the Scheduler, Fire Incident Subsystem, and Drone Subsystem.
- UDP-only communication between those separate processes.
- Multiple independent drone processes, each with its own command port.
- Scheduler dispatch decisions that prioritize:
  - higher fire severity
  - shorter estimated travel time
  - lower current assignment count per drone
  - older request time when the earlier criteria tie
- Rerouting support when:
  - a higher-severity fire appears while a drone is already `EN_ROUTE`
  - a same-severity fire appears earlier on that drone's current path
- Battery-feasibility checks before dispatching a mission.
- Return-to-base commands when a drone cannot safely serve pending work with its remaining resources.
- GUI compatibility retained through the existing `Main` launch path.

## System Architecture

### Scheduler

Source:

- `src/Scheduler/Scheduler.java`
- `src/Scheduler/SchedulerMain.java`

Responsibilities:

- receives fire requests
- tracks queued and in-progress missions
- tracks each drone's state, remaining agent, remaining battery, and latest position
- dispatches missions to specific drones
- decides when a drone should return to base
- forwards mission completion acknowledgements back to the Fire Incident Subsystem in UDP mode

### Drone Subsystem

Source:

- `src/Drone_subsystem/DroneSubsystem.java`
- `src/Drone_subsystem/DroneExecutionEngine.java`
- `src/Drone_subsystem/DroneSubsystemMain.java`
- `src/Drone_subsystem/Drone.java`

Responsibilities:

- listens for dispatch or return commands
- simulates travel, arrival, suppression, return, refill, and resume behavior
- sends status updates back to the Scheduler
- runs one drone per process in UDP mode

### Fire Incident Subsystem

Source:

- `src/fire_incident_subsystem/FireIncidentSubsystem.java`
- `src/fire_incident_subsystem/FireIncidentSubsystemMain.java`

Responsibilities:

- reads fire events from a CSV file
- submits those events in timestamp order
- waits for one completion acknowledgement per submitted event before exiting

### GUI

Source:

- `src/Main.java`
- `src/gui/SimulationGUI.java`

The GUI launch path remains available for a same-process demo. It still lets you:

- load an incident CSV
- configure drone count and capacity
- start the simulation
- watch zone color changes, active-fire count, and log output

## How Communication Works

In separate-process mode, subsystems communicate with plain UDP text messages over loopback by default:

- Fire Incident Subsystem -> Scheduler: `REQ|time|zoneId|eventType|severity`
- Scheduler -> Drone: `CMD|DISPATCH|droneId|missionId|zoneId|time|type|severity`
- Scheduler -> Drone: `CMD|RETURN|droneId`
- Drone -> Scheduler: `STATUS|droneId|state|missionId|remainingAgent|remainingBattery|x|y|message`
- Scheduler -> Fire Incident Subsystem: `COMP|zoneId|resolved`

Default ports from `src/types/UdpConfig.java`:

- fire requests to Scheduler: `5001`
- drone status to Scheduler: `5002`
- drone command base port: `5003`
- fire completion acknowledgements: `5004`

Important note for multiple drones:

- Drone command ports are `5003 + (droneId - 1)`.
- Because the default completion port is also `5004`, the default port set is only conflict-free for a single drone.
- For 2 or more drones, pass a different `--completionPort` to both `SchedulerMain` and `FireIncidentSubsystemMain`.

## How to Run the Project

### Prerequisites

- JDK 11 or newer
- PowerShell

Run the following commands from the `project` directory.

### Compile

```powershell
$sources = Get-ChildItem -Recurse -Path src -Filter *.java | Select-Object -ExpandProperty FullName
javac -cp "lib\commons-lang3-3.20.0.jar;lib\opencsv-5.12.0.jar" -d bin $sources
```

### Run the GUI Demo

This launches the preserved same-process demo path from `src/Main.java`.

```powershell
java -cp "bin;lib\commons-lang3-3.20.0.jar;lib\opencsv-5.12.0.jar" Main
```

GUI notes:

- Load an incident CSV with the `Load CSV` button.
- Set the number of drones and capacity in the top controls.
- Press `Start`.
- The GUI mode uses the sample zone map automatically and runs with a fixed time scale of `20`.

### Run the UDP Scheduler

Single-drone example using the default ports:

```powershell
java -cp "bin;lib\commons-lang3-3.20.0.jar;lib\opencsv-5.12.0.jar" Scheduler.SchedulerMain --zoneCsv sampleData/sample_zone_file.csv --drones 1
```

Two-drone example with a non-conflicting completion port:

```powershell
java -cp "bin;lib\commons-lang3-3.20.0.jar;lib\opencsv-5.12.0.jar" Scheduler.SchedulerMain --zoneCsv sampleData/sample_zone_file.csv --drones 2 --completionPort 5008
```

### Run One Drone

Drone 1 on its default command port:

```powershell
java -cp "bin;lib\commons-lang3-3.20.0.jar;lib\opencsv-5.12.0.jar" Drone_subsystem.DroneSubsystemMain --droneId 1 --commandPort 5003 --schedulerHost localhost --schedulerStatusPort 5002 --zoneCsv sampleData/sample_zone_file.csv --timeScale 20
```

### Run Multiple Drones

Each drone must run in its own terminal with its own command port.

Drone 1:

```powershell
java -cp "bin;lib\commons-lang3-3.20.0.jar;lib\opencsv-5.12.0.jar" Drone_subsystem.DroneSubsystemMain --droneId 1 --commandPort 5003 --schedulerHost localhost --schedulerStatusPort 5002 --zoneCsv sampleData/sample_zone_file.csv --timeScale 20
```

Drone 2:

```powershell
java -cp "bin;lib\commons-lang3-3.20.0.jar;lib\opencsv-5.12.0.jar" Drone_subsystem.DroneSubsystemMain --droneId 2 --commandPort 5004 --schedulerHost localhost --schedulerStatusPort 5002 --zoneCsv sampleData/sample_zone_file.csv --timeScale 20
```

If you add more drones, keep incrementing the command port from the scheduler's base port.

### Run the Fire Incident Subsystem

Single-drone example using the default completion port:

```powershell
java -cp "bin;lib\commons-lang3-3.20.0.jar;lib\opencsv-5.12.0.jar" fire_incident_subsystem.FireIncidentSubsystemMain --csv sampleData/test_mixed_scenario.csv --schedulerHost localhost --schedulerPort 5001 --completionPort 5004 --timeScale 20
```

Two-drone example matching the scheduler command above:

```powershell
java -cp "bin;lib\commons-lang3-3.20.0.jar;lib\opencsv-5.12.0.jar" fire_incident_subsystem.FireIncidentSubsystemMain --csv sampleData/test_mixed_scenario.csv --schedulerHost localhost --schedulerPort 5001 --completionPort 5008 --timeScale 20
```

Recommended launch order for UDP mode:

1. Start `SchedulerMain`.
2. Start one or more `DroneSubsystemMain` processes.
3. Start `FireIncidentSubsystemMain`.

## How to Run Tests

The tests are plain JUnit 5 source files under `tests/`. There is no Maven or Gradle build in this repository.

Main test groups:

- `tests/scheduler/`: dispatch priority, rerouting, waiting-time decisions, resource decisions, GUI compatibility
- `tests/drone_subsystem/`: drone lifecycle and local end-to-end integration
- `tests/fire_incident_subsystem/`: CSV reading and UDP fire/completion flow
- `tests/udp/`: UDP utility behavior and command-port allocation

To run the tests, place `junit-platform-console-standalone-1.10.2.jar` in `lib/`, then run:

```powershell
$sources = Get-ChildItem -Recurse -Path src -Filter *.java | Select-Object -ExpandProperty FullName
javac -cp "lib\commons-lang3-3.20.0.jar;lib\opencsv-5.12.0.jar" -d bin $sources

$testSources = Get-ChildItem -Recurse -Path tests -Filter *.java | Select-Object -ExpandProperty FullName
javac -cp "bin;lib\commons-lang3-3.20.0.jar;lib\opencsv-5.12.0.jar;lib\junit-platform-console-standalone-1.10.2.jar" -d test-bin $testSources

java -jar "lib\junit-platform-console-standalone-1.10.2.jar" --class-path "bin;test-bin;lib\commons-lang3-3.20.0.jar;lib\opencsv-5.12.0.jar" --scan-class-path
```

## Scheduling Behavior

The scheduler logic lives in `src/Scheduler/Scheduler.java`.

Dispatch selection is implementation-backed and currently works as follows:

1. Prefer the highest severity fire.
2. If severity ties, prefer the drone/mission pairing with the shorter estimated travel time.
3. If travel time also ties, prefer the drone with fewer assigned missions so far.
4. If those still tie, prefer the older fire request.

Additional Iteration 3 behavior:

- The scheduler stores the latest drone position from status updates and uses that position in travel-time and reroute decisions.
- A drone already `EN_ROUTE` can be rerouted to a newly reported higher-severity fire.
- A drone already `EN_ROUTE` can also be rerouted to a same-severity fire when the new zone lies earlier on its current path.
- Before dispatching, the scheduler checks whether the drone has enough battery to reach the target and then return home with a safety margin.
- If a pending mission cannot be served safely with the drone's current remaining resources, the scheduler sends that drone back to base first.

## GUI Compatibility

Iteration 3 keeps the GUI launch path in `src/Main.java` intact.

Compatibility is also covered by `tests/scheduler/SchedulerGuiCompatibilityTest.java`, which verifies that:

- `getActiveFires()` still reflects mission progress
- `getDroneState()` still exposes a GUI-readable state during and after dispatch

Practical demo note:

- the GUI is a same-process compatibility/demo path
- the separate-process UDP launchers are the Iteration 3 distributed path

## Demo Notes

Short TA-friendly demo flow:

1. Compile from the `project` directory.
2. Start `SchedulerMain` with `--drones 2 --completionPort 5008`.
3. Start two drone processes on command ports `5003` and `5004`.
4. Start `FireIncidentSubsystemMain` with `sampleData/test_consecutive_missions.csv` to show independent drone processes and split work.
5. Repeat with `sampleData/test_mixed_scenario.csv` to show severity-aware scheduling over time.
6. Launch `Main` separately to show that the GUI path still works.

For the explicit reroute cases, the clearest references are the scheduler tests:

- `tests/scheduler/SchedulerReroutePolicyTest.java`
- `tests/scheduler/SchedulerWaitingTimeOptimizationTest.java`

## Project Structure

- `src/`: application source code
- `src/Scheduler/`: scheduler state machine and UDP launcher
- `src/Drone_subsystem/`: drone model, execution engine, subsystem wrappers, zone loader
- `src/fire_incident_subsystem/`: fire CSV reader, fire requests, UDP launcher
- `src/gui/`: Swing GUI
- `tests/`: JUnit 5 tests for Iteration 3 behavior
- `sampleData/`: sample zone map and incident CSVs
- `lib/`: third-party jars currently used for compilation
- `docs/`: diagrams and notes

## Assumptions or Notes

- The scheduler assumes one active fire per zone at a time. A duplicate request for a zone that is already queued or in progress is ignored and acknowledged as unresolved.
- High-severity fires require `30.0L` of suppressant (`Severity.HIGH`), so with the default drone capacity of `12.5L`, a mission can require multiple trips to base.
- In GUI mode, multiple drones can run, but the GUI exposes compatibility-oriented status displays rather than a per-drone dashboard.
- The repository currently includes `commons-lang3` and `opencsv` jars, but not the JUnit console jar needed for command-line test execution.
