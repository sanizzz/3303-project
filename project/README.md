# Firefighting Drone Swarm (SYSC 3033A / 3303A) - Iteration 2

## Project Overview

The Firefighting Drone Swarm system models coordinated wildfire response using a simulated drone-based architecture.

The specification defines three required subsystems:

- Scheduler subsystem
- Drone subsystem
- Fire Incident subsystem

The system is expected to operate as a real-time simulation, with behavior driven by incident timing and configurable runtime parameters.

## System Architecture

The specification defines three logical components with distinct responsibilities:

- Scheduler: receives incident information and makes dispatch decisions.
- Drone subsystem: executes dispatches and reports operational updates.
- Fire Incident subsystem: provides fire events to the system.

Architecture requirements in the specification include:

- Each component should execute as a separate process.
- Inter-process communication is expected to use UDP datagrams (`DatagramSocket`).

## Iteration 2 Objectives

- Implement core scheduling logic for a single-drone scenario.
- Implement required drone state transitions.
- Maintain compatibility with future scaling to multi-drone operation.
- Update the GUI to track drone states and the number of active fires.

## Iteration 2 Functional Requirements

- Iteration 2 assumes a single drone.
- The Scheduler receives fire requests and determines dispatching decisions.
- The drone notifies the Scheduler when it arrives at a fire location.
- After agent drop, the drone provides status updates needed for Scheduler decisions, including remaining agent and battery/travel feasibility.
- The Scheduler determines whether the drone should service the next task or return to base.
- Refill/recharge at base is treated as instantaneous.
- The system assumes one active fire per zone at a time.

## Iteration 2 Requirement 

- R1: Single-drone operation.
- R2: Scheduler accepts fire requests and dispatches missions.
- R3: Drone arrival and status transitions are reported to Scheduler.
- R4: Scheduler decisions use remaining battery and agent.
- R5: Scheduler decides continue-next-mission vs return-to-base.
- R6: Refill/recharge at base is instantaneous.
- R7: One active fire per zone at a time.
- R8: GUI tracking includes drone state and active fire count.

## Iteration 2 Test Cases and Requirement Coverage

Test class: `tests/java/Iteration2SpecificationTest.java`

## State Machines Required

Iteration 2 deliverables require:

- A Scheduler state machine diagram.
- A Drone subsystem state machine diagram.
- Sequence diagrams.
- A UML class diagram.

## GUI Requirements (Iteration 2)

For Iteration 2, the GUI must:

- Track drone states.
- Display the number of active fires.

## Work Products for Iteration 2

- README.txt
- Breakdown of responsibilities
- UML class diagram
- Sequence diagrams
- State machine diagrams
- Detailed setup and test instructions
- Source code (`.java` and IntelliJ project files)

## TA Setup Instructions



### Prerequisites

- JDK 11 or newer (`java` and `javac` on `PATH`)

### 1. Open project folder

```powershell
cd "C:\Users\sanid\OneDrive\Desktop\3303 project\project"
```

### 2. Compile source

```powershell
$sources = Get-ChildItem -Recurse -Path src -Filter *.java | Select-Object -ExpandProperty FullName
javac -cp "lib\commons-lang3-3.20.0.jar;lib\opencsv-5.12.0.jar" -d bin $sources
```

### 3. Run option A: single-process GUI mode

```powershell
java -cp "bin;lib\commons-lang3-3.20.0.jar;lib\opencsv-5.12.0.jar" Main
```

In the GUI:
- Click `Load CSV` and choose a CSV from `sampleData`.
- Click `Start`.

### 4. Run option B: UDP multi-process mode (3 terminals)

Terminal 1 (Scheduler):

```powershell
java -cp "bin;lib\commons-lang3-3.20.0.jar;lib\opencsv-5.12.0.jar" Scheduler.SchedulerMain --zoneCsv sampleData/sample_zone_file.csv --drones 1 --firePort 5001 --droneStatusPort 5002 --droneHost localhost --droneCommandPort 5003 --fireHost localhost --completionPort 5004
```

Terminal 2 (Drone Subsystem):

```powershell
java -cp "bin;lib\commons-lang3-3.20.0.jar;lib\opencsv-5.12.0.jar" Drone_subsystem.DroneSubsystemMain --droneId 1 --commandPort 5003 --schedulerHost localhost --schedulerStatusPort 5002 --zoneCsv sampleData/sample_zone_file.csv --timeScale 1
```

Terminal 3 (Fire Incident Subsystem):

```powershell
java -cp "bin;lib\commons-lang3-3.20.0.jar;lib\opencsv-5.12.0.jar" fire_incident_subsystem.FireIncidentSubsystemMain --csv sampleData/test_mixed_scenario.csv --schedulerHost localhost --schedulerPort 5001 --completionPort 5004 --timeScale 1
```

### 5. Optional: run JUnit tests

Place `junit-platform-console-standalone-1.10.2.jar` in `lib/`, then run:

```powershell
$testSources = Get-ChildItem -Path tests\java -Filter *.java | Select-Object -ExpandProperty FullName
javac -cp "bin;lib\commons-lang3-3.20.0.jar;lib\opencsv-5.12.0.jar;lib\junit-platform-console-standalone-1.10.2.jar" -d test-bin $testSources
java -jar "lib\junit-platform-console-standalone-1.10.2.jar" --class-path "bin;test-bin;lib\commons-lang3-3.20.0.jar;lib\opencsv-5.12.0.jar" --scan-class-path
```

## Assumptions Explicitly Stated in Spec

- Single drone for Iteration 2
- Instant refill/recharge at base
- One fire per zone at a time
- CSV-driven fire incident input
