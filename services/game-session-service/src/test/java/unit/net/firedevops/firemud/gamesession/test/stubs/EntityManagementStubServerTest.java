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
                new FixtureCharacter("7", "Emberline"),
                new FixtureCharacter("8", "Sora"),
                new FixtureCharacter("9", "Nyx"))) {
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
  void unknownOrMismatchedTenantAndAccountReturnNoCharacters() throws Exception {
    try (EntityManagementStubServer server = new EntityManagementStubServer(0)) {
      ManagedChannel channel =
          ManagedChannelBuilder.forTarget(server.endpoint()).usePlaintext().build();
      try {
        EntityManagementServiceGrpc.EntityManagementServiceBlockingStub stub =
            EntityManagementServiceGrpc.newBlockingStub(channel);

        assertThat(
                listCharacters(stub, "2", "7", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
                    .getCharactersList())
            .isEmpty();
        assertThat(
                listCharacters(stub, "1", "999", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
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
                listCharacters(stub, "1", "7", PlayableStateScope.PLAYABLE_STATE_SCOPE_UNSPECIFIED)
                    .getCharactersList())
            .isEmpty();
        assertThat(
                listCharacters(stub, "1", "7", PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED)
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
    return ChatTestFixtures.characterByName(name).toBuilder()
        .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
        .build();
  }

  private record FixtureCharacter(String accountId, String name) {}
}
