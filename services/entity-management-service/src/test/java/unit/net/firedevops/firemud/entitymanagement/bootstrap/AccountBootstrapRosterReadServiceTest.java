package unit.net.firedevops.firemud.entitymanagement.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.google.protobuf.UnknownFieldSet;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence;
import net.firedevops.firemud.entitymanagement.bootstrap.AccountBootstrapRosterActorProjection;
import net.firedevops.firemud.entitymanagement.bootstrap.AccountBootstrapRosterFixture;
import net.firedevops.firemud.entitymanagement.bootstrap.AccountBootstrapRosterReadContract;
import net.firedevops.firemud.entitymanagement.bootstrap.AccountBootstrapRosterReadService;
import net.firedevops.firemud.entitymanagement.client.GameSessionCanonicalGameplayRosterOwnerReadClient;
import net.firedevops.firemud.entitymanagement.repository.CharacterRepository;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterOwnerEvidencePort.OwnerEvidenceUnavailableException;
import net.firedevops.firemud.entitymanagement.service.impl.GameSessionCanonicalGameplayRosterOwnerEvidenceAdapter;
import net.firedevops.firemud.entitymanagement.v1.AccountBootstrapRosterActor;
import net.firedevops.firemud.entitymanagement.v1.AccountBootstrapRosterReadResponse;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class AccountBootstrapRosterReadServiceTest {
  @Test
  void realAdapterReadsCurrentSourceBeforeAndAfterShortLocalProjectionIncludingEmpty()
      throws Exception {
    var f = AccountBootstrapRosterFixture.create();
    var client = mock(GameSessionCanonicalGameplayRosterOwnerReadClient.class);
    var repository = mock(CharacterRepository.class);
    when(client.getCanonicalGameplayRosterOwnerRead(any()))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
              assertThat(
                      invocation.getArgument(
                          0, CanonicalGameplayRosterOwnerReadEvidence.Request.class))
                  .isEqualTo(f.evidence().request());
              return f.evidence();
            });
    UUID actor = UUID.randomUUID();
    when(repository.readAccountBootstrapRoster(any(), eq(f.target())))
        .thenAnswer(
            ignored -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
              return List.of(
                  new AccountBootstrapRosterActorProjection(actor, "Persisted Actor", 7));
            })
        .thenReturn(List.of());
    var service = service(client, repository);
    assertThat(service.read(f.request()).getActorsList())
        .containsExactly(
            AccountBootstrapRosterActor.newBuilder()
                .setCharacterUuid(actor.toString())
                .setDisplayName("Persisted Actor")
                .setLevel(7)
                .build());
    assertThat(service.read(f.request()).getActorsList()).isEmpty();
    verify(client, times(4)).getCanonicalGameplayRosterOwnerRead(f.evidence().request());
    verify(repository, times(2))
        .readAccountBootstrapRoster(
            UUID.fromString(f.request().getCanonicalAccountUuid()), f.target());
    verifyNoMoreInteractions(client, repository);
  }

  @Test
  void sourceChangedBeforeSqlOrDuringProjectionNeverReturnsEmptySuccess() throws Exception {
    var f = AccountBootstrapRosterFixture.create();
    var changed =
        new CanonicalGameplayRosterOwnerReadEvidence(
            f.evidence().request(),
            "f".repeat(64),
            f.evidence().gameSessionOwnerProof(),
            f.evidence().publishedPolicySetEvidence());
    var client = mock(GameSessionCanonicalGameplayRosterOwnerReadClient.class);
    var repository = mock(CharacterRepository.class);
    var service = service(client, repository);
    when(client.getCanonicalGameplayRosterOwnerRead(any())).thenReturn(changed);
    assertThatThrownBy(() -> service.read(f.request()))
        .isInstanceOf(AccountBootstrapRosterReadService.TargetChangedException.class);
    verifyNoInteractions(repository);
    when(client.getCanonicalGameplayRosterOwnerRead(any())).thenReturn(f.evidence(), changed);
    when(repository.readAccountBootstrapRoster(any(), any())).thenReturn(List.of());
    assertThatThrownBy(() -> service.read(f.request()))
        .isInstanceOf(AccountBootstrapRosterReadService.TargetChangedException.class);
    verify(repository).readAccountBootstrapRoster(any(), any());
  }

  @Test
  void unavailableOrWrongAccountSourceCannotBeAnEmptyRoster() throws Exception {
    var f = AccountBootstrapRosterFixture.create();
    var client = mock(GameSessionCanonicalGameplayRosterOwnerReadClient.class);
    var repository = mock(CharacterRepository.class);
    var service = service(client, repository);
    when(client.getCanonicalGameplayRosterOwnerRead(any()))
        .thenThrow(new IllegalStateException("offline"));
    assertThatThrownBy(() -> service.read(f.request()))
        .isInstanceOf(OwnerEvidenceUnavailableException.class);
    org.mockito.Mockito.doReturn(f.evidence())
        .when(client)
        .getCanonicalGameplayRosterOwnerRead(any());
    var changedAccount =
        f.request().toBuilder().setCanonicalAccountUuid(UUID.randomUUID().toString()).build();
    assertThatThrownBy(() -> service.read(changedAccount))
        .isInstanceOf(OwnerEvidenceUnavailableException.class);
    verifyNoInteractions(repository);
  }

  @Test
  void malformedUnsupportedAndAmbientTransactionRefuseBeforeAnySourceOrSql() throws Exception {
    var f = AccountBootstrapRosterFixture.create();
    var client = mock(GameSessionCanonicalGameplayRosterOwnerReadClient.class);
    var repository = mock(CharacterRepository.class);
    var service = service(client, repository);
    assertThatThrownBy(
            () -> service.read(f.request().toBuilder().setCanonicalAccountUuid("12").build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                service.read(
                    f.request().toBuilder()
                        .setExpectedTarget(
                            f.request().getExpectedTarget().toBuilder()
                                .setPlayableStateScope(
                                    PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED))
                        .build()))
        .isInstanceOf(UnsupportedOperationException.class);
    var unknown =
        UnknownFieldSet.newBuilder()
            .addField(100, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
            .build();
    assertThatThrownBy(
            () -> service.read(f.request().toBuilder().setUnknownFields(unknown).build()))
        .isInstanceOf(IllegalArgumentException.class);
    new TransactionTemplate(new LocalTransactions())
        .executeWithoutResult(
            ignored -> {
              assertThatThrownBy(() -> service.read(f.request()))
                  .isInstanceOf(AccountBootstrapRosterReadService.TargetChangedException.class);
            });
    verifyNoInteractions(client, repository);
  }

  @Test
  void exactEchoClosedActorBoundsCanonicalIdsAndDeterministicOrderAreRequired() throws Exception {
    var f = AccountBootstrapRosterFixture.create();
    var first =
        AccountBootstrapRosterActor.newBuilder()
            .setCharacterUuid("11111111-1111-4111-8111-111111111111")
            .setDisplayName("First")
            .setLevel(0)
            .build();
    var second = first.toBuilder().setCharacterUuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa").build();
    var valid =
        AccountBootstrapRosterReadResponse.newBuilder()
            .setRequest(f.request())
            .addActors(first)
            .addActors(second)
            .build();
    assertThat(AccountBootstrapRosterReadContract.requireResponse(f.request(), valid))
        .isEqualTo(valid);
    for (var invalid :
        List.of(
            valid.toBuilder()
                .setRequest(f.request().toBuilder().setRequestUuid(UUID.randomUUID().toString()))
                .build(),
            valid.toBuilder().addActors(second).build(),
            valid.toBuilder().clearActors().addActors(second).addActors(first).build(),
            valid.toBuilder()
                .clearActors()
                .addActors(first.toBuilder().setCharacterUuid("1"))
                .build(),
            valid.toBuilder()
                .clearActors()
                .addActors(first.toBuilder().setDisplayName(" First"))
                .build(),
            valid.toBuilder().clearActors().addActors(first.toBuilder().clearLevel()).build(),
            valid.toBuilder().clearActors().addActors(first.toBuilder().setLevel(-1)).build(),
            valid.toBuilder().addAllActors(java.util.Collections.nCopies(100, second)).build())) {
      assertThatThrownBy(
              () -> AccountBootstrapRosterReadContract.requireResponse(f.request(), invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  private static AccountBootstrapRosterReadService service(
      GameSessionCanonicalGameplayRosterOwnerReadClient client, CharacterRepository repository) {
    return new AccountBootstrapRosterReadService(
        new GameSessionCanonicalGameplayRosterOwnerEvidenceAdapter(
            client, AccountBootstrapRosterFixture.NAMESPACE),
        repository,
        new LocalTransactions());
  }

  private static final class LocalTransactions extends AbstractPlatformTransactionManager {
    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object tx, TransactionDefinition definition) {}

    @Override
    protected void doCommit(DefaultTransactionStatus status) {}

    @Override
    protected void doRollback(DefaultTransactionStatus status) {}
  }
}
