package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.grpc.Context;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeSourceReadScope;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSelectedSource;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSourceReadClient;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSourceReadEvidence;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSourceRevision;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.test.TestContainerImages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real creator issuance/currentness, Account transactions, V109/V112 and source guards. GD source
 * transport and the original author's terminal are explicit upstream fixtures, not composed
 * production or mTLS proof. No GL terminal, settlement, digest or activation is claimed.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class AccountGameLogicIntakeAuthorizationPostgresIntegrationTest {
  private static final Network NETWORK = Network.newNetwork();

  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Container
  static final GenericContainer<?> redis =
      new GenericContainer<>(TestContainerImages.redis())
          .withNetwork(NETWORK)
          .withNetworkAliases("intake-primary")
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
  static final GenericContainer<?> replica =
      new GenericContainer<>(TestContainerImages.redis())
          .withNetwork(NETWORK)
          .dependsOn(redis)
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
              "intake-primary",
              "6379");

  @TempDir Path temporary;

  @Test
  void distinctPendingIntakeRequiresSettledAuthorAndBlocksRealSourceWritersAndDisclosure()
      throws Exception {
    try (var fixture =
        new AccountControlUiOriginalOrderFixture(
            postgres.getJdbcUrl(),
            postgres.getUsername(),
            postgres.getPassword(),
            redis.getHost(),
            redis.getMappedPort(6379),
            temporary)) {
      var issued = fixture.issueCreator();
      var f = issued.sources();
      var selected = selected(f.tenant);
      var captured = f.tx(() -> f.authority.captureInitial(f.tenant, issued.environment()));
      var original =
          new DraftAuthorizationFenceBinding(
                  UUID.randomUUID(),
                  selected.requestId(),
                  selected.commitId(),
                  UUID.randomUUID(),
                  f.account.getAccountUuid(),
                  f.tenant,
                  selected.target().canonicalVersionId(),
                  selected.baseCommitId(),
                  "0",
                  selected.canonicalBytes(),
                  selected.canonicalBytes(),
                  selected.digest(),
                  captured.sources())
              .withRequiredOwners();
      issued.actors().claimOriginalDraft(issued.compact(), original, issued.environment());
      var source = source(selected);
      var gd = mock(GameplayRuleSourceReadClient.class);
      doAnswer(call -> new GameplayRuleSourceReadEvidence(call.getArgument(0), source))
          .when(gd)
          .read(any());
      var repository = new AccountGameLogicIntakeAuthorizationRepository(f.dsl);
      var service =
          new AccountGameLogicIntakeAuthorizationService(
              issued.actors(), f.fences, repository, gd, f.manager, "test");
      var requestId = UUID.randomUUID();

      assertThatThrownBy(
              () ->
                  asGameDesign(
                      () ->
                          service.authorize(
                              issued.compact(), requestId, selected, issued.environment())))
          .isInstanceOf(IllegalStateException.class);
      verifyNoInteractions(gd);
      assertThat(
              f.dsl.fetchCount(org.jooq.impl.DSL.table("account_game_logic_intake_authorizations")))
          .isZero();
      f.tx(
          () -> {
            f.fences.recordOwnerReadback(
                original,
                new DraftAuthorizationFenceBinding.OwnerReadback(
                    DraftAuthorizationFenceBinding.Owner.GAME_DESIGN,
                    DraftAuthorizationFenceBinding.Outcome.COMMITTED,
                    original.operationId(),
                    original.commitId(),
                    original.fenceId(),
                    original.inputDigest(),
                    original.canonicalBytes(),
                    new byte[] {1}));
            return null;
          });
      var order =
          asGameDesign(
              () -> service.authorize(issued.compact(), requestId, selected, issued.environment()));
      assertThat(order.source()).isEqualTo(source);
      assertThat(order.sources()).hasSize(captured.sources().size());
      assertThat(
              asGameDesign(
                      () ->
                          service.authorize(
                              issued.compact(), requestId, selected, issued.environment()))
                  .canonicalBytes())
          .isEqualTo(order.canonicalBytes());
      String genesis =
          GameplayRuleManifest.tree(source.snapshotJson()).path("genesisReceiptId").textValue();
      var substituted =
          new GameplayRuleSelectedSource(
              source.snapshotJson().replace(genesis, UUID.randomUUID().toString()));
      doAnswer(call -> new GameplayRuleSourceReadEvidence(call.getArgument(0), substituted))
          .when(gd)
          .read(any());
      clearInvocations(gd);
      assertThat(
              asGameDesign(
                      () ->
                          service.authorize(
                              issued.compact(), requestId, selected, issued.environment()))
                  .canonicalBytes())
          .isEqualTo(order.canonicalBytes());
      verifyNoInteractions(gd);
      doAnswer(call -> new GameplayRuleSourceReadEvidence(call.getArgument(0), source))
          .when(gd)
          .read(any());

      UUID rolledBackRequest = UUID.randomUUID();
      assertThatThrownBy(
              () ->
                  issued
                      .actors()
                      .withCurrent(
                          issued.compact(),
                          f.tenant,
                          issued.environment(),
                          current -> {
                            f.fences.lockProducerSourcesNowait(current.source().sources());
                            repository.reserveSourceRead(
                                rolledBackRequest,
                                selected,
                                current,
                                "test",
                                () ->
                                    f.fences.requireGameLogicIntakeAdmission(
                                        current.source().sources(), selected));
                            throw new IllegalStateException("rollback before owner commit");
                          }))
          .isInstanceOf(IllegalStateException.class);
      assertThat(
              f.dsl.fetchOne(
                  "SELECT 1 FROM account_game_logic_intake_source_read_reservations WHERE intake_request_id = ?",
                  rolledBackRequest))
          .isNull();
      f.tx(
          () -> {
            repository.readHeld(order);
            return null;
          });
      assertThatThrownBy(
              () ->
                  f.tx(
                      () -> {
                        f.fences.requireDisclosurePreparation(order.sources());
                        return null;
                      }))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("intake");

      // Only the distinct intake is pending; original Draft settlement is already COMMITTED.
      assertThat(f.tx(() -> f.fences.readSettlement(original)))
          .isEqualTo(DraftAuthorizationFenceRepository.Settlement.COMMITTED);
      assertThatThrownBy(
              () ->
                  f.tx(
                      () -> {
                        f.dsl.execute(
                            "UPDATE accounts SET role = 'admin' WHERE id = ?", f.account.getId());
                        return null;
                      }))
          .hasStackTraceContaining("Distinct Game Logic intake remains pending");

      var change =
          new DraftAuthorizationFenceRepository.SourceChange(
              UUID.randomUUID(), order.sources(), new byte[] {2});
      assertThat(f.tx(() -> f.fences.requestSourceChange(change))).isFalse();
      assertThat(f.tx(() -> f.fences.sourceMutationPermitted(change))).isFalse();
      assertThat(f.tx(() -> f.fences.sourceAbortPermitted(change))).isFalse();
      f.tx(
          () -> {
            repository.readHeld(order);
            return null;
          });

      assertThatThrownBy(
              () ->
                  f.tx(
                      () -> {
                        f.dsl.execute(
                            "UPDATE account_game_logic_intake_authorizations SET source_digest = ? WHERE operation_id = ?",
                            "sha256:" + "0".repeat(64),
                            order.operationId());
                        return null;
                      }))
          .hasStackTraceContaining("immutable");
      assertThatThrownBy(
              () ->
                  asGameDesign(
                      () ->
                          service.authorize(
                              "invalid-credential",
                              UUID.randomUUID(),
                              selected,
                              issued.environment())))
          .isInstanceOf(RuntimeException.class);
    }
  }

  @Test
  void lostFirstResponseRecoversByOriginalRequestAfterExpiryAndAbortReleasesParticipation()
      throws Exception {
    var offsetSeconds = new java.util.concurrent.atomic.AtomicLong();
    var clock =
        new Clock() {
          @Override
          public ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          @Override
          public Clock withZone(ZoneId zone) {
            return this;
          }

          @Override
          public Instant instant() {
            return Instant.now().plusSeconds(offsetSeconds.get());
          }
        };
    try (var fixture =
        new AccountControlUiOriginalOrderFixture(
            postgres.getJdbcUrl(),
            postgres.getUsername(),
            postgres.getPassword(),
            redis.getHost(),
            redis.getMappedPort(6379),
            temporary,
            UUID.randomUUID(),
            clock)) {
      var issued = fixture.issueCreator();
      var f = issued.sources();
      var selected = selected(f.tenant);
      settleOriginal(issued, selected);
      var repository = new AccountGameLogicIntakeAuthorizationRepository(f.dsl);
      var gd = mock(GameplayRuleSourceReadClient.class);
      var service =
          new AccountGameLogicIntakeAuthorizationService(
              issued.actors(), f.fences, repository, gd, f.manager, "test");
      var requestId = UUID.randomUUID();
      doAnswer(
              call -> {
                assertThat(
                        org.springframework.transaction.support.TransactionSynchronizationManager
                            .isActualTransactionActive())
                    .isFalse();
                var request = (GameplayRuleSourceReadEvidence.Request) call.getArgument(0);
                var scope = ((GameplayRuleSourceReadEvidence.Preliminary) request.proof()).scope();
                var retainedScope =
                    java.util.Objects.requireNonNull(
                        f.dsl.fetchOne(
                            "SELECT scope_bytes FROM account_game_logic_intake_source_read_reservations WHERE operation_id = ?",
                            scope.operationId()),
                        "Reserved source scope row required");
                assertThat(retainedScope.get("scope_bytes", byte[].class))
                    .isEqualTo(scope.canonicalBytes());
                throw io.grpc.Status.UNAVAILABLE
                    .withDescription("test-only lost source response")
                    .asRuntimeException();
              })
          .when(gd)
          .read(any());
      assertThatThrownBy(
              () ->
                  asGameDesign(
                      () ->
                          service.authorize(
                              issued.compact(), requestId, selected, issued.environment())))
          .isInstanceOf(io.grpc.StatusRuntimeException.class);
      var recovered = asGameDesign(() -> service.recover(issued.compact(), requestId, selected));
      assertThat(recovered.state())
          .isEqualTo(AccountGameLogicIntakeSourceReadRecovery.State.RESERVED);
      assertThat(
              asGameDesign(
                      () ->
                          service.reserveSourceRead(
                              issued.compact(), requestId, selected, issued.environment()))
                  .canonicalBytes())
          .isEqualTo(recovered.scope().canonicalBytes());
      doAnswer(
              call -> {
                throw io.grpc.Status.PERMISSION_DENIED.asRuntimeException();
              })
          .when(gd)
          .read(any());
      assertThatThrownBy(
              () ->
                  asGameDesign(
                      () ->
                          service.authorize(
                              issued.compact(), requestId, selected, issued.environment())))
          .isInstanceOf(io.grpc.StatusRuntimeException.class)
          .hasMessageContaining("PERMISSION_DENIED");
      assertThat(asGameDesign(() -> service.recover(issued.compact(), requestId, selected)).scope())
          .isEqualTo(recovered.scope());
      var originalSources =
          f.tx(() -> f.authority.captureInitial(f.tenant, issued.environment())).sources();
      assertThatThrownBy(
              () ->
                  f.tx(
                      () -> {
                        f.fences.requireDisclosurePreparation(originalSources);
                        return null;
                      }))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("intake");
      assertThatThrownBy(
              () ->
                  f.tx(
                      () -> {
                        f.dsl.execute(
                            "UPDATE accounts SET role = 'admin' WHERE id = ?", f.account.getId());
                        return null;
                      }))
          .hasStackTraceContaining("Preliminary Game Logic source read remains pending");
      assertThatThrownBy(
              () ->
                  asGameDesign(
                      () -> service.recover("different-original-credential", requestId, selected)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("conflicts");
      assertThatThrownBy(
              () ->
                  asGameDesign(
                      () -> service.recover(issued.compact(), requestId, selected(f.tenant))))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("conflicts");
      clearInvocations(gd);
      offsetSeconds.set(600);
      assertThat(asGameDesign(() -> service.recover(issued.compact(), requestId, selected)).scope())
          .isEqualTo(recovered.scope());
      assertThatThrownBy(
              () ->
                  asGameDesign(
                      () ->
                          service.authorize(
                              issued.compact(), requestId, selected, issued.environment())))
          .isInstanceOf(RuntimeException.class);
      verifyNoInteractions(gd);
      var aborted =
          asGameDesign(() -> service.abortSourceRead(issued.compact(), requestId, selected));
      assertThat(aborted.state()).isEqualTo(AccountGameLogicIntakeSourceReadRecovery.State.ABORTED);
      assertThat(asGameDesign(() -> service.abortSourceRead(recovered.scope()))).isEqualTo(aborted);
      f.tx(
          () -> {
            f.fences.requireDisclosurePreparation(originalSources);
            return null;
          });
      f.tx(
          () -> {
            f.dsl.execute("UPDATE accounts SET role = 'admin' WHERE id = ?", f.account.getId());
            return null;
          });
      assertThat(
              f.dsl.fetchOne(
                  "SELECT binding FROM account_game_logic_intake_authorizations WHERE operation_id = ?",
                  recovered.scope().operationId()))
          .isNull();
    }
  }

  @Test
  void abortWhileSourceReadIsInFlightExcludesLateFinalizationAndExactFinalRetrySurvivesExpiry()
      throws Exception {
    var offsetSeconds = new java.util.concurrent.atomic.AtomicLong();
    var clock =
        new Clock() {
          @Override
          public ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          @Override
          public Clock withZone(ZoneId zone) {
            return this;
          }

          @Override
          public Instant instant() {
            return Instant.now().plusSeconds(offsetSeconds.get());
          }
        };
    try (var fixture =
        new AccountControlUiOriginalOrderFixture(
            postgres.getJdbcUrl(),
            postgres.getUsername(),
            postgres.getPassword(),
            redis.getHost(),
            redis.getMappedPort(6379),
            temporary,
            UUID.randomUUID(),
            clock)) {
      var issued = fixture.issueCreator();
      var f = issued.sources();
      var selected = selected(f.tenant);
      settleOriginal(issued, selected);
      var source = source(selected);
      var repository = new AccountGameLogicIntakeAuthorizationRepository(f.dsl);
      var gd = mock(GameplayRuleSourceReadClient.class);
      var service =
          new AccountGameLogicIntakeAuthorizationService(
              issued.actors(), f.fences, repository, gd, f.manager, "test");
      var arrived = new java.util.concurrent.CountDownLatch(1);
      var release = new java.util.concurrent.CountDownLatch(1);
      doAnswer(
              call -> {
                arrived.countDown();
                if (!release.await(20, java.util.concurrent.TimeUnit.SECONDS))
                  throw new IllegalStateException("Source response barrier timed out");
                return new GameplayRuleSourceReadEvidence(call.getArgument(0), source);
              })
          .when(gd)
          .read(any());
      var requestId = UUID.randomUUID();
      var inFlight =
          java.util.concurrent.CompletableFuture.supplyAsync(
              () ->
                  asGameDesign(
                      () ->
                          service.authorize(
                              issued.compact(), requestId, selected, issued.environment())));
      assertThat(arrived.await(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      var aborted =
          asGameDesign(() -> service.abortSourceRead(issued.compact(), requestId, selected));
      release.countDown();
      assertThatThrownBy(() -> inFlight.get(20, java.util.concurrent.TimeUnit.SECONDS))
          .hasRootCauseMessage("Original source-read scope aborted");
      assertThat(aborted.state()).isEqualTo(AccountGameLogicIntakeSourceReadRecovery.State.ABORTED);
      assertThat(
              f.dsl.fetchOne(
                  "SELECT binding FROM account_game_logic_intake_authorizations WHERE operation_id = ?",
                  aborted.scope().operationId()))
          .isNull();
      doAnswer(call -> new GameplayRuleSourceReadEvidence(call.getArgument(0), source))
          .when(gd)
          .read(any());
      var finalRequest = UUID.randomUUID();
      var order =
          asGameDesign(
              () ->
                  service.authorize(
                      issued.compact(), finalRequest, selected, issued.environment()));
      var recovery = asGameDesign(() -> service.recover(issued.compact(), finalRequest, selected));
      assertThat(order.operationId()).isEqualTo(recovery.scope().operationId());
      assertThat(order.fenceId()).isEqualTo(recovery.scope().fenceId());
      var readOwner =
          new AccountGameLogicIntakeSourceReadService(
              repository,
              mock(AccountControlUiCoordination.class),
              f.manager,
              Clock.systemUTC(),
              "test");
      assertThat(
              asGameDesign(
                      () ->
                          readOwner.readFinalizedIntake(
                              order,
                              "spiffe://firemud/ns/test/sa/game-logic-service",
                              GameLogicIntakeSourceReadScope.PURPOSE))
                  .canonicalBytes())
          .isEqualTo(order.canonicalBytes());
      assertThatThrownBy(
              () ->
                  f.tx(
                      () -> {
                        f.dsl.execute(
                            "INSERT INTO account_game_logic_intake_source_read_aborts (operation_id) VALUES (?)",
                            order.operationId());
                        return null;
                      }))
          .hasStackTraceContaining("Finalized intake cannot be preliminarily aborted");
      assertThat(asGameDesign(() -> service.abortSourceRead(recovery.scope())).state())
          .isEqualTo(AccountGameLogicIntakeSourceReadRecovery.State.FINALIZED);
      offsetSeconds.set(600);
      clearInvocations(gd);
      assertThat(
              asGameDesign(
                      () ->
                          service.authorize(
                              issued.compact(), finalRequest, selected, issued.environment()))
                  .canonicalBytes())
          .isEqualTo(order.canonicalBytes());
      verifyNoInteractions(gd);
    }
  }

  private static void settleOriginal(
      AccountControlUiOriginalOrderFixture.IssuedCreator issued, DraftCommitBinding selected) {
    var f = issued.sources();
    var captured = f.tx(() -> f.authority.captureInitial(f.tenant, issued.environment()));
    var original =
        new DraftAuthorizationFenceBinding(
                UUID.randomUUID(),
                selected.requestId(),
                selected.commitId(),
                UUID.randomUUID(),
                f.account.getAccountUuid(),
                f.tenant,
                selected.target().canonicalVersionId(),
                selected.baseCommitId(),
                "0",
                selected.canonicalBytes(),
                selected.canonicalBytes(),
                selected.digest(),
                captured.sources())
            .withRequiredOwners();
    issued.actors().claimOriginalDraft(issued.compact(), original, issued.environment());
    f.tx(
        () -> {
          f.fences.recordOwnerReadback(
              original,
              new DraftAuthorizationFenceBinding.OwnerReadback(
                  DraftAuthorizationFenceBinding.Owner.GAME_DESIGN,
                  DraftAuthorizationFenceBinding.Outcome.COMMITTED,
                  original.operationId(),
                  original.commitId(),
                  original.fenceId(),
                  original.inputDigest(),
                  original.canonicalBytes(),
                  new byte[] {1}));
          return null;
        });
  }

  @Test
  void sourceReadbackProvesExactCurrentScopeAndChangedAuthorityBetweenPhasesCannotFinalize()
      throws Exception {
    try (var fixture =
        new AccountControlUiOriginalOrderFixture(
            postgres.getJdbcUrl(),
            postgres.getUsername(),
            postgres.getPassword(),
            redis.getHost(),
            redis.getMappedPort(6379),
            temporary)) {
      var issued = fixture.issueCreator();
      var f = issued.sources();
      var selected = selected(f.tenant);
      settleOriginal(issued, selected);
      var repository = new AccountGameLogicIntakeAuthorizationRepository(f.dsl);
      var registry = mock(AccountControlUiCoordination.class);
      doAnswer(
              call -> {
                assertThat(
                        org.springframework.transaction.support.TransactionSynchronizationManager
                            .isActualTransactionActive())
                    .isFalse();
                var retainedIssuer =
                    java.util.Objects.requireNonNull(
                        f.dsl.fetchOne(
                            "SELECT active_registry FROM account_control_ui_issuance_operations WHERE token_hash = ?",
                            call.getArgument(0, String.class)),
                        "Original active registry row required");
                return retainedIssuer.get("active_registry", byte[].class);
              })
          .when(registry)
          .readActive(any());
      var owner =
          new AccountGameLogicIntakeSourceReadService(
              repository, registry, f.manager, Clock.systemUTC(), "test");
      var gd = mock(GameplayRuleSourceReadClient.class);
      var service =
          new AccountGameLogicIntakeAuthorizationService(
              issued.actors(), f.fences, repository, gd, f.manager, "test");
      var request = UUID.randomUUID();
      var scope =
          asGameDesign(
              () ->
                  service.reserveSourceRead(
                      issued.compact(), request, selected, issued.environment()));
      assertThat(
              asGameDesign(
                      () -> owner.readSourceScope(scope, scope.intendedReader(), scope.purpose()))
                  .canonicalBytes())
          .isEqualTo(scope.canonicalBytes());
      assertThatThrownBy(
              () ->
                  asGameDesign(
                      () ->
                          owner.readSourceScope(
                              scope,
                              "spiffe://firemud/ns/test/sa/game-logic-service",
                              scope.purpose())))
          .isInstanceOf(io.grpc.StatusRuntimeException.class);
      assertThatThrownBy(
              () ->
                  asGameDesign(
                      () -> owner.readSourceScope(scope, scope.intendedReader(), "PUBLICATION")))
          .isInstanceOf(io.grpc.StatusRuntimeException.class);
      var changed =
          new GameLogicIntakeSourceReadScope(
              scope.targetNamespace(),
              scope.operationId(),
              scope.fenceId(),
              scope.intakeRequestId(),
              UUID.randomUUID(),
              scope.selected(),
              scope.intendedReader(),
              scope.purpose());
      assertThatThrownBy(
              () ->
                  asGameDesign(
                      () ->
                          owner.readSourceScope(
                              changed, changed.intendedReader(), changed.purpose())))
          .isInstanceOf(IllegalArgumentException.class);
      var expiredReader =
          new AccountGameLogicIntakeSourceReadService(
              repository,
              registry,
              f.manager,
              Clock.offset(Clock.systemUTC(), java.time.Duration.ofMinutes(10)),
              "test");
      assertThatThrownBy(
              () ->
                  asGameDesign(
                      () ->
                          expiredReader.readSourceScope(
                              scope, scope.intendedReader(), scope.purpose())))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("current held");
      // Direct SQL cannot establish a changed purpose even with exact committed issuance bytes.
      var changedPurpose = new java.io.ByteArrayOutputStream();
      DraftAuthorizationFenceBinding.frame(changedPurpose, GameLogicIntakeSourceReadScope.SCHEMA);
      DraftAuthorizationFenceBinding.frame(changedPurpose, scope.targetNamespace());
      var badOperation = UUID.randomUUID();
      var badFence = UUID.randomUUID();
      var badRequest = UUID.randomUUID();
      for (var id : List.of(badOperation, badFence, badRequest, scope.actorAccountId()))
        DraftAuthorizationFenceBinding.frame(changedPurpose, id.toString());
      DraftAuthorizationFenceBinding.frame(changedPurpose, selected.canonicalBytes());
      DraftAuthorizationFenceBinding.frame(changedPurpose, selected.digest());
      DraftAuthorizationFenceBinding.frame(changedPurpose, scope.intendedReader());
      DraftAuthorizationFenceBinding.frame(changedPurpose, "PUBLICATION");
      var malformed = changedPurpose.toByteArray();
      assertThatThrownBy(
              () ->
                  f.tx(
                      () -> {
                        f.dsl.execute(
                            "INSERT INTO account_game_logic_intake_source_read_reservations "
                                + "(operation_id, fence_id, intake_request_id, actor_account_uuid, tenant_uuid, version_uuid, scope_bytes, scope_digest, "
                                + "issuance_operation_id, issuance_fence, source_payload, issuance_bundle, outbox_checkpoints) "
                                + "SELECT ?, ?, ?, actor_account_uuid, tenant_uuid, version_uuid, ?, ?, issuance_operation_id, issuance_fence, source_payload, issuance_bundle, outbox_checkpoints "
                                + "FROM account_game_logic_intake_source_read_reservations WHERE operation_id = ?",
                            badOperation,
                            badFence,
                            badRequest,
                            malformed,
                            DraftAuthorizationFenceBinding.digest(malformed),
                            scope.operationId());
                        f.dsl.execute(
                            "INSERT INTO account_game_logic_intake_source_read_sources SELECT ?, source_key, source_evidence FROM account_game_logic_intake_source_read_sources WHERE operation_id = ?",
                            badOperation,
                            scope.operationId());
                        return null;
                      }))
          .hasStackTraceContaining("Invalid preliminary purpose");
      assertThat(
              f.dsl.fetchOne(
                  "SELECT operation_id FROM account_game_logic_intake_source_read_reservations WHERE operation_id = ?",
                  badOperation))
          .isNull();
      doAnswer(
              call -> {
                var sourceRequest = (GameplayRuleSourceReadEvidence.Request) call.getArgument(0);
                assertThat(
                        ((GameplayRuleSourceReadEvidence.Preliminary) sourceRequest.proof())
                            .scope())
                    .isEqualTo(scope);
                owner.readSourceScope(scope, scope.intendedReader(), scope.purpose());
                f.boundary.set(
                    AccountControlUiOwnerSourcesFixture.testBoundary(
                        "changed-current-environment"));
                return new GameplayRuleSourceReadEvidence(sourceRequest, source(selected));
              })
          .when(gd)
          .read(any());
      assertThatThrownBy(
              () ->
                  asGameDesign(
                      () ->
                          service.authorize(
                              issued.compact(), request, selected, issued.environment())))
          .isInstanceOf(RuntimeException.class);
      assertThat(asGameDesign(() -> service.recover(issued.compact(), request, selected)).state())
          .isEqualTo(AccountGameLogicIntakeSourceReadRecovery.State.RESERVED);
      assertThat(
              f.dsl.fetchOne(
                  "SELECT operation_id FROM account_game_logic_intake_authorizations WHERE operation_id = ?",
                  scope.operationId()))
          .isNull();
      assertThat(
              asGameDesign(() -> service.abortSourceRead(issued.compact(), request, selected))
                  .state())
          .isEqualTo(AccountGameLogicIntakeSourceReadRecovery.State.ABORTED);
    }
  }

  private static DraftCommitBinding selected(UUID tenant) {
    var target =
        new DraftCommitBinding.TargetProof(
            tenant, UUID.randomUUID(), 17, "private", 11, "private", "NEW_GAME_ROW");
    return DraftCommitBinding.create(
        target,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "genesis",
        List.of(
            new DraftCommitBinding.RevisionPayload(
                "0",
                UUID.randomUUID(),
                DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                GameplayRuleSourceRevision.upsertPayload(
                    new GameplayRuleManifest.AdmissionTag("gameplay")))),
        List.of(
            new DraftCommitBinding.AffectedUnit(
                DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                "GAMEPLAY_RULE_SET",
                target.canonicalVersionId().toString(),
                "GAMEPLAY_RULE_SET",
                "effective",
                "0")));
  }

  private static GameplayRuleSelectedSource source(DraftCommitBinding selected) {
    var definition = new GameplayRuleManifest.AdmissionTag("gameplay");
    Map<GameplayRuleManifest.Family, List<GameplayRuleManifest.Definition>> families =
        new EnumMap<>(GameplayRuleManifest.Family.class);
    for (var family : GameplayRuleManifest.Family.values()) families.put(family, new ArrayList<>());
    families.get(definition.family()).add(definition);
    var manifest = new GameplayRuleManifest(families);
    return new GameplayRuleSelectedSource(
        GameplayRuleManifest.canonical(
            Map.of(
                "schema",
                "game-design-gameplay-rule-source-snapshot/v1",
                "bindingJson",
                selected.canonicalJson(),
                "bindingDigest",
                selected.digest(),
                "sourceEpoch",
                "1",
                "inheritedCommitId",
                "",
                "genesisReceiptId",
                UUID.randomUUID().toString(),
                "manifestJson",
                manifest.canonicalJson(),
                "entries",
                List.of(
                    Map.of(
                        "family",
                        definition.family().name(),
                        "definitionJson",
                        GameplayRuleManifest.canonical(definition),
                        "sourceBindingJson",
                        selected.canonicalJson(),
                        "sourceBindingDigest",
                        selected.digest(),
                        "revisionOrder",
                        "0",
                        "revisionId",
                        selected.revisions().getFirst().revisionId().toString())))));
  }

  private static <T> T asGameDesign(java.util.function.Supplier<T> action) {
    var context =
        Context.current()
            .withValue(
                GrpcPeerIdentity.CONTEXT_KEY,
                GrpcPeerIdentity.parseUri("spiffe://firemud/ns/test/sa/game-design-service")
                    .orElseThrow());
    var previous = context.attach();
    try {
      return action.get();
    } finally {
      context.detach(previous);
    }
  }
}
