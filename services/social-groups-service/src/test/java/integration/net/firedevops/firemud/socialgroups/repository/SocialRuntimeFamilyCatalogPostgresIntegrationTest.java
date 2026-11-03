package integration.net.firedevops.firemud.socialgroups.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.social.SocialRuntimeFamilyCatalog;
import net.firedevops.firemud.common.social.SocialRuntimeFamilyCatalog.Evidence;
import net.firedevops.firemud.common.social.SocialRuntimeFamilyCatalog.FactKind;
import net.firedevops.firemud.common.social.SocialRuntimeFamilyCatalog.FreshDisposition;
import net.firedevops.firemud.common.social.SocialRuntimeFamilyCatalog.Request;
import net.firedevops.firemud.common.social.SocialRuntimeFamilyCatalogCodec;
import net.firedevops.firemud.socialgroups.runtimecatalog.SocialRuntimeFamilyCatalogReadGrpcService;
import net.firedevops.firemud.socialgroups.runtimecatalog.SocialRuntimeFamilyCatalogRepository;
import net.firedevops.firemud.socialgroups.v1.ReadSocialRuntimeFamilyCatalogResponse;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Actual installed Social/Saga PostgreSQL proof; caller identity is controlled, not physical mTLS.
 */
@Testcontainers
@SuppressWarnings("resource")
class SocialRuntimeFamilyCatalogPostgresIntegrationTest {
  private static final List<String> RETAINED_TABLES =
      List.of(
          "guilds",
          "guild_members",
          "guild_storage_items",
          "guild_alliances",
          "chat_messages",
          "friend_links",
          "account_friend_links",
          "mail_messages",
          "saga_instance",
          "saga_step");

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void configuredFlywayHistoryNameIsTheInstalledHistoryFamilyAndExactHistorySource()
      throws Exception {
    for (String history :
        List.of(
            "flyway_schema_history_social_groups_service", "flyway_schema_history_custom_social")) {
      try (Fixture f = fixture(null, history)) {
        var evidence = f.owner().read(request());
        assertThat(evidence.catalog().families())
            .anySatisfy(
                family -> {
                  assertThat(family.familyId()).isEqualTo(history);
                  assertThat(family.freshDisposition())
                      .isEqualTo(FreshDisposition.MIGRATION_HISTORY_ONLY);
                });
        assertThat(evidence.catalog().schemaFacts())
            .anySatisfy(
                fact -> {
                  assertThat(fact.kind()).isEqualTo(FactKind.TABLE);
                  assertThat(fact.identity()).isEqualTo(history);
                })
            .anySatisfy(
                fact -> {
                  assertThat(fact.kind()).isEqualTo(FactKind.MIGRATION);
                  assertThat(fact.identity()).isEqualTo("catalog-installation:1004");
                });
        assertThat(f.owner().readExact(evidence.request())).isEqualTo(evidence);
        assertThat(
                f.dsl
                    .fetchSingle("SELECT to_regclass(?) IS NULL", f.schema + "." + defaultHistory())
                    .get(0, Boolean.class))
            .isEqualTo(!history.equals(defaultHistory()));
        assertThat(
                f.dsl
                    .fetchSingle(
                        "SELECT " + f.schema + ".social_runtime_family_catalog_installed()")
                    .get(0, Boolean.class))
            .isTrue();
      }
    }
  }

