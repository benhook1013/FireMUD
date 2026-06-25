package net.firedevops.firemud.gamesession.command.text;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.client.EntityManagementClient;
import net.firedevops.firemud.gamesession.client.SocialGroupsClient;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.entity.GameplayCommand;
import net.firedevops.firemud.gamesession.presentation.FriendDetailViewOutput;
import net.firedevops.firemud.gamesession.presentation.FriendMutationResultOutput;
import net.firedevops.firemud.gamesession.presentation.FriendPresencePolicyViewOutput;
import net.firedevops.firemud.gamesession.presentation.FriendPresenceViewOutput;
import net.firedevops.firemud.gamesession.presentation.FriendRosterSummaryViewOutput;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@SuppressFBWarnings(
    value = "CT_CONSTRUCTOR_THROW",
    justification =
        "Constructor validation only guards injected collaborators before the handler is used.")
@Component
public class FriendsCommandHandler {
  private static final Logger LOG = LoggerFactory.getLogger(FriendsCommandHandler.class);
  private final SocialGroupsClient socialGroupsClient;
  private final EntityManagementClient entityManagementClient;
  private final ScriptEventPublisher scriptEventPublisher;

  public FriendsCommandHandler(
      SocialGroupsClient socialGroupsClient,
      EntityManagementClient entityManagementClient,
      ScriptEventPublisher scriptEventPublisher) {
    this.socialGroupsClient =
        Objects.requireNonNull(socialGroupsClient, "socialGroupsClient must not be null");
    this.entityManagementClient =
        Objects.requireNonNull(entityManagementClient, "entityManagementClient must not be null");
    this.scriptEventPublisher =
        Objects.requireNonNull(scriptEventPublisher, "scriptEventPublisher must not be null");
  }

  @Timed(value = "gamesession.command.friends")
  public TextCommandInterpretationResult handle(TextCommand command, SessionContext context) {
    FriendAction action = parseAction(command);
    if (action.kind() == FriendActionKind.INVALID) {
      return invalidUsage(action.invalidUsage());
    }
    if (action.kind() == FriendActionKind.LIST) {
      return handleList(context, action.listFilter());
    }
    if (action.kind() == FriendActionKind.SUMMARY) {
      return handleSummary(context);
    }
    if (action.kind() == FriendActionKind.VISIBILITY) {
      return StringUtils.hasText(action.targetToken())
          ? handleVisibilityUpdate(context, action.targetToken())
          : handleVisibilityView(context);
    }
    if (action.kind() == FriendActionKind.DETAIL && !StringUtils.hasText(action.targetToken())) {
      return invalidUsage("FRIENDS SHOW <friendAccountId|characterName|#entryNumber>");
    }
    if (action.kind() == FriendActionKind.REMOVE && isOrdinalToken(action.targetToken())) {
      return handleRemoveByOrdinal(context, action.targetToken());
    }
    if (action.kind() == FriendActionKind.DETAIL && isOrdinalToken(action.targetToken())) {
      return handleDetailByOrdinal(context, action.targetToken());
    }
    if (!StringUtils.hasText(action.targetToken())) {
      return invalidUsage("FRIENDS " + action.keyword() + " <friendAccountId|characterName>");
    }
    ResolvedFriendTarget resolvedTarget = resolveTarget(context, action);
    if (resolvedTarget.errorResult() != null) {
      return resolvedTarget.errorResult();
    }
    return switch (action.kind()) {
      case ADD -> handleAdd(context, resolvedTarget);
      case REMOVE -> handleRemove(context, resolvedTarget);
      case DETAIL -> handleDetail(context, resolvedTarget);
      case SUMMARY -> handleSummary(context);
      case VISIBILITY -> throw new IllegalStateException("Visibility branch should have returned");
      case LIST -> handleList(context, action.listFilter());
      case INVALID -> throw new IllegalStateException("Invalid branch should have returned");
    };
  }

