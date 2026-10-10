package net.firedevops.firemud.gamedesign.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.gamedesign.RealmPolicySource;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Structural owner-read proof; mocks do not establish physical PostgreSQL source provenance. */
class SelectedDraftPublicationDigestReadServiceTest {
  private static final String NAMESPACE = "test";

  private final SelectedDraftPublicationDigestReadService.OwnerRead owner =
      mock(SelectedDraftPublicationDigestReadService.OwnerRead.class);
  private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
  private final TransactionStatus transactionStatus = mock(TransactionStatus.class);

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void readsExactSelectionFrozenAndSynchronizedSourcesInOneReadOnlyRepeatableReadSnapshot()
      throws Exception {
    var fixture = fixture();
    var request = request(fixture.operation());
    when(transactions.getTransaction(any())).thenReturn(transactionStatus);
    when(owner.read(request.derivedWorkflowIdentity()))
        .thenReturn(
            Optional.of(
                new GameDesignPublicationOperationRepository.Readback(
                    fixture.operation(), "PENDING", new byte[] {1})));
    when(owner.readSourceCapture(fixture.operation())).thenReturn(Optional.of(fixture.capture()));
    when(owner.readSynchronized(fixture.binding().target(), fixture.binding().commitId()))
        .thenReturn(Optional.of(fixture.sources()));

    var result =
        new SelectedDraftPublicationDigestReadService(owner, transactions, NAMESPACE)
            .read(NAMESPACE, request);

    assertThat(result.requestBinding()).isSameAs(request);
    assertThat(result.requestDigest()).isEqualTo(request.requestDigest());
    assertThat(result.gameDesignDigest().tenantId()).isEqualTo(request.tenantId());
    assertThat(result.gameDesignDigest().scopeValue()).isEqualTo(request.versionId());
    assertThat(result.gameDesignDigest().appliedCommitId())
        .isEqualTo(fixture.binding().commitId().toString());
    assertThat(result.gameDesignDigest().digestSchemaVersion()).isEqualTo(2);
    assertThat(result.worldManagementDigest().participantKey()).isEqualTo("WORLD_MANAGEMENT");
    assertThat(result.worldManagementDigest().contentDigest())
        .isEqualTo(fixture.operation().world().request().contentDigest());
    assertThat(result.worldManagementDigest().digestSchemaVersion())
        .isEqualTo(fixture.operation().world().request().digestSchemaVersion());

    var definition = ArgumentCaptor.forClass(TransactionDefinition.class);
    var order = inOrder(transactions, owner);
    order.verify(transactions).getTransaction(definition.capture());
    order.verify(owner).read(request.derivedWorkflowIdentity());
    order.verify(owner).requireExactSelection(fixture.operation());
    order.verify(owner).readSourceCapture(fixture.operation());
    order.verify(owner).readSynchronized(fixture.binding().target(), fixture.binding().commitId());
    order.verify(transactions).commit(transactionStatus);
    assertThat(definition.getValue().getPropagationBehavior())
        .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    assertThat(definition.getValue().getIsolationLevel())
        .isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    assertThat(definition.getValue().isReadOnly()).isTrue();
  }

  @Test
  void namespaceMalformedScopeAndAmbientSqlAreRejectedBeforeOwnerReads() throws Exception {
    var fixture = fixture();
    var service = new SelectedDraftPublicationDigestReadService(owner, transactions, NAMESPACE);

    assertCode(
        Status.Code.PERMISSION_DENIED, () -> service.read("other", request(fixture.operation())));
    assertCode(Status.Code.INVALID_ARGUMENT, () -> service.read(NAMESPACE, null));
    assertCode(
        Status.Code.INVALID_ARGUMENT,
        () ->
            service.read(
                NAMESPACE,
                PublicationDigestRequestBinding.patch(
                    UUID.randomUUID().toString(), "19", "patch", "publish-id")));
    assertCode(
        Status.Code.INVALID_ARGUMENT,
        () ->
            service.read(
                NAMESPACE,
                PublicationDigestRequestBinding.full("not-a-canonical-uuid", "19", "publish-id")));

    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertCode(
        Status.Code.FAILED_PRECONDITION,
        () -> service.read(NAMESPACE, request(fixture.operation())));
    TransactionSynchronizationManager.setActualTransactionActive(false);
    TransactionSynchronizationManager.initSynchronization();
    assertCode(
        Status.Code.FAILED_PRECONDITION,
        () -> service.read(NAMESPACE, request(fixture.operation())));

    verifyNoInteractions(owner, transactions);
  }

