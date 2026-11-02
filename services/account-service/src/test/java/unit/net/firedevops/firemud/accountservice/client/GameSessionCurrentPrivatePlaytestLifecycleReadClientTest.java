package unit.net.firedevops.firemud.accountservice.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.client.GameSessionCurrentPrivatePlaytestLifecycleReadClient;
import net.firedevops.firemud.accountservice.testsupport.CurrentPrivatePlaytestReadTransportFixture;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamesession.CurrentPrivatePlaytestLifecycleReadEvidence.Scope;
import net.firedevops.firemud.common.gamesession.CurrentPrivatePlaytestLifecycleReadGrpcCodec;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.v1.ReadCurrentPrivatePlaytestLifecycleResponse;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Actual Account client against a controlled native mTLS GS transport; no GS SQL proof. */
class GameSessionCurrentPrivatePlaytestLifecycleReadClientTest {
  @TempDir static Path directory;
  private static LoopbackMtlsTestPki.Pki pki;

  @BeforeAll
  static void createPki() throws Exception {
    pki = CurrentPrivatePlaytestReadTransportFixture.createPki(directory);
  }

  @AfterEach
  void clearContext() {
    SessionContext.clear();
    TransactionSynchronizationManager.clear();
  }

  @Test
  void exactSameNamespacePeerProducesOpaqueCorrelatedFiniteReadAndTruthfulAbsence()
      throws Exception {
    Scope scope = scope();
    try (var transport = new CurrentPrivatePlaytestReadTransportFixture(pki, directory, "gs")) {
      var snapshot =
          CurrentPrivatePlaytestReadTransportFixture.snapshot(
              scope, UUID.randomUUID(), Instant.now().plusSeconds(300), Instant.now());
      transport.current(Optional.of(snapshot));
      var verified = transport.client().read(scope);
      assertThat(verified.evidence().current()).contains(snapshot);
      assertThat(verified.evidence().request().scope()).isEqualTo(scope);
      assertThat(transport.finiteDeadlineObserved()).isTrue();
      UUID firstReadId = verified.evidence().request().readRequestId();
      transport.current(Optional.empty());
      var absent = transport.client().read(scope);
      assertThat(absent.evidence().current()).isEmpty();
      assertThat(absent.evidence().request().readRequestId()).isNotEqualTo(firstReadId);
      assertThat(
              GameSessionCurrentPrivatePlaytestLifecycleReadClient.Verified.class
                  .getDeclaredConstructors())
          .allSatisfy(
              constructor -> assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue());
    }
  }

  @Test
  void trustedWrongWorkloadOrNamespaceCannotMintVerifiedEvidence() throws Exception {
    for (String server : List.of("wrong-workload", "wrong-namespace")) {
      try (var transport = new CurrentPrivatePlaytestReadTransportFixture(pki, directory, server)) {
        assertThatThrownBy(() -> transport.client().read(scope()))
            .isInstanceOf(RuntimeException.class);
        assertThat(transport.calls()).isZero();
      }
    }
  }

  @Test
  void changedCorrelationMalformedAndOverLimitResponsesFailClosed() throws Exception {
    try (var transport = new CurrentPrivatePlaytestReadTransportFixture(pki, directory, "gs")) {
      transport.respond(
          request ->
              CurrentPrivatePlaytestLifecycleReadGrpcCodec.toResponse(
                  new CurrentPrivatePlaytestLifecycleReadGrpcCodec.Request(
                      UUID.randomUUID(), request.scope()),
                  Optional.empty()));
      assertThatThrownBy(() -> transport.client().read(scope()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("invalid");
      transport.respond(
          request ->
              ReadCurrentPrivatePlaytestLifecycleResponse.newBuilder()
                  .setCanonicalEvidence(ByteString.copyFromUtf8("{}"))
                  .build());
      assertThatThrownBy(() -> transport.client().read(scope()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("invalid");
      transport.respond(
          request ->
              ReadCurrentPrivatePlaytestLifecycleResponse.newBuilder()
                  .setCanonicalEvidence(ByteString.copyFrom(new byte[4097]))
                  .build());
      assertThatThrownBy(() -> transport.client().read(scope()))
          .isInstanceOf(RuntimeException.class);
    }
  }

  @Test
  void ambientSqlSynchronizationWrongNamespaceAndEndUserContextRefuseBeforeTransport()
      throws Exception {
    try (var transport = new CurrentPrivatePlaytestReadTransportFixture(pki, directory, "gs")) {
      TransactionSynchronizationManager.setActualTransactionActive(true);
      assertThatThrownBy(() -> transport.client().read(scope()))
          .isInstanceOf(IllegalStateException.class);
      TransactionSynchronizationManager.clear();
      TransactionSynchronizationManager.initSynchronization();
      assertThatThrownBy(() -> transport.client().read(scope()))
          .isInstanceOf(IllegalStateException.class);
      TransactionSynchronizationManager.clear();
      SessionContext.setContext(UUID.randomUUID().toString(), List.of(), Map.of());
      assertThatThrownBy(() -> transport.client().read(scope()))
          .isInstanceOf(IllegalStateException.class);
      SessionContext.clear();
      assertThatThrownBy(
              () ->
                  transport
                      .client()
                      .read(new Scope("another-namespace", UUID.randomUUID(), "world", "realm")))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(transport.calls()).isZero();
    }
  }

  @Test
  void plaintextCompositionIsUnavailable() {
    var plaintext = new CommonGrpcClientProperties();
    plaintext.setPlaintext(true);
    assertThatThrownBy(
            () ->
                new GameSessionCurrentPrivatePlaytestLifecycleReadClient(
                    new ServiceEndpointsProperties(),
                    plaintext,
                    new GrpcChannelFactory(),
                    CurrentPrivatePlaytestReadTransportFixture.NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mTLS");
  }

  private static Scope scope() {
    return new Scope(
        CurrentPrivatePlaytestReadTransportFixture.NAMESPACE, UUID.randomUUID(), "world", "realm");
  }
}
