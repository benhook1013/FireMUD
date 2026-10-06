package net.firedevops.firemud.accountservice.dto;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/** Versioned semantic request framing; it deliberately accepts no credential material. */
public final class AccountControlUiIssuanceRequestDigest {
  public static final int VERSION = 1;
  public static final String DOMAIN = "firemud/account/control-ui-issuance/request/v1";

  private AccountControlUiIssuanceRequestDigest() {}

  public static byte[] canonicalBytes(AccountControlUiIssuanceRequest request) {
    Objects.requireNonNull(request, "request");
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    frame(output, DOMAIN);
    frame(output, Integer.toString(VERSION));
    frame(output, "LOGIN_ISSUANCE");
    frame(output, request.accountUuid());
    frame(output, AccountControlUiIssuanceRequest.PROFILE);
    frame(output, AccountControlUiIssuanceRequest.AUDIENCE);
    return output.toByteArray();
  }

  public static byte[] digest(AccountControlUiIssuanceRequest request) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(canonicalBytes(request));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static void frame(ByteArrayOutputStream output, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    output.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
    output.writeBytes(bytes);
  }
}
