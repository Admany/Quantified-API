# Quantified API Update Changelogs 

## V2 is here, with large changes, and FINALLY Official Documentation at `https://admany.dev` :]

## v2.0.0 - Released on 2026-05-18

### Quick summary :]

- Quantified API V2 is a large release. The CPU runtime internals were heavily reworked so tiny tasks, duplicate bursts, parallel work, and DAG execution tasks are way cheaper to compute.
- The public API was cleaned up around the new V2 builder first style, so compute, parallel, graph, and cache flows are more obvious and cleaner then V1.
- Auto-register support is now built in for Fabric, Forge, and NeoForge through loader metadata detection (Manual registering was kept as a Legacy option).
- The caching system was upgraded.
- GPU routing is now cleaner, Vulkan junk that was no longer useful got removed, and backend preference API options are easier to use.
- Official Quantified API docs are here! Can be found at `https://admany.dev` and it includes API docs, examples, migration info, and LC2H code examples.
- Benchmark tool was added to compare V2 VS `v1.4.4`. V2 wins by a BIG margin. 

---

### Added

- New V2 public API lanes:
  - `QuantifiedAPI.compute(...)`
  - `QuantifiedAPI.parallel(...)`
  - `QuantifiedAPI.graph(...)`
  - `QuantifiedAPI.cache(...)`
- New/improved compute controls:
  - `key(String)`
  - `key(long)`
  - `affinity(String)`
  - `threadSafe()`
  - `notThreadSafe()`
  - `fallback(...)`
  - `dataSizeBytes(...)`
  - `parallelUnits(...)`
  - `complexity(...)`
  - `kind(...)`
  - `allowMainThreadRerouting(...)`
- New/improved backend routing controls:
  - `preferGpu()`
  - `preferVulkan()`
  - `preferOpenCL()`
  - `requireVulkan()`
  - `requireOpenCL()`
  - `cpuOnly()`
- New/improved cache builder surface:
  - `get(...)`
  - `getAsync(...)`
  - `prefetch(...)`
  - `refresh(...)`
  - `refreshAsync(...)`
  - `put(...)`
  - `contains(...)`
  - `remove(...)`
  - `clear()`
  - `ttl(...)`
  - `maxEntries(...)`
  - `persistent()`
  - `diskPreferred()`
  - `ephemeral()`
  - `memoryOnly()`
  - `compression(...)`
  - `compressed()`
  - `refreshOnAccess()`
  - `fixedTtl()`
- New/improved loader metadata support:
  - Fabric auto-register support
  - Forge metadata resolution
  - NeoForge metadata resolution
  - caller-based auto-detection on first API call

---

### What changed

- CPU runtime internals were heavily redesigned:
  - lower submission overhead on tiny unique tasks
  - much better duplicate burst suppression
  - lower orchestration cost for parallel work
  - dramatically cheaper tiny DAG execution cost
- The unique task path now has a real fast path internally instead of being taxed by duplicate/coalescing machinery it doesn't even need xd.
- Duplicate burst handling was fixed and tightened so explicit duplicate storms can collapse into a single real execution instead of exploding into a whole big puddle.
- Task graph execution was redone and made much cheaper for small-node and wavefront-style graphs (2952x better performance then V1.4.4).
- Parallel execution internals were cleaned up so scheduler overhead hurts less on micro and medium tasks.
- Caching internals were improved and hardened:
  - better persistent cache disk behavior
  - cleaner save/load handling
  - better async/persistent lifecycle handling
- GPU routing was cleaned up more:
  - clearer backend prefferencing
  - better Vulkan/OpenCL preference API
  - removed the Vulkan MC density path fully (useless)
- Auto-registration now resolves:
  - mod id
  - display name
  - version
  - all from loader metadata when possible (worst case depends on manual LEGACY registration)

---

### Benchie results

Jar-vs-jar runtime comparison against `quantified api-omni-1.4.4.jar`:

- Tiny unique burst:
  - `247.848 ms -> 62.665 ms`
  - roughly `3.95x` faster
- Duplicate burst:
  - `173.335 ms -> 3.815 ms`
  - roughly `45.4x` faster
