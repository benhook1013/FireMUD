package net.firedevops.firemud.accountservice.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountJoinDigest;
import net.firedevops.firemud.accountservice.dto.AccountJoinDigest.EntitlementAvailabilityV2;
import net.firedevops.firemud.accountservice.dto.CanonicalJoinScopeV2;
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementSnapshot;
import net.firedevops.firemud.accountservice.dto.TenantAuthorityEventV1Codec;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.entity.AccountTenantMembership;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.CompositeSnapshot;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Checkpoint;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Event;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.EventEvidence;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.IssuerAccountSourceSnapshot;
import net.firedevops.firemud.accountservice.repository.AccountDemoTenantEntitlementRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository.CanonicalJoinOperationEvidence;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository.CanonicalJoinTerminalProof;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.PairAuthority;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.PairTransition;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.TenantProvenanceKind;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.VerifiedTenantProvenance;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantAuthorityEventRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantCreationBootstrapOperationRepository.StoredOperation;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository.RoleSnapshot;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.AuthorityTuple;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.MembershipEvent;
import net.firedevops.firemud.common.account.authority.RuntimeMembershipAuthorityEvidenceValidator;
import net.firedevops.firemud.common.account.authority.RuntimeMembershipAuthorityEvidenceValidator.Snapshot;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Composes one canonical fresh JOIN membership with its Account-owned event and pair checkpoint.
 */
@Service
public class AccountMembershipAuthorityEventProducer {
  private static final String ACCOUNT_JWT_ISSUER = "firemud-account-service";
  private static final int MAX_REQUEST_ID_LENGTH = 128;
  private static final int MAX_CALLER_BINDING_LENGTH = 128;
  private static final Comparator<OutboxCheckpointEntry> OUTBOX_CHECKPOINT_ORDER =
      Comparator.comparing(
          OutboxCheckpointEntry::outboxStreamKey,
          AccountMembershipAuthorityEventProducer::compareUnsignedUtf8);

  private final AccountJoinOperationRepository joinOperationRepository;
  private final AccountRepository accountRepository;
  private final AccountMembershipPairAuthorityRepository pairAuthorityRepository;
  private final AccountTenantMembershipRepository membershipRepository;
  private final AccountTenantMembershipRoleSnapshotRepository roleSnapshotRepository;
  private final AccountAuthorityGenerationRepository authorityGenerationRepository;
  private final AccountAuthorityOutboxRepository authorityOutboxRepository;
  private final AccountAuthoritySourceEvidenceRepository sourceEvidenceRepository;
  private final AccountTenantAuthorityEventRepository tenantAuthorityEventRepository;
  private final AccountDemoTenantEntitlementRepository demoTenantEntitlementRepository;

  @SuppressFBWarnings(
      value = {"CT_CONSTRUCTOR_THROW", "EI_EXPOSE_REP2"},
      justification =
          "Keep injected transaction collaborators private and preserve their preconditions; "
              + "Spring must proxy this non-final service.")
  public AccountMembershipAuthorityEventProducer(
      AccountJoinOperationRepository joinOperationRepository,
      AccountRepository accountRepository,
      AccountMembershipPairAuthorityRepository pairAuthorityRepository,
      AccountTenantMembershipRepository membershipRepository,
      AccountTenantMembershipRoleSnapshotRepository roleSnapshotRepository,
      AccountAuthorityGenerationRepository authorityGenerationRepository,
      AccountAuthorityOutboxRepository authorityOutboxRepository,
      AccountAuthoritySourceEvidenceRepository sourceEvidenceRepository,
      AccountTenantAuthorityEventRepository tenantAuthorityEventRepository,
      AccountDemoTenantEntitlementRepository demoTenantEntitlementRepository) {
    this.joinOperationRepository = Objects.requireNonNull(joinOperationRepository);
    this.accountRepository = Objects.requireNonNull(accountRepository);
    this.pairAuthorityRepository = Objects.requireNonNull(pairAuthorityRepository);
    this.membershipRepository = Objects.requireNonNull(membershipRepository);
    this.roleSnapshotRepository = Objects.requireNonNull(roleSnapshotRepository);
    this.authorityGenerationRepository = Objects.requireNonNull(authorityGenerationRepository);
    this.authorityOutboxRepository = Objects.requireNonNull(authorityOutboxRepository);
    this.sourceEvidenceRepository = Objects.requireNonNull(sourceEvidenceRepository);
    this.tenantAuthorityEventRepository = Objects.requireNonNull(tenantAuthorityEventRepository);
    this.demoTenantEntitlementRepository = Objects.requireNonNull(demoTenantEntitlementRepository);
  }