  @Test
  void actualCombinedSocialSagaCensusClassifiesEveryInstalledTableWithoutRowInference()
      throws Exception {
    try (Fixture f = fixture(null)) {
      var catalog = f.owner().read(request()).catalog();
      assertThat(catalog.catalogEpoch()).isEqualTo(1);
      assertThat(
              catalog.schemaFacts().stream()
                  .filter(fact -> fact.kind() == FactKind.TABLE)
                  .map(SocialRuntimeFamilyCatalog.SchemaFact::identity)
                  .toList())
          .containsExactlyInAnyOrderElementsOf(
              f.dsl
                  .fetch(
                      "SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace"
                          + " WHERE n.nspname=? AND c.relkind IN ('r','p','f')",
                      f.schema)
                  .getValues(0, String.class));
      assertThat(catalog.families().stream().filter(family -> family.component().isEmpty()).count())
          .isEqualTo(
              catalog.schemaFacts().stream().filter(fact -> fact.kind() == FactKind.TABLE).count());
      assertThat(catalog.families())
          .anySatisfy(
              family -> {
                assertThat(family.familyId()).isEqualTo("account_friend_links");
                assertThat(family.freshDisposition())
                    .isEqualTo(FreshDisposition.ACCOUNT_PAIR_SCOPE_DRIFT);
              })
          .anySatisfy(
              family -> {
                assertThat(family.familyId()).isEqualTo("guild_storage_items");
                assertThat(family.freshDisposition())
                    .isEqualTo(FreshDisposition.ENTITY_VALUE_OWNERSHIP_DRIFT_BLOCKING);
                assertThat(family.targetOwner())
                    .isEqualTo(SocialRuntimeFamilyCatalog.TargetOwner.ENTITY_MANAGEMENT_SERVICE);
              })
          .anySatisfy(
              family -> {
                assertThat(family.familyId()).isEqualTo("saga_instance");
                assertThat(family.freshDisposition())
                    .isEqualTo(FreshDisposition.SAGA_ADOPTER_UNRESOLVED);
              })
          .anySatisfy(
              family -> {
                assertThat(family.familyId()).isEqualTo("chat_messages.effect_id");
                assertThat(family.role()).isEqualTo(SocialRuntimeFamilyCatalog.Role.EFFECT_REPLAY);
              });
      assertThat(catalog.families())
          .noneMatch(
              family ->
                  family.familyId().equals("guild_storage_items.item_name")
                      || family.familyId().equals("guild_storage_items.quantity"));
      assertThat(catalog.unimplementedContracts())
          .containsExactly(
              SocialRuntimeFamilyCatalog.ContractGap.CHAT_RESTRICTION_AND_REVISION,
              SocialRuntimeFamilyCatalog.ContractGap.DIRECTIONAL_BLOCK_LIFECYCLE,
              SocialRuntimeFamilyCatalog.ContractGap.ENTITY_ESCROW_REFERENCE_FAMILIES,
              SocialRuntimeFamilyCatalog.ContractGap.GAMEPLAY_WHISPER_NAMESPACE_SCOPE,
              SocialRuntimeFamilyCatalog.ContractGap.SAFETY_EVIDENCE_AND_CONTENT_FREE_RECEIPTS,
              SocialRuntimeFamilyCatalog.ContractGap.SOCIAL_PLAYER_STANDING_OWNER,
              SocialRuntimeFamilyCatalog.ContractGap.SOCIAL_SAGA_ADOPTER_CLASSIFICATION);
      assertThat(catalog.schemaFacts())
          .anyMatch(fact -> fact.kind() == FactKind.MIGRATION)
          .anyMatch(fact -> fact.kind() == FactKind.ROUTINE)
          .anyMatch(fact -> fact.kind() == FactKind.COLUMN);
      assertThat(f.count("social_runtime_family_catalog_snapshot")).isEqualTo(1);
      assertThat(
              f.dsl
                  .fetchSingle(
                      "SELECT catalog_bytes = social_runtime_family_catalog_bytes(catalog_epoch)"
                          + " FROM social_runtime_family_catalog_epoch")
                  .get(0, Boolean.class))
          .isTrue();
    }
  }

  @Test
  void exactRetriesIndependentReadbackAndConcurrentReplaysRetainOneSnapshot() throws Exception {
    try (Fixture f = fixture(null);
        var workers = Executors.newFixedThreadPool(2)) {
      Request read = request();
      var first = workers.submit(() -> f.owner().read(read));
      var second = workers.submit(() -> f.owner().read(read));
      var original = first.get(30, TimeUnit.SECONDS);
      assertThat(second.get(30, TimeUnit.SECONDS)).isEqualTo(original);
      assertThat(f.owner().read(read)).isEqualTo(original);
      assertThat(f.owner().readExact(read)).isEqualTo(original);
      assertThat(f.count("social_runtime_family_catalog_snapshot")).isEqualTo(1);
      var next = f.owner().read(request());
      assertThat(next.snapshotId()).isNotEqualTo(original.snapshotId());
      assertThat(next.catalog().exactBytes()).isEqualTo(original.catalog().exactBytes());
      assertThat(next.digest()).isNotEqualTo(original.digest());
      assertThat(f.count("social_runtime_family_catalog_snapshot")).isEqualTo(2);
      var wrongNamespace =
          new SocialRuntimeFamilyCatalogRepository(f.dsl, f.manager, f.schema, "other");
      assertThatThrownBy(() -> wrongNamespace.read(new Request(read.readRequestId(), "other")))
          .isInstanceOf(SocialRuntimeFamilyCatalogRepository.CatalogConflictException.class);
      assertThatThrownBy(() -> f.owner().readExact(request()))
          .isInstanceOf(SocialRuntimeFamilyCatalogRepository.CatalogConflictException.class);
      var transaction = new TransactionTemplate(f.manager);
      assertThatThrownBy(() -> transaction.execute(status -> f.owner().read(request())))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("independent");
      assertThat(f.count("social_runtime_family_catalog_snapshot")).isEqualTo(2);
    }
  }