  @Test
  void wrongActualVersionOrPublicationRequestCannotReuseAnotherOriginalOperation()
      throws Exception {
    var fixture = fixture();
    var validRequest = request(fixture.operation());
    when(transactions.getTransaction(any())).thenReturn(transactionStatus);
    when(owner.read(any()))
        .thenReturn(
            Optional.of(
                new GameDesignPublicationOperationRepository.Readback(
                    fixture.operation(), "PENDING", new byte[] {1})));
    var service = new SelectedDraftPublicationDigestReadService(owner, transactions, NAMESPACE);

    var wrongVersion =
        PublicationDigestRequestBinding.full(
            validRequest.tenantId(), "20", validRequest.publishRequestId());
    assertCode(Status.Code.FAILED_PRECONDITION, () -> service.read(NAMESPACE, wrongVersion));
    var wrongTenant =
        PublicationDigestRequestBinding.full(
            "33333333-3333-4333-8333-333333333333",
            validRequest.versionId(),
            validRequest.publishRequestId());
    assertCode(Status.Code.FAILED_PRECONDITION, () -> service.read(NAMESPACE, wrongTenant));
    var wrongRequest =
        PublicationDigestRequestBinding.full(
            validRequest.tenantId(), validRequest.versionId(), "different-request");
    assertCode(Status.Code.FAILED_PRECONDITION, () -> service.read(NAMESPACE, wrongRequest));

    verify(owner, never()).requireExactSelection(any());
    verify(owner, never()).readSourceCapture(any());
    verify(owner, never()).readSynchronized(any(), any());
  }

  @Test
  void missingOperationOrRetainedSelectionFailsBeforeSourceReads() throws Exception {
    var fixture = fixture();
    var request = request(fixture.operation());
    when(transactions.getTransaction(any())).thenReturn(transactionStatus);
    when(owner.read(request.derivedWorkflowIdentity())).thenReturn(Optional.empty());
    var service = new SelectedDraftPublicationDigestReadService(owner, transactions, NAMESPACE);
    assertCode(Status.Code.NOT_FOUND, () -> service.read(NAMESPACE, request));
    verify(owner, never()).requireExactSelection(any());
    verify(owner, never()).readSourceCapture(any());

    org.mockito.Mockito.reset(owner);
    when(owner.read(request.derivedWorkflowIdentity()))
        .thenReturn(
            Optional.of(
                new GameDesignPublicationOperationRepository.Readback(
                    fixture.operation(), "PENDING", new byte[] {1})));
    org.mockito.Mockito.doThrow(new IllegalStateException("selection readback unavailable"))
        .when(owner)
        .requireExactSelection(fixture.operation());
    assertCode(Status.Code.FAILED_PRECONDITION, () -> service.read(NAMESPACE, request));
    verify(owner, never()).readSourceCapture(any());
  }

  @Test
  void missingSourceFamiliesAndFrozenSnapshotMismatchCannotProveAnEmptyOrDifferentSource()
      throws Exception {
    var fixture = fixture();
    var request = request(fixture.operation());
    when(transactions.getTransaction(any())).thenReturn(transactionStatus);
    when(owner.read(request.derivedWorkflowIdentity()))
        .thenReturn(
            Optional.of(
                new GameDesignPublicationOperationRepository.Readback(
                    fixture.operation(), "PENDING", new byte[] {1})));
    when(owner.readSourceCapture(fixture.operation())).thenReturn(Optional.of(fixture.capture()));
    var missingBranding =
        new GameDesignSourceRepository.SynchronizedSources(
            fixture.sources().command(),
            fixture.sources().policy(),
            fixture.sources().asset(),
            fixture.sources().gameplay(),
            Optional.empty(),
            fixture.sources().templateConfig());
    when(owner.readSynchronized(fixture.binding().target(), fixture.binding().commitId()))
        .thenReturn(Optional.of(missingBranding));
    var service = new SelectedDraftPublicationDigestReadService(owner, transactions, NAMESPACE);
    assertCode(Status.Code.FAILED_PRECONDITION, () -> service.read(NAMESPACE, request));

    org.mockito.Mockito.reset(owner);
    when(owner.read(request.derivedWorkflowIdentity()))
        .thenReturn(
            Optional.of(
                new GameDesignPublicationOperationRepository.Readback(
                    fixture.operation(), "PENDING", new byte[] {1})));
    when(owner.readSourceCapture(fixture.operation())).thenReturn(Optional.of(fixture.capture()));
    var changedAsset =
        new AssetSnapshot(
            fixture.binding(),
            "1",
            null,
            UUID.fromString("77777777-7777-4777-8777-777777777777"),
            List.of());
    var changedSources =
        new GameDesignSourceRepository.SynchronizedSources(
            fixture.sources().command(),
            fixture.sources().policy(),
            changedAsset,
            fixture.sources().gameplay(),
            fixture.sources().branding(),
            fixture.sources().templateConfig());
    when(owner.readSynchronized(fixture.binding().target(), fixture.binding().commitId()))
        .thenReturn(Optional.of(changedSources));
    assertCode(Status.Code.FAILED_PRECONDITION, () -> service.read(NAMESPACE, request));

    org.mockito.Mockito.reset(owner);
    when(owner.read(request.derivedWorkflowIdentity()))
        .thenReturn(
            Optional.of(
                new GameDesignPublicationOperationRepository.Readback(
                    fixture.operation(), "PENDING", new byte[] {1})));
    when(owner.readSourceCapture(fixture.operation())).thenReturn(Optional.of(fixture.capture()));
    var anotherCommit =
        DraftCommitBinding.create(
            fixture.binding().target(),
            fixture.binding().requestId(),
            UUID.fromString("99999999-9999-4999-8999-999999999999"),
            fixture.binding().baseCommitId(),
            fixture.binding().revisions(),
            fixture.binding().affectedUnits());
    when(owner.readSynchronized(fixture.binding().target(), fixture.binding().commitId()))
        .thenReturn(Optional.of(sources(anotherCommit)));
    assertCode(Status.Code.FAILED_PRECONDITION, () -> service.read(NAMESPACE, request));
  }

