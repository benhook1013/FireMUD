package net.firedevops.firemud.common.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.UnknownFieldSet;
import java.util.UUID;
import net.firedevops.firemud.gamedesign.v1.ResolveAuthoredWorldSourceRequest;
import org.junit.jupiter.api.Test;

class AuthoredWorldSourceGrpcCodecTest {
  private static final String NAMESPACE = "test";
  private static final UUID READ_REQUEST_ID =
      UUID.fromString("12345678-1234-4234-8234-123456789abc");
  private static final UUID OPERATION_ID = UUID.fromString("22345678-1234-4234-8234-123456789abc");
  private static final UUID TENANT_ID = UUID.fromString("32345678-1234-4234-8234-123456789abc");
  private static final UUID REGISTRATION_REQUEST_ID =
      UUID.fromString("42345678-1234-4234-8234-123456789abc");
  private static final String TENANT_SLUG = "new-kingdom";
  private static final String WORLD_SLUG = "silver-march";
  private static final String WORLD_DISPLAY_NAME = "Silver March";
  private static final String SOURCE_KEY = "legacy-tenant-7";
  private static final AuthoredWorldSourceGrpcCodec.ReadRequest READ_REQUEST =
      readRequest(NAMESPACE, TENANT_ID, WORLD_SLUG);

  @Test
  void requestRoundTripCarriesOnlyReadCorrelationAndExactSourceSelector() {
    ResolveAuthoredWorldSourceRequest encoded =
        AuthoredWorldSourceGrpcCodec.toReadRequest(READ_REQUEST);

    assertThat(AuthoredWorldSourceGrpcCodec.fromReadRequest(NAMESPACE, encoded))
        .isEqualTo(READ_REQUEST);
    assertThat(encoded.getDescriptorForType().getFields())
        .extracting(field -> field.getName())
        .containsExactly("request_id", "operation_id", "canonical_tenant_id", "world_slug");
    assertThat(encoded.getRequestId()).isEqualTo(READ_REQUEST_ID.toString());
    assertThat(encoded.getOperationId()).isEqualTo(OPERATION_ID.toString());
    assertThat(encoded.getCanonicalTenantId()).isEqualTo(TENANT_ID.toString());
    assertThat(encoded.getWorldSlug()).isEqualTo(WORLD_SLUG);
  }

  @Test
  void requestDecoderRejectsMalformedSelectorsAndUnknownFields() {
    ResolveAuthoredWorldSourceRequest valid =
        AuthoredWorldSourceGrpcCodec.toReadRequest(READ_REQUEST);
    for (ResolveAuthoredWorldSourceRequest malformed :
        new ResolveAuthoredWorldSourceRequest[] {
          valid.toBuilder().setRequestId("not-a-uuid").build(),
          valid.toBuilder().setOperationId("00000000-0000-0000-0000-000000000000").build(),
          valid.toBuilder().setCanonicalTenantId("not-a-uuid").build(),
          valid.toBuilder().setWorldSlug("Silver March").build(),
          valid.toBuilder().setUnknownFields(unknownField()).build()
        }) {
      assertThatThrownBy(() -> AuthoredWorldSourceGrpcCodec.fromReadRequest(NAMESPACE, malformed))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> AuthoredWorldSourceGrpcCodec.fromReadRequest("Bad_Namespace", valid))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void responseRoundTripVerifiesCompleteImmutableReceiptAndSelector() {
    AuthoredWorldSourceEvidence source = source(READ_REQUEST);
    var response = AuthoredWorldSourceGrpcCodec.toReadResponse(READ_REQUEST, source);

    assertThat(AuthoredWorldSourceGrpcCodec.fromReadResponse(READ_REQUEST, response))
        .isEqualTo(source);
    assertThat(response.getRequestId()).isEqualTo(READ_REQUEST_ID.toString());
    assertThat(response.getRegistrationRequestId()).isEqualTo(REGISTRATION_REQUEST_ID.toString());
    assertThat(response.getOperationId()).isEqualTo(OPERATION_ID.toString());
    assertThat(response.getEvidenceDigest()).isEqualTo(source.evidenceDigest());
  }

  @Test
  void responseDecoderRejectsChangedEchoReceiptDigestsAndUnknownFields() {
    var response = AuthoredWorldSourceGrpcCodec.toReadResponse(READ_REQUEST, source(READ_REQUEST));
    for (var malformed :
        new net.firedevops.firemud.gamedesign.v1.ResolveAuthoredWorldSourceResponse[] {
          response.toBuilder().setRequestId("52345678-1234-4234-8234-123456789abc").build(),
          response.toBuilder().setOperationId("52345678-1234-4234-8234-123456789abc").build(),
          response.toBuilder().setCanonicalTenantId("52345678-1234-4234-8234-123456789abc").build(),
          response.toBuilder().setRequestDigest("0".repeat(64)).build(),
          response.toBuilder().setEvidenceDigest("0".repeat(64)).build(),
          response.toBuilder().setSourceGameRowId(0L).build(),
          response.toBuilder().setProvenanceKind("GUESSED").build(),
          response.toBuilder().setUnknownFields(unknownField()).build()
        }) {
      assertThatThrownBy(
              () -> AuthoredWorldSourceGrpcCodec.fromReadResponse(READ_REQUEST, malformed))
          .isInstanceOf(IllegalArgumentException.class);
    }

    AuthoredWorldSourceEvidence substituted = source(readRequest(NAMESPACE, TENANT_ID, "other"));
    assertThatThrownBy(() -> AuthoredWorldSourceGrpcCodec.toReadResponse(READ_REQUEST, substituted))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact request");
  }

  private static AuthoredWorldSourceGrpcCodec.ReadRequest readRequest(
      String namespace, UUID canonicalTenantId, String worldSlug) {
    return new AuthoredWorldSourceGrpcCodec.ReadRequest(
        namespace, READ_REQUEST_ID, OPERATION_ID, canonicalTenantId, worldSlug);
  }

  private static AuthoredWorldSourceEvidence source(
      AuthoredWorldSourceGrpcCodec.ReadRequest request) {
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE,
            REGISTRATION_REQUEST_ID,
            request.canonicalTenantId(),
            TENANT_SLUG,
            request.worldSlug(),
            WORLD_DISPLAY_NAME);
    String evidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            NAMESPACE,
            REGISTRATION_REQUEST_ID,
            OPERATION_ID,
            requestDigest,
            request.canonicalTenantId(),
            TENANT_SLUG,
            request.worldSlug(),
            WORLD_DISPLAY_NAME,
            17L,
            SOURCE_KEY,
            "NEW_GAME_ROW");
    return new AuthoredWorldSourceEvidence(
        1,
        NAMESPACE,
        REGISTRATION_REQUEST_ID,
        OPERATION_ID,
        requestDigest,
        request.canonicalTenantId(),
        TENANT_SLUG,
        request.worldSlug(),
        WORLD_DISPLAY_NAME,
        17L,
        SOURCE_KEY,
        "NEW_GAME_ROW",
        evidenceDigest);
  }

  private static UnknownFieldSet unknownField() {
    return UnknownFieldSet.newBuilder()
        .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
        .build();
  }
}
