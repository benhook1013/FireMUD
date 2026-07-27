package net.firedevops.firemud.account;

/** Shared constants for login error codes emitted by the Account Service. */
public final class AuthenticationErrorCodes {
  public static final String INVALID_CREDENTIALS = "AUTH_INVALID_CREDENTIALS";
  public static final String RETRY_LATER = "AUTH_RETRY_LATER";
  public static final String ACCOUNT_LOCKED = "AUTH_ACCOUNT_LOCKED";
  public static final String ABUSE_CONTROL_UNAVAILABLE = "AUTH_ABUSE_CONTROL_UNAVAILABLE";
  public static final String UNAVAILABLE = "AUTH_UNAVAILABLE";

  private AuthenticationErrorCodes() {
    // constants only
  }
}
