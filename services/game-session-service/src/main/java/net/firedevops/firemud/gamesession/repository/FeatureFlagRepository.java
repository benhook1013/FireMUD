package net.firedevops.firemud.gamesession.repository;

import static net.firedevops.firemud.gamesession.jooq.tables.FeatureFlag.FEATURE_FLAG;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Optional;
import net.firedevops.firemud.gamesession.entity.FeatureFlag;
import net.firedevops.firemud.gamesession.jooq.tables.records.FeatureFlagRecord;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class FeatureFlagRepository {
  private final DSLContext dsl;

  public FeatureFlagRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public long count() {
    return dsl.fetchCount(FEATURE_FLAG);
  }

  public Optional<FeatureFlag> findByTenantIdAndName(Long tenantId, String name) {
    return dsl.selectFrom(FEATURE_FLAG)
        .where(FEATURE_FLAG.TENANT_ID.eq(tenantId).and(FEATURE_FLAG.NAME.eq(name)))
        .fetchOptional(this::toEntity);
  }

  public FeatureFlag save(FeatureFlag entity) {
    if (entity.getId() == null) {
      FeatureFlagRecord record = dsl.newRecord(FEATURE_FLAG);
      populate(record, entity);
      record.store();
      return findById(record.getId()).orElseThrow();
    }
    int updated =
        dsl.update(FEATURE_FLAG)
            .set(FEATURE_FLAG.TENANT_ID, entity.getTenantId())
            .set(FEATURE_FLAG.NAME, entity.getName())
            .set(FEATURE_FLAG.ENABLED, entity.isEnabled())
            .where(FEATURE_FLAG.ID.eq(entity.getId()))
            .execute();
    if (updated != 1) {
      throw new IllegalStateException("Failed to update feature_flag id=" + entity.getId());
    }
    return findById(entity.getId()).orElseThrow();
  }

  private Optional<FeatureFlag> findById(Long id) {
    return dsl.selectFrom(FEATURE_FLAG).where(FEATURE_FLAG.ID.eq(id)).fetchOptional(this::toEntity);
  }

  private void populate(FeatureFlagRecord record, FeatureFlag entity) {
    record.setTenantId(entity.getTenantId());
    record.setName(entity.getName());
    record.setEnabled(entity.isEnabled());
  }

  private FeatureFlag toEntity(Record record) {
    FeatureFlag entity = new FeatureFlag();
    entity.setId(record.get(FEATURE_FLAG.ID));
    entity.setTenantId(record.get(FEATURE_FLAG.TENANT_ID));
    entity.setName(record.get(FEATURE_FLAG.NAME));
    entity.setEnabled(Boolean.TRUE.equals(record.get(FEATURE_FLAG.ENABLED)));
    return entity;
  }
}
