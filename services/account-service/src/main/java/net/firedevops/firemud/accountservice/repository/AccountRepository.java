package net.firedevops.firemud.accountservice.repository;

import static net.firedevops.firemud.accountservice.jooq.Tables.ACCOUNTS;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.jooq.tables.records.AccountsRecord;
import net.firedevops.firemud.common.EmailCanonicalization;
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

  public Optional<Account> findByAccountUuid(UUID accountUuid) {
    Objects.requireNonNull(accountUuid, "accountUuid must not be null");
    return Optional.ofNullable(
        dsl.selectFrom(ACCOUNTS)
            .where(ACCOUNTS.ACCOUNT_UUID.eq(accountUuid))
            .fetchOne(this::toEntity));
  }

  public Optional<Account> findByUsername(String username) {
    return Optional.ofNullable(
        dsl.selectFrom(ACCOUNTS).where(ACCOUNTS.USERNAME.eq(username)).fetchOne(this::toEntity));
  }

  public Optional<Account> findByEmail(String email) {
    return Optional.ofNullable(
        dsl.selectFrom(ACCOUNTS)
            .where(ACCOUNTS.EMAIL.eq(EmailCanonicalization.normalize(email)))
            .fetchOne(this::toEntity));
  }

  public Account save(Account entity) {
    entity.setEmail(EmailCanonicalization.normalize(entity.getEmail()));
    if (entity.getId() == null) {
      UUID expectedUuid = UUID.randomUUID();
      AccountsRecord inserted =
          dsl.insertInto(ACCOUNTS)
              .set(ACCOUNTS.ACCOUNT_UUID, expectedUuid)
              .set(
                  ACCOUNTS.ACCOUNT_UUID_PROVENANCE,
                  AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT.name())
              .set(ACCOUNTS.USERNAME, entity.getUsername())
              .set(ACCOUNTS.EMAIL, entity.getEmail())
              .set(ACCOUNTS.PASSWORD_HASH, entity.getPasswordHash())
              .set(ACCOUNTS.ROLE, entity.getRole())
              .set(ACCOUNTS.EMAIL_VERIFIED, entity.isEmailVerified())
              .set(ACCOUNTS.LOGIN_AUTH_MODES, normalizedLoginAuthModes(entity))
              .set(ACCOUNTS.LIFECYCLE_STATE, entity.getLifecycleState().storageValue())
              .returning()
              .fetchOne();
      if (inserted == null) {
        throw new IllegalStateException("Account insert did not return its persisted identity");
      }
      requireExactIdentityReadback(
          inserted, expectedUuid, AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT);
      copyIdentityToEntity(entity, inserted);
      return entity;
    }
    UUID expectedUuid = entity.getAccountUuid();
    AccountIdentityProvenance expectedProvenance = entity.getAccountUuidProvenance();
    Long expectedSourceNumericId = entity.getAccountUuidSourceNumericId();
    var update =
        dsl.update(ACCOUNTS)
            .set(ACCOUNTS.USERNAME, entity.getUsername())
            .set(ACCOUNTS.EMAIL, entity.getEmail())
            .set(ACCOUNTS.PASSWORD_HASH, entity.getPasswordHash())
            .set(ACCOUNTS.ROLE, entity.getRole())
            .set(ACCOUNTS.EMAIL_VERIFIED, entity.isEmailVerified())
            .set(ACCOUNTS.LOGIN_AUTH_MODES, normalizedLoginAuthModes(entity))
            .where(ACCOUNTS.ID.eq(entity.getId()));
    if (expectedUuid != null) {
      update = update.and(ACCOUNTS.ACCOUNT_UUID.eq(expectedUuid));
    }
    if (expectedProvenance != null) {
      update = update.and(ACCOUNTS.ACCOUNT_UUID_PROVENANCE.eq(expectedProvenance.name()));
    }
    if (expectedSourceNumericId != null) {
      update = update.and(ACCOUNTS.ACCOUNT_UUID_SOURCE_NUMERIC_ID.eq(expectedSourceNumericId));
    }
    AccountsRecord updated = update.returning().fetchOne();
    if (updated == null) {
      throw JooqAccountRepositorySupport.staleWrite("accounts", entity.getId());
    }
    requireExactIdentityReadback(
        updated,
        expectedUuid == null ? updated.getAccountUuid() : expectedUuid,
        expectedProvenance == null
            ? AccountIdentityProvenance.fromStorageValue(updated.getAccountUuidProvenance())
            : expectedProvenance);
    copyIdentityToEntity(entity, updated);
    return entity;
  }

  public void delete(Account entity) {
    if (entity != null && entity.getId() != null) {
      dsl.deleteFrom(ACCOUNTS).where(ACCOUNTS.ID.eq(entity.getId())).execute();
    }
  }

  private Account toEntity(AccountsRecord record) {
    Account account =
        JooqAccountRepositorySupport.partialAccount(
            record.getId(),
            record.getUsername(),
            record.getEmail(),
            record.getPasswordHash(),
            record.getRole(),
            record.getEmailVerified(),
            record.getLoginAuthModes(),
            record.getLifecycleState());
    if (account != null) {
      account.setAccountUuid(record.getAccountUuid());
      account.setAccountUuidProvenance(
          AccountIdentityProvenance.fromStorageValue(record.getAccountUuidProvenance()));
      account.setAccountUuidSourceNumericId(record.getAccountUuidSourceNumericId());
      requireExactIdentityReadback(
          record, record.getAccountUuid(), account.getAccountUuidProvenance());
    }
    return account;
  }

  private void requireExactIdentityReadback(
      AccountsRecord record, UUID expectedUuid, AccountIdentityProvenance expectedProvenance) {
    if (record.getId() == null
        || expectedUuid == null
        || expectedProvenance == null
        || !Objects.equals(record.getAccountUuid(), expectedUuid)
        || !Objects.equals(record.getAccountUuidProvenance(), expectedProvenance.name())
        || !Objects.equals(record.getAccountUuidSourceNumericId(), record.getId())) {
      throw new IllegalStateException(
          "Account UUID readback did not match its exact persisted source row");
    }
  }

  private void copyIdentityToEntity(Account entity, AccountsRecord record) {
    entity.setId(record.getId());
    entity.setAccountUuid(record.getAccountUuid());
    entity.setAccountUuidProvenance(
        AccountIdentityProvenance.fromStorageValue(record.getAccountUuidProvenance()));
    entity.setAccountUuidSourceNumericId(record.getAccountUuidSourceNumericId());
  }

  private String normalizedLoginAuthModes(Account entity) {
    return net.firedevops.firemud.accountservice.entity.AccountLoginAuthModes.normalize(
        entity.getLoginAuthModes());
  }
}
