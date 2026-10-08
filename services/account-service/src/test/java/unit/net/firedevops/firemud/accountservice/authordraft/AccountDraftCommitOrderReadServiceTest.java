package unit.net.firedevops.firemud.accountservice.authordraft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.authordraft.AccountDraftCommitOrderReadService;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository.FenceSnapshot;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository.Ordering;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository.Settlement;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.authoring.DraftCommitOrderReadEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountDraftCommitOrderReadServiceTest {
  private final DraftAuthorizationFenceRepository repository =
      mock(DraftAuthorizationFenceRepository.class);
  private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
  private final TransactionStatus transactionStatus = mock(TransactionStatus.class);
  private final AccountDraftCommitOrderReadService service =
      new AccountDraftCommitOrderReadService(repository, manager, "test");

  @AfterEach
  void clearTransaction() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void acceptsOnlyExactHeldOriginalCommitOrderInOwnedWritableReadCommittedTransaction() {
    var binding = binding(1);
    var request = request(binding);
    when(manager.getTransaction(any())).thenReturn(transactionStatus);
    when(repository.readOriginalBinding(binding.operationId())).thenReturn(Optional.of(binding));
    when(repository.read(binding))
        .thenReturn(new FenceSnapshot(Ordering.COMMIT_ORDER, binding.canonicalBytes(), null, null));
    when(repository.readSettlement(binding)).thenReturn(Settlement.PENDING);

    asWorld(() -> service.requireHeld(request));

    var definition = ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(manager).getTransaction(definition.capture());
    assertThat(definition.getValue().getPropagationBehavior())
        .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    assertThat(definition.getValue().getIsolationLevel())
        .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
    assertThat(definition.getValue().isReadOnly()).isFalse();
    verify(manager).commit(transactionStatus);
    verify(repository).readOriginalBinding(binding.operationId());
    verify(repository).read(binding);
    verify(repository).readSettlement(binding);
  }

  @Test
  void deniesAbsentChangedReservedRevokedAndSettledOperationsWithoutChangingOwnerState() {
    var original = binding(1);
    var changed = binding(2);
    var absentRepository = mock(DraftAuthorizationFenceRepository.class);
    var absentManager = mock(PlatformTransactionManager.class);
    when(absentManager.getTransaction(any())).thenReturn(transactionStatus);
    when(absentRepository.readOriginalBinding(original.operationId())).thenReturn(Optional.empty());
    assertDenied(
        new AccountDraftCommitOrderReadService(absentRepository, absentManager, "test"),
        request(original));
    verify(absentRepository, never()).read(any());

    var changedRepository = mock(DraftAuthorizationFenceRepository.class);
    var changedManager = mock(PlatformTransactionManager.class);
    when(changedManager.getTransaction(any())).thenReturn(transactionStatus);
    when(changedRepository.readOriginalBinding(changed.operationId()))
        .thenReturn(Optional.of(original));
    assertDenied(
        new AccountDraftCommitOrderReadService(changedRepository, changedManager, "test"),
        request(changed));
    verify(changedRepository, never()).read(any());

    for (Ordering ordering : List.of(Ordering.RESERVED, Ordering.REVOKE_ORDER)) {
      var fencedRepository = mock(DraftAuthorizationFenceRepository.class);
      var fencedManager = mock(PlatformTransactionManager.class);
      when(fencedManager.getTransaction(any())).thenReturn(transactionStatus);
      when(fencedRepository.readOriginalBinding(original.operationId()))
          .thenReturn(Optional.of(original));
      when(fencedRepository.read(original))
          .thenReturn(
              new FenceSnapshot(ordering, original.canonicalBytes(), null, OffsetDateTime.now()));
      assertDenied(
          new AccountDraftCommitOrderReadService(fencedRepository, fencedManager, "test"),
          request(original));
      verify(fencedRepository, never()).readSettlement(original);
    }

    for (Settlement settlement : List.of(Settlement.COMMITTED, Settlement.FAILED_NONPUBLICATION)) {
      var settledRepository = mock(DraftAuthorizationFenceRepository.class);
      var settledManager = mock(PlatformTransactionManager.class);
      when(settledManager.getTransaction(any())).thenReturn(transactionStatus);
      when(settledRepository.readOriginalBinding(original.operationId()))
          .thenReturn(Optional.of(original));
      when(settledRepository.read(original))
          .thenReturn(
              new FenceSnapshot(
                  Ordering.COMMIT_ORDER, original.canonicalBytes(), null, OffsetDateTime.now()));
      when(settledRepository.readSettlement(original)).thenReturn(settlement);
      assertDenied(
          new AccountDraftCommitOrderReadService(settledRepository, settledManager, "test"),
          request(original));
    }

    verifyNoInteractions(repository);
  }

  @Test
  void rejectsMissingWrongWorkloadAndAmbientTransactionBeforeRepositoryOrOwnerTransaction() {
    var binding = binding(1);
    var request = request(binding);
    assertStatus(Status.Code.UNAUTHENTICATED, () -> service.requireHeld(request));
    assertStatus(
        Status.Code.PERMISSION_DENIED,
        () ->
            asPeer(
                "spiffe://firemud/ns/test/sa/account-service", () -> service.requireHeld(request)));
    assertStatus(
        Status.Code.PERMISSION_DENIED,
        () ->
            asPeer(
                "spiffe://firemud/ns/other/sa/world-management-service",
                () -> service.requireHeld(request)));

    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertStatus(
        Status.Code.FAILED_PRECONDITION, () -> asWorld(() -> service.requireHeld(request)));
    verifyNoInteractions(repository, manager);
  }

  @Test
  void rejectsTargetNamespaceSubstitutionAndUnavailableStorageFailClosed() {
    var binding = binding(1);
    var wrongNamespace =
        new DraftCommitOrderReadEvidence.Request(
            1,
            "other",
            UUID.fromString("33333333-3333-4333-8333-333333333333"),
            binding.canonicalBytes());
    assertStatus(
        Status.Code.PERMISSION_DENIED, () -> asWorld(() -> service.requireHeld(wrongNamespace)));
    verifyNoInteractions(repository, manager);

    when(manager.getTransaction(any())).thenReturn(transactionStatus);
    when(repository.readOriginalBinding(binding.operationId()))
        .thenThrow(new org.jooq.exception.DataAccessException("offline"));
    assertStatus(
        Status.Code.UNAVAILABLE, () -> asWorld(() -> service.requireHeld(request(binding))));
  }

  private static void assertDenied(
      AccountDraftCommitOrderReadService target, DraftCommitOrderReadEvidence.Request request) {
    assertStatus(Status.Code.FAILED_PRECONDITION, () -> asWorld(() -> target.requireHeld(request)));
  }

  private static void assertStatus(
      Status.Code expected, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOf(StatusRuntimeException.class)
        .extracting(failure -> ((StatusRuntimeException) failure).getStatus().getCode())
        .isEqualTo(expected);
  }

  private static void asWorld(Runnable call) {
    asPeer("spiffe://firemud/ns/test/sa/world-management-service", call);
  }

  private static void asPeer(String uri, Runnable call) {
    var peer = GrpcPeerIdentity.parseUri(uri).orElseThrow();
    var context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    var previous = context.attach();
    try {
      call.run();
    } finally {
      context.detach(previous);
    }
  }

  static DraftCommitOrderReadEvidence.Request request(DraftAuthorizationFenceBinding binding) {
    return new DraftCommitOrderReadEvidence.Request(
        1,
        "test",
        UUID.fromString("33333333-3333-4333-8333-333333333333"),
        binding.canonicalBytes());
  }

  static DraftAuthorizationFenceBinding binding(long sourceGeneration) {
    UUID tenant = uuid("11111111-1111-4111-8111-111111111111");
    UUID version = uuid("22222222-2222-4222-8222-222222222222");
    UUID request = uuid("44444444-4444-4444-8444-444444444444");
    UUID commit = uuid("55555555-5555-4555-8555-555555555555");
    DraftCommitBinding draft =
        DraftCommitBinding.create(
            new TargetProof(tenant, version, 19L, "tenant-key", 42L, "tenant-key", "NEW_GAME_ROW"),
            request,
            commit,
            "base-1",
            List.of(
                new RevisionPayload(
                    "0",
                    uuid("66666666-6666-4666-8666-666666666666"),
                    DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                    "{}")),
            List.of(
                new AffectedUnit(
                    DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                    "WORLD_TEMPLATE",
                    "world-1",
                    "ROOM_SCOPE",
                    "room-1",
                    "0")));
    byte[] draftBytes = draft.canonicalBytes();
    return new DraftAuthorizationFenceBinding(
        uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
        request,
        commit,
        uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
        uuid("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
        tenant,
        version,
        "base-1",
        "0",
        draftBytes,
        draftBytes,
        draft.digest(),
        List.of(
            new SourceEvidence(
                SourceKind.GLOBAL_ROLES,
                "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
                null,
                Long.toString(sourceGeneration),
                null,
                null,
                new byte[] {1})));
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
