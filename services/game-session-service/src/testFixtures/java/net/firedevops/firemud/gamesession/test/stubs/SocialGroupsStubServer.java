package net.firedevops.firemud.gamesession.test.stubs;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.socialgroups.v1.AddFriendRequest;
import net.firedevops.firemud.socialgroups.v1.AddFriendResponse;
import net.firedevops.firemud.socialgroups.v1.FriendRosterEntry;
import net.firedevops.firemud.socialgroups.v1.FriendRosterFilter;
import net.firedevops.firemud.socialgroups.v1.FriendRosterSummary;
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
import net.firedevops.firemud.socialgroups.v1.SendMessageRequest;
import net.firedevops.firemud.socialgroups.v1.SendMessageResponse;
import net.firedevops.firemud.socialgroups.v1.SocialGroupsServiceGrpc;
import net.firedevops.firemud.socialgroups.v1.UpdateFriendPresencePolicyRequest;
import net.firedevops.firemud.socialgroups.v1.UpdateFriendPresencePolicyResponse;

public final class SocialGroupsStubServer implements AutoCloseable {
  private final Server server;
  private final int port;
  private final AtomicReference<SendMessageRequest> lastRequest = new AtomicReference<>();
  private final CopyOnWriteArrayList<SendMessageRequest> messageRequests =
      new CopyOnWriteArrayList<>();
  private final AtomicReference<ListFriendsRequest> lastFriendsRequest = new AtomicReference<>();
  private final AtomicReference<GetFriendRequest> lastGetFriendRequest = new AtomicReference<>();
  private final AtomicReference<GetFriendByOrdinalRequest> lastGetFriendByOrdinalRequest =
      new AtomicReference<>();
  private final AtomicReference<GetFriendRosterSummaryRequest> lastSummaryRequest =
      new AtomicReference<>();
  private final AtomicReference<GetFriendPresencePolicyRequest> lastGetVisibilityRequest =
      new AtomicReference<>();
  private final AtomicReference<AddFriendRequest> lastAddFriendRequest = new AtomicReference<>();
  private final AtomicReference<RemoveFriendByOrdinalRequest> lastRemoveFriendByOrdinalRequest =
      new AtomicReference<>();
  private final AtomicReference<RemoveFriendRequest> lastRemoveFriendRequest =
      new AtomicReference<>();
  private final AtomicReference<ListFriendPresenceRequest> lastPresenceRequest =
      new AtomicReference<>();
  private final AtomicReference<UpdateFriendPresencePolicyRequest> lastUpdateVisibilityRequest =
      new AtomicReference<>();
  private final AtomicReference<ListFriendPresenceResponse> friendPresenceResponse =
      new AtomicReference<>(ListFriendPresenceResponse.newBuilder().build());
  private final AtomicReference<
          net.firedevops.firemud.socialgroups.v1.FriendPresenceVisibilityPolicy>
      friendPresencePolicy =
          new AtomicReference<>(
              net.firedevops.firemud.socialgroups.v1.FriendPresenceVisibilityPolicy
                  .FRIEND_PRESENCE_VISIBILITY_POLICY_FRIENDS_ONLY);
  private final ConcurrentHashMap<String, FriendRosterEntry> rosterEntries =
      new ConcurrentHashMap<>();

