package net.firedevops.firemud.loggingadmin.client;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.account.v1.AccountServiceGrpc;
import net.firedevops.firemud.account.v1.DeleteAccountRequest;
import net.firedevops.firemud.account.v1.DeleteAccountResponse;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.security.GrpcClientAuth;
import net.firedevops.firemud.common.security.JwtUtil;
import org.springframework.stereotype.Component;

/** gRPC client for communicating with the Account Service. */
@Component
public class AccountClient
    extends AbstractReloadingBlockingGrpcClient<AccountServiceGrpc.AccountServiceBlockingStub> {
  private final JwtUtil jwtUtil;

  public AccountClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      JwtUtil jwtUtil,
      GrpcChannelFactory channelFactory) {
    super(endpoints, tlsProps, channelFactory, AccountClient.class);
    this.jwtUtil = jwtUtil;
  }

  @PostConstruct
  void init() throws SSLException, IOException {
    initReloadingClient();
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
  protected AccountServiceGrpc.AccountServiceBlockingStub buildStub(
      io.grpc.ManagedChannel channel) {
    return GrpcClientAuth.attach(
        AccountServiceGrpc.newBlockingStub(channel).withCompression("gzip"), jwtUtil);
  }

  /** Permanently delete the account. */
  public DeleteAccountResponse deleteAccount(long tenantId, long accountId) {
    DeleteAccountRequest request =
        DeleteAccountRequest.newBuilder()
            .setTenantId(Long.toString(tenantId))
            .setAccountId(Long.toString(accountId))
            .build();
    return stub().deleteAccount(request);
  }
}
