package net.firedevops.firemud.common.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.CallOptions;
import io.grpc.HandlerRegistry;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerMethodDefinition;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.TlsServerCredentials;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ServerCalls;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import net.firedevops.firemud.test.LoopbackMtlsTestPki.IdentitySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoundedGrpcServerFactoryTest {
  private static final int ORDINARY = GrpcRequestBytePolicy.ORDINARY_MAXIMUM_BYTES;

  @Test
  void rejectsDuplicateUnknownAndNonidenticalDescriptorConfiguration() {
    var method = method("test.Owner/Read", new AtomicInteger());
    assertThatThrownBy(
            () ->
                new GrpcRequestBytePolicy(
                    List.of(
                        new GrpcRequestBytePolicy.MethodBound(method, 1024),
                        new GrpcRequestBytePolicy.MethodBound(method, 1024))))
        .isInstanceOf(IllegalArgumentException.class);
    var policy =
        new GrpcRequestBytePolicy(
            List.of(new GrpcRequestBytePolicy.MethodBound(method, ORDINARY + 1024)));
    var factory = factory(policy);
    factory.addService(service(method.toBuilder().build()));
    assertThatThrownBy(factory::createServer).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> factory(policy).createServer())
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void refusesAnUnwrappedServiceAddedByCustomizer() {
    var factory =
        new BoundedGrpcServerFactory(
            "127.0.0.1:0",
            List.of(
                builder ->
                    builder.addService(service(method("test.Bypass/Read", new AtomicInteger())))),
            null,
            null,
            TlsServerCredentials.ClientAuth.NONE,
            new GrpcRequestBytePolicy(List.of()),
            ORDINARY);
    assertThatThrownBy(factory::createServer)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Unguarded gRPC method");
  }

  @Test
  void refusesAnExternallyGuardedDescriptorWhoseHandlerWasReplaced() {
    var policy = GrpcRequestBytePolicy.ordinary();
    var external = policy.guard(service(method("test.Bypass/Read", new AtomicInteger())), ORDINARY);
    var replaced =
        io.grpc.ServerInterceptors.intercept(
            external,
            new io.grpc.ServerInterceptor() {
              @Override
              public <Q, R> io.grpc.ServerCall.Listener<Q> interceptCall(
                  io.grpc.ServerCall<Q, R> call,
                  io.grpc.Metadata headers,
                  io.grpc.ServerCallHandler<Q, R> next) {
                return new io.grpc.ServerCall.Listener<>() {};
              }
            });
    assertThat(replaced.getMethod("test.Bypass/Read").getMethodDescriptor())
        .isSameAs(external.getMethod("test.Bypass/Read").getMethodDescriptor());
    var factory =
        new BoundedGrpcServerFactory(
            "127.0.0.1:0",
            List.of(builder -> builder.addService(replaced)),
            null,
            null,
            TlsServerCredentials.ClientAuth.NONE,
            policy,
            ORDINARY);
    assertThatThrownBy(factory::createServer)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Unguarded gRPC method");
  }

  @Test
  void mtlsCompressedOrdinaryAndFutureMethodsRejectBeforeParserAndFallbackCannotBypass(
      @TempDir Path directory) throws Exception {
    var pki =
        LoopbackMtlsTestPki.create(
            directory,
            "bounded factory test CA",
            new IdentitySpec("server", "spiffe://firemud/ns/test/sa/game-session-service", true),
            new IdentitySpec(
                "entity", "spiffe://firemud/ns/test/sa/entity-management-service", false));
    var serverIdentity = pki.identity("server");
    var ordinaryParses = new AtomicInteger();
    var exceptionalParses = new AtomicInteger();
    var futureParses = new AtomicInteger();
    var ordinary = method("test.Owner/Ordinary", ordinaryParses);
    var exceptional = method("test.Owner/Exceptional", exceptionalParses);
    var future = method("test.Owner/Future", futureParses);
    var unknown = method("test.Unknown/Read", new AtomicInteger());
    var policy =
        new GrpcRequestBytePolicy(
            List.of(new GrpcRequestBytePolicy.MethodBound(exceptional, ORDINARY + 1024)));
    var fallbackCalls = new AtomicInteger();
    var factory =
        new BoundedGrpcServerFactory(
            "127.0.0.1:0",
            List.of(
                builder -> {
                  builder.fallbackHandlerRegistry(
                      new HandlerRegistry() {
                        @Override
                        public ServerMethodDefinition<?, ?> lookupMethod(
                            String method, String authority) {
                          fallbackCalls.incrementAndGet();
                          return service(unknown).getMethod(method);
                        }
                      });
                }),
            pki.keyManagerFactory(serverIdentity),
            pki.trustManagerFactory(),
            TlsServerCredentials.ClientAuth.REQUIRE,
            policy,
            explicitTransportMaximum(policy));
    factory.addService(
        ServerServiceDefinition.builder("test.Owner")
            .addMethod(ordinary, handler())
            .addMethod(exceptional, handler())
            .addMethod(future, handler())
            .build());
    Server server = factory.createServer().start();
    var clientIdentity = pki.identity("entity");
    ManagedChannel channel =
        NettyChannelBuilder.forAddress("127.0.0.1", server.getPort())
            .sslContext(
                GrpcSslContexts.forClient()
                    .trustManager(pki.caCertificate())
                    .keyManager(clientIdentity.privateKey(), clientIdentity.certificate())
                    .build())
            .build();
    ManagedChannel noCertificate =
        NettyChannelBuilder.forAddress("127.0.0.1", server.getPort())
            .sslContext(GrpcSslContexts.forClient().trustManager(pki.caCertificate()).build())
            .build();
    try {
      var options =
          CallOptions.DEFAULT.withDeadlineAfter(10, TimeUnit.SECONDS).withCompression("gzip");
      assertThat(ClientCalls.blockingUnaryCall(channel, ordinary, options, new byte[ORDINARY]))
          .isEmpty();
      assertThat(ordinaryParses).hasValue(1);
      byte[] bytes = new byte[ORDINARY + 1];
      assertThat(ClientCalls.blockingUnaryCall(channel, exceptional, options, bytes)).isEmpty();
      assertThat(exceptionalParses).hasValue(1);
      // Response parsing uses a separate counter; these counters observe server request parsing.
      assertRejected(channel, ordinary, options, bytes, Status.Code.RESOURCE_EXHAUSTED);
      assertRejected(channel, future, options, bytes, Status.Code.RESOURCE_EXHAUSTED);
      assertThat(ordinaryParses).hasValue(1);
      assertThat(futureParses).hasValue(0);
      assertRejected(channel, unknown, options, bytes, Status.Code.UNIMPLEMENTED);
      assertThat(fallbackCalls).hasValue(0);
      assertRejected(
          channel,
          exceptional,
          options,
          new byte[policy.transportMaximumBytes() + 1],
          Status.Code.RESOURCE_EXHAUSTED);
      assertThat(exceptionalParses).hasValue(1);
      assertThatThrownBy(
              () ->
                  ClientCalls.blockingUnaryCall(
                      noCertificate,
                      ordinary,
                      CallOptions.DEFAULT.withDeadlineAfter(3, TimeUnit.SECONDS),
                      new byte[0]))
          .satisfies(failure -> assertThat(Status.fromThrowable(failure).isOk()).isFalse());
      assertThat(ordinaryParses).hasValue(1);
    } finally {
      channel.shutdownNow();
      noCertificate.shutdownNow();
      server.shutdownNow();
      assertThat(channel.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
      assertThat(noCertificate.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
      assertThat(server.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  private static void assertRejected(
      ManagedChannel channel,
      MethodDescriptor<byte[], byte[]> method,
      CallOptions options,
      byte[] bytes,
      Status.Code code) {
    assertThatThrownBy(() -> ClientCalls.blockingUnaryCall(channel, method, options, bytes))
        .satisfies(failure -> assertThat(Status.fromThrowable(failure).getCode()).isEqualTo(code));
  }

  private static int explicitTransportMaximum(GrpcRequestBytePolicy policy) {
    var properties =
        new org.springframework.boot.context.properties.bind.Binder(
                new org.springframework.boot.context.properties.source
                    .MapConfigurationPropertySource(
                    java.util.Map.of(
                        "spring.grpc.server.max-inbound-message-size",
                        policy.transportMaximumBytes() + "B")))
            .bind(
                "spring.grpc.server",
                org.springframework.boot.grpc.server.autoconfigure.GrpcServerProperties.class)
            .get();
    return Math.toIntExact(properties.getMaxInboundMessageSize().toBytes());
  }

  private static BoundedGrpcServerFactory factory(GrpcRequestBytePolicy policy) {
    return new BoundedGrpcServerFactory(
        "127.0.0.1:0",
        List.of(),
        null,
        null,
        TlsServerCredentials.ClientAuth.NONE,
        policy,
        explicitTransportMaximum(policy));
  }

  private static MethodDescriptor<byte[], byte[]> method(String name, AtomicInteger parses) {
    return MethodDescriptor.<byte[], byte[]>newBuilder()
        .setFullMethodName(name)
        .setType(MethodDescriptor.MethodType.UNARY)
        .setRequestMarshaller(BoundedGrpcRequestMarshallerTest.parser(parses))
        .setResponseMarshaller(BoundedGrpcRequestMarshallerTest.parser(new AtomicInteger()))
        .build();
  }

  private static io.grpc.ServerCallHandler<byte[], byte[]> handler() {
    return ServerCalls.asyncUnaryCall(
        (request, observer) -> {
          observer.onNext(new byte[0]);
          observer.onCompleted();
        });
  }

  private static ServerServiceDefinition service(MethodDescriptor<byte[], byte[]> method) {
    return ServerServiceDefinition.builder(
            MethodDescriptor.extractFullServiceName(method.getFullMethodName()))
        .addMethod(method, handler())
        .build();
  }
}
