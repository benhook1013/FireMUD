package net.firedevops.firemud.common.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.UnknownFieldSet;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.gamedesign.v1.ReadFreshTenantCreationReservationResponse;
import org.junit.jupiter.api.Test;

class FreshTenantCreationReservationGrpcCodecTest {
  private static final String NAMESPACE = "test";
  private static final UUID READ_REQUEST_ID =
      UUID.fromString("12345678-1234-4234-8234-123456789abc");
  private static final UUID CREATION_REQUEST_ID =
      UUID.fromString("22345678-1234-4234-8234-123456789abc");
  private static final UUID OPERATION_ID = UUID.fromString("32345678-1234-4234-8234-123456789abc");
  private static final UUID TENANT_ID = UUID.fromString("42345678-1234-4234-8234-123456789abc");
  private static final String SOURCE_KEY = "source-key";
  private static final String NAME = "Wörld";
  private static final String REQUEST_DIGEST =
      GameTenantCreationDigest.requestDigest(
          NAMESPACE, CREATION_REQUEST_ID, SOURCE_KEY, NAME, null);
  private static final FreshTenantCreationReservationGrpcCodec.ReadRequest REQUEST =
      new FreshTenantCreationReservationGrpcCodec.ReadRequest(
          NAMESPACE, READ_REQUEST_ID, CREATION_REQUEST_ID, REQUEST_DIGEST, OPERATION_ID, TENANT_ID);

  @Test
  void encodesTheExactClosedReadTupleAndRetainsDescriptionPresence() {
    var encoded = FreshTenantCreationReservationGrpcCodec.toReadRequest(REQUEST);
    assertThat(encoded.getSchemaVersion()).isEqualTo(1);
    assertThat(encoded.getTargetNamespace()).isEqualTo(NAMESPACE);
    assertThat(encoded.getReadRequestId()).isEqualTo(READ_REQUEST_ID.toString());
    assertThat(encoded.getCreationRequestId()).isEqualTo(CREATION_REQUEST_ID.toString());
    assertThat(encoded.getExpectedRequestDigest()).isEqualTo(REQUEST_DIGEST);
    assertThat(encoded.getExpectedCreationOperationId()).isEqualTo(OPERATION_ID.toString());
    assertThat(encoded.getExpectedCanonicalTenantId()).isEqualTo(TENANT_ID.toString());
    assertThat(encoded.getUnknownFields().asMap()).isEmpty();

    var absent = FreshTenantCreationReservationGrpcCodec.fromReadResponse(REQUEST, response(null));
    var presentEmptyRequest =
        new FreshTenantCreationReservationGrpcCodec.ReadRequest(
            NAMESPACE,
            READ_REQUEST_ID,
            CREATION_REQUEST_ID,
            GameTenantCreationDigest.requestDigest(
                NAMESPACE, CREATION_REQUEST_ID, SOURCE_KEY, NAME, ""),
            OPERATION_ID,
            TENANT_ID);
    var presentEmpty =
        FreshTenantCreationReservationGrpcCodec.fromReadResponse(
            presentEmptyRequest, response(presentEmptyRequest, ""));
    assertThat(absent.description()).isNull();
    assertThat(presentEmpty.description()).isEmpty();
    assertThat(absent.evidenceDigest()).isEqualTo(referenceReservationDigest(REQUEST_DIGEST, null));
    assertThat(presentEmpty.evidenceDigest())
        .isEqualTo(referenceReservationDigest(presentEmptyRequest.expectedRequestDigest(), ""));
    assertThat(absent.evidenceDigest()).isNotEqualTo(presentEmpty.evidenceDigest());
  }

