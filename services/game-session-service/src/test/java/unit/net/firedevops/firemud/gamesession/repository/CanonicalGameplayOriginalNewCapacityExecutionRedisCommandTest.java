package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import net.firedevops.firemud.common.account.capacity.CapacityAdmissionTestFixture;
import net.firedevops.firemud.common.account.capacity.OriginalNewCapacityExecution;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingIdentity;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayOriginalNewFinalAdmissionRedisScriptContribution;
import org.junit.jupiter.api.Test;

/** Controlled command transport only; mock carriers do not qualify admission or source custody. */
class CanonicalGameplayOriginalNewCapacityExecutionRedisCommandTest {
  @Test
  void unscopedCloseUsesOneAbsoluteDeadlineForEveryCommand() throws Exception {
    var fixture = new Fixture();
    var clock = mock(Clock.class);
    AtomicLong now = new AtomicLong(fixture.deadline - 100);
    when(clock.millis()).thenAnswer(ignored -> now.get());
    when(fixture.redis.connect(ByteArrayCodec.INSTANCE)).thenReturn(fixture.connection);
    fixture.completeWhoami(() -> now.addAndGet(30));
    fixture.completeScriptLoad(() -> now.addAndGet(50));
    fixture.completeEvaluation();
    var owner =
        new CanonicalGameplayOriginalNewFinalAdmissionRedisOwner(
            fixture.redis, fixture.catalog, clock, mock());
    assertThat(
            owner.closeCapacityExecutionOnce(
                fixture.execution, fixture.candidate, fixture.assignment, fixture.deadline))
        .isEqualTo(4);
    verify(fixture.whoami)
        .get(
            org.mockito.ArgumentMatchers.longThat(value -> value > 0 && value <= 100),
            eq(TimeUnit.MILLISECONDS));
    verify(fixture.scriptLoad)
        .get(
            org.mockito.ArgumentMatchers.longThat(value -> value > 0 && value <= 70),
            eq(TimeUnit.MILLISECONDS));
    verify(fixture.evaluation)
        .get(
            org.mockito.ArgumentMatchers.longThat(value -> value > 0 && value <= 20),
            eq(TimeUnit.MILLISECONDS));
  }

  @Test
  @org.junit.jupiter.api.Timeout(10)
  void defaultOwnerBoundsSlowConnectAndDisposesLateConnectionWithoutCommands() throws Exception {
    var fixture = new Fixture();
    var contextRef = new AtomicReference<io.grpc.Context.CancellableContext>();
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var disposed = new java.util.concurrent.CountDownLatch(1);
    when(fixture.redis.connect(ByteArrayCodec.INSTANCE))
        .thenAnswer(
            ignored -> {
              entered.countDown();
              contextRef.get().cancel(new TimeoutException("controlled connect-phase expiry"));
              while (release.getCount() != 0) {
                try {
                  release.await();
                } catch (InterruptedException cancelled) {
                  /* physical connect ignores cancellation */
                }
              }
              return fixture.connection;
            });
    when(fixture.connection.closeAsync())
        .thenAnswer(
            ignored -> {
              disposed.countDown();
              return CompletableFuture.completedFuture(null);
            });
    var timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
    try (var context = io.grpc.Context.current().withDeadlineAfter(5, TimeUnit.SECONDS, timer)) {
      contextRef.set(context);
      var previous = context.attach();
      try {
        assertThatThrownBy(
                () ->
                    new CanonicalGameplayOriginalNewFinalAdmissionRedisOwner(
                            fixture.redis, fixture.catalog, Clock.systemUTC(), mock())
                        .closeCapacityExecutionOnce(
                            fixture.execution,
                            fixture.candidate,
                            fixture.assignment,
                            fixture.deadline))
            .isInstanceOf(IllegalStateException.class);
        assertThat(entered.getCount()).isZero();
        verifyNoInteractions(fixture.whoami, fixture.scriptLoad, fixture.evaluation);
      } finally {
        context.detach(previous);
        release.countDown();
      }
      assertThat(disposed.await(2, TimeUnit.SECONDS)).isTrue();
      verify(fixture.connection, never()).async();
      verify(fixture.connection, never()).close();
    } finally {
      release.countDown();
      timer.shutdownNow();
    }
  }

