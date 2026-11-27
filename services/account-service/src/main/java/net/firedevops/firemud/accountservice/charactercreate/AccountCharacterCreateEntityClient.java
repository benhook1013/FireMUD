package net.firedevops.firemud.accountservice.charactercreate;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
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
import net.firedevops.firemud.entitymanagement.v1.EntityCharacterCreateServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.EntityCharacterCreateTerminal;
import net.firedevops.firemud.entitymanagement.v1.ReadEntityCharacterCreateTerminalRequest;
import net.firedevops.firemud.gamesession.v1.CharacterCreateTargetProtection;
import net.firedevops.firemud.worldmanagement.v1.WorldCharacterCreateLifecycleProtection;

/**
 * Unregistered certificate-only Account client; every call uses its exact owner server identity.
 */
public final class AccountCharacterCreateEntityClient
    extends AbstractReloadingBlockingGrpcClient<
        EntityCharacterCreateServiceGrpc.EntityCharacterCreateServiceBlockingStub> {
  private final String namespace;

  public AccountCharacterCreateEntityClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tls,
      GrpcChannelFactory channels,
      String namespace) {
    super(
        endpoints,
        requireFileBackedMtls(tls, "Account CHARACTER_CREATE Entity terminal"),
        channels,
        AccountCharacterCreateEntityClient.class);
    this.namespace = AccountCharacterCreateDelegationBindings.namespace(namespace);
  }

  public void init() throws IOException {
    initOnce(
        "Creation Entity terminal client is closed",
        "Creation Entity terminal client is not initialized");
  }

  @Override
  protected String configuredTarget(ServiceEndpointsProperties endpoints) {
    return endpoints.getEntityManagementService();
  }

  @Override
  protected String defaultTarget() {
    return "entity-management-service:6565";
  }

  @Override
  protected EntityCharacterCreateServiceGrpc.EntityCharacterCreateServiceBlockingStub buildStub(
      ManagedChannel channel) {
    String peer = "spiffe://firemud/ns/" + namespace + "/sa/entity-management-service";
    return EntityCharacterCreateServiceGrpc.newBlockingStub(channel)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(peer))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(peer));
  }

  public EntityCharacterCreateTerminal commitAndReadOriginal(
      ReadOriginalCharacterCreateProtectionResponse account,
      CharacterCreateTargetProtection target,
      WorldCharacterCreateLifecycleProtection world) {
    requireNoAmbientSql("Entity creation calls require no ambient Account transaction");
    AccountCharacterCreateDelegationBindings.requireAccount(namespace, account);
    AccountCharacterCreateDelegationBindings.gameSession(account, target);
    AccountCharacterCreateDelegationBindings.world(account, world);
    var request = AccountCharacterCreateDelegationBindings.request(account, target, world);
    var committed = calls().commitProtectedCharacterCreate(request);
    AccountCharacterCreateDelegationBindings.terminal(request, account, target, world, committed);
    var readback = readOriginalTerminal(account, target, world);
    if (!committed.equals(readback)) throw AccountCharacterCreateDelegationBindings.invalid();
    return readback;
  }

  /** Lookup-only reconciliation after an ambiguous commit; absence is never an abort. */
  public EntityCharacterCreateTerminal readOriginalTerminal(
      ReadOriginalCharacterCreateProtectionResponse account,
      CharacterCreateTargetProtection target,
      WorldCharacterCreateLifecycleProtection world) {
    requireNoAmbientSql("Entity creation calls require no ambient Account transaction");
    AccountCharacterCreateDelegationBindings.requireAccount(namespace, account);
    AccountCharacterCreateDelegationBindings.gameSession(account, target);
    AccountCharacterCreateDelegationBindings.world(account, world);
    var request = AccountCharacterCreateDelegationBindings.request(account, target, world);
    var terminal =
        calls()
            .readEntityCharacterCreateTerminal(
                ReadEntityCharacterCreateTerminalRequest.newBuilder()
                    .setOriginal(request.getOriginal())
                    .build());
    AccountCharacterCreateDelegationBindings.terminal(request, account, target, world, terminal);
    return terminal;
  }

  private EntityCharacterCreateServiceGrpc.EntityCharacterCreateServiceBlockingStub calls() {
    return requireInitializedStub("Creation Entity terminal client is not initialized")
        .withMaxInboundMessageSize(AccountCharacterCreateDelegationBindings.MAX_TERMINAL_BYTES)
        .withDeadlineAfter(5, TimeUnit.SECONDS);
  }

  /**
   * Reads a terminal independently for Account's own original hold. The immutable terminal names
   * the other protections; this lookup never acquires them or attempts a new commit.
   */
  public VerifiedTerminal readVerifiedOriginalTerminal(
      ReadOriginalCharacterCreateProtectionResponse account) {
    requireNoAmbientSql("Entity creation calls require no ambient Account transaction");
    AccountCharacterCreateDelegationBindings.requireAccount(namespace, account);
    var terminal =
        calls()
            .readEntityCharacterCreateTerminal(
                ReadEntityCharacterCreateTerminalRequest.newBuilder()
                    .setOriginal(AccountCharacterCreateDelegationBindings.original(account))
                    .build());
    AccountCharacterCreateDelegationBindings.exact(
        terminal, AccountCharacterCreateDelegationBindings.MAX_TERMINAL_BYTES);
    try {
      var target =
          CharacterCreateTargetProtection.parseFrom(terminal.getOriginalGameSessionProtection());
      var lifecycle =
          WorldCharacterCreateLifecycleProtection.parseFrom(terminal.getOriginalWorldProtection());
      AccountCharacterCreateDelegationBindings.gameSession(account, target);
      AccountCharacterCreateDelegationBindings.world(account, lifecycle);
      var request = AccountCharacterCreateDelegationBindings.request(account, target, lifecycle);
      AccountCharacterCreateDelegationBindings.terminal(
          request, account, target, lifecycle, terminal);
      return new VerifiedTerminal(account, terminal);
    } catch (com.google.protobuf.InvalidProtocolBufferException malformed) {
      throw AccountCharacterCreateDelegationBindings.invalid();
    }
  }

  /** Constructed only after this client's exact authenticated original-terminal read. */
  public static final class VerifiedTerminal {
    private final ReadOriginalCharacterCreateProtectionResponse account;
    private final EntityCharacterCreateTerminal terminal;

    private VerifiedTerminal(
        ReadOriginalCharacterCreateProtectionResponse account,
        EntityCharacterCreateTerminal terminal) {
      this.account = account;
      this.terminal = terminal;
    }

    @SuppressFBWarnings(
        value = "EI_EXPOSE_REP",
        justification =
            "Generated protobuf messages are immutable values and cannot expose mutable state.")
    public ReadOriginalCharacterCreateProtectionResponse account() {
      return account;
    }

    @SuppressFBWarnings(
        value = "EI_EXPOSE_REP",
        justification =
            "Generated protobuf messages are immutable values and cannot expose mutable state.")
    public EntityCharacterCreateTerminal terminal() {
      return terminal;
    }
  }
}
