package net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.lettuce.core.AclSetuserArgs;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.output.StatusOutput;
import io.lettuce.core.protocol.CommandArgs;
import io.lettuce.core.protocol.CommandKeyword;
import io.lettuce.core.protocol.CommandType;
import io.lettuce.core.protocol.ProtocolVersion;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.account.v1.InitialGameplayLoginTokenWitness;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.gamesession.client.CanonicalGameplayLoginAccountClient;
import net.firedevops.firemud.gamesession.dto.CanonicalGameplayLoginResult;
import net.firedevops.firemud.gamesession.dto.InitialGameplayLoginAttemptCoordinates;
import net.firedevops.firemud.gamesession.repository.InitialGameplayLoginCoordinationJournalRepository;
import net.firedevops.firemud.gamesession.service.InitialGameplayLoginRedisBinding.AcknowledgementPolicy;
import net.firedevops.firemud.gamesession.service.InitialGameplayLoginRedisBinding.Profile;
import org.jooq.DSLContext;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Actual original journal, PENDING, Account client and INSTALLED composition over borrowed physical
 * stores. Credential delivery and external Redis qualification remain controlled. The caller must
 * finish earlier users of the GS Redis principal before opening this fixture; no active sibling
 * connections are reset. The decoded readback is historical evidence, not receiving custody,
 * current token authority, gameplay admission or a production qualification provider.
 */
@SuppressWarnings("resource")
public final class InitialGameplayLoginPhysicalCompositionFixture implements AutoCloseable {
  private static final String PRINCIPAL = InitialGameplayLoginRedisBinding.PRINCIPAL;
  private static final String BINDING = "controlled-physical-initial-login-primary";
  private final InitialGameplayLoginCoordinationJournalRepository journal;
  private final RedisClient writer;
  private final List<String> mutations = new ArrayList<>();
  private final List<String> journalObservations = new ArrayList<>();
  private InitialGameplayLoginOwner.Outcome outcome;
  private CanonicalGameplayLoginResult loginResult;
  private InitialGameplayLoginContextState installedReadback;
  private boolean closed;

  /**
   * Transfers the mutable OTP buffer, but borrows the already migrated SQL owners, initialized
   * actual Account client and test-owned AOF primary/replica. No listener or runtime bean is added.
   */
  public static InitialGameplayLoginPhysicalCompositionFixture open(
      DSLContext gs,
      PlatformTransactionManager transactionManager,
      String redisHost,
      int redisPort,
      CanonicalGameplayLoginAccountClient account,
      String email,
      String canonicalClientAddress,
      char[] ownedOtp) {
    Objects.requireNonNull(ownedOtp, "Owned credential buffer required");
    InitialGameplayLoginPhysicalCompositionFixture fixture = null;
    try (var credentials =
        new InitialGameplayLoginCredentialPresentation(email, canonicalClientAddress, ownedOtp)) {
      fixture = new InitialGameplayLoginPhysicalCompositionFixture(gs, redisHost, redisPort);
      fixture.receive(transactionManager, account, credentials);
      assertThat(Arrays.equals(ownedOtp, new char[ownedOtp.length])).isTrue();
      return fixture;
    } catch (RuntimeException | Error failure) {
      if (fixture != null) {
        try {
          fixture.close();
        } catch (RuntimeException | Error cleanup) {
          failure.addSuppressed(cleanup);
        }
      }
      throw failure;
    } finally {
      Arrays.fill(ownedOtp, '\0');
    }
  }

