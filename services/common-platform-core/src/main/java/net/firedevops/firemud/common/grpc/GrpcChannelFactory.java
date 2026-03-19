package net.firedevops.firemud.common.grpc;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import java.io.File;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;

/** Builds outbound gRPC channels with FireMUD's standard TLS and keepalive behavior. */
public class GrpcChannelFactory {

  public ManagedChannel buildChannel(
      String target, int defaultPort, CommonGrpcClientProperties properties, boolean keepAlive)
      throws SSLException {
    return buildChannel(target, defaultPort, properties, keepAlive, null);
  }

  public ManagedChannel buildChannel(
      String target,
      int defaultPort,
      CommonGrpcClientProperties properties,
      boolean keepAlive,
      ResolvedGrpcTlsMaterial tlsMaterial)
      throws SSLException {
    String resolved = target;
    if (resolved == null || resolved.isBlank()) {
      resolved = "localhost:" + defaultPort;
    }

    if (properties.isPlaintext()) {
      ManagedChannelBuilder<?> builder = createPlaintextBuilder(resolved, defaultPort);
      configureKeepAlive(builder, keepAlive);
      return builder.build();
    }

    NettyChannelBuilder builder = createTlsBuilder(resolved, defaultPort);
    var sslBuilder = GrpcSslContexts.forClient();
    if (tlsMaterial != null) {
      try (InputStream certChainStream = tlsMaterial.certChain().openStream();
          InputStream privateKeyStream = tlsMaterial.privateKey().openStream();
          InputStream caCertStream = tlsMaterial.caCert().openStream()) {
        sslBuilder.trustManager(caCertStream).keyManager(certChainStream, privateKeyStream);
        builder = builder.sslContext(sslBuilder.build());
      } catch (Exception e) {
        if (e instanceof SSLException sslException) {
          throw sslException;
        }
        throw new SSLException("Failed to load TLS materials: " + e.getMessage());
      }
    } else {
      sslBuilder.trustManager(new File(properties.getCaCert()));
      if (properties.getCertChain() != null
          && !properties.getCertChain().isBlank()
          && properties.getPrivateKey() != null
          && !properties.getPrivateKey().isBlank()) {
        sslBuilder.keyManager(
            new File(properties.getCertChain()), new File(properties.getPrivateKey()));
      }
      builder = builder.sslContext(sslBuilder.build());
    }
    configureKeepAlive(builder, keepAlive);
    return builder.build();
  }

  private ManagedChannelBuilder<?> createPlaintextBuilder(String resolved, int defaultPort) {
    if (resolved.contains("://")) {
      return ManagedChannelBuilder.forTarget(resolved).usePlaintext();
    }
    String[] parts = resolved.split(":");
    String host = parts[0];
    int port = parts.length > 1 ? Integer.parseInt(parts[1]) : defaultPort;
    return ManagedChannelBuilder.forAddress(host, port).usePlaintext();
  }

  private NettyChannelBuilder createTlsBuilder(String resolved, int defaultPort) {
    if (resolved.contains("://")) {
      return NettyChannelBuilder.forTarget(resolved);
    }
    String[] parts = resolved.split(":");
    String host = parts[0];
    int port = parts.length > 1 ? Integer.parseInt(parts[1]) : defaultPort;
    return NettyChannelBuilder.forAddress(host, port);
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
