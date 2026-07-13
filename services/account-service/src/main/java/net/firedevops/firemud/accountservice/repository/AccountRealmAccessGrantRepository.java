package net.firedevops.firemud.accountservice.repository;

import static net.firedevops.firemud.accountservice.jooq.Tables.ACCOUNTS;
import static net.firedevops.firemud.accountservice.jooq.Tables.ACCOUNT_REALM_ACCESS_GRANT;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Optional;
import net.firedevops.firemud.accountservice.entity.AccountRealmAccessGrant;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class AccountRealmAccessGrantRepository {
  private final DSLContext dsl;

  public AccountRealmAccessGrantRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Optional<AccountRealmAccessGrant> findByAccountIdAndTenantIdAndWorldSlugAndRealmSlug(
      Long accountId, Long tenantId, String worldSlug, String realmSlug) {
    return Optional.ofNullable(
        baseSelect()
            .where(
                ACCOUNT_REALM_ACCESS_GRANT
                    .ACCOUNT_ID
                    .eq(accountId)
                    .and(ACCOUNT_REALM_ACCESS_GRANT.TENANT_ID.eq(tenantId))
                    .and(ACCOUNT_REALM_ACCESS_GRANT.WORLD_SLUG.eq(worldSlug))
                    .and(ACCOUNT_REALM_ACCESS_GRANT.REALM_SLUG.eq(realmSlug)))
            .fetchOne(this::toEntity));
  }

  public boolean existsByAccountIdAndTenantIdAndWorldSlugAndRealmSlug(
      Long accountId, Long tenantId, String worldSlug, String realmSlug) {
    return dsl.fetchExists(
        ACCOUNT_REALM_ACCESS_GRANT,
        ACCOUNT_REALM_ACCESS_GRANT
            .ACCOUNT_ID
            .eq(accountId)
            .and(ACCOUNT_REALM_ACCESS_GRANT.TENANT_ID.eq(tenantId))
            .and(ACCOUNT_REALM_ACCESS_GRANT.WORLD_SLUG.eq(worldSlug))
            .and(ACCOUNT_REALM_ACCESS_GRANT.REALM_SLUG.eq(realmSlug)));
  }

  public AccountRealmAccessGrant save(AccountRealmAccessGrant entity) {
    Long accountId = entity.getAccount() == null ? null : entity.getAccount().getId();
    if (entity.getId() == null) {
      Long id =
          dsl.insertInto(ACCOUNT_REALM_ACCESS_GRANT)
              .set(ACCOUNT_REALM_ACCESS_GRANT.ACCOUNT_ID, accountId)
              .set(ACCOUNT_REALM_ACCESS_GRANT.TENANT_ID, entity.getTenantId())
              .set(ACCOUNT_REALM_ACCESS_GRANT.WORLD_SLUG, entity.getWorldSlug())
              .set(ACCOUNT_REALM_ACCESS_GRANT.REALM_SLUG, entity.getRealmSlug())
              .set(ACCOUNT_REALM_ACCESS_GRANT.GRANT_VERSION, entity.getGrantVersion())
              .set(ACCOUNT_REALM_ACCESS_GRANT.GRANTED_BY, entity.getGrantedBy())
              .set(ACCOUNT_REALM_ACCESS_GRANT.GRANT_REASON, entity.getGrantReason())
              .set(
                  ACCOUNT_REALM_ACCESS_GRANT.CREATED_AT,
                  JooqAccountRepositorySupport.toOffsetDateTime(entity.getCreatedAt()))
              .set(
                  ACCOUNT_REALM_ACCESS_GRANT.UPDATED_AT,
                  JooqAccountRepositorySupport.toOffsetDateTime(entity.getUpdatedAt()))
              .returningResult(ACCOUNT_REALM_ACCESS_GRANT.ID)
              .fetchOne(ACCOUNT_REALM_ACCESS_GRANT.ID);
      entity.setId(id);
      return entity;
    }
    int updated =
        dsl.update(ACCOUNT_REALM_ACCESS_GRANT)
            .set(ACCOUNT_REALM_ACCESS_GRANT.ACCOUNT_ID, accountId)
            .set(ACCOUNT_REALM_ACCESS_GRANT.TENANT_ID, entity.getTenantId())
            .set(ACCOUNT_REALM_ACCESS_GRANT.WORLD_SLUG, entity.getWorldSlug())
            .set(ACCOUNT_REALM_ACCESS_GRANT.REALM_SLUG, entity.getRealmSlug())
            .set(ACCOUNT_REALM_ACCESS_GRANT.GRANT_VERSION, entity.getGrantVersion())
            .set(ACCOUNT_REALM_ACCESS_GRANT.GRANTED_BY, entity.getGrantedBy())
            .set(ACCOUNT_REALM_ACCESS_GRANT.GRANT_REASON, entity.getGrantReason())
            .set(
                ACCOUNT_REALM_ACCESS_GRANT.CREATED_AT,
                JooqAccountRepositorySupport.toOffsetDateTime(entity.getCreatedAt()))
            .set(
                ACCOUNT_REALM_ACCESS_GRANT.UPDATED_AT,
                JooqAccountRepositorySupport.toOffsetDateTime(entity.getUpdatedAt()))
            .where(ACCOUNT_REALM_ACCESS_GRANT.ID.eq(entity.getId()))
            .execute();
    if (updated != 1) {
      throw JooqAccountRepositorySupport.staleWrite("account_realm_access_grant", entity.getId());
    }
    return entity;
  }

  public void deleteByAccountIdAndTenantIdAndWorldSlugAndRealmSlug(
      Long accountId, Long tenantId, String worldSlug, String realmSlug) {
    dsl.deleteFrom(ACCOUNT_REALM_ACCESS_GRANT)
        .where(
            ACCOUNT_REALM_ACCESS_GRANT
                .ACCOUNT_ID
                .eq(accountId)
                .and(ACCOUNT_REALM_ACCESS_GRANT.TENANT_ID.eq(tenantId))
                .and(ACCOUNT_REALM_ACCESS_GRANT.WORLD_SLUG.eq(worldSlug))
                .and(ACCOUNT_REALM_ACCESS_GRANT.REALM_SLUG.eq(realmSlug)))
        .execute();
  }

  public void deleteByAccountId(Long accountId) {
    dsl.deleteFrom(ACCOUNT_REALM_ACCESS_GRANT)
        .where(ACCOUNT_REALM_ACCESS_GRANT.ACCOUNT_ID.eq(accountId))
        .execute();
  }

  private org.jooq.SelectOnConditionStep<? extends Record> baseSelect() {
    return dsl.select(
            ACCOUNT_REALM_ACCESS_GRANT.ID,
            ACCOUNT_REALM_ACCESS_GRANT.ACCOUNT_ID,
            ACCOUNT_REALM_ACCESS_GRANT.TENANT_ID,
            ACCOUNT_REALM_ACCESS_GRANT.WORLD_SLUG,
            ACCOUNT_REALM_ACCESS_GRANT.REALM_SLUG,
            ACCOUNT_REALM_ACCESS_GRANT.GRANT_VERSION,
            ACCOUNT_REALM_ACCESS_GRANT.GRANTED_BY,
            ACCOUNT_REALM_ACCESS_GRANT.GRANT_REASON,
            ACCOUNT_REALM_ACCESS_GRANT.CREATED_AT,
            ACCOUNT_REALM_ACCESS_GRANT.UPDATED_AT,
            ACCOUNTS.ID,
            ACCOUNTS.USERNAME,
            ACCOUNTS.EMAIL,
            ACCOUNTS.PASSWORD_HASH,
            ACCOUNTS.ROLE,
            ACCOUNTS.EMAIL_VERIFIED,
            ACCOUNTS.LOGIN_AUTH_MODES)
        .from(ACCOUNT_REALM_ACCESS_GRANT)
        .join(ACCOUNTS)
        .on(ACCOUNT_REALM_ACCESS_GRANT.ACCOUNT_ID.eq(ACCOUNTS.ID));
  }

  private AccountRealmAccessGrant toEntity(Record record) {
    AccountRealmAccessGrant grant = new AccountRealmAccessGrant();
    grant.setId(record.get(ACCOUNT_REALM_ACCESS_GRANT.ID));
    grant.setAccount(
        JooqAccountRepositorySupport.partialAccount(
            record.get(ACCOUNTS.ID),
            record.get(ACCOUNTS.USERNAME),
            record.get(ACCOUNTS.EMAIL),
            record.get(ACCOUNTS.PASSWORD_HASH),
            record.get(ACCOUNTS.ROLE),
            record.get(ACCOUNTS.EMAIL_VERIFIED),
            record.get(ACCOUNTS.LOGIN_AUTH_MODES)));
    grant.setTenantId(record.get(ACCOUNT_REALM_ACCESS_GRANT.TENANT_ID));
    grant.setWorldSlug(record.get(ACCOUNT_REALM_ACCESS_GRANT.WORLD_SLUG));
    grant.setRealmSlug(record.get(ACCOUNT_REALM_ACCESS_GRANT.REALM_SLUG));
    grant.setGrantVersion(record.get(ACCOUNT_REALM_ACCESS_GRANT.GRANT_VERSION));
    grant.setGrantedBy(record.get(ACCOUNT_REALM_ACCESS_GRANT.GRANTED_BY));
    grant.setGrantReason(record.get(ACCOUNT_REALM_ACCESS_GRANT.GRANT_REASON));
    grant.setCreatedAt(
        JooqAccountRepositorySupport.toInstant(record.get(ACCOUNT_REALM_ACCESS_GRANT.CREATED_AT)));
    grant.setUpdatedAt(
        JooqAccountRepositorySupport.toInstant(record.get(ACCOUNT_REALM_ACCESS_GRANT.UPDATED_AT)));
    return grant;
  }
}
