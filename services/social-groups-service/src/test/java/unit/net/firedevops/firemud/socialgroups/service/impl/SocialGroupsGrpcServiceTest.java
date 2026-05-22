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
import net.firedevops.firemud.socialgroups.dto.FriendPresencePolicyViewDto;
import net.firedevops.firemud.socialgroups.dto.FriendPresenceVisibilityPolicyValue;
import net.firedevops.firemud.socialgroups.dto.FriendRecentPresenceDisposition;
import net.firedevops.firemud.socialgroups.dto.FriendRosterEntryDto;
import net.firedevops.firemud.socialgroups.dto.FriendRosterFilter;
import net.firedevops.firemud.socialgroups.dto.FriendRosterSummaryDto;
import net.firedevops.firemud.socialgroups.dto.FriendRosterViewDto;
import net.firedevops.firemud.socialgroups.dto.SendMessageRequestDto;
import net.firedevops.firemud.socialgroups.enums.ChatType;
import net.firedevops.firemud.socialgroups.security.SocialAccessGuard;
import net.firedevops.firemud.socialgroups.service.ChatService;
import net.firedevops.firemud.socialgroups.service.FriendService;
import net.firedevops.firemud.socialgroups.service.GuildService;
import net.firedevops.firemud.socialgroups.service.MailService;
import net.firedevops.firemud.socialgroups.service.PingService;
import net.firedevops.firemud.socialgroups.v1.CreateGuildResponse;
import net.firedevops.firemud.socialgroups.v1.GetFriendByOrdinalResponse;
import net.firedevops.firemud.socialgroups.v1.GetFriendPresencePolicyResponse;
import net.firedevops.firemud.socialgroups.v1.GetFriendRosterSummaryResponse;
import net.firedevops.firemud.socialgroups.v1.ListFriendPresenceResponse;
import net.firedevops.firemud.socialgroups.v1.ListFriendsResponse;
import net.firedevops.firemud.socialgroups.v1.PingRequest;
import net.firedevops.firemud.socialgroups.v1.PingResponse;
import net.firedevops.firemud.socialgroups.v1.RemoveFriendByOrdinalResponse;
import net.firedevops.firemud.socialgroups.v1.RemoveFriendResponse;
import net.firedevops.firemud.socialgroups.v1.SendMessageResponse;
import net.firedevops.firemud.socialgroups.v1.UpdateFriendPresencePolicyResponse;
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
    Mockito.when(friend.listFriendPresence(1L, 2L, FriendRosterFilter.FRIENDS_ONLY))
        .thenReturn(
            new net.firedevops.firemud.socialgroups.dto.FriendPresenceViewDto(
                FriendRosterFilter.FRIENDS_ONLY,
                2,
                1,
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
                        FriendRecentPresenceDisposition.LOGOUT))));
    SocialGroupsGrpcService service =
        new SocialGroupsGrpcService(
            ping, chat, guild, friend, mail, accessGuard, new SimpleMeterRegistry());

    AtomicReference<ListFriendPresenceResponse> ref = new AtomicReference<>();
    service.listFriendPresence(
        net.firedevops.firemud.socialgroups.v1.ListFriendPresenceRequest.newBuilder()
            .setTenantId("1")
            .setAccountId("2")
            .setFilter(
                net.firedevops.firemud.socialgroups.v1.FriendRosterFilter
                    .FRIEND_ROSTER_FILTER_FRIENDS_ONLY)
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
    assertEquals(
        net.firedevops.firemud.socialgroups.v1.FriendRosterFilter.FRIEND_ROSTER_FILTER_FRIENDS_ONLY,
        ref.get().getFilter());
    assertEquals(2, ref.get().getTotalCount());
    assertEquals(1, ref.get().getMatchCount());
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

  @Test
  void getFriendMapsRosterDtoToProtoEntry() {
    PingService ping = Mockito.mock(PingService.class);
    ChatService chat = Mockito.mock(ChatService.class);
    GuildService guild = Mockito.mock(GuildService.class);
    FriendService friend = Mockito.mock(FriendService.class);
    MailService mail = Mockito.mock(MailService.class);
    SocialAccessGuard accessGuard = Mockito.mock(SocialAccessGuard.class);
    Mockito.when(accessGuard.hasAccountAccess(1L, 2L)).thenReturn(true);
    Mockito.when(friend.getFriend(1L, 2L, 3L))
        .thenReturn(
            java.util.Optional.of(
                new FriendRosterEntryDto(
                    1,
                    7L,
                    1L,
                    2L,
                    3L,
                    "active",
                    Instant.parse("2026-04-10T01:02:03Z"),
                    new FriendPresenceDto(
                        3L,
                        true,
                        9L,
                        "SHARED",
                        "demo",
                        "Demo World",
                        "production",
                        "Live Realm",
                        17L,
                        99L,
                        "Ben",
                        null,
                        Instant.parse("2026-04-11T06:15:30Z"),
                        FriendRecentPresenceDisposition.LOGOUT))));
    SocialGroupsGrpcService service =
        new SocialGroupsGrpcService(
            ping, chat, guild, friend, mail, accessGuard, new SimpleMeterRegistry());

    AtomicReference<net.firedevops.firemud.socialgroups.v1.GetFriendResponse> ref =
        new AtomicReference<>();
    service.getFriend(
        net.firedevops.firemud.socialgroups.v1.GetFriendRequest.newBuilder()
            .setTenantId("1")
            .setAccountId("2")
            .setFriendAccountId("3")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(net.firedevops.firemud.socialgroups.v1.GetFriendResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertEquals("3", ref.get().getFriend().getFriendAccountId());
    assertEquals("7", ref.get().getFriend().getFriendLinkId());
    assertEquals("Ben", ref.get().getFriend().getPresence().getCharacterName());
  }

  @Test
  void removeFriendInvokesServiceAndReturnsSuccess() {
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

    AtomicReference<RemoveFriendResponse> ref = new AtomicReference<>();
    service.removeFriend(
        net.firedevops.firemud.socialgroups.v1.RemoveFriendRequest.newBuilder()
            .setTenantId("1")
            .setAccountId("2")
            .setFriendAccountId("3")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(RemoveFriendResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    verify(friend).removeFriend(1L, 2L, 3L);
    assertNotNull(ref.get());
    assertEquals(true, ref.get().getSuccess());
  }

  @Test
  void addFriendIllegalArgumentReturnsErrorDetail() {
    PingService ping = Mockito.mock(PingService.class);
    ChatService chat = Mockito.mock(ChatService.class);
    GuildService guild = Mockito.mock(GuildService.class);
    FriendService friend = Mockito.mock(FriendService.class);
    Mockito.when(friend.addFriend(Mockito.any()))
        .thenThrow(
            new IllegalArgumentException("Cannot add or remove your own account as a friend"));
    MailService mail = Mockito.mock(MailService.class);
    SocialAccessGuard accessGuard = Mockito.mock(SocialAccessGuard.class);
    Mockito.when(accessGuard.hasAccountAccess(1L, 2L)).thenReturn(true);
    SocialGroupsGrpcService service =
        new SocialGroupsGrpcService(
            ping, chat, guild, friend, mail, accessGuard, new SimpleMeterRegistry());

    AtomicReference<net.firedevops.firemud.socialgroups.v1.AddFriendResponse> ref =
        new AtomicReference<>();
    service.addFriend(
        net.firedevops.firemud.socialgroups.v1.AddFriendRequest.newBuilder()
            .setTenantId("1")
            .setAccountId("2")
            .setFriendAccountId("2")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(net.firedevops.firemud.socialgroups.v1.AddFriendResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
    assertEquals(
        "Cannot add or remove your own account as a friend", ref.get().getError().getMessage());
  }

  @Test
  void removeFriendIllegalArgumentReturnsErrorDetail() {
    PingService ping = Mockito.mock(PingService.class);
    ChatService chat = Mockito.mock(ChatService.class);
    GuildService guild = Mockito.mock(GuildService.class);
    FriendService friend = Mockito.mock(FriendService.class);
    Mockito.doThrow(
            new IllegalArgumentException("Cannot add or remove your own account as a friend"))
        .when(friend)
        .removeFriend(1L, 2L, 2L);
    MailService mail = Mockito.mock(MailService.class);
    SocialAccessGuard accessGuard = Mockito.mock(SocialAccessGuard.class);
    Mockito.when(accessGuard.hasAccountAccess(1L, 2L)).thenReturn(true);
    SocialGroupsGrpcService service =
        new SocialGroupsGrpcService(
            ping, chat, guild, friend, mail, accessGuard, new SimpleMeterRegistry());

    AtomicReference<RemoveFriendResponse> ref = new AtomicReference<>();
    service.removeFriend(
        net.firedevops.firemud.socialgroups.v1.RemoveFriendRequest.newBuilder()
            .setTenantId("1")
            .setAccountId("2")
            .setFriendAccountId("2")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(RemoveFriendResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
    assertEquals(
        "Cannot add or remove your own account as a friend", ref.get().getError().getMessage());
  }

  @Test
  void listFriendsMapsDtosToProtoEntries() {
    PingService ping = Mockito.mock(PingService.class);
    ChatService chat = Mockito.mock(ChatService.class);
    GuildService guild = Mockito.mock(GuildService.class);
    FriendService friend = Mockito.mock(FriendService.class);
    MailService mail = Mockito.mock(MailService.class);
    SocialAccessGuard accessGuard = Mockito.mock(SocialAccessGuard.class);
    Mockito.when(accessGuard.hasAccountAccess(1L, 2L)).thenReturn(true);
    Mockito.when(friend.listFriends(1L, 2L, FriendRosterFilter.FRIENDS_ONLY))
        .thenReturn(
            new FriendRosterViewDto(
                FriendRosterFilter.FRIENDS_ONLY,
                1,
                1,
                List.of(
                    new FriendRosterEntryDto(
                        1,
                        7L,
                        1L,
                        2L,
                        3L,
                        "active",
                        Instant.parse("2026-04-10T01:02:03Z"),
                        new FriendPresenceDto(
                            3L,
                            true,
                            9L,
                            "SHARED",
                            "demo",
                            "Demo World",
                            "production",
                            "Live Realm",
                            17L,
                            99L,
                            "Ben",
                            null,
                            Instant.parse("2026-04-11T06:15:30Z"),
                            FriendRecentPresenceDisposition.LOGOUT)))));
    SocialGroupsGrpcService service =
        new SocialGroupsGrpcService(
            ping, chat, guild, friend, mail, accessGuard, new SimpleMeterRegistry());

    AtomicReference<ListFriendsResponse> ref = new AtomicReference<>();
    service.listFriends(
        net.firedevops.firemud.socialgroups.v1.ListFriendsRequest.newBuilder()
            .setTenantId("1")
            .setAccountId("2")
            .setFilter(
                net.firedevops.firemud.socialgroups.v1.FriendRosterFilter
                    .FRIEND_ROSTER_FILTER_FRIENDS_ONLY)
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(ListFriendsResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertEquals(1, ref.get().getFriendsCount());
    assertEquals(
        net.firedevops.firemud.socialgroups.v1.FriendRosterFilter.FRIEND_ROSTER_FILTER_FRIENDS_ONLY,
        ref.get().getFilter());
    assertEquals(1, ref.get().getTotalCount());
    assertEquals(1, ref.get().getMatchCount());
    assertEquals(1, ref.get().getFriends(0).getOrdinal());
    assertEquals("7", ref.get().getFriends(0).getFriendLinkId());
    assertEquals("3", ref.get().getFriends(0).getFriendAccountId());
    assertEquals("active", ref.get().getFriends(0).getStatus());
    assertEquals(
        Instant.parse("2026-04-10T01:02:03Z").toEpochMilli(),
        ref.get().getFriends(0).getCreatedAtMs());
    assertEquals(true, ref.get().getFriends(0).getPresence().getOnline());
    assertEquals("demo", ref.get().getFriends(0).getPresence().getWorldSlug());
    assertEquals(
        net.firedevops.firemud.socialgroups.v1.FriendRecentPresenceDisposition
            .FRIEND_RECENT_PRESENCE_DISPOSITION_LOGOUT,
        ref.get().getFriends(0).getPresence().getRecentDisposition());
  }

  @Test
  void getFriendRosterSummaryMapsCanonicalCounts() {
    PingService ping = Mockito.mock(PingService.class);
    ChatService chat = Mockito.mock(ChatService.class);
    GuildService guild = Mockito.mock(GuildService.class);
    FriendService friend = Mockito.mock(FriendService.class);
    MailService mail = Mockito.mock(MailService.class);
    SocialAccessGuard accessGuard = Mockito.mock(SocialAccessGuard.class);
    Mockito.when(accessGuard.hasAccountAccess(1L, 2L)).thenReturn(true);
    Mockito.when(friend.getFriendRosterSummary(1L, 2L))
        .thenReturn(new FriendRosterSummaryDto(4, 1, 3, 2, 1, 2, 1, 0, 0, 2, 1, 1));
    SocialGroupsGrpcService service =
        new SocialGroupsGrpcService(
            ping, chat, guild, friend, mail, accessGuard, new SimpleMeterRegistry());

    AtomicReference<GetFriendRosterSummaryResponse> ref = new AtomicReference<>();
    service.getFriendRosterSummary(
        net.firedevops.firemud.socialgroups.v1.GetFriendRosterSummaryRequest.newBuilder()
            .setTenantId("1")
            .setAccountId("2")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(GetFriendRosterSummaryResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertEquals(4, ref.get().getSummary().getTotalCount());
    assertEquals(1, ref.get().getSummary().getOnlineCount());
    assertEquals(3, ref.get().getSummary().getOfflineCount());
    assertEquals(2, ref.get().getSummary().getRecentCount());
    assertEquals(1, ref.get().getSummary().getPublicCount());
    assertEquals(2, ref.get().getSummary().getFriendsOnlyCount());
    assertEquals(1, ref.get().getSummary().getPrivateCount());
    assertEquals(0, ref.get().getSummary().getHiddenStaffCount());
    assertEquals(0, ref.get().getSummary().getUnspecifiedVisibilityCount());
    assertEquals(2, ref.get().getSummary().getSharedCount());
    assertEquals(1, ref.get().getSummary().getIsolatedCount());
    assertEquals(1, ref.get().getSummary().getUnspecifiedScopeCount());
  }

  @Test
  void getFriendByOrdinalMapsCanonicalEntry() {
    PingService ping = Mockito.mock(PingService.class);
    ChatService chat = Mockito.mock(ChatService.class);
    GuildService guild = Mockito.mock(GuildService.class);
    FriendService friend = Mockito.mock(FriendService.class);
    MailService mail = Mockito.mock(MailService.class);
    SocialAccessGuard accessGuard = Mockito.mock(SocialAccessGuard.class);
    Mockito.when(accessGuard.hasAccountAccess(1L, 2L)).thenReturn(true);
    Mockito.when(friend.getFriendByOrdinal(1L, 2L, 1))
        .thenReturn(
            java.util.Optional.of(
                new FriendRosterEntryDto(
                    1,
                    7L,
                    1L,
                    2L,
                    3L,
                    "active",
                    Instant.parse("2026-04-10T01:02:03Z"),
                    new FriendPresenceDto(
                        3L, true, null, null, null, null, null, null, null, null, "Ben", null, null,
                        null, null))));
    SocialGroupsGrpcService service =
        new SocialGroupsGrpcService(
            ping, chat, guild, friend, mail, accessGuard, new SimpleMeterRegistry());

    AtomicReference<GetFriendByOrdinalResponse> ref = new AtomicReference<>();
    service.getFriendByOrdinal(
        net.firedevops.firemud.socialgroups.v1.GetFriendByOrdinalRequest.newBuilder()
            .setTenantId("1")
            .setAccountId("2")
            .setOrdinal(1)
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(GetFriendByOrdinalResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertEquals(1, ref.get().getFriend().getOrdinal());
    assertEquals("3", ref.get().getFriend().getFriendAccountId());
  }

  @Test
  void removeFriendByOrdinalMapsRemovedCanonicalEntry() {
    PingService ping = Mockito.mock(PingService.class);
    ChatService chat = Mockito.mock(ChatService.class);
    GuildService guild = Mockito.mock(GuildService.class);
    FriendService friend = Mockito.mock(FriendService.class);
    MailService mail = Mockito.mock(MailService.class);
    SocialAccessGuard accessGuard = Mockito.mock(SocialAccessGuard.class);
    Mockito.when(accessGuard.hasAccountAccess(1L, 2L)).thenReturn(true);
    Mockito.when(friend.removeFriendByOrdinal(1L, 2L, 1))
        .thenReturn(
            java.util.Optional.of(
                new FriendRosterEntryDto(
                    1,
                    7L,
                    1L,
                    2L,
                    3L,
                    "active",
                    Instant.parse("2026-04-10T01:02:03Z"),
                    new FriendPresenceDto(
                        3L, false, null, null, null, null, null, null, null, null, null, null, null,
                        null, null))));
    SocialGroupsGrpcService service =
        new SocialGroupsGrpcService(
            ping, chat, guild, friend, mail, accessGuard, new SimpleMeterRegistry());

    AtomicReference<RemoveFriendByOrdinalResponse> ref = new AtomicReference<>();
    service.removeFriendByOrdinal(
        net.firedevops.firemud.socialgroups.v1.RemoveFriendByOrdinalRequest.newBuilder()
            .setTenantId("1")
            .setAccountId("2")
            .setOrdinal(1)
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(RemoveFriendByOrdinalResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertEquals(true, ref.get().getSuccess());
    assertEquals("3", ref.get().getRemovedFriend().getFriendAccountId());
  }

  @Test
  void getFriendPresencePolicyMapsCanonicalPolicy() {
    PingService ping = Mockito.mock(PingService.class);
    ChatService chat = Mockito.mock(ChatService.class);
    GuildService guild = Mockito.mock(GuildService.class);
    FriendService friend = Mockito.mock(FriendService.class);
    MailService mail = Mockito.mock(MailService.class);
    SocialAccessGuard accessGuard = Mockito.mock(SocialAccessGuard.class);
    Mockito.when(accessGuard.hasAccountAccess(1L, 2L)).thenReturn(true);
    Mockito.when(friend.getFriendPresencePolicy(1L, 2L))
        .thenReturn(new FriendPresencePolicyViewDto(FriendPresenceVisibilityPolicyValue.PRIVATE));
    SocialGroupsGrpcService service =
        new SocialGroupsGrpcService(
            ping, chat, guild, friend, mail, accessGuard, new SimpleMeterRegistry());

    AtomicReference<GetFriendPresencePolicyResponse> ref = new AtomicReference<>();
    service.getFriendPresencePolicy(
        net.firedevops.firemud.socialgroups.v1.GetFriendPresencePolicyRequest.newBuilder()
            .setTenantId("1")
            .setAccountId("2")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(GetFriendPresencePolicyResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertEquals(
        net.firedevops.firemud.socialgroups.v1.FriendPresenceVisibilityPolicy
            .FRIEND_PRESENCE_VISIBILITY_POLICY_PRIVATE,
        ref.get().getCurrentPolicy());
  }

  @Test
  void updateFriendPresencePolicyMapsCanonicalPolicy() {
    PingService ping = Mockito.mock(PingService.class);
    ChatService chat = Mockito.mock(ChatService.class);
    GuildService guild = Mockito.mock(GuildService.class);
    FriendService friend = Mockito.mock(FriendService.class);
    MailService mail = Mockito.mock(MailService.class);
    SocialAccessGuard accessGuard = Mockito.mock(SocialAccessGuard.class);
    Mockito.when(accessGuard.hasAccountAccess(1L, 2L)).thenReturn(true);
    Mockito.when(
            friend.updateFriendPresencePolicy(1L, 2L, FriendPresenceVisibilityPolicyValue.PUBLIC))
        .thenReturn(new FriendPresencePolicyViewDto(FriendPresenceVisibilityPolicyValue.PUBLIC));
    SocialGroupsGrpcService service =
        new SocialGroupsGrpcService(
            ping, chat, guild, friend, mail, accessGuard, new SimpleMeterRegistry());

    AtomicReference<UpdateFriendPresencePolicyResponse> ref = new AtomicReference<>();
    service.updateFriendPresencePolicy(
        net.firedevops.firemud.socialgroups.v1.UpdateFriendPresencePolicyRequest.newBuilder()
            .setTenantId("1")
            .setAccountId("2")
            .setVisibilityPolicy(
                net.firedevops.firemud.socialgroups.v1.FriendPresenceVisibilityPolicy
                    .FRIEND_PRESENCE_VISIBILITY_POLICY_PUBLIC)
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(UpdateFriendPresencePolicyResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertEquals(true, ref.get().getSuccess());
    assertEquals(
        net.firedevops.firemud.socialgroups.v1.FriendPresenceVisibilityPolicy
            .FRIEND_PRESENCE_VISIBILITY_POLICY_PUBLIC,
        ref.get().getCurrentPolicy());
  }
}
