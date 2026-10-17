package net.firedevops.firemud.common.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalReadEvidence.ReadRequest;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalReadEvidence.ReadResult;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalReadEvidence.Status;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalReadGrpcCodec;
import net.firedevops.firemud.common.publication.GameDesignPublicationOperationBinding;
import org.junit.jupiter.api.Test;

class GameDesignPublicationTerminalEvidenceCodecTest {
  @Test
  void selectedFullV4TerminalRoundTripsOnlyTheExactCompleteParticipantVector() throws Exception {
    var fixture = PublishedRealmEntryPolicySetEvidenceTest.fixture(false);
    var previous = fixture.terminal().releaseContent();
    var previousSelector = previous.worldStartLocationEvidence();
    var oldRequest = previousSelector.request();
    var selectorRequest =
        new WorldPublishedStartLocationEvidence.Request(
            oldRequest.targetNamespace(),
            oldRequest.canonicalTenantId(),
            oldRequest.canonicalVersionId(),
            oldRequest.intakeRequestId(),
            oldRequest.publicationFence(),
            oldRequest.publicationRequestId(),
            oldRequest.requestDigest(),
            oldRequest.versionStateEpoch(),
            oldRequest.publishWorkflowId(),
            oldRequest.appliedCommitId(),
            oldRequest.contentDigest(),
            AuthoredWorldReleaseAttestationEvidence.SELECTED_FULL_SCHEMA_VERSION,
            oldRequest.worldAffectedTuples());
    var selector =
        new WorldPublishedStartLocationEvidence(
            selectorRequest,
            previousSelector.selectorReceiptBytes(),
            previousSelector.originalAccountBindingBytes(),
            previousSelector.appliedResultBytes());
    var operation =
        new GameDesignPublicationOperationBinding(
            fixture.operation().account(),
            selector,
            net.firedevops.firemud.test.IsolatedWorldPublicationInventoryFixtures.stipulated(
                fixture.operation().account(), selector));
    var participants =
        previous.participantDigests().stream()
            .map(
                participant ->
                    new GameDesignPublicationTerminalEvidence.Participant(
                        participant.participantKey(),
                        participant.scopeValue(),
                        participant.baseVersionId(),
                        participant.appliedCommitId(),
                        participant.contentDigest(),
                        AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                            participant.participantKey(),
                            AuthoredWorldReleaseAttestationEvidence.SELECTED_FULL_SCHEMA_VERSION),
                        participant.abilitySchemaDigest(),
                        participant.errorCode(),
                        participant.errorMessage()))
            .toList();
    var release =
        new GameDesignPublicationTerminalEvidence.ReleaseContent(
            previous.canonicalTenantId(),
            previous.canonicalVersionId(),
            previous.publishedReleaseBundleRef(),
            previous.versionNumber(),
            "v4",
            previous.publishWorkflowId(),
            previous.manifestHash(),
            previous.manifestSchemaVersion(),
            previous.artifactDigests(),
            previous.requiredManifestAssetKeys(),
            participants,
            previous.commandDefinitions(),
            previous.generationConfigRevision(),
            selector);
    assertThat(participants)
        .extracting(GameDesignPublicationTerminalEvidence.Participant::digestSchemaVersion)
        .containsExactly(4, 3, 1, 6, 2);

    var terminal =
        new GameDesignPublicationTerminalEvidence(
            operation.canonicalBytes(),
            GameDesignPublicationTerminalEvidence.Outcome.PUBLISHED,
            release,
            fixture.terminal().publicationVersionStateEpoch());
    var request =
        new ReadRequest(
            ReadRequest.SCHEMA_VERSION,
            operation.world().request().targetNamespace(),
            java.util.UUID.fromString("30303030-3030-4030-8030-303030303030"),
            operation.canonicalBytes());
    var result = new ReadResult(request, Status.PUBLISHED, Optional.of(terminal));
    var response = GameDesignPublicationTerminalReadGrpcCodec.toResponse(request, result);

    assertThat(GameDesignPublicationTerminalReadGrpcCodec.fromResponse(request, response))
        .isEqualTo(result);

