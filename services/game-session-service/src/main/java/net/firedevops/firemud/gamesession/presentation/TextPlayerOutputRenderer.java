package net.firedevops.firemud.gamesession.presentation;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import net.firedevops.firemud.gamesession.command.text.TextCommand;
import net.firedevops.firemud.gamesession.command.text.TextCommandType;
import net.firedevops.firemud.gamesession.config.PresentationProperties;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Renders normalized player outputs for classic text-based clients. */
@Component
public class TextPlayerOutputRenderer {
  private final PresentationProperties presentationProperties;
  private final PresentationMessageCatalog presentationMessageCatalog;

  public TextPlayerOutputRenderer(PresentationProperties presentationProperties) {
    this(presentationProperties, new PresentationMessageCatalog());
  }

  @Autowired
  public TextPlayerOutputRenderer(
      PresentationProperties presentationProperties,
      PresentationMessageCatalog presentationMessageCatalog) {
    this.presentationProperties = presentationProperties;
    this.presentationMessageCatalog = presentationMessageCatalog;
  }

  public String render(PlayerOutput output) {
    return render(output, presentationProperties.defaultLocaleTag(), presentationProperties);
  }

  public String render(PlayerOutput output, String localeTag) {
    return render(output, localeTag, presentationProperties);
  }

  public String render(
      PlayerOutput output,
      String localeTag,
      PresentationProperties effectivePresentationProperties) {
    if (effectivePresentationProperties.briefEnabledByDefault()
        && output.briefRenderPolicy() == BriefRenderPolicy.SUPPRESS_IN_BRIEF) {
      return "";
    }
    return switch (output.kind()) {
      case MESSAGE -> renderMessage((TextMessageOutput) output.payload(), localeTag);
      case VIEW -> {
        if (output.payload() instanceof LookViewOutput lookView) {
          yield renderLookView(lookView, localeTag, effectivePresentationProperties);
        }
        if (output.payload() instanceof InventoryViewOutput inventoryView) {
          yield renderInventoryView(inventoryView, localeTag, effectivePresentationProperties);
        }
        if (output.payload() instanceof WorldsViewOutput worldsView) {
          yield renderWorldsView(worldsView);
        }
        if (output.payload() instanceof RealmBrowseViewOutput realmsView) {
          yield renderRealmsView(realmsView);
        }
        if (output.payload() instanceof CharacterBrowseViewOutput charactersView) {
          yield renderCharactersView(charactersView);
        }
        if (output.payload() instanceof WhoViewOutput whoView) {
          yield renderWhoView(whoView);
        }
        if (output.payload() instanceof FriendPresenceViewOutput friendsView) {
          yield renderFriendsView(friendsView);
        }
        if (output.payload() instanceof FriendDetailViewOutput friendDetailView) {
          yield renderFriendDetailView(friendDetailView);
        }
        if (output.payload() instanceof FriendRosterSummaryViewOutput friendRosterSummaryView) {
          yield renderFriendRosterSummaryView(friendRosterSummaryView);
        }
        if (output.payload() instanceof FriendPresencePolicyViewOutput friendPresencePolicyView) {
          yield renderFriendPresencePolicyView(friendPresencePolicyView);
        }
        throw new IllegalArgumentException(
            "Unsupported view payload: " + output.payload().getClass().getName());
      }
      case PROMPT -> renderPrompt((PromptOutput) output.payload(), effectivePresentationProperties);
      case ERROR -> renderError((ErrorOutput) output.payload(), localeTag);
      case NOTICE -> renderNotice(output.payload(), localeTag, effectivePresentationProperties);
    };
  }

  public String renderAll(
      TextCommand command, CommandEnqueueResult result, java.util.List<PlayerOutput> outputs) {
    return renderAll(
        command,
        result,
        outputs,
        presentationProperties.defaultLocaleTag(),
        presentationProperties);
  }

  public String renderAll(
      TextCommand command,
      CommandEnqueueResult result,
      java.util.List<PlayerOutput> outputs,
      String localeTag) {
    return renderAll(command, result, outputs, localeTag, presentationProperties);
  }

  public String renderAll(
      TextCommand command,
      CommandEnqueueResult result,
      java.util.List<PlayerOutput> outputs,
      String localeTag,
      PresentationProperties effectivePresentationProperties) {
    return renderAllForCommandType(
        command.type(), result, outputs, localeTag, effectivePresentationProperties);
  }

