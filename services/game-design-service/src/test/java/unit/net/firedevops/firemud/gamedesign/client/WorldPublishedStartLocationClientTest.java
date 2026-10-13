package net.firedevops.firemud.gamedesign.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Owner adapter tests use explicit transport doubles; they do not prove a physical mTLS call. */
class WorldPublishedStartLocationClientTest {
  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void explicitTransportDoubleRetainsExactRequestEvidenceAndLifecycle() throws Exception {
    var transport =
        mock(net.firedevops.firemud.common.world.WorldPublishedStartLocationClient.class);
    var request = mock(WorldPublishedStartLocationEvidence.Request.class);
    var evidence = mock(WorldPublishedStartLocationEvidence.class);
    when(transport.read(request)).thenReturn(evidence);
    var client = new WorldPublishedStartLocationClient(transport);

    client.init();
    assertThat(client.read(request)).isSameAs(evidence);
    client.close();

    verify(transport).init();
    verify(transport).read(request);
    verify(transport).close();
  }

  @Test
  void refusesCaptureUnderOwnerTransactionOrSynchronization() {
    var transport =
        mock(net.firedevops.firemud.common.world.WorldPublishedStartLocationClient.class);
    var request = mock(WorldPublishedStartLocationEvidence.Request.class);
    var client = new WorldPublishedStartLocationClient(transport);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThatThrownBy(() -> client.read(request)).isInstanceOf(IllegalStateException.class);
    TransactionSynchronizationManager.setActualTransactionActive(false);
    TransactionSynchronizationManager.initSynchronization();
    assertThatThrownBy(() -> client.read(request)).isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(transport);
  }

  @Test
  void propagatesUnavailableTransportDoubleWithoutEvidenceFallback() {
    var transport =
        mock(net.firedevops.firemud.common.world.WorldPublishedStartLocationClient.class);
    var request = mock(WorldPublishedStartLocationEvidence.Request.class);
    var failure = Status.UNAVAILABLE.asRuntimeException();
    when(transport.read(request)).thenThrow(failure);
    assertThatThrownBy(() -> new WorldPublishedStartLocationClient(transport).read(request))
        .isSameAs(failure);
  }

  @Test
  void productionConstructorRejectsPlaintextBeforeOpeningChannel() {
    var tls = new CommonGrpcClientProperties();
    tls.setPlaintext(true);
    var channels = mock(GrpcChannelFactory.class);
    assertThatThrownBy(
            () ->
                new WorldPublishedStartLocationClient(
                    new ServiceEndpointsProperties(), tls, channels, "test"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("workload mTLS");
    verifyNoInteractions(channels);
  }
}
