package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Instant;
import net.firedevops.firemud.gamesession.client.SocialGroupsClient;
import net.firedevops.firemud.gamesession.presentation.FriendPresenceViewOutput;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.presentation.TextPlayerOutputRenderer;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.socialgroups.v1.FriendPresenceActivityState;
import net.firedevops.firemud.socialgroups.v1.FriendPresenceEntry;
import net.firedevops.firemud.socialgroups.v1.FriendRecentPresenceDisposition;
import net.firedevops.firemud.socialgroups.v1.ListFriendPresenceResponse;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class FriendsCommandHandlerTest {

  @Test
  void friendsMapsVisiblePresenceToTypedViewAndText() {
    SocialGroupsClient socialGroupsClient = Mockito.mock(SocialGroupsClient.class);
    FriendsCommandHandler handler = new FriendsCommandHandler(socialGroupsClient);
    when(socialGroupsClient.listFriendPresence(1L, 41L))
        .thenReturn(
            ListFriendPresenceResponse.newBuilder()
                .addPresences(
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
                            FriendPresenceActivityState.FRIEND_PRESENCE_ACTIVITY_STATE_AUTO_AFK)
                        .build())
                .build());

    TextCommandInterpretationResult result =
        handler.handle(
            new SessionContext(7L, 1L, 41L, "demo@example.com", 99L, "Emberline", 7L, "R-1", null));

    assertThat(result.commandResult().accepted()).isTrue();
    assertThat(result.outputs())
        .singleElement()
        .extracting(PlayerOutput::payload)
        .isInstanceOf(FriendPresenceViewOutput.class);
    FriendPresenceViewOutput view =
        (FriendPresenceViewOutput) result.outputs().getFirst().payload();
    assertThat(view.friends())
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(entry.friendAccountId()).isEqualTo(77L);
              assertThat(entry.displayName()).isEqualTo("Sora");
              assertThat(entry.activityState()).isEqualTo("AUTO_AFK");
            });
    assertThat(
            new TextPlayerOutputRenderer(
                    new net.firedevops.firemud.gamesession.config.PresentationProperties())
                .render(result.outputs().getFirst()))
        .contains("Sora - online in Demo World / Live Realm (idle)");
  }

  @Test
  void friendsFallsBackToBoundedOfflineLabelWhenDetailsAreSuppressed() {
    SocialGroupsClient socialGroupsClient = Mockito.mock(SocialGroupsClient.class);
    FriendsCommandHandler handler = new FriendsCommandHandler(socialGroupsClient);
    when(socialGroupsClient.listFriendPresence(1L, 41L))
        .thenReturn(
            ListFriendPresenceResponse.newBuilder()
                .addPresences(
                    FriendPresenceEntry.newBuilder()
                        .setFriendAccountId("77")
                        .setOnline(false)
                        .setLastSeenAtMs(Instant.parse("2026-04-11T06:15:30Z").toEpochMilli())
                        .setRecentDisposition(
                            FriendRecentPresenceDisposition
                                .FRIEND_RECENT_PRESENCE_DISPOSITION_LOGOUT)
                        .build())
                .build());

    TextCommandInterpretationResult result =
        handler.handle(
            new SessionContext(7L, 1L, 41L, "demo@example.com", 99L, "Emberline", 7L, "R-1", null));

    FriendPresenceViewOutput view =
        (FriendPresenceViewOutput) result.outputs().getFirst().payload();
    assertThat(view.friends())
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(entry.displayName()).isEqualTo("Friend #77");
              assertThat(entry.lastSeenAtEpochMs())
                  .isEqualTo(Instant.parse("2026-04-11T06:15:30Z").toEpochMilli());
              assertThat(entry.recentDisposition()).isEqualTo("LOGOUT");
            });
  }
}
