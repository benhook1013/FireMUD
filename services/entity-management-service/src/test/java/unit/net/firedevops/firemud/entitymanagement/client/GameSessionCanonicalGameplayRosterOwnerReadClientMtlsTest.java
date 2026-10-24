package unit.net.firedevops.firemud.entitymanagement.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.stub.StreamObserver;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.testing.ControlledPlayerCreatedRosterTerminalFixtures;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterRetirementEvidence;
import net.firedevops.firemud.entitymanagement.client.GameSessionCanonicalGameplayRosterOwnerReadClient;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterEntryPolicy;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterTarget;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterOwnerReadServiceGrpc;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterReferenceState;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterRetirementAcknowledgementRequest;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterRetirementAcknowledgementResponse;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterRetirementConfirmationRequest;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterRetirementConfirmationResponse;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterOwnerReadRequest;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterOwnerReadResponse;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceRequest;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceResponse;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import net.firedevops.firemud.test.LoopbackMtlsTestPki.IdentitySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Actual file-backed Entity client transport; owner responses are controlled source evidence. */
class GameSessionCanonicalGameplayRosterOwnerReadClientMtlsTest {
  private static final String NAMESPACE = "gameplay";
  private static final String ENTITY_URI =
      "spiffe://firemud/ns/gameplay/sa/entity-management-service";
  @TempDir static Path directory;
  private static LoopbackMtlsTestPki.Pki pki;
  private final AtomicInteger receivedCalls = new AtomicInteger();
  private final AtomicInteger handlerReads = new AtomicInteger();
  private final AtomicReference<String> callerUri = new AtomicReference<>();
  private final AtomicReference<String> authorization = new AtomicReference<>();
  private final AtomicReference<GetCanonicalGameplayRosterReferenceRequest> observedRequest =
      new AtomicReference<>();
  private final AtomicReference<CanonicalGameplayRosterRetirementAcknowledgementRequest>
      observedAcknowledgement = new AtomicReference<>();
  private final AtomicReference<CanonicalGameplayRosterRetirementConfirmationRequest>
      observedConfirmation = new AtomicReference<>();
  private Server server;

  @BeforeAll
  static void certificates() throws Exception {
    pki =
        LoopbackMtlsTestPki.create(
            directory,
            "Entity roster owner client test CA",
            new IdentitySpec(
                "server", "spiffe://firemud/ns/gameplay/sa/game-session-service", true),
            new IdentitySpec(
                "foreign-server", "spiffe://firemud/ns/other/sa/game-session-service", true),
            new IdentitySpec(
                "wrong-service-server", "spiffe://firemud/ns/gameplay/sa/account-service", true),
            new IdentitySpec("entity", ENTITY_URI, false));
  }

  @AfterEach
  void stop() throws Exception {
    SessionContext.clear();
    if (server != null) {
      server.shutdownNow();
      assertThat(server.awaitTermination(5L, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void exactServerReceivesOriginalRequestAndEntityCertificateWithoutAmbientClaims()
      throws Exception {
    start("server");
    var request = referenceRequest();
    SessionContext.setContext(
        UUID.randomUUID().toString(),
        List.of("platformAdmin"),
        Map.of(),
        true,
        "account-service",
        "ambient-instance");
    try (var client = client()) {
      client.init();
      var response = client.getCanonicalGameplayRosterReference(request);
      assertThat(response.getRequest()).isEqualTo(request);
      assertThat(observedRequest.get()).isEqualTo(request);
      assertThat(handlerReads.get()).isEqualTo(1);
      assertThat(receivedCalls.get()).isEqualTo(1);
      assertThat(callerUri.get()).isEqualTo(ENTITY_URI);
      assertThat(authorization.get()).isNull();
      assertThat(SessionContext.getServiceName()).isEqualTo("account-service");
    } finally {
      SessionContext.clear();
    }
  }

  @Test
  void foreignNamespaceServerCannotReceiveOwnerReadOrRetirementTransfer() throws Exception {
    assertWrongServerCannotReceive("foreign-server");
  }

  @Test
  void sameNamespaceWrongServiceCannotReceiveOwnerReadOrRetirementTransfer() throws Exception {
    assertWrongServerCannotReceive("wrong-service-server");
  }

  private void assertWrongServerCannotReceive(String alias) throws Exception {
    start(alias);
    try (var client = client()) {
      client.init();
      assertThatThrownBy(() -> client.getCanonicalGameplayRosterReference(referenceRequest()))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(Status.fromThrowable(failure).getCode())
                      .isEqualTo(Status.Code.UNAUTHENTICATED));
      assertThatThrownBy(() -> client.getCanonicalGameplayRosterOwnerRead(ownerRequest()))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(Status.fromThrowable(failure).getCode())
                      .isEqualTo(Status.Code.UNAUTHENTICATED));
      var retirement = retirementRequest(referenceRequest());
      var confirmation =
          CanonicalGameplayRosterRetirementEvidence.confirmation(
              retirement,
              CanonicalGameplayRosterRetirementEvidence.response(
                  retirement, UUID.randomUUID(), 1000));
      assertThatThrownBy(() -> client.acknowledgeCanonicalGameplayRosterRetirement(retirement))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(Status.fromThrowable(failure).getCode())
                      .isEqualTo(Status.Code.UNAUTHENTICATED));
      assertThatThrownBy(
              () -> client.confirmCanonicalGameplayRosterRetirementAcknowledgement(confirmation))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(Status.fromThrowable(failure).getCode())
                      .isEqualTo(Status.Code.UNAUTHENTICATED));
      assertThat(observedAcknowledgement.get()).isNull();
      assertThat(observedConfirmation.get()).isNull();
      assertThat(handlerReads.get()).isZero();
      assertThat(receivedCalls.get()).isZero();
      assertThat(observedRequest.get()).isNull();
      assertThat(callerUri.get()).isNull();
    }
  }

