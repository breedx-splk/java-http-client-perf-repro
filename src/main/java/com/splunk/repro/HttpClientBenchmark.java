package com.splunk.repro;

import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public final class HttpClientBenchmark {
  private static final OkHttpClient HTTP_CLIENT = new OkHttpClient();

  private HttpClientBenchmark() {}

  public static void main(String[] args) {
    int iterations = args.length > 0 ? Integer.parseInt(args[0]) : 1000;
    int warmups = args.length > 1 ? Integer.parseInt(args[1]) : 100;
    String url = args.length > 2 ? args[2] : "http://localhost:50054/";
    if (iterations < 1 || warmups < 0) {
      throw new IllegalArgumentException("iterations must be positive and warmups cannot be negative");
    }

    Request request = new Request.Builder().url(url).get().build();
    for (int i = 0; i < warmups; i++) {
      getStatus(request);
    }

    long[] durations = new long[iterations];
    int[] statusCodes = new int[iterations];
    System.out.println("iteration,duration_ms,status_code");
    for (int i = 0; i < iterations; i++) {
      long start = System.nanoTime();
      statusCodes[i] = getStatus(request);
      durations[i] = System.nanoTime() - start;
      System.out.printf(
          Locale.ROOT, "%d,%.3f,%d%n", i + 1, toMillis(durations[i]), statusCodes[i]);
    }

    printSummary(durations, statusCodes, url, warmups);
  }

  private static int getStatus(Request request) {
    try {
      get(request);
      return 200;
    } catch (HttpStatusException exception) {
      return exception.statusCode;
    } catch (IOException exception) {
      return -1;
    }
  }

  @WithSpan("benchmark.http.get")
  public static void get(Request request) throws IOException {
    try (Response response = HTTP_CLIENT.newCall(request).execute()) {
      if (!response.isSuccessful()) {
        throw new HttpStatusException(response.code());
      }
      response.body().bytes();
    }
  }

  private static void printSummary(
      long[] durations, int[] statusCodes, String url, int warmups) {
    long[] sorted = durations.clone();
    Arrays.sort(sorted);
    double mean = Arrays.stream(sorted).average().orElseThrow() / 1_000_000.0;
    long failures = Arrays.stream(statusCodes).filter(status -> status != 200).count();

    System.err.printf(
        Locale.ROOT,
        "# java=%s agent=%s url=%s warmups=%d iterations=%d failures=%d failure_rate=%.1f%% min_ms=%.3f mean_ms=%.3f p50_ms=%.3f p95_ms=%.3f max_ms=%.3f%n",
        System.getProperty("java.version"),
        System.getProperty("benchmark.agent.version", "none"),
        url,
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

  private static final class HttpStatusException extends IOException {
    private final int statusCode;

    private HttpStatusException(int statusCode) {
      super("Unexpected HTTP status " + statusCode);
      this.statusCode = statusCode;
    }
  }
}
