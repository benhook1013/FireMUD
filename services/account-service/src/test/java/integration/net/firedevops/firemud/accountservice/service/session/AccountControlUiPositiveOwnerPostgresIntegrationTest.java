package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.codec.ByteArrayCodec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.redis.contracts.RedisScriptContribution;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor;
import net.firedevops.firemud.test.TestContainerImages;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Isolated positive Account storage/producer proof with explicitly stipulated upstream trust,
 * materializer Secret observations, validator inventory/acceptance and external creator/legal
 * evidence. Generated ephemeral key custody and test-only ConfigMap CAS replace platform delivery.
 * Real Account repositories, transactions, coordinator, materialization RPC owner, mounted signer,
 * issuance, physical Coordination Redis and actor/order owners execute. No production trust,
 * deployed Pod attribution, World APPLIED, public wiring or runtime activation is established.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class AccountControlUiPositiveOwnerPostgresIntegrationTest {
  private static final Network NETWORK = Network.newNetwork();
  private static final String PASSWORD = UUID.randomUUID().toString();

  @Container
  static final PostgreSQLContainer<?> postgres =
      AccountControlUiOwnerWorkflowPostgresIntegrationTest.postgres;

  @Container
  static final GenericContainer<?> redis =
      new GenericContainer<>(TestContainerImages.redis())
          .withNetwork(NETWORK)
          .withNetworkAliases("positive-control-ui-primary")
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
              "always");

  @Container
  static final GenericContainer<?> replica =
      new GenericContainer<>(TestContainerImages.redis())
          .withNetwork(NETWORK)
          .dependsOn(redis)
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
              "positive-control-ui-primary",
              "6379");

  @TempDir Path temporary;

  @Test
  void realSignerLifecycleIssuesAuthenticatesAndClaimsExactOriginalAccountOrder() throws Exception {
    var f =
        new AccountControlUiOwnerSourcesFixture(
            postgres.getJdbcUrl(),
            postgres.getUsername(),
            postgres.getPassword(),
            temporary,
            false);
    var lifecycle = new AccountControlUiSignerFixture(f, temporary);
    lifecycle.commit();
    // This is the actual private lifecycle-derived signer receipt, never a handcrafted Capture.
    var originalSigner = f.tx(lifecycle.signer::captureCurrent);
    assertThat(f.tx(() -> lifecycle.signer.requireOriginal(originalSigner.receipt())).receipt())
        .isEqualTo(originalSigner.receipt());
    custody("encryption", "enc1", 11);
    custody("request-mac", "mac1", 29);
    try (var connections = new RedisFixture()) {
      var admin = connections.admin;
      var accountClient = connections.account;
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
      }
      var registry =
          new AccountControlUiCoordination(
              () -> accountClient.connect(ByteArrayCodec.INSTANCE),
              new AccountGameplayDelegationRedisClient.AcknowledgementRequirements(1, 1, 5000),
              testCatalog());
      var operations = new AccountControlUiIssuanceRepository(f.dsl);
      var publicSource =
          new AccountJwtJwksTrustedSource(
              lifecycle.client,
              lifecycle.trust,
              lifecycle.desired,
              lifecycle.publication,
              f.manager);
      var actors =
          new AccountControlUiActorService(
              operations,
              f.authority,
              lifecycle.signer,
              registry,
              publicSource,
              f.fences,
              f.manager,
              Clock.systemUTC());
      var issuance =
          new AccountControlUiIssuanceService(
              f.primary,
              operations,
              f.authority,
              f.fences,
              lifecycle.signer,
              new AccountControlUiResponseCryptography(
                  new AccountControlUiKeyring(temporary.resolve("custody")), Clock.systemUTC()),
              registry,
              actors,
              f.manager,
              Clock.systemUTC(),
              AccountControlUiOwnerWorkflowPostgresIntegrationTest.CALLER);
      var environment = f.terms.captureCurrentEnvironmentBoundary();
      String compact;
      byte[] originalCompact;
      Instant originalExpiry, originalRecoveryExpiry;
      try (var peer =
              AccountControlUiOwnerWorkflowPostgresIntegrationTest.withPeer(
                  AccountControlUiOwnerWorkflowPostgresIntegrationTest.CALLER);
          var issued =
              issuance.issue(
                  f.request(AccountControlUiOwnerWorkflowPostgresIntegrationTest.OTP),
                  environment)) {
        originalCompact = issued.compactBytes();
        compact = new String(originalCompact, StandardCharsets.US_ASCII);
        originalExpiry = issued.expiresAt();
        originalRecoveryExpiry = issued.recoveryExpiresAt();
      }
      assertThat(f.challenges.findByAccountId(f.account.getId())).isEmpty();
      var originalOperation = f.tx(() -> operations.findRequest(f.request));
      var originalEnvelope = f.tx(() -> operations.envelope(originalOperation));
      // Simulate loss of the completed response: recover with the original, now-consumed OTP.
      // No new challenge or primary-auth credential is installed before the exact retry.
      try (var peer =
              AccountControlUiOwnerWorkflowPostgresIntegrationTest.withPeer(
                  AccountControlUiOwnerWorkflowPostgresIntegrationTest.CALLER);
          var recovered =
              issuance.issue(
                  f.request(AccountControlUiOwnerWorkflowPostgresIntegrationTest.OTP),
                  environment)) {
        assertThat(recovered.compactBytes()).isEqualTo(originalCompact);
        assertThat(recovered.expiresAt()).isEqualTo(originalExpiry);
        assertThat(recovered.recoveryExpiresAt()).isEqualTo(originalRecoveryExpiry);
        assertThatThrownBy(() -> issuance.issue(f.request("changed-original-otp"), environment))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Control-ui issuance idempotency conflict");
      }
      assertThat(f.challenges.findByAccountId(f.account.getId())).isEmpty();
      var recoveredOperation = f.tx(() -> operations.findRequest(f.request));
      assertThat(recoveredOperation.operationId).isEqualTo(originalOperation.operationId);
      assertThat(recoveredOperation.status).isEqualTo("COMMITTED");
      assertThat(recoveredOperation.tokenHash).isEqualTo(originalOperation.tokenHash);
      assertThat(recoveredOperation.claims).isEqualTo(originalOperation.claims);
      var recoveredEnvelope = f.tx(() -> operations.envelope(recoveredOperation));
      assertThat(recoveredEnvelope.encrypted()).isEqualTo(originalEnvelope.encrypted());
      assertThat(recoveredEnvelope.binding()).isEqualTo(originalEnvelope.binding());
      assertThat(f.dsl.fetchCount(DSL.table("account_control_ui_issuance_operations")))
          .isEqualTo(1);
      assertThat(f.dsl.fetchCount(DSL.table("account_control_ui_response_envelopes"))).isEqualTo(1);
      var actor = actors.authenticate(compact, f.tenant, environment);
      assertThat(actor.accountId()).isEqualTo(f.account.getAccountUuid());
      var snapshot = f.tx(() -> f.authority.captureInitial(f.tenant, environment));
      var binding = originalBinding(f, snapshot.sources());
      var claimed = actors.claimOriginalDraft(compact, binding, environment);
      assertThat(claimed.ordering().name()).isEqualTo("COMMIT_ORDER");
      assertOrder(actors.claimOriginalDraft(compact, binding, environment), binding);
      assertOrder(f.tx(() -> f.fences.read(binding)), binding);
      assertThat(f.dsl.fetchCount(DSL.table("account_control_ui_issuance_operations")))
          .isEqualTo(1);
      assertThat(f.dsl.fetchCount(DSL.table("account_control_ui_response_envelopes"))).isEqualTo(1);
      String tokenHash =
          AccountControlUiIssuanceRepository.hash(compact.getBytes(StandardCharsets.US_ASCII));
      var retained = f.tx(() -> operations.findToken(tokenHash));
      assertThat(retained.status).isEqualTo("COMMITTED");
      assertThat(retained.signerReceipt).isEqualTo(originalSigner.receipt());
      var active = registry.readActive(tokenHash);
      assertThat(active).isNotEmpty();
      var committed = f.tx(() -> operations.requireCommitted(operations.findToken(tokenHash)));
      var insufficientAck =
          new AccountControlUiCoordination(
              () -> accountClient.connect(ByteArrayCodec.INSTANCE),
              new AccountGameplayDelegationRedisClient.AcknowledgementRequirements(1, 2, 1000),
              testCatalog());
      assertThatThrownBy(() -> insufficientAck.activate(committed))
          .isInstanceOf(IllegalStateException.class);
      assertThat(registry.readActive(tokenHash)).isEqualTo(active);
      // An exact actor cannot claim an original binding whose retained Account source is changed.
      var changed = new ArrayList<>(snapshot.sources());
      var first = changed.getFirst();
      changed.set(
          0,
          new DraftAuthorizationFenceBinding.SourceEvidence(
              first.kind(),
              first.scopeId(),
              first.generation(),
              first.sourceVersion(),
              first.checkpointStream(),
              first.checkpointSequence(),
              new byte[] {99}));
      var changedBinding = originalBinding(f, changed);
      assertThatThrownBy(() -> actors.claimOriginalDraft(compact, changedBinding, environment))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Exact current authenticated initial creator required");
      assertOrder(f.tx(() -> f.fences.read(binding)), binding);
    }
  }

  private static DraftAuthorizationFenceBinding originalBinding(
      AccountControlUiOwnerSourcesFixture f,
      List<DraftAuthorizationFenceBinding.SourceEvidence> sources) {
    var complete =
        DraftCommitBinding.create(
            new DraftCommitBinding.TargetProof(
                f.tenant,
                UUID.randomUUID(),
                1,
                "test-game-key",
                2,
                "test-game-key",
                "NEW_GAME_ROW"),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "test-only-base",
            List.of(
                new DraftCommitBinding.RevisionPayload(
                    "0",
                    UUID.randomUUID(),
                    DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                    "test-only-world-input")),
            List.of(
                new DraftCommitBinding.AffectedUnit(
                    DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                    "region",
                    "region-1",
                    "aggregate",
                    "region-1",
                    "0")));
    return new DraftAuthorizationFenceBinding(
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
        sources);
  }

  private static RedisClient redisClient(boolean application) {
    var uri = RedisURI.Builder.redis(redis.getHost(), redis.getMappedPort(6379));
    if (application) uri.withAuthentication("account_coord_app", PASSWORD);
    var result = RedisClient.create(uri.build());
    result.setOptions(ClientOptions.builder().autoReconnect(false).build());
    return result;
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

  private static void assertOrder(
      net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository
              .FenceSnapshot
          observed,
      DraftAuthorizationFenceBinding binding) {
    assertThat(observed.ordering().name()).isEqualTo("COMMIT_ORDER");
    assertThat(observed.binding()).isEqualTo(binding.canonicalBytes());
    assertThat(observed.orderedAt()).isNotNull();
  }

  private static final class RedisFixture implements AutoCloseable {
    final RedisClient admin = redisClient(false), account = redisClient(true);

    @Override
    public void close() {
      account.shutdown();
      admin.shutdown();
    }
  }

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
