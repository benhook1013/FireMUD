package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateClient;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationReadClient;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationReadEvidence;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding.PublishIntent;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding.VisibilityFence;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionReadClient;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionReadEvidence;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceEvidence.Checkpoint;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceEvidence.FrozenAttempt;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceEvidence.OwnerBinding;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class WorldSelectedDraftPublicationFreezeServiceTest {
  private static final String NAMESPACE = "firemud";
  private static final UUID TENANT = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID VERSION = uuid("22222222-2222-4222-8222-222222222222");
  private static final long GAME_DESIGN_VERSION = 17L;
  private static final long VERSION_EPOCH = 9L;

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void callerAuthenticationPrecedesAnyRepositoryOrRemoteRead() {
    Fixture fixture = fixture();
    var collaborators = new Collaborators();
    var service = service(fixture, collaborators);

    assertThatThrownBy(
            () ->
                withoutPeer(
                    () ->
                        service.freeze(
                            fixture.evidence(), fixture.plan(), fixture.accountBinding())))
        .isInstanceOf(SecurityException.class);
    assertThatThrownBy(
            () ->
                withPeer(
                    peer("other", "game-design-service"),
                    () ->
                        service.freeze(
                            fixture.evidence(), fixture.plan(), fixture.accountBinding())))
        .isInstanceOf(SecurityException.class);
    assertThatThrownBy(
            () ->
                withPeer(
                    peer(NAMESPACE, "game-session-service"),
                    () ->
                        service.freeze(
                            fixture.evidence(), fixture.plan(), fixture.accountBinding())))
        .isInstanceOf(SecurityException.class);

    collaborators.verifyNoReadsOrWrites();
    assertThat(collaborators.transactionManager.commits).isZero();
  }

  @Test
  void exactCommittedRetryUsesFrozenAccountCorrelationBeforeMutableRemoteReads() {
    Fixture fixture = fixture();
    var collaborators = new Collaborators();
    FrozenAttempt prior = frozen(fixture.evidence(), fixture.binding());
    when(collaborators.fence.readAttempt(fixture.evidence())).thenReturn(Optional.of(prior));
    when(collaborators.authorizationRepository.readCommitted(prior))
        .thenReturn(Optional.of(fixture.accountBinding()));
    var service = service(fixture, collaborators);

    FrozenAttempt result =
        withGameDesign(
            () -> service.freeze(fixture.evidence(), fixture.plan(), fixture.accountBinding()));

    assertThat(result).isEqualTo(prior);
    verifyNoInteractions(
        collaborators.selectionClient,
        collaborators.versionStateClient,
        collaborators.accountClient);
    verify(collaborators.checkpointRepository, never()).capture(any(), any());
    verify(collaborators.fence, never()).claimFreeze(any(), any());
    assertThat(collaborators.transactionManager.commits).isZero();
  }

  @Test
  void changedAccountOperationOrFenceDeniesAnOtherwiseExactRetry() {
    Fixture fixture = fixture();
    var collaborators = new Collaborators();
    FrozenAttempt prior = frozen(fixture.evidence(), fixture.binding());
    var changed =
        accountBinding(
            fixture.selection(),
            uuid("33333333-3333-4333-8333-333333333333"),
            fixture.accountBinding().fenceId());
    when(collaborators.fence.readAttempt(fixture.evidence())).thenReturn(Optional.of(prior));
    when(collaborators.authorizationRepository.readCommitted(prior))
        .thenReturn(Optional.of(fixture.accountBinding()));
    var service = service(fixture, collaborators);

    assertThatThrownBy(
            () -> withGameDesign(() -> service.freeze(fixture.evidence(), fixture.plan(), changed)))
        .isInstanceOf(WorldDesignPublicationFenceRepository.ConflictException.class)
        .hasMessageContaining("Account");
    verifyNoInteractions(
        collaborators.selectionClient,
        collaborators.versionStateClient,
        collaborators.accountClient);
    assertThat(collaborators.transactionManager.commits).isZero();

    var changedFence =
        accountBinding(
            fixture.selection(),
            fixture.accountBinding().operationId(),
            uuid("14141414-1414-4414-8414-141414141414"));
    assertThatThrownBy(
            () ->
                withGameDesign(
                    () -> service.freeze(fixture.evidence(), fixture.plan(), changedFence)))
        .isInstanceOf(WorldDesignPublicationFenceRepository.ConflictException.class)
        .hasMessageContaining("Account");
    verifyNoInteractions(
        collaborators.selectionClient,
        collaborators.versionStateClient,
        collaborators.accountClient);
    assertThat(collaborators.transactionManager.commits).isZero();
  }

  @Test
  void unqualifiedHistoricalAttemptIsDeniedWithoutCurrentStateOrHeldResampling() {
    Fixture fixture = fixture();
    var collaborators = new Collaborators();
    FrozenAttempt prior = frozen(fixture.evidence(), fixture.binding());
    when(collaborators.fence.readAttempt(fixture.evidence())).thenReturn(Optional.of(prior));
    when(collaborators.authorizationRepository.readCommitted(prior)).thenReturn(Optional.empty());
    var service = service(fixture, collaborators);

    assertThatThrownBy(
            () ->
                withGameDesign(
                    () ->
                        service.freeze(
                            fixture.evidence(), fixture.plan(), fixture.accountBinding())))
        .isInstanceOf(WorldDesignPublicationFenceRepository.ConflictException.class)
        .hasMessageContaining("no original Account");
    verifyNoInteractions(
        collaborators.selectionClient,
        collaborators.versionStateClient,
        collaborators.accountClient);
    verify(collaborators.fence, never()).claimFreeze(any(), any());
    assertThat(collaborators.transactionManager.commits).isZero();
  }

  @Test
  void completeAccountSelectionRelationIsRejectedBeforeStorageOrTransport() {
    Fixture fixture = fixture();
    var collaborators = new Collaborators();
    var changedSelection =
        selection(fixture.binding(), "changed-publication-request", VERSION_EPOCH, "different");
    var changedOrder =
        accountBinding(changedSelection, uuid("15151515-1515-4515-8515-151515151515"));
    var service = service(fixture, collaborators);

    assertThatThrownBy(
            () ->
                withGameDesign(
                    () -> service.freeze(fixture.evidence(), fixture.plan(), changedOrder)))
        .isInstanceOf(WorldDesignPublicationFenceRepository.ConflictException.class)
        .hasMessageContaining("not exact");
    collaborators.verifyNoReadsOrWrites();
    assertThat(collaborators.transactionManager.commits).isZero();
  }

  @Test
  void selectedReadMustEchoExactImmutableSelectionBeforeStateOrAccountReads() {
    Fixture fixture = fixture();
    var collaborators = new Collaborators();
    when(collaborators.fence.readAttempt(fixture.evidence())).thenReturn(Optional.empty());
    when(collaborators.intakeRepository.read(NAMESPACE, fixture.evidence().intakeRequestId()))
        .thenReturn(Optional.of(fixture.intake()));
    var changedSelection =
        selection(fixture.binding(), "other-selection", VERSION_EPOCH, "other immutable selection");
    when(collaborators.selectionClient.read(any()))
        .thenAnswer(
            invocation ->
                selectionRead(
                    AuthoredDraftPublishSelectionReadEvidence.Request.create(
                        NAMESPACE, changedSelection)));
    var service = service(fixture, collaborators);

    assertThatThrownBy(
            () ->
                withGameDesign(
                    () ->
                        service.freeze(
                            fixture.evidence(), fixture.plan(), fixture.accountBinding())))
        .isInstanceOf(WorldDesignPublicationFenceRepository.ConflictException.class)
        .hasMessageContaining("selected Draft read");
    verify(collaborators.versionStateClient, never()).read(any());
    verify(collaborators.accountClient, never()).read(any());
    verify(collaborators.fence, never()).claimFreeze(any(), any());
    assertThat(collaborators.transactionManager.commits).isZero();
  }

  @Test
  void firstFreezeReadsSelectionCurrentDraftAndAccountHeldBeforeOwnerTransaction() {
    Fixture fixture = fixture();
    var collaborators = new Collaborators();
    FrozenAttempt committed = frozen(fixture.evidence(), fixture.binding());
    when(collaborators.intakeRepository.read(NAMESPACE, fixture.evidence().intakeRequestId()))
        .thenReturn(Optional.of(fixture.intake()));
    when(collaborators.fence.readAttempt(fixture.evidence()))
        .thenReturn(Optional.empty(), Optional.of(committed));
    when(collaborators.selectionClient.read(any()))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
              return selectionRead(invocation.getArgument(0));
            });
    when(collaborators.versionStateClient.read(any()))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
              return AuthoredWorldVersionStateEvidence.create(
                  invocation.getArgument(0),
                  fixture.source(),
                  VERSION,
                  VersionLifecycleState.VERSION_LIFECYCLE_STATE_DRAFT,
                  VERSION_EPOCH);
            });
    when(collaborators.accountClient.read(any()))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
              return accountRead(invocation.getArgument(0));
            });
    when(collaborators.checkpointRepository.capture(fixture.evidence(), fixture.plan()))
        .thenReturn(fixture.checkpoint());
    when(collaborators.fence.claimFreeze(eq(fixture.evidence()), any()))
        .thenAnswer(
            invocation -> {
              Checkpoint checkpoint = ((Supplier<Checkpoint>) invocation.getArgument(1)).get();
              return new FrozenAttempt(
                  fixture.evidence(), committed.publicationFence(), checkpoint);
            });
    when(collaborators.authorizationRepository.retainOrRequireExact(
            committed, fixture.accountBinding(), true))
        .thenReturn(fixture.accountBinding());
    when(collaborators.authorizationRepository.readCommitted(committed))
        .thenReturn(Optional.of(fixture.accountBinding()));
    var service = service(fixture, collaborators);

    FrozenAttempt result =
        withGameDesign(
            () -> service.freeze(fixture.evidence(), fixture.plan(), fixture.accountBinding()));

    assertThat(result).isEqualTo(committed);
    assertThat(collaborators.transactionManager.commits).isEqualTo(1);
    assertThat(collaborators.transactionManager.rollbacks).isZero();
    assertThat(collaborators.transactionManager.startedWith.getPropagationBehavior())
        .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    assertThat(collaborators.transactionManager.startedWith.getIsolationLevel())
        .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
    assertThat(collaborators.transactionManager.startedWith.isReadOnly()).isFalse();
    var order =
        inOrder(
            collaborators.selectionClient,
            collaborators.versionStateClient,
            collaborators.accountClient,
            collaborators.fence,
            collaborators.authorizationRepository);
    order.verify(collaborators.selectionClient).read(any());
    order.verify(collaborators.versionStateClient).read(any());
    order.verify(collaborators.accountClient).read(any());
    order.verify(collaborators.fence).claimFreeze(eq(fixture.evidence()), any());
    order
        .verify(collaborators.authorizationRepository)
        .retainOrRequireExact(committed, fixture.accountBinding(), true);
    verify(collaborators.checkpointRepository).capture(fixture.evidence(), fixture.plan());
  }

  @Test
  void currentStateMustStillBeExactDraftEpochBeforeAccountReadOrFreeze() {
    Fixture fixture = fixture();
    var collaborators = new Collaborators();
    when(collaborators.intakeRepository.read(NAMESPACE, fixture.evidence().intakeRequestId()))
        .thenReturn(Optional.of(fixture.intake()));
    when(collaborators.fence.readAttempt(fixture.evidence())).thenReturn(Optional.empty());
    when(collaborators.selectionClient.read(any()))
        .thenAnswer(invocation -> selectionRead(invocation.getArgument(0)));
    when(collaborators.versionStateClient.read(any()))
        .thenAnswer(
            invocation ->
                AuthoredWorldVersionStateEvidence.create(
                    invocation.getArgument(0),
                    fixture.source(),
                    VERSION,
                    VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED,
                    VERSION_EPOCH));
    var service = service(fixture, collaborators);

    assertThatThrownBy(
            () ->
                withGameDesign(
                    () ->
                        service.freeze(
                            fixture.evidence(), fixture.plan(), fixture.accountBinding())))
        .isInstanceOf(WorldDesignPublicationFenceRepository.ConflictException.class)
        .hasMessageContaining("DRAFT epoch");
    verify(collaborators.accountClient, never()).read(any());
    verify(collaborators.fence, never()).claimFreeze(any(), any());
    assertThat(collaborators.transactionManager.commits).isZero();
  }

  @Test
  void staleCurrentEpochIsRejectedBeforeAccountReadOrFreeze() {
    Fixture fixture = fixture();
    var collaborators = new Collaborators();
    when(collaborators.intakeRepository.read(NAMESPACE, fixture.evidence().intakeRequestId()))
        .thenReturn(Optional.of(fixture.intake()));
    when(collaborators.fence.readAttempt(fixture.evidence())).thenReturn(Optional.empty());
    when(collaborators.selectionClient.read(any()))
        .thenAnswer(invocation -> selectionRead(invocation.getArgument(0)));
    when(collaborators.versionStateClient.read(any()))
        .thenAnswer(
            invocation ->
                AuthoredWorldVersionStateEvidence.create(
                    invocation.getArgument(0),
                    fixture.source(),
                    VERSION,
                    VersionLifecycleState.VERSION_LIFECYCLE_STATE_DRAFT,
                    VERSION_EPOCH + 1L));
    var service = service(fixture, collaborators);

    assertThatThrownBy(
            () ->
                withGameDesign(
                    () ->
                        service.freeze(
                            fixture.evidence(), fixture.plan(), fixture.accountBinding())))
        .isInstanceOf(WorldDesignPublicationFenceRepository.ConflictException.class)
        .hasMessageContaining("DRAFT epoch");
    verify(collaborators.accountClient, never()).read(any());
    verify(collaborators.fence, never()).claimFreeze(any(), any());
    assertThat(collaborators.transactionManager.commits).isZero();
  }

  @Test
  void changedCurrentSourceSelectorIsRejectedBeforeAccountReadOrFreeze() {
    Fixture fixture = fixture();
    var collaborators = new Collaborators();
    when(collaborators.intakeRepository.read(NAMESPACE, fixture.evidence().intakeRequestId()))
        .thenReturn(Optional.of(fixture.intake()));
    when(collaborators.fence.readAttempt(fixture.evidence())).thenReturn(Optional.empty());
    when(collaborators.selectionClient.read(any()))
        .thenAnswer(invocation -> selectionRead(invocation.getArgument(0)));
    when(collaborators.versionStateClient.read(any()))
        .thenAnswer(
            invocation -> {
              AuthoredWorldVersionStateEvidence.Request expected = invocation.getArgument(0);
              AuthoredWorldSourceEvidence changedSource = changedSource(fixture.source());
              var changedRequest =
                  new AuthoredWorldVersionStateEvidence.Request(
                      1,
                      NAMESPACE,
                      expected.readRequestId(),
                      expected.canonicalTenantId(),
                      changedSource.worldSlug(),
                      changedSource.operationId(),
                      changedSource.evidenceDigest(),
                      expected.versionId());
              return AuthoredWorldVersionStateEvidence.create(
                  changedRequest,
                  changedSource,
                  VERSION,
                  VersionLifecycleState.VERSION_LIFECYCLE_STATE_DRAFT,
                  VERSION_EPOCH);
            });
    var service = service(fixture, collaborators);

    assertThatThrownBy(
            () ->
                withGameDesign(
                    () ->
                        service.freeze(
                            fixture.evidence(), fixture.plan(), fixture.accountBinding())))
        .isInstanceOf(WorldDesignPublicationFenceRepository.ConflictException.class)
        .hasMessageContaining("DRAFT epoch");
    verify(collaborators.accountClient, never()).read(any());
    verify(collaborators.fence, never()).claimFreeze(any(), any());
    assertThat(collaborators.transactionManager.commits).isZero();
  }

  @Test
  void accountReadMustEchoExactHeldOrderBeforeOwnerFreeze() {
    Fixture fixture = fixture();
    var collaborators = new Collaborators();
    when(collaborators.intakeRepository.read(NAMESPACE, fixture.evidence().intakeRequestId()))
        .thenReturn(Optional.of(fixture.intake()));
    when(collaborators.fence.readAttempt(fixture.evidence())).thenReturn(Optional.empty());
    when(collaborators.selectionClient.read(any()))
        .thenAnswer(invocation -> selectionRead(invocation.getArgument(0)));
    when(collaborators.versionStateClient.read(any()))
        .thenAnswer(
            invocation ->
                AuthoredWorldVersionStateEvidence.create(
                    invocation.getArgument(0),
                    fixture.source(),
                    VERSION,
                    VersionLifecycleState.VERSION_LIFECYCLE_STATE_DRAFT,
                    VERSION_EPOCH));
    var changed = accountBinding(fixture.selection(), uuid("16161616-1616-4616-8616-161616161616"));
    when(collaborators.accountClient.read(any()))
        .thenAnswer(
            invocation ->
                accountRead(
                    AccountPublicationAuthorizationReadEvidence.Request.create(
                        NAMESPACE, changed)));
    var service = service(fixture, collaborators);

    assertThatThrownBy(
            () ->
                withGameDesign(
                    () ->
                        service.freeze(
                            fixture.evidence(), fixture.plan(), fixture.accountBinding())))
        .isInstanceOf(WorldDesignPublicationFenceRepository.ConflictException.class)
        .hasMessageContaining("HELD read");
    verify(collaborators.fence, never()).claimFreeze(any(), any());
    assertThat(collaborators.transactionManager.commits).isZero();
  }

  @Test
  void unavailableAccountReadDoesNotCreateOrAcknowledgeFreeze() {
    Fixture fixture = fixture();
    var collaborators = new Collaborators();
    when(collaborators.intakeRepository.read(NAMESPACE, fixture.evidence().intakeRequestId()))
        .thenReturn(Optional.of(fixture.intake()));
    when(collaborators.fence.readAttempt(fixture.evidence())).thenReturn(Optional.empty());
    when(collaborators.selectionClient.read(any()))
        .thenAnswer(invocation -> selectionRead(invocation.getArgument(0)));
    when(collaborators.versionStateClient.read(any()))
        .thenAnswer(
            invocation ->
                AuthoredWorldVersionStateEvidence.create(
                    invocation.getArgument(0),
                    fixture.source(),
                    VERSION,
                    VersionLifecycleState.VERSION_LIFECYCLE_STATE_DRAFT,
                    VERSION_EPOCH));
    when(collaborators.accountClient.read(any()))
        .thenThrow(new IllegalStateException("Account authorization read unavailable"));
    var service = service(fixture, collaborators);

    assertThatThrownBy(
            () ->
                withGameDesign(
                    () ->
                        service.freeze(
                            fixture.evidence(), fixture.plan(), fixture.accountBinding())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unavailable");
    verify(collaborators.fence, never()).claimFreeze(any(), any());
    assertThat(collaborators.transactionManager.commits).isZero();
  }

  @Test
  void transactionFailureAfterClaimRollsBackAndReturnsNoAcknowledgement() {
    Fixture fixture = fixture();
    var collaborators = new Collaborators();
    FrozenAttempt claimed = frozen(fixture.evidence(), fixture.binding());
    when(collaborators.intakeRepository.read(NAMESPACE, fixture.evidence().intakeRequestId()))
        .thenReturn(Optional.of(fixture.intake()));
    when(collaborators.fence.readAttempt(fixture.evidence())).thenReturn(Optional.empty());
    when(collaborators.selectionClient.read(any()))
        .thenAnswer(invocation -> selectionRead(invocation.getArgument(0)));
    when(collaborators.versionStateClient.read(any()))
        .thenAnswer(
            invocation ->
                AuthoredWorldVersionStateEvidence.create(
                    invocation.getArgument(0),
                    fixture.source(),
                    VERSION,
                    VersionLifecycleState.VERSION_LIFECYCLE_STATE_DRAFT,
                    VERSION_EPOCH));
    when(collaborators.accountClient.read(any()))
        .thenAnswer(invocation -> accountRead(invocation.getArgument(0)));
    when(collaborators.checkpointRepository.capture(fixture.evidence(), fixture.plan()))
        .thenReturn(fixture.checkpoint());
    when(collaborators.fence.claimFreeze(eq(fixture.evidence()), any()))
        .thenAnswer(
            invocation -> {
              ((Supplier<Checkpoint>) invocation.getArgument(1)).get();
              return claimed;
            });
    when(collaborators.authorizationRepository.retainOrRequireExact(
            claimed, fixture.accountBinding(), true))
        .thenThrow(
            new WorldDesignPublicationFenceRepository.ConflictException(
                "qualification readback failed"));
    var service = service(fixture, collaborators);

    assertThatThrownBy(
            () ->
                withGameDesign(
                    () ->
                        service.freeze(
                            fixture.evidence(), fixture.plan(), fixture.accountBinding())))
        .isInstanceOf(WorldDesignPublicationFenceRepository.ConflictException.class)
        .hasMessageContaining("qualification readback");
    assertThat(collaborators.transactionManager.commits).isZero();
    assertThat(collaborators.transactionManager.rollbacks).isEqualTo(1);
    verify(collaborators.fence).readAttempt(fixture.evidence());
  }

  @Test
  void raceLoserCannotAttachAccountOrderToAnExistingUnqualifiedFreeze() {
    Fixture fixture = fixture();
    var collaborators = new Collaborators();
    FrozenAttempt unqualified = frozen(fixture.evidence(), fixture.binding());
    when(collaborators.intakeRepository.read(NAMESPACE, fixture.evidence().intakeRequestId()))
        .thenReturn(Optional.of(fixture.intake()));
    when(collaborators.fence.readAttempt(fixture.evidence())).thenReturn(Optional.empty());
    when(collaborators.selectionClient.read(any()))
        .thenAnswer(invocation -> selectionRead(invocation.getArgument(0)));
    when(collaborators.versionStateClient.read(any()))
        .thenAnswer(
            invocation ->
                AuthoredWorldVersionStateEvidence.create(
                    invocation.getArgument(0),
                    fixture.source(),
                    VERSION,
                    VersionLifecycleState.VERSION_LIFECYCLE_STATE_DRAFT,
                    VERSION_EPOCH));
    when(collaborators.accountClient.read(any()))
        .thenAnswer(invocation -> accountRead(invocation.getArgument(0)));
    when(collaborators.fence.claimFreeze(eq(fixture.evidence()), any())).thenReturn(unqualified);
    when(collaborators.authorizationRepository.retainOrRequireExact(
            unqualified, fixture.accountBinding(), false))
        .thenThrow(
            new WorldDesignPublicationFenceRepository.ConflictException(
                "unqualified race winner cannot be attached to this Account order"));
    var service = service(fixture, collaborators);

    assertThatThrownBy(
            () ->
                withGameDesign(
                    () ->
                        service.freeze(
                            fixture.evidence(), fixture.plan(), fixture.accountBinding())))
        .isInstanceOf(WorldDesignPublicationFenceRepository.ConflictException.class)
        .hasMessageContaining("cannot be attached");
    verify(collaborators.checkpointRepository, never()).capture(any(), any());
    verify(collaborators.fence).readAttempt(fixture.evidence());
    assertThat(collaborators.transactionManager.commits).isZero();
    assertThat(collaborators.transactionManager.rollbacks).isEqualTo(1);
  }

  @Test
  void ambientTransactionIsRejectedBeforeAnyRead() {
    Fixture fixture = fixture();
    var collaborators = new Collaborators();
    var service = service(fixture, collaborators);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertThatThrownBy(
              () ->
                  withGameDesign(
                      () ->
                          service.freeze(
                              fixture.evidence(), fixture.plan(), fixture.accountBinding())))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("ambient transaction");
    } finally {
      TransactionSynchronizationManager.clear();
    }
    collaborators.verifyNoReadsOrWrites();
  }

  private static WorldSelectedDraftPublicationFreezeService service(
      Fixture fixture, Collaborators collaborators) {
    return new WorldSelectedDraftPublicationFreezeService(
        NAMESPACE,
        collaborators.selectionClient,
        collaborators.versionStateClient,
        collaborators.accountClient,
        collaborators.intakeRepository,
        collaborators.fence,
        collaborators.checkpointRepository,
        collaborators.authorizationRepository,
        collaborators.transactionManager);
  }

  private static Fixture fixture() {
    UUID versionIdentityOperation = uuid("44444444-4444-4444-8444-444444444444");
    UUID intakeRequest = uuid("55555555-5555-4555-8555-555555555555");
    UUID sourceOperation = uuid("77777777-7777-4777-8777-777777777777");
    UUID registrationRequest = uuid("88888888-8888-4888-8888-888888888888");
    String worldSlug = "violet-wilds";
    long sourceGameRow = 41L;
    String gameDesignTenantKey = "gd-tenant-41";
    String sourceRequestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE, registrationRequest, TENANT, "tenant-violet", worldSlug, "Violet Wilds");
    String sourceEvidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            NAMESPACE,
            registrationRequest,
            sourceOperation,
            sourceRequestDigest,
            TENANT,
            "tenant-violet",
            worldSlug,
            "Violet Wilds",
            sourceGameRow,
            gameDesignTenantKey,
            "NEW_GAME_ROW");
    AuthoredWorldSourceEvidence source =
        new AuthoredWorldSourceEvidence(
            1,
            NAMESPACE,
            registrationRequest,
            sourceOperation,
            sourceRequestDigest,
            TENANT,
            "tenant-violet",
            worldSlug,
            "Violet Wilds",
            sourceGameRow,
            gameDesignTenantKey,
            "NEW_GAME_ROW",
            sourceEvidenceDigest);
    String intakeRequestDigest =
        WorldAuthoredSourceIntakeDigest.requestDigest(NAMESPACE, intakeRequest, source);
    UUID intakeId = uuid("99999999-9999-4999-8999-999999999999");
    long localTenantKey = 502L;
    String intakeReceiptDigest =
        WorldAuthoredSourceIntakeDigest.receiptDigest(
            NAMESPACE, intakeId, intakeRequestDigest, source, localTenantKey);
    var intake =
        new WorldAuthoredSourceIntakeReceipt(
            1,
            NAMESPACE,
            intakeRequest,
            intakeId,
            TENANT,
            worldSlug,
            sourceOperation,
            sourceEvidenceDigest,
            intakeRequestDigest,
            intakeReceiptDigest,
            localTenantKey,
            source);
    OwnerBinding owner =
        new OwnerBinding(
            NAMESPACE,
            TENANT,
            VERSION,
            versionIdentityOperation,
            GAME_DESIGN_VERSION,
            intakeRequest,
            intakeId,
            intakeRequestDigest,
            sourceOperation,
            sourceEvidenceDigest,
            intakeReceiptDigest);
    TargetProof target =
        new TargetProof(
            TENANT,
            VERSION,
            GAME_DESIGN_VERSION,
            gameDesignTenantKey,
            sourceGameRow,
            gameDesignTenantKey,
            "NEW_GAME_ROW");
    UUID commitRequest = uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    UUID commitId = uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    UUID revisionId = uuid("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
    UUID aggregateId = uuid("dddddddd-dddd-4ddd-8ddd-dddddddddddd");
    var binding =
        DraftCommitBinding.create(
            target,
            commitRequest,
            commitId,
            "base-commit",
            List.of(new RevisionPayload("0", revisionId, Owner.WORLD_MANAGEMENT, "{}")),
            List.of(
                new AffectedUnit(
                    Owner.WORLD_MANAGEMENT,
                    "REGION",
                    aggregateId.toString(),
                    "AGGREGATE",
                    aggregateId.toString(),
                    "0")));
    WorldDraftTopologyCommitPlan plan = mock(WorldDraftTopologyCommitPlan.class);
    when(plan.binding()).thenReturn(binding);
    when(plan.ownerBinding()).thenReturn(owner);
    String publicationRequest = "publication-request-17";
    var selection =
        selection(binding, publicationRequest, VERSION_EPOCH, "stipulated immutable selection");
    var account = accountBinding(selection, uuid("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"));
    var evidence =
        new WorldDesignPublicationFenceEvidence(
            NAMESPACE,
            TENANT,
            VERSION,
            versionIdentityOperation,
            GAME_DESIGN_VERSION,
            intakeRequest,
            intakeId,
            intakeRequestDigest,
            sourceOperation,
            sourceEvidenceDigest,
            intakeReceiptDigest,
            publicationRequest,
            selection.digest().substring("sha256:".length()),
            VERSION_EPOCH,
            "publish:" + TENANT + ":publish-request:" + publicationRequest);
    Checkpoint checkpoint = new Checkpoint(commitId.toString(), "a".repeat(64), 3);
    return new Fixture(
        source, intake, owner, binding, plan, selection, account, evidence, checkpoint);
  }

  private static AccountPublicationAuthorizationBinding accountBinding(
      AuthoredDraftPublishSelectionBinding selection, UUID operationId) {
    return accountBinding(selection, operationId, uuid("ffffffff-ffff-4fff-8fff-ffffffffffff"));
  }

  private static AccountPublicationAuthorizationBinding accountBinding(
      AuthoredDraftPublishSelectionBinding selection, UUID operationId, UUID fenceId) {
    return new AccountPublicationAuthorizationBinding(
        operationId,
        fenceId,
        new AccountPublicationAuthorizationBinding.PreallocationInput(
            uuid("12121212-1212-4212-8212-121212121212"), selection),
        List.of(
            new DraftAuthorizationFenceBinding.SourceEvidence(
                DraftAuthorizationFenceBinding.SourceKind.ACCOUNT,
                "12121212-1212-4212-8212-121212121212",
                "1",
                "1",
                null,
                null,
                new byte[] {1})));
  }

  private static AuthoredDraftPublishSelectionBinding selection(
      DraftCommitBinding binding, String publicationRequest, long versionStateEpoch, String notes) {
    var target = binding.target();
    return AuthoredDraftPublishSelectionBinding.capture(
        new PublishIntent(
            target.canonicalTenantId(),
            target.canonicalVersionId(),
            publicationRequest,
            Long.toString(versionStateEpoch),
            notes,
            binding.requestId(),
            binding.commitId(),
            binding.digest()),
        target,
        binding,
        new VisibilityFence(
            target,
            binding.requestId(),
            binding.commitId(),
            binding.digest(),
            "[]",
            java.time.OffsetDateTime.parse("2026-10-01T00:00:00Z")));
  }

  private static AuthoredWorldSourceEvidence changedSource(AuthoredWorldSourceEvidence source) {
    UUID operationId = UUID.randomUUID();
    String worldSlug = "changed-" + UUID.randomUUID().toString().replace("-", "");
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE,
            source.registrationRequestId(),
            source.canonicalTenantId(),
            source.tenantSlug(),
            worldSlug,
            source.worldDisplayName());
    String evidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            NAMESPACE,
            source.registrationRequestId(),
            operationId,
            requestDigest,
            source.canonicalTenantId(),
            source.tenantSlug(),
            worldSlug,
            source.worldDisplayName(),
            source.sourceGameRowId(),
            source.sourceGameTenantKey(),
            source.provenanceKind());
    return new AuthoredWorldSourceEvidence(
        1,
        NAMESPACE,
        source.registrationRequestId(),
        operationId,
        requestDigest,
        source.canonicalTenantId(),
        source.tenantSlug(),
        worldSlug,
        source.worldDisplayName(),
        source.sourceGameRowId(),
        source.sourceGameTenantKey(),
        source.provenanceKind(),
        evidenceDigest);
  }

  private static FrozenAttempt frozen(
      WorldDesignPublicationFenceEvidence evidence, DraftCommitBinding binding) {
    return new FrozenAttempt(
        evidence,
        uuid("13131313-1313-4313-8313-131313131313"),
        new Checkpoint(binding.commitId().toString(), "a".repeat(64), 3));
  }

  private static AuthoredDraftPublishSelectionReadEvidence selectionRead(
      AuthoredDraftPublishSelectionReadEvidence.Request request) {
    var evidence = mock(AuthoredDraftPublishSelectionReadEvidence.class);
    when(evidence.request()).thenReturn(request);
    return evidence;
  }

  private static AccountPublicationAuthorizationReadEvidence accountRead(
      AccountPublicationAuthorizationReadEvidence.Request request) {
    var evidence = mock(AccountPublicationAuthorizationReadEvidence.class);
    when(evidence.request()).thenReturn(request);
    return evidence;
  }

  private static GrpcPeerIdentity peer(String namespace, String service) {
    return new GrpcPeerIdentity(
        "spiffe://firemud/ns/" + namespace + "/sa/" + service, namespace, service);
  }

  private static <T> T withGameDesign(Supplier<T> action) {
    return withPeer(peer(NAMESPACE, "game-design-service"), action);
  }

  private static <T> T withPeer(GrpcPeerIdentity peer, Supplier<T> action) {
    Context context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context previous = context.attach();
    try {
      return action.get();
    } finally {
      context.detach(previous);
    }
  }

  private static <T> T withoutPeer(Supplier<T> action) {
    Context previous = Context.ROOT.attach();
    try {
      return action.get();
    } finally {
      Context.ROOT.detach(previous);
    }
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private record Fixture(
      AuthoredWorldSourceEvidence source,
      WorldAuthoredSourceIntakeReceipt intake,
      OwnerBinding owner,
      DraftCommitBinding binding,
      WorldDraftTopologyCommitPlan plan,
      AuthoredDraftPublishSelectionBinding selection,
      AccountPublicationAuthorizationBinding accountBinding,
      WorldDesignPublicationFenceEvidence evidence,
      Checkpoint checkpoint) {}

  private static final class Collaborators {
    final AuthoredDraftPublishSelectionReadClient selectionClient =
        mock(AuthoredDraftPublishSelectionReadClient.class);
    final AuthoredWorldVersionStateClient versionStateClient =
        mock(AuthoredWorldVersionStateClient.class);
    final AccountPublicationAuthorizationReadClient accountClient =
        mock(AccountPublicationAuthorizationReadClient.class);
    final WorldAuthoredSourceIntakeRepository intakeRepository =
        mock(WorldAuthoredSourceIntakeRepository.class);
    final WorldDesignPublicationFenceRepository fence =
        mock(WorldDesignPublicationFenceRepository.class);
    final WorldSelectedDraftPublicationCheckpointRepository checkpointRepository =
        mock(WorldSelectedDraftPublicationCheckpointRepository.class);
    final WorldSelectedDraftPublicationAuthorizationRepository authorizationRepository =
        mock(WorldSelectedDraftPublicationAuthorizationRepository.class);
    final RecordingTransactionManager transactionManager = new RecordingTransactionManager();

    void verifyNoReadsOrWrites() {
      verifyNoInteractions(
          selectionClient,
          versionStateClient,
          accountClient,
          intakeRepository,
          fence,
          checkpointRepository,
          authorizationRepository);
    }
  }

  private static final class RecordingTransactionManager
      implements org.springframework.transaction.PlatformTransactionManager {
    TransactionDefinition startedWith;
    int commits;
    int rollbacks;

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      startedWith = definition;
      TransactionSynchronizationManager.setActualTransactionActive(true);
      TransactionSynchronizationManager.setCurrentTransactionReadOnly(definition.isReadOnly());
      TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
          definition.getIsolationLevel());
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {
      commits++;
      TransactionSynchronizationManager.clear();
    }

    @Override
    public void rollback(TransactionStatus status) {
      rollbacks++;
      TransactionSynchronizationManager.clear();
    }
  }
}
