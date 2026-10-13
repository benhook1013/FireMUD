package net.firedevops.firemud.gamedesign.publication;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.gamedesign.AssetSource;
import net.firedevops.firemud.common.gamedesign.BrandingSource;
import net.firedevops.firemud.common.gamedesign.CommandSource;
import net.firedevops.firemud.common.gamedesign.GameplayRuleSource;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.exception.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Read-only selected source inventory producer; it neither writes release objects nor finalizes.
 */
public final class SelectedDraftAssetInventoryReadService {
  private final OwnerRead owner;
  private final TransactionTemplate snapshot;

  public SelectedDraftAssetInventoryReadService(
      DSLContext dsl, PlatformTransactionManager transactions) {
    this(
        new RepositoryOwnerRead(
            new GameDesignPublicationOperationRepository(Objects.requireNonNull(dsl, "dsl")),
            new GameDesignSourceRepository(dsl),
            new SelectedDraftGameLogicReceiptRepository(dsl),
            dsl),
        transactions);
  }

  SelectedDraftAssetInventoryReadService(OwnerRead owner, PlatformTransactionManager transactions) {
    this.owner = Objects.requireNonNull(owner, "owner");
    snapshot = new TransactionTemplate(Objects.requireNonNull(transactions, "transactions"));
    snapshot.setName("game-design-selected-draft-asset-inventory-read");
    snapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    snapshot.setReadOnly(true);
  }

