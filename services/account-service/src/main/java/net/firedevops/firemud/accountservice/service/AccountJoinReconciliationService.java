package net.firedevops.firemud.accountservice.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.accountservice.dto.AccountAuditDigest;
import net.firedevops.firemud.accountservice.dto.AccountAuditEnvelope;
import net.firedevops.firemud.accountservice.repository.AccountAuditOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountConnectScopeRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository.JoinOperation;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository.JoinMembershipProof;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Readback-only recovery for Account JOIN operations left PENDING after an uncertain commit. */
@Service
@SuppressFBWarnings(
    value = {"EI_EXPOSE_REP2", "CT_CONSTRUCTOR_THROW"},
    justification =
        "Injected Spring collaborators are internal; invalid reconciliation settings must fail startup.")
public class AccountJoinReconciliationService {
  private static final Logger logger =
      LoggerFactory.getLogger(AccountJoinReconciliationService.class);
  private static final int MAX_BATCH_SIZE = 100;
  private static final Pattern SHA256_PATTERN = Pattern.compile("sha256:[0-9a-f]{64}");
  private static final Set<String> JOIN_AUDIT_FIELDS =
      Set.of("accountId", "tenantId", "worldSlug", "realmSlug", "membershipVersion", "requestId");
  private static final JsonMapper AUDIT_JSON =
      JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

  private final AccountJoinOperationRepository joinOperationRepository;
  private final AccountConnectScopeRepository connectScopeRepository;
  private final AccountTenantMembershipRepository membershipRepository;
  private final AccountAuditOutboxRepository auditOutboxRepository;
  private final TransactionTemplate joinTransactionTemplate;
  private final int batchSize;
  private final int maxAttempts;
  private final long backoffMillis;
  private final Counter committed;
  private final Counter unresolved;
  private final Counter failures;
  private final Counter maxAttemptsReached;

  public AccountJoinReconciliationService(
      AccountJoinOperationRepository joinOperationRepository,
      AccountConnectScopeRepository connectScopeRepository,
      AccountTenantMembershipRepository membershipRepository,
      AccountAuditOutboxRepository auditOutboxRepository,
      MeterRegistry meterRegistry,
      PlatformTransactionManager transactionManager,
      @Value("${firemud.account.join-reconciliation.batch-size:50}") int batchSize,
      @Value("${firemud.account.join-reconciliation.max-attempts:12}") int maxAttempts,
      @Value("${firemud.account.join-reconciliation.backoff-ms:30000}") long backoffMillis) {
    if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
      throw new IllegalArgumentException(
          "firemud.account.join-reconciliation.batch-size must be between 1 and 100");
    }
    if (maxAttempts < 1) {
      throw new IllegalArgumentException(
          "firemud.account.join-reconciliation.max-attempts must be positive");
    }
    if (backoffMillis < 1) {
      throw new IllegalArgumentException(
          "firemud.account.join-reconciliation.backoff-ms must be positive");
    }
    this.joinOperationRepository = joinOperationRepository;
    this.connectScopeRepository = connectScopeRepository;
    this.membershipRepository = membershipRepository;
    this.auditOutboxRepository = auditOutboxRepository;
    this.batchSize = batchSize;
    this.maxAttempts = maxAttempts;
    this.backoffMillis = backoffMillis;
    this.joinTransactionTemplate = new TransactionTemplate(transactionManager);
    this.joinTransactionTemplate.setPropagationBehavior(
        TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.committed = counter(meterRegistry, "committed");
    this.unresolved = counter(meterRegistry, "unresolved");
    this.failures = counter(meterRegistry, "failure");
    this.maxAttemptsReached = counter(meterRegistry, "max_attempts_reached");
  }

  /** Processes one bounded due page using the caller's captured time for deterministic rechecks. */
  public void reconcileDueOperations(Instant now) {
    Objects.requireNonNull(now, "JOIN reconciliation time is required");
    final List<JoinOperation> dueOperations;
    try {
      dueOperations =
          joinOperationRepository.findDuePendingReconciliation(now, batchSize, maxAttempts);
    } catch (RuntimeException ex) {
      failures.increment();
      logger.warn(
          "JOIN reconciliation could not list due operations ({})", ex.getClass().getSimpleName());
      return;
    }

    for (JoinOperation candidate : dueOperations) {
      reconcileOne(candidate, now);
    }
  }

  /** Convenience entry point for direct operational invocation. */
  public void reconcileDueOperations() {
    reconcileDueOperations(Instant.now());
  }