  @Test
  void forwardMigrationRetainsPopulatedSocialSagaRowsAndEveryPriorHistoryRow() throws Exception {
    try (Fixture f = fixture("1003")) {
      long guild =
          f.dsl
              .fetchSingle(
                  "INSERT INTO guilds(tenant_id,name,owner_account_id) VALUES (7,'retained',?::uuid) RETURNING id",
                  "10000000-0000-4000-8000-000000000001")
              .get(0, Long.class);
      f.dsl.execute(
          "INSERT INTO guild_members(tenant_id,guild_id,account_id,role) VALUES (7,?,?::uuid,'owner')",
          guild,
          "10000000-0000-4000-8000-000000000001");
      f.dsl.execute(
          "INSERT INTO guild_storage_items(tenant_id,guild_id,item_name,quantity) VALUES (7,?,'legacy-value',4)",
          guild);
      f.dsl.execute(
          "INSERT INTO guild_alliances(tenant_id,guild_id,ally_guild_id,created_at) VALUES (7,?,9,TIMESTAMP '2026-10-01 00:00:00')",
          guild);
      f.dsl.execute(
          "INSERT INTO chat_messages(tenant_id,sender_account_id,recipient_account_id,content,effect_id)"
              + " VALUES (7,?::uuid,?::uuid,'retained chat','retained-effect')",
          "10000000-0000-4000-8000-000000000001",
          "10000000-0000-4000-8000-000000000002");
      f.dsl.execute(
          "INSERT INTO friend_links(tenant_id,account_id,friend_account_id,status) VALUES (7,?::uuid,?::uuid,'active')",
          "10000000-0000-4000-8000-000000000001",
          "10000000-0000-4000-8000-000000000002");
      f.dsl.execute(
          "INSERT INTO account_friend_links(tenant_id,account_id,friend_account_id,status) VALUES (7,?::uuid,?::uuid,'active')",
          "10000000-0000-4000-8000-000000000001",
          "10000000-0000-4000-8000-000000000002");
      f.dsl.execute(
          "INSERT INTO mail_messages(tenant_id,sender_account_id,recipient_account_id,subject,content)"
              + " VALUES (7,?::uuid,?::uuid,'retained','retained mail')",
          "10000000-0000-4000-8000-000000000001",
          "10000000-0000-4000-8000-000000000002");
      long saga =
          f.dsl
              .fetchSingle(
                  "INSERT INTO saga_instance(saga_name,state) VALUES ('retained-saga','RUNNING') RETURNING id")
              .get(0, Long.class);
      f.dsl.execute(
          "INSERT INTO saga_step(instance_id,name,status) VALUES (?, 'retained-step','PENDING')",
          saga);

      Map<String, String> rowsBefore = f.retainedRows();
      List<String> historyBefore = f.historyRows();
      migrate(f, null);
      assertThat(f.retainedRows()).isEqualTo(rowsBefore);
      assertThat(f.historyRowsExcluding("1004")).isEqualTo(historyBefore);
      var catalog = f.owner().read(request()).catalog();
      assertThat(catalog.catalogEpoch()).isEqualTo(1);
      assertThat(catalog.families())
          .anySatisfy(
              family ->
                  assertThat(family.freshDisposition())
                      .isEqualTo(FreshDisposition.ENTITY_VALUE_OWNERSHIP_DRIFT_BLOCKING));
      assertThat(f.retainedRows()).isEqualTo(rowsBefore);
      assertThat(f.count("social_runtime_family_catalog_epoch")).isEqualTo(1);
      assertThat(f.count("social_runtime_family_catalog_snapshot")).isEqualTo(1);
    }
  }

