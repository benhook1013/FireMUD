package net.firedevops.firemud.gamesession.client;

import jakarta.annotation.PostConstruct;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.socialgroups.v1.ListFriendPresenceRequest;
import net.firedevops.firemud.socialgroups.v1.ListFriendPresenceResponse;
import net.firedevops.firemud.socialgroups.v1.SocialGroupsServiceGrpc;
import org.springframework.stereotype.Component;

@Component
public final class SocialGroupsClient
    extends AbstractBlockingGrpcClient<SocialGroupsServiceGrpc.SocialGroupsServiceBlockingStub> {
  private static final long CALL_DEADLINE_SECONDS = 5L;

  public SocialGroupsClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      GrpcChannelFactory channelFactory,
      BlockingGrpcStubCustomizer stubCustomizer) {
    super(endpoints, tlsProps, channelFactory, stubCustomizer);
  }

  @PostConstruct
  void init() throws SSLException {
    initClient();
  }

  @Override
  protected String configuredTarget(ServiceEndpointsProperties endpoints) {
    return endpoints.getSocialGroupsService();
  }

  @Override
  protected String defaultTarget() {
    return "social-groups-service:6565";
  }

  @Override
  protected SocialGroupsServiceGrpc.SocialGroupsServiceBlockingStub buildStub(
      io.grpc.ManagedChannel channel) {
    return applyStubCustomizer(
        SocialGroupsServiceGrpc.newBlockingStub(channel).withCompression("gzip"));
  }

  public ListFriendPresenceResponse listFriendPresence(long tenantId, long accountId) {
    return callStub()
        .listFriendPresence(
            ListFriendPresenceRequest.newBuilder()
                .setTenantId(Long.toString(tenantId))
                .setAccountId(Long.toString(accountId))
                .build());
  }

  private SocialGroupsServiceGrpc.SocialGroupsServiceBlockingStub callStub() {
    return stub().withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS);
  }
}
