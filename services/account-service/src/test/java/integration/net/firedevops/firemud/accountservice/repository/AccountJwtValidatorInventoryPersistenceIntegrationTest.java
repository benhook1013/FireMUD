package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.Binding;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.CustodyMode;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.TrustFence;
import net.firedevops.firemud.accountservice.repository.AccountJwtValidatorInventoryRepository;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource.InventorySnapshot;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;

/** Persistence-component proof only; fixture bytes are not evidence of a live Kubernetes read. */
class AccountJwtValidatorInventoryPersistenceIntegrationTest {
  private static final String SCHEMA_PREFIX = "jwt_inv_snapshot_";
  private static final AccountPostgresIntegrationFixture POSTGRES =
      new AccountPostgresIntegrationFixture();
  private static final Binding BINDING =
      new Binding(
          "prod",
          "prod-cluster-1",
          "firemud-prod",
          CustodyMode.INTERIM_ACCOUNT_ONLY_MOUNTED_FALLBACK);
  private static final String CLUSTER_UID = "11111111-1111-4111-8111-111111111111";
  private static final String NAMESPACE_UID = "22222222-2222-4222-8222-222222222222";
  private final Set<String> schemas = ConcurrentHashMap.newKeySet();

  @BeforeAll
  static void startPostgres() {
    POSTGRES.start();
  }

  @AfterAll
  static void stopPostgres() {
    POSTGRES.stop();
  }

