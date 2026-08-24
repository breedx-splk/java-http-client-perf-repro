package com.splunk.repro;

import static software.amazon.awssdk.core.client.config.SdkAdvancedAsyncClientOption.FUTURE_COMPLETION_EXECUTOR;

import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.client.config.ClientAsyncConfiguration;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient;
import software.amazon.awssdk.services.cloudwatch.model.ListMetricsRequest;
import software.amazon.awssdk.services.cloudwatch.model.ListMetricsResponse;

public final class AwsCloudWatchAsyncBenchmark {
  private static final int HTTP_MAX_CONCURRENCY = 100;
  private static final int MAX_PENDING_CONNECTION_ACQUIRES = 10_000;

  private AwsCloudWatchAsyncBenchmark() {}

  public static void main(String[] args) throws InterruptedException {
    int iterations = args.length > 0 ? Integer.parseInt(args[0]) : 1000;
    int warmups = args.length > 1 ? Integer.parseInt(args[1]) : 100;
    URI endpoint = URI.create(args.length > 2 ? args[2] : "http://localhost:50053");
    int concurrency = args.length > 3 ? Integer.parseInt(args[3]) : 100;
    int completionThreads = args.length > 4 ? Integer.parseInt(args[4]) : 4;
    if (iterations < 1 || warmups < 0 || concurrency < 1 || completionThreads < 1) {
      throw new IllegalArgumentException(
          "iterations, concurrency, and completionThreads must be positive; warmups cannot be negative");
    }

    ExecutorService completionExecutor = Executors.newFixedThreadPool(completionThreads);
    try (CloudWatchAsyncClient client = createClient(endpoint, completionExecutor)) {
      ListMetricsRequest request =
          ListMetricsRequest.builder()
              .namespace("AWS/EC2")
              .metricName("CPUUtilization")
              .build();

      runRequests(client, request, warmups, concurrency, null);

      long[] durations = new long[iterations];
      int[] statusCodes = new int[iterations];
      runRequests(client, request, iterations, concurrency, durations, statusCodes);

      System.out.println("iteration,duration_ms,status_code");
      for (int i = 0; i < durations.length; i++) {
        System.out.printf(
            Locale.ROOT, "%d,%.3f,%d%n", i + 1, toMillis(durations[i]), statusCodes[i]);
      }
      printSummary(
          durations, statusCodes, endpoint, warmups, concurrency, completionThreads);
    } finally {
      completionExecutor.shutdownNow();
      completionExecutor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  private static CloudWatchAsyncClient createClient(
      URI endpoint, ExecutorService completionExecutor) {
    return CloudWatchAsyncClient.builder()
        .endpointOverride(endpoint)
        .region(Region.US_EAST_1)
        .credentialsProvider(
            StaticCredentialsProvider.create(AwsBasicCredentials.create("benchmark", "benchmark")))
        .httpClientBuilder(
            NettyNioAsyncHttpClient.builder()
                .maxConcurrency(HTTP_MAX_CONCURRENCY)
                .maxPendingConnectionAcquires(MAX_PENDING_CONNECTION_ACQUIRES)
                .connectionTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(10)))
        .asyncConfiguration(
            ClientAsyncConfiguration.builder()
                .advancedOption(FUTURE_COMPLETION_EXECUTOR, completionExecutor)
                .build())
        .build();
  }

  private static void runRequests(
      CloudWatchAsyncClient client,
      ListMetricsRequest request,
      int count,
      int concurrency,
      long[] durations) {
    runRequests(client, request, count, concurrency, durations, null);
  }

  private static void runRequests(
      CloudWatchAsyncClient client,
      ListMetricsRequest request,
      int count,
      int concurrency,
      long[] durations,
      int[] statusCodes) {
    for (int offset = 0; offset < count; offset += concurrency) {
      int batchSize = Math.min(concurrency, count - offset);
      List<CompletableFuture<Void>> batch = new ArrayList<>(batchSize);
      for (int i = 0; i < batchSize; i++) {
        int index = offset + i;
        long start = System.nanoTime();
        CompletableFuture<Void> future =
            listMetrics(client, request)
                .handle(
                    (response, error) -> {
                      if (durations != null) {
                        durations[index] = System.nanoTime() - start;
                      }
                      if (error == null) {
                        validateResponse(response);
                        if (statusCodes != null) {
                          statusCodes[index] = 200;
                        }
                      } else if (statusCodes != null) {
                        statusCodes[index] = statusCode(error);
                      }
                      return null;
                    });
        batch.add(future);
      }
      CompletableFuture.allOf(batch.toArray(CompletableFuture[]::new)).join();
    }
  }

  @WithSpan("benchmark.aws.cloudwatch.list-metrics")
  public static CompletableFuture<ListMetricsResponse> listMetrics(
      CloudWatchAsyncClient client, ListMetricsRequest request) {
    return client.listMetrics(request);
  }

  private static void validateResponse(ListMetricsResponse response) {
    if (response == null || response.metrics() == null) {
      throw new IllegalStateException("Invalid CloudWatch ListMetrics response");
    }
  }

  private static int statusCode(Throwable error) {
    Throwable cause = error;
    while (cause != null) {
      if (cause instanceof AwsServiceException serviceException) {
        return serviceException.statusCode();
      }
      cause = cause.getCause();
    }
    return -1;
  }

  private static void printSummary(
      long[] durations,
      int[] statusCodes,
      URI endpoint,
      int warmups,
      int concurrency,
      int completionThreads) {
    long[] sorted = durations.clone();
    Arrays.sort(sorted);
    double mean = Arrays.stream(sorted).average().orElseThrow() / 1_000_000.0;
    long failures = Arrays.stream(statusCodes).filter(status -> status != 200).count();

    System.err.printf(
        Locale.ROOT,
        "# java=%s agent=%s endpoint=%s warmups=%d iterations=%d concurrency=%d completion_threads=%d failures=%d failure_rate=%.1f%% min_ms=%.3f mean_ms=%.3f p50_ms=%.3f p95_ms=%.3f max_ms=%.3f%n",
        System.getProperty("java.version"),
        System.getProperty("benchmark.agent.version", "none"),
        endpoint,
        warmups,
        sorted.length,
        concurrency,
        completionThreads,
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