  public String renderAllForCommandType(
      TextCommandType commandType,
      CommandEnqueueResult result,
      java.util.List<PlayerOutput> outputs,
      String localeTag,
      PresentationProperties effectivePresentationProperties) {
    TextCommand command = syntheticCommand(commandType);
    if (!result.accepted()) {
      String renderedError =
          outputs.stream()
              .filter(output -> output.kind() == PlayerOutputKind.ERROR)
              .reduce((first, second) -> second)
              .map(output -> render(output, localeTag, effectivePresentationProperties))
              .orElse(null);
      if (StringUtils.hasText(renderedError)) {
        return renderedError;
      }
      String message = result.errorMessage() == null ? "" : result.errorMessage();
      return "ERROR " + result.errorCode() + " " + message;
    }
    String prompt = renderTrailingPrompt(outputs, effectivePresentationProperties, localeTag);
    java.util.List<PlayerOutput> nonPromptOutputs =
        outputs.stream().filter(output -> output.kind() != PlayerOutputKind.PROMPT).toList();
    if (nonPromptOutputs.isEmpty()) {
      if (StringUtils.hasText(prompt)) {
        return prompt;
      }
      return "OK " + command.type().name();
    }
    if (nonPromptOutputs.size() == 1 && nonPromptOutputs.get(0).kind() == PlayerOutputKind.NOTICE) {
      String rendered =
          renderNoticeWithCommand(
              command, nonPromptOutputs.get(0), localeTag, effectivePresentationProperties);
      return appendPrompt(
          rendered, prompt, !rendered.contains("\n") && StringUtils.hasText(prompt));
    }
    String body =
        nonPromptOutputs.stream()
            .map(output -> render(output, localeTag, effectivePresentationProperties))
            .filter(StringUtils::hasText)
            .collect(Collectors.joining("\n"));
    if (body.isBlank()) {
      if (StringUtils.hasText(prompt)) {
        return prompt;
      }
      return "OK " + command.type().name();
    }
    String commandLabel = responseCommandLabel(command, nonPromptOutputs);
    boolean plainMessageOnly =
        nonPromptOutputs.stream().allMatch(output -> output.kind() == PlayerOutputKind.MESSAGE);
    boolean proseCommand =
        command.type().name().equals("SAY")
            || command.type().name().equals("WHISPER")
            || command.type().name().equals("TELL");
    if (plainMessageOnly && proseCommand) {
      return appendPrompt(body, prompt, true);
    }
    return appendPrompt("OK " + commandLabel + "\n" + body + "\n\n", prompt, false);
  }

  public String renderSuccessfulForCommandType(
      TextCommandType commandType,
      java.util.List<PlayerOutput> outputs,
      String localeTag,
      PresentationProperties effectivePresentationProperties) {
    return renderAllForCommandType(
        commandType,
        CommandEnqueueResult.success(),
        outputs,
        localeTag,
        effectivePresentationProperties);
  }

  private String renderLookView(
      LookViewOutput result,
      String localeTag,
      PresentationProperties effectivePresentationProperties) {
    boolean brief = shouldUseBriefRendering(result, effectivePresentationProperties);
    StringBuilder out = new StringBuilder();
    out.append(
            colorizeLabel(
                localizedLabel("label.room", "Room: ", localeTag), effectivePresentationProperties))
        .append(colorizeRoomName(result.roomName(), effectivePresentationProperties))
        .append(" (ID: ")
        .append(result.roomId())
        .append(")\n");
    out.append(
            colorizeLabel(
                localizedLabel("label.short", "Short: ", localeTag),
                effectivePresentationProperties))
        .append(result.shortDescription())
        .append("\n");
    if (!brief && result.includeLongDescription()) {
      out.append(
              colorizeLabel(
                  localizedLabel("label.long", "Long: ", localeTag),
                  effectivePresentationProperties))
          .append(result.longDescription())
          .append("\n");
    }
    out.append(
        colorizeLabel(
            localizedLabel("label.exits", "Exits: ", localeTag), effectivePresentationProperties));
    out.append(
        result.exits().stream()
            .map(exit -> exit.label() + " (" + exit.description() + ")")
            .collect(Collectors.joining(", ")));
    out.append("\n")
        .append(
            colorizeLabel(
                localizedLabel("label.entities", "Entities:", localeTag),
                effectivePresentationProperties))
        .append("\n");
    for (LookViewEntity entity : result.entities()) {
      out.append("- ")
          .append(entity.entityType())
          .append(" \"")
          .append(entity.displayName())
          .append("\"")
          .append(entity.role().isEmpty() ? "" : " (" + entity.role() + ")");
      String stateFlags = formatStateFlags(entity.stateFlags());
      if (!stateFlags.isEmpty()) {
        out.append(stateFlags);
      }
      out.append("\n");
    }
    return out.toString().trim();
  }

