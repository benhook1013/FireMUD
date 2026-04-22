package net.firedevops.firemud.gamesession.client;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.loggingadmin.v1.EvaluateModerationPolicyRequest;
import net.firedevops.firemud.loggingadmin.v1.EvaluateModerationPolicyResponse;
import net.firedevops.firemud.loggingadmin.v1.LoggingAdminServiceGrpc;
import org.springframework.stereotype.Component;

/** Internal moderation-policy reader for gameplay admission. */
@Component
public class ModerationPolicyClient
    extends AbstractReloadingBlockingGrpcClient<
        LoggingAdminServiceGrpc.LoggingAdminServiceBlockingStub> {
  static final String SCOPE_GAMEPLAY_ADMISSION = "GAMEPLAY_ADMISSION";

  public ModerationPolicyClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      GrpcChannelFactory channelFactory,
      BlockingGrpcStubCustomizer stubCustomizer) {
    super(endpoints, tlsProps, channelFactory, stubCustomizer, ModerationPolicyClient.class);
  }

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
    return applyStubCustomizer(
        LoggingAdminServiceGrpc.newBlockingStub(channel).withCompression("gzip"));
  }

  public EvaluateModerationPolicyResponse evaluateGameplayAdmission(long tenantId, long accountId) {
    EvaluateModerationPolicyRequest request =
        EvaluateModerationPolicyRequest.newBuilder()
            .setTenantId(Long.toString(tenantId))
            .setAccountId(Long.toString(accountId))
            .setScope(SCOPE_GAMEPLAY_ADMISSION)
            .build();
    return stub().evaluateModerationPolicy(request);
  }
}
