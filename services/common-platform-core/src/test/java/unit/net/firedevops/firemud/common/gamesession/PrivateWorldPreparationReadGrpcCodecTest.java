package net.firedevops.firemud.common.gamesession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.gamedesign.RealmPolicyPublishedEvidence;
import net.firedevops.firemud.common.gamedesign.RealmPolicySource;
import net.firedevops.firemud.common.gamesession.CurrentPrivatePlaytestLifecycleReadEvidence.Scope;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadEvidence.Preparation;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadEvidence.RuntimeAllocation;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadEvidence.Selection;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadEvidence.Snapshot;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadEvidence.Source;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadEvidence.Target;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.gamesession.v1.ReadPrivateWorldPreparationRequest;
import net.firedevops.firemud.gamesession.v1.ReadPrivateWorldPreparationResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PrivateWorldPreparationReadGrpcCodecTest {
  private static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");
  private static final String DIGEST = "sha256:" + "a".repeat(64);

  @Test
  void selectedBuildFramesBindFreshReadSourceAndExactWorldSelector() throws Exception {
    var world = net.firedevops.firemud.common.testing.AuthoringFixtures.startLocationEvidence();
    var scope =
        new Scope(
            world.request().targetNamespace(),
            world.request().canonicalTenantId(),
            "world",
            "private");
    var snapshot = snapshot(1, scope, world.request().canonicalVersionId());
    var request = request(snapshot);
    var build =
        new PrivateWorldPreparationBuildGrpcCodec.Build(
            new PrivateWorldPreparationReadEvidence.Evidence(request, snapshot), world);
    var wire = PrivateWorldPreparationBuildGrpcCodec.toResponse(request, build);
    assertThat(PrivateWorldPreparationBuildGrpcCodec.fromResponse(request, wire)).isEqualTo(build);
    assertThat(build.admissionAllowed()).isFalse();
    assertThatThrownBy(
            () -> PrivateWorldPreparationBuildGrpcCodec.fromResponse(request(snapshot), wire))
        .isInstanceOf(IllegalArgumentException.class);
    var unrelated = snapshot();
    assertThatThrownBy(
            () ->
                new PrivateWorldPreparationBuildGrpcCodec.Build(
                    new PrivateWorldPreparationReadEvidence.Evidence(request(unrelated), unrelated),
                    world))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                PrivateWorldPreparationBuildGrpcCodec.fromResponse(
                    request,
                    wire.toBuilder()
                        .setCanonicalEvidence(
                            ByteString.copyFrom(
                                new byte
                                    [PrivateWorldPreparationBuildGrpcCodec.MAX_RESPONSE_WIRE_BYTES
                                        + 1]))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    var unknown =
        com.google.protobuf.UnknownFieldSet.newBuilder()
            .addField(
                2, com.google.protobuf.UnknownFieldSet.Field.newBuilder().addVarint(1).build())
            .build();
    assertThatThrownBy(
            () ->
                PrivateWorldPreparationBuildGrpcCodec.fromResponse(
                    request, wire.toBuilder().setUnknownFields(unknown).build()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void pendingProtectionFramesBindOperationRequestHoldAndCompleteSource() {
    var snapshot = snapshot();
    var evidence = new PrivateWorldPreparationReadEvidence.Evidence(request(snapshot), snapshot);
    var acquire = new PrivatePreparationProtectionGrpcCodec.Acquire(UUID.randomUUID(), evidence);
    var held =
        new PrivatePreparationProtectionGrpcCodec.Held(
            acquire.requestId(), UUID.randomUUID(), 1, evidence);
    assertThat(
            PrivatePreparationProtectionGrpcCodec.parse(
                PrivatePreparationProtectionGrpcCodec.toWire(acquire)))
        .isEqualTo(acquire);
    var acquired = PrivatePreparationProtectionGrpcCodec.response(acquire, held);
    assertThat(PrivatePreparationProtectionGrpcCodec.fromResponse(acquire, acquired))
        .isEqualTo(held);
    var read =
        new PrivatePreparationProtectionGrpcCodec.Read(
            UUID.randomUUID(), held.requestId(), held.holdId(), held.fence(), held.sourceDigest());
    assertThat(
            PrivatePreparationProtectionGrpcCodec.parse(
                PrivatePreparationProtectionGrpcCodec.toWire(read)))
        .isEqualTo(read);
    assertThat(
            PrivatePreparationProtectionGrpcCodec.fromResponse(
                read, PrivatePreparationProtectionGrpcCodec.response(read, held)))
        .isEqualTo(held);
    assertThat(held.admissionAllowed()).isFalse();
    assertThatThrownBy(() -> PrivatePreparationProtectionGrpcCodec.fromResponse(read, acquired))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                PrivatePreparationProtectionGrpcCodec.fromResponse(
                    new PrivatePreparationProtectionGrpcCodec.Acquire(UUID.randomUUID(), evidence),
                    acquired))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                PrivatePreparationProtectionGrpcCodec.response(
                    new PrivatePreparationProtectionGrpcCodec.Read(
                        UUID.randomUUID(), held.requestId(), held.holdId(), 2, held.sourceDigest()),
                    held))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void protectionRejectsOversizedAndUnknownWireFrames() {
    var snapshot = snapshot();
    var acquire =
        new PrivatePreparationProtectionGrpcCodec.Acquire(
            UUID.randomUUID(),
            new PrivateWorldPreparationReadEvidence.Evidence(request(snapshot), snapshot));
    var wire = PrivatePreparationProtectionGrpcCodec.toWire(acquire);
    var unknown =
        com.google.protobuf.UnknownFieldSet.newBuilder()
            .addField(
                99, com.google.protobuf.UnknownFieldSet.Field.newBuilder().addVarint(1).build())
            .build();
    assertThatThrownBy(
            () ->
                PrivatePreparationProtectionGrpcCodec.parse(
                    wire.toBuilder().setUnknownFields(unknown).build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                PrivatePreparationProtectionGrpcCodec.parse(
                    wire.toBuilder()
                        .setCanonicalEvidence(
                            ByteString.copyFrom(
                                new byte[PrivatePreparationProtectionGrpcCodec.MAX_WIRE_BYTES + 1]))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void exactCompleteRoundTripRetainsOriginalBytesAndNonAdmission() {
    var snapshot = snapshot();
    var request = request(snapshot);
    assertThat(
            PrivateWorldPreparationReadGrpcCodec.parseRequest(
                PrivateWorldPreparationReadGrpcCodec.toRequest(request)))
        .isEqualTo(request);
    var wire = PrivateWorldPreparationReadGrpcCodec.toResponse(request, snapshot);
    var decoded = PrivateWorldPreparationReadGrpcCodec.fromResponse(request, wire);
    assertThat(decoded.snapshot()).isEqualTo(snapshot);
    assertThat(decoded.snapshot().preparation().originalRequest())
        .isEqualTo(snapshot.preparation().originalRequest());
    assertThat(decoded.snapshot().selection().originalRequest())
        .isEqualTo(snapshot.selection().originalRequest());
    assertThat(decoded.snapshot().source().targetPolicy().source().policy().visible()).isTrue();
    assertThat(decoded.snapshot().target().visible()).isFalse();
    assertThat(decoded.snapshot().target().publicProduction()).isFalse();
    assertThat(decoded.snapshot().admissionAllowed()).isFalse();
    assertThat(wire.getSerializedSize())
        .isLessThan(PrivateWorldPreparationReadGrpcCodec.MAX_RESPONSE_WIRE_BYTES);
  }

  @Test
  void responseRequiresCanonicalRuntimeIdentityAndDigestAndRejectsV1() {
    var snapshot = snapshot();
    var request = request(snapshot);
    var wire = PrivateWorldPreparationReadGrpcCodec.toResponse(request, snapshot);
    var bytes = wire.getCanonicalEvidence();
    int frames = frameCount(bytes);

    assertThatThrownBy(
            () ->
                PrivateWorldPreparationReadGrpcCodec.fromResponse(
                    request,
                    wire.toBuilder()
                        .setCanonicalEvidence(
                            replaceFrame(
                                bytes, frames - 4, id("different-runtime-instance").toString()))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                PrivateWorldPreparationReadGrpcCodec.fromResponse(
                    request,
                    wire.toBuilder()
                        .setCanonicalEvidence(
                            replaceFrame(bytes, frames - 3, "sha256:" + "b".repeat(64)))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                PrivateWorldPreparationReadGrpcCodec.fromResponse(
                    request,
                    wire.toBuilder().setCanonicalEvidence(removeFrame(bytes, frames - 2)).build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                PrivateWorldPreparationReadGrpcCodec.fromResponse(
                    request,
                    wire.toBuilder()
                        .setCanonicalEvidence(
                            replaceFrame(
                                bytes, 0, "game-session-private-world-preparation-response/v1"))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void laterTargetRevisionsRoundTripWithoutChangingPublicSourceRevisionOrAdmission() {
    for (long revision : new long[] {2, Long.MAX_VALUE}) {
      var snapshot = snapshot(revision);
      var request = request(snapshot);
      var decoded =
          PrivateWorldPreparationReadGrpcCodec.fromResponse(
              request, PrivateWorldPreparationReadGrpcCodec.toResponse(request, snapshot));
      assertThat(decoded.snapshot()).isEqualTo(snapshot);
      assertThat(decoded.snapshot().target().catalogRevision()).isEqualTo(revision);
      assertThat(decoded.snapshot().source().catalogRevision()).isEqualTo(1);
      assertThat(decoded.snapshot().admissionAllowed()).isFalse();
    }
    for (long invalid : new long[] {0, -1}) {
      assertThatThrownBy(() -> snapshot(invalid)).isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void requestAndResponseRejectUnknownTrailingOversizeAndChangedEcho() {
    var snapshot = snapshot();
    var request = request(snapshot);
    var wireRequest = PrivateWorldPreparationReadGrpcCodec.toRequest(request);
    var wireResponse = PrivateWorldPreparationReadGrpcCodec.toResponse(request, snapshot);
    var unknown =
        UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
            .build();
    assertThatThrownBy(
            () ->
                PrivateWorldPreparationReadGrpcCodec.parseRequest(
                    wireRequest.toBuilder().setUnknownFields(unknown).build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                PrivateWorldPreparationReadGrpcCodec.fromResponse(
                    request, wireResponse.toBuilder().setUnknownFields(unknown).build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                PrivateWorldPreparationReadGrpcCodec.parseRequest(
                    wireRequest.toBuilder()
                        .setCanonicalEvidence(
                            wireRequest
                                .getCanonicalEvidence()
                                .concat(ByteString.copyFromUtf8("0:")))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                PrivateWorldPreparationReadGrpcCodec.fromResponse(
                    request,
                    wireResponse.toBuilder()
                        .setCanonicalEvidence(
                            wireResponse
                                .getCanonicalEvidence()
                                .concat(ByteString.copyFromUtf8("0:")))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                PrivateWorldPreparationReadGrpcCodec.parseRequest(
                    ReadPrivateWorldPreparationRequest.newBuilder()
                        .setCanonicalEvidence(
                            ByteString.copyFrom(
                                new byte
                                    [PrivateWorldPreparationReadGrpcCodec.MAX_REQUEST_WIRE_BYTES]))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                PrivateWorldPreparationReadGrpcCodec.fromResponse(
                    request,
                    ReadPrivateWorldPreparationResponse.newBuilder()
                        .setCanonicalEvidence(
                            ByteString.copyFrom(
                                new byte
                                    [PrivateWorldPreparationReadGrpcCodec.MAX_RESPONSE_WIRE_BYTES]))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                PrivateWorldPreparationReadGrpcCodec.fromResponse(request(snapshot), wireResponse))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsNoncanonicalUuidCountersAndMismatchedExpectedPreparation() {
    var snapshot = snapshot();
    var request = request(snapshot);
    var bytes = PrivateWorldPreparationReadGrpcCodec.toRequest(request).getCanonicalEvidence();
    assertThatThrownBy(
            () ->
                PrivateWorldPreparationReadGrpcCodec.parseRequest(
                    ReadPrivateWorldPreparationRequest.newBuilder()
                        .setCanonicalEvidence(
                            replaceFrame(
                                bytes,
                                3,
                                snapshot
                                    .scope()
                                    .tenantId()
                                    .toString()
                                    .toUpperCase(java.util.Locale.ROOT)))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    var response = PrivateWorldPreparationReadGrpcCodec.toResponse(request, snapshot);
    assertThatThrownBy(
            () ->
                PrivateWorldPreparationReadGrpcCodec.fromResponse(
                    request,
                    response.toBuilder()
                        .setCanonicalEvidence(
                            replaceFrame(response.getCanonicalEvidence(), 4, "01"))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                PrivateWorldPreparationReadGrpcCodec.toResponse(
                    new PrivateWorldPreparationReadGrpcCodec.Request(
                        UUID.randomUUID(), snapshot.scope(), UUID.randomUUID()),
                    snapshot))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new Selection(
                    snapshot.selection().requestId(),
                    null,
                    null,
                    1,
                    DIGEST,
                    NOW,
                    snapshot.selection().originalRequest()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void stableComparisonExcludesOnlyObservationAndCorrelationAndExpiryNeverBecomesReadiness() {
    var snapshot = snapshot();
    var later =
        new Snapshot(
            snapshot.scope(),
            snapshot.selection(),
            snapshot.preparation(),
            snapshot.source(),
            snapshot.target(),
            snapshot.runtimeAllocation(),
            NOW.plusSeconds(1));
    assertThat(PrivateWorldPreparationReadGrpcCodec.stableBytes(later))
        .containsExactly(PrivateWorldPreparationReadGrpcCodec.stableBytes(snapshot));
    assertThatThrownBy(() -> snapshot.requireCurrentAt(snapshot.preparation().forkExpiresAt()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new Snapshot(
                    snapshot.scope(),
                    snapshot.selection(),
                    snapshot.preparation(),
                    snapshot.source(),
                    snapshot.target(),
                    snapshot.runtimeAllocation(),
                    snapshot.preparation().forkExpiresAt()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static PrivateWorldPreparationReadGrpcCodec.Request request(Snapshot snapshot) {
    return new PrivateWorldPreparationReadGrpcCodec.Request(
        UUID.randomUUID(), snapshot.scope(), snapshot.preparation().requestId());
  }

  private static ByteString replaceFrame(ByteString original, int changedIndex, String value) {
    var input = ByteBuffer.wrap(original.toByteArray());
    var out = new ByteArrayOutputStream();
    int index = 0;
    while (input.hasRemaining()) {
      byte[] field = new byte[input.getInt()];
      input.get(field);
      DraftAuthorizationFenceBinding.frame(
          out, index++ == changedIndex ? value.getBytes(StandardCharsets.UTF_8) : field);
    }
    return ByteString.copyFrom(out.toByteArray());
  }

  private static ByteString removeFrame(ByteString original, int removedIndex) {
    var input = ByteBuffer.wrap(original.toByteArray());
    var out = new ByteArrayOutputStream();
    int index = 0;
    while (input.hasRemaining()) {
      byte[] field = new byte[input.getInt()];
      input.get(field);
      if (index++ != removedIndex) DraftAuthorizationFenceBinding.frame(out, field);
    }
    return ByteString.copyFrom(out.toByteArray());
  }

  private static int frameCount(ByteString original) {
    var input = ByteBuffer.wrap(original.toByteArray());
    int count = 0;
    while (input.hasRemaining()) {
      int length = input.getInt();
      input.position(input.position() + length);
      count++;
    }
    return count;
  }

  private static UUID id(String value) {
    return UUID.nameUUIDFromBytes(
        ("PRIVATE_PREPARATION_CODEC:" + value).getBytes(StandardCharsets.UTF_8));
  }

  private static Snapshot snapshot() {
    return snapshot(1);
  }

  private static Snapshot snapshot(long targetRevision) {
    return snapshot(
        targetRevision,
        new Scope(
            "test", UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"), "world", "private"),
        UUID.randomUUID());
  }

  private static Snapshot snapshot(long targetRevision, Scope scope, UUID canonicalVersionId) {
    var policy =
        RealmEntryPolicy.parse(
            """
        {"schemaVersion":1,"worldSlug":"world","worldDisplayName":"World","realmSlug":"private",
         "realmDisplayName":"Private","visible":true,"publicProduction":false,"stateScope":"ISOLATED","entryPolicy":"PRESEEDED_ONLY"}
        """,
            JsonMapper.builder().build());
    var source =
        new Source(
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            DIGEST,
            UUID.randomUUID(),
            DIGEST,
            UUID.randomUUID(),
            DIGEST,
            canonicalVersionId,
            "release:private",
            DIGEST,
            DIGEST,
            DIGEST,
            new RealmPolicyPublishedEvidence.Policy(
                UUID.randomUUID(),
                new RealmPolicySource.Policy(
                    UUID.randomUUID(), UUID.randomUUID(), "policy", policy),
                DIGEST));
    UUID preparationId = UUID.randomUUID(),
        lifecycle = UUID.randomUUID(),
        namespace = UUID.randomUUID();
    Instant expiry = NOW.plusSeconds(60);
    byte[] prepBytes =
        PrivateWorldPreparationReadEvidence.framed(
            "game-session-initial-fresh-playtest-request/v1",
            preparationId.toString(),
            scope.targetNamespace(),
            scope.tenantId().toString(),
            scope.worldSlug(),
            scope.realmSlug(),
            source.catalogRealmId().toString(),
            source.catalogRequestId().toString(),
            "1",
            source.intakeOperationId().toString(),
            source.canonicalVersionId().toString(),
            source.publishedReleaseBundleRef(),
            source.publishedReleaseBundleDigest(),
            "FRESH",
            expiry.toString());
    var preparation =
        new Preparation(
            preparationId,
            lifecycle,
            namespace,
            1,
            "PREPARING",
            "FRESH",
            PrivateWorldPreparationReadEvidence.sha256(prepBytes),
            NOW.minusSeconds(2),
            expiry,
            ByteString.copyFrom(prepBytes));
    UUID selectionId = UUID.randomUUID();
    byte[] selectionBytes =
        PrivateWorldPreparationReadEvidence.framed(
            "game-session-current-private-playtest-selection/v1",
            selectionId.toString(),
            scope.targetNamespace(),
            scope.tenantId().toString(),
            scope.worldSlug(),
            scope.realmSlug(),
            preparationId.toString(),
            lifecycle.toString(),
            "ABSENT");
    var selection =
        new Selection(
            selectionId,
            null,
            null,
            1,
            PrivateWorldPreparationReadEvidence.sha256(selectionBytes),
            NOW.minusSeconds(1),
            ByteString.copyFrom(selectionBytes));
    var target =
        new Target(
            scope,
            UUID.randomUUID(),
            "Private",
            false,
            false,
            "ISOLATED",
            namespace,
            "PRESEEDED_ONLY",
            targetRevision,
            UUID.randomUUID(),
            DIGEST,
            DIGEST,
            preparationId,
            lifecycle,
            1,
            "PREPARING",
            source.catalogRealmId(),
            source.targetPolicy().policyId(),
            DIGEST,
            source.canonicalVersionId(),
            source.publishedReleaseBundleRef(),
            DIGEST);
    RuntimeAllocation allocation =
        PrivateWorldPreparationReadEvidence.runtimeAllocation(
            scope,
            selection,
            preparation,
            target,
            id("runtime-allocation-request"),
            id("runtime-game-instance"));
    return new Snapshot(scope, selection, preparation, source, target, allocation, NOW);
  }
}
