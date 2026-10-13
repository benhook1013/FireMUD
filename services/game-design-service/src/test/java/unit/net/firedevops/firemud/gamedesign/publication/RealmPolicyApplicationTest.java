package net.firedevops.firemud.gamedesign.publication;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.AppliedEpoch;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.OwnerOutcome;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.OwnerStatus;
import org.junit.jupiter.api.Test;

class RealmPolicyApplicationTest {
  @Test
  void genesisApplicationFramesAbsentInheritedCommitAsEmptyBytesAndBindsOwnerOutcome() {
    var target =
        new DraftCommitBinding.TargetProof(
            UUID.randomUUID(),
            UUID.randomUUID(),
            17L,
            "tenant-key",
            23L,
            "tenant-key",
            "NEW_GAME_ROW");
    var binding =
        DraftCommitBinding.create(
            target,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "genesis:" + UUID.randomUUID(),
            List.of(
                new DraftCommitBinding.RevisionPayload(
                    "0",
                    UUID.randomUUID(),
                    DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                    "world-revision")),
            List.of(
                new DraftCommitBinding.AffectedUnit(
                    DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                    "ROOM",
                    "room",
                    "ROOM",
                    "room",
                    "0")));
    var genesis = new RealmPolicyGenesis(target, UUID.randomUUID(), "1");
    var snapshot = new RealmPolicySnapshot(binding, "1", List.of());
    var application = new RealmPolicyApplication(genesis, null, "0", snapshot);

    ByteArrayOutputStream expected = new ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(expected, "game-design-realm-policy-application/v1");
    DraftAuthorizationFenceBinding.frame(expected, "true");
    DraftAuthorizationFenceBinding.frame(expected, genesis.canonicalBytes());
    DraftAuthorizationFenceBinding.frame(expected, "false");
    DraftAuthorizationFenceBinding.frame(expected, new byte[0]);
    DraftAuthorizationFenceBinding.frame(expected, "0");
    DraftAuthorizationFenceBinding.frame(expected, snapshot.canonicalBytes());

    var ownerOutcome = application.ownerOutcome();
    assertThat(application.canonicalBytes()).containsExactly(expected.toByteArray());
    assertThat(ownerOutcome)
        .isEqualTo(
            new OwnerOutcome(
                DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                OwnerStatus.APPLIED,
                binding.commitId(),
                binding.digest(),
                "realm-policy:" + binding.commitId(),
                expected.toByteArray(),
                List.of(
                    new AppliedEpoch(
                        RealmPolicySource.SCOPE,
                        target.canonicalVersionId().toString(),
                        RealmPolicySource.SCOPE,
                        "effective",
                        "0",
                        "1"))));

    UUID inheritedCommitId = UUID.randomUUID();
    var inherited = new RealmPolicyApplication(inheritedCommitId, "0", snapshot);
    ByteArrayOutputStream expectedInherited = new ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(
        expectedInherited, "game-design-realm-policy-application/v1");
    DraftAuthorizationFenceBinding.frame(expectedInherited, "false");
    DraftAuthorizationFenceBinding.frame(expectedInherited, new byte[0]);
    DraftAuthorizationFenceBinding.frame(expectedInherited, "true");
    DraftAuthorizationFenceBinding.frame(expectedInherited, inheritedCommitId.toString());
    DraftAuthorizationFenceBinding.frame(expectedInherited, "0");
    DraftAuthorizationFenceBinding.frame(expectedInherited, snapshot.canonicalBytes());

    assertThat(inherited.canonicalBytes()).containsExactly(expectedInherited.toByteArray());
    assertThat(inherited.canonicalBytes()).isNotEqualTo(application.canonicalBytes());
    assertThat(inherited.ownerOutcome().resultBytes())
        .containsExactly(expectedInherited.toByteArray());
  }
}