  @Test
  void emptyUnclassifiedNewTableInvalidatesExactAndNewReadsWithoutResampling() throws Exception {
    try (Fixture f = fixture(null)) {
      Request retained = request();
      f.owner().read(retained);
      f.dsl.execute("CREATE TABLE unknown_runtime_family (id BIGINT PRIMARY KEY)");
      assertThat(f.count("unknown_runtime_family")).isZero();
      assertThatThrownBy(() -> f.owner().readExact(retained)).hasMessageContaining("Unclassified");
      assertThatThrownBy(() -> f.owner().read(request())).hasMessageContaining("Unclassified");
      assertThat(f.count("social_runtime_family_catalog_epoch")).isEqualTo(1);
      assertThat(f.count("social_runtime_family_catalog_snapshot")).isEqualTo(1);
    }
  }

  @Test
  void migrationRefusesPreexistingUnclassifiedFamilyAtomicallyAndKeepsParentHistory()
      throws Exception {
    try (Fixture f = fixture("1003")) {
      f.dsl.execute("CREATE TABLE unknown_runtime_family (id BIGINT PRIMARY KEY)");
      List<String> historyBefore = f.historyRows();
      assertThatThrownBy(() -> migrate(f, null)).hasMessageContaining("Unclassified or changed");
      assertThat(f.tableAbsent("social_runtime_family_catalog_epoch")).isTrue();
      assertThat(f.tableAbsent("social_runtime_family_classification")).isTrue();
      assertThat(f.historyRows()).isEqualTo(historyBefore);
      assertThat(f.tableAbsent("unknown_runtime_family")).isFalse();
    }
  }

