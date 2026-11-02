package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lettuce.core.AclSetuserArgs;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.cluster.SlotHash;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.output.ArrayOutput;
import io.lettuce.core.output.StatusOutput;
import io.lettuce.core.protocol.CommandArgs;
import io.lettuce.core.protocol.CommandKeyword;
import io.lettuce.core.protocol.CommandType;
import io.lettuce.core.protocol.ProtocolKeyword;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority;
import net.firedevops.firemud.accountservice.service.session.AccountCoordinationPinnedConnectionProvider.AcknowledgementRequirements;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.redis.contracts.RedisScriptContribution;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor;
import net.firedevops.firemud.test.TestContainerImages;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Actual primary/replica Redis and Lua with synthetic original records and controlled external
 * qualification. This proves preliminary receipt mechanics, not SQL admission, live qualification,
 * JWT authentication, ordinary logout, Game Design dispatch or runtime activation.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class AccountTenantCreationAdmissionRedisIntegrationTest {
  private static final String USER = "account_coord_app";
  private static final String PASSWORD = UUID.randomUUID().toString();
  private static final String WRONG_USER = "another_account_reader";
  private static final String WRONG_PASSWORD = UUID.randomUUID().toString();
  private static final String BINDING = "test-only-creation-coordination";
  private static final Network NETWORK = Network.newNetwork();
  private static final RedisScriptCatalog CATALOG = catalog();

  @Container
  static final GenericContainer<?> primary =
      new GenericContainer<>(TestContainerImages.redis())
          .withNetwork(NETWORK)
          .withNetworkAliases("creation-admission-primary")
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
              "always",
              "--notify-keyspace-events",
              "K$");

  @Container
  static final GenericContainer<?> replica =
      new GenericContainer<>(TestContainerImages.redis())
          .withNetwork(NETWORK)
          .dependsOn(primary)
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
              "creation-admission-primary",
              "6379");

  private static RedisClient admin;

  @BeforeAll
  static void configureIsolatedRedis() throws InterruptedException {
    admin = RedisClient.create(uri(null, null));
    try (var connection = admin.connect(ByteArrayCodec.INSTANCE)) {
      var commands = connection.sync();
      commands.aclSetuser(
          USER,
          new AclSetuserArgs()
              .reset()
              .on()
              .addPassword(PASSWORD)
              .keyPattern("session:auth:token:*")
              .keyPattern("session:auth:creation-admission:*")
              .addCommand(CommandType.HELLO)
              .addCommand(CommandType.PING)
              .addCommand(CommandType.CLIENT, CommandKeyword.SETINFO)
              .addCommand(CommandType.ACL, CommandKeyword.WHOAMI)
              .addCommand(CommandType.SCRIPT, CommandKeyword.LOAD)
              .addCommand(CommandType.EVALSHA)
              .addCommand(CommandType.GET)
              .addCommand(CommandType.SET)
              .addCommand(CommandType.PEXPIRETIME));
      commands.dispatch(
          AclCommand.INSTANCE,
          new StatusOutput<>(ByteArrayCodec.INSTANCE),
          new CommandArgs<>(ByteArrayCodec.INSTANCE).add("SETUSER").add(USER).add("+waitaof"));
      commands.aclSetuser(
          WRONG_USER,
          new AclSetuserArgs()
              .reset()
              .on()
              .addPassword(WRONG_PASSWORD)
              .keyPattern("session:auth:token:*")
              .keyPattern("session:auth:creation-admission:*")
              .addCommand(CommandType.HELLO)
              .addCommand(CommandType.PING)
              .addCommand(CommandType.CLIENT, CommandKeyword.SETINFO)
              .addCommand(CommandType.ACL, CommandKeyword.WHOAMI)
              .addCommand(CommandType.GET)
              .addCommand(CommandType.PEXPIRETIME));
      commands.set(bytes("test-only:creation-admission:aof-probe"), bytes("probe"));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
      while (System.nanoTime() < deadline) {
        List<Object> counts = waitAof(commands, 1, 1, 500);
        if (sufficient(counts)) return;
        Thread.sleep(50);
      }
      throw new IllegalStateException("Isolated creation Redis replica failed to become durable");
    }
  }

  @AfterAll
  static void closeAdmin() {
    if (admin != null) admin.shutdown();
  }

  @Test
  void exactReceiptRetainsSlotBytesDeadlineAndRecoversAfterActualTokenRevocation()
      throws Exception {
    var f = new Original();
    seed(f, f.active);
    assertThat(SlotHash.getSlot(f.receiptKey())).isEqualTo(SlotHash.getSlot(f.tokenKey()));
    try (var provider = provider(USER, PASSWORD)) {
      var client = client(provider, 1, Clock.systemUTC());
      client.withInitialReceipt(
          f.binding,
          f.active,
          receipt -> {
            assertThat(receipt.localAofCount()).isGreaterThanOrEqualTo(1L);
            assertThat(receipt.replicaAofCount()).isGreaterThanOrEqualTo(1L);
            receipt.requireCurrent();
            return true;
          });
      assertReceipt(f);
      assertThat(revoke(f)).isEqualTo(1L);
      assertThatThrownBy(() -> client.withInitialReceipt(f.binding, f.active, receipt -> true))
          .isInstanceOf(IllegalStateException.class);
      // Existing-only recovery does not attempt to authenticate the now-revoked original JWT.
      client.withExistingReceipt(
          f.binding,
          receipt -> {
            assertThat(receipt.canonicalReceipt()).isEqualTo(f.binding.canonicalReceipt());
            return true;
          });
      assertReceipt(f);
      try (var connection = admin.connect(ByteArrayCodec.INSTANCE)) {
        connection.sync().del(bytes(f.receiptKey()));
      }
      assertThatThrownBy(() -> client.withExistingReceipt(f.binding, receipt -> true))
          .isInstanceOf(IllegalStateException.class);
      assertThat(get(f.receiptKey())).isNull();
      assertThatThrownBy(() -> client.withInitialReceipt(f.binding, f.active, receipt -> true))
          .isInstanceOf(IllegalStateException.class);
      assertThat(get(f.receiptKey())).isNull();
    }
  }

  @Test
  void denyFirstAndConcurrentRevocationHaveOnlyAtomicOriginalOutcomes() throws Exception {
    var denied = new Original();
    seed(denied, denied.active);
    assertThat(revoke(denied)).isEqualTo(1L);
    try (var provider = provider(USER, PASSWORD)) {
      var client = client(provider, 1, Clock.systemUTC());
      assertThatThrownBy(
              () -> client.withInitialReceipt(denied.binding, denied.active, receipt -> true))
          .isInstanceOf(IllegalStateException.class);
      assertThat(get(denied.receiptKey())).isNull();
      for (int attempt = 0; attempt < 8; attempt++) {
        var f = new Original();
        seed(f, f.active);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
          var creation =
              pool.submit(
                  () -> {
                    ready.countDown();
                    await(start);
                    try {
                      return client.withInitialReceipt(f.binding, f.active, receipt -> true);
                    } catch (IllegalStateException refused) {
                      return false;
                    }
                  });
          var revocation =
              pool.submit(
                  () -> {
                    ready.countDown();
                    await(start);
                    return revoke(f);
                  });
          assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
          start.countDown();
          assertThat(revocation.get(10, TimeUnit.SECONDS)).isEqualTo(1L);
          boolean created = creation.get(10, TimeUnit.SECONDS);
          if (created) {
            assertReceipt(f);
            client.withExistingReceipt(f.binding, receipt -> true);
          } else {
            assertThat(get(f.receiptKey())).isNull();
          }
        }
      }
    }
  }

  @Test
  void exactExistingRetryPhysicallySetsIdenticalBytesAtUnchangedAbsoluteDeadline()
      throws Exception {
    var f = new Original();
    seed(f, f.active);
    try (var provider = provider(USER, PASSWORD)) {
      var client = client(provider, 1, Clock.systemUTC());
      client.withInitialReceipt(f.binding, f.active, receipt -> true);
      assertThat(revoke(f)).isEqualTo(1L);
      var setObserved = new CountDownLatch(1);
      try (var observer = admin.connectPubSub(ByteArrayCodec.INSTANCE)) {
        observer.addListener(
            new RedisPubSubAdapter<byte[], byte[]>() {
              @Override
              public void message(byte[] channel, byte[] message) {
                if ("set".equals(new String(message, StandardCharsets.US_ASCII)))
                  setObserved.countDown();
              }
            });
        observer.sync().subscribe(bytes("__keyspace@0__:" + f.receiptKey()));
        client.withExistingReceipt(f.binding, receipt -> true);
        assertThat(setObserved.await(5, TimeUnit.SECONDS)).isTrue();
      }
      assertReceipt(f);
    }
  }

  @Test
  void existingOnlyModeNeverCreatesMissingOrLostReceiptEvenWhileTokenRemainsActive() {
    var f = new Original();
    seed(f, f.active);
    try (var provider = provider(USER, PASSWORD)) {
      var client = client(provider, 1, Clock.systemUTC());
      assertThatThrownBy(() -> client.withExistingReceipt(f.binding, receipt -> true))
          .isInstanceOf(IllegalStateException.class);
      assertThat(get(f.receiptKey())).isNull();
      client.withInitialReceipt(f.binding, f.active, receipt -> true);
      try (var connection = admin.connect(ByteArrayCodec.INSTANCE)) {
        connection.sync().del(bytes(f.receiptKey()));
      }
      assertThat(get(f.tokenKey())).isEqualTo(f.active);
      assertThatThrownBy(() -> client.withExistingReceipt(f.binding, receipt -> true))
          .isInstanceOf(IllegalStateException.class);
      assertThat(get(f.receiptKey())).isNull();
    }
  }

  @Test
  void missingPendingMalformedAndChangedTokenOrPhysicalExpiryCannotCreateReceipt() {
    try (var provider = provider(USER, PASSWORD)) {
      var client = client(provider, 1, Clock.systemUTC());
      var missing = new Original();
      assertThatThrownBy(
              () -> client.withInitialReceipt(missing.binding, missing.active, receipt -> true))
          .isInstanceOf(IllegalStateException.class);
      assertThat(get(missing.receiptKey())).isNull();
      for (String state : List.of("pending", "retiring", "revoked", "malformed", "changed")) {
        var f = new Original();
        var fields = new HashMap<>(f.fields);
        fields.put("state", state);
        seed(
            f,
            "malformed".equals(state) ? bytes("{") : AccountControlUiAuthority.canonical(fields));
        assertThatThrownBy(() -> client.withInitialReceipt(f.binding, f.active, receipt -> true))
            .isInstanceOf(IllegalStateException.class);
        assertThat(get(f.receiptKey())).isNull();
      }
      var wrongExpiry = new Original();
      seed(wrongExpiry, wrongExpiry.active);
      try (var connection = admin.connect(ByteArrayCodec.INSTANCE)) {
        connection
            .sync()
            .pexpireat(
                bytes(wrongExpiry.tokenKey()),
                wrongExpiry.binding.tokenCleanupDeadlineEpochMillis() + 1L);
      }
      assertThatThrownBy(
              () ->
                  client.withInitialReceipt(
                      wrongExpiry.binding, wrongExpiry.active, receipt -> true))
          .isInstanceOf(IllegalStateException.class);
      assertThat(get(wrongExpiry.receiptKey())).isNull();
    }
  }

  @Test
  void logicalDeadlinesAndConflictingReceiptRemainSeparateFromCleanupTtl() throws Exception {
    var f = new Original();
    seed(f, f.active);
    try (var provider = provider(USER, PASSWORD)) {
      var client = client(provider, 1, Clock.systemUTC());
      client.withInitialReceipt(f.binding, f.active, receipt -> true);
      var atDeadline =
          Clock.fixed(
              Instant.ofEpochMilli(f.binding.operationDeadlineEpochMillis()), ZoneOffset.UTC);
      assertThatThrownBy(
              () -> client(provider, 1, atDeadline).withExistingReceipt(f.binding, receipt -> true))
          .isInstanceOf(IllegalStateException.class);
      assertThat(
              lua(
                  f,
                  f.receiptKey(),
                  "EXISTING_ONLY",
                  new byte[0],
                  f.binding.canonicalReceipt(),
                  f.binding.operationDeadlineEpochMillis()))
          .isEqualTo(-2L);
      assertThat(
              lua(
                  f,
                  f.receiptKey(),
                  "EXISTING_ONLY",
                  new byte[0],
                  f.binding.canonicalReceipt(),
                  f.binding.tokenExpiresAtEpochMillis()))
          .isEqualTo(-2L);
      assertReceipt(f);
      // A changed exact-original payload is not replaceable, including a different target.
      byte[] changed =
          new String(f.binding.canonicalReceipt(), StandardCharsets.UTF_8)
              .replace("\"targetNamespace\":\"test-game\"", "\"targetNamespace\":\"other-game\"")
              .getBytes(StandardCharsets.UTF_8);
      assertThat(lua(f, f.receiptKey(), "INITIAL", f.active, changed, System.currentTimeMillis()))
          .isEqualTo(-3L);
      assertReceipt(f);
      try (var connection = admin.connect(ByteArrayCodec.INSTANCE)) {
        connection
            .sync()
            .pexpireat(bytes(f.receiptKey()), f.binding.receiptCleanupDeadlineEpochMillis() + 1L);
      }
      assertThatThrownBy(() -> client.withExistingReceipt(f.binding, receipt -> true))
          .isInstanceOf(IllegalStateException.class);
      assertThat(get(f.receiptKey())).isEqualTo(f.binding.canonicalReceipt());
    }
  }

  @Test
  void scriptRejectsHashOnlyCompanionAndMalformedNumericFieldsBeforeMutation() throws Exception {
    var f = new Original();
    seed(f, f.active);
    String wrongSlot =
        "session:auth:creation-admission:{"
            + f.binding.tokenHash()
            + "}:"
            + f.binding.creationOperationId();
    assertThat(
            lua(
                f,
                wrongSlot,
                "INITIAL",
                f.active,
                f.binding.canonicalReceipt(),
                System.currentTimeMillis()))
        .isEqualTo(-1L);
    assertThat(get(wrongSlot)).isNull();
    for (String malformed : List.of("\"not-number\"", "{}", "null")) {
      byte[] changed =
          new String(f.binding.canonicalReceipt(), StandardCharsets.UTF_8)
              .replace(
                  "\"tokenExpiresAtEpochMillis\":" + f.binding.tokenExpiresAtEpochMillis(),
                  "\"tokenExpiresAtEpochMillis\":" + malformed)
              .getBytes(StandardCharsets.UTF_8);
      assertThat(lua(f, f.receiptKey(), "INITIAL", f.active, changed, System.currentTimeMillis()))
          .isEqualTo(-1L);
      assertThat(get(f.receiptKey())).isNull();
    }
  }

  @Test
  void insufficientPhysicalAofAckAndDroppedSufficientReplyCannotReachCallback() {
    var f = new Original();
    seed(f, f.active);
    var called = new AtomicBoolean();
    try (var provider = provider(USER, PASSWORD)) {
      assertThatThrownBy(
              () ->
                  client(provider, 2, Clock.systemUTC())
                      .withInitialReceipt(
                          f.binding, f.active, receipt -> called.compareAndSet(false, true)))
          .isInstanceOf(IllegalStateException.class);
      assertThat(called).isFalse();
      assertReceipt(f);

      var dropped = new AtomicBoolean();
      AccountCoordinationPinnedConnectionProvider dropping =
          () -> dropAofReply(provider.openPinnedConnection(), dropped);
      assertThatThrownBy(
              () ->
                  client(dropping, 1, Clock.systemUTC())
                      .withExistingReceipt(f.binding, receipt -> called.compareAndSet(false, true)))
          .isInstanceOf(IllegalStateException.class);
      assertThat(dropped).isTrue();
      assertThat(called).isFalse();
      assertReceipt(f);
      // Recovery must reassert again and obtain its own real physical acknowledgement/readback.
      client(provider, 1, Clock.systemUTC()).withExistingReceipt(f.binding, receipt -> true);
      assertReceipt(f);
    }
  }

  @Test
  void unavailableIndependentReadbackAndWrongPrincipalPreserveNonadmittingOriginal() {
    var f = new Original();
    seed(f, f.active);
    try (var provider = provider(USER, PASSWORD);
        var wrong = provider(WRONG_USER, WRONG_PASSWORD)) {
      assertThatThrownBy(
              () ->
                  client(wrong, 1, Clock.systemUTC())
                      .withInitialReceipt(f.binding, f.active, receipt -> true))
          .isInstanceOf(IllegalStateException.class);
      assertThat(get(f.receiptKey())).isNull();
      var opened = new AtomicInteger();
      AccountCoordinationPinnedConnectionProvider interrupted =
          () -> {
            if (opened.incrementAndGet() == 2)
              throw new IllegalStateException("Controlled readback interruption");
            return provider.openPinnedConnection();
          };
      var called = new AtomicBoolean();
      assertThatThrownBy(
              () ->
                  client(interrupted, 1, Clock.systemUTC())
                      .withInitialReceipt(
                          f.binding, f.active, receipt -> called.compareAndSet(false, true)))
          .isInstanceOf(IllegalStateException.class);
      assertThat(opened).hasValue(2);
      assertThat(called).isFalse();
      assertReceipt(f);
    }
  }

  private static AccountTenantCreationAdmissionRedisClient client(
      AccountCoordinationPinnedConnectionProvider provider, int replicas, Clock clock) {
    return new AccountTenantCreationAdmissionRedisClient(
        provider,
        new AcknowledgementRequirements(1, replicas, 1000),
        CATALOG,
        (scope, binding) -> {
          // Controlled test exclusion only; no production authority/provider is supplied.
          var closed = new AtomicBoolean();
          return Optional.of(
              new AccountTenantCreationAdmissionCoordinationQualification.Lease() {
                @Override
                public String scope() {
                  return scope;
                }

                @Override
                public String protectedBindingId() {
                  return binding;
                }

                @Override
                public long fence() {
                  return 11L;
                }

                @Override
                public void requireCurrent() {
                  if (closed.get())
                    throw new IllegalStateException("Fixture qualification released");
                }

                @Override
                public void close() {
                  closed.set(true);
                }
              });
        },
        BINDING,
        clock);
  }

  private static LettuceAccountCoordinationPinnedConnectionProvider provider(
      String user, String password) {
    return new LettuceAccountCoordinationPinnedConnectionProvider(uri(user, password));
  }

  private static RedisURI uri(String user, String password) {
    var builder = RedisURI.Builder.redis(primary.getHost(), primary.getMappedPort(6379));
    if (user != null) builder.withAuthentication(user, password);
    return builder.build();
  }

  private static void seed(Original f, byte[] record) {
    try (var connection = admin.connect(ByteArrayCodec.INSTANCE)) {
      connection.sync().set(bytes(f.tokenKey()), record);
      connection.sync().pexpireat(bytes(f.tokenKey()), f.binding.tokenCleanupDeadlineEpochMillis());
    }
  }

  private static Long revoke(Original f) throws Exception {
    var fields = new HashMap<>(f.fields);
    fields.put("state", "revoked");
    fields.put("registryVersion", 3);
    try (var connection = admin.connect(ByteArrayCodec.INSTANCE)) {
      byte[] source = source(AccountControlUiRegistryContract.RESOURCE);
      String sha = connection.sync().scriptLoad(source);
      return connection
          .sync()
          .evalsha(
              sha,
              ScriptOutputType.INTEGER,
              new byte[][] {bytes(f.tokenKey())},
              f.active,
              AccountControlUiAuthority.canonical(fields),
              bytes(Long.toString(f.binding.tokenCleanupDeadlineEpochMillis())));
    }
  }

  private static Long lua(
      Original f, String receiptKey, String mode, byte[] active, byte[] receipt, long now)
      throws Exception {
    try (var connection = admin.connect(ByteArrayCodec.INSTANCE)) {
      byte[] source = source(AccountTenantCreationAdmissionRedisContract.RESOURCE);
      assertThat(AccountControlUiCoordination.digest("SHA-256", source))
          .isEqualTo(AccountTenantCreationAdmissionRedisContract.descriptor().sha256());
      String sha = connection.sync().scriptLoad(source);
      return connection
          .sync()
          .evalsha(
              sha,
              ScriptOutputType.INTEGER,
              new byte[][] {bytes(f.tokenKey()), bytes(receiptKey)},
              bytes(mode),
              active,
              receipt,
              bytes(Long.toString(now)));
    }
  }

  private static byte[] source(String resource) throws Exception {
    try (var input =
        AccountTenantCreationAdmissionRedisIntegrationTest.class
            .getClassLoader()
            .getResourceAsStream(resource)) {
      if (input == null) throw new IllegalStateException("Missing owner Lua resource");
      return input.readAllBytes();
    }
  }

  private static byte[] get(String key) {
    try (var connection = admin.connect(ByteArrayCodec.INSTANCE)) {
      return connection.sync().get(bytes(key));
    }
  }

  private static void assertReceipt(Original f) {
    try (var connection = admin.connect(ByteArrayCodec.INSTANCE)) {
      assertThat(connection.sync().get(bytes(f.receiptKey())))
          .isEqualTo(f.binding.canonicalReceipt());
      assertThat(connection.sync().pexpiretime(bytes(f.receiptKey())))
          .isEqualTo(f.binding.receiptCleanupDeadlineEpochMillis());
    }
  }

  private static List<Object> waitAof(
      RedisCommands<byte[], byte[]> commands, int local, int replicas, int timeout) {
    return commands.dispatch(
        WaitAof.INSTANCE,
        new ArrayOutput<>(ByteArrayCodec.INSTANCE),
        new CommandArgs<>(ByteArrayCodec.INSTANCE).add(local).add(replicas).add(timeout));
  }

  private static boolean sufficient(List<Object> counts) {
    return counts != null
        && counts.size() == 2
        && counts.get(0) instanceof Long local
        && local >= 1L
        && counts.get(1) instanceof Long replicas
        && replicas >= 1L;
  }

  @SuppressWarnings("unchecked")
  private static StatefulRedisConnection<byte[], byte[]> dropAofReply(
      StatefulRedisConnection<byte[], byte[]> actual, AtomicBoolean dropped) {
    var commands = actual.sync();
    var wrappedCommands =
        Proxy.newProxyInstance(
            RedisCommands.class.getClassLoader(),
            new Class<?>[] {RedisCommands.class},
            (proxy, method, args) -> {
              Object result = invoke(method, commands, args);
              if ("dispatch".equals(method.getName())
                  && args != null
                  && args.length > 0
                  && args[0] instanceof ProtocolKeyword keyword
                  && "WAITAOF".equals(new String(keyword.getBytes(), StandardCharsets.US_ASCII))) {
                if (!(result instanceof List<?> counts)
                    || counts.size() != 2
                    || !(counts.get(0) instanceof Long local)
                    || local < 1L
                    || !(counts.get(1) instanceof Long replicas)
                    || replicas < 1L) {
                  throw new IllegalStateException(
                      "Expected a real sufficient physical acknowledgement");
                }
                dropped.set(true);
                throw new IllegalStateException(
                    "Controlled loss after real sufficient WAITAOF reply");
              }
              return result;
            });
    return (StatefulRedisConnection<byte[], byte[]>)
        Proxy.newProxyInstance(
            StatefulRedisConnection.class.getClassLoader(),
            new Class<?>[] {StatefulRedisConnection.class},
            (proxy, method, args) ->
                "sync".equals(method.getName()) ? wrappedCommands : invoke(method, actual, args));
  }

  private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException failure) {
      throw failure.getCause();
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS))
        throw new IllegalStateException("Fixture race timed out");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Fixture race interrupted", interrupted);
    }
  }

  private static RedisScriptCatalog catalog() {
    return RedisScriptCatalog.fromContributions(
        List.of(
            new RedisScriptContribution() {
              @Override
              public String ownerId() {
                return "account-service";
              }

              @Override
              public Collection<RedisScriptDescriptor> descriptors() {
                return List.of(AccountTenantCreationAdmissionRedisContract.descriptor());
              }
            }));
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  private static final class Original {
    final Map<String, Object> fields;
    final byte[] active;
    final AccountTenantCreationAdmissionBinding binding;

    Original() {
      long now = Instant.now().getEpochSecond() * 1000L;
      long expires = now + 300_000L;
      UUID account = UUID.randomUUID();
      UUID issuance = UUID.randomUUID();
      String hash =
          AccountControlUiCoordination.digest("SHA-256", bytes(UUID.randomUUID().toString()));
      fields =
          Map.ofEntries(
              Map.entry("schemaVersion", 1),
              Map.entry("registryVersion", 2),
              Map.entry("tokenHash", hash),
              Map.entry("kid", "key-1"),
              Map.entry("signerGeneration", "1"),
              Map.entry("issuer", "firemud-account-service"),
              Map.entry("profile", "control-ui"),
              Map.entry("type", "control-ui"),
              Map.entry("audience", "control-ui"),
              Map.entry("accountId", account.toString()),
              Map.entry("jti", UUID.randomUUID().toString()),
              Map.entry("iat", now / 1000L),
              Map.entry("nbf", now / 1000L),
              Map.entry("exp", expires / 1000L),
              Map.entry("tokenGeneration", 1),
              Map.entry("authorityTuple", Map.of("accountAuthorityGeneration", 1)),
              Map.entry("membershipVersion", Map.of()),
              Map.entry("issuanceFence", 1),
              Map.entry("operationId", issuance.toString()),
              Map.entry("requestId", UUID.randomUUID().toString()),
              Map.entry("requestDigest", "a".repeat(64)),
              Map.entry("registryCleanupDeadlineEpochMillis", expires + 60_000L),
              Map.entry("state", "active"),
              Map.entry("authoritySourceVersions", Map.of()),
              Map.entry("authEvidenceBundle", Map.of("bundleVersion", "1")),
              Map.entry("originalSignerReceiptDigest", "b".repeat(64)));
      active = AccountControlUiAuthority.canonical(fields);
      binding =
          new AccountTenantCreationAdmissionBinding(
              UUID.randomUUID(),
              UUID.randomUUID(),
              issuance,
              account,
              "test-game",
              UUID.randomUUID(),
              "sha256:" + "c".repeat(64),
              "sha256:" + "d".repeat(64),
              AccountControlUiCoordination.digest("SHA-256", active),
              UUID.randomUUID(),
              hash,
              expires + 60_000L,
              expires,
              now,
              now + 240_000L,
              now + 240_000L + 86_400_000L);
    }

    String tokenKey() {
      return AccountTenantCreationAdmissionRedisKeys.token(binding.tokenHash());
    }

    String receiptKey() {
      return AccountTenantCreationAdmissionRedisKeys.receipt(
          binding.tokenHash(), binding.creationOperationId());
    }
  }

  private enum WaitAof implements ProtocolKeyword {
    INSTANCE;

    @Override
    public byte[] getBytes() {
      return bytes("WAITAOF");
    }
  }

  private enum AclCommand implements ProtocolKeyword {
    INSTANCE;

    @Override
    public byte[] getBytes() {
      return bytes("ACL");
    }
  }
}
