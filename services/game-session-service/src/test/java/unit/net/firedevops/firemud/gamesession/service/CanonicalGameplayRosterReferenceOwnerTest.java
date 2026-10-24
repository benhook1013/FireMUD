package net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import io.grpc.Status;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterEntryPolicy;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterResponse;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterTarget;
import net.firedevops.firemud.entitymanagement.v1.CanonicalPlayerCreatedRosterResponse;
import net.firedevops.firemud.entitymanagement.v1.CanonicalPlayerCreatedRosterSourceIdentity;
import net.firedevops.firemud.entitymanagement.v1.CanonicalSelectedPlayerCreatedCharacterResponse;
import net.firedevops.firemud.gamesession.charactercreate.GameSessionCharacterCreatePublicSourceService;
import net.firedevops.firemud.gamesession.charactercreate.GameSessionCharacterCreatePublicSourceService.ResolvedPlayerCreatedRosterSource;
import net.firedevops.firemud.gamesession.client.CanonicalGameplayRosterClient;
import net.firedevops.firemud.gamesession.client.CanonicalGameplayRosterRecoveryClient;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget;
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayRosterReferenceRepository;
import net.firedevops.firemud.gamesession.repository.CanonicalInitialAdmissionRepository;
import net.firedevops.firemud.gamesession.repository.CanonicalInitialAdmissionRepository.CurrentOpenSnapshot;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterReferenceState;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceRequest;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceResponse;
import net.firedevops.firemud.gamesession.v1.ReadPlayerCreatedRosterSourceRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import support.net.firedevops.firemud.gamesession.PublishedRealmPolicyEvidenceFixture;

/** Controlled orchestration doubles, not SQL proof or genuine source/provenance custody. */
class CanonicalGameplayRosterReferenceOwnerTest {
  private final CanonicalGameplayRosterReferenceRepository repository =
      mock(CanonicalGameplayRosterReferenceRepository.class);
  private final CanonicalPublishedPlayerRouteReadService published =
      mock(CanonicalPublishedPlayerRouteReadService.class);
  private final CanonicalPlayerRouteReadService routes =
      mock(CanonicalPlayerRouteReadService.class);
  private final CanonicalInitialAdmissionRepository admissions =
      mock(CanonicalInitialAdmissionRepository.class);
  private final CanonicalGameplayRosterRecoveryClient recovery =
      mock(CanonicalGameplayRosterRecoveryClient.class);
  private final GameSessionCharacterCreatePublicSourceService created =
      mock(GameSessionCharacterCreatePublicSourceService.class);
  // A private-view double tests consumption only; no constructor/factory bypass is introduced.
  private final ResolvedPlayerCreatedRosterSource source =
      mock(ResolvedPlayerCreatedRosterSource.class);
  private final CanonicalPlayableTarget route = route("test");
  private final CanonicalGameplayRosterTarget target =
      CanonicalGameplayRosterClient.expectedTarget(
              PublishedRealmPolicyEvidenceFixture.canonicalPublishedRoute())
          .toBuilder()
          .setEntryPolicy(
              CanonicalGameplayRosterEntryPolicy.CANONICAL_ROSTER_ENTRY_POLICY_PLAYER_CREATED)
          .build();
  private final CanonicalPlayerCreatedRosterSourceIdentity identity =
      CanonicalPlayerCreatedRosterSourceIdentity.newBuilder()
          .setSchemaVersion(1)
          .setDescriptorUuid(UUID.randomUUID().toString())
          .setDescriptorRevisionUuid(UUID.randomUUID().toString())
          .setDescriptorDigest("sha256:" + "1".repeat(64))
          .setAuthoredBindingUuid(UUID.randomUUID().toString())
          .setAuthoredBindingDigest("sha256:" + "2".repeat(64))
          .build();
  private final GetCanonicalGameplayRosterReferenceRequest request =
      CanonicalGameplayRosterReferenceEvidence.playerCreatedRequest(
          UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "test", target, identity);
  private final CurrentOpenSnapshot snapshot = snapshot(route, route.pointerVersion());
  private final GetCanonicalGameplayRosterReferenceResponse reserved =
      CanonicalGameplayRosterReferenceEvidence.playerCreatedResponse(
          request, 1, UUID.randomUUID(), null);
  private final CanonicalGameplayRosterReferenceOwner owner =
      new CanonicalGameplayRosterReferenceOwner(
          repository, transactions(), published, routes, admissions, recovery, created);

