package net.firedevops.firemud.accountservice.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.client.StripeHostingSubscriptionObserver;
import net.firedevops.firemud.accountservice.dto.AccountAuditDigest;
import net.firedevops.firemud.accountservice.dto.AccountAuditEnvelope;
import net.firedevops.firemud.accountservice.dto.AccountAuditTenantIdentity;
import net.firedevops.firemud.accountservice.dto.AccountJoinDigest;
import net.firedevops.firemud.accountservice.dto.CanonicalJoinScopeV2;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.entity.AccountTenantMembership;
import net.firedevops.firemud.accountservice.repository.AccountAuditOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Checkpoint;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Event;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository.ReadFence;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository.CanonicalJoinOperationConflictException;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository.CanonicalJoinOperationEvidence;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository.CanonicalJoinTerminalProof;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.PairAuthority;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.TenantProvenanceKind;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.VerifiedTenantProvenance;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository.RoleSnapshot;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.MembershipEvent;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Composes canonical JOIN membership, authority event/pair, and Account-local terminal receipts in
 * one caller-owned transaction. The exact first-JOIN primitive remains separately guarded; the
 * existing-membership entry point retains ACTIVE snapshots or restores INACTIVE membership.
 *
 * <p>This is an owner-local persistence primitive, not a caller authenticator, target-currentness
 * check, public JOIN entry point, cross-service commit gate, or Logging receiver receipt.
 */
@Service
public class AccountCanonicalFirstJoinTerminalCoordinator {
  private static final String JOINED_EVENT_TYPE = "ACCOUNT_JOINED_PUBLIC_PRODUCTION";
  private static final String ACCOUNT_PRODUCER = "account-service";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final AccountRepository accountRepository;
  private final AccountJoinOperationRepository joinOperationRepository;
  private final AccountTenantMembershipRepository membershipRepository;
  private final AccountTenantMembershipRoleSnapshotRepository roleSnapshotRepository;
  private final AccountAuthorityOutboxRepository authorityOutboxRepository;
  private final AccountMembershipPairAuthorityRepository pairAuthorityRepository;
  private final AccountAuditOutboxRepository auditOutboxRepository;
  private final AccountMembershipAuthorityEventProducer eventProducer;

  @SuppressFBWarnings(
      value = {"CT_CONSTRUCTOR_THROW", "EI_EXPOSE_REP2"},
      justification =
          "Keep injected transaction collaborators private and preserve their preconditions; "
              + "Spring must proxy this non-final service.")
  public AccountCanonicalFirstJoinTerminalCoordinator(
      AccountRepository accountRepository,
      AccountJoinOperationRepository joinOperationRepository,
      AccountTenantMembershipRepository membershipRepository,
      AccountTenantMembershipRoleSnapshotRepository roleSnapshotRepository,
      AccountAuthorityOutboxRepository authorityOutboxRepository,
      AccountMembershipPairAuthorityRepository pairAuthorityRepository,
      AccountAuditOutboxRepository auditOutboxRepository,
      AccountMembershipAuthorityEventProducer eventProducer) {
    this.accountRepository = Objects.requireNonNull(accountRepository);
    this.joinOperationRepository = Objects.requireNonNull(joinOperationRepository);
    this.membershipRepository = Objects.requireNonNull(membershipRepository);
    this.roleSnapshotRepository = Objects.requireNonNull(roleSnapshotRepository);
    this.authorityOutboxRepository = Objects.requireNonNull(authorityOutboxRepository);
    this.pairAuthorityRepository = Objects.requireNonNull(pairAuthorityRepository);
    this.auditOutboxRepository = Objects.requireNonNull(auditOutboxRepository);
    this.eventProducer = Objects.requireNonNull(eventProducer);
  }