  @Test
  @org.junit.jupiter.api.Timeout(10)
  void defaultOwnerBoundsCloseWithoutReturningSuccessAfterUnknownDisposal() throws Exception {
    var fixture = new Fixture();
    fixture.completeWhoami(() -> {});
    fixture.completeScriptLoad(() -> {});
    fixture.completeEvaluation();
    when(fixture.redis.connect(ByteArrayCodec.INSTANCE)).thenReturn(fixture.connection);
    var close = new CompletableFuture<Void>();
    var contextRef = new AtomicReference<io.grpc.Context.CancellableContext>();
    when(fixture.connection.closeAsync())
        .thenAnswer(
            ignored -> {
              contextRef.get().cancel(new TimeoutException("controlled close-phase expiry"));
              return close;
            });
    var timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
    try (var context = io.grpc.Context.current().withDeadlineAfter(5, TimeUnit.SECONDS, timer)) {
      contextRef.set(context);
      var previous = context.attach();
      try {
        assertThatThrownBy(
                () ->
                    new CanonicalGameplayOriginalNewFinalAdmissionRedisOwner(
                            fixture.redis, fixture.catalog, Clock.systemUTC(), mock())
                        .closeCapacityExecutionOnce(
                            fixture.execution,
                            fixture.candidate,
                            fixture.assignment,
                            fixture.deadline))
            .isInstanceOf(IllegalStateException.class);
        assertThat(close).isNotDone();
        assertThat(fixture.evaluationSubmissions).hasValue(1);
        verify(fixture.connection, never()).close();
      } finally {
        context.detach(previous);
        close.complete(null);
      }
    } finally {
      close.complete(null);
      timer.shutdownNow();
    }
  }

  @Test
  void scopedCloseUsesShrinkingCommandWaitsAndHandsDisposalToItsBudget() throws Exception {
    var fixture = new Fixture();
    AtomicLong remaining = new AtomicLong(500);
    var close = new CompletableFuture<Void>();
    var trackedClose = new AtomicReference<CompletableFuture<Void>>();
    when(fixture.connection.closeAsync()).thenReturn(close);
    fixture.completeWhoami(() -> remaining.set(300));
    fixture.completeScriptLoad(() -> remaining.set(100));
    fixture.completeEvaluation();

    assertThat(
            fixture
                .scoped(remaining::get, trackedClose::set)
                .closeCapacityExecutionOnce(
                    fixture.execution, fixture.candidate, fixture.assignment, fixture.deadline))
        .isEqualTo(4);

    verify(fixture.whoami).get(500, TimeUnit.MILLISECONDS);
    verify(fixture.scriptLoad).get(300, TimeUnit.MILLISECONDS);
    verify(fixture.evaluation).get(100, TimeUnit.MILLISECONDS);
    verifyNoInteractions(fixture.redis);
    verify(fixture.connection).closeAsync();
    verify(fixture.connection, never()).close();
    assertThat(trackedClose.get()).isSameAs(close);
  }

  @Test
  void expiryAfterPrincipalCheckPreventsScriptSubmission() throws Exception {
    var fixture = new Fixture();
    AtomicLong remaining = new AtomicLong(500);
    fixture.completeWhoami(() -> remaining.set(0));

    assertThatThrownBy(
            () ->
                fixture
                    .scoped(checked(remaining))
                    .closeCapacityExecutionOnce(
                        fixture.execution, fixture.candidate, fixture.assignment, fixture.deadline))
        .isInstanceOf(IllegalStateException.class);

    verifyNoInteractions(fixture.scriptLoad, fixture.evaluation);
    assertThat(fixture.scriptSubmissions).hasValue(0);
    assertThat(fixture.evaluationSubmissions).hasValue(0);
    verify(fixture.connection).closeAsync();
    verifyNoInteractions(fixture.redis);
  }

  @Test
  void expiryAfterScriptLoadPreventsMutatingEvaluationSubmission() throws Exception {
    var fixture = new Fixture();
    AtomicLong remaining = new AtomicLong(500);
    fixture.completeWhoami(() -> {});
    fixture.completeScriptLoad(() -> remaining.set(0));

    assertThatThrownBy(
            () ->
                fixture
                    .scoped(checked(remaining))
                    .closeCapacityExecutionOnce(
                        fixture.execution, fixture.candidate, fixture.assignment, fixture.deadline))
        .isInstanceOf(IllegalStateException.class);

    assertThat(fixture.scriptSubmissions).hasValue(1);
    assertThat(fixture.evaluationSubmissions).hasValue(0);
    verifyNoInteractions(fixture.evaluation, fixture.redis);
    verify(fixture.connection).closeAsync();
  }

