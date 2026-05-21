package net.firedevops.firemud.worldmanagement.repository;

import static net.firedevops.firemud.worldmanagement.jooq.tables.Instance.INSTANCE;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.worldmanagement.entity.Instance;
import net.firedevops.firemud.worldmanagement.jooq.tables.records.InstanceRecord;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class InstanceRepository {
  private final DSLContext dsl;

  public InstanceRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public List<Instance> findByExpiresAtBefore(LocalDateTime cutoff) {
    return dsl.selectFrom(INSTANCE).where(INSTANCE.EXPIRES_AT.lt(cutoff)).fetch(this::toEntity);
  }

  public Optional<Instance> findById(Long id) {
    return dsl.selectFrom(INSTANCE).where(INSTANCE.ID.eq(id)).fetchOptional(this::toEntity);
  }

  public Instance save(Instance entity) {
    if (entity.getId() == null) {
      InstanceRecord record = dsl.newRecord(INSTANCE);
      populate(record, entity);
      record.store();
      return findById(record.getId()).orElseThrow();
    }
    int updated =
        dsl.update(INSTANCE)
            .set(INSTANCE.ZONE_ID, entity.getZone().getId())
            .set(INSTANCE.OWNER_ACCOUNT_ID, entity.getOwnerAccountId())
            .set(INSTANCE.CREATED_AT, entity.getCreatedAt())
            .set(INSTANCE.TENANT_ID, entity.getTenantId())
            .set(INSTANCE.EXPIRES_AT, entity.getExpiresAt())
            .set(INSTANCE.VERSION, entity.getVersion() + 1)
            .where(INSTANCE.ID.eq(entity.getId()).and(INSTANCE.VERSION.eq(entity.getVersion())))
            .execute();
    if (updated != 1) {
      throw JooqWorldManagementRepositorySupport.staleWrite("instance", entity.getId());
    }
    return findById(entity.getId()).orElseThrow();
  }

  public void delete(Instance entity) {
    dsl.deleteFrom(INSTANCE).where(INSTANCE.ID.eq(entity.getId())).execute();
  }

  private void populate(InstanceRecord record, Instance entity) {
    record.setZoneId(entity.getZone().getId());
    record.setOwnerAccountId(entity.getOwnerAccountId());
    record.setCreatedAt(entity.getCreatedAt());
    record.setTenantId(entity.getTenantId());
    record.setExpiresAt(entity.getExpiresAt());
    record.setVersion(entity.getVersion());
  }

  private Instance toEntity(Record record) {
    Instance entity = new Instance();
    entity.setId(record.get(INSTANCE.ID));
    entity.setZone(JooqWorldManagementRepositorySupport.partialZone(record.get(INSTANCE.ZONE_ID)));
    entity.setOwnerAccountId(record.get(INSTANCE.OWNER_ACCOUNT_ID));
    entity.setCreatedAt(record.get(INSTANCE.CREATED_AT));
    entity.setTenantId(record.get(INSTANCE.TENANT_ID));
    entity.setExpiresAt(record.get(INSTANCE.EXPIRES_AT));
    Integer version = record.get(INSTANCE.VERSION);
    entity.setVersion(version == null ? 0 : version);
    return entity;
  }
}
