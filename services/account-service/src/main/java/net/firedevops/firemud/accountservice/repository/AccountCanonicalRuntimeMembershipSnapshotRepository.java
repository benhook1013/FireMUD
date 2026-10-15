package net.firedevops.firemud.accountservice.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.entity.AccountLifecycleState;
import net.firedevops.firemud.accountservice.entity.AccountTenantMembership;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.CompositeSnapshot;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Checkpoint;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Event;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.CurrentSourceEvidence;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.IssuerAccountSourceSnapshot;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.PairAuthority;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.TenantProvenanceKind;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.VerifiedTenantProvenance;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository.RoleSnapshot;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.AccountSecurityCutoff;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.AuthorityTuple;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.MembershipEvent;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.TenantBillingCutoff;
import net.firedevops.firemud.common.account.authority.RuntimeMembershipAuthorityEvidenceValidator;
import net.firedevops.firemud.common.account.authority.RuntimeMembershipAuthorityEvidenceValidator.Snapshot;
import net.firedevops.firemud.common.account.authority.RuntimeMembershipAuthorityEvidenceValidator.SourceEvidence;
import net.firedevops.firemud.common.account.authority.TenantAuthorityEventV1Codec;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Reads one unregistered, Account-local current public-membership evidence snapshot.
 *
 * <p>This component does not authenticate a source or authorize gameplay admission. It performs no
 * writes and returns the existing content-validator carrier only after Account owner readbacks and
 * event bindings agree in the caller's single owner transaction.
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected Account repositories are internal owner-transaction collaborators.")
public final class AccountCanonicalRuntimeMembershipSnapshotRepository {
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final String ISSUER = "firemud-account-service";
  private static final Comparator<String> UTF8_ORDER =
      AccountCanonicalRuntimeMembershipSnapshotRepository::compareUtf8;

  private final AccountRepository accounts;
  private final AccountAuthorityGenerationRepository generations;
  private final AccountAuthoritySourceEvidenceRepository sources;
  private final AccountTenantAuthorityEventRepository tenantEvents;
  private final FreshTenantIdentityAssociationRepository freshTenants;
  private final AccountTenantMembershipRepository memberships;
  private final AccountMembershipPairAuthorityRepository pairs;
  private final AccountTenantMembershipRoleSnapshotRepository roles;
  private final AccountAuthorityOutboxRepository outbox;

  public AccountCanonicalRuntimeMembershipSnapshotRepository(
      AccountRepository accounts,
      AccountAuthorityGenerationRepository generations,
      AccountAuthoritySourceEvidenceRepository sources,
      AccountTenantAuthorityEventRepository tenantEvents,
      FreshTenantIdentityAssociationRepository freshTenants,
      AccountTenantMembershipRepository memberships,
      AccountMembershipPairAuthorityRepository pairs,
      AccountTenantMembershipRoleSnapshotRepository roles,
      AccountAuthorityOutboxRepository outbox) {
    this.accounts = Objects.requireNonNull(accounts);
    this.generations = Objects.requireNonNull(generations);
    this.sources = Objects.requireNonNull(sources);
    this.tenantEvents = Objects.requireNonNull(tenantEvents);
    this.freshTenants = Objects.requireNonNull(freshTenants);
    this.memberships = Objects.requireNonNull(memberships);
    this.pairs = Objects.requireNonNull(pairs);
    this.roles = Objects.requireNonNull(roles);
    this.outbox = Objects.requireNonNull(outbox);
  }

