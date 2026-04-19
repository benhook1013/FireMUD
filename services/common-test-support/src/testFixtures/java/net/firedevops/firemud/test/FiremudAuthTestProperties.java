package net.firedevops.firemud.test;

/** Shared Spring test property constants for FireMUD auth-enabled test contexts. */
public final class FiremudAuthTestProperties {
  public static final String JWT_SECRET =
      "firemud.auth.jwt-secret=testsecretkeytestsecretkeytest1234";
  public static final String JWT_EXPIRATION = "firemud.auth.jwt-expiration-ms=3600000";
  public static final String HTTP_ENABLED = "firemud.auth.http.enabled=true";
  public static final String HTTP_ROLE_REQUIREMENT_PRIVILEGED =
      "firemud.auth.http.role-requirement=PRIVILEGED";

  private FiremudAuthTestProperties() {}
}
