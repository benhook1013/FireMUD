package net.firedevops.firemud.worldmanagement.client;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.Request;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class GrpcGameSessionCanonicalInitialAdmissionOwnerClientTest {
  private static final String NAMESPACE = "firemud";
  private static final UUID TENANT = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID REALM = uuid("22222222-2222-4222-8222-222222222222");
  private static final UUID PLAYABLE_NAMESPACE = uuid("33333333-3333-4333-8333-333333333333");
  private static final UUID INSTANCE = uuid("44444444-4444-4444-8444-444444444444");
  private static final UUID VERSION = uuid("55555555-5555-4555-8555-555555555555");
  private static final UUID HOLD_ID = uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID HOLD_FENCE = uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
  private static final String REQUEST_DIGEST = "a".repeat(64);
  private GrpcGameSessionCanonicalInitialAdmissionOwnerClient client;

  @AfterEach
  void closeClientAndClearTransactions() throws Exception {
    if (client != null) {
      client.close();
      client = null;
    }
    TransactionSynchronizationManager.clear();
  }

  @Test
  void requiresExplicitInitializationBeforeReading(@TempDir Path temporary) throws Exception {
    client = newClient(temporary, new PassingChannelFactory());

    assertThatThrownBy(() -> client.read(holdIdentity(NAMESPACE)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not initialized and available");
  }

  @Test
  void requiresConfiguredNamespaceAndNoAmbientTransactionBeforeRpc(@TempDir Path temporary)
      throws Exception {
    client = newClient(temporary, new PassingChannelFactory());
    client.init();

    assertThatThrownBy(() -> client.read(holdIdentity("other")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("configured workload namespace");
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThatThrownBy(() -> client.read(holdIdentity(NAMESPACE)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no ambient World transaction");
  }

  @Test
  void requiresReadableFileBackedWorkloadMtls(@TempDir Path temporary) throws Exception {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    CommonGrpcClientProperties plaintext = tlsProperties(temporary);
    plaintext.setPlaintext(true);

    assertThatThrownBy(
            () ->
                new GrpcGameSessionCanonicalInitialAdmissionOwnerClient(
                    endpoints, plaintext, new PassingChannelFactory(), NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("require workload mTLS");
  }

  private static GrpcGameSessionCanonicalInitialAdmissionOwnerClient newClient(
      Path temporary, GrpcChannelFactory channelFactory) throws IOException {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    endpoints.setGameSessionService("game-session-service:6565");
    return new GrpcGameSessionCanonicalInitialAdmissionOwnerClient(
        endpoints, tlsProperties(temporary), channelFactory, NAMESPACE);
  }

  private static CommonGrpcClientProperties tlsProperties(Path temporary) throws IOException {
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setPlaintext(false);
    tls.setCertChain(Files.writeString(temporary.resolve("client.crt"), "fixture").toString());
    tls.setPrivateKey(Files.writeString(temporary.resolve("client.key"), "fixture").toString());
    tls.setCaCert(Files.writeString(temporary.resolve("ca.crt"), "fixture").toString());
    return tls;
  }

  private static HoldIdentity holdIdentity(String namespace) {
    return new HoldIdentity(
        new Request(
            namespace,
            TENANT,
            "green-hollow",
            REALM,
            PLAYABLE_NAMESPACE,
            "SHARED",
            INSTANCE,
            VERSION,
            7L,
            "gs-initial-admission-17",
            REQUEST_DIGEST,
            InitialAdmissionOrigin.NO_PRIOR_POINTER,
            12L,
            null),
        HOLD_ID,
        HOLD_FENCE);
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private static final class PassingChannelFactory extends GrpcChannelFactory {
    @Override
    public ManagedChannel buildChannel(
        String target, int defaultPort, CommonGrpcClientProperties properties, boolean keepAlive)
        throws SSLException {
      return mock(ManagedChannel.class);
    }
  }
}
