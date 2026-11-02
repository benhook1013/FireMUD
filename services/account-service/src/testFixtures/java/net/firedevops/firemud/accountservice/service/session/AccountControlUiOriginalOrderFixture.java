package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.codec.ByteArrayCodec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.authordraft.AccountDraftTerminalReconciliationService;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository.Settlement;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService.CapturedEnvironmentBoundary;
import net.firedevops.firemud.accountservice.service.session.AccountCoordinationPinnedConnectionProvider.AcknowledgementRequirements;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.GameDesignDraftTerminalReadClient;
import net.firedevops.firemud.common.authoring.WorldDraftTerminalReadClient;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.redis.contracts.RedisScriptContribution;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor;

/**
 * Genuine Account owner mutations. Fresh creator/legal sources and signer platform evidence are
 * explicitly test-only upstream inputs; this fixture provides no production activation or
 * authorization and derives settlement only from exact retained owner readbacks.
 */
public final class AccountControlUiOriginalOrderFixture implements AutoCloseable {
  private final AccountControlUiOwnerSourcesFixture f;
  private final AccountControlUiActorService actors;
  final AccountJwtJwksTrustedSource publicSource;
  final AccountControlUiSignerFixture signerFixture;
  private final AccountControlUiIssuanceService issuance;
  private final AccountControlUiIssuanceRepository operations;
  private final AccountControlUiCoordination coordination;
  private final RedisClient accountClient;
  private final Path temporary;
  private IssuedCreator originalCreator;

  public AccountControlUiOriginalOrderFixture(
      String jdbcUrl,
      String username,
      String password,
      String redisHost,
      int redisPort,
      Path temporary)
      throws Exception {
    this(jdbcUrl, username, password, redisHost, redisPort, temporary, UUID.randomUUID());
  }

  public AccountControlUiOriginalOrderFixture(
      String jdbcUrl,
      String username,
      String password,
      String redisHost,
      int redisPort,
      Path temporary,
      UUID gameDesignTenant)
      throws Exception {
    this(
        jdbcUrl,
        username,
        password,
        redisHost,
        redisPort,
        temporary,
        gameDesignTenant,
        Clock.systemUTC());
  }

  /** Actor clock is injectable for genuine signed-credential expiry recovery proof. */
  public AccountControlUiOriginalOrderFixture(
      String jdbcUrl,
      String username,
      String password,
      String redisHost,
      int redisPort,
      Path temporary,
      UUID gameDesignTenant,
      Clock actorClock)
      throws Exception {
    this(
        jdbcUrl,
        username,
        password,
        redisHost,
        redisPort,
        temporary,
        gameDesignTenant,
        actorClock,
        null);
  }

  /**
   * Test-only historical owner fixture. A null target retains the existing current-default setup.
   */
  public AccountControlUiOriginalOrderFixture(
      String jdbcUrl,
      String username,
      String password,
      String redisHost,
      int redisPort,
      Path temporary,
      UUID gameDesignTenant,
      Clock actorClock,
      String migrationTarget)
      throws Exception {
    this(
        jdbcUrl,
        username,
        password,
        redisHost,
        redisPort,
        temporary,
        gameDesignTenant,
        actorClock,
        migrationTarget,
        Clock.systemUTC());
  }

