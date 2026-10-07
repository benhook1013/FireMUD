package unit.net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceActivation;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceActivationRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceActivationService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class WorldCanonicalInstanceActivationServiceTest {
  private static final UUID ACTIVATION_ID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

  @Test
  void defaultVerifierDeniesNewOperationAfterReadOnlyLedgerMiss() {
    var repository = mock(WorldCanonicalInstanceActivationRepository.class);
    var request = request();
    when(repository.readResult(request)).thenReturn(Optional.empty());
    var service = new WorldCanonicalInstanceActivationService(repository);

    assertThatThrownBy(() -> service.activate(request))
        .isInstanceOf(WorldCanonicalInstanceActivationService.ActivationDeniedException.class)
        .hasMessageContaining("no authenticated current source/release");

    verify(repository).readResult(request);
    verify(repository, never()).activate(eq(request), any());
  }

  @Test
  void exactPriorResultReplaysWithoutConsultingCurrentAuthority() {
    var repository = mock(WorldCanonicalInstanceActivationRepository.class);
    var request = request();
    var result = mock(WorldCanonicalInstanceActivation.Result.class);
    when(repository.readResult(request)).thenReturn(Optional.of(result));
    var service =
        new WorldCanonicalInstanceActivationService(
            repository,
            ignored -> {
              throw new AssertionError("prior immutable result must bypass current authority");
            });

    assertThat(service.activate(request)).isSameAs(result);

    verify(repository, never()).activate(eq(request), any());
  }

  @Test
  void aNewOperationChecksAndHoldsAuthorityOutsideTheOwnerTransaction() {
    var repository = mock(WorldCanonicalInstanceActivationRepository.class);
    var request = request();
    var result = mock(WorldCanonicalInstanceActivation.Result.class);
    var closed = new java.util.concurrent.atomic.AtomicBoolean();
    var service =
        new WorldCanonicalInstanceActivationService(
            repository,
            ignored -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return new WorldCanonicalInstanceActivationService.HeldActivationAuthority() {
                public void requireHeld() {
                  assertThat(closed.get()).isFalse();
                }

                public void close() {
                  closed.set(true);
                }
              };
            });
    when(repository.readResult(request)).thenReturn(Optional.empty());
    when(repository.activate(eq(request), any())).thenReturn(result);

    assertThat(service.activate(request)).isSameAs(result);
    assertThat(closed.get()).isTrue();
    verify(repository).activate(eq(request), any());
  }

  private static WorldCanonicalInstanceActivation.Request request() {
    var lifecycleRequest = mock(WorldCanonicalInstanceLifecycleEvidence.Request.class);
    when(lifecycleRequest.canonicalGameInstanceId())
        .thenReturn(UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"));
    var preparing = mock(WorldCanonicalInstanceLifecycleEvidence.class);
    when(preparing.request()).thenReturn(lifecycleRequest);
    when(preparing.lifecycleStatus()).thenReturn("PREPARING");
    when(preparing.lifecycleEpoch()).thenReturn(1L);
    when(preparing.rowVersion()).thenReturn(0L);
    when(preparing.canonicalBytes())
        .thenReturn(
            ("{\"request\":{\"readRequestId\":\"11111111-1111-4111-8111-111111111111\"}}")
                .getBytes(StandardCharsets.UTF_8));
    return new WorldCanonicalInstanceActivation.Request(ACTIVATION_ID, preparing);
  }
}
