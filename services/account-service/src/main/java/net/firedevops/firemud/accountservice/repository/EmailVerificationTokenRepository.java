package net.firedevops.firemud.accountservice.repository;

import static net.firedevops.firemud.accountservice.jooq.Tables.ACCOUNTS;
import static net.firedevops.firemud.accountservice.jooq.Tables.EMAIL_VERIFICATION_TOKEN;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Optional;
import net.firedevops.firemud.accountservice.entity.EmailVerificationToken;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class EmailVerificationTokenRepository {
  private final DSLContext dsl;

  public EmailVerificationTokenRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Optional<EmailVerificationToken> findByToken(String token) {
    return Optional.ofNullable(
        baseSelect().where(EMAIL_VERIFICATION_TOKEN.TOKEN.eq(token)).fetchOne(this::toEntity));
  }

  public EmailVerificationToken save(EmailVerificationToken entity) {
    Long accountId = entity.getAccount() == null ? null : entity.getAccount().getId();
    if (entity.getId() == null) {
      Long id =
          dsl.insertInto(EMAIL_VERIFICATION_TOKEN)
              .set(EMAIL_VERIFICATION_TOKEN.ACCOUNT_ID, accountId)
              .set(EMAIL_VERIFICATION_TOKEN.TOKEN, entity.getToken())
              .set(EMAIL_VERIFICATION_TOKEN.EXPIRES_AT, entity.getExpiresAt())
              .returningResult(EMAIL_VERIFICATION_TOKEN.ID)
              .fetchOne(EMAIL_VERIFICATION_TOKEN.ID);
      entity.setId(id);
      return entity;
    }
    int updated =
        dsl.update(EMAIL_VERIFICATION_TOKEN)
            .set(EMAIL_VERIFICATION_TOKEN.ACCOUNT_ID, accountId)
            .set(EMAIL_VERIFICATION_TOKEN.TOKEN, entity.getToken())
            .set(EMAIL_VERIFICATION_TOKEN.EXPIRES_AT, entity.getExpiresAt())
            .where(EMAIL_VERIFICATION_TOKEN.ID.eq(entity.getId()))
            .execute();
    if (updated != 1) {
      throw JooqAccountRepositorySupport.staleWrite("email_verification_token", entity.getId());
    }
    return entity;
  }

  public void delete(EmailVerificationToken entity) {
    if (entity != null && entity.getId() != null) {
      dsl.deleteFrom(EMAIL_VERIFICATION_TOKEN)
          .where(EMAIL_VERIFICATION_TOKEN.ID.eq(entity.getId()))
          .execute();
    }
  }

  public void deleteByAccountId(Long accountId) {
    dsl.deleteFrom(EMAIL_VERIFICATION_TOKEN)
        .where(EMAIL_VERIFICATION_TOKEN.ACCOUNT_ID.eq(accountId))
        .execute();
  }

  private org.jooq.SelectOnConditionStep<? extends Record> baseSelect() {
    return dsl.select(
            EMAIL_VERIFICATION_TOKEN.ID,
            EMAIL_VERIFICATION_TOKEN.ACCOUNT_ID,
            EMAIL_VERIFICATION_TOKEN.TOKEN,
            EMAIL_VERIFICATION_TOKEN.EXPIRES_AT,
            ACCOUNTS.ID,
            ACCOUNTS.USERNAME,
            ACCOUNTS.EMAIL,
            ACCOUNTS.PASSWORD_HASH,
            ACCOUNTS.ROLE,
            ACCOUNTS.TWO_FACTOR_SECRET,
            ACCOUNTS.EMAIL_VERIFIED,
            ACCOUNTS.LOGIN_AUTH_MODES)
        .from(EMAIL_VERIFICATION_TOKEN)
        .join(ACCOUNTS)
        .on(EMAIL_VERIFICATION_TOKEN.ACCOUNT_ID.eq(ACCOUNTS.ID));
  }

  private EmailVerificationToken toEntity(Record record) {
    EmailVerificationToken token = new EmailVerificationToken();
    token.setId(record.get(EMAIL_VERIFICATION_TOKEN.ID));
    token.setAccount(
        JooqAccountRepositorySupport.partialAccount(
            record.get(ACCOUNTS.ID),
            record.get(ACCOUNTS.USERNAME),
            record.get(ACCOUNTS.EMAIL),
            record.get(ACCOUNTS.PASSWORD_HASH),
            record.get(ACCOUNTS.ROLE),
            record.get(ACCOUNTS.TWO_FACTOR_SECRET),
            record.get(ACCOUNTS.EMAIL_VERIFIED),
            record.get(ACCOUNTS.LOGIN_AUTH_MODES)));
    token.setToken(record.get(EMAIL_VERIFICATION_TOKEN.TOKEN));
    token.setExpiresAt(record.get(EMAIL_VERIFICATION_TOKEN.EXPIRES_AT));
    return token;
  }
}
