package net.firedevops.firemud.socialgroups.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.common.grpc.GrpcAppErrors;
import net.firedevops.firemud.socialgroups.dto.AddFriendRequest;
import net.firedevops.firemud.socialgroups.dto.CreateGuildRequest;
import net.firedevops.firemud.socialgroups.dto.SendMailRequest;
import net.firedevops.firemud.socialgroups.dto.SendMessageRequestDto;
import net.firedevops.firemud.socialgroups.service.ChatService;
import net.firedevops.firemud.socialgroups.service.FriendService;
import net.firedevops.firemud.socialgroups.service.GuildService;
import net.firedevops.firemud.socialgroups.service.MailService;
import net.firedevops.firemud.socialgroups.service.PingService;
import net.firedevops.firemud.socialgroups.v1.AddFriendResponse;
import net.firedevops.firemud.socialgroups.v1.CreateGuildResponse;
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
    }
  }

  @Override
  @Timed(value = "socialGrpc.addFriend")
  public void addFriend(
      net.firedevops.firemud.socialgroups.v1.AddFriendRequest request,
      StreamObserver<AddFriendResponse> responseObserver) {
    try {
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
    }
  }

  @Override
  @Timed(value = "socialGrpc.sendMail")
  public void sendMail(
      net.firedevops.firemud.socialgroups.v1.SendMailRequest request,
      StreamObserver<SendMailResponse> responseObserver) {
    try {
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
    }
  }

  private net.firedevops.firemud.shared.v1.ErrorDetail invalidArgument(
      String operation, String message) {
    return GrpcAppErrors.error(meterRegistry, logger, operation, "INVALID_ARGUMENT", message);
  }
}
