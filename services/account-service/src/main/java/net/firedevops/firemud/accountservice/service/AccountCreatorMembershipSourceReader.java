package net.firedevops.firemud.accountservice.service;

import static net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer.validatorSnapshot;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.entity.AccountTenantMembership;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.CompositeSnapshot;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Checkpoint;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Event;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.IssuerAccountSourceSnapshot;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.PairAuthority;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.TenantProvenanceKind;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.VerifiedTenantProvenance;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantCreationBootstrapOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantCreationBootstrapOperationRepository.StoredOperation;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository.RoleSnapshot;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer.CreatorControlCaptureSources;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer.NeverJoinedMembershipSnapshot;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer.OutboxCheckpointEntry;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer.OutboxSourceEvidence;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.AuthorityTuple;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.MembershipEvent;
import net.firedevops.firemud.common.account.authority.RuntimeMembershipAuthorityEvidenceValidator;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Account-owned creator source composition; supplies no caller, authoring or gameplay authority.
 */
@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected Account repositories are internal transaction collaborators.")
public class AccountCreatorMembershipSourceReader {
  private static final String ISSUER = "firemud-account-service";
  private final AccountRepository accountRepository;
  private final AccountJoinOperationRepository joinOperationRepository;
  private final AccountTenantMembershipRepository membershipRepository;
  private final AccountMembershipPairAuthorityRepository pairAuthorityRepository;
  private final AccountTenantMembershipRoleSnapshotRepository roleSnapshotRepository;
  private final FreshTenantIdentityAssociationRepository freshTenantIdentityAssociationRepository;
  private final AccountAuthorityGenerationRepository authorityGenerationRepository;
  private final AccountAuthorityOutboxRepository authorityOutboxRepository;
  private final AccountAuthoritySourceEvidenceRepository sourceEvidenceRepository;
  private final AccountTenantCreationBootstrapOperationRepository bootstrapOperations;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Spring proxies the non-final owner reader; constructor validates private collaborators.")
  public AccountCreatorMembershipSourceReader(
      AccountRepository accounts,
      AccountJoinOperationRepository joins,
      AccountTenantMembershipRepository memberships,
      AccountMembershipPairAuthorityRepository pairs,
      AccountTenantMembershipRoleSnapshotRepository roles,
      FreshTenantIdentityAssociationRepository fresh,
      AccountAuthorityGenerationRepository generations,
      AccountAuthorityOutboxRepository outbox,
      AccountAuthoritySourceEvidenceRepository sources,
      AccountTenantCreationBootstrapOperationRepository bootstrapOperations) {
    this.accountRepository = Objects.requireNonNull(accounts);
    this.joinOperationRepository = Objects.requireNonNull(joins);
    this.membershipRepository = Objects.requireNonNull(memberships);
    this.pairAuthorityRepository = Objects.requireNonNull(pairs);
    this.roleSnapshotRepository = Objects.requireNonNull(roles);
    this.freshTenantIdentityAssociationRepository = Objects.requireNonNull(fresh);
    this.authorityGenerationRepository = Objects.requireNonNull(generations);
    this.authorityOutboxRepository = Objects.requireNonNull(outbox);
    this.sourceEvidenceRepository = Objects.requireNonNull(sources);
    this.bootstrapOperations = Objects.requireNonNull(bootstrapOperations);
  }

