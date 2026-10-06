package net.firedevops.firemud.common.gamedesign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class DraftSynchronizedVisibilityEvidenceTest {
  private static final String NAMESPACE = "test";
  private static final UUID TENANT_ID = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID VERSION_ID = uuid("22222222-2222-4222-8222-222222222222");
  private static final UUID READ_ID = uuid("33333333-3333-4333-8333-333333333333");
  private static final UUID REQUEST_ID = uuid("44444444-4444-4444-8444-444444444444");
  private static final UUID COMMIT_ID = uuid("55555555-5555-4555-8555-555555555555");

  @Test
  void completeAllOwnerFenceValidatesAndReturnsDefensiveExactWorldResult() {
    var evidence = evidence(binding(), resultVector(binding()));

    evidence.requireValid();
    assertThat(evidence.appliedOwnerResults()).hasSize(2);
    assertThat(evidence.appliedOwnerResult(Owner.WORLD_MANAGEMENT).resultIdentity())
        .isEqualTo("world-result");
    byte[] resultBytes = evidence.appliedOwnerResult(Owner.WORLD_MANAGEMENT).resultBytes();
    resultBytes[0] = 0;
    assertThat(evidence.appliedOwnerResult(Owner.WORLD_MANAGEMENT).resultBytes())
        .containsExactly("world-result-bytes".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void rejectsIncompleteOrSubstitutedFenceAndOwnerResults() {
    DraftCommitBinding binding = binding();
    String vector = resultVector(binding);

    assertThatThrownBy(() -> evidence(binding, vector.replace("APPLIED", "UNKNOWN")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("terminal APPLIED");
    assertThatThrownBy(() -> evidence(binding, vector.replace("WORLD_TEMPLATE", "OTHER")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("bound epoch declaration");
    assertThatThrownBy(
            () ->
                new DraftSynchronizedVisibilityEvidence(
                    request(),
                    binding,
                    "APPLYING",
                    fence(binding, vector)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not bound");
    assertThatThrownBy(
            () ->
                new DraftSynchronizedVisibilityEvidence(
                    request(),
                    binding,
                    "SYNCHRONIZED",
                    new DraftSynchronizedVisibilityEvidence.Fence(
                        REQUEST_ID,
                        COMMIT_ID,
                        "sha256:" + "f".repeat(64),
                        vector,
                        OffsetDateTime.now(ZoneOffset.UTC))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("full input");
  }

  private static DraftSynchronizedVisibilityEvidence evidence(
      DraftCommitBinding binding, String vector) {
    return new DraftSynchronizedVisibilityEvidence(
        request(), binding, "SYNCHRONIZED", fence(binding, vector));
  }

  private static DraftSynchronizedVisibilityEvidence.Fence fence(
      DraftCommitBinding binding, String vector) {
    return new DraftSynchronizedVisibilityEvidence.Fence(
        REQUEST_ID,
        COMMIT_ID,
        binding.digest(),
        vector,
        OffsetDateTime.of(2026, 10, 6, 0, 0, 0, 0, ZoneOffset.UTC));
  }

  private static DraftSynchronizedVisibilityEvidence.Request request() {
    return new DraftSynchronizedVisibilityEvidence.Request(1, NAMESPACE, READ_ID, target());
  }

  private static TargetProof target() {
    return new TargetProof(TENANT_ID, VERSION_ID, 19L, "tenant-key", 42L, "tenant-key", "NEW_GAME_ROW");
  }

  private static DraftCommitBinding binding() {
    return DraftCommitBinding.create(
        target(),
        REQUEST_ID,
        COMMIT_ID,
        "base-source-1",
        List.of(
            new RevisionPayload("0", uuid("66666666-6666-4666-8666-666666666666"), Owner.WORLD_MANAGEMENT, "{}"),
            new RevisionPayload("1", uuid("77777777-7777-4777-8777-777777777777"), Owner.ENTITY_MANAGEMENT, "{}")),
        List.of(
            new AffectedUnit(
                Owner.WORLD_MANAGEMENT,
                "WORLD_TEMPLATE",
                "world-1",
                "ROOM_SCOPE",
                "room-1",
                "0"),
            new AffectedUnit(
                Owner.ENTITY_MANAGEMENT,
                "ENTITY_TEMPLATE",
                "entity-1",
                "ACTOR_SCOPE",
                "actor-1",
                "1")));
  }

  private static String resultVector(DraftCommitBinding binding) {
    List<Map<String, Object>> vector = new ArrayList<>();
    for (Owner owner : binding.requiredOwners()) {
      AffectedUnit unit = binding.affectedUnits(owner).getFirst();
      Map<String, Object> epoch = new LinkedHashMap<>();
      epoch.put("aggregateType", unit.aggregateType());
      epoch.put("aggregateId", unit.aggregateId());
      epoch.put("scopeType", unit.scopeType());
      epoch.put("scopeId", unit.scopeId());
      epoch.put("expectedEpoch", unit.expectedEpoch());
      epoch.put("resultingEpoch", new java.math.BigInteger(unit.expectedEpoch()).add(java.math.BigInteger.ONE).toString());
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("owner", owner.name());
      item.put("status", "APPLIED");
      item.put("commitId", COMMIT_ID.toString());
      item.put("bindingDigest", binding.digest());
      item.put("resultIdentity", owner == Owner.WORLD_MANAGEMENT ? "world-result" : "entity-result");
      item.put("resultBytesBase64", Base64.getEncoder().encodeToString("world-result-bytes".getBytes(StandardCharsets.UTF_8)));
      item.put("appliedEpochs", List.of(epoch));
      vector.add(item);
    }
    return canonical(new ObjectMapper().writeValueAsString(vector));
  }

  private static String canonical(String json) {
    try {
      return new String(Rfc8785CanonicalJson.canonicalizeUtf8(json), StandardCharsets.UTF_8);
    } catch (java.io.IOException exception) {
      throw new AssertionError(exception);
    }
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
