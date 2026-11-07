package net.firedevops.firemud.worldmanagement.tenant;

import static net.firedevops.firemud.worldmanagement.WorldPostgresMigrationTestSupport.migration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.gamedesign.RealmPolicyPublishedEvidence;
import net.firedevops.firemud.common.gamedesign.RealmPolicySource;
import net.firedevops.firemud.common.gamesession.CurrentPrivatePlaytestLifecycleReadEvidence.Scope;
import net.firedevops.firemud.common.gamesession.PrivatePreparationProtectionGrpcCodec.Held;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationBuildGrpcCodec.Build;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadEvidence;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadEvidence.Evidence;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadEvidence.Preparation;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadEvidence.Selection;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadEvidence.Snapshot;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadEvidence.Source;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadEvidence.Target;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadGrpcCodec.Request;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.common.world.WorldRuntimeFamilyCatalog;
import net.firedevops.firemud.test.TestContainerImages;
import net.firedevops.firemud.worldmanagement.tenant.WorldPrivateFreshOperationRepository.HeldPermission;
import net.firedevops.firemud.worldmanagement.tenant.WorldPrivateFreshOperationRepository.HeldSourceProtection;
import net.firedevops.firemud.worldmanagement.tenant.WorldSelectedPublicationPostgresIntegrationTest.PrivateFreshPublished;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Actual SQL materialization from the existing APPLIED/frozen/PUBLISHED World owner chain. Game
 * Session source facts, pending-source lifetime, actor-operation permission and installation
 * custody are independent controlled fixtures, not production producers. No owner manifest or
 * admission is asserted. No source settlement/cleanup or credential-lifetime behavior is qualified
 * here.
 */
