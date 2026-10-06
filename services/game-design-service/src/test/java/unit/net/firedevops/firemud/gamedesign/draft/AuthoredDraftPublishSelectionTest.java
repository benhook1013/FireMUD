package unit.net.firedevops.firemud.gamedesign.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;
import net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelection;
import net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelection.PublishIntent;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.PublicationEvidence;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.VisibilityFence;
import org.junit.jupiter.api.Test;

class AuthoredDraftPublishSelectionTest {
  private static final String LARGE_COUNTER = "900719925474099312345678901234567890";

  @Test
  void selectionRetainsExactIntentFullCommitAndSynchronizedEvidence() throws Exception {
    TargetProof target = target();
    DraftCommitBinding binding = binding(target);
    VisibilityFence fence =
        new VisibilityFence(
            target,
            binding.requestId(),
            binding.commitId(),
            binding.digest(),
            "[{\"appliedEpochs\":[{\"aggregateId\":\"world-17\","
                + "\"aggregateType\":\"WORLD_TEMPLATE\",\"expectedEpoch\":\""
                + LARGE_COUNTER
                + "\",\"resultingEpoch\":\"900719925474099312345678901234567891\","
                + "\"scopeId\":\"room-scope-5\",\"scopeType\":\"ROOM_SCOPE\"}],"
                + "\"bindingDigest\":\""
                + binding.digest()
                + "\",\"commitId\":\""
                + binding.commitId()
                + "\",\"owner\":\"WORLD_MANAGEMENT\",\"resultBytesBase64\":\"YXBwbGllZA==\","
                + "\"resultIdentity\":\"world-result-1\",\"status\":\"APPLIED\"}]",
            OffsetDateTime.parse("2026-10-05T00:00:00Z"));
    PublishIntent intent =
        new PublishIntent(
            target.canonicalTenantId(),
            target.canonicalVersionId(),
            "publish-request-é",
            LARGE_COUNTER,
            "Exact creator notes — preserve spacing  ",
            binding.requestId(),
            binding.commitId(),
            binding.digest());

    AuthoredDraftPublishSelection selection =
        AuthoredDraftPublishSelection.capture(
            intent, target, new PublicationEvidence(binding, fence));
    AuthoredDraftPublishSelection restored =
        AuthoredDraftPublishSelection.fromStored(selection.canonicalJson(), selection.digest());

    assertThat(restored).isEqualTo(selection);
    assertThat(restored.intent().notes()).isEqualTo(intent.notes());
    assertThat(restored.intent().expectedVersionStateEpoch()).isEqualTo(LARGE_COUNTER);
    assertThat(restored.canonicalJson())
        .contains("\"sourceGameRowId\":\"9007199254740995\"")
        .contains("\"expectedVersionStateEpoch\":\"" + LARGE_COUNTER + "\"");
    assertThat(restored.selectedCommit().canonicalJson()).isEqualTo(binding.canonicalJson());
    assertThat(restored.fenceResultVectorJson()).isEqualTo(fence.resultVectorJson());
    assertThat(restored.fenceCreatedAt()).isEqualTo("2026-10-05T00:00:00Z");
    // Independent original V43 object layout. Extraction must preserve its exact bytes/digest.
    byte[] legacy = Rfc8785CanonicalJson.canonicalizeUtf8(new ObjectMapper().writeValueAsString(
        Map.of("schemaVersion", "1",
            "intent", Map.of("canonicalTenantId", intent.canonicalTenantId().toString(),
                "canonicalVersionId", intent.canonicalVersionId().toString(),
                "publishRequestId", intent.publishRequestId(),
                "expectedVersionStateEpoch", intent.expectedVersionStateEpoch(),
                "notes", intent.notes(), "selectedCommitRequestId", intent.selectedCommitRequestId().toString(),
                "selectedCommitId", intent.selectedCommitId().toString(),
                "selectedCommitDigest", intent.selectedCommitDigest()),
            "target", Map.of("canonicalTenantId", target.canonicalTenantId().toString(),
                "canonicalVersionId", target.canonicalVersionId().toString(),
                "gameDesignVersionRowId", Long.toString(target.gameDesignVersionRowId()),
                "gameDesignVersionTenantKey", target.gameDesignVersionTenantKey(),
                "sourceGameRowId", Long.toString(target.sourceGameRowId()),
                "sourceGameTenantKey", target.sourceGameTenantKey(),
                "sourceProvenanceKind", target.sourceProvenanceKind()),
            "selectedCommitBindingJson", binding.canonicalJson(), "selectedCommitDigest", binding.digest(),
            "synchronizedFence", Map.of("requestId", fence.requestId().toString(),
                "commitId", fence.commitId().toString(), "inputDigest", fence.inputDigest(),
                "resultVectorJson", fence.resultVectorJson(), "createdAt", "2026-10-05T00:00:00Z"))));
    assertThat(selection.canonicalBytes()).containsExactly(legacy);
    assertThat(selection.digest()).isEqualTo(DraftAuthorizationFenceBinding.digest(legacy));
    var shared = AuthoredDraftPublishSelectionBinding.fromStored(selection.canonicalJson(), selection.digest());
    assertThat(shared.canonicalBytes()).containsExactly(legacy);
  }

  @Test
  void rejectsInvalidSelectionEpochAndChangedIntent() {
    TargetProof target = target();
    DraftCommitBinding binding = binding(target);
    PublishIntent original = intent(target, binding, "original notes");

    assertThatThrownBy(
            () ->
                new PublishIntent(
                    target.canonicalTenantId(),
                    target.canonicalVersionId(),
                    original.publishRequestId(),
                    "01",
                    original.notes(),
                    binding.requestId(),
                    binding.commitId(),
                    binding.digest()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive canonical decimal");
    assertThat(original).isNotEqualTo(intent(target, binding, "changed notes"));
  }

  private static TargetProof target() {
    return new TargetProof(
        uuid("11111111-1111-4111-8111-111111111111"),
        uuid("22222222-2222-4222-8222-222222222222"),
        9_007_199_254_740_993L,
        "tenant-source",
        9_007_199_254_740_995L,
        "tenant-source",
        "NEW_GAME_ROW");
  }

  private static DraftCommitBinding binding(TargetProof target) {
    return DraftCommitBinding.create(
        target,
        uuid("33333333-3333-4333-8333-333333333333"),
        uuid("44444444-4444-4444-8444-444444444444"),
        "base-commit-exact-9",
        List.of(
            new RevisionPayload(
                "0",
                uuid("55555555-5555-4555-8555-555555555555"),
                Owner.WORLD_MANAGEMENT,
                "{\"fullTypedMutation\":{\"unknown\":\"retained\"}}")),
        List.of(
            new AffectedUnit(
                Owner.WORLD_MANAGEMENT,
                "WORLD_TEMPLATE",
                "world-17",
                "ROOM_SCOPE",
                "room-scope-5",
                LARGE_COUNTER)));
  }

  private static PublishIntent intent(
      TargetProof target, DraftCommitBinding binding, String notes) {
    return new PublishIntent(
        target.canonicalTenantId(),
        target.canonicalVersionId(),
        "publish-request-7",
        LARGE_COUNTER,
        notes,
        binding.requestId(),
        binding.commitId(),
        binding.digest());
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
