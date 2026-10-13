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
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.gamedesign.AssetSource;
import net.firedevops.firemud.common.gamedesign.BrandingSource;
import net.firedevops.firemud.common.gamedesign.CommandSource;
import net.firedevops.firemud.common.gamedesign.RealmPolicySource;
import net.firedevops.firemud.common.gamelogic.AccountGameLogicIntakeSettlementEvidence;
import net.firedevops.firemud.common.gamelogic.GameLogicGameplayRuleIntakeOperation;
import net.firedevops.firemud.common.gamelogic.GameLogicGameplayRuleIntakeTerminal;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeAuthorizationBinding;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSelectedSource;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** Structural owner-read proof; mocks do not establish physical PostgreSQL source provenance. */
class SelectedDraftAssetInventoryReadServiceTest {
  private final SelectedDraftAssetInventoryReadService.OwnerRead owner =
      mock(SelectedDraftAssetInventoryReadService.OwnerRead.class);
  private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
  private final TransactionStatus transactionStatus = mock(TransactionStatus.class);

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void readsExactCapturedFamiliesAndRetainedGameLogicInOneReadOnlySnapshot() throws Exception {
    var fixture = fixture();
    when(transactions.getTransaction(any())).thenReturn(transactionStatus);
    when(owner.read(fixture.request().derivedWorkflowIdentity()))
        .thenReturn(
            Optional.of(
                new GameDesignPublicationOperationRepository.Readback(
                    fixture.operation(), "PENDING", new byte[] {1})));
    when(owner.readGenesis(fixture.binding().target())).thenReturn(Optional.of(fixture.genesis()));
    when(owner.readSourceCapture(fixture.operation())).thenReturn(Optional.of(fixture.capture()));
    when(owner.readSynchronized(fixture.binding().target(), fixture.binding().commitId()))
        .thenReturn(Optional.of(fixture.sources()));
    when(owner.readGameLogicReceipt(fixture.request()))
        .thenReturn(Optional.of(fixture.gameLogicReceipt()));

    var inventory =
        new SelectedDraftAssetInventoryReadService(owner, transactions).read(fixture.request());

    assertThat(inventory.request()).isSameAs(fixture.request());
    assertThat(inventory.operation()).isEqualTo(fixture.operation());
    assertThat(inventory.selectedCommit()).isEqualTo(fixture.binding());
    assertThat(inventory.gameLogicReceipt()).isEqualTo(fixture.gameLogicReceipt());
    assertThat(inventory.assets()).isEmpty();
    assertThat(inventory.sourceFamilyDeclarations())
        .containsEntry("ORDINARY", "EMPTY")
        .containsEntry("BRANDING:RESOURCE", "EMPTY")
        .containsEntry("BRANDING:LOGO", "EMPTY")
        .containsEntry("BRANDING:FAVICON", "EMPTY")
        .containsEntry("BRANDING:THEME", "EMPTY")
        .containsEntry("GAMEPLAY_RULE", "EMPTY")
        .containsEntry("TEMPLATE_CONFIG", "EMPTY");
    assertThat(inventory.canonicalJson()).contains(SelectedDraftAssetInventory.SCHEMA);
    byte[] canonical = inventory.canonicalBytes();
    canonical[0] ^= 1;
    assertThat(inventory.canonicalBytes()).isNotEqualTo(canonical);

    var definition = ArgumentCaptor.forClass(TransactionDefinition.class);
    var order = inOrder(transactions, owner);
    order.verify(transactions).getTransaction(definition.capture());
    order.verify(owner).read(fixture.request().derivedWorkflowIdentity());
    order.verify(owner).requireExactSelection(fixture.operation());
    order.verify(owner).readGenesis(fixture.binding().target());
    order.verify(owner).readSourceCapture(fixture.operation());
    order.verify(owner).readSynchronized(fixture.binding().target(), fixture.binding().commitId());
    order.verify(owner).readGameLogicReceipt(fixture.request());
    order.verify(transactions).commit(transactionStatus);
    assertThat(definition.getValue().getPropagationBehavior())
        .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    assertThat(definition.getValue().getIsolationLevel())
        .isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    assertThat(definition.getValue().isReadOnly()).isTrue();
    verify(owner, never()).readAssetBytes(any(), any());
  }

