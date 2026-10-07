package net.firedevops.firemud.worldmanagement.tenant;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * World-owned immutable copy of authenticated, fresh Game Design authored-world source evidence.
 */
@Repository
public class WorldAuthoredSourceIntakeRepository {
  private static final int SCHEMA_VERSION = 1;
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final Table<?> TENANT_ASSOCIATION =
      DSL.table(DSL.name("world_authored_source_tenant_association"));
  private static final Table<?> TENANT_KEY_RESERVATION =
      DSL.table(DSL.name("world_authored_source_tenant_key_reservation"));
  private static final Table<?> INTAKE = DSL.table(DSL.name("world_authored_source_intake"));

  private static final Field<String> TARGET_NAMESPACE =
      DSL.field(DSL.name("target_namespace"), String.class);
  private static final Field<UUID> CANONICAL_TENANT_ID =
      DSL.field(DSL.name("canonical_tenant_id"), UUID.class);
  private static final Field<String> TENANT_SLUG = DSL.field(DSL.name("tenant_slug"), String.class);
  private static final Field<Long> SOURCE_GAME_ROW_ID =
      DSL.field(DSL.name("source_game_row_id"), Long.class);
  private static final Field<String> SOURCE_GAME_TENANT_KEY =
      DSL.field(DSL.name("source_game_tenant_key"), String.class);
  private static final Field<String> SOURCE_PROVENANCE_KIND =
      DSL.field(DSL.name("source_provenance_kind"), String.class);
  private static final Field<Long> LOCAL_TENANT_KEY =
      DSL.field(DSL.name("local_tenant_key"), Long.class);
  private static final Field<Long> RESERVED_TENANT_KEY =
      DSL.field(DSL.name("tenant_key"), Long.class);
  private static final Field<String> RESERVATION_CLAIM_KIND =
      DSL.field(DSL.name("claim_kind"), String.class);
  private static final Field<String> RESERVATION_NAMESPACE =
      DSL.field(DSL.name("target_namespace"), String.class);
  private static final Field<UUID> RESERVATION_CANONICAL_TENANT_ID =
      DSL.field(DSL.name("canonical_tenant_id"), UUID.class);

  private static final Field<UUID> OPERATION_ID = DSL.field(DSL.name("operation_id"), UUID.class);
  private static final Field<Short> STORED_SCHEMA_VERSION =
      DSL.field(DSL.name("schema_version"), Short.class);
  private static final Field<UUID> INTAKE_REQUEST_ID =
      DSL.field(DSL.name("intake_request_id"), UUID.class);
  private static final Field<String> REQUEST_DIGEST =
      DSL.field(DSL.name("request_digest"), String.class);
  private static final Field<Short> SOURCE_SCHEMA_VERSION =
      DSL.field(DSL.name("source_schema_version"), Short.class);
  private static final Field<UUID> SOURCE_REGISTRATION_REQUEST_ID =
      DSL.field(DSL.name("source_registration_request_id"), UUID.class);
  private static final Field<UUID> SOURCE_OPERATION_ID =
      DSL.field(DSL.name("source_operation_id"), UUID.class);
  private static final Field<String> SOURCE_REQUEST_DIGEST =
      DSL.field(DSL.name("source_request_digest"), String.class);
  private static final Field<String> WORLD_SLUG = DSL.field(DSL.name("world_slug"), String.class);
  private static final Field<String> WORLD_DISPLAY_NAME =
      DSL.field(DSL.name("world_display_name"), String.class);
  private static final Field<String> SOURCE_EVIDENCE_DIGEST =
      DSL.field(DSL.name("source_evidence_digest"), String.class);
  private static final Field<String> RECEIPT_DIGEST =
      DSL.field(DSL.name("receipt_digest"), String.class);