  @Test
  void schemaColumnConstraintTriggerRoutineAndRemovalChangesCannotResampleUnderSameEpoch()
      throws Exception {
    for (String change :
        List.of(
            "ALTER TABLE chat_messages ADD COLUMN future_state TEXT",
            "ALTER TABLE chat_messages RENAME COLUMN effect_id TO renamed_effect_id",
            "ALTER TABLE chat_messages ADD CONSTRAINT catalog_probe CHECK (id > 0)",
            "CREATE TRIGGER catalog_probe BEFORE INSERT ON guilds FOR EACH ROW EXECUTE FUNCTION social_runtime_family_immutable_probe()",
            "CREATE FUNCTION social_runtime_family_immutable_probe() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NEW; END $$",
            "CREATE FUNCTION future_social_writer() RETURNS INTEGER LANGUAGE SQL AS 'SELECT 1'")) {
      try (Fixture f = fixture(null)) {
        Request retained = request();
        f.owner().read(retained);
        if (change.startsWith("CREATE TRIGGER")) {
          f.dsl.execute(
              "CREATE FUNCTION social_runtime_family_immutable_probe() RETURNS trigger"
                  + " LANGUAGE plpgsql AS $$ BEGIN RETURN NEW; END $$");
        }
        f.dsl.execute(change);
        assertThatThrownBy(() -> f.owner().readExact(retained))
            .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> f.owner().read(request())).isInstanceOf(RuntimeException.class);
        assertThat(f.count("social_runtime_family_catalog_epoch")).isEqualTo(1);
        assertThat(f.count("social_runtime_family_catalog_snapshot")).isEqualTo(1);
      }
    }
    try (Fixture f = fixture(null)) {
      Request retained = request();
      f.owner().read(retained);
      f.dsl.execute("DROP TABLE guild_storage_items");
      assertThatThrownBy(() -> f.owner().readExact(retained))
          .hasMessageContaining("Unclassified or changed");
    }
  }

  @Test
  void changedClassificationMigrationHistoryOrUnsuccessfulInstallationRefusesOldSource()
      throws Exception {
    try (Fixture f = fixture(null)) {
      Request retained = request();
      f.owner().read(retained);
      f.dsl.execute(
          "INSERT INTO social_runtime_family_classification"
              + " (family_id,relation_name,component_name,family_role,fresh_disposition,target_owner)"
              + " VALUES ('guilds.name','guilds','name','GUILD_DEFINITION_ACL','GUILD_SUBJECT_TYPE_UNSUPPORTED','NONE')");
      assertThatThrownBy(() -> f.owner().readExact(retained))
          .isInstanceOf(SocialRuntimeFamilyCatalogRepository.CatalogConflictException.class);
      assertThatThrownBy(() -> f.owner().read(request()))
          .isInstanceOf(SocialRuntimeFamilyCatalogRepository.CatalogConflictException.class);
      assertThat(f.count("social_runtime_family_catalog_epoch")).isEqualTo(1);
      assertThat(f.count("social_runtime_family_catalog_snapshot")).isEqualTo(1);
    }
    try (Fixture f = fixture(null)) {
      Request retained = request();
      f.owner().read(retained);
      f.dsl.execute("UPDATE " + f.history + " SET checksum=checksum+1 WHERE version='1'");
      assertThatThrownBy(() -> f.owner().readExact(retained))
          .isInstanceOf(SocialRuntimeFamilyCatalogRepository.CatalogConflictException.class);
    }
    try (Fixture f = fixture(null)) {
      var retained = f.owner().read(request());
      Request proposalRequest = request();
      var proposal = new Evidence(proposalRequest, UUID.randomUUID(), retained.catalog());
      byte[] requestBytes = SocialRuntimeFamilyCatalogCodec.requestBytes(proposalRequest);
      byte[] evidenceBytes = SocialRuntimeFamilyCatalogCodec.evidenceBytes(proposal);
      f.dsl.execute("UPDATE " + f.history + " SET success=false WHERE version='1004'");
      assertThatThrownBy(() -> f.owner().read(request()))
          .hasMessageContaining("No durable Social catalog epoch");
      assertThatThrownBy(
              () ->
                  f.dsl.execute(
                      "INSERT INTO social_runtime_family_catalog_snapshot"
                          + " (read_request_id,target_namespace,request_bytes,request_digest,snapshot_id,"
                          + "catalog_epoch,catalog_digest,evidence_bytes,evidence_digest)"
                          + " VALUES (?,?,?,?,?,?,?,?,?)",
                      proposalRequest.readRequestId(),
                      proposalRequest.targetNamespace(),
                      requestBytes,
                      SocialRuntimeFamilyCatalog.sha256(requestBytes),
                      proposal.snapshotId(),
                      proposal.catalog().catalogEpoch(),
                      proposal.catalog().digest(),
                      evidenceBytes,
                      proposal.digest()))
          .hasMessageContaining("Social schema/classification changed");
      assertThat(f.count("social_runtime_family_catalog_snapshot")).isEqualTo(1);
    }
  }

  @Test
  void immutableMetadataAndSnapshotsRefuseMutationAndIncorrectEpochSeals() throws Exception {
    try (Fixture f = fixture(null)) {
      f.owner().read(request());
      for (String table :
          List.of(
              "social_runtime_family_catalog_epoch",
              "social_runtime_family_classification",
              "social_runtime_family_catalog_snapshot")) {
        assertThatThrownBy(() -> f.dsl.execute("DELETE FROM " + table))
            .hasMessageContaining("immutable");
        assertThatThrownBy(() -> f.dsl.execute("TRUNCATE " + table + " CASCADE"))
            .hasMessageContaining("immutable");
      }
      assertThatThrownBy(
              () -> f.dsl.execute("UPDATE social_runtime_family_catalog_epoch SET catalog_epoch=2"))
          .hasMessageContaining("immutable");
      assertThatThrownBy(
              () ->
                  f.dsl.execute(
                      "UPDATE social_runtime_family_classification SET fresh_disposition='CATALOG_ONLY'"
                          + " WHERE family_id='account_friend_links'"))
          .hasMessageContaining("immutable");
      assertThatThrownBy(
              () ->
                  f.dsl.execute(
                      "INSERT INTO social_runtime_family_catalog_epoch(catalog_epoch,catalog_bytes,catalog_digest)"
                          + " SELECT 2,catalog_bytes,catalog_digest FROM social_runtime_family_catalog_epoch WHERE catalog_epoch=1"))
          .hasMessageContaining("exact classified installed schema");
      assertThat(f.count("social_runtime_family_catalog_epoch")).isEqualTo(1);
    }
  }

  @Test
  void actualProducerReturnsCompleteCorrelatedEvidenceWithoutCallerContextOrRuntimeRows()
      throws Exception {
    try (Fixture f = fixture(null)) {
      Request read = request();
      var service = new SocialRuntimeFamilyCatalogReadGrpcService(f.owner(), "test");
      var collector = new Collector();
      var peer =
          GrpcPeerIdentity.parseUri("spiffe://firemud/ns/test/sa/game-session-service")
              .orElseThrow();
      SessionContext.clear();
      try {
        Context.current()
            .withValue(GrpcPeerIdentity.CONTEXT_KEY, peer)
            .run(
                () ->
                    service.readSocialRuntimeFamilyCatalog(
                        SocialRuntimeFamilyCatalogCodec.toRequest(read), collector));
      } finally {
        SessionContext.clear();
      }
      assertThat(collector.error).isNull();
      assertThat(collector.completed).isTrue();
      var evidence = SocialRuntimeFamilyCatalogCodec.fromResponse(read, collector.value);
      assertThat(f.owner().readExact(read)).isEqualTo(evidence);
      assertThat(f.count("guilds")).isZero();
      assertThat(f.count("chat_messages")).isZero();
      assertThat(f.count("mail_messages")).isZero();
      var transaction = new TransactionTemplate(f.manager);
      assertThatThrownBy(() -> transaction.execute(status -> f.owner().read(request())))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("independent");
    }
  }

  private static Request request() {
    return new Request(UUID.randomUUID(), "test");
  }

  private static String defaultHistory() {
    return "flyway_schema_history_social_groups_service";
  }

  private static Fixture fixture(String target) {
    return fixture(target, defaultHistory());
  }

  private static Fixture fixture(String target, String history) {
    String schema = "social_catalog_" + UUID.randomUUID().toString().replace("-", "");
    var source =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    source.setSchema(schema);
    var dsl = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
    try {
      Flyway flyway = flyway(source, schema, history, target);
      flyway.migrate();
      return new Fixture(schema, history, source, dsl, new DataSourceTransactionManager(source));
    } catch (RuntimeException failed) {
      dsl.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
      throw failed;
    }
  }

  private static Flyway flyway(
      DriverManagerDataSource source, String schema, String history, String target) {
    Path root = repositoryRoot();
    var configuration =
        Flyway.configure()
            .dataSource(source)
            .schemas(schema)
            .defaultSchema(schema)
            .table(history)
            .locations(
                "filesystem:"
                    + root.resolve(
                        "services/social-groups-service/src/main/resources/db/migration"),
                "filesystem:"
                    + root.resolve("services/common-saga/src/main/resources/db/migration/saga"))
            .placeholders(Map.of("serviceSchema", schema));
    if (target != null) configuration.target(target);
    return configuration.load();
  }

  private static Path repositoryRoot() {
    Path current = Path.of("").toAbsolutePath();
    while (current != null) {
      if (Files.exists(current.resolve("settings.gradle.kts"))) return current;
      current = current.getParent();
    }
    throw new IllegalStateException("Unable to locate repository filesystem migrations");
  }

  private static void migrate(Fixture f, String target) {
    flyway(f.source, f.schema, f.history, target).migrate();
  }

  private record Fixture(
      String schema,
      String history,
      DriverManagerDataSource source,
      DSLContext dsl,
      DataSourceTransactionManager manager)
      implements AutoCloseable {
    SocialRuntimeFamilyCatalogRepository owner() {
      return new SocialRuntimeFamilyCatalogRepository(dsl, manager, schema, "test");
    }

    long count(String table) {
      return dsl.fetchSingle("SELECT count(*) FROM " + table).get(0, Long.class);
    }

    boolean tableAbsent(String table) {
      return dsl.fetchSingle("SELECT to_regclass(?) IS NULL", schema + "." + table)
          .get(0, Boolean.class);
    }

    Map<String, String> retainedRows() {
      return RETAINED_TABLES.stream()
          .collect(java.util.stream.Collectors.toUnmodifiableMap(table -> table, this::jsonRows));
    }

    String jsonRows(String table) {
      return dsl.fetchSingle(
              "SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY id),'[]'::jsonb)::text FROM "
                  + table
                  + " t")
          .get(0, String.class);
    }

    List<String> historyRows() {
      return dsl.fetch("SELECT to_jsonb(h)::text FROM " + history + " h ORDER BY installed_rank")
          .getValues(0, String.class);
    }

    List<String> historyRowsExcluding(String version) {
      return dsl.fetch(
              "SELECT to_jsonb(h)::text FROM "
                  + history
                  + " h WHERE version IS DISTINCT FROM ? ORDER BY installed_rank",
              version)
          .getValues(0, String.class);
    }

    @Override
    public void close() {
      dsl.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }

  private static final class Collector
      implements StreamObserver<ReadSocialRuntimeFamilyCatalogResponse> {
    private ReadSocialRuntimeFamilyCatalogResponse value;
    private Status.Code error;
    private boolean completed;

    @Override
    public void onNext(ReadSocialRuntimeFamilyCatalogResponse response) {
      value = response;
    }

    @Override
    public void onError(Throwable failure) {
      error = Status.fromThrowable(failure).getCode();
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
