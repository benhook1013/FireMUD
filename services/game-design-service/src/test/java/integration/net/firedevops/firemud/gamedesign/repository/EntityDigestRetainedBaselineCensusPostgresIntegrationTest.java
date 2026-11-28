package integration.net.firedevops.firemud.gamedesign.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.gamedesign.draft.IsolatedPublicationOwnerSetup;
import net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.PublishedReleaseBundle;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.publication.GameDesignPublicationOperation;
import net.firedevops.firemud.gamedesign.repository.EntityDigestRetainedBaselineCensusRepository;
import net.firedevops.firemud.gamedesign.repository.EntityDigestRetainedBaselineCensusRepository.RetainedBaselineFinding;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.PublishedReleaseBundleRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.service.impl.PublishedWorldSelectorFixtures;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.DataSourceConnectionProvider;
import org.jooq.impl.DefaultConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jooq.autoconfigure.SpringTransactionProvider;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Actual Game Design migrations with controlled retained publication rows. */
class EntityDigestRetainedBaselineCensusPostgresIntegrationTest {
  private static final GameDesignPostgresIntegrationFixture POSTGRES =
      new GameDesignPostgresIntegrationFixture();
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String ENTITY = "ENTITY_MANAGEMENT";
  private static final String DIGEST = "a".repeat(64);
  private static final String MANIFEST = "sha256:" + "b".repeat(64);
  private static final LocalDateTime RECORDED = LocalDateTime.parse("2025-01-02T03:04:05.123456");
  private static final LocalDateTime VERIFIED = RECORDED.plusSeconds(1);
  private static final List<String> EVIDENCE_TABLES =
      List.of(
          "game",
          "version",
          "publish_recorded_participant_digest",
          "publish_attempt",
          "publish_attempt_participant_digest",
          "published_release_bundle",
          "version_asset_artifact",
          "game_design_authored_draft_publish_selection",
          "game_design_publication_operation");

  @BeforeAll
  static void startPostgres() {
    POSTGRES.start();
  }

  @AfterAll
  static void stopPostgres() {
    POSTGRES.stop();
  }

  @Test
  void pagesExactFrozenOrdinaryFindingsForOneTenantWithoutChangingPhysicalRows() {
    Fixture fixture = fixture("38");
    Game owner = game(fixture, "401");
    Game foreign = game(fixture, "402");
    Version published = version(fixture, owner, 1, VersionLifecycleState.PUBLISHED);
    Version foreignPublished = version(fixture, foreign, 1, VersionLifecycleState.PUBLISHED);
    Version retired = version(fixture, owner, 2, VersionLifecycleState.RETIRED);
    var first = retainOrdinary(fixture, published, 1, participants(published, 1, DIGEST), DIGEST);
    var foreignFinding =
        retainOrdinary(
            fixture, foreignPublished, 1, participants(foreignPublished, 1, DIGEST), DIGEST);
    var second = retainOrdinary(fixture, retired, 1, participants(retired, 1, DIGEST), DIGEST);
    fixture.migrate(null);
    var before = physicalRows(fixture);

    assertThat(fixture.read(owner.getCanonicalTenantId(), 0, 1)).containsExactly(first);
    assertThat(fixture.read(owner.getCanonicalTenantId(), first.baselineId(), 1))
        .containsExactly(second);
    assertThat(fixture.read(owner.getCanonicalTenantId(), second.baselineId(), 1)).isEmpty();
    assertThat(fixture.read(owner.getCanonicalTenantId(), 0, 500)).containsExactly(first, second);
    assertThat(fixture.read(foreign.getCanonicalTenantId(), 0, 500))
        .containsExactly(foreignFinding);
    assertThat(fixture.read(UUID.randomUUID(), 0, 500)).isEmpty();
    assertThat(physicalRows(fixture)).isEqualTo(before);
  }

