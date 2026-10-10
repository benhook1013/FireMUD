package net.firedevops.firemud.worldmanagement.tenant;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Owner-local immutable association between a fresh authored Version UUID and a World key. */
public class WorldAuthoredVersionIdentityRepository {
  private static final Table<?> VERSION_IDENTITY =
      DSL.table(DSL.name("world_authored_version_identity"));
  private static final JsonMapper CLOSED_JSON =
      JsonMapper.builder()
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .build();

  private static final Field<UUID> OPERATION_ID = DSL.field(DSL.name("operation_id"), UUID.class);
  private static final Field<String> TARGET_NAMESPACE =
      DSL.field(DSL.name("target_namespace"), String.class);
  private static final Field<UUID> CANONICAL_TENANT_ID =
      DSL.field(DSL.name("canonical_tenant_id"), UUID.class);
  private static final Field<String> WORLD_SLUG = DSL.field(DSL.name("world_slug"), String.class);
  private static final Field<UUID> CANONICAL_VERSION_ID =
      DSL.field(DSL.name("canonical_version_id"), UUID.class);
  private static final Field<Long> GAME_DESIGN_VERSION_ID =
      DSL.field(DSL.name("game_design_version_id"), Long.class);
  private static final Field<Long> LOCAL_VERSION_KEY =
      DSL.field(DSL.name("local_version_key"), Long.class);
  private static final Field<UUID> INTAKE_OPERATION_ID =
      DSL.field(DSL.name("intake_operation_id"), UUID.class);
  private static final Field<UUID> INTAKE_REQUEST_ID =
      DSL.field(DSL.name("intake_request_id"), UUID.class);
  private static final Field<Long> LOCAL_TENANT_KEY =
      DSL.field(DSL.name("local_tenant_key"), Long.class);
  private static final Field<String> INTAKE_REQUEST_DIGEST =
      DSL.field(DSL.name("intake_request_digest"), String.class);
  private static final Field<UUID> SOURCE_OPERATION_ID =
      DSL.field(DSL.name("source_operation_id"), UUID.class);
  private static final Field<String> SOURCE_EVIDENCE_DIGEST =
      DSL.field(DSL.name("source_evidence_digest"), String.class);
  private static final Field<String> INTAKE_RECEIPT_DIGEST =
      DSL.field(DSL.name("intake_receipt_digest"), String.class);
  private static final Field<Short> VERSION_STATE_SCHEMA_VERSION =
      DSL.field(DSL.name("version_state_schema_version"), Short.class);
  private static final Field<UUID> VERSION_STATE_READ_REQUEST_ID =
      DSL.field(DSL.name("version_state_read_request_id"), UUID.class);
  private static final Field<String> VERSION_STATE_STATE =
      DSL.field(DSL.name("version_state_state"), String.class);
  private static final Field<Long> VERSION_STATE_EPOCH =
      DSL.field(DSL.name("version_state_epoch"), Long.class);
  private static final Field<String> VERSION_STATE_EVIDENCE_DIGEST =
      DSL.field(DSL.name("version_state_evidence_digest"), String.class);
  private static final Field<String> VERSION_STATE_EVIDENCE_JSON =
      DSL.field(DSL.name("version_state_evidence_json"), String.class);

