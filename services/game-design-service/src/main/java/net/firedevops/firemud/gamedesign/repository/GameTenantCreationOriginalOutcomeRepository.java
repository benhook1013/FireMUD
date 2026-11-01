package net.firedevops.firemud.gamedesign.repository;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.gamedesign.repository.GameTenantCreationRepository.CreationRequestConflictException;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Owner-local persistence for the immutable original result of an Account-bound fresh-creation
 * attempt. This repository is deliberately not registered as an application bean or ingress.
 */
public final class GameTenantCreationOriginalOutcomeRepository {
  private static final String PENDING = "PENDING";
  private static final String CREATED = "CREATED";
  private static final String NOT_CREATED = "NOT_CREATED";
  private static final Pattern DNS_LABEL = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final Table<?> OUTCOME_TABLE =
      DSL.table(DSL.name("game_tenant_creation_original_outcomes"));
  private static final Table<?> OPERATION_TABLE =
      DSL.table(DSL.name("game_tenant_creation_operations"));
  private static final Field<String> TARGET_NAMESPACE =
      DSL.field(DSL.name("target_namespace"), String.class);
  private static final Field<UUID> CREATION_REQUEST_ID =
      DSL.field(DSL.name("creation_request_id"), UUID.class);
  private static final Field<UUID> ACCOUNT_AUTHORIZATION_OPERATION_ID =
      DSL.field(DSL.name("account_authorization_operation_id"), UUID.class);
  private static final Field<UUID> INITIATING_ACCOUNT_ID =
      DSL.field(DSL.name("initiating_account_id"), UUID.class);
  private static final Field<String> CREATION_REQUEST_DIGEST =
      DSL.field(DSL.name("creation_request_digest"), String.class);
  private static final Field<String> ACCOUNT_AUTHORIZATION_DIGEST =
      DSL.field(DSL.name("account_authorization_digest"), String.class);
  private static final Field<String> STATUS = DSL.field(DSL.name("status"), String.class);
  private static final Field<UUID> CREATION_OPERATION_ID =
      DSL.field(DSL.name("creation_operation_id"), UUID.class);
  private static final Field<String> CREATION_EVIDENCE_DIGEST =
      DSL.field(DSL.name("creation_evidence_digest"), String.class);

  private final DSLContext dsl;

  public GameTenantCreationOriginalOutcomeRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
  }

  /**
   * Claims the exact internal Account binding under the shared namespace/request identity lock. A
   * committed pre-migration V30 operation is never adopted into a new Account binding.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public OriginalOutcomeSnapshot claimLocked(OriginalOutcomeBinding binding) {
    requireActiveOwnerTransaction();
    validateBinding(binding);
    lockIdentity(binding);

    Record existing = findOutcome(binding);
    if (existing != null) {
      OriginalOutcomeSnapshot snapshot = toSnapshot(existing);
      requireSameBinding(snapshot.binding(), binding);
      return snapshot;
    }

    if (hasCreationOperation(binding)) {
      throw new CreationRequestConflictException(
          "A retained fresh tenant creation cannot be adopted into a new Account binding");
    }

    insertPending(binding);
    return requireOutcome(binding);
  }

  /**
   * Records the real V30 creation receipt in the same owner transaction as its creation. The
   * migration independently checks that the operation ID and evidence digest match V30 storage.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public OriginalOutcomeSnapshot markCreatedLocked(
      OriginalOutcomeBinding binding, FreshTenantCreationEvidence receipt) {
    requireActiveOwnerTransaction();
    validateBinding(binding);
    Objects.requireNonNull(receipt, "receipt");
    requireReceiptBinding(binding, receipt);
    lockIdentity(binding);

    Record existing = findOutcome(binding);
    if (existing == null) {
      throw new IllegalStateException("Fresh tenant original outcome was not claimed");
    }
    OriginalOutcomeSnapshot snapshot = toSnapshot(existing);
    requireSameBinding(snapshot.binding(), binding);
    if (snapshot.status() == OutcomeStatus.CREATED) {
      if (receipt.operationId().equals(snapshot.creationOperationId())
          && receipt.evidenceDigest().equals(snapshot.creationEvidenceDigest())) {
        return snapshot;
      }
      throw new CreationRequestConflictException(
          "Fresh tenant original outcome is already bound to a different creation receipt");
    }
    if (snapshot.status() == OutcomeStatus.NOT_CREATED) {
      throw new CreationRequestConflictException(
          "A definitively closed fresh tenant creation cannot be reopened");
    }

    int updated =
        dsl.update(OUTCOME_TABLE)
            .set(STATUS, CREATED)
            .set(CREATION_OPERATION_ID, receipt.operationId())
            .set(CREATION_EVIDENCE_DIGEST, receipt.evidenceDigest())
            .where(
                TARGET_NAMESPACE
                    .eq(binding.targetNamespace())
                    .and(CREATION_REQUEST_ID.eq(binding.creationRequestId()))
                    .and(STATUS.eq(PENDING)))
            .execute();
    if (updated != 1) {
      throw new IllegalStateException("Fresh tenant original outcome claim was lost");
    }
    return requireOutcome(binding);
  }

  /**
   * Persists an exact original tombstone. V30 storage and the migration's shared identity lock
   * ensure that this transition cannot race with or follow a creation operation.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public OriginalOutcomeSnapshot closeNotCreatedLocked(OriginalOutcomeBinding binding) {
    requireActiveOwnerTransaction();
    validateBinding(binding);
    lockIdentity(binding);

    Record existing = findOutcome(binding);
    if (existing != null) {
      OriginalOutcomeSnapshot snapshot = toSnapshot(existing);
      requireSameBinding(snapshot.binding(), binding);
      if (snapshot.status() == OutcomeStatus.NOT_CREATED) {
        return snapshot;
      }
      if (snapshot.status() == OutcomeStatus.CREATED) {
        throw new CreationRequestConflictException(
            "A created fresh tenant original cannot be closed as not created");
      }
    } else {
      if (hasCreationOperation(binding)) {
        throw new CreationRequestConflictException(
            "A retained fresh tenant creation cannot be closed as not created");
      }
      insertPending(binding);
    }

    int updated =
        dsl.update(OUTCOME_TABLE)
            .set(STATUS, NOT_CREATED)
            .where(
                TARGET_NAMESPACE
                    .eq(binding.targetNamespace())
                    .and(CREATION_REQUEST_ID.eq(binding.creationRequestId()))
                    .and(STATUS.eq(PENDING)))
            .execute();
    if (updated != 1) {
      throw new IllegalStateException("Fresh tenant original outcome claim was lost");
    }
    return requireOutcome(binding);
  }

  /**
   * Independently reads only an exact, committed terminal outcome. Empty means no durable original
   * outcome was found; it is not evidence that no creation occurred.
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED, readOnly = true)
  public Optional<OriginalOutcomeSnapshot> readCommitted(OriginalOutcomeBinding binding) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Fresh tenant original outcome read requires a committed-outcome owner read");
    }
    validateBinding(binding);
    Record record = findOutcome(binding);
    if (record == null) {
      return Optional.empty();
    }
    OriginalOutcomeSnapshot snapshot = toSnapshot(record);
    requireSameBinding(snapshot.binding(), binding);
    if (snapshot.status() == OutcomeStatus.PENDING) {
      throw new IllegalStateException("A pending fresh tenant original outcome was committed");
    }
    return Optional.of(snapshot);
  }

  private void insertPending(OriginalOutcomeBinding binding) {
    int inserted =
        dsl.insertInto(OUTCOME_TABLE)
            .set(TARGET_NAMESPACE, binding.targetNamespace())
            .set(CREATION_REQUEST_ID, binding.creationRequestId())
            .set(ACCOUNT_AUTHORIZATION_OPERATION_ID, binding.accountAuthorizationOperationId())
            .set(INITIATING_ACCOUNT_ID, binding.initiatingAccountId())
            .set(CREATION_REQUEST_DIGEST, binding.creationRequestDigest())
            .set(ACCOUNT_AUTHORIZATION_DIGEST, binding.accountAuthorizationDigest())
            .set(STATUS, PENDING)
            .execute();
    if (inserted != 1) {
      throw new IllegalStateException("Fresh tenant original outcome claim was not inserted");
    }
  }

  private Record findOutcome(OriginalOutcomeBinding binding) {
    return dsl.selectFrom(OUTCOME_TABLE)
        .where(
            TARGET_NAMESPACE
                .eq(binding.targetNamespace())
                .and(CREATION_REQUEST_ID.eq(binding.creationRequestId())))
        .fetchOne();
  }

  private boolean hasCreationOperation(OriginalOutcomeBinding binding) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(OPERATION_TABLE)
            .where(
                TARGET_NAMESPACE
                    .eq(binding.targetNamespace())
                    .and(CREATION_REQUEST_ID.eq(binding.creationRequestId()))));
  }

  private OriginalOutcomeSnapshot requireOutcome(OriginalOutcomeBinding binding) {
    Record record = findOutcome(binding);
    if (record == null) {
      throw new IllegalStateException("Fresh tenant original outcome is missing");
    }
    OriginalOutcomeSnapshot snapshot = toSnapshot(record);
    requireSameBinding(snapshot.binding(), binding);
    return snapshot;
  }

  private OriginalOutcomeSnapshot toSnapshot(Record record) {
    OriginalOutcomeBinding binding =
        new OriginalOutcomeBinding(
            record.get(TARGET_NAMESPACE),
            record.get(CREATION_REQUEST_ID),
            record.get(ACCOUNT_AUTHORIZATION_OPERATION_ID),
            record.get(INITIATING_ACCOUNT_ID),
            record.get(CREATION_REQUEST_DIGEST),
            record.get(ACCOUNT_AUTHORIZATION_DIGEST));
    String storedStatus = record.get(STATUS);
    OutcomeStatus outcomeStatus =
        switch (storedStatus == null ? "" : storedStatus) {
          case PENDING -> OutcomeStatus.PENDING;
          case CREATED -> OutcomeStatus.CREATED;
          case NOT_CREATED -> OutcomeStatus.NOT_CREATED;
          default -> throw new IllegalStateException("Stored fresh tenant outcome is malformed");
        };
    return new OriginalOutcomeSnapshot(
        binding,
        outcomeStatus,
        record.get(CREATION_OPERATION_ID),
        record.get(CREATION_EVIDENCE_DIGEST));
  }

  private void lockIdentity(OriginalOutcomeBinding binding) {
    dsl.fetch(
        "SELECT lock_game_tenant_creation_identity(?, ?)",
        binding.targetNamespace(),
        binding.creationRequestId());
  }

  private void requireActiveOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Fresh tenant original outcome mutation requires an active Game Design owner transaction");
    }
    if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Fresh tenant original outcome mutation requires a writable Game Design owner transaction");
    }
  }

  private void validateBinding(OriginalOutcomeBinding binding) {
    Objects.requireNonNull(binding, "binding");
  }

  private void requireReceiptBinding(
      OriginalOutcomeBinding binding, FreshTenantCreationEvidence receipt) {
    if (!binding.targetNamespace().equals(receipt.targetNamespace())
        || !binding.creationRequestId().equals(receipt.creationRequestId())
        || !binding.creationRequestDigest().equals(receipt.requestDigest())) {
      throw new CreationRequestConflictException(
          "Fresh tenant creation receipt does not match the original request binding");
    }
  }

  private void requireSameBinding(OriginalOutcomeBinding stored, OriginalOutcomeBinding requested) {
    if (!stored.equals(requested)) {
      throw new CreationRequestConflictException(
          "Fresh tenant original identity was reused with changed Account or request binding");
    }
  }

  private static void validateNamespace(String targetNamespace) {
    Objects.requireNonNull(targetNamespace, "targetNamespace");
    if (!DNS_LABEL.matcher(targetNamespace).matches()) {
      throw new IllegalArgumentException("targetNamespace must be one lowercase DNS label");
    }
  }

  private static void requireNonNil(UUID value, String label) {
    Objects.requireNonNull(value, label);
    if (NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(label + " must not be nil");
    }
  }

  /** The six immutable fields that identify this internal original attempt. */
  public record OriginalOutcomeBinding(
      String targetNamespace,
      UUID creationRequestId,
      UUID accountAuthorizationOperationId,
      UUID initiatingAccountId,
      String creationRequestDigest,
      String accountAuthorizationDigest) {
    public OriginalOutcomeBinding {
      validateNamespace(targetNamespace);
      requireNonNil(creationRequestId, "creationRequestId");
      requireNonNil(accountAuthorizationOperationId, "accountAuthorizationOperationId");
      requireNonNil(initiatingAccountId, "initiatingAccountId");
      if (!GameTenantCreationDigest.isDigest(creationRequestDigest)) {
        throw new IllegalArgumentException(
            "creationRequestDigest must be a lowercase SHA-256 digest");
      }
      if (!GameTenantCreationDigest.isDigest(accountAuthorizationDigest)) {
        throw new IllegalArgumentException(
            "accountAuthorizationDigest must be a lowercase SHA-256 digest");
      }
    }
  }

  public enum OutcomeStatus {
    PENDING,
    CREATED,
    NOT_CREATED
  }

  /** Typed immutable readback of the exact original outcome row. */
  public record OriginalOutcomeSnapshot(
      OriginalOutcomeBinding binding,
      OutcomeStatus status,
      UUID creationOperationId,
      String creationEvidenceDigest) {
    public OriginalOutcomeSnapshot {
      Objects.requireNonNull(binding, "binding");
      Objects.requireNonNull(status, "status");
      if (status == OutcomeStatus.CREATED) {
        requireNonNil(creationOperationId, "creationOperationId");
        if (!GameTenantCreationDigest.isDigest(creationEvidenceDigest)) {
          throw new IllegalArgumentException(
              "creationEvidenceDigest must be a lowercase SHA-256 digest");
        }
      } else if (creationOperationId != null || creationEvidenceDigest != null) {
        throw new IllegalArgumentException(
            "Only a created fresh tenant outcome may contain a creation receipt");
      }
    }
  }
}
