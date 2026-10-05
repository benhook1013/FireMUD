package net.firedevops.firemud.accountservice.security;

/** The independently keyed response-envelope purposes owned by Account. */
public enum AccountEnvelopePurpose {
  CONNECT_TOKEN_RESPONSE("connect-token"),
  BARE_LOGIN_RESPONSE("bare-login");

  private final String manifestName;

  AccountEnvelopePurpose(String manifestName) {
    this.manifestName = manifestName;
  }

  String manifestName() {
    return manifestName;
  }

  static AccountEnvelopePurpose fromManifestName(String value) {
    for (AccountEnvelopePurpose purpose : values()) {
      if (purpose.manifestName.equals(value)) {
        return purpose;
      }
    }
    return null;
  }
}
