package net.firedevops.firemud.gamesession.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.RealmPolicyPublishedEvidence;
import net.firedevops.firemud.common.gamedesign.RealmPolicySource;
import net.firedevops.firemud.common.gamesession.CurrentPrivatePlaytestLifecycleReadEvidence.Scope;
import net.firedevops.firemud.common.gamesession.PrivatePreparationProtectionGrpcCodec;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadEvidence;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.gamesession.dto.CanonicalPrivateFreshRuntimeAllocationSnapshot;
import net.firedevops.firemud.gamesession.dto.CanonicalPrivateFreshTargetSnapshot;
import net.firedevops.firemud.gamesession.repository.GameSessionCurrentPrivatePlaytestRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionInitialPlaytestLifecycleRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionInitialPlaytestLifecycleRepository.InitializationMode;
import net.firedevops.firemud.gamesession.repository.GameSessionPrivateFreshRuntimeAllocationRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionPrivateFreshTargetRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionPrivatePreparationProtectionRepository;
import net.firedevops.firemud.gamesession.v1.PrivatePreparationProtectionResponse;
import net.firedevops.firemud.gamesession.v1.ReadPrivateWorldPreparationResponse;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Controlled owner responses; physical coherent SQL and mTLS are covered by the coupled proof. */
class PrivateWorldPreparationReadGrpcServiceTest {
  private static final String NS = "private-world-test";
  private static final String DIGEST = "sha256:" + "a".repeat(64);
  private static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");
  private static final UUID RUNTIME_REQUEST_ID =
      UUID.fromString("00000000-0000-4000-8000-000000000001");
  private static final UUID GAME_INSTANCE_ID =
      UUID.fromString("00000000-0000-4000-8000-000000000002");

  @Test
  void retainedBuildCorruptionIsNotReportedAsInvalidCallerInput() throws Exception {
    var f = new Fixture();
    var builds =
        mock(
            net.firedevops.firemud.gamesession.repository
                .GameSessionPrivateWorldPreparationBuildReader.class);
    when(builds.read(f.request))
        .thenThrow(new IllegalArgumentException("Controlled retained carrier corruption"));
    var read =
        new TransactionOperations() {
          @Override
          public <T> T execute(
              org.springframework.transaction.support.TransactionCallback<T> callback) {
            return callback.doInTransaction(new SimpleTransactionStatus());
          }
        };
    var service =
        new PrivateWorldPreparationReadGrpcService(
            f.current, f.targets, f.runtimes, read, NS, Clock.fixed(NOW, ZoneOffset.UTC), builds);
    var observer = new BuildResult();
    withPeer(
        "spiffe://firemud/ns/" + NS + "/sa/world-management-service",
        () ->
            service.readPrivateWorldPreparationBuild(
                PrivateWorldPreparationReadGrpcCodec.toRequest(f.request), observer));
    assertThat(Status.fromThrowable(observer.failure).getCode())
        .isEqualTo(Status.Code.FAILED_PRECONDITION);
    verifyNoInteractions(f.current, f.targets, f.runtimes);
  }

  @Test
  void selectedBuildDefaultsDenyAndChecksPeerBeforeAnyOwnerRead() throws Exception {
    var f = new Fixture();
    var observer = new BuildResult();
    f.service.readPrivateWorldPreparationBuild(null, observer);
    assertThat(Status.fromThrowable(observer.failure).getCode())
        .isEqualTo(Status.Code.UNAUTHENTICATED);
    var authorized = new BuildResult();
    withPeer(
        "spiffe://firemud/ns/" + NS + "/sa/world-management-service",
        () ->
            f.service.readPrivateWorldPreparationBuild(
                PrivateWorldPreparationReadGrpcCodec.toRequest(f.request), authorized));
    assertThat(Status.fromThrowable(authorized.failure).getCode())
        .isEqualTo(Status.Code.FAILED_PRECONDITION);
    verifyNoInteractions(f.current, f.targets, f.runtimes);
  }

