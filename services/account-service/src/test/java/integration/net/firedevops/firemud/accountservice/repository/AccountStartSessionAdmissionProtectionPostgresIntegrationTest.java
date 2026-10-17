package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProofCodec;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.util.PSQLException;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * SQL integrity and source-writer definitions. Upstream Account capture, redeemed authorization,
 * World settlement and Game Session observation are explicitly stipulated with upstream triggers
 * disabled. Current admission guards remain enabled for acquisition and all assertions. These
 * fixtures are not genuine authenticated Account/World/Game Session producer or consumer proof.
 */
@Testcontainers(disabledWithoutDocker = true)
class AccountStartSessionAdmissionProtectionPostgresIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  // Exact installed parent artifacts at Entity source 9526190049a86feef40cd616ec7b5eb931247e8b.
  private static final Set<String> PARENT_MIGRATION_SCRIPTS =
      Set.of(
          "V100__account_jwt_signer_materialization_readback.sql",
          "V101__account_jwt_jwks_prepublication.sql",
          "V103__account_jwt_readiness_probes.sql",
          "V106__account_jwt_signer_promotion_lifecycle.sql",
          "V107__account_jwt_readiness_single_delivery_claims.sql",
          "V109__account_jwt_validator_inventory_snapshots.sql",
          "V10__platform_fee.sql",
          "V11__donation_flag.sql",
          "V123__account_jwt_readiness_per_pod_closure.sql",
          "V124__account_jwt_readiness_version_aware_delivery_claim_guard.sql",
          "V125__account_jwt_signer_promotion_receipt_binding.sql",
          "V126.1__start_session_operator_response_envelope_erasure.sql",
          "V126.2__account_control_ui_registry_cleanup_deadline.sql",
          "V126.3__start_session_response_envelope_bounds.sql",
          "V126__start_session_operator_authorization.sql",
          "V12__virtual_currency.sql",
          "V137.1__account_platform_restriction_lifecycle_consistency.sql",
          "V137.2__account_platform_restriction_outbox_column_qualification.sql",
          "V137.3__account_security_guard_source_row_type.sql",
          "V137__account_platform_restriction_revision_receipts.sql",
          "V13__creator_share.sql",
          "V140__account_gameplay_admission_lease_operations.sql",
          "V141__account_admission_unknown_decision_abort.sql",
          "V142__account_admission_durable_commit_confirmation.sql",
          "V143__account_global_pending_admission_fences.sql",
          "V144__account_admission_original_ack_receipt.sql",
          "V145__retire_experimental_admission_confirmation_writes.sql",
          "V146__account_original_ack_pg18_fresh_insert.sql",
          "V147__account_gameplay_delegation_issuance.sql",
          "V148__account_auth_evidence_bundles.sql",
          "V149__account_gameplay_delegation_encrypted_response.sql",
          "V14__tenant_indexes.sql",
          "V150__account_gameplay_delegation_committed_issuance.sql",
          "V151__account_gameplay_token_identity_fences.sql",
          "V152__account_gameplay_login_credential_request_binding.sql",
          "V153__account_gameplay_delegation_decimal_source_versions.sql",
          "V154__account_auth_evidence_checkpoint_key_count.sql",
          "V155__account_auth_evidence_shared_issuer_guard.sql",
          "V156__account_gameplay_commit_bundle_source_fence.sql",
          "V157.1__account_gameplay_token_fence_truncate_guard.sql",
          "V157__account_gameplay_authority_shared_issuer_guard.sql",
          "V158__account_start_session_authority_captures.sql",
          "V159__start_session_world_participation.sql",
          "V15__account_tenant_membership.sql",
          "V160.1__start_session_operator_bundle_source_fence.sql",
          "V160__start_session_world_original_attempt_evidence.sql",
          "V16__profile_presence_visibility.sql",
          "V17__account_realm_access_grants.sql",
          "V18__account_email_login_challenges.sql",
          "V19__account_login_auth_modes.sql",
          "V1__init.sql",
          "V20__account_lifecycle_state.sql",
          "V21__validate_account_lifecycle_state.sql",
          "V22__canonicalize_account_emails.sql",
          "V23__validate_account_email_canonical_check.sql",
          "V24__account_credential_expiry_indexes.sql",
          "V25__scope_profile_identity.sql",
          "V26__global_registration_join_evidence.sql",
          "V27__retain_join_connect_scopes.sql",
          "V28__account_audit_delivery_backoff.sql",
          "V29__account_identity_uuid_bridge.sql",
          "V2__payment_tables.sql",
          "V30__approved_legacy_tenant_associations.sql",
          "V31__subscription_authority_generation.sql",
          "V32__realm_grant_currentness.sql",
          "V33__fresh_tenant_identity_associations.sql",
          "V34__account_membership_pair_authority.sql",
          "V35__canonical_uuid_membership_identity.sql",
          "V36__account_auth_authority_generations.sql",
          "V37__canonical_first_join_scope_and_intent.sql",
          "V38__account_first_join_authority_outbox_and_roles.sql",
          "V39__canonical_first_join_terminal_receipt.sql",
          "V3__tenant_columns.sql",
          "V40.1__retained_tenant_approval_payload_references.sql",
          "V40__account_authority_source_baselines.sql",
          "V4__roles_2fa.sql",
          "V53__account_protected_demo_entitlements.sql",
          "V5__payment_tenant_columns.sql",
          "V6__password_reset_token.sql",
          "V73__account_tenant_authority_source_bridge.sql",
          "V74__account_global_role_source.sql",
          "V75__draft_commit_authorization_fences.sql",
          "V76__account_draft_required_owner_fence.sql",
          "V77__account_password_reset_operation_receipts.sql",
          "V78__account_logout_all_operation_receipts.sql",
          "V79__account_security_state_operations.sql",
          "V7__external_account.sql",
          "V80__account_security_event_source_guard.sql",
          "V81__account_platform_restriction_birth_sources.sql",
          "V82__creator_membership_provenance.sql",
          "V83__account_tenant_creation_bootstrap.sql",
          "V84__individual_creator_party_sources.sql",
          "V85__account_hosted_terms_acceptance_workflow.sql",
          "V86__account_hosted_terms_environment_binding.sql",
          "V87__account_control_ui_owner_source_guards.sql",
          "V88__account_logout_all_pending_draft_source_changes.sql",
          "V89__account_password_reset_pending_draft_source_changes.sql",
          "V8__email_verification.sql",
          "V90__account_control_ui_committed_issuance.sql",
          "V91__account_selected_publication_authorization.sql",
          "V92__account_publication_pre_freeze_order.sql",
          "V93__account_selected_publication_settlement.sql",
          "V94__selected_publication_inventory_v2.sql",
          "V95__game_logic_intake_authorization.sql",
          "V96__game_logic_intake_terminal_settlement.sql",
          "V97__game_logic_intake_source_read_reservation.sql",
          "V98__account_hosted_terms_disclosure_handoffs.sql",
          "V99__account_jwt_signer_desired_state.sql",
          "V9__payment_provider_id.sql",
          "V1000__create_saga_schema.sql",
          "V1001__saga_instance_table.sql",
          "V1002__saga_step_table.sql");

  @Test
  void actualDefaultLocationsFreshBootstrapAppliesFullCombinedHistoryAndEnabledGuards() {
    String schema = "ss_fresh_" + UUID.randomUUID().toString().replace("-", "");
    var source =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    source.setSchema(schema);
    Flyway current =
        actualParentFlyway(source, "classpath:db/migration", "classpath:db/migration/saga");
    current.migrate();
    current.validate();
    assertCurrentMigrationsInstalled(current);
    var dsl = DSL.using(source, SQLDialect.POSTGRES);
    assertThat(
            dsl.fetchSingle(
                    "SELECT count(*) FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = ? AND NOT t.tgisinternal AND t.tgenabled <> 'O'",
                    schema)
                .get(0, Long.class))
        .isZero();
    for (String signature :
        List.of(
            "account_ss_admission_assert_current(account_start_session_admission_protections,boolean)",
            "account_ss_activation_assert_current(bytea,bytea,text,boolean)")) {
      assertThat(
              dsl.fetchSingle("SELECT pg_get_functiondef(?::regprocedure)", signature)
                  .get(0, String.class))
          .contains("issuanceOperationIdentity,issuanceOperationId");
    }
  }

  @Test
  void actualReceivingParentIncludingSagaUpgradePreservesHistoryAndAppliesSourceProtections(
      @TempDir Path parentMigrations) throws Exception {
    var parent = fixture(60_000L, "160.1", false);
    parent
        .dsl()
        .execute(
            "ALTER TABLE flyway_schema_history RENAME TO flyway_schema_history_account_service");
    int copied = 0;
    for (var resource :
        new PathMatchingResourcePatternResolver()
            .getResources("classpath*:db/migration/**/*.sql")) {
      String filename = java.util.Objects.requireNonNull(resource.getFilename());
      if (!PARENT_MIGRATION_SCRIPTS.contains(filename)) continue;
      try (var input = resource.getInputStream()) {
        Files.copy(input, parentMigrations.resolve(filename));
      }
      copied++;
    }
    assertThat(copied).isEqualTo(112);
    actualParentFlyway(parent, "filesystem:" + parentMigrations).migrate();
    var checksums =
        parent
            .dsl()
            .fetch(
                "SELECT version || ':' || script || ':' || checksum || ':' || success FROM flyway_schema_history_account_service WHERE version IS NOT NULL ORDER BY installed_rank")
            .getValues(0, String.class);
    assertThat(checksums).hasSize(112);
    var capture = retainedPhysicalRows(parent, "account_start_session_authority_captures");

    Flyway current =
        actualParentFlyway(parent, "classpath:db/migration", "classpath:db/migration/saga");
    current.migrate();
    current.validate();
    assertCurrentMigrationsInstalled(current);

    assertThat(
            parent
                .dsl()
                .fetch(
                    "SELECT version || ':' || script || ':' || checksum || ':' || success FROM flyway_schema_history_account_service WHERE version IS NOT NULL AND installed_rank <= 112 ORDER BY installed_rank")
                .getValues(0, String.class))
        .isEqualTo(checksums);
    assertThat(retainedPhysicalRows(parent, "account_start_session_authority_captures"))
        .isEqualTo(capture);
    assertThat(
            parent
                .dsl()
                .fetchSingle(
                    "SELECT to_regclass('account_start_session_admission_protections')::TEXT")
                .get(0, String.class))
        .isNotNull();
  }

  private static void assertCurrentMigrationsInstalled(Flyway flyway) {
    var migrations = flyway.info().all();
    assertThat(migrations)
        .isNotEmpty()
        .allSatisfy(
            migration ->
                assertThat(migration.getState())
                    .as(migration.getScript())
                    .isEqualTo(MigrationState.SUCCESS));
    assertThat(migrations)
        .extracting(MigrationInfo::getVersion)
        .contains(MigrationVersion.fromVersion("1003"), MigrationVersion.fromVersion("1004"));
  }

  private static Flyway actualParentFlyway(Fixture fixture, String... locations) {
    return actualParentFlyway(fixture.dataSource(), locations);
  }

  private static Flyway actualParentFlyway(DriverManagerDataSource source, String... locations) {
    return Flyway.configure()
        .dataSource(source)
        .schemas(source.getSchema())
        .defaultSchema(source.getSchema())
        .placeholders(Map.of("serviceSchema", source.getSchema()))
        .locations(locations)
        .table("flyway_schema_history_account_service")
        .load();
  }

  @Test
  void forwardMigrationAddsSourceProtectionsWithoutChangingRetainedCanonicalParentEvidence() {
    Fixture parent = fixture(60_000L, "160.1", false);
    // A passive non-authorizing signer baseline is explicitly stipulated, as are the upstream
    // capture/World rows in this fixture. This does not establish authenticated producer proof.
    parent
        .transaction()
        .executeWithoutResult(
            status -> {
              parent.dsl().execute("SET LOCAL session_replication_role = replica");
              parent
                  .dsl()
                  .execute(
                      "INSERT INTO account_jwt_signer_desired_states "
                          + "(environment_id, cluster_id, kubernetes_namespace, custody_mode) "
                          + "VALUES ('test', 'test-cluster', 'test', 'INTERIM_ACCOUNT_ONLY_MOUNTED_FALLBACK')");
              parent.dsl().execute("SET LOCAL session_replication_role = origin");
            });
    List<String> retainedTables =
        List.of(
            "account_jwt_signer_desired_states",
            "account_draft_authorization_source_locks",
            "account_start_session_authority_captures",
            "account_start_session_operator_authorizations",
            "account_start_session_world_participations",
            "account_start_session_world_participation_sources",
            "account_start_session_world_original_attempt_evidence",
            "account_start_session_world_participation_settlements");
    Map<String, String> beforeRows = new LinkedHashMap<>();
    Map<String, Long> beforeOids = new LinkedHashMap<>();
    for (String table : retainedTables) {
      beforeRows.put(table, retainedPhysicalRows(parent, table));
      beforeOids.put(
          table,
          parent.dsl().fetchSingle("SELECT ?::regclass::oid::bigint", table).get(0, Long.class));
    }
    List<String> retainedFunctions =
        List.of(
            "account_start_session_world_participation_insert_guard()",
            "account_start_session_operator_authorization_guard()",
            "account_jwt_signer_promotion_insert_guard()");
    Map<String, String> beforeDefinitions = new LinkedHashMap<>();
    for (String signature : retainedFunctions) {
      beforeDefinitions.put(
          signature,
          parent
              .dsl()
              .fetchSingle("SELECT pg_get_functiondef(?::regprocedure)", signature)
              .get(0, String.class));
      beforeOids.put(
          signature,
          parent
              .dsl()
              .fetchSingle("SELECT ?::regprocedure::oid::bigint", signature)
              .get(0, Long.class));
    }
    var checksums =
        parent
            .dsl()
            .fetch(
                "SELECT version, checksum FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank");
    assertThat(
            parent
                .dsl()
                .fetchSingle(
                    "SELECT to_regclass('account_start_session_admission_protections')::TEXT")
                .get(0, String.class))
        .isNull();
    Flyway.configure()
        .dataSource(parent.dataSource())
        .schemas(parent.dataSource().getSchema())
        .defaultSchema(parent.dataSource().getSchema())
        .placeholders(Map.of("serviceSchema", parent.dataSource().getSchema()))
        .locations("classpath:db/migration")
        .target(MigrationVersion.fromVersion("1003"))
        .load()
        .migrate();
    for (String table : retainedTables) {
      assertThat(retainedPhysicalRows(parent, table)).isEqualTo(beforeRows.get(table));
      assertThat(
              parent.dsl().fetchSingle("SELECT ?::regclass::oid::bigint", table).get(0, Long.class))
          .isEqualTo(beforeOids.get(table));
    }
    for (String signature : retainedFunctions) {
      assertThat(
              parent
                  .dsl()
                  .fetchSingle("SELECT pg_get_functiondef(?::regprocedure)", signature)
                  .get(0, String.class))
          .isEqualTo(beforeDefinitions.get(signature));
      assertThat(
              parent
                  .dsl()
                  .fetchSingle("SELECT ?::regprocedure::oid::bigint", signature)
                  .get(0, Long.class))
          .isEqualTo(beforeOids.get(signature));
    }
    for (var row : checksums) {
      assertThat(
              parent
                  .dsl()
                  .fetchSingle(
                      "SELECT checksum FROM flyway_schema_history WHERE success AND version = ?",
                      row.get("version", String.class))
                  .get(0, Integer.class))
          .isEqualTo(row.get("checksum", Integer.class));
    }
    for (String signature :
        List.of(
            "account_ss_admission_assert_current(account_start_session_admission_protections,boolean)",
            "account_ss_activation_assert_current(bytea,bytea,text,boolean)")) {
      String definition =
          parent
              .dsl()
              .fetchSingle("SELECT pg_get_functiondef(?::regprocedure)", signature)
              .get(0, String.class);
      assertThat(definition)
          .contains("issuanceOperationIdentity,issuanceOperationId")
          .doesNotContain("'projectionSchemaId', 'accountStartSessionRedemptionProjection'");
    }
    acquire(parent);
    assertThat(parent.projection()).containsExactly(parent.tuple());
    assertThat(
            parent
                .dsl()
                .fetchSingle(
                    "SELECT bundle_source_fence, issuance_fence FROM account_start_session_operator_authorizations WHERE control_plane_request_id = ?",
                    parent.requestId())
                .get("bundle_source_fence", String.class))
        .isEqualTo("13");
    assertThat(
            parent
                .dsl()
                .fetchSingle(
                    "SELECT issuance_fence FROM account_start_session_operator_authorizations WHERE control_plane_request_id = ?",
                    parent.requestId())
                .get(0, Long.class))
        .isEqualTo(9L);

    Fixture scalar = fixture(60_000L, null, true);
    assertThatThrownBy(() -> acquire(scalar))
        .hasMessageContaining(
            "Admission projection differs from complete original Account redemption");
  }

  private static String retainedPhysicalRows(Fixture fixture, String table) {
    return fixture
        .dsl()
        .fetchSingle(
            "SELECT coalesce(jsonb_agg(value ORDER BY value::TEXT), '[]'::JSONB)::TEXT "
                + "FROM (SELECT to_jsonb(original) || jsonb_build_object("
                + "'_xmin', original.xmin::TEXT, '_ctid', original.ctid::TEXT) AS value FROM "
                + table
                + " original) retained")
        .get(0, String.class);
  }

  @Test
  void fullTupleDoesNotBypassIndependentOwnerIssuanceOperationBinding() {
    Fixture fixture = fixture();
    // Stipulate a contradictory retained owner row to isolate this SQL integrity conjunct.
    // The parent immutable writer remains enabled outside this deliberate corruption fixture.
    fixture
        .transaction()
        .executeWithoutResult(
            status -> {
              fixture.dsl().execute("SET LOCAL session_replication_role = replica");
              fixture
                  .dsl()
                  .execute(
                      "UPDATE account_start_session_operator_authorizations SET issuance_operation_id = ? "
                          + "WHERE control_plane_request_id = ?",
                      UUID.randomUUID(),
                      fixture.requestId());
              fixture.dsl().execute("SET LOCAL session_replication_role = origin");
            });
    assertThat(fixture.projection()).containsExactly(fixture.tuple());
    assertThatThrownBy(() -> acquire(fixture))
        .hasMessageContaining(
            "Admission projection differs from complete original Account redemption");
    assertThat(
            fixture
                .dsl()
                .fetchSingle("SELECT count(*) FROM account_start_session_admission_protections")
                .get(0, Long.class))
        .isZero();
  }

  @Test
  void installsAdditiveHooksWithoutRemovingExistingOwnerChecks() {
    Fixture fixture = fixture();
    for (String function :
        List.of(
            "account_control_ui_hold_required_sources(text[])",
            "account_draft_authorization_source_change_guard()",
            "account_hosted_terms_disclosure_handoff_guard()")) {
      String definition =
          fixture
              .dsl()
              .fetchSingle("SELECT pg_get_functiondef(?::regprocedure)", function)
              .get(0, String.class);
      assertThat(definition)
          .contains(
              "account_ss_admission_assert_no_pending",
              "account_start_session_world_participations",
              "account_selected_owner_intake_source_read_is_pending",
              "account_selected_owner_intake_source_read_reservations");
    }
    String hold =
        fixture
            .dsl()
            .fetchSingle(
                "SELECT pg_get_functiondef('account_control_ui_hold_required_sources(text[])'::regprocedure)")
            .get(0, String.class);
    assertThat(hold)
        .contains(
            "account_draft_authorization_sources",
            "account_selected_publication_sources",
            "account_game_logic_intake_sources",
            "account_game_logic_intake_source_read_sources");
    for (String guard :
        List.of(
            "account_ss_admission_assert_current(account_start_session_admission_protections,boolean)",
            "account_ss_admission_settlement_insert_guard()")) {
      assertThat(
              fixture
                  .dsl()
                  .fetchSingle("SELECT pg_get_functiondef(?::regprocedure)", guard)
                  .get(0, String.class))
          .contains("account_ss_admission_canonical_hold_next_pointer(original)");
    }
  }

  @Test
  void exactCurrentReadPreservesIdentityWithoutAllocatingAndWorldCommitCannotReleaseIt() {
    Fixture fixture = fixture();
    assertThat(fixture.expiry().getNano() % 1_000_000).isNotZero();
    acquire(fixture);
    Long sequence = sequenceValue(fixture);
    for (int retry = 0; retry < 2; retry++) {
      var current =
          fixture
              .transaction()
              .execute(
                  status ->
                      fixture
                          .dsl()
                          .fetchSingle(
                              "SELECT * FROM account_ss_admission_read_current_exact(?, ?, ?)",
                              fixture.protectionId(),
                              1L,
                              fixture.request()));
      assertThat(current.get("protection_id", UUID.class)).isEqualTo(fixture.protectionId());
      assertThat(current.get("request_binding_bytes", byte[].class))
          .containsExactly(fixture.request());
      OffsetDateTime retainedExpiry =
          current.get("original_lease_expires_at", OffsetDateTime.class);
      assertThat(retainedExpiry).isNotNull();
      assertThat(retainedExpiry.toInstant()).isEqualTo(fixture.expiry().toInstant());
    }
    assertThat(sequenceValue(fixture)).isEqualTo(sequence);
    assertThat(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT account_start_session_world_participation_is_settled(?)",
                    fixture.worldId())
                .get(0, Boolean.class))
        .isTrue();
    assertThatThrownBy(
            () ->
                fixture
                    .transaction()
                    .executeWithoutResult(
                        status ->
                            fixture
                                .dsl()
                                .execute(
                                    "SELECT account_control_ui_hold_required_sources(ARRAY[?]::text[])",
                                    fixture.source().key())))
        .hasMessageContaining("Original StartSession admission protection remains pending");
  }

  @Test
  void changedSubmillisecondOriginalExpiryCannotRecoverRetainedProtection() {
    Fixture fixture = fixture();
    acquire(fixture);
    var storedRowBefore =
        fixture
            .dsl()
            .fetchSingle(
                "SELECT * FROM account_start_session_admission_protections WHERE protection_id = ?",
                fixture.protectionId());
    DateTimeFormatter expiryFormat =
        DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);
    String originalExpiryText = expiryFormat.format(fixture.expiry().toInstant());
    String changedExpiryText = expiryFormat.format(fixture.expiry().plusNanos(1_000).toInstant());
    byte[] changedRequest =
        new String(fixture.request(), StandardCharsets.UTF_8)
            .replace(originalExpiryText, changedExpiryText)
            .getBytes(StandardCharsets.UTF_8);
    assertThat(changedRequest).isNotEqualTo(fixture.request());

    assertThatThrownBy(
            () ->
                fixture
                    .transaction()
                    .executeWithoutResult(
                        status ->
                            fixture
                                .dsl()
                                .execute(
                                    "SELECT * FROM account_ss_admission_read_current_exact(?, ?, ?)",
                                    fixture.protectionId(),
                                    1L,
                                    changedRequest)))
        .hasMessageContaining("Original admission recovery binding differs");
    var storedRowAfter =
        fixture
            .dsl()
            .fetchSingle(
                "SELECT * FROM account_start_session_admission_protections WHERE protection_id = ?",
                fixture.protectionId());
    assertThat(storedRowAfter).isEqualTo(storedRowBefore);
    assertThat(
            fixture
                .dsl()
                .fetchSingle("SELECT account_ss_admission_is_settled(?)", fixture.protectionId())
                .get(0, Boolean.class))
        .isFalse();
  }

  @Test
  void forgedWorldHoldDigestWithoutCanonicalPrefixCannotBecomeProtection() {
    Fixture fixture = fixture();
    String holdText = new String(fixture.hold(), StandardCharsets.UTF_8);
    String digestMarker = "\"holdBindingDigest\":\"";
    int digestStart = holdText.indexOf(digestMarker) + digestMarker.length();
    int digestEnd = holdText.indexOf('"', digestStart);
    String validDigest = holdText.substring(digestStart, digestEnd);
    assertThat(validDigest).startsWith("sha256:");
    String forgedHoldText =
        holdText.substring(0, digestStart)
            + validDigest.substring("sha256:".length())
            + holdText.substring(digestEnd);
    byte[] forgedHold = forgedHoldText.getBytes(StandardCharsets.UTF_8);
    byte[] forgedRequest =
        new String(fixture.request(), StandardCharsets.UTF_8)
            .replace(base64(fixture.hold()), base64(forgedHold))
            .getBytes(StandardCharsets.UTF_8);

    assertThatThrownBy(
            () ->
                fixture
                    .transaction()
                    .executeWithoutResult(
                        status -> insertProtection(fixture, 11L, forgedHold, forgedRequest)))
        .hasMessageContaining(
            "Original StartSession admission hold request differs from retained protection");
    assertThat(
            fixture
                .dsl()
                .fetchSingle("SELECT count(*) FROM account_start_session_admission_protections")
                .get(0, Long.class))
        .isZero();
  }

  @Test
  void pendingAdmissionExcludesRealSourceChangeCompletionAndDisclosureDispatch() {
    Fixture fixture = fixture();
    acquire(fixture);
    UUID changeId = UUID.randomUUID();
    fixture
        .transaction()
        .executeWithoutResult(
            status -> {
              insert(
                  fixture.dsl(),
                  "account_draft_authorization_source_changes",
                  "change_id",
                  changeId,
                  "binding",
                  bytes("change"),
                  "status",
                  "WAITING");
              insert(
                  fixture.dsl(),
                  "account_draft_authorization_changed_scopes",
                  "change_id",
                  changeId,
                  "source_key",
                  fixture.source().key());
            });
    assertThatThrownBy(
            () ->
                fixture
                    .transaction()
                    .executeWithoutResult(
                        status ->
                            fixture
                                .dsl()
                                .execute(
                                    "UPDATE account_draft_authorization_source_changes SET status = 'SOURCE_COMMITTED', committed_at = clock_timestamp() WHERE change_id = ?",
                                    changeId)))
        .hasMessageContaining("Original StartSession admission protection remains pending");
    UUID handoffId = UUID.randomUUID();
    fixture
        .transaction()
        .executeWithoutResult(
            status ->
                insert(
                    fixture.dsl(),
                    "account_hosted_terms_disclosure_handoffs",
                    "handoff_id",
                    handoffId,
                    "request_id",
                    UUID.randomUUID(),
                    "kind",
                    "CATALOG",
                    "source_key",
                    fixture.source().key(),
                    "predecessor_digest",
                    "sha256:" + "a".repeat(64),
                    "candidate_digest",
                    "sha256:" + "b".repeat(64),
                    "effective_at",
                    OffsetDateTime.now().plusDays(1),
                    "binding",
                    bytes("disclosure"),
                    "binding_digest",
                    "sha256:" + hash(bytes("disclosure")),
                    "status",
                    "PREPARED"));
    assertThatThrownBy(
            () ->
                fixture
                    .transaction()
                    .executeWithoutResult(
                        status ->
                            fixture
                                .dsl()
                                .execute(
                                    "UPDATE account_hosted_terms_disclosure_handoffs SET status = 'DISPATCH_AUTHORIZED', dispatch_attempts = 1 WHERE handoff_id = ?",
                                    handoffId)))
        .hasMessageContaining("Original StartSession admission protection remains pending");
  }

  @Test
  void rawChangedCaptureAndIncompleteChildrenCannotBecomeProtection() {
    Fixture changed = fixture();
    assertThatThrownBy(
            () ->
                changed
                    .transaction()
                    .executeWithoutResult(status -> insertProtection(changed, 99L)))
        .hasMessageContaining("retained owner bindings");
    Fixture incomplete = fixture();
    assertThatThrownBy(
            () ->
                incomplete
                    .transaction()
                    .executeWithoutResult(status -> insertProtection(incomplete, 11L)))
        .satisfies(
            failure ->
                assertDatabaseGuardFailure(
                    failure,
                    "Admission protection has incomplete or extra source children",
                    "23514"));
    assertThat(
            incomplete
                .dsl()
                .fetchSingle("SELECT count(*) FROM account_start_session_admission_protections")
                .get(0, Long.class))
        .isZero();
  }

  @Test
  void immutableRowsAndUnprovedTerminalCannotReleaseProtection() {
    Fixture fixture = fixture();
    acquire(fixture);
    for (String table :
        List.of(
            "account_start_session_admission_protections",
            "account_start_session_admission_protection_sources")) {
      assertThatThrownBy(() -> fixture.dsl().execute("DELETE FROM " + table))
          .hasMessageContaining("append-only");
      assertThatThrownBy(() -> fixture.dsl().execute("TRUNCATE " + table + " CASCADE"))
          .hasMessageContaining("append-only");
    }
    assertThatThrownBy(
            () ->
                fixture
                    .dsl()
                    .execute(
                        "UPDATE account_start_session_admission_protection_sources SET source_evidence = ?",
                        bytes("changed")))
        .hasMessageContaining("append-only");
    assertThatThrownBy(
            () ->
                fixture
                    .dsl()
                    .execute(
                        "INSERT INTO account_start_session_admission_protection_settlements (protection_id, outcome, terminal_bytes, terminal_digest) VALUES (?, 'ABORTED', ?, ?)",
                        fixture.protectionId(),
                        malformedSettlementBytes(),
                        "sha256:" + hash(malformedSettlementBytes())))
        .hasMessageContaining("not the closed envelope");
    assertThat(
            fixture
                .dsl()
                .fetchSingle("SELECT account_ss_admission_is_settled(?)", fixture.protectionId())
                .get(0, Boolean.class))
        .isFalse();
  }

  @Test
  void genuineFramedTerminalReceiptsReleaseRequiredSource() {
    for (GameSessionCanonicalInitialAdmissionOwnerProof.Outcome outcome :
        List.of(
            GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED,
            GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.ABORTED)) {
      Fixture fixture = fixture();
      acquire(fixture);
      assertThatThrownBy(
              () ->
                  fixture
                      .dsl()
                      .execute(
                          "SELECT account_control_ui_hold_required_sources(ARRAY[?]::text[])",
                          fixture.source().key()))
          .hasMessageContaining("Original StartSession admission protection remains pending");
      var proof =
          new GameSessionCanonicalInitialAdmissionOwnerProof(
              WorldCanonicalInitialAdmissionHold.HoldIdentity.fromStored(fixture.hold()),
              outcome,
              outcome == GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED
                  ? 1L
                  : null,
              outcome == GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED
                  ? 1L
                  : null,
              "sha256:" + "a".repeat(64),
              outcome == GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.ABORTED,
              Instant.now());
      byte[] ownerProof = GameSessionCanonicalInitialAdmissionOwnerProofCodec.canonicalBytes(proof);
      long fence =
          fixture
              .dsl()
              .fetchSingle(
                  "SELECT protection_fence FROM account_start_session_admission_protections WHERE protection_id = ?",
                  fixture.protectionId())
              .get(0, Long.class);
      byte[] evidence =
          json(
              Map.of(
                  "schema", "account-start-session-admission-protection-evidence/v1",
                  "accountProtectionId", fixture.protectionId().toString(),
                  "accountProtectionFence", Long.toString(fence),
                  "originalSourceCaptureReferenceBytesBase64", base64(bytes("{}")),
                  "originalSourceCaptureReferenceSha256", hash(bytes("{}")),
                  "requestBytesBase64", base64(fixture.request()),
                  "sourceEvidenceVector", List.of(base64(fixture.source().canonicalBytes()))));
      byte[] terminal =
          json(
              Map.of(
                  "schema", "account-start-session-admission-protection-settlement/v1",
                  "canonicalAccountProtectionEvidenceBytesBase64", base64(evidence),
                  "canonicalGameSessionOwnerProofBytesBase64", base64(ownerProof)));
      insert(
          fixture.dsl(),
          "account_start_session_admission_protection_settlements",
          "protection_id",
          fixture.protectionId(),
          "outcome",
          outcome.name(),
          "terminal_bytes",
          terminal,
          "terminal_digest",
          "sha256:" + hash(terminal));
      assertThat(
              fixture
                  .dsl()
                  .fetchSingle("SELECT account_ss_admission_is_settled(?)", fixture.protectionId())
                  .get(0, Boolean.class))
          .isTrue();
      fixture
          .dsl()
          .execute(
              "SELECT account_control_ui_hold_required_sources(ARRAY[?]::text[])",
              fixture.source().key());
    }
  }

  @Test
  void expiredOriginalCannotRecoverEvenWhenRetainedIdentityAndSourcesRemain() {
    Fixture fixture = fixture();
    acquire(fixture);
    // Stipulate passage of the immutable original expiry without changing protection:
    // rewriting the fixture row requires disabling the immutable upstream/new triggers.
    fixture
        .transaction()
        .executeWithoutResult(
            status -> {
              fixture.dsl().execute("SET LOCAL session_replication_role = replica");
              fixture
                  .dsl()
                  .execute(
                      "UPDATE account_start_session_admission_protections SET original_lease_expires_at = date_trunc('milliseconds', clock_timestamp() - INTERVAL '1 second') WHERE protection_id = ?",
                      fixture.protectionId());
              fixture.dsl().execute("SET LOCAL session_replication_role = origin");
            });
    assertThatThrownBy(
            () ->
                fixture
                    .transaction()
                    .executeWithoutResult(
                        status ->
                            fixture
                                .dsl()
                                .execute(
                                    "SELECT * FROM account_ss_admission_read_current_exact(?, ?, ?)",
                                    fixture.protectionId(),
                                    1L,
                                    fixture.request())))
        .hasMessageContaining("not current");
    assertThat(
            fixture
                .dsl()
                .fetchSingle("SELECT account_ss_admission_is_settled(?)", fixture.protectionId())
                .get(0, Boolean.class))
        .isFalse();
  }

  @Test
  void currentRecoveryRechecksDatabaseExpiryAfterTheCallerWaitsForOriginalCaptureLock()
      throws Exception {
    Fixture fixture = fixture(3_000L);
    acquire(fixture);
    var executor = Executors.newSingleThreadExecutor();
    CountDownLatch entered = new CountDownLatch(1);
    try (Connection lock = fixture.dataSource().getConnection()) {
      lock.setAutoCommit(false);
      DSL.using(lock, SQLDialect.POSTGRES)
          .fetch(
              "SELECT control_plane_request_id FROM account_start_session_authority_captures WHERE control_plane_request_id = ? FOR UPDATE",
              fixture.requestId());
      var waiting =
          executor.submit(
              () ->
                  fixture
                      .transaction()
                      .executeWithoutResult(
                          status -> {
                            entered.countDown();
                            // The currentness producer's original-capture lock can wait before the
                            // V165
                            // boundary. V165 itself uses NOWAIT to avoid reversing source lock
                            // order.
                            fixture
                                .dsl()
                                .fetch(
                                    "SELECT control_plane_request_id FROM account_start_session_authority_captures WHERE control_plane_request_id = ? FOR UPDATE",
                                    fixture.requestId());
                            fixture
                                .dsl()
                                .execute(
                                    "SELECT * FROM account_ss_admission_read_current_exact(?, ?, ?)",
                                    fixture.protectionId(),
                                    1L,
                                    fixture.request());
                          }));
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      long expiryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (fixture
              .dsl()
              .fetchSingle("SELECT clock_timestamp() >= ?::timestamptz", fixture.expiry())
              .get(0, Boolean.class)
              .equals(false)
          && System.nanoTime() < expiryDeadline) {
        Thread.sleep(20L);
      }
      assertThat(
              fixture
                  .dsl()
                  .fetchSingle("SELECT clock_timestamp() >= ?::timestamptz", fixture.expiry())
                  .get(0, Boolean.class))
          .isTrue();
      assertThat(waiting.isDone()).isFalse();
      lock.commit();
      assertThatThrownBy(() -> waiting.get(5, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasStackTraceContaining("not current");
    } finally {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
    assertThat(
            fixture
                .dsl()
                .fetchSingle("SELECT account_ss_admission_is_settled(?)", fixture.protectionId())
                .get(0, Boolean.class))
        .isFalse();
  }

  @Test
  void acquisitionRejectsUnsettleableHoldRequestAndSourceSnapshotBounds() throws Exception {
    Fixture fixture = fixture();
    byte[] oversizedHold =
        bytes(new String(fixture.hold(), StandardCharsets.UTF_8) + " ".repeat(65537));
    Map<String, Object> oversizedHoldRequest =
        tools.jackson.databind.json.JsonMapper.builder()
            .build()
            .readValue(fixture.request(), new tools.jackson.core.type.TypeReference<>() {});
    oversizedHoldRequest.put("worldAdmissionHoldIdentityBytesBase64", base64(oversizedHold));
    assertDatabaseGuardFailure(
        () ->
            fixture
                .transaction()
                .executeWithoutResult(
                    status ->
                        insertProtection(fixture, 11L, oversizedHold, json(oversizedHoldRequest))),
        "23514");
    assertDatabaseGuardFailure(
        () ->
            fixture
                .transaction()
                .executeWithoutResult(
                    status ->
                        insertProtection(
                            fixture,
                            11L,
                            fixture.hold(),
                            bytes(
                                new String(fixture.request(), StandardCharsets.UTF_8)
                                    + " ".repeat(786433)))),
        "23514");

    for (List<String> vector :
        List.of(
            List.<String>of(),
            java.util.Collections.nCopies(257, base64(fixture.source().canonicalBytes())),
            List.of(base64(new byte[131073])),
            List.of(base64(fixture.source().canonicalBytes()) + "\n"))) {
      replaceSnapshot(
          fixture,
          json(
              Map.of(
                  "schema",
                  "account-start-session-authority-snapshot/v1",
                  "controlPlaneRequestId",
                  fixture.requestId(),
                  "sourceVector",
                  vector)));
      assertDatabaseGuardFailure(() -> acquire(fixture), "23514");
    }
    assertDatabaseGuardFailure(
        () ->
            replaceSnapshot(
                fixture,
                bytes(
                    new String(
                            json(
                                Map.of(
                                    "schema",
                                    "account-start-session-authority-snapshot/v1",
                                    "controlPlaneRequestId",
                                    fixture.requestId(),
                                    "sourceVector",
                                    List.of(base64(fixture.source().canonicalBytes())))),
                            StandardCharsets.UTF_8)
                        + " ".repeat(245761))),
        "23514");
  }

  @Test
  void acquisitionRejectsEveryHoldShapeThatSettlementCannotAccept() throws Exception {
    Fixture fixture = fixture();
    var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
    Map<String, Object> originalHold =
        mapper.readValue(fixture.hold(), new tools.jackson.core.type.TypeReference<>() {});
    byte[] originalHoldRequest =
        Base64.getDecoder().decode((String) originalHold.get("requestBytesBase64"));
    Map<String, Object> requestEnvelope =
        mapper.readValue(originalHoldRequest, new tools.jackson.core.type.TypeReference<>() {});
    @SuppressWarnings("unchecked")
    Map<String, Object> requestFields = (Map<String, Object>) requestEnvelope.get("request");

    Map<String, Object> uppercaseId = new LinkedHashMap<>(originalHold);
    uppercaseId.put(
        "holdId", ((String) uppercaseId.get("holdId")).toUpperCase(java.util.Locale.ROOT));
    assertMalformedHoldRejected(fixture, json(uppercaseId));

    Map<String, Object> extraEnvelope = new LinkedHashMap<>(requestEnvelope);
    extraEnvelope.put("extra", "unsettleable");
    assertMalformedHoldRejected(fixture, holdWithRequest(originalHold, json(extraEnvelope)));

    Map<String, Object> extraFields = new LinkedHashMap<>(requestFields);
    extraFields.put("extra", "unsettleable");
    assertMalformedHoldRejected(fixture, holdWithRequest(originalHold, holdRequest(extraFields)));

    Map<String, Object> wrongType = new LinkedHashMap<>(requestFields);
    wrongType.put("activeLifecycleEpoch", 1);
    assertMalformedHoldRejected(fixture, holdWithRequest(originalHold, holdRequest(wrongType)));

    assertMalformedHoldRejected(
        fixture,
        holdWithRequest(
            originalHold, bytes(new String(originalHoldRequest, StandardCharsets.UTF_8) + " ")));
    assertMalformedHoldRejected(
        fixture, bytes(new String(fixture.hold(), StandardCharsets.UTF_8) + " "));

    Map<String, Object> wrongOrigin = new LinkedHashMap<>(requestFields);
    wrongOrigin.put("initialAdmissionOrigin", "EXPECT_CLOSED");
    assertMalformedHoldRejected(fixture, holdWithRequest(originalHold, holdRequest(wrongOrigin)));

    Map<String, Object> wrongPointer = new LinkedHashMap<>(requestFields);
    wrongPointer.put("expectedPriorPointerVersion", "1");
    assertMalformedHoldRejected(fixture, holdWithRequest(originalHold, holdRequest(wrongPointer)));

    assertThat(
            fixture
                .dsl()
                .fetchSingle("SELECT count(*) FROM account_start_session_admission_protections")
                .get(0, Long.class))
        .isZero();
  }

  private static byte[] holdRequest(Map<String, Object> fields) {
    return json(
        Map.of("schema", "world-canonical-initial-admission-hold-request/v1", "request", fields));
  }

  private static byte[] holdWithRequest(Map<String, Object> originalHold, byte[] requestBytes) {
    Map<String, Object> hold = new LinkedHashMap<>(originalHold);
    hold.put("requestBytesBase64", base64(requestBytes));
    hold.put("holdBindingDigest", "sha256:" + hash(requestBytes));
    return json(hold);
  }

  private static void assertMalformedHoldRejected(Fixture fixture, byte[] hold) throws Exception {
    Map<String, Object> request =
        tools.jackson.databind.json.JsonMapper.builder()
            .build()
            .readValue(fixture.request(), new tools.jackson.core.type.TypeReference<>() {});
    request.put("worldAdmissionHoldIdentityBytesBase64", base64(hold));
    assertDatabaseGuardFailure(
        () ->
            fixture
                .transaction()
                .executeWithoutResult(
                    status -> insertProtection(fixture, 11L, hold, json(request))),
        "23514");
  }

  @Test
  void acquisitionAccepts256CompleteSourcesAndRejects257CompleteSources() {
    for (int sourceCount : List.of(256, 257)) {
      Fixture fixture = fixture();
      var sources = new java.util.ArrayList<SourceEvidence>();
      sources.add(fixture.source());
      for (int index = 1; index < sourceCount; index++) {
        sources.add(
            new SourceEvidence(
                SourceKind.HOSTED_TERMS,
                "bounded-source-" + index,
                "1",
                "1",
                null,
                null,
                bytes("captured source")));
      }
      sources.sort(java.util.Comparator.comparing(SourceEvidence::key));
      fixture
          .transaction()
          .executeWithoutResult(
              status -> {
                fixture.dsl().execute("SET LOCAL session_replication_role = replica");
                for (SourceEvidence source : sources) {
                  if (source.key().equals(fixture.source().key())) continue;
                  fixture
                      .dsl()
                      .execute(
                          "INSERT INTO account_draft_authorization_source_locks(source_key) VALUES (?)",
                          source.key());
                  insert(
                      fixture.dsl(),
                      "account_start_session_world_participation_sources",
                      "participation_id",
                      fixture.worldId(),
                      "source_key",
                      source.key(),
                      "source_evidence",
                      source.canonicalBytes());
                }
                fixture.dsl().execute("SET LOCAL session_replication_role = origin");
              });
      replaceSnapshot(
          fixture,
          json(
              Map.of(
                  "schema",
                  "account-start-session-authority-snapshot/v1",
                  "controlPlaneRequestId",
                  fixture.requestId(),
                  "sourceVector",
                  sources.stream().map(source -> base64(source.canonicalBytes())).toList())));
      if (sourceCount == 256) {
        acquire(fixture);
        assertThat(
                fixture
                    .dsl()
                    .fetchSingle(
                        "SELECT count(*) FROM account_start_session_admission_protection_sources WHERE protection_id = ?",
                        fixture.protectionId())
                    .get(0, Long.class))
            .isEqualTo(256L);
      } else {
        assertDatabaseGuardFailure(() -> acquire(fixture), "23514");
      }
    }
  }

  @Test
  void acquisitionRequiresCanonicalUtf16OrderForMultipleUnicodeSources() {
    Fixture fixture = fixture();
    var supplementary =
        new SourceEvidence(
            SourceKind.HOSTED_TERMS,
            "terms-\uD800\uDC00",
            "1",
            "1",
            null,
            null,
            bytes("supplementary"));
    var bmp =
        new SourceEvidence(
            SourceKind.HOSTED_TERMS, "terms-\uE000", "1", "1", null, null, bytes("BMP"));
    var sources =
        java.util.stream.Stream.of(fixture.source(), supplementary, bmp)
            .sorted(java.util.Comparator.comparing(SourceEvidence::key))
            .toList();
    fixture
        .transaction()
        .executeWithoutResult(
            status -> {
              fixture.dsl().execute("SET LOCAL session_replication_role = replica");
              for (SourceEvidence source : List.of(supplementary, bmp)) {
                fixture
                    .dsl()
                    .execute(
                        "INSERT INTO account_draft_authorization_source_locks(source_key) VALUES (?)",
                        source.key());
                insert(
                    fixture.dsl(),
                    "account_start_session_world_participation_sources",
                    "participation_id",
                    fixture.worldId(),
                    "source_key",
                    source.key(),
                    "source_evidence",
                    source.canonicalBytes());
              }
              fixture.dsl().execute("SET LOCAL session_replication_role = origin");
            });
    var reversed = new java.util.ArrayList<>(sources);
    java.util.Collections.reverse(reversed);
    replaceSnapshot(
        fixture,
        json(
            Map.of(
                "schema",
                "account-start-session-authority-snapshot/v1",
                "controlPlaneRequestId",
                fixture.requestId(),
                "sourceVector",
                reversed.stream().map(source -> base64(source.canonicalBytes())).toList())));
    assertDatabaseGuardFailure(() -> acquire(fixture), "23514");
    replaceSnapshot(
        fixture,
        json(
            Map.of(
                "schema",
                "account-start-session-authority-snapshot/v1",
                "controlPlaneRequestId",
                fixture.requestId(),
                "sourceVector",
                sources.stream().map(source -> base64(source.canonicalBytes())).toList())));
    acquire(fixture);
    assertThat(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT count(*) FROM account_start_session_admission_protection_sources WHERE protection_id = ?",
                    fixture.protectionId())
                .get(0, Long.class))
        .isEqualTo(3L);
  }

  private static void assertDatabaseGuardFailure(Runnable operation, String expectedSqlState) {
    assertThatThrownBy(operation::run)
        .isInstanceOf(RuntimeException.class)
        .satisfies(failure -> assertDatabaseGuardFailure(failure, "", expectedSqlState));
  }

  private static void replaceSnapshot(Fixture fixture, byte[] snapshot) {
    fixture
        .transaction()
        .executeWithoutResult(
            status -> {
              fixture.dsl().execute("SET LOCAL session_replication_role = replica");
              fixture
                  .dsl()
                  .execute(
                      "UPDATE account_start_session_authority_captures SET canonical_snapshot_bytes = ?, snapshot_sha256 = ? WHERE control_plane_request_id = ?",
                      snapshot,
                      hash(snapshot),
                      fixture.requestId());
              fixture.dsl().execute("SET LOCAL session_replication_role = origin");
            });
  }

  private static void acquire(Fixture fixture) {
    fixture
        .transaction()
        .executeWithoutResult(
            status -> {
              insertProtection(fixture, 11L);
              fixture
                  .dsl()
                  .execute(
                      "INSERT INTO account_start_session_admission_protection_sources(protection_id, source_key, source_evidence) SELECT ?, source_key, source_evidence FROM account_start_session_world_participation_sources WHERE participation_id = ?",
                      fixture.protectionId(),
                      fixture.worldId());
            });
  }

  private static void insertProtection(Fixture fixture, long version) {
    insertProtection(fixture, version, fixture.hold(), fixture.request());
  }

  private static void insertProtection(
      Fixture fixture, long version, byte[] holdIdentity, byte[] request) {
    insert(
        fixture.dsl(),
        "account_start_session_admission_protections",
        "protection_id",
        fixture.protectionId(),
        "control_plane_request_id",
        fixture.requestId(),
        "original_post_authorization_tuple",
        fixture.tuple(),
        "account_redemption_projection",
        fixture.projection(),
        "game_session_owner_mutation_id",
        fixture.mutationId(),
        "game_session_owner_attempt_id",
        fixture.attemptId(),
        "game_session_owner_fence",
        47L,
        "original_lease_expires_at",
        fixture.expiry(),
        "account_world_participation_id",
        fixture.worldId(),
        "account_world_participation_fence",
        1L,
        "target_namespace",
        "test",
        "canonical_tenant_id",
        fixture.tenantId(),
        "canonical_game_instance_id",
        fixture.instanceId(),
        "capture_source_version",
        version,
        "capture_source_fence",
        13L,
        "capture_sha256",
        hash(bytes("{}")),
        "world_admission_hold_identity_bytes",
        holdIdentity,
        "request_binding_bytes",
        request,
        "request_binding_digest",
        "sha256:" + hash(request));
  }

  private static Fixture fixture() {
    return fixture(60_000L);
  }

  private static Fixture fixture(long leaseMillis) {
    return fixture(leaseMillis, null, false);
  }

  private static Fixture fixture(long leaseMillis, String target, boolean scalarProjection) {
    String schema = "ss_admission_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource source =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    source.setSchema(schema);
    var migration =
        Flyway.configure()
            .dataSource(source)
            .schemas(schema)
            .defaultSchema(schema)
            .placeholders(Map.of("serviceSchema", schema))
            .locations("classpath:db/migration");
    if (target != null) migration.target(MigrationVersion.fromVersion(target));
    migration.load().migrate();
    DSLContext dsl = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
    TransactionTemplate transaction =
        new TransactionTemplate(new DataSourceTransactionManager(source));
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    OffsetDateTime now =
        dsl.fetchSingle(
                "SELECT date_trunc('milliseconds', clock_timestamp()) + INTERVAL '123 microseconds'")
            .get(0, OffsetDateTime.class);
    OffsetDateTime capturedAt = now.truncatedTo(ChronoUnit.MILLIS);
    UUID tenant = UUID.randomUUID();
    UUID instance = UUID.randomUUID();
    UUID actor = UUID.randomUUID();
    UUID mutation = UUID.randomUUID();
    UUID attempt = UUID.randomUUID();
    UUID world = UUID.randomUUID();
    UUID issuance = UUID.randomUUID();
    String requestId = "original/" + UUID.randomUUID();
    SourceEvidence evidence =
        new SourceEvidence(
            SourceKind.HOSTED_TERMS, "test-terms", "1", "1", null, null, bytes("terms"));
    Map<String, Object> authorityBundle =
        Map.of("issuanceOperationIdentity", Map.of("issuanceOperationId", issuance.toString()));
    byte[] tuple =
        json(
            Map.of(
                "preAuthorizationReservationTuple",
                Map.of(),
                "authorizationReferenceFingerprint",
                "arfp/v1/key-1/" + "a".repeat(64),
                "issuanceFence",
                "9",
                "authorityEvidenceBundle",
                authorityBundle));
    // Upstream authority is explicitly stipulated; current SQL still requires exact tuple bytes.
    byte[] projection =
        scalarProjection
            ? json(
                Map.of(
                    "projectionSchemaId",
                    "accountStartSessionRedemptionProjection",
                    "projectionSchemaVersion",
                    "1",
                    "authorizationReferenceFingerprint",
                    "arfp/v1/key-1/" + "a".repeat(64),
                    "authorityEvidenceBundle",
                    authorityBundle,
                    "issuanceOperationId",
                    issuance.toString(),
                    "issuanceFence",
                    9L))
            : tuple.clone();
    var holdRequest =
        new WorldCanonicalInitialAdmissionHold.Request(
            "test",
            tenant,
            "test-world",
            UUID.randomUUID(),
            UUID.randomUUID(),
            "SHARED",
            instance,
            UUID.randomUUID(),
            1L,
            "separate-initial-admission-identity",
            "b".repeat(64),
            WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.NO_PRIOR_POINTER,
            1L,
            null);
    byte[] hold =
        new WorldCanonicalInitialAdmissionHold.HoldIdentity(
                holdRequest, UUID.randomUUID(), UUID.randomUUID())
            .canonicalBytes();
    OffsetDateTime expiry = now.plusNanos(leaseMillis * 1_000_000L);
    byte[] request =
        json(
            Map.of(
                "schema",
                "account-start-session-admission-protection-request/v1",
                "originalPostAuthorizationTupleBytesBase64",
                base64(tuple),
                "accountRedemptionProjectionBytesBase64",
                base64(projection),
                "gameSessionOwnerMutationId",
                mutation.toString(),
                "gameSessionOwnerAttemptId",
                attempt.toString(),
                "gameSessionOwnerFence",
                "47",
                "originalLeaseExpiresAt",
                DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'")
                    .withZone(ZoneOffset.UTC)
                    .format(expiry.toInstant()),
                "accountWorldParticipationId",
                world.toString(),
                "accountWorldParticipationFence",
                "1",
                "worldAdmissionHoldIdentityBytesBase64",
                base64(hold)));
    Fixture fixture =
        new Fixture(
            dsl,
            transaction,
            source,
            UUID.randomUUID(),
            requestId,
            tenant,
            instance,
            mutation,
            attempt,
            world,
            expiry,
            evidence,
            tuple,
            projection,
            hold,
            request);
    byte[] snapshot =
        json(
            Map.of(
                "schema",
                "account-start-session-authority-snapshot/v1",
                "controlPlaneRequestId",
                requestId,
                "sourceVector",
                List.of(base64(evidence.canonicalBytes()))));
    transaction.executeWithoutResult(
        status -> {
          dsl.execute("SET LOCAL session_replication_role = replica");
          insert(
              dsl,
              "account_canonical_tenant_identity_claims",
              "canonical_tenant_id",
              tenant,
              "identity_kind",
              "APPROVED_RETAINED",
              "source_operation_id",
              UUID.randomUUID(),
              "source_account_legacy_tenant_id",
              1L,
              "source_target_namespace",
              "test",
              "source_game_row_id",
              1L,
              "source_game_tenant_key",
              tenant.toString());
          insert(dsl, "account_draft_authorization_source_locks", "source_key", evidence.key());
          insert(
              dsl,
              "account_start_session_authority_captures",
              "control_plane_request_id",
              requestId,
              "account_uuid",
              actor,
              "tenant_uuid",
              tenant,
              "target_owner",
              "game-session-service",
              "logging_workload_uri",
              "spiffe://firemud/ns/test/sa/logging-admin-service",
              "reservation_owner_id",
              UUID.randomUUID(),
              "reservation_claim_fence",
              7L,
              "pre_authorization_tuple",
              bytes("{}"),
              "mutation_digest",
              "c".repeat(64),
              "control_ui_operation_id",
              UUID.randomUUID(),
              "control_ui_token_jti",
              UUID.randomUUID(),
              "control_ui_token_hash",
              "d".repeat(64),
              "control_ui_signer_receipt_sha256",
              "e".repeat(64),
              "issuance_fence",
              9L,
              "issuance_fence_source_version",
              1L,
              "captured_at",
              capturedAt,
              "snapshot_sha256",
              hash(snapshot),
              "canonical_snapshot_bytes",
              snapshot,
              "source_version",
              11L,
              "source_fence",
              13L,
              "linearization",
              "17",
              "canonical_sha256",
              hash(bytes("{}")),
              "canonical_capture_bytes",
              bytes("{}"));
          insert(
              dsl,
              "account_start_session_operator_authorizations",
              "control_plane_request_id",
              requestId,
              "pre_authorization_tuple",
              bytes("{}"),
              "mutation_digest",
              "c".repeat(64),
              "issuance_workload_uri",
              "spiffe://firemud/ns/test/sa/logging-admin-service",
              "reservation_owner_id",
              UUID.randomUUID(),
              "reservation_claim_fence",
              7L,
              "issuance_operation_id",
              issuance,
              "issuance_fence",
              9L,
              "bundle_version",
              "authorityEvidenceBundle/v1",
              "bundle_source_version",
              "11",
              "bundle_source_fence",
              "13",
              "bundle_linearization",
              "17",
              "authority_evidence_bundle",
              json(authorityBundle),
              "authorization_reference_fingerprint",
              "arfp/v1/key-1/" + "a".repeat(64),
              "encrypted_response_envelope",
              bytes("synthetic"),
              "issued_at",
              now.minusSeconds(10),
              "reference_expires_at",
              now.plusSeconds(90),
              "response_envelope_expires_at",
              now.plusSeconds(120),
              "status",
              "REDEEMED",
              "redemption_redeemer_workload_uri",
              "spiffe://firemud/ns/test/sa/game-session-service",
              "redemption_owner_attempt_id",
              attempt,
              "redemption_owner_fence",
              47L,
              "redeemed_at",
              now.minusSeconds(5),
              "redemption_reference_fingerprint",
              "arfp/v1/key-1/" + "a".repeat(64),
              "redemption_authority_evidence_bundle",
              json(authorityBundle));
          insert(
              dsl,
              "account_start_session_world_participations",
              "participation_id",
              world,
              "control_plane_request_id",
              requestId,
              "original_post_authorization_tuple",
              tuple,
              "target_namespace",
              "test",
              "canonical_tenant_id",
              tenant,
              "canonical_game_instance_id",
              instance,
              "game_session_owner_attempt_id",
              attempt,
              "game_session_owner_fence",
              47L,
              "preparation_input_json",
              "{}",
              "preparation_input_digest",
              "sha256:" + hash(bytes("{}")));
          insert(
              dsl,
              "account_start_session_world_participation_sources",
              "participation_id",
              world,
              "source_key",
              evidence.key(),
              "source_evidence",
              evidence.canonicalBytes());
          insert(
              dsl,
              "account_start_session_world_original_attempt_evidence",
              "participation_id",
              world,
              "target_namespace",
              "test",
              "game_session_owner_mutation_id",
              mutation,
              "game_session_owner_attempt_id",
              attempt,
              "game_session_owner_fence",
              47L,
              "original_lease_expires_at",
              expiry,
              "original_response_bytes",
              bytes("synthetic-observation"),
              "original_response_digest",
              "sha256:" + hash(bytes("synthetic-observation")));
          insert(
              dsl,
              "account_start_session_world_participation_settlements",
              "participation_id",
              world,
              "outcome",
              "COMMITTED",
              "world_execution_fence",
              1L,
              "terminal_bytes",
              bytes("synthetic-terminal"),
              "terminal_digest",
              "sha256:" + hash(bytes("synthetic-terminal")));
          dsl.execute("SET LOCAL session_replication_role = origin");
        });
    return fixture;
  }

  private static void insert(DSLContext dsl, String table, Object... fields) {
    Map<Field<?>, Object> values = new LinkedHashMap<>();
    for (int index = 0; index < fields.length; index += 2)
      values.put(DSL.field(DSL.name((String) fields[index])), fields[index + 1]);
    try {
      dsl.insertInto(DSL.table(DSL.name(table))).set(values).execute();
    } catch (RuntimeException failure) {
      String sqlState = "unavailable";
      String constraint = "unavailable";
      StringBuilder causeTypes = new StringBuilder();
      var visited =
          java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
      Throwable cause = failure;
      int causeCount = 0;
      while (cause != null && causeCount < 32 && visited.add(cause)) {
        if (causeTypes.length() > 0) causeTypes.append("->");
        causeTypes.append(diagnosticIdentifier(cause.getClass().getSimpleName()));
        causeCount++;
        if (cause instanceof SQLException sqlFailure && sqlFailure.getSQLState() != null) {
          sqlState = sqlFailure.getSQLState();
        }
        if (cause instanceof PSQLException postgresFailure) {
          var serverError = postgresFailure.getServerErrorMessage();
          if (serverError != null && serverError.getConstraint() != null) {
            constraint = serverError.getConstraint();
          }
        }
        cause = cause.getCause();
      }
      if (cause != null) causeTypes.append("->truncated");
      System.err.printf(
          "StartSession admission fixture insert failed: "
              + "table=%s SQLSTATE=%s constraint=%s causeTypes=%s%n",
          diagnosticIdentifier(table),
          diagnosticIdentifier(sqlState),
          diagnosticIdentifier(constraint),
          causeTypes);
      throw failure;
    }
  }

  private static String diagnosticIdentifier(String value) {
    return value != null && value.matches("[A-Za-z0-9_]+") ? value : "unavailable";
  }

  private static Long sequenceValue(Fixture fixture) {
    var sequence =
        fixture
            .dsl()
            .fetchSingle(
                "SELECT namespace.nspname AS schema_name, relation.relname AS sequence_name "
                    + "FROM pg_class relation "
                    + "JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace "
                    + "WHERE relation.oid = pg_get_serial_sequence("
                    + "format('%I.%I', current_schema(), ?), ?)::regclass",
                "account_start_session_admission_protections", "protection_fence");
    return fixture
        .dsl()
        .select(DSL.field(DSL.name("last_value"), Long.class))
        .from(
            DSL.table(
                DSL.name(
                    sequence.get("schema_name", String.class),
                    sequence.get("sequence_name", String.class))))
        .fetchSingle(0, Long.class);
  }

  private static void assertDatabaseGuardFailure(
      Throwable failure, String expectedMessage, String expectedSqlState) {
    var visited =
        java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
    Throwable cause = failure;
    PSQLException databaseFailure = null;
    while (cause != null && visited.add(cause)) {
      if (cause instanceof PSQLException postgresFailure) {
        databaseFailure = postgresFailure;
        break;
      }
      cause = cause.getCause();
    }
    assertThat((Throwable) databaseFailure)
        .as("the rejected fixture write must fail at PostgreSQL's database guard")
        .isNotNull();
    assertThat(databaseFailure.getSQLState()).isEqualTo(expectedSqlState);
    assertThat(databaseFailure.getServerErrorMessage()).isNotNull();
    assertThat(databaseFailure.getServerErrorMessage().getMessage()).contains(expectedMessage);
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] malformedSettlementBytes() {
    return bytes(
        "{\"canonicalAccountProtectionEvidenceBytesBase64\":\"\","
            + "\"canonicalGameSessionOwnerProofBytesBase64\":\"\","
            + "\"schema\":\"account-start-session-admission-protection-settlement/v2\"}");
  }

  private static byte[] json(Map<String, Object> value) {
    return AccountControlUiAuthority.canonical(value);
  }

  private static String base64(byte[] value) {
    return Base64.getEncoder().encodeToString(value);
  }

  private static String hash(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private record Fixture(
      DSLContext dsl,
      TransactionTemplate transaction,
      DriverManagerDataSource dataSource,
      UUID protectionId,
      String requestId,
      UUID tenantId,
      UUID instanceId,
      UUID mutationId,
      UUID attemptId,
      UUID worldId,
      OffsetDateTime expiry,
      SourceEvidence source,
      byte[] tuple,
      byte[] projection,
      byte[] hold,
      byte[] request) {}
}