  private String formatStateFlags(List<String> stateFlags) {
    if (stateFlags.isEmpty()) {
      return "";
    }

    List<String> visibleFlags = new ArrayList<>();
    List<String> affordances = new ArrayList<>();
    for (String flag : stateFlags) {
      if (!StringUtils.hasText(flag)) {
        continue;
      }
      if (flag.equals("container")) {
        affordances.add("container");
        continue;
      }
      if (flag.startsWith("wearable:")) {
        String slot = flag.substring("wearable:".length()).trim();
        affordances.add(StringUtils.hasText(slot) ? "wearable " + slot : "wearable");
        continue;
      }
      visibleFlags.add(flag);
    }

    if (visibleFlags.isEmpty() && affordances.isEmpty()) {
      return "";
    }
    if (affordances.isEmpty()) {
      return " [" + String.join(",", visibleFlags) + "]";
    }
    if (visibleFlags.isEmpty()) {
      return " [affordances: " + String.join(", ", affordances) + "]";
    }
    return " ["
        + String.join(",", visibleFlags)
        + "; affordances: "
        + String.join(", ", affordances)
        + "]";
  }

  private String renderPrompt(
      PromptOutput output, PresentationProperties effectivePresentationProperties) {
    if (!effectivePresentationProperties.prompt().enabled()
        || !StringUtils.hasText(output.text())) {
      return "";
    }
    return colorizePrompt(output.text(), effectivePresentationProperties);
  }

  private String renderWorldsView(WorldsViewOutput output) {
    return output.worlds().stream()
        .map(world -> world.ordinal() + ") " + world.displayName() + " (" + world.slug() + ")")
        .collect(Collectors.joining("\n"));
  }

  private String renderRealmsView(RealmBrowseViewOutput output) {
    return output.realms().stream()
        .map(
            realm ->
                realm.ordinal()
                    + ") "
                    + realm.displayName()
                    + " ("
                    + realm.realmSlug()
                    + ") ["
                    + realm.stateScope().toLowerCase(java.util.Locale.ROOT)
                    + ", "
                    + realm.characterCreationPolicy().toLowerCase(java.util.Locale.ROOT)
                    + "]")
        .collect(Collectors.joining("\n"));
  }

  private String renderCharactersView(CharacterBrowseViewOutput output) {
    if (output.characters().isEmpty()) {
      return "No characters available for "
          + output.worldSlug()
          + " ("
          + output.realmSlug()
          + "). ["
          + output.stateScope().toLowerCase(java.util.Locale.ROOT)
          + ", "
          + output.characterCreationPolicy().toLowerCase(java.util.Locale.ROOT)
          + "]";
    }
    String roster =
        output.characters().stream()
            .map(
                character ->
                    character.ordinal()
                        + ") "
                        + character.characterName()
                        + " [lvl "
                        + character.level()
                        + "]")
            .collect(Collectors.joining("\n"));
    return roster
        + "\n\n"
        + "Realm state: "
        + output.stateScope().toLowerCase(java.util.Locale.ROOT)
        + ", creation: "
        + output.characterCreationPolicy().toLowerCase(java.util.Locale.ROOT);
  }

  private String renderWhoView(WhoViewOutput output) {
    return "Gods ["
        + output.gods().size()
        + "]: "
        + renderWhoEntries(output.gods())
        + "\nPlayers ["
        + output.players().size()
        + "]: "
        + renderWhoEntries(output.players());
  }

  private String renderWhoEntries(List<WhoViewOutput.Entry> entries) {
    return entries.stream()
        .map(
            entry ->
                switch (entry.activityState()) {
                  case "AUTO_AFK" -> entry.characterName() + " (idle)";
                  case "EXPLICIT_AFK" -> entry.characterName() + " (AFK)";
                  default -> entry.characterName();
                })
        .collect(Collectors.joining(", "));
  }

