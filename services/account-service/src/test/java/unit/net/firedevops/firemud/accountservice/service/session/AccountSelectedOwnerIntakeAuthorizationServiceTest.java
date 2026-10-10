package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService.CapturedEnvironmentBoundary;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationBinding;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSourceReadEvidence;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSourceReadScope;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.gamedesign.AssetSnapshot;
import net.firedevops.firemud.common.gamedesign.BrandingSourceSnapshot;
import net.firedevops.firemud.common.gamedesign.CommandSnapshot;
import net.firedevops.firemud.common.gamedesign.CommandSource;
import net.firedevops.firemud.common.gamedesign.RealmPolicySnapshot;
import net.firedevops.firemud.common.gamedesign.SelectedOwnerIntakeSourceClient;
import net.firedevops.firemud.common.gamedesign.SelectedOwnerIntakeSourceContent;
import net.firedevops.firemud.common.gamedesign.TemplateConfigSourceSnapshot;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSelectedSource;
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

/** Synthetic Account composition tests; they do not establish physical or cross-owner proof. */
class AccountSelectedOwnerIntakeAuthorizationServiceTest {
  private static final String TOKEN = "header.payload.signature";
  private static final UUID ACTOR = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID TENANT = UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee");
  private static final UUID VERSION = UUID.fromString("ffffffff-ffff-4fff-8fff-ffffffffffff");
  private static final UUID OPERATION = UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final UUID FENCE = UUID.fromString("66666666-6666-4666-8666-666666666666");

  private final AccountControlUiActorService actors = mock(AccountControlUiActorService.class);
  private final DraftAuthorizationFenceRepository fences =
      mock(DraftAuthorizationFenceRepository.class);
  private final AccountSelectedOwnerIntakeSourceReservationRepository repository =
      mock(AccountSelectedOwnerIntakeSourceReservationRepository.class);
  private final SelectedOwnerIntakeSourceClient sources =
      mock(SelectedOwnerIntakeSourceClient.class);
  private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
  private final AccountSelectedOwnerIntakeAuthorizationService service =
      new AccountSelectedOwnerIntakeAuthorizationService(
          actors, fences, repository, sources, transactions, "test");

  @AfterEach
  void clearContextAndTransaction() {
    SessionContext.clear();
    TransactionSynchronizationManager.clear();
  }

