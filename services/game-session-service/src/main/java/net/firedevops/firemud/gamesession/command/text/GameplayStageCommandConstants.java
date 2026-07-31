package net.firedevops.firemud.gamesession.command.text;

import net.firedevops.firemud.account.AuthenticationErrorCodes;

/** Stage-aware text command error codes/messages for login and gameplay admission. */
public final class GameplayStageCommandConstants {
  public static final String LOGIN_REQUIRED_CODE = "LOGIN_REQUIRED";
  public static final String LOGIN_REQUIRED_MESSAGE =
      "You must LOGIN first. Use LOGIN <email> [secret].";

  public static final String PLAY_REQUIRED_CODE = "PLAY_REQUIRED";
  public static final String PLAY_REQUIRED_MESSAGE =
      "You must PLAY first. Use PLAY <world> [realm] [character].";

  public static final String PLAY_INVALID_ARGUMENT_CODE = "INVALID_ARGUMENT";
  public static final String PLAY_INVALID_ARGUMENT_MESSAGE =
      "PLAY command requires a world selector.";
  public static final String PLAY_SELECTION_REQUIRED_CODE = "PLAY_SELECTION_REQUIRED";
  public static final String PLAY_SELECTION_REQUIRED_MESSAGE =
      "Use WORLDS to browse available worlds, then PLAY <world> [realm] [character].";
  public static final String WORLD_ACCESS_DENIED_CODE = "WORLD_ACCESS_DENIED";
  public static final String WORLD_ACCESS_DENIED_MESSAGE =
      "You are not allowed to enter that world.";
  public static final String TENANT_BILLING_BLOCKED_CODE = "TENANT_BILLING_BLOCKED";
  public static final String TENANT_BILLING_BLOCKED_MESSAGE =
      "That world is temporarily unavailable for gameplay.";
  public static final String AUTH_UNAVAILABLE_CODE = AuthenticationErrorCodes.UNAVAILABLE;
  public static final String AUTH_UNAVAILABLE_MESSAGE =
      "Account authority is temporarily unavailable. Retry PLAY shortly.";
  public static final String ENTITLEMENT_UNAVAILABLE_CODE = "ENTITLEMENT_UNAVAILABLE";
  public static final String ENTITLEMENT_UNAVAILABLE_MESSAGE =
      "Entitlement state is temporarily unavailable. Retry PLAY shortly.";
  public static final String PLAY_IDENTITY_UNAVAILABLE_CODE = "PLAY_IDENTITY_UNAVAILABLE";
  public static final String PLAY_IDENTITY_UNAVAILABLE_MESSAGE =
      "Character identity is temporarily unavailable. Retry PLAY shortly.";

  private GameplayStageCommandConstants() {
    // constants only
  }
}
