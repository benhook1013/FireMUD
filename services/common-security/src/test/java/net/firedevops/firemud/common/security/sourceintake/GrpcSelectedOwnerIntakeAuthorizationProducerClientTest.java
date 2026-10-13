package net.firedevops.firemud.common.security.sourceintake;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationProducerEvidence;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.security.SessionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Constructor and pre-transport gates only; placeholder files do not prove an mTLS handshake. */
class GrpcSelectedOwnerIntakeAuthorizationProducerClientTest {
  @AfterEach
  void clearAmbientContext() {
    TransactionSynchronizationManager.clear();
    SessionContext.clear();
  }

  @Test
  void requiresReadableFileBackedMtlsAndCanonicalNamespace(@TempDir Path directory)
      throws IOException {
    var channelFactory = mock(GrpcChannelFactory.class);
    var plaintext = tls(directory);
    plaintext.setPlaintext(true);
    assertThatThrownBy(
            () ->
                new GrpcSelectedOwnerIntakeAuthorizationProducerClient(
                    new ServiceEndpointsProperties(), plaintext, channelFactory, "test"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mTLS");

    for (String invalidPath :
        new String[] {
          null,
          "",
          "classpath:credential.pem",
          directory.toString(),
          directory.resolve("missing.pem").toString()
        }) {
      var invalidCertificate = tls(directory);
      invalidCertificate.setCertChain(invalidPath);
      assertThatThrownBy(
              () ->
                  new GrpcSelectedOwnerIntakeAuthorizationProducerClient(
                      new ServiceEndpointsProperties(), invalidCertificate, channelFactory, "test"))
          .isInstanceOf(IllegalArgumentException.class);
    }
    var invalidKey = tls(directory);
    invalidKey.setPrivateKey(directory.resolve("missing-key.pem").toString());
    assertThatThrownBy(
            () ->
                new GrpcSelectedOwnerIntakeAuthorizationProducerClient(
                    new ServiceEndpointsProperties(), invalidKey, channelFactory, "test"))
        .isInstanceOf(IllegalArgumentException.class);
    var invalidCa = tls(directory);
    invalidCa.setCaCert(directory.resolve("missing-ca.pem").toString());
    assertThatThrownBy(
            () ->
                new GrpcSelectedOwnerIntakeAuthorizationProducerClient(
                    new ServiceEndpointsProperties(), invalidCa, channelFactory, "test"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new GrpcSelectedOwnerIntakeAuthorizationProducerClient(
                    new ServiceEndpointsProperties(), tls(directory), channelFactory, "Test"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("namespace");

    verifyNoInteractions(channelFactory);
  }

  @Test
  void rejectsAmbientSqlSynchronizationAndEndUserContextBeforeInitOrTransport(
      @TempDir Path directory) throws IOException {
    var channelFactory = mock(GrpcChannelFactory.class);
    var client =
        new GrpcSelectedOwnerIntakeAuthorizationProducerClient(
            new ServiceEndpointsProperties(), tls(directory), channelFactory, "test");
    var request = request("test");
    try {
      TransactionSynchronizationManager.setActualTransactionActive(true);
      assertThatThrownBy(() -> client.authorize(request, "creator.credential"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("ambient SQL");
      TransactionSynchronizationManager.setActualTransactionActive(false);

      TransactionSynchronizationManager.initSynchronization();
      assertThatThrownBy(() -> client.authorize(request, "creator.credential"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("ambient SQL");
      TransactionSynchronizationManager.clearSynchronization();

      SessionContext.setContext("101", List.of(), Map.of());
      assertThatThrownBy(() -> client.authorize(request, "creator.credential"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("workload-only context");
      SessionContext.clear();

      assertThatThrownBy(() -> client.authorize(request("other"), "creator.credential"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("namespace mismatch");
      verifyNoInteractions(channelFactory);
    } finally {
      client.close();
      TransactionSynchronizationManager.clear();
      SessionContext.clear();
    }
  }

  private static CommonGrpcClientProperties tls(Path directory) throws IOException {
    var tls = new CommonGrpcClientProperties();
    tls.setPlaintext(false);
    tls.setCertChain(
        Files.writeString(directory.resolve("client.crt"), "placeholder cert").toString());
    tls.setPrivateKey(
        Files.writeString(directory.resolve("client.key"), "placeholder key").toString());
    tls.setCaCert(Files.writeString(directory.resolve("ca.crt"), "placeholder CA").toString());
    return tls;
  }

  private static SelectedOwnerIntakeAuthorizationProducerEvidence.Request request(
      String namespace) {
    UUID tenant = UUID.fromString("11111111-1111-4111-8111-111111111111");
    UUID version = UUID.fromString("22222222-2222-4222-8222-222222222222");
    var selected =
        DraftCommitBinding.create(
            new DraftCommitBinding.TargetProof(
                tenant, version, 1L, "tenant", 2L, "tenant", "NEW_GAME_ROW"),
            UUID.fromString("33333333-3333-4333-8333-333333333333"),
            UUID.fromString("44444444-4444-4444-8444-444444444444"),
            "base",
            List.of(
                new DraftCommitBinding.RevisionPayload(
                    "0",
                    UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
                    DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                    "{}")),
            List.of(
                new DraftCommitBinding.AffectedUnit(
                    DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                    "DESIGN",
                    version.toString(),
                    "DESIGN",
                    "effective",
                    "0")));
    return SelectedOwnerIntakeAuthorizationProducerEvidence.Request.create(
        namespace,
        UUID.fromString("55555555-5555-4555-8555-555555555555"),
        DraftCommitBinding.Owner.ENTITY_MANAGEMENT,
        selected);
  }
}
