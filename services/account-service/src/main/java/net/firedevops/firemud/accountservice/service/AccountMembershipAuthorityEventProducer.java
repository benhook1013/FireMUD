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
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.MembershipTransitionReceipt;
import net.firedevops.firemud.accountservice.dto.MembershipTransitionReceiptDigest;
import net.firedevops.firemud.accountservice.dto.RuntimeMembershipSnapshotDto;
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
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.AuthorityTuple;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.MembershipEvent;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Coordinates Account-owned membership authority events and fenced snapshot evidence. */
@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected repositories are internal Spring collaborators.")
public class AccountMembershipAuthorityEventProducer {
  private static final Comparator<OutboxCheckpointEntry> OUTBOX_CHECKPOINT_ORDER =
      Comparator.comparing(
          OutboxCheckpointEntry::outboxStreamKey,
          AccountMembershipAuthorityEventProducer::compareUnsignedUtf8);

  private final AccountJoinOperationRepository joinOperationRepository;
  private final AccountMembershipPairAuthorityRepository pairAuthorityRepository;
  private final AccountRepository accountRepository;
  private final AccountTenantIdentityResolver tenantIdentityResolver;
  private final FreshTenantIdentityAssociationRepository freshTenantIdentityAssociationRepository;
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
      FreshTenantIdentityAssociationRepository freshTenantIdentityAssociationRepository,
      AccountAuthorityGenerationRepository authorityGenerationRepository,
      AccountAuthorityOutboxRepository authorityOutboxRepository,
      AccountMembershipTransitionReceiptRepository transitionReceiptRepository,
      AccountTenantMembershipRepository membershipRepository,
      AccountTenantMembershipRoleSnapshotRepository roleSnapshotRepository) {
    this.joinOperationRepository = joinOperationRepository;
    this.pairAuthorityRepository = pairAuthorityRepository;
    this.accountRepository = accountRepository;
    this.tenantIdentityResolver = tenantIdentityResolver;
    this.freshTenantIdentityAssociationRepository = freshTenantIdentityAssociationRepository;
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
    List<OutboxCheckpointEntry> upstreamCheckpoints =
        readBaselineUpstreamCheckpoints(identity, authoritySnapshot);

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

    List<OutboxCheckpointEntry> checkpoints = new ArrayList<>(upstreamCheckpoints);
    checkpoints.add(
        new OutboxCheckpointEntry(
            checkpoint.outboxStreamKey(), Long.toString(checkpoint.outboxSequence())));
    checkpoints = orderedCheckpoints(checkpoints);
    return new PositiveMembershipSnapshot(
        true,
        verified.accountId(),
        verified.tenantId(),
        verified.membershipLifecycleState(),
        verified.gameplayAdmissionAllowed(),
        membershipVersionMap(verified, identity),
        verified.membershipAuthorityGeneration(),
        verified.roles(),
        verified.authorityTuple(),
        verified.issuanceFence(),
        Instant.now(),
        checkpoints,
        List.of(sourceEvidence(checkpoint, event)),
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
            Long.parseLong(
                membershipVersionValue(
                    positive.membershipVersion(), identity.tenantUuid().toString())),
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
        || pair.membershipVersion()
            != Long.parseLong(
                membershipVersionValue(
                    positive.membershipVersion(), identity.tenantUuid().toString()))
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
   * Reads exact current retained INACTIVE evidence without enrolling or authorizing the pair. The
   * positive immutable receipt, closed event, current generation/fence and existing pair row must
   * all agree under the same Account owner-row fence.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public RuntimeMembershipSnapshotDto readCurrentPairBoundInactiveMembershipSnapshot(
      long accountId, long legacyTenantId) {
    requireActiveOwnerTransaction();
    if (accountId <= 0L || legacyTenantId <= 0L) {
      throw new IllegalArgumentException("Account and retained tenant identities must be positive");
    }
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
        || !"INACTIVE".equals(membership.lifecycleState())
        || membership.gameplayAdmissionAllowed()
        || !"EXPLICIT_JOIN".equals(membership.authorityProvenance())) {
      throw new IllegalStateException(
          "Current Account membership is not an explicit retained inactive membership");
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
        || roles.snapshotVersion() != membership.membershipVersion()) {
      throw new IllegalStateException(
          "Current Account role snapshot differs from its exact inactive membership");
    }

    MembershipTransitionReceipt receipt =
        transitionReceiptRepository
            .findLatestReceipt(accountId, legacyTenantId)
            .orElseThrow(
                () -> new IllegalStateException("Current inactive membership receipt is absent"));
    if (!MembershipTransitionReceiptDigest.receiptStreamKey(accountId, legacyTenantId)
            .equals(receipt.receiptStreamKey())
        || receipt.receiptSequence() <= 0L
        || receipt.receiptId() == null
        || receipt.receiptDigest() == null
        || !MembershipTransitionReceiptDigest.EVIDENCE_STATUS.equals(receipt.evidenceStatus())
        || !"MEMBERSHIP_LEFT".equals(receipt.transitionType())
        || receipt.requestId() == null
        || receipt.requestId().isBlank()
        || receipt.membershipId() != membership.membershipId()) {
      throw new IllegalStateException(
          "Current inactive membership lacks its exact positive LEFT receipt");
    }

    CompositeSnapshot authoritySnapshot = readSnapshot(identity);
    ScopeState membershipAuthority = only(authoritySnapshot.memberships(), "membership");
    requireMatchingFence(authoritySnapshot, membershipAuthority, identity.accountUuid());
    if (membershipAuthority.generation() != membership.membershipAuthorityGeneration()) {
      throw new IllegalStateException(
          "Current inactive membership differs from its V31 authority generation");
    }
    List<OutboxCheckpointEntry> upstreamCheckpoints =
        readBaselineUpstreamCheckpoints(identity, authoritySnapshot);
    String streamKey = membershipStreamKey(identity);
    Checkpoint checkpoint =
        authorityOutboxRepository
            .readCheckpoint(streamKey)
            .orElseThrow(
                () ->
                    new IllegalStateException("Current inactive membership has no V33 checkpoint"));
    if (checkpoint.outboxSequence() <= 0L) {
      throw new IllegalStateException("Current inactive membership checkpoint is not positive");
    }
    Event event =
        authorityOutboxRepository
            .findEvent(streamKey, checkpoint.outboxSequence())
            .orElseThrow(
                () -> new IllegalStateException("Current inactive checkpoint has no event"));
    if (!checkpointMatches(checkpoint, event)) {
      throw new IllegalStateException("Current inactive checkpoint differs from its event");
    }
    MembershipEvent verified = verifyStoredEvent(event, identity, event.requestId());
    if (!receipt.requestId().equals(verified.requestId())) {
      throw new IllegalStateException("Current inactive receipt differs from its latest event");
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
        receipt.requestId(),
        true,
        authoritySnapshot);

    PairAuthority pair =
        pairAuthorityRepository
            .readForUpdate(identity.accountUuid(), identity.tenantUuid())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Current inactive membership pair authority is absent"));
    if (!verifiedProvenance(identity).equals(pair.provenance())
        || !pair.membershipExists()
        || pair.membershipVersion() != membership.membershipVersion()
        || pair.membershipAuthorityGeneration() != membership.membershipAuthorityGeneration()
        || pair.lastEventSequence() != checkpoint.outboxSequence()
        || !checkpoint.sourceEventId().equals(pair.lastEventId())
        || !checkpoint.sourceEventDigest().equals(pair.lastEventDigest())
        || !pair.lastTransitionInvalidated()) {
      throw new IllegalStateException(
          "Current inactive membership differs from its durable pair authority");
    }

    List<OutboxCheckpointEntry> checkpoints = new ArrayList<>(upstreamCheckpoints);
    checkpoints.add(
        new OutboxCheckpointEntry(streamKey, Long.toString(checkpoint.outboxSequence())));
    return new RuntimeMembershipSnapshotDto(
        identity.accountUuid().toString(),
        identity.tenantUuid().toString(),
        identity.accountUuid().toString(),
        identity.tenantUuid().toString(),
        true,
        false,
        new RuntimeMembershipSnapshotDto.MembershipBaseline(
            "INACTIVE",
            membershipVersionMap(verified, identity),
            verified.membershipAuthorityGeneration()),
        exactRoles,
        verified.authorityTuple(),
        verified.issuanceFence(),
        Instant.now(),
        orderedCheckpoints(checkpoints),
        List.of(sourceEvidence(checkpoint, event)),
        verified);
  }

  /**
   * Reads only an already-existing positive membership for exact canonical Account and tenant
   * UUIDs, under the Account owner transaction and row fence.
   *
   * <p>This is membership evidence, not complete current authorization or terminal-recovery
   * eligibility. It never prepares, enrolls, initializes, or transitions membership authority.
   * Fresh tenant associations currently have only non-admitting membership state and are denied;
   * retained membership must have complete exact current row, role, receipt, event, pair, and
   * generation/fence readback. Missing, inactive, incomplete, or contradictory state fails closed.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public PositiveMembershipSnapshot readExistingPairBoundPositiveMembershipSnapshot(
      UUID accountUuid, UUID tenantUuid) {
    requireActiveOwnerTransaction();
    requireCanonicalUuidInput(accountUuid, "Account UUID");
    requireCanonicalUuidInput(tenantUuid, "tenant UUID");

    Account initialAccount =
        accountRepository
            .findByAccountUuid(accountUuid)
            .orElseThrow(
                () -> new IllegalStateException("Membership snapshot Account row is absent"));
    requirePersistedAccountIdentity(initialAccount, accountUuid);
    long accountId = initialAccount.getId();

    // Match JOIN's lock order: the numeric Account row is private fence provenance, never a
    // request identity or tenant alias. Re-read its immutable UUID mapping after acquiring it.
    joinOperationRepository.lockAccount(accountId);
    Account fencedAccount =
        accountRepository
            .findByAccountUuid(accountUuid)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Membership snapshot Account row disappeared at its fence"));
    requirePersistedAccountIdentity(fencedAccount, accountUuid);
    if (!Objects.equals(initialAccount.getId(), fencedAccount.getId())
        || initialAccount.getAccountUuidProvenance() != fencedAccount.getAccountUuidProvenance()
        || !Objects.equals(
            initialAccount.getAccountUuidSourceNumericId(),
            fencedAccount.getAccountUuidSourceNumericId())) {
      throw new IllegalStateException(
          "Membership snapshot Account identity changed at its row fence");
    }

    Optional<FreshTenantCreationEvidence> freshAssociation =
        freshTenantIdentityAssociationRepository.read(tenantUuid);
    if (freshAssociation.isPresent()) {
      if (!tenantUuid.equals(freshAssociation.orElseThrow().canonicalTenantId())) {
        throw new IllegalStateException(
            "Fresh tenant association differs from its canonical UUID scope");
      }
      throw new IllegalStateException(
          "Fresh tenant membership has no positive current Account membership readback");
    }

    ApprovedAssociation retainedAssociation = tenantIdentityResolver.resolve(tenantUuid);
    if (retainedAssociation.legacyTenantId() <= 0L
        || !tenantUuid.equals(retainedAssociation.canonicalTenantId())) {
      throw new IllegalStateException(
          "Retained tenant UUID has no exact approved private-row association");
    }

    PositiveMembershipSnapshot positive =
        readCurrentPairBoundPositiveMembershipSnapshot(
            accountId, retainedAssociation.legacyTenantId());
    if (!accountUuid.toString().equals(positive.accountId())
        || !tenantUuid.toString().equals(positive.tenantId())) {
      throw new IllegalStateException(
          "Current Account membership differs from its canonical UUID request");
    }

    // Recheck both identity sources after reading membership history. The Account lock remains
    // held, and the resolver repeats its current retained-row digest check.
    Account finalAccount =
        accountRepository
            .findByAccountUuid(accountUuid)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Membership snapshot Account row disappeared before readback"));
    requirePersistedAccountIdentity(finalAccount, accountUuid);
    if (!Objects.equals(initialAccount.getId(), finalAccount.getId())
        || initialAccount.getAccountUuidProvenance() != finalAccount.getAccountUuidProvenance()
        || !Objects.equals(
            initialAccount.getAccountUuidSourceNumericId(),
            finalAccount.getAccountUuidSourceNumericId())
        || !retainedAssociation.equals(tenantIdentityResolver.resolve(tenantUuid))) {
      throw new IllegalStateException(
          "Canonical Account or retained tenant identity changed during membership readback");
    }
    return positive;
  }

  /**
   * Reads one same-fence runtime membership result from the proved active-positive or never-joined
   * sequence-zero path.
   *
   * <p>This deliberately has no inactive, unproved, or synthesized fallback. The caller owns the
   * Account transaction and the RPC remains unavailable until its consumer validates this whole
   * evidence bundle.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public RuntimeMembershipSnapshotDto readRuntimeMembershipSnapshot(
      long accountId, long legacyTenantId) {
    if (accountId <= 0L || legacyTenantId <= 0L) {
      throw new IllegalArgumentException("Account and retained tenant identities must be positive");
    }
    preparePairAuthorityForRuntimeSnapshot(accountId, legacyTenantId);
    Optional<JoinMembershipProof> currentMembership =
        membershipRepository.findJoinProofForUpdate(accountId, legacyTenantId);
    if (currentMembership.isPresent()
        && "INACTIVE".equals(currentMembership.orElseThrow().lifecycleState())) {
      return readCurrentPairBoundInactiveMembershipSnapshot(accountId, legacyTenantId);
    }
    if (currentMembership.isPresent()) {
      PositiveMembershipSnapshot positive =
          readCurrentPairBoundPositiveMembershipSnapshot(accountId, legacyTenantId);
      return new RuntimeMembershipSnapshotDto(
          positive.accountId(),
          positive.tenantId(),
          positive.accountId(),
          positive.tenantId(),
          positive.membershipExists(),
          positive.gameplayAdmissionAllowed(),
          new RuntimeMembershipSnapshotDto.MembershipBaseline(
              positive.membershipLifecycleState(),
              positive.membershipVersion(),
              positive.membershipAuthorityGeneration()),
          positive.roles(),
          positive.authorityTuple(),
          positive.issuanceFence(),
          positive.evaluatedAt(),
          positive.outboxCheckpoints(),
          positive.outboxSourceEvidence(),
          positive.authorityEvent());
    }

    NeverJoinedMembershipSnapshot absent =
        readNeverJoinedMembershipSnapshot(accountId, legacyTenantId);
    return new RuntimeMembershipSnapshotDto(
        absent.accountId(),
        absent.tenantId(),
        absent.accountId(),
        absent.tenantId(),
        absent.membershipExists(),
        absent.gameplayAdmissionAllowed(),
        new RuntimeMembershipSnapshotDto.MembershipBaseline(
            absent.membershipLifecycleState(),
            absent.membershipVersion(),
            absent.membershipAuthorityGeneration()),
        absent.roles(),
        absent.authorityTuple(),
        absent.issuanceFence(),
        absent.evaluatedAt(),
        absent.outboxCheckpoints(),
        absent.outboxSourceEvidence(),
        null);
  }

  /**
   * Resolves canonical Account and tenant UUIDs to one owner-fenced runtime membership snapshot.
   * Fresh tenants use only their immutable V38 association and never receive a numeric alias;
   * retained tenants must resolve through the exact approved UUID association and source digest.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public RuntimeMembershipSnapshotDto readRuntimeMembershipSnapshot(
      UUID accountUuid, UUID tenantUuid) {
    requireActiveOwnerTransaction();
    requireCanonicalUuidInput(accountUuid, "Account UUID");
    requireCanonicalUuidInput(tenantUuid, "tenant UUID");

    Account initialAccount =
        accountRepository
            .findByAccountUuid(accountUuid)
            .orElseThrow(() -> new IllegalStateException("Runtime snapshot Account row is absent"));
    requirePersistedAccountIdentity(initialAccount, accountUuid);
    long accountId = initialAccount.getId();

    // The persisted Account row is the owner fence for every subsequent identity/history read.
    joinOperationRepository.lockAccount(accountId);
    Account fencedAccount =
        accountRepository
            .findByAccountUuid(accountUuid)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Runtime snapshot Account row disappeared at its fence"));
    requirePersistedAccountIdentity(fencedAccount, accountUuid);
    if (!Objects.equals(initialAccount.getId(), fencedAccount.getId())
        || initialAccount.getAccountUuidProvenance() != fencedAccount.getAccountUuidProvenance()
        || !Objects.equals(
            initialAccount.getAccountUuidSourceNumericId(),
            fencedAccount.getAccountUuidSourceNumericId())) {
      throw new IllegalStateException("Runtime snapshot Account identity changed at its row fence");
    }

    Optional<FreshTenantCreationEvidence> freshAssociation =
        freshTenantIdentityAssociationRepository.read(tenantUuid);
    if (freshAssociation.isPresent()) {
      NeverJoinedMembershipSnapshot absent =
          readFreshNeverJoinedMembershipSnapshot(accountUuid, tenantUuid);
      return toRuntimeMembershipSnapshot(absent);
    }

    ApprovedAssociation retainedAssociation = tenantIdentityResolver.resolve(tenantUuid);
    if (retainedAssociation.legacyTenantId() <= 0L
        || !tenantUuid.equals(retainedAssociation.canonicalTenantId())) {
      throw new IllegalStateException(
          "Retained tenant UUID has no exact private numeric association");
    }
    RuntimeMembershipSnapshotDto retained =
        readRuntimeMembershipSnapshot(accountId, retainedAssociation.legacyTenantId());
    if (!accountUuid.toString().equals(retained.accountUuid())
        || !tenantUuid.toString().equals(retained.tenantUuid())
        || !accountUuid.toString().equals(retained.requestAccountUuid())
        || !tenantUuid.toString().equals(retained.requestTenantUuid())) {
      throw new IllegalStateException(
          "Retained numeric membership readback differs from its exact canonical UUID request");
    }
    return retained;
  }

  private RuntimeMembershipSnapshotDto toRuntimeMembershipSnapshot(
      NeverJoinedMembershipSnapshot absent) {
    return new RuntimeMembershipSnapshotDto(
        absent.accountId(),
        absent.tenantId(),
        absent.accountId(),
        absent.tenantId(),
        false,
        false,
        new RuntimeMembershipSnapshotDto.MembershipBaseline(
            absent.membershipLifecycleState(),
            absent.membershipVersion(),
            absent.membershipAuthorityGeneration()),
        List.of(),
        absent.authorityTuple(),
        absent.issuanceFence(),
        absent.evaluatedAt(),
        absent.outboxCheckpoints(),
        absent.outboxSourceEvidence(),
        null);
  }

  /**
   * Reads an explicit sequence-zero checkpoint only from a durable verified pair baseline, after
   * the no-membership and no-committed-history proof under one Account-row fence. It never admits
   * gameplay and is not exposed through the still-denied runtime membership RPC.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public NeverJoinedMembershipSnapshot readNeverJoinedMembershipSnapshot(
      long accountId, long legacyTenantId) {
    if (accountId <= 0L || legacyTenantId <= 0L) {
      throw new IllegalArgumentException("Account and retained tenant identities must be positive");
    }
    joinOperationRepository.lockAccount(accountId);
    Identity identity = resolveIdentity(accountId, legacyTenantId);
    if (membershipRepository.findJoinProofForUpdate(accountId, legacyTenantId).isPresent()) {
      throw new IllegalStateException("Never-joined Account membership row is not absent");
    }
    transitionReceiptRepository.assertNewMembershipTransitionCanStart(accountId, legacyTenantId);
    return assembleNeverJoinedMembershipSnapshot(
        identity.accountUuid(), identity.tenantUuid(), verifiedProvenance(identity));
  }

  /**
   * Enrolls and reads the non-admitting baseline for one exact Game Design-issued tenant UUID.
   *
   * <p>The Account row is the transaction fence. The UUID-to-UUID fresh association is read only
   * after that row is locked and reread, and no numeric tenant selector or alias is accepted or
   * derived. This prepares local authority evidence only; it does not create membership, an event,
   * a credential, or an enabled runtime RPC.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public NeverJoinedMembershipSnapshot readFreshNeverJoinedMembershipSnapshot(
      UUID accountUuid, UUID tenantUuid) {
    requireActiveOwnerTransaction();
    requireCanonicalUuidInput(accountUuid, "Account UUID");
    requireCanonicalUuidInput(tenantUuid, "tenant UUID");

    Account initialAccount =
        accountRepository
            .findByAccountUuid(accountUuid)
            .orElseThrow(() -> new IllegalStateException("Fresh snapshot Account row is absent"));
    requirePersistedAccountIdentity(initialAccount, accountUuid);
    long accountId = initialAccount.getId();

    // Preserve the JOIN lock order: acquire the numeric Account-row lock before consulting tenant
    // provenance, pair authority, or any membership-snapshot input.
    joinOperationRepository.lockAccount(accountId);
    Account fencedAccount =
        accountRepository
            .findByAccountUuid(accountUuid)
            .orElseThrow(
                () -> new IllegalStateException("Fresh snapshot Account row disappeared at fence"));
    requirePersistedAccountIdentity(fencedAccount, accountUuid);
    if (!Objects.equals(initialAccount.getId(), fencedAccount.getId())
        || initialAccount.getAccountUuidProvenance() != fencedAccount.getAccountUuidProvenance()
        || !Objects.equals(
            initialAccount.getAccountUuidSourceNumericId(),
            fencedAccount.getAccountUuidSourceNumericId())) {
      throw new IllegalStateException("Fresh snapshot Account identity changed at its row fence");
    }

    FreshTenantCreationEvidence evidence =
        freshTenantIdentityAssociationRepository
            .read(tenantUuid)
            .orElseThrow(
                () -> new IllegalStateException("Fresh Game Design tenant association is absent"));
    if (!tenantUuid.equals(evidence.canonicalTenantId())) {
      throw new IllegalStateException(
          "Fresh Game Design tenant association differs from its canonical UUID scope");
    }
    VerifiedTenantProvenance provenance =
        new VerifiedTenantProvenance(
            null,
            TenantProvenanceKind.FRESH_GAME_DESIGN,
            evidence.operationId(),
            evidence.evidenceDigest());

    // Take upstream generation/fence locks in the composite reader's canonical order before the
    // membership initializer takes its Account/tenant locks. The Account entity row remains the
    // outer owner fence, and the full composite snapshot is reread below after enrollment.
    CompositeSnapshot upstream = lockUpstreamAuthorityScopes(accountUuid, tenantUuid);
    requireFreshUpstreamSequenceZeroBaseline(accountUuid, tenantUuid, upstream);

    String membershipStreamKey = membershipStreamKey(accountUuid, tenantUuid);
    Optional<PairAuthority> existingPair =
        pairAuthorityRepository.readForUpdate(accountUuid, tenantUuid);
    if (existingPair.isEmpty()) {
      if (authorityOutboxRepository.readCheckpoint(membershipStreamKey).isPresent()) {
        throw new IllegalStateException(
            "Absent fresh Account membership has retained authority-event history");
      }
      ScopeState generation =
          authorityGenerationRepository.initialize(
              AuthorityScope.membership(accountUuid, tenantUuid));
      if (generation.generation() != 1L || generation.sourceVersion() != 1L) {
        throw new IllegalStateException(
            "Fresh never-joined Account membership generation did not initialize at one");
      }
    }

    PairAuthority pair = pairAuthorityRepository.enrollAbsence(accountUuid, tenantUuid, provenance);
    if (!provenance.equals(pair.provenance())
        || pair.membershipExists()
        || pair.membershipVersion() != 1L
        || pair.lastEventSequence() != 0L) {
      throw new IllegalStateException(
          "Fresh never-joined Account membership differs from its positive pair baseline");
    }
    return assembleNeverJoinedMembershipSnapshot(accountUuid, tenantUuid, provenance);
  }

  private NeverJoinedMembershipSnapshot assembleNeverJoinedMembershipSnapshot(
      UUID accountUuid, UUID tenantUuid, VerifiedTenantProvenance provenance) {
    PairAuthority pair =
        pairAuthorityRepository
            .readForUpdate(accountUuid, tenantUuid)
            .orElseThrow(() -> new IllegalStateException("Never-joined pair baseline is absent"));
    CompositeSnapshot authority = readSnapshot(accountUuid, tenantUuid);
    ScopeState member = only(authority.memberships(), "membership");
    requireMatchingFence(authority, member, accountUuid);
    List<OutboxCheckpointEntry> upstreamCheckpoints =
        readBaselineUpstreamCheckpoints(accountUuid, tenantUuid, authority);
    String streamKey = membershipStreamKey(accountUuid, tenantUuid);
    OutboxCheckpointEntry membershipCheckpoint =
        requireSequenceZeroCheckpoint(streamKey, member, "membership");
    if (!provenance.equals(pair.provenance())
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
                tenantUuid.toString(), decimal(only(authority.tenants(), "tenant").generation())),
            Map.of(tenantUuid.toString(), decimal(member.generation())),
            List.of(),
            Optional.empty(),
            Optional.empty());
    List<OutboxCheckpointEntry> checkpoints = new ArrayList<>(upstreamCheckpoints);
    checkpoints.add(membershipCheckpoint);
    checkpoints = orderedCheckpoints(checkpoints);
    return new NeverJoinedMembershipSnapshot(
        accountUuid.toString(),
        tenantUuid.toString(),
        Map.of(tenantUuid.toString(), decimal(pair.membershipVersion())),
        decimal(pair.membershipAuthorityGeneration()),
        tuple,
        decimal(authority.issuanceFence().value()),
        Instant.now(),
        streamKey,
        checkpoints,
        List.of());
  }

  private void requireFreshUpstreamSequenceZeroBaseline(
      UUID accountUuid, UUID tenantUuid, CompositeSnapshot snapshot) {
    ScopeState tenant = only(snapshot.tenants(), "tenant");
    if (!AuthorityScope.issuer(AccountServiceImpl.ACCOUNT_JWT_ISSUER)
            .equals(snapshot.issuer().scope())
        || !AuthorityScope.account(accountUuid).equals(snapshot.account().scope())
        || !AuthorityScope.tenant(tenantUuid).equals(tenant.scope())
        || !snapshot.memberships().isEmpty()
        || !accountUuid.equals(snapshot.issuanceFence().accountId())
        || !snapshot.issuanceFence().equals(snapshot.account().issuanceFence())) {
      throw new IllegalStateException(
          "Fresh Account authority snapshot does not cover its exact issuer/account/tenant scopes");
    }
    requireSequenceZeroCheckpoint(accountStreamKey(accountUuid), snapshot.account(), "account");
    requireSequenceZeroCheckpoint(issuerStreamKey(), snapshot.issuer(), "issuer");
    requireSequenceZeroCheckpoint(tenantStreamKey(tenantUuid), tenant, "tenant");
  }

  private CompositeSnapshot lockUpstreamAuthorityScopes(UUID accountUuid, UUID tenantUuid) {
    return authorityGenerationRepository.readCompositeSnapshot(
        AccountServiceImpl.ACCOUNT_JWT_ISSUER, accountUuid, List.of(tenantUuid), List.of());
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

    lockUpstreamAuthorityScopes(identity.accountUuid(), identity.tenantUuid());
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

  /**
   * Ensures that one exact, Account-fenced runtime snapshot has durable pair authority to read.
   *
   * <p>A never-joined pair reuses JOIN's committed absence enrollment, then proves the resulting
   * sequence-zero snapshot. An existing membership is enrolled only through its exact current
   * active or inactive receipt/event proof and is read back against the pair row. No retained
   * inactive path enrolls or changes membership/event history.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void preparePairAuthorityForRuntimeSnapshot(long accountId, long legacyTenantId) {
    if (accountId <= 0L || legacyTenantId <= 0L) {
      throw new IllegalArgumentException("Account and retained tenant identities must be positive");
    }

    // Keep the same lock order as JOIN before resolving identity or inspecting retained history.
    joinOperationRepository.lockAccount(accountId);
    resolveIdentity(accountId, legacyTenantId);
    Optional<JoinMembershipProof> currentMembership =
        membershipRepository.findJoinProofForUpdate(accountId, legacyTenantId);
    if (currentMembership.isEmpty()) {
      preparePairAuthorityForJoin(accountId, legacyTenantId);
      readNeverJoinedMembershipSnapshot(accountId, legacyTenantId);
      return;
    }

    if ("INACTIVE".equals(currentMembership.orElseThrow().lifecycleState())) {
      readCurrentPairBoundInactiveMembershipSnapshot(accountId, legacyTenantId);
      return;
    }

    enrollProvenRetainedActivePair(accountId, legacyTenantId);
    readCurrentPairBoundPositiveMembershipSnapshot(accountId, legacyTenantId);
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
   * Requires the latest checkpoint event to prove the exact current retained membership state. This
   * read may fail closed during JOIN/reconciliation without undoing a caller's retry receipt.
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
        appendAndReadBack(identity, requestId, membership, snapshot, false, true, false);
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
        appendAndReadBack(identity, requestId, membership, committedCandidate, true, false, false);
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

  /** Compare-and-advances an existing active member to its committed non-admitting LEFT event. */
  @Transactional(propagation = Propagation.MANDATORY)
  public Checkpoint publishLeftMembershipChange(
      long accountId, long legacyTenantId, String requestId, AccountTenantMembership membership) {
    requireActiveOwnerTransaction();
    Identity identity = resolveIdentity(accountId, legacyTenantId);
    requireMembershipIdentity(accountId, legacyTenantId, membership);
    if (!"INACTIVE".equals(membership.getLifecycleState())
        || membership.isGameplayAdmissionAllowed()
        || !"EXPLICIT_JOIN".equals(membership.getAuthorityProvenance())) {
      throw new IllegalStateException("LEFT Account membership is not inactive and explicit");
    }
    long expectedPriorVersion = decrementPositive(membership.getMembershipVersion());
    long expectedPriorGeneration = decrementPositive(membership.getMembershipAuthorityGeneration());
    PairAuthority priorPair =
        pairAuthorityRepository
            .readForUpdate(identity.accountUuid(), identity.tenantUuid())
            .orElseThrow(() -> new IllegalStateException("LEFT membership has no pair authority"));
    if (!verifiedProvenance(identity).equals(priorPair.provenance())
        || !priorPair.membershipExists()
        || priorPair.membershipVersion() != expectedPriorVersion
        || priorPair.membershipAuthorityGeneration() != expectedPriorGeneration
        || priorPair.lastEventSequence() <= 0L) {
      throw new IllegalStateException("LEFT membership differs from its prior pair authority");
    }
    CompositeSnapshot beforeAdvance = readSnapshot(identity);
    ScopeState currentMembership = only(beforeAdvance.memberships(), "membership");
    requireMatchingFence(beforeAdvance, currentMembership, identity.accountUuid());
    if (currentMembership.generation() != expectedPriorGeneration) {
      throw new IllegalStateException(
          "Embedded and canonical Account membership generations differ at LEFT");
    }
    ScopeState advanced =
        authorityGenerationRepository.advance(currentMembership, beforeAdvance.issuanceFence());
    if (advanced.generation() != membership.getMembershipAuthorityGeneration()) {
      throw new IllegalStateException(
          "Account membership authority generation did not advance at LEFT");
    }
    CompositeSnapshot committedCandidate = readSnapshot(identity);
    ScopeState committedMembership = only(committedCandidate.memberships(), "membership");
    requireMatchingFence(committedCandidate, committedMembership, identity.accountUuid());
    if (committedMembership.generation() != membership.getMembershipAuthorityGeneration()) {
      throw new IllegalStateException(
          "Committed Account membership tuple differs from the LEFT candidate");
    }
    Checkpoint checkpoint =
        appendAndReadBack(identity, requestId, membership, committedCandidate, true, false, true);
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
            != membership.getMembershipAuthorityGeneration()
        || !committed.lastTransitionInvalidated()) {
      throw new IllegalStateException(
          "LEFT membership pair authority readback differs from its event");
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
    if (!Map.of(identity.tenantUuid().toString(), Long.toString(operation.membershipVersion()))
            .equals(verified.membershipVersion())
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
      boolean newMembership,
      boolean inactiveMembership) {
    requirePositive(requestId, "membership transition request ID");
    if (inactiveMembership) {
      requireInactiveMembership(membership);
    } else {
      requireActiveMembership(membership);
    }
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
      throw new IllegalStateException(
          "Membership lifecycle transition must invalidate caller authority");
    }
    readBaselineUpstreamCheckpoints(identity, snapshot);
    RoleSnapshot roles = requireRoleSnapshot(identity, membership, inactiveMembership);
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
    if (!verified.membershipVersion().equals(expected.membershipVersion())
        || !verified.eventDigest().equals(expected.eventDigest())
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
        || !Map.of(identity.tenantUuid().toString(), Long.toString(membershipVersion))
            .equals(verified.membershipVersion())
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
    event.put(
        "membershipVersion",
        Map.of(identity.tenantUuid().toString(), decimal(membership.getMembershipVersion())));
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

  /** Retains the verified event's exact immutable one-tenant map in the local snapshot. */
  private Map<String, String> membershipVersionMap(MembershipEvent event, Identity identity) {
    String tenantId = identity.tenantUuid().toString();
    Map<String, String> versions = event.membershipVersion();
    membershipVersionValue(versions, tenantId);
    return Map.copyOf(versions);
  }

  /** Extracts a process-local scalar only after validating the complete map. */
  private String membershipVersionValue(Map<String, String> versions, String tenantId) {
    String version = versions.get(tenantId);
    if (versions.size() != 1
        || !versions.containsKey(tenantId)
        || version == null
        || !version.matches("[1-9][0-9]*")) {
      throw new IllegalStateException(
          "Stored Account membership event has no exact canonical one-tenant version map");
    }
    return version;
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

  private List<OutboxCheckpointEntry> readBaselineUpstreamCheckpoints(
      Identity identity, CompositeSnapshot snapshot) {
    return readBaselineUpstreamCheckpoints(identity.accountUuid(), identity.tenantUuid(), snapshot);
  }

  private List<OutboxCheckpointEntry> readBaselineUpstreamCheckpoints(
      UUID accountUuid, UUID tenantUuid, CompositeSnapshot snapshot) {
    ScopeState tenant = only(snapshot.tenants(), "tenant");
    if (!AuthorityScope.issuer(AccountServiceImpl.ACCOUNT_JWT_ISSUER)
            .equals(snapshot.issuer().scope())
        || !AuthorityScope.account(accountUuid).equals(snapshot.account().scope())
        || !AuthorityScope.tenant(tenantUuid).equals(tenant.scope())
        || !AuthorityScope.membership(accountUuid, tenantUuid)
            .equals(only(snapshot.memberships(), "membership").scope())) {
      throw new IllegalStateException(
          "Account authority snapshot does not cover the exact issuer/account/tenant scopes");
    }

    return orderedCheckpoints(
        List.of(
            requireSequenceZeroCheckpoint(
                accountStreamKey(accountUuid), snapshot.account(), "account"),
            requireSequenceZeroCheckpoint(issuerStreamKey(), snapshot.issuer(), "issuer"),
            requireSequenceZeroCheckpoint(tenantStreamKey(tenantUuid), tenant, "tenant")));
  }

  private OutboxCheckpointEntry requireSequenceZeroCheckpoint(
      String streamKey, ScopeState scope, String scopeLabel) {
    // The composite generation read holds the source rows. V31 advances source_version with every
    // generation change; V33 keeps committed streams/events immutable and authority writers commit
    // them with the applicable source generation. Both pristine generation state and an absent
    // V33 head are required before zero is reported.
    if (scope.generation() != 1L || scope.sourceVersion() != 1L) {
      throw new IllegalStateException(
          "Account "
              + scopeLabel
              + " authority generation cannot prove its sequence-zero baseline");
    }
    if (authorityOutboxRepository.readCheckpoint(streamKey).isPresent()) {
      throw new IllegalStateException(
          "Account " + scopeLabel + " authority stream has committed history");
    }
    return new OutboxCheckpointEntry(streamKey, "0");
  }

  private List<OutboxCheckpointEntry> orderedCheckpoints(List<OutboxCheckpointEntry> checkpoints) {
    if (checkpoints == null || checkpoints.isEmpty()) {
      throw new IllegalArgumentException("Account authority checkpoints are required");
    }
    ArrayList<OutboxCheckpointEntry> ordered = new ArrayList<>(checkpoints);
    ordered.sort(OUTBOX_CHECKPOINT_ORDER);
    for (int index = 1; index < ordered.size(); index++) {
      if (ordered.get(index - 1).outboxStreamKey().equals(ordered.get(index).outboxStreamKey())) {
        throw new IllegalArgumentException(
            "Account authority checkpoint stream keys must be unique");
      }
    }
    return List.copyOf(ordered);
  }

  private OutboxSourceEvidence sourceEvidence(Checkpoint checkpoint, Event event) {
    if (!checkpointMatches(checkpoint, event)) {
      throw new IllegalStateException(
          "Account authority source event differs from its exact outbox checkpoint");
    }
    return new OutboxSourceEvidence(
        checkpoint.outboxStreamKey(),
        Long.toString(checkpoint.outboxSequence()),
        event.eventId(),
        event.eventDigest(),
        new String(event.payload(), StandardCharsets.UTF_8));
  }

  private RoleSnapshot requireRoleSnapshot(
      Identity identity, AccountTenantMembership membership, boolean inactiveMembership) {
    if (membership.getId() == null || membership.getMembershipVersion() <= 0L) {
      throw new IllegalStateException("Account membership role identity is incomplete");
    }
    RoleSnapshot snapshot =
        roleSnapshotRepository
            .findForUpdate(
                identity.accountId(),
                identity.legacyTenantId(),
                membership.getId(),
                membership.getMembershipVersion())
            .orElseThrow(
                () -> new IllegalStateException("Account membership role snapshot is absent"));
    List<String> exactRoles;
    try {
      exactRoles =
          AccountTenantMembershipRoleSnapshotRepository.requireCanonicalRoleSet(snapshot.roles());
    } catch (RuntimeException exception) {
      throw new IllegalStateException(
          "Account membership role snapshot is not canonical", exception);
    }
    if (snapshot.accountId() != identity.accountId()
        || snapshot.tenantId() != identity.legacyTenantId()
        || snapshot.membershipId() != membership.getId()
        || snapshot.snapshotVersion() != membership.getMembershipVersion()
        || (!inactiveMembership && !exactRoles.contains("player"))) {
      throw new IllegalStateException("Account role snapshot differs from its membership");
    }
    return new RoleSnapshot(
        snapshot.accountId(),
        snapshot.tenantId(),
        snapshot.membershipId(),
        snapshot.snapshotVersion(),
        exactRoles);
  }

  private CompositeSnapshot readSnapshot(Identity identity) {
    return readSnapshot(identity.accountUuid(), identity.tenantUuid());
  }

  private CompositeSnapshot readSnapshot(UUID accountUuid, UUID tenantUuid) {
    return authorityGenerationRepository.readCompositeSnapshot(
        AccountServiceImpl.ACCOUNT_JWT_ISSUER,
        accountUuid,
        List.of(tenantUuid),
        List.of(tenantUuid));
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

  private void requirePersistedAccountIdentity(Account account, UUID expectedAccountUuid) {
    if (account == null
        || account.getId() == null
        || account.getId() <= 0L
        || !expectedAccountUuid.equals(account.getAccountUuid())
        || account.getAccountUuidProvenance() == null
        || !Objects.equals(account.getAccountUuidSourceNumericId(), account.getId())) {
      throw new IllegalStateException(
          "Fresh snapshot Account UUID does not exactly identify its persisted row");
    }
  }

  private void requireCanonicalUuidInput(UUID value, String label) {
    if (value == null || value.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException(label + " must be a non-nil canonical UUID");
    }
  }

  private void requireActiveOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Fresh Account membership snapshot requires an active owner transaction");
    }
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

  private void requireInactiveMembership(AccountTenantMembership membership) {
    if (membership.getMembershipVersion() <= 0L
        || membership.getMembershipAuthorityGeneration() <= 0L
        || !"INACTIVE".equals(membership.getLifecycleState())
        || membership.isGameplayAdmissionAllowed()
        || !"EXPLICIT_JOIN".equals(membership.getAuthorityProvenance())) {
      throw new IllegalStateException(
          "LEFT event requires a persisted inactive explicit membership");
    }
  }

  private String membershipStreamKey(Identity identity) {
    return membershipStreamKey(identity.accountUuid(), identity.tenantUuid());
  }

  private String membershipStreamKey(UUID accountUuid, UUID tenantUuid) {
    return MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX
        + "membership/"
        + accountUuid
        + "/"
        + tenantUuid;
  }

  private String issuerStreamKey() {
    return canonicalIssuerStreamKey();
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
      throw new IllegalStateException("Membership lifecycle requires prior positive history");
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

  /**
   * One immutable Account authority checkpoint; event identity remains separate source evidence.
   */
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
      if (!outboxStreamKey.startsWith(MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX)
          || !isPositiveCanonicalDecimal(outboxSequence)
          || !eventDigest.matches("sha256:[0-9a-f]{64}")) {
        throw new IllegalArgumentException(
            "Account authority source event evidence is not canonical");
      }
      Objects.requireNonNull(canonicalEventJson, "canonical source event JSON is required");
      MembershipEvent event = MembershipAuthorityEventV1Codec.verify(canonicalEventJson);
      if (!event.canonicalJson().equals(canonicalEventJson)
          || !event.eventId().equals(eventId)
          || !event.eventDigest().equals(eventDigest)
          || !event.outboxStreamKey().equals(outboxStreamKey)
          || !event.outboxSequence().equals(outboxSequence)) {
        throw new IllegalArgumentException(
            "Account authority source event JSON differs from its exact identity");
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

      if (!"1".equals(authorityTuple.issuerAuthGeneration())
          || !"1".equals(authorityTuple.accountAuthorityGeneration())
          || authorityTuple.tenantAuthorityGeneration().size() != 1
          || authorityTuple.membershipAuthorityGeneration().size() != 1
          || !authorityTuple.tenantAuthorityGeneration().containsKey(tenantId)
          || !authorityTuple.membershipAuthorityGeneration().containsKey(tenantId)
          || !"1".equals(authorityTuple.tenantAuthorityGeneration().get(tenantId))
          || !membershipAuthorityGeneration.equals(
              authorityTuple.membershipAuthorityGeneration().get(tenantId))
          || !"1".equals(membershipVersion.get(tenantId))
          || !authorityTuple.privateRealmGrantVersions().isEmpty()
          || authorityTuple.accountSecurityCutoff().isPresent()
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
      List<OutboxCheckpointEntry> expectedCheckpoints =
          requireOrderedCheckpointSet(
              List.of(
                  new OutboxCheckpointEntry(accountStreamKey(accountId), "0"),
                  new OutboxCheckpointEntry(canonicalIssuerStreamKey(), "0"),
                  new OutboxCheckpointEntry(outboxStreamKey, "0"),
                  new OutboxCheckpointEntry(tenantStreamKey(tenantId), "0")));
      if (!expectedCheckpoints.equals(outboxCheckpoints) || !outboxSourceEvidence.isEmpty()) {
        throw new IllegalArgumentException(
            "Never-joined Account snapshot must contain only four sequence-zero checkpoints");
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

  /** Immutable positive membership evidence assembled from one fenced Account transaction. */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "The constructor stores immutable Map.copyOf and List.copyOf values.")
  public record PositiveMembershipSnapshot(
      boolean membershipExists,
      String accountId,
      String tenantId,
      String membershipLifecycleState,
      boolean gameplayAdmissionAllowed,
      Map<String, String> membershipVersion,
      String membershipAuthorityGeneration,
      List<String> roles,
      AuthorityTuple authorityTuple,
      String issuanceFence,
      Instant evaluatedAt,
      List<OutboxCheckpointEntry> outboxCheckpoints,
      List<OutboxSourceEvidence> outboxSourceEvidence,
      MembershipTransitionReceipt transitionReceipt,
      MembershipEvent authorityEvent) {
    public PositiveMembershipSnapshot {
      accountId = requireCanonicalUuid(accountId, "canonical Account UUID");
      tenantId = requireCanonicalUuid(tenantId, "canonical tenant UUID");
      Objects.requireNonNull(membershipLifecycleState, "membership lifecycle is required");
      membershipVersion =
          requireExactPositiveVersionMap(membershipVersion, tenantId, "membership version");
      Objects.requireNonNull(
          membershipAuthorityGeneration, "membership authority generation is required");
      membershipAuthorityGeneration =
          requirePositiveCanonicalDecimal(
              membershipAuthorityGeneration, "membership authority generation");
      roles = List.copyOf(roles);
      Objects.requireNonNull(authorityTuple, "complete authority tuple is required");
      issuanceFence = requirePositiveCanonicalDecimal(issuanceFence, "Account issuance fence");
      Objects.requireNonNull(evaluatedAt, "Account evaluation time is required");
      outboxCheckpoints = requireOrderedCheckpointSet(outboxCheckpoints);
      outboxSourceEvidence = List.copyOf(outboxSourceEvidence);
      Objects.requireNonNull(transitionReceipt, "membership transition receipt is required");
      Objects.requireNonNull(authorityEvent, "canonical membership authority event is required");

      String membershipStreamKey = membershipStreamKey(accountId, tenantId);
      String membershipSequence = authorityEvent.outboxSequence();
      if (!isPositiveCanonicalDecimal(membershipSequence)) {
        throw new IllegalArgumentException(
            "Positive Account membership event sequence must be canonical and positive");
      }
      List<OutboxCheckpointEntry> expectedCheckpoints =
          requireOrderedCheckpointSet(
              List.of(
                  new OutboxCheckpointEntry(accountStreamKey(accountId), "0"),
                  new OutboxCheckpointEntry(canonicalIssuerStreamKey(), "0"),
                  new OutboxCheckpointEntry(membershipStreamKey, membershipSequence),
                  new OutboxCheckpointEntry(tenantStreamKey(tenantId), "0")));
      List<OutboxSourceEvidence> expectedSourceEvidence =
          List.of(
              new OutboxSourceEvidence(
                  membershipStreamKey,
                  membershipSequence,
                  authorityEvent.eventId(),
                  authorityEvent.eventDigest(),
                  authorityEvent.canonicalJson()));

      if (!membershipExists
          || !"ACTIVE".equals(membershipLifecycleState)
          || !gameplayAdmissionAllowed
          || !accountId.equals(authorityEvent.accountId())
          || !tenantId.equals(authorityEvent.tenantId())
          || !membershipStreamKey.equals(authorityEvent.outboxStreamKey())
          || !membershipLifecycleState.equals(authorityEvent.membershipLifecycleState())
          || !membershipVersion.equals(authorityEvent.membershipVersion())
          || !membershipAuthorityGeneration.equals(authorityEvent.membershipAuthorityGeneration())
          || !roles.equals(authorityEvent.roles())
          || gameplayAdmissionAllowed != authorityEvent.gameplayAdmissionAllowed()
          || !authorityTuple.equals(authorityEvent.authorityTuple())
          || !issuanceFence.equals(authorityEvent.issuanceFence())
          || !"1".equals(authorityTuple.issuerAuthGeneration())
          || !"1".equals(authorityTuple.accountAuthorityGeneration())
          || !Map.of(tenantId, "1").equals(authorityTuple.tenantAuthorityGeneration())
          || !Map.of(tenantId, membershipAuthorityGeneration)
              .equals(authorityTuple.membershipAuthorityGeneration())
          || !membershipAuthorityGeneration.equals(
              authorityTuple.membershipAuthorityGeneration().get(tenantId))
          || !authorityTuple.privateRealmGrantVersions().isEmpty()
          || authorityTuple.accountSecurityCutoff().isPresent()
          || authorityTuple.tenantBillingCutoff().isPresent()
          || !expectedCheckpoints.equals(outboxCheckpoints)
          || !expectedSourceEvidence.equals(outboxSourceEvidence)
          || !transitionReceipt.requestId().equals(authorityEvent.requestId())) {
        throw new IllegalArgumentException(
            "Positive Account membership snapshot evidence is internally inconsistent");
      }
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

  private static String membershipStreamKey(String accountId, String tenantId) {
    return MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX
        + "membership/"
        + accountId
        + "/"
        + tenantId;
  }

  private static String canonicalIssuerStreamKey() {
    return MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX
        + "issuer/"
        + AccountServiceImpl.ACCOUNT_JWT_ISSUER;
  }

  private static String accountStreamKey(String accountId) {
    return MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX + "account/" + accountId;
  }

  private static String tenantStreamKey(String tenantId) {
    return MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX + "tenant/" + tenantId;
  }

  private record Identity(
      long accountId,
      long legacyTenantId,
      UUID accountUuid,
      UUID tenantUuid,
      ApprovedAssociation association) {}
}