  /**
   * Commits or exactly replays a first public-production JOIN receipt inside the caller's writable
   * Account transaction. A PENDING operation always stages fresh membership and its role header in
   * this transaction before publishing; an already-positive member is only accepted for an
   * already-COMMITTED exact replay.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public CanonicalJoinTerminalProof commitCanonicalFirstJoin(
      CanonicalJoinScopeV2 scope, String requestId, String verifiedCallerBinding) {
    return commitCanonicalFirstJoin(scope, requestId, verifiedCallerBinding, null, null, null);
  }

  /**
   * Owner-local paid first JOIN, using the actual fence prepared in an earlier source transaction
   * and sealed SDK observation obtained outside SQL. The final paid reread, membership/roles,
   * authority event/pair, audit and terminal receipt share this caller-owned transaction.
   * Exceptions must escape that transaction so every staged write rolls back. This supplies neither
   * caller authentication nor a World hold; default Spring composition and the explicit demo path
   * are unchanged.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public CanonicalJoinTerminalProof commitPaidCanonicalFirstJoin(
      CanonicalJoinScopeV2 scope,
      String requestId,
      String verifiedCallerBinding,
      AccountHostingSubscriptionLifecycleRepository hosting,
      ReadFence fence,
      StripeHostingSubscriptionObserver.Result observed) {
    Objects.requireNonNull(hosting, "Actual paid hosting source required");
    Objects.requireNonNull(fence, "Original paid read fence required");
    Objects.requireNonNull(observed, "Original sealed SDK result required");
    return commitCanonicalFirstJoin(
        scope, requestId, verifiedCallerBinding, hosting, fence, observed);
  }

  /**
   * Applies the accepted existing-membership JOIN lifecycle without reusing the first-row writer.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public CanonicalJoinOperationEvidence commitCanonicalJoin(
      CanonicalJoinScopeV2 scope,
      String requestId,
      String callerBinding,
      AccountHostingSubscriptionLifecycleRepository hosting,
      ReadFence fence,
      StripeHostingSubscriptionObserver.Result observed) {
    requireWritableOwnerTransaction();
    Objects.requireNonNull(scope, "canonical JOIN scope is required");
    requireBoundedText(requestId, "canonical JOIN request ID");
    requireBoundedText(callerBinding, "verified caller binding");
    Account account = accountRepository.findByAccountUuid(scope.accountId()).orElseThrow();
    requireAccountIdentity(account, scope.accountId());
    joinOperationRepository.lockAccount(account.getId());
    CanonicalJoinOperationEvidence operation =
        joinOperationRepository.findCanonicalEvidenceForUpdateByRequestId(requestId).orElseThrow();
    requireExactOperation(operation, account.getId(), scope, requestId, callerBinding);
    // Definitive FAILED receipts are replayed by the adapter before policy evaluation.
    if ("COMMITTED".equals(operation.status())) {
      return operation;
    }
    if (!"PENDING".equals(operation.status())) {
      throw new CanonicalJoinOperationConflictException("Canonical JOIN intent conflicts");
    }
    AccountTenantMembership membership =
        membershipRepository
            .findFreshMembershipForUpdate(scope.accountId(), scope.tenantId())
            .orElse(null);
    if (membership == null) {
      if (hosting == null) commitCanonicalFirstJoin(scope, requestId, callerBinding);
      else commitPaidCanonicalFirstJoin(scope, requestId, callerBinding, hosting, fence, observed);
      return joinOperationRepository
          .findCanonicalEvidenceForUpdateByRequestId(requestId)
          .orElseThrow();
    }
    eventProducer.requireCurrentCanonicalJoinPolicy(
        scope,
        operation,
        !"ACTIVE".equals(membership.getLifecycleState()),
        hosting,
        fence,
        observed);
    VerifiedTenantProvenance provenance = operation.scopeEvidence().tenantProvenance();
    RoleSnapshot roles =
        roleSnapshotRepository
            .findForCanonicalUpdate(
                scope.accountId(),
                scope.tenantId(),
                provenance,
                membership.getId(),
                membership.getMembershipVersion())
            .orElseThrow();
    PairAuthority previous =
        pairAuthorityRepository.readForUpdate(scope.accountId(), scope.tenantId()).orElseThrow();
    String outcome;
    Checkpoint checkpoint;
    if ("ACTIVE".equals(membership.getLifecycleState())) {
      outcome = "ALREADY_ACTIVE";
      checkpoint =
          authorityOutboxRepository
              .readCheckpoint(
                  MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX
                      + "membership/"
                      + scope.accountId()
                      + "/"
                      + scope.tenantId())
              .orElseThrow();
    } else if ("INACTIVE".equals(membership.getLifecycleState())) {
      if (!Boolean.TRUE.equals(operation.allowPublicJoin())) {
        throw new IllegalStateException("Restore requires current positive public JOIN policy");
      }
      membership =
          membershipRepository.restoreFreshMembershipForJoin(
              scope.accountId(), scope.tenantId(), membership);
      // Public enrollment grants only player authority, never retained elevated tenant roles.
      List<String> restoredRoles = List.of("player");
      roles =
          roleSnapshotRepository.replaceCanonical(
              membership,
              scope.accountId(),
              scope.tenantId(),
              provenance,
              membership.getMembershipVersion(),
              restoredRoles);
      checkpoint =
          eventProducer.publishCanonicalRestoredMembershipChange(
              scope, requestId, callerBinding, previous, hosting, fence, observed);
      outcome = "JOINED";
    } else {
      throw new IllegalStateException("Membership lifecycle does not allow JOIN");
    }
    FirstJoinEvidence current = readCurrentJoinEvidence(scope, membership, roles, checkpoint);
    if ("ALREADY_ACTIVE".equals(outcome) && requestId.equals(current.event().requestId())) {
      throw new IllegalStateException("Pending JOIN cannot adopt its precommitted transition");
    }
    if ("JOINED".equals(outcome) && !requestId.equals(current.event().requestId())) {
      throw new IllegalStateException("Transition JOIN event does not belong to this request");
    }
    final CanonicalJoinTerminalProof proof;
    if ("ALREADY_ACTIVE".equals(outcome)) {
      proof =
          new CanonicalJoinTerminalProof(
              checkpoint.outboxStreamKey(),
              checkpoint.outboxSequence(),
              checkpoint.sourceEventId(),
              checkpoint.sourceEventDigest(),
              null,
              null,
              null,
              operation.entitlementVersion(),
              membership.getId(),
              membership.getMembershipVersion(),
              membership.getMembershipAuthorityGeneration());
    } else {
      String payload =
          canonicalCurrentAuditPayload(scope, requestId, operation, current, checkpoint);
      UUID auditId = auditEventIdFor(requestId);
      AccountAuditEnvelope appended =
          auditOutboxRepository.appendCanonicalTenant(
              auditId, scope.tenantId().toString(), JOINED_EVENT_TYPE, payload);
      AccountAuditEnvelope audit =
          auditOutboxRepository.findExactCanonicalTenantEnvelopeForUpdate(appended).orElseThrow();
      requireExactAudit(audit, scope, auditId, payload, operation.terminalProof());
      proof = proof(operation, current, audit);
    }
    return joinOperationRepository.commitCanonicalJoin(
        requestId, scope, callerBinding, outcome, proof);
  }

  private FirstJoinEvidence readCurrentJoinEvidence(
      CanonicalJoinScopeV2 scope,
      AccountTenantMembership membership,
      RoleSnapshot roles,
      Checkpoint checkpoint) {
    PairAuthority pair =
        pairAuthorityRepository.readForUpdate(scope.accountId(), scope.tenantId()).orElseThrow();
    String stream =
        MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX
            + "membership/"
            + scope.accountId()
            + "/"
            + scope.tenantId();
    if (!stream.equals(checkpoint.outboxStreamKey())
        || !pair.membershipExists()
        || !scope.accountId().equals(pair.accountUuid())
        || !scope.tenantId().equals(pair.tenantUuid())
        || pair.membershipVersion() != membership.getMembershipVersion()
        || pair.membershipAuthorityGeneration() != membership.getMembershipAuthorityGeneration()
        || pair.eventSequence() != checkpoint.outboxSequence()
        || !pair.eventId().equals(checkpoint.sourceEventId())
        || !pair.eventDigest().equals(checkpoint.sourceEventDigest())
        || roles.snapshotVersion() != membership.getMembershipVersion()
        || roles.membershipId() != membership.getId()
        || !scope.accountId().equals(roles.accountUuid())
        || !scope.tenantId().equals(roles.tenantUuid())
        || !pair.provenance().equals(roles.tenantProvenance())
        || !"ACTIVE".equals(membership.getLifecycleState())) {
      throw new IllegalStateException("Canonical JOIN current membership/pair/roles differ");
    }
    Event event =
        authorityOutboxRepository.findEvent(stream, checkpoint.outboxSequence()).orElseThrow();
    MembershipEvent decoded =
        MembershipAuthorityEventV1Codec.verify(new String(event.payload(), StandardCharsets.UTF_8));
    if (!Arrays.equals(event.payload(), decoded.canonicalJsonUtf8())
        || !checkpoint.sourceEventId().equals(event.eventId())
        || !checkpoint.sourceEventDigest().equals(event.eventDigest())
        || !event.eventId().equals(decoded.eventId())
        || !event.eventDigest().equals(decoded.eventDigest())
        || !scope.accountId().toString().equals(decoded.accountId())
        || !scope.tenantId().toString().equals(decoded.tenantId())
        || !Map.of(scope.tenantId().toString(), Long.toString(membership.getMembershipVersion()))
            .equals(decoded.membershipVersion())
        || !Long.toString(membership.getMembershipAuthorityGeneration())
            .equals(decoded.membershipAuthorityGeneration())
        || !roles.roles().equals(decoded.roles())
        || !"ACTIVE".equals(decoded.membershipLifecycleState())
        || decoded.gameplayAdmissionAllowed() != membership.isGameplayAdmissionAllowed()) {
      throw new IllegalStateException("Canonical JOIN current event contradicts owner snapshot");
    }
    return new FirstJoinEvidence(membership, event);
  }

  private static String canonicalCurrentAuditPayload(
      CanonicalJoinScopeV2 scope,
      String requestId,
      CanonicalJoinOperationEvidence operation,
      FirstJoinEvidence current,
      Checkpoint checkpoint) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("accountId", scope.accountId().toString());
    payload.put("tenantId", scope.tenantId().toString());
    payload.put("requestId", requestId);
    payload.put("worldSlug", scope.worldSlug());
    payload.put("realmSlug", scope.realmSlug());
    payload.put("membershipVersion", current.membership().getMembershipVersion());
    payload.put(
        "membershipAuthorityGeneration", current.membership().getMembershipAuthorityGeneration());
    payload.put("membershipEventStreamKey", checkpoint.outboxStreamKey());
    payload.put("membershipEventSequence", checkpoint.outboxSequence());
    payload.put("membershipEventId", checkpoint.sourceEventId());
    payload.put("membershipEventDigest", checkpoint.sourceEventDigest());
    payload.put("joinIntentDigest", operation.intentDigest());
    payload.put("joinRequestDigest", operation.requestDigest());
    payload.put("allowPublicJoin", true);
    payload.put("entitlementVersion", operation.entitlementVersion());
    try {
      return new String(
          Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(payload)),
          StandardCharsets.UTF_8);
    } catch (IOException failure) {
      throw new IllegalStateException("Canonical JOIN audit cannot be encoded", failure);
    }
  }

  private CanonicalJoinTerminalProof commitCanonicalFirstJoin(
      CanonicalJoinScopeV2 scope,
      String requestId,
      String verifiedCallerBinding,
      AccountHostingSubscriptionLifecycleRepository hosting,
      ReadFence fence,
      StripeHostingSubscriptionObserver.Result observed) {
    requireWritableOwnerTransaction();
    Objects.requireNonNull(scope, "canonical JOIN scope is required");
    requireBoundedText(requestId, "canonical JOIN request ID");
    requireBoundedText(verifiedCallerBinding, "verified caller binding");

    Account account =
        accountRepository
            .findByAccountUuid(scope.accountId())
            .orElseThrow(
                () ->
                    new CanonicalJoinOperationConflictException("Canonical JOIN intent conflicts"));
    requireAccountIdentity(account, scope.accountId());
    joinOperationRepository.lockAccount(account.getId());
    CanonicalJoinOperationEvidence operation =
        joinOperationRepository
            .findCanonicalEvidenceForUpdateByRequestId(requestId)
            .orElseThrow(
                () ->
                    new CanonicalJoinOperationConflictException("Canonical JOIN intent conflicts"));
    requireExactOperation(operation, account.getId(), scope, requestId, verifiedCallerBinding);
    if (!Boolean.TRUE.equals(operation.allowPublicJoin())) {
      throw new CanonicalJoinOperationConflictException(
          "First JOIN requires available public policy");
    }

    final Checkpoint checkpoint;
    if ("PENDING".equals(operation.status())) {
      VerifiedTenantProvenance provenance = operation.scopeEvidence().tenantProvenance();
      if (!Boolean.TRUE.equals(operation.allowPublicJoin())
          || !"AVAILABLE".equals(operation.entitlementAuthorityAvailability())
          || operation.entitlementVersion() == null
          || operation.entitlementVersion() <= 0L) {
        throw new CanonicalJoinOperationConflictException(
            "Canonical JOIN operation has no available positive public-join policy");
      }

      // This must be the first persisted member write. A PENDING journal paired with an already
      // stored member is an old split commit, not authority for an audit-only terminal success.
      AccountTenantMembership staged =
          membershipRepository.createFreshMembershipForJoin(scope.accountId(), scope.tenantId());
      RoleSnapshot stagedRoles =
          roleSnapshotRepository.replaceCanonical(
              staged, scope.accountId(), scope.tenantId(), provenance, 2L, List.of("player"));
      requireExactRoleSnapshot(staged, provenance, scope, stagedRoles);

      checkpoint =
          hosting == null
              ? eventProducer.publishCanonicalFirstJoinMembershipChange(
                  scope, requestId, verifiedCallerBinding)
              : eventProducer.publishPaidCanonicalFirstJoinMembershipChange(
                  scope, requestId, verifiedCallerBinding, hosting, fence, observed);
    } else if ("COMMITTED".equals(operation.status())
        && "JOINED".equals(operation.outcome())
        && operation.terminalProof() != null) {
      // The producer's positive-state path is read-only and validates the stored event/pair tuple.
      checkpoint =
          eventProducer.publishCanonicalFirstJoinMembershipChange(
              scope, requestId, verifiedCallerBinding);
    } else {
      throw new CanonicalJoinOperationConflictException("Canonical JOIN intent conflicts");
    }

    FirstJoinEvidence current = readExactPositiveEvidence(scope, requestId, operation, checkpoint);
    String payload =
        canonicalAuditPayload(
            scope,
            requestId,
            operation.intentDigest(),
            operation.requestDigest(),
            operation.entitlementVersion(),
            checkpoint,
            current.event());
    UUID auditEventId = auditEventIdFor(requestId);
    if ("PENDING".equals(operation.status())) {
      AccountAuditEnvelope appended =
          auditOutboxRepository.appendCanonicalTenant(
              auditEventId, scope.tenantId().toString(), JOINED_EVENT_TYPE, payload);
      AccountAuditEnvelope audit =
          auditOutboxRepository
              .findExactCanonicalTenantEnvelopeForUpdate(appended)
              .orElseThrow(
                  () -> new IllegalStateException("Canonical JOIN audit readback is absent"));
      requireExactAudit(audit, scope, auditEventId, payload, null);
      CanonicalJoinTerminalProof proof = proof(operation, current, audit);
      CanonicalJoinOperationEvidence committed =
          joinOperationRepository.commitCanonicalFirstJoin(
              requestId, scope, verifiedCallerBinding, proof);
      if (!"COMMITTED".equals(committed.status())
          || !Objects.equals(committed.terminalProof(), proof)) {
        throw new IllegalStateException("Canonical JOIN terminal receipt readback differs");
      }
      return committed.terminalProof();
    }

    CanonicalJoinTerminalProof priorProof = operation.terminalProof();
    AccountAuditEnvelope audit =
        auditOutboxRepository
            .findCanonicalTenantEnvelopeForUpdate(auditEventId)
            .orElseThrow(
                () -> new IllegalStateException("Committed canonical JOIN audit is absent"));
    requireExactAudit(audit, scope, auditEventId, payload, priorProof);
    CanonicalJoinTerminalProof replayProof = proof(operation, current, audit);
    if (!priorProof.equals(replayProof)) {
      throw new CanonicalJoinOperationConflictException(
          "Canonical JOIN request ID conflicts with its committed receipt");
    }
    CanonicalJoinOperationEvidence replayed =
        joinOperationRepository.commitCanonicalFirstJoin(
            requestId, scope, verifiedCallerBinding, replayProof);
    if (!Objects.equals(replayed.terminalProof(), priorProof)) {
      throw new IllegalStateException("Canonical JOIN committed receipt readback differs");
    }
    return priorProof;
  }

  private FirstJoinEvidence readExactPositiveEvidence(
      CanonicalJoinScopeV2 scope,
      String requestId,
      CanonicalJoinOperationEvidence operation,
      Checkpoint checkpoint) {
    String streamKey =
        MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX
            + "membership/"
            + scope.accountId()
            + "/"
            + scope.tenantId();
    if (!streamKey.equals(checkpoint.outboxStreamKey()) || checkpoint.outboxSequence() != 1L) {
      throw new IllegalStateException("Canonical first-JOIN checkpoint differs from exact scope");
    }
    AccountTenantMembership membership =
        membershipRepository
            .findFreshJoinForPublicationForUpdate(scope.accountId(), scope.tenantId())
            .orElseThrow(
                () -> new IllegalStateException("Canonical first-JOIN membership is absent"));
    VerifiedTenantProvenance provenance = operation.scopeEvidence().tenantProvenance();
    requireExactMembership(membership, scope, operation, provenance);
    RoleSnapshot roles =
        roleSnapshotRepository
            .findForCanonicalUpdate(
                scope.accountId(),
                scope.tenantId(),
                provenance,
                membership.getId(),
                membership.getMembershipVersion())
            .orElseThrow(
                () -> new IllegalStateException("Canonical first-JOIN role header is absent"));
    requireExactRoleSnapshot(membership, provenance, scope, roles);

    Event event =
        authorityOutboxRepository
            .findEvent(streamKey, 1L)
            .orElseThrow(() -> new IllegalStateException("Canonical first-JOIN event is absent"));
    if (!requestId.equals(event.requestId())
        || !streamKey.equals(event.outboxStreamKey())
        || event.outboxSequence() != checkpoint.outboxSequence()
        || !checkpoint.sourceEventId().equals(event.eventId())
        || !checkpoint.sourceEventDigest().equals(event.eventDigest())) {
      throw new IllegalStateException("Canonical first-JOIN event differs from checkpoint");
    }
    MembershipEvent decoded;
    try {
      String eventJson = new String(event.payload(), StandardCharsets.UTF_8);
      decoded = MembershipAuthorityEventV1Codec.verify(eventJson);
      if (!Arrays.equals(event.payload(), decoded.canonicalJsonUtf8())) {
        throw new IllegalStateException("Canonical first-JOIN event bytes are not canonical");
      }
    } catch (RuntimeException invalid) {
      throw new IllegalStateException("Canonical first-JOIN event readback is invalid", invalid);
    }
    if (!scope.accountId().toString().equals(decoded.accountId())
        || !scope.tenantId().toString().equals(decoded.tenantId())
        || !requestId.equals(decoded.requestId())
        || !event.eventId().equals(decoded.eventId())
        || !event.eventDigest().equals(decoded.eventDigest())
        || !roles.roles().equals(decoded.roles())
        || !Map.of(scope.tenantId().toString(), "2").equals(decoded.membershipVersion())
        || !"1".equals(decoded.membershipAuthorityGeneration())) {
      throw new IllegalStateException("Canonical first-JOIN event contradicts owner readback");
    }

    PairAuthority pair =
        pairAuthorityRepository
            .readForUpdate(scope.accountId(), scope.tenantId())
            .orElseThrow(() -> new IllegalStateException("Canonical first-JOIN pair is absent"));
    if (!pair.membershipExists()
        || !scope.accountId().equals(pair.accountUuid())
        || !scope.tenantId().equals(pair.tenantUuid())
        || !provenance.equals(pair.provenance())
        || pair.membershipVersion() != 2L
        || pair.membershipAuthorityGeneration() != 1L
        || pair.eventSequence() < 1L
        || (pair.eventSequence() == 1L
            && (!event.eventId().equals(pair.eventId())
                || !event.eventDigest().equals(pair.eventDigest())))
        || (pair.eventSequence() > 1L && !"COMMITTED".equals(operation.status()))
        || pair.lastTransitionInvalidated()) {
      throw new IllegalStateException("Canonical first-JOIN pair differs from its event");
    }
    return new FirstJoinEvidence(membership, event);
  }

  private static void requireAccountIdentity(Account account, UUID accountUuid) {
    if (account.getId() == null
        || account.getId() <= 0L
        || !accountUuid.equals(account.getAccountUuid())
        || !AccountIdentityProvenance.isAccepted(account.getAccountUuidProvenance())
        || !Objects.equals(account.getAccountUuidSourceNumericId(), account.getId())) {
      throw new CanonicalJoinOperationConflictException("Canonical JOIN intent conflicts");
    }
  }

  private static void requireExactOperation(
      CanonicalJoinOperationEvidence operation,
      long privateAccountId,
      CanonicalJoinScopeV2 scope,
      String requestId,
      String callerBinding) {
    var persisted = operation.scopeEvidence();
    String intentDigest = AccountJoinDigest.intentV2(requestId, scope, callerBinding);
    boolean exactScope =
        operation.privateAccountId() == privateAccountId
            && operation.requestId().equals(requestId)
            && operation.callerBinding().equals(callerBinding)
            && operation.intentDigest().equals(intentDigest)
            && persisted.accountUuid().equals(scope.accountId())
            && persisted.tenantUuid().equals(scope.tenantId())
            && persisted
                .scopeTokenHash()
                .equals(AccountJoinDigest.tokenHash(scope.connectScopeId()))
            && persisted.realmId().equals(scope.realmId())
            && persisted.tenantSlug().equals(scope.tenantSlug())
            && persisted.worldSlug().equals(scope.worldSlug())
            && persisted.realmSlug().equals(scope.realmSlug())
            && persisted.playableStateNamespaceUuid().equals(scope.playableStateNamespaceId())
            && persisted.playableStateScope().equals(scope.playableStateScope())
            && persisted.gameInstanceUuid().equals(scope.gameInstanceId())
            && persisted.catalogRevision() == scope.catalogRevision()
            && persisted.pointerVersion() == scope.pointerVersion()
            && persisted.evaluatedAt().equals(scope.evaluatedAt())
            && persisted.connectScopeExpiresAt().equals(scope.connectScopeExpiresAt());
    if (!exactScope || operation.callerBoundAuthorityInvalidated()) {
      throw new CanonicalJoinOperationConflictException("Canonical JOIN intent conflicts");
    }
    if (!"AVAILABLE".equals(operation.entitlementAuthorityAvailability())
        || operation.allowPublicJoin() == null
        || operation.entitlementVersion() == null
        || operation.entitlementVersion() <= 0L
        || !Integer.valueOf(2).equals(operation.requestDigestVersion())
        || !"AVAILABLE".equals(operation.lastAttemptAuthorityAvailability())
        || operation.lastAttemptFailureCode() != null) {
      throw new CanonicalJoinOperationConflictException(
          "Canonical JOIN operation has no available positive public-join policy");
    }
    String requestDigest =
        AccountJoinDigest.requestV2(
            scope,
            callerBinding,
            AccountJoinDigest.EntitlementAvailabilityV2.AVAILABLE,
            operation.allowPublicJoin(),
            operation.entitlementVersion());
    if (!requestDigest.equals(operation.requestDigest())) {
      throw new CanonicalJoinOperationConflictException("Canonical JOIN policy conflicts");
    }
  }

  private static void requireExactMembership(
      AccountTenantMembership membership,
      CanonicalJoinScopeV2 scope,
      CanonicalJoinOperationEvidence operation,
      VerifiedTenantProvenance provenance) {
    if (membership.getId() == null
        || membership.getId() <= 0L
        || membership.getAccount() == null
        || !Objects.equals(membership.getAccount().getId(), operation.privateAccountId())
        || !scope.accountId().equals(membership.getAccount().getAccountUuid())
        || !scope.tenantId().equals(membership.getTenantUuid())
        || membership.getTenantId() != null
        || !TenantProvenanceKind.FRESH_GAME_DESIGN
            .name()
            .equals(membership.getTenantProvenanceKind())
        || !Objects.equals(membership.getTenantSourceOperationId(), provenance.sourceOperationId())
        || !Objects.equals(membership.getTenantProvenanceDigest(), provenance.digest())
        || membership.getMembershipVersion() != 2L
        || membership.getMembershipAuthorityGeneration() != 1L
        || !"ACTIVE".equals(membership.getLifecycleState())
        || !membership.isGameplayAdmissionAllowed()
        || !"EXPLICIT_JOIN".equals(membership.getAuthorityProvenance())) {
      throw new IllegalStateException("Canonical first-JOIN membership differs from source");
    }
  }

  private static void requireExactRoleSnapshot(
      AccountTenantMembership membership,
      VerifiedTenantProvenance provenance,
      CanonicalJoinScopeV2 scope,
      RoleSnapshot roles) {
    List<String> canonical;
    try {
      canonical =
          AccountTenantMembershipRoleSnapshotRepository.requireCanonicalRoleSet(roles.roles());
    } catch (RuntimeException invalid) {
      throw new IllegalStateException("Canonical first-JOIN roles are invalid", invalid);
    }
    if (roles.accountId() != membership.getAccount().getId()
        || roles.tenantId() != null
        || roles.membershipId() != membership.getId()
        || roles.snapshotVersion() != 2L
        || !scope.accountId().equals(roles.accountUuid())
        || !scope.tenantId().equals(roles.tenantUuid())
        || !provenance.equals(roles.tenantProvenance())
        || !canonical.contains("player")) {
      throw new IllegalStateException("Canonical first-JOIN role snapshot differs from source");
    }
  }

  static String canonicalAuditPayload(
      CanonicalJoinScopeV2 scope,
      String requestId,
      String intentDigest,
      String requestDigest,
      long entitlementVersion,
      Checkpoint checkpoint,
      Event event) {
    String expectedStream =
        MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX
            + "membership/"
            + scope.accountId()
            + "/"
            + scope.tenantId();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("accountId", scope.accountId().toString());
    payload.put("tenantId", scope.tenantId().toString());
    payload.put("requestId", requestId);
    payload.put("worldSlug", scope.worldSlug());
    payload.put("realmSlug", scope.realmSlug());
    payload.put("membershipVersion", 2);
    payload.put("membershipAuthorityGeneration", 1);
    payload.put("membershipEventStreamKey", checkpoint.outboxStreamKey());
    payload.put("membershipEventSequence", checkpoint.outboxSequence());
    payload.put("membershipEventId", checkpoint.sourceEventId());
    payload.put("membershipEventDigest", checkpoint.sourceEventDigest());
    payload.put("joinIntentDigest", intentDigest);
    payload.put("joinRequestDigest", requestDigest);
    payload.put("allowPublicJoin", true);
    payload.put("entitlementVersion", entitlementVersion);
    if (checkpoint.outboxSequence() != 1L
        || !expectedStream.equals(checkpoint.outboxStreamKey())
        || !event.requestId().equals(requestId)
        || !event.eventId().equals(checkpoint.sourceEventId())
        || !event.eventDigest().equals(checkpoint.sourceEventDigest())
        || !event.outboxStreamKey().equals(checkpoint.outboxStreamKey())
        || event.outboxSequence() != checkpoint.outboxSequence()) {
      throw new IllegalArgumentException(
          "Audit preimage does not match the exact event checkpoint");
    }
    try {
      byte[] canonical = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(payload));
      return new String(canonical, StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException(
          "Canonical JOIN audit preimage could not be encoded", exception);
    }
  }

  private static void requireExactAudit(
      AccountAuditEnvelope audit,
      CanonicalJoinScopeV2 scope,
      UUID auditEventId,
      String expectedPayload,
      CanonicalJoinTerminalProof priorProof) {
    if (!auditEventId.equals(audit.auditEventId())
        || !"tenant".equals(audit.scope())
        || audit.tenantIdentityVersion() != AccountAuditTenantIdentity.VERSION_2
        || !scope.tenantId().equals(audit.tenantUuid())
        || !ACCOUNT_PRODUCER.equals(audit.producerService())
        || !JOINED_EVENT_TYPE.equals(audit.eventType())
        || audit.schemaVersion() != 1
        || audit.payloadDigestVersion() != 1
        || !AccountAuditDigest.ofPayload(expectedPayload).equals(audit.payloadDigest())
        || (audit.payload() != null && !expectedPayload.equals(audit.payload()))
        || (priorProof != null
            && (!audit.auditEventId().equals(priorProof.auditEventId())
                || !audit.payloadDigest().equals(priorProof.auditPayloadDigest())
                || !audit.occurredAt().equals(priorProof.auditOccurredAt())))) {
      throw new IllegalStateException("Canonical JOIN audit envelope differs from its receipt");
    }
  }

  private static CanonicalJoinTerminalProof proof(
      CanonicalJoinOperationEvidence operation,
      FirstJoinEvidence current,
      AccountAuditEnvelope audit) {
    return new CanonicalJoinTerminalProof(
        current.event().outboxStreamKey(),
        current.event().outboxSequence(),
        current.event().eventId(),
        current.event().eventDigest(),
        audit.auditEventId(),
        audit.payloadDigest(),
        audit.occurredAt(),
        Objects.requireNonNull(operation.entitlementVersion()),
        Objects.requireNonNull(current.membership().getId()),
        current.membership().getMembershipVersion(),
        current.membership().getMembershipAuthorityGeneration());
  }

  private static UUID auditEventIdFor(String requestId) {
    return UUID.nameUUIDFromBytes(
        ("account-join-audit/v1:" + requestId).getBytes(StandardCharsets.UTF_8));
  }

  private static void requireBoundedText(String value, String label) {
    if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > 128) {
      throw new IllegalArgumentException(label + " must contain 1 to 128 characters");
    }
  }

  private static void requireWritableOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Canonical first-JOIN receipt requires a writable Account owner transaction");
    }
  }

  private record FirstJoinEvidence(AccountTenantMembership membership, Event event) {}
}