  @Test
  void skipsDraftSchemaTwoMalformedAmbiguousAndWrongOriginRowsInTheSamePage() {
    Fixture fixture = fixture(null);
    Game owner = game(fixture, "403");
    Game foreign = game(fixture, "404");
    Version draft = version(fixture, owner, 1, VersionLifecycleState.DRAFT);
    retainOrdinary(fixture, draft, 1, participants(draft, 1, DIGEST), DIGEST);
    Version schemaTwo = version(fixture, owner, 2, VersionLifecycleState.PUBLISHED);
    retainOrdinary(fixture, schemaTwo, 2, participants(schemaTwo, 2, DIGEST), DIGEST);
    Version malformed = version(fixture, owner, 3, VersionLifecycleState.PUBLISHED);
    retainOrdinary(fixture, malformed, 1, "[", DIGEST);
    Version ambiguous = version(fixture, owner, 4, VersionLifecycleState.PUBLISHED);
    var ambiguousEvidence =
        retainOrdinary(fixture, ambiguous, 1, participants(ambiguous, 1, DIGEST), DIGEST);
    insertBaseline(
        fixture.dsl(),
        owner.getTenantId(),
        Long.toString(ambiguous.getId()),
        "ambiguous-history",
        DIGEST,
        2,
        ambiguousEvidence.recordedFromPublishWorkflowId());
    Version wrongOrigin = version(fixture, owner, 5, VersionLifecycleState.PUBLISHED);
    var wrongOriginEvidence =
        retainOrdinary(fixture, wrongOrigin, 1, participants(wrongOrigin, 1, DIGEST), DIGEST);
    fixture
        .dsl()
        .execute(
            "UPDATE publish_recorded_participant_digest SET tenant_id = ? WHERE id = ?",
            foreign.getTenantId(),
            wrongOriginEvidence.baselineId());
    Version duplicateEvidence = version(fixture, owner, 6, VersionLifecycleState.PUBLISHED);
    var duplicate =
        retainOrdinary(
            fixture, duplicateEvidence, 1, participants(duplicateEvidence, 1, DIGEST), DIGEST);
    insertObservation(
        fixture.dsl(),
        duplicate.publishAttemptId(),
        Long.toString(duplicateEvidence.getId()),
        duplicate.appliedCommitId(),
        DIGEST,
        1);
    Version mismatched = version(fixture, owner, 7, VersionLifecycleState.PUBLISHED);
    retainOrdinary(fixture, mismatched, 1, participants(mismatched, 1, "c".repeat(64)), DIGEST);
    Version malformedScope = version(fixture, owner, 8, VersionLifecycleState.PUBLISHED);
    var leadingZero =
        retainOrdinary(fixture, malformedScope, 1, participants(malformedScope, 1, DIGEST), DIGEST);
    fixture
        .dsl()
        .execute(
            "UPDATE publish_recorded_participant_digest SET scope_value = ? WHERE id = ?",
            "0" + malformedScope.getId(),
            leadingZero.baselineId());
    Version duplicateJsonKey = version(fixture, owner, 9, VersionLifecycleState.PUBLISHED);
    retainOrdinary(
        fixture,
        duplicateJsonKey,
        1,
        participants(duplicateJsonKey, 1, DIGEST)
            .replace(
                "\"digestSchemaVersion\":1", "\"digestSchemaVersion\":2,\"digestSchemaVersion\":1"),
        DIGEST);
    Version failed = version(fixture, owner, 10, VersionLifecycleState.FAILED);
    retainOrdinary(fixture, failed, 1, participants(failed, 1, DIGEST), DIGEST);
    Version valid = version(fixture, owner, 11, VersionLifecycleState.PUBLISHED);
    var expected = retainOrdinary(fixture, valid, 1, participants(valid, 1, DIGEST), DIGEST);
    var before = physicalRows(fixture);

    assertThat(fixture.read(owner.getCanonicalTenantId(), 0, 1)).containsExactly(expected);
    assertThat(fixture.read(foreign.getCanonicalTenantId(), 0, 500)).isEmpty();
    assertThat(physicalRows(fixture)).isEqualTo(before);
  }

