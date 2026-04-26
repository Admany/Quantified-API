
<div style="font-family:'JetBrains Mono','Courier New',monospace;color:#e0e0e0;background:#1b1b1b;padding:20px;border-radius:12px;line-height:1.6;">

<h1 style="text-align:center;color:#ffffff">《▓ Quantified API ▓》</h1>

<p style="text-align:center">
  <a href="https://github.com/Admany/Quantified-API"><img src="https://i.imghippo.com/files/fC4894ILw.png" alt="Quantified API Logo"></a>
</p>

<div style="border:1px solid #9c27b0;color:#e1bee7;padding:15px;margin-top:12px;background:rgba(156, 39, 176, 0.05);">
  <p>
    Say hello to Quantified API, the slick framework that takes those clunky <code>ForkJoinPool</code> setups and transforms them into a smooth, high-throughput job system. It’s designed so mods can finally play nice with one another, eliminating deadlocks and pushing main-thread tasks seamlessly onto your GPU and background cores leveraging some fancy <b>Directed Acyclic Graph (DAG) scheduling</b> with massive parallelism.
  </p>
</div>

<div style="border:1px solid #7b1fa2;color:#ce93d8;padding:15px;margin-top:12px;">
  <p style="font-size:18px;color:#ba68c8;margin-top:0;">《▒ Vulkan GPU Acceleration ▒》</p>
  <p>
    Welcome to the future: the first-ever Vulkan-based compute layer for Minecraft. Unlike other mods that just spruce up the visuals, this one fundamentally alters the way the game <i>thinks</i>.
  </p>
  <ul style="list-style-type: '→ '; padding-left: 20px;">
    <li><b>Universal:</b> Works seamlessly with NVIDIA, AMD, Intel, and even Integrated GPUs.</li>
    <li><b>Zero Setup:</b> No hassle here! It takes care of the hardware handshake automatically - just fire it up and game on.</li>
    <li><b>Auto-Adapting:</b> It recognizes your hardware and adjusts performance dynamically, saving you the trouble of tweaking those JVM args.</li>
    <li><b>Compute Fallback:</b> If Vulkan decides to be tricky, no worries - the <code>GPUMemoryManager</code> smoothly falls back to <b>OpenCL</b>, ensuring you keep that performance sweet.</li>
  </ul>
</div>

<div style="border:1px solid #6a1b9a;color:#b39ddb;padding:15px;margin-top:12px;">
  <p style="font-size:18px;color:#9575cd;margin-top:0;">《▒ Live Webpanel ▒》</p>
  <p>
    Host it yourself and access it via the config or in-game with <code>/quantified webpanel</code>. It’s your go-to live control center for all things hardware:
  </p>
  <ul style="list-style-type: '• '; padding-left: 20px;">
    <li><b>GPU Control:</b> Switch between Vulkan and OpenCL devices on the fly. It’ll automatically terminate and restart probes/kernels on the new device.</li>
</li>
    <li><b>Real-Time Metrics:</b> Take a deep dive into task queues, cache hit ratios, and hardware usage levels.</li>
    <li><b>Thread Management:</b> Get a clear view of how the <code>AsyncManager</code> is juggling tasks across your CPU cores.</li>
  </ul>
</div>

<div style="border:1px solid #4a148c;color:#d1c4e9;padding:15px;margin-top:12px;">
  <p style="font-size:18px;color:#b39ddb;margin-top:0;">《▒ Smart Tuning & The DAG System ▒》</p>
  <p>
    At the heart of the API lies the <b>DAG scheduler</b>. Instead of having tasks duel it out for CPU time, Quantified maps task dependencies to keep the main thread flowing smoothly.
  </p>
  <ul style="list-style-type: '• '; padding-left: 20px;">
    <li><b>Dynamic Optimization:</b> Keeps VRAM, RAM, CPU, and Disk Cache balanced to steer clear of thermal throttling and memory overflow.</li>
    <li><b>Pull-Based Logic:</b> The main thread doesn’t "wait" around for data: it pulls in completed results from background workers when they’re set - zero micro-stuttering.</li>
    <li><b>Multi-Tier Caching:</b> Leverages <b>L1 (RAM)</b> for quick TTL tasks, <b>L2 (Disk)</b> for massive datasets, and <b>L3 (VRAM)</b> for GPU-resident data.</li>
  </ul>
</div>

<div style="border:1px solid #2e7d32;color:#e8f5e9;padding:15px;margin-top:12px;background:rgba(46, 125, 50, 0.05);">
  <p style="font-size:18px;color:#81c784;margin-top:0;">《▒ API Docs? ▒》</p>
  <p>
    The docs are still in the lab - website is a work in progress. Coders, feel free to peek at the source: it’s likely tidier than the documentation will ever be! :L
  </p>
</div>

<div style="border:2px solid #7b1fa2;color:#fff3ff;background:#4a148c;padding:15px;margin-top:12px;border-radius:8px;">
  <p style="font-size:18px;color:#ffd6ff;margin-top:0;">《▒ Contributing ▒》</p>
  <p>
   Totally open to any cool PRs or ideas! Keeping pace with the latest Forge/Minecraft updates, and yes - other loaders (Fabric/Quilt) are on my radar xd.
  </p>
</div>

<div style="border:1px solid #1565c0;color:#e3f2fd;padding:15px;margin-top:12px;">
  <p style="font-size:18px;color:#7986cb;margin-top:0;">《▒ License ▒》</p>
  <p style="margin-bottom:0;">
    Licensed under <b>BRSSLA V1.5</b>.
    <br><br>
    <b>Mod Developers:</b> If you’re using the Quantified API as a dependency, <b>you automatically score Commercial Rights for your mod!</b> Hit up BlackRift Studios for any other commercial licensing inquiries.
  </p>
</div>

<p style="text-align:center;color:#9e9e9e;margin-top:20px;font-size:12px;">
  Brought to you by Admany - BlackRift Studios - 2026
</p>

</div>
