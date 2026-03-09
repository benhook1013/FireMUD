package net.firedevops.firemud.gamelogic.logic.event;

/** Listener for events produced by the game logic service. */
public interface GameEventListener {
  void onEvent(GameEvent event);
}