  @Test
  void excludesActualSelectedFullSchemaThreeAndPreIdentityV26History() {
    Fixture fixture = fixture("26");
    DSLContext dsl = fixture.dsl();
    dsl.execute("INSERT INTO game (tenant_id, name) VALUES ('405', 'Retained V26 owner')");
    long legacyVersion =
        insertedId(
            dsl,
            "INSERT INTO version (tenant_id, version_number, version_state, version_state_epoch) "
                + "VALUES ('405', 1, 'PUBLISHED', 2) RETURNING id");
    String legacyWorkflow = "publish:405:publish-request:legacy";
    dsl.execute(
        """
        INSERT INTO published_release_bundle (
          tenant_id, version_id, version_number, attestation_schema_version, publish_workflow_id,
          manifest_hash, generation_config_revision, required_manifest_asset_keys_json,
          participant_digests_json
        ) VALUES ('405', ?, 1, 'v1', ?, ?, 'retained-revision', '[]', ?)
        """,
        legacyVersion,
        legacyWorkflow,
        MANIFEST,
        participants(legacyVersion, 1, DIGEST));
    String legacyScope = Long.toString(legacyVersion);
    long legacyAttempt = insertAttempt(dsl, "405", legacyVersion, 1, legacyWorkflow, null);
    insertObservation(dsl, legacyAttempt, legacyScope, "version:" + legacyScope, DIGEST, 1);
    insertBaseline(dsl, "405", legacyScope, "version:" + legacyScope, DIGEST, 1, legacyWorkflow);
    insertArtifact(dsl, "405", legacyVersion, 1, legacyWorkflow);
    fixture.migrate(null);

    UUID canonicalRetainedTenant =
        dsl.fetchSingle("SELECT canonical_tenant_id FROM game WHERE tenant_id = '405'")
            .get(0, UUID.class);
    assertThat(
            dsl.fetchSingle("SELECT canonical_version_id FROM version WHERE id = ?", legacyVersion)
                .get(0))
        .isNull();
    Game selectedOwner = game(fixture, "406");
    Version draft = version(fixture, selectedOwner, 1, VersionLifecycleState.DRAFT);
    var operation = retainSelectedFull(fixture, draft);
    var selectedParticipants =
        PublishedWorldSelectorFixtures.participants(draft.getId(), operation.world(), 4);
    var entity =
        selectedParticipants.stream()
            .filter(participant -> ENTITY.equals(participant.participantKey()))
            .findFirst()
            .orElseThrow();
    assertThat(entity.digestSchemaVersion()).isEqualTo(3);
    PublishedReleaseBundle release =
        fixture.inTransaction(
            () ->
                IsolatedPublicationOwnerSetup.commitStorage(
                    dsl,
                    new VersionRepository(dsl),
                    operation,
                    () -> {
                      PublishedReleaseBundle candidate =
                          bundle(
                              draft,
                              operation.workflowId(),
                              JSON.writeValueAsString(selectedParticipants));
                      candidate.setAttestationSchemaVersion("v4");
                      candidate.setManifestSchemaVersion(1);
                      candidate.setArtifactDigestsJson("[]");
                      candidate.setWorldPublishedStartLocationEvidenceJson(
                          new String(operation.world().canonicalBytes(), StandardCharsets.UTF_8));
                      return new PublishedReleaseBundleRepository(dsl).save(candidate);
                    }));
    assertThat(release.getAttestationSchemaVersion()).isEqualTo("v4");
    insertBaseline(
        dsl,
        selectedOwner.getTenantId(),
        entity.scopeValue(),
        entity.appliedCommitId(),
        entity.contentDigest(),
        3,
        operation.workflowId());
    insertBaseline(
        dsl,
        selectedOwner.getTenantId(),
        entity.scopeValue(),
        "version:" + draft.getId(),
        DIGEST,
        1,
        operation.workflowId());
    var before = physicalRows(fixture);

    assertThat(fixture.read(canonicalRetainedTenant, 0, 500)).isEmpty();
    assertThat(fixture.read(selectedOwner.getCanonicalTenantId(), 0, 500)).isEmpty();
    assertThat(physicalRows(fixture)).isEqualTo(before);
  }

