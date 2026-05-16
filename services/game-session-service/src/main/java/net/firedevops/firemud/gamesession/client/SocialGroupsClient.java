package net.firedevops.firemud.gamesession.client;

import jakarta.annotation.PostConstruct;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.socialgroups.v1.AddFriendRequest;
import net.firedevops.firemud.socialgroups.v1.AddFriendResponse;
import net.firedevops.firemud.socialgroups.v1.FriendRosterFilter;
import net.firedevops.firemud.socialgroups.v1.GetFriendByOrdinalRequest;
import net.firedevops.firemud.socialgroups.v1.GetFriendByOrdinalResponse;
import net.firedevops.firemud.socialgroups.v1.GetFriendPresencePolicyRequest;
import net.firedevops.firemud.socialgroups.v1.GetFriendPresencePolicyResponse;
import net.firedevops.firemud.socialgroups.v1.GetFriendRequest;
import net.firedevops.firemud.socialgroups.v1.GetFriendResponse;
import net.firedevops.firemud.socialgroups.v1.GetFriendRosterSummaryRequest;
import net.firedevops.firemud.socialgroups.v1.GetFriendRosterSummaryResponse;
import net.firedevops.firemud.socialgroups.v1.ListFriendPresenceRequest;
import net.firedevops.firemud.socialgroups.v1.ListFriendPresenceResponse;
import net.firedevops.firemud.socialgroups.v1.ListFriendsRequest;
import net.firedevops.firemud.socialgroups.v1.ListFriendsResponse;
import net.firedevops.firemud.socialgroups.v1.RemoveFriendByOrdinalRequest;
import net.firedevops.firemud.socialgroups.v1.RemoveFriendByOrdinalResponse;
import net.firedevops.firemud.socialgroups.v1.RemoveFriendRequest;
import net.firedevops.firemud.socialgroups.v1.RemoveFriendResponse;
import net.firedevops.firemud.socialgroups.v1.SocialGroupsServiceGrpc;
import net.firedevops.firemud.socialgroups.v1.UpdateFriendPresencePolicyRequest;
import net.firedevops.firemud.socialgroups.v1.UpdateFriendPresencePolicyResponse;
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

  public ListFriendsResponse listFriends(long tenantId, long accountId, FriendRosterFilter filter) {
    return callStub()
        .listFriends(
            ListFriendsRequest.newBuilder()
                .setTenantId(Long.toString(tenantId))
                .setAccountId(Long.toString(accountId))
                .setFilter(filter)
                .build());
  }

  public ListFriendsResponse listFriends(long tenantId, long accountId) {
    return listFriends(tenantId, accountId, FriendRosterFilter.FRIEND_ROSTER_FILTER_ALL);
  }

  public AddFriendResponse addFriend(long tenantId, long accountId, long friendAccountId) {
    return callStub()
        .addFriend(
            AddFriendRequest.newBuilder()
                .setTenantId(Long.toString(tenantId))
                .setAccountId(Long.toString(accountId))
                .setFriendAccountId(Long.toString(friendAccountId))
                .build());
  }

  public RemoveFriendResponse removeFriend(long tenantId, long accountId, long friendAccountId) {
    return callStub()
        .removeFriend(
            RemoveFriendRequest.newBuilder()
                .setTenantId(Long.toString(tenantId))
                .setAccountId(Long.toString(accountId))
                .setFriendAccountId(Long.toString(friendAccountId))
                .build());
  }

  public RemoveFriendByOrdinalResponse removeFriendByOrdinal(
      long tenantId, long accountId, int ordinal) {
    return callStub()
        .removeFriendByOrdinal(
            RemoveFriendByOrdinalRequest.newBuilder()
                .setTenantId(Long.toString(tenantId))
                .setAccountId(Long.toString(accountId))
                .setOrdinal(ordinal)
                .build());
  }

  public GetFriendResponse getFriend(long tenantId, long accountId, long friendAccountId) {
    return callStub()
        .getFriend(
            GetFriendRequest.newBuilder()
                .setTenantId(Long.toString(tenantId))
                .setAccountId(Long.toString(accountId))
                .setFriendAccountId(Long.toString(friendAccountId))
                .build());
  }

  public GetFriendByOrdinalResponse getFriendByOrdinal(long tenantId, long accountId, int ordinal) {
    return callStub()
        .getFriendByOrdinal(
            GetFriendByOrdinalRequest.newBuilder()
                .setTenantId(Long.toString(tenantId))
                .setAccountId(Long.toString(accountId))
                .setOrdinal(ordinal)
                .build());
  }

  public GetFriendRosterSummaryResponse getFriendRosterSummary(long tenantId, long accountId) {
    return callStub()
        .getFriendRosterSummary(
            GetFriendRosterSummaryRequest.newBuilder()
                .setTenantId(Long.toString(tenantId))
                .setAccountId(Long.toString(accountId))
                .build());
  }

  public GetFriendPresencePolicyResponse getFriendPresencePolicy(long tenantId, long accountId) {
    return callStub()
        .getFriendPresencePolicy(
            GetFriendPresencePolicyRequest.newBuilder()
                .setTenantId(Long.toString(tenantId))
                .setAccountId(Long.toString(accountId))
                .build());
  }

  public UpdateFriendPresencePolicyResponse updateFriendPresencePolicy(
      long tenantId,
      long accountId,
      net.firedevops.firemud.socialgroups.v1.FriendPresenceVisibilityPolicy visibilityPolicy) {
    return callStub()
        .updateFriendPresencePolicy(
            UpdateFriendPresencePolicyRequest.newBuilder()
                .setTenantId(Long.toString(tenantId))
                .setAccountId(Long.toString(accountId))
                .setVisibilityPolicy(visibilityPolicy)
                .build());
  }

  private SocialGroupsServiceGrpc.SocialGroupsServiceBlockingStub callStub() {
    return stub().withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS);
  }
}
