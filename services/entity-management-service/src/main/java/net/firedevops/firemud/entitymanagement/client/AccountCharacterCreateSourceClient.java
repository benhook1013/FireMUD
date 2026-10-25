package net.firedevops.firemud.entitymanagement.client;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.account.v1.CharacterCreateSourceServiceGrpc;
import net.firedevops.firemud.account.v1.ReadOriginalCharacterCreateProtectionRequest;
import net.firedevops.firemud.account.v1.ReadOriginalCharacterCreateProtectionResponse;
import net.firedevops.firemud.account.v1.ReadOriginalCharacterCreateRequest;
import net.firedevops.firemud.account.v1.ReadOriginalCharacterCreateResponse;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.entitymanagement.charactercreate.EntityCharacterCreateBindings;

/** Unregistered direct same-namespace mTLS read of original Account creation intent and hold. */
public final class AccountCharacterCreateSourceClient
    extends AbstractReloadingBlockingGrpcClient<
        CharacterCreateSourceServiceGrpc.CharacterCreateSourceServiceBlockingStub> {
  private final String namespace;

  public AccountCharacterCreateSourceClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tls,
      GrpcChannelFactory channels,
      String namespace) {
    super(
        endpoints,
        EntityManagementSourceClientValidation.requireEntityManagementMtls(
            tls, "CHARACTER_CREATE source reads"),
        channels,
        AccountCharacterCreateSourceClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(namespace))
      throw new IllegalArgumentException("Namespace required");
    this.namespace = namespace;
  }

  public void init() throws SSLException, IOException {
    initOnce(
        "Account creation source client is closed",
        "Account creation source client is not initialized");
  }

  @Override
  protected String configuredTarget(ServiceEndpointsProperties endpoints) {
    return endpoints.getAccountService();
  }

  @Override
  protected String defaultTarget() {
    return "account-service:6565";
  }

  @Override
  protected CharacterCreateSourceServiceGrpc.CharacterCreateSourceServiceBlockingStub buildStub(
      ManagedChannel channel) {
    return CharacterCreateSourceServiceGrpc.newBlockingStub(channel)
        .withInterceptors(
            new GrpcServerPeerIdentityClientInterceptor(
                "spiffe://firemud/ns/" + namespace + "/sa/account-service"));
  }

  public ReadOriginalCharacterCreateResponse readOriginal(
      ReadOriginalCharacterCreateRequest request) {
    requireNoAmbientSql("Independent Account creation source read required");
    EntityCharacterCreateBindings.requireOriginalRequest(request);
    if (!namespace.equals(request.getTargetNamespace()))
      throw EntityCharacterCreateBindings.invalid();
    var response =
        requireInitializedStub("Account creation source client is not initialized")
            .withMaxInboundMessageSize(
                EntityCharacterCreateBindings.MAX_ACCOUNT_ORIGINAL_RESPONSE_BYTES)
            .withDeadlineAfter(5, TimeUnit.SECONDS)
            .readOriginalCharacterCreate(request);
    EntityCharacterCreateBindings.requireOriginal(request, response);
    return response;
  }

  public ReadOriginalCharacterCreateProtectionResponse readProtection(
      ReadOriginalCharacterCreateProtectionRequest request) {
    requireNoAmbientSql("Independent Account creation protection read required");
    EntityCharacterCreateBindings.exact(request);
    if (!request.hasOriginal() || request.getAccountProtectionFence() <= 0)
      throw EntityCharacterCreateBindings.invalid();
    EntityCharacterCreateBindings.requireOriginalRequest(request.getOriginal());
    EntityCharacterCreateBindings.id(request.getAccountProtectionId());
    if (!namespace.equals(request.getOriginal().getTargetNamespace()))
      throw EntityCharacterCreateBindings.invalid();
    var response =
        requireInitializedStub("Account creation source client is not initialized")
            .withMaxInboundMessageSize(EntityCharacterCreateBindings.MAX_ACCOUNT_RESPONSE_BYTES)
            .withDeadlineAfter(5, TimeUnit.SECONDS)
            .readOriginalCharacterCreateProtection(request);
    EntityCharacterCreateBindings.requireBound(
        response, EntityCharacterCreateBindings.MAX_ACCOUNT_RESPONSE_BYTES);
    if (!response.hasOriginal()
        || !request.getAccountProtectionId().equals(response.getAccountProtectionId())
        || request.getAccountProtectionFence() != response.getAccountProtectionFence())
      throw EntityCharacterCreateBindings.invalid();
    EntityCharacterCreateBindings.requireOriginal(request.getOriginal(), response.getOriginal());
    return response;
  }
}
