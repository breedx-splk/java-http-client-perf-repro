# Java HTTP and gRPC client performance repro

This benchmark compares Splunk OpenTelemetry Java agent 2.29.0 and 2.30.0 while matching the
Cloud Metric Syncer production environment from August 2026 as closely as practical.

The default client runtime now uses:

- Java 21
- the exact checked-in `splunk-otel-javaagent-csa` artifacts used by the base images
- a 20 GB fixed heap with G1GC
- the CMS OpenTelemetry sampler, exporter, propagator, profiler, resource, and disabled-resource
  settings
- the CMS JMX and Jolokia agents
- 100 warmups and 1,000 measured requests
- a random 10% server failure rate

The HTTP and gRPC clients remain useful control cases. The AWS benchmark is the closest match for
the CMS CloudWatch call path.

## Run locally with Gradle

The client tasks default to a production-sized `-Xms20g -Xmx20g`. On a smaller laptop, append
`-Pheap=2g` to both comparison commands. Apply identical overrides to both agent versions.

### HTTP: OkHttp 4.12.0

Start the local service:

```shell
./gradlew -q httpMockServer
```

Run the two CSA agents:

```shell
./gradlew -q benchmark -PagentVersion=2.29.0 > results-2.29.0.csv
./gradlew -q benchmark -PagentVersion=2.30.0 > results-2.30.0.csv
```

The service listens on port 50054. Override it with `-PhttpPort=50055` on the server and
`-Purl=http://localhost:50055/` on the client.

### gRPC

Start the local unary service:

```shell
./gradlew -q grpcServer
```

Run the clients:

```shell
./gradlew -q grpcBenchmark -PagentVersion=2.29.0 > results-grpc-2.29.0.csv
./gradlew -q grpcBenchmark -PagentVersion=2.30.0 > results-grpc-2.30.0.csv
```

The service listens on port 50052. Override it for both tasks with `-PgrpcPort=50053`.

### AWS SDK CloudWatch async client

Start the local CloudWatch-compatible endpoint:

```shell
./gradlew -q awsMockServer
```

Run the clients:

```shell
./gradlew -q awsBenchmark -PagentVersion=2.29.0 > results-aws-2.29.0.csv
./gradlew -q awsBenchmark -PagentVersion=2.30.0 > results-aws-2.30.0.csv
```

This uses the CMS versions and settings:

- AWS SDK BOM 2.46.20
- `NettyNioAsyncHttpClient`
- maximum concurrency 100
- 10,000 pending connection acquires
- 5-second connection timeout
- 10-second read timeout
- a four-thread future-completion/job executor with CMS's queue and core-thread timeout behavior
- AWS user-agent prefix `SignalFx` and suffix `cwinfo`

The client runs at concurrency 100. Overrides include `-Pwarmups`, `-Piterations`,
`-Pconcurrency`, `-PcompletionThreads`, and `-PawsEndpoint`.

## Exact base-image comparison

The Docker workflow is more faithful than direct Gradle execution. It uses the actual runtime base
images, including Ubuntu 24.04, Amazon Corretto 21.0.11.10, their embedded CSA agent, and their
ARM64 build.

Authenticate Docker to the image registry, set these variables to the complete rolled-back and
affected base-image references, and then build both images:

```shell
export CMS_BASE_IMAGE_229='<complete base image reference for release 1.15.6>'
export CMS_BASE_IMAGE_230='<complete base image reference for release 1.15.7>'

./gradlew installDist

docker build --platform linux/arm64 \
  --build-arg BASE_IMAGE="$CMS_BASE_IMAGE_229" \
  --build-arg AGENT_VERSION=2.29.0 \
  -t java-http-perf:agent-2.29.0 .

docker build --platform linux/arm64 \
  --build-arg BASE_IMAGE="$CMS_BASE_IMAGE_230" \
  --build-arg AGENT_VERSION=2.30.0 \
  -t java-http-perf:agent-2.30.0 .
```

Start the desired mock server on the host, then run the same benchmark through each image. For AWS:

```shell
docker run --rm --platform linux/arm64 \
  -e BENCHMARK=aws \
  java-http-perf:agent-2.29.0 > results-aws-2.29.0.csv

docker run --rm --platform linux/arm64 \
  -e BENCHMARK=aws \
  java-http-perf:agent-2.30.0 > results-aws-2.30.0.csv
```

Set `BENCHMARK=http` or `BENCHMARK=grpc` for the other clients. The container uses
`host.docker.internal` for mock-service and local collector connections. On Linux, add:

```text
--add-host=host.docker.internal:host-gateway
```

The container runs as UID/GID 9739 and includes production-like paths for GC logs, heap dumps, and
the profiler.

## Runtime overrides

Production-compatible defaults are intentional. Useful overrides are:

| Gradle property | Default | Purpose |
|---|---:|---|
| `-Pheap` | `20g` | Sets both initial and maximum heap |
| `-Pprofiler` | `true` | Enables CPU/snapshot profiling and the production memory-profiler environment variable |
| `-PmemoryProfilerProperty` | `false` | Reproduces the incident launcher's mismatched memory-profiler JVM property |
| `-Pjolokia` | `true` | Loads Jolokia on port 6000 |
| `-PjmxPort` | `18752` | Set to `0` to disable JMX |
| `-PotlpEndpoint` | `http://localhost:4318` | Trace OTLP endpoint |
| `-PotlpMetricsEndpoint` | `http://localhost:4319/v1/metrics` | Metrics OTLP endpoint |
| `-PagentFlavor` | `csa` | Use `standard` only for a deliberate non-production comparison |

The split memory-profiler configuration is intentional: the deployment set
`SPLUNK_PROFILER_MEMORY_ENABLED=true`, while the incident-era launcher read
`PROFILER_MEMORY_ENABLED` and produced `-Dsplunk.profiler.memory.enabled=false` by default.

For a low-resource smoke test:

```shell
./gradlew -q awsBenchmark \
  -PagentVersion=2.30.0 \
  -Piterations=20 \
  -Pwarmups=5 \
  -Pheap=512m \
  -Pprofiler=false \
  -Pjolokia=false \
  -PjmxPort=0
```

## Remaining differences

Direct Gradle execution uses whichever compatible Java 21 toolchain is installed locally. Use the
Docker workflow for the exact Corretto build. A laptop also cannot reproduce Graviton scheduling,
Kubernetes networking, CPU throttling, neighboring workloads, or the production collector without
running on comparable ARM64 infrastructure.

The base-image comparison is deliberately cleaner than comparing CMS `v12.9.16` with
`v12.9.17`: that application rollback also reverted unrelated dependency changes.
