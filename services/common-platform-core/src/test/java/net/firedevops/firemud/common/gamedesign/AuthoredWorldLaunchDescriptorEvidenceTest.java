package net.firedevops.firemud.common.gamedesign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

  @Test
  void factoryRejectsResultsThatContradictRequestedTargetOrPatch() {
    AuthoredWorldLaunchDescriptorEvidence.Request targetRequest = request(42L, false, null);
    AuthoredWorldLaunchDescriptorEvidence.Request patchRequest =
        request(null, true, "requested-patch");

    assertThatThrownBy(() -> create(targetRequest, 43L, false, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("requested target");
    assertThatThrownBy(() -> create(patchRequest, 42L, false, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("requested override");
    assertThatThrownBy(() -> create(patchRequest, 42L, true, "different-patch"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("requested override");
  }

  @Test
  void directConstructionRejectsResultsThatContradictRequestedTargetOrPatch() {
    AuthoredWorldLaunchDescriptorEvidence.Request request = request(42L, true, "requested-patch");
    AuthoredWorldLaunchDescriptorEvidence valid = create(request, 42L, true, "requested-patch");

    assertThatThrownBy(() -> copyWithResult(valid, 43L, true, "requested-patch"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("requested target");
    assertThatThrownBy(() -> copyWithResult(valid, 42L, false, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("requested override");
    assertThatThrownBy(() -> copyWithResult(valid, 42L, true, "different-patch"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("requested override");
  }

  @Test
  void acceptsExactOverridesAndServerSelectedDefaults() {
    AuthoredWorldLaunchDescriptorEvidence exactOverride =
        create(request(42L, true, "requested-patch"), 42L, true, "requested-patch");
    AuthoredWorldLaunchDescriptorEvidence selectedDefault =
        create(request(42L, false, null), 42L, true, "template-default-patch");

    assertThat(exactOverride.versionId()).isEqualTo(42L);
    assertThat(exactOverride.scriptPatchVersion()).isEqualTo("requested-patch");
    assertThat(selectedDefault.versionId()).isEqualTo(42L);
    assertThat(selectedDefault.scriptPatchVersion()).isEqualTo("template-default-patch");
  }

  private static AuthoredWorldLaunchDescriptorEvidence create(
      AuthoredWorldLaunchDescriptorEvidence.Request request,
      long versionId,
      boolean patchPresent,
      String patch) {
    return AuthoredWorldLaunchDescriptorEvidence.create(
        request,
        "descriptor",
        versionId,
        patchPresent,
        patch,
        "{}",
        "generation-config",
        9L,
        7L,
        "published-release",
        false,
        null);
  }

  private static AuthoredWorldLaunchDescriptorEvidence copyWithResult(
      AuthoredWorldLaunchDescriptorEvidence evidence,
      long versionId,
      boolean patchPresent,
      String patch) {
    return new AuthoredWorldLaunchDescriptorEvidence(
        evidence.schemaVersion(),
        evidence.targetNamespace(),
        evidence.controlPlaneRequestId(),
        evidence.canonicalTenantId(),
        evidence.worldSlug(),
        evidence.authoredWorldSourceOperationId(),
        evidence.authoredWorldSourceEvidenceDigest(),
        evidence.gameTemplateId(),
        evidence.requestedScriptPatchVersionPresent(),
        evidence.requestedScriptPatchVersion(),
        evidence.sourceVersionIdPresent(),
        evidence.sourceVersionId(),
        evidence.targetVersionIdPresent(),
        evidence.targetVersionId(),
        evidence.requestedRuntimeFlagsJsonPresent(),
        evidence.requestedRuntimeFlagsJson(),
        evidence.requestDigest(),
        evidence.launchDescriptorId(),
        versionId,
        patchPresent,
        patch,
        evidence.runtimeFlagsJson(),
        evidence.generationConfigRevision(),
        evidence.versionStateEpoch(),
        evidence.releaseBundleId(),
        evidence.publishedReleaseBundleRef(),
        evidence.remapSetIdPresent(),
        evidence.remapSetId(),
        evidence.resultDigest());
  }

  private static AuthoredWorldLaunchDescriptorEvidence.Request request(
      Long targetVersionId, boolean patchPresent, String patch) {
    return new AuthoredWorldLaunchDescriptorEvidence.Request(
        "test",
        "launch-control-request",
        UUID.fromString("11111111-1111-4111-8111-111111111111"),
        "synthetic-world",
        UUID.fromString("99999999-9999-4999-8999-999999999999"),
        "sha256:" + "a".repeat(64),
        19L,
        patchPresent,
        patch,
        false,
        null,
        targetVersionId != null,
        targetVersionId,
        false,
        null);
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
