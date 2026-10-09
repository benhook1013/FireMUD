package net.firedevops.firemud.gamedesign.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.gamelogic.AccountGameLogicIntakeSettlementEvidence;
import net.firedevops.firemud.common.gamelogic.GameLogicGameplayRuleIntakeOperation;
import net.firedevops.firemud.common.gamelogic.GameLogicGameplayRuleIntakeTerminal;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeAuthorizationBinding;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSelectedSource;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelection;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * V61-only PostgreSQL storage proof definitions. The predecessor selection table and upstream
 * authority are explicit fixtures; this is not composed authoring or authenticated intake proof.
 */
@Testcontainers(disabledWithoutDocker = true)
class SelectedDraftGameLogicReceiptPostgresIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void migrationPreservesHistoricalSelectionAndReceiptReadbackIsImmutableAndExact() {
    var value = receiptFixture();
    var fixture = fixture(value);
    var dsl = fixture.dsl();
    var repository = new SelectedDraftGameLogicReceiptRepository(dsl);
    fixture.tx().executeWithoutResult(ignored -> repository.retain(value));
    fixture
        .tx()
        .executeWithoutResult(
            ignored ->
                assertThat(repository.retain(value).receipt().canonicalBytes())
                    .containsExactly(value.receipt().canonicalBytes()));
    var recovered = repository.read(value.selection(), value.authorization()).orElseThrow();
    assertThat(recovered.receipt().canonicalBytes())
        .containsExactly(value.receipt().canonicalBytes());
    assertThat(recovered.authorization().canonicalBytes())
        .containsExactly(value.authorization().canonicalBytes());
    // Publication may advance the mutable lifecycle epoch. Exact retained content recovery uses
    // the original authenticated epoch, not a new mutation authorization against current state.
    dsl.execute(
        "UPDATE version SET version_state_epoch = version_state_epoch + 1 WHERE id = ?",
        value.selection().target().gameDesignVersionRowId());
    var publicationRead = repository.readForPublication(publicationBinding(value));
    assertThat(publicationRead).isPresent();
    assertThat(publicationRead.orElseThrow().receipt().canonicalBytes())
        .containsExactly(value.receipt().canonicalBytes());
    assertThat(
            dsl.fetchSingle("SELECT count(*) FROM game_design_selected_game_logic_receipt")
                .get(0, Long.class))
        .isEqualTo(1L);
    assertThat(
            dsl.fetchSingle(
                    "SELECT selection_json FROM game_design_authored_draft_publish_selection")
                .get(0, String.class))
        .isEqualTo(value.selection().canonicalJson());
    var changed =
        new GameLogicIntakeAuthorizationBinding(
            value.authorization().operationId(),
            UUID.randomUUID(),
            value.authorization().intakeRequestId(),
            value.authorization().actorAccountId(),
            value.authorization().source(),
            value.authorization().sources());
    assertThatThrownBy(() -> repository.read(value.selection(), changed))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE game_design_selected_game_logic_receipt SET receipt_bytes = ?",
                    new byte[] {1}))
        .isInstanceOf(org.jooq.exception.DataAccessException.class);
    assertThatThrownBy(() -> dsl.execute("DELETE FROM game_design_selected_game_logic_receipt"))
        .isInstanceOf(org.jooq.exception.DataAccessException.class);
    assertThatThrownBy(
            () ->
                insert(
                    dsl,
                    value,
                    value.selection().canonicalBytes(),
                    value.receipt().canonicalBytes()))
        .isInstanceOf(org.jooq.exception.DataAccessException.class);
  }

  @Test
  void publicationReadUsesExactTenantAndRequestAndRejectsChangedVersionOrPatchScope() {
    var value = receiptFixture();
    var fixture = fixture(value);
    var dsl = fixture.dsl();
    var repository = new SelectedDraftGameLogicReceiptRepository(dsl);
    fixture.tx().executeWithoutResult(ignored -> repository.retain(value));

    assertThat(
            repository.readForPublication(
                PublicationDigestRequestBinding.full(
                    value.selection().intent().canonicalTenantId().toString(),
                    Long.toString(value.selection().target().gameDesignVersionRowId()),
                    "different-request")))
        .isEmpty();
    assertThat(
            repository.readForPublication(
                PublicationDigestRequestBinding.full(
                    UUID.randomUUID().toString(),
                    Long.toString(value.selection().target().gameDesignVersionRowId()),
                    value.selection().intent().publishRequestId())))
        .isEmpty();
    assertThatThrownBy(
            () ->
                repository.readForPublication(
                    PublicationDigestRequestBinding.full(
                        value.selection().intent().canonicalTenantId().toString(),
                        Long.toString(value.selection().target().gameDesignVersionRowId() + 1),
                        value.selection().intent().publishRequestId())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("does not match publication request");
    assertThatThrownBy(
            () ->
                repository.readForPublication(
                    PublicationDigestRequestBinding.patch(
                        value.selection().intent().canonicalTenantId().toString(),
                        "1",
                        "patch-1",
                        value.selection().intent().publishRequestId())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("FULL_VERSION");
    assertThat(
            dsl.fetchSingle("SELECT count(*) FROM game_design_selected_game_logic_receipt")
                .get(0, Long.class))
        .isEqualTo(1L);
  }

  @Test
  void publicationReadFailsClosedWhenNumericVersionRowNoLongerMatchesSelection() {
    var value = receiptFixture();
    var fixture = fixture(value);
    var repository = new SelectedDraftGameLogicReceiptRepository(fixture.dsl());
    fixture.tx().executeWithoutResult(ignored -> repository.retain(value));
    fixture
        .dsl()
        .execute(
            "UPDATE version SET canonical_version_id = ? WHERE id = ?",
            UUID.randomUUID(),
            value.selection().target().gameDesignVersionRowId());

    assertThatThrownBy(() -> repository.readForPublication(publicationBinding(value)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Version row proof unavailable");
  }

  @Test
  void publicationReadFailsClosedOnConflictingRetainedWorkflowField() {
    var value = receiptFixture();
    var fixture = fixture(value);
    var dsl = fixture.dsl();
    var repository = new SelectedDraftGameLogicReceiptRepository(dsl);
    fixture.tx().executeWithoutResult(ignored -> repository.retain(value));
    // Fault injection bypasses the immutable-row trigger to model conflicting retained bytes.
    dsl.execute(
        "ALTER TABLE game_design_selected_game_logic_receipt DISABLE TRIGGER trg_gd_selected_game_logic_receipt");
    dsl.execute(
        "UPDATE game_design_selected_game_logic_receipt SET workflow_identity = ?",
        "publish:wrong-tenant:publish-request:publish-1");

    assertThatThrownBy(() -> repository.readForPublication(publicationBinding(value)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("integrity conflict");
  }

  @Test
  void missingReceiptIsAbsentAndChangedSelectionOrOversizedReceiptCannotInsert() {
    var value = receiptFixture();
    var fixture = fixture(value);
    var dsl = fixture.dsl();
    assertThat(
            new SelectedDraftGameLogicReceiptRepository(dsl)
                .read(value.selection(), value.authorization()))
        .isEmpty();
    assertThatThrownBy(() -> insert(dsl, value, new byte[] {1}, value.receipt().canonicalBytes()))
        .isInstanceOf(org.jooq.exception.DataAccessException.class);
    assertThatThrownBy(
            () -> insert(dsl, value, value.selection().canonicalBytes(), new byte[16778241]))
        .isInstanceOf(org.jooq.exception.DataAccessException.class);
    assertThat(
            dsl.fetchSingle("SELECT count(*) FROM game_design_selected_game_logic_receipt")
                .get(0, Long.class))
        .isZero();
  }

  @Test
  void ownerTransactionRechecksCompleteSelectionBeforeInsertingDerivedReceipt() {
    var value = receiptFixture();
    var fixture = fixture(value);
    String altered =
        value.selection().canonicalJson().replace("\"notes\":\"\"", "\"notes\":\"changed\"");
    String digest =
        net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.digest(
            altered.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    // This predecessor fixture permits fault injection; the real V43 owner rejects rewriting.
    fixture
        .dsl()
        .execute(
            "UPDATE game_design_authored_draft_publish_selection SET selection_json = ?, selection_digest = ?",
            altered,
            digest);
    var repository = new SelectedDraftGameLogicReceiptRepository(fixture.dsl());
    assertThatThrownBy(() -> fixture.tx().execute(status -> repository.retain(value)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("changed before receipt retention");
    assertThat(
            fixture
                .dsl()
                .fetchSingle("SELECT count(*) FROM game_design_selected_game_logic_receipt")
                .get(0, Long.class))
        .isZero();
  }

  private static Fixture fixture(SelectedDraftGameLogicReceipt value) {
    String schema = "receipt_" + UUID.randomUUID().toString().replace("-", "");
    var data =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    DSL.using(data, SQLDialect.POSTGRES).execute("CREATE SCHEMA " + schema);
    var scoped =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl()
                + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?")
                + "currentSchema="
                + schema,
            POSTGRES.getUsername(),
            POSTGRES.getPassword());
    var dsl = DSL.using(new TransactionAwareDataSourceProxy(scoped), SQLDialect.POSTGRES);
    dsl.execute(
        "CREATE TABLE game_design_authored_draft_publish_selection (canonical_tenant_id UUID NOT NULL, canonical_version_id UUID NOT NULL, publish_request_id TEXT NOT NULL, selection_digest TEXT NOT NULL, selection_json TEXT NOT NULL, game_design_version_row_id BIGINT NOT NULL, game_design_version_tenant_key TEXT NOT NULL, source_game_row_id BIGINT NOT NULL, source_game_tenant_key TEXT NOT NULL, source_provenance_kind TEXT NOT NULL, version_state_epoch BIGINT NOT NULL, selected_commit_request_id UUID NOT NULL, selected_commit_id UUID NOT NULL, selected_commit_digest TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (canonical_tenant_id, canonical_version_id, publish_request_id))");
    dsl.execute(
        "CREATE TABLE game (id BIGINT PRIMARY KEY, tenant_id TEXT, canonical_tenant_id UUID, tenant_identity_provenance_kind TEXT, tenant_identity_source_game_id BIGINT, tenant_identity_source_legacy_tenant_id TEXT)");
    dsl.execute(
        "CREATE TABLE version (id BIGINT PRIMARY KEY, tenant_id TEXT, canonical_tenant_id UUID, canonical_version_id UUID, identity_source_game_row_id BIGINT, identity_source_game_tenant_key TEXT, identity_source_provenance_kind TEXT, version_state_epoch BIGINT)");
    var target = value.selection().target();
    dsl.execute(
        "INSERT INTO game VALUES (?, ?, ?, ?, ?, ?)",
        target.sourceGameRowId(),
        target.sourceGameTenantKey(),
        target.canonicalTenantId(),
        target.sourceProvenanceKind(),
        target.sourceGameRowId(),
        target.sourceGameTenantKey());
    dsl.execute(
        "INSERT INTO version VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        target.gameDesignVersionRowId(),
        target.gameDesignVersionTenantKey(),
        target.canonicalTenantId(),
        target.canonicalVersionId(),
        target.sourceGameRowId(),
        target.sourceGameTenantKey(),
        target.sourceProvenanceKind(),
        Long.parseLong(value.selection().intent().expectedVersionStateEpoch()));
    selection(dsl, value);
    Flyway.configure()
        .dataSource(scoped)
        .defaultSchema(schema)
        .schemas(schema)
        .baselineOnMigrate(true)
        .baselineVersion("60")
        .target("61")
        .placeholders(Map.of("serviceSchema", schema))
        .load()
        .migrate();
    var tx = new TransactionTemplate(new DataSourceTransactionManager(scoped));
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    return new Fixture(dsl, tx);
  }

  private static void selection(DSLContext dsl, SelectedDraftGameLogicReceipt value) {
    var target = value.selection().target();
    var intent = value.selection().intent();
    dsl.execute(
        "INSERT INTO game_design_authored_draft_publish_selection (canonical_tenant_id, canonical_version_id, publish_request_id, selection_digest, selection_json, game_design_version_row_id, game_design_version_tenant_key, source_game_row_id, source_game_tenant_key, source_provenance_kind, version_state_epoch, selected_commit_request_id, selected_commit_id, selected_commit_digest) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        value.selection().intent().canonicalTenantId(),
        value.selection().intent().canonicalVersionId(),
        value.selection().intent().publishRequestId(),
        value.selection().digest(),
        value.selection().canonicalJson(),
        target.gameDesignVersionRowId(),
        target.gameDesignVersionTenantKey(),
        target.sourceGameRowId(),
        target.sourceGameTenantKey(),
        target.sourceProvenanceKind(),
        Long.parseLong(intent.expectedVersionStateEpoch()),
        intent.selectedCommitRequestId(),
        intent.selectedCommitId(),
        intent.selectedCommitDigest());
  }

  private static void insert(
      DSLContext dsl,
      SelectedDraftGameLogicReceipt value,
      byte[] selectionBytes,
      byte[] receiptBytes) {
    dsl.execute(
        "INSERT INTO game_design_selected_game_logic_receipt (canonical_tenant_id, canonical_version_id, publish_request_id, selection_digest, workflow_identity, selection_bytes, authorization_bytes, authorization_digest, receipt_bytes, receipt_digest) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        value.selection().intent().canonicalTenantId(),
        value.selection().intent().canonicalVersionId(),
        value.selection().intent().publishRequestId(),
        value.selection().digest(),
        value.workflowIdentity(),
        selectionBytes,
        value.authorization().canonicalBytes(),
        value.authorization().digest(),
        receiptBytes,
        value.receipt().digest());
  }

  private static SelectedDraftGameLogicReceipt receiptFixture() {
    UUID actor = UUID.randomUUID();
    var target =
        new DraftCommitBinding.TargetProof(
            UUID.randomUUID(), UUID.randomUUID(), 1, "private", 2, "private", "NEW_GAME_ROW");
    var binding =
        DraftCommitBinding.create(
            target,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "genesis",
            List.of(
                new DraftCommitBinding.RevisionPayload(
                    "0",
                    UUID.randomUUID(),
                    DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                    "{}")),
            List.of(
                new DraftCommitBinding.AffectedUnit(
                    DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                    "GAMEPLAY_RULE_SET",
                    target.canonicalVersionId().toString(),
                    "GAMEPLAY_RULE_SET",
                    "effective",
                    "0")));
    var source =
        new GameplayRuleSelectedSource(
            GameplayRuleManifest.canonical(
                Map.of(
                    "schema", "game-design-gameplay-rule-source-snapshot/v1",
                    "bindingJson", binding.canonicalJson(),
                    "bindingDigest", binding.digest(),
                    "sourceEpoch", "1",
                    "inheritedCommitId", "",
                    "genesisReceiptId", UUID.randomUUID().toString(),
                    "manifestJson", GameplayRuleManifest.explicitEmpty().canonicalJson(),
                    "entries", List.of())));
    var auth =
        new GameLogicIntakeAuthorizationBinding(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            actor,
            source,
            List.of(
                new DraftAuthorizationFenceBinding.SourceEvidence(
                    DraftAuthorizationFenceBinding.SourceKind.ACCOUNT,
                    actor.toString(),
                    "1",
                    "1",
                    null,
                    null,
                    new byte[] {1})));
    var selected =
        AuthoredDraftPublishSelectionBinding.capture(
            new AuthoredDraftPublishSelectionBinding.PublishIntent(
                target.canonicalTenantId(),
                target.canonicalVersionId(),
                "publish-1",
                "1",
                "",
                binding.requestId(),
                binding.commitId(),
                binding.digest()),
            target,
            binding,
            new AuthoredDraftPublishSelectionBinding.VisibilityFence(
                target,
                binding.requestId(),
                binding.commitId(),
                binding.digest(),
                "[]",
                OffsetDateTime.parse("2026-10-08T00:00:00Z")));
    var terminal =
        GameLogicGameplayRuleIntakeTerminal.retained(
            new GameLogicGameplayRuleIntakeOperation("test", auth),
            source.canonicalBytes(),
            source.manifest().canonicalJson().getBytes(StandardCharsets.UTF_8));
    return new SelectedDraftGameLogicReceipt(
        AuthoredDraftPublishSelection.fromStored(selected.canonicalJson(), selected.digest()),
        auth,
        new AccountGameLogicIntakeSettlementEvidence(terminal));
  }

  private static PublicationDigestRequestBinding publicationBinding(
      SelectedDraftGameLogicReceipt value) {
    return PublicationDigestRequestBinding.full(
        value.selection().intent().canonicalTenantId().toString(),
        Long.toString(value.selection().target().gameDesignVersionRowId()),
        value.selection().intent().publishRequestId());
  }

  private record Fixture(DSLContext dsl, TransactionTemplate tx) {}
}
