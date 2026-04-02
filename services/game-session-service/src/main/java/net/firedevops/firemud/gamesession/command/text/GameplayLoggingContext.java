package net.firedevops.firemud.gamesession.command.text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.slf4j.MDC;

/** Attaches gameplay identity fields to MDC where that context is already known. */
final class GameplayLoggingContext implements AutoCloseable {
  private final List<AutoCloseable> closeables;

  private GameplayLoggingContext(List<AutoCloseable> closeables) {
    this.closeables = closeables;
  }

  static GameplayLoggingContext from(SessionContext context) {
    return open(
        Long.toString(context.tenantId()),
        context.gameInstanceId() > 0 ? Long.toString(context.gameInstanceId()) : null,
        context.characterId() > 0 ? Long.toString(context.characterId()) : null,
        null);
  }

  static GameplayLoggingContext open(
      String tenantId, String gameInstanceId, String characterId, String regionId) {
    List<AutoCloseable> closeables = new ArrayList<>(4);
    put(closeables, "tenantId", tenantId);
    put(closeables, "gameInstanceId", gameInstanceId);
    put(closeables, "characterId", characterId);
    put(closeables, "regionId", regionId);
    return new GameplayLoggingContext(closeables);
  }

  private static void put(List<AutoCloseable> closeables, String key, String value) {
    if (value != null && !value.isBlank()) {
      closeables.add(MDC.putCloseable(key, value));
    }
  }

  @Override
  public void close() {
    List<AutoCloseable> reversed = new ArrayList<>(closeables);
    Collections.reverse(reversed);
    for (AutoCloseable closeable : reversed) {
      try {
        closeable.close();
      } catch (Exception ignored) {
        // MDC cleanup should not affect gameplay flow.
      }
    }
  }
}