  /**
   * Publishes or exactly replays one first-JOIN membership event from Account-owned durable state.
   *
   * <p>The scope, request ID, and caller binding select immutable operation evidence; they do not
   * supply event authority. This method requires the caller's writable Account transaction and
   * locks the canonical Account/membership/provenance before the operation, role, generation, pair,
   * and outbox evidence. It is intentionally not wired to the public JOIN RPC in this slice.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Checkpoint publishCanonicalFirstJoinMembershipChange(
      CanonicalJoinScopeV2 scope, String requestId, String callerBinding) {
    requireWritableOwnerTransaction();
    Objects.requireNonNull(scope, "canonical JOIN scope is required");
    requireBoundedText(requestId, "canonical JOIN request ID", MAX_REQUEST_ID_LENGTH);
    requireBoundedText(callerBinding, "verified JOIN caller binding", MAX_CALLER_BINDING_LENGTH);

    UUID accountUuid = scope.accountId();
    UUID tenantUuid = scope.tenantId();
    var account =
        accountRepository
            .findByAccountUuid(accountUuid)
            .orElseThrow(() -> new IllegalStateException("Canonical Account identity is absent"));
    if (account.getId() == null
        || account.getId() <= 0L
        || !accountUuid.equals(account.getAccountUuid())
        || !AccountIdentityProvenance.isAccepted(account.getAccountUuidProvenance())
        || !Objects.equals(account.getAccountUuidSourceNumericId(), account.getId())) {
      throw new IllegalStateException("Canonical Account owner identity is contradictory");
    }
    // The operation repository exposes only a retained private row key. Lock its actual Account
    // owner first, then lock/read the operation before resolving any membership source evidence.
    joinOperationRepository.lockAccount(account.getId());
    CanonicalJoinOperationEvidence operation =
        joinOperationRepository
            .findCanonicalEvidenceForUpdateByRequestId(requestId)
            .orElseThrow(
                () -> new IllegalStateException("Canonical first-JOIN V2 operation is absent"));
    requireOperationMatchesRequest(scope, requestId, callerBinding, account.getId(), operation);
    if (operation.privateAccountId() != account.getId()) {
      throw new IllegalStateException("Canonical JOIN operation belongs to another Account row");
    }

    AccountTenantMembership membership =
        membershipRepository
            .findFreshJoinForPublicationForUpdate(accountUuid, tenantUuid)
            .orElseThrow(
                () -> new IllegalStateException("Canonical first-JOIN membership row is absent"));
    VerifiedTenantProvenance provenance = requireMembershipIdentity(scope, membership);
    if (membership.getAccount().getId() != operation.privateAccountId()
        || !provenance.equals(operation.scopeEvidence().tenantProvenance())) {
      throw new IllegalStateException(
          "Canonical JOIN operation differs from its locked Account or tenant provenance");
    }

    RoleSnapshot roles =
        roleSnapshotRepository
            .findForCanonicalUpdate(
                accountUuid,
                tenantUuid,
                provenance,
                Objects.requireNonNull(membership.getId()),
                membership.getMembershipVersion())
            .orElseThrow(
                () -> new IllegalStateException("Canonical first-JOIN role snapshot is absent"));
    List<String> exactRoles = requireFirstJoinRoleSnapshot(scope, membership, provenance, roles);

    PairAuthority pair =
        pairAuthorityRepository
            .readForUpdate(accountUuid, tenantUuid)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Canonical first JOIN has no committed never-joined pair baseline"));
    requireExactPairScope(pair, accountUuid, tenantUuid, provenance);

    String streamKey = membershipStreamKey(accountUuid, tenantUuid);
    if (isPendingBaseline(pair)) {
      if (!"PENDING".equals(operation.status())) {
        throw new IllegalStateException(
            "Committed canonical first JOIN has no positive pair/event readback");
      }
      CompositeSnapshot authority =
          authorityGenerationRepository.readCompositeSnapshot(
              ACCOUNT_JWT_ISSUER, accountUuid, List.of(tenantUuid), List.of(tenantUuid));
      ScopeState tenant = only(authority.tenants(), "tenant");
      IssuerAccountSourceSnapshot sourceSnapshot =
          sourceEvidenceRepository.readCurrentIssuerAccountSources(ACCOUNT_JWT_ISSUER, accountUuid);
      TenantAuthorityEventV1Codec.Event tenantSourceEvent =
          readCurrentTenantSourceEvent(tenantUuid, provenance, tenant);
      requireCurrentAuthority(
          authority,
          sourceSnapshot,
          account.getId(),
          accountUuid,
          tenantUuid,
          membership,
          provenance,
          tenantSourceEvent);
      DemoTenantEntitlementSnapshot entitlementSnapshot =
          demoTenantEntitlementRepository.readCurrent(tenantUuid);
      requireCurrentJoinEntitlement(operation, entitlementSnapshot, tenantSourceEvent, tenantUuid);
      if (authorityOutboxRepository.readCheckpoint(streamKey).isPresent()) {
        throw new IllegalStateException(
            "Canonical first JOIN cannot publish over retained membership-event history");
      }
      Checkpoint checkpoint =
          appendFirstJoinEvent(
              streamKey,
              requestId,
              scope,
              membership,
              exactRoles,
              authority,
              sourceSnapshot,
              tenantSourceEvent);
      PairAuthority committed =
          pairAuthorityRepository.commitTransition(
              pair,
              new PairTransition(
                  true,
                  checkpoint.outboxSequence(),
                  checkpoint.sourceEventId(),
                  checkpoint.sourceEventDigest(),
                  false));
      requireCommittedFirstJoinPair(committed, checkpoint, provenance, accountUuid, tenantUuid);
      return checkpoint;
    }

    requirePositiveFirstJoinPair(pair, membership);
    if (!"COMMITTED".equals(operation.status())
        || !"JOINED".equals(operation.outcome())
        || operation.terminalProof() == null) {
      throw new IllegalStateException("Positive first-JOIN pair has no committed operation proof");
    }
    return readExactCommittedFirstJoin(
        streamKey, requestId, scope, membership, exactRoles, pair, operation);
  }

  private Checkpoint appendFirstJoinEvent(
      String streamKey,
      String requestId,
      CanonicalJoinScopeV2 scope,
      AccountTenantMembership membership,
      List<String> roles,
      CompositeSnapshot authority,
      IssuerAccountSourceSnapshot sourceSnapshot,
      TenantAuthorityEventV1Codec.Event tenantSourceEvent) {
    String eventId = eventIdForRequest(requestId);
    MembershipEvent[] candidate = new MembershipEvent[1];
    Event appended =
        authorityOutboxRepository.append(
            streamKey,
            requestId,
            sequence -> {
              if (sequence != 1L) {
                throw new IllegalStateException(
                    "Canonical first JOIN requires the exact first membership-event sequence");
              }
              MembershipEvent event =
                  MembershipAuthorityEventV1Codec.seal(
                      membershipEventPreimage(
                          scope,
                          membership,
                          roles,
                          authority,
                          sourceSnapshot,
                          tenantSourceEvent,
                          eventId,
                          requestId,
                          sequence));
              candidate[0] = event;
              return new EventEvidence(
                  event.eventId(), event.eventDigest(), event.canonicalJsonUtf8());
            });
    MembershipEvent expected =
        Objects.requireNonNull(candidate[0], "first-JOIN event candidate was not produced");
    requireAppendedEvent(appended, expected, streamKey, requestId);
    Event byRequest =
        authorityOutboxRepository
            .findEvent(streamKey, requestId)
            .orElseThrow(
                () -> new IllegalStateException("Canonical first-JOIN event readback is absent"));
    Event bySequence =
        authorityOutboxRepository
            .findEvent(streamKey, 1L)
            .orElseThrow(
                () ->
                    new IllegalStateException("Canonical first-JOIN sequence-one event is absent"));
    if (!appended.equals(byRequest) || !appended.equals(bySequence)) {
      throw new IllegalStateException("Canonical first-JOIN event readback differs from append");
    }
    MembershipEvent verified = verifyStoredEvent(byRequest, scope, membership, roles, requestId);
    if (!verified.canonicalJson().equals(expected.canonicalJson())) {
      throw new IllegalStateException("Canonical first-JOIN codec readback differs from candidate");
    }
    Checkpoint checkpoint =
        authorityOutboxRepository
            .readCheckpoint(streamKey)
            .orElseThrow(
                () -> new IllegalStateException("Canonical first-JOIN checkpoint is absent"));
    requireCheckpointMatchesEvent(checkpoint, byRequest);
    if (checkpoint.outboxSequence() != 1L) {
      throw new IllegalStateException("Canonical first-JOIN checkpoint is not sequence one");
    }
    return checkpoint;
  }

  private Checkpoint readExactCommittedFirstJoin(
      String streamKey,
      String requestId,
      CanonicalJoinScopeV2 scope,
      AccountTenantMembership membership,
      List<String> roles,
      PairAuthority pair,
      CanonicalJoinOperationEvidence operation) {
    Checkpoint checkpoint =
        authorityOutboxRepository
            .readCheckpoint(streamKey)
            .orElseThrow(
                () -> new IllegalStateException("Committed first-JOIN checkpoint is absent"));
    if (checkpoint.outboxSequence() != 1L) {
      throw new IllegalStateException("Committed first-JOIN stream has unexpected event history");
    }
    Event byRequest =
        authorityOutboxRepository
            .findEvent(streamKey, requestId)
            .orElseThrow(
                () -> new IllegalStateException("Committed first-JOIN request event is absent"));
    Event bySequence =
        authorityOutboxRepository
            .findEvent(streamKey, 1L)
            .orElseThrow(
                () ->
                    new IllegalStateException("Committed first-JOIN sequence-one event is absent"));
    if (!byRequest.equals(bySequence)) {
      throw new IllegalStateException("Committed first-JOIN event identity is contradictory");
    }
    requireCheckpointMatchesEvent(checkpoint, byRequest);
    MembershipEvent verified = verifyStoredEvent(byRequest, scope, membership, roles, requestId);
    CanonicalJoinTerminalProof proof =
        Objects.requireNonNull(
            operation.terminalProof(), "committed JOIN terminal proof is required");
    if (!pair.eventId().equals(verified.eventId())
        || !pair.eventDigest().equals(verified.eventDigest())
        || !proof.eventStreamKey().equals(streamKey)
        || proof.eventSequence() != byRequest.outboxSequence()
        || !proof.eventId().equals(verified.eventId())
        || !proof.eventDigest().equals(verified.eventDigest())
        || proof.membershipId() != Objects.requireNonNull(membership.getId())
        || proof.membershipVersion() != membership.getMembershipVersion()
        || proof.membershipAuthorityGeneration() != membership.getMembershipAuthorityGeneration()) {
      throw new IllegalStateException("Committed first-JOIN payload or pair linkage differs");
    }
    return checkpoint;
  }

  private static Map<String, Object> membershipEventPreimage(
      CanonicalJoinScopeV2 scope,
      AccountTenantMembership membership,
      List<String> roles,
      CompositeSnapshot authority,
      IssuerAccountSourceSnapshot sourceSnapshot,
      TenantAuthorityEventV1Codec.Event tenantSourceEvent,
      String eventId,
      String requestId,
      long sequence) {
    UUID accountUuid = scope.accountId();
    UUID tenantUuid = scope.tenantId();
    String streamKey = membershipStreamKey(accountUuid, tenantUuid);
    ScopeState tenant = only(authority.tenants(), "tenant");
    ScopeState member = only(authority.memberships(), "membership");
    Map<String, Object> authorityTuple = new LinkedHashMap<>();
    authorityTuple.put("issuerAuthGeneration", decimal(authority.issuer().generation()));
    authorityTuple.put("accountAuthorityGeneration", decimal(authority.account().generation()));
    authorityTuple.put(
        "tenantAuthorityGeneration", Map.of(tenantUuid.toString(), decimal(tenant.generation())));
    authorityTuple.put(
        "membershipAuthorityGeneration",
        Map.of(tenantUuid.toString(), decimal(member.generation())));
    authorityTuple.put("privateRealmGrantVersions", List.of());
    sourceSnapshot
        .account()
        .accountSecurityCutoff()
        .ifPresent(
            cutoff ->
                authorityTuple.put(
                    "accountSecurityCutoff",
                    Map.of(
                        "accountAuthorityGeneration", cutoff.accountAuthorityGeneration(),
                        "outboxStreamKey", cutoff.outboxStreamKey(),
                        "outboxSequence", cutoff.outboxSequence())));
    authorityTuple.put(
        "tenantBillingCutoff",
        Map.of(
            tenantUuid.toString(),
            Map.of(
                "tenantAuthorityGeneration",
                decimal(tenantSourceEvent.tenantAuthorityGeneration()),
                "tenantBillingSequence",
                decimal(tenantSourceEvent.tenantBillingSequence()),
                "outboxStreamKey",
                tenantSourceEvent.outboxStreamKey(),
                "outboxSequence",
                decimal(tenantSourceEvent.outboxSequence()))));

    Map<String, Object> event = new LinkedHashMap<>();
    event.put("schemaVersion", MembershipAuthorityEventV1Codec.SCHEMA_VERSION);
    event.put("eventType", MembershipAuthorityEventV1Codec.EVENT_TYPE);
    event.put("eventId", eventId);
    event.put("requestId", requestId);
    event.put("outboxStreamKey", streamKey);
    event.put("outboxSequence", Long.toString(sequence));
    event.put(
        "sourceScope",
        streamKey.substring(MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX.length()));
    event.put("accountId", accountUuid.toString());
    event.put("tenantId", tenantUuid.toString());
    event.put("membershipExists", true);
    event.put("membershipLifecycleState", membership.getLifecycleState());
    event.put(
        "membershipVersion",
        Map.of(tenantUuid.toString(), decimal(membership.getMembershipVersion())));
    event.put(
        "membershipAuthorityGeneration", decimal(membership.getMembershipAuthorityGeneration()));
    event.put("authorityTuple", authorityTuple);
    event.put("issuanceFence", decimal(authority.issuanceFence().value()));
    event.put("roles", roles);
    event.put("gameplayAdmissionAllowed", membership.isGameplayAdmissionAllowed());
    event.put("callerBoundAuthorityInvalidated", false);
    return event;
  }

  private VerifiedTenantProvenance requireMembershipIdentity(
      CanonicalJoinScopeV2 scope, AccountTenantMembership membership) {
    if (membership == null
        || membership.getId() == null
        || membership.getId() <= 0L
        || membership.getAccount() == null
        || membership.getAccount().getId() == null
        || membership.getAccount().getId() <= 0L
        || !scope.accountId().equals(membership.getAccount().getAccountUuid())
        || !AccountIdentityProvenance.isAccepted(membership.getAccount().getAccountUuidProvenance())
        || !Objects.equals(
            membership.getAccount().getAccountUuidSourceNumericId(),
            membership.getAccount().getId())
        || !scope.tenantId().equals(membership.getTenantUuid())
        || membership.getTenantId() != null
        || !TenantProvenanceKind.FRESH_GAME_DESIGN
            .name()
            .equals(membership.getTenantProvenanceKind())
        || membership.getTenantSourceOperationId() == null
        || membership.getTenantProvenanceDigest() == null
        || membership.getMembershipVersion() != 2L
        || membership.getMembershipAuthorityGeneration() != 1L
        || !"ACTIVE".equals(membership.getLifecycleState())
        || !membership.isGameplayAdmissionAllowed()
        || !"EXPLICIT_JOIN".equals(membership.getAuthorityProvenance())) {
      throw new IllegalStateException(
          "Canonical first-JOIN membership differs from its exact fresh Account source");
    }
    return new VerifiedTenantProvenance(
        null,
        TenantProvenanceKind.FRESH_GAME_DESIGN,
        membership.getTenantSourceOperationId(),
        membership.getTenantProvenanceDigest());
  }

  private static void requireOperationMatchesRequest(
      CanonicalJoinScopeV2 scope,
      String requestId,
      String callerBinding,
      long privateAccountId,
      CanonicalJoinOperationEvidence operation) {
    var persisted = operation.scopeEvidence();
    boolean exactScope =
        persisted.scopeTokenHash().equals(AccountJoinDigest.tokenHash(scope.connectScopeId()))
            && persisted.privateAccountId() == privateAccountId
            && "PUBLIC_PRODUCTION".equals(persisted.targetClass())
            && scope.accountId().equals(persisted.accountUuid())
            && scope.tenantId().equals(persisted.tenantUuid())
            && scope.realmId().equals(persisted.realmId())
            && scope.tenantSlug().equals(persisted.tenantSlug())
            && scope.worldSlug().equals(persisted.worldSlug())
            && scope.realmSlug().equals(persisted.realmSlug())
            && scope.playableStateNamespaceId().equals(persisted.playableStateNamespaceUuid())
            && scope.playableStateScope().equals(persisted.playableStateScope())
            && scope.gameInstanceId().equals(persisted.gameInstanceUuid())
            && scope.catalogRevision() == persisted.catalogRevision()
            && scope.pointerVersion() == persisted.pointerVersion()
            && scope.evaluatedAt().equals(persisted.evaluatedAt())
            && scope.connectScopeExpiresAt().equals(persisted.connectScopeExpiresAt())
            && persisted.scopeDigestVersion() == 2
            && AccountJoinDigest.scopeV2(scope).equals(persisted.scopeDigest())
            && persisted.tenantProvenance().kind() == TenantProvenanceKind.FRESH_GAME_DESIGN
            && persisted.tenantProvenance().legacyTenantId() == null;
    String expectedIntent = AccountJoinDigest.intentV2(requestId, scope, callerBinding);
    if (!exactScope
        || !requestId.equals(operation.requestId())
        || !callerBinding.equals(operation.callerBinding())
        || operation.privateAccountId() != privateAccountId
        || operation.operationRepresentationVersion() != 2
        || operation.scopeDigestVersion() != 2
        || operation.intentDigestVersion() != 2
        || !expectedIntent.equals(operation.intentDigest())
        || (!"PENDING".equals(operation.status())
            && !("COMMITTED".equals(operation.status())
                && "JOINED".equals(operation.outcome())
                && operation.terminalProof() != null))
        || !"AVAILABLE".equals(operation.entitlementAuthorityAvailability())
        || !Boolean.TRUE.equals(operation.allowPublicJoin())
        || operation.entitlementVersion() == null
        || operation.entitlementVersion() <= 0L
        || !Integer.valueOf(2).equals(operation.requestDigestVersion())
        || operation.lastAttemptFailureCode() != null
        || !"AVAILABLE".equals(operation.lastAttemptAuthorityAvailability())
        || operation.callerBoundAuthorityInvalidated()) {
      throw new IllegalStateException(
          "Canonical JOIN request, caller, scope, or available policy differs from its persisted operation");
    }
    String expectedRequestDigest =
        AccountJoinDigest.requestV2(
            scope,
            callerBinding,
            EntitlementAvailabilityV2.AVAILABLE,
            operation.allowPublicJoin(),
            operation.entitlementVersion());
    if (!expectedRequestDigest.equals(operation.requestDigest())) {
      throw new IllegalStateException(
          "Canonical JOIN policy digest differs from persisted evidence");
    }
  }

  private static void requireCurrentJoinEntitlement(
      CanonicalJoinOperationEvidence operation,
      DemoTenantEntitlementSnapshot entitlement,
      TenantAuthorityEventV1Codec.Event tenantSourceEvent,
      UUID tenantUuid) {
    if (entitlement == null
        || !tenantUuid.equals(entitlement.canonicalTenantId())
        || entitlement.entitlementVersion() != operation.entitlementVersion()
        || !entitlement.allowPublicJoin()
        || !entitlement.gameplayAvailable()
        || tenantSourceEvent == null
        || !tenantUuid.equals(tenantSourceEvent.tenantId())
        || !entitlement.sourceEvidence().equals(tenantSourceEvent.sourceEvidence())
        || entitlement.tenantAuthorityGeneration() != tenantSourceEvent.tenantAuthorityGeneration()
        || entitlement.tenantAuthoritySourceVersion()
            != tenantSourceEvent.tenantAuthoritySourceVersion()
        || !entitlement.tenantAuthorityOutboxStreamKey().equals(tenantSourceEvent.outboxStreamKey())
        || entitlement.tenantAuthorityOutboxSequence() != tenantSourceEvent.outboxSequence()
        || !entitlement.tenantAuthorityEventId().equals(tenantSourceEvent.eventId())
        || !entitlement.tenantAuthorityEventDigest().equals(tenantSourceEvent.eventDigest())
        || !entitlement.outboxStreamKey().equals(tenantSourceEvent.tenantBillingStreamKey())
        || entitlement.tenantBillingSequence() != tenantSourceEvent.tenantBillingSequence()
        || !entitlement.eventId().equals(tenantSourceEvent.tenantBillingEventId())
        || !entitlement.eventDigest().equals(tenantSourceEvent.tenantBillingEventDigest())) {
      throw new IllegalStateException(
          "Canonical first JOIN requires exact current public-join entitlement evidence");
    }
  }

  private static List<String> requireFirstJoinRoleSnapshot(
      CanonicalJoinScopeV2 scope,
      AccountTenantMembership membership,
      VerifiedTenantProvenance provenance,
      RoleSnapshot roles) {
    List<String> exactRoles;
    try {
      exactRoles =
          AccountTenantMembershipRoleSnapshotRepository.requireCanonicalRoleSet(roles.roles());
    } catch (RuntimeException malformed) {
      throw new IllegalStateException("Canonical JOIN role snapshot is not canonical", malformed);
    }
    if (roles.accountId() != membership.getAccount().getId()
        || roles.tenantId() != null
        || roles.membershipId() != membership.getId()
        || roles.snapshotVersion() != 2L
        || !scope.accountId().equals(roles.accountUuid())
        || !scope.tenantId().equals(roles.tenantUuid())
        || !provenance.equals(roles.tenantProvenance())
        || !exactRoles.contains("player")) {
      throw new IllegalStateException(
          "Canonical JOIN role snapshot differs from its exact membership source or lacks player role");
    }
    return exactRoles;
  }

  private static void requireExactPairScope(
      PairAuthority pair, UUID accountUuid, UUID tenantUuid, VerifiedTenantProvenance provenance) {
    if (!accountUuid.equals(pair.accountUuid())
        || !tenantUuid.equals(pair.tenantUuid())
        || !provenance.equals(pair.provenance())) {
      throw new IllegalStateException("Canonical first-JOIN pair has different source provenance");
    }
  }

  private void requireCurrentAuthority(
      CompositeSnapshot snapshot,
      IssuerAccountSourceSnapshot sourceSnapshot,
      long accountRowId,
      UUID accountUuid,
      UUID tenantUuid,
      AccountTenantMembership membership,
      VerifiedTenantProvenance provenance,
      TenantAuthorityEventV1Codec.Event tenantSourceEvent) {
    ScopeState member = only(snapshot.memberships(), "membership");
    ScopeState tenant = only(snapshot.tenants(), "tenant");
    if (!AuthorityScope.issuer(ACCOUNT_JWT_ISSUER).equals(snapshot.issuer().scope())
        || !AuthorityScope.account(accountUuid).equals(snapshot.account().scope())
        || !AuthorityScope.tenant(tenantUuid).equals(tenant.scope())
        || !tenantSourceEvidenceMatches(tenantSourceEvent, tenant, tenantUuid, provenance)
        || !AuthorityScope.membership(accountUuid, tenantUuid).equals(member.scope())
        || member.generation() != membership.getMembershipAuthorityGeneration()
        || !accountUuid.equals(snapshot.issuanceFence().accountId())
        || !snapshot.issuanceFence().equals(snapshot.account().issuanceFence())
        || !snapshot.issuanceFence().equals(member.issuanceFence())
        || sourceSnapshot == null
        || !AuthorityScope.issuer(ACCOUNT_JWT_ISSUER).equals(sourceSnapshot.issuer().scope())
        || !AuthorityScope.account(accountUuid).equals(sourceSnapshot.account().scope())
        || sourceSnapshot.issuer().generation() != snapshot.issuer().generation()
        || sourceSnapshot.issuer().sourceVersion() != snapshot.issuer().sourceVersion()
        || sourceSnapshot.account().generation() != snapshot.account().generation()
        || sourceSnapshot.account().sourceVersion() != snapshot.account().sourceVersion()
        || !snapshot.issuanceFence().equals(sourceSnapshot.issuanceFence())
        || !snapshot.issuanceFence().equals(sourceSnapshot.account().issuanceFence())
        || !"ISSUER_SCOPE_INSERT".equals(sourceSnapshot.issuer().initializationProvenance())
        || sourceSnapshot.issuer().initializationTransactionId() <= 0L
        || sourceSnapshot.issuer().accountRepositoryInsertTransactionId() != null
        || !"ACCOUNT_REPOSITORY_INSERT".equals(sourceSnapshot.account().initializationProvenance())
        || !AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT
            .name()
            .equals(sourceSnapshot.account().accountUuidProvenance())
        || sourceSnapshot.account().accountSourceNumericId() != accountRowId
        || sourceSnapshot.account().accountRepositoryInsertTransactionId() == null
        || sourceSnapshot.account().accountRepositoryInsertTransactionId() <= 0L
        || sourceSnapshot.account().initializationTransactionId()
            != sourceSnapshot.account().accountRepositoryInsertTransactionId()) {
      throw new IllegalStateException(
          "Canonical first JOIN requires exact current Account, source, and tenant authority evidence");
    }
  }

  private TenantAuthorityEventV1Codec.Event readCurrentTenantSourceEvent(
      UUID tenantUuid, VerifiedTenantProvenance provenance, ScopeState tenantAuthority) {
    if (tenantAuthority.generation() == 1L && tenantAuthority.sourceVersion() == 1L) {
      throw new IllegalStateException(
          "Canonical first JOIN requires exact current public-JOIN entitlement and tenant authority evidence");
    }
    TenantAuthorityEventV1Codec.Event event =
        tenantAuthorityEventRepository.readCurrentByTenant(tenantUuid);
    if (!tenantSourceEvidenceMatches(event, tenantAuthority, tenantUuid, provenance)) {
      throw new IllegalStateException(
          "Canonical first JOIN requires exact current Account, source, and tenant authority evidence");
    }
    return event;
  }

  private static boolean tenantSourceEvidenceMatches(
      TenantAuthorityEventV1Codec.Event event,
      ScopeState tenantAuthority,
      UUID tenantUuid,
      VerifiedTenantProvenance provenance) {
    if (event == null) {
      return false;
    }
    var source = event.sourceEvidence();
    return event.tenantId().equals(tenantUuid)
        && event.tenantAuthorityGeneration() == tenantAuthority.generation()
        && event.tenantAuthoritySourceVersion() == tenantAuthority.sourceVersion()
        && "NEW_GAME_ROW".equals(source.provenanceKind())
        && tenantUuid.equals(source.canonicalTenantId())
        && provenance.kind() == TenantProvenanceKind.FRESH_GAME_DESIGN
        && provenance.legacyTenantId() == null
        && provenance.sourceOperationId().equals(source.operationId())
        && provenance.digest().equals(source.evidenceDigest());
  }

  private static PairAuthority requirePositiveFirstJoinPair(
      PairAuthority pair, AccountTenantMembership membership) {
    if (!pair.membershipExists()
        || pair.membershipVersion() != 2L
        || pair.membershipAuthorityGeneration() != 1L
        || pair.eventSequence() != 1L
        || pair.eventId() == null
        || pair.eventDigest() == null
        || pair.lastTransitionInvalidated()
        || membership.getMembershipVersion() != 2L
        || membership.getMembershipAuthorityGeneration() != 1L) {
      throw new IllegalStateException("Canonical first-JOIN pair is not the exact committed event");
    }
    return pair;
  }

  private static void requireCommittedFirstJoinPair(
      PairAuthority pair,
      Checkpoint checkpoint,
      VerifiedTenantProvenance provenance,
      UUID accountUuid,
      UUID tenantUuid) {
    if (!pair.membershipExists()
        || !accountUuid.equals(pair.accountUuid())
        || !tenantUuid.equals(pair.tenantUuid())
        || !provenance.equals(pair.provenance())
        || pair.membershipVersion() != 2L
        || pair.membershipAuthorityGeneration() != 1L
        || pair.eventSequence() != 1L
        || pair.lastTransitionInvalidated()
        || !checkpoint.sourceEventId().equals(pair.eventId())
        || !checkpoint.sourceEventDigest().equals(pair.eventDigest())) {
      throw new IllegalStateException("Canonical first-JOIN pair readback differs from its event");
    }
  }

  private static MembershipEvent verifyStoredEvent(
      Event event,
      CanonicalJoinScopeV2 scope,
      AccountTenantMembership membership,
      List<String> roles,
      String requestId) {
    String streamKey = membershipStreamKey(scope.accountId(), scope.tenantId());
    if (!streamKey.equals(event.outboxStreamKey())
        || !requestId.equals(event.requestId())
        || event.outboxSequence() != 1L) {
      throw new IllegalStateException(
          "Stored first-JOIN event differs from its exact stream scope");
    }
    final MembershipEvent verified;
    try {
      verified =
          MembershipAuthorityEventV1Codec.verify(
              new String(event.payload(), StandardCharsets.UTF_8));
    } catch (RuntimeException invalid) {
      throw new IllegalStateException("Stored first-JOIN membership event is invalid", invalid);
    }
    if (!Arrays.equals(event.payload(), verified.canonicalJsonUtf8())
        || !verified.schemaVersion().equals(MembershipAuthorityEventV1Codec.SCHEMA_VERSION)
        || !verified.eventType().equals(MembershipAuthorityEventV1Codec.EVENT_TYPE)
        || !verified.eventId().equals(event.eventId())
        || !verified.eventId().equals(eventIdForRequest(requestId))
        || !verified.requestId().equals(requestId)
        || !verified.outboxStreamKey().equals(streamKey)
        || !"1".equals(verified.outboxSequence())
        || !verified
            .sourceScope()
            .equals("membership/" + scope.accountId() + "/" + scope.tenantId())
        || !verified.accountId().equals(scope.accountId().toString())
        || !verified.tenantId().equals(scope.tenantId().toString())
        || !verified.membershipLifecycleState().equals(membership.getLifecycleState())
        || !verified.membershipVersion().equals(Map.of(scope.tenantId().toString(), "2"))
        || !"1".equals(verified.membershipAuthorityGeneration())
        || !verified.roles().equals(roles)
        || !verified.gameplayAdmissionAllowed()
        || verified.callerBoundAuthorityInvalidated()
        || !verified.eventDigest().equals(event.eventDigest())) {
      throw new IllegalStateException(
          "Stored first-JOIN event payload differs from its immutable membership sources");
    }
    return verified;
  }

  private static void requireAppendedEvent(
      Event appended, MembershipEvent expected, String streamKey, String requestId) {
    if (!streamKey.equals(appended.outboxStreamKey())
        || !requestId.equals(appended.requestId())
        || appended.outboxSequence() != 1L
        || !expected.eventId().equals(appended.eventId())
        || !expected.eventDigest().equals(appended.eventDigest())
        || !Arrays.equals(expected.canonicalJsonUtf8(), appended.payload())) {
      throw new IllegalStateException("Account first-JOIN append differs from its exact candidate");
    }
  }

  private static void requireCheckpointMatchesEvent(Checkpoint checkpoint, Event event) {
    if (!checkpoint.outboxStreamKey().equals(event.outboxStreamKey())
        || checkpoint.outboxSequence() != event.outboxSequence()
        || !checkpoint.sourceEventId().equals(event.eventId())
        || !checkpoint.sourceEventDigest().equals(event.eventDigest())) {
      throw new IllegalStateException("Account first-JOIN checkpoint differs from its event");
    }
  }

  private static boolean isPendingBaseline(PairAuthority pair) {
    return !pair.membershipExists()
        && pair.membershipVersion() == 1L
        && pair.membershipAuthorityGeneration() == 1L
        && pair.eventSequence() == 0L
        && pair.eventId() == null
        && pair.eventDigest() == null
        && !pair.lastTransitionInvalidated();
  }

  private static ScopeState only(List<ScopeState> values, String label) {
    if (values.size() != 1) {
      throw new IllegalStateException(
          "Account authority snapshot does not contain exactly one " + label);
    }
    return values.getFirst();
  }

  private static String membershipStreamKey(UUID accountUuid, UUID tenantUuid) {
    return MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX
        + "membership/"
        + accountUuid
        + "/"
        + tenantUuid;
  }

  private static String eventIdForRequest(String requestId) {
    return UUID.nameUUIDFromBytes(
            (MembershipAuthorityEventV1Codec.SCHEMA_VERSION + ":" + requestId)
                .getBytes(StandardCharsets.UTF_8))
        .toString();
  }

  private static String decimal(long value) {
    if (value <= 0L) {
      throw new IllegalStateException("Account authority event values must be positive");
    }
    return Long.toString(value);
  }

  private static void requireBoundedText(String value, String label, int maxCodePoints) {
    if (value == null
        || value.isBlank()
        || value.codePointCount(0, value.length()) > maxCodePoints) {
      throw new IllegalArgumentException(
          label + " must contain 1 to " + maxCodePoints + " characters");
    }
  }

  public record OutboxCheckpointEntry(String outboxStreamKey, String outboxSequence) {
    public OutboxCheckpointEntry {
      Objects.requireNonNull(outboxStreamKey, "authority outbox stream key is required");
      Objects.requireNonNull(outboxSequence, "authority outbox sequence is required");
      if (!outboxStreamKey.startsWith(MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX)
          || !isNonnegativeCanonicalDecimal(outboxSequence)) {
        throw new IllegalArgumentException("Account authority checkpoint is not canonical");
      }
    }
  }

  /** Positive committed source-event identity kept outside the two-field checkpoint carrier. */
  public record OutboxSourceEvidence(
      String outboxStreamKey,
      String outboxSequence,
      String eventId,
      String eventDigest,
      String canonicalEventJson) {
    public OutboxSourceEvidence {
      Objects.requireNonNull(outboxStreamKey, "authority outbox stream key is required");
      Objects.requireNonNull(outboxSequence, "authority outbox sequence is required");
      if (eventId == null || eventId.isBlank()) {
        throw new IllegalArgumentException("source event ID is required");
      }
      Objects.requireNonNull(eventDigest, "source event digest is required");
      Objects.requireNonNull(canonicalEventJson, "canonical source event JSON is required");
      if (!outboxStreamKey.startsWith(MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX)
          || !isPositiveCanonicalDecimal(outboxSequence)
          || !eventDigest.matches("sha256:[0-9a-f]{64}")
          || !canonicalEventJson.startsWith("{")
          || !canonicalEventJson.endsWith("}")) {
        throw new IllegalArgumentException(
            "Account authority source event evidence is not canonical");
      }
    }
  }

