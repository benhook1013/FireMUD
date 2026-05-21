package net.firedevops.firemud.accountservice.repository;

import static net.firedevops.firemud.accountservice.jooq.Tables.ACCOUNTS;
import static net.firedevops.firemud.accountservice.jooq.Tables.PAYMENT_TRANSACTION;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Optional;
import net.firedevops.firemud.accountservice.entity.PaymentTransaction;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class PaymentTransactionRepository {
  private final DSLContext dsl;

  public PaymentTransactionRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Optional<PaymentTransaction> findById(Long id) {
    return Optional.ofNullable(
        baseSelect().where(PAYMENT_TRANSACTION.ID.eq(id)).fetchOne(this::toEntity));
  }

  public PaymentTransaction save(PaymentTransaction entity) {
    Long accountId = entity.getAccount() == null ? null : entity.getAccount().getId();
    if (entity.getId() == null) {
      Long id =
          dsl.insertInto(PAYMENT_TRANSACTION)
              .set(PAYMENT_TRANSACTION.ACCOUNT_ID, accountId)
              .set(PAYMENT_TRANSACTION.AMOUNT_CENTS, entity.getAmountCents())
              .set(PAYMENT_TRANSACTION.CURRENCY, entity.getCurrency())
              .set(PAYMENT_TRANSACTION.STATUS, entity.getStatus())
              .set(PAYMENT_TRANSACTION.TENANT_ID, entity.getTenantId())
              .set(PAYMENT_TRANSACTION.PROVIDER_ID, entity.getProviderId())
              .set(PAYMENT_TRANSACTION.PLATFORM_FEE_CENTS, entity.getPlatformFeeCents())
              .set(PAYMENT_TRANSACTION.DONATION, entity.isDonation())
              .set(PAYMENT_TRANSACTION.CREATOR_SHARE_CENTS, entity.getCreatorShareCents())
              .returningResult(PAYMENT_TRANSACTION.ID)
              .fetchOne(PAYMENT_TRANSACTION.ID);
      entity.setId(id);
      return entity;
    }
    int updated =
        dsl.update(PAYMENT_TRANSACTION)
            .set(PAYMENT_TRANSACTION.ACCOUNT_ID, accountId)
            .set(PAYMENT_TRANSACTION.AMOUNT_CENTS, entity.getAmountCents())
            .set(PAYMENT_TRANSACTION.CURRENCY, entity.getCurrency())
            .set(PAYMENT_TRANSACTION.STATUS, entity.getStatus())
            .set(PAYMENT_TRANSACTION.TENANT_ID, entity.getTenantId())
            .set(PAYMENT_TRANSACTION.PROVIDER_ID, entity.getProviderId())
            .set(PAYMENT_TRANSACTION.PLATFORM_FEE_CENTS, entity.getPlatformFeeCents())
            .set(PAYMENT_TRANSACTION.DONATION, entity.isDonation())
            .set(PAYMENT_TRANSACTION.CREATOR_SHARE_CENTS, entity.getCreatorShareCents())
            .where(PAYMENT_TRANSACTION.ID.eq(entity.getId()))
            .execute();
    if (updated != 1) {
      throw JooqAccountRepositorySupport.staleWrite("payment_transaction", entity.getId());
    }
    return entity;
  }

  public void deleteByAccountId(Long accountId, Long tenantId) {
    dsl.deleteFrom(PAYMENT_TRANSACTION)
        .where(
            PAYMENT_TRANSACTION
                .ACCOUNT_ID
                .eq(accountId)
                .and(PAYMENT_TRANSACTION.TENANT_ID.eq(tenantId)))
        .execute();
  }

  public void deleteByAccountId(Long accountId) {
    dsl.deleteFrom(PAYMENT_TRANSACTION)
        .where(PAYMENT_TRANSACTION.ACCOUNT_ID.eq(accountId))
        .execute();
  }

  private org.jooq.SelectOnConditionStep<? extends Record> baseSelect() {
    return dsl.select(
            PAYMENT_TRANSACTION.ID,
            PAYMENT_TRANSACTION.ACCOUNT_ID,
            PAYMENT_TRANSACTION.AMOUNT_CENTS,
            PAYMENT_TRANSACTION.CURRENCY,
            PAYMENT_TRANSACTION.STATUS,
            PAYMENT_TRANSACTION.TENANT_ID,
            PAYMENT_TRANSACTION.PROVIDER_ID,
            PAYMENT_TRANSACTION.PLATFORM_FEE_CENTS,
            PAYMENT_TRANSACTION.DONATION,
            PAYMENT_TRANSACTION.CREATOR_SHARE_CENTS,
            ACCOUNTS.ID,
            ACCOUNTS.USERNAME,
            ACCOUNTS.EMAIL,
            ACCOUNTS.PASSWORD_HASH,
            ACCOUNTS.ROLE,
            ACCOUNTS.TWO_FACTOR_SECRET,
            ACCOUNTS.EMAIL_VERIFIED)
        .from(PAYMENT_TRANSACTION)
        .join(ACCOUNTS)
        .on(PAYMENT_TRANSACTION.ACCOUNT_ID.eq(ACCOUNTS.ID));
  }

  private PaymentTransaction toEntity(Record record) {
    PaymentTransaction entity = new PaymentTransaction();
    entity.setId(record.get(PAYMENT_TRANSACTION.ID));
    entity.setAccount(
        JooqAccountRepositorySupport.partialAccount(
            record.get(ACCOUNTS.ID),
            record.get(ACCOUNTS.USERNAME),
            record.get(ACCOUNTS.EMAIL),
            record.get(ACCOUNTS.PASSWORD_HASH),
            record.get(ACCOUNTS.ROLE),
            record.get(ACCOUNTS.TWO_FACTOR_SECRET),
            record.get(ACCOUNTS.EMAIL_VERIFIED)));
    entity.setAmountCents(record.get(PAYMENT_TRANSACTION.AMOUNT_CENTS));
    entity.setCurrency(record.get(PAYMENT_TRANSACTION.CURRENCY));
    entity.setStatus(record.get(PAYMENT_TRANSACTION.STATUS));
    entity.setTenantId(record.get(PAYMENT_TRANSACTION.TENANT_ID));
    entity.setProviderId(record.get(PAYMENT_TRANSACTION.PROVIDER_ID));
    entity.setPlatformFeeCents(record.get(PAYMENT_TRANSACTION.PLATFORM_FEE_CENTS));
    entity.setDonation(Boolean.TRUE.equals(record.get(PAYMENT_TRANSACTION.DONATION)));
    entity.setCreatorShareCents(record.get(PAYMENT_TRANSACTION.CREATOR_SHARE_CENTS));
    return entity;
  }
}
