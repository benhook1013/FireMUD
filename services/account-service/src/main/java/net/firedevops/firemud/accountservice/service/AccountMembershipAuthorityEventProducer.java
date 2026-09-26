package net.firedevops.firemud.accountservice.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountTenantMembership;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.CompositeSnapshot;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Checkpoint;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Event;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.EventEvidence;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository.JoinOperation;
import net.firedevops.firemud.accountservice.repository.AccountMembershipTransitionReceiptRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantIdentityResolver;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository.JoinMembershipProof;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository.RoleSnapshot;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.MembershipEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Produces the Account-owned V33 authority event for an explicit public-production JOIN. */
@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected repositories are internal Spring collaborators.")
public class AccountMembershipAuthorityEventProducer {
  private final AccountRepository accountRepository;
  private final AccountTenantIdentityResolver tenantIdentityResolver;
  private final AccountAuthorityGenerationRepository authorityGenerationRepository;
  private final AccountAuthorityOutboxRepository authorityOutboxRepository;
  private final AccountMembershipTransitionReceiptRepository transitionReceiptRepository;
  private final AccountTenantMembershipRepository membershipRepository;
  private final AccountTenantMembershipRoleSnapshotRepository roleSnapshotRepository;

  public AccountMembershipAuthorityEventProducer(
      AccountRepository accountRepository,
      AccountTenantIdentityResolver tenantIdentityResolver,
      AccountAuthorityGenerationRepository authorityGenerationRepository,
      AccountAuthorityOutboxRepository authorityOutboxRepository,
      AccountMembershipTransitionReceiptRepository transitionReceiptRepository,
      AccountTenantMembershipRepository membershipRepository,
      AccountTenantMembershipRoleSnapshotRepository roleSnapshotRepository) {
    this.accountRepository = accountRepository;
    this.tenantIdentityResolver = tenantIdentityResolver;
    this.authorityGenerationRepository = authorityGenerationRepository;
    this.authorityOutboxRepository = authorityOutboxRepository;
    this.transitionReceiptRepository = transitionReceiptRepository;
    this.membershipRepository = membershipRepository;
    this.roleSnapshotRepository = roleSnapshotRepository;
  }

  /**
   * Initializes a membership generation only after exact absence/history checks in the JOIN txn.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void initializeNewMembershipAuthority(long accountId, long legacyTenantId) {
    Identity identity = resolveIdentity(accountId, legacyTenantId);
    if (membershipRepository.findByAccountIdAndTenantId(accountId, legacyTenantId).isPresent()) {
      throw new IllegalStateException("New Account membership already exists");
    }
    transitionReceiptRepository.assertNewMembershipTransitionCanStart(accountId, legacyTenantId);
    String streamKey = membershipStreamKey(identity);
    if (authorityOutboxRepository.readCheckpoint(streamKey).isPresent()) {
      throw new IllegalStateException(
          "New Account membership has retained V33 authority-event history");
    }
    ScopeState initialized =
        authorityGenerationRepository.initialize(
            AuthorityScope.membership(identity.accountUuid(), identity.tenantUuid()));
    if (initialized.generation() != 1L
        || initialized.issuanceFence() == null
        || !identity.accountUuid().equals(initialized.issuanceFence().accountId())) {
      throw new IllegalStateException(
          "New Account membership authority did not initialize at its exact first generation");
    }
  }

  /** Locks the current generation tuple and requires it to match the retained inactive row. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void requireExistingMembershipAuthorityMatches(
      long accountId,
      long legacyTenantId,
      AccountTenantMembership membership,
      RoleSnapshot roleSnapshot) {
    Identity identity = resolveIdentity(accountId, legacyTenantId);
    requireMembershipIdentity(accountId, legacyTenantId, membership);
    if (!"INACTIVE".equals(membership.getLifecycleState())
        || membership.isGameplayAdmissionAllowed()
        || !"EXPLICIT_JOIN".equals(membership.getAuthorityProvenance())) {
      throw new IllegalStateException("Only retained explicit inactive membership may reactivate");
    }
    CompositeSnapshot snapshot = readSnapshot(identity);
    ScopeState membershipState = only(snapshot.memberships(), "membership");
    requireMatchingFence(snapshot, membershipState, identity.accountUuid());
    if (membership.getMembershipAuthorityGeneration() <= 0L
        || membership.getMembershipAuthorityGeneration() != membershipState.generation()) {
      throw new IllegalStateException(
          "Embedded and canonical Account membership generations differ");
    }
    requireCurrentMembershipEvent(
        identity,
        membership.getId(),
        membership.getLifecycleState(),
        membership.isGameplayAdmissionAllowed(),
        membership.getMembershipVersion(),
        membership.getMembershipAuthorityGeneration(),
        membership.getAuthorityProvenance(),
        roleSnapshot,
        null,
        true,
        snapshot);
  }

  /** Requires the latest checkpoint event to prove the exact current retained membership state. */
  @Transactional(propagation = Propagation.MANDATORY)
  public Checkpoint requireCurrentMembershipEvent(
      long accountId,
      long legacyTenantId,
      AccountTenantMembership membership,
      RoleSnapshot roleSnapshot,
      String expectedRequestId,
      boolean callerBoundAuthorityInvalidated) {
    Identity identity = resolveIdentity(accountId, legacyTenantId);
    requireMembershipIdentity(accountId, legacyTenantId, membership);
    CompositeSnapshot snapshot = readSnapshot(identity);
    ScopeState member = only(snapshot.memberships(), "membership");
    requireMatchingFence(snapshot, member, identity.accountUuid());
    if (member.generation() != membership.getMembershipAuthorityGeneration()) {
      throw new IllegalStateException(
          "Embedded and canonical Account membership generations differ");
    }
    return requireCurrentMembershipEvent(
        identity,
        membership.getId(),
        membership.getLifecycleState(),
        membership.isGameplayAdmissionAllowed(),
        membership.getMembershipVersion(),
        membership.getMembershipAuthorityGeneration(),
        membership.getAuthorityProvenance(),
        roleSnapshot,
        expectedRequestId,
        callerBoundAuthorityInvalidated,
        snapshot);
  }

