package net.firedevops.firemud.gamedesign.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec.CommittedReceipt;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec.IntakeRequest;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Durable Game Design outbox for exact, fresh authored-world source delivery to World. */
@Repository
public class GameAuthoredWorldSourceDeliveryRepository {
  private static final int SCHEMA_VERSION = 1;
  private static final String NEW_GAME_ROW = "NEW_GAME_ROW";
  private static final int MAX_PENDING_BATCH_SIZE = 100;
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private static final Table<?> DELIVERY =
      DSL.table(DSL.name("game_design_authored_world_source_deliveries"));
  private static final Field<UUID> DELIVERY_SOURCE_OPERATION_ID =
      DSL.field(DSL.name("source_operation_id"), UUID.class);
  private static final Field<Integer> DELIVERY_SOURCE_SCHEMA_VERSION =
      DSL.field(DSL.name("source_schema_version"), Integer.class);
  private static final Field<String> DELIVERY_NAMESPACE =
      DSL.field(DSL.name("target_namespace"), String.class);
  private static final Field<UUID> DELIVERY_REGISTRATION_REQUEST_ID =
      DSL.field(DSL.name("registration_request_id"), UUID.class);
  private static final Field<String> DELIVERY_SOURCE_REQUEST_DIGEST =
      DSL.field(DSL.name("source_request_digest"), String.class);
  private static final Field<UUID> DELIVERY_CANONICAL_TENANT_ID =
      DSL.field(DSL.name("canonical_tenant_id"), UUID.class);
  private static final Field<String> DELIVERY_TENANT_SLUG =
      DSL.field(DSL.name("tenant_slug"), String.class);
  private static final Field<String> DELIVERY_WORLD_SLUG =
      DSL.field(DSL.name("world_slug"), String.class);
  private static final Field<String> DELIVERY_WORLD_DISPLAY_NAME =
      DSL.field(DSL.name("world_display_name"), String.class);
  private static final Field<Long> DELIVERY_SOURCE_GAME_ROW_ID =
      DSL.field(DSL.name("source_game_row_id"), Long.class);
  private static final Field<String> DELIVERY_SOURCE_GAME_TENANT_KEY =
      DSL.field(DSL.name("source_game_tenant_key"), String.class);
  private static final Field<String> DELIVERY_PROVENANCE_KIND =
      DSL.field(DSL.name("provenance_kind"), String.class);
  private static final Field<String> DELIVERY_SOURCE_EVIDENCE_DIGEST =
      DSL.field(DSL.name("source_evidence_digest"), String.class);
  private static final Field<Integer> DELIVERY_INTAKE_SCHEMA_VERSION =
      DSL.field(DSL.name("intake_schema_version"), Integer.class);
  private static final Field<UUID> DELIVERY_INTAKE_REQUEST_ID =
      DSL.field(DSL.name("intake_request_id"), UUID.class);
  private static final Field<String> DELIVERY_INTAKE_REQUEST_DIGEST =
      DSL.field(DSL.name("intake_request_digest"), String.class);
  private static final Field<UUID> DELIVERY_WORLD_OPERATION_ID =
      DSL.field(DSL.name("world_operation_id"), UUID.class);
  private static final Field<String> DELIVERY_WORLD_REQUEST_DIGEST =
      DSL.field(DSL.name("world_request_digest"), String.class);
  private static final Field<String> DELIVERY_WORLD_RECEIPT_DIGEST =
      DSL.field(DSL.name("world_receipt_digest"), String.class);
  private static final Field<OffsetDateTime> DELIVERY_ACKNOWLEDGED_AT =
      DSL.field(DSL.name("acknowledged_at"), OffsetDateTime.class);

