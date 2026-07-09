package net.firedevops.firemud.gamesession.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.cache.ScreenBufferService;
import net.firedevops.firemud.gamesession.command.text.TextCommand;
import net.firedevops.firemud.gamesession.command.text.TextCommandActionCategory;
import net.firedevops.firemud.gamesession.command.text.TextCommandActionTag;
import net.firedevops.firemud.gamesession.command.text.TextCommandInterpretationResult;
import net.firedevops.firemud.gamesession.command.text.TextCommandMetadataResolver;
import net.firedevops.firemud.gamesession.command.text.TextCommandPayload;
import net.firedevops.firemud.gamesession.command.text.TextCommandType;
import net.firedevops.firemud.gamesession.config.PresentationProperties;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.presentation.FriendDetailViewOutput;
import net.firedevops.firemud.gamesession.presentation.FriendMutationResultOutput;
import net.firedevops.firemud.gamesession.presentation.FriendPresencePolicyViewOutput;
import net.firedevops.firemud.gamesession.presentation.FriendPresenceViewOutput;
import net.firedevops.firemud.gamesession.presentation.FriendRosterSummaryViewOutput;
import net.firedevops.firemud.gamesession.presentation.LookViewOutput;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.presentation.TextPlayerOutputRenderer;
import net.firedevops.firemud.gamesession.presentation.WhoViewOutput;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

class WebSocketOutputProjectorTest {

  private final PresentationProperties presentation = new PresentationProperties();
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final TextCommandMetadataResolver metadataResolver =
      commandId ->
          switch (commandId) {
            case "look" ->
                java.util.Optional.of(
                    new TextCommandMetadataResolver.ResolvedTextCommandMetadata(
                        net.firedevops.firemud.gamesession.command.text.TextCommandDispatchGroup
                            .LOOK,
                        TextCommandActionCategory.META,
                        List.of(TextCommandActionTag.UI)));
            case "wave" ->
                java.util.Optional.of(
                    new TextCommandMetadataResolver.ResolvedTextCommandMetadata(
                        net.firedevops.firemud.gamesession.command.text.TextCommandDispatchGroup
                            .AUTHORED,
                        TextCommandActionCategory.SOCIAL,
                        List.of(
                            TextCommandActionTag.AUTHORING, TextCommandActionTag.COMMUNICATION)));
            default -> java.util.Optional.empty();
          };
  private final WebSocketOutputProjector projector =
      new WebSocketOutputProjector(new TextPlayerOutputRenderer(presentation), metadataResolver);

  @Test
  void genericWebSocketStillReceivesClassicText() {
    WebSocketSession session = mock(WebSocketSession.class);
    when(session.getAttributes()).thenReturn(Map.of());

    String payload =
        projector.projectCommandResponse(
            session,
            new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"),
            new TextCommandInterpretationResult(
                CommandEnqueueResult.success(),
                List.of(PlayerOutput.notice("Entered world: demo"))),
            List.of(PlayerOutput.notice("Entered world: demo")),
            "en-NZ",
            presentation);

    assertThat(payload).startsWith("OK PLAY");
  }

  @Test
  void firstPartyWebReceivesStructuredCommandEnvelope() throws Exception {
    WebSocketSession session = mock(WebSocketSession.class);
    when(session.getAttributes())
        .thenReturn(
            Map.of(
                GameSessionWebSocketHandshakeInterceptor.CONNECTION_MODE_ATTR, "first_party_web"));

    String payload =
        projector.projectCommandResponse(
            session,
            new TextCommand(TextCommandType.LOOK, List.of(), "LOOK"),
            new TextCommandInterpretationResult(
                CommandEnqueueResult.success(), List.of(PlayerOutput.prompt("demo> "))),
            List.of(PlayerOutput.prompt("demo> ")),
            "en-NZ",
            presentation);

    JsonNode json = objectMapper.readTree(payload);
    assertThat(json.path("eventType").asText()).isEqualTo("command_result");
    assertThat(json.path("commandType").asText()).isEqualTo("LOOK");
    assertThat(json.path("commandId").asText()).isEqualTo("look");
    assertThat(json.path("actionCategory").asText()).isEqualTo("META");
    assertThat(json.path("actionTags")).extracting(JsonNode::asText).containsExactly("UI");
    assertThat(json.path("accepted").asBoolean()).isTrue();
    assertThat(json.path("outputs")).hasSize(1);
    assertThat(json.path("outputs").get(0).path("kind").asText()).isEqualTo("PROMPT");
    assertThat(json.path("outputs").get(0).path("payloadType").asText()).isEqualTo("prompt");
    assertThat(json.path("outputs").get(0).path("payload").path("text").asText())
        .isEqualTo("demo> ");
  }