  /** Reconciliation proof variant for the locked membership and role readback records. */
  @Transactional(propagation = Propagation.MANDATORY)
  public Checkpoint requireCurrentMembershipEvent(
      JoinMembershipProof membership,
      RoleSnapshot roleSnapshot,
      String expectedRequestId,
      boolean callerBoundAuthorityInvalidated) {
    if (membership == null) {
      throw new IllegalArgumentException("Locked Account membership evidence is required");
    }
    Identity identity = resolveIdentity(membership.accountId(), membership.tenantId());
    CompositeSnapshot snapshot = readSnapshot(identity);
    ScopeState member = only(snapshot.memberships(), "membership");
    requireMatchingFence(snapshot, member, identity.accountUuid());
    if (member.generation() != membership.membershipAuthorityGeneration()) {
      throw new IllegalStateException(
          "Embedded and canonical Account membership generations differ");
    }
    return requireCurrentMembershipEvent(
        identity,
        membership.membershipId(),
        membership.lifecycleState(),
        membership.gameplayAdmissionAllowed(),
        membership.membershipVersion(),
        membership.membershipAuthorityGeneration(),
        membership.authorityProvenance(),
        roleSnapshot,
        expectedRequestId,
        callerBoundAuthorityInvalidated,
        snapshot);
  }

  /** Advances and emits the complete committed tuple for a newly created public membership. */
  @Transactional(propagation = Propagation.MANDATORY)
  public Checkpoint publishNewMembershipChange(
      long accountId, long legacyTenantId, String requestId, AccountTenantMembership membership) {
    Identity identity = resolveIdentity(accountId, legacyTenantId);
    requireMembershipIdentity(accountId, legacyTenantId, membership);
    if (membership.getMembershipVersion() != 1L
        || membership.getMembershipAuthorityGeneration() != 1L) {
      throw new IllegalStateException(
          "New Account membership must commit at version and generation one");
    }
    CompositeSnapshot snapshot = readSnapshot(identity);
    ScopeState membershipState = only(snapshot.memberships(), "membership");
    requireMatchingFence(snapshot, membershipState, identity.accountUuid());
    if (membershipState.generation() != 1L) {
      throw new IllegalStateException(
          "New Account membership canonical generation is not its first generation");
    }
    String streamKey = membershipStreamKey(identity);
    if (authorityOutboxRepository.readCheckpoint(streamKey).isPresent()) {
      throw new IllegalStateException(
          "New Account membership acquired V33 authority-event history before publication");
    }
    return appendAndReadBack(identity, requestId, membership, snapshot, false, true);
  }

