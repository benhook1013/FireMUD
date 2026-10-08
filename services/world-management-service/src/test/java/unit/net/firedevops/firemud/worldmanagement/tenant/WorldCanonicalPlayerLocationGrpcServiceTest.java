package unit.net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Outcome;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.authoring.WorldDraftTerminalReadEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.WorldCanonicalInitialPlayerLocation;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.common.world.WorldDraftStartLocationEvidence;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalCurrentPlayerLocationRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalCurrentPlayerLocationService;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInitialPlayerLocationRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInitialPlayerLocationService;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalPlayerLocationGrpcService;
import net.firedevops.firemud.worldmanagement.v1.PlaceCanonicalInitialPlayerLocationRequest;
import net.firedevops.firemud.worldmanagement.v1.PlaceCanonicalInitialPlayerLocationResponse;
import net.firedevops.firemud.worldmanagement.v1.ReadCanonicalCurrentPlayerLocationRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadCanonicalCurrentPlayerLocationResponse;
import net.firedevops.firemud.worldmanagement.v1.ReadCanonicalInitialPlayerLocationOutcomeRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadCanonicalInitialPlayerLocationOutcomeResponse;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class WorldCanonicalPlayerLocationGrpcServiceTest {
  private static final String NAMESPACE = "test";
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final UUID OPERATION_ID = uuid("a0000000-0000-4000-8000-000000000001");
  private static final UUID TERMINAL_READ_ID = uuid("d0000000-0000-4000-8000-000000000001");
  private static final UUID OTHER_OPERATION_ID = uuid("10000000-0000-4000-8000-000000000002");
  private static final UUID REGION_INSTANCE_ID = uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID OPERATIONAL_REGION_ID = uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");

  @Test
  void rejectsMissingWrongOrWrongNamespacePeerBeforeParsingOrOwnerAccess() throws Exception {
    var placementService = mock(WorldCanonicalInitialPlayerLocationService.class);
    var currentService = mock(WorldCanonicalCurrentPlayerLocationService.class);
    var grpc = service(placementService, currentService);
    var malformed = placeRequest(new byte[] {1}, new byte[] {1});
    var malformedRead = readRequest("not-a-uuid", new byte[] {1}, new byte[] {1});

    Collector<PlaceCanonicalInitialPlayerLocationResponse> missing =
        callPlace(grpc, malformed, null);
    Collector<PlaceCanonicalInitialPlayerLocationResponse> wrongService =
        callPlace(grpc, malformed, peer("account-service", NAMESPACE));
    Collector<PlaceCanonicalInitialPlayerLocationResponse> wrongNamespace =
        callPlace(grpc, malformed, peer("game-session-service", "other"));
    Collector<ReadCanonicalCurrentPlayerLocationResponse> readWrongService =
        callRead(grpc, malformedRead, peer("account-service", NAMESPACE));

    assertThat(missing.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(wrongService.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(wrongNamespace.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(readWrongService.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(placementService, currentService);
  }

  @Test
  void terminalOutcomeReadRequiresExactSameNamespaceEntityPeerBeforeOwnerAccess() throws Exception {
    var placementService = mock(WorldCanonicalInitialPlayerLocationService.class);
    var currentService = mock(WorldCanonicalCurrentPlayerLocationService.class);
    var grpc = service(placementService, currentService);
    var malformed = terminalOutcomeRequest("not-a-uuid", new byte[] {1}, new byte[] {1});

    Collector<ReadCanonicalInitialPlayerLocationOutcomeResponse> missing =
        callTerminalOutcome(grpc, malformed, null);
    Collector<ReadCanonicalInitialPlayerLocationOutcomeResponse> wrongService =
        callTerminalOutcome(grpc, malformed, peer("game-session-service", NAMESPACE));
    Collector<ReadCanonicalInitialPlayerLocationOutcomeResponse> wrongNamespace =
        callTerminalOutcome(grpc, malformed, peer("entity-management-service", "other"));

    assertThat(missing.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(wrongService.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(wrongNamespace.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(placementService, currentService);
  }

  @Test
  void terminalOutcomeReadRejectsEndUserContextAndAmbientTransactionsBeforeOwnerAccess()
      throws Exception {
    var placementService = mock(WorldCanonicalInitialPlayerLocationService.class);
    var currentService = mock(WorldCanonicalCurrentPlayerLocationService.class);
    var grpc = service(placementService, currentService);
    var malformed = terminalOutcomeRequest("not-a-uuid", new byte[] {1}, new byte[] {1});

    SessionContext.setContext("11111111-1111-4111-8111-111111111111", List.of(), Map.of());
    Collector<ReadCanonicalInitialPlayerLocationOutcomeResponse> callerContext;
    try {
      callerContext =
          callTerminalOutcome(grpc, malformed, peer("entity-management-service", NAMESPACE));
    } finally {
      SessionContext.clear();
    }

    TransactionSynchronizationManager.setActualTransactionActive(true);
    Collector<ReadCanonicalInitialPlayerLocationOutcomeResponse> transaction;
    try {
      transaction =
          callTerminalOutcome(grpc, malformed, peer("entity-management-service", NAMESPACE));
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    assertThat(callerContext.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(transaction.error).isEqualTo(Status.Code.FAILED_PRECONDITION);
    verifyNoInteractions(placementService, currentService);
  }

  @Test
  void terminalOutcomeReadRejectsMalformedOrReusedCorrelationBeforeOwnerAccess() throws Exception {
    var placementService = mock(WorldCanonicalInitialPlayerLocationService.class);
    var currentService = mock(WorldCanonicalCurrentPlayerLocationService.class);
    var grpc = service(placementService, currentService);
    var fixture = fixture(NAMESPACE, "b0000000-0000-4000-8000-000000000001");

    var malformed =
        callTerminalOutcome(
            grpc,
            terminalOutcomeRequest(
                TERMINAL_READ_ID.toString(), new byte[] {1}, fixture.originalLifecycleBytes()),
            peer("entity-management-service", NAMESPACE));
    var operationCorrelation =
        callTerminalOutcome(
            grpc,
            terminalOutcomeRequest(
                OPERATION_ID.toString(),
                fixture.request().canonicalRequestBytes(),
                fixture.originalLifecycleBytes()),
            peer("entity-management-service", NAMESPACE));
    var lifecycleCorrelation =
        callTerminalOutcome(
            grpc,
            terminalOutcomeRequest(
                fixture.readRequestId().toString(),
                fixture.request().canonicalRequestBytes(),
                fixture.originalLifecycleBytes()),
            peer("entity-management-service", NAMESPACE));
    var otherNamespace = fixture("other", "c0000000-0000-4000-8000-000000000001");
    var namespaceMismatch =
        callTerminalOutcome(
            grpc,
            terminalOutcomeRequest(
                TERMINAL_READ_ID.toString(),
                otherNamespace.request().canonicalRequestBytes(),
                otherNamespace.originalLifecycleBytes()),
            peer("entity-management-service", NAMESPACE));

    assertThat(malformed.error).isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(operationCorrelation.error).isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(lifecycleCorrelation.error).isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(namespaceMismatch.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(placementService, currentService);
  }

  @Test
  void rejectsAuthenticatedEndUserContextBeforeParsingOrOwnerAccess() throws Exception {
    var placementService = mock(WorldCanonicalInitialPlayerLocationService.class);
    var currentService = mock(WorldCanonicalCurrentPlayerLocationService.class);
    var grpc = service(placementService, currentService);
    SessionContext.setContext("11111111-1111-4111-8111-111111111111", List.of(), Map.of());
    Collector<PlaceCanonicalInitialPlayerLocationResponse> placeResult;
    Collector<ReadCanonicalCurrentPlayerLocationResponse> readResult;
    try {
      placeResult =
          callPlace(
              grpc,
              placeRequest(new byte[] {1}, new byte[] {1}),
              peer("game-session-service", NAMESPACE));
      readResult =
          callRead(
              grpc,
              readRequest("not-a-uuid", new byte[] {1}, new byte[] {1}),
              peer("game-session-service", NAMESPACE));
    } finally {
      SessionContext.clear();
    }

    assertThat(placeResult.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(readResult.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(placementService, currentService);
  }

  @Test
  void rejectsAmbientTransactionAndSynchronizationBeforeParsingOrOwnerAccess() {
    var placementService = mock(WorldCanonicalInitialPlayerLocationService.class);
    var currentService = mock(WorldCanonicalCurrentPlayerLocationService.class);
    var grpc = service(placementService, currentService);
    var malformedPlace = placeRequest(new byte[] {1}, new byte[] {1});
    var malformedRead = readRequest("not-a-uuid", new byte[] {1}, new byte[] {1});

    TransactionSynchronizationManager.setActualTransactionActive(true);
    Collector<PlaceCanonicalInitialPlayerLocationResponse> transactionPlace;
    try {
      transactionPlace = callPlace(grpc, malformedPlace, peer("game-session-service", NAMESPACE));
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    TransactionSynchronizationManager.initSynchronization();
    Collector<ReadCanonicalCurrentPlayerLocationResponse> synchronizationRead;
    try {
      synchronizationRead = callRead(grpc, malformedRead, peer("game-session-service", NAMESPACE));
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }

    assertThat(transactionPlace.error).isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(synchronizationRead.error).isEqualTo(Status.Code.FAILED_PRECONDITION);
    verifyNoInteractions(placementService, currentService);
  }

  @Test
  void rejectsUnknownEmptyMalformedAndNamespaceSubstitutedEvidenceBeforeOwnerAccess()
      throws Exception {
    var placementService = mock(WorldCanonicalInitialPlayerLocationService.class);
    var currentService = mock(WorldCanonicalCurrentPlayerLocationService.class);
    var grpc = service(placementService, currentService);
    var fixture = fixture(NAMESPACE, "b0000000-0000-4000-8000-000000000001");
    var unknownPlace =
        placeRequest(fixture.request().canonicalRequestBytes(), fixture.originalLifecycleBytes())
            .toBuilder()
            .setUnknownFields(unknownField())
            .build();
    var unknownRead =
        readRequest(
                fixture.readRequestId().toString(),
                fixture.request().canonicalRequestBytes(),
                fixture.originalLifecycleBytes())
            .toBuilder()
            .setUnknownFields(unknownField())
            .build();

    assertThat(callPlace(grpc, unknownPlace, peer("game-session-service", NAMESPACE)).error)
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(
            callPlace(
                    grpc,
                    placeRequest(new byte[0], fixture.originalLifecycleBytes()),
                    peer("game-session-service", NAMESPACE))
                .error)
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(
            callPlace(
                    grpc,
                    placeRequest(fixture.request().canonicalRequestBytes(), new byte[0]),
                    peer("game-session-service", NAMESPACE))
                .error)
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(
            callPlace(
                    grpc,
                    placeRequest(new byte[] {1}, fixture.originalLifecycleBytes()),
                    peer("game-session-service", NAMESPACE))
                .error)
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    String canonicalPlacementRequest =
        new String(fixture.request().canonicalRequestBytes(), StandardCharsets.UTF_8);
    assertThat(
            callPlace(
                    grpc,
                    placeRequest(
                        canonicalPlacementRequest
                            .replace(OPERATION_ID.toString(), NIL_UUID.toString())
                            .getBytes(StandardCharsets.UTF_8),
                        fixture.originalLifecycleBytes()),
                    peer("game-session-service", NAMESPACE))
                .error)
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(
            callPlace(
                    grpc,
                    placeRequest(
                        canonicalPlacementRequest
                            .replace(OPERATION_ID.toString(), OPERATION_ID.toString().toUpperCase())
                            .getBytes(StandardCharsets.UTF_8),
                        fixture.originalLifecycleBytes()),
                    peer("game-session-service", NAMESPACE))
                .error)
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(callRead(grpc, unknownRead, peer("game-session-service", NAMESPACE)).error)
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(
            callRead(
                    grpc,
                    readRequest(
                        fixture.readRequestId().toString(),
                        new byte[0],
                        fixture.originalLifecycleBytes()),
                    peer("game-session-service", NAMESPACE))
                .error)
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(
            callRead(
                    grpc,
                    readRequest(
                        fixture.readRequestId().toString(),
                        fixture.request().canonicalRequestBytes(),
                        new byte[0]),
                    peer("game-session-service", NAMESPACE))
                .error)
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(
            callRead(
                    grpc,
                    readRequest(
                        fixture.readRequestId().toString(),
                        new byte[] {1},
                        fixture.originalLifecycleBytes()),
                    peer("game-session-service", NAMESPACE))
                .error)
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(
            callRead(
                    grpc,
                    readRequest(
                        NIL_UUID.toString(),
                        fixture.request().canonicalRequestBytes(),
                        fixture.originalLifecycleBytes()),
                    peer("game-session-service", NAMESPACE))
                .error)
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(
            callRead(
                    grpc,
                    readRequest(
                        fixture.readRequestId().toString().toUpperCase(),
                        fixture.request().canonicalRequestBytes(),
                        fixture.originalLifecycleBytes()),
                    peer("game-session-service", NAMESPACE))
                .error)
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(
            callRead(
                    grpc,
                    readRequest(
                        "not-a-uuid",
                        fixture.request().canonicalRequestBytes(),
                        fixture.originalLifecycleBytes()),
                    peer("game-session-service", NAMESPACE))
                .error)
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    Fixture reusedOperationId = fixture(NAMESPACE, OPERATION_ID.toString());
    assertThat(
            callRead(
                    grpc,
                    readRequest(
                        OPERATION_ID.toString(),
                        reusedOperationId.request().canonicalRequestBytes(),
                        reusedOperationId.originalLifecycleBytes()),
                    peer("game-session-service", NAMESPACE))
                .error)
        .isEqualTo(Status.Code.INVALID_ARGUMENT);

    Fixture otherNamespace = fixture("other", "b0000000-0000-4000-8000-000000000002");
    assertThat(
            callPlace(
                    grpc,
                    placeRequest(
                        otherNamespace.request().canonicalRequestBytes(),
                        otherNamespace.originalLifecycleBytes()),
                    peer("game-session-service", NAMESPACE))
                .error)
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(
            callRead(
                    grpc,
                    readRequest(
                        otherNamespace.readRequestId().toString(),
                        otherNamespace.request().canonicalRequestBytes(),
                        otherNamespace.originalLifecycleBytes()),
                    peer("game-session-service", NAMESPACE))
                .error)
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(placementService, currentService);
  }

  @Test
  void rejectsReadCorrelationThatDoesNotMatchFullCurrentLifecycleEvidence() throws Exception {
    var placementService = mock(WorldCanonicalInitialPlayerLocationService.class);
    var currentService = mock(WorldCanonicalCurrentPlayerLocationService.class);
    var grpc = service(placementService, currentService);
    var fixture = fixture(NAMESPACE, "b0000000-0000-4000-8000-000000000001");

    Collector<ReadCanonicalCurrentPlayerLocationResponse> result =
        callRead(
            grpc,
            readRequest(
                "b0000000-0000-4000-8000-000000000002",
                fixture.request().canonicalRequestBytes(),
                fixture.originalLifecycleBytes()),
            peer("game-session-service", NAMESPACE));

    assertThat(result.error).isEqualTo(Status.Code.INVALID_ARGUMENT);
    verifyNoInteractions(placementService, currentService);
  }

  @Test
  void defaultPlacementAndCurrentLocationVerifiersDenyOwnerAccess() throws Exception {
    var placementRepository = mock(WorldCanonicalInitialPlayerLocationRepository.class);
    var currentRepository = mock(WorldCanonicalCurrentPlayerLocationRepository.class);
    var placementService = new WorldCanonicalInitialPlayerLocationService(placementRepository);
    var currentService = new WorldCanonicalCurrentPlayerLocationService(currentRepository);
    var grpc = service(placementService, currentService);
    var fixture = fixture(NAMESPACE, "b0000000-0000-4000-8000-000000000001");

    Collector<PlaceCanonicalInitialPlayerLocationResponse> placeResult =
        callPlace(
            grpc,
            placeRequest(
                fixture.request().canonicalRequestBytes(), fixture.originalLifecycleBytes()),
            peer("game-session-service", NAMESPACE));
    Collector<ReadCanonicalCurrentPlayerLocationResponse> readResult =
        callRead(
            grpc,
            readRequest(
                fixture.readRequestId().toString(),
                fixture.request().canonicalRequestBytes(),
                fixture.originalLifecycleBytes()),
            peer("game-session-service", NAMESPACE));

    assertThat(placeResult.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(readResult.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    verify(placementRepository, never()).place(any(), any());
    verify(currentRepository, never()).read(any(), any());
  }

  @Test
  void exactPlacementRetryWithFreshLifecycleReadIdReturnsOriginalImmutableResult()
      throws Exception {
    var placementService = mock(WorldCanonicalInitialPlayerLocationService.class);
    var currentService = mock(WorldCanonicalCurrentPlayerLocationService.class);
    var grpc = service(placementService, currentService);
    Fixture original = fixture(NAMESPACE, "b0000000-0000-4000-8000-000000000001");
    Fixture retry = fixture(NAMESPACE, "b0000000-0000-4000-8000-000000000002");
    assertThat(retry.request().canonicalRequestBytes())
        .containsExactly(original.request().canonicalRequestBytes());
    var stored =
        WorldCanonicalInitialPlayerLocation.Result.applied(
            original.request(),
            original.lifecycleEvidence().startLocation(),
            original.lifecycleEvidence().runtimeRoomInstanceId());
    var exactRetryResult =
        WorldCanonicalInitialPlayerLocation.Result.fromStored(
            retry.request(), stored.canonicalBytes());
    when(placementService.place(any())).thenReturn(exactRetryResult);

    Collector<PlaceCanonicalInitialPlayerLocationResponse> response =
        callPlace(
            grpc,
            placeRequest(retry.request().canonicalRequestBytes(), retry.originalLifecycleBytes()),
            peer("game-session-service", NAMESPACE));

    assertThat(response.error).isNull();
    assertThat(response.completed).isTrue();
    assertThat(response.value.getOperationId()).isEqualTo(OPERATION_ID.toString());
    assertThat(response.value.getCanonicalResultBytes().toByteArray())
        .containsExactly(stored.canonicalBytes());
    ArgumentCaptor<WorldCanonicalInitialPlayerLocation.Request> captor =
        ArgumentCaptor.forClass(WorldCanonicalInitialPlayerLocation.Request.class);
    verify(placementService).place(captor.capture());
    assertThat(captor.getValue().originalLifecycleEvidenceBytes())
        .containsExactly(retry.originalLifecycleBytes());
  }

  @Test
  void rejectsPlacementConflictAndMapsStorageFailures() throws Exception {
    Fixture fixture = fixture(NAMESPACE, "b0000000-0000-4000-8000-000000000001");
    var conflictService = mock(WorldCanonicalInitialPlayerLocationService.class);
    when(conflictService.place(any()))
        .thenReturn(
            WorldCanonicalInitialPlayerLocation.Result.conflict(
                fixture.request(), "ACTOR_CONFLICT"));
    var transientService = mock(WorldCanonicalInitialPlayerLocationService.class);
    when(transientService.place(any()))
        .thenThrow(new TransientDataAccessException("temporary store issue") {});
    var permanentService = mock(WorldCanonicalInitialPlayerLocationService.class);
    when(permanentService.place(any()))
        .thenThrow(
            new DataAccessException("permanent store issue", new SQLException("bad schema")) {});
    var unknownService = mock(WorldCanonicalInitialPlayerLocationService.class);
    when(unknownService.place(any())).thenThrow(new IllegalStateException("unexpected failure"));

    var conflict =
        callPlace(
            service(conflictService, mock(WorldCanonicalCurrentPlayerLocationService.class)),
            placeRequest(
                fixture.request().canonicalRequestBytes(), fixture.originalLifecycleBytes()),
            peer("game-session-service", NAMESPACE));
    var unavailable =
        callPlace(
            service(transientService, mock(WorldCanonicalCurrentPlayerLocationService.class)),
            placeRequest(
                fixture.request().canonicalRequestBytes(), fixture.originalLifecycleBytes()),
            peer("game-session-service", NAMESPACE));
    var internal =
        callPlace(
            service(permanentService, mock(WorldCanonicalCurrentPlayerLocationService.class)),
            placeRequest(
                fixture.request().canonicalRequestBytes(), fixture.originalLifecycleBytes()),
            peer("game-session-service", NAMESPACE));
    var unknown =
        callPlace(
            service(unknownService, mock(WorldCanonicalCurrentPlayerLocationService.class)),
            placeRequest(
                fixture.request().canonicalRequestBytes(), fixture.originalLifecycleBytes()),
            peer("game-session-service", NAMESPACE));

    assertThat(conflict.error).isEqualTo(Status.Code.ALREADY_EXISTS);
    assertThat(unavailable.error).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(internal.error).isEqualTo(Status.Code.INTERNAL);
    assertThat(unknown.error).isEqualTo(Status.Code.INTERNAL);
  }

  @Test
  void terminalOutcomeReadReturnsExactAppliedBytesAndDoesNotConsultCurrentLocation()
      throws Exception {
    Fixture fixture = fixture(NAMESPACE, "b0000000-0000-4000-8000-000000000001");
    var applied =
        WorldCanonicalInitialPlayerLocation.Result.applied(
            fixture.request(),
            fixture.lifecycleEvidence().startLocation(),
            fixture.lifecycleEvidence().runtimeRoomInstanceId());
    var placementService = mock(WorldCanonicalInitialPlayerLocationService.class);
    var currentService = mock(WorldCanonicalCurrentPlayerLocationService.class);
    when(placementService.readTerminalOutcome(any()))
        .thenReturn(
            Optional.of(
                new WorldCanonicalInitialPlayerLocationRepository.TerminalReadback(
                    applied, fixture.originalLifecycleBytes())));
    var grpc = service(placementService, currentService);

    Collector<ReadCanonicalInitialPlayerLocationOutcomeResponse> response =
        callTerminalOutcome(
            grpc,
            terminalOutcomeRequest(
                TERMINAL_READ_ID.toString(),
                fixture.request().canonicalRequestBytes(),
                fixture.originalLifecycleBytes()),
            peer("entity-management-service", NAMESPACE));

    assertThat(response.error).isNull();
    assertThat(response.completed).isTrue();
    assertThat(response.value.getReadRequestId()).isEqualTo(TERMINAL_READ_ID.toString());
    assertThat(response.value.getPlacementOperationId()).isEqualTo(OPERATION_ID.toString());
    assertThat(response.value.getPlacementRequestDigest())
        .isEqualTo(fixture.request().requestDigest());
    assertThat(response.value.getImmutablePlacementResultBytes().toByteArray())
        .containsExactly(applied.canonicalBytes());
    assertThat(response.value.getOriginalPlacementLifecycleEvidenceBytes().toByteArray())
        .containsExactly(fixture.originalLifecycleBytes());
    verify(placementService).readTerminalOutcome(any());
    verify(placementService, never()).place(any());
    verifyNoInteractions(currentService);
  }

  @Test
  void terminalOutcomeReadReturnsDurableConflictAsEvidenceInsteadOfGrpcConflict() throws Exception {
    Fixture fixture = fixture(NAMESPACE, "b0000000-0000-4000-8000-000000000001");
    var conflict =
        WorldCanonicalInitialPlayerLocation.Result.conflict(
            fixture.request(), "INITIAL_LOCATION_ALREADY_ASSIGNED");
    var placementService = mock(WorldCanonicalInitialPlayerLocationService.class);
    var currentService = mock(WorldCanonicalCurrentPlayerLocationService.class);
    when(placementService.readTerminalOutcome(any()))
        .thenReturn(
            Optional.of(
                new WorldCanonicalInitialPlayerLocationRepository.TerminalReadback(
                    conflict, fixture.originalLifecycleBytes())));
    var grpc = service(placementService, currentService);

    Collector<ReadCanonicalInitialPlayerLocationOutcomeResponse> response =
        callTerminalOutcome(
            grpc,
            terminalOutcomeRequest(
                TERMINAL_READ_ID.toString(),
                fixture.request().canonicalRequestBytes(),
                fixture.originalLifecycleBytes()),
            peer("entity-management-service", NAMESPACE));

    assertThat(response.error).isNull();
    assertThat(response.completed).isTrue();
    assertThat(
            WorldCanonicalInitialPlayerLocation.Result.fromStored(
                    fixture.request(),
                    response.value.getImmutablePlacementResultBytes().toByteArray())
                .outcome())
        .isEqualTo(WorldCanonicalInitialPlayerLocation.Outcome.CONFLICT);
    assertThat(response.value.getImmutablePlacementResultBytes().toByteArray())
        .containsExactly(conflict.canonicalBytes());
    assertThat(response.value.getOriginalPlacementLifecycleEvidenceBytes().toByteArray())
        .containsExactly(fixture.originalLifecycleBytes());
    verify(placementService).readTerminalOutcome(any());
    verify(placementService, never()).place(any());
    verifyNoInteractions(currentService);
  }

  @Test
  void missingTerminalOutcomeRemainsNotFoundAndDoesNotInvokeMutationOrCurrentRead()
      throws Exception {
    Fixture fixture = fixture(NAMESPACE, "b0000000-0000-4000-8000-000000000001");
    var placementService = mock(WorldCanonicalInitialPlayerLocationService.class);
    var currentService = mock(WorldCanonicalCurrentPlayerLocationService.class);
    when(placementService.readTerminalOutcome(any())).thenReturn(Optional.empty());
    var grpc = service(placementService, currentService);

    Collector<ReadCanonicalInitialPlayerLocationOutcomeResponse> response =
        callTerminalOutcome(
            grpc,
            terminalOutcomeRequest(
                TERMINAL_READ_ID.toString(),
                fixture.request().canonicalRequestBytes(),
                fixture.originalLifecycleBytes()),
            peer("entity-management-service", NAMESPACE));

    assertThat(response.error).isEqualTo(Status.Code.NOT_FOUND);
    assertThat(response.description).contains("remains unresolved");
    verify(placementService).readTerminalOutcome(any());
    verify(placementService, never()).place(any());
    verifyNoInteractions(currentService);
  }

  @Test
  void mismatchedOrCorruptTerminalResultFailsClosedWithoutReturningBytes() throws Exception {
    Fixture fixture = fixture(NAMESPACE, "b0000000-0000-4000-8000-000000000001");
    var substitutedRequest = placementRequestWithOperation(fixture, OTHER_OPERATION_ID);
    var substitutedResult =
        WorldCanonicalInitialPlayerLocation.Result.applied(
            substitutedRequest,
            substitutedRequest.activeLifecycleEvidence().startLocation(),
            substitutedRequest.activeLifecycleEvidence().runtimeRoomInstanceId());
    var mismatchedService = mock(WorldCanonicalInitialPlayerLocationService.class);
    when(mismatchedService.readTerminalOutcome(any()))
        .thenReturn(
            Optional.of(
                new WorldCanonicalInitialPlayerLocationRepository.TerminalReadback(
                    substitutedResult, fixture.originalLifecycleBytes())));
    var mismatched =
        callTerminalOutcome(
            service(mismatchedService, mock(WorldCanonicalCurrentPlayerLocationService.class)),
            terminalOutcomeRequest(
                TERMINAL_READ_ID.toString(),
                fixture.request().canonicalRequestBytes(),
                fixture.originalLifecycleBytes()),
            peer("entity-management-service", NAMESPACE));

    var corruptResult = mock(WorldCanonicalInitialPlayerLocation.Result.class);
    when(corruptResult.request()).thenReturn(fixture.request());
    when(corruptResult.requestDigest()).thenReturn(fixture.request().requestDigest());
    when(corruptResult.canonicalBytes()).thenReturn(new byte[] {1, 2, 3});
    var corruptService = mock(WorldCanonicalInitialPlayerLocationService.class);
    when(corruptService.readTerminalOutcome(any()))
        .thenReturn(
            Optional.of(
                new WorldCanonicalInitialPlayerLocationRepository.TerminalReadback(
                    corruptResult, fixture.originalLifecycleBytes())));
    var corrupt =
        callTerminalOutcome(
            service(corruptService, mock(WorldCanonicalCurrentPlayerLocationService.class)),
            terminalOutcomeRequest(
                TERMINAL_READ_ID.toString(),
                fixture.request().canonicalRequestBytes(),
                fixture.originalLifecycleBytes()),
            peer("entity-management-service", NAMESPACE));

    assertThat(mismatched.error).isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(mismatched.value).isNull();
    assertThat(corrupt.error).isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(corrupt.value).isNull();
  }

  @Test
  void foundLocationReturnsExactLifecycleResultProofAndDistinctRegionUuids() throws Exception {
    var fixture = fixture(NAMESPACE, "b0000000-0000-4000-8000-000000000001");
    Fixture originalPlacement = fixture(NAMESPACE, "c0000000-0000-4000-8000-000000000001");
    var immutableResult =
        WorldCanonicalInitialPlayerLocation.Result.applied(
            originalPlacement.request(),
            originalPlacement.lifecycleEvidence().startLocation(),
            originalPlacement.lifecycleEvidence().runtimeRoomInstanceId());
    var retainedResult =
        WorldCanonicalInitialPlayerLocation.Result.fromStored(
            fixture.request(), immutableResult.canonicalBytes());
    var current =
        currentLocation(fixture, originalPlacement.originalLifecycleBytes(), retainedResult);
    var placementService = mock(WorldCanonicalInitialPlayerLocationService.class);
    var currentService = mock(WorldCanonicalCurrentPlayerLocationService.class);
    when(currentService.read(any())).thenReturn(Optional.of(current));
    var grpc = service(placementService, currentService);

    Collector<ReadCanonicalCurrentPlayerLocationResponse> response =
        callRead(
            grpc,
            readRequest(
                fixture.readRequestId().toString(),
                fixture.request().canonicalRequestBytes(),
                fixture.originalLifecycleBytes()),
            peer("game-session-service", NAMESPACE));

    assertThat(response.error).isNull();
    assertThat(response.completed).isTrue();
    assertThat(response.value.getReadRequestId()).isEqualTo(fixture.readRequestId().toString());
    assertThat(response.value.getPlacementOperationId()).isEqualTo(OPERATION_ID.toString());
    assertThat(response.value.getPlacementRequestDigest())
        .isEqualTo(fixture.request().requestDigest());
    assertThat(response.value.getCurrentLifecycleEvidenceBytes().toByteArray())
        .containsExactly(fixture.originalLifecycleBytes());
    assertThat(response.value.getImmutablePlacementResultBytes().toByteArray())
        .containsExactly(immutableResult.canonicalBytes());
    assertThat(response.value.getOriginalPlacementLifecycleEvidenceBytes().toByteArray())
        .containsExactly(originalPlacement.originalLifecycleBytes());
    assertThat(response.value.getRegionInstanceId()).isEqualTo(REGION_INSTANCE_ID.toString());
    assertThat(response.value.getOperationalRegionId()).isEqualTo(OPERATIONAL_REGION_ID.toString());
    verify(currentService).read(any());
    verifyNoInteractions(placementService);
  }

  @Test
  void emptyCurrentPlacementMapsToNotFound() throws Exception {
    var fixture = fixture(NAMESPACE, "b0000000-0000-4000-8000-000000000001");
    var currentService = mock(WorldCanonicalCurrentPlayerLocationService.class);
    when(currentService.read(any())).thenReturn(Optional.empty());
    var grpc = service(mock(WorldCanonicalInitialPlayerLocationService.class), currentService);

    Collector<ReadCanonicalCurrentPlayerLocationResponse> response =
        callRead(
            grpc,
            readRequest(
                fixture.readRequestId().toString(),
                fixture.request().canonicalRequestBytes(),
                fixture.originalLifecycleBytes()),
            peer("game-session-service", NAMESPACE));

    assertThat(response.error).isEqualTo(Status.Code.NOT_FOUND);
  }

  @Test
  void substitutedReturnedBindingResultOrRegionEvidenceFailsClosed() throws Exception {
    Fixture fixture = fixture(NAMESPACE, "b0000000-0000-4000-8000-000000000001");
    Fixture original = fixture(NAMESPACE, "c0000000-0000-4000-8000-000000000001");
    var immutableResult =
        WorldCanonicalInitialPlayerLocation.Result.applied(
            original.request(),
            original.lifecycleEvidence().startLocation(),
            original.lifecycleEvidence().runtimeRoomInstanceId());
    var validResult =
        WorldCanonicalInitialPlayerLocation.Result.fromStored(
            fixture.request(), immutableResult.canonicalBytes());
    var changedBindingRequest = placementRequestWithOperation(fixture, OTHER_OPERATION_ID);
    var changedBindingResult =
        WorldCanonicalInitialPlayerLocation.Result.applied(
            changedBindingRequest,
            changedBindingRequest.activeLifecycleEvidence().startLocation(),
            changedBindingRequest.activeLifecycleEvidence().runtimeRoomInstanceId());
    var changedBindingLocation =
        currentLocation(
            fixture,
            original.originalLifecycleBytes(),
            changedBindingResult,
            changedBindingRequest);
    var bindingError = readForgedCurrent(fixture, changedBindingLocation);

    var substitutedResult =
        mock(WorldCanonicalCurrentPlayerLocationRepository.CurrentLocation.class);
    var changedResult =
        WorldCanonicalInitialPlayerLocation.Result.applied(
            placementRequestWithOperation(fixture, OTHER_OPERATION_ID),
            fixture.lifecycleEvidence().startLocation(),
            fixture.lifecycleEvidence().runtimeRoomInstanceId());
    stubCurrentLocation(
        substitutedResult, fixture, original.originalLifecycleBytes(), changedResult);
    var resultError = readForgedCurrent(fixture, substitutedResult);

    var substitutedRegion =
        mock(WorldCanonicalCurrentPlayerLocationRepository.CurrentLocation.class);
    stubCurrentLocation(substitutedRegion, fixture, original.originalLifecycleBytes(), validResult);
    when(substitutedRegion.operationalRegionId()).thenReturn(REGION_INSTANCE_ID);
    var regionError = readForgedCurrent(fixture, substitutedRegion);

    assertThat(bindingError).isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(resultError).isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(regionError).isEqualTo(Status.Code.FAILED_PRECONDITION);
  }

  @Test
  void mapsCurrentLocationStorageFailureAndUnknownFailure() throws Exception {
    var fixture = fixture(NAMESPACE, "b0000000-0000-4000-8000-000000000001");
    var transientService = mock(WorldCanonicalCurrentPlayerLocationService.class);
    when(transientService.read(any()))
        .thenThrow(new TransientDataAccessException("temporary store issue") {});
    var permanentService = mock(WorldCanonicalCurrentPlayerLocationService.class);
    when(permanentService.read(any()))
        .thenThrow(
            new DataAccessException("permanent store issue", new SQLException("bad schema")) {});
    var unknownService = mock(WorldCanonicalCurrentPlayerLocationService.class);
    when(unknownService.read(any())).thenThrow(new IllegalStateException("unexpected failure"));

    var request =
        readRequest(
            fixture.readRequestId().toString(),
            fixture.request().canonicalRequestBytes(),
            fixture.originalLifecycleBytes());
    var unavailable =
        callRead(
            service(mock(WorldCanonicalInitialPlayerLocationService.class), transientService),
            request,
            peer("game-session-service", NAMESPACE));
    var internal =
        callRead(
            service(mock(WorldCanonicalInitialPlayerLocationService.class), permanentService),
            request,
            peer("game-session-service", NAMESPACE));
    var unknown =
        callRead(
            service(mock(WorldCanonicalInitialPlayerLocationService.class), unknownService),
            request,
            peer("game-session-service", NAMESPACE));

    assertThat(unavailable.error).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(internal.error).isEqualTo(Status.Code.INTERNAL);
    assertThat(unknown.error).isEqualTo(Status.Code.INTERNAL);
  }

  private static Status.Code readForgedCurrent(
      Fixture fixture, WorldCanonicalCurrentPlayerLocationRepository.CurrentLocation location) {
    var currentService = mock(WorldCanonicalCurrentPlayerLocationService.class);
    when(currentService.read(any())).thenReturn(Optional.of(location));
    var isolated = service(mock(WorldCanonicalInitialPlayerLocationService.class), currentService);
    Collector<ReadCanonicalCurrentPlayerLocationResponse> result =
        callRead(
            isolated,
            readRequest(
                fixture.readRequestId().toString(),
                fixture.request().canonicalRequestBytes(),
                fixture.originalLifecycleBytes()),
            peer("game-session-service", NAMESPACE));
    return result.error;
  }

  private static WorldCanonicalCurrentPlayerLocationRepository.CurrentLocation currentLocation(
      Fixture currentFixture,
      byte[] originalPlacementLifecycleBytes,
      WorldCanonicalInitialPlayerLocation.Result placementResult) {
    return currentLocation(
        currentFixture, originalPlacementLifecycleBytes, placementResult, currentFixture.request());
  }

  private static WorldCanonicalCurrentPlayerLocationRepository.CurrentLocation currentLocation(
      Fixture currentFixture,
      byte[] originalPlacementLifecycleBytes,
      WorldCanonicalInitialPlayerLocation.Result placementResult,
      WorldCanonicalInitialPlayerLocation.Request binding) {
    return new WorldCanonicalCurrentPlayerLocationRepository.CurrentLocation(
        binding,
        currentFixture.lifecycleEvidence(),
        101L,
        currentFixture.lifecycleEvidence().startLocation(),
        currentFixture.lifecycleEvidence().runtimeRoomInstanceId(),
        202L,
        REGION_INSTANCE_ID,
        OPERATIONAL_REGION_ID,
        placementResult,
        originalPlacementLifecycleBytes);
  }

  private static WorldCanonicalInitialPlayerLocation.Request placementRequestWithOperation(
      Fixture fixture, UUID operationId) {
    return placementRequest(fixture.lifecycleEvidence(), operationId);
  }

  private static void stubCurrentLocation(
      WorldCanonicalCurrentPlayerLocationRepository.CurrentLocation location,
      Fixture fixture,
      byte[] originalPlacementLifecycleBytes,
      WorldCanonicalInitialPlayerLocation.Result result) {
    when(location.binding()).thenReturn(fixture.request());
    when(location.currentLifecycleEvidence()).thenReturn(fixture.lifecycleEvidence());
    when(location.startLocation()).thenReturn(fixture.lifecycleEvidence().startLocation());
    when(location.runtimeRoomInstanceId())
        .thenReturn(fixture.lifecycleEvidence().runtimeRoomInstanceId());
    when(location.canonicalRegionInstanceId()).thenReturn(REGION_INSTANCE_ID);
    when(location.operationalRegionId()).thenReturn(OPERATIONAL_REGION_ID);
    when(location.placementResult()).thenReturn(result);
    when(location.originalLifecycleEvidenceBytes()).thenReturn(originalPlacementLifecycleBytes);
  }

  private static WorldCanonicalPlayerLocationGrpcService service(
      WorldCanonicalInitialPlayerLocationService placementService,
      WorldCanonicalCurrentPlayerLocationService currentService) {
    return new WorldCanonicalPlayerLocationGrpcService(placementService, currentService, NAMESPACE);
  }

  private static PlaceCanonicalInitialPlayerLocationRequest placeRequest(
      byte[] canonicalRequestBytes, byte[] lifecycleEvidenceBytes) {
    return PlaceCanonicalInitialPlayerLocationRequest.newBuilder()
        .setCanonicalRequestBytes(ByteString.copyFrom(canonicalRequestBytes))
        .setOriginalLifecycleEvidenceBytes(ByteString.copyFrom(lifecycleEvidenceBytes))
        .build();
  }

  private static ReadCanonicalCurrentPlayerLocationRequest readRequest(
      String readRequestId, byte[] canonicalRequestBytes, byte[] lifecycleEvidenceBytes) {
    return ReadCanonicalCurrentPlayerLocationRequest.newBuilder()
        .setReadRequestId(readRequestId)
        .setCanonicalPlacementRequestBytes(ByteString.copyFrom(canonicalRequestBytes))
        .setOriginalLifecycleEvidenceBytes(ByteString.copyFrom(lifecycleEvidenceBytes))
        .build();
  }

  private static ReadCanonicalInitialPlayerLocationOutcomeRequest terminalOutcomeRequest(
      String readRequestId, byte[] canonicalRequestBytes, byte[] lifecycleEvidenceBytes) {
    return ReadCanonicalInitialPlayerLocationOutcomeRequest.newBuilder()
        .setReadRequestId(readRequestId)
        .setCanonicalPlacementRequestBytes(ByteString.copyFrom(canonicalRequestBytes))
        .setOriginalLifecycleEvidenceBytes(ByteString.copyFrom(lifecycleEvidenceBytes))
        .build();
  }

  private static UnknownFieldSet unknownField() {
    return UnknownFieldSet.newBuilder()
        .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
        .build();
  }

  private static Collector<PlaceCanonicalInitialPlayerLocationResponse> callPlace(
      WorldCanonicalPlayerLocationGrpcService service,
      PlaceCanonicalInitialPlayerLocationRequest request,
      GrpcPeerIdentity peer) {
    Collector<PlaceCanonicalInitialPlayerLocationResponse> response = new Collector<>();
    Context context =
        peer == null
            ? Context.current()
            : Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context previous = context.attach();
    try {
      service.placeCanonicalInitialPlayerLocation(request, response);
    } finally {
      context.detach(previous);
    }
    return response;
  }

  private static Collector<ReadCanonicalCurrentPlayerLocationResponse> callRead(
      WorldCanonicalPlayerLocationGrpcService service,
      ReadCanonicalCurrentPlayerLocationRequest request,
      GrpcPeerIdentity peer) {
    Collector<ReadCanonicalCurrentPlayerLocationResponse> response = new Collector<>();
    Context context =
        peer == null
            ? Context.current()
            : Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context previous = context.attach();
    try {
      service.readCanonicalCurrentPlayerLocation(request, response);
    } finally {
      context.detach(previous);
    }
    return response;
  }

  private static Collector<ReadCanonicalInitialPlayerLocationOutcomeResponse> callTerminalOutcome(
      WorldCanonicalPlayerLocationGrpcService service,
      ReadCanonicalInitialPlayerLocationOutcomeRequest request,
      GrpcPeerIdentity peer) {
    Collector<ReadCanonicalInitialPlayerLocationOutcomeResponse> response = new Collector<>();
    Context context =
        peer == null
            ? Context.current()
            : Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context previous = context.attach();
    try {
      service.readCanonicalInitialPlayerLocationOutcome(request, response);
    } finally {
      context.detach(previous);
    }
    return response;
  }

  private static GrpcPeerIdentity peer(String service, String namespace) {
    return new GrpcPeerIdentity(
        "spiffe://firemud/ns/" + namespace + "/sa/" + service, namespace, service);
  }

  private static Fixture fixture(String namespace, String readRequestId) throws Exception {
    var lifecycleEvidence = lifecycleEvidence(namespace, readRequestId);
    var request = placementRequest(lifecycleEvidence, OPERATION_ID);
    return new Fixture(
        request,
        lifecycleEvidence,
        lifecycleEvidence.canonicalBytes(),
        lifecycleEvidence.request().readRequestId());
  }

  private static WorldCanonicalInitialPlayerLocation.Request placementRequest(
      WorldCanonicalInstanceLifecycleEvidence lifecycleEvidence, UUID operationId) {
    UUID tenant = lifecycleEvidence.request().canonicalTenantId();
    return new WorldCanonicalInitialPlayerLocation.Request(
        operationId,
        tenant,
        lifecycleEvidence.request().canonicalVersionId(),
        lifecycleEvidence.request().worldSlug(),
        lifecycleEvidence.request().canonicalGameInstanceId(),
        lifecycleEvidence.request().playableStateNamespaceId(),
        lifecycleEvidence.request().playableStateScope(),
        uuid("60000000-0000-4000-8000-000000000001"),
        uuid("70000000-0000-4000-8000-000000000001"),
        uuid("80000000-0000-4000-8000-000000000001"),
        "a".repeat(64),
        uuid("90000000-0000-4000-8000-000000000001"),
        uuid("a0000000-0000-4000-8000-000000000001"),
        "initial-request",
        "b".repeat(64),
        1,
        "owner-proof",
        "c".repeat(64),
        "pointer-audit",
        1,
        WorldCanonicalInitialPlayerLocation.InitialAdmissionOrigin.NO_PRIOR_POINTER,
        lifecycleEvidence);
  }

  private static WorldCanonicalInstanceLifecycleEvidence lifecycleEvidence(
      String namespace, String readRequestId) throws Exception {
    WorldPublishedStartLocationEvidence selector = publishedSelector(namespace);
    var descriptorRequest =
        new AuthoredWorldLaunchDescriptorEvidence.Request(
            selector.request().targetNamespace(),
            "world-lifecycle-control-request",
            selector.request().canonicalTenantId(),
            "synthetic-world",
            uuid("dddddddd-dddd-4ddd-8ddd-dddddddddddd"),
            "sha256:" + "a".repeat(64),
            19L,
            false,
            null,
            false,
            null,
            false,
            null,
            false,
            null);
    var descriptor =
        AuthoredWorldLaunchDescriptorEvidence.create(
            descriptorRequest,
            "canonical-instance-launch-descriptor",
            42L,
            false,
            null,
            "{}",
            "generation-revision",
            9L,
            7L,
            "release-bundle",
            false,
            null);
    List<AuthoredWorldReleaseAttestationEvidence.Participant> participants =
        AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder().stream()
            .map(
                owner ->
                    new AuthoredWorldReleaseAttestationEvidence.Participant(
                        owner,
                        Long.toString(descriptor.versionId()),
                        false,
                        null,
                        selector.request().appliedCommitId(),
                        selector.request().contentDigest(),
                        AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                            owner),
                        "GAME_LOGIC".equals(owner),
                        "GAME_LOGIC".equals(owner) ? "sha256:" + "c".repeat(64) : null))
            .toList();
    var release =
        AuthoredWorldReleaseAttestationEvidence.create(
            descriptor.targetNamespace(),
            descriptor.resultDigest(),
            descriptor.canonicalTenantId(),
            selector.request().canonicalVersionId(),
            descriptor.worldSlug(),
            descriptor.authoredWorldSourceOperationId(),
            descriptor.authoredWorldSourceEvidenceDigest(),
            descriptor.launchDescriptorId(),
            descriptor.publishedReleaseBundleRef(),
            descriptor.versionStateEpoch(),
            selector.request().publishWorkflowId(),
            selector.request().appliedCommitId(),
            participants,
            "sha256:" + "d".repeat(64),
            1,
            List.of(),
            List.of(),
            List.of(),
            descriptor.generationConfigRevision(),
            selector);
    var binding = new CompleteLaunchBindingEvidence(descriptor, release);
    var selectorReceipt =
        WorldDraftStartLocationEvidence.fromStored(selector.selectorReceiptBytes());
    var request =
        new WorldCanonicalInstanceLifecycleEvidence.Request(
            WorldCanonicalInstanceLifecycleEvidence.Request.SCHEMA_VERSION,
            uuid(readRequestId),
            descriptor.targetNamespace(),
            descriptor.canonicalTenantId(),
            descriptor.worldSlug(),
            uuid("11111111-1111-4111-8111-111111111111"),
            uuid("22222222-2222-4222-8222-222222222222"),
            "SHARED",
            true,
            descriptor.controlPlaneRequestId(),
            release.canonicalVersionId(),
            descriptor.requestDigest(),
            descriptor.resultDigest(),
            release.evidenceDigest());
    return new WorldCanonicalInstanceLifecycleEvidence(
        request,
        binding,
        selectorReceipt.startLocation(),
        1042L,
        "ACTIVE",
        3L,
        0L,
        uuid("33333333-3333-4333-8333-333333333333"),
        selectorReceipt.graphDigest().substring("sha256:".length()),
        "sha256:" + "e".repeat(64),
        java.util.Map.of(
            uuid("11111111-1111-4111-8111-111111111111"),
            uuid("22222222-2222-4222-8222-222222222222")));
  }

  private static WorldPublishedStartLocationEvidence publishedSelector(String namespace)
      throws Exception {
    DraftCommitBinding draft = freshGraphBinding();
    byte[] accountBytes = accountBinding(draft);
    var terminalRequest =
        new WorldDraftTerminalReadEvidence.Request(
            1, namespace, uuid("33333333-3333-4333-8333-333333333333"), accountBytes);
    var account = terminalRequest.accountBinding();
    List<WorldPublishedStartLocationEvidence.OwnedAffectedTuple> tuples =
        draft.affectedUnits(DraftCommitBinding.Owner.WORLD_MANAGEMENT).stream()
            .map(
                unit ->
                    new WorldPublishedStartLocationEvidence.OwnedAffectedTuple(
                        unit.owner().name(),
                        unit.aggregateType(),
                        unit.aggregateId(),
                        unit.scopeType(),
                        unit.scopeId(),
                        unit.expectedEpoch()))
            .toList();
    var selectorRequest =
        new WorldPublishedStartLocationEvidence.Request(
            namespace,
            account.tenantId(),
            account.versionId(),
            uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
            uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
            "publication-request",
            "a".repeat(64),
            5L,
            "publish-workflow",
            account.commitId().toString(),
            "b".repeat(64),
            3,
            tuples);
    byte[] appliedBytes = appliedResult(terminalRequest, draft);
    byte[] receiptBytes = receiptBytes(terminalRequest, draft, appliedBytes);
    // Construction validates the complete original Account binding, APPLIED receipt, operation
    // identity, exact World affected set, and selector bytes.
    return new WorldPublishedStartLocationEvidence(
        selectorRequest, receiptBytes, accountBytes, appliedBytes);
  }

  private static DraftCommitBinding freshGraphBinding() throws Exception {
    String declaration =
        JSON.writeValueAsString(
            Map.of(
                "tenantId",
                "11111111-1111-4111-8111-111111111111",
                "versionId",
                "22222222-2222-4222-8222-222222222222",
                "startLocation",
                Map.of(
                    "tenantId", "11111111-1111-4111-8111-111111111111",
                    "versionId", "22222222-2222-4222-8222-222222222222",
                    "roomTemplateId", "77777777-7777-4777-8777-777777777777"),
                "familyCounts",
                List.of(
                    Map.of("family", "WORLD_DESIGN_AGGREGATE_TYPE_REGION", "count", 1),
                    Map.of("family", "WORLD_DESIGN_AGGREGATE_TYPE_ZONE", "count", 1),
                    Map.of("family", "WORLD_DESIGN_AGGREGATE_TYPE_ROOM", "count", 1),
                    Map.of("family", "WORLD_DESIGN_AGGREGATE_TYPE_ROOM_EXIT", "count", 0),
                    Map.of("family", "WORLD_DESIGN_AGGREGATE_TYPE_GENERATION_RULE", "count", 0),
                    Map.of(
                        "family",
                        "WORLD_DESIGN_AGGREGATE_TYPE_WORLD_ENTITY_SPAWN_BINDING",
                        "count",
                        0))));
    UUID tenantId = uuid("11111111-1111-4111-8111-111111111111");
    UUID versionId = uuid("22222222-2222-4222-8222-222222222222");
    UUID commitId = uuid("55555555-5555-4555-8555-555555555555");
    UUID regionId = uuid("88888888-8888-4888-8888-888888888888");
    UUID zoneId = uuid("99999999-9999-4999-8999-999999999999");
    UUID roomId = uuid("77777777-7777-4777-8777-777777777777");
    UUID regionRevision = uuid("12345678-1234-4234-8234-123456789001");
    UUID zoneRevision = uuid("12345678-1234-4234-8234-123456789002");
    UUID roomRevision = uuid("12345678-1234-4234-8234-123456789003");
    return DraftCommitBinding.create(
        new TargetProof(tenantId, versionId, 19L, "tenant-key", 42L, "tenant-key", "NEW_GAME_ROW"),
        uuid("44444444-4444-4444-8444-444444444444"),
        commitId,
        "base-1",
        List.of(
            new RevisionPayload(
                "0",
                regionRevision,
                DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                worldRevisionPayload(
                    regionRevision,
                    commitId,
                    "WORLD_DESIGN_AGGREGATE_TYPE_REGION",
                    regionId,
                    declaration)),
            new RevisionPayload(
                "1",
                zoneRevision,
                DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                worldRevisionPayload(
                    zoneRevision, commitId, "WORLD_DESIGN_AGGREGATE_TYPE_ZONE", zoneId, null)),
            new RevisionPayload(
                "2",
                roomRevision,
                DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                worldRevisionPayload(
                    roomRevision, commitId, "WORLD_DESIGN_AGGREGATE_TYPE_ROOM", roomId, null))),
        List.of(
                affected("REGION", regionId, "REGION_SUBTREE", regionId),
                affected("ZONE", zoneId, "REGION_SUBTREE", regionId),
                affected("ROOM", roomId, "ZONE_SUBTREE", zoneId))
            .stream()
            .flatMap(List::stream)
            .toList());
  }

  private static String worldRevisionPayload(
      UUID revisionId, UUID commitId, String family, UUID templateId, String declaration)
      throws Exception {
    var payload = new java.util.LinkedHashMap<String, Object>();
    payload.put("logicalRevisionId", revisionId.toString());
    payload.put("commitId", commitId.toString());
    payload.put("aggregateType", family);
    payload.put("aggregateId", templateId.toString());
    if (declaration != null) payload.put("freshGraphDeclaration", JSON.readTree(declaration));
    return JSON.writeValueAsString(payload);
  }

  private static List<AffectedUnit> affected(
      String family, UUID templateId, String scopeType, UUID scopeId) {
    return List.of(
        new AffectedUnit(
            DraftCommitBinding.Owner.WORLD_MANAGEMENT,
            family,
            templateId.toString(),
            "AGGREGATE",
            templateId.toString(),
            "0"),
        new AffectedUnit(
            DraftCommitBinding.Owner.WORLD_MANAGEMENT,
            family,
            templateId.toString(),
            scopeType,
            scopeId.toString(),
            "0"));
  }

  private static byte[] accountBinding(DraftCommitBinding draft) {
    byte[] draftBytes = draft.canonicalBytes();
    return new DraftAuthorizationFenceBinding(
            uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
            uuid("44444444-4444-4444-8444-444444444444"),
            uuid("55555555-5555-4555-8555-555555555555"),
            uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
            uuid("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
            uuid("11111111-1111-4111-8111-111111111111"),
            uuid("22222222-2222-4222-8222-222222222222"),
            "base-1",
            "0",
            draftBytes,
            draftBytes,
            draft.digest(),
            List.of(
                new SourceEvidence(
                    SourceKind.GLOBAL_ROLES,
                    "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
                    null,
                    "1",
                    null,
                    null,
                    new byte[] {4, 5})))
        .canonicalBytes();
  }

  private static byte[] appliedResult(
      WorldDraftTerminalReadEvidence.Request request, DraftCommitBinding draft) throws Exception {
    var account = request.accountBinding();
    var operation = new ByteArrayOutputStream();
    var frames = new java.io.DataOutputStream(operation);
    java.util.function.Consumer<byte[]> frame =
        bytes -> {
          try {
            frames.writeInt(bytes.length);
            frames.write(bytes);
          } catch (java.io.IOException impossible) {
            throw new AssertionError(impossible);
          }
        };
    java.util.function.Consumer<String> text =
        value -> frame.accept(value.getBytes(StandardCharsets.UTF_8));
    text.accept("world-draft-terminal-operation/v1");
    for (UUID id :
        List.of(
            account.operationId(),
            account.requestId(),
            account.commitId(),
            account.fenceId(),
            account.tenantId(),
            account.versionId())) text.accept(id.toString());
    frame.accept(draft.canonicalBytes());
    for (String value :
        List.of(
            request.targetNamespace(),
            account.tenantId().toString(),
            account.versionId().toString(),
            "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            Long.toString(draft.target().gameDesignVersionRowId()),
            "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
            "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
            "a".repeat(64),
            "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
            "b".repeat(64),
            "c".repeat(64))) text.accept(value);
    text.accept(sha256(account.canonicalBytes()));
    frame.accept(account.canonicalBytes());
    byte[] graph = graphBytes(request, draft);
    String graphDigest = sha256(graph);
    var result = new java.util.LinkedHashMap<String, Object>();
    result.put("schema", "world-draft-graph-applied/v2");
    result.put("status", "APPLIED");
    result.put("operationBytesBase64", Base64.getEncoder().encodeToString(operation.toByteArray()));
    result.put("graphBytesBase64", Base64.getEncoder().encodeToString(graph));
    result.put("graphDigest", graphDigest);
    var receipt = receiptJson(request, draft, graphDigest);
    byte[] receiptBytes = canonical(receipt);
    result.put("startLocationReceiptBase64", Base64.getEncoder().encodeToString(receiptBytes));
    result.put("startLocationReceiptDigest", receipt.get("receiptDigest").textValue());
    result.put(
        "appliedEpochs",
        draft.affectedUnits(DraftCommitBinding.Owner.WORLD_MANAGEMENT).stream()
            .map(
                unit ->
                    Map.of(
                        "aggregateType", unit.aggregateType(),
                        "aggregateId", unit.aggregateId(),
                        "scopeType", unit.scopeType(),
                        "scopeId", unit.scopeId(),
                        "expectedEpoch", unit.expectedEpoch(),
                        "resultingEpoch",
                            new java.math.BigInteger(unit.expectedEpoch())
                                .add(java.math.BigInteger.ONE)
                                .toString()))
            .toList());
    byte[] resultBytes = canonical(JSON.valueToTree(result));
    var readback =
        new DraftAuthorizationFenceBinding.OwnerReadback(
            Owner.WORLD,
            Outcome.COMMITTED,
            account.operationId(),
            account.commitId(),
            account.fenceId(),
            account.inputDigest(),
            account.canonicalBytes(),
            resultBytes);
    net.firedevops.firemud.common.authoring.WorldDraftTerminalReadGrpcCodec.fromResponse(
        request,
        net.firedevops.firemud.common.authoring.WorldDraftTerminalReadGrpcCodec.toResponse(
            request, Optional.of(readback)));
    return resultBytes;
  }

  private static byte[] graphBytes(
      WorldDraftTerminalReadEvidence.Request request, DraftCommitBinding draft) throws Exception {
    var root = new java.util.LinkedHashMap<String, Object>();
    root.put("schemaVersion", "2");
    root.put("canonicalTenantId", draft.target().canonicalTenantId().toString());
    root.put("canonicalVersionId", draft.target().canonicalVersionId().toString());
    var rows = new java.util.ArrayList<Map<String, Object>>();
    int mappingId = 1;
    long privateRowKey = 101;
    for (RevisionPayload revision : draft.revisions()) {
      if (revision.owner() != DraftCommitBinding.Owner.WORLD_MANAGEMENT) continue;
      var mutation = JSON.readTree(revision.payload());
      String family =
          mutation.get("aggregateType").textValue().replace("WORLD_DESIGN_AGGREGATE_TYPE_", "");
      var mapping = new java.util.LinkedHashMap<String, Object>();
      mapping.put("id", mappingId++);
      mapping.put("target_namespace", request.targetNamespace());
      mapping.put("canonical_tenant_id", draft.target().canonicalTenantId().toString());
      mapping.put("canonical_version_id", draft.target().canonicalVersionId().toString());
      mapping.put("family", family);
      mapping.put("template_id", mutation.get("aggregateId").textValue());
      mapping.put("private_row_key", privateRowKey++);
      mapping.put("tenant_id", 11);
      mapping.put("version_id", 19);
      mapping.put(
          "version_identity_operation_id", uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa").toString());
      mapping.put("request_id", draft.requestId().toString());
      mapping.put("commit_id", draft.commitId().toString());
      mapping.put("revision_id", revision.revisionId().toString());
      mapping.put("revision_order", revision.revisionOrder());
      rows.add(Map.of("mapping", mapping, "content", Map.of()));
    }
    root.put("rows", rows);
    return JSON.writeValueAsBytes(root);
  }

  private static byte[] receiptBytes(
      WorldDraftTerminalReadEvidence.Request request, DraftCommitBinding draft, byte[] appliedBytes)
      throws Exception {
    var applied = JSON.readTree(appliedBytes);
    return Base64.getDecoder().decode(applied.get("startLocationReceiptBase64").textValue());
  }

  private static tools.jackson.databind.node.ObjectNode receiptJson(
      WorldDraftTerminalReadEvidence.Request request, DraftCommitBinding draft, String graphDigest)
      throws Exception {
    var account = request.accountBinding();
    UUID tenant = uuid("11111111-1111-4111-8111-111111111111");
    UUID version = uuid("22222222-2222-4222-8222-222222222222");
    UUID room = uuid("77777777-7777-4777-8777-777777777777");
    String accountDigest = sha256(request.originalAccountBinding());
    String digest =
        startLocationReceiptDigest(
            request.targetNamespace(),
            account,
            accountDigest,
            draft.digest(),
            tenant,
            version,
            room,
            graphDigest);
    var value = new java.util.LinkedHashMap<String, Object>();
    value.put("schema", "world-draft-start-location-receipt/v1");
    value.put("targetNamespace", request.targetNamespace());
    value.put("operationId", account.operationId().toString());
    value.put("requestId", account.requestId().toString());
    value.put("commitId", account.commitId().toString());
    value.put("authorizationFenceId", account.fenceId().toString());
    value.put("accountBindingDigest", accountDigest);
    value.put("bindingDigest", draft.digest());
    value.put(
        "startLocation",
        Map.of(
            "tenantId",
            tenant.toString(),
            "versionId",
            version.toString(),
            "roomTemplateId",
            room.toString()));
    value.put("graphDigest", graphDigest);
    value.put("receiptDigest", digest);
    return (tools.jackson.databind.node.ObjectNode) JSON.valueToTree(value);
  }

  private static String startLocationReceiptDigest(
      String targetNamespace,
      DraftAuthorizationFenceBinding account,
      String accountBindingDigest,
      String bindingDigest,
      UUID tenantId,
      UUID versionId,
      UUID roomTemplateId,
      String graphDigest)
      throws Exception {
    var framed = new ByteArrayOutputStream();
    for (String value :
        List.of(
            "world-draft-start-location-receipt/v1",
            targetNamespace,
            account.operationId().toString(),
            account.requestId().toString(),
            account.commitId().toString(),
            account.fenceId().toString(),
            accountBindingDigest,
            bindingDigest,
            tenantId.toString(),
            versionId.toString(),
            roomTemplateId.toString(),
            graphDigest)) {
      byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
      framed.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
      framed.writeBytes(bytes);
    }
    return sha256(framed.toByteArray());
  }

  private static String sha256(byte[] bytes) throws Exception {
    return "sha256:"
        + java.util.HexFormat.of()
            .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private static byte[] canonical(tools.jackson.databind.JsonNode value) throws Exception {
    return net.firedevops.firemud.common.json.Rfc8785CanonicalJson.canonicalizeUtf8(
        JSON.writeValueAsString(value));
  }

  private static final tools.jackson.databind.ObjectMapper JSON =
      new tools.jackson.databind.ObjectMapper();

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private record Fixture(
      WorldCanonicalInitialPlayerLocation.Request request,
      WorldCanonicalInstanceLifecycleEvidence lifecycleEvidence,
      byte[] originalLifecycleBytes,
      UUID readRequestId) {}

  private static final class Collector<T> implements StreamObserver<T> {
    private T value;
    private Status.Code error;
    private String description;
    private boolean completed;

    @Override
    public void onNext(T response) {
      value = response;
    }

    @Override
    public void onError(Throwable failure) {
      assertThat(value).isNull();
      assertThat(completed).isFalse();
      Status status = Status.fromThrowable(failure);
      error = status.getCode();
      description = status.getDescription();
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
