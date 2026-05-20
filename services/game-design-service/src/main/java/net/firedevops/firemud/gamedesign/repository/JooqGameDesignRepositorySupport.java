package net.firedevops.firemud.gamedesign.repository;

import org.jooq.JSONB;

final class JooqGameDesignRepositorySupport {
  private JooqGameDesignRepositorySupport() {}

  static JSONB jsonbParam(String json) {
    return JSONB.jsonbOrNull(json);
  }

  static String nullableString(Object value) {
    if (value instanceof JSONB jsonb) {
      return jsonb.data();
    }
    return value == null ? null : value.toString();
  }
}
