package net.firedevops.firemud.accountservice.repository;

import static net.firedevops.firemud.accountservice.jooq.tables.AccountAuditOutbox.ACCOUNT_AUDIT_OUTBOX;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toInstant;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toLocalDateTime;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.accountservice.dto.AccountAuditDigest;
import net.firedevops.firemud.accountservice.dto.AccountAuditEnvelope;
import net.firedevops.firemud.accountservice.dto.AccountAuditTenantIdentity;
import net.firedevops.firemud.accountservice.dto.MembershipTransitionReceiptDigest;
import net.firedevops.firemud.accountservice.jooq.tables.records.AccountAuditOutboxRecord;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Owner-local audit identity, immutable envelope, and durable delivery state. */
@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class AccountAuditOutboxRepository {
  private static final Pattern POSITIVE_DECIMAL = Pattern.compile("[1-9][0-9]*");
  private static final JsonMapper CANONICAL_JOIN_AUDIT_JSON = JsonMapper.builder().build();
  private final DSLContext dsl;

  public AccountAuditOutboxRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  /** Source-only retained identity writer; every row it writes uses explicit tenant version 1. */
  public AccountAuditEnvelope append(
      UUID auditEventId, String scope, Long tenantId, String eventType, String payload) {
    AccountAuditTenantIdentity identity = retainedIdentity(scope, tenantId);
    return append(auditEventId, scope, identity, eventType, payload, false);
  }

  /**
   * Appends version-2 canonical tenant identity in the active Account owner write transaction. This
   * storage boundary does not establish source authenticity, membership, or admission.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public AccountAuditEnvelope appendCanonicalTenant(
      UUID auditEventId, String canonicalTenantUuid, String eventType, String payload) {
    requireReadWriteOwnerTransaction();
    return append(
        auditEventId,
        "tenant",
        AccountAuditTenantIdentity.canonicalTenantV2(canonicalTenantUuid),
        eventType,
        payload,
        true);
  }

  /**
   * Locks and returns only the exact original canonical envelope in the current owner transaction.
   * A missing row returns empty; changed identity or immutable bytes fail closed.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<AccountAuditEnvelope> findExactCanonicalTenantEnvelopeForUpdate(
      AccountAuditEnvelope expected) {
    requireReadWriteOwnerTransaction();
    if (expected == null
        || expected.tenantIdentityVersion() != AccountAuditTenantIdentity.VERSION_2
        || expected.payload() == null) {
      throw new IllegalArgumentException("Exact canonical tenant audit envelope is required");
    }
    var found =
        dsl.selectFrom(ACCOUNT_AUDIT_OUTBOX)
            .where(ACCOUNT_AUDIT_OUTBOX.AUDIT_EVENT_ID.eq(expected.auditEventId()))
            .forUpdate()
            .fetchOptional(this::toEnvelope);
    found.ifPresent(
        actual -> {
          if (!actual.equals(expected)) {
            throw new IllegalStateException(
                "Canonical Account audit identity conflicts with its immutable original envelope");
          }
        });
    return found;
  }

  /**
   * Locks the existing JOIN audit identity and returns it only for its exact canonical UUID scope.
   * A present event with another scope, tenant representation, producer, or event type is
   * contradictory evidence rather than an absent canonical audit.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<AccountAuditEnvelope> findCanonicalJoinEnvelopeForUpdate(
      UUID auditEventId, UUID canonicalTenantUuid) {
    requireReadWriteOwnerTransaction();
    if (auditEventId == null
        || canonicalTenantUuid == null
        || new UUID(0L, 0L).equals(canonicalTenantUuid)) {
      throw new IllegalArgumentException("Canonical JOIN audit identity and tenant are required");
    }
    Optional<AccountAuditEnvelope> found =
        dsl.selectFrom(ACCOUNT_AUDIT_OUTBOX)
            .where(ACCOUNT_AUDIT_OUTBOX.AUDIT_EVENT_ID.eq(auditEventId))
            .forUpdate()
            .fetchOptional(this::toEnvelope);
    found.ifPresent(
        actual -> {
          if (!"tenant".equals(actual.scope())
              || actual.tenantIdentityVersion() != AccountAuditTenantIdentity.VERSION_2
              || actual.tenantId() != null
              || !canonicalTenantUuid.equals(actual.tenantUuid())
              || !"account-service".equals(actual.producerService())
              || !"ACCOUNT_JOINED_PUBLIC_PRODUCTION".equals(actual.eventType())) {
            throw new IllegalStateException(
                "Canonical JOIN audit identity differs from its immutable UUID envelope");
          }
        });
    return found;
  }

  /**
   * Serializes the existing six-field JOIN audit payload for canonical UUID identity. The field
   * order and names remain those of the retained JOIN payload; only the UUID identities and the
   * canonical one-tenant membership-version map are represented in their owning forms.
   */
  public static String canonicalJoinPayload(
      UUID accountUuid,
      UUID tenantUuid,
      String worldSlug,
      String realmSlug,
      Map<String, String> membershipVersion,
      String requestId) {
    requireCanonicalUuid(accountUuid, "Account UUID");
    requireCanonicalUuid(tenantUuid, "tenant UUID");
    if (worldSlug == null || worldSlug.isEmpty() || realmSlug == null || realmSlug.isEmpty()) {
      throw new IllegalArgumentException("Canonical JOIN audit target slugs are required");
    }
    String canonicalRequestId = MembershipTransitionReceiptDigest.requireRequestIdV2(requestId);
    if (membershipVersion == null
        || membershipVersion.size() != 1
        || !membershipVersion.containsKey(tenantUuid.toString())) {
      throw new IllegalArgumentException(
          "Canonical JOIN audit membership version must contain exactly its tenant UUID");
    }
    String version = membershipVersion.get(tenantUuid.toString());
    if (version == null || !POSITIVE_DECIMAL.matcher(version).matches()) {
      throw new IllegalArgumentException(
          "Canonical JOIN audit membership version must be a positive canonical decimal");
    }
    try {
      if (Long.parseLong(version) <= 0L) {
        throw new IllegalArgumentException(
            "Canonical JOIN audit membership version must be a positive BIGINT");
      }
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException(
          "Canonical JOIN audit membership version must be a positive BIGINT", exception);
    }
    try {
      return CANONICAL_JOIN_AUDIT_JSON.writeValueAsString(
          new CanonicalJoinAuditPayload(
              accountUuid.toString(),
              tenantUuid.toString(),
              worldSlug,
              realmSlug,
              Map.of(tenantUuid.toString(), version),
              canonicalRequestId));
    } catch (RuntimeException exception) {
      throw new IllegalStateException(
          "Canonical JOIN audit payload serialization failed", exception);
    }
  }

  private AccountAuditEnvelope append(
      UUID auditEventId,
      String scope,
      AccountAuditTenantIdentity identity,
      String eventType,
      String payload,
      boolean canonicalWrite) {
    identity.requireScope(scope);
    Instant occurredAt = Instant.now();
    String digest = AccountAuditDigest.ofPayload(payload);
    AccountAuditOutboxRecord row = dsl.newRecord(ACCOUNT_AUDIT_OUTBOX);
    row.setAuditEventId(auditEventId);
    row.setScope(scope);
    row.setTenantId(identity.tenantId());
    row.setTenantIdentityVersion(identity.version());
    row.setTenantUuid(identity.tenantUuid());
    row.setProducerService("account-service");
    row.setEventType(eventType);
    row.setOccurredAt(toLocalDateTime(occurredAt));
    row.setNextAttemptAt(toLocalDateTime(occurredAt));
    row.setSchemaVersion(1);
    row.setPayloadDigestVersion(1);
    row.setPayloadDigest(digest);
    row.setPayload(payload);
    row.setDeliveryStatus("PENDING");
    row.store();
    row.refresh();
    AccountAuditEnvelope stored = toEnvelope(row);
    if (!stored.auditEventId().equals(auditEventId)
        || !stored.scope().equals(scope)
        || !stored.tenantIdentity().equals(identity)
        || !stored.producerService().equals("account-service")
        || !stored.eventType().equals(eventType)
        || stored.schemaVersion() != 1
        || stored.payloadDigestVersion() != 1
        || !stored.payloadDigest().equals(digest)
        || !payload.equals(stored.payload())) {
      throw new IllegalStateException("Account audit outbox did not preserve its exact envelope");
    }
    if (canonicalWrite && stored.tenantIdentityVersion() != AccountAuditTenantIdentity.VERSION_2) {
      throw new IllegalStateException(
          "Account audit outbox did not preserve canonical identity version");
    }
    return stored;
  }

  public List<AccountAuditEnvelope> pending(int limit, Instant dueBeforeOrAt) {
    return dsl.selectFrom(ACCOUNT_AUDIT_OUTBOX)
        .where(
            ACCOUNT_AUDIT_OUTBOX
                .DELIVERY_STATUS
                .eq("PENDING")
                .and(ACCOUNT_AUDIT_OUTBOX.NEXT_ATTEMPT_AT.le(toLocalDateTime(dueBeforeOrAt))))
        .orderBy(
            ACCOUNT_AUDIT_OUTBOX.NEXT_ATTEMPT_AT.asc(),
            ACCOUNT_AUDIT_OUTBOX.CREATED_AT.asc(),
            ACCOUNT_AUDIT_OUTBOX.AUDIT_EVENT_ID.asc())
        .limit(limit)
        .fetch(this::toEnvelope);
  }

  /** Locks one exact Account JOIN transition envelope for transaction-local reconciliation. */
  public Optional<JoinAuditEvidence> findJoinEnvelopeForUpdate(UUID auditEventId, long tenantId) {
    if (auditEventId == null || tenantId <= 0) {
      throw new IllegalArgumentException("JOIN audit identity and tenant are required");
    }
    return dsl.selectFrom(ACCOUNT_AUDIT_OUTBOX)
        .where(
            ACCOUNT_AUDIT_OUTBOX
                .AUDIT_EVENT_ID
                .eq(auditEventId)
                .and(ACCOUNT_AUDIT_OUTBOX.SCOPE.eq("tenant"))
                .and(ACCOUNT_AUDIT_OUTBOX.TENANT_ID.eq(tenantId))
                .and(ACCOUNT_AUDIT_OUTBOX.PRODUCER_SERVICE.eq("account-service"))
                .and(ACCOUNT_AUDIT_OUTBOX.EVENT_TYPE.eq("ACCOUNT_JOINED_PUBLIC_PRODUCTION")))
        .forUpdate()
        .fetchOptional(
            row ->
                new JoinAuditEvidence(
                    toEnvelope(row),
                    row.getDeliveryStatus(),
                    row.getReceiverAuditProjectionVersion(),
                    row.getReceiverReceiptId(),
                    row.getReceiverLogEventId()));
  }

  /** Locks one exact Account LEFT envelope for same-owner-transaction retry proof. */
  @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
  public Optional<AccountAuditEnvelope> findMembershipLeftEnvelopeForUpdate(
      UUID auditEventId, long tenantId) {
    if (auditEventId == null || tenantId <= 0) {
      throw new IllegalArgumentException("LEFT audit identity and tenant are required");
    }
    return dsl.selectFrom(ACCOUNT_AUDIT_OUTBOX)
        .where(
            ACCOUNT_AUDIT_OUTBOX
                .AUDIT_EVENT_ID
                .eq(auditEventId)
                .and(ACCOUNT_AUDIT_OUTBOX.SCOPE.eq("tenant"))
                .and(ACCOUNT_AUDIT_OUTBOX.TENANT_ID.eq(tenantId))
                .and(ACCOUNT_AUDIT_OUTBOX.PRODUCER_SERVICE.eq("account-service"))
                .and(ACCOUNT_AUDIT_OUTBOX.EVENT_TYPE.eq("ACCOUNT_MEMBERSHIP_LEFT")))
        .forUpdate()
        .fetchOptional(this::toEnvelope);
  }

  public void markDelivered(
      UUID auditEventId, String receiptId, String logEventId, boolean minimized) {
    if (auditEventId == null
        || receiptId == null
        || receiptId.isBlank()
        || logEventId == null
        || logEventId.isBlank()) {
      throw new IllegalArgumentException("Verified audit delivery requires nonblank identity");
    }
    var update =
        dsl.update(ACCOUNT_AUDIT_OUTBOX)
            .set(ACCOUNT_AUDIT_OUTBOX.RECEIVER_RECEIPT_ID, receiptId)
            .set(ACCOUNT_AUDIT_OUTBOX.RECEIVER_LOG_EVENT_ID, logEventId)
            .set(ACCOUNT_AUDIT_OUTBOX.RECEIVER_AUDIT_PROJECTION_VERSION, 1)
            .set(ACCOUNT_AUDIT_OUTBOX.DELIVERY_STATUS, minimized ? "MINIMIZED" : "COMMITTED")
            .set(ACCOUNT_AUDIT_OUTBOX.LAST_ATTEMPT_AT, toLocalDateTime(Instant.now()))
            .set(ACCOUNT_AUDIT_OUTBOX.NEXT_ATTEMPT_AT, (LocalDateTime) null);
    if (minimized) {
      update.set(ACCOUNT_AUDIT_OUTBOX.PAYLOAD, (String) null);
    }
    int changed =
        update
            .where(
                ACCOUNT_AUDIT_OUTBOX
                    .AUDIT_EVENT_ID
                    .eq(auditEventId)
                    .and(ACCOUNT_AUDIT_OUTBOX.DELIVERY_STATUS.eq("PENDING")))
            .execute();
    if (changed == 0) {
      AccountAuditOutboxRecord durableRow =
          dsl.selectFrom(ACCOUNT_AUDIT_OUTBOX)
              .where(ACCOUNT_AUDIT_OUTBOX.AUDIT_EVENT_ID.eq(auditEventId))
              .fetchOne();
      String expectedStatus = minimized ? "MINIMIZED" : "COMMITTED";
      if (durableRow != null
          && expectedStatus.equals(durableRow.getDeliveryStatus())
          && Integer.valueOf(1).equals(durableRow.getReceiverAuditProjectionVersion())
          && Objects.equals(receiptId, durableRow.getReceiverReceiptId())
          && Objects.equals(logEventId, durableRow.getReceiverLogEventId())) {
        return;
      }
    }
    if (changed != 1) {
      throw new IllegalStateException("Audit delivery state changed concurrently");
    }
  }

  public void recordAttempt(UUID auditEventId) {
    LocalDateTime attemptedAt = toLocalDateTime(Instant.now());
    dsl.update(ACCOUNT_AUDIT_OUTBOX)
        .set(ACCOUNT_AUDIT_OUTBOX.ATTEMPT_COUNT, ACCOUNT_AUDIT_OUTBOX.ATTEMPT_COUNT.plus(1))
        .set(ACCOUNT_AUDIT_OUTBOX.LAST_ATTEMPT_AT, attemptedAt)
        .set(
            ACCOUNT_AUDIT_OUTBOX.NEXT_ATTEMPT_AT,
            DSL.field(
                "CAST({0} AS TIMESTAMP) + make_interval(secs => "
                    + "LEAST(300, (5 * POWER(2, LEAST({1}, 6)))::INTEGER))",
                LocalDateTime.class, DSL.val(attemptedAt), ACCOUNT_AUDIT_OUTBOX.ATTEMPT_COUNT))
        .where(
            ACCOUNT_AUDIT_OUTBOX
                .AUDIT_EVENT_ID
                .eq(auditEventId)
                .and(ACCOUNT_AUDIT_OUTBOX.DELIVERY_STATUS.eq("PENDING")))
        .execute();
  }

  private AccountAuditEnvelope toEnvelope(AccountAuditOutboxRecord row) {
    return new AccountAuditEnvelope(
        row.getAuditEventId(),
        row.getScope(),
        new AccountAuditTenantIdentity(
            row.getTenantIdentityVersion(), row.getTenantId(), row.getTenantUuid()),
        row.getProducerService(),
        row.getEventType(),
        toInstant(row.getOccurredAt()),
        row.getSchemaVersion(),
        row.getPayloadDigestVersion(),
        row.getPayloadDigest(),
        row.getPayload());
  }

  private static void requireReadWriteOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Canonical Account audit identity requires an active read-write owner transaction");
    }
  }

  private static void requireCanonicalUuid(UUID value, String field) {
    if (value == null || new UUID(0L, 0L).equals(value)) {
      throw new IllegalArgumentException(field + " must be a non-nil canonical UUID");
    }
  }

  private record CanonicalJoinAuditPayload(
      String accountId,
      String tenantId,
      String worldSlug,
      String realmSlug,
      Map<String, String> membershipVersion,
      String requestId) {}

  private static AccountAuditTenantIdentity retainedIdentity(String scope, Long tenantId) {
    if ("platform".equals(scope) && tenantId == null) {
      return AccountAuditTenantIdentity.platformV1();
    }
    if ("tenant".equals(scope) && tenantId != null && tenantId > 0) {
      return AccountAuditTenantIdentity.retainedTenantV1(tenantId);
    }
    throw new IllegalArgumentException("Audit scope and retained tenant identity must match");
  }

  public record JoinAuditEvidence(
      AccountAuditEnvelope envelope,
      String deliveryStatus,
      Integer auditProjectionVersion,
      String receiptId,
      String projectionId) {
    public JoinAuditEvidence {
      Objects.requireNonNull(envelope, "envelope");
    }
  }
}
