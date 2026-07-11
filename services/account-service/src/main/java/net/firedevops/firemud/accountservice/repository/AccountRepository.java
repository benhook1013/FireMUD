package net.firedevops.firemud.accountservice.repository;

import static net.firedevops.firemud.accountservice.jooq.Tables.ACCOUNTS;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Optional;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.jooq.tables.records.AccountsRecord;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class AccountRepository {
  private final DSLContext dsl;

  public AccountRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Optional<Account> findById(Long id) {
    return Optional.ofNullable(
        dsl.selectFrom(ACCOUNTS).where(ACCOUNTS.ID.eq(id)).fetchOne(this::toEntity));
  }

  public Optional<Account> findByUsername(String username) {
    return Optional.ofNullable(
        dsl.selectFrom(ACCOUNTS).where(ACCOUNTS.USERNAME.eq(username)).fetchOne(this::toEntity));
  }

  public Optional<Account> findByEmail(String email) {
    return Optional.ofNullable(
        dsl.selectFrom(ACCOUNTS).where(ACCOUNTS.EMAIL.eq(email)).fetchOne(this::toEntity));
  }

  public Account save(Account entity) {
    if (entity.getId() == null) {
      Long id =
          dsl.insertInto(ACCOUNTS)
              .set(ACCOUNTS.USERNAME, entity.getUsername())
              .set(ACCOUNTS.EMAIL, entity.getEmail())
              .set(ACCOUNTS.PASSWORD_HASH, entity.getPasswordHash())
              .set(ACCOUNTS.ROLE, entity.getRole())
              .set(ACCOUNTS.TWO_FACTOR_SECRET, entity.getTwoFactorSecret())
              .set(ACCOUNTS.EMAIL_VERIFIED, entity.isEmailVerified())
              .set(ACCOUNTS.LOGIN_AUTH_MODES, normalizedLoginAuthModes(entity))
              .returningResult(ACCOUNTS.ID)
              .fetchOne(ACCOUNTS.ID);
      entity.setId(id);
      return entity;
    }
    int updated =
        dsl.update(ACCOUNTS)
            .set(ACCOUNTS.USERNAME, entity.getUsername())
            .set(ACCOUNTS.EMAIL, entity.getEmail())
            .set(ACCOUNTS.PASSWORD_HASH, entity.getPasswordHash())
            .set(ACCOUNTS.ROLE, entity.getRole())
            .set(ACCOUNTS.TWO_FACTOR_SECRET, entity.getTwoFactorSecret())
            .set(ACCOUNTS.EMAIL_VERIFIED, entity.isEmailVerified())
            .set(ACCOUNTS.LOGIN_AUTH_MODES, normalizedLoginAuthModes(entity))
            .where(ACCOUNTS.ID.eq(entity.getId()))
            .execute();
    if (updated != 1) {
      throw JooqAccountRepositorySupport.staleWrite("accounts", entity.getId());
    }
    return entity;
  }

  public void delete(Account entity) {
    if (entity != null && entity.getId() != null) {
      dsl.deleteFrom(ACCOUNTS).where(ACCOUNTS.ID.eq(entity.getId())).execute();
    }
  }

  private Account toEntity(AccountsRecord record) {
    return JooqAccountRepositorySupport.partialAccount(
        record.getId(),
        record.getUsername(),
        record.getEmail(),
        record.getPasswordHash(),
        record.getRole(),
        record.getTwoFactorSecret(),
        record.getEmailVerified(),
        record.getLoginAuthModes());
  }

  private String normalizedLoginAuthModes(Account entity) {
    return net.firedevops.firemud.accountservice.entity.AccountLoginAuthModes.normalize(
        entity.getLoginAuthModes());
  }
}
