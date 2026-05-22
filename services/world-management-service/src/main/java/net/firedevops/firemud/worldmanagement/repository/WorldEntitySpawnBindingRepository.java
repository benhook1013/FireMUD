package net.firedevops.firemud.worldmanagement.repository;

import static net.firedevops.firemud.worldmanagement.jooq.tables.WorldEntitySpawnBinding.WORLD_ENTITY_SPAWN_BINDING;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.worldmanagement.entity.WorldEntitySpawnBinding;
import net.firedevops.firemud.worldmanagement.jooq.tables.records.WorldEntitySpawnBindingRecord;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class WorldEntitySpawnBindingRepository {
  private final DSLContext dsl;

  public WorldEntitySpawnBindingRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Optional<WorldEntitySpawnBinding> findByTenantIdAndVersionIdAndId(
      Long tenantId, Long versionId, Long id) {
    return dsl.selectFrom(WORLD_ENTITY_SPAWN_BINDING)
        .where(
            WORLD_ENTITY_SPAWN_BINDING
                .TENANT_ID
                .eq(tenantId)
                .and(WORLD_ENTITY_SPAWN_BINDING.VERSION_ID.eq(versionId))
                .and(WORLD_ENTITY_SPAWN_BINDING.ID.eq(id)))
        .fetchOptional(this::toEntity);
  }

  public Optional<WorldEntitySpawnBinding>
      findByTenantIdAndVersionIdAndRoomIdAndEntityTemplateTypeAndEntityTemplateId(
          Long tenantId,
          Long versionId,
          Long roomId,
          String entityTemplateType,
          Long entityTemplateId) {
    return dsl.selectFrom(WORLD_ENTITY_SPAWN_BINDING)
        .where(
            WORLD_ENTITY_SPAWN_BINDING
                .TENANT_ID
                .eq(tenantId)
                .and(WORLD_ENTITY_SPAWN_BINDING.VERSION_ID.eq(versionId))
                .and(WORLD_ENTITY_SPAWN_BINDING.ROOM_ID.eq(roomId))
                .and(WORLD_ENTITY_SPAWN_BINDING.ENTITY_TEMPLATE_TYPE.eq(entityTemplateType))
                .and(WORLD_ENTITY_SPAWN_BINDING.ENTITY_TEMPLATE_ID.eq(entityTemplateId)))
        .fetchOptional(this::toEntity);
  }

  public List<WorldEntitySpawnBinding> findByTenantIdAndVersionIdOrderByIdAsc(
      Long tenantId, Long versionId) {
    return dsl.selectFrom(WORLD_ENTITY_SPAWN_BINDING)
        .where(
            WORLD_ENTITY_SPAWN_BINDING
                .TENANT_ID
                .eq(tenantId)
                .and(WORLD_ENTITY_SPAWN_BINDING.VERSION_ID.eq(versionId)))
        .orderBy(WORLD_ENTITY_SPAWN_BINDING.ID.asc())
        .fetch(this::toEntity);
  }

  public Optional<WorldEntitySpawnBinding> findById(Long id) {
    return dsl.selectFrom(WORLD_ENTITY_SPAWN_BINDING)
        .where(WORLD_ENTITY_SPAWN_BINDING.ID.eq(id))
        .fetchOptional(this::toEntity);
  }

  public WorldEntitySpawnBinding save(WorldEntitySpawnBinding entity) {
    if (entity.getId() == null) {
      WorldEntitySpawnBindingRecord record = dsl.newRecord(WORLD_ENTITY_SPAWN_BINDING);
      populate(record, entity);
      record.store();
      return findById(record.getId()).orElseThrow();
    }
    int updated =
        dsl.update(WORLD_ENTITY_SPAWN_BINDING)
            .set(WORLD_ENTITY_SPAWN_BINDING.TENANT_ID, entity.getTenantId())
            .set(WORLD_ENTITY_SPAWN_BINDING.VERSION_ID, entity.getVersionId())
            .set(WORLD_ENTITY_SPAWN_BINDING.ROOM_ID, entity.getRoom().getId())
            .set(WORLD_ENTITY_SPAWN_BINDING.ENTITY_TEMPLATE_TYPE, entity.getEntityTemplateType())
            .set(WORLD_ENTITY_SPAWN_BINDING.ENTITY_TEMPLATE_ID, entity.getEntityTemplateId())
            .set(WORLD_ENTITY_SPAWN_BINDING.SPAWN_COUNT, entity.getSpawnCount())
            .set(WORLD_ENTITY_SPAWN_BINDING.RESPAWN_DELAY_SECONDS, entity.getRespawnDelaySeconds())
            .set(WORLD_ENTITY_SPAWN_BINDING.VERSION, entity.getVersion() + 1)
            .where(
                WORLD_ENTITY_SPAWN_BINDING
                    .ID
                    .eq(entity.getId())
                    .and(WORLD_ENTITY_SPAWN_BINDING.VERSION.eq(entity.getVersion())))
            .execute();
    if (updated != 1) {
      throw JooqWorldManagementRepositorySupport.staleWrite(
          "world_entity_spawn_binding", entity.getId());
    }
    return findById(entity.getId()).orElseThrow();
  }

  public void delete(WorldEntitySpawnBinding entity) {
    dsl.deleteFrom(WORLD_ENTITY_SPAWN_BINDING)
        .where(WORLD_ENTITY_SPAWN_BINDING.ID.eq(entity.getId()))
        .execute();
  }

  public void deleteAll(List<WorldEntitySpawnBinding> entities) {
    if (entities.isEmpty()) {
      return;
    }
    dsl.deleteFrom(WORLD_ENTITY_SPAWN_BINDING)
        .where(
            WORLD_ENTITY_SPAWN_BINDING.ID.in(
                entities.stream().map(WorldEntitySpawnBinding::getId).toList()))
        .execute();
  }

  private void populate(WorldEntitySpawnBindingRecord record, WorldEntitySpawnBinding entity) {
    record.setTenantId(entity.getTenantId());
    record.setVersionId(entity.getVersionId());
    record.setRoomId(entity.getRoom().getId());
    record.setEntityTemplateType(entity.getEntityTemplateType());
    record.setEntityTemplateId(entity.getEntityTemplateId());
    record.setSpawnCount(entity.getSpawnCount());
    record.setRespawnDelaySeconds(entity.getRespawnDelaySeconds());
    record.setVersion(entity.getVersion());
  }

  private WorldEntitySpawnBinding toEntity(Record record) {
    WorldEntitySpawnBinding entity = new WorldEntitySpawnBinding();
    entity.setId(record.get(WORLD_ENTITY_SPAWN_BINDING.ID));
    entity.setTenantId(record.get(WORLD_ENTITY_SPAWN_BINDING.TENANT_ID));
    entity.setVersionId(record.get(WORLD_ENTITY_SPAWN_BINDING.VERSION_ID));
    entity.setRoom(
        JooqWorldManagementRepositorySupport.partialRoom(
            record.get(WORLD_ENTITY_SPAWN_BINDING.ROOM_ID)));
    entity.setEntityTemplateType(record.get(WORLD_ENTITY_SPAWN_BINDING.ENTITY_TEMPLATE_TYPE));
    entity.setEntityTemplateId(record.get(WORLD_ENTITY_SPAWN_BINDING.ENTITY_TEMPLATE_ID));
    Integer spawnCount = record.get(WORLD_ENTITY_SPAWN_BINDING.SPAWN_COUNT);
    entity.setSpawnCount(spawnCount == null ? 1 : spawnCount);
    Integer respawnDelaySeconds = record.get(WORLD_ENTITY_SPAWN_BINDING.RESPAWN_DELAY_SECONDS);
    entity.setRespawnDelaySeconds(respawnDelaySeconds == null ? 0 : respawnDelaySeconds);
    Integer version = record.get(WORLD_ENTITY_SPAWN_BINDING.VERSION);
    entity.setVersion(version == null ? 0 : version);
    return entity;
  }
}