  /** Explicit owner enrollment/readback, never an absent-row authority inference. */
  @Transactional(propagation = Propagation.MANDATORY)
  public NeverJoinedMembershipSnapshot readFreshNeverJoinedMembershipSnapshot(
      UUID accountUuid, UUID tenantUuid) {
    requireWritableOwnerTransaction();
    requireCanonicalUuidInput(accountUuid, "Account UUID");
    requireCanonicalUuidInput(tenantUuid, "tenant UUID");
    Account account =
        accountRepository
            .findByAccountUuidForUpdate(accountUuid)
            .orElseThrow(() -> new IllegalStateException("Fresh Account source is absent"));
    requirePersistedAccountIdentity(account, accountUuid);
    FreshTenantCreationEvidence creation =
        freshTenantIdentityAssociationRepository
            .read(tenantUuid)
            .orElseThrow(() -> new IllegalStateException("Fresh tenant source is absent"));
    CompositeSnapshot upstream =
        authorityGenerationRepository.readCompositeSnapshot(
            ISSUER, accountUuid, List.of(tenantUuid), List.of());
    readCurrentUpstreamSourceEvidence(accountUuid, tenantUuid, upstream);
    bootstrapOperations.assertNoContradictoryMembershipHistory(accountUuid, tenantUuid);
    String stream = membershipStreamKey(accountUuid, tenantUuid);
    if (authorityOutboxRepository.readCheckpoint(stream).isPresent()) {
      throw new IllegalStateException("Fresh creator membership has committed event history");
    }
    ScopeState generation =
        authorityGenerationRepository.initialize(
            AuthorityScope.membership(accountUuid, tenantUuid));
    if (generation.generation() != 1L || generation.sourceVersion() != 1L) {
      throw new IllegalStateException("Fresh creator membership generation is not pristine");
    }
    PairAuthority pair = membershipRepository.prepareFreshCreatorAbsence(accountUuid, tenantUuid);
    VerifiedTenantProvenance provenance = freshTenantProvenance(creation);
    if (!new PairAuthority(
            accountUuid, tenantUuid, provenance, false, 1L, 1L, 0L, null, null, false)
        .equals(pair)) {
      throw new IllegalStateException("Fresh creator pair baseline differs");
    }
    CompositeSnapshot authority = readSnapshot(accountUuid, tenantUuid);
    requireMatchingFence(authority, only(authority.memberships(), "membership"), accountUuid);
    CurrentSourceEvidence sources =
        readCurrentUpstreamSourceEvidence(accountUuid, tenantUuid, authority);
    List<OutboxCheckpointEntry> checkpoints = new ArrayList<>(sources.checkpoints());
    checkpoints.add(new OutboxCheckpointEntry(stream, "0"));
    requireUnchangedAccountIdentity(account, accountUuid);
    if (!Optional.of(creation).equals(freshTenantIdentityAssociationRepository.read(tenantUuid))
        || authorityOutboxRepository.readCheckpoint(stream).isPresent()) {
      throw new IllegalStateException("Fresh creator source changed during capture");
    }
    return new NeverJoinedMembershipSnapshot(
        accountUuid.toString(),
        tenantUuid.toString(),
        Map.of(tenantUuid.toString(), "1"),
        "1",
        currentAuthorityTuple(tenantUuid, authority, sources),
        decimal(authority.issuanceFence().value()),
        Instant.now(),
        stream,
        orderedCheckpoints(checkpoints),
        sources.sourceEvidence());
  }