  private static final Table<?> SOURCE =
      DSL.table(DSL.name("game_design_authored_world_source_operations"));
  private static final Field<UUID> SOURCE_OPERATION_ID =
      DSL.field(DSL.name("operation_id"), UUID.class);
  private static final Field<Integer> SOURCE_SCHEMA_VERSION =
      DSL.field(DSL.name("schema_version"), Integer.class);
  private static final Field<String> SOURCE_NAMESPACE =
      DSL.field(DSL.name("target_namespace"), String.class);
  private static final Field<UUID> SOURCE_REGISTRATION_REQUEST_ID =
      DSL.field(DSL.name("registration_request_id"), UUID.class);
  private static final Field<String> SOURCE_REQUEST_DIGEST =
      DSL.field(DSL.name("request_digest"), String.class);
  private static final Field<UUID> SOURCE_CANONICAL_TENANT_ID =
      DSL.field(DSL.name("canonical_tenant_id"), UUID.class);
  private static final Field<String> SOURCE_TENANT_SLUG =
      DSL.field(DSL.name("tenant_slug"), String.class);
  private static final Field<String> SOURCE_WORLD_SLUG =
      DSL.field(DSL.name("world_slug"), String.class);
  private static final Field<String> SOURCE_WORLD_DISPLAY_NAME =
      DSL.field(DSL.name("world_display_name"), String.class);
  private static final Field<Long> SOURCE_GAME_ROW_ID =
      DSL.field(DSL.name("source_game_row_id"), Long.class);
  private static final Field<String> SOURCE_GAME_TENANT_KEY =
      DSL.field(DSL.name("source_game_tenant_key"), String.class);
  private static final Field<String> SOURCE_PROVENANCE_KIND =
      DSL.field(DSL.name("provenance_kind"), String.class);
  private static final Field<String> SOURCE_EVIDENCE_DIGEST =
      DSL.field(DSL.name("evidence_digest"), String.class);

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

  private static final Table<?> TENANT_BINDING =
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

