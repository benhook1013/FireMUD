package net.firedevops.firemud.gamesession.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import net.firedevops.firemud.cache.ScreenBufferService;
import net.firedevops.firemud.gamesession.command.text.AdmittedTextCommandRegistryResolver;
import net.firedevops.firemud.gamesession.command.text.TextCommand;
import net.firedevops.firemud.gamesession.command.text.TextCommandInterpretationResult;
import net.firedevops.firemud.gamesession.command.text.TextCommandMetadataResolver;
import net.firedevops.firemud.gamesession.config.PresentationProperties;
import net.firedevops.firemud.gamesession.presentation.ActorStateViewOutput;
import net.firedevops.firemud.gamesession.presentation.CharacterBrowseViewOutput;
import net.firedevops.firemud.gamesession.presentation.ErrorOutput;
import net.firedevops.firemud.gamesession.presentation.FriendDetailViewOutput;
import net.firedevops.firemud.gamesession.presentation.FriendMutationResultOutput;
import net.firedevops.firemud.gamesession.presentation.FriendPresencePolicyViewOutput;
import net.firedevops.firemud.gamesession.presentation.FriendPresenceViewOutput;
import net.firedevops.firemud.gamesession.presentation.FriendRosterSummaryViewOutput;
import net.firedevops.firemud.gamesession.presentation.InventoryViewOutput;
import net.firedevops.firemud.gamesession.presentation.ItemMutationResultOutput;
import net.firedevops.firemud.gamesession.presentation.LookViewOutput;
import net.firedevops.firemud.gamesession.presentation.NoticeOutput;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.presentation.PlayerOutputPayload;
import net.firedevops.firemud.gamesession.presentation.PromptOutput;
import net.firedevops.firemud.gamesession.presentation.RealmBrowseViewOutput;
import net.firedevops.firemud.gamesession.presentation.TextMessageOutput;
import net.firedevops.firemud.gamesession.presentation.TextPlayerOutputRenderer;
import net.firedevops.firemud.gamesession.presentation.WhoViewOutput;
import net.firedevops.firemud.gamesession.presentation.WorldsViewOutput;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/** Projects structured player outputs to either classic text or first-party structured messages. */
@Component
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected renderer dependency is framework-managed and retained internally")
public final class WebSocketOutputProjector {
  private final TextPlayerOutputRenderer textRenderer;
  private final TextCommandMetadataResolver textCommandMetadataResolver;
  private final AdmittedTextCommandRegistryResolver admittedRegistryResolver;
  private final com.fasterxml.jackson.databind.ObjectMapper objectMapper =
      new com.fasterxml.jackson.databind.ObjectMapper();

  public WebSocketOutputProjector(TextPlayerOutputRenderer textRenderer) {
    this(textRenderer, commandId -> java.util.Optional.empty(), null);
  }

  public WebSocketOutputProjector(
      TextPlayerOutputRenderer textRenderer,
      TextCommandMetadataResolver textCommandMetadataResolver) {
    this(textRenderer, textCommandMetadataResolver, null);
  }

  @Autowired
  public WebSocketOutputProjector(
      TextPlayerOutputRenderer textRenderer,
      TextCommandMetadataResolver textCommandMetadataResolver,
      AdmittedTextCommandRegistryResolver admittedRegistryResolver) {
    this.textRenderer = textRenderer;
    this.textCommandMetadataResolver = textCommandMetadataResolver;
    this.admittedRegistryResolver = admittedRegistryResolver;
  }

  public String projectCommandResponse(
      WebSocketSession session,
      TextCommand command,
      TextCommandInterpretationResult interpretation,
      List<PlayerOutput> outputs,
      String localeTag,
      PresentationProperties effectivePresentation) {
    return projectCommandResponse(
        session, command, interpretation, outputs, localeTag, effectivePresentation, null);
  }

  public String projectCommandResponse(
      WebSocketSession session,
      TextCommand command,
      TextCommandInterpretationResult interpretation,
      List<PlayerOutput> outputs,
      String localeTag,
      PresentationProperties effectivePresentation,
      SessionContext context) {
    if (!isFirstPartyWeb(session)) {
      return textRenderer.renderAll(
          command, interpretation.commandResult(), outputs, localeTag, effectivePresentation);
    }
    return toJson(
        new FirstPartyEnvelope(
            "command_result",
            command.type().name(),
            command.commandId(),
            resolveActionCategory(command, interpretation, context),
            resolveActionTags(command, interpretation, context),
            interpretation.commandResult().accepted(),
            interpretation.commandResult().errorCode(),
            interpretation.commandResult().errorMessage(),
            interpretation.reconnectRedrawRecommended(),
            outputs.stream().map(this::toEnvelope).toList()));
  }

  public String projectPlayerOutput(
      WebSocketSession session,
      PlayerOutput output,
      String localeTag,
      PresentationProperties effectivePresentation) {
    if (!isFirstPartyWeb(session)) {
      return renderClassicPlayerOutput(output, localeTag, effectivePresentation);
    }
    return toJson(
        new FirstPartyEnvelope(
            "player_output",
            null,
            null,
            null,
            java.util.List.of(),
            null,
            null,
            null,
            null,
            List.of(toEnvelope(output))));
  }

  public String renderClassicPlayerOutput(
      PlayerOutput output, String localeTag, PresentationProperties effectivePresentation) {
    if (output.kind() == net.firedevops.firemud.gamesession.presentation.PlayerOutputKind.VIEW) {
      return textRenderer.renderSuccessfulForOutput(output, localeTag, effectivePresentation);
    }
    return textRenderer.render(output, localeTag, effectivePresentation);
  }

