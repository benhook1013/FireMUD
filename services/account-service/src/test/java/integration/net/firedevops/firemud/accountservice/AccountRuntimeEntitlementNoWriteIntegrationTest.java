package net.firedevops.firemud.accountservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mockingDetails;

import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.MetadataUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import net.firedevops.firemud.account.v1.AccountServiceGrpc;
import net.firedevops.firemud.account.v1.GetTenantEntitlementsForRuntimeRequest;
import net.firedevops.firemud.account.v1.GetTenantEntitlementsForRuntimeResponse;
import net.firedevops.firemud.accountservice.client.EntityManagementClient;
import net.firedevops.firemud.accountservice.client.GameSessionClient;
import net.firedevops.firemud.accountservice.client.LoggingAdminClient;
import net.firedevops.firemud.accountservice.repository.AccountTenantCapacityRepository;
import net.firedevops.firemud.accountservice.security.AccountPlayerAdmissionEntitlementWorkloadGuard;
import net.firedevops.firemud.accountservice.security.AccountTenantCapacityWorkloadGuard;
import net.firedevops.firemud.accountservice.service.AccountPlayerAdmissionEntitlementOwner;
import net.firedevops.firemud.accountservice.service.AccountService;
import net.firedevops.firemud.accountservice.service.AccountTenantCapacityOwner;
import net.firedevops.firemud.accountservice.service.AccountTenantCapacityRecoveryWorker;
import net.firedevops.firemud.accountservice.service.impl.AccountGrpcService;
import net.firedevops.firemud.accountservice.service.impl.AccountPlayerAdmissionEntitlementGrpcService;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
import net.firedevops.firemud.accountservice.service.impl.AccountTenantCapacityGrpcService;
import net.firedevops.firemud.common.grpc.BoundedGrpcServerFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.security.GrpcAuthProperties;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import net.firedevops.firemud.test.LoopbackMtlsTestPki.IdentitySpec;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.test.TestContainerImages;
import net.firedevops.firemud.test.TlsTestSupport;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.ApplicationContext;
import org.springframework.grpc.server.GrpcServerFactory;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Full default Account application, real database/Redis and ordinary REQUIRE-mTLS listener. Empty
 * capacity cohorts prove no durable creation only. Populated-family preservation,
 * retention/erasure, production PKI, admission and owner activation are not qualified here.
 */
@Testcontainers
@SpringBootTest(
    classes = AccountServiceApplication.class,
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.config.import=classpath:account-main-application.yml",
      "spring.grpc.server.port=0",
      "spring.grpc.server.ssl.enabled=true",
      "spring.grpc.server.ssl.bundle=firemud-grpc",
      "spring.grpc.server.ssl.client-auth=REQUIRE",
      "firemud.grpc.workload-namespace=test",
      "firemud.mail.verification-url=https://account.test/verify-email?token=%s"
    })
class AccountRuntimeEntitlementNoWriteIntegrationTest {
  private static final String METHOD = "account.v1.AccountService/GetTenantEntitlementsForRuntime";
  private static final List<String> CAPACITY_TABLES =
      List.of(
          "account_tenant_capacity_evaluations",
          "account_tenant_capacity_usage",
          "account_tenant_capacity_reservations",
          "account_tenant_capacity_attempt_history",
          "account_tenant_capacity_recovery_evidence");
  private static final GetTenantEntitlementsForRuntimeRequest REQUEST =
      GetTenantEntitlementsForRuntimeRequest.newBuilder()
          .setTenantId("1")
          .setRequestId("default-runtime-entitlement-no-write")
          .build();

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>(TestContainerImages.redis()).withExposedPorts(6379);

