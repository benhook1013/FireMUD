package net.firedevops.firemud.accountservice.repository;

import static net.firedevops.firemud.accountservice.jooq.Tables.ACCOUNTS;
import static net.firedevops.firemud.accountservice.jooq.Tables.PASSWORD_RESET_TOKEN;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.LocalDateTime;
import java.util.Optional;
import net.firedevops.firemud.accountservice.entity.PasswordResetToken;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class PasswordResetTokenRepository {
  private final DSLContext dsl;

  public PasswordResetTokenRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Optional<PasswordResetToken> findByToken(String token) {
    return Optional.ofNullable(
        baseSelect().where(PASSWORD_RESET_TOKEN.TOKEN.eq(token)).fetchOne(this::toEntity));
  }

  public PasswordResetToken save(PasswordResetToken entity) {
    Long accountId = entity.getAccount() == null ? null : entity.getAccount().getId();
    if (entity.getId() == null) {
      Long id =
          dsl.insertInto(PASSWORD_RESET_TOKEN)
              .set(PASSWORD_RESET_TOKEN.ACCOUNT_ID, accountId)
              .set(PASSWORD_RESET_TOKEN.TOKEN, entity.getToken())
              .set(PASSWORD_RESET_TOKEN.EXPIRES_AT, entity.getExpiresAt())
              .returningResult(PASSWORD_RESET_TOKEN.ID)
              .fetchOne(PASSWORD_RESET_TOKEN.ID);
      entity.setId(id);
      return entity;
    }
    int updated =
        dsl.update(PASSWORD_RESET_TOKEN)
            .set(PASSWORD_RESET_TOKEN.ACCOUNT_ID, accountId)
            .set(PASSWORD_RESET_TOKEN.TOKEN, entity.getToken())
            .set(PASSWORD_RESET_TOKEN.EXPIRES_AT, entity.getExpiresAt())
            .where(PASSWORD_RESET_TOKEN.ID.eq(entity.getId()))
            .execute();
    if (updated != 1) {
      throw JooqAccountRepositorySupport.staleWrite("password_reset_token", entity.getId());
    }
    return entity;
  }

  public void delete(PasswordResetToken entity) {
    if (entity != null && entity.getId() != null) {
      dsl.deleteFrom(PASSWORD_RESET_TOKEN)
          .where(PASSWORD_RESET_TOKEN.ID.eq(entity.getId()))
          .execute();
    }
  }

  public void deleteByAccountId(Long accountId) {
    dsl.deleteFrom(PASSWORD_RESET_TOKEN)
        .where(PASSWORD_RESET_TOKEN.ACCOUNT_ID.eq(accountId))
        .execute();
  }

  /** Deletes at most {@code batchSize} expired rows in deterministic expiry/id order. */
  public int deleteExpired(LocalDateTime capturedNow, int batchSize) {
    validateCleanupArguments(capturedNow, batchSize);
    return dsl.deleteFrom(PASSWORD_RESET_TOKEN)
        .where(
            PASSWORD_RESET_TOKEN.ID.in(
                dsl.select(PASSWORD_RESET_TOKEN.ID)
                    .from(PASSWORD_RESET_TOKEN)
                    .where(PASSWORD_RESET_TOKEN.EXPIRES_AT.lt(capturedNow))
                    .orderBy(PASSWORD_RESET_TOKEN.EXPIRES_AT.asc(), PASSWORD_RESET_TOKEN.ID.asc())
                    .limit(batchSize)))
        .execute();
  }

  public Optional<LocalDateTime> findOldestExpiredAt(LocalDateTime capturedNow) {
    if (capturedNow == null) {
      throw new IllegalArgumentException("capturedNow must not be null");
    }
    return Optional.ofNullable(
        dsl.select(PASSWORD_RESET_TOKEN.EXPIRES_AT)
            .from(PASSWORD_RESET_TOKEN)
            .where(PASSWORD_RESET_TOKEN.EXPIRES_AT.lt(capturedNow))
            .orderBy(PASSWORD_RESET_TOKEN.EXPIRES_AT.asc(), PASSWORD_RESET_TOKEN.ID.asc())
            .limit(1)
            .fetchOne(PASSWORD_RESET_TOKEN.EXPIRES_AT));
  }

  private static void validateCleanupArguments(LocalDateTime capturedNow, int batchSize) {
    if (capturedNow == null) {
      throw new IllegalArgumentException("capturedNow must not be null");
    }
    if (batchSize <= 0) {
      throw new IllegalArgumentException("batchSize must be positive");
    }
  }

  private org.jooq.SelectOnConditionStep<? extends Record> baseSelect() {
    return dsl.select(
            PASSWORD_RESET_TOKEN.ID,
            PASSWORD_RESET_TOKEN.ACCOUNT_ID,
            PASSWORD_RESET_TOKEN.TOKEN,
            PASSWORD_RESET_TOKEN.EXPIRES_AT,
            ACCOUNTS.ID,
            ACCOUNTS.USERNAME,
            ACCOUNTS.EMAIL,
            ACCOUNTS.PASSWORD_HASH,
            ACCOUNTS.ROLE,
            ACCOUNTS.EMAIL_VERIFIED,
            ACCOUNTS.LOGIN_AUTH_MODES)
        .from(PASSWORD_RESET_TOKEN)
        .join(ACCOUNTS)
        .on(PASSWORD_RESET_TOKEN.ACCOUNT_ID.eq(ACCOUNTS.ID));
  }

  private PasswordResetToken toEntity(Record record) {
    PasswordResetToken token = new PasswordResetToken();
    token.setId(record.get(PASSWORD_RESET_TOKEN.ID));
    token.setAccount(
        JooqAccountRepositorySupport.partialAccount(
            record.get(ACCOUNTS.ID),
            record.get(ACCOUNTS.USERNAME),
            record.get(ACCOUNTS.EMAIL),
            record.get(ACCOUNTS.PASSWORD_HASH),
            record.get(ACCOUNTS.ROLE),
            record.get(ACCOUNTS.EMAIL_VERIFIED),
            record.get(ACCOUNTS.LOGIN_AUTH_MODES)));
    token.setToken(record.get(PASSWORD_RESET_TOKEN.TOKEN));
    token.setExpiresAt(record.get(PASSWORD_RESET_TOKEN.EXPIRES_AT));
    return token;
  }
}
