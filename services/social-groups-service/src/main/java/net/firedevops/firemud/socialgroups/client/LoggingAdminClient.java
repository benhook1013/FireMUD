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
import net.firedevops.firemud.loggingadmin.v1.CreateReportRequest;
import net.firedevops.firemud.loggingadmin.v1.CreateReportResponse;
import net.firedevops.firemud.loggingadmin.v1.ReportServiceGrpc;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Client for communicating with the Logging & Admin Service. */
@Component
public class LoggingAdminClient
    extends AbstractReloadingBlockingGrpcClient<ReportServiceGrpc.ReportServiceBlockingStub> {
  public LoggingAdminClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      GrpcChannelFactory channelFactory,
      JwtUtil jwtUtil,
      ObjectProvider<RuntimeIdentity> runtimeIdentityProvider) {
    super(endpoints, tlsProps, channelFactory, LoggingAdminClient.class);
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
  protected ReportServiceGrpc.ReportServiceBlockingStub buildStub(io.grpc.ManagedChannel channel) {
    return GrpcClientAuth.attachInternal(
        ReportServiceGrpc.newBlockingStub(channel).withCompression("gzip"),
        jwtUtil,
        runtimeIdentityProvider.getIfAvailable());
  }

  /**
   * Create a moderation report for a chat message.
   *
   * @param tenantId tenant identifier
   * @param accountId account that sent the message
   * @param description details of the violation
   */
  public void reportChatViolation(long tenantId, long accountId, String description) {
    CreateReportRequest request =
        CreateReportRequest.newBuilder()
            .setTenantId(Long.toString(tenantId))
            .setReporterAccountId(Long.toString(accountId))
            .setTargetAccountId(Long.toString(accountId))
            .setType("CHAT_PROFANITY")
            .setDescription(description)
            .build();
    CreateReportResponse response = stub().createReport(request);
    if (response.hasError()) {
      throw new IllegalStateException(
          "Chat violation report failed: "
              + response.getError().getCode()
              + ": "
              + response.getError().getMessage());
    }
  }
}