@Testcontainers(disabledWithoutDocker = false)
@SuppressWarnings("resource")
class WorldPrivateFreshOperationPostgresIntegrationTest {
  private static final String NS = "firemud";
  private static final String DIGEST = "sha256:" + "a".repeat(64);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void actualMaterializationExactReplayIndependentTerminalRetainClosedPreparingState()
      throws Exception {
    try (Fixture f = fixture(null)) {
      var prepared = f.prepare();
      var owner = f.owner();
      var inputJson = JSON.readTree(prepared.inputJson());
      assertThat(inputJson.get("gameSessionIntakeOperationId").asText())
          .isNotEqualTo(inputJson.get("worldIntakeOperationId").asText());
      assertThat(inputJson.get("gameSessionIntakeReceiptDigest").asText())
          .isNotEqualTo(inputJson.get("worldIntakeReceiptDigest").asText());
      var terminal = owner.materialize(prepared);
      assertThat(terminal.outcome()).isEqualTo("APPLIED");
      assertThat(terminal.admissionAllowed()).isFalse();
      assertThat(prepared.admissionAllowed()).isFalse();
      var originalRows = f.rows();
      assertThat(owner.materialize(prepared)).isEqualTo(terminal);
      var substituted = (ObjectNode) JSON.readTree(prepared.inputJson());
      substituted.put("policySetDigest", "sha256:" + "b".repeat(64));
      String changedJson = JSON.writeValueAsString(substituted);
      byte[] originalOperation =
          f.dsl
              .fetchSingle("SELECT original_operation_bytes FROM world_private_fresh_operation")
              .get(0, byte[].class);
      assertThatThrownBy(
              () ->
                  f.dsl.fetchValue(
                      "SELECT world_private_fresh_intent(?,?,?)",
                      changedJson,
                      hash(changedJson.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                      originalOperation))
          .hasMessageContaining("different exact original inputs");
      assertThatThrownBy(
              () ->
                  f.dsl.fetchValue(
                      "SELECT world_private_fresh_intent(?,?,?)",
                      prepared.inputJson(),
                      prepared.inputDigest(),
                      "substituted original operation"
                          .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
          .hasMessageContaining("different exact original inputs");
      assertThat(f.rows()).isEqualTo(originalRows);
      assertThat(f.owner().read(prepared.operationId())).contains(terminal);
      assertThat(f.count("world_private_fresh_operation")).isEqualTo(1);
      assertThat(f.count("world_private_fresh_terminal")).isEqualTo(1);
      assertThat(f.count("world_private_instance_association")).isEqualTo(1);
      assertThat(f.count("world_canonical_instance_association")).isZero();
      assertThat(f.count("world_canonical_instance_preparation")).isZero();
      assertThat(f.count("world_canonical_instance_execution")).isZero();
      assertThat(f.count("world_private_fresh_execution_manifest")).isZero();
      assertThat(f.count("region_instance")).isEqualTo(1);
      assertThat(f.count("zone_instance")).isEqualTo(1);
      assertThat(f.count("room_instance")).isEqualTo(2);
      assertThat(f.count("room_instance_exit")).isEqualTo(1);
      assertThat(f.count("world_canonical_instance_topology_identity")).isEqualTo(5);
      var world = f.dsl.fetchSingle("SELECT * FROM world_instance");
      assertThat(world.get("canonical_game_instance_id", UUID.class))
          .isEqualTo(prepared.gameInstanceId());
      assertThat(world.get("private_fresh_operation_id", UUID.class))
          .isEqualTo(prepared.operationId());
      assertThat(world.get("status", String.class)).isEqualTo("PREPARING");
      assertThat(world.get("lifecycle_epoch", Long.class)).isEqualTo(1);
      assertThat(world.get("playtest_state_generation", Long.class)).isEqualTo(1);
      assertThat(world.get("playable_state_scope", String.class)).isEqualTo("ISOLATED");
      assertThat(world.get("public_production", Boolean.class)).isFalse();
      assertThat(world.get("canonical_launch_binding_operation_id")).isNull();
      assertThat(world.get("launch_descriptor_id")).isNull();
      assertThat(world.get("game_template_id")).isNull();
      assertThat(world.get("release_bundle_id")).isNull();
      assertThat(
              f.dsl
                  .fetchSingle(
                      "SELECT graph_bytes=(SELECT graph_bytes FROM world_canonical_frozen_topology f WHERE f.capture_id=o.capture_id) FROM world_private_fresh_operation o")
                  .get(0, Boolean.class))
          .isTrue();
      assertThat(
              f.dsl
                  .fetchSingle(
                      "SELECT count(*) FROM world_canonical_instance_topology_identity t JOIN world_private_instance_association a ON a.operation_id=t.private_fresh_operation_id AND a.world_instance_id=t.world_instance_id")
                  .get(0, Long.class))
          .isEqualTo(5);
      assertThatThrownBy(() -> owner.abort(prepared))
          .hasMessageContaining("different terminal outcome");
      assertThatThrownBy(
              () ->
                  f.dsl.execute(
                      "UPDATE world_instance SET status='ACTIVE',lifecycle_epoch=2,row_version=1"))
          .isInstanceOf(RuntimeException.class);
      assertThatThrownBy(() -> f.dsl.execute("UPDATE room_instance SET name='foreign'"))
          .hasMessageContaining("no exact transaction execution manifest");
      assertThatThrownBy(() -> f.dsl.execute("DELETE FROM world_private_fresh_terminal"))
          .hasMessageContaining("immutable");
      assertThatThrownBy(() -> f.dsl.execute("TRUNCATE world_private_fresh_operation CASCADE"))
          .isInstanceOf(RuntimeException.class);
      assertThat(f.rows()).isEqualTo(originalRows);
      assertThat(f.count("character_location")).isZero();
      assertThat(f.count("world_event")).isZero();
    }
  }

  @Test
  void actualPublishedSpawnIntentCannotBecomeEmptyFreshSuccess() throws Exception {
    try (Fixture f = fixture(null)) {
      assertThatThrownBy(() -> f.prepare(true))
          .hasMessageContaining("generation and spawn intent are unsupported");
      assertThat(f.count("world_private_fresh_operation")).isZero();
      assertThat(f.count("world_private_fresh_terminal")).isZero();
      assertThat(f.count("world_instance")).isZero();
      assertThat(f.count("world_entity_spawn_binding")).isEqualTo(1);
    }
  }

  @Test
  void abortIsAppendOnlyExactReplayAndCannotBecomeMaterialized() throws Exception {
    try (Fixture f = fixture("1049")) {
      var input = f.prepare();
      var aborted = f.owner().abort(input);
      var rows = f.rows();
      assertThat(aborted.outcome()).isEqualTo("NOT_APPLIED");
      assertThat(aborted.worldInstanceId()).isNull();
      assertThat(f.owner().abort(input)).isEqualTo(aborted);
      assertThat(f.owner().read(input.operationId())).contains(aborted);
      assertThatThrownBy(() -> f.owner().materialize(input))
          .hasMessageContaining("different terminal outcome");
      assertThat(f.rows()).isEqualTo(rows);
      assertThat(f.count("world_private_fresh_operation")).isEqualTo(1);
      assertThat(f.count("world_instance")).isZero();
      assertThat(f.count("world_canonical_instance_topology_identity")).isZero();
      var history =
          f.dsl
              .fetch(
                  "SELECT to_jsonb(h)::TEXT||':'||xmin::TEXT value FROM flyway_schema_history_world_management_service h ORDER BY installed_rank")
              .getValues(0, String.class);
      var epochs =
          f.dsl
              .fetch(
                  "SELECT to_jsonb(e)::TEXT||':'||xmin::TEXT value FROM world_runtime_family_catalog_epoch e ORDER BY catalog_epoch")
              .getValues(0, String.class);
      var snapshots =
          f.dsl
              .fetch(
                  "SELECT to_jsonb(s)::TEXT||':'||xmin::TEXT value FROM world_runtime_family_catalog_snapshot s ORDER BY read_request_id")
              .getValues(0, String.class);
      migration(f.dataSource, f.schema, null).migrate();
      assertThat(f.rows()).isEqualTo(rows);
      assertThat(
              f.dsl
                  .fetch(
                      "SELECT to_jsonb(h)::TEXT||':'||xmin::TEXT value FROM flyway_schema_history_world_management_service h WHERE version IS DISTINCT FROM '1050' ORDER BY installed_rank")
                  .getValues(0, String.class))
          .isEqualTo(history);
      assertThat(
              f.dsl
                  .fetch(
                      "SELECT to_jsonb(e)::TEXT||':'||xmin::TEXT value FROM world_runtime_family_catalog_epoch e WHERE catalog_epoch<=4 ORDER BY catalog_epoch")
                  .getValues(0, String.class))
          .isEqualTo(epochs);
      assertThat(
              f.dsl
                  .fetch(
                      "SELECT to_jsonb(s)::TEXT||':'||xmin::TEXT value FROM world_runtime_family_catalog_snapshot s ORDER BY read_request_id")
                  .getValues(0, String.class))
          .isEqualTo(snapshots);
      assertThat(f.owner().read(input.operationId())).contains(aborted);
      // Re-register the controlled installation against the new current catalog before a new
      // operation. The retained original remains NOT_APPLIED; it is never repurposed.
      f.artifact = artifact(f.dsl);
      var fresh = f.prepare();
      var applied = f.owner().materialize(fresh);
      assertThat(applied.outcome()).isEqualTo("APPLIED");
      assertThat(f.owner().read(fresh.operationId())).contains(applied);
      assertThat(f.owner().read(input.operationId())).contains(aborted);
      assertThat(f.rows()).containsAll(rows);
      assertThat(f.count("world_instance")).isEqualTo(1);
      migration(f.dataSource, f.schema, null).validate();
    }
  }

  @Test
  void defaultPermissionStaleSourceAndChangedInstallationRefuseWithoutRuntimeEffects()
      throws Exception {
    try (Fixture f = fixture(null)) {
      var input = f.prepare();
      var defaultOwner = new WorldPrivateFreshOperationRepository(f.dsl, f.manager, f.schema, NS);
      assertThatThrownBy(() -> defaultOwner.materialize(input))
          .hasMessageContaining("permission is unavailable");
      f.sourceCurrent.set(false);
      assertThatThrownBy(() -> f.owner().materialize(input))
          .hasMessageContaining("source expired or changed");
      f.sourceCurrent.set(true);
      assertThat(f.count("world_private_fresh_operation")).isZero();
      assertThat(f.count("world_instance")).isZero();
      f.artifact = artifact(f.dsl);
      f.registry().register();
      assertThatThrownBy(() -> f.owner().materialize(input))
          .hasMessageContaining("complete current frozen installation");
      assertThat(f.count("world_private_fresh_operation")).isZero();
      assertThat(f.count("world_instance")).isZero();
    }
  }

  @Test
  void lossOfSourceAfterEffectsRollsBackMaterializationButRetainsExactIntentForRetry()
      throws Exception {
    try (Fixture f = fixture(null)) {
      var input = f.prepare();
      // Checks 1..5 surround intent and materialization entry. Check 6 follows actual SQL effects.
      f.failSourceAt.set(6);
      assertThatThrownBy(() -> f.owner().materialize(input))
          .hasMessageContaining("source expired or changed");
      assertThat(f.count("world_private_fresh_operation")).isEqualTo(1);
      assertThat(f.count("world_private_fresh_terminal")).isZero();
      assertThat(f.count("world_instance")).isZero();
      assertThat(f.count("room_instance")).isZero();
      assertThat(f.count("world_private_fresh_execution_manifest")).isZero();
      f.failSourceAt.set(-1);
      assertThat(f.owner().materialize(input).outcome()).isEqualTo("APPLIED");
    }
  }

  @Test
  void sourceLossAfterCommitRetainsReadableTerminalWithoutCompensationOrRenewal() throws Exception {
    try (Fixture f = fixture(null)) {
      var input = f.prepare();
      f.failSourceAt.set(7);
      assertThatThrownBy(() -> f.owner().materialize(input))
          .hasMessageContaining("source expired or changed");
      f.sourceCurrent.set(false);
      var retained =
          new WorldPrivateFreshOperationRepository(f.dsl, f.manager, f.schema, NS)
              .read(input.operationId())
              .orElseThrow();
      assertThat(retained.outcome()).isEqualTo("APPLIED");
      assertThat(retained.admissionAllowed()).isFalse();
      assertThat(f.count("world_instance")).isEqualTo(1);
      assertThat(f.count("room_instance")).isEqualTo(2);
      assertThat(f.count("world_private_fresh_terminal")).isEqualTo(1);
    }
  }

  @Test
  void concurrentExactOperationsConvergeOnOneTerminalAndPhysicalGraph() throws Exception {
    try (Fixture f = fixture(null);
        var workers = Executors.newFixedThreadPool(2)) {
      var input = f.prepare();
      var first = workers.submit(() -> f.owner().materialize(input));
      var second = workers.submit(() -> f.owner().materialize(input));
      assertThat(first.get(30, TimeUnit.SECONDS)).isEqualTo(second.get(30, TimeUnit.SECONDS));
      assertThat(f.count("world_instance")).isEqualTo(1);
      assertThat(f.count("room_instance")).isEqualTo(2);
      assertThat(f.count("world_private_fresh_terminal")).isEqualTo(1);
    }
  }

  @Test
  void expiredSourceAndPublicShapeCannotEnterPrivateOwner() {
    try (Fixture f = fixture(null)) {
      ObjectNode input = JSON.createObjectNode();
      input.put("schema", "world-private-fresh-input/v1");
      input.put("stateScope", "ISOLATED");
      input.put("publicProduction", false);
      input.put("generation", "1");
      input.put("targetNamespace", NS);
      input.put("worldSlug", "world");
      input.put("realmSlug", "private");
      input.put("catalogRevision", "1");
      input.put("protectionFence", "1");
      input.put("forkExpiresAt", Instant.now().minusSeconds(1).toString());
      input.put("regionCount", 1);
      input.put("zoneCount", 1);
      input.put("roomCount", 1);
      input.put("exitCount", 0);
      assertThatThrownBy(
              () ->
                  f.dsl.fetchValue(
                      "SELECT world_private_fresh_assert_inputs(?::JSONB)",
                      JSON.writeValueAsString(input)))
          .hasMessageContaining("nonpublic generation-one FRESH target required");
      input.put("forkExpiresAt", Instant.now().plusSeconds(600).toString());
      input.put("publicProduction", true);
      assertThatThrownBy(
              () ->
                  f.dsl.fetchValue(
                      "SELECT world_private_fresh_assert_inputs(?::JSONB)",
                      JSON.writeValueAsString(input)))
          .hasMessageContaining("nonpublic generation-one FRESH target required");
      assertThat(f.count("world_instance")).isZero();
    }
  }

  @Test
  void forwardUpgradeRetainsCatalogHistoryAndAddsLivePrivateFamiliesWithoutPayload() {
    try (Fixture f = fixture("1048")) {
      var oldOwner = new WorldRuntimeFamilyCatalogRepository(f.dsl, f.manager, f.schema, NS);
      var oldSnapshot = oldOwner.read(new WorldRuntimeFamilyCatalog.Request(UUID.randomUUID(), NS));
      var old =
          f.dsl
              .fetch(
                  "SELECT to_jsonb(e)::TEXT||':'||xmin::TEXT value FROM world_runtime_family_catalog_epoch e ORDER BY catalog_epoch")
              .getValues(0, String.class);
      var snapshots =
          f.dsl
              .fetch(
                  "SELECT to_jsonb(s)::TEXT||':'||xmin::TEXT value FROM world_runtime_family_catalog_snapshot s ORDER BY read_request_id")
              .getValues(0, String.class);
      var history =
          f.dsl
              .fetch(
                  "SELECT to_jsonb(h)::TEXT||':'||xmin::TEXT value FROM flyway_schema_history_world_management_service h ORDER BY installed_rank")
              .getValues(0, String.class);
      migration(f.dataSource, f.schema, null).migrate();
      assertThat(
              f.dsl
                  .fetch(
                      "SELECT to_jsonb(e)::TEXT||':'||xmin::TEXT value FROM world_runtime_family_catalog_epoch e WHERE catalog_epoch<=3 ORDER BY catalog_epoch")
                  .getValues(0, String.class))
          .isEqualTo(old);
      assertThat(
              f.dsl
                  .fetch(
                      "SELECT to_jsonb(s)::TEXT||':'||xmin::TEXT value FROM world_runtime_family_catalog_snapshot s ORDER BY read_request_id")
                  .getValues(0, String.class))
          .isEqualTo(snapshots);
      assertThat(
              f.dsl
                  .fetch(
                      "SELECT to_jsonb(h)::TEXT||':'||xmin::TEXT value FROM flyway_schema_history_world_management_service h WHERE version IS DISTINCT FROM '1049' AND version IS DISTINCT FROM '1050' ORDER BY installed_rank")
                  .getValues(0, String.class))
          .isEqualTo(history);
      assertThatThrownBy(() -> oldOwner.readExact(oldSnapshot.request()))
          .isInstanceOf(WorldRuntimeFamilyCatalogRepository.CatalogConflictException.class);
      var catalog =
          new WorldRuntimeFamilyCatalogRepository(f.dsl, f.manager, f.schema, NS)
              .read(new WorldRuntimeFamilyCatalog.Request(UUID.randomUUID(), NS))
              .catalog();
      assertThat(catalog.catalogEpoch()).isEqualTo(5);
      assertThat(catalog.families())
          .extracting(WorldRuntimeFamilyCatalog.Family::familyId)
          .contains(
              "world_private_fresh_operation",
              "world_private_instance_association",
              "world_private_fresh_terminal",
              "world_private_fresh_execution_manifest");
      assertThat(
              f.dsl
                  .fetchSingle(
                      "SELECT catalog_bytes=world_runtime_family_catalog_bytes(catalog_epoch) FROM world_runtime_family_catalog_epoch WHERE catalog_epoch=5")
                  .get(0, Boolean.class))
          .isTrue();
      assertThat(f.count("world_private_fresh_operation")).isZero();
      assertThat(f.count("world_instance")).isZero();
    }
  }

  private static Snapshot source(PrivateFreshPublished published) {
    var intake = published.intake();
    var scope = new Scope(NS, intake.canonicalTenantId(), intake.worldSlug(), "private");
    var policyJson = JSON.createObjectNode();
    policyJson.put("schemaVersion", 1);
    policyJson.put("worldSlug", intake.worldSlug());
    policyJson.put("worldDisplayName", "Controlled world");
    policyJson.put("realmSlug", "private");
    policyJson.put("realmDisplayName", "Private");
    policyJson.put("visible", true);
    policyJson.put("publicProduction", false);
    policyJson.put("stateScope", "ISOLATED");
    policyJson.put("entryPolicy", "PRESEEDED_ONLY");
    var policy = RealmEntryPolicy.parse(JSON.writeValueAsString(policyJson), JSON);
    var source =
        new Source(
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            DIGEST,
            UUID.randomUUID(),
            DIGEST,
            intake.sourceOperationId(),
            intake.sourceEvidenceDigest(),
            published.frozen().sourceIdentity().canonicalVersionId(),
            published.terminal().publishedReleaseBundleRef(),
            published.terminal().publishedReleaseBundleDigest(),
            DIGEST,
            DIGEST,
            new RealmPolicyPublishedEvidence.Policy(
                UUID.randomUUID(),
                new RealmPolicySource.Policy(
                    UUID.randomUUID(), UUID.randomUUID(), "controlled-private-policy", policy),
                DIGEST));
    Instant now = Instant.now();
    UUID request = UUID.randomUUID(), lifecycle = UUID.randomUUID(), namespace = UUID.randomUUID();
    Instant expires = now.plusSeconds(600);
    byte[] original =
        frames(
            "game-session-initial-fresh-playtest-request/v1",
            request.toString(),
            NS,
            scope.tenantId().toString(),
            scope.worldSlug(),
            scope.realmSlug(),
            source.catalogRealmId().toString(),
            source.catalogRequestId().toString(),
            "1",
            source.intakeOperationId().toString(),
            source.canonicalVersionId().toString(),
            source.publishedReleaseBundleRef(),
            source.publishedReleaseBundleDigest(),
            "FRESH",
            expires.toString());
    var preparation =
        new Preparation(
            request,
            lifecycle,
            namespace,
            1,
            "PREPARING",
            "FRESH",
            hash(original),
            now.minusSeconds(3),
            expires,
            ByteString.copyFrom(original));
    UUID selectedId = UUID.randomUUID();
    byte[] selected =
        frames(
            "game-session-current-private-playtest-selection/v1",
            selectedId.toString(),
            NS,
            scope.tenantId().toString(),
            scope.worldSlug(),
            scope.realmSlug(),
            request.toString(),
            lifecycle.toString(),
            "ABSENT");
    var selection =
        new Selection(
            selectedId,
            null,
            null,
            1,
            hash(selected),
            now.minusSeconds(2),
            ByteString.copyFrom(selected));
    var target =
        new Target(
            scope,
            UUID.randomUUID(),
            "Private",
            false,
            false,
            "ISOLATED",
            namespace,
            "PRESEEDED_ONLY",
            1,
            UUID.randomUUID(),
            DIGEST,
            DIGEST,
            request,
            lifecycle,
            1,
            "PREPARING",
            source.catalogRealmId(),
            source.targetPolicy().policyId(),
            DIGEST,
            source.canonicalVersionId(),
            source.publishedReleaseBundleRef(),
            source.publishedReleaseBundleDigest());
    return new Snapshot(
        scope,
        selection,
        preparation,
        source,
        target,
        PrivateWorldPreparationReadEvidence.runtimeAllocation(
            scope, selection, preparation, target, UUID.randomUUID(), UUID.randomUUID()),
        now.minusSeconds(1));
  }

  private static byte[] frames(String... values) {
    var out = new ByteArrayOutputStream();
    for (String value : values) DraftAuthorizationFenceBinding.frame(out, value);
    return out.toByteArray();
  }

  private static String hash(byte[] bytes) {
    return PrivateWorldPreparationReadEvidence.sha256(bytes);
  }

  private static byte[] artifact(DSLContext dsl) {
    ObjectNode root = JSON.createObjectNode();
    root.put("schema", "firemud-runtime-installation-inventory/v1");
    root.put("artifactId", UUID.randomUUID().toString());
    root.put("installationId", UUID.randomUUID().toString());
    root.put("environment", "staging");
    root.put("generation", "controlled-private-qualification");
    root.put("clusterUid", "controlled-cluster");
    root.put("namespaceUid", "controlled-namespace");
    root.put("namespace", NS);
    root.put("sourceCommit", "b".repeat(40));
    root.put("renderedManifestDigest", DIGEST);
    root.put("declarationsDigest", DIGEST);
    var executor = root.putObject("executor");
    executor.put("identity", "controlled-private-qualification/v1");
    executor.put("versionDigest", DIGEST);
    var inputs = root.putObject("inputBindings");
    for (String key :
        List.of(
            "migrations",
            "configurations",
            "mountedResources",
            "secretContracts",
            "externalBindings")) inputs.putArray(key);
    var member = root.putArray("members").addObject();
    member.put("workloadApiVersion", "v1");
    member.put("workloadKind", "Pod");
    member.put("workloadNamespace", NS);
    member.put("workloadName", "controlled-world");
    member.put("containerKind", "APPLICATION");
    member.put("containerName", "world");
    member.put("image", "registry.example/controlled/world@" + DIGEST);
    member.put("ownerId", "world-management-service");
    member.put("ownership", "RUNTIME_OWNER");
    var current =
        dsl.fetchSingle(
            "SELECT catalog_epoch,catalog_digest,catalog_bytes FROM world_runtime_family_catalog_epoch ORDER BY catalog_epoch DESC LIMIT 1");
    var catalogSource = member.putArray("catalogSources").addObject();
    catalogSource.put("sourceId", "world-sql");
    catalogSource.put("contract", "world-runtime-family-catalog/v1");
    catalogSource.put("epoch", current.get("catalog_epoch", Long.class).toString());
    catalogSource.put("digest", current.get("catalog_digest", String.class));
    var families = member.putArray("families");
    for (var family : JSON.readTree(current.get("catalog_bytes", byte[].class)).get("families")) {
      if (!"S3".equals(family.get("stateClass").asText())) continue;
      var installed = families.addObject();
      installed.put("familyId", family.get("familyId").asText());
      installed.put("catalogSourceId", "world-sql");
      installed.put("replacementClass", "S3");
      var cleanup = installed.putObject("cleanupContract");
      for (String key : List.of("operation", "requestIdentity", "acknowledgement", "retention"))
        cleanup.put(key, "controlled-world/" + key + "/v1");
    }
    return JSON.writeValueAsBytes(root);
  }

  private static Fixture fixture(String target) {
    String schema = "world_private_" + UUID.randomUUID().toString().replace("-", "");
    var source =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    source.setSchema(schema);
    var dsl = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
    try {
      migration(source, schema, target).migrate();
      return new Fixture(schema, source, dsl, new DataSourceTransactionManager(source));
    } catch (RuntimeException failure) {
      dsl.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
      throw failure;
    }
  }

  private static final class Fixture implements AutoCloseable {
    final String schema;
    final DriverManagerDataSource dataSource;
    final DSLContext dsl;
    final DataSourceTransactionManager manager;
    final AtomicBoolean sourceCurrent = new AtomicBoolean(true);
    final AtomicInteger sourceChecks = new AtomicInteger();
    final AtomicInteger failSourceAt = new AtomicInteger(-1);
    byte[] artifact;

    Fixture(
        String schema,
        DriverManagerDataSource dataSource,
        DSLContext dsl,
        DataSourceTransactionManager manager) {
      this.schema = schema;
      this.dataSource = dataSource;
      this.dsl = dsl;
      this.manager = manager;
    }

    WorldRuntimeInstallationRegistry registry() {
      if (artifact == null) artifact = artifact(dsl);
      return new WorldRuntimeInstallationRegistry(
          dsl,
          manager,
          schema,
          NS,
          () -> {
            byte[] held = artifact.clone();
            return new WorldRuntimeInstallationRegistry.HeldInstallation() {
              public byte[] exactArtifactBytes() {
                return held.clone();
              }

              public void requireCurrent() {
                if (!java.util.Arrays.equals(held, artifact))
                  throw new IllegalStateException("Controlled installation changed");
              }

              public void close() {}
            };
          });
    }

    WorldPrivateFreshOperation prepare() throws Exception {
      return prepare(false);
    }

    WorldPrivateFreshOperation prepare(boolean includeSpawnBinding) throws Exception {
      var published =
          WorldSelectedPublicationPostgresIntegrationTest.privateFreshPublished(
              dsl, manager, includeSpawnBinding);
      var facts = source(published);
      var evidence =
          new Evidence(
              new Request(UUID.randomUUID(), facts.scope(), facts.preparation().requestId()),
              facts);
      var build = mock(WorldPrivatePreparationBuildAssembly.Inputs.class);
      when(build.committedReadback())
          .thenReturn(new Build(evidence, published.terminal().worldEvidence()));
      when(build.topology())
          .thenReturn(WorldCanonicalInstanceTopologyPlan.create(published.frozen()));
      var protection = mock(WorldPrivatePreparationProtectionClient.Verified.class);
      when(protection.currentRead()).thenReturn(true);
      when(protection.evidence())
          .thenReturn(new Held(UUID.randomUUID(), UUID.randomUUID(), 1, evidence));
      var registry = registry();
      var registration = registry.register();
      return registry.withLaunchOwners(
          facts.runtimeAllocation().gameInstanceId(),
          () ->
              WorldPrivateFreshOperation.create(
                  UUID.randomUUID(), build, protection, registration));
    }

    WorldPrivateFreshOperationRepository owner() {
      return new WorldPrivateFreshOperationRepository(
          dsl,
          manager,
          schema,
          NS,
          input ->
              new HeldPermission() {
                public UUID operationId() {
                  return input.operationId();
                }

                public String inputDigest() {
                  return input.inputDigest();
                }

                public ByteString originalOperation() {
                  return ByteString.copyFromUtf8(
                      "controlled original actor operation/"
                          + input.operationId()
                          + "/"
                          + input.inputDigest());
                }

                public void requireHeld() {}

                public void close() {}
              },
          input ->
              new HeldSourceProtection() {
                public WorldPrivateFreshOperation.SourceProtectionBinding binding() {
                  return input.protection();
                }

                public void requireHeld() {
                  if (!sourceCurrent.get() || sourceChecks.incrementAndGet() == failSourceAt.get())
                    throw new IllegalStateException("Controlled pending source expired or changed");
                }

                public void close() {}
              });
    }

    long count(String table) {
      return dsl.fetchSingle("SELECT count(*) FROM " + table).get(0, Long.class);
    }

    List<String> rows() {
      return dsl.fetch(
              "SELECT value FROM (SELECT 'operation:'||to_jsonb(o)::TEXT||':'||xmin::TEXT value FROM world_private_fresh_operation o"
                  + " UNION ALL SELECT 'terminal:'||to_jsonb(t)::TEXT||':'||xmin::TEXT FROM world_private_fresh_terminal t"
                  + " UNION ALL SELECT 'world:'||to_jsonb(w)::TEXT||':'||xmin::TEXT FROM world_instance w"
                  + " UNION ALL SELECT 'association:'||to_jsonb(a)::TEXT||':'||xmin::TEXT FROM world_private_instance_association a"
                  + " UNION ALL SELECT 'topology:'||to_jsonb(i)::TEXT||':'||xmin::TEXT FROM world_canonical_instance_topology_identity i) s ORDER BY value")
          .getValues(0, String.class);
    }

    public void close() {
      dsl.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }
}
