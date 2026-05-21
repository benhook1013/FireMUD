package net.firedevops.firemud.accountservice.repository;

import static net.firedevops.firemud.accountservice.jooq.Tables.ACCOUNTS;
import static net.firedevops.firemud.accountservice.jooq.Tables.ACCOUNT_TENANT_MEMBERSHIP;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.accountservice.entity.AccountTenantMembership;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class AccountTenantMembershipRepository {
  private final DSLContext dsl;

  public AccountTenantMembershipRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Optional<AccountTenantMembership> findByAccountIdAndTenantId(
      Long accountId, Long tenantId) {
    return Optional.ofNullable(
        baseSelect()
            .where(
                ACCOUNT_TENANT_MEMBERSHIP
                    .ACCOUNT_ID
                    .eq(accountId)
                    .and(ACCOUNT_TENANT_MEMBERSHIP.TENANT_ID.eq(tenantId)))
            .fetchOne(this::toEntity));
  }

  public boolean existsByAccountIdAndTenantId(Long accountId, Long tenantId) {
    return dsl.fetchExists(
        ACCOUNT_TENANT_MEMBERSHIP,
        ACCOUNT_TENANT_MEMBERSHIP
            .ACCOUNT_ID
            .eq(accountId)
            .and(ACCOUNT_TENANT_MEMBERSHIP.TENANT_ID.eq(tenantId)));
  }

  public boolean existsByAccountId(Long accountId) {
    return dsl.fetchExists(
        ACCOUNT_TENANT_MEMBERSHIP, ACCOUNT_TENANT_MEMBERSHIP.ACCOUNT_ID.eq(accountId));
  }

  public List<AccountTenantMembership> findByAccountId(Long accountId) {
    return baseSelect()
        .where(ACCOUNT_TENANT_MEMBERSHIP.ACCOUNT_ID.eq(accountId))
        .orderBy(ACCOUNT_TENANT_MEMBERSHIP.ID.asc())
        .fetch(this::toEntity);
  }

  public AccountTenantMembership save(AccountTenantMembership entity) {
    Long accountId = entity.getAccount() == null ? null : entity.getAccount().getId();
    if (entity.getId() == null) {
      Long id =
          dsl.insertInto(ACCOUNT_TENANT_MEMBERSHIP)
              .set(ACCOUNT_TENANT_MEMBERSHIP.ACCOUNT_ID, accountId)
              .set(ACCOUNT_TENANT_MEMBERSHIP.TENANT_ID, entity.getTenantId())
              .set(
                  ACCOUNT_TENANT_MEMBERSHIP.GAMEPLAY_ADMISSION_ALLOWED,
                  entity.isGameplayAdmissionAllowed())
              .returningResult(ACCOUNT_TENANT_MEMBERSHIP.ID)
              .fetchOne(ACCOUNT_TENANT_MEMBERSHIP.ID);
      entity.setId(id);
      return entity;
    }
    int updated =
        dsl.update(ACCOUNT_TENANT_MEMBERSHIP)
            .set(ACCOUNT_TENANT_MEMBERSHIP.ACCOUNT_ID, accountId)
            .set(ACCOUNT_TENANT_MEMBERSHIP.TENANT_ID, entity.getTenantId())
            .set(
                ACCOUNT_TENANT_MEMBERSHIP.GAMEPLAY_ADMISSION_ALLOWED,
                entity.isGameplayAdmissionAllowed())
            .where(ACCOUNT_TENANT_MEMBERSHIP.ID.eq(entity.getId()))
            .execute();
    if (updated != 1) {
      throw JooqAccountRepositorySupport.staleWrite("account_tenant_membership", entity.getId());
    }
    return entity;
  }

  public AccountTenantMembership saveAndFlush(AccountTenantMembership entity) {
    return save(entity);
  }

  public void delete(AccountTenantMembership entity) {
    if (entity != null && entity.getId() != null) {
      dsl.deleteFrom(ACCOUNT_TENANT_MEMBERSHIP)
          .where(ACCOUNT_TENANT_MEMBERSHIP.ID.eq(entity.getId()))
          .execute();
    }
  }

  public void deleteByAccountIdAndTenantId(Long accountId, Long tenantId) {
    dsl.deleteFrom(ACCOUNT_TENANT_MEMBERSHIP)
        .where(
            ACCOUNT_TENANT_MEMBERSHIP
                .ACCOUNT_ID
                .eq(accountId)
                .and(ACCOUNT_TENANT_MEMBERSHIP.TENANT_ID.eq(tenantId)))
        .execute();
  }

  public void deleteByAccountId(Long accountId) {
    dsl.deleteFrom(ACCOUNT_TENANT_MEMBERSHIP)
        .where(ACCOUNT_TENANT_MEMBERSHIP.ACCOUNT_ID.eq(accountId))
        .execute();
  }

  private org.jooq.SelectOnConditionStep<? extends Record> baseSelect() {
    return dsl.select(
            ACCOUNT_TENANT_MEMBERSHIP.ID,
            ACCOUNT_TENANT_MEMBERSHIP.ACCOUNT_ID,
            ACCOUNT_TENANT_MEMBERSHIP.TENANT_ID,
            ACCOUNT_TENANT_MEMBERSHIP.GAMEPLAY_ADMISSION_ALLOWED,
            ACCOUNTS.ID,
            ACCOUNTS.USERNAME,
            ACCOUNTS.EMAIL,
            ACCOUNTS.PASSWORD_HASH,
            ACCOUNTS.ROLE,
            ACCOUNTS.TWO_FACTOR_SECRET,
            ACCOUNTS.EMAIL_VERIFIED)
        .from(ACCOUNT_TENANT_MEMBERSHIP)
        .join(ACCOUNTS)
        .on(ACCOUNT_TENANT_MEMBERSHIP.ACCOUNT_ID.eq(ACCOUNTS.ID));
  }

  private AccountTenantMembership toEntity(Record record) {
    AccountTenantMembership membership = new AccountTenantMembership();
    membership.setId(record.get(ACCOUNT_TENANT_MEMBERSHIP.ID));
    membership.setAccount(
        JooqAccountRepositorySupport.partialAccount(
            record.get(ACCOUNTS.ID),
            record.get(ACCOUNTS.USERNAME),
            record.get(ACCOUNTS.EMAIL),
            record.get(ACCOUNTS.PASSWORD_HASH),
            record.get(ACCOUNTS.ROLE),
            record.get(ACCOUNTS.TWO_FACTOR_SECRET),
            record.get(ACCOUNTS.EMAIL_VERIFIED)));
    membership.setTenantId(record.get(ACCOUNT_TENANT_MEMBERSHIP.TENANT_ID));
    membership.setGameplayAdmissionAllowed(
        Boolean.TRUE.equals(record.get(ACCOUNT_TENANT_MEMBERSHIP.GAMEPLAY_ADMISSION_ALLOWED)));
    return membership;
  }
}
