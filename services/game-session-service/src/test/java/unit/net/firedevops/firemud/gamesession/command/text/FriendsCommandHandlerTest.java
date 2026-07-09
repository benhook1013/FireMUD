package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import net.firedevops.firemud.entitymanagement.v1.Character;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.client.EntityManagementClient;
import net.firedevops.firemud.gamesession.client.SocialGroupsClient;
import net.firedevops.firemud.gamesession.presentation.FriendDetailViewOutput;
import net.firedevops.firemud.gamesession.presentation.FriendMutationResultOutput;
import net.firedevops.firemud.gamesession.presentation.FriendPresencePolicyViewOutput;
import net.firedevops.firemud.gamesession.presentation.FriendPresenceViewOutput;
import net.firedevops.firemud.gamesession.presentation.FriendRosterSummaryViewOutput;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.presentation.TextPlayerOutputRenderer;
import net.firedevops.firemud.gamesession.service.ScriptEventPublisher;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.socialgroups.v1.AddFriendResponse;
import net.firedevops.firemud.socialgroups.v1.FriendPresenceActivityState;
import net.firedevops.firemud.socialgroups.v1.FriendPresenceEntry;
import net.firedevops.firemud.socialgroups.v1.FriendRecentPresenceDisposition;
import net.firedevops.firemud.socialgroups.v1.FriendRosterEntry;
import net.firedevops.firemud.socialgroups.v1.FriendRosterFilter;
import net.firedevops.firemud.socialgroups.v1.GetFriendByOrdinalResponse;
import net.firedevops.firemud.socialgroups.v1.GetFriendPresencePolicyResponse;
import net.firedevops.firemud.socialgroups.v1.GetFriendResponse;
import net.firedevops.firemud.socialgroups.v1.GetFriendRosterSummaryResponse;
import net.firedevops.firemud.socialgroups.v1.ListFriendsResponse;
import net.firedevops.firemud.socialgroups.v1.RemoveFriendByOrdinalResponse;
import net.firedevops.firemud.socialgroups.v1.RemoveFriendResponse;
import net.firedevops.firemud.socialgroups.v1.UpdateFriendPresencePolicyResponse;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class FriendsCommandHandlerTest {
  private static final SessionContext GAMEPLAY_CONTEXT =
      new SessionContext(
          7L,
          1L,
          41L,
          "demo@example.com",
          99L,
          "Emberline",
          7L,
          "R-1",
          null,
          null,
          7L,
          "demo",
          "production",
          1L,
          "SHARED");

  private FriendsCommandHandler newHandler(
      SocialGroupsClient socialGroupsClient,
      EntityManagementClient entityManagementClient,
      ScriptEventPublisher scriptEventPublisher) {
    return new FriendsCommandHandler(
        socialGroupsClient, entityManagementClient, scriptEventPublisher);
  }

  @Test
  void friendsMapsVisiblePresenceToTypedViewAndText() {
    SocialGroupsClient socialGroupsClient = Mockito.mock(SocialGroupsClient.class);
    EntityManagementClient entityManagementClient = Mockito.mock(EntityManagementClient.class);
    ScriptEventPublisher scriptEventPublisher = Mockito.mock(ScriptEventPublisher.class);
    FriendsCommandHandler handler =
        newHandler(socialGroupsClient, entityManagementClient, scriptEventPublisher);
    when(socialGroupsClient.listFriends(1L, 41L))
        .thenReturn(
            ListFriendsResponse.newBuilder()
                .addFriends(
                    FriendRosterEntry.newBuilder()
                        .setFriendAccountId("77")
                        .setStatus("active")
                        .setPresence(
                            FriendPresenceEntry.newBuilder()
                                .setFriendAccountId("77")
                                .setOnline(true)
                                .setCharacterId("99")
                                .setCharacterName("Sora")
                                .setWorldSlug("demo")
                                .setWorldDisplayName("Demo World")
                                .setRealmSlug("production")
                                .setRealmDisplayName("Live Realm")
                                .setActivityState(
                                    FriendPresenceActivityState
                                        .FRIEND_PRESENCE_ACTIVITY_STATE_AUTO_AFK)
                                .build())
                        .build())
                .build());

    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommand(TextCommandType.FRIENDS, java.util.List.of(), "FRIENDS"),
            GAMEPLAY_CONTEXT);

    assertThat(result.commandResult().accepted()).isTrue();
    assertThat(result.outputs())
        .singleElement()
        .extracting(PlayerOutput::payload)
        .isInstanceOf(FriendPresenceViewOutput.class);
    FriendPresenceViewOutput view =
        (FriendPresenceViewOutput) result.outputs().getFirst().payload();
    assertThat(view.filter()).isEqualTo("ALL");
    assertThat(view.totalCount()).isEqualTo(1);
    assertThat(view.matchCount()).isEqualTo(1);
    assertThat(view.friends())
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(entry.friendLinkId()).isNull();
              assertThat(entry.friendAccountId()).isEqualTo(77L);
              assertThat(entry.status()).isEqualTo("active");
              assertThat(entry.linkedAtEpochMs()).isNull();
              assertThat(entry.displayName()).isEqualTo("Sora");
              assertThat(entry.activityState()).isEqualTo("AUTO_AFK");
            });
    assertThat(
            new TextPlayerOutputRenderer(
                    new net.firedevops.firemud.gamesession.config.PresentationProperties())
                .render(result.outputs().getFirst()))
        .contains("Sora [acct #77] - online in Demo World / Live Realm (idle)");
    Mockito.verify(scriptEventPublisher)
        .publishCommandEvent(
            Mockito.any(),
            Mockito.argThat(
                gameplayCommand ->
                    "FRIENDS".equals(gameplayCommand.getCommandName())
                        && "FRIENDS".equals(gameplayCommand.getCommandText())
                        && gameplayCommand.getCommandId() != null
                        && gameplayCommand.getCommandId().startsWith("friends-")));
  }

  @Test
  void friendsFallsBackToBoundedOfflineLabelWhenDetailsAreSuppressed() {
    SocialGroupsClient socialGroupsClient = Mockito.mock(SocialGroupsClient.class);
    EntityManagementClient entityManagementClient = Mockito.mock(EntityManagementClient.class);
    FriendsCommandHandler handler =
        newHandler(
            socialGroupsClient, entityManagementClient, Mockito.mock(ScriptEventPublisher.class));
    when(socialGroupsClient.listFriends(1L, 41L))
        .thenReturn(
            ListFriendsResponse.newBuilder()
                .addFriends(
                    FriendRosterEntry.newBuilder()
                        .setFriendAccountId("77")
                        .setStatus("active")
                        .setPresence(
                            FriendPresenceEntry.newBuilder()
                                .setFriendAccountId("77")
                                .setOnline(false)
                                .setLastSeenAtMs(
                                    Instant.parse("2026-04-11T06:15:30Z").toEpochMilli())
                                .setRecentDisposition(
                                    FriendRecentPresenceDisposition
                                        .FRIEND_RECENT_PRESENCE_DISPOSITION_LOGOUT)
                                .build())
                        .build())
                .build());

    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommand(TextCommandType.FRIENDS, java.util.List.of(), "FRIENDS"),
            GAMEPLAY_CONTEXT);

    FriendPresenceViewOutput view =
        (FriendPresenceViewOutput) result.outputs().getFirst().payload();
    assertThat(view.filter()).isEqualTo("ALL");
    assertThat(view.friends())
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(entry.friendLinkId()).isNull();
              assertThat(entry.status()).isEqualTo("active");
              assertThat(entry.displayName()).isEqualTo("Friend #77");
              assertThat(entry.lastSeenAtEpochMs())
                  .isEqualTo(Instant.parse("2026-04-11T06:15:30Z").toEpochMilli());
              assertThat(entry.recentDisposition()).isEqualTo("LOGOUT");
            });
  }

  @Test
  void friendsAddLinksAccountScopedFriendAndReturnsNotice() {
    SocialGroupsClient socialGroupsClient = Mockito.mock(SocialGroupsClient.class);
    EntityManagementClient entityManagementClient = Mockito.mock(EntityManagementClient.class);
    ScriptEventPublisher scriptEventPublisher = Mockito.mock(ScriptEventPublisher.class);
    FriendsCommandHandler handler =
        newHandler(socialGroupsClient, entityManagementClient, scriptEventPublisher);
    when(socialGroupsClient.addFriend(1L, 41L, 77L))
        .thenReturn(AddFriendResponse.newBuilder().setSuccess(true).build());

    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommand(
                TextCommandType.FRIENDS, java.util.List.of("ADD", "77"), "friends add 77"),
            GAMEPLAY_CONTEXT);

    assertThat(result.commandResult().accepted()).isTrue();
    assertThat(result.outputs())
        .singleElement()
        .satisfies(
            output -> {
              assertThat(output.text()).isEqualTo("Friend #77 added.");
              assertThat(output.payload()).isInstanceOf(FriendMutationResultOutput.class);
              FriendMutationResultOutput mutation = (FriendMutationResultOutput) output.payload();
              assertThat(mutation.action()).isEqualTo("ADD");
              assertThat(mutation.friendAccountId()).isEqualTo(77L);
              assertThat(mutation.displayName()).isEqualTo("Friend #77");
              assertThat(mutation.characterName()).isNull();
              assertThat(mutation.ordinal()).isNull();
            });
    Mockito.verify(scriptEventPublisher)
        .publishCommandEvent(
            Mockito.any(),
            Mockito.argThat(
                gameplayCommand ->
                    "FRIENDS".equals(gameplayCommand.getCommandName())
                        && "friends add 77".equals(gameplayCommand.getCommandText())));
  }

  @Test
  void friendsOnlineFiltersCanonicalRosterWithoutRenumbering() {
    SocialGroupsClient socialGroupsClient = Mockito.mock(SocialGroupsClient.class);
    EntityManagementClient entityManagementClient = Mockito.mock(EntityManagementClient.class);
    FriendsCommandHandler handler =
        newHandler(
            socialGroupsClient, entityManagementClient, Mockito.mock(ScriptEventPublisher.class));
    when(socialGroupsClient.listFriends(1L, 41L, FriendRosterFilter.FRIEND_ROSTER_FILTER_ONLINE))
        .thenReturn(
            ListFriendsResponse.newBuilder()
                .setFilter(FriendRosterFilter.FRIEND_ROSTER_FILTER_ONLINE)
                .setTotalCount(2)
                .setMatchCount(1)
                .addFriends(
                    FriendRosterEntry.newBuilder()
                        .setOrdinal(1)
                        .setFriendAccountId("77")
                        .setPresence(
                            FriendPresenceEntry.newBuilder()
                                .setFriendAccountId("77")
                                .setOnline(true)
                                .setCharacterName("Sora")
                                .build())
                        .build())
                .build());

    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommand(TextCommandType.FRIENDS, java.util.List.of("ONLINE"), "FRIENDS ONLINE"),
            GAMEPLAY_CONTEXT);

    FriendPresenceViewOutput view =
        (FriendPresenceViewOutput) result.outputs().getFirst().payload();
    assertThat(view.filter()).isEqualTo("ONLINE");
    assertThat(view.totalCount()).isEqualTo(2);
    assertThat(view.matchCount()).isEqualTo(1);
    assertThat(view.friends())
        .singleElement()
        .satisfies(entry -> assertThat(entry.ordinal()).isEqualTo(1));
    assertThat(
            new TextPlayerOutputRenderer(
                    new net.firedevops.firemud.gamesession.config.PresentationProperties())
                .render(result.outputs().getFirst()))
        .contains("Friends ONLINE [1/2]:")
        .contains("1) Sora [acct #77] - online");
    Mockito.verify(socialGroupsClient)
        .listFriends(1L, 41L, FriendRosterFilter.FRIEND_ROSTER_FILTER_ONLINE);
  }

  @Test
  void friendsPrivateFiltersCanonicalRosterByVisibilityPolicy() {
    SocialGroupsClient socialGroupsClient = Mockito.mock(SocialGroupsClient.class);
    EntityManagementClient entityManagementClient = Mockito.mock(EntityManagementClient.class);
    FriendsCommandHandler handler =
        newHandler(
            socialGroupsClient, entityManagementClient, Mockito.mock(ScriptEventPublisher.class));
    when(socialGroupsClient.listFriends(1L, 41L, FriendRosterFilter.FRIEND_ROSTER_FILTER_PRIVATE))
        .thenReturn(
            ListFriendsResponse.newBuilder()
                .setFilter(FriendRosterFilter.FRIEND_ROSTER_FILTER_PRIVATE)
                .setTotalCount(2)
                .setMatchCount(1)
                .addFriends(
                    FriendRosterEntry.newBuilder()
                        .setOrdinal(2)
                        .setFriendAccountId("88")
                        .setPresence(
                            FriendPresenceEntry.newBuilder()
                                .setFriendAccountId("88")
                                .setOnline(false)
                                .setVisibilityPolicy(
                                    net.firedevops.firemud.socialgroups.v1
                                        .FriendPresenceVisibilityPolicy
                                        .FRIEND_PRESENCE_VISIBILITY_POLICY_PRIVATE)
                                .build())
                        .build())
                .build());

    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommand(
                TextCommandType.FRIENDS, java.util.List.of("PRIVATE"), "FRIENDS PRIVATE"),
            GAMEPLAY_CONTEXT);

    FriendPresenceViewOutput view =
        (FriendPresenceViewOutput) result.outputs().getFirst().payload();
    assertThat(view.filter()).isEqualTo("PRIVATE");
    assertThat(view.totalCount()).isEqualTo(2);
    assertThat(view.matchCount()).isEqualTo(1);
    assertThat(view.friends())
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(entry.ordinal()).isEqualTo(2);
              assertThat(entry.friendAccountId()).isEqualTo(88L);
              assertThat(entry.visibilityPolicy()).isEqualTo("PRIVATE");
            });
    assertThat(
            new TextPlayerOutputRenderer(
                    new net.firedevops.firemud.gamesession.config.PresentationProperties())
                .render(result.outputs().getFirst()))
        .contains("Friends PRIVATE [1/2]:");
    Mockito.verify(socialGroupsClient)
        .listFriends(1L, 41L, FriendRosterFilter.FRIEND_ROSTER_FILTER_PRIVATE);
  }

  @Test
  void friendsSharedFiltersCanonicalRosterByPlayableStateScope() {
    SocialGroupsClient socialGroupsClient = Mockito.mock(SocialGroupsClient.class);
    EntityManagementClient entityManagementClient = Mockito.mock(EntityManagementClient.class);
    FriendsCommandHandler handler =
        newHandler(
            socialGroupsClient, entityManagementClient, Mockito.mock(ScriptEventPublisher.class));
    when(socialGroupsClient.listFriends(1L, 41L, FriendRosterFilter.FRIEND_ROSTER_FILTER_SHARED))
        .thenReturn(
            ListFriendsResponse.newBuilder()
                .setFilter(FriendRosterFilter.FRIEND_ROSTER_FILTER_SHARED)
                .setTotalCount(2)
                .setMatchCount(1)
                .addFriends(
                    FriendRosterEntry.newBuilder()
                        .setOrdinal(1)
                        .setFriendAccountId("77")
                        .setPresence(
                            FriendPresenceEntry.newBuilder()
                                .setFriendAccountId("77")
                                .setOnline(true)
                                .setCharacterName("Sora")
                                .setPlayableStateScope(
                                    PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
                                .build())
                        .build())
                .build());

    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommand(TextCommandType.FRIENDS, java.util.List.of("SHARED"), "FRIENDS SHARED"),
            GAMEPLAY_CONTEXT);

    FriendPresenceViewOutput view =
        (FriendPresenceViewOutput) result.outputs().getFirst().payload();
    assertThat(view.filter()).isEqualTo("SHARED");
    assertThat(view.totalCount()).isEqualTo(2);
    assertThat(view.matchCount()).isEqualTo(1);
    assertThat(view.friends())
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(entry.ordinal()).isEqualTo(1);
              assertThat(entry.friendAccountId()).isEqualTo(77L);
              assertThat(entry.playableStateScope()).isEqualTo("SHARED");
            });
    assertThat(
            new TextPlayerOutputRenderer(
                    new net.firedevops.firemud.gamesession.config.PresentationProperties())
                .render(result.outputs().getFirst()))
        .contains("Friends SHARED [1/2]:");
    Mockito.verify(socialGroupsClient)
        .listFriends(1L, 41L, FriendRosterFilter.FRIEND_ROSTER_FILTER_SHARED);
  }

  @Test
  void friendsRecentFiltersOfflineEntriesWithRecentPresence() {
    SocialGroupsClient socialGroupsClient = Mockito.mock(SocialGroupsClient.class);
    EntityManagementClient entityManagementClient = Mockito.mock(EntityManagementClient.class);
    FriendsCommandHandler handler =
        newHandler(
            socialGroupsClient, entityManagementClient, Mockito.mock(ScriptEventPublisher.class));
    when(socialGroupsClient.listFriends(1L, 41L, FriendRosterFilter.FRIEND_ROSTER_FILTER_RECENT))
        .thenReturn(
            ListFriendsResponse.newBuilder()
                .setFilter(FriendRosterFilter.FRIEND_ROSTER_FILTER_RECENT)
                .setTotalCount(2)
                .setMatchCount(1)
                .addFriends(
                    FriendRosterEntry.newBuilder()
                        .setOrdinal(1)
                        .setFriendAccountId("77")
                        .setPresence(
                            FriendPresenceEntry.newBuilder()
                                .setFriendAccountId("77")
                                .setOnline(false)
                                .setLastSeenAtMs(
                                    Instant.parse("2026-04-11T06:15:30Z").toEpochMilli())
                                .build())
                        .build())
                .build());

    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommand(TextCommandType.FRIENDS, java.util.List.of("RECENT"), "FRIENDS RECENT"),
            GAMEPLAY_CONTEXT);

    FriendPresenceViewOutput view =
        (FriendPresenceViewOutput) result.outputs().getFirst().payload();
    assertThat(view.filter()).isEqualTo("RECENT");
    assertThat(view.totalCount()).isEqualTo(2);
    assertThat(view.matchCount()).isEqualTo(1);
    assertThat(view.friends())
        .singleElement()
        .satisfies(entry -> assertThat(entry.friendAccountId()).isEqualTo(77L));
  }

  @Test
  void friendsSummaryUsesCanonicalRosterSummarySurface() {
    SocialGroupsClient socialGroupsClient = Mockito.mock(SocialGroupsClient.class);
    EntityManagementClient entityManagementClient = Mockito.mock(EntityManagementClient.class);
    FriendsCommandHandler handler =
        newHandler(
            socialGroupsClient, entityManagementClient, Mockito.mock(ScriptEventPublisher.class));
    when(socialGroupsClient.getFriendRosterSummary(1L, 41L))
        .thenReturn(
            GetFriendRosterSummaryResponse.newBuilder()
                .setSummary(
                    net.firedevops.firemud.socialgroups.v1.FriendRosterSummary.newBuilder()
                        .setTotalCount(4)
                        .setOnlineCount(1)
                        .setOfflineCount(3)
                        .setRecentCount(2)
                        .setPublicCount(1)
                        .setFriendsOnlyCount(2)
                        .setPrivateCount(1)
                        .setHiddenStaffCount(0)
                        .setUnspecifiedVisibilityCount(0)
                        .setSharedCount(2)
                        .setIsolatedCount(1)
                        .setUnspecifiedScopeCount(1)
                        .build())
                .build());

    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommand(
                TextCommandType.FRIENDS, java.util.List.of("SUMMARY"), "FRIENDS SUMMARY"),
            GAMEPLAY_CONTEXT);

    assertThat(result.commandResult().accepted()).isTrue();
    assertThat(result.outputs())
        .singleElement()
        .extracting(PlayerOutput::payload)
        .isInstanceOf(FriendRosterSummaryViewOutput.class);
    FriendRosterSummaryViewOutput view =
        (FriendRosterSummaryViewOutput) result.outputs().getFirst().payload();
    assertThat(view.totalCount()).isEqualTo(4);
    assertThat(view.onlineCount()).isEqualTo(1);
    assertThat(view.offlineCount()).isEqualTo(3);
    assertThat(view.recentCount()).isEqualTo(2);
    assertThat(view.publicCount()).isEqualTo(1);
    assertThat(view.friendsOnlyCount()).isEqualTo(2);
    assertThat(view.privateCount()).isEqualTo(1);
    assertThat(view.hiddenStaffCount()).isZero();
    assertThat(view.unspecifiedVisibilityCount()).isZero();
    assertThat(view.sharedCount()).isEqualTo(2);
    assertThat(view.isolatedCount()).isEqualTo(1);
    assertThat(view.unspecifiedScopeCount()).isEqualTo(1);
    assertThat(
            new TextPlayerOutputRenderer(
                    new net.firedevops.firemud.gamesession.config.PresentationProperties())
                .render(result.outputs().getFirst()))
        .contains("Friend roster summary:")
        .contains("Linked: 4")
        .contains("Online: 1")
        .contains("Offline: 3")
        .contains("Recent offline: 2")
        .contains("Visibility public: 1")
        .contains("Visibility friends-only: 2")
        .contains("Visibility private: 1")
        .contains("Scope shared: 2")
        .contains("Scope isolated: 1");
    Mockito.verify(socialGroupsClient).getFriendRosterSummary(1L, 41L);
  }

  @Test
  void friendsAddResolvesCharacterNameToCanonicalAccountId() {
    SocialGroupsClient socialGroupsClient = Mockito.mock(SocialGroupsClient.class);
    EntityManagementClient entityManagementClient = Mockito.mock(EntityManagementClient.class);
    ScriptEventPublisher scriptEventPublisher = Mockito.mock(ScriptEventPublisher.class);
    FriendsCommandHandler handler =
        newHandler(socialGroupsClient, entityManagementClient, scriptEventPublisher);
    when(entityManagementClient.findCharacterByName(
            Mockito.any(),
            Mockito.eq(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED),
            Mockito.eq("Sora")))
        .thenReturn(
            Optional.of(
                Character.newBuilder().setId("99").setAccountId("77").setName("Sora").build()));
    when(socialGroupsClient.addFriend(1L, 41L, 77L))
        .thenReturn(AddFriendResponse.newBuilder().setSuccess(true).build());

    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommand(
                TextCommandType.FRIENDS, java.util.List.of("ADD", "Sora"), "FRIENDS ADD Sora"),
            GAMEPLAY_CONTEXT);

    assertThat(result.commandResult().accepted()).isTrue();
    assertThat(result.outputs())
        .singleElement()
        .satisfies(
            output -> {
              assertThat(output.text()).isEqualTo("Sora [acct #77] added.");
              assertThat(output.payload()).isInstanceOf(FriendMutationResultOutput.class);
              FriendMutationResultOutput mutation = (FriendMutationResultOutput) output.payload();
              assertThat(mutation.action()).isEqualTo("ADD");
              assertThat(mutation.friendAccountId()).isEqualTo(77L);
              assertThat(mutation.displayName()).isEqualTo("Sora");
              assertThat(mutation.characterName()).isEqualTo("Sora");
            });
    Mockito.verify(socialGroupsClient).addFriend(1L, 41L, 77L);
  }

  @Test
  void friendsRemoveUnlinksAccountScopedFriendAndReturnsNotice() {
    SocialGroupsClient socialGroupsClient = Mockito.mock(SocialGroupsClient.class);
    EntityManagementClient entityManagementClient = Mockito.mock(EntityManagementClient.class);
    FriendsCommandHandler handler =
        newHandler(
            socialGroupsClient, entityManagementClient, Mockito.mock(ScriptEventPublisher.class));
    when(socialGroupsClient.removeFriend(1L, 41L, 77L))
        .thenReturn(RemoveFriendResponse.newBuilder().setSuccess(true).build());

    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommand(
                TextCommandType.FRIENDS, java.util.List.of("REMOVE", "77"), "FRIENDS REMOVE 77"),
            GAMEPLAY_CONTEXT);

    assertThat(result.commandResult().accepted()).isTrue();
    assertThat(result.outputs())
        .singleElement()
        .satisfies(
            output -> {
              assertThat(output.text()).isEqualTo("Friend #77 removed.");
              assertThat(output.payload()).isInstanceOf(FriendMutationResultOutput.class);
              FriendMutationResultOutput mutation = (FriendMutationResultOutput) output.payload();
              assertThat(mutation.action()).isEqualTo("REMOVE");
              assertThat(mutation.friendAccountId()).isEqualTo(77L);
              assertThat(mutation.displayName()).isEqualTo("Friend #77");
              assertThat(mutation.characterName()).isNull();
              assertThat(mutation.ordinal()).isNull();
            });
  }

  @Test
  void friendsRemoveResolvesCharacterNameToCanonicalAccountId() {
    SocialGroupsClient socialGroupsClient = Mockito.mock(SocialGroupsClient.class);
    EntityManagementClient entityManagementClient = Mockito.mock(EntityManagementClient.class);
    FriendsCommandHandler handler =
        newHandler(
            socialGroupsClient, entityManagementClient, Mockito.mock(ScriptEventPublisher.class));
    when(entityManagementClient.findCharacterByName(
            Mockito.any(),
            Mockito.eq(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED),
            Mockito.eq("Sora")))
        .thenReturn(
            Optional.of(
                Character.newBuilder().setId("99").setAccountId("77").setName("Sora").build()));
    when(socialGroupsClient.removeFriend(1L, 41L, 77L))
        .thenReturn(RemoveFriendResponse.newBuilder().setSuccess(true).build());

    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommand(
                TextCommandType.FRIENDS,
                java.util.List.of("REMOVE", "Sora"),
                "FRIENDS REMOVE Sora"),
            GAMEPLAY_CONTEXT);

    assertThat(result.commandResult().accepted()).isTrue();
    assertThat(result.outputs())
        .singleElement()
        .satisfies(
            output -> {
              assertThat(output.text()).isEqualTo("Sora [acct #77] removed.");
              assertThat(output.payload()).isInstanceOf(FriendMutationResultOutput.class);
              FriendMutationResultOutput mutation = (FriendMutationResultOutput) output.payload();
              assertThat(mutation.action()).isEqualTo("REMOVE");
              assertThat(mutation.friendAccountId()).isEqualTo(77L);
              assertThat(mutation.displayName()).isEqualTo("Sora");
              assertThat(mutation.characterName()).isEqualTo("Sora");
            });
    Mockito.verify(socialGroupsClient).removeFriend(1L, 41L, 77L);
  }

  @Test
  void friendsRemoveByOrdinalUsesCanonicalRemoveSurface() {
    SocialGroupsClient socialGroupsClient = Mockito.mock(SocialGroupsClient.class);
    EntityManagementClient entityManagementClient = Mockito.mock(EntityManagementClient.class);
    FriendsCommandHandler handler =
        newHandler(
            socialGroupsClient, entityManagementClient, Mockito.mock(ScriptEventPublisher.class));
    when(socialGroupsClient.removeFriendByOrdinal(1L, 41L, 1))
        .thenReturn(
            RemoveFriendByOrdinalResponse.newBuilder()
                .setSuccess(true)
                .setRemovedFriend(
                    FriendRosterEntry.newBuilder()
                        .setOrdinal(1)
                        .setFriendAccountId("77")
                        .setPresence(
                            FriendPresenceEntry.newBuilder()
                                .setFriendAccountId("77")
                                .setCharacterName("Sora")
                                .build())
                        .build())
                .build());

    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommand(
                TextCommandType.FRIENDS, java.util.List.of("REMOVE", "#1"), "FRIENDS REMOVE #1"),
            GAMEPLAY_CONTEXT);

    assertThat(result.commandResult().accepted()).isTrue();
    assertThat(result.outputs())
        .singleElement()
        .satisfies(
            output -> {
              assertThat(output.text()).isEqualTo("Sora [acct #77] removed.");
              assertThat(output.payload()).isInstanceOf(FriendMutationResultOutput.class);
              FriendMutationResultOutput mutation = (FriendMutationResultOutput) output.payload();
              assertThat(mutation.action()).isEqualTo("REMOVE");
              assertThat(mutation.friendAccountId()).isEqualTo(77L);
              assertThat(mutation.displayName()).isEqualTo("Sora");
              assertThat(mutation.characterName()).isEqualTo("Sora");
              assertThat(mutation.ordinal()).isEqualTo(1);
            });
    Mockito.verify(socialGroupsClient).removeFriendByOrdinal(1L, 41L, 1);
  }

  @Test
  void friendsRemoveByOrdinalRejectsMalformedReturnedFriendAccountId() {
    SocialGroupsClient socialGroupsClient = Mockito.mock(SocialGroupsClient.class);
    EntityManagementClient entityManagementClient = Mockito.mock(EntityManagementClient.class);
    FriendsCommandHandler handler =
        newHandler(
            socialGroupsClient, entityManagementClient, Mockito.mock(ScriptEventPublisher.class));
    when(socialGroupsClient.removeFriendByOrdinal(1L, 41L, 1))
        .thenReturn(
            RemoveFriendByOrdinalResponse.newBuilder()
                .setSuccess(true)
                .setRemovedFriend(
                    FriendRosterEntry.newBuilder()
                        .setOrdinal(1)
                        .setFriendAccountId("abc")
                        .setPresence(
                            FriendPresenceEntry.newBuilder()
                                .setFriendAccountId("abc")
                                .setCharacterName("Sora")
                                .build())
                        .build())
                .build());

    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommand(
                TextCommandType.FRIENDS, java.util.List.of("REMOVE", "#1"), "FRIENDS REMOVE #1"),
            GAMEPLAY_CONTEXT);

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("FRIEND_REMOVE_UNAVAILABLE");
    assertThat(result.commandResult().errorMessage()).isEqualTo("Friend removal unavailable");
  }

  @Test
  void friendsShowReturnsCanonicalFriendDetailView() {
    SocialGroupsClient socialGroupsClient = Mockito.mock(SocialGroupsClient.class);
    EntityManagementClient entityManagementClient = Mockito.mock(EntityManagementClient.class);
    FriendsCommandHandler handler =
        newHandler(
            socialGroupsClient, entityManagementClient, Mockito.mock(ScriptEventPublisher.class));
    when(socialGroupsClient.getFriend(1L, 41L, 77L))
        .thenReturn(
            GetFriendResponse.newBuilder()
                .setFriend(
                    FriendRosterEntry.newBuilder()
                        .setFriendLinkId("11")
                        .setFriendAccountId("77")
                        .setStatus("active")
                        .setCreatedAtMs(Instant.parse("2026-04-10T01:02:03Z").toEpochMilli())
                        .setPresence(
                            FriendPresenceEntry.newBuilder()
                                .setFriendAccountId("77")
                                .setOnline(true)
                                .setCharacterName("Sora")
                                .setWorldDisplayName("Demo World")
                                .setRealmDisplayName("Live Realm")
                                .setPlayableStateScope(
                                    PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
                                .setPointerVersion(17L)
                                .setActivityState(
                                    FriendPresenceActivityState
                                        .FRIEND_PRESENCE_ACTIVITY_STATE_AUTO_AFK)
                                .build())
                        .build())
                .build());

    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommand(
                TextCommandType.FRIENDS, java.util.List.of("SHOW", "77"), "FRIENDS SHOW 77"),
            GAMEPLAY_CONTEXT);

    assertThat(result.commandResult().accepted()).isTrue();
    assertThat(result.outputs())
        .singleElement()
        .extracting(PlayerOutput::payload)
        .isInstanceOf(FriendDetailViewOutput.class);
    FriendDetailViewOutput detail = (FriendDetailViewOutput) result.outputs().getFirst().payload();
    assertThat(detail.friend().friendAccountId()).isEqualTo(77L);
    assertThat(detail.friend().friendLinkId()).isEqualTo(11L);
    assertThat(detail.friend().characterName()).isEqualTo("Sora");
    assertThat(detail.friend().playableStateScope()).isEqualTo("SHARED");
    assertThat(detail.friend().pointerVersion()).isEqualTo(17L);
    assertThat(
            new TextPlayerOutputRenderer(
                    new net.firedevops.firemud.gamesession.config.PresentationProperties())
                .render(result.outputs().getFirst()))
        .contains("Friend Sora [acct #77]")
        .contains("Presence: online in Demo World / Live Realm (idle)")
        .contains("State scope: shared")
        .contains("Pointer version: 17");
  }

  @Test
  void friendsShowByOrdinalUsesCanonicalDetailSurface() {
    SocialGroupsClient socialGroupsClient = Mockito.mock(SocialGroupsClient.class);
    EntityManagementClient entityManagementClient = Mockito.mock(EntityManagementClient.class);
    FriendsCommandHandler handler =
        newHandler(
            socialGroupsClient, entityManagementClient, Mockito.mock(ScriptEventPublisher.class));
    when(socialGroupsClient.getFriendByOrdinal(1L, 41L, 1))
        .thenReturn(
            GetFriendByOrdinalResponse.newBuilder()
                .setFriend(
                    FriendRosterEntry.newBuilder()
                        .setOrdinal(1)
                        .setFriendAccountId("77")
                        .setPresence(
                            FriendPresenceEntry.newBuilder()
                                .setFriendAccountId("77")
                                .setCharacterName("Sora")
                                .build())
                        .build())
                .build());

    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommand(
                TextCommandType.FRIENDS, java.util.List.of("SHOW", "#1"), "FRIENDS SHOW #1"),
            GAMEPLAY_CONTEXT);

    assertThat(result.commandResult().accepted()).isTrue();
    FriendDetailViewOutput detail = (FriendDetailViewOutput) result.outputs().getFirst().payload();
    assertThat(detail.friend().ordinal()).isEqualTo(1);
    Mockito.verify(socialGroupsClient).getFriendByOrdinal(1L, 41L, 1);
  }

  @Test
  void friendsMutationRejectsSelfLink() {
    SocialGroupsClient socialGroupsClient = Mockito.mock(SocialGroupsClient.class);
    EntityManagementClient entityManagementClient = Mockito.mock(EntityManagementClient.class);
    FriendsCommandHandler handler =
        newHandler(
            socialGroupsClient, entityManagementClient, Mockito.mock(ScriptEventPublisher.class));

    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommand(
                TextCommandType.FRIENDS, java.util.List.of("ADD", "41"), "FRIENDS ADD 41"),
            GAMEPLAY_CONTEXT);

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("FRIEND_SELF_LINK_FORBIDDEN");
    assertThat(result.outputs())
        .singleElement()
        .extracting(PlayerOutput::text)
        .isEqualTo(
            "ERROR FRIEND_SELF_LINK_FORBIDDEN Cannot add or remove your own account as a friend");
  }

  @Test
  void friendsMutationRejectsMissingTarget() {
    SocialGroupsClient socialGroupsClient = Mockito.mock(SocialGroupsClient.class);
    EntityManagementClient entityManagementClient = Mockito.mock(EntityManagementClient.class);
    FriendsCommandHandler handler =
        newHandler(
            socialGroupsClient, entityManagementClient, Mockito.mock(ScriptEventPublisher.class));

    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommand(TextCommandType.FRIENDS, java.util.List.of("ADD"), "FRIENDS ADD"),
            GAMEPLAY_CONTEXT);

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("INVALID_ARGUMENT");
    assertThat(result.outputs())
        .singleElement()
        .extracting(PlayerOutput::text)
        .isEqualTo("ERROR INVALID_ARGUMENT FRIENDS ADD <friendAccountId|characterName>");
  }

  @Test
  void friendsRemoveRejectsUnknownRosterOrdinal() {
    SocialGroupsClient socialGroupsClient = Mockito.mock(SocialGroupsClient.class);
    EntityManagementClient entityManagementClient = Mockito.mock(EntityManagementClient.class);
    FriendsCommandHandler handler =
        newHandler(
            socialGroupsClient, entityManagementClient, Mockito.mock(ScriptEventPublisher.class));
    when(socialGroupsClient.removeFriendByOrdinal(1L, 41L, 1))
        .thenReturn(
            RemoveFriendByOrdinalResponse.newBuilder()
                .setSuccess(false)
                .setError(
                    net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                        .setCode("NOT_FOUND")
                        .setMessage("Friend not found for ordinal=1")
                        .build())
                .build());

    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommand(
                TextCommandType.FRIENDS, java.util.List.of("REMOVE", "#1"), "FRIENDS REMOVE #1"),
            GAMEPLAY_CONTEXT);

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("FRIEND_TARGET_NOT_FOUND");
    assertThat(result.outputs())
        .singleElement()
        .extracting(PlayerOutput::text)
        .isEqualTo("ERROR FRIEND_TARGET_NOT_FOUND Friend not found for ordinal=1");
  }

  @Test
  void friendsMutationRejectsUnknownCharacterTarget() {
    SocialGroupsClient socialGroupsClient = Mockito.mock(SocialGroupsClient.class);
    EntityManagementClient entityManagementClient = Mockito.mock(EntityManagementClient.class);
    FriendsCommandHandler handler =
        newHandler(
            socialGroupsClient, entityManagementClient, Mockito.mock(ScriptEventPublisher.class));
    when(entityManagementClient.findCharacterByName(
            Mockito.any(),
            Mockito.eq(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED),
            Mockito.eq("Unknown")))
        .thenReturn(Optional.empty());

    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommand(
                TextCommandType.FRIENDS,
                java.util.List.of("ADD", "Unknown"),
                "FRIENDS ADD Unknown"),
            GAMEPLAY_CONTEXT);

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("FRIEND_TARGET_NOT_FOUND");
    assertThat(result.outputs())
        .singleElement()
        .extracting(PlayerOutput::text)
        .isEqualTo("ERROR FRIEND_TARGET_NOT_FOUND Character not found: Unknown");
  }

  @Test
  void friendsAddRejectsMalformedResolvedCharacterAccountIdAsUnavailable() {
    SocialGroupsClient socialGroupsClient = Mockito.mock(SocialGroupsClient.class);
    EntityManagementClient entityManagementClient = Mockito.mock(EntityManagementClient.class);
    FriendsCommandHandler handler =
        newHandler(
            socialGroupsClient, entityManagementClient, Mockito.mock(ScriptEventPublisher.class));
    when(entityManagementClient.findCharacterByName(
            Mockito.any(),
            Mockito.eq(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED),
            Mockito.eq("Sora")))
        .thenReturn(
            Optional.of(Character.newBuilder().setName("Sora").setAccountId("abc").build()));

    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommand(
                TextCommandType.FRIENDS, java.util.List.of("ADD", "Sora"), "FRIENDS ADD Sora"),
            GAMEPLAY_CONTEXT);

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("FRIEND_ADD_UNAVAILABLE");
    assertThat(result.outputs())
        .singleElement()
        .extracting(PlayerOutput::text)
        .isEqualTo("ERROR FRIEND_ADD_UNAVAILABLE Friend add unavailable");
    Mockito.verifyNoInteractions(socialGroupsClient);
  }

  @Test
  void friendsListTreatsMalformedFriendLinkIdAsAbsent() {
    SocialGroupsClient socialGroupsClient = Mockito.mock(SocialGroupsClient.class);
    EntityManagementClient entityManagementClient = Mockito.mock(EntityManagementClient.class);
    FriendsCommandHandler handler =
        newHandler(
            socialGroupsClient, entityManagementClient, Mockito.mock(ScriptEventPublisher.class));
    when(socialGroupsClient.listFriends(1L, 41L))
        .thenReturn(
            ListFriendsResponse.newBuilder()
                .addFriends(
                    FriendRosterEntry.newBuilder()
                        .setFriendLinkId("abc")
                        .setFriendAccountId("77")
                        .setStatus("active")
                        .setPresence(
                            FriendPresenceEntry.newBuilder().setFriendAccountId("77").build())
                        .build())
                .build());

    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommand(TextCommandType.FRIENDS, java.util.List.of(), "FRIENDS"),
            GAMEPLAY_CONTEXT);

    assertThat(result.commandResult().accepted()).isTrue();
    FriendPresenceViewOutput view =
        (FriendPresenceViewOutput) result.outputs().getFirst().payload();
    assertThat(view.friends())
        .singleElement()
        .satisfies(entry -> assertThat(entry.friendLinkId()).isNull());
  }

  @Test
  void friendsListRejectsMalformedFriendAccountIdPayload() {
    SocialGroupsClient socialGroupsClient = Mockito.mock(SocialGroupsClient.class);
    EntityManagementClient entityManagementClient = Mockito.mock(EntityManagementClient.class);
    FriendsCommandHandler handler =
        newHandler(
            socialGroupsClient, entityManagementClient, Mockito.mock(ScriptEventPublisher.class));
    when(socialGroupsClient.listFriends(1L, 41L))
        .thenReturn(
            ListFriendsResponse.newBuilder()
                .addFriends(
                    FriendRosterEntry.newBuilder()
                        .setFriendAccountId("abc")
                        .setStatus("active")
                        .setPresence(
                            FriendPresenceEntry.newBuilder().setFriendAccountId("abc").build())
                        .build())
                .build());

    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommand(TextCommandType.FRIENDS, java.util.List.of(), "FRIENDS"),
            GAMEPLAY_CONTEXT);

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("FRIEND_PRESENCE_UNAVAILABLE");
    assertThat(result.commandResult().errorMessage()).isEqualTo("Friend presence unavailable");
  }

  @Test
  void friendsVisibilityShowsCurrentCanonicalPolicy() {
    SocialGroupsClient socialGroupsClient = Mockito.mock(SocialGroupsClient.class);
    EntityManagementClient entityManagementClient = Mockito.mock(EntityManagementClient.class);
    FriendsCommandHandler handler =
        newHandler(
            socialGroupsClient, entityManagementClient, Mockito.mock(ScriptEventPublisher.class));
    when(socialGroupsClient.getFriendPresencePolicy(1L, 41L))
        .thenReturn(
            GetFriendPresencePolicyResponse.newBuilder()
                .setCurrentPolicy(
                    net.firedevops.firemud.socialgroups.v1.FriendPresenceVisibilityPolicy
                        .FRIEND_PRESENCE_VISIBILITY_POLICY_FRIENDS_ONLY)
                .build());

    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommand(
                TextCommandType.FRIENDS, java.util.List.of("VISIBILITY"), "FRIENDS VISIBILITY"),
            GAMEPLAY_CONTEXT);

    assertThat(result.commandResult().accepted()).isTrue();
    assertThat(result.outputs())
        .singleElement()
        .extracting(PlayerOutput::payload)
        .isInstanceOf(FriendPresencePolicyViewOutput.class);
    FriendPresencePolicyViewOutput view =
        (FriendPresencePolicyViewOutput) result.outputs().getFirst().payload();
    assertThat(view.currentPolicy()).isEqualTo("FRIENDS_ONLY");
    assertThat(view.options())
        .anySatisfy(
            option -> {
              assertThat(option.policy()).isEqualTo("FRIENDS_ONLY");
              assertThat(option.current()).isTrue();
              assertThat(option.selectable()).isTrue();
            });
    assertThat(
            new TextPlayerOutputRenderer(
                    new net.firedevops.firemud.gamesession.config.PresentationProperties())
                .render(result.outputs().getFirst()))
        .contains("Friend presence visibility: FRIENDS_ONLY")
        .contains("Use FRIENDS VISIBILITY <PUBLIC|FRIENDS_ONLY|PRIVATE>.");
    Mockito.verify(socialGroupsClient).getFriendPresencePolicy(1L, 41L);
  }

  @Test
  void friendsVisibilityViewFailsClosedWhenCanonicalPolicyReadIsUnavailable() {
    SocialGroupsClient socialGroupsClient = Mockito.mock(SocialGroupsClient.class);
    EntityManagementClient entityManagementClient = Mockito.mock(EntityManagementClient.class);
    FriendsCommandHandler handler =
        newHandler(
            socialGroupsClient, entityManagementClient, Mockito.mock(ScriptEventPublisher.class));
    when(socialGroupsClient.getFriendPresencePolicy(1L, 41L))
        .thenReturn(
            GetFriendPresencePolicyResponse.newBuilder()
                .setError(
                    net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                        .setCode("SOCIAL_UNAVAILABLE")
                        .setMessage("Policy service unavailable")
                        .build())
                .build());

    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommand(
                TextCommandType.FRIENDS, java.util.List.of("VISIBILITY"), "FRIENDS VISIBILITY"),
            GAMEPLAY_CONTEXT);

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("SOCIAL_UNAVAILABLE");
    assertThat(result.outputs())
        .singleElement()
        .extracting(PlayerOutput::text)
        .isEqualTo("ERROR SOCIAL_UNAVAILABLE Policy service unavailable");
  }

  @Test
  void friendsVisibilityUpdatesCanonicalPolicy() {
    SocialGroupsClient socialGroupsClient = Mockito.mock(SocialGroupsClient.class);
    EntityManagementClient entityManagementClient = Mockito.mock(EntityManagementClient.class);
    ScriptEventPublisher scriptEventPublisher = Mockito.mock(ScriptEventPublisher.class);
    FriendsCommandHandler handler =
        newHandler(socialGroupsClient, entityManagementClient, scriptEventPublisher);
    when(socialGroupsClient.updateFriendPresencePolicy(
            1L,
            41L,
            net.firedevops.firemud.socialgroups.v1.FriendPresenceVisibilityPolicy
                .FRIEND_PRESENCE_VISIBILITY_POLICY_PRIVATE))
        .thenReturn(
            UpdateFriendPresencePolicyResponse.newBuilder()
                .setSuccess(true)
                .setCurrentPolicy(
                    net.firedevops.firemud.socialgroups.v1.FriendPresenceVisibilityPolicy
                        .FRIEND_PRESENCE_VISIBILITY_POLICY_PRIVATE)
                .build());

    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommand(
                TextCommandType.FRIENDS,
                java.util.List.of("VISIBILITY", "PRIVATE"),
                "FRIENDS VISIBILITY PRIVATE"),
            GAMEPLAY_CONTEXT);

    assertThat(result.commandResult().accepted()).isTrue();
    assertThat(result.outputs()).hasSize(2);
    assertThat(result.outputs().get(0).text())
        .isEqualTo("Friend presence visibility set to PRIVATE.");
    assertThat(result.outputs().get(1).payload())
        .isInstanceOf(FriendPresencePolicyViewOutput.class);
    FriendPresencePolicyViewOutput view =
        (FriendPresencePolicyViewOutput) result.outputs().get(1).payload();
    assertThat(view.currentPolicy()).isEqualTo("PRIVATE");
    assertThat(view.options())
        .anySatisfy(
            option -> {
              assertThat(option.policy()).isEqualTo("PRIVATE");
              assertThat(option.current()).isTrue();
            });
    Mockito.verify(socialGroupsClient)
        .updateFriendPresencePolicy(
            1L,
            41L,
            net.firedevops.firemud.socialgroups.v1.FriendPresenceVisibilityPolicy
                .FRIEND_PRESENCE_VISIBILITY_POLICY_PRIVATE);
    Mockito.verify(scriptEventPublisher)
        .publishCommandEvent(
            Mockito.any(),
            Mockito.argThat(gameplayCommand -> "FRIENDS".equals(gameplayCommand.getCommandName())));
  }

  @Test
  void friendsVisibilityUpdateFailsClosedWhenCanonicalPolicyUpdateIsUnavailable() {
    SocialGroupsClient socialGroupsClient = Mockito.mock(SocialGroupsClient.class);
    EntityManagementClient entityManagementClient = Mockito.mock(EntityManagementClient.class);
    FriendsCommandHandler handler =
        newHandler(
            socialGroupsClient, entityManagementClient, Mockito.mock(ScriptEventPublisher.class));
    when(socialGroupsClient.updateFriendPresencePolicy(
            1L,
            41L,
            net.firedevops.firemud.socialgroups.v1.FriendPresenceVisibilityPolicy
                .FRIEND_PRESENCE_VISIBILITY_POLICY_PRIVATE))
        .thenReturn(
            UpdateFriendPresencePolicyResponse.newBuilder()
                .setSuccess(false)
                .setError(
                    net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                        .setCode("SOCIAL_UNAVAILABLE")
                        .setMessage("Policy update unavailable")
                        .build())
                .build());

    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommand(
                TextCommandType.FRIENDS,
                java.util.List.of("VISIBILITY", "PRIVATE"),
                "FRIENDS VISIBILITY PRIVATE"),
            GAMEPLAY_CONTEXT);

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("SOCIAL_UNAVAILABLE");
    assertThat(result.outputs())
        .singleElement()
        .extracting(PlayerOutput::text)
        .isEqualTo("ERROR SOCIAL_UNAVAILABLE Policy update unavailable");
  }

  @Test
  void friendsVisibilityRejectsReservedHiddenStaffPolicy() {
    SocialGroupsClient socialGroupsClient = Mockito.mock(SocialGroupsClient.class);
    EntityManagementClient entityManagementClient = Mockito.mock(EntityManagementClient.class);
    FriendsCommandHandler handler =
        newHandler(
            socialGroupsClient, entityManagementClient, Mockito.mock(ScriptEventPublisher.class));

    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommand(
                TextCommandType.FRIENDS,
                java.util.List.of("VISIBILITY", "HIDDEN_STAFF"),
                "FRIENDS VISIBILITY HIDDEN_STAFF"),
            GAMEPLAY_CONTEXT);

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("INVALID_ARGUMENT");
    assertThat(result.outputs())
        .singleElement()
        .extracting(PlayerOutput::text)
        .isEqualTo(
            "ERROR INVALID_ARGUMENT HIDDEN_STAFF is reserved and cannot be set from gameplay");
    Mockito.verify(socialGroupsClient, Mockito.never())
        .updateFriendPresencePolicy(Mockito.anyLong(), Mockito.anyLong(), Mockito.any());
  }

  @Test
  void friendsUnknownSubcommandFailsClosedWithUsage() {
    SocialGroupsClient socialGroupsClient = Mockito.mock(SocialGroupsClient.class);
    EntityManagementClient entityManagementClient = Mockito.mock(EntityManagementClient.class);
    FriendsCommandHandler handler =
        newHandler(
            socialGroupsClient, entityManagementClient, Mockito.mock(ScriptEventPublisher.class));

    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommand(TextCommandType.FRIENDS, java.util.List.of("BOGUS"), "FRIENDS BOGUS"),
            GAMEPLAY_CONTEXT);

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("INVALID_ARGUMENT");
    assertThat(result.outputs())
        .singleElement()
        .extracting(PlayerOutput::text)
        .isEqualTo(
            "ERROR INVALID_ARGUMENT FRIENDS [ADD|REMOVE|SHOW|SUMMARY|VISIBILITY|ONLINE|OFFLINE|RECENT|PUBLIC|FRIENDS_ONLY|PRIVATE|HIDDEN_STAFF|UNSPECIFIED_VISIBILITY|SHARED|ISOLATED|UNSPECIFIED_SCOPE]");
    Mockito.verifyNoInteractions(socialGroupsClient);
  }

  @Test
  void friendsUnspecifiedVisibilityUsesCanonicalFilterAlias() {
    SocialGroupsClient socialGroupsClient = Mockito.mock(SocialGroupsClient.class);
    EntityManagementClient entityManagementClient = Mockito.mock(EntityManagementClient.class);
    FriendsCommandHandler handler =
        newHandler(
            socialGroupsClient, entityManagementClient, Mockito.mock(ScriptEventPublisher.class));
    when(socialGroupsClient.listFriends(
            1L, 41L, FriendRosterFilter.FRIEND_ROSTER_FILTER_UNSPECIFIED_VISIBILITY))
        .thenReturn(
            ListFriendsResponse.newBuilder()
                .setFilter(FriendRosterFilter.FRIEND_ROSTER_FILTER_UNSPECIFIED_VISIBILITY)
                .build());

    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommand(
                TextCommandType.FRIENDS,
                java.util.List.of("UNSPECIFIED_VISIBILITY"),
                "FRIENDS UNSPECIFIED_VISIBILITY"),
            GAMEPLAY_CONTEXT);

    assertThat(result.commandResult().accepted()).isTrue();
    FriendPresenceViewOutput view =
        (FriendPresenceViewOutput) result.outputs().getFirst().payload();
    assertThat(view.filter()).isEqualTo("UNSPECIFIED_VISIBILITY");
    Mockito.verify(socialGroupsClient)
        .listFriends(1L, 41L, FriendRosterFilter.FRIEND_ROSTER_FILTER_UNSPECIFIED_VISIBILITY);
  }
}
