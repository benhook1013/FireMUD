package net.firedevops.firemud.accountservice.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.accountservice.client.CanonicalGameplayAdmissionDecisionReadClient.ClientUnavailableException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.RetainedTargetSelector;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayAdmissionDecisionReadServiceGrpc;
import net.firedevops.firemud.gamesession.v1.GetPublishedRealmAdmissionOwnerReadResponse;
import net.firedevops.firemud.gamesession.v1.ListVisiblePublicProductionAdmissionTargetsResponse;
import net.firedevops.firemud.gamesession.v1.ResolvePublishedRealmAdmissionTargetResponse;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Controlled channel/codec tests; no physical mTLS, owner execution or custody is claimed. */
class CanonicalGameplayAdmissionDecisionReadClientTest {
  @TempDir static Path tlsDirectory;
  private static LoopbackMtlsTestPki.Pki pki;
  private final List<CanonicalGameplayAdmissionDecisionReadClient> initializedClients =
      new ArrayList<>();

  @BeforeAll
  static void createFileBackedTlsMaterial() throws Exception {
    pki =
        LoopbackMtlsTestPki.create(
            tlsDirectory,
            "Controlled canonical discovery client CA",
            new LoopbackMtlsTestPki.IdentitySpec(
                "account", "spiffe://firemud/ns/test/sa/account-service", false));
  }

  @AfterEach
  void closeClientsAndClearContexts() throws Exception {
    try {
      for (var client : initializedClients) {
        client.close();
      }
    } finally {
      SessionContext.clear();
      TransactionSynchronizationManager.clear();
    }
  }

  private Fixture fixture() throws Exception {
    return fixture(true);
  }

  private Fixture fixture(boolean initialize) throws Exception {
    Fixture fixture = new Fixture(initialize);
    initializedClients.add(fixture.client);
    return fixture;
  }

  @Test
  void sourceReadsUseTypedUnavailableBeforeInitializationAndAfterClose() throws Exception {
    for (boolean closed : new boolean[] {false, true}) {
      Fixture f = fixture(false);
      if (closed) {
        f.client.init();
        f.client.close();
      }

      assertThatThrownBy(
              () -> f.client.listVisiblePublicProductionAdmissionTargets(UUID.randomUUID()))
          .isInstanceOf(ClientUnavailableException.class);
      assertThatThrownBy(() -> f.client.resolvePublishedRealmAdmissionTarget(f.selector))
          .isInstanceOf(ClientUnavailableException.class);
      assertThatThrownBy(() -> f.client.readPublishedRealmAdmissionOwner(f.strictSelector()))
          .isInstanceOf(ClientUnavailableException.class);
      verifyNoInteractions(f.stub);
    }
  }

  @Test
  void discoveryUsesExactFourMiBBoundAndFiniteDeadlineAndAcceptsAuthenticatedEmptyCollection()
      throws Exception {
    Fixture f = fixture();
    UUID readId = UUID.randomUUID();
    var expected =
        new CanonicalGameplayAdmissionDecisionReadGrpcCodec.DiscoveryRequest(readId, "test");
    var request = CanonicalGameplayAdmissionDecisionReadGrpcCodec.toDiscoveryRequest(expected);
    when(f.stub.listVisiblePublicProductionAdmissionTargets(request))
        .thenReturn(
            CanonicalGameplayAdmissionDecisionReadGrpcCodec.toDiscoveryResponse(
                expected, List.of()));
    var result = f.client.listVisiblePublicProductionAdmissionTargets(readId);
    assertThat(result.request()).isEqualTo(expected);
    assertThat(result.targets()).isEmpty();
    verify(f.stub).withMaxInboundMessageSize(4 * 1024 * 1024);
    verify(f.stub).withDeadlineAfter(5L, TimeUnit.SECONDS);
    verify(f.stub).listVisiblePublicProductionAdmissionTargets(request);
  }