  /** Compare-and-advances an inactive member's existing canonical generation before publication. */
  @Transactional(propagation = Propagation.MANDATORY)
  public Checkpoint publishReactivatedMembershipChange(
      long accountId, long legacyTenantId, String requestId, AccountTenantMembership membership) {
    Identity identity = resolveIdentity(accountId, legacyTenantId);
    requireMembershipIdentity(accountId, legacyTenantId, membership);
    if (!"ACTIVE".equals(membership.getLifecycleState())
        || !membership.isGameplayAdmissionAllowed()
        || !"EXPLICIT_JOIN".equals(membership.getAuthorityProvenance())) {
      throw new IllegalStateException("Reactivated Account membership is not active and explicit");
    }
    long expectedPriorGeneration = decrementPositive(membership.getMembershipAuthorityGeneration());
    CompositeSnapshot beforeAdvance = readSnapshot(identity);
    ScopeState currentMembership = only(beforeAdvance.memberships(), "membership");
    requireMatchingFence(beforeAdvance, currentMembership, identity.accountUuid());
    if (currentMembership.generation() != expectedPriorGeneration) {
      throw new IllegalStateException(
          "Embedded and canonical Account membership generations differ at reactivation");
    }
    ScopeState advanced =
        authorityGenerationRepository.advance(currentMembership, beforeAdvance.issuanceFence());
    if (advanced.generation() != membership.getMembershipAuthorityGeneration()) {
      throw new IllegalStateException(
          "Account membership authority generation did not advance with reactivation");
    }
    CompositeSnapshot committedCandidate = readSnapshot(identity);
    ScopeState committedMembership = only(committedCandidate.memberships(), "membership");
    requireMatchingFence(committedCandidate, committedMembership, identity.accountUuid());
    if (committedMembership.generation() != membership.getMembershipAuthorityGeneration()) {
      throw new IllegalStateException(
          "Committed Account membership tuple differs from the reactivation candidate");
    }
    return appendAndReadBack(identity, requestId, membership, committedCandidate, true, false);
  }

