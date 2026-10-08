package net.firedevops.firemud.gamesession.dto;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class CanonicalInitialAdmissionRequestTest {
  @Test
  void initialAdmissionRequestIdMatches128CharacterOwnerAndIntentBounds() {
    for (int length : new int[] {120, 121, 128}) {
      request("r".repeat(length));
    }

    assertThatThrownBy(() -> request("r".repeat(129)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("initialAdmissionRequestId must contain 1..128 characters");
  }

  private static CanonicalInitialAdmissionRequest request(String requestId) {
    String targetNamespace = "firemud-prod";
    UUID tenantId = UUID.randomUUID();
    String worldSlug = "world";
    UUID realmId = UUID.randomUUID();
    UUID playableStateNamespaceId = UUID.randomUUID();
    String playableStateScope = "SHARED";
    UUID gameInstanceId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    long lifecycleEpoch = 1L;
    long catalogRevision = 1L;
    CanonicalInitialAdmissionRequest.OriginKind originKind =
        CanonicalInitialAdmissionRequest.OriginKind.NO_PRIOR_POINTER;
    String requestDigest =
        CanonicalInitialAdmissionRequest.computeRequestDigest(
            targetNamespace,
            tenantId,
            worldSlug,
            realmId,
            playableStateNamespaceId,
            playableStateScope,
            gameInstanceId,
            versionId,
            lifecycleEpoch,
            catalogRevision,
            originKind,
            null,
            requestId);

    return new CanonicalInitialAdmissionRequest(
        targetNamespace,
        tenantId,
        worldSlug,
        realmId,
        playableStateNamespaceId,
        playableStateScope,
        gameInstanceId,
        versionId,
        lifecycleEpoch,
        catalogRevision,
        originKind,
        null,
        requestId,
        requestDigest,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "sha256:" + "a".repeat(64));
  }
}