  @Transactional(propagation = Propagation.MANDATORY)
  CreatorControlCaptureSources readExistingCreatorControlCaptureSources(
      UUID accountUuid, UUID tenantUuid, Supplier<StoredOperation> verifiedOriginalReceipt) {
    requireWritableOwnerTransaction();
    requireCanonicalUuidInput(accountUuid, "Account UUID");
    requireCanonicalUuidInput(tenantUuid, "tenant UUID");
    Account account =
        accountRepository
            .findByAccountUuid(accountUuid)
            .orElseThrow(() -> new IllegalStateException("Creator Account source is absent"));
    requirePersistedAccountIdentity(account, accountUuid);
    joinOperationRepository.lockAccount(account.getId());
    CompositeSnapshot authority = readSnapshot(accountUuid, tenantUuid);
    CurrentSourceEvidence upstream =
        readCurrentUpstreamSourceEvidence(accountUuid, tenantUuid, authority);
    FreshTenantCreationEvidence creation =
        freshTenantIdentityAssociationRepository
            .read(tenantUuid)
            .orElseThrow(() -> new IllegalStateException("Creator fresh tenant source is absent"));
    var receipt = Objects.requireNonNull(verifiedOriginalReceipt.get());
    var provenance = freshTenantProvenance(creation);
    Identity identity = new Identity(account.getId(), null, accountUuid, tenantUuid, provenance);
    PairAuthority pair =
        pairAuthorityRepository
            .readForUpdate(accountUuid, tenantUuid)
            .orElseThrow(() -> new IllegalStateException("Creator pair source is absent"));
    AccountTenantMembership membership =
        membershipRepository
            .findCanonicalMembershipForUpdate(accountUuid, tenantUuid)
            .orElseThrow(
                () -> new IllegalStateException("Creator control membership source is absent"));
    if (membership.getAccount() == null) {
      throw new IllegalStateException("Creator control membership Account is absent");
    }
    requirePersistedAccountIdentity(membership.getAccount(), accountUuid);
    ScopeState memberSource = only(authority.memberships(), "membership");
    requireMatchingFence(authority, memberSource, accountUuid);
    if (!"COMMITTED".equals(receipt.status())
        || !accountUuid.equals(receipt.initiatingAccountUuid())
        || !tenantUuid.equals(receipt.tenantUuid())
        || !creation.operationId().equals(receipt.creationOperationId())
        || !creation.creationRequestId().equals(receipt.creationRequestId())
        || !Objects.equals(account.getId(), membership.getAccount().getId())
        || !tenantUuid.equals(membership.getTenantUuid())
        || membership.getTenantId() != null
        || !provenance.kind().name().equals(membership.getTenantProvenanceKind())
        || !provenance.sourceOperationId().equals(membership.getTenantSourceOperationId())
        || !provenance.digest().equals(membership.getTenantProvenanceDigest())
        || !"TENANT_CREATION".equals(membership.getAuthorityProvenance())
        || !"ACTIVE".equals(membership.getLifecycleState())
        || membership.isGameplayAdmissionAllowed()
        || !Objects.equals(receipt.membershipId(), membership.getId())
        || !Objects.equals(receipt.membershipVersion(), membership.getMembershipVersion())
        || !Objects.equals(
            receipt.membershipAuthorityGeneration(), membership.getMembershipAuthorityGeneration())
        || receipt.baselineEventSequence() != 0L
        || membership.getMembershipVersion()
            != Math.incrementExact(receipt.baselineMembershipVersion())
        || membership.getMembershipAuthorityGeneration()
            != receipt.baselineMembershipAuthorityGeneration()
        || memberSource.generation() != membership.getMembershipAuthorityGeneration()) {
      throw new IllegalStateException(
          "Creator control membership differs from its exact bootstrap source");
    }
    RoleSnapshot roles =
        roleSnapshotRepository
            .findForCanonicalUpdate(
                accountUuid,
                tenantUuid,
                provenance,
                membership.getId(),
                membership.getMembershipVersion())
            .orElseThrow(() -> new IllegalStateException("Creator control role source is absent"));
    if (!List.of("tenantAdmin").equals(roles.roles())
        || roles.accountId() != account.getId()
        || roles.tenantId() != null
        || !accountUuid.equals(roles.accountUuid())
        || !tenantUuid.equals(roles.tenantUuid())
        || !provenance.equals(roles.tenantProvenance())
        || roles.membershipId() != membership.getId()
        || roles.snapshotVersion() != membership.getMembershipVersion()
        || !Arrays.equals(
            receipt.membershipRolesPayload(),
            "[\"tenantAdmin\"]".getBytes(StandardCharsets.UTF_8))) {
      throw new IllegalStateException(
          "Creator control roles differ from the exact bootstrap receipt");
    }
    String stream = membershipStreamKey(identity);
    Checkpoint checkpoint =
        authorityOutboxRepository
            .readCheckpoint(stream)
            .orElseThrow(
                () -> new IllegalStateException("Creator current event checkpoint is absent"));
    Event event =
        authorityOutboxRepository
            .findEvent(stream, checkpoint.outboxSequence())
            .orElseThrow(() -> new IllegalStateException("Creator current event source is absent"));
    MembershipEvent verified = verifyStoredEvent(event, identity, receipt.eventRequestId());
    if (checkpoint.outboxSequence() != 1L
        || !checkpointMatches(checkpoint, event)
        || !stream.equals(receipt.eventStreamKey())
        || !Objects.equals(receipt.eventSequence(), 1L)
        || !event.eventId().equals(receipt.eventId())
        || !event.eventDigest().equals(receipt.eventDigest())
        || !Arrays.equals(event.payload(), receipt.eventPayload())
        || !pair.equals(
            new PairAuthority(
                accountUuid,
                tenantUuid,
                provenance,
                true,
                membership.getMembershipVersion(),
                membership.getMembershipAuthorityGeneration(),
                1L,
                event.eventId(),
                event.eventDigest(),
                false))) {
      throw new IllegalStateException(
          "Creator control event or pair differs from the bootstrap receipt");
    }
    List<OutboxCheckpointEntry> checkpoints = new ArrayList<>(upstream.checkpoints());
    checkpoints.add(new OutboxCheckpointEntry(stream, "1"));
    List<OutboxSourceEvidence> sources = new ArrayList<>(upstream.sourceEvidence());
    sources.add(sourceEvidence(checkpoint, event));
    AuthorityTuple tuple = currentAuthorityTuple(tenantUuid, authority, upstream);
    Map<String, String> version =
        Map.of(tenantUuid.toString(), Long.toString(membership.getMembershipVersion()));
    var boundEvent =
        RuntimeMembershipAuthorityEvidenceValidator.validate(
            validatorSnapshot(
                accountUuid.toString(),
                tenantUuid.toString(),
                true,
                "ACTIVE",
                false,
                version,
                Long.toString(memberSource.generation()),
                List.of("tenantAdmin"),
                tuple,
                decimal(authority.issuanceFence().value()),
                orderedCheckpoints(checkpoints),
                orderedSourceEvidence(sources)));
    if (boundEvent.isEmpty()
        || !verified.canonicalJson().equals(boundEvent.orElseThrow().canonicalJson())
        || verified.callerBoundAuthorityInvalidated()) {
      throw new IllegalStateException(
          "Creator control event does not bind the current source capture");
    }
    requireUnchangedAccountIdentity(account, accountUuid);
    if (!Optional.of(creation).equals(freshTenantIdentityAssociationRepository.read(tenantUuid))) {
      throw new IllegalStateException("Creator fresh tenant source changed during capture");
    }
    return new CreatorControlCaptureSources(
        accountUuid,
        tenantUuid,
        authority,
        version,
        tuple,
        decimal(authority.issuanceFence().value()),
        pair,
        roles,
        creation,
        receipt,
        verified,
        orderedCheckpoints(checkpoints),
        orderedSourceEvidence(sources),
        Instant.now());
  }