  @Test
  void authenticatedRetirementTransferUsesExactBytesAndNoAmbientClaims() throws Exception {
    start("server");
    var request = retirementRequest(referenceRequest());
    SessionContext.setContext(
        UUID.randomUUID().toString(),
        List.of("platformAdmin"),
        Map.of(),
        true,
        "account-service",
        "ambient-instance");
    try (var client = client()) {
      client.init();
      var acknowledgement = client.acknowledgeCanonicalGameplayRosterRetirement(request);
      var confirmation =
          CanonicalGameplayRosterRetirementEvidence.confirmation(request, acknowledgement);
      assertThat(client.confirmCanonicalGameplayRosterRetirementAcknowledgement(confirmation))
          .isEqualTo(CanonicalGameplayRosterRetirementEvidence.confirmationResponse(confirmation));
      assertThat(observedAcknowledgement.get()).isEqualTo(request);
      assertThat(observedConfirmation.get()).isEqualTo(confirmation);
      assertThat(callerUri.get()).isEqualTo(ENTITY_URI);
      assertThat(authorization.get()).isNull();
      assertThat(receivedCalls.get()).isEqualTo(2);
      assertThat(handlerReads.get()).isEqualTo(2);
      assertThat(SessionContext.getServiceName()).isEqualTo("account-service");
      var foreignOriginal =
          request.getOriginalSelection().toBuilder().setTargetNamespace("other").build();
      var foreign = retirementRequest(foreignOriginal);
      assertThatThrownBy(() -> client.acknowledgeCanonicalGameplayRosterRetirement(foreign))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("namespace");
      var foreignConfirmation =
          CanonicalGameplayRosterRetirementEvidence.confirmation(
              foreign,
              CanonicalGameplayRosterRetirementEvidence.response(foreign, UUID.randomUUID(), 1000));
      assertThatThrownBy(
              () ->
                  client.confirmCanonicalGameplayRosterRetirementAcknowledgement(
                      foreignConfirmation))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("namespace");
      assertThat(receivedCalls.get()).isEqualTo(2);
    } finally {
      SessionContext.clear();
    }
  }

