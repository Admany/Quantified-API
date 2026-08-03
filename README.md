<div style="font-family:'JetBrains Mono','Courier New',monospace;color:#e7e7e7;background:#151515;padding:30px;border-radius:16px;line-height:1.65;max-width:900px">

<h1 style="text-align:center;color:#ffffff;margin:0 0 20px 0">《▓ Quantified API ▓》</h1>

<p style="text-align:center;margin-bottom:20px">
  <a href="https://github.com/Admany/Quantified-API" rel="nofollow">
    <img src="https://i.imghippo.com/files/fC4894ILw.png" alt="Quantified API Banner" style="max-width:100%;border-radius:10px">
  </a>
</p>

<p>
Quantified API, or QAPI, is a performance framework for mods that do a bit more than just slap random async on stuff and pray it doesn't implode xd
</p>

<p>
It's goal is pretty simple:
stop mods from frying the main thread and give people one API that can route work properly across CPU, caching, and GPU backends without breaking the game with deadlocks.
V2 is a large rework - CPU internals, the public API surface, and caching were all heavily redesigned, and official docs are finally here :]
</p>

<div style="border:1px solid #8e24aa;padding:20px;margin-top:20px;background:rgba(142,36,170,0.08);border-radius:10px">
<p style="font-size:18px;color:#f0c6ff;margin-top:0">《▒ What it actually does ▒》</p>
<ul style="list-style-type:'→ ';padding-left:20px;color:#e1bee7">
  <li><b>Proper async scheduling</b> with worker lanes, telemetry, sane fallback behavior, and less <b>Ehhh, why is my game suddenly at 5 tps</b> xd</li>
  <li><b>DAG / task graph execution</b> so dependency-aware workflows don't need to be wired by hand (I 100% recommend doing it, so fun)</li>
  <li><b>Backend-aware compute routing</b> through <code>QuantifiedAPI.compute(...)</code> with CPU, Vulkan, and OpenCL preferences</li>
  <li><b>Parallel execution</b> via <code>QuantifiedAPI.parallel(...)</code> with much lower scheduler overhead on micro and medium tasks in V2</li>
  <li><b>GPU acceleration when it actually makes sense</b>, not fake "gpu preferred" branding slapped onto CPU work as on other mods</li>
  <li><b>Caching + persistence layers</b> via <code>QuantifiedAPI.cache(...)</code> with TTL, compression, async refresh, and disk/memory modes - so repeated expensive work doesn't repeat xd</li>
  <li><b>Live diagnostics</b> through the Quantified webpanel and runtime telemetry</li>
</ul>
</div>

<div style="border:1px solid #5e35b1;padding:20px;margin-top:20px;border-radius:10px">
<p style="font-size:18px;color:#d1c4e9;margin-top:0">《▒ Vulkan GPU Acceleration/OpenCL Acceleration ▒》</p>
<p>
Quantified API is the first Minecraft mod / API setup with Vulkan compute acceleration that simply <b>works</b>, without any configuration required.
Vulkan is the preferred GPU backend, while OpenCL is still kept around as the fallback / legacy path for compatibility.
</p>
<p>
That said, Vulkan under Minecraft + Forge + LWJGL can be a complete pain, so to counter that Quantified API is built around isolated probing, deferred runtime init, explicit failure reasons, and proper fallbacks.
V2 cleaned up GPU routing further - the backend preference API is easier to use, and some Vulkan paths that were no longer useful got removed.
</p>
<ul style="list-style-type:'• ';padding-left:20px;color:#c5cae9">
  <li><b>Isolated Vulkan probing</b> so a corrupted driver or a loader issue doesn't instantly brick the game or cause a crash</li>
  <li><b>Explicit fallback routing</b> Vulkan falls to OpenCL if Vulkan isn't available, or is misbehaving. Worst case if both decide to act up, everything heads to the CPU without you even noticing :DDD</li>
  <li><b>Backend diagnostics</b> so you can see probe state, runtime state, active backend, chosen device, and failure reason without digging through 9 years of log spam (Modded logs xd)</li>
  <li><b>Custom SPIR-V compute</b> through <code>SpirvComputeProgram</code> and <code>QuantifiedVulkan.Context.dispatch(...)</code>, with validated modules, bounded pipeline caching, reusable workspaces, isolated-runtime support, and normal CPU fallback routing</li>
</ul>
</div>

