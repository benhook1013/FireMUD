package net.firedevops.firemud.gamesession.repository;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.gamesession.dto.CanonicalRealmCatalogSnapshot;
import org.jooq.DSLContext;

/**
 * Pre-v1 gate waiver (ADR 0185): provisions the per-tenant capacity-evidence and region
 * transfer-history budgets for the run-owned fixture tenant. Operational budget provisioning is
 * owed before v1. Rows are inserted once and never changed; a retry is a no-op.
 */
public final class PreV1CanonicalPlayBudgets {
  public static final int CAPACITY_EVIDENCE_RECORDS = 128;
  public static final long CAPACITY_EVIDENCE_BYTES_PER_RECORD = 1_048_576L;
  public static final int REGION_TRANSFERS = 256;
  public static final int REGION_TRANSFER_ITEMS = 1024;

  private final DSLContext dsl;
  private final GameSessionCanonicalRealmCatalogRepository catalog;
  private final CanonicalGameplayTenantGameplayTagRepository tags;
  private final String workloadNamespace;

  PreV1CanonicalPlayBudgets(
      DSLContext dsl,
      GameSessionCanonicalRealmCatalogRepository catalog,
      CanonicalGameplayTenantGameplayTagRepository tags,
      String workloadNamespace) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    this.tags = Objects.requireNonNull(tags, "tags");
    this.workloadNamespace = Objects.requireNonNull(workloadNamespace, "workloadNamespace");
  }

  /** The unique run-owned fixture tenant in this workload's catalog, once its realm exists. */
  public Optional<UUID> fixtureTenant() {
    List<UUID> tenants =
        catalog.readVisiblePublicProductionSnapshots(workloadNamespace).stream()
            .filter(snapshot -> snapshot.fixturePolicy() != null)
            .map(CanonicalRealmCatalogSnapshot::tenantId)
            .distinct()
            .toList();
    if (tenants.size() > 1) {
      throw new IllegalStateException("Run-owned fixture tenant is ambiguous");
    }
    return tenants.stream().findFirst();
  }

  /** Allocates the tenant's gameplay tag if absent, then inserts both budget rows if absent. */
  public void provision(UUID tenantId) {
    tags.tagForClaimedTenant(tenantId);
    dsl.execute(
        "INSERT INTO game_session_capacity_evidence_budget"
            + " (tenant_id, maximum_records, maximum_binding_bytes, maximum_retained_records)"
            + " VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
        tenantId,
        CAPACITY_EVIDENCE_RECORDS,
        CAPACITY_EVIDENCE_RECORDS * CAPACITY_EVIDENCE_BYTES_PER_RECORD,
        CAPACITY_EVIDENCE_RECORDS);
    dsl.execute(
        "INSERT INTO game_session_region_transfer_history_budget"
            + " (tenant_id, maximum_transfers, maximum_items) VALUES (?, ?, ?)"
            + " ON CONFLICT DO NOTHING",
        tenantId,
        REGION_TRANSFERS,
        REGION_TRANSFER_ITEMS);
  }
}
