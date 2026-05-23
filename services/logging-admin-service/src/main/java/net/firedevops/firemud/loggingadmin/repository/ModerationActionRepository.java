package net.firedevops.firemud.loggingadmin.repository;

import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toInstant;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toLocalDateTime;
import static net.firedevops.firemud.loggingadmin.jooq.tables.ModerationActions.MODERATION_ACTIONS;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.loggingadmin.entity.ModerationAction;
import net.firedevops.firemud.loggingadmin.jooq.tables.records.ModerationActionsRecord;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class ModerationActionRepository {
  private final DSLContext dsl;

  public ModerationActionRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public long count() {
    return dsl.fetchCount(MODERATION_ACTIONS);
  }

  public Optional<ModerationAction> findFirstByTenantIdAndAccountIdAndActionAndReason(
      Long tenantId, Long accountId, String action, String reason) {
    return dsl.selectFrom(MODERATION_ACTIONS)
        .where(
            MODERATION_ACTIONS
                .TENANT_ID
                .eq(tenantId)
                .and(MODERATION_ACTIONS.ACCOUNT_ID.eq(accountId))
                .and(MODERATION_ACTIONS.ACTION.eq(action))
                .and(MODERATION_ACTIONS.REASON.eq(reason)))
        .orderBy(MODERATION_ACTIONS.ID.asc())
        .limit(1)
        .fetchOptional(this::toEntity);
  }

  public List<ModerationAction> findActivePolicyActions(
      Long tenantId, Long accountId, List<String> actions, Instant now) {
    return dsl.selectFrom(MODERATION_ACTIONS)
        .where(
            MODERATION_ACTIONS
                .TENANT_ID
                .eq(tenantId)
                .and(MODERATION_ACTIONS.ACCOUNT_ID.eq(accountId))
                .and(DSL.lower(MODERATION_ACTIONS.ACTION).in(actions))
                .and(
                    MODERATION_ACTIONS
                        .EXPIRES_AT
                        .isNull()
                        .or(MODERATION_ACTIONS.EXPIRES_AT.gt(toLocalDateTime(now)))))
        .orderBy(MODERATION_ACTIONS.CREATED_AT.desc())
        .fetch(this::toEntity);
  }

  public ModerationAction save(ModerationAction entity) {
    if (entity.getId() == null) {
      ModerationActionsRecord record = dsl.newRecord(MODERATION_ACTIONS);
      populate(record, entity);
      record.store();
      entity.setId(record.getId());
      return entity;
    }
    int updated =
        dsl.update(MODERATION_ACTIONS)
            .set(MODERATION_ACTIONS.TENANT_ID, entity.getTenantId())
            .set(MODERATION_ACTIONS.ACCOUNT_ID, entity.getAccountId())
            .set(MODERATION_ACTIONS.ACTION, entity.getAction())
            .set(MODERATION_ACTIONS.REASON, entity.getReason())
            .set(MODERATION_ACTIONS.CREATED_AT, toLocalDateTime(entity.getCreatedAt()))
            .set(MODERATION_ACTIONS.EXPIRES_AT, toLocalDateTime(entity.getExpiresAt()))
            .where(MODERATION_ACTIONS.ID.eq(entity.getId()))
            .execute();
    if (updated != 1) {
      throw new IllegalStateException("Failed to update moderation_actions id=" + entity.getId());
    }
    return findById(entity.getId()).orElseThrow();
  }

  private Optional<ModerationAction> findById(Long id) {
    return dsl.selectFrom(MODERATION_ACTIONS)
        .where(MODERATION_ACTIONS.ID.eq(id))
        .fetchOptional(this::toEntity);
  }

  private void populate(ModerationActionsRecord record, ModerationAction entity) {
    record.setTenantId(entity.getTenantId());
    record.setAccountId(entity.getAccountId());
    record.setAction(entity.getAction());
    record.setReason(entity.getReason());
    record.setCreatedAt(toLocalDateTime(entity.getCreatedAt()));
    record.setExpiresAt(toLocalDateTime(entity.getExpiresAt()));
  }

  private ModerationAction toEntity(Record record) {
    ModerationAction entity = new ModerationAction();
    entity.setId(record.get(MODERATION_ACTIONS.ID));
    entity.setTenantId(record.get(MODERATION_ACTIONS.TENANT_ID));
    entity.setAccountId(record.get(MODERATION_ACTIONS.ACCOUNT_ID));
    entity.setAction(record.get(MODERATION_ACTIONS.ACTION));
    entity.setReason(record.get(MODERATION_ACTIONS.REASON));
    entity.setCreatedAt(toInstant(record.get(MODERATION_ACTIONS.CREATED_AT)));
    entity.setExpiresAt(toInstant(record.get(MODERATION_ACTIONS.EXPIRES_AT)));
    return entity;
  }
}
