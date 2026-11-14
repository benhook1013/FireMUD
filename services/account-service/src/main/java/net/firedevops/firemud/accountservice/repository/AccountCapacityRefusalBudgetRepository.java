package net.firedevops.firemud.accountservice.repository;

import static net.firedevops.firemud.accountservice.repository.AccountTenantCapacityRepository.conflict;
import static net.firedevops.firemud.accountservice.repository.AccountTenantCapacityRepository.transaction;
import static net.firedevops.firemud.accountservice.repository.AccountTenantCapacityRepository.unavailable;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission;
import org.jooq.DSLContext;

/** Explicit Account-local provisioning only. No bean, RPC, automatic seed or budget reset. */
@SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Private owner transaction collaborator.")
public final class AccountCapacityRefusalBudgetRepository {
  private final DSLContext dsl;

  public AccountCapacityRefusalBudgetRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl);
  }

  /**
   * Install an explicitly sized lifetime limit for a known canonical tenant. Exact retries preserve
   * the row; a different limit is a conflict, never an increase, reset or replacement. The caller
   * owns the writable transaction and must commit before enabling any admission producer.
   */
  public void provision(UUID tenantId, int maximumRefusals) {
    validate(tenantId, maximumRefusals);
    transaction();
    int inserted = dsl.execute(
        "INSERT INTO account_capacity_origin_refusal_budget (tenant_uuid,maximum_refusals) VALUES (?,?) ON CONFLICT (tenant_uuid) DO NOTHING",
        tenantId, maximumRefusals);
    if (inserted != 0 && inserted != 1) throw unavailable();
    var row = dsl.fetchOne(
        "SELECT maximum_refusals FROM account_capacity_origin_refusal_budget WHERE tenant_uuid=? FOR SHARE", tenantId);
    if (row == null) throw unavailable();
    if (!Integer.valueOf(maximumRefusals).equals(row.get(0, Integer.class))) throw conflict();
  }

  /**
   * Fail closed unless the exact installed policy has room for the stated bounded admission cohort.
   * This is a transaction-local readiness observation, not a reservation or admission authorization.
   * Callers must exclude admission writers while checking startup readiness. The existing SQL trigger
   * remains authoritative for every refusal and serializes competing runtime writers.
   */
  public long requireReady(UUID tenantId, int maximumRefusals, int requiredHeadroom) {
    validate(tenantId, maximumRefusals);
    if (requiredHeadroom < 1 || requiredHeadroom > maximumRefusals)
      throw new IllegalArgumentException("Positive headroom within the explicit limit required");
    transaction();
    var row = dsl.fetchOne(
        "SELECT maximum_refusals FROM account_capacity_origin_refusal_budget WHERE tenant_uuid=? FOR SHARE", tenantId);
    if (row == null) throw unavailable();
    if (!Integer.valueOf(maximumRefusals).equals(row.get(0, Integer.class))) throw conflict();
    var count = dsl.fetchOne(
        "SELECT count(*) FROM (SELECT 1 FROM account_capacity_origin_decisions WHERE tenant_uuid=? AND decision='NO_ACCEPT' LIMIT ?) retained",
        tenantId, (long) maximumRefusals + 1);
    Long retained = count == null ? null : count.get(0, Long.class);
    if (retained == null || retained < 0 || retained > maximumRefusals) throw unavailable();
    long remaining = maximumRefusals - retained;
    if (remaining < requiredHeadroom) throw unavailable();
    return remaining;
  }

  private static void validate(UUID tenantId, int maximumRefusals) {
    TenantCapacityAdmission.uuid(tenantId);
    if (maximumRefusals < 1) throw new IllegalArgumentException("Explicit finite refusal limit required");
  }
}
