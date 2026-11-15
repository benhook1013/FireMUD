package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorldCanonicalInstanceFixtureActivationResultTest {
  @Test
  void exactRequestRoundTripsWithoutClaimingHumanLifecycleAuthority() {
    var request = request(UUID.randomUUID());
    var result = new WorldCanonicalInstanceFixtureActivationResult(request);
    byte[] bytes = result.canonicalBytes();
    assertThat(WorldCanonicalInstanceFixtureActivationResult.fromStored(request, bytes))
        .isEqualTo(result);
    assertThat(new String(bytes, StandardCharsets.UTF_8))
        .contains(WorldCanonicalInstanceFixtureActivationResult.SCHEMA)
        .doesNotContain("world-canonical-instance-lifecycle-evidence/v1");
    bytes[0] = 0;
    assertThat(result.canonicalBytes()[0]).isEqualTo((byte) '{');
    assertThatThrownBy(
            () -> WorldCanonicalInstanceFixtureActivationResult.fromStored(request, bytes))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void wrongRequestAndUnqualifiedEpochCannotReuseReceipt() {
    var request = request(UUID.randomUUID());
    var bytes = new WorldCanonicalInstanceFixtureActivationResult(request).canonicalBytes();
    assertThatThrownBy(
            () ->
                WorldCanonicalInstanceFixtureActivationResult.fromStored(
                    request(UUID.randomUUID()), bytes))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                new WorldCanonicalInstanceFixtureActivationRequest(
                    request.activationRequestId(),
                    request.canonicalTenantId(),
                    request.canonicalGameInstanceId(),
                    request.fixturePolicySealEvidenceDigest(),
                    2,
                    1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static WorldCanonicalInstanceFixtureActivationRequest request(UUID operation) {
    return new WorldCanonicalInstanceFixtureActivationRequest(
        operation,
        UUID.fromString("10000000-0000-4000-8000-000000000001"),
        UUID.fromString("20000000-0000-4000-8000-000000000001"),
        "sha256:" + "a".repeat(64),
        1,
        0);
  }
}
