# Quantified API Update Changelogs 

## **No changes needed for mod authors**  

## v1.2.0 - Released on 2026-02-12  

### What’s new / big stuff

- Full dashboard redesign. It’s way cleaner now. More calm SaaS vibe, better structure, less clutter.
- Massive async/OpenCL backend improvements for throughput + stability.
- Added runtime auto-tuning + guardrails so performance adapts automatically under load.
- No API changes. Everything stays compatible.
- Logo's colour scheme was changed to a more simplistic and minimalistic look.

---

### Added

- New adaptive batching system internally:
  - AutoBatchController.java
- New runtime performance tuner:
  - RuntimeAutoTuner.java
- Proper stress + soak + tuner test coverage:
  - RuntimeAutoTunerTest.java
  - AutoBatchControllerTest.java
  - StressSoakBenchmarkSuite.java
- New Gradle benchmark task:
  - :forge-1.20.1:benchmarkSoak

Basically: smarter batching, smarter scaling, actually tested under pressure.

---

### Changed (internals got smarter)

- PriorityScheduler
  - Now makes adaptive batching decisions based on live queue/load/latency signals.
  - Drops stale background work if the system is under pressure.
  - Applies runtime tuning continuously during housekeeping.

- DynamicThreadScaler
  - Supports runtime-tuned throttle penalties.
  - Boosts scaling when load is healthy.

- TaskScheduler
  - Lower-overhead GPU fallback path.
  - GPU utilization threshold + batch targets can now be runtime tuned.

- AsyncManager
  - Cleans request maps aggressively on complete/timeout/reject/prune.
  - Less internal buildup, less long-term waste.

- OpenCLTaskManager
  - Switched to lower-overhead bounded event history tracking.

In short: less wasted compute, better scaling, more stable under stress.

---

### Dashboard / Web Panel

Major polish pass.

- Reworked entire layout shell.
- Cleaner spacing, cleaner cards, better structure.
- Removed useless widgets.
- Reworked main tabs:
  - Overview  
  - Resources  
  - Logs  
  - Controls  
  - Config  
  - System  

- Charts + legends are clearer.
- Panel hierarchy makes more sense.
- Config UI is more organized and denser (in a good way).
- Smoother tab transitions.
- Tons of small UX improvements everywhere.

It just feels more finished now.

---

### Notes

- No mod author code changes required.
- No public mod-facing contract changes.
- This is purely performance, tuning, stability, and dashboard refinement.
