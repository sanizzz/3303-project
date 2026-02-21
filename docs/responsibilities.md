# Subsystem Responsibilities (Iteration 2)

## Fire Incident Subsystem
- Read incident events from CSV.
- Emit fire requests (zone, type, severity) to Scheduler.
- Receive mission completion acknowledgments.

## Scheduler
- Maintain mission queue (FIFO).
- Dispatch missions to available drone(s).
- Process drone status updates (arrival, drop progress, completion, return).
- Decide dispatch vs return-to-base based on remaining agent and battery feasibility.

## Drone Subsystem
- Execute dispatched missions using drone state machine.
- Simulate travel and agent drop timing.
- Send status updates to Scheduler at key transitions.
- Return to base and refill/recharge instantly when commanded or when tank is empty.

## GUI
- Display map and zone state colors.
- Display drone state.
- Display active fire count.
- Display simulation log.
