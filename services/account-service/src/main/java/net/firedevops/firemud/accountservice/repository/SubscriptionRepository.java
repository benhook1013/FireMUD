package net.firedevops.firemud.accountservice.repository;

import static net.firedevops.firemud.accountservice.jooq.Tables.ACCOUNTS;
import static net.firedevops.firemud.accountservice.jooq.Tables.SUBSCRIPTION;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import net.firedevops.firemud.accountservice.entity.Subscription;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class SubscriptionRepository {
  private final DSLContext dsl;

  public SubscriptionRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public List<Subscription> findByTenantId(Long tenantId) {
    return baseSelect()
        .where(SUBSCRIPTION.TENANT_ID.eq(tenantId))
        .orderBy(SUBSCRIPTION.ID.asc())
        .fetch(this::toEntity);
  }

  public List<Subscription> findByAccountId(Long accountId) {
    return baseSelect()
        .where(SUBSCRIPTION.ACCOUNT_ID.eq(accountId))
        .orderBy(SUBSCRIPTION.ID.asc())
        .fetch(this::toEntity);
  }

  public Subscription save(Subscription entity) {
    Long accountId = entity.getAccount() == null ? null : entity.getAccount().getId();
    if (entity.getId() == null) {
      Long id =
          dsl.insertInto(SUBSCRIPTION)
              .set(SUBSCRIPTION.ACCOUNT_ID, accountId)
              .set(SUBSCRIPTION.PLAN_ID, entity.getPlanId())
              .set(SUBSCRIPTION.STATUS, entity.getStatus())
              .set(SUBSCRIPTION.STARTED_AT, entity.getStartedAt())
              .set(SUBSCRIPTION.ENDED_AT, entity.getEndedAt())
              .set(SUBSCRIPTION.TENANT_ID, entity.getTenantId())
              .returningResult(SUBSCRIPTION.ID)
              .fetchOne(SUBSCRIPTION.ID);
      entity.setId(id);
      return entity;
    }
    int updated =
        dsl.update(SUBSCRIPTION)
            .set(SUBSCRIPTION.ACCOUNT_ID, accountId)
            .set(SUBSCRIPTION.PLAN_ID, entity.getPlanId())
            .set(SUBSCRIPTION.STATUS, entity.getStatus())
            .set(SUBSCRIPTION.STARTED_AT, entity.getStartedAt())
            .set(SUBSCRIPTION.ENDED_AT, entity.getEndedAt())
            .set(SUBSCRIPTION.TENANT_ID, entity.getTenantId())
            .where(SUBSCRIPTION.ID.eq(entity.getId()))
            .execute();
    if (updated != 1) {
      throw JooqAccountRepositorySupport.staleWrite("subscription", entity.getId());
    }
    return entity;
  }

  public void deleteByAccountId(Long accountId, Long tenantId) {
    dsl.deleteFrom(SUBSCRIPTION)
        .where(SUBSCRIPTION.ACCOUNT_ID.eq(accountId).and(SUBSCRIPTION.TENANT_ID.eq(tenantId)))
        .execute();
  }

  public void deleteByAccountId(Long accountId) {
    dsl.deleteFrom(SUBSCRIPTION).where(SUBSCRIPTION.ACCOUNT_ID.eq(accountId)).execute();
  }

  private org.jooq.SelectOnConditionStep<? extends Record> baseSelect() {
    return dsl.select(
            SUBSCRIPTION.ID,
            SUBSCRIPTION.ACCOUNT_ID,
            SUBSCRIPTION.PLAN_ID,
            SUBSCRIPTION.STATUS,
            SUBSCRIPTION.STARTED_AT,
            SUBSCRIPTION.ENDED_AT,
            SUBSCRIPTION.TENANT_ID,
            ACCOUNTS.ID,
            ACCOUNTS.USERNAME,
            ACCOUNTS.EMAIL,
            ACCOUNTS.PASSWORD_HASH,
            ACCOUNTS.ROLE,
            ACCOUNTS.TWO_FACTOR_SECRET,
            ACCOUNTS.EMAIL_VERIFIED)
        .from(SUBSCRIPTION)
        .join(ACCOUNTS)
        .on(SUBSCRIPTION.ACCOUNT_ID.eq(ACCOUNTS.ID));
  }

  private Subscription toEntity(Record record) {
    Subscription entity = new Subscription();
    entity.setId(record.get(SUBSCRIPTION.ID));
    entity.setAccount(
        JooqAccountRepositorySupport.partialAccount(
            record.get(ACCOUNTS.ID),
            record.get(ACCOUNTS.USERNAME),
            record.get(ACCOUNTS.EMAIL),
            record.get(ACCOUNTS.PASSWORD_HASH),
            record.get(ACCOUNTS.ROLE),
            record.get(ACCOUNTS.TWO_FACTOR_SECRET),
            record.get(ACCOUNTS.EMAIL_VERIFIED)));
    entity.setPlanId(record.get(SUBSCRIPTION.PLAN_ID));
    entity.setStatus(record.get(SUBSCRIPTION.STATUS));
    entity.setStartedAt(record.get(SUBSCRIPTION.STARTED_AT));
    entity.setEndedAt(record.get(SUBSCRIPTION.ENDED_AT));
    entity.setTenantId(record.get(SUBSCRIPTION.TENANT_ID));
    return entity;
  }
}