  /** Builds and validates one snapshot from exact UUID-qualified Account owner reads. */
  public Snapshot readCurrent(UUID accountUuid, UUID tenantUuid) {
    requireOwnerTransaction();
    requireCanonicalUuid(accountUuid, "Account UUID");
    requireCanonicalUuid(tenantUuid, "tenant UUID");

    CompositeSnapshot composite =
        generations.readCompositeSnapshot(
            ISSUER, accountUuid, List.of(tenantUuid), List.of(tenantUuid));
    ScopeState tenantScope = only(composite.tenants(), "tenant");
    ScopeState membershipScope = only(composite.memberships(), "membership");
    requireScope(
        tenantScope, AccountAuthorityGenerationRepository.AuthorityScope.tenant(tenantUuid));
    requireScope(
        membershipScope,
        AccountAuthorityGenerationRepository.AuthorityScope.membership(accountUuid, tenantUuid));

    IssuerAccountSourceSnapshot currentSources =
        sources.readCurrentIssuerAccountSources(ISSUER, accountUuid);
    requireCurrentSource(currentSources.issuer(), composite.issuer());
    requireCurrentSource(currentSources.account(), composite.account());
    if (!composite.issuanceFence().equals(currentSources.issuanceFence())
        || !composite.issuanceFence().equals(currentSources.account().issuanceFence())) {
      throw unavailable("Account issuance fence differs across current owner reads");
    }

    Account account =
        accounts
            .findByAccountUuid(accountUuid)
            .orElseThrow(() -> unavailable("Canonical Account row is absent"));
    requireActiveCanonicalAccount(account, accountUuid, currentSources.account());

    FreshTenantCreationEvidence freshTenant =
        freshTenants
            .read(tenantUuid)
            .orElseThrow(() -> unavailable("Fresh tenant source association is absent"));
    TenantAuthorityEventV1Codec.Event tenantEvent = tenantEvents.readCurrentByTenant(tenantUuid);
    requireTenantSource(tenantUuid, tenantScope, freshTenant, tenantEvent);
    Checkpoint tenantCheckpoint =
        requireCheckpoint(tenantEvent.outboxStreamKey(), tenantEvent.outboxSequence());
    requireCheckpointMatches(
        tenantCheckpoint,
        tenantEvent.outboxStreamKey(),
        tenantEvent.outboxSequence(),
        tenantEvent.eventId().toString(),
        tenantEvent.eventDigest());

    VerifiedTenantProvenance provenance =
        new VerifiedTenantProvenance(
            null,
            TenantProvenanceKind.FRESH_GAME_DESIGN,
            freshTenant.operationId(),
            freshTenant.evidenceDigest());
    PairAuthority pair =
        pairs
            .readState(accountUuid, tenantUuid)
            .orElseThrow(() -> unavailable("Account membership absence baseline is absent"));
    requirePairIdentity(pair, accountUuid, tenantUuid, provenance, membershipScope);
    Optional<AccountTenantMembership> membership =
        memberships.findFreshMembership(accountUuid, tenantUuid);

    List<String> currentRoles;
    String lifecycle;
    boolean admission;
    String membershipVersion;
    if (membership.isPresent()) {
      AccountTenantMembership current = membership.orElseThrow();
      requireMembershipIdentity(current, account, tenantUuid, provenance, pair);
      RoleSnapshot roleSnapshot =
          roles
              .findForCanonicalUpdate(
                  accountUuid,
                  tenantUuid,
                  provenance,
                  current.getId(),
                  current.getMembershipVersion())
              .orElseThrow(
                  () -> unavailable("Committed current membership role snapshot is absent"));
      requireRoleSnapshot(roleSnapshot, account, current, provenance);
      currentRoles = roleSnapshot.roles();
      lifecycle = current.getLifecycleState();
      admission = current.isGameplayAdmissionAllowed();
      membershipVersion = Long.toString(current.getMembershipVersion());
    } else {
      requireAbsentBaseline(pair, membershipScope);
      currentRoles = List.of();
      lifecycle = "MISSING";
      admission = false;
      membershipVersion = Long.toString(pair.membershipVersion());
    }

    String memberStream = membershipStreamKey(accountUuid, tenantUuid);
    List<RuntimeMembershipAuthorityEvidenceValidator.Checkpoint> checkpoints = new ArrayList<>(4);
    List<SourceEvidence> sourceEvents = new ArrayList<>(4);
    addCurrentSource(
        checkpoints,
        sourceEvents,
        currentSources.issuer(),
        currentSources.canonicalIssuerProjection().sourceEvent());
    addCurrentSource(
        checkpoints,
        sourceEvents,
        currentSources.account(),
        currentSources.canonicalAccountProjection().sourceEvent());

    checkpoints.add(
        validatorCheckpoint(tenantCheckpoint.outboxStreamKey(), tenantCheckpoint.outboxSequence()));
    sourceEvents.add(
        sourceEvidence(
            tenantCheckpoint.outboxStreamKey(),
            tenantCheckpoint.outboxSequence(),
            tenantCheckpoint.sourceEventId(),
            tenantCheckpoint.sourceEventDigest(),
            decodeUtf8(tenantEvent.payload(), "tenant authority event")));

    Optional<Checkpoint> memberCheckpoint = outbox.readCheckpoint(memberStream);
    if (membership.isPresent()) {
      Checkpoint currentCheckpoint =
          memberCheckpoint.orElseThrow(
              () -> unavailable("Positive membership source checkpoint is absent"));
      requireCheckpointMatches(
          currentCheckpoint,
          memberStream,
          pair.eventSequence(),
          pair.eventId(),
          pair.eventDigest());
      Event stored =
          outbox
              .findEvent(memberStream, currentCheckpoint.outboxSequence())
              .orElseThrow(() -> unavailable("Positive membership source event is absent"));
      MembershipEvent memberEvent =
          MembershipAuthorityEventV1Codec.verify(
              decodeUtf8(stored.payload(), "membership authority event"));
      requireMembershipEvent(
          memberEvent,
          stored,
          accountUuid,
          tenantUuid,
          membership.orElseThrow(),
          currentRoles,
          pair);
      checkpoints.add(
          validatorCheckpoint(
              currentCheckpoint.outboxStreamKey(), currentCheckpoint.outboxSequence()));
      sourceEvents.add(
          sourceEvidence(
              stored.outboxStreamKey(),
              stored.outboxSequence(),
              stored.eventId(),
              stored.eventDigest(),
              memberEvent.canonicalJson()));
    } else {
      if (memberCheckpoint.isPresent()) {
        throw unavailable("Absent membership has a retained positive source checkpoint");
      }
      checkpoints.add(validatorCheckpoint(memberStream, 0L));
    }

    checkpoints.sort(
        Comparator.comparing(
            RuntimeMembershipAuthorityEvidenceValidator.Checkpoint::outboxStreamKey, UTF8_ORDER));
    sourceEvents.sort(Comparator.comparing(SourceEvidence::outboxStreamKey, UTF8_ORDER));
    AuthorityTuple tuple =
        new AuthorityTuple(
            Long.toString(composite.issuer().generation()),
            Long.toString(composite.account().generation()),
            Map.of(tenantUuid.toString(), Long.toString(tenantScope.generation())),
            Map.of(tenantUuid.toString(), Long.toString(membershipScope.generation())),
            List.of(),
            currentSources
                .account()
                .accountSecurityCutoff()
                .map(
                    cutoff ->
                        new AccountSecurityCutoff(
                            cutoff.accountAuthorityGeneration(),
                            cutoff.outboxStreamKey(),
                            cutoff.outboxSequence())),
            Optional.of(
                Map.of(
                    tenantUuid.toString(),
                    new TenantBillingCutoff(
                        Long.toString(tenantEvent.tenantAuthorityGeneration()),
                        Long.toString(tenantEvent.tenantBillingSequence()),
                        tenantEvent.outboxStreamKey(),
                        Long.toString(tenantEvent.outboxSequence())))));
    Snapshot snapshot =
        new Snapshot(
            ISSUER,
            accountUuid.toString(),
            tenantUuid.toString(),
            membership.isPresent(),
            lifecycle,
            admission,
            Map.of(tenantUuid.toString(), membershipVersion),
            Long.toString(membershipScope.generation()),
            currentRoles,
            tuple,
            Long.toString(composite.issuanceFence().value()),
            checkpoints,
            sourceEvents);
    RuntimeMembershipAuthorityEvidenceValidator.validate(snapshot);
    return snapshot;
  }

