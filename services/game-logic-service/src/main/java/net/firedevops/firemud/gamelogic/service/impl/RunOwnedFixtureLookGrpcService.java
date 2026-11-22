package net.firedevops.firemud.gamelogic.service.impl;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.prev1.PreV1GateWaivers;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleGrpcCodec;
import net.firedevops.firemud.entitymanagement.v1.ListRoomEntitiesResponse;
import net.firedevops.firemud.entitymanagement.v1.ReadRunOwnedFixtureGroundRequest;
import net.firedevops.firemud.entitymanagement.v1.RunOwnedFixtureLookEntityServiceGrpc.RunOwnedFixtureLookEntityServiceBlockingStub;
import net.firedevops.firemud.gamelogic.service.LookAggregationService;
import net.firedevops.firemud.gamelogic.v1.LookResult;
import net.firedevops.firemud.gamelogic.v1.ResolveRunOwnedFixtureLookRequest;
import net.firedevops.firemud.gamelogic.v1.ResolveRunOwnedFixtureLookResponse;
import net.firedevops.firemud.gamelogic.v1.RunOwnedFixtureLookServiceGrpc;
import net.firedevops.firemud.shared.v1.RoomInstanceRef;
import net.firedevops.firemud.worldmanagement.v1.RunOwnedFixtureLookWorldServiceGrpc.RunOwnedFixtureLookWorldServiceBlockingStub;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.core.env.Environment;
import org.springframework.grpc.server.service.GrpcService;

/** Game Logic owns the combined World and Entity view for an admitted fixture player. */
@GrpcService
@ConditionalOnExpression(
    "${FIREMUD_SMOKE_INITIAL_ADMISSION_FIXTURE_ENABLED:false}"
        + " and ${firemud.pre-v1.gate-waivers.enabled:false}")
public final class RunOwnedFixtureLookGrpcService
    extends RunOwnedFixtureLookServiceGrpc.RunOwnedFixtureLookServiceImplBase {
  private final RunOwnedFixtureLookWorldServiceBlockingStub world;
  private final RunOwnedFixtureLookEntityServiceBlockingStub entity;
  private final LookAggregationService look;
  private final String namespace;

  public RunOwnedFixtureLookGrpcService(
      RunOwnedFixtureLookWorldServiceBlockingStub world,
      RunOwnedFixtureLookEntityServiceBlockingStub entity,
      LookAggregationService look,
      Environment environment,
      @Value("${firemud.grpc.workload-namespace:}") String namespace) {
    if (!PreV1GateWaivers.from(environment).enabled()
        || !GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw new IllegalArgumentException("An enabled run-owned LOOK scope is required");
    }
    this.world = world;
    this.entity = entity;
    this.look = look;
    this.namespace = namespace;
  }

  @Override
  public void resolveRunOwnedFixtureLook(
      ResolveRunOwnedFixtureLookRequest wire,
      StreamObserver<ResolveRunOwnedFixtureLookResponse> observer) {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer == null) {
      observer.onError(Status.UNAUTHENTICATED.asRuntimeException());
      return;
    }
    if (!peer.isService("game-session-service")
        || !peer.isInNamespace(namespace)
        || SessionContext.hasAuthenticatedCallerContext()
        || SessionContext.isInternalService()) {
      observer.onError(Status.PERMISSION_DENIED.asRuntimeException());
      return;
    }
    LookResult view;
    try {
      if (!wire.hasRoom() || !wire.getUnknownFields().asMap().isEmpty()) {
        throw new IllegalArgumentException("The exact fixture room request is required");
      }
      var request = wire.getRoom();
      var selector = WorldCanonicalInstanceLifecycleGrpcCodec.fromRequest(request.getLifecycle());
      if (!request.getUnknownFields().asMap().isEmpty()
          || !namespace.equals(selector.targetNamespace())) {
        throw new IllegalArgumentException("The exact fixture World selector is required");
      }
      var first = world.withDeadlineAfter(2, TimeUnit.SECONDS).readRunOwnedFixtureLookRoom(request);
      var active =
          WorldCanonicalInstanceLifecycleGrpcCodec.fromFixtureResponse(
              selector, first.getLifecycle());
      var snapshot = first.getSnapshot();
      var room =
          RoomInstanceRef.newBuilder()
              .setTenantId(selector.canonicalTenantId().toString())
              .setGameInstanceId(selector.canonicalGameInstanceId().toString())
              .setRoomInstanceId(request.getExpectedRoomInstanceKey())
              .build();
      if (!"ACTIVE".equals(active.lifecycleStatus())
          || !first.hasSnapshot()
          || !room.getTenantId().equals(snapshot.getTenantId())
          || !room.getGameInstanceId().equals(snapshot.getGameInstanceId())
          || !room.getRoomInstanceId().equals(snapshot.getRoomInstanceId())) {
        throw Status.FAILED_PRECONDITION.asRuntimeException();
      }
      var ground =
          entity
              .withDeadlineAfter(2, TimeUnit.SECONDS)
              .readRunOwnedFixtureGround(
                  ReadRunOwnedFixtureGroundRequest.newBuilder()
                      .setRoom(room)
                      .setWorldFixtureLifecycle(first.getLifecycle().getCanonicalResponseBytes())
                      .build());
      if (!ground.getEmptyGroundVerified() || !room.equals(ground.getRoom())) {
        throw Status.FAILED_PRECONDITION.asRuntimeException();
      }
      if (!first.equals(
          world.withDeadlineAfter(2, TimeUnit.SECONDS).readRunOwnedFixtureLookRoom(request))) {
        throw Status.FAILED_PRECONDITION.asRuntimeException();
      }
      view =
          look.project(
              snapshot,
              ListRoomEntitiesResponse.newBuilder()
                  .setEntitySnapshotId(ground.getEntitySnapshotId())
                  .build());
    } catch (StatusRuntimeException sourceFailure) {
      observer.onError(Status.fromCode(sourceFailure.getStatus().getCode()).asRuntimeException());
      return;
    } catch (IllegalArgumentException invalid) {
      observer.onError(Status.INVALID_ARGUMENT.asRuntimeException());
      return;
    } catch (RuntimeException unavailable) {
      observer.onError(Status.UNAVAILABLE.asRuntimeException());
      return;
    }
    observer.onNext(ResolveRunOwnedFixtureLookResponse.newBuilder().setLook(view).build());
    observer.onCompleted();
  }
}