  @Test
  void pendingProtectionReceiverRequiresWorldAndBindsActualRetainedOwnerResult() throws Exception {
    var fixture = new Fixture();
    var original = fixture.call(fixture.request);
    var evidence =
        PrivateWorldPreparationReadGrpcCodec.fromResponse(fixture.request, original.value);
    var request = new PrivatePreparationProtectionGrpcCodec.Acquire(UUID.randomUUID(), evidence);
    var owner = mock(GameSessionPrivatePreparationProtectionRepository.class);
    var retained =
        new GameSessionPrivatePreparationProtectionRepository.Hold(
            request.requestId(), UUID.randomUUID(), 1, evidence);
    when(owner.acquire(request.requestId(), evidence.snapshot())).thenReturn(retained);
    when(owner.readCurrent(request.requestId(), retained.holdId(), 1)).thenReturn(retained);
    var service = new PrivatePreparationProtectionGrpcService(owner, NS);
    var missing = new ProtectionResult();
    service.acquirePrivatePreparationProtection(null, missing);
    assertThat(Status.fromThrowable(missing.failure).getCode())
        .isEqualTo(Status.Code.UNAUTHENTICATED);
    for (String peer :
        List.of(
            "spiffe://firemud/ns/" + NS + "/sa/account-service",
            "spiffe://firemud/ns/other/sa/world-management-service")) {
      var denied = new ProtectionResult();
      withPeer(peer, () -> service.readCurrentPrivatePreparationProtection(null, denied));
      assertThat(Status.fromThrowable(denied.failure).getCode())
          .isEqualTo(Status.Code.PERMISSION_DENIED);
    }
    verifyNoInteractions(owner);
    var acquired = new ProtectionResult();
    withPeer(
        "spiffe://firemud/ns/" + NS + "/sa/world-management-service",
        () ->
            service.acquirePrivatePreparationProtection(
                PrivatePreparationProtectionGrpcCodec.toWire(request), acquired));
    assertThat(acquired.failure).isNull();
    var held = PrivatePreparationProtectionGrpcCodec.fromResponse(request, acquired.value);
    assertThat(held.holdId()).isEqualTo(retained.holdId());
    var read =
        new PrivatePreparationProtectionGrpcCodec.Read(
            UUID.randomUUID(), request.requestId(), held.holdId(), 1, held.sourceDigest());
    var result = new ProtectionResult();
    withPeer(
        "spiffe://firemud/ns/" + NS + "/sa/world-management-service",
        () ->
            service.readCurrentPrivatePreparationProtection(
                PrivatePreparationProtectionGrpcCodec.toWire(read), result));
    assertThat(result.failure).isNull();
    assertThat(PrivatePreparationProtectionGrpcCodec.fromResponse(read, result.value))
        .isEqualTo(held);
  }

