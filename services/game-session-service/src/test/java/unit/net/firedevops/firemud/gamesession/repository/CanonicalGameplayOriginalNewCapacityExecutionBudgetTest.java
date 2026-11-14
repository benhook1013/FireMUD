package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
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
import net.firedevops.firemud.gamesession.service.CanonicalGameplayOriginalNewFinalAdmissionRedisScriptContribution;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayRegionLeaseRedisScriptContribution;
import org.jooq.ConnectionProvider;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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
        .thenAnswer(
            ignored ->
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
      context.run(
          () ->
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
        .thenAnswer(
            invocation -> {
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
          executor.submit(
              () ->
                  assertThatThrownBy(
                          () ->
                              CanonicalGameplayOriginalNewCapacityExecutionBudget.runBackground(
                                  CLOCK,
                                  CLOCK.millis() + 500,
                                  budget ->
                                      ReflectionTestUtils.invokeMethod(
                                          owner, "openConnection", budget)))
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
    try {
      prepared =
          owner
              .forHandoffDeadline(CLOCK, CLOCK.millis() + 4000)
              .prepareTimelineInitialization(preparation, Duration.ofSeconds(30), identity);
      assertPermitRemainsHeld(operations, operationMaximum);
      assertPermitRemainsHeld(background, backgroundMaximum);

      long closeStarted = System.nanoTime();
      prepared.close();
      assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - closeStarted)).isLessThan(250);
      verify(connection, timeout(1000)).closeAsync();
      assertPermitRemainsHeld(operations, operationMaximum);
      assertPermitRemainsHeld(background, backgroundMaximum);
      var probeArguments = org.mockito.ArgumentCaptor.forClass(byte[][].class);
      verify(commands, org.mockito.Mockito.times(1))
          .evalsha(any(), any(), any(), probeArguments.capture());
      assertThat(probeArguments.getValue()[1])
          .containsExactly("PROBE".getBytes(java.nio.charset.StandardCharsets.US_ASCII));

      close.complete(null);
      assertThat(operations.tryAcquire(operationMaximum, 2, TimeUnit.SECONDS)).isTrue();
      operations.release(operationMaximum);
      assertThat(background.tryAcquire(backgroundMaximum, 2, TimeUnit.SECONDS)).isTrue();
      background.release(backgroundMaximum);
      verify(connection, org.mockito.Mockito.times(1)).closeAsync();
      // Closing must not dispatch INITIALIZE or repeat the preparation probe.
      verify(commands, org.mockito.Mockito.times(1))
          .evalsha(any(), any(), any(), any(byte[][].class));
    } finally {
      if (prepared != null) prepared.close();
      close.complete(null);
    }
  }

  @Test
  void timelineInitializationUsesPreparedPermitWhileOtherBackgroundSlotIsOccupied()
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
        .thenAnswer(
            invocation -> {
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
        prepared =
            owner
                .forHandoffDeadline(CLOCK, handoffDeadline)
                .prepareTimelineInitialization(preparation, Duration.ofSeconds(30), identity);
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
        assertThat(background.availablePermits()).isZero();
        assertThat(operations.availablePermits()).isEqualTo(operationMaximum - 2);

        var session = prepared;
        initialization = CompletableFuture.supplyAsync(() -> session.initialize(permit), executor);
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
          executor.submit(
              () ->
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
        .thenAnswer(
            invocation -> {
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
    try {
      assertThatThrownBy(
              () ->
                  Context.current()
                      .withDeadlineAfter(2, TimeUnit.SECONDS, scheduler)
                      .call(() -> owner.readExact(request, reservation)))
          .isInstanceOf(IllegalStateException.class);
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
      assertThat(dsl.configuration().connectionProvider()).isSameAs(provider);
    } finally {
      release.countDown();
      scheduler.shutdownNow();
    }
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
        .thenAnswer(
            invocation -> {
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
    try {
      assertThatThrownBy(
              () ->
                  CanonicalGameplayOriginalNewCapacityExecutionBudget.run(
                      CLOCK, CLOCK.millis() + 2000, budget -> execute(budget.sql(dsl))))
          .isInstanceOf(IllegalStateException.class);
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
    } finally {
      finishExecution.countDown();
      finishCancellation.countDown();
    }
  }

  @Test
  void uncertainProviderReleaseRetainsOnePermitAndExposesItThroughMicrometer() {
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
      try {
        assertThatThrownBy(
                () ->
                    CanonicalGameplayOriginalNewCapacityExecutionBudget.runBackground(
                        CLOCK,
                        Long.MAX_VALUE,
                        budget -> {
                          captured.set(budget);
                          var scoped =
                              budget.sql(source(provider)).configuration().connectionProvider();
                          scoped.acquire();
                          scoped.acquire();
                          return true;
                        }))
            .isInstanceOf(IllegalStateException.class);
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
    CompletableFuture<Void> cancelled = new CompletableFuture<>();
    cancelled.cancel(false);
    try {
      for (var close :
          List.of(
              CompletableFuture.<Void>failedFuture(new IllegalStateException("close failed")),
              cancelled)) {
        assertThatThrownBy(
                () ->
                    CanonicalGameplayOriginalNewCapacityExecutionBudget.runBackground(
                        CLOCK,
                        Long.MAX_VALUE,
                        budget -> {
                          budget.awaitAsyncClose(close);
                          return true;
                        }))
            .isInstanceOf(IllegalStateException.class);
      }
      assertThat(operations.availablePermits()).isEqualTo(priorOperations - 2);
      assertThat(background.availablePermits()).isEqualTo(priorBackground - 2);
      assertThat(
              registry
                  .get("original_new_capacity_redis_close_failure_retained_operations")
                  .gauge()
                  .value())
          .isEqualTo(priorRetained + 2);
    } finally {
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
    try {
      assertThatThrownBy(
              () ->
                  CanonicalGameplayOriginalNewCapacityExecutionBudget.run(
                      CLOCK,
                      CLOCK.millis() + 200,
                      budget -> {
                        execute(budget.sql(dsl));
                        awaitUninterruptibly(finish);
                        return true;
                      },
                      cancellation ->
                          Thread.ofVirtual()
                              .name("test-original-new-capacity-cancel")
                              .unstarted(
                                  () -> {
                                    try {
                                      cancellation.run();
                                    } finally {
                                      cancellationWorkerFinished.countDown();
                                    }
                                  })))
          .isInstanceOf(IllegalStateException.class);
      assertThat(returned.await(1, TimeUnit.SECONDS)).isTrue();
    } finally {
      finish.countDown();
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
        .thenAnswer(
            invocation -> {
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
                    assertThatThrownBy(
                            () ->
                                CanonicalGameplayOriginalNewCapacityExecutionBudget.run(
                                    CLOCK,
                                    CLOCK.millis() + 300,
                                    budget -> execute(budget.sql(source))))
                        .isInstanceOf(IllegalStateException.class),
                executor));
      assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
      CompletableFuture.allOf(callers.toArray(CompletableFuture[]::new)).get(2, TimeUnit.SECONDS);
      assertThatThrownBy(
              () ->
                  CanonicalGameplayOriginalNewCapacityExecutionBudget.run(
                      CLOCK, CLOCK.millis() + 1000, budget -> execute(budget.sql(source))))
          .isInstanceOf(IllegalStateException.class);
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
        .thenAnswer(
            invocation -> {
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
                    assertThatThrownBy(
                            () ->
                                CanonicalGameplayOriginalNewCapacityExecutionBudget.runBackground(
                                    CLOCK,
                                    CLOCK.millis() + 1000,
                                    budget -> execute(budget.sql(source))))
                        .isInstanceOf(IllegalStateException.class),
                executor));
      assertThat(backgroundEntered.await(2, TimeUnit.SECONDS)).isTrue();
      var refused = new AtomicBoolean();
      assertThatThrownBy(
              () ->
                  CanonicalGameplayOriginalNewCapacityExecutionBudget.runBackground(
                      CLOCK,
                      Long.MAX_VALUE,
                      budget -> {
                        refused.set(true);
                        return true;
                      }))
          .isInstanceOf(IllegalStateException.class);
      assertThat(refused).isFalse();
      boolean admitted =
          CanonicalGameplayOriginalNewCapacityExecutionBudget.run(
              CLOCK, Long.MAX_VALUE, budget -> Boolean.TRUE);
      assertThat(admitted).isTrue();
      for (int index = backgroundMaximum; index < maximum; index++)
        callers.add(
            CompletableFuture.runAsync(
                () ->
                    assertThatThrownBy(
                            () ->
                                CanonicalGameplayOriginalNewCapacityExecutionBudget.run(
                                    CLOCK,
                                    CLOCK.millis() + 1000,
                                    budget -> execute(budget.sql(source))))
                        .isInstanceOf(IllegalStateException.class),
                executor));
      assertThat(allEntered.await(2, TimeUnit.SECONDS)).isTrue();
      CompletableFuture.allOf(callers.toArray(CompletableFuture[]::new)).get(2, TimeUnit.SECONDS);
      // Callers timed out, but blocked acquisition still owns all four permits.
      assertThatThrownBy(
              () ->
                  CanonicalGameplayOriginalNewCapacityExecutionBudget.run(
                      CLOCK, Long.MAX_VALUE, budget -> execute(budget.sql(source))))
          .isInstanceOf(IllegalStateException.class);
      verify(provider, org.mockito.Mockito.times(maximum)).acquire();
      verifyNoInteractions(connection);
    } finally {
      release.countDown();
      executor.shutdownNow();
      verify(provider, timeout(2000).times(maximum)).release(connection);
    }
  }

  @Test
  void explicitDeadlineIsOneBudgetAcrossQueriesWithoutGrpcContext() throws Exception {
    ConnectionProvider provider = mock();
    PreparedStatement statement = mock();
    Connection connection = preparedConnection(statement);
    when(provider.acquire()).thenReturn(connection);
    AtomicInteger executions = new AtomicInteger();
    CountDownLatch second = new CountDownLatch(1), release = new CountDownLatch(1);
    when(statement.execute())
        .thenAnswer(
            invocation -> {
              if (executions.incrementAndGet() == 1)
                assertThat(new CountDownLatch(1).await(80, TimeUnit.MILLISECONDS)).isFalse();
              else {
                second.countDown();
                awaitUninterruptibly(release);
              }
              return true;
            });
    DSLContext dsl = source(provider);
    long started = System.nanoTime();
    try {
      assertThatThrownBy(
              () ->
                  CanonicalGameplayOriginalNewCapacityExecutionBudget.run(
                      CLOCK,
                      CLOCK.millis() + 250,
                      budget -> {
                        var sql = budget.sql(dsl);
                        execute(sql);
                        return execute(sql);
                      }))
          .isInstanceOf(IllegalStateException.class);
      assertThat(second.await(1, TimeUnit.SECONDS)).isTrue();
      assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(1500);
      verify(statement, timeout(1000)).cancel();
    } finally {
      release.countDown();
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
    assertThatThrownBy(
            () ->
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
        .thenAnswer(
            invocation -> {
              entered.countDown();
              awaitUninterruptibly(finish);
              return true;
            });
    DSLContext dsl = source(provider);
    try {
      assertThatThrownBy(
              () ->
                  CanonicalGameplayOriginalNewCapacityExecutionBudget.run(
                      CLOCK,
                      CLOCK.millis() + 200,
                      budget -> {
                        captured.set(budget);
                        return execute(budget.sql(dsl));
                      },
                      ignored -> {
                        throw new java.util.concurrent.RejectedExecutionException(
                            "Controlled startup refusal");
                      }))
          .isInstanceOf(IllegalStateException.class);
      assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
      verify(statement, never()).cancel();
      verify(provider, never()).release(connection);
      assertThat(ReflectionTestUtils.getField(captured.get(), "cancellationDone")).isEqualTo(true);
      finish.countDown();
      verify(provider, timeout(2000)).release(connection);
      assertNoOwnedConnections(captured.get());
      for (int index = 0;
          index < 2 * CanonicalGameplayOriginalNewCapacityExecutionBudget.MAX_IN_FLIGHT_OPERATIONS;
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

  @Test
  void exceptionalCallbackDrainsAnAcquiredConnectionBeforeReleasingItsResourcePermit() {
    ConnectionProvider provider = mock();
    Connection connection = mock();
    when(provider.acquire()).thenReturn(connection);
    AtomicReference<CanonicalGameplayOriginalNewCapacityExecutionBudget> captured =
        new AtomicReference<>();
    assertThatThrownBy(
            () ->
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
        .thenAnswer(
            ignored -> {
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
              .call(
                  () ->
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
    return DSL.using(new DefaultConfiguration().set(SQLDialect.POSTGRES).set(provider));
  }

  private static boolean execute(DSLContext sql) {
    return sql.connectionResult(
        connection -> {
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
}
