# Java HTTP and gRPC client performance repro

A small Java 17 benchmark that measures sequential OkHttp GET requests while the Splunk
OpenTelemetry Java agent instruments an `@WithSpan` method. Request durations are written as CSV
to standard output, and a summary is written to standard error.

## HTTP

Run either agent version against the local collector:

```shell
./gradlew -q benchmark -PagentVersion=2.29.0 -Piterations=100 > results-2.29.0.csv
./gradlew -q benchmark -PagentVersion=2.30.0 -Piterations=100 > results-2.30.0.csv
```

The 2.29.0 and 2.30.0 agent JARs are checked in at the repository root. There are 10 unmeasured
warmup requests by default. Override the defaults with `-Pwarmups=0`, `-Piterations=1000`, or
`-Purl=https://example.com/`.

## gRPC

Start the local unary gRPC service in one terminal:

```shell
./gradlew -q grpcServer
```

Then run either client agent version in another terminal:

```shell
./gradlew -q grpcBenchmark -PagentVersion=2.29.0 > results-grpc-2.29.0.csv
./gradlew -q grpcBenchmark -PagentVersion=2.30.0 > results-grpc-2.30.0.csv
```

The client performs 100 warmups and 1,000 measured calls by default. The service listens on port
50052. Override it for both tasks with `-PgrpcPort=50053`; the client also accepts
`-PgrpcHost=localhost`.
