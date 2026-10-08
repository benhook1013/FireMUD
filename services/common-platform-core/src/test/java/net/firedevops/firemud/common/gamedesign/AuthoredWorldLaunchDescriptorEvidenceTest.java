package net.firedevops.firemud.common.gamedesign;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuthoredWorldLaunchDescriptorEvidenceTest {
  private static final String REQUEST_DIGEST =
      "sha256:36ddbc22c1284986cc459dcf3737400bd24ba2b28a6cb8832001705bfe807ed9";
  private static final String RESULT_DIGEST =
      "sha256:5205ddc44ac207017521c0782cdc21f69de1bdf4ccddefdf249211e05cfe3a3f";

  @Test
  void digestsHashTheExactPreimagesAndKeepTheirCurrentVectors() throws NoSuchAlgorithmException {
    AuthoredWorldLaunchDescriptorEvidence.Request request = request();

    assertThat(sha256(AuthoredWorldLaunchDescriptorEvidence.requestPreimage(request)))
        .isEqualTo(REQUEST_DIGEST);
    assertThat(request.requestDigest()).isEqualTo(REQUEST_DIGEST);

    AuthoredWorldLaunchDescriptorEvidence evidence =
        AuthoredWorldLaunchDescriptorEvidence.create(
            request,
            "descriptor",
            Long.MAX_VALUE,
            false,
            null,
            "{}",
            "generation-config",
            9L,
            7L,
            "published-release",
            false,
            null);

    assertThat(sha256(AuthoredWorldLaunchDescriptorEvidence.resultPreimage(evidence)))
        .isEqualTo(RESULT_DIGEST);
    assertThat(evidence.resultDigest()).isEqualTo(RESULT_DIGEST);
  }

  private static AuthoredWorldLaunchDescriptorEvidence.Request request() {
    return new AuthoredWorldLaunchDescriptorEvidence.Request(
        "test",
        "launch-control-request",
        UUID.fromString("11111111-1111-4111-8111-111111111111"),
        "synthetic-world",
        UUID.fromString("99999999-9999-4999-8999-999999999999"),
        "sha256:" + "a".repeat(64),
        19L,
        false,
        null,
        false,
        null,
        false,
        null,
        false,
        null);
  }

  private static String sha256(byte[] preimage) throws NoSuchAlgorithmException {
    return "sha256:"
        + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(preimage));
  }
}
