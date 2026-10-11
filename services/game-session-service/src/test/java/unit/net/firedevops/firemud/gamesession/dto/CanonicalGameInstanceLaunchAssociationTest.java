package unit.net.firedevops.firemud.gamesession.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.gamesession.dto.CanonicalGameInstanceLaunchAssociation;
import org.junit.jupiter.api.Test;

class CanonicalGameInstanceLaunchAssociationTest {
  private static final String NAMESPACE = "launch-owner";
  private static final UUID TENANT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID INSTANCE_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID ASSOCIATION_ID =
      UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID STATE_NAMESPACE_ID =
      UUID.fromString("44444444-4444-4444-8444-444444444444");

  @Test
  void retainsTheCompleteBindingAndSeparatesCapturedFromCurrentOwnerProjection() {
    CompleteLaunchBindingEvidence evidence = evidence();
    CanonicalGameInstanceLaunchAssociation association =
        new CanonicalGameInstanceLaunchAssociation(
            NAMESPACE,
            701L,
            ASSOCIATION_ID,
            TENANT_ID,
            INSTANCE_ID,
            "earth",
            STATE_NAMESPACE_ID,
            RealmEntryPolicy.StateScope.SHARED,
            true,
            "launch-request-1",
            "descriptor-902",
            3L,
            evidence,
            CanonicalGameInstanceLaunchAssociation.CurrentGameInstanceStatus.RUNNING,
            8L);

    assertThat(association.capturedStartingRowVersion()).isEqualTo(3L);
    assertThat(association.currentRowVersion()).isEqualTo(8L);
    assertThat(association.currentGameInstanceStatus())
        .isEqualTo(CanonicalGameInstanceLaunchAssociation.CurrentGameInstanceStatus.RUNNING);
    assertThat(association.launchBindingEvidence()).isSameAs(evidence);
  }

  @Test
  void rejectsNonSharedAndNonProductionLaunchAssociations() {
    assertThatThrownBy(
            () -> association(evidence(), RealmEntryPolicy.StateScope.ISOLATED, true, 3L, 3L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("SHARED");
    assertThatThrownBy(
            () -> association(evidence(), RealmEntryPolicy.StateScope.SHARED, false, 3L, 3L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("public production");
  }

  @Test
  void rejectsSelectorsAndCurrentRowVersionsThatDifferFromTheOwnerEvidence() {
    assertThatThrownBy(
            () ->
                new CanonicalGameInstanceLaunchAssociation(
                    NAMESPACE,
                    701L,
                    ASSOCIATION_ID,
                    TENANT_ID,
                    INSTANCE_ID,
                    "mars",
                    STATE_NAMESPACE_ID,
                    RealmEntryPolicy.StateScope.SHARED,
                    true,
                    "launch-request-1",
                    "descriptor-902",
                    3L,
                    evidence(),
                    CanonicalGameInstanceLaunchAssociation.CurrentGameInstanceStatus.STARTING,
                    3L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not match");
    assertThatThrownBy(
            () -> association(evidence(), RealmEntryPolicy.StateScope.SHARED, true, 4L, 3L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not precede");
  }

  private static CanonicalGameInstanceLaunchAssociation association(
      CompleteLaunchBindingEvidence evidence,
      RealmEntryPolicy.StateScope scope,
      boolean publicProduction,
      long capturedVersion,
      long currentVersion) {
    return new CanonicalGameInstanceLaunchAssociation(
        NAMESPACE,
        701L,
        ASSOCIATION_ID,
        TENANT_ID,
        INSTANCE_ID,
        "earth",
        STATE_NAMESPACE_ID,
        scope,
        publicProduction,
        "launch-request-1",
        "descriptor-902",
        capturedVersion,
        evidence,
        CanonicalGameInstanceLaunchAssociation.CurrentGameInstanceStatus.STARTING,
        currentVersion);
  }

  private static CompleteLaunchBindingEvidence evidence() {
    UUID sourceOperationId = UUID.fromString("55555555-5555-4555-8555-555555555555");
    String sourceEvidenceDigest = "sha256:" + "a".repeat(64);
    AuthoredWorldLaunchDescriptorEvidence.Request request =
        new AuthoredWorldLaunchDescriptorEvidence.Request(
            NAMESPACE,
            "launch-request-1",
            TENANT_ID,
            "earth",
            sourceOperationId,
            sourceEvidenceDigest,
            810L,
            false,
            null,
            false,
            null,
            false,
            null,
            false,
            null);
    AuthoredWorldLaunchDescriptorEvidence descriptor =
        AuthoredWorldLaunchDescriptorEvidence.create(
            request,
            "descriptor-902",
            902L,
            false,
            null,
            "{}",
            "generation-4",
            4L,
            903L,
            "prb:tenant:902:903",
            false,
            null);
    List<AuthoredWorldReleaseAttestationEvidence.Participant> participants =
        AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder().stream()
            .map(
                owner ->
                    new AuthoredWorldReleaseAttestationEvidence.Participant(
                        owner,
                        "902",
                        false,
                        null,
                        "publish-commit-902",
                        "c".repeat(64),
                        AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                            owner),
                        "GAME_LOGIC".equals(owner),
                        "GAME_LOGIC".equals(owner) ? "sha256:" + "d".repeat(64) : null))
            .toList();
    AuthoredWorldReleaseAttestationEvidence attestation =
        AuthoredWorldReleaseAttestationEvidence.create(
            NAMESPACE,
            descriptor.resultDigest(),
            TENANT_ID,
            UUID.fromString("66666666-6666-4666-8666-666666666666"),
            "earth",
            sourceOperationId,
            sourceEvidenceDigest,
            descriptor.launchDescriptorId(),
            descriptor.publishedReleaseBundleRef(),
            descriptor.versionStateEpoch(),
            "publish:tenant:version:request",
            "publish-commit-902",
            participants,
            "sha256:" + "b".repeat(64),
            1,
            List.of(),
            List.of(),
            List.of(),
            descriptor.generationConfigRevision());
    return new CompleteLaunchBindingEvidence(descriptor, attestation);
  }
}
