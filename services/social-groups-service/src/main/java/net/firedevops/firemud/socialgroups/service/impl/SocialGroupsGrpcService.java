package net.firedevops.firemud.socialgroups.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.common.grpc.GrpcAppErrors;
import net.firedevops.firemud.common.security.RequestIdValidation;
import net.firedevops.firemud.socialgroups.dto.AddFriendRequest;
import net.firedevops.firemud.socialgroups.dto.CreateGuildRequest;
import net.firedevops.firemud.socialgroups.dto.FriendPresenceDto;
import net.firedevops.firemud.socialgroups.dto.FriendPresencePolicyViewDto;
import net.firedevops.firemud.socialgroups.dto.FriendPresenceViewDto;
import net.firedevops.firemud.socialgroups.dto.FriendPresenceVisibilityPolicyValue;
import net.firedevops.firemud.socialgroups.dto.FriendRosterEntryDto;
import net.firedevops.firemud.socialgroups.dto.FriendRosterFilter;
import net.firedevops.firemud.socialgroups.dto.FriendRosterSummaryDto;
import net.firedevops.firemud.socialgroups.dto.FriendRosterViewDto;
import net.firedevops.firemud.socialgroups.dto.SendMailRequest;
import net.firedevops.firemud.socialgroups.dto.SendMessageRequestDto;
import net.firedevops.firemud.socialgroups.security.SocialAccessGuard;
import net.firedevops.firemud.socialgroups.service.ChatService;
import net.firedevops.firemud.socialgroups.service.FriendService;
import net.firedevops.firemud.socialgroups.service.GuildService;
import net.firedevops.firemud.socialgroups.service.MailService;
import net.firedevops.firemud.socialgroups.service.PingService;
import net.firedevops.firemud.socialgroups.v1.AddFriendResponse;
import net.firedevops.firemud.socialgroups.v1.CreateGuildResponse;
import net.firedevops.firemud.socialgroups.v1.FriendPresenceActivityState;
import net.firedevops.firemud.socialgroups.v1.FriendPresenceEntry;
import net.firedevops.firemud.socialgroups.v1.FriendPresenceVisibilityPolicy;
import net.firedevops.firemud.socialgroups.v1.FriendRecentPresenceDisposition;
import net.firedevops.firemud.socialgroups.v1.FriendRosterEntry;
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
import net.firedevops.firemud.socialgroups.v1.PingRequest;
import net.firedevops.firemud.socialgroups.v1.PingResponse;
import net.firedevops.firemud.socialgroups.v1.RemoveFriendByOrdinalRequest;
import net.firedevops.firemud.socialgroups.v1.RemoveFriendByOrdinalResponse;
import net.firedevops.firemud.socialgroups.v1.RemoveFriendRequest;
import net.firedevops.firemud.socialgroups.v1.RemoveFriendResponse;
import net.firedevops.firemud.socialgroups.v1.SendMailResponse;
import net.firedevops.firemud.socialgroups.v1.SendMessageRequest;
import net.firedevops.firemud.socialgroups.v1.SendMessageResponse;
import net.firedevops.firemud.socialgroups.v1.SocialGroupsServiceGrpc;
import net.firedevops.firemud.socialgroups.v1.UpdateFriendPresencePolicyRequest;
import net.firedevops.firemud.socialgroups.v1.UpdateFriendPresencePolicyResponse;
import org.slf4j.Logger;
import org.springframework.grpc.server.service.GrpcService;

