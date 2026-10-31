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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
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
  private final DirectTextWorldSelectionService selection =
      new DirectTextWorldSelectionService(
          GameplayWorldCatalog.forWorldViews(List.of()), routes, CLOCK);
  private final StatefulRedisConnection<byte[], byte[]> connection = mockConnection();
  private final RedisCommands<byte[], byte[]> commands = mockCommands();
  private final AtomicReference<byte[]> stored = new AtomicReference<>();
  private final AtomicReference<String> outcome = new AtomicReference<>("MENU_WRITTEN");
  private DirectTextWorldMenuRedisStore store;

  @BeforeEach
  void prepare() throws Exception {
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
  void retainsGenuineMenuAndRestoresOnlyToItsExactObservation() {
    var menu = selection.browse().orElseThrow();
    assertThat(store.replace(transport, menu)).isTrue();
    var restored = store.find(transport).orElseThrow();
    assertThat(restored.expiresAt()).isEqualTo(menu.expiresAt());
    assertThat(selection.encodeSnapshot(restored))
        .hasValueSatisfying(
            bytes -> assertThat(bytes).isEqualTo(selection.encodeSnapshot(menu).orElseThrow()));
    assertThat(new String(stored.get(), StandardCharsets.UTF_8))
        .contains(transport.proxyConnectionId().toString(), "\"transportSessionId\":\"101\"")
        .doesNotContain("accountId", "connectScope", "credential", "joinIntent");
    clearInvocations(commands, routes);
    var wrong = new ObservedDirectTextTransport("102", transport.proxyConnectionId());
    assertThat(store.find(wrong)).isEmpty();
    assertThat(store.replace(wrong, menu)).isFalse();
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
    assertThatThrownBy(() -> store.replace(signed, menu))
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
    assertThat(store.replace(transport, selection.browse().orElseThrow())).isTrue();
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
    assertThat(store.replace(transport, genuine)).isFalse();
    verify(commands, never()).scriptLoad(any(byte[].class));
    assertThat(stored.get()).isEqualTo(changed);
  }

  @Test
  void missingAndInvalidInnerStateAreNotReconstructedAndSourceFailureEscapes() {
    assertThat(store.find(transport)).isEmpty();
    verifyNoInteractions(routes);
    assertThat(store.replace(transport, selection.browse().orElseThrow())).isTrue();
    var unavailable = new ReadUnavailableException("controlled unavailable source");
    when(routes.readVisiblePublicProductionTargets()).thenThrow(unavailable);
    assertThatThrownBy(() -> store.find(transport)).isSameAs(unavailable);
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
    assertThat(store.replace(transport, foreign)).isFalse();
    assertThat(store.replace(transport, null)).isFalse();
    verifyNoInteractions(commands, routes);
  }

  @ParameterizedTest
  @ValueSource(strings = {"PREIMAGE_MISMATCH", "EXPIRED_MENU", "INVALID_ARGS", "UNKNOWN"})
  void refusalsAndUnknownOutcomesNeverReportSuccessfulWrite(String result) {
    outcome.set(result);
    var menu = selection.browse().orElseThrow();
    if ("PREIMAGE_MISMATCH".equals(result) || "EXPIRED_MENU".equals(result)) {
      assertThat(store.replace(transport, menu)).isFalse();
    } else {
      assertThatThrownBy(() -> store.replace(transport, menu))
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
    assertThatThrownBy(() -> store.replace(transport, menu)).isSameAs(failure);
    assertThatThrownBy(() -> store.clear(transport)).isSameAs(failure);
    verify(commands, never()).scriptLoad(any(byte[].class));
  }

  @Test
  void principalReconnectAndLoadedHashMismatchDenyBeforeEval() {
    var menu = selection.browse().orElseThrow();
    when(commands.aclWhoami()).thenReturn("default");
    assertThatThrownBy(() -> store.replace(transport, menu))
        .isInstanceOf(IllegalStateException.class);
    when(commands.aclWhoami()).thenReturn("gamesession_coord_app");
    when(connection.getOptions()).thenReturn(ClientOptions.builder().autoReconnect(true).build());
    assertThatThrownBy(() -> store.find(transport)).isInstanceOf(IllegalStateException.class);
    when(connection.getOptions()).thenReturn(ClientOptions.builder().autoReconnect(false).build());
    when(commands.scriptLoad(any(byte[].class))).thenReturn("wrong-loaded-hash");
    assertThatThrownBy(() -> store.replace(transport, menu))
        .isInstanceOf(IllegalStateException.class);
    verify(commands, never())
        .evalsha(
            anyString(), eq(ScriptOutputType.STATUS), any(byte[][].class), any(byte[][].class));
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