  @Test
  void timedOutEvaluationIsIndeterminateAndClosesAsynchronouslyWithoutResubmission()
      throws Exception {
    var fixture = new Fixture();
    fixture.completeWhoami(() -> {});
    fixture.completeScriptLoad(() -> {});
    when(fixture.evaluation.get(anyLong(), eq(TimeUnit.MILLISECONDS)))
        .thenThrow(new TimeoutException("Controlled lost write reply"));

    assertThatThrownBy(
            () ->
                fixture
                    .scoped(() -> 100)
                    .closeCapacityExecutionOnce(
                        fixture.execution, fixture.candidate, fixture.assignment, fixture.deadline))
        .isInstanceOf(IllegalStateException.class);

    assertThat(fixture.evaluationSubmissions).hasValue(1);
    verify(fixture.evaluation).get(100, TimeUnit.MILLISECONDS);
    verify(fixture.connection).closeAsync();
    verify(fixture.connection, never()).close();
    verifyNoInteractions(fixture.redis);
  }

  @Test
  void unscopedCloseCapsWaitsAtFiveSecondsAndUsesBoundedAsyncCleanup() throws Exception {
    var fixture = new Fixture();
    fixture.completeWhoami(() -> {});
    fixture.completeScriptLoad(() -> {});
    fixture.completeEvaluation();
    when(fixture.redis.connect(ByteArrayCodec.INSTANCE)).thenReturn(fixture.connection);

    assertThat(
            new CanonicalGameplayOriginalNewFinalAdmissionRedisOwner(
                    fixture.redis,
                    fixture.catalog,
                    Clock.fixed(java.time.Instant.now(), java.time.ZoneOffset.UTC),
                    mock())
                .closeCapacityExecutionOnce(
                    fixture.execution, fixture.candidate, fixture.assignment))
        .isEqualTo(4);

    verify(fixture.redis).connect(ByteArrayCodec.INSTANCE);
    verify(fixture.whoami)
        .get(
            org.mockito.ArgumentMatchers.longThat(value -> value > 0 && value <= 5000),
            eq(TimeUnit.MILLISECONDS));
    verify(fixture.scriptLoad)
        .get(
            org.mockito.ArgumentMatchers.longThat(value -> value > 0 && value <= 5000),
            eq(TimeUnit.MILLISECONDS));
    verify(fixture.evaluation)
        .get(
            org.mockito.ArgumentMatchers.longThat(value -> value > 0 && value <= 5000),
            eq(TimeUnit.MILLISECONDS));
    verify(fixture.connection).closeAsync();
    verify(fixture.connection, never()).close();
  }

  private static LongSupplier checked(AtomicLong remaining) {
    return () -> {
      long value = remaining.get();
      if (value <= 0) throw new IllegalStateException("Controlled expired operation");
      return value;
    };
  }

  private static final class Fixture {
    final RedisClient redis = mock();
    final StatefulRedisConnection<byte[], byte[]> connection = mock();
    final RedisFuture<String> whoami = mock();
    final RedisFuture<String> scriptLoad = mock();
    final RedisFuture<Long> evaluation = mock();
    final AtomicLong scriptSubmissions = new AtomicLong();
    final AtomicLong evaluationSubmissions = new AtomicLong();
    final CanonicalGameplayOriginalNewCandidate candidate = mock();
    final OriginalNewCapacityExecution execution = mock();
    final CanonicalGameplayTenantGameplayTagRepository.Assignment assignment = mock();
    final RedisScriptCatalog catalog =
        RedisScriptCatalog.fromContributions(
            List.of(new CanonicalGameplayOriginalNewFinalAdmissionRedisScriptContribution()));
    final long deadline = System.currentTimeMillis() + 5000;
    String loadedSha;

