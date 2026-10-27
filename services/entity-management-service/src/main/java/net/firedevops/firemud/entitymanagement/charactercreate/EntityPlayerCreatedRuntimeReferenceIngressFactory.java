package net.firedevops.firemud.entitymanagement.charactercreate;

import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import java.util.Objects;

/** Creates only the two explicitly guarded owner reads; callers choose whether to bind them. */
public final class EntityPlayerCreatedRuntimeReferenceIngressFactory {
  private EntityPlayerCreatedRuntimeReferenceIngressFactory() {}

  public static ServerServiceDefinition bind(
      EntityPlayerCreatedRuntimeReferenceService owner, String trustedNamespace) {
    Objects.requireNonNull(owner, "owner");
    var service = new EntityPlayerCreatedRuntimeReferenceGrpcService(owner);
    return ServerInterceptors.intercept(
        service,
        new EntityPlayerCreatedRuntimeReferencePeerInterceptor(
            trustedNamespace, "ReadPlayerCreatedActorPlacementReceipt", "world-management-service"),
        new EntityPlayerCreatedRuntimeReferencePeerInterceptor(
            trustedNamespace, "ReadPlayerCreatedActorRuntimeReference", "game-session-service"));
  }
}
