package net.firedevops.firemud.accountservice.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores Account-owned authority generations and their transaction-local issuance fence inputs.
 *
 * <p>This is a persistence primitive only. Callers must compose applicable generation rows, this
 * account-local fence, authority mutations, and any canonical outbox evidence in their own
 * transaction. The account-local scalar is not by itself the composite fence for issuer or tenant
 * changes; {@link #readCompositeSnapshot(String, UUID, Collection, Collection)} locks and returns
 * the exact scope rows selected by the caller. This repository does not create projections, issue
 * tokens, or enable runtime reads.
 */
@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class AccountAuthorityGenerationRepository {
  private static final String GENERATION_TABLE = "account_authority_generations";

  private final DSLContext dsl;

  public AccountAuthorityGenerationRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  /** Explicitly initializes one exact authority scope. Reads never initialize missing state. */
  @Transactional(propagation = Propagation.MANDATORY)
  public ScopeState initialize(AuthorityScope scope) {
    validateScope(scope);
    if (scope.kind() == ScopeKind.MEMBERSHIP) {
      requireAccountState(scope.accountId(), true);
      readScopeState(AuthorityScope.tenant(scope.tenantId()), true);
      // Establishing a never-joined pair does not invalidate existing callers. Only an
      // explicit authority advance may change the account-local issuance fence.
    }
    insertGeneration(scope);
    if (scope.kind() == ScopeKind.ACCOUNT) {
      dsl.execute(
          "INSERT INTO account_authority_issuance_fences "
              + "(account_uuid, issuance_fence, source_version) VALUES (?, 1, 1)",
          scope.accountId());
    }
    return readScopeState(scope, true);
  }

  /**
   * Enrolls the exact Account JWT issuer scope once, preserving any existing durable values.
   *
   * <p>The partial unique index arbitrates concurrent Account startups. The subsequent locked
   * readback returns the exact persisted issuer row and rejects missing or malformed state.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public ScopeState initializeIssuerIfAbsent(String exactIssuerId) {
    AuthorityScope scope = AuthorityScope.issuer(exactIssuerId);
    int inserted =
        dsl.execute(
            "INSERT INTO "
                + GENERATION_TABLE
                + " (scope_kind, issuer_id, account_uuid, tenant_uuid, generation, source_version) "
                + "VALUES ('ISSUER', ?, NULL, NULL, 1, 1) "
                + "ON CONFLICT (issuer_id) WHERE scope_kind = 'ISSUER' DO NOTHING",
            exactIssuerId);
    if (inserted < 0 || inserted > 1) {
      throw new IllegalStateException("Account issuer authority enrollment was ambiguous");
    }
    return readScopeState(scope, true);
  }

  /**
   * Enrolls one exact canonical tenant scope once, preserving any existing durable generation.
   *
   * <p>This is used only after Account has stored and exactly read back its approved tenant
   * identity association in the caller's transaction. Numeric retained tenant keys are not accepted
   * by this method.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public ScopeState initializeTenantIfAbsent(UUID canonicalTenantId) {
    AuthorityScope scope = AuthorityScope.tenant(canonicalTenantId);
    validateScope(scope);
    int inserted =
        dsl.execute(
            "INSERT INTO "
                + GENERATION_TABLE
                + " (scope_kind, issuer_id, account_uuid, tenant_uuid, generation, source_version) "
                + "VALUES ('TENANT', NULL, NULL, ?, 1, 1) "
                + "ON CONFLICT (tenant_uuid) WHERE scope_kind = 'TENANT' DO NOTHING",
            canonicalTenantId);
    if (inserted < 0 || inserted > 1) {
      throw new IllegalStateException("Account tenant authority enrollment was ambiguous");
    }
    return readScopeState(scope, true);
  }

  /** Reads one initialized exact scope and fails closed for absent or malformed state. */
  @Transactional(propagation = Propagation.MANDATORY)
  public ScopeState read(AuthorityScope scope) {
    validateScope(scope);
    return readScopeState(scope, true);
  }

  /**
   * Locks one exact generation scope and compare-and-advances it by one.
   *
   * <p>Account and membership advances also require and advance the account-local issuance fence.
   * Issuer and tenant advances are fenced by their exact generation row; a composite token issuance
   * must lock and compare every applicable scope row in the same caller transaction.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public ScopeState advance(ScopeState expectedState, IssuanceFence expectedIssuanceFence) {
    if (expectedState == null) {
      throw new IllegalArgumentException("Expected Account authority state is required");
    }
    AuthorityScope scope = expectedState.scope();
    validateScope(scope);
    boolean accountFenced =
        scope.kind() == ScopeKind.ACCOUNT || scope.kind() == ScopeKind.MEMBERSHIP;
    if (accountFenced != (expectedIssuanceFence != null)) {
      throw new IllegalArgumentException(
          "Account and membership advances require the matching Account issuance fence");
    }
    if (!Objects.equals(expectedState.issuanceFence(), expectedIssuanceFence)) {
      throw new IllegalArgumentException(
          "Expected scope state and Account issuance fence must match exactly");
    }

    if (accountFenced) {
      requireAccountState(scope.accountId(), true);
      IssuanceFence currentFence = readIssuanceFence(scope.accountId(), true);
      requireExpectedFence(expectedIssuanceFence, currentFence);
    }

    Record changed =
        updateGeneration(scope, expectedState.generation(), expectedState.sourceVersion());
    if (changed == null) {
      throw new IllegalStateException("Stale or missing Account authority generation");
    }

    IssuanceFence nextFence =
        accountFenced ? advanceIssuanceFence(scope.accountId(), expectedIssuanceFence) : null;
    return state(scope, changed, nextFence);
  }

  /**
   * Locks one exact requested authority tuple in stable scope order at the caller's linearization
   * point. Tenant and membership scope lists are independent because their canonical tuple maps can
   * have different key sets.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public CompositeSnapshot readCompositeSnapshot(
      String issuerId,
      UUID accountId,
      Collection<UUID> tenantIds,
      Collection<UUID> membershipTenantIds) {
    AuthorityScope issuerScope = AuthorityScope.issuer(issuerId);
    AuthorityScope accountScope = AuthorityScope.account(accountId);
    List<UUID> tenants = sortedDistinct(tenantIds, "tenant authority scopes");
    List<UUID> memberships = sortedDistinct(membershipTenantIds, "membership authority scopes");

    ScopeState issuer = readScopeState(issuerScope, true);
    ScopeState account = readScopeState(accountScope, true);
    IssuanceFence fence = requireIssuanceFence(accountId, true);
    List<ScopeState> tenantStates = new ArrayList<>();
    for (UUID tenantId : tenants) {
      tenantStates.add(readScopeState(AuthorityScope.tenant(tenantId), true));
    }
    List<ScopeState> membershipStates = new ArrayList<>();
    for (UUID tenantId : memberships) {
      membershipStates.add(readScopeState(AuthorityScope.membership(accountId, tenantId), true));
    }
    return new CompositeSnapshot(issuer, account, tenantStates, membershipStates, fence);
  }

  private void insertGeneration(AuthorityScope scope) {
    int inserted =
        dsl.execute(
            "INSERT INTO "
                + GENERATION_TABLE
                + " (scope_kind, issuer_id, account_uuid, tenant_uuid, generation, source_version) "
                + "VALUES (?, ?, ?, ?, 1, 1)",
            scope.kind().name(),
            scope.issuerId(),
            scope.accountId(),
            scope.tenantId());
    if (inserted != 1) {
      throw new IllegalStateException("Account authority generation was not initialized");
    }
  }

  private ScopeState readScopeState(AuthorityScope scope, boolean lock) {
    if (scope.kind() == ScopeKind.MEMBERSHIP) {
      requireAccountState(scope.accountId(), lock);
      readScopeState(AuthorityScope.tenant(scope.tenantId()), lock);
    }
    String suffix = lock ? " FOR UPDATE" : "";
    Record row =
        dsl.fetchOne(
            "SELECT generation, source_version FROM "
                + GENERATION_TABLE
                + " WHERE scope_kind = ? AND issuer_id IS NOT DISTINCT FROM ? "
                + "AND account_uuid IS NOT DISTINCT FROM ? "
                + "AND tenant_uuid IS NOT DISTINCT FROM ?"
                + suffix,
            scope.kind().name(),
            scope.issuerId(),
            scope.accountId(),
            scope.tenantId());
    if (row == null) {
      throw new IllegalStateException("Account authority generation is missing");
    }
    long generation = requiredPositive(row.get("generation", Long.class), "generation");
    long sourceVersion = requiredPositive(row.get("source_version", Long.class), "source version");
    IssuanceFence fence =
        scope.kind() == ScopeKind.ACCOUNT || scope.kind() == ScopeKind.MEMBERSHIP
            ? requireIssuanceFence(scope.accountId(), lock)
            : null;
    return new ScopeState(scope, generation, sourceVersion, fence);
  }

  private Record updateGeneration(
      AuthorityScope scope, long expectedGeneration, long expectedVersion) {
    if (expectedGeneration <= 0L || expectedVersion <= 0L) {
      throw new IllegalArgumentException("Expected Account authority values must be positive");
    }
    return dsl.fetchOne(
        "UPDATE "
            + GENERATION_TABLE
            + " SET generation = generation + 1, source_version = source_version + 1, "
            + "updated_at = CURRENT_TIMESTAMP WHERE scope_kind = ? "
            + "AND issuer_id IS NOT DISTINCT FROM ? AND account_uuid IS NOT DISTINCT FROM ? "
            + "AND tenant_uuid IS NOT DISTINCT FROM ? AND generation = ? AND source_version = ? "
            + "RETURNING generation, source_version",
        scope.kind().name(),
        scope.issuerId(),
        scope.accountId(),
        scope.tenantId(),
        expectedGeneration,
        expectedVersion);
  }

  private IssuanceFence advanceIssuanceFence(UUID accountId, IssuanceFence expected) {
    Record row;
    if (expected == null) {
      row =
          dsl.fetchOne(
              "UPDATE account_authority_issuance_fences "
                  + "SET issuance_fence = issuance_fence + 1, "
                  + "source_version = source_version + 1, updated_at = CURRENT_TIMESTAMP "
                  + "WHERE account_uuid = ? RETURNING issuance_fence, source_version",
              accountId);
    } else {
      requireFenceOwner(expected, accountId);
      row =
          dsl.fetchOne(
              "UPDATE account_authority_issuance_fences "
                  + "SET issuance_fence = issuance_fence + 1, "
                  + "source_version = source_version + 1, updated_at = CURRENT_TIMESTAMP "
                  + "WHERE account_uuid = ? AND issuance_fence = ? AND source_version = ? "
                  + "RETURNING issuance_fence, source_version",
              accountId,
              expected.value(),
              expected.sourceVersion());
    }
    if (row == null) {
      throw new IllegalStateException("Stale or missing Account authority issuance fence");
    }
    return new IssuanceFence(
        accountId,
        requiredPositive(row.get("issuance_fence", Long.class), "issuance fence"),
        requiredPositive(row.get("source_version", Long.class), "issuance-fence source version"));
  }

  private IssuanceFence readIssuanceFence(UUID accountId, boolean lock) {
    String suffix = lock ? " FOR UPDATE" : "";
    Record row =
        dsl.fetchOne(
            "SELECT issuance_fence, source_version FROM account_authority_issuance_fences "
                + "WHERE account_uuid = ?"
                + suffix,
            accountId);
    if (row == null) {
      throw new IllegalStateException("Account authority issuance fence is missing");
    }
    return new IssuanceFence(
        accountId,
        requiredPositive(row.get("issuance_fence", Long.class), "issuance fence"),
        requiredPositive(row.get("source_version", Long.class), "issuance-fence source version"));
  }

  private IssuanceFence requireIssuanceFence(UUID accountId, boolean lock) {
    return readIssuanceFence(accountId, lock);
  }

  private void requireAccountState(UUID accountId, boolean lock) {
    readScopeState(AuthorityScope.account(accountId), lock);
  }

  private void requireExpectedFence(IssuanceFence expected, IssuanceFence current) {
    requireFenceOwner(expected, current.accountId());
    if (!expected.equals(current)) {
      throw new IllegalStateException("Stale Account authority issuance fence");
    }
  }

  private void requireFenceOwner(IssuanceFence fence, UUID accountId) {
    if (fence == null || !accountId.equals(fence.accountId()) || fence.value() <= 0L) {
      throw new IllegalArgumentException("Matching positive Account issuance fence is required");
    }
  }

  private ScopeState state(AuthorityScope scope, Record row, IssuanceFence fence) {
    return new ScopeState(
        scope,
        requiredPositive(row.get("generation", Long.class), "generation"),
        requiredPositive(row.get("source_version", Long.class), "source version"),
        fence);
  }

  private List<UUID> sortedDistinct(Collection<UUID> values, String label) {
    if (values == null) {
      throw new IllegalArgumentException(label + " are required");
    }
    List<UUID> result = new ArrayList<>();
    Set<UUID> seen = new HashSet<>();
    for (UUID value : values) {
      if (value == null || !seen.add(value)) {
        throw new IllegalArgumentException(label + " must contain unique canonical UUIDs");
      }
      result.add(value);
    }
    result.sort(Comparator.naturalOrder());
    return List.copyOf(result);
  }

  private void validateScope(AuthorityScope scope) {
    Objects.requireNonNull(scope, "authority scope is required");
    switch (scope.kind()) {
      case ISSUER -> requireNonBlank(scope.issuerId(), "issuer ID");
      case ACCOUNT ->
          Objects.requireNonNull(scope.accountId(), "canonical Account UUID is required");
      case TENANT -> Objects.requireNonNull(scope.tenantId(), "canonical tenant UUID is required");
      case MEMBERSHIP -> {
        Objects.requireNonNull(scope.accountId(), "canonical Account UUID is required");
        Objects.requireNonNull(scope.tenantId(), "canonical tenant UUID is required");
      }
    }
  }

  private void requireNonBlank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
  }

  private long requiredPositive(Long value, String field) {
    if (value == null || value <= 0L) {
      throw new IllegalStateException("Account authority " + field + " is missing or malformed");
    }
    return value;
  }

  public enum ScopeKind {
    ISSUER,
    ACCOUNT,
    TENANT,
    MEMBERSHIP
  }

  /** Exact owner scope. UUID fields are canonical identities; retained numeric IDs are excluded. */
  public record AuthorityScope(ScopeKind kind, String issuerId, UUID accountId, UUID tenantId) {
    public AuthorityScope {
      Objects.requireNonNull(kind, "authority scope kind is required");
      switch (kind) {
        case ISSUER -> {
          if (issuerId == null || issuerId.isBlank() || accountId != null || tenantId != null) {
            throw new IllegalArgumentException(
                "Issuer authority scope must contain only exact issuer ID");
          }
        }
        case ACCOUNT -> {
          if (issuerId != null || accountId == null || tenantId != null) {
            throw new IllegalArgumentException(
                "Account authority scope requires only Account UUID");
          }
        }
        case TENANT -> {
          if (issuerId != null || accountId != null || tenantId == null) {
            throw new IllegalArgumentException("Tenant authority scope requires only tenant UUID");
          }
        }
        case MEMBERSHIP -> {
          if (issuerId != null || accountId == null || tenantId == null) {
            throw new IllegalArgumentException("Membership authority scope requires both UUIDs");
          }
        }
      }
    }

    public static AuthorityScope issuer(String exactIssuerId) {
      return new AuthorityScope(ScopeKind.ISSUER, exactIssuerId, null, null);
    }

    public static AuthorityScope account(UUID accountId) {
      return new AuthorityScope(ScopeKind.ACCOUNT, null, accountId, null);
    }

    public static AuthorityScope tenant(UUID tenantId) {
      return new AuthorityScope(ScopeKind.TENANT, null, null, tenantId);
    }

    public static AuthorityScope membership(UUID accountId, UUID tenantId) {
      return new AuthorityScope(ScopeKind.MEMBERSHIP, null, accountId, tenantId);
    }
  }

  /** Positive generation and independently monotonic durable source version for one exact scope. */
  public record ScopeState(
      AuthorityScope scope, long generation, long sourceVersion, IssuanceFence issuanceFence) {
    public ScopeState {
      Objects.requireNonNull(scope, "authority scope is required");
      if (generation <= 0L || sourceVersion <= 0L) {
        throw new IllegalArgumentException(
            "Authority generation and source version must be positive");
      }
    }
  }

  /** Account-local issuance serialization value; it is one component of a composite snapshot. */
  public record IssuanceFence(UUID accountId, long value, long sourceVersion) {
    public IssuanceFence {
      Objects.requireNonNull(accountId, "canonical Account UUID is required");
      if (value <= 0L || sourceVersion <= 0L) {
        throw new IllegalArgumentException("Account issuance fence values must be positive");
      }
    }
  }

  /**
   * Exact issuer/account and caller-selected tenant/membership generation rows plus Account fence.
   */
  public record CompositeSnapshot(
      ScopeState issuer,
      ScopeState account,
      List<ScopeState> tenants,
      List<ScopeState> memberships,
      IssuanceFence issuanceFence) {
    public CompositeSnapshot {
      Objects.requireNonNull(issuer, "issuer authority state is required");
      Objects.requireNonNull(account, "Account authority state is required");
      tenants = List.copyOf(tenants);
      memberships = List.copyOf(memberships);
      Objects.requireNonNull(issuanceFence, "Account issuance fence is required");
    }
  }
}
