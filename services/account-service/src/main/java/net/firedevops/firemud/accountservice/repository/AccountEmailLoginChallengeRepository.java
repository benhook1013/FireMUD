package net.firedevops.firemud.accountservice.repository;

import static net.firedevops.firemud.accountservice.jooq.Tables.ACCOUNT_EMAIL_LOGIN_CHALLENGE;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Optional;
import net.firedevops.firemud.accountservice.entity.AccountEmailLoginChallenge;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "DSLContext is an internal collaborator")
public class AccountEmailLoginChallengeRepository {
  private final DSLContext dsl;

  public AccountEmailLoginChallengeRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Optional<AccountEmailLoginChallenge> findByAccountId(long accountId) {
    return Optional.ofNullable(
        dsl.selectFrom(ACCOUNT_EMAIL_LOGIN_CHALLENGE)
            .where(ACCOUNT_EMAIL_LOGIN_CHALLENGE.ACCOUNT_ID.eq(accountId))
            .fetchOne(this::toEntity));
  }

  public AccountEmailLoginChallenge save(AccountEmailLoginChallenge entity) {
    if (entity.getId() == null) {
      Long id =
          dsl.insertInto(ACCOUNT_EMAIL_LOGIN_CHALLENGE)
              .set(ACCOUNT_EMAIL_LOGIN_CHALLENGE.ACCOUNT_ID, entity.getAccountId())
              .set(ACCOUNT_EMAIL_LOGIN_CHALLENGE.CODE_HASH, entity.getCodeHash())
              .set(ACCOUNT_EMAIL_LOGIN_CHALLENGE.EXPIRES_AT, entity.getExpiresAt())
              .set(ACCOUNT_EMAIL_LOGIN_CHALLENGE.RESEND_AVAILABLE_AT, entity.getResendAvailableAt())
              .set(
                  ACCOUNT_EMAIL_LOGIN_CHALLENGE.INVALID_ATTEMPT_COUNT,
                  entity.getInvalidAttemptCount())
              .set(ACCOUNT_EMAIL_LOGIN_CHALLENGE.CREATED_AT, entity.getCreatedAt())
              .set(ACCOUNT_EMAIL_LOGIN_CHALLENGE.UPDATED_AT, entity.getUpdatedAt())
              .returningResult(ACCOUNT_EMAIL_LOGIN_CHALLENGE.ID)
              .fetchOne(ACCOUNT_EMAIL_LOGIN_CHALLENGE.ID);
      entity.setId(id);
      return entity;
    }
    dsl.update(ACCOUNT_EMAIL_LOGIN_CHALLENGE)
        .set(ACCOUNT_EMAIL_LOGIN_CHALLENGE.CODE_HASH, entity.getCodeHash())
        .set(ACCOUNT_EMAIL_LOGIN_CHALLENGE.EXPIRES_AT, entity.getExpiresAt())
        .set(ACCOUNT_EMAIL_LOGIN_CHALLENGE.RESEND_AVAILABLE_AT, entity.getResendAvailableAt())
        .set(ACCOUNT_EMAIL_LOGIN_CHALLENGE.INVALID_ATTEMPT_COUNT, entity.getInvalidAttemptCount())
        .set(ACCOUNT_EMAIL_LOGIN_CHALLENGE.UPDATED_AT, entity.getUpdatedAt())
        .where(ACCOUNT_EMAIL_LOGIN_CHALLENGE.ID.eq(entity.getId()))
        .execute();
    return entity;
  }

  public void delete(AccountEmailLoginChallenge entity) {
    if (entity != null && entity.getId() != null) {
      dsl.deleteFrom(ACCOUNT_EMAIL_LOGIN_CHALLENGE)
          .where(ACCOUNT_EMAIL_LOGIN_CHALLENGE.ID.eq(entity.getId()))
          .execute();
    }
  }

  private AccountEmailLoginChallenge toEntity(
      net.firedevops.firemud.accountservice.jooq.tables.records.AccountEmailLoginChallengeRecord
          record) {
    AccountEmailLoginChallenge entity = new AccountEmailLoginChallenge();
    entity.setId(record.getId());
    entity.setAccountId(record.getAccountId());
    entity.setCodeHash(record.getCodeHash());
    entity.setExpiresAt(record.getExpiresAt());
    entity.setResendAvailableAt(record.getResendAvailableAt());
    entity.setInvalidAttemptCount(record.getInvalidAttemptCount());
    entity.setCreatedAt(record.getCreatedAt());
    entity.setUpdatedAt(record.getUpdatedAt());
    return entity;
  }
}
