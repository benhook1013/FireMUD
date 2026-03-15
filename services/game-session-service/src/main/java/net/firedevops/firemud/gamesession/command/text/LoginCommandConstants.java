package net.firedevops.firemud.gamesession.command.text;

/** Constants used when interpreting LOGIN/LOGON commands. */
public final class LoginCommandConstants {
  public static final String PROMPT_MODE_UNSUPPORTED_CODE = "PROMPT_LOGIN_UNSUPPORTED";
  public static final String PROMPT_MODE_UNSUPPORTED_MESSAGE =
      "Prompt-based login is not implemented yet; send LOGIN <username> <password>.";

  public static final String ACCOUNT_MISMATCH_CODE = "ACCOUNT_MISMATCH";
  public static final String ACCOUNT_MISMATCH_MESSAGE =
      "Authenticated account does not own this session";

  public static final String INVALID_ACCOUNT_CODE = "INVALID_ACCOUNT";
  public static final String INVALID_ACCOUNT_MESSAGE =
      "Account identifier supplied by Account Service is invalid";

  private LoginCommandConstants() {
    // constants only
  }
}
