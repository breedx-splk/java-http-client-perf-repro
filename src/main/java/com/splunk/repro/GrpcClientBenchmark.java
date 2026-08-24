package com.splunk.repro;

import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ClientCalls;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public final class GrpcClientBenchmark {
  private GrpcClientBenchmark() {}

  public static void main(String[] args) throws InterruptedException {
    int iterations = args.length > 0 ? Integer.parseInt(args[0]) : 1000;
    int warmups = args.length > 1 ? Integer.parseInt(args[1]) : 100;
    String host = args.length > 2 ? args[2] : "localhost";
    int port = args.length > 3 ? Integer.parseInt(args[3]) : 50052;
    if (iterations < 1 || warmups < 0) {
      throw new IllegalArgumentException("iterations must be positive and warmups cannot be negative");
    }

    ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
    try {
      for (int i = 0; i < warmups; i++) {
        pingStatus(channel);
      }

      long[] durations = new long[iterations];
      String[] statusCodes = new String[iterations];
      System.out.println("iteration,duration_ms,status_code");
      for (int i = 0; i < iterations; i++) {
        long start = System.nanoTime();
        statusCodes[i] = pingStatus(channel);
        durations[i] = System.nanoTime() - start;
        System.out.printf(
            Locale.ROOT, "%d,%.3f,%s%n", i + 1, toMillis(durations[i]), statusCodes[i]);
      }

      printSummary(durations, statusCodes, host, port, warmups);
    } finally {
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  private static String pingStatus(ManagedChannel channel) {
    try {
      ping(channel);
      return "OK";
    } catch (StatusRuntimeException exception) {
      return exception.getStatus().getCode().name();
    }
  }

  @WithSpan("benchmark.grpc.unary")
  public static void ping(ManagedChannel channel) {
    String response =
        ClientCalls.blockingUnaryCall(
            channel, GrpcPingService.PING_METHOD, CallOptions.DEFAULT, "ping");
    if (!"pong".equals(response)) {
      throw new IllegalStateException("Unexpected gRPC response: " + response);
    }
  }

  private static void printSummary(
      long[] durations, String[] statusCodes, String host, int port, int warmups) {
    long[] sorted = durations.clone();
    Arrays.sort(sorted);
    double mean = Arrays.stream(sorted).average().orElseThrow() / 1_000_000.0;
    long failures = Arrays.stream(statusCodes).filter(status -> !"OK".equals(status)).count();

    System.err.printf(
        Locale.ROOT,
        "# java=%s agent=%s target=%s:%d warmups=%d iterations=%d failures=%d failure_rate=%.1f%% min_ms=%.3f mean_ms=%.3f p50_ms=%.3f p95_ms=%.3f max_ms=%.3f%n",
        System.getProperty("java.version"),
        System.getProperty("benchmark.agent.version", "none"),
        host,
        port,
        warmups,
        sorted.length,
        failures,
        failures * 100.0 / sorted.length,
        toMillis(sorted[0]),
        mean,
        percentile(sorted, 0.50),
        percentile(sorted, 0.95),
        toMillis(sorted[sorted.length - 1]));
  }

  private static double percentile(long[] sorted, double percentile) {
    int index = (int) Math.ceil(percentile * sorted.length) - 1;
    return toMillis(sorted[Math.max(0, index)]);
  }

  private static double toMillis(long nanos) {
    return nanos / 1_000_000.0;
  }
}
