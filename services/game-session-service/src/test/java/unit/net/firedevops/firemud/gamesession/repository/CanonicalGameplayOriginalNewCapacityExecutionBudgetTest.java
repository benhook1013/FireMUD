package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.ConnectionFuture;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.account.capacity.CapacityAdmissionTestFixture;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayOriginalNewFinalAdmissionRedisScriptContribution;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayRegionLeaseRedisScriptContribution;
import org.jooq.ConnectionProvider;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/** Controlled acquisition/driver behavior, not database or owner-evidence qualification. */
class CanonicalGameplayOriginalNewCapacityExecutionBudgetTest {
  private static final Clock CLOCK = Clock.systemUTC();

  @AfterEach
  void awaitResourceAndCancellationWorkerCleanup() throws InterruptedException {
    Semaphore permits =
        (Semaphore)
            java.util.Objects.requireNonNull(
                ReflectionTestUtils.getField(
                    CanonicalGameplayOriginalNewCapacityExecutionBudget.class, "OPERATIONS"));
    int maximum = CanonicalGameplayOriginalNewCapacityExecutionBudget.MAX_IN_FLIGHT_OPERATIONS;
    // A mocked provider release is observed before its caller drains connection custody and
    // restores admission. Wait for the real worker/cancellation lifecycle, including on failure.
    assertThat(permits.tryAcquire(maximum, 2, TimeUnit.SECONDS))
        .as("resource and cancellation workers restore all operation permits")
        .isTrue();
    permits.release(maximum);
    Semaphore background =
        (Semaphore)
            java.util.Objects.requireNonNull(
                ReflectionTestUtils.getField(
                    CanonicalGameplayOriginalNewCapacityExecutionBudget.class,
                    "BACKGROUND_OPERATIONS"));
    int maximumBackground =
        CanonicalGameplayOriginalNewCapacityExecutionBudget.MAX_BACKGROUND_OPERATIONS;
    assertThat(background.tryAcquire(maximumBackground, 2, TimeUnit.SECONDS))
        .as("background admission follows the same cleanup lifecycle")
        .isTrue();
    background.release(maximumBackground);
  }

  @Test
  void ownedResultDisposesExactlyOnceAfterCallerCancellation() throws Exception {
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var disposed = new CountDownLatch(1);
    var value = new Object();
    var disposalCount = new AtomicInteger();
    try (var caller = Context.current().withCancellation();
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var result =
          CompletableFuture.supplyAsync(
              () -> {
                var previous = caller.attach();
                try {
                  return CanonicalGameplayOriginalNewCapacityExecutionBudget.runOwned(
                      CLOCK,
                      Long.MAX_VALUE,
                      budget -> {
                        started.countDown();
                        awaitUninterruptibly(release);
                        return value;
                      },
                      abandoned -> {
                        if (abandoned == value) disposalCount.incrementAndGet();
                        disposed.countDown();
                      });
                } finally {
                  caller.detach(previous);
                }
              },
              executor);
      try {
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        caller.cancel(new IllegalStateException("controlled caller cancellation"));
        assertThatThrownBy(() -> result.get(2, TimeUnit.SECONDS))
            .hasCauseInstanceOf(
                CanonicalGameplayOriginalNewCapacityExecutionBudget.BudgetUnavailable.class);
        assertThat(disposalCount.get()).isZero();
      } finally {
        release.countDown();
      }
      assertThat(disposed.await(2, TimeUnit.SECONDS)).isTrue();
      assertThat(disposalCount.get()).isEqualTo(1);
    }
    Object returned =
        CanonicalGameplayOriginalNewCapacityExecutionBudget.runOwned(
            CLOCK, Long.MAX_VALUE, budget -> value, ignored -> disposalCount.incrementAndGet());
    assertThat(returned).isSameAs(value);
    assertThat(disposalCount.get()).isEqualTo(1);
  }