  @Test
  void createdOriginalAndRetirementPreserveCompleteTerminalAboveDefaultReceiveLimit()
      throws Exception {
    // Controlled foreign proofs; actual file-backed Entity client and socket mTLS only.
    var fixture =
        ControlledPlayerCreatedRosterTerminalFixtures.withFiveMebibyteAccountProtection(NAMESPACE);
    int defaultReceiveLimit = 4 * 1024 * 1024;
    assertThat(fixture.selected().getTerminal().getOriginalAccountProtection().size())
        .isEqualTo(5 * 1024 * 1024);
    assertThat(fixture.selected().getTerminal().getSerializedSize())
        .isGreaterThan(defaultReceiveLimit);
    assertThat(fixture.original().getSerializedSize()).isGreaterThan(defaultReceiveLimit);
    assertThat(fixture.acknowledgementRequest().getSerializedSize())
        .isGreaterThan(defaultReceiveLimit);
    assertThat(fixture.confirmation().getSerializedSize()).isGreaterThan(defaultReceiveLimit);
    start(
        "server",
        fixture,
        CanonicalGameplayRosterRetirementEvidence.MAX_PLAYER_CREATED_CONFIRMATION_BYTES);
    SessionContext.setContext(
        UUID.randomUUID().toString(),
        List.of("platformAdmin"),
        Map.of(),
        true,
        "account-service",
        "ambient-instance");
    try (var client = client()) {
      client.init();
      // The production client selects its 194 MiB receive bound from this created request.
      var original = client.getCanonicalGameplayRosterReference(fixture.originalRequest());
      assertThat(original).isEqualTo(fixture.original());
      assertThat(
              CanonicalGameplayRosterReferenceEvidence.selectedPlayerCreatedCharacterReceipt(
                      original)
                  .getTerminal()
                  .toByteString())
          .isEqualTo(fixture.selected().getTerminal().toByteString());
      assertThat(observedRequest.get()).isEqualTo(fixture.originalRequest());
      assertThat(authorization.get()).isNull();
      var acknowledgement =
          client.acknowledgeCanonicalGameplayRosterRetirement(fixture.acknowledgementRequest());
      assertThat(acknowledgement).isEqualTo(fixture.acknowledgement());
      assertThat(observedAcknowledgement.get()).isEqualTo(fixture.acknowledgementRequest());
      assertThat(
              observedAcknowledgement
                  .get()
                  .getObservedRetirement()
                  .getCanonicalSelectedPlayerCreatedCharacterReceipt())
          .isEqualTo(fixture.selected().toByteString());
      assertThat(authorization.get()).isNull();
      assertThat(
              client.confirmCanonicalGameplayRosterRetirementAcknowledgement(
                  fixture.confirmation()))
          .isEqualTo(
              CanonicalGameplayRosterRetirementEvidence.confirmationResponse(
                  fixture.confirmation()));
      assertThat(observedConfirmation.get()).isEqualTo(fixture.confirmation());
      assertThat(
              observedConfirmation
                  .get()
                  .getAcknowledgementRequest()
                  .getObservedRetirement()
                  .getCanonicalSelectedPlayerCreatedCharacterReceipt())
          .isEqualTo(fixture.selected().toByteString());
      assertThat(authorization.get()).isNull();
      assertThat(callerUri.get()).isEqualTo(ENTITY_URI);
      assertThat(receivedCalls.get()).isEqualTo(3);
      assertThat(handlerReads.get()).isEqualTo(3);
      assertThat(SessionContext.getServiceName()).isEqualTo("account-service");
    } finally {
      SessionContext.clear();
    }
  }

  private void start(String alias) throws Exception {
    start(alias, null, 0);
  }

