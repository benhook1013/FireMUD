package integration.net.firedevops.firemud.gamedesign.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.firedevops.firemud.common.authoring.DraftBaseReference;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository;
import net.firedevops.firemud.gamedesign.draft.GameDesignReviewedBaseRepository;
import net.firedevops.firemud.gamedesign.draft.IsolatedPublicationOwnerSetup;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.PublishedReleaseBundle;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.publication.GameDesignPublicationOperation;
import net.firedevops.firemud.gamedesign.publication.GameDesignPublicationOperationRepository;
import net.firedevops.firemud.gamedesign.publication.GameDesignSourceRepository;
import net.firedevops.firemud.gamedesign.publication.RealmPolicyPublicationRepository;
import net.firedevops.firemud.gamedesign.publication.RealmPolicyPublicationService;
import net.firedevops.firemud.gamedesign.publication.RealmPolicyPublishedEvidence;
import net.firedevops.firemud.gamedesign.publication.RealmPolicySource;
import net.firedevops.firemud.gamedesign.publication.RealmPolicySourceRepository;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.PublishAttemptRepository;
import net.firedevops.firemud.gamedesign.repository.PublishedReleaseBundleRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.service.impl.PublishedWorldSelectorFixtures;
import net.firedevops.firemud.test.TestContainerImages;
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

