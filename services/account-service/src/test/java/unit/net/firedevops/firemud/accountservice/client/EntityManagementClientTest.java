package net.firedevops.firemud.accountservice.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.UUID;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.entitymanagement.v1.Character;
import net.firedevops.firemud.entitymanagement.v1.EntityManagementServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.ListCharactersByAccountRequest;
import net.firedevops.firemud.entitymanagement.v1.ListCharactersByAccountResponse;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class EntityManagementClientTest {
  @Test
  void listCharactersByAccountSerializesTheCanonicalAccountUuid() throws Exception {
    EntityManagementServiceGrpc.EntityManagementServiceBlockingStub stub = mockStub();
    Character character = Character.newBuilder().setId("character-1").setName("Mara").build();
    when(stub.listCharactersByAccount(any()))
        .thenReturn(ListCharactersByAccountResponse.newBuilder().addCharacters(character).build());
    EntityManagementClient client = newClient(stub);
    UUID accountUuid = UUID.fromString("4cae05e8-7a6b-4b14-9d44-665e3eec450b");

    assertThat(
            client.listCharactersByAccount(
                7L, accountUuid, 44L, PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
        .containsExactly(character);

    ArgumentCaptor<ListCharactersByAccountRequest> request =
        ArgumentCaptor.forClass(ListCharactersByAccountRequest.class);
    verify(stub).listCharactersByAccount(request.capture());
    assertThat(request.getValue().getTenantId()).isEqualTo("7");
    assertThat(request.getValue().getAccountId()).isEqualTo(accountUuid.toString());
    assertThat(request.getValue().getGameInstanceId()).isEqualTo("44");
    assertThat(request.getValue().getPlayableStateScope())
        .isEqualTo(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED);
  }

  @Test
  void listCharactersByAccountRejectsNullOrNilUuidBeforeDispatch() throws Exception {
    EntityManagementServiceGrpc.EntityManagementServiceBlockingStub stub = mockStub();
    EntityManagementClient client = newClient(stub);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            client.listCharactersByAccount(
                7L, null, 44L, PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            client.listCharactersByAccount(
                7L, new UUID(0L, 0L), 44L, PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED));

    verify(stub, never()).listCharactersByAccount(any());
  }

  private static EntityManagementClient newClient(
      EntityManagementServiceGrpc.EntityManagementServiceBlockingStub stub) throws Exception {
    EntityManagementClient client =
        new EntityManagementClient(
            new ServiceEndpointsProperties(),
            new CommonGrpcClientProperties(),
            mock(GrpcChannelFactory.class),
            BlockingGrpcStubCustomizer.noop());
    Field field = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("stub");
    field.setAccessible(true);
    field.set(client, stub);
    return client;
  }

  private static EntityManagementServiceGrpc.EntityManagementServiceBlockingStub mockStub() {
    return mock(EntityManagementServiceGrpc.EntityManagementServiceBlockingStub.class);
  }
}