  @Test
  void rejectsEveryChangedReservationFieldUnknownFieldsAndMissingRequiredValues() {
    ReadFreshTenantCreationReservationResponse valid = response(null);
    UnknownFieldSet unknownFields =
        UnknownFieldSet.newBuilder()
            .addField(100, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
            .build();
    for (ReadFreshTenantCreationReservationResponse changed :
        List.of(
            valid.toBuilder().setSchemaVersion(2).build(),
            valid.toBuilder().setTargetNamespace("other").build(),
            valid.toBuilder().setReadRequestId(CREATION_REQUEST_ID.toString()).build(),
            valid.toBuilder().setCreationRequestId(READ_REQUEST_ID.toString()).build(),
            valid.toBuilder().setRequestDigest("sha256:" + "0".repeat(64)).build(),
            valid.toBuilder().setCreationOperationId(READ_REQUEST_ID.toString()).build(),
            valid.toBuilder().setCanonicalTenantId(READ_REQUEST_ID.toString()).build(),
            valid.toBuilder().setSourceGameTenantKey("changed-key").build(),
            valid.toBuilder().setName("Changed").build(),
            valid.toBuilder().setDescription("").build(),
            valid.toBuilder().setEvidenceDigest("sha256:" + "0".repeat(64)).build(),
            valid.toBuilder().setUnknownFields(unknownFields).build(),
            valid.toBuilder().clearReadRequestId().build(),
            valid.toBuilder().clearName().build())) {
      assertThatThrownBy(
              () -> FreshTenantCreationReservationGrpcCodec.fromReadResponse(REQUEST, changed))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void exactRequestMustBindNamespaceReadIdentityAndBothExpectedOwnerIds() {
    var valid = response(null);
    for (FreshTenantCreationReservationGrpcCodec.ReadRequest changed :
        List.of(
            new FreshTenantCreationReservationGrpcCodec.ReadRequest(
                "other",
                READ_REQUEST_ID,
                CREATION_REQUEST_ID,
                REQUEST_DIGEST,
                OPERATION_ID,
                TENANT_ID),
            new FreshTenantCreationReservationGrpcCodec.ReadRequest(
                NAMESPACE,
                OPERATION_ID,
                CREATION_REQUEST_ID,
                REQUEST_DIGEST,
                OPERATION_ID,
                TENANT_ID),
            new FreshTenantCreationReservationGrpcCodec.ReadRequest(
                NAMESPACE,
                READ_REQUEST_ID,
                CREATION_REQUEST_ID,
                REQUEST_DIGEST,
                TENANT_ID,
                TENANT_ID),
            new FreshTenantCreationReservationGrpcCodec.ReadRequest(
                NAMESPACE,
                READ_REQUEST_ID,
                CREATION_REQUEST_ID,
                REQUEST_DIGEST,
                OPERATION_ID,
                READ_REQUEST_ID))) {
      assertThatThrownBy(
              () -> FreshTenantCreationReservationGrpcCodec.fromReadResponse(changed, valid))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void rejectsMalformedSelectorsAndContradictoryReservationInputs() {
    for (Runnable invalid :
        List.<Runnable>of(
            () ->
                new FreshTenantCreationReservationGrpcCodec.ReadRequest(
                    "Other",
                    READ_REQUEST_ID,
                    CREATION_REQUEST_ID,
                    REQUEST_DIGEST,
                    OPERATION_ID,
                    TENANT_ID),
            () ->
                new FreshTenantCreationReservationGrpcCodec.ReadRequest(
                    NAMESPACE,
                    READ_REQUEST_ID,
                    READ_REQUEST_ID,
                    REQUEST_DIGEST,
                    OPERATION_ID,
                    TENANT_ID),
            () ->
                new FreshTenantCreationReservationGrpcCodec.ReadRequest(
                    NAMESPACE,
                    new UUID(0L, 0L),
                    CREATION_REQUEST_ID,
                    REQUEST_DIGEST,
                    OPERATION_ID,
                    TENANT_ID),
            () ->
                new FreshTenantCreationReservationGrpcCodec.ReadRequest(
                    NAMESPACE,
                    READ_REQUEST_ID,
                    CREATION_REQUEST_ID,
                    "bad",
                    OPERATION_ID,
                    TENANT_ID),
            () ->
                FreshTenantCreationReservationEvidence.fromReservation(
                    1,
                    NAMESPACE,
                    CREATION_REQUEST_ID,
                    REQUEST_DIGEST,
                    OPERATION_ID,
                    TENANT_ID,
                    SOURCE_KEY,
                    NAME,
                    "changed"))) {
      assertThatThrownBy(invalid::run).isInstanceOf(IllegalArgumentException.class);
    }
  }

  private static ReadFreshTenantCreationReservationResponse response(String description) {
    return response(REQUEST, description);
  }

  private static ReadFreshTenantCreationReservationResponse response(
      FreshTenantCreationReservationGrpcCodec.ReadRequest request, String description) {
    FreshTenantCreationReservationEvidence evidence =
        FreshTenantCreationReservationEvidence.fromReservation(
            1,
            NAMESPACE,
            CREATION_REQUEST_ID,
            request.expectedRequestDigest(),
            OPERATION_ID,
            TENANT_ID,
            SOURCE_KEY,
            NAME,
            description);
    var builder =
        ReadFreshTenantCreationReservationResponse.newBuilder()
            .setSchemaVersion(evidence.schemaVersion())
            .setTargetNamespace(evidence.targetNamespace())
            .setReadRequestId(request.readRequestId().toString())
            .setCreationRequestId(evidence.creationRequestId().toString())
            .setRequestDigest(evidence.requestDigest())
            .setCreationOperationId(evidence.operationId().toString())
            .setCanonicalTenantId(evidence.canonicalTenantId().toString())
            .setSourceGameTenantKey(evidence.sourceGameTenantKey())
            .setName(evidence.name())
            .setEvidenceDigest(evidence.evidenceDigest());
    if (description != null) {
      builder.setDescription(description);
    }
    return builder.build();
  }

  private static String referenceReservationDigest(String requestDigest, String description) {
    String[] segments = {
      "game-design-tenant-creation-reservation/v1",
      "1",
      NAMESPACE,
      CREATION_REQUEST_ID.toString(),
      requestDigest,
      OPERATION_ID.toString(),
      TENANT_ID.toString(),
      SOURCE_KEY,
      NAME,
      description == null ? "absent" : "present",
      description == null ? "" : description
    };
    ByteArrayOutputStream framed = new ByteArrayOutputStream();
    for (String segment : segments) {
      byte[] bytes = segment.getBytes(StandardCharsets.UTF_8);
      framed.writeBytes(Integer.toString(bytes.length).getBytes(StandardCharsets.UTF_8));
      framed.write(':');
      framed.writeBytes(bytes);
    }
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(framed.toByteArray());
      return "sha256:" + HexFormat.of().formatHex(digest);
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
