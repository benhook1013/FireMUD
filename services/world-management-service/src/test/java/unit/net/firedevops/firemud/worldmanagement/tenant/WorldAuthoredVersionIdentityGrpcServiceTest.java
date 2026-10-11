package unit.net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.WorldAuthoredVersionIdentityEvidence;
import net.firedevops.firemud.common.authoring.WorldAuthoredVersionIdentityGrpcCodec;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeDigest;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeReceipt;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredVersionIdentityGrpcService;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredVersionIdentityReceipt;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredVersionIdentityService;
import net.firedevops.firemud.worldmanagement.v1.AssociateAuthoredWorldVersionRequest;
import net.firedevops.firemud.worldmanagement.v1.AssociateAuthoredWorldVersionResponse;
import org.junit.jupiter.api.Test;

/** Authentication and dispatch proof only; physical owner persistence is separate. */
class WorldAuthoredVersionIdentityGrpcServiceTest {
  @Test
  void deniesWrongMissingAndOtherNamespacePeersBeforeDecodingOrOwnerAccess() {
    var owner = mock(WorldAuthoredVersionIdentityService.class);
    var grpc = new WorldAuthoredVersionIdentityGrpcService(owner, "test");
    var malformed = AssociateAuthoredWorldVersionRequest.getDefaultInstance();
    for (var service :
        List.of("account-service", "world-management-service", "game-session-service")) {
      assertThat(call(grpc, malformed, peer(service, "test")).code)
          .isEqualTo(Status.Code.PERMISSION_DENIED);
    }
    assertThat(call(grpc, malformed, null).code).isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(call(grpc, malformed, peer("game-design-service", "other")).code)
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    SessionContext.setContext("123", List.of(), Map.of());
    try {
      assertThat(call(grpc, malformed, peer("game-design-service", "test")).code)
          .isEqualTo(Status.Code.PERMISSION_DENIED);
    } finally {
      SessionContext.clear();
    }
    verifyNoInteractions(owner);
  }

  @Test
  void rejectsUnknownAndInvalidRequestsAndWrongTargetNamespaceWithoutOwnerAccess() {
    var owner = mock(WorldAuthoredVersionIdentityService.class);
    var grpc = new WorldAuthoredVersionIdentityGrpcService(owner, "test");
    var wire = WorldAuthoredVersionIdentityGrpcCodec.toRequest(request("test", id(5)));
    var unknown =
        com.google.protobuf.UnknownFieldSet.newBuilder()
            .addField(
                99, com.google.protobuf.UnknownFieldSet.Field.newBuilder().addVarint(1).build())
            .build();
    for (var invalid :
        List.of(
            AssociateAuthoredWorldVersionRequest.getDefaultInstance(),
            wire.toBuilder().setUnknownFields(unknown).build(),
            wire.toBuilder().setGameDesignVersionId(0).build())) {
      assertThat(call(grpc, invalid, peer("game-design-service", "test")).code)
          .isEqualTo(Status.Code.INVALID_ARGUMENT);
    }
    assertThat(
            call(
                    grpc,
                    WorldAuthoredVersionIdentityGrpcCodec.toRequest(request("other", id(5))),
                    peer("game-design-service", "test"))
                .code)
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(owner);
  }

  @Test
  void dispatchesExactAssociationParametersAndReturnsOriginalCommittedEvidenceOnRetry() {
    var owner = mock(WorldAuthoredVersionIdentityService.class);
    var grpc = new WorldAuthoredVersionIdentityGrpcService(owner, "test");
    var original = request("test", id(5));
    var retained = receipt(original);
    var retry = request("test", id(9));
    when(owner.associate(
            "test", id(1), "north-star", id(3), retry.sourceEvidenceDigest(), id(4), 42L, id(9)))
        .thenReturn(retained);
    var response =
        call(
            grpc,
            WorldAuthoredVersionIdentityGrpcCodec.toRequest(retry),
            peer("game-design-service", "test"));
    assertThat(response.code).isEqualTo(Status.Code.OK);
    assertThat(response.completed).isTrue();
    var result = WorldAuthoredVersionIdentityGrpcCodec.fromResponse(retry, response.value);
    assertThat(result.operationId()).isEqualTo(retained.operationId());
    assertThat(result.versionStateEvidence()).isEqualTo(retained.versionStateEvidence());
    assertThat(result.versionStateEvidence().request().readRequestId()).isEqualTo(id(5));
    verify(owner)
        .associate(
            "test", id(1), "north-star", id(3), retry.sourceEvidenceDigest(), id(4), 42L, id(9));
  }

