package net.firedevops.firemud.gamesession.test;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ChatTestFixturesTest {
  @Test
  void rosterAndRoomViewsRetainThePublishedNumericFixtureBindings() {
    assertThat(ChatTestFixtures.EMBERLINE_ACCOUNT_ROW_ID).isEqualTo(7L);
    assertThat(ChatTestFixtures.SORA_ACCOUNT_ROW_ID).isEqualTo(8L);
    assertThat(ChatTestFixtures.NYX_ACCOUNT_ROW_ID).isEqualTo(9L);
    assertThat(ChatTestFixtures.EMBERLINE_CHARACTER_ID).isEqualTo(7L);
    assertThat(ChatTestFixtures.SORA_CHARACTER_ID).isEqualTo(8L);
    assertThat(ChatTestFixtures.NYX_CHARACTER_ID).isEqualTo(9L);
    assertThat(ChatTestFixtures.characterByName("Emberline").getAccountId()).isEqualTo("7");
    assertThat(ChatTestFixtures.characterByName("Sora").getAccountId()).isEqualTo("8");
    assertThat(ChatTestFixtures.characterByName("Nyx").getAccountId()).isEqualTo("9");
    assertThat(ChatTestFixtures.sampleEntities().getEntitiesList())
        .extracting(entity -> entity.getEntityId())
        .containsExactly("7", "8", "9", "NPC-001");
  }

  @Test
  void recipientSessionUsesTheSameSubjectAsTheCharacterRosterAndRoomView() {
    var context = ChatTestFixtures.soraRecipientSession(90210L, 1L, 3L);
    var character = ChatTestFixtures.characterByName("Sora");
    var roomSora = ChatTestFixtures.sampleEntities().getEntities(1);

    assertThat(context.sessionId()).isEqualTo(90210L);
    assertThat(context.tenantId()).isEqualTo(1L);
    assertThat(context.accountId()).isEqualTo("8");
    assertThat(context.accountId()).isEqualTo(character.getAccountId());
    assertThat(context.characterId()).isEqualTo(8L);
    assertThat(Long.toString(context.characterId())).isEqualTo(character.getId());
    assertThat(roomSora.getEntityId()).isEqualTo(character.getId());
    assertThat(context.characterName()).isEqualTo(roomSora.getDisplayName());
    assertThat(context.gameInstanceId()).isEqualTo(3L);
    assertThat(context.bootstrapGameInstanceId()).isEqualTo(3L);
    assertThat(context.roomInstanceId()).isEqualTo("R-1021");
    assertThat(context.worldSlug()).isEqualTo("demo");
    assertThat(context.realmSlug()).isEqualTo("production");
    assertThat(context.pointerVersion()).isEqualTo(1L);
    assertThat(context.playableStateScope()).isEqualTo("SHARED");
  }

  @Test
  void scenarioOverridesDoNotChangeTheNextRosterOrRecipient() {
    var changed =
        ChatTestFixtures.characterByName("Sora").toBuilder().setAccountId("81").setId("82").build();

    assertThat(changed.getAccountId()).isEqualTo("81");
    assertThat(changed.getId()).isEqualTo("82");
    assertThat(ChatTestFixtures.characterByName("Sora").getAccountId()).isEqualTo("8");
    assertThat(ChatTestFixtures.characterByName("Sora").getId()).isEqualTo("8");
    assertThat(ChatTestFixtures.soraRecipientSession(90210L, 1L, 3L).characterId()).isEqualTo(8L);
  }
}
