package net.firedevops.firemud.accountservice.client;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.loggingadmin.v1.ApplyModerationActionRequest;
import net.firedevops.firemud.loggingadmin.v1.LoggingAdminServiceGrpc;
import org.springframework.stereotype.Component;

/** Client for communicating with the Logging & Admin Service. */
@Component
public class LoggingAdminClient
    extends AbstractReloadingBlockingGrpcClient<
        LoggingAdminServiceGrpc.LoggingAdminServiceBlockingStub> {

  public LoggingAdminClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      GrpcChannelFactory channelFactory) {
    super(endpoints, tlsProps, channelFactory, LoggingAdminClient.class);
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
    return LoggingAdminServiceGrpc.newBlockingStub(channel).withCompression("gzip");
  }

  /** Log that a new account was created. */
  public void logAccountCreation(long tenantId, long accountId) {
    ApplyModerationActionRequest request =
        ApplyModerationActionRequest.newBuilder()
            .setTenantId(Long.toString(tenantId))
            .setAccountId(Long.toString(accountId))
            .setAction("ACCOUNT_CREATED")
            .setReason("")
            .build();
    stub().applyModerationAction(request);
  }

  /** Log that a payment transaction occurred. */
  public void logPayment(long tenantId, long accountId, long transactionId) {
    ApplyModerationActionRequest request =
        ApplyModerationActionRequest.newBuilder()
            .setTenantId(Long.toString(tenantId))
            .setAccountId(Long.toString(accountId))
            .setAction("PAYMENT_TXN")
            .setReason("txId=" + transactionId)
            .build();
    stub().applyModerationAction(request);
  }
}
