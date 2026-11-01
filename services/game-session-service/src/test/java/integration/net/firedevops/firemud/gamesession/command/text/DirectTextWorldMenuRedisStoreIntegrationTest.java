package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.lettuce.core.AclSetuserArgs;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.protocol.CommandKeyword;
import io.lettuce.core.protocol.CommandType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.redis.contracts.RedisScriptContribution;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor;
import net.firedevops.firemud.gamesession.command.text.GameplayWorldCatalog.RealmView;
import net.firedevops.firemud.gamesession.command.text.GameplayWorldCatalog.WorldView;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionOwnerProof.Outcome;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest.OriginKind;
import net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget;
import net.firedevops.firemud.gamesession.service.CanonicalPlayerRouteReadService;
import net.firedevops.firemud.test.TestContainerImages;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Real disposable standalone Redis, registered script, scoped ACL and physical WHOAMI. Catalog and
 * canonical-route evidence are controlled fixtures; transport construction is NOT genuine ingress,
 * hidden socket rebind, deployment custody, authentication, admission or Account/JOIN proof.
 */
@SuppressWarnings("resource")
class DirectTextWorldMenuRedisStoreIntegrationTest {
  private static final String PRINCIPAL = "gamesession_coord_app";
  private static final String ADMIN = "world_menu_fixture_admin";
  private static final String PASSWORD = UUID.randomUUID().toString();
  private static final String ADMIN_PASSWORD = UUID.randomUUID().toString();
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(TestContainerImages.redis()).withExposedPorts(6379);
  private static RedisClient writer;
  private static RedisClient administrator;
  private final List<byte[]> ownedKeys = new ArrayList<>();
  private StatefulRedisConnection<byte[], byte[]> adminConnection;
  private RedisCommands<byte[], byte[]> admin;

  @BeforeAll
  static void start() {
    assumeTrue(
        DockerClientFactory.instance().isDockerAvailable(),
        "Docker unavailable; Redis menu proof skipped");
    REDIS.start();
    RedisClient bootstrap = client(null, null);
    try (var connection = bootstrap.connect(ByteArrayCodec.INSTANCE)) {
      var commands = connection.sync();
      commands.aclSetuser(
          PRINCIPAL,
          baseAcl(PASSWORD)
              .addCommand(CommandType.SCRIPT, CommandKeyword.LOAD)
              .addCommand(CommandType.EVALSHA)
              .addCommand(CommandType.GETRANGE)
              .addCommand(CommandType.EXISTS)
              .addCommand(CommandType.GET)
              .addCommand(CommandType.TYPE)
              .addCommand(CommandType.STRLEN)
              .addCommand(CommandType.SET)
              .addCommand(CommandType.DEL));
      commands.aclSetuser(
          ADMIN,
          baseAcl(ADMIN_PASSWORD)
              .addCommand(CommandType.GET)
              .addCommand(CommandType.SET)
              .addCommand(CommandType.DEL)
              .addCommand(CommandType.PEXPIRETIME));
    } finally {
      bootstrap.shutdown();
    }
    writer = client(PRINCIPAL, PASSWORD);
    administrator = client(ADMIN, ADMIN_PASSWORD);
  }

  private static AclSetuserArgs baseAcl(String password) {
    return new AclSetuserArgs()
        .reset()
        .on()
        .addPassword(password)
        .keyPattern(DirectTextWorldMenuRedisStore.OWNED_PREFIX + "*")
        .addCommand(CommandType.HELLO)
        .addCommand(CommandType.PING)
        .addCommand(CommandType.CLIENT, CommandKeyword.SETINFO)
        .addCommand(CommandType.ACL, CommandKeyword.WHOAMI);
  }

  @BeforeEach
  void openAdmin() {
    adminConnection = administrator.connect(ByteArrayCodec.INSTANCE);
    admin = adminConnection.sync();
    assertThat(admin.aclWhoami()).isEqualTo(ADMIN);
  }

