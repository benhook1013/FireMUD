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
  public static final String JOIN_REQUIRED_CODE = "JOIN_REQUIRED";
  public static final String JOIN_REQUIRED_MESSAGE =
      "Membership is required before PLAY. Use JOIN <world> or choose Join & Play.";
  public static final String NON_PUBLIC_ENROLLMENT_REQUIRED_CODE = "NON_PUBLIC_ENROLLMENT_REQUIRED";
  public static final String NON_PUBLIC_ENROLLMENT_REQUIRED_MESSAGE =
      "Existing game membership is required for this non-public realm.";
  public static final String REALM_ACCESS_DENIED_CODE = "REALM_ACCESS_DENIED";
  public static final String REALM_ACCESS_DENIED_MESSAGE =
      "This non-public realm is not available to your account.";
  public static final String TENANT_BILLING_BLOCKED_CODE = "TENANT_BILLING_BLOCKED";
  public static final String TENANT_BILLING_BLOCKED_MESSAGE =
      "That world is temporarily unavailable for gameplay.";
  public static final String PUBLIC_PRODUCTION_ADMISSION_DENIED_CODE =
      "PUBLIC_PRODUCTION_ADMISSION_DENIED";
  public static final String PUBLIC_PRODUCTION_ADMISSION_DENIED_MESSAGE =
      "Public entry to that world is currently unavailable.";
  public static final String AUTH_UNAVAILABLE_CODE = AuthenticationErrorCodes.UNAVAILABLE;
  public static final String AUTH_UNAVAILABLE_MESSAGE =
      "Gameplay authority is temporarily unavailable. Retry PLAY shortly.";
  public static final String ENTITLEMENT_UNAVAILABLE_CODE = "ENTITLEMENT_UNAVAILABLE";
  public static final String ENTITLEMENT_UNAVAILABLE_MESSAGE =
      "Gameplay entitlement is temporarily unavailable. Retry PLAY shortly.";
  public static final String PLAY_IDENTITY_UNAVAILABLE_CODE = "PLAY_IDENTITY_UNAVAILABLE";
  public static final String PLAY_IDENTITY_UNAVAILABLE_MESSAGE =
      "Character identity is temporarily unavailable. Retry PLAY shortly.";

  private GameplayStageCommandConstants() {
    // constants only
  }
}