  @Test
  void missingReceiptOrSelectedSourceFamilyCannotBeReclassifiedAsEmpty() throws Exception {
    var fixture = fixture();
    when(transactions.getTransaction(any())).thenReturn(transactionStatus);
    when(owner.read(fixture.request().derivedWorkflowIdentity()))
        .thenReturn(
            Optional.of(
                new GameDesignPublicationOperationRepository.Readback(
                    fixture.operation(), "PENDING", new byte[] {1})));
    when(owner.readGenesis(fixture.binding().target())).thenReturn(Optional.of(fixture.genesis()));
    when(owner.readSourceCapture(fixture.operation())).thenReturn(Optional.of(fixture.capture()));
    when(owner.readSynchronized(fixture.binding().target(), fixture.binding().commitId()))
        .thenReturn(Optional.of(fixture.sources()));
    when(owner.readGameLogicReceipt(fixture.request())).thenReturn(Optional.empty());
    var service = new SelectedDraftAssetInventoryReadService(owner, transactions);
    assertCode(Status.Code.FAILED_PRECONDITION, () -> service.read(fixture.request()));
    verify(owner, never()).readAssetBytes(any(), any());

    org.mockito.Mockito.reset(owner);
    when(owner.read(fixture.request().derivedWorkflowIdentity()))
        .thenReturn(
            Optional.of(
                new GameDesignPublicationOperationRepository.Readback(
                    fixture.operation(), "PENDING", new byte[] {1})));
    when(owner.readGenesis(fixture.binding().target())).thenReturn(Optional.empty());
    assertCode(Status.Code.FAILED_PRECONDITION, () -> service.read(fixture.request()));
    verify(owner, never()).readSourceCapture(any());

    org.mockito.Mockito.reset(owner);
    when(owner.read(fixture.request().derivedWorkflowIdentity()))
        .thenReturn(
            Optional.of(
                new GameDesignPublicationOperationRepository.Readback(
                    fixture.operation(), "PENDING", new byte[] {1})));
    when(owner.readGenesis(fixture.binding().target())).thenReturn(Optional.of(fixture.genesis()));
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
    assertCode(Status.Code.FAILED_PRECONDITION, () -> service.read(fixture.request()));
    verify(owner, never()).readGameLogicReceipt(any());
  }

  @Test
  void rejectsAmbientTransactionBeforeAnyOwnerRead() throws Exception {
    var fixture = fixture();
    var service = new SelectedDraftAssetInventoryReadService(owner, transactions);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertCode(Status.Code.FAILED_PRECONDITION, () -> service.read(fixture.request()));
    verifyNoInteractions(owner, transactions);
  }