  private final DSLContext dsl;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "The injected DSLContext is an internal collaborator; construction acquires no resources and this repository has no finalizer.")
  public WorldAuthoredVersionIdentityRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "dsl must not be null");
  }

  /** Reads the immutable identity by canonical Version UUID after the owner transaction commits. */
  public Optional<WorldAuthoredVersionIdentityReceipt> readByCanonicalVersion(
      String namespace, UUID canonicalTenantId, String worldSlug, UUID canonicalVersionId) {
    requireNoActiveTransaction("World authored-Version identity read");
    return readByCanonicalVersionValidated(
        namespace, canonicalTenantId, worldSlug, canonicalVersionId);
  }

  /** Reads the exact immutable Version receipt within a verified owner snapshot. */
  Optional<WorldAuthoredVersionIdentityReceipt> readByCanonicalVersionInOwnerReadOnlyRepeatableRead(
      String namespace, UUID canonicalTenantId, String worldSlug, UUID canonicalVersionId) {
    requireReadOnlyRepeatableReadOwnerTransaction();
    return readByCanonicalVersionValidated(
        namespace, canonicalTenantId, worldSlug, canonicalVersionId);
  }

  /**
   * Reconstructs the same immutable Version receipt inside canonical activation's owner
   * transaction.
   */
  Optional<WorldAuthoredVersionIdentityReceipt> readByCanonicalVersionInOwnerActivationTransaction(
      String namespace, UUID canonicalTenantId, String worldSlug, UUID canonicalVersionId) {
    requireWritableActivationTransaction();
    return readByCanonicalVersionValidated(
        namespace, canonicalTenantId, worldSlug, canonicalVersionId);
  }

  private Optional<WorldAuthoredVersionIdentityReceipt> readByCanonicalVersionValidated(
      String namespace, UUID canonicalTenantId, String worldSlug, UUID canonicalVersionId) {
    validateIdentityKey(namespace, canonicalTenantId, worldSlug);
    requireNonNil(canonicalVersionId, "canonicalVersionId");
    Record row =
        findByCanonicalVersion(namespace, canonicalTenantId, worldSlug, canonicalVersionId);
    return row == null ? Optional.empty() : Optional.of(toReceipt(row));
  }

  /** Reads an immutable identity by the exact Game Design-owned numeric Version selector. */
  public Optional<WorldAuthoredVersionIdentityReceipt> readByGameDesignVersion(
      String namespace, UUID canonicalTenantId, String worldSlug, long gameDesignVersionId) {
    requireNoActiveTransaction("World authored-Version identity read");
    validateIdentityKey(namespace, canonicalTenantId, worldSlug);
    requirePositive(gameDesignVersionId, "gameDesignVersionId");
    Record row =
        findByGameDesignVersion(namespace, canonicalTenantId, worldSlug, gameDesignVersionId);
    return row == null ? Optional.empty() : Optional.of(toReceipt(row));
  }

  /**
   * Reads the unique immutable identity for an exact canonical target and Game Design row.
   *
   * <p>This selector deliberately does not infer or accept a World slug. Multiple rows for the same
   * canonical target are conflicting source associations, not a choice to resolve.
   */
  public Optional<WorldAuthoredVersionIdentityReceipt> readByCanonicalTarget(
      String namespace,
      UUID canonicalTenantId,
      UUID canonicalVersionId,
      long gameDesignVersionRowId) {
    requireNoActiveTransaction("World authored-Version identity read");
    if (namespace == null || !GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw new IllegalArgumentException("Target namespace must be one canonical DNS label");
    }
    requireNonNil(canonicalTenantId, "canonicalTenantId");
    requireNonNil(canonicalVersionId, "canonicalVersionId");
    requirePositive(gameDesignVersionRowId, "gameDesignVersionRowId");

    var rows =
        dsl.selectFrom(VERSION_IDENTITY)
            .where(
                TARGET_NAMESPACE
                    .eq(namespace)
                    .and(CANONICAL_TENANT_ID.eq(canonicalTenantId))
                    .and(CANONICAL_VERSION_ID.eq(canonicalVersionId))
                    .and(GAME_DESIGN_VERSION_ID.eq(gameDesignVersionRowId)))
            .fetch();
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    if (rows.size() != 1) {
      throw new InvalidIdentityEvidenceException(
          "World authored-Version identity target has ambiguous World associations");
    }
    return Optional.of(toReceipt(rows.getFirst()));
  }

  /**
   * Atomically claims the source-qualified Version identity with a World-allocated local key.
   *
   * <p>Concurrent requests for the same stable source, canonical UUID, and Game Design selector
   * return the first stored receipt even when their transport read IDs or current state epochs
   * differ. Changed source or selector evidence conflicts without replacing that receipt.
   */
  public WorldAuthoredVersionIdentityReceipt acceptFresh(
      WorldAuthoredSourceIntakeReceipt sourceReceipt,
      AuthoredWorldVersionStateEvidence versionStateEvidence) {
    requireWritableReadCommittedOwnerTransaction();
    Objects.requireNonNull(sourceReceipt, "sourceReceipt");
    Objects.requireNonNull(versionStateEvidence, "versionStateEvidence");
    validateFreshEvidence(sourceReceipt, versionStateEvidence);

    AuthoredWorldVersionStateEvidence.Request request = versionStateEvidence.request();
    UUID operationId = newNonNilUuid();
    int inserted =
        dsl.insertInto(VERSION_IDENTITY)
            .set(OPERATION_ID, operationId)
            .set(TARGET_NAMESPACE, sourceReceipt.targetNamespace())
            .set(CANONICAL_TENANT_ID, sourceReceipt.canonicalTenantId())
            .set(WORLD_SLUG, sourceReceipt.worldSlug())
            .set(CANONICAL_VERSION_ID, versionStateEvidence.canonicalVersionId())
            .set(GAME_DESIGN_VERSION_ID, request.versionId())
            .set(INTAKE_OPERATION_ID, sourceReceipt.operationId())
            .set(INTAKE_REQUEST_ID, sourceReceipt.intakeRequestId())
            .set(LOCAL_TENANT_KEY, sourceReceipt.localTenantKey())
            .set(INTAKE_REQUEST_DIGEST, sourceReceipt.requestDigest())
            .set(SOURCE_OPERATION_ID, sourceReceipt.sourceOperationId())
            .set(SOURCE_EVIDENCE_DIGEST, sourceReceipt.sourceEvidenceDigest())
            .set(INTAKE_RECEIPT_DIGEST, sourceReceipt.receiptDigest())
            .set(VERSION_STATE_SCHEMA_VERSION, (short) request.schemaVersion())
            .set(VERSION_STATE_READ_REQUEST_ID, request.readRequestId())
            .set(VERSION_STATE_STATE, lifecycleToken(versionStateEvidence.versionState()))
            .set(VERSION_STATE_EPOCH, versionStateEvidence.versionStateEpoch())
            .set(VERSION_STATE_EVIDENCE_DIGEST, versionStateEvidence.evidenceDigest())
            .set(VERSION_STATE_EVIDENCE_JSON, writeJson(versionStateEvidence))
            .onConflictDoNothing()
            .execute();

    Record row =
        findByCanonicalVersion(
            sourceReceipt.targetNamespace(),
            sourceReceipt.canonicalTenantId(),
            sourceReceipt.worldSlug(),
            versionStateEvidence.canonicalVersionId());
    if (row == null) {
      row =
          findByGameDesignVersion(
              sourceReceipt.targetNamespace(),
              sourceReceipt.canonicalTenantId(),
              sourceReceipt.worldSlug(),
              request.versionId());
    }
    if (row == null) {
      throw new InvalidIdentityEvidenceException(
          "World authored-Version identity is missing after its owner claim");
    }

    WorldAuthoredVersionIdentityReceipt receipt = toReceipt(row);
    requireSameIdentity(receipt, sourceReceipt, versionStateEvidence);
    if (inserted == 0) {
      // A duplicate request resolves only to the original immutable receipt; never rewrite it.
      return receipt;
    }
    return receipt;
  }

  private Record findByCanonicalVersion(
      String namespace, UUID canonicalTenantId, String worldSlug, UUID canonicalVersionId) {
    return dsl.selectFrom(VERSION_IDENTITY)
        .where(
            TARGET_NAMESPACE
                .eq(namespace)
                .and(CANONICAL_TENANT_ID.eq(canonicalTenantId))
                .and(WORLD_SLUG.eq(worldSlug))
                .and(CANONICAL_VERSION_ID.eq(canonicalVersionId)))
        .fetchOne();
  }

  private Record findByGameDesignVersion(
      String namespace, UUID canonicalTenantId, String worldSlug, long gameDesignVersionId) {
    return dsl.selectFrom(VERSION_IDENTITY)
        .where(
            TARGET_NAMESPACE
                .eq(namespace)
                .and(CANONICAL_TENANT_ID.eq(canonicalTenantId))
                .and(WORLD_SLUG.eq(worldSlug))
                .and(GAME_DESIGN_VERSION_ID.eq(gameDesignVersionId)))
        .fetchOne();
  }

  private WorldAuthoredVersionIdentityReceipt toReceipt(Record row) {
    try {
      UUID operationId = required(row, OPERATION_ID);
      String namespace = required(row, TARGET_NAMESPACE);
      UUID canonicalTenantId = required(row, CANONICAL_TENANT_ID);
      String worldSlug = required(row, WORLD_SLUG);
      UUID canonicalVersionId = required(row, CANONICAL_VERSION_ID);
      Long gameDesignVersionId = required(row, GAME_DESIGN_VERSION_ID);
      Long localVersionKey = required(row, LOCAL_VERSION_KEY);
      UUID intakeOperationId = required(row, INTAKE_OPERATION_ID);
      UUID intakeRequestId = required(row, INTAKE_REQUEST_ID);
      Long localTenantKey = required(row, LOCAL_TENANT_KEY);
      String intakeRequestDigest = required(row, INTAKE_REQUEST_DIGEST);
      UUID sourceOperationId = required(row, SOURCE_OPERATION_ID);
      String sourceEvidenceDigest = required(row, SOURCE_EVIDENCE_DIGEST);
      String intakeReceiptDigest = required(row, INTAKE_RECEIPT_DIGEST);
      Short versionStateSchemaVersion = required(row, VERSION_STATE_SCHEMA_VERSION);
      UUID readRequestId = required(row, VERSION_STATE_READ_REQUEST_ID);
      String versionStateToken = required(row, VERSION_STATE_STATE);
      Long versionStateEpoch = required(row, VERSION_STATE_EPOCH);
      String versionStateEvidenceDigest = required(row, VERSION_STATE_EVIDENCE_DIGEST);
      AuthoredWorldVersionStateEvidence evidence =
          readJson(required(row, VERSION_STATE_EVIDENCE_JSON));

      if (versionStateSchemaVersion != evidence.request().schemaVersion()
          || !readRequestId.equals(evidence.request().readRequestId())
          || !versionStateToken.equals(lifecycleToken(evidence.versionState()))
          || !versionStateEpoch.equals(evidence.versionStateEpoch())
          || !versionStateEvidenceDigest.equals(evidence.evidenceDigest())
          || !canonicalVersionId.equals(evidence.canonicalVersionId())
          || gameDesignVersionId != evidence.request().versionId()) {
        throw new IllegalArgumentException(
            "Persisted World Version identity columns differ from their closed evidence");
      }

      WorldAuthoredSourceIntakeReceipt sourceReceipt =
          new WorldAuthoredSourceIntakeReceipt(
              1,
              namespace,
              intakeRequestId,
              intakeOperationId,
              canonicalTenantId,
              worldSlug,
              sourceOperationId,
              sourceEvidenceDigest,
              intakeRequestDigest,
              intakeReceiptDigest,
              localTenantKey,
              evidence.sourceEvidence());
      return new WorldAuthoredVersionIdentityReceipt(
          1, operationId, localVersionKey, sourceReceipt, evidence);
    } catch (RuntimeException exception) {
      if (exception instanceof InvalidIdentityEvidenceException) {
        throw exception;
      }
      throw new InvalidIdentityEvidenceException(
          "Persisted World authored-Version identity evidence is invalid", exception);
    }
  }

  private static void validateFreshEvidence(
      WorldAuthoredSourceIntakeReceipt sourceReceipt, AuthoredWorldVersionStateEvidence evidence) {
    evidence.requireValid();
    AuthoredWorldVersionStateEvidence.Request request = evidence.request();
    if (!"NEW_GAME_ROW".equals(sourceReceipt.source().provenanceKind())
        || !sourceReceipt.targetNamespace().equals(request.targetNamespace())
        || !sourceReceipt.canonicalTenantId().equals(request.canonicalTenantId())
        || !sourceReceipt.worldSlug().equals(request.worldSlug())
        || !sourceReceipt.sourceOperationId().equals(request.sourceOperationId())
        || !sourceReceipt.sourceEvidenceDigest().equals(request.expectedSourceEvidenceDigest())
        || !sourceReceipt.source().equals(evidence.sourceEvidence())) {
      throw new RegistrationConflictException(
          "World Version identity evidence differs from the exact fresh source intake");
    }
  }

  private static void requireSameIdentity(
      WorldAuthoredVersionIdentityReceipt receipt,
      WorldAuthoredSourceIntakeReceipt sourceReceipt,
      AuthoredWorldVersionStateEvidence candidate) {
    if (!receipt.sourceIntakeReceipt().equals(sourceReceipt)
        || !receipt.canonicalVersionId().equals(candidate.canonicalVersionId())
        || receipt.gameDesignVersionId() != candidate.request().versionId()) {
      throw new RegistrationConflictException(
          "World Version identity scope, source intake, or Game Design selector conflicts");
    }
  }

  private static String writeJson(Object value) {
    try {
      return CLOSED_JSON.writeValueAsString(value);
    } catch (Exception exception) {
      throw new InvalidIdentityEvidenceException(
          "World Version identity evidence could not be encoded", exception);
    }
  }

  private static AuthoredWorldVersionStateEvidence readJson(String json) {
    try {
      AuthoredWorldVersionStateEvidence evidence =
          CLOSED_JSON.readValue(json, AuthoredWorldVersionStateEvidence.class);
      if (!writeJson(evidence).equals(json)) {
        throw new IllegalArgumentException(
            "Persisted Version-state evidence JSON is not in its closed form");
      }
      evidence.requireValid();
      return evidence;
    } catch (Exception exception) {
      throw new InvalidIdentityEvidenceException(
          "Persisted Version-state JSON is not closed evidence", exception);
    }
  }

  private static String lifecycleToken(VersionLifecycleState state) {
    return switch (state) {
      case VERSION_LIFECYCLE_STATE_DRAFT -> "DRAFT";
      case VERSION_LIFECYCLE_STATE_PUBLISHED -> "PUBLISHED";
      case VERSION_LIFECYCLE_STATE_ACTIVE -> "ACTIVE";
      default ->
          throw new InvalidIdentityEvidenceException(
              "World Version identity requires DRAFT, PUBLISHED, or ACTIVE owner evidence");
    };
  }

  private static <T> T required(Record row, Field<T> field) {
    return Objects.requireNonNull(row.get(field), "Persisted " + field.getName() + " is null");
  }

  private static void validateIdentityKey(
      String namespace, UUID canonicalTenantId, String worldSlug) {
    AuthoredWorldSourceDigest.validateReadSelector(namespace, canonicalTenantId, worldSlug);
  }

  private static void requireNonNil(UUID value, String label) {
    if (value == null || value.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException(label + " must be a non-nil UUID");
    }
  }

  private static void requirePositive(long value, String label) {
    if (value <= 0) {
      throw new IllegalArgumentException(label + " must be positive");
    }
  }

  private static UUID newNonNilUuid() {
    UUID value;
    do {
      value = UUID.randomUUID();
    } while (value.equals(new UUID(0L, 0L)));
    return value;
  }

  private static void requireNoActiveTransaction(String label) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(label + " requires an independent committed owner read");
    }
  }

  private void requireReadOnlyRepeatableReadOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        || !Integer.valueOf(Connection.TRANSACTION_REPEATABLE_READ)
            .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) {
      throw new IllegalStateException(
          "World authored-Version snapshot read requires a read-only REPEATABLE READ owner transaction");
    }
    Record state =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT current_setting('transaction_isolation') AS isolation, "
                    + "current_setting('transaction_read_only') AS read_only"),
            "World transaction state query returned no row");
    if (!"repeatable read".equals(state.get("isolation", String.class))
        || !"on".equals(state.get("read_only", String.class))) {
      throw new IllegalStateException(
          "World authored-Version snapshot read requires a read-only REPEATABLE READ owner transaction");
    }
  }

  private void requireWritableActivationTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        || !Integer.valueOf(Connection.TRANSACTION_READ_COMMITTED)
            .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) {
      throw new IllegalStateException(
          "World authored-Version activation read requires a writable READ COMMITTED owner transaction");
    }
    Record state =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT current_setting('transaction_isolation') AS isolation, "
                    + "current_setting('transaction_read_only') AS read_only"),
            "World transaction state query returned no row");
    if (!"read committed".equals(state.get("isolation", String.class))
        || !"off".equals(state.get("read_only", String.class))) {
      throw new IllegalStateException(
          "World authored-Version activation read requires a writable READ COMMITTED owner transaction");
    }
  }

  private static void requireWritableReadCommittedOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "World authored-Version identity requires an active owner transaction");
    }
    if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "World authored-Version identity requires a writable owner transaction");
    }
    Integer declaredIsolation =
        TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
    if (declaredIsolation != null && declaredIsolation != Connection.TRANSACTION_READ_COMMITTED) {
      throw new IllegalStateException(
          "World authored-Version identity requires READ COMMITTED isolation");
    }
  }

  public static final class RegistrationConflictException extends IllegalStateException {
    public RegistrationConflictException(String message) {
      super(message);
    }
  }

  public static final class InvalidIdentityEvidenceException extends IllegalStateException {
    public InvalidIdentityEvidenceException(String message) {
      super(message);
    }

    public InvalidIdentityEvidenceException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
