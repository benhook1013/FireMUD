package net.firedevops.firemud.entitymanagement.charactercreate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.UUID;
import net.firedevops.firemud.entitymanagement.charactercreate.EntityPlayerCreatedRosterReadService.AuthenticatedAccountSelector;
import net.firedevops.firemud.entitymanagement.charactercreate.EntityPlayerCreatedRosterReadService.PublishedIdentity;
import net.firedevops.firemud.entitymanagement.v1.EntityCharacterCreateTerminal;
import net.firedevops.firemud.gamesession.v1.ReadPlayerCreatedRosterSourceRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Order-only unit proof; authenticated source custody is exercised by the loopback mTLS test. */
class EntityPlayerCreatedRosterReadServiceTest {
  @AfterEach
  void clearTransactionMarker() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void rejectsNilAuthenticatedAccountSelector() {
    assertThatThrownBy(() -> new AuthenticatedAccountSelector(new UUID(0L, 0L)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void refusesAmbientSqlBeforeCallingTheSourceOwner() {
    var repository = mock(EntityCharacterCreateRepository.class);
    var source = mock(EntityPlayerCreatedRosterSourceClient.class);
    var service = new EntityPlayerCreatedRosterReadService(repository, source);
    TransactionSynchronizationManager.initSynchronization();

    assertThatThrownBy(
            () ->
                service.read(
                    new AuthenticatedAccountSelector(UUID.randomUUID()),
                    ReadPlayerCreatedRosterSourceRequest.getDefaultInstance()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("short Entity transactions");
    verifyNoInteractions(source, repository);
  }

  @Test
  void selectedReadCallsSourceBeforeRepositoryWhenSourceFails() {
    var repository = mock(EntityCharacterCreateRepository.class);
    var source = mock(EntityPlayerCreatedRosterSourceClient.class);
    var service = new EntityPlayerCreatedRosterReadService(repository, source);
    var account = new AuthenticatedAccountSelector(UUID.randomUUID());
    UUID characterId = UUID.randomUUID();
    var request = ReadPlayerCreatedRosterSourceRequest.getDefaultInstance();
    var sourceFailure = new IllegalStateException("controlled source read failure");
    when(source.read(request)).thenThrow(sourceFailure);

    assertThatThrownBy(() -> service.readSelectedTerminal(account, characterId, request))
        .isSameAs(sourceFailure);

    verify(source).read(request);
    verifyNoInteractions(repository);
  }

  @Test
  void selectedReadDispatchesExactAccountSourceAndCharacterToRepository() {
    var repository = mock(EntityCharacterCreateRepository.class);
    var source = mock(EntityPlayerCreatedRosterSourceClient.class);
    var service = new EntityPlayerCreatedRosterReadService(repository, source);
    var account = new AuthenticatedAccountSelector(UUID.randomUUID());
    UUID characterId = UUID.randomUUID();
    var published =
        new PublishedIdentity(
            "entity-test",
            UUID.randomUUID(),
            UUID.randomUUID(),
            "world",
            "realm",
            UUID.randomUUID(),
            UUID.randomUUID(),
            null);
    var terminal = EntityCharacterCreateTerminal.getDefaultInstance();
    when(repository.readSelectedPlayerCreatedTerminal(account, published, characterId))
        .thenReturn(terminal);

    // This internal wiring check supplies a value object directly; it makes no authentication
    // claim.
    assertThat(service.readSelectedTerminal(account, characterId, published)).isSameAs(terminal);

    verify(repository).readSelectedPlayerCreatedTerminal(account, published, characterId);
    verifyNoInteractions(source);
  }

  @Test
  void selectedReadRejectsNilCharacterBeforeCallingSource() {
    var repository = mock(EntityCharacterCreateRepository.class);
    var source = mock(EntityPlayerCreatedRosterSourceClient.class);
    var service = new EntityPlayerCreatedRosterReadService(repository, source);

    assertThatThrownBy(
            () ->
                service.readSelectedTerminal(
                    new AuthenticatedAccountSelector(UUID.randomUUID()),
                    new UUID(0L, 0L),
                    ReadPlayerCreatedRosterSourceRequest.getDefaultInstance()))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            failure ->
                assertThat(Status.fromThrowable(failure).getCode())
                    .isEqualTo(Status.Code.INVALID_ARGUMENT));

    verifyNoInteractions(source, repository);
  }
}