  /**
   * Test-only owner fixture with a separately injectable issuance clock; actor validation remains
   * on its independently supplied clock.
   */
  public AccountControlUiOriginalOrderFixture(
      String jdbcUrl,
      String username,
      String password,
      String redisHost,
      int redisPort,
      Path temporary,
      UUID gameDesignTenant,
      Clock actorClock,
      String migrationTarget,
      Clock serviceClock)
      throws Exception {
    this.temporary = temporary;
    Objects.requireNonNull(actorClock, "actorClock");
    Objects.requireNonNull(serviceClock, "serviceClock");
    f =
        new AccountControlUiOwnerSourcesFixture(
            jdbcUrl, username, password, temporary, false, gameDesignTenant, migrationTarget);
    signerFixture = new AccountControlUiSignerFixture(f, temporary, serviceClock);
    var lifecycle = signerFixture;
    lifecycle.commit();
    var originalSigner = f.tx(lifecycle.signer::captureCurrent);
    assertThat(f.tx(() -> lifecycle.signer.requireOriginal(originalSigner.receipt())).receipt())
        .isEqualTo(originalSigner.receipt());
    custody("encryption", "enc1", 11);
    custody("request-mac", "mac1", 29);
    String PASSWORD = UUID.randomUUID().toString();
    var admin = RedisClient.create(RedisURI.Builder.redis(redisHost, redisPort).build());
    accountClient =
        RedisClient.create(
            RedisURI.Builder.redis(redisHost, redisPort)
                .withAuthentication("account_coord_app", PASSWORD)
                .build());
    accountClient.setOptions(ClientOptions.builder().autoReconnect(false).build());
    try (var connection = admin.connect(ByteArrayCodec.INSTANCE)) {
      connection
          .sync()
          .aclSetuser(
              "account_coord_app",
              new io.lettuce.core.AclSetuserArgs()
                  .reset()
                  .on()
                  .addPassword(PASSWORD)
                  .keyPattern("session:auth:token:*")
                  .addCommand(io.lettuce.core.protocol.CommandType.HELLO)
                  .addCommand(io.lettuce.core.protocol.CommandType.PING)
                  .addCommand(
                      io.lettuce.core.protocol.CommandType.CLIENT,
                      io.lettuce.core.protocol.CommandKeyword.SETINFO)
                  .addCommand(
                      io.lettuce.core.protocol.CommandType.ACL,
                      io.lettuce.core.protocol.CommandKeyword.WHOAMI)
                  .addCommand(
                      io.lettuce.core.protocol.CommandType.SCRIPT,
                      io.lettuce.core.protocol.CommandKeyword.LOAD)
                  .addCommand(io.lettuce.core.protocol.CommandType.EVALSHA)
                  .addCommand(io.lettuce.core.protocol.CommandType.GET)
                  .addCommand(io.lettuce.core.protocol.CommandType.SET)
                  .addCommand(io.lettuce.core.protocol.CommandType.PEXPIRETIME)
                  .addCommand(io.lettuce.core.protocol.CommandType.TIME));
      connection
          .sync()
          .dispatch(
              TestAclCommand.INSTANCE,
              new io.lettuce.core.output.StatusOutput<>(ByteArrayCodec.INSTANCE),
              new io.lettuce.core.protocol.CommandArgs<>(ByteArrayCodec.INSTANCE)
                  .add("SETUSER")
                  .add("account_coord_app")
                  .add("+waitaof"));
      awaitLocalAndReplicaAof(connection.sync());

    } finally {
      admin.shutdown();
    }
    coordination =
        new AccountControlUiCoordination(
            () -> accountClient.connect(ByteArrayCodec.INSTANCE),
            new AcknowledgementRequirements(1, 1, 5000),
            testCatalog());
    operations =
        migrationTarget != null
                && AccountControlUiOwnerSourcesFixture.HISTORICAL_MIGRATION_TARGETS.contains(
                    migrationTarget)
            ? new AccountHistoricalControlUiIssuanceRepository(f.dsl)
            : new AccountControlUiIssuanceRepository(f.dsl);
    publicSource =
        org.mockito.Mockito.spy(
            new AccountJwtJwksTrustedSource(
                lifecycle.client,
                lifecycle.trust,
                lifecycle.desired,
                lifecycle.publication,
                f.manager));
    actors =
        new AccountControlUiActorService(
            operations,
            f.authority,
            lifecycle.signer,
            coordination,
            publicSource,
            f.fences,
            f.manager,
            actorClock);
    issuance =
        new AccountControlUiIssuanceService(
            f.primary,
            operations,
            f.authority,
            f.fences,
            lifecycle.signer,
            new AccountControlUiResponseCryptography(
                new AccountControlUiKeyring(temporary.resolve("custody")), serviceClock),
            coordination,
            actors,
            f.manager,
            serviceClock,
            300_000L,
            AccountControlUiOwnerSourcesFixture.CALLER);
  }

  /** The same persistence layout used by this fixture's genuine issuance and actor owners. */
  AccountControlUiIssuanceRepository issuanceRepository() {
    return operations;
  }

