package net.firedevops.firemud.gamedesign.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSourceReadEvidence;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSourceReadScope;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class GameDesignSelectedOwnerIntakeSourceReadServiceTest {
  private static final String NAMESPACE = "test";
  private static final String ACCOUNT_PEER = "spiffe://firemud/ns/test/sa/account-service";
  private static final UUID GENESIS = UUID.randomUUID();

  @AfterEach
  void clearCallerAndTransactionContext() {
    SessionContext.clear();
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
    TransactionSynchronizationManager.setActualTransactionActive(false);
  }

  @Test
  void readsCompleteSelectedSourceForEntityAndAutomationOnlyAfterExactPermission() {
    for (Owner owner : List.of(Owner.ENTITY_MANAGEMENT, Owner.AUTOMATION_SCRIPTING)) {
      var repository = mock(GameDesignSourceRepository.class);
      var transactions = mock(PlatformTransactionManager.class);
      when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
      var permissions =
          mock(
              net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSourceReadClient
                  .class);
      org.mockito.Mockito.doAnswer(
              invocation -> new SelectedOwnerIntakeSourceReadEvidence(invocation.getArgument(0)))
          .when(permissions)
          .read(any());

      var binding = binding();
      var scope = scope(owner, binding);
      var export = SelectedOwnerIntakeSourceExport.create(scope, sources(binding));
      when(repository.requireSelectedOwnerIntakeSource(scope)).thenReturn(export);
      var service = service(repository, transactions, permissions);

      var result =
          new java.util.concurrent.atomic.AtomicReference<SelectedOwnerIntakeSourceExport>();
      asAccount(() -> result.set(service.readSource(scope)));

      assertThat(result.get()).isSameAs(export);
      assertThat(result.get().scope().canonicalBytes()).isEqualTo(scope.canonicalBytes());
      assertThat(result.get().scope().digest()).isEqualTo(scope.digest());
      assertThat(result.get().canonicalBytes()).isNotEmpty();
      assertThat(result.get().digest()).isNotBlank();
      var ordered = org.mockito.Mockito.inOrder(permissions, transactions, repository);
      ordered.verify(permissions).read(any());
      ordered.verify(transactions).getTransaction(any());
      ordered.verify(repository).requireSelectedOwnerIntakeSource(scope);
    }
  }

  @Test
  void rejectsMissingOrSubstitutedPermissionBeforeOpeningTheSourceTransaction() {
    var binding = binding();
    var scope = scope(Owner.ENTITY_MANAGEMENT, binding);

    assertPermissionDeniedBeforeQuery(scope, null);
    assertPermissionDeniedBeforeQuery(
        scope,
        request ->
            new SelectedOwnerIntakeSourceReadEvidence(
                new SelectedOwnerIntakeSourceReadEvidence.Request(
                    request.schemaVersion(),
                    request.targetNamespace(),
                    UUID.randomUUID(),
                    request.intendedReader(),
                    request.purpose(),
                    request.scope())));
    assertPermissionDeniedBeforeQuery(
        scope,
        request ->
            new SelectedOwnerIntakeSourceReadEvidence(
                SelectedOwnerIntakeSourceReadEvidence.Request.create(
                    request.targetNamespace(),
                    scope(Owner.AUTOMATION_SCRIPTING, request.scope().selected()))));
  }

  @Test
  void authenticatesAccountPeerAndRejectsEndUserContextBeforePermissionOrStorage() {
    var scope = scope(Owner.ENTITY_MANAGEMENT, binding());
    var repository = mock(GameDesignSourceRepository.class);
    var transactions = mock(PlatformTransactionManager.class);
    var permissions =
        mock(
            net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSourceReadClient
                .class);
    var service = service(repository, transactions, permissions);

    assertThatThrownBy(() -> service.readSource(scope))
        .isInstanceOf(io.grpc.StatusRuntimeException.class);
    asPeer("spiffe://firemud/ns/test/sa/game-design-service", () -> denied(service, scope));
    asPeer("spiffe://firemud/ns/other/sa/account-service", () -> denied(service, scope));
    SessionContext.setContext("123", List.of(), java.util.Map.of());
    asAccount(() -> denied(service, scope));
    assertNoPermissionOrQuery(permissions, transactions, repository);
  }

  @Test
  void rejectsNamespaceMismatchAndAmbientTransactionOrSynchronizationBeforePermission() {
    var repository = mock(GameDesignSourceRepository.class);
    var transactions = mock(PlatformTransactionManager.class);
    var permissions =
        mock(
            net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSourceReadClient
                .class);
    var service = service(repository, transactions, permissions);
    var scope = scope(Owner.ENTITY_MANAGEMENT, binding());
    var wrongNamespace =
        new SelectedOwnerIntakeSourceReadScope(
            Owner.ENTITY_MANAGEMENT,
            "other",
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            scope.selected());

    asAccount(() -> denied(service, wrongNamespace));
    TransactionSynchronizationManager.setActualTransactionActive(true);
    asAccount(() -> denied(service, scope));
    TransactionSynchronizationManager.setActualTransactionActive(false);
    TransactionSynchronizationManager.initSynchronization();
    asAccount(() -> denied(service, scope));
    assertNoPermissionOrQuery(permissions, transactions, repository);
  }

  @Test
  void rejectsRepositoryExportForAnotherExactScope() {
    var repository = mock(GameDesignSourceRepository.class);
    var transactions = mock(PlatformTransactionManager.class);
    when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    var permissions =
        mock(
            net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSourceReadClient
                .class);
    org.mockito.Mockito.doAnswer(
            invocation -> new SelectedOwnerIntakeSourceReadEvidence(invocation.getArgument(0)))
        .when(permissions)
        .read(any());
    var binding = binding();
    var scope = scope(Owner.ENTITY_MANAGEMENT, binding);
    var substitutedScope =
        new SelectedOwnerIntakeSourceReadScope(
            Owner.ENTITY_MANAGEMENT,
            NAMESPACE,
            UUID.randomUUID(),
            scope.fenceId(),
            scope.intakeRequestId(),
            scope.actorAccountId(),
            binding);
    when(repository.requireSelectedOwnerIntakeSource(scope))
        .thenReturn(SelectedOwnerIntakeSourceExport.create(substitutedScope, sources(binding)));
    var service = service(repository, transactions, permissions);

    assertThatThrownBy(() -> asAccount(() -> service.readSource(scope)))
        .isInstanceOf(io.grpc.StatusRuntimeException.class);
    org.mockito.Mockito.verify(permissions).read(any());
    org.mockito.Mockito.verify(transactions).getTransaction(any());
    org.mockito.Mockito.verify(repository).requireSelectedOwnerIntakeSource(scope);
  }

  private static void assertPermissionDeniedBeforeQuery(
      SelectedOwnerIntakeSourceReadScope scope,
      java.util.function.Function<
              SelectedOwnerIntakeSourceReadEvidence.Request, SelectedOwnerIntakeSourceReadEvidence>
          response) {
    var repository = mock(GameDesignSourceRepository.class);
    var transactions = mock(PlatformTransactionManager.class);
    var permissions =
        mock(
            net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSourceReadClient
                .class);
    if (response == null) {
      org.mockito.Mockito.doReturn(null).when(permissions).read(any());
    } else {
      org.mockito.Mockito.doAnswer(invocation -> response.apply(invocation.getArgument(0)))
          .when(permissions)
          .read(any());
    }
    var service = service(repository, transactions, permissions);

    assertThatThrownBy(() -> asAccount(() -> service.readSource(scope)))
        .isInstanceOf(io.grpc.StatusRuntimeException.class);
    org.mockito.Mockito.verify(permissions).read(any());
    org.mockito.Mockito.verifyNoInteractions(transactions, repository);
  }

  private static void assertNoPermissionOrQuery(
      Object permissions, Object transactions, Object repository) {
    org.mockito.Mockito.verifyNoInteractions(permissions, transactions, repository);
  }

  private static void denied(
      GameDesignSelectedOwnerIntakeSourceReadService service,
      SelectedOwnerIntakeSourceReadScope scope) {
    assertThatThrownBy(() -> service.readSource(scope))
        .isInstanceOf(io.grpc.StatusRuntimeException.class);
  }

  private static GameDesignSelectedOwnerIntakeSourceReadService service(
      GameDesignSourceRepository repository,
      PlatformTransactionManager transactions,
      net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSourceReadClient
          permissions) {
    return new GameDesignSelectedOwnerIntakeSourceReadService(
        repository, transactions, permissions, NAMESPACE);
  }

  private static GameDesignSourceRepository.SynchronizedSources sources(
      DraftCommitBinding binding) {
    var command = new CommandSnapshot(binding, "0", null, "sha256:" + "a".repeat(64), List.of());
    var policy = new RealmPolicySnapshot(binding, "0", List.of());
    var asset = new AssetSnapshot(binding, "0", null, GENESIS, List.of());
    var gameplay = new GameplayRuleSnapshot(binding, "0", null, GENESIS, List.of());
    var branding = new BrandingSourceSnapshot(binding, "0", null, GENESIS, List.of());
    var template = new TemplateConfigSourceSnapshot(binding, "0", null, GENESIS, List.of());
    return new GameDesignSourceRepository.SynchronizedSources(
        command, policy, asset, gameplay, Optional.of(branding), Optional.of(template));
  }

  private static SelectedOwnerIntakeSourceReadScope scope(
      Owner owner, DraftCommitBinding selected) {
    return new SelectedOwnerIntakeSourceReadScope(
        owner,
        NAMESPACE,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        selected);
  }

  private static DraftCommitBinding binding() {
    var target =
        new DraftCommitBinding.TargetProof(
            UUID.randomUUID(), UUID.randomUUID(), 23L, "tenant", 42L, "tenant", "NEW_GAME_ROW");
    return DraftCommitBinding.create(
        target,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "base-commit-0",
        List.of(
            new DraftCommitBinding.RevisionPayload(
                "0", UUID.randomUUID(), Owner.GAME_DESIGN_CONTROL_PLANE, "{}")),
        List.of(
            new DraftCommitBinding.AffectedUnit(
                Owner.GAME_DESIGN_CONTROL_PLANE,
                "TEMPLATE_CONFIG",
                target.canonicalVersionId().toString(),
                "TEMPLATE_CONFIG",
                "ALL",
                "0")));
  }

  private static void asAccount(Runnable action) {
    asPeer(ACCOUNT_PEER, action);
  }

  private static void asPeer(String uri, Runnable action) {
    Context.current()
        .withValue(GrpcPeerIdentity.CONTEXT_KEY, GrpcPeerIdentity.parseUri(uri).orElseThrow())
        .run(action);
  }
}