  @BeforeEach
  void controlledSource() {
    when(repository.find(request)).thenReturn(Optional.empty());
    when(repository.begin(request))
        .thenAnswer(
            ignored -> {
              inside();
              return reserved;
            });
    when(source.target()).thenReturn(target);
    when(source.sourceIdentity()).thenReturn(identity);
    when(source.route()).thenReturn(route);
    when(created.resolvePlayerCreatedRosterSource(any()))
        .thenAnswer(
            ignored -> {
              outside();
              return source;
            });
    when(admissions.readCurrentOpenSnapshotForRealm(
            "test", route.canonicalTenantId(), route.realmId()))
        .thenAnswer(
            ignored -> {
              outside();
              return snapshot;
            });
    when(admissions.lockExactCurrentOpenSnapshot(snapshot))
        .thenAnswer(
            ignored -> {
              inside();
              return snapshot;
            });
  }

  @Test
  void freshCreatedReservationResolvesExactSourceOutsideSqlThenLocksCurrentPointerInsideSql() {
    assertThat(owner.begin(request)).isSameAs(reserved);
    var calls = inOrder(repository, created, admissions);
    calls.verify(repository).find(request);
    calls.verify(created).resolvePlayerCreatedRosterSource(expectedSourceRequest());
    calls
        .verify(admissions)
        .readCurrentOpenSnapshotForRealm("test", route.canonicalTenantId(), route.realmId());
    calls.verify(repository).begin(request);
    calls.verify(admissions).lockExactCurrentOpenSnapshot(snapshot);
    verifyNoInteractions(published, routes, recovery);
    outside();
  }

  @Test
  void missingCreatedCollaboratorFailsClosedWhileSixArgumentCompositionStillExists() {
    var preseededComposition =
        new CanonicalGameplayRosterReferenceOwner(
            repository, transactions(), published, routes, admissions, recovery);
    assertThatThrownBy(() -> preseededComposition.begin(request))
        .isInstanceOf(CanonicalPublishedPlayerRouteReadService.StorageUnavailableException.class);
    verify(repository, never()).begin(any());
    verifyNoInteractions(created, published, admissions);
  }

  @Test
  void preseededRequestsContinueThroughTheExistingPublishedSourceReader() {
    var preseeded =
        CanonicalGameplayRosterReferenceEvidence.request(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "test",
            CanonicalGameplayRosterClient.expectedTarget(
                PublishedRealmPolicyEvidenceFixture.canonicalPublishedRoute()));
    when(repository.find(preseeded)).thenReturn(Optional.empty());
    var stale =
        new CanonicalPublishedPlayerRouteReadService.StaleAuthorityException(
            "published PRESEEDED_ONLY source changed");
    when(published.readCurrent(
            any(
                net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence.Request
                    .class)))
        .thenAnswer(
            ignored -> {
              outside();
              throw stale;
            });
    assertThatThrownBy(() -> owner.begin(preseeded)).isSameAs(stale);
    verifyNoInteractions(created, admissions);
    verify(repository, never()).begin(any());
  }

  @Test
  void retainedCreatedReceiptReplaysWithoutResolvingOrRebindingCurrentSource() {
    var retained =
        reserved.toBuilder()
            .setState(
                CanonicalGameplayRosterReferenceState.CANONICAL_GAMEPLAY_ROSTER_REFERENCE_RECORDED)
            .setCanonicalPlayerCreatedRosterReceipt(
                ByteString.copyFromUtf8("controlled retained bytes"))
            .build();
    when(repository.find(request)).thenReturn(Optional.of(retained));
    when(repository.replay(request)).thenReturn(retained);
    assertThat(owner.begin(request)).isSameAs(retained);
    verify(repository, never()).begin(any());
    verifyNoInteractions(created, published, admissions);
  }

