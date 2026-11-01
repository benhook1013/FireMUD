package unit.net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.RedisConnectionException;
import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor;
import net.firedevops.firemud.gamesession.command.text.DirectTextWorldMenuRedisScriptContribution;
import net.firedevops.firemud.gamesession.command.text.DirectTextWorldMenuRedisStore;
import net.firedevops.firemud.gamesession.command.text.DirectTextWorldSelectionService;
import net.firedevops.firemud.gamesession.command.text.GameplayWorldCatalog;
import net.firedevops.firemud.gamesession.command.text.ObservedDirectTextTransport;
import net.firedevops.firemud.gamesession.service.CanonicalPlayerRouteReadService;
import net.firedevops.firemud.gamesession.service.CanonicalPlayerRouteReadService.ReadUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Controlled public source/physical-connection doubles; no genuine ingress is established. */
class DirectTextWorldMenuRedisStoreTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-01T12:00:00Z"), ZoneOffset.UTC);
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final ObservedDirectTextTransport transport =
      new ObservedDirectTextTransport(
          "101", UUID.fromString("aaaaaaaa-1111-4111-8111-111111111111"));
  private final CanonicalPlayerRouteReadService routes =
      mock(CanonicalPlayerRouteReadService.class);
  private final AtomicInteger catalogReads = new AtomicInteger();
  private final DirectTextWorldSelectionService selection =
      new DirectTextWorldSelectionService(
          GameplayWorldCatalog.forWorldSupplier(
              () -> {
                catalogReads.incrementAndGet();
                return List.of();
              }),
          routes,
          CLOCK);
  private final StatefulRedisConnection<byte[], byte[]> connection = mockConnection();
  private final RedisCommands<byte[], byte[]> commands = mockCommands();
  private final AtomicReference<byte[]> stored = new AtomicReference<>();
  private final AtomicReference<String> outcome = new AtomicReference<>("MENU_WRITTEN");
  private DirectTextWorldMenuRedisStore store;
  private byte[] source;
  private String sha1;

  @BeforeEach
  void prepare() throws Exception {
    try (var input =
        DirectTextWorldMenuRedisStore.class
            .getClassLoader()
            .getResourceAsStream(DirectTextWorldMenuRedisScriptContribution.RESOURCE_PATH)) {
      source = java.util.Objects.requireNonNull(input).readAllBytes();
    }
    sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(source));
    when(routes.readVisiblePublicProductionTargets()).thenReturn(List.of());
    when(connection.isOpen()).thenReturn(true);
    when(connection.getOptions()).thenReturn(ClientOptions.builder().autoReconnect(false).build());
    when(connection.sync()).thenReturn(commands);
    when(commands.aclWhoami()).thenReturn("gamesession_coord_app");
    when(commands.getrange(any(byte[].class), eq(0L), eq(2097152L)))
        .thenAnswer(invocation -> stored.get() == null ? new byte[0] : stored.get());
    when(commands.exists(any(byte[].class)))
        .thenAnswer(invocation -> stored.get() == null ? 0L : 1L);
    when(commands.scriptLoad(any(byte[].class)))
        .thenAnswer(
            invocation ->
                HexFormat.of()
                    .formatHex(
                        MessageDigest.getInstance("SHA-1")
                            .digest(invocation.getArgument(0, byte[].class))));
    when(commands.evalsha(
            anyString(),
            eq(ScriptOutputType.STATUS),
            any(byte[][].class),
            any(byte[].class),
            any(byte[].class),
            any(byte[].class),
            any(byte[].class),
            any(byte[].class)))
        .thenAnswer(
            invocation -> {
              assertThat(
                      new String(
                          invocation.getArgument(7, byte[].class), StandardCharsets.US_ASCII))
                  .isEqualTo(Long.toString(CLOCK.millis()));
              boolean clear =
                  "CLEAR"
                      .equals(
                          new String(
                              invocation.getArgument(3, byte[].class), StandardCharsets.US_ASCII));
              assertThat(
                      new String(
                          invocation.getArgument(6, byte[].class), StandardCharsets.US_ASCII))
                  .isEqualTo(clear ? "0" : Long.toString(CLOCK.millis() + 300_000L));
              byte[] expected = invocation.getArgument(4, byte[].class);
              if (!Arrays.equals(expected, stored.get() == null ? new byte[0] : stored.get())) {
                return "PREIMAGE_MISMATCH";
              }
              if ("MENU_WRITTEN".equals(outcome.get())) {
                stored.set(invocation.getArgument(5, byte[].class));
              }
              return outcome.get();
            });
    store = new DirectTextWorldMenuRedisStore(selection, CLOCK, () -> connection, installed());
  }

  @Test
  void clearUsesZeroExpiryAndTheSameBoundedClockArgument() {
    outcome.set("MENU_UNCHANGED");
    assertThat(store.clear(transport)).isTrue();
  }

  @Test
  void freshDiscoveryComposesOnceButFindAndRetainedReassertEachRestoreFreshSources() {
    var menu = store.discoverAndReplace(transport).orElseThrow();
    assertThat(catalogReads.get()).isEqualTo(2);
    verify(routes).readVisiblePublicProductionTargets();
    byte[] original = stored.get();

    clearInvocations(routes);
    catalogReads.set(0);
    var restored = store.find(transport).orElseThrow();
    assertThat(catalogReads.get()).isEqualTo(2);
    verify(routes).readVisiblePublicProductionTargets();
    assertThat(restored.expiresAt()).isEqualTo(menu.expiresAt());

    for (var retained : List.of(menu, restored)) {
      clearInvocations(routes);
      catalogReads.set(0);
      outcome.set("MENU_UNCHANGED");
      assertThat(store.reassert(transport, retained)).isTrue();
      assertThat(catalogReads.get()).isEqualTo(2);
      verify(routes).readVisiblePublicProductionTargets();
      assertThat(stored.get()).isEqualTo(original);
    }
  }

  @ParameterizedTest
  @ValueSource(longs = {-1L, 300L})
  void freshDiscoveryStillRejectsFutureOrExpiredLifetimeBeforeRedis(long clockOffsetSeconds) {
    var bounded =
        new DirectTextWorldMenuRedisStore(
            selection,
            Clock.offset(CLOCK, java.time.Duration.ofSeconds(clockOffsetSeconds)),
            () -> connection,
            installed());
    assertThat(bounded.discoverAndReplace(transport)).isEmpty();
    assertThat(catalogReads.get()).isEqualTo(2);
    verify(routes).readVisiblePublicProductionTargets();
    verifyNoInteractions(commands, connection);
  }

  @Test
  void expiredRetainedHandleCannotUseTheFreshDiscoveryPath() {
    var clock = mock(Clock.class);
    when(clock.instant()).thenReturn(CLOCK.instant());
    when(clock.millis()).thenReturn(CLOCK.millis());
    var localSelection =
        new DirectTextWorldSelectionService(
            GameplayWorldCatalog.forWorldViews(List.of()), routes, clock);
    var localStore =
        new DirectTextWorldMenuRedisStore(localSelection, clock, () -> connection, installed());
    var menu = localStore.discoverAndReplace(transport).orElseThrow();
    byte[] original = stored.get();
    when(clock.instant()).thenReturn(menu.expiresAt());
    when(clock.millis()).thenReturn(menu.expiresAt().toEpochMilli());
    clearInvocations(commands, connection, routes);

    assertThat(localStore.reassert(transport, menu)).isFalse();
    assertThat(localSelection.resolve(menu, "1")).isEmpty();
    assertThat(stored.get()).isEqualTo(original);
    verifyNoInteractions(commands, connection, routes);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void selectionOwnerClockMovementRejectsFreshHandleEvenWhenStoreClockRemainsLive(boolean expired) {
    var clock = mock(Clock.class);
    var createdAt = CLOCK.instant();
    // browse sees a live original for both of its own clock reads. The selection owner's next
    // read sees expiry or sub-millisecond rollback, while the independent store clock stays live.
    when(clock.instant())
        .thenReturn(
            createdAt, createdAt, expired ? createdAt.plusSeconds(300) : createdAt.minusNanos(1));
    var localSelection =
        new DirectTextWorldSelectionService(
            GameplayWorldCatalog.forWorldViews(List.of()), routes, clock);
    var localStore =
        new DirectTextWorldMenuRedisStore(localSelection, CLOCK, () -> connection, installed());

    assertThat(localStore.discoverAndReplace(transport)).isEmpty();
    verify(routes).readVisiblePublicProductionTargets();
    verifyNoInteractions(commands, connection);
  }

  @Test
  void verifiedSourceIsResolvedOnceAtConstructionAndWarmOperationsNeverLoad() {
    var catalog = mock(RedisScriptCatalog.class);
    String id = DirectTextWorldMenuRedisScriptContribution.SCRIPT_ID;
    when(catalog.require(id)).thenReturn(installed().require(id));
    var cached = new DirectTextWorldMenuRedisStore(selection, CLOCK, () -> connection, catalog);
    // Later catalog lookups would fail: all operations must use the already verified source.
    when(catalog.require(id)).thenThrow(new IllegalStateException("unexpected repeated lookup"));
    var menu = cached.discoverAndReplace(transport).orElseThrow();
    assertThat(cached.find(transport)).isPresent();
    outcome.set("MENU_UNCHANGED");
    assertThat(cached.reassert(transport, menu)).isTrue();
    outcome.set("MENU_CLEARED");
    assertThat(cached.clear(transport)).isTrue();
    verify(catalog).require(id);
    verify(commands, never()).scriptLoad(any(byte[].class));
    verifyEvaluations(3);
    verify(commands, times(4)).aclWhoami();
  }

  @ParameterizedTest
  @ValueSource(strings = {"scriptId", "owner", "principal", "resourcePath", "role", "sha256"})
  void mismatchedInstalledDescriptorIsDeniedAtConstructionWithoutRedis(String field) {
    var catalog = mock(RedisScriptCatalog.class);
    String id = DirectTextWorldMenuRedisScriptContribution.SCRIPT_ID;
    ObjectNode changed = (ObjectNode) JSON.valueToTree(installed().require(id));
    changed.put(
        field,
        switch (field) {
          case "scriptId" -> "game-session.wrong-menu.v1";
          case "owner" -> "wrong-service";
          case "principal" -> "wrong_principal";
          case "resourcePath" -> "redis/lua/wrong-menu.lua";
          case "role" -> "CACHE_RATE_LIMIT";
          case "sha256" -> "0".repeat(64);
          default -> throw new IllegalArgumentException(field);
        });
    when(catalog.require(id)).thenReturn(JSON.treeToValue(changed, RedisScriptDescriptor.class));
    clearInvocations(commands);
    assertThatThrownBy(
            () -> new DirectTextWorldMenuRedisStore(selection, CLOCK, () -> connection, catalog))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(commands, connection);
  }

  @Test
  void noScriptLoadsExactVerifiedBytesAndRetriesExactShaOnce() {
    var menu = store.discoverAndReplace(transport).orElseThrow();
    byte[] original = stored.get();
    when(evaluation())
        .thenThrow(new RedisNoScriptException("NOSCRIPT controlled loss"))
        .thenReturn("MENU_UNCHANGED");
    clearInvocations(commands, connection);
    assertThat(store.reassert(transport, menu)).isTrue();
    verify(commands).scriptLoad(eq(source));
    verifyEvaluations(2);
    verify(connection).sync();
    verify(connection).close();
    assertThat(stored.get()).isEqualTo(original);
  }

  @Test
  void loadedDigestMismatchRefusesBeforeSecondEvaluation() {
    when(evaluation()).thenThrow(new RedisNoScriptException("NOSCRIPT controlled loss"));
    when(commands.scriptLoad(any(byte[].class))).thenReturn("wrong-loaded-hash");
    clearInvocations(commands);
    assertThatThrownBy(() -> store.discoverAndReplace(transport))
        .isInstanceOf(IllegalStateException.class);
    verify(commands).scriptLoad(eq(source));
    verifyEvaluations(1);
    assertThat(stored.get()).isNull();
  }

  @Test
  void nullReturnedStatusIsUnavailableWithoutLoadOrRetry() {
    when(evaluation()).thenReturn(null);
    clearInvocations(commands);
    assertThatThrownBy(() -> store.discoverAndReplace(transport))
        .isInstanceOf(IllegalStateException.class);
    verify(commands, never()).scriptLoad(any(byte[].class));
    verifyEvaluations(1);
    assertThat(stored.get()).isNull();
  }

  @Test
  void secondNoScriptEscapesWithoutAnotherLoadOrRetry() {
    var second = new RedisNoScriptException("NOSCRIPT second loss");
    when(evaluation())
        .thenThrow(new RedisNoScriptException("NOSCRIPT first loss"))
        .thenThrow(second);
    clearInvocations(commands);
    assertThatThrownBy(() -> store.discoverAndReplace(transport)).isSameAs(second);
    verify(commands).scriptLoad(eq(source));
    verifyEvaluations(2);
    assertThat(stored.get()).isNull();
  }

  @Test
  void arbitraryErrorsAndUnknownWriteFailuresAreNeverLoadedOrRetried() {
    for (RuntimeException failure :
        List.of(
            new RedisCommandExecutionException("NOSCRIPT text is not the typed exception"),
            new RedisCommandExecutionException("ERR controlled failure"),
            new RedisCommandTimeoutException("controlled timeout"),
            new RedisConnectionException("controlled transport loss"))) {
      // doAnswer also models a write that completed before its reply became unavailable.
      org.mockito.Mockito.doAnswer(
              invocation -> {
                stored.set(invocation.getArgument(5, byte[].class));
                throw failure;
              })
          .when(commands)
          .evalsha(
              anyString(),
              eq(ScriptOutputType.STATUS),
              any(byte[][].class),
              any(byte[].class),
              any(byte[].class),
              any(byte[].class),
              any(byte[].class),
              any(byte[].class));
      stored.set(null);
      clearInvocations(commands);
      assertThatThrownBy(() -> store.discoverAndReplace(transport)).isSameAs(failure);
      assertThat(stored.get()).isNotEmpty();
      verify(commands, never()).scriptLoad(any(byte[].class));
      verifyEvaluations(1);
    }
  }

  @Test
  void retainsGenuineMenuAndRestoresOnlyToItsExactObservation() {
    var menu = store.discoverAndReplace(transport).orElseThrow();
    var restored = store.find(transport).orElseThrow();
    assertThat(restored.expiresAt()).isEqualTo(menu.expiresAt());
    assertThat(selection.encodeSnapshot(restored))
        .hasValueSatisfying(
            bytes -> assertThat(bytes).isEqualTo(selection.encodeSnapshot(menu).orElseThrow()));
    byte[] original = stored.get();
    outcome.set("MENU_UNCHANGED");
    assertThat(store.reassert(transport, menu)).isTrue();
    assertThat(store.reassert(transport, restored)).isTrue();
    assertThat(stored.get()).isEqualTo(original);
    assertThat(new String(stored.get(), StandardCharsets.UTF_8))
        .contains(transport.proxyConnectionId().toString(), "\"transportSessionId\":\"101\"")
        .doesNotContain("accountId", "connectScope", "credential", "joinIntent");
    clearInvocations(commands, routes);
    var wrong = new ObservedDirectTextTransport("102", transport.proxyConnectionId());
    assertThat(store.find(wrong)).isEmpty();
    assertThat(store.reassert(wrong, menu)).isFalse();
    assertThat(store.discoverAndReplace(wrong)).isEmpty();
    assertThat(store.clear(wrong)).isFalse();
    assertThat(store.find(new ObservedDirectTextTransport("0101", transport.proxyConnectionId())))
        .isEmpty();
    verify(commands, never()).scriptLoad(any(byte[].class));
  }

  @Test
  void malformedOrUnboundedNumericCorrelationCannotReachRedis() {
    var menu = selection.browse().orElseThrow();
    clearInvocations(commands, routes);
    var signed = new ObservedDirectTextTransport("+101", transport.proxyConnectionId());
    var oversized =
        new ObservedDirectTextTransport("0".repeat(100) + "101", transport.proxyConnectionId());
    assertThatThrownBy(() -> store.reassert(signed, menu))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> store.discoverAndReplace(signed))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> store.find(oversized)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> store.clear(signed)).isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(commands, routes);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "schema",
        "unknown",
        "duplicate",
        "trailing",
        "coerced",
        "missing",
        "uuid",
        "base64",
        "oversize"
      })
  void rejectsMalformedOuterStateWithoutSourceReadsOrMutation(String change) {
    assertThat(store.discoverAndReplace(transport)).isPresent();
    byte[] original = stored.get();
    ObjectNode tree = (ObjectNode) JSON.readTree(original);
    switch (change) {
      case "schema" -> tree.put("schemaVersion", "direct-text-world-menu/v2");
      case "unknown" -> tree.put("authority", true);
      case "coerced" -> tree.put("transportSessionId", 101);
      case "missing" -> tree.remove("transportSessionId");
      case "uuid" ->
          tree.put(
              "originalProxyConnectionUuid",
              transport.proxyConnectionId().toString().toUpperCase(java.util.Locale.ROOT));
      case "base64" -> tree.put("encodedOriginalMenu", "!");
      default -> {}
    }
    byte[] changed = JSON.writeValueAsBytes(tree);
    if ("duplicate".equals(change)) {
      changed =
          (new String(original, StandardCharsets.UTF_8)
                  .replaceFirst("\\{", "{\"transportSessionId\":\"101\","))
              .getBytes(StandardCharsets.UTF_8);
    } else if ("trailing".equals(change)) {
      changed =
          (new String(original, StandardCharsets.UTF_8) + "{}").getBytes(StandardCharsets.UTF_8);
    } else if ("oversize".equals(change)) {
      changed = new byte[2097153];
    }
    stored.set(changed);
    var genuine = selection.browse().orElseThrow();
    clearInvocations(commands, routes);
    assertThat(store.find(transport)).isEmpty();
    verifyNoInteractions(routes);
    assertThat(store.clear(transport)).isFalse();
    assertThat(store.reassert(transport, genuine)).isFalse();
    assertThat(store.discoverAndReplace(transport)).isEmpty();
    verify(commands, never()).scriptLoad(any(byte[].class));
    assertThat(stored.get()).isEqualTo(changed);
  }

  @Test
  void missingAndInvalidInnerStateAreNotReconstructedAndSourceFailureEscapes() {
    assertThat(store.find(transport)).isEmpty();
    verifyNoInteractions(routes);
    var menu = store.discoverAndReplace(transport).orElseThrow();
    var unavailable = new ReadUnavailableException("controlled unavailable source");
    when(routes.readVisiblePublicProductionTargets()).thenThrow(unavailable);
    assertThatThrownBy(() -> store.find(transport)).isSameAs(unavailable);
    assertThatThrownBy(() -> store.reassert(transport, menu)).isSameAs(unavailable);
    assertThatThrownBy(() -> store.discoverAndReplace(transport)).isSameAs(unavailable);
    ObjectNode tree = (ObjectNode) JSON.readTree(stored.get());
    tree.put("encodedOriginalMenu", "e30=");
    stored.set(JSON.writeValueAsBytes(tree));
    clearInvocations(routes);
    assertThat(store.find(transport)).isEmpty();
    verifyNoInteractions(routes);
  }

  @Test
  void foreignHandlesCannotBeWrittenAndNoRawPayloadApiExists() {
    var other =
        new DirectTextWorldSelectionService(
            GameplayWorldCatalog.forWorldViews(List.of()), routes, CLOCK);
    var foreign = other.browse().orElseThrow();
    clearInvocations(commands, routes);
    assertThat(store.reassert(transport, foreign)).isFalse();
    assertThat(store.reassert(transport, null)).isFalse();
    verifyNoInteractions(commands, routes);
  }

  @ParameterizedTest
  @ValueSource(strings = {"PREIMAGE_MISMATCH", "EXPIRED_MENU", "INVALID_ARGS", "UNKNOWN"})
  void refusalsAndUnknownOutcomesNeverReportSuccessfulWrite(String result) {
    var menu = store.discoverAndReplace(transport).orElseThrow();
    byte[] original = stored.get();
    outcome.set(result);
    if ("PREIMAGE_MISMATCH".equals(result) || "EXPIRED_MENU".equals(result)) {
      assertThat(store.reassert(transport, menu)).isFalse();
    } else {
      assertThatThrownBy(() -> store.reassert(transport, menu))
          .isInstanceOf(IllegalStateException.class);
    }
    assertThat(stored.get()).isEqualTo(original);
    stored.set(null);
    if ("PREIMAGE_MISMATCH".equals(result) || "EXPIRED_MENU".equals(result)) {
      assertThat(store.discoverAndReplace(transport)).isEmpty();
    } else {
      assertThatThrownBy(() -> store.discoverAndReplace(transport))
          .isInstanceOf(IllegalStateException.class);
    }
    assertThat(stored.get()).isNull();
  }

  @Test
  void redisFailuresEscapeWithoutFallbackOrFakeSuccess() {
    var menu = selection.browse().orElseThrow();
    var failure = new IllegalStateException("controlled transport failure");
    when(commands.getrange(any(byte[].class), anyLong(), anyLong())).thenThrow(failure);
    assertThatThrownBy(() -> store.find(transport)).isSameAs(failure);
    assertThatThrownBy(() -> store.reassert(transport, menu)).isSameAs(failure);
    assertThatThrownBy(() -> store.discoverAndReplace(transport)).isSameAs(failure);
    assertThatThrownBy(() -> store.clear(transport)).isSameAs(failure);
    verify(commands, never()).scriptLoad(any(byte[].class));
  }

  @Test
  void principalAndReconnectMismatchDenyBeforeEval() {
    when(commands.aclWhoami()).thenReturn("default");
    assertThatThrownBy(() -> store.discoverAndReplace(transport))
        .isInstanceOf(IllegalStateException.class);
    when(commands.aclWhoami()).thenReturn("gamesession_coord_app");
    when(connection.getOptions()).thenReturn(ClientOptions.builder().autoReconnect(true).build());
    assertThatThrownBy(() -> store.find(transport)).isInstanceOf(IllegalStateException.class);
    verify(commands, never())
        .evalsha(
            anyString(), eq(ScriptOutputType.STATUS), any(byte[][].class), any(byte[][].class));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void lostOriginalOrFindRestoredMenuCannotBeReassertedButExplicitDiscoveryRerunsSources(
      boolean restoredHandle) {
    var original = store.discoverAndReplace(transport).orElseThrow();
    var retained = restoredHandle ? store.find(transport).orElseThrow() : original;
    stored.set(null); // Controlled DEL/reset of an unexpired original menu.
    clearInvocations(commands, routes);

    assertThat(store.reassert(transport, retained)).isFalse();
    assertThat(stored.get()).isNull();
    verify(commands, never()).scriptLoad(any(byte[].class));

    clearInvocations(routes);
    var fresh = store.discoverAndReplace(transport).orElseThrow();
    assertThat(fresh).isNotSameAs(retained);
    // One new guarded browse, not local reconstruction or a redundant second composition.
    verify(routes).readVisiblePublicProductionTargets();
    assertThat(stored.get()).isNotEmpty();
    assertThat(store.find(transport)).isPresent();
  }

  @Test
  void deletionAfterReassertPreReadIsRefusedWithoutRetryOrRecreation() {
    var menu = store.discoverAndReplace(transport).orElseThrow();
    when(commands.getrange(any(byte[].class), anyLong(), anyLong()))
        .thenAnswer(invocation -> stored.getAndSet(null));
    clearInvocations(commands);

    assertThat(store.reassert(transport, menu)).isFalse();
    assertThat(stored.get()).isNull();
    verify(commands).getrange(any(byte[].class), eq(0L), eq(2097152L));
    verify(commands, never()).scriptLoad(any(byte[].class));
    verifyEvaluations(1);
  }

  private String evaluation() {
    return commands.evalsha(
        anyString(),
        eq(ScriptOutputType.STATUS),
        any(byte[][].class),
        any(byte[].class),
        any(byte[].class),
        any(byte[].class),
        any(byte[].class),
        any(byte[].class));
  }

  private void verifyEvaluations(int count) {
    verify(commands, times(count))
        .evalsha(
            eq(sha1),
            eq(ScriptOutputType.STATUS),
            eq(
                new byte[][] {
                  (DirectTextWorldMenuRedisStore.OWNED_PREFIX + transport.proxyConnectionId())
                      .getBytes(StandardCharsets.US_ASCII)
                }),
            any(byte[].class),
            any(byte[].class),
            any(byte[].class),
            any(byte[].class),
            any(byte[].class));
  }

  private static RedisScriptCatalog installed() {
    return RedisScriptCatalog.loadInstalled(DirectTextWorldMenuRedisStore.class.getClassLoader());
  }

  @SuppressWarnings("unchecked")
  private static StatefulRedisConnection<byte[], byte[]> mockConnection() {
    return mock(StatefulRedisConnection.class);
  }

  @SuppressWarnings("unchecked")
  private static RedisCommands<byte[], byte[]> mockCommands() {
    return mock(RedisCommands.class);
  }
}