  @Test
  void freshAuthorizationReadsOutsideSqlThenRevalidatesAndReadsBackExactOriginalIdentity() {
    var selected = selected();
    var scope = scope(Owner.ENTITY_MANAGEMENT, selected, OPERATION, FENCE);
    var content = content(scope, "original");
    var current = current(ACTOR, TENANT);
    var binding = new SelectedOwnerIntakeAuthorizationBinding(content, current.source().sources());
    var firstEnvironment = mock(CapturedEnvironmentBoundary.class);
    var finalEnvironment = mock(CapturedEnvironmentBoundary.class);
    var captures = new AtomicInteger();
    var request =
        org.mockito.ArgumentCaptor.forClass(SelectedOwnerIntakeSourceReadEvidence.Request.class);
    stubTransactions();
    when(repository.findSourceReadScope(
            selected.requestId(), Owner.ENTITY_MANAGEMENT, selected, "test", tokenHash(TOKEN)))
        .thenReturn(Optional.empty());
    when(repository.reserveSourceRead(
            eq(selected.requestId()),
            eq(Owner.ENTITY_MANAGEMENT),
            eq(selected),
            eq(current),
            eq("test"),
            any(Runnable.class)))
        .thenAnswer(
            invocation -> {
              invocation.<Runnable>getArgument(5).run();
              return scope;
            });
    when(repository.recoverSourceRead(scope))
        .thenReturn(
            recovery(scope, AccountSelectedOwnerIntakeSourceReservationRepository.State.RESERVED));
    when(repository.finalizeSourceRead(
            eq(scope),
            argThat(candidate -> sameContent(candidate, content)),
            eq(current),
            any(Runnable.class)))
        .thenAnswer(
            invocation -> {
              invocation.<Runnable>getArgument(3).run();
              return binding;
            });
    doAnswer(
            invocation -> {
              Function<AccountControlUiActorService.Current, ?> action = invocation.getArgument(3);
              return action.apply(current);
            })
        .when(actors)
        .withCurrent(
            eq(TOKEN), eq(TENANT), any(CapturedEnvironmentBoundary.class), any(Function.class));
    doAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
              return content;
            })
        .when(sources)
        .read(request.capture());

    var result =
        asPeerResult(
            "test",
            "game-design-service",
            () ->
                service.authorizeWithEnvironmentCapture(
                    TOKEN,
                    selected.requestId(),
                    Owner.ENTITY_MANAGEMENT,
                    selected,
                    () -> captures.incrementAndGet() == 1 ? firstEnvironment : finalEnvironment));

    assertThat(result.canonicalBytes()).isEqualTo(binding.canonicalBytes());
    assertThat(captures.get()).isEqualTo(2);
    assertThat(request.getValue().scope()).isEqualTo(scope);
    assertThat(result.operationId()).isEqualTo(scope.operationId());
    assertThat(result.fenceId()).isEqualTo(scope.fenceId());
    assertThat(result.intakeRequestId()).isEqualTo(selected.requestId());
    verify(repository).readFinalAuthorization(binding);
    verify(fences, org.mockito.Mockito.times(2))
        .requireSelectedOwnerIntakeAdmission(current.source().sources(), selected);
    InOrder order = inOrder(actors, repository, sources);
    order
        .verify(repository)
        .findSourceReadScope(
            selected.requestId(), Owner.ENTITY_MANAGEMENT, selected, "test", tokenHash(TOKEN));
    order.verify(actors).withCurrent(eq(TOKEN), eq(TENANT), eq(firstEnvironment), any());
    order
        .verify(repository)
        .reserveSourceRead(
            eq(selected.requestId()),
            eq(Owner.ENTITY_MANAGEMENT),
            eq(selected),
            eq(current),
            eq("test"),
            any(Runnable.class));
    order.verify(repository).recoverSourceRead(scope);
    order.verify(sources).read(request.getValue());
    order.verify(actors).withCurrent(eq(TOKEN), eq(TENANT), eq(finalEnvironment), any());
    order
        .verify(repository)
        .finalizeSourceRead(
            eq(scope),
            argThat(candidate -> sameContent(candidate, content)),
            eq(current),
            any(Runnable.class));
    order.verify(repository).readFinalAuthorization(binding);
  }

  @Test
  void exactFinalRetryUsesOriginalBindingBeforeEnvironmentOrRemoteSourceRead() {
    var selected = selected();
    var scope = scope(Owner.AUTOMATION_SCRIPTING, selected, OPERATION, FENCE);
    var content = content(scope, "retained");
    var binding = new SelectedOwnerIntakeAuthorizationBinding(content, sources(ACTOR));
    stubTransactions();
    when(repository.findSourceReadScope(
            selected.requestId(), Owner.AUTOMATION_SCRIPTING, selected, "test", tokenHash(TOKEN)))
        .thenReturn(Optional.of(scope));
    when(repository.recoverSourceRead(scope))
        .thenReturn(
            recovery(scope, AccountSelectedOwnerIntakeSourceReservationRepository.State.FINALIZED));
    when(repository.findFinalAuthorization(scope)).thenReturn(Optional.of(binding));
    AtomicInteger environmentReads = new AtomicInteger();

    var result =
        asPeerResult(
            "test",
            "game-design-service",
            () ->
                service.authorizeWithEnvironmentCapture(
                    TOKEN,
                    selected.requestId(),
                    Owner.AUTOMATION_SCRIPTING,
                    selected,
                    () -> {
                      environmentReads.incrementAndGet();
                      throw new AssertionError(
                          "exact finalized retry must not capture environment");
                    }));

    assertThat(result.canonicalBytes()).isEqualTo(binding.canonicalBytes());
    assertThat(result.operationId()).isEqualTo(OPERATION);
    assertThat(result.fenceId()).isEqualTo(FENCE);
    assertThat(result.intakeRequestId()).isEqualTo(selected.requestId());
    assertThat(environmentReads.get()).isZero();
    verify(repository).readFinalAuthorization(binding);
    verifyNoInteractions(actors, fences, sources);
  }

  @Test
  void substitutedSelectedContentDeniesWithoutReleasingThePreliminaryReservation() {
    var selected = selected();
    var scope = scope(Owner.ENTITY_MANAGEMENT, selected, OPERATION, FENCE);
    var substitutedScope =
        scope(
            Owner.ENTITY_MANAGEMENT,
            selected,
            UUID.fromString("77777777-7777-4777-8777-777777777777"),
            FENCE);
    var current = current(ACTOR, TENANT);
    var captures = new AtomicInteger();
    var environment = mock(CapturedEnvironmentBoundary.class);
    stubFreshReservation(selected, scope, current);
    when(sources.read(any(SelectedOwnerIntakeSourceReadEvidence.Request.class)))
        .thenReturn(content(substitutedScope, "substituted"));

    assertThatThrownBy(
            () ->
                asPeerResult(
                    "test",
                    "game-design-service",
                    () ->
                        service.authorizeWithEnvironmentCapture(
                            TOKEN,
                            selected.requestId(),
                            Owner.ENTITY_MANAGEMENT,
                            selected,
                            () -> {
                              captures.incrementAndGet();
                              return environment;
                            })))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            failure ->
                assertThat(Status.fromThrowable(failure).getCode())
                    .isEqualTo(Status.Code.FAILED_PRECONDITION));

    assertThat(captures.get()).isEqualTo(1);
    verify(repository)
        .reserveSourceRead(
            eq(selected.requestId()),
            eq(Owner.ENTITY_MANAGEMENT),
            eq(selected),
            eq(current),
            eq("test"),
            any(Runnable.class));
    verify(repository, never()).finalizeSourceRead(any(), any(), any(), any());
    verify(repository, never()).abortSourceRead(any());
  }

  @Test
  void changedCurrentCreatorDuringPostReadCaptureCannotFinalize() {
    var selected = selected();
    var scope = scope(Owner.ENTITY_MANAGEMENT, selected, OPERATION, FENCE);
    var content = content(scope, "source");
    var current = current(ACTOR, TENANT);
    var environment = mock(CapturedEnvironmentBoundary.class);
    var actorCalls = new AtomicInteger();
    stubFreshReservation(selected, scope, current);
    when(sources.read(any(SelectedOwnerIntakeSourceReadEvidence.Request.class)))
        .thenReturn(content);
    doAnswer(
            invocation -> {
              if (actorCalls.incrementAndGet() == 1) {
                Function<AccountControlUiActorService.Current, ?> action =
                    invocation.getArgument(3);
                return action.apply(current);
              }
              throw Status.PERMISSION_DENIED
                  .withDescription("creator source changed after remote read")
                  .asRuntimeException();
            })
        .when(actors)
        .withCurrent(
            eq(TOKEN), eq(TENANT), any(CapturedEnvironmentBoundary.class), any(Function.class));

    assertThatThrownBy(
            () ->
                asPeerResult(
                    "test",
                    "game-design-service",
                    () ->
                        service.authorizeWithEnvironmentCapture(
                            TOKEN,
                            selected.requestId(),
                            Owner.ENTITY_MANAGEMENT,
                            selected,
                            () -> environment)))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            failure ->
                assertThat(Status.fromThrowable(failure).getCode())
                    .isEqualTo(Status.Code.PERMISSION_DENIED));

    assertThat(actorCalls.get()).isEqualTo(2);
    verify(sources).read(any(SelectedOwnerIntakeSourceReadEvidence.Request.class));
    verify(repository, never()).finalizeSourceRead(any(), any(), any(), any());
    verify(repository, never()).abortSourceRead(any());
  }

  @Test
  void lostFinalizationAcknowledgementRecoversOnlyTheCommittedSameBinding() {
    var selected = selected();
    var scope = scope(Owner.AUTOMATION_SCRIPTING, selected, OPERATION, FENCE);
    var content = content(scope, "exact source");
    var current = current(ACTOR, TENANT);
    var binding = new SelectedOwnerIntakeAuthorizationBinding(content, current.source().sources());
    var environment = mock(CapturedEnvironmentBoundary.class);
    stubFreshReservation(selected, scope, current);
    when(sources.read(any(SelectedOwnerIntakeSourceReadEvidence.Request.class)))
        .thenReturn(content);
    when(repository.finalizeSourceRead(
            eq(scope),
            argThat(candidate -> sameContent(candidate, content)),
            eq(current),
            any(Runnable.class)))
        .thenAnswer(
            invocation -> {
              invocation.<Runnable>getArgument(3).run();
              throw new IllegalStateException("final commit acknowledgement lost");
            });
    when(repository.findFinalAuthorization(scope)).thenReturn(Optional.of(binding));

    var result =
        asPeerResult(
            "test",
            "game-design-service",
            () ->
                service.authorizeWithEnvironmentCapture(
                    TOKEN,
                    selected.requestId(),
                    Owner.AUTOMATION_SCRIPTING,
                    selected,
                    () -> environment));

    assertThat(result.canonicalBytes()).isEqualTo(binding.canonicalBytes());
    verify(repository).findFinalAuthorization(scope);
    verify(repository).readFinalAuthorization(binding);
    // The fresh attempt reserves once before finalization; lost-ACK recovery must not reserve
    // again.
    verify(repository)
        .reserveSourceRead(eq(selected.requestId()), any(), any(), any(), any(), any());
    InOrder order = inOrder(repository);
    order
        .verify(repository)
        .finalizeSourceRead(
            eq(scope), argThat(candidate -> sameContent(candidate, content)), eq(current), any());
    order.verify(repository).findFinalAuthorization(scope);
    order.verify(repository).readFinalAuthorization(binding);
  }

  @Test
  void unavailableRemoteResponseLeavesTheOriginalPreliminaryReservationHeld() {
    var selected = selected();
    var scope = scope(Owner.ENTITY_MANAGEMENT, selected, OPERATION, FENCE);
    var current = current(ACTOR, TENANT);
    var environment = mock(CapturedEnvironmentBoundary.class);
    stubFreshReservation(selected, scope, current);
    when(sources.read(any(SelectedOwnerIntakeSourceReadEvidence.Request.class)))
        .thenThrow(
            Status.UNAVAILABLE.withDescription("source read outcome unknown").asRuntimeException());

    assertThatThrownBy(
            () ->
                asPeerResult(
                    "test",
                    "game-design-service",
                    () ->
                        service.authorizeWithEnvironmentCapture(
                            TOKEN,
                            selected.requestId(),
                            Owner.ENTITY_MANAGEMENT,
                            selected,
                            () -> environment)))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            failure ->
                assertThat(Status.fromThrowable(failure).getCode())
                    .isEqualTo(Status.Code.UNAVAILABLE));

    verify(repository)
        .reserveSourceRead(
            eq(selected.requestId()),
            eq(Owner.ENTITY_MANAGEMENT),
            eq(selected),
            eq(current),
            eq("test"),
            any(Runnable.class));
    verify(repository, never()).finalizeSourceRead(any(), any(), any(), any());
    verify(repository, never()).abortSourceRead(any());
  }

  @Test
  void exactGameDesignPeerAndNoEndUserContextAreCheckedBeforeInputOrStorage() {
    assertCode(
        Status.Code.UNAUTHENTICATED,
        () -> service.authorizeWithEnvironmentCapture(null, null, null, null, null));
    assertCode(
        Status.Code.PERMISSION_DENIED,
        () ->
            asPeer(
                "other",
                "game-design-service",
                () -> service.authorizeWithEnvironmentCapture(null, null, null, null, null)));
    assertCode(
        Status.Code.PERMISSION_DENIED,
        () ->
            asPeer(
                "test",
                "account-service",
                () -> service.authorizeWithEnvironmentCapture(null, null, null, null, null)));

    SessionContext.setContext("44", List.of(), Map.of());
    try {
      assertCode(
          Status.Code.PERMISSION_DENIED,
          () ->
              asPeer(
                  "test",
                  "game-design-service",
                  () -> service.authorizeWithEnvironmentCapture(null, null, null, null, null)));
    } finally {
      SessionContext.clear();
    }
    verifyNoInteractions(actors, fences, repository, sources, transactions);
  }

  private void stubFreshReservation(
      DraftCommitBinding selected,
      SelectedOwnerIntakeSourceReadScope scope,
      AccountControlUiActorService.Current current) {
    stubTransactions();
    when(repository.findSourceReadScope(
            selected.requestId(), scope.owner(), selected, "test", tokenHash(TOKEN)))
        .thenReturn(Optional.empty());
    when(repository.reserveSourceRead(
            eq(selected.requestId()),
            eq(scope.owner()),
            eq(selected),
            eq(current),
            eq("test"),
            any(Runnable.class)))
        .thenAnswer(
            invocation -> {
              invocation.<Runnable>getArgument(5).run();
              return scope;
            });
    when(repository.recoverSourceRead(scope))
        .thenReturn(
            recovery(scope, AccountSelectedOwnerIntakeSourceReservationRepository.State.RESERVED));
    doAnswer(
            invocation -> {
              Function<AccountControlUiActorService.Current, ?> action = invocation.getArgument(3);
              return action.apply(current);
            })
        .when(actors)
        .withCurrent(
            eq(TOKEN),
            eq(selected.target().canonicalTenantId()),
            any(CapturedEnvironmentBoundary.class),
            any(Function.class));
  }

  private void stubTransactions() {
    when(transactions.getTransaction(any(TransactionDefinition.class)))
        .thenAnswer(ignored -> new SimpleTransactionStatus());
  }

  private static AccountSelectedOwnerIntakeSourceReservationRepository.Recovery recovery(
      SelectedOwnerIntakeSourceReadScope scope,
      AccountSelectedOwnerIntakeSourceReservationRepository.State state) {
    return new AccountSelectedOwnerIntakeSourceReservationRepository.Recovery(scope, state);
  }

  private static AccountControlUiActorService.Current current(UUID actorId, UUID tenantId) {
    Record row = mock(Record.class);
    byte[] sourcePayload = AccountControlUiAuthority.canonical(Map.of("sources", List.of("AQID")));
    byte[] bundle = AccountControlUiAuthority.canonical(Map.of("issuanceFence", 17));
    when(row.get("request_id", UUID.class)).thenReturn(UUID.randomUUID());
    when(row.get("operation_id", UUID.class))
        .thenReturn(UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd"));
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
    var stored = new AccountControlUiIssuanceRepository.Stored(row);
    var snapshot = mock(AccountControlUiAuthority.Snapshot.class);
    when(snapshot.sources()).thenReturn(sources(actorId));
    when(snapshot.issuanceFence()).thenReturn(17L);
    when(snapshot.outboxCheckpoints())
        .thenReturn(List.of(Map.of("stream", "account", "sequence", 7)));
    return new AccountControlUiActorService.Current(stored, snapshot);
  }

  private static List<SourceEvidence> sources(UUID actorId) {
    return List.of(
        evidence(SourceKind.ACCOUNT, actorId.toString(), "account"),
        evidence(SourceKind.TENANT, TENANT.toString(), "tenant"),
        evidence(SourceKind.MEMBERSHIP, actorId + "/" + TENANT, "membership"));
  }

  private static SourceEvidence evidence(SourceKind kind, String scope, String payload) {
    return new SourceEvidence(
        kind, scope, null, "1", null, null, payload.getBytes(StandardCharsets.UTF_8));
  }

  private static DraftCommitBinding selected() {
    return DraftCommitBinding.create(
        new DraftCommitBinding.TargetProof(
            TENANT, VERSION, 1L, "tenant-key", 2L, "tenant-key", "NEW_GAME_ROW"),
        UUID.fromString("11111111-1111-4111-8111-111111111111"),
        UUID.fromString("22222222-2222-4222-8222-222222222222"),
        "base",
        List.of(
            new DraftCommitBinding.RevisionPayload(
                "0",
                UUID.fromString("33333333-3333-4333-8333-333333333333"),
                Owner.GAME_DESIGN_CONTROL_PLANE,
                CommandSource.deletePayload("synthetic-fixture-command"))),
        List.of(
            new DraftCommitBinding.AffectedUnit(
                Owner.GAME_DESIGN_CONTROL_PLANE,
                "TEMPLATE_CONFIG",
                "templates",
                "TENANT",
                TENANT.toString(),
                "0")));
  }

  private static SelectedOwnerIntakeSourceReadScope scope(
      Owner owner, DraftCommitBinding selected, UUID operation, UUID fence) {
    return new SelectedOwnerIntakeSourceReadScope(
        owner, "test", operation, fence, selected.requestId(), ACTOR, selected);
  }

  private static SelectedOwnerIntakeSourceContent content(
      SelectedOwnerIntakeSourceReadScope scope, String marker) {
    var selected = scope.selected();
    UUID markerGenesis = UUID.nameUUIDFromBytes(marker.getBytes(StandardCharsets.UTF_8));
    String markerEpoch = Integer.toUnsignedString(marker.hashCode());
    var gameplay =
        new GameplayRuleSelectedSource(
            GameplayRuleManifest.canonical(
                Map.of(
                    "schema", "game-design-gameplay-rule-source-snapshot/v1",
                    "bindingJson", selected.canonicalJson(),
                    "bindingDigest", selected.digest(),
                    "sourceEpoch", "0",
                    "inheritedCommitId", "",
                    "genesisReceiptId", markerGenesis.toString(),
                    "manifestJson", GameplayRuleManifest.explicitEmpty().canonicalJson(),
                    "entries", List.of())));
    var template = new TemplateConfigSourceSnapshot(selected, "0", null, markerGenesis, List.of());
    Map<String, byte[]> snapshots =
        Map.of(
            "COMMAND",
            new CommandSnapshot(
                    selected,
                    "0",
                    null,
                    DraftAuthorizationFenceBinding.digest(marker.getBytes(StandardCharsets.UTF_8)),
                    List.of())
                .canonicalBytes(),
            "REALM_POLICY",
            new RealmPolicySnapshot(selected, markerEpoch, List.of()).canonicalBytes(),
            "ASSET",
            new AssetSnapshot(selected, "0", null, markerGenesis, List.of()).canonicalBytes(),
            "BRANDING",
            new BrandingSourceSnapshot(selected, "0", null, markerGenesis, List.of())
                .canonicalBytes());
    var out = new ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(out, SelectedOwnerIntakeSourceContent.DOMAIN);
    DraftAuthorizationFenceBinding.frame(out, scope.canonicalBytes());
    DraftAuthorizationFenceBinding.frame(out, scope.digest());
    for (String family :
        List.of(
            "COMMAND", "REALM_POLICY", "ASSET", "GAMEPLAY_RULE", "BRANDING", "TEMPLATE_CONFIG")) {
      byte[] snapshot =
          switch (family) {
            case "GAMEPLAY_RULE" -> gameplay.canonicalBytes();
            case "TEMPLATE_CONFIG" -> template.canonicalBytes();
            default -> snapshots.get(family);
          };
      DraftAuthorizationFenceBinding.frame(out, family);
      DraftAuthorizationFenceBinding.frame(out, snapshot);
      DraftAuthorizationFenceBinding.frame(out, DraftAuthorizationFenceBinding.digest(snapshot));
    }
    byte[] bytes = out.toByteArray();
    return SelectedOwnerIntakeSourceContent.fromStored(
        bytes, scope, DraftAuthorizationFenceBinding.digest(bytes));
  }

  private static String tokenHash(String value) {
    return AccountControlUiIssuanceRepository.hash(value.getBytes(StandardCharsets.US_ASCII));
  }

  private static boolean sameContent(
      SelectedOwnerIntakeSourceContent candidate, SelectedOwnerIntakeSourceContent expected) {
    return candidate != null
        && expected != null
        && candidate.scope().equals(expected.scope())
        && candidate.digest().equals(expected.digest())
        && java.util.Arrays.equals(candidate.canonicalBytes(), expected.canonicalBytes());
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
