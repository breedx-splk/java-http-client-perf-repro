package com.splunk.repro;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

public final class HttpMockServer {
  private HttpMockServer() {}

  public static void main(String[] args) throws IOException {
    int port = args.length > 0 ? Integer.parseInt(args[0]) : 50054;
    HttpServer server = HttpServer.create(new InetSocketAddress("localhost", port), 0);
    server.createContext("/", HttpMockServer::respond);
    server.setExecutor(Executors.newCachedThreadPool());
    server.start();
    System.err.printf(
        "HTTP benchmark server listening on http://localhost:%d with a 10%% failure rate%n", port);
  }

  private static void respond(HttpExchange exchange) throws IOException {
    try (exchange) {
      exchange.getRequestBody().readAllBytes();
      int statusCode = 200;
      if (ThreadLocalRandom.current().nextInt(10) == 0) {
        statusCode = ThreadLocalRandom.current().nextBoolean() ? 403 : 500;
      }
      byte[] body = (statusCode == 200 ? "ok" : "error").getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
      exchange.sendResponseHeaders(statusCode, body.length);
      exchange.getResponseBody().write(body);
    }
  }
}