  private InitialGameplayLoginPhysicalCompositionFixture(
      DSLContext gs, String redisHost, int redisPort) {
    var actualJournal = new InitialGameplayLoginCoordinationJournalRepository(gs);
    // Observation only: every method delegates to the unchanged real SQL owner.
    journal =
        org.mockito.Mockito.mock(
            InitialGameplayLoginCoordinationJournalRepository.class,
            invocation -> {
              String operation = invocation.getMethod().getName();
              try {
                Object result =
                    invoke(actualJournal, invocation.getMethod(), invocation.getArguments());
                journalObservations.add(operation + ":OK");
                return result;
              } catch (Throwable failure) {
                String sqlState =
                    failure instanceof org.jooq.exception.DataAccessException sql
                        ? sql.sqlState()
                        : "none";
                journalObservations.add(
                    operation + ":" + failure.getClass().getSimpleName() + ":" + sqlState);
                throw failure;
              }
            });
    String password = UUID.randomUUID().toString();
    configureOwnedPrincipal(redisHost, redisPort, password);
    writer = redis(redisHost, redisPort, PRINCIPAL, password);
  }

  private void receive(
      PlatformTransactionManager transactionManager,
      CanonicalGameplayLoginAccountClient account,
      InitialGameplayLoginCredentialPresentation credentials) {
    var contexts =
        new InitialGameplayLoginContextRedisClient(
            new InitialGameplayLoginRedisBinding(
                BINDING,
                this::observedConnection,
                new AcknowledgementPolicy(Profile.REPLICATED, 1, 1, 5_000)),
            RedisScriptCatalog.fromContributions(
                List.of(new InitialGameplayLoginRedisScriptContribution())),
            new ControlledQualification());
    var cleanup =
        new InitialGameplayLoginCleanupOwner(
            journal,
            new InitialGameplayLoginRedisAttemptClosure(contexts),
            account,
            transactionManager);
    outcome = new InitialGameplayLoginOwner(contexts, cleanup, account).receive(credentials);
    assertThat(outcome.status())
        .as(
            "Initial LOGIN failureCode=%s journal=%s mutations=%s",
            outcome.failureCode(), journalObservations, mutations)
        .isEqualTo(InitialGameplayLoginOwner.Status.INSTALLED_NON_ADMITTING);
    loginResult = outcome.takePrivateResult().orElseThrow();
    assertThat(mutations).containsExactly("CREATE_PENDING", "INSTALL");
    installedReadback = physicalReadback();
    assertThat(installedReadback.state())
        .isEqualTo(InitialGameplayLoginContextState.State.INSTALLED);
    assertThat(installedReadback.evidence()).isEqualTo(loginResult.evidence());
    var retained = journal.readCommitted(coordinates()).orElseThrow();
    assertThat(retained.abandoned()).isFalse();
    assertThat(retained.verifiedWitness()).contains(witness(installedReadback));
    assertThat(retained.frozenRetirementRequest()).isEmpty();
    byte[] compact = loginResult.compactJwtBytes();
    try {
      assertThat(containsSequence(installedReadback.encode(), compact)).isFalse();
    } finally {
      Arrays.fill(compact, (byte) 0);
    }
  }

  public InitialGameplayLoginAttemptCoordinates coordinates() {
    return outcome.coordinates();
  }

