package net.firedevops.firemud.command.text;

/** Shared helpers for the minimal LOOK command response. */
public final class LookCommandConstants {
  public static final String ROOM_DESCRIPTION =
      "You are in a candle-lit antechamber carved into basalt.\nExits: NORTH EAST";

  public static final String LOOK_RESPONSE = "OK LOOK\n" + ROOM_DESCRIPTION + "\n\n";

  private LookCommandConstants() {}
}