- Parallel micro:
  - `3.103 ms -> 0.560 ms`
  - roughly `5.54x` faster
- Parallel medium:
  - `10.623 ms -> 1.160 ms`
  - roughly `9.16x` faster
- DAG micro:
  - `2958.849 ms -> 1.002 ms`
  - roughly `2952x` faster on the tested micrograph setup

Duplicate execution suppression also improved hard:

- `v1.4.4`: `2048` real executions
- earlier `V2` pass: `3`
- latest `V2` pass: `1`

V2 reduces the amount of real work time, latency, and how long it takes to process a task by a LOT.

---

### Docs / site release

- Official Quantified API docs are now live at:
  - `https://admany.dev`
- The docs now include:
  - getting started
  - full V2 API docs
  - compute docs
  - parallel docs
  - graph / DAG docs
  - caching docs
  - keys / affinity docs
  - troubleshooting
  - examples
  - LC2H V2 examples
  - migration info from V1 API

---

## v1.4.0 - Released on 2026-04-02

### Quick summary :]

- Vulkan has been added as a new GPU acceleration backend alongside OpenCL (OpenCL is now legacy, but I'll keep it for compatability reasons xd).
- DAG execution was added so mods can submit dependency aware async workflows aka less clutter and makes performance go brrrrrr.
- New public APIs, `QuantifiedCompute` and `QuantifiedVulkan`, allow ya to target CPU, OpenCL, Vulkan, or backend stuff from one interface.
- The dashboard has been updated to fit new config options for Vulkan (and some UI elements).
- GPU routing was tightened, because there was a bug, where GPU tasks went through the CPU... well that's not surprising XD

---

### Added

- New Vulkan public API:
  - `QuantifiedAPI.vulkan(...)`
  - `QuantifiedVulkan`
  - Vulkan helpers for vector addition, matrix multiplication, Monte Carlo Pi and so on
- New backend agnostic compute builder:
  - `QuantifiedAPI.compute(...)`
  - `QuantifiedCompute`
  - backend preference options like `preferVulkan()`, `preferOpenCL()`, `requireVulkan()`, `requireOpenCL()`, and `cpuOnly()`
- New DAG/task graph API:
  - `QuantifiedAPI.graph(...)`
  - `QuantifiedTaskGraph`
  - graph submission helpers for only terminal node or full graph execution (depends on the task)
- New Vulkan backend routing and runtime surfaces:
  - mod backend preference routing
  - preferred GPU device selection (only on the webpanel)
  - explicit Vulkan/OpenCL backend types and preferences (also webpanel)
  - subprocess Vulkan probe path (because MC doesn't like me when I change thread mem sizes :L )
- New build/runtime support for Vulkan:
  - precompiled SPIR-V resources (less wait, and also makes the API smaller)
  - embedded Vulkan probe helper (Read up xd)
  - shader compiler pipeline in Forge 1.20.1 resources/build (Tbh nobody uses it on older versions xd)
- New benchmark and test coverage:
  - Vulkan vs OpenCL backend comparison benchmark (purely to see if vulkan is faster then OpenCL)
  - Vulkan utility/runtime tests (to ensure it actually worky)
  - task graph tests (to ensure DAG is DAGGING)
  - backend router tests (to make sure that GPU work goes to the GPU, and NOT the CPU xd)

---

### What changed

- `TaskScheduler`, `GpuTaskDispatcher`, so GPU work goes to the GPU rather to the CPU
- Vulkan runtime now supports:
  - real device probing (no false or imaginary devices xd)
  - fallback-aware runtime status reporting (mostly for debug)
  - delayed first-use initialization (sometimes LWGJL isn't ready instantly)
  - multi-workspace execution instead of a single fixed submit/wait loop (allows to run multiple Vulkan GPU acceleration tasks at once, rather one at a time)
- The Vulkan terrain path now returns a compact multi field summary per chunk. (I aint explaining xd)
- OpenCL VRAM saturation handling now unloads caches and trims buffers instead of nuking the probe and handler....
- The dashboard and web panel configuration was expanded with:
  - backend preference controls (Vulkannn)
  - Vulkan/OpenCL device selectors 
  - active backend display in the overview (So yk if you're using VK or CL)
  - richer GPU/Vulkan diagnostic information (so debugging is EASY)
- Runtime logging has been significantly cleaned up. Vulkan probe failures, missing bindings, and backend fallbacks are easy to debug (Vulkan likes to be quite the a**hole)

---

### Stability/compatibility notes

- Vulkan is optional. If Vulkan just decides to not work for some reason, it tries to fallback to OpenCL and if even that isn't available to the CPU.
- Existing OpenCL integrations still work (no torture for anyone xd). I recommend using DAG tho.
- Vulkan shader compilation no longer relies on runtime `shaderc`. Shaders are precompiled into SPIR-V during the build of the jar (smaller jar sizes yk)
- The runtime now avoids several LWJGL and Vulkan initialization issues:
  - duplicate probe messages spamming logs
  - missing probe diagnostics (debugging would be hell without them)
  - misleading "probe not yet run" fallback states (fun stuff)

## v1.3.0 - Released on 2026-03-07  

### Quick summary

- Persistent cache CPU usage is much lower now under real load.
- Cache persistence is now opt-in by default instead of silently enabled everywhere.
- Disk saves are debounced, so cache-heavy mods stop wasting CPU on constant rewrite churn.
- Fixed an OpenCL batch execution bug where successful GPU batches could still fail to complete caller futures correctly.
- Added much better GPU batch / fallback telemetry so it is easier to see why GPU paths are or are not being used.

---

### What changed

- `QuantifiedAPI.getCached(...)`, `getCachedAsync(...)`, and `putCached(...)` now default to non-persistent cache usage unless persistence is explicitly requested.
- Persistent cache writes are now coalesced/debounced instead of saving on every single mutation.
- Removed the old expensive per-entry trial serialization path during disk saves.
- Improved persistent cache lifecycle handling:
  - file lock references are cleaned up properly
  - timed-out async hydrate now logs a warning
  - disk hydrate precedence is now explicitly documented in code/logging
- Fixed GPU batch success flow so completed GPU work actually propagates results back to waiting task futures.
- Added detailed GPU batch telemetry counters for:
  - missing metadata
  - not batchable
  - not GPU-marked
  - thermal rejection
  - dispatcher unavailable fallback
  - no-workload fallback
  - execution failure fallback
  - direct GPU throttle/capacity/cooldown rejections

---

### Real-world result

- In monitored runs, `quantified-cache-io` dropped from being one of the top CPU-heavy threads to not showing up in the top thread list at all.
- One compared run improved from roughly **587 CPM** to **753 CPM** after the broader LC2H + Quantified API cleanup pass.
- This update does not add API breakage for mod authors. It is mainly an internal efficiency/stability pass.

---

## v1.2.2 - Released on 2026-02-22  

### Quick summary

- Startup RAM spikes are lower now.
- OpenCL startup is fully deferred/background (no forced sync probe on boot).
- Persistent cache loading is now async, so cache construction no longer blocks startup.
- Added async cache API path: `QuantifiedAPI.getCachedAsync(...)`.
- Existing `getCached(...)` path is unchanged. No API breaks.

---

### What changed

- Removed eager startup OpenCL probe path from Forge bootstrap.
- Removed eager default cache registrations that were allocating memory too early.
- Cache disk-usage snapshot no longer does a blocking first scan.
- Added async cache miss compute path in `QuantifiedHandle` + API overloads in `QuantifiedAPI`.
- Added async hydrate path for persistent cache files.

---

### Notes

- First seconds after launch may run CPU path until GPU probe completes (expected).
- Existing mods do not need code changes.

---

## v1.2.1 - Released on 2026-02-17  

### Quick summary

- Patched a scheduler stall issue where `quantified-fg` workers could sit in sleep loops under high load.
- Workers now always try to pull queued work first, then back off only when queues are empty.
- Interrupt handling was cleaned up so workers do not get stuck in a bad interrupted state.
- No API changes. Fully compatible update.

---

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