  public UUID tenantId() {
    return f.tenant;
  }

  /**
   * Real Account-owned selected Game Logic source reads over this fixture's SQL and Coordination
   * Redis owners; callers choose the clock and target namespace for their composition.
   */
  public AccountGameLogicIntakeSourceReadService gameLogicIntakeSourceReadService(
      Clock clock, String namespace) {
    return new AccountGameLogicIntakeSourceReadService(
        new AccountGameLogicIntakeAuthorizationRepository(f.dsl),
        coordination,
        f.manager,
        clock,
        namespace);
  }

  /** Actual preliminary permission owner; upstream creator/environment inputs remain controlled. */
  public AccountSelectedOwnerIntakeSourceReadService selectedOwnerIntakeSourceReadService(
      String namespace) {
    return new AccountSelectedOwnerIntakeSourceReadService(
        new AccountSelectedOwnerIntakeSourceReservationRepository(f.dsl),
        coordination,
        f.manager,
        namespace);
  }

  /**
   * Real Account control-UI creator retained for composition with the original StartSession proof.
   */
  IssuedCreator preparedOriginalCreator() {
    if (originalCreator == null) originalCreator = issueCreator();
    return originalCreator;
  }

  /** Same-package proof reuse; returns only an actual owner-issued and authenticated credential. */
  IssuedCreator issueCreator() {
    var environment = f.terms.captureCurrentEnvironmentBoundary();
    String compact;
    try (var peer =
            AccountControlUiOwnerSourcesFixture.withPeer(
                AccountControlUiOwnerSourcesFixture.CALLER);
        var issued =
            issuance.issue(f.request(AccountControlUiOwnerSourcesFixture.OTP), environment)) {
      compact = new String(issued.compactBytes(), StandardCharsets.US_ASCII);
    }
    assertThat(f.challenges.findByAccountId(f.account.getId())).isEmpty();
    assertThat(actors.authenticate(compact, f.tenant, environment).accountId())
        .isEqualTo(f.account.getAccountUuid());
    return new IssuedCreator(compact, actors, environment, f);
  }

  record IssuedCreator(
      String compact,
      AccountControlUiActorService actors,
      CapturedEnvironmentBoundary environment,
      AccountControlUiOwnerSourcesFixture sources) {}

  // These fixture entry points use actual Account owners with test-only creator/legal,
  // environment, signer-platform and Redis inputs. They confer no production authority.
  public net.firedevops.firemud.common.authoring.AccountOriginalDraftOrderGrpcCodec.Request
      prepareOriginalDraftOrder(DraftCommitBinding complete, String namespace) {
    if (!f.tenant.equals(complete.target().canonicalTenantId()))
      throw new IllegalArgumentException("Original Draft must target the retained Account tenant");
    if (originalCreator == null) originalCreator = issueCreator();
    var sources =
        f.tx(() -> f.authority.captureInitial(f.tenant, originalCreator.environment())).sources();
    var original =
        new DraftAuthorizationFenceBinding(
                UUID.randomUUID(),
                complete.requestId(),
                complete.commitId(),
                UUID.randomUUID(),
                f.account.getAccountUuid(),
                f.tenant,
                complete.target().canonicalVersionId(),
                complete.baseCommitId(),
                "0",
                complete.canonicalBytes(),
                complete.canonicalBytes(),
                complete.digest(),
                sources)
            .withRequiredOwners();
    return net.firedevops.firemud.common.authoring.AccountOriginalDraftOrderGrpcCodec.Request
        .create(namespace, original, originalCreator.compact());
  }

  public io.grpc.BindableService originalDraftOrderProducer(String namespace) {
    return new AccountOriginalDraftOrderGrpcService(
        new AccountOriginalDraftOrderService(actors, namespace), f.terms, namespace);
  }

  public void assertOriginalDraftOrderPending(DraftAuthorizationFenceBinding original) {
    var retained = f.tx(() -> f.fences.read(original));
    assertThat(retained.ordering().name()).isEqualTo("COMMIT_ORDER");
    assertThat(retained.binding()).isEqualTo(original.canonicalBytes());
    assertThat(retained.orderedAt()).isNotNull();
    assertThat(f.tx(() -> f.fences.readSettlement(original)).name()).isEqualTo("PENDING");
  }

