package net.firedevops.firemud.service.impl;

import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.dto.AddFriendRequest;
import net.firedevops.firemud.dto.CreateGuildRequest;
import net.firedevops.firemud.dto.SendMailRequest;
import net.firedevops.firemud.dto.SendMessageRequestDto;
import net.firedevops.firemud.service.ChatService;
import net.firedevops.firemud.service.FriendService;
import net.firedevops.firemud.service.GuildService;
import net.firedevops.firemud.service.MailService;
import net.firedevops.firemud.service.PingService;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import net.firedevops.firemud.socialgroups.v1.AddFriendResponse;
import net.firedevops.firemud.socialgroups.v1.CreateGuildResponse;
import net.firedevops.firemud.socialgroups.v1.PingRequest;
import net.firedevops.firemud.socialgroups.v1.PingResponse;
import net.firedevops.firemud.socialgroups.v1.SendMailResponse;
import net.firedevops.firemud.socialgroups.v1.SendMessageResponse;
import net.firedevops.firemud.socialgroups.v1.SocialGroupsServiceGrpc;
import org.lognet.springboot.grpc.GRpcService;

/** gRPC service implementation for the SocialGroupsService API. */
@GRpcService
@RequiredArgsConstructor
public class SocialGroupsGrpcService extends SocialGroupsServiceGrpc.SocialGroupsServiceImplBase {
  private final PingService pingService;
  private final ChatService chatService;
  private final GuildService guildService;
  private final FriendService friendService;
  private final MailService mailService;

  @Override
  public void ping(PingRequest request, StreamObserver<PingResponse> responseObserver) {
    String msg = pingService.ping();
    PingResponse response = PingResponse.newBuilder().setMessage(msg).build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  public void sendMessage(
      net.firedevops.firemud.socialgroups.v1.SendMessageRequest request,
      StreamObserver<SendMessageResponse> responseObserver) {
    try {
      SendMessageRequestDto dto =
          new SendMessageRequestDto(
              Long.valueOf(request.getTenantId()),
              Long.valueOf(request.getSenderId()),
              request.getChannelId(),
              request.getContent());
      chatService.sendMessage(dto);
      SendMessageResponse response = SendMessageResponse.newBuilder().setSuccess(true).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      SendMessageResponse response =
          SendMessageResponse.newBuilder()
              .setSuccess(false)
              .setError(
                  ErrorDetail.newBuilder()
                      .setCode("INVALID_ARGUMENT")
                      .setMessage(ex.getMessage())
                      .build())
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
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
              .setError(
                  ErrorDetail.newBuilder()
                      .setCode("INVALID_ARGUMENT")
                      .setMessage(ex.getMessage())
                      .build())
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  public void addFriend(
      net.firedevops.firemud.socialgroups.v1.AddFriendRequest request,
      StreamObserver<AddFriendResponse> responseObserver) {
    try {
      AddFriendRequest dto =
          new AddFriendRequest(
              Long.valueOf(request.getTenantId()),
              Long.valueOf(request.getAccountId()),
              Long.valueOf(request.getFriendAccountId()));
      friendService.addFriend(dto);
      AddFriendResponse response = AddFriendResponse.newBuilder().setSuccess(true).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      AddFriendResponse response =
          AddFriendResponse.newBuilder()
              .setSuccess(false)
              .setError(
                  ErrorDetail.newBuilder()
                      .setCode("INVALID_ARGUMENT")
                      .setMessage(ex.getMessage())
                      .build())
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
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
              .setError(
                  ErrorDetail.newBuilder()
                      .setCode("INVALID_ARGUMENT")
                      .setMessage(ex.getMessage())
                      .build())
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }
}