  private static Path tlsDirectory;
  private static LoopbackMtlsTestPki.Pki pki;

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) throws Exception {
    PostgresBackedServiceTestSupport.registerPostgresService(registry, postgres, "account_service");
    PostgresBackedServiceTestSupport.registerRedisService(registry, redis);
    tlsDirectory = Files.createTempDirectory("account-runtime-no-write-mtls-");
    pki =
        LoopbackMtlsTestPki.create(
            tlsDirectory,
            "Account default runtime no-write CA",
            new IdentitySpec("account", "spiffe://firemud/ns/test/sa/account-service", true),
            new IdentitySpec(
                "game-session", "spiffe://firemud/ns/test/sa/game-session-service", false),
            new IdentitySpec(
                "world", "spiffe://firemud/ns/test/sa/world-management-service", false),
            new IdentitySpec(
                "wrong-service", "spiffe://firemud/ns/test/sa/entity-management-service", false),
            new IdentitySpec(
                "cross-namespace", "spiffe://firemud/ns/other/sa/game-session-service", false));
    var server = pki.clientProperties(tlsDirectory, pki.identity("account"));
    registry.add("FIREMUD_GRPC_CERT_CHAIN_PATH", server::getCertChain);
    registry.add("FIREMUD_GRPC_PRIVATE_KEY_PATH", server::getPrivateKey);
    registry.add("FIREMUD_GRPC_CA_CERT_PATH", server::getCaCert);
  }

  @Autowired private ApplicationContext context;
  @Autowired private DataSource dataSource;
  @Autowired private DSLContext dsl;
  @Autowired private AccountService accountService;
  @Autowired private AccountServiceImpl accountFacade;
  @Autowired private AccountGrpcService accountGrpcService;
  @Autowired private GrpcServerLifecycle listener;

  @MockitoBean private EntityManagementClient entityManagementClient;
  @MockitoBean private GameSessionClient gameSessionClient;
  @MockitoBean private LoggingAdminClient loggingAdminClient;
  @MockitoBean private JavaMailSender mailSender;

  @BeforeEach
  void assertActualDefaultRegistration() {
    assertThat(listener.isRunning()).isTrue();
    assertThat(listener.getPort()).isPositive();
    assertThat(listener.getFactory()).isSameAs(context.getBean(GrpcServerFactory.class));
    assertThat(listener.getFactory()).isExactlyInstanceOf(BoundedGrpcServerFactory.class);
    assertThat(accountService).isSameAs(accountFacade);
    for (Object actual :
        List.of(
            accountService,
            accountGrpcService,
            dataSource,
            dsl,
            context.getBean(GrpcPeerIdentityInterceptor.class))) {
      assertThat(mockingDetails(actual).isMock()).isFalse();
    }
    assertThat(context.getBeansOfType(GrpcPeerIdentityInterceptor.class)).hasSize(1);
    assertThat(context.getBean(GrpcAuthProperties.class).getPublicMethods())
        .contains(METHOD)
        .doesNotContain("account.v1.AccountService/*");
    assertThat(context.getBeansOfType(AccountPlayerAdmissionEntitlementOwner.class)).isEmpty();
    assertThat(context.getBeansOfType(AccountPlayerAdmissionEntitlementGrpcService.class))
        .isEmpty();
    assertThat(context.getBeansOfType(AccountPlayerAdmissionEntitlementWorkloadGuard.class))
        .isEmpty();
    assertThat(context.getBeansOfType(AccountTenantCapacityRepository.class)).isEmpty();
    assertThat(context.getBeansOfType(AccountTenantCapacityOwner.class)).isEmpty();
    assertThat(context.getBeansOfType(AccountTenantCapacityGrpcService.class)).isEmpty();
    assertThat(context.getBeansOfType(AccountTenantCapacityRecoveryWorker.class)).isEmpty();
    assertThat(context.getBeansOfType(AccountTenantCapacityWorkloadGuard.class)).isEmpty();
    assertThat(dsl.fetchValue("SELECT current_schema()", String.class))
        .isEqualTo("account_service");
  }

  @ParameterizedTest
  @ValueSource(strings = {"game-session", "world"})
  void authorizedNumericRefusalAndExactRetryCreateNoCapacityRows(String identity) throws Exception {
    Map<String, TableImage> before = emptyCapacityImages();
    ManagedChannel channel = channel(identity);
    try {
      var stub = AccountServiceGrpc.newBlockingStub(channel);
      for (int attempt = 0; attempt < 2; attempt++) {
        assertRefusal(
            stub.withDeadlineAfter(5, TimeUnit.SECONDS).getTenantEntitlementsForRuntime(REQUEST),
            "ENTITLEMENT_UNAVAILABLE");
        assertThat(capacityImages()).isEqualTo(before);
      }
    } finally {
      close(channel);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"wrong-service", "cross-namespace"})
  void deniedCertificateAndSpoofedMetadataCreateNoCapacityRows(String identity) throws Exception {
    Map<String, TableImage> before = emptyCapacityImages();
    ManagedChannel channel = channel(identity);
    try {
      var stub = AccountServiceGrpc.newBlockingStub(channel);
      assertRefusal(
          stub.withDeadlineAfter(5, TimeUnit.SECONDS).getTenantEntitlementsForRuntime(REQUEST),
          "PERMISSION_DENIED");
      assertThat(capacityImages()).isEqualTo(before);
      assertRefusal(
          stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(fakePeer()))
              .withDeadlineAfter(5, TimeUnit.SECONDS)
              .getTenantEntitlementsForRuntime(REQUEST),
          "PERMISSION_DENIED");
      assertThat(capacityImages()).isEqualTo(before);
    } finally {
      close(channel);
    }
  }

  @Test
  void missingClientCertificateIsRefusedByTlsWithoutCapacityCreation() throws Exception {
    Map<String, TableImage> before = emptyCapacityImages();
    ManagedChannel channel = channel(null);
    try {
      assertThatThrownBy(
              () ->
                  AccountServiceGrpc.newBlockingStub(channel)
                      .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(fakePeer()))
                      .withDeadlineAfter(5, TimeUnit.SECONDS)
                      .getTenantEntitlementsForRuntime(REQUEST))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure -> assertThat(TlsTestSupport.isTlsHandshakeRejection(failure)).isTrue());
      assertThat(capacityImages()).isEqualTo(before);
    } finally {
      close(channel);
    }
  }

  private static void assertRefusal(GetTenantEntitlementsForRuntimeResponse response, String code) {
    assertThat(response.hasError()).isTrue();
    assertThat(response.getError().getCode()).isEqualTo(code);
    assertThat(response.toBuilder().clearError().build())
        .isEqualTo(GetTenantEntitlementsForRuntimeResponse.getDefaultInstance());
  }

  private static Metadata fakePeer() {
    Metadata headers = new Metadata();
    String claim = "spiffe://firemud/ns/test/sa/game-session-service";
    for (String name :
        List.of("x-firemud-peer-identity", "x-spiffe-id", "x-forwarded-client-cert")) {
      headers.put(Metadata.Key.of(name, Metadata.ASCII_STRING_MARSHALLER), claim);
    }
    return headers;
  }

  private ManagedChannel channel(String identity) throws Exception {
    var tls = GrpcSslContexts.forClient().trustManager(pki.trustManagerFactory());
    if (identity != null) tls.keyManager(pki.keyManagerFactory(pki.identity(identity)));
    return NettyChannelBuilder.forAddress("localhost", listener.getPort())
        .sslContext(tls.build())
        .build();
  }

  private static void close(ManagedChannel channel) throws InterruptedException {
    channel.shutdownNow();
    assertThat(channel.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
  }

  private Map<String, TableImage> emptyCapacityImages() throws Exception {
    Map<String, TableImage> images = capacityImages();
    assertThat(images).hasSize(5);
    images.forEach(
        (table, image) -> {
          assertThat(image.count()).as(table).isZero();
          assertThat(image.rows()).as(table).isEmpty();
        });
    return images;
  }

  private Map<String, TableImage> capacityImages() throws Exception {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    Map<String, TableImage> images = new LinkedHashMap<>();
    // Fresh autocommit connections observe committed postimages, never a shared test rollback.
    try (Connection connection = dataSource.getConnection()) {
      assertThat(connection.getAutoCommit()).isTrue();
      assertThat(connection.getTransactionIsolation())
          .isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
      assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
      assertThat(connection.getCatalog()).isEqualTo(postgres.getDatabaseName());
      assertThat(connection.getSchema()).isEqualTo("account_service");
      for (String table : CAPACITY_TABLES) {
        long count;
        try (var statement = connection.createStatement();
            var result = statement.executeQuery("SELECT COUNT(*) FROM account_service." + table)) {
          assertThat(result.next()).isTrue();
          count = result.getLong(1);
        }
        List<RowImage> rows = new ArrayList<>();
        try (var statement = connection.createStatement();
            var result =
                statement.executeQuery(
                    "SELECT to_jsonb(t)::text, t.xmin::text FROM account_service."
                        + table
                        + " t ORDER BY 1, 2")) {
          while (result.next()) rows.add(new RowImage(result.getString(1), result.getString(2)));
        }
        assertThat(rows).hasSize(Math.toIntExact(count));
        images.put(table, new TableImage(count, List.copyOf(rows)));
      }
    }
    return Map.copyOf(images);
  }

  @AfterAll
  static void removeOwnedTlsFixture() throws Exception {
    if (tlsDirectory != null) {
      try (var files = Files.walk(tlsDirectory)) {
        for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
      }
    }
  }

  private record RowImage(String json, String xmin) {}

  private record TableImage(long count, List<RowImage> rows) {}
}
