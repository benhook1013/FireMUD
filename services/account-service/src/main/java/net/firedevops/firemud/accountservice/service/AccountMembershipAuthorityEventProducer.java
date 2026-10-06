package net.firedevops.firemud.accountservice.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountJoinDigest;
import net.firedevops.firemud.accountservice.dto.AccountJoinDigest.EntitlementAvailabilityV2;
import net.firedevops.firemud.accountservice.dto.CanonicalJoinScopeV2;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.entity.AccountTenantMembership;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.CompositeSnapshot;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeKind;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Checkpoint;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Event;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.EventEvidence;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.CurrentSourceEvidence;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.IssuerAccountSourceSnapshot;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository.CanonicalJoinOperationEvidence;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.PairAuthority;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.PairTransition;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.TenantProvenanceKind;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.VerifiedTenantProvenance;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository.RoleSnapshot;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.AuthorityTuple;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.MembershipEvent;
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

  private final AccountJoinOperationRepository joinOperationRepository;
  private final AccountRepository accountRepository;
  private final AccountMembershipPairAuthorityRepository pairAuthorityRepository;
  private final AccountTenantMembershipRepository membershipRepository;
  private final AccountTenantMembershipRoleSnapshotRepository roleSnapshotRepository;
  private final AccountAuthorityGenerationRepository authorityGenerationRepository;
  private final AccountAuthorityOutboxRepository authorityOutboxRepository;
  private final AccountAuthoritySourceEvidenceRepository sourceEvidenceRepository;

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
      AccountAuthoritySourceEvidenceRepository sourceEvidenceRepository) {
    this.joinOperationRepository = Objects.requireNonNull(joinOperationRepository);
    this.accountRepository = Objects.requireNonNull(accountRepository);
    this.pairAuthorityRepository = Objects.requireNonNull(pairAuthorityRepository);
    this.membershipRepository = Objects.requireNonNull(membershipRepository);
    this.roleSnapshotRepository = Objects.requireNonNull(roleSnapshotRepository);
    this.authorityGenerationRepository = Objects.requireNonNull(authorityGenerationRepository);
    this.authorityOutboxRepository = Objects.requireNonNull(authorityOutboxRepository);
    this.sourceEvidenceRepository = Objects.requireNonNull(sourceEvidenceRepository);
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

    CompositeSnapshot authority =
        authorityGenerationRepository.readCompositeSnapshot(
            ACCOUNT_JWT_ISSUER, accountUuid, List.of(tenantUuid), List.of(tenantUuid));
    IssuerAccountSourceSnapshot sourceSnapshot =
        sourceEvidenceRepository.readCurrentIssuerAccountSources(ACCOUNT_JWT_ISSUER, accountUuid);
    requireGenesisAuthority(authority, accountUuid, tenantUuid, membership);
    requireFreshIssuerAccountBaselines(sourceSnapshot, accountUuid);
    requireNoPriorTenantHistory(tenantUuid);

    String streamKey = membershipStreamKey(accountUuid, tenantUuid);
    if (isPendingBaseline(pair)) {
      if (authorityOutboxRepository.readCheckpoint(streamKey).isPresent()) {
        throw new IllegalStateException(
            "Canonical first JOIN cannot publish over retained membership-event history");
      }
      Checkpoint checkpoint =
          appendFirstJoinEvent(streamKey, requestId, scope, membership, exactRoles, authority);
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
    return readExactCommittedFirstJoin(
        streamKey,
        requestId,
        scope,
        membership,
        exactRoles,
        pair,
        authority.issuanceFence().value());
  }

  private Checkpoint appendFirstJoinEvent(
      String streamKey,
      String requestId,
      CanonicalJoinScopeV2 scope,
      AccountTenantMembership membership,
      List<String> roles,
      CompositeSnapshot authority) {
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
                          scope, membership, roles, authority, eventId, requestId, sequence));
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
    requireInitialAuthorityTuple(verified, scope.tenantId());
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
      long currentIssuanceFence) {
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
    requireInitialAuthorityTuple(verified, scope.tenantId());
    long committedFence = parsePositiveCanonicalDecimal(verified.issuanceFence(), "issuance fence");
    if (committedFence > currentIssuanceFence
        || !pair.eventId().equals(verified.eventId())
        || !pair.eventDigest().equals(verified.eventDigest())) {
      throw new IllegalStateException("Committed first-JOIN payload or pair linkage differs");
    }
    return checkpoint;
  }

  private static Map<String, Object> membershipEventPreimage(
      CanonicalJoinScopeV2 scope,
      AccountTenantMembership membership,
      List<String> roles,
      CompositeSnapshot authority,
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

  private void requireGenesisAuthority(
      CompositeSnapshot snapshot,
      UUID accountUuid,
      UUID tenantUuid,
      AccountTenantMembership membership) {
    ScopeState member = only(snapshot.memberships(), "membership");
    ScopeState tenant = only(snapshot.tenants(), "tenant");
    if (!AuthorityScope.issuer(ACCOUNT_JWT_ISSUER).equals(snapshot.issuer().scope())
        || !AuthorityScope.account(accountUuid).equals(snapshot.account().scope())
        || !AuthorityScope.tenant(tenantUuid).equals(tenant.scope())
        || !AuthorityScope.membership(accountUuid, tenantUuid).equals(member.scope())
        || snapshot.issuer().generation() != 1L
        || snapshot.issuer().sourceVersion() != 1L
        || snapshot.account().generation() != 1L
        || snapshot.account().sourceVersion() != 1L
        || tenant.generation() != 1L
        || tenant.sourceVersion() != 1L
        || member.generation() != 1L
        || member.sourceVersion() != 1L
        || member.generation() != membership.getMembershipAuthorityGeneration()
        || snapshot.issuanceFence().value() != 1L
        || snapshot.issuanceFence().sourceVersion() != 1L
        || !accountUuid.equals(snapshot.issuanceFence().accountId())
        || !snapshot.issuanceFence().equals(snapshot.account().issuanceFence())
        || !snapshot.issuanceFence().equals(member.issuanceFence())) {
      throw new IllegalStateException(
          "Canonical first JOIN requires exact generation-one owner rows and current Account fence");
    }
  }

  private void requireFreshIssuerAccountBaselines(
      IssuerAccountSourceSnapshot snapshot, UUID accountUuid) {
    requireFreshBaseline(snapshot.issuer(), AuthorityScope.issuer(ACCOUNT_JWT_ISSUER));
    requireFreshBaseline(snapshot.account(), AuthorityScope.account(accountUuid));
    if (!accountUuid.equals(snapshot.account().scope().accountId())
        || !AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT
            .name()
            .equals(snapshot.account().accountUuidProvenance())
        || snapshot.account().accountSourceNumericId() == null
        || snapshot.account().accountSourceNumericId() <= 0L
        || snapshot.account().accountRepositoryInsertTransactionId() == null
        || snapshot.account().accountRepositoryInsertTransactionId() <= 0L
        || snapshot.account().initializationTransactionId()
            != snapshot.account().accountRepositoryInsertTransactionId()
        || snapshot.issuer().accountRepositoryInsertTransactionId() != null
        || snapshot.issuanceFence().value() != 1L
        || snapshot.issuanceFence().sourceVersion() != 1L) {
      throw new IllegalStateException(
          "Canonical first JOIN requires owner-proved fresh issuer and Account source baselines");
    }
  }

  private static void requireFreshBaseline(
      CurrentSourceEvidence source, AuthorityScope expectedScope) {
    if (!expectedScope.equals(source.scope())
        || source.generation() != 1L
        || source.sourceVersion() != 1L
        || source.checkpoint().sequence() != 0L
        || source.checkpoint().sourceEventId().isPresent()
        || source.checkpoint().sourceEventDigest().isPresent()
        || source.accountSecurityCutoff().isPresent()
        || source.initializationTransactionId() <= 0L
        || (expectedScope.kind() == ScopeKind.ISSUER
            && (source.issuanceFence() != null
                || source.accountSourceNumericId() != null
                || source.accountUuidProvenance() != null
                || source.accountRepositoryInsertTransactionId() != null))
        || (expectedScope.kind() == ScopeKind.ACCOUNT
            && (source.issuanceFence() == null
                || source.issuanceFence().value() != 1L
                || source.issuanceFence().sourceVersion() != 1L
                || source.accountSourceNumericId() == null
                || source.accountSourceNumericId() <= 0L
                || source.accountRepositoryInsertTransactionId() == null
                || source.accountRepositoryInsertTransactionId() <= 0L
                || source.initializationTransactionId()
                    != source.accountRepositoryInsertTransactionId()))) {
      throw new IllegalStateException(
          "Canonical first JOIN requires exact owner-proved sequence-zero source baselines");
    }
  }

  private void requireNoPriorTenantHistory(UUID tenantUuid) {
    requireAbsentHistory(tenantStreamKey(tenantUuid));
  }

  private void requireAbsentHistory(String streamKey) {
    if (authorityOutboxRepository.readCheckpoint(streamKey).isPresent()) {
      throw new IllegalStateException(
          "Canonical first JOIN cannot publish with prior issuer, Account, or tenant event history");
    }
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
          "Stored first-JOIN event payload differs from Account sources");
    }
    return verified;
  }

  private static void requireInitialAuthorityTuple(MembershipEvent event, UUID tenantUuid) {
    AuthorityTuple tuple = event.authorityTuple();
    if (!"1".equals(tuple.issuerAuthGeneration())
        || !"1".equals(tuple.accountAuthorityGeneration())
        || !Map.of(tenantUuid.toString(), "1").equals(tuple.tenantAuthorityGeneration())
        || !Map.of(tenantUuid.toString(), "1").equals(tuple.membershipAuthorityGeneration())
        || !tuple.privateRealmGrantVersions().isEmpty()
        || tuple.accountSecurityCutoff().isPresent()
        || tuple.tenantBillingCutoff().isPresent()) {
      throw new IllegalStateException(
          "Committed first-JOIN event contains advanced or cutoff authority state");
    }
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

  private static String tenantStreamKey(UUID tenantUuid) {
    return MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX + "tenant/" + tenantUuid;
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

  private static long parsePositiveCanonicalDecimal(String value, String label) {
    if (value == null || !value.matches("[1-9][0-9]*")) {
      throw new IllegalStateException("Committed Account event has invalid " + label);
    }
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException overflow) {
      throw new IllegalStateException(
          "Committed Account event " + label + " exceeds BIGINT", overflow);
    }
  }

  private static void requireBoundedText(String value, String label, int maxCodePoints) {
    if (value == null
        || value.isBlank()
        || value.codePointCount(0, value.length()) > maxCodePoints) {
      throw new IllegalArgumentException(
          label + " must contain 1 to " + maxCodePoints + " characters");
    }
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
