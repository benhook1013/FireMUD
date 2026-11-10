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
import java.util.function.LongSupplier;
import net.firedevops.firemud.common.account.capacity.OriginalNewCapacityExecution;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingIdentity;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayOriginalNewFinalAdmissionRedisScriptContribution;
import org.junit.jupiter.api.Test;

/** Controlled command transport only; mock carriers do not qualify admission or source custody. */
class CanonicalGameplayOriginalNewCapacityExecutionRedisCommandTest {
  @Test
  void scopedCloseUsesSuppliedConnectionAndShrinkingCommandWaits() throws Exception {
    var fixture = new Fixture();
    AtomicLong remaining = new AtomicLong(500);
    fixture.completeWhoami(() -> remaining.set(300));
    fixture.completeScriptLoad(() -> remaining.set(100));
    fixture.completeEvaluation();

    assertThat(
            fixture
                .scoped(remaining::get)
                .closeCapacityExecutionOnce(
                    fixture.execution, fixture.candidate, fixture.assignment, fixture.deadline))
        .isEqualTo(4);

    verify(fixture.whoami).get(500, TimeUnit.MILLISECONDS);
    verify(fixture.scriptLoad).get(300, TimeUnit.MILLISECONDS);
    verify(fixture.evaluation).get(100, TimeUnit.MILLISECONDS);
    verifyNoInteractions(fixture.redis);
    verify(fixture.connection).closeAsync();
    verify(fixture.connection, never()).close();
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
  void unscopedCloseRetainsDirectConnectionFiveSecondWaitsAndSynchronousCleanup() throws Exception {
    var fixture = new Fixture();
    fixture.completeWhoami(() -> {});
    fixture.completeScriptLoad(() -> {});
    fixture.completeEvaluation();
    when(fixture.redis.connect(ByteArrayCodec.INSTANCE)).thenReturn(fixture.connection);

    assertThat(
            new CanonicalGameplayOriginalNewFinalAdmissionRedisOwner(
                    fixture.redis, fixture.catalog, Clock.systemUTC(), mock())
                .closeCapacityExecutionOnce(
                    fixture.execution, fixture.candidate, fixture.assignment))
        .isEqualTo(4);

    verify(fixture.redis).connect(ByteArrayCodec.INSTANCE);
    verify(fixture.whoami).get(5000, TimeUnit.MILLISECONDS);
    verify(fixture.scriptLoad).get(5000, TimeUnit.MILLISECONDS);
    verify(fixture.evaluation).get(5000, TimeUnit.MILLISECONDS);
    verify(fixture.connection).close();
    verify(fixture.connection, never()).closeAsync();
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
      UUID tenant = UUID.randomUUID();
      var binding =
          new CanonicalGameplayBindingIdentity(
              UUID.randomUUID(),
              tenant,
              UUID.randomUUID(),
              UUID.randomUUID(),
              "SHARED",
              UUID.randomUUID(),
              1,
              UUID.randomUUID().toString(),
              UUID.randomUUID(),
              BigInteger.ONE,
              "firemud-account-service",
              BigInteger.ONE,
              BigInteger.ONE,
              BigInteger.ONE,
              BigInteger.ONE);
      UUID transitionId = UUID.randomUUID();
      when(candidate.bindingIdentity()).thenReturn(binding);
      when(candidate.transition())
          .thenReturn(
              new CanonicalGameplayOriginalNewCandidate.TransitionSource(
                  transitionId,
                  binding.bindingRef(),
                  BigInteger.ONE,
                  UUID.randomUUID(),
                  UUID.randomUUID(),
                  UUID.randomUUID()));
      when(candidate.canonicalBytes())
          .thenAnswer(ignored -> "controlled-candidate".getBytes(StandardCharsets.US_ASCII));
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
      TenantCapacityAdmission.Request request = mock();
      when(request.admissionAttemptId()).thenReturn(UUID.randomUUID());
      when(execution.request()).thenReturn(request);
      when(execution.canonicalBytes())
          .thenAnswer(ignored -> "controlled-execution".getBytes(StandardCharsets.US_ASCII));
      when(execution.digest()).thenReturn("sha256:" + "a".repeat(64));
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
      when(connection.async()).thenReturn(commands);
      when(connection.closeAsync()).thenReturn(CompletableFuture.completedFuture(null));
    }

    CanonicalGameplayOriginalNewFinalAdmissionRedisOwner scoped(LongSupplier remaining) {
      return new CanonicalGameplayOriginalNewFinalAdmissionRedisOwner(
          redis, catalog, Clock.systemUTC(), mock(), () -> connection, remaining);
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
