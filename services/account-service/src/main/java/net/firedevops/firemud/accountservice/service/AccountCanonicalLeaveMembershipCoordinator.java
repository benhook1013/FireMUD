package net.firedevops.firemud.accountservice.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountLeaveMembershipBinding;
import net.firedevops.firemud.accountservice.repository.AccountAuditOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountLeaveOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountLeaveOperationRepository.Receipt;
import net.firedevops.firemud.accountservice.repository.AccountLeaveOperationRepository.Terminal;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unregistered Account-local LEAVE owner. The caller supplies an authenticated binding and one
 * writable owner transaction; failures must escape it. No public route or current authority is
 * provided by this primitive, and historical receipt replay never reruns a transition.
 */
public final class AccountCanonicalLeaveMembershipCoordinator {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final AccountRepository accounts;
  private final AccountJoinOperationRepository accountLocks;
  private final AccountLeaveOperationRepository operations;
  private final AccountTenantMembershipRepository memberships;
  private final AccountAuthoritySourceEvidenceRepository sources;
  private final AccountMembershipAuthorityEventProducer producer;
  private final AccountAuditOutboxRepository audit;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Shared Account owner transaction collaborators")
  public AccountCanonicalLeaveMembershipCoordinator(
      AccountRepository accounts,
      AccountJoinOperationRepository accountLocks,
      AccountLeaveOperationRepository operations,
      AccountTenantMembershipRepository memberships,
      AccountAuthoritySourceEvidenceRepository sources,
      AccountMembershipAuthorityEventProducer producer,
      AccountAuditOutboxRepository audit) {
    this.accounts = Objects.requireNonNull(accounts);
    this.accountLocks = Objects.requireNonNull(accountLocks);
    this.operations = Objects.requireNonNull(operations);
    this.memberships = Objects.requireNonNull(memberships);
    this.sources = Objects.requireNonNull(sources);
    this.producer = Objects.requireNonNull(producer);
    this.audit = Objects.requireNonNull(audit);
  }

  public Receipt leave(AccountLeaveMembershipBinding binding) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("LEAVE requires a writable Account owner transaction");
    }
    Objects.requireNonNull(binding);
    var account = accounts.findByAccountUuid(binding.accountId()).orElseThrow();
    if (account.getId() == null
        || account.getId() <= 0
        || !binding.accountId().equals(account.getAccountUuid())
        || !account.getId().equals(account.getAccountUuidSourceNumericId())) {
      throw new IllegalStateException("Exact persisted LEAVE Account identity required");
    }
    accountLocks.lockAccount(account.getId());
    Receipt original = operations.begin(account.getId(), binding);
    if ("COMMITTED".equals(original.status())) return original;
    var member =
        memberships
            .findFreshMembershipForUpdate(binding.accountId(), binding.tenantId())
            .orElse(null);
    if (member == null || "INACTIVE".equals(member.getLifecycleState())) {
      long fence =
          sources
              .readCurrentIssuerAccountSources("firemud-account-service", binding.accountId())
              .issuanceFence()
              .value();
      if (member != null && member.isGameplayAdmissionAllowed())
        throw new IllegalStateException("Inactive membership still admits gameplay");
      Long version = member == null ? null : member.getMembershipVersion();
      Long generation = member == null ? null : member.getMembershipAuthorityGeneration();
      return operations.finish(
          binding,
          new Terminal(
              "ALREADY_LEFT",
              member == null ? null : member.getId(),
              version,
              generation,
              fence,
              version,
              generation,
              fence,
              null,
              null,
              null,
              null,
              null,
              null));
    }
    if (!"ACTIVE".equals(member.getLifecycleState()))
      throw new IllegalStateException("Membership lifecycle does not permit LEAVE");
    var transition = producer.publishCanonicalLeftMembershipChange(binding);
    var event = transition.event();
    operations.recordFenceCause(
        binding, transition.beforeAuthority(), transition.afterAuthority(), event);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("operationKind", "LEAVE");
    payload.put("requestId", binding.requestId());
    payload.put("requestDigest", binding.digest());
    payload.put("accountId", binding.accountId().toString());
    payload.put("tenantId", binding.tenantId().toString());
    payload.put("membershipEventId", event.eventId());
    payload.put("membershipEventDigest", event.eventDigest());
    payload.put("membershipVersion", Long.toString(transition.version()));
    payload.put("membershipAuthorityGeneration", Long.toString(transition.generation()));
    final String bytes;
    try {
      bytes =
          new String(
              Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(payload)),
              StandardCharsets.UTF_8);
    } catch (Exception failure) {
      throw new IllegalStateException("Cannot encode exact LEAVE audit", failure);
    }
    UUID auditId =
        UUID.nameUUIDFromBytes(
            ("account-leave-audit/v1:" + binding.requestId()).getBytes(StandardCharsets.UTF_8));
    var appended =
        audit.appendCanonicalTenant(
            auditId, binding.tenantId().toString(), "ACCOUNT_LEFT_TENANT_MEMBERSHIP", bytes);
    var retained = audit.findExactCanonicalTenantEnvelopeForUpdate(appended).orElseThrow();
    return operations.finish(
        binding,
        new Terminal(
            "LEFT",
            transition.membershipId(),
            transition.beforeVersion(),
            transition.beforeGeneration(),
            transition.beforeAuthority().issuanceFence().value(),
            transition.version(),
            transition.generation(),
            transition.afterAuthority().issuanceFence().value(),
            event.outboxStreamKey(),
            event.outboxSequence(),
            event.eventId(),
            event.eventDigest(),
            retained.auditEventId(),
            retained.payloadDigest()));
  }
}
