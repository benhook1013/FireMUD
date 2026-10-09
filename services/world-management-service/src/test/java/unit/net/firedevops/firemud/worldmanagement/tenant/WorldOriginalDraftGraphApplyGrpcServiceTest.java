package unit.net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.WorldOriginalDraftGraphApplyEvidence;
import net.firedevops.firemud.common.authoring.WorldOriginalDraftGraphApplyGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftGraphAppliedResult;
import net.firedevops.firemud.worldmanagement.tenant.WorldOriginalDraftGraphApplicationService;
import net.firedevops.firemud.worldmanagement.tenant.WorldOriginalDraftGraphApplyGrpcService;
import net.firedevops.firemud.worldmanagement.v1.ApplyOriginalDraftGraphRequest;
import net.firedevops.firemud.worldmanagement.v1.ApplyOriginalDraftGraphResponse;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Guard/dispatch structural proof only; authentic source/persistence is the owner's separate proof.
 */
class WorldOriginalDraftGraphApplyGrpcServiceTest {
  @Test
  void deniesMissingOtherAccountAndWrongNamespacePeersBeforeDecodingOrOwnerAccess() {
    var owner = mock(WorldOriginalDraftGraphApplicationService.class);
    var grpc = new WorldOriginalDraftGraphApplyGrpcService(owner, "test");
    var malformed = ApplyOriginalDraftGraphRequest.newBuilder().setSchemaVersion(-1).build();
    for (var service :
        List.of("account-service", "world-management-service", "game-session-service")) {
      var response = call(grpc, malformed, peer(service, "test"));
      assertThat(response.code()).isEqualTo(Status.Code.PERMISSION_DENIED);
    }
    assertThat(call(grpc, malformed, null).code()).isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(call(grpc, malformed, peer("game-design-service", "other")).code())
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(owner);
  }

  @Test
  void rejectsPlayerContextEvenWithCorrectWorkloadBeforeDecode() {
    var owner = mock(WorldOriginalDraftGraphApplicationService.class);
    var grpc = new WorldOriginalDraftGraphApplyGrpcService(owner, "test");
    SessionContext.setContext("123", List.of(), Map.of());
    try {
      var response =
          call(
              grpc,
              ApplyOriginalDraftGraphRequest.getDefaultInstance(),
              peer("game-design-service", "test"));
      assertThat(response.code()).isEqualTo(Status.Code.PERMISSION_DENIED);
      verifyNoInteractions(owner);
    } finally {
      SessionContext.clear();
    }
  }

  @Test
  void sameNamespaceGameDesignAuthenticatesButInvalidUnknownAndUnsupportedInputsNeverInvokeOwner() {
    var owner = mock(WorldOriginalDraftGraphApplicationService.class);
    var grpc = new WorldOriginalDraftGraphApplyGrpcService(owner, "test");
    var wire = WorldOriginalDraftGraphApplyGrpcCodec.toRequest(request("test"));
    var unknown =
        com.google.protobuf.UnknownFieldSet.newBuilder()
            .addField(
                99, com.google.protobuf.UnknownFieldSet.Field.newBuilder().addVarint(1).build())
            .build();
    for (var invalid :
        List.of(
            ApplyOriginalDraftGraphRequest.getDefaultInstance(),
            wire.toBuilder().setUnknownFields(unknown).build(),
            wire.toBuilder()
                .setOriginalAccountBinding(com.google.protobuf.ByteString.copyFrom(binding(false)))
                .build())) {
      var response = call(grpc, invalid, peer("game-design-service", "test"));
      assertThat(response.code()).isEqualTo(Status.Code.INVALID_ARGUMENT);
      assertThat(response.value).isNull();
      assertThat(response.completed).isFalse();
    }
    assertThat(
            call(
                    grpc,
                    WorldOriginalDraftGraphApplyGrpcCodec.toRequest(request("other")),
                    peer("game-design-service", "test"))
                .code())
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(owner);
  }

