package net.firedevops.firemud.accountservice.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.accountservice.dto.AccountAuditDigest;
import net.firedevops.firemud.accountservice.dto.AccountAuditEnvelope;
import net.firedevops.firemud.accountservice.dto.CanonicalMembershipTransitionReceipt;
import net.firedevops.firemud.accountservice.repository.AccountAuditOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Checkpoint;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository.CanonicalJoinOperationEvidence;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository.CanonicalJoinReconciliationCandidate;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.VerifiedTenantProvenance;
import net.firedevops.firemud.accountservice.repository.AccountMembershipTransitionReceiptRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository.RoleSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Readback-only recovery candidate for canonical UUID JOIN operations; no runtime path invokes it.
 */
@Service
@SuppressFBWarnings(
    value = "CT_CONSTRUCTOR_THROW",
    justification = "Invalid bounded retry settings must fail startup.")
public class AccountCanonicalJoinReconciliationService {
  private static final Logger logger =
      LoggerFactory.getLogger(AccountCanonicalJoinReconciliationService.class);
  private static final int MAX_BATCH_SIZE = 100;
  private static final Pattern SHA256 = Pattern.compile("sha256:[0-9a-f]{64}");

  private final AccountJoinOperationRepository joinOperationRepository;
  private final AccountTenantMembershipRepository membershipRepository;
  private final AccountTenantMembershipRoleSnapshotRepository roleSnapshotRepository;
  private final AccountMembershipTransitionReceiptRepository transitionReceiptRepository;
  private final AccountAuditOutboxRepository auditOutboxRepository;
  private final AccountMembershipAuthorityEventProducer membershipAuthorityEventProducer;
  private final TransactionTemplate pageTransactionTemplate;
  private final TransactionTemplate reconciliationTransactionTemplate;
  private final int batchSize;
  private final int maxAttempts;
  private final long backoffMillis;

