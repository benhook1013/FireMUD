package net.firedevops.firemud.gamesession.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import net.firedevops.firemud.gamelogic.v1.CommunicationPerception;
import net.firedevops.firemud.gamelogic.v1.CommunicationRecipientRole;
import net.firedevops.firemud.gamelogic.v1.CommunicationRecipientView;
import net.firedevops.firemud.gamelogic.v1.CommunicationType;
import net.firedevops.firemud.gamelogic.v1.SendCommunicationResponse;
import net.firedevops.firemud.gamesession.command.text.TextCommand;
import net.firedevops.firemud.gamesession.command.text.TextCommandType;
import org.junit.jupiter.api.Test;

class CommunicationOutputMapperTest {
  private final CommunicationOutputMapper mapper = new CommunicationOutputMapper();

  @Test
  void actorSayPresentationNormalizesSpeech() {
    PlayerOutput output =
        mapper.actorOutput(
            new TextCommand(
                TextCommandType.SAY, java.util.List.of("hello travelers"), "SAY hello travelers"),
            SendCommunicationResponse.newBuilder()
                .setType(CommunicationType.SAY)
                .setMessage(" hello travelers ")
                .setSpeakerName("Emberline")
                .build());

    assertThat(output.text()).isEqualTo("You say, \"Hello travelers.\"");
  }

  @Test
  void sayRecipientPresentationNormalizesSpeech() {
    PlayerOutput output =
        mapper.recipientOutput(
            CommunicationType.SAY,
            CommunicationRecipientView.newBuilder()
                .setRole(CommunicationRecipientRole.COMMUNICATION_RECIPIENT_ROLE_TARGET)
                .setPerception(CommunicationPerception.COMMUNICATION_PERCEPTION_FULL_CONTENT)
                .setSpeakerName("Emberline")
                .build(),
            " hello travelers ");

    assertThat(output.text()).isEqualTo("Emberline says, \"Hello travelers.\"");
  }

  @Test
  void whisperRecipientPresentationNormalizesSpeech() {
    PlayerOutput output =
        mapper.recipientOutput(
            CommunicationType.WHISPER,
            CommunicationRecipientView.newBuilder()
                .setRole(CommunicationRecipientRole.COMMUNICATION_RECIPIENT_ROLE_TARGET)
                .setPerception(CommunicationPerception.COMMUNICATION_PERCEPTION_FULL_CONTENT)
                .setSpeakerName("Emberline")
                .setTargetName("Sora")
                .build(),
            " keep quiet ");

    assertThat(output.text()).isEqualTo("Emberline whispers to you, \"Keep quiet.\"");
  }

  @Test
  void tellRecipientPresentationNormalizesSpeechAndPreservesPunctuation() {
    PlayerOutput output =
        mapper.recipientOutput(
            CommunicationType.TELL,
            CommunicationRecipientView.newBuilder()
                .setRole(CommunicationRecipientRole.COMMUNICATION_RECIPIENT_ROLE_TARGET)
                .setPerception(CommunicationPerception.COMMUNICATION_PERCEPTION_FULL_CONTENT)
                .setSpeakerName("Emberline")
                .setTargetName("Sora")
                .build(),
            "Meet me at the forge!");

    assertThat(output.text()).isEqualTo("Emberline tells you, \"Meet me at the forge!\"");
  }
}
