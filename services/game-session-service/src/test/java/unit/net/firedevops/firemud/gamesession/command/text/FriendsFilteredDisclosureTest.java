package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.firedevops.firemud.gamesession.client.EntityManagementClient;
import net.firedevops.firemud.gamesession.client.SocialGroupsClient;
import net.firedevops.firemud.gamesession.presentation.FriendPresenceViewOutput;
import net.firedevops.firemud.gamesession.presentation.TextPlayerOutputRenderer;
import net.firedevops.firemud.gamesession.service.ScriptEventPublisher;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.socialgroups.v1.FriendPresenceEntry;
import net.firedevops.firemud.socialgroups.v1.FriendPresenceVisibilityPolicy;
import net.firedevops.firemud.socialgroups.v1.FriendRosterEntry;
import net.firedevops.firemud.socialgroups.v1.FriendRosterFilter;
import net.firedevops.firemud.socialgroups.v1.ListFriendsResponse;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;

class FriendsFilteredDisclosureTest {
  private static final String CALLER_ACCOUNT_ID = "a0000000-0000-4000-8000-000000000041";
  private static final String OTHER_FRIEND_ACCOUNT_ID = "c0000000-0000-4000-8000-000000000088";

  private static final SessionContext GAMEPLAY_CONTEXT =
      new SessionContext(
          7L,
          1L,
          CALLER_ACCOUNT_ID,
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

  @ParameterizedTest
  @CsvSource({
    "ONLINE,PRIVATE", "RECENT,PRIVATE",
    "ONLINE,MISSING", "RECENT,MISSING",
    "ONLINE,UNKNOWN", "RECENT,UNKNOWN"
  })
  void nonDisclosableMatchesCannotRevealPresence(String filter, String policy) throws Exception {
    FriendPresenceViewOutput view =
        render(
            filter, roster(filter, 1, entry(2, OTHER_FRIEND_ACCOUNT_ID, filter, policy, "Secret")));

    assertThat(view.filter()).isEqualTo(filter);
    assertThat(view.totalCount()).isEqualTo(20);
    assertThat(view.matchCount()).isZero();
    assertThat(view.friends()).isEmpty();
    String json = new ObjectMapper().writeValueAsString(view);
    assertThat(json).doesNotContain("Secret", OTHER_FRIEND_ACCOUNT_ID, "1744336000000");
    assertThat(new ObjectMapper().readTree(json).path("matchCount").asInt()).isZero();
    assertThat(text(view))
        .isEqualTo("Friends " + filter + ": no matching friends.")
        .doesNotContain("[", "Secret", OTHER_FRIEND_ACCOUNT_ID);
  }

  @ParameterizedTest
  @CsvSource({
    "ONLINE,PUBLIC,true", "RECENT,PUBLIC,true",
    "ONLINE,FRIENDS_ONLY,true", "RECENT,FRIENDS_ONLY,true",
    "ONLINE,PUBLIC,false", "RECENT,FRIENDS_ONLY,false"
  })
  void mixedRowsKeepOnlyDisclosedMatchesAndOriginalOrdinals(
      String filter, String policy, boolean matchingMetadata) throws Exception {
    String visible = "d0000000-0000-4000-8000-000000000099";
    String wrongState = "e0000000-0000-4000-8000-000000000100";
    ListFriendsResponse response =
        roster(
            matchingMetadata ? filter : "ALL",
            19,
            entry(2, OTHER_FRIEND_ACCOUNT_ID, filter, "PRIVATE", "Secret"),
            entry(7, visible, filter, policy, "Visible"),
            entry(9, wrongState, "ONLINE".equals(filter) ? "RECENT" : "ONLINE", policy, "Wrong"));
    FriendPresenceViewOutput view = render(filter, response);

    assertThat(view.totalCount()).isEqualTo(20);
    assertThat(view.matchCount()).isEqualTo(1);
    assertThat(view.friends())
        .singleElement()
        .satisfies(
            value -> {
              assertThat(value.ordinal()).isEqualTo(7);
              assertThat(value.friendAccountId()).isEqualTo(visible);
            });
    String json = new ObjectMapper().writeValueAsString(view);
    assertThat(json)
        .contains(visible)
        .doesNotContain("Secret", "Wrong", OTHER_FRIEND_ACCOUNT_ID, wrongState);
    assertThat(new ObjectMapper().readTree(json).path("friends").size()).isEqualTo(1);
    assertThat(text(view))
        .startsWith("Friends " + filter + ":\n7) Visible")
        .doesNotContain("[19/20]", "[1/20]", "Secret", "Wrong");
  }

  @ParameterizedTest
  @CsvSource({"ALL", "PRIVATE", "SHARED", "OFFLINE"})
  void unrelatedFiltersKeepExistingMetadataAndEmptyText(String filter) {
    FriendPresenceViewOutput view = render(filter, roster(filter, 19));
    assertThat(view.totalCount()).isEqualTo(20);
    assertThat(view.matchCount()).isEqualTo(19);
    assertThat(view.friends()).isEmpty();
    assertThat(text(view))
        .isEqualTo(
            "ALL".equals(filter)
                ? "Friends [0]: no linked friends. Use FRIENDS ADD <friendAccountId|characterName>."
                : "Friends " + filter + " [0/20]: no matching friends.");
  }

  private FriendPresenceViewOutput render(String filter, ListFriendsResponse response) {
    SocialGroupsClient social = Mockito.mock(SocialGroupsClient.class);
    FriendRosterFilter requested = rosterFilter(filter);
    if ("ALL".equals(filter)) {
      when(social.listFriends(1L, CALLER_ACCOUNT_ID)).thenReturn(response);
    } else {
      when(social.listFriends(1L, CALLER_ACCOUNT_ID, requested)).thenReturn(response);
    }
    FriendsCommandHandler handler =
        newHandler(
            social,
            Mockito.mock(EntityManagementClient.class),
            Mockito.mock(ScriptEventPublisher.class));
    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommand(
                TextCommandType.FRIENDS, java.util.List.of(filter), "FRIENDS " + filter),
            GAMEPLAY_CONTEXT);
    assertThat(result.commandResult().accepted()).isTrue();
    if ("ALL".equals(filter)) {
      Mockito.verify(social).listFriends(1L, CALLER_ACCOUNT_ID);
    } else {
      Mockito.verify(social).listFriends(1L, CALLER_ACCOUNT_ID, requested);
    }
    return (FriendPresenceViewOutput) result.outputs().getFirst().payload();
  }

