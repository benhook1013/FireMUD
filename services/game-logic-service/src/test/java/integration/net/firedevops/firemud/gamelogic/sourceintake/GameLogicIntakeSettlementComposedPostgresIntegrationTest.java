package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamelogic.*;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.gamelogic.sourceintake.*;
import net.firedevops.firemud.test.TestContainerImages;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Actual Account intake producer/SQL guards, GL storage and HELD/terminal mTLS endpoints. GD
 * immutable source and the original author's terminal are explicit upstream stipulations. This
 * defines downstream composition proof, not full authoring-chain or registered runtime proof.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class GameLogicIntakeSettlementComposedPostgresIntegrationTest {
  private static final String NAMESPACE = "test";
  private static final Network NETWORK = Network.newNetwork();

  @Container
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  static final GenericContainer<?> redis =
      new GenericContainer<>(TestContainerImages.redis())
          .withNetwork(NETWORK)
          .withNetworkAliases("intake-settlement-primary")
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
              "intake-settlement-primary",
              "6379");

  @TempDir Path temporary;

  @Test
  void exactTerminalSettlementReleasesOnlyItsOrderAndSurvivesOwnerOutage() throws Exception {
    try (var fixture =
        new AccountControlUiOriginalOrderFixture(
            postgres.getJdbcUrl(),
            postgres.getUsername(),
            postgres.getPassword(),
            redis.getHost(),
            redis.getMappedPort(6379),
            temporary)) {
      var issued = fixture.issueCreator();
      var f = issued.sources();
      var selected = selected(f.tenant);
      var captured = f.tx(() -> f.authority.captureInitial(f.tenant, issued.environment()));
      var original =
          new DraftAuthorizationFenceBinding(
                  UUID.randomUUID(),
                  selected.requestId(),
                  selected.commitId(),
                  UUID.randomUUID(),
                  f.account.getAccountUuid(),
                  f.tenant,
                  selected.target().canonicalVersionId(),
                  selected.baseCommitId(),
                  "0",
                  selected.canonicalBytes(),
                  selected.canonicalBytes(),
                  selected.digest(),
                  captured.sources())
              .withRequiredOwners();
      issued.actors().claimOriginalDraft(issued.compact(), original, issued.environment());
      // Upstream original-author terminal fixture; this test does not manufacture GL HELD evidence.
      f.tx(
          () -> {
            f.fences.recordOwnerReadback(
                original,
                new DraftAuthorizationFenceBinding.OwnerReadback(
                    DraftAuthorizationFenceBinding.Owner.GAME_DESIGN,
                    DraftAuthorizationFenceBinding.Outcome.COMMITTED,
                    original.operationId(),
                    original.commitId(),
                    original.fenceId(),
                    original.inputDigest(),
                    original.canonicalBytes(),
                    new byte[] {1}));
            return null;
          });
      var source = source(selected);
      var gd = mock(GameplayRuleSourceReadClient.class);
      when(gd.read(any()))
          .thenAnswer(call -> new GameplayRuleSourceReadEvidence(call.getArgument(0), source));
      var accountRepository = new AccountGameLogicIntakeAuthorizationRepository(f.dsl);
      var producer =
          new AccountGameLogicIntakeAuthorizationService(
              issued.actors(), f.fences, accountRepository, gd, f.manager, NAMESPACE);
      var retainedOrder =
          asGameDesign(
              () ->
                  producer.authorize(
                      issued.compact(), UUID.randomUUID(), selected, issued.environment()));
      var abortedOrder =
          asGameDesign(
              () ->
                  producer.authorize(
                      issued.compact(), UUID.randomUUID(), selected, issued.environment()));

      String schema = "gl_settlement_" + UUID.randomUUID().toString().replace("-", "");
      var dataSource =
          new DriverManagerDataSource(
              postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
      dataSource.setSchema(schema);
      org.flywaydb.core.Flyway.configure()
          .dataSource(dataSource)
          .schemas(schema)
          .defaultSchema(schema)
          .placeholders(Map.of("serviceSchema", schema))
          .locations("classpath:db/migration")
          .load()
          .migrate();
      var glDsl = DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
      var glManager = new DataSourceTransactionManager(dataSource);
      var glTransaction = new TransactionTemplate(glManager);
      glTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
      var glRepository = new GameLogicGameplayRuleIntakeRepository(glDsl);
      var pki = new TestPki(temporary.resolve("pki"));
      var heldOwner =
          new AccountGameLogicIntakeAuthorizationReadGrpcService(
              new AccountGameLogicIntakeAuthorizationReadService(
                  accountRepository, f.manager, NAMESPACE),
              NAMESPACE);
      var terminalOwner =
          new GameLogicGameplayRuleIntakeTerminalReadGrpcService(
              new GameLogicGameplayRuleIntakeTerminalReadService(glRepository, NAMESPACE),
              NAMESPACE);
      Server accountServer = server(heldOwner, pki.accountServer, pki);
      Server glServer = server(terminalOwner, pki.gameLogicServer, pki);
      var endpoints = new ServiceEndpointsProperties();
      endpoints.setAccountService("127.0.0.1:" + accountServer.getPort());
      endpoints.setGameLogicService("127.0.0.1:" + glServer.getPort());
      try (var held =
              new GameLogicIntakeAuthorizationReadClient(
                  endpoints,
                  pki.gameLogicClient.properties(pki.ca),
                  new GrpcChannelFactory(),
                  NAMESPACE);
          var terminals =
              new GameLogicIntakeTerminalReadClient(
                  endpoints,
                  pki.accountClient.properties(pki.ca),
                  new GrpcChannelFactory(),
                  NAMESPACE)) {
        held.init();
        terminals.init();
        var glOwner =
            new GameLogicGameplayRuleIntakeService(glRepository, glManager, held, gd, NAMESPACE);
        var settlement =
            new AccountGameLogicIntakeSettlementService(
                accountRepository, terminals, f.manager, NAMESPACE);
        var request = GameLogicIntakeTerminalReadEvidence.Request.create(NAMESPACE, retainedOrder);
        assertThat(terminals.read(request).terminal()).isEmpty();
        assertThatThrownBy(() -> asGameDesign(() -> settlement.settle(retainedOrder)))
            .satisfies(
                error ->
                    assertThat(Status.fromThrowable(error).getCode())
                        .isEqualTo(Status.Code.FAILED_PRECONDITION));
        assertThat(f.dsl.fetchCount(DSL.table("account_game_logic_intake_settlements"))).isZero();
        f.tx(
            () -> {
              accountRepository.readHeld(retainedOrder);
              return null;
            });
        var change =
            new DraftAuthorizationFenceRepository.SourceChange(
                UUID.randomUUID(), retainedOrder.sources(), new byte[] {2});
        assertThat(f.tx(() -> f.fences.requestSourceChange(change))).isFalse();

        // Actual HELD transport and local terminal transaction. Both outcomes remain pending here.
        var retained = asGameDesign(() -> glOwner.retain(retainedOrder));
        var aborted = asGameDesign(() -> glOwner.abort(abortedOrder));
        assertThat(asGameDesign(() -> glOwner.retain(abortedOrder)).canonicalBytes())
            .isEqualTo(aborted.canonicalBytes());
        assertThat(aborted.outcome())
            .isEqualTo(GameLogicGameplayRuleIntakeTerminal.Outcome.ABORTED);
        assertThat(terminals.read(request).terminal().orElseThrow().canonicalBytes())
            .isEqualTo(retained.canonicalBytes());
        assertThat(f.tx(() -> f.fences.sourceMutationPermitted(change))).isFalse();
        assertThatThrownBy(
                () ->
                    f.tx(
                        () -> {
                          f.fences.requireDisclosurePreparation(retainedOrder.sources());
                          return null;
                        }))
            .isInstanceOf(IllegalStateException.class);

        // Unknown/changed operation identity, peer workload and namespace fail at the real
        // endpoint.
        var changed =
            new GameLogicIntakeAuthorizationBinding(
                retainedOrder.operationId(),
                retainedOrder.fenceId(),
                UUID.randomUUID(),
                retainedOrder.actorAccountId(),
                retainedOrder.source(),
                retainedOrder.sources());
        assertThatThrownBy(
                () ->
                    terminals.read(
                        GameLogicIntakeTerminalReadEvidence.Request.create(NAMESPACE, changed)))
            .satisfies(
                error ->
                    assertThat(Status.fromThrowable(error).getCode())
                        .isEqualTo(Status.Code.ALREADY_EXISTS));
        for (var identity : List.of(pki.wrongClient, pki.otherNamespaceClient)) {
          try (var denied =
              new GameLogicIntakeTerminalReadClient(
                  endpoints, identity.properties(pki.ca), new GrpcChannelFactory(), NAMESPACE)) {
            denied.init();
            assertThatThrownBy(() -> denied.read(request))
                .satisfies(
                    error ->
                        assertThat(Status.fromThrowable(error).getCode())
                            .isEqualTo(Status.Code.PERMISSION_DENIED));
          }
        }
        for (var identity : List.of(pki.wrongServer, pki.otherNamespaceServer)) {
          Server substituted = server(terminalOwner, identity, pki);
          var substitutedEndpoints = endpoints.copy();
          substitutedEndpoints.setGameLogicService("127.0.0.1:" + substituted.getPort());
          try (var denied =
              new GameLogicIntakeTerminalReadClient(
                  substitutedEndpoints,
                  pki.accountClient.properties(pki.ca),
                  new GrpcChannelFactory(),
                  NAMESPACE)) {
            denied.init();
            assertThatThrownBy(() -> denied.read(request))
                .satisfies(
                    error ->
                        assertThat(Status.fromThrowable(error).getCode())
                            .isEqualTo(Status.Code.UNAUTHENTICATED));
          } finally {
            stop(substituted);
          }
        }

        // Rollback leaves the exact order held; a fabricated/different terminal cannot release it.
        assertThatThrownBy(
                () ->
                    f.tx(
                        () -> {
                          accountRepository.settle(new AccountGameLogicIntakeSettlement(retained));
                          throw new IllegalStateException("rollback settlement");
                        }))
            .hasMessageContaining("rollback");
        f.tx(
            () -> {
              accountRepository.readHeld(retainedOrder);
              return null;
            });
        assertThatThrownBy(
                () ->
                    f.tx(
                        () -> {
                          var receipt = new AccountGameLogicIntakeSettlement(retained);
                          f.dsl.execute(
                              "INSERT INTO account_game_logic_intake_settlements (operation_id, target_namespace, outcome, terminal_bytes, terminal_digest, receipt_bytes, receipt_digest) VALUES (?, ?, ?, ?, ?, ?, ?)",
                              abortedOrder.operationId(),
                              NAMESPACE,
                              "RETAINED",
                              retained.canonicalBytes(),
                              retained.digest(),
                              receipt.canonicalBytes(),
                              receipt.digest());
                          return null;
                        }))
            .hasStackTraceContaining("Changed original Game Logic operation authorization");

        AccountGameLogicIntakeSettlement receipt;
        try (var executor = Executors.newFixedThreadPool(2)) {
          var first = executor.submit(() -> asGameDesign(() -> settlement.settle(retainedOrder)));
          var second = executor.submit(() -> asGameDesign(() -> settlement.settle(retainedOrder)));
          receipt = first.get(20, TimeUnit.SECONDS);
          assertThat(second.get(20, TimeUnit.SECONDS).canonicalBytes())
              .isEqualTo(receipt.canonicalBytes());
        }
        assertThat(receipt.terminal().canonicalBytes()).isEqualTo(retained.canonicalBytes());
        assertThat(f.dsl.fetchCount(DSL.table("account_game_logic_intake_settlements")))
            .isEqualTo(1);
        assertThatThrownBy(
                () ->
                    held.read(
                        GameLogicIntakeAuthorizationReadEvidence.Request.create(
                            NAMESPACE, retainedOrder)))
            .satisfies(
                error ->
                    assertThat(Status.fromThrowable(error).getCode())
                        .isEqualTo(Status.Code.FAILED_PRECONDITION));
        f.tx(
            () -> {
              accountRepository.readHeld(abortedOrder);
              return null;
            });
        assertThat(f.tx(() -> f.fences.sourceMutationPermitted(change))).isFalse();
        assertThatThrownBy(
                () ->
                    f.tx(
                        () -> {
                          f.fences.requireDisclosurePreparation(retainedOrder.sources());
                          return null;
                        }))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(
                () ->
                    f.tx(
                        () -> {
                          f.dsl.execute(
                              "UPDATE accounts SET role = 'admin' WHERE id = ?", f.account.getId());
                          return null;
                        }))
            .hasStackTraceContaining("Distinct Game Logic intake remains pending");
        var abortReceipt = asGameDesign(() -> settlement.settle(abortedOrder));
        assertThat(abortReceipt.terminal().canonicalBytes()).isEqualTo(aborted.canonicalBytes());
        assertThat(f.tx(() -> f.fences.sourceMutationPermitted(change))).isTrue();
        f.tx(
            () -> {
              f.fences.markSourceAborted(
                  change,
                  DraftAuthorizationFenceRepository.SourceChangeAbortReason.DEFINITIVE_ABORT);
              return null;
            });
        f.tx(
            () -> {
              f.fences.requireDisclosurePreparation(retainedOrder.sources());
              return null;
            });
        assertThatThrownBy(
                () ->
                    f.dsl.execute(
                        "UPDATE account_game_logic_intake_settlements SET outcome = 'ABORTED' WHERE operation_id = ?",
                        retainedOrder.operationId()))
            .hasStackTraceContaining("immutable");
        assertThatThrownBy(
                () ->
                    f.dsl.execute(
                        "DELETE FROM account_game_logic_intake_settlements WHERE operation_id = ?",
                        retainedOrder.operationId()))
            .hasStackTraceContaining("immutable");
        assertThatThrownBy(() -> f.dsl.execute("TRUNCATE account_game_logic_intake_settlements"))
            .hasStackTraceContaining("immutable");
        stop(glServer);
        glServer = null;
        // New process collaborators return the stored receipt without an available GL channel.
        var restarted =
            new AccountGameLogicIntakeSettlementService(
                new AccountGameLogicIntakeAuthorizationRepository(f.dsl),
                terminals,
                f.manager,
                NAMESPACE);
        assertThat(asGameDesign(() -> restarted.settle(retainedOrder)).canonicalBytes())
            .isEqualTo(receipt.canonicalBytes());
        assertThatThrownBy(() -> asGameDesign(() -> restarted.settle(changed)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(f.dsl.fetchCount(DSL.table("account_game_logic_intake_settlements")))
            .isEqualTo(2);
        // Historical GL retry remains exact after Account no longer permits HELD.
        assertThat(asGameDesign(() -> glOwner.retain(retainedOrder)).canonicalBytes())
            .isEqualTo(retained.canonicalBytes());
      } finally {
        stop(accountServer);
        if (glServer != null) stop(glServer);
      }
    }
  }

  private static Server server(io.grpc.BindableService owner, TestIdentity identity, TestPki pki)
      throws Exception {
    return NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
        .maxInboundMessageSize(GameLogicGameplayRuleIntakeTerminal.MAX_WIRE_BYTES)
        .sslContext(
            GrpcSslContexts.configure(
                    SslContextBuilder.forServer(
                        identity.certificate().toFile(), identity.key().toFile()))
                .trustManager(pki.ca.toFile())
                .clientAuth(ClientAuth.REQUIRE)
                .build())
        .addService(ServerInterceptors.intercept(owner, new GrpcPeerIdentityInterceptor()))
        .build()
        .start();
  }

  private static void stop(Server server) throws Exception {
    server.shutdownNow();
    assertThat(server.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
  }

  private static DraftCommitBinding selected(UUID tenant) {
    var target =
        new DraftCommitBinding.TargetProof(
            tenant, UUID.randomUUID(), 17, "private", 11, "private", "NEW_GAME_ROW");
    return DraftCommitBinding.create(
        target,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "genesis",
        List.of(
            new DraftCommitBinding.RevisionPayload(
                "0",
                UUID.randomUUID(),
                DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                GameplayRuleSourceRevision.upsertPayload(
                    new GameplayRuleManifest.AdmissionTag("gameplay")))),
        List.of(
            new DraftCommitBinding.AffectedUnit(
                DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                "GAMEPLAY_RULE_SET",
                target.canonicalVersionId().toString(),
                "GAMEPLAY_RULE_SET",
                "effective",
                "0")));
  }

  private static GameplayRuleSelectedSource source(DraftCommitBinding selected) {
    var definition = new GameplayRuleManifest.AdmissionTag("gameplay");
    Map<GameplayRuleManifest.Family, List<GameplayRuleManifest.Definition>> families =
        new EnumMap<>(GameplayRuleManifest.Family.class);
    for (var family : GameplayRuleManifest.Family.values()) families.put(family, new ArrayList<>());
    families.get(definition.family()).add(definition);
    var manifest = new GameplayRuleManifest(families);
    return new GameplayRuleSelectedSource(
        GameplayRuleManifest.canonical(
            Map.of(
                "schema",
                "game-design-gameplay-rule-source-snapshot/v1",
                "bindingJson",
                selected.canonicalJson(),
                "bindingDigest",
                selected.digest(),
                "sourceEpoch",
                "1",
                "inheritedCommitId",
                "",
                "genesisReceiptId",
                UUID.randomUUID().toString(),
                "manifestJson",
                manifest.canonicalJson(),
                "entries",
                List.of(
                    Map.of(
                        "family",
                        definition.family().name(),
                        "definitionJson",
                        GameplayRuleManifest.canonical(definition),
                        "sourceBindingJson",
                        selected.canonicalJson(),
                        "sourceBindingDigest",
                        selected.digest(),
                        "revisionOrder",
                        "0",
                        "revisionId",
                        selected.revisions().getFirst().revisionId().toString())))));
  }

  private static <T> T asGameDesign(java.util.function.Supplier<T> action) {
    var context =
        Context.current()
            .withValue(
                GrpcPeerIdentity.CONTEXT_KEY,
                GrpcPeerIdentity.parseUri("spiffe://firemud/ns/test/sa/game-design-service")
                    .orElseThrow());
    var previous = context.attach();
    try {
      return action.get();
    } finally {
      context.detach(previous);
    }
  }

  /** Ephemeral certificate authority, never a deployed trust root or live workload credential. */
  private static final class TestPki {
    final Path ca;
    final TestIdentity accountServer,
        gameLogicServer,
        gameLogicClient,
        accountClient,
        wrongServer,
        wrongClient,
        otherNamespaceClient,
        otherNamespaceServer;

    TestPki(Path root) throws Exception {
      Files.createDirectories(root);
      var generator = java.security.KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      var caKeys = generator.generateKeyPair();
      var name =
          new org.bouncycastle.asn1.x500.X500Name("CN=Test-only Account Game Logic proof CA");
      var now = java.time.Instant.now();
      var builder =
          new org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
              name,
              java.math.BigInteger.ONE,
              java.util.Date.from(now.minusSeconds(60)),
              java.util.Date.from(now.plusSeconds(3600)),
              name,
              caKeys.getPublic());
      builder.addExtension(
          org.bouncycastle.asn1.x509.Extension.basicConstraints,
          true,
          new org.bouncycastle.asn1.x509.BasicConstraints(true));
      builder.addExtension(
          org.bouncycastle.asn1.x509.Extension.keyUsage,
          true,
          new org.bouncycastle.asn1.x509.KeyUsage(org.bouncycastle.asn1.x509.KeyUsage.keyCertSign));
      var signer =
          new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA")
              .build(caKeys.getPrivate());
      var caCert =
          new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
              .getCertificate(builder.build(signer));
      ca = pem(root.resolve("ca.pem"), caCert);
      accountServer = issue(root, "account-service", true, caKeys, caCert, NAMESPACE);
      gameLogicServer = issue(root, "game-logic-service", true, caKeys, caCert, NAMESPACE);
      gameLogicClient = issue(root, "game-logic-service", false, caKeys, caCert, NAMESPACE);
      accountClient = issue(root, "account-service", false, caKeys, caCert, NAMESPACE);
      wrongServer = issue(root, "game-design-service", true, caKeys, caCert, NAMESPACE);
      wrongClient = issue(root, "game-design-service", false, caKeys, caCert, NAMESPACE);
      otherNamespaceClient = issue(root, "account-service", false, caKeys, caCert, "other-test");
      otherNamespaceServer = issue(root, "game-logic-service", true, caKeys, caCert, "other-test");
    }

    private static TestIdentity issue(
        Path root,
        String service,
        boolean server,
        java.security.KeyPair caKeys,
        java.security.cert.X509Certificate caCert,
        String namespace)
        throws Exception {
      var generator = java.security.KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      var keys = generator.generateKeyPair();
      var now = java.time.Instant.now();
      var builder =
          new org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
              caCert,
              new java.math.BigInteger(120, new java.security.SecureRandom()),
              java.util.Date.from(now.minusSeconds(60)),
              java.util.Date.from(now.plusSeconds(3600)),
              new org.bouncycastle.asn1.x500.X500Name("CN=" + service),
              keys.getPublic());
      builder.addExtension(
          org.bouncycastle.asn1.x509.Extension.basicConstraints,
          true,
          new org.bouncycastle.asn1.x509.BasicConstraints(false));
      builder.addExtension(
          org.bouncycastle.asn1.x509.Extension.keyUsage,
          true,
          new org.bouncycastle.asn1.x509.KeyUsage(
              org.bouncycastle.asn1.x509.KeyUsage.digitalSignature
                  | org.bouncycastle.asn1.x509.KeyUsage.keyEncipherment));
      builder.addExtension(
          org.bouncycastle.asn1.x509.Extension.extendedKeyUsage,
          false,
          new org.bouncycastle.asn1.x509.ExtendedKeyUsage(
              server
                  ? org.bouncycastle.asn1.x509.KeyPurposeId.id_kp_serverAuth
                  : org.bouncycastle.asn1.x509.KeyPurposeId.id_kp_clientAuth));
      builder.addExtension(
          org.bouncycastle.asn1.x509.Extension.subjectAlternativeName,
          false,
          new org.bouncycastle.asn1.x509.GeneralNames(
              new org.bouncycastle.asn1.x509.GeneralName[] {
                new org.bouncycastle.asn1.x509.GeneralName(
                    org.bouncycastle.asn1.x509.GeneralName.uniformResourceIdentifier,
                    "spiffe://firemud/ns/" + namespace + "/sa/" + service),
                new org.bouncycastle.asn1.x509.GeneralName(
                    org.bouncycastle.asn1.x509.GeneralName.dNSName, "localhost"),
                new org.bouncycastle.asn1.x509.GeneralName(
                    org.bouncycastle.asn1.x509.GeneralName.iPAddress, "127.0.0.1")
              }));
      var certificate =
          new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
              .getCertificate(
                  builder.build(
                      new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA")
                          .build(caKeys.getPrivate())));
      String prefix = namespace + "-" + service + (server ? "-server" : "-client");
      return new TestIdentity(
          pem(root.resolve(prefix + ".crt"), certificate),
          pem(root.resolve(prefix + ".key"), keys.getPrivate()));
    }

    private static Path pem(Path path, Object value) throws Exception {
      try (var writer =
          new org.bouncycastle.openssl.jcajce.JcaPEMWriter(Files.newBufferedWriter(path))) {
        writer.writeObject(value);
      }
      return path;
    }
  }

  private record TestIdentity(Path certificate, Path key) {
    CommonGrpcClientProperties properties(Path ca) {
      var result = new CommonGrpcClientProperties();
      result.setPlaintext(false);
      result.setCertChain(certificate.toString());
      result.setPrivateKey(key.toString());
      result.setCaCert(ca.toString());
      return result;
    }
  }
}