  @Test
  void targetSourceAndNamespaceSubstitutionsRefuseBeforeReservation() {
    when(source.target())
        .thenReturn(target.toBuilder().setPointerVersion(target.getPointerVersion() + 1).build());
    assertStaleBeforeReservation();
    when(source.target()).thenReturn(target);
    when(source.sourceIdentity())
        .thenReturn(
            identity.toBuilder().setDescriptorRevisionUuid(UUID.randomUUID().toString()).build());
    assertStaleBeforeReservation();
    when(source.sourceIdentity()).thenReturn(identity);
    when(source.route()).thenReturn(route("foreign"));
    assertStaleBeforeReservation();
    verifyNoInteractions(admissions);
  }

  @Test
  void independentlyChangedCurrentPointerRefusesBeforeReservation() {
    when(admissions.readCurrentOpenSnapshotForRealm(
            "test", route.canonicalTenantId(), route.realmId()))
        .thenReturn(snapshot(route, route.pointerVersion() + 1));
    assertStaleBeforeReservation();
    verify(admissions, never()).lockExactCurrentOpenSnapshot(any());
  }

  @Test
  void currentPointerLockFailureEscapesWithoutReturningAReservedResult() {
    doThrow(new IllegalStateException("current pointer changed under lock"))
        .when(admissions)
        .lockExactCurrentOpenSnapshot(snapshot);
    assertThatThrownBy(() -> owner.begin(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("current pointer changed under lock");
    verify(repository).begin(request);
    // This callback seam proves ordering/propagation, not physical transaction rollback.
    outside();
  }

  @Test
  void sourceTransportCategoriesRemainTypedAndPreserveCauses() {
    var cases =
        List.of(
            Map.entry(
                Status.FAILED_PRECONDITION,
                CanonicalPublishedPlayerRouteReadService.StaleAuthorityException.class),
            Map.entry(
                Status.UNAVAILABLE,
                CanonicalPublishedPlayerRouteReadService.StorageUnavailableException.class),
            Map.entry(
                Status.DEADLINE_EXCEEDED,
                CanonicalPublishedPlayerRouteReadService.StorageUnavailableException.class),
            Map.entry(
                Status.INVALID_ARGUMENT,
                CanonicalPublishedPlayerRouteReadService.InvalidAuthorityException.class));
    for (var entry : cases) {
      var failure = entry.getKey().withDescription("private source detail").asRuntimeException();
      doThrow(failure).when(created).resolvePlayerCreatedRosterSource(any());
      assertThatThrownBy(() -> owner.begin(request))
          .isInstanceOf(entry.getValue())
          .hasCause(failure);
    }
    // Controlled exception double checks orchestration; actual dependency classification is proved
    // by the source service tests.
    var dependency =
        mock(
            GameSessionCharacterCreatePublicSourceService.SourceDependencyUnavailableException
                .class);
    doThrow(dependency).when(created).resolvePlayerCreatedRosterSource(any());
    assertThatThrownBy(() -> owner.begin(request))
        .isInstanceOf(CanonicalPublishedPlayerRouteReadService.StorageUnavailableException.class)
        .hasCause(dependency);
    var unexpected = Status.INTERNAL.asRuntimeException();
    doThrow(unexpected).when(created).resolvePlayerCreatedRosterSource(any());
    assertThatThrownBy(() -> owner.begin(request)).isSameAs(unexpected);
    verify(repository, never()).begin(any());
    verifyNoInteractions(admissions);
  }

  @Test
  void staleUnissuedReplayClosesDeniedButUnavailableReplayRemainsProtected() {
    when(repository.find(request)).thenReturn(Optional.of(reserved));
    when(repository.replay(request)).thenReturn(reserved);
    doAnswer(
            ignored -> {
              inside();
              return null;
            })
        .when(repository)
        .completeUnissuedDenied(request);
    doThrow(Status.FAILED_PRECONDITION.asRuntimeException())
        .when(created)
        .resolvePlayerCreatedRosterSource(any());
    assertThatThrownBy(() -> owner.begin(request))
        .isInstanceOf(CanonicalPublishedPlayerRouteReadService.StaleAuthorityException.class);
    verify(repository).completeUnissuedDenied(request);
    doThrow(Status.UNAVAILABLE.asRuntimeException())
        .when(created)
        .resolvePlayerCreatedRosterSource(any());
    assertThatThrownBy(() -> owner.begin(request))
        .isInstanceOf(CanonicalPublishedPlayerRouteReadService.StorageUnavailableException.class);
    var dependency =
        mock(
            GameSessionCharacterCreatePublicSourceService.SourceDependencyUnavailableException
                .class);
    doThrow(dependency).when(created).resolvePlayerCreatedRosterSource(any());
    assertThatThrownBy(() -> owner.begin(request))
        .isInstanceOf(CanonicalPublishedPlayerRouteReadService.StorageUnavailableException.class)
        .hasCause(dependency);
    verify(repository).completeUnissuedDenied(request);
    verify(repository, never()).begin(any());
  }

  @Test
  void unissuedOwnerReadRequiresSourceButRecoveryAndClosedEvidenceRemainReadable() {
    when(repository.read(request)).thenReturn(reserved);
    assertThat(owner.read(request)).isSameAs(reserved);
    verify(created).resolvePlayerCreatedRosterSource(expectedSourceRequest());
    doThrow(new AssertionError("source must not be reread"))
        .when(created)
        .resolvePlayerCreatedRosterSource(any());
    var recovering = reserved.toBuilder().setRecoveryUuid(UUID.randomUUID().toString()).build();
    var closed = reserved.toBuilder().setTerminalOutcome("DENIED").build();
    var recorded =
        reserved.toBuilder()
            .setState(
                CanonicalGameplayRosterReferenceState.CANONICAL_GAMEPLAY_ROSTER_REFERENCE_RECORDED)
            .setCanonicalPlayerCreatedRosterReceipt(ByteString.copyFromUtf8("retained"))
            .build();
    for (var original : List.of(recovering, closed, recorded)) {
      when(repository.read(request)).thenReturn(original);
      assertThat(owner.read(request)).isSameAs(original);
    }
  }

  @Test
  void createdReceiptWritesForwardDistinctCompleteValuesWithinShortTransactions() {
    var roster =
        CanonicalPlayerCreatedRosterResponse.newBuilder().setSourceIdentity(identity).build();
    var selected =
        CanonicalSelectedPlayerCreatedCharacterResponse.newBuilder()
            .setCreationOperationUuid(UUID.randomUUID().toString())
            .build();
    when(repository.recordPlayerCreated(request, roster))
        .thenAnswer(
            ignored -> {
              inside();
              return reserved;
            });
    doAnswer(
            ignored -> {
              inside();
              return null;
            })
        .when(repository)
        .recordSelectedPlayerCreatedCharacter(request, selected);
    assertThat(owner.recordPlayerCreated(request, roster)).isSameAs(reserved);
    owner.recordSelectedPlayerCreatedCharacter(request, selected);
    verify(repository).recordSelectedPlayerCreatedCharacter(request, selected);
    var oldReceipt = CanonicalGameplayRosterResponse.getDefaultInstance();
    doThrow(new IllegalArgumentException("strict PRESEEDED_ONLY repository method"))
        .when(repository)
        .record(request, oldReceipt);
    assertThatThrownBy(() -> owner.record(request, oldReceipt))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(created, published, admissions);
    outside();
  }

  @Test
  void ambientTransactionsRefuseBeforeSourceOrStorage() {
    try {
      TransactionSynchronizationManager.setActualTransactionActive(true);
      assertThatThrownBy(() -> owner.begin(request)).isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(
              () ->
                  owner.recordPlayerCreated(
                      request, CanonicalPlayerCreatedRosterResponse.getDefaultInstance()))
          .isInstanceOf(IllegalStateException.class);
      TransactionSynchronizationManager.setActualTransactionActive(false);
      TransactionSynchronizationManager.initSynchronization();
      assertThatThrownBy(
              () ->
                  owner.recordSelectedPlayerCreatedCharacter(
                      request,
                      CanonicalSelectedPlayerCreatedCharacterResponse.getDefaultInstance()))
          .isInstanceOf(IllegalStateException.class);
      verifyNoInteractions(repository, created, published, admissions);
    } finally {
      TransactionSynchronizationManager.clear();
    }
  }

  private void assertStaleBeforeReservation() {
    assertThatThrownBy(() -> owner.begin(request))
        .isInstanceOf(CanonicalPublishedPlayerRouteReadService.StaleAuthorityException.class);
    verify(repository, never()).begin(any());
  }

  private ReadPlayerCreatedRosterSourceRequest expectedSourceRequest() {
    return ReadPlayerCreatedRosterSourceRequest.newBuilder()
        .setSchemaVersion(1)
        .setReadRequestId(request.getOperationUuid())
        .setTargetNamespace("test")
        .setCanonicalTenantId(target.getTenantUuid())
        .setRealmId(target.getRealmUuid())
        .setWorldSlug(target.getWorldSlug())
        .setRealmSlug(target.getRealmSlug())
        .setPlayableStateNamespaceId(target.getPlayableStateNamespaceUuid())
        .setPlayableStateScope("SHARED")
        .setCanonicalGameInstanceId(target.getGameInstanceUuid())
        .setCatalogRevision(target.getCatalogRevision())
        .setPointerVersion(target.getPointerVersion())
        .setExpectedCanonicalVersionId(target.getCanonicalVersionUuid())
        .setActiveWorldEpoch(target.getActiveWorldEpoch())
        .build();
  }

  private static CanonicalPlayableTarget route(String namespace) {
    var r = PublishedRealmPolicyEvidenceFixture.canonicalPublishedRoute().route();
    return new CanonicalPlayableTarget(
        namespace,
        r.tenantSlug(),
        r.canonicalTenantId(),
        r.worldSlug(),
        r.worldDisplayName(),
        r.realmId(),
        r.realmSlug(),
        r.realmDisplayName(),
        r.gameSessionTenantId(),
        r.gameInstanceId(),
        r.playableStateNamespaceId(),
        r.playableStateScope(),
        r.canonicalGameInstanceId(),
        r.canonicalVersionId(),
        r.runtimeVersionId(),
        r.catalogRevision(),
        r.pointerVersion(),
        r.admissionPointerSnapshotDigest(),
        r.activeWorldEpoch(),
        r.initialAdmissionRequestId(),
        r.initialAdmissionRequestDigest(),
        r.initialAdmissionOriginKind(),
        r.expectedPriorPointerVersion(),
        r.holdId(),
        r.holdFence(),
        r.holdBindingDigest(),
        r.auditEventId(),
        r.ownerProofDigest(),
        r.ownerProofOutcome(),
        r.positiveDurableAbort(),
        r.ownerProofTerminalAt(),
        "PLAYER_CREATED");
  }

  private static CurrentOpenSnapshot snapshot(CanonicalPlayableTarget r, long pointer) {
    return new CurrentOpenSnapshot(
        new CanonicalInitialAdmissionOwnerProof(
            CanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED,
            r.initialAdmissionRequestId(),
            r.initialAdmissionRequestDigest(),
            r.targetNamespace(),
            r.canonicalTenantId(),
            r.worldSlug(),
            r.realmId(),
            r.playableStateNamespaceId(),
            r.playableStateScope(),
            r.canonicalGameInstanceId(),
            r.canonicalVersionId(),
            r.activeWorldEpoch(),
            r.catalogRevision(),
            r.initialAdmissionOriginKind(),
            r.expectedPriorPointerVersion(),
            r.holdId(),
            r.holdFence(),
            r.holdBindingDigest(),
            pointer,
            r.auditEventId(),
            r.ownerProofDigest(),
            false,
            r.ownerProofTerminalAt()),
        r.admissionPointerSnapshotDigest());
  }

  private static TransactionOperations transactions() {
    return new TransactionOperations() {
      @Override
      public <T> T execute(TransactionCallback<T> action) {
        outside();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
          return action.doInTransaction(new SimpleTransactionStatus());
        } finally {
          TransactionSynchronizationManager.clear();
        }
      }
    };
  }

  private static void outside() {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
  }

  private static void inside() {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
    assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isTrue();
  }
}
