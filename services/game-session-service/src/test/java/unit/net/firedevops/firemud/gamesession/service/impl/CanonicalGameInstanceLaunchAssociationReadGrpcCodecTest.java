package net.firedevops.firemud.gamesession.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorGrpcCodec;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.gamedesign.v1.GetCompleteLaunchBindingResponse;
import net.firedevops.firemud.gamedesign.v1.GetLaunchDescriptorRequest;
import net.firedevops.firemud.gamesession.dto.CanonicalGameInstanceLaunchAssociation;
import net.firedevops.firemud.gamesession.repository.CanonicalGameInstanceLaunchAssociationRepository;
import net.firedevops.firemud.gamesession.service.CanonicalGameInstanceLaunchAssociationReadRequest;
import net.firedevops.firemud.gamesession.service.CanonicalGameInstanceLaunchAssociationReadService;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameInstanceLaunchAssociationRequest;
import org.junit.jupiter.api.Test;

class CanonicalGameInstanceLaunchAssociationReadGrpcCodecTest {
  private static final String NAMESPACE = "gameplay";
  private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID INSTANCE = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID TENANT_ASSOCIATION =
      UUID.fromString("33333333-3333-4333-8333-333333333333");

  @Test
  void returnsCompleteTypedPairAndCurrentGsProjectionForTheExactReadSelector() throws Exception {
    CompleteLaunchBindingEvidence evidence = evidence();
    var association = association(evidence);
    var request = wireRequest(evidence);

    var decoded = CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromWire(request);
    assertThat(decoded)
        .isEqualTo(
            new CanonicalGameInstanceLaunchAssociationReadRequest(
                UUID.fromString(request.getReadRequestId()),
                NAMESPACE,
                TENANT,
                "earth",
                INSTANCE,
                evidence.descriptor().controlPlaneRequestId(),
                evidence.descriptor().launchDescriptorId(),
                evidence.descriptor().requestDigest(),
                evidence.descriptor().resultDigest(),
                evidence.releaseAttestation().evidenceDigest()));

    var response = CanonicalGameInstanceLaunchAssociationReadGrpcCodec.toWire(request, association);
    var parsed = response.getParserForType().parseFrom(response.toByteArray());
    assertThat(parsed.getReadRequestId()).isEqualTo(request.getReadRequestId());
    assertThat(parsed.getGameInstanceUuid()).isEqualTo(INSTANCE.toString());
    assertThat(parsed.getPlayableStateScope()).isEqualTo("SHARED");
    assertThat(parsed.getPublicProduction()).isTrue();
    assertThat(parsed.getCurrentGameInstanceStatus()).isEqualTo("RUNNING");
    assertThat(parsed.getCurrentRowVersion()).isEqualTo(8L);
    assertThat(parsed.getLaunchDescriptor().getAuthoredWorldBinding().hasSourceVersionId())
        .isTrue();
    assertThat(parsed.getLaunchDescriptor().getAuthoredWorldBinding().getSourceVersionId())
        .isEqualTo(3_000_000_001L);
    assertThat(parsed.getReleaseAttestation().getParticipantDigests(2).hasAbilitySchemaDigest())
        .isTrue();

    GetLaunchDescriptorRequest gdRequest =
        GetLaunchDescriptorRequest.newBuilder()
            .setRequestId(request.getReadRequestId())
            .setCanonicalTenantId(request.getCanonicalTenantId())
            .setWorldSlug(request.getWorldSlug())
            .setControlPlaneRequestId(request.getControlPlaneRequestId())
            .setExpectedRequestDigest(request.getExpectedDescriptorRequestDigest())
            .setExpectedResultDigest(request.getExpectedDescriptorResultDigest())
            .build();
    CompleteLaunchBindingEvidence roundTrip =
        AuthoredWorldLaunchDescriptorGrpcCodec.fromCompleteResponse(
            gdRequest,
            GetCompleteLaunchBindingResponse.newBuilder()
                .setRequestId(request.getReadRequestId())
                .setLaunchDescriptor(parsed.getLaunchDescriptor())
                .setReleaseAttestation(parsed.getReleaseAttestation())
                .build());
    assertThat(roundTrip).isEqualTo(evidence);
  }

