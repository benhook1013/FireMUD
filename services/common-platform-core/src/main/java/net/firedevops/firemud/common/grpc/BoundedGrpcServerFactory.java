package net.firedevops.firemud.common.grpc;

import io.grpc.HandlerRegistry;
import io.grpc.Server;
import io.grpc.ServerMethodDefinition;
import io.grpc.ServerServiceDefinition;
import io.grpc.TlsServerCredentials;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;
import org.springframework.grpc.server.ServerBuilderCustomizer;
import org.springframework.grpc.server.ShadedNettyGrpcServerFactory;

/** The production factory admits every method before protobuf parsing. */
public final class BoundedGrpcServerFactory extends ShadedNettyGrpcServerFactory {
  private static final HandlerRegistry CLOSED_FALLBACK =
      new HandlerRegistry() {
        @Override
        public ServerMethodDefinition<?, ?> lookupMethod(String methodName, String authority) {
          return null;
        }
      };
  private final Set<ServerMethodDefinition<?, ?>> guardedMethods =
      Collections.newSetFromMap(new IdentityHashMap<>());
  private final GrpcRequestBytePolicy policy;
  private final int transportMaximum;

  public BoundedGrpcServerFactory(
      String address,
      List<ServerBuilderCustomizer<NettyServerBuilder>> customizers,
      KeyManagerFactory keys,
      TrustManagerFactory trust,
      TlsServerCredentials.ClientAuth clientAuth,
      GrpcRequestBytePolicy policy,
      int transportMaximum) {
    super(address, customizers, keys, trust, clientAuth);
    if (transportMaximum <= 0 || transportMaximum > policy.transportMaximumBytes()) {
      throw new IllegalArgumentException("Transport bound exceeds the closed gRPC request policy");
    }
    this.policy = policy;
    this.transportMaximum = transportMaximum;
  }

  @Override
  protected void configureServices(
      NettyServerBuilder builder, List<ServerServiceDefinition> services) {
    policy.requireRegistered(services);
    List<ServerServiceDefinition> guarded =
        services.stream().map(service -> policy.guard(service, transportMaximum)).toList();
    guardedMethods.clear();
    guarded.forEach(service -> guardedMethods.addAll(service.getMethods()));
    super.configureServices(builder, guarded);
  }

  @Override
  protected void configure(NettyServerBuilder builder, List<ServerServiceDefinition> services) {
    super.configure(builder, services);
    // Customizers cannot leave an alternate mutable lookup or widen the finite ceiling.
    builder.fallbackHandlerRegistry(CLOSED_FALLBACK);
    builder.maxInboundMessageSize(transportMaximum);
  }

  @Override
  public Server createServer() {
    Server server = super.createServer();
    try {
      for (ServerServiceDefinition service : server.getServices()) {
        for (ServerMethodDefinition<?, ?> method : service.getMethods()) {
          // A descriptor or marshaller alone cannot prove its handler still enforces admission.
          if (!guardedMethods.contains(method)) {
            throw new IllegalStateException(
                "Unguarded gRPC method registered: "
                    + method.getMethodDescriptor().getFullMethodName());
          }
        }
      }
      return server;
    } catch (RuntimeException failure) {
      server.shutdownNow();
      throw failure;
    }
  }
}