  private String renderFriendsView(FriendPresenceViewOutput output) {
    if (output.friends().isEmpty() && "ALL".equalsIgnoreCase(output.filter())) {
      return "Friends [0]: no linked friends. Use FRIENDS ADD <friendAccountId|characterName>.";
    }
    if (output.friends().isEmpty()) {
      return "Friends "
          + output.filter().toUpperCase(java.util.Locale.ROOT)
          + " [0/"
          + output.totalCount()
          + "]: no matching friends.";
    }
    String body =
        output.friends().stream()
            .map(
                entry ->
                    entry.ordinal()
                        + ") "
                        + entry.displayName()
                        + " [acct #"
                        + entry.friendAccountId()
                        + "]"
                        + renderFriendStatusSuffix(entry)
                        + " - "
                        + renderFriendStatus(entry))
            .collect(Collectors.joining("\n"));
    if ("ALL".equalsIgnoreCase(output.filter())) {
      return body;
    }
    return "Friends "
        + output.filter().toUpperCase(java.util.Locale.ROOT)
        + " ["
        + output.matchCount()
        + "/"
        + output.totalCount()
        + "]:\n"
        + body;
  }

  private String renderFriendDetailView(FriendDetailViewOutput output) {
    FriendPresenceViewOutput.Entry entry = output.friend();
    StringBuilder body =
        new StringBuilder()
            .append("Friend ")
            .append(entry.displayName())
            .append(" [acct #")
            .append(entry.friendAccountId())
            .append("]");
    if (entry.friendLinkId() != null) {
      body.append("\nLink: #").append(entry.friendLinkId());
    }
    if (StringUtils.hasText(entry.status())) {
      body.append("\nStatus: ").append(entry.status().trim().toLowerCase(java.util.Locale.ROOT));
    }
    if (entry.linkedAtEpochMs() != null) {
      body.append("\nLinked: ").append(java.time.Instant.ofEpochMilli(entry.linkedAtEpochMs()));
    }
    body.append("\nPresence: ").append(renderFriendStatus(entry));
    if (StringUtils.hasText(entry.visibilityPolicy())) {
      body.append("\nVisibility: ").append(entry.visibilityPolicy());
    }
    String location = renderFriendLocation(entry);
    if (StringUtils.hasText(location)) {
      body.append("\nLocation: ").append(location);
    }
    if (StringUtils.hasText(entry.characterName())) {
      body.append("\nCharacter: ").append(entry.characterName());
    }
    String activity = renderFriendActivity(entry.activityState());
    if (StringUtils.hasText(activity)) {
      body.append("\nActivity: ").append(activity);
    }
    if (StringUtils.hasText(entry.playableStateScope())) {
      body.append("\nState scope: ")
          .append(entry.playableStateScope().toLowerCase(java.util.Locale.ROOT));
    }
    if (entry.pointerVersion() != null) {
      body.append("\nPointer version: ").append(entry.pointerVersion());
    }
    if (entry.ordinal() > 0) {
      body.append("\nRoster entry: #").append(entry.ordinal());
    }
    return body.toString();
  }

  private String renderFriendRosterSummaryView(FriendRosterSummaryViewOutput output) {
    return "Friend roster summary:"
        + "\nLinked: "
        + output.totalCount()
        + "\nOnline: "
        + output.onlineCount()
        + "\nOffline: "
        + output.offlineCount()
        + "\nRecent offline: "
        + output.recentCount()
        + "\nVisibility public: "
        + output.publicCount()
        + "\nVisibility friends-only: "
        + output.friendsOnlyCount()
        + "\nVisibility private: "
        + output.privateCount()
        + "\nVisibility hidden-staff: "
        + output.hiddenStaffCount()
        + "\nVisibility unspecified: "
        + output.unspecifiedVisibilityCount()
        + "\nScope shared: "
        + output.sharedCount()
        + "\nScope isolated: "
        + output.isolatedCount()
        + "\nScope unspecified: "
        + output.unspecifiedScopeCount();
  }

  private String renderFriendPresencePolicyView(FriendPresencePolicyViewOutput output) {
    StringBuilder body =
        new StringBuilder()
            .append("Friend presence visibility: ")
            .append(output.currentPolicy())
            .append('\n');
    for (FriendPresencePolicyViewOutput.Option option : output.options()) {
      body.append(option.policy());
      if (option.current()) {
        body.append(" (current)");
      }
      if (!option.selectable()) {
        body.append(" [reserved]");
      }
      body.append(": ").append(option.description()).append('\n');
    }
    body.append("Use FRIENDS VISIBILITY <PUBLIC|FRIENDS_ONLY|PRIVATE>.");
    return body.toString();
  }

