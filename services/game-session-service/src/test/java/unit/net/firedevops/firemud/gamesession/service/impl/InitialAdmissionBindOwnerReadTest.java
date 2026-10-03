package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.service.InitialAdmissionBindHoldBinding;
import net.firedevops.firemud.gamesession.service.InitialAdmissionBindOwnerProof;
import net.firedevops.firemud.gamesession.service.InitialAdmissionBindOwnerProofReader;
import net.firedevops.firemud.gamesession.v1.GetInitialAdmissionBindProofRequest;
import net.firedevops.firemud.gamesession.v1.GetInitialAdmissionBindProofResponse;
import net.firedevops.firemud.gamesession.v1.InitialAdmissionBindOwnerProofOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class InitialAdmissionBindOwnerReadTest {
  private static final String NAMESPACE = "dev";
  private static final String HOLD_ID = "00000000-0000-0000-0000-000000000003";
  private static final String HOLD_FENCE = "00000000-0000-0000-0000-000000000004";
  private static final String REALM_UUID = "00000000-0000-0000-0000-000000000001";
  private static final String PLAYABLE_NAMESPACE_UUID = "00000000-0000-0000-0000-000000000002";
  private static final String REQUEST_ID = "initial-admission-1";
  private static final String REQUEST_DIGEST = "a".repeat(64);

  @AfterEach
  void clearContext() {
    SessionContext.clear();
  }

  @Test
  void exactWorldManagementPeerReadsAndReceivesCompleteTypedOwnerProof() {
    InitialAdmissionBindOwnerProofReader reader = mock(InitialAdmissionBindOwnerProofReader.class);
    InitialAdmissionBindHoldBinding binding = binding();
    when(reader.read(binding)).thenReturn(committedProof());
    GameSessionControlPlaneGrpcService service = service(reader, NAMESPACE);
    AtomicReference<GetInitialAdmissionBindProofResponse> response = new AtomicReference<>();

    runAsWorldManagement(() -> response.set(invoke(service, request())));

    assertEquals("", response.get().getError().getCode());
    assertEquals(
        InitialAdmissionBindOwnerProofOutcome.INITIAL_ADMISSION_BIND_OWNER_PROOF_OUTCOME_COMMITTED,
        response.get().getOutcome());
    assertEquals(HOLD_ID, response.get().getHoldId());
    assertEquals(HOLD_FENCE, response.get().getHoldFence());
    assertEquals("42", response.get().getTenantId());
    assertEquals(REALM_UUID, response.get().getRealmUuid());
    assertEquals(PLAYABLE_NAMESPACE_UUID, response.get().getPlayableStateNamespaceUuid());
    assertEquals(
        PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED, response.get().getPlayableStateScope());
    assertEquals("101", response.get().getGameInstanceId());
    assertEquals("11", response.get().getVersionId());
    assertEquals(7L, response.get().getActiveLifecycleEpoch());
    assertEquals(REQUEST_ID, response.get().getInitialAdmissionRequestId());
    assertEquals(REQUEST_DIGEST, response.get().getRequestDigest());
    assertEquals(true, response.get().getExpectedNoPriorPointer());
    assertEquals(1L, response.get().getExpectedCatalogRevision());
    assertEquals("gs-ledger-100", response.get().getOwnerProofId());
    assertEquals("audit-100", response.get().getPointerAuditId());
    assertEquals(1L, response.get().getPointerVersion());
    assertEquals(REQUEST_DIGEST, response.get().getPointerAuditRequestDigest());
    verify(reader).read(binding);
  }

  @Test
  void refusesToReturnPositiveProofWhenOwnerTupleDiffersFromRequestedHold() {
    InitialAdmissionBindOwnerProofReader reader = mock(InitialAdmissionBindOwnerProofReader.class);
    when(reader.read(binding()))
        .thenReturn(withHoldId(committedProof(), "00000000-0000-0000-0000-000000000099"));
    GameSessionControlPlaneGrpcService service = service(reader, NAMESPACE);

    AtomicReference<GetInitialAdmissionBindProofResponse> response = new AtomicReference<>();
    runAsWorldManagement(() -> response.set(invoke(service, request())));

    assertEquals("INTERNAL", response.get().getError().getCode());
    assertEquals(0, response.get().getOutcomeValue());
    assertEquals("", response.get().getOwnerProofId());
    verify(reader).read(binding());
  }

  @Test
  void rejectsMissingWrongServiceWrongNamespaceAndEmptyConfiguredNamespace() {
    InitialAdmissionBindOwnerProofReader reader = mock(InitialAdmissionBindOwnerProofReader.class);
    GameSessionControlPlaneGrpcService service = service(reader, NAMESPACE);

    var noPeer = invoke(service, request());
    assertEquals("PERMISSION_DENIED", noPeer.getError().getCode());

    AtomicReference<GetInitialAdmissionBindProofResponse> wrongService = new AtomicReference<>();
    runAsPeer(
        "entity-management-service", NAMESPACE, () -> wrongService.set(invoke(service, request())));
    assertEquals("PERMISSION_DENIED", wrongService.get().getError().getCode());

    AtomicReference<GetInitialAdmissionBindProofResponse> wrongNamespace = new AtomicReference<>();
    runAsPeer(
        "world-management-service", "other", () -> wrongNamespace.set(invoke(service, request())));
    assertEquals("PERMISSION_DENIED", wrongNamespace.get().getError().getCode());

    AtomicReference<GetInitialAdmissionBindProofResponse> emptyConfiguration =
        new AtomicReference<>();
    runAsPeer(
        "world-management-service",
        NAMESPACE,
        () -> emptyConfiguration.set(invoke(service(reader, ""), request())));
    assertEquals("PERMISSION_DENIED", emptyConfiguration.get().getError().getCode());

    verifyNoInteractions(reader);
  }

  @Test
  void headerOnlyCallerCannotReadOwnerProof() {
    InitialAdmissionBindOwnerProofReader reader = mock(InitialAdmissionBindOwnerProofReader.class);
    GameSessionControlPlaneGrpcService service = service(reader, NAMESPACE);
    SessionContext.setContext(null, List.of(), Map.of(), true, "world-management-service", "wms-1");

    var response = invoke(service, request());

    assertEquals("PERMISSION_DENIED", response.getError().getCode());
    verifyNoInteractions(reader);
  }

  @Test
  void rejectsUnspecifiedAndUnrecognizedScopeBeforeOwnerRead() {
    InitialAdmissionBindOwnerProofReader reader = mock(InitialAdmissionBindOwnerProofReader.class);
    GameSessionControlPlaneGrpcService service = service(reader, NAMESPACE);
    AtomicReference<GetInitialAdmissionBindProofResponse> unspecified = new AtomicReference<>();
    runAsWorldManagement(
        () ->
            unspecified.set(
                invoke(
                    service,
                    request().toBuilder()
                        .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_UNSPECIFIED)
                        .build())));
    assertEquals("INVALID_ARGUMENT", unspecified.get().getError().getCode());

    AtomicReference<GetInitialAdmissionBindProofResponse> unrecognized = new AtomicReference<>();
    runAsWorldManagement(
        () ->
            unrecognized.set(
                invoke(service, request().toBuilder().setPlayableStateScopeValue(999).build())));
    assertEquals("INVALID_ARGUMENT", unrecognized.get().getError().getCode());
    verifyNoInteractions(reader);
  }

  private static InitialAdmissionBindOwnerProof committedProof() {
    return new InitialAdmissionBindOwnerProof(
        InitialAdmissionBindOwnerProof.Outcome.COMMITTED,
        HOLD_ID,
        HOLD_FENCE,
        42L,
        REALM_UUID,
        PLAYABLE_NAMESPACE_UUID,
        "SHARED",
        101L,
        11L,
        7L,
        REQUEST_ID,
        REQUEST_DIGEST,
        true,
        1L,
        "gs-ledger-100",
        "audit-100",
        1L,
        REQUEST_DIGEST,
        false);
  }

  private static InitialAdmissionBindOwnerProof withHoldId(
      InitialAdmissionBindOwnerProof proof, String holdId) {
    return new InitialAdmissionBindOwnerProof(
        proof.outcome(),
        holdId,
        proof.holdFence(),
        proof.tenantId(),
        proof.realmUuid(),
        proof.playableStateNamespaceUuid(),
        proof.playableStateScope(),
        proof.gameInstanceId(),
        proof.versionId(),
        proof.activeLifecycleEpoch(),
        proof.initialAdmissionRequestId(),
        proof.requestDigest(),
        proof.expectedNoPriorPointer(),
        proof.expectedCatalogRevision(),
        proof.ownerProofId(),
        proof.pointerAuditId(),
        proof.pointerVersion(),
        proof.pointerAuditRequestDigest(),
        proof.futureCommitPrevented());
  }

  private static InitialAdmissionBindHoldBinding binding() {
    return new InitialAdmissionBindHoldBinding(
        HOLD_ID,
        HOLD_FENCE,
        42L,
        REALM_UUID,
        PLAYABLE_NAMESPACE_UUID,
        "SHARED",
        101L,
        11L,
        7L,
        REQUEST_ID,
        REQUEST_DIGEST,
        true,
        1L);
  }

  private static GetInitialAdmissionBindProofRequest request() {
    return GetInitialAdmissionBindProofRequest.newBuilder()
        .setHoldId(HOLD_ID)
        .setHoldFence(HOLD_FENCE)
        .setTenantId("42")
        .setRealmUuid(REALM_UUID)
        .setPlayableStateNamespaceUuid(PLAYABLE_NAMESPACE_UUID)
        .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
        .setGameInstanceId("101")
        .setVersionId("11")
        .setActiveLifecycleEpoch(7L)
        .setInitialAdmissionRequestId(REQUEST_ID)
        .setRequestDigest(REQUEST_DIGEST)
        .setExpectedNoPriorPointer(true)
        .setExpectedCatalogRevision(1L)
        .build();
  }

  private static GameSessionControlPlaneGrpcService service(
      InitialAdmissionBindOwnerProofReader reader, String trustedNamespace) {
    GameSessionControlPlaneGrpcService service =
        new GameSessionControlPlaneGrpcService(
            mock(GameSessionCommandControlPlaneService.class),
            mock(GameSessionRemoteControlPlaneService.class),
            mock(GameSessionRuntimeControlPlaneReadService.class),
            mock(GameSessionAdmissionPointerControlPlaneService.class),
            mock(GameSessionOperatorControlPlaneService.class),
            mock(GameSessionVersionUpgradeControlPlaneService.class),
            new SimpleMeterRegistry());
    service.configureInitialAdmissionBindOwnerReadBoundary(reader, trustedNamespace);
    return service;
  }

  private static GetInitialAdmissionBindProofResponse invoke(
      GameSessionControlPlaneGrpcService service, GetInitialAdmissionBindProofRequest request) {
    AtomicReference<GetInitialAdmissionBindProofResponse> response = new AtomicReference<>();
    service.getInitialAdmissionBindProof(
        request,
        new StreamObserver<>() {
          @Override
          public void onNext(GetInitialAdmissionBindProofResponse value) {
            response.set(value);
          }

          @Override
          public void onError(Throwable throwable) {
            throw new AssertionError("Owner proof RPC failed", throwable);
          }

          @Override
          public void onCompleted() {}
        });
    return response.get();
  }

  private static void runAsWorldManagement(Runnable action) {
    runAsPeer("world-management-service", NAMESPACE, action);
  }

  private static void runAsPeer(String service, String namespace, Runnable action) {
    Context.current()
        .withValue(
            GrpcPeerIdentity.CONTEXT_KEY,
            new GrpcPeerIdentity(
                "spiffe://firemud/ns/" + namespace + "/sa/" + service, namespace, service))
        .run(action);
  }
}
