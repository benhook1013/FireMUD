package net.firedevops.firemud.common.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.UnknownFieldSet;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec.CommittedReceipt;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec.IntakeRequest;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec.ReadRequest;
import net.firedevops.firemud.worldmanagement.v1.IntakeAuthoredWorldSourceRequest;
import net.firedevops.firemud.worldmanagement.v1.IntakeAuthoredWorldSourceResponse;
import net.firedevops.firemud.worldmanagement.v1.ReadAuthoredWorldSourceIntakeRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadAuthoredWorldSourceIntakeResponse;
import org.junit.jupiter.api.Test;

class WorldAuthoredSourceIntakeGrpcCodecTest {
  private static final String NAMESPACE = "firemud";
  private static final UUID INTAKE_REQUEST_ID =
      UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID READ_REQUEST_ID =
      UUID.fromString("66666666-6666-4666-8666-666666666666");
  private static final UUID TENANT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID SOURCE_OPERATION_ID =
      UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID OPERATION_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final String WORLD_SLUG = "violet-wilds";
  private static final String SOURCE_DIGEST = digest('a');
  private static final String REQUEST_DIGEST =
      "sha256:d8b4609586debeae9752cc22ddb58a686fa770ea5928a52aefab71494164e1ce";
  private static final String RECEIPT_DIGEST = digest('b');
  private static final IntakeRequest BINDING =
      new IntakeRequest(
          1,
          NAMESPACE,
          INTAKE_REQUEST_ID,
          TENANT_ID,
          WORLD_SLUG,
          SOURCE_OPERATION_ID,
          SOURCE_DIGEST);

  @Test
  void decodesAndEchoesTheFullIntakeBindingAndPublicReceipt() {
    var request = intakeRequest();
    assertThat(WorldAuthoredSourceIntakeGrpcCodec.fromIntakeRequest(request)).isEqualTo(BINDING);
    assertThat(WorldAuthoredSourceIntakeGrpcCodec.toIntakeRequest(BINDING)).isEqualTo(request);
    assertThat(
            WorldAuthoredSourceIntakeGrpcCodec.toIntakeRequest(BINDING).getUnknownFields().asMap())
        .isEmpty();

    CommittedReceipt receipt = committedReceipt(BINDING);
    assertThat(WorldAuthoredSourceIntakeGrpcCodec.requestDigest(BINDING)).isEqualTo(REQUEST_DIGEST);
    IntakeAuthoredWorldSourceResponse response =
        WorldAuthoredSourceIntakeGrpcCodec.toIntakeResponse(BINDING, receipt);
    assertThat(response.getSchemaVersion()).isEqualTo(1);
    assertThat(response.getTargetNamespace()).isEqualTo(NAMESPACE);
    assertThat(response.getIntakeRequestId()).isEqualTo(INTAKE_REQUEST_ID.toString());
    assertThat(response.getCanonicalTenantId()).isEqualTo(TENANT_ID.toString());
    assertThat(response.getWorldSlug()).isEqualTo(WORLD_SLUG);
    assertThat(response.getSourceOperationId()).isEqualTo(SOURCE_OPERATION_ID.toString());
    assertThat(response.getExpectedSourceEvidenceDigest()).isEqualTo(SOURCE_DIGEST);
    assertThat(response.getOperationId()).isEqualTo(OPERATION_ID.toString());
    assertThat(response.getRequestDigest()).isEqualTo(REQUEST_DIGEST);
    assertThat(response.getReceiptDigest()).isEqualTo(RECEIPT_DIGEST);
    assertThat(WorldAuthoredSourceIntakeGrpcCodec.fromIntakeResponse(BINDING, response))
        .isEqualTo(receipt);
    assertThat(response.getAllFields().keySet())
        .extracting(field -> field.getName())
        .doesNotContain("local_tenant_key", "localTenantKey");
  }

