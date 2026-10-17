package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService.CapturedEnvironmentBoundary;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSourceReadScope;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Synthetic owner producer tests; these do not establish TLS or PostgreSQL runtime proof. */
class AccountSelectedOwnerIntakeSourceReservationServiceTest {
  private static final String TOKEN = "header.payload.signature";
  private final AccountControlUiActorService actors = mock(AccountControlUiActorService.class);
  private final DraftAuthorizationFenceRepository fences =
      mock(DraftAuthorizationFenceRepository.class);
  private final AccountSelectedOwnerIntakeSourceReservationRepository repository =
      mock(AccountSelectedOwnerIntakeSourceReservationRepository.class);
  private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
  private final AccountSelectedOwnerIntakeSourceReservationService service =
      new AccountSelectedOwnerIntakeSourceReservationService(
          actors, fences, repository, transactions, "test");

  @AfterEach
  void clearContextAndTransaction() {
    SessionContext.clear();
    TransactionSynchronizationManager.clear();
  }

  @Test
  void freshProducerRunsCurrentActorThenSelectedAdmissionBeforePersisting() {
    var selected = selected();
    assertThat(selected.requiredOwners())
        .doesNotContain(DraftCommitBinding.Owner.ENTITY_MANAGEMENT)
        .doesNotContain(DraftCommitBinding.Owner.AUTOMATION_SCRIPTING);
    UUID actorId = UUID.fromString("44444444-4444-4444-8444-444444444444");
    var currentSource = source(actorId);
    var actorCurrent =
        new AccountControlUiActorService.Current(
            stored(actorId, selected.target().canonicalTenantId()),
            snapshot(List.of(currentSource)));
    var environment = mock(CapturedEnvironmentBoundary.class);
    var scope =
        new SelectedOwnerIntakeSourceReadScope(
            DraftCommitBinding.Owner.ENTITY_MANAGEMENT,
            "test",
            UUID.fromString("55555555-5555-4555-8555-555555555555"),
            UUID.fromString("66666666-6666-4666-8666-666666666666"),
            selected.requestId(),
            actorId,
            selected);
    var status = new SimpleTransactionStatus();
    when(transactions.getTransaction(any(TransactionDefinition.class))).thenReturn(status);
    when(repository.findSourceReadScope(
            selected.requestId(),
            DraftCommitBinding.Owner.ENTITY_MANAGEMENT,
            selected,
            "test",
            tokenHash(TOKEN)))
        .thenReturn(Optional.empty());
    when(repository.reserveSourceRead(
            eq(selected.requestId()),
            eq(DraftCommitBinding.Owner.ENTITY_MANAGEMENT),
            eq(selected),
            eq(actorCurrent),
            eq("test"),
            any(Runnable.class)))
        .thenReturn(scope);
    doAnswer(
            invocation -> {
              Function<AccountControlUiActorService.Current, Object> action =
                  invocation.getArgument(3);
              return action.apply(actorCurrent);
            })
        .when(actors)
        .withCurrent(eq(TOKEN), eq(selected.target().canonicalTenantId()), eq(environment), any());

    var result =
        asPeerResult(
            "test",
            "game-design-service",
            () ->
                service.reserveSourceRead(
                    TOKEN,
                    selected.requestId(),
                    DraftCommitBinding.Owner.ENTITY_MANAGEMENT,
                    selected,
                    environment));

    assertThat(result).isEqualTo(scope);
    InOrder order = inOrder(repository, actors, fences);
    order
        .verify(repository)
        .findSourceReadScope(
            selected.requestId(),
            DraftCommitBinding.Owner.ENTITY_MANAGEMENT,
            selected,
            "test",
            tokenHash(TOKEN));
    order
        .verify(actors)
        .withCurrent(eq(TOKEN), eq(selected.target().canonicalTenantId()), eq(environment), any());
    order.verify(fences).requireSelectedOwnerIntakeAdmission(List.of(currentSource), selected);
    order
        .verify(repository)
        .reserveSourceRead(
            eq(selected.requestId()),
            eq(DraftCommitBinding.Owner.ENTITY_MANAGEMENT),
            eq(selected),
            eq(actorCurrent),
            eq("test"),
            any(Runnable.class));
  }