  @Test
  void inventoryAssetBytesAreDefensivelyCopiedAndOptionalIsUnsupported() throws Exception {
    var fixture = fixture();
    byte[] bytes = "exact owner bytes".getBytes(StandardCharsets.UTF_8);
    var asset =
        new SelectedDraftAssetInventory.Asset(
            SelectedDraftAssetInventory.Family.ORDINARY,
            "RESOURCE",
            "world/map.bin",
            "42",
            "REQUIRED",
            fixture.binding(),
            "0",
            UUID.fromString("77777777-7777-4777-8777-777777777777"),
            "application/octet-stream",
            CommandSource.sha256(bytes),
            bytes);
    bytes[0] ^= 1;
    byte[] exposed = asset.contentBytes();
    exposed[0] ^= 1;
    assertThat(asset.contentBytes())
        .isEqualTo("exact owner bytes".getBytes(StandardCharsets.UTF_8));
    assertThatThrownBy(
            () ->
                new SelectedDraftAssetInventory.Asset(
                    SelectedDraftAssetInventory.Family.ORDINARY,
                    "RESOURCE",
                    "optional.bin",
                    "43",
                    "OPTIONAL",
                    fixture.binding(),
                    "0",
                    UUID.fromString("88888888-8888-4888-8888-888888888888"),
                    "application/octet-stream",
                    CommandSource.sha256(new byte[] {1}),
                    new byte[] {1}))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void resolvesPresentOrdinaryAndBrandingReferencesToExactTenantQualifiedBytes() throws Exception {
    var empty = fixture();
    byte[] ordinaryBytes = "ordinary map".getBytes(StandardCharsets.UTF_8);
    byte[] brandingBytes = "branding logo".getBytes(StandardCharsets.UTF_8);
    var fixture =
        withAssets(empty, "world/map.bin", ordinaryBytes, "branding/logo.png", brandingBytes);
    stubOperationAndSources(fixture);
    when(owner.readGameLogicReceipt(fixture.request()))
        .thenReturn(Optional.of(fixture.gameLogicReceipt()));
    when(owner.readAssetBytes("tenant-key", "42"))
        .thenReturn(
            Optional.of(
                new SelectedDraftAssetInventoryReadService.AssetBytes(
                    "world/map.bin", "application/octet-stream", ordinaryBytes)));
    when(owner.readAssetBytes("tenant-key", "43"))
        .thenReturn(
            Optional.of(
                new SelectedDraftAssetInventoryReadService.AssetBytes(
                    "branding/logo.png", "image/png", brandingBytes)));

    var inventory =
        new SelectedDraftAssetInventoryReadService(owner, transactions).read(fixture.request());

    assertThat(inventory.assets()).hasSize(2);
    assertThat(inventory.assets())
        .extracting(SelectedDraftAssetInventory.Asset::usageKey)
        .containsExactly("branding/logo.png", "world/map.bin");
    var branding = inventory.assets().getFirst();
    assertThat(branding.family()).isEqualTo(SelectedDraftAssetInventory.Family.BRANDING);
    assertThat(branding.role()).isEqualTo("LOGO");
    assertThat(branding.requiredness()).isEqualTo("REQUIRED");
    assertThat(branding.contentBytes()).isEqualTo(brandingBytes);
    assertThat(branding.contentDigest()).isEqualTo(CommandSource.sha256(brandingBytes));
    assertThat(branding.revisionId())
        .isEqualTo(
            fixture.sources().branding().orElseThrow().items().getFirst().reference().revisionId());
    assertThat(branding.sourceBinding()).isNotEqualTo(fixture.binding());
    var ordinary = inventory.assets().getLast();
    assertThat(ordinary.family()).isEqualTo(SelectedDraftAssetInventory.Family.ORDINARY);
    assertThat(ordinary.role()).isEqualTo("RESOURCE");
    assertThat(ordinary.contentBytes()).isEqualTo(ordinaryBytes);
    assertThat(ordinary.contentDigest()).isEqualTo(CommandSource.sha256(ordinaryBytes));
    assertThat(ordinary.revisionId())
        .isEqualTo(fixture.sources().asset().items().getFirst().reference().revisionId());
    assertThat(ordinary.sourceBinding()).isNotEqualTo(fixture.binding());
    assertThat(inventory.sourceFamilyDeclarations())
        .containsEntry("ORDINARY", "PRESENT")
        .containsEntry("BRANDING:LOGO", "PRESENT");
    var canonicalInventory = new ObjectMapper().readTree(inventory.canonicalJson());
    assertThat(canonicalInventory.path("assets").get(0).path("sourceBindingJson").textValue())
        .isEqualTo(branding.sourceBinding().canonicalJson());
    assertThat(canonicalInventory.path("assets").get(1).path("sourceBindingJson").textValue())
        .isEqualTo(ordinary.sourceBinding().canonicalJson());
    assertThat(canonicalInventory.path("assets").get(1).path("byteSize").textValue())
        .isEqualTo(Integer.toString(ordinaryBytes.length));
    verify(owner).readAssetBytes("tenant-key", "42");
    verify(owner).readAssetBytes("tenant-key", "43");
  }

  @Test
  void rejectsActualByteMismatchAndCrossFamilyUsageKeyCollisionBeforeReturningInventory()
      throws Exception {
    var empty = fixture();
    byte[] ordinaryBytes = "ordinary map".getBytes(StandardCharsets.UTF_8);
    byte[] brandingBytes = "branding logo".getBytes(StandardCharsets.UTF_8);
    var fixture =
        withAssets(empty, "shared.bin", ordinaryBytes, "branding/logo.png", brandingBytes);
    stubOperationAndSources(fixture);
    when(owner.readGameLogicReceipt(fixture.request()))
        .thenReturn(Optional.of(fixture.gameLogicReceipt()));
    when(owner.readAssetBytes("tenant-key", "42"))
        .thenReturn(
            Optional.of(
                new SelectedDraftAssetInventoryReadService.AssetBytes(
                    "shared.bin",
                    "application/octet-stream",
                    "changed".getBytes(StandardCharsets.UTF_8))));
    assertCode(
        Status.Code.FAILED_PRECONDITION,
        () ->
            new SelectedDraftAssetInventoryReadService(owner, transactions)
                .read(fixture.request()));

    org.mockito.Mockito.reset(owner);
    var collision =
        withAssets(empty, "duplicate.png", ordinaryBytes, "duplicate.png", brandingBytes);
    stubOperationAndSources(collision);
    when(owner.readGameLogicReceipt(collision.request()))
        .thenReturn(Optional.of(collision.gameLogicReceipt()));
    assertCode(
        Status.Code.FAILED_PRECONDITION,
        () ->
            new SelectedDraftAssetInventoryReadService(owner, transactions)
                .read(collision.request()));
    verify(owner, never()).readAssetBytes(any(), any());

    org.mockito.Mockito.reset(owner);
    assertThatThrownBy(
            () ->
                AssetSource.upsertPayload("44", "manifest.json", AssetSource.Requiredness.REQUIRED))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                BrandingSource.upsertPayload(
                    "45",
                    "manifest.json",
                    BrandingSource.Role.LOGO,
                    BrandingSource.Requiredness.REQUIRED))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void missingOrCorruptCaptureChangedSourceAndDifferentReceiptSelectionAreDenied()
      throws Exception {
    var fixture = fixture();
    when(transactions.getTransaction(any())).thenReturn(transactionStatus);
    when(owner.read(fixture.request().derivedWorkflowIdentity()))
        .thenReturn(
            Optional.of(
                new GameDesignPublicationOperationRepository.Readback(
                    fixture.operation(), "PENDING", new byte[] {1})));
    when(owner.readGenesis(fixture.binding().target())).thenReturn(Optional.of(fixture.genesis()));
    when(owner.readSourceCapture(fixture.operation())).thenReturn(Optional.empty());
    assertCode(
        Status.Code.FAILED_PRECONDITION,
        () ->
            new SelectedDraftAssetInventoryReadService(owner, transactions)
                .read(fixture.request()));
    verify(owner, never()).readSynchronized(any(), any());

    org.mockito.Mockito.reset(owner);
    stubOperationAndSources(fixture);
    var changedSelection = receiptWithChangedSelection(fixture).selection();
    var changedSelectionBinding =
        AuthoredDraftPublishSelectionBinding.fromStored(
            changedSelection.canonicalJson(), changedSelection.digest());
    var anotherOperation =
        IsolatedPublicationOperationFixtures.forSelection(
            changedSelectionBinding, fixture.operation().world());
    assertThat(anotherOperation.account().input().selection().canonicalJson())
        .isNotEqualTo(fixture.operation().account().input().selection().canonicalJson());
    when(owner.readSourceCapture(fixture.operation()))
        .thenReturn(Optional.of(capture(anotherOperation, fixture.sources())));
    assertCode(
        Status.Code.FAILED_PRECONDITION,
        () ->
            new SelectedDraftAssetInventoryReadService(owner, transactions)
                .read(fixture.request()));
    verify(owner, never()).readSynchronized(any(), any());

    org.mockito.Mockito.reset(owner);
    stubOperationAndSources(fixture);
    var changedAsset =
        new AssetSnapshot(
            fixture.binding(), "1", null, fixture.genesis().asset().receiptId(), List.of());
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
    assertCode(
        Status.Code.FAILED_PRECONDITION,
        () ->
            new SelectedDraftAssetInventoryReadService(owner, transactions)
                .read(fixture.request()));
    verify(owner, never()).readGameLogicReceipt(any());

    org.mockito.Mockito.reset(owner);
    stubOperationAndSources(fixture);
    var changedReceipt = receiptWithChangedSelection(fixture);
    assertThat(changedReceipt.selection().selectedCommit()).isEqualTo(fixture.binding());
    assertThat(changedReceipt.selection().canonicalJson())
        .isNotEqualTo(fixture.operation().account().input().selection().canonicalJson());
    assertThat(changedReceipt.workflowIdentity()).isEqualTo(fixture.operation().workflowId());
    when(owner.readGameLogicReceipt(fixture.request())).thenReturn(Optional.of(changedReceipt));
    assertCode(
        Status.Code.FAILED_PRECONDITION,
        () ->
            new SelectedDraftAssetInventoryReadService(owner, transactions)
                .read(fixture.request()));
    verify(owner, never()).readAssetBytes(any(), any());
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
    var gameplay =
        new GameplayRuleSnapshot(
            binding, "0", null, UUID.fromString("44444444-4444-4444-8444-444444444444"), List.of());
    var source = new GameplayRuleSelectedSource(gameplay.canonicalJson());
    UUID actor = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    var authorization =
        new GameLogicIntakeAuthorizationBinding(
            UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
            UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
            UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd"),
            actor,
            source,
            List.of(
                new DraftAuthorizationFenceBinding.SourceEvidence(
                    DraftAuthorizationFenceBinding.SourceKind.ACCOUNT,
                    actor.toString(),
                    "1",
                    "1",
                    null,
                    null,
                    new byte[] {1})));
    var intake = new GameLogicGameplayRuleIntakeOperation("test", authorization);
    var terminal =
        GameLogicGameplayRuleIntakeTerminal.retained(
            intake,
            source.canonicalBytes(),
            gameplay.manifest().canonicalJson().getBytes(StandardCharsets.UTF_8));
    var receipt =
        new SelectedDraftGameLogicReceipt(
            AuthoredDraftPublishSelection.fromStored(
                operation.account().input().selection().canonicalJson(),
                operation.account().input().selection().digest()),
            authorization,
            new AccountGameLogicIntakeSettlementEvidence(terminal));
    var sources = sources(binding, gameplay);
    var genesis = genesis(binding.target());
    var capture = capture(operation, sources);
    var request =
        PublicationDigestRequestBinding.full(
            operation.account().input().selection().intent().canonicalTenantId().toString(),
            Long.toString(operation.versionId()),
            operation.account().input().selection().intent().publishRequestId());
    return new Fixture(operation, binding, genesis, sources, capture, receipt, request);
  }

  private Fixture withAssets(
      Fixture base,
      String ordinaryUsageKey,
      byte[] ordinaryBytes,
      String brandingUsageKey,
      byte[] brandingBytes) {
    var ordinarySource =
        sourceBinding(
            base.binding().target(),
            AssetSource.SCOPE,
            AssetSource.upsertPayload("42", ordinaryUsageKey, AssetSource.Requiredness.REQUIRED));
    var ordinaryReference =
        new AssetSource.Reference(
            ordinaryUsageKey,
            "42",
            AssetSource.Requiredness.REQUIRED,
            ordinarySource,
            "0",
            ordinarySource.revisions().getFirst().revisionId());
    var ordinaryItem =
        new net.firedevops.firemud.common.gamedesign.AssetSnapshot.Item(
            ordinaryReference,
            "application/octet-stream",
            CommandSource.sha256(ordinaryBytes),
            ordinaryBytes.length);

    var brandingSource =
        sourceBinding(
            base.binding().target(),
            BrandingSource.SCOPE,
            BrandingSource.upsertPayload(
                "43",
                brandingUsageKey,
                BrandingSource.Role.LOGO,
                BrandingSource.Requiredness.REQUIRED));
    var brandingReference =
        new BrandingSource.Reference(
            brandingUsageKey,
            "43",
            BrandingSource.Role.LOGO,
            BrandingSource.Requiredness.REQUIRED,
            brandingSource,
            "0",
            brandingSource.revisions().getFirst().revisionId());
    var brandingItem =
        new net.firedevops.firemud.common.gamedesign.BrandingSourceSnapshot.Item(
            brandingReference,
            "image/png",
            CommandSource.sha256(brandingBytes),
            brandingBytes.length);

    var assetSnapshot =
        new AssetSnapshot(
            base.binding(), "1", null, base.genesis().asset().receiptId(), List.of(ordinaryItem));
    var brandingSnapshot =
        new BrandingSourceSnapshot(
            base.binding(),
            "1",
            null,
            base.genesis().branding().receiptId(),
            List.of(brandingItem));
    var sources =
        new GameDesignSourceRepository.SynchronizedSources(
            base.sources().command(),
            base.sources().policy(),
            assetSnapshot,
            base.sources().gameplay(),
            Optional.of(brandingSnapshot),
            base.sources().templateConfig());
    return new Fixture(
        base.operation(),
        base.binding(),
        base.genesis(),
        sources,
        capture(base.operation(), sources),
        base.gameLogicReceipt(),
        base.request());
  }

  private static DraftCommitBinding sourceBinding(
      TargetProof target, String scope, String payload) {
    UUID revisionId = UUID.randomUUID();
    String scopeId = "effective";
    return DraftCommitBinding.create(
        target,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "source-parent",
        List.of(
            new DraftCommitBinding.RevisionPayload(
                "0", revisionId, DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE, payload)),
        List.of(
            new DraftCommitBinding.AffectedUnit(
                DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                scope,
                target.canonicalVersionId().toString(),
                scope,
                scopeId,
                "0")));
  }

  private static GameDesignSourceRepository.Capture capture(
      GameDesignPublicationOperation operation,
      GameDesignSourceRepository.SynchronizedSources sources) {
    return new GameDesignSourceRepository.Capture(
        new CommandSnapshot.Capture(operation, sources.command()),
        new RealmPolicySnapshot.Capture(operation, sources.policy()),
        new AssetSnapshot.Capture(operation, sources.asset()),
        new BrandingSourceSnapshot.Capture(operation, sources.branding().orElseThrow()),
        new TemplateConfigSourceSnapshot.Capture(
            operation, sources.templateConfig().orElseThrow()));
  }

  private void stubOperationAndSources(Fixture fixture) {
    when(transactions.getTransaction(any())).thenReturn(transactionStatus);
    when(owner.read(fixture.request().derivedWorkflowIdentity()))
        .thenReturn(
            Optional.of(
                new GameDesignPublicationOperationRepository.Readback(
                    fixture.operation(), "PENDING", new byte[] {1})));
    when(owner.readGenesis(fixture.binding().target())).thenReturn(Optional.of(fixture.genesis()));
    when(owner.readSourceCapture(fixture.operation())).thenReturn(Optional.of(fixture.capture()));
    when(owner.readSynchronized(fixture.binding().target(), fixture.binding().commitId()))
        .thenReturn(Optional.of(fixture.sources()));
  }

  private static SelectedDraftGameLogicReceipt receiptWithChangedSelection(Fixture fixture) {
    var original = fixture.operation().account().input().selection();
    var intent = original.intent();
    var changedIntent =
        new AuthoredDraftPublishSelectionBinding.PublishIntent(
            intent.canonicalTenantId(),
            intent.canonicalVersionId(),
            intent.publishRequestId(),
            intent.expectedVersionStateEpoch(),
            intent.notes() + " changed",
            intent.selectedCommitRequestId(),
            intent.selectedCommitId(),
            intent.selectedCommitDigest());
    var changed =
        AuthoredDraftPublishSelectionBinding.capture(
            changedIntent,
            original.target(),
            original.selectedCommit(),
            new AuthoredDraftPublishSelectionBinding.VisibilityFence(
                original.target(),
                original.fenceRequestId(),
                original.fenceCommitId(),
                original.fenceInputDigest(),
                original.fenceResultVectorJson(),
                OffsetDateTime.parse(original.fenceCreatedAt())));
    var selection =
        AuthoredDraftPublishSelection.fromStored(changed.canonicalJson(), changed.digest());
    return new SelectedDraftGameLogicReceipt(
        selection,
        fixture.gameLogicReceipt().authorization(),
        fixture.gameLogicReceipt().receipt());
  }

  private static GameDesignSourceRepository.Genesis genesis(TargetProof target) {
    UUID commandPolicyReceipt = UUID.fromString("99999999-9999-4999-8999-999999999999");
    return new GameDesignSourceRepository.Genesis(
        new RealmPolicyGenesis(target, commandPolicyReceipt, "1"),
        new CommandSource.NewDraftGenesisReceipt(target, commandPolicyReceipt, "1"),
        new AssetSnapshot.Genesis(
            target, UUID.fromString("33333333-3333-4333-8333-333333333333"), "1"),
        new GameplayRuleSnapshot.Genesis(
            target,
            UUID.fromString("44444444-4444-4444-8444-444444444444"),
            "1",
            GameplayRuleManifest.explicitEmpty()),
        new BrandingSourceSnapshot.Genesis(
            target, UUID.fromString("55555555-5555-4555-8555-555555555555"), "1"),
        new TemplateConfigSourceSnapshot.Genesis(
            target, UUID.fromString("66666666-6666-4666-8666-666666666666"), "1"));
  }

  private static GameDesignSourceRepository.SynchronizedSources sources(
      DraftCommitBinding binding, GameplayRuleSnapshot gameplay) {
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
      GameDesignSourceRepository.Genesis genesis,
      GameDesignSourceRepository.SynchronizedSources sources,
      GameDesignSourceRepository.Capture capture,
      SelectedDraftGameLogicReceipt gameLogicReceipt,
      PublicationDigestRequestBinding request) {}
}
