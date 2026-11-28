package net.firedevops.firemud.common.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import org.junit.jupiter.api.Test;

/** Isolated fixture currentness is stipulated, not a legal/verification producer. */
class AccountPublicationAuthorizationBindingTest {
  @Test
  void roundTripRetainsNestedSelectionAndSeparatesInputFromAllocatedFencesAndCapture() {
    var input =
        new AccountPublicationAuthorizationBinding.PreallocationInput(
            UUID.randomUUID(), selection("notes"));
    var source =
        new SourceEvidence(
            SourceKind.ACCOUNT,
            input.actorAccountId().toString(),
            "3",
            "4",
            null,
            null,
            new byte[] {9});
    var original =
        new AccountPublicationAuthorizationBinding(
            UUID.randomUUID(), UUID.randomUUID(), input, List.of(source));
    var restored = AccountPublicationAuthorizationBinding.fromStored(original.canonicalBytes());
    assertThat(restored.canonicalBytes()).isEqualTo(original.canonicalBytes());
    var reallocated =
        new AccountPublicationAuthorizationBinding(
            UUID.randomUUID(), UUID.randomUUID(), input, List.of(source));
    assertThat(reallocated.input().digest()).isEqualTo(input.digest());
    assertThat(reallocated.canonicalBytes()).isNotEqualTo(original.canonicalBytes());
    assertThat(restored.input().selection().digest()).isEqualTo(input.selection().digest());
    var recaptured =
        new AccountPublicationAuthorizationBinding(
            original.operationId(),
            original.fenceId(),
            input,
            List.of(
                new SourceEvidence(
                    SourceKind.ACCOUNT,
                    input.actorAccountId().toString(),
                    "4",
                    "5",
                    null,
                    null,
                    new byte[] {10})));
    assertThat(recaptured.input().digest()).isEqualTo(input.digest());
    assertThat(recaptured.canonicalBytes()).isNotEqualTo(original.canonicalBytes());
  }

  @Test
  void rejectsDifferentActorScopeDuplicateSourcesAndWrongOperationDomain() {
    var input =
        new AccountPublicationAuthorizationBinding.PreallocationInput(
            UUID.randomUUID(), selection("notes"));
    var source =
        new SourceEvidence(
            SourceKind.ACCOUNT, UUID.randomUUID().toString(), "1", "1", null, null, new byte[] {1});
    assertThatThrownBy(
            () ->
                new AccountPublicationAuthorizationBinding(
                    UUID.randomUUID(), UUID.randomUUID(), input, List.of(source)))
        .isInstanceOf(IllegalArgumentException.class);
    var exact =
        new SourceEvidence(
            SourceKind.ACCOUNT,
            input.actorAccountId().toString(),
            "1",
            "1",
            null,
            null,
            new byte[] {1});
    assertThatThrownBy(
            () ->
                new AccountPublicationAuthorizationBinding(
                    UUID.randomUUID(), UUID.randomUUID(), input, List.of(exact, exact)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> AccountPublicationAuthorizationBinding.fromStored(exact.canonicalBytes()))
        .isInstanceOf(IllegalArgumentException.class);
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
