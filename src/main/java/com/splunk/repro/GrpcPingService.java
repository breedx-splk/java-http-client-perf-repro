package com.splunk.repro;

import io.grpc.MethodDescriptor;
import io.grpc.ServerServiceDefinition;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

final class GrpcPingService {
  static final String SERVICE_NAME = "com.splunk.repro.PingService";
  static final MethodDescriptor<String, String> PING_METHOD =
      MethodDescriptor.<String, String>newBuilder()
          .setType(MethodDescriptor.MethodType.UNARY)
          .setFullMethodName(MethodDescriptor.generateFullMethodName(SERVICE_NAME, "Ping"))
          .setRequestMarshaller(new Utf8Marshaller())
          .setResponseMarshaller(new Utf8Marshaller())
          .build();

  private GrpcPingService() {}

  static ServerServiceDefinition bindService() {
    return ServerServiceDefinition.builder(SERVICE_NAME)
        .addMethod(PING_METHOD, ServerCalls.asyncUnaryCall(GrpcPingService::ping))
        .build();
  }

  private static void ping(String request, StreamObserver<String> responseObserver) {
    responseObserver.onNext("pong");
    responseObserver.onCompleted();
  }

  private static final class Utf8Marshaller implements MethodDescriptor.Marshaller<String> {
    @Override
    public InputStream stream(String value) {
      return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String parse(InputStream stream) {
      try {
        return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      } catch (IOException exception) {
        throw new IllegalStateException("Could not read gRPC payload", exception);
      }
    }
  }
}
