# Quantified API V2 Performance Report

Date: 2026-06-15
Comparison: `quantified api-omni-1.4.4.jar` vs current rebuilt `quantified api-omni-2.0.0.jar`
JVM: `Java 21.0.8`
Logical processors: `16`

## Setup

- Old jar: `C:\Users\mocke\Downloads\quantified api-omni-1.4.4.jar`
- New jar: `C:\Users\mocke\Desktop\Quantified API\build\libs\quantified api-omni-2.0.0.jar`
- Harness: `tools/benchmarks/JarApiPerfBench.java`
- Warmups: `3`
- Measured iterations: `8`

## Median Results

| Scenario | V1.4.4 | V2.0.0 | Speedup |
|---|---:|---:|---:|
| Tiny unique burst | 215.739 ms | 60.981 ms | 3.54x |
| Duplicate burst | 155.731 ms | 57.635 ms | 2.70x |
| Parallel micro | 1.225 ms | 0.922 ms | 1.33x |
| Parallel medium | 2.661 ms | 2.778 ms | 0.96x |
| DAG micro | 2352.005 ms | 7.360 ms | 319.57x |

## Extra Signal

- Tiny unique burst completed:
  - V1.4.4: `4096`
  - V2.0.0: `4096`
- Duplicate burst actual executions:
  - V1.4.4: `2048`
  - V2.0.0: `1`
- Parallel micro result count:
  - V1.4.4: `2048`
  - V2.0.0: `2048`
- Parallel medium result count:
  - V1.4.4: `8192`
  - V2.0.0: `8192`
- DAG micro result count:
  - V1.4.4: `24`
  - V2.0.0: `24`

## Notes

- V2 keeps the big wins in unique bursts, duplicate coalescing, and especially DAG execution.
- `parallel_medium` was effectively parity in this Java 21 run and came out slightly slower by median. Earlier Java 25 confirmation runs had V2 faster there, so this one should be watched but not treated as a hard regression without more isolated samples.
- The duplicate burst still coalesces correctly in V2 (`2048` submissions -> `1` execution), but median wall time has jitter in the standalone jar harness.

## Raw Output Files

- V1.4.4: `benchmarks/jarbench_java21_v1.4.4_2026-06-15_170150.txt`
- V2.0.0: `benchmarks/jarbench_java21_v2.0.0_2026-06-15_170150.txt`