  @Test
  void firstPartyWebReceivesCanonicalAuthoredCommandId() throws Exception {
    WebSocketSession session = mock(WebSocketSession.class);
    when(session.getAttributes())
        .thenReturn(
            Map.of(
                GameSessionWebSocketHandshakeInterceptor.CONNECTION_MODE_ATTR, "first_party_web"));

    String payload =
        projector.projectCommandResponse(
            session,
            new TextCommand(
                "wave",
                TextCommandType.AUTHORED,
                List.of("hello"),
                "wave hello",
                "wave",
                new TextCommandPayload.AuthoredActionInvocation("wave", List.of("hello"))),
            new TextCommandInterpretationResult(
                CommandEnqueueResult.success(), List.of(PlayerOutput.message("You wave hello."))),
            List.of(PlayerOutput.message("You wave hello.")),
            "en-NZ",
            presentation);

    JsonNode json = objectMapper.readTree(payload);
    assertThat(json.path("commandType").asText()).isEqualTo("AUTHORED");
    assertThat(json.path("commandId").asText()).isEqualTo("wave");
    assertThat(json.path("actionCategory").asText()).isEqualTo("SOCIAL");
    assertThat(json.path("actionTags"))
        .extracting(JsonNode::asText)
        .containsExactly("AUTHORING", "COMMUNICATION");
  }

  @Test
  void firstPartyWebReceivesStructuredAsyncOutputEnvelope() throws Exception {
    WebSocketSession session = mock(WebSocketSession.class);
    when(session.getAttributes())
        .thenReturn(
            Map.of(
                GameSessionWebSocketHandshakeInterceptor.CONNECTION_MODE_ATTR, "first_party_web"));

    String payload =
        projector.projectPlayerOutput(
            session,
            PlayerOutput.message(
                "You whisper to Sora, \"Keep quiet\"",
                "communication.whisper.actor",
                Map.of("targetName", "Sora", "message", "Keep quiet")),
            "en-NZ",
            presentation);

    JsonNode json = objectMapper.readTree(payload);
    assertThat(json.path("eventType").asText()).isEqualTo("player_output");
    assertThat(json.path("outputs")).hasSize(1);
    assertThat(json.path("outputs").get(0).path("kind").asText()).isEqualTo("MESSAGE");
    assertThat(json.path("outputs").get(0).path("payloadType").asText()).isEqualTo("text_message");
    assertThat(json.path("outputs").get(0).path("payload").path("messageKey").asText())
        .isEqualTo("communication.whisper.actor");
  }

  @Test
  void genericWebSocketProjectsViewOutputThroughLookRenderer() {
    TextPlayerOutputRenderer renderer = mock(TextPlayerOutputRenderer.class);
    WebSocketOutputProjector localProjector = new WebSocketOutputProjector(renderer);
    WebSocketSession session = mock(WebSocketSession.class);
    when(session.getAttributes()).thenReturn(Map.of());
    PlayerOutput output =
        PlayerOutput.view(
            new LookViewOutput(
                "room-1", "Room One", "Short desc", "Long desc", true, List.of(), List.of()));
    when(renderer.renderSuccessfulForCommandType(
            TextCommandType.LOOK, List.of(output), "en-NZ", presentation))
        .thenReturn("Room One\nLong desc");

    String payload = localProjector.projectPlayerOutput(session, output, "en-NZ", presentation);

    assertThat(payload).isEqualTo("Room One\nLong desc");
    verify(renderer)
        .renderSuccessfulForCommandType(
            TextCommandType.LOOK, List.of(output), "en-NZ", presentation);
    verify(renderer, never()).render(output, "en-NZ", presentation);
  }