  private static FriendRosterFilter rosterFilter(String filter) {
    return switch (filter) {
      case "ONLINE" -> FriendRosterFilter.FRIEND_ROSTER_FILTER_ONLINE;
      case "RECENT" -> FriendRosterFilter.FRIEND_ROSTER_FILTER_RECENT;
      case "PRIVATE" -> FriendRosterFilter.FRIEND_ROSTER_FILTER_PRIVATE;
      case "SHARED" -> FriendRosterFilter.FRIEND_ROSTER_FILTER_SHARED;
      case "OFFLINE" -> FriendRosterFilter.FRIEND_ROSTER_FILTER_OFFLINE;
      default -> FriendRosterFilter.FRIEND_ROSTER_FILTER_ALL;
    };
  }

  private static ListFriendsResponse roster(
      String filter, int count, FriendRosterEntry... entries) {
    return ListFriendsResponse.newBuilder()
        .setFilter(rosterFilter(filter))
        .setTotalCount(20)
        .setMatchCount(count)
        .addAllFriends(java.util.List.of(entries))
        .build();
  }

  private static FriendRosterEntry entry(
      int ordinal, String id, String state, String policy, String name) {
    var presence =
        FriendPresenceEntry.newBuilder()
            .setFriendAccountId(id)
            .setOnline("ONLINE".equals(state))
            .setLastSeenAtMs(1_744_336_000_000L)
            .setCharacterName(name);
    switch (policy) {
      case "PUBLIC" ->
          presence.setVisibilityPolicy(
              FriendPresenceVisibilityPolicy.FRIEND_PRESENCE_VISIBILITY_POLICY_PUBLIC);
      case "FRIENDS_ONLY" ->
          presence.setVisibilityPolicy(
              FriendPresenceVisibilityPolicy.FRIEND_PRESENCE_VISIBILITY_POLICY_FRIENDS_ONLY);
      case "PRIVATE" ->
          presence.setVisibilityPolicy(
              FriendPresenceVisibilityPolicy.FRIEND_PRESENCE_VISIBILITY_POLICY_PRIVATE);
      case "UNKNOWN" -> presence.setVisibilityPolicyValue(999);
      default -> {}
    }
    return FriendRosterEntry.newBuilder()
        .setOrdinal(ordinal)
        .setFriendAccountId(id)
        .setPresence(presence)
        .build();
  }

  private static String text(FriendPresenceViewOutput view) {
    return new TextPlayerOutputRenderer(
            new net.firedevops.firemud.gamesession.config.PresentationProperties())
        .render(net.firedevops.firemud.gamesession.presentation.PlayerOutput.view(view));
  }
}
