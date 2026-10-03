package net.firedevops.firemud.accountservice.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores the durable Account-owned authority state for one verified Account/tenant pair.
 *
 * <p>This repository does not establish that a membership row, authority event, receipt, or other
 * retained history is absent. Callers must establish those facts under the same Account fence
 * before enrolling or returning a never-joined sequence-zero snapshot.
 */
@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring transaction collaborator.")
public class AccountMembershipPairAuthorityRepository {
  private static final String TABLE = "account_membership_pair_authority";
  private static final String COLUMNS =
      "account_uuid, tenant_uuid, legacy_tenant_id, tenant_provenance_kind, "
          + "tenant_source_operation_id, tenant_provenance_digest, membership_exists, "
          + "membership_version, membership_authority_generation, last_event_sequence, "
          + "last_event_id, last_event_digest, last_transition_invalidated";
  private static final Pattern SHA256_DIGEST = Pattern.compile("^sha256:[0-9a-f]{64}$");

  private final DSLContext dsl;

  public AccountMembershipPairAuthorityRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  /**
   * Enrolls the positive never-joined baseline after the caller independently proves the pair
   * association and absence of membership and committed history under its Account fence.
   *
   * <p>Repeated enrollment succeeds only when the complete persisted row still exactly equals the
   * baseline and verified tenant provenance. A progressed row fails closed and is never reset.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public PairAuthority enrollAbsence(
      UUID accountUuid, UUID tenantUuid, VerifiedTenantProvenance provenance) {
    requireScope(accountUuid, tenantUuid);
    Objects.requireNonNull(provenance, "verified tenant provenance is required");

    PairAuthority baseline =
        new PairAuthority(
            accountUuid, tenantUuid, provenance, false, 1L, 1L, 0L, null, null, false);
    int inserted =
        dsl.execute(
            "INSERT INTO "
                + TABLE
                + " (account_uuid, tenant_uuid, legacy_tenant_id, tenant_provenance_kind, "
                + "tenant_source_operation_id, tenant_provenance_digest, membership_exists, "
                + "membership_version, membership_authority_generation, last_event_sequence, "
                + "last_event_id, last_event_digest, last_transition_invalidated) "
                + "VALUES (?, ?, ?, ?, ?, ?, FALSE, 1, 1, 0, NULL, NULL, FALSE) "
                + "ON CONFLICT DO NOTHING",
            accountUuid,
            tenantUuid,
            provenance.legacyTenantId(),
            provenance.kind().name(),
            provenance.sourceOperationId(),
            provenance.digest());
    if (inserted != 0 && inserted != 1) {
      throw new IllegalStateException("Account membership pair baseline insert was not singular");
    }

    Optional<PairAuthority> readback = readForUpdate(accountUuid, tenantUuid);
    if (readback.isEmpty()) {
      if (readByLegacyScopeForUpdate(accountUuid, provenance.legacyTenantId()).isPresent()) {
        throw new IllegalStateException(
            "Verified Account tenant association conflicts with an existing pair authority");
      }
      throw new IllegalStateException("Account membership pair baseline readback is absent");
    }

    PairAuthority current = readback.orElseThrow();
    requireSameImmutableIdentity(baseline, current);
    if (!baseline.equals(current)) {
      throw new IllegalStateException(
          "Account membership pair baseline readback differs from the exact absence state");
    }
    return current;
  }

  /**
   * Enrolls a retained existing membership from caller-verified positive current receipt/event
   * readback. This path is limited to approved retained associations and never manufactures a
   * sequence-zero baseline or changes supplied counters.
   *
   * <p>The caller must prove that the supplied version, generation, event checkpoint, and tenant
   * provenance describe the same current retained membership under its Account fence. An existing
   * row is accepted only when its complete state is exactly equal to that proof.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public PairAuthority enrollProvenPositive(
      UUID accountUuid,
      UUID tenantUuid,
      VerifiedTenantProvenance provenance,
      ProvenPositiveCheckpoint checkpoint) {
    requireScope(accountUuid, tenantUuid);
    Objects.requireNonNull(provenance, "verified tenant provenance is required");
    Objects.requireNonNull(checkpoint, "verified positive membership checkpoint is required");
    if (provenance.kind() != TenantProvenanceKind.APPROVED_RETAINED) {
      throw new IllegalArgumentException(
          "Positive retained membership requires approved retained tenant provenance");
    }

    PairAuthority expected =
        new PairAuthority(
            accountUuid,
            tenantUuid,
            provenance,
            true,
            checkpoint.membershipVersion(),
            checkpoint.membershipAuthorityGeneration(),
            checkpoint.eventSequence(),
            checkpoint.eventId(),
            checkpoint.eventDigest(),
            checkpoint.lastTransitionInvalidated());
    int inserted =
        dsl.execute(
            "INSERT INTO "
                + TABLE
                + " (account_uuid, tenant_uuid, legacy_tenant_id, tenant_provenance_kind, "
                + "tenant_source_operation_id, tenant_provenance_digest, membership_exists, "
                + "membership_version, membership_authority_generation, last_event_sequence, "
                + "last_event_id, last_event_digest, last_transition_invalidated) "
                + "VALUES (?, ?, ?, ?, ?, ?, TRUE, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT DO NOTHING",
            accountUuid,
            tenantUuid,
            provenance.legacyTenantId(),
            provenance.kind().name(),
            provenance.sourceOperationId(),
            provenance.digest(),
            checkpoint.membershipVersion(),
            checkpoint.membershipAuthorityGeneration(),
            checkpoint.eventSequence(),
            checkpoint.eventId(),
            checkpoint.eventDigest(),
            checkpoint.lastTransitionInvalidated());
    if (inserted != 0 && inserted != 1) {
      throw new IllegalStateException("Positive Account membership pair insert was not singular");
    }

    Optional<PairAuthority> readback = readForUpdate(accountUuid, tenantUuid);
    if (readback.isEmpty()) {
      if (readByLegacyScopeForUpdate(accountUuid, provenance.legacyTenantId()).isPresent()) {
        throw new IllegalStateException(
            "Verified Account tenant association conflicts with an existing pair authority");
      }
      throw new IllegalStateException("Positive Account membership pair readback is absent");
    }
    PairAuthority current = readback.orElseThrow();
    if (!expected.equals(current)) {
      throw new IllegalStateException(
          "Retained Account membership pair differs from verified positive current evidence");
    }
    return current;
  }

  /**
   * Locks and reads one exact pair row. An empty result is not proof that membership or committed
   * stream history is absent; the caller owns that cross-table proof.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<PairAuthority> readForUpdate(UUID accountUuid, UUID tenantUuid) {
    requireScope(accountUuid, tenantUuid);
    Record row =
        dsl.fetchOne(
            "SELECT "
                + COLUMNS
                + " FROM "
                + TABLE
                + " WHERE account_uuid = ? AND tenant_uuid = ? "
                + "FOR UPDATE",
            accountUuid,
            tenantUuid);
    return Optional.ofNullable(row).map(this::mapRow);
  }

  /**
   * Advances one exact pair state to its next committed authority event.
   *
   * <p>The supplied expected value is compared with a locked full-row readback, and the update
   * repeats the prior version, sequence, generation, membership state, event identity, and
   * immutable provenance as a compare-and-advance guard. The surrounding Account transaction must
   * commit the corresponding membership mutation and event atomically.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public PairAuthority commitTransition(PairAuthority expected, PairTransition transition) {
    Objects.requireNonNull(expected, "expected pair authority is required");
    Objects.requireNonNull(transition, "pair authority transition is required");
    long nextVersion = increment(expected.membershipVersion(), "membership version");
    long nextSequence = increment(expected.lastEventSequence(), "membership event sequence");
    long nextGeneration =
        transition.callerBoundAuthorityInvalidated()
            ? increment(expected.membershipAuthorityGeneration(), "membership authority generation")
            : expected.membershipAuthorityGeneration();
    if (transition.eventSequence() != nextSequence) {
      throw new IllegalArgumentException("Membership event sequence must advance by exactly one");
    }
    if (expected.lastEventSequence() == 0L && !transition.membershipExists()) {
      throw new IllegalArgumentException(
          "The first committed membership event must create membership");
    }

    PairAuthority current =
        readForUpdate(expected.accountUuid(), expected.tenantUuid())
            .orElseThrow(
                () -> new IllegalStateException("Account membership pair authority is missing"));
    if (!expected.equals(current)) {
      throw new IllegalStateException("Stale or contradictory Account membership pair authority");
    }

    int changed =
        dsl.execute(
            "UPDATE "
                + TABLE
                + " SET membership_exists = ?, membership_version = ?, "
                + "membership_authority_generation = ?, last_event_sequence = ?, "
                + "last_event_id = ?, last_event_digest = ?, last_transition_invalidated = ? "
                + "WHERE account_uuid = ? AND tenant_uuid = ? AND legacy_tenant_id = ? "
                + "AND tenant_provenance_kind = ? AND tenant_source_operation_id = ? "
                + "AND tenant_provenance_digest = ? AND membership_exists = ? "
                + "AND membership_version = ? AND membership_authority_generation = ? "
                + "AND last_event_sequence = ? AND last_event_id IS NOT DISTINCT FROM ? "
                + "AND last_event_digest IS NOT DISTINCT FROM ? "
                + "AND last_transition_invalidated = ?",
            transition.membershipExists(),
            nextVersion,
            nextGeneration,
            nextSequence,
            transition.eventId(),
            transition.eventDigest(),
            transition.callerBoundAuthorityInvalidated(),
            expected.accountUuid(),
            expected.tenantUuid(),
            expected.provenance().legacyTenantId(),
            expected.provenance().kind().name(),
            expected.provenance().sourceOperationId(),
            expected.provenance().digest(),
            expected.membershipExists(),
            expected.membershipVersion(),
            expected.membershipAuthorityGeneration(),
            expected.lastEventSequence(),
            expected.lastEventId(),
            expected.lastEventDigest(),
            expected.lastTransitionInvalidated());
    if (changed != 1) {
      throw new IllegalStateException(
          "Stale Account membership pair authority compare-and-advance");
    }

    PairAuthority advanced =
        readForUpdate(expected.accountUuid(), expected.tenantUuid())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Advanced Account membership pair readback is absent"));
    PairAuthority required =
        new PairAuthority(
            expected.accountUuid(),
            expected.tenantUuid(),
            expected.provenance(),
            transition.membershipExists(),
            nextVersion,
            nextGeneration,
            nextSequence,
            transition.eventId(),
            transition.eventDigest(),
            transition.callerBoundAuthorityInvalidated());
    if (!required.equals(advanced)) {
      throw new IllegalStateException("Advanced Account membership pair readback differs");
    }
    return advanced;
  }

  private Optional<PairAuthority> readByLegacyScopeForUpdate(
      UUID accountUuid, long legacyTenantId) {
    Record row =
        dsl.fetchOne(
            "SELECT "
                + COLUMNS
                + " FROM "
                + TABLE
                + " WHERE account_uuid = ? AND legacy_tenant_id = ? "
                + "FOR UPDATE",
            accountUuid,
            legacyTenantId);
    return Optional.ofNullable(row).map(this::mapRow);
  }

  private PairAuthority mapRow(Record row) {
    try {
      return new PairAuthority(
          row.get("account_uuid", UUID.class),
          row.get("tenant_uuid", UUID.class),
          new VerifiedTenantProvenance(
              row.get("legacy_tenant_id", Long.class),
              TenantProvenanceKind.valueOf(row.get("tenant_provenance_kind", String.class)),
              row.get("tenant_source_operation_id", UUID.class),
              row.get("tenant_provenance_digest", String.class)),
          row.get("membership_exists", Boolean.class),
          row.get("membership_version", Long.class),
          row.get("membership_authority_generation", Long.class),
          row.get("last_event_sequence", Long.class),
          row.get("last_event_id", String.class),
          row.get("last_event_digest", String.class),
          row.get("last_transition_invalidated", Boolean.class));
    } catch (RuntimeException corrupt) {
      throw new IllegalStateException(
          "Persisted Account membership pair authority is contradictory", corrupt);
    }
  }

  private void requireSameImmutableIdentity(PairAuthority expected, PairAuthority actual) {
    if (!expected.accountUuid().equals(actual.accountUuid())
        || !expected.tenantUuid().equals(actual.tenantUuid())
        || !expected.provenance().equals(actual.provenance())) {
      throw new IllegalStateException(
          "Account membership pair authority conflicts with verified association provenance");
    }
  }

  private void requireScope(UUID accountUuid, UUID tenantUuid) {
    if (accountUuid == null || tenantUuid == null) {
      throw new IllegalArgumentException("Canonical Account and tenant UUIDs are required");
    }
  }

  private long increment(long value, String label) {
    try {
      return Math.addExact(value, 1L);
    } catch (ArithmeticException overflow) {
      throw new IllegalStateException("Account membership " + label + " is exhausted", overflow);
    }
  }

  private static void requireDigest(String digest, String label) {
    if (digest == null || !SHA256_DIGEST.matcher(digest).matches()) {
      throw new IllegalArgumentException(label + " must be a canonical lowercase SHA-256 digest");
    }
  }

  public enum TenantProvenanceKind {
    APPROVED_RETAINED,
    FRESH_GAME_DESIGN
  }

  /** Immutable verified tenant-association facts supplied by the caller. */
  public record VerifiedTenantProvenance(
      long legacyTenantId, TenantProvenanceKind kind, UUID sourceOperationId, String digest) {
    public VerifiedTenantProvenance {
      if (legacyTenantId <= 0L || kind == null || sourceOperationId == null) {
        throw new IllegalArgumentException("Verified Account tenant provenance is incomplete");
      }
      requireDigest(digest, "Tenant provenance digest");
    }
  }