  /**
   * Verifies a JOINED operation against its exact immutable event, even after later stream events.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Checkpoint requireCommittedJoinEvent(JoinOperation operation) {
    if (operation == null
        || !"COMMITTED".equals(operation.status())
        || !"JOINED".equals(operation.outcome())
        || operation.membershipId() == null
        || operation.membershipId() <= 0L
        || operation.membershipVersion() == null
        || operation.membershipVersion() <= 0L
        || operation.membershipAuthorityGeneration() == null
        || operation.membershipAuthorityGeneration() <= 0L) {
      throw new IllegalStateException(
          "Committed JOIN operation lacks exact event readback identity");
    }
    Identity identity = resolveIdentity(operation.accountId(), operation.tenantId());
    String streamKey = membershipStreamKey(identity);
    Event event =
        authorityOutboxRepository
            .findEvent(streamKey, operation.requestId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Committed JOIN has no matching V33 authority event"));
    MembershipEvent verified = verifyStoredEvent(event, identity, operation.requestId());
    if (!Long.toString(operation.membershipVersion()).equals(verified.membershipVersion())
        || !Long.toString(operation.membershipAuthorityGeneration())
            .equals(verified.membershipAuthorityGeneration())
        || !verified.gameplayAdmissionAllowed()
        || !"ACTIVE".equals(verified.membershipLifecycleState())
        || !verified.roles().contains("player")) {
      throw new IllegalStateException(
          "Committed JOIN operation differs from its canonical V33 authority event");
    }
    Checkpoint head =
        authorityOutboxRepository
            .readCheckpoint(streamKey)
            .orElseThrow(
                () ->
                    new IllegalStateException("Committed JOIN authority stream has no checkpoint"));
    if (head.outboxSequence() < event.outboxSequence()) {
      throw new IllegalStateException("Committed JOIN authority checkpoint regressed");
    }
    return head;
  }

  private Checkpoint appendAndReadBack(
      Identity identity,
      String requestId,
      AccountTenantMembership membership,
      CompositeSnapshot snapshot,
      boolean callerBoundAuthorityInvalidated,
      boolean newMembership) {
    requirePositive(requestId, "JOIN request ID");
    requireActiveMembership(membership);
    ScopeState issuer = snapshot.issuer();
    ScopeState account = snapshot.account();
    ScopeState tenant = only(snapshot.tenants(), "tenant");
    ScopeState member = only(snapshot.memberships(), "membership");
    requireMatchingFence(snapshot, member, identity.accountUuid());
    if (membership.getMembershipAuthorityGeneration() != member.generation()) {
      throw new IllegalStateException(
          "Embedded and committed Account membership generations differ");
    }
    if (newMembership && member.generation() != 1L) {
      throw new IllegalStateException(
          "A new Account membership cannot reuse a retained generation");
    }
    if (!newMembership && !callerBoundAuthorityInvalidated) {
      throw new IllegalStateException("Membership reactivation must invalidate caller authority");
    }
    requireNoUnmodeledCutoff(identity);
    RoleSnapshot roles = requireRoleSnapshot(identity, membership);
    String streamKey = membershipStreamKey(identity);
    String expectedEventId = eventIdForRequest(requestId);
    MembershipEvent[] candidate = new MembershipEvent[1];
    Event appended =
        authorityOutboxRepository.append(
            streamKey,
            requestId,
            sequence -> {
              MembershipEvent event =
                  MembershipAuthorityEventV1Codec.seal(
                      preimage(
                          identity,
                          requestId,
                          expectedEventId,
                          streamKey,
                          sequence,
                          issuer,
                          account,
                          tenant,
                          member,
                          snapshot.issuanceFence().value(),
                          membership,
                          roles.roles(),
                          callerBoundAuthorityInvalidated));
              candidate[0] = event;
              return new EventEvidence(
                  event.eventId(), event.eventDigest(), event.canonicalJsonUtf8());
            });
    MembershipEvent expected = candidate[0];
    if (expected == null
        || !appended.outboxStreamKey().equals(streamKey)
        || !appended.requestId().equals(requestId)
        || appended.outboxSequence() != Long.parseLong(expected.outboxSequence())
        || !appended.eventId().equals(expected.eventId())
        || !appended.eventDigest().equals(expected.eventDigest())
        || !Arrays.equals(appended.payload(), expected.canonicalJsonUtf8())) {
      throw new IllegalStateException("Account authority event append differs from its candidate");
    }
    Event exactReadback =
        authorityOutboxRepository
            .findEvent(streamKey, requestId)
            .orElseThrow(
                () -> new IllegalStateException("Account authority event readback is absent"));
    if (!appended.equals(exactReadback)) {
      throw new IllegalStateException("Account authority event readback differs from its append");
    }
    MembershipEvent verified = verifyStoredEvent(exactReadback, identity, requestId);
    if (!verified.eventDigest().equals(expected.eventDigest())
        || !verified.canonicalJson().equals(expected.canonicalJson())) {
      throw new IllegalStateException("Account authority event codec readback differs");
    }
    Checkpoint checkpoint =
        authorityOutboxRepository
            .readCheckpoint(streamKey)
            .orElseThrow(
                () -> new IllegalStateException("Account authority event checkpoint is absent"));
    if (checkpoint.outboxSequence() != appended.outboxSequence()
        || !checkpoint.sourceEventId().equals(appended.eventId())
        || !checkpoint.sourceEventDigest().equals(appended.eventDigest())) {
      throw new IllegalStateException("Account authority event checkpoint differs from its append");
    }
    return checkpoint;
  }

  private Checkpoint requireCurrentMembershipEvent(
      Identity identity,
      long membershipId,
      String lifecycleState,
      boolean gameplayAdmissionAllowed,
      long membershipVersion,
      long membershipAuthorityGeneration,
      String authorityProvenance,
      RoleSnapshot roleSnapshot,
      String expectedRequestId,
      boolean callerBoundAuthorityInvalidated,
      CompositeSnapshot snapshot) {
    if (membershipId <= 0L
        || membershipVersion <= 0L
        || membershipAuthorityGeneration <= 0L
        || (!"ACTIVE".equals(lifecycleState) && !"INACTIVE".equals(lifecycleState))
        || ("INACTIVE".equals(lifecycleState) && gameplayAdmissionAllowed)
        || !"EXPLICIT_JOIN".equals(authorityProvenance)
        || roleSnapshot == null
        || roleSnapshot.accountId() != identity.accountId()
        || roleSnapshot.tenantId() != identity.legacyTenantId()
        || roleSnapshot.membershipId() != membershipId
        || roleSnapshot.snapshotVersion() != membershipVersion) {
      throw new IllegalStateException("Current Account membership and role evidence is incomplete");
    }
    List<String> exactRoles;
    try {
      exactRoles =
          AccountTenantMembershipRoleSnapshotRepository.requireCanonicalRoleSet(
              roleSnapshot.roles());
    } catch (RuntimeException exception) {
      throw new IllegalStateException("Current Account role snapshot is not canonical", exception);
    }

    String streamKey = membershipStreamKey(identity);
    Checkpoint checkpoint =
        authorityOutboxRepository
            .readCheckpoint(streamKey)
            .orElseThrow(
                () ->
                    new IllegalStateException("Current Account membership has no V33 checkpoint"));
    Event event =
        authorityOutboxRepository
            .findEvent(streamKey, checkpoint.outboxSequence())
            .orElseThrow(
                () -> new IllegalStateException("Current Account V33 checkpoint has no event"));
    if (!checkpointMatches(checkpoint, event)
        || (expectedRequestId != null && !expectedRequestId.equals(event.requestId()))) {
      throw new IllegalStateException("Current Account membership checkpoint identity differs");
    }
    MembershipEvent verified = verifyStoredEvent(event, identity, event.requestId());
    ScopeState issuer = snapshot.issuer();
    ScopeState account = snapshot.account();
    ScopeState tenant = only(snapshot.tenants(), "tenant");
    ScopeState member = only(snapshot.memberships(), "membership");
    requireMatchingFence(snapshot, member, identity.accountUuid());
    var tuple = verified.authorityTuple();
    if (!Long.toString(issuer.generation()).equals(tuple.issuerAuthGeneration())
        || !Long.toString(account.generation()).equals(tuple.accountAuthorityGeneration())
        || !Map.of(identity.tenantUuid().toString(), Long.toString(tenant.generation()))
            .equals(tuple.tenantAuthorityGeneration())
        || !Map.of(identity.tenantUuid().toString(), Long.toString(member.generation()))
            .equals(tuple.membershipAuthorityGeneration())
        || !tuple.privateRealmGrantVersions().isEmpty()
        || tuple.accountSecurityCutoff().isPresent()
        || tuple.tenantBillingCutoff().isPresent()
        || !Long.toString(snapshot.issuanceFence().value()).equals(verified.issuanceFence())
        || member.generation() != membershipAuthorityGeneration
        || !lifecycleState.equals(verified.membershipLifecycleState())
        || !Long.toString(membershipVersion).equals(verified.membershipVersion())
        || !Long.toString(membershipAuthorityGeneration)
            .equals(verified.membershipAuthorityGeneration())
        || !exactRoles.equals(verified.roles())
        || gameplayAdmissionAllowed != verified.gameplayAdmissionAllowed()
        || callerBoundAuthorityInvalidated != verified.callerBoundAuthorityInvalidated()) {
      throw new IllegalStateException(
          "Current Account membership differs from its canonical V33 event and V31 tuple");
    }
    return checkpoint;
  }

  private static boolean checkpointMatches(Checkpoint checkpoint, Event event) {
    return checkpoint.outboxStreamKey().equals(event.outboxStreamKey())
        && checkpoint.outboxSequence() == event.outboxSequence()
        && checkpoint.sourceEventId().equals(event.eventId())
        && checkpoint.sourceEventDigest().equals(event.eventDigest());
  }

  private Map<String, Object> preimage(
      Identity identity,
      String requestId,
      String eventId,
      String streamKey,
      long sequence,
      ScopeState issuer,
      ScopeState account,
      ScopeState tenant,
      ScopeState membershipState,
      long issuanceFence,
      AccountTenantMembership membership,
      List<String> roles,
      boolean callerBoundAuthorityInvalidated) {
    Map<String, Object> authorityTuple = new LinkedHashMap<>();
    authorityTuple.put("issuerAuthGeneration", decimal(issuer.generation()));
    authorityTuple.put("accountAuthorityGeneration", decimal(account.generation()));
    authorityTuple.put(
        "tenantAuthorityGeneration",
        Map.of(identity.tenantUuid().toString(), decimal(tenant.generation())));
    authorityTuple.put(
        "membershipAuthorityGeneration",
        Map.of(identity.tenantUuid().toString(), decimal(membershipState.generation())));
    authorityTuple.put("privateRealmGrantVersions", List.of());

    Map<String, Object> event = new LinkedHashMap<>();
    event.put("schemaVersion", MembershipAuthorityEventV1Codec.SCHEMA_VERSION);
    event.put("eventType", MembershipAuthorityEventV1Codec.EVENT_TYPE);
    event.put("eventId", eventId);
    event.put("requestId", requestId);
    event.put("outboxStreamKey", streamKey);
    event.put("outboxSequence", decimal(sequence));
    event.put(
        "sourceScope",
        streamKey.substring(MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX.length()));
    event.put("accountId", identity.accountUuid().toString());
    event.put("tenantId", identity.tenantUuid().toString());
    event.put("membershipExists", true);
    event.put("membershipLifecycleState", membership.getLifecycleState());
    event.put("membershipVersion", decimal(membership.getMembershipVersion()));
    event.put(
        "membershipAuthorityGeneration", decimal(membership.getMembershipAuthorityGeneration()));
    event.put("authorityTuple", authorityTuple);
    event.put("issuanceFence", decimal(issuanceFence));
    event.put("roles", roles);
    event.put("gameplayAdmissionAllowed", membership.isGameplayAdmissionAllowed());
    event.put("callerBoundAuthorityInvalidated", callerBoundAuthorityInvalidated);
    return event;
  }

  private MembershipEvent verifyStoredEvent(Event event, Identity identity, String requestId) {
    if (!event.outboxStreamKey().equals(membershipStreamKey(identity))
        || !event.requestId().equals(requestId)) {
      throw new IllegalStateException(
          "Account authority event does not match its exact JOIN scope");
    }
    final MembershipEvent verified;
    try {
      verified =
          MembershipAuthorityEventV1Codec.verify(
              new String(event.payload(), StandardCharsets.UTF_8));
    } catch (RuntimeException exception) {
      throw new IllegalStateException("Stored Account authority event is invalid", exception);
    }
    if (!Arrays.equals(event.payload(), verified.canonicalJsonUtf8())
        || !verified.eventId().equals(event.eventId())
        || !verified.requestId().equals(requestId)
        || !verified.outboxStreamKey().equals(event.outboxStreamKey())
        || !verified.accountId().equals(identity.accountUuid().toString())
        || !verified.tenantId().equals(identity.tenantUuid().toString())
        || !verified.eventDigest().equals(event.eventDigest())
        || !Long.toString(event.outboxSequence()).equals(verified.outboxSequence())) {
      throw new IllegalStateException("Stored Account authority event identity or digest differs");
    }
    return verified;
  }

  private void requireNoUnmodeledCutoff(Identity identity) {
    Optional<Checkpoint> accountCutoff =
        authorityOutboxRepository.readCheckpoint(accountStreamKey(identity.accountUuid()));
    Optional<Checkpoint> tenantCutoff =
        authorityOutboxRepository.readCheckpoint(tenantStreamKey(identity.tenantUuid()));
    if (accountCutoff.isPresent() || tenantCutoff.isPresent()) {
      throw new IllegalStateException(
          "Account JOIN cannot form optional security or billing cutoff evidence from retained events");
    }
  }

  private RoleSnapshot requireRoleSnapshot(Identity identity, AccountTenantMembership membership) {
    if (membership.getId() == null || membership.getMembershipVersion() <= 0L) {
      throw new IllegalStateException("Account JOIN membership role identity is incomplete");
    }
    RoleSnapshot snapshot =
        roleSnapshotRepository
            .findForUpdate(
                identity.accountId(),
                identity.legacyTenantId(),
                membership.getId(),
                membership.getMembershipVersion())
            .orElseThrow(() -> new IllegalStateException("Account JOIN role snapshot is absent"));
    if (snapshot.accountId() != identity.accountId()
        || snapshot.tenantId() != identity.legacyTenantId()
        || snapshot.membershipId() != membership.getId()
        || snapshot.snapshotVersion() != membership.getMembershipVersion()
        || !snapshot.roles().contains("player")) {
      throw new IllegalStateException("Account JOIN role snapshot differs from its membership");
    }
    return snapshot;
  }

  private CompositeSnapshot readSnapshot(Identity identity) {
    return authorityGenerationRepository.readCompositeSnapshot(
        AccountServiceImpl.ACCOUNT_JWT_ISSUER,
        identity.accountUuid(),
        List.of(identity.tenantUuid()),
        List.of(identity.tenantUuid()));
  }

  private void requireMatchingFence(
      CompositeSnapshot snapshot, ScopeState membershipState, UUID accountUuid) {
    if (!accountUuid.equals(snapshot.issuanceFence().accountId())
        || !snapshot.issuanceFence().equals(snapshot.account().issuanceFence())
        || !snapshot.issuanceFence().equals(membershipState.issuanceFence())) {
      throw new IllegalStateException(
          "Account JOIN authority tuple has an incomplete issuance fence");
    }
  }

  private ScopeState only(List<ScopeState> scopes, String label) {
    if (scopes == null || scopes.size() != 1) {
      throw new IllegalStateException("Account JOIN authority snapshot has no exact " + label);
    }
    return scopes.getFirst();
  }

  private Identity resolveIdentity(long accountId, long legacyTenantId) {
    if (accountId <= 0L || legacyTenantId <= 0L) {
      throw new IllegalArgumentException("Account and retained tenant identities must be positive");
    }
    Account account =
        accountRepository
            .findById(accountId)
            .orElseThrow(() -> new IllegalStateException("JOIN Account row is absent"));
    if (account.getId() == null
        || account.getId() != accountId
        || account.getAccountUuid() == null
        || account.getAccountUuidProvenance() == null
        || account.getAccountUuidSourceNumericId() == null
        || account.getAccountUuidSourceNumericId() != accountId) {
      throw new IllegalStateException(
          "JOIN Account UUID does not exactly identify its persisted row");
    }
    var association = tenantIdentityResolver.resolve(legacyTenantId);
    if (association.legacyTenantId() != legacyTenantId || association.canonicalTenantId() == null) {
      throw new IllegalStateException(
          "JOIN retained tenant has no exact approved UUID association");
    }
    return new Identity(
        accountId, legacyTenantId, account.getAccountUuid(), association.canonicalTenantId());
  }

  private void requireMembershipIdentity(
      long accountId, long legacyTenantId, AccountTenantMembership membership) {
    if (membership == null
        || membership.getId() == null
        || membership.getId() <= 0L
        || membership.getAccount() == null
        || membership.getAccount().getId() == null
        || membership.getAccount().getId() != accountId
        || membership.getTenantId() != legacyTenantId) {
      throw new IllegalStateException("JOIN membership does not match its exact Account scope");
    }
  }

  private void requireActiveMembership(AccountTenantMembership membership) {
    if (membership.getMembershipVersion() <= 0L
        || membership.getMembershipAuthorityGeneration() <= 0L
        || !"ACTIVE".equals(membership.getLifecycleState())
        || !membership.isGameplayAdmissionAllowed()
        || !"EXPLICIT_JOIN".equals(membership.getAuthorityProvenance())) {
      throw new IllegalStateException("JOIN event requires a persisted active explicit membership");
    }
  }

  private String membershipStreamKey(Identity identity) {
    return MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX
        + "membership/"
        + identity.accountUuid()
        + "/"
        + identity.tenantUuid();
  }

  private String accountStreamKey(UUID accountUuid) {
    return MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX + "account/" + accountUuid;
  }

  private String tenantStreamKey(UUID tenantUuid) {
    return MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX + "tenant/" + tenantUuid;
  }

  private String eventIdForRequest(String requestId) {
    requirePositive(requestId, "JOIN request ID");
    return UUID.nameUUIDFromBytes(
            (MembershipAuthorityEventV1Codec.SCHEMA_VERSION + ":" + requestId)
                .getBytes(StandardCharsets.UTF_8))
        .toString();
  }

  private String decimal(long value) {
    if (value <= 0L) {
      throw new IllegalStateException("Account authority counters must be positive");
    }
    return Long.toString(value);
  }

  private long decrementPositive(long value) {
    if (value <= 1L) {
      throw new IllegalStateException("Reactivation requires prior positive membership history");
    }
    return value - 1L;
  }

  private void requirePositive(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
  }

  private record Identity(long accountId, long legacyTenantId, UUID accountUuid, UUID tenantUuid) {}
}
