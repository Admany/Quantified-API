# Quantified API Update Changelogs 

## v2.2.1

- Fixed isolated OpenCL execution when the game already loaded LWJGL through another classloader.
- Added single flight OpenCL startup and a dedicated runtime thread so concurrent GPU requests do not duplicate native loading or stall the game.
- Routed API OpenCL workloads through the isolated runtime when the in process binding is unavailable.
- Fixed GPU status, dashboard, overlay, command, and health reporting so an executable isolated runtime is shown as active.

## v2.2.0

- Fixed Linux GPU acceleration by packaging the Linux x64 and ARM64 LWJGL natives inside the isolated Vulkan and OpenCL runtimes.
- GPU probe bundles are now cross-platform instead of silently inheriting the operating system used to build the release jar.
- Isolated Vulkan now performs a real background runtime preflight after device detection and reports the exact activation failure instead of stopping at probe success.
- Added an omni-jar verification task so Windows, Linux x64, and Linux ARM64 GPU natives cannot disappear from a release unnoticed.
- Dedicated servers no longer touch Minecraft client classes from dashboard polling; Vulkan and OpenCL compute now initialise independently of the client renderer.
- OpenCL now preflights and activates its isolated bundled runtime on Linux instead of stopping after device discovery, with exact context and native failures exposed in diagnostics.

- Restored the custom Vulkan shader lane that was lost during the V2 rewrite, now as the cleaner `SpirvComputeProgram` API.
- Mods can package precompiled SPIR-V and dispatch it through `QuantifiedVulkan.Context.dispatch(...)` on both normal and isolated Vulkan runtimes.
- Custom pipelines are content-addressed, bounded, cached for reuse, and share Quantified API's existing workspace/residency system instead of rebuilding Vulkan objects per call.
- Added strict shader, descriptor, push-constant, dispatch-size, and VRAM-footprint validation plus focused API tests.

## V2 is here, with large changes, and FINALLY Official Documentation at `https://www.admany.dev` :]

## v2.0.0 - Released on 2026-05-18 (The TRUE V2 Release xd)

### Quick summary :]

- Quantified API V2 is now the TRUE V2 release, with the new API, runtime rework, platform restructure, GPU backend fixes, loader support, compatibility system, docs, and omni packaging all finished in one proper release.
- The CPU runtime internals were heavily reworked so tiny tasks, duplicate bursts, parallel work, and DAG execution tasks are way cheaper to compute.
- The public API was cleaned up around the new V2 builder first style, so compute, parallel, graph, and cache flows are more obvious and cleaner then V1.
- The old flat platform layout was fully replaced with a loader first structure under `platforms/fabric`, `platforms/forge`, and `platforms/neoforge`.
- Support was added for Fabric, Forge, and NeoForge from Minecraft `1.20.1`, `1.21.X`, all the way to `1.26.X`.
- The platform and compatibility systems were updated so mods depending on the V1 API can still work under V2.
- Vulkan and OpenCL runtime handling was heavily reworked so probing, initialization, execution, routing, and fallback behavior is much more stable.
- The root Gradle and omni build system was updated to work with the new platform split and generate the merged omni jar correctly again.
- Auto-register support is built in for Fabric, Forge, and NeoForge through loader metadata detection (Manual registering was kept as a Legacy option).
- The caching system was upgraded.
- Official Quantified API docs are here! Can be found at `https://admany.dev`, with the docs source and even more updated info at `https://github.com/Admany/Quantified-Docs`.
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
  - caller based auto-detection on first API call
- New platform layout:
  - `platforms/fabric`
  - `platforms/forge`
  - `platforms/neoforge`
  - loader specific Minecraft version modules under each platform
- New loader/version support:
  - Fabric from `1.20.1` to `1.26.X`
  - Forge from `1.20.1` to `1.26.X`
  - NeoForge from `1.20.1` to `1.26.X`
- V1 API compatibility support under the new V2 platform system.

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
- Auto-registration now resolves:
  - mod id
  - display name
  - version
  - all from loader metadata when possible (worst case depends on manual LEGACY registration)

---

### Platform layout / build rework

- Replaced the old flat platform layout with the new loader first structure under:
  - `platforms/fabric`
  - `platforms/forge`
  - `platforms/neoforge`
- Removed stale legacy platform trees for the old `1.20.1` and `1.21.1` paths that are now replaced by the new per loader version layout.
- Reworked the root Gradle orchestration so the omni build targets the new platform directories.
- Updated the omni build so it produces the merged omni jar from the new module split.
- Updated the platform discovery and module wiring so new Minecraft versions can be added much cleaner.
- Cleaned up leftover generated wrappers, nested build junk, temporary omni extraction folders, and other repo noise that should not be in source control xd.
- Updated platform/runtime paths used by dashboard and dev tooling.
- Shrunk and cleaned bundled dashboard/runtime assets where the new packaging layout made it possible.

