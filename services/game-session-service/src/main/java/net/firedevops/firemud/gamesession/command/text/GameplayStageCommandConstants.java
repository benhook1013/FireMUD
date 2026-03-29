package net.firedevops.firemud.gamesession.command.text;

/** Stage-aware text command error codes/messages for login and gameplay admission. */
public final class GameplayStageCommandConstants {
  public static final String LOGIN_REQUIRED_CODE = "LOGIN_REQUIRED";
  public static final String LOGIN_REQUIRED_MESSAGE =
      "Use LOGIN <username> <password> to continue.";

  public static final String PLAY_REQUIRED_CODE = "PLAY_REQUIRED";
  public static final String PLAY_REQUIRED_MESSAGE = "Use PLAY <world> [character] to enter.";

  public static final String PLAY_INVALID_ARGUMENT_CODE = "INVALID_ARGUMENT";
  public static final String PLAY_INVALID_ARGUMENT_MESSAGE =
      "PLAY command requires a world selector.";
  public static final String PLAY_SELECTION_REQUIRED_CODE = "PLAY_SELECTION_REQUIRED";
  public static final String PLAY_SELECTION_REQUIRED_MESSAGE =
      "Use WORLDS to browse available worlds, then PLAY <world> [character].";

  private GameplayStageCommandConstants() {
    // constants only
  }
}