  public net.firedevops.firemud.accountservice.authordraft.AccountDraftCommitOrderReadService
      heldOrderOwner(String namespace) {
    return new net.firedevops.firemud.accountservice.authordraft.AccountDraftCommitOrderReadService(
        f.fences, f.manager, namespace);
  }

  /** Controlled composition only; settlement still derives from retained exact owner readbacks. */
  public AccountDraftTerminalReconciliationService terminalReconciler(
      GameDesignDraftTerminalReadClient gameDesignClient,
      WorldDraftTerminalReadClient worldClient,
      String namespace) {
    return new AccountDraftTerminalReconciliationService(
        f.fences, f.manager, gameDesignClient, worldClient, namespace);
  }

  public void assertOriginalDraftSettlement(
      DraftAuthorizationFenceBinding original, Settlement expected) {
    assertThat(f.tx(() -> f.fences.readSettlement(original))).isEqualTo(expected);
  }

  public byte[] originalDraftOwnerReadback(DraftAuthorizationFenceBinding original, Owner owner) {
    return f.tx(
            () ->
                f.fences
                    .readOwnerResult(original, owner)
                    .orElseThrow(() -> new AssertionError("Original Draft owner readback absent"))
                    .readback())
        .clone();
  }

  @Override
  public void close() {
    accountClient.shutdown();
  }

  private static RedisScriptCatalog testCatalog() {
    return RedisScriptCatalog.fromContributions(
        List.of(
            new RedisScriptContribution() {
              @Override
              public String ownerId() {
                return "account-service";
              }

              @Override
              public Collection<RedisScriptDescriptor> descriptors() {
                return List.of(AccountControlUiRegistryContract.descriptor());
              }
            }));
  }

  private void custody(String purpose, String id, int value) throws Exception {
    byte[] key = new byte[32];
    java.util.Arrays.fill(key, (byte) value);
    var directory = Files.createDirectories(temporary.resolve("custody").resolve(purpose));
    Files.writeString(
        directory.resolve("keyring"),
        "firemud-account-response-envelope-keyring-v1\nactive "
            + id
            + " "
            + Base64.getUrlEncoder().withoutPadding().encodeToString(key)
            + "\n");
  }

  private record PublicJwk(String json, String jsonWithoutKeyOps, String fingerprint) {}

  private static void awaitLocalAndReplicaAof(
      io.lettuce.core.api.sync.RedisCommands<byte[], byte[]> commands) throws InterruptedException {
    byte[] probeKey =
        ("test-only:positive-account-coordination-aof-probe:" + UUID.randomUUID())
            .getBytes(StandardCharsets.US_ASCII);
    commands.set(probeKey, "test-only-replication-probe".getBytes(StandardCharsets.US_ASCII));
    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
    while (System.nanoTime() < deadline) {
      List<Object> counts =
          commands.dispatch(
              TestWaitAof.INSTANCE,
              new io.lettuce.core.output.ArrayOutput<>(ByteArrayCodec.INSTANCE),
              new io.lettuce.core.protocol.CommandArgs<>(ByteArrayCodec.INSTANCE)
                  .add(1)
                  .add(1)
                  .add(500));
      if (counts != null
          && counts.size() == 2
          && counts.get(0) instanceof Long local
          && counts.get(1) instanceof Long replicas
          && local >= 1
          && replicas >= 1) {
        return;
      }
      Thread.sleep(50);
    }
    throw new IllegalStateException("Test Coordination Redis replica did not acknowledge AOF");
  }

  private enum TestWaitAof implements io.lettuce.core.protocol.ProtocolKeyword {
    INSTANCE;

    @Override
    public byte[] getBytes() {
      return "WAITAOF".getBytes(StandardCharsets.US_ASCII);
    }
  }

  private enum TestAclCommand implements io.lettuce.core.protocol.ProtocolKeyword {
    INSTANCE;

    @Override
    public byte[] getBytes() {
      return "ACL".getBytes(StandardCharsets.US_ASCII);
    }
  }
}
