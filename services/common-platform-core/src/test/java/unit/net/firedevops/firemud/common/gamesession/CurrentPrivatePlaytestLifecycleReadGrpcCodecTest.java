package net.firedevops.firemud.common.gamesession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.gamesession.CurrentPrivatePlaytestLifecycleReadEvidence.ReadState;
import net.firedevops.firemud.common.gamesession.CurrentPrivatePlaytestLifecycleReadEvidence.Scope;
import net.firedevops.firemud.common.gamesession.CurrentPrivatePlaytestLifecycleReadEvidence.Snapshot;
import net.firedevops.firemud.common.gamesession.CurrentPrivatePlaytestLifecycleReadGrpcCodec.Request;
import net.firedevops.firemud.gamesession.v1.ReadCurrentPrivatePlaytestLifecycleResponse;
import org.junit.jupiter.api.Test;

class CurrentPrivatePlaytestLifecycleReadGrpcCodecTest {
  private static final String DIGEST = "sha256:" + "a".repeat(64);
  private static final String OTHER_DIGEST = "sha256:" + "b".repeat(64);

  @Test
  void requestAndPresentCurrentSnapshotRoundTripAsClosedCorrelatedEvidence() {
    Request request = request();
    var wireRequest = CurrentPrivatePlaytestLifecycleReadGrpcCodec.toRequest(request);
    assertThat(CurrentPrivatePlaytestLifecycleReadGrpcCodec.parseRequest(wireRequest))
        .isEqualTo(request);

    Snapshot snapshot = snapshot(request.scope());
    var response =
        CurrentPrivatePlaytestLifecycleReadGrpcCodec.toResponse(request, Optional.of(snapshot));
    var evidence = CurrentPrivatePlaytestLifecycleReadGrpcCodec.fromResponse(request, response);

    assertThat(evidence.request()).isEqualTo(request);
    assertThat(evidence.current()).contains(snapshot);
  }