  private void reconcileOne(JoinOperation candidate, Instant now) {
    try {
      ReconciliationResult result =
          joinTransactionTemplate.execute(transactionStatus -> reconcileLocked(candidate, now));
      if (result == null || result == ReconciliationResult.SKIPPED) {
        return;
      }
      if (result == ReconciliationResult.COMMITTED) {
        committed.increment();
      } else if (result == ReconciliationResult.ATTEMPT_RECORDED) {
        unresolved.increment();
      } else if (result == ReconciliationResult.MAX_ATTEMPTS_REACHED) {
        unresolved.increment();
        maxAttemptsReached.increment();
      }
    } catch (RuntimeException ex) {
      failures.increment();
      logger.warn(
          "JOIN reconciliation readback failed for request {} ({})",
          candidate.requestId(),
          ex.getClass().getSimpleName());
      recordUnavailableAttempt(candidate, now);
    }
  }

  private ReconciliationResult reconcileLocked(JoinOperation candidate, Instant now) {
    // Keep the same lock order as the caller: account row, then durable operation row.
    joinOperationRepository.lockAccount(candidate.accountId());
    JoinOperation operation =
        joinOperationRepository.findForUpdate(candidate.requestId()).orElse(null);
    if (operation == null
        || operation.accountId() != candidate.accountId()
        || !"PENDING".equals(operation.status())
        || operation.reconciliationAttemptCount() >= maxAttempts
        || operation.nextReconciliationAttemptAt() == null
        || operation.nextReconciliationAttemptAt().isAfter(now)) {
      return ReconciliationResult.SKIPPED;
    }

    String unresolvedReason = validateOperationAndPolicy(operation);
    if (unresolvedReason == null) {
      unresolvedReason = validateRetainedScope(operation);
    }

    JoinMembershipProof membership = null;
    AccountAuditEnvelope envelope = null;
    if (unresolvedReason == null) {
      membership =
          membershipRepository
              .findJoinProofForUpdate(operation.accountId(), operation.tenantId())
              .orElse(null);
      if (membership == null) {
        unresolvedReason = "MEMBERSHIP_EVIDENCE_ABSENT";
      } else if (!membershipMatches(operation, membership)) {
        unresolvedReason = "MEMBERSHIP_EVIDENCE_MISMATCH";
      }
    }

    if (unresolvedReason == null) {
      envelope =
          auditOutboxRepository
              .findJoinEnvelopeForUpdate(
                  joinAuditEventId(operation.requestId()), operation.tenantId())
              .orElse(null);
      if (envelope == null) {
        unresolvedReason = "JOIN_AUDIT_ENVELOPE_ABSENT";
      } else if (!auditEnvelopeMatches(operation, membership, envelope)) {
        unresolvedReason = "JOIN_AUDIT_ENVELOPE_UNCLEAR";
      }
    }

    if (unresolvedReason != null) {
      return recordUnresolvedAttempt(operation, now, unresolvedReason);
    }

    joinOperationRepository.finish(
        operation.requestId(),
        "COMMITTED",
        "JOINED",
        membership.membershipId(),
        membership.membershipVersion(),
        membership.membershipAuthorityGeneration());
    return ReconciliationResult.COMMITTED;
  }

  private String validateOperationAndPolicy(JoinOperation operation) {
    if (operation.requestId() == null
        || operation.requestId().isBlank()
        || operation.accountId() <= 0
        || operation.tenantId() <= 0
        || operation.realmId() == null
        || !hasText(operation.worldSlug())
        || !hasText(operation.realmSlug())
        || !hasText(operation.playableStateNamespaceId())
        || !hasText(operation.playableStateScope())
        || operation.gameInstanceId() <= 0
        || operation.catalogRevision() <= 0
        || operation.pointerVersion() <= 0
        || operation.callerBinding() == null
        || operation.callerBinding().isBlank()
        || operation.intentDigestVersion() != 1
        || !isSha256(operation.intentDigest())
        || operation.requestDigestVersion() == null
        || operation.requestDigestVersion() != 1
        || !isSha256(operation.requestDigest())
        || !"AVAILABLE".equals(operation.entitlementAuthorityAvailability())
        || !Boolean.TRUE.equals(operation.allowPublicJoin())
        || operation.entitlementVersion() == null
        || operation.entitlementVersion() <= 0
        || operation.outcome() != null
        || operation.membershipId() != null
        || operation.membershipVersion() != null
        || operation.membershipAuthorityGeneration() != null) {
      return "JOIN_OPERATION_POLICY_UNPROVEN";
    }
    return null;
  }

