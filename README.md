# Java HTTP client performance repro

A small Java 17 benchmark that measures sequential OkHttp GET requests while the Splunk
OpenTelemetry Java agent instruments an `@WithSpan` method. Request durations are written as CSV
to standard output, and a summary is written to standard error.

Run either agent version against the local collector:

```shell
./gradlew -q benchmark -PagentVersion=2.29.0 -Piterations=100 > results-2.29.0.csv
./gradlew -q benchmark -PagentVersion=2.30.0 -Piterations=100 > results-2.30.0.csv
```

The 2.29.0 and 2.30.0 agent JARs are checked in at the repository root. There are 10 unmeasured
warmup requests by default. Override the defaults with `-Pwarmups=0`, `-Piterations=1000`, or
`-Purl=https://example.com/`.
