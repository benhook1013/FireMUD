package integration.net.firedevops.firemud.worldmanagement.tenant;

import static net.firedevops.firemud.worldmanagement.WorldPostgresMigrationTestSupport.flyway;
import static net.firedevops.firemud.worldmanagement.WorldPostgresMigrationTestSupport.repositoryRoot;
import static net.firedevops.firemud.worldmanagement.WorldPostgresMigrationTestSupport.sagaMigrations;
import static net.firedevops.firemud.worldmanagement.WorldPostgresMigrationTestSupport.worldMigrations;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.testing.AuthoringFixtures;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.test.TestContainerImages;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredVersionIdentityRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInitialPlayerLocationRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInitialPlayerLocationRepository.RequestSelector;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceAssociationRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceLifecycleReadRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldCompleteLaunchBindingRepository;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

/**
 * Current-schema refusal proof for the manually composed initial-location receiver. The controlled
 * lifecycle carrier below is test data only; this test has no retained canonical World association,
 * authenticated ACTIVE lifecycle transition, committed Game Session pointer terminal, or
 * authenticated Entity source. It exercises the exact-request parser and the receiver's
 * unregistered transaction path without manufacturing owner facts. Positive APPLIED, exact replay,
 * and hold-bound write proof require the protected cross-owner facts that this schema fixture
 * intentionally does not create.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class WorldCanonicalInitialPlayerLocationRepositoryPostgresIntegrationTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void receiverRejectsInvalidIntentAndLeavesUnregisteredWorldUnchanged() throws Exception {
    String schema = "world_initial_location_" + UUID.randomUUID().toString().replace("-", "");
    var source =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    source.setSchema(schema);
    var transactionalSource = new TransactionAwareDataSourceProxy(source);
    DSLContext dsl = DSL.using(transactionalSource, SQLDialect.POSTGRES);
    var transactionManager = new DataSourceTransactionManager(source);
    try {
      Flyway current =
          flyway(
              source,
              schema,
              null,
              worldMigrations(repositoryRoot()),
              sagaMigrations(repositoryRoot()));
      current.migrate();
      current.validate();
      assertThat(current.info().pending()).isEmpty();

      // Plan the actual production join without inventing an ACTIVE owner or placement. This
      // checks current-schema column references only; positive region/placement proof stays open.
      var queryField =
          WorldCanonicalInitialPlayerLocationRepository.class.getDeclaredField(
              "INITIAL_REGION_MAPPING_SQL");
      queryField.setAccessible(true);
      String regionMappingSql = java.util.Objects.requireNonNull((String) queryField.get(null));
      Object[] unboundSelectors = new Object[15];
      assertThat(dsl.fetch("EXPLAIN " + regionMappingSql, unboundSelectors)).isNotEmpty();

      var associationRepository =
          new WorldCanonicalInstanceAssociationRepository(
              dsl,
              new WorldCompleteLaunchBindingRepository(dsl),
              new WorldAuthoredSourceIntakeRepository(dsl),
              new WorldAuthoredVersionIdentityRepository(dsl));
      var lifecycleRepository =
          new WorldCanonicalInstanceLifecycleReadRepository(
              dsl, transactionManager, associationRepository);
      var repository =
          new WorldCanonicalInitialPlayerLocationRepository(
              dsl, transactionManager, lifecycleRepository);
      WorldCanonicalInstanceLifecycleEvidence lifecycle =
          AuthoringFixtures.lifecycleEvidence("ACTIVE", 3L);
      var request =
          request(
              lifecycle,
              UUID.randomUUID(),
              UUID.fromString("99999999-9999-4999-8999-999999999999"));

      long operationsBefore = rowCount(dsl, "world_canonical_initial_player_location_operation");
      long locationsBefore = rowCount(dsl, "character_location");
      var first = repository.store(request);
      var replay = repository.store(request);

      assertThat(first.outcome())
          .isEqualTo(WorldCanonicalInitialPlayerLocationRepository.Outcome.UNREGISTERED);
      assertThat(first.retained()).isFalse();
      assertThat(first.requestBytes()).isNull();
      assertThat(first.resultBytes()).isNull();
      assertThat(replay).isEqualTo(first);
      assertThat(repository.read(request)).isEmpty();
      assertThat(repository.readByOperationId(request.operationId(), selector(request, lifecycle)))
          .isEmpty();
      assertThat(repository.readInitialPlayerLocationRegion(selector(request, lifecycle)))
          .isEmpty();

      var downgradedTransactionManager =
          new ReadCommittedPhysicalRepeatableReadTransactionManager(source);
      var downgradedRepository =
          new WorldCanonicalInitialPlayerLocationRepository(
              dsl, downgradedTransactionManager, lifecycleRepository);
      assertThatThrownBy(() -> downgradedRepository.read(request))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage(
              "World initial-location readback requires a read-only repeatable-read transaction");
      assertThat(downgradedTransactionManager.repeatableReadRequested()).isTrue();

      assertThat(rowCount(dsl, "world_canonical_initial_player_location_operation"))
          .isEqualTo(operationsBefore);
      assertThat(rowCount(dsl, "character_location")).isEqualTo(locationsBefore);

      // Closed creation origin reaches the same actual writer/read/region refusal boundary.
      // This fixture supplies no authenticated Entity receipt or protected ACTIVE/hold owner.
      var createdRoot = (tools.jackson.databind.node.ObjectNode) JSON.readTree(request.requestBytes());
      createdRoot.put("schema", "world-canonical-initial-player-location-request/v2");
      createdRoot.remove("entityAssignmentOperationId");
      createdRoot.remove("entityAssignmentDigest");
      var origin = JSON.createObjectNode();
      origin.put("targetNamespace", lifecycle.request().targetNamespace());
      origin.put("creationOperationId", "12121212-1212-4212-8212-121212121212");
      origin.put("mutationDigest", "d".repeat(64));
      origin.put("terminalId", "13131313-1313-4313-8313-131313131313");
      origin.put("terminalSha256", "e".repeat(64));
      createdRoot.set("entityCreatedOrigin", origin);
      byte[] createdBytes = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(createdRoot));
      var created = new WorldCanonicalInitialPlayerLocationRepository.Request(
          request.operationId(), prefixedDigest(createdBytes), createdBytes, request.originalLifecycleEvidenceBytes());
      assertThat(repository.store(created).outcome())
          .isEqualTo(WorldCanonicalInitialPlayerLocationRepository.Outcome.UNREGISTERED);
      assertThat(repository.store(created).retained()).isFalse();
      assertThat(repository.read(created)).isEmpty();
      createdRoot.put("entityAssignmentDigest", "a".repeat(64));
      byte[] mixedBytes = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(createdRoot));
      var mixed = new WorldCanonicalInitialPlayerLocationRepository.Request(
          request.operationId(), prefixedDigest(mixedBytes), mixedBytes, request.originalLifecycleEvidenceBytes());
      assertThatThrownBy(() -> repository.store(mixed))
          .isInstanceOf(WorldCanonicalInitialPlayerLocationRepository.InvalidInitialLocationEvidenceException.class);
      assertThat(rowCount(dsl, "world_canonical_initial_player_location_operation")).isEqualTo(operationsBefore);
      assertThat(rowCount(dsl, "character_location")).isEqualTo(locationsBefore);

      var wrongOrigin = withOrigin(request, "PRIOR_POINTER");
      assertThatThrownBy(() -> repository.store(wrongOrigin))
          .isInstanceOf(
              WorldCanonicalInitialPlayerLocationRepository.InvalidInitialLocationEvidenceException
                  .class);
      assertThat(repository.read(request)).isEmpty();
      assertThat(rowCount(dsl, "world_canonical_initial_player_location_operation"))
          .isEqualTo(operationsBefore);
      assertThat(rowCount(dsl, "character_location")).isEqualTo(locationsBefore);
    } finally {
      dsl.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
    }
  }

  private static WorldCanonicalInitialPlayerLocationRepository.Request withOrigin(
      WorldCanonicalInitialPlayerLocationRepository.Request request, String origin)
      throws Exception {
    String exactRequest = new String(request.requestBytes(), StandardCharsets.UTF_8);
    byte[] changedBytes =
        exactRequest
            .replace(
                "\"initialAdmissionOrigin\":\"NO_PRIOR_POINTER\"",
                "\"initialAdmissionOrigin\":\"" + origin + "\"")
            .getBytes(StandardCharsets.UTF_8);
    if (java.util.Arrays.equals(changedBytes, request.requestBytes())) {
      throw new IllegalStateException("Initial-admission origin was not present in request bytes");
    }
    return new WorldCanonicalInitialPlayerLocationRepository.Request(
        request.operationId(),
        prefixedDigest(changedBytes),
        changedBytes,
        request.originalLifecycleEvidenceBytes());
  }

  private static WorldCanonicalInitialPlayerLocationRepository.Request request(
      WorldCanonicalInstanceLifecycleEvidence lifecycle, UUID operationId, UUID characterId)
      throws Exception {
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("schema", "world-canonical-initial-player-location-request/v1");
    request.put("operationId", operationId.toString());
    request.put("canonicalTenantId", lifecycle.request().canonicalTenantId().toString());
    request.put(
        "playableStateNamespaceId", lifecycle.request().playableStateNamespaceId().toString());
    request.put(
        "canonicalGameInstanceId", lifecycle.request().canonicalGameInstanceId().toString());
    request.put("canonicalAccountId", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    request.put("characterId", characterId.toString());
    request.put("entityAssignmentOperationId", "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    request.put("entityAssignmentDigest", "a".repeat(64));
    request.put("realmId", "cccccccc-cccc-4ccc-8ccc-cccccccccccc");
    request.put("worldSlug", lifecycle.request().worldSlug());
    request.put("playableStateScope", lifecycle.request().playableStateScope());
    request.put("initialAdmissionHoldId", "dddddddd-dddd-4ddd-8ddd-dddddddddddd");
    request.put("initialAdmissionHoldFence", "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee");
    request.put("initialAdmissionOrigin", "NO_PRIOR_POINTER");
    request.put("initialAdmissionRequestId", "controlled-admission-17");
    request.put("initialAdmissionRequestDigest", "b".repeat(64));
    request.put("catalogRevision", "17");
    request.put("initialAdmissionOwnerProofId", "controlled-admission-17");
    request.put("initialAdmissionOwnerProofDigest", "c".repeat(64));
    request.put("pointerAuditId", "controlled-pointer-audit-17");
    request.put("pointerVersion", "1");
    request.put("activeLifecycleEvidence", JSON.readTree(lifecycle.canonicalBytes()));
    byte[] requestBytes = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(request));
    return new WorldCanonicalInitialPlayerLocationRepository.Request(
        operationId, prefixedDigest(requestBytes), requestBytes, lifecycle.canonicalBytes());
  }

  private static RequestSelector selector(
      WorldCanonicalInitialPlayerLocationRepository.Request request,
      WorldCanonicalInstanceLifecycleEvidence lifecycle) {
    return new RequestSelector(
        request.operationId(),
        lifecycle.request().canonicalTenantId(),
        lifecycle.request().playableStateNamespaceId(),
        lifecycle.request().canonicalGameInstanceId(),
        UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
        UUID.fromString("99999999-9999-4999-8999-999999999999"),
        UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
        "a".repeat(64),
        UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
        lifecycle.request().worldSlug(),
        lifecycle.request().playableStateScope(),
        lifecycle.request().canonicalVersionId(),
        UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd"),
        UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
        "controlled-admission-17",
        "b".repeat(64),
        lifecycle.lifecycleEpoch(),
        17L,
        "controlled-admission-17",
        "c".repeat(64),
        "controlled-pointer-audit-17",
        1L,
        null);
  }

  private static long rowCount(DSLContext dsl, String table) {
    return dsl.fetchSingle("SELECT count(*) FROM " + DSL.name(table)).get(0, Long.class);
  }

  private static final class ReadCommittedPhysicalRepeatableReadTransactionManager
      extends DataSourceTransactionManager {
    private static final long serialVersionUID = 1L;
    private boolean repeatableReadRequested;

    private ReadCommittedPhysicalRepeatableReadTransactionManager(DriverManagerDataSource source) {
      super(source);
    }

    @Override
    protected void prepareTransactionalConnection(
        Connection connection, TransactionDefinition definition) throws SQLException {
      super.prepareTransactionalConnection(connection, definition);
      if (definition.isReadOnly()
          && definition.getIsolationLevel() == TransactionDefinition.ISOLATION_REPEATABLE_READ) {
        repeatableReadRequested = true;
        try (Statement statement = connection.createStatement()) {
          statement.execute("SET TRANSACTION ISOLATION LEVEL READ COMMITTED");
        }
      }
    }

    private boolean repeatableReadRequested() {
      return repeatableReadRequested;
    }
  }

  private static String prefixedDigest(byte[] bytes) throws Exception {
    return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }
}