  public SocialGroupsStubServer(int port) throws IOException {
    this.server =
        ServerBuilder.forPort(port)
            .addService(
                new SocialGroupsServiceGrpc.SocialGroupsServiceImplBase() {
                  @Override
                  public void sendMessage(
                      SendMessageRequest request,
                      StreamObserver<SendMessageResponse> responseObserver) {
                    lastRequest.set(request);
                    messageRequests.add(request);
                    SendMessageResponse response =
                        SendMessageResponse.newBuilder().setSuccess(true).build();
                    responseObserver.onNext(response);
                    responseObserver.onCompleted();
                  }

                  @Override
                  public void listFriendPresence(
                      ListFriendPresenceRequest request,
                      StreamObserver<ListFriendPresenceResponse> responseObserver) {
                    lastPresenceRequest.set(request);
                    responseObserver.onNext(friendPresenceResponse.get());
                    responseObserver.onCompleted();
                  }

                  @Override
                  public void listFriends(
                      ListFriendsRequest request,
                      StreamObserver<ListFriendsResponse> responseObserver) {
                    lastFriendsRequest.set(request);
                    responseObserver.onNext(currentRosterResponse(request.getFilter()));
                    responseObserver.onCompleted();
                  }

                  @Override
                  public void getFriend(
                      GetFriendRequest request,
                      StreamObserver<GetFriendResponse> responseObserver) {
                    lastGetFriendRequest.set(request);
                    FriendRosterEntry entry = rosterEntries.get(request.getFriendAccountId());
                    GetFriendResponse.Builder response = GetFriendResponse.newBuilder();
                    if (entry == null) {
                      response.setError(
                          net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                              .setCode("NOT_FOUND")
                              .setMessage(
                                  "Friend not found for accountId=" + request.getFriendAccountId())
                              .build());
                    } else {
                      response.setFriend(entry);
                    }
                    responseObserver.onNext(response.build());
                    responseObserver.onCompleted();
                  }

                  @Override
                  public void getFriendByOrdinal(
                      GetFriendByOrdinalRequest request,
                      StreamObserver<GetFriendByOrdinalResponse> responseObserver) {
                    lastGetFriendByOrdinalRequest.set(request);
                    FriendRosterEntry entry = findEntryByOrdinal(request.getOrdinal());
                    GetFriendByOrdinalResponse.Builder response =
                        GetFriendByOrdinalResponse.newBuilder();
                    if (entry == null) {
                      response.setError(
                          net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                              .setCode("NOT_FOUND")
                              .setMessage("Friend not found for ordinal=" + request.getOrdinal())
                              .build());
                    } else {
                      response.setFriend(entry);
                    }
                    responseObserver.onNext(response.build());
                    responseObserver.onCompleted();
                  }

                  @Override
                  public void getFriendRosterSummary(
                      GetFriendRosterSummaryRequest request,
                      StreamObserver<GetFriendRosterSummaryResponse> responseObserver) {
                    lastSummaryRequest.set(request);
                    responseObserver.onNext(
                        GetFriendRosterSummaryResponse.newBuilder()
                            .setSummary(currentRosterSummary())
                            .build());
                    responseObserver.onCompleted();
                  }

                  @Override
                  public void addFriend(
                      AddFriendRequest request,
                      StreamObserver<AddFriendResponse> responseObserver) {
                    lastAddFriendRequest.set(request);
                    upsertRosterEntry(request.getFriendAccountId());
                    responseObserver.onNext(
                        AddFriendResponse.newBuilder().setSuccess(true).build());
                    responseObserver.onCompleted();
                  }

                  @Override
                  public void removeFriend(
                      RemoveFriendRequest request,
                      StreamObserver<RemoveFriendResponse> responseObserver) {
                    lastRemoveFriendRequest.set(request);
                    rosterEntries.remove(request.getFriendAccountId());
                    responseObserver.onNext(
                        RemoveFriendResponse.newBuilder().setSuccess(true).build());
                    responseObserver.onCompleted();
                  }

                  @Override
                  public void removeFriendByOrdinal(
                      RemoveFriendByOrdinalRequest request,
                      StreamObserver<RemoveFriendByOrdinalResponse> responseObserver) {
                    lastRemoveFriendByOrdinalRequest.set(request);
                    FriendRosterEntry entry = findEntryByOrdinal(request.getOrdinal());
                    RemoveFriendByOrdinalResponse.Builder response =
                        RemoveFriendByOrdinalResponse.newBuilder();
                    if (entry == null) {
                      response
                          .setSuccess(false)
                          .setError(
                              net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                                  .setCode("NOT_FOUND")
                                  .setMessage(
                                      "Friend not found for ordinal=" + request.getOrdinal())
                                  .build());
                    } else {
                      rosterEntries.remove(entry.getFriendAccountId());
                      response.setSuccess(true).setRemovedFriend(entry);
                    }
                    responseObserver.onNext(response.build());
                    responseObserver.onCompleted();
                  }

                  @Override
                  public void getFriendPresencePolicy(
                      GetFriendPresencePolicyRequest request,
                      StreamObserver<GetFriendPresencePolicyResponse> responseObserver) {
                    lastGetVisibilityRequest.set(request);
                    responseObserver.onNext(
                        GetFriendPresencePolicyResponse.newBuilder()
                            .setCurrentPolicy(friendPresencePolicy.get())
                            .build());
                    responseObserver.onCompleted();
                  }

                  @Override
                  public void updateFriendPresencePolicy(
                      UpdateFriendPresencePolicyRequest request,
                      StreamObserver<UpdateFriendPresencePolicyResponse> responseObserver) {
                    lastUpdateVisibilityRequest.set(request);
                    if (request.getVisibilityPolicy()
                        == net.firedevops.firemud.socialgroups.v1.FriendPresenceVisibilityPolicy
                            .FRIEND_PRESENCE_VISIBILITY_POLICY_HIDDEN_STAFF) {
                      responseObserver.onNext(
                          UpdateFriendPresencePolicyResponse.newBuilder()
                              .setSuccess(false)
                              .setError(
                                  net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                                      .setCode("INVALID_ARGUMENT")
                                      .setMessage(
                                          "Friend presence visibility policy HIDDEN_STAFF is reserved")
                                      .build())
                              .build());
                      responseObserver.onCompleted();
                      return;
                    }
                    friendPresencePolicy.set(request.getVisibilityPolicy());
                    responseObserver.onNext(
                        UpdateFriendPresencePolicyResponse.newBuilder()
                            .setSuccess(true)
                            .setCurrentPolicy(friendPresencePolicy.get())
                            .build());
                    responseObserver.onCompleted();
                  }
                })
            .build()
            .start();
    this.port = server.getPort();
  }

