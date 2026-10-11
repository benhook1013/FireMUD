package net.firedevops.firemud.accountservice.dto;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Exact versioned request/capture bytes; neither digest establishes caller authorization. */
public final class AccountSecurityStateRequestDigest {
  public static final String REQUEST_DOMAIN = "firemud/account/security-state/request/v1";

  private AccountSecurityStateRequestDigest() {}

  public static byte[] requestBytes(AccountSecurityStateMutationRequest request) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    frame(bytes, REQUEST_DOMAIN);
    frame(bytes, request.requestId().toString());
    frame(bytes, request.accountUuid().toString());
    frame(bytes, request.callerProofBinding());
    frame(bytes, Long.toString(request.expectedGeneration()));
    frame(bytes, Long.toString(request.expectedSourceVersion()));
    // Each kind has its own frame; cardinality is also an explicit frame.
    frame(bytes, Integer.toString(request.mutationKinds().size()));
    request.mutationKinds().forEach(kind -> frame(bytes, kind));
    frame(bytes, request.canonicalDesiredState());
    return bytes.toByteArray();
  }

  public static String digest(AccountSecurityStateMutationRequest request) {
    return sha256(requestBytes(request));
  }

  public static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  public static void frame(ByteArrayOutputStream output, String text) {
    frame(output, text.getBytes(StandardCharsets.UTF_8));
  }

  public static void frame(ByteArrayOutputStream output, byte[] value) {
    output.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
    output.writeBytes(value);
  }
}
