package net.firedevops.firemud.accountservice.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.entity.AccountTenantMembership;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.PairAuthority;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.TenantProvenanceKind;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.VerifiedTenantProvenance;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.MembershipEvent;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Reads only complete, current Account-owned evidence for non-admitting actor staging. */
@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected repositories and DSLContext are private Account read collaborators.")
public class AccountActorStagingEligibilityRepository {
  private final DSLContext dsl;
  private final AccountRepository accounts;
  private final FreshTenantIdentityAssociationRepository tenantIdentity;
  private final AccountTenantMembershipRepository memberships;
  private final AccountMembershipPairAuthorityRepository pairAuthority;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification = "Spring must proxy this repository and all collaborators are required.")
  public AccountActorStagingEligibilityRepository(
      DSLContext dsl,
      AccountRepository accounts,
      FreshTenantIdentityAssociationRepository tenantIdentity,
      AccountTenantMembershipRepository memberships,
      AccountMembershipPairAuthorityRepository pairAuthority) {
    this.dsl = Objects.requireNonNull(dsl);
    this.accounts = Objects.requireNonNull(accounts);
    this.tenantIdentity = Objects.requireNonNull(tenantIdentity);
    this.memberships = Objects.requireNonNull(memberships);
    this.pairAuthority = Objects.requireNonNull(pairAuthority);
  }

  /**
   * Reads identity, membership, pair authority, tenant provenance, checkpoint, and committed event
   * inside the caller's single repeatable-read Account snapshot. Missing or contradictory evidence
   * fails closed; this method never enrolls a pair or creates membership.
   */
  @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
  public CurrentSnapshot readCurrent(UUID accountUuid, UUID tenantUuid) {
    requireReadOnlyOwnerTransaction();
    requireCanonicalUuid(accountUuid, "Account UUID");
    requireCanonicalUuid(tenantUuid, "tenant UUID");

    Account account =
        accounts
            .findByAccountUuid(accountUuid)
            .orElseThrow(() -> new IllegalStateException("Canonical Account identity is absent"));
    if (account.getId() == null
        || account.getId() <= 0L
        || !accountUuid.equals(account.getAccountUuid())
        || account.getAccountUuidProvenance() == null
        || !AccountIdentityProvenance.isAccepted(account.getAccountUuidProvenance())
        || !Objects.equals(account.getId(), account.getAccountUuidSourceNumericId())
        || account.getLifecycleState() == null) {
      throw new IllegalStateException("Canonical Account identity or lifecycle is contradictory");
    }

    FreshTenantCreationEvidence tenantSource =
        tenantIdentity
            .read(tenantUuid)
            .orElseThrow(
                () -> new IllegalStateException("Fresh Game Design tenant source is absent"));
    if (!tenantUuid.equals(tenantSource.canonicalTenantId())
        || !"NEW_GAME_ROW".equals(tenantSource.provenanceKind())) {
      throw new IllegalStateException("Fresh Game Design tenant source is contradictory");
    }

    AccountTenantMembership membership =
        memberships
            .findFreshMembership(accountUuid, tenantUuid)
            .orElseThrow(
                () -> new IllegalStateException("Current positive Account membership is absent"));
    PairAuthority pair =
        pairAuthority
            .readPositive(accountUuid, tenantUuid)
            .orElseThrow(
                () -> new IllegalStateException("Positive Account membership authority is absent"));
    VerifiedTenantProvenance expectedProvenance =
        new VerifiedTenantProvenance(
            null,
            TenantProvenanceKind.FRESH_GAME_DESIGN,
            tenantSource.operationId(),
            tenantSource.evidenceDigest());
    requireMembershipMatchesSource(
        accountUuid, tenantUuid, account, membership, pair, expectedProvenance);

    MembershipEventSnapshot committedEvent = readCurrentCommittedEvent(accountUuid, tenantUuid);
    requireEventMatchesCurrentSources(accountUuid, tenantUuid, membership, pair, committedEvent);

    return new CurrentSnapshot(
        accountUuid,
        account.getAccountUuidProvenance(),
        account.getLifecycleState().name(),
        tenantUuid,
        expectedProvenance.kind().name(),
        expectedProvenance.sourceOperationId(),
        expectedProvenance.digest(),
        membership.getLifecycleState(),
        membership.isGameplayAdmissionAllowed(),
        membership.getAuthorityProvenance(),
        membership.getMembershipVersion(),
        membership.getMembershipAuthorityGeneration(),
        pair.eventSequence(),
        parseCanonicalUuid(pair.eventId(), "membership event ID"),
        pair.eventDigest(),
        pair.lastTransitionInvalidated());
  }

  private MembershipEventSnapshot readCurrentCommittedEvent(UUID accountUuid, UUID tenantUuid) {
    String streamKey = membershipStreamKey(accountUuid, tenantUuid);
    Record row =
        dsl.fetchOne(
            "SELECT stream.last_sequence AS checkpoint_sequence, "
                + "event.outbox_sequence AS event_sequence, event.request_id, event.event_id, "
                + "event.event_digest, event.payload, "
                + "(SELECT max(retained.outbox_sequence) "
                + "FROM account_authority_outbox_events retained "
                + "WHERE retained.outbox_stream_key = stream.outbox_stream_key) AS maximum_sequence "
                + "FROM account_authority_outbox_streams stream "
                + "JOIN account_authority_outbox_events event "
                + "ON event.outbox_stream_key = stream.outbox_stream_key "
                + "AND event.outbox_sequence = stream.last_sequence "
                + "WHERE stream.outbox_stream_key = ? AND stream.last_sequence > 0",
            streamKey);
    if (row == null) {
      throw new IllegalStateException("Current Account membership checkpoint or event is absent");
    }

    long checkpointSequence = requiredPositive(row, "checkpoint_sequence");
    long eventSequence = requiredPositive(row, "event_sequence");
    Long maximumSequence = row.get("maximum_sequence", Long.class);
    String requestId = required(row, "request_id", String.class);
    String eventId = required(row, "event_id", String.class);
    String eventDigest = required(row, "event_digest", String.class);
    byte[] payload = required(row, "payload", byte[].class);
    if (checkpointSequence != eventSequence
        || maximumSequence == null
        || maximumSequence.longValue() != checkpointSequence) {
      throw new IllegalStateException("Account membership checkpoint is not the stream head");
    }

    try {
      AccountAuthorityOutboxRepository.Event event =
          new AccountAuthorityOutboxRepository.Event(
              streamKey, requestId, eventSequence, eventId, eventDigest, payload);
      AccountAuthorityOutboxRepository.Checkpoint checkpoint =
          new AccountAuthorityOutboxRepository.Checkpoint(
              streamKey, checkpointSequence, eventId, eventDigest);
      return new MembershipEventSnapshot(event, checkpoint, verifyMembershipEvent(event));
    } catch (IllegalArgumentException invalidRecord) {
      throw new IllegalStateException(
          "Account membership event checkpoint is malformed", invalidRecord);
    }
  }

  private static MembershipEvent verifyMembershipEvent(
      AccountAuthorityOutboxRepository.Event event) {
    final MembershipEvent verified;
    try {
      verified =
          MembershipAuthorityEventV1Codec.verify(
              new String(event.payload(), StandardCharsets.UTF_8));
    } catch (RuntimeException invalid) {
      throw new IllegalStateException("Committed Account membership event is invalid", invalid);
    }
    if (!Arrays.equals(event.payload(), verified.canonicalJsonUtf8())
        || !event.eventId().equals(verified.eventId())
        || !event.eventDigest().equals(verified.eventDigest())
        || !event.requestId().equals(verified.requestId())
        || !event.outboxStreamKey().equals(verified.outboxStreamKey())
        || !Long.toString(event.outboxSequence()).equals(verified.outboxSequence())) {
      throw new IllegalStateException(
          "Committed Account membership event readback is contradictory");
    }
    return verified;
  }

  private static void requireEventMatchesCurrentSources(
      UUID accountUuid,
      UUID tenantUuid,
      AccountTenantMembership membership,
      PairAuthority pair,
      MembershipEventSnapshot committed) {
    AccountAuthorityOutboxRepository.Event event = committed.event();
    AccountAuthorityOutboxRepository.Checkpoint checkpoint = committed.checkpoint();
    MembershipEvent decoded = committed.decoded();
    String tenantKey = tenantUuid.toString();
    if (!event.outboxStreamKey().equals(membershipStreamKey(accountUuid, tenantUuid))
        || !checkpoint.outboxStreamKey().equals(event.outboxStreamKey())
        || checkpoint.outboxSequence() != pair.eventSequence()
        || !checkpoint.sourceEventId().equals(pair.eventId())
        || !checkpoint.sourceEventDigest().equals(pair.eventDigest())
        || event.outboxSequence() != pair.eventSequence()
        || !event.eventId().equals(pair.eventId())
        || !event.eventDigest().equals(pair.eventDigest())
        || !accountUuid.toString().equals(decoded.accountId())
        || !tenantKey.equals(decoded.tenantId())
        || !decoded.sourceScope().equals("membership/" + accountUuid + "/" + tenantUuid)
        || !membership.getLifecycleState().equals(decoded.membershipLifecycleState())
        || !Long.toString(membership.getMembershipVersion())
            .equals(decoded.membershipVersion().get(tenantKey))
        || !Long.toString(membership.getMembershipAuthorityGeneration())
            .equals(decoded.membershipAuthorityGeneration())
        || membership.isGameplayAdmissionAllowed() != decoded.gameplayAdmissionAllowed()
        || pair.lastTransitionInvalidated() != decoded.callerBoundAuthorityInvalidated()) {
      throw new IllegalStateException(
          "Committed Account membership event differs from the current membership and pair state");
    }
  }

  private static void requireMembershipMatchesSource(
      UUID accountUuid,
      UUID tenantUuid,
      Account account,
      AccountTenantMembership membership,
      PairAuthority pair,
      VerifiedTenantProvenance expectedProvenance) {
    if (membership.getAccount() == null
        || !Objects.equals(account.getId(), membership.getAccount().getId())
        || !accountUuid.equals(membership.getAccount().getAccountUuid())
        || membership.getTenantId() != null
        || !tenantUuid.equals(membership.getTenantUuid())
        || !expectedProvenance.equals(pair.provenance())
        || !expectedProvenance.kind().name().equals(membership.getTenantProvenanceKind())
        || !expectedProvenance.sourceOperationId().equals(membership.getTenantSourceOperationId())
        || !expectedProvenance.digest().equals(membership.getTenantProvenanceDigest())
        || !pair.membershipExists()
        || pair.eventSequence() <= 0L
        || pair.membershipVersion() != membership.getMembershipVersion()
        || pair.membershipAuthorityGeneration() != membership.getMembershipAuthorityGeneration()
        || membership.getMembershipVersion() <= 0L
        || membership.getMembershipAuthorityGeneration() <= 0L
        || !"EXPLICIT_JOIN".equals(membership.getAuthorityProvenance())
        || (!"ACTIVE".equals(membership.getLifecycleState())
            && !"INACTIVE".equals(membership.getLifecycleState()))) {
      throw new IllegalStateException(
          "Canonical Account membership or its current authority source is contradictory");
    }
  }

  private static String membershipStreamKey(UUID accountUuid, UUID tenantUuid) {
    return MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX
        + "membership/"
        + accountUuid
        + "/"
        + tenantUuid;
  }

  private static long requiredPositive(Record row, String field) {
    Long value = required(row, field, Long.class);
    if (value <= 0L) {
      throw new IllegalStateException("Account membership event source lacks positive " + field);
    }
    return value;
  }

  private static <T> T required(Record row, String field, Class<T> type) {
    T value = row.get(field, type);
    if (value == null) {
      throw new IllegalStateException("Account membership event source lacks " + field);
    }
    return value;
  }

  private static UUID parseCanonicalUuid(String value, String label) {
    if (value == null) {
      throw new IllegalStateException("Account membership source lacks " + label);
    }
    try {
      UUID parsed = UUID.fromString(value);
      if (!parsed.toString().equals(value) || new UUID(0L, 0L).equals(parsed)) {
        throw new IllegalArgumentException("not canonical");
      }
      return parsed;
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException("Account membership source has invalid " + label, invalid);
    }
  }

  private static void requireCanonicalUuid(UUID value, String label) {
    if (value == null || new UUID(0L, 0L).equals(value)) {
      throw new IllegalArgumentException(label + " must be a canonical nonnil UUID");
    }
  }

  private static void requireReadOnlyOwnerTransaction() {
    Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        || isolation == null
        || isolation != TransactionDefinition.ISOLATION_REPEATABLE_READ) {
      throw new IllegalStateException(
          "Actor-staging eligibility reads require a repeatable-read, read-only Account snapshot");
    }
  }

  private record MembershipEventSnapshot(
      AccountAuthorityOutboxRepository.Event event,
      AccountAuthorityOutboxRepository.Checkpoint checkpoint,
      MembershipEvent decoded) {}

  /** Exact source values sealed into the cross-service evidence response. */
  public record CurrentSnapshot(
      UUID accountUuid,
      AccountIdentityProvenance accountUuidProvenance,
      String accountLifecycleState,
      UUID tenantUuid,
      String tenantProvenanceKind,
      UUID tenantSourceOperationId,
      String tenantProvenanceDigest,
      String membershipLifecycleState,
      boolean gameplayAdmissionAllowed,
      String membershipAuthorityProvenance,
      long membershipVersion,
      long membershipAuthorityGeneration,
      long membershipEventSequence,
      UUID membershipEventId,
      String membershipEventDigest,
      boolean lastTransitionInvalidated) {}
}