  private CurrentSourceEvidence readCurrentUpstreamSourceEvidence(
      UUID accountUuid, UUID tenantUuid, CompositeSnapshot authority) {
    IssuerAccountSourceSnapshot source =
        sourceEvidenceRepository.readCurrentIssuerAccountSources(ISSUER, accountUuid);
    ScopeState tenant = only(authority.tenants(), "tenant");
    Account account = accountRepository.findByAccountUuid(accountUuid).orElseThrow();
    requirePersistedAccountIdentity(account, accountUuid);
    if (!AuthorityScope.issuer(ISSUER).equals(authority.issuer().scope())
        || !AuthorityScope.account(accountUuid).equals(authority.account().scope())
        || !AuthorityScope.tenant(tenantUuid).equals(tenant.scope())
        || !authority.issuanceFence().equals(source.issuanceFence())
        || !authority.issuanceFence().equals(authority.account().issuanceFence())
        || !accountUuid.equals(authority.issuanceFence().accountId())
        || source.account().accountSourceNumericId() == null
        || !Objects.equals(source.account().accountSourceNumericId(), account.getId())
        || source.issuer().generation() != authority.issuer().generation()
        || source.issuer().sourceVersion() != authority.issuer().sourceVersion()
        || source.account().generation() != authority.account().generation()
        || source.account().sourceVersion() != authority.account().sourceVersion()
        || tenant.generation() != 1L
        || tenant.sourceVersion() != 1L
        || authorityOutboxRepository
            .readCheckpoint(
                MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX + "tenant/" + tenantUuid)
            .isPresent()) {
      throw new IllegalStateException(
          "Current creator upstream sources are unavailable or mismatched");
    }
    // The source repository proves complete retained histories and exact live Account state;
    // these projections additionally validate their current closed source-event schemas.
    net.firedevops.firemud.accountservice.service.session
        .AccountGameplayDelegationAuthorityProjection.canonicalProjectionPair(source);
    FreshTenantCreationEvidence fresh =
        freshTenantIdentityAssociationRepository
            .read(tenantUuid)
            .orElseThrow(
                () -> new IllegalStateException("Current fresh tenant provenance is absent"));
    if (!tenantUuid.equals(fresh.canonicalTenantId())) {
      throw new IllegalStateException("Current fresh tenant provenance differs");
    }
    List<OutboxCheckpointEntry> checkpoints = new ArrayList<>();
    List<OutboxSourceEvidence> sources = new ArrayList<>();
    for (var current : List.of(source.issuer(), source.account())) {
      var checkpoint = current.checkpoint();
      checkpoints.add(
          new OutboxCheckpointEntry(
              checkpoint.outboxStreamKey(), Long.toString(checkpoint.sequence())));
      if (checkpoint.sequence() > 0L) {
        Checkpoint stored =
            authorityOutboxRepository.readCheckpoint(checkpoint.outboxStreamKey()).orElseThrow();
        Event event =
            authorityOutboxRepository
                .findEvent(checkpoint.outboxStreamKey(), checkpoint.sequence())
                .orElseThrow();
        if (stored.outboxSequence() != checkpoint.sequence()
            || !checkpoint.sourceEventId().orElseThrow().equals(stored.sourceEventId())
            || !checkpoint.sourceEventDigest().orElseThrow().equals(stored.sourceEventDigest())) {
          throw new IllegalStateException("Current source checkpoint differs from owner proof");
        }
        sources.add(sourceEvidence(stored, event));
      }
    }
    checkpoints.add(
        new OutboxCheckpointEntry(
            MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX + "tenant/" + tenantUuid, "0"));
    return new CurrentSourceEvidence(
        orderedCheckpoints(checkpoints),
        orderedSourceEvidence(sources),
        source
            .account()
            .accountSecurityCutoff()
            .map(
                c ->
                    new MembershipAuthorityEventV1Codec.AccountSecurityCutoff(
                        c.accountAuthorityGeneration(), c.outboxStreamKey(), c.outboxSequence())));
  }