  public String projectTranscriptChunk(WebSocketSession session, String label, String text) {
    if (!isFirstPartyWeb(session)) {
      return text;
    }
    return toJson(new TranscriptChunkEnvelope("transcript_chunk", label, text));
  }

  public String projectTranscriptEntry(
      WebSocketSession session, String label, ScreenBufferService.BufferedEntry entry) {
    if (!isFirstPartyWeb(session) || entry == null || !entry.hasStructuredOutput()) {
      return projectTranscriptChunk(session, label, entry == null ? "" : entry.text());
    }
    JsonNode payload = parsePayload(entry.payloadJson());
    if (payload == null) {
      return projectTranscriptChunk(session, label, entry.text());
    }
    return toJson(
        new TranscriptEntryEnvelope(
            "transcript_entry",
            label,
            entry.text(),
            new FirstPartyPlayerOutputEnvelope(
                entry.outputKind(),
                entry.replayPolicy(),
                entry.briefRenderPolicy(),
                entry.payloadType(),
                payload)));
  }

  public ScreenBufferService.BufferedEntry toBufferedEntry(
      PlayerOutput output, String protocolText) {
    return ScreenBufferService.BufferedEntry.fromStructuredOutput(
        protocolText,
        output.kind().name(),
        output.replayPolicy().name(),
        output.briefRenderPolicy().name(),
        payloadType(output.payload()),
        toJson(output.payload()));
  }

  boolean isFirstPartyWeb(WebSocketSession session) {
    Object mode =
        session.getAttributes().get(GameSessionWebSocketHandshakeInterceptor.CONNECTION_MODE_ATTR);
    return "first_party_web".equals(mode);
  }

  private FirstPartyPlayerOutputEnvelope toEnvelope(PlayerOutput output) {
    return new FirstPartyPlayerOutputEnvelope(
        output.kind().name(),
        output.replayPolicy().name(),
        output.briefRenderPolicy().name(),
        payloadType(output.payload()),
        output.payload());
  }

  private String payloadType(PlayerOutputPayload payload) {
    return switch (payload) {
      case TextMessageOutput ignored -> "text_message";
      case PromptOutput ignored -> "prompt";
      case NoticeOutput ignored -> "notice";
      case FriendMutationResultOutput ignored -> "friend_mutation_result";
      case ItemMutationResultOutput ignored -> "item_mutation_result";
      case ErrorOutput ignored -> "error";
      case LookViewOutput ignored -> "look_view";
      case InventoryViewOutput ignored -> "inventory_view";
      case WorldsViewOutput ignored -> "worlds_view";
      case RealmBrowseViewOutput ignored -> "realms_view";
      case CharacterBrowseViewOutput ignored -> "characters_view";
      case WhoViewOutput ignored -> "who_view";
      case ActorStateViewOutput ignored -> "actor_state_view";
      case FriendPresenceViewOutput ignored -> "friends_view";
      case FriendDetailViewOutput ignored -> "friend_detail_view";
      case FriendRosterSummaryViewOutput ignored -> "friend_roster_summary_view";
      case FriendPresencePolicyViewOutput ignored -> "friend_presence_policy_view";
      default -> "unknown";
    };
  }

  private String toJson(Object envelope) {
    try {
      return objectMapper.writeValueAsString(envelope);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Failed to serialize first-party output envelope", ex);
    }
  }

  private JsonNode parsePayload(String payloadJson) {
    try {
      return objectMapper.readTree(payloadJson);
    } catch (JsonProcessingException ex) {
      return null;
    }
  }

  private record FirstPartyEnvelope(
      String eventType,
      String commandType,
      String commandId,
      String actionCategory,
      List<String> actionTags,
      Boolean accepted,
      String errorCode,
      String errorMessage,
      Boolean reconnectRedrawRecommended,
      List<FirstPartyPlayerOutputEnvelope> outputs) {}

  private record FirstPartyPlayerOutputEnvelope(
      String kind,
      String replayPolicy,
      String briefRenderPolicy,
      String payloadType,
      Object payload) {}

  private record TranscriptChunkEnvelope(String eventType, String label, String text) {}

  private record TranscriptEntryEnvelope(
      String eventType, String label, String text, FirstPartyPlayerOutputEnvelope output) {}

  private String resolveActionCategory(
      TextCommand command, TextCommandInterpretationResult interpretation, SessionContext context) {
    return resolveMetadata(command, interpretation, context)
        .map(TextCommandMetadataResolver.ResolvedTextCommandMetadata::actionCategory)
        .map(Enum::name)
        .orElse(null);
  }

  private List<String> resolveActionTags(
      TextCommand command, TextCommandInterpretationResult interpretation, SessionContext context) {
    return resolveMetadata(command, interpretation, context)
        .map(metadata -> metadata.actionTags().stream().map(Enum::name).toList())
        .orElse(java.util.List.of());
  }

  private java.util.Optional<TextCommandMetadataResolver.ResolvedTextCommandMetadata>
      resolveMetadata(
          TextCommand command,
          TextCommandInterpretationResult interpretation,
          SessionContext context) {
    if (interpretation.resolvedMetadata() != null) {
      return java.util.Optional.of(interpretation.resolvedMetadata());
    }
    if (admittedRegistryResolver != null && context != null) {
      return admittedRegistryResolver.resolveMetadata(
          context, command.commandId(), command.aliasUsed());
    }
    return textCommandMetadataResolver.resolve(command.commandId());
  }
}