  private String renderFriendStatusSuffix(FriendPresenceViewOutput.Entry entry) {
    if (!StringUtils.hasText(entry.status()) || "active".equalsIgnoreCase(entry.status())) {
      return "";
    }
    return " {" + entry.status().trim().toLowerCase(java.util.Locale.ROOT) + "}";
  }

  private String renderFriendStatus(FriendPresenceViewOutput.Entry entry) {
    if (entry.online()) {
      StringBuilder line = new StringBuilder("online");
      String location = renderFriendLocation(entry);
      if (StringUtils.hasText(location)) {
        line.append(" in ").append(location);
      }
      if (StringUtils.hasText(entry.characterName())
          && !entry.characterName().equals(entry.displayName())) {
        line.append(" as ").append(entry.characterName());
      }
      if (StringUtils.hasText(entry.activityState())) {
        String activity = renderFriendActivity(entry.activityState());
        if (activity != null) {
          line.append(" (").append(activity).append(")");
        }
      }
      return line.toString();
    }
    if (entry.lastSeenAtEpochMs() != null) {
      String qualifier =
          switch (entry.recentDisposition()) {
            case "LOGOUT" -> "logged out";
            case "TAKEOVER" -> "replaced session";
            case "TRANSPORT_LOSS" -> "connection lost";
            default -> "last seen";
          };
      return qualifier + " " + java.time.Instant.ofEpochMilli(entry.lastSeenAtEpochMs());
    }
    return "offline";
  }

  private String renderFriendLocation(FriendPresenceViewOutput.Entry entry) {
    if (StringUtils.hasText(entry.worldDisplayName())
        && StringUtils.hasText(entry.realmDisplayName())) {
      return entry.worldDisplayName() + " / " + entry.realmDisplayName();
    }
    if (StringUtils.hasText(entry.worldDisplayName())) {
      return entry.worldDisplayName();
    }
    return StringUtils.hasText(entry.realmDisplayName()) ? entry.realmDisplayName() : null;
  }

  private String renderFriendActivity(String activityState) {
    return switch (activityState) {
      case "AUTO_AFK" -> "idle";
      case "EXPLICIT_AFK" -> "AFK";
      case "ACTIVE" -> "active";
      default -> null;
    };
  }

  private String renderInventoryView(
      InventoryViewOutput output,
      String localeTag,
      PresentationProperties effectivePresentationProperties) {
    StringBuilder out = new StringBuilder();
    out.append(
            colorizeLabel(
                localizedLabel("label.inventory", output.title(), localeTag),
                effectivePresentationProperties))
        .append("\n");
    if (output.lines().isEmpty()) {
      out.append("(empty)");
    } else {
      for (String line : output.lines()) {
        out.append(line).append("\n");
      }
      if (out.charAt(out.length() - 1) == '\n') {
        out.setLength(out.length() - 1);
      }
    }
    return out.toString();
  }

  private String renderMessage(TextMessageOutput output, String localeTag) {
    return presentationMessageCatalog.render(
        output.text(), output.messageKey(), output.arguments(), localeTag);
  }

  private String renderNotice(
      PlayerOutputPayload payload,
      String localeTag,
      PresentationProperties effectivePresentationProperties) {
    if (payload instanceof NoticeOutput output) {
      return colorizeNotice(
          presentationMessageCatalog.render(
              output.text(), output.messageKey(), output.arguments(), localeTag),
          effectivePresentationProperties);
    }
    if (payload instanceof FriendMutationResultOutput result) {
      return colorizeNotice(renderFriendMutationResult(result), effectivePresentationProperties);
    }
    throw new IllegalArgumentException(
        "Unsupported notice payload: " + payload.getClass().getName());
  }

  private String renderFriendMutationResult(FriendMutationResultOutput result) {
    if (StringUtils.hasText(result.characterName())) {
      return result.displayName()
          + " [acct #"
          + result.friendAccountId()
          + "] "
          + friendMutationVerb(result.action());
    }
    return result.displayName() + " " + friendMutationVerb(result.action());
  }

  private String friendMutationVerb(String action) {
    return "ADD".equals(action) ? "added." : "removed.";
  }