  public AccountCanonicalJoinReconciliationService(
      AccountJoinOperationRepository joinOperationRepository,
      AccountTenantMembershipRepository membershipRepository,
      AccountTenantMembershipRoleSnapshotRepository roleSnapshotRepository,
      AccountMembershipTransitionReceiptRepository transitionReceiptRepository,
      AccountAuditOutboxRepository auditOutboxRepository,
      AccountMembershipAuthorityEventProducer membershipAuthorityEventProducer,
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
    this.joinOperationRepository =
        Objects.requireNonNull(joinOperationRepository, "JOIN operation repository is required");
    this.membershipRepository =
        Objects.requireNonNull(membershipRepository, "Membership repository is required");
    this.roleSnapshotRepository =
        Objects.requireNonNull(roleSnapshotRepository, "Role snapshot repository is required");
    this.transitionReceiptRepository =
        Objects.requireNonNull(
            transitionReceiptRepository, "Transition receipt repository is required");
    this.auditOutboxRepository =
        Objects.requireNonNull(auditOutboxRepository, "Audit outbox repository is required");
    this.membershipAuthorityEventProducer =
        Objects.requireNonNull(
            membershipAuthorityEventProducer, "Membership authority event producer is required");
    this.batchSize = batchSize;
    this.maxAttempts = maxAttempts;
    this.backoffMillis = backoffMillis;
    this.pageTransactionTemplate = new TransactionTemplate(transactionManager);
    this.pageTransactionTemplate.setPropagationBehavior(
        TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.pageTransactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.reconciliationTransactionTemplate = new TransactionTemplate(transactionManager);
    this.reconciliationTransactionTemplate.setPropagationBehavior(
        TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.reconciliationTransactionTemplate.setIsolationLevel(
        TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  /** Processes one bounded due page; scope expiry and missing evidence never create an outcome. */
  public void reconcileDueOperations(Instant now) {
    Objects.requireNonNull(now, "Canonical JOIN reconciliation time is required");
    final List<CanonicalJoinReconciliationCandidate> dueOperations;
    try {
      dueOperations =
          pageTransactionTemplate.execute(
              status ->
                  joinOperationRepository.findDueCanonicalPendingReconciliation(
                      now, batchSize, maxAttempts));
    } catch (RuntimeException exception) {
      logger.warn(
          "Canonical JOIN reconciliation could not list due operations ({})",
          exception.getClass().getSimpleName());
      return;
    }
    if (dueOperations == null) {
      return;
    }
    for (CanonicalJoinReconciliationCandidate candidate : dueOperations) {
      reconcileOne(candidate, now);
    }
  }

  private void reconcileOne(CanonicalJoinReconciliationCandidate candidate, Instant now) {
    try {
      reconciliationTransactionTemplate.execute(status -> reconcileLocked(candidate, now));
    } catch (RuntimeException exception) {
      logger.warn(
          "Canonical JOIN reconciliation readback failed for request {} ({})",
          candidate.requestId(),
          exception.getClass().getSimpleName());
      recordUnavailableAttempt(candidate, now);
    }
  }

  private ReconciliationResult reconcileLocked(
      CanonicalJoinReconciliationCandidate candidate, Instant now) {
    // All dependent evidence and any operation outcome are read or written after this Account
    // fence.
    joinOperationRepository.lockAccount(candidate.privateAccountId());
    CanonicalJoinOperationEvidence operation =
        joinOperationRepository
            .findCanonicalEvidenceByRequestId(candidate.requestId())
            .orElse(null);
    if (!isDueCandidate(operation, candidate, now)) {
      return ReconciliationResult.SKIPPED;
    }

    String unresolvedReason = validatePendingOperation(operation);
    var scope = operation.scopeEvidence();
    if (unresolvedReason == null) {
      try {
        var membership =
            membershipRepository
                .findCanonicalMembershipForUpdate(scope.accountUuid(), scope.tenantUuid())
                .orElse(null);
        if (membership == null) {
          unresolvedReason = "CANONICAL_MEMBERSHIP_ABSENT";
        } else if (!membershipMatches(operation, membership)) {
          unresolvedReason = "CANONICAL_MEMBERSHIP_MISMATCH";
        } else {
          RoleSnapshot roles =
              roleSnapshotRepository
                  .findForCanonicalUpdate(
                      scope.accountUuid(),
                      scope.tenantUuid(),
                      scope.tenantProvenance(),
                      membership.getId(),
                      membership.getMembershipVersion())
                  .orElse(null);
          if (roles == null) {
            unresolvedReason = "CANONICAL_ROLE_SNAPSHOT_ABSENT";
          } else if (!roleSnapshotMatches(operation, membership.getId(), roles)) {
            unresolvedReason = "CANONICAL_ROLE_SNAPSHOT_MISMATCH";
          } else {
            unresolvedReason =
                validateReceipt(operation, membership.getId(), roles, scope.tenantProvenance());
            if (unresolvedReason == null) {
              unresolvedReason = validateAuditEnvelope(operation);
            }
            if (unresolvedReason == null) {
              try {
                Checkpoint checkpoint =
                    membershipAuthorityEventProducer.requireCanonicalFirstJoinEvent(operation);
                if (checkpoint == null
                    || checkpoint.outboxSequence() != 1L
                    || checkpoint.sourceEventId() == null
                    || checkpoint.sourceEventDigest() == null) {
                  unresolvedReason = "CANONICAL_AUTHORITY_EVENT_MISMATCH";
                }
              } catch (RuntimeException exception) {
                unresolvedReason = "CANONICAL_AUTHORITY_EVENT_UNAVAILABLE";
              }
            }
            if (unresolvedReason == null) {
              joinOperationRepository.finishCanonicalOperation(
                  operation.requestId(),
                  "COMMITTED",
                  "JOINED",
                  membership.getId(),
                  membership.getMembershipVersion(),
                  membership.getMembershipAuthorityGeneration());
              return ReconciliationResult.COMMITTED;
            }
          }
        }
      } catch (RuntimeException exception) {
        unresolvedReason = "CANONICAL_MEMBERSHIP_READBACK_UNAVAILABLE";
      }
    }

    return recordUnresolvedAttempt(operation, now, unresolvedReason);
  }

  private boolean isDueCandidate(
      CanonicalJoinOperationEvidence operation,
      CanonicalJoinReconciliationCandidate candidate,
      Instant now) {
    return operation != null
        && candidate != null
        && operation.privateAccountId() == candidate.privateAccountId()
        && operation.requestId().equals(candidate.requestId())
        && "PENDING".equals(operation.status())
        && operation.reconciliationAttemptCount() == candidate.reconciliationAttemptCount()
        && operation.nextReconciliationAttemptAt().equals(candidate.nextReconciliationAttemptAt())
        && operation.reconciliationAttemptCount() < maxAttempts
        && operation.nextReconciliationAttemptAt() != null
        && !operation.nextReconciliationAttemptAt().isAfter(now);
  }

  private String validatePendingOperation(CanonicalJoinOperationEvidence operation) {
    var scope = operation.scopeEvidence();
    if (operation.operationRepresentationVersion() != 2
        || operation.scopeDigestVersion() != 2
        || operation.intentDigestVersion() != 2
        || operation.requestDigestVersion() == null
        || operation.requestDigestVersion() != 2
        || !isSha256(operation.intentDigest())
        || !isSha256(operation.requestDigest())
        || !isSha256(operation.scopeTokenHash())
        || !isSha256(operation.connectScopeDigest())
        || !"AVAILABLE".equals(operation.entitlementAuthorityAvailability())
        || !Boolean.TRUE.equals(operation.allowPublicJoin())
        || operation.entitlementVersion() == null
        || operation.entitlementVersion() <= 0L
        || !("AVAILABLE".equals(operation.lastAttemptAuthorityAvailability())
                && operation.lastAttemptFailureCode() == null
            || "UNAVAILABLE".equals(operation.lastAttemptAuthorityAvailability())
                && operation.lastAttemptFailureCode() != null
                && !operation.lastAttemptFailureCode().isBlank())
        || operation.callerBoundAuthorityInvalidated()
        || !"PENDING".equals(operation.status())
        || operation.outcome() != null
        || operation.membershipId() != null
        || operation.membershipVersion() != null
        || operation.membershipAuthorityGeneration() != null
        || !"PUBLIC_PRODUCTION".equals(scope.targetClass())
        || scope.privateAccountId() != operation.privateAccountId()
        || !operation.scopeTokenHash().equals(scope.scopeTokenHash())
        || !operation.connectScopeDigest().equals(scope.scopeDigest())) {
      return "CANONICAL_JOIN_OPERATION_POLICY_UNPROVEN";
    }
    return null;
  }

  private static boolean membershipMatches(
      CanonicalJoinOperationEvidence operation,
      net.firedevops.firemud.accountservice.entity.AccountTenantMembership membership) {
    var scope = operation.scopeEvidence();
    VerifiedTenantProvenance provenance = scope.tenantProvenance();
    return membership.getId() != null
        && membership.getId() > 0L
        && membership.getAccount() != null
        && membership.getAccount().getId() != null
        && membership.getAccount().getId() == operation.privateAccountId()
        && scope.accountUuid().equals(membership.getAccount().getAccountUuid())
        && scope.tenantUuid().equals(membership.getTenantUuid())
        && Objects.equals(provenance.legacyTenantId(), membership.getTenantId())
        && provenance.kind().name().equals(membership.getTenantProvenanceKind())
        && provenance.sourceOperationId().equals(membership.getTenantSourceOperationId())
        && provenance.digest().equals(membership.getTenantProvenanceDigest())
        && membership.isGameplayAdmissionAllowed()
        && "ACTIVE".equals(membership.getLifecycleState())
        && "EXPLICIT_JOIN".equals(membership.getAuthorityProvenance())
        && membership.getMembershipVersion() == 2L
        && membership.getMembershipAuthorityGeneration() == 1L;
  }

  private static boolean roleSnapshotMatches(
      CanonicalJoinOperationEvidence operation, long membershipId, RoleSnapshot roles) {
    var scope = operation.scopeEvidence();
    return roles.accountId() == operation.privateAccountId()
        && Objects.equals(roles.tenantId(), scope.tenantProvenance().legacyTenantId())
        && roles.membershipId() == membershipId
        && roles.snapshotVersion() == 2L
        && scope.accountUuid().equals(roles.accountUuid())
        && scope.tenantUuid().equals(roles.tenantUuid())
        && scope.tenantProvenance().equals(roles.tenantProvenance())
        && List.of("player").equals(roles.roles());
  }

  private String validateReceipt(
      CanonicalJoinOperationEvidence operation,
      long membershipId,
      RoleSnapshot roles,
      VerifiedTenantProvenance provenance) {
    final CanonicalMembershipTransitionReceipt receipt;
    try {
      receipt =
          transitionReceiptRepository.findCanonicalByRequestId(operation.requestId()).orElse(null);
    } catch (RuntimeException exception) {
      return "CANONICAL_TRANSITION_RECEIPT_UNAVAILABLE";
    }
    if (receipt == null) {
      return "CANONICAL_TRANSITION_RECEIPT_ABSENT";
    }
    var scope = operation.scopeEvidence();
    boolean exact =
        receipt.receiptSequence() == 1L
            && receipt.accountId().equals(scope.accountUuid())
            && receipt.tenantId().equals(scope.tenantUuid())
            && receipt.requestId().equals(operation.requestId())
            && "MEMBERSHIP_JOINED".equals(receipt.transitionType())
            && "ACTIVE".equals(receipt.membershipLifecycleState())
            && receipt.gameplayAdmissionAllowed()
            && Map.of(scope.tenantUuid().toString(), "2").equals(receipt.membershipVersion())
            && receipt.membershipAuthorityGeneration() == 1L
            && "EXPLICIT_JOIN".equals(receipt.authorityProvenance())
            && provenance.kind().name().equals(receipt.tenantProvenanceKind())
            && provenance.sourceOperationId().equals(receipt.tenantSourceOperationId())
            && provenance.digest().equals(receipt.tenantProvenanceDigest())
            && receipt
                .receiptId()
                .equals(
                    net.firedevops.firemud.accountservice.dto.MembershipTransitionReceiptDigest
                        .receiptIdForRequestV2(operation.requestId()))
            && roles.membershipId() == membershipId;
    return exact ? null : "CANONICAL_TRANSITION_RECEIPT_MISMATCH";
  }

  private String validateAuditEnvelope(CanonicalJoinOperationEvidence operation) {
    var scope = operation.scopeEvidence();
    final AccountAuditEnvelope envelope;
    try {
      envelope =
          auditOutboxRepository
              .findCanonicalJoinEnvelopeForUpdate(
                  joinAuditEventId(operation.requestId()), scope.tenantUuid())
              .orElse(null);
    } catch (RuntimeException exception) {
      return "CANONICAL_JOIN_AUDIT_ENVELOPE_MISMATCH";
    }
    if (envelope == null) {
      return "CANONICAL_JOIN_AUDIT_ENVELOPE_ABSENT";
    }
    if (envelope.payload() == null
        || envelope.schemaVersion() != 1
        || envelope.payloadDigestVersion() != 1
        || !isSha256(envelope.payloadDigest())
        || !envelope.payloadDigest().equals(AccountAuditDigest.ofPayload(envelope.payload()))) {
      return "CANONICAL_JOIN_AUDIT_ENVELOPE_UNCLEAR";
    }
    String expectedPayload =
        AccountAuditOutboxRepository.canonicalJoinPayload(
            scope.accountUuid(),
            scope.tenantUuid(),
            scope.worldSlug(),
            scope.realmSlug(),
            Map.of(scope.tenantUuid().toString(), "2"),
            operation.requestId());
    return expectedPayload.equals(envelope.payload())
            && joinAuditEventId(operation.requestId()).equals(envelope.auditEventId())
        ? null
        : "CANONICAL_JOIN_AUDIT_ENVELOPE_MISMATCH";
  }

  private static UUID joinAuditEventId(String requestId) {
    return UUID.nameUUIDFromBytes(
        ("account-join-audit/v1:" + requestId).getBytes(StandardCharsets.UTF_8));
  }

  private ReconciliationResult recordUnresolvedAttempt(
      CanonicalJoinOperationEvidence operation, Instant attemptedAt, String reason) {
    boolean recorded =
        joinOperationRepository.recordCanonicalReconciliationAttempt(
            operation.requestId(),
            operation.reconciliationAttemptCount(),
            maxAttempts,
            attemptedAt,
            reason,
            nextAttemptAt(attemptedAt));
    return !recorded
        ? ReconciliationResult.SKIPPED
        : operation.reconciliationAttemptCount() + 1 >= maxAttempts
            ? ReconciliationResult.MAX_ATTEMPTS_REACHED
            : ReconciliationResult.ATTEMPT_RECORDED;
  }

  private void recordUnavailableAttempt(
      CanonicalJoinReconciliationCandidate candidate, Instant attemptedAt) {
    try {
      reconciliationTransactionTemplate.execute(
          status -> {
            joinOperationRepository.lockAccount(candidate.privateAccountId());
            if (candidate.reconciliationAttemptCount() >= maxAttempts
                || candidate.nextReconciliationAttemptAt().isAfter(attemptedAt)) {
              return ReconciliationResult.SKIPPED;
            }
            boolean recorded =
                joinOperationRepository.recordCanonicalReconciliationAttempt(
                    candidate.requestId(),
                    candidate.reconciliationAttemptCount(),
                    maxAttempts,
                    attemptedAt,
                    "CANONICAL_JOIN_READBACK_UNAVAILABLE",
                    nextAttemptAt(attemptedAt));
            return !recorded
                ? ReconciliationResult.SKIPPED
                : candidate.reconciliationAttemptCount() + 1 >= maxAttempts
                    ? ReconciliationResult.MAX_ATTEMPTS_REACHED
                    : ReconciliationResult.ATTEMPT_RECORDED;
          });
    } catch (RuntimeException exception) {
      logger.warn(
          "Canonical JOIN reconciliation could not persist a retry diagnostic for request {} ({})",
          candidate.requestId(),
          exception.getClass().getSimpleName());
    }
  }

  private Instant nextAttemptAt(Instant attemptedAt) {
    try {
      return attemptedAt.plusMillis(backoffMillis);
    } catch (DateTimeException | ArithmeticException exception) {
      throw new IllegalStateException(
          "Canonical JOIN reconciliation backoff is outside timestamp range", exception);
    }
  }

  private static boolean isSha256(String value) {
    return value != null && SHA256.matcher(value).matches();
  }

  private enum ReconciliationResult {
    SKIPPED,
    COMMITTED,
    ATTEMPT_RECORDED,
    MAX_ATTEMPTS_REACHED
  }
}
