package net.firedevops.firemud.gamedesign.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.accountservice.service.session.AccountControlUiOriginalOrderFixture;
import net.firedevops.firemud.common.authoring.AccountOriginalDraftOrderClient;
import net.firedevops.firemud.common.authoring.AccountOriginalDraftOrderGrpcCodec;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.publication.GameDesignSourceRepository;
import net.firedevops.firemud.gamedesign.publication.GameplayRuleSource;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real GD-issued Game/Version identity and typed gameplay source -> real Account creator issuance,
 * original COMMIT_ORDER over strict loopback mTLS -> atomic GD source/fence/terminal readback.
 * Account creator/legal bootstrap, platform custody and signer observations remain explicitly
 * stipulated upstream inputs. This defines internal construction proof, not authenticated public
 * recovery, deployed environment authority or runtime activation. Original Account settlement is
 * driven through the existing real authenticated GD terminal read after the local commit.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class GameDesignSourceCommitPostgresIntegrationTest {
  private static final String NAMESPACE = "firemud";
  private static final Network NETWORK = Network.newNetwork();

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  static final PostgreSQLContainer<?> ACCOUNT_POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(TestContainerImages.redis())
          .withNetwork(NETWORK)
          .withNetworkAliases("positive-control-ui-primary")
          .withExposedPorts(6379)
          .withCommand(
              "redis-server",
              "--bind",
              "0.0.0.0",
              "--protected-mode",
              "no",
              "--appendonly",
              "yes",
              "--appendfsync",
              "always");

  @Container
  static final GenericContainer<?> REPLICA =
      new GenericContainer<>(TestContainerImages.redis())
          .withNetwork(NETWORK)
          .dependsOn(REDIS)
          .withCommand(
              "redis-server",
              "--bind",
              "0.0.0.0",
              "--protected-mode",
              "no",
              "--appendonly",
              "yes",
              "--appendfsync",
              "always",
              "--replicaof",
              "positive-control-ui-primary",
              "6379");

  @TempDir Path temporary;

  @Test
  void genuineOriginalProducerCommitsSourcesAndRetainsExactTerminalRetryWithoutRecapture()
      throws Exception {
    var local = local();
    try (var account = account(local);
        var remote = remote(account)) {
      var request = account.prepareOriginalDraftOrder(binding(local, "focus"), NAMESPACE);
      assertThat(local.dsl.fetchCount(DSL.table("game_design_draft_commit"))).isZero();
      var service = service(local, remote.client, new GameDesignSourceRepository(local.dsl));
      var terminal = service.commit(request.original(), request.originalCreatorCredential());
      assertThat(terminal.result()).isEqualTo(GameDesignDraftTerminalOutcome.Result.COMMITTED);
      assertThat(terminal.operation().accountBindingBytes())
          .isEqualTo(request.originalAccountBinding());
      assertThat(remote.calls.get()).isEqualTo(1);
      assertSynchronized(local, request.original());
      account.assertOriginalDraftOrderPending(request.original());

      // No public recovery endpoint exists: internal exact terminal replay accepts retained source
      // identity without a fresh mutable credential. It cannot replace the original durable input.
      var retry = service.commit(request.original(), null);
      assertThat(retry.finalEvidenceBytes()).isEqualTo(terminal.finalEvidenceBytes());
      assertThat(remote.calls.get()).isEqualTo(1);
      assertSynchronized(local, request.original());
      var original = request.original();
      var changedOperation =
          new DraftAuthorizationFenceBinding(
              UUID.randomUUID(),
              original.requestId(),
              original.commitId(),
              original.fenceId(),
              original.actorAccountId(),
              original.tenantId(),
              original.versionId(),
              original.baseCommitId(),
              original.expectedDraftEpoch(),
              original.gameDesignBinding(),
              original.normalizedInput(),
              original.inputDigest(),
              original.sources(),
              original.schemaVersion(),
              original.requiredOwners());
      assertThatThrownBy(
              () -> service.commit(changedOperation, request.originalCreatorCredential()))
          .isInstanceOf(
              DraftCommitCoordinatorRepository.DraftCommitIdentityConflictException.class);
      var changedSources = new java.util.ArrayList<>(original.sources());
      var source = changedSources.getFirst();
      changedSources.set(
          0,
          new DraftAuthorizationFenceBinding.SourceEvidence(
              source.kind(),
              source.scopeId(),
              source.generation(),
              source.sourceVersion(),
              source.checkpointStream(),
              source.checkpointSequence(),
              new byte[] {99}));
      var substitutedSource =
          new DraftAuthorizationFenceBinding(
              original.operationId(),
              original.requestId(),
              original.commitId(),
              original.fenceId(),
              original.actorAccountId(),
              original.tenantId(),
              original.versionId(),
              original.baseCommitId(),
              original.expectedDraftEpoch(),
              original.gameDesignBinding(),
              original.normalizedInput(),
              original.inputDigest(),
              changedSources,
              original.schemaVersion(),
              original.requiredOwners());
      assertThatThrownBy(
              () -> service.commit(substitutedSource, request.originalCreatorCredential()))
          .isInstanceOf(
              DraftCommitCoordinatorRepository.DraftCommitIdentityConflictException.class);
      assertThat(remote.calls.get()).isEqualTo(1);
      account.assertOriginalDraftOrderPending(original);
      settleOriginal(account, local, original);
      assertThat(service.commit(original, null).finalEvidenceBytes())
          .isEqualTo(terminal.finalEvidenceBytes());
      assertThat(remote.calls.get()).isEqualTo(1);
    }
  }

  @Test
  void failureAfterActualSourceAwareFenceRollsBackAllLocalEffectsAndRetriesOriginalOrder()
      throws Exception {
    var local = local();
    try (var account = account(local);
        var remote = remote(account)) {
      var request = account.prepareOriginalDraftOrder(binding(local, "rollback"), NAMESPACE);
      var failingSources = spy(new GameDesignSourceRepository(local.dsl));
      doAnswer(
              call -> {
                var actual = call.callRealMethod();
                assertThat(actual).isNotNull();
                assertThat(
                        new DraftCommitCoordinatorRepository(local.dsl)
                            .readVisibilityFence(local.target))
                    .isPresent();
                throw new IllegalStateException("test-only failure after source-aware visibility");
              })
          .when(failingSources)
          .readSynchronized(local.target, request.original().commitId());
      assertThatThrownBy(
              () ->
                  service(local, remote.client, failingSources)
                      .commit(request.original(), request.originalCreatorCredential()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("test-only failure after source-aware visibility");
      var coordinator = new DraftCommitCoordinatorRepository(local.dsl);
      assertThat(
              coordinator
                  .read(local.target, request.original().requestId())
                  .orElseThrow()
                  .workflowState())
          .isEqualTo(DraftCommitCoordinatorRepository.WorkflowState.QUEUED);
      assertThat(coordinator.readApplicationSlot(local.target)).isPresent();
      assertThat(coordinator.readVisibilityFence(local.target)).isEmpty();
      assertThat(
              new GameDesignSourceRepository(local.dsl)
                  .readSynchronized(local.target, request.original().commitId()))
          .isEmpty();
      assertThat(epoch(local)).isEqualTo("0");
      account.assertOriginalDraftOrderPending(request.original());

      var committed =
          service(local, remote.client, new GameDesignSourceRepository(local.dsl))
              .commit(request.original(), request.originalCreatorCredential());
      assertThat(committed.operation().accountBindingBytes())
          .isEqualTo(request.originalAccountBinding());
      assertSynchronized(local, request.original());
      account.assertOriginalDraftOrderPending(request.original());
    }
  }

  @Test
  void concurrentExactClaimsCannotApplySourceTwice() throws Exception {
    var local = local();
    try (var account = account(local);
        var remote = remote(account)) {
      var request = account.prepareOriginalDraftOrder(binding(local, "concurrent"), NAMESPACE);
      // Establish the same genuine original order; concurrent owner retries may fail NOWAIT,
      // but must never replace this operation or commit a second source application.
      remote.client.claim(request);
      var service = service(local, remote.client, new GameDesignSourceRepository(local.dsl));
      try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
        var start = new java.util.concurrent.CountDownLatch(1);
        var attempts =
            java.util.stream.IntStream.range(0, 2)
                .mapToObj(
                    i ->
                        workers.submit(
                            () -> {
                              start.await();
                              try {
                                service.commit(
                                    request.original(), request.originalCreatorCredential());
                              } catch (io.grpc.StatusRuntimeException transientAccountRace) {
                                assertThat(transientAccountRace.getStatus().getCode())
                                    .isEqualTo(io.grpc.Status.Code.UNAVAILABLE);
                              }
                              return true;
                            }))
                .toList();
        start.countDown();
        for (var attempt : attempts) assertThat(attempt.get(20, TimeUnit.SECONDS)).isTrue();
      }
      var terminal = service.commit(request.original(), request.originalCreatorCredential());
      if (terminal.result() == GameDesignDraftTerminalOutcome.Result.COMMITTED) {
        assertSynchronized(local, request.original());
      } else {
        assertAborted(local, request.original());
      }
      assertThat(local.dsl.fetchCount(DSL.table("game_design_draft_commit"))).isEqualTo(1);
      account.assertOriginalDraftOrderPending(request.original());
    }
  }

  @Test
  void nonDraftAndOccupiedTargetsCannotCreateAccountOrders() throws Exception {
    var local = local();
    try (var account = account(local)) {
      var request = account.prepareOriginalDraftOrder(binding(local, "ineligible"), NAMESPACE);
      var unusedAccount = mock(AccountOriginalDraftOrderClient.class);
      local.dsl.execute(
          "UPDATE version SET version_state = 'RETIRED' WHERE id = ?",
          local.target.gameDesignVersionRowId());
      assertThatThrownBy(
              () ->
                  service(local, unusedAccount, new GameDesignSourceRepository(local.dsl))
                      .commit(request.original(), request.originalCreatorCredential()))
          .isInstanceOf(DraftCommitCoordinatorRepository.DraftCommitStateConflictException.class);
      local.dsl.execute(
          "UPDATE version SET version_state = 'DRAFT' WHERE id = ?",
          local.target.gameDesignVersionRowId());
      var other = binding(local, "other-slot");
      tx(local)
          .executeWithoutResult(
              ignored -> {
                var coordinator = new DraftCommitCoordinatorRepository(local.dsl);
                coordinator.claim(other);
                coordinator.claimApplicationSlot(other);
              });
      assertThatThrownBy(
              () ->
                  service(local, unusedAccount, new GameDesignSourceRepository(local.dsl))
                      .commit(request.original(), request.originalCreatorCredential()))
          .isInstanceOf(DraftCommitCoordinatorRepository.DraftCommitStateConflictException.class);
      verifyNoInteractions(unusedAccount);
      assertThat(
              new GameDesignDraftTerminalOutcomeRepository(local.dsl)
                  .read(new GameDesignDraftTerminalOperation(request.original())))
          .isEmpty();
      assertThat(epoch(local)).isEqualTo("0");
    }
  }

  @Test
  void selectedPublicationCannotOrderAnotherOriginalSourceCommit() throws Exception {
    var local = local();
    try (var account = account(local);
        var remote = remote(account)) {
      var first = account.prepareOriginalDraftOrder(binding(local, "selected"), NAMESPACE);
      service(local, remote.client, new GameDesignSourceRepository(local.dsl))
          .commit(first.original(), first.originalCreatorCredential());
      var selected = new GameDesignDraftTerminalOperation(first.original()).gameDesignBinding();
      tx(local)
          .executeWithoutResult(
              ignored ->
                  new AuthoredDraftPublishSelectionRepository(
                          local.dsl, new DraftCommitCoordinatorRepository(local.dsl))
                      .reserve(
                          new AuthoredDraftPublishSelection.PublishIntent(
                              local.target.canonicalTenantId(),
                              local.target.canonicalVersionId(),
                              UUID.randomUUID().toString(),
                              "1",
                              "selected",
                              selected.requestId(),
                              selected.commitId(),
                              selected.digest())));
      var later = account.prepareOriginalDraftOrder(binding(local, "after-selection"), NAMESPACE);
      var unusedAccount = mock(AccountOriginalDraftOrderClient.class);
      assertThatThrownBy(
              () ->
                  service(local, unusedAccount, new GameDesignSourceRepository(local.dsl))
                      .commit(later.original(), later.originalCreatorCredential()))
          .isInstanceOf(DraftCommitCoordinatorRepository.DraftCommitStateConflictException.class);
      verifyNoInteractions(unusedAccount);
      assertThat(remote.calls.get()).isEqualTo(1);
      assertThat(epoch(local)).isEqualTo("1");
    }
  }

  @Test
  void deniedAccountAcquisitionProducesImmutableLocalAbortWithoutApplyingSources()
      throws Exception {
    var local = local();
    try (var account = account(local)) {
      var request = account.prepareOriginalDraftOrder(binding(local, "denied"), NAMESPACE);
      var denied = mock(AccountOriginalDraftOrderClient.class);
      when(denied.claim(org.mockito.ArgumentMatchers.any()))
          .thenThrow(io.grpc.Status.PERMISSION_DENIED.asRuntimeException());
      var service = service(local, denied, new GameDesignSourceRepository(local.dsl));
      var aborted = service.commit(request.original(), request.originalCreatorCredential());
      assertAborted(local, request.original());
      assertThat(service.commit(request.original(), null).finalEvidenceBytes())
          .isEqualTo(aborted.finalEvidenceBytes());
      org.mockito.Mockito.verify(denied, org.mockito.Mockito.times(1))
          .claim(org.mockito.ArgumentMatchers.any());
      assertThatThrownBy(
              () ->
                  local.dsl.execute(
                      "UPDATE game_design_draft_commit_final_abort SET abort_bytes = ? WHERE commit_id = ?",
                      new byte[] {99},
                      request.original().commitId()))
          .hasMessageContaining("Game Design Draft final-abort evidence is immutable and retained");
      assertThatThrownBy(
              () ->
                  tx(local)
                      .executeWithoutResult(
                          ignored ->
                              new DraftCommitCoordinatorRepository(local.dsl)
                                  .claim(
                                      new GameDesignDraftTerminalOperation(request.original())
                                          .gameDesignBinding(),
                                      request.original())))
          .isInstanceOf(DraftCommitCoordinatorRepository.DraftCommitStateConflictException.class);
    }
  }

  @Test
  void lostAccountResponseSettlesAgainstActualDurableLocalAbort() throws Exception {
    var local = local();
    try (var account = account(local);
        var remote = remote(account)) {
      var request = account.prepareOriginalDraftOrder(binding(local, "lost-response"), NAMESPACE);
      var lost = spy(remote.client);
      doAnswer(
              call -> {
                call.callRealMethod();
                throw io.grpc.Status.UNAVAILABLE
                    .withDescription("test-only lost reply")
                    .asRuntimeException();
              })
          .when(lost)
          .claim(org.mockito.ArgumentMatchers.any());
      var service = service(local, lost, new GameDesignSourceRepository(local.dsl));
      var aborted = service.commit(request.original(), request.originalCreatorCredential());
      assertAborted(local, request.original());
      account.assertOriginalDraftOrderPending(request.original());
      settleOriginal(account, local, request.original(), "FAILED_NONPUBLICATION");
      assertThat(service.commit(request.original(), null).finalEvidenceBytes())
          .isEqualTo(aborted.finalEvidenceBytes());
      assertThat(remote.calls.get()).isEqualTo(1);
    }
  }

  @Test
  void lateAccountCompletionCannotBypassAbortAndLifecycleRemainsFencedUntilTerminal()
      throws Exception {
    var local = local();
    try (var account = account(local);
        var remote = remote(account);
        var worker = java.util.concurrent.Executors.newSingleThreadExecutor()) {
      var request = account.prepareOriginalDraftOrder(binding(local, "late-order"), NAMESPACE);
      var entered = new java.util.concurrent.CountDownLatch(1);
      var finishAccount = new java.util.concurrent.CountDownLatch(1);
      var late = spy(remote.client);
      doAnswer(
              call -> {
                entered.countDown();
                if (!finishAccount.await(20, TimeUnit.SECONDS))
                  throw new AssertionError("late Account wait");
                return call.callRealMethod();
              })
          .when(late)
          .claim(org.mockito.ArgumentMatchers.any());
      var attempt =
          worker.submit(
              () ->
                  service(local, late, new GameDesignSourceRepository(local.dsl))
                      .commit(request.original(), request.originalCreatorCredential()));
      try {
        assertThat(entered.await(20, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(
                () ->
                    local.dsl.execute(
                        "UPDATE version SET version_state_epoch = version_state_epoch + 1 WHERE id = ?",
                        local.target.gameDesignVersionRowId()))
            .hasMessageContaining(
                "Active or unresolved Draft application prevents Version lifecycle changes");
        assertThatThrownBy(
                () ->
                    local.dsl.execute(
                        "UPDATE version SET version_state = 'RETIRED' WHERE id = ?",
                        local.target.gameDesignVersionRowId()))
            .hasMessageContaining(
                "Active or unresolved Draft application prevents Version lifecycle changes");
        assertThat(
                local.dsl.execute(
                    "UPDATE version SET version_state = version_state, version_state_epoch = version_state_epoch WHERE id = ?",
                    local.target.gameDesignVersionRowId()))
            .isEqualTo(1);
        var binding = new GameDesignDraftTerminalOperation(request.original()).gameDesignBinding();
        assertThatThrownBy(
                () ->
                    tx(local)
                        .executeWithoutResult(
                            ignored ->
                                new AuthoredDraftPublishSelectionRepository(
                                        local.dsl, new DraftCommitCoordinatorRepository(local.dsl))
                                    .reserve(
                                        new AuthoredDraftPublishSelection.PublishIntent(
                                            local.target.canonicalTenantId(),
                                            local.target.canonicalVersionId(),
                                            UUID.randomUUID().toString(),
                                            "1",
                                            "blocked during order",
                                            binding.requestId(),
                                            binding.commitId(),
                                            binding.digest()))))
            .isInstanceOf(DraftCommitCoordinatorRepository.DraftCommitStateConflictException.class)
            .hasMessage(
                "An active or unresolved owner application prevents Draft publication selection");
        var denied = mock(AccountOriginalDraftOrderClient.class);
        when(denied.claim(org.mockito.ArgumentMatchers.any()))
            .thenThrow(io.grpc.Status.UNAVAILABLE.asRuntimeException());
        service(local, denied, new GameDesignSourceRepository(local.dsl))
            .commit(request.original(), request.originalCreatorCredential());
        assertAborted(local, request.original());
      } finally {
        finishAccount.countDown();
      }
      assertThat(attempt.get(20, TimeUnit.SECONDS).result())
          .isEqualTo(GameDesignDraftTerminalOutcome.Result.DEFINITIVELY_ABORTED);
      assertAborted(local, request.original());
      account.assertOriginalDraftOrderPending(request.original());
      settleOriginal(account, local, request.original(), "FAILED_NONPUBLICATION");
      assertThat(
              local.dsl.execute(
                  "UPDATE version SET version_state_epoch = version_state_epoch + 1 WHERE id = ?",
                  local.target.gameDesignVersionRowId()))
          .isEqualTo(1);
    }
  }

  @Test
  void concurrentApplicationWinningVersionLockReturnsCommittedInsteadOfAborting() throws Exception {
    var local = local();
    try (var account = account(local);
        var remote = remote(account);
        var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var request = account.prepareOriginalDraftOrder(binding(local, "apply-wins"), NAMESPACE);
      var firstOrdered = new java.util.concurrent.CountDownLatch(1);
      var permitApply = new java.util.concurrent.CountDownLatch(1);
      var failedCallEntered = new java.util.concurrent.CountDownLatch(1);
      var permitFailure = new java.util.concurrent.CountDownLatch(1);
      var applicationEntered = new java.util.concurrent.CountDownLatch(1);
      var permitCommit = new java.util.concurrent.CountDownLatch(1);
      var firstClient = spy(remote.client);
      doAnswer(
              call -> {
                var evidence = call.callRealMethod();
                firstOrdered.countDown();
                if (!permitApply.await(20, TimeUnit.SECONDS))
                  throw new AssertionError("apply admission wait");
                return evidence;
              })
          .when(firstClient)
          .claim(org.mockito.ArgumentMatchers.any());
      var failedClient = mock(AccountOriginalDraftOrderClient.class);
      doAnswer(
              call -> {
                failedCallEntered.countDown();
                if (!permitFailure.await(20, TimeUnit.SECONDS))
                  throw new AssertionError("failure wait");
                throw io.grpc.Status.UNAVAILABLE.asRuntimeException();
              })
          .when(failedClient)
          .claim(org.mockito.ArgumentMatchers.any());
      var heldSources = spy(new GameDesignSourceRepository(local.dsl));
      doAnswer(
              call -> {
                var applied = call.callRealMethod();
                applicationEntered.countDown();
                if (!permitCommit.await(20, TimeUnit.SECONDS))
                  throw new AssertionError("source commit wait");
                return applied;
              })
          .when(heldSources)
          .apply(org.mockito.ArgumentMatchers.any());
      var applying =
          workers.submit(
              () ->
                  service(local, firstClient, heldSources)
                      .commit(request.original(), request.originalCreatorCredential()));
      java.util.concurrent.Future<GameDesignDraftTerminalOutcome> aborting = null;
      try {
        assertThat(firstOrdered.await(20, TimeUnit.SECONDS)).isTrue();
        aborting =
            workers.submit(
                () ->
                    service(local, failedClient, new GameDesignSourceRepository(local.dsl))
                        .commit(request.original(), request.originalCreatorCredential()));
        assertThat(failedCallEntered.await(20, TimeUnit.SECONDS)).isTrue();
        permitApply.countDown();
        assertThat(applicationEntered.await(20, TimeUnit.SECONDS)).isTrue();
        permitFailure.countDown();
      } finally {
        permitApply.countDown();
        permitFailure.countDown();
        permitCommit.countDown();
      }
      var committed = applying.get(20, TimeUnit.SECONDS);
      assertThat(committed.result()).isEqualTo(GameDesignDraftTerminalOutcome.Result.COMMITTED);
      assertThat(Objects.requireNonNull(aborting).get(20, TimeUnit.SECONDS).finalEvidenceBytes())
          .isEqualTo(committed.finalEvidenceBytes());
      assertSynchronized(local, request.original());
      settleOriginal(account, local, request.original());
    }
  }

  private void assertAborted(Local local, DraftAuthorizationFenceBinding original) {
    var terminal =
        new GameDesignDraftTerminalOutcomeRepository(local.dsl)
            .read(new GameDesignDraftTerminalOperation(original))
            .orElseThrow();
    assertThat(terminal.result())
        .isEqualTo(GameDesignDraftTerminalOutcome.Result.DEFINITIVELY_ABORTED);
    var coordinator = new DraftCommitCoordinatorRepository(local.dsl);
    var state = coordinator.read(local.target, original.requestId()).orElseThrow();
    assertThat(state.workflowState())
        .isEqualTo(DraftCommitCoordinatorRepository.WorkflowState.FAILED_NONPUBLICATION);
    assertThat(state.ownerStates().get(DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE).status())
        .isEqualTo(DraftCommitCoordinatorRepository.OwnerStatus.REJECTED);
    assertThat(coordinator.readApplicationSlot(local.target)).isEmpty();
    assertThat(coordinator.readVisibilityFence(local.target)).isEmpty();
    assertThat(epoch(local)).isEqualTo("0");
  }

  @Test
  void noApplicationProducerCannotAbortInProgressUnknownOrActuallyAppliedOwner() throws Exception {
    var local = local();
    try (var account = account(local);
        var remote = remote(account)) {
      var request = account.prepareOriginalDraftOrder(binding(local, "unsafe-abort"), NAMESPACE);
      var operation = new GameDesignDraftTerminalOperation(request.original());
      var binding = operation.gameDesignBinding();
      var coordinator = new DraftCommitCoordinatorRepository(local.dsl);
      remote.client.claim(request);
      tx(local)
          .executeWithoutResult(
              ignored -> {
                coordinator.claim(binding, request.original());
                coordinator.claimApplicationSlot(binding);
                coordinator.markOwnerInProgress(
                    binding, DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE);
              });
      assertUnsafeLocalAbort(local, coordinator, operation);
      // This storage-only diagnostic setup supplies uncertainty, never positive owner evidence.
      tx(local)
          .executeWithoutResult(
              ignored ->
                  coordinator.recordOwnerOutcome(
                      binding,
                      new DraftCommitCoordinatorRepository.OwnerOutcome(
                          DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                          DraftCommitCoordinatorRepository.OwnerStatus.UNKNOWN,
                          binding.commitId(),
                          binding.digest(),
                          null,
                          null,
                          List.of())));
      assertUnsafeLocalAbort(local, coordinator, operation);
      var applied =
          tx(local).execute(ignored -> new GameDesignSourceRepository(local.dsl).apply(binding));
      assertUnsafeLocalAbort(local, coordinator, operation);
      tx(local)
          .executeWithoutResult(
              ignored -> {
                coordinator.advanceSourceVisibilityFence(
                    binding,
                    new DraftCommitCoordinatorRepository.CoordinatorProof(
                        binding, List.of(Objects.requireNonNull(applied).ownerOutcome())));
                coordinator.releaseApplicationSlot(binding);
              });
      assertSynchronized(local, request.original());
      settleOriginal(account, local, request.original());
    }
  }

  private void assertUnsafeLocalAbort(
      Local local,
      DraftCommitCoordinatorRepository coordinator,
      GameDesignDraftTerminalOperation operation) {
    assertThatThrownBy(
            () -> tx(local).execute(ignored -> coordinator.abortUnattemptedLocalSource(operation)))
        .isInstanceOf(DraftCommitCoordinatorRepository.DraftCommitStateConflictException.class)
        .hasMessage("Local no-application proof requires an unattempted owner");
    assertThat(coordinator.readApplicationSlot(local.target)).isPresent();
    assertThat(new GameDesignDraftTerminalOutcomeRepository(local.dsl).read(operation)).isEmpty();
  }

  private TransactionTemplate tx(Local local) {
    var result = new TransactionTemplate(local.manager);
    result.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    return result;
  }

  @Test
  void rawLifecycleFirstRejectsSlotInsertAcrossSupportedIsolationLevels() throws Exception {
    for (int isolation : storageIsolationLevels()) {
      var local = local();
      var binding = binding(local, "lifecycle-first");
      tx(local)
          .executeWithoutResult(
              ignored -> new DraftCommitCoordinatorRepository(local.dsl).claim(binding));
      try (var slot = storageConnection(local, isolation);
          var lifecycle = storageConnection(local, Connection.TRANSACTION_READ_COMMITTED);
          var worker = java.util.concurrent.Executors.newSingleThreadExecutor()) {
        var slotSql = DSL.using(slot, SQLDialect.POSTGRES);
        slotSql.fetch("SELECT * FROM version WHERE id = ?", local.target.gameDesignVersionRowId());
        DSL.using(lifecycle, SQLDialect.POSTGRES)
            .execute(
                "UPDATE version SET version_state = 'RETIRED', version_state_epoch = version_state_epoch + 1 WHERE id = ?",
                local.target.gameDesignVersionRowId());
        var started = new java.util.concurrent.CountDownLatch(1);
        var inserting =
            worker.submit(
                () -> {
                  started.countDown();
                  return failedStorageWrite(() -> insertRawSlot(slotSql, binding));
                });
        try {
          assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
          lifecycle.commit();
        }
        assertThat(inserting.get(20, TimeUnit.SECONDS))
            .isEqualTo(isolation == Connection.TRANSACTION_READ_COMMITTED ? "23514" : "40001");
        slot.rollback();
        assertThat(
                new DraftCommitCoordinatorRepository(local.dsl).readApplicationSlot(local.target))
            .isEmpty();
        assertThat(
                local
                    .dsl
                    .fetchSingle(
                        "SELECT version_state FROM version WHERE id = ?",
                        local.target.gameDesignVersionRowId())
                    .get(0, String.class))
            .isEqualTo("RETIRED");
      }
    }
  }

  @Test
  void rawSlotFirstRejectsLifecycleWriteIncludingStaleSnapshotsWithoutChangingVersionFields()
      throws Exception {
    for (int isolation : storageIsolationLevels()) {
      var local = local();
      var binding = binding(local, "slot-first");
      tx(local)
          .executeWithoutResult(
              ignored -> new DraftCommitCoordinatorRepository(local.dsl).claim(binding));
      var before = versionFields(local);
      try (var slot = storageConnection(local, Connection.TRANSACTION_READ_COMMITTED);
          var lifecycle = storageConnection(local, isolation);
          var worker = java.util.concurrent.Executors.newSingleThreadExecutor()) {
        var lifecycleSql = DSL.using(lifecycle, SQLDialect.POSTGRES);
        lifecycleSql.fetch(
            "SELECT * FROM version WHERE id = ?", local.target.gameDesignVersionRowId());
        insertRawSlot(DSL.using(slot, SQLDialect.POSTGRES), binding);
        var started = new java.util.concurrent.CountDownLatch(1);
        var updating =
            worker.submit(
                () -> {
                  started.countDown();
                  return failedStorageWrite(
                      () ->
                          lifecycleSql.execute(
                              "UPDATE version SET version_state = 'RETIRED', version_state_epoch = version_state_epoch + 1 WHERE id = ?",
                              local.target.gameDesignVersionRowId()));
                });
        try {
          assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
          slot.commit();
        }
        assertThat(updating.get(20, TimeUnit.SECONDS))
            .isEqualTo(isolation == Connection.TRANSACTION_READ_COMMITTED ? "23514" : "40001");
        lifecycle.rollback();
        assertThat(versionFields(local)).isEqualTo(before);
        assertThat(
                new DraftCommitCoordinatorRepository(local.dsl).readApplicationSlot(local.target))
            .isPresent();
      }
    }
  }

  @Test
  void rawSelectionFirstRejectsSlotInsertEvenWhenSlotSnapshotPredatesSelection() throws Exception {
    for (int isolation : storageIsolationLevels()) {
      var local = local();
      var selected = synchronizedStorageSelection(local);
      var later = binding(local, "after-selection");
      tx(local)
          .executeWithoutResult(
              ignored -> new DraftCommitCoordinatorRepository(local.dsl).claim(later));
      var before = versionFields(local);
      try (var slot = storageConnection(local, isolation);
          var selection = storageConnection(local, Connection.TRANSACTION_READ_COMMITTED);
          var worker = java.util.concurrent.Executors.newSingleThreadExecutor()) {
        var slotSql = DSL.using(slot, SQLDialect.POSTGRES);
        slotSql.fetch("SELECT * FROM version WHERE id = ?", local.target.gameDesignVersionRowId());
        insertRawSelection(DSL.using(selection, SQLDialect.POSTGRES), selected);
        var started = new java.util.concurrent.CountDownLatch(1);
        var inserting =
            worker.submit(
                () -> {
                  started.countDown();
                  return failedStorageWrite(() -> insertRawSlot(slotSql, later));
                });
        try {
          assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
          selection.commit();
        }
        assertThat(inserting.get(20, TimeUnit.SECONDS))
            .isEqualTo(isolation == Connection.TRANSACTION_READ_COMMITTED ? "23514" : "40001");
        slot.rollback();
        assertThat(versionFields(local)).isEqualTo(before);
        assertThat(
                new DraftCommitCoordinatorRepository(local.dsl).readApplicationSlot(local.target))
            .isEmpty();
        assertThat(local.dsl.fetchCount(DSL.table("game_design_authored_draft_publish_selection")))
            .isEqualTo(1);
      }
    }
  }

  @Test
  void rawSlotFirstRejectsSelectionEvenWhenSelectionSnapshotPredatesSlot() throws Exception {
    for (int isolation : storageIsolationLevels()) {
      var local = local();
      var selected = synchronizedStorageSelection(local);
      var later = binding(local, "slot-before-selection");
      tx(local)
          .executeWithoutResult(
              ignored -> new DraftCommitCoordinatorRepository(local.dsl).claim(later));
      var before = versionFields(local);
      try (var slot = storageConnection(local, Connection.TRANSACTION_READ_COMMITTED);
          var selection = storageConnection(local, isolation);
          var worker = java.util.concurrent.Executors.newSingleThreadExecutor()) {
        var selectionSql = DSL.using(selection, SQLDialect.POSTGRES);
        selectionSql.fetch(
            "SELECT * FROM version WHERE id = ?", local.target.gameDesignVersionRowId());
        insertRawSlot(DSL.using(slot, SQLDialect.POSTGRES), later);
        var started = new java.util.concurrent.CountDownLatch(1);
        var inserting =
            worker.submit(
                () -> {
                  started.countDown();
                  return failedStorageWrite(() -> insertRawSelection(selectionSql, selected));
                });
        try {
          assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
          slot.commit();
        }
        assertThat(inserting.get(20, TimeUnit.SECONDS))
            .isEqualTo(isolation == Connection.TRANSACTION_READ_COMMITTED ? "23514" : "40001");
        selection.rollback();
        assertThat(versionFields(local)).isEqualTo(before);
        assertThat(
                new DraftCommitCoordinatorRepository(local.dsl).readApplicationSlot(local.target))
            .isPresent();
        assertThat(local.dsl.fetchCount(DSL.table("game_design_authored_draft_publish_selection")))
            .isZero();
      }
    }
  }

  @Test
  void migrationRejectsRetainedNonDraftSlotWithoutChangingHistory() {
    var local = local("57");
    var binding = binding(local, "retained-invalid");
    tx(local)
        .executeWithoutResult(
            ignored -> new DraftCommitCoordinatorRepository(local.dsl).claim(binding));
    insertRawSlot(local.dsl, binding);
    local.dsl.execute(
        "UPDATE version SET version_state = 'RETIRED' WHERE id = ?",
        local.target.gameDesignVersionRowId());
    var before = versionFields(local);
    var retainedSlot =
        local
            .dsl
            .fetchSingle(
                "SELECT to_jsonb(s)::text FROM game_design_draft_commit_application_slot s")
            .get(0, String.class);
    var schema = local.dsl.fetchSingle("SELECT current_schema()").get(0, String.class);
    assertThatThrownBy(
            () ->
                Flyway.configure()
                    .dataSource(Objects.requireNonNull(local.manager.getDataSource()))
                    .schemas(schema)
                    .defaultSchema(schema)
                    .table("flyway_schema_history_game_design_service")
                    .placeholders(Map.of("serviceSchema", schema))
                    .locations("classpath:db/migration")
                    .load()
                    .migrate())
        .hasStackTraceContaining("Retained Draft application slot lacks its exact DRAFT Version");
    assertThat(versionFields(local)).isEqualTo(before);
    assertThat(
            local
                .dsl
                .fetchSingle(
                    "SELECT to_jsonb(s)::text FROM game_design_draft_commit_application_slot s")
                .get(0, String.class))
        .isEqualTo(retainedSlot);
  }

  private int[] storageIsolationLevels() {
    return new int[] {
      Connection.TRANSACTION_READ_COMMITTED,
      Connection.TRANSACTION_REPEATABLE_READ,
      Connection.TRANSACTION_SERIALIZABLE
    };
  }

  private Connection storageConnection(Local local, int isolation) throws SQLException {
    var connection = Objects.requireNonNull(local.manager.getDataSource()).getConnection();
    connection.setTransactionIsolation(isolation);
    connection.setAutoCommit(false);
    DSL.using(connection, SQLDialect.POSTGRES).execute("SET LOCAL lock_timeout = '10s'");
    return connection;
  }

  private String failedStorageWrite(Runnable action) {
    try {
      action.run();
      throw new AssertionError("Conflicting storage write unexpectedly succeeded");
    } catch (org.jooq.exception.DataAccessException failure) {
      Throwable cause = failure;
      while (cause != null) {
        if (cause instanceof SQLException sql) return sql.getSQLState();
        cause = cause.getCause();
      }
      throw failure;
    }
  }

  private String versionFields(Local local) {
    return local
        .dsl
        .fetchSingle(
            "SELECT to_jsonb(v)::text FROM version v WHERE id = ?",
            local.target.gameDesignVersionRowId())
        .get(0, String.class);
  }

  private void insertRawSlot(DSLContext sql, DraftCommitBinding binding) {
    sql.execute(
        "INSERT INTO game_design_draft_commit_application_slot "
            + "(canonical_tenant_id, canonical_version_id, request_id, commit_id) VALUES (?, ?, ?, ?)",
        binding.target().canonicalTenantId(),
        binding.target().canonicalVersionId(),
        binding.requestId(),
        binding.commitId());
  }

  private AuthoredDraftPublishSelection synchronizedStorageSelection(Local local) {
    // Storage-only setup uses actual typed source application/fence, without asserting creator or
    // Account authorization. Both racing writes below bypass the Java coordinator entirely.
    var binding = binding(local, "storage-selected");
    return Objects.requireNonNull(
        tx(local)
            .execute(
                ignored -> {
                  var coordinator = new DraftCommitCoordinatorRepository(local.dsl);
                  coordinator.claim(binding);
                  coordinator.claimApplicationSlot(binding);
                  coordinator.markOwnerInProgress(
                      binding, DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE);
                  var applied = new GameDesignSourceRepository(local.dsl).apply(binding);
                  coordinator.advanceSourceVisibilityFence(
                      binding,
                      new DraftCommitCoordinatorRepository.CoordinatorProof(
                          binding, List.of(applied.ownerOutcome())));
                  coordinator.releaseApplicationSlot(binding);
                  var intent =
                      new AuthoredDraftPublishSelection.PublishIntent(
                          local.target.canonicalTenantId(),
                          local.target.canonicalVersionId(),
                          UUID.randomUUID().toString(),
                          "1",
                          "storage race",
                          binding.requestId(),
                          binding.commitId(),
                          binding.digest());
                  return AuthoredDraftPublishSelection.capture(
                      intent,
                      local.target,
                      coordinator.requireSynchronizedPublicationEvidence(
                          local.target, binding.requestId(), binding.commitId(), binding.digest()));
                }));
  }

  private void insertRawSelection(DSLContext sql, AuthoredDraftPublishSelection selected) {
    var target = selected.target();
    var intent = selected.intent();
    sql.execute(
        "INSERT INTO game_design_authored_draft_publish_selection "
            + "(canonical_tenant_id, canonical_version_id, game_design_version_row_id, "
            + "game_design_version_tenant_key, source_game_row_id, source_game_tenant_key, "
            + "source_provenance_kind, publish_request_id, version_state_epoch, "
            + "selected_commit_request_id, selected_commit_id, selected_commit_digest, selection_digest, selection_json) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        target.canonicalTenantId(),
        target.canonicalVersionId(),
        target.gameDesignVersionRowId(),
        target.gameDesignVersionTenantKey(),
        target.sourceGameRowId(),
        target.sourceGameTenantKey(),
        target.sourceProvenanceKind(),
        intent.publishRequestId(),
        Long.parseLong(intent.expectedVersionStateEpoch()),
        intent.selectedCommitRequestId(),
        intent.selectedCommitId(),
        intent.selectedCommitDigest(),
        selected.digest(),
        selected.canonicalJson());
  }

  private void assertSynchronized(Local local, DraftAuthorizationFenceBinding original) {
    var snapshot =
        new GameDesignSourceRepository(local.dsl)
            .readSynchronized(local.target, original.commitId())
            .orElseThrow();
    assertThat(
            snapshot
                .gameplay()
                .manifest()
                .families()
                .get(GameplayRuleManifest.Family.ADMISSION_TAGS))
        .hasSize(1);
    assertThat(snapshot.gameplay().binding().canonicalBytes())
        .isEqualTo(original.gameDesignBinding());
    var coordinator = new DraftCommitCoordinatorRepository(local.dsl);
    assertThat(coordinator.read(local.target, original.requestId()).orElseThrow().workflowState())
        .isEqualTo(DraftCommitCoordinatorRepository.WorkflowState.SYNCHRONIZED);
    assertThat(coordinator.readApplicationSlot(local.target)).isEmpty();
    assertThat(epoch(local)).isEqualTo("1");
  }

  private String epoch(Local local) {
    return local
        .dsl
        .fetchSingle(
            "SELECT source_epoch FROM game_design_gameplay_rule_head WHERE canonical_version_id = ?",
            local.target.canonicalVersionId())
        .get(0, String.class);
  }

  private GameDesignSourceCommitService service(
      Local local, AccountOriginalDraftOrderClient client, GameDesignSourceRepository sources) {
    return new GameDesignSourceCommitService(
        client,
        new DraftCommitCoordinatorRepository(local.dsl),
        new GameDesignDraftTerminalOutcomeRepository(local.dsl),
        sources,
        local.manager,
        NAMESPACE);
  }

  private AccountControlUiOriginalOrderFixture account(Local local) throws Exception {
    return new AccountControlUiOriginalOrderFixture(
        ACCOUNT_POSTGRES.getJdbcUrl(),
        ACCOUNT_POSTGRES.getUsername(),
        ACCOUNT_POSTGRES.getPassword(),
        REDIS.getHost(),
        REDIS.getMappedPort(6379),
        temporary.resolve(UUID.randomUUID().toString()),
        local.target.canonicalTenantId());
  }

  private DraftCommitBinding binding(Local local, String tag) {
    return DraftCommitBinding.create(
        local.target,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "base-commit-0",
        List.of(
            new DraftCommitBinding.RevisionPayload(
                "0",
                UUID.randomUUID(),
                DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                GameplayRuleSource.upsertPayload(new GameplayRuleManifest.AdmissionTag(tag)))),
        List.of(
            new DraftCommitBinding.AffectedUnit(
                DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                GameplayRuleSource.SCOPE,
                local.target.canonicalVersionId().toString(),
                GameplayRuleSource.SCOPE,
                "effective",
                "0")));
  }

  private Local local() {
    return local(null);
  }

  private Local local(String maximumMigration) {
    var schema = "source_commit_" + UUID.randomUUID().toString().replace("-", "");
    var data =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    data.setSchema(schema);
    var migrations =
        Flyway.configure()
            .dataSource(data)
            .schemas(schema)
            .defaultSchema(schema)
            .table("flyway_schema_history_game_design_service")
            .placeholders(Map.of("serviceSchema", schema))
            .locations("classpath:db/migration");
    if (maximumMigration != null) migrations.target(maximumMigration);
    migrations.load().migrate();
    var dsl = DSL.using(new TransactionAwareDataSourceProxy(data), SQLDialect.POSTGRES);
    var manager = new DataSourceTransactionManager(data);
    var tx = new TransactionTemplate(manager);
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    var game = new Game();
    game.setTenantId(UUID.randomUUID().toString());
    game.setName("Genuine source commit Game");
    game.setDescription("Actual GD repository identity; Account bootstrap trust is stipulated");
    var savedGame =
        Objects.requireNonNull(tx.execute(ignored -> new GameRepository(dsl).save(game)));
    var version = new Version();
    version.setTenantId(savedGame.getTenantId());
    version.setVersionNumber(1);
    version.setVersionState(VersionLifecycleState.DRAFT);
    version.setVersionStateEpoch(1L);
    var saved =
        Objects.requireNonNull(tx.execute(ignored -> new VersionRepository(dsl).save(version)));
    var target =
        new DraftCommitBinding.TargetProof(
            saved.getCanonicalTenantId(),
            saved.getCanonicalVersionId(),
            saved.getId(),
            saved.getTenantId(),
            saved.getIdentitySourceGameRowId(),
            saved.getIdentitySourceGameTenantKey(),
            saved.getIdentitySourceProvenanceKind());
    assertThat(target.canonicalTenantId()).isEqualTo(savedGame.getCanonicalTenantId());
    return new Local(dsl, manager, target);
  }

  private record Local(
      DSLContext dsl,
      DataSourceTransactionManager manager,
      DraftCommitBinding.TargetProof target) {}

  private void settleOriginal(
      AccountControlUiOriginalOrderFixture account,
      Local local,
      DraftAuthorizationFenceBinding original)
      throws Exception {
    settleOriginal(account, local, original, "COMMITTED");
  }

  private void settleOriginal(
      AccountControlUiOriginalOrderFixture account,
      Local local,
      DraftAuthorizationFenceBinding original,
      String expectedSettlementOutcome)
      throws Exception {
    var pki = new TestPki(temporary.resolve(UUID.randomUUID().toString()));
    var server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .maxInboundMessageSize(AccountOriginalDraftOrderGrpcCodec.MAX_WIRE_BYTES)
            .sslContext(
                GrpcSslContexts.forServer(
                        pki.gameDesignServer.certificate.toFile(),
                        pki.gameDesignServer.key.toFile())
                    .trustManager(pki.ca.toFile())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(
                ServerInterceptors.intercept(
                    new GameDesignDraftTerminalReadGrpcService(
                        new GameDesignDraftTerminalOutcomeRepository(local.dsl), NAMESPACE),
                    new GrpcPeerIdentityInterceptor()))
            .build()
            .start();
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setGameDesignService("127.0.0.1:" + server.getPort());
    var unusedWorld =
        org.mockito.Mockito.mock(
            net.firedevops.firemud.common.authoring.WorldDraftTerminalReadClient.class);
    try (var client =
        new net.firedevops.firemud.common.authoring.GameDesignDraftTerminalReadClient(
            endpoints, pki.accountClient.properties(pki.ca), new GrpcChannelFactory(), NAMESPACE)) {
      client.init();
      account.reconcileOriginalDraft(
          original, client, unusedWorld, NAMESPACE, expectedSettlementOutcome);
      org.mockito.Mockito.verifyNoInteractions(unusedWorld);
    } finally {
      server.shutdownNow();
      if (!server.awaitTermination(5, TimeUnit.SECONDS))
        throw new IllegalStateException("Test server did not stop");
    }
  }

  private Remote remote(AccountControlUiOriginalOrderFixture account) throws Exception {
    var pki = new TestPki(temporary.resolve(UUID.randomUUID().toString()));
    var calls = new AtomicInteger();
    var server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .maxInboundMessageSize(AccountOriginalDraftOrderGrpcCodec.MAX_WIRE_BYTES)
            .maxInboundMetadataSize(AccountOriginalDraftOrderGrpcCodec.MAX_METADATA_BYTES)
            .sslContext(
                GrpcSslContexts.forServer(
                        pki.accountServer.certificate.toFile(), pki.accountServer.key.toFile())
                    .trustManager(pki.ca.toFile())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(
                ServerInterceptors.intercept(
                    account.originalDraftOrderProducer(NAMESPACE),
                    new io.grpc.ServerInterceptor() {
                      @Override
                      public <ReqT, RespT> io.grpc.ServerCall.Listener<ReqT> interceptCall(
                          io.grpc.ServerCall<ReqT, RespT> call,
                          io.grpc.Metadata headers,
                          io.grpc.ServerCallHandler<ReqT, RespT> next) {
                        calls.incrementAndGet();
                        return next.startCall(call, headers);
                      }
                    },
                    new GrpcPeerIdentityInterceptor()))
            .build()
            .start();
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setAccountService("127.0.0.1:" + server.getPort());
    var client =
        new AccountOriginalDraftOrderClient(
            endpoints,
            pki.gameDesignClient.properties(pki.ca),
            new GrpcChannelFactory(),
            NAMESPACE);
    try {
      client.init();
      return new Remote(server, client, calls);
    } catch (Exception failure) {
      server.shutdownNow();
      throw failure;
    }
  }

  private record Remote(Server server, AccountOriginalDraftOrderClient client, AtomicInteger calls)
      implements AutoCloseable {
    @Override
    public void close() throws Exception {
      client.close();
      server.shutdownNow();
      if (!server.awaitTermination(5, TimeUnit.SECONDS))
        throw new IllegalStateException("Test server did not stop");
    }
  }

  /** Ephemeral certificate authority, never a deployed trust root or live workload credential. */
  private static final class TestPki {
    final Path ca;
    final TestIdentity accountServer, gameDesignClient, accountClient, gameDesignServer;

    TestPki(Path root) throws Exception {
      Files.createDirectories(root);
      var generator = java.security.KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      var caKeys = generator.generateKeyPair();
      var name = new org.bouncycastle.asn1.x500.X500Name("CN=Test-only Account World proof CA");
      var now = java.time.Instant.now();
      var builder =
          new org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
              name,
              java.math.BigInteger.ONE,
              java.util.Date.from(now.minusSeconds(60)),
              java.util.Date.from(now.plusSeconds(3600)),
              name,
              caKeys.getPublic());
      builder.addExtension(
          org.bouncycastle.asn1.x509.Extension.basicConstraints,
          true,
          new org.bouncycastle.asn1.x509.BasicConstraints(true));
      builder.addExtension(
          org.bouncycastle.asn1.x509.Extension.keyUsage,
          true,
          new org.bouncycastle.asn1.x509.KeyUsage(org.bouncycastle.asn1.x509.KeyUsage.keyCertSign));
      var signer =
          new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA")
              .build(caKeys.getPrivate());
      var caCert =
          new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
              .getCertificate(builder.build(signer));
      ca = pem(root.resolve("ca.pem"), caCert);
      accountServer = issue(root, "account-service", true, caKeys, caCert);
      gameDesignClient = issue(root, "game-design-service", false, caKeys, caCert);
      accountClient = issue(root, "account-service", false, caKeys, caCert);
      gameDesignServer = issue(root, "game-design-service", true, caKeys, caCert);
    }

    private static TestIdentity issue(
        Path root,
        String service,
        boolean server,
        java.security.KeyPair caKeys,
        java.security.cert.X509Certificate caCert)
        throws Exception {
      var generator = java.security.KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      var keys = generator.generateKeyPair();
      var now = java.time.Instant.now();
      var builder =
          new org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
              caCert,
              new java.math.BigInteger(120, new java.security.SecureRandom()),
              java.util.Date.from(now.minusSeconds(60)),
              java.util.Date.from(now.plusSeconds(3600)),
              new org.bouncycastle.asn1.x500.X500Name("CN=" + service),
              keys.getPublic());
      builder.addExtension(
          org.bouncycastle.asn1.x509.Extension.basicConstraints,
          true,
          new org.bouncycastle.asn1.x509.BasicConstraints(false));
      builder.addExtension(
          org.bouncycastle.asn1.x509.Extension.keyUsage,
          true,
          new org.bouncycastle.asn1.x509.KeyUsage(
              org.bouncycastle.asn1.x509.KeyUsage.digitalSignature
                  | org.bouncycastle.asn1.x509.KeyUsage.keyEncipherment));
      builder.addExtension(
          org.bouncycastle.asn1.x509.Extension.extendedKeyUsage,
          false,
          new org.bouncycastle.asn1.x509.ExtendedKeyUsage(
              server
                  ? org.bouncycastle.asn1.x509.KeyPurposeId.id_kp_serverAuth
                  : org.bouncycastle.asn1.x509.KeyPurposeId.id_kp_clientAuth));
      builder.addExtension(
          org.bouncycastle.asn1.x509.Extension.subjectAlternativeName,
          false,
          new org.bouncycastle.asn1.x509.GeneralNames(
              new org.bouncycastle.asn1.x509.GeneralName[] {
                new org.bouncycastle.asn1.x509.GeneralName(
                    org.bouncycastle.asn1.x509.GeneralName.uniformResourceIdentifier,
                    "spiffe://firemud/ns/" + NAMESPACE + "/sa/" + service),
                new org.bouncycastle.asn1.x509.GeneralName(
                    org.bouncycastle.asn1.x509.GeneralName.dNSName, "localhost"),
                new org.bouncycastle.asn1.x509.GeneralName(
                    org.bouncycastle.asn1.x509.GeneralName.iPAddress, "127.0.0.1")
              }));
      var certificate =
          new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
              .getCertificate(
                  builder.build(
                      new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA")
                          .build(caKeys.getPrivate())));
      String prefix = service + (server ? "-server" : "-client");
      return new TestIdentity(
          pem(root.resolve(prefix + ".crt"), certificate),
          pem(root.resolve(prefix + ".key"), keys.getPrivate()));
    }

    private static Path pem(Path path, Object value) throws Exception {
      try (var writer =
          new org.bouncycastle.openssl.jcajce.JcaPEMWriter(Files.newBufferedWriter(path))) {
        writer.writeObject(value);
      }
      return path;
    }
  }

  private record TestIdentity(Path certificate, Path key) {
    CommonGrpcClientProperties properties(Path ca) {
      var result = new CommonGrpcClientProperties();
      result.setPlaintext(false);
      result.setCertChain(certificate.toString());
      result.setPrivateKey(key.toString());
      result.setCaCert(ca.toString());
      return result;
    }
  }
}
