package net.firedevops.firemud.gamelogic.authoring;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Game Logic owner-local insert-only persistence and exact committed readback.
 *
 * <p>This repository is intentionally not a Spring component. Its one-row-per-Version insert is the
 * epoch-zero compare-and-swap; the row retains the incremented aggregate and scope epochs,
 * immutable manifest, complete binding, source proof, and exact local owner result atomically.
 */
public final class GameLogicRuleInputManifestRepository {
  private static final String TABLE = "game_logic_version_rule_input_manifest";
  private static final String INSERT_SQL =
      "INSERT INTO "
          + TABLE
          + " (canonical_tenant_id, canonical_version_id, source_proof_json, "
          + "game_design_version_row_id, game_design_version_tenant_key, source_game_row_id, "
          + "source_game_tenant_key, source_provenance_kind, request_id, commit_id, base_commit_id, "
          + "revision_id, binding_json, binding_digest, owner_intent_json, manifest_json, "
          + "digest_schema_version, content_digest, ability_schema_version, ability_schema_json, "
          + "ability_schema_digest, aggregate_type, aggregate_id, scope_type, scope_id, "
          + "expected_epoch, aggregate_epoch_after, scope_epoch_after, owner_result_json, "
          + "owner_result_digest) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
          + "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING";
  private static final String BY_VERSION_SQL =
      "SELECT * FROM "
          + TABLE
          + " WHERE canonical_tenant_id = ? AND canonical_version_id = ? FOR UPDATE";
  private static final String READ_SQL =
      "SELECT * FROM " + TABLE + " WHERE canonical_tenant_id = ? AND canonical_version_id = ?";

  private final DSLContext dsl;
  private final TransactionTemplate transaction;

  public GameLogicRuleInputManifestRepository(
      DSLContext dsl, PlatformTransactionManager transactionManager) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    transaction =
        new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  /**
   * Claims epoch zero once, or validates an exact retry against the immutable original row. The
   * authority handle must remain valid until the transaction manager has committed.
   */
  public void apply(
      GameLogicRuleInputManifest input,
      GameLogicEmptyRuleInputManifestService.HeldCommitAuthority authority) {
    Objects.requireNonNull(input, "input");
    Objects.requireNonNull(authority, "authority");
    requireNoActiveTransaction("Game Logic rule manifest apply");

    transaction.executeWithoutResult(
        status -> {
          authority.requireHeld();
          int inserted =
              dsl.execute(
                  INSERT_SQL,
                  input.tenantId(),
                  input.versionId(),
                  input.sourceProofJson(),
                  input.binding().target().gameDesignVersionRowId(),
                  input.binding().target().gameDesignVersionTenantKey(),
                  input.binding().target().sourceGameRowId(),
                  input.binding().target().sourceGameTenantKey(),
                  input.binding().target().sourceProvenanceKind(),
                  input.requestId(),
                  input.commitId(),
                  input.baseCommitId(),
                  input.revisionId(),
                  input.binding().canonicalJson(),
                  input.binding().digest(),
                  input.ownerIntentJson(),
                  input.manifestJson(),
                  input.digestSchemaVersion(),
                  input.contentDigest(),
                  input.abilitySchemaVersion(),
                  input.abilitySchemaJson(),
                  input.abilitySchemaDigest(),
                  GameLogicRuleInputManifest.AGGREGATE_TYPE,
                  input.versionId().toString(),
                  GameLogicRuleInputManifest.SCOPE_TYPE,
                  input.versionId().toString(),
                  GameLogicRuleInputManifest.INITIAL_EPOCH,
                  GameLogicRuleInputManifest.APPLIED_EPOCH,
                  GameLogicRuleInputManifest.APPLIED_EPOCH,
                  input.ownerResultJson(),
                  input.ownerResultDigest());
          if (inserted == 0) {
            Record existing = dsl.fetchOne(BY_VERSION_SQL, input.tenantId(), input.versionId());
            if (existing == null || !input.equals(decodeAndVerify(existing))) {
              throw new ManifestConflictException(
                  "Game Logic Version manifest identity is already bound to different input");
            }
          }
          authority.requireHeld();
        });

    // TransactionTemplate returns only after commit. Readback is deliberately outside that
    // transaction and does not imply that the protected publication RPC is enabled.
    authority.requireHeld();
    GameLogicRuleInputManifest committed =
        read(input.tenantId(), input.versionId())
            .orElseThrow(
                () ->
                    new InvalidStoredManifestException(
                        "Committed Game Logic manifest has no exact owner readback"));
    if (!input.equals(committed)) {
      throw new InvalidStoredManifestException(
          "Committed Game Logic manifest readback differs from its exact input");
    }
  }

