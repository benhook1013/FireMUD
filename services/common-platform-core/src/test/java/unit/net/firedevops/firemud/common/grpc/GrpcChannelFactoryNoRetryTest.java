package net.firedevops.firemud.common.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GrpcChannelFactoryNoRetryTest {
  @TempDir static Path temporaryDirectory;
  private static LoopbackMtlsTestPki.Pki pki;

  @BeforeAll
  static void createPki() throws Exception {
    pki =
        LoopbackMtlsTestPki.create(
            temporaryDirectory,
            "No-retry channel test CA",
            new LoopbackMtlsTestPki.IdentitySpec(
                "gateway-client", "spiffe://firemud/ns/retry-test/sa/spring-cloud-gateway", false));
  }

  @Test
  void dedicatedTlsBuilderDisablesRetriesBeforeBuilding() throws Exception {
    NettyChannelBuilder builder = spy(NettyChannelBuilder.forAddress("localhost", 6565));
    GrpcChannelFactory factory = tlsFactoryReturning(builder);
    var properties = pki.clientProperties(temporaryDirectory, pki.identity("gateway-client"));
    ManagedChannel channel =
        factory.buildNonRetryingChannel("localhost:6565", 6565, properties, true);
    try {
      var order = inOrder(builder);
      order.verify(builder).disableRetry();
      order.verify(builder).build();
    } finally {
      channel.shutdownNow();
      assertThat(channel.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void existingTlsBuilderDoesNotChangeItsRetryBehavior() throws Exception {
    NettyChannelBuilder builder = spy(NettyChannelBuilder.forAddress("localhost", 6565));
    GrpcChannelFactory factory = tlsFactoryReturning(builder);
    var properties = pki.clientProperties(temporaryDirectory, pki.identity("gateway-client"));
    ManagedChannel channel = factory.buildChannel("localhost:6565", 6565, properties, false);
    try {
      verify(builder, never()).disableRetry();
      verify(builder).build();
    } finally {
      channel.shutdownNow();
      assertThat(channel.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
    }
  }

  private static GrpcChannelFactory tlsFactoryReturning(NettyChannelBuilder builder) {
    return new GrpcChannelFactory() {
      @Override
      protected NettyChannelBuilder createTlsBuilder(String resolved, int defaultPort) {
        return builder;
      }
    };
  }

  @Test
  void dedicatedChannelBuilderDisablesRetriesBeforeBuilding() throws SSLException {
    ManagedChannel channel = mock(ManagedChannel.class);
    ManagedChannelBuilder<?> builder = mock(ManagedChannelBuilder.class);
    when(builder.build()).thenReturn(channel);
    GrpcChannelFactory factory = factoryReturning(builder);
    CommonGrpcClientProperties properties = plaintextProperties();

    ManagedChannel built =
        factory.buildNonRetryingChannel("checkout-account:6565", 6565, properties, false);

    assertThat(built).isSameAs(channel);
    var order = inOrder(builder);
    order.verify(builder).disableRetry();
    order.verify(builder).build();
  }

  @Test
  void existingChannelBuilderDoesNotChangeItsRetryBehavior() throws SSLException {
    ManagedChannel channel = mock(ManagedChannel.class);
    ManagedChannelBuilder<?> builder = mock(ManagedChannelBuilder.class);
    when(builder.build()).thenReturn(channel);
    GrpcChannelFactory factory = factoryReturning(builder);

    ManagedChannel built =
        factory.buildChannel("existing-service:6565", 6565, plaintextProperties(), false);

    assertThat(built).isSameAs(channel);
    verify(builder, never()).disableRetry();
    verify(builder).build();
  }

  private static GrpcChannelFactory factoryReturning(ManagedChannelBuilder<?> builder) {
    return new GrpcChannelFactory() {
      @Override
      protected ManagedChannelBuilder<?> createPlaintextBuilder(String resolved, int defaultPort) {
        return builder;
      }
    };
  }

  private static CommonGrpcClientProperties plaintextProperties() {
    CommonGrpcClientProperties properties = new CommonGrpcClientProperties();
    properties.setPlaintext(true);
    return properties;
  }
}
