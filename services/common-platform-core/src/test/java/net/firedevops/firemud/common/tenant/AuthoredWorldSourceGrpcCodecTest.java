package net.firedevops.firemud.common.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.UnknownFieldSet;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.gamedesign.v1.ResolveAuthoredWorldSourceResponse;
import org.junit.jupiter.api.Test;

class AuthoredWorldSourceGrpcCodecTest {
  private static final String NAMESPACE = "test";
  private static final UUID REQUEST_ID = UUID.fromString("12345678-1234-4234-8234-123456789abc");
  private static final UUID OPERATION_ID = UUID.fromString("22345678-1234-4234-8234-123456789abc");
  private static final UUID TENANT_ID = UUID.fromString("32345678-1234-4234-8234-123456789abc");
  private static final UUID REGISTRATION_REQUEST_ID =
      UUID.fromString("42345678-1234-4234-8234-123456789abc");
  private static final AuthoredWorldSourceGrpcCodec.ReadRequest REQUEST =
      new AuthoredWorldSourceGrpcCodec.ReadRequest(
          NAMESPACE, REQUEST_ID, OPERATION_ID, TENANT_ID, "world-one");

  @Test
  void encodesTheExactReadTupleAndDecodesTheCompleteReceipt() {
    var encoded = AuthoredWorldSourceGrpcCodec.toReadRequest(REQUEST);
    assertThat(encoded.getRequestId()).isEqualTo(REQUEST_ID.toString());
    assertThat(encoded.getOperationId()).isEqualTo(OPERATION_ID.toString());
    assertThat(encoded.getCanonicalTenantId()).isEqualTo(TENANT_ID.toString());
    assertThat(encoded.getWorldSlug()).isEqualTo("world-one");
    assertThat(encoded.getUnknownFields().asMap()).isEmpty();

    AuthoredWorldSourceEvidence evidence =
        AuthoredWorldSourceGrpcCodec.fromReadResponse(REQUEST, response());
    assertThat(evidence.registrationRequestId()).isEqualTo(REGISTRATION_REQUEST_ID);
    assertThat(evidence.operationId()).isEqualTo(OPERATION_ID);
    assertThat(evidence.canonicalTenantId()).isEqualTo(TENANT_ID);
    assertThat(evidence.tenantSlug()).isEqualTo("tenant-one");
    assertThat(evidence.worldSlug()).isEqualTo("world-one");
    assertThat(evidence.worldDisplayName()).isEqualTo("Wörld");
    assertThat(evidence.sourceGameRowId()).isEqualTo(19L);
    assertThat(evidence.sourceGameTenantKey()).isEqualTo("source-key");
    assertThat(evidence.provenanceKind()).isEqualTo("NEW_GAME_ROW");
  }