  /** Reads one exact complete inventory inside an independent owner-local database snapshot. */
  public SelectedDraftAssetInventory read(PublicationDigestRequestBinding request) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Independent selected asset inventory snapshot required")
          .asRuntimeException();
    }
    validateRequest(request);
    try {
      return Objects.requireNonNull(
          snapshot.execute(ignored -> readSnapshot(request)),
          "Selected asset inventory snapshot returned no result");
    } catch (StatusRuntimeException failure) {
      throw failure;
    } catch (DataAccessException unavailable) {
      throw Status.UNAVAILABLE
          .withDescription("Selected asset inventory readback unavailable")
          .withCause(unavailable)
          .asRuntimeException();
    } catch (RuntimeException invalid) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Exact selected asset inventory evidence unavailable")
          .withCause(invalid)
          .asRuntimeException();
    }
  }

  private SelectedDraftAssetInventory readSnapshot(PublicationDigestRequestBinding request) {
    var readback =
        owner
            .read(request.derivedWorkflowIdentity())
            .orElseThrow(
                () ->
                    Status.NOT_FOUND
                        .withDescription("Selected publication operation unavailable")
                        .asRuntimeException());
    GameDesignPublicationOperation operation = readback.operation();
    requireOperationCorrelation(operation, request);
    if (!"PENDING".equals(readback.outcome())) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Selected publication operation is no longer pending")
          .asRuntimeException();
    }

    owner.requireExactSelection(operation);
    TargetProof target = operation.account().input().selection().target();
    GameDesignSourceRepository.Genesis genesis =
        owner
            .readGenesis(target)
            .orElseThrow(
                () ->
                    Status.FAILED_PRECONDITION
                        .withDescription("Complete actual Game Design source genesis unavailable")
                        .asRuntimeException());
    GameDesignSourceRepository.Capture capture =
        owner
            .readSourceCapture(operation)
            .orElseThrow(
                () ->
                    Status.FAILED_PRECONDITION
                        .withDescription("Complete selected source capture unavailable")
                        .asRuntimeException());
    requireCapture(operation, capture);

    DraftCommitBinding selected = operation.account().input().selection().selectedCommit();
    GameDesignSourceRepository.SynchronizedSources sources =
        owner
            .readSynchronized(selected.target(), selected.commitId())
            .orElseThrow(
                () ->
                    Status.FAILED_PRECONDITION
                        .withDescription("Complete synchronized selected sources unavailable")
                        .asRuntimeException());
    requireFrozenSources(selected, capture, sources);
    requireGenesisMatches(target, genesis, sources);

    SelectedDraftGameLogicReceipt receipt =
        owner
            .readGameLogicReceipt(request)
            .orElseThrow(
                () ->
                    Status.FAILED_PRECONDITION
                        .withDescription("Exact retained Game Logic source receipt unavailable")
                        .asRuntimeException());
    requireGameLogicConsumerEvidence(operation, selected, sources, receipt);

    List<PendingAsset> pendingAssets = pendingAssets(selected.target(), sources);
    List<SelectedDraftAssetInventory.Asset> assets = new ArrayList<>();
    for (PendingAsset pending : pendingAssets) {
      AssetBytes actual =
          owner
              .readAssetBytes(selected.target().gameDesignVersionTenantKey(), pending.assetRowId())
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "Exact tenant-qualified selected asset bytes unavailable"));
      byte[] bytes = actual.contentBytes();
      if (!pending.usageKey().equals(actual.fileName())
          || !pending.contentType().equals(actual.contentType())
          || !pending.contentDigest().equals(CommandSource.sha256(bytes))
          || pending.byteSize() != bytes.length) {
        throw new IllegalStateException("Selected asset bytes differ from owner source snapshot");
      }
      assets.add(
          new SelectedDraftAssetInventory.Asset(
              pending.family(),
              pending.role(),
              pending.usageKey(),
              pending.assetRowId(),
              pending.requiredness(),
              pending.sourceBinding(),
              pending.revisionOrder(),
              pending.revisionId(),
              actual.contentType(),
              pending.contentDigest(),
              bytes));
    }
    assets.sort(
        (left, right) ->
            Arrays.compareUnsigned(
                left.usageKey().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                right.usageKey().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    return new SelectedDraftAssetInventory(
        request, operation, genesis, capture, sources, receipt, assets);
  }

  private static void requireGenesisMatches(
      TargetProof selectedTarget,
      GameDesignSourceRepository.Genesis genesis,
      GameDesignSourceRepository.SynchronizedSources sources) {
    if (!selectedTarget.equals(genesis.policy().target())
        || !selectedTarget.equals(genesis.command().target())
        || !selectedTarget.equals(genesis.asset().target())
        || !selectedTarget.equals(genesis.gameplay().target())
        || !selectedTarget.equals(genesis.branding().target())
        || !selectedTarget.equals(genesis.templateConfig().target())
        || !genesis.asset().receiptId().equals(sources.asset().genesisReceiptId())
        || !genesis.gameplay().receiptId().equals(sources.gameplay().genesisReceiptId())
        || !genesis
            .branding()
            .receiptId()
            .equals(sources.branding().orElseThrow().genesisReceiptId())
        || !genesis
            .templateConfig()
            .receiptId()
            .equals(sources.templateConfig().orElseThrow().genesisReceiptId())) {
      throw new IllegalStateException(
          "Selected snapshots differ from actual source genesis history");
    }
  }

  private static List<PendingAsset> pendingAssets(
      TargetProof selectedTarget, GameDesignSourceRepository.SynchronizedSources sources) {
    var pending = new ArrayList<PendingAsset>();
    var usageKeys = new HashSet<String>();
    for (net.firedevops.firemud.common.gamedesign.AssetSnapshot.Item item :
        sources.asset().items()) {
      var reference = item.reference();
      requireAssetReference(selectedTarget, reference.sourceBinding(), reference.usageKey());
      if (reference.requiredness() != AssetSource.Requiredness.REQUIRED
          || !usageKeys.add(reference.usageKey())) {
        throw new IllegalStateException("Ordinary asset requiredness or usage key is unsupported");
      }
      pending.add(
          new PendingAsset(
              SelectedDraftAssetInventory.Family.ORDINARY,
              reference.role(),
              reference.usageKey(),
              reference.assetRowId(),
              reference.requiredness().name(),
              reference.sourceBinding(),
              reference.revisionOrder(),
              reference.revisionId(),
              item.contentType(),
              item.contentDigest(),
              item.byteSize()));
    }
    BrandingSourceSnapshot branding = sources.branding().orElseThrow();
    for (net.firedevops.firemud.common.gamedesign.BrandingSourceSnapshot.Item item :
        branding.items()) {
      var reference = item.reference();
      requireAssetReference(selectedTarget, reference.sourceBinding(), reference.usageKey());
      if (reference.requiredness() != BrandingSource.Requiredness.REQUIRED
          || !usageKeys.add(reference.usageKey())) {
        throw new IllegalStateException("Branding requiredness or cross-family key is unsupported");
      }
      pending.add(
          new PendingAsset(
              SelectedDraftAssetInventory.Family.BRANDING,
              reference.role().name(),
              reference.usageKey(),
              reference.assetRowId(),
              reference.requiredness().name(),
              reference.sourceBinding(),
              reference.revisionOrder(),
              reference.revisionId(),
              item.contentType(),
              item.contentDigest(),
              item.byteSize()));
    }
    return List.copyOf(pending);
  }

  private static void requireAssetReference(
      TargetProof selectedTarget, DraftCommitBinding sourceBinding, String usageKey) {
    if (!selectedTarget.equals(sourceBinding.target())
        || usageKey == null
        || usageKey.isBlank()
        || "manifest.json".equals(usageKey)) {
      throw new IllegalStateException("Asset provenance, reserved key, or source target conflict");
    }
  }

  private static void requireGameLogicConsumerEvidence(
      GameDesignPublicationOperation operation,
      DraftCommitBinding selected,
      GameDesignSourceRepository.SynchronizedSources sources,
      SelectedDraftGameLogicReceipt receipt) {
    var selection = receipt.selection();
    SelectedDraftGameLogicReceipt.requireExactSelection(selection, receipt.authorization());
    if (!Arrays.equals(
            operation.account().input().selection().canonicalBytes(), selection.canonicalBytes())
        || !selected.equals(selection.selectedCommit())
        || !Arrays.equals(
            sources.gameplay().canonicalBytes(), receipt.authorization().source().canonicalBytes())
        || !receipt
            .workflowIdentity()
            .equals(
                PublicationDigestRequestBinding.full(
                        selection.intent().canonicalTenantId().toString(),
                        Long.toString(selection.target().gameDesignVersionRowId()),
                        selection.intent().publishRequestId())
                    .derivedWorkflowIdentity())) {
      throw new IllegalStateException("Retained Game Logic receipt differs from selected source");
    }

    Set<String> selectedRules = new HashSet<>();
    for (GameplayRuleSource.Entry entry : sources.gameplay().entries()) {
      selectedRules.add(
          entry.family().name() + ":" + entry.definition().key() + ":" + entry.revisionId());
    }
    TemplateConfigSourceSnapshot template = sources.templateConfig().orElseThrow();
    for (TemplateConfigSource.Entry entry : template.entries()) {
      entry.config().requireAvailableOwnerReads();
      for (TemplateConfigSource.GameplayInput input : entry.config().gameplayInputs()) {
        if (!selectedRules.contains(
            input.family().name() + ":" + input.key() + ":" + input.revisionId())) {
          throw new IllegalStateException(
              "Template Game Logic input lacks exact selected owner source evidence");
        }
      }
    }
  }

  private static void requireCapture(
      GameDesignPublicationOperation operation, GameDesignSourceRepository.Capture capture) {
    byte[] operationBytes = operation.canonicalBytes();
    for (byte[] retainedOperation :
        new byte[][] {
          capture.command().operation().canonicalBytes(),
          capture.policy().operation().canonicalBytes(),
          capture.asset().operation().canonicalBytes(),
          capture.branding().operation().canonicalBytes(),
          capture.templateConfig().operation().canonicalBytes()
        }) {
      if (!Arrays.equals(operationBytes, retainedOperation)) {
        throw new IllegalStateException("Frozen source capture differs from exact operation");
      }
    }
  }

  private static void requireFrozenSources(
      DraftCommitBinding selected,
      GameDesignSourceRepository.Capture frozen,
      GameDesignSourceRepository.SynchronizedSources synchronizedSources) {
    BrandingSourceSnapshot branding = synchronizedSources.branding().orElseThrow();
    TemplateConfigSourceSnapshot templateConfig =
        synchronizedSources.templateConfig().orElseThrow();
    if (!selected.equals(synchronizedSources.command().binding())
        || !selected.equals(synchronizedSources.policy().binding())
        || !selected.equals(synchronizedSources.asset().binding())
        || !selected.equals(synchronizedSources.gameplay().binding())
        || !selected.equals(branding.binding())
        || !selected.equals(templateConfig.binding())
        || !Arrays.equals(
            frozen.command().snapshot().canonicalBytes(),
            synchronizedSources.command().canonicalBytes())
        || !Arrays.equals(
            frozen.policy().snapshot().canonicalBytes(),
            synchronizedSources.policy().canonicalBytes())
        || !Arrays.equals(
            frozen.asset().snapshot().canonicalBytes(),
            synchronizedSources.asset().canonicalBytes())
        || !Arrays.equals(frozen.branding().snapshot().canonicalBytes(), branding.canonicalBytes())
        || !Arrays.equals(
            frozen.templateConfig().snapshot().canonicalBytes(), templateConfig.canonicalBytes())) {
      throw new IllegalStateException(
          "Frozen and synchronized sources differ from complete selected commit");
    }
  }

  private static void requireOperationCorrelation(
      GameDesignPublicationOperation operation, PublicationDigestRequestBinding request) {
    var selection = operation.account().input().selection();
    var intent = selection.intent();
    TargetProof target = selection.target();
    DraftCommitBinding selected = selection.selectedCommit();
    var world = operation.world().request();
    UUID tenant;
    long numericVersion;
    try {
      tenant = UUID.fromString(request.tenantId());
      numericVersion = Long.parseLong(request.versionId());
    } catch (RuntimeException malformed) {
      throw new IllegalArgumentException(
          "Canonical tenant and numeric version required", malformed);
    }
    if (!tenant.toString().equals(request.tenantId())
        || numericVersion <= 0
        || !tenant.equals(target.canonicalTenantId())
        || !tenant.equals(intent.canonicalTenantId())
        || target.gameDesignVersionRowId() != numericVersion
        || operation.versionId() != numericVersion
        || !operation.tenantKey().equals(target.gameDesignVersionTenantKey())
        || !operation.selectionDigest().equals(selection.digest())
        || !intent.canonicalVersionId().equals(target.canonicalVersionId())
        || !intent.canonicalVersionId().equals(selected.target().canonicalVersionId())
        || !request.publishRequestId().equals(intent.publishRequestId())
        || !request.derivedWorkflowIdentity().equals(operation.workflowId())
        || !request.derivedWorkflowIdentity().equals(world.publishWorkflowId())
        || !tenant.equals(world.canonicalTenantId())
        || !intent.canonicalVersionId().equals(world.canonicalVersionId())
        || !intent.publishRequestId().equals(world.publicationRequestId())
        || !selection.digest().substring("sha256:".length()).equals(world.requestDigest())
        || !intent.expectedVersionStateEpoch().equals(Long.toString(world.versionStateEpoch()))
        || !selected.commitId().toString().equals(world.appliedCommitId())) {
      throw new IllegalStateException(
          "Publication operation differs from canonical selected request");
    }
  }

  private static void validateRequest(PublicationDigestRequestBinding request) {
    if (request == null
        || request.scopeKind() != PublicationDigestRequestBinding.ScopeKind.FULL_VERSION) {
      throw Status.INVALID_ARGUMENT
          .withDescription("Canonical full-version publication binding required")
          .asRuntimeException();
    }
    try {
      var canonical =
          PublicationDigestRequestBinding.full(
              request.tenantId(), request.versionId(), request.publishRequestId());
      UUID tenant = UUID.fromString(request.tenantId());
      if (!Arrays.equals(canonical.canonicalPreimage(), request.canonicalPreimage())
          || !canonical.requestDigest().equals(request.requestDigest())
          || !canonical.derivedWorkflowIdentity().equals(request.derivedWorkflowIdentity())
          || !tenant.toString().equals(request.tenantId())) {
        throw new IllegalArgumentException("Publication request binding is not canonical");
      }
    } catch (RuntimeException malformed) {
      throw Status.INVALID_ARGUMENT
          .withDescription("Canonical full-version publication binding required")
          .withCause(malformed)
          .asRuntimeException();
    }
  }

  interface OwnerRead {
    Optional<GameDesignPublicationOperationRepository.Readback> read(String workflowIdentity);

    void requireExactSelection(GameDesignPublicationOperation operation);

    Optional<GameDesignSourceRepository.Capture> readSourceCapture(
        GameDesignPublicationOperation operation);

    Optional<GameDesignSourceRepository.Genesis> readGenesis(TargetProof target);

    Optional<GameDesignSourceRepository.SynchronizedSources> readSynchronized(
        TargetProof target, UUID selectedCommitId);

    Optional<SelectedDraftGameLogicReceipt> readGameLogicReceipt(
        PublicationDigestRequestBinding request);

    Optional<AssetBytes> readAssetBytes(String tenantKey, String assetRowId);
  }

  record AssetBytes(String fileName, String contentType, byte[] contentBytes) {
    AssetBytes {
      if (contentBytes == null) throw new IllegalArgumentException("Actual owner bytes required");
      contentBytes = contentBytes.clone();
    }

    @Override
    public byte[] contentBytes() {
      return contentBytes.clone();
    }
  }

  private record PendingAsset(
      SelectedDraftAssetInventory.Family family,
      String role,
      String usageKey,
      String assetRowId,
      String requiredness,
      DraftCommitBinding sourceBinding,
      String revisionOrder,
      UUID revisionId,
      String contentType,
      String contentDigest,
      long byteSize) {}

  private record RepositoryOwnerRead(
      GameDesignPublicationOperationRepository operations,
      GameDesignSourceRepository sources,
      SelectedDraftGameLogicReceiptRepository gameLogicReceipts,
      DSLContext dsl)
      implements OwnerRead {
    private RepositoryOwnerRead {
      Objects.requireNonNull(operations, "operations");
      Objects.requireNonNull(sources, "sources");
      Objects.requireNonNull(gameLogicReceipts, "gameLogicReceipts");
      Objects.requireNonNull(dsl, "dsl");
    }

    @Override
    public Optional<GameDesignPublicationOperationRepository.Readback> read(
        String workflowIdentity) {
      return operations.read(workflowIdentity);
    }

    @Override
    public void requireExactSelection(GameDesignPublicationOperation operation) {
      operations.requireExactSelection(operation);
    }

    @Override
    public Optional<GameDesignSourceRepository.Capture> readSourceCapture(
        GameDesignPublicationOperation operation) {
      return operations.readSourceCapture(operation);
    }

    @Override
    public Optional<GameDesignSourceRepository.Genesis> readGenesis(TargetProof target) {
      return sources.readGenesis(target);
    }

    @Override
    public Optional<GameDesignSourceRepository.SynchronizedSources> readSynchronized(
        TargetProof target, UUID selectedCommitId) {
      return sources.readSynchronized(target, selectedCommitId);
    }

    @Override
    public Optional<SelectedDraftGameLogicReceipt> readGameLogicReceipt(
        PublicationDigestRequestBinding request) {
      return gameLogicReceipts.readForPublication(request);
    }

    @Override
    public Optional<AssetBytes> readAssetBytes(String tenantKey, String assetRowId) {
      Record row =
          dsl.fetchOne(
              "SELECT file_name, content_type, data FROM game_assets WHERE tenant_id = ? AND id = ?",
              tenantKey,
              Long.parseLong(assetRowId));
      if (row == null) return Optional.empty();
      return Optional.of(
          new AssetBytes(
              row.get("file_name", String.class),
              row.get("content_type", String.class),
              row.get("data", byte[].class)));
    }
  }
}