  @Test
  void discoveryNeverConvertsTransportOrMalformedResponseIntoEmptySuccess() throws Exception {
    for (var status :
        List.of(Status.UNAVAILABLE, Status.DEADLINE_EXCEEDED, Status.RESOURCE_EXHAUSTED)) {
      Fixture f = fixture();
      when(f.stub.listVisiblePublicProductionAdmissionTargets(any()))
          .thenThrow(status.asRuntimeException());
      assertThatThrownBy(
              () -> f.client.listVisiblePublicProductionAdmissionTargets(UUID.randomUUID()))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(Status.fromThrowable(failure).getCode()).isEqualTo(status.getCode()));
    }
    Fixture f = fixture();
    when(f.stub.listVisiblePublicProductionAdmissionTargets(any()))
        .thenReturn(ListVisiblePublicProductionAdmissionTargetsResponse.getDefaultInstance());
    assertThatThrownBy(
            () -> f.client.listVisiblePublicProductionAdmissionTargets(UUID.randomUUID()))
        .isInstanceOf(IllegalStateException.class)
        .isNotInstanceOf(ClientUnavailableException.class);
  }

  @Test
  void discoveryRefusesPlayerInternalAndAmbientSqlContextsBeforeTransport() throws Exception {
    Fixture f = fixture();
    SessionContext.setContext("account", List.of(), Map.of());
    assertThatThrownBy(
            () -> f.client.listVisiblePublicProductionAdmissionTargets(UUID.randomUUID()))
        .isInstanceOf(IllegalStateException.class)
        .isNotInstanceOf(ClientUnavailableException.class);
    SessionContext.setContext(null, List.of(), Map.of(), true, "account-service", "instance");
    assertThatThrownBy(
            () -> f.client.listVisiblePublicProductionAdmissionTargets(UUID.randomUUID()))
        .isInstanceOf(IllegalStateException.class)
        .isNotInstanceOf(ClientUnavailableException.class);
    SessionContext.clear();
    TransactionSynchronizationManager.initSynchronization();
    assertThatThrownBy(
            () -> f.client.listVisiblePublicProductionAdmissionTargets(UUID.randomUUID()))
        .isInstanceOf(IllegalStateException.class)
        .isNotInstanceOf(ClientUnavailableException.class);
    TransactionSynchronizationManager.clear();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThatThrownBy(
            () -> f.client.listVisiblePublicProductionAdmissionTargets(UUID.randomUUID()))
        .isInstanceOf(IllegalStateException.class)
        .isNotInstanceOf(ClientUnavailableException.class);
    verifyNoInteractions(f.stub);
  }

  @Test
  void resolverUsesItsOwnFiniteDeadlineAndResponseBoundAndReturnsOnlyDecodedOwnerResult()
      throws Exception {
    Fixture f = fixture();
    var request =
        CanonicalGameplayAdmissionDecisionReadGrpcCodec.toTargetResolutionRequest(f.selector);
    var response = ResolvePublishedRealmAdmissionTargetResponse.getDefaultInstance();
    var result =
        mock(CanonicalGameplayAdmissionDecisionReadGrpcCodec.ResolvedPublishedTarget.class);
    when(f.stub.resolvePublishedRealmAdmissionTarget(request)).thenReturn(response);
    try (var codec = mockStatic(CanonicalGameplayAdmissionDecisionReadGrpcCodec.class)) {
      codec
          .when(
              () ->
                  CanonicalGameplayAdmissionDecisionReadGrpcCodec.toTargetResolutionRequest(
                      f.selector))
          .thenReturn(request);
      codec
          .when(
              () ->
                  CanonicalGameplayAdmissionDecisionReadGrpcCodec.fromTargetResolutionResponse(
                      f.selector, response))
          .thenReturn(result);
      assertThat(f.client.resolvePublishedRealmAdmissionTarget(f.selector)).isSameAs(result);
    }
    verify(f.stub).withDeadlineAfter(5L, TimeUnit.SECONDS);
    verify(f.stub)
        .withMaxInboundMessageSize(
            CanonicalGameplayAdmissionDecisionReadGrpcCodec
                .MAX_TARGET_RESOLUTION_RESPONSE_WIRE_BYTES);
  }

  @Test
  void publishedReadRetainsItsFullPurposeBoundAndFiniteCompleteSourceDeadline() throws Exception {
    Fixture f = fixture();
    var selector = f.strictSelector();
    var request = CanonicalGameplayAdmissionDecisionReadGrpcCodec.toPublishedRealmRequest(selector);
    var parsed =
        CanonicalGameplayAdmissionDecisionReadGrpcCodec.parsePublishedRealmRequest(request);
    var response = GetPublishedRealmAdmissionOwnerReadResponse.getDefaultInstance();
    var result = mock(CanonicalGameplayAdmissionDecisionReadGrpcCodec.PublishedRealmEvidence.class);
    when(f.stub.getPublishedRealmAdmissionOwnerRead(request)).thenReturn(response);
    try (var codec = mockStatic(CanonicalGameplayAdmissionDecisionReadGrpcCodec.class)) {
      codec
          .when(
              () ->
                  CanonicalGameplayAdmissionDecisionReadGrpcCodec.toPublishedRealmRequest(selector))
          .thenReturn(request);
      codec
          .when(
              () ->
                  CanonicalGameplayAdmissionDecisionReadGrpcCodec.parsePublishedRealmRequest(
                      request))
          .thenReturn(parsed);
      codec
          .when(
              () ->
                  CanonicalGameplayAdmissionDecisionReadGrpcCodec.fromPublishedRealmResponse(
                      parsed, response))
          .thenReturn(result);
      assertThat(f.client.readPublishedRealmAdmissionOwner(selector)).isSameAs(result);
    }
    verify(f.stub).withDeadlineAfter(30L, TimeUnit.SECONDS);
    verify(f.stub)
        .withMaxInboundMessageSize(
            CanonicalGameplayAdmissionDecisionReadGrpcCodec
                .MAX_PUBLISHED_REALM_RESPONSE_WIRE_BYTES);
  }

  @Test
  void timeoutAndUnavailableAreNeverReinterpretedAsAbsence() throws Exception {
    for (var status : List.of(Status.UNAVAILABLE, Status.DEADLINE_EXCEEDED)) {
      Fixture f = fixture();
      when(f.stub.resolvePublishedRealmAdmissionTarget(any()))
          .thenThrow(status.asRuntimeException());
      assertThatThrownBy(() -> f.client.resolvePublishedRealmAdmissionTarget(f.selector))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(Status.fromThrowable(failure).getCode()).isEqualTo(status.getCode()));
      when(f.stub.getPublishedRealmAdmissionOwnerRead(any()))
          .thenThrow(status.asRuntimeException());
      assertThatThrownBy(() -> f.client.readPublishedRealmAdmissionOwner(f.strictSelector()))
          .isInstanceOf(StatusRuntimeException.class);
    }
  }

  @Test
  void emptyResponsesAreContractFailuresRatherThanMissingTargets() throws Exception {
    Fixture f = fixture();
    when(f.stub.resolvePublishedRealmAdmissionTarget(any()))
        .thenReturn(ResolvePublishedRealmAdmissionTargetResponse.getDefaultInstance());
    when(f.stub.getPublishedRealmAdmissionOwnerRead(any()))
        .thenReturn(GetPublishedRealmAdmissionOwnerReadResponse.getDefaultInstance());
    assertThatThrownBy(() -> f.client.resolvePublishedRealmAdmissionTarget(f.selector))
        .isInstanceOf(IllegalStateException.class)
        .isNotInstanceOf(ClientUnavailableException.class);
    assertThatThrownBy(() -> f.client.readPublishedRealmAdmissionOwner(f.strictSelector()))
        .isInstanceOf(IllegalStateException.class)
        .isNotInstanceOf(ClientUnavailableException.class);
  }

  @Test
  void callerContextOrAmbientSqlRejectsBeforeTransport() throws Exception {
    Fixture f = fixture();
    SessionContext.setContext("account", List.of(), Map.of());
    assertThatThrownBy(() -> f.client.resolvePublishedRealmAdmissionTarget(f.selector))
        .isInstanceOf(IllegalStateException.class)
        .isNotInstanceOf(ClientUnavailableException.class);
    assertThatThrownBy(() -> f.client.readPublishedRealmAdmissionOwner(f.strictSelector()))
        .isInstanceOf(IllegalStateException.class)
        .isNotInstanceOf(ClientUnavailableException.class);
    SessionContext.clear();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThatThrownBy(() -> f.client.resolvePublishedRealmAdmissionTarget(f.selector))
        .isInstanceOf(IllegalStateException.class)
        .isNotInstanceOf(ClientUnavailableException.class);
    assertThatThrownBy(() -> f.client.readPublishedRealmAdmissionOwner(f.strictSelector()))
        .isInstanceOf(IllegalStateException.class)
        .isNotInstanceOf(ClientUnavailableException.class);
    verifyNoInteractions(f.stub);
  }

  private static final class Fixture {
    private final CanonicalGameplayAdmissionDecisionReadServiceGrpc
            .CanonicalGameplayAdmissionDecisionReadServiceBlockingStub
        stub =
            mock(
                CanonicalGameplayAdmissionDecisionReadServiceGrpc
                    .CanonicalGameplayAdmissionDecisionReadServiceBlockingStub.class);
    private final RetainedTargetSelector selector =
        new RetainedTargetSelector(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "test",
            UUID.randomUUID(),
            "tenant",
            "earth",
            UUID.randomUUID(),
            "main",
            UUID.randomUUID(),
            "SHARED",
            UUID.randomUUID(),
            3,
            5);
    private final CanonicalGameplayAdmissionDecisionReadClient client;

    private Fixture(boolean initialize) throws Exception {
      var tls = pki.clientProperties(tlsDirectory, pki.identity("account"));
      var channels = mock(GrpcChannelFactory.class);
      when(channels.buildChannel(
              any(String.class), anyInt(), any(CommonGrpcClientProperties.class), eq(true)))
          .thenReturn(mock(ManagedChannel.class));
      client =
          new CanonicalGameplayAdmissionDecisionReadClient(
              new ServiceEndpointsProperties(), tls, channels, "test");
      // Exercise the real explicit initialization/close lifecycle, then supply a controlled stub.
      // Neither the mocked channel nor these request-mapping cases prove a TLS handshake.
      if (initialize) {
        client.init();
        Field field = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("stub");
        field.setAccessible(true);
        field.set(client, stub);
      }
      when(stub.withDeadlineAfter(anyLong(), eq(TimeUnit.SECONDS))).thenReturn(stub);
      when(stub.withMaxInboundMessageSize(anyInt())).thenReturn(stub);
    }

    private CanonicalGameplayRosterOwnerReadEvidence.Request strictSelector() {
      return new CanonicalGameplayRosterOwnerReadEvidence.Request(
          selector.readRequestId(),
          selector.accountId(),
          "test",
          selector.tenantId(),
          selector.worldSlug(),
          selector.realmId(),
          selector.realmSlug(),
          selector.playableStateNamespaceId(),
          "SHARED",
          selector.gameInstanceId(),
          UUID.randomUUID(),
          selector.catalogRevision(),
          selector.pointerVersion(),
          7);
    }
  }
}
