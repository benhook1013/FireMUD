package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Context;
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
import java.util.UUID;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService.CapturedEnvironmentBoundary;
import net.firedevops.firemud.accountservice.service.session.AccountSelectedOwnerIntakeSourceReservationRepository.Recovery;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSourceReadScope;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.gamedesign.SelectedOwnerIntakeSourceClient;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionReadClient;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.redis.contracts.RedisScriptContribution;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor;

/**
 * Genuine Account owner mutations. Fresh creator/legal sources and signer platform evidence are
 * explicitly test-only upstream inputs; this fixture is not production activation or settlement.
 */
public final class AccountControlUiOriginalOrderFixture implements AutoCloseable {
  private final AccountControlUiOwnerSourcesFixture f;
  private final AccountControlUiActorService actors;
  private final AccountControlUiIssuanceService issuance;
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
    this.temporary = temporary;
    f =
        new AccountControlUiOwnerSourcesFixture(
            jdbcUrl, username, password, temporary, false, gameDesignTenant);
    var lifecycle = new AccountControlUiSignerFixture(f, temporary);
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
            new AccountGameplayDelegationRedisClient.AcknowledgementRequirements(1, 1, 5000),
            testCatalog());
    var operations = new AccountControlUiIssuanceRepository(f.dsl);
    var publicSource =
        new AccountJwtJwksTrustedSource(
            lifecycle.client, lifecycle.trust, lifecycle.desired, lifecycle.publication, f.manager);
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
                new AccountControlUiKeyring(temporary.resolve("custody")), Clock.systemUTC()),
            coordination,
            actors,
            f.manager,
            Clock.systemUTC(),
            AccountControlUiOwnerSourcesFixture.CALLER);
  }

  public UUID tenantId() {
    return f.tenant;
  }

  /** Genuine issuance and source capture only; first reservation/order occurs over the producer. */
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

  /** Exact creator evidence retained by prepareOriginalDraftOrder, without issuing it twice. */
  IssuedCreator preparedOriginalCreator() {
    if (originalCreator == null) {
      throw new IllegalStateException(
          "Prepare the original Draft order before reading its creator");
    }
    return originalCreator;
  }

  AccountControlUiCoordination coordination() {
    return coordination;
  }

  /**
   * Invokes the real preliminary reservation owner with the existing creator and current Account
   * environment. The Game Design peer context is stipulated by this direct fixture call; this does
   * not prove authenticated transport.
   */
  public SelectedOwnerIntakeSourceReadScope reserveSelectedOwnerSourceRead(
      UUID intakeRequestId, Owner owner, DraftCommitBinding selected, String namespace) {
    requireOriginalCreator();
    var service = selectedOwnerSourceReservationService(namespace);
    return asStipulatedGameDesignPeer(
        namespace,
        () ->
            service.reserveSourceRead(
                originalCreator.compact(),
                intakeRequestId,
                owner,
                selected,
                originalCreator.environment()));
  }

  /** Exact historical recovery of one preliminary source reservation. */
  public Recovery recoverSelectedOwnerSourceRead(
      SelectedOwnerIntakeSourceReadScope scope, String namespace) {
    var service = selectedOwnerSourceReservationService(namespace);
    return asStipulatedGameDesignPeer(namespace, () -> service.recover(scope));
  }

  /** Definitively aborts only the supplied preliminary source reservation. */
  public Recovery abortSelectedOwnerSourceRead(
      SelectedOwnerIntakeSourceReadScope scope, String namespace) {
    var service = selectedOwnerSourceReservationService(namespace);
    return asStipulatedGameDesignPeer(namespace, () -> service.abortSourceRead(scope));
  }

  /** Real unregistered producer; callers must authenticate the Game Design peer over transport. */
  public io.grpc.BindableService selectedOwnerIntakeAuthorizationProducerReceiver(
      SelectedOwnerIntakeSourceClient sources, String namespace) {
    var owner =
        new AccountSelectedOwnerIntakeAuthorizationService(
            actors,
            f.fences,
            new AccountSelectedOwnerIntakeSourceReservationRepository(f.dsl),
            sources,
            f.manager,
            namespace);
    return new AccountSelectedOwnerIntakeAuthorizationProducerGrpcService(
        owner, f.terms, namespace);
  }

  private AccountSelectedOwnerIntakeSourceReservationService selectedOwnerSourceReservationService(
      String namespace) {
    return new AccountSelectedOwnerIntakeSourceReservationService(
        actors,
        f.fences,
        new AccountSelectedOwnerIntakeSourceReservationRepository(f.dsl),
        f.manager,
        namespace);
  }

  private void requireOriginalCreator() {
    if (originalCreator == null) {
      throw new IllegalStateException(
          "Prepare the original Draft order before reserving selected-owner source reads");
    }
  }

  private static <T> T asStipulatedGameDesignPeer(
      String namespace, java.util.function.Supplier<T> action) {
    var identity =
        GrpcPeerIdentity.parseUri("spiffe://firemud/ns/" + namespace + "/sa/game-design-service")
            .orElseThrow();
    Context context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, identity);
    Context previous = context.attach();
    try {
      return action.get();
    } finally {
      context.detach(previous);
    }
  }

  /** Real unregistered Account producer, with Account-internal environment capture. */
  public io.grpc.BindableService originalDraftOrderProducer(String namespace) {
    return new AccountOriginalDraftOrderGrpcService(
        new AccountOriginalDraftOrderService(actors, namespace), f.terms, namespace);
  }

  /** Exact source participation remains pending; local Game Design success cannot settle it. */
  public void assertOriginalDraftOrderPending(DraftAuthorizationFenceBinding original) {
    var retained = f.tx(() -> f.fences.read(original));
    assertThat(retained.ordering().name()).isEqualTo("COMMIT_ORDER");
    assertThat(retained.binding()).isEqualTo(original.canonicalBytes());
    assertThat(retained.orderedAt()).isNotNull();
    assertThat(f.tx(() -> f.fences.readSettlement(original)).name()).isEqualTo("PENDING");
  }

  /** Uses only actual authenticated terminal clients; it never installs constructed readbacks. */
  public void reconcileOriginalDraft(
      DraftAuthorizationFenceBinding original,
      net.firedevops.firemud.common.authoring.GameDesignDraftTerminalReadClient gameDesign,
      net.firedevops.firemud.common.authoring.WorldDraftTerminalReadClient world,
      String namespace) {
    reconcileOriginalDraft(original, gameDesign, world, namespace, "COMMITTED");
  }

  public void reconcileOriginalDraft(
      DraftAuthorizationFenceBinding original,
      net.firedevops.firemud.common.authoring.GameDesignDraftTerminalReadClient gameDesign,
      net.firedevops.firemud.common.authoring.WorldDraftTerminalReadClient world,
      String namespace,
      String expectedSettlementOutcome) {
    var recovery =
        new net.firedevops.firemud.accountservice.authordraft
            .AccountDraftTerminalReconciliationService(
            f.fences, f.manager, gameDesign, world, namespace);
    assertThat(recovery.reconcile(original.operationId()).orElseThrow().name())
        .isEqualTo(expectedSettlementOutcome);
    assertThat(f.tx(() -> f.fences.readSettlement(original)).name())
        .isEqualTo(expectedSettlementOutcome);
    assertThat(f.tx(() -> f.fences.read(original)).binding()).isEqualTo(original.canonicalBytes());
  }

  /** Caller supplies the real World plan before Account authenticates and captures its sources. */
  public DraftAuthorizationFenceBinding claimOriginal(DraftCommitBinding complete) {
    if (!f.tenant.equals(complete.target().canonicalTenantId()))
      throw new IllegalArgumentException("World plan must target the retained Account tenant");
    var issued = issueCreator();
    var compact = issued.compact();
    var environment = issued.environment();
    var sources = f.tx(() -> f.authority.captureInitial(f.tenant, environment)).sources();
    var binding =
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
    var claimed = actors.claimOriginalDraft(compact, binding, environment);
    assertThat(claimed.ordering().name()).isEqualTo("COMMIT_ORDER");
    assertThat(claimed.binding()).isEqualTo(binding.canonicalBytes());
    var retained = f.tx(() -> f.fences.read(binding));
    assertThat(retained.ordering().name()).isEqualTo("COMMIT_ORDER");
    assertThat(retained.binding()).isEqualTo(binding.canonicalBytes());
    assertThat(retained.orderedAt()).isNotNull();
    originalCreator = issued;
    return binding;
  }

  /**
   * Uses the original Draft creator credential unchanged, after an actual strict selected-Draft
   * read. The incoming Game Design context and upstream selection/platform/legal evidence remain
   * explicit test stipulations; this helper does not establish production publication authority.
   */
  public AccountPublicationAuthorizationBinding authorizePublication(
      AuthoredDraftPublishSelectionBinding selection,
      AuthoredDraftPublishSelectionReadClient selectionClient,
      String namespace) {
    if (originalCreator == null) {
      throw new IllegalStateException("Claim the original Draft before publication authorization");
    }
    var owner =
        new AccountPublicationAuthorizationService(
            actors, f.fences, new AccountPublicationAuthorizationRepository(f.dsl));
    var composition =
        new AccountSelectedDraftPublicationOrderService(selectionClient, owner, namespace);
    var context =
        io.grpc.Context.current()
            .withValue(
                GrpcPeerIdentity.CONTEXT_KEY,
                GrpcPeerIdentity.parseUri(
                        "spiffe://firemud/ns/" + namespace + "/sa/game-design-service")
                    .orElseThrow());
    var prior = context.attach();
    try {
      return composition.authorize(
          originalCreator.compact(), selection, originalCreator.environment());
    } finally {
      context.detach(prior);
    }
  }

  /** Real exact retained-order read owner; no publication registration or settlement is implied. */
  public AccountPublicationAuthorizationReadService heldPublicationOwner(String namespace) {
    return new AccountPublicationAuthorizationReadService(
        new AccountPublicationAuthorizationRepository(f.dsl), f.manager, namespace);
  }

  /**
   * Verifies the complete original publication/source vector and guarded mutation denial. The
   * original Draft is also pending, so writer denial does not isolate publication as its sole
   * cause.
   */
  public void assertPublicationSourcesHeld(AccountPublicationAuthorizationBinding order) {
    var repository = new AccountPublicationAuthorizationRepository(f.dsl);
    f.tx(
        () -> {
          repository.readHeld(order);
          return null;
        });
    var participation =
        f.dsl.fetch(
            "SELECT source_key, source_evidence FROM account_selected_publication_sources"
                + " WHERE operation_id = ? ORDER BY account_publication_authorization_source_sort_key(source_key)",
            order.operationId());
    assertThat(participation).hasSize(order.sources().size());
    for (int i = 0; i < order.sources().size(); i++) {
      assertThat(participation.get(i).get("source_key", String.class))
          .isEqualTo(order.sources().get(i).key());
      assertThat(participation.get(i).get("source_evidence", byte[].class))
          .isEqualTo(order.sources().get(i).canonicalBytes());
    }
    String originalRole =
        f.dsl
            .fetchSingle("SELECT role FROM accounts WHERE id = ?", f.account.getId())
            .get("role", String.class);
    String changedRole = "admin".equals(originalRole) ? "player" : "admin";
    assertThatThrownBy(
            () ->
                f.tx(
                    () ->
                        f.dsl.execute(
                            "UPDATE accounts SET role = ? WHERE id = ?",
                            changedRole,
                            f.account.getId())))
        .isInstanceOf(org.jooq.exception.DataAccessException.class);
    assertThat(
            f.dsl
                .fetchSingle("SELECT role FROM accounts WHERE id = ?", f.account.getId())
                .get("role", String.class))
        .isEqualTo(originalRole);
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

  public net.firedevops.firemud.accountservice.authordraft.AccountDraftCommitOrderReadService
      heldOrderOwner(String namespace) {
    return new net.firedevops.firemud.accountservice.authordraft.AccountDraftCommitOrderReadService(
        f.fences, f.manager, namespace);
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