  @Test
  void firstPartyWebProjectsWhoViewPayloads() throws Exception {
    WebSocketSession session = mock(WebSocketSession.class);
    when(session.getAttributes())
        .thenReturn(
            Map.of(
                GameSessionWebSocketHandshakeInterceptor.CONNECTION_MODE_ATTR, "first_party_web"));

    String payload =
        projector.projectCommandResponse(
            session,
            new TextCommand(TextCommandType.WHO, List.of(), "WHO"),
            new TextCommandInterpretationResult(
                CommandEnqueueResult.success(),
                List.of(
                    PlayerOutput.view(
                        new WhoViewOutput(
                            List.of(new WhoViewOutput.Entry(1, "Aster", "ACTIVE")),
                            List.of(new WhoViewOutput.Entry(1, "Ben", "EXPLICIT_AFK")))))),
            List.of(
                PlayerOutput.view(
                    new WhoViewOutput(
                        List.of(new WhoViewOutput.Entry(1, "Aster", "ACTIVE")),
                        List.of(new WhoViewOutput.Entry(1, "Ben", "EXPLICIT_AFK"))))),
            "en-NZ",
            presentation);

    JsonNode json = objectMapper.readTree(payload);
    assertThat(json.path("outputs")).hasSize(1);
    assertThat(json.path("outputs").get(0).path("payloadType").asText()).isEqualTo("who_view");
    assertThat(
            json.path("outputs")
                .get(0)
                .path("payload")
                .path("players")
                .get(0)
                .path("activityState")
                .asText())
        .isEqualTo("EXPLICIT_AFK");
  }

  @Test
  void firstPartyWebProjectsFriendsViewPayloads() throws Exception {
    WebSocketSession session = mock(WebSocketSession.class);
    when(session.getAttributes())
        .thenReturn(
            Map.of(
                GameSessionWebSocketHandshakeInterceptor.CONNECTION_MODE_ATTR, "first_party_web"));

    String payload =
        projector.projectCommandResponse(
            session,
            new TextCommand(TextCommandType.FRIENDS, List.of(), "FRIENDS"),
            new TextCommandInterpretationResult(
                CommandEnqueueResult.success(),
                List.of(
                    PlayerOutput.view(
                        new FriendPresenceViewOutput(
                            "ALL",
                            1,
                            1,
                            List.of(
                                new FriendPresenceViewOutput.Entry(
                                    1,
                                    11L,
                                    3L,
                                    "active",
                                    1_744_336_000_000L,
                                    "Sora",
                                    true,
                                    "demo",
                                    "Demo World",
                                    "production",
                                    "Live Realm",
                                    "Sora",
                                    "SHARED",
                                    17L,
                                    "AUTO_AFK",
                                    null,
                                    null,
                                    null)))))),
            List.of(
                PlayerOutput.view(
                    new FriendPresenceViewOutput(
                        "ALL",
                        1,
                        1,
                        List.of(
                            new FriendPresenceViewOutput.Entry(
                                1,
                                11L,
                                3L,
                                "active",
                                1_744_336_000_000L,
                                "Sora",
                                true,
                                "demo",
                                "Demo World",
                                "production",
                                "Live Realm",
                                "Sora",
                                "SHARED",
                                17L,
                                "AUTO_AFK",
                                null,
                                null,
                                null))))),
            "en-NZ",
            presentation);

    JsonNode json = objectMapper.readTree(payload);
    assertThat(json.path("outputs")).hasSize(1);
    assertThat(json.path("outputs").get(0).path("payloadType").asText()).isEqualTo("friends_view");
    assertThat(json.path("outputs").get(0).path("payload").path("filter").asText())
        .isEqualTo("ALL");
    assertThat(json.path("outputs").get(0).path("payload").path("totalCount").asInt()).isEqualTo(1);
    assertThat(
            json.path("outputs")
                .get(0)
                .path("payload")
                .path("friends")
                .get(0)
                .path("friendAccountId")
                .asLong())
        .isEqualTo(3L);
    assertThat(
            json.path("outputs")
                .get(0)
                .path("payload")
                .path("friends")
                .get(0)
                .path("friendLinkId")
                .asLong())
        .isEqualTo(11L);
    assertThat(
            json.path("outputs")
                .get(0)
                .path("payload")
                .path("friends")
                .get(0)
                .path("linkedAtEpochMs")
                .asLong())
        .isEqualTo(1_744_336_000_000L);
    assertThat(
            json.path("outputs")
                .get(0)
                .path("payload")
                .path("friends")
                .get(0)
                .path("worldDisplayName")
                .asText())
        .isEqualTo("Demo World");
    assertThat(
            json.path("outputs")
                .get(0)
                .path("payload")
                .path("friends")
                .get(0)
                .path("playableStateScope")
                .asText())
        .isEqualTo("SHARED");
    assertThat(
            json.path("outputs")
                .get(0)
                .path("payload")
                .path("friends")
                .get(0)
                .path("pointerVersion")
                .asLong())
        .isEqualTo(17L);
  }