  private static void requireCurrentSource(CurrentSourceEvidence evidence, ScopeState state) {
    if (!evidence.scope().equals(state.scope())
        || evidence.generation() != state.generation()
        || evidence.sourceVersion() != state.sourceVersion()) {
      throw unavailable("Current source evidence differs from its composite owner generation");
    }
  }

  private static void requireActiveCanonicalAccount(
      Account account, UUID accountUuid, CurrentSourceEvidence source) {
    if (account.getId() == null
        || account.getId() <= 0L
        || !accountUuid.equals(account.getAccountUuid())
        || account.getAccountUuidProvenance() != AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT
        || account.getAccountUuidSourceNumericId() == null
        || account.getAccountUuidSourceNumericId() <= 0L
        || account.getLifecycleState() != AccountLifecycleState.ACTIVE
        || !Objects.equals(source.accountSourceNumericId(), account.getId())
        || !AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT
            .name()
            .equals(source.accountUuidProvenance())
        || !Objects.equals(
            source.accountRepositoryInsertTransactionId(), source.initializationTransactionId())) {
      throw unavailable("Current Account lifecycle or insert provenance is unavailable");
    }
  }

  private static void requireTenantSource(
      UUID tenantUuid,
      ScopeState scope,
      FreshTenantCreationEvidence freshTenant,
      TenantAuthorityEventV1Codec.Event event) {
    if (!tenantUuid.equals(freshTenant.canonicalTenantId())
        || !tenantUuid.equals(event.tenantId())
        || !freshTenant.equals(event.sourceEvidence())
        || event.tenantAuthorityGeneration() != scope.generation()
        || event.tenantAuthoritySourceVersion() != scope.sourceVersion()
        || !TenantAuthorityEventV1Codec.streamKey(tenantUuid).equals(event.outboxStreamKey())) {
      throw unavailable("Current tenant event differs from its fresh source or authority scope");
    }
  }

