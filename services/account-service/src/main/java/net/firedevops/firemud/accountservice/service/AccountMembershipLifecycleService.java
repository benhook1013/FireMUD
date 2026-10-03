package net.firedevops.firemud.accountservice.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountAuditDigest;
import net.firedevops.firemud.accountservice.dto.AccountAuditEnvelope;
import net.firedevops.firemud.accountservice.dto.MembershipTransitionReceipt;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountTenantMembership;
import net.firedevops.firemud.accountservice.repository.AccountAuditOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Event;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipTransitionReceiptRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipTransitionReceiptRepository.TransitionReceiptEvidence;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantIdentityResolver;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository.RoleSnapshot;
import net.firedevops.firemud.accountservice.repository.ApprovedLegacyTenantAssociationRepository.ApprovedAssociation;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.MembershipEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unwired, Account-owned membership lifecycle primitive. Numeric scope inputs identify rows only;
 * this class provides no caller authentication, route, credential issuance, or runtime dispatch.
 */
@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification =
        "Injected Account repositories and transaction manager are private collaborators.")
public final class AccountMembershipLifecycleService {
  private static final String MEMBERSHIP_LEFT = "MEMBERSHIP_LEFT";
  private static final String LEFT_AUDIT_TYPE = "ACCOUNT_MEMBERSHIP_LEFT";
  private static final JsonMapper AUDIT_JSON = JsonMapper.builder().build();

  private final AccountJoinOperationRepository joinOperationRepository;
  private final AccountRepository accountRepository;
  private final AccountTenantIdentityResolver tenantIdentityResolver;
  private final AccountTenantMembershipRepository membershipRepository;
  private final AccountTenantMembershipRoleSnapshotRepository roleSnapshotRepository;
  private final AccountMembershipTransitionReceiptRepository receiptRepository;
  private final AccountAuthorityOutboxRepository authorityOutboxRepository;
  private final AccountAuditOutboxRepository auditOutboxRepository;
  private final AccountMembershipAuthorityEventProducer authorityEventProducer;
  private final TransactionTemplate ownerTransaction;

