package net.firedevops.firemud.accountservice.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.firedevops.firemud.accountservice.entity.AccountTenantMembership;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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

    Record membership =
        dsl.fetchOne(
            "SELECT id, account_id, tenant_id, membership_version "
                + "FROM account_tenant_membership "
                + "WHERE id = ? AND account_id = ? AND tenant_id = ? FOR UPDATE",
            membershipId,
            accountId,
            tenantId);
    if (membership == null) {
      throw new IllegalStateException("Account membership identity is missing or mismatched");
    }
    requireExactPositive(membership.get("id", Long.class), membershipId, "membership ID");
    requireExactPositive(membership.get("account_id", Long.class), accountId, "account ID");
    requireExactPositive(membership.get("tenant_id", Long.class), tenantId, "tenant ID");
    requireExactPositive(
        membership.get("membership_version", Long.class),
        expectedMembershipVersion,
        "membership version");

    Record header =
        dsl.fetchOne(
            "SELECT membership_id, snapshot_version FROM "
                + SNAPSHOT_TABLE
                + " WHERE membership_id = ? FOR UPDATE",
            membershipId);
    if (header == null) {
      return Optional.empty();
    }
    requireExactPositive(header.get("membership_id", Long.class), membershipId, "membership ID");
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
                membershipId,
                expectedMembershipVersion)
            .stream()
            .map(row -> requiredRole(row.get("role_identifier", String.class)))
            .toList();
    return Optional.of(
        new RoleSnapshot(accountId, tenantId, membershipId, expectedMembershipVersion, roles));
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

    Record current =
        dsl.fetchOne(
            "SELECT id, account_id, tenant_id, membership_version "
                + "FROM account_tenant_membership "
                + "WHERE id = ? AND account_id = ? AND tenant_id = ? FOR UPDATE",
            membershipId,
            accountId,
            tenantId);
    if (current == null) {
      throw new IllegalStateException("Account membership identity is missing or mismatched");
    }
    requireExactPositive(
        current.get("membership_version", Long.class), snapshotVersion, "membership version");

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
    return new RoleSnapshot(accountId, tenantId, membershipId, snapshotVersion, canonicalRoles);
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
      long accountId, long tenantId, long membershipId, long snapshotVersion, List<String> roles) {
    public RoleSnapshot {
      if (accountId <= 0L || tenantId <= 0L || membershipId <= 0L || snapshotVersion <= 0L) {
        throw new IllegalArgumentException("Role snapshot identity and version must be positive");
      }
      roles = requireCanonicalRoleSet(roles);
    }

    @Override
    public List<String> roles() {
      return List.copyOf(roles);
    }
  }
}