  @Test
  void rejectsMalformedReadSelectorsBeforeEncoding() {
    for (Runnable invalid :
        List.<Runnable>of(
            () ->
                new AuthoredWorldSourceGrpcCodec.ReadRequest(
                    "Other", REQUEST_ID, OPERATION_ID, TENANT_ID, "world-one"),
            () ->
                new AuthoredWorldSourceGrpcCodec.ReadRequest(
                    NAMESPACE, new UUID(0L, 0L), OPERATION_ID, TENANT_ID, "world-one"),
            () ->
                new AuthoredWorldSourceGrpcCodec.ReadRequest(
                    NAMESPACE, REQUEST_ID, new UUID(0L, 0L), TENANT_ID, "world-one"),
            () ->
                new AuthoredWorldSourceGrpcCodec.ReadRequest(
                    NAMESPACE, REQUEST_ID, OPERATION_ID, new UUID(0L, 0L), "world-one"),
            () ->
                new AuthoredWorldSourceGrpcCodec.ReadRequest(
                    NAMESPACE, REQUEST_ID, OPERATION_ID, TENANT_ID, "World"),
            () ->
                new AuthoredWorldSourceGrpcCodec.ReadRequest(
                    NAMESPACE, REQUEST_ID, OPERATION_ID, TENANT_ID, "w".repeat(121)))) {
      assertThatThrownBy(invalid::run).isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void rejectsUnknownFieldsAndEveryChangedReceiptField() {
    ResolveAuthoredWorldSourceResponse valid = response();
    for (ResolveAuthoredWorldSourceResponse changed :
        List.of(
            valid.toBuilder().setSchemaVersion(2).build(),
            valid.toBuilder().setTargetNamespace("other").build(),
            valid.toBuilder().setRequestId(REGISTRATION_REQUEST_ID.toString()).build(),
            valid.toBuilder().setRegistrationRequestId(REQUEST_ID.toString()).build(),
            valid.toBuilder().setOperationId(REQUEST_ID.toString()).build(),
            valid.toBuilder().setRequestDigest("sha256:" + "0".repeat(64)).build(),
            valid.toBuilder().setCanonicalTenantId(REQUEST_ID.toString()).build(),
            valid.toBuilder().setTenantSlug("tenant-other").build(),
            valid.toBuilder().setWorldSlug("world-other").build(),
            valid.toBuilder().setWorldDisplayName("World").build(),
            valid.toBuilder().setSourceGameRowId(20L).build(),
            valid.toBuilder().setSourceGameTenantKey("changed-key").build(),
            valid.toBuilder().setProvenanceKind("RETAINED_GAME_V30").build(),
            valid.toBuilder().setEvidenceDigest("sha256:" + "0".repeat(64)).build(),
            valid.toBuilder()
                .setUnknownFields(
                    UnknownFieldSet.newBuilder()
                        .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
                        .build())
                .build())) {
      assertThatThrownBy(() -> AuthoredWorldSourceGrpcCodec.fromReadResponse(REQUEST, changed))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void rejectsCoherentRepliesForAnotherNamespaceOperationTenantOrWorld() {
    for (ResolveAuthoredWorldSourceResponse changed :
        List.of(
            response("other", TENANT_ID, OPERATION_ID, "world-one"),
            response(NAMESPACE, TENANT_ID, REQUEST_ID, "world-one"),
            response(NAMESPACE, REQUEST_ID, OPERATION_ID, "world-one"),
            response(NAMESPACE, TENANT_ID, OPERATION_ID, "other-world"))) {
      assertThatThrownBy(() -> AuthoredWorldSourceGrpcCodec.fromReadResponse(REQUEST, changed))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("exact request");
    }
  }

  private static ResolveAuthoredWorldSourceResponse response() {
    return response(NAMESPACE, TENANT_ID, OPERATION_ID, "world-one");
  }

  private static ResolveAuthoredWorldSourceResponse response(
      String namespace, UUID tenantId, UUID operationId, String worldSlug) {
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            namespace, REGISTRATION_REQUEST_ID, tenantId, "tenant-one", worldSlug, "Wörld");
    String evidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            namespace,
            REGISTRATION_REQUEST_ID,
            operationId,
            requestDigest,
            tenantId,
            "tenant-one",
            worldSlug,
            "Wörld",
            19L,
            "source-key",
            "NEW_GAME_ROW");
    return ResolveAuthoredWorldSourceResponse.newBuilder()
        .setSchemaVersion(1)
        .setTargetNamespace(namespace)
        .setRequestId(REQUEST_ID.toString())
        .setRegistrationRequestId(REGISTRATION_REQUEST_ID.toString())
        .setOperationId(operationId.toString())
        .setRequestDigest(requestDigest)
        .setCanonicalTenantId(tenantId.toString())
        .setTenantSlug("tenant-one")
        .setWorldSlug(worldSlug)
        .setWorldDisplayName("Wörld")
        .setSourceGameRowId(19L)
        .setSourceGameTenantKey("source-key")
        .setProvenanceKind("NEW_GAME_ROW")
        .setEvidenceDigest(evidenceDigest)
        .build();
  }
}