  @Test
  void rejectsInvalidTenantCursorAndPageSizeBeforeReading() {
    Fixture fixture = fixture(null);
    UUID tenant = UUID.randomUUID();

    assertThatThrownBy(() -> fixture.read(null, 0, 1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> fixture.read(new UUID(0, 0), 0, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> fixture.read(tenant, -1, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> fixture.read(tenant, 0, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> fixture.read(tenant, 0, 501))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static Game game(Fixture fixture, String tenantKey) {
    Game game = new Game();
    game.setTenantId(tenantKey);
    game.setName("Census owner " + tenantKey);
    return fixture.inTransaction(() -> new GameRepository(fixture.dsl()).save(game));
  }

  private static Version version(
      Fixture fixture, Game owner, int versionNumber, VersionLifecycleState state) {
    Version version = new Version();
    version.setTenantId(owner.getTenantId());
    version.setVersionNumber(versionNumber);
    version.setVersionState(state);
    version.setVersionStateEpoch(state == VersionLifecycleState.DRAFT ? 1L : 2L);
    return fixture.inTransaction(() -> new VersionRepository(fixture.dsl()).save(version));
  }

  private static RetainedBaselineFinding retainOrdinary(
      Fixture fixture, Version version, int schema, String participants, String digest) {
    DSLContext dsl = fixture.dsl();
    String tenant = version.getTenantId();
    String scope = Long.toString(version.getId());
    String commit = "version:" + scope;
    var binding = PublicationDigestRequestBinding.full(tenant, scope, "census-" + scope);
    String workflow = binding.derivedWorkflowIdentity();
    long attempt =
        insertAttempt(
            dsl,
            tenant,
            version.getId(),
            version.getVersionNumber(),
            workflow,
            binding.requestDigest());
    long observation = insertObservation(dsl, attempt, scope, commit, digest, schema);
    long baseline = insertBaseline(dsl, tenant, scope, commit, digest, schema, workflow);
    long release =
        insertedId(
            dsl,
            """
        INSERT INTO published_release_bundle (
          tenant_id, version_id, version_number, canonical_tenant_id, canonical_version_id,
          published_release_bundle_ref, attestation_schema_version, publish_workflow_id,
          manifest_hash, generation_config_revision, required_manifest_asset_keys_json,
          participant_digests_json
        ) VALUES (?, ?, ?, ?, ?, ?, 'v1', ?, ?, 'retained-revision', '[]', ?) RETURNING id
        """,
            tenant,
            version.getId(),
            version.getVersionNumber(),
            version.getCanonicalTenantId(),
            version.getCanonicalVersionId(),
            UUID.randomUUID().toString(),
            workflow,
            MANIFEST,
            participants);
    long artifact =
        insertArtifact(dsl, tenant, version.getId(), version.getVersionNumber(), workflow);
    return new RetainedBaselineFinding(
        baseline,
        version.getCanonicalTenantId(),
        tenant,
        version.getIdentitySourceGameRowId(),
        version.getIdentitySourceProvenanceKind(),
        version.getId(),
        version.getCanonicalVersionId(),
        version.getVersionNumber(),
        version.getVersionState(),
        version.getVersionStateEpoch(),
        commit,
        digest,
        schema,
        workflow,
        RECORDED,
        workflow,
        VERIFIED,
        attempt,
        observation,
        release,
        artifact);
  }

  private static String participants(Version version, int schema, String digest) {
    return participants(version.getId(), schema, digest);
  }

  private static String participants(long versionId, int schema, String digest) {
    String scope = Long.toString(versionId);
    return JSON.writeValueAsString(
        List.of(
            new PublishParticipantDigestDto(
                ENTITY, scope, "version:" + scope, digest, schema, null, null)));
  }

  private static PublishedReleaseBundle bundle(
      Version version, String workflow, String participants) {
    var bundle = new PublishedReleaseBundle();
    bundle.setTenantId(version.getTenantId());
    bundle.setVersionId(version.getId());
    bundle.setVersionNumber(version.getVersionNumber());
    bundle.setPublishWorkflowId(workflow);
    bundle.setManifestHash(MANIFEST);
    bundle.setGenerationConfigRevision("retained-revision");
    bundle.setRequiredManifestAssetKeysJson("[]");
    bundle.setParticipantDigestsJson(participants);
    return bundle;
  }

  private static GameDesignPublicationOperation retainSelectedFull(Fixture fixture, Version draft) {
    var target =
        new TargetProof(
            draft.getCanonicalTenantId(),
            draft.getCanonicalVersionId(),
            draft.getId(),
            draft.getTenantId(),
            draft.getIdentitySourceGameRowId(),
            draft.getIdentitySourceGameTenantKey(),
            draft.getIdentitySourceProvenanceKind());
    return fixture.inTransaction(
        () -> {
          try {
            return IsolatedPublicationOwnerSetup.retainFrozenSourceBackedSelectedFull(
                fixture.dsl(),
                target,
                draft.getVersionStateEpoch(),
                "Census selected control",
                List.of());
          } catch (Exception failure) {
            throw new IllegalStateException(failure);
          }
        });
  }

  private static long insertAttempt(
      DSLContext dsl,
      String tenant,
      long version,
      int number,
      String workflow,
      String requestDigest) {
    return insertedId(
        dsl,
        """
        INSERT INTO publish_attempt (
          tenant_id, publish_workflow_id, publish_type, status, version_id, version_number,
          request_digest, completed_at
        ) VALUES (?, ?, 'FULL_VERSION', 'SUCCEEDED', ?, ?, ?, CURRENT_TIMESTAMP) RETURNING id
        """,
        tenant,
        workflow,
        version,
        number,
        requestDigest);
  }

  private static long insertObservation(
      DSLContext dsl, long attempt, String scope, String commit, String digest, int schema) {
    return insertedId(
        dsl,
        """
        INSERT INTO publish_attempt_participant_digest (
          publish_attempt_id, participant_key, scope_value, applied_commit_id, content_digest,
          digest_schema_version
        ) VALUES (?, 'ENTITY_MANAGEMENT', ?, ?, ?, ?) RETURNING id
        """,
        attempt,
        scope,
        commit,
        digest,
        schema);
  }

  private static long insertBaseline(
      DSLContext dsl,
      String tenant,
      String scope,
      String commit,
      String digest,
      int schema,
      String workflow) {
    return insertedId(
        dsl,
        """
        INSERT INTO publish_recorded_participant_digest (
          tenant_id, publish_type, participant_key, scope_value, applied_commit_id, content_digest,
          digest_schema_version, recorded_from_publish_workflow_id, recorded_at,
          last_verified_publish_workflow_id, last_verified_at
        ) VALUES (?, 'FULL_VERSION', 'ENTITY_MANAGEMENT', ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
        """,
        tenant,
        scope,
        commit,
        digest,
        schema,
        workflow,
        RECORDED,
        workflow,
        VERIFIED);
  }

  private static long insertArtifact(
      DSLContext dsl, String tenant, long version, int number, String workflow) {
    return insertedId(
        dsl,
        """
        INSERT INTO version_asset_artifact (
          tenant_id, version_id, artifact_state, state_epoch, exported_version_number,
          manifest_hash, last_workflow_id
        ) VALUES (?, ?, 'PUBLISHED', 1, ?, ?, ?) RETURNING id
        """,
        tenant,
        version,
        number,
        MANIFEST,
        workflow);
  }

  private static long insertedId(DSLContext dsl, String sql, Object... arguments) {
    return dsl.resultQuery(sql, arguments).fetchSingle().get(0, Long.class);
  }

  private static List<List<String>> physicalRows(Fixture fixture) {
    return EVIDENCE_TABLES.stream()
        .map(
            table ->
                fixture
                    .dsl()
                    .fetch(
                        "SELECT row_to_json(r)::text || ':' || r.xmin::text FROM "
                            + table
                            + " r ORDER BY row_to_json(r)::text")
                    .getValues(0, String.class))
        .toList();
  }

  private static Fixture fixture(String target) {
    var source = POSTGRES.dataSource();
    source.setSchema("entity_census_" + UUID.randomUUID().toString().replace("-", ""));
    var transactionManager = new DataSourceTransactionManager(source);
    var transactions = new TransactionTemplate(transactionManager);
    transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    var jooq = new DefaultConfiguration();
    jooq.set(SQLDialect.POSTGRES);
    jooq.set(new DataSourceConnectionProvider(new TransactionAwareDataSourceProxy(source)));
    jooq.set(new SpringTransactionProvider(transactionManager));
    var fixture = new Fixture(source, DSL.using(jooq), transactions);
    fixture.migrate(target);
    return fixture;
  }

  private record Fixture(
      DriverManagerDataSource source, DSLContext dsl, TransactionTemplate transactions) {
    void migrate(String target) {
      var configuration =
          Flyway.configure()
              .dataSource(source)
              .schemas(source.getSchema())
              .defaultSchema(source.getSchema())
              .table("flyway_schema_history_game_design_service")
              .placeholders(Map.of("serviceSchema", source.getSchema()))
              .locations(
                  "filesystem:"
                      + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize());
      if (target != null) {
        configuration.target(target);
      }
      configuration.load().migrate();
    }

    <T> T inTransaction(Supplier<T> work) {
      return transactions.execute(status -> work.get());
    }

    List<RetainedBaselineFinding> read(UUID tenant, long cursor, int limit) {
      return inTransaction(
          () -> {
            dsl.execute("SET TRANSACTION READ ONLY");
            return new EntityDigestRetainedBaselineCensusRepository(dsl)
                .findPage(tenant, cursor, limit);
          });
    }
  }
}
