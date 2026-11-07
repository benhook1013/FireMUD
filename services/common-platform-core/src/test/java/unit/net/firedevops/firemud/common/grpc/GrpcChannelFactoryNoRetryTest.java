package net.firedevops.firemud.common.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import javax.net.ssl.SSLException;
import org.junit.jupiter.api.Test;

class GrpcChannelFactoryNoRetryTest {
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