  private static PublicationDigestRequestBinding request(GameDesignPublicationOperation operation) {
    var selection = operation.account().input().selection();
    return PublicationDigestRequestBinding.full(
        selection.intent().canonicalTenantId().toString(),
        Long.toString(selection.target().gameDesignVersionRowId()),
        selection.intent().publishRequestId());
  }

  private static Fixture fixture() throws Exception {
    var operation =
        IsolatedPublicationOperationFixtures.fresh(
            new TargetProof(
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
                19L,
                "tenant-key",
                42L,
                "tenant-key",
                "NEW_GAME_ROW"));
    var binding = operation.account().input().selection().selectedCommit();
    var sources = sources(binding);
    var capture =
        new GameDesignSourceRepository.Capture(
            new CommandSnapshot.Capture(operation, sources.command()),
            new RealmPolicySnapshot.Capture(operation, sources.policy()),
            new AssetSnapshot.Capture(operation, sources.asset()),
            new BrandingSourceSnapshot.Capture(operation, sources.branding().orElseThrow()),
            new TemplateConfigSourceSnapshot.Capture(
                operation, sources.templateConfig().orElseThrow()));
    return new Fixture(operation, binding, sources, capture);
  }

  private static GameDesignSourceRepository.SynchronizedSources sources(
      DraftCommitBinding binding) {
    var command = new CommandSnapshot(binding, "0", null, "sha256:" + "a".repeat(64), List.of());
    var policies =
        binding.revisions().stream()
            .filter(
                revision -> revision.owner() == DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE)
            .filter(RealmPolicySource::isPolicyRevision)
            .map(revision -> RealmPolicySource.revision(binding, revision))
            .toList();
    var policy = new RealmPolicySnapshot(binding, "1", policies);
    var asset =
        new AssetSnapshot(
            binding, "0", null, UUID.fromString("33333333-3333-4333-8333-333333333333"), List.of());
    var gameplay =
        new GameplayRuleSnapshot(
            binding, "0", null, UUID.fromString("44444444-4444-4444-8444-444444444444"), List.of());
    var branding =
        new BrandingSourceSnapshot(
            binding, "0", null, UUID.fromString("55555555-5555-4555-8555-555555555555"), List.of());
    var template =
        new TemplateConfigSourceSnapshot(
            binding, "0", null, UUID.fromString("66666666-6666-4666-8666-666666666666"), List.of());
    return new GameDesignSourceRepository.SynchronizedSources(
        command, policy, asset, gameplay, Optional.of(branding), Optional.of(template));
  }

  private static void assertCode(Status.Code expected, Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            failure -> assertThat(Status.fromThrowable(failure).getCode()).isEqualTo(expected));
  }

  private record Fixture(
      GameDesignPublicationOperation operation,
      DraftCommitBinding binding,
      GameDesignSourceRepository.SynchronizedSources sources,
      GameDesignSourceRepository.Capture capture) {}
}
