package integration.net.firedevops.firemud.gamedesign.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.config.PostgresProperties;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository;
import net.firedevops.firemud.gamedesign.draft.IsolatedPublicationOwnerSetup;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.PublishedReleaseBundle;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.publication.GameDesignPublicationOperation;
import net.firedevops.firemud.gamedesign.publication.GameDesignPublicationOperationRepository;
import net.firedevops.firemud.gamedesign.publication.RealmPolicyPublicationRepository;
import net.firedevops.firemud.gamedesign.publication.RealmPolicyPublicationService;
import net.firedevops.firemud.gamedesign.publication.RealmPolicyPublishedEvidence;
import net.firedevops.firemud.gamedesign.publication.RealmPolicySnapshot;
import net.firedevops.firemud.gamedesign.publication.RealmPolicySource;
import net.firedevops.firemud.gamedesign.publication.RealmPolicySourceRepository;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.PublishAttemptRepository;
import net.firedevops.firemud.gamedesign.repository.PublishedReleaseBundleRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.service.impl.PublishedWorldSelectorFixtures;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.ExecuteContext;
import org.jooq.ExecuteListener;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.DataSourceConnectionProvider;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.impl.DefaultExecuteListenerProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jooq.autoconfigure.SpringTransactionProvider;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/** Actual Game Design terminal and release rows; the retained policy baseline is ISOLATED. */
@Testcontainers(disabledWithoutDocker = true)
class RealmPolicyPublicationPostgresIntegrationTest {
  private static final String MANIFEST_HASH = "sha256:" + "a".repeat(64);
  private static final List<String> PUBLISHED_SET_COLUMNS =
      List.of(
          "canonical_tenant_id",
          "canonical_version_id",
          "game_design_version_row_id",
          "version_number",
          "source_commit_id",
          "source_epoch",
          "target_proof_json",
          "operation_bytes",
          "capture_bytes",
          "terminal_evidence_bytes",
          "release_content_bytes",
          "published_release_bundle_ref",
          "published_release_bundle_digest",
          "publish_workflow_id",
          "manifest_hash",
          "publication_version_state_epoch",
          "policy_count",
          "policy_set_digest",
          "sealed");

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void actualSealAssociatesCompleteSetAndReadKeepsOriginalEpochAfterVersionMovement()
      throws Exception {
    Fixture fixture = fixture(true);
    GameDesignPublicationOperation operation = fixture.prepare(true);
    fixture.writePublish(operation, false);

    var original = fixture.publicationReads().readPublishedSet(fixture.target()).orElseThrow();
    assertThat(original.policies()).hasSize(1);
    assertThat(original.policyCount()).isEqualTo(1);
    assertThat(original.policies().getFirst().policyId()).isNotEqualTo(new UUID(0L, 0L));
    assertThat(original.policies().getFirst().source().revisionId())
        .isEqualTo(fixture.baseline().revisions().getFirst().revisionId());
    assertThat(original.policies().getFirst().source().commitId())
        .isEqualTo(fixture.baseline().commitId());
    assertThat(original.publishWorkflowId()).isEqualTo(operation.workflowId());
    assertThat(original.publicationVersionStateEpoch())
        .isEqualTo(operation.world().request().versionStateEpoch() + 1);
    assertRejectedHeaderClonesPreserveOriginal(fixture, original);

    var exactRetry =
        fixture.tx(
            () ->
                fixture
                    .publicationWrites()
                    .retainSealedPublished(operation.workflowId())
                    .orElseThrow());
    assertThat(exactRetry.policies().getFirst().policyId())
        .isEqualTo(original.policies().getFirst().policyId());
    assertThat(exactRetry.policySetDigest()).isEqualTo(original.policySetDigest());
    assertThat(exactRetry.operationBytes()).containsExactly(original.operationBytes());
    assertThat(exactRetry.captureBytes()).containsExactly(original.captureBytes());
    assertThat(exactRetry.terminalEvidenceBytes())
        .containsExactly(original.terminalEvidenceBytes());

    fixture.moveVersion(VersionLifecycleState.ACTIVE, original.publicationVersionStateEpoch() + 1);
    var activeRead = fixture.publicationReads().readPublishedSet(fixture.target()).orElseThrow();
    assertThat(activeRead.publicationVersionStateEpoch())
        .isEqualTo(original.publicationVersionStateEpoch());
    assertThat(activeRead.policies().getFirst().policyId())
        .isEqualTo(original.policies().getFirst().policyId());
    assertThat(
            fixture
                .publicationReads()
                .readPublishedPolicy(fixture.target(), "world", "main")
                .orElseThrow()
                .policyId())
        .isEqualTo(original.policies().getFirst().policyId());
    var retryAfterActivation =
        fixture.tx(
            () ->
                fixture
                    .publicationWrites()
                    .retainSealedPublished(operation.workflowId())
                    .orElseThrow());
    assertThat(retryAfterActivation.policies().getFirst().policyId())
        .isEqualTo(original.policies().getFirst().policyId());
    assertThat(retryAfterActivation.policySetDigest()).isEqualTo(original.policySetDigest());
    assertThat(retryAfterActivation.terminalEvidenceBytes())
        .containsExactly(original.terminalEvidenceBytes());

    assertThatThrownBy(
            () ->
                fixture.tx(
                    () ->
                        fixture
                            .dsl()
                            .execute(
                                "UPDATE game_design_published_realm_policy_set "
                                    + "SET policy_set_digest = ? WHERE canonical_tenant_id = ? AND canonical_version_id = ?",
                                "sha256:" + "f".repeat(64),
                                fixture.target().canonicalTenantId(),
                                fixture.target().canonicalVersionId())))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void openSetRejectsReplacedSourceAndWrongOrdinalWhileKeepingInheritedProvenance()
      throws Exception {
    Fixture fixture = fixture(true);
    var stale =
        fixture
            .policySource()
            .readSnapshot(fixture.target(), fixture.baseline().commitId())
            .orElseThrow()
            .policies()
            .getFirst();
    // A second stipulated ISOLATED owner baseline replaces the main policy. The genuine
    // publication selection then inherits it through the ordinary frozen-source path.
    var replacementBinding =
        fixture.tx(() -> baseline(fixture.target(), fixture.dsl(), "1", "Replacement World"));
    GameDesignPublicationOperation operation = fixture.prepare(true);
    var selected = fixture.policySource().readCapture(operation).orElseThrow().snapshot();
    var replacement = selected.policies().getFirst();
    assertThat(selected.binding().commitId()).isNotEqualTo(replacementBinding.commitId());
    assertThat(replacement.commitId()).isEqualTo(replacementBinding.commitId());
    assertThat(replacement.revisionId())
        .isEqualTo(replacementBinding.revisions().getFirst().revisionId());

    var probes = new AtomicInteger();
    var configuration = fixture.dsl().configuration();
    var previousListeners = configuration.executeListenerProviders();
    configuration.set(
        new DefaultExecuteListenerProvider(
            new ExecuteListener() {
              @Override
              public void executeEnd(ExecuteContext context) {
                String sql = context.sql();
                if (sql == null
                    || !sql.startsWith("INSERT INTO game_design_published_realm_policy_set "))
                  return;
                assertRejectedOpenSetPolicy(fixture, operation, stale, 0);
                assertRejectedOpenSetPolicy(fixture, operation, replacement, 1);
                probes.incrementAndGet();
              }
            }));
    try {
      fixture.writePublish(operation, false);
    } finally {
      configuration.set(previousListeners);
    }
    assertThat(probes.get()).isEqualTo(1);
    var published = fixture.publicationReads().readPublishedSet(fixture.target()).orElseThrow();
    assertThat(published.sourceCommitId()).isEqualTo(selected.binding().commitId());
    assertThat(published.policies()).hasSize(1);
    assertThat(published.policies().getFirst().source()).isEqualTo(replacement);
    var retry =
        fixture.tx(
            () ->
                fixture
                    .publicationWrites()
                    .retainSealedPublished(operation.workflowId())
                    .orElseThrow());
    assertPublishedSetUnchanged(published, retry);
    assertPublishedSetUnchanged(
        published, fixture.publicationReads().readPublishedSet(fixture.target()).orElseThrow());
  }

  private static void assertRejectedOpenSetPolicy(
      Fixture fixture,
      GameDesignPublicationOperation operation,
      RealmPolicySource.Policy source,
      int ordinal) {
    var policy = source.policy();
    UUID policyId = UUID.randomUUID();
    var terminal =
        GameDesignPublicationTerminalEvidence.fromStored(
            fixture
                .operations()
                .read(operation.workflowId())
                .orElseThrow()
                .terminalEvidenceBytes());
    var release = terminal.releaseContent();
    String digest =
        RealmPolicyPublishedEvidence.policyDigest(
            policyId,
            fixture.target(),
            release.versionNumber(),
            release.publishedReleaseBundleRef(),
            terminal.publishedReleaseBundleDigest(),
            release.publishWorkflowId(),
            release.manifestHash(),
            source);
    assertThatThrownBy(
            () ->
                fixture
                    .dsl()
                    .transaction(
                        nested ->
                            DSL.using(nested)
                                .execute(
                                    "INSERT INTO game_design_published_realm_policy "
                                        + "(canonical_tenant_id, canonical_version_id, ordinal, realm_policy_id, "
                                        + "source_commit_id, source_revision_id, logical_revision_id, world_slug, realm_slug, "
                                        + "policy_json, policy_digest, visible, public_production) "
                                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                                    fixture.target().canonicalTenantId(),
                                    fixture.target().canonicalVersionId(),
                                    ordinal,
                                    policyId,
                                    source.commitId(),
                                    source.revisionId(),
                                    source.logicalRevisionId(),
                                    policy.worldSlug(),
                                    policy.realmSlug(),
                                    policy.canonicalJson(),
                                    digest,
                                    policy.visible(),
                                    policy.publicProduction())))
        .satisfies(
            failure ->
                assertThat(rootCause(failure).getMessage())
                    .contains(
                        "published realm policy row differs from exact captured source revision"));
    assertThat(fixture.dsl().fetchCount(DSL.table("game_design_published_realm_policy"))).isZero();
  }

  @Test
  void sealRollbackLeavesNoAssociationAndExactPublicationRetryAllocatesOneStableIdentity()
      throws Exception {
    Fixture fixture = fixture(true);
    GameDesignPublicationOperation operation = fixture.prepare(true);
    fixture.writePublish(operation, true);

    assertThat(fixture.operations().read(operation.workflowId()).orElseThrow().outcome())
        .isEqualTo("PENDING");
    assertThat(fixture.dsl().fetchCount(DSL.table("game_design_published_realm_policy_set")))
        .isZero();
    assertThat(fixture.dsl().fetchCount(DSL.table("game_design_published_realm_policy"))).isZero();

    fixture.writePublish(operation, false);
    var first = fixture.publicationReads().readPublishedSet(fixture.target()).orElseThrow();
    fixture.tx(() -> fixture.publicationWrites().retainSealedPublished(operation.workflowId()));
    var replay = fixture.publicationReads().readPublishedSet(fixture.target()).orElseThrow();
    assertThat(replay.policies().getFirst().policyId())
        .isEqualTo(first.policies().getFirst().policyId());
    assertThat(replay.policySetDigest()).isEqualTo(first.policySetDigest());
  }

  @Test
  void releaseWithoutTypedSourceRemainsValidButMissingCaptureWithSourceFailsClosed()
      throws Exception {
    Fixture unrelated = fixture(false);
    GameDesignPublicationOperation noPolicy = unrelated.prepare(false);
    unrelated.writePublish(noPolicy, false);
    assertThat(unrelated.publicationReads().readPublishedSet(unrelated.target())).isEmpty();
    assertThat(
            unrelated.tx(
                () -> unrelated.publicationWrites().retainSealedPublished(noPolicy.workflowId())))
        .isEmpty();

    Fixture missingCapture = fixture(true);
    GameDesignPublicationOperation policyOperation = missingCapture.prepare(false);
    assertThatThrownBy(() -> missingCapture.writePublish(policyOperation, false))
        .hasMessageContaining("POLICY_PUBLICATION_CAPTURE_UNAVAILABLE");
    assertThat(
            missingCapture.operations().read(policyOperation.workflowId()).orElseThrow().outcome())
        .isEqualTo("PENDING");
    assertThat(missingCapture.publicationReads().readPublishedSet(missingCapture.target()))
        .isEmpty();
  }

  private static Fixture fixture(boolean isolatedPolicyBaseline) throws Exception {
    String schema = "gd_realm_policy_pub_" + UUID.randomUUID().toString().replace("-", "");
    var source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    source.setSchema(schema);
    migrate(source, schema, "51");
    var transactions = new DataSourceTransactionManager(source);
    var write = new TransactionTemplate(transactions);
    write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    var configuration = new DefaultConfiguration();
    configuration.set(SQLDialect.POSTGRES);
    configuration.set(
        new DataSourceConnectionProvider(new TransactionAwareDataSourceProxy(source)));
    configuration.set(new SpringTransactionProvider(transactions));
    var dsl = DSL.using(configuration);
    var games = new GameRepository(dsl);
    var properties = new PostgresProperties();
    properties.setSchema(schema);
    var versions = new VersionRepository(dsl, properties);
    Game game =
        write.execute(
            status -> {
              var requested = new Game();
              requested.setTenantId("ISOLATED-" + UUID.randomUUID().toString().substring(0, 8));
              requested.setName("ISOLATED realm policy publication");
              return games.save(requested);
            });
    Version version = write.execute(status -> insertRetainedVersion(dsl, versions, game, 1));
    migrate(source, schema, "54");
    TargetProof target =
        new TargetProof(
            version.getCanonicalTenantId(),
            version.getCanonicalVersionId(),
            version.getId(),
            version.getTenantId(),
            version.getIdentitySourceGameRowId(),
            version.getIdentitySourceGameTenantKey(),
            version.getIdentitySourceProvenanceKind());
    var policySource = new RealmPolicySourceRepository(dsl);
    var baseline = isolatedPolicyBaseline ? write.execute(status -> baseline(target, dsl)) : null;
    var publicationWrites = new RealmPolicyPublicationRepository(dsl);
    var publicationReads = new RealmPolicyPublicationService(publicationWrites, transactions);
    return new Fixture(
        dsl,
        write,
        transactions,
        target,
        baseline,
        versions,
        new PublishAttemptRepository(dsl),
        new PublishedReleaseBundleRepository(dsl),
        new GameDesignPublicationOperationRepository(dsl),
        policySource,
        publicationWrites,
        publicationReads);
  }

  private static void migrate(DriverManagerDataSource source, String schema, String target) {
    Flyway.configure()
        .dataSource(source)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .table("flyway_schema_history_game_design_service")
        .target(target)
        .load()
        .migrate();
  }

  private static Version insertRetainedVersion(
      DSLContext dsl, VersionRepository versions, Game game, int versionNumber) {
    var inserted =
        dsl.fetchOne(
            "INSERT INTO version (tenant_id, canonical_version_id, canonical_tenant_id, "
                + "identity_source_game_row_id, identity_source_game_tenant_key, "
                + "identity_source_provenance_kind, version_number, version_state, version_state_epoch, "
                + "script_patch_version, base_version_id, is_script_only, notes, created_at, updated_at) "
                + "SELECT g.tenant_id, ?, g.canonical_tenant_id, g.id, g.tenant_id, "
                + "g.tenant_identity_provenance_kind, ?, 'DRAFT', 1, NULL, NULL, FALSE, "
                + "'ISOLATED retained policy publication fixture', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP "
                + "FROM game g WHERE g.id = ? RETURNING id",
            UUID.randomUUID(),
            versionNumber,
            game.getId());
    if (inserted == null) {
      throw new IllegalStateException("ISOLATED retained Game Design owner row is absent");
    }
    return versions.findById(inserted.get("id", Long.class)).orElseThrow();
  }

  private static DraftCommitBinding baseline(TargetProof target, DSLContext dsl) {
    return baseline(target, dsl, "0", "World");
  }

  private static DraftCommitBinding baseline(
      TargetProof target, DSLContext dsl, String expectedEpoch, String worldDisplayName) {
    DraftCommitBinding binding = policyBinding(target, expectedEpoch, worldDisplayName);
    String sourceEpoch = Long.toString(Long.parseLong(expectedEpoch) + 1);
    var coordinator = new DraftCommitCoordinatorRepository(dsl);
    coordinator.claim(binding);
    coordinator.claimApplicationSlot(binding);
    coordinator.markOwnerInProgress(binding, Owner.GAME_DESIGN_CONTROL_PLANE);
    var outcome =
        new DraftCommitCoordinatorRepository.OwnerOutcome(
            Owner.GAME_DESIGN_CONTROL_PLANE,
            DraftCommitCoordinatorRepository.OwnerStatus.APPLIED,
            binding.commitId(),
            binding.digest(),
            "ISOLATED-initial-policy-baseline",
            "ISOLATED-test-owner-baseline".getBytes(StandardCharsets.UTF_8),
            List.of(
                new DraftCommitCoordinatorRepository.AppliedEpoch(
                    RealmPolicySource.SCOPE,
                    target.canonicalVersionId().toString(),
                    RealmPolicySource.SCOPE,
                    "effective",
                    expectedEpoch,
                    sourceEpoch)));
    coordinator.recordOwnerOutcome(binding, outcome);
    IsolatedPublicationOwnerSetup.advanceIsolatedVisibility(dsl, binding, List.of(outcome));
    coordinator.releaseApplicationSlot(binding);
    var policy = RealmPolicySource.revision(binding, binding.revisions().getFirst());
    var snapshot = new RealmPolicySnapshot(binding, sourceEpoch, List.of(policy));
    dsl.execute(
        "INSERT INTO game_design_realm_policy_snapshot "
            + "(canonical_tenant_id, canonical_version_id, commit_id, request_id, snapshot_json) "
            + "VALUES (?, ?, ?, ?, ?)",
        target.canonicalTenantId(),
        target.canonicalVersionId(),
        binding.commitId(),
        binding.requestId(),
        snapshot.canonicalJson());
    if ("0".equals(expectedEpoch)) {
      dsl.execute(
          "INSERT INTO game_design_realm_policy_source "
              + "(canonical_tenant_id, canonical_version_id, source_epoch, visible_commit_id) "
              + "VALUES (?, ?, ?, ?)",
          target.canonicalTenantId(),
          target.canonicalVersionId(),
          sourceEpoch,
          binding.commitId());
    } else {
      dsl.execute(
          "UPDATE game_design_realm_policy_source SET source_epoch = ?, visible_commit_id = ? "
              + "WHERE canonical_tenant_id = ? AND canonical_version_id = ?",
          sourceEpoch,
          binding.commitId(),
          target.canonicalTenantId(),
          target.canonicalVersionId());
    }
    return binding;
  }

  private static DraftCommitBinding policyBinding(
      TargetProof target, String expectedEpoch, String worldDisplayName) {
    var policy =
        RealmEntryPolicy.parse(
            "{\"schemaVersion\":1,\"worldSlug\":\"world\",\"worldDisplayName\":\""
                + worldDisplayName
                + "\","
                + "\"realmSlug\":\"main\",\"realmDisplayName\":\"Main\",\"visible\":true,"
                + "\"publicProduction\":true,\"stateScope\":\"SHARED\","
                + "\"entryPolicy\":\"PRESEEDED_ONLY\"}",
            new ObjectMapper());
    String payload =
        "{\"revisionKind\":\"REALM_ENTRY_POLICY\",\"logicalRevisionId\":\"isolated-main\",\"policy\":"
            + policy.canonicalJson()
            + "}";
    return DraftCommitBinding.create(
        target,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "ISOLATED-initial-source-base",
        List.of(
            new DraftCommitBinding.RevisionPayload(
                "0", UUID.randomUUID(), Owner.GAME_DESIGN_CONTROL_PLANE, payload)),
        List.of(
            new DraftCommitBinding.AffectedUnit(
                Owner.GAME_DESIGN_CONTROL_PLANE,
                RealmPolicySource.SCOPE,
                target.canonicalVersionId().toString(),
                RealmPolicySource.SCOPE,
                "effective",
                expectedEpoch)));
  }

  private static void assertRejectedHeaderClonesPreserveOriginal(
      Fixture fixture, RealmPolicyPublishedEvidence.PublishedSet original) {
    String targetProofError = "published realm policy set requires a closed complete TargetProof";
    String terminalBindingError =
        "published realm policy set is not bound to exact sealed operation, capture, source, and release";
    assertRejectedHeaderClonePreservesOriginal(
        fixture,
        original,
        "target_proof_json",
        "jsonb_set(existing.target_proof_json::JSONB, '{sourceGameRowId}', 'null'::JSONB)::TEXT",
        targetProofError);
    assertRejectedHeaderClonePreservesOriginal(
        fixture,
        original,
        "target_proof_json",
        "(existing.target_proof_json::JSONB - 'canonicalVersionId')::TEXT",
        targetProofError);
    assertRejectedHeaderClonePreservesOriginal(
        fixture,
        original,
        "target_proof_json",
        "(existing.target_proof_json::JSONB || '{\"unknown\":\"x\"}'::JSONB)::TEXT",
        targetProofError);
    assertRejectedHeaderClonePreservesOriginal(
        fixture,
        original,
        "operation_bytes",
        "existing.operation_bytes || decode('00', 'hex')",
        terminalBindingError);
    assertRejectedHeaderClonePreservesOriginal(
        fixture,
        original,
        "terminal_evidence_bytes",
        "existing.terminal_evidence_bytes || decode('00', 'hex')",
        terminalBindingError);
    assertRejectedHeaderClonePreservesOriginal(
        fixture,
        original,
        "capture_bytes",
        "existing.capture_bytes || decode('00', 'hex')",
        terminalBindingError);
    assertRejectedHeaderClonePreservesOriginal(
        fixture,
        original,
        "publish_workflow_id",
        "('wrong-' || existing.publish_workflow_id)::VARCHAR(1024)",
        "published realm policy set operation is unavailable");
  }

  private static void assertRejectedHeaderClonePreservesOriginal(
      Fixture fixture,
      RealmPolicyPublishedEvidence.PublishedSet original,
      String replacementColumn,
      String replacementExpression,
      String expectedGuardMessage) {
    String headerBefore = publishedSetRowJson(fixture);
    List<String> policiesBefore = publishedPolicyRowsJson(fixture);
    assertThat(fixture.dsl().fetchCount(DSL.table("game_design_published_realm_policy_set")))
        .isEqualTo(1);
    assertThat(fixture.dsl().fetchCount(DSL.table("game_design_published_realm_policy")))
        .isEqualTo(original.policyCount());

    String sql = headerCloneInsertSql(replacementColumn, replacementExpression);
    assertThatThrownBy(
            () ->
                fixture.tx(
                    () -> {
                      fixture
                          .dsl()
                          .execute(
                              sql,
                              fixture.target().canonicalTenantId(),
                              fixture.target().canonicalVersionId());
                      return null;
                    }))
        .satisfies(
            failure -> assertThat(rootCause(failure).getMessage()).contains(expectedGuardMessage));

    assertThat(publishedSetRowJson(fixture)).isEqualTo(headerBefore);
    assertThat(publishedPolicyRowsJson(fixture)).containsExactlyElementsOf(policiesBefore);
    assertThat(fixture.dsl().fetchCount(DSL.table("game_design_published_realm_policy_set")))
        .isEqualTo(1);
    assertThat(fixture.dsl().fetchCount(DSL.table("game_design_published_realm_policy")))
        .isEqualTo(original.policyCount());
    assertPublishedSetUnchanged(
        original, fixture.publicationReads().readPublishedSet(fixture.target()).orElseThrow());
  }

  private static String headerCloneInsertSql(
      String replacementColumn, String replacementExpression) {
    if (!PUBLISHED_SET_COLUMNS.contains(replacementColumn)) {
      throw new IllegalArgumentException("Published-set clone column is not allowlisted");
    }
    String columnList = String.join(", ", PUBLISHED_SET_COLUMNS);
    String selectList =
        PUBLISHED_SET_COLUMNS.stream()
            .map(
                column -> {
                  if ("sealed".equals(column)) return "FALSE";
                  if (column.equals(replacementColumn)) return replacementExpression;
                  return "existing." + column;
                })
            .collect(java.util.stream.Collectors.joining(", "));
    return "INSERT INTO game_design_published_realm_policy_set ("
        + columnList
        + ") SELECT "
        + selectList
        + " FROM game_design_published_realm_policy_set existing "
        + "WHERE existing.canonical_tenant_id = ? AND existing.canonical_version_id = ?";
  }

  private static String publishedSetRowJson(Fixture fixture) {
    var row =
        fixture
            .dsl()
            .fetchOne(
                "SELECT to_jsonb(s)::TEXT AS evidence FROM game_design_published_realm_policy_set s "
                    + "WHERE canonical_tenant_id = ? AND canonical_version_id = ?",
                fixture.target().canonicalTenantId(),
                fixture.target().canonicalVersionId());
    assertThat(row).isNotNull();
    return row.get("evidence", String.class);
  }

  private static List<String> publishedPolicyRowsJson(Fixture fixture) {
    return fixture
        .dsl()
        .fetch(
            "SELECT to_jsonb(p)::TEXT AS evidence FROM game_design_published_realm_policy p "
                + "WHERE canonical_tenant_id = ? AND canonical_version_id = ? ORDER BY ordinal",
            fixture.target().canonicalTenantId(),
            fixture.target().canonicalVersionId())
        .stream()
        .map(row -> row.get("evidence", String.class))
        .toList();
  }

  private static Throwable rootCause(Throwable failure) {
    Throwable cause = failure;
    while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
    return cause;
  }

  private static void assertPublishedSetUnchanged(
      RealmPolicyPublishedEvidence.PublishedSet expected,
      RealmPolicyPublishedEvidence.PublishedSet actual) {
    assertThat(actual.target()).isEqualTo(expected.target());
    assertThat(actual.versionNumber()).isEqualTo(expected.versionNumber());
    assertThat(actual.sourceCommitId()).isEqualTo(expected.sourceCommitId());
    assertThat(actual.sourceEpoch()).isEqualTo(expected.sourceEpoch());
    assertThat(actual.publishedReleaseBundleRef()).isEqualTo(expected.publishedReleaseBundleRef());
    assertThat(actual.publishedReleaseBundleDigest())
        .isEqualTo(expected.publishedReleaseBundleDigest());
    assertThat(actual.publishWorkflowId()).isEqualTo(expected.publishWorkflowId());
    assertThat(actual.manifestHash()).isEqualTo(expected.manifestHash());
    assertThat(actual.publicationVersionStateEpoch())
        .isEqualTo(expected.publicationVersionStateEpoch());
    assertThat(actual.operationBytes()).containsExactly(expected.operationBytes());
    assertThat(actual.captureBytes()).containsExactly(expected.captureBytes());
    assertThat(actual.terminalEvidenceBytes()).containsExactly(expected.terminalEvidenceBytes());
    assertThat(actual.policyCount()).isEqualTo(expected.policyCount());
    assertThat(actual.policySetDigest()).isEqualTo(expected.policySetDigest());
    assertThat(actual.policies()).containsExactlyElementsOf(expected.policies());
  }

  private static PublishedReleaseBundle bundle(GameDesignPublicationOperation operation)
      throws Exception {
    var bundle = new PublishedReleaseBundle();
    bundle.setTenantId(operation.tenantKey());
    bundle.setVersionId(operation.versionId());
    bundle.setVersionNumber(1);
    bundle.setCanonicalTenantId(operation.account().tenantId());
    bundle.setCanonicalVersionId(operation.world().request().canonicalVersionId());
    bundle.setAttestationSchemaVersion("v2");
    bundle.setPublishWorkflowId(operation.workflowId());
    bundle.setManifestHash(MANIFEST_HASH);
    bundle.setManifestSchemaVersion(1);
    bundle.setArtifactDigestsJson("[]");
    bundle.setRequiredManifestAssetKeysJson("[]");
    bundle.setCommandDefinitionsJson("[]");
    bundle.setGenerationConfigRevision("generation-isolated-1");
    bundle.setParticipantDigestsJson(
        new ObjectMapper()
            .writeValueAsString(
                PublishedWorldSelectorFixtures.participants(
                    operation.versionId(), operation.world())));
    bundle.setWorldPublishedStartLocationEvidenceJson(
        new String(operation.world().canonicalBytes(), StandardCharsets.UTF_8));
    return bundle;
  }

  private record Fixture(
      DSLContext dsl,
      TransactionTemplate write,
      DataSourceTransactionManager transactions,
      TargetProof target,
      DraftCommitBinding baseline,
      VersionRepository versions,
      PublishAttemptRepository attempts,
      PublishedReleaseBundleRepository releases,
      GameDesignPublicationOperationRepository operations,
      RealmPolicySourceRepository policySource,
      RealmPolicyPublicationRepository publicationWrites,
      RealmPolicyPublicationService publicationReads) {
    <T> T tx(Supplier<T> work) {
      return write.execute(status -> work.get());
    }

    GameDesignPublicationOperation prepare(boolean capturePolicySource) throws Exception {
      return tx(
          () -> {
            try {
              var operation = IsolatedPublicationOwnerSetup.retain(dsl, target, 1);
              if (capturePolicySource) {
                policySource.captureSynchronized(
                    operation.account().input().selection().selectedCommit());
                policySource.freeze(operation);
              }
              return operation;
            } catch (Exception failure) {
              throw new IllegalStateException(failure);
            }
          });
    }

    void writePublish(GameDesignPublicationOperation operation, boolean rollBack) throws Exception {
      write.executeWithoutResult(
          status -> {
            try {
              IsolatedPublicationOwnerSetup.commitStorage(
                  dsl, versions, operation, () -> saveBundle(operation));
              if (rollBack) status.setRollbackOnly();
            } catch (Exception failure) {
              throw new IllegalStateException(failure);
            }
          });
    }

    PublishedReleaseBundle saveBundle(GameDesignPublicationOperation operation) {
      try {
        return releases.save(bundle(operation));
      } catch (Exception failure) {
        throw new IllegalStateException(failure);
      }
    }

    void moveVersion(VersionLifecycleState next, long epoch) {
      tx(
          () -> {
            var version =
                versions
                    .findByTenantIdAndIdForUpdate(
                        target.gameDesignVersionTenantKey(), target.gameDesignVersionRowId())
                    .orElseThrow();
            version.setVersionState(next);
            version.setVersionStateEpoch(epoch);
            versions.save(version);
            return null;
          });
    }
  }
}