  /** Internal exact manifest/digest readback for future owner composition. */
  public Optional<GameLogicRuleInputManifest> read(UUID tenantId, UUID versionId) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(versionId, "versionId");
    requireNoActiveTransaction("Game Logic rule manifest readback");
    Record row = dsl.fetchOne(READ_SQL, tenantId, versionId);
    return row == null ? Optional.empty() : Optional.of(decodeAndVerify(row));
  }

  private GameLogicRuleInputManifest decodeAndVerify(Record row) {
    String bindingJson = required(row, "binding_json", String.class);
    String bindingDigest = required(row, "binding_digest", String.class);
    DraftCommitBinding binding = DraftCommitBinding.fromStored(bindingJson, bindingDigest);
    GameLogicRuleInputManifest expected =
        GameLogicRuleInputManifest.fromExplicitEmptyIntent(binding);

    requireEqual(row, "canonical_tenant_id", UUID.class, expected.tenantId());
    requireEqual(row, "canonical_version_id", UUID.class, expected.versionId());
    requireEqual(row, "source_proof_json", String.class, expected.sourceProofJson());
    requireEqual(
        row, "game_design_version_row_id", Long.class, binding.target().gameDesignVersionRowId());
    requireEqual(
        row,
        "game_design_version_tenant_key",
        String.class,
        binding.target().gameDesignVersionTenantKey());
    requireEqual(row, "source_game_row_id", Long.class, binding.target().sourceGameRowId());
    requireEqual(
        row, "source_game_tenant_key", String.class, binding.target().sourceGameTenantKey());
    requireEqual(
        row, "source_provenance_kind", String.class, binding.target().sourceProvenanceKind());
    requireEqual(row, "request_id", UUID.class, expected.requestId());
    requireEqual(row, "commit_id", UUID.class, expected.commitId());
    requireEqual(row, "base_commit_id", String.class, expected.baseCommitId());
    requireEqual(row, "revision_id", UUID.class, expected.revisionId());
    requireEqual(row, "owner_intent_json", String.class, expected.ownerIntentJson());
    requireEqual(row, "manifest_json", String.class, expected.manifestJson());
    requireEqual(row, "digest_schema_version", Integer.class, expected.digestSchemaVersion());
    requireEqual(row, "content_digest", String.class, expected.contentDigest());
    requireEqual(row, "ability_schema_version", Integer.class, expected.abilitySchemaVersion());
    requireEqual(row, "ability_schema_json", String.class, expected.abilitySchemaJson());
    requireEqual(row, "ability_schema_digest", String.class, expected.abilitySchemaDigest());
    requireEqual(row, "aggregate_type", String.class, GameLogicRuleInputManifest.AGGREGATE_TYPE);
    requireEqual(row, "aggregate_id", String.class, expected.versionId().toString());
    requireEqual(row, "scope_type", String.class, GameLogicRuleInputManifest.SCOPE_TYPE);
    requireEqual(row, "scope_id", String.class, expected.versionId().toString());
    requireEqual(row, "expected_epoch", Long.class, GameLogicRuleInputManifest.INITIAL_EPOCH);
    requireEqual(
        row, "aggregate_epoch_after", Long.class, GameLogicRuleInputManifest.APPLIED_EPOCH);
    requireEqual(row, "scope_epoch_after", Long.class, GameLogicRuleInputManifest.APPLIED_EPOCH);
    requireEqual(row, "owner_result_json", String.class, expected.ownerResultJson());
    requireEqual(row, "owner_result_digest", String.class, expected.ownerResultDigest());
    return expected;
  }

  private static <T> T required(Record row, String name, Class<T> type) {
    T value = row.get(name, type);
    if (value == null) {
      throw new InvalidStoredManifestException("Stored Game Logic manifest is missing " + name);
    }
    return value;
  }

  private static <T> void requireEqual(Record row, String name, Class<T> type, T expected) {
    if (!Objects.equals(required(row, name, type), expected)) {
      throw new InvalidStoredManifestException(
          "Stored Game Logic manifest has inconsistent " + name);
    }
  }

  private static void requireNoActiveTransaction(String operation) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(operation + " must use an independent committed boundary");
    }
  }

  public static final class ManifestConflictException extends IllegalStateException {
    public ManifestConflictException(String message) {
      super(message);
    }
  }

  public static final class InvalidStoredManifestException extends IllegalStateException {
    public InvalidStoredManifestException(String message) {
      super(message);
    }

    public InvalidStoredManifestException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