  private final DSLContext dsl;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "The injected DSLContext is the repository's owner transaction collaborator.")
  public GameAuthoredWorldSourceDeliveryRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  /** Persists one stable World intake identity inside the source-registration transaction. */
  @Transactional(propagation = Propagation.MANDATORY)
  public DeliveryClaim enqueueFresh(AuthoredWorldSourceEvidence source) {
    requireActiveWritableOwnerTransaction();
    requireFreshSource(source);
    requireExactPersistedSource(source);

    Record existing = findDelivery(source.operationId());
    if (existing != null) {
      DeliveryClaim claim = toClaim(existing);
      requireSameSource(source, claim.source());
      return claim;
    }

    UUID intakeRequestId = UUID.randomUUID();
    while (NIL_UUID.equals(intakeRequestId) || source.operationId().equals(intakeRequestId)) {
      intakeRequestId = UUID.randomUUID();
    }
    IntakeRequest request =
        new IntakeRequest(
            SCHEMA_VERSION,
            source.targetNamespace(),
            intakeRequestId,
            source.canonicalTenantId(),
            source.worldSlug(),
            source.operationId(),
            source.evidenceDigest());
    String intakeRequestDigest = WorldAuthoredSourceIntakeGrpcCodec.requestDigest(request);

    int inserted =
        dsl.insertInto(DELIVERY)
            .set(DELIVERY_SOURCE_OPERATION_ID, source.operationId())
            .set(DELIVERY_SOURCE_SCHEMA_VERSION, source.schemaVersion())
            .set(DELIVERY_NAMESPACE, source.targetNamespace())
            .set(DELIVERY_REGISTRATION_REQUEST_ID, source.registrationRequestId())
            .set(DELIVERY_SOURCE_REQUEST_DIGEST, source.requestDigest())
            .set(DELIVERY_CANONICAL_TENANT_ID, source.canonicalTenantId())
            .set(DELIVERY_TENANT_SLUG, source.tenantSlug())
            .set(DELIVERY_WORLD_SLUG, source.worldSlug())
            .set(DELIVERY_WORLD_DISPLAY_NAME, source.worldDisplayName())
            .set(DELIVERY_SOURCE_GAME_ROW_ID, source.sourceGameRowId())
            .set(DELIVERY_SOURCE_GAME_TENANT_KEY, source.sourceGameTenantKey())
            .set(DELIVERY_PROVENANCE_KIND, source.provenanceKind())
            .set(DELIVERY_SOURCE_EVIDENCE_DIGEST, source.evidenceDigest())
            .set(DELIVERY_INTAKE_SCHEMA_VERSION, request.schemaVersion())
            .set(DELIVERY_INTAKE_REQUEST_ID, request.intakeRequestId())
            .set(DELIVERY_INTAKE_REQUEST_DIGEST, intakeRequestDigest)
            .onConflictDoNothing()
            .execute();
    if (inserted != 1) {
      Record conflict = findDelivery(source.operationId());
      if (conflict != null) {
        DeliveryClaim claim = toClaim(conflict);
        requireSameSource(source, claim.source());
        return claim;
      }
      throw new DeliveryConflictException(
          "Authored-world source delivery conflicts with an existing owner claim");
    }

    Record persisted = findDelivery(source.operationId());
    if (persisted == null) {
      throw new IllegalStateException("Authored-world source delivery claim is missing");
    }
    DeliveryClaim claim = toClaim(persisted);
    requireSameSource(source, claim.source());
    if (!request.equals(claim.request()) || claim.acknowledgedReceipt().isPresent()) {
      throw new DeliveryConflictException(
          "Committed authored-world source delivery differs from its original claim");
    }
    return claim;
  }

  /** Reads one complete committed delivery claim without allocating or repairing it. */
  @Transactional(propagation = Propagation.NOT_SUPPORTED, readOnly = true)
  public Optional<DeliveryClaim> read(UUID sourceOperationId) {
    requireNonNil(sourceOperationId, "sourceOperationId");
    requireCommittedOutcomeRead();
    Record record = findDelivery(sourceOperationId);
    if (record == null) {
      return Optional.empty();
    }
    DeliveryClaim claim = toClaim(record);
    requireExactPersistedSource(claim.source());
    return Optional.of(claim);
  }

  /**
   * Reads one bounded page of exact pending claims for this workload namespace. The stable intake
   * request identity is the keyset cursor; callers cycle back to the beginning after reaching the
   * end so a failed older claim cannot starve later claims.
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED, readOnly = true)
  public List<DeliveryClaim> readPending(
      String targetNamespace, UUID afterIntakeRequestId, int limit) {
    if (!GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
      throw new IllegalArgumentException("Workload namespace must be one canonical DNS label");
    }
    if (afterIntakeRequestId != null) {
      requireNonNil(afterIntakeRequestId, "afterIntakeRequestId");
    }
    if (limit < 1 || limit > MAX_PENDING_BATCH_SIZE) {
      throw new IllegalArgumentException(
          "Pending authored-world delivery page size must be between 1 and "
              + MAX_PENDING_BATCH_SIZE);
    }
    requireCommittedOutcomeRead();

    var rows =
        dsl.selectFrom(DELIVERY)
            .where(
                DELIVERY_NAMESPACE
                    .eq(targetNamespace)
                    .and(DELIVERY_PROVENANCE_KIND.eq(NEW_GAME_ROW))
                    .and(DELIVERY_WORLD_OPERATION_ID.isNull())
                    .and(DELIVERY_WORLD_REQUEST_DIGEST.isNull())
                    .and(DELIVERY_WORLD_RECEIPT_DIGEST.isNull())
                    .and(DELIVERY_ACKNOWLEDGED_AT.isNull())
                    .and(
                        afterIntakeRequestId == null
                            ? DSL.noCondition()
                            : DELIVERY_INTAKE_REQUEST_ID.gt(afterIntakeRequestId)))
            .orderBy(DELIVERY_INTAKE_REQUEST_ID.asc())
            .limit(limit)
            .fetch();
    List<DeliveryClaim> claims = new ArrayList<>(rows.size());
    for (Record row : rows) {
      DeliveryClaim claim = toClaim(row);
      if (!targetNamespace.equals(claim.request().targetNamespace())
          || claim.acknowledgedReceipt().isPresent()) {
        throw new DeliveryConflictException(
            "Pending authored-world delivery read returned a claim outside its exact selector");
      }
      requireExactPersistedSource(claim.source());
      claims.add(claim);
    }
    return List.copyOf(claims);
  }

  /** Records only the first full World receipt, or returns an identical concurrent winner. */
  @Transactional(propagation = Propagation.MANDATORY)
  public DeliveryClaim acknowledge(DeliveryClaim expected, CommittedReceipt receipt) {
    requireActiveWritableOwnerTransaction();
    Objects.requireNonNull(expected, "expected");
    Objects.requireNonNull(receipt, "receipt");
    requireFreshSource(expected.source());
    requireReceiptMatchesRequest(expected.request(), receipt);

    Record currentRecord = findDelivery(expected.source().operationId());
    if (currentRecord == null) {
      throw new DeliveryConflictException("Authored-world source delivery claim is missing");
    }
    DeliveryClaim current = toClaim(currentRecord);
    requireSameClaim(expected, current);
    requireExactPersistedSource(current.source());
    if (current.acknowledgedReceipt().isPresent()) {
      CommittedReceipt original = current.acknowledgedReceipt().orElseThrow();
      if (!original.equals(receipt)) {
        throw new DeliveryConflictException(
            "Authored-world source delivery already has a different World receipt");
      }
      return current;
    }

    int updated =
        dsl.update(DELIVERY)
            .set(DELIVERY_WORLD_OPERATION_ID, receipt.operationId())
            .set(DELIVERY_WORLD_REQUEST_DIGEST, receipt.requestDigest())
            .set(DELIVERY_WORLD_RECEIPT_DIGEST, receipt.receiptDigest())
            .set(DELIVERY_ACKNOWLEDGED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .where(
                DELIVERY_SOURCE_OPERATION_ID
                    .eq(expected.source().operationId())
                    .and(DELIVERY_WORLD_OPERATION_ID.isNull())
                    .and(DELIVERY_WORLD_REQUEST_DIGEST.isNull())
                    .and(DELIVERY_WORLD_RECEIPT_DIGEST.isNull())
                    .and(DELIVERY_ACKNOWLEDGED_AT.isNull()))
            .execute();
    if (updated > 1) {
      throw new IllegalStateException("Authored-world source delivery ACK changed multiple rows");
    }

    Record acknowledgedRecord = findDelivery(expected.source().operationId());
    if (acknowledgedRecord == null) {
      throw new IllegalStateException("Authored-world source delivery disappeared during ACK");
    }
    DeliveryClaim acknowledged = toClaim(acknowledgedRecord);
    requireSameClaim(expected, acknowledged);
    CommittedReceipt durableReceipt =
        acknowledged
            .acknowledgedReceipt()
            .orElseThrow(
                () -> new DeliveryConflictException("World receipt ACK did not read back"));
    if (!durableReceipt.equals(receipt)) {
      throw new DeliveryConflictException(
          "Concurrent authored-world source delivery ACK has a different World receipt");
    }
    return acknowledged;
  }

  /** Validates a pre-V41 registration retry when its optional delivery row already exists. */
  @Transactional(propagation = Propagation.MANDATORY)
  void validateExistingIfPresent(AuthoredWorldSourceEvidence source) {
    requireActiveWritableOwnerTransaction();
    requireFreshSource(source);
    Record existing = findDelivery(source.operationId());
    if (existing == null) {
      return;
    }
    DeliveryClaim claim = toClaim(existing);
    requireSameSource(source, claim.source());
  }

  private Record findDelivery(UUID sourceOperationId) {
    return dsl.selectFrom(DELIVERY)
        .where(DELIVERY_SOURCE_OPERATION_ID.eq(sourceOperationId))
        .fetchOne();
  }

  private DeliveryClaim toClaim(Record row) {
    AuthoredWorldSourceEvidence source;
    IntakeRequest request;
    Optional<CommittedReceipt> acknowledgedReceipt;
    try {
      source =
          new AuthoredWorldSourceEvidence(
              row.get(DELIVERY_SOURCE_SCHEMA_VERSION),
              row.get(DELIVERY_NAMESPACE),
              row.get(DELIVERY_REGISTRATION_REQUEST_ID),
              row.get(DELIVERY_SOURCE_OPERATION_ID),
              row.get(DELIVERY_SOURCE_REQUEST_DIGEST),
              row.get(DELIVERY_CANONICAL_TENANT_ID),
              row.get(DELIVERY_TENANT_SLUG),
              row.get(DELIVERY_WORLD_SLUG),
              row.get(DELIVERY_WORLD_DISPLAY_NAME),
              row.get(DELIVERY_SOURCE_GAME_ROW_ID),
              row.get(DELIVERY_SOURCE_GAME_TENANT_KEY),
              row.get(DELIVERY_PROVENANCE_KIND),
              row.get(DELIVERY_SOURCE_EVIDENCE_DIGEST));
      request =
          new IntakeRequest(
              row.get(DELIVERY_INTAKE_SCHEMA_VERSION),
              row.get(DELIVERY_NAMESPACE),
              row.get(DELIVERY_INTAKE_REQUEST_ID),
              row.get(DELIVERY_CANONICAL_TENANT_ID),
              row.get(DELIVERY_WORLD_SLUG),
              row.get(DELIVERY_SOURCE_OPERATION_ID),
              row.get(DELIVERY_SOURCE_EVIDENCE_DIGEST));
      if (!WorldAuthoredSourceIntakeGrpcCodec.requestDigest(request)
          .equals(row.get(DELIVERY_INTAKE_REQUEST_DIGEST))) {
        throw new IllegalArgumentException("Persisted World intake request digest is invalid");
      }
      UUID worldOperationId = row.get(DELIVERY_WORLD_OPERATION_ID);
      String worldRequestDigest = row.get(DELIVERY_WORLD_REQUEST_DIGEST);
      String worldReceiptDigest = row.get(DELIVERY_WORLD_RECEIPT_DIGEST);
      if (worldOperationId == null && worldRequestDigest == null && worldReceiptDigest == null) {
        acknowledgedReceipt = Optional.empty();
      } else {
        acknowledgedReceipt =
            Optional.of(
                new CommittedReceipt(
                    request.schemaVersion(),
                    request.targetNamespace(),
                    request.intakeRequestId(),
                    worldOperationId,
                    request.canonicalTenantId(),
                    request.worldSlug(),
                    request.sourceOperationId(),
                    request.expectedSourceEvidenceDigest(),
                    worldRequestDigest,
                    worldReceiptDigest));
      }
      return new DeliveryClaim(source, request, acknowledgedReceipt);
    } catch (RuntimeException exception) {
      if (exception instanceof DeliveryConflictException conflict) {
        throw conflict;
      }
      throw new InvalidDeliveryClaimException(
          "Persisted authored-world source delivery claim is invalid", exception);
    }
  }

  private void requireExactPersistedSource(AuthoredWorldSourceEvidence expected) {
    Record sourceRow =
        dsl.selectFrom(SOURCE).where(SOURCE_OPERATION_ID.eq(expected.operationId())).fetchOne();
    if (sourceRow == null || !expected.equals(toSourceEvidence(sourceRow))) {
      throw new InvalidDeliveryClaimException(
          "Authored-world delivery does not match the complete persisted source receipt");
    }

    Record gameRow =
        dsl.select(
                GAME_ID,
                GAME_TENANT_ID,
                GAME_CANONICAL_TENANT_ID,
                GAME_PROVENANCE_KIND,
                GAME_SOURCE_ROW_ID,
                GAME_SOURCE_TENANT_KEY)
            .from(GAME)
            .where(GAME_ID.eq(expected.sourceGameRowId()))
            .fetchOne();
    if (gameRow == null
        || !Long.valueOf(expected.sourceGameRowId()).equals(gameRow.get(GAME_ID))
        || !expected.sourceGameTenantKey().equals(gameRow.get(GAME_TENANT_ID))
        || !expected.canonicalTenantId().equals(gameRow.get(GAME_CANONICAL_TENANT_ID))
        || !expected.provenanceKind().equals(gameRow.get(GAME_PROVENANCE_KIND))
        || !Long.valueOf(expected.sourceGameRowId()).equals(gameRow.get(GAME_SOURCE_ROW_ID))
        || !expected.sourceGameTenantKey().equals(gameRow.get(GAME_SOURCE_TENANT_KEY))) {
      throw new InvalidDeliveryClaimException(
          "Authored-world delivery source no longer matches its Game Design game row");
    }

    Record bindingRow =
        dsl.select(
                BINDING_NAMESPACE,
                BINDING_CANONICAL_TENANT_ID,
                BINDING_TENANT_SLUG,
                BINDING_SOURCE_GAME_ROW_ID,
                BINDING_SOURCE_GAME_TENANT_KEY,
                BINDING_PROVENANCE_KIND)
            .from(TENANT_BINDING)
            .where(
                BINDING_NAMESPACE
                    .eq(expected.targetNamespace())
                    .and(BINDING_CANONICAL_TENANT_ID.eq(expected.canonicalTenantId())))
            .fetchOne();
    if (bindingRow == null
        || !expected.tenantSlug().equals(bindingRow.get(BINDING_TENANT_SLUG))
        || !Long.valueOf(expected.sourceGameRowId())
            .equals(bindingRow.get(BINDING_SOURCE_GAME_ROW_ID))
        || !expected.sourceGameTenantKey().equals(bindingRow.get(BINDING_SOURCE_GAME_TENANT_KEY))
        || !expected.provenanceKind().equals(bindingRow.get(BINDING_PROVENANCE_KIND))) {
      throw new InvalidDeliveryClaimException(
          "Authored-world delivery source no longer matches its tenant selector binding");
    }
  }

  private AuthoredWorldSourceEvidence toSourceEvidence(Record row) {
    try {
      return new AuthoredWorldSourceEvidence(
          row.get(SOURCE_SCHEMA_VERSION),
          row.get(SOURCE_NAMESPACE),
          row.get(SOURCE_REGISTRATION_REQUEST_ID),
          row.get(SOURCE_OPERATION_ID),
          row.get(SOURCE_REQUEST_DIGEST),
          row.get(SOURCE_CANONICAL_TENANT_ID),
          row.get(SOURCE_TENANT_SLUG),
          row.get(SOURCE_WORLD_SLUG),
          row.get(SOURCE_WORLD_DISPLAY_NAME),
          row.get(SOURCE_GAME_ROW_ID),
          row.get(SOURCE_GAME_TENANT_KEY),
          row.get(SOURCE_PROVENANCE_KIND),
          row.get(SOURCE_EVIDENCE_DIGEST));
    } catch (RuntimeException exception) {
      throw new InvalidDeliveryClaimException(
          "Persisted authored-world source evidence is invalid", exception);
    }
  }

  private static void requireSameClaim(DeliveryClaim expected, DeliveryClaim actual) {
    requireSameSource(expected.source(), actual.source());
    if (!expected.request().equals(actual.request())) {
      throw new DeliveryConflictException(
          "Authored-world source delivery request identity changed after dispatch");
    }
  }

  private static void requireSameSource(
      AuthoredWorldSourceEvidence expected, AuthoredWorldSourceEvidence actual) {
    if (!expected.equals(actual)) {
      throw new DeliveryConflictException(
          "Authored-world source delivery no longer matches its immutable source evidence");
    }
  }

  private static void requireReceiptMatchesRequest(
      IntakeRequest request, CommittedReceipt receipt) {
    if (receipt.schemaVersion() != request.schemaVersion()
        || !receipt.targetNamespace().equals(request.targetNamespace())
        || !receipt.intakeRequestId().equals(request.intakeRequestId())
        || !receipt.canonicalTenantId().equals(request.canonicalTenantId())
        || !receipt.worldSlug().equals(request.worldSlug())
        || !receipt.sourceOperationId().equals(request.sourceOperationId())
        || !receipt.sourceEvidenceDigest().equals(request.expectedSourceEvidenceDigest())) {
      throw new DeliveryConflictException(
          "World receipt does not bind the exact persisted authored-world source request");
    }
  }

  private static void requireFreshSource(AuthoredWorldSourceEvidence source) {
    Objects.requireNonNull(source, "source");
    if (!NEW_GAME_ROW.equals(source.provenanceKind())) {
      throw new DeliveryConflictException(
          "Only a proven NEW_GAME_ROW authored-world source may be delivered to World");
    }
  }

  private static void requireNonNil(UUID value, String label) {
    if (value == null || NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(label + " must be a canonical non-nil UUID");
    }
  }

  private static void requireActiveWritableOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Authored-world source delivery mutation requires an active writable owner transaction");
    }
  }

  private static void requireCommittedOutcomeRead() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Authored-world source delivery read requires a committed-outcome owner read");
    }
  }

  /** Exact persisted source, immutable World intake identity, and first optional World ACK. */
  public record DeliveryClaim(
      AuthoredWorldSourceEvidence source,
      IntakeRequest request,
      Optional<CommittedReceipt> acknowledgedReceipt) {
    public DeliveryClaim {
      Objects.requireNonNull(source, "source");
      Objects.requireNonNull(request, "request");
      Objects.requireNonNull(acknowledgedReceipt, "acknowledgedReceipt");
      requireFreshSource(source);
      IntakeRequest expected =
          new IntakeRequest(
              SCHEMA_VERSION,
              source.targetNamespace(),
              request.intakeRequestId(),
              source.canonicalTenantId(),
              source.worldSlug(),
              source.operationId(),
              source.evidenceDigest());
      if (!expected.equals(request)) {
        throw new DeliveryConflictException(
            "World intake request does not bind the complete authored-world source");
      }
      acknowledgedReceipt.ifPresent(receipt -> requireReceiptMatchesRequest(request, receipt));
    }
  }

  public static class DeliveryConflictException extends IllegalStateException {
    public DeliveryConflictException(String message) {
      super(message);
    }
  }

  public static final class InvalidDeliveryClaimException extends DeliveryConflictException {
    public InvalidDeliveryClaimException(String message) {
      super(message);
    }

    public InvalidDeliveryClaimException(String message, Throwable cause) {
      super(message);
      initCause(cause);
    }
  }
}