<div style="border:1px solid #2e7d32;padding:20px;margin-top:20px;background:rgba(46,125,50,0.08);border-radius:10px">
<p style="font-size:18px;color:#c8e6c9;margin-top:0">《▒ Public API surface ▒》</p>
<p>
The V2 API surface was cleaned up and expanded with a builder-first style, so compute, parallel, graph, and cache flows are obvious without writing cursed boilerplate everywhere (GeckoLib's API has to be studied in my opinion xd).
Full API docs, examples, and migration info from V1 are at <a href="https://admany.dev" style="color:#81c784">admany.dev</a>.
</p>
<ul style="list-style-type:'→ ';padding-left:20px;color:#c8e6c9">
  <li><code>QuantifiedAPI.compute(...)</code> for backend-aware compute submission</li>
  <li><code>QuantifiedAPI.parallel(...)</code> for parallel execution with lower orchestration overhead</li>
  <li><code>QuantifiedAPI.vulkan(...)</code> for explicit Vulkan-targeted workloads</li>
  <li><code>SpirvComputeProgram.fromResource(...)</code> for precompiled custom Vulkan kernels without runtime shader compilation</li>
  <li><code>QuantifiedAPI.graph(...)</code> for DAG / dependency-aware flows</li>
  <li><code>QuantifiedAPI.cache(...)</code> for caching with TTL, compression, async refresh, and persistence controls</li>
  <li>Backend preferences like <code>preferGpu()</code>, <code>preferVulkan()</code>, <code>preferOpenCL()</code>, <code>requireVulkan()</code>, and <code>cpuOnly()</code></li>
</ul>
<p style="margin-bottom:0">
If your mod is doing worldgen prep, simulation, batching, heavy math, cache-heavy systems, or other compute nonsense (Dancing cockroaches :DDD),
Quantified API gives you a proper runtime instead of forcing you to reinvent multithreading for the 40th time, and then fix each compatibility issue, every <b>FREAKING</b> time...
</p>
</div>

<div style="border:1px solid #00838f;padding:20px;margin-top:20px;background:rgba(0,131,143,0.08);border-radius:10px">
<p style="font-size:18px;color:#b2ebf2;margin-top:0">《▒ V2 Performance ▒》</p>
<p>
V2 reworked the CPU runtime internals from scratch. Here's what it actually benchmarks to against V1.4.4:
</p>
<ul style="list-style-type:'→ ';padding-left:20px;color:#b2ebf2">
  <li><b>Tiny unique burst:</b> 247ms → 62ms - roughly <b>3.95x faster</b></li>
  <li><b>Duplicate burst:</b> 173ms → 3.8ms - roughly <b>45x faster</b></li>
  <li><b>Parallel micro:</b> 3.1ms → 0.56ms - roughly <b>5.5x faster</b></li>
  <li><b>Parallel medium:</b> 10.6ms → 1.16ms - roughly <b>9.2x faster</b></li>
  <li><b>DAG micro:</b> 2958ms → 1ms - roughly <b>2952x faster</b> on tested micrographs xd</li>
</ul>
<p>
Duplicate execution suppression also went from 2048 real executions (V1.4.4) down to <b>1</b> in V2. Not a typo.
</p>
</div>

<div style="border:1px solid #ef6c00;padding:20px;margin-top:20px;border-radius:10px">
<p style="font-size:18px;color:#ffcc80;margin-top:0">《▒ Quantified Webpanel ▒》</p>
<p>
Quantified API from day one ships with a webpanel, which is basically there so you can see what it's doing in realtime.
</p>
<p>
You can enable it through config or with <code>/quantified webpanel</code>.
</p>
<ul style="list-style-type:'• ';padding-left:20px;color:#ffe0b2">
  <li><b>Runtime overview</b> for scheduler activity, queue pressure, backend state, mod usage and so etc</li>
  <li><b>GPU controls</b> for backend preference and device selection (Dual 5090 users will love this xd)</li>
  <li><b>Diagnostics export</b> so debugging weird behavior is easier for me, and you :]</li>
  <li><b>Optional auth / HTTPS stuff</b> if you want it a bit less jank when exposing it beyond localhost</li>
</ul>
</div>

<div style="border:1px solid #1565c0;padding:20px;margin-top:20px;background:rgba(21,101,192,0.08);border-radius:10px">
<p style="font-size:18px;color:#90caf9;margin-top:0">《▒ Compatibility ▒》</p>
<ul style="list-style-type:'→ ';padding-left:20px;color:#bbdefb">
  <li>Vulkan is optional. If it works, W. If not, it'll degrade cleanly instead of becoming a startup bomb</li>
  <li>OpenCL support still exists for compatibility, but Vulkan is the main direction due to its large advantages over OpenCL</li>
  <li>V2 adds auto-registration support for Fabric, Forge, and NeoForge through loader metadata detection - manual registration still works as a legacy option</li>
  <li>If your hardware doesn't support Vulkan, it as mentioned switches to OpenCL. If even OpenCL isn't supported... how are you running the game then???</li>
</ul>
</div>

<div style="border:1px solid #37474f;padding:20px;margin-top:20px;background:rgba(55,71,79,0.08);border-radius:10px">
<p style="font-size:18px;color:#cfd8dc;margin-top:0">《▒ Documentation ▒》</p>
<p style="margin-bottom:0">
Official V2 docs are live at <a href="https://admany.dev" style="color:#81d4fa">admany.dev</a> - covers getting started, the full V2 API, compute, parallel, graph, caching, keys/affinity, troubleshooting, examples, LC2H examples, and V1 migration info :]
</p>
</div>

<div style="border:1px solid #8d6e63;padding:20px;margin-top:20px;border-radius:10px">
<p style="font-size:18px;color:#d7ccc8;margin-top:0">《▒ Licensing and support ▒》</p>
<p>
Licensed under BRSSLA V1.5 which is free for personal and non commercial use.
</p>
<p style="color:#d7ccc8;font-weight:bold">
Mod Developers who include Quantified API as a dependency automatically gain Commercial Rights for their mod.
</p>
<p style="margin-bottom:0">
It's built to be flexible, easy to integrate and to be as user friendly as possible, while being flexible. If you have any requests, ideas, or found a bug, feel free to open a github issue or even a merge request :]
</p>
</div>

<p style="text-align:center;font-size:12px;color:#8a8a8a;margin-top:30px">
Developed and Maintained by Admany - BlackRift Studios 2026
</p>

</div>
