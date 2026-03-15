package net.firedevops.firemud.socialgroups.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.grpc.stub.StreamObserver;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.socialgroups.service.ChatService;
import net.firedevops.firemud.socialgroups.service.FriendService;
import net.firedevops.firemud.socialgroups.service.GuildService;
import net.firedevops.firemud.socialgroups.service.MailService;
import net.firedevops.firemud.socialgroups.service.PingService;
import net.firedevops.firemud.socialgroups.v1.CreateGuildResponse;
import net.firedevops.firemud.socialgroups.v1.PingRequest;
import net.firedevops.firemud.socialgroups.v1.PingResponse;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class SocialGroupsGrpcServiceTest {
  @Test
  void pingReturnsPong() {
    PingService ping = Mockito.mock(PingService.class);
    Mockito.when(ping.ping()).thenReturn("pong");
    ChatService chat = Mockito.mock(ChatService.class);
    GuildService guild = Mockito.mock(GuildService.class);
    FriendService friend = Mockito.mock(FriendService.class);
    MailService mail = Mockito.mock(MailService.class);
    SocialGroupsGrpcService service = new SocialGroupsGrpcService(ping, chat, guild, friend, mail);

    AtomicReference<PingResponse> ref = new AtomicReference<>();
    service.ping(
        PingRequest.getDefaultInstance(),
        new StreamObserver<>() {
          @Override
          public void onNext(PingResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertEquals("pong", ref.get().getMessage());
  }

  @Test
  void createGuildErrorReturnsErrorDetail() {
    PingService ping = Mockito.mock(PingService.class);
    ChatService chat = Mockito.mock(ChatService.class);
    GuildService guild = Mockito.mock(GuildService.class);
    Mockito.when(guild.createGuild(Mockito.any())).thenThrow(new IllegalArgumentException("bad"));
    FriendService friend = Mockito.mock(FriendService.class);
    MailService mail = Mockito.mock(MailService.class);
    SocialGroupsGrpcService service = new SocialGroupsGrpcService(ping, chat, guild, friend, mail);

    AtomicReference<CreateGuildResponse> ref = new AtomicReference<>();
    service.createGuild(
        net.firedevops.firemud.socialgroups.v1.CreateGuildRequest.newBuilder()
            .setTenantId("1")
            .setOwnerAccountId("2")
            .setName("test")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(CreateGuildResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
  }
}
