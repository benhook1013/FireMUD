package net.firedevops.firemud.socialgroups.client;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;
import net.firedevops.firemud.common.security.GrpcClientAuth;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.loggingadmin.v1.EvaluateModerationPolicyRequest;
import net.firedevops.firemud.loggingadmin.v1.EvaluateModerationPolicyResponse;
import net.firedevops.firemud.loggingadmin.v1.LoggingAdminServiceGrpc;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Internal moderation-policy reader for chat enforcement. */
@Component
public class ModerationPolicyClient
    extends AbstractReloadingBlockingGrpcClient<
        LoggingAdminServiceGrpc.LoggingAdminServiceBlockingStub> {
  static final String SCOPE_CHAT_SEND = "CHAT_SEND";

  public ModerationPolicyClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      GrpcChannelFactory channelFactory,
      JwtUtil jwtUtil,
      ObjectProvider<RuntimeIdentity> runtimeIdentityProvider) {
    super(endpoints, tlsProps, channelFactory, ModerationPolicyClient.class);
    this.jwtUtil = jwtUtil;
    this.runtimeIdentityProvider = runtimeIdentityProvider;
  }

  private final JwtUtil jwtUtil;
  private final ObjectProvider<RuntimeIdentity> runtimeIdentityProvider;

  @PostConstruct
  void init() throws SSLException, IOException {
    initReloadingClient();
  }

  @Override
  protected String configuredTarget(ServiceEndpointsProperties endpoints) {
    return endpoints.getLoggingAdminService();
  }

  @Override
  protected String defaultTarget() {
    return "logging-admin-service:6565";
  }

  @Override
  protected LoggingAdminServiceGrpc.LoggingAdminServiceBlockingStub buildStub(
      io.grpc.ManagedChannel channel) {
    return GrpcClientAuth.attachInternal(
        LoggingAdminServiceGrpc.newBlockingStub(channel).withCompression("gzip"),
        jwtUtil,
        runtimeIdentityProvider.getIfAvailable());
  }

  public EvaluateModerationPolicyResponse evaluateChatSend(long tenantId, long accountId) {
    EvaluateModerationPolicyRequest request =
        EvaluateModerationPolicyRequest.newBuilder()
            .setTenantId(Long.toString(tenantId))
            .setAccountId(Long.toString(accountId))
            .setScope(SCOPE_CHAT_SEND)
            .build();
    return stub().evaluateModerationPolicy(request);
  }
}