  public String endpoint() {
    return "localhost:" + port;
  }

  public int port() {
    return port;
  }

  public Optional<SendMessageRequest> lastRequest() {
    return Optional.ofNullable(lastRequest.get());
  }

  public List<SendMessageRequest> messageRequests() {
    return List.copyOf(messageRequests);
  }

  public Optional<ListFriendPresenceRequest> lastPresenceRequest() {
    return Optional.ofNullable(lastPresenceRequest.get());
  }

  public Optional<ListFriendsRequest> lastFriendsRequest() {
    return Optional.ofNullable(lastFriendsRequest.get());
  }

  public Optional<GetFriendRequest> lastGetFriendRequest() {
    return Optional.ofNullable(lastGetFriendRequest.get());
  }

  public Optional<GetFriendByOrdinalRequest> lastGetFriendByOrdinalRequest() {
    return Optional.ofNullable(lastGetFriendByOrdinalRequest.get());
  }

  public Optional<GetFriendRosterSummaryRequest> lastSummaryRequest() {
    return Optional.ofNullable(lastSummaryRequest.get());
  }

  public Optional<GetFriendPresencePolicyRequest> lastGetVisibilityRequest() {
    return Optional.ofNullable(lastGetVisibilityRequest.get());
  }

  public Optional<AddFriendRequest> lastAddFriendRequest() {
    return Optional.ofNullable(lastAddFriendRequest.get());
  }

  public Optional<RemoveFriendRequest> lastRemoveFriendRequest() {
    return Optional.ofNullable(lastRemoveFriendRequest.get());
  }

  public Optional<RemoveFriendByOrdinalRequest> lastRemoveFriendByOrdinalRequest() {
    return Optional.ofNullable(lastRemoveFriendByOrdinalRequest.get());
  }

  public Optional<UpdateFriendPresencePolicyRequest> lastUpdateVisibilityRequest() {
    return Optional.ofNullable(lastUpdateVisibilityRequest.get());
  }

  public void setFriendPresenceResponse(ListFriendPresenceResponse response) {
    friendPresenceResponse.set(response);
    rosterEntries.clear();
    for (var presence : response.getPresencesList()) {
      upsertRosterEntry(presence.getFriendAccountId());
    }
  }

  public void setFriendPresenceEntries(
      List<net.firedevops.firemud.socialgroups.v1.FriendPresenceEntry> entries) {
    friendPresenceResponse.set(
        ListFriendPresenceResponse.newBuilder().addAllPresences(entries).build());
    rosterEntries.clear();
    for (var presence : entries) {
      upsertRosterEntry(presence.getFriendAccountId());
    }
  }

  public ListFriendPresenceResponse currentFriendPresenceResponse() {
    return friendPresenceResponse.get();
  }

  public void setFriendPresencePolicy(
      net.firedevops.firemud.socialgroups.v1.FriendPresenceVisibilityPolicy visibilityPolicy) {
    friendPresencePolicy.set(visibilityPolicy);
  }

