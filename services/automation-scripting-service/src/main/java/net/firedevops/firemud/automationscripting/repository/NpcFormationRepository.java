package net.firedevops.firemud.automationscripting.repository;

import static net.firedevops.firemud.automationscripting.jooq.tables.NpcFormations.NPC_FORMATIONS;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Optional;
import net.firedevops.firemud.automationscripting.entity.NpcFormation;
import net.firedevops.firemud.automationscripting.jooq.tables.records.NpcFormationsRecord;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class NpcFormationRepository {
  private final DSLContext dsl;

  public NpcFormationRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Optional<NpcFormation> findById(Long id) {
    return dsl.selectFrom(NPC_FORMATIONS)
        .where(NPC_FORMATIONS.ID.eq(id))
        .fetchOptional(this::toEntity);
  }

  public NpcFormation save(NpcFormation entity) {
    if (entity.getId() == null) {
      NpcFormationsRecord record = dsl.newRecord(NPC_FORMATIONS);
      populate(record, entity);
      record.store();
      return findById(record.getId()).orElseThrow();
    }
    int nextVersion = entity.getVersion() + 1;
    int updated =
        dsl.update(NPC_FORMATIONS)
            .set(NPC_FORMATIONS.TENANT_ID, entity.getTenantId())
            .set(NPC_FORMATIONS.NAME, entity.getName())
            .set(NPC_FORMATIONS.LEADER_NPC_ID, entity.getLeaderNpcId())
            .set(
                NPC_FORMATIONS.FORMATION_TYPE,
                entity.getFormationType() == null ? null : entity.getFormationType().name())
            .set(NPC_FORMATIONS.ROW_VERSION, nextVersion)
            .where(
                NPC_FORMATIONS
                    .ID
                    .eq(entity.getId())
                    .and(NPC_FORMATIONS.ROW_VERSION.eq(entity.getVersion())))
            .execute();
    if (updated != 1) {
      throw AutomationScriptingJooqRepositorySupport.staleWrite("npc_formations", entity.getId());
    }
    entity.setVersion(nextVersion);
    return findById(entity.getId()).orElseThrow();
  }

  private void populate(NpcFormationsRecord record, NpcFormation entity) {
    record.setTenantId(entity.getTenantId());
    record.setName(entity.getName());
    record.setLeaderNpcId(entity.getLeaderNpcId());
    record.setFormationType(
        entity.getFormationType() == null ? null : entity.getFormationType().name());
    record.setRowVersion(entity.getVersion());
  }

  private NpcFormation toEntity(Record record) {
    NpcFormation entity = new NpcFormation();
    entity.setId(record.get(NPC_FORMATIONS.ID));
    entity.setTenantId(record.get(NPC_FORMATIONS.TENANT_ID));
    entity.setName(record.get(NPC_FORMATIONS.NAME));
    entity.setLeaderNpcId(record.get(NPC_FORMATIONS.LEADER_NPC_ID));
    String formationType = record.get(NPC_FORMATIONS.FORMATION_TYPE);
    entity.setFormationType(
        formationType == null
            ? null
            : net.firedevops.firemud.automationscripting.model.FormationType.valueOf(
                formationType));
    Integer rowVersion = record.get(NPC_FORMATIONS.ROW_VERSION);
    entity.setVersion(rowVersion == null ? 0 : rowVersion);
    return entity;
  }
}
