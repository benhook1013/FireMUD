package net.firedevops.firemud.accountservice.security;

/** The independently keyed encrypted-envelope purposes owned by Account. */
public enum AccountEnvelopePurpose {
  CONNECT_TOKEN_RESPONSE("connect-token"),
  BARE_LOGIN_RESPONSE("bare-login"),
  PENDING_PASSWORD_RESET("pending-reset"),
  CONTROL_UI_RESPONSE("control-ui-response");

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
