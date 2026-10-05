package net.firedevops.firemud.common.gamedesign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.UnknownFieldSet;
import java.util.UUID;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamedesign.v1.GetAuthoredWorldVersionStateRequest;
import net.firedevops.firemud.gamedesign.v1.GetAuthoredWorldVersionStateResponse;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import org.junit.jupiter.api.Test;

class AuthoredWorldVersionStateGrpcCodecTest {
  private static final UUID READ_REQUEST_ID = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID TENANT_ID = uuid("22222222-2222-4222-8222-222222222222");
  private static final UUID SOURCE_OPERATION_ID = uuid("33333333-3333-4333-8333-333333333333");
  private static final UUID REGISTRATION_REQUEST_ID = uuid("44444444-4444-4444-8444-444444444444");
  private static final UUID CANONICAL_VERSION_ID = uuid("abcdefab-cdef-4abc-8def-abcdefabcdef");

  @Test
  void requestAndCompleteEvidenceRoundTrip() {
    var request = request();
    var wireRequest = AuthoredWorldVersionStateGrpcCodec.toRequest(request);
    var decodedRequest = AuthoredWorldVersionStateGrpcCodec.fromRequest(wireRequest);
    var evidence = evidence(request);
    var response = AuthoredWorldVersionStateGrpcCodec.toResponse(evidence);

    assertThat(decodedRequest).isEqualTo(request);
    assertThat(response.getEvidence().getCanonicalVersionId())
        .isEqualTo(CANONICAL_VERSION_ID.toString());
    assertThat(AuthoredWorldVersionStateGrpcCodec.fromResponse(request, response))
        .isEqualTo(evidence);
  }

