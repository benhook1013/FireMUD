package net.firedevops.firemud.accountservice.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountLeaveMembershipBinding;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Internal durable LEAVE operation owner. Receipts are retry evidence, never current authority. */
public final class AccountLeaveOperationRepository {
  private final DSLContext dsl;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Shared Account transaction collaborator")
  public AccountLeaveOperationRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl);
  }

  public Receipt begin(long privateAccountId, AccountLeaveMembershipBinding binding) {
    requireTransaction();
    Objects.requireNonNull(binding);
    dsl.execute(
        "INSERT INTO account_leave_operations "
            + "(request_id, account_id, account_uuid, tenant_uuid, caller_binding, request_digest, status) "
            + "VALUES (?, ?, ?, ?, ?, ?, 'PENDING') ON CONFLICT (request_id) DO NOTHING",
        binding.requestId(),
        privateAccountId,
        binding.accountId(),
        binding.tenantId(),
        binding.callerBinding(),
        binding.digest());
    Record row =
        dsl.fetchOne(
            "SELECT * FROM account_leave_operations WHERE request_id = ? FOR UPDATE",
            binding.requestId());
    if (row == null
        || !Objects.equals(row.get("account_id", Long.class), privateAccountId)
        || !binding.accountId().equals(row.get("account_uuid", UUID.class))
        || !binding.tenantId().equals(row.get("tenant_uuid", UUID.class))
        || !binding.callerBinding().equals(row.get("caller_binding", String.class))
        || !binding.digest().equals(row.get("request_digest", String.class))) {
      throw new IllegalStateException("LEAVE idempotency conflict: original binding differs");
    }
    return decode(row);
  }

  public Receipt finish(AccountLeaveMembershipBinding binding, Terminal terminal) {
    requireTransaction();
    Objects.requireNonNull(terminal);
    int changed =
        dsl.execute(
            "UPDATE account_leave_operations SET status = 'COMMITTED', outcome = ?, membership_id = ?, "
                + "before_membership_version = ?, before_membership_generation = ?, before_fence = ?, "
                + "membership_version = ?, membership_authority_generation = ?, issuance_fence = ?, "
                + "membership_event_stream_key = ?, event_sequence = ?, event_id = ?, event_digest = ?, "
                + "audit_event_id = ?, audit_payload_digest = ? "
                + "WHERE request_id = ? AND account_uuid = ? AND tenant_uuid = ? "
                + "AND caller_binding = ? AND request_digest = ? AND status = 'PENDING'",
            terminal.outcome(),
            terminal.membershipId(),
            terminal.beforeVersion(),
            terminal.beforeGeneration(),
            terminal.beforeFence(),
            terminal.version(),
            terminal.generation(),
            terminal.fence(),
            terminal.streamKey(),
            terminal.eventSequence(),
            terminal.eventId(),
            terminal.eventDigest(),
            terminal.auditEventId(),
            terminal.auditDigest(),
            binding.requestId(),
            binding.accountId(),
            binding.tenantId(),
            binding.callerBinding(),
            binding.digest());
    if (changed != 1) throw new IllegalStateException("LEAVE terminal compare-and-commit failed");
    Record row =
        dsl.fetchOne(
            "SELECT * FROM account_leave_operations WHERE request_id = ?", binding.requestId());
    Receipt result = decode(Objects.requireNonNull(row));
    if (!terminal.equals(result.terminal()))
      throw new IllegalStateException("LEAVE terminal readback differs");
    return result;
  }

  /** Retains the membership event as the sole cause of its exact composite fence advance. */
  public void recordFenceCause(
      AccountLeaveMembershipBinding binding,
      AccountAuthorityGenerationRepository.ScopeState before,
      AccountAuthorityGenerationRepository.ScopeState after,
      AccountAuthorityOutboxRepository.Event event) {
    requireTransaction();
    var scope =
        AccountAuthorityGenerationRepository.AuthorityScope.membership(
            binding.accountId(), binding.tenantId());
    if (!scope.equals(before.scope())
        || !scope.equals(after.scope())
        || after.generation() != Math.incrementExact(before.generation())
        || after.sourceVersion() != Math.incrementExact(before.sourceVersion())
        || after.issuanceFence().value() != Math.incrementExact(before.issuanceFence().value())
        || !event.requestId().equals("leave:" + binding.requestId())) {
      throw new IllegalArgumentException("Exact LEAVE fence cause required");
    }
    dsl.execute(
        "INSERT INTO account_issuance_fence_provenance "
            + "(account_uuid, issuance_fence, source_version, prior_fence, cause_kind, tenant_uuid, "
            + "source_stream_key, event_sequence, event_id, event_digest, prior_generation, generation, "
            + "prior_authority_source_version, authority_source_version) "
            + "VALUES (?, ?, ?, ?, 'MEMBERSHIP', ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        binding.accountId(),
        after.issuanceFence().value(),
        after.issuanceFence().sourceVersion(),
        before.issuanceFence().value(),
        binding.tenantId(),
        event.outboxStreamKey(),
        event.outboxSequence(),
        event.eventId(),
        event.eventDigest(),
        before.generation(),
        after.generation(),
        before.sourceVersion(),
        after.sourceVersion());
  }

  private static Receipt decode(Record row) {
    AccountLeaveMembershipBinding binding =
        new AccountLeaveMembershipBinding(
            row.get("request_id", String.class), row.get("account_uuid", UUID.class),
            row.get("tenant_uuid", UUID.class), row.get("caller_binding", String.class));
    if (!binding.digest().equals(row.get("request_digest", String.class))) {
      throw new IllegalStateException("Stored LEAVE digest differs from exact binding");
    }
    String status = row.get("status", String.class);
    Terminal terminal =
        "COMMITTED".equals(status)
            ? new Terminal(
                row.get("outcome", String.class), row.get("membership_id", Long.class),
                row.get("before_membership_version", Long.class),
                    row.get("before_membership_generation", Long.class),
                row.get("before_fence", Long.class), row.get("membership_version", Long.class),
                row.get("membership_authority_generation", Long.class),
                    row.get("issuance_fence", Long.class),
                row.get("membership_event_stream_key", String.class),
                    row.get("event_sequence", Long.class),
                row.get("event_id", String.class), row.get("event_digest", String.class),
                row.get("audit_event_id", UUID.class),
                    row.get("audit_payload_digest", String.class))
            : null;
    if (!"PENDING".equals(status) && !"COMMITTED".equals(status))
      throw new IllegalStateException("Invalid LEAVE status");
    return new Receipt(binding, status, terminal);
  }

  private static void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("LEAVE requires a writable Account transaction");
    }
  }

  public record Receipt(AccountLeaveMembershipBinding binding, String status, Terminal terminal) {}

  public record Terminal(
      String outcome,
      Long membershipId,
      Long beforeVersion,
      Long beforeGeneration,
      Long beforeFence,
      Long version,
      Long generation,
      Long fence,
      String streamKey,
      Long eventSequence,
      String eventId,
      String eventDigest,
      UUID auditEventId,
      String auditDigest) {
    public Terminal {
      if (!("LEFT".equals(outcome) || "ALREADY_LEFT".equals(outcome))
          || beforeFence == null
          || beforeFence <= 0
          || fence == null
          || fence <= 0) {
        throw new IllegalArgumentException("Complete LEAVE outcome required");
      }
      if ("LEFT".equals(outcome)) {
        if (membershipId == null
            || membershipId <= 0
            || beforeVersion == null
            || beforeGeneration == null
            || version == null
            || generation == null
            || version != Math.addExact(beforeVersion, 1)
            || generation != Math.addExact(beforeGeneration, 1)
            || fence != Math.addExact(beforeFence, 1)
            || streamKey == null
            || eventSequence == null
            || eventSequence <= 0
            || eventId == null
            || eventDigest == null
            || auditEventId == null
            || auditDigest == null) {
          throw new IllegalArgumentException("Exact LEAVE transition evidence required");
        }
      } else if (!Objects.equals(beforeVersion, version)
          || !Objects.equals(beforeGeneration, generation)
          || !beforeFence.equals(fence)
          || streamKey != null
          || eventSequence != null
          || eventId != null
          || eventDigest != null
          || auditEventId != null
          || auditDigest != null) {
        throw new IllegalArgumentException("Already-left outcome cannot carry a new transition");
      }
    }
  }
}
