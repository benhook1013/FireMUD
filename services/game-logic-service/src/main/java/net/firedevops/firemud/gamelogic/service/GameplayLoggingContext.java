package net.firedevops.firemud.gamelogic.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.firedevops.firemud.gamelogic.v1.LookRequest;
import net.firedevops.firemud.gamelogic.v1.MoveRequest;
import net.firedevops.firemud.gamelogic.v1.SendCommunicationRequest;
import org.slf4j.MDC;

/** Attaches gameplay identity fields to MDC where Game Logic requests already know them. */
public final class GameplayLoggingContext implements AutoCloseable {
  private final List<AutoCloseable> closeables;

  private GameplayLoggingContext(List<AutoCloseable> closeables) {
    this.closeables = closeables;
  }

  public static GameplayLoggingContext from(LookRequest request) {
    return open(
        tenantId(
            request.getTenantId(),
            request.hasRoomInstance() ? request.getRoomInstance().getTenantId() : null),
        request.hasRoomInstance()
            ? blankToNull(request.getRoomInstance().getGameInstanceId())
            : null,
        blankToNull(request.getCharacterId()),
        null);
  }

  public static GameplayLoggingContext from(MoveRequest request) {
    return open(
        tenantId(
            request.getTenantId(),
            request.hasRoomInstance() ? request.getRoomInstance().getTenantId() : null),
        request.hasRoomInstance()
            ? blankToNull(request.getRoomInstance().getGameInstanceId())
            : null,
        blankToNull(request.getCharacterId()),
        null);
  }

  public static GameplayLoggingContext from(SendCommunicationRequest request) {
    return open(
        tenantId(
            request.getTenantId(),
            request.hasRoomInstance() ? request.getRoomInstance().getTenantId() : null),
        blankToNull(request.getGameInstanceId()),
        blankToNull(request.getCharacterId()),
        null);
  }

  public static GameplayLoggingContext open(
      String tenantId, String gameInstanceId, String characterId, String regionId) {
    List<AutoCloseable> closeables = new ArrayList<>(4);
    put(closeables, "tenantId", tenantId);
    put(closeables, "gameInstanceId", gameInstanceId);
    put(closeables, "characterId", characterId);
    put(closeables, "regionId", regionId);
    return new GameplayLoggingContext(closeables);
  }

  private static String tenantId(String requestTenantId, String roomTenantId) {
    return blankToNull(roomTenantId) != null ? roomTenantId : blankToNull(requestTenantId);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static void put(List<AutoCloseable> closeables, String key, String value) {
    if (value != null) {
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
        // MDC cleanup should never interfere with request handling.
      }
    }
  }
}
