package net.firedevops.firemud.accountservice.repository;

import static net.firedevops.firemud.accountservice.jooq.Tables.ACCOUNTS;
import static net.firedevops.firemud.accountservice.jooq.Tables.CURRENCY_BALANCE;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Optional;
import net.firedevops.firemud.accountservice.entity.CurrencyBalance;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class CurrencyBalanceRepository {
  private final DSLContext dsl;

  public CurrencyBalanceRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Optional<CurrencyBalance> findByTenantIdAndAccountIdAndCurrencyCode(
      Long tenantId, Long accountId, String currencyCode) {
    return Optional.ofNullable(
        baseSelect()
            .where(
                CURRENCY_BALANCE
                    .TENANT_ID
                    .eq(tenantId)
                    .and(CURRENCY_BALANCE.ACCOUNT_ID.eq(accountId))
                    .and(CURRENCY_BALANCE.CURRENCY_CODE.eq(currencyCode)))
            .fetchOne(this::toEntity));
  }

  public CurrencyBalance save(CurrencyBalance entity) {
    Long accountId = entity.getAccount() == null ? null : entity.getAccount().getId();
    if (entity.getId() == null) {
      Long id =
          dsl.insertInto(CURRENCY_BALANCE)
              .set(CURRENCY_BALANCE.ACCOUNT_ID, accountId)
              .set(CURRENCY_BALANCE.CURRENCY_CODE, entity.getCurrencyCode())
              .set(CURRENCY_BALANCE.BALANCE, entity.getBalance())
              .set(CURRENCY_BALANCE.TENANT_ID, entity.getTenantId())
              .returningResult(CURRENCY_BALANCE.ID)
              .fetchOne(CURRENCY_BALANCE.ID);
      entity.setId(id);
      return entity;
    }
    int updated =
        dsl.update(CURRENCY_BALANCE)
            .set(CURRENCY_BALANCE.ACCOUNT_ID, accountId)
            .set(CURRENCY_BALANCE.CURRENCY_CODE, entity.getCurrencyCode())
            .set(CURRENCY_BALANCE.BALANCE, entity.getBalance())
            .set(CURRENCY_BALANCE.TENANT_ID, entity.getTenantId())
            .where(CURRENCY_BALANCE.ID.eq(entity.getId()))
            .execute();
    if (updated != 1) {
      throw JooqAccountRepositorySupport.staleWrite("currency_balance", entity.getId());
    }
    return entity;
  }

  private org.jooq.SelectOnConditionStep<? extends Record> baseSelect() {
    return dsl.select(
            CURRENCY_BALANCE.ID,
            CURRENCY_BALANCE.ACCOUNT_ID,
            CURRENCY_BALANCE.CURRENCY_CODE,
            CURRENCY_BALANCE.BALANCE,
            CURRENCY_BALANCE.TENANT_ID,
            ACCOUNTS.ID,
            ACCOUNTS.USERNAME,
            ACCOUNTS.EMAIL,
            ACCOUNTS.PASSWORD_HASH,
            ACCOUNTS.ROLE,
            ACCOUNTS.EMAIL_VERIFIED,
            ACCOUNTS.LOGIN_AUTH_MODES)
        .from(CURRENCY_BALANCE)
        .join(ACCOUNTS)
        .on(CURRENCY_BALANCE.ACCOUNT_ID.eq(ACCOUNTS.ID));
  }

  private CurrencyBalance toEntity(Record record) {
    CurrencyBalance balance = new CurrencyBalance();
    balance.setId(record.get(CURRENCY_BALANCE.ID));
    balance.setAccount(
        JooqAccountRepositorySupport.partialAccount(
            record.get(ACCOUNTS.ID),
            record.get(ACCOUNTS.USERNAME),
            record.get(ACCOUNTS.EMAIL),
            record.get(ACCOUNTS.PASSWORD_HASH),
            record.get(ACCOUNTS.ROLE),
            record.get(ACCOUNTS.EMAIL_VERIFIED),
            record.get(ACCOUNTS.LOGIN_AUTH_MODES)));
    balance.setCurrencyCode(record.get(CURRENCY_BALANCE.CURRENCY_CODE));
    balance.setBalance(record.get(CURRENCY_BALANCE.BALANCE));
    balance.setTenantId(record.get(CURRENCY_BALANCE.TENANT_ID));
    return balance;
  }
}