  @Test
  void protectionOwnerCorruptionIsDistinctFromMalformedCallerFrames() throws Exception {
    var fixture = new Fixture();
    var source = fixture.call(fixture.request);
    var evidence = PrivateWorldPreparationReadGrpcCodec.fromResponse(fixture.request, source.value);
    var acquire = new PrivatePreparationProtectionGrpcCodec.Acquire(UUID.randomUUID(), evidence);
    var held =
        new PrivatePreparationProtectionGrpcCodec.Held(
            acquire.requestId(), UUID.randomUUID(), 1, evidence);
    var read =
        new PrivatePreparationProtectionGrpcCodec.Read(
            UUID.randomUUID(), acquire.requestId(), held.holdId(), 1, held.sourceDigest());
    var owner = mock(GameSessionPrivatePreparationProtectionRepository.class);
    var service = new PrivatePreparationProtectionGrpcService(owner, NS);
    String peer = "spiffe://firemud/ns/" + NS + "/sa/world-management-service";
    var malformedAcquire = new ProtectionResult();
    withPeer(
        peer,
        () ->
            service.acquirePrivatePreparationProtection(
                net.firedevops.firemud.gamesession.v1.AcquirePrivatePreparationProtectionRequest
                    .getDefaultInstance(),
                malformedAcquire));
    var malformedRead = new ProtectionResult();
    withPeer(
        peer,
        () ->
            service.readCurrentPrivatePreparationProtection(
                net.firedevops.firemud.gamesession.v1.ReadCurrentPrivatePreparationProtectionRequest
                    .getDefaultInstance(),
                malformedRead));
    assertThat(Status.fromThrowable(malformedAcquire.failure).getCode())
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(Status.fromThrowable(malformedRead.failure).getCode())
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    var originalAcquire = PrivatePreparationProtectionGrpcCodec.toWire(acquire);
    for (int frame : new int[] {6, 15, 16, 36, 62}) {
      byte[] malformedSource =
          replaceFrame(
              source.value.getCanonicalEvidence().toByteArray(),
              frame,
              (frame == 36 ? "{" : "not-an-instant").getBytes(StandardCharsets.UTF_8));
      var malformed =
          originalAcquire.toBuilder()
              .setCanonicalEvidence(
                  ByteString.copyFrom(
                      replaceFrame(
                          originalAcquire.getCanonicalEvidence().toByteArray(),
                          3,
                          malformedSource)))
              .build();
      var result = new ProtectionResult();
      withPeer(peer, () -> service.acquirePrivatePreparationProtection(malformed, result));
      assertThat(Status.fromThrowable(result.failure).getCode())
          .as("malformed source frame %s", frame)
          .isEqualTo(Status.Code.INVALID_ARGUMENT);
      assertThat(result.value).isNull();
    }
    verifyNoInteractions(owner);

    when(owner.acquire(acquire.requestId(), evidence.snapshot()))
        .thenThrow(new IllegalArgumentException("Controlled retained acquire corruption"));
    when(owner.readCurrent(read.requestId(), read.holdId(), read.fence()))
        .thenThrow(new IllegalArgumentException("Controlled retained read corruption"));
    var badAcquire = new ProtectionResult();
    withPeer(
        peer,
        () ->
            service.acquirePrivatePreparationProtection(
                PrivatePreparationProtectionGrpcCodec.toWire(acquire), badAcquire));
    var badRead = new ProtectionResult();
    withPeer(
        peer,
        () ->
            service.readCurrentPrivatePreparationProtection(
                PrivatePreparationProtectionGrpcCodec.toWire(read), badRead));
    assertThat(Status.fromThrowable(badAcquire.failure).getCode())
        .isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(Status.fromThrowable(badRead.failure).getCode())
        .isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(badAcquire.value).isNull();
    assertThat(badRead.value).isNull();
  }

  private static byte[] replaceFrame(byte[] original, int changedIndex, byte[] replacement) {
    var input = ByteBuffer.wrap(original);
    var output = new ByteArrayOutputStream();
    int index = 0;
    while (input.hasRemaining()) {
      byte[] field = new byte[input.getInt()];
      input.get(field);
      net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.frame(
          output, index++ == changedIndex ? replacement : field);
    }
    assertThat(index).isGreaterThan(changedIndex);
    return output.toByteArray();
  }

  private static final class ProtectionResult
      implements StreamObserver<PrivatePreparationProtectionResponse> {
    PrivatePreparationProtectionResponse value;
    Throwable failure;

    @Override
    public void onNext(PrivatePreparationProtectionResponse value) {
      this.value = value;
    }

    @Override
    @SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Test observer retains the exact failure for assertions.")
    public void onError(Throwable error) {
      failure = error;
    }

    @Override
    public void onCompleted() {}
  }

  @Test
  void missingWrongRoleAndWrongNamespaceAreDeniedBeforeDecodeOrOwners() throws Exception {
    var f = new Fixture();
    var absent = new Result();
    f.service.readPrivateWorldPreparation(null, absent);
    assertThat(Status.fromThrowable(absent.failure).getCode())
        .isEqualTo(Status.Code.UNAUTHENTICATED);
    for (String peer :
        List.of(
            "spiffe://firemud/ns/" + NS + "/sa/account-service",
            "spiffe://firemud/ns/other/sa/world-management-service")) {
      var result = new Result();
      withPeer(peer, () -> f.service.readPrivateWorldPreparation(null, result));
      assertThat(Status.fromThrowable(result.failure).getCode())
          .isEqualTo(Status.Code.PERMISSION_DENIED);
    }
    verifyNoInteractions(f.current, f.targets, f.runtimes);
  }

