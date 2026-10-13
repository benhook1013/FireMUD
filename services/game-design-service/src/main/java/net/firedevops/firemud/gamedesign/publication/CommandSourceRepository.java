package net.firedevops.firemud.gamedesign.publication;

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
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.OwnerStatus;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Internal owner storage for a complete effective command set. Writes require the caller's
 * coordinator transaction; this repository does not register an owner outcome or authorize a
 * publication.
 */
public final class CommandSourceRepository {
  private final DSLContext dsl;

  public CommandSourceRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
  }

  /**
   * Explicit empty genesis for a Version known by its creation owner to be newly inserted as a
   * Draft. Call only in that exact Version insertion transaction. Missing source rows alone never
   * establish genesis, and this receipt is not a Draft commit.
   */
  public CommandSource.NewDraftGenesisReceipt enrollNewDraftGenesis(
      CommandSource.NewDraftGenesisReceipt receipt) {
    requireWrite();
    Objects.requireNonNull(receipt, "receipt");
    TargetProof target = receipt.target();
    lockAuthority(target);
    Record version = version(target);
    if (!"DRAFT".equals(version.get("version_state", String.class))
        || !Long.valueOf(1L).equals(version.get("version_state_epoch", Long.class))
        || Boolean.TRUE.equals(version.get("is_script_only", Boolean.class))
        || version.get("script_patch_version") != null
        || version.get("base_version_id") != null) {
      throw new IllegalStateException("COMMAND_SOURCE_GENESIS_REQUIRES_NEW_FULL_DRAFT_EPOCH_ONE");
    }
    Optional<Record> existing = baseline(target);
    if (existing.isPresent()) {
      Record row = existing.orElseThrow();
      if (!Arrays.equals(receipt.canonicalBytes(), row.get("receipt_bytes", byte[].class))
          || !receipt.digest().equals(row.get("receipt_digest", String.class))
          || !receipt.targetProofJson().equals(row.get("target_proof_json", String.class))
          || !receipt.receiptId().equals(row.get("receipt_id", UUID.class))
          || !receipt
              .creationTransactionId()
              .equals(row.get("creation_transaction_id", String.class))) {
        throw new IllegalStateException("COMMAND_SOURCE_GENESIS_RECEIPT_CONFLICT");
      }
      requireVersionInsertWitness(target, receipt.creationTransactionId(), false);
      requireHeadBaseline(target, receipt.digest());
      return receipt;
    }
    requireVersionInsertWitness(target, receipt.creationTransactionId(), true);
    if (head(target).isPresent()
        || new DraftCommitCoordinatorRepository(dsl).readVisibilityFence(target).isPresent()) {
      throw new IllegalStateException("COMMAND_SOURCE_GENESIS_AFTER_DRAFT_COMMIT_DENIED");
    }
    dsl.execute(
        "INSERT INTO game_design_command_source_baseline "
            + "(canonical_tenant_id, canonical_version_id, game_design_version_row_id, baseline_kind, "
            + "receipt_id, creation_transaction_id, target_proof_json, receipt_bytes, receipt_digest) "
            + "VALUES (?, ?, ?, 'NEW_DRAFT_EMPTY', ?, ?, ?, ?, ?)",
        target.canonicalTenantId(),
        target.canonicalVersionId(),
        target.gameDesignVersionRowId(),
        receipt.receiptId(),
        receipt.creationTransactionId(),
        receipt.targetProofJson(),
        receipt.canonicalBytes(),
        receipt.digest());
    dsl.execute(
        "INSERT INTO game_design_command_source_head "
            + "(canonical_tenant_id, canonical_version_id, source_epoch, visible_commit_id, applied_commit_id, baseline_digest) "
            + "VALUES (?, ?, '0', NULL, NULL, ?)",
        target.canonicalTenantId(),
        target.canonicalVersionId(),
        receipt.digest());
    requireHeadBaseline(target, receipt.digest());
    return receipt;
  }

  /** Exact readback of an explicitly enrolled empty new-Draft baseline; absence stays absent. */
  public Optional<CommandSource.NewDraftGenesisReceipt> readNewDraftGenesis(TargetProof target) {
    Objects.requireNonNull(target, "target");
    Optional<Record> stored = baseline(target);
    if (stored.isEmpty()) return Optional.empty();
    Record row = stored.orElseThrow();
    CommandSource.NewDraftGenesisReceipt receipt =
        new CommandSource.NewDraftGenesisReceipt(
            target,
            row.get("receipt_id", UUID.class),
            row.get("creation_transaction_id", String.class));
    if (row.get("game_design_version_row_id", Long.class) != target.gameDesignVersionRowId()
        || !receipt.targetProofJson().equals(row.get("target_proof_json", String.class))
        || !Arrays.equals(receipt.canonicalBytes(), row.get("receipt_bytes", byte[].class))
        || !receipt.digest().equals(row.get("receipt_digest", String.class))) {
      throw new IllegalStateException("COMMAND_SOURCE_GENESIS_RECEIPT_READBACK_CONFLICT");
    }
    requireVersionInsertWitness(target, receipt.creationTransactionId(), false);
    return Optional.of(receipt);
  }

  /**
   * Applies command operations to private staged source. The returned evidence is one component of
   * GAME_DESIGN_CONTROL_PLANE's combined APPLIED result; this method never records that result.
   */
  public Optional<CommandApplication> apply(DraftCommitBinding binding) {
    requireWrite();
    Objects.requireNonNull(binding, "binding");
    TargetProof target = binding.target();
    lockAuthority(target);
    exactCommit(binding);
    List<CommandSource.Mutation> mutations = CommandSource.mutations(binding);
    boolean policyMutation = CommandSource.hasRealmPolicyRevision(binding);
    requireCompleteControlPlaneScopes(binding, mutations, policyMutation);
    Optional<CommandApplication> existing = application(binding);
    if (existing.isPresent()) return existing;
    if (mutations.isEmpty()) return Optional.empty();
    requireOpenDraft(target);
    requireNoPublicationSelection(target);
    requireCoordinatorApplicationSlot(binding);
    requireOwnerApplyStarted(binding);

    Record head = requireHead(target);
    String expectedEpoch = commandScope(binding).expectedEpoch();
    if (!expectedEpoch.equals(head.get("source_epoch", String.class))) {
      throw new IllegalStateException("COMMAND_SOURCE_EPOCH_CONFLICT");
    }
    UUID inheritedCommitId = head.get("visible_commit_id", UUID.class);
    requireVisibleHeadMatchesFence(target, inheritedCommitId);
    String baselineDigest = head.get("baseline_digest", String.class);
    List<CommandSource.Definition> inheritedDefinitions;
    if (inheritedCommitId == null) {
      inheritedDefinitions = List.of();
      if (!"0".equals(expectedEpoch)) {
        throw new IllegalStateException("COMMAND_SOURCE_GENESIS_EPOCH_CHANGED");
      }
    } else {
      CommandSnapshot inherited =
          readSnapshot(target, inheritedCommitId)
              .orElseThrow(
                  () -> new IllegalStateException("COMMAND_SOURCE_VISIBLE_SNAPSHOT_UNAVAILABLE"));
      if (!inherited.sourceEpoch().equals(expectedEpoch)
          || !inherited.genesisReceiptDigest().equals(baselineDigest)) {
        throw new IllegalStateException("COMMAND_SOURCE_VISIBLE_STATE_CONFLICT");
      }
      inheritedDefinitions = inherited.definitions();
    }
    for (CommandSource.Mutation mutation : mutations)
      requireRevisionIdentityUnused(target, mutation);
    String resultEpoch = new BigInteger(expectedEpoch).add(BigInteger.ONE).toString();
    CommandSnapshot snapshot =
        new CommandSnapshot(
            binding,
            resultEpoch,
            inheritedCommitId,
            baselineDigest,
            CommandSource.replay(inheritedDefinitions, mutations));
    CommandApplication result =
        new CommandApplication(binding, inheritedCommitId, expectedEpoch, mutations, snapshot);
    insertApplication(result, baselineDigest);
    insertOperations(result);
    int advanced =
        dsl.execute(
            "UPDATE game_design_command_source_head SET source_epoch = ?, applied_commit_id = ? "
                + "WHERE canonical_tenant_id = ? AND canonical_version_id = ? "
                + "AND source_epoch = ? AND visible_commit_id IS NOT DISTINCT FROM ?",
            resultEpoch,
            binding.commitId(),
            target.canonicalTenantId(),
            target.canonicalVersionId(),
            expectedEpoch,
            inheritedCommitId);
    if (advanced != 1) throw new IllegalStateException("COMMAND_SOURCE_EPOCH_CONFLICT");
    return Optional.of(
        application(binding)
            .orElseThrow(
                () -> new IllegalStateException("COMMAND_SOURCE_APPLICATION_READBACK_FAILED")));
  }

  /**
   * Makes the staged complete source visible only after the coordinator's exact synchronized fence
   * and combined owner outcome are durable in this same transaction.
   */
  public CommandSnapshot captureSynchronized(DraftCommitBinding binding) {
    requireWrite();
    Objects.requireNonNull(binding, "binding");
    TargetProof target = binding.target();
    lockAuthority(target);
    exactCommit(binding);
    Optional<CommandSnapshot> existing = readSnapshot(target, binding.commitId());
    if (existing.isPresent()) {
      if (!existing.orElseThrow().binding().equals(binding)) {
        throw new IllegalStateException("COMMAND_SOURCE_SNAPSHOT_BINDING_CONFLICT");
      }
      return existing.orElseThrow();
    }
    requireOpenDraft(target);
    requireNoPublicationSelection(target);
    requireExactSynchronizedOwnerResult(binding);
    Record head = requireHead(target);
    UUID inheritedCommitId = head.get("visible_commit_id", UUID.class);
    String baselineDigest = head.get("baseline_digest", String.class);
    String sourceEpoch = head.get("source_epoch", String.class);
    List<CommandSource.Mutation> mutations = CommandSource.mutations(binding);
    requireCompleteControlPlaneScopes(
        binding, mutations, CommandSource.hasRealmPolicyRevision(binding));
    List<CommandSource.Definition> definitions;
    if (!mutations.isEmpty()) {
      CommandApplication applied =
          application(binding)
              .orElseThrow(
                  () -> new IllegalStateException("COMMAND_SOURCE_APPLICATION_UNAVAILABLE"));
      if (!applied.snapshot().sourceEpoch().equals(sourceEpoch)
          || !applied.snapshot().genesisReceiptDigest().equals(baselineDigest)
          || !Objects.equals(applied.inheritedCommitId(), inheritedCommitId)) {
        throw new IllegalStateException("COMMAND_SOURCE_APPLICATION_NOT_CURRENT");
      }
      definitions = applied.snapshot().definitions();
    } else if (inheritedCommitId == null) {
      if (!"0".equals(sourceEpoch)) {
        throw new IllegalStateException("COMMAND_SOURCE_GENESIS_EPOCH_CHANGED");
      }
      definitions = List.of();
    } else {
      CommandSnapshot inherited =
          readSnapshot(target, inheritedCommitId)
              .orElseThrow(
                  () -> new IllegalStateException("COMMAND_SOURCE_VISIBLE_SNAPSHOT_UNAVAILABLE"));
      if (!inherited.sourceEpoch().equals(sourceEpoch)
          || !inherited.genesisReceiptDigest().equals(baselineDigest)) {
        throw new IllegalStateException("COMMAND_SOURCE_VISIBLE_STATE_CONFLICT");
      }
      definitions = inherited.definitions();
    }
    CommandSnapshot snapshot =
        new CommandSnapshot(binding, sourceEpoch, inheritedCommitId, baselineDigest, definitions);
    dsl.execute(
        "INSERT INTO game_design_command_source_snapshot "
            + "(canonical_tenant_id, canonical_version_id, commit_id, request_id, input_digest, "
            + "binding_json, inherited_commit_id, baseline_digest, source_epoch, snapshot_json, snapshot_digest) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        target.canonicalTenantId(),
        target.canonicalVersionId(),
        binding.commitId(),
        binding.requestId(),
        binding.digest(),
        binding.canonicalJson(),
        inheritedCommitId,
        baselineDigest,
        sourceEpoch,
        snapshot.canonicalJson(),
        snapshot.digest());
    int advanced =
        dsl.execute(
            "UPDATE game_design_command_source_head SET visible_commit_id = ? "
                + "WHERE canonical_tenant_id = ? AND canonical_version_id = ? "
                + "AND visible_commit_id IS NOT DISTINCT FROM ? AND source_epoch = ?",
            binding.commitId(),
            target.canonicalTenantId(),
            target.canonicalVersionId(),
            inheritedCommitId,
            sourceEpoch);
    if (advanced != 1) throw new IllegalStateException("COMMAND_SOURCE_VISIBLE_FENCE_CONFLICT");
    return readSnapshot(target, binding.commitId())
        .orElseThrow(() -> new IllegalStateException("COMMAND_SOURCE_SNAPSHOT_READBACK_FAILED"));
  }

  /** Captures source against the actual original publication operation and exact selected fence. */
  public CommandSnapshot.Capture freeze(GameDesignPublicationOperation operation) {
    requireWrite();
    Objects.requireNonNull(operation, "operation");
    var selection = operation.account().input().selection();
    TargetProof target = selection.target();
    lockAuthority(target);
    Optional<CommandSnapshot.Capture> existing = readCapture(operation);
    if (existing.isPresent()) return existing.orElseThrow();
    requireOpenDraft(target);
    Record selected =
        dsl.fetchOne(
            "SELECT selection_json, selection_digest FROM game_design_authored_draft_publish_selection "
                + "WHERE canonical_tenant_id = ? AND canonical_version_id = ?",
            target.canonicalTenantId(),
            target.canonicalVersionId());
    if (selected == null
        || !selection.canonicalJson().equals(selected.get("selection_json", String.class))
        || !selection.digest().equals(selected.get("selection_digest", String.class))) {
      throw new IllegalStateException("COMMAND_SOURCE_PUBLICATION_SELECTION_UNAVAILABLE");
    }
    var retainedOperation =
        new GameDesignPublicationOperationRepository(dsl)
            .read(operation.workflowId())
            .orElseThrow(
                () ->
                    new IllegalStateException("COMMAND_SOURCE_PUBLICATION_OPERATION_UNAVAILABLE"));
    GameDesignPublicationOperationRepository.exact(retainedOperation.operation(), operation);
    if (!"PENDING".equals(retainedOperation.outcome())) {
      throw new IllegalStateException("COMMAND_SOURCE_PUBLICATION_OPERATION_SEALED");
    }
    Record versionRow =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT version_state_epoch FROM version WHERE id = ?",
                target.gameDesignVersionRowId()),
            "COMMAND_SOURCE_VERSION_UNAVAILABLE");
    Long versionEpoch = versionRow.get("version_state_epoch", Long.class);
    if (versionEpoch == null
        || !selection.intent().expectedVersionStateEpoch().equals(Long.toString(versionEpoch))) {
      throw new IllegalStateException("COMMAND_SOURCE_PUBLICATION_VERSION_EPOCH_CHANGED");
    }
    DraftCommitBinding selectedCommit = selection.selectedCommit();
    requireCurrentFence(selectedCommit);
    CommandSnapshot snapshot =
        readSnapshot(target, selectedCommit.commitId())
            .orElseThrow(
                () -> new IllegalStateException("COMMAND_SOURCE_SELECTED_SNAPSHOT_UNAVAILABLE"));
    Record head = requireHead(target);
    if (!selectedCommit.equals(snapshot.binding())
        || !selectedCommit.commitId().equals(head.get("visible_commit_id", UUID.class))
        || !snapshot.sourceEpoch().equals(head.get("source_epoch", String.class))) {
      throw new IllegalStateException("COMMAND_SOURCE_SELECTED_SOURCE_NOT_SYNCHRONIZED");
    }
    CommandSnapshot.Capture capture = new CommandSnapshot.Capture(operation, snapshot);
    dsl.execute(
        "INSERT INTO game_design_command_source_capture "
            + "(canonical_tenant_id, canonical_version_id, publish_workflow_id, commit_id, "
            + "operation_bytes, capture_bytes, capture_digest) VALUES (?, ?, ?, ?, ?, ?, ?)",
        target.canonicalTenantId(),
        target.canonicalVersionId(),
        operation.workflowId(),
        selectedCommit.commitId(),
        operation.canonicalBytes(),
        capture.canonicalBytes(),
        capture.digest());
    return readCapture(operation)
        .orElseThrow(() -> new IllegalStateException("COMMAND_SOURCE_CAPTURE_READBACK_FAILED"));
  }

  public Optional<CommandSnapshot> readSnapshot(TargetProof target, UUID commitId) {
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(commitId, "commitId");
    Record row =
        dsl.fetchOne(
            "SELECT * FROM game_design_command_source_snapshot WHERE canonical_tenant_id = ? "
                + "AND canonical_version_id = ? AND commit_id = ?",
            target.canonicalTenantId(),
            target.canonicalVersionId(),
            commitId);
    if (row == null) return Optional.empty();
    CommandSnapshot snapshot = CommandSnapshot.fromStored(row.get("snapshot_json", String.class));
    if (!snapshot.binding().target().equals(target)
        || !snapshot.binding().commitId().equals(commitId)
        || !snapshot.binding().requestId().equals(row.get("request_id", UUID.class))
        || !snapshot.binding().digest().equals(row.get("input_digest", String.class))
        || !snapshot.binding().canonicalJson().equals(row.get("binding_json", String.class))
        || !snapshot.sourceEpoch().equals(row.get("source_epoch", String.class))
        || !snapshot.genesisReceiptDigest().equals(row.get("baseline_digest", String.class))
        || !snapshot.digest().equals(row.get("snapshot_digest", String.class))) {
      throw new IllegalStateException("COMMAND_SOURCE_SNAPSHOT_STORAGE_CONFLICT");
    }
    var commit =
        new DraftCommitCoordinatorRepository(dsl)
            .read(target, snapshot.binding().requestId())
            .orElseThrow(() -> new IllegalStateException("COMMAND_SOURCE_COMMIT_UNAVAILABLE"));
    Record fence =
        dsl.fetchOne(
            "SELECT input_digest FROM game_design_draft_commit_visibility_fence "
                + "WHERE canonical_tenant_id = ? AND canonical_version_id = ? AND request_id = ? AND commit_id = ?",
            target.canonicalTenantId(),
            target.canonicalVersionId(),
            snapshot.binding().requestId(),
            commitId);
    if (!commit.binding().equals(snapshot.binding())
        || fence == null
        || !snapshot.binding().digest().equals(fence.get("input_digest", String.class))) {
      throw new IllegalStateException("COMMAND_SOURCE_SNAPSHOT_FENCE_CONFLICT");
    }
    verifyDefinitionProvenance(snapshot);
    return Optional.of(snapshot);
  }

  public Optional<CommandSnapshot.Capture> readCapture(GameDesignPublicationOperation operation) {
    Objects.requireNonNull(operation, "operation");
    var selection = operation.account().input().selection();
    TargetProof target = selection.target();
    Record row =
        dsl.fetchOne(
            "SELECT * FROM game_design_command_source_capture WHERE canonical_tenant_id = ? AND canonical_version_id = ?",
            target.canonicalTenantId(),
            target.canonicalVersionId());
    if (row == null) return Optional.empty();
    if (!Arrays.equals(operation.canonicalBytes(), row.get("operation_bytes", byte[].class))
        || !operation.workflowId().equals(row.get("publish_workflow_id", String.class))
        || !selection.selectedCommit().commitId().equals(row.get("commit_id", UUID.class))) {
      throw new IllegalArgumentException("COMMAND_SOURCE_FREEZE_OPERATION_CONFLICT");
    }
    CommandSnapshot snapshot =
        readSnapshot(target, row.get("commit_id", UUID.class))
            .orElseThrow(
                () -> new IllegalStateException("COMMAND_SOURCE_CAPTURE_SNAPSHOT_UNAVAILABLE"));
    CommandSnapshot.Capture capture = new CommandSnapshot.Capture(operation, snapshot);
    if (!Arrays.equals(capture.canonicalBytes(), row.get("capture_bytes", byte[].class))
        || !capture.digest().equals(row.get("capture_digest", String.class))) {
      throw new IllegalStateException("COMMAND_SOURCE_CAPTURE_STORAGE_CONFLICT");
    }
    return Optional.of(capture);
  }

  private void insertApplication(CommandApplication application, String baselineDigest) {
    DraftCommitBinding binding = application.binding();
    dsl.execute(
        "INSERT INTO game_design_command_source_application "
            + "(canonical_tenant_id, canonical_version_id, commit_id, request_id, input_digest, "
            + "binding_json, inherited_commit_id, baseline_digest, expected_epoch, snapshot_json, "
            + "operations_json, result_bytes) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        binding.target().canonicalTenantId(),
        binding.target().canonicalVersionId(),
        binding.commitId(),
        binding.requestId(),
        binding.digest(),
        binding.canonicalJson(),
        application.inheritedCommitId(),
        baselineDigest,
        application.expectedEpoch(),
        application.snapshot().canonicalJson(),
        CommandSource.mutationsJson(application.mutations()),
        application.canonicalBytes());
  }

  private void insertOperations(CommandApplication application) {
    for (CommandSource.Mutation mutation : application.mutations()) {
      String operationJson = CommandSource.canonical(mutation.canonicalObject());
      dsl.execute(
          "INSERT INTO game_design_command_source_operation "
              + "(canonical_tenant_id, canonical_version_id, commit_id, revision_order, revision_id, "
              + "operation_kind, command_id, command_key, definition_json, operation_json) "
              + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
          application.binding().target().canonicalTenantId(),
          application.binding().target().canonicalVersionId(),
          application.binding().commitId(),
          Integer.parseInt(mutation.revisionOrder()),
          mutation.revisionId(),
          mutation.operation().name(),
          mutation.commandId(),
          mutation.stableKey(),
          mutation.definitionJson(),
          operationJson);
    }
  }

  private Optional<CommandApplication> application(DraftCommitBinding binding) {
    Record row =
        dsl.fetchOne(
            "SELECT * FROM game_design_command_source_application WHERE canonical_tenant_id = ? "
                + "AND canonical_version_id = ? AND commit_id = ?",
            binding.target().canonicalTenantId(),
            binding.target().canonicalVersionId(),
            binding.commitId());
    if (row == null) return Optional.empty();
    DraftCommitBinding stored =
        DraftCommitBinding.fromStored(
            row.get("binding_json", String.class), row.get("input_digest", String.class));
    CommandSnapshot snapshot = CommandSnapshot.fromStored(row.get("snapshot_json", String.class));
    String operationsJson = row.get("operations_json", String.class);
    List<CommandSource.Mutation> mutations = CommandSource.mutationsFromStored(operationsJson);
    CommandApplication result =
        new CommandApplication(
            stored,
            row.get("inherited_commit_id", UUID.class),
            row.get("expected_epoch", String.class),
            mutations,
            snapshot);
    if (!stored.equals(binding)
        || !binding.requestId().equals(row.get("request_id", UUID.class))
        || !CommandSource.mutationsJson(mutations).equals(operationsJson)
        || !Arrays.equals(result.canonicalBytes(), row.get("result_bytes", byte[].class))) {
      throw new IllegalStateException("COMMAND_SOURCE_APPLICATION_EXACT_BINDING_CONFLICT");
    }
    List<String> storedOperations =
        dsl.fetch(
                "SELECT operation_json FROM game_design_command_source_operation "
                    + "WHERE canonical_tenant_id = ? AND canonical_version_id = ? AND commit_id = ? ORDER BY revision_order",
                binding.target().canonicalTenantId(),
                binding.target().canonicalVersionId(),
                binding.commitId())
            .getValues("operation_json", String.class);
    if (!storedOperations.equals(
        result.mutations().stream()
            .map(m -> CommandSource.canonical(m.canonicalObject()))
            .toList())) {
      throw new IllegalStateException("COMMAND_SOURCE_OPERATION_READBACK_CONFLICT");
    }
    return Optional.of(result);
  }

  private void verifyDefinitionProvenance(CommandSnapshot snapshot) {
    for (CommandSource.Definition definition : snapshot.definitions()) {
      Record row =
          dsl.fetchOne(
              "SELECT binding_json, input_digest FROM game_design_draft_commit "
                  + "WHERE canonical_tenant_id = ? AND canonical_version_id = ? AND commit_id = ?",
              snapshot.binding().target().canonicalTenantId(),
              snapshot.binding().target().canonicalVersionId(),
              definition.sourceCommitId());
      if (row == null)
        throw new IllegalStateException("COMMAND_SOURCE_AUTHORED_COMMIT_UNAVAILABLE");
      DraftCommitBinding original =
          DraftCommitBinding.fromStored(
              row.get("binding_json", String.class), row.get("input_digest", String.class));
      if (!original.target().equals(snapshot.binding().target())) {
        throw new IllegalStateException("COMMAND_SOURCE_AUTHORED_TARGET_CHANGED");
      }
      CommandSource.Mutation source =
          CommandSource.mutations(original).stream()
              .filter(mutation -> mutation.revisionId().equals(definition.sourceRevisionId()))
              .filter(mutation -> mutation.operation() == CommandSource.OperationKind.UPSERT)
              .findFirst()
              .orElseThrow(
                  () -> new IllegalStateException("COMMAND_SOURCE_AUTHORED_REVISION_UNAVAILABLE"));
      if (!source.commandId().equals(definition.commandId())
          || !source.definitionJson().equals(definition.definitionJson())
          || !source.commitId().equals(definition.sourceCommitId())) {
        throw new IllegalStateException("COMMAND_SOURCE_AUTHORED_PROVENANCE_CHANGED");
      }
    }
  }

  private void requireCompleteControlPlaneScopes(
      DraftCommitBinding binding,
      List<CommandSource.Mutation> commandMutations,
      boolean policyMutation) {
    List<DraftCommitBinding.AffectedUnit> units =
        binding.affectedUnits(Owner.GAME_DESIGN_CONTROL_PLANE);
    List<DraftCommitBinding.AffectedUnit> commands =
        units.stream().filter(this::isCommandScope).toList();
    List<DraftCommitBinding.AffectedUnit> policies =
        units.stream().filter(this::isPolicyScope).toList();
    if (commands.size() != (commandMutations.isEmpty() ? 0 : 1)
        || policies.size() != (policyMutation ? 1 : 0)
        || commands.size() + policies.size() != units.size()) {
      throw new IllegalArgumentException(
          "Complete supported Game Design control-plane scopes are required");
    }
    if (!commands.isEmpty()) requireScopeTarget(commands.getFirst(), binding.target());
    if (!policies.isEmpty()) requireScopeTarget(policies.getFirst(), binding.target());
  }

  private boolean isCommandScope(DraftCommitBinding.AffectedUnit unit) {
    return CommandSource.SCOPE.equals(unit.aggregateType())
        && CommandSource.SCOPE.equals(unit.scopeType())
        && CommandSource.SCOPE_ID.equals(unit.scopeId());
  }

  private boolean isPolicyScope(DraftCommitBinding.AffectedUnit unit) {
    return RealmPolicySource.SCOPE.equals(unit.aggregateType())
        && RealmPolicySource.SCOPE.equals(unit.scopeType())
        && "effective".equals(unit.scopeId());
  }

  private void requireScopeTarget(DraftCommitBinding.AffectedUnit unit, TargetProof target) {
    if (!target.canonicalVersionId().toString().equals(unit.aggregateId())) {
      throw new IllegalArgumentException(
          "Control-plane scope must bind the exact canonical Version");
    }
  }

  private DraftCommitBinding.AffectedUnit commandScope(DraftCommitBinding binding) {
    return binding.affectedUnits(Owner.GAME_DESIGN_CONTROL_PLANE).stream()
        .filter(this::isCommandScope)
        .findFirst()
        .orElseThrow(
            () -> new IllegalArgumentException("Command source affected scope is omitted"));
  }

  private void requireCoordinatorApplicationSlot(DraftCommitBinding binding) {
    var slot =
        new DraftCommitCoordinatorRepository(dsl)
            .readApplicationSlot(binding.target())
            .orElseThrow(
                () -> new IllegalStateException("COMMAND_SOURCE_APPLICATION_SLOT_UNAVAILABLE"));
    if (!slot.requestId().equals(binding.requestId())
        || !slot.commitId().equals(binding.commitId())) {
      throw new IllegalStateException("COMMAND_SOURCE_APPLICATION_SLOT_CHANGED");
    }
  }

  private void requireOwnerApplyStarted(DraftCommitBinding binding) {
    var state =
        new DraftCommitCoordinatorRepository(dsl)
            .read(binding.target(), binding.requestId())
            .orElseThrow()
            .ownerStates()
            .get(Owner.GAME_DESIGN_CONTROL_PLANE);
    if (state == null
        || (state.status() != OwnerStatus.IN_PROGRESS && state.status() != OwnerStatus.UNKNOWN)) {
      throw new IllegalStateException("COMMAND_SOURCE_OWNER_APPLICATION_NOT_STARTED");
    }
  }

  private void requireExactSynchronizedOwnerResult(DraftCommitBinding binding) {
    DraftCommitCoordinatorRepository coordinator = new DraftCommitCoordinatorRepository(dsl);
    var commit = coordinator.read(binding.target(), binding.requestId()).orElseThrow();
    var owner = commit.ownerStates().get(Owner.GAME_DESIGN_CONTROL_PLANE);
    var fence = coordinator.readVisibilityFence(binding.target()).orElseThrow();
    if (!commit.binding().equals(binding)
        || commit.workflowState() != DraftCommitCoordinatorRepository.WorkflowState.SYNCHRONIZED
        || !binding.requestId().equals(fence.requestId())
        || !binding.commitId().equals(fence.commitId())
        || !binding.digest().equals(fence.inputDigest())) {
      throw new IllegalStateException("COMMAND_SOURCE_SYNCHRONIZED_OWNER_RESULT_UNAVAILABLE");
    }
    if (binding.requiredOwners().contains(Owner.GAME_DESIGN_CONTROL_PLANE)
        && (owner == null
            || owner.status() != OwnerStatus.APPLIED
            || owner.outcome().isEmpty()
            || !binding.commitId().equals(owner.outcome().orElseThrow().commitId())
            || !binding.digest().equals(owner.outcome().orElseThrow().bindingDigest()))) {
      throw new IllegalStateException("COMMAND_SOURCE_SYNCHRONIZED_OWNER_RESULT_UNAVAILABLE");
    }
  }

  private void requireVisibleHeadMatchesFence(TargetProof target, UUID visibleCommitId) {
    var fence = new DraftCommitCoordinatorRepository(dsl).readVisibilityFence(target);
    if (visibleCommitId == null) {
      if (fence.isPresent())
        throw new IllegalStateException("COMMAND_SOURCE_VISIBLE_FENCE_CHANGED");
    } else if (fence.isEmpty() || !visibleCommitId.equals(fence.orElseThrow().commitId())) {
      throw new IllegalStateException("COMMAND_SOURCE_VISIBLE_FENCE_CHANGED");
    }
  }

  private void requireCurrentFence(DraftCommitBinding binding) {
    var fence =
        new DraftCommitCoordinatorRepository(dsl)
            .readVisibilityFence(binding.target())
            .orElseThrow(
                () -> new IllegalStateException("COMMAND_SOURCE_SYNCHRONIZED_FENCE_UNAVAILABLE"));
    if (!binding.requestId().equals(fence.requestId())
        || !binding.commitId().equals(fence.commitId())
        || !binding.digest().equals(fence.inputDigest())) {
      throw new IllegalStateException("COMMAND_SOURCE_SELECTED_COMMIT_NOT_CURRENT");
    }
  }

  private void exactCommit(DraftCommitBinding binding) {
    var retained =
        new DraftCommitCoordinatorRepository(dsl)
            .read(binding.target(), binding.requestId())
            .orElseThrow(() -> new IllegalStateException("COMMAND_SOURCE_COMMIT_UNAVAILABLE"));
    if (!retained.binding().equals(binding)) {
      throw new IllegalArgumentException("COMMAND_SOURCE_COMMIT_BINDING_CONFLICT");
    }
  }

  private void requireRevisionIdentityUnused(TargetProof target, CommandSource.Mutation mutation) {
    if (dsl.fetchOne(
            "SELECT 1 FROM game_design_command_source_operation "
                + "WHERE canonical_tenant_id = ? AND canonical_version_id = ? AND revision_id = ?",
            target.canonicalTenantId(),
            target.canonicalVersionId(),
            mutation.revisionId())
        != null) {
      throw new IllegalArgumentException("COMMAND_SOURCE_REVISION_ID_REUSE");
    }
  }

  private Optional<Record> baseline(TargetProof target) {
    return Optional.ofNullable(
        dsl.fetchOne(
            "SELECT * FROM game_design_command_source_baseline WHERE canonical_tenant_id = ? AND canonical_version_id = ?",
            target.canonicalTenantId(),
            target.canonicalVersionId()));
  }

  private Optional<Record> head(TargetProof target) {
    return Optional.ofNullable(
        dsl.fetchOne(
            "SELECT * FROM game_design_command_source_head WHERE canonical_tenant_id = ? AND canonical_version_id = ? FOR UPDATE",
            target.canonicalTenantId(),
            target.canonicalVersionId()));
  }

  private Record requireHead(TargetProof target) {
    return head(target)
        .orElseThrow(() -> new IllegalStateException("COMMAND_SOURCE_BASELINE_UNAVAILABLE"));
  }

  private void requireHeadBaseline(TargetProof target, String digest) {
    Record row = requireHead(target);
    if (!digest.equals(row.get("baseline_digest", String.class))
        || !"0".equals(row.get("source_epoch", String.class))
        || row.get("visible_commit_id", UUID.class) != null
        || row.get("applied_commit_id", UUID.class) != null) {
      throw new IllegalStateException("COMMAND_SOURCE_GENESIS_READBACK_CONFLICT");
    }
  }

  private void requireVersionInsertWitness(
      TargetProof target, String creationTransactionId, boolean requireCurrentTransaction) {
    Record witness =
        dsl.fetchOne(
            "SELECT creation_transaction_id FROM game_design_realm_policy_version_insert "
                + "WHERE version_id = ?",
            target.gameDesignVersionRowId());
    Record currentTransaction =
        Objects.requireNonNull(
            dsl.fetchOne("SELECT pg_current_xact_id()::TEXT AS transaction_id"),
            "COMMAND_SOURCE_CURRENT_TRANSACTION_UNAVAILABLE");
    String currentTransactionId = currentTransaction.get("transaction_id", String.class);
    if (witness == null
        || !creationTransactionId.equals(witness.get("creation_transaction_id", String.class))
        || (requireCurrentTransaction && !creationTransactionId.equals(currentTransactionId))) {
      throw new IllegalStateException("COMMAND_SOURCE_NEW_DRAFT_INSERTION_WITNESS_UNAVAILABLE");
    }
  }

  private void requireOpenDraft(TargetProof target) {
    Record version = version(target);
    if (!"DRAFT".equals(version.get("version_state", String.class))
        || Boolean.TRUE.equals(version.get("is_script_only", Boolean.class))
        || version.get("script_patch_version") != null
        || version.get("base_version_id") != null
        || dsl.fetchOne(
                "SELECT 1 FROM game_design_command_source_capture WHERE canonical_tenant_id = ? AND canonical_version_id = ?",
                target.canonicalTenantId(),
                target.canonicalVersionId())
            != null) {
      throw new IllegalStateException("COMMAND_SOURCE_NOT_OPEN_FULL_DRAFT");
    }
  }

  private void requireNoPublicationSelection(TargetProof target) {
    if (dsl.fetchOne(
            "SELECT 1 FROM game_design_authored_draft_publish_selection "
                + "WHERE canonical_tenant_id = ? AND canonical_version_id = ?",
            target.canonicalTenantId(),
            target.canonicalVersionId())
        != null) {
      throw new IllegalStateException("COMMAND_SOURCE_PUBLICATION_SELECTION_RESERVED");
    }
  }

  private void lockAuthority(TargetProof target) {
    Record row =
        dsl.fetchOne(
            "SELECT v.id FROM version v JOIN game g ON g.id = v.identity_source_game_row_id "
                + "AND g.tenant_id = v.identity_source_game_tenant_key AND g.canonical_tenant_id = v.canonical_tenant_id "
                + "AND g.tenant_identity_provenance_kind = v.identity_source_provenance_kind "
                + "AND g.tenant_identity_source_game_id = g.id AND g.tenant_identity_source_legacy_tenant_id = g.tenant_id "
                + "WHERE v.id = ? AND v.tenant_id = ? AND v.canonical_tenant_id = ? AND v.canonical_version_id = ? "
                + "AND v.identity_source_game_row_id = ? AND v.identity_source_game_tenant_key = ? "
                + "AND v.identity_source_provenance_kind = ? FOR UPDATE OF v",
            target.gameDesignVersionRowId(),
            target.gameDesignVersionTenantKey(),
            target.canonicalTenantId(),
            target.canonicalVersionId(),
            target.sourceGameRowId(),
            target.sourceGameTenantKey(),
            target.sourceProvenanceKind());
    if (row == null) throw new IllegalStateException("COMMAND_SOURCE_CANONICAL_OWNER_UNAVAILABLE");
  }

  private Record version(TargetProof target) {
    Record row =
        dsl.fetchOne(
            "SELECT v.id, v.tenant_id, v.canonical_tenant_id, v.canonical_version_id, "
                + "v.identity_source_game_row_id, v.identity_source_game_tenant_key, v.identity_source_provenance_kind, "
                + "v.version_state, v.version_state_epoch, v.is_script_only, v.script_patch_version, v.base_version_id "
                + "FROM version v JOIN game g ON g.id = v.identity_source_game_row_id "
                + "AND g.tenant_id = v.identity_source_game_tenant_key AND g.canonical_tenant_id = v.canonical_tenant_id "
                + "AND g.tenant_identity_provenance_kind = v.identity_source_provenance_kind "
                + "AND g.tenant_identity_source_game_id = g.id AND g.tenant_identity_source_legacy_tenant_id = g.tenant_id "
                + "WHERE v.id = ? AND v.tenant_id = ? AND v.canonical_tenant_id = ? AND v.canonical_version_id = ? "
                + "AND v.identity_source_game_row_id = ? AND v.identity_source_game_tenant_key = ? "
                + "AND v.identity_source_provenance_kind = ?",
            target.gameDesignVersionRowId(),
            target.gameDesignVersionTenantKey(),
            target.canonicalTenantId(),
            target.canonicalVersionId(),
            target.sourceGameRowId(),
            target.sourceGameTenantKey(),
            target.sourceProvenanceKind());
    if (row == null) throw new IllegalStateException("COMMAND_SOURCE_CANONICAL_OWNER_UNAVAILABLE");
    return row;
  }

  private static void requireWrite() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        || !Integer.valueOf(TransactionDefinition.ISOLATION_READ_COMMITTED)
            .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) {
      throw new IllegalStateException(
          "Command source writes require caller-owned writable READ_COMMITTED transaction");
    }
  }
}
