package net.firedevops.firemud.socialgroups.repository;

import java.time.Instant;
import java.time.LocalDateTime;
import net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport;

final class JooqSocialGroupsRepositorySupport {
  private JooqSocialGroupsRepositorySupport() {}

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