  @Test
  void passesOnlyExactOriginalBytesAndPropagatesStorageFailureWithoutTerminalAbort() {
    var owner = mock(WorldOriginalDraftGraphApplicationService.class);
    var request = request("test");
    when(owner.apply(eq("test"), any(byte[].class)))
        .thenThrow(new org.jooq.exception.DataAccessException("lost database connection"));
    var grpc = new WorldOriginalDraftGraphApplyGrpcService(owner, "test");
    var response =
        call(
            grpc,
            WorldOriginalDraftGraphApplyGrpcCodec.toRequest(request),
            peer("game-design-service", "test"));
    verify(owner).apply("test", request.originalAccountBinding());
    assertThat(response.code()).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(response.value).isNull();
    assertThat(response.completed).isFalse();
  }

  @Test
  void deniesOwnerConflictAndMalformedReadbackRatherThanEmittingSuccess() {
    var owner = mock(WorldOriginalDraftGraphApplicationService.class);
    var request = request("test");
    when(owner.apply(eq("test"), any(byte[].class)))
        .thenThrow(new IllegalStateException("retained operation conflict"));
    var grpc = new WorldOriginalDraftGraphApplyGrpcService(owner, "test");
    assertThat(
            call(
                    grpc,
                    WorldOriginalDraftGraphApplyGrpcCodec.toRequest(request),
                    peer("game-design-service", "test"))
                .code())
        .isEqualTo(Status.Code.FAILED_PRECONDITION);
    var result = mock(WorldDraftGraphAppliedResult.class);
    var account = request.accountBinding();
    when(result.ownerReadback())
        .thenReturn(
            new DraftAuthorizationFenceBinding.OwnerReadback(
                DraftAuthorizationFenceBinding.Owner.WORLD,
                DraftAuthorizationFenceBinding.Outcome.COMMITTED,
                account.operationId(),
                account.commitId(),
                account.fenceId(),
                account.inputDigest(),
                account.canonicalBytes(),
                new byte[] {1}));
    when(owner.apply(eq("test"), any(byte[].class))).thenReturn(result);
    var malformed =
        call(
            grpc,
            WorldOriginalDraftGraphApplyGrpcCodec.toRequest(request),
            peer("game-design-service", "test"));
    assertThat(malformed.code()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(malformed.value).isNull();
    assertThat(malformed.completed).isFalse();
  }

  @Test
  void unexpectedOwnerFailureIsDeniedAndLogsOnlyFailureTypesAndFrameLocations() {
    var owner = mock(WorldOriginalDraftGraphApplicationService.class);
    var request = request("test");
    String outerMessage = "creatorCredential=outer-secret";
    String nestedMessage = "sqlParameter=nested-secret";
    var failure =
        new IllegalStateException(outerMessage, new IllegalArgumentException(nestedMessage));
    when(owner.apply(eq("test"), any(byte[].class))).thenThrow(failure);
    var grpc = new WorldOriginalDraftGraphApplyGrpcService(owner, "test");
    Logger logger = (Logger) LoggerFactory.getLogger(WorldOriginalDraftGraphApplyGrpcService.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      var response =
          call(
              grpc,
              WorldOriginalDraftGraphApplyGrpcCodec.toRequest(request),
              peer("game-design-service", "test"));
      assertThat(response.code()).isEqualTo(Status.Code.FAILED_PRECONDITION);
      assertThat(response.value).isNull();
      assertThat(response.completed).isFalse();

      assertThat(appender.list).hasSize(1);
      var event = appender.list.getFirst();
      assertThat(event.getLevel()).isEqualTo(Level.ERROR);
      assertThat(event.getFormattedMessage())
          .contains("failureType=java.lang.IllegalStateException")
          .contains("WorldOriginalDraftGraphApplyGrpcServiceTest.java:")
          .contains("cause java.lang.IllegalArgumentException")
          .doesNotContain(outerMessage, nestedMessage);
      assertThat(event.getThrowableProxy()).isNull();
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
    verify(owner).apply("test", request.originalAccountBinding());
  }

  private static GrpcPeerIdentity peer(String service, String namespace) {
    return new GrpcPeerIdentity(
        "spiffe://firemud/ns/" + namespace + "/sa/" + service, namespace, service);
  }

  private static Collector call(
      WorldOriginalDraftGraphApplyGrpcService grpc,
      ApplyOriginalDraftGraphRequest wire,
      GrpcPeerIdentity peer) {
    var context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    var previous = context.attach();
    var response = new Collector();
    try {
      grpc.applyOriginalDraftGraph(wire, response);
    } finally {
      context.detach(previous);
    }
    return response;
  }

  static WorldOriginalDraftGraphApplyEvidence.Request request(String namespace) {
    return WorldOriginalDraftGraphApplyEvidence.Request.create(namespace, binding(true));
  }

  static byte[] binding(boolean includeGameDesign) {
    var tenant = UUID.fromString("11111111-1111-4111-8111-111111111111");
    var version = UUID.fromString("22222222-2222-4222-8222-222222222222");
    var request = UUID.fromString("44444444-4444-4444-8444-444444444444");
    var commit = UUID.fromString("55555555-5555-4555-8555-555555555555");
    var revisions = new java.util.ArrayList<DraftCommitBinding.RevisionPayload>();
    revisions.add(
        new DraftCommitBinding.RevisionPayload(
            "0",
            UUID.fromString("66666666-6666-4666-8666-666666666666"),
            DraftCommitBinding.Owner.WORLD_MANAGEMENT,
            "{}"));
    var units = new java.util.ArrayList<DraftCommitBinding.AffectedUnit>();
    units.add(
        new DraftCommitBinding.AffectedUnit(
            DraftCommitBinding.Owner.WORLD_MANAGEMENT,
            "WORLD_TEMPLATE",
            "world-1",
            "ROOM_SCOPE",
            "room-1",
            "7"));
    units.add(
        new DraftCommitBinding.AffectedUnit(
            DraftCommitBinding.Owner.WORLD_MANAGEMENT,
            "WORLD_TEMPLATE",
            "world-1",
            "ZONE_SCOPE",
            "zone-1",
            "19"));
    if (includeGameDesign) {
      revisions.add(
          new DraftCommitBinding.RevisionPayload(
              "1",
              UUID.fromString("77777777-7777-4777-8777-777777777777"),
              DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
              "{}"));
      units.add(
          new DraftCommitBinding.AffectedUnit(
              DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
              "DESIGN",
              "design-1",
              "REVISION",
              "revision-1",
              "0"));
    }
    var draft =
        DraftCommitBinding.create(
            new DraftCommitBinding.TargetProof(
                tenant, version, 19L, "tenant-key", 42L, "tenant-key", "NEW_GAME_ROW"),
            request,
            commit,
            "base-1",
            revisions,
            units);
    return new DraftAuthorizationFenceBinding(
            UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
            request,
            commit,
            UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
            UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
            tenant,
            version,
            "base-1",
            "0",
            draft.canonicalBytes(),
            draft.canonicalBytes(),
            draft.digest(),
            java.util.List.of(
                new DraftAuthorizationFenceBinding.SourceEvidence(
                    DraftAuthorizationFenceBinding.SourceKind.GLOBAL_ROLES,
                    "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
                    null,
                    "1",
                    null,
                    null,
                    new byte[] {1})))
        .canonicalBytes();
  }

  private static final class Collector implements StreamObserver<ApplyOriginalDraftGraphResponse> {
    private ApplyOriginalDraftGraphResponse value;
    private Status.Code errorCode = Status.Code.OK;
    private boolean completed;

    Status.Code code() {
      return errorCode;
    }

    @Override
    public void onNext(ApplyOriginalDraftGraphResponse response) {
      value = response;
    }

    @Override
    public void onError(Throwable failure) {
      errorCode = Status.fromThrowable(failure).getCode();
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
