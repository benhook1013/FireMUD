package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.common.gameplay.GameplayCatalogProperties;
import net.firedevops.firemud.entitymanagement.v1.Character;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamelogic.v1.CommunicationType;
import net.firedevops.firemud.gamelogic.v1.SendCommunicationResponse;
import net.firedevops.firemud.gamesession.client.EntityManagementClient;
import net.firedevops.firemud.gamesession.client.GameLogicClient;
import net.firedevops.firemud.gamesession.config.GameLogicProperties;
import net.firedevops.firemud.gamesession.presentation.CommunicationOutputMapper;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.presentation.PlayerOutputKind;
import net.firedevops.firemud.gamesession.service.CommunicationRecipientDeliveryService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.service.SessionContextService;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class CommunicationCommandHandlerTest {
  private final EntityManagementClient entityManagementClient =
      Mockito.mock(EntityManagementClient.class);
  private final GameplayCatalogProperties gameplayCatalogProperties =
      new GameplayCatalogProperties();
  private final GameLogicClient gameLogicClient = Mockito.mock(GameLogicClient.class);
  private final GameLogicProperties gameLogicProperties = new GameLogicProperties();
  private final SessionContextService sessionContextService =
      Mockito.mock(SessionContextService.class);
  private final CommunicationRecipientDeliveryService recipientDeliveryService =
      Mockito.mock(CommunicationRecipientDeliveryService.class);
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private CommunicationCommandHandler handler;
  private final SessionContext sessionContext =
      new SessionContext(
          1L, 22L, 123L, "emberline@example.com", 911L, "Emberline", 1L, "room-7", "jwt-token");

  @BeforeEach
  void setUp() {
    gameplayCatalogProperties.getWorlds().clear();
    GameplayCatalogProperties.World world = new GameplayCatalogProperties.World();
    world.setSlug("demo");
    world.setDisplayName("Demo World");
    GameplayCatalogProperties.Realm realm = new GameplayCatalogProperties.Realm();
    realm.setSlug("production");
    realm.setDisplayName("Live Realm");
    realm.setTenantId(22L);
    realm.setGameInstanceId(1L);
    world.setRealms(List.of(realm));
    gameplayCatalogProperties.setWorlds(List.of(world));
    handler =
        new CommunicationCommandHandler(
            entityManagementClient,
            new GameplayWorldCatalog(gameplayCatalogProperties),
            gameLogicClient,
            gameLogicProperties,
            sessionContextService,
            recipientDeliveryService,
            new CommunicationOutputMapper(),
            meterRegistry);
  }

  @Test
  void saySuccessReturnsCanonicalText() {
    SendCommunicationResponse response =
        SendCommunicationResponse.newBuilder()
            .setSuccess(true)
            .setMessage(" hello travelers ")
            .setSpeakerName("Emberline")
            .addDeliveredTo("Emberline")
            .addDeliveredTo("Sora")
            .build();
    when(gameLogicClient.sendCommunication(
            Mockito.eq(sessionContext),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.any(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString()))
        .thenReturn(response);

    CommunicationCommandHandlingResult result =
        handler.handle(
            sessionContext,
            new TextCommand(
                TextCommandType.SAY, List.of("hello travelers"), "SAY hello travelers"));

    assertThat(result.commandResult().accepted()).isTrue();
    assertThat(result.outputs())
        .extracting(output -> output.kind())
        .containsExactly(PlayerOutputKind.MESSAGE);
    assertThat(joinedOutputText(result.outputs())).isEqualTo("You say, \"Hello travelers.\"");
    Mockito.verify(gameLogicClient)
        .sendCommunication(
            sessionContext,
            "Emberline",
            "room-7",
            CommunicationType.SAY,
            "hello travelers",
            "",
            "");
  }

  @Test
  void whisperRequiresTargetAndMessage() {
    CommunicationCommandHandlingResult result =
        handler.handle(
            sessionContext,
            new TextCommand(TextCommandType.WHISPER, List.of("Sora"), "WHISPER Sora"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("INVALID_ARGUMENT");
  }

  @Test
  void tellRequiresOnlineTarget() {
    when(entityManagementClient.findCharacterByName(
            sessionContext, PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED, "Sora"))
        .thenReturn(
            Optional.of(
                Character.newBuilder()
                    .setId("300")
                    .setTenantId("22")
                    .setAccountId("700")
                    .setName("Sora")
                    .build()));
    when(sessionContextService.findByGameplayName(22L, 1L, "Sora")).thenReturn(Optional.empty());

    CommunicationCommandHandlingResult result =
        handler.handle(
            sessionContext,
            new TextCommand(
                TextCommandType.TELL, List.of("Sora", "Meet me later"), "TELL Sora Meet me later"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorMessage()).contains("Target is not available");
  }

  @Test
  void underlyingFailurePropagates() {
    ErrorDetail error =
        ErrorDetail.newBuilder().setCode("PERMISSION_DENIED").setMessage("silenced").build();
    when(gameLogicClient.sendCommunication(
            Mockito.eq(sessionContext),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.any(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString()))
        .thenReturn(
            SendCommunicationResponse.newBuilder()
                .setSuccess(false)
                .setError(error)
                .setMessage("unused")
                .build());

    CommunicationCommandHandlingResult result =
        handler.handle(
            sessionContext, new TextCommand(TextCommandType.SAY, List.of("Hello"), "SAY Hello"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("COMMUNICATION_NOT_DELIVERED");
    assertThat(result.commandResult().errorMessage()).isEqualTo("silenced");
  }

  private static String joinedOutputText(List<PlayerOutput> outputs) {
    return outputs.stream()
        .map(PlayerOutput::text)
        .filter(text -> text != null && !text.isBlank())
        .reduce((left, right) -> left + "\n" + right)
        .orElse(null);
  }
}
