package net.firedevops.firemud.common.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class WorldPublishedSpawnRequirementsEvidenceTest {
  @Test
  void retainsImmutableOriginalRequirementsAndArbitraryPrecisionRevisionOrder() {
    var evidence = WorldPublishedSpawnRequirementsGrpcCodecTest.evidence();

    assertThat(evidence.spawnRequirements()).hasSize(2);
    assertThat(evidence.spawnRequirements().get(0).revisionOrder()).isEqualTo("9007199254740993");
    assertThat(evidence.spawnRequirements().get(0).spawnCount()).isEqualTo(7);
    assertThat(evidence.spawnRequirements().get(0).respawnDelaySeconds()).isEqualTo(2700);
    assertThat(evidence.spawnRequirements().get(0).roomTemplate().tenantId())
        .isEqualTo(evidence.launchBinding().releaseAttestation().canonicalTenantId());
    assertThat(evidence.spawnRequirements().get(0).entityTemplate().kind().name())
        .isEqualTo("ENTITY_TEMPLATE_REFERENCE_TYPE_ITEM");
    assertThat(evidence.generationRequirements().get(0).revisionOrder())
        .isEqualTo("9007199254740992");
    assertThat(evidence.generationRequirements().get(0).value()).isEqualTo(" authored value ");
    assertThatThrownBy(evidence.spawnRequirements()::clear)
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(evidence.generationRequirements()::clear)
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void deniesDuplicateIdentityAndNonIncreasingRevisionOrder() {
    var evidence = WorldPublishedSpawnRequirementsGrpcCodecTest.evidence();
    var first = evidence.spawnRequirements().get(0);
    var second = evidence.spawnRequirements().get(1);

    assertThatThrownBy(
            () ->
                new WorldPublishedSpawnRequirementsEvidence(
                    evidence.request(),
                    evidence.launchBinding(),
                    evidence.captureId(),
                    evidence.graphDigest(),
                    evidence.familyCounts(),
                    List.of(
                        first,
                        new WorldPublishedSpawnRequirementsEvidence.SpawnRequirement(
                            "9007199254740994",
                            second.revisionId(),
                            first.spawnBindingId(),
                            second.roomTemplate(),
                            second.entityTemplate(),
                            second.spawnCount(),
                            second.respawnDelaySeconds())),
                    evidence.generationRequirements()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("binding identity");

    assertThatThrownBy(
            () ->
                new WorldPublishedSpawnRequirementsEvidence(
                    evidence.request(),
                    evidence.launchBinding(),
                    evidence.captureId(),
                    evidence.graphDigest(),
                    evidence.familyCounts(),
                    List.of(second, first),
                    evidence.generationRequirements()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("strictly increasing revision order");
  }

  @Test
  void bindsGraphDigestToTheReleaseSelectorReceipt() {
    var evidence = WorldPublishedSpawnRequirementsGrpcCodecTest.evidence();

    assertThatThrownBy(
            () ->
                new WorldPublishedSpawnRequirementsEvidence(
                    evidence.request(),
                    evidence.launchBinding(),
                    evidence.captureId(),
                    "sha256:" + "f".repeat(64),
                    evidence.familyCounts(),
                    evidence.spawnRequirements(),
                    evidence.generationRequirements()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("graph digest");
  }
}
