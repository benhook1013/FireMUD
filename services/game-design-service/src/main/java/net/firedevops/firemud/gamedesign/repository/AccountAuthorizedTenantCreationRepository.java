package net.firedevops.firemud.gamedesign.repository;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.gamedesign.repository.GameTenantCreationOriginalOutcomeRepository.OriginalOutcomeBinding;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Narrow immutable owner record joining current Account authorization to the committed creation.
 */
public final class AccountAuthorizedTenantCreationRepository {
  private static final Table<?> TABLE =
      DSL.table(DSL.name("game_tenant_creation_authorized_dispatches"));
  private static final Field<String> TARGET_NAMESPACE =
      DSL.field(DSL.name("target_namespace"), String.class);
  private static final Field<UUID> CREATION_REQUEST_ID =
      DSL.field(DSL.name("creation_request_id"), UUID.class);
  private static final Field<UUID> AUTHORIZATION_OPERATION_ID =
      DSL.field(DSL.name("account_authorization_operation_id"), UUID.class);
  private static final Field<UUID> INITIATING_ACCOUNT_ID =
      DSL.field(DSL.name("initiating_account_id"), UUID.class);
  private static final Field<String> CREATION_REQUEST_DIGEST =
      DSL.field(DSL.name("creation_request_digest"), String.class);
  private static final Field<String> ACCOUNT_AUTHORIZATION_DIGEST =
      DSL.field(DSL.name("account_authorization_digest"), String.class);
  private static final Field<UUID> AUTHORIZATION_READ_REQUEST_ID =
      DSL.field(DSL.name("authorization_read_request_id"), UUID.class);
  private static final Field<Long> VALID_BEFORE_EPOCH_MILLIS =
      DSL.field(DSL.name("valid_before_epoch_millis"), Long.class);

  private final DSLContext dsl;

  public AccountAuthorizedTenantCreationRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
  }

  /** Applies database bounds before taking the shared original-outcome identity lock. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void applyLocalWriteTimeouts() {
    requireWritableOwnerTransaction();
    dsl.execute("SET LOCAL lock_timeout = '3s'");
    dsl.execute("SET LOCAL statement_timeout = '12s'");
  }

  /** Uses a fresh database clock before any receiver-side mutation. */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean isAuthorizationCurrent(long validBeforeEpochMillis) {
    requireWritableOwnerTransaction();
    if (validBeforeEpochMillis <= 0) {
      throw new IllegalArgumentException("Account authorization deadline must be positive");
    }
    Record current =
        dsl.fetchOne(
            "SELECT clock_timestamp() < to_timestamp(? / 1000.0) AS authorization_current",
            validBeforeEpochMillis);
    return current != null
        && Boolean.TRUE.equals(current.get("authorization_current", Boolean.class));
  }

  /** Inserts the Account response correlation and deadline in the V30/V1033 owner transaction. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void recordAuthorizedDispatch(
      OriginalOutcomeBinding binding,
      UUID authorizationReadRequestId,
      long validBeforeEpochMillis) {
    requireWritableOwnerTransaction();
    AuthorizedDispatch dispatch =
        new AuthorizedDispatch(binding, authorizationReadRequestId, validBeforeEpochMillis);
    int inserted =
        dsl.insertInto(TABLE)
            .set(TARGET_NAMESPACE, binding.targetNamespace())
            .set(CREATION_REQUEST_ID, binding.creationRequestId())
            .set(AUTHORIZATION_OPERATION_ID, binding.accountAuthorizationOperationId())
            .set(INITIATING_ACCOUNT_ID, binding.initiatingAccountId())
            .set(CREATION_REQUEST_DIGEST, binding.creationRequestDigest())
            .set(ACCOUNT_AUTHORIZATION_DIGEST, binding.accountAuthorizationDigest())
            .set(AUTHORIZATION_READ_REQUEST_ID, dispatch.authorizationReadRequestId())
            .set(VALID_BEFORE_EPOCH_MILLIS, dispatch.validBeforeEpochMillis())
            .execute();
    if (inserted != 1) {
      throw new IllegalStateException("Authorized fresh tenant dispatch was not recorded");
    }
  }

  /** Independently reads only the exact committed Account authorization fence. */
  @Transactional(propagation = Propagation.NOT_SUPPORTED, readOnly = true)
  public Optional<AuthorizedDispatch> readCommitted(OriginalOutcomeBinding expected) {
    Objects.requireNonNull(expected, "expected");
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Authorized fresh tenant dispatch read requires committed owner state");
    }
    Record row =
        dsl.selectFrom(TABLE)
            .where(
                TARGET_NAMESPACE
                    .eq(expected.targetNamespace())
                    .and(CREATION_REQUEST_ID.eq(expected.creationRequestId())))
            .fetchOne();
    if (row == null) {
      return Optional.empty();
    }
    AuthorizedDispatch stored = toDispatch(row);
    if (!stored.binding().equals(expected)) {
      throw new IllegalStateException(
          "Committed authorized fresh tenant dispatch has a different original binding");
    }
    return Optional.of(stored);
  }

  private AuthorizedDispatch toDispatch(Record row) {
    return new AuthorizedDispatch(
        new OriginalOutcomeBinding(
            row.get(TARGET_NAMESPACE),
            row.get(CREATION_REQUEST_ID),
            row.get(AUTHORIZATION_OPERATION_ID),
            row.get(INITIATING_ACCOUNT_ID),
            row.get(CREATION_REQUEST_DIGEST),
            row.get(ACCOUNT_AUTHORIZATION_DIGEST)),
        row.get(AUTHORIZATION_READ_REQUEST_ID),
        row.get(VALID_BEFORE_EPOCH_MILLIS));
  }

  private void requireWritableOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Authorized fresh tenant dispatch mutation requires an active owner transaction");
    }
    if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Authorized fresh tenant dispatch mutation requires a writable owner transaction");
    }
  }

  public record AuthorizedDispatch(
      OriginalOutcomeBinding binding,
      UUID authorizationReadRequestId,
      long validBeforeEpochMillis) {
    public AuthorizedDispatch {
      Objects.requireNonNull(binding, "binding");
      Objects.requireNonNull(authorizationReadRequestId, "authorizationReadRequestId");
      if (new UUID(0L, 0L).equals(authorizationReadRequestId)) {
        throw new IllegalArgumentException("authorizationReadRequestId must not be nil");
      }
      if (validBeforeEpochMillis <= 0 || validBeforeEpochMillis > 253402300799999L) {
        throw new IllegalArgumentException("validBeforeEpochMillis must be a supported UTC time");
      }
    }
  }
}