  public void resetState() {
    lastRequest.set(null);
    messageRequests.clear();
    lastFriendsRequest.set(null);
    lastGetFriendRequest.set(null);
    lastGetFriendByOrdinalRequest.set(null);
    lastSummaryRequest.set(null);
    lastGetVisibilityRequest.set(null);
    lastAddFriendRequest.set(null);
    lastRemoveFriendByOrdinalRequest.set(null);
    lastRemoveFriendRequest.set(null);
    lastPresenceRequest.set(null);
    lastUpdateVisibilityRequest.set(null);
    friendPresenceResponse.set(ListFriendPresenceResponse.newBuilder().build());
    friendPresencePolicy.set(
        net.firedevops.firemud.socialgroups.v1.FriendPresenceVisibilityPolicy
            .FRIEND_PRESENCE_VISIBILITY_POLICY_FRIENDS_ONLY);
    rosterEntries.clear();
  }

  private ListFriendsResponse currentRosterResponse(FriendRosterFilter filter) {
    ListFriendsResponse.Builder roster = ListFriendsResponse.newBuilder();
    List<FriendRosterEntry> entries =
        rosterEntries.values().stream()
            .sorted(java.util.Comparator.comparing(FriendRosterEntry::getFriendAccountId))
            .toList();
    for (var entry : entries) {
      if (matchesFilter(filter, entry)) {
        roster.addFriends(entry);
      }
    }
    ListFriendPresenceResponse response = friendPresenceResponse.get();
    if (response.hasError()) {
      roster.setError(response.getError());
    }
    roster.setFilter(
        filter == FriendRosterFilter.FRIEND_ROSTER_FILTER_UNSPECIFIED
            ? FriendRosterFilter.FRIEND_ROSTER_FILTER_ALL
            : filter);
    roster.setTotalCount(entries.size());
    roster.setMatchCount(roster.getFriendsCount());
    return roster.build();
  }

  private void upsertRosterEntry(String friendAccountId) {
    var presence =
        friendPresenceResponse.get().getPresencesList().stream()
            .filter(entry -> entry.getFriendAccountId().equals(friendAccountId))
            .findFirst()
            .orElseGet(
                () ->
                    net.firedevops.firemud.socialgroups.v1.FriendPresenceEntry.newBuilder()
                        .setFriendAccountId(friendAccountId)
                        .build());
    rosterEntries.put(
        friendAccountId,
        FriendRosterEntry.newBuilder()
            .setOrdinal(rosterEntries.size() + 1)
            .setFriendAccountId(friendAccountId)
            .setStatus("active")
            .setPresence(presence)
            .build());
  }

  private FriendRosterSummary currentRosterSummary() {
    List<FriendRosterEntry> entries =
        rosterEntries.values().stream()
            .sorted(java.util.Comparator.comparing(FriendRosterEntry::getFriendAccountId))
            .toList();
    int onlineCount = 0;
    int recentCount = 0;
    int publicCount = 0;
    int friendsOnlyCount = 0;
    int privateCount = 0;
    int hiddenStaffCount = 0;
    int unspecifiedVisibilityCount = 0;
    int sharedCount = 0;
    int isolatedCount = 0;
    int unspecifiedScopeCount = 0;
    for (FriendRosterEntry entry : entries) {
      if (entry.getPresence().getOnline()) {
        onlineCount++;
      } else if (entry.getPresence().getLastSeenAtMs() > 0) {
        recentCount++;
      }
      switch (entry.getPresence().getVisibilityPolicy()) {
        case FRIEND_PRESENCE_VISIBILITY_POLICY_PUBLIC -> publicCount++;
        case FRIEND_PRESENCE_VISIBILITY_POLICY_FRIENDS_ONLY -> friendsOnlyCount++;
        case FRIEND_PRESENCE_VISIBILITY_POLICY_PRIVATE -> privateCount++;
        case FRIEND_PRESENCE_VISIBILITY_POLICY_HIDDEN_STAFF -> hiddenStaffCount++;
        case FRIEND_PRESENCE_VISIBILITY_POLICY_UNSPECIFIED, UNRECOGNIZED ->
            unspecifiedVisibilityCount++;
      }
      switch (entry.getPresence().getPlayableStateScope()) {
        case PLAYABLE_STATE_SCOPE_SHARED -> sharedCount++;
        case PLAYABLE_STATE_SCOPE_ISOLATED -> isolatedCount++;
        case PLAYABLE_STATE_SCOPE_UNSPECIFIED, UNRECOGNIZED -> unspecifiedScopeCount++;
      }
    }
    return FriendRosterSummary.newBuilder()
        .setTotalCount(entries.size())
        .setOnlineCount(onlineCount)
        .setOfflineCount(entries.size() - onlineCount)
        .setRecentCount(recentCount)
        .setPublicCount(publicCount)
        .setFriendsOnlyCount(friendsOnlyCount)
        .setPrivateCount(privateCount)
        .setHiddenStaffCount(hiddenStaffCount)
        .setUnspecifiedVisibilityCount(unspecifiedVisibilityCount)
        .setSharedCount(sharedCount)
        .setIsolatedCount(isolatedCount)
        .setUnspecifiedScopeCount(unspecifiedScopeCount)
        .build();
  }

