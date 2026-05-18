# Quantified API V2 Performance Report

Date: 2026-05-18  
Comparison: `quantified api-omni-1.4.4.jar` vs `quantified api-omni-2.0.0.jar`  
JVM: `Java 17.0.16`  
Logical processors: `16`

## Setup

- Old jar: `quantified api-omni-1.4.4.jar`
- New jar: `quantified api-omni-2.0.0.jar`
- Harness: `tools/benchmarks/JarApiPerfBench.java`
- Warmups: `3`
- Measured iterations: `8`

## Median results

| Scenario | V1.4.4 | V2.0.0 | Speedup |
|---|---:|---:|---:|
| Tiny unique burst | 218.752 ms | 63.432 ms | 3.45x |
| Duplicate burst | 141.122 ms | 3.234 ms | 43.64x |
| Parallel micro | 1.191 ms | 0.457 ms | 2.60x |
| Parallel medium | 4.043 ms | 0.703 ms | 5.75x |
| DAG micro | 849.173 ms | 62.488 ms | 13.59x |

## Extra signal

- Tiny unique burst actual completions:
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

## Raw benchmark output

### V1.4.4

```text
QAPI jar benchmark
label=v1.4.4
jar=quantified api-omni-1.4.4.jar
tiny_unique_burst median=218.752ms mean=174.646ms min=127.359ms max=221.422ms throughput=18724.4/s extra=4096
duplicate_burst median=141.122ms mean=111.057ms min=61.414ms max=187.300ms throughput=14512.2/s extra=2048
parallel_micro median=1.191ms mean=1.311ms min=1.095ms max=2.014ms throughput=1719274.7/s extra=2048
parallel_medium median=4.043ms mean=3.959ms min=2.098ms max=5.878ms throughput=2026268.3/s extra=8192
dag_micro median=849.173ms mean=929.677ms min=469.783ms max=1922.839ms throughput=28.3/s extra=24
```

### V2.0.0

```text
QAPI jar benchmark
label=v2.0.0
jar=quantified api-omni-2.0.0.jar
tiny_unique_burst median=63.432ms mean=63.068ms min=62.075ms max=64.213ms throughput=64573.2/s extra=4096
duplicate_burst median=3.234ms mean=15.915ms min=0.873ms max=58.781ms throughput=633349.8/s extra=1
parallel_micro median=0.457ms mean=0.482ms min=0.424ms max=0.591ms throughput=4478460.5/s extra=2048
parallel_medium median=0.703ms mean=0.959ms min=0.640ms max=2.348ms throughput=11654573.9/s extra=8192
dag_micro median=62.488ms mean=47.082ms min=0.503ms max=63.663ms throughput=384.1/s extra=24
```

