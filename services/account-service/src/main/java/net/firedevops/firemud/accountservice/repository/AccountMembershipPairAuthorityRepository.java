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
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Reads positive Account-owned membership-pair currentness without enrolling or mutating a pair.
 *
 * <p>An absent pair or a sequence-zero absence baseline is not caller-bound membership authority.
 * Positive row creation and transition remain reserved for an explicit Account-owned JOIN
 * composition that commits the corresponding membership and event sources atomically.
 */
@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Account transaction collaborator.")
public class AccountMembershipPairAuthorityRepository {
  private static final String TABLE = "account_membership_pair_authority";
  private static final String COLUMNS =
      "account_uuid, tenant_uuid, legacy_tenant_id, tenant_provenance_kind, "
          + "tenant_source_operation_id, tenant_provenance_digest, membership_exists, "
          + "membership_version, membership_authority_generation, last_event_sequence, "
          + "last_event_id, last_event_digest, last_transition_invalidated";
  private static final Pattern SHA256_DIGEST = Pattern.compile("^sha256:[0-9a-f]{64}$");
  private final DSLContext dsl;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Preserve the injected DSLContext precondition; Spring must proxy this non-final repository.")
  public AccountMembershipPairAuthorityRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl);
  }

  /** Reads one existing positive membership pair in the caller's repeatable Account snapshot. */
  @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
  public Optional<PairAuthority> readPositive(UUID accountUuid, UUID tenantUuid) {
    requireOwnerTransaction();
    requireScope(accountUuid, tenantUuid);
    Optional<PairAuthority> pair = readState(accountUuid, tenantUuid);
    if (pair.isEmpty()) {
      return Optional.empty();
    }
    PairAuthority current = pair.orElseThrow();
    if (!current.membershipExists() || current.eventSequence() <= 0L) {
      throw new IllegalStateException("Account membership pair has no positive authority event");
    }
    return pair;
  }

  /**
   * Reads pair state for consistency checks only; absence and sequence zero are never authority.
   */
  @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
  public Optional<PairAuthority> readState(UUID accountUuid, UUID tenantUuid) {
    requireOwnerTransaction();
    requireScope(accountUuid, tenantUuid);
    return readPair(accountUuid, tenantUuid, false);
  }

  /**
   * Reads the complete pair ledger state under the caller's Account write fence.
   *
   * <p>A sequence-zero row is initialization evidence only. It is never returned by {@link
   * #readPositive(UUID, UUID)} and cannot authorize a caller.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<PairAuthority> readForUpdate(UUID accountUuid, UUID tenantUuid) {
    requireOwnerWriteTransaction();
    requireScope(accountUuid, tenantUuid);
    return readPair(accountUuid, tenantUuid, true);
  }

  /**
   * Enrolls the fresh pair's exact sequence-zero baseline inside an explicit Account write.
   *
   * <p>This method is not called by reads and does not grant membership. The caller must already
   * hold the Account row lock and prove the exact fresh source association and absent canonical
   * membership. A retry succeeds only while the entire baseline remains unchanged.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  PairAuthority enrollAbsence(
      UUID accountUuid, UUID tenantUuid, VerifiedTenantProvenance provenance) {
    requireOwnerWriteTransaction();
    requireScope(accountUuid, tenantUuid);
    requireFreshProvenance(provenance);
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
                + "VALUES (?, ?, NULL, ?, ?, ?, FALSE, 1, 1, 0, NULL, NULL, FALSE) "
                + "ON CONFLICT DO NOTHING",
            accountUuid,
            tenantUuid,
            provenance.kind().name(),
            provenance.sourceOperationId(),
            provenance.digest());
    if (inserted < 0 || inserted > 1) {
      throw new IllegalStateException("Account membership pair baseline insert was ambiguous");
    }

    PairAuthority readback =
        readForUpdate(accountUuid, tenantUuid)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Account membership pair baseline readback is absent"));
    if (!baseline.equals(readback)) {
      throw new IllegalStateException(
          "Account membership pair baseline readback differs from the exact absence state");
    }
    return readback;
  }

  /**
   * Commits the first positive JOIN transition against the exact sequence-zero fresh pair state.
   *
   * <p>The Account transaction must also commit the canonical membership row and the operation's
   * durable event/outbox evidence. The event ID and digest recorded here are the exact linkage to
   * that evidence; callers may not advance a retained or previously transitioned pair through this
   * first-JOIN primitive.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public PairAuthority commitTransition(PairAuthority expected, PairTransition transition) {
    requireOwnerWriteTransaction();
    Objects.requireNonNull(expected, "expected pair authority is required");
    Objects.requireNonNull(transition, "pair authority transition is required");
    requireFreshProvenance(expected.provenance());
    if (expected.membershipExists()
        || expected.eventSequence() != 0L
        || transition.eventSequence() != 1L
        || !transition.membershipExists()) {
      throw new IllegalArgumentException(
          "Only a fresh sequence-zero pair can commit its first positive JOIN transition");
    }

    long nextVersion = increment(expected.membershipVersion(), "membership version");
    long nextGeneration =
        transition.callerBoundAuthorityInvalidated()
            ? increment(expected.membershipAuthorityGeneration(), "membership authority generation")
            : expected.membershipAuthorityGeneration();
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
                + " SET membership_exists = TRUE, membership_version = ?, "
                + "membership_authority_generation = ?, last_event_sequence = 1, "
                + "last_event_id = ?, last_event_digest = ?, last_transition_invalidated = ? "
                + "WHERE account_uuid = ? AND tenant_uuid = ? "
                + "AND legacy_tenant_id IS NULL AND tenant_provenance_kind = ? "
                + "AND tenant_source_operation_id = ? AND tenant_provenance_digest = ? "
                + "AND membership_exists = FALSE AND membership_version = ? "
                + "AND membership_authority_generation = ? AND last_event_sequence = 0 "
                + "AND last_event_id IS NULL AND last_event_digest IS NULL "
                + "AND last_transition_invalidated = FALSE",
            nextVersion,
            nextGeneration,
            transition.eventId(),
            transition.eventDigest(),
            transition.callerBoundAuthorityInvalidated(),
            expected.accountUuid(),
            expected.tenantUuid(),
            expected.provenance().kind().name(),
            expected.provenance().sourceOperationId(),
            expected.provenance().digest(),
            expected.membershipVersion(),
            expected.membershipAuthorityGeneration());
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
            true,
            nextVersion,
            nextGeneration,
            1L,
            transition.eventId(),
            transition.eventDigest(),
            transition.callerBoundAuthorityInvalidated());
    if (!required.equals(advanced)) {
      throw new IllegalStateException("Advanced Account membership pair readback differs");
    }
    return advanced;
  }

  private static void requireScope(UUID accountUuid, UUID tenantUuid) {
    if (accountUuid == null || tenantUuid == null || isNil(accountUuid) || isNil(tenantUuid)) {
      throw new IllegalArgumentException("Canonical non-nil Account and tenant UUIDs are required");
    }
  }

  private Optional<PairAuthority> readPair(UUID accountUuid, UUID tenantUuid, boolean lock) {
    String suffix = lock ? " FOR UPDATE" : "";
    Record row =
        dsl.fetchOne(
            "SELECT "
                + COLUMNS
                + " FROM "
                + TABLE
                + " WHERE account_uuid = ? AND tenant_uuid = ?"
                + suffix,
            accountUuid,
            tenantUuid);
    if (row == null) {
      return Optional.empty();
    }
    try {
      PairAuthority pair = mapRow(row);
      if (!accountUuid.equals(pair.accountUuid()) || !tenantUuid.equals(pair.tenantUuid())) {
        throw new IllegalStateException("Account membership pair readback changed its scope");
      }
      return Optional.of(pair);
    } catch (RuntimeException corrupt) {
      throw new IllegalStateException(
          "Persisted Account membership pair authority is contradictory", corrupt);
    }
  }

  private PairAuthority mapRow(Record row) {
    try {
      return new PairAuthority(
          required(row.get("account_uuid", UUID.class), "Account UUID"),
          required(row.get("tenant_uuid", UUID.class), "tenant UUID"),
          new VerifiedTenantProvenance(
              row.get("legacy_tenant_id", Long.class),
              TenantProvenanceKind.valueOf(
                  required(row.get("tenant_provenance_kind", String.class), "tenant provenance")),
              required(
                  row.get("tenant_source_operation_id", UUID.class), "tenant source operation"),
              required(row.get("tenant_provenance_digest", String.class), "tenant source digest")),
          required(row.get("membership_exists", Boolean.class), "membership state"),
          required(row.get("membership_version", Long.class), "membership version"),
          required(
              row.get("membership_authority_generation", Long.class),
              "membership authority generation"),
          required(row.get("last_event_sequence", Long.class), "membership event sequence"),
          row.get("last_event_id", String.class),
          row.get("last_event_digest", String.class),
          required(
              row.get("last_transition_invalidated", Boolean.class), "event invalidation state"));
    } catch (RuntimeException corrupt) {
      throw new IllegalStateException(
          "Persisted Account membership pair authority is contradictory", corrupt);
    }
  }

  private static <T> T required(T value, String label) {
    if (value == null) {
      throw new IllegalStateException("Persisted Account membership pair lacks " + label);
    }
    return value;
  }

  private static void requireFreshProvenance(VerifiedTenantProvenance provenance) {
    Objects.requireNonNull(provenance, "verified tenant provenance is required");
    if (provenance.kind() != TenantProvenanceKind.FRESH_GAME_DESIGN
        || provenance.legacyTenantId() != null) {
      throw new IllegalArgumentException(
          "First JOIN pair writes require fresh Game Design tenant provenance");
    }
  }

  private static boolean isNil(UUID value) {
    return new UUID(0L, 0L).equals(value);
  }

  private static void requireOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Account membership pair read requires an active owner transaction");
    }
  }

  private static void requireOwnerWriteTransaction() {
    requireOwnerTransaction();
    if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Account membership pair mutation requires a writable owner transaction");
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
      throw new IllegalArgumentException(label + " must be a lowercase SHA-256 digest");
    }
  }

  public enum TenantProvenanceKind {
    APPROVED_RETAINED,
    FRESH_GAME_DESIGN
  }

  /** Immutable Account owner-verified source tuple; fresh tenants never have a numeric bridge. */
  public record VerifiedTenantProvenance(
      Long legacyTenantId, TenantProvenanceKind kind, UUID sourceOperationId, String digest) {
    public VerifiedTenantProvenance {
      if (kind == null || sourceOperationId == null || isNil(sourceOperationId)) {
        throw new IllegalArgumentException("Verified Account tenant provenance is incomplete");
      }
      if (kind == TenantProvenanceKind.APPROVED_RETAINED
          && (legacyTenantId == null || legacyTenantId <= 0L)) {
        throw new IllegalArgumentException(
            "Approved retained provenance requires a positive legacy tenant ID");
      }
      if (kind == TenantProvenanceKind.FRESH_GAME_DESIGN && legacyTenantId != null) {
        throw new IllegalArgumentException(
            "Fresh Game Design provenance must not carry a legacy tenant ID");
      }
      requireDigest(digest, "Tenant provenance digest");
    }
  }

  /**
   * Full pair ledger state. A sequence-zero absence row is evidence, never membership authority.
   */
  public record PairAuthority(
      UUID accountUuid,
      UUID tenantUuid,
      VerifiedTenantProvenance provenance,
      boolean membershipExists,
      long membershipVersion,
      long membershipAuthorityGeneration,
      long eventSequence,
      String eventId,
      String eventDigest,
      boolean lastTransitionInvalidated) {
    public PairAuthority {
      requireScope(accountUuid, tenantUuid);
      Objects.requireNonNull(provenance, "verified tenant provenance is required");
      if (membershipVersion <= 0L || membershipAuthorityGeneration <= 0L || eventSequence < 0L) {
        throw new IllegalArgumentException("Account membership pair state is incomplete");
      }
      if (eventSequence == 0L) {
        if (membershipExists
            || eventId != null
            || eventDigest != null
            || lastTransitionInvalidated) {
          throw new IllegalArgumentException(
              "Sequence-zero pair state cannot claim membership or event evidence");
        }
      } else {
        if (eventId == null || eventId.isBlank() || eventId.length() > 512) {
          throw new IllegalArgumentException("Positive Account pair requires event identity");
        }
        requireDigest(eventDigest, "Membership event digest");
      }
    }
  }

  /** Exact event linkage supplied by the explicit Account JOIN composition. */
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
}
