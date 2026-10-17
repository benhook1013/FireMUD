package net.firedevops.firemud.common.gamesession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorGrpcCodec;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence.CurrentGameInstanceStatus;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence.Request;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.common.testing.AuthoringFixtures;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameInstanceLaunchAssociationResponse;
import org.junit.jupiter.api.Test;

class CanonicalGameInstanceLaunchAssociationReadGrpcCodecTest {
  private static final UUID READ_REQUEST_ID =
      UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID GAME_INSTANCE_ID =
      UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
  private static final UUID OWNER_ATTEMPT_ID =
      UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
  private static final UUID CANONICAL_REALM_ID =
      UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee");

  @Test
  void controlPlaneRequestIdUsesCanonicalUtf8ByteBoundAndUnicode() {
    String ascii128Bytes = "a".repeat(128);
    String multibyte128Bytes = "é".repeat(64);

    assertThat(requestWithControlPlaneRequestId(ascii128Bytes).controlPlaneRequestId())
        .isEqualTo(ascii128Bytes);
    assertThat(requestWithControlPlaneRequestId(multibyte128Bytes).controlPlaneRequestId())
        .isEqualTo(multibyte128Bytes);
    for (String invalid : new String[] {"a".repeat(129), "é".repeat(65), "e\u0301", "\uD800"}) {
      assertThatThrownBy(() -> requestWithControlPlaneRequestId(invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void roundTripsOwnerAttemptAndFenceAndRejectsMissingMalformedOrNonpositiveProof()
      throws Exception {
    var launchBinding = AuthoringFixtures.lifecycleEvidence("ACTIVE", 3L).launchBinding();
    var descriptor = launchBinding.descriptor();
    var request =
        new Request(
            READ_REQUEST_ID,
            descriptor.targetNamespace(),
            descriptor.canonicalTenantId(),
            descriptor.worldSlug(),
            GAME_INSTANCE_ID,
            descriptor.controlPlaneRequestId(),
            descriptor.launchDescriptorId(),
            descriptor.requestDigest(),
            descriptor.resultDigest(),
            launchBinding.releaseAttestation().evidenceDigest());
    var response = response(request, launchBinding);

    var result =
        CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromResponse(request, response);

    assertThat(CanonicalGameInstanceLaunchAssociationReadGrpcCodec.toResponse(result))
        .isEqualTo(response);
    assertThat(
            CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromRequest(
                CanonicalGameInstanceLaunchAssociationReadGrpcCodec.toRequest(request)))
        .isEqualTo(request);
    assertThat(result.ownerAttemptId()).isEqualTo(OWNER_ATTEMPT_ID);
    assertThat(result.ownerFence()).isEqualTo(4L);
    assertThat(result.canonicalRealmId()).isEqualTo(CANONICAL_REALM_ID);
    assertThat(result.catalogRevision()).isEqualTo(9L);
    assertThat(result.playableStateScope()).isEqualTo(RealmEntryPolicy.StateScope.SHARED);
    assertThat(result.launchBindingEvidence()).isEqualTo(launchBinding);
    for (var invalid :
        new GetCanonicalGameInstanceLaunchAssociationResponse[] {
          response.toBuilder().clearOwnerAttemptId().build(),
          response.toBuilder().setOwnerAttemptId("not-a-uuid").build(),
          response.toBuilder().setOwnerAttemptId("00000000-0000-0000-0000-000000000000").build(),
          response.toBuilder().setOwnerFence(0).build(),
          response.toBuilder().setOwnerFence(-1).build(),
          response.toBuilder().clearCanonicalRealmId().build(),
          response.toBuilder().setCanonicalRealmId("not-a-uuid").build(),
          response.toBuilder().setCanonicalRealmId("00000000-0000-0000-0000-000000000000").build(),
          response.toBuilder().clearCatalogRevision().build(),
          response.toBuilder().setCatalogRevision(0L).build(),
          response.toBuilder().setCatalogRevision(-1L).build()
        }) {
      assertThatThrownBy(
              () ->
                  CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromResponse(
                      request, invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }

    for (var invalidScope :
        new GetCanonicalGameInstanceLaunchAssociationResponse[] {
          response.toBuilder().clearPlayableStateScope().build(),
          response.toBuilder()
              .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_UNSPECIFIED)
              .build(),
          response.toBuilder()
              .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED)
              .build(),
          response.toBuilder().setPlayableStateScopeValue(999).build()
        }) {
      var wireResponse =
          GetCanonicalGameInstanceLaunchAssociationResponse.parseFrom(invalidScope.toByteArray());
      assertThatThrownBy(
              () ->
                  CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromResponse(
                      request, wireResponse))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  private static GetCanonicalGameInstanceLaunchAssociationResponse response(
      Request request,
      net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence launchBinding) {
    return GetCanonicalGameInstanceLaunchAssociationResponse.newBuilder()
        .setReadRequestId(request.readRequestId().toString())
        .setTargetNamespace(request.targetNamespace())
        .setCanonicalTenantId(request.canonicalTenantId().toString())
        .setWorldSlug(request.worldSlug())
        .setGameInstanceUuid(request.gameInstanceUuid().toString())
        .setControlPlaneRequestId(request.controlPlaneRequestId())
        .setLaunchDescriptorId(request.launchDescriptorId())
        .setDescriptorRequestDigest(request.expectedDescriptorRequestDigest())
        .setDescriptorResultDigest(request.expectedDescriptorResultDigest())
        .setReleaseAttestationEvidenceDigest(request.expectedReleaseAttestationEvidenceDigest())
        .setPlayableStateNamespaceId("dddddddd-dddd-4ddd-8ddd-dddddddddddd")
        .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
        .setPublicProduction(true)
        .setCurrentGameInstanceStatus(CurrentGameInstanceStatus.STARTING.name())
        .setCurrentRowVersion(1L)
        .setLaunchDescriptor(
            AuthoredWorldLaunchDescriptorGrpcCodec.toLaunchDescriptorMessage(
                launchBinding.descriptor()))
        .setReleaseAttestation(
            AuthoredWorldLaunchDescriptorGrpcCodec.toReleaseAttestation(
                launchBinding.releaseAttestation()))
        .setOwnerAttemptId(OWNER_ATTEMPT_ID.toString())
        .setOwnerFence(4L)
        .setCanonicalRealmId(CANONICAL_REALM_ID.toString())
        .setCatalogRevision(9L)
        .build();
  }

  private static Request requestWithControlPlaneRequestId(String controlPlaneRequestId) {
    var launchBinding = AuthoringFixtures.lifecycleEvidence("ACTIVE", 3L).launchBinding();
    var descriptor = launchBinding.descriptor();
    return new Request(
        READ_REQUEST_ID,
        descriptor.targetNamespace(),
        descriptor.canonicalTenantId(),
        descriptor.worldSlug(),
        GAME_INSTANCE_ID,
        controlPlaneRequestId,
        descriptor.launchDescriptorId(),
        descriptor.requestDigest(),
        descriptor.resultDigest(),
        launchBinding.releaseAttestation().evidenceDigest());
  }
}
