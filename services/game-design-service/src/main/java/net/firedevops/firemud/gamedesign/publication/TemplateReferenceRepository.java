package net.firedevops.firemud.gamedesign.publication;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Owner-local normalized template base references and explicit tenant cutover authority. */
public final class TemplateReferenceRepository {
  private final DSLContext dsl;

  public TemplateReferenceRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
  }

  /**
   * Creates the non-launchable initial phase only from the exact completed fresh Game operation.
   * Retained tenants and rows discovered by inventory scans are never initialized here.
   */
  public PhaseSnapshot initializeFreshTenant(FreshTenantCreationEvidence evidence) {
    requireWritableTransaction();
    Objects.requireNonNull(evidence, "evidence");
    if (!"NEW_GAME_ROW".equals(evidence.provenanceKind())) {
      throw new IllegalArgumentException("Fresh Game tenant creation evidence is required");
    }
    Record owner =
        dsl.fetchOne(
            "SELECT g.id AS game_row_id, g.tenant_id, g.canonical_tenant_id, "
                + "g.tenant_identity_provenance_kind, c.target_namespace, c.request_digest, "
                + "c.operation_id, c.creation_request_id, "
                + "c.status, c.source_game_row_id, c.source_game_tenant_key, c.provenance_kind, "
                + "c.evidence_digest FROM game_tenant_creation_operations c JOIN game g "
                + "ON g.id = c.source_game_row_id AND g.tenant_id = c.source_game_tenant_key "
                + "AND g.canonical_tenant_id = c.canonical_tenant_id "
                + "WHERE c.operation_id = ? FOR UPDATE OF g",
            evidence.operationId());
    Long sourceGameRowId = owner == null ? null : owner.get("source_game_row_id", Long.class);
    if (owner == null
        || !"COMPLETED".equals(owner.get("status", String.class))
        || !evidence.operationId().equals(owner.get("operation_id", UUID.class))
        || !evidence.targetNamespace().equals(owner.get("target_namespace", String.class))
        || !evidence.creationRequestId().equals(owner.get("creation_request_id", UUID.class))
        || !evidence.requestDigest().equals(owner.get("request_digest", String.class))
        || !evidence.canonicalTenantId().equals(owner.get("canonical_tenant_id", UUID.class))
        || sourceGameRowId == null
        || evidence.sourceGameRowId() != sourceGameRowId
        || !evidence.sourceGameTenantKey().equals(owner.get("source_game_tenant_key", String.class))
        || !evidence.sourceGameTenantKey().equals(owner.get("tenant_id", String.class))
        || !"NEW_GAME_ROW".equals(owner.get("provenance_kind", String.class))
        || !"NEW_GAME_ROW".equals(owner.get("tenant_identity_provenance_kind", String.class))
        || !evidence.evidenceDigest().equals(owner.get("evidence_digest", String.class))) {
      throw new IllegalStateException("TEMPLATE_REFERENCE_FRESH_CREATION_EVIDENCE_UNAVAILABLE");
    }

    Record existing =
        dsl.fetchOne(
            "SELECT * FROM game_template_reference_phase WHERE canonical_tenant_id = ? FOR UPDATE",
            evidence.canonicalTenantId());
    if (existing != null) {
      PhaseSnapshot snapshot = phaseSnapshot(existing);
      requireSameCreation(snapshot, evidence);
      return snapshot;
    }

    if (dsl.execute(
            "INSERT INTO game_template_reference_phase (canonical_tenant_id, source_game_row_id, "
                + "source_game_tenant_key, creation_operation_id, creation_request_id, "
                + "creation_evidence_digest, phase, phase_epoch) "
                + "VALUES (?, ?, ?, ?, ?, ?, 'BACKFILLING', 1)",
            evidence.canonicalTenantId(),
            evidence.sourceGameRowId(),
            evidence.sourceGameTenantKey(),
            evidence.operationId(),
            evidence.creationRequestId(),
            evidence.evidenceDigest())
        != 1) {
      throw new IllegalStateException("TEMPLATE_REFERENCE_PHASE_GENESIS_WRITE_CONFLICT");
    }
    PhaseSnapshot persisted = readPhaseForUpdate(evidence.canonicalTenantId()).orElseThrow();
    requireSameCreation(persisted, evidence);
    if (persisted.phase() != Phase.BACKFILLING || persisted.phaseEpoch() != 1) {
      throw new IllegalStateException("TEMPLATE_REFERENCE_PHASE_GENESIS_READBACK_CONFLICT");
    }
    return persisted;
  }

  /**
   * Reads explicit phase state. An absent row remains absent and is never interpreted as ENFORCED.
   */
  public Optional<PhaseSnapshot> readPhase(UUID canonicalTenantId) {
    requireCanonicalTenantId(canonicalTenantId);
    Record row =
        dsl.fetchOne(
            "SELECT * FROM game_template_reference_phase WHERE canonical_tenant_id = ?",
            canonicalTenantId);
    return row == null ? Optional.empty() : Optional.of(phaseSnapshot(row));
  }

  public PhaseSnapshot requireEnforced(UUID canonicalTenantId) {
    return readPhase(canonicalTenantId)
        .filter(snapshot -> snapshot.phase() == Phase.ENFORCED)
        .orElseThrow(() -> new IllegalStateException("TEMPLATE_REFERENCE_PHASE_NOT_ENFORCED"));
  }

  /**
   * Validates the complete physical tenant inventory under the Game-row mutex, then performs both
   * monotonic CAS transitions using the same exact inventory/source digest. This is an internal
   * owner primitive, not a public or read-side repair operation.
   */
  public PhaseSnapshot validateAndEnforce(UUID canonicalTenantId) {
    requireWritableReadCommittedTransaction();
    requireCanonicalTenantId(canonicalTenantId);
    GameOwner owner = lockGameOwner(canonicalTenantId);
    PhaseSnapshot phase =
        readPhaseForUpdate(canonicalTenantId)
            .orElseThrow(
                () -> new IllegalStateException("TEMPLATE_REFERENCE_PHASE_NOT_INITIALIZED"));
    requirePhaseOwnerMatches(phase, owner);
    if (phase.phase() == Phase.ENFORCED) return phase;

    InventoryEvidence evidence = readCompleteInventory(owner);
    if (phase.phase() == Phase.BACKFILLING) {
      if (dsl.execute(
              "UPDATE game_template_reference_phase SET phase = 'VALIDATED', phase_epoch = phase_epoch + 1, "
                  + "inventory_digest = ?, inventory_template_count = ? "
                  + "WHERE canonical_tenant_id = ? AND phase = 'BACKFILLING' AND phase_epoch = ?",
              evidence.digest(),
              evidence.templateCount(),
              canonicalTenantId,
              phase.phaseEpoch())
          != 1) {
        throw new IllegalStateException("TEMPLATE_REFERENCE_PHASE_CAS_CONFLICT");
      }
      phase = readPhaseForUpdate(canonicalTenantId).orElseThrow();
      if (phase.phase() != Phase.VALIDATED
          || !evidence.digest().equals(phase.inventoryDigest())
          || evidence.templateCount() != phase.inventoryTemplateCount()) {
        throw new IllegalStateException("TEMPLATE_REFERENCE_PHASE_VALIDATION_READBACK_CONFLICT");
      }
    } else if (phase.phase() == Phase.VALIDATED
        && (!evidence.digest().equals(phase.inventoryDigest())
            || evidence.templateCount() != phase.inventoryTemplateCount())) {
      throw new IllegalStateException("TEMPLATE_REFERENCE_PHASE_VALIDATION_EVIDENCE_STALE");
    }

    if (phase.phase() != Phase.VALIDATED
        || dsl.execute(
                "UPDATE game_template_reference_phase SET phase = 'ENFORCED', phase_epoch = phase_epoch + 1 "
                    + "WHERE canonical_tenant_id = ? AND phase = 'VALIDATED' AND phase_epoch = ? "
                    + "AND inventory_digest = ? AND inventory_template_count = ?",
                canonicalTenantId,
                phase.phaseEpoch(),
                evidence.digest(),
                evidence.templateCount())
            != 1) {
      throw new IllegalStateException("TEMPLATE_REFERENCE_PHASE_CAS_CONFLICT");
    }
    PhaseSnapshot enforced = readPhaseForUpdate(canonicalTenantId).orElseThrow();
    if (enforced.phase() != Phase.ENFORCED
        || enforced.phaseEpoch() != phase.phaseEpoch() + 1
        || !evidence.digest().equals(enforced.inventoryDigest())
        || evidence.templateCount() != enforced.inventoryTemplateCount()) {
      throw new IllegalStateException("TEMPLATE_REFERENCE_PHASE_ENFORCEMENT_READBACK_CONFLICT");
    }
    return enforced;
  }

  /** Applies one authorized source mutation to the current canonical base projection. */
  void synchronizeCurrentProjection(
      TargetProof target,
      DraftCommitBinding binding,
      TemplateConfigSourceSnapshot snapshot,
      List<TemplateConfigSource.Mutation> mutations,
      Map<UUID, String> createdRows) {
    requireWritableReadCommittedTransaction();
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(binding, "binding");
    Objects.requireNonNull(snapshot, "snapshot");
    if (!binding.target().equals(target) || !snapshot.binding().equals(binding)) {
      throw new IllegalStateException("TEMPLATE_REFERENCE_SOURCE_BINDING_CONFLICT");
    }
    Map<String, TemplateConfigSource.Mutation> finalMutationByTemplate =
        new java.util.LinkedHashMap<>();
    for (TemplateConfigSource.Mutation mutation : mutations) {
      String templateId =
          mutation.operation() == TemplateConfigSource.OperationKind.CREATE
              ? createdRows.get(mutation.revisionId())
              : mutation.templateId();
      if (templateId == null) {
        throw new IllegalStateException("TEMPLATE_REFERENCE_SOURCE_TEMPLATE_ID_UNAVAILABLE");
      }
      finalMutationByTemplate.put(templateId, mutation);
    }
    for (var mutationEntry : finalMutationByTemplate.entrySet()) {
      String templateId = mutationEntry.getKey();
      TemplateConfigSource.Mutation mutation = mutationEntry.getValue();
      TemplateConfigSource.Entry current =
          snapshot.entries().stream()
              .filter(entry -> entry.templateId().equals(templateId))
              .findFirst()
              .orElse(null);
      if (current == null) {
        if (mutation.operation() != TemplateConfigSource.OperationKind.DELETE) {
          throw new IllegalStateException("TEMPLATE_REFERENCE_SOURCE_ENTRY_MISSING");
        }
        dsl.execute(
            "DELETE FROM game_template_version_ref WHERE canonical_tenant_id = ? AND template_id = ?",
            target.canonicalTenantId(),
            Long.parseLong(templateId));
        continue;
      }
      if (mutation.operation() == TemplateConfigSource.OperationKind.DELETE
          || !current.sourceBinding().equals(binding)
          || !current.config().baseVersionId().equals(target.canonicalVersionId())) {
        throw new IllegalStateException("TEMPLATE_REFERENCE_SOURCE_ENTRY_PROVENANCE_CONFLICT");
      }
      int written =
          dsl.execute(
              "INSERT INTO game_template_version_ref (canonical_tenant_id, template_id, canonical_version_id, "
                  + "source_commit_id, source_revision_id) VALUES (?, ?, ?, ?, ?) "
                  + "ON CONFLICT (canonical_tenant_id, template_id) DO UPDATE SET "
                  + "source_commit_id = EXCLUDED.source_commit_id, "
                  + "source_revision_id = EXCLUDED.source_revision_id",
              target.canonicalTenantId(),
              Long.parseLong(templateId),
              target.canonicalVersionId(),
              current.sourceBinding().commitId(),
              current.revisionId());
      if (written != 1) {
        throw new IllegalStateException("TEMPLATE_REFERENCE_BASE_PROJECTION_WRITE_CONFLICT");
      }
      Record readback =
          dsl.fetchOne(
              "SELECT canonical_version_id, source_commit_id, source_revision_id "
                  + "FROM game_template_version_ref WHERE canonical_tenant_id = ? AND template_id = ?",
              target.canonicalTenantId(),
              Long.parseLong(templateId));
      if (readback == null
          || !target.canonicalVersionId().equals(readback.get("canonical_version_id", UUID.class))
          || !current
              .sourceBinding()
              .commitId()
              .equals(readback.get("source_commit_id", UUID.class))
          || !current.revisionId().equals(readback.get("source_revision_id", UUID.class))) {
        throw new IllegalStateException("TEMPLATE_REFERENCE_BASE_PROJECTION_READBACK_CONFLICT");
      }
    }
  }

  /**
   * Exact current projection read for later launch-consumer integration; no fallback is provided.
   */
  public Optional<BaseReference> readExactBaseReference(UUID canonicalTenantId, long templateId) {
    requireCanonicalTenantId(canonicalTenantId);
    if (templateId <= 0) throw new IllegalArgumentException("Positive template row id required");
    Record row =
        dsl.fetchOne(
            "SELECT r.canonical_tenant_id, r.template_id, r.canonical_version_id, "
                + "r.source_commit_id, r.source_revision_id, t.tenant_id "
                + "FROM game_template_version_ref r JOIN game_templates t ON t.id = r.template_id "
                + "JOIN game g ON g.tenant_id = t.tenant_id AND g.canonical_tenant_id = r.canonical_tenant_id "
                + "WHERE r.canonical_tenant_id = ? AND r.template_id = ?",
            canonicalTenantId,
            templateId);
    if (row == null) return Optional.empty();
    if (!canonicalTenantId.equals(row.get("canonical_tenant_id", UUID.class))
        || templateId != row.get("template_id", Long.class)) {
      throw new IllegalStateException("TEMPLATE_REFERENCE_BASE_READBACK_SCOPE_CONFLICT");
    }
    return Optional.of(
        new BaseReference(
            canonicalTenantId,
            templateId,
            row.get("canonical_version_id", UUID.class),
            row.get("source_commit_id", UUID.class),
            row.get("source_revision_id", UUID.class)));
  }

  private InventoryEvidence readCompleteInventory(GameOwner owner) {
    List<Record> rows =
        dsl.fetch(
            "SELECT t.id, t.tenant_id, t.config::TEXT AS config_json, t.default_version_id, "
                + "q.canonical_tenant_id, q.canonical_version_id "
                + "FROM game_templates t LEFT JOIN game_design_template_config_source_qualification q "
                + "ON q.template_id = t.id WHERE t.tenant_id = ? ORDER BY t.id",
            owner.tenantKey());
    if (rows.isEmpty()) {
      throw new IllegalStateException("TEMPLATE_REFERENCE_INVENTORY_EMPTY");
    }

    Map<UUID, VersionScope> scopes = new TreeMap<>();
    Map<UUID, Map<Long, TemplateRow>> templatesByVersion = new HashMap<>();
    List<TemplateRow> completeTemplates = new ArrayList<>();
    for (Record row : rows) {
      Long templateId = row.get("id", Long.class);
      Long privateVersionId = row.get("default_version_id", Long.class);
      UUID rowTenantId = row.get("canonical_tenant_id", UUID.class);
      UUID canonicalVersionId = row.get("canonical_version_id", UUID.class);
      if (templateId == null
          || privateVersionId == null
          || !owner.canonicalTenantId().equals(rowTenantId)
          || canonicalVersionId == null) {
        throw new IllegalStateException("TEMPLATE_REFERENCE_INVENTORY_UNQUALIFIED");
      }
      VersionScope scope = scopes.get(canonicalVersionId);
      if (scope == null) {
        scope = findVersionScope(owner, canonicalVersionId, privateVersionId);
        scopes.put(canonicalVersionId, scope);
      } else if (scope.privateVersionId() != privateVersionId) {
        throw new IllegalStateException("TEMPLATE_REFERENCE_INVENTORY_VERSION_CONFLICT");
      }
      TemplateRow template =
          new TemplateRow(
              templateId,
              privateVersionId,
              canonicalVersionId,
              row.get("config_json", String.class));
      completeTemplates.add(template);
      if (templatesByVersion
              .computeIfAbsent(canonicalVersionId, ignored -> new HashMap<>())
              .put(templateId, template)
          != null) {
        throw new IllegalStateException("TEMPLATE_REFERENCE_INVENTORY_DUPLICATE_TEMPLATE");
      }
    }

    List<Map<String, Object>> evidenceTemplates = new ArrayList<>();
    Set<Long> sourceEntryTemplateIds = new HashSet<>();
    TemplateConfigSourceRepository sourceRepository = new TemplateConfigSourceRepository(dsl);
    for (var scopeEntry : scopes.entrySet()) {
      UUID canonicalVersionId = scopeEntry.getKey();
      VersionScope scope = scopeEntry.getValue();
      Record head =
          dsl.fetchOne(
              "SELECT visible_commit_id FROM game_design_template_config_source_head "
                  + "WHERE canonical_tenant_id = ? AND canonical_version_id = ?",
              owner.canonicalTenantId(),
              canonicalVersionId);
      UUID visibleCommitId = head == null ? null : head.get("visible_commit_id", UUID.class);
      if (visibleCommitId == null) {
        throw new IllegalStateException("TEMPLATE_REFERENCE_SOURCE_SNAPSHOT_UNAVAILABLE");
      }
      TemplateConfigSourceSnapshot snapshot =
          sourceRepository
              .readSnapshot(scope.target(), visibleCommitId)
              .orElseThrow(
                  () ->
                      new IllegalStateException("TEMPLATE_REFERENCE_SOURCE_SNAPSHOT_UNAVAILABLE"));
      Map<Long, TemplateRow> versionTemplates = templatesByVersion.get(canonicalVersionId);
      if (snapshot.entries().size() != versionTemplates.size()) {
        throw new IllegalStateException("TEMPLATE_REFERENCE_INVENTORY_SOURCE_SET_CONFLICT");
      }
      for (TemplateConfigSource.Entry entry : snapshot.entries()) {
        long templateId = Long.parseLong(entry.templateId());
        TemplateRow template = versionTemplates.get(templateId);
        if (template == null
            || !entry.sourceBinding().target().equals(scope.target())
            || !entry.config().baseVersionId().equals(canonicalVersionId)
            || !new TemplateConfigSource.Config(template.configJson())
                .canonicalJson()
                .equals(entry.config().canonicalJson())
            || !sourceEntryTemplateIds.add(templateId)) {
          throw new IllegalStateException("TEMPLATE_REFERENCE_INVENTORY_SOURCE_SET_CONFLICT");
        }
        entry.config().requireAvailableOwnerReads();
        requireExactProjection(owner.canonicalTenantId(), template, snapshot, entry);
        evidenceTemplates.add(
            Map.of(
                "templateId", Long.toString(templateId),
                "canonicalVersionId", canonicalVersionId.toString(),
                "sourceCommitId", entry.sourceBinding().commitId().toString(),
                "sourceRevisionId", entry.revisionId().toString(),
                "sourceSnapshotDigest", snapshot.digest(),
                "configJson", entry.config().canonicalJson()));
      }
    }
    if (sourceEntryTemplateIds.size() != completeTemplates.size()) {
      throw new IllegalStateException("TEMPLATE_REFERENCE_INVENTORY_SOURCE_SET_CONFLICT");
    }
    String canonicalEvidence =
        CommandSource.canonical(
            Map.of(
                "schema", "game-design-template-reference-inventory/v1",
                "canonicalTenantId", owner.canonicalTenantId().toString(),
                "sourceGameRowId", Long.toString(owner.gameRowId()),
                "sourceGameTenantKey", owner.tenantKey(),
                "templates", evidenceTemplates));
    return new InventoryEvidence(
        CommandSource.sha256(canonicalEvidence.getBytes(StandardCharsets.UTF_8)),
        completeTemplates.size());
  }

  private VersionScope findVersionScope(
      GameOwner owner, UUID canonicalVersionId, long expectedPrivateVersionId) {
    Record version =
        dsl.fetchOne(
            "SELECT v.id, v.tenant_id, v.canonical_tenant_id, v.canonical_version_id, "
                + "v.identity_source_game_row_id, v.identity_source_game_tenant_key, "
                + "v.identity_source_provenance_kind FROM version v JOIN game g "
                + "ON g.id = v.identity_source_game_row_id "
                + "AND g.tenant_id = v.identity_source_game_tenant_key "
                + "AND g.canonical_tenant_id = v.canonical_tenant_id "
                + "AND g.tenant_identity_provenance_kind = v.identity_source_provenance_kind "
                + "WHERE v.canonical_tenant_id = ? AND v.canonical_version_id = ?",
            owner.canonicalTenantId(),
            canonicalVersionId);
    if (version == null
        || expectedPrivateVersionId != version.get("id", Long.class)
        || !owner.tenantKey().equals(version.get("tenant_id", String.class))
        || owner.gameRowId() != version.get("identity_source_game_row_id", Long.class)
        || !owner.tenantKey().equals(version.get("identity_source_game_tenant_key", String.class))
        || !owner
            .provenanceKind()
            .equals(version.get("identity_source_provenance_kind", String.class))) {
      throw new IllegalStateException("TEMPLATE_REFERENCE_INVENTORY_VERSION_PROVENANCE_CONFLICT");
    }
    TargetProof target =
        new TargetProof(
            version.get("canonical_tenant_id", UUID.class),
            version.get("canonical_version_id", UUID.class),
            version.get("id", Long.class),
            version.get("tenant_id", String.class),
            version.get("identity_source_game_row_id", Long.class),
            version.get("identity_source_game_tenant_key", String.class),
            version.get("identity_source_provenance_kind", String.class));
    return new VersionScope(target, expectedPrivateVersionId);
  }

  private void requireExactProjection(
      UUID canonicalTenantId,
      TemplateRow template,
      TemplateConfigSourceSnapshot snapshot,
      TemplateConfigSource.Entry entry) {
    Record projection =
        dsl.fetchOne(
            "SELECT canonical_version_id, source_commit_id, source_revision_id "
                + "FROM game_template_version_ref WHERE canonical_tenant_id = ? AND template_id = ?",
            canonicalTenantId,
            template.templateId());
    if (projection == null
        || !template.canonicalVersionId().equals(projection.get("canonical_version_id", UUID.class))
        || !entry.sourceBinding().commitId().equals(projection.get("source_commit_id", UUID.class))
        || !entry.revisionId().equals(projection.get("source_revision_id", UUID.class))
        || !snapshot.binding().target().canonicalTenantId().equals(canonicalTenantId)) {
      throw new IllegalStateException("TEMPLATE_REFERENCE_BASE_PROJECTION_UNAVAILABLE");
    }
  }

  private GameOwner lockGameOwner(UUID canonicalTenantId) {
    Record game =
        dsl.fetchOne(
            "SELECT id, tenant_id, canonical_tenant_id, tenant_identity_provenance_kind, "
                + "tenant_identity_source_game_id, tenant_identity_source_legacy_tenant_id "
                + "FROM game WHERE canonical_tenant_id = ? FOR UPDATE",
            canonicalTenantId);
    if (game == null
        || game.get("id", Long.class) == null
        || game.get("id", Long.class) <= 0
        || !canonicalTenantId.equals(game.get("canonical_tenant_id", UUID.class))
        || !game.get("id", Long.class)
            .equals(game.get("tenant_identity_source_game_id", Long.class))
        || !game.get("tenant_id", String.class)
            .equals(game.get("tenant_identity_source_legacy_tenant_id", String.class))) {
      throw new IllegalStateException("TEMPLATE_REFERENCE_GAME_OWNER_UNAVAILABLE");
    }
    return new GameOwner(
        game.get("id", Long.class),
        game.get("tenant_id", String.class),
        canonicalTenantId,
        game.get("tenant_identity_provenance_kind", String.class));
  }

  private void requirePhaseOwnerMatches(PhaseSnapshot phase, GameOwner owner) {
    if (phase.sourceGameRowId() != owner.gameRowId()
        || !phase.sourceGameTenantKey().equals(owner.tenantKey())
        || !"NEW_GAME_ROW".equals(owner.provenanceKind())
        || !"NEW_GAME_ROW".equals(phase.provenanceKind())) {
      throw new IllegalStateException("TEMPLATE_REFERENCE_PHASE_GAME_OWNER_CONFLICT");
    }
  }

  private Optional<PhaseSnapshot> readPhaseForUpdate(UUID canonicalTenantId) {
    Record row =
        dsl.fetchOne(
            "SELECT * FROM game_template_reference_phase WHERE canonical_tenant_id = ? FOR UPDATE",
            canonicalTenantId);
    return row == null ? Optional.empty() : Optional.of(phaseSnapshot(row));
  }

  private PhaseSnapshot phaseSnapshot(Record row) {
    return new PhaseSnapshot(
        row.get("canonical_tenant_id", UUID.class),
        Phase.valueOf(row.get("phase", String.class)),
        row.get("phase_epoch", Long.class),
        row.get("inventory_digest", String.class),
        row.get("inventory_template_count", Long.class),
        row.get("creation_operation_id", UUID.class),
        row.get("creation_request_id", UUID.class),
        row.get("source_game_row_id", Long.class),
        row.get("source_game_tenant_key", String.class),
        row.get("creation_evidence_digest", String.class),
        "NEW_GAME_ROW");
  }

  private void requireSameCreation(PhaseSnapshot phase, FreshTenantCreationEvidence evidence) {
    if (!phase.canonicalTenantId().equals(evidence.canonicalTenantId())
        || !phase.creationOperationId().equals(evidence.operationId())
        || !phase.creationRequestId().equals(evidence.creationRequestId())
        || phase.sourceGameRowId() != evidence.sourceGameRowId()
        || !phase.sourceGameTenantKey().equals(evidence.sourceGameTenantKey())
        || !phase.creationEvidenceDigest().equals(evidence.evidenceDigest())) {
      throw new IllegalStateException("TEMPLATE_REFERENCE_PHASE_CREATION_READBACK_CONFLICT");
    }
  }

  private void requireCanonicalTenantId(UUID canonicalTenantId) {
    Objects.requireNonNull(canonicalTenantId, "canonicalTenantId");
    if (canonicalTenantId.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException("Canonical tenant identity is required");
    }
  }

  private static void requireWritableReadCommittedTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        || !Integer.valueOf(TransactionDefinition.ISOLATION_READ_COMMITTED)
            .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) {
      throw new IllegalStateException(
          "Template reference writes require a writable READ_COMMITTED owner transaction");
    }
  }

  private static void requireWritableTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Template reference creation requires a writable Game Design owner transaction");
    }
  }

  public enum Phase {
    BACKFILLING,
    VALIDATED,
    ENFORCED
  }

  public record PhaseSnapshot(
      UUID canonicalTenantId,
      Phase phase,
      long phaseEpoch,
      String inventoryDigest,
      Long inventoryTemplateCount,
      UUID creationOperationId,
      UUID creationRequestId,
      long sourceGameRowId,
      String sourceGameTenantKey,
      String creationEvidenceDigest,
      String provenanceKind) {}

  public record BaseReference(
      UUID canonicalTenantId,
      long templateId,
      UUID canonicalVersionId,
      UUID sourceCommitId,
      UUID sourceRevisionId) {}

  private record GameOwner(
      long gameRowId, String tenantKey, UUID canonicalTenantId, String provenanceKind) {}

  private record VersionScope(TargetProof target, long privateVersionId) {}

  private record TemplateRow(
      long templateId, long privateVersionId, UUID canonicalVersionId, String configJson) {}

  private record InventoryEvidence(String digest, long templateCount) {}
}
