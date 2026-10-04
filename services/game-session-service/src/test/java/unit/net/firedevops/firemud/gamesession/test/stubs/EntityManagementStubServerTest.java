package net.firedevops.firemud.gamesession.test.stubs;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.util.List;
import net.firedevops.firemud.entitymanagement.v1.Character;
import net.firedevops.firemud.entitymanagement.v1.EntityManagementServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.ListCharactersByAccountRequest;
import net.firedevops.firemud.entitymanagement.v1.ListCharactersByAccountResponse;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.test.ChatTestFixtures;
import net.firedevops.firemud.test.AccountRuntimeStubServer;
import org.junit.jupiter.api.Test;

class EntityManagementStubServerTest {

  @Test
  void listsOnlyThePersistedSharedCharacterForTheRequestedTenantAndAccount() throws Exception {
    try (EntityManagementStubServer server = new EntityManagementStubServer(0)) {
      ManagedChannel channel =
          ManagedChannelBuilder.forTarget(server.endpoint()).usePlaintext().build();
      try {
        EntityManagementServiceGrpc.EntityManagementServiceBlockingStub stub =
            EntityManagementServiceGrpc.newBlockingStub(channel);

        for (FixtureCharacter fixture :
            List.of(
                new FixtureCharacter(accountUuid(7L), "Emberline"),
                new FixtureCharacter(accountUuid(8L), "Sora"),
                new FixtureCharacter(accountUuid(9L), "Nyx"))) {
          ListCharactersByAccountResponse response =
              listCharacters(
                  stub, "1", fixture.accountId(), PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED);

          assertThat(response.hasError()).isFalse();
          assertThat(response.getCharactersList()).containsExactly(sharedCharacter(fixture.name()));
        }
      } finally {
        channel.shutdownNow();
      }
    }
  }

  @Test
  void listsLoadActorsByTheirPersistedTenantAndAccountOwner() throws Exception {
    Character firstPlayer =
        Character.newBuilder()
            .setId("101")
            .setTenantId("1")
            .setAccountId(accountUuid(101L))
            .setName("player-1")
            .build();
    Character secondPlayer =
        Character.newBuilder()
            .setId("102")
            .setTenantId("1")
            .setAccountId(accountUuid(102L))
            .setName("player-2")
            .build();

    try (EntityManagementStubServer server = new EntityManagementStubServer(0)) {
      server.setCharacters(List.of(firstPlayer, secondPlayer));
      ManagedChannel channel =
          ManagedChannelBuilder.forTarget(server.endpoint()).usePlaintext().build();
      try {
        EntityManagementServiceGrpc.EntityManagementServiceBlockingStub stub =
            EntityManagementServiceGrpc.newBlockingStub(channel);

        assertThat(
                listCharacters(
                    stub, "1", accountUuid(101L), PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
                    .getCharactersList())
            .containsExactly(sharedCharacter(firstPlayer));
        assertThat(
                listCharacters(
                    stub, "1", accountUuid(102L), PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
                    .getCharactersList())
            .containsExactly(sharedCharacter(secondPlayer));
        assertThat(
                listCharacters(
                    stub, "2", accountUuid(101L), PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
                    .getCharactersList())
            .isEmpty();
      } finally {
        channel.shutdownNow();
      }
    }
  }

  @Test
  void unknownOrMismatchedTenantAndAccountReturnNoCharacters() throws Exception {
    try (EntityManagementStubServer server = new EntityManagementStubServer(0)) {
      ManagedChannel channel =
          ManagedChannelBuilder.forTarget(server.endpoint()).usePlaintext().build();
      try {
        EntityManagementServiceGrpc.EntityManagementServiceBlockingStub stub =
            EntityManagementServiceGrpc.newBlockingStub(channel);

        assertThat(
                listCharacters(
                        stub,
                        "2",
                        accountUuid(7L),
                        PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
                    .getCharactersList())
            .isEmpty();
        assertThat(
                listCharacters(
                        stub,
                        "1",
                        accountUuid(999L),
                        PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
                    .getCharactersList())
            .isEmpty();
      } finally {
        channel.shutdownNow();
      }
    }
  }

  @Test
  void unsupportedPlayableStateScopesReturnNoCharacters() throws Exception {
    try (EntityManagementStubServer server = new EntityManagementStubServer(0)) {
      ManagedChannel channel =
          ManagedChannelBuilder.forTarget(server.endpoint()).usePlaintext().build();
      try {
        EntityManagementServiceGrpc.EntityManagementServiceBlockingStub stub =
            EntityManagementServiceGrpc.newBlockingStub(channel);

        assertThat(
                listCharacters(
                        stub,
                        "1",
                        accountUuid(7L),
                        PlayableStateScope.PLAYABLE_STATE_SCOPE_UNSPECIFIED)
                    .getCharactersList())
            .isEmpty();
        assertThat(
                listCharacters(
                        stub,
                        "1",
                        accountUuid(7L),
                        PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED)
                    .getCharactersList())
            .isEmpty();
      } finally {
        channel.shutdownNow();
      }
    }
  }

  private static ListCharactersByAccountResponse listCharacters(
      EntityManagementServiceGrpc.EntityManagementServiceBlockingStub stub,
      String tenantId,
      String accountId,
      PlayableStateScope scope) {
    return stub.listCharactersByAccount(
        ListCharactersByAccountRequest.newBuilder()
            .setTenantId(tenantId)
            .setAccountId(accountId)
            .setGameInstanceId("42")
            .setPlayableStateScope(scope)
            .build());
  }

  private static Character sharedCharacter(String name) {
    return sharedCharacter(ChatTestFixtures.characterByName(name));
  }

  private static Character sharedCharacter(Character character) {
    return character.toBuilder()
        .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
        .build();
  }

  private static String accountUuid(long accountRowId) {
    return AccountRuntimeStubServer.accountUuidForTestFixture(accountRowId);
  }

  private record FixtureCharacter(String accountId, String name) {}
}
