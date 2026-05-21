package net.firedevops.firemud.common.persistence.jooq;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.data.domain.Pageable;

/** Shared support helpers for FireMUD jOOQ-backed repositories. */
public final class JooqPersistenceSupport {
  private JooqPersistenceSupport() {}

  public static LocalDateTime toLocalDateTime(Instant instant) {
    return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  public static Instant toInstant(LocalDateTime localDateTime) {
    return localDateTime == null ? null : localDateTime.toInstant(ZoneOffset.UTC);
  }

  public static OffsetDateTime toOffsetDateTime(Instant instant) {
    return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
  }

  public static Instant toInstant(OffsetDateTime offsetDateTime) {
    return offsetDateTime == null ? null : offsetDateTime.toInstant();
  }

  public static int limitOrDefault(Pageable pageable, int fallback) {
    return pageable == null || pageable.isUnpaged() ? fallback : pageable.getPageSize();
  }

  public static int offsetOrZero(Pageable pageable) {
    return pageable == null || pageable.isUnpaged() ? 0 : Math.toIntExact(pageable.getOffset());
  }
}