  @Test
  void completeBindingRetainsEveryOwnerSourceAndOriginalRequest() throws Exception {
    var f = new Fixture();
    var result = f.call(f.request);
    assertThat(result.failure).isNull();
    var evidence = PrivateWorldPreparationReadGrpcCodec.fromResponse(f.request, result.value);
    assertThat(evidence.snapshot().preparation().originalRequest().toByteArray())
        .containsExactly(f.preparationRequest.canonicalPreimage());
    assertThat(evidence.snapshot().selection().originalRequest().toByteArray())
        .containsExactly(f.selectionRequest.canonicalPreimage());
    assertThat(evidence.snapshot().source().catalogReceiptDigest()).isEqualTo(DIGEST);
    assertThat(evidence.snapshot().source().targetPolicy()).isEqualTo(f.source.targetPolicy());
    assertThat(evidence.snapshot().target().receiptDigest()).isEqualTo(f.target.receiptDigest());
    assertThat(evidence.snapshot().target().realmId()).isEqualTo(f.target.realmId());
    assertThat(evidence.snapshot().runtimeAllocation().requestId())
        .isEqualTo(f.allocation.allocationRequestId());
    assertThat(evidence.snapshot().runtimeAllocation().gameInstanceId())
        .isEqualTo(f.allocation.gameInstanceId());
    assertThat(evidence.snapshot().runtimeAllocation().requestDigest())
        .isEqualTo(f.allocation.requestDigest());
    assertThat(evidence.snapshot().runtimeAllocation().receiptDigest())
        .isEqualTo(f.allocation.receiptDigest());
    assertThat(evidence.snapshot().admissionAllowed()).isFalse();
    assertThat(result.completed).isTrue();
  }

  @Test
  void absentAllocationExpiredWrongOriginalAndSourceOrReceiptFailuresNeverReturnSuccess()
      throws Exception {
    for (String failure :
        List.of(
            "missing-current",
            "missing-target",
            "expired",
            "wrong-original",
            "source",
            "receipt",
            "missing-runtime-allocation",
            "mismatched-runtime-identity")) {
      var f = new Fixture();
      var request = f.request;
      switch (failure) {
        case "missing-current" ->
            when(f.current.readCurrent(any(), any(), any(), any())).thenReturn(Optional.empty());
        case "missing-target" ->
            when(f.targets.readForWorldPreparation(any(), any())).thenReturn(Optional.empty());
        case "expired" ->
            when(f.current.readCurrent(any(), any(), any(), any()))
                .thenReturn(
                    Optional.of(
                        new GameSessionCurrentPrivatePlaytestRepository.Current(
                            f.selected.selection(),
                            new GameSessionInitialPlaytestLifecycleRepository.Snapshot(
                                f.preparation,
                                GameSessionInitialPlaytestLifecycleRepository.ReadState.EXPIRED,
                                NOW.plusSeconds(3600)))));
        case "wrong-original" ->
            request =
                new PrivateWorldPreparationReadGrpcCodec.Request(
                    UUID.randomUUID(), f.request.scope(), UUID.randomUUID());
        case "source" ->
            when(f.current.readCurrent(any(), any(), any(), any()))
                .thenThrow(
                    new GameSessionInitialPlaytestLifecycleRepository.SourceUnavailableException(
                        "Controlled source mismatch"));
        case "receipt" ->
            when(f.targets.readForWorldPreparation(any(), any()))
                .thenThrow(
                    new GameSessionPrivateFreshTargetRepository.InvalidPrivateTargetException(
                        "Controlled receipt mismatch"));
        case "missing-runtime-allocation" ->
            when(f.runtimes.readForWorldPreparation(any(), any())).thenReturn(Optional.empty());
        case "mismatched-runtime-identity" ->
            when(f.runtimes.readForWorldPreparation(any(), any()))
                .thenReturn(
                    Optional.of(
                        f.allocationWithGameInstance(
                            UUID.fromString("00000000-0000-4000-8000-000000000003"))));
        default -> throw new IllegalArgumentException(failure);
      }
      var result = f.call(request);
      assertThat(result.value).isNull();
      assertThat(result.completed).isFalse();
      assertThat(Status.fromThrowable(result.failure).getCode())
          .isEqualTo(Status.Code.FAILED_PRECONDITION);
    }
  }

