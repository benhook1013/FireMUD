package net.firedevops.firemud.socialgroups.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;

import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.socialgroups.dto.FriendPresenceDto;
import net.firedevops.firemud.socialgroups.dto.FriendRecentPresenceDisposition;
import net.firedevops.firemud.socialgroups.dto.SendMessageRequestDto;
import net.firedevops.firemud.socialgroups.enums.ChatType;
import net.firedevops.firemud.socialgroups.security.SocialAccessGuard;
import net.firedevops.firemud.socialgroups.service.ChatService;
import net.firedevops.firemud.socialgroups.service.FriendService;
import net.firedevops.firemud.socialgroups.service.GuildService;
import net.firedevops.firemud.socialgroups.service.MailService;
import net.firedevops.firemud.socialgroups.service.PingService;
import net.firedevops.firemud.socialgroups.v1.CreateGuildResponse;
import net.firedevops.firemud.socialgroups.v1.ListFriendPresenceResponse;
import net.firedevops.firemud.socialgroups.v1.PingRequest;
import net.firedevops.firemud.socialgroups.v1.PingResponse;
import net.firedevops.firemud.socialgroups.v1.SendMessageResponse;
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
    SocialAccessGuard accessGuard = Mockito.mock(SocialAccessGuard.class);
    SocialGroupsGrpcService service =
        new SocialGroupsGrpcService(
            ping, chat, guild, friend, mail, accessGuard, new SimpleMeterRegistry());

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
    SocialAccessGuard accessGuard = Mockito.mock(SocialAccessGuard.class);
    Mockito.when(accessGuard.hasAccountAccess(1L, 2L)).thenReturn(true);
    SocialGroupsGrpcService service =
        new SocialGroupsGrpcService(
            ping, chat, guild, friend, mail, accessGuard, new SimpleMeterRegistry());

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

  @Test
  void createGuildRuntimeFailureReturnsInternalErrorDetail() {
    PingService ping = Mockito.mock(PingService.class);
    ChatService chat = Mockito.mock(ChatService.class);
    GuildService guild = Mockito.mock(GuildService.class);
    Mockito.when(guild.createGuild(Mockito.any())).thenThrow(new IllegalStateException("boom"));
    FriendService friend = Mockito.mock(FriendService.class);
    MailService mail = Mockito.mock(MailService.class);
    SocialAccessGuard accessGuard = Mockito.mock(SocialAccessGuard.class);
    Mockito.when(accessGuard.hasAccountAccess(1L, 2L)).thenReturn(true);
    SocialGroupsGrpcService service =
        new SocialGroupsGrpcService(
            ping, chat, guild, friend, mail, accessGuard, new SimpleMeterRegistry());

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
    assertEquals("INTERNAL", ref.get().getError().getCode());
  }

  @Test
  void sendMessageMapsWhisperProtoToDomainEnum() {
    PingService ping = Mockito.mock(PingService.class);
    ChatService chat = Mockito.mock(ChatService.class);
    GuildService guild = Mockito.mock(GuildService.class);
    FriendService friend = Mockito.mock(FriendService.class);
    MailService mail = Mockito.mock(MailService.class);
    SocialAccessGuard accessGuard = Mockito.mock(SocialAccessGuard.class);
    Mockito.when(accessGuard.hasAccountAccess(1L, 2L)).thenReturn(true);
    SocialGroupsGrpcService service =
        new SocialGroupsGrpcService(
            ping, chat, guild, friend, mail, accessGuard, new SimpleMeterRegistry());

    AtomicReference<SendMessageResponse> ref = new AtomicReference<>();
    service.sendMessage(
        net.firedevops.firemud.socialgroups.v1.SendMessageRequest.newBuilder()
            .setTenantId("1")
            .setSenderId("2")
            .setType(net.firedevops.firemud.socialgroups.v1.ChatType.CHAT_TYPE_WHISPER)
            .setRecipientId("7")
            .setContent("quiet")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(SendMessageResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    verify(chat)
        .sendMessage(
            new SendMessageRequestDto(1L, 2L, ChatType.WHISPER, "", 7L, null, null, "quiet", null));
    assertNotNull(ref.get());
    assertEquals(true, ref.get().getSuccess());
  }

  @Test
  void sendMessageMapsEffectIdToDomainRequest() {
    PingService ping = Mockito.mock(PingService.class);
    ChatService chat = Mockito.mock(ChatService.class);
    GuildService guild = Mockito.mock(GuildService.class);
    FriendService friend = Mockito.mock(FriendService.class);
    MailService mail = Mockito.mock(MailService.class);
    SocialAccessGuard accessGuard = Mockito.mock(SocialAccessGuard.class);
    Mockito.when(accessGuard.hasAccountAccess(1L, 2L)).thenReturn(true);
    SocialGroupsGrpcService service =
        new SocialGroupsGrpcService(
            ping, chat, guild, friend, mail, accessGuard, new SimpleMeterRegistry());

    service.sendMessage(
        net.firedevops.firemud.socialgroups.v1.SendMessageRequest.newBuilder()
            .setTenantId("1")
            .setSenderId("2")
            .setType(net.firedevops.firemud.socialgroups.v1.ChatType.CHAT_TYPE_SAY)
            .setContent("hello")
            .setEffectId("fx-comm-2")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(SendMessageResponse value) {}

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    verify(chat)
        .sendMessage(
            new SendMessageRequestDto(
                1L, 2L, ChatType.SAY, "", null, null, null, "hello", "fx-comm-2"));
  }

  @Test
  void listFriendPresenceMapsDtosToProtoEntries() {
    PingService ping = Mockito.mock(PingService.class);
    ChatService chat = Mockito.mock(ChatService.class);
    GuildService guild = Mockito.mock(GuildService.class);
    FriendService friend = Mockito.mock(FriendService.class);
    MailService mail = Mockito.mock(MailService.class);
    SocialAccessGuard accessGuard = Mockito.mock(SocialAccessGuard.class);
    Mockito.when(accessGuard.hasAccountAccess(1L, 2L)).thenReturn(true);
    Mockito.when(friend.listFriendPresence(1L, 2L))
        .thenReturn(
            List.of(
                new FriendPresenceDto(
                    3L,
                    true,
                    9L,
                    "demo",
                    "Demo World",
                    "production",
                    "Live Realm",
                    99L,
                    "Ben",
                    null,
                    Instant.parse("2026-04-11T06:15:30Z"),
                    FriendRecentPresenceDisposition.LOGOUT)));
    SocialGroupsGrpcService service =
        new SocialGroupsGrpcService(
            ping, chat, guild, friend, mail, accessGuard, new SimpleMeterRegistry());

    AtomicReference<ListFriendPresenceResponse> ref = new AtomicReference<>();
    service.listFriendPresence(
        net.firedevops.firemud.socialgroups.v1.ListFriendPresenceRequest.newBuilder()
            .setTenantId("1")
            .setAccountId("2")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(ListFriendPresenceResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertEquals(1, ref.get().getPresencesCount());
    assertEquals("3", ref.get().getPresences(0).getFriendAccountId());
    assertEquals(true, ref.get().getPresences(0).getOnline());
    assertEquals("demo", ref.get().getPresences(0).getWorldSlug());
    assertEquals("Demo World", ref.get().getPresences(0).getWorldDisplayName());
    assertEquals("production", ref.get().getPresences(0).getRealmSlug());
    assertEquals("Live Realm", ref.get().getPresences(0).getRealmDisplayName());
    assertEquals(
        Instant.parse("2026-04-11T06:15:30Z").toEpochMilli(),
        ref.get().getPresences(0).getLastSeenAtMs());
    assertEquals(
        net.firedevops.firemud.socialgroups.v1.FriendRecentPresenceDisposition
            .FRIEND_RECENT_PRESENCE_DISPOSITION_LOGOUT,
        ref.get().getPresences(0).getRecentDisposition());
  }
}
