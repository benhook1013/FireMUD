package net.firedevops.firemud.accountservice.repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport;

final class JooqAccountRepositorySupport {
  private JooqAccountRepositorySupport() {}

  static LocalDateTime toLocalDateTime(Instant instant) {
    return JooqPersistenceSupport.toLocalDateTime(instant);
  }

  static Instant toInstant(LocalDateTime localDateTime) {
    return JooqPersistenceSupport.toInstant(localDateTime);
  }

  static OffsetDateTime toOffsetDateTime(Instant instant) {
    return JooqPersistenceSupport.toOffsetDateTime(instant);
  }

  static Instant toInstant(OffsetDateTime offsetDateTime) {
    return JooqPersistenceSupport.toInstant(offsetDateTime);
  }

  static Account partialAccount(
      Long id,
      String username,
      String email,
      String passwordHash,
      String role,
      String twoFactorSecret,
      Boolean emailVerified) {
    if (id == null) {
      return null;
    }
    Account account = new Account();
    account.setId(id);
    account.setUsername(username);
    account.setEmail(email);
    account.setPasswordHash(passwordHash);
    account.setRole(role);
    account.setTwoFactorSecret(twoFactorSecret);
    account.setEmailVerified(Boolean.TRUE.equals(emailVerified));
    return account;
  }

  static IllegalStateException staleWrite(String table, Object id) {
    return new IllegalStateException("Failed to update " + table + " id=" + id);
  }
}
