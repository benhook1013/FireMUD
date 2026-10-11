package net.firedevops.firemud.accountservice.service.session;

import java.nio.file.Path;
import java.time.Instant;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * Separate unpublished control-ui custody using the existing bounded mounted-ring parser.
 * encryption/ and request-mac/ each contain an independent existing-format keyring. Neither
 * defaults to or reads the gameplay response-envelope mount.
 */
public final class AccountControlUiKeyring {
  private final AccountResponseEnvelopeKeyring encryption;
  private final AccountResponseEnvelopeKeyring requestMac;

  public AccountControlUiKeyring(Path dedicatedRoot) {
    if (dedicatedRoot == null || !dedicatedRoot.isAbsolute()) {
      throw new IllegalArgumentException("Separate absolute control-ui custody root required");
    }
    encryption = new AccountResponseEnvelopeKeyring(dedicatedRoot.resolve("encryption").toString());
    requestMac =
        new AccountResponseEnvelopeKeyring(dedicatedRoot.resolve("request-mac").toString());
  }

  AccountResponseEnvelopeKeyring encryption() {
    return encryption;
  }

  String requestKeyId() {
    return requestMac.readCurrent().activeKeyId();
  }

  SecretKey requestKey(String id, Instant now) {
    byte[] bytes = requestMac.readCurrent().decryptionKey(id, now).secretKey().getEncoded();
    byte[] encryptionBytes = encryption.readCurrent().activeKey().secretKey().getEncoded();
    try {
      if (java.util.Arrays.equals(bytes, encryptionBytes)) {
        throw new IllegalStateException("Control-ui encryption and request-MAC keys must differ");
      }
      return new SecretKeySpec(bytes, "HmacSHA256");
    } finally {
      java.util.Arrays.fill(bytes, (byte) 0);
      java.util.Arrays.fill(encryptionBytes, (byte) 0);
    }
  }
}
