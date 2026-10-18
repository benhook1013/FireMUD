package net.firedevops.firemud.automationscripting.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.automationscripting.sourceintake.AutomationEmptySelectedSourceIntakeRepository;
import net.firedevops.firemud.automationscripting.sourceintake.AutomationEmptySelectedSourceIntakeTerminalReadService;
import net.firedevops.firemud.automationscripting.sourceintake.AutomationSelectedSourceIntakeTerminalReadGrpcService;
import net.firedevops.firemud.common.automation.sourceintake.AutomationEmptySelectedSourceIntakeReceipt;
import net.firedevops.firemud.common.automation.sourceintake.AutomationSelectedSourceIntakeTerminalReadClient;
import net.firedevops.firemud.common.automation.sourceintake.AutomationSelectedSourceIntakeTerminalReadEvidence;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Native Account mTLS proof for Automation's owner edge; upstream fixture evidence stays synthetic.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class AutomationSelectedSourceIntakeTerminalReadMtlsPostgresIntegrationTest {
  private static final String NS = "test";
  private static final String ACCOUNT = "spiffe://firemud/ns/test/sa/account-service";
  private static final String AUTOMATION =
      "spiffe://firemud/ns/test/sa/automation-scripting-service";
  private static final String WRONG_ACCOUNT = "spiffe://firemud/ns/other/sa/account-service";
  private static final String MIGRATIONS =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();

  @TempDir static Path temp;

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  private static LoopbackMtlsTestPki.Pki pki;
  private static LoopbackMtlsTestPki.Identity serverIdentity;
  private static LoopbackMtlsTestPki.Identity accountIdentity;
  private static LoopbackMtlsTestPki.Identity wrongIdentity;

  private String schema;
  private DSLContext dsl;
  private AutomationEmptySelectedSourceIntakeRepository repository;
  private Server server;
  private AutomationSelectedSourceIntakeTerminalReadClient client;

  @BeforeAll
  static void issueCertificates() throws Exception {
    pki =
        LoopbackMtlsTestPki.create(
            temp,
            "FireMUD test CA",
            new LoopbackMtlsTestPki.IdentitySpec("automation", AUTOMATION, true),
            new LoopbackMtlsTestPki.IdentitySpec("account", ACCOUNT, false),
            new LoopbackMtlsTestPki.IdentitySpec("wrong-account", WRONG_ACCOUNT, false));
    serverIdentity = pki.identity("automation");
    accountIdentity = pki.identity("account");
    wrongIdentity = pki.identity("wrong-account");
  }

  @BeforeEach
  void migrateIsolatedSchema() {
    schema = "automation_terminal_read_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource ds = dataSource(schema);
    Flyway.configure()
        .dataSource(ds)
        .locations(MIGRATIONS, "classpath:db/migration/saga")
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .target("1002")
        .load()
        .migrate();
    Flyway.configure()
        .dataSource(ds)
        .locations(MIGRATIONS, "classpath:db/migration/saga")
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .load()
        .migrate();
    dsl = DSL.using(ds, SQLDialect.POSTGRES);
    repository = new AutomationEmptySelectedSourceIntakeRepository(dsl);
  }

  @AfterEach
  void cleanup() throws Exception {
    if (client != null) {
      client.close();
      client = null;
    }
    if (server != null) {
      server.shutdownNow();
      assertThat(server.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
      server = null;
    }
    if (schema != null) {
      String safeSchema = schema.replaceAll("[^a-zA-Z0-9_]", "");
      DSL.using(dataSource(null), SQLDialect.POSTGRES)
          .execute("DROP SCHEMA IF EXISTS \"" + safeSchema + "\" CASCADE");
      schema = null;
    }
  }

  @Test
  void readsStoredCommittedEmptyReceiptOverAccountMtlsAndRejectsWrongNamespace() throws Exception {
    var fixture = AutomationEmptySelectedSourceIntakePostgresFixture.create("1");
    String digest =
        AutomationEmptySelectedSourceIntakeReceipt.requestDigest(
            NS, fixture.authorization(), fixture.freezeEvidence());

    // These typed Common inputs are synthetic; proof covers only Automation's owner edge.
    var committed = repository.retainFresh(fixture.inputs(), digest);
    assertThat(committed.outcome()).isEqualTo("COMMITTED_EMPTY");
    Record stored =
        dsl.fetchSingle(
            "SELECT receipt_bytes, receipt_digest FROM automation_empty_selected_source_receipt "
                + "WHERE target_namespace = ? AND intake_request_id = ?",
            committed.targetNamespace(),
            committed.intakeRequestId());
    assertThat(stored).isNotNull();
    assertThat(stored.get(0, byte[].class)).containsExactly(committed.canonicalBytes());
    assertThat(stored.get(1, String.class)).isEqualTo(committed.receiptDigest());

    startReceiver();
    client = newClient(accountIdentity);
    client.init();
    var request =
        AutomationSelectedSourceIntakeTerminalReadEvidence.Request.create(
            NS, fixture.authorization());
    var first = client.read(request);
    assertThat(first.receipt().canonicalBytes()).containsExactly(stored.get(0, byte[].class));
    assertThat(first.receipt().receiptDigest()).isEqualTo(stored.get(1, String.class));
    assertThat(first.receipt().outcome()).isEqualTo("COMMITTED_EMPTY");

    var replayRequest =
        AutomationSelectedSourceIntakeTerminalReadEvidence.Request.create(
            NS, fixture.authorization());
    assertThat(replayRequest.readRequestId()).isNotEqualTo(request.readRequestId());
    var replay = client.read(replayRequest);
    assertThat(replay.request()).isEqualTo(replayRequest);
    assertThat(replay.receipt().canonicalBytes()).containsExactly(first.receipt().canonicalBytes());
    assertThat(replay.receipt().receiptDigest()).isEqualTo(first.receipt().receiptDigest());

    var wrongClient = newClient(wrongIdentity);
    try {
      wrongClient.init();
      assertThatThrownBy(() -> wrongClient.read(request))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              e ->
                  assertThat(Status.fromThrowable(e).getCode())
                      .isEqualTo(Status.Code.PERMISSION_DENIED));
    } finally {
      wrongClient.close();
    }
    assertThat(count("SELECT COUNT(*) FROM automation_empty_selected_source_association"))
        .isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM automation_empty_selected_source_receipt")).isEqualTo(1);
    Record unchanged =
        dsl.fetchSingle(
            "SELECT receipt_bytes, receipt_digest FROM automation_empty_selected_source_receipt "
                + "WHERE target_namespace = ? AND intake_request_id = ?",
            committed.targetNamespace(),
            committed.intakeRequestId());
    assertThat(unchanged).isNotNull();
    assertThat(unchanged.get(0, byte[].class)).containsExactly(first.receipt().canonicalBytes());
    assertThat(unchanged.get(1, String.class)).isEqualTo(first.receipt().receiptDigest());
  }

  private void startReceiver() throws Exception {
    var owner = new AutomationEmptySelectedSourceIntakeTerminalReadService(repository, NS);
    var service = new AutomationSelectedSourceIntakeTerminalReadGrpcService(owner);
    server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .maxInboundMessageSize(
                net.firedevops.firemud.common.automation.sourceintake
                    .AutomationSelectedSourceIntakeTerminalReadGrpcCodec.MAX_WIRE_BYTES)
            .sslContext(
                GrpcSslContexts.configure(
                        SslContextBuilder.forServer(
                            serverIdentity.privateKey(), serverIdentity.certificate()))
                    .trustManager(pki.caCertificate())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(ServerInterceptors.intercept(service, new GrpcPeerIdentityInterceptor()))
            .build()
            .start();
  }

  private AutomationSelectedSourceIntakeTerminalReadClient newClient(
      LoopbackMtlsTestPki.Identity identity) throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setAutomationScriptingService("127.0.0.1:" + server.getPort());
    return new AutomationSelectedSourceIntakeTerminalReadClient(
        endpoints, pki.clientProperties(temp, identity), new GrpcChannelFactory(), NS);
  }

  private DriverManagerDataSource dataSource(String schemaName) {
    var ds = new DriverManagerDataSource();
    ds.setDriverClassName(postgres.getDriverClassName());
    String base = postgres.getJdbcUrl();
    ds.setUrl(
        schemaName == null
            ? base
            : base + (base.contains("?") ? "&" : "?") + "currentSchema=" + schemaName);
    ds.setUsername(postgres.getUsername());
    ds.setPassword(postgres.getPassword());
    return ds;
  }

  private long count(String sql) {
    return dsl.fetchSingle(sql).get(0, Long.class);
  }
}
