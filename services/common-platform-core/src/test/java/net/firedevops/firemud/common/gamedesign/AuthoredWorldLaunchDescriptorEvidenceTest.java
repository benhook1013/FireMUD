package net.firedevops.firemud.common.gamedesign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuthoredWorldLaunchDescriptorEvidenceTest {
  private static final String REQUEST_ABSENT_PREIMAGE =
      "55:game-design-authored-world-launch-descriptor-request/v113:schemaVersion1:115:targetNamespace4:test21:controlPlaneRequestId5:cp-α17:canonicalTenantId36:12345678-1234-4234-8234-123456789abc9:worldSlug12:copper-coast30:authoredWorldSourceOperationId36:22345678-1234-4234-8234-123456789abc33:authoredWorldSourceEvidenceDigest71:sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa14:gameTemplateId2:1935:requestedScriptPatchVersion.present4:true33:requestedScriptPatchVersion.value11:pätch-🦊23:sourceVersionId.present5:false21:sourceVersionId.value0:23:targetVersionId.present5:false21:targetVersionId.value0:33:requestedRuntimeFlagsJson.present5:false31:requestedRuntimeFlagsJson.value0:";
  private static final String REQUEST_EMPTY_PREIMAGE =
      "55:game-design-authored-world-launch-descriptor-request/v113:schemaVersion1:115:targetNamespace4:test21:controlPlaneRequestId5:cp-α17:canonicalTenantId36:12345678-1234-4234-8234-123456789abc9:worldSlug12:copper-coast30:authoredWorldSourceOperationId36:22345678-1234-4234-8234-123456789abc33:authoredWorldSourceEvidenceDigest71:sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa14:gameTemplateId2:1935:requestedScriptPatchVersion.present4:true33:requestedScriptPatchVersion.value11:pätch-🦊23:sourceVersionId.present5:false21:sourceVersionId.value0:23:targetVersionId.present5:false21:targetVersionId.value0:33:requestedRuntimeFlagsJson.present4:true31:requestedRuntimeFlagsJson.value0:";
  private static final String REQUEST_CHANGED_WORLD_PREIMAGE =
      "55:game-design-authored-world-launch-descriptor-request/v113:schemaVersion1:115:targetNamespace4:test21:controlPlaneRequestId5:cp-α17:canonicalTenantId36:12345678-1234-4234-8234-123456789abc9:worldSlug12:copper-shore30:authoredWorldSourceOperationId36:22345678-1234-4234-8234-123456789abc33:authoredWorldSourceEvidenceDigest71:sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa14:gameTemplateId2:1935:requestedScriptPatchVersion.present4:true33:requestedScriptPatchVersion.value11:pätch-🦊23:sourceVersionId.present5:false21:sourceVersionId.value0:23:targetVersionId.present5:false21:targetVersionId.value0:33:requestedRuntimeFlagsJson.present5:false31:requestedRuntimeFlagsJson.value0:";
  private static final String RESULT_MAX_PREIMAGE =
      "54:game-design-authored-world-launch-descriptor-result/v113:schemaVersion1:115:targetNamespace4:test21:controlPlaneRequestId5:cp-α17:canonicalTenantId36:12345678-1234-4234-8234-123456789abc9:worldSlug12:copper-coast30:authoredWorldSourceOperationId36:22345678-1234-4234-8234-123456789abc33:authoredWorldSourceEvidenceDigest71:sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa14:gameTemplateId2:1935:requestedScriptPatchVersion.present4:true33:requestedScriptPatchVersion.value11:pätch-🦊23:sourceVersionId.present5:false21:sourceVersionId.value0:23:targetVersionId.present5:false21:targetVersionId.value0:33:requestedRuntimeFlagsJson.present5:false31:requestedRuntimeFlagsJson.value0:13:requestDigest71:sha256:ac6fc51a4b81185a33cfe9c84df69e05537a69415a6d8b8fb478a946221e711118:launchDescriptorId11:ld-vector-19:versionId19:922337203685477580726:scriptPatchVersion.present4:true24:scriptPatchVersion.value11:pätch-🦊16:runtimeFlagsJson17:{\"welcome\":\"雪\"}24:generationConfigRevision13:gen-rév-🧭17:versionStateEpoch19:922337203685477580715:releaseBundleId19:922337203685477580725:publishedReleaseBundleRef91:release-bundle:12345678-1234-4234-8234-123456789abc:9223372036854775807:922337203685477580718:remapSetId.present5:false16:remapSetId.value0:";
  private static final String RESULT_CHANGED_EPOCH_PREIMAGE =
      "54:game-design-authored-world-launch-descriptor-result/v113:schemaVersion1:115:targetNamespace4:test21:controlPlaneRequestId5:cp-α17:canonicalTenantId36:12345678-1234-4234-8234-123456789abc9:worldSlug12:copper-coast30:authoredWorldSourceOperationId36:22345678-1234-4234-8234-123456789abc33:authoredWorldSourceEvidenceDigest71:sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa14:gameTemplateId2:1935:requestedScriptPatchVersion.present4:true33:requestedScriptPatchVersion.value11:pätch-🦊23:sourceVersionId.present5:false21:sourceVersionId.value0:23:targetVersionId.present5:false21:targetVersionId.value0:33:requestedRuntimeFlagsJson.present5:false31:requestedRuntimeFlagsJson.value0:13:requestDigest71:sha256:ac6fc51a4b81185a33cfe9c84df69e05537a69415a6d8b8fb478a946221e711118:launchDescriptorId11:ld-vector-19:versionId19:922337203685477580726:scriptPatchVersion.present4:true24:scriptPatchVersion.value11:pätch-🦊16:runtimeFlagsJson17:{\"welcome\":\"雪\"}24:generationConfigRevision13:gen-rév-🧭17:versionStateEpoch19:922337203685477580615:releaseBundleId19:922337203685477580725:publishedReleaseBundleRef91:release-bundle:12345678-1234-4234-8234-123456789abc:9223372036854775807:922337203685477580718:remapSetId.present5:false16:remapSetId.value0:";

  @Test
  void requestDigestMatchesIndependentUtf8FramingVectors() {
    AuthoredWorldLaunchDescriptorEvidence.Request absentFlags = request("copper-coast", false);
    AuthoredWorldLaunchDescriptorEvidence.Request emptyFlags = request("copper-coast", true);
    AuthoredWorldLaunchDescriptorEvidence.Request changedWorld = request("copper-shore", false);

    assertEquals(
        REQUEST_ABSENT_PREIMAGE,
        text(AuthoredWorldLaunchDescriptorEvidence.requestPreimage(absentFlags)));
    assertEquals(
        "sha256:ac6fc51a4b81185a33cfe9c84df69e05537a69415a6d8b8fb478a946221e7111",
        absentFlags.requestDigest());
    assertEquals(
        REQUEST_EMPTY_PREIMAGE,
        text(AuthoredWorldLaunchDescriptorEvidence.requestPreimage(emptyFlags)));
    assertEquals(
        "sha256:99207cb6a0d39108465c81fdd2519117237607c7e80b2ad1db07e74217d81f42",
        emptyFlags.requestDigest());
    assertEquals(
        REQUEST_CHANGED_WORLD_PREIMAGE,
        text(AuthoredWorldLaunchDescriptorEvidence.requestPreimage(changedWorld)));
    assertEquals(
        "sha256:fec25a5c82d0b831feb7018d25b7f76123af23834b1d2ce653a1b9dc97aea4eb",
        changedWorld.requestDigest());
    assertNotEquals(absentFlags.requestDigest(), emptyFlags.requestDigest());
  }

  @Test
  void resultDigestMatchesIndependentUnicodeAndLongBoundaryVectors() {
    AuthoredWorldLaunchDescriptorEvidence maximumCounter = evidence(Long.MAX_VALUE);
    AuthoredWorldLaunchDescriptorEvidence changedCounter = evidence(Long.MAX_VALUE - 1);

    assertEquals(
        RESULT_MAX_PREIMAGE,
        text(AuthoredWorldLaunchDescriptorEvidence.resultPreimage(maximumCounter)));
    assertEquals(
        "sha256:701c68797b898b696cac24d48dcff4fe25c0856f15965ff7e8b219f7c57acd11",
        maximumCounter.resultDigest());
    assertEquals(
        RESULT_CHANGED_EPOCH_PREIMAGE,
        text(AuthoredWorldLaunchDescriptorEvidence.resultPreimage(changedCounter)));
    assertEquals(
        "sha256:1b3f500a92a621c901c0f2200a244b3f4d1a120c4416d79469fb11ec77a5cf68",
        changedCounter.resultDigest());
    assertNotEquals(maximumCounter.resultDigest(), changedCounter.resultDigest());
  }

  private static AuthoredWorldLaunchDescriptorEvidence evidence(long versionStateEpoch) {
    AuthoredWorldLaunchDescriptorEvidence.Request request = request("copper-coast", false);
    return AuthoredWorldLaunchDescriptorEvidence.create(
        request,
        "ld-vector-1",
        Long.MAX_VALUE,
        true,
        "pätch-🦊",
        "{\"welcome\":\"雪\"}",
        "gen-rév-🧭",
        versionStateEpoch,
        Long.MAX_VALUE,
        "release-bundle:12345678-1234-4234-8234-123456789abc:9223372036854775807:9223372036854775807",
        false,
        null);
  }

  private static AuthoredWorldLaunchDescriptorEvidence.Request request(
      String worldSlug, boolean runtimeFlagsPresent) {
    return new AuthoredWorldLaunchDescriptorEvidence.Request(
        "test",
        "cp-α",
        UUID.fromString("12345678-1234-4234-8234-123456789abc"),
        worldSlug,
        UUID.fromString("22345678-1234-4234-8234-123456789abc"),
        "sha256:" + "a".repeat(64),
        19L,
        true,
        "pätch-🦊",
        false,
        null,
        false,
        null,
        runtimeFlagsPresent,
        runtimeFlagsPresent ? "" : null);
  }

  private static String text(byte[] bytes) {
    return new String(bytes, StandardCharsets.UTF_8);
  }
}