    var mixedParticipants = new java.util.ArrayList<>(participants);
    mixedParticipants.set(
        3,
        new GameDesignPublicationTerminalEvidence.Participant(
            "AUTOMATION_SCRIPTING",
            participants.get(3).scopeValue(),
            null,
            participants.get(3).appliedCommitId(),
            participants.get(3).contentDigest(),
            5,
            null,
            null,
            null));
    assertThatThrownBy(
            () ->
                new GameDesignPublicationTerminalEvidence.ReleaseContent(
                    previous.canonicalTenantId(),
                    previous.canonicalVersionId(),
                    previous.publishedReleaseBundleRef(),
                    previous.versionNumber(),
                    "v4",
                    previous.publishWorkflowId(),
                    previous.manifestHash(),
                    previous.manifestSchemaVersion(),
                    previous.artifactDigests(),
                    previous.requiredManifestAssetKeys(),
                    mixedParticipants,
                    previous.commandDefinitions(),
                    previous.generationConfigRevision(),
                    selector))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Incomplete or changed required participant proof");
  }

  @Test
  void maximumFrozenEpochIsRejectedAsInvalidEvidenceAndOrdinaryEpochRoundTrips() throws Exception {
    var ordinary = PublishedRealmEntryPolicySetEvidenceTest.fixture(false);
    var ordinaryRequest =
        new ReadRequest(
            ReadRequest.SCHEMA_VERSION,
            ordinary.operation().world().request().targetNamespace(),
            java.util.UUID.fromString("10101010-1010-4010-8010-101010101010"),
            ordinary.operation().canonicalBytes());
    var ordinaryResult =
        new ReadResult(ordinaryRequest, Status.PUBLISHED, Optional.of(ordinary.terminal()));
    var ordinaryResponse =
        GameDesignPublicationTerminalReadGrpcCodec.toResponse(ordinaryRequest, ordinaryResult);

    assertThat(
            GameDesignPublicationTerminalReadGrpcCodec.fromResponse(
                ordinaryRequest, ordinaryResponse))
        .isEqualTo(ordinaryResult);

    GameDesignPublicationOperationBinding maxEpochOperation =
        PublishedRealmEntryPolicySetEvidenceTest.operation(Long.MAX_VALUE);
    var maxEpochRelease =
        PublishedRealmEntryPolicySetEvidenceTest.release(maxEpochOperation, "generation-max");
    var malformedTerminal = new ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(
        malformedTerminal, GameDesignPublicationTerminalEvidence.SCHEMA);
    DraftAuthorizationFenceBinding.frame(malformedTerminal, maxEpochOperation.canonicalBytes());
    DraftAuthorizationFenceBinding.frame(malformedTerminal, "PUBLISHED");
    DraftAuthorizationFenceBinding.frame(malformedTerminal, maxEpochRelease.canonicalBytes());
    DraftAuthorizationFenceBinding.frame(malformedTerminal, "1");

    var malformedResponseBytes = new ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(malformedResponseBytes, ReadResult.SCHEMA);
    var maxEpochRequest =
        new ReadRequest(
            ReadRequest.SCHEMA_VERSION,
            maxEpochOperation.world().request().targetNamespace(),
            java.util.UUID.fromString("20202020-2020-4020-8020-202020202020"),
            maxEpochOperation.canonicalBytes());
    DraftAuthorizationFenceBinding.frame(malformedResponseBytes, maxEpochRequest.canonicalBytes());
    DraftAuthorizationFenceBinding.frame(malformedResponseBytes, "PUBLISHED");
    DraftAuthorizationFenceBinding.frame(malformedResponseBytes, malformedTerminal.toByteArray());

    var malformedWireResponse =
        net.firedevops.firemud.gamedesign.v1.ReadGameDesignPublicationTerminalResponse.newBuilder()
            .setCanonicalResponseBytes(ByteString.copyFrom(malformedResponseBytes.toByteArray()))
            .build();

    assertThatThrownBy(
            () ->
                GameDesignPublicationTerminalReadGrpcCodec.fromResponse(
                    maxEpochRequest, malformedWireResponse))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Published version-state epoch exceeds owner range");
  }

  @Test
  void publicationCounterAboveIntRangeIsRejectedAsInvalidTerminalReadEvidence() throws Exception {
    var fixture = PublishedRealmEntryPolicySetEvidenceTest.fixture(false);
    var request =
        new ReadRequest(
            ReadRequest.SCHEMA_VERSION,
            fixture.operation().world().request().targetNamespace(),
            java.util.UUID.fromString("10101010-1010-4010-8010-101010101010"),
            fixture.operation().canonicalBytes());
    var result = new ReadResult(request, Status.PUBLISHED, Optional.of(fixture.terminal()));
    var response = GameDesignPublicationTerminalReadGrpcCodec.toResponse(request, result);

    byte[] malformedRelease =
        replaceSegment(
            fixture.terminal().releaseContent().canonicalBytes(),
            4,
            "2147483648".getBytes(StandardCharsets.UTF_8));
    byte[] malformedTerminal =
        replaceFrame(fixture.terminal().canonicalBytes(), 3, malformedRelease);
    byte[] malformedResponse = replaceFrame(result.canonicalBytes(), 3, malformedTerminal);

    assertThatThrownBy(
            () ->
                GameDesignPublicationTerminalReadGrpcCodec.fromResponse(
                    request,
                    response.toBuilder()
                        .setCanonicalResponseBytes(ByteString.copyFrom(malformedResponse))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("supported range");
  }

  private static byte[] replaceFrame(byte[] framed, int targetIndex, byte[] replacement) {
    var input = ByteBuffer.wrap(framed);
    var output = new ByteArrayOutputStream();
    for (int index = 0; input.hasRemaining(); index++) {
      int length = input.getInt();
      byte[] value = new byte[length];
      input.get(value);
      DraftAuthorizationFenceBinding.frame(output, index == targetIndex ? replacement : value);
    }
    return output.toByteArray();
  }

  private static byte[] replaceSegment(byte[] encoded, int targetIndex, byte[] replacement) {
    var input = ByteBuffer.wrap(encoded);
    var output = new ByteArrayOutputStream();
    for (int index = 0; input.hasRemaining(); index++) {
      int lengthStart = input.position();
      while (input.get() != ':') {}
      int lengthEnd = input.position() - 1;
      int length =
          Integer.parseInt(
              new String(encoded, lengthStart, lengthEnd - lengthStart, StandardCharsets.US_ASCII));
      byte[] value = new byte[length];
      input.get(value);
      byte[] selected = index == targetIndex ? replacement : value;
      output.writeBytes(Integer.toString(selected.length).getBytes(StandardCharsets.US_ASCII));
      output.write(':');
      output.writeBytes(selected);
    }
    return output.toByteArray();
  }
}