  @Test
  void backgroundPhysicalCompletionIncludesDelayedProviderReturn() throws Exception {
    ConnectionProvider provider = mock();
    Connection connection = mock();
    when(provider.acquire()).thenReturn(connection);
    var releaseEntered = new CountDownLatch(1);
    var providerMayReturn = new CountDownLatch(1);
    var physicallyReleased = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              releaseEntered.countDown();
              awaitUninterruptibly(providerMayReturn);
              return null;
            })
        .when(provider)
        .release(connection);
    var dsl = DSL.using(new DefaultConfiguration().set(provider).set(SQLDialect.POSTGRES));
    try (var caller = Context.current().withCancellation();
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var outcome =
          CompletableFuture.supplyAsync(
              () -> {
                var previous = caller.attach();
                try {
                  return CanonicalGameplayOriginalNewCapacityExecutionBudget.runBackground(
                      CLOCK,
                      Long.MAX_VALUE,
                      budget -> {
                        var scoped = budget.sql(dsl);
                        var scopedProvider = scoped.configuration().connectionProvider();
                        var acquired = scopedProvider.acquire();
                        scopedProvider.release(acquired);
                        return true;
                      },
                      physicallyReleased::countDown);
                } finally {
                  caller.detach(previous);
                }
              },
              executor);
      try {
        assertThat(releaseEntered.await(2, TimeUnit.SECONDS)).isTrue();
        caller.cancel(new IllegalStateException("controlled caller cancellation"));
        assertThatThrownBy(() -> outcome.get(2, TimeUnit.SECONDS))
            .hasCauseInstanceOf(
                CanonicalGameplayOriginalNewCapacityExecutionBudget.BudgetUnavailable.class);
        assertThat(physicallyReleased.getCount()).isEqualTo(1);
      } finally {
        providerMayReturn.countDown();
      }
      assertThat(physicallyReleased.await(2, TimeUnit.SECONDS)).isTrue();
      verify(provider).release(connection);
    }
  }

  @Test
  void retainedInitialGrantOnlyReadsSameLiveTokenAndRejectsReplacement() throws Exception {
    RedisClient client = mock(RedisClient.class);
    StatefulRedisConnection<byte[], byte[]> connection = mock();
    RedisAsyncCommands<byte[], byte[]> commands = mock();
    RedisFuture<String> principal = mock();
    RedisFuture<byte[]> tokenRead = mock();
    RedisFuture<Long> ttlRead = mock();
    var source = mock(CanonicalGameplayRegionRuntimeStatusRepository.Source.class);
    when(source.tenantId()).thenReturn(UUID.randomUUID());
    when(source.gameInstanceId()).thenReturn(UUID.randomUUID());
    when(source.regionId()).thenReturn(UUID.randomUUID());
    var preparation =
        mock(CanonicalGameplayRegionRuntimeStatusRepository.TimelinePreparation.class);
    when(preparation.source()).thenReturn(source);
    when(preparation.regionEpoch()).thenReturn(1L);
    var status =
        mock(net.firedevops.firemud.gamesession.entity.CanonicalGameplayRegionRuntimeStatus.class);
    String token = UUID.randomUUID().toString();
    String correlation =
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
    when(preparation.leaseTokenCorrelation()).thenReturn(correlation);
    when(status.leaseTokenCorrelation()).thenReturn(correlation);
    when(status.regionEpoch()).thenReturn(1L);
    when(status.ownerService()).thenReturn("game-session-service");
    when(status.ownerInstanceId()).thenReturn("same-instance");
    var identity =
        new RuntimeIdentity(
            "game-session-service",
            "same-instance",
            "localhost",
            CLOCK.instant(),
            "test",
            "test",
            "test");
    var owner = new CanonicalGameplayRegionLeaseRedisOwner(client, mock(RedisScriptCatalog.class));
    when(client.connect(ByteArrayCodec.INSTANCE)).thenReturn(connection);
    when(connection.async()).thenReturn(commands);
    when(commands.aclWhoami()).thenReturn(principal);
    when(principal.get(anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn("gamesession_coord_app");
    when(commands.get(any(byte[].class))).thenReturn(tokenRead);
    when(tokenRead.get(anyLong(), eq(TimeUnit.MILLISECONDS)))
        .thenAnswer(ignored -> token.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    when(commands.pttl(any(byte[].class))).thenReturn(ttlRead);
    when(ttlRead.get(anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(1000L);
    assertThat(owner.readRetainedInitialGrant(preparation, status, identity).tokenCorrelation())
        .isEqualTo(correlation);
    when(ttlRead.get(anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(-2L);
    assertThatThrownBy(() -> owner.readRetainedInitialGrant(preparation, status, identity))
        .isInstanceOf(IllegalStateException.class);
    when(ttlRead.get(anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(1000L);
    when(tokenRead.get(anyLong(), eq(TimeUnit.MILLISECONDS)))
        .thenAnswer(ignored ->
            UUID.randomUUID().toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    assertThatThrownBy(() -> owner.readRetainedInitialGrant(preparation, status, identity))
        .isInstanceOf(IllegalStateException.class);
    when(status.ownerInstanceId()).thenReturn("replacement-instance");
    assertThatThrownBy(() -> owner.readRetainedInitialGrant(preparation, status, identity))
        .isInstanceOf(IllegalStateException.class);
    verify(commands, org.mockito.Mockito.times(3)).aclWhoami();
    verify(commands, org.mockito.Mockito.times(3)).get(any(byte[].class));
    verify(commands, org.mockito.Mockito.times(3)).pttl(any(byte[].class));
    org.mockito.Mockito.verifyNoMoreInteractions(commands);
  }

  @Test
  void manualFactoryEnablesPerInstallationDeadline() {
    var owner =
        CanonicalGameplayRegionLeaseOwnerFactory.compose(
            mock(DSLContext.class),
            mock(RedisClient.class),
            RedisURI.create("redis://localhost:6379"),
            mock(RedisScriptCatalog.class),
            mock(CanonicalGameplayRegionIncarnationSource.class),
            mock(
                net.firedevops.firemud.gamesession.service
                    .CanonicalInitialPlayerLocationRegionResolver.class),
            mock(RuntimeIdentity.class),
            Duration.ofSeconds(30),
            CLOCK);
    assertThat(ReflectionTestUtils.getField(owner, "deadlineBoundInstallation")).isEqualTo(true);
  }

  @Test
  void installationFreezesDeadlineAndNeverExtendsExistingBudget() {
    Clock frozenClock = Clock.fixed(CLOCK.instant(), java.time.ZoneOffset.UTC);
    RedisClient client = mock(RedisClient.class);
    var owner =
        new CanonicalGameplayRegionLeaseRedisOwner(
            client, RedisURI.create("redis://localhost:6379"), mock(RedisScriptCatalog.class));
    var bounded = owner.forInstallation(frozenClock);
    assertThat(ReflectionTestUtils.getField(bounded, "handoffDeadlineMillis"))
        .isEqualTo(frozenClock.millis() + 5000L);
    var shorter =
        owner
            .forHandoffDeadline(frozenClock, frozenClock.millis() + 1000L)
            .forInstallation(frozenClock);
    assertThat(ReflectionTestUtils.getField(shorter, "handoffDeadlineMillis"))
        .isEqualTo(frozenClock.millis() + 1000L);
    try (var context = Context.current().withCancellation()) {
      context.cancel(new IllegalStateException("cancelled before installation"));
      context.run(() ->
          assertThatThrownBy(() -> assertThat(owner.forInstallation(frozenClock)).isNotNull())
              .isInstanceOf(IllegalStateException.class));
    }
    verifyNoInteractions(client);
  }

  @Test
  void timedOutHandoffRedisConnectRetainsAdmissionThroughLateClose() throws Exception {
    RedisClient redis = mock();
    RedisURI uri = RedisURI.create("redis://localhost:6379");
    var owner =
        new CanonicalGameplayRegionLeaseRedisOwner(
            redis, uri, RedisScriptCatalog.fromContributions(List.of()));
    CompletableFuture<StatefulRedisConnection<byte[], byte[]>> handshake =
        new CompletableFuture<>();
    StatefulRedisConnection<byte[], byte[]> connection = mock();
    CompletableFuture<Void> close = new CompletableFuture<>();
    CountDownLatch connected = new CountDownLatch(1);
    when(redis.connectAsync(eq(ByteArrayCodec.INSTANCE), any(RedisURI.class)))
        .thenAnswer(invocation -> {
          connected.countDown();
          return ConnectionFuture.from(new InetSocketAddress("localhost", 6379), handshake);
        });
    when(connection.closeAsync()).thenReturn(close);
    Semaphore permits =
        (Semaphore)
            java.util.Objects.requireNonNull(
                ReflectionTestUtils.getField(
                    CanonicalGameplayOriginalNewCapacityExecutionBudget.class,
                    "BACKGROUND_OPERATIONS"));
    int maximum = CanonicalGameplayOriginalNewCapacityExecutionBudget.MAX_BACKGROUND_OPERATIONS;

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var caller =
          executor.submit(() ->
              assertThatThrownBy(() ->
                      CanonicalGameplayOriginalNewCapacityExecutionBudget.runBackground(
                          CLOCK,
                          CLOCK.millis() + 500,
                          budget ->
                              ReflectionTestUtils.invokeMethod(owner, "openConnection", budget)))
                  .isInstanceOf(IllegalStateException.class));
      try {
        assertThat(connected.await(2, TimeUnit.SECONDS)).isTrue();
        caller.get(2, TimeUnit.SECONDS);
        assertPermitRemainsHeld(permits, maximum);

        handshake.complete(connection);
        verify(connection, timeout(1000)).closeAsync();
        assertPermitRemainsHeld(permits, maximum);
        verify(connection, never()).async();
      } finally {
        handshake.completeExceptionally(new IllegalStateException("test cleanup"));
        close.complete(null);
      }
      assertThat(permits.tryAcquire(maximum, 2, TimeUnit.SECONDS)).isTrue();
      permits.release(maximum);
    }
  }

  @Test
  void timelinePreparationRefusesReconnectBeforeQualifyingOrSubmittingRedisCommands() {
    RedisClient redis = mock();
    RedisURI uri = RedisURI.create("redis://localhost:6379");
    var catalog =
        RedisScriptCatalog.fromContributions(
            List.of(new CanonicalGameplayRegionLeaseRedisScriptContribution()));
    var owner = new CanonicalGameplayRegionLeaseRedisOwner(redis, uri, catalog);
    StatefulRedisConnection<byte[], byte[]> connection = mock();
    var source = mock(CanonicalGameplayRegionRuntimeStatusRepository.Source.class);
    var preparation =
        mock(CanonicalGameplayRegionRuntimeStatusRepository.TimelinePreparation.class);
    when(source.tenantId()).thenReturn(UUID.randomUUID());
    when(source.gameInstanceId()).thenReturn(UUID.randomUUID());
    when(source.regionId()).thenReturn(UUID.randomUUID());
    when(preparation.source()).thenReturn(source);
    when(preparation.operationId()).thenReturn(UUID.randomUUID());
    when(preparation.sourceDigest()).thenReturn("a".repeat(64));
    when(preparation.regionEpoch()).thenReturn(1L);
    when(redis.connectAsync(eq(ByteArrayCodec.INSTANCE), any(RedisURI.class)))
        .thenReturn(
            ConnectionFuture.from(
                new InetSocketAddress("localhost", 6379),
                CompletableFuture.completedFuture(connection)));
    when(connection.getOptions()).thenReturn(ClientOptions.builder().autoReconnect(true).build());
    when(connection.closeAsync()).thenReturn(CompletableFuture.completedFuture(null));
    RuntimeIdentity identity =
        new RuntimeIdentity(
            "game-session-service",
            "controlled-instance",
            "localhost",
            CLOCK.instant(),
            "controlled-test",
            "controlled-sha",
            "controlled-image");

    assertThatThrownBy(() ->
            owner
                .forHandoffDeadline(CLOCK, CLOCK.millis() + 4_000L)
                .prepareTimelineInitialization(preparation, Duration.ofSeconds(30), identity))
        .isInstanceOf(IllegalStateException.class);
    verify(connection, atLeastOnce()).getOptions();
    verify(connection, never()).async();
    verify(connection).closeAsync();
  }

  @Test
  void preparedTimelineRetainsAdmissionThroughRejectedCallersAsyncClose() throws Exception {
    RedisClient redis = mock();
    RedisURI uri = RedisURI.create("redis://localhost:6379");
    var catalog =
        RedisScriptCatalog.fromContributions(
            List.of(new CanonicalGameplayRegionLeaseRedisScriptContribution()));
    var owner = new CanonicalGameplayRegionLeaseRedisOwner(redis, uri, catalog);
    StatefulRedisConnection<byte[], byte[]> connection = mock();
    RedisAsyncCommands<byte[], byte[]> commands = mock();
    RedisFuture<String> principal = mock();
    RedisFuture<String> scriptLoad = mock();
    CompletableFuture<Void> close = new CompletableFuture<>();
    UUID tenantId = UUID.randomUUID();
    UUID gameInstanceId = UUID.randomUUID();
    UUID regionId = UUID.randomUUID();
    var source = mock(CanonicalGameplayRegionRuntimeStatusRepository.Source.class);
    var preparation =
        mock(CanonicalGameplayRegionRuntimeStatusRepository.TimelinePreparation.class);
    UUID operationId = UUID.randomUUID();
    String sourceDigest = "a".repeat(64);
    when(source.tenantId()).thenReturn(tenantId);
    when(source.gameInstanceId()).thenReturn(gameInstanceId);
    when(source.regionId()).thenReturn(regionId);
    when(preparation.source()).thenReturn(source);
    when(preparation.operationId()).thenReturn(operationId);
    when(preparation.sourceDigest()).thenReturn(sourceDigest);
    when(preparation.regionEpoch()).thenReturn(1L);
    when(redis.connectAsync(eq(ByteArrayCodec.INSTANCE), any(RedisURI.class)))
        .thenReturn(
            ConnectionFuture.from(
                new InetSocketAddress("localhost", 6379),
                CompletableFuture.completedFuture(connection)));
    when(connection.getOptions()).thenReturn(ClientOptions.builder().autoReconnect(false).build());
    when(connection.async()).thenReturn(commands);
    when(commands.aclWhoami()).thenReturn(principal);
    when(principal.get(anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn("gamesession_coord_app");
    when(commands.scriptLoad(any(byte[].class))).thenReturn(scriptLoad);
    var descriptor =
        catalog.require(
            CanonicalGameplayRegionLeaseRedisScriptContribution.TIMELINE_INITIALIZE_DEADLINE_ID);
    byte[] script;
    try (var scriptResource =
        CanonicalGameplayRegionLeaseRedisOwner.class
            .getClassLoader()
            .getResourceAsStream(descriptor.resourcePath())) {
      assertThat(scriptResource).isNotNull();
      script = scriptResource.readAllBytes();
    }
    String loadedSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(script));
    when(scriptLoad.get(anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(loadedSha);
    when(connection.closeAsync()).thenReturn(close);
    RedisFuture<Long> probed = mock();
    when(commands.<Long>evalsha(any(), any(), any(byte[][].class), any(byte[][].class)))
        .thenReturn(probed);
    when(probed.get(anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(1L);
    RuntimeIdentity identity =
        new RuntimeIdentity(
            "game-session-service",
            "controlled-instance",
            "localhost",
            CLOCK.instant(),
            "controlled-test",
            "controlled-sha",
            "controlled-image");
    Semaphore operations =
        (Semaphore)
            java.util.Objects.requireNonNull(
                ReflectionTestUtils.getField(
                    CanonicalGameplayOriginalNewCapacityExecutionBudget.class, "OPERATIONS"));
    int operationMaximum =
        CanonicalGameplayOriginalNewCapacityExecutionBudget.MAX_IN_FLIGHT_OPERATIONS;
    Semaphore background =
        (Semaphore)
            java.util.Objects.requireNonNull(
                ReflectionTestUtils.getField(
                    CanonicalGameplayOriginalNewCapacityExecutionBudget.class,
                    "BACKGROUND_OPERATIONS"));
    int backgroundMaximum =
        CanonicalGameplayOriginalNewCapacityExecutionBudget.MAX_BACKGROUND_OPERATIONS;

    CanonicalGameplayRegionLeaseRedisOwner.PreparedTimelineInitialization prepared = null;
    ExecutorService closer = Executors.newSingleThreadExecutor();
    try {
      prepared =
          owner
              .forHandoffDeadline(CLOCK, CLOCK.millis() + 4000)
              .prepareTimelineInitialization(preparation, Duration.ofSeconds(30), identity);
      assertPermitRemainsHeld(operations, operationMaximum);
      assertPermitRemainsHeld(background, backgroundMaximum);

      var preparedInitialization = prepared;
      closer.submit(preparedInitialization::close).get(1, TimeUnit.SECONDS);
      verify(connection, timeout(1000)).closeAsync();
      assertPermitRemainsHeld(operations, operationMaximum);
      assertPermitRemainsHeld(background, backgroundMaximum);

      close.complete(null);
      assertThat(operations.tryAcquire(operationMaximum, 2, TimeUnit.SECONDS)).isTrue();
      operations.release(operationMaximum);
      assertThat(background.tryAcquire(backgroundMaximum, 2, TimeUnit.SECONDS)).isTrue();
      background.release(backgroundMaximum);
      verify(connection, org.mockito.Mockito.times(1)).closeAsync();
      ArgumentCaptor<byte[][]> arguments = ArgumentCaptor.forClass(byte[][].class);
      verify(commands, times(1)).evalsha(any(), any(), any(byte[][].class), arguments.capture());
      assertThat(arguments.getValue()[1])
          .isEqualTo("PROBE".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    } finally {
      close.complete(null);
      closer.shutdown();
      try {
        assertThat(closer.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
      } finally {
        if (prepared != null) prepared.close();
      }
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void timelineInitializationUsesPreparedPermitWhileOtherBackgroundSlotIsOccupied(boolean composed)
      throws Exception {
    RedisClient redis = mock();
    RedisURI uri = RedisURI.create("redis://localhost:6379");
    var catalog =
        RedisScriptCatalog.fromContributions(
            List.of(new CanonicalGameplayRegionLeaseRedisScriptContribution()));
    var owner = new CanonicalGameplayRegionLeaseRedisOwner(redis, uri, catalog);
    StatefulRedisConnection<byte[], byte[]> connection = mock();
    RedisAsyncCommands<byte[], byte[]> commands = mock();
    RedisFuture<String> principal = mock();
    RedisFuture<String> scriptLoad = mock();
    RedisFuture<Long> initialized = mock();
    CompletableFuture<Void> close = new CompletableFuture<>();
    AtomicReference<byte[][]> submittedArguments = new AtomicReference<>();
    UUID tenantId = UUID.randomUUID();
    UUID gameInstanceId = UUID.randomUUID();
    UUID regionId = UUID.randomUUID();
    var source = mock(CanonicalGameplayRegionRuntimeStatusRepository.Source.class);
    var preparation =
        mock(CanonicalGameplayRegionRuntimeStatusRepository.TimelinePreparation.class);
    UUID operationId = UUID.randomUUID();
    String sourceDigest = "a".repeat(64);
    when(source.tenantId()).thenReturn(tenantId);
    when(source.gameInstanceId()).thenReturn(gameInstanceId);
    when(source.regionId()).thenReturn(regionId);
    when(preparation.source()).thenReturn(source);
    when(preparation.operationId()).thenReturn(operationId);
    when(preparation.sourceDigest()).thenReturn(sourceDigest);
    when(preparation.regionEpoch()).thenReturn(1L);
    when(redis.connectAsync(eq(ByteArrayCodec.INSTANCE), any(RedisURI.class)))
        .thenReturn(
            ConnectionFuture.from(
                new InetSocketAddress("localhost", 6379),
                CompletableFuture.completedFuture(connection)));
    when(connection.getOptions()).thenReturn(ClientOptions.builder().autoReconnect(false).build());
    when(connection.async()).thenReturn(commands);
    when(commands.aclWhoami()).thenReturn(principal);
    when(principal.get(anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn("gamesession_coord_app");
    when(commands.scriptLoad(any(byte[].class))).thenReturn(scriptLoad);
    var descriptor =
        catalog.require(
            CanonicalGameplayRegionLeaseRedisScriptContribution.TIMELINE_INITIALIZE_DEADLINE_ID);
    byte[] script;
    try (var scriptResource =
        CanonicalGameplayRegionLeaseRedisOwner.class
            .getClassLoader()
            .getResourceAsStream(descriptor.resourcePath())) {
      assertThat(scriptResource).isNotNull();
      script = scriptResource.readAllBytes();
    }
    String loadedSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(script));
    when(scriptLoad.get(anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(loadedSha);
    when(connection.closeAsync()).thenReturn(close);
    when(commands.evalsha(any(), any(), any(byte[][].class), any(byte[][].class)))
        .thenAnswer(invocation -> {
          byte[][] arguments = (byte[][]) invocation.getRawArguments()[3];
          if ("INITIALIZE"
              .equals(new String(arguments[1], java.nio.charset.StandardCharsets.US_ASCII))) {
            submittedArguments.set(arguments);
          }
          return initialized;
        });
    when(initialized.get(anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(1L);
    RuntimeIdentity identity =
        new RuntimeIdentity(
            "game-session-service",
            "controlled-instance",
            "localhost",
            CLOCK.instant(),
            "controlled-test",
            "controlled-sha",
            "controlled-image");
    var permitConstructor =
        CanonicalGameplayRegionRuntimeStatusRepository.InitialInitializationPermit.class
            .getDeclaredConstructor(UUID.class, String.class);
    permitConstructor.setAccessible(true);
    var permit = permitConstructor.newInstance(operationId, sourceDigest);
    long handoffDeadline = CLOCK.millis() + 4000;
    Semaphore operations =
        (Semaphore)
            java.util.Objects.requireNonNull(
                ReflectionTestUtils.getField(
                    CanonicalGameplayOriginalNewCapacityExecutionBudget.class, "OPERATIONS"));
    int operationMaximum =
        CanonicalGameplayOriginalNewCapacityExecutionBudget.MAX_IN_FLIGHT_OPERATIONS;
    Semaphore background =
        (Semaphore)
            java.util.Objects.requireNonNull(
                ReflectionTestUtils.getField(
                    CanonicalGameplayOriginalNewCapacityExecutionBudget.class,
                    "BACKGROUND_OPERATIONS"));
    int backgroundMaximum =
        CanonicalGameplayOriginalNewCapacityExecutionBudget.MAX_BACKGROUND_OPERATIONS;
    CountDownLatch otherBackgroundStarted = new CountDownLatch(1);
    CountDownLatch releaseOtherBackground = new CountDownLatch(1);

    CanonicalGameplayRegionLeaseRedisOwner.PreparedTimelineInitialization prepared = null;
    CompletableFuture<Boolean> otherBackground = null;
    CompletableFuture<?> initialization = null;
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      try {
        otherBackground =
            CompletableFuture.supplyAsync(
                () ->
                    CanonicalGameplayOriginalNewCapacityExecutionBudget.runBackground(
                        CLOCK,
                        handoffDeadline,
                        budget -> {
                          otherBackgroundStarted.countDown();
                          awaitUninterruptibly(releaseOtherBackground);
                          return true;
                        }),
                executor);
        assertThat(otherBackgroundStarted.await(2, TimeUnit.SECONDS)).isTrue();
        if (composed) {
          initialization =
              CompletableFuture.supplyAsync(
                  () ->
                      CanonicalGameplayOriginalNewCapacityExecutionBudget.runBackground(
                          CLOCK,
                          handoffDeadline,
                          budget -> {
                            try (var session =
                                owner
                                    .forInstallation(CLOCK, budget)
                                    .prepareTimelineInitialization(
                                        preparation, Duration.ofSeconds(30), identity)) {
                              return session.initialize(permit);
                            }
                          }),
                  executor);
        } else {
          prepared =
              owner
                  .forHandoffDeadline(CLOCK, handoffDeadline)
                  .prepareTimelineInitialization(preparation, Duration.ofSeconds(30), identity);
          var session = prepared;
          initialization =
              CompletableFuture.supplyAsync(() -> session.initialize(permit), executor);
        }
        verify(commands, timeout(1000).times(2))
            .evalsha(any(), eq(ScriptOutputType.INTEGER), any(byte[][].class), any(byte[][].class));
        verify(connection, timeout(1000)).closeAsync();
        byte[][] arguments = submittedArguments.get();
        assertThat(arguments).isNotNull();
        assertThat(new String(arguments[7], java.nio.charset.StandardCharsets.US_ASCII))
            .isEqualTo("1");
        assertThat(new String(arguments[8], java.nio.charset.StandardCharsets.US_ASCII))
            .isEqualTo(Long.toString(handoffDeadline));
        assertThat(initialization.isDone()).isFalse();
        assertThat(background.availablePermits()).isZero();
        assertThat(operations.availablePermits()).isEqualTo(operationMaximum - 2);

        close.complete(null);
        assertThat(initialization.get(2, TimeUnit.SECONDS)).isNotNull();
        releaseOtherBackground.countDown();
        assertThat(otherBackground.get(2, TimeUnit.SECONDS)).isTrue();
        assertThat(operations.tryAcquire(operationMaximum, 2, TimeUnit.SECONDS)).isTrue();
        operations.release(operationMaximum);
        assertThat(background.tryAcquire(backgroundMaximum, 2, TimeUnit.SECONDS)).isTrue();
        background.release(backgroundMaximum);
        verify(connection).closeAsync();
      } finally {
        releaseOtherBackground.countDown();
        if (prepared != null) prepared.close();
        close.complete(null);
      }
    }
  }

  @Test
  void successfulTransferReadbackRetainsAdmissionUntilAsyncRedisCloseCompletes() throws Exception {
    RedisClient redis = mock();
    StatefulRedisConnection<byte[], byte[]> connection = mock();
    ClientOptions options = ClientOptions.builder().autoReconnect(false).build();
    RedisAsyncCommands<byte[], byte[]> commands = mock();
    RedisFuture<String> principal = mock();
    CompletableFuture<Void> close = new CompletableFuture<>();
    when(redis.connect(ByteArrayCodec.INSTANCE)).thenReturn(connection);
    when(connection.isOpen()).thenReturn(true);
    when(connection.getOptions()).thenReturn(options);
    when(connection.async()).thenReturn(commands);
    when(commands.aclWhoami()).thenReturn(principal);
    when(principal.get(anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn("gamesession_coord_app");
    when(connection.closeAsync()).thenReturn(close);
    DSLContext dsl = source(mock());
    var owner =
        new CanonicalGameplayOriginalNewCapacityExecutionOwner(
            dsl,
            redis,
            RedisScriptCatalog.fromContributions(
                List.of(new CanonicalGameplayOriginalNewFinalAdmissionRedisScriptContribution())),
            CLOCK,
            new CanonicalGameplayTenantGameplayTagRepository(dsl, "prod", 1));
    CountDownLatch readbackReturned = new CountDownLatch(1);
    Semaphore operations =
        (Semaphore)
            java.util.Objects.requireNonNull(
                ReflectionTestUtils.getField(
                    CanonicalGameplayOriginalNewCapacityExecutionBudget.class, "OPERATIONS"));
    int operationMaximum =
        CanonicalGameplayOriginalNewCapacityExecutionBudget.MAX_IN_FLIGHT_OPERATIONS;
    Semaphore background =
        (Semaphore)
            java.util.Objects.requireNonNull(
                ReflectionTestUtils.getField(
                    CanonicalGameplayOriginalNewCapacityExecutionBudget.class,
                    "BACKGROUND_OPERATIONS"));
    int backgroundMaximum =
        CanonicalGameplayOriginalNewCapacityExecutionBudget.MAX_BACKGROUND_OPERATIONS;

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var caller =
          executor.submit(() ->
              CanonicalGameplayOriginalNewCapacityExecutionBudget.runBackground(
                  CLOCK,
                  CLOCK.millis() + 4000,
                  budget -> {
                    owner
                        .forTransferBudget(budget)
                        .withTransferReadback(ignored -> readbackReturned.countDown());
                    return true;
                  }));
      try {
        assertThat(readbackReturned.await(2, TimeUnit.SECONDS)).isTrue();
        verify(connection, timeout(1000)).closeAsync();
        assertPermitRemainsHeld(operations, operationMaximum);
        assertPermitRemainsHeld(background, backgroundMaximum);
        assertThat(caller.isDone()).isFalse();

        close.complete(null);
        assertThat(caller.get(2, TimeUnit.SECONDS)).isTrue();
      } finally {
        close.complete(null);
      }
    }
  }

  private static void assertPermitRemainsHeld(Semaphore permits, int maximum)
      throws InterruptedException {
    boolean acquired = permits.tryAcquire(maximum, 100, TimeUnit.MILLISECONDS);
    if (acquired) permits.release(maximum);
    assertThat(acquired).isFalse();
  }

  @Test
  void actualOwnerReadReturnsAtDeadlineAndLateAcquisitionNeverExecutes() throws Exception {
    ConnectionProvider provider = mock();
    Connection connection = mock();
    RedisClient redis = mock();
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    when(provider.acquire())
        .thenAnswer(invocation -> {
          entered.countDown();
          awaitUninterruptibly(release);
          return connection;
        });
    DSLContext dsl = source(provider);
    var owner =
        new CanonicalGameplayOriginalNewCapacityExecutionOwner(
            dsl,
            redis,
            RedisScriptCatalog.fromContributions(
                List.of(new CanonicalGameplayOriginalNewFinalAdmissionRedisScriptContribution())),
            CLOCK,
            new CanonicalGameplayTenantGameplayTagRepository(dsl, "prod", 1));
    var request = CapacityAdmissionTestFixture.playerRequest();
    var reservation =
        new TenantCapacityAdmission.Result(
            request.identity(),
            request.admissionAttemptId(),
            request.capacityRearmFence(),
            request.mutationDigest(),
            UUID.randomUUID(),
            1,
            request.evidence().expiresAt(),
            TenantCapacityAdmission.State.RESERVED,
            request.evidence().snapshotIdentity(),
            request.evidence().evaluationIdentity());
    var scheduler = Executors.newSingleThreadScheduledExecutor();
    long started = System.nanoTime();
    try (var caller =
        new WatchdogCaller(
            CompletableFuture.runAsync(() ->
                assertThatThrownBy(() ->
                        Context.current()
                            .withDeadlineAfter(2, TimeUnit.SECONDS, scheduler)
                            .call(() -> owner.readExact(request, reservation)))
                    .isInstanceOf(IllegalStateException.class)),
            release::countDown)) {
      caller.result().get(3500, TimeUnit.MILLISECONDS);
      assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(3500);
      assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
      Semaphore background =
          (Semaphore)
              java.util.Objects.requireNonNull(
                  ReflectionTestUtils.getField(
                      CanonicalGameplayOriginalNewCapacityExecutionBudget.class,
                      "BACKGROUND_OPERATIONS"));
      assertThat(background.availablePermits())
          .isEqualTo(
              CanonicalGameplayOriginalNewCapacityExecutionBudget.MAX_BACKGROUND_OPERATIONS - 1);
      verifyNoInteractions(connection, redis);
      release.countDown();
      verify(provider, timeout(2000)).release(connection);
      verifyNoInteractions(connection, redis);
      assertThat(dsl.configuration().connectionProvider())
          .isInstanceOf(ControlledConnectionProvider.class);
    } finally {
      scheduler.shutdownNow();
    }
  }

  @Test
  void actualTransferCommittedReadBoundsAcquisitionAndCannotExecuteAfterItsDeadline()
      throws Exception {
    ConnectionProvider provider = mock();
    Connection connection = mock();
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    when(provider.acquire())
        .thenAnswer(invocation -> {
          entered.countDown();
          awaitUninterruptibly(release);
          return connection;
        });
    var inventory = new CanonicalGameplayBindingInventoryRepository(source(provider));
    var candidate = mock(CanonicalGameplayOriginalNewCandidate.class);
    UUID transitionId = UUID.randomUUID();
    when(candidate.bindingDecisionId()).thenReturn(transitionId);
    long started = System.nanoTime();
    var caller = Executors.newVirtualThreadPerTaskExecutor();
    var pending = new AtomicReference<java.util.concurrent.Future<?>>();
    try (AutoCloseable cleanup =
        () -> {
          release.countDown();
          var submitted = pending.get();
          if (submitted != null) submitted.cancel(true);
          caller.shutdownNow();
          assertThat(caller.awaitTermination(5, TimeUnit.SECONDS))
              .as("bounded acquisition caller termination")
              .isTrue();
          verify(provider, timeout(2000)).release(connection);
          verifyNoInteractions(connection);
        }) {
      var read =
          caller.submit(() ->
              inventory.withinTransferRead(
                  CLOCK,
                  CLOCK.millis() + 1000,
                  scoped -> scoped.readRegionTransferCommittedSource(transitionId, candidate)));
      pending.set(read);
      assertThatThrownBy(() -> read.get(3, TimeUnit.SECONDS))
          .isInstanceOf(java.util.concurrent.ExecutionException.class)
          .hasCauseInstanceOf(IllegalStateException.class);
      assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
      assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(2500);
      verifyNoInteractions(connection);
    }
  }

  @Test
  void handoffStatusReadBoundsAcquisitionAndCannotExecuteAfterItsDeadline() throws Exception {
    ConnectionProvider provider = mock();
    Connection connection = mock();
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    when(provider.acquire())
        .thenAnswer(invocation -> {
          entered.countDown();
          awaitUninterruptibly(release);
          return connection;
        });
    var dsl = source(provider);
    var transferRepository =
        new CanonicalGameplayRegionOwnerTransferRepository(
            dsl, CLOCK, mock(CanonicalGameplayTenantGameplayTagRepository.class));
    var transfer =
        new CanonicalGameplayRegionOwnerTransferCoordinator(
            dsl,
            CLOCK,
            transferRepository,
            mock(CanonicalGameplayOriginalNewCapacityExecutionOwner.class),
            mock(CanonicalGameplayProtectedIncarnationReceiver.class));
    UUID tenantId = UUID.randomUUID();
    UUID gameInstanceId = UUID.randomUUID();
    var lifecycleRequest =
        new WorldCanonicalInstanceLifecycleEvidence.Request(
            WorldCanonicalInstanceLifecycleEvidence.Request.SCHEMA_VERSION,
            UUID.randomUUID(),
            "prod",
            tenantId,
            "world",
            gameInstanceId,
            UUID.randomUUID(),
            "SHARED",
            true,
            "budget-test",
            UUID.randomUUID(),
            "sha256:" + "a".repeat(64),
            "sha256:" + "b".repeat(64),
            "sha256:" + "c".repeat(64));
    var regionSource =
        new CanonicalGameplayRegionRuntimeStatusRepository.Source(
            tenantId,
            gameInstanceId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            1L,
            0L,
            lifecycleRequest,
            UUID.randomUUID(),
            "d".repeat(64),
            "sha256:" + "e".repeat(64));

    var caller = Executors.newVirtualThreadPerTaskExecutor();
    var pending = new AtomicReference<java.util.concurrent.Future<?>>();
    try (AutoCloseable cleanup =
        () -> {
          release.countDown();
          var submitted = pending.get();
          if (submitted != null) submitted.cancel(true);
          caller.shutdownNow();
          assertThat(caller.awaitTermination(5, TimeUnit.SECONDS))
              .as("bounded acquisition caller termination")
              .isTrue();
          verify(provider, timeout(2000)).release(connection);
          verifyNoInteractions(connection);
        }) {
      var read =
          caller.submit(() ->
              transfer.withStatusRepository(
                  CLOCK.millis() + 1000,
                  statusRepository -> statusRepository.findTimelinePreparation(regionSource)));
      pending.set(read);
      assertThatThrownBy(() -> read.get(3, TimeUnit.SECONDS))
          .isInstanceOf(java.util.concurrent.ExecutionException.class)
          .hasCauseInstanceOf(IllegalStateException.class);
      assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
      verifyNoInteractions(connection);
    }
  }

  @Test
  void transferReadDerivesItsOwnTransactionProviderAndKeepsTheOriginalAbsoluteDeadline() {
    ConnectionProvider provider = mock();
    Connection connection = mock();
    when(provider.acquire()).thenReturn(connection);
    try {
      when(connection.getAutoCommit()).thenReturn(true);
    } catch (java.sql.SQLException failure) {
      throw new AssertionError(failure);
    }
    var original = source(provider);
    var sharedTransactions = mock(org.jooq.TransactionProvider.class);
    original.configuration().set(sharedTransactions);
    var inventory = new CanonicalGameplayBindingInventoryRepository(original);
    AtomicLong wall = new AtomicLong(CLOCK.millis());
    Clock clock = mock();
    when(clock.millis()).thenAnswer(ignored -> wall.get());
    long deadline = wall.get() + 2000;
    boolean read =
        inventory.withinTransferRead(
            clock,
            deadline,
            scoped -> {
              var sql = (DSLContext) ReflectionTestUtils.getField(scoped, "dsl");
              assertThat(sql).isNotNull();
              assertThat(sql.configuration().connectionProvider()).isNotSameAs(provider);
              assertThat(sql.configuration().transactionProvider()).isNotSameAs(sharedTransactions);
              return sql.transactionResult(
                  transaction -> DSL.using(transaction).connectionResult(owned -> Boolean.TRUE));
            });
    assertThat(read).isTrue();
    verify(provider).acquire();
    verify(provider).release(connection);
    verifyNoInteractions(sharedTransactions);
    wall.set(deadline);
    assertThatThrownBy(() -> inventory.withinTransferRead(clock, deadline, scoped -> Boolean.TRUE))
        .isInstanceOf(IllegalStateException.class);
    verify(provider, org.mockito.Mockito.times(1)).acquire();
  }

  @Test
  void activeStatementCancellationFinishesBeforePoolReturn() throws Exception {
    ConnectionProvider provider = mock();
    PreparedStatement statement = mock();
    Connection connection = preparedConnection(statement);
    when(provider.acquire()).thenReturn(connection);
    CountDownLatch executing = new CountDownLatch(1), finishExecution = new CountDownLatch(1);
    CountDownLatch cancelling = new CountDownLatch(1), finishCancellation = new CountDownLatch(1);
    CountDownLatch cancelled = new CountDownLatch(1), released = new CountDownLatch(1);
    AtomicBoolean returned = new AtomicBoolean();
    AtomicBoolean orderingViolation = new AtomicBoolean();
    when(statement.execute())
        .thenAnswer(invocation -> {
          executing.countDown();
          awaitUninterruptibly(finishExecution);
          return true;
        });
    doAnswer(
            invocation -> {
              if (returned.get()) orderingViolation.set(true);
              cancelling.countDown();
              awaitUninterruptibly(finishCancellation);
              if (returned.get()) orderingViolation.set(true);
              cancelled.countDown();
              return null;
            })
        .when(statement)
        .cancel();
    doAnswer(
            invocation -> {
              returned.set(true);
              if (cancelled.getCount() != 0) orderingViolation.set(true);
              released.countDown();
              return null;
            })
        .when(provider)
        .release(connection);
    // Initialize the fixture DSL before starting the execution/cancellation budget.
    DSLContext dsl = source(provider);
    long started = System.nanoTime();
    try (var caller =
        new WatchdogCaller(
            CompletableFuture.runAsync(() ->
                assertThatThrownBy(() ->
                        CanonicalGameplayOriginalNewCapacityExecutionBudget.run(
                            CLOCK, CLOCK.millis() + 2000, budget -> execute(budget.sql(dsl))))
                    .isInstanceOf(IllegalStateException.class)),
            () -> {
              finishExecution.countDown();
              finishCancellation.countDown();
            })) {
      caller.result().get(3500, TimeUnit.MILLISECONDS);
      assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(3500);
      assertThat(executing.await(1, TimeUnit.SECONDS)).isTrue();
      assertThat(cancelling.await(1, TimeUnit.SECONDS)).isTrue();
      finishExecution.countDown();
      verify(provider, never()).release(connection);
      finishCancellation.countDown();
      assertThat(cancelled.await(2, TimeUnit.SECONDS)).isTrue();
      assertThat(released.await(2, TimeUnit.SECONDS)).isTrue();
      assertThat(orderingViolation).isFalse();
      verify(provider, timeout(2000)).release(connection);
      verify(statement).cancel();
      verify(statement).setQueryTimeout(anyInt());
    }
  }

  @Test
  void cancellationRetiresBeforeDelayedDriverWorkResumes() throws Exception {
    ConnectionProvider delegate = mock();
    var provider = new ControlledConnectionProvider(delegate);
    PreparedStatement statement = mock();
    Connection connection = preparedConnection(statement);
    when(delegate.acquire()).thenReturn(connection);
    CountDownLatch insideDriver = new CountDownLatch(1), resumeDriver = new CountDownLatch(1);
    when(statement.execute())
        .thenAnswer(invocation -> {
          insideDriver.countDown();
          awaitUninterruptibly(resumeDriver);
          if (provider.retired(connection)) throw new java.sql.SQLException("retired connection");
          return true;
        });
    DSLContext dsl = source(provider);
    Semaphore permits = operationPermits();
    int availableBefore = permits.availablePermits();
    try (var context = Context.current().withCancellation();
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var caller =
          executor.submit(
              context.wrap(() ->
                  assertThatThrownBy(() ->
                          CanonicalGameplayOriginalNewCapacityExecutionBudget.run(
                              CLOCK, Long.MAX_VALUE, budget -> execute(budget.sql(dsl))))
                      .isInstanceOf(
                          CanonicalGameplayOriginalNewCapacityExecutionBudget.BudgetUnavailable
                              .class)));
      try {
        assertThat(insideDriver.await(2, TimeUnit.SECONDS)).isTrue();
        context.cancel(null);
        caller.get(2, TimeUnit.SECONDS);
        assertThat(provider.retirementAttempted.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(provider.retired(connection)).isTrue();
        assertThat(permits.availablePermits()).isEqualTo(availableBefore - 1);
        verify(delegate, never()).release(connection);

        resumeDriver.countDown();
        verify(delegate, timeout(2000)).release(connection);
        assertThat(
                permits.tryAcquire(
                    CanonicalGameplayOriginalNewCapacityExecutionBudget.MAX_IN_FLIGHT_OPERATIONS,
                    2,
                    TimeUnit.SECONDS))
            .isTrue();
        permits.release(
            CanonicalGameplayOriginalNewCapacityExecutionBudget.MAX_IN_FLIGHT_OPERATIONS);
      } finally {
        resumeDriver.countDown();
      }
    }
  }

  @Test
  void failedConnectionRetirementKeepsConnectionAndOperationPermit() throws Exception {
    ConnectionProvider delegate = mock();
    var provider = new ControlledConnectionProvider(delegate);
    provider.failRetirement = true;
    PreparedStatement statement = mock();
    Connection connection = preparedConnection(statement);
    when(delegate.acquire()).thenReturn(connection);
    CountDownLatch insideDriver = new CountDownLatch(1), resumeDriver = new CountDownLatch(1);
    when(statement.execute())
        .thenAnswer(invocation -> {
          insideDriver.countDown();
          awaitUninterruptibly(resumeDriver);
          return true;
        });
    DSLContext dsl = source(provider);
    AtomicReference<CanonicalGameplayOriginalNewCapacityExecutionBudget> captured =
        new AtomicReference<>();
    Semaphore permits = operationPermits();
    AtomicInteger retained =
        (AtomicInteger)
            java.util.Objects.requireNonNull(
                ReflectionTestUtils.getField(
                    CanonicalGameplayOriginalNewCapacityExecutionBudget.class,
                    "RETIRE_FAILURE_RETAINED_OPERATIONS"));
    int beforeRetained = retained.get();
    int availableBefore = permits.availablePermits();
    try (var context = Context.current().withCancellation();
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var caller =
          executor.submit(
              context.wrap(() ->
                  assertThatThrownBy(() ->
                          CanonicalGameplayOriginalNewCapacityExecutionBudget.run(
                              CLOCK,
                              Long.MAX_VALUE,
                              budget -> {
                                captured.set(budget);
                                return execute(budget.sql(dsl));
                              }))
                      .isInstanceOf(
                          CanonicalGameplayOriginalNewCapacityExecutionBudget.BudgetUnavailable
                              .class)));
      try {
        assertThat(insideDriver.await(2, TimeUnit.SECONDS)).isTrue();
        context.cancel(null);
        caller.get(2, TimeUnit.SECONDS);
        assertThat(provider.retirementAttempted.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(provider.retired(connection)).isFalse();
        resumeDriver.countDown();
        var budget = captured.get();
        org.awaitility.Awaitility.await()
            .atMost(2, TimeUnit.SECONDS)
            .untilAsserted(() ->
                assertThat(ReflectionTestUtils.getField(budget, "workerDone")).isEqualTo(true));
        assertThat(permits.availablePermits()).isEqualTo(availableBefore - 1);
        assertThat(retained.get()).isEqualTo(beforeRetained + 1);
        assertThat((AtomicInteger) ReflectionTestUtils.getField(budget, "outstandingConnections"))
            .hasValue(1);
        assertThat(ReflectionTestUtils.getField(budget, "released")).isEqualTo(false);
        verify(delegate, never()).release(connection);
      } finally {
        resumeDriver.countDown();
        if (retained.get() == beforeRetained + 1) retained.decrementAndGet();
        if (permits.availablePermits() == availableBefore - 1) permits.release();
        var budget = captured.get();
        if (budget != null) {
          synchronized (
              java.util.Objects.requireNonNull(ReflectionTestUtils.getField(budget, "resources"))) {
            ((java.util.Map<?, ?>) ReflectionTestUtils.getField(budget, "connections")).clear();
            ((AtomicInteger) ReflectionTestUtils.getField(budget, "outstandingConnections")).set(0);
          }
        }
      }
    }
  }

  @Test
  void uncertainProviderReleaseRetainsOnePermitAndExposesItThroughMicrometer() throws Exception {
    ConnectionProvider provider = mock();
    Connection first = mock(), second = mock();
    when(provider.acquire()).thenReturn(first, second);
    doThrow(new IllegalStateException("Controlled uncertain pool return"))
        .when(provider)
        .release(org.mockito.ArgumentMatchers.any(Connection.class));
    AtomicReference<CanonicalGameplayOriginalNewCapacityExecutionBudget> captured =
        new AtomicReference<>();
    var registry = new SimpleMeterRegistry();
    try {
      Metrics.addRegistry(registry);
      AtomicInteger retained =
          (AtomicInteger)
              ReflectionTestUtils.getField(
                  CanonicalGameplayOriginalNewCapacityExecutionBudget.class,
                  "RELEASE_FAILURE_RETAINED_OPERATIONS");
      Semaphore permits =
          (Semaphore)
              ReflectionTestUtils.getField(
                  CanonicalGameplayOriginalNewCapacityExecutionBudget.class, "OPERATIONS");
      assertThat(retained).isNotNull();
      assertThat(permits).isNotNull();
      Semaphore background =
          (Semaphore)
              java.util.Objects.requireNonNull(
                  ReflectionTestUtils.getField(
                      CanonicalGameplayOriginalNewCapacityExecutionBudget.class,
                      "BACKGROUND_OPERATIONS"));
      int backgroundAvailable = background.availablePermits();
      int before = retained.get();
      int available = permits.availablePermits();
      var rollbackFailure = new AtomicReference<Throwable>();
      var autoCommitFailure = new AtomicReference<Throwable>();
      try {
        assertThatThrownBy(() ->
                CanonicalGameplayOriginalNewCapacityExecutionBudget.runBackground(
                    CLOCK,
                    Long.MAX_VALUE,
                    budget -> {
                      captured.set(budget);
                      var scoped =
                          budget.sql(source(provider)).configuration().connectionProvider();
                      Connection proxy = java.util.Objects.requireNonNull(scoped.acquire());
                      scoped.acquire();
                      assertThatThrownBy(() -> scoped.release(proxy))
                          .hasMessage("Controlled uncertain pool return");
                      rollbackFailure.set(
                          org.assertj.core.api.Assertions.catchThrowable(proxy::rollback));
                      autoCommitFailure.set(
                          org.assertj.core.api.Assertions.catchThrowable(
                              () -> proxy.setAutoCommit(true)));
                      return true;
                    }))
            .isInstanceOf(IllegalStateException.class);
        assertThat(rollbackFailure.get()).isInstanceOf(java.sql.SQLException.class);
        assertThat(autoCommitFailure.get()).isInstanceOf(java.sql.SQLException.class);
        assertThat(
                registry
                    .get("original_new_capacity_sql_release_failure_retained_operations")
                    .gauge()
                    .value())
            .isEqualTo(before + 1);
        assertThat(permits.availablePermits()).isEqualTo(available - 1);
        assertThat(background.availablePermits()).isEqualTo(backgroundAvailable - 1);
        assertThat(ReflectionTestUtils.getField(captured.get(), "released")).isEqualTo(false);
        assertThat(
                (AtomicInteger)
                    ReflectionTestUtils.getField(captured.get(), "outstandingConnections"))
            .hasValue(2);
        verify(first, never()).rollback();
        verify(first, never()).setAutoCommit(true);
        verify(provider).release(first);
        verify(provider).release(second);
      } finally {
        // Only mocked resources exist here. Restore JVM-wide fixture state after proving that
        // production code neither retries an uncertain return nor releases its retained permit.
        if (retained.get() == before + 1) {
          retained.decrementAndGet();
        }
        if (permits.availablePermits() == available - 1) permits.release();
        if (background.availablePermits() == backgroundAvailable - 1) background.release();
      }
    } finally {
      Metrics.removeRegistry(registry);
      registry.close();
    }
  }

  @Test
  void exceptionalOrCancelledRedisDisposalRetainsTheBudgetAndReportsIt() {
    var registry = new SimpleMeterRegistry();
    Metrics.addRegistry(registry);
    var retained =
        (AtomicInteger)
            java.util.Objects.requireNonNull(
                ReflectionTestUtils.getField(
                    CanonicalGameplayOriginalNewCapacityExecutionBudget.class,
                    "REDIS_CLOSE_FAILURE_RETAINED_OPERATIONS"));
    var operations =
        (Semaphore)
            java.util.Objects.requireNonNull(
                ReflectionTestUtils.getField(
                    CanonicalGameplayOriginalNewCapacityExecutionBudget.class, "OPERATIONS"));
    var background =
        (Semaphore)
            java.util.Objects.requireNonNull(
                ReflectionTestUtils.getField(
                    CanonicalGameplayOriginalNewCapacityExecutionBudget.class,
                    "BACKGROUND_OPERATIONS"));
    int priorOperations = operations.availablePermits();
    int priorBackground = background.availablePermits();
    int priorRetained = retained.get();
    var logger =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(
                CanonicalGameplayOriginalNewCapacityExecutionBudget.class);
    var events =
        new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    events.start();
    logger.addAppender(events);
    CompletableFuture<Void> late = new CompletableFuture<>();
    CompletableFuture<Void> cancelled = new CompletableFuture<>();
    try {
      assertThatThrownBy(() ->
              CanonicalGameplayOriginalNewCapacityExecutionBudget.runBackground(
                  CLOCK,
                  Long.MAX_VALUE,
                  budget -> {
                    budget.awaitAsyncClose(
                        CompletableFuture.failedFuture(
                            new IllegalStateException(
                                "fixture diagnostic marker must not be emitted")));
                    return true;
                  }))
          .isExactlyInstanceOf(
              CanonicalGameplayOriginalNewCapacityExecutionBudget.BudgetUnavailable.class);
      CanonicalGameplayOriginalNewCapacityExecutionBudget.runBackground(
          CLOCK,
          Long.MAX_VALUE,
          budget -> {
            budget.retainUntilAsyncClose(cancelled);
            budget.retainUntilAsyncClose(late);
            return true;
          });
      assertThat(events.list).hasSize(1);
      assertThat(cancelled.cancel(false)).isTrue();
      assertThat(late.completeExceptionally(new IllegalStateException("second close failure")))
          .isTrue();
      assertThat(events.list)
          .extracting(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
          .containsExactly(
              "Original NEW Redis close failed; operation permit retained (category=exception)",
              "Original NEW Redis close failed; operation permit retained (category=cancelled)");
      assertThat(events.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
      assertThat(operations.availablePermits()).isEqualTo(priorOperations - 2);
      assertThat(background.availablePermits()).isEqualTo(priorBackground - 2);
      assertThat(
              registry
                  .get("original_new_capacity_redis_close_failure_retained_operations")
                  .gauge()
                  .value())
          .isEqualTo(priorRetained + 2);
    } finally {
      late.complete(null);
      cancelled.complete(null);
      logger.detachAppender(events);
      events.stop();
      operations.release(priorOperations - operations.availablePermits());
      background.release(priorBackground - background.availablePermits());
      retained.set(priorRetained);
      Metrics.removeRegistry(registry);
      registry.close();
    }
  }

  @Test
  void cancellationAfterPoolReturnCannotCancelAReusedConnectionStatement() throws Exception {
    ConnectionProvider provider = mock();
    PreparedStatement statement = mock();
    Connection connection = preparedConnection(statement);
    when(provider.acquire()).thenReturn(connection);
    when(statement.execute()).thenReturn(true);
    CountDownLatch returned = new CountDownLatch(1), finish = new CountDownLatch(1);
    CountDownLatch cancellationWorkerFinished = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              returned.countDown();
              return null;
            })
        .when(provider)
        .release(connection);
    DSLContext dsl = source(provider);
    try (var context = Context.current().withCancellation();
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var caller =
          executor.submit(
              context.wrap(() -> {
                assertThatThrownBy(() ->
                        CanonicalGameplayOriginalNewCapacityExecutionBudget.run(
                            CLOCK,
                            Long.MAX_VALUE,
                            budget -> {
                              execute(budget.sql(dsl));
                              awaitUninterruptibly(finish);
                              return true;
                            },
                            cancellation ->
                                Thread.ofVirtual()
                                    .name("test-original-new-capacity-cancel")
                                    .unstarted(() -> {
                                      try {
                                        cancellation.run();
                                      } finally {
                                        cancellationWorkerFinished.countDown();
                                      }
                                    })))
                    .isInstanceOf(IllegalStateException.class);
              }));
      try {
        assertThat(returned.await(2, TimeUnit.SECONDS)).isTrue();
        context.cancel(null);
        caller.get(2, TimeUnit.SECONDS);
      } finally {
        finish.countDown();
      }
    }
    assertThat(cancellationWorkerFinished.await(2, TimeUnit.SECONDS)).isTrue();
    awaitResourceAndCancellationWorkerCleanup();
    verify(statement, never()).cancel();
  }

  @Test
  void blockedAcquisitionsKeepAllFourSlotsAfterCallerTimeoutAndRejectWithoutQueueing()
      throws Exception {
    ConnectionProvider provider = mock();
    Connection connection = mock();
    int maximum = CanonicalGameplayOriginalNewCapacityExecutionBudget.MAX_IN_FLIGHT_OPERATIONS;
    CountDownLatch entered = new CountDownLatch(maximum), release = new CountDownLatch(1);
    when(provider.acquire())
        .thenAnswer(invocation -> {
          entered.countDown();
          awaitUninterruptibly(release);
          return connection;
        });
    DSLContext source = source(provider);
    var callers = new ArrayList<CompletableFuture<Void>>();
    var executor = Executors.newFixedThreadPool(maximum);
    try {
      for (int index = 0; index < maximum; index++)
        callers.add(
            CompletableFuture.runAsync(
                () ->
                    assertThatThrownBy(() ->
                            CanonicalGameplayOriginalNewCapacityExecutionBudget.run(
                                CLOCK,
                                CLOCK.millis() + 3000,
                                budget -> execute(budget.sql(source))))
                        .isInstanceOf(IllegalStateException.class),
                executor));
      assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
      for (var caller : callers) assertThat(caller.isDone()).isFalse();
      CompletableFuture.allOf(callers.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
      assertPromptBudgetRefusal(
          executor,
          () ->
              CanonicalGameplayOriginalNewCapacityExecutionBudget.run(
                  CLOCK, Long.MAX_VALUE, budget -> execute(budget.sql(source))));
      verify(provider, org.mockito.Mockito.times(maximum)).acquire();
      verifyNoInteractions(connection);
    } finally {
      release.countDown();
      executor.shutdownNow();
      verify(provider, timeout(2000).times(maximum)).release(connection);
    }
  }

  @Test
  void backgroundSaturationReservesAdmissionCapacityWithoutExceedingCombinedFourSlots()
      throws Exception {
    ConnectionProvider provider = mock();
    Connection connection = mock();
    int backgroundMaximum =
        CanonicalGameplayOriginalNewCapacityExecutionBudget.MAX_BACKGROUND_OPERATIONS;
    int maximum = CanonicalGameplayOriginalNewCapacityExecutionBudget.MAX_IN_FLIGHT_OPERATIONS;
    CountDownLatch backgroundEntered = new CountDownLatch(backgroundMaximum);
    CountDownLatch allEntered = new CountDownLatch(maximum);
    CountDownLatch release = new CountDownLatch(1);
    when(provider.acquire())
        .thenAnswer(invocation -> {
          backgroundEntered.countDown();
          allEntered.countDown();
          awaitUninterruptibly(release);
          return connection;
        });
    DSLContext source = source(provider);
    var callers = new ArrayList<CompletableFuture<Void>>();
    var executor = Executors.newFixedThreadPool(maximum);
    try {
      for (int index = 0; index < backgroundMaximum; index++)
        callers.add(
            CompletableFuture.runAsync(
                () ->
                    assertThatThrownBy(() ->
                            CanonicalGameplayOriginalNewCapacityExecutionBudget.runBackground(
                                CLOCK,
                                CLOCK.millis() + 1000,
                                budget -> execute(budget.sql(source))))
                        .isInstanceOf(IllegalStateException.class),
                executor));
      assertThat(backgroundEntered.await(2, TimeUnit.SECONDS)).isTrue();
      var refused = new AtomicBoolean();
      assertPromptBudgetRefusal(
          executor,
          () ->
              CanonicalGameplayOriginalNewCapacityExecutionBudget.runBackground(
                  CLOCK,
                  Long.MAX_VALUE,
                  budget -> {
                    refused.set(true);
                    return true;
                  }));
      assertThat(refused).isFalse();
      boolean admitted =
          CanonicalGameplayOriginalNewCapacityExecutionBudget.run(
              CLOCK, Long.MAX_VALUE, budget -> Boolean.TRUE);
      assertThat(admitted).isTrue();
      for (int index = backgroundMaximum; index < maximum; index++)
        callers.add(
            CompletableFuture.runAsync(
                () ->
                    assertThatThrownBy(() ->
                            CanonicalGameplayOriginalNewCapacityExecutionBudget.run(
                                CLOCK,
                                CLOCK.millis() + 1000,
                                budget -> execute(budget.sql(source))))
                        .isInstanceOf(IllegalStateException.class),
                executor));
      assertThat(allEntered.await(2, TimeUnit.SECONDS)).isTrue();
      CompletableFuture.allOf(callers.toArray(CompletableFuture[]::new)).get(2, TimeUnit.SECONDS);
      // Callers timed out, but blocked acquisition still owns all four permits.
      assertPromptBudgetRefusal(
          executor,
          () ->
              CanonicalGameplayOriginalNewCapacityExecutionBudget.run(
                  CLOCK, Long.MAX_VALUE, budget -> execute(budget.sql(source))));
      verify(provider, org.mockito.Mockito.times(maximum)).acquire();
      verifyNoInteractions(connection);
    } finally {
      release.countDown();
      executor.shutdownNow();
      verify(provider, timeout(2000).times(maximum)).release(connection);
    }
  }

  private static void assertPromptBudgetRefusal(ExecutorService executor, Runnable extra)
      throws Exception {
    // The holders stay blocked. The independent watchdog is shorter than the five-second
    // operation budget, so an operation that queues and times out cannot satisfy this assertion.
    var refusal =
        executor.submit(() ->
            assertThatThrownBy(extra::run)
                .isInstanceOf(
                    CanonicalGameplayOriginalNewCapacityExecutionBudget.BudgetUnavailable.class));
    try {
      refusal.get(2, TimeUnit.SECONDS);
    } finally {
      refusal.cancel(true);
    }
  }

  @Test
  void explicitDeadlineIsOneBudgetAcrossQueriesWithoutGrpcContext() throws Exception {
    ConnectionProvider provider = mock();
    PreparedStatement statement = mock();
    Connection connection = preparedConnection(statement);
    when(provider.acquire()).thenReturn(connection);
    AtomicInteger executions = new AtomicInteger(), queryTimeout = new AtomicInteger();
    AtomicInteger secondTimeout = new AtomicInteger();
    AtomicLong wallMillis = new AtomicLong(CLOCK.millis());
    Clock clock = mock();
    when(clock.millis()).thenAnswer(ignored -> wallMillis.get());
    CountDownLatch second = new CountDownLatch(1), release = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              queryTimeout.set(invocation.getArgument(0));
              return null;
            })
        .when(statement)
        .setQueryTimeout(anyInt());
    when(statement.execute())
        .thenAnswer(invocation -> {
          if (executions.incrementAndGet() == 1) {
            wallMillis.addAndGet(1500L);
          } else {
            secondTimeout.set(queryTimeout.get());
            second.countDown();
            awaitUninterruptibly(release);
          }
          return true;
        });
    DSLContext dsl = source(provider);
    try (var caller =
        new WatchdogCaller(
            CompletableFuture.runAsync(() ->
                assertThatThrownBy(() ->
                        CanonicalGameplayOriginalNewCapacityExecutionBudget.run(
                            clock,
                            clock.millis() + 2000,
                            budget -> {
                              var sql = budget.sql(dsl);
                              execute(sql);
                              return execute(sql);
                            }))
                    .isInstanceOf(IllegalStateException.class)),
            release::countDown)) {
      caller.result().get(3500, TimeUnit.MILLISECONDS);
      assertThat(second.await(1, TimeUnit.SECONDS)).isTrue();
      assertThat(secondTimeout)
          .as("second query receives at most the remaining 500 ms")
          .hasValue(1);
      verify(statement, timeout(1000)).cancel();
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void secondQueryCannotRenewAnAlreadyConsumedAbsoluteDeadline(boolean exhaustFirstQuery)
      throws Exception {
    ConnectionProvider provider = mock();
    PreparedStatement statement = mock();
    when(provider.acquire()).thenReturn(preparedConnection(statement));
    AtomicInteger executions = new AtomicInteger();
    AtomicLong wallMillis = new AtomicLong(CLOCK.millis());
    Clock clock = mock();
    when(clock.millis()).thenAnswer(ignored -> wallMillis.get());
    long absoluteDeadline = clock.millis() + 5_000L;
    when(statement.execute())
        .thenAnswer(invocation -> {
          if (executions.incrementAndGet() == 1 && exhaustFirstQuery) {
            wallMillis.set(absoluteDeadline + 1L);
          }
          return true;
        });
    DSLContext dsl = source(provider);
    Runnable bothQueries =
        () ->
            CanonicalGameplayOriginalNewCapacityExecutionBudget.run(
                clock,
                absoluteDeadline,
                budget -> {
                  var sql = budget.sql(dsl);
                  execute(sql);
                  return execute(sql);
                });
    if (exhaustFirstQuery) {
      assertThatThrownBy(bothQueries::run)
          .isInstanceOf(
              CanonicalGameplayOriginalNewCapacityExecutionBudget.BudgetUnavailable.class);
      assertThat(executions).hasValue(1);
    } else {
      bothQueries.run();
      assertThat(executions).hasValue(2);
    }
  }

  @Test
  void expiredExplicitBudgetDoesNotAcquireAndTagRebindingPreservesPrivateConfiguration() {
    ConnectionProvider provider = mock();
    DSLContext original = source(provider);
    DSLContext operation = mock();
    var tags = new CanonicalGameplayTenantGameplayTagRepository(original, "capacity-test", 7);
    var rebound = tags.withCapacityExecutionDsl(operation);
    assertThat(ReflectionTestUtils.getField(rebound, "dsl")).isSameAs(operation);
    assertThat(ReflectionTestUtils.getField(rebound, "workloadNamespace"))
        .isEqualTo("capacity-test");
    assertThat(ReflectionTestUtils.getField(rebound, "allocationVersion")).isEqualTo(7);
    assertThat(ReflectionTestUtils.getField(tags, "dsl")).isSameAs(original);
    assertThatThrownBy(() ->
            CanonicalGameplayOriginalNewCapacityExecutionBudget.run(
                CLOCK, CLOCK.millis() - 1, budget -> execute(budget.sql(original))))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(provider, operation);
  }

  @Test
  void cancellationWorkerStartupFailureRetainsOnlyActualOutstandingResources() throws Exception {
    ConnectionProvider provider = mock();
    PreparedStatement statement = mock();
    Connection connection = preparedConnection(statement);
    when(provider.acquire()).thenReturn(connection);
    CountDownLatch entered = new CountDownLatch(1), finish = new CountDownLatch(1);
    AtomicReference<CanonicalGameplayOriginalNewCapacityExecutionBudget> captured =
        new AtomicReference<>();
    when(statement.execute())
        .thenAnswer(invocation -> {
          entered.countDown();
          awaitUninterruptibly(finish);
          return true;
        });
    DSLContext dsl = source(provider);
    try (var context = Context.current().withCancellation();
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var caller =
          executor.submit(
              context.wrap(() -> {
                assertThatThrownBy(() ->
                        CanonicalGameplayOriginalNewCapacityExecutionBudget.run(
                            CLOCK,
                            Long.MAX_VALUE,
                            budget -> {
                              captured.set(budget);
                              return execute(budget.sql(dsl));
                            },
                            ignored -> {
                              throw new java.util.concurrent.RejectedExecutionException(
                                  "Controlled startup refusal");
                            }))
                    .isInstanceOf(IllegalStateException.class);
              }));
      try {
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        context.cancel(null);
        caller.get(2, TimeUnit.SECONDS);
        verify(statement, never()).cancel();
        verify(provider, never()).release(connection);
        assertThat(ReflectionTestUtils.getField(captured.get(), "cancellationDone"))
            .isEqualTo(true);
        finish.countDown();
        verify(provider, timeout(2000)).release(connection);
        assertNoOwnedConnections(captured.get());
        for (int index = 0;
            index
                < 2 * CanonicalGameplayOriginalNewCapacityExecutionBudget.MAX_IN_FLIGHT_OPERATIONS;
            index++) {
          boolean admitted =
              CanonicalGameplayOriginalNewCapacityExecutionBudget.run(
                  CLOCK, Long.MAX_VALUE, budget -> true);
          assertThat(admitted).isTrue();
        }
      } finally {
        finish.countDown();
      }
    }
  }

  @Test
  void exceptionalCallbackDrainsAnAcquiredConnectionBeforeReleasingItsResourcePermit() {
    ConnectionProvider provider = mock();
    Connection connection = mock();
    when(provider.acquire()).thenReturn(connection);
    AtomicReference<CanonicalGameplayOriginalNewCapacityExecutionBudget> captured =
        new AtomicReference<>();
    assertThatThrownBy(() ->
            CanonicalGameplayOriginalNewCapacityExecutionBudget.run(
                CLOCK,
                Long.MAX_VALUE,
                budget -> {
                  captured.set(budget);
                  budget.sql(source(provider)).configuration().connectionProvider().acquire();
                  throw new IllegalStateException(
                      "Controlled exception before ordinary provider release");
                }))
        .isInstanceOf(IllegalStateException.class);
    verify(provider).release(connection);
    assertNoOwnedConnections(captured.get());
  }

  @Test
  void absoluteDeadlineStaysFrozenAcrossSqlWorkAndWallClockPassage() throws Exception {
    Clock clock = mock();
    AtomicLong now = new AtomicLong(1_800_000_000_000L);
    when(clock.millis()).thenAnswer(ignored -> now.get());
    long originalDeadline = now.get() + 4000;
    ConnectionProvider provider = mock();
    PreparedStatement statement = mock();
    when(provider.acquire()).thenReturn(preparedConnection(statement));
    when(statement.execute())
        .thenAnswer(ignored -> {
          now.addAndGet(1000);
          return true;
        });
    DSLContext dsl = source(provider);

    long retainedDeadline =
        CanonicalGameplayOriginalNewCapacityExecutionBudget.run(
            clock,
            originalDeadline,
            budget -> {
              assertThat(budget.absoluteDeadlineMillis()).isEqualTo(originalDeadline);
              execute(budget.sql(dsl));
              assertThat(budget.remainingMillis()).isBetween(1L, 3000L);
              return budget.absoluteDeadlineMillis();
            });

    assertThat(retainedDeadline).isEqualTo(originalDeadline);
    assertThat(now).hasValue(originalDeadline - 3000);
    verify(statement).execute();
  }

  @Test
  void defaultFiveSecondAbsoluteDeadlineDoesNotRenewAfterClockPassage() {
    Clock clock = mock();
    AtomicLong now = new AtomicLong(1_800_000_000_000L);
    when(clock.millis()).thenAnswer(ignored -> now.get());
    long initial = now.get();

    long retainedDeadline =
        CanonicalGameplayOriginalNewCapacityExecutionBudget.run(
            clock,
            Long.MAX_VALUE,
            budget -> {
              long deadline = budget.absoluteDeadlineMillis();
              now.addAndGet(1000);
              assertThat(budget.absoluteDeadlineMillis()).isEqualTo(deadline);
              return deadline;
            });

    assertThat(retainedDeadline).isBetween(initial + 1, initial + 5000);
  }

  @Test
  void incomingGrpcDeadlineCapsTheFrozenAbsoluteDeadline() throws Exception {
    Clock clock =
        Clock.fixed(java.time.Instant.ofEpochMilli(1_800_000_000_000L), java.time.ZoneOffset.UTC);
    var scheduler = Executors.newSingleThreadScheduledExecutor();
    try {
      long retainedDeadline =
          Context.current()
              .withDeadlineAfter(2, TimeUnit.SECONDS, scheduler)
              .call(() ->
                  CanonicalGameplayOriginalNewCapacityExecutionBudget.run(
                      clock, clock.millis() + 4000, budget -> budget.absoluteDeadlineMillis()));
      assertThat(retainedDeadline).isBetween(clock.millis() + 1, clock.millis() + 2000);
    } finally {
      scheduler.shutdownNow();
    }
  }

  private static void assertNoOwnedConnections(
      CanonicalGameplayOriginalNewCapacityExecutionBudget budget) {
    Object monitor =
        java.util.Objects.requireNonNull(ReflectionTestUtils.getField(budget, "resources"));
    synchronized (monitor) {
      assertThat((java.util.Map<?, ?>) ReflectionTestUtils.getField(budget, "connections"))
          .isEmpty();
      assertThat((AtomicInteger) ReflectionTestUtils.getField(budget, "outstandingConnections"))
          .hasValue(0);
    }
  }

  /** A controlled JDBC answer, without invoking a resource-creating method during stubbing. */
  private static Connection preparedConnection(PreparedStatement statement) {
    return mock(
        Connection.class,
        invocation -> {
          if (invocation.getMethod().getName().equals("prepareStatement")) {
            assertThat(invocation.getArgument(0, String.class)).isEqualTo("controlled");
            return statement;
          }
          return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });
  }

  private static DSLContext source(ConnectionProvider provider) {
    ConnectionProvider scoped =
        provider instanceof CanonicalGameplayOriginalNewCapacityExecutionBudget.ConnectionRetirer
            ? provider
            : new ControlledConnectionProvider(provider);
    return DSL.using(new DefaultConfiguration().set(SQLDialect.POSTGRES).set(scoped));
  }

  private static Semaphore operationPermits() {
    return (Semaphore)
        java.util.Objects.requireNonNull(
            ReflectionTestUtils.getField(
                CanonicalGameplayOriginalNewCapacityExecutionBudget.class, "OPERATIONS"));
  }

  private static final class ControlledConnectionProvider
      implements ConnectionProvider,
          CanonicalGameplayOriginalNewCapacityExecutionBudget.ConnectionRetirer {
    private final ConnectionProvider delegate;
    private final java.util.Set<Connection> retired =
        java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private final CountDownLatch retirementAttempted = new CountDownLatch(1);
    private volatile boolean failRetirement;

    private ControlledConnectionProvider(ConnectionProvider delegate) {
      this.delegate = delegate;
    }

    @Override
    public Connection acquire() {
      return delegate.acquire();
    }

    @Override
    public void release(Connection connection) {
      delegate.release(connection);
    }

    @Override
    public synchronized void retire(Connection connection) throws java.sql.SQLException {
      retirementAttempted.countDown();
      if (failRetirement) throw new java.sql.SQLException("controlled retirement uncertainty");
      retired.add(connection);
    }

    private synchronized boolean retired(Connection connection) {
      return retired.contains(connection);
    }
  }

  private static boolean execute(DSLContext sql) {
    return sql.connectionResult(connection -> {
      try (var statement = connection.prepareStatement("controlled")) {
        return statement.execute();
      }
    });
  }

  private static void awaitUninterruptibly(CountDownLatch latch) {
    boolean interrupted = false;
    for (; ; ) {
      try {
        latch.await();
        break;
      } catch (InterruptedException ignored) {
        interrupted = true;
      }
    }
    if (interrupted) Thread.currentThread().interrupt();
  }

  private record WatchdogCaller(CompletableFuture<Void> result, Runnable release)
      implements AutoCloseable {

    @Override
    public void close() throws Exception {
      release.run();
      // Await termination after releasing the fixture. The watchdog owns the assertion
      // outcome; this join ignores that outcome without ignoring a cleanup timeout.
      result.handle((ignored, failure) -> null).get(2, TimeUnit.SECONDS);
    }
  }
}
