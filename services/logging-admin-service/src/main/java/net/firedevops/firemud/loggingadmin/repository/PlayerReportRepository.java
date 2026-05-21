package net.firedevops.firemud.loggingadmin.repository;

import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toInstant;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toLocalDateTime;
import static net.firedevops.firemud.loggingadmin.jooq.tables.PlayerReports.PLAYER_REPORTS;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Optional;
import net.firedevops.firemud.loggingadmin.entity.PlayerReport;
import net.firedevops.firemud.loggingadmin.jooq.tables.records.PlayerReportsRecord;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class PlayerReportRepository {
  private final DSLContext dsl;

  public PlayerReportRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public long count() {
    return dsl.fetchCount(PLAYER_REPORTS);
  }

  public PlayerReport save(PlayerReport entity) {
    if (entity.getId() == null) {
      PlayerReportsRecord record = dsl.newRecord(PLAYER_REPORTS);
      populate(record, entity);
      record.store();
      entity.setId(record.getId());
      return entity;
    }
    int updated =
        dsl.update(PLAYER_REPORTS)
            .set(PLAYER_REPORTS.TENANT_ID, entity.getTenantId())
            .set(PLAYER_REPORTS.REPORTER_ACCOUNT_ID, entity.getReporterAccountId())
            .set(PLAYER_REPORTS.TARGET_ACCOUNT_ID, entity.getTargetAccountId())
            .set(PLAYER_REPORTS.TYPE, entity.getType())
            .set(PLAYER_REPORTS.DESCRIPTION, entity.getDescription())
            .set(PLAYER_REPORTS.CREATED_AT, toLocalDateTime(entity.getCreatedAt()))
            .where(PLAYER_REPORTS.ID.eq(entity.getId()))
            .execute();
    if (updated != 1) {
      throw new IllegalStateException("Failed to update player_reports id=" + entity.getId());
    }
    return findById(entity.getId()).orElseThrow();
  }

  private Optional<PlayerReport> findById(Long id) {
    return dsl.selectFrom(PLAYER_REPORTS)
        .where(PLAYER_REPORTS.ID.eq(id))
        .fetchOptional(this::toEntity);
  }

  private void populate(PlayerReportsRecord record, PlayerReport entity) {
    record.setTenantId(entity.getTenantId());
    record.setReporterAccountId(entity.getReporterAccountId());
    record.setTargetAccountId(entity.getTargetAccountId());
    record.setType(entity.getType());
    record.setDescription(entity.getDescription());
    record.setCreatedAt(toLocalDateTime(entity.getCreatedAt()));
  }

  private PlayerReport toEntity(Record record) {
    PlayerReport entity = new PlayerReport();
    entity.setId(record.get(PLAYER_REPORTS.ID));
    entity.setTenantId(record.get(PLAYER_REPORTS.TENANT_ID));
    entity.setReporterAccountId(record.get(PLAYER_REPORTS.REPORTER_ACCOUNT_ID));
    entity.setTargetAccountId(record.get(PLAYER_REPORTS.TARGET_ACCOUNT_ID));
    entity.setType(record.get(PLAYER_REPORTS.TYPE));
    entity.setDescription(record.get(PLAYER_REPORTS.DESCRIPTION));
    entity.setCreatedAt(toInstant(record.get(PLAYER_REPORTS.CREATED_AT)));
    return entity;
  }
}
