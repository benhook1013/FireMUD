package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationReadClient;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding.PublishIntent;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding.VisibilityFence;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionReadClient;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeEvidence;
import net.firedevops.firemud.common.world.RoomTemplateRef;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceEvidence.Checkpoint;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceEvidence.FrozenAttempt;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceEvidence.OwnerBinding;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class WorldSelectedDraftPublicationFreezeRequestTest {
  private static final String NAMESPACE = "firemud";
  private static final UUID TENANT = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID VERSION = uuid("22222222-2222-4222-8222-222222222222");
  private static final long GD_VERSION = 17L;
  private static final long EPOCH = 9L;

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void callerDenialPrecedesOwnerResolutionAndEveryOtherRead() {
    Fixture fixture = fixture();
    Collaborators collaborators = new Collaborators();
    var service = service(fixture, collaborators);

    assertThatThrownBy(() -> withoutPeer(() -> service.begin(fixture.request())))
        .isInstanceOf(SecurityException.class);

    collaborators.verifyNoReadsOrWrites();
    assertThat(collaborators.transactionManager.commits).isZero();
  }

  @Test
  void ownerResolvesAppliedBeforeExactRetryAndRetrySkipsMutableRemoteReads() {
    Fixture fixture = fixture();
    Collaborators collaborators = new Collaborators();
    FrozenAttempt prior = frozen(fixture.evidence(), fixture.binding());
    when(collaborators.applications.readSelectedPublicationApplication(
            NAMESPACE, TENANT, VERSION, fixture.selection()))
        .thenReturn(Optional.of(fixture.application()));
    when(collaborators.fence.readAttempt(fixture.evidence())).thenReturn(Optional.of(prior));
    when(collaborators.authorizationRepository.readCommitted(prior))
        .thenReturn(Optional.of(fixture.accountBinding()));
    var service = service(fixture, collaborators);

    var acknowledgement = withGameDesign(() -> service.begin(fixture.request()));

    assertThat(acknowledgement.request()).isEqualTo(fixture.request());
    assertThat(acknowledgement.intakeRequestId()).isEqualTo(fixture.owner().intakeRequestId());
    assertThat(acknowledgement.versionStateEpoch()).isEqualTo(EPOCH);
    assertThat(acknowledgement.publicationFence()).isEqualTo(prior.publicationFence());
    assertThat(acknowledgement.appliedCommitId())
        .isEqualTo(fixture.binding().commitId().toString());
    assertThat(acknowledgement.contentDigest()).isEqualTo("a".repeat(64));
    assertThat(acknowledgement.digestSchemaVersion()).isEqualTo(3);
    assertThat(acknowledgement.ownerFreezePhase())
        .isEqualTo(WorldSelectedDraftPublicationFreezeEvidence.OwnerFreezePhase.FROZEN);
    var order =
        inOrder(
            collaborators.applications,
            collaborators.fence,
            collaborators.authorizationRepository,
            collaborators.artifactInventoryRepository);
    order
        .verify(collaborators.applications)
        .readSelectedPublicationApplication(NAMESPACE, TENANT, VERSION, fixture.selection());
    order.verify(collaborators.fence).readAttempt(fixture.evidence());
    order.verify(collaborators.authorizationRepository).readCommitted(prior);
    order
        .verify(collaborators.artifactInventoryRepository)
        .readCommitted(prior, fixture.accountBinding());
    verifyNoInteractions(
        collaborators.selectionClient,
        collaborators.versionStateClient,
        collaborators.accountClient);
    verify(collaborators.fence, never()).claimFreeze(any(), any());
    verify(collaborators.checkpointRepository, never()).captureWithSource(any(), any());
    assertThat(collaborators.transactionManager.commits).isZero();
  }

  @Test
  void missingAppliedSelectionIsDeniedBeforeFreezeHistoryOrRemoteReads() {
    Fixture fixture = fixture();
    Collaborators collaborators = new Collaborators();
    when(collaborators.applications.readSelectedPublicationApplication(
            NAMESPACE, TENANT, VERSION, fixture.selection()))
        .thenReturn(Optional.empty());
    var service = service(fixture, collaborators);

    assertThatThrownBy(() -> withGameDesign(() -> service.begin(fixture.request())))
        .isInstanceOf(WorldDesignPublicationFenceRepository.ConflictException.class)
        .hasMessageContaining("no exact retained APPLIED");

    verify(collaborators.fence, never()).readAttempt(any());
    verifyNoInteractions(
        collaborators.selectionClient,
        collaborators.versionStateClient,
        collaborators.accountClient,
        collaborators.authorizationRepository,
        collaborators.checkpointRepository);
    assertThat(collaborators.transactionManager.commits).isZero();
  }

  @Test
  void exactSelectionRetryCannotAttachChangedAccountOrder() {
    Fixture fixture = fixture();
    Collaborators collaborators = new Collaborators();
    var changedAccount =
        accountBinding(
            fixture.selection(),
            uuid("abababab-abab-4bab-8bab-abababababab"),
            fixture.accountBinding().fenceId());
    var changedRequest =
        WorldSelectedDraftPublicationFreezeEvidence.Request.create(
            NAMESPACE,
            TENANT,
            VERSION,
            fixture.selection().intent().publishRequestId(),
            EPOCH,
            fixture.selection().digest().substring("sha256:".length()),
            changedAccount);
    FrozenAttempt prior = frozen(fixture.evidence(), fixture.binding());
    when(collaborators.applications.readSelectedPublicationApplication(
            NAMESPACE, TENANT, VERSION, fixture.selection()))
        .thenReturn(Optional.of(fixture.application()));
    when(collaborators.fence.readAttempt(fixture.evidence())).thenReturn(Optional.of(prior));
    when(collaborators.authorizationRepository.readCommitted(prior))
        .thenReturn(Optional.of(fixture.accountBinding()));
    var service = service(fixture, collaborators);

    assertThatThrownBy(() -> withGameDesign(() -> service.begin(changedRequest)))
        .isInstanceOf(WorldDesignPublicationFenceRepository.ConflictException.class)
        .hasMessageContaining("Account");

    verifyNoInteractions(
        collaborators.selectionClient,
        collaborators.versionStateClient,
        collaborators.accountClient);
    verify(collaborators.fence, never()).claimFreeze(any(), any());
    assertThat(collaborators.transactionManager.commits).isZero();
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
        collaborators.artifactInventoryRepository,
        collaborators.applications,
        collaborators.transactionManager);
  }

  static Fixture fixture() {
    var target =
        new TargetProof(TENANT, VERSION, GD_VERSION, "gd-tenant", 41L, "gd-tenant", "NEW_GAME_ROW");
    UUID commitRequest = uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    UUID commitId = uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    var binding =
        DraftCommitBinding.create(
            target,
            commitRequest,
            commitId,
            "base-commit",
            List.of(
                new RevisionPayload(
                    "0",
                    uuid("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
                    Owner.WORLD_MANAGEMENT,
                    "{}")),
            List.of(
                new AffectedUnit(
                    Owner.WORLD_MANAGEMENT, "REGION", "region-1", "AGGREGATE", "region-1", "0")));
    var selection =
        AuthoredDraftPublishSelectionBinding.capture(
            new PublishIntent(
                TENANT,
                VERSION,
                "publication-request",
                Long.toString(EPOCH),
                "immutable selection",
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
    var account = accountBinding(selection, uuid("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"));
    var request =
        WorldSelectedDraftPublicationFreezeEvidence.Request.create(
            NAMESPACE,
            TENANT,
            VERSION,
            selection.intent().publishRequestId(),
            EPOCH,
            selection.digest().substring("sha256:".length()),
            account);
    var owner = owner();
    var evidence =
        new WorldDesignPublicationFenceEvidence(
            NAMESPACE,
            TENANT,
            VERSION,
            owner.versionIdentityOperationId(),
            GD_VERSION,
            owner.intakeRequestId(),
            owner.intakeOperationId(),
            owner.intakeRequestDigest(),
            owner.sourceOperationId(),
            owner.sourceEvidenceDigest(),
            owner.intakeReceiptDigest(),
            request.publicationRequestId(),
            request.requestDigest(),
            EPOCH,
            "publish:" + TENANT + ":publish-request:" + request.publicationRequestId());
    var plan = mock(WorldDraftTopologyCommitPlan.class);
    when(plan.binding()).thenReturn(binding);
    when(plan.ownerBinding()).thenReturn(owner);
    when(plan.graph())
        .thenReturn(
            new WorldDraftTopologyInputGraph(
                TENANT,
                VERSION,
                List.of(),
                new WorldDraftTopologyInputGraph.FreshGraphDeclaration(
                    TENANT,
                    VERSION,
                    new RoomTemplateRef(
                        TENANT, VERSION, uuid("dddddddd-dddd-4ddd-8ddd-dddddddddddd")),
                    List.of())));
    var operation = mock(WorldDraftTerminalOperation.class);
    when(operation.binding()).thenReturn(binding);
    when(operation.ownerBinding()).thenReturn(owner);
    when(operation.canonicalTenantId()).thenReturn(TENANT);
    when(operation.canonicalVersionId()).thenReturn(VERSION);
    var application = new WorldDraftGraphApplication(operation, plan);
    return new Fixture(selection, binding, account, request, owner, evidence, application);
  }

  private static OwnerBinding owner() {
    return new OwnerBinding(
        NAMESPACE,
        TENANT,
        VERSION,
        uuid("12121212-1212-4212-8212-121212121212"),
        GD_VERSION,
        uuid("13131313-1313-4313-8313-131313131313"),
        uuid("14141414-1414-4414-8414-141414141414"),
        "sha256:" + "1".repeat(64),
        uuid("15151515-1515-4515-8515-151515151515"),
        "sha256:" + "2".repeat(64),
        "sha256:" + "3".repeat(64));
  }

  private static AccountPublicationAuthorizationBinding accountBinding(
      AuthoredDraftPublishSelectionBinding selection, UUID operationId) {
    return accountBinding(selection, operationId, uuid("ffffffff-ffff-4fff-8fff-ffffffffffff"));
  }

  private static AccountPublicationAuthorizationBinding accountBinding(
      AuthoredDraftPublishSelectionBinding selection, UUID operationId, UUID fenceId) {
    UUID actor = uuid("16161616-1616-4616-8616-161616161616");
    return new AccountPublicationAuthorizationBinding(
        operationId,
        fenceId,
        new AccountPublicationAuthorizationBinding.PreallocationInput(actor, selection),
        List.of(
            new DraftAuthorizationFenceBinding.SourceEvidence(
                DraftAuthorizationFenceBinding.SourceKind.ACCOUNT,
                actor.toString(),
                "1",
                "1",
                null,
                null,
                new byte[] {1})));
  }

  private static FrozenAttempt frozen(
      WorldDesignPublicationFenceEvidence evidence, DraftCommitBinding binding) {
    return new FrozenAttempt(
        evidence,
        uuid("17171717-1717-4717-8717-171717171717"),
        new Checkpoint(binding.commitId().toString(), "a".repeat(64), 3));
  }

  private static <T> T withGameDesign(Supplier<T> action) {
    return withPeer(
        GrpcPeerIdentity.parseUri("spiffe://firemud/ns/" + NAMESPACE + "/sa/game-design-service")
            .orElseThrow(),
        action);
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

  record Fixture(
      AuthoredDraftPublishSelectionBinding selection,
      DraftCommitBinding binding,
      AccountPublicationAuthorizationBinding accountBinding,
      WorldSelectedDraftPublicationFreezeEvidence.Request request,
      OwnerBinding owner,
      WorldDesignPublicationFenceEvidence evidence,
      WorldDraftGraphApplication application) {}

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
    final WorldSelectedPublicationArtifactInventoryRepository artifactInventoryRepository =
        mock(WorldSelectedPublicationArtifactInventoryRepository.class);
    final WorldDraftGraphApplicationRepository applications =
        mock(WorldDraftGraphApplicationRepository.class);
    final RecordingTransactionManager transactionManager = new RecordingTransactionManager();

    void verifyNoReadsOrWrites() {
      verifyNoInteractions(
          selectionClient,
          versionStateClient,
          accountClient,
          intakeRepository,
          fence,
          checkpointRepository,
          authorizationRepository,
          artifactInventoryRepository,
          applications);
    }
  }

  private static final class RecordingTransactionManager
      implements org.springframework.transaction.PlatformTransactionManager {
    int commits;

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
      throw new AssertionError("A committed retry must not open the World owner transaction");
    }

    @Override
    public void commit(TransactionStatus status) {
      commits++;
    }

    @Override
    public void rollback(TransactionStatus status) {
      throw new AssertionError("A committed retry must not roll back a World owner transaction");
    }
  }
}
