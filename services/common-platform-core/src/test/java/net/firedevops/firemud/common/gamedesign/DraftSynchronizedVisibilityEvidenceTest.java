package net.firedevops.firemud.common.gamedesign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.gamedesign.DraftSynchronizedVisibilityEvidence.AppliedEpoch;
import net.firedevops.firemud.common.gamedesign.DraftSynchronizedVisibilityEvidence.Fence;
import net.firedevops.firemud.common.gamedesign.DraftSynchronizedVisibilityEvidence.Request;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import org.junit.jupiter.api.Test;

class DraftSynchronizedVisibilityEvidenceTest {
  private static final String EXPECTED_EPOCH = "900719925474099312345678901234567890";
  private static final UUID REQUEST_ID = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID COMMIT_ID = uuid("22222222-2222-4222-8222-222222222222");
  private static final UUID READ_REQUEST_ID = uuid("33333333-3333-4333-8333-333333333333");

  @Test
  void requiresExactlyOneAppliedEpochAdvanceAcrossLargeCounters() throws Exception {
    BigInteger expected = new BigInteger(EXPECTED_EPOCH);
    String nextEpoch = expected.add(BigInteger.ONE).toString();

    DraftSynchronizedVisibilityEvidence evidence = evidence(nextEpoch);
    evidence.requireValid();
    assertThat(evidence.appliedOwnerResult(Owner.WORLD_MANAGEMENT).appliedEpochs())
        .containsExactly(
            new AppliedEpoch(
                "WORLD_TEMPLATE",
                "world-23",
                "ROOM_SCOPE",
                "room-scope-3",
                EXPECTED_EPOCH,
                nextEpoch));

    for (String invalidEpoch :
        List.of(
            expected.toString(),
            expected.subtract(BigInteger.ONE).toString(),
            expected.add(BigInteger.TWO).toString())) {
      assertThatThrownBy(() -> evidence(invalidEpoch))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("complete bound epoch declaration");
    }
  }

  private static DraftSynchronizedVisibilityEvidence evidence(String resultingEpoch)
      throws Exception {
    DraftCommitBinding binding = binding();
    Request request = new Request(1, "test", READ_REQUEST_ID, binding.target());
    Fence fence =
        new Fence(
            REQUEST_ID,
            COMMIT_ID,
            binding.digest(),
            resultVector(binding, resultingEpoch),
            OffsetDateTime.parse("2026-10-14T00:00:00Z"));
    return new DraftSynchronizedVisibilityEvidence(request, binding, "SYNCHRONIZED", fence);
  }

  private static String resultVector(DraftCommitBinding binding, String resultingEpoch)
      throws Exception {
    String resultBytes = Base64.getEncoder().encodeToString("{}".getBytes(StandardCharsets.UTF_8));
    String json =
        "[{"
            + "\"owner\":\"WORLD_MANAGEMENT\","
            + "\"status\":\"APPLIED\","
            + "\"commitId\":\""
            + COMMIT_ID
            + "\","
            + "\"bindingDigest\":\""
            + binding.digest()
            + "\","
            + "\"resultIdentity\":\"world-result-1\","
            + "\"resultBytesBase64\":\""
            + resultBytes
            + "\","
            + "\"appliedEpochs\":[{"
            + "\"aggregateType\":\"WORLD_TEMPLATE\","
            + "\"aggregateId\":\"world-23\","
            + "\"scopeType\":\"ROOM_SCOPE\","
            + "\"scopeId\":\"room-scope-3\","
            + "\"expectedEpoch\":\""
            + EXPECTED_EPOCH
            + "\","
            + "\"resultingEpoch\":\""
            + resultingEpoch
            + "\"}]}]";
    return new String(Rfc8785CanonicalJson.canonicalizeUtf8(json), StandardCharsets.UTF_8);
  }

  private static DraftCommitBinding binding() {
    return DraftCommitBinding.create(
        new TargetProof(
            uuid("44444444-4444-4444-8444-444444444444"),
            uuid("55555555-5555-4555-8555-555555555555"),
            1,
            "test-tenant",
            2,
            "test-tenant",
            "NEW_GAME_ROW"),
        REQUEST_ID,
        COMMIT_ID,
        "base-commit-1",
        List.of(
            new RevisionPayload(
                "0",
                uuid("66666666-6666-4666-8666-666666666666"),
                Owner.WORLD_MANAGEMENT,
                "{\"world\":true}")),
        List.of(
            new AffectedUnit(
                Owner.WORLD_MANAGEMENT,
                "WORLD_TEMPLATE",
                "world-23",
                "ROOM_SCOPE",
                "room-scope-3",
                EXPECTED_EPOCH)));
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
