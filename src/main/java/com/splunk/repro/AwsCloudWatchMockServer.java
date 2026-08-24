package com.splunk.repro;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

public final class AwsCloudWatchMockServer {
  private static final byte[] EMPTY_CBOR_MAP = {(byte) 0xa0};

  private AwsCloudWatchMockServer() {}

  public static void main(String[] args) throws IOException {
    int port = args.length > 0 ? Integer.parseInt(args[0]) : 50053;
    HttpServer server = HttpServer.create(new InetSocketAddress("localhost", port), 0);
    server.createContext("/", AwsCloudWatchMockServer::respond);
    server.setExecutor(Executors.newCachedThreadPool());
    server.start();
    System.err.printf(
        "CloudWatch mock endpoint listening on http://localhost:%d with a 10%% failure rate%n",
        port);
  }

  private static void respond(HttpExchange exchange) throws IOException {
    try (exchange) {
      exchange.getRequestBody().readAllBytes();
      int statusCode = 200;
      if (ThreadLocalRandom.current().nextInt(10) == 0) {
        statusCode = ThreadLocalRandom.current().nextBoolean() ? 403 : 500;
        exchange
            .getResponseHeaders()
            .set(
                "x-amzn-errortype",
                statusCode == 403 ? "AccessDeniedException" : "InternalServiceFault");
      }
      exchange.getResponseHeaders().set("Content-Type", "application/cbor");
      exchange.getResponseHeaders().set("x-amzn-requestid", "benchmark-request");
      exchange.sendResponseHeaders(statusCode, EMPTY_CBOR_MAP.length);
      exchange.getResponseBody().write(EMPTY_CBOR_MAP);
    }
  }
}