  private void start(
      String alias,
      ControlledPlayerCreatedRosterTerminalFixtures.Bundle controlled,
      int maxInboundBytes)
      throws Exception {
    var identity = pki.identity(alias);
    var service =
        new CanonicalGameplayRosterOwnerReadServiceGrpc
            .CanonicalGameplayRosterOwnerReadServiceImplBase() {
          @Override
          public void getCanonicalGameplayRosterReference(
              GetCanonicalGameplayRosterReferenceRequest request,
              StreamObserver<GetCanonicalGameplayRosterReferenceResponse> responseObserver) {
            handlerReads.incrementAndGet();
            observedRequest.set(request);
            callerUri.set(GrpcPeerIdentity.current().uri());
            responseObserver.onNext(
                controlled == null
                    ? CanonicalGameplayRosterReferenceEvidence.response(
                        request, 1L, UUID.fromString("12121212-1212-4212-8212-121212121212"), null)
                    : controlled.original());
            responseObserver.onCompleted();
          }

          @Override
          public void acknowledgeCanonicalGameplayRosterRetirement(
              CanonicalGameplayRosterRetirementAcknowledgementRequest request,
              StreamObserver<CanonicalGameplayRosterRetirementAcknowledgementResponse> observer) {
            handlerReads.incrementAndGet();
            observedAcknowledgement.set(request);
            callerUri.set(GrpcPeerIdentity.current().uri());
            observer.onNext(
                controlled == null
                    ? CanonicalGameplayRosterRetirementEvidence.response(
                        request, UUID.fromString("34343434-3434-4434-8434-343434343434"), 1000)
                    : controlled.acknowledgement());
            observer.onCompleted();
          }

          @Override
          public void confirmCanonicalGameplayRosterRetirementAcknowledgement(
              CanonicalGameplayRosterRetirementConfirmationRequest request,
              StreamObserver<CanonicalGameplayRosterRetirementConfirmationResponse> observer) {
            handlerReads.incrementAndGet();
            observedConfirmation.set(request);
            callerUri.set(GrpcPeerIdentity.current().uri());
            observer.onNext(
                CanonicalGameplayRosterRetirementEvidence.confirmationResponse(request));
            observer.onCompleted();
          }

          @Override
          public void getCanonicalGameplayRosterOwnerRead(
              GetCanonicalGameplayRosterOwnerReadRequest request,
              StreamObserver<GetCanonicalGameplayRosterOwnerReadResponse> responseObserver) {
            handlerReads.incrementAndGet();
            responseObserver.onError(Status.UNAVAILABLE.asRuntimeException());
          }
        };
    ServerInterceptor captureHeaders =
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            receivedCalls.incrementAndGet();
            authorization.set(
                headers.get(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)));
            return next.startCall(call, headers);
          }
        };
    var serverBuilder = NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0));
    if (maxInboundBytes > 0) serverBuilder.maxInboundMessageSize(maxInboundBytes);
    server =
        serverBuilder
            .sslContext(
                GrpcSslContexts.configure(
                        SslContextBuilder.forServer(identity.privateKey(), identity.certificate()))
                    .trustManager(pki.caCertificate())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(
                ServerInterceptors.intercept(
                    service, captureHeaders, new GrpcPeerIdentityInterceptor()))
            .build()
            .start();
  }

  private GameSessionCanonicalGameplayRosterOwnerReadClient client() throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setGameSessionService("127.0.0.1:" + server.getPort());
    return new GameSessionCanonicalGameplayRosterOwnerReadClient(
        endpoints,
        pki.clientProperties(directory, pki.identity("entity")),
        new GrpcChannelFactory(),
        NAMESPACE);
  }

  private static GetCanonicalGameplayRosterReferenceRequest referenceRequest() {
    var target =
        CanonicalGameplayRosterTarget.newBuilder()
            .setTenantUuid(UUID.randomUUID().toString())
            .setRealmUuid(UUID.randomUUID().toString())
            .setGameInstanceUuid(UUID.randomUUID().toString())
            .setCanonicalVersionUuid(UUID.randomUUID().toString())
            .setPlayableStateNamespaceUuid(UUID.randomUUID().toString())
            .setWorldSlug("earth")
            .setRealmSlug("main")
            .setCatalogRevision(7L)
            .setPointerVersion(11L)
            .setActiveWorldEpoch(13L)
            .setPublishedPolicyDigest("a".repeat(64))
            .setPublishedOwnerProofDigest("b".repeat(64))
            .setAdmissionPointerSnapshotDigest("c".repeat(64))
            .setPublishedReleaseBundleRef("release:exact")
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .setEntryPolicy(
                CanonicalGameplayRosterEntryPolicy.CANONICAL_ROSTER_ENTRY_POLICY_PRESEEDED_ONLY)
            .build();
    return CanonicalGameplayRosterReferenceEvidence.request(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), NAMESPACE, target);
  }

  private static CanonicalGameplayRosterRetirementAcknowledgementRequest retirementRequest(
      GetCanonicalGameplayRosterReferenceRequest original) {
    var target = CanonicalGameplayRosterReferenceEvidence.target(original);
    var proof =
        CanonicalGameplayRosterReferenceEvidence.sealRetirement(
            CanonicalGameplayRosterReferenceEvidence.response(original, 1, UUID.randomUUID(), null)
                .toBuilder()
                .setState(
                    CanonicalGameplayRosterReferenceState
                        .CANONICAL_GAMEPLAY_ROSTER_REFERENCE_RETIRED)
                .setTerminalOutcome("DENIED")
                .setRetirementUuid(UUID.randomUUID().toString())
                .setSupersedingPointerDigest("d".repeat(64))
                .setSupersedingPointerVersion(target.getPointerVersion() + 1)
                .setSupersedingCatalogRevision(target.getCatalogRevision())
                .setSupersedingWorldEpoch(target.getActiveWorldEpoch())
                .build());
    return CanonicalGameplayRosterRetirementEvidence.requireAcknowledgement(
        CanonicalGameplayRosterRetirementAcknowledgementRequest.newBuilder()
            .setSchemaVersion(1)
            .setOriginalSelection(original)
            .setObservedRetirement(proof)
            .setObservationUuid(UUID.randomUUID().toString())
            .setAdmissionUuid(UUID.randomUUID().toString())
            .setMinimumRetentionMillis(1000)
            .build());
  }

  private static CanonicalGameplayRosterOwnerReadEvidence.Request ownerRequest() {
    var reference = referenceRequest();
    var target = CanonicalGameplayRosterReferenceEvidence.target(reference);
    return new CanonicalGameplayRosterOwnerReadEvidence.Request(
        UUID.fromString(reference.getOperationUuid()),
        UUID.fromString(reference.getCanonicalAccountUuid()),
        NAMESPACE,
        UUID.fromString(target.getTenantUuid()),
        target.getWorldSlug(),
        UUID.fromString(target.getRealmUuid()),
        target.getRealmSlug(),
        UUID.fromString(target.getPlayableStateNamespaceUuid()),
        "SHARED",
        UUID.fromString(target.getGameInstanceUuid()),
        UUID.fromString(target.getCanonicalVersionUuid()),
        target.getCatalogRevision(),
        target.getPointerVersion(),
        target.getActiveWorldEpoch());
  }
}