  private String renderNoticeWithCommand(
      TextCommand command,
      PlayerOutput output,
      String localeTag,
      PresentationProperties effectivePresentationProperties) {
    String body = render(output, localeTag, effectivePresentationProperties);
    String commandLabel = responseCommandLabel(command, java.util.List.of(output));
    if (body.contains("\n")) {
      return "OK " + commandLabel + "\n" + body + "\n\n";
    }
    return "OK " + commandLabel + " " + body;
  }

  private String renderError(ErrorOutput output, String localeTag) {
    return "ERROR "
        + output.code()
        + (StringUtils.hasText(output.message())
            ? " "
                + presentationMessageCatalog.render(
                    output.message(), output.messageKey(), output.arguments(), localeTag)
            : "");
  }

  private String localizedLabel(String messageKey, String fallbackText, String localeTag) {
    return presentationMessageCatalog.render(
        fallbackText, messageKey, java.util.Map.of(), localeTag);
  }

  private String colorizeLabel(
      String text, PresentationProperties effectivePresentationProperties) {
    return switch (effectivePresentationProperties.defaultColorMode()) {
      case NONE -> text;
      case BASIC -> ansi(text, "1;36");
      case RICH -> ansi(text, "38;5;81");
    };
  }

  private String colorizeRoomName(
      String text, PresentationProperties effectivePresentationProperties) {
    return switch (effectivePresentationProperties.defaultColorMode()) {
      case NONE -> text;
      case BASIC -> ansi(text, "1;33");
      case RICH -> ansi(text, "38;5;229");
    };
  }

  private String colorizePrompt(
      String text, PresentationProperties effectivePresentationProperties) {
    return switch (effectivePresentationProperties.defaultColorMode()) {
      case NONE -> text;
      case BASIC -> ansi(text, "1;32");
      case RICH -> ansi(text, "38;5;119");
    };
  }

  private String colorizeNotice(
      String text, PresentationProperties effectivePresentationProperties) {
    return switch (effectivePresentationProperties.defaultColorMode()) {
      case NONE -> text;
      case BASIC -> ansi(text, "1;33");
      case RICH -> ansi(text, "38;5;221");
    };
  }

  private String ansi(String text, String code) {
    if (!StringUtils.hasText(text)) {
      return text;
    }
    return "\u001B[" + code + "m" + text + "\u001B[0m";
  }

  private String renderTrailingPrompt(
      java.util.List<PlayerOutput> outputs,
      PresentationProperties effectivePresentationProperties,
      String localeTag) {
    for (int i = outputs.size() - 1; i >= 0; i--) {
      PlayerOutput output = outputs.get(i);
      if (output.kind() == PlayerOutputKind.PROMPT) {
        return render(output, localeTag, effectivePresentationProperties);
      }
    }
    return "";
  }

  private String appendPrompt(String base, String prompt, boolean inline) {
    if (!StringUtils.hasText(prompt)) {
      return base;
    }
    if (!StringUtils.hasText(base)) {
      return prompt;
    }
    return inline ? base + "\n" + prompt : base + prompt;
  }

  private String responseCommandLabel(TextCommand command, java.util.List<PlayerOutput> outputs) {
    if (command.type() == net.firedevops.firemud.gamesession.command.text.TextCommandType.MOVE
        && outputs.stream().allMatch(output -> output.kind() == PlayerOutputKind.VIEW)) {
      return net.firedevops.firemud.gamesession.command.text.TextCommandType.LOOK.name();
    }
    if (outputs.stream().allMatch(output -> output.kind() == PlayerOutputKind.VIEW)
        && outputs.stream()
            .map(PlayerOutput::payload)
            .filter(LookViewOutput.class::isInstance)
            .map(LookViewOutput.class::cast)
            .anyMatch(view -> view.refreshReason() == LookViewOutput.RefreshReason.MOVE_REFRESH)) {
      return net.firedevops.firemud.gamesession.command.text.TextCommandType.LOOK.name();
    }
    return command.type().name();
  }

  private boolean shouldUseBriefRendering(
      LookViewOutput result, PresentationProperties effectivePresentationProperties) {
    if (!result.includeLongDescription()) {
      return true;
    }
    if (effectivePresentationProperties.briefEnabledByDefault()) {
      return true;
    }
    return result.briefRenderingHint() == LookViewOutput.BriefRenderingHint.PREFER_BRIEF;
  }

  private TextCommand syntheticCommand(TextCommandType commandType) {
    return new TextCommand(commandType, java.util.List.of(), commandType.name());
  }
}