  @Test
  void ambientSqlAndSynchronizationAreRejectedBeforeOwnerReads() throws Exception {
    var f = new Fixture();
    try {
      TransactionSynchronizationManager.setActualTransactionActive(true);
      assertThat(Status.fromThrowable(f.call(f.request).failure).getCode())
          .isEqualTo(Status.Code.FAILED_PRECONDITION);
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
    try {
      TransactionSynchronizationManager.initSynchronization();
      assertThat(Status.fromThrowable(f.call(f.request).failure).getCode())
          .isEqualTo(Status.Code.FAILED_PRECONDITION);
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
    verifyNoInteractions(f.current, f.targets, f.runtimes);
  }

  @Test
  void scopeMismatchAndConnectionFailureRemainTypedRefusals() throws Exception {
    var f = new Fixture();
    var other =
        new PrivateWorldPreparationReadGrpcCodec.Request(
            UUID.randomUUID(),
            new Scope("other", f.request.scope().tenantId(), "world", "private"),
            f.preparationRequest.requestId());
    assertThat(Status.fromThrowable(f.call(other).failure).getCode())
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(f.current, f.targets);
    when(f.current.readCurrent(any(), any(), any(), any()))
        .thenThrow(
            new org.jooq.exception.DataAccessException(
                "Controlled SQL outage", new SQLException("Unavailable", "08006")));
    var result = f.call(f.request);
    assertThat(Status.fromThrowable(result.failure).getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(result.value).isNull();
  }

  private static void withPeer(String uri, Runnable work) throws Exception {
    Context.current()
        .withValue(GrpcPeerIdentity.CONTEXT_KEY, GrpcPeerIdentity.parseUri(uri).orElseThrow())
        .run(work);
  }

  private static final class Fixture {
    final GameSessionCurrentPrivatePlaytestRepository current = mock();
    final GameSessionPrivateFreshTargetRepository targets = mock();
    final GameSessionPrivateFreshRuntimeAllocationRepository runtimes = mock();
    final UUID tenant = UUID.randomUUID(),
        preparationId = UUID.randomUUID(),
        lifecycle = UUID.randomUUID(),
        namespace = UUID.randomUUID();
    final GameSessionInitialPlaytestLifecycleRepository.Request preparationRequest =
        new GameSessionInitialPlaytestLifecycleRepository.Request(
            preparationId,
            NS,
            tenant,
            "world",
            "private",
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "release:private",
            DIGEST,
            InitializationMode.FRESH,
            NOW.plusSeconds(3600));
    final RealmEntryPolicy policy =
        RealmEntryPolicy.parse(
            """
        {"schemaVersion":1,"worldSlug":"world","worldDisplayName":"World","realmSlug":"private",
         "realmDisplayName":"Private","visible":true,"publicProduction":false,"stateScope":"ISOLATED","entryPolicy":"PRESEEDED_ONLY"}
        """,
            JsonMapper.builder().build());
    final GameSessionInitialPlaytestLifecycleRepository.SourceReference source =
        new GameSessionInitialPlaytestLifecycleRepository.SourceReference(
            preparationRequest.sourceCatalogRealmId(),
            preparationRequest.sourceCatalogRequestId(),
            1,
            DIGEST,
            preparationRequest.sourceIntakeOperationId(),
            DIGEST,
            UUID.randomUUID(),
            DIGEST,
            preparationRequest.canonicalVersionId(),
            preparationRequest.publishedReleaseBundleRef(),
            DIGEST,
            DIGEST,
            DIGEST,
            new RealmPolicyPublishedEvidence.Policy(
                UUID.randomUUID(),
                new RealmPolicySource.Policy(
                    UUID.randomUUID(), UUID.randomUUID(), "policy", policy),
                DIGEST));
    final GameSessionInitialPlaytestLifecycleRepository.Preparation preparation =
        new GameSessionInitialPlaytestLifecycleRepository.Preparation(
            preparationRequest,
            lifecycle,
            namespace,
            1,
            "PREPARING",
            source,
            preparationRequest.requestDigest(),
            NOW.minusSeconds(2));
    final GameSessionCurrentPrivatePlaytestRepository.Request selectionRequest =
        new GameSessionCurrentPrivatePlaytestRepository.Request(
            UUID.randomUUID(),
            new GameSessionCurrentPrivatePlaytestRepository.Scope(NS, tenant, "world", "private"),
            preparationId,
            lifecycle,
            null,
            null);
    final GameSessionCurrentPrivatePlaytestRepository.Current selected =
        new GameSessionCurrentPrivatePlaytestRepository.Current(
            new GameSessionCurrentPrivatePlaytestRepository.SelectionReceipt(
                selectionRequest, 1, selectionRequest.requestDigest(), NOW.minusSeconds(1)),
            new GameSessionInitialPlaytestLifecycleRepository.Snapshot(
                preparation,
                GameSessionInitialPlaytestLifecycleRepository.ReadState.INCOMPLETE,
                NOW));
    final CanonicalPrivateFreshTargetSnapshot target =
        new CanonicalPrivateFreshTargetSnapshot(
            NS,
            tenant,
            "world",
            "private",
            UUID.randomUUID(),
            "Private",
            false,
            false,
            "ISOLATED",
            namespace,
            "PRESEEDED_ONLY",
            1,
            UUID.randomUUID(),
            DIGEST,
            DIGEST,
            preparationId,
            lifecycle,
            1,
            "PREPARING",
            source.catalogRealmId(),
            source.targetPolicy().policyId(),
            DIGEST,
            source.canonicalVersionId(),
            source.publishedReleaseBundleRef(),
            DIGEST);
    final PrivateWorldPreparationReadGrpcCodec.Request request =
        new PrivateWorldPreparationReadGrpcCodec.Request(
            UUID.randomUUID(), new Scope(NS, tenant, "world", "private"), preparationId);
    final CanonicalPrivateFreshRuntimeAllocationSnapshot allocation = allocationSnapshot();
    final PrivateWorldPreparationReadGrpcService service;

    Fixture() {
      when(current.readCurrent(any(), any(), any(), any())).thenReturn(Optional.of(selected));
      when(targets.readForWorldPreparation(any(), any())).thenReturn(Optional.of(target));
      when(runtimes.readForWorldPreparation(any(), any())).thenReturn(Optional.of(allocation));
      var read =
          new TransactionOperations() {
            @Override
            public <T> T execute(
                org.springframework.transaction.support.TransactionCallback<T> callback) {
              return callback.doInTransaction(new SimpleTransactionStatus());
            }
          };
      service =
          new PrivateWorldPreparationReadGrpcService(
              current, targets, runtimes, read, NS, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private CanonicalPrivateFreshRuntimeAllocationSnapshot allocationSnapshot() {
      var commonScope = request.scope();
      var commonSelection =
          new PrivateWorldPreparationReadEvidence.Selection(
              selectionRequest.requestId(),
              null,
              null,
              selected.selection().headRevision(),
              selectionRequest.requestDigest(),
              selected.selection().selectedAt(),
              ByteString.copyFrom(selectionRequest.canonicalPreimage()));
      var commonPreparation =
          new PrivateWorldPreparationReadEvidence.Preparation(
              preparation.request().requestId(),
              preparation.playtestLifecycleId(),
              preparation.playableStateNamespaceId(),
              preparation.playtestStateGeneration(),
              preparation.preparationState(),
              preparation.request().initializationMode().name(),
              preparation.requestDigest(),
              preparation.createdAt(),
              preparation.request().forkExpiresAt(),
              ByteString.copyFrom(preparationRequest.canonicalPreimage()));
      var commonTarget =
          new PrivateWorldPreparationReadEvidence.Target(
              commonScope,
              target.realmId(),
              target.realmDisplayName(),
              target.visible(),
              target.publicProduction(),
              target.stateScope(),
              target.playableStateNamespaceId(),
              target.characterCreationPolicy(),
              target.catalogRevision(),
              target.creationRequestId(),
              target.requestDigest(),
              target.receiptDigest(),
              target.preparationRequestId(),
              target.playtestLifecycleId(),
              target.playtestStateGeneration(),
              target.preparationState(),
              target.sourceCatalogRealmId(),
              target.targetPolicyId(),
              target.targetPolicyDigest(),
              target.canonicalVersionId(),
              target.publishedReleaseBundleRef(),
              target.publishedReleaseBundleDigest());
      var commonAllocation =
          PrivateWorldPreparationReadEvidence.runtimeAllocation(
              commonScope,
              commonSelection,
              commonPreparation,
              commonTarget,
              RUNTIME_REQUEST_ID,
              GAME_INSTANCE_ID);
      return new CanonicalPrivateFreshRuntimeAllocationSnapshot(
          RUNTIME_REQUEST_ID,
          GAME_INSTANCE_ID,
          target.targetNamespace(),
          target.tenantId(),
          target.worldSlug(),
          target.realmSlug(),
          target.realmId(),
          target.catalogRevision(),
          target.creationRequestId(),
          target.requestDigest(),
          target.receiptDigest(),
          target.preparationRequestId(),
          preparation.requestDigest(),
          target.playtestLifecycleId(),
          target.playableStateNamespaceId(),
          target.playtestStateGeneration(),
          target.canonicalVersionId(),
          target.publishedReleaseBundleRef(),
          target.publishedReleaseBundleDigest(),
          selectionRequest.requestId(),
          selected.selection().headRevision(),
          selectionRequest.requestDigest(),
          commonAllocation.requestDigest(),
          commonAllocation.receiptDigest());
    }

    private CanonicalPrivateFreshRuntimeAllocationSnapshot allocationWithGameInstance(
        UUID gameInstanceId) {
      return new CanonicalPrivateFreshRuntimeAllocationSnapshot(
          allocation.allocationRequestId(),
          gameInstanceId,
          allocation.targetNamespace(),
          allocation.tenantId(),
          allocation.worldSlug(),
          allocation.realmSlug(),
          allocation.realmId(),
          allocation.catalogRevision(),
          allocation.catalogRequestId(),
          allocation.catalogRequestDigest(),
          allocation.catalogReceiptDigest(),
          allocation.preparationRequestId(),
          allocation.preparationRequestDigest(),
          allocation.playtestLifecycleId(),
          allocation.playableStateNamespaceId(),
          allocation.playtestStateGeneration(),
          allocation.canonicalVersionId(),
          allocation.publishedReleaseBundleRef(),
          allocation.publishedReleaseBundleDigest(),
          allocation.selectionRequestId(),
          allocation.selectionHeadRevision(),
          allocation.selectionRequestDigest(),
          allocation.requestDigest(),
          allocation.receiptDigest());
    }

    Result call(PrivateWorldPreparationReadGrpcCodec.Request selector) throws Exception {
      var result = new Result();
      withPeer(
          "spiffe://firemud/ns/" + NS + "/sa/world-management-service",
          () ->
              service.readPrivateWorldPreparation(
                  PrivateWorldPreparationReadGrpcCodec.toRequest(selector), result));
      return result;
    }
  }

  private static final class BuildResult
      implements StreamObserver<
          net.firedevops.firemud.gamesession.v1.ReadPrivateWorldPreparationBuildResponse> {
    private Throwable failure;

    @Override
    public void onNext(
        net.firedevops.firemud.gamesession.v1.ReadPrivateWorldPreparationBuildResponse value) {}

    @Override
    @SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Test observer retains exact failure.")
    public void onError(Throwable failure) {
      this.failure = failure;
    }

    @Override
    public void onCompleted() {}
  }

  private static final class Result implements StreamObserver<ReadPrivateWorldPreparationResponse> {
    private ReadPrivateWorldPreparationResponse value;
    private Throwable failure;
    private boolean completed;

    @Override
    public void onNext(ReadPrivateWorldPreparationResponse value) {
      this.value = value;
    }

    @Override
    @SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Test observer deliberately retains the exact failure for assertions.")
    public void onError(Throwable failure) {
      this.failure = failure;
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
