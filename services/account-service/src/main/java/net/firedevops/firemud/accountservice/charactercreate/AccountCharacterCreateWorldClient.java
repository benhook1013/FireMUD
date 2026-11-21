package net.firedevops.firemud.accountservice.charactercreate;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.account.v1.ReadOriginalCharacterCreateProtectionResponse;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.worldmanagement.v1.AcquireCharacterCreateLifecycleProtectionRequest;
import net.firedevops.firemud.worldmanagement.v1.CharacterCreateLifecycleServiceGrpc;
import net.firedevops.firemud.worldmanagement.v1.ReadOriginalCharacterCreateLifecycleProtectionRequest;
import net.firedevops.firemud.worldmanagement.v1.WorldCharacterCreateLifecycleProtection;

/**
 * Unregistered certificate-only Account client; every call uses its exact owner server identity.
 */
public final class AccountCharacterCreateWorldClient
    extends AbstractReloadingBlockingGrpcClient<
        CharacterCreateLifecycleServiceGrpc.CharacterCreateLifecycleServiceBlockingStub> {
  private final String namespace;

  public AccountCharacterCreateWorldClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tls,
      GrpcChannelFactory channels,
      String namespace) {
    super(
        endpoints,
        requireFileBackedMtls(tls, "Account CHARACTER_CREATE lifecycle"),
        channels,
        AccountCharacterCreateWorldClient.class);
    this.namespace = AccountCharacterCreateDelegationBindings.namespace(namespace);
  }

  public void init() throws IOException {
    initOnce("Creation lifecycle client is closed", "Creation lifecycle client is not initialized");
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
    String peer = "spiffe://firemud/ns/" + namespace + "/sa/world-management-service";
    return CharacterCreateLifecycleServiceGrpc.newBlockingStub(channel)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(peer))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(peer));
  }

  public WorldCharacterCreateLifecycleProtection acquireAndReadOriginal(
      ReadOriginalCharacterCreateProtectionResponse account) {
    requireNoAmbientSql("World creation calls require no ambient Account transaction");
    AccountCharacterCreateDelegationBindings.requireAccount(namespace, account);
    var selector = AccountCharacterCreateDelegationBindings.selector(account);
    var acquired =
        calls()
            .acquireCharacterCreateLifecycleProtection(
                AcquireCharacterCreateLifecycleProtectionRequest.newBuilder()
                    .setAccountProtection(selector)
                    .build());
    AccountCharacterCreateDelegationBindings.world(account, acquired);
    var readback =
        calls()
            .readOriginalCharacterCreateLifecycleProtection(
                ReadOriginalCharacterCreateLifecycleProtectionRequest.newBuilder()
                    .setAccountProtection(selector)
                    .setWorldProtectionId(acquired.getWorldProtectionId())
                    .setWorldProtectionFence(acquired.getWorldProtectionFence())
                    .build());
    AccountCharacterCreateDelegationBindings.world(account, readback);
    if (!acquired.equals(readback)) throw AccountCharacterCreateDelegationBindings.invalid();
    return readback;
  }

  private CharacterCreateLifecycleServiceGrpc.CharacterCreateLifecycleServiceBlockingStub calls() {
    return requireInitializedStub("Creation lifecycle client is not initialized")
        .withMaxInboundMessageSize(AccountCharacterCreateDelegationBindings.MAX_PROTECTION_BYTES)
        .withDeadlineAfter(5, TimeUnit.SECONDS);
  }
}
