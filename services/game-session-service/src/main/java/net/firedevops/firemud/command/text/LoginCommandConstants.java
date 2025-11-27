package net.firedevops.firemud.command.text;

/** Constants used when interpreting LOGIN/LOGON commands. */
public final class LoginCommandConstants {
  public static final String PROMPT_MODE_UNSUPPORTED_CODE = "PROMPT_LOGIN_UNSUPPORTED";
  public static final String PROMPT_MODE_UNSUPPORTED_MESSAGE =
      "Prompt-based login is not implemented yet; send LOGIN <username> <password>.";

  private LoginCommandConstants() {
    // constants only
  }
}