  private TextCommandInterpretationResult handleList(
      SessionContext context, FriendListFilter filter) {
    ListFriendsResponse response =
        filter == FriendListFilter.ALL
            ? socialGroupsClient.listFriends(context.tenantId(), context.accountId())
            : socialGroupsClient.listFriends(
                context.tenantId(), context.accountId(), mapRosterFilter(filter));
    if (response == null && filter != FriendListFilter.ALL) {
      response = socialGroupsClient.listFriends(context.tenantId(), context.accountId());
    }
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
        CommandEnqueueResult.success(), List.of(PlayerOutput.view(toView(response, filter))));
  }

  private TextCommandInterpretationResult handleAdd(
      SessionContext context, ResolvedFriendTarget target) {
    if (target.friendAccountId() == context.accountId()) {
      return friendTargetError(
          "FRIEND_SELF_LINK_FORBIDDEN", "Cannot add or remove your own account as a friend");
    }
    AddFriendResponse response =
        socialGroupsClient.addFriend(
            context.tenantId(), context.accountId(), target.friendAccountId());
    if (response.hasError() || !response.getSuccess()) {
      String message =
          response.hasError() && !response.getError().getMessage().isBlank()
              ? response.getError().getMessage()
              : "Friend add unavailable";
      return new TextCommandInterpretationResult(
          CommandEnqueueResult.failure("FRIEND_ADD_UNAVAILABLE", message),
          List.of(PlayerOutput.error("FRIEND_ADD_UNAVAILABLE", message)));
    }
    publishCommandEvent(context);
    return new TextCommandInterpretationResult(
        CommandEnqueueResult.success(),
        List.of(PlayerOutput.notice(friendMutation("ADD", target))));
  }

  private TextCommandInterpretationResult handleRemove(
      SessionContext context, ResolvedFriendTarget target) {
    if (target.friendAccountId() == context.accountId()) {
      return friendTargetError(
          "FRIEND_SELF_LINK_FORBIDDEN", "Cannot add or remove your own account as a friend");
    }
    RemoveFriendResponse response =
        socialGroupsClient.removeFriend(
            context.tenantId(), context.accountId(), target.friendAccountId());
    if (response.hasError() || !response.getSuccess()) {
      String message =
          response.hasError() && !response.getError().getMessage().isBlank()
              ? response.getError().getMessage()
              : "Friend removal unavailable";
      return new TextCommandInterpretationResult(
          CommandEnqueueResult.failure("FRIEND_REMOVE_UNAVAILABLE", message),
          List.of(PlayerOutput.error("FRIEND_REMOVE_UNAVAILABLE", message)));
    }
    publishCommandEvent(context);
    return new TextCommandInterpretationResult(
        CommandEnqueueResult.success(),
        List.of(PlayerOutput.notice(friendMutation("REMOVE", target))));
  }

  private TextCommandInterpretationResult handleDetail(
      SessionContext context, ResolvedFriendTarget target) {
    GetFriendResponse response =
        socialGroupsClient.getFriend(
            context.tenantId(), context.accountId(), target.friendAccountId());
    if (response.hasError()) {
      String code =
          "NOT_FOUND".equalsIgnoreCase(response.getError().getCode())
              ? "FRIEND_TARGET_NOT_FOUND"
              : response.getError().getCode().isBlank()
                  ? "FRIEND_DETAIL_UNAVAILABLE"
                  : response.getError().getCode();
      String message =
          response.getError().getMessage().isBlank()
              ? "Friend detail unavailable"
              : response.getError().getMessage();
      return new TextCommandInterpretationResult(
          CommandEnqueueResult.failure(code, message), List.of(PlayerOutput.error(code, message)));
    }
    publishCommandEvent(context);
    FriendPresenceViewOutput.Entry entry = toEntry(0, response.getFriend());
    return new TextCommandInterpretationResult(
        CommandEnqueueResult.success(),
        List.of(PlayerOutput.view(new FriendDetailViewOutput(entry))));
  }

  private TextCommandInterpretationResult handleDetailByOrdinal(
      SessionContext context, String targetToken) {
    int ordinal = parseOrdinal(targetToken);
    if (ordinal <= 0) {
      return invalidUsage("FRIENDS SHOW <friendAccountId|characterName|#entryNumber>");
    }
    GetFriendByOrdinalResponse response =
        socialGroupsClient.getFriendByOrdinal(context.tenantId(), context.accountId(), ordinal);
    if (response.hasError()) {
      String code =
          "NOT_FOUND".equalsIgnoreCase(response.getError().getCode())
              ? "FRIEND_TARGET_NOT_FOUND"
              : response.getError().getCode().isBlank()
                  ? "FRIEND_DETAIL_UNAVAILABLE"
                  : response.getError().getCode();
      String message =
          response.getError().getMessage().isBlank()
              ? "Friend detail unavailable"
              : response.getError().getMessage();
      return new TextCommandInterpretationResult(
          CommandEnqueueResult.failure(code, message), List.of(PlayerOutput.error(code, message)));
    }
    publishCommandEvent(context);
    return new TextCommandInterpretationResult(
        CommandEnqueueResult.success(),
        List.of(
            PlayerOutput.view(new FriendDetailViewOutput(toEntry(ordinal, response.getFriend())))));
  }

  private TextCommandInterpretationResult handleSummary(SessionContext context) {
    GetFriendRosterSummaryResponse response =
        socialGroupsClient.getFriendRosterSummary(context.tenantId(), context.accountId());
    if (response.hasError()) {
      String message =
          response.getError().getMessage().isBlank()
              ? "Friend roster summary unavailable"
              : response.getError().getMessage();
      return new TextCommandInterpretationResult(
          CommandEnqueueResult.failure("FRIEND_SUMMARY_UNAVAILABLE", message),
          List.of(PlayerOutput.error("FRIEND_SUMMARY_UNAVAILABLE", message)));
    }
    publishCommandEvent(context);
    return new TextCommandInterpretationResult(
        CommandEnqueueResult.success(),
        List.of(
            PlayerOutput.view(
                new FriendRosterSummaryViewOutput(
                    response.getSummary().getTotalCount(),
                    response.getSummary().getOnlineCount(),
                    response.getSummary().getOfflineCount(),
                    response.getSummary().getRecentCount(),
                    response.getSummary().getPublicCount(),
                    response.getSummary().getFriendsOnlyCount(),
                    response.getSummary().getPrivateCount(),
                    response.getSummary().getHiddenStaffCount(),
                    response.getSummary().getUnspecifiedVisibilityCount(),
                    response.getSummary().getSharedCount(),
                    response.getSummary().getIsolatedCount(),
                    response.getSummary().getUnspecifiedScopeCount()))));
  }

  private TextCommandInterpretationResult handleVisibilityView(SessionContext context) {
    GetFriendPresencePolicyResponse response =
        socialGroupsClient.getFriendPresencePolicy(context.tenantId(), context.accountId());
    if (response.hasError()) {
      String code =
          response.getError().getCode().isBlank()
              ? "FRIEND_VISIBILITY_UNAVAILABLE"
              : response.getError().getCode();
      String message =
          response.getError().getMessage().isBlank()
              ? "Friend presence visibility unavailable"
              : response.getError().getMessage();
      return new TextCommandInterpretationResult(
          CommandEnqueueResult.failure(code, message), List.of(PlayerOutput.error(code, message)));
    }
    publishCommandEvent(context);
    return new TextCommandInterpretationResult(
        CommandEnqueueResult.success(),
        List.of(PlayerOutput.view(friendPresencePolicyView(response.getCurrentPolicy()))));
  }

  private TextCommandInterpretationResult handleVisibilityUpdate(
      SessionContext context, String targetToken) {
    net.firedevops.firemud.socialgroups.v1.FriendPresenceVisibilityPolicy visibilityPolicy =
        parseVisibilityPolicy(targetToken);
    if (visibilityPolicy == null) {
      return invalidUsage("FRIENDS VISIBILITY <PUBLIC|FRIENDS_ONLY|PRIVATE>");
    }
    if (visibilityPolicy
        == net.firedevops.firemud.socialgroups.v1.FriendPresenceVisibilityPolicy
            .FRIEND_PRESENCE_VISIBILITY_POLICY_HIDDEN_STAFF) {
      return friendTargetError(
          "INVALID_ARGUMENT", "HIDDEN_STAFF is reserved and cannot be set from gameplay");
    }
    UpdateFriendPresencePolicyResponse response =
        socialGroupsClient.updateFriendPresencePolicy(
            context.tenantId(), context.accountId(), visibilityPolicy);
    if (response.hasError() || !response.getSuccess()) {
      String code =
          response.hasError() && !response.getError().getCode().isBlank()
              ? response.getError().getCode()
              : "FRIEND_VISIBILITY_UPDATE_UNAVAILABLE";
      String message =
          response.hasError() && !response.getError().getMessage().isBlank()
              ? response.getError().getMessage()
              : "Friend presence visibility update unavailable";
      return new TextCommandInterpretationResult(
          CommandEnqueueResult.failure(code, message), List.of(PlayerOutput.error(code, message)));
    }
    publishCommandEvent(context);
    return new TextCommandInterpretationResult(
        CommandEnqueueResult.success(),
        List.of(
            PlayerOutput.notice(
                "Friend presence visibility set to "
                    + visibilityPolicy(response.getCurrentPolicy())
                    + "."),
            PlayerOutput.view(friendPresencePolicyView(response.getCurrentPolicy()))));
  }

  private TextCommandInterpretationResult handleRemoveByOrdinal(
      SessionContext context, String targetToken) {
    int ordinal = parseOrdinal(targetToken);
    if (ordinal <= 0) {
      return invalidUsage("FRIENDS REMOVE <friendAccountId|characterName|#entryNumber>");
    }
    RemoveFriendByOrdinalResponse response =
        socialGroupsClient.removeFriendByOrdinal(context.tenantId(), context.accountId(), ordinal);
    if (response.hasError() || !response.getSuccess()) {
      String code =
          response.hasError() && "NOT_FOUND".equalsIgnoreCase(response.getError().getCode())
              ? "FRIEND_TARGET_NOT_FOUND"
              : "FRIEND_REMOVE_UNAVAILABLE";
      String message =
          response.hasError() && !response.getError().getMessage().isBlank()
              ? response.getError().getMessage()
              : "Friend removal unavailable";
      return new TextCommandInterpretationResult(
          CommandEnqueueResult.failure(code, message), List.of(PlayerOutput.error(code, message)));
    }
    FriendPresenceViewOutput.Entry removed = toEntry(ordinal, response.getRemovedFriend());
    publishCommandEvent(context);
    return new TextCommandInterpretationResult(
        CommandEnqueueResult.success(),
        List.of(PlayerOutput.notice(friendMutation("REMOVE", removed))));
  }

  private TextCommandInterpretationResult invalidUsage(String syntax) {
    return new TextCommandInterpretationResult(
        CommandEnqueueResult.failure("INVALID_ARGUMENT", syntax),
        List.of(PlayerOutput.error("INVALID_ARGUMENT", syntax)));
  }

  private TextCommandInterpretationResult friendTargetError(String code, String message) {
    return new TextCommandInterpretationResult(
        CommandEnqueueResult.failure(code, message), List.of(PlayerOutput.error(code, message)));
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

  private FriendPresenceViewOutput toView(ListFriendsResponse response, FriendListFilter filter) {
    List<FriendPresenceViewOutput.Entry> allEntries =
        java.util.stream.IntStream.range(0, response.getFriendsCount())
            .mapToObj(
                index -> {
                  FriendRosterEntry entry = response.getFriends(index);
                  int ordinal = entry.getOrdinal() > 0 ? entry.getOrdinal() : index + 1;
                  return toEntry(ordinal, entry);
                })
            .toList();
    boolean canonicalFiltered =
        filter == FriendListFilter.ALL
            || response.getFilter() == mapRosterFilter(filter)
            || response.getMatchCount() > 0
            || response.getTotalCount() > 0;
    List<FriendPresenceViewOutput.Entry> mapped =
        canonicalFiltered ? allEntries : allEntries.stream().filter(filter::matches).toList();
    int totalCount = response.getTotalCount() > 0 ? response.getTotalCount() : allEntries.size();
    int matchCount = response.getMatchCount() > 0 ? response.getMatchCount() : mapped.size();
    return new FriendPresenceViewOutput(filter.name(), totalCount, matchCount, mapped);
  }

  private FriendPresenceViewOutput.Entry toEntry(int ordinal, FriendRosterEntry entry) {
    FriendPresenceEntry presence = entry.getPresence();
    long friendAccountId = parseLong(entry.getFriendAccountId());
    String characterName =
        presence.getCharacterName().isBlank() ? null : presence.getCharacterName().trim();
    return new FriendPresenceViewOutput.Entry(
        ordinal,
        parseOptionalLong(entry.getFriendLinkId()),
        friendAccountId,
        blankToNull(entry.getStatus()),
        entry.getCreatedAtMs() > 0 ? entry.getCreatedAtMs() : null,
        characterName != null ? characterName : "Friend #" + friendAccountId,
        presence.getOnline(),
        blankToNull(presence.getWorldSlug()),
        blankToNull(presence.getWorldDisplayName()),
        blankToNull(presence.getRealmSlug()),
        blankToNull(presence.getRealmDisplayName()),
        characterName,
        playableStateScope(presence.getPlayableStateScope()),
        presence.getPointerVersion() > 0 ? presence.getPointerVersion() : null,
        activityState(presence.getActivityState()),
        presence.getLastSeenAtMs() > 0 ? presence.getLastSeenAtMs() : null,
        recentDisposition(presence.getRecentDisposition()),
        visibilityPolicy(presence.getVisibilityPolicy()));
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

  private Long parseOptionalLong(String value) {
    if (value == null || value.isBlank()) {
      return null;
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

  private String playableStateScope(PlayableStateScope scope) {
    return switch (scope) {
      case PLAYABLE_STATE_SCOPE_SHARED -> "SHARED";
      case PLAYABLE_STATE_SCOPE_ISOLATED -> "ISOLATED";
      default -> null;
    };
  }

  private String visibilityPolicy(
      net.firedevops.firemud.socialgroups.v1.FriendPresenceVisibilityPolicy visibilityPolicy) {
    return switch (visibilityPolicy) {
      case FRIEND_PRESENCE_VISIBILITY_POLICY_PUBLIC -> "PUBLIC";
      case FRIEND_PRESENCE_VISIBILITY_POLICY_FRIENDS_ONLY -> "FRIENDS_ONLY";
      case FRIEND_PRESENCE_VISIBILITY_POLICY_PRIVATE -> "PRIVATE";
      case FRIEND_PRESENCE_VISIBILITY_POLICY_HIDDEN_STAFF -> "HIDDEN_STAFF";
      default -> null;
    };
  }

  private FriendAction parseAction(TextCommand command) {
    List<String> args = command == null ? List.of() : command.args();
    if (args.isEmpty()) {
      return new FriendAction(FriendActionKind.LIST, "LIST", FriendListFilter.ALL, null, null);
    }
    String verb = args.getFirst().trim().toUpperCase(Locale.ROOT);
    String targetToken =
        args.size() > 1 ? String.join(" ", args.subList(1, args.size())).trim() : null;
    return switch (verb) {
      case "ADD" ->
          new FriendAction(FriendActionKind.ADD, "ADD", FriendListFilter.ALL, targetToken, null);
      case "REMOVE", "DEL", "DELETE", "RM" ->
          new FriendAction(
              FriendActionKind.REMOVE, "REMOVE", FriendListFilter.ALL, targetToken, null);
      case "SHOW", "INFO", "DETAIL" ->
          new FriendAction(
              FriendActionKind.DETAIL, "SHOW", FriendListFilter.ALL, targetToken, null);
      case "SUMMARY", "COUNTS" ->
          new FriendAction(FriendActionKind.SUMMARY, "SUMMARY", FriendListFilter.ALL, null, null);
      case "VISIBILITY", "POLICY", "PRIVACY" ->
          new FriendAction(
              FriendActionKind.VISIBILITY, "VISIBILITY", FriendListFilter.ALL, targetToken, null);
      case "ALL", "LIST" ->
          new FriendAction(FriendActionKind.LIST, "LIST", FriendListFilter.ALL, null, null);
      case "ONLINE" ->
          new FriendAction(FriendActionKind.LIST, "LIST", FriendListFilter.ONLINE, null, null);
      case "OFFLINE" ->
          new FriendAction(FriendActionKind.LIST, "LIST", FriendListFilter.OFFLINE, null, null);
      case "RECENT" ->
          new FriendAction(FriendActionKind.LIST, "LIST", FriendListFilter.RECENT, null, null);
      case "PUBLIC" ->
          new FriendAction(FriendActionKind.LIST, "LIST", FriendListFilter.PUBLIC, null, null);
      case "FRIENDS_ONLY", "FRIENDS-ONLY", "POLICY_FRIENDS_ONLY" ->
          new FriendAction(
              FriendActionKind.LIST, "LIST", FriendListFilter.FRIENDS_ONLY, null, null);
      case "PRIVATE" ->
          new FriendAction(FriendActionKind.LIST, "LIST", FriendListFilter.PRIVATE, null, null);
      case "HIDDEN_STAFF", "HIDDEN-STAFF", "HIDDEN" ->
          new FriendAction(
              FriendActionKind.LIST, "LIST", FriendListFilter.HIDDEN_STAFF, null, null);
      case "SHARED" ->
          new FriendAction(FriendActionKind.LIST, "LIST", FriendListFilter.SHARED, null, null);
      case "ISOLATED" ->
          new FriendAction(FriendActionKind.LIST, "LIST", FriendListFilter.ISOLATED, null, null);
      case "UNSPECIFIED", "UNKNOWN", "UNSPECIFIED_VISIBILITY" ->
          new FriendAction(
              FriendActionKind.LIST, "LIST", FriendListFilter.UNSPECIFIED_VISIBILITY, null, null);
      case "UNSCOPED", "UNKNOWN_SCOPE", "UNSPECIFIED_SCOPE" ->
          new FriendAction(
              FriendActionKind.LIST, "LIST", FriendListFilter.UNSPECIFIED_SCOPE, null, null);
      default ->
          new FriendAction(
              FriendActionKind.INVALID,
              "INVALID",
              FriendListFilter.ALL,
              null,
              "FRIENDS [ADD|REMOVE|SHOW|SUMMARY|VISIBILITY|ONLINE|OFFLINE|RECENT|PUBLIC|FRIENDS_ONLY|PRIVATE|HIDDEN_STAFF|UNSPECIFIED_VISIBILITY|SHARED|ISOLATED|UNSPECIFIED_SCOPE]");
    };
  }

  private ResolvedFriendTarget resolveTarget(SessionContext context, FriendAction action) {
    Long accountId = tryParseAccountId(action.targetToken());
    if (accountId != null) {
      return new ResolvedFriendTarget(accountId, null, null);
    }
    if (!StringUtils.hasText(action.targetToken())) {
      return new ResolvedFriendTarget(
          0L,
          null,
          invalidUsage("FRIENDS " + action.keyword() + " <friendAccountId|characterName>"));
    }
    var character =
        entityManagementClient.findCharacterByName(
            context, resolvePlayableStateScope(context), action.targetToken().trim());
    if (character.isEmpty() || !StringUtils.hasText(character.get().getAccountId())) {
      return new ResolvedFriendTarget(
          0L,
          null,
          friendTargetError("FRIEND_TARGET_NOT_FOUND", notFoundMessage(action.targetToken())));
    }
    return new ResolvedFriendTarget(
        Long.parseLong(character.get().getAccountId()), character.get().getName(), null);
  }

  private PlayableStateScope resolvePlayableStateScope(SessionContext context) {
    if (!StringUtils.hasText(context.playableStateScope())) {
      throw new IllegalStateException(
          "Missing admitted playableStateScope on session context for friends command");
    }
    return switch (context.playableStateScope()) {
      case "SHARED" -> PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED;
      case "ISOLATED" -> PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED;
      default ->
          throw new IllegalStateException(
              "Unsupported playableStateScope=" + context.playableStateScope());
    };
  }

  private FriendMutationResultOutput friendMutation(String action, ResolvedFriendTarget target) {
    String characterName = blankToNull(target.characterName());
    return new FriendMutationResultOutput(
        action,
        target.friendAccountId(),
        characterName != null ? characterName.trim() : "Friend #" + target.friendAccountId(),
        characterName,
        null);
  }

  private FriendMutationResultOutput friendMutation(
      String action, FriendPresenceViewOutput.Entry entry) {
    String characterName = blankToNull(entry.characterName());
    return new FriendMutationResultOutput(
        action,
        entry.friendAccountId(),
        characterName != null ? characterName.trim() : "Friend #" + entry.friendAccountId(),
        characterName,
        entry.ordinal());
  }

  private FriendRosterFilter mapRosterFilter(FriendListFilter filter) {
    return switch (filter) {
      case ONLINE -> FriendRosterFilter.FRIEND_ROSTER_FILTER_ONLINE;
      case OFFLINE -> FriendRosterFilter.FRIEND_ROSTER_FILTER_OFFLINE;
      case RECENT -> FriendRosterFilter.FRIEND_ROSTER_FILTER_RECENT;
      case PUBLIC -> FriendRosterFilter.FRIEND_ROSTER_FILTER_PUBLIC;
      case FRIENDS_ONLY -> FriendRosterFilter.FRIEND_ROSTER_FILTER_FRIENDS_ONLY;
      case PRIVATE -> FriendRosterFilter.FRIEND_ROSTER_FILTER_PRIVATE;
      case HIDDEN_STAFF -> FriendRosterFilter.FRIEND_ROSTER_FILTER_HIDDEN_STAFF;
      case UNSPECIFIED_VISIBILITY -> FriendRosterFilter.FRIEND_ROSTER_FILTER_UNSPECIFIED_VISIBILITY;
      case SHARED -> FriendRosterFilter.FRIEND_ROSTER_FILTER_SHARED;
      case ISOLATED -> FriendRosterFilter.FRIEND_ROSTER_FILTER_ISOLATED;
      case UNSPECIFIED_SCOPE -> FriendRosterFilter.FRIEND_ROSTER_FILTER_UNSPECIFIED_SCOPE;
      case ALL -> FriendRosterFilter.FRIEND_ROSTER_FILTER_ALL;
    };
  }

  private Long tryParseAccountId(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Long.parseLong(value.trim());
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private boolean isOrdinalToken(String value) {
    return value != null && value.startsWith("#");
  }

  private int parseOrdinal(String value) {
    if (!isOrdinalToken(value)) {
      return -1;
    }
    try {
      return Integer.parseInt(value.substring(1).trim());
    } catch (NumberFormatException ex) {
      return -1;
    }
  }

  private String notFoundMessage(String targetToken) {
    return "Character not found: " + targetToken.trim();
  }

  private FriendPresencePolicyViewOutput friendPresencePolicyView(
      net.firedevops.firemud.socialgroups.v1.FriendPresenceVisibilityPolicy current) {
    return new FriendPresencePolicyViewOutput(
        visibilityPolicy(current),
        List.of(
            new FriendPresencePolicyViewOutput.Option(
                "PUBLIC",
                "Show the normal bounded friend-presence payload to approved social consumers.",
                current
                    == net.firedevops.firemud.socialgroups.v1.FriendPresenceVisibilityPolicy
                        .FRIEND_PRESENCE_VISIBILITY_POLICY_PUBLIC,
                true),
            new FriendPresencePolicyViewOutput.Option(
                "FRIENDS_ONLY",
                "Expose richer live identity only to approved friends.",
                current
                    == net.firedevops.firemud.socialgroups.v1.FriendPresenceVisibilityPolicy
                        .FRIEND_PRESENCE_VISIBILITY_POLICY_FRIENDS_ONLY,
                true),
            new FriendPresencePolicyViewOutput.Option(
                "PRIVATE",
                "Suppress current live character identity and expose only coarse online or recent activity.",
                current
                    == net.firedevops.firemud.socialgroups.v1.FriendPresenceVisibilityPolicy
                        .FRIEND_PRESENCE_VISIBILITY_POLICY_PRIVATE,
                true),
            new FriendPresencePolicyViewOutput.Option(
                "HIDDEN_STAFF",
                "Reserved for staff-hidden role clamps and not set directly from gameplay.",
                current
                    == net.firedevops.firemud.socialgroups.v1.FriendPresenceVisibilityPolicy
                        .FRIEND_PRESENCE_VISIBILITY_POLICY_HIDDEN_STAFF,
                false)));
  }

  private net.firedevops.firemud.socialgroups.v1.FriendPresenceVisibilityPolicy
      parseVisibilityPolicy(String targetToken) {
    if (!StringUtils.hasText(targetToken)) {
      return null;
    }
    String normalized = targetToken.trim().toUpperCase(Locale.ROOT).replace('-', '_');
    return switch (normalized) {
      case "PUBLIC" ->
          net.firedevops.firemud.socialgroups.v1.FriendPresenceVisibilityPolicy
              .FRIEND_PRESENCE_VISIBILITY_POLICY_PUBLIC;
      case "FRIENDS_ONLY" ->
          net.firedevops.firemud.socialgroups.v1.FriendPresenceVisibilityPolicy
              .FRIEND_PRESENCE_VISIBILITY_POLICY_FRIENDS_ONLY;
      case "PRIVATE" ->
          net.firedevops.firemud.socialgroups.v1.FriendPresenceVisibilityPolicy
              .FRIEND_PRESENCE_VISIBILITY_POLICY_PRIVATE;
      case "HIDDEN_STAFF" ->
          net.firedevops.firemud.socialgroups.v1.FriendPresenceVisibilityPolicy
              .FRIEND_PRESENCE_VISIBILITY_POLICY_HIDDEN_STAFF;
      default -> null;
    };
  }

  private enum FriendActionKind {
    INVALID,
    LIST,
    ADD,
    REMOVE,
    DETAIL,
    SUMMARY,
    VISIBILITY
  }

  private enum FriendListFilter {
    ALL {
      @Override
      boolean matches(FriendPresenceViewOutput.Entry entry) {
        return true;
      }
    },
    ONLINE {
      @Override
      boolean matches(FriendPresenceViewOutput.Entry entry) {
        return entry.online();
      }
    },
    OFFLINE {
      @Override
      boolean matches(FriendPresenceViewOutput.Entry entry) {
        return !entry.online();
      }
    },
    RECENT {
      @Override
      boolean matches(FriendPresenceViewOutput.Entry entry) {
        return !entry.online() && entry.lastSeenAtEpochMs() != null;
      }
    },
    PUBLIC {
      @Override
      boolean matches(FriendPresenceViewOutput.Entry entry) {
        return "PUBLIC".equals(entry.visibilityPolicy());
      }
    },
    FRIENDS_ONLY {
      @Override
      boolean matches(FriendPresenceViewOutput.Entry entry) {
        return "FRIENDS_ONLY".equals(entry.visibilityPolicy());
      }
    },
    PRIVATE {
      @Override
      boolean matches(FriendPresenceViewOutput.Entry entry) {
        return "PRIVATE".equals(entry.visibilityPolicy());
      }
    },
    HIDDEN_STAFF {
      @Override
      boolean matches(FriendPresenceViewOutput.Entry entry) {
        return "HIDDEN_STAFF".equals(entry.visibilityPolicy());
      }
    },
    SHARED {
      @Override
      boolean matches(FriendPresenceViewOutput.Entry entry) {
        return "SHARED".equals(entry.playableStateScope());
      }
    },
    ISOLATED {
      @Override
      boolean matches(FriendPresenceViewOutput.Entry entry) {
        return "ISOLATED".equals(entry.playableStateScope());
      }
    },
    UNSPECIFIED_VISIBILITY {
      @Override
      boolean matches(FriendPresenceViewOutput.Entry entry) {
        return !StringUtils.hasText(entry.visibilityPolicy());
      }
    },
    UNSPECIFIED_SCOPE {
      @Override
      boolean matches(FriendPresenceViewOutput.Entry entry) {
        return !StringUtils.hasText(entry.playableStateScope());
      }
    };

    abstract boolean matches(FriendPresenceViewOutput.Entry entry);
  }

  private record FriendAction(
      FriendActionKind kind,
      String keyword,
      FriendListFilter listFilter,
      String targetToken,
      String invalidUsage) {}

  private record ResolvedFriendTarget(
      long friendAccountId, String characterName, TextCommandInterpretationResult errorResult) {}
}
