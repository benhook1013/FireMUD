package net.firedevops.firemud.common.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.time.Instant;
import java.util.UUID;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterOwnerReadResponse;
import org.junit.jupiter.api.Test;

class CanonicalGameplayRosterOwnerReadEvidenceTest {
  private static final String TARGET_NAMESPACE = "gameplay";
  private static final UUID ACCOUNT = uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID REALM = uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
  private static final UUID PLAYABLE_NAMESPACE = uuid("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
  private static final UUID INSTANCE = uuid("dddddddd-dddd-4ddd-8ddd-dddddddddddd");
  private static final Instant TERMINAL_AT = Instant.parse("2026-10-08T03:04:05Z");

  @Test
  void acceptsExactCommittedWorldHoldAndCompletePreseededPolicySet() throws Exception {
    var expected = fixture();
    assertThat(expected.gameSessionOwnerProof().outcome())
        .isEqualTo(GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED);
    assertThat(expected.gameSessionOwnerProof().holdIdentity().request().canonicalTenantId())
        .isEqualTo(expected.request().canonicalTenantUuid());
    assertThat(expected.publishedPolicySetEvidence().policyCount()).isEqualTo(1);
    assertThat(expected.selectedPolicyEvidence().policy().entryPolicy())
        .isEqualTo(
            net.firedevops.firemud.common.publication.RealmEntryPolicy.EntryPolicy.PRESEEDED_ONLY);
  }

  @Test
  void rejectsNoncommittedStaleOrChangedRouteAndPolicyEvidence() throws Exception {
    var good = fixture();
    var pending = pendingProof(good.gameSessionOwnerProof().holdIdentity());
    assertThatThrownBy(
            () ->
                new CanonicalGameplayRosterOwnerReadEvidence(
                    good.request(), "1".repeat(64), pending, good.publishedPolicySetEvidence()))
        .isInstanceOf(IllegalArgumentException.class);

    var staleRequest =
        new CanonicalGameplayRosterOwnerReadEvidence.Request(
            good.request().requestUuid(),
            ACCOUNT,
            TARGET_NAMESPACE,
            good.request().canonicalTenantUuid(),
            "earth",
            REALM,
            "main",
            PLAYABLE_NAMESPACE,
            "SHARED",
            INSTANCE,
            good.request().canonicalVersionUuid(),
            71L,
            74L,
            82L);
    assertThatThrownBy(
            () ->
                new CanonicalGameplayRosterOwnerReadEvidence(
                    staleRequest,
                    good.admissionPointerSnapshotDigest(),
                    good.gameSessionOwnerProof(),
                    good.publishedPolicySetEvidence()))
        .isInstanceOf(IllegalArgumentException.class);

    var wrongRealm =
        new CanonicalGameplayRosterOwnerReadEvidence.Request(
            good.request().requestUuid(),
            ACCOUNT,
            TARGET_NAMESPACE,
            good.request().canonicalTenantUuid(),
            "earth",
            REALM,
            "unpublished",
            PLAYABLE_NAMESPACE,
            "SHARED",
            INSTANCE,
            good.request().canonicalVersionUuid(),
            71L,
            73L,
            82L);
    assertThatThrownBy(
            () ->
                new CanonicalGameplayRosterOwnerReadEvidence(
                    wrongRealm,
                    good.admissionPointerSnapshotDigest(),
                    good.gameSessionOwnerProof(),
                    good.publishedPolicySetEvidence()))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(
            () ->
                new CanonicalGameplayRosterOwnerReadEvidence(
                    good.request(),
                    "A".repeat(64),
                    good.gameSessionOwnerProof(),
                    good.publishedPolicySetEvidence()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void grpcCodecRoundTripsCompleteProofAndRejectsUnknownPartialOrChangedEcho() throws Exception {
    var evidence = fixture();
    var request = evidence.request();
    var wireRequest = CanonicalGameplayRosterOwnerReadGrpcCodec.toRequest(request);
    assertThat(CanonicalGameplayRosterOwnerReadGrpcCodec.fromRequest(wireRequest))
        .isEqualTo(request);

    var response = CanonicalGameplayRosterOwnerReadGrpcCodec.toResponse(request, evidence);
    assertThat(response.hasRequest()).isTrue();
    assertThat(response.getRequest().getCanonicalAccountUuid()).isEqualTo(ACCOUNT.toString());
    assertThat(response.getAdmissionPointerSnapshotDigest())
        .isEqualTo(evidence.admissionPointerSnapshotDigest());
    assertThat(CanonicalGameplayRosterOwnerReadGrpcCodec.fromResponse(request, response))
        .isEqualTo(evidence);

    var unknownRequest =
        wireRequest.toBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(100, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
                    .build())
            .build();
    assertThatThrownBy(() -> CanonicalGameplayRosterOwnerReadGrpcCodec.fromRequest(unknownRequest))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                CanonicalGameplayRosterOwnerReadGrpcCodec.fromRequest(
                    wireRequest.toBuilder().setCanonicalAccountUuid("not-a-uuid").build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                CanonicalGameplayRosterOwnerReadGrpcCodec.fromRequest(
                    wireRequest.toBuilder()
                        .setExpectedPointerVersion(Long.MAX_VALUE * -1L)
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                CanonicalGameplayRosterOwnerReadGrpcCodec.fromRequest(
                    wireRequest.toBuilder().setWorldSlug("x".repeat(3000)).build()))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(
            () ->
                CanonicalGameplayRosterOwnerReadGrpcCodec.fromResponse(
                    request, GetCanonicalGameplayRosterOwnerReadResponse.getDefaultInstance()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                CanonicalGameplayRosterOwnerReadGrpcCodec.fromResponse(
                    request,
                    response.toBuilder()
                        .setRequest(
                            wireRequest.toBuilder()
                                .setCanonicalAccountUuid(
                                    uuid("99999999-9999-4999-8999-999999999999").toString())
                                .build())
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                CanonicalGameplayRosterOwnerReadGrpcCodec.fromResponse(
                    request,
                    response.toBuilder()
                        .setUnknownFields(
                            UnknownFieldSet.newBuilder()
                                .addField(
                                    101, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
                                .build())
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                CanonicalGameplayRosterOwnerReadGrpcCodec.fromResponse(
                    request, response.toBuilder().clearPublishedPolicySetEvidence().build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                CanonicalGameplayRosterOwnerReadGrpcCodec.fromResponse(
                    request,
                    response.toBuilder().setAdmissionPointerSnapshotDigest("A".repeat(64)).build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                CanonicalGameplayRosterOwnerReadGrpcCodec.fromResponse(
                    request,
                    response.toBuilder()
                        .setPublishedPolicySetEvidence(
                            ByteString.copyFrom(
                                new byte
                                    [CanonicalGameplayRosterOwnerReadEvidence.MAX_POLICY_SET_BYTES
                                        + 1]))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);

    var pending = pendingProof(evidence.gameSessionOwnerProof().holdIdentity());
    var nonCommittedResponse =
        GetCanonicalGameplayRosterOwnerReadResponse.newBuilder()
            .setRequest(wireRequest)
            .setGameSessionOwnerProof(
                ByteString.copyFrom(
                    GameSessionCanonicalInitialAdmissionOwnerProofCodec.canonicalBytes(pending)))
            .setPublishedPolicySetEvidence(
                ByteString.copyFrom(evidence.publishedPolicySetEvidence().canonicalBytes()))
            .build();
    assertThatThrownBy(
            () ->
                CanonicalGameplayRosterOwnerReadGrpcCodec.fromResponse(
                    request, nonCommittedResponse))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static CanonicalGameplayRosterOwnerReadEvidence fixture() throws Exception {
    var set = PublishedRealmEntryPolicySetEvidenceTest.preseededSetFixture();
    UUID tenant = set.target().canonicalTenantId();
    UUID version = set.target().canonicalVersionId();
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
    var holdIdentity =
        new WorldCanonicalInitialAdmissionHold.HoldIdentity(
            holdRequest,
            uuid("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
            uuid("ffffffff-ffff-4fff-8fff-ffffffffffff"));
    var proof =
        new GameSessionCanonicalInitialAdmissionOwnerProof(
            holdIdentity,
            GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED,
            73L,
            91L,
            "sha256:" + "b".repeat(64),
            false,
            TERMINAL_AT);
    var request =
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
    return new CanonicalGameplayRosterOwnerReadEvidence(request, "1".repeat(64), proof, set);
  }

  private static GameSessionCanonicalInitialAdmissionOwnerProof pendingProof(
      WorldCanonicalInitialAdmissionHold.HoldIdentity identity) {
    return new GameSessionCanonicalInitialAdmissionOwnerProof(
        identity,
        GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.PENDING,
        null,
        null,
        null,
        false,
        null);
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