  private String validateRetainedScope(JoinOperation operation) {
    if (!isSha256(operation.scopeTokenHash()) || !isSha256(operation.connectScopeDigest())) {
      return "JOIN_SCOPE_DIGEST_UNCLEAR";
    }
    var evidence =
        connectScopeRepository.findEvidenceByTokenHash(operation.scopeTokenHash()).orElse(null);
    if (evidence == null) {
      return "JOIN_SCOPE_EVIDENCE_ABSENT";
    }
    if (!scopeMatches(operation, evidence)) {
      return "JOIN_SCOPE_EVIDENCE_MISMATCH";
    }
    if (evidence.evaluatedAt() == null || evidence.connectScopeExpiresAt() == null) {
      return "JOIN_SCOPE_TIME_UNCLEAR";
    }
    try {
      Instant evaluatedAt = Instant.parse(evidence.evaluatedAt());
      Instant expiresAt = Instant.parse(evidence.connectScopeExpiresAt());
      // Expiry blocks new caller use; it cannot erase positive persisted commit evidence.
      if (!expiresAt.isAfter(evaluatedAt)) {
        return "JOIN_SCOPE_TIME_UNCLEAR";
      }
    } catch (DateTimeException ex) {
      return "JOIN_SCOPE_TIME_UNCLEAR";
    }
    return null;
  }

  private static boolean scopeMatches(
      JoinOperation operation, AccountConnectScopeRepository.ConnectScopeEvidence evidence) {
    return evidence.accountId() == operation.accountId()
        && "PUBLIC_PRODUCTION".equals(evidence.targetClass())
        && evidence.tenantId() == operation.tenantId()
        && Objects.equals(evidence.realmId(), operation.realmId())
        && Objects.equals(evidence.worldSlug(), operation.worldSlug())
        && Objects.equals(evidence.realmSlug(), operation.realmSlug())
        && Objects.equals(evidence.playableStateNamespaceId(), operation.playableStateNamespaceId())
        && Objects.equals(evidence.playableStateScope(), operation.playableStateScope())
        && evidence.gameInstanceId() == operation.gameInstanceId()
        && evidence.catalogRevision() == operation.catalogRevision()
        && evidence.pointerVersion() == operation.pointerVersion()
        && Objects.equals(evidence.scopeTokenHash(), operation.scopeTokenHash())
        && Objects.equals(evidence.snapshotDigest(), operation.connectScopeDigest())
        && isSha256(evidence.snapshotDigest());
  }

  private static boolean membershipMatches(
      JoinOperation operation, JoinMembershipProof membership) {
    return membership.membershipId() > 0
        && membership.accountId() == operation.accountId()
        && membership.tenantId() == operation.tenantId()
        && membership.gameplayAdmissionAllowed()
        && "ACTIVE".equals(membership.lifecycleState())
        && "EXPLICIT_JOIN".equals(membership.authorityProvenance())
        && membership.membershipVersion() > 0
        && membership.membershipAuthorityGeneration() > 0;
  }

  private static boolean auditEnvelopeMatches(
      JoinOperation operation, JoinMembershipProof membership, AccountAuditEnvelope envelope) {
    if (!joinAuditEventId(operation.requestId()).equals(envelope.auditEventId())
        || !"tenant".equals(envelope.scope())
        || !Objects.equals(envelope.tenantId(), operation.tenantId())
        || !"account-service".equals(envelope.producerService())
        || !"ACCOUNT_JOINED_PUBLIC_PRODUCTION".equals(envelope.eventType())
        || envelope.occurredAt() == null
        || envelope.schemaVersion() != 1
        || envelope.payloadDigestVersion() != 1
        || !isSha256(envelope.payloadDigest())
        || envelope.payload() == null
        || !envelope.payloadDigest().equals(AccountAuditDigest.ofPayload(envelope.payload()))) {
      return false;
    }

    JoinAuditPayload payload = parseJoinAuditPayload(envelope.payload());
    return payload != null
        && payload.accountId() == operation.accountId()
        && payload.tenantId() == operation.tenantId()
        && Objects.equals(payload.worldSlug(), operation.worldSlug())
        && Objects.equals(payload.realmSlug(), operation.realmSlug())
        && payload.membershipVersion() == membership.membershipVersion()
        && Objects.equals(payload.requestId(), operation.requestId());
  }