  @Test
  void rejectsNoncanonicalReadIdentityAndChangedOwnerSelector() {
    CompleteLaunchBindingEvidence evidence = evidence();
    GetCanonicalGameInstanceLaunchAssociationRequest invalid =
        wireRequest(evidence).toBuilder().setReadRequestId("1-1-1-1-1").build();
    assertThatThrownBy(() -> CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromWire(invalid))
        .isInstanceOf(IllegalArgumentException.class);

    CanonicalGameInstanceLaunchAssociationRepository repository =
        mock(CanonicalGameInstanceLaunchAssociationRepository.class);
    when(repository.read(evidence.descriptor().controlPlaneRequestId()))
        .thenReturn(Optional.of(association(evidence)));
    var service = new CanonicalGameInstanceLaunchAssociationReadService(repository, NAMESPACE);
    var request =
        CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromWire(wireRequest(evidence));
    var mismatched =
        new CanonicalGameInstanceLaunchAssociationReadRequest(
            request.readRequestId(),
            NAMESPACE,
            TENANT,
            "earth",
            INSTANCE,
            request.controlPlaneRequestId(),
            request.launchDescriptorId(),
            request.expectedDescriptorRequestDigest(),
            "sha256:" + "f".repeat(64),
            request.expectedReleaseAttestationEvidenceDigest());
    assertThatThrownBy(() -> service.read(mismatched)).isInstanceOf(IllegalStateException.class);
  }

  private static GetCanonicalGameInstanceLaunchAssociationRequest wireRequest(
      CompleteLaunchBindingEvidence evidence) {
    return GetCanonicalGameInstanceLaunchAssociationRequest.newBuilder()
        .setReadRequestId("44444444-4444-4444-8444-444444444444")
        .setTargetNamespace(NAMESPACE)
        .setCanonicalTenantId(TENANT.toString())
        .setWorldSlug("earth")
        .setGameInstanceUuid(INSTANCE.toString())
        .setControlPlaneRequestId(evidence.descriptor().controlPlaneRequestId())
        .setLaunchDescriptorId(evidence.descriptor().launchDescriptorId())
        .setExpectedDescriptorRequestDigest(evidence.descriptor().requestDigest())
        .setExpectedDescriptorResultDigest(evidence.descriptor().resultDigest())
        .setExpectedReleaseAttestationEvidenceDigest(evidence.releaseAttestation().evidenceDigest())
        .build();
  }

  private static CanonicalGameInstanceLaunchAssociation association(
      CompleteLaunchBindingEvidence evidence) {
    return new CanonicalGameInstanceLaunchAssociation(
        NAMESPACE,
        19L,
        TENANT_ASSOCIATION,
        TENANT,
        INSTANCE,
        "earth",
        UUID.fromString("77777777-7777-4777-8777-777777777777"),
        RealmEntryPolicy.StateScope.SHARED,
        true,
        evidence.descriptor().controlPlaneRequestId(),
        evidence.descriptor().launchDescriptorId(),
        5L,
        evidence,
        CanonicalGameInstanceLaunchAssociation.CurrentGameInstanceStatus.RUNNING,
        8L);
  }

  private static CompleteLaunchBindingEvidence evidence() {
    UUID sourceOperationId = UUID.fromString("55555555-5555-4555-8555-555555555555");
    String sourceDigest = "sha256:" + "a".repeat(64);
    var request =
        new AuthoredWorldLaunchDescriptorEvidence.Request(
            NAMESPACE,
            "launch-op-1",
            TENANT,
            "earth",
            sourceOperationId,
            sourceDigest,
            3_000_000_000L,
            true,
            "requested-script",
            true,
            3_000_000_001L,
            true,
            3_000_000_002L,
            true,
            "{\"seed\":true}");
    var descriptor =
        AuthoredWorldLaunchDescriptorEvidence.create(
            request,
            "descriptor-large-counter",
            4_000_000_001L,
            true,
            "script-9",
            "{\"runtime\":true}",
            "generation-9",
            5_000_000_001L,
            6_000_000_001L,
            "release-large-counter",
            true,
            "remap-9");
    List<AuthoredWorldReleaseAttestationEvidence.Participant> participants =
        AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder().stream()
            .map(
                owner ->
                    new AuthoredWorldReleaseAttestationEvidence.Participant(
                        owner,
                        Long.toString(descriptor.versionId()),
                        false,
                        null,
                        "commit-9",
                        "c".repeat(64),
                        AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                            owner),
                        "GAME_LOGIC".equals(owner),
                        "GAME_LOGIC".equals(owner) ? "sha256:" + "d".repeat(64) : null))
            .toList();
    var attestation =
        AuthoredWorldReleaseAttestationEvidence.create(
            NAMESPACE,
            descriptor.resultDigest(),
            TENANT,
            UUID.fromString("66666666-6666-4666-8666-666666666666"),
            "earth",
            sourceOperationId,
            sourceDigest,
            descriptor.launchDescriptorId(),
            descriptor.publishedReleaseBundleRef(),
            descriptor.versionStateEpoch(),
            "publish-workflow-large",
            "commit-9",
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