  @Test
  void firstPartyWebProjectsFriendDetailViewPayloads() throws Exception {
    WebSocketSession session = mock(WebSocketSession.class);
    when(session.getAttributes())
        .thenReturn(
            Map.of(
                GameSessionWebSocketHandshakeInterceptor.CONNECTION_MODE_ATTR, "first_party_web"));

    String payload =
        projector.projectCommandResponse(
            session,
            new TextCommand(TextCommandType.FRIENDS, List.of("SHOW", "3"), "FRIENDS SHOW 3"),
            new TextCommandInterpretationResult(
                CommandEnqueueResult.success(),
                List.of(
                    PlayerOutput.view(
                        new FriendDetailViewOutput(
                            new FriendPresenceViewOutput.Entry(
                                1,
                                11L,
                                3L,
                                "active",
                                1_744_336_000_000L,
                                "Sora",
                                true,
                                "demo",
                                "Demo World",
                                "production",
                                "Live Realm",
                                "Sora",
                                "SHARED",
                                17L,
                                "AUTO_AFK",
                                null,
                                null,
                                null))))),
            List.of(
                PlayerOutput.view(
                    new FriendDetailViewOutput(
                        new FriendPresenceViewOutput.Entry(
                            1,
                            11L,
                            3L,
                            "active",
                            1_744_336_000_000L,
                            "Sora",
                            true,
                            "demo",
                            "Demo World",
                            "production",
                            "Live Realm",
                            "Sora",
                            "SHARED",
                            17L,
                            "AUTO_AFK",
                            null,
                            null,
                            null)))),
            "en-NZ",
            presentation);

    JsonNode json = objectMapper.readTree(payload);
    assertThat(json.path("outputs")).hasSize(1);
    assertThat(json.path("outputs").get(0).path("payloadType").asText())
        .isEqualTo("friend_detail_view");
    assertThat(
            json.path("outputs")
                .get(0)
                .path("payload")
                .path("friend")
                .path("friendAccountId")
                .asLong())
        .isEqualTo(3L);
    assertThat(
            json.path("outputs")
                .get(0)
                .path("payload")
                .path("friend")
                .path("playableStateScope")
                .asText())
        .isEqualTo("SHARED");
    assertThat(
            json.path("outputs")
                .get(0)
                .path("payload")
                .path("friend")
                .path("pointerVersion")
                .asLong())
        .isEqualTo(17L);
  }

  @Test
  void firstPartyWebProjectsFriendRosterSummaryViewPayloads() throws Exception {
    WebSocketSession session = mock(WebSocketSession.class);
    when(session.getAttributes())
        .thenReturn(
            Map.of(
                GameSessionWebSocketHandshakeInterceptor.CONNECTION_MODE_ATTR, "first_party_web"));

    String payload =
        projector.projectCommandResponse(
            session,
            new TextCommand(TextCommandType.FRIENDS, List.of("SUMMARY"), "FRIENDS SUMMARY"),
            new TextCommandInterpretationResult(
                CommandEnqueueResult.success(),
                List.of(
                    PlayerOutput.view(
                        new FriendRosterSummaryViewOutput(4, 1, 3, 2, 1, 2, 1, 0, 0, 2, 1, 1)))),
            List.of(
                PlayerOutput.view(
                    new FriendRosterSummaryViewOutput(4, 1, 3, 2, 1, 2, 1, 0, 0, 2, 1, 1))),
            "en-NZ",
            presentation);

    JsonNode json = objectMapper.readTree(payload);
    assertThat(json.path("outputs")).hasSize(1);
    assertThat(json.path("outputs").get(0).path("payloadType").asText())
        .isEqualTo("friend_roster_summary_view");
    assertThat(json.path("outputs").get(0).path("payload").path("totalCount").asInt()).isEqualTo(4);
    assertThat(json.path("outputs").get(0).path("payload").path("recentCount").asInt())
        .isEqualTo(2);
    assertThat(json.path("outputs").get(0).path("payload").path("friendsOnlyCount").asInt())
        .isEqualTo(2);
    assertThat(json.path("outputs").get(0).path("payload").path("privateCount").asInt())
        .isEqualTo(1);
    assertThat(json.path("outputs").get(0).path("payload").path("sharedCount").asInt())
        .isEqualTo(2);
    assertThat(json.path("outputs").get(0).path("payload").path("isolatedCount").asInt())
        .isEqualTo(1);
  }