  /**
   * Local V36 absence evidence with an exact one-tenant membership-version map. The sequence-zero
   * checkpoints carry no source event identity or digest and never authorize gameplay.
   */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "The constructor stores immutable Map.copyOf and List.copyOf values.")
  public record NeverJoinedMembershipSnapshot(
      String accountId,
      String tenantId,
      Map<String, String> membershipVersion,
      String membershipAuthorityGeneration,
      AuthorityTuple authorityTuple,
      String issuanceFence,
      Instant evaluatedAt,
      String outboxStreamKey,
      List<OutboxCheckpointEntry> outboxCheckpoints,
      List<OutboxSourceEvidence> outboxSourceEvidence) {
    public NeverJoinedMembershipSnapshot {
      accountId = requireCanonicalUuid(accountId, "canonical Account UUID");
      tenantId = requireCanonicalUuid(tenantId, "canonical tenant UUID");
      membershipVersion =
          requireExactPositiveVersionMap(membershipVersion, tenantId, "membership version");
      membershipAuthorityGeneration =
          requirePositiveCanonicalDecimal(
              membershipAuthorityGeneration, "membership authority generation");
      Objects.requireNonNull(authorityTuple, "complete authority tuple is required");
      issuanceFence = requirePositiveCanonicalDecimal(issuanceFence, "Account issuance fence");
      Objects.requireNonNull(evaluatedAt, "Account evaluation time is required");
      outboxStreamKey =
          Objects.requireNonNull(outboxStreamKey, "membership stream key is required");
      outboxCheckpoints = requireOrderedCheckpointSet(outboxCheckpoints);
      outboxSourceEvidence = List.copyOf(outboxSourceEvidence);

      if (!"1".equals(membershipAuthorityGeneration)
          || !membershipAuthorityGeneration.equals(
              authorityTuple.membershipAuthorityGeneration().get(tenantId))
          || !"1".equals(membershipVersion.get(tenantId))
          || !authorityTuple.privateRealmGrantVersions().isEmpty()
          || authorityTuple.tenantBillingCutoff().isPresent()) {
        throw new IllegalArgumentException(
            "Never-joined Account authority tuple is incomplete or mismatched");
      }

      String expectedStreamKey =
          MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX
              + "membership/"
              + accountId
              + "/"
              + tenantId;
      if (!expectedStreamKey.equals(outboxStreamKey)) {
        throw new IllegalArgumentException(
            "Never-joined Account membership stream key is not canonical");
      }
      List<OutboxCheckpointEntry> membershipCheckpoints =
          outboxCheckpoints.stream()
              .filter(checkpoint -> checkpoint.outboxStreamKey().equals(expectedStreamKey))
              .toList();
      if (membershipCheckpoints.size() != 1
          || !"0".equals(membershipCheckpoints.getFirst().outboxSequence())) {
        throw new IllegalArgumentException(
            "Never-joined Account snapshot must retain a sequence-zero membership checkpoint");
      }
      if (RuntimeMembershipAuthorityEvidenceValidator.validate(
              validatorSnapshot(
                  accountId,
                  tenantId,
                  false,
                  "MISSING",
                  false,
                  membershipVersion,
                  membershipAuthorityGeneration,
                  List.of(),
                  authorityTuple,
                  issuanceFence,
                  outboxCheckpoints,
                  outboxSourceEvidence))
          .isPresent()) {
        throw new IllegalArgumentException(
            "Never-joined Account snapshot cannot contain a membership event");
      }
    }

    public boolean membershipExists() {
      return false;
    }

    public String membershipLifecycleState() {
      return "MISSING";
    }

    public List<String> roles() {
      return List.of();
    }

    public boolean gameplayAdmissionAllowed() {
      return false;
    }
  }

  /** Existing control-only source evidence, never a runtime admission or authoring permission. */
  public record CreatorControlCaptureSources(
      UUID accountUuid,
      UUID tenantUuid,
      CompositeSnapshot authoritySnapshot,
      Map<String, String> membershipVersion,
      AuthorityTuple authorityTuple,
      String issuanceFence,
      PairAuthority pairSource,
      RoleSnapshot roleSource,
      FreshTenantCreationEvidence creationSource,
      StoredOperation bootstrapReceipt,
      MembershipEvent sourceEvent,
      List<OutboxCheckpointEntry> outboxCheckpoints,
      List<OutboxSourceEvidence> outboxSourceEvidence,
      Instant evaluatedAt) {
    public CreatorControlCaptureSources {
      membershipVersion = Map.copyOf(membershipVersion);
      outboxCheckpoints = List.copyOf(outboxCheckpoints);
      outboxSourceEvidence = List.copyOf(outboxSourceEvidence);
    }
  }

  private static String requireCanonicalUuid(String value, String field) {
    Objects.requireNonNull(value, field + " is required");
    try {
      if (!UUID.fromString(value).toString().equals(value)) {
        throw new IllegalArgumentException(field + " must be a canonical lowercase UUID");
      }
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException(field + " must be a canonical lowercase UUID", exception);
    }
    return value;
  }

  private static String requirePositiveCanonicalDecimal(String value, String field) {
    Objects.requireNonNull(value, field + " is required");
    if (!isPositiveCanonicalDecimal(value)) {
      throw new IllegalArgumentException(
          field + " must be a positive canonical unsigned decimal string");
    }
    return value;
  }

  private static boolean isPositiveCanonicalDecimal(String value) {
    return value != null && value.matches("[1-9][0-9]*");
  }

  private static boolean isNonnegativeCanonicalDecimal(String value) {
    return value != null && value.matches("0|[1-9][0-9]*");
  }

  private static Map<String, String> requireExactPositiveVersionMap(
      Map<String, String> value, String tenantId, String field) {
    Objects.requireNonNull(value, field + " map is required");
    if (value.size() != 1
        || !value.containsKey(tenantId)
        || !isPositiveCanonicalDecimal(value.get(tenantId))) {
      throw new IllegalArgumentException(field + " must be a positive canonical one-tenant map");
    }
    return Map.copyOf(value);
  }

  private static List<OutboxCheckpointEntry> requireOrderedCheckpointSet(
      List<OutboxCheckpointEntry> checkpoints) {
    Objects.requireNonNull(checkpoints, "Account authority checkpoints are required");
    List<OutboxCheckpointEntry> copy = List.copyOf(checkpoints);
    ArrayList<OutboxCheckpointEntry> ordered = new ArrayList<>(copy);
    ordered.sort(OUTBOX_CHECKPOINT_ORDER);
    if (!copy.equals(ordered)) {
      throw new IllegalArgumentException(
          "Account authority checkpoints must use canonical stream-key order");
    }
    for (int index = 1; index < copy.size(); index++) {
      if (copy.get(index - 1).outboxStreamKey().equals(copy.get(index).outboxStreamKey())) {
        throw new IllegalArgumentException(
            "Account authority checkpoint stream keys must be unique");
      }
    }
    return copy;
  }

  static Snapshot validatorSnapshot(
      String accountId,
      String tenantId,
      boolean membershipExists,
      String membershipLifecycleState,
      boolean gameplayAdmissionAllowed,
      Map<String, String> membershipVersion,
      String membershipAuthorityGeneration,
      List<String> roles,
      AuthorityTuple authorityTuple,
      String issuanceFence,
      List<OutboxCheckpointEntry> checkpoints,
      List<OutboxSourceEvidence> sourceEvidence) {
    return new Snapshot(
        ACCOUNT_JWT_ISSUER,
        accountId,
        tenantId,
        membershipExists,
        membershipLifecycleState,
        gameplayAdmissionAllowed,
        membershipVersion,
        membershipAuthorityGeneration,
        roles,
        authorityTuple,
        issuanceFence,
        checkpoints.stream()
            .map(
                checkpoint ->
                    new RuntimeMembershipAuthorityEvidenceValidator.Checkpoint(
                        checkpoint.outboxStreamKey(), checkpoint.outboxSequence()))
            .toList(),
        sourceEvidence.stream()
            .map(
                source ->
                    new RuntimeMembershipAuthorityEvidenceValidator.SourceEvidence(
                        source.outboxStreamKey(),
                        source.outboxSequence(),
                        source.eventId(),
                        source.eventDigest(),
                        source.canonicalEventJson()))
            .toList());
  }

  private static int compareUnsignedUtf8(String left, String right) {
    byte[] leftBytes = left.getBytes(StandardCharsets.UTF_8);
    byte[] rightBytes = right.getBytes(StandardCharsets.UTF_8);
    int commonLength = Math.min(leftBytes.length, rightBytes.length);
    for (int index = 0; index < commonLength; index++) {
      int comparison =
          Integer.compare(
              Byte.toUnsignedInt(leftBytes[index]), Byte.toUnsignedInt(rightBytes[index]));
      if (comparison != 0) {
        return comparison;
      }
    }
    return Integer.compare(leftBytes.length, rightBytes.length);
  }

  private static void requireWritableOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Canonical Account JOIN requires an active owner transaction");
    }
    if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Canonical Account JOIN requires a writable owner transaction");
    }
  }
}