  @Test
  void historicalRecoveryAndAbortUseTheExactOriginalCredentialAndDoNotRenewIt() {
    var selected = selected();
    var scope =
        new SelectedOwnerIntakeSourceReadScope(
            DraftCommitBinding.Owner.AUTOMATION_SCRIPTING,
            "test",
            UUID.fromString("77777777-7777-4777-8777-777777777777"),
            UUID.fromString("88888888-8888-4888-8888-888888888888"),
            selected.requestId(),
            UUID.fromString("99999999-9999-4999-8999-999999999999"),
            selected);
    var status = new SimpleTransactionStatus();
    when(transactions.getTransaction(any(TransactionDefinition.class))).thenReturn(status);
    when(repository.findSourceReadScope(
            selected.requestId(),
            DraftCommitBinding.Owner.AUTOMATION_SCRIPTING,
            selected,
            "test",
            tokenHash(TOKEN)))
        .thenReturn(Optional.of(scope));
    when(repository.recoverSourceRead(scope))
        .thenReturn(
            new AccountSelectedOwnerIntakeSourceReservationRepository.Recovery(
                scope, AccountSelectedOwnerIntakeSourceReservationRepository.State.RESERVED));
    when(repository.abortSourceRead(scope))
        .thenReturn(
            new AccountSelectedOwnerIntakeSourceReservationRepository.Recovery(
                scope, AccountSelectedOwnerIntakeSourceReservationRepository.State.ABORTED));

    var recovered =
        asPeerResult(
            "test",
            "game-design-service",
            () ->
                service.recover(
                    TOKEN,
                    selected.requestId(),
                    DraftCommitBinding.Owner.AUTOMATION_SCRIPTING,
                    selected));
    var aborted =
        asPeerResult(
            "test",
            "game-design-service",
            () ->
                service.abortSourceRead(
                    TOKEN,
                    selected.requestId(),
                    DraftCommitBinding.Owner.AUTOMATION_SCRIPTING,
                    selected));

    assertThat(recovered.state())
        .isEqualTo(AccountSelectedOwnerIntakeSourceReservationRepository.State.RESERVED);
    assertThat(aborted.state())
        .isEqualTo(AccountSelectedOwnerIntakeSourceReservationRepository.State.ABORTED);
    InOrder order = inOrder(repository);
    order
        .verify(repository)
        .findSourceReadScope(
            selected.requestId(),
            DraftCommitBinding.Owner.AUTOMATION_SCRIPTING,
            selected,
            "test",
            tokenHash(TOKEN));
    order.verify(repository).recoverSourceRead(scope);
    order
        .verify(repository)
        .findSourceReadScope(
            selected.requestId(),
            DraftCommitBinding.Owner.AUTOMATION_SCRIPTING,
            selected,
            "test",
            tokenHash(TOKEN));
    order.verify(repository).abortSourceRead(scope);
  }

  @Test
  void exactSameNamespaceGameDesignPeerIsRequiredBeforeInputsOrStorage() {
    assertCode(
        Status.Code.UNAUTHENTICATED, () -> service.reserveSourceRead(null, null, null, null, null));
    assertCode(
        Status.Code.PERMISSION_DENIED,
        () ->
            asPeer(
                "other",
                "game-design-service",
                () -> service.reserveSourceRead(null, null, null, null, null)));
    assertCode(
        Status.Code.PERMISSION_DENIED,
        () ->
            asPeer(
                "test",
                "account-service",
                () -> service.reserveSourceRead(null, null, null, null, null)));

    SessionContext.setContext("44", List.of(), Map.of());
    try {
      assertCode(
          Status.Code.PERMISSION_DENIED,
          () ->
              asPeer(
                  "test",
                  "game-design-service",
                  () -> service.reserveSourceRead(null, null, null, null, null)));
    } finally {
      SessionContext.clear();
    }
    verifyNoInteractions(actors, fences, repository, transactions);
  }

