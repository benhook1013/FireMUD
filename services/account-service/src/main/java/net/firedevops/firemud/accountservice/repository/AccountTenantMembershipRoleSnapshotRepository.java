package net.firedevops.firemud.accountservice.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.entity.AccountTenantMembership;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.TenantProvenanceKind;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.VerifiedTenantProvenance;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Persists the current, Account-owned role snapshot for one tenant membership.
 *
 * <p>The snapshot header is deliberately separate from its normalized role rows. Its presence and
 * version therefore distinguish an authoritative empty role set from a retained membership row for
 * which role evidence was never recorded.
 */
@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class AccountTenantMembershipRoleSnapshotRepository {
  private static final int MAX_ROLE_IDENTIFIER_LENGTH = 128;
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final String UNBRIDGED_RETAINED = "UNBRIDGED_RETAINED";
  private static final String SNAPSHOT_TABLE = "account_tenant_membership_role_snapshots";
  private static final String ROLE_TABLE = "account_tenant_membership_role_snapshot_roles";
  private static final Comparator<String> ROLE_IDENTIFIER_BYTE_COMPARATOR =
      AccountTenantMembershipRoleSnapshotRepository::compareRoleIdentifierBytes;

  private final DSLContext dsl;

  public AccountTenantMembershipRoleSnapshotRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  /**
   * Reads the exact current snapshot while locking its membership/header evidence.
   *
   * <p>An absent header is returned as empty: callers must not infer roles from the global account
   * role or the membership admission flag. A present header with no role rows is a valid,
   * authoritative empty snapshot.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<RoleSnapshot> findForUpdate(
      long accountId, long tenantId, long membershipId, long expectedMembershipVersion) {
    validatePositive(accountId, "account ID");
    validatePositive(tenantId, "tenant ID");
    validatePositive(membershipId, "membership ID");
    validatePositive(expectedMembershipVersion, "membership version");

    MembershipIdentity identity =
        lockRetainedMembership(accountId, tenantId, membershipId, expectedMembershipVersion);
    return readLockedSnapshot(identity, expectedMembershipVersion);
  }

  /**
   * Reads one exact canonical role snapshot while locking its UUID-qualified membership row and
   * header. Returned provenance is the complete source tuple stored on that membership row.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<RoleSnapshot> findForCanonicalUpdate(
      UUID accountUuid,
      UUID tenantUuid,
      VerifiedTenantProvenance expectedProvenance,
      long membershipId,
      long expectedMembershipVersion) {
    requireCanonicalOwnerTransaction();
    requireCanonicalIdentity(accountUuid, tenantUuid, expectedProvenance);
    validatePositive(membershipId, "membership ID");
    validatePositive(expectedMembershipVersion, "membership version");
    MembershipIdentity identity =
        lockCanonicalMembership(
            accountUuid, tenantUuid, expectedProvenance, membershipId, expectedMembershipVersion);
    return readLockedSnapshot(identity, expectedMembershipVersion);
  }

  /** Replaces one exact current snapshot in the caller's JOIN transaction. */
  @Transactional(propagation = Propagation.MANDATORY)
  public RoleSnapshot replace(
      AccountTenantMembership membership,
      long snapshotVersion,
      Collection<String> roleIdentifiers) {
    if (membership == null
        || membership.getAccount() == null
        || membership.getAccount().getId() == null
        || membership.getId() == null
        || membership.getTenantId() == null) {
      throw new IllegalArgumentException("A persisted Account membership is required");
    }
    long accountId = membership.getAccount().getId();
    long tenantId = membership.getTenantId();
    long membershipId = membership.getId();
    validatePositive(accountId, "account ID");
    validatePositive(tenantId, "tenant ID");
    validatePositive(membershipId, "membership ID");
    validatePositive(snapshotVersion, "role snapshot version");
    List<String> canonicalRoles = canonicalizeRoleSet(roleIdentifiers);

    MembershipIdentity identity =
        lockRetainedMembership(accountId, tenantId, membershipId, snapshotVersion);

    dsl.execute("DELETE FROM " + ROLE_TABLE + " WHERE membership_id = ?", membershipId);
    int headerRows =
        dsl.execute(
            "INSERT INTO "
                + SNAPSHOT_TABLE
                + " (membership_id, snapshot_version) VALUES (?, ?) "
                + "ON CONFLICT (membership_id) DO UPDATE SET snapshot_version = EXCLUDED.snapshot_version",
            membershipId,
            snapshotVersion);
    if (headerRows != 1) {
      throw new IllegalStateException("Account role snapshot header was not persisted");
    }
    for (String role : canonicalRoles) {
      int roleRows =
          dsl.execute(
              "INSERT INTO "
                  + ROLE_TABLE
                  + " (membership_id, snapshot_version, role_identifier) VALUES (?, ?, ?)",
              membershipId,
              snapshotVersion,
              role);
      if (roleRows != 1) {
        throw new IllegalStateException("Account role snapshot row was not persisted");
      }
    }
    RoleSnapshot snapshot =
        new RoleSnapshot(
            identity.accountId(),
            identity.tenantId(),
            identity.membershipId(),
            snapshotVersion,
            canonicalRoles,
            identity.accountUuid(),
            identity.tenantUuid(),
            identity.tenantProvenance());
    requireSnapshotReadback(snapshot, readLockedSnapshot(identity, snapshotVersion));
    return snapshot;
  }

  /** Replaces and reads back one exact UUID-qualified membership role snapshot. */
  @Transactional(propagation = Propagation.MANDATORY)
  public RoleSnapshot replaceCanonical(
      AccountTenantMembership membership,
      UUID accountUuid,
      UUID tenantUuid,
      VerifiedTenantProvenance expectedProvenance,
      long snapshotVersion,
      Collection<String> roleIdentifiers) {
    requireCanonicalOwnerTransaction();
    if (membership == null
        || membership.getAccount() == null
        || membership.getAccount().getId() == null
        || membership.getId() == null) {
      throw new IllegalArgumentException("A persisted Account membership is required");
    }
    requireCanonicalIdentity(accountUuid, tenantUuid, expectedProvenance);
    validatePositive(membership.getAccount().getId(), "account ID");
    validatePositive(membership.getId(), "membership ID");
    validatePositive(snapshotVersion, "role snapshot version");
    if (membership.getMembershipVersion() != snapshotVersion
        || !Objects.equals(membership.getTenantUuid(), tenantUuid)
        || !provenanceFromMembership(membership).equals(expectedProvenance)) {
      throw new IllegalStateException(
          "Canonical role write differs from the exact membership identity or version");
    }
    MembershipIdentity identity =
        lockCanonicalMembership(
            accountUuid, tenantUuid, expectedProvenance, membership.getId(), snapshotVersion);
    if (identity.accountId() != membership.getAccount().getId()
        || !Objects.equals(identity.tenantId(), membership.getTenantId())) {
      throw new IllegalStateException(
          "Canonical role write membership differs from its persisted account row");
    }
    List<String> canonicalRoles = canonicalizeRoleSet(roleIdentifiers);

    dsl.execute("DELETE FROM " + ROLE_TABLE + " WHERE membership_id = ?", membership.getId());
    int headerRows =
        dsl.execute(
            "INSERT INTO "
                + SNAPSHOT_TABLE
                + " (membership_id, snapshot_version) VALUES (?, ?) "
                + "ON CONFLICT (membership_id) DO UPDATE SET snapshot_version = EXCLUDED.snapshot_version",
            membership.getId(),
            snapshotVersion);
    if (headerRows != 1) {
      throw new IllegalStateException("Account role snapshot header was not persisted");
    }
    for (String role : canonicalRoles) {
      int roleRows =
          dsl.execute(
              "INSERT INTO "
                  + ROLE_TABLE
                  + " (membership_id, snapshot_version, role_identifier) VALUES (?, ?, ?)",
              membership.getId(),
              snapshotVersion,
              role);
      if (roleRows != 1) {
        throw new IllegalStateException("Account role snapshot row was not persisted");
      }
    }

    RoleSnapshot expected =
        new RoleSnapshot(
            identity.accountId(),
            identity.tenantId(),
            identity.membershipId(),
            snapshotVersion,
            canonicalRoles,
            identity.accountUuid(),
            identity.tenantUuid(),
            identity.tenantProvenance());
    RoleSnapshot readback =
        findForCanonicalUpdate(
                accountUuid, tenantUuid, expectedProvenance, membership.getId(), snapshotVersion)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Canonical role snapshot header is absent after its write"));
    requireSnapshotReadback(expected, Optional.of(readback));
    return readback;
  }

  private Optional<RoleSnapshot> readLockedSnapshot(
      MembershipIdentity identity, long expectedMembershipVersion) {
    Record header =
        dsl.fetchOne(
            "SELECT membership_id, snapshot_version FROM "
                + SNAPSHOT_TABLE
                + " WHERE membership_id = ? FOR UPDATE",
            identity.membershipId());
    if (header == null) {
      return Optional.empty();
    }
    requireExactPositive(
        header.get("membership_id", Long.class), identity.membershipId(), "membership ID");
    requireExactPositive(
        header.get("snapshot_version", Long.class),
        expectedMembershipVersion,
        "role snapshot version");
    List<String> roles =
        dsl
            .fetch(
                "SELECT role_identifier FROM "
                    + ROLE_TABLE
                    + " WHERE membership_id = ? AND snapshot_version = ?"
                    + " ORDER BY convert_to(role_identifier, 'UTF8')",
                identity.membershipId(),
                expectedMembershipVersion)
            .stream()
            .map(row -> requiredRole(row.get("role_identifier", String.class)))
            .toList();
    return Optional.of(
        new RoleSnapshot(
            identity.accountId(),
            identity.tenantId(),
            identity.membershipId(),
            expectedMembershipVersion,
            roles,
            identity.accountUuid(),
            identity.tenantUuid(),
            identity.tenantProvenance()));
  }

  private MembershipIdentity lockRetainedMembership(
      long accountId, long tenantId, long membershipId, long expectedMembershipVersion) {
    Record row =
        dsl.fetchOne(
            membershipIdentitySelect()
                + "WHERE m.id = ? AND m.account_id = ? AND m.tenant_id = ? FOR UPDATE OF m",
            membershipId,
            accountId,
            tenantId);
    if (row == null) {
      throw new IllegalStateException("Account membership identity is missing or mismatched");
    }
    MembershipIdentity identity = toMembershipIdentity(row);
    requireExactPositive(identity.membershipId(), membershipId, "membership ID");
    requireExactPositive(identity.accountId(), accountId, "account ID");
    requireExactPositive(identity.tenantId(), tenantId, "tenant ID");
    requireExactPositive(
        identity.membershipVersion(), expectedMembershipVersion, "membership version");
    return identity;
  }

  private MembershipIdentity lockCanonicalMembership(
      UUID accountUuid,
      UUID tenantUuid,
      VerifiedTenantProvenance expectedProvenance,
      long membershipId,
      long expectedMembershipVersion) {
    Record row =
        dsl.fetchOne(
            membershipIdentitySelect()
                + "WHERE m.id = ? AND a.account_uuid = ? AND m.tenant_uuid = ? FOR UPDATE OF m",
            membershipId,
            accountUuid,
            tenantUuid);
    if (row == null) {
      throw new IllegalStateException(
          "Canonical Account membership identity is missing or mismatched");
    }
    MembershipIdentity identity = toMembershipIdentity(row);
    requireCanonicalIdentity(accountUuid, tenantUuid, expectedProvenance);
    requireExactPositive(identity.membershipId(), membershipId, "membership ID");
    if (!accountUuid.equals(identity.accountUuid())
        || !tenantUuid.equals(identity.tenantUuid())
        || !expectedProvenance.equals(identity.tenantProvenance())
        || !Objects.equals(identity.tenantId(), expectedProvenance.legacyTenantId())) {
      throw new IllegalStateException(
          "Canonical Account membership differs from exact UUID identity or stored provenance");
    }
    requireExactPositive(
        identity.membershipVersion(), expectedMembershipVersion, "membership version");
    return identity;
  }

  private String membershipIdentitySelect() {
    return "SELECT m.id, m.account_id, m.tenant_id, m.tenant_uuid, "
        + "m.tenant_provenance_kind, m.tenant_source_operation_id, "
        + "m.tenant_provenance_digest, m.membership_version, a.account_uuid, "
        + "a.account_uuid_provenance, a.account_uuid_source_numeric_id "
        + "FROM account_tenant_membership m JOIN accounts a ON a.id = m.account_id ";
  }

  private MembershipIdentity toMembershipIdentity(Record row) {
    Long accountId = row.get("account_id", Long.class);
    UUID accountUuid = row.get("account_uuid", UUID.class);
    String accountProvenance = row.get("account_uuid_provenance", String.class);
    Long accountSource = row.get("account_uuid_source_numeric_id", Long.class);
    if (accountId == null
        || accountId <= 0L
        || accountUuid == null
        || NIL_UUID.equals(accountUuid)
        || accountProvenance == null
        || accountSource == null
        || !accountId.equals(accountSource)) {
      throw new IllegalStateException("Canonical Account row identity is incomplete or mismatched");
    }
    AccountIdentityProvenance.fromStorageValue(accountProvenance);
    Long tenantId = row.get("tenant_id", Long.class);
    UUID tenantUuid = row.get("tenant_uuid", UUID.class);
    String tenantKind = row.get("tenant_provenance_kind", String.class);
    UUID sourceOperationId = row.get("tenant_source_operation_id", UUID.class);
    String sourceDigest = row.get("tenant_provenance_digest", String.class);
    VerifiedTenantProvenance provenance =
        tenantProvenance(tenantId, tenantUuid, tenantKind, sourceOperationId, sourceDigest);
    return new MembershipIdentity(
        row.get("id", Long.class),
        accountId,
        tenantId,
        tenantUuid,
        tenantKind,
        sourceOperationId,
        sourceDigest,
        row.get("membership_version", Long.class),
        accountUuid,
        provenance);
  }

  private VerifiedTenantProvenance tenantProvenance(
      Long tenantId,
      UUID tenantUuid,
      String tenantKind,
      UUID sourceOperationId,
      String sourceDigest) {
    if (UNBRIDGED_RETAINED.equals(tenantKind)) {
      if (tenantId == null
          || tenantUuid != null
          || sourceOperationId != null
          || sourceDigest != null) {
        throw new IllegalStateException("Unbridged Account membership identity is contradictory");
      }
      return null;
    }
    try {
      if (tenantUuid == null || NIL_UUID.equals(tenantUuid) || tenantKind == null) {
        throw new IllegalStateException("Canonical Account tenant UUID is absent or nil");
      }
      return new VerifiedTenantProvenance(
          tenantId, TenantProvenanceKind.valueOf(tenantKind), sourceOperationId, sourceDigest);
    } catch (IllegalArgumentException ex) {
      throw new IllegalStateException("Canonical Account tenant provenance is malformed", ex);
    }
  }

  private VerifiedTenantProvenance provenanceFromMembership(AccountTenantMembership membership) {
    return tenantProvenance(
        membership.getTenantId(),
        membership.getTenantUuid(),
        membership.getTenantProvenanceKind(),
        membership.getTenantSourceOperationId(),
        membership.getTenantProvenanceDigest());
  }

  private void requireCanonicalIdentity(
      UUID accountUuid, UUID tenantUuid, VerifiedTenantProvenance provenance) {
    requireNonNil(accountUuid, "Account UUID");
    requireNonNil(tenantUuid, "tenant UUID");
    if (provenance == null
        || (provenance.kind() != TenantProvenanceKind.APPROVED_RETAINED
            && provenance.kind() != TenantProvenanceKind.FRESH_GAME_DESIGN)) {
      throw new IllegalArgumentException(
          "Canonical role identity requires verified UUID provenance");
    }
  }

  private void requireSnapshotReadback(RoleSnapshot expected, Optional<RoleSnapshot> readback) {
    RoleSnapshot actual =
        readback.orElseThrow(
            () ->
                new IllegalStateException(
                    "Account role snapshot header is absent after its write"));
    if (!expected.equals(actual)) {
      throw new IllegalStateException(
          "Account role snapshot readback differs from its exact write");
    }
  }

  private void requireNonNil(UUID value, String name) {
    if (value == null || NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(name + " must be a non-nil canonical UUID");
    }
  }

  private void requireCanonicalOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Canonical Account role snapshot access requires an active owner transaction");
    }
  }

  /** Sorts and validates a role set exactly as the canonical unsigned-byte comparator requires. */
  public static List<String> canonicalizeRoleSet(Collection<String> roleIdentifiers) {
    if (roleIdentifiers == null) {
      throw new IllegalArgumentException("Role snapshot is required");
    }
    List<String> canonical = new ArrayList<>(roleIdentifiers.size());
    Set<String> seen = new HashSet<>();
    for (String role : roleIdentifiers) {
      validateRoleIdentifier(role);
      if (!seen.add(role)) {
        throw new IllegalArgumentException("Role snapshot contains a duplicate role");
      }
      canonical.add(role);
    }
    canonical.sort(ROLE_IDENTIFIER_BYTE_COMPARATOR);
    return List.copyOf(canonical);
  }

  /** Validates a persisted/readback array without repairing its order or duplicate evidence. */
  public static List<String> requireCanonicalRoleSet(Collection<String> roleIdentifiers) {
    if (roleIdentifiers == null) {
      throw new IllegalArgumentException("Role snapshot is required");
    }
    List<String> validated = new ArrayList<>(roleIdentifiers.size());
    String previous = null;
    for (String role : roleIdentifiers) {
      validateRoleIdentifier(role);
      if (previous != null && compareRoleIdentifierBytes(previous, role) >= 0) {
        throw new IllegalArgumentException("Role snapshot is not strictly bytewise sorted");
      }
      validated.add(role);
      previous = role;
    }
    return List.copyOf(validated);
  }

  /** Compares identifier bytes as unsigned values, with the shorter prefix first. */
  public static int compareRoleIdentifierBytes(String left, String right) {
    if (left == null || right == null) {
      throw new IllegalArgumentException("Role identifier is required");
    }
    validateRoleIdentifier(left);
    validateRoleIdentifier(right);
    byte[] leftBytes = left.getBytes(StandardCharsets.US_ASCII);
    byte[] rightBytes = right.getBytes(StandardCharsets.US_ASCII);
    int shared = Math.min(leftBytes.length, rightBytes.length);
    for (int index = 0; index < shared; index++) {
      int comparison =
          Integer.compare(
              Byte.toUnsignedInt(leftBytes[index]), Byte.toUnsignedInt(rightBytes[index]));
      if (comparison != 0) {
        return comparison;
      }
    }
    return Integer.compare(leftBytes.length, rightBytes.length);
  }

  private static void validateRoleIdentifier(String role) {
    if (role == null || role.isEmpty() || role.length() > MAX_ROLE_IDENTIFIER_LENGTH) {
      throw new IllegalArgumentException("Role identifier must match the canonical ASCII grammar");
    }
    char first = role.charAt(0);
    if (!isAsciiLetter(first)) {
      throw new IllegalArgumentException("Role identifier must match the canonical ASCII grammar");
    }
    for (int index = 1; index < role.length(); index++) {
      char character = role.charAt(index);
      if (!isAsciiLetter(character)
          && !isAsciiDigit(character)
          && character != '_'
          && character != '-') {
        throw new IllegalArgumentException(
            "Role identifier must match the canonical ASCII grammar");
      }
    }
  }

  private static boolean isAsciiLetter(char character) {
    return (character >= 'A' && character <= 'Z') || (character >= 'a' && character <= 'z');
  }

  private static boolean isAsciiDigit(char character) {
    return character >= '0' && character <= '9';
  }

  private static String requiredRole(String role) {
    validateRoleIdentifier(role);
    return role;
  }

  private static void validatePositive(long value, String name) {
    if (value <= 0L) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }

  private static void requireExactPositive(Long actual, long expected, String name) {
    if (actual == null || actual <= 0L || actual.longValue() != expected) {
      throw new IllegalStateException(
          "Account role snapshot " + name + " is missing or mismatched");
    }
  }

  public record RoleSnapshot(
      long accountId,
      Long tenantId,
      long membershipId,
      long snapshotVersion,
      List<String> roles,
      UUID accountUuid,
      UUID tenantUuid,
      VerifiedTenantProvenance tenantProvenance) {
    public RoleSnapshot {
      if (accountId <= 0L
          || (tenantId != null && tenantId <= 0L)
          || membershipId <= 0L
          || snapshotVersion <= 0L) {
        throw new IllegalArgumentException("Role snapshot identity and version must be positive");
      }
      if (tenantUuid == null) {
        if (tenantId == null || tenantProvenance != null) {
          throw new IllegalArgumentException(
              "Legacy role snapshot must retain its numeric tenant selector only");
        }
      } else if (NIL_UUID.equals(tenantUuid)
          || accountUuid == null
          || NIL_UUID.equals(accountUuid)
          || tenantProvenance == null
          || !Objects.equals(tenantId, tenantProvenance.legacyTenantId())) {
        throw new IllegalArgumentException(
            "Canonical role snapshot must retain exact UUID identity and source provenance");
      }
      roles = requireCanonicalRoleSet(roles);
    }

    public RoleSnapshot(
        long accountId,
        long tenantId,
        long membershipId,
        long snapshotVersion,
        List<String> roles) {
      this(accountId, tenantId, membershipId, snapshotVersion, roles, null, null, null);
    }

    @Override
    public List<String> roles() {
      return List.copyOf(roles);
    }
  }

  private record MembershipIdentity(
      long membershipId,
      long accountId,
      Long tenantId,
      UUID tenantUuid,
      String tenantProvenanceKind,
      UUID tenantSourceOperationId,
      String tenantProvenanceDigest,
      long membershipVersion,
      UUID accountUuid,
      VerifiedTenantProvenance tenantProvenance) {}
}