---

### Loader and Minecraft version support

- Quantified API now supports all 3 main mod loaders:
  - Fabric
  - Forge
  - NeoForge
- Platform modules were added/updated for Minecraft:
  - `1.20.1`
  - `1.21.X`
  - `1.26.X`
- The new loader first system is made so additional versions can be added without rebuilding the whole repo layout every single time.
- Shared code and platform bridges were cleaned up so behavior stays more consistent across loaders and versions.
- The platform compatibility system was updated so V1 API dependent mods can keep working with V2 instead of instantly exploding xd.

---

### Vulkan runtime rework

- Vulkan runtime plumbing was overhauled with a newer execution path split between:
  - probe
  - runtime availability checks
  - isolated execution
  - in-process support
- Vulkan probe, initialization, and execution behavior is now more controlled under real Minecraft startup and workload conditions.
- Added Vulkan runtime tuning and scheduler changes so initialization is less spammy and less likely to fight with the game startup.
- Runtime state handling was cleaned up so failed or half initialized Vulkan paths don't stay marked as ready.
- Vulkan task execution now falls back cleaner instead of leaving broken runtime states behind.
- Vulkan facing API hooks and common compute/task graph plumbing were updated for the newer execution model.
- Logging around Vulkan probing, startup, runtime availability, and fallback behavior was cleaned up so debugging is much less painful.
- Old useless Vulkan runtime junk and stale paths were removed.

---

### OpenCL and GPU routing rework

- OpenCL runtime handling was improved in the same pass so GPU backend routing is more consistent across loaders and Minecraft versions.
- OpenCL availability, initialization, execution, and fallback states were cleaned up.
- GPU backend routing and task dispatch behavior was updated so Vulkan and OpenCL fall back more cleanly.
- Broken or half ready GPU runtimes no longer stay selected and hold tasks hostage.
- Backend preference routing is now more consistent for:
  - `preferGpu()`
  - `preferVulkan()`
  - `preferOpenCL()`
  - `requireVulkan()`
  - `requireOpenCL()`
  - `cpuOnly()`
- If Vulkan fails, it can cleanly move to OpenCL.
- If OpenCL also isn't available, it can cleanly move to CPU without the task getting nuked.
- GPU runtime behavior is now much more aligned across Fabric, Forge, NeoForge, and all supported MC versions.

---

### V1 API compatibility

- The V2 platform and runtime systems were updated so mods made against the V1 API can still work.
- Existing mods do not have to instantly rewrite their full integration to the V2 builder system.
- Legacy/manual registration is still supported when automatic loader metadata detection cannot resolve everything.
- V1 compatibility works through the new loader first platform structure.
- New mods should still use the V2 API, but old mods are not being tortured into migrating instantly xd.

---

### Dashboard / dev tooling

- Dashboard and developer tooling paths were updated for the new platform layout.
- Runtime and platform information was updated to work across the new loader/version split.
- Vulkan/OpenCL status and backend routing diagnostics were cleaned up.
- Bundled dashboard/runtime assets were shrunk and cleaned where possible.
- Dev tooling and packaging helpers were updated so they no longer depend on the removed legacy platform paths.

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

- Official Quantified API docs are live at:
  - `https://admany.dev`
- The full docs source and latest documentation updates can be found at:
  - `https://github.com/Admany/Quantified-Docs`
- The docs now include:
  - getting started
  - installation for Fabric, Forge, and NeoForge
  - supported Minecraft versions
  - full V2 API docs
  - compute docs
  - parallel docs
  - graph / DAG docs
  - caching docs
  - keys / affinity docs
  - Vulkan and OpenCL routing
  - loader/platform information
  - V1 compatibility
  - migration info from V1 API
  - troubleshooting
  - examples
  - LC2H V2 examples
- Docs were also cleaned up and improved so the platform layout, version support, API compatibility, and GPU backend behavior actually make sense without having to read the source code like a maniac xd.

---

### Final V2 notes

- This release lands the new V2 API and CPU runtime.
- It lands the full loader first platform restructure.
- It adds Fabric, Forge, and NeoForge support from `1.20.1` to `1.26.X`.
- It keeps V1 API dependent mods working.
- It hardens Vulkan and OpenCL runtime paths.
- It fixes GPU routing and fallback behavior.
- It updates the root Gradle and omni packaging system.
- It cleans the repo and removed old platform junk.
- It updates the dashboard, runtime assets, dev tooling, and docs.

So yh, this is the TRUE Quantified API V2.0 release now xd.

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