  @AfterEach
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "VA_PRIMITIVE_ARRAY_PASSED_TO_OBJECT_VARARG",
      justification =
          "RedisCommands<byte[], byte[]> takes binary keys; byte[][] supplies exactly one owned fixture key for native Redis deletion.")
  void cleanExactOwnedKeys() {
    if (adminConnection != null) {
      try {
        for (byte[] key : ownedKeys) admin.del(new byte[][] {key});
      } finally {
        adminConnection.close();
      }
    }
  }

  @AfterAll
  static void stop() {
    if (writer != null) writer.shutdown();
    if (administrator != null) administrator.shutdown();
    REDIS.stop();
  }

  @Test
  void sharedSameEdgeReplacementRestoresOriginalOrdinalsAndAbsoluteDeadline() {
    var fixture = fixture(Clock.systemUTC());
    var first = fixture.selection();
    var transport = observation();
    var menu = store(first, fixture.clock()).discoverAndReplace(transport).orElseThrow();
    byte[] original = admin.get(key(transport));
    long deadline = admin.pexpiretime(key(transport));
    assertThat(deadline).isEqualTo(menu.expiresAt().toEpochMilli());

    var replacement = fixture.selection();
    var replacementStore = store(replacement, fixture.clock());
    var restored = replacementStore.find(transport).orElseThrow();
    assertThat(restored.selectors()).containsExactly("other/second", "studio/forest");
    assertThat(restored.worlds()).isEqualTo(menu.worlds());
    assertThat(restored.expiresAt()).isEqualTo(menu.expiresAt());
    assertThat(replacement.resolve(restored, "1")).contains(fixture.targets().get(1));
    assertThat(replacement.resolve(restored, "2")).contains(fixture.targets().get(0));
    assertThat(replacementStore.reassert(transport, restored)).isTrue();
    assertThat(admin.get(key(transport))).isEqualTo(original);
    assertThat(admin.pexpiretime(key(transport))).isEqualTo(deadline);

    var newEdge = observation();
    assertThat(replacementStore.find(newEdge)).isEmpty();
    assertThat(replacementStore.clear(newEdge)).isTrue();
    assertThat(admin.get(key(transport))).isEqualTo(original);
    assertThat(admin.get(key(newEdge))).isNull();

    when(fixture.routes().readVisiblePublicProductionTargets()).thenReturn(List.of());
    assertThat(replacementStore.find(transport)).isEmpty();
    assertThat(replacementStore.reassert(transport, restored)).isFalse();
    assertThat(replacementStore.discoverAndReplace(transport)).isEmpty();
    assertThat(admin.get(key(transport))).isEqualTo(original);
  }

  @Test
  void physicalScriptCacheLossLoadsOnceWhileWarmAndReadOnlyUsesDoNotLoad() throws Exception {
    var fixture = fixture(Clock.systemUTC());
    var selection = fixture.selection();
    var transport = observation();
    byte[] source;
    try (var input =
        getClass()
            .getClassLoader()
            .getResourceAsStream(DirectTextWorldMenuRedisScriptContribution.RESOURCE_PATH)) {
      source = java.util.Objects.requireNonNull(input).readAllBytes();
    }
    String sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(source));
    List<RedisCommands<byte[], byte[]>> uses = new ArrayList<>();
    Supplier<StatefulRedisConnection<byte[], byte[]>> observedConnections =
        () -> {
          var physical = writer.connect(ByteArrayCodec.INSTANCE);
          var observed = delegatedCommands(physical.sync());
          uses.add(observed);
          return delegatedConnection(physical, observed);
        };
    var store =
        new DirectTextWorldMenuRedisStore(
            selection, fixture.clock(), observedConnections, installed());
    flushScripts();
    var menu = store.discoverAndReplace(transport).orElseThrow();
    verify(uses.get(0)).scriptLoad(eq(source));
    verifyEvaluations(uses.get(0), sha1, 2);
    byte[] original = admin.get(key(transport));
    long deadline = admin.pexpiretime(key(transport));

    assertThat(store.reassert(transport, menu)).isTrue();
    verify(uses.get(1), never()).scriptLoad(any(byte[].class));
    verifyEvaluations(uses.get(1), sha1, 1);

    var controller = client(null, null);
    try (var inspection = controller.connect(ByteArrayCodec.INSTANCE)) {
      long originalPttl = inspection.sync().pttl(key(transport));
      assertThat(originalPttl).isPositive();
      flushScripts();
      // SCRIPT FLUSH discards code only, not the original menu or its physical deadline/PTTL.
      assertThat(admin.get(key(transport))).isEqualTo(original);
      assertThat(admin.pexpiretime(key(transport))).isEqualTo(deadline);
      assertThat(store.find(transport)).isPresent();
      verify(uses.get(2), never()).scriptLoad(any(byte[].class));
      verifyEvaluations(uses.get(2), sha1, 0);
      assertThat(store.reassert(transport, menu)).isTrue();
      verify(uses.get(3)).scriptLoad(eq(source));
      verifyEvaluations(uses.get(3), sha1, 2);
      assertThat(admin.get(key(transport))).isEqualTo(original);
      assertThat(admin.pexpiretime(key(transport))).isEqualTo(deadline);
      assertThat(inspection.sync().pttl(key(transport))).isBetween(1L, originalPttl);
    } finally {
      controller.shutdown();
    }

    flushScripts();
    assertThat(store.clear(transport)).isTrue();
    verify(uses.get(4)).scriptLoad(eq(source));
    verifyEvaluations(uses.get(4), sha1, 2);
    assertThat(admin.get(key(transport))).isNull();
    assertThat(store.reassert(transport, menu)).isFalse();
    verify(uses.get(5), never()).scriptLoad(any(byte[].class));
    verifyEvaluations(uses.get(5), sha1, 0);
    for (var use : uses) verify(use).aclWhoami();
  }

  @Test
  void exactTransportBindingRefusesReadReassertDiscoveryAndDeleteWithoutMutating() {
    var fixture = fixture(Clock.systemUTC());
    var selection = fixture.selection();
    var store = store(selection, fixture.clock());
    var transport = observation();
    var menu = store.discoverAndReplace(transport).orElseThrow();
    byte[] original = admin.get(key(transport));
    long expiry = admin.pexpiretime(key(transport));
    var wrong = new ObservedDirectTextTransport("102", transport.proxyConnectionId());
    assertThat(store.find(wrong)).isEmpty();
    assertThat(store.reassert(wrong, menu)).isFalse();
    assertThat(store.discoverAndReplace(wrong)).isEmpty();
    assertThat(store.clear(wrong)).isFalse();
    assertThat(admin.get(key(transport))).isEqualTo(original);
    assertThat(admin.pexpiretime(key(transport))).isEqualTo(expiry);
    assertThat(store.clear(transport)).isTrue();
    assertThat(admin.get(key(transport))).isNull();
    assertThat(store.find(transport)).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"reassert", "delete-before-reassert", "discover", "clear"})
  void competingPreimageIsRefusedByRealLuaWithoutAutomaticRetry(String operation) {
    var fixture = fixture(Clock.systemUTC());
    var selection = fixture.selection();
    var transport = observation();
    var menu = store(selection, fixture.clock()).discoverAndReplace(transport).orElseThrow();
    byte[] competitor = ascii("administrator-seeded-malformed-competing-ui");
    Supplier<StatefulRedisConnection<byte[], byte[]>> racingConnection =
        () -> {
          var physical = writer.connect(ByteArrayCodec.INSTANCE);
          var intercepted = delegatedCommands(physical.sync());
          doAnswer(
                  invocation -> {
                    byte[] prior = physical.sync().getrange(invocation.getArgument(0), 0, 2097152);
                    // Controlled administrator seed AFTER the actual bounded read, before real CAS.
                    if ("delete-before-reassert".equals(operation)) {
                      admin.del(new byte[][] {key(transport)});
                    } else {
                      admin.set(key(transport), competitor);
                    }
                    // Cache loss must not bypass the original preimage on the load/retry path.
                    flushScripts();
                    return prior;
                  })
              .when(intercepted)
              .getrange(any(byte[].class), anyLong(), anyLong());
          return delegatedConnection(physical, intercepted);
        };
    var racing =
        new DirectTextWorldMenuRedisStore(
            selection, fixture.clock(), racingConnection, installed());
    if ("discover".equals(operation)) {
      assertThat(racing.discoverAndReplace(transport)).isEmpty();
    } else {
      assertThat(
              "clear".equals(operation)
                  ? racing.clear(transport)
                  : racing.reassert(transport, menu))
          .isFalse();
    }
    if ("delete-before-reassert".equals(operation)) {
      assertThat(admin.get(key(transport))).isNull();
    } else {
      assertThat(admin.get(key(transport))).isEqualTo(competitor);
      assertThat(admin.pexpiretime(key(transport))).isEqualTo(-1L);
    }
  }

  @Test
  void expiredAndDeletedUiRequiresFreshDiscoveryAndNeverRefreshesDeadline() throws Exception {
    // The controlled menu was created 298 seconds ago; Redis's own wall clock enforces PXAT.
    var clock = Clock.fixed(Instant.now().minusSeconds(298), ZoneOffset.UTC);
    var fixture = fixture(clock);
    var selection = fixture.selection();
    var transport = observation();
    var store = store(selection, Clock.systemUTC());
    var menu = store.discoverAndReplace(transport).orElseThrow();
    long expiry = admin.pexpiretime(key(transport));
    assertThat(expiry).isEqualTo(menu.expiresAt().toEpochMilli());
    assertThat(store.reassert(transport, menu)).isTrue();
    assertThat(admin.pexpiretime(key(transport))).isEqualTo(expiry);
    long wait = Math.max(0L, expiry - System.currentTimeMillis()) + 100L;
    Thread.sleep(wait);
    assertThat(admin.get(key(transport))).isNull();
    assertThat(store.find(transport)).isEmpty();
    assertThat(store.reassert(transport, menu)).isFalse();
    assertThat(admin.get(key(transport))).isNull();
    // Fresh WORLDS is the only replacement. Administrator deletion models disposable UI loss.
    var freshFixture = fixture(Clock.systemUTC());
    var freshSelection = freshFixture.selection();
    var freshStore = store(freshSelection, freshFixture.clock());
    var fresh = freshStore.discoverAndReplace(transport).orElseThrow();
    admin.del(new byte[][] {key(transport)});
    assertThat(freshStore.find(transport)).isEmpty();
    assertThat(freshStore.reassert(transport, fresh)).isFalse();
    assertThat(admin.get(key(transport))).isNull();
  }

  @Test
  void futureDeadlineAndMalformedBoundedStateCannotBeInstalledOrAdopted() {
    var fixture = fixture(Clock.offset(Clock.systemUTC(), Duration.ofSeconds(10)));
    var selection = fixture.selection();
    var transport = observation();
    // The owner rejects an original expiry >5 minutes after its single observed clock input.
    assertThat(store(selection, Clock.systemUTC()).discoverAndReplace(transport)).isEmpty();
    assertThat(admin.get(key(transport))).isNull();
    var expiredFixture = fixture(Clock.offset(Clock.systemUTC(), Duration.ofSeconds(-301)));
    var expiredSelection = expiredFixture.selection();
    // A menu expired relative to the owner's observation is refused without reconstruction.
    assertThat(store(expiredSelection, Clock.systemUTC()).discoverAndReplace(transport)).isEmpty();
    assertThat(admin.get(key(transport))).isNull();
    var current = fixture(Clock.systemUTC());
    var currentSelection = current.selection();
    var currentStore = store(currentSelection, current.clock());
    byte[] oversized = new byte[2097153];
    admin.set(key(transport), oversized);
    assertThat(currentStore.find(transport)).isEmpty();
    assertThat(currentStore.clear(transport)).isFalse();
    assertThat(currentStore.reassert(transport, currentSelection.browse().orElseThrow())).isFalse();
    assertThat(currentStore.discoverAndReplace(transport)).isEmpty();
    assertThat(admin.get(key(transport))).isEqualTo(oversized);
  }

  @Test
  void realPrincipalAndInstalledDigestMismatchAreDeniedBeforeMutation() {
    var fixture = fixture(Clock.systemUTC());
    var selection = fixture.selection();
    var transport = observation();
    var wrongPrincipal =
        new DirectTextWorldMenuRedisStore(
            selection,
            fixture.clock(),
            () -> administrator.connect(ByteArrayCodec.INSTANCE),
            installed());
    assertThatThrownBy(() -> wrongPrincipal.discoverAndReplace(transport))
        .isInstanceOf(IllegalStateException.class);
    var original = installed().require(DirectTextWorldMenuRedisScriptContribution.SCRIPT_ID);
    ObjectNode changed = (ObjectNode) JSON.valueToTree(original);
    changed.put("sha256", "0".repeat(64));
    var mismatched = JSON.treeToValue(changed, RedisScriptDescriptor.class);
    var badCatalog =
        RedisScriptCatalog.fromContributions(
            List.of(
                new RedisScriptContribution() {
                  @Override
                  public String ownerId() {
                    return "game-session-service";
                  }

                  @Override
                  public Collection<RedisScriptDescriptor> descriptors() {
                    return List.of(mismatched);
                  }
                }));
    assertThatThrownBy(
            () ->
                new DirectTextWorldMenuRedisStore(
                    selection,
                    fixture.clock(),
                    () -> writer.connect(ByteArrayCodec.INSTANCE),
                    badCatalog))
        .isInstanceOf(IllegalStateException.class);
    assertThat(admin.get(key(transport))).isNull();
  }

  @Test
  void lostSuccessfulWriteReplyEscapesAsUnknownRatherThanFakeSuccess() {
    var fixture = fixture(Clock.systemUTC());
    var selection = fixture.selection();
    var transport = observation();
    var failure = new IllegalStateException("controlled lost script reply");
    Supplier<StatefulRedisConnection<byte[], byte[]>> replyLoss =
        () -> {
          var physical = writer.connect(ByteArrayCodec.INSTANCE);
          var intercepted = delegatedCommands(physical.sync());
          doAnswer(
                  invocation -> {
                    String result =
                        physical
                            .sync()
                            .evalsha(
                                invocation.getArgument(0, String.class),
                                ScriptOutputType.STATUS,
                                invocation.getArgument(2, byte[][].class),
                                invocation.getArgument(3, byte[].class),
                                invocation.getArgument(4, byte[].class),
                                invocation.getArgument(5, byte[].class),
                                invocation.getArgument(6, byte[].class),
                                invocation.getArgument(7, byte[].class));
                    assertThat(result).isEqualTo("MENU_WRITTEN");
                    throw failure;
                  })
              .when(intercepted)
              .evalsha(
                  anyString(),
                  eq(ScriptOutputType.STATUS),
                  any(byte[][].class),
                  any(byte[].class),
                  any(byte[].class),
                  any(byte[].class),
                  any(byte[].class),
                  any(byte[].class));
          return delegatedConnection(physical, intercepted);
        };
    var losing =
        new DirectTextWorldMenuRedisStore(selection, fixture.clock(), replyLoss, installed());
    assertThatThrownBy(() -> losing.discoverAndReplace(transport)).isSameAs(failure);
    assertThat(admin.get(key(transport))).isNotEmpty();
    var replacement = fixture.selection();
    var menu = store(replacement, fixture.clock()).find(transport).orElseThrow();
    assertThat(admin.pexpiretime(key(transport))).isEqualTo(menu.expiresAt().toEpochMilli());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void unexpiredOriginalAndFindRestoredHandlesCannotRecreateDeletedOrResetMenu(
      boolean restoredHandle) {
    var clock = mock(Clock.class);
    var now = Instant.now();
    when(clock.instant()).thenReturn(now);
    when(clock.millis()).thenReturn(now.toEpochMilli());
    var fixture = fixture(clock);
    var selection = fixture.selection();
    var store = store(selection, clock);
    var transport = observation();
    var original = store.discoverAndReplace(transport).orElseThrow();
    var retained = restoredHandle ? store.find(transport).orElseThrow() : original;
    byte[] originalBytes = admin.get(key(transport));
    assertThat(admin.pexpiretime(key(transport))).isEqualTo(original.expiresAt().toEpochMilli());

    // Exact-key DEL models missing/reset UI state while both handles remain unexpired.
    admin.del(new byte[][] {key(transport)});
    assertThat(store.reassert(transport, retained)).isFalse();
    assertThat(store.find(transport)).isEmpty();
    assertThat(admin.get(key(transport))).isNull();

    when(clock.instant()).thenReturn(now.plusSeconds(1));
    when(clock.millis()).thenReturn(now.plusSeconds(1).toEpochMilli());
    clearInvocations(fixture.routes());
    var fresh = store.discoverAndReplace(transport).orElseThrow();
    verify(fixture.routes(), times(2)).readVisiblePublicProductionTargets();
    assertThat(fresh).isNotSameAs(retained);
    assertThat(fresh.expiresAt()).isEqualTo(original.expiresAt().plusSeconds(1));
    byte[] freshBytes = admin.get(key(transport));
    assertThat(freshBytes).isNotEqualTo(originalBytes);
    long freshDeadline = admin.pexpiretime(key(transport));
    assertThat(freshDeadline).isEqualTo(fresh.expiresAt().toEpochMilli());
    // Even a valid but different present preimage cannot be adopted by the old handle.
    assertThat(store.reassert(transport, retained)).isFalse();
    assertThat(admin.get(key(transport))).isEqualTo(freshBytes);
    assertThat(admin.pexpiretime(key(transport))).isEqualTo(freshDeadline);
    assertThat(store.reassert(transport, fresh)).isTrue();
    assertThat(admin.pexpiretime(key(transport))).isEqualTo(freshDeadline);
  }

  private DirectTextWorldMenuRedisStore store(
      DirectTextWorldSelectionService selection, Clock clock) {
    return new DirectTextWorldMenuRedisStore(
        selection, clock, () -> writer.connect(ByteArrayCodec.INSTANCE), installed());
  }

  private static void flushScripts() {
    // The default fixture controller exists only in this isolated disposable Redis. Application
    // and scoped administrator ACLs are unchanged; neither receives SCRIPT FLUSH permission.
    var controller = client(null, null);
    try (var connection = controller.connect(ByteArrayCodec.INSTANCE)) {
      assertThat(connection.sync().scriptFlush()).isEqualTo("OK");
    } finally {
      controller.shutdown();
    }
  }

  private static void verifyEvaluations(
      RedisCommands<byte[], byte[]> commands, String sha1, int count) {
    verify(commands, times(count))
        .evalsha(
            eq(sha1),
            eq(ScriptOutputType.STATUS),
            any(byte[][].class),
            any(byte[].class),
            any(byte[].class),
            any(byte[].class),
            any(byte[].class),
            any(byte[].class));
  }

  private ObservedDirectTextTransport observation() {
    var observed = new ObservedDirectTextTransport("101", UUID.randomUUID());
    ownedKeys.add(key(observed));
    return observed;
  }

  private static byte[] key(ObservedDirectTextTransport transport) {
    return ascii(DirectTextWorldMenuRedisStore.OWNED_PREFIX + transport.proxyConnectionId());
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  private static RedisScriptCatalog installed() {
    return RedisScriptCatalog.loadInstalled(DirectTextWorldMenuRedisStore.class.getClassLoader());
  }

  private static RedisClient client(String principal, String password) {
    var uri = RedisURI.builder().withHost(REDIS.getHost()).withPort(REDIS.getMappedPort(6379));
    if (principal != null) uri.withAuthentication(principal, password);
    var client = RedisClient.create(uri.build());
    client.setOptions(ClientOptions.builder().autoReconnect(false).build());
    return client;
  }

  @SuppressWarnings("unchecked")
  private static RedisCommands<byte[], byte[]> delegatedCommands(
      RedisCommands<byte[], byte[]> actual) {
    return mock(RedisCommands.class, delegatesTo(actual));
  }

  @SuppressWarnings("unchecked")
  private static StatefulRedisConnection<byte[], byte[]> delegatedConnection(
      StatefulRedisConnection<byte[], byte[]> physical, RedisCommands<byte[], byte[]> commands) {
    StatefulRedisConnection<byte[], byte[]> connection =
        mock(StatefulRedisConnection.class, delegatesTo(physical));
    when(connection.sync()).thenReturn(commands);
    return connection;
  }

  private static Fixture fixture(Clock clock) {
    var first = target(false);
    var second = target(true);
    var targets = List.of(first, second);
    var routes = mock(CanonicalPlayerRouteReadService.class);
    when(routes.readVisiblePublicProductionTargets()).thenReturn(targets);
    targets.forEach(target -> when(routes.readCurrentTarget(target)).thenReturn(target));
    return new Fixture(
        clock,
        targets,
        GameplayWorldCatalog.forWorldViews(List.of(world(second), world(first))),
        routes);
  }

  private record Fixture(
      Clock clock,
      List<CanonicalPlayableTarget> targets,
      GameplayWorldCatalog catalog,
      CanonicalPlayerRouteReadService routes) {
    DirectTextWorldSelectionService selection() {
      return new DirectTextWorldSelectionService(catalog, routes, clock);
    }
  }

  private static WorldView world(CanonicalPlayableTarget target) {
    return new WorldView(
        target.worldSlug(),
        target.worldDisplayName(),
        List.of(
            new RealmView(
                target.realmSlug(),
                target.realmDisplayName(),
                target.gameSessionTenantId(),
                target.gameInstanceId(),
                target.pointerVersion(),
                true,
                true,
                false,
                target.playableStateScope(),
                target.characterCreationPolicy(),
                target.catalogRevision(),
                target.realmId(),
                target.playableStateNamespaceId())));
  }

  private static CanonicalPlayableTarget target(boolean second) {
    return new CanonicalPlayableTarget(
        "gameplay",
        second ? "other" : "studio",
        UUID.randomUUID(),
        second ? "second" : "forest",
        "World",
        UUID.randomUUID(),
        "public",
        "Public",
        second ? 909L : 101L,
        second ? 808L : 202L,
        UUID.randomUUID(),
        "SHARED",
        UUID.randomUUID(),
        UUID.randomUUID(),
        303L,
        1L,
        7L,
        "d".repeat(64),
        82L,
        "original-open",
        "a".repeat(64),
        OriginKind.NO_PRIOR_POINTER,
        null,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "sha256:" + "c".repeat(64),
        27L,
        "sha256:" + "f".repeat(64),
        Outcome.COMMITTED,
        false,
        Instant.parse("2026-10-01T00:00:00Z"),
        "ALLOW_NEW");
  }
}
