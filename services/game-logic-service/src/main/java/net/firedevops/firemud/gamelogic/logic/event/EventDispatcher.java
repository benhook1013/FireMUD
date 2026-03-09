package net.firedevops.firemud.gamelogic.logic.event;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Simple dispatcher for game events. */
public class EventDispatcher {
  private final List<GameEventListener> listeners = new CopyOnWriteArrayList<>();

  public void addListener(GameEventListener listener) {
    listeners.add(listener);
  }

  public void dispatch(GameEvent event) {
    for (GameEventListener listener : listeners) {
      listener.onEvent(event);
    }
  }
}