  @Test
  void storageFailureAndOwnerConflictProduceNoSuccessfulEvidence() {
    var owner = mock(WorldAuthoredVersionIdentityService.class);
    var grpc = new WorldAuthoredVersionIdentityGrpcService(owner, "test");
    var request = request("test", id(5));
    when(owner.associate(
            "test", id(1), "north-star", id(3), request.sourceEvidenceDigest(), id(4), 42L, id(5)))
        .thenThrow(new org.jooq.exception.DataAccessException("storage unavailable"))
        .thenThrow(new IllegalStateException("conflicting identity"));
    for (var code : List.of(Status.Code.UNAVAILABLE, Status.Code.FAILED_PRECONDITION)) {
      var response =
          call(
              grpc,
              WorldAuthoredVersionIdentityGrpcCodec.toRequest(request),
              peer("game-design-service", "test"));
      assertThat(response.code).isEqualTo(code);
      assertThat(response.value).isNull();
      assertThat(response.completed).isFalse();
    }
  }

  private static WorldAuthoredVersionIdentityEvidence.Request request(String namespace, UUID read) {
    var source = source(namespace);
    return new WorldAuthoredVersionIdentityEvidence.Request(
        1, namespace, id(1), "north-star", id(3), source.evidenceDigest(), id(4), 42L, read);
  }

  private static WorldAuthoredVersionIdentityReceipt receipt(
      WorldAuthoredVersionIdentityEvidence.Request request) {
    var source = source(request.targetNamespace());
    var requestDigest = WorldAuthoredSourceIntakeDigest.requestDigest("test", id(6), source);
    var intake =
        new WorldAuthoredSourceIntakeReceipt(
            1,
            "test",
            id(6),
            id(7),
            id(1),
            "north-star",
            id(3),
            source.evidenceDigest(),
            requestDigest,
            WorldAuthoredSourceIntakeDigest.receiptDigest(
                "test", id(7), requestDigest, source, 900L),
            900L,
            source);
    var version =
        AuthoredWorldVersionStateEvidence.create(
            request.versionReadRequest(),
            source,
            id(4),
            VersionLifecycleState.VERSION_LIFECYCLE_STATE_DRAFT,
            1L);
    return new WorldAuthoredVersionIdentityReceipt(1, id(8), 901L, intake, version);
  }

  private static AuthoredWorldSourceEvidence source(String namespace) {
    var digest =
        AuthoredWorldSourceDigest.requestDigest(
            namespace, id(2), id(1), "north-star", "north-star", "North Star");
    return new AuthoredWorldSourceEvidence(
        1,
        namespace,
        id(2),
        id(3),
        digest,
        id(1),
        "north-star",
        "north-star",
        "North Star",
        42L,
        "game-tenant-42",
        "NEW_GAME_ROW",
        AuthoredWorldSourceDigest.evidenceDigest(
            namespace,
            id(2),
            id(3),
            digest,
            id(1),
            "north-star",
            "north-star",
            "North Star",
            42L,
            "game-tenant-42",
            "NEW_GAME_ROW"));
  }

  private static UUID id(int suffix) {
    return UUID.fromString("11111111-1111-4111-8111-" + String.format("%012d", suffix));
  }

  private static GrpcPeerIdentity peer(String service, String namespace) {
    return new GrpcPeerIdentity(
        "spiffe://firemud/ns/" + namespace + "/sa/" + service, namespace, service);
  }

  private static Collector call(
      WorldAuthoredVersionIdentityGrpcService grpc,
      AssociateAuthoredWorldVersionRequest wire,
      GrpcPeerIdentity peer) {
    var context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    var previous = context.attach();
    var result = new Collector();
    try {
      grpc.associateAuthoredWorldVersion(wire, result);
    } finally {
      context.detach(previous);
    }
    return result;
  }

  private static final class Collector
      implements StreamObserver<AssociateAuthoredWorldVersionResponse> {
    private AssociateAuthoredWorldVersionResponse value;
    private Status.Code code = Status.Code.OK;
    private boolean completed;

    @Override
    public void onNext(AssociateAuthoredWorldVersionResponse response) {
      value = response;
    }

    @Override
    public void onError(Throwable failure) {
      code = Status.fromThrowable(failure).getCode();
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
