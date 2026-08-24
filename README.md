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

## AWS SDK async client

Start the local CloudWatch-compatible endpoint in one terminal. Each request rolls a d10; a roll of
1 returns either HTTP 403 or 500:

```shell
./gradlew -q awsMockServer
```

Then run the AWS SDK v2 `CloudWatchAsyncClient` benchmark:

```shell
./gradlew -q awsBenchmark -PagentVersion=2.29.0 > results-aws-2.29.0.csv
./gradlew -q awsBenchmark -PagentVersion=2.30.0 > results-aws-2.30.0.csv
```

The benchmark uses AWS SDK 2.46.20 with a Netty async client configured for 100 concurrent requests,
10,000 pending connection acquires, a 5-second connection timeout, a 10-second read timeout, and a
four-thread future-completion executor. It runs 100 warmups followed by 1,000 measured
`ListMetrics` requests at concurrency 100. Override these with `-Pwarmups`, `-Piterations`,
`-Pconcurrency`, or `-PcompletionThreads`. The endpoint defaults to
`http://localhost:50053`; use `-PawsPort` for the server and `-PawsEndpoint` for the client.