  private static void requirePairIdentity(
      PairAuthority pair,
      UUID accountUuid,
      UUID tenantUuid,
      VerifiedTenantProvenance provenance,
      ScopeState scope) {
    if (!accountUuid.equals(pair.accountUuid())
        || !tenantUuid.equals(pair.tenantUuid())
        || !provenance.equals(pair.provenance())
        || pair.membershipAuthorityGeneration() != scope.generation()) {
      throw unavailable("Current membership pair differs from its exact fresh source scope");
    }
  }

  private static void requireMembershipIdentity(
      AccountTenantMembership membership,
      Account account,
      UUID tenantUuid,
      VerifiedTenantProvenance provenance,
      PairAuthority pair) {
    if (membership.getAccount() == null
        || !Objects.equals(membership.getAccount().getId(), account.getId())
        || membership.getTenantId() != null
        || !tenantUuid.equals(membership.getTenantUuid())
        || !"FRESH_GAME_DESIGN".equals(membership.getTenantProvenanceKind())
        || !provenance.sourceOperationId().equals(membership.getTenantSourceOperationId())
        || !provenance.digest().equals(membership.getTenantProvenanceDigest())
        || membership.getMembershipVersion() != pair.membershipVersion()
        || membership.getMembershipAuthorityGeneration() != pair.membershipAuthorityGeneration()
        || !pair.membershipExists()
        || pair.eventSequence() <= 0L) {
      throw unavailable("Current membership row differs from its exact owner pair state");
    }
  }

  private static void requireRoleSnapshot(
      RoleSnapshot snapshot,
      Account account,
      AccountTenantMembership membership,
      VerifiedTenantProvenance provenance) {
    if (snapshot.accountId() != account.getId()
        || snapshot.tenantId() != null
        || !Objects.equals(snapshot.accountUuid(), account.getAccountUuid())
        || !Objects.equals(snapshot.tenantUuid(), membership.getTenantUuid())
        || !provenance.equals(snapshot.tenantProvenance())
        || snapshot.membershipId() != membership.getId()
        || snapshot.snapshotVersion() != membership.getMembershipVersion()) {
      throw unavailable("Current membership role snapshot differs from its owner row");
    }
  }

  private static void requireAbsentBaseline(PairAuthority pair, ScopeState scope) {
    if (pair.membershipExists()
        || pair.membershipVersion() != 1L
        || pair.membershipAuthorityGeneration() != 1L
        || pair.eventSequence() != 0L
        || pair.eventId() != null
        || pair.eventDigest() != null
        || pair.lastTransitionInvalidated()
        || scope.generation() != 1L
        || scope.sourceVersion() != 1L) {
      throw unavailable("Absent membership is not the exact original Account baseline");
    }
  }

  private static void requireMembershipEvent(
      MembershipEvent event,
      Event stored,
      UUID accountUuid,
      UUID tenantUuid,
      AccountTenantMembership membership,
      List<String> currentRoles,
      PairAuthority pair) {
    if (!stored.outboxStreamKey().equals(event.outboxStreamKey())
        || stored.outboxSequence() != Long.parseLong(event.outboxSequence())
        || !stored.eventId().equals(event.eventId())
        || !stored.eventDigest().equals(event.eventDigest())
        || !accountUuid.toString().equals(event.accountId())
        || !tenantUuid.toString().equals(event.tenantId())
        || !pair.membershipExists()
        || !membership.getLifecycleState().equals(event.membershipLifecycleState())
        || membership.isGameplayAdmissionAllowed() != event.gameplayAdmissionAllowed()
        || !currentRoles.equals(event.roles())
        || !Map.of(tenantUuid.toString(), Long.toString(membership.getMembershipVersion()))
            .equals(event.membershipVersion())
        || !Long.toString(pair.membershipAuthorityGeneration())
            .equals(event.membershipAuthorityGeneration())
        || pair.eventSequence() != stored.outboxSequence()
        || !pair.eventId().equals(stored.eventId())
        || !pair.eventDigest().equals(stored.eventDigest())) {
      throw unavailable("Committed membership event differs from its current row and pair");
    }
  }

