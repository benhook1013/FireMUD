package net.firedevops.firemud.entitymanagement.repository;

import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.*;
import static net.firedevops.firemud.entitymanagement.jooq.Tables.NPCS;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.entitymanagement.entity.Npc;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class NpcRepository {
  private final DSLContext dsl;

  public NpcRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Optional<Npc> findById(Long id) {
    return Optional.ofNullable(dsl.selectFrom(NPCS).where(NPCS.ID.eq(id)).fetchOne(this::toEntity));
  }

  public Optional<Npc> findByTenantIdAndVersionIdAndId(Long tenantId, Long versionId, Long id) {
    return Optional.ofNullable(
        dsl.selectFrom(NPCS)
            .where(
                NPCS.TENANT_ID.eq(tenantId).and(NPCS.VERSION_ID.eq(versionId)).and(NPCS.ID.eq(id)))
            .fetchOne(this::toEntity));
  }

  public List<Npc> findByTenantIdOrderByIdAsc(Long tenantId) {
    return dsl.selectFrom(NPCS)
        .where(NPCS.TENANT_ID.eq(tenantId))
        .orderBy(NPCS.ID.asc())
        .fetch(this::toEntity);
  }

  public List<Npc> findByTenantIdAndVersionIdOrderByIdAsc(Long tenantId, Long versionId) {
    return dsl.selectFrom(NPCS)
        .where(NPCS.TENANT_ID.eq(tenantId).and(NPCS.VERSION_ID.eq(versionId)))
        .orderBy(NPCS.ID.asc())
        .fetch(this::toEntity);
  }

  public Npc save(Npc entity) {
    if (entity.getId() == null) {
      Long id =
          dsl.insertInto(NPCS)
              .set(NPCS.TENANT_ID, entity.getTenantId())
              .set(NPCS.VERSION_ID, entity.getVersionId())
              .set(NPCS.NAME, entity.getName())
              .set(NPCS.BEHAVIOR, entity.getBehavior())
              .set(NPCS.RESPAWN_DELAY, entity.getRespawnDelaySeconds())
              .set(NPCS.LAST_DEFEATED_AT, toLocalDateTime(entity.getLastDefeatedAt()))
              .set(NPCS.VERSION, entity.getVersion())
              .returningResult(NPCS.ID)
              .fetchOne(NPCS.ID);
      return findById(id).orElseThrow();
    }
    dsl.update(NPCS)
        .set(NPCS.TENANT_ID, entity.getTenantId())
        .set(NPCS.VERSION_ID, entity.getVersionId())
        .set(NPCS.NAME, entity.getName())
        .set(NPCS.BEHAVIOR, entity.getBehavior())
        .set(NPCS.RESPAWN_DELAY, entity.getRespawnDelaySeconds())
        .set(NPCS.LAST_DEFEATED_AT, toLocalDateTime(entity.getLastDefeatedAt()))
        .set(NPCS.VERSION, entity.getVersion() + 1)
        .where(NPCS.ID.eq(entity.getId()))
        .execute();
    return findById(entity.getId()).orElseThrow();
  }

  private Npc toEntity(Record record) {
    if (record == null) {
      return null;
    }
    Npc npc = new Npc();
    npc.setId(record.get(NPCS.ID));
    npc.setTenantId(record.get(NPCS.TENANT_ID));
    npc.setVersionId(record.get(NPCS.VERSION_ID));
    npc.setName(record.get(NPCS.NAME));
    npc.setBehavior(record.get(NPCS.BEHAVIOR));
    npc.setRespawnDelaySeconds(record.get(NPCS.RESPAWN_DELAY));
    npc.setLastDefeatedAt(toInstant(record.get(NPCS.LAST_DEFEATED_AT)));
    npc.setVersion(record.get(NPCS.VERSION));
    return npc;
  }
}