  @Test
  void originalCreatorRecoveryUsesAnIndependentReadCommittedTransaction() {
    var selected = selected();
    var scope =
        new SelectedOwnerIntakeSourceReadScope(
            DraftCommitBinding.Owner.ENTITY_MANAGEMENT,
            "test",
            UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
            UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
            selected.requestId(),
            UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
            selected);
    var status = new SimpleTransactionStatus();
    when(transactions.getTransaction(any(TransactionDefinition.class))).thenReturn(status);
    when(repository.recoverSourceRead(scope))
        .thenReturn(
            new AccountSelectedOwnerIntakeSourceReservationRepository.Recovery(
                scope, AccountSelectedOwnerIntakeSourceReservationRepository.State.RESERVED));

    asPeer("test", "game-design-service", () -> service.recover(scope));

    var definition = org.mockito.ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactions).getTransaction(definition.capture());
    assertThat(definition.getValue().getPropagationBehavior())
        .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    assertThat(definition.getValue().getIsolationLevel())
        .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
    assertThat(definition.getValue().isReadOnly()).isFalse();
    verify(repository).recoverSourceRead(scope);
  }

  private static AccountControlUiAuthority.Snapshot snapshot(
      List<DraftAuthorizationFenceBinding.SourceEvidence> sources) {
    var snapshot = mock(AccountControlUiAuthority.Snapshot.class);
    when(snapshot.sources()).thenReturn(sources);
    when(snapshot.issuanceFence()).thenReturn(17L);
    when(snapshot.outboxCheckpoints())
        .thenReturn(List.of(Map.of("stream", "account", "sequence", 7)));
    return snapshot;
  }

  private static AccountControlUiIssuanceRepository.Stored stored(UUID actorId, UUID tenantId) {
    Record row = mock(Record.class);
    var operation = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd");
    var sourcePayload = AccountControlUiAuthority.canonical(Map.of("sources", List.of("AQID")));
    var bundle =
        AccountControlUiAuthority.canonical(
            Map.of("issuanceFence", 17, "outboxCheckpoints", List.of()));
    when(row.get("request_id", UUID.class)).thenReturn(UUID.randomUUID());
    when(row.get("operation_id", UUID.class)).thenReturn(operation);
    when(row.get("token_jti", UUID.class)).thenReturn(UUID.randomUUID());
    when(row.get("account_uuid", UUID.class)).thenReturn(actorId);
    when(row.get("tenant_uuid", UUID.class)).thenReturn(tenantId);
    when(row.get("caller_context_id", UUID.class)).thenReturn(UUID.randomUUID());
    when(row.get("caller_workload", String.class)).thenReturn("game-design-service");
    when(row.get("request_mac_key_id", String.class)).thenReturn("key");
    when(row.get("request_digest", String.class)).thenReturn("digest");
    when(row.get("status", String.class)).thenReturn("COMMITTED");
    when(row.get("token_hash", String.class)).thenReturn(tokenHash(TOKEN));
    when(row.get("claims_payload", byte[].class)).thenReturn(new byte[] {1});
    when(row.get("source_payload", byte[].class)).thenReturn(sourcePayload);
    when(row.get("bundle_payload", byte[].class)).thenReturn(bundle);
    when(row.get("signer_receipt", byte[].class)).thenReturn(new byte[] {2});
    when(row.get("pending_registry", byte[].class)).thenReturn(new byte[] {3});
    when(row.get("active_registry", byte[].class)).thenReturn(new byte[] {4});
    when(row.get("issued_at_epoch_second", Long.class)).thenReturn(1_800_000_000L);
    when(row.get("expires_at_epoch_second", Long.class)).thenReturn(1_800_000_300L);
    when(row.get("recovery_expires_at", OffsetDateTime.class))
        .thenReturn(OffsetDateTime.parse("2027-01-01T00:01:00Z"));
    return new AccountControlUiIssuanceRepository.Stored(row);
  }

  private static DraftAuthorizationFenceBinding.SourceEvidence source(UUID actorId) {
    return new DraftAuthorizationFenceBinding.SourceEvidence(
        DraftAuthorizationFenceBinding.SourceKind.ACCOUNT,
        actorId.toString(),
        "1",
        "1",
        null,
        null,
        new byte[] {1, 2, 3});
  }

  private static DraftCommitBinding selected() {
    UUID tenant = UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee");
    UUID version = UUID.fromString("ffffffff-ffff-4fff-8fff-ffffffffffff");
    return DraftCommitBinding.create(
        new DraftCommitBinding.TargetProof(
            tenant, version, 1L, "tenant-key", 2L, "tenant-key", "NEW_GAME_ROW"),
        UUID.fromString("11111111-1111-4111-8111-111111111111"),
        UUID.fromString("22222222-2222-4222-8222-222222222222"),
        "base",
        List.of(
            new DraftCommitBinding.RevisionPayload(
                "0",
                UUID.fromString("33333333-3333-4333-8333-333333333333"),
                DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                "{}")),
        List.of(
            new DraftCommitBinding.AffectedUnit(
                DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                "TEMPLATE_CONFIG",
                "templates",
                "TENANT",
                tenant.toString(),
                "0")));
  }

  private static String tokenHash(String value) {
    return AccountControlUiIssuanceRepository.hash(value.getBytes(StandardCharsets.US_ASCII));
  }

  private static void assertCode(Status.Code code, Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(failure -> assertThat(Status.fromThrowable(failure).getCode()).isEqualTo(code));
  }

  private static void asPeer(String namespace, String workload, Runnable action) {
    var peer =
        Context.current()
            .withValue(
                GrpcPeerIdentity.CONTEXT_KEY,
                GrpcPeerIdentity.parseUri("spiffe://firemud/ns/" + namespace + "/sa/" + workload)
                    .orElseThrow());
    var previous = peer.attach();
    try {
      action.run();
    } finally {
      peer.detach(previous);
    }
  }

  private static <T> T asPeerResult(String namespace, String workload, Supplier<T> action) {
    var peer =
        Context.current()
            .withValue(
                GrpcPeerIdentity.CONTEXT_KEY,
                GrpcPeerIdentity.parseUri("spiffe://firemud/ns/" + namespace + "/sa/" + workload)
                    .orElseThrow());
    var previous = peer.attach();
    try {
      return action.get();
    } finally {
      peer.detach(previous);
    }
  }
}
