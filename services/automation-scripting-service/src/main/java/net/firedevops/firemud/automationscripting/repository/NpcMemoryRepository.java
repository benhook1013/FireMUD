package net.firedevops.firemud.automationscripting.repository;

import static net.firedevops.firemud.automationscripting.jooq.tables.NpcMemory.NPC_MEMORY;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Optional;
import net.firedevops.firemud.automationscripting.entity.NpcMemory;
import net.firedevops.firemud.automationscripting.jooq.tables.records.NpcMemoryRecord;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class NpcMemoryRepository {
  private final DSLContext dsl;

  public NpcMemoryRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Optional<NpcMemory> findByNpcIdAndKeyAndTenantId(Long npcId, String key, Long tenantId) {
    return dsl.selectFrom(NPC_MEMORY)
        .where(
            NPC_MEMORY
                .NPC_ID
                .eq(npcId)
                .and(NPC_MEMORY.KEY.eq(key))
                .and(NPC_MEMORY.TENANT_ID.eq(tenantId)))
        .fetchOptional(this::toEntity);
  }

  public NpcMemory save(NpcMemory entity) {
    if (entity.getId() == null) {
      NpcMemoryRecord record = dsl.newRecord(NPC_MEMORY);
      populate(record, entity);
      record.store();
      return findById(record.getId()).orElseThrow();
    }
    int nextVersion = entity.getVersion() + 1;
    int updated =
        dsl.update(NPC_MEMORY)
            .set(NPC_MEMORY.NPC_ID, entity.getNpcId())
            .set(NPC_MEMORY.KEY, entity.getKey())
            .set(NPC_MEMORY.VALUE, entity.getValue())
            .set(NPC_MEMORY.TENANT_ID, entity.getTenantId())
            .set(NPC_MEMORY.ROW_VERSION, nextVersion)
            .where(
                NPC_MEMORY
                    .ID
                    .eq(entity.getId())
                    .and(NPC_MEMORY.ROW_VERSION.eq(entity.getVersion())))
            .execute();
    if (updated != 1) {
      throw AutomationScriptingJooqRepositorySupport.staleWrite("npc_memory", entity.getId());
    }
    entity.setVersion(nextVersion);
    return findById(entity.getId()).orElseThrow();
  }

  private Optional<NpcMemory> findById(Long id) {
    return dsl.selectFrom(NPC_MEMORY).where(NPC_MEMORY.ID.eq(id)).fetchOptional(this::toEntity);
  }

  private void populate(NpcMemoryRecord record, NpcMemory entity) {
    record.setNpcId(entity.getNpcId());
    record.setKey(entity.getKey());
    record.setValue(entity.getValue());
    record.setTenantId(entity.getTenantId());
    record.setRowVersion(entity.getVersion());
  }

  private NpcMemory toEntity(Record record) {
    NpcMemory entity = new NpcMemory();
    entity.setId(record.get(NPC_MEMORY.ID));
    entity.setNpcId(record.get(NPC_MEMORY.NPC_ID));
    entity.setKey(record.get(NPC_MEMORY.KEY));
    entity.setValue(record.get(NPC_MEMORY.VALUE));
    entity.setTenantId(record.get(NPC_MEMORY.TENANT_ID));
    Integer rowVersion = record.get(NPC_MEMORY.ROW_VERSION);
    entity.setVersion(rowVersion == null ? 0 : rowVersion);
    return entity;
  }
}
