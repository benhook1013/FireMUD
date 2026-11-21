package net.firedevops.firemud.accountservice.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;

/**
 * Unregistered storage-only recovery classifier. It cannot claim, mint, decrypt, replay, renew or
 * delete anything. Supplied identity/digest provenance remains the Account owner's responsibility.
 */
public final class ConnectTokenReceiptClassifier {
  private final DSLContext dsl;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "The owner supplies its internal transaction-bound DSLContext; this is not registered.")
  public ConnectTokenReceiptClassifier(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl);
  }

  public enum State {
    MISSING_UNCERTAIN,
    IDEMPOTENCY_CONFLICT,
    PENDING,
    ABORTED,
    RECONCILIATION_REQUIRED,
    SUCCESS_REVALIDATION_REQUIRED,
    FAILURE_ENVELOPE_RETAINED,
    RESPONSE_RECOVERY_EXPIRED,
    INCONSISTENT_EVIDENCE
  }

  /** Exact caller-bound lookup input, not proof of authentication or a digest producer. */
  public record Lookup(
      UUID accountId,
      UUID tenantId,
      String connectScopeId,
      String requestId,
      int digestVersion,
      String requestDigest) {
    public Lookup {
      requireUuid(accountId);
      requireUuid(tenantId);
      requireText(connectScopeId, 2048);
      requireText(requestId, 128);
      if (digestVersion != 1
          || requestDigest == null
          || !requestDigest.matches("sha256:[0-9a-f]{64}")) {
        throw new IllegalArgumentException("Exact supported retained digest is required");
      }
    }
  }

  /**
   * Reads and locks only an existing receipt within the caller's transaction. A missing receipt is
   * uncertain, never permission to mint. Success remains unusable until the owner independently
   * revalidates current authority/postconditions and authenticates the encrypted envelope.
   */
  public State classify(Lookup lookup) {
    Objects.requireNonNull(lookup);
    return dsl.connectionResult(
        connection -> {
          if (connection.getAutoCommit()) {
            throw new IllegalStateException(
                "Receipt classification requires an existing Account transaction");
          }
          return classifyLocked(
              org.jooq.impl.DSL.using(
                  dsl.configuration()
                      .derive(new org.jooq.impl.DefaultConnectionProvider(connection))),
              lookup);
        });
  }

  private State classifyLocked(DSLContext transaction, Lookup lookup) {
    Record row =
        transaction.fetchOne(
            "SELECT op.request_digest_version,op.request_digest,op.status,op.reconciliation_blocked,"
                + "op.reconciliation_evidence,op.operation_id "
                + "FROM connect_token_issuance_operation op "
                + "WHERE op.account_uuid=? AND op.tenant_uuid=? AND op.connect_scope_id=? AND op.request_id=? "
                + "FOR UPDATE OF op",
            lookup.accountId(),
            lookup.tenantId(),
            lookup.connectScopeId(),
            lookup.requestId());
    if (row == null) return State.MISSING_UNCERTAIN;
    Integer version = row.get("request_digest_version", Integer.class);
    String digest = row.get("request_digest", String.class);
    if (!Objects.equals(version, lookup.digestVersion())
        || digest == null
        || !MessageDigest.isEqual(
            digest.getBytes(StandardCharsets.UTF_8),
            lookup.requestDigest().getBytes(StandardCharsets.UTF_8))) {
      return State.IDEMPOTENCY_CONFLICT;
    }
    String status = row.get("status", String.class);
    if ("PENDING".equals(status)) return State.PENDING;
    if ("ABORTED".equals(status)) return State.ABORTED;
    if (!"COMMITTED".equals(status) && !"FAILED".equals(status)) return State.INCONSISTENT_EVIDENCE;
    if (!Boolean.FALSE.equals(row.get("reconciliation_blocked", Boolean.class))
        || row.get("reconciliation_evidence", byte[].class) == null) {
      return State.RECONCILIATION_REQUIRED;
    }
    // Separate read after taking the owner lock: READ COMMITTED can update the locked row
    // while a joined envelope would still come from the pre-wait statement snapshot.
    Record envelope =
        transaction.fetchOne(
            "SELECT EXISTS (SELECT 1 FROM connect_token_response_envelope e WHERE e.operation_id=op.operation_id) AS present,"
                + "op.response_recovery_expires_at > clock_timestamp() AS within_horizon "
                + "FROM connect_token_issuance_operation op WHERE operation_id=?",
            row.get("operation_id", UUID.class));
    if (envelope == null) return State.INCONSISTENT_EVIDENCE;
    boolean present = Boolean.TRUE.equals(envelope.get("present", Boolean.class));
    boolean withinHorizon = Boolean.TRUE.equals(envelope.get("within_horizon", Boolean.class));
    if (!present)
      return withinHorizon ? State.INCONSISTENT_EVIDENCE : State.RESPONSE_RECOVERY_EXPIRED;
    return "COMMITTED".equals(status)
        ? State.SUCCESS_REVALIDATION_REQUIRED
        : State.FAILURE_ENVELOPE_RETAINED;
  }

  private static void requireUuid(UUID value) {
    if (value == null || value.equals(new UUID(0, 0)))
      throw new IllegalArgumentException("Canonical identity is required");
  }

  private static void requireText(String value, int maxBytes) {
    if (value == null || value.isBlank() || value.indexOf('\0') >= 0)
      throw new IllegalArgumentException("Exact request identity is required");
    try {
      int length = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(value)).remaining();
      if (length > maxBytes)
        throw new IllegalArgumentException("Request identity exceeds storage bound");
    } catch (CharacterCodingException exception) {
      throw new IllegalArgumentException("Request identity must be valid UTF-8", exception);
    }
  }
}