  @Test
  void firstPartyWebProjectsFriendPresencePolicyViewPayloads() throws Exception {
    WebSocketSession session = mock(WebSocketSession.class);
    when(session.getAttributes())
        .thenReturn(
            Map.of(
                GameSessionWebSocketHandshakeInterceptor.CONNECTION_MODE_ATTR, "first_party_web"));

    FriendPresencePolicyViewOutput payloadView =
        new FriendPresencePolicyViewOutput(
            "FRIENDS_ONLY",
            List.of(
                new FriendPresencePolicyViewOutput.Option(
                    "PUBLIC", "Normal bounded payload.", false, true),
                new FriendPresencePolicyViewOutput.Option(
                    "FRIENDS_ONLY", "Approved friends only.", true, true),
                new FriendPresencePolicyViewOutput.Option("PRIVATE", "Coarse only.", false, true),
                new FriendPresencePolicyViewOutput.Option(
                    "HIDDEN_STAFF", "Reserved.", false, false)));

    String payload =
        projector.projectCommandResponse(
            session,
            new TextCommand(TextCommandType.FRIENDS, List.of("VISIBILITY"), "FRIENDS VISIBILITY"),
            new TextCommandInterpretationResult(
                CommandEnqueueResult.success(), List.of(PlayerOutput.view(payloadView))),
            List.of(PlayerOutput.view(payloadView)),
            "en-NZ",
            presentation);

    JsonNode json = objectMapper.readTree(payload);
    assertThat(json.path("outputs")).hasSize(1);
    assertThat(json.path("outputs").get(0).path("payloadType").asText())
        .isEqualTo("friend_presence_policy_view");
    assertThat(json.path("outputs").get(0).path("payload").path("currentPolicy").asText())
        .isEqualTo("FRIENDS_ONLY");
  }

  @Test
  void firstPartyWebProjectsFriendMutationResultPayloads() throws Exception {
    WebSocketSession session = mock(WebSocketSession.class);
    when(session.getAttributes())
        .thenReturn(
            Map.of(
                GameSessionWebSocketHandshakeInterceptor.CONNECTION_MODE_ATTR, "first_party_web"));

    FriendMutationResultOutput payloadView =
        new FriendMutationResultOutput("REMOVE", 77L, "Sora", "Sora", 1);

    String payload =
        projector.projectCommandResponse(
            session,
            new TextCommand(TextCommandType.FRIENDS, List.of("REMOVE", "#1"), "FRIENDS REMOVE #1"),
            new TextCommandInterpretationResult(
                CommandEnqueueResult.success(), List.of(PlayerOutput.notice(payloadView))),
            List.of(PlayerOutput.notice(payloadView)),
            "en-NZ",
            presentation);

    JsonNode json = objectMapper.readTree(payload);
    assertThat(json.path("outputs")).hasSize(1);
    assertThat(json.path("outputs").get(0).path("payloadType").asText())
        .isEqualTo("friend_mutation_result");
    assertThat(json.path("outputs").get(0).path("payload").path("action").asText())
        .isEqualTo("REMOVE");
    assertThat(json.path("outputs").get(0).path("payload").path("friendAccountId").asLong())
        .isEqualTo(77L);
    assertThat(json.path("outputs").get(0).path("payload").path("displayName").asText())
        .isEqualTo("Sora");
    assertThat(json.path("outputs").get(0).path("payload").path("ordinal").asInt()).isEqualTo(1);
  }

