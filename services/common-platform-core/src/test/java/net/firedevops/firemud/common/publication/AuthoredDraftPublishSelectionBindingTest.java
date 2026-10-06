package net.firedevops.firemud.common.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import org.junit.jupiter.api.Test;

/**
 * Codec-only fixtures stipulate upstream synchronized evidence; they grant no publication
 * authority.
 */
class AuthoredDraftPublishSelectionBindingTest {
  @Test
  void preservesRetainedBytesDigestLargeCountersAndDefensiveCopies() {
    var original = selection(" exact notes —  ");
    var restored =
        AuthoredDraftPublishSelectionBinding.fromStored(
            original.canonicalJson(), original.digest());
    assertThat(restored).isEqualTo(original);
    assertThat(restored.digest())
        .isEqualTo(DraftAuthorizationFenceBinding.digest(original.canonicalBytes()));
    assertThat(restored.intent().expectedVersionStateEpoch()).isEqualTo("900719925474099312345");
    assertThat(restored.canonicalJson())
        .contains("\"gameDesignVersionRowId\":\"9007199254740993\"");
    byte[] copy = restored.canonicalBytes();
    copy[0] = 0;
    assertThat(restored.canonicalBytes()).isEqualTo(original.canonicalBytes());
  }

  @Test
  void changedNotesChangeCompleteSelectionDigest() {
    assertThat(selection("old").digest()).isNotEqualTo(selection("new").digest());
  }

  @Test
  void everyCallerSelectionFieldAndSynchronizedEvidenceIsBound() {
    var original = selection("notes");
    String json = original.canonicalJson();
    for (String changed :
        List.of(
            json.replace("publication-é", "publication-other"),
            json.replace(
                "\"expectedVersionStateEpoch\":\"900719925474099312345\"",
                "\"expectedVersionStateEpoch\":\"900719925474099312346\""),
            json.replace("\"notes\":\"notes\"", "\"notes\":\"changed\""),
            json.replace("2026-10-05T00:00:00Z", "2026-10-05T00:00:01Z"),
            json.replace("\"resultVectorJson\":\"[]\"", "\"resultVectorJson\":\"[1]\""))) {
      String digest =
          DraftAuthorizationFenceBinding.digest(
              changed.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      assertThat(digest).isNotEqualTo(original.digest());
      assertThat(AuthoredDraftPublishSelectionBinding.fromStored(changed, digest).canonicalJson())
          .isEqualTo(changed);
    }
    for (String field :
        List.of(
            "canonicalTenantId",
            "canonicalVersionId",
            "selectedCommitRequestId",
            "selectedCommitId")) {
      String changed =
          json.replaceFirst(
              "\\\"" + field + "\\\":\\\"[0-9a-f-]+\\\"",
              "\"" + field + "\":\"66666666-6666-4666-8666-666666666666\"");
      assertThatThrownBy(
              () ->
                  AuthoredDraftPublishSelectionBinding.fromStored(
                      changed,
                      DraftAuthorizationFenceBinding.digest(
                          changed.getBytes(java.nio.charset.StandardCharsets.UTF_8))))
          .isInstanceOf(IllegalStateException.class);
    }
    String changedDigest =
        json.replaceFirst(
            "\\\"selectedCommitDigest\\\":\\\"sha256:[0-9a-f]{64}\\\"",
            "\"selectedCommitDigest\":\"sha256:" + "0".repeat(64) + "\"");
    assertThatThrownBy(
            () ->
                AuthoredDraftPublishSelectionBinding.fromStored(
                    changedDigest,
                    DraftAuthorizationFenceBinding.digest(
                        changedDigest.getBytes(java.nio.charset.StandardCharsets.UTF_8))))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void corruptNestedCommitOrSynchronizedFenceFailsEvenWithRecomputedOuterDigest() {
    var original = selection("notes");
    String corrupt =
        original.canonicalJson().replace("\"inputDigest\":\"", "\"inputDigest\":\"changed-");
    assertThatThrownBy(
            () ->
                AuthoredDraftPublishSelectionBinding.fromStored(
                    corrupt,
                    DraftAuthorizationFenceBinding.digest(
                        corrupt.getBytes(java.nio.charset.StandardCharsets.UTF_8))))
        .isInstanceOf(IllegalStateException.class);
    String changed = original.canonicalJson().replace("900719925474099312345", "01");
    assertThatThrownBy(
            () ->
                AuthoredDraftPublishSelectionBinding.fromStored(
                    changed,
                    DraftAuthorizationFenceBinding.digest(
                        changed.getBytes(java.nio.charset.StandardCharsets.UTF_8))))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                AuthoredDraftPublishSelectionBinding.fromStored(
                    original.canonicalJson() + " ", original.digest()))
        .isInstanceOf(IllegalStateException.class);
  }

  private static AuthoredDraftPublishSelectionBinding selection(String notes) {
    var target =
        new DraftCommitBinding.TargetProof(
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
            9007199254740993L,
            "tenant-source",
            9007199254740995L,
            "tenant-source",
            "NEW_GAME_ROW");
    var commit =
        DraftCommitBinding.create(
            target,
            UUID.fromString("33333333-3333-4333-8333-333333333333"),
            UUID.fromString("44444444-4444-4444-8444-444444444444"),
            "base",
            List.of(
                new DraftCommitBinding.RevisionPayload(
                    "0",
                    UUID.fromString("55555555-5555-4555-8555-555555555555"),
                    DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                    "{\"mutation\":\"fixture\"}")),
            List.of(
                new DraftCommitBinding.AffectedUnit(
                    DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                    "WORLD_TEMPLATE",
                    "world",
                    "ROOM_SCOPE",
                    "room",
                    "900719925474099312345")));
    return AuthoredDraftPublishSelectionBinding.capture(
        new AuthoredDraftPublishSelectionBinding.PublishIntent(
            target.canonicalTenantId(),
            target.canonicalVersionId(),
            "publication-é",
            "900719925474099312345",
            notes,
            commit.requestId(),
            commit.commitId(),
            commit.digest()),
        target,
        commit,
        new AuthoredDraftPublishSelectionBinding.VisibilityFence(
            target,
            commit.requestId(),
            commit.commitId(),
            commit.digest(),
            "[]",
            OffsetDateTime.parse("2026-10-05T00:00:00Z")));
  }
}