  @Test
  void requestRejectsUnknownFieldsUnsupportedSchemaAndInvalidCounters() {
    GetAuthoredWorldVersionStateRequest valid =
        AuthoredWorldVersionStateGrpcCodec.toRequest(request());

    assertThatThrownBy(
            () ->
                AuthoredWorldVersionStateGrpcCodec.fromRequest(
                    valid.toBuilder().setUnknownFields(unknownFields()).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");
    assertThatThrownBy(
            () ->
                AuthoredWorldVersionStateGrpcCodec.fromRequest(
                    valid.toBuilder().setSchemaVersion(2).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("request is invalid");
    assertThatThrownBy(
            () ->
                AuthoredWorldVersionStateGrpcCodec.fromRequest(
                    valid.toBuilder().setVersionId(0L).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("request is invalid");
    assertThatThrownBy(
            () ->
                AuthoredWorldVersionStateGrpcCodec.fromRequest(
                    valid.toBuilder()
                        .setReadRequestId("AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA")
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("request is invalid");
  }

  @Test
  void responseRejectsUnknownFieldsAtEveryCarrierAndContradictoryEchoes() {
    var request = request();
    GetAuthoredWorldVersionStateResponse valid =
        AuthoredWorldVersionStateGrpcCodec.toResponse(evidence(request));
    var wireEvidence = valid.getEvidence();

    assertInvalidResponse(
        request, valid.toBuilder().setUnknownFields(unknownFields()).build(), "unsupported fields");
    assertInvalidResponse(
        request,
        valid.toBuilder()
            .setEvidence(wireEvidence.toBuilder().setUnknownFields(unknownFields()))
            .build(),
        "unsupported fields");
    assertInvalidResponse(
        request,
        valid.toBuilder()
            .setEvidence(
                wireEvidence.toBuilder()
                    .setSourceEvidence(
                        wireEvidence.getSourceEvidence().toBuilder()
                            .setUnknownFields(unknownFields())))
            .build(),
        "unsupported fields");
    assertInvalidResponse(
        request,
        valid.toBuilder()
            .setEvidence(
                wireEvidence.toBuilder()
                    .setReadRequestId(uuid("55555555-5555-4555-8555-555555555555").toString()))
            .build(),
        "response is invalid");
  }

  @Test
  void responseRejectsMissingReceiptBadDigestStateAndEpoch() {
    var request = request();
    GetAuthoredWorldVersionStateResponse valid =
        AuthoredWorldVersionStateGrpcCodec.toResponse(evidence(request));
    var wireEvidence = valid.getEvidence();

    assertThatThrownBy(
            () ->
                AuthoredWorldVersionStateGrpcCodec.fromResponse(
                    request, GetAuthoredWorldVersionStateResponse.getDefaultInstance()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no evidence");
    assertInvalidResponse(
        request,
        valid.toBuilder().setEvidence(wireEvidence.toBuilder().clearSourceEvidence()).build(),
        "complete source receipt");
    assertInvalidResponse(
        request,
        valid.toBuilder()
            .setEvidence(wireEvidence.toBuilder().setEvidenceDigest("sha256:" + "f".repeat(64)))
            .build(),
        "response is invalid");
    assertInvalidResponse(
        request,
        valid.toBuilder()
            .setEvidence(
                wireEvidence.toBuilder()
                    .setVersionState(VersionLifecycleState.VERSION_LIFECYCLE_STATE_UNSPECIFIED))
            .build(),
        "response is invalid");
    assertInvalidResponse(
        request,
        valid.toBuilder().setEvidence(wireEvidence.toBuilder().setVersionStateValue(99)).build(),
        "response is invalid");
    assertInvalidResponse(
        request,
        valid.toBuilder().setEvidence(wireEvidence.toBuilder().setVersionStateEpoch(0L)).build(),
        "response is invalid");
    assertInvalidResponse(
        request,
        valid.toBuilder()
            .setEvidence(
                wireEvidence.toBuilder()
                    .setSourceEvidence(
                        wireEvidence.getSourceEvidence().toBuilder()
                            .setWorldDisplayName("Changed café")))
            .build(),
        "response is invalid");
  }

  @Test
  void responseRequiresCanonicalNonNilVersionUuidAndRejectsSubstitution() {
    var request = request();
    GetAuthoredWorldVersionStateResponse valid =
        AuthoredWorldVersionStateGrpcCodec.toResponse(evidence(request));
    var wireEvidence = valid.getEvidence();

    assertInvalidResponse(
        request,
        valid.toBuilder().setEvidence(wireEvidence.toBuilder().clearCanonicalVersionId()).build(),
        "response is invalid");
    assertInvalidResponse(
        request,
        valid.toBuilder()
            .setEvidence(wireEvidence.toBuilder().setCanonicalVersionId("not-a-uuid"))
            .build(),
        "response is invalid");
    assertInvalidResponse(
        request,
        valid.toBuilder().setEvidence(wireEvidence.toBuilder().setCanonicalVersionId("")).build(),
        "response is invalid");
    assertInvalidResponse(
        request,
        valid.toBuilder()
            .setEvidence(
                wireEvidence.toBuilder()
                    .setCanonicalVersionId("00000000-0000-0000-0000-000000000000"))
            .build(),
        "response is invalid");
    assertInvalidResponse(
        request,
        valid.toBuilder()
            .setEvidence(
                wireEvidence.toBuilder()
                    .setCanonicalVersionId("ABCDEFAB-CDEF-4ABC-8DEF-ABCDEFABCDEF"))
            .build(),
        "response is invalid");
    assertInvalidResponse(
        request,
        valid.toBuilder()
            .setEvidence(
                wireEvidence.toBuilder()
                    .setCanonicalVersionId("fedcbafe-dcba-4fed-8cba-fedcbafedcba"))
            .build(),
        "response is invalid");
  }

  private static void assertInvalidResponse(
      AuthoredWorldVersionStateEvidence.Request request,
      GetAuthoredWorldVersionStateResponse response,
      String message) {
    assertThatThrownBy(() -> AuthoredWorldVersionStateGrpcCodec.fromResponse(request, response))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(message);
  }

  private static AuthoredWorldVersionStateEvidence.Request request() {
    AuthoredWorldSourceEvidence source = sourceEvidence();
    return new AuthoredWorldVersionStateEvidence.Request(
        1,
        "test",
        READ_REQUEST_ID,
        TENANT_ID,
        "cafe-coast",
        SOURCE_OPERATION_ID,
        source.evidenceDigest(),
        19L);
  }

  private static AuthoredWorldVersionStateEvidence evidence(
      AuthoredWorldVersionStateEvidence.Request request) {
    return AuthoredWorldVersionStateEvidence.create(
        request,
        sourceEvidence(),
        CANONICAL_VERSION_ID,
        VersionLifecycleState.VERSION_LIFECYCLE_STATE_DRAFT,
        7L);
  }

  private static AuthoredWorldSourceEvidence sourceEvidence() {
    String displayName = "Café 🐉";
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            "test", REGISTRATION_REQUEST_ID, TENANT_ID, "tenant-one", "cafe-coast", displayName);
    return new AuthoredWorldSourceEvidence(
        1,
        "test",
        REGISTRATION_REQUEST_ID,
        SOURCE_OPERATION_ID,
        requestDigest,
        TENANT_ID,
        "tenant-one",
        "cafe-coast",
        displayName,
        42L,
        "game-owner-tenant",
        "NEW_GAME_ROW",
        AuthoredWorldSourceDigest.evidenceDigest(
            "test",
            REGISTRATION_REQUEST_ID,
            SOURCE_OPERATION_ID,
            requestDigest,
            TENANT_ID,
            "tenant-one",
            "cafe-coast",
            displayName,
            42L,
            "game-owner-tenant",
            "NEW_GAME_ROW"));
  }

  private static UnknownFieldSet unknownFields() {
    return UnknownFieldSet.newBuilder()
        .addField(100, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
        .build();
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
