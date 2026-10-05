package net.firedevops.firemud.gamedesign.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Owner-local immutable selector-source registration and exact committed readback. */
@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class GameAuthoredWorldSourceRepository {
  private static final int SCHEMA_VERSION = 1;
  private static final String NEW_GAME_ROW = "NEW_GAME_ROW";
  private static final String RETAINED_GAME_V30 = "RETAINED_GAME_V30";

  private static final Table<?> GAME = DSL.table(DSL.name("game"));
  private static final Field<Long> GAME_ID = DSL.field(DSL.name("id"), Long.class);
  private static final Field<String> GAME_TENANT_ID =
      DSL.field(DSL.name("tenant_id"), String.class);
  private static final Field<UUID> GAME_CANONICAL_TENANT_ID =
      DSL.field(DSL.name("canonical_tenant_id"), UUID.class);
  private static final Field<String> GAME_PROVENANCE_KIND =
      DSL.field(DSL.name("tenant_identity_provenance_kind"), String.class);
  private static final Field<Long> GAME_SOURCE_ROW_ID =
      DSL.field(DSL.name("tenant_identity_source_game_id"), Long.class);
  private static final Field<String> GAME_SOURCE_TENANT_KEY =
      DSL.field(DSL.name("tenant_identity_source_legacy_tenant_id"), String.class);

  private static final Table<?> TENANT_SLUG_BINDING =
      DSL.table(DSL.name("game_design_tenant_slug_binding"));
  private static final Field<String> BINDING_NAMESPACE =
      DSL.field(DSL.name("target_namespace"), String.class);
  private static final Field<UUID> BINDING_CANONICAL_TENANT_ID =
      DSL.field(DSL.name("canonical_tenant_id"), UUID.class);
  private static final Field<String> BINDING_TENANT_SLUG =
      DSL.field(DSL.name("tenant_slug"), String.class);
  private static final Field<Long> BINDING_SOURCE_GAME_ROW_ID =
      DSL.field(DSL.name("source_game_row_id"), Long.class);
  private static final Field<String> BINDING_SOURCE_GAME_TENANT_KEY =
      DSL.field(DSL.name("source_game_tenant_key"), String.class);
  private static final Field<String> BINDING_PROVENANCE_KIND =
      DSL.field(DSL.name("provenance_kind"), String.class);

  private static final Table<?> OPERATION =
      DSL.table(DSL.name("game_design_authored_world_source_operations"));
  private static final Field<UUID> OPERATION_ID = DSL.field(DSL.name("operation_id"), UUID.class);
  private static final Field<Integer> OPERATION_SCHEMA_VERSION =
      DSL.field(DSL.name("schema_version"), Integer.class);
  private static final Field<String> OPERATION_NAMESPACE =
      DSL.field(DSL.name("target_namespace"), String.class);
  private static final Field<UUID> REGISTRATION_REQUEST_ID =
      DSL.field(DSL.name("registration_request_id"), UUID.class);
  private static final Field<String> REQUEST_DIGEST =
      DSL.field(DSL.name("request_digest"), String.class);
  private static final Field<UUID> CANONICAL_TENANT_ID =
      DSL.field(DSL.name("canonical_tenant_id"), UUID.class);
  private static final Field<String> TENANT_SLUG = DSL.field(DSL.name("tenant_slug"), String.class);
  private static final Field<String> WORLD_SLUG = DSL.field(DSL.name("world_slug"), String.class);
  private static final Field<String> WORLD_DISPLAY_NAME =
      DSL.field(DSL.name("world_display_name"), String.class);
  private static final Field<Long> SOURCE_GAME_ROW_ID =
      DSL.field(DSL.name("source_game_row_id"), Long.class);
  private static final Field<String> SOURCE_GAME_TENANT_KEY =
      DSL.field(DSL.name("source_game_tenant_key"), String.class);
  private static final Field<String> PROVENANCE_KIND =
      DSL.field(DSL.name("provenance_kind"), String.class);
  private static final Field<String> EVIDENCE_DIGEST =
      DSL.field(DSL.name("evidence_digest"), String.class);

  private static final Table<?> VERSION = DSL.table(DSL.name("version"));
  private static final Field<Long> VERSION_ID = DSL.field(DSL.name("id"), Long.class);
  private static final Field<String> VERSION_TENANT_ID =
      DSL.field(DSL.name("tenant_id"), String.class);
  private static final Field<String> VERSION_STATE =
      DSL.field(DSL.name("version_state"), String.class);
  private static final Field<Long> VERSION_STATE_EPOCH =
      DSL.field(DSL.name("version_state_epoch"), Long.class);
  private final DSLContext dsl;

  public GameAuthoredWorldSourceRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  /**
   * Binds explicit authored-world selectors to one exact persisted Game Design tenant source.
   *
   * <p>The caller must own the encompassing Game Design transaction. The actual source game row is
   * locked before request replay checks and selector claims, serializing registrations for one
   * tenant without treating a template, display name, or legacy key as a selector.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public AuthoredWorldSourceEvidence register(
      String targetNamespace,
      UUID registrationRequestId,
      UUID canonicalTenantId,
      String tenantSlug,
      String worldSlug,
      String worldDisplayName) {
    requireActiveOwnerTransaction();
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            targetNamespace,
            registrationRequestId,
            canonicalTenantId,
            tenantSlug,
            worldSlug,
            worldDisplayName);

    GameSource source = lockGameSource(canonicalTenantId);
    if (source == null) {
      throw new RegistrationConflictException("Canonical tenant source game row is missing");
    }
    requireConsistentGameSource(source);

    Record existingRequest = findByRegistrationRequest(targetNamespace, registrationRequestId);
    if (existingRequest != null) {
      AuthoredWorldSourceEvidence receipt = toEvidence(existingRequest);
      if (!requestDigest.equals(receipt.requestDigest())
          || !canonicalTenantId.equals(receipt.canonicalTenantId())
          || !tenantSlug.equals(receipt.tenantSlug())
          || !worldSlug.equals(receipt.worldSlug())
          || !worldDisplayName.equals(receipt.worldDisplayName())) {
        throw new RegistrationConflictException(
            "Authored-world registration identity was reused with changed input");
      }
      requireReceiptSource(receipt, source);
      requireTenantBinding(receipt);
      return receipt;
    }

    ensureTenantSlugBinding(targetNamespace, tenantSlug, source);
    UUID operationId = UUID.randomUUID();
    String evidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            targetNamespace,
            registrationRequestId,
            operationId,
            requestDigest,
            source.canonicalTenantId(),
            tenantSlug,
            worldSlug,
            worldDisplayName,
            source.sourceGameRowId(),
            source.sourceGameTenantKey(),
            source.provenanceKind());

    int inserted =
        dsl.insertInto(OPERATION)
            .set(OPERATION_ID, operationId)
            .set(OPERATION_SCHEMA_VERSION, SCHEMA_VERSION)
            .set(OPERATION_NAMESPACE, targetNamespace)
            .set(REGISTRATION_REQUEST_ID, registrationRequestId)
            .set(REQUEST_DIGEST, requestDigest)
            .set(CANONICAL_TENANT_ID, source.canonicalTenantId())
            .set(TENANT_SLUG, tenantSlug)
            .set(WORLD_SLUG, worldSlug)
            .set(WORLD_DISPLAY_NAME, worldDisplayName)
            .set(SOURCE_GAME_ROW_ID, source.sourceGameRowId())
            .set(SOURCE_GAME_TENANT_KEY, source.sourceGameTenantKey())
            .set(PROVENANCE_KIND, source.provenanceKind())
            .set(EVIDENCE_DIGEST, evidenceDigest)
            .onConflictDoNothing()
            .execute();
    if (inserted == 0) {
      Record requestConflict = findByRegistrationRequest(targetNamespace, registrationRequestId);
      if (requestConflict != null) {
        throw new RegistrationConflictException(
            "Authored-world registration identity was reused with changed input");
      }
      Record selectorConflict = findByWorldSelector(targetNamespace, canonicalTenantId, worldSlug);
      if (selectorConflict != null) {
        throw new RegistrationConflictException(
            "Authored-world selector is already owned by another source operation");
      }
      throw new RegistrationConflictException(
          "Authored-world source operation conflicts with an existing owner claim");
    }

    Record persisted = findByRegistrationRequest(targetNamespace, registrationRequestId);
    if (persisted == null) {
      throw new IllegalStateException("Committed authored-world source operation is missing");
    }
    AuthoredWorldSourceEvidence receipt = toEvidence(persisted);
    requireReceiptSource(receipt, source);
    requireTenantBinding(receipt);
    return receipt;
  }

  /** Reads one exact committed source operation without allocating or repairing selector state. */
  @Transactional(propagation = Propagation.NOT_SUPPORTED, readOnly = true)
  public Optional<AuthoredWorldSourceEvidence> read(
      UUID operationId, UUID canonicalTenantId, String worldSlug, String targetNamespace) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Authored-world source read requires a committed-outcome owner read");
    }
    AuthoredWorldSourceDigest.validateReadSelector(targetNamespace, canonicalTenantId, worldSlug);
    if (operationId == null || operationId.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException("operationId must be a canonical non-nil UUID");
    }
    Record record =
        dsl.selectFrom(OPERATION)
            .where(
                OPERATION_ID
                    .eq(operationId)
                    .and(CANONICAL_TENANT_ID.eq(canonicalTenantId))
                    .and(WORLD_SLUG.eq(worldSlug))
                    .and(OPERATION_NAMESPACE.eq(targetNamespace)))
            .fetchOne();
    if (record == null) {
      return Optional.empty();
    }
    AuthoredWorldSourceEvidence receipt = toEvidence(record);
    GameSource source = findGameSourceByRow(receipt.sourceGameRowId());
    if (source == null) {
      throw new InvalidSourceEvidenceException("Authored-world source row is missing");
    }
    requireReceiptSource(receipt, source);
    requireTenantBinding(receipt);
    return Optional.of(receipt);
  }

  /**
   * Reads the exact source receipt and its source-owned version from the caller's owner snapshot.
   *
   * <p>This method is intentionally separate from {@link #read}: the latter suspends any ambient
   * transaction to read a committed outcome, while this owner-current read requires the dedicated
   * read-only REPEATABLE_READ transaction opened by AuthoredWorldVersionStateService.
   */
  @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
  public Optional<AuthoredWorldVersionStateSnapshot> readVersionStateSnapshot(
      String targetNamespace,
      UUID readRequestId,
      UUID canonicalTenantId,
      String worldSlug,
      UUID sourceOperationId,
      String expectedSourceEvidenceDigest,
      long versionId) {
    requireOwnerSnapshot();
    AuthoredWorldSourceDigest.validateReadSelector(targetNamespace, canonicalTenantId, worldSlug);
    requireNonNil(readRequestId, "readRequestId");
    requireNonNil(sourceOperationId, "sourceOperationId");
    if (readRequestId.equals(sourceOperationId)) {
      throw new IllegalArgumentException("readRequestId must be separate from sourceOperationId");
    }
    if (!GameTenantCreationDigest.isDigest(expectedSourceEvidenceDigest)) {
      throw new IllegalArgumentException("Expected source evidence digest is not canonical");
    }
    if (versionId <= 0) {
      throw new IllegalArgumentException("versionId must be positive");
    }

    Record sourceRecord =
        dsl.selectFrom(OPERATION).where(OPERATION_ID.eq(sourceOperationId)).fetchOne();
    if (sourceRecord == null) {
      return Optional.empty();
    }
    AuthoredWorldSourceEvidence receipt = toEvidence(sourceRecord);
    if (!targetNamespace.equals(receipt.targetNamespace())
        || !canonicalTenantId.equals(receipt.canonicalTenantId())
        || !worldSlug.equals(receipt.worldSlug())
        || !sourceOperationId.equals(receipt.operationId())) {
      throw new InvalidSourceEvidenceException(
          "Authored-world source operation does not match the exact requested scope");
    }
    if (!expectedSourceEvidenceDigest.equals(receipt.evidenceDigest())) {
      throw new InvalidSourceEvidenceException(
          "Authored-world source evidence digest no longer matches the requested binding");
    }

    GameSource source = findGameSourceByRow(receipt.sourceGameRowId());
    if (source == null) {
      throw new InvalidSourceEvidenceException("Authored-world source game row is missing");
    }
    requireConsistentGameSource(source);
    requireReceiptSource(receipt, source);
    requireTenantBinding(receipt);

    Record versionRecord =
        dsl.select(VERSION_ID, VERSION_TENANT_ID, VERSION_STATE, VERSION_STATE_EPOCH)
            .from(VERSION)
            .where(VERSION_ID.eq(versionId))
            .fetchOne();
    if (versionRecord == null) {
      return Optional.empty();
    }
    String versionTenantId = versionRecord.get(VERSION_TENANT_ID);
    if (versionTenantId == null || !source.sourceGameTenantKey().equals(versionTenantId)) {
      throw new InvalidSourceEvidenceException(
          "Requested version is not owned by the exact Game Design source game");
    }

    VersionLifecycleState versionState;
    Long versionStateEpoch = versionRecord.get(VERSION_STATE_EPOCH);
    try {
      String versionStateName = versionRecord.get(VERSION_STATE);
      if (versionStateName == null || versionStateEpoch == null || versionStateEpoch <= 0) {
        throw new IllegalArgumentException("Version lifecycle state or epoch is invalid");
      }
      versionState = VersionLifecycleState.valueOf(versionStateName);
    } catch (IllegalArgumentException exception) {
      throw new InvalidSourceEvidenceException(
          "Persisted version lifecycle state or epoch is invalid", exception);
    }
    return Optional.of(
        new AuthoredWorldVersionStateSnapshot(receipt, versionState, versionStateEpoch));
  }

  private void ensureTenantSlugBinding(
      String targetNamespace, String tenantSlug, GameSource source) {
    Record existing =
        dsl.selectFrom(TENANT_SLUG_BINDING)
            .where(
                BINDING_NAMESPACE
                    .eq(targetNamespace)
                    .and(BINDING_CANONICAL_TENANT_ID.eq(source.canonicalTenantId())))
            .fetchOne();
    if (existing != null) {
      requireMatchingBinding(existing, targetNamespace, tenantSlug, source);
      return;
    }

    int inserted =
        dsl.insertInto(TENANT_SLUG_BINDING)
            .set(BINDING_NAMESPACE, targetNamespace)
            .set(BINDING_CANONICAL_TENANT_ID, source.canonicalTenantId())
            .set(BINDING_TENANT_SLUG, tenantSlug)
            .set(BINDING_SOURCE_GAME_ROW_ID, source.sourceGameRowId())
            .set(BINDING_SOURCE_GAME_TENANT_KEY, source.sourceGameTenantKey())
            .set(BINDING_PROVENANCE_KIND, source.provenanceKind())
            .onConflictDoNothing()
            .execute();
    if (inserted == 1) {
      return;
    }

    Record tenantBinding =
        dsl.selectFrom(TENANT_SLUG_BINDING)
            .where(
                BINDING_NAMESPACE
                    .eq(targetNamespace)
                    .and(BINDING_CANONICAL_TENANT_ID.eq(source.canonicalTenantId())))
            .fetchOne();
    if (tenantBinding != null) {
      requireMatchingBinding(tenantBinding, targetNamespace, tenantSlug, source);
      return;
    }
    Record slugOwner =
        dsl.selectFrom(TENANT_SLUG_BINDING)
            .where(BINDING_NAMESPACE.eq(targetNamespace).and(BINDING_TENANT_SLUG.eq(tenantSlug)))
            .fetchOne();
    if (slugOwner != null) {
      throw new RegistrationConflictException(
          "Tenant selector is already owned by another canonical tenant");
    }
    throw new RegistrationConflictException(
        "Tenant selector binding conflicts with an owner claim");
  }

  private void requireTenantBinding(AuthoredWorldSourceEvidence receipt) {
    Record binding =
        dsl.selectFrom(TENANT_SLUG_BINDING)
            .where(
                BINDING_NAMESPACE
                    .eq(receipt.targetNamespace())
                    .and(BINDING_CANONICAL_TENANT_ID.eq(receipt.canonicalTenantId())))
            .fetchOne();
    if (binding == null) {
      throw new InvalidSourceEvidenceException("Tenant selector binding is missing");
    }
    requireMatchingBinding(
        binding,
        receipt.targetNamespace(),
        receipt.tenantSlug(),
        new GameSource(
            receipt.sourceGameRowId(),
            receipt.canonicalTenantId(),
            receipt.sourceGameTenantKey(),
            receipt.provenanceKind()));
  }

  private void requireMatchingBinding(
      Record binding, String targetNamespace, String tenantSlug, GameSource source) {
    if (!targetNamespace.equals(binding.get(BINDING_NAMESPACE))
        || !source.canonicalTenantId().equals(binding.get(BINDING_CANONICAL_TENANT_ID))
        || !tenantSlug.equals(binding.get(BINDING_TENANT_SLUG))
        || source.sourceGameRowId() != binding.get(BINDING_SOURCE_GAME_ROW_ID)
        || !source.sourceGameTenantKey().equals(binding.get(BINDING_SOURCE_GAME_TENANT_KEY))
        || !source.provenanceKind().equals(binding.get(BINDING_PROVENANCE_KIND))) {
      throw new RegistrationConflictException(
          "Canonical tenant already has a different stable tenant selector or source tuple");
    }
  }

  private Record findByRegistrationRequest(String targetNamespace, UUID registrationRequestId) {
    return dsl.selectFrom(OPERATION)
        .where(
            OPERATION_NAMESPACE
                .eq(targetNamespace)
                .and(REGISTRATION_REQUEST_ID.eq(registrationRequestId)))
        .fetchOne();
  }

  private Record findByWorldSelector(
      String targetNamespace, UUID canonicalTenantId, String worldSlug) {
    return dsl.selectFrom(OPERATION)
        .where(
            OPERATION_NAMESPACE
                .eq(targetNamespace)
                .and(CANONICAL_TENANT_ID.eq(canonicalTenantId))
                .and(WORLD_SLUG.eq(worldSlug)))
        .fetchOne();
  }

  private GameSource lockGameSource(UUID canonicalTenantId) {
    Record row =
        dsl.select(
                GAME_ID,
                GAME_TENANT_ID,
                GAME_CANONICAL_TENANT_ID,
                GAME_PROVENANCE_KIND,
                GAME_SOURCE_ROW_ID,
                GAME_SOURCE_TENANT_KEY)
            .from(GAME)
            .where(GAME_CANONICAL_TENANT_ID.eq(canonicalTenantId))
            .forUpdate()
            .fetchOne();
    return row == null ? null : gameSource(row);
  }

  private GameSource findGameSourceByRow(long sourceGameRowId) {
    Record row =
        dsl.select(
                GAME_ID,
                GAME_TENANT_ID,
                GAME_CANONICAL_TENANT_ID,
                GAME_PROVENANCE_KIND,
                GAME_SOURCE_ROW_ID,
                GAME_SOURCE_TENANT_KEY)
            .from(GAME)
            .where(GAME_ID.eq(sourceGameRowId))
            .fetchOne();
    return row == null ? null : gameSource(row);
  }

  private GameSource gameSource(Record row) {
    Long id = row.get(GAME_ID);
    String tenantKey = row.get(GAME_TENANT_ID);
    UUID canonicalTenantId = row.get(GAME_CANONICAL_TENANT_ID);
    String provenanceKind = row.get(GAME_PROVENANCE_KIND);
    Long provenanceRowId = row.get(GAME_SOURCE_ROW_ID);
    String provenanceTenantKey = row.get(GAME_SOURCE_TENANT_KEY);
    if (id == null
        || tenantKey == null
        || canonicalTenantId == null
        || provenanceKind == null
        || provenanceRowId == null
        || provenanceTenantKey == null
        || !id.equals(provenanceRowId)
        || !tenantKey.equals(provenanceTenantKey)) {
      throw new InvalidSourceEvidenceException(
          "Game Design tenant source provenance does not match its game row");
    }
    return new GameSource(id, canonicalTenantId, tenantKey, provenanceKind);
  }

  private void requireConsistentGameSource(GameSource source) {
    if (!NEW_GAME_ROW.equals(source.provenanceKind())
        && !RETAINED_GAME_V30.equals(source.provenanceKind())) {
      throw new InvalidSourceEvidenceException("Game Design tenant provenance is not recognized");
    }
  }

  private void requireReceiptSource(AuthoredWorldSourceEvidence receipt, GameSource source) {
    if (receipt.sourceGameRowId() != source.sourceGameRowId()
        || !receipt.canonicalTenantId().equals(source.canonicalTenantId())
        || !receipt.sourceGameTenantKey().equals(source.sourceGameTenantKey())
        || !receipt.provenanceKind().equals(source.provenanceKind())) {
      throw new InvalidSourceEvidenceException(
          "Authored-world source tuple no longer matches its Game Design row");
    }
  }

  private AuthoredWorldSourceEvidence toEvidence(Record row) {
    try {
      return new AuthoredWorldSourceEvidence(
          row.get(OPERATION_SCHEMA_VERSION),
          row.get(OPERATION_NAMESPACE),
          row.get(REGISTRATION_REQUEST_ID),
          row.get(OPERATION_ID),
          row.get(REQUEST_DIGEST),
          row.get(CANONICAL_TENANT_ID),
          row.get(TENANT_SLUG),
          row.get(WORLD_SLUG),
          row.get(WORLD_DISPLAY_NAME),
          row.get(SOURCE_GAME_ROW_ID),
          row.get(SOURCE_GAME_TENANT_KEY),
          row.get(PROVENANCE_KIND),
          row.get(EVIDENCE_DIGEST));
    } catch (RuntimeException exception) {
      throw new InvalidSourceEvidenceException(
          "Persisted authored-world source evidence is invalid", exception);
    }
  }

  private void requireActiveOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Authored-world source registration requires an active Game Design owner transaction");
    }
  }

  private void requireOwnerSnapshot() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        || !Integer.valueOf(TransactionDefinition.ISOLATION_REPEATABLE_READ)
            .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) {
      throw new IllegalStateException(
          "Authored-world version-state read requires a read-only REPEATABLE_READ owner snapshot");
    }
  }

  private void requireNonNil(UUID value, String label) {
    if (value == null || value.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException(label + " must be a canonical non-nil UUID");
    }
  }

  public record AuthoredWorldVersionStateSnapshot(
      AuthoredWorldSourceEvidence sourceEvidence,
      VersionLifecycleState versionState,
      long versionStateEpoch) {}

  private record GameSource(
      long sourceGameRowId,
      UUID canonicalTenantId,
      String sourceGameTenantKey,
      String provenanceKind) {}

  public static final class RegistrationConflictException extends IllegalStateException {
    public RegistrationConflictException(String message) {
      super(message);
    }
  }

  public static final class InvalidSourceEvidenceException extends IllegalStateException {
    public InvalidSourceEvidenceException(String message) {
      super(message);
    }

    public InvalidSourceEvidenceException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
