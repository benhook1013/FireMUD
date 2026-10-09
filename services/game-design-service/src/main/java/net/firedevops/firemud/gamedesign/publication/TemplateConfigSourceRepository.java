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
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Actual coordinated author history. Account mutation authority must be supplied by its owner. */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Owner-local persistence collaborator")
public final class TemplateConfigSourceRepository {
  private final DSLContext dsl;
  private final DraftCommitCoordinatorRepository coordinator;
  private final TemplateReferenceRepository templateReferences;

  public TemplateConfigSourceRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl);
    coordinator = new DraftCommitCoordinatorRepository(dsl);
    templateReferences = new TemplateReferenceRepository(dsl);
  }

  public TemplateConfigSourceSnapshot.Genesis enrollFreshDraft(TargetProof target) {
    write();
    lock(target);
    var existing = readGenesis(target);
    if (existing.isPresent()) return existing.orElseThrow();
    Record witness =
        dsl.fetchOne(
            "SELECT creation_transaction_id FROM game_design_realm_policy_version_insert "
                + "WHERE version_id = ? AND creation_transaction_id = pg_current_xact_id()::TEXT",
            target.gameDesignVersionRowId());
    if (witness == null)
      throw new IllegalStateException("TEMPLATE_CONFIG_FRESH_INSERT_UNAVAILABLE");
    var result =
        new TemplateConfigSourceSnapshot.Genesis(
            target, UUID.randomUUID(), witness.get(0, String.class));
    dsl.execute(
        "INSERT INTO game_design_template_config_source_genesis (canonical_tenant_id, canonical_version_id, "
            + "version_id, receipt_id, creation_transaction_id, target_json) VALUES (?, ?, ?, ?, ?, ?)",
        target.canonicalTenantId(),
        target.canonicalVersionId(),
        target.gameDesignVersionRowId(),
        result.receiptId(),
        result.creationTransactionId(),
        CommandSource.canonical(CommandSource.targetObject(target)));
    dsl.execute(
        "INSERT INTO game_design_template_config_source_head (canonical_tenant_id, canonical_version_id, source_epoch, genesis_receipt_id) "
            + "VALUES (?, ?, '0', ?)",
        target.canonicalTenantId(),
        target.canonicalVersionId(),
        result.receiptId());
    if (!result.equals(readGenesis(target).orElseThrow()))
      throw new IllegalStateException("TEMPLATE_CONFIG_GENESIS_READBACK_CONFLICT");
    return result;
  }

  public Optional<TemplateConfigSourceSnapshot.Genesis> readGenesis(TargetProof target) {
    var row =
        dsl.fetchOne(
            "SELECT * FROM game_design_template_config_source_genesis WHERE canonical_tenant_id = ? AND canonical_version_id = ?",
            target.canonicalTenantId(),
            target.canonicalVersionId());
    if (row == null) return Optional.empty();
    if (!CommandSource.canonical(CommandSource.targetObject(target))
            .equals(row.get("target_json", String.class))
        || target.gameDesignVersionRowId() != row.get("version_id", Long.class))
      throw new IllegalStateException("TEMPLATE_CONFIG_GENESIS_TARGET_CONFLICT");
    return Optional.of(
        new TemplateConfigSourceSnapshot.Genesis(
            target,
            row.get("receipt_id", UUID.class),
            row.get("creation_transaction_id", String.class)));
  }

  public Optional<TemplateConfigSourceSnapshot.Application> apply(DraftCommitBinding binding) {
    write();
    lock(binding.target());
    exact(binding);
    var prior = application(binding);
    if (prior.isPresent()) return prior;
    var mutations = TemplateConfigSource.mutations(binding);
    if (mutations.isEmpty()) return Optional.empty();
    open(binding.target());
    var slot = coordinator.readApplicationSlot(binding.target()).orElseThrow();
    var owner =
        coordinator
            .read(binding.target(), binding.requestId())
            .orElseThrow()
            .ownerStates()
            .get(Owner.GAME_DESIGN_CONTROL_PLANE);
    if (!slot.commitId().equals(binding.commitId())
        || !slot.requestId().equals(binding.requestId())
        || owner == null
        || owner.status() != DraftCommitCoordinatorRepository.OwnerStatus.IN_PROGRESS
            && owner.status() != DraftCommitCoordinatorRepository.OwnerStatus.UNKNOWN)
      throw new IllegalStateException("TEMPLATE_CONFIG_COORDINATOR_APPLICATION_UNAVAILABLE");
    Record head = head(binding.target());
    String epoch = head.get("source_epoch", String.class);
    var units =
        binding.affectedUnits(Owner.GAME_DESIGN_CONTROL_PLANE).stream()
            .filter(TemplateConfigSource::isScope)
            .toList();
    if (units.size() != 1
        || !units.getFirst().aggregateId().equals(binding.target().canonicalVersionId().toString())
        || !epoch.equals(units.getFirst().expectedEpoch()))
      throw new IllegalStateException("TEMPLATE_CONFIG_EPOCH_CONFLICT");
    UUID inherited = head.get("visible_commit_id", UUID.class);
    var fence = coordinator.readVisibilityFence(binding.target());
    if (inherited == null
        ? fence.isPresent() || !"0".equals(epoch)
        : fence.isEmpty() || !inherited.equals(fence.orElseThrow().commitId()))
      throw new IllegalStateException("TEMPLATE_CONFIG_PREVIOUS_FENCE_CHANGED");
    var previousSnapshot =
        inherited == null
            ? Optional.<TemplateConfigSourceSnapshot>empty()
            : Optional.of(readSnapshot(binding.target(), inherited).orElseThrow());
    List<TemplateConfigSource.Entry> previous =
        previousSnapshot.map(TemplateConfigSourceSnapshot::entries).orElseGet(List::of);
    List<TemplateConfigOwnerSourceInventoryDeclaration> previousDeclarations =
        previousSnapshot
            .map(TemplateConfigSourceSnapshot::ownerSourceInventoryDeclarations)
            .orElseGet(List::of);
    var createdRows = new java.util.HashMap<UUID, String>();
    for (var mutation : mutations) {
      if (mutation.operation()
          == TemplateConfigSource.OperationKind.DECLARE_OWNER_SOURCE_INVENTORY) {
        dsl.execute(
            "INSERT INTO game_design_template_config_owner_source_inventory_declaration "
                + "(canonical_tenant_id, canonical_version_id, request_id, commit_id, revision_id, revision_order, owner, inventory_json, payload_json) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            binding.target().canonicalTenantId(),
            binding.target().canonicalVersionId(),
            binding.requestId(),
            binding.commitId(),
            mutation.revisionId(),
            Integer.parseInt(mutation.revisionOrder()),
            mutation.declaredOwner().name(),
            mutation.inventoryJson(),
            mutation.payload());
        continue;
      }
      if (mutation.config() != null) {
        mutation.config().requireAvailableOwnerReads();
        requireGameplayInputs(binding, mutation.config());
      }
      String templateId = mutation.templateId();
      if (mutation.operation() == TemplateConfigSource.OperationKind.CREATE) {
        var row =
            dsl.fetchOne(
                "INSERT INTO game_templates (tenant_id, name, config, default_version_id, template_reference_phase) "
                    + "VALUES (?, ?, CAST(? AS JSONB), ?, 'LEGACY') RETURNING id",
                binding.target().gameDesignVersionTenantKey(),
                mutation.templateName(),
                mutation.config().canonicalJson(),
                binding.target().gameDesignVersionRowId());
        templateId = Objects.requireNonNull(row).get("id", Long.class).toString();
        createdRows.put(mutation.revisionId(), templateId);
        dsl.execute(
            "INSERT INTO game_design_template_config_source_qualification "
                + "(template_id, canonical_tenant_id, canonical_version_id, request_id, commit_id, revision_id, revision_order) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)",
            Long.parseLong(templateId),
            binding.target().canonicalTenantId(),
            binding.target().canonicalVersionId(),
            binding.requestId(),
            binding.commitId(),
            mutation.revisionId(),
            Integer.parseInt(mutation.revisionOrder()));
      } else {
        requireQualified(binding.target(), templateId, true);
        if (mutation.operation() == TemplateConfigSource.OperationKind.UPSERT)
          dsl.execute(
              "UPDATE game_templates SET config = CAST(? AS JSONB) WHERE id = ? AND tenant_id = ?",
              mutation.config().canonicalJson(),
              Long.parseLong(templateId),
              binding.target().gameDesignVersionTenantKey());
      }
      dsl.execute(
          "INSERT INTO game_design_template_config_source_revision "
              + "(canonical_tenant_id, canonical_version_id, request_id, commit_id, revision_id, revision_order, operation_kind, template_id, payload_json) "
              + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
          binding.target().canonicalTenantId(),
          binding.target().canonicalVersionId(),
          binding.requestId(),
          binding.commitId(),
          mutation.revisionId(),
          Integer.parseInt(mutation.revisionOrder()),
          mutation.operation().name(),
          Long.parseLong(templateId),
          mutation.payload());
      if (mutation.config() != null) {
        dsl.execute(
            "INSERT INTO game_design_template_config_source_ref "
                + "(canonical_tenant_id, canonical_version_id, revision_id, ref_kind, ref_key, referenced_revision_id) "
                + "VALUES (?, ?, ?, 'BASE_VERSION', ?, NULL)",
            binding.target().canonicalTenantId(),
            binding.target().canonicalVersionId(),
            mutation.revisionId(),
            mutation.config().baseVersionId().toString());
        for (var input : mutation.config().gameplayInputs())
          dsl.execute(
              "INSERT INTO game_design_template_config_source_ref "
                  + "(canonical_tenant_id, canonical_version_id, revision_id, ref_kind, ref_key, referenced_revision_id) "
                  + "VALUES (?, ?, ?, ?, ?, ?)",
              binding.target().canonicalTenantId(),
              binding.target().canonicalVersionId(),
              mutation.revisionId(),
              input.family().name(),
              input.key(),
              input.revisionId());
      }
    }
    var snapshot =
        new TemplateConfigSourceSnapshot(
            binding,
            new BigInteger(epoch).add(BigInteger.ONE).toString(),
            inherited,
            head.get("genesis_receipt_id", UUID.class),
            TemplateConfigSource.replay(previous, binding, createdRows),
            TemplateConfigSource.replayOwnerInventoryDeclarations(previousDeclarations, binding));
    var result = new TemplateConfigSourceSnapshot.Application(binding, epoch, snapshot);
    dsl.execute(
        "INSERT INTO game_design_template_config_source_application (canonical_tenant_id, canonical_version_id, request_id, commit_id, "
            + "expected_epoch, snapshot_json, result_bytes) VALUES (?, ?, ?, ?, ?, ?, ?)",
        binding.target().canonicalTenantId(),
        binding.target().canonicalVersionId(),
        binding.requestId(),
        binding.commitId(),
        epoch,
        snapshot.canonicalJson(),
        result.canonicalBytes());
    if (dsl.execute(
            "UPDATE game_design_template_config_source_head SET source_epoch = ?, applied_commit_id = ? "
                + "WHERE canonical_tenant_id = ? AND canonical_version_id = ? AND source_epoch = ? AND visible_commit_id IS NOT DISTINCT FROM CAST(? AS UUID)",
            snapshot.sourceEpoch(),
            binding.commitId(),
            binding.target().canonicalTenantId(),
            binding.target().canonicalVersionId(),
            epoch,
            inherited)
        != 1) throw new IllegalStateException("TEMPLATE_CONFIG_EPOCH_CONFLICT");
    templateReferences.synchronizeCurrentProjection(
        binding.target(),
        binding,
        snapshot,
        mutations.stream().filter(TemplateConfigSource.Mutation::changesTemplateRow).toList(),
        createdRows);
    return Optional.of(application(binding).orElseThrow());
  }

  public TemplateConfigSourceSnapshot captureSynchronized(DraftCommitBinding binding) {
    write();
    lock(binding.target());
    exact(binding);
    var existing = readSnapshot(binding.target(), binding.commitId());
    if (existing.isPresent()) {
      if (!existing.orElseThrow().binding().equals(binding))
        throw new IllegalStateException("TEMPLATE_CONFIG_SNAPSHOT_BINDING_CONFLICT");
      return existing.orElseThrow();
    }
    open(binding.target());
    requireFence(binding);
    var head = head(binding.target());
    UUID inherited = head.get("visible_commit_id", UUID.class);
    var applied = application(binding);
    TemplateConfigSourceSnapshot snapshot;
    if (!TemplateConfigSource.mutations(binding).isEmpty()) {
      snapshot = applied.orElseThrow().snapshot();
      if (!Objects.equals(inherited, snapshot.inheritedCommitId()))
        throw new IllegalStateException("TEMPLATE_CONFIG_PREDECESSOR_CHANGED");
    } else {
      if (applied.isPresent())
        throw new IllegalStateException("TEMPLATE_CONFIG_UNDECLARED_APPLICATION");
      var inheritedSnapshot =
          inherited == null
              ? Optional.<TemplateConfigSourceSnapshot>empty()
              : Optional.of(readSnapshot(binding.target(), inherited).orElseThrow());
      var entries =
          inheritedSnapshot.map(TemplateConfigSourceSnapshot::entries).orElseGet(List::of);
      var declarations =
          inheritedSnapshot
              .map(TemplateConfigSourceSnapshot::ownerSourceInventoryDeclarations)
              .orElseGet(List::of);
      if (inherited == null && !"0".equals(head.get("source_epoch", String.class)))
        throw new IllegalStateException("TEMPLATE_CONFIG_GENESIS_NOT_CURRENT");
      snapshot =
          new TemplateConfigSourceSnapshot(
              binding,
              head.get("source_epoch", String.class),
              inherited,
              head.get("genesis_receipt_id", UUID.class),
              entries,
              declarations);
    }
    requireSelectedReferences(snapshot);
    requireCurrentInventory(snapshot);
    if (!snapshot.sourceEpoch().equals(head.get("source_epoch", String.class)))
      throw new IllegalStateException("TEMPLATE_CONFIG_PARTIAL_WRITE_NOT_VISIBLE");
    dsl.execute(
        "INSERT INTO game_design_template_config_source_snapshot (canonical_tenant_id, canonical_version_id, request_id, commit_id, "
            + "snapshot_json, snapshot_digest) VALUES (?, ?, ?, ?, ?, ?)",
        binding.target().canonicalTenantId(),
        binding.target().canonicalVersionId(),
        binding.requestId(),
        binding.commitId(),
        snapshot.canonicalJson(),
        snapshot.digest());
    if (dsl.execute(
            "UPDATE game_design_template_config_source_head SET visible_commit_id = ? WHERE canonical_tenant_id = ? AND canonical_version_id = ? "
                + "AND visible_commit_id IS NOT DISTINCT FROM CAST(? AS UUID) AND source_epoch = ?",
            binding.commitId(),
            binding.target().canonicalTenantId(),
            binding.target().canonicalVersionId(),
            inherited,
            snapshot.sourceEpoch())
        != 1) throw new IllegalStateException("TEMPLATE_CONFIG_VISIBILITY_CONFLICT");
    return readSnapshot(binding.target(), binding.commitId()).orElseThrow();
  }

  public Optional<TemplateConfigSourceSnapshot> readSnapshot(TargetProof target, UUID commitId) {
    var row =
        dsl.fetchOne(
            "SELECT * FROM game_design_template_config_source_snapshot WHERE canonical_tenant_id = ? AND canonical_version_id = ? AND commit_id = ?",
            target.canonicalTenantId(),
            target.canonicalVersionId(),
            commitId);
    if (row == null) return Optional.empty();
    var snapshot = TemplateConfigSourceSnapshot.fromStored(row.get("snapshot_json", String.class));
    if (!snapshot.binding().target().equals(target)
        || !snapshot.binding().commitId().equals(commitId)
        || !snapshot.binding().requestId().equals(row.get("request_id", UUID.class))
        || !snapshot.digest().equals(row.get("snapshot_digest", String.class)))
      throw new IllegalStateException("TEMPLATE_CONFIG_SNAPSHOT_READBACK_CONFLICT");
    exact(snapshot.binding());
    var genesis = readGenesis(target).orElseThrow();
    if (!snapshot.genesisReceiptId().equals(genesis.receiptId()))
      throw new IllegalStateException("TEMPLATE_CONFIG_GENESIS_CHANGED");
    var fence =
        dsl.fetchOne(
            "SELECT input_digest FROM game_design_draft_commit_visibility_fence WHERE canonical_tenant_id = ? "
                + "AND canonical_version_id = ? AND request_id = ? AND commit_id = ?",
            target.canonicalTenantId(),
            target.canonicalVersionId(),
            snapshot.binding().requestId(),
            commitId);
    if (fence == null || !snapshot.binding().digest().equals(fence.get(0, String.class)))
      throw new IllegalStateException("TEMPLATE_CONFIG_SNAPSHOT_FENCE_CONFLICT");
    verifyEntries(snapshot);
    verifyOwnerInventoryDeclarations(snapshot);
    requireSelectedReferences(snapshot);
    return Optional.of(snapshot);
  }

  public TemplateConfigSourceSnapshot.Capture freeze(GameDesignPublicationOperation operation) {
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
      throw new IllegalStateException("TEMPLATE_CONFIG_OPERATION_SEALED");
    requireFence(selection.selectedCommit());
    var selected =
        dsl.fetchOne(
            "SELECT selection_json FROM game_design_authored_draft_publish_selection WHERE canonical_tenant_id = ? AND canonical_version_id = ?",
            selection.target().canonicalTenantId(),
            selection.target().canonicalVersionId());
    if (selected == null || !selection.canonicalJson().equals(selected.get(0, String.class)))
      throw new IllegalStateException("TEMPLATE_CONFIG_SELECTION_CHANGED");
    var snapshot =
        readSnapshot(selection.target(), selection.selectedCommit().commitId()).orElseThrow();
    var head = head(selection.target());
    if (!snapshot.binding().equals(selection.selectedCommit())
        || !snapshot.sourceEpoch().equals(head.get("source_epoch", String.class))
        || !snapshot.binding().commitId().equals(head.get("visible_commit_id", UUID.class)))
      throw new IllegalStateException("TEMPLATE_CONFIG_NOT_SYNCHRONIZED");
    requireSelectedReferences(snapshot);
    requireCurrentInventory(snapshot);
    for (var entry : snapshot.entries()) {
      var row =
          dsl.fetchOne(
              "SELECT config::TEXT, default_version_id, default_script_patch_version, default_runtime_flags_json "
                  + "FROM game_templates WHERE id = ? AND tenant_id = ? FOR UPDATE",
              Long.parseLong(entry.templateId()),
              selection.target().gameDesignVersionTenantKey());
      if (row == null
          || !entry
              .config()
              .canonicalJson()
              .equals(CommandSource.canonical(CommandSource.tree(row.get(0, String.class))))
          || !Long.valueOf(selection.target().gameDesignVersionRowId())
              .equals(row.get(1, Long.class))
          || row.get(2, String.class) != null
          || !"{}".equals(CommandSource.canonical(CommandSource.tree(row.get(3, String.class)))))
        throw new IllegalStateException("TEMPLATE_CONFIG_LIVE_ROW_DIFFERS_FROM_SELECTED_SOURCE");
    }
    var capture = new TemplateConfigSourceSnapshot.Capture(operation, snapshot);
    dsl.execute(
        "INSERT INTO game_design_template_config_source_capture (canonical_tenant_id, canonical_version_id, publish_workflow_id, commit_id, operation_bytes, capture_bytes, capture_digest) VALUES (?, ?, ?, ?, ?, ?, ?)",
        selection.target().canonicalTenantId(),
        selection.target().canonicalVersionId(),
        operation.workflowId(),
        snapshot.binding().commitId(),
        operation.canonicalBytes(),
        capture.canonicalBytes(),
        capture.digest());
    return readCapture(operation).orElseThrow();
  }

  public Optional<TemplateConfigSourceSnapshot.Capture> readCapture(
      GameDesignPublicationOperation operation) {
    var target = operation.account().input().selection().target();
    var row =
        dsl.fetchOne(
            "SELECT * FROM game_design_template_config_source_capture WHERE canonical_tenant_id = ? AND canonical_version_id = ?",
            target.canonicalTenantId(),
            target.canonicalVersionId());
    if (row == null) return Optional.empty();
    var capture =
        new TemplateConfigSourceSnapshot.Capture(
            operation, readSnapshot(target, row.get("commit_id", UUID.class)).orElseThrow());
    if (!operation.workflowId().equals(row.get("publish_workflow_id", String.class))
        || !Arrays.equals(operation.canonicalBytes(), row.get("operation_bytes", byte[].class))
        || !Arrays.equals(capture.canonicalBytes(), row.get("capture_bytes", byte[].class))
        || !capture.digest().equals(row.get("capture_digest", String.class)))
      throw new IllegalStateException("TEMPLATE_CONFIG_CAPTURE_READBACK_CONFLICT");
    return Optional.of(capture);
  }

  private void requireCurrentInventory(TemplateConfigSourceSnapshot snapshot) {
    var target = snapshot.binding().target();
    Boolean complete =
        dsl.fetchSingle(
                "SELECT template_config_current_inventory(?, ?, ?, ?, CAST(? AS JSONB))",
                target.canonicalTenantId(),
                target.canonicalVersionId(),
                target.gameDesignVersionRowId(),
                target.gameDesignVersionTenantKey(),
                snapshot.canonicalJson())
            .get(0, Boolean.class);
    if (!Boolean.TRUE.equals(complete))
      throw new IllegalStateException("TEMPLATE_CONFIG_EXPLICIT_TARGET_INVENTORY_UNQUALIFIED");
  }

  private void requireSelectedReferences(TemplateConfigSourceSnapshot snapshot) {
    var gameplay =
        new GameplayRuleSourceRepository(dsl)
            .readSnapshot(snapshot.binding().target(), snapshot.binding().commitId())
            .orElseThrow();
    if (!snapshot.binding().equals(gameplay.binding()))
      throw new IllegalStateException("TEMPLATE_CONFIG_GAMEPLAY_SELECTION_CHANGED");
    for (var entry : snapshot.entries())
      for (var input : entry.config().gameplayInputs())
        if (gameplay.entries().stream()
            .noneMatch(
                e ->
                    e.family() == input.family()
                        && e.definition().key().equals(input.key())
                        && e.revisionId().equals(input.revisionId())))
          throw new IllegalStateException("TEMPLATE_CONFIG_GAMEPLAY_INPUT_CHANGED");
  }

  private Optional<TemplateConfigSourceSnapshot.Application> application(
      DraftCommitBinding binding) {
    var row =
        dsl.fetchOne(
            "SELECT * FROM game_design_template_config_source_application WHERE canonical_tenant_id = ? AND canonical_version_id = ? AND commit_id = ?",
            binding.target().canonicalTenantId(),
            binding.target().canonicalVersionId(),
            binding.commitId());
    if (row == null) return Optional.empty();
    var result =
        new TemplateConfigSourceSnapshot.Application(
            binding,
            row.get("expected_epoch", String.class),
            TemplateConfigSourceSnapshot.fromStored(row.get("snapshot_json", String.class)));
    if (!binding.requestId().equals(row.get("request_id", UUID.class))
        || !Arrays.equals(result.canonicalBytes(), row.get("result_bytes", byte[].class)))
      throw new IllegalStateException("TEMPLATE_CONFIG_APPLICATION_READBACK_CONFLICT");
    var stored =
        dsl.fetch(
                "SELECT payload_json FROM game_design_template_config_source_revision WHERE canonical_tenant_id = ? AND canonical_version_id = ? "
                    + "AND commit_id = ? ORDER BY revision_order",
                binding.target().canonicalTenantId(),
                binding.target().canonicalVersionId(),
                binding.commitId())
            .getValues("payload_json", String.class);
    if (!stored.equals(
        TemplateConfigSource.mutations(binding).stream()
            .filter(TemplateConfigSource.Mutation::changesTemplateRow)
            .map(TemplateConfigSource.Mutation::payload)
            .toList()))
      throw new IllegalStateException("TEMPLATE_CONFIG_REVISION_READBACK_CONFLICT");
    verifyOwnerInventoryApplication(binding);
    verifyEntries(result.snapshot());
    verifyOwnerInventoryDeclarations(result.snapshot());
    return Optional.of(result);
  }

  private void verifyOwnerInventoryApplication(DraftCommitBinding binding) {
    var expected =
        TemplateConfigSource.mutations(binding).stream()
            .filter(
                mutation ->
                    mutation.operation()
                        == TemplateConfigSource.OperationKind.DECLARE_OWNER_SOURCE_INVENTORY)
            .toList();
    var rows =
        dsl.fetch(
            "SELECT revision_id, revision_order, owner, inventory_json, payload_json "
                + "FROM game_design_template_config_owner_source_inventory_declaration "
                + "WHERE canonical_tenant_id = ? AND canonical_version_id = ? AND request_id = ? AND commit_id = ? "
                + "ORDER BY revision_order",
            binding.target().canonicalTenantId(),
            binding.target().canonicalVersionId(),
            binding.requestId(),
            binding.commitId());
    if (rows.size() != expected.size())
      throw new IllegalStateException("TEMPLATE_CONFIG_OWNER_INVENTORY_READBACK_CONFLICT");
    for (int i = 0; i < expected.size(); i++) {
      var mutation = expected.get(i);
      var row = rows.get(i);
      if (!mutation.revisionId().equals(row.get("revision_id", UUID.class))
          || Integer.parseInt(mutation.revisionOrder()) != row.get("revision_order", Integer.class)
          || !mutation.declaredOwner().name().equals(row.get("owner", String.class))
          || !mutation.inventoryJson().equals(row.get("inventory_json", String.class))
          || !mutation.payload().equals(row.get("payload_json", String.class)))
        throw new IllegalStateException("TEMPLATE_CONFIG_OWNER_INVENTORY_READBACK_CONFLICT");
    }
  }

  private void verifyOwnerInventoryDeclarations(TemplateConfigSourceSnapshot snapshot) {
    var target = snapshot.binding().target();
    var effectiveRows =
        dsl.fetch(
            "SELECT DISTINCT ON (d.owner) d.request_id, d.commit_id, d.revision_id, d.revision_order, "
                + "d.owner, d.inventory_json, d.payload_json, c.binding_json, c.input_digest "
                + "FROM game_design_template_config_owner_source_inventory_declaration d "
                + "JOIN game_design_template_config_source_application a ON a.canonical_tenant_id = d.canonical_tenant_id "
                + "AND a.canonical_version_id = d.canonical_version_id AND a.request_id = d.request_id AND a.commit_id = d.commit_id "
                + "JOIN game_design_draft_commit c ON c.canonical_tenant_id = d.canonical_tenant_id "
                + "AND c.canonical_version_id = d.canonical_version_id AND c.request_id = d.request_id AND c.commit_id = d.commit_id "
                + "WHERE d.canonical_tenant_id = ? AND d.canonical_version_id = ? AND a.expected_epoch::NUMERIC < CAST(? AS NUMERIC) "
                + "ORDER BY d.owner, a.expected_epoch::NUMERIC DESC, d.revision_order DESC",
            target.canonicalTenantId(),
            target.canonicalVersionId(),
            snapshot.sourceEpoch());
    var effectiveDeclarations =
        new java.util.ArrayList<TemplateConfigOwnerSourceInventoryDeclaration>();
    for (var row : effectiveRows) {
      var sourceBinding =
          DraftCommitBinding.fromStored(
              row.get("binding_json", String.class), row.get("input_digest", String.class));
      exact(sourceBinding);
      var mutation =
          TemplateConfigSource.mutations(sourceBinding).stream()
              .filter(m -> m.revisionId().equals(row.get("revision_id", UUID.class)))
              .filter(
                  m ->
                      Integer.parseInt(m.revisionOrder())
                          == row.get("revision_order", Integer.class))
              .filter(
                  m ->
                      m.operation()
                          == TemplateConfigSource.OperationKind.DECLARE_OWNER_SOURCE_INVENTORY)
              .findFirst()
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "TEMPLATE_CONFIG_OWNER_INVENTORY_SOURCE_UNAVAILABLE"));
      if (!sourceBinding.requestId().equals(row.get("request_id", UUID.class))
          || !sourceBinding.commitId().equals(row.get("commit_id", UUID.class))
          || !mutation.declaredOwner().name().equals(row.get("owner", String.class))
          || !mutation.inventoryJson().equals(row.get("inventory_json", String.class))
          || !mutation.payload().equals(row.get("payload_json", String.class)))
        throw new IllegalStateException("TEMPLATE_CONFIG_OWNER_INVENTORY_SOURCE_CHANGED");
      effectiveDeclarations.add(mutation.ownerInventoryDeclaration());
    }
    if (!effectiveDeclarations.equals(snapshot.ownerSourceInventoryDeclarations()))
      throw new IllegalStateException("TEMPLATE_CONFIG_OWNER_INVENTORY_SNAPSHOT_CONFLICT");

    for (var declaration : snapshot.ownerSourceInventoryDeclarations()) {
      exact(declaration.sourceBinding());
      var mutation =
          TemplateConfigSource.mutations(declaration.sourceBinding()).stream()
              .filter(m -> m.revisionId().equals(declaration.revisionId()))
              .filter(m -> m.revisionOrder().equals(declaration.revisionOrder()))
              .filter(
                  m ->
                      m.operation()
                          == TemplateConfigSource.OperationKind.DECLARE_OWNER_SOURCE_INVENTORY)
              .findFirst()
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "TEMPLATE_CONFIG_OWNER_INVENTORY_SOURCE_UNAVAILABLE"));
      var row =
          dsl.fetchOne(
              "SELECT request_id, commit_id, owner, inventory_json, payload_json "
                  + "FROM game_design_template_config_owner_source_inventory_declaration "
                  + "WHERE canonical_tenant_id = ? AND canonical_version_id = ? AND request_id = ? "
                  + "AND commit_id = ? AND revision_id = ? AND revision_order = ? AND owner = ?",
              snapshot.binding().target().canonicalTenantId(),
              snapshot.binding().target().canonicalVersionId(),
              declaration.sourceBinding().requestId(),
              declaration.sourceBinding().commitId(),
              declaration.revisionId(),
              Integer.parseInt(declaration.revisionOrder()),
              declaration.owner().name());
      if (row == null
          || !declaration.sourceBinding().requestId().equals(row.get("request_id", UUID.class))
          || !declaration.sourceBinding().commitId().equals(row.get("commit_id", UUID.class))
          || !declaration.owner().name().equals(row.get("owner", String.class))
          || !declaration.inventoryJson().equals(row.get("inventory_json", String.class))
          || !mutation.payload().equals(row.get("payload_json", String.class)))
        throw new IllegalStateException("TEMPLATE_CONFIG_OWNER_INVENTORY_SOURCE_CHANGED");
    }
    for (var mutation : TemplateConfigSource.mutations(snapshot.binding()))
      if (mutation.operation() == TemplateConfigSource.OperationKind.DECLARE_OWNER_SOURCE_INVENTORY
          && snapshot.ownerSourceInventoryDeclarations().stream()
              .noneMatch(d -> d.revisionId().equals(mutation.revisionId())))
        throw new IllegalStateException("TEMPLATE_CONFIG_OWNER_INVENTORY_SNAPSHOT_MISSING");
  }

  private void verifyEntries(TemplateConfigSourceSnapshot snapshot) {
    for (var entry : snapshot.entries()) {
      exact(entry.sourceBinding());
      requireQualified(snapshot.binding().target(), entry.templateId(), false);
      var mutation =
          new TemplateConfigSource.Mutation(
              entry.sourceBinding(),
              entry.revisionOrder(),
              entry.revisionId(),
              entry.createdName() == null
                  ? TemplateConfigSource.OperationKind.UPSERT
                  : TemplateConfigSource.OperationKind.CREATE,
              entry.createdName() == null ? entry.templateId() : null,
              entry.createdName(),
              entry.config());
      var row =
          dsl.fetchOne(
              "SELECT payload_json FROM game_design_template_config_source_revision WHERE canonical_tenant_id = ? AND canonical_version_id = ? "
                  + "AND request_id = ? AND commit_id = ? AND revision_id = ? AND revision_order = ? AND template_id = ?",
              snapshot.binding().target().canonicalTenantId(),
              snapshot.binding().target().canonicalVersionId(),
              entry.sourceBinding().requestId(),
              entry.sourceBinding().commitId(),
              entry.revisionId(),
              Integer.parseInt(entry.revisionOrder()),
              Long.parseLong(entry.templateId()));
      if (row == null || !mutation.payload().equals(row.get(0, String.class)))
        throw new IllegalStateException("TEMPLATE_CONFIG_ENTRY_PROVENANCE_CHANGED");
      entry.config().requireAvailableOwnerReads();
      verifyNormalizedReferences(snapshot.binding().target(), entry);
    }
  }

  private void requireQualified(TargetProof target, String templateId, boolean lock) {
    var row =
        dsl.fetchOne(
            "SELECT t.id, q.request_id, q.commit_id, q.revision_id, q.revision_order, c.binding_json, c.input_digest "
                + "FROM game_templates t JOIN game_design_template_config_source_qualification q ON q.template_id = t.id "
                + "JOIN game_design_draft_commit c ON c.canonical_tenant_id = q.canonical_tenant_id "
                + "AND c.canonical_version_id = q.canonical_version_id AND c.request_id = q.request_id AND c.commit_id = q.commit_id "
                + "WHERE t.id = ? AND t.tenant_id = ? AND q.canonical_tenant_id = ? AND q.canonical_version_id = ?"
                + (lock ? " FOR UPDATE OF t" : ""),
            Long.parseLong(templateId),
            target.gameDesignVersionTenantKey(),
            target.canonicalTenantId(),
            target.canonicalVersionId());
    if (row == null)
      throw new IllegalStateException("TEMPLATE_CONFIG_TEMPLATE_OWNER_PROVENANCE_UNAVAILABLE");
    var original =
        DraftCommitBinding.fromStored(
            row.get("binding_json", String.class), row.get("input_digest", String.class));
    if (!target.equals(original.target()))
      throw new IllegalStateException("TEMPLATE_CONFIG_TEMPLATE_OWNER_PROVENANCE_CHANGED");
    exact(original);
    var create =
        TemplateConfigSource.mutations(original).stream()
            .filter(m -> m.revisionId().equals(row.get("revision_id", UUID.class)))
            .filter(
                m -> m.revisionOrder().equals(row.get("revision_order", Integer.class).toString()))
            .filter(m -> m.operation() == TemplateConfigSource.OperationKind.CREATE)
            .findFirst()
            .orElseThrow();
    var retained =
        dsl.fetchOne(
            "SELECT payload_json FROM game_design_template_config_source_revision "
                + "WHERE canonical_tenant_id = ? AND canonical_version_id = ? AND revision_id = ? AND template_id = ?",
            target.canonicalTenantId(),
            target.canonicalVersionId(),
            create.revisionId(),
            Long.parseLong(templateId));
    if (retained == null || !create.payload().equals(retained.get(0, String.class)))
      throw new IllegalStateException("TEMPLATE_CONFIG_TEMPLATE_CREATE_READBACK_UNAVAILABLE");
  }

  private void requireGameplayInputs(
      DraftCommitBinding binding, TemplateConfigSource.Config config) {
    var head =
        dsl.fetchOne(
            "SELECT visible_commit_id, applied_commit_id FROM game_design_gameplay_rule_head "
                + "WHERE canonical_tenant_id = ? AND canonical_version_id = ?",
            binding.target().canonicalTenantId(),
            binding.target().canonicalVersionId());
    if (head == null)
      throw new IllegalStateException("TEMPLATE_CONFIG_GAMEPLAY_SOURCE_UNAVAILABLE");
    String json;
    if (binding.commitId().equals(head.get("applied_commit_id", UUID.class))) {
      var row =
          dsl.fetchOne(
              "SELECT snapshot_json FROM game_design_gameplay_rule_application "
                  + "WHERE canonical_tenant_id = ? AND canonical_version_id = ? AND commit_id = ?",
              binding.target().canonicalTenantId(),
              binding.target().canonicalVersionId(),
              binding.commitId());
      json =
          Objects.requireNonNull(row, "TEMPLATE_CONFIG_GAMEPLAY_APPLICATION_UNAVAILABLE")
              .get(0, String.class);
    } else {
      UUID visible = head.get("visible_commit_id", UUID.class);
      var fence = coordinator.readVisibilityFence(binding.target());
      if (visible == null && fence.isEmpty()) {
        new GameplayRuleSourceRepository(dsl).readGenesis(binding.target()).orElseThrow();
        if (!config.gameplayInputs().isEmpty())
          throw new IllegalStateException("TEMPLATE_CONFIG_GAMEPLAY_INPUT_UNAVAILABLE");
        return;
      }
      if (fence.isEmpty() || !Objects.equals(visible, fence.orElseThrow().commitId()))
        throw new IllegalStateException("TEMPLATE_CONFIG_GAMEPLAY_SOURCE_NOT_SYNCHRONIZED");
      json =
          new GameplayRuleSourceRepository(dsl)
              .readSnapshot(binding.target(), visible)
              .orElseThrow()
              .canonicalJson();
    }
    var rules = GameplayRuleSnapshot.fromStored(json);
    for (var input : config.gameplayInputs())
      if (rules.entries().stream()
          .noneMatch(
              e ->
                  e.family() == input.family()
                      && e.definition().key().equals(input.key())
                      && e.revisionId().equals(input.revisionId())))
        throw new IllegalStateException("TEMPLATE_CONFIG_GAMEPLAY_INPUT_UNAVAILABLE");
  }

  private void verifyNormalizedReferences(TargetProof target, TemplateConfigSource.Entry entry) {
    var refs =
        dsl.fetch(
            "SELECT ref_kind, ref_key, referenced_revision_id FROM game_design_template_config_source_ref "
                + "WHERE canonical_tenant_id = ? AND canonical_version_id = ? AND revision_id = ?",
            target.canonicalTenantId(),
            target.canonicalVersionId(),
            entry.revisionId());
    var expected = new java.util.HashSet<String>();
    expected.add("BASE_VERSION:" + entry.config().baseVersionId() + ":");
    for (var input : entry.config().gameplayInputs())
      expected.add(input.family().name() + ":" + input.key() + ":" + input.revisionId());
    var actual = new java.util.HashSet<String>();
    for (var ref : refs)
      actual.add(
          ref.get("ref_kind", String.class)
              + ":"
              + ref.get("ref_key", String.class)
              + ":"
              + (ref.get("referenced_revision_id", UUID.class) == null
                  ? ""
                  : ref.get("referenced_revision_id", UUID.class)));
    if (!expected.equals(actual) || refs.size() != expected.size())
      throw new IllegalStateException("TEMPLATE_CONFIG_NORMALIZED_REFERENCE_READBACK_CONFLICT");
  }

  private Record head(TargetProof target) {
    readGenesis(target)
        .orElseThrow(() -> new IllegalStateException("TEMPLATE_CONFIG_GENESIS_UNAVAILABLE"));
    return Objects.requireNonNull(
        dsl.fetchOne(
            "SELECT * FROM game_design_template_config_source_head WHERE canonical_tenant_id = ? AND canonical_version_id = ? FOR UPDATE",
            target.canonicalTenantId(),
            target.canonicalVersionId()),
        "TEMPLATE_CONFIG_HEAD_UNAVAILABLE");
  }

  private void exact(DraftCommitBinding binding) {
    if (!coordinator
        .read(binding.target(), binding.requestId())
        .orElseThrow()
        .binding()
        .equals(binding)) throw new IllegalStateException("TEMPLATE_CONFIG_BINDING_CHANGED");
  }

  private void requireFence(DraftCommitBinding binding) {
    var fence = coordinator.readVisibilityFence(binding.target()).orElseThrow();
    if (!binding.commitId().equals(fence.commitId())
        || !binding.requestId().equals(fence.requestId())
        || !binding.digest().equals(fence.inputDigest())
        || coordinator.read(binding.target(), binding.requestId()).orElseThrow().workflowState()
            != DraftCommitCoordinatorRepository.WorkflowState.SYNCHRONIZED)
      throw new IllegalStateException("TEMPLATE_CONFIG_SYNCHRONIZED_FENCE_UNAVAILABLE");
  }

  private void open(TargetProof target) {
    if (dsl.fetchOne(
                "SELECT id FROM version WHERE id = ? AND version_state = 'DRAFT' AND NOT is_script_only "
                    + "AND base_version_id IS NULL AND script_patch_version IS NULL",
                target.gameDesignVersionRowId())
            == null
        || dsl.fetchOne(
                "SELECT 1 FROM game_design_authored_draft_publish_selection WHERE canonical_tenant_id = ? AND canonical_version_id = ?",
                target.canonicalTenantId(),
                target.canonicalVersionId())
            != null) throw new IllegalStateException("TEMPLATE_CONFIG_FROZEN_OR_NOT_DRAFT");
  }

  private void lock(TargetProof target) {
    var repository = new net.firedevops.firemud.gamedesign.repository.VersionRepository(dsl);
    var qualified =
        repository
            .findByCanonicalTenantIdAndCanonicalVersionId(
                target.canonicalTenantId(), target.canonicalVersionId())
            .orElseThrow();
    var version =
        repository
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
      throw new IllegalStateException("TEMPLATE_CONFIG_TARGET_CHANGED");
  }

  private static void write() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        || !Integer.valueOf(TransactionDefinition.ISOLATION_READ_COMMITTED)
            .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))
      throw new IllegalStateException(
          "Template config source requires writable READ_COMMITTED transaction");
  }
}
