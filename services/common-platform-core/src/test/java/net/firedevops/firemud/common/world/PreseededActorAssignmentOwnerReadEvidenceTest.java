package net.firedevops.firemud.common.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.UnknownFieldSet;
import java.time.Instant;
import java.util.UUID;
import net.firedevops.firemud.gamesession.v1.GetPreseededActorAssignmentOwnerReadResponse;
import org.junit.jupiter.api.Test;

class PreseededActorAssignmentOwnerReadEvidenceTest {
  private static final String TARGET_NAMESPACE = "gameplay";
  private static final UUID ACCOUNT = uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID REALM = uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
  private static final UUID PLAYABLE_NAMESPACE = uuid("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
  private static final UUID INSTANCE = uuid("dddddddd-dddd-4ddd-8ddd-dddddddddddd");
  private static final Instant TERMINAL_AT = Instant.parse("2026-10-08T03:04:05Z");

  @Test
  void assignmentRequestOmitsCountersAndResponseCarriesCountersOnlyFromOwnerProof()
      throws Exception {
    var evidence = fixture();
    var request = evidence.request();
    var wireRequest = PreseededActorAssignmentOwnerReadGrpcCodec.toRequest(request);

    assertThat(wireRequest.getAllFields().keySet())
        .noneMatch(
            field -> field.getName().contains("pointer") || field.getName().contains("epoch"));
    assertThat(PreseededActorAssignmentOwnerReadGrpcCodec.fromRequest(wireRequest))
        .isEqualTo(request);
    assertThat(evidence.sourceEvidence().request().expectedPointerVersion()).isEqualTo(73L);
    assertThat(evidence.sourceEvidence().request().expectedActiveWorldEpoch()).isEqualTo(82L);

    var response = PreseededActorAssignmentOwnerReadGrpcCodec.toResponse(request, evidence);
    assertThat(response.hasRequest()).isTrue();
    assertThat(response.getRequest()).isEqualTo(wireRequest);
    assertThat(PreseededActorAssignmentOwnerReadGrpcCodec.fromResponse(request, response))
        .isEqualTo(evidence);
  }

  @Test
  void rejectsChangedSelectorsPolicyReleaseUnknownFieldsAndPartialSourceEvidence()
      throws Exception {
    var evidence = fixture();
    var request = evidence.request();
    var wireRequest = PreseededActorAssignmentOwnerReadGrpcCodec.toRequest(request);
    var response = PreseededActorAssignmentOwnerReadGrpcCodec.toResponse(request, evidence);

    var changedDigest =
        new PreseededActorAssignmentOwnerReadEvidence.Request(
            request.assignmentUuid(),
            request.canonicalAccountUuid(),
            request.targetNamespace(),
            request.canonicalTenantUuid(),
            request.worldSlug(),
            request.realmUuid(),
            request.realmSlug(),
            request.playableStateNamespaceUuid(),
            request.playableStateScope(),
            request.canonicalGameInstanceUuid(),
            request.canonicalVersionUuid(),
            request.expectedCatalogRevision(),
            "9".repeat(64),
            request.expectedPublishedReleaseBundleRef());
    assertThatThrownBy(
            () ->
                new PreseededActorAssignmentOwnerReadEvidence(
                    changedDigest, evidence.sourceEvidence()))
        .isInstanceOf(IllegalArgumentException.class);

    var changedRelease =
        new PreseededActorAssignmentOwnerReadEvidence.Request(
            request.assignmentUuid(),
            request.canonicalAccountUuid(),
            request.targetNamespace(),
            request.canonicalTenantUuid(),
            request.worldSlug(),
            request.realmUuid(),
            request.realmSlug(),
            request.playableStateNamespaceUuid(),
            request.playableStateScope(),
            request.canonicalGameInstanceUuid(),
            request.canonicalVersionUuid(),
            request.expectedCatalogRevision(),
            request.expectedPolicyDigest(),
            "substituted-release");
    assertThatThrownBy(
            () ->
                new PreseededActorAssignmentOwnerReadEvidence(
                    changedRelease, evidence.sourceEvidence()))
        .isInstanceOf(IllegalArgumentException.class);

    var unknownRequest =
        wireRequest.toBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(100, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
                    .build())
            .build();
    assertThatThrownBy(() -> PreseededActorAssignmentOwnerReadGrpcCodec.fromRequest(unknownRequest))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                PreseededActorAssignmentOwnerReadGrpcCodec.fromResponse(
                    request, GetPreseededActorAssignmentOwnerReadResponse.getDefaultInstance()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                PreseededActorAssignmentOwnerReadGrpcCodec.fromResponse(
                    request,
                    response.toBuilder()
                        .setRequest(wireRequest.toBuilder().setRealmSlug("other").build())
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static PreseededActorAssignmentOwnerReadEvidence fixture() throws Exception {
    var policySet = PublishedRealmEntryPolicySetEvidenceTest.preseededSetFixture();
    var selectedPolicy = policySet.policies().getFirst();
    UUID tenant = policySet.target().canonicalTenantId();
    UUID version = policySet.target().canonicalVersionId();
    var holdRequest =
        new WorldCanonicalInitialAdmissionHold.Request(
            TARGET_NAMESPACE,
            tenant,
            "earth",
            REALM,
            PLAYABLE_NAMESPACE,
            "SHARED",
            INSTANCE,
            version,
            82L,
            "first-open-request-1",
            "a".repeat(64),
            WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.NO_PRIOR_POINTER,
            71L,
            null);
    var hold =
        new WorldCanonicalInitialAdmissionHold.HoldIdentity(
            holdRequest,
            uuid("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
            uuid("ffffffff-ffff-4fff-8fff-ffffffffffff"));
    var proof =
        new GameSessionCanonicalInitialAdmissionOwnerProof(
            hold,
            GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED,
            73L,
            91L,
            "sha256:" + "b".repeat(64),
            false,
            TERMINAL_AT);
    var sourceRequest =
        new CanonicalGameplayRosterOwnerReadEvidence.Request(
            uuid("11111111-1111-4111-8111-111111111111"),
            ACCOUNT,
            TARGET_NAMESPACE,
            tenant,
            "earth",
            REALM,
            "main",
            PLAYABLE_NAMESPACE,
            "SHARED",
            INSTANCE,
            version,
            71L,
            73L,
            82L);
    var sourceEvidence =
        new CanonicalGameplayRosterOwnerReadEvidence(
            sourceRequest, "1".repeat(64), proof, policySet);
    var request =
        new PreseededActorAssignmentOwnerReadEvidence.Request(
            sourceRequest.requestUuid(),
            ACCOUNT,
            TARGET_NAMESPACE,
            tenant,
            "earth",
            REALM,
            "main",
            PLAYABLE_NAMESPACE,
            "SHARED",
            INSTANCE,
            version,
            71L,
            raw(selectedPolicy.policyDigest()),
            policySet.publishedReleaseBundleRef());
    return new PreseededActorAssignmentOwnerReadEvidence(request, sourceEvidence);
  }

  private static String raw(String digest) {
    return digest.startsWith("sha256:") ? digest.substring("sha256:".length()) : digest;
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