  private FriendRosterEntry findEntryByOrdinal(int ordinal) {
    return rosterEntries.values().stream()
        .sorted(java.util.Comparator.comparing(FriendRosterEntry::getFriendAccountId))
        .filter(entry -> entry.getOrdinal() == ordinal)
        .findFirst()
        .orElse(null);
  }

  private boolean matchesFilter(FriendRosterFilter filter, FriendRosterEntry entry) {
    boolean online = entry.getPresence().getOnline();
    return switch (filter) {
      case FRIEND_ROSTER_FILTER_ONLINE -> online;
      case FRIEND_ROSTER_FILTER_OFFLINE -> !online;
      case FRIEND_ROSTER_FILTER_RECENT -> !online && entry.getPresence().getLastSeenAtMs() > 0;
      case FRIEND_ROSTER_FILTER_PUBLIC ->
          entry.getPresence().getVisibilityPolicy()
              == net.firedevops.firemud.socialgroups.v1.FriendPresenceVisibilityPolicy
                  .FRIEND_PRESENCE_VISIBILITY_POLICY_PUBLIC;
      case FRIEND_ROSTER_FILTER_FRIENDS_ONLY ->
          entry.getPresence().getVisibilityPolicy()
              == net.firedevops.firemud.socialgroups.v1.FriendPresenceVisibilityPolicy
                  .FRIEND_PRESENCE_VISIBILITY_POLICY_FRIENDS_ONLY;
      case FRIEND_ROSTER_FILTER_PRIVATE ->
          entry.getPresence().getVisibilityPolicy()
              == net.firedevops.firemud.socialgroups.v1.FriendPresenceVisibilityPolicy
                  .FRIEND_PRESENCE_VISIBILITY_POLICY_PRIVATE;
      case FRIEND_ROSTER_FILTER_HIDDEN_STAFF ->
          entry.getPresence().getVisibilityPolicy()
              == net.firedevops.firemud.socialgroups.v1.FriendPresenceVisibilityPolicy
                  .FRIEND_PRESENCE_VISIBILITY_POLICY_HIDDEN_STAFF;
      case FRIEND_ROSTER_FILTER_UNSPECIFIED_VISIBILITY ->
          entry.getPresence().getVisibilityPolicy()
                  == net.firedevops.firemud.socialgroups.v1.FriendPresenceVisibilityPolicy
                      .FRIEND_PRESENCE_VISIBILITY_POLICY_UNSPECIFIED
              || entry.getPresence().getVisibilityPolicyValue() == 0;
      case FRIEND_ROSTER_FILTER_SHARED ->
          entry.getPresence().getPlayableStateScope()
              == net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                  .PLAYABLE_STATE_SCOPE_SHARED;
      case FRIEND_ROSTER_FILTER_ISOLATED ->
          entry.getPresence().getPlayableStateScope()
              == net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                  .PLAYABLE_STATE_SCOPE_ISOLATED;
      case FRIEND_ROSTER_FILTER_UNSPECIFIED_SCOPE ->
          entry.getPresence().getPlayableStateScope()
                  == net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                      .PLAYABLE_STATE_SCOPE_UNSPECIFIED
              || entry.getPresence().getPlayableStateScopeValue() == 0;
      case FRIEND_ROSTER_FILTER_UNSPECIFIED, FRIEND_ROSTER_FILTER_ALL, UNRECOGNIZED -> true;
    };
  }

  @Override
  public void close() {
    if (server != null) {
      server.shutdownNow();
    }
  }
}
