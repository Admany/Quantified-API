<div style="font-family:'JetBrains Mono','Courier New',monospace;color:#e0e0e0;background:#1b1b1b;padding:20px;border-radius:12px;">

<h1 style="text-align:center;color:#ffffff">《▓ Quantified API ▓》</h1>

<p style="text-align:center">
  <a href="https://github.com/Admany/Quantified-API"><img src="https://i.imghippo.com/files/k1781Ug.png"></a>
</p>

<div style="border:1px solid #9c27b0;color:#e1bee7;padding:12px;margin-top:12px;">
  <p style="font-size:18px;color:#f3e5f5">《▒ Overview ▒》</p>
  <p>
    Quantified API is a high-performance framework designed to revolutionize how Minecraft mods handle heavy tasks. Its main goal is to make the game run smoother by offloading computations, preventing lag, and managing resources efficiently.
  </p>
  <p>
    - GPU acceleration for compute-heavy tasks  
    - Multi-tier caching (RAM, disk, VRAM)  
    - Intelligent async and parallel task scheduling  
    - Thread safety, isolation, and robust error handling  
    - Developer dashboard for live performance metrics  
  </p>
  <p style="color:#c8e6c9">
    This project is actively maintained and frequently updated to stay compatible with the latest Minecraft and Forge versions.
  </p>
</div>

<div style="border:1px solid #7b1fa2;color:#ce93d8;padding:12px;margin-top:12px;">
  <p style="font-size:18px;color:#ba68c8">《▒ Why Use Quantified API ▒》</p>
  <p>
    - Keeps Minecraft smooth even with heavy mods  
    - Automatically handles multithreading and GPU offload  
    - Reduces main-thread bottlenecks  
    - Scales with modern hardware  
    - Simplifies complex systems for modders  
  </p>
  <p style="font-weight:bold;color:#ab47bc">
    Recommendation - if your mod performs world-gen, AI, networking, or heavy calculations, use this API to prevent crashes and stutter.
  </p>
</div>

<div style="border:1px solid #6a1b9a;color:#b39ddb;padding:12px;margin-top:12px;">
  <p style="font-size:18px;color:#9575cd">《▒ Core Features ▒》</p>
  <p>
    - Job-based task execution with automatic routing  
    - Dynamic task scheduling for optimal CPU/GPU usage  
    - Parallel and slice-based processing for large workloads  
    - Optional GPU acceleration via OpenCL  
    - Networking support for distributed task coordination  
    - Built-in caching layers (RAM, disk, VRAM)  
    - Developer dashboard for monitoring tasks, caches, and performance  
    - Easy integration with minimal code changes
  </p>
</div>

<div style="border:1px solid #4a148c;color:#d1c4e9;padding:12px;margin-top:12px;">
  <p style="font-size:18px;color:#b39ddb">《▒ How It Works ▒》</p>
  <p>
    - Tasks are submitted asynchronously via `QuantifiedAPI` or `ParallelCompute`  
    - Small tasks run on CPU worker pools, GPU tasks offloaded automatically  
    - Caching prevents redundant computations and speeds up repeated tasks  
    - Networking allows multi-server coordination  
    - Developer dashboard provides live performance insights
  </p>
</div>

<div style="border:1px solid #2e7d32;color:#e8f5e9;padding:12px;margin-top:12px;">
  <p style="font-size:18px;color:#b39ddb">《▒ Quick Start ▒》</p>
  <p>
    - Add the API dependency or drop the JAR in `mods/`  
    - Ensure Java 17+ runtime  
    - Configure via `config/quantified/quantified_config.json`  
    - Register your mod at startup:  
    <pre style="background:#2b0b2b;color:#f6e6ff;padding:6px;border-radius:4px;">QuantifiedAPI.register("mymodid", "My Mod", "1.0.0");</pre>
    - Submit async, parallel, or GPU tasks:  
    <pre style="background:#2b0b2b;color:#f6e6ff;padding:6px;border-radius:4px;">CompletableFuture&lt;String&gt; result = QuantifiedAPI.submit("myTask", () -&gt; "done");</pre>
  </p>
</div>

<div style="border:1px solid #0d47a1;color:#90caf9;padding:12px;margin-top:12px;">
  <p style="font-size:18px;color:#42a5f5">《▒ Code Locations ▒》</p>
  <p>
    All interface, builder, and model code can be found here:  
    - `\src\main\java\org\admany\quantified\api\interfaces`  
    - `\src\main\java\org\admany\quantified\api\builders`  
    - `\src\main\java\org\admany\quantified\api\model`  
  </p>
  <p>
    Example use cases and task implementations:  
    - `\src\main\java\org\admany\quantified\core\common\opencl\task`
  </p>
</div>

<div style="border:2px solid #7b1fa2;color:#fff3ff;background:#4a148c;padding:12px;margin-top:12px;border-radius:8px;">
  <p style="font-size:18px;color:#ffd6ff">《▒ Contributing ▒》</p>
  <p>
    Contributions, bug fixes, examples, or improvements on <a href="https://github.com/Admany/Quantified-API" style="color:#ffd6ff;">GitHub</a> are appreciated.
    The project is actively maintained and frequently updated.
  </p>
</div>

<div style="border:1px solid #1565c0;color:#e3f2fd;padding:12px;margin-top:12px;">
  <p style="font-size:18px;color:#7986cb">《▒ License ▒》</p>
  <p>
    Licensed under BRSSLA V1.3 - free for personal and non-commercial use with proper attribution.
    Contact BlackRift Studios for commercial licensing.
  </p>
</div>

<div style="border:1px solid #fbc02d;color:#fff9c4;padding:12px;margin-top:12px;">
  <p style="font-size:18px;color:#f57f17">《▒ Final Recommendation ▒》</p>
  <p>
    For heavy mods, world-gen, AI, or networking tasks - Quantified API is highly recommended.
    It handles multithreading, caching, and GPU acceleration automatically, keeping Minecraft smooth while you focus on mod features.
  </p>
</div>

<p style="text-align:center;color:#9e9e9e;margin-top:16px;">
  Built by Admany - BlackRift Studios - 2025
</p>

</div>