    @SuppressWarnings("unchecked")
    Fixture() {
      var request = CapacityAdmissionTestFixture.playerRequest();
      var player = (TenantCapacityAdmission.PlayerContext) request.context();
      UUID tenant = request.identity().tenantId();
      UUID transitionId = request.identity().admissionId();
      var binding =
          new CanonicalGameplayBindingIdentity(
              player.accountId(),
              tenant,
              player.playableStateNamespaceId(),
              player.characterId(),
              "SHARED",
              player.gameInstanceId(),
              1,
              player.sessionId().toString(),
              UUID.randomUUID(),
              BigInteger.ONE,
              "firemud-account-service",
              BigInteger.ONE,
              BigInteger.ONE,
              BigInteger.ONE,
              BigInteger.ONE);
      byte[] candidateBytes = "controlled-candidate".getBytes(StandardCharsets.US_ASCII);
      byte[] executionBytes = "controlled-execution".getBytes(StandardCharsets.US_ASCII);
      when(candidate.bindingIdentity()).thenReturn(binding);
      when(candidate.bindingDecisionId()).thenReturn(transitionId);
      when(candidate.transition())
          .thenReturn(
              new CanonicalGameplayOriginalNewCandidate.TransitionSource(
                  transitionId,
                  binding.bindingRef(),
                  BigInteger.ONE,
                  UUID.randomUUID(),
                  UUID.randomUUID(),
                  UUID.randomUUID()));
      when(candidate.canonicalBytes()).thenAnswer(ignored -> candidateBytes.clone());
      when(assignment.isBindingTransition()).thenReturn(true);
      when(assignment.transitionId()).thenReturn(transitionId);
      when(assignment.tenantId()).thenReturn(tenant);
      when(assignment.accountId()).thenReturn(binding.accountId());
      when(assignment.playableStateNamespaceId()).thenReturn(binding.playableStateNamespaceId());
      when(assignment.characterId()).thenReturn(binding.characterId());
      when(assignment.gameInstanceId()).thenReturn(binding.gameInstanceId());
      when(assignment.sessionId()).thenReturn(binding.sessionId());
      when(assignment.bindingRef()).thenReturn(binding.bindingRef());
      when(assignment.tenantGameplayTag()).thenReturn("g1-0123456789abcdef0123456789abcdef");
      when(execution.request()).thenReturn(request);
      when(execution.canonicalBytes()).thenAnswer(ignored -> executionBytes.clone());
      when(execution.digest())
          .thenReturn(CanonicalGameplayIssuerMemberV2Codec.sha256(executionBytes));
      when(execution.candidateDigest())
          .thenReturn(CanonicalGameplayIssuerMemberV2Codec.sha256(candidateBytes));
      when(execution.transitionId()).thenReturn(transitionId);
      RedisAsyncCommands<byte[], byte[]> commands =
          mock(
              RedisAsyncCommands.class,
              invocation -> {
                return switch (invocation.getMethod().getName()) {
                  case "aclWhoami" -> whoami;
                  case "scriptLoad" -> {
                    scriptSubmissions.incrementAndGet();
                    loadedSha =
                        HexFormat.of()
                            .formatHex(
                                MessageDigest.getInstance("SHA-1")
                                    .digest(invocation.getArgument(0, byte[].class)));
                    yield scriptLoad;
                  }
                  case "evalsha" -> {
                    evaluationSubmissions.incrementAndGet();
                    yield evaluation;
                  }
                  default -> org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
                };
              });
      when(connection.isOpen()).thenReturn(true);
      when(connection.getOptions())
          .thenReturn(io.lettuce.core.ClientOptions.builder().autoReconnect(false).build());
      when(connection.async()).thenReturn(commands);
      when(connection.closeAsync()).thenReturn(CompletableFuture.completedFuture(null));
    }

    CanonicalGameplayOriginalNewFinalAdmissionRedisOwner scoped(LongSupplier remaining) {
      return scoped(remaining, CompletableFuture::join);
    }

    CanonicalGameplayOriginalNewFinalAdmissionRedisOwner scoped(
        LongSupplier remaining, Consumer<CompletableFuture<Void>> close) {
      return new CanonicalGameplayOriginalNewFinalAdmissionRedisOwner(
          redis, catalog, Clock.systemUTC(), mock(), () -> connection, remaining, close);
    }

    void completeWhoami(Runnable completion) throws Exception {
      when(whoami.get(anyLong(), eq(TimeUnit.MILLISECONDS)))
          .thenAnswer(
              ignored -> {
                completion.run();
                return "gamesession_coord_app";
              });
    }

    void completeScriptLoad(Runnable completion) throws Exception {
      when(scriptLoad.get(anyLong(), eq(TimeUnit.MILLISECONDS)))
          .thenAnswer(
              ignored -> {
                completion.run();
                return loadedSha;
              });
    }

    void completeEvaluation() throws Exception {
      when(evaluation.get(anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(4L);
    }
  }
}
