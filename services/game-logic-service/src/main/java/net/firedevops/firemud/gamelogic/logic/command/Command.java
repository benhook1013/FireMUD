package net.firedevops.firemud.gamelogic.logic.command;

/** Parsed command structure. */
public record Command(ActionType actionType, String target, String raw, boolean requiresSoloTick) {
  public Command(ActionType actionType, String target, String raw) {
    this(actionType, target, raw, false);
  }
}
