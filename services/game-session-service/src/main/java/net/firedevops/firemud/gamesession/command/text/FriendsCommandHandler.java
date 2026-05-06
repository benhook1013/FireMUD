package net.firedevops.firemud.gamesession.command.text;

import io.micrometer.core.annotation.Timed;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.gamesession.client.SocialGroupsClient;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.entity.GameplayCommand;
import net.firedevops.firemud.gamesession.presentation.FriendPresenceViewOutput;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.service.ScriptEventPublisher;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.socialgroups.v1.FriendPresenceActivityState;
import net.firedevops.firemud.socialgroups.v1.FriendPresenceEntry;
import net.firedevops.firemud.socialgroups.v1.FriendRecentPresenceDisposition;
import net.firedevops.firemud.socialgroups.v1.ListFriendPresenceResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class FriendsCommandHandler {
  private static final Logger LOG = LoggerFactory.getLogger(FriendsCommandHandler.class);
  private final SocialGroupsClient socialGroupsClient;
  private final ScriptEventPublisher scriptEventPublisher;

  public FriendsCommandHandler(
      SocialGroupsClient socialGroupsClient, ScriptEventPublisher scriptEventPublisher) {
    this.socialGroupsClient = socialGroupsClient;
    this.scriptEventPublisher = scriptEventPublisher;
  }

  @Timed(value = "gamesession.command.friends")
  public TextCommandInterpretationResult handle(SessionContext context) {
    ListFriendPresenceResponse response =
        socialGroupsClient.listFriendPresence(context.tenantId(), context.accountId());
    if (response.hasError()) {
      String message =
          response.getError().getMessage().isBlank()
              ? "Friend presence unavailable"
              : response.getError().getMessage();
      return new TextCommandInterpretationResult(
          CommandEnqueueResult.failure("FRIEND_PRESENCE_UNAVAILABLE", message),
          List.of(PlayerOutput.error("FRIEND_PRESENCE_UNAVAILABLE", message)));
    }
    publishCommandEvent(context);
    return new TextCommandInterpretationResult(
        CommandEnqueueResult.success(),
        List.of(PlayerOutput.view(toView(response.getPresencesList()))));
  }

  private void publishCommandEvent(SessionContext context) {
    try {
      GameplayCommand gameplayCommand = new GameplayCommand();
      gameplayCommand.setCommandId("friends-" + UUID.randomUUID());
      gameplayCommand.setCommandName(TextCommandType.FRIENDS.name());
      scriptEventPublisher.publishCommandEvent(context, gameplayCommand);
    } catch (RuntimeException ex) {
      LOG.warn(
          "Friends script event publish failed tenantId={} gameInstanceId={} characterId={}",
          context.tenantId(),
          context.gameInstanceId(),
          context.characterId(),
          ex);
    }
  }

  private FriendPresenceViewOutput toView(List<FriendPresenceEntry> entries) {
    return new FriendPresenceViewOutput(
        java.util.stream.IntStream.range(0, entries.size())
            .mapToObj(index -> toEntry(index + 1, entries.get(index)))
            .toList());
  }

  private FriendPresenceViewOutput.Entry toEntry(int ordinal, FriendPresenceEntry entry) {
    long friendAccountId = parseLong(entry.getFriendAccountId());
    String characterName =
        entry.getCharacterName().isBlank() ? null : entry.getCharacterName().trim();
    return new FriendPresenceViewOutput.Entry(
        ordinal,
        friendAccountId,
        characterName != null ? characterName : "Friend #" + friendAccountId,
        entry.getOnline(),
        blankToNull(entry.getWorldSlug()),
        blankToNull(entry.getWorldDisplayName()),
        blankToNull(entry.getRealmSlug()),
        blankToNull(entry.getRealmDisplayName()),
        characterName,
        activityState(entry.getActivityState()),
        entry.getLastSeenAtMs() > 0 ? entry.getLastSeenAtMs() : null,
        recentDisposition(entry.getRecentDisposition()));
  }

  private String activityState(FriendPresenceActivityState activityState) {
    return switch (activityState) {
      case FRIEND_PRESENCE_ACTIVITY_STATE_ACTIVE -> "ACTIVE";
      case FRIEND_PRESENCE_ACTIVITY_STATE_AUTO_AFK -> "AUTO_AFK";
      case FRIEND_PRESENCE_ACTIVITY_STATE_EXPLICIT_AFK -> "EXPLICIT_AFK";
      default -> null;
    };
  }

  private long parseLong(String value) {
    if (value == null || value.isBlank()) {
      return 0L;
    }
    return Long.parseLong(value);
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private String recentDisposition(FriendRecentPresenceDisposition disposition) {
    return switch (disposition) {
      case FRIEND_RECENT_PRESENCE_DISPOSITION_TRANSPORT_LOSS -> "TRANSPORT_LOSS";
      case FRIEND_RECENT_PRESENCE_DISPOSITION_LOGOUT -> "LOGOUT";
      case FRIEND_RECENT_PRESENCE_DISPOSITION_TAKEOVER -> "TAKEOVER";
      default -> null;
    };
  }
}
