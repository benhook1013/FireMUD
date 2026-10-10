package net.firedevops.firemud.accountservice.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StartSessionReservationEvidenceClientTest {
  private static final String REQUEST_ID = "start-session-βeta";
  private static final UUID ACTOR_ID = UUID.fromString("1ea6eb2c-e211-4f4f-a812-14909b9a72b8");
  private static final UUID TENANT_ID = UUID.fromString("5212125e-7d89-4c51-b3a0-bef10e04ccda");
  private static final UUID TARGET_ACCOUNT_ID =
      UUID.fromString("b2a1a560-5e62-4f61-98bf-dae49ba37a38");
  private static final UUID RESERVATION_OWNER_ID =
      UUID.fromString("44997fae-a4c4-45de-8d4f-5e75ad233f0f");
  private static final UUID RECOVERY_OWNER_ID =
      UUID.fromString("c8190808-e325-4d8a-9702-fbf5a53d436d");
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2027-01-15T08:00:00Z"), ZoneOffset.UTC);
  private static final String LOGGING_ADMIN_TARGET = "logging-admin.internal:6565";

  @Test
  void derivesTheExactLoggingPeerFromTheTupleNamespace() {
    assertThat(StartSessionReservationEvidenceClient.loggingAdminServerUri("world-runtime"))
        .isEqualTo("spiffe://firemud/ns/world-runtime/sa/logging-admin-service");
    assertThatThrownBy(
            () -> StartSessionReservationEvidenceClient.loggingAdminServerUri("bad/namespace"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("namespace");
  }

  @Test
  void invalidInputIsRejectedBeforeAnyTransportUse() {
    var channelFactory = mock(GrpcChannelFactory.class);
    var client = newClient(channelFactory, plaintextProperties());
    var tuple = tuple();

    assertThatThrownBy(
            () ->
                client.readCurrentClaimEvidence(
                    null,
                    RESERVATION_OWNER_ID,
                    1L,
                    RESERVATION_OWNER_ID,
                    1L,
                    StartSessionReservationEvidenceClient.Purpose.ISSUE))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () ->
                client.readCurrentClaimEvidence(
                    tuple,
                    RESERVATION_OWNER_ID,
                    0L,
                    RESERVATION_OWNER_ID,
                    1L,
                    StartSessionReservationEvidenceClient.Purpose.ISSUE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                client.readCurrentClaimEvidence(
                    tuple,
                    RESERVATION_OWNER_ID,
                    1L,
                    RECOVERY_OWNER_ID,
                    3L,
                    StartSessionReservationEvidenceClient.Purpose.ISSUE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                client.readCurrentClaimEvidence(
                    tuple,
                    RESERVATION_OWNER_ID,
                    1L,
                    RESERVATION_OWNER_ID,
                    1L,
                    StartSessionReservationEvidenceClient.Purpose.RECOVER))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(channelFactory);
  }

  @Test
  void usesConfiguredLoggingEndpointTlsChannelAndStubCustomizer(@TempDir Path directory)
      throws Exception {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    endpoints.setLoggingAdminService(LOGGING_ADMIN_TARGET);
    CommonGrpcClientProperties tls = tlsProperties(directory);
    GrpcChannelFactory channelFactory = spy(new GrpcChannelFactory());
    var customizer = new AtomicInteger();
    BlockingGrpcStubCustomizer stubCustomizer =
        new BlockingGrpcStubCustomizer() {
          @Override
          public <T extends io.grpc.stub.AbstractStub<T>> T customize(T stub) {
            customizer.incrementAndGet();
            return stub;
          }
        };
    var client =
        new StartSessionReservationEvidenceClient(
            endpoints, tls, channelFactory, stubCustomizer, CLOCK);

    try {
      client.init();

      verify(channelFactory)
          .buildChannel(
              eq(LOGGING_ADMIN_TARGET), eq(6565), any(CommonGrpcClientProperties.class), eq(true));
      assertThat(customizer).hasValue(1);
    } finally {
      client.close();
    }
  }

  private static StartSessionReservationEvidenceClient newClient(
      GrpcChannelFactory channelFactory, CommonGrpcClientProperties tlsProperties) {
    return new StartSessionReservationEvidenceClient(
        new ServiceEndpointsProperties(),
        tlsProperties,
        channelFactory,
        BlockingGrpcStubCustomizer.noop(),
        CLOCK);
  }

  private static CommonGrpcClientProperties plaintextProperties() {
    var tls = new CommonGrpcClientProperties();
    tls.setPlaintext(true);
    return tls;
  }

  private static StartSessionPreAuthorizationReservationTuple tuple() {
    var action =
        new StartSessionOperatorAction(
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
            new StartSessionOperatorAction.Scope(TENANT_ID, "world-runtime"),
            new StartSessionOperatorAction.Target(17L, TARGET_ACCOUNT_ID),
            StartSessionOperatorAction.ExpectedVersion.ABSENT,
            new StartSessionOperatorAction.Mutation(StartSessionOperatorAction.ClientIp.absent()),
            "reservation evidence client test");
    return StartSessionPreAuthorizationReservationTuple.createHuman(REQUEST_ID, ACTOR_ID, action);
  }

  private static CommonGrpcClientProperties tlsProperties(Path directory) throws Exception {
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain(copyCertificateResource("dev-cert.pem", directory).toString());
    tls.setPrivateKey(copyCertificateResource("dev-key.pem", directory).toString());
    tls.setCaCert(copyCertificateResource("dev-ca.pem", directory).toString());
    return tls;
  }

  private static Path copyCertificateResource(String fileName, Path directory) throws Exception {
    Path destination = directory.resolve(fileName);
    try (InputStream source =
        StartSessionReservationEvidenceClientTest.class.getResourceAsStream("/certs/" + fileName)) {
      if (source == null) {
        throw new IllegalStateException("Missing shared TLS test resource: " + fileName);
      }
      Files.copy(source, destination);
    }
    return destination;
  }
}
