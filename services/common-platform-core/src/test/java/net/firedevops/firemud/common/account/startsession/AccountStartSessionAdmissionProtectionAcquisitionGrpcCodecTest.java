package net.firedevops.firemud.common.account.startsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.UnknownFieldSet;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.account.v1.AcquireStartSessionAdmissionProtectionRequest;
import net.firedevops.firemud.account.v1.AcquireStartSessionAdmissionProtectionResponse;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AccountStartSessionAdmissionProtectionAcquisitionGrpcCodecTest {
  private static final String NAMESPACE = "world-runtime";
  private static final UUID TENANT = uuid("9f8f06b4-36e5-4d11-9c2a-5adfd7f41531");
  private static final UUID ACTOR = uuid("a4f5f4eb-8243-4d42-903a-33495456a622");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void roundTripsCompleteCanonicalRequestAndAccountEvidenceAndRejectsDifferentRequest()
      throws Exception {
    Fixture fixture = fixture("grpc-codec");
    var requestWire =
        AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec.toRequest(fixture.request());
    var request =
        AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec.fromRequestWireBytes(
            requestWire.toByteArray());
    var responseWire =
        AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec.toResponse(
            request, fixture.evidence());
    var evidence =
        AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec.fromResponseWireBytes(
            request, responseWire.toByteArray());

    assertThat(request.canonicalBytes()).containsExactly(fixture.request().canonicalBytes());
    assertThat(evidence.canonicalBytes()).containsExactly(fixture.evidence().canonicalBytes());
    assertThat(evidence.request().canonicalBytes()).containsExactly(request.canonicalBytes());
    assertThatThrownBy(
            () ->
                AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec.fromResponse(
                    fixture("different-request").request(), responseWire))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsUnknownDuplicateAndNoncanonicalEnvelopeFields() throws Exception {
    var fixture = fixture("closed-envelope");
    var request =
        AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec.toRequest(fixture.request());
    var unknownRequest =
        request.toBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                    .build())
            .build();
    var duplicateSchema = request.toBuilder().addSchemaVersion(1).build();
    var unsupportedSchema = request.toBuilder().clearSchemaVersion().addSchemaVersion(2).build();

    assertThatThrownBy(
            () ->
                AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec.fromRequest(
                    unknownRequest))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec.fromRequest(
                    duplicateSchema))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec.fromRequest(
                    unsupportedSchema))
        .isInstanceOf(IllegalArgumentException.class);

    byte[] noncanonicalRequestBytes =
        java.util.Arrays.copyOf(
            fixture.request().canonicalBytes(), fixture.request().canonicalBytes().length + 1);
    noncanonicalRequestBytes[noncanonicalRequestBytes.length - 1] = (byte) ' ';
    var noncanonicalRequest =
        AcquireStartSessionAdmissionProtectionRequest.newBuilder()
            .addSchemaVersion(1)
            .addRequestCanonicalBytes(ByteString.copyFrom(noncanonicalRequestBytes))
            .build();
    assertThatThrownBy(
            () ->
                AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec.fromRequest(
                    noncanonicalRequest))
        .isInstanceOf(IllegalArgumentException.class);

    ByteArrayOutputStream reorderedBytes = new ByteArrayOutputStream();
    CodedOutputStream output = CodedOutputStream.newInstance(reorderedBytes);
    output.writeByteArray(2, fixture.request().canonicalBytes());
    output.writeInt32(1, AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec.SCHEMA_VERSION);
    output.flush();
    assertThatThrownBy(
            () ->
                AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec.fromRequestWireBytes(
                    reorderedBytes.toByteArray()))
        .isInstanceOf(IllegalArgumentException.class);

    var response =
        AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec.toResponse(
            fixture.request(), fixture.evidence());
    var unknownResponse =
        response.toBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                    .build())
            .build();
    var unsupportedResponseSchema =
        response.toBuilder().clearSchemaVersion().addSchemaVersion(2).build();
    assertThatThrownBy(
            () ->
                AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec.fromResponse(
                    fixture.request(), unknownResponse))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec.fromResponse(
                    fixture.request(), unsupportedResponseSchema))
        .isInstanceOf(IllegalArgumentException.class);

    byte[] noncanonicalEvidenceBytes =
        java.util.Arrays.copyOf(
            fixture.evidence().canonicalBytes(), fixture.evidence().canonicalBytes().length + 1);
    noncanonicalEvidenceBytes[noncanonicalEvidenceBytes.length - 1] = (byte) ' ';
    var noncanonicalResponse =
        AcquireStartSessionAdmissionProtectionResponse.newBuilder()
            .addSchemaVersion(1)
            .addEvidenceCanonicalBytes(ByteString.copyFrom(noncanonicalEvidenceBytes))
            .build();
    assertThatThrownBy(
            () ->
                AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec.fromResponse(
                    fixture.request(), noncanonicalResponse))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsOneByteBeyondEachPurposePayloadLimit() {
    var oversizedRequest =
        AcquireStartSessionAdmissionProtectionRequest.newBuilder()
            .addSchemaVersion(1)
            .addRequestCanonicalBytes(
                ByteString.copyFrom(
                    new byte
                        [AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec
                                .MAX_REQUEST_CANONICAL_BYTES
                            + 1]))
            .build();
    var oversizedResponse =
        AcquireStartSessionAdmissionProtectionResponse.newBuilder()
            .addSchemaVersion(1)
            .addEvidenceCanonicalBytes(
                ByteString.copyFrom(
                    new byte
                        [AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec
                                .MAX_EVIDENCE_CANONICAL_BYTES
                            + 1]))
            .build();

    assertThatThrownBy(
            () ->
                AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec.fromRequest(
                    oversizedRequest))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec.fromResponse(
                    fixture("oversized-response").request(), oversizedResponse))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static Fixture fixture(String requestId) throws Exception {
    StartSessionPostAuthorizationExecutionTuple tuple = originalTuple(requestId);
    var hold =
        new WorldCanonicalInitialAdmissionHold.HoldIdentity(
            new WorldCanonicalInitialAdmissionHold.Request(
                NAMESPACE,
                TENANT,
                "earth",
                uuid("3916f423-2870-426a-a8aa-5e3f97412613"),
                uuid("54e6094e-11bb-4f4c-93ee-a52f715b530b"),
                "SHARED",
                uuid("a55b2e10-9a24-4adb-adb8-6fc66fe3b8e9"),
                uuid("d7280ec0-5979-4b62-8418-e9f139415184"),
                3L,
                tuple.controlPlaneRequestId(),
                "c".repeat(64),
                WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.NO_PRIOR_POINTER,
                12L,
                null),
            uuid("0db7344a-1e67-4b95-905a-83dc9c472f0c"),
            uuid("52a14272-f9e4-4f67-97c9-62247b5fbcc1"));
    var request =
        AccountStartSessionAdmissionProtectionRequest.create(
            tuple.canonicalBytes(),
            StartSessionAccountRedemptionProjection.fromOriginalTuple(tuple),
            uuid("f1a3ab1e-9147-4667-b6c4-6eb5119e8a31"),
            uuid("ec13cc04-ec15-4eb8-a018-c2c5e8da65f8"),
            7L,
            Instant.parse("2026-10-09T10:20:30.456789Z"),
            uuid("47b3be7f-a32f-4e19-8916-8c3b8da07a82"),
            11L,
            hold);
    byte[] capture = captureReference(tuple);
    var evidence =
        AccountStartSessionAdmissionProtectionEvidence.create(
            request,
            uuid("6b763f1d-c5bc-4080-b499-d29debc0a7b8"),
            13L,
            capture,
            sha256(capture),
            List.of(
                source(SourceKind.ACCOUNT, TENANT.toString(), "account-source"),
                source(SourceKind.ISSUER, "https://issuer.example", "issuer-source")));
    return new Fixture(request, evidence);
  }

  private static StartSessionPostAuthorizationExecutionTuple originalTuple(String requestId)
      throws IOException {
    var preTuple =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            requestId,
            ACTOR,
            new StartSessionOperatorAction(
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
                new StartSessionOperatorAction.Scope(TENANT, NAMESPACE),
                new StartSessionOperatorAction.Target(
                    91L, uuid("36aa9ce5-0ebc-4c14-9f6b-d160edc6059a")),
                StartSessionOperatorAction.ExpectedVersion.ABSENT,
                new StartSessionOperatorAction.Mutation(
                    StartSessionOperatorAction.ClientIp.absent()),
                "canonical original StartSession admission attempt"));
    return StartSessionPostAuthorizationExecutionTuple.createHuman(
        preTuple,
        "spiffe://firemud/ns/" + NAMESPACE + "/sa/logging-admin-service",
        "arfp/v1/test-key/" + "b".repeat(64),
        uuid("7c005b65-fcb1-4ac9-a714-f3d0f449edcf"),
        19L,
        authorityBundle(preTuple),
        new StartSessionAuthorityEvidenceBundle.BundleReference(
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "17",
            "23",
            "18446744073709551615"));
  }

  private static byte[] authorityBundle(StartSessionPreAuthorizationReservationTuple tuple)
      throws IOException {
    String tenant = TENANT.toString();
    Map<String, Object> projection =
        Map.of(
            "sourceType", "ACCOUNT",
            "sourceEvidenceId", "sha256:" + "a".repeat(64),
            "sourceEvidenceVersion", "17",
            "projectionStatus", "CURRENT",
            "evaluatedAt", "2026-10-09T00:00:00Z",
            "expiresAt", "2026-10-09T00:05:00Z");
    Map<String, Object> operation =
        Map.of(
            "issuanceOperationId", "f5d044bd-7e5f-4e2d-9859-9025cbdcc60f",
            "controlPlaneRequestId", tuple.controlPlaneRequestId(),
            "actionFamilyRequestIdentity",
                Map.of(
                    "requestIdentityKind",
                    "controlPlaneRequestId",
                    "requestId",
                    tuple.controlPlaneRequestId()),
            "mutationDigest", tuple.mutationDigest());
    Map<String, Object> authority =
        Map.of(
            "issuerAuthGeneration", 1L,
            "accountAuthorityGeneration", 2L,
            "tenantAuthorityGeneration", Map.of(tenant, 3L),
            "membershipAuthorityGeneration", Map.of(tenant, 4L),
            "privateRealmGrantVersions", List.of());
    Map<String, Object> issuanceEvidence =
        Map.of(
            "evidenceType", StartSessionAuthorityEvidenceBundle.HUMAN_EVIDENCE_TYPE,
            "actorAccountId", ACTOR.toString(),
            "controlUiTokenJti", "a681bba7-c215-4cf1-a35b-14348912cbdc",
            "role", "tenantAdmin",
            "accountGeneration", "2",
            "tenantGeneration", "3");
    Map<String, Object> bundle =
        Map.of(
            "bundleVersion",
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "authorityScope",
            Map.of(
                "scope", Map.of("tenantId", tenant, "targetNamespace", NAMESPACE),
                "actionFamily", tuple.actionFamily(),
                "applicableAccountId", ACTOR.toString(),
                "applicableTenantId", tenant),
            "accountProjectionEvidence",
            projection,
            "issuanceOperationIdentity",
            operation,
            "issuanceKind",
            "human_operator",
            "authorityTuple",
            authority,
            "membershipVersion",
            Map.of(tenant, 5L),
            "issuanceFence",
            "23",
            "issuanceEvidence",
            issuanceEvidence);
    return canonical(bundle);
  }

  private static byte[] captureReference(StartSessionPostAuthorizationExecutionTuple tuple)
      throws Exception {
    return canonical(
        Map.of(
            "schema",
            AccountStartSessionAdmissionProtectionEvidence.SOURCE_CAPTURE_REFERENCE_SCHEMA,
            "controlPlaneRequestId",
            tuple.controlPlaneRequestId(),
            "capturedAt",
            "2026-10-09T10:11:12.123Z",
            "bundleReference",
            Map.of(
                "bundleVersion",
                StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
                "sourceVersion",
                "17",
                "sourceFence",
                "23",
                "linearization",
                "18446744073709551615"),
            "snapshotSha256",
            "d".repeat(64)));
  }

  private static SourceEvidence source(SourceKind kind, String scope, String value) {
    return new SourceEvidence(
        kind, scope, "2", "17", null, null, value.getBytes(StandardCharsets.UTF_8));
  }

  private static byte[] canonical(Object value) throws IOException {
    return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
  }

  private static String sha256(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private record Fixture(
      AccountStartSessionAdmissionProtectionRequest request,
      AccountStartSessionAdmissionProtectionEvidence evidence) {}
}