  private static final String LOCK_LEGACY_TENANT_TABLES =
      "LOCK TABLE generation_rule, instance, region, region_instance, room, room_exit, "
          + "room_instance, room_instance_exit, world_design_aggregate_epoch, "
          + "world_design_revision_ledger, world_design_scope_epoch, "
          + "world_entity_spawn_binding, world_event, world_instance, zone, zone_instance "
          + "IN SHARE MODE";
  private static final String MAX_OCCUPIED_TENANT_KEY =
      "SELECT COALESCE(MAX(occupied_key), 0) FROM ("
          + "SELECT tenant_id AS occupied_key FROM generation_rule UNION ALL "
          + "SELECT tenant_id FROM instance UNION ALL "
          + "SELECT tenant_id FROM region UNION ALL "
          + "SELECT tenant_id FROM region_instance UNION ALL "
          + "SELECT tenant_id FROM room UNION ALL "
          + "SELECT tenant_id FROM room_exit UNION ALL "
          + "SELECT tenant_id FROM room_instance UNION ALL "
          + "SELECT tenant_id FROM room_instance_exit UNION ALL "
          + "SELECT tenant_id FROM world_design_aggregate_epoch UNION ALL "
          + "SELECT tenant_id FROM world_design_revision_ledger UNION ALL "
          + "SELECT tenant_id FROM world_design_scope_epoch UNION ALL "
          + "SELECT tenant_id FROM world_entity_spawn_binding UNION ALL "
          + "SELECT tenant_id FROM world_event UNION ALL "
          + "SELECT tenant_id FROM world_instance UNION ALL "
          + "SELECT tenant_id FROM zone UNION ALL "
          + "SELECT tenant_id FROM zone_instance UNION ALL "
          + "SELECT tenant_key FROM world_authored_source_tenant_key_reservation UNION ALL "
          + "SELECT local_tenant_key FROM world_authored_source_tenant_association"
          + ") AS occupied_tenant_keys";

