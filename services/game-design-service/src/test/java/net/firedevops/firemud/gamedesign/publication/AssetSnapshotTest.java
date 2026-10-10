package net.firedevops.firemud.gamedesign.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.gamedesign.AssetSource;
import net.firedevops.firemud.common.gamedesign.CommandSource;
import org.junit.jupiter.api.Test;

class AssetSnapshotTest {
  @Test
  void exactTypedSnapshotRoundTripsAndOwnsItsImmutableCollection() {
    var binding = binding();
    var reference = AssetSource.replay(List.of(), binding).references().getFirst();
    var item =
        new net.firedevops.firemud.common.gamedesign.AssetSnapshot.Item(
            reference, "image/png", CommandSource.sha256(new byte[] {1, 2}), 2);
    var mutable = new ArrayList<>(List.of(item));
    var snapshot = new AssetSnapshot(binding, "1", null, UUID.randomUUID(), mutable);
    mutable.clear();
    assertThat(AssetSnapshot.fromStored(snapshot.canonicalJson())).isEqualTo(snapshot);
    assertThat(snapshot.items()).containsExactly(item);
    assertThatThrownBy(() -> snapshot.items().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(snapshot.items().getFirst().reference().family()).isEqualTo("ORDINARY");
    assertThat(snapshot.items().getFirst().reference().role()).isEqualTo("RESOURCE");
    assertThat(new AssetApplication(binding, "0", snapshot).appliedEpoch().resultingEpoch())
        .isEqualTo("1");
    assertThatThrownBy(() -> new AssetApplication(binding, "1", snapshot))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void storedSnapshotCannotChangeBoundRequirednessIdentityOrCanonicalRepresentation() {
    var binding = binding();
    var reference = AssetSource.replay(List.of(), binding).references().getFirst();
    var snapshot =
        new AssetSnapshot(
            binding,
            "1",
            null,
            UUID.randomUUID(),
            List.of(
                new net.firedevops.firemud.common.gamedesign.AssetSnapshot.Item(
                    reference, "image/png", CommandSource.sha256(new byte[0]), 0)));
    String json = snapshot.canonicalJson();
    for (String invalid :
        List.of(
            json.replace("\"requiredness\":\"REQUIRED\"", "\"requiredness\":\"OPTIONAL\""),
            json.replace("\"family\":\"ORDINARY\"", "\"family\":\"UNKNOWN\""),
            json.replace("\"role\":\"RESOURCE\"", "\"role\":\"PRESENTATION\""),
            " " + json)) {
      assertThatThrownBy(() -> AssetSnapshot.fromStored(invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(
            () ->
                new net.firedevops.firemud.common.gamedesign.AssetSnapshot.Item(
                    reference, "", CommandSource.sha256(new byte[0]), 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new net.firedevops.firemud.common.gamedesign.AssetSnapshot.Item(
                    reference, "image/png", "sha256:00", 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new net.firedevops.firemud.common.gamedesign.AssetSnapshot.Item(
                    reference, "image/png", CommandSource.sha256(new byte[0]), -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static DraftCommitBinding binding() {
    var target =
        new DraftCommitBinding.TargetProof(
            UUID.randomUUID(), UUID.randomUUID(), 1, "tenant", 2, "tenant", "NEW_GAME_ROW");
    return DraftCommitBinding.create(
        target,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "base-commit-0",
        List.of(
            new DraftCommitBinding.RevisionPayload(
                "0",
                UUID.randomUUID(),
                DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                AssetSource.upsertPayload("3", "logo.png", AssetSource.Requiredness.REQUIRED))),
        List.of(
            new DraftCommitBinding.AffectedUnit(
                DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                AssetSource.SCOPE,
                target.canonicalVersionId().toString(),
                AssetSource.SCOPE,
                AssetSource.SCOPE_ID,
                "0")));
  }
}
