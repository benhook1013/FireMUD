package net.firedevops.firemud.accountservice.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.IssuanceFence;
import net.firedevops.firemud.accountservice.repository.ConnectTokenReceiptClassifier.Lookup;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultConnectionProvider;

/** Manually composed Account storage for the original Connect issuance fence. */
public final class AccountConnectIssuanceFenceCaptureRepository {
  private static final String CAPTURE_FIELDS =
      "issuance_fence_capture_schema, issuance_fence_capture_account_uuid, "
          + "issuance_fence_capture_value, issuance_fence_capture_source_version, "
          + "issuance_fence_capture_digest";
  private final DSLContext dsl;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "The owner supplies its internal transaction-bound DSLContext.")
  public AccountConnectIssuanceFenceCaptureRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl);
  }

  /**
   * Captures the supplied owner-observed fence only if it still matches the current Account
   * composite position and the exact operation is pending. The caller owns authority evaluation.
   */
  public AccountConnectIssuanceFenceEvidence capture(
      UUID operationId, Lookup identity, IssuanceFence expectedFence) {
    AccountConnectIssuanceFenceEvidence candidate =
        AccountConnectIssuanceFenceEvidence.capture(operationId, identity, expectedFence);
    return inOwnerTransaction(
        transaction -> {
          lockCurrentFence(transaction, expectedFence);
          Record operation = exactOperation(transaction, operationId, identity, true);
          if (!"PENDING".equals(operation.get("status", String.class))) {
            throw new IllegalStateException("Connect issuance fence requires a pending operation");
          }
          Optional<AccountConnectIssuanceFenceEvidence> retained =
              readCapture(operationId, identity, operation);
          if (retained.isPresent()) {
            if (!candidate.equals(retained.orElseThrow())) {
              throw new IllegalStateException(
                  "Connect issuance fence was captured with different evidence");
            }
            return retained.orElseThrow();
          }
          Record changed =
              transaction.fetchOne(
                  "UPDATE connect_token_issuance_operation "
                      + "SET issuance_fence_capture_schema=?, issuance_fence_capture_account_uuid=?, "
                      + "issuance_fence_capture_value=?, issuance_fence_capture_source_version=?, "
                      + "issuance_fence_capture_digest=? WHERE operation_id=? AND status='PENDING' "
                      + "AND issuance_fence_capture_schema IS NULL RETURNING "
                      + CAPTURE_FIELDS,
                  AccountConnectIssuanceFenceEvidence.SCHEMA_NAME,
                  expectedFence.accountId(),
                  expectedFence.value(),
                  expectedFence.sourceVersion(),
                  HexFormat.of().parseHex(candidate.digest()),
                  operationId);
          if (changed == null
              || !readCapture(operationId, identity, changed).equals(Optional.of(candidate))) {
            throw new IllegalStateException("Connect issuance fence capture lacks exact readback");
          }
          return candidate;
        });
  }

  /** Reads retained historical capture evidence without asserting current Account authority. */
  public Optional<AccountConnectIssuanceFenceEvidence> read(UUID operationId, Lookup identity) {
    Objects.requireNonNull(operationId);
    Objects.requireNonNull(identity);
    return inOwnerTransaction(
        transaction ->
            readCapture(
                operationId, identity, exactOperation(transaction, operationId, identity, false)));
  }

  private static void lockCurrentFence(DSLContext transaction, IssuanceFence expected) {
    UUID account = expected.accountId();
    // Account authority writers acquire the Account row before the fence and operation rows.
    Record owner =
        transaction.fetchOne(
            "SELECT account_uuid FROM accounts WHERE account_uuid=? FOR SHARE", account);
    if (owner == null) {
      throw new IllegalStateException("Connect issuance fence requires its exact Account");
    }
    Record current =
        transaction.fetchOne(
            "SELECT issuance_fence, source_version FROM account_authority_issuance_fences "
                + "WHERE account_uuid=? FOR SHARE",
            account);
    if (current == null) {
      throw new IllegalStateException("Current Account issuance fence is unavailable");
    }
    IssuanceFence locked =
        new IssuanceFence(account, current.get(0, Long.class), current.get(1, Long.class));
    // Use a fresh statement after any lock wait so provenance is evaluated at the locked position.
    if (!expected.equals(locked)
        || !Boolean.TRUE.equals(
            transaction
                .fetchSingle(
                    "SELECT account_has_current_fence_provenance(?, ?, ?)",
                    account,
                    locked.value(),
                    locked.sourceVersion())
                .get(0, Boolean.class))) {
      throw new IllegalStateException("Current Account issuance fence differs or lacks provenance");
    }
  }

  private static Record exactOperation(
      DSLContext transaction, UUID operationId, Lookup identity, boolean lock) {
    Record operation =
        transaction.fetchOne(
            "SELECT status, "
                + CAPTURE_FIELDS
                + " FROM connect_token_issuance_operation WHERE operation_id=? AND account_uuid=? "
                + "AND tenant_uuid=? AND connect_scope_id=? AND request_id=? "
                + "AND request_digest_version=? AND request_digest=?"
                + (lock ? " FOR UPDATE" : ""),
            operationId,
            identity.accountId(),
            identity.tenantId(),
            identity.connectScopeId(),
            identity.requestId(),
            identity.digestVersion(),
            identity.requestDigest());
    if (operation == null) {
      throw new IllegalStateException(
          "Original Connect operation is missing or its identity differs");
    }
    return operation;
  }

  private static Optional<AccountConnectIssuanceFenceEvidence> readCapture(
      UUID operationId, Lookup identity, Record row) {
    String schema = row.get("issuance_fence_capture_schema", String.class);
    UUID accountId = row.get("issuance_fence_capture_account_uuid", UUID.class);
    Long value = row.get("issuance_fence_capture_value", Long.class);
    Long sourceVersion = row.get("issuance_fence_capture_source_version", Long.class);
    byte[] digest = row.get("issuance_fence_capture_digest", byte[].class);
    if (schema == null
        && accountId == null
        && value == null
        && sourceVersion == null
        && digest == null) {
      return Optional.empty();
    }
    if (!AccountConnectIssuanceFenceEvidence.SCHEMA_NAME.equals(schema)
        || !identity.accountId().equals(accountId)
        || value == null
        || sourceVersion == null
        || digest == null) {
      throw new IllegalStateException("Stored Connect issuance fence capture is malformed");
    }
    return Optional.of(
        new AccountConnectIssuanceFenceEvidence(
            operationId,
            identity,
            new IssuanceFence(accountId, value, sourceVersion),
            HexFormat.of().formatHex(digest)));
  }

  private <T> T inOwnerTransaction(Function<DSLContext, T> action) {
    return dsl.connectionResult(
        connection -> {
          if (connection.getAutoCommit()) {
            throw new IllegalStateException(
                "Connect issuance fence requires an existing Account transaction");
          }
          return action.apply(
              DSL.using(dsl.configuration().derive(new DefaultConnectionProvider(connection))));
        });
  }
}