/** gRPC service implementation for the SocialGroupsService API. */
@GrpcService
@RequiredArgsConstructor
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected services are managed by Spring")
public class SocialGroupsGrpcService extends SocialGroupsServiceGrpc.SocialGroupsServiceImplBase {
  private static final Logger logger = LoggingUtil.getLogger(SocialGroupsGrpcService.class);
  private final PingService pingService;
  private final ChatService chatService;
  private final GuildService guildService;
  private final FriendService friendService;
  private final MailService mailService;
  private final SocialAccessGuard socialAccessGuard;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "MeterRegistry is thread-safe and only stored")
  private final MeterRegistry meterRegistry;

  @Override
  @Timed(value = "socialGrpc.ping")
  public void ping(PingRequest request, StreamObserver<PingResponse> responseObserver) {
    String msg = pingService.ping();
    PingResponse response = PingResponse.newBuilder().setMessage(msg).build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "socialGrpc.sendMessage")
  public void sendMessage(
      net.firedevops.firemud.socialgroups.v1.SendMessageRequest request,
      StreamObserver<SendMessageResponse> responseObserver) {
    try {
      AccountScope senderScope =
          requireAccountScope(request.getTenantId(), request.getSenderId(), "senderId");
      SendMessageRequestDto dto =
          new SendMessageRequestDto(
              senderScope.tenantId(),
              senderScope.accountId(),
              mapChatType(request),
              request.getChannelId(),
              RequestIdValidation.parseOptionalPositiveLong(
                  request.getRecipientId(), "recipientId"),
              RequestIdValidation.parseOptionalPositiveLong(request.getGuildId(), "guildId"),
              RequestIdValidation.parseOptionalPositiveLong(request.getCityId(), "cityId"),
              request.getContent(),
              request.getEffectId().isEmpty() ? null : request.getEffectId());
      chatService.sendMessage(dto);
      SendMessageResponse response = SendMessageResponse.newBuilder().setSuccess(true).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      SendMessageResponse response =
          SendMessageResponse.newBuilder()
              .setSuccess(false)
              .setError(invalidArgument("SendMessage", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      SendMessageResponse response =
          SendMessageResponse.newBuilder()
              .setSuccess(false)
              .setError(internal("SendMessage", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  private net.firedevops.firemud.socialgroups.enums.ChatType mapChatType(
      SendMessageRequest request) {
    return switch (request.getType()) {
      case CHAT_TYPE_SAY -> net.firedevops.firemud.socialgroups.enums.ChatType.SAY;
      case CHAT_TYPE_TELL -> net.firedevops.firemud.socialgroups.enums.ChatType.TELL;
      case CHAT_TYPE_WHISPER -> net.firedevops.firemud.socialgroups.enums.ChatType.WHISPER;
      case CHAT_TYPE_GUILD -> net.firedevops.firemud.socialgroups.enums.ChatType.GUILD;
      case CHAT_TYPE_CITY -> net.firedevops.firemud.socialgroups.enums.ChatType.CITY;
      case CHAT_TYPE_ACCOUNT -> net.firedevops.firemud.socialgroups.enums.ChatType.ACCOUNT;
      case UNRECOGNIZED, CHAT_TYPE_UNSPECIFIED ->
          throw new IllegalArgumentException("Unsupported chat type: " + request.getType());
    };
  }

  @Override
  @Timed(value = "socialGrpc.createGuild")
  public void createGuild(
      net.firedevops.firemud.socialgroups.v1.CreateGuildRequest request,
      StreamObserver<CreateGuildResponse> responseObserver) {
    try {
      AccountScope ownerScope =
          requireAccountScope(request.getTenantId(), request.getOwnerAccountId(), "ownerAccountId");
      CreateGuildRequest dto =
          new CreateGuildRequest(ownerScope.tenantId(), ownerScope.accountId(), request.getName());
      var guild = guildService.createGuild(dto);
      CreateGuildResponse response =
          CreateGuildResponse.newBuilder().setGuildId(guild.id().toString()).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      CreateGuildResponse response =
          CreateGuildResponse.newBuilder()
              .setError(invalidArgument("CreateGuild", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      CreateGuildResponse response =
          CreateGuildResponse.newBuilder().setError(internal("CreateGuild", ex)).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "socialGrpc.addFriend")
  public void addFriend(
      net.firedevops.firemud.socialgroups.v1.AddFriendRequest request,
      StreamObserver<AddFriendResponse> responseObserver) {
    try {
      AccountScope accountScope =
          requireAccountScope(request.getTenantId(), request.getAccountId(), "accountId");
      AddFriendRequest dto =
          new AddFriendRequest(
              accountScope.tenantId(),
              accountScope.accountId(),
              RequestIdValidation.requirePositiveLong(
                  request.getFriendAccountId(), "friendAccountId"));
      friendService.addFriend(dto);
      AddFriendResponse response = AddFriendResponse.newBuilder().setSuccess(true).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      AddFriendResponse response =
          AddFriendResponse.newBuilder()
              .setSuccess(false)
              .setError(invalidArgument("AddFriend", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      AddFriendResponse response =
          AddFriendResponse.newBuilder()
              .setSuccess(false)
              .setError(internal("AddFriend", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "socialGrpc.removeFriend")
  public void removeFriend(
      RemoveFriendRequest request, StreamObserver<RemoveFriendResponse> responseObserver) {
    try {
      AccountScope accountScope =
          requireAccountScope(request.getTenantId(), request.getAccountId(), "accountId");
      friendService.removeFriend(
          accountScope.tenantId(),
          accountScope.accountId(),
          RequestIdValidation.requirePositiveLong(request.getFriendAccountId(), "friendAccountId"));
      responseObserver.onNext(RemoveFriendResponse.newBuilder().setSuccess(true).build());
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      RemoveFriendResponse response =
          RemoveFriendResponse.newBuilder()
              .setSuccess(false)
              .setError(invalidArgument("RemoveFriend", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (AuthorizationException ex) {
      RemoveFriendResponse response =
          RemoveFriendResponse.newBuilder()
              .setSuccess(false)
              .setError(permissionDenied("RemoveFriend", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      RemoveFriendResponse response =
          RemoveFriendResponse.newBuilder()
              .setSuccess(false)
              .setError(internal("RemoveFriend", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "socialGrpc.getFriend")
  public void getFriend(
      GetFriendRequest request, StreamObserver<GetFriendResponse> responseObserver) {
    try {
      AccountScope accountScope =
          requireAccountScope(request.getTenantId(), request.getAccountId(), "accountId");
      long friendAccountId =
          RequestIdValidation.requirePositiveLong(request.getFriendAccountId(), "friendAccountId");
      FriendRosterEntryDto friend =
          friendService
              .getFriend(accountScope.tenantId(), accountScope.accountId(), friendAccountId)
              .orElse(null);
      if (friend == null) {
        responseObserver.onNext(
            GetFriendResponse.newBuilder()
                .setError(
                    notFound("GetFriend", "Friend not found for accountId=" + friendAccountId))
                .build());
        responseObserver.onCompleted();
        return;
      }
      responseObserver.onNext(
          GetFriendResponse.newBuilder().setFriend(mapFriendRosterEntry(friend)).build());
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      responseObserver.onNext(
          GetFriendResponse.newBuilder()
              .setError(invalidArgument("GetFriend", ex.getMessage()))
              .build());
      responseObserver.onCompleted();
    } catch (AuthorizationException ex) {
      responseObserver.onNext(
          GetFriendResponse.newBuilder()
              .setError(permissionDenied("GetFriend", ex.getMessage()))
              .build());
      responseObserver.onCompleted();
    } catch (Exception ex) {
      responseObserver.onNext(
          GetFriendResponse.newBuilder().setError(internal("GetFriend", ex)).build());
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "socialGrpc.getFriendByOrdinal")
  public void getFriendByOrdinal(
      GetFriendByOrdinalRequest request,
      StreamObserver<GetFriendByOrdinalResponse> responseObserver) {
    try {
      AccountScope accountScope =
          requireAccountScope(request.getTenantId(), request.getAccountId(), "accountId");
      FriendRosterEntryDto friend =
          friendService
              .getFriendByOrdinal(
                  accountScope.tenantId(), accountScope.accountId(), request.getOrdinal())
              .orElse(null);
      if (friend == null) {
        responseObserver.onNext(
            GetFriendByOrdinalResponse.newBuilder()
                .setError(
                    notFound(
                        "GetFriendByOrdinal",
                        "Friend not found for ordinal=" + request.getOrdinal()))
                .build());
        responseObserver.onCompleted();
        return;
      }
      responseObserver.onNext(
          GetFriendByOrdinalResponse.newBuilder().setFriend(mapFriendRosterEntry(friend)).build());
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      responseObserver.onNext(
          GetFriendByOrdinalResponse.newBuilder()
              .setError(invalidArgument("GetFriendByOrdinal", ex.getMessage()))
              .build());
      responseObserver.onCompleted();
    } catch (AuthorizationException ex) {
      responseObserver.onNext(
          GetFriendByOrdinalResponse.newBuilder()
              .setError(permissionDenied("GetFriendByOrdinal", ex.getMessage()))
              .build());
      responseObserver.onCompleted();
    } catch (Exception ex) {
      responseObserver.onNext(
          GetFriendByOrdinalResponse.newBuilder()
              .setError(internal("GetFriendByOrdinal", ex))
              .build());
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "socialGrpc.listFriends")
  public void listFriends(
      ListFriendsRequest request, StreamObserver<ListFriendsResponse> responseObserver) {
    try {
      AccountScope accountScope =
          requireAccountScope(request.getTenantId(), request.getAccountId(), "accountId");
      FriendRosterViewDto friends =
          friendService.listFriends(
              accountScope.tenantId(),
              accountScope.accountId(),
              mapRosterFilter(request.getFilter()));
      ListFriendsResponse.Builder response = ListFriendsResponse.newBuilder();
      for (FriendRosterEntryDto friend : friends.friends()) {
        response.addFriends(mapFriendRosterEntry(friend));
      }
      response
          .setFilter(mapRosterFilter(friends.filter()))
          .setTotalCount(friends.totalCount())
          .setMatchCount(friends.matchCount());
      responseObserver.onNext(response.build());
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      ListFriendsResponse response =
          ListFriendsResponse.newBuilder()
              .setError(invalidArgument("ListFriends", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (AuthorizationException ex) {
      ListFriendsResponse response =
          ListFriendsResponse.newBuilder()
              .setError(permissionDenied("ListFriends", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      ListFriendsResponse response =
          ListFriendsResponse.newBuilder().setError(internal("ListFriends", ex)).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "socialGrpc.removeFriendByOrdinal")
  public void removeFriendByOrdinal(
      RemoveFriendByOrdinalRequest request,
      StreamObserver<RemoveFriendByOrdinalResponse> responseObserver) {
    try {
      AccountScope accountScope =
          requireAccountScope(request.getTenantId(), request.getAccountId(), "accountId");
      FriendRosterEntryDto removed =
          friendService
              .removeFriendByOrdinal(
                  accountScope.tenantId(), accountScope.accountId(), request.getOrdinal())
              .orElse(null);
      if (removed == null) {
        responseObserver.onNext(
            RemoveFriendByOrdinalResponse.newBuilder()
                .setSuccess(false)
                .setError(
                    notFound(
                        "RemoveFriendByOrdinal",
                        "Friend not found for ordinal=" + request.getOrdinal()))
                .build());
        responseObserver.onCompleted();
        return;
      }
      responseObserver.onNext(
          RemoveFriendByOrdinalResponse.newBuilder()
              .setSuccess(true)
              .setRemovedFriend(mapFriendRosterEntry(removed))
              .build());
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      responseObserver.onNext(
          RemoveFriendByOrdinalResponse.newBuilder()
              .setSuccess(false)
              .setError(invalidArgument("RemoveFriendByOrdinal", ex.getMessage()))
              .build());
      responseObserver.onCompleted();
    } catch (AuthorizationException ex) {
      responseObserver.onNext(
          RemoveFriendByOrdinalResponse.newBuilder()
              .setSuccess(false)
              .setError(permissionDenied("RemoveFriendByOrdinal", ex.getMessage()))
              .build());
      responseObserver.onCompleted();
    } catch (Exception ex) {
      responseObserver.onNext(
          RemoveFriendByOrdinalResponse.newBuilder()
              .setSuccess(false)
              .setError(internal("RemoveFriendByOrdinal", ex))
              .build());
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "socialGrpc.getFriendRosterSummary")
  public void getFriendRosterSummary(
      GetFriendRosterSummaryRequest request,
      StreamObserver<GetFriendRosterSummaryResponse> responseObserver) {
    try {
      AccountScope accountScope =
          requireAccountScope(request.getTenantId(), request.getAccountId(), "accountId");
      FriendRosterSummaryDto summary =
          friendService.getFriendRosterSummary(accountScope.tenantId(), accountScope.accountId());
      responseObserver.onNext(
          GetFriendRosterSummaryResponse.newBuilder()
              .setSummary(mapRosterSummary(summary))
              .build());
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      responseObserver.onNext(
          GetFriendRosterSummaryResponse.newBuilder()
              .setError(invalidArgument("GetFriendRosterSummary", ex.getMessage()))
              .build());
      responseObserver.onCompleted();
    } catch (AuthorizationException ex) {
      responseObserver.onNext(
          GetFriendRosterSummaryResponse.newBuilder()
              .setError(permissionDenied("GetFriendRosterSummary", ex.getMessage()))
              .build());
      responseObserver.onCompleted();
    } catch (Exception ex) {
      responseObserver.onNext(
          GetFriendRosterSummaryResponse.newBuilder()
              .setError(internal("GetFriendRosterSummary", ex))
              .build());
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "socialGrpc.listFriendPresence")
  public void listFriendPresence(
      ListFriendPresenceRequest request,
      StreamObserver<ListFriendPresenceResponse> responseObserver) {
    try {
      AccountScope accountScope =
          requireAccountScope(request.getTenantId(), request.getAccountId(), "accountId");
      FriendPresenceViewDto presences =
          friendService.listFriendPresence(
              accountScope.tenantId(),
              accountScope.accountId(),
              mapRosterFilter(request.getFilter()));
      ListFriendPresenceResponse.Builder response = ListFriendPresenceResponse.newBuilder();
      for (FriendPresenceDto presence : presences.presences()) {
        response.addPresences(mapPresence(presence));
      }
      response
          .setFilter(mapRosterFilter(presences.filter()))
          .setTotalCount(presences.totalCount())
          .setMatchCount(presences.matchCount());
      responseObserver.onNext(response.build());
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      ListFriendPresenceResponse response =
          ListFriendPresenceResponse.newBuilder()
              .setError(invalidArgument("ListFriendPresence", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (AuthorizationException ex) {
      ListFriendPresenceResponse response =
          ListFriendPresenceResponse.newBuilder()
              .setError(permissionDenied("ListFriendPresence", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      ListFriendPresenceResponse response =
          ListFriendPresenceResponse.newBuilder()
              .setError(internal("ListFriendPresence", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "socialGrpc.getFriendPresencePolicy")
  public void getFriendPresencePolicy(
      GetFriendPresencePolicyRequest request,
      StreamObserver<GetFriendPresencePolicyResponse> responseObserver) {
    try {
      AccountScope accountScope =
          requireAccountScope(request.getTenantId(), request.getAccountId(), "accountId");
      FriendPresencePolicyViewDto policy =
          friendService.getFriendPresencePolicy(accountScope.tenantId(), accountScope.accountId());
      responseObserver.onNext(
          GetFriendPresencePolicyResponse.newBuilder()
              .setCurrentPolicy(mapVisibilityPolicy(policy.currentPolicy()))
              .build());
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      responseObserver.onNext(
          GetFriendPresencePolicyResponse.newBuilder()
              .setError(invalidArgument("GetFriendPresencePolicy", ex.getMessage()))
              .build());
      responseObserver.onCompleted();
    } catch (IllegalStateException ex) {
      responseObserver.onNext(
          GetFriendPresencePolicyResponse.newBuilder()
              .setError(unavailable("GetFriendPresencePolicy", ex.getMessage()))
              .build());
      responseObserver.onCompleted();
    } catch (AuthorizationException ex) {
      responseObserver.onNext(
          GetFriendPresencePolicyResponse.newBuilder()
              .setError(permissionDenied("GetFriendPresencePolicy", ex.getMessage()))
              .build());
      responseObserver.onCompleted();
    } catch (Exception ex) {
      responseObserver.onNext(
          GetFriendPresencePolicyResponse.newBuilder()
              .setError(internal("GetFriendPresencePolicy", ex))
              .build());
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "socialGrpc.updateFriendPresencePolicy")
  public void updateFriendPresencePolicy(
      UpdateFriendPresencePolicyRequest request,
      StreamObserver<UpdateFriendPresencePolicyResponse> responseObserver) {
    try {
      AccountScope accountScope =
          requireAccountScope(request.getTenantId(), request.getAccountId(), "accountId");
      FriendPresencePolicyViewDto policy =
          friendService.updateFriendPresencePolicy(
              accountScope.tenantId(),
              accountScope.accountId(),
              mapVisibilityPolicy(request.getVisibilityPolicy()));
      responseObserver.onNext(
          UpdateFriendPresencePolicyResponse.newBuilder()
              .setSuccess(true)
              .setCurrentPolicy(mapVisibilityPolicy(policy.currentPolicy()))
              .build());
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      responseObserver.onNext(
          UpdateFriendPresencePolicyResponse.newBuilder()
              .setSuccess(false)
              .setError(invalidArgument("UpdateFriendPresencePolicy", ex.getMessage()))
              .build());
      responseObserver.onCompleted();
    } catch (IllegalStateException ex) {
      responseObserver.onNext(
          UpdateFriendPresencePolicyResponse.newBuilder()
              .setSuccess(false)
              .setError(unavailable("UpdateFriendPresencePolicy", ex.getMessage()))
              .build());
      responseObserver.onCompleted();
    } catch (AuthorizationException ex) {
      responseObserver.onNext(
          UpdateFriendPresencePolicyResponse.newBuilder()
              .setSuccess(false)
              .setError(permissionDenied("UpdateFriendPresencePolicy", ex.getMessage()))
              .build());
      responseObserver.onCompleted();
    } catch (Exception ex) {
      responseObserver.onNext(
          UpdateFriendPresencePolicyResponse.newBuilder()
              .setSuccess(false)
              .setError(internal("UpdateFriendPresencePolicy", ex))
              .build());
      responseObserver.onCompleted();
    }
  }

  private FriendPresenceEntry mapPresence(FriendPresenceDto presence) {
    FriendPresenceEntry.Builder entry =
        FriendPresenceEntry.newBuilder()
            .setFriendAccountId(Long.toString(presence.friendAccountId()))
            .setOnline(presence.online());
    if (presence.gameInstanceId() != null) {
      entry.setGameInstanceId(Long.toString(presence.gameInstanceId()));
    }
    if (presence.worldSlug() != null && !presence.worldSlug().isBlank()) {
      entry.setWorldSlug(presence.worldSlug());
    }
    if (presence.worldDisplayName() != null && !presence.worldDisplayName().isBlank()) {
      entry.setWorldDisplayName(presence.worldDisplayName());
    }
    if (presence.realmSlug() != null && !presence.realmSlug().isBlank()) {
      entry.setRealmSlug(presence.realmSlug());
    }
    if (presence.realmDisplayName() != null && !presence.realmDisplayName().isBlank()) {
      entry.setRealmDisplayName(presence.realmDisplayName());
    }
    if (presence.pointerVersion() != null && presence.pointerVersion() > 0L) {
      entry.setPointerVersion(presence.pointerVersion());
    }
    if (presence.playableStateScope() != null && !presence.playableStateScope().isBlank()) {
      entry.setPlayableStateScope(mapPlayableStateScope(presence.playableStateScope()));
    }
    if (presence.visibilityPolicy() != null && !presence.visibilityPolicy().isBlank()) {
      entry.setVisibilityPolicy(mapVisibilityPolicy(presence.visibilityPolicy()));
    }
    if (presence.characterId() != null) {
      entry.setCharacterId(Long.toString(presence.characterId()));
    }
    if (presence.characterName() != null && !presence.characterName().isBlank()) {
      entry.setCharacterName(presence.characterName());
    }
    if (presence.activityState() != null) {
      entry.setActivityState(mapActivityState(presence.activityState()));
    }
    if (presence.lastSeenAt() != null) {
      entry.setLastSeenAtMs(presence.lastSeenAt().toEpochMilli());
    }
    if (presence.recentDisposition() != null) {
      entry.setRecentDisposition(mapRecentDisposition(presence.recentDisposition()));
    }
    return entry.build();
  }

  private FriendRosterEntry mapFriendRosterEntry(FriendRosterEntryDto friend) {
    FriendRosterEntry.Builder entry =
        FriendRosterEntry.newBuilder()
            .setOrdinal(friend.ordinal())
            .setFriendAccountId(Long.toString(friend.friendAccountId()))
            .setPresence(mapPresence(friend.presence()));
    if (friend.friendLinkId() != null) {
      entry.setFriendLinkId(Long.toString(friend.friendLinkId()));
    }
    if (friend.tenantId() != null) {
      entry.setTenantId(Long.toString(friend.tenantId()));
    }
    if (friend.accountId() != null) {
      entry.setAccountId(Long.toString(friend.accountId()));
    }
    if (friend.status() != null && !friend.status().isBlank()) {
      entry.setStatus(friend.status());
    }
    if (friend.createdAt() != null) {
      entry.setCreatedAtMs(friend.createdAt().toEpochMilli());
    }
    return entry.build();
  }

  private FriendRosterSummary mapRosterSummary(FriendRosterSummaryDto summary) {
    return FriendRosterSummary.newBuilder()
        .setTotalCount(summary.totalCount())
        .setOnlineCount(summary.onlineCount())
        .setOfflineCount(summary.offlineCount())
        .setRecentCount(summary.recentCount())
        .setPublicCount(summary.publicCount())
        .setFriendsOnlyCount(summary.friendsOnlyCount())
        .setPrivateCount(summary.privateCount())
        .setHiddenStaffCount(summary.hiddenStaffCount())
        .setUnspecifiedVisibilityCount(summary.unspecifiedVisibilityCount())
        .setSharedCount(summary.sharedCount())
        .setIsolatedCount(summary.isolatedCount())
        .setUnspecifiedScopeCount(summary.unspecifiedScopeCount())
        .build();
  }

  private FriendRosterFilter mapRosterFilter(
      net.firedevops.firemud.socialgroups.v1.FriendRosterFilter filter) {
    return switch (filter) {
      case FRIEND_ROSTER_FILTER_ONLINE -> FriendRosterFilter.ONLINE;
      case FRIEND_ROSTER_FILTER_OFFLINE -> FriendRosterFilter.OFFLINE;
      case FRIEND_ROSTER_FILTER_RECENT -> FriendRosterFilter.RECENT;
      case FRIEND_ROSTER_FILTER_PUBLIC -> FriendRosterFilter.PUBLIC;
      case FRIEND_ROSTER_FILTER_FRIENDS_ONLY -> FriendRosterFilter.FRIENDS_ONLY;
      case FRIEND_ROSTER_FILTER_PRIVATE -> FriendRosterFilter.PRIVATE;
      case FRIEND_ROSTER_FILTER_HIDDEN_STAFF -> FriendRosterFilter.HIDDEN_STAFF;
      case FRIEND_ROSTER_FILTER_UNSPECIFIED_VISIBILITY -> FriendRosterFilter.UNSPECIFIED_VISIBILITY;
      case FRIEND_ROSTER_FILTER_SHARED -> FriendRosterFilter.SHARED;
      case FRIEND_ROSTER_FILTER_ISOLATED -> FriendRosterFilter.ISOLATED;
      case FRIEND_ROSTER_FILTER_UNSPECIFIED_SCOPE -> FriendRosterFilter.UNSPECIFIED_SCOPE;
      case FRIEND_ROSTER_FILTER_UNSPECIFIED, FRIEND_ROSTER_FILTER_ALL, UNRECOGNIZED ->
          FriendRosterFilter.ALL;
    };
  }

  private net.firedevops.firemud.socialgroups.v1.FriendRosterFilter mapRosterFilter(
      FriendRosterFilter filter) {
    return switch (filter) {
      case ONLINE ->
          net.firedevops.firemud.socialgroups.v1.FriendRosterFilter.FRIEND_ROSTER_FILTER_ONLINE;
      case OFFLINE ->
          net.firedevops.firemud.socialgroups.v1.FriendRosterFilter.FRIEND_ROSTER_FILTER_OFFLINE;
      case RECENT ->
          net.firedevops.firemud.socialgroups.v1.FriendRosterFilter.FRIEND_ROSTER_FILTER_RECENT;
      case PUBLIC ->
          net.firedevops.firemud.socialgroups.v1.FriendRosterFilter.FRIEND_ROSTER_FILTER_PUBLIC;
      case FRIENDS_ONLY ->
          net.firedevops.firemud.socialgroups.v1.FriendRosterFilter
              .FRIEND_ROSTER_FILTER_FRIENDS_ONLY;
      case PRIVATE ->
          net.firedevops.firemud.socialgroups.v1.FriendRosterFilter.FRIEND_ROSTER_FILTER_PRIVATE;
      case HIDDEN_STAFF ->
          net.firedevops.firemud.socialgroups.v1.FriendRosterFilter
              .FRIEND_ROSTER_FILTER_HIDDEN_STAFF;
      case UNSPECIFIED_VISIBILITY ->
          net.firedevops.firemud.socialgroups.v1.FriendRosterFilter
              .FRIEND_ROSTER_FILTER_UNSPECIFIED_VISIBILITY;
      case SHARED ->
          net.firedevops.firemud.socialgroups.v1.FriendRosterFilter.FRIEND_ROSTER_FILTER_SHARED;
      case ISOLATED ->
          net.firedevops.firemud.socialgroups.v1.FriendRosterFilter.FRIEND_ROSTER_FILTER_ISOLATED;
      case UNSPECIFIED_SCOPE ->
          net.firedevops.firemud.socialgroups.v1.FriendRosterFilter
              .FRIEND_ROSTER_FILTER_UNSPECIFIED_SCOPE;
      case ALL ->
          net.firedevops.firemud.socialgroups.v1.FriendRosterFilter.FRIEND_ROSTER_FILTER_ALL;
    };
  }

  @Override
  @Timed(value = "socialGrpc.sendMail")
  public void sendMail(
      net.firedevops.firemud.socialgroups.v1.SendMailRequest request,
      StreamObserver<SendMailResponse> responseObserver) {
    try {
      AccountScope senderScope =
          requireAccountScope(
              request.getTenantId(), request.getSenderAccountId(), "senderAccountId");
      SendMailRequest dto =
          new SendMailRequest(
              senderScope.tenantId(),
              senderScope.accountId(),
              RequestIdValidation.requirePositiveLong(
                  request.getRecipientAccountId(), "recipientAccountId"),
              request.getSubject(),
              request.getContent());
      mailService.sendMail(dto);
      SendMailResponse response = SendMailResponse.newBuilder().setSuccess(true).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      SendMailResponse response =
          SendMailResponse.newBuilder()
              .setSuccess(false)
              .setError(invalidArgument("SendMail", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (AuthorizationException ex) {
      SendMailResponse response =
          SendMailResponse.newBuilder()
              .setSuccess(false)
              .setError(permissionDenied("SendMail", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      SendMailResponse response =
          SendMailResponse.newBuilder()
              .setSuccess(false)
              .setError(internal("SendMail", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  private net.firedevops.firemud.shared.v1.ErrorDetail invalidArgument(
      String operation, String message) {
    return GrpcAppErrors.error(meterRegistry, logger, operation, "INVALID_ARGUMENT", message);
  }

  private net.firedevops.firemud.shared.v1.ErrorDetail notFound(String operation, String message) {
    return GrpcAppErrors.error(meterRegistry, logger, operation, "NOT_FOUND", message);
  }

  private net.firedevops.firemud.shared.v1.ErrorDetail permissionDenied(
      String operation, String message) {
    return GrpcAppErrors.error(meterRegistry, logger, operation, "PERMISSION_DENIED", message);
  }

  private net.firedevops.firemud.shared.v1.ErrorDetail unavailable(
      String operation, String message) {
    return GrpcAppErrors.error(meterRegistry, logger, operation, "UNAVAILABLE", message);
  }

  private net.firedevops.firemud.shared.v1.ErrorDetail internal(String operation, Exception ex) {
    return GrpcAppErrors.internal(meterRegistry, logger, operation, ex);
  }

  private void requireAccountAccess(long tenantId, long accountId) {
    if (socialAccessGuard.hasAccountAccess(tenantId, accountId)) {
      return;
    }
    throw new AuthorizationException("Account access required");
  }

  private AccountScope requireAccountScope(
      String tenantIdText, String accountIdText, String accountFieldName) {
    long tenantId = RequestIdValidation.requirePositiveLong(tenantIdText, "tenantId");
    long accountId = RequestIdValidation.requirePositiveLong(accountIdText, accountFieldName);
    requireAccountAccess(tenantId, accountId);
    return new AccountScope(tenantId, accountId);
  }

  private record AccountScope(long tenantId, long accountId) {}

  private FriendPresenceActivityState mapActivityState(
      net.firedevops.firemud.socialgroups.dto.FriendPresenceActivityState activityState) {
    return switch (activityState) {
      case ACTIVE -> FriendPresenceActivityState.FRIEND_PRESENCE_ACTIVITY_STATE_ACTIVE;
      case AUTO_AFK -> FriendPresenceActivityState.FRIEND_PRESENCE_ACTIVITY_STATE_AUTO_AFK;
      case EXPLICIT_AFK -> FriendPresenceActivityState.FRIEND_PRESENCE_ACTIVITY_STATE_EXPLICIT_AFK;
    };
  }

  private FriendRecentPresenceDisposition mapRecentDisposition(
      net.firedevops.firemud.socialgroups.dto.FriendRecentPresenceDisposition disposition) {
    return switch (disposition) {
      case TRANSPORT_LOSS ->
          net.firedevops.firemud.socialgroups.v1.FriendRecentPresenceDisposition
              .FRIEND_RECENT_PRESENCE_DISPOSITION_TRANSPORT_LOSS;
      case LOGOUT ->
          net.firedevops.firemud.socialgroups.v1.FriendRecentPresenceDisposition
              .FRIEND_RECENT_PRESENCE_DISPOSITION_LOGOUT;
      case TAKEOVER ->
          net.firedevops.firemud.socialgroups.v1.FriendRecentPresenceDisposition
              .FRIEND_RECENT_PRESENCE_DISPOSITION_TAKEOVER;
    };
  }

  private FriendPresenceVisibilityPolicy mapVisibilityPolicy(String visibilityPolicy) {
    return switch (visibilityPolicy) {
      case "PUBLIC" -> FriendPresenceVisibilityPolicy.FRIEND_PRESENCE_VISIBILITY_POLICY_PUBLIC;
      case "FRIENDS_ONLY" ->
          FriendPresenceVisibilityPolicy.FRIEND_PRESENCE_VISIBILITY_POLICY_FRIENDS_ONLY;
      case "PRIVATE" -> FriendPresenceVisibilityPolicy.FRIEND_PRESENCE_VISIBILITY_POLICY_PRIVATE;
      case "HIDDEN_STAFF" ->
          FriendPresenceVisibilityPolicy.FRIEND_PRESENCE_VISIBILITY_POLICY_HIDDEN_STAFF;
      default -> FriendPresenceVisibilityPolicy.FRIEND_PRESENCE_VISIBILITY_POLICY_UNSPECIFIED;
    };
  }

  private FriendPresenceVisibilityPolicy mapVisibilityPolicy(
      FriendPresenceVisibilityPolicyValue visibilityPolicy) {
    return switch (visibilityPolicy) {
      case PUBLIC -> FriendPresenceVisibilityPolicy.FRIEND_PRESENCE_VISIBILITY_POLICY_PUBLIC;
      case FRIENDS_ONLY ->
          FriendPresenceVisibilityPolicy.FRIEND_PRESENCE_VISIBILITY_POLICY_FRIENDS_ONLY;
      case PRIVATE -> FriendPresenceVisibilityPolicy.FRIEND_PRESENCE_VISIBILITY_POLICY_PRIVATE;
      case HIDDEN_STAFF ->
          FriendPresenceVisibilityPolicy.FRIEND_PRESENCE_VISIBILITY_POLICY_HIDDEN_STAFF;
    };
  }

  private FriendPresenceVisibilityPolicyValue mapVisibilityPolicy(
      FriendPresenceVisibilityPolicy visibilityPolicy) {
    return switch (visibilityPolicy) {
      case FRIEND_PRESENCE_VISIBILITY_POLICY_PUBLIC -> FriendPresenceVisibilityPolicyValue.PUBLIC;
      case FRIEND_PRESENCE_VISIBILITY_POLICY_FRIENDS_ONLY ->
          FriendPresenceVisibilityPolicyValue.FRIENDS_ONLY;
      case FRIEND_PRESENCE_VISIBILITY_POLICY_PRIVATE -> FriendPresenceVisibilityPolicyValue.PRIVATE;
      case FRIEND_PRESENCE_VISIBILITY_POLICY_HIDDEN_STAFF ->
          FriendPresenceVisibilityPolicyValue.HIDDEN_STAFF;
      case FRIEND_PRESENCE_VISIBILITY_POLICY_UNSPECIFIED, UNRECOGNIZED ->
          throw new IllegalArgumentException("Friend presence visibility policy is required");
    };
  }

  private net.firedevops.firemud.entitymanagement.v1.PlayableStateScope mapPlayableStateScope(
      String playableStateScope) {
    return switch (playableStateScope) {
      case "SHARED" ->
          net.firedevops.firemud.entitymanagement.v1.PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED;
      case "ISOLATED" ->
          net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
              .PLAYABLE_STATE_SCOPE_ISOLATED;
      default ->
          net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
              .PLAYABLE_STATE_SCOPE_UNSPECIFIED;
    };
  }

  private static final class AuthorizationException extends RuntimeException {
    private AuthorizationException(String message) {
      super(message);
    }
  }
}