  @Test
  void firstPartyWebWrapsTranscriptChunksDuringReconnectReplay() throws Exception {
    WebSocketSession session = mock(WebSocketSession.class);
    when(session.getAttributes())
        .thenReturn(
            Map.of(
                GameSessionWebSocketHandshakeInterceptor.CONNECTION_MODE_ATTR, "first_party_web"));

    String payload =
        projector.projectTranscriptChunk(session, "screen buffer", "Recent combat line\n");

    JsonNode json = objectMapper.readTree(payload);
    assertThat(json.path("eventType").asText()).isEqualTo("transcript_chunk");
    assertThat(json.path("label").asText()).isEqualTo("screen buffer");
    assertThat(json.path("text").asText()).isEqualTo("Recent combat line\n");
  }

  @Test
  void firstPartyWebReplaysStructuredTranscriptEntriesWhenBufferedMetadataExists()
      throws Exception {
    WebSocketSession session = mock(WebSocketSession.class);
    when(session.getAttributes())
        .thenReturn(
            Map.of(
                GameSessionWebSocketHandshakeInterceptor.CONNECTION_MODE_ATTR, "first_party_web"));

    ScreenBufferService.BufferedEntry entry =
        projector.toBufferedEntry(
            PlayerOutput.message(
                "You say, \"hello\"", "communication.say.actor", Map.of("message", "hello")),
            "You say, \"hello\"\n");

    String payload = projector.projectTranscriptEntry(session, "screen buffer", entry);

    JsonNode json = objectMapper.readTree(payload);
    assertThat(json.path("eventType").asText()).isEqualTo("transcript_entry");
    assertThat(json.path("label").asText()).isEqualTo("screen buffer");
    assertThat(json.path("text").asText()).isEqualTo("You say, \"hello\"\n");
    assertThat(json.path("output").path("kind").asText()).isEqualTo("MESSAGE");
    assertThat(json.path("output").path("payloadType").asText()).isEqualTo("text_message");
    assertThat(json.path("output").path("payload").path("messageKey").asText())
        .isEqualTo("communication.say.actor");
  }

  @Test
  void firstPartyWebFallsBackToTranscriptChunkForUnreadableBufferedMetadata() throws Exception {
    WebSocketSession session = mock(WebSocketSession.class);
    when(session.getAttributes())
        .thenReturn(
            Map.of(
                GameSessionWebSocketHandshakeInterceptor.CONNECTION_MODE_ATTR, "first_party_web"));

    ScreenBufferService.BufferedEntry entry =
        ScreenBufferService.BufferedEntry.fromStructuredOutput(
            "Legacy safe text\n", "MESSAGE", "BUFFERABLE", "DEFAULT", "text_message", "{bad json");

    String payload = projector.projectTranscriptEntry(session, "screen buffer", entry);

    JsonNode json = objectMapper.readTree(payload);
    assertThat(json.path("eventType").asText()).isEqualTo("transcript_chunk");
    assertThat(json.path("text").asText()).isEqualTo("Legacy safe text\n");
  }

  @Test
  void firstPartyWebFallsBackToTranscriptChunkForIncompleteBufferedMetadata() throws Exception {
    WebSocketSession session = mock(WebSocketSession.class);
    when(session.getAttributes())
        .thenReturn(
            Map.of(
                GameSessionWebSocketHandshakeInterceptor.CONNECTION_MODE_ATTR, "first_party_web"));

    ScreenBufferService.BufferedEntry entry =
        new ScreenBufferService.BufferedEntry(
            "Legacy safe text\n",
            1,
            "Legacy safe text\n".getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
            System.currentTimeMillis(),
            "MESSAGE",
            null,
            "DEFAULT",
            "text_message",
            "{\"text\":\"Legacy safe text\"}");

    String payload = projector.projectTranscriptEntry(session, "screen buffer", entry);

    JsonNode json = objectMapper.readTree(payload);
    assertThat(json.path("eventType").asText()).isEqualTo("transcript_chunk");
    assertThat(json.path("text").asText()).isEqualTo("Legacy safe text\n");
  }
}
