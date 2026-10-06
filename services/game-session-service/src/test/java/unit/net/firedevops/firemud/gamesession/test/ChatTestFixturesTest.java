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
