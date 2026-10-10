package unit.net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.UnknownFieldSet;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.gamesession.CanonicalInitialAdmissionOwnerGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.Request;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest.OriginKind;
import net.firedevops.firemud.gamesession.repository.CanonicalInitialAdmissionRepository;
import net.firedevops.firemud.gamesession.repository.CanonicalInitialAdmissionRepository.CanonicalInitialAdmissionReconciliationRequiredException;
import net.firedevops.firemud.gamesession.service.impl.CanonicalInitialAdmissionOwnerReadGrpcService;
import net.firedevops.firemud.gamesession.service.impl.CanonicalInitialAdmissionOwnerReadWorkloadGuard;
import net.firedevops.firemud.gamesession.v1.CanonicalInitialAdmissionOwnerProofOutcome;
import net.firedevops.firemud.gamesession.v1.CanonicalInitialAdmissionOwnerProofReadServiceGrpc;
import net.firedevops.firemud.gamesession.v1.GameSessionControlPlaneServiceGrpc;
import net.firedevops.firemud.gamesession.v1.GetCanonicalInitialAdmissionOwnerProofRequest;
import net.firedevops.firemud.gamesession.v1.GetCanonicalInitialAdmissionOwnerProofResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CanonicalInitialAdmissionOwnerReadGrpcServiceTest {
  private static final String NAMESPACE = "dev";
  private static final UUID TENANT = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID REALM = uuid("22222222-2222-4222-8222-222222222222");
  private static final UUID PLAYABLE_NAMESPACE = uuid("33333333-3333-4333-8333-333333333333");
  private static final UUID INSTANCE = uuid("44444444-4444-4444-8444-444444444444");
  private static final UUID VERSION = uuid("55555555-5555-4555-8555-555555555555");
  private static final UUID HOLD_ID = uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID HOLD_FENCE = uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
  private static final String REQUEST_ID = "initial-admission-1";
  private static final Instant TERMINAL_AT = Instant.parse("2025-02-03T04:05:06.123456Z");

  @AfterEach
  void clearContext() {
    SessionContext.clear();
  }

  @Test
  void ownerProofReadUsesDedicatedServiceIdentityOutsideControlPlane() {
    String methodName =
        CanonicalInitialAdmissionOwnerProofReadServiceGrpc
            .getGetCanonicalInitialAdmissionOwnerProofMethod()
            .getFullMethodName();

    assertEquals(
        "game_session.v1.CanonicalInitialAdmissionOwnerProofReadService",
        CanonicalInitialAdmissionOwnerProofReadServiceGrpc.getServiceDescriptor().getName());
    assertEquals(
        "game_session.v1.CanonicalInitialAdmissionOwnerProofReadService/GetCanonicalInitialAdmissionOwnerProof",
        methodName);
    assertNotEquals(
        GameSessionControlPlaneServiceGrpc.getServiceDescriptor().getName(),
        CanonicalInitialAdmissionOwnerProofReadServiceGrpc.getServiceDescriptor().getName());
  }

  @Test
  void sameNamespaceWorldWorkloadReceivesCompletePendingTupleWithoutTerminalFields() {
    CanonicalInitialAdmissionRepository repository =
        mock(CanonicalInitialAdmissionRepository.class);
    HoldIdentity identity = identity();
    when(repository.read(NAMESPACE, REQUEST_ID))
        .thenReturn(Optional.of(ownerProof(identity, "PENDING")));
    var service = service(repository);

    var result = invokeAsWorld(service, request(identity));

    assertNull(result.status());
    GetCanonicalInitialAdmissionOwnerProofResponse response = result.response();
    assertCompleteTuple(identity, response);
    assertEquals(
        CanonicalInitialAdmissionOwnerProofOutcome
            .CANONICAL_INITIAL_ADMISSION_OWNER_PROOF_OUTCOME_PENDING,
        response.getOutcome());
    assertFalse(response.hasCommittedPointerVersion());
    assertFalse(response.hasAuditEventId());
    assertEquals("", response.getProofDigest());
    assertFalse(response.getPositiveDurableAbort());
    assertFalse(response.hasTerminalAt());
    verify(repository).read(NAMESPACE, REQUEST_ID);
  }

  @Test
  void closedOriginReadbackEchoesPriorPointerVersionWithoutTreatingPendingAsTerminal() {
    CanonicalInitialAdmissionRepository repository =
        mock(CanonicalInitialAdmissionRepository.class);
    HoldIdentity identity = identity(InitialAdmissionOrigin.EXPECT_CLOSED, 1L);
    when(repository.read(NAMESPACE, REQUEST_ID))
        .thenReturn(Optional.of(ownerProof(identity, "PENDING")));

    var result = invokeAsWorld(service(repository), request(identity));

    assertNull(result.status());
    assertCompleteTuple(identity, result.response());
    assertEquals(
        net.firedevops.firemud.gamesession.v1.CanonicalInitialAdmissionOrigin
            .CANONICAL_INITIAL_ADMISSION_ORIGIN_EXPECT_CLOSED,
        result.response().getOrigin());
    assertTrue(result.response().hasExpectedPriorPointerVersion());
    assertEquals(1L, result.response().getExpectedPriorPointerVersion());
    assertEquals(
        CanonicalInitialAdmissionOwnerProofOutcome
            .CANONICAL_INITIAL_ADMISSION_OWNER_PROOF_OUTCOME_PENDING,
        result.response().getOutcome());
    assertFalse(result.response().hasCommittedPointerVersion());
  }

  @Test
  void closedOriginCommitMustAdvanceThePriorPointerExactlyOnce() {
    CanonicalInitialAdmissionRepository repository =
        mock(CanonicalInitialAdmissionRepository.class);
    HoldIdentity identity = identity(InitialAdmissionOrigin.EXPECT_CLOSED, 1L);
    when(repository.read(NAMESPACE, REQUEST_ID))
        .thenReturn(Optional.of(ownerProof(identity, "COMMITTED")));

    var result = invokeAsWorld(service(repository), request(identity));

    assertNull(result.status());
    assertEquals(2L, result.response().getCommittedPointerVersion());
  }

  @Test
  void committedReadbackFailsClosedWhenPriorPointerVersionCannotAdvance() {
    CanonicalInitialAdmissionRepository repository =
        mock(CanonicalInitialAdmissionRepository.class);
    HoldIdentity identity = identity(InitialAdmissionOrigin.EXPECT_CLOSED, Long.MAX_VALUE);
    when(repository.read(NAMESPACE, REQUEST_ID))
        .thenReturn(Optional.of(ownerProof(identity, "COMMITTED", TENANT, 1L)));

    var result = invokeAsWorld(service(repository), request(identity));

    assertEquals(Status.Code.FAILED_PRECONDITION, result.status().getCode());
  }

  @Test
  void codecRejectsOverflowingPriorPointerWithTheExplicitOverflowCause() {
    HoldIdentity exactHold = identity(InitialAdmissionOrigin.EXPECT_CLOSED, Long.MAX_VALUE);
    CanonicalInitialAdmissionOwnerProof existingProof =
        ownerProof(exactHold, "COMMITTED", TENANT, 1L);
    GameSessionCanonicalInitialAdmissionOwnerProof proof =
        new GameSessionCanonicalInitialAdmissionOwnerProof(
            exactHold,
            GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED,
            existingProof.committedPointerVersion(),
            existingProof.auditEventId(),
            existingProof.proofDigest(),
            existingProof.positiveDurableAbort(),
            existingProof.terminalAt());

    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () -> CanonicalInitialAdmissionOwnerGrpcCodec.toResponse(exactHold, proof));

    assertEquals(
        "Expected prior pointer version cannot advance without overflow", failure.getMessage());
    assertTrue(failure.getCause() instanceof ArithmeticException);
  }

  @Test
  void committedOwnerReadbackMapsExactPointerAuditAndTerminalProof() {
    CanonicalInitialAdmissionRepository repository =
        mock(CanonicalInitialAdmissionRepository.class);
    HoldIdentity identity = identity();
    when(repository.read(NAMESPACE, REQUEST_ID))
        .thenReturn(Optional.of(ownerProof(identity, "COMMITTED")));

    var result = invokeAsWorld(service(repository), request(identity));

    assertNull(result.status());
    var response = result.response();
    assertCompleteTuple(identity, response);
    assertEquals(
        CanonicalInitialAdmissionOwnerProofOutcome
            .CANONICAL_INITIAL_ADMISSION_OWNER_PROOF_OUTCOME_COMMITTED,
        response.getOutcome());
    assertEquals(1L, response.getCommittedPointerVersion());
    assertEquals(41L, response.getAuditEventId());
    assertEquals("sha256:" + "b".repeat(64), response.getProofDigest());
    assertFalse(response.getPositiveDurableAbort());
    assertEquals(TERMINAL_AT.getEpochSecond(), response.getTerminalAt().getSeconds());
    assertEquals(TERMINAL_AT.getNano(), response.getTerminalAt().getNanos());
  }

  @Test
  void durableAbortMapsOnlyPositiveFencingAndTerminalProof() {
    CanonicalInitialAdmissionRepository repository =
        mock(CanonicalInitialAdmissionRepository.class);
    HoldIdentity identity = identity();
    when(repository.read(NAMESPACE, REQUEST_ID))
        .thenReturn(Optional.of(ownerProof(identity, "ABORTED")));

    var result = invokeAsWorld(service(repository), request(identity));

    assertNull(result.status());
    var response = result.response();
    assertCompleteTuple(identity, response);
    assertEquals(
        CanonicalInitialAdmissionOwnerProofOutcome
            .CANONICAL_INITIAL_ADMISSION_OWNER_PROOF_OUTCOME_ABORTED,
        response.getOutcome());
    assertFalse(response.hasCommittedPointerVersion());
    assertFalse(response.hasAuditEventId());
    assertEquals("sha256:" + "c".repeat(64), response.getProofDigest());
    assertTrue(response.getPositiveDurableAbort());
    assertTrue(response.hasTerminalAt());
  }

  @Test
  void refusesWrongWorkloadAndCrossNamespaceBeforeReadingOwnerState() {
    CanonicalInitialAdmissionRepository repository =
        mock(CanonicalInitialAdmissionRepository.class);
    var service = service(repository);
    var request = request(identity());

    assertEquals(Status.Code.PERMISSION_DENIED, invoke(service, request).status().getCode());
    assertEquals(
        Status.Code.PERMISSION_DENIED,
        invokeAsPeer(service, request, "game-session-service", NAMESPACE).status().getCode());
    assertEquals(
        Status.Code.PERMISSION_DENIED,
        invokeAsPeer(service, request, "world-management-service", "other").status().getCode());
    verifyNoInteractions(repository);
  }

  @Test
  void refusesNullOrMalformedRequestBeforeDecodingForUnauthorizedCaller() {
    CanonicalInitialAdmissionRepository repository =
        mock(CanonicalInitialAdmissionRepository.class);
    var service = service(repository);
    var malformed =
        GetCanonicalInitialAdmissionOwnerProofRequest.newBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                    .build())
            .build();

    assertEquals(Status.Code.PERMISSION_DENIED, invoke(service, null).status().getCode());
    assertEquals(Status.Code.PERMISSION_DENIED, invoke(service, malformed).status().getCode());
    verifyNoInteractions(repository);
  }

  @Test
  void refusesForeignWorkloadAndMatchingForeignRequestOutsideTrustedNamespace() {
    CanonicalInitialAdmissionRepository repository =
        mock(CanonicalInitialAdmissionRepository.class);
    HoldIdentity foreignIdentity = identity("other", InitialAdmissionOrigin.NO_PRIOR_POINTER, null);
    var foreignRequest = request(foreignIdentity);

    var result =
        invokeAsPeer(service(repository), foreignRequest, "world-management-service", "other");

    assertEquals(Status.Code.PERMISSION_DENIED, result.status().getCode());
    verifyNoInteractions(repository);
  }

  @Test
  void refusesInvalidServerOwnedTrustedNamespace() {
    CanonicalInitialAdmissionRepository repository =
        mock(CanonicalInitialAdmissionRepository.class);

    var result = invokeAsWorld(service(repository, ""), request(identity()));

    assertEquals(Status.Code.PERMISSION_DENIED, result.status().getCode());
    verifyNoInteractions(repository);
  }

  @Test
  void refusesAuthenticatedEndUserCallerContextEvenWithWorldWorkloadPeer() {
    CanonicalInitialAdmissionRepository repository =
        mock(CanonicalInitialAdmissionRepository.class);
    SessionContext.setContext(
        "42", java.util.List.of(), java.util.Map.of(), true, "world-management-service", "wms-1");

    var result = invokeAsWorld(service(repository), request(identity()));

    assertEquals(Status.Code.PERMISSION_DENIED, result.status().getCode());
    verifyNoInteractions(repository);
  }

  @Test
  void rejectsUnknownFieldsMalformedTupleAndForgedHoldDigestBeforeReading() {
    CanonicalInitialAdmissionRepository repository =
        mock(CanonicalInitialAdmissionRepository.class);
    var service = service(repository);
    HoldIdentity identity = identity();
    UnknownFieldSet unknown =
        UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
            .build();

    assertEquals(
        Status.Code.INVALID_ARGUMENT,
        invokeAsWorld(service, request(identity).toBuilder().setUnknownFields(unknown).build())
            .status()
            .getCode());
    assertEquals(Status.Code.INVALID_ARGUMENT, invokeAsWorld(service, null).status().getCode());
    assertEquals(
        Status.Code.INVALID_ARGUMENT,
        invokeAsWorld(
                service,
                request(identity).toBuilder().setCanonicalTenantId("not-a-canonical-uuid").build())
            .status()
            .getCode());
    assertEquals(
        Status.Code.INVALID_ARGUMENT,
        invokeAsWorld(
                service,
                request(identity).toBuilder()
                    .setHoldBindingDigest("sha256:" + "0".repeat(64))
                    .build())
            .status()
            .getCode());
    verifyNoInteractions(repository);
  }

  @Test
  void missingAmbiguousAndMismatchedOwnerReadbacksNeverProduceDomainOutcomes() {
    HoldIdentity identity = identity();

    CanonicalInitialAdmissionRepository missing = mock(CanonicalInitialAdmissionRepository.class);
    when(missing.read(NAMESPACE, REQUEST_ID)).thenReturn(Optional.empty());
    assertEquals(
        Status.Code.FAILED_PRECONDITION,
        invokeAsWorld(service(missing), request(identity)).status().getCode());

    CanonicalInitialAdmissionRepository ambiguous = mock(CanonicalInitialAdmissionRepository.class);
    when(ambiguous.read(NAMESPACE, REQUEST_ID))
        .thenThrow(
            new CanonicalInitialAdmissionReconciliationRequiredException("conflicting readback"));
    assertEquals(
        Status.Code.FAILED_PRECONDITION,
        invokeAsWorld(service(ambiguous), request(identity)).status().getCode());

    CanonicalInitialAdmissionRepository mismatched =
        mock(CanonicalInitialAdmissionRepository.class);
    when(mismatched.read(NAMESPACE, REQUEST_ID))
        .thenReturn(
            Optional.of(
                ownerProof(identity, "COMMITTED", uuid("99999999-9999-4999-8999-999999999999"))));
    assertEquals(
        Status.Code.FAILED_PRECONDITION,
        invokeAsWorld(service(mismatched), request(identity)).status().getCode());
  }

  private static void assertCompleteTuple(
      HoldIdentity identity, GetCanonicalInitialAdmissionOwnerProofResponse response) {
    assertNotNull(response);
    Request request = identity.request();
    assertEquals(NAMESPACE, response.getTargetNamespace());
    assertEquals(REQUEST_ID, response.getInitialAdmissionRequestId());
    assertEquals(
        request.initialAdmissionRequestDigest(), response.getInitialAdmissionRequestDigest());
    assertNotEquals(request.initialAdmissionRequestDigest(), identity.holdBindingDigest());
    assertEquals(TENANT.toString(), response.getCanonicalTenantId());
    assertEquals("demo-world", response.getWorldSlug());
    assertEquals(REALM.toString(), response.getRealmId());
    assertEquals(PLAYABLE_NAMESPACE.toString(), response.getPlayableStateNamespaceId());
    assertEquals("SHARED", response.getPlayableStateScope());
    assertEquals(INSTANCE.toString(), response.getCanonicalGameInstanceId());
    assertEquals(VERSION.toString(), response.getCanonicalVersionId());
    assertEquals(7L, response.getActiveLifecycleEpoch());
    assertEquals(3L, response.getExpectedCatalogRevision());
    assertEquals(toWireOrigin(request.initialAdmissionOrigin()), response.getOrigin());
    if (request.expectedPriorPointerVersion() == null) {
      assertFalse(response.hasExpectedPriorPointerVersion());
    } else {
      assertTrue(response.hasExpectedPriorPointerVersion());
      assertEquals(
          request.expectedPriorPointerVersion(), response.getExpectedPriorPointerVersion());
    }
    assertEquals(HOLD_ID.toString(), response.getHoldId());
    assertEquals(HOLD_FENCE.toString(), response.getHoldFence());
    assertEquals(identity.holdBindingDigest(), response.getHoldBindingDigest());
    assertTrue(response.getUnknownFields().asMap().isEmpty());
  }

  private static CanonicalInitialAdmissionOwnerProof ownerProof(
      HoldIdentity identity, String outcome) {
    return ownerProof(identity, outcome, TENANT);
  }

  private static CanonicalInitialAdmissionOwnerProof ownerProof(
      HoldIdentity identity, String outcome, UUID tenantId) {
    Request request = identity.request();
    long committedPointerVersion =
        request.expectedPriorPointerVersion() == null
                || request.expectedPriorPointerVersion() == Long.MAX_VALUE
            ? 1L
            : Math.addExact(request.expectedPriorPointerVersion(), 1L);
    return ownerProof(identity, outcome, tenantId, committedPointerVersion);
  }

  private static CanonicalInitialAdmissionOwnerProof ownerProof(
      HoldIdentity identity, String outcome, UUID tenantId, long committedPointerVersion) {
    Request request = identity.request();
    boolean committed = "COMMITTED".equals(outcome);
    boolean aborted = "ABORTED".equals(outcome);
    return new CanonicalInitialAdmissionOwnerProof(
        CanonicalInitialAdmissionOwnerProof.Outcome.valueOf(outcome),
        request.initialAdmissionRequestId(),
        request.initialAdmissionRequestDigest(),
        request.targetNamespace(),
        tenantId,
        request.worldSlug(),
        request.realmId(),
        request.playableStateNamespaceId(),
        request.playableStateScope(),
        request.canonicalGameInstanceId(),
        request.canonicalVersionId(),
        request.activeLifecycleEpoch(),
        request.expectedCatalogRevision(),
        OriginKind.valueOf(request.initialAdmissionOrigin().name()),
        request.expectedPriorPointerVersion(),
        identity.holdId(),
        identity.holdFence(),
        identity.holdBindingDigest(),
        committed ? committedPointerVersion : null,
        committed ? 41L : null,
        committed ? "sha256:" + "b".repeat(64) : aborted ? "sha256:" + "c".repeat(64) : null,
        aborted,
        committed || aborted ? TERMINAL_AT : null);
  }

  private static HoldIdentity identity() {
    return identity(InitialAdmissionOrigin.NO_PRIOR_POINTER, null);
  }

  private static HoldIdentity identity(
      InitialAdmissionOrigin origin, Long expectedPriorPointerVersion) {
    return identity(NAMESPACE, origin, expectedPriorPointerVersion);
  }

  private static HoldIdentity identity(
      String namespace, InitialAdmissionOrigin origin, Long expectedPriorPointerVersion) {
    OriginKind originKind = OriginKind.valueOf(origin.name());
    String digest =
        net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest
            .computeRequestDigest(
                namespace,
                TENANT,
                "demo-world",
                REALM,
                PLAYABLE_NAMESPACE,
                "SHARED",
                INSTANCE,
                VERSION,
                7L,
                3L,
                originKind,
                expectedPriorPointerVersion,
                REQUEST_ID);
    Request request =
        new Request(
            namespace,
            TENANT,
            "demo-world",
            REALM,
            PLAYABLE_NAMESPACE,
            "SHARED",
            INSTANCE,
            VERSION,
            7L,
            REQUEST_ID,
            digest,
            origin,
            3L,
            expectedPriorPointerVersion);
    return new HoldIdentity(request, HOLD_ID, HOLD_FENCE);
  }

  private static GetCanonicalInitialAdmissionOwnerProofRequest request(HoldIdentity identity) {
    Request request = identity.request();
    var builder =
        GetCanonicalInitialAdmissionOwnerProofRequest.newBuilder()
            .setTargetNamespace(request.targetNamespace())
            .setInitialAdmissionRequestId(request.initialAdmissionRequestId())
            .setInitialAdmissionRequestDigest(request.initialAdmissionRequestDigest())
            .setCanonicalTenantId(request.canonicalTenantId().toString())
            .setWorldSlug(request.worldSlug())
            .setRealmId(request.realmId().toString())
            .setPlayableStateNamespaceId(request.playableStateNamespaceId().toString())
            .setPlayableStateScope(request.playableStateScope())
            .setCanonicalGameInstanceId(request.canonicalGameInstanceId().toString())
            .setCanonicalVersionId(request.canonicalVersionId().toString())
            .setActiveLifecycleEpoch(request.activeLifecycleEpoch())
            .setExpectedCatalogRevision(request.expectedCatalogRevision())
            .setOrigin(toWireOrigin(request.initialAdmissionOrigin()))
            .setHoldId(identity.holdId().toString())
            .setHoldFence(identity.holdFence().toString())
            .setHoldBindingDigest(identity.holdBindingDigest());
    if (request.expectedPriorPointerVersion() != null) {
      builder.setExpectedPriorPointerVersion(request.expectedPriorPointerVersion());
    }
    return builder.build();
  }

  private static net.firedevops.firemud.gamesession.v1.CanonicalInitialAdmissionOrigin toWireOrigin(
      InitialAdmissionOrigin origin) {
    return switch (origin) {
      case NO_PRIOR_POINTER ->
          net.firedevops.firemud.gamesession.v1.CanonicalInitialAdmissionOrigin
              .CANONICAL_INITIAL_ADMISSION_ORIGIN_NO_PRIOR_POINTER;
      case EXPECT_CLOSED ->
          net.firedevops.firemud.gamesession.v1.CanonicalInitialAdmissionOrigin
              .CANONICAL_INITIAL_ADMISSION_ORIGIN_EXPECT_CLOSED;
    };
  }

  private static CanonicalInitialAdmissionOwnerReadGrpcService service(
      CanonicalInitialAdmissionRepository repository) {
    return service(repository, NAMESPACE);
  }

  private static CanonicalInitialAdmissionOwnerReadGrpcService service(
      CanonicalInitialAdmissionRepository repository, String trustedNamespace) {
    return new CanonicalInitialAdmissionOwnerReadGrpcService(
        repository, new CanonicalInitialAdmissionOwnerReadWorkloadGuard(trustedNamespace));
  }

  private static ReadResult invokeAsWorld(
      CanonicalInitialAdmissionOwnerReadGrpcService service,
      GetCanonicalInitialAdmissionOwnerProofRequest request) {
    return invokeAsPeer(service, request, "world-management-service", NAMESPACE);
  }

  private static ReadResult invokeAsPeer(
      CanonicalInitialAdmissionOwnerReadGrpcService service,
      GetCanonicalInitialAdmissionOwnerProofRequest request,
      String peerService,
      String peerNamespace) {
    AtomicReference<ReadResult> result = new AtomicReference<>();
    Context.current()
        .withValue(
            GrpcPeerIdentity.CONTEXT_KEY,
            new GrpcPeerIdentity(
                "spiffe://firemud/ns/" + peerNamespace + "/sa/" + peerService,
                peerNamespace,
                peerService))
        .run(() -> result.set(invoke(service, request)));
    return result.get();
  }

  private static ReadResult invoke(
      CanonicalInitialAdmissionOwnerReadGrpcService service,
      GetCanonicalInitialAdmissionOwnerProofRequest request) {
    AtomicReference<GetCanonicalInitialAdmissionOwnerProofResponse> response =
        new AtomicReference<>();
    AtomicReference<Status> status = new AtomicReference<>();
    service.getCanonicalInitialAdmissionOwnerProof(
        request,
        new StreamObserver<>() {
          @Override
          public void onNext(GetCanonicalInitialAdmissionOwnerProofResponse value) {
            response.set(value);
          }

          @Override
          public void onError(Throwable throwable) {
            status.set(Status.fromThrowable(throwable));
          }

          @Override
          public void onCompleted() {}
        });
    return new ReadResult(response.get(), status.get());
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private record ReadResult(
      GetCanonicalInitialAdmissionOwnerProofResponse response, Status status) {}
}