  private static JoinAuditPayload parseJoinAuditPayload(String json) {
    try {
      JsonNode root = AUDIT_JSON.readTree(json);
      if (root == null || !root.isObject()) {
        return null;
      }
      Set<String> names = new HashSet<>();
      root.properties().forEach(entry -> names.add(entry.getKey()));
      if (!JOIN_AUDIT_FIELDS.equals(names)) {
        return null;
      }
      JsonNode accountId = root.get("accountId");
      JsonNode tenantId = root.get("tenantId");
      JsonNode worldSlug = root.get("worldSlug");
      JsonNode realmSlug = root.get("realmSlug");
      JsonNode membershipVersion = root.get("membershipVersion");
      JsonNode requestId = root.get("requestId");
      if (!isLong(accountId)
          || !isLong(tenantId)
          || !worldSlug.isTextual()
          || !realmSlug.isTextual()
          || !isLong(membershipVersion)
          || !requestId.isTextual()) {
        return null;
      }
      return new JoinAuditPayload(
          accountId.longValue(),
          tenantId.longValue(),
          worldSlug.textValue(),
          realmSlug.textValue(),
          membershipVersion.longValue(),
          requestId.textValue());
    } catch (RuntimeException ex) {
      return null;
    }
  }

  private static boolean isLong(JsonNode node) {
    return node != null && node.isIntegralNumber() && node.canConvertToLong();
  }

  private ReconciliationResult recordUnresolvedAttempt(
      JoinOperation operation, Instant now, String reason) {
    boolean recorded =
        joinOperationRepository.recordReconciliationAttempt(
            operation.requestId(),
            operation.reconciliationAttemptCount(),
            maxAttempts,
            now,
            reason,
            nextAttemptAt(now));
    if (!recorded) {
      return ReconciliationResult.SKIPPED;
    }
    if (operation.reconciliationAttemptCount() + 1 >= maxAttempts) {
      logger.warn(
          "JOIN reconciliation reached its attempt limit for request {}; it remains PENDING and caller-retryable (reason {})",
          operation.requestId(),
          reason);
      return ReconciliationResult.MAX_ATTEMPTS_REACHED;
    }
    logger.debug(
        "JOIN reconciliation remains pending for request {} (reason {})",
        operation.requestId(),
        reason);
    return ReconciliationResult.ATTEMPT_RECORDED;
  }

  private void recordUnavailableAttempt(JoinOperation candidate, Instant now) {
    try {
      joinTransactionTemplate.execute(
          transactionStatus -> {
            joinOperationRepository.lockAccount(candidate.accountId());
            JoinOperation operation =
                joinOperationRepository.findForUpdate(candidate.requestId()).orElse(null);
            if (operation == null
                || operation.accountId() != candidate.accountId()
                || !"PENDING".equals(operation.status())
                || operation.reconciliationAttemptCount() >= maxAttempts
                || operation.nextReconciliationAttemptAt() == null
                || operation.nextReconciliationAttemptAt().isAfter(now)) {
              return ReconciliationResult.SKIPPED;
            }
            return recordUnresolvedAttempt(operation, now, "JOIN_READBACK_UNAVAILABLE");
          });
    } catch (RuntimeException ex) {
      logger.warn(
          "JOIN reconciliation could not persist a retry diagnostic for request {} ({})",
          candidate.requestId(),
          ex.getClass().getSimpleName());
    }
  }

  private Instant nextAttemptAt(Instant attemptedAt) {
    try {
      return attemptedAt.plusMillis(backoffMillis);
    } catch (DateTimeException | ArithmeticException ex) {
      throw new IllegalStateException("JOIN reconciliation backoff is outside timestamp range", ex);
    }
  }

  private static UUID joinAuditEventId(String requestId) {
    return UUID.nameUUIDFromBytes(
        ("account-join-audit/v1:" + requestId).getBytes(StandardCharsets.UTF_8));
  }

  private static boolean isSha256(String value) {
    return value != null && SHA256_PATTERN.matcher(value).matches();
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  private static Counter counter(MeterRegistry meterRegistry, String result) {
    return Counter.builder("account.join.reconciliation.operations")
        .description("Account JOIN reconciliation operation outcomes")
        .tag("result", result)
        .register(meterRegistry);
  }

  private enum ReconciliationResult {
    SKIPPED,
    COMMITTED,
    ATTEMPT_RECORDED,
    MAX_ATTEMPTS_REACHED
  }

  private record JoinAuditPayload(
      long accountId,
      long tenantId,
      String worldSlug,
      String realmSlug,
      long membershipVersion,
      String requestId) {}
}