/** Actual Game Design terminal and release rows; the policy author's source input is ISOLATED. */
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
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void retainedAndFreshPolicyPublicationsSurviveAdditiveReleaseColumn() throws Exception {
    Fixture fixture = fixture(true);
    GameDesignPublicationOperation operation = fixture.prepare(true);
    fixture.writePublish(operation, false);

    var originalOwnerRead = fixture.operations().read(operation.workflowId()).orElseThrow();
    String originalReleaseEvidence =
        java.util.Objects.requireNonNull(
                fixture
                    .dsl()
                    .fetchOne(
                        "SELECT release_row_json FROM game_design_publication_operation WHERE publish_workflow_id = ?",
                        operation.workflowId()),
                "Expected retained policy publication operation row")
            .get("release_row_json", String.class);
    String originalWholeReleaseRow =
        java.util.Objects.requireNonNull(
                fixture
                    .dsl()
                    .fetchOne(
                        "SELECT to_jsonb(r)::TEXT AS evidence FROM published_release_bundle r WHERE tenant_id = ? AND version_id = ?",
                        operation.tenantKey(),
                        operation.versionId()),
                "Expected original policy published release row")
            .get("evidence", String.class);
    assertThat(originalReleaseEvidence).isEqualTo(originalWholeReleaseRow);

    fixture.tx(
        () -> {
          fixture
              .dsl()
              .execute(
                  "ALTER TABLE published_release_bundle ADD COLUMN future_optional_evidence TEXT");
          return null;
        });
    String expandedWholeReleaseRow =
        java.util.Objects.requireNonNull(
                fixture
                    .dsl()
                    .fetchOne(
                        "SELECT to_jsonb(r)::TEXT AS evidence FROM published_release_bundle r WHERE tenant_id = ? AND version_id = ?",
                        operation.tenantKey(),
                        operation.versionId()),
                "Expected policy published release row after additive column")
            .get("evidence", String.class);
    assertThat(expandedWholeReleaseRow).isNotEqualTo(originalReleaseEvidence);
    var retainedOwnerRead = fixture.operations().read(operation.workflowId()).orElseThrow();
    assertThat(retainedOwnerRead.receiptBytes()).isEqualTo(originalOwnerRead.receiptBytes());
    assertThat(retainedOwnerRead.terminalEvidenceBytes())
        .isEqualTo(originalOwnerRead.terminalEvidenceBytes());

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

    Fixture fresh = fixture(true);
    fresh.tx(
        () -> {
          fresh
              .dsl()
              .execute(
                  "ALTER TABLE published_release_bundle ADD COLUMN future_optional_evidence TEXT");
          return null;
        });
    GameDesignPublicationOperation freshOperation = fresh.prepare(true);
    fresh.writePublish(freshOperation, false);
    var freshRead = fresh.publicationReads().readPublishedSet(fresh.target()).orElseThrow();
    assertThat(freshRead.policies()).hasSize(1);
    assertThat(freshRead.policyCount()).isEqualTo(1);
    assertThat(
            fresh
                .publicationReads()
                .readPublishedPolicy(fresh.target(), "world", "main")
                .orElseThrow()
                .policyId())
        .isEqualTo(freshRead.policies().getFirst().policyId());
    var freshOwnerRead = fresh.operations().read(freshOperation.workflowId()).orElseThrow();
    assertThat(freshOwnerRead.outcome()).isEqualTo("PUBLISHED");
    byte[] freshReceipt = freshOwnerRead.receiptBytes();
    assertThat(freshReceipt).isNotNull();
    var freshRetry =
        fresh.tx(
            () ->
                fresh
                    .publicationWrites()
                    .retainSealedPublished(freshOperation.workflowId())
                    .orElseThrow());
    assertThat(freshRetry.policies().getFirst().policyId())
        .isEqualTo(freshRead.policies().getFirst().policyId());
    assertThat(freshRetry.operationBytes()).containsExactly(freshRead.operationBytes());
    assertThat(freshRetry.captureBytes()).containsExactly(freshRead.captureBytes());
    assertThat(freshRetry.terminalEvidenceBytes())
        .containsExactly(freshRead.terminalEvidenceBytes());
    assertThat(fresh.operations().read(freshOperation.workflowId()).orElseThrow().receiptBytes())
        .isEqualTo(freshReceipt);
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
        fixture.tx(
            () ->
                baseline(
                    fixture.target(),
                    fixture.dsl(),
                    new GameDesignSourceRepository(fixture.dsl()),
                    "Replacement World"));
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
  void missingPolicyCaptureFailsClosedForEveryFullVersionRelease() throws Exception {
    Fixture unrelated = fixture(false);
    GameDesignPublicationOperation noPolicy = unrelated.prepare(false);
    assertThatThrownBy(() -> unrelated.writePublish(noPolicy, false))
        .hasMessageContaining("POLICY_PUBLICATION_CAPTURE_UNAVAILABLE");
    assertThat(unrelated.operations().read(noPolicy.workflowId()).orElseThrow().outcome())
        .isEqualTo("PENDING");
    assertThat(unrelated.publicationReads().readPublishedSet(unrelated.target())).isEmpty();

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
    migrate(source, schema);
    var transactions = new DataSourceTransactionManager(source);
    var write = new TransactionTemplate(transactions);
    write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    var configuration = new DefaultConfiguration();
    configuration.set(SQLDialect.POSTGRES);
    configuration.set(
        new DataSourceConnectionProvider(new TransactionAwareDataSourceProxy(source)));
    configuration.set(new SpringTransactionProvider(transactions));
    var dsl = DSL.using(configuration);
    var sources = new GameDesignSourceRepository(dsl);
    var policySource = new RealmPolicySourceRepository(dsl);
    var games = new GameRepository(dsl);
    var versions = new VersionRepository(dsl);
    Game game =
        write.execute(
            status -> {
              var requested = new Game();
              requested.setTenantId("ISOLATED-" + UUID.randomUUID().toString().substring(0, 8));
              requested.setName("ISOLATED realm policy publication");
              return games.save(requested);
            });
    Version version =
        write.execute(
            status -> {
              var requested = new Version();
              requested.setTenantId(game.getTenantId());
              requested.setVersionNumber(1);
              Version created = versions.save(requested);
              sources
                  .readGenesis(target(created))
                  .orElseThrow(
                      () -> new IllegalStateException("fresh Version lacks actual source genesis"));
              return created;
            });
    TargetProof target = target(version);
    var baseline =
        isolatedPolicyBaseline
            ? write.execute(status -> baseline(target, dsl, sources, "World"))
            : null;
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

  private static void migrate(DriverManagerDataSource source, String schema) {
    Flyway.configure()
        .dataSource(source)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .table("flyway_schema_history_game_design_service")
        .load()
        .migrate();
  }

  private static DraftCommitBinding baseline(
      TargetProof target,
      DSLContext dsl,
      GameDesignSourceRepository sources,
      String worldDisplayName) {
    SourceHead head = currentSourceHead(target, dsl, sources);
    DraftCommitBinding binding =
        policyBinding(target, head.baseReference(), head.sourceEpoch(), worldDisplayName);
    var coordinator = new DraftCommitCoordinatorRepository(dsl);
    var reviewed =
        new GameDesignReviewedBaseRepository(dsl)
            .resolve(target, DraftBaseReference.parse(binding.baseCommitId()));
    sources.claimReviewed(binding, reviewed);
    coordinator.claimApplicationSlot(binding);
    coordinator.markOwnerInProgress(binding, Owner.GAME_DESIGN_CONTROL_PLANE);
    var applied = sources.apply(binding);
    IsolatedPublicationOwnerSetup.advanceIsolatedSourceVisibility(
        dsl, binding, List.of(applied.ownerOutcome()));
    coordinator.releaseApplicationSlot(binding);
    return binding;
  }

  private static DraftCommitBinding policyBinding(
      TargetProof target, String baseReference, String expectedEpoch, String worldDisplayName) {
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
        baseReference,
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

  private static SourceHead currentSourceHead(
      TargetProof target, DSLContext dsl, GameDesignSourceRepository sources) {
    var row =
        java.util.Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT source_epoch, visible_commit_id, genesis_receipt_id "
                    + "FROM game_design_realm_policy_source WHERE canonical_tenant_id = ? "
                    + "AND canonical_version_id = ?",
                target.canonicalTenantId(),
                target.canonicalVersionId()),
            "fresh Draft fixture must retain its actual policy source head");
    UUID visibleCommit = row.get("visible_commit_id", UUID.class);
    UUID genesisReceipt = row.get("genesis_receipt_id", UUID.class);
    UUID sharedGenesisReceipt =
        sources
            .readGenesis(target)
            .orElseThrow(
                () -> new IllegalStateException("fresh Draft fixture lacks shared genesis"))
            .policy()
            .receiptId();
    if (!sharedGenesisReceipt.equals(genesisReceipt)) {
      throw new IllegalStateException("policy source head differs from shared genesis receipt");
    }
    String baseReference =
        visibleCommit == null ? "genesis:" + sharedGenesisReceipt : visibleCommit.toString();
    return new SourceHead(row.get("source_epoch", String.class), baseReference);
  }

  private static TargetProof target(Version version) {
    return new TargetProof(
        version.getCanonicalTenantId(),
        version.getCanonicalVersionId(),
        version.getId(),
        version.getTenantId(),
        version.getIdentitySourceGameRowId(),
        version.getIdentitySourceGameTenantKey(),
        version.getIdentitySourceProvenanceKind());
  }

  private record SourceHead(String sourceEpoch, String baseReference) {}

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
                    operation.versionId(),
                    operation.world(),
                    AuthoredWorldReleaseAttestationEvidence.SELECTOR_SCHEMA_VERSION)));
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
              var sources = new GameDesignSourceRepository(dsl);
              String sourceBaseReference = currentSourceHead(target, dsl, sources).baseReference();
              var operation =
                  IsolatedPublicationOwnerSetup.retainSourceBacked(
                      dsl,
                      target,
                      1,
                      "ISOLATED remote owner transaction proof",
                      sourceBaseReference);
              if (capturePolicySource) {
                sources.freeze(operation);
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
