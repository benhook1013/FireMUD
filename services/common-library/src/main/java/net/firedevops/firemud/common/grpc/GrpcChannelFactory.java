package net.firedevops.firemud.common.grpc;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import java.io.File;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;

/** Builds outbound gRPC channels with FireMUD's standard TLS and keepalive behavior. */
public class GrpcChannelFactory {

  public ManagedChannel buildChannel(
      String target, int defaultPort, CommonGrpcClientProperties properties, boolean keepAlive)
      throws SSLException {
    String resolved = target;
    if (resolved == null || resolved.isBlank()) {
      resolved = "localhost:" + defaultPort;
    }

    String[] parts = resolved.split(":");
    String host = parts[0];
    int port = parts.length > 1 ? Integer.parseInt(parts[1]) : defaultPort;

    if (properties.isPlaintext()) {
      ManagedChannelBuilder<?> builder =
          ManagedChannelBuilder.forAddress(host, port).usePlaintext();
      configureKeepAlive(builder, keepAlive);
      return builder.build();
    }

    var sslBuilder = GrpcSslContexts.forClient().trustManager(new File(properties.getCaCert()));
    if (properties.getCertChain() != null
        && !properties.getCertChain().isBlank()
        && properties.getPrivateKey() != null
        && !properties.getPrivateKey().isBlank()) {
      sslBuilder.keyManager(
          new File(properties.getCertChain()), new File(properties.getPrivateKey()));
    }
    NettyChannelBuilder builder =
        NettyChannelBuilder.forAddress(host, port).sslContext(sslBuilder.build());
    configureKeepAlive(builder, keepAlive);
    return builder.build();
  }

  private void configureKeepAlive(ManagedChannelBuilder<?> builder, boolean keepAlive) {
    if (!keepAlive) {
      return;
    }
    builder
        .keepAliveTime(30, TimeUnit.SECONDS)
        .keepAliveTimeout(5, TimeUnit.SECONDS)
        .keepAliveWithoutCalls(true);
  }
}
