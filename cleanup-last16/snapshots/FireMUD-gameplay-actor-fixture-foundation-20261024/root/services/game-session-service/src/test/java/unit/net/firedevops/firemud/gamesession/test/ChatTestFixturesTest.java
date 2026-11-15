package net.firedevops.firemud.gamesession.test;

import static org.assertj.core.api.Assertions.assertThat;

import net.firedevops.firemud.test.AccountRuntimeStubServer;
import org.junit.jupiter.api.Test;

class ChatTestFixturesTest {
  @Test
  void characterRosterUsesCanonicalAccountUuidsAndPreservesActorIds() {
    assertCharacter("Emberline", 7L, ChatTestFixtures.PLAYER_EMBERLINE);
    assertCharacter("Sora", 8L, ChatTestFixtures.PLAYER_SORA);
    assertCharacter("Nyx", 9L, ChatTestFixtures.PLAYER_NYX);
  }

  @Test
  void recipientSessionUsesTheSameSubjectAsTheCharacterRosterAndRoomView() {
    var context = ChatTestFixtures.soraRecipientSession(90210L, 1L, 3L);
    var character = ChatTestFixtures.characterByName("Sora");
    var roomSora = ChatTestFixtures.sampleEntities().getEntities(1);

    assertThat(context.sessionId()).isEqualTo(90210L);
    assertThat(context.tenantId()).isEqualTo(1L);
    assertThat(context.accountId())
        .isEqualTo(AccountRuntimeStubServer.accountUuidForTestFixture(8L));
    assertThat(context.accountId()).isEqualTo(character.getAccountId());
    assertThat(context.accountId())
        .isNotEqualTo(ChatTestFixtures.characterByName("Emberline").getAccountId())
        .isNotEqualTo(ChatTestFixtures.characterByName("Nyx").getAccountId());
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
  void unknownCharacterNameRemainsEmpty() {
    assertThat(ChatTestFixtures.characterByName("unknown").getId()).isEmpty();
    assertThat(ChatTestFixtures.characterByName("unknown").getAccountId()).isEmpty();
  }

  private static void assertCharacter(String name, long accountRowId, String actorId) {
    var character = ChatTestFixtures.characterByName(name);

    assertThat(character.getId()).isEqualTo(actorId);
    assertThat(character.getAccountId())
        .isEqualTo(AccountRuntimeStubServer.accountUuidForTestFixture(accountRowId));
    assertThat(character.getAccountId()).isNotEqualTo(character.getId());
  }
}
