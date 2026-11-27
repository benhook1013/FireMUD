package net.firedevops.firemud.worldmanagement.service.impl;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleGrpcCodec;
import net.firedevops.firemud.worldmanagement.service.RoomService;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceLifecycleReadRepository;
import net.firedevops.firemud.worldmanagement.v1.ReadRunOwnedFixtureLookRoomRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadRunOwnedFixtureLookRoomResponse;
import net.firedevops.firemud.worldmanagement.v1.RunOwnedFixtureLookWorldServiceGrpc;

/** Reads actual World-owned topology and prose for the active run-owned fixture. */
public final class RunOwnedFixtureLookWorldGrpcService
    extends RunOwnedFixtureLookWorldServiceGrpc.RunOwnedFixtureLookWorldServiceImplBase {
  private final WorldCanonicalInstanceLifecycleReadRepository lifecycle;
  private final RoomService rooms;
  private final String namespace;

  public RunOwnedFixtureLookWorldGrpcService(
      WorldCanonicalInstanceLifecycleReadRepository lifecycle,
      RoomService rooms,
      String namespace) {
    this.lifecycle = Objects.requireNonNull(lifecycle);
    this.rooms = Objects.requireNonNull(rooms);
    if (!GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw new IllegalArgumentException("A World workload namespace is required");
    }
    this.namespace = namespace;
  }

  @Override
  public void readRunOwnedFixtureLookRoom(
      ReadRunOwnedFixtureLookRoomRequest wire,
      StreamObserver<ReadRunOwnedFixtureLookRoomResponse> observer) {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer == null) {
      observer.onError(Status.UNAUTHENTICATED.asRuntimeException());
      return;
    }
    if (!peer.isService("game-logic-service")
        || !peer.isInNamespace(namespace)
        || SessionContext.hasAuthenticatedCallerContext()
        || SessionContext.isInternalService()) {
      observer.onError(Status.PERMISSION_DENIED.asRuntimeException());
      return;
    }

    WorldCanonicalInstanceLifecycleEvidence.Request request;
    try {
      if (!wire.hasLifecycle()
          || !wire.getUnknownFields().asMap().isEmpty()
          || !wire.getExpectedRoomInstanceKey().matches("R-[1-9][0-9]{0,18}")
          || wire.getPreferredLocale().length() > 128) {
        throw new IllegalArgumentException("An exact fixture room selector is required");
      }
      request = WorldCanonicalInstanceLifecycleGrpcCodec.fromRequest(wire.getLifecycle());
    } catch (IllegalArgumentException invalid) {
      observer.onError(Status.INVALID_ARGUMENT.asRuntimeException());
      return;
    }
    if (!namespace.equals(request.targetNamespace())) {
      observer.onError(Status.PERMISSION_DENIED.asRuntimeException());
      return;
    }

    ReadRunOwnedFixtureLookRoomResponse response;
    try {
      var view =
          lifecycle.readRunOwnedFixtureStartRoomView(
              request, wire.getExpectedRoomInstanceKey(), wire.getPreferredLocale(), rooms);
      if (view.isEmpty()) {
        observer.onError(Status.NOT_FOUND.asRuntimeException());
        return;
      }
      var starter = view.orElseThrow();
      String tenant = request.canonicalTenantId().toString();
      String game = request.canonicalGameInstanceId().toString();
      String room = wire.getExpectedRoomInstanceKey();
      response =
          ReadRunOwnedFixtureLookRoomResponse.newBuilder()
              .setLifecycle(
                  WorldCanonicalInstanceLifecycleGrpcCodec.toFixtureResponse(
                      request, starter.lifecycle()))
              .setSnapshot(
                  WorldManagementGrpcService.projectRoomSnapshot(
                      starter.snapshot(), tenant, game, tenant + ":" + game + ":" + room))
              .build();
    } catch (RuntimeException unavailable) {
      observer.onError(Status.UNAVAILABLE.asRuntimeException());
      return;
    }
    observer.onNext(response);
    observer.onCompleted();
  }
}