  private final DSLContext dsl;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "The injected DSLContext is an internal Spring collaborator; construction acquires no resources and this repository has no finalizer.")
  public WorldAuthoredSourceIntakeRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "dsl must not be null");
  }

  /**
   * Retains the complete fresh Game Design receipt and its World-local tenant association.
   *
   * <p>The caller must already have authenticated Game Design and opened the World owner
   * transaction. This method does not accept a forwarded digest in place of source evidence.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public WorldAuthoredSourceIntakeReceipt acceptFresh(
      String namespace, UUID intakeRequestId, AuthoredWorldSourceEvidence source) {
    requireActiveOwnerTransaction();
    requireWritableReadCommittedOwnerTransaction();
    AuthoredWorldSourceEvidence validatedSource = validateFreshSource(namespace, source);
    String requestDigest =
        WorldAuthoredSourceIntakeDigest.requestDigest(namespace, intakeRequestId, validatedSource);

    Record priorRequest = findByRequest(namespace, intakeRequestId);
    if (priorRequest != null) {
      return requireExactReplay(priorRequest, intakeRequestId, requestDigest, validatedSource);
    }

    lockFreshIntakeAndExistingTenantRows();

    priorRequest = findByRequest(namespace, intakeRequestId);
    if (priorRequest != null) {
      return requireExactReplay(priorRequest, intakeRequestId, requestDigest, validatedSource);
    }
    requireUnclaimedSourceAndWorld(namespace, validatedSource);

    long localTenantKey = ensureTenantAssociation(validatedSource);
    UUID operationId = UUID.randomUUID();
    String receiptDigest =
        WorldAuthoredSourceIntakeDigest.receiptDigest(
            namespace, operationId, requestDigest, validatedSource, localTenantKey);

    int inserted =
        dsl.insertInto(INTAKE)
            .set(OPERATION_ID, operationId)
            .set(STORED_SCHEMA_VERSION, (short) SCHEMA_VERSION)
            .set(TARGET_NAMESPACE, namespace)
            .set(INTAKE_REQUEST_ID, intakeRequestId)
            .set(REQUEST_DIGEST, requestDigest)
            .set(LOCAL_TENANT_KEY, localTenantKey)
            .set(SOURCE_SCHEMA_VERSION, (short) validatedSource.schemaVersion())
            .set(SOURCE_REGISTRATION_REQUEST_ID, validatedSource.registrationRequestId())
            .set(SOURCE_OPERATION_ID, validatedSource.operationId())
            .set(SOURCE_REQUEST_DIGEST, validatedSource.requestDigest())
            .set(CANONICAL_TENANT_ID, validatedSource.canonicalTenantId())
            .set(TENANT_SLUG, validatedSource.tenantSlug())
            .set(WORLD_SLUG, validatedSource.worldSlug())
            .set(WORLD_DISPLAY_NAME, validatedSource.worldDisplayName())
            .set(SOURCE_GAME_ROW_ID, validatedSource.sourceGameRowId())
            .set(SOURCE_GAME_TENANT_KEY, validatedSource.sourceGameTenantKey())
            .set(SOURCE_PROVENANCE_KIND, validatedSource.provenanceKind())
            .set(SOURCE_EVIDENCE_DIGEST, validatedSource.evidenceDigest())
            .set(RECEIPT_DIGEST, receiptDigest)
            .execute();
    if (inserted != 1) {
      throw new RegistrationConflictException("World authored-source intake was not inserted");
    }

    Record persisted = findByRequest(namespace, intakeRequestId);
    if (persisted == null) {
      throw new InvalidIntakeEvidenceException(
          "World authored-source intake disappeared before owner readback");
    }
    WorldAuthoredSourceIntakeReceipt receipt = toReceipt(persisted);
    requireExactReceipt(receipt, intakeRequestId, requestDigest, validatedSource);
    requireTenantAssociation(receipt);
    return receipt;
  }

  /** Reads a committed World intake by its exact namespace and stable request identity. */
  @Transactional(propagation = Propagation.NOT_SUPPORTED, readOnly = true)
  public Optional<WorldAuthoredSourceIntakeReceipt> read(String namespace, UUID intakeRequestId) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "World authored-source intake read requires a committed-outcome owner read");
    }
    return readValidated(namespace, intakeRequestId);
  }

  private Optional<WorldAuthoredSourceIntakeReceipt> readValidated(
      String namespace, UUID intakeRequestId) {
    validateReadKey(namespace, intakeRequestId);
    Record record = findByRequest(namespace, intakeRequestId);
    if (record == null) {
      return Optional.empty();
    }
    WorldAuthoredSourceIntakeReceipt receipt = toReceipt(record);
    requireTenantAssociation(receipt);
    return Optional.of(receipt);
  }

  /** Reads a committed World intake by its exact canonical source-owned binding. */
  @Transactional(propagation = Propagation.NOT_SUPPORTED, readOnly = true)
  public Optional<WorldAuthoredSourceIntakeReceipt> readBySource(
      String namespace,
      UUID canonicalTenantId,
      String worldSlug,
      UUID sourceOperationId,
      String sourceEvidenceDigest) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "World authored-source intake read requires a committed-outcome owner read");
    }
    validateSourceReadKey(
        namespace, canonicalTenantId, worldSlug, sourceOperationId, sourceEvidenceDigest);
    Record record =
        dsl.selectFrom(INTAKE)
            .where(
                TARGET_NAMESPACE
                    .eq(namespace)
                    .and(CANONICAL_TENANT_ID.eq(canonicalTenantId))
                    .and(WORLD_SLUG.eq(worldSlug))
                    .and(SOURCE_OPERATION_ID.eq(sourceOperationId))
                    .and(SOURCE_EVIDENCE_DIGEST.eq(sourceEvidenceDigest)))
            .fetchOne();
    if (record == null) {
      return Optional.empty();
    }
    WorldAuthoredSourceIntakeReceipt receipt = toReceipt(record);
    requireTenantAssociation(receipt);
    return Optional.of(receipt);
  }

  private void lockFreshIntakeAndExistingTenantRows() {
    // Serialize allocator owners, then keep every current tenant-bearing table stable while the
    // candidate is checked. Legacy writers do not take this association lock; their database
    // reservation trigger rejects selectors already claimed by a canonical source.
    dsl.execute("LOCK TABLE world_authored_source_tenant_association IN SHARE ROW EXCLUSIVE MODE");
    dsl.execute(LOCK_LEGACY_TENANT_TABLES);
  }

  private void requireUnclaimedSourceAndWorld(
      String namespace, AuthoredWorldSourceEvidence source) {
    if (findBySourceOperation(namespace, source.operationId()) != null) {
      throw new RegistrationConflictException(
          "Game Design source operation is already claimed by another World intake");
    }
    if (findBySourceRegistrationRequest(namespace, source.registrationRequestId()) != null) {
      throw new RegistrationConflictException(
          "Game Design source registration request is already claimed by another World intake");
    }
    if (findByWorldSelector(namespace, source.canonicalTenantId(), source.worldSlug()) != null) {
      throw new RegistrationConflictException(
          "World selector is already associated with different source evidence");
    }
  }

  private long ensureTenantAssociation(AuthoredWorldSourceEvidence source) {
    Record existing = findTenantAssociation(source.targetNamespace(), source.canonicalTenantId());
    if (existing != null) {
      requireMatchingTenantAssociation(existing, source);
      long existingKey = Objects.requireNonNull(existing.get(LOCAL_TENANT_KEY, Long.class));
      requireCanonicalTenantKeyReservation(source, existingKey);
      return existingKey;
    }

    if (findTenantSlugAssociation(source.targetNamespace(), source.tenantSlug()) != null) {
      throw new RegistrationConflictException(
          "Tenant selector is already associated with another canonical tenant");
    }
    if (findSourceRowAssociation(source.targetNamespace(), source.sourceGameRowId()) != null) {
      throw new RegistrationConflictException(
          "Game Design source row is already associated with another canonical tenant");
    }

    long localTenantKey = nextUnclaimedTenantKey();
    int inserted =
        dsl.insertInto(TENANT_ASSOCIATION)
            .set(TARGET_NAMESPACE, source.targetNamespace())
            .set(CANONICAL_TENANT_ID, source.canonicalTenantId())
            .set(TENANT_SLUG, source.tenantSlug())
            .set(SOURCE_GAME_ROW_ID, source.sourceGameRowId())
            .set(SOURCE_GAME_TENANT_KEY, source.sourceGameTenantKey())
            .set(SOURCE_PROVENANCE_KIND, source.provenanceKind())
            .set(LOCAL_TENANT_KEY, localTenantKey)
            .onConflictDoNothing()
            .execute();
    if (inserted == 1) {
      requireCanonicalTenantKeyReservation(source, localTenantKey);
      return localTenantKey;
    }

    existing = findTenantAssociation(source.targetNamespace(), source.canonicalTenantId());
    if (existing != null) {
      requireMatchingTenantAssociation(existing, source);
      long existingKey = Objects.requireNonNull(existing.get(LOCAL_TENANT_KEY, Long.class));
      requireCanonicalTenantKeyReservation(source, existingKey);
      return existingKey;
    }
    if (findTenantSlugAssociation(source.targetNamespace(), source.tenantSlug()) != null) {
      throw new RegistrationConflictException(
          "Tenant selector is already associated with another canonical tenant");
    }
    if (findSourceRowAssociation(source.targetNamespace(), source.sourceGameRowId()) != null) {
      throw new RegistrationConflictException(
          "Game Design source row is already associated with another canonical tenant");
    }
    throw new RegistrationConflictException(
        "World authored-source tenant association conflicts with an owner claim");
  }

  private long nextUnclaimedTenantKey() {
    Record allocation = dsl.fetchOne(MAX_OCCUPIED_TENANT_KEY);
    if (allocation == null) {
      throw new IllegalStateException("World tenant-selector allocation aggregate returned no row");
    }
    Long maxKey = allocation.get(0, Long.class);
    if (maxKey == null) {
      throw new IllegalStateException("World tenant-selector allocation aggregate returned null");
    }
    long currentMax = maxKey;
    if (currentMax == Long.MAX_VALUE) {
      throw new IllegalStateException("World has no remaining positive local tenant selectors");
    }
    return Math.max(currentMax, 0L) + 1L;
  }

  private void requireCanonicalTenantKeyReservation(
      AuthoredWorldSourceEvidence source, long localTenantKey) {
    dsl.insertInto(TENANT_KEY_RESERVATION)
        .set(RESERVED_TENANT_KEY, localTenantKey)
        .set(RESERVATION_CLAIM_KIND, "CANONICAL_AUTHORED_SOURCE")
        .set(RESERVATION_NAMESPACE, source.targetNamespace())
        .set(RESERVATION_CANONICAL_TENANT_ID, source.canonicalTenantId())
        .onConflictDoNothing()
        .execute();
    Record reservation = findTenantKeyReservation(localTenantKey);
    if (reservation == null
        || !Objects.equals(
            reservation.get(RESERVATION_CLAIM_KIND, String.class), "CANONICAL_AUTHORED_SOURCE")
        || !Objects.equals(
            reservation.get(RESERVATION_NAMESPACE, String.class), source.targetNamespace())
        || !Objects.equals(
            reservation.get(RESERVATION_CANONICAL_TENANT_ID, UUID.class),
            source.canonicalTenantId())) {
      throw new RegistrationConflictException(
          "World local tenant selector is already reserved by another claim");
    }
  }

  private void requireTenantAssociation(WorldAuthoredSourceIntakeReceipt receipt) {
    Record association =
        findTenantAssociation(receipt.targetNamespace(), receipt.canonicalTenantId());
    if (association == null) {
      throw new InvalidIntakeEvidenceException(
          "World authored-source tenant association is missing");
    }
    requireMatchingTenantAssociation(association, receipt.source());
    if (!Objects.equals(association.get(LOCAL_TENANT_KEY, Long.class), receipt.localTenantKey())) {
      throw new InvalidIntakeEvidenceException(
          "World authored-source private tenant selector differs from its association");
    }
    Record reservation = findTenantKeyReservation(receipt.localTenantKey());
    if (reservation == null
        || !Objects.equals(
            reservation.get(RESERVATION_CLAIM_KIND, String.class), "CANONICAL_AUTHORED_SOURCE")
        || !Objects.equals(
            reservation.get(RESERVATION_NAMESPACE, String.class), receipt.targetNamespace())
        || !Objects.equals(
            reservation.get(RESERVATION_CANONICAL_TENANT_ID, UUID.class),
            receipt.canonicalTenantId())) {
      throw new InvalidIntakeEvidenceException(
          "World authored-source private tenant selector reservation is missing or changed");
    }
  }

  private Record findTenantKeyReservation(long tenantKey) {
    return dsl.selectFrom(TENANT_KEY_RESERVATION)
        .where(RESERVED_TENANT_KEY.eq(tenantKey))
        .fetchOne();
  }

  private void requireMatchingTenantAssociation(
      Record association, AuthoredWorldSourceEvidence source) {
    if (!Objects.equals(association.get(TARGET_NAMESPACE, String.class), source.targetNamespace())
        || !Objects.equals(
            association.get(CANONICAL_TENANT_ID, UUID.class), source.canonicalTenantId())
        || !Objects.equals(association.get(TENANT_SLUG, String.class), source.tenantSlug())
        || !Objects.equals(
            association.get(SOURCE_GAME_ROW_ID, Long.class), source.sourceGameRowId())
        || !Objects.equals(
            association.get(SOURCE_GAME_TENANT_KEY, String.class), source.sourceGameTenantKey())
        || !Objects.equals(
            association.get(SOURCE_PROVENANCE_KIND, String.class), source.provenanceKind())) {
      throw new RegistrationConflictException(
          "Canonical tenant source or stable tenant selector conflicts with its World association");
    }
  }

  private WorldAuthoredSourceIntakeReceipt requireExactReplay(
      Record record,
      UUID intakeRequestId,
      String requestDigest,
      AuthoredWorldSourceEvidence source) {
    WorldAuthoredSourceIntakeReceipt receipt = toReceipt(record);
    requireExactReceipt(receipt, intakeRequestId, requestDigest, source);
    requireTenantAssociation(receipt);
    return receipt;
  }

  private void requireExactReceipt(
      WorldAuthoredSourceIntakeReceipt receipt,
      UUID intakeRequestId,
      String requestDigest,
      AuthoredWorldSourceEvidence source) {
    if (!receipt.intakeRequestId().equals(intakeRequestId)
        || !receipt.requestDigest().equals(requestDigest)
        || !receipt.source().equals(source)) {
      throw new RegistrationConflictException(
          "World intake request identity was reused with changed scope or source evidence");
    }
  }

  private AuthoredWorldSourceEvidence validateFreshSource(
      String namespace, AuthoredWorldSourceEvidence source) {
    Objects.requireNonNull(namespace, "namespace");
    if (!GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw new InvalidIntakeEvidenceException(
          "World authored-source target namespace must be one canonical DNS label");
    }
    Objects.requireNonNull(source, "source");
    try {
      AuthoredWorldSourceEvidence validated =
          new AuthoredWorldSourceEvidence(
              source.schemaVersion(),
              source.targetNamespace(),
              source.registrationRequestId(),
              source.operationId(),
              source.requestDigest(),
              source.canonicalTenantId(),
              source.tenantSlug(),
              source.worldSlug(),
              source.worldDisplayName(),
              source.sourceGameRowId(),
              source.sourceGameTenantKey(),
              source.provenanceKind(),
              source.evidenceDigest());
      WorldAuthoredSourceIntakeDigest.requireFreshSource(namespace, validated);
      return validated;
    } catch (IllegalArgumentException exception) {
      throw new InvalidIntakeEvidenceException(
          "Game Design authored-world source evidence is invalid or not fresh", exception);
    }
  }

  private void validateReadKey(String namespace, UUID intakeRequestId) {
    if (namespace == null || !GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw new IllegalArgumentException("namespace must be one canonical DNS label");
    }
    requireNonNil(intakeRequestId, "intakeRequestId");
  }

  private void validateSourceReadKey(
      String namespace,
      UUID canonicalTenantId,
      String worldSlug,
      UUID sourceOperationId,
      String sourceEvidenceDigest) {
    AuthoredWorldSourceDigest.validateReadSelector(namespace, canonicalTenantId, worldSlug);
    requireNonNil(sourceOperationId, "sourceOperationId");
    if (!GameTenantCreationDigest.isDigest(sourceEvidenceDigest)) {
      throw new IllegalArgumentException(
          "sourceEvidenceDigest must be a canonical lowercase SHA-256 digest");
    }
  }

  private void requireActiveOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "World authored-source intake requires an active World owner transaction");
    }
  }

  private void requireWritableReadCommittedOwnerTransaction() {
    if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "World authored-source intake requires a writable owner transaction");
    }
    Integer declaredIsolation =
        TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
    if (declaredIsolation != null && declaredIsolation != Connection.TRANSACTION_READ_COMMITTED) {
      throw new IllegalStateException(
          "World authored-source intake requires READ COMMITTED isolation");
    }
    Record settings =
        dsl.fetchOne(
            "SELECT current_setting('transaction_isolation') AS transaction_isolation, "
                + "current_setting('transaction_read_only') AS transaction_read_only");
    if (settings == null
        || !"read committed".equalsIgnoreCase(settings.get("transaction_isolation", String.class))
        || !"off".equalsIgnoreCase(settings.get("transaction_read_only", String.class))) {
      throw new IllegalStateException(
          "World authored-source intake requires a writable READ COMMITTED owner transaction");
    }
  }

  private Record findByRequest(String namespace, UUID intakeRequestId) {
    return dsl.selectFrom(INTAKE)
        .where(TARGET_NAMESPACE.eq(namespace).and(INTAKE_REQUEST_ID.eq(intakeRequestId)))
        .fetchOne();
  }

  private Record findBySourceOperation(String namespace, UUID sourceOperationId) {
    return dsl.selectFrom(INTAKE)
        .where(TARGET_NAMESPACE.eq(namespace).and(SOURCE_OPERATION_ID.eq(sourceOperationId)))
        .fetchOne();
  }

  private Record findBySourceRegistrationRequest(String namespace, UUID sourceRequestId) {
    return dsl.selectFrom(INTAKE)
        .where(
            TARGET_NAMESPACE.eq(namespace).and(SOURCE_REGISTRATION_REQUEST_ID.eq(sourceRequestId)))
        .fetchOne();
  }

  private Record findByWorldSelector(String namespace, UUID tenantId, String worldSlug) {
    return dsl.selectFrom(INTAKE)
        .where(
            TARGET_NAMESPACE
                .eq(namespace)
                .and(CANONICAL_TENANT_ID.eq(tenantId))
                .and(WORLD_SLUG.eq(worldSlug)))
        .fetchOne();
  }

  private Record findTenantAssociation(String namespace, UUID canonicalTenantId) {
    return dsl.selectFrom(TENANT_ASSOCIATION)
        .where(TARGET_NAMESPACE.eq(namespace).and(CANONICAL_TENANT_ID.eq(canonicalTenantId)))
        .fetchOne();
  }

  private Record findTenantSlugAssociation(String namespace, String tenantSlug) {
    return dsl.selectFrom(TENANT_ASSOCIATION)
        .where(TARGET_NAMESPACE.eq(namespace).and(TENANT_SLUG.eq(tenantSlug)))
        .fetchOne();
  }

  private Record findSourceRowAssociation(String namespace, long sourceGameRowId) {
    return dsl.selectFrom(TENANT_ASSOCIATION)
        .where(TARGET_NAMESPACE.eq(namespace).and(SOURCE_GAME_ROW_ID.eq(sourceGameRowId)))
        .fetchOne();
  }

  private WorldAuthoredSourceIntakeReceipt toReceipt(Record record) {
    try {
      Short schemaVersion = Objects.requireNonNull(record.get(STORED_SCHEMA_VERSION, Short.class));
      Short sourceSchemaVersion =
          Objects.requireNonNull(record.get(SOURCE_SCHEMA_VERSION, Short.class));
      AuthoredWorldSourceEvidence source =
          new AuthoredWorldSourceEvidence(
              sourceSchemaVersion.intValue(),
              Objects.requireNonNull(record.get(TARGET_NAMESPACE, String.class)),
              Objects.requireNonNull(record.get(SOURCE_REGISTRATION_REQUEST_ID, UUID.class)),
              Objects.requireNonNull(record.get(SOURCE_OPERATION_ID, UUID.class)),
              Objects.requireNonNull(record.get(SOURCE_REQUEST_DIGEST, String.class)),
              Objects.requireNonNull(record.get(CANONICAL_TENANT_ID, UUID.class)),
              Objects.requireNonNull(record.get(TENANT_SLUG, String.class)),
              Objects.requireNonNull(record.get(WORLD_SLUG, String.class)),
              Objects.requireNonNull(record.get(WORLD_DISPLAY_NAME, String.class)),
              Objects.requireNonNull(record.get(SOURCE_GAME_ROW_ID, Long.class)),
              Objects.requireNonNull(record.get(SOURCE_GAME_TENANT_KEY, String.class)),
              Objects.requireNonNull(record.get(SOURCE_PROVENANCE_KIND, String.class)),
              Objects.requireNonNull(record.get(SOURCE_EVIDENCE_DIGEST, String.class)));
      if (schemaVersion.intValue() != SCHEMA_VERSION) {
        throw new IllegalArgumentException("Unsupported local intake schema version");
      }
      return new WorldAuthoredSourceIntakeReceipt(
          schemaVersion.intValue(),
          Objects.requireNonNull(record.get(TARGET_NAMESPACE, String.class)),
          Objects.requireNonNull(record.get(INTAKE_REQUEST_ID, UUID.class)),
          Objects.requireNonNull(record.get(OPERATION_ID, UUID.class)),
          Objects.requireNonNull(record.get(CANONICAL_TENANT_ID, UUID.class)),
          Objects.requireNonNull(record.get(WORLD_SLUG, String.class)),
          source.operationId(),
          source.evidenceDigest(),
          Objects.requireNonNull(record.get(REQUEST_DIGEST, String.class)),
          Objects.requireNonNull(record.get(RECEIPT_DIGEST, String.class)),
          Objects.requireNonNull(record.get(LOCAL_TENANT_KEY, Long.class)),
          source);
    } catch (RuntimeException exception) {
      if (exception instanceof InvalidIntakeEvidenceException) {
        throw exception;
      }
      throw new InvalidIntakeEvidenceException(
          "Persisted World authored-source intake evidence is invalid", exception);
    }
  }

  private static void requireNonNil(UUID value, String label) {
    if (value == null || NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(label + " must be a non-nil UUID");
    }
  }

  public static final class RegistrationConflictException extends IllegalStateException {
    public RegistrationConflictException(String message) {
      super(message);
    }
  }

  public static final class InvalidIntakeEvidenceException extends IllegalStateException {
    public InvalidIntakeEvidenceException(String message) {
      super(message);
    }

    public InvalidIntakeEvidenceException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
