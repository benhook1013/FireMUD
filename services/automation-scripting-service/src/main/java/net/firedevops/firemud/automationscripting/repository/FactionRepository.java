package net.firedevops.firemud.automationscripting.repository;

import static net.firedevops.firemud.automationscripting.jooq.tables.Factions.FACTIONS;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Optional;
import net.firedevops.firemud.automationscripting.entity.Faction;
import net.firedevops.firemud.automationscripting.jooq.tables.records.FactionsRecord;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class FactionRepository {
  private final DSLContext dsl;

  public FactionRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Optional<Faction> findById(Long id) {
    return dsl.selectFrom(FACTIONS).where(FACTIONS.ID.eq(id)).fetchOptional(this::toEntity);
  }

  public Optional<Faction> findByTenantIdAndId(Long tenantId, Long id) {
    return dsl.selectFrom(FACTIONS)
        .where(FACTIONS.TENANT_ID.eq(tenantId).and(FACTIONS.ID.eq(id)))
        .fetchOptional(this::toEntity);
  }

  public Faction save(Faction entity) {
    if (entity.getId() == null) {
      FactionsRecord record = dsl.newRecord(FACTIONS);
      populate(record, entity);
      record.store();
      return findById(record.getId()).orElseThrow();
    }
    int nextVersion = entity.getVersion() + 1;
    int updated =
        dsl.update(FACTIONS)
            .set(FACTIONS.TENANT_ID, entity.getTenantId())
            .set(FACTIONS.NAME, entity.getName())
            .set(FACTIONS.DESCRIPTION, entity.getDescription())
            .set(FACTIONS.ROW_VERSION, nextVersion)
            .where(FACTIONS.ID.eq(entity.getId()).and(FACTIONS.ROW_VERSION.eq(entity.getVersion())))
            .execute();
    if (updated != 1) {
      throw AutomationScriptingJooqRepositorySupport.staleWrite("factions", entity.getId());
    }
    entity.setVersion(nextVersion);
    return findById(entity.getId()).orElseThrow();
  }

  private void populate(FactionsRecord record, Faction entity) {
    record.setTenantId(entity.getTenantId());
    record.setName(entity.getName());
    record.setDescription(entity.getDescription());
    record.setRowVersion(entity.getVersion());
  }

  private Faction toEntity(Record record) {
    Faction entity = new Faction();
    entity.setId(record.get(FACTIONS.ID));
    entity.setTenantId(record.get(FACTIONS.TENANT_ID));
    entity.setName(record.get(FACTIONS.NAME));
    entity.setDescription(record.get(FACTIONS.DESCRIPTION));
    Integer rowVersion = record.get(FACTIONS.ROW_VERSION);
    entity.setVersion(rowVersion == null ? 0 : rowVersion);
    return entity;
  }
}