  @Test
  void exactReadUsesAndEchoesItsSeparateRequestIdentity() {
    ReadAuthoredWorldSourceIntakeRequest request = readRequest();
    ReadRequest decoded = WorldAuthoredSourceIntakeGrpcCodec.fromReadRequest(request);
    assertThat(decoded.binding()).isEqualTo(BINDING);
    assertThat(decoded.requestId()).isEqualTo(READ_REQUEST_ID);
    assertThat(WorldAuthoredSourceIntakeGrpcCodec.toReadRequest(decoded)).isEqualTo(request);
    assertThat(WorldAuthoredSourceIntakeGrpcCodec.toReadRequest(decoded).getUnknownFields().asMap())
        .isEmpty();

    CommittedReceipt receipt = committedReceipt(BINDING);
    ReadAuthoredWorldSourceIntakeResponse response =
        WorldAuthoredSourceIntakeGrpcCodec.toReadResponse(decoded, receipt);
    assertThat(response.getRequestId()).isEqualTo(READ_REQUEST_ID.toString());
    assertThat(response.getIntakeRequestId()).isEqualTo(INTAKE_REQUEST_ID.toString());
    assertThat(response.getCanonicalTenantId()).isEqualTo(TENANT_ID.toString());
    assertThat(response.getWorldSlug()).isEqualTo(WORLD_SLUG);
    assertThat(response.getSourceOperationId()).isEqualTo(SOURCE_OPERATION_ID.toString());
    assertThat(WorldAuthoredSourceIntakeGrpcCodec.fromReadResponse(decoded, response))
        .isEqualTo(receipt);
  }