  @Test
  void sourceCharacterBoundsPreserveMultibyteBundleAndLogicalRevision() {
    Request request = request();
    Snapshot source = snapshot(request.scope(), "界".repeat(1024), "界".repeat(128));
    var response =
        CurrentPrivatePlaytestLifecycleReadGrpcCodec.toResponse(request, Optional.of(source));
    assertThat(response.getSerializedSize()).isGreaterThan(4096).isLessThanOrEqualTo(8192);
    assertThat(CurrentPrivatePlaytestLifecycleReadGrpcCodec.fromResponse(request, response).current())
        .contains(source);
    assertThatThrownBy(() -> snapshot(request.scope(), "界".repeat(1025), "revision"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> snapshot(request.scope(), "bundle", "界".repeat(129)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void absenceIsExplicitAndDoesNotFabricateLifecycleOrPolicyFacts() {
    Request request = request();
    var response =
        CurrentPrivatePlaytestLifecycleReadGrpcCodec.toResponse(request, Optional.empty());

    assertThat(
            CurrentPrivatePlaytestLifecycleReadGrpcCodec.fromResponse(request, response).current())
        .isEmpty();
  }

  @Test
  void responseRejectsChangedCorrelationUnknownFieldsAndTrailingFrames() {
    Request expected = request();
    var response =
        CurrentPrivatePlaytestLifecycleReadGrpcCodec.toResponse(
            expected, Optional.of(snapshot(expected.scope())));
    Request otherRequest = new Request(UUID.randomUUID(), expected.scope());

    assertThatThrownBy(
            () -> CurrentPrivatePlaytestLifecycleReadGrpcCodec.fromResponse(otherRequest, response))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                CurrentPrivatePlaytestLifecycleReadGrpcCodec.fromResponse(
                    expected,
                    response.toBuilder()
                        .setUnknownFields(
                            UnknownFieldSet.newBuilder()
                                .addField(
                                    99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                                .build())
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);

    ByteArrayOutputStream trailing = new ByteArrayOutputStream();
    trailing.writeBytes(response.getCanonicalEvidence().toByteArray());
    DraftAuthorizationFenceBinding.frame(trailing, "TRAILING");
    var malformed =
        response.toBuilder()
            .setCanonicalEvidence(ByteString.copyFrom(trailing.toByteArray()))
            .build();
    assertThatThrownBy(
            () -> CurrentPrivatePlaytestLifecycleReadGrpcCodec.fromResponse(expected, malformed))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void requestAndResponseEnforceWireBoundsAndRequiredCanonicalPayload() {
    assertThatThrownBy(
            () ->
                CurrentPrivatePlaytestLifecycleReadGrpcCodec.parseRequest(
                    net.firedevops.firemud.gamesession.v1.ReadCurrentPrivatePlaytestLifecycleRequest
                        .getDefaultInstance()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                CurrentPrivatePlaytestLifecycleReadGrpcCodec.parseRequest(
                    net.firedevops.firemud.gamesession.v1.ReadCurrentPrivatePlaytestLifecycleRequest
                        .newBuilder()
                        .setCanonicalEvidence(
                            ByteString.copyFrom(
                                new byte
                                    [CurrentPrivatePlaytestLifecycleReadGrpcCodec
                                        .MAX_REQUEST_WIRE_BYTES]))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                CurrentPrivatePlaytestLifecycleReadGrpcCodec.parseRequest(
                    CurrentPrivatePlaytestLifecycleReadGrpcCodec.toRequest(request()).toBuilder()
                        .setUnknownFields(
                            UnknownFieldSet.newBuilder()
                                .addField(
                                    99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                                .build())
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                CurrentPrivatePlaytestLifecycleReadGrpcCodec.fromResponse(
                    request(),
                    ReadCurrentPrivatePlaytestLifecycleResponse.newBuilder()
                        .setCanonicalEvidence(
                            ByteString.copyFrom(
                                new byte
                                    [CurrentPrivatePlaytestLifecycleReadGrpcCodec
                                        .MAX_RESPONSE_WIRE_BYTES]))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void snapshotRejectsImpossibleReadStateAndSelectionAfterObservation() {
    Scope scope = request().scope();
    Snapshot valid = snapshot(scope);

    assertThat(snapshotAt(valid, ReadState.EXPIRED, valid.forkExpiresAt()).readState())
        .isEqualTo(ReadState.EXPIRED);

    assertThatThrownBy(
            () ->
                new Snapshot(
                    valid.scope(),
                    valid.selectionRequestId(),
                    valid.selectionHeadRevision(),
                    valid.selectionRequestDigest(),
                    valid.selectedAt(),
                    valid.preparationRequestId(),
                    valid.preparationRequestDigest(),
                    valid.playtestLifecycleId(),
                    valid.playableStateNamespaceId(),
                    valid.playtestStateGeneration(),
                    valid.forkExpiresAt(),
                    valid.preparationState(),
                    ReadState.EXPIRED,
                    valid.observedAt(),
                    valid.canonicalVersionId(),
                    valid.sourceCatalogRealmId(),
                    valid.sourceCatalogRequestId(),
                    valid.sourceCatalogRevision(),
                    valid.publishedReleaseBundleRef(),
                    valid.publishedReleaseBundleDigest(),
                    valid.privatePolicyId(),
                    valid.privatePolicyDigest(),
                    valid.policySourceCommitId(),
                    valid.policySourceRevisionId(),
                    valid.policyLogicalRevisionId(),
                    valid.policySetDigest()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new Snapshot(
                    valid.scope(),
                    valid.selectionRequestId(),
                    valid.selectionHeadRevision(),
                    valid.selectionRequestDigest(),
                    valid.observedAt().plusSeconds(1),
                    valid.preparationRequestId(),
                    valid.preparationRequestDigest(),
                    valid.playtestLifecycleId(),
                    valid.playableStateNamespaceId(),
                    valid.playtestStateGeneration(),
                    valid.forkExpiresAt(),
                    valid.preparationState(),
                    valid.readState(),
                    valid.observedAt(),
                    valid.canonicalVersionId(),
                    valid.sourceCatalogRealmId(),
                    valid.sourceCatalogRequestId(),
                    valid.sourceCatalogRevision(),
                    valid.publishedReleaseBundleRef(),
                    valid.publishedReleaseBundleDigest(),
                    valid.privatePolicyId(),
                    valid.privatePolicyDigest(),
                    valid.policySourceCommitId(),
                    valid.policySourceRevisionId(),
                    valid.policyLogicalRevisionId(),
                    valid.policySetDigest()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static Snapshot snapshotAt(Snapshot source, ReadState state, Instant observedAt) {
    return new Snapshot(
        source.scope(),
        source.selectionRequestId(),
        source.selectionHeadRevision(),
        source.selectionRequestDigest(),
        source.selectedAt(),
        source.preparationRequestId(),
        source.preparationRequestDigest(),
        source.playtestLifecycleId(),
        source.playableStateNamespaceId(),
        source.playtestStateGeneration(),
        source.forkExpiresAt(),
        source.preparationState(),
        state,
        observedAt,
        source.canonicalVersionId(),
        source.sourceCatalogRealmId(),
        source.sourceCatalogRequestId(),
        source.sourceCatalogRevision(),
        source.publishedReleaseBundleRef(),
        source.publishedReleaseBundleDigest(),
        source.privatePolicyId(),
        source.privatePolicyDigest(),
        source.policySourceCommitId(),
        source.policySourceRevisionId(),
        source.policyLogicalRevisionId(),
        source.policySetDigest());
  }

  private static Request request() {
    return new Request(
        UUID.randomUUID(),
        new Scope("game-session-read-test", UUID.randomUUID(), "world-one", "realm-one"));
  }

  private static Snapshot snapshot(Scope scope) {
    return snapshot(scope, "release-bundle:current-private-proof", "policy-revision:current-private-proof");
  }

  private static Snapshot snapshot(Scope scope, String bundleRef, String logicalRevision) {
    Instant selectedAt = Instant.parse("2026-11-03T00:00:00Z");
    Instant expiresAt = selectedAt.plusSeconds(3600);
    Instant observedAt = selectedAt.plusSeconds(1);
    return new Snapshot(
        scope,
        UUID.randomUUID(),
        1L,
        DIGEST,
        selectedAt,
        UUID.randomUUID(),
        OTHER_DIGEST,
        UUID.randomUUID(),
        UUID.randomUUID(),
        1L,
        expiresAt,
        "PREPARING",
        ReadState.INCOMPLETE,
        observedAt,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        1L,
        bundleRef,
        DIGEST,
        UUID.randomUUID(),
        OTHER_DIGEST,
        UUID.randomUUID(),
        UUID.randomUUID(),
        logicalRevision,
        DIGEST);
  }
}
