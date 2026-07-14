package net.firedevops.firemud.accountservice.repository;

import static net.firedevops.firemud.accountservice.jooq.Tables.ACCOUNTS;
import static net.firedevops.firemud.accountservice.jooq.Tables.EXTERNAL_ACCOUNT;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Optional;
import net.firedevops.firemud.accountservice.entity.ExternalAccount;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class ExternalAccountRepository {
  private final DSLContext dsl;

  public ExternalAccountRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Optional<ExternalAccount> findByTenantIdAndProviderAndExternalId(
      Long tenantId, String provider, String externalId) {
    return Optional.ofNullable(
        baseSelect()
            .where(
                EXTERNAL_ACCOUNT
                    .TENANT_ID
                    .eq(tenantId)
                    .and(EXTERNAL_ACCOUNT.PROVIDER.eq(provider))
                    .and(EXTERNAL_ACCOUNT.EXTERNAL_ID.eq(externalId)))
            .fetchOne(this::toEntity));
  }

  public boolean existsByTenantIdAndAccountIdAndProvider(
      Long tenantId, Long accountId, String provider) {
    return dsl.fetchExists(
        EXTERNAL_ACCOUNT,
        EXTERNAL_ACCOUNT
            .TENANT_ID
            .eq(tenantId)
            .and(EXTERNAL_ACCOUNT.ACCOUNT_ID.eq(accountId))
            .and(EXTERNAL_ACCOUNT.PROVIDER.eq(provider)));
  }

  public ExternalAccount save(ExternalAccount entity) {
    Long accountId = entity.getAccount() == null ? null : entity.getAccount().getId();
    if (entity.getId() == null) {
      Long id =
          dsl.insertInto(EXTERNAL_ACCOUNT)
              .set(EXTERNAL_ACCOUNT.ACCOUNT_ID, accountId)
              .set(EXTERNAL_ACCOUNT.TENANT_ID, entity.getTenantId())
              .set(EXTERNAL_ACCOUNT.PROVIDER, entity.getProvider())
              .set(EXTERNAL_ACCOUNT.EXTERNAL_ID, entity.getExternalId())
              .returningResult(EXTERNAL_ACCOUNT.ID)
              .fetchOne(EXTERNAL_ACCOUNT.ID);
      entity.setId(id);
      return entity;
    }
    int updated =
        dsl.update(EXTERNAL_ACCOUNT)
            .set(EXTERNAL_ACCOUNT.ACCOUNT_ID, accountId)
            .set(EXTERNAL_ACCOUNT.TENANT_ID, entity.getTenantId())
            .set(EXTERNAL_ACCOUNT.PROVIDER, entity.getProvider())
            .set(EXTERNAL_ACCOUNT.EXTERNAL_ID, entity.getExternalId())
            .where(EXTERNAL_ACCOUNT.ID.eq(entity.getId()))
            .execute();
    if (updated != 1) {
      throw JooqAccountRepositorySupport.staleWrite("external_account", entity.getId());
    }
    return entity;
  }

  public void deleteByAccountId(Long accountId) {
    dsl.deleteFrom(EXTERNAL_ACCOUNT).where(EXTERNAL_ACCOUNT.ACCOUNT_ID.eq(accountId)).execute();
  }

  private org.jooq.SelectOnConditionStep<? extends Record> baseSelect() {
    return dsl.select(
            EXTERNAL_ACCOUNT.ID,
            EXTERNAL_ACCOUNT.ACCOUNT_ID,
            EXTERNAL_ACCOUNT.TENANT_ID,
            EXTERNAL_ACCOUNT.PROVIDER,
            EXTERNAL_ACCOUNT.EXTERNAL_ID,
            ACCOUNTS.ID,
            ACCOUNTS.USERNAME,
            ACCOUNTS.EMAIL,
            ACCOUNTS.PASSWORD_HASH,
            ACCOUNTS.ROLE,
            ACCOUNTS.EMAIL_VERIFIED,
            ACCOUNTS.LOGIN_AUTH_MODES)
        .from(EXTERNAL_ACCOUNT)
        .join(ACCOUNTS)
        .on(EXTERNAL_ACCOUNT.ACCOUNT_ID.eq(ACCOUNTS.ID));
  }

  private ExternalAccount toEntity(Record record) {
    ExternalAccount entity = new ExternalAccount();
    entity.setId(record.get(EXTERNAL_ACCOUNT.ID));
    entity.setAccount(
        JooqAccountRepositorySupport.partialAccount(
            record.get(ACCOUNTS.ID),
            record.get(ACCOUNTS.USERNAME),
            record.get(ACCOUNTS.EMAIL),
            record.get(ACCOUNTS.PASSWORD_HASH),
            record.get(ACCOUNTS.ROLE),
            record.get(ACCOUNTS.EMAIL_VERIFIED),
            record.get(ACCOUNTS.LOGIN_AUTH_MODES)));
    entity.setTenantId(record.get(EXTERNAL_ACCOUNT.TENANT_ID));
    entity.setProvider(record.get(EXTERNAL_ACCOUNT.PROVIDER));
    entity.setExternalId(record.get(EXTERNAL_ACCOUNT.EXTERNAL_ID));
    return entity;
  }
}
