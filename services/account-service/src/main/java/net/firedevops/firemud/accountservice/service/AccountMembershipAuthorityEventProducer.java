package net.firedevops.firemud.accountservice.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.MembershipTransitionReceipt;
import net.firedevops.firemud.accountservice.dto.MembershipTransitionReceiptDigest;
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
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository.JoinOperation;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.PairAuthority;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.PairTransition;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.ProvenPositiveCheckpoint;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.TenantProvenanceKind;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.VerifiedTenantProvenance;
import net.firedevops.firemud.accountservice.repository.AccountMembershipTransitionReceiptRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantIdentityResolver;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository.JoinMembershipProof;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository.RoleSnapshot;
import net.firedevops.firemud.accountservice.repository.ApprovedLegacyTenantAssociationRepository.ApprovedAssociation;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.AuthorityTuple;
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
  private final AccountJoinOperationRepository joinOperationRepository;
  private final AccountMembershipPairAuthorityRepository pairAuthorityRepository;
  private final AccountRepository accountRepository;
  private final AccountTenantIdentityResolver tenantIdentityResolver;
  private final AccountAuthorityGenerationRepository authorityGenerationRepository;
  private final AccountAuthorityOutboxRepository authorityOutboxRepository;
  private final AccountMembershipTransitionReceiptRepository transitionReceiptRepository;
  private final AccountTenantMembershipRepository membershipRepository;
  private final AccountTenantMembershipRoleSnapshotRepository roleSnapshotRepository;

  public AccountMembershipAuthorityEventProducer(
      AccountJoinOperationRepository joinOperationRepository,
      AccountMembershipPairAuthorityRepository pairAuthorityRepository,
      AccountRepository accountRepository,
      AccountTenantIdentityResolver tenantIdentityResolver,
      AccountAuthorityGenerationRepository authorityGenerationRepository,
      AccountAuthorityOutboxRepository authorityOutboxRepository,
      AccountMembershipTransitionReceiptRepository transitionReceiptRepository,
      AccountTenantMembershipRepository membershipRepository,
      AccountTenantMembershipRoleSnapshotRepository roleSnapshotRepository) {
    this.joinOperationRepository = joinOperationRepository;
    this.pairAuthorityRepository = pairAuthorityRepository;
    this.accountRepository = accountRepository;
    this.tenantIdentityResolver = tenantIdentityResolver;
    this.authorityGenerationRepository = authorityGenerationRepository;
    this.authorityOutboxRepository = authorityOutboxRepository;
    this.transitionReceiptRepository = transitionReceiptRepository;
    this.membershipRepository = membershipRepository;
    this.roleSnapshotRepository = roleSnapshotRepository;
  }

  /**
   * Reads a positive current membership snapshot under the same Account-row fence used by JOIN.
   *
   * <p>This deliberately does not produce sequence-zero absence evidence. A missing row, receipt,
   * role snapshot, generation tuple, event, or checkpoint is a denied snapshot.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public PositiveMembershipSnapshot readCurrentPositiveMembershipSnapshot(
      long accountId, long legacyTenantId) {
    if (accountId <= 0L || legacyTenantId <= 0L) {
      throw new IllegalArgumentException("Account and retained tenant identities must be positive");
    }

    // JOIN locks the Account row before the operation row and any membership evidence.
    joinOperationRepository.lockAccount(accountId);
    Identity identity = resolveIdentity(accountId, legacyTenantId);
    JoinMembershipProof membership =
        membershipRepository
            .findJoinProofForUpdate(accountId, legacyTenantId)
            .orElseThrow(
                () -> new IllegalStateException("Current Account membership row is absent"));
    if (membership.accountId() != accountId
        || membership.tenantId() != legacyTenantId
        || membership.membershipId() <= 0L
        || membership.membershipVersion() <= 0L
        || membership.membershipAuthorityGeneration() <= 0L
        || !"ACTIVE".equals(membership.lifecycleState())
        || !membership.gameplayAdmissionAllowed()
        || !"EXPLICIT_JOIN".equals(membership.authorityProvenance())) {
      throw new IllegalStateException(
          "Current Account membership is not a positive active explicit membership");
    }

    RoleSnapshot roles =
        roleSnapshotRepository
            .findForUpdate(
                accountId,
                legacyTenantId,
                membership.membershipId(),
                membership.membershipVersion())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Current Account membership role snapshot is absent"));
    List<String> exactRoles;
    try {
      exactRoles =
          AccountTenantMembershipRoleSnapshotRepository.requireCanonicalRoleSet(roles.roles());
    } catch (RuntimeException exception) {
      throw new IllegalStateException("Current Account role snapshot is not canonical", exception);
    }
    if (roles.accountId() != accountId
        || roles.tenantId() != legacyTenantId
        || roles.membershipId() != membership.membershipId()
        || roles.snapshotVersion() != membership.membershipVersion()
        || !exactRoles.contains("player")) {
      throw new IllegalStateException(
          "Current Account role snapshot differs from its exact active membership");
    }

    MembershipTransitionReceipt transitionReceipt =
        transitionReceiptRepository
            .findLatestReceipt(accountId, legacyTenantId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Current Account membership has no positive transition receipt"));
    if (!MembershipTransitionReceiptDigest.receiptStreamKey(accountId, legacyTenantId)
            .equals(transitionReceipt.receiptStreamKey())
        || transitionReceipt.receiptSequence() <= 0L
        || transitionReceipt.receiptId() == null
        || transitionReceipt.receiptDigest() == null
        || !MembershipTransitionReceiptDigest.EVIDENCE_STATUS.equals(
            transitionReceipt.evidenceStatus())
        || (!"MEMBERSHIP_JOINED".equals(transitionReceipt.transitionType())
            && !"MEMBERSHIP_REACTIVATED".equals(transitionReceipt.transitionType()))
        || transitionReceipt.requestId() == null
        || transitionReceipt.requestId().isBlank()
        || transitionReceipt.membershipId() != membership.membershipId()) {
      throw new IllegalStateException(
          "Current Account membership transition receipt is incomplete or mismatched");
    }

    CompositeSnapshot authoritySnapshot = readSnapshot(identity);
    ScopeState membershipAuthority = only(authoritySnapshot.memberships(), "membership");
    requireMatchingFence(authoritySnapshot, membershipAuthority, identity.accountUuid());
    if (membershipAuthority.generation() != membership.membershipAuthorityGeneration()) {
      throw new IllegalStateException(
          "Current Account membership differs from its V31 authority generation");
    }

    String streamKey = membershipStreamKey(identity);
    Checkpoint checkpoint =
        authorityOutboxRepository
            .readCheckpoint(streamKey)
            .orElseThrow(
                () ->
                    new IllegalStateException("Current Account membership has no V33 checkpoint"));
    if (checkpoint.outboxSequence() <= 0L) {
      throw new IllegalStateException("Current Account membership checkpoint is not positive");
    }
    Event event =
        authorityOutboxRepository
            .findEvent(streamKey, checkpoint.outboxSequence())
            .orElseThrow(
                () -> new IllegalStateException("Current Account V33 checkpoint has no event"));
    if (!checkpointMatches(checkpoint, event)) {
      throw new IllegalStateException(
          "Current Account membership checkpoint differs from its event");
    }
    MembershipEvent verified = verifyStoredEvent(event, identity, event.requestId());
    if (!transitionReceipt.requestId().equals(verified.requestId())) {
      throw new IllegalStateException(
          "Current Account membership receipt differs from its latest V33 event");
    }
    requireCurrentMembershipEvent(
        identity,
        membership.membershipId(),
        membership.lifecycleState(),
        membership.gameplayAdmissionAllowed(),
        membership.membershipVersion(),
        membership.membershipAuthorityGeneration(),
        membership.authorityProvenance(),
        roles,
        transitionReceipt.requestId(),
        "MEMBERSHIP_REACTIVATED".equals(transitionReceipt.transitionType()),
        authoritySnapshot);

    return new PositiveMembershipSnapshot(
        true,
        verified.accountId(),
        verified.tenantId(),
        verified.membershipLifecycleState(),
        verified.gameplayAdmissionAllowed(),
        verified.membershipVersion(),
        verified.membershipAuthorityGeneration(),
        verified.roles(),
        verified.authorityTuple(),
        verified.issuanceFence(),
        Instant.now(),
        List.of(checkpoint),
        transitionReceipt,
        verified);
  }

  /**
   * Preserves an existing active row's exact positive counters and event identity in V36. This is
   * not a sequence-zero backfill: absent, inactive, or contradictory retained state stays denied.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public PositiveMembershipSnapshot enrollProvenRetainedActivePair(
      long accountId, long legacyTenantId) {
    PositiveMembershipSnapshot positive =
        readCurrentPositiveMembershipSnapshot(accountId, legacyTenantId);
    Identity identity = resolveIdentity(accountId, legacyTenantId);
    MembershipEvent event = positive.authorityEvent();
    pairAuthorityRepository.enrollProvenPositive(
        identity.accountUuid(),
        identity.tenantUuid(),
        verifiedProvenance(identity),
        new ProvenPositiveCheckpoint(
            Long.parseLong(positive.membershipVersion()),
            Long.parseLong(positive.membershipAuthorityGeneration()),
            Long.parseLong(event.outboxSequence()),
            event.eventId(),
            event.eventDigest(),
            event.callerBoundAuthorityInvalidated()));
    return positive;
  }

  /** Requires the V36 pair row to agree with one current positive Account snapshot. */
  @Transactional(propagation = Propagation.MANDATORY)
  public PositiveMembershipSnapshot readCurrentPairBoundPositiveMembershipSnapshot(
      long accountId, long legacyTenantId) {
    PositiveMembershipSnapshot positive =
        readCurrentPositiveMembershipSnapshot(accountId, legacyTenantId);
    Identity identity = resolveIdentity(accountId, legacyTenantId);
    PairAuthority pair =
        pairAuthorityRepository
            .readForUpdate(identity.accountUuid(), identity.tenantUuid())
            .orElseThrow(
                () -> new IllegalStateException("Current membership pair authority is absent"));
    MembershipEvent event = positive.authorityEvent();
    if (!verifiedProvenance(identity).equals(pair.provenance())
        || !pair.membershipExists()
        || pair.membershipVersion() != Long.parseLong(positive.membershipVersion())
        || pair.membershipAuthorityGeneration()
            != Long.parseLong(positive.membershipAuthorityGeneration())
        || pair.lastEventSequence() != Long.parseLong(event.outboxSequence())
        || !event.eventId().equals(pair.lastEventId())
        || !event.eventDigest().equals(pair.lastEventDigest())
        || pair.lastTransitionInvalidated() != event.callerBoundAuthorityInvalidated()) {
      throw new IllegalStateException(
          "Current Account membership differs from its durable pair authority");
    }
    return positive;
  }

  /**
   * Reads an explicit sequence-zero checkpoint only from a durable verified pair baseline, after
   * the no-membership and no-committed-history proof under one Account-row fence. It never admits
   * gameplay and is not exposed through the still-denied runtime membership RPC.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public NeverJoinedMembershipSnapshot readNeverJoinedMembershipSnapshot(
      long accountId, long legacyTenantId) {
    joinOperationRepository.lockAccount(accountId);
    Identity identity = resolveIdentity(accountId, legacyTenantId);
    if (membershipRepository.findJoinProofForUpdate(accountId, legacyTenantId).isPresent()) {
      throw new IllegalStateException("Never-joined Account membership row is not absent");
    }
    transitionReceiptRepository.assertNewMembershipTransitionCanStart(accountId, legacyTenantId);
    String streamKey = membershipStreamKey(identity);
    if (authorityOutboxRepository.readCheckpoint(streamKey).isPresent()) {
      throw new IllegalStateException(
          "Never-joined Account membership has committed event history");
    }
    PairAuthority pair =
        pairAuthorityRepository
            .readForUpdate(identity.accountUuid(), identity.tenantUuid())
            .orElseThrow(() -> new IllegalStateException("Never-joined pair baseline is absent"));
    CompositeSnapshot authority = readSnapshot(identity);
    ScopeState member = only(authority.memberships(), "membership");
    requireMatchingFence(authority, member, identity.accountUuid());
    requireNoUnmodeledCutoff(identity);
    if (!verifiedProvenance(identity).equals(pair.provenance())
        || pair.membershipExists()
        || pair.lastEventSequence() != 0L
        || pair.membershipVersion() != 1L
        || pair.membershipAuthorityGeneration() != member.generation()) {
      throw new IllegalStateException(
          "Never-joined Account membership differs from its positive pair baseline");
    }
    AuthorityTuple tuple =
        new AuthorityTuple(
            decimal(authority.issuer().generation()),
            decimal(authority.account().generation()),
            Map.of(
                identity.tenantUuid().toString(),
                decimal(only(authority.tenants(), "tenant").generation())),
            Map.of(identity.tenantUuid().toString(), decimal(member.generation())),
            List.of(),
            Optional.empty(),
            Optional.empty());
    return new NeverJoinedMembershipSnapshot(
        identity.accountUuid().toString(),
        identity.tenantUuid().toString(),
        decimal(pair.membershipVersion()),
        decimal(pair.membershipAuthorityGeneration()),
        tuple,
        decimal(authority.issuanceFence().value()),
        Instant.now(),
        streamKey);
  }

  /**
   * Establishes a committed never-joined baseline before a separate JOIN transaction may consume
   * it. A retained active row is enrolled only from its exact current positive event/receipt proof;
   * inactive or contradictory retained rows remain denied rather than being assigned sequence zero.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void preparePairAuthorityForJoin(long accountId, long legacyTenantId) {
    joinOperationRepository.lockAccount(accountId);
    Identity identity = resolveIdentity(accountId, legacyTenantId);
    if (membershipRepository.findJoinProofForUpdate(accountId, legacyTenantId).isPresent()) {
      // Retained rows are not eligible for an absence baseline. Their existing JOIN/readback
      // behavior stays intact until a separate exact positive-history enrollment is proven.
      return;
    }

    transitionReceiptRepository.assertNewMembershipTransitionCanStart(accountId, legacyTenantId);
    if (authorityOutboxRepository.readCheckpoint(membershipStreamKey(identity)).isPresent()) {
      throw new IllegalStateException(
          "Absent Account membership has retained authority-event history");
    }
    Optional<PairAuthority> existing =
        pairAuthorityRepository.readForUpdate(identity.accountUuid(), identity.tenantUuid());
    if (existing.isEmpty()) {
      ScopeState generation =
          authorityGenerationRepository.initialize(
              AuthorityScope.membership(identity.accountUuid(), identity.tenantUuid()));
      if (generation.generation() != 1L) {
        throw new IllegalStateException(
            "Never-joined Account membership generation did not initialize at one");
      }
    }
    PairAuthority pair =
        pairAuthorityRepository.enrollAbsence(
            identity.accountUuid(), identity.tenantUuid(), verifiedProvenance(identity));
    CompositeSnapshot snapshot = readSnapshot(identity);
    ScopeState member = only(snapshot.memberships(), "membership");
    requireMatchingFence(snapshot, member, identity.accountUuid());
    if (pair.membershipExists()
        || pair.lastEventSequence() != 0L
        || pair.membershipVersion() != 1L
        || pair.membershipAuthorityGeneration() != member.generation()
        || pair.membershipAuthorityGeneration() != 1L) {
      throw new IllegalStateException(
          "Absent Account membership differs from its durable pair authority baseline");
    }
  }

  /** Requires the previously committed never-joined baseline under the JOIN account fence. */
  @Transactional(propagation = Propagation.MANDATORY)
  public NewMembershipBaseline requireNewMembershipBaseline(long accountId, long legacyTenantId) {
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
    PairAuthority pair =
        pairAuthorityRepository
            .readForUpdate(identity.accountUuid(), identity.tenantUuid())
            .orElseThrow(
                () -> new IllegalStateException("Never-joined pair authority is not enrolled"));
    CompositeSnapshot snapshot = readSnapshot(identity);
    ScopeState member = only(snapshot.memberships(), "membership");
    requireMatchingFence(snapshot, member, identity.accountUuid());
    if (!verifiedProvenance(identity).equals(pair.provenance())
        || pair.membershipExists()
        || pair.lastEventSequence() != 0L
        || pair.membershipVersion() != 1L
        || pair.membershipAuthorityGeneration() != member.generation()
        || member.generation() != 1L) {
      throw new IllegalStateException(
          "New Account membership differs from its committed absence baseline");
    }
    return new NewMembershipBaseline(
        pair.membershipVersion(), pair.membershipAuthorityGeneration());
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

  /**
   * Requires the latest checkpoint event to prove the exact current retained membership state.
   * This read may fail closed during JOIN/reconciliation without undoing a caller's retry receipt.
   */
  @Transactional(
      propagation = Propagation.MANDATORY,
      readOnly = true,
      noRollbackFor = IllegalStateException.class)
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

  /** Reconciliation proof variant for locked membership and role readback records. */
  @Transactional(
      propagation = Propagation.MANDATORY,
      readOnly = true,
      noRollbackFor = IllegalStateException.class)
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
    if (membership.getMembershipVersion() != 2L
        || membership.getMembershipAuthorityGeneration() != 1L) {
      throw new IllegalStateException(
          "New Account membership must advance baseline version one to two without invalidation");
    }
    PairAuthority absenceBaseline =
        pairAuthorityRepository
            .readForUpdate(identity.accountUuid(), identity.tenantUuid())
            .orElseThrow(() -> new IllegalStateException("New membership has no absence baseline"));
    if (!verifiedProvenance(identity).equals(absenceBaseline.provenance())
        || absenceBaseline.membershipExists()
        || absenceBaseline.membershipVersion() != 1L
        || absenceBaseline.membershipAuthorityGeneration() != 1L
        || absenceBaseline.lastEventSequence() != 0L) {
      throw new IllegalStateException("New membership differs from its exact absence baseline");
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
    Checkpoint checkpoint =
        appendAndReadBack(identity, requestId, membership, snapshot, false, true);
    PairAuthority committed =
        pairAuthorityRepository.commitTransition(
            absenceBaseline,
            new PairTransition(
                true,
                checkpoint.outboxSequence(),
                checkpoint.sourceEventId(),
                checkpoint.sourceEventDigest(),
                false));
    if (checkpoint.outboxSequence() != 1L
        || committed.membershipVersion() != membership.getMembershipVersion()
        || committed.membershipAuthorityGeneration()
            != membership.getMembershipAuthorityGeneration()) {
      throw new IllegalStateException("First JOIN pair authority readback differs from its event");
    }
    return checkpoint;
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
    PairAuthority priorPair =
        pairAuthorityRepository
            .readForUpdate(identity.accountUuid(), identity.tenantUuid())
            .orElseThrow(
                () -> new IllegalStateException("Reactivated membership has no pair authority"));
    if (!verifiedProvenance(identity).equals(priorPair.provenance())
        || !priorPair.membershipExists()
        || priorPair.membershipVersion() != membership.getMembershipVersion() - 1L
        || priorPair.membershipAuthorityGeneration() != expectedPriorGeneration
        || priorPair.lastEventSequence() <= 0L) {
      throw new IllegalStateException(
          "Reactivated membership differs from its prior pair authority");
    }
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
    Checkpoint checkpoint =
        appendAndReadBack(identity, requestId, membership, committedCandidate, true, false);
    PairAuthority committed =
        pairAuthorityRepository.commitTransition(
            priorPair,
            new PairTransition(
                true,
                checkpoint.outboxSequence(),
                checkpoint.sourceEventId(),
                checkpoint.sourceEventDigest(),
                true));
    if (committed.membershipVersion() != membership.getMembershipVersion()
        || committed.membershipAuthorityGeneration()
            != membership.getMembershipAuthorityGeneration()) {
      throw new IllegalStateException(
          "Reactivated membership pair authority readback differs from its event");
    }
    return checkpoint;
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
    ApprovedAssociation association = tenantIdentityResolver.resolve(legacyTenantId);
    if (association.legacyTenantId() != legacyTenantId || association.canonicalTenantId() == null) {
      throw new IllegalStateException(
          "JOIN retained tenant has no exact approved UUID association");
    }
    return new Identity(
        accountId,
        legacyTenantId,
        account.getAccountUuid(),
        association.canonicalTenantId(),
        association);
  }

  private VerifiedTenantProvenance verifiedProvenance(Identity identity) {
    ApprovedAssociation association = identity.association();
    return new VerifiedTenantProvenance(
        identity.legacyTenantId(),
        TenantProvenanceKind.APPROVED_RETAINED,
        association.operationId(),
        association.manifestDigest());
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

  /** Positive durable authority values committed before the first membership transition. */
  public record NewMembershipBaseline(long membershipVersion, long membershipAuthorityGeneration) {
    public NewMembershipBaseline {
      if (membershipVersion <= 0L || membershipAuthorityGeneration <= 0L) {
        throw new IllegalArgumentException(
            "Never-joined Account authority baseline must be positive");
      }
    }
  }

  /** Sequence zero carries no event identity/digest and never grants gameplay admission. */
  public record NeverJoinedMembershipSnapshot(
      String accountId,
      String tenantId,
      String membershipVersion,
      String membershipAuthorityGeneration,
      AuthorityTuple authorityTuple,
      String issuanceFence,
      Instant evaluatedAt,
      String outboxStreamKey) {
    public NeverJoinedMembershipSnapshot {
      Objects.requireNonNull(accountId);
      Objects.requireNonNull(tenantId);
      Objects.requireNonNull(membershipVersion);
      Objects.requireNonNull(membershipAuthorityGeneration);
      Objects.requireNonNull(authorityTuple);
      Objects.requireNonNull(issuanceFence);
      Objects.requireNonNull(evaluatedAt);
      Objects.requireNonNull(outboxStreamKey);
    }

    public long outboxSequence() {
      return 0L;
    }

    public boolean membershipExists() {
      return false;
    }

    public boolean gameplayAdmissionAllowed() {
      return false;
    }
  }

  /** Immutable positive membership evidence assembled from one fenced Account transaction. */
  public record PositiveMembershipSnapshot(
      boolean membershipExists,
      String accountId,
      String tenantId,
      String membershipLifecycleState,
      boolean gameplayAdmissionAllowed,
      String membershipVersion,
      String membershipAuthorityGeneration,
      List<String> roles,
      AuthorityTuple authorityTuple,
      String issuanceFence,
      Instant evaluatedAt,
      List<Checkpoint> outboxCheckpoints,
      MembershipTransitionReceipt transitionReceipt,
      MembershipEvent authorityEvent) {
    public PositiveMembershipSnapshot {
      Objects.requireNonNull(accountId, "canonical Account UUID is required");
      Objects.requireNonNull(tenantId, "canonical tenant UUID is required");
      Objects.requireNonNull(membershipLifecycleState, "membership lifecycle is required");
      Objects.requireNonNull(membershipVersion, "membership version is required");
      Objects.requireNonNull(
          membershipAuthorityGeneration, "membership authority generation is required");
      roles = List.copyOf(roles);
      Objects.requireNonNull(authorityTuple, "complete authority tuple is required");
      Objects.requireNonNull(issuanceFence, "Account issuance fence is required");
      Objects.requireNonNull(evaluatedAt, "Account evaluation time is required");
      outboxCheckpoints = List.copyOf(outboxCheckpoints);
      Objects.requireNonNull(transitionReceipt, "membership transition receipt is required");
      Objects.requireNonNull(authorityEvent, "canonical membership authority event is required");

      if (!membershipExists
          || !"ACTIVE".equals(membershipLifecycleState)
          || !gameplayAdmissionAllowed
          || !accountId.equals(authorityEvent.accountId())
          || !tenantId.equals(authorityEvent.tenantId())
          || !membershipLifecycleState.equals(authorityEvent.membershipLifecycleState())
          || !membershipVersion.equals(authorityEvent.membershipVersion())
          || !membershipAuthorityGeneration.equals(authorityEvent.membershipAuthorityGeneration())
          || !roles.equals(authorityEvent.roles())
          || !authorityTuple.equals(authorityEvent.authorityTuple())
          || !issuanceFence.equals(authorityEvent.issuanceFence())
          || !membershipAuthorityGeneration.equals(
              authorityTuple.membershipAuthorityGeneration().get(tenantId))
          || outboxCheckpoints.size() != 1
          || !outboxCheckpoints.contains(
              new Checkpoint(
                  authorityEvent.outboxStreamKey(),
                  Long.parseLong(authorityEvent.outboxSequence()),
                  authorityEvent.eventId(),
                  authorityEvent.eventDigest()))
          || !transitionReceipt.requestId().equals(authorityEvent.requestId())) {
        throw new IllegalArgumentException(
            "Positive Account membership snapshot evidence is internally inconsistent");
      }
    }
  }

  private record Identity(
      long accountId,
      long legacyTenantId,
      UUID accountUuid,
      UUID tenantUuid,
      ApprovedAssociation association) {}
}