  private CompositeSnapshot readSnapshot(UUID accountUuid, UUID tenantUuid) {
    return authorityGenerationRepository.readCompositeSnapshot(
        ISSUER, accountUuid, List.of(tenantUuid), List.of(tenantUuid));
  }

  private static AuthorityTuple currentAuthorityTuple(
      UUID tenantUuid, CompositeSnapshot authority, CurrentSourceEvidence sources) {
    return new AuthorityTuple(
        decimal(authority.issuer().generation()),
        decimal(authority.account().generation()),
        Map.of(tenantUuid.toString(), decimal(only(authority.tenants(), "tenant").generation())),
        Map.of(
            tenantUuid.toString(),
            decimal(only(authority.memberships(), "membership").generation())),
        List.of(),
        sources.accountSecurityCutoff(),
        Optional.empty());
  }

  private static void requireMatchingFence(
      CompositeSnapshot authority, ScopeState member, UUID accountUuid) {
    if (!AuthorityScope.membership(
                accountUuid, only(authority.tenants(), "tenant").scope().tenantId())
            .equals(member.scope())
        || !accountUuid.equals(authority.issuanceFence().accountId())
        || !authority.issuanceFence().equals(authority.account().issuanceFence())
        || !authority.issuanceFence().equals(member.issuanceFence())) {
      throw new IllegalStateException("Creator membership source fence differs");
    }
  }

  private static MembershipEvent verifyStoredEvent(
      Event event, Identity identity, String requestId) {
    MembershipEvent verified =
        MembershipAuthorityEventV1Codec.verify(new String(event.payload(), StandardCharsets.UTF_8));
    if (!requestId.equals(event.requestId())
        || !requestId.equals(verified.requestId())
        || !membershipStreamKey(identity).equals(event.outboxStreamKey())
        || !event.outboxStreamKey().equals(verified.outboxStreamKey())
        || event.outboxSequence() != 1L
        || !"1".equals(verified.outboxSequence())
        || !event.eventId().equals(verified.eventId())
        || !event.eventDigest().equals(verified.eventDigest())
        || !UUID.nameUUIDFromBytes(
                (MembershipAuthorityEventV1Codec.SCHEMA_VERSION + ":" + requestId)
                    .getBytes(StandardCharsets.UTF_8))
            .toString()
            .equals(verified.eventId())
        || !identity.accountUuid().toString().equals(verified.accountId())
        || !identity.tenantUuid().toString().equals(verified.tenantId())
        || !Arrays.equals(event.payload(), verified.canonicalJsonUtf8())) {
      throw new IllegalStateException("Creator event differs from its immutable source columns");
    }
    return verified;
  }

