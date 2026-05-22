package net.firedevops.firemud.worldmanagement.repository;

import static net.firedevops.firemud.worldmanagement.jooq.tables.Zone.ZONE;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.worldmanagement.entity.Zone;
import net.firedevops.firemud.worldmanagement.jooq.tables.records.ZoneRecord;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class ZoneRepository {
  private final DSLContext dsl;

  public ZoneRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public List<Zone> findByTenantIdOrderByIdAsc(Long tenantId) {
    return dsl.selectFrom(ZONE)
        .where(ZONE.TENANT_ID.eq(tenantId))
        .orderBy(ZONE.ID.asc())
        .fetch(this::toEntity);
  }

  public List<Zone> findByTenantIdAndVersionIdOrderByIdAsc(Long tenantId, Long versionId) {
    return dsl.selectFrom(ZONE)
        .where(ZONE.TENANT_ID.eq(tenantId).and(ZONE.VERSION_ID.eq(versionId)))
        .orderBy(ZONE.ID.asc())
        .fetch(this::toEntity);
  }

  public Optional<Zone> findByTenantIdAndVersionIdAndId(Long tenantId, Long versionId, Long id) {
    return dsl.selectFrom(ZONE)
        .where(ZONE.TENANT_ID.eq(tenantId).and(ZONE.VERSION_ID.eq(versionId)).and(ZONE.ID.eq(id)))
        .fetchOptional(this::toEntity);
  }

  public Optional<Zone> findById(Long id) {
    return dsl.selectFrom(ZONE).where(ZONE.ID.eq(id)).fetchOptional(this::toEntity);
  }

  public Zone save(Zone entity) {
    if (entity.getId() == null) {
      ZoneRecord record = dsl.newRecord(ZONE);
      populate(record, entity);
      record.store();
      return findById(record.getId()).orElseThrow();
    }
    int updated =
        dsl.update(ZONE)
            .set(ZONE.REGION_ID, entity.getRegion().getId())
            .set(ZONE.NAME, entity.getName())
            .set(ZONE.TENANT_ID, entity.getTenantId())
            .set(ZONE.VERSION_ID, entity.getVersionId())
            .set(ZONE.VERSION, entity.getVersion() + 1)
            .where(ZONE.ID.eq(entity.getId()).and(ZONE.VERSION.eq(entity.getVersion())))
            .execute();
    if (updated != 1) {
      throw JooqWorldManagementRepositorySupport.staleWrite("zone", entity.getId());
    }
    return findById(entity.getId()).orElseThrow();
  }

  public void delete(Zone entity) {
    dsl.deleteFrom(ZONE).where(ZONE.ID.eq(entity.getId())).execute();
  }

  public void deleteAll(List<Zone> entities) {
    if (entities.isEmpty()) {
      return;
    }
    dsl.deleteFrom(ZONE).where(ZONE.ID.in(entities.stream().map(Zone::getId).toList())).execute();
  }

  private void populate(ZoneRecord record, Zone entity) {
    record.setRegionId(entity.getRegion().getId());
    record.setName(entity.getName());
    record.setTenantId(entity.getTenantId());
    record.setVersion(entity.getVersion());
    record.setVersionId(entity.getVersionId());
  }

  private Zone toEntity(Record record) {
    Zone entity = new Zone();
    entity.setId(record.get(ZONE.ID));
    entity.setRegion(
        JooqWorldManagementRepositorySupport.partialRegion(record.get(ZONE.REGION_ID)));
    entity.setName(record.get(ZONE.NAME));
    entity.setTenantId(record.get(ZONE.TENANT_ID));
    Integer version = record.get(ZONE.VERSION);
    entity.setVersion(version == null ? 0 : version);
    entity.setVersionId(record.get(ZONE.VERSION_ID));
    return entity;
  }
}
