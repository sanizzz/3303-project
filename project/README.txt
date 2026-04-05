# Iteration 5 Timing and Metrics Notes

## Timing Instrumentation

- `project/src/Drone_subsystem/DroneExecutionEngine.java`
  - Added per-drone timing counters for `IDLE` time and airborne `EN_ROUTE` / `RETURNING` time.
  - The counters are updated inside `transitionTo(...)` and again when the engine shuts down, so each run captures the full lifetime of every drone thread.
  - These counters are exposed through `DroneSubsystem.getIdleSeconds()` and `DroneSubsystem.getFlightSeconds()` for the metrics harness.

- `project/src/Scheduler/Scheduler.java`
  - Added `waitUntilQuiescent()` and `isQuiescent()` so the metrics harness can block until the scheduler has drained incoming requests, the ready queue, in-progress work, return-to-base commands, and post-mission status traffic.
  - This prevents thread bleed between consecutive metrics samples.

- `project/src/MetricsRunner.java`
  - Wraps the full simulation in a 30-run loop.
  - For each run, it starts a fresh scheduler, fire subsystem, zone map, and exactly 10 drone threads.
  - The total processing time is measured with `System.nanoTime()` from simulation start until the scheduler reaches quiescence.
  - After each run, every thread is interrupted and joined before the next run starts.

## Confidence Interval Math

The metrics summary uses the 30 recorded total processing times:

- Sample Mean:

  `mean = (x1 + x2 + ... + xn) / n`

- Sample Standard Deviation:

  `s = sqrt( sum((xi - mean)^2) / (n - 1) )`

  The `n - 1` denominator is used because this is a sample standard deviation, not a population standard deviation.

- 95% Confidence Interval for the Mean:

  `mean +/- t * (s / sqrt(n))`

  For exactly 30 runs, the implementation uses the 95% two-tailed `t` critical value for 29 degrees of freedom:

  `t = 2.04523`

So the final interval is:

`[ mean - 2.04523 * (s / sqrt(30)), mean + 2.04523 * (s / sqrt(30)) ]`
