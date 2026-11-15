package net.firedevops.firemud.worldmanagement.tenant;

import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;

/**
 * Owner-local fixture CAS receipt. Not Human lifecycle, admission or authenticated transport proof.
 */
public record WorldCanonicalInstanceFixtureActivationResult(
    WorldCanonicalInstanceFixtureActivationRequest request) {
  public static final String SCHEMA = "world-canonical-instance-fixture-activation-result/v1";

  public WorldCanonicalInstanceFixtureActivationResult {
    Objects.requireNonNull(request, "request");
  }

  public byte[] canonicalBytes() {
    String json =
        "{\"schema\":\""
            + SCHEMA
            + "\",\"requestBytesBase64\":\""
            + Base64.getEncoder().encodeToString(request.canonicalBytes())
            + "\",\"lifecycleStatus\":\"ACTIVE\",\"lifecycleEpoch\":\"2\",\"rowVersion\":\"1\"}";
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(json);
    } catch (IOException impossible) {
      throw new IllegalStateException(
          "Fixture activation result cannot be canonicalized", impossible);
    }
  }

  public static WorldCanonicalInstanceFixtureActivationResult fromStored(
      WorldCanonicalInstanceFixtureActivationRequest request, byte[] bytes) {
    var result = new WorldCanonicalInstanceFixtureActivationResult(request);
    if (!Arrays.equals(result.canonicalBytes(), bytes)) {
      throw new IllegalStateException("Fixture activation receipt differs from the exact request");
    }
    return result;
  }
}