  private static void addCurrentSource(
      List<RuntimeMembershipAuthorityEvidenceValidator.Checkpoint> checkpoints,
      List<SourceEvidence> sourceEvents,
      CurrentSourceEvidence current,
      Optional<String> canonicalEvent) {
    var checkpoint = current.checkpoint();
    checkpoints.add(validatorCheckpoint(checkpoint.outboxStreamKey(), checkpoint.sequence()));
    if (checkpoint.sequence() == 0L) {
      if (canonicalEvent.isPresent())
        throw unavailable("Sequence-zero source has an event payload");
      return;
    }
    sourceEvents.add(
        sourceEvidence(
            checkpoint.outboxStreamKey(),
            checkpoint.sequence(),
            checkpoint.sourceEventId().orElseThrow(() -> unavailable("Source event ID is absent")),
            checkpoint
                .sourceEventDigest()
                .orElseThrow(() -> unavailable("Source event digest is absent")),
            canonicalEvent.orElseThrow(
                () -> unavailable("Current source event payload is absent"))));
  }

  private Checkpoint requireCheckpoint(String stream, long expectedSequence) {
    if (expectedSequence <= 0L) {
      throw unavailable("Current positive source event has a nonpositive checkpoint");
    }
    Checkpoint checkpoint =
        outbox
            .readCheckpoint(stream)
            .orElseThrow(() -> unavailable("Current positive source checkpoint is absent"));
    if (!stream.equals(checkpoint.outboxStreamKey())
        || checkpoint.outboxSequence() != expectedSequence) {
      throw unavailable(
          "Current positive source checkpoint differs from its exact stream and event");
    }
    return checkpoint;
  }

  private static void requireCheckpointMatches(
      Checkpoint checkpoint,
      String expectedStream,
      long sequence,
      String eventId,
      String eventDigest) {
    if (!expectedStream.equals(checkpoint.outboxStreamKey())
        || checkpoint.outboxSequence() != sequence
        || !checkpoint.sourceEventId().equals(eventId)
        || !checkpoint.sourceEventDigest().equals(eventDigest)) {
      throw unavailable("Current event differs from its exact committed checkpoint");
    }
  }

  private static RuntimeMembershipAuthorityEvidenceValidator.Checkpoint validatorCheckpoint(
      String stream, long sequence) {
    return new RuntimeMembershipAuthorityEvidenceValidator.Checkpoint(
        stream, Long.toString(sequence));
  }

  private static SourceEvidence sourceEvidence(
      String stream, long sequence, String eventId, String digest, String json) {
    return new SourceEvidence(stream, Long.toString(sequence), eventId, digest, json);
  }

  private static String membershipStreamKey(UUID accountUuid, UUID tenantUuid) {
    return MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX
        + "membership/"
        + accountUuid
        + "/"
        + tenantUuid;
  }

  private static String decodeUtf8(byte[] payload, String label) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(payload))
          .toString();
    } catch (CharacterCodingException malformed) {
      throw unavailable(label + " is not valid UTF-8");
    }
  }

  private static ScopeState only(List<ScopeState> states, String label) {
    if (states.size() != 1) {
      throw unavailable("Composite owner read lacks exactly one " + label + " scope");
    }
    return states.getFirst();
  }

  private static void requireScope(
      ScopeState actual, AccountAuthorityGenerationRepository.AuthorityScope expected) {
    if (!expected.equals(actual.scope())) {
      throw unavailable("Composite owner read returned a different authority scope");
    }
  }

  private static int compareUtf8(String left, String right) {
    byte[] leftBytes = left.getBytes(StandardCharsets.UTF_8);
    byte[] rightBytes = right.getBytes(StandardCharsets.UTF_8);
    int common = Math.min(leftBytes.length, rightBytes.length);
    for (int index = 0; index < common; index++) {
      int compared =
          Integer.compare(
              Byte.toUnsignedInt(leftBytes[index]), Byte.toUnsignedInt(rightBytes[index]));
      if (compared != 0) return compared;
    }
    return Integer.compare(leftBytes.length, rightBytes.length);
  }

  private static void requireCanonicalUuid(UUID value, String label) {
    if (value == null || NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(label + " must be a non-nil canonical UUID");
    }
  }

  private static void requireOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw unavailable(
          "Current membership snapshot requires a writable Account owner transaction");
    }
  }

  private static IllegalStateException unavailable(String message) {
    return new IllegalStateException(message);
  }
}
