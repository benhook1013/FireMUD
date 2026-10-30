package net.firedevops.firemud.socialgroups.repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport;
import net.firedevops.firemud.common.security.JwtClaims;

final class JooqSocialGroupsRepositorySupport {
  private JooqSocialGroupsRepositorySupport() {}

  static UUID toAccountUuid(String value) {
    return UUID.fromString(JwtClaims.requireCanonicalAccountUuid(value, "accountId"));
  }

  static UUID toOptionalAccountUuid(String value) {
    return value == null ? null : toAccountUuid(value);
  }

  static String fromAccountUuid(UUID value) {
    return value == null
        ? null
        : JwtClaims.requireCanonicalAccountUuid(value.toString(), "accountId");
  }

  static LocalDateTime toLocalDateTime(Instant instant) {
    return JooqPersistenceSupport.toLocalDateTime(instant);
  }

  static Instant toInstant(LocalDateTime localDateTime) {
    return JooqPersistenceSupport.toInstant(localDateTime);
  }

  static IllegalStateException staleWrite(String table, Object id) {
    return new IllegalStateException("Failed to update " + table + " id=" + id);
  }
}