  /** The actual owner outcome, retained for explicit pre-admission custody-loss cleanup. */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification =
          "The fixture deliberately shares its identical live Outcome for exact-original cleanup; copying or reconstructing it would lose the owned lifecycle obligation.")
  public InitialGameplayLoginOwner.Outcome outcome() {
    return outcome;
  }

  /** Borrow the actual transferred result; the fixture retains its cleanup responsibility. */
  public CanonicalGameplayLoginResult loginResult() {
    return loginResult;
  }

  /** Historical physical installation evidence only; this does not authenticate current custody. */
  public InitialGameplayLoginContextState installedReadback() {
    return installedReadback;
  }

  /**
   * Call before shutting down Account transport; known-token disposal uses that actual receiver.
   */
  public InitialGameplayLoginCleanupOwner.CleanupObservation abandonOriginalAfterCustodyLoss() {
    var observation = outcome.abandonOriginalAfterCustodyLoss();
    assertThat(physicalReadback().state()).isEqualTo(InitialGameplayLoginContextState.State.CLOSED);
    var retained = journal.readCommitted(coordinates()).orElseThrow();
    assertThat(retained.abandoned()).isTrue();
    assertThat(retained.frozenRetirementRequest()).isPresent();
    assertThat(retained.frozenRetirementRequest().orElseThrow().getTokenWitness())
        .isEqualTo(witness(installedReadback));
    assertThatThrownBy(loginResult::compactJwtBytes).isInstanceOf(IllegalStateException.class);
    return observation;
  }

  private InitialGameplayLoginContextState physicalReadback() {
    try (var connection = writer.connect(ByteArrayCodec.INSTANCE)) {
      var commands = connection.sync();
      assertThat(commands.aclWhoami()).isEqualTo(PRINCIPAL);
      byte[] key = key(coordinates());
      var decoded = InitialGameplayLoginContextState.decode(commands.get(key));
      assertThat(decoded.coordinates()).isEqualTo(coordinates());
      assertThat(commands.pttl(key)).isEqualTo(-1L);
      return decoded;
    }
  }

  /** Every command still delegates to the real pinned physical connection. */
  @SuppressWarnings("unchecked")
  private StatefulRedisConnection<byte[], byte[]> observedConnection() {
    var connection = writer.connect(ByteArrayCodec.INSTANCE);
    return (StatefulRedisConnection<byte[], byte[]>)
        Proxy.newProxyInstance(
            StatefulRedisConnection.class.getClassLoader(),
            new Class<?>[] {StatefulRedisConnection.class},
            (proxy, method, args) -> {
              if (!method.getName().equals("sync")) return invoke(connection, method, args);
              var commands = connection.sync();
              return Proxy.newProxyInstance(
                  RedisCommands.class.getClassLoader(),
                  new Class<?>[] {RedisCommands.class},
                  (commandProxy, command, arguments) -> {
                    if (command.getName().equals("evalsha")) {
                      beforeMutation(commands, (byte[][]) arguments[2], (byte[][]) arguments[3]);
                    }
                    return invoke(commands, command, arguments);
                  });
            });
  }

  private void beforeMutation(
      RedisCommands<byte[], byte[]> commands, byte[][] keys, byte[][] args) {
    require(keys.length == 1 && args.length == 3);
    String operation = new String(args[0], StandardCharsets.US_ASCII);
    var desired = InitialGameplayLoginContextState.decode(args[2]);
    var original = desired.coordinates();
    require(Arrays.equals(keys[0], key(original)));
    var committed = journal.readCommitted(original).orElseThrow();
    require(original.expectedAttemptFence() == 1);
    require(!original.requestId().equals(original.callerContextId()));
    if ("CREATE_PENDING".equals(operation)) {
      require(mutations.isEmpty());
      require(!committed.abandoned() && committed.verifiedWitness().isEmpty());
      // Observation only: the actual Lua absence check remains the create authority.
      require(commands.get(keys[0]) == null);
    } else if ("INSTALL".equals(operation)) {
      require(mutations.equals(List.of("CREATE_PENDING")));
      require(!committed.abandoned());
      require(committed.verifiedWitness().filter(witness(desired)::equals).isPresent());
      var pending = InitialGameplayLoginContextState.decode(commands.get(keys[0]));
      require(pending.coordinates().equals(original));
      require(pending.state() == InitialGameplayLoginContextState.State.PENDING);
    } else {
      require("CLOSE".equals(operation) && committed.abandoned());
    }
    mutations.add(operation);
  }

  private static InitialGameplayLoginTokenWitness witness(InitialGameplayLoginContextState state) {
    var evidence = state.evidence();
    return InitialGameplayLoginTokenWitness.newBuilder()
        .setTokenJti(evidence.tokenJti().toString())
        .setTokenSha256(evidence.tokenSha256())
        .setTokenGeneration(Long.parseLong(evidence.tokenGeneration()))
        .setIssuanceFence(Long.parseLong(evidence.issuanceFence()))
        .build();
  }

  private static Object invoke(Object target, Method method, Object[] arguments) throws Throwable {
    try {
      return method.invoke(target, arguments);
    } catch (InvocationTargetException failure) {
      throw failure.getCause();
    }
  }

  private static void configureOwnedPrincipal(String host, int port, String password) {
    var administrator = redis(host, port, null, null);
    try (var connection = administrator.connect(ByteArrayCodec.INSTANCE)) {
      var commands = connection.sync();
      require(
          commands
              .clientList()
              .lines()
              .noneMatch(line -> line.contains(" user=" + PRINCIPAL + " ")));
      commands.aclSetuser(
          PRINCIPAL,
          new AclSetuserArgs()
              .reset()
              .on()
              .addPassword(password)
              .keyPattern(InitialGameplayLoginContextRedisKeys.FAMILY_SCOPE + "*")
              .addCommand(CommandType.HELLO)
              .addCommand(CommandType.PING)
              .addCommand(CommandType.CLIENT, CommandKeyword.SETINFO)
              .addCommand(CommandType.ACL, CommandKeyword.WHOAMI)
              .addCommand(CommandType.SCRIPT, CommandKeyword.LOAD)
              .addCommand(CommandType.EVALSHA)
              .addCommand(CommandType.GET)
              .addCommand(CommandType.SET)
              .addCommand(CommandType.TYPE)
              .addCommand(CommandType.PTTL)
              .addCommand(CommandType.PEXPIRETIME));
      commands.dispatch(
          CommandType.ACL,
          new StatusOutput<>(ByteArrayCodec.INSTANCE),
          new CommandArgs<>(ByteArrayCodec.INSTANCE).add("SETUSER").add(PRINCIPAL).add("+waitaof"));
    } finally {
      administrator.shutdown(Duration.ZERO, Duration.ofSeconds(2));
    }
  }

  private static RedisClient redis(String host, int port, String username, String password) {
    var builder = RedisURI.Builder.redis(host, port);
    if (username != null) builder.withAuthentication(username, password);
    var uri = builder.build();
    uri.setTimeout(Duration.ofSeconds(10));
    var client = RedisClient.create(uri);
    client.setOptions(
        ClientOptions.builder()
            .autoReconnect(false)
            .protocolVersion(ProtocolVersion.RESP2)
            .build());
    return client;
  }

  private static byte[] key(InitialGameplayLoginAttemptCoordinates coordinates) {
    return InitialGameplayLoginContextRedisKeys.contextKey(coordinates.callerContextId())
        .getBytes(StandardCharsets.US_ASCII);
  }

  private static boolean containsSequence(byte[] haystack, byte[] needle) {
    for (int offset = 0; offset <= haystack.length - needle.length; offset++) {
      if (Arrays.equals(haystack, offset, offset + needle.length, needle, 0, needle.length)) {
        return true;
      }
    }
    return false;
  }

  private static void require(boolean condition) {
    if (!condition)
      throw new IllegalStateException("Physical initial LOGIN fixture precondition failed");
  }

  /** Stipulated test authority only, never a production recovery or transport-custody producer. */
  private static final class ControlledQualification
      implements InitialGameplayLoginCoordinationQualification {
    @Override
    public Optional<Lease> acquire(String scope, String binding) {
      require(InitialGameplayLoginContextRedisKeys.FAMILY_SCOPE.equals(scope));
      require(BINDING.equals(binding));
      return Optional.of(
          new Lease() {
            private boolean closed;

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
              return 37;
            }

            @Override
            public void requireCurrent() {
              require(!closed);
            }

            @Override
            public void close() {
              closed = true;
            }
          });
    }
  }

  /**
   * Ends pre-admission private custody before closing owned Redis clients. Retained SQL rows and
   * Redis markers stay with the enclosing disposable physical fixture, without a retention bypass.
   */
  @Override
  public void close() {
    if (closed) return;
    closed = true;
    try {
      if (outcome != null
          && outcome.status() == InitialGameplayLoginOwner.Status.INSTALLED_NON_ADMITTING
          && outcome.custodyLossCleanup().isEmpty()) {
        outcome.abandonOriginalAfterCustodyLoss();
      }
    } finally {
      if (loginResult != null) loginResult.close();
      if (outcome != null) outcome.close();
      writer.shutdown(Duration.ZERO, Duration.ofSeconds(2));
    }
  }
}
