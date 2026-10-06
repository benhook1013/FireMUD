package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.worldmanagement.WorldManagementServiceApplication;
import net.firedevops.firemud.worldmanagement.client.EntityManagementClient;
import net.firedevops.firemud.worldmanagement.client.GameDesignClient;
import net.firedevops.firemud.worldmanagement.client.GameSessionClient;
import net.firedevops.firemud.worldmanagement.client.GrpcGameSessionInitialAdmissionBindProofClient;
import net.firedevops.firemud.worldmanagement.entity.RegionInstance;
import net.firedevops.firemud.worldmanagement.entity.WorldInstance;
import net.firedevops.firemud.worldmanagement.repository.RegionInstanceRepository;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PostgreSQL definition for World-owned operational region assignment persistence. */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@SpringBootTest(
    classes = WorldManagementServiceApplication.class,
    properties = {"spring.grpc.server.port=0", "firemud.smoke.seed-demo-runtime.enabled=false"})
class WorldOperationalRegionAssignmentPostgresIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7.2-alpine").withExposedPorts(6379);

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    PostgresBackedServiceTestSupport.registerPostgresService(
        registry, postgres, "world_management_service");
    PostgresBackedServiceTestSupport.registerRedisService(registry, redis);
  }

  @Autowired private DSLContext dsl;
  @Autowired private RegionInstanceRepository regionInstances;

  @MockitoBean private GrpcServerLifecycle grpcServerLifecycle;

  @MockitoBean(enforceOverride = true)
  private GrpcGameSessionInitialAdmissionBindProofClient bindProofClient;

  @MockitoBean private EntityManagementClient entityManagementClient;
  @MockitoBean private GameDesignClient gameDesignClient;
  @MockitoBean private GameSessionClient gameSessionClient;

  @Test
  void repositoryAllocatesFreshStableWorldIdentityAndDatabasePinsItsAssignedScope() {
    Fixture fixture = fixture();
    RegionInstance candidate = candidate(fixture, "fresh-region");

    RegionInstance inserted = regionInstances.save(candidate);
    UUID operationalId = inserted.getOperationalRegionId();
    assertThat(candidate.getOperationalRegionId()).isNull();
    assertThat(operationalId).isNotNull().isNotEqualTo(new UUID(0L, 0L));
    assertThat(inserted.getOperationalRegionId()).isEqualTo(operationalId);
    RegionInstance callerAssigned = candidate(fixture, "caller-assigned");
    callerAssigned.setOperationalRegionId(UUID.randomUUID());
    assertThatThrownBy(() -> regionInstances.save(callerAssigned))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("allocated by World");

    var stored =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT operational_region_id, canonical_region_instance_id "
                    + "FROM region_instance WHERE id = ?",
                inserted.getId()));
    assertThat(stored.get("operational_region_id", UUID.class)).isEqualTo(operationalId);
    assertThat(stored.get("canonical_region_instance_id", UUID.class)).isNull();

    inserted.setName("fresh-region-updated");
    RegionInstance updated = regionInstances.save(inserted);
    assertThat(updated.getOperationalRegionId()).isEqualTo(operationalId);

    RegionInstance changedScope = regionInstances.findById(updated.getId()).orElseThrow();
    changedScope.setTenantId(fixture.tenantId() + 1);
    assertThatThrownBy(() -> regionInstances.save(changedScope))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("assignment tuple is immutable");
    Fixture otherScope = fixture();
    for (String statement :
        new String[] {
          "UPDATE region_instance SET tenant_id = ? WHERE id = ?",
          "UPDATE region_instance SET game_instance_id = ? WHERE id = ?",
          "UPDATE region_instance SET world_instance_id = ? WHERE id = ?",
          "UPDATE region_instance SET operational_region_id = ? WHERE id = ?"
        }) {
      Object replacement =
          switch (statement) {
            case "UPDATE region_instance SET tenant_id = ? WHERE id = ?" -> otherScope.tenantId();
            case "UPDATE region_instance SET game_instance_id = ? WHERE id = ?" ->
                otherScope.gameInstanceId();
            case "UPDATE region_instance SET world_instance_id = ? WHERE id = ?" ->
                otherScope.worldInstanceId();
            default -> UUID.randomUUID();
          };
      assertThatThrownBy(() -> dsl.execute(statement, replacement, updated.getId()))
          .isInstanceOf(DataAccessException.class);
    }
  }

  @Test
  void nullAssignmentIsNotRepairedAndV45KeepsPreparationFunctionClosed() {
    Fixture fixture = fixture();
    long legacyId = insertRegionWithoutOperationalIdentity(fixture, "retained-legacy-region");

    RegionInstance retained = regionInstances.findById(legacyId).orElseThrow();
    assertThat(retained.getOperationalRegionId()).isNull();
    retained.setName("retained-legacy-region-updated");
    RegionInstance updated = regionInstances.save(retained);
    assertThat(updated.getOperationalRegionId()).isNull();
    assertThat(
            Objects.requireNonNull(
                    dsl.fetchOne(
                        "SELECT operational_region_id FROM region_instance WHERE id = ?", legacyId))
                .get("operational_region_id", UUID.class))
        .isNull();

    assertThat(
            Objects.requireNonNull(
                    dsl.fetchOne(
                        "SELECT count(*) FROM flyway_schema_history_world_management_service "
                            + "WHERE version IN ('44', '45') AND success"))
                .get(0, Long.class))
        .isEqualTo(2);
    String prepareDefinition =
        Objects.requireNonNull(
                dsl.fetchOne(
                    "SELECT pg_get_functiondef('world_management_service."
                        + "world_prepare_canonical_instance(text,text)'::regprocedure)"))
            .get(0, String.class);
    assertThat(prepareDefinition).contains("'operational_region_id', operational_runtime_uuid");
    assertThat(
            Objects.requireNonNull(
                    dsl.fetchOne(
                        "SELECT EXISTS (SELECT 1 FROM pg_proc p "
                            + "JOIN pg_namespace n ON n.oid = p.pronamespace "
                            + "CROSS JOIN LATERAL aclexplode(coalesce(p.proacl, acldefault('f', p.proowner))) acl "
                            + "WHERE n.nspname = 'world_management_service' "
                            + "AND p.proname = 'world_prepare_canonical_instance' "
                            + "AND acl.grantee = 0 AND acl.privilege_type = 'EXECUTE')"))
                .get(0, Boolean.class))
        .isFalse();
  }

  @Test
  void v44ToV45RetainsLegacyRegionAndPreparationOwnerFunctionIdentity() throws Exception {
    String schema = "retained_operational_region_" + UUID.randomUUID().toString().replace("-", "");
    try {
      Flyway.configure()
          .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
          .schemas(schema)
          .defaultSchema(schema)
          .table("flyway_schema_history_world_management_service")
          .placeholders(Map.of("serviceSchema", schema))
          .locations("classpath:db/migration")
          .target(MigrationVersion.fromVersion("44"))
          .load()
          .migrate();

      long tenantId = 760_000L + Math.abs(UUID.randomUUID().getLeastSignificantBits() % 100_000L);
      long gameInstanceId =
          761_000L + Math.abs(UUID.randomUUID().getLeastSignificantBits() % 100_000L);
      UUID canonicalRegionId = UUID.randomUUID();
      try (var connection =
          DriverManager.getConnection(
              postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
        DSLContext retained = DSL.using(connection, SQLDialect.POSTGRES);
        retained.execute("SET search_path TO " + schema + ", public");
        long worldId =
            Objects.requireNonNull(
                    retained.fetchOne(
                        "INSERT INTO world_instance (tenant_id, game_instance_id, game_template_id, "
                            + "control_plane_request_id, launch_descriptor_id, version_id, "
                            + "runtime_flags_json, generation_config_revision, release_bundle_id, "
                            + "published_release_bundle_ref, version_state_epoch, status) "
                            + "VALUES (?, ?, 71, ?, 'retained-descriptor', 83, '{}', 'retained-config', "
                            + "97, 'retained-release', 5, 'PREPARING') RETURNING id",
                        tenantId,
                        gameInstanceId,
                        "retained-region-" + UUID.randomUUID()))
                .get("id", Long.class);
        long regionId =
            Objects.requireNonNull(
                    retained.fetchOne(
                        "INSERT INTO region_instance (tenant_id, game_instance_id, world_instance_id, "
                            + "shard_id, name, weather, generation_seed, generator_type, "
                            + "generator_params, spacing_multiplier, version, canonical_region_instance_id) "
                            + "VALUES (?, ?, ?, 3, 'retained-region', 'rain', 73, 'legacy', 'kept', "
                            + "1.25, 4, ?) RETURNING id",
                        tenantId,
                        gameInstanceId,
                        worldId,
                        canonicalRegionId))
                .get("id", Long.class);
        assertThat(regionId).isPositive();
        String regionBefore =
            Objects.requireNonNull(
                    retained.fetchOne(
                        "SELECT to_jsonb(r) AS row_json FROM region_instance r WHERE id = ?",
                        regionId))
                .get("row_json", JSONB.class)
                .data();
        String worldBefore =
            Objects.requireNonNull(
                    retained.fetchOne(
                        "SELECT to_jsonb(w) AS row_json FROM world_instance w WHERE id = ?",
                        worldId))
                .get("row_json", JSONB.class)
                .data();
        var functionBefore = preparationFunction(retained, schema);
        assertThat(functionBefore.body())
            .contains(
                "Canonical preparation release differs from the exact selected frozen World graph",
                "world_require_preparation_start_location",
                "Canonical preparation requires the exact published V44 terminal");
        assertThat(
                Objects.requireNonNull(
                        retained.fetchOne(
                            "SELECT count(*) FROM information_schema.columns "
                                + "WHERE table_schema = ? AND table_name = 'region_instance' "
                                + "AND column_name = 'operational_region_id'",
                            schema))
                    .get(0, Long.class))
            .isZero();

        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .schemas(schema)
            .defaultSchema(schema)
            .table("flyway_schema_history_world_management_service")
            .placeholders(Map.of("serviceSchema", schema))
            .locations("classpath:db/migration")
            .load()
            .migrate();

        var functionAfter = preparationFunction(retained, schema);
        assertThat(functionAfter.oid()).isEqualTo(functionBefore.oid());
        assertThat(functionAfter.acl()).isEqualTo(functionBefore.acl());
        assertThat(functionAfter.body())
            .contains(
                "Canonical preparation release differs from the exact selected frozen World graph",
                "world_require_preparation_start_location",
                "Canonical preparation requires the exact published V44 terminal",
                "'operational_region_id', operational_runtime_uuid");
        assertThat(functionAfter.body()).isNotEqualTo(functionBefore.body());
        assertThat(
                Objects.requireNonNull(
                        retained.fetchOne(
                            "SELECT EXISTS (SELECT 1 FROM pg_proc p "
                                + "CROSS JOIN LATERAL aclexplode(coalesce(p.proacl, acldefault('f', p.proowner))) acl "
                                + "WHERE p.oid = to_regprocedure(? || '.world_prepare_canonical_instance(text,text)') "
                                + "AND acl.grantee = 0 AND acl.privilege_type = 'EXECUTE')",
                            schema))
                    .get(0, Boolean.class))
            .isFalse();

        var retainedRegion =
            Objects.requireNonNull(
                retained.fetchOne(
                    "SELECT (to_jsonb(r) - 'operational_region_id') = ?::jsonb AS original_row, "
                        + "operational_region_id IS NULL AS unassigned, "
                        + "canonical_region_instance_id AS canonical_id "
                        + "FROM region_instance r WHERE id = ?",
                    regionBefore,
                    regionId));
        assertThat(retainedRegion.get("original_row", Boolean.class)).isTrue();
        assertThat(retainedRegion.get("unassigned", Boolean.class)).isTrue();
        assertThat(retainedRegion.get("canonical_id", UUID.class)).isEqualTo(canonicalRegionId);
        assertThat(
                Objects.requireNonNull(
                        retained.fetchOne(
                            "SELECT to_jsonb(w) = ?::jsonb FROM world_instance w WHERE id = ?",
                            worldBefore,
                            worldId))
                    .get(0, Boolean.class))
            .isTrue();
        assertThat(
                Objects.requireNonNull(
                        retained.fetchOne(
                            "SELECT count(*) FROM flyway_schema_history_world_management_service "
                                + "WHERE version IN ('40', '42', '44', '45') AND success"))
                    .get(0, Long.class))
            .isEqualTo(4);
      }
    } finally {
      try (var connection =
          DriverManager.getConnection(
              postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
        DSL.using(connection, SQLDialect.POSTGRES).execute("DROP SCHEMA " + schema + " CASCADE");
      }
    }
  }

  private PreparationFunction preparationFunction(DSLContext dsl, String schema) {
    var function =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT p.oid::bigint AS function_oid, p.proacl::text AS function_acl, "
                    + "pg_get_functiondef(p.oid) AS function_body FROM pg_proc p "
                    + "WHERE p.oid = to_regprocedure(? || '.world_prepare_canonical_instance(text,text)')",
                schema));
    return new PreparationFunction(
        function.get("function_oid", Long.class),
        function.get("function_acl", String.class),
        function.get("function_body", String.class));
  }

  private RegionInstance candidate(Fixture fixture, String name) {
    RegionInstance region = new RegionInstance();
    region.setTenantId(fixture.tenantId());
    region.setGameInstanceId(fixture.gameInstanceId());
    WorldInstance world = new WorldInstance();
    world.setId(fixture.worldInstanceId());
    region.setWorldInstance(world);
    region.setName(name);
    return region;
  }

  private long insertRegionWithoutOperationalIdentity(Fixture fixture, String name) {
    return Objects.requireNonNull(
            dsl.fetchOne(
                "INSERT INTO region_instance "
                    + "(tenant_id, game_instance_id, world_instance_id, name) "
                    + "VALUES (?, ?, ?, ?) RETURNING id",
                fixture.tenantId(),
                fixture.gameInstanceId(),
                fixture.worldInstanceId(),
                name))
        .get("id", Long.class);
  }

  private Fixture fixture() {
    long tenantId = 740_000L + Math.abs(UUID.randomUUID().getLeastSignificantBits() % 100_000L);
    long gameInstanceId =
        741_000L + Math.abs(UUID.randomUUID().getLeastSignificantBits() % 100_000L);
    long worldInstanceId =
        Objects.requireNonNull(
                dsl.fetchOne(
                    "INSERT INTO world_instance (tenant_id, game_instance_id, game_template_id, "
                        + "control_plane_request_id, launch_descriptor_id, version_id, "
                        + "runtime_flags_json, generation_config_revision, release_bundle_id, "
                        + "published_release_bundle_ref, version_state_epoch, status) "
                        + "VALUES (?, ?, 71, ?, 'region-assignment-descriptor', 83, '{}', "
                        + "'region-assignment-config', 97, 'region-assignment-release', 5, 'PREPARING') "
                        + "RETURNING id",
                    tenantId,
                    gameInstanceId,
                    "region-assignment-" + UUID.randomUUID()))
            .get("id", Long.class);
    return new Fixture(tenantId, gameInstanceId, worldInstanceId);
  }

  private record Fixture(long tenantId, long gameInstanceId, long worldInstanceId) {}

  private record PreparationFunction(Long oid, String acl, String body) {}
}