  private static boolean checkpointMatches(Checkpoint c, Event e) {
    return c.outboxStreamKey().equals(e.outboxStreamKey())
        && c.outboxSequence() == e.outboxSequence()
        && c.sourceEventId().equals(e.eventId())
        && c.sourceEventDigest().equals(e.eventDigest());
  }

  private static OutboxSourceEvidence sourceEvidence(Checkpoint c, Event e) {
    if (!checkpointMatches(c, e))
      throw new IllegalStateException("Creator checkpoint differs from event");
    return new OutboxSourceEvidence(
        c.outboxStreamKey(),
        Long.toString(c.outboxSequence()),
        e.eventId(),
        e.eventDigest(),
        new String(e.payload(), StandardCharsets.UTF_8));
  }

  private static List<OutboxCheckpointEntry> orderedCheckpoints(
      List<OutboxCheckpointEntry> values) {
    return values.stream()
        .sorted(Comparator.comparing(OutboxCheckpointEntry::outboxStreamKey))
        .toList();
  }

  private static List<OutboxSourceEvidence> orderedSourceEvidence(
      List<OutboxSourceEvidence> values) {
    return values.stream()
        .sorted(Comparator.comparing(OutboxSourceEvidence::outboxStreamKey))
        .toList();
  }

  private static ScopeState only(List<ScopeState> values, String label) {
    if (values.size() != 1)
      throw new IllegalStateException("Expected exactly one " + label + " source");
    return values.getFirst();
  }

  private static VerifiedTenantProvenance freshTenantProvenance(FreshTenantCreationEvidence fresh) {
    return new VerifiedTenantProvenance(
        null, TenantProvenanceKind.FRESH_GAME_DESIGN, fresh.operationId(), fresh.evidenceDigest());
  }

  private static void requireCanonicalUuidInput(UUID uuid, String label) {
    if (uuid == null || new UUID(0L, 0L).equals(uuid))
      throw new IllegalArgumentException(label + " must be non-nil");
  }

  private static void requirePersistedAccountIdentity(Account account, UUID uuid) {
    if (account == null
        || account.getId() == null
        || account.getId() <= 0L
        || !uuid.equals(account.getAccountUuid())
        || account.getAccountUuidProvenance() != AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT
        || !account.getId().equals(account.getAccountUuidSourceNumericId())) {
      throw new IllegalStateException("Creator Account source identity differs");
    }
  }

  private void requireUnchangedAccountIdentity(Account before, UUID uuid) {
    Account after = accountRepository.findByAccountUuid(uuid).orElseThrow();
    requirePersistedAccountIdentity(after, uuid);
    if (!before.getId().equals(after.getId()))
      throw new IllegalStateException("Creator Account identity changed");
  }

  private static String decimal(long value) {
    if (value <= 0L) throw new IllegalStateException("Creator source counters must be positive");
    return Long.toString(value);
  }

  private static String membershipStreamKey(Identity identity) {
    return membershipStreamKey(identity.accountUuid(), identity.tenantUuid());
  }

  private static String membershipStreamKey(UUID account, UUID tenant) {
    return MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX
        + "membership/"
        + account
        + "/"
        + tenant;
  }

  private static void requireWritableOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Creator source capture requires a writable Account owner transaction");
    }
  }

  private record Identity(
      long accountId,
      Long legacyTenantId,
      UUID accountUuid,
      UUID tenantUuid,
      VerifiedTenantProvenance provenance) {}

  private record CurrentSourceEvidence(
      List<OutboxCheckpointEntry> checkpoints,
      List<OutboxSourceEvidence> sourceEvidence,
      Optional<MembershipAuthorityEventV1Codec.AccountSecurityCutoff> accountSecurityCutoff) {}
}