  @AfterEach
  void dropRunOwnedSchemas() {
    JdbcTemplate jdbc = new JdbcTemplate(POSTGRES.dataSource());
    for (String schema : schemas) {
      if (!schema.startsWith(SCHEMA_PREFIX) || !schema.matches("[a-z][a-z0-9_]{0,62}")) {
        throw new IllegalStateException("Refusing to clean an unowned PostgreSQL schema");
      }
      jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
    schemas.clear();
  }

  @Test
  void exactDigestRetryReadsBackImmutableCanonicalBytesAndRejectsMutation() throws Exception {
    TestContext context = newTestContext();
    AccountJwtValidatorInventoryRepository repository =
        new AccountJwtValidatorInventoryRepository(context.dsl());
    InventorySnapshot candidate = snapshot();

    var first =
        inTransaction(context, () -> repository.persistOrReadback(candidate, BINDING, trust()));
    var retry =
        inTransaction(context, () -> repository.persistOrReadback(candidate, BINDING, trust()));

    assertThat(retry).isEqualTo(first);
    assertThat(
            context
                .dsl()
                .resultQuery("SELECT COUNT(*) FROM account_jwt_validator_inventory_snapshots")
                .fetchOne(0, Long.class))
        .isEqualTo(1L);
    assertThat(retry.canonicalBytes()).isEqualTo(candidate.canonicalBytes());
    assertThat(new String(retry.canonicalBytes(), StandardCharsets.UTF_8))
        .doesNotContain("privateKey");
    assertThatThrownBy(
            () ->
                context
                    .dsl()
                    .execute(
                        "UPDATE account_jwt_validator_inventory_snapshots "
                            + "SET canonical_snapshot = ? WHERE snapshot_digest = ?",
                        "{}".getBytes(StandardCharsets.UTF_8),
                        retry.digest()))
        .isInstanceOf(DataAccessException.class);

    long plansBeforeTruncate = count(context, "account_jwt_readiness_probe_plans");
    assertThatThrownBy(
            () ->
                context
                    .dsl()
                    .execute("TRUNCATE TABLE account_jwt_validator_inventory_snapshots CASCADE"))
        .isInstanceOf(DataAccessException.class);
    assertThat(count(context, "account_jwt_validator_inventory_snapshots")).isEqualTo(1L);
    assertThat(count(context, "account_jwt_readiness_probe_plans")).isEqualTo(plansBeforeTruncate);
  }

  @Test
  void differentBytesCannotReuseAStoredDigestAndWrongOwnerIdentityIsDenied() throws Exception {
    TestContext context = newTestContext();
    AccountJwtValidatorInventoryRepository repository =
        new AccountJwtValidatorInventoryRepository(context.dsl());
    InventorySnapshot candidate = snapshot();
    inTransaction(context, () -> repository.persistOrReadback(candidate, BINDING, trust()));
    Instant observedAt = candidate.observedAt();
    String environmentId = candidate.environmentId();
    String namespace = candidate.namespace();
    String apiBindingRevision = candidate.apiBindingRevision();
    String apiBindingDigest = candidate.apiBindingDigest();
    String inventoryBindingRevision = candidate.inventoryBindingRevision();
    String inventoryBindingDigest = candidate.inventoryBindingDigest();
    String digest = candidate.digest();
    byte[] canonicalBytes = candidate.canonicalBytes();

    InventorySnapshot wrongIdentity = mock(InventorySnapshot.class);
    when(wrongIdentity.observedAt()).thenReturn(observedAt);
    when(wrongIdentity.environmentId()).thenReturn(environmentId);
    when(wrongIdentity.clusterId()).thenReturn("other-cluster");
    when(wrongIdentity.clusterIncarnationUid()).thenReturn(CLUSTER_UID);
    when(wrongIdentity.namespace()).thenReturn(namespace);
    when(wrongIdentity.namespaceUid()).thenReturn(NAMESPACE_UID);
    when(wrongIdentity.apiBindingRevision()).thenReturn(apiBindingRevision);
    when(wrongIdentity.apiBindingDigest()).thenReturn(apiBindingDigest);
    when(wrongIdentity.inventoryBindingRevision()).thenReturn(inventoryBindingRevision);
    when(wrongIdentity.inventoryBindingDigest()).thenReturn(inventoryBindingDigest);
    when(wrongIdentity.digest()).thenReturn(digest);
    when(wrongIdentity.canonicalBytes()).thenReturn(canonicalBytes);

    assertThatThrownBy(
            () ->
                inTransaction(
                    context, () -> repository.persistOrReadback(wrongIdentity, BINDING, trust())))
        .isInstanceOf(
            AccountJwtValidatorInventoryRepository.InventorySnapshotUnavailableException.class)
        .hasNoCause();
  }

  private static InventorySnapshot snapshot() throws Exception {
    byte[] bytes =
        ("{\"domain\":\"firemud-account-validator-inventory/v1\","
                + "\"environmentId\":\"prod\",\"clusterId\":\"prod-cluster-1\","
                + "\"clusterIncarnationUid\":\""
                + CLUSTER_UID
                + "\","
                + "\"namespace\":\"firemud-prod\",\"namespaceUid\":\""
                + NAMESPACE_UID
                + "\"}")
            .getBytes(StandardCharsets.UTF_8);
    InventorySnapshot snapshot = mock(InventorySnapshot.class);
    when(snapshot.observedAt()).thenReturn(Instant.parse("2026-10-05T00:00:00Z"));
    when(snapshot.environmentId()).thenReturn("prod");
    when(snapshot.clusterId()).thenReturn("prod-cluster-1");
    when(snapshot.clusterIncarnationUid()).thenReturn(CLUSTER_UID);
    when(snapshot.namespace()).thenReturn("firemud-prod");
    when(snapshot.namespaceUid()).thenReturn(NAMESPACE_UID);
    when(snapshot.apiBindingRevision()).thenReturn("api-r1");
    when(snapshot.apiBindingDigest()).thenReturn("a".repeat(64));
    when(snapshot.inventoryBindingRevision()).thenReturn("inventory-r1");
    when(snapshot.inventoryBindingDigest()).thenReturn("b".repeat(64));
    when(snapshot.canonicalBytes()).thenReturn(bytes);
    when(snapshot.digest()).thenReturn(sha256(bytes));
    return snapshot;
  }

  private TestContext newTestContext() {
    String schema = SCHEMA_PREFIX + UUID.randomUUID().toString().replace("-", "");
    schemas.add(schema);
    var dataSource = POSTGRES.dataSource(schema);
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .load()
        .migrate();
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    return new TestContext(
        new TransactionTemplate(new DataSourceTransactionManager(dataSource)), dsl);
  }

  private static <T> T inTransaction(TestContext context, java.util.function.Supplier<T> action) {
    return context.transaction().execute(status -> action.get());
  }

  private static long count(TestContext context, String table) {
    return java.util.Objects.requireNonNull(
        context.dsl().resultQuery("SELECT COUNT(*) FROM " + table).fetchOne(0, Long.class),
        "COUNT query must return a scalar row");
  }

  private static TrustFence trust() {
    return new TrustFence(CLUSTER_UID, NAMESPACE_UID, "c".repeat(64), "trust-r1");
  }

  private static String sha256(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private record TestContext(TransactionTemplate transaction, DSLContext dsl) {}
}
