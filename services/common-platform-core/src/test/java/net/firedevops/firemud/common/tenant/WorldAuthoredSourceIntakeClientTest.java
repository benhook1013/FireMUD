package net.firedevops.firemud.common.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.UnknownFieldSet;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.worldmanagement.v1.IntakeAuthoredWorldSourceRequest;
import net.firedevops.firemud.worldmanagement.v1.IntakeAuthoredWorldSourceResponse;
import net.firedevops.firemud.worldmanagement.v1.ReadAuthoredWorldSourceIntakeByIdRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadAuthoredWorldSourceIntakeByIdResponse;
import net.firedevops.firemud.worldmanagement.v1.ReadAuthoredWorldSourceIntakeRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadAuthoredWorldSourceIntakeResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldAuthoredSourceIntakeServiceGrpc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldAuthoredSourceIntakeClientTest {
  private static final String NAMESPACE = "test";
  private static final String SOURCE_DIGEST = "sha256:" + "a".repeat(64);
  private static final String RECEIPT_DIGEST = "sha256:" + "b".repeat(64);
  private static final WorldAuthoredSourceIntakeGrpcCodec.IntakeRequest INTAKE_REQUEST =
      new WorldAuthoredSourceIntakeGrpcCodec.IntakeRequest(
          1,
          NAMESPACE,
          UUID.fromString("12345678-1234-4234-8234-123456789abc"),
          UUID.fromString("22345678-1234-4234-8234-123456789abc"),
          "world-one",
          UUID.fromString("32345678-1234-4234-8234-123456789abc"),
          SOURCE_DIGEST);
  private static final WorldAuthoredSourceIntakeGrpcCodec.ReadRequest READ_REQUEST =
      new WorldAuthoredSourceIntakeGrpcCodec.ReadRequest(
          INTAKE_REQUEST, UUID.fromString("42345678-1234-4234-8234-123456789abc"));
  private static final WorldAuthoredSourceIntakeGrpcCodec.ByIdReadRequest BY_ID_READ_REQUEST =
      new WorldAuthoredSourceIntakeGrpcCodec.ByIdReadRequest(
          1,
          NAMESPACE,
          UUID.fromString("52345678-1234-4234-8234-123456789abc"),
          INTAKE_REQUEST.intakeRequestId(),
          INTAKE_REQUEST.canonicalTenantId());

  @Test
  void rejectsPlaintextClasspathAndUnreadableTlsMaterialBeforeChannelCreation(@TempDir Path dir)
      throws Exception {
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);

    CommonGrpcClientProperties plaintext = fileBackedMtls(dir);
    plaintext.setPlaintext(true);
    assertThatThrownBy(() -> newClient(plaintext, channelFactory))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mTLS");

    assertThatThrownBy(() -> newClient(new CommonGrpcClientProperties(), channelFactory))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("file-backed");

    CommonGrpcClientProperties classpath = fileBackedMtls(dir);
    classpath.setCaCert("classpath:certs/ca.crt");
    assertThatThrownBy(() -> newClient(classpath, channelFactory))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("file-backed");

    CommonGrpcClientProperties missingFiles = new CommonGrpcClientProperties();
    missingFiles.setCertChain(dir.resolve("missing-client.crt").toString());
    missingFiles.setPrivateKey(dir.resolve("missing-client.key").toString());
    missingFiles.setCaCert(dir.resolve("missing-ca.crt").toString());
    assertThatThrownBy(() -> newClient(missingFiles, channelFactory))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("existing readable file");

    verifyNoInteractions(channelFactory);
  }

  @Test
  void requiresExplicitLifecycleAndExactConfiguredNamespace(@TempDir Path dir) throws Exception {
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    WorldAuthoredSourceIntakeClient client = newClient(fileBackedMtls(dir), channelFactory);
    try {
      assertThatThrownBy(() -> client.intake(INTAKE_REQUEST))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("not initialized and available");
      assertThatThrownBy(
              () ->
                  client.intake(
                      new WorldAuthoredSourceIntakeGrpcCodec.IntakeRequest(
                          1,
                          "other",
                          INTAKE_REQUEST.intakeRequestId(),
                          INTAKE_REQUEST.canonicalTenantId(),
                          INTAKE_REQUEST.worldSlug(),
                          INTAKE_REQUEST.sourceOperationId(),
                          INTAKE_REQUEST.expectedSourceEvidenceDigest())))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("configured workload namespace");
      assertThatThrownBy(() -> client.read(READ_REQUEST))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("not initialized and available");
      assertThatThrownBy(() -> client.readById(BY_ID_READ_REQUEST))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("not initialized and available");
      assertThatThrownBy(
              () ->
                  client.readById(
                      new WorldAuthoredSourceIntakeGrpcCodec.ByIdReadRequest(
                          1,
                          "other",
                          BY_ID_READ_REQUEST.readRequestId(),
                          BY_ID_READ_REQUEST.intakeRequestId(),
                          BY_ID_READ_REQUEST.canonicalTenantId())))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("configured workload namespace");
      verifyNoInteractions(channelFactory);
    } finally {
      client.close();
    }
  }

  @Test
  void initializesConfiguredWorldTargetOnceAndCannotRestartAfterClose(@TempDir Path dir)
      throws Exception {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    endpoints.setWorldManagementService("world.internal:7676");
    CommonGrpcClientProperties tls = fileBackedMtls(dir);
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    ManagedChannel channel = mock(ManagedChannel.class);
    when(channelFactory.buildChannel(
            eq("world.internal:7676"), eq(6565), any(CommonGrpcClientProperties.class), eq(true)))
        .thenReturn(channel);
    WorldAuthoredSourceIntakeClient client =
        new WorldAuthoredSourceIntakeClient(endpoints, tls, channelFactory, NAMESPACE);

    client.init();
    client.init();
    client.close();

    verify(channelFactory)
        .buildChannel(
            eq("world.internal:7676"), eq(6565), any(CommonGrpcClientProperties.class), eq(true));
    assertThatThrownBy(() -> client.intake(INTAKE_REQUEST))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not initialized and available");
    assertThatThrownBy(client::init)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("is closed");
  }

  @Test
  void sendsOneIntakeAndReturnsOnlyAValidatedExactWorldReceipt(@TempDir Path dir) throws Exception {
    WorldAuthoredSourceIntakeServiceGrpc.WorldAuthoredSourceIntakeServiceBlockingStub stub =
        mock(
            WorldAuthoredSourceIntakeServiceGrpc.WorldAuthoredSourceIntakeServiceBlockingStub
                .class);
    WorldAuthoredSourceIntakeClient client = clientWithStub(dir, stub);
    IntakeAuthoredWorldSourceResponse response = intakeResponse(INTAKE_REQUEST);
    when(stub.intakeAuthoredWorldSource(any(IntakeAuthoredWorldSourceRequest.class)))
        .thenReturn(response);
    when(stub.withDeadlineAfter(anyLong(), eq(TimeUnit.SECONDS))).thenReturn(stub);

    WorldAuthoredSourceIntakeGrpcCodec.CommittedReceipt receipt = client.intake(INTAKE_REQUEST);

    assertThat(receipt.intakeRequestId()).isEqualTo(INTAKE_REQUEST.intakeRequestId());
    assertThat(receipt.operationId())
        .isEqualTo(UUID.fromString("52345678-1234-4234-8234-123456789abc"));
    assertThat(receipt.receiptDigest()).isEqualTo(RECEIPT_DIGEST);
    verify(stub).withDeadlineAfter(5L, TimeUnit.SECONDS);
    verify(stub)
        .intakeAuthoredWorldSource(
            WorldAuthoredSourceIntakeGrpcCodec.toIntakeRequest(INTAKE_REQUEST));
    assertThat(
            WorldAuthoredSourceIntakeGrpcCodec.toIntakeRequest(INTAKE_REQUEST)
                .getDescriptorForType()
                .getFields())
        .extracting(field -> field.getName())
        .doesNotContain("world_tenant_key", "local_tenant_key", "tenant_id");
  }

  @Test
  void rejectsChangedEchoesAndUnknownResponseFields(@TempDir Path dir) throws Exception {
    WorldAuthoredSourceIntakeServiceGrpc.WorldAuthoredSourceIntakeServiceBlockingStub stub =
        mock(
            WorldAuthoredSourceIntakeServiceGrpc.WorldAuthoredSourceIntakeServiceBlockingStub
                .class);
    WorldAuthoredSourceIntakeClient client = clientWithStub(dir, stub);
    when(stub.withDeadlineAfter(anyLong(), eq(TimeUnit.SECONDS))).thenReturn(stub);

    when(stub.intakeAuthoredWorldSource(any(IntakeAuthoredWorldSourceRequest.class)))
        .thenReturn(intakeResponse(INTAKE_REQUEST).toBuilder().setWorldSlug("other-world").build());
    assertThatThrownBy(() -> client.intake(INTAKE_REQUEST))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("invalid authored-source intake receipt");

    when(stub.intakeAuthoredWorldSource(any(IntakeAuthoredWorldSourceRequest.class)))
        .thenReturn(
            intakeResponse(INTAKE_REQUEST).toBuilder().setUnknownFields(unknownField()).build());
    assertThatThrownBy(() -> client.intake(INTAKE_REQUEST))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("invalid authored-source intake receipt");

    ReadAuthoredWorldSourceIntakeResponse alteredRead =
        readResponse(READ_REQUEST).toBuilder()
            .setRequestId(INTAKE_REQUEST.intakeRequestId().toString())
            .build();
    when(stub.readAuthoredWorldSourceIntake(any(ReadAuthoredWorldSourceIntakeRequest.class)))
        .thenReturn(alteredRead);
    assertThatThrownBy(() -> client.read(READ_REQUEST))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("invalid authored-source intake readback");

    when(stub.intakeAuthoredWorldSource(any(IntakeAuthoredWorldSourceRequest.class)))
        .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));
    assertThatThrownBy(() -> client.intake(INTAKE_REQUEST))
        .isInstanceOf(StatusRuntimeException.class);
    verify(stub, org.mockito.Mockito.times(3))
        .intakeAuthoredWorldSource(any(IntakeAuthoredWorldSourceRequest.class));
  }

  @Test
  void readRequiresAndReturnsTheSeparateExactReadIdentity(@TempDir Path dir) throws Exception {
    WorldAuthoredSourceIntakeServiceGrpc.WorldAuthoredSourceIntakeServiceBlockingStub stub =
        mock(
            WorldAuthoredSourceIntakeServiceGrpc.WorldAuthoredSourceIntakeServiceBlockingStub
                .class);
    WorldAuthoredSourceIntakeClient client = clientWithStub(dir, stub);
    when(stub.withDeadlineAfter(anyLong(), eq(TimeUnit.SECONDS))).thenReturn(stub);
    when(stub.readAuthoredWorldSourceIntake(any(ReadAuthoredWorldSourceIntakeRequest.class)))
        .thenReturn(readResponse(READ_REQUEST));

    WorldAuthoredSourceIntakeGrpcCodec.CommittedReceipt receipt = client.read(READ_REQUEST);

    assertThat(receipt.intakeRequestId()).isEqualTo(INTAKE_REQUEST.intakeRequestId());
    assertThat(receipt.sourceOperationId()).isEqualTo(INTAKE_REQUEST.sourceOperationId());
    verify(stub).withDeadlineAfter(5L, TimeUnit.SECONDS);
    verify(stub)
        .readAuthoredWorldSourceIntake(
            WorldAuthoredSourceIntakeGrpcCodec.toReadRequest(READ_REQUEST));
  }

  @Test
  void byIdReadReturnsTheFullValidatedPublicReceiptWithBoundedDeadline(@TempDir Path dir)
      throws Exception {
    WorldAuthoredSourceIntakeServiceGrpc.WorldAuthoredSourceIntakeServiceBlockingStub stub =
        mock(
            WorldAuthoredSourceIntakeServiceGrpc.WorldAuthoredSourceIntakeServiceBlockingStub
                .class);
    WorldAuthoredSourceIntakeClient client = clientWithStub(dir, stub);
    when(stub.withDeadlineAfter(anyLong(), eq(TimeUnit.SECONDS))).thenReturn(stub);
    when(stub.readAuthoredWorldSourceIntakeById(
            any(ReadAuthoredWorldSourceIntakeByIdRequest.class)))
        .thenReturn(byIdReadResponse(BY_ID_READ_REQUEST));

    var receipt = client.readById(BY_ID_READ_REQUEST);

    assertThat(receipt.intakeRequestId()).isEqualTo(BY_ID_READ_REQUEST.intakeRequestId());
    assertThat(receipt.canonicalTenantId()).isEqualTo(BY_ID_READ_REQUEST.canonicalTenantId());
    assertThat(receipt.worldSlug()).isEqualTo(INTAKE_REQUEST.worldSlug());
    assertThat(receipt.source()).isEqualTo(sourceEvidence());
    assertThat(receipt.receiptDigest()).isEqualTo(RECEIPT_DIGEST);
    assertThat(byIdReadResponse(BY_ID_READ_REQUEST).getReceipt().getAllFields().keySet())
        .extracting(field -> field.getName())
        .doesNotContain("local_tenant_key", "local_version_key", "world_tenant_key");
    assertThat(
            byIdReadResponse(BY_ID_READ_REQUEST).getReceipt().getSource().getAllFields().keySet())
        .extracting(field -> field.getName())
        .containsExactlyInAnyOrder(
            "schema_version",
            "target_namespace",
            "registration_request_id",
            "operation_id",
            "request_digest",
            "canonical_tenant_id",
            "tenant_slug",
            "world_slug",
            "world_display_name",
            "source_game_row_id",
            "source_game_tenant_key",
            "provenance_kind",
            "evidence_digest")
        .doesNotContain("world_tenant_key", "local_tenant_key", "local_version_key");
    verify(stub).withDeadlineAfter(5L, TimeUnit.SECONDS);
    verify(stub)
        .readAuthoredWorldSourceIntakeById(
            WorldAuthoredSourceIntakeGrpcCodec.toReadByIdRequest(BY_ID_READ_REQUEST));
    assertThat(
            WorldAuthoredSourceIntakeGrpcCodec.toReadByIdRequest(BY_ID_READ_REQUEST)
                .getDescriptorForType()
                .getFields())
        .extracting(field -> field.getName())
        .containsExactly(
            "schema_version",
            "target_namespace",
            "read_request_id",
            "intake_request_id",
            "canonical_tenant_id")
        .doesNotContain("world_slug", "source_operation_id", "expected_source_evidence_digest");
  }

  @Test
  void byIdReadRejectsChangedEchoUnknownFieldsAndCorruptFullSource(@TempDir Path dir)
      throws Exception {
    WorldAuthoredSourceIntakeServiceGrpc.WorldAuthoredSourceIntakeServiceBlockingStub stub =
        mock(
            WorldAuthoredSourceIntakeServiceGrpc.WorldAuthoredSourceIntakeServiceBlockingStub
                .class);
    WorldAuthoredSourceIntakeClient client = clientWithStub(dir, stub);
    when(stub.withDeadlineAfter(anyLong(), eq(TimeUnit.SECONDS))).thenReturn(stub);

    when(stub.readAuthoredWorldSourceIntakeById(
            any(ReadAuthoredWorldSourceIntakeByIdRequest.class)))
        .thenReturn(
            byIdReadResponse(BY_ID_READ_REQUEST).toBuilder()
                .setReadRequestId(BY_ID_READ_REQUEST.intakeRequestId().toString())
                .build());
    assertThatThrownBy(() -> client.readById(BY_ID_READ_REQUEST))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("invalid authored-source intake by-ID readback");

    when(stub.readAuthoredWorldSourceIntakeById(
            any(ReadAuthoredWorldSourceIntakeByIdRequest.class)))
        .thenReturn(
            byIdReadResponse(BY_ID_READ_REQUEST).toBuilder()
                .setUnknownFields(unknownField())
                .build());
    assertThatThrownBy(() -> client.readById(BY_ID_READ_REQUEST))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("invalid authored-source intake by-ID readback");

    var valid = byIdReadResponse(BY_ID_READ_REQUEST);
    var corruptReceipt =
        valid.getReceipt().toBuilder()
            .setSource(
                valid.getReceipt().getSource().toBuilder().setWorldDisplayName("substituted"))
            .build();
    when(stub.readAuthoredWorldSourceIntakeById(
            any(ReadAuthoredWorldSourceIntakeByIdRequest.class)))
        .thenReturn(valid.toBuilder().setReceipt(corruptReceipt).build());
    assertThatThrownBy(() -> client.readById(BY_ID_READ_REQUEST))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("invalid authored-source intake by-ID readback");

    var unknownSource =
        valid.getReceipt().getSource().toBuilder().setUnknownFields(unknownField()).build();
    when(stub.readAuthoredWorldSourceIntakeById(
            any(ReadAuthoredWorldSourceIntakeByIdRequest.class)))
        .thenReturn(
            valid.toBuilder()
                .setReceipt(valid.getReceipt().toBuilder().setSource(unknownSource))
                .build());
    assertThatThrownBy(() -> client.readById(BY_ID_READ_REQUEST))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("invalid authored-source intake by-ID readback");
  }

  private static WorldAuthoredSourceIntakeClient clientWithStub(
      Path directory,
      WorldAuthoredSourceIntakeServiceGrpc.WorldAuthoredSourceIntakeServiceBlockingStub stub)
      throws Exception {
    WorldAuthoredSourceIntakeClient client =
        newClient(fileBackedMtls(directory), mock(GrpcChannelFactory.class));
    setField(AbstractReloadingBlockingGrpcClient.class, client, "stub", stub);
    setField(WorldAuthoredSourceIntakeClient.class, client, "initialized", true);
    return client;
  }

  private static IntakeAuthoredWorldSourceResponse intakeResponse(
      WorldAuthoredSourceIntakeGrpcCodec.IntakeRequest request) {
    return WorldAuthoredSourceIntakeGrpcCodec.toIntakeResponse(request, receipt(request));
  }

  private static ReadAuthoredWorldSourceIntakeResponse readResponse(
      WorldAuthoredSourceIntakeGrpcCodec.ReadRequest request) {
    return WorldAuthoredSourceIntakeGrpcCodec.toReadResponse(request, receipt(request.binding()));
  }

  private static ReadAuthoredWorldSourceIntakeByIdResponse byIdReadResponse(
      WorldAuthoredSourceIntakeGrpcCodec.ByIdReadRequest request) {
    return WorldAuthoredSourceIntakeGrpcCodec.toReadByIdResponse(request, publicReceipt());
  }

  private static WorldAuthoredSourceIntakeGrpcCodec.PublicReceipt publicReceipt() {
    var source = sourceEvidence();
    var binding =
        new WorldAuthoredSourceIntakeGrpcCodec.IntakeRequest(
            1,
            NAMESPACE,
            INTAKE_REQUEST.intakeRequestId(),
            INTAKE_REQUEST.canonicalTenantId(),
            INTAKE_REQUEST.worldSlug(),
            INTAKE_REQUEST.sourceOperationId(),
            source.evidenceDigest());
    return new WorldAuthoredSourceIntakeGrpcCodec.PublicReceipt(
        1,
        NAMESPACE,
        INTAKE_REQUEST.intakeRequestId(),
        UUID.fromString("62345678-1234-4234-8234-123456789abc"),
        INTAKE_REQUEST.canonicalTenantId(),
        INTAKE_REQUEST.worldSlug(),
        INTAKE_REQUEST.sourceOperationId(),
        source.evidenceDigest(),
        WorldAuthoredSourceIntakeGrpcCodec.requestDigest(binding),
        RECEIPT_DIGEST,
        source);
  }

  private static AuthoredWorldSourceEvidence sourceEvidence() {
    UUID registrationRequestId = UUID.fromString("72345678-1234-4234-8234-123456789abc");
    String tenantSlug = "north-star";
    String worldDisplayName = "World One";
    String sourceRequestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE,
            registrationRequestId,
            INTAKE_REQUEST.canonicalTenantId(),
            tenantSlug,
            INTAKE_REQUEST.worldSlug(),
            worldDisplayName);
    String evidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            NAMESPACE,
            registrationRequestId,
            INTAKE_REQUEST.sourceOperationId(),
            sourceRequestDigest,
            INTAKE_REQUEST.canonicalTenantId(),
            tenantSlug,
            INTAKE_REQUEST.worldSlug(),
            worldDisplayName,
            42L,
            "legacy-game-tenant-42",
            "NEW_GAME_ROW");
    return new AuthoredWorldSourceEvidence(
        1,
        NAMESPACE,
        registrationRequestId,
        INTAKE_REQUEST.sourceOperationId(),
        sourceRequestDigest,
        INTAKE_REQUEST.canonicalTenantId(),
        tenantSlug,
        INTAKE_REQUEST.worldSlug(),
        worldDisplayName,
        42L,
        "legacy-game-tenant-42",
        "NEW_GAME_ROW",
        evidenceDigest);
  }

  private static WorldAuthoredSourceIntakeGrpcCodec.CommittedReceipt receipt(
      WorldAuthoredSourceIntakeGrpcCodec.IntakeRequest request) {
    return new WorldAuthoredSourceIntakeGrpcCodec.CommittedReceipt(
        request.schemaVersion(),
        request.targetNamespace(),
        request.intakeRequestId(),
        UUID.fromString("52345678-1234-4234-8234-123456789abc"),
        request.canonicalTenantId(),
        request.worldSlug(),
        request.sourceOperationId(),
        request.expectedSourceEvidenceDigest(),
        WorldAuthoredSourceIntakeGrpcCodec.requestDigest(request),
        RECEIPT_DIGEST);
  }

  private static UnknownFieldSet unknownField() {
    return UnknownFieldSet.newBuilder()
        .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
        .build();
  }

  private static void setField(Class<?> declaringClass, Object target, String name, Object value)
      throws Exception {
    Field field = declaringClass.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static WorldAuthoredSourceIntakeClient newClient(
      CommonGrpcClientProperties tls, GrpcChannelFactory channelFactory) {
    return new WorldAuthoredSourceIntakeClient(
        new ServiceEndpointsProperties(), tls, channelFactory, NAMESPACE);
  }

  private static CommonGrpcClientProperties fileBackedMtls(Path directory) throws Exception {
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain(Files.writeString(directory.resolve("client.crt"), "certificate").toString());
    tls.setPrivateKey(Files.writeString(directory.resolve("client.key"), "private key").toString());
    tls.setCaCert(
        Files.writeString(directory.resolve("server-ca.crt"), "CA certificate").toString());
    return tls;
  }
}
