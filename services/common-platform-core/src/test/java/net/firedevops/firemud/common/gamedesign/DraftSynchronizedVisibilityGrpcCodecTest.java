package net.firedevops.firemud.common.gamedesign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.UnknownFieldSet;
import java.nio.charset.StandardCharsets;
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
import net.firedevops.firemud.gamedesign.v1.ReadDraftSynchronizedVisibilityResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class DraftSynchronizedVisibilityGrpcCodecTest {
  private static final UUID TENANT_ID = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID VERSION_ID = uuid("22222222-2222-4222-8222-222222222222");
  private static final UUID READ_ID = uuid("33333333-3333-4333-8333-333333333333");
  private static final UUID REQUEST_ID = uuid("44444444-4444-4444-8444-444444444444");
  private static final UUID COMMIT_ID = uuid("55555555-5555-4555-8555-555555555555");

  @Test
  void completeRequestBindingAndFenceRoundTrip() {
    var request = request();
    var evidence = evidence(binding());
    var response = DraftSynchronizedVisibilityGrpcCodec.toResponse(evidence);

    assertThat(DraftSynchronizedVisibilityGrpcCodec.fromRequest(
            DraftSynchronizedVisibilityGrpcCodec.toRequest(request)))
        .isEqualTo(request);
    assertThat(DraftSynchronizedVisibilityGrpcCodec.fromResponse(request, response))
        .isEqualTo(evidence);
  }

  @Test
  void rejectsReadTargetBindingWorkflowFenceAndOwnerVectorSubstitutions() {
    var request = request();
    var response = DraftSynchronizedVisibilityGrpcCodec.toResponse(evidence(binding()));

    assertInvalid(request, response.toBuilder().setReadRequestId(uuid("88888888-8888-4888-8888-888888888888").toString()).build());
    assertInvalid(
        request,
        response.toBuilder()
            .setTarget(response.getTarget().toBuilder().setSourceGameRowId(43L))
            .build());
    assertInvalid(request, response.toBuilder().setBindingDigest("sha256:" + "f".repeat(64)).build());
    assertInvalid(request, response.toBuilder().setWorkflowState("APPLYING").build());
    assertInvalid(
        request,
        response.toBuilder()
            .setFence(response.getFence().toBuilder().setCommitId(uuid("99999999-9999-4999-8999-999999999999").toString()))
            .build());
    assertInvalid(
        request,
        response.toBuilder()
            .setFence(response.getFence().toBuilder().setResultVectorJson(response.getFence().getResultVectorJson().replace("APPLIED", "UNKNOWN")))
            .build());
  }

  @Test
  void rejectsUnknownWireFieldsAndMissingTargetOrFence() {
    var request = request();
    var response = DraftSynchronizedVisibilityGrpcCodec.toResponse(evidence(binding()));
    UnknownFieldSet unknown =
        UnknownFieldSet.newBuilder()
            .addField(100, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
            .build();

    assertThatThrownBy(
            () ->
                DraftSynchronizedVisibilityGrpcCodec.fromRequest(
                    DraftSynchronizedVisibilityGrpcCodec.toRequest(request).toBuilder()
                        .setUnknownFields(unknown)
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");
    assertInvalid(request, response.toBuilder().clearTarget().build());
    assertInvalid(request, response.toBuilder().clearFence().build());
    assertInvalid(
        request,
        response.toBuilder()
            .setFence(response.getFence().toBuilder().setUnknownFields(unknown))
            .build());
  }

  private static void assertInvalid(
      DraftSynchronizedVisibilityEvidence.Request request,
      ReadDraftSynchronizedVisibilityResponse response) {
    assertThatThrownBy(() -> DraftSynchronizedVisibilityGrpcCodec.fromResponse(request, response))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static DraftSynchronizedVisibilityEvidence evidence(DraftCommitBinding binding) {
    return new DraftSynchronizedVisibilityEvidence(
        request(),
        binding,
        "SYNCHRONIZED",
        new DraftSynchronizedVisibilityEvidence.Fence(
            REQUEST_ID,
            COMMIT_ID,
            binding.digest(),
            resultVector(binding),
            java.time.OffsetDateTime.parse("2026-10-06T00:00:00Z")));
  }

  private static DraftSynchronizedVisibilityEvidence.Request request() {
    return new DraftSynchronizedVisibilityEvidence.Request(1, "test", READ_ID, target());
  }

  private static TargetProof target() {
    return new TargetProof(TENANT_ID, VERSION_ID, 19L, "tenant-key", 42L, "tenant-key", "NEW_GAME_ROW");
  }

  private static DraftCommitBinding binding() {
    return DraftCommitBinding.create(
        target(), REQUEST_ID, COMMIT_ID, "base-source-1",
        List.of(new RevisionPayload("0", uuid("66666666-6666-4666-8666-666666666666"), Owner.WORLD_MANAGEMENT, "{}")),
        List.of(new AffectedUnit(Owner.WORLD_MANAGEMENT, "WORLD_TEMPLATE", "world-1", "ROOM_SCOPE", "room-1", "0")));
  }

  private static String resultVector(DraftCommitBinding binding) {
    AffectedUnit unit = binding.affectedUnits(Owner.WORLD_MANAGEMENT).getFirst();
    Map<String, Object> epoch = new LinkedHashMap<>();
    epoch.put("aggregateType", unit.aggregateType());
    epoch.put("aggregateId", unit.aggregateId());
    epoch.put("scopeType", unit.scopeType());
    epoch.put("scopeId", unit.scopeId());
    epoch.put("expectedEpoch", unit.expectedEpoch());
    epoch.put("resultingEpoch", "1");
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("owner", Owner.WORLD_MANAGEMENT.name());
    item.put("status", "APPLIED");
    item.put("commitId", COMMIT_ID.toString());
    item.put("bindingDigest", binding.digest());
    item.put("resultIdentity", "world-result");
    item.put("resultBytesBase64", Base64.getEncoder().encodeToString("result".getBytes(StandardCharsets.UTF_8)));
    item.put("appliedEpochs", List.of(epoch));
    return canonical(new ObjectMapper().writeValueAsString(List.of(item)));
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
