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
import net.firedevops.firemud.gamesession.v1.AcquireCharacterCreateTargetProtectionRequest;
import net.firedevops.firemud.gamesession.v1.CharacterCreateTargetProtection;
import net.firedevops.firemud.gamesession.v1.CharacterCreateTargetServiceGrpc;
import net.firedevops.firemud.gamesession.v1.ReadOriginalCharacterCreateTargetProtectionRequest;

/**
 * Unregistered certificate-only Account client; every call uses its exact owner server identity.
 */
public final class AccountCharacterCreateGameSessionClient
    extends AbstractReloadingBlockingGrpcClient<
        CharacterCreateTargetServiceGrpc.CharacterCreateTargetServiceBlockingStub> {
  private final String namespace;

  public AccountCharacterCreateGameSessionClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tls,
      GrpcChannelFactory channels,
      String namespace) {
    super(
        endpoints,
        requireFileBackedMtls(tls, "Account CHARACTER_CREATE target"),
        channels,
        AccountCharacterCreateGameSessionClient.class);
    this.namespace = AccountCharacterCreateDelegationBindings.namespace(namespace);
  }

  public void init() throws IOException {
    initOnce("Creation target client is closed", "Creation target client is not initialized");
  }

  @Override
  protected String configuredTarget(ServiceEndpointsProperties endpoints) {
    return endpoints.getGameSessionService();
  }

  @Override
  protected String defaultTarget() {
    return "game-session-service:6565";
  }

  @Override
  protected CharacterCreateTargetServiceGrpc.CharacterCreateTargetServiceBlockingStub buildStub(
      ManagedChannel channel) {
    String peer = "spiffe://firemud/ns/" + namespace + "/sa/game-session-service";
    return CharacterCreateTargetServiceGrpc.newBlockingStub(channel)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(peer))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(peer));
  }

  public CharacterCreateTargetProtection acquireAndReadOriginal(
      ReadOriginalCharacterCreateProtectionResponse account) {
    requireNoAmbientSql("Game Session creation calls require no ambient Account transaction");
    AccountCharacterCreateDelegationBindings.requireAccount(namespace, account);
    var selector = AccountCharacterCreateDelegationBindings.selector(account);
    var acquired =
        calls()
            .acquireCharacterCreateTargetProtection(
                AcquireCharacterCreateTargetProtectionRequest.newBuilder()
                    .setAccountProtection(selector)
                    .build());
    AccountCharacterCreateDelegationBindings.gameSession(account, acquired);
    var readback =
        calls()
            .readOriginalCharacterCreateTargetProtection(
                ReadOriginalCharacterCreateTargetProtectionRequest.newBuilder()
                    .setAccountProtection(selector)
                    .setGameSessionProtectionId(acquired.getGameSessionProtectionId())
                    .setGameSessionProtectionFence(acquired.getGameSessionProtectionFence())
                    .build());
    AccountCharacterCreateDelegationBindings.gameSession(account, readback);
    if (!acquired.equals(readback)) throw AccountCharacterCreateDelegationBindings.invalid();
    return readback;
  }

  private CharacterCreateTargetServiceGrpc.CharacterCreateTargetServiceBlockingStub calls() {
    return requireInitializedStub("Creation target client is not initialized")
        .withMaxInboundMessageSize(AccountCharacterCreateDelegationBindings.MAX_PROTECTION_BYTES)
        .withDeadlineAfter(5, TimeUnit.SECONDS);
  }
}