  @Test
  void rejectsMalformedClosedIntakeRequests() {
    for (IntakeAuthoredWorldSourceRequest invalid :
        List.of(
            intakeRequest().toBuilder().setSchemaVersion(2).build(),
            intakeRequest().toBuilder().setTargetNamespace("FireMUD").build(),
            intakeRequest().toBuilder().setIntakeRequestId("not-a-uuid").build(),
            intakeRequest().toBuilder().setCanonicalTenantId(new UUID(0L, 0L).toString()).build(),
            intakeRequest().toBuilder().setWorldSlug("Violet-Wilds").build(),
            intakeRequest().toBuilder().setWorldSlug("w".repeat(121)).build(),
            intakeRequest().toBuilder()
                .setSourceOperationId("00000000-0000-0000-0000-000000000000")
                .build(),
            intakeRequest().toBuilder().setExpectedSourceEvidenceDigest("sha256:BAD").build(),
            intakeRequest().toBuilder()
                .setUnknownFields(
                    UnknownFieldSet.newBuilder()
                        .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
                        .build())
                .build())) {
      assertThatThrownBy(() -> WorldAuthoredSourceIntakeGrpcCodec.fromIntakeRequest(invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void rejectsReadRequestsThatReuseTheIntakeIdentityOrContainUnknownFields() {
    for (ReadAuthoredWorldSourceIntakeRequest invalid :
        List.of(
            readRequest().toBuilder().setRequestId(INTAKE_REQUEST_ID.toString()).build(),
            readRequest().toBuilder().setRequestId("00000000-0000-0000-0000-000000000000").build(),
            readRequest().toBuilder()
                .setUnknownFields(
                    UnknownFieldSet.newBuilder()
                        .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
                        .build())
                .build())) {
      assertThatThrownBy(() -> WorldAuthoredSourceIntakeGrpcCodec.fromReadRequest(invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void rejectsChangedEchoesMalformedDigestsAndUnknownResponseFields() {
    IntakeAuthoredWorldSourceResponse valid =
        WorldAuthoredSourceIntakeGrpcCodec.toIntakeResponse(BINDING, committedReceipt(BINDING));
    for (IntakeAuthoredWorldSourceResponse invalid :
        List.of(
            valid.toBuilder().setTargetNamespace("other").build(),
            valid.toBuilder().setIntakeRequestId(READ_REQUEST_ID.toString()).build(),
            valid.toBuilder().setCanonicalTenantId(READ_REQUEST_ID.toString()).build(),
            valid.toBuilder().setWorldSlug("other-world").build(),
            valid.toBuilder().setSourceOperationId(READ_REQUEST_ID.toString()).build(),
            valid.toBuilder().setExpectedSourceEvidenceDigest(digest('c')).build(),
            valid.toBuilder().setOperationId("not-a-uuid").build(),
            valid.toBuilder().setRequestDigest(digest('c')).build(),
            valid.toBuilder().setReceiptDigest("sha256:invalid").build(),
            valid.toBuilder()
                .setUnknownFields(
                    UnknownFieldSet.newBuilder()
                        .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
                        .build())
                .build())) {
      assertThatThrownBy(
              () -> WorldAuthoredSourceIntakeGrpcCodec.fromIntakeResponse(BINDING, invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }

    ReadRequest readRequest = new ReadRequest(BINDING, READ_REQUEST_ID);
    ReadAuthoredWorldSourceIntakeResponse readResponse =
        WorldAuthoredSourceIntakeGrpcCodec.toReadResponse(readRequest, committedReceipt(BINDING));
    assertThatThrownBy(
            () ->
                WorldAuthoredSourceIntakeGrpcCodec.fromReadResponse(
                    readRequest,
                    readResponse.toBuilder().setRequestId(INTAKE_REQUEST_ID.toString()).build()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void refusesToEncodeAReceiptForAnotherBinding() {
    CommittedReceipt changed =
        new CommittedReceipt(
            1,
            NAMESPACE,
            INTAKE_REQUEST_ID,
            OPERATION_ID,
            TENANT_ID,
            "other-world",
            SOURCE_OPERATION_ID,
            SOURCE_DIGEST,
            WorldAuthoredSourceIntakeGrpcCodec.requestDigest(
                new IntakeRequest(
                    1,
                    NAMESPACE,
                    INTAKE_REQUEST_ID,
                    TENANT_ID,
                    "other-world",
                    SOURCE_OPERATION_ID,
                    SOURCE_DIGEST)),
            RECEIPT_DIGEST);
    assertThatThrownBy(() -> WorldAuthoredSourceIntakeGrpcCodec.toIntakeResponse(BINDING, changed))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static IntakeAuthoredWorldSourceRequest intakeRequest() {
    return IntakeAuthoredWorldSourceRequest.newBuilder()
        .setSchemaVersion(1)
        .setTargetNamespace(NAMESPACE)
        .setIntakeRequestId(INTAKE_REQUEST_ID.toString())
        .setCanonicalTenantId(TENANT_ID.toString())
        .setWorldSlug(WORLD_SLUG)
        .setSourceOperationId(SOURCE_OPERATION_ID.toString())
        .setExpectedSourceEvidenceDigest(SOURCE_DIGEST)
        .build();
  }

  private static ReadAuthoredWorldSourceIntakeRequest readRequest() {
    return ReadAuthoredWorldSourceIntakeRequest.newBuilder()
        .setSchemaVersion(1)
        .setTargetNamespace(NAMESPACE)
        .setRequestId(READ_REQUEST_ID.toString())
        .setIntakeRequestId(INTAKE_REQUEST_ID.toString())
        .setCanonicalTenantId(TENANT_ID.toString())
        .setWorldSlug(WORLD_SLUG)
        .setSourceOperationId(SOURCE_OPERATION_ID.toString())
        .setExpectedSourceEvidenceDigest(SOURCE_DIGEST)
        .build();
  }

  private static CommittedReceipt committedReceipt(IntakeRequest binding) {
    return new CommittedReceipt(
        1,
        binding.targetNamespace(),
        binding.intakeRequestId(),
        OPERATION_ID,
        binding.canonicalTenantId(),
        binding.worldSlug(),
        binding.sourceOperationId(),
        binding.expectedSourceEvidenceDigest(),
        WorldAuthoredSourceIntakeGrpcCodec.requestDigest(binding),
        RECEIPT_DIGEST);
  }

  private static String digest(char value) {
    return "sha256:" + String.valueOf(value).repeat(64);
  }
}
