package net.firedevops.firemud.gamesession.repository;

import java.util.List;
import java.util.function.Supplier;
import org.jooq.Condition;

final class JooqGameSessionRepositorySupport {
  private JooqGameSessionRepositorySupport() {}

  static void addIfNotBlank(
      List<Condition> conditions, String value, Supplier<Condition> conditionSupplier) {
    if (value != null && !value.isBlank()) {
      conditions.add(conditionSupplier.get());
    }
  }

  static void addIfPositive(
      List<Condition> conditions, long value, Supplier<Condition> conditionSupplier) {
    if (value > 0) {
      conditions.add(conditionSupplier.get());
    }
  }

  static <T> void addIfNonNull(
      List<Condition> conditions, T value, Supplier<Condition> conditionSupplier) {
    if (value != null) {
      conditions.add(conditionSupplier.get());
    }
  }
}
