package net.firedevops.firemud.common.gamesession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.UnknownFieldSet;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorGrpcCodec;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence.CurrentGameInstanceStatus;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence.Request;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundleFixture;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
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
  void controlPlaneRequestIdUsesCanonicalUtf8ByteBoundAndUnicode() throws Exception {
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
          response.toBuilder()
              .setOwnerAttemptId(OWNER_ATTEMPT_ID.toString().toUpperCase(Locale.ROOT))
              .build(),
          response.toBuilder().setOwnerFence(0).build(),
          response.toBuilder().setOwnerFence(-1).build(),
          response.toBuilder().clearCanonicalRealmId().build(),
          response.toBuilder().setCanonicalRealmId("not-a-uuid").build(),
          response.toBuilder().setCanonicalRealmId("00000000-0000-0000-0000-000000000000").build(),
          response.toBuilder()
              .setCanonicalRealmId(CANONICAL_REALM_ID.toString().toUpperCase(Locale.ROOT))
              .build(),
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

  @Test
  void accountOperationRoundTripsExactTupleAndOwnerSelectorAndRejectsPartialOrChangedEchoes()
      throws Exception {
    var binding = AuthoringFixtures.lifecycleEvidence("PREPARING", 1L).launchBinding();
    var descriptor = binding.descriptor();
    var tuple = originalTuple(descriptor);
    var operation =
        new CanonicalGameInstanceLaunchAssociationReadEvidence.OriginalOperation(
            tuple.canonicalBytes(), UUID.randomUUID(), OWNER_ATTEMPT_ID, 4L);
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
            binding.releaseAttestation().evidenceDigest(),
            operation);
    var wire = CanonicalGameInstanceLaunchAssociationReadGrpcCodec.toRequest(request);
    assertThat(CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromRequest(wire))
        .isEqualTo(request);
    // Realm/catalog values are explicitly controlled codec inputs, not GS owner production.
    var result =
        new CanonicalGameInstanceLaunchAssociationReadEvidence.Result(
            request,
            UUID.randomUUID(),
            RealmEntryPolicy.StateScope.SHARED,
            true,
            CurrentGameInstanceStatus.STARTING,
            1L,
            OWNER_ATTEMPT_ID,
            4L,
            UUID.randomUUID(),
            17L,
            binding);
    var combined =
        CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromResponse(
            request, CanonicalGameInstanceLaunchAssociationReadGrpcCodec.toResponse(result));
    assertThat(combined.canonicalRealmId()).isEqualTo(result.canonicalRealmId());
    assertThat(combined.catalogRevision()).isEqualTo(result.catalogRevision());
    assertThat(combined.request().originalOperation()).isEqualTo(operation);
    var response = CanonicalGameInstanceLaunchAssociationReadGrpcCodec.toResponse(result);
    assertThat(CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromResponse(request, response))
        .isEqualTo(result);
    byte[] copy = operation.originalPostAuthorizationTuple();
    copy[0] ^= 1;
    assertThat(operation.originalPostAuthorizationTuple()).containsExactly(tuple.canonicalBytes());
    for (var invalid :
        java.util.List.of(
            wire.toBuilder().clearOriginalPostAuthorizationTuple().build(),
            wire.toBuilder().clearExpectedOwnerMutationId().build(),
            wire.toBuilder().clearExpectedOwnerAttemptId().build(),
            wire.toBuilder().setExpectedOwnerFence(0L).build())) {
      assertThatThrownBy(
              () -> CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromRequest(invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }
    for (var invalid :
        java.util.List.of(
            response.toBuilder().clearOriginalPostAuthorizationTuple().build(),
            response.toBuilder().clearExpectedOwnerMutationId().build(),
            response.toBuilder().setExpectedOwnerMutationId(UUID.randomUUID().toString()).build(),
            response.toBuilder().setExpectedOwnerFence(5L).build(),
            response.toBuilder().setOwnerFence(5L).build())) {
      assertThatThrownBy(
              () ->
                  CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromResponse(
                      request, invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }
    var worldRequest =
        new Request(
            request.readRequestId(),
            request.targetNamespace(),
            request.canonicalTenantId(),
            request.worldSlug(),
            request.gameInstanceUuid(),
            request.controlPlaneRequestId(),
            request.launchDescriptorId(),
            request.expectedDescriptorRequestDigest(),
            request.expectedDescriptorResultDigest(),
            request.expectedReleaseAttestationEvidenceDigest());
    assertThatThrownBy(
            () ->
                CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromResponse(
                    worldRequest, response))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new Request(
                    operation.ownerMutationId(),
                    request.targetNamespace(),
                    request.canonicalTenantId(),
                    request.worldSlug(),
                    request.gameInstanceUuid(),
                    request.controlPlaneRequestId(),
                    request.launchDescriptorId(),
                    request.expectedDescriptorRequestDigest(),
                    request.expectedDescriptorResultDigest(),
                    request.expectedReleaseAttestationEvidenceDigest(),
                    operation))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsOriginalRequestIdentityAtBothLaunchReadWireBoundaries() throws Exception {
    var binding = AuthoringFixtures.lifecycleEvidence("PREPARING", 1L).launchBinding();
    var request = request(binding);
    var reusedIdentity =
        new Request(
            READ_REQUEST_ID,
            request.targetNamespace(),
            request.canonicalTenantId(),
            request.worldSlug(),
            request.gameInstanceUuid(),
            READ_REQUEST_ID.toString(),
            request.launchDescriptorId(),
            request.expectedDescriptorRequestDigest(),
            request.expectedDescriptorResultDigest(),
            request.expectedReleaseAttestationEvidenceDigest());
    var reusedWire =
        CanonicalGameInstanceLaunchAssociationReadGrpcCodec.toRequest(request).toBuilder()
            .setControlPlaneRequestId(READ_REQUEST_ID.toString())
            .build();

    assertThatThrownBy(
            () -> CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromRequest(reusedWire))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Exact canonical launch association request required")
        .hasRootCauseMessage(
            "Launch association read identity must remain separate from the original request");
    assertThatThrownBy(
            () -> CanonicalGameInstanceLaunchAssociationReadGrpcCodec.toRequest(reusedIdentity))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("separate from the original request");
  }

  @Test
  void rejectsChangedEchoMissingLaunchPairAndUnknownTopLevelOrNestedFields() throws Exception {
    var binding = AuthoringFixtures.lifecycleEvidence("PREPARING", 1L).launchBinding();
    var request = request(binding);
    var wire = CanonicalGameInstanceLaunchAssociationReadGrpcCodec.toRequest(request);
    var response = response(request, binding);
    var unknown =
        UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
            .build();

    assertThatThrownBy(
            () ->
                CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromRequest(
                    wire.toBuilder().setUnknownFields(unknown).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");
    for (var invalid :
        java.util.List.of(
            response.toBuilder().setControlPlaneRequestId("changed").build(),
            response.toBuilder().clearLaunchDescriptor().build(),
            response.toBuilder().clearReleaseAttestation().build(),
            response.toBuilder().setUnknownFields(unknown).build(),
            response.toBuilder()
                .setLaunchDescriptor(
                    response.getLaunchDescriptor().toBuilder().setUnknownFields(unknown))
                .build(),
            response.toBuilder()
                .setReleaseAttestation(
                    response.getReleaseAttestation().toBuilder().setUnknownFields(unknown))
                .build())) {
      assertThatThrownBy(
              () ->
                  CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromResponse(
                      request, invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  private static Request request(
      net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence binding) {
    var descriptor = binding.descriptor();
    return new Request(
        READ_REQUEST_ID,
        descriptor.targetNamespace(),
        descriptor.canonicalTenantId(),
        descriptor.worldSlug(),
        GAME_INSTANCE_ID,
        descriptor.controlPlaneRequestId(),
        descriptor.launchDescriptorId(),
        descriptor.requestDigest(),
        descriptor.resultDigest(),
        binding.releaseAttestation().evidenceDigest());
  }

  private static StartSessionPostAuthorizationExecutionTuple originalTuple(
      net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence descriptor) {
    var pre =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            descriptor.controlPlaneRequestId(),
            UUID.randomUUID(),
            new StartSessionOperatorAction(
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
                new StartSessionOperatorAction.Scope(
                    descriptor.canonicalTenantId(), descriptor.targetNamespace()),
                new StartSessionOperatorAction.Target(
                    descriptor.gameTemplateId(), UUID.randomUUID()),
                StartSessionOperatorAction.ExpectedVersion.ABSENT,
                new StartSessionOperatorAction.Mutation(
                    StartSessionOperatorAction.ClientIp.absent()),
                "TEST_ONLY canonical Account read codec"));
    var bytes =
        StartSessionAuthorityEvidenceBundleFixture.canonicalBytes(
            pre,
            new StartSessionAuthorityEvidenceBundleFixture.Values(
                UUID.randomUUID(),
                "sha256:" + "a".repeat(64),
                "1",
                "CURRENT",
                Instant.parse("2030-01-01T00:00:00Z"),
                Instant.parse("2030-01-01T00:05:00Z"),
                1,
                1,
                1,
                1,
                1,
                1,
                UUID.randomUUID(),
                "1",
                "1"));
    return StartSessionPostAuthorizationExecutionTuple.createHuman(
        pre,
        "spiffe://firemud/ns/" + descriptor.targetNamespace() + "/sa/logging-admin-service",
        "arfp/v1/test-key/" + "b".repeat(64),
        UUID.randomUUID(),
        1L,
        bytes,
        new StartSessionAuthorityEvidenceBundle.BundleReference(
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION, "1", "1", "1"));
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

  private static Request requestWithControlPlaneRequestId(String controlPlaneRequestId)
      throws Exception {
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
