package net.firedevops.firemud.account;

/** Shared constants for login error codes emitted by the Account Service. */
public final class AuthenticationErrorCodes {
  public static final String INVALID_CREDENTIALS = "AUTH_INVALID_CREDENTIALS";
  public static final String OTP_REQUIRED = "AUTH_OTP_REQUIRED";
  public static final String ACCOUNT_LOCKED = "AUTH_ACCOUNT_LOCKED";
  public static final String UPSTREAM_FAILURE = "AUTH_UPSTREAM_FAILURE";

  private AuthenticationErrorCodes() {
    // constants only
  }
}
