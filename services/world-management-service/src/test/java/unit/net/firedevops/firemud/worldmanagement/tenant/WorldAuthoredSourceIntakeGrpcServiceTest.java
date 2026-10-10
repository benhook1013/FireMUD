package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.UnknownFieldSet;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec.CommittedReceipt;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec.IntakeRequest;
import net.firedevops.firemud.worldmanagement.v1.IntakeAuthoredWorldSourceRequest;
import net.firedevops.firemud.worldmanagement.v1.IntakeAuthoredWorldSourceResponse;
import net.firedevops.firemud.worldmanagement.v1.ReadAuthoredWorldSourceIntakeByIdRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadAuthoredWorldSourceIntakeByIdResponse;
import net.firedevops.firemud.worldmanagement.v1.ReadAuthoredWorldSourceIntakeRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadAuthoredWorldSourceIntakeResponse;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class WorldAuthoredSourceIntakeGrpcServiceTest {
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
  private static final String SOURCE_DIGEST =
      source(NAMESPACE, TENANT_ID, SOURCE_OPERATION_ID, WORLD_SLUG).evidenceDigest();

  private final WorldAuthoredSourceIntakeService intakeService =
      mock(WorldAuthoredSourceIntakeService.class);
  private final WorldAuthoredSourceIntakeGrpcService grpcService =
      new WorldAuthoredSourceIntakeGrpcService(intakeService, NAMESPACE);

  @Test
  void intakeReturnsOnlyTheExactCommittedReceiptBinding() {
    IntakeRequest binding = binding();
    WorldAuthoredSourceIntakeReceipt receipt = receipt(binding, 9001L);
    when(intakeService.intake(
            1,
            NAMESPACE,
            INTAKE_REQUEST_ID,
            TENANT_ID,
            WORLD_SLUG,
            SOURCE_OPERATION_ID,
            SOURCE_DIGEST))
        .thenReturn(receipt);
    RecordingObserver<IntakeAuthoredWorldSourceResponse> observer = new RecordingObserver<>();

    withGameDesign(() -> grpcService.intakeAuthoredWorldSource(intakeRequest(), observer));

    assertThat(observer.error).isNull();
    assertThat(observer.completed).isTrue();
    assertThat(observer.values).hasSize(1);
    IntakeAuthoredWorldSourceResponse response = observer.values.get(0);
    CommittedReceipt decoded =
        WorldAuthoredSourceIntakeGrpcCodec.fromIntakeResponse(binding, response);
    assertThat(decoded.operationId()).isEqualTo(OPERATION_ID);
    assertThat(decoded.requestDigest())
        .isEqualTo(WorldAuthoredSourceIntakeGrpcCodec.requestDigest(binding));
    assertThat(response.getReceiptDigest()).isEqualTo(receipt.receiptDigest());
    assertThat(response.getAllFields().keySet())
        .extracting(field -> field.getName())
        .doesNotContain("local_tenant_key", "localTenantKey");
    verify(intakeService)
        .intake(
            1,
            NAMESPACE,
            INTAKE_REQUEST_ID,
            TENANT_ID,
            WORLD_SLUG,
            SOURCE_OPERATION_ID,
            SOURCE_DIGEST);
  }

  @Test
  void readReturnsCommittedReceiptAndEchoesSeparateRequestIdentity() {
    IntakeRequest binding = binding();
    WorldAuthoredSourceIntakeReceipt receipt = receipt(binding, 9001L);
    when(intakeService.readCommittedReceipt(
            1,
            NAMESPACE,
            READ_REQUEST_ID,
            INTAKE_REQUEST_ID,
            TENANT_ID,
            WORLD_SLUG,
            SOURCE_OPERATION_ID,
            SOURCE_DIGEST))
        .thenReturn(Optional.of(receipt));
    RecordingObserver<ReadAuthoredWorldSourceIntakeResponse> observer = new RecordingObserver<>();

    withGameDesign(() -> grpcService.readAuthoredWorldSourceIntake(readRequest(), observer));

    assertThat(observer.error).isNull();
    assertThat(observer.completed).isTrue();
    assertThat(observer.values).hasSize(1);
    ReadAuthoredWorldSourceIntakeResponse response = observer.values.get(0);
    assertThat(response.getRequestId()).isEqualTo(READ_REQUEST_ID.toString());
    assertThat(response.getIntakeRequestId()).isEqualTo(INTAKE_REQUEST_ID.toString());
    CommittedReceipt decoded =
        WorldAuthoredSourceIntakeGrpcCodec.fromReadResponse(
            new WorldAuthoredSourceIntakeGrpcCodec.ReadRequest(binding, READ_REQUEST_ID), response);
    assertThat(decoded.operationId()).isEqualTo(OPERATION_ID);
    assertThat(decoded.requestDigest())
        .isEqualTo(WorldAuthoredSourceIntakeGrpcCodec.requestDigest(binding));
    verify(intakeService)
        .readCommittedReceipt(
            1,
            NAMESPACE,
            READ_REQUEST_ID,
            INTAKE_REQUEST_ID,
            TENANT_ID,
            WORLD_SLUG,
            SOURCE_OPERATION_ID,
            SOURCE_DIGEST);
  }

  @Test
  void readByIdReturnsFullPublicReceiptAndExactRequestEcho() {
    AuthoredWorldSourceEvidence source =
        source(NAMESPACE, TENANT_ID, SOURCE_OPERATION_ID, WORLD_SLUG);
    WorldAuthoredSourceIntakeReceipt receipt = receipt(binding(), 9001L);
    var request = readByIdRequest();
    when(intakeService.readCommittedReceiptById(
            1, NAMESPACE, READ_REQUEST_ID, INTAKE_REQUEST_ID, TENANT_ID))
        .thenReturn(Optional.of(receipt));
    RecordingObserver<ReadAuthoredWorldSourceIntakeByIdResponse> observer =
        new RecordingObserver<>();

    withGameDesign(() -> grpcService.readAuthoredWorldSourceIntakeById(request, observer));

    assertThat(observer.error).isNull();
    assertThat(observer.completed).isTrue();
    assertThat(observer.values).hasSize(1);
    var response = observer.values.get(0);
    assertThat(response.getReadRequestId()).isEqualTo(READ_REQUEST_ID.toString());
    assertThat(response.getIntakeRequestId()).isEqualTo(INTAKE_REQUEST_ID.toString());
    var decoded = WorldAuthoredSourceIntakeGrpcCodec.fromReadByIdResponse(readById(), response);
    assertThat(decoded.source()).isEqualTo(source);
    assertThat(decoded.operationId()).isEqualTo(OPERATION_ID);
    assertThat(decoded.receiptDigest()).isEqualTo(receipt.receiptDigest());
    assertThat(response.getReceipt().getAllFields().keySet())
        .extracting(field -> field.getName())
        .doesNotContain("local_tenant_key", "localTenantKey", "local_version_key");
    assertThat(response.getReceipt().getSource().getAllFields().keySet())
        .extracting(field -> field.getName())
        .contains("registration_request_id", "source_game_row_id", "source_game_tenant_key");
    verify(intakeService)
        .readCommittedReceiptById(1, NAMESPACE, READ_REQUEST_ID, INTAKE_REQUEST_ID, TENANT_ID);
  }

  @Test
  void readByIdRejectsWrongPeerEndUserContextAndUnknownFieldsBeforeOwnerAccess() {
    var request = readByIdRequest();
    RecordingObserver<ReadAuthoredWorldSourceIntakeByIdResponse> wrongPeer =
        new RecordingObserver<>();
    withPeer(
        NAMESPACE,
        "game-session-service",
        () -> grpcService.readAuthoredWorldSourceIntakeById(request, wrongPeer));
    assertThat(Status.fromThrowable(wrongPeer.error).getCode())
        .isEqualTo(Status.Code.PERMISSION_DENIED);

    RecordingObserver<ReadAuthoredWorldSourceIntakeByIdResponse> userContext =
        new RecordingObserver<>();
    SessionContext.setContext("account", List.of(), java.util.Map.of());
    try {
      withGameDesign(() -> grpcService.readAuthoredWorldSourceIntakeById(request, userContext));
    } finally {
      SessionContext.clear();
    }
    assertThat(Status.fromThrowable(userContext.error).getCode())
        .isEqualTo(Status.Code.PERMISSION_DENIED);

    RecordingObserver<ReadAuthoredWorldSourceIntakeByIdResponse> unknown =
        new RecordingObserver<>();
    ReadAuthoredWorldSourceIntakeByIdRequest withUnknown =
        request.toBuilder().setUnknownFields(unknownField()).build();
    withGameDesign(() -> grpcService.readAuthoredWorldSourceIntakeById(withUnknown, unknown));
    assertThat(Status.fromThrowable(unknown.error).getCode())
        .isEqualTo(Status.Code.INVALID_ARGUMENT);

    RecordingObserver<ReadAuthoredWorldSourceIntakeByIdResponse> wrongNamespace =
        new RecordingObserver<>();
    withGameDesign(
        () ->
            grpcService.readAuthoredWorldSourceIntakeById(
                request.toBuilder().setTargetNamespace("other").build(), wrongNamespace));
    assertThat(Status.fromThrowable(wrongNamespace.error).getCode())
        .isEqualTo(Status.Code.PERMISSION_DENIED);

    for (ReadAuthoredWorldSourceIntakeByIdRequest invalidRequest :
        List.of(
            request.toBuilder().setSchemaVersion(2).build(),
            request.toBuilder().setReadRequestId(INTAKE_REQUEST_ID.toString()).build(),
            request.toBuilder().setCanonicalTenantId("not-a-canonical-uuid").build())) {
      RecordingObserver<ReadAuthoredWorldSourceIntakeByIdResponse> invalid =
          new RecordingObserver<>();
      withGameDesign(() -> grpcService.readAuthoredWorldSourceIntakeById(invalidRequest, invalid));
      assertThat(Status.fromThrowable(invalid.error).getCode())
          .isEqualTo(Status.Code.INVALID_ARGUMENT);
    }
    verifyNoInteractions(intakeService);
  }

  @Test
  void readByIdReportsMissingAndRejectsSubstitutedTenantReceipt() {
    var request = readByIdRequest();
    when(intakeService.readCommittedReceiptById(
            1, NAMESPACE, READ_REQUEST_ID, INTAKE_REQUEST_ID, TENANT_ID))
        .thenReturn(Optional.empty());
    RecordingObserver<ReadAuthoredWorldSourceIntakeByIdResponse> missing =
        new RecordingObserver<>();
    withGameDesign(() -> grpcService.readAuthoredWorldSourceIntakeById(request, missing));
    assertThat(Status.fromThrowable(missing.error).getCode()).isEqualTo(Status.Code.NOT_FOUND);
    assertThat(missing.values).isEmpty();

    UUID otherTenant = UUID.fromString("77777777-7777-4777-8777-777777777777");
    IntakeRequest otherBinding =
        new IntakeRequest(
            1,
            NAMESPACE,
            INTAKE_REQUEST_ID,
            otherTenant,
            WORLD_SLUG,
            SOURCE_OPERATION_ID,
            source(NAMESPACE, otherTenant, SOURCE_OPERATION_ID, WORLD_SLUG).evidenceDigest());
    when(intakeService.readCommittedReceiptById(
            1, NAMESPACE, READ_REQUEST_ID, INTAKE_REQUEST_ID, TENANT_ID))
        .thenReturn(Optional.of(receipt(otherBinding, 9001L)));
    RecordingObserver<ReadAuthoredWorldSourceIntakeByIdResponse> changed =
        new RecordingObserver<>();
    withGameDesign(() -> grpcService.readAuthoredWorldSourceIntakeById(request, changed));
    assertThat(Status.fromThrowable(changed.error).getCode())
        .isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(changed.values).isEmpty();
  }

  @Test
  void rejectsUnknownWireFieldsBeforeCallingOwnerService() {
    IntakeAuthoredWorldSourceRequest request =
        intakeRequest().toBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
                    .build())
            .build();
    RecordingObserver<IntakeAuthoredWorldSourceResponse> observer = new RecordingObserver<>();

    grpcService.intakeAuthoredWorldSource(request, observer);

    assertThat(Status.fromThrowable(observer.error).getCode())
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(observer.values).isEmpty();
    assertThat(observer.completed).isFalse();
    verifyNoInteractions(intakeService);

    RecordingObserver<IntakeAuthoredWorldSourceResponse> invalid = new RecordingObserver<>();
    withGameDesign(() -> grpcService.intakeAuthoredWorldSource(request, invalid));
    assertThat(Status.fromThrowable(invalid.error).getCode())
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(invalid.values).isEmpty();
    verifyNoInteractions(intakeService);

    RecordingObserver<IntakeAuthoredWorldSourceResponse> wrongTargetNamespace =
        new RecordingObserver<>();
    IntakeAuthoredWorldSourceRequest crossNamespaceRequest =
        intakeRequest().toBuilder().setTargetNamespace("other").build();
    withGameDesign(
        () -> grpcService.intakeAuthoredWorldSource(crossNamespaceRequest, wrongTargetNamespace));
    assertThat(Status.fromThrowable(wrongTargetNamespace.error).getCode())
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(wrongTargetNamespace.values).isEmpty();
    verifyNoInteractions(intakeService);

    RecordingObserver<ReadAuthoredWorldSourceIntakeResponse> wrongWorkload =
        new RecordingObserver<>();
    withPeer(
        NAMESPACE,
        "game-session-service",
        () -> grpcService.readAuthoredWorldSourceIntake(readRequest(), wrongWorkload));
    assertThat(Status.fromThrowable(wrongWorkload.error).getCode())
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(wrongWorkload.values).isEmpty();
    verifyNoInteractions(intakeService);

    RecordingObserver<IntakeAuthoredWorldSourceResponse> wrongNamespace = new RecordingObserver<>();
    withPeer(
        "other",
        "game-design-service",
        () -> grpcService.intakeAuthoredWorldSource(intakeRequest(), wrongNamespace));
    assertThat(Status.fromThrowable(wrongNamespace.error).getCode())
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(wrongNamespace.values).isEmpty();
    verifyNoInteractions(intakeService);

    RecordingObserver<ReadAuthoredWorldSourceIntakeResponse> wrongReadTargetNamespace =
        new RecordingObserver<>();
    ReadAuthoredWorldSourceIntakeRequest crossNamespaceReadRequest =
        readRequest().toBuilder().setTargetNamespace("other").build();
    withGameDesign(
        () ->
            grpcService.readAuthoredWorldSourceIntake(
                crossNamespaceReadRequest, wrongReadTargetNamespace));
    assertThat(Status.fromThrowable(wrongReadTargetNamespace.error).getCode())
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(wrongReadTargetNamespace.values).isEmpty();
    verifyNoInteractions(intakeService);
  }

  @Test
  void mapsPeerDenialAndRepositoryConflictToNonSuccessGrpcOutcomes() {
    doThrow(
            new SecurityException("not the Game Design workload"),
            new WorldAuthoredSourceIntakeRepository.RegistrationConflictException("changed source"))
        .when(intakeService)
        .intake(
            1,
            NAMESPACE,
            INTAKE_REQUEST_ID,
            TENANT_ID,
            WORLD_SLUG,
            SOURCE_OPERATION_ID,
            SOURCE_DIGEST);
    RecordingObserver<IntakeAuthoredWorldSourceResponse> denied = new RecordingObserver<>();
    withGameDesign(() -> grpcService.intakeAuthoredWorldSource(intakeRequest(), denied));
    assertThat(Status.fromThrowable(denied.error).getCode())
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(denied.values).isEmpty();

    RecordingObserver<IntakeAuthoredWorldSourceResponse> conflict = new RecordingObserver<>();
    withGameDesign(() -> grpcService.intakeAuthoredWorldSource(intakeRequest(), conflict));
    assertThat(Status.fromThrowable(conflict.error).getCode())
        .isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(conflict.values).isEmpty();
  }

  @Test
  void preservesTransientStorageFailureAndReportsAbsentReadWithoutReceipt() {
    when(intakeService.intake(
            1,
            NAMESPACE,
            INTAKE_REQUEST_ID,
            TENANT_ID,
            WORLD_SLUG,
            SOURCE_OPERATION_ID,
            SOURCE_DIGEST))
        .thenThrow(new DataAccessResourceFailureException("database unavailable"));
    RecordingObserver<IntakeAuthoredWorldSourceResponse> unavailable = new RecordingObserver<>();
    withGameDesign(() -> grpcService.intakeAuthoredWorldSource(intakeRequest(), unavailable));
    assertThat(Status.fromThrowable(unavailable.error).getCode())
        .isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(unavailable.values).isEmpty();

    when(intakeService.readCommittedReceipt(
            1,
            NAMESPACE,
            READ_REQUEST_ID,
            INTAKE_REQUEST_ID,
            TENANT_ID,
            WORLD_SLUG,
            SOURCE_OPERATION_ID,
            SOURCE_DIGEST))
        .thenReturn(Optional.empty());
    RecordingObserver<ReadAuthoredWorldSourceIntakeResponse> missing = new RecordingObserver<>();
    withGameDesign(() -> grpcService.readAuthoredWorldSourceIntake(readRequest(), missing));
    assertThat(Status.fromThrowable(missing.error).getCode()).isEqualTo(Status.Code.NOT_FOUND);
    assertThat(missing.values).isEmpty();
    verify(intakeService)
        .readCommittedReceipt(
            1,
            NAMESPACE,
            READ_REQUEST_ID,
            INTAKE_REQUEST_ID,
            TENANT_ID,
            WORLD_SLUG,
            SOURCE_OPERATION_ID,
            SOURCE_DIGEST);
  }

  @Test
  void refusesToReturnAnOwnerReceiptWhoseBindingDoesNotMatch() {
    WorldAuthoredSourceIntakeReceipt changed = receipt(binding("other-world"), 9001L);
    when(intakeService.intake(
            1,
            NAMESPACE,
            INTAKE_REQUEST_ID,
            TENANT_ID,
            WORLD_SLUG,
            SOURCE_OPERATION_ID,
            SOURCE_DIGEST))
        .thenReturn(changed);
    RecordingObserver<IntakeAuthoredWorldSourceResponse> observer = new RecordingObserver<>();

    withGameDesign(() -> grpcService.intakeAuthoredWorldSource(intakeRequest(), observer));

    assertThat(Status.fromThrowable(observer.error).getCode())
        .isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(observer.values).isEmpty();
    verify(intakeService)
        .intake(
            1,
            NAMESPACE,
            INTAKE_REQUEST_ID,
            TENANT_ID,
            WORLD_SLUG,
            SOURCE_OPERATION_ID,
            SOURCE_DIGEST);
  }

  private static IntakeRequest binding() {
    return binding(WORLD_SLUG);
  }

  private static IntakeRequest binding(String worldSlug) {
    AuthoredWorldSourceEvidence source =
        source(NAMESPACE, TENANT_ID, SOURCE_OPERATION_ID, worldSlug);
    return new IntakeRequest(
        1,
        NAMESPACE,
        INTAKE_REQUEST_ID,
        TENANT_ID,
        worldSlug,
        SOURCE_OPERATION_ID,
        source.evidenceDigest());
  }

  private static IntakeAuthoredWorldSourceRequest intakeRequest() {
    return WorldAuthoredSourceIntakeGrpcCodec.toIntakeRequest(binding());
  }

  private static ReadAuthoredWorldSourceIntakeRequest readRequest() {
    return WorldAuthoredSourceIntakeGrpcCodec.toReadRequest(
        new WorldAuthoredSourceIntakeGrpcCodec.ReadRequest(binding(), READ_REQUEST_ID));
  }

  private static WorldAuthoredSourceIntakeGrpcCodec.ByIdReadRequest readById() {
    return new WorldAuthoredSourceIntakeGrpcCodec.ByIdReadRequest(
        1, NAMESPACE, READ_REQUEST_ID, INTAKE_REQUEST_ID, TENANT_ID);
  }

  private static ReadAuthoredWorldSourceIntakeByIdRequest readByIdRequest() {
    return WorldAuthoredSourceIntakeGrpcCodec.toReadByIdRequest(readById());
  }

  private static WorldAuthoredSourceIntakeReceipt receipt(
      IntakeRequest binding, long localTenantKey) {
    AuthoredWorldSourceEvidence source =
        source(
            binding.targetNamespace(),
            binding.canonicalTenantId(),
            binding.sourceOperationId(),
            binding.worldSlug());
    if (!binding.expectedSourceEvidenceDigest().equals(source.evidenceDigest())) {
      throw new IllegalArgumentException("Test source evidence does not match intake binding");
    }
    String requestDigest =
        WorldAuthoredSourceIntakeDigest.requestDigest(
            binding.targetNamespace(), binding.intakeRequestId(), source);
    return new WorldAuthoredSourceIntakeReceipt(
        1,
        binding.targetNamespace(),
        binding.intakeRequestId(),
        OPERATION_ID,
        binding.canonicalTenantId(),
        binding.worldSlug(),
        binding.sourceOperationId(),
        binding.expectedSourceEvidenceDigest(),
        requestDigest,
        WorldAuthoredSourceIntakeDigest.receiptDigest(
            binding.targetNamespace(), OPERATION_ID, requestDigest, source, localTenantKey),
        localTenantKey,
        source);
  }

  private static AuthoredWorldSourceEvidence source(
      String namespace, UUID tenantId, UUID sourceOperationId, String worldSlug) {
    UUID registrationRequestId = UUID.fromString("11111111-1111-4111-8111-111111111111");
    String sourceRequestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            namespace, registrationRequestId, tenantId, "north-star", worldSlug, "Café 🐉");
    String evidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            namespace,
            registrationRequestId,
            sourceOperationId,
            sourceRequestDigest,
            tenantId,
            "north-star",
            worldSlug,
            "Café 🐉",
            42L,
            "legacy-game-tenant-42",
            "NEW_GAME_ROW");
    return new AuthoredWorldSourceEvidence(
        1,
        namespace,
        registrationRequestId,
        sourceOperationId,
        sourceRequestDigest,
        tenantId,
        "north-star",
        worldSlug,
        "Café 🐉",
        42L,
        "legacy-game-tenant-42",
        "NEW_GAME_ROW",
        evidenceDigest);
  }

  private static UnknownFieldSet unknownField() {
    return UnknownFieldSet.newBuilder()
        .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
        .build();
  }

  private static void withGameDesign(Runnable action) {
    withPeer(NAMESPACE, "game-design-service", action);
  }

  private static void withPeer(String namespace, String service, Runnable action) {
    GrpcPeerIdentity peer =
        new GrpcPeerIdentity(
            "spiffe://firemud/ns/" + namespace + "/sa/" + service, namespace, service);
    Context context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context previous = context.attach();
    try {
      action.run();
    } finally {
      context.detach(previous);
    }
  }

  private static final class RecordingObserver<T> implements StreamObserver<T> {
    private final List<T> values = new ArrayList<>();
    private Throwable error;
    private boolean completed;

    @Override
    public void onNext(T value) {
      values.add(value);
    }

    @Override
    @SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification =
            "The test recorder retains the exact original throwable solely for assertion.")
    public void onError(Throwable throwable) {
      error = throwable;
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
