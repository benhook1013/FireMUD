package net.firedevops.firemud.gamedesign.publication;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.gamedesign.BrandingSource;
import net.firedevops.firemud.common.gamedesign.CommandSource;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Actual branding source writes under the existing coordinator; never total inventory authority.
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Owner-local injected persistence collaborator")
public final class BrandingSourceRepository {
  private final DSLContext dsl;
  private final DraftCommitCoordinatorRepository coordinator;

  public BrandingSourceRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl);
    coordinator = new DraftCommitCoordinatorRepository(dsl);
  }

  public BrandingSourceSnapshot.Genesis enrollFreshDraft(TargetProof target) {
    write();
    lock(target);
    var prior = readGenesis(target);
    if (prior.isPresent()) return prior.orElseThrow();
    var witness =
        dsl.fetchOne(
            "SELECT creation_transaction_id FROM game_design_realm_policy_version_insert "
                + "WHERE version_id = ? AND creation_transaction_id = pg_current_xact_id()::TEXT",
            target.gameDesignVersionRowId());
    if (witness == null)
      throw new IllegalStateException("BRANDING_SOURCE_FRESH_INSERT_UNAVAILABLE");
    var genesis =
        new BrandingSourceSnapshot.Genesis(target, UUID.randomUUID(), witness.get(0, String.class));
    dsl.execute(
        "INSERT INTO game_design_branding_source_genesis "
            + "(canonical_tenant_id, canonical_version_id, version_id, receipt_id, creation_transaction_id, target_json, roles_json) VALUES (?, ?, ?, ?, ?, ?, ?)",
        target.canonicalTenantId(),
        target.canonicalVersionId(),
        target.gameDesignVersionRowId(),
        genesis.receiptId(),
        genesis.creationTransactionId(),
        CommandSource.canonical(CommandSource.targetObject(target)),
        CommandSource.canonical(BrandingSource.emptyRoleDeclarations()));
    dsl.execute(
        "INSERT INTO game_design_branding_source_head "
            + "(canonical_tenant_id, canonical_version_id, source_epoch, genesis_receipt_id) VALUES (?, ?, '0', ?)",
        target.canonicalTenantId(),
        target.canonicalVersionId(),
        genesis.receiptId());
    if (!genesis.equals(readGenesis(target).orElseThrow()))
      throw new IllegalStateException("BRANDING_SOURCE_GENESIS_READBACK_CONFLICT");
    return genesis;
  }

  public Optional<BrandingSourceSnapshot.Genesis> readGenesis(TargetProof target) {
    var row =
        dsl.fetchOne(
            "SELECT * FROM game_design_branding_source_genesis WHERE canonical_tenant_id = ? AND canonical_version_id = ?",
            target.canonicalTenantId(),
            target.canonicalVersionId());
    if (row == null) return Optional.empty();
    if (!CommandSource.canonical(CommandSource.targetObject(target))
            .equals(row.get("target_json", String.class))
        || target.gameDesignVersionRowId() != row.get("version_id", Long.class)
        || !CommandSource.canonical(BrandingSource.emptyRoleDeclarations())
            .equals(row.get("roles_json", String.class)))
      throw new IllegalStateException("BRANDING_SOURCE_GENESIS_TARGET_CONFLICT");
    return Optional.of(
        new BrandingSourceSnapshot.Genesis(
            target,
            row.get("receipt_id", UUID.class),
            row.get("creation_transaction_id", String.class)));
  }

  public Optional<BrandingSourceApplication> apply(DraftCommitBinding binding) {
    write();
    lock(binding.target());
    exact(binding);
    var mutations = BrandingSource.mutations(binding);
    var prior = application(binding);
    if (prior.isPresent()) return prior;
    if (mutations.isEmpty()) return Optional.empty();
    open(binding.target());
    var slot = coordinator.readApplicationSlot(binding.target()).orElseThrow();
    var owner =
        coordinator
            .read(binding.target(), binding.requestId())
            .orElseThrow()
            .ownerStates()
            .get(Owner.GAME_DESIGN_CONTROL_PLANE);
    if (!binding.requestId().equals(slot.requestId())
        || !binding.commitId().equals(slot.commitId())
        || owner == null
        || (owner.status() != DraftCommitCoordinatorRepository.OwnerStatus.IN_PROGRESS
            && owner.status() != DraftCommitCoordinatorRepository.OwnerStatus.UNKNOWN))
      throw new IllegalStateException("BRANDING_SOURCE_COORDINATOR_APPLICATION_UNAVAILABLE");
    Record head = head(binding.target());
    var units =
        binding.affectedUnits(Owner.GAME_DESIGN_CONTROL_PLANE).stream()
            .filter(BrandingSourceRepository::isScope)
            .toList();
    String epoch = head.get("source_epoch", String.class);
    if (units.size() != 1
        || !binding.target().canonicalVersionId().toString().equals(units.getFirst().aggregateId())
        || !epoch.equals(units.getFirst().expectedEpoch()))
      throw new IllegalStateException("BRANDING_SOURCE_EPOCH_CONFLICT");
    UUID inherited = head.get("visible_commit_id", UUID.class);
    requirePreviousFence(binding.target(), inherited);
    List<net.firedevops.firemud.common.gamedesign.BrandingSourceSnapshot.Item> previous =
        inherited == null
            ? List.of()
            : readSnapshot(binding.target(), inherited).orElseThrow().items();
    if (inherited == null && !"0".equals(epoch))
      throw new IllegalStateException("BRANDING_SOURCE_GENESIS_NOT_CURRENT");
    // Lock all actual source rows in owner-key order before writing any revision history.
    mutations.stream()
        .filter(m -> m.operation() == BrandingSource.OperationKind.UPSERT)
        .map(m -> Long.parseLong(m.assetRowId()))
        .distinct()
        .sorted()
        .forEach(
            id -> {
              if (dsl.fetchOne(
                      "SELECT id FROM game_assets WHERE tenant_id = ? AND id = ? FOR UPDATE",
                      binding.target().gameDesignVersionTenantKey(),
                      id)
                  == null) throw new IllegalStateException("BRANDING_SOURCE_ROW_UNAVAILABLE");
            });
    for (var mutation : mutations) {
      net.firedevops.firemud.common.gamedesign.BrandingSourceSnapshot.Item item =
          mutation.operation() == BrandingSource.OperationKind.UPSERT
              ? qualify(
                  new BrandingSource.Reference(
                      mutation.usageKey(),
                      mutation.assetRowId(),
                      mutation.role(),
                      mutation.requiredness(),
                      binding,
                      mutation.revisionOrder(),
                      mutation.revisionId()),
                  true)
              : null;
      dsl.execute(
          "INSERT INTO game_design_branding_source_revision "
              + "(canonical_tenant_id, canonical_version_id, request_id, commit_id, revision_id, revision_order, operation_kind, usage_key, role, asset_id, requiredness, content_type, content_digest, byte_size) "
              + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
          binding.target().canonicalTenantId(),
          binding.target().canonicalVersionId(),
          binding.requestId(),
          binding.commitId(),
          mutation.revisionId(),
          Integer.parseInt(mutation.revisionOrder()),
          mutation.operation().name(),
          mutation.usageKey(),
          mutation.role().name(),
          item == null ? null : Long.parseLong(mutation.assetRowId()),
          item == null ? null : mutation.requiredness().name(),
          item == null ? null : item.contentType(),
          item == null ? null : item.contentDigest(),
          item == null ? null : item.byteSize());
    }
    var references =
        BrandingSource.replay(
                previous.stream()
                    .map(
                        net.firedevops.firemud.common.gamedesign.BrandingSourceSnapshot.Item
                            ::reference)
                    .toList(),
                binding)
            .references();
    var snapshot =
        new BrandingSourceSnapshot(
            binding,
            new BigInteger(epoch).add(BigInteger.ONE).toString(),
            inherited,
            head.get("genesis_receipt_id", UUID.class),
            references.stream().map(r -> qualify(r, false)).toList());
    var result = new BrandingSourceApplication(binding, epoch, snapshot);
    dsl.execute(
        "INSERT INTO game_design_branding_source_application "
            + "(canonical_tenant_id, canonical_version_id, request_id, commit_id, expected_epoch, snapshot_json, result_bytes) VALUES (?, ?, ?, ?, ?, ?, ?)",
        binding.target().canonicalTenantId(),
        binding.target().canonicalVersionId(),
        binding.requestId(),
        binding.commitId(),
        epoch,
        snapshot.canonicalJson(),
        result.canonicalBytes());
    if (dsl.execute(
            "UPDATE game_design_branding_source_head SET source_epoch = ?, applied_commit_id = ? "
                + "WHERE canonical_tenant_id = ? AND canonical_version_id = ? AND source_epoch = ? AND visible_commit_id IS NOT DISTINCT FROM CAST(? AS UUID)",
            snapshot.sourceEpoch(),
            binding.commitId(),
            binding.target().canonicalTenantId(),
            binding.target().canonicalVersionId(),
            epoch,
            inherited)
        != 1) throw new IllegalStateException("BRANDING_SOURCE_EPOCH_CONFLICT");
    return Optional.of(application(binding).orElseThrow());
  }

  public BrandingSourceSnapshot captureSynchronized(DraftCommitBinding binding) {
    write();
    lock(binding.target());
    exact(binding);
    var prior = readSnapshot(binding.target(), binding.commitId());
    if (prior.isPresent()) {
      if (!prior.orElseThrow().binding().equals(binding))
        throw new IllegalStateException("BRANDING_SOURCE_SNAPSHOT_BINDING_CONFLICT");
      return prior.orElseThrow();
    }
    open(binding.target());
    requireFence(binding);
    var head = head(binding.target());
    UUID inherited = head.get("visible_commit_id", UUID.class);
    var applied = application(binding);
    BrandingSourceSnapshot snapshot;
    if (!BrandingSource.mutations(binding).isEmpty()) {
      var result =
          applied.orElseThrow(
              () -> new IllegalStateException("BRANDING_SOURCE_APPLICATION_UNAVAILABLE"));
      snapshot = result.snapshot();
      if (!Objects.equals(inherited, snapshot.inheritedCommitId()))
        throw new IllegalStateException("BRANDING_SOURCE_PREDECESSOR_CHANGED");
    } else {
      var items =
          inherited == null
              ? List.<net.firedevops.firemud.common.gamedesign.BrandingSourceSnapshot.Item>of()
              : readSnapshot(binding.target(), inherited).orElseThrow().items();
      snapshot =
          new BrandingSourceSnapshot(
              binding,
              head.get("source_epoch", String.class),
              inherited,
              head.get("genesis_receipt_id", UUID.class),
              items);
    }
    if (!snapshot.sourceEpoch().equals(head.get("source_epoch", String.class)))
      throw new IllegalStateException("BRANDING_SOURCE_PARTIAL_WRITE_NOT_VISIBLE");
    dsl.execute(
        "INSERT INTO game_design_branding_source_snapshot "
            + "(canonical_tenant_id, canonical_version_id, request_id, commit_id, snapshot_json, snapshot_digest) VALUES (?, ?, ?, ?, ?, ?)",
        binding.target().canonicalTenantId(),
        binding.target().canonicalVersionId(),
        binding.requestId(),
        binding.commitId(),
        snapshot.canonicalJson(),
        snapshot.digest());
    dsl.execute(
        "UPDATE game_design_branding_source_head SET visible_commit_id = ? WHERE canonical_tenant_id = ? AND canonical_version_id = ?",
        binding.commitId(),
        binding.target().canonicalTenantId(),
        binding.target().canonicalVersionId());
    return readSnapshot(binding.target(), binding.commitId()).orElseThrow();
  }

  public Optional<BrandingSourceSnapshot> readSnapshot(TargetProof target, UUID commitId) {
    var row =
        dsl.fetchOne(
            "SELECT snapshot_json, snapshot_digest FROM game_design_branding_source_snapshot WHERE canonical_tenant_id = ? AND canonical_version_id = ? AND commit_id = ?",
            target.canonicalTenantId(),
            target.canonicalVersionId(),
            commitId);
    if (row == null) return Optional.empty();
    var snapshot = BrandingSourceSnapshot.fromStored(row.get("snapshot_json", String.class));
    if (!snapshot.binding().target().equals(target)
        || !snapshot.binding().commitId().equals(commitId)
        || !snapshot.digest().equals(row.get("snapshot_digest", String.class)))
      throw new IllegalStateException("BRANDING_SOURCE_SNAPSHOT_READBACK_CONFLICT");
    exact(snapshot.binding());
    var fence =
        dsl.fetchOne(
            "SELECT input_digest FROM game_design_draft_commit_visibility_fence WHERE canonical_tenant_id = ? AND canonical_version_id = ? AND request_id = ? AND commit_id = ?",
            target.canonicalTenantId(),
            target.canonicalVersionId(),
            snapshot.binding().requestId(),
            commitId);
    if (fence == null || !snapshot.binding().digest().equals(fence.get(0, String.class))) {
      throw new IllegalStateException("BRANDING_SOURCE_SNAPSHOT_FENCE_CONFLICT");
    }
    verifyItems(snapshot);
    return Optional.of(snapshot);
  }

  public BrandingSourceSnapshot.Capture freeze(GameDesignPublicationOperation operation) {
    write();
    var selection = operation.account().input().selection();
    lock(selection.target());
    var prior = readCapture(operation);
    if (prior.isPresent()) return prior.orElseThrow();
    var retained =
        new GameDesignPublicationOperationRepository(dsl)
            .read(operation.workflowId())
            .orElseThrow();
    GameDesignPublicationOperationRepository.exact(retained.operation(), operation);
    if (!"PENDING".equals(retained.outcome()))
      throw new IllegalStateException("BRANDING_SOURCE_OPERATION_SEALED");
    requireFence(selection.selectedCommit());
    var selected =
        dsl.fetchOne(
            "SELECT selection_json FROM game_design_authored_draft_publish_selection WHERE canonical_tenant_id = ? AND canonical_version_id = ?",
            selection.target().canonicalTenantId(),
            selection.target().canonicalVersionId());
    if (selected == null || !selection.canonicalJson().equals(selected.get(0, String.class)))
      throw new IllegalStateException("BRANDING_SOURCE_SELECTION_CHANGED");
    var snapshot =
        readSnapshot(selection.target(), selection.selectedCommit().commitId()).orElseThrow();
    var head = head(selection.target());
    if (!snapshot.binding().equals(selection.selectedCommit())
        || !snapshot.sourceEpoch().equals(head.get("source_epoch", String.class))
        || !snapshot.binding().commitId().equals(head.get("visible_commit_id", UUID.class)))
      throw new IllegalStateException("BRANDING_SOURCE_NOT_SYNCHRONIZED");
    var capture = new BrandingSourceSnapshot.Capture(operation, snapshot);
    dsl.execute(
        "INSERT INTO game_design_branding_source_capture (canonical_tenant_id, canonical_version_id, publish_workflow_id, commit_id, operation_bytes, capture_bytes, capture_digest) VALUES (?, ?, ?, ?, ?, ?, ?)",
        selection.target().canonicalTenantId(),
        selection.target().canonicalVersionId(),
        operation.workflowId(),
        snapshot.binding().commitId(),
        operation.canonicalBytes(),
        capture.canonicalBytes(),
        capture.digest());
    return readCapture(operation).orElseThrow();
  }

  public Optional<BrandingSourceSnapshot.Capture> readCapture(
      GameDesignPublicationOperation operation) {
    var target = operation.account().input().selection().target();
    var row =
        dsl.fetchOne(
            "SELECT * FROM game_design_branding_source_capture WHERE canonical_tenant_id = ? AND canonical_version_id = ?",
            target.canonicalTenantId(),
            target.canonicalVersionId());
    if (row == null) return Optional.empty();
    var capture =
        new BrandingSourceSnapshot.Capture(
            operation, readSnapshot(target, row.get("commit_id", UUID.class)).orElseThrow());
    if (!operation.workflowId().equals(row.get("publish_workflow_id", String.class))
        || !Arrays.equals(operation.canonicalBytes(), row.get("operation_bytes", byte[].class))
        || !Arrays.equals(capture.canonicalBytes(), row.get("capture_bytes", byte[].class))
        || !capture.digest().equals(row.get("capture_digest", String.class)))
      throw new IllegalStateException("BRANDING_SOURCE_CAPTURE_READBACK_CONFLICT");
    return Optional.of(capture);
  }

  private Optional<BrandingSourceApplication> application(DraftCommitBinding binding) {
    var row =
        dsl.fetchOne(
            "SELECT * FROM game_design_branding_source_application WHERE canonical_tenant_id = ? AND canonical_version_id = ? AND commit_id = ?",
            binding.target().canonicalTenantId(),
            binding.target().canonicalVersionId(),
            binding.commitId());
    if (row == null) return Optional.empty();
    var result =
        new BrandingSourceApplication(
            binding,
            row.get("expected_epoch", String.class),
            BrandingSourceSnapshot.fromStored(row.get("snapshot_json", String.class)));
    if (!Arrays.equals(result.canonicalBytes(), row.get("result_bytes", byte[].class))
        || !binding.requestId().equals(row.get("request_id", UUID.class)))
      throw new IllegalStateException("BRANDING_SOURCE_APPLICATION_READBACK_CONFLICT");
    verifyItems(result.snapshot());
    return Optional.of(result);
  }

  private net.firedevops.firemud.common.gamedesign.BrandingSourceSnapshot.Item qualify(
      BrandingSource.Reference reference, boolean lock) {
    var target = reference.sourceBinding().target();
    var row =
        dsl.fetchOne(
            "SELECT file_name, content_type, data FROM game_assets WHERE tenant_id = ? AND id = ?"
                + (lock ? " FOR UPDATE" : ""),
            target.gameDesignVersionTenantKey(),
            Long.parseLong(reference.assetRowId()));
    if (row == null
        || !reference.usageKey().equals(row.get("file_name", String.class))
        || row.get("data", byte[].class) == null)
      throw new IllegalStateException("BRANDING_SOURCE_EXACT_BYTES_UNAVAILABLE");
    byte[] bytes = row.get("data", byte[].class);
    return new net.firedevops.firemud.common.gamedesign.BrandingSourceSnapshot.Item(
        reference,
        row.get("content_type", String.class),
        CommandSource.sha256(bytes),
        bytes.length);
  }

  private void verifyItems(BrandingSourceSnapshot snapshot) {
    for (var item : snapshot.items()) {
      if (!item.equals(qualify(item.reference(), false)))
        throw new IllegalStateException("BRANDING_SOURCE_BYTE_READBACK_CONFLICT");
      var r = item.reference();
      var row =
          dsl.fetchOne(
              "SELECT content_digest, content_type, byte_size FROM game_design_branding_source_revision "
                  + "WHERE canonical_tenant_id = ? AND canonical_version_id = ? AND revision_id = ? AND commit_id = ? AND request_id = ? AND revision_order = ? AND asset_id = ? AND usage_key = ? AND role = ? AND requiredness = ?",
              snapshot.binding().target().canonicalTenantId(),
              snapshot.binding().target().canonicalVersionId(),
              r.revisionId(),
              r.sourceBinding().commitId(),
              r.sourceBinding().requestId(),
              Integer.parseInt(r.revisionOrder()),
              Long.parseLong(r.assetRowId()),
              r.usageKey(),
              r.role().name(),
              r.requiredness().name());
      if (row == null
          || !item.contentDigest().equals(row.get("content_digest", String.class))
          || !item.contentType().equals(row.get("content_type", String.class))
          || item.byteSize() != row.get("byte_size", Long.class))
        throw new IllegalStateException("BRANDING_SOURCE_REVISION_READBACK_CONFLICT");
    }
  }

  private Record head(TargetProof target) {
    return readHead(target, true);
  }

  private Record readHead(TargetProof target, boolean lock) {
    readGenesis(target)
        .orElseThrow(() -> new IllegalStateException("BRANDING_SOURCE_GENESIS_UNAVAILABLE"));
    return Objects.requireNonNull(
        dsl.fetchOne(
            "SELECT * FROM game_design_branding_source_head WHERE canonical_tenant_id = ? AND canonical_version_id = ?"
                + (lock ? " FOR UPDATE" : ""),
            target.canonicalTenantId(),
            target.canonicalVersionId()),
        "BRANDING_SOURCE_HEAD_UNAVAILABLE");
  }

  private void exact(DraftCommitBinding binding) {
    if (!coordinator
        .read(binding.target(), binding.requestId())
        .orElseThrow()
        .binding()
        .equals(binding)) throw new IllegalStateException("BRANDING_SOURCE_BINDING_CHANGED");
  }

  private void requirePreviousFence(TargetProof target, UUID inherited) {
    var fence = coordinator.readVisibilityFence(target);
    if (inherited == null
        ? fence.isPresent()
        : fence.isEmpty() || !inherited.equals(fence.orElseThrow().commitId()))
      throw new IllegalStateException("BRANDING_SOURCE_PREVIOUS_FENCE_CHANGED");
  }

  private void requireFence(DraftCommitBinding binding) {
    var fence = coordinator.readVisibilityFence(binding.target()).orElseThrow();
    if (!binding.commitId().equals(fence.commitId())
        || !binding.requestId().equals(fence.requestId())
        || !binding.digest().equals(fence.inputDigest())
        || coordinator.read(binding.target(), binding.requestId()).orElseThrow().workflowState()
            != DraftCommitCoordinatorRepository.WorkflowState.SYNCHRONIZED)
      throw new IllegalStateException("BRANDING_SOURCE_SYNCHRONIZED_FENCE_UNAVAILABLE");
  }

  private void open(TargetProof target) {
    if (dsl.fetchOne(
                "SELECT id FROM version WHERE id = ? AND version_state = 'DRAFT' AND NOT is_script_only AND base_version_id IS NULL AND script_patch_version IS NULL",
                target.gameDesignVersionRowId())
            == null
        || dsl.fetchOne(
                "SELECT 1 FROM game_design_authored_draft_publish_selection WHERE canonical_tenant_id = ? AND canonical_version_id = ?",
                target.canonicalTenantId(),
                target.canonicalVersionId())
            != null
        || dsl.fetchOne(
                "SELECT 1 FROM version_asset_export_snapshot WHERE tenant_id = ? AND version_id = ?",
                target.gameDesignVersionTenantKey(),
                target.gameDesignVersionRowId())
            != null) throw new IllegalStateException("BRANDING_SOURCE_FROZEN_OR_NOT_DRAFT");
  }

  private void lock(TargetProof target) {
    var qualified =
        new net.firedevops.firemud.gamedesign.repository.VersionRepository(dsl)
            .findByCanonicalTenantIdAndCanonicalVersionId(
                target.canonicalTenantId(), target.canonicalVersionId())
            .orElseThrow();
    var version =
        new net.firedevops.firemud.gamedesign.repository.VersionRepository(dsl)
            .findByTenantIdAndIdForUpdate(
                target.gameDesignVersionTenantKey(), target.gameDesignVersionRowId())
            .orElseThrow();
    var actual =
        new TargetProof(
            version.getCanonicalTenantId(),
            version.getCanonicalVersionId(),
            version.getId(),
            version.getTenantId(),
            version.getIdentitySourceGameRowId(),
            version.getIdentitySourceGameTenantKey(),
            version.getIdentitySourceProvenanceKind());
    if (!target.equals(actual) || !qualified.getId().equals(version.getId()))
      throw new IllegalStateException("BRANDING_SOURCE_TARGET_CHANGED");
  }

  static boolean isScope(DraftCommitBinding.AffectedUnit unit) {
    return BrandingSource.SCOPE.equals(unit.aggregateType())
        && BrandingSource.SCOPE.equals(unit.scopeType())
        && BrandingSource.SCOPE_ID.equals(unit.scopeId());
  }

  private static void write() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        || !Integer.valueOf(TransactionDefinition.ISOLATION_READ_COMMITTED)
            .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))
      throw new IllegalStateException(
          "Branding asset source requires writable READ_COMMITTED transaction");
  }
}
