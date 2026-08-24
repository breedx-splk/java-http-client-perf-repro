package com.splunk.repro;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import java.io.IOException;

public final class GrpcTestServer {
  private GrpcTestServer() {}

  public static void main(String[] args) throws IOException, InterruptedException {
    int port = args.length > 0 ? Integer.parseInt(args[0]) : 50052;
    Server server = ServerBuilder.forPort(port).addService(GrpcPingService.bindService()).build();
    server.start();
    Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown));
    System.out.printf("gRPC benchmark server listening on localhost:%d%n", port);
    server.awaitTermination();
  }
}
