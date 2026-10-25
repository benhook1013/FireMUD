package net.firedevops.firemud.entitymanagement.client;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.entitymanagement.charactercreate.EntityCharacterCreateBindings;
import net.firedevops.firemud.worldmanagement.v1.CharacterCreateLifecycleServiceGrpc;
import net.firedevops.firemud.worldmanagement.v1.ReadOriginalCharacterCreateLifecycleProtectionRequest;
import net.firedevops.firemud.worldmanagement.v1.WorldCharacterCreateLifecycleProtection;

/** Unregistered direct World owner read; other lifecycle/admission evidence cannot substitute. */
public final class WorldCharacterCreateLifecycleClient
    extends AbstractReloadingBlockingGrpcClient<
        CharacterCreateLifecycleServiceGrpc.CharacterCreateLifecycleServiceBlockingStub> {
  private final String namespace;

  public WorldCharacterCreateLifecycleClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tls,
      GrpcChannelFactory channels,
      String namespace) {
    super(
        endpoints,
        EntityManagementSourceClientValidation.requireEntityManagementMtls(
            tls, "CHARACTER_CREATE lifecycle reads"),
        channels,
        WorldCharacterCreateLifecycleClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(namespace))
      throw new IllegalArgumentException("Namespace required");
    this.namespace = namespace;
  }

  public void init() throws SSLException, IOException {
    initOnce(
        "World creation lifecycle client is closed",
        "World creation lifecycle client is not initialized");
  }

  @Override
  protected String configuredTarget(ServiceEndpointsProperties endpoints) {
    return endpoints.getWorldManagementService();
  }

  @Override
  protected String defaultTarget() {
    return "world-management-service:6565";
  }

  @Override
  protected CharacterCreateLifecycleServiceGrpc.CharacterCreateLifecycleServiceBlockingStub
      buildStub(ManagedChannel channel) {
    return CharacterCreateLifecycleServiceGrpc.newBlockingStub(channel)
        .withInterceptors(
            new GrpcServerPeerIdentityClientInterceptor(
                "spiffe://firemud/ns/" + namespace + "/sa/world-management-service"));
  }

  public WorldCharacterCreateLifecycleProtection readOriginal(
      ReadOriginalCharacterCreateLifecycleProtectionRequest request) {
    requireNoAmbientSql("Independent World creation protection read required");
    EntityCharacterCreateBindings.exact(request);
    if (!request.hasAccountProtection()
        || !request.getAccountProtection().hasOriginal()
        || !namespace.equals(request.getAccountProtection().getOriginal().getTargetNamespace())
        || request.getWorldProtectionFence() <= 0L) throw EntityCharacterCreateBindings.invalid();
    EntityCharacterCreateBindings.id(request.getWorldProtectionId());
    var response =
        requireInitializedStub("World creation lifecycle client is not initialized")
            .withMaxInboundMessageSize(EntityCharacterCreateBindings.MAX_WORLD_RESPONSE_BYTES)
            .withDeadlineAfter(5, TimeUnit.SECONDS)
            .readOriginalCharacterCreateLifecycleProtection(request);
    EntityCharacterCreateBindings.requireBound(
        response, EntityCharacterCreateBindings.MAX_WORLD_RESPONSE_BYTES);
    if (!response.hasAccountProtection()
        || !request.getWorldProtectionId().equals(response.getWorldProtectionId())
        || request.getWorldProtectionFence() != response.getWorldProtectionFence()
        || response.getOriginalLifecycleEvidence().isEmpty())
      throw EntityCharacterCreateBindings.invalid();
    EntityCharacterCreateBindings.instant(response.getAcquiredAt());
    return response;
  }
}