  public AccountMembershipLifecycleService(
      AccountJoinOperationRepository joinOperationRepository,
      AccountRepository accountRepository,
      AccountTenantIdentityResolver tenantIdentityResolver,
      AccountTenantMembershipRepository membershipRepository,
      AccountTenantMembershipRoleSnapshotRepository roleSnapshotRepository,
      AccountMembershipTransitionReceiptRepository receiptRepository,
      AccountAuthorityOutboxRepository authorityOutboxRepository,
      AccountAuditOutboxRepository auditOutboxRepository,
      AccountMembershipAuthorityEventProducer authorityEventProducer,
      PlatformTransactionManager transactionManager) {
    this.joinOperationRepository = Objects.requireNonNull(joinOperationRepository);
    this.accountRepository = Objects.requireNonNull(accountRepository);
    this.tenantIdentityResolver = Objects.requireNonNull(tenantIdentityResolver);
    this.membershipRepository = Objects.requireNonNull(membershipRepository);
    this.roleSnapshotRepository = Objects.requireNonNull(roleSnapshotRepository);
    this.receiptRepository = Objects.requireNonNull(receiptRepository);
    this.authorityOutboxRepository = Objects.requireNonNull(authorityOutboxRepository);
    this.auditOutboxRepository = Objects.requireNonNull(auditOutboxRepository);
    this.authorityEventProducer = Objects.requireNonNull(authorityEventProducer);
    this.ownerTransaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    this.ownerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /**
   * Commits an internal owner-local LEFT using one stable request ID. The returned provisional
   * receipt identifies that immutable operation only; it is not current authority or authorization.
   */
  public MembershipTransitionReceipt leave(long accountId, long legacyTenantId, String requestId) {
    if (accountId <= 0L || legacyTenantId <= 0L) {
      throw new IllegalArgumentException("Account and retained tenant identities must be positive");
    }
    requireRequestId(requestId);
    return Objects.requireNonNull(
        ownerTransaction.execute(
            status -> leaveInOwnerTransaction(accountId, legacyTenantId, requestId)),
        "Account LEFT transaction returned no immutable receipt");
  }

  private MembershipTransitionReceipt leaveInOwnerTransaction(
      long accountId, long legacyTenantId, String requestId) {
    joinOperationRepository.lockAccount(accountId);
    CanonicalScope scope = resolveCanonicalScope(accountId, legacyTenantId);

    Optional<TransitionReceiptEvidence> priorReceipt = receiptRepository.findByRequestId(requestId);
    if (priorReceipt.isPresent()) {
      TransitionReceiptEvidence evidence = priorReceipt.orElseThrow();
      if (evidence.accountId() != accountId
          || evidence.tenantId() != legacyTenantId
          || !MEMBERSHIP_LEFT.equals(evidence.receipt().transitionType())) {
        throw new IllegalStateException("Membership request ID was reused for another operation");
      }
      return requireExactLeftReplay(scope, evidence);
    }
    if (joinOperationRepository.find(requestId).isPresent()) {
      throw new IllegalStateException("Membership request ID conflicts with an existing JOIN");
    }

    String streamKey = membershipStreamKey(scope.accountUuid(), scope.tenantUuid());
    if (authorityOutboxRepository.findEvent(streamKey, requestId).isPresent()) {
      throw new IllegalStateException("LEFT request has an event without its matching receipt");
    }
    UUID auditEventId = leftAuditId(requestId);
    if (auditOutboxRepository
        .findMembershipLeftEnvelopeForUpdate(auditEventId, legacyTenantId)
        .isPresent()) {
      throw new IllegalStateException("LEFT request has an audit envelope without its receipt");
    }

    AccountMembershipAuthorityEventProducer.PositiveMembershipSnapshot active =
        authorityEventProducer.readCurrentPairBoundPositiveMembershipSnapshot(
            accountId, legacyTenantId);
    if (!scope.accountUuid().toString().equals(active.accountId())
        || !scope.tenantUuid().toString().equals(active.tenantId())
        || !active.membershipExists()
        || !"ACTIVE".equals(active.membershipLifecycleState())
        || !active.gameplayAdmissionAllowed()) {
      throw new IllegalStateException("LEFT requires complete current positive ACTIVE membership");
    }

    AccountTenantMembership membership =
        membershipRepository
            .findByAccountIdAndTenantId(accountId, legacyTenantId)
            .orElseThrow(
                () -> new IllegalStateException("Current Account membership row is absent"));
    if (membership.getId() == null
        || membership.getId() <= 0L
        || membership.getAccount() == null
        || membership.getAccount().getId() == null
        || membership.getAccount().getId() != accountId
        || membership.getTenantId() != legacyTenantId
        || !"ACTIVE".equals(membership.getLifecycleState())
        || !membership.isGameplayAdmissionAllowed()
        || membership.getMembershipVersion()
            != Long.parseLong(active.membershipVersion().get(scope.tenantUuid().toString()))
        || membership.getMembershipAuthorityGeneration()
            != Long.parseLong(active.membershipAuthorityGeneration())
        || !"EXPLICIT_JOIN".equals(membership.getAuthorityProvenance())) {
      throw new IllegalStateException("Current Account membership differs from its positive proof");
    }
    RoleSnapshot roles =
        roleSnapshotRepository
            .findForUpdate(
                accountId, legacyTenantId, membership.getId(), membership.getMembershipVersion())
            .orElseThrow(
                () -> new IllegalStateException("Current Account role snapshot is absent"));
    if (!active.roles().equals(roles.roles())) {
      throw new IllegalStateException(
          "Current Account role snapshot differs from its positive proof");
    }

    long nextVersion = increment(membership.getMembershipVersion(), "membership version");
    long nextGeneration =
        increment(membership.getMembershipAuthorityGeneration(), "membership authority generation");
    membership.setLifecycleState("INACTIVE");
    membership.setGameplayAdmissionAllowed(false);
    membership.setMembershipVersion(nextVersion);
    membership.setMembershipAuthorityGeneration(nextGeneration);
    membership.setAuthorityProvenance("EXPLICIT_JOIN");
    membershipRepository.saveAndFlush(membership);
    roleSnapshotRepository.replace(membership, nextVersion, roles.roles());

    var checkpoint =
        authorityEventProducer.publishLeftMembershipChange(
            accountId, legacyTenantId, requestId, membership);
    if (checkpoint == null || checkpoint.outboxSequence() <= 0L) {
      throw new IllegalStateException("Account LEFT event checkpoint readback is incomplete");
    }
    MembershipTransitionReceipt receipt =
        receiptRepository.appendTransition(membership, MEMBERSHIP_LEFT, requestId);
    Event leftEvent =
        authorityOutboxRepository
            .findEvent(streamKey, requestId)
            .orElseThrow(() -> new IllegalStateException("Account LEFT event readback is absent"));
    TransitionReceiptEvidence leftReceiptEvidence =
        new TransitionReceiptEvidence(
            receipt,
            accountId,
            legacyTenantId,
            membership.getLifecycleState(),
            membership.isGameplayAdmissionAllowed(),
            membership.getMembershipVersion(),
            membership.getMembershipAuthorityGeneration(),
            membership.getAuthorityProvenance());
    MembershipEvent verifiedLeftEvent = verifyLeftEvent(scope, leftReceiptEvidence, leftEvent);
    if (leftEvent.outboxSequence() != checkpoint.outboxSequence()
        || !leftEvent.eventId().equals(checkpoint.sourceEventId())
        || !leftEvent.eventDigest().equals(checkpoint.sourceEventDigest())
        || !roles.roles().equals(verifiedLeftEvent.roles())) {
      throw new IllegalStateException("Account LEFT event differs from its committed checkpoint");
    }
    String payload =
        leftAuditPayload(scope, receipt, leftEvent, leftReceiptEvidence, verifiedLeftEvent);
    AccountAuditEnvelope appended =
        auditOutboxRepository.append(
            auditEventId, "tenant", legacyTenantId, LEFT_AUDIT_TYPE, payload);
    requireExactAuditEnvelope(
        scope,
        auditEventId,
        legacyTenantId,
        payload,
        appended,
        auditOutboxRepository
            .findMembershipLeftEnvelopeForUpdate(auditEventId, legacyTenantId)
            .orElseThrow(() -> new IllegalStateException("Account LEFT audit readback is absent")));
    return receipt;
  }

  private MembershipTransitionReceipt requireExactLeftReplay(
      CanonicalScope scope, TransitionReceiptEvidence evidence) {
    MembershipTransitionReceipt receipt = evidence.receipt();
    if (!"INACTIVE".equals(evidence.lifecycleState())
        || evidence.gameplayAdmissionAllowed()
        || !"EXPLICIT_JOIN".equals(evidence.authorityProvenance())) {
      throw new IllegalStateException("Stored LEFT receipt state is contradictory");
    }
    String streamKey = membershipStreamKey(scope.accountUuid(), scope.tenantUuid());
    Event storedEvent =
        authorityOutboxRepository
            .findEvent(streamKey, receipt.requestId())
            .orElseThrow(() -> new IllegalStateException("Stored LEFT receipt has no exact event"));
    MembershipEvent event = verifyLeftEvent(scope, evidence, storedEvent);
    UUID auditEventId = leftAuditId(receipt.requestId());
    String payload = leftAuditPayload(scope, receipt, storedEvent, evidence, event);
    AccountAuditEnvelope audit =
        auditOutboxRepository
            .findMembershipLeftEnvelopeForUpdate(auditEventId, scope.legacyTenantId())
            .orElseThrow(() -> new IllegalStateException("Stored LEFT receipt has no exact audit"));
    requireExactAuditEnvelope(scope, auditEventId, scope.legacyTenantId(), payload, audit, audit);
    return receipt;
  }

  private MembershipEvent verifyLeftEvent(
      CanonicalScope scope, TransitionReceiptEvidence evidence, Event storedEvent) {
    MembershipTransitionReceipt receipt = evidence.receipt();
    MembershipEvent event;
    try {
      event =
          MembershipAuthorityEventV1Codec.verify(
              new String(storedEvent.payload(), StandardCharsets.UTF_8));
    } catch (RuntimeException exception) {
      throw new IllegalStateException("Stored LEFT authority event is invalid", exception);
    }
    String streamKey = membershipStreamKey(scope.accountUuid(), scope.tenantUuid());
    String tenantUuid = scope.tenantUuid().toString();
    if (!storedEvent.outboxStreamKey().equals(streamKey)
        || !storedEvent.requestId().equals(receipt.requestId())
        || !storedEvent.eventId().equals(event.eventId())
        || !storedEvent.eventDigest().equals(event.eventDigest())
        || !event.outboxStreamKey().equals(streamKey)
        || !event.accountId().equals(scope.accountUuid().toString())
        || !event.tenantId().equals(tenantUuid)
        || !event.requestId().equals(receipt.requestId())
        || !event.eventId().equals(expectedEventId(receipt.requestId()))
        || !Long.toString(storedEvent.outboxSequence()).equals(event.outboxSequence())
        || !java.util.Arrays.equals(storedEvent.payload(), event.canonicalJsonUtf8())
        || !"INACTIVE".equals(event.membershipLifecycleState())
        || event.gameplayAdmissionAllowed()
        || !event.callerBoundAuthorityInvalidated()
        || !Map.of(tenantUuid, Long.toString(evidence.membershipVersion()))
            .equals(event.membershipVersion())
        || !Long.toString(evidence.membershipAuthorityGeneration())
            .equals(event.membershipAuthorityGeneration())
        || !Long.toString(evidence.membershipAuthorityGeneration())
            .equals(event.authorityTuple().membershipAuthorityGeneration().get(tenantUuid))
        || event.roles() == null) {
      throw new IllegalStateException("Stored LEFT event differs from its exact receipt and scope");
    }
    return event;
  }

  private String leftAuditPayload(
      CanonicalScope scope,
      MembershipTransitionReceipt receipt,
      Event storedEvent,
      TransitionReceiptEvidence evidence,
      MembershipEvent verifiedEvent) {
    LeftAuditPayload payload =
        new LeftAuditPayload(
            scope.accountUuid().toString(),
            scope.tenantUuid().toString(),
            receipt.requestId(),
            storedEvent.outboxStreamKey(),
            Long.toString(storedEvent.outboxSequence()),
            storedEvent.eventId(),
            storedEvent.eventDigest(),
            Map.of(scope.tenantUuid().toString(), Long.toString(evidence.membershipVersion())),
            Long.toString(evidence.membershipAuthorityGeneration()),
            verifiedEvent == null ? null : verifiedEvent.issuanceFence(),
            receipt.receiptStreamKey(),
            Long.toString(receipt.receiptSequence()),
            receipt.receiptId().toString(),
            receipt.receiptDigest());
    try {
      return AUDIT_JSON.writeValueAsString(payload);
    } catch (Exception exception) {
      throw new IllegalStateException(
          "Account LEFT audit payload could not be serialized", exception);
    }
  }

  private void requireExactAuditEnvelope(
      CanonicalScope scope,
      UUID auditEventId,
      long tenantId,
      String payload,
      AccountAuditEnvelope appended,
      AccountAuditEnvelope readback) {
    String digest = AccountAuditDigest.ofPayload(payload);
    if (scope.legacyTenantId() != tenantId
        || !appended.equals(readback)
        || !auditEventId.equals(readback.auditEventId())
        || !"tenant".equals(readback.scope())
        || !Long.valueOf(tenantId).equals(readback.tenantId())
        || !"account-service".equals(readback.producerService())
        || !LEFT_AUDIT_TYPE.equals(readback.eventType())
        || readback.schemaVersion() != 1
        || readback.payloadDigestVersion() != 1
        || !digest.equals(readback.payloadDigest())
        || (readback.payload() != null && !payload.equals(readback.payload()))) {
      throw new IllegalStateException(
          "Account LEFT audit envelope differs from exact operation evidence");
    }
  }

  private CanonicalScope resolveCanonicalScope(long accountId, long legacyTenantId) {
    Account account =
        accountRepository
            .findById(accountId)
            .orElseThrow(() -> new IllegalStateException("LEFT Account row is absent"));
    if (account.getId() == null
        || account.getId() != accountId
        || account.getAccountUuid() == null
        || account.getAccountUuidProvenance() == null
        || account.getAccountUuidSourceNumericId() == null
        || account.getAccountUuidSourceNumericId() != accountId) {
      throw new IllegalStateException(
          "LEFT Account UUID does not exactly identify its persisted row");
    }
    ApprovedAssociation association = tenantIdentityResolver.resolve(legacyTenantId);
    if (association.legacyTenantId() != legacyTenantId
        || association.canonicalTenantId() == null
        || association.canonicalTenantId().equals(new UUID(0L, 0L))) {
      throw new IllegalStateException(
          "LEFT retained tenant has no exact approved UUID association");
    }
    return new CanonicalScope(
        accountId, legacyTenantId, account.getAccountUuid(), association.canonicalTenantId());
  }

  private static String membershipStreamKey(UUID accountUuid, UUID tenantUuid) {
    return MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX
        + "membership/"
        + accountUuid
        + "/"
        + tenantUuid;
  }

  private static UUID leftAuditId(String requestId) {
    return UUID.nameUUIDFromBytes(
        ("account-membership-left-audit/v1:" + requestId).getBytes(StandardCharsets.UTF_8));
  }

  private static String expectedEventId(String requestId) {
    return UUID.nameUUIDFromBytes(
            (MembershipAuthorityEventV1Codec.SCHEMA_VERSION + ":" + requestId)
                .getBytes(StandardCharsets.UTF_8))
        .toString();
  }

  private static long increment(long value, String field) {
    try {
      return Math.addExact(value, 1L);
    } catch (ArithmeticException overflow) {
      throw new IllegalStateException("Account membership " + field + " is exhausted", overflow);
    }
  }

  private static void requireRequestId(String requestId) {
    if (requestId == null
        || requestId.isBlank()
        || requestId.length() > 128
        || requestId.indexOf('=') >= 0
        || requestId.indexOf('\n') >= 0
        || requestId.indexOf('\r') >= 0) {
      throw new IllegalArgumentException("Stable Account membership request ID is required");
    }
  }

  private record CanonicalScope(
      long accountId, long legacyTenantId, UUID accountUuid, UUID tenantUuid) {}

  private record LeftAuditPayload(
      String accountUuid,
      String tenantUuid,
      String requestId,
      String eventStreamKey,
      String eventSequence,
      String eventId,
      String eventDigest,
      Map<String, String> membershipVersion,
      String membershipAuthorityGeneration,
      String issuanceFence,
      String receiptStreamKey,
      String receiptSequence,
      String receiptId,
      String receiptDigest) {}
}