  /** Exact durable state for one canonical Account/tenant pair. */
  public record PairAuthority(
      UUID accountUuid,
      UUID tenantUuid,
      VerifiedTenantProvenance provenance,
      boolean membershipExists,
      long membershipVersion,
      long membershipAuthorityGeneration,
      long lastEventSequence,
      String lastEventId,
      String lastEventDigest,
      boolean lastTransitionInvalidated) {
    public PairAuthority {
      if (accountUuid == null
          || tenantUuid == null
          || provenance == null
          || membershipVersion <= 0L
          || membershipAuthorityGeneration <= 0L
          || lastEventSequence < 0L) {
        throw new IllegalArgumentException("Account membership pair authority state is incomplete");
      }
      if (lastEventSequence == 0L) {
        if (membershipExists
            || lastEventId != null
            || lastEventDigest != null
            || lastTransitionInvalidated) {
          throw new IllegalArgumentException(
              "Sequence-zero pair authority cannot claim membership or event evidence");
        }
      } else if (lastEventId == null
          || lastEventId.isBlank()
          || lastEventId.length() > 512
          || lastEventDigest == null) {
        throw new IllegalArgumentException(
            "Positive pair authority sequence requires event evidence");
      } else {
        requireDigest(lastEventDigest, "Membership event digest");
      }
    }
  }

  /** Caller-selected immutable event evidence for one transition. */
  public record PairTransition(
      boolean membershipExists,
      long eventSequence,
      String eventId,
      String eventDigest,
      boolean callerBoundAuthorityInvalidated) {
    public PairTransition {
      if (eventSequence <= 0L || eventId == null || eventId.isBlank() || eventId.length() > 512) {
        throw new IllegalArgumentException("Membership transition event identity is incomplete");
      }
      requireDigest(eventDigest, "Membership transition event digest");
    }
  }

  /**
   * Exact positive current membership state supplied by verified retained receipt/event readback.
   */
  public record ProvenPositiveCheckpoint(
      long membershipVersion,
      long membershipAuthorityGeneration,
      long eventSequence,
      String eventId,
      String eventDigest,
      boolean lastTransitionInvalidated) {
    public ProvenPositiveCheckpoint {
      if (membershipVersion <= 0L
          || membershipAuthorityGeneration <= 0L
          || eventSequence <= 0L
          || eventId == null
          || eventId.isBlank()
          || eventId.length() > 512) {
        throw new IllegalArgumentException("Verified positive membership checkpoint is incomplete");
      }
      requireDigest(eventDigest, "Verified membership checkpoint digest");
    }
  }
}
