package net.firedevops.firemud.socialgroups.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.common.grpc.GrpcAppErrors;
import net.firedevops.firemud.socialgroups.dto.AddFriendRequest;
import net.firedevops.firemud.socialgroups.dto.CreateGuildRequest;
import net.firedevops.firemud.socialgroups.dto.FriendPresenceDto;
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
import net.firedevops.firemud.socialgroups.v1.ListFriendPresenceRequest;
import net.firedevops.firemud.socialgroups.v1.ListFriendPresenceResponse;
import net.firedevops.firemud.socialgroups.v1.PingRequest;
import net.firedevops.firemud.socialgroups.v1.PingResponse;
import net.firedevops.firemud.socialgroups.v1.SendMailResponse;
import net.firedevops.firemud.socialgroups.v1.SendMessageRequest;
import net.firedevops.firemud.socialgroups.v1.SendMessageResponse;
import net.firedevops.firemud.socialgroups.v1.SocialGroupsServiceGrpc;
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
      requireAccountAccess(
          Long.parseLong(request.getTenantId()), Long.parseLong(request.getSenderId()));
      SendMessageRequestDto dto =
          new SendMessageRequestDto(
              Long.valueOf(request.getTenantId()),
              Long.valueOf(request.getSenderId()),
              mapChatType(request),
              request.getChannelId(),
              request.getRecipientId().isEmpty() ? null : Long.valueOf(request.getRecipientId()),
              request.getGuildId().isEmpty() ? null : Long.valueOf(request.getGuildId()),
              request.getCityId().isEmpty() ? null : Long.valueOf(request.getCityId()),
              request.getContent());
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
      requireAccountAccess(
          Long.parseLong(request.getTenantId()), Long.parseLong(request.getOwnerAccountId()));
      CreateGuildRequest dto =
          new CreateGuildRequest(
              Long.valueOf(request.getTenantId()),
              Long.valueOf(request.getOwnerAccountId()),
              request.getName());
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
      requireAccountAccess(
          Long.parseLong(request.getTenantId()), Long.parseLong(request.getAccountId()));
      AddFriendRequest dto =
          new AddFriendRequest(
              Long.valueOf(request.getTenantId()),
              Long.valueOf(request.getAccountId()),
              Long.valueOf(request.getFriendAccountId()),
              request.getAccountLevel());
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
  @Timed(value = "socialGrpc.listFriendPresence")
  public void listFriendPresence(
      ListFriendPresenceRequest request,
      StreamObserver<ListFriendPresenceResponse> responseObserver) {
    try {
      long tenantId = Long.parseLong(request.getTenantId());
      long accountId = Long.parseLong(request.getAccountId());
      requireAccountAccess(tenantId, accountId);
      List<FriendPresenceDto> presences = friendService.listFriendPresence(tenantId, accountId);
      ListFriendPresenceResponse.Builder response = ListFriendPresenceResponse.newBuilder();
      for (FriendPresenceDto presence : presences) {
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
        response.addPresences(entry.build());
      }
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
  @Timed(value = "socialGrpc.sendMail")
  public void sendMail(
      net.firedevops.firemud.socialgroups.v1.SendMailRequest request,
      StreamObserver<SendMailResponse> responseObserver) {
    try {
      requireAccountAccess(
          Long.parseLong(request.getTenantId()), Long.parseLong(request.getSenderAccountId()));
      SendMailRequest dto =
          new SendMailRequest(
              Long.valueOf(request.getTenantId()),
              Long.valueOf(request.getSenderAccountId()),
              Long.valueOf(request.getRecipientAccountId()),
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

  private net.firedevops.firemud.shared.v1.ErrorDetail permissionDenied(
      String operation, String message) {
    return GrpcAppErrors.error(meterRegistry, logger, operation, "PERMISSION_DENIED", message);
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

  private FriendPresenceActivityState mapActivityState(
      net.firedevops.firemud.socialgroups.dto.FriendPresenceActivityState activityState) {
    return switch (activityState) {
      case ACTIVE -> FriendPresenceActivityState.FRIEND_PRESENCE_ACTIVITY_STATE_ACTIVE;
      case AUTO_AFK -> FriendPresenceActivityState.FRIEND_PRESENCE_ACTIVITY_STATE_AUTO_AFK;
      case EXPLICIT_AFK -> FriendPresenceActivityState.FRIEND_PRESENCE_ACTIVITY_STATE_EXPLICIT_AFK;
    };
  }

  private static final class AuthorizationException extends RuntimeException {
    private AuthorizationException(String message) {
      super(message);
    }
  }
}
