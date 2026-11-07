package net.firedevops.firemud.accountservice.repository;

import static net.firedevops.firemud.accountservice.jooq.Tables.ACCOUNTS;
import static net.firedevops.firemud.accountservice.jooq.Tables.SUBSCRIPTION;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.UUID;
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

  /** Shares the row lock with subscription writers through the JOIN commit boundary. */
  public List<Subscription> findByTenantIdForUpdate(Long tenantId) {
    return baseSelect()
        .where(SUBSCRIPTION.TENANT_ID.eq(tenantId))
        .orderBy(SUBSCRIPTION.ID.asc())
        .forUpdate()
        .of(SUBSCRIPTION)
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
      var inserted =
          dsl.insertInto(SUBSCRIPTION)
              .set(SUBSCRIPTION.ACCOUNT_ID, accountId)
              .set(SUBSCRIPTION.PLAN_ID, entity.getPlanId())
              .set(SUBSCRIPTION.STATUS, entity.getStatus())
              .set(SUBSCRIPTION.STARTED_AT, entity.getStartedAt())
              .set(SUBSCRIPTION.ENDED_AT, entity.getEndedAt())
              .set(SUBSCRIPTION.TENANT_ID, entity.getTenantId())
              .set(SUBSCRIPTION.ENTITLEMENT_VERSION, 1L)
              .set(SUBSCRIPTION.TENANT_AUTHORITY_GENERATION, UUID.randomUUID())
              .returningResult(
                  SUBSCRIPTION.ID,
                  SUBSCRIPTION.ENTITLEMENT_VERSION,
                  SUBSCRIPTION.TENANT_AUTHORITY_GENERATION)
              .fetchOne();
      if (inserted == null) {
        throw new IllegalStateException("Subscription insert did not return its authority state");
      }
      entity.setId(inserted.get(SUBSCRIPTION.ID));
      entity.setEntitlementVersion(1L);
      entity.setTenantAuthorityGeneration(inserted.get(SUBSCRIPTION.TENANT_AUTHORITY_GENERATION));
      return entity;
    }
    var updated =
        dsl.update(SUBSCRIPTION)
            .set(SUBSCRIPTION.ACCOUNT_ID, accountId)
            .set(SUBSCRIPTION.PLAN_ID, entity.getPlanId())
            .set(SUBSCRIPTION.STATUS, entity.getStatus())
            .set(SUBSCRIPTION.STARTED_AT, entity.getStartedAt())
            .set(SUBSCRIPTION.ENDED_AT, entity.getEndedAt())
            .set(SUBSCRIPTION.TENANT_ID, entity.getTenantId())
            .where(
                SUBSCRIPTION
                    .ID
                    .eq(entity.getId())
                    .and(SUBSCRIPTION.ENTITLEMENT_VERSION.eq(entity.getEntitlementVersion()))
                    .and(
                        entity.getTenantAuthorityGeneration() == null
                            ? SUBSCRIPTION.TENANT_AUTHORITY_GENERATION.isNull()
                            : SUBSCRIPTION.TENANT_AUTHORITY_GENERATION.eq(
                                entity.getTenantAuthorityGeneration())))
            .returningResult(
                SUBSCRIPTION.ENTITLEMENT_VERSION, SUBSCRIPTION.TENANT_AUTHORITY_GENERATION)
            .fetchOne();
    if (updated == null) {
      throw JooqAccountRepositorySupport.staleWrite("subscription", entity.getId());
    }
    entity.setEntitlementVersion(updated.get(SUBSCRIPTION.ENTITLEMENT_VERSION));
    entity.setTenantAuthorityGeneration(updated.get(SUBSCRIPTION.TENANT_AUTHORITY_GENERATION));
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
            SUBSCRIPTION.ENTITLEMENT_VERSION,
            SUBSCRIPTION.TENANT_AUTHORITY_GENERATION,
            ACCOUNTS.ID,
            ACCOUNTS.USERNAME,
            ACCOUNTS.EMAIL,
            ACCOUNTS.PASSWORD_HASH,
            ACCOUNTS.ROLE,
            ACCOUNTS.EMAIL_VERIFIED,
            ACCOUNTS.LOGIN_AUTH_MODES)
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
            record.get(ACCOUNTS.EMAIL_VERIFIED),
            record.get(ACCOUNTS.LOGIN_AUTH_MODES)));
    entity.setPlanId(record.get(SUBSCRIPTION.PLAN_ID));
    entity.setStatus(record.get(SUBSCRIPTION.STATUS));
    entity.setStartedAt(record.get(SUBSCRIPTION.STARTED_AT));
    entity.setEndedAt(record.get(SUBSCRIPTION.ENDED_AT));
    entity.setTenantId(record.get(SUBSCRIPTION.TENANT_ID));
    entity.setEntitlementVersion(record.get(SUBSCRIPTION.ENTITLEMENT_VERSION));
    entity.setTenantAuthorityGeneration(record.get(SUBSCRIPTION.TENANT_AUTHORITY_GENERATION));
    return entity;
  }
}
