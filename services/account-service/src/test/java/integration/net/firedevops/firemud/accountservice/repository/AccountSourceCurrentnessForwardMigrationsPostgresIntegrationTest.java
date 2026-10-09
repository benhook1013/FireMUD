package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountLifecycleState;
import net.firedevops.firemud.accountservice.hostedterms.HostedTermsCatalogVersion;
import net.firedevops.firemud.accountservice.hostedterms.HostedTermsEncoding;
import net.firedevops.firemud.accountservice.hostedterms.HostedTermsRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuditOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantCreationBootstrapOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.accountservice.service.AccountCreatorMembershipSourceReader;
import net.firedevops.firemud.accountservice.service.AccountTenantCreationBootstrapAuthorizationSource;
import net.firedevops.firemud.accountservice.service.AccountTenantCreationBootstrapService;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Outcome;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.OwnerReadback;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.FreshTenantCreatorDigest;
import net.firedevops.firemud.common.tenant.FreshTenantCreatorEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;

/** Physical PostgreSQL proof for the first Account source/currentness forward stage. */
class AccountSourceCurrentnessForwardMigrationsPostgresIntegrationTest {
  private static final AccountPostgresIntegrationFixture POSTGRES =
      new AccountPostgresIntegrationFixture();

  @BeforeAll
  static void startPostgres() {
    POSTGRES.start();
  }

  @AfterAll
  static void stopPostgres() {
    POSTGRES.stop();
  }

  @Test
  void freshInstallAppliesEveryForwardMigrationThroughV98() {
    TestContext context = context(null);

    assertThat(
            context
                .dsl()
                .resultQuery(
                    "SELECT version FROM flyway_schema_history "
                        + "WHERE success AND version = '98'")
                .fetchOne(0, String.class))
        .isEqualTo("98");
    assertThat(
            context
                .dsl()
                .resultQuery(
                    "SELECT count(*) FROM flyway_schema_history "
                        + "WHERE version::numeric BETWEEN 74 AND 98 AND success")
                .fetchOne(0, Long.class))
        .isEqualTo(25L);

    for (String relation :
        new String[] {
          "account_global_role_sources",
          "account_draft_authorization_source_locks",
          "account_draft_authorization_fences",
          "account_draft_authorization_source_changes",
          "account_password_reset_operation_receipts",
          "account_logout_all_operation_receipts",
          "account_security_state_operations",
          "account_platform_restriction_births",
          "account_tenant_creation_bootstrap_operations",
          "account_individual_creator_party_sources",
          "account_tenant_creator_party_history",
          "account_hosted_terms_scopes",
          "account_hosted_terms_catalog_versions",
          "account_hosted_terms_environment_binding_heads",
          "account_logout_all_draft_source_changes",
          "account_password_reset_draft_source_changes",
          "account_game_logic_intake_source_read_reservations",
          "account_game_logic_intake_source_read_sources",
          "account_game_logic_intake_source_read_aborts",
          "account_hosted_terms_disclosure_handoffs",
          "account_hosted_terms_disclosure_sources"
        }) {
      assertThat(
              context
                  .dsl()
                  .resultQuery("SELECT to_regclass(?)::TEXT", relation)
                  .fetchOne(0, String.class))
          .as("forward relation %s", relation)
          .isNotNull();
    }
    for (String function :
        new String[] {
          "account_draft_authorization_required_owners(bytea)",
          "account_draft_authorization_is_settled(uuid)",
          "account_creator_party_initial_receipt_guard()",
          "account_hosted_terms_acceptance_insert_guard()",
          "account_password_reset_draft_link_guard()",
          "account_game_logic_intake_source_read_is_pending(uuid)",
          "account_game_logic_intake_source_read_complete_guard()",
          "account_game_logic_intake_source_read_source_guard()",
          "account_game_logic_intake_source_read_terminal_guard()",
          "account_hosted_terms_disclosure_handoff_guard()",
          "account_hosted_terms_disclosure_immutable_guard()",
          "account_hosted_terms_disclosure_source_insert_guard()"
        }) {
      assertThat(
              context
                  .dsl()
                  .resultQuery("SELECT to_regprocedure(?)::TEXT", function)
                  .fetchOne(0, String.class))
          .as("forward SQL guard %s", function)
          .isNotNull();
    }
    String settlementGuard =
        context
            .dsl()
            .resultQuery(
                "SELECT pg_get_functiondef('account_game_logic_intake_settlement_guard()'::REGPROCEDURE)")
            .fetchOne(0, String.class);
    assertThat(settlementGuard)
        .contains(
            "ORDER BY account_publication_authorization_source_sort_key(l.source_key) FOR UPDATE OF l");
    for (String[] trigger :
        new String[][] {
          {
            "account_password_reset_operation_no_truncate",
            "account_password_reset_operation_receipts"
          },
          {"account_logout_all_operation_no_truncate", "account_logout_all_operation_receipts"},
          {
            "account_tenant_creation_bootstrap_no_truncate",
            "account_tenant_creation_bootstrap_operations"
          },
          {"account_hosted_terms_catalog_no_truncate", "account_hosted_terms_catalog_versions"},
          {
            "account_hosted_terms_acceptance_no_truncate",
            "account_individual_hosted_terms_acceptances"
          },
          {"account_hosted_terms_scope_no_truncate", "account_hosted_terms_scopes"},
          {
            "account_hosted_terms_publication_no_truncate",
            "account_hosted_terms_publication_operations"
          },
          {
            "account_hosted_terms_environment_binding_no_truncate",
            "account_hosted_terms_environment_bindings"
          },
          {
            "account_hosted_terms_environment_binding_publication_no_truncate",
            "account_hosted_terms_environment_binding_publications"
          },
          {
            "account_hosted_terms_disclosure_handoffs_no_truncate",
            "account_hosted_terms_disclosure_handoffs"
          },
          {
            "account_hosted_terms_disclosure_sources_no_truncate",
            "account_hosted_terms_disclosure_sources"
          },
          {
            "account_game_logic_intake_source_read_no_truncate",
            "account_game_logic_intake_source_read_reservations"
          },
          {
            "account_game_logic_intake_source_read_sources_no_truncate",
            "account_game_logic_intake_source_read_sources"
          },
          {
            "account_game_logic_intake_source_read_aborts_no_truncate",
            "account_game_logic_intake_source_read_aborts"
          }
        }) {
      assertThat(
              context
                  .dsl()
                  .resultQuery(
                      "SELECT count(*) FROM pg_trigger WHERE tgname = ?::name "
                          + "AND tgrelid = ?::regclass AND NOT tgisinternal "
                          + "AND (tgtype::INTEGER & 32) <> 0 "
                          + "AND (tgtype::INTEGER & 1) = 0 AND tgenabled <> 'D'",
                      trigger[0],
                      trigger[1])
                  .fetchOne(0, Long.class))
          .as("statement-level immutable trigger %s", trigger[0])
          .isEqualTo(1L);
    }
  }

  @Test
  void controlUiIssuanceMustStartPreparedAndCanCommitAfterProtectedResponseReadback() {
    TestContext context = context(null);
    TestIdentity identity = testIdentity(context);
    long issuedAt = Instant.now().getEpochSecond();
    OffsetDateTime recoveryExpiresAt =
        OffsetDateTime.ofInstant(Instant.ofEpochSecond(issuedAt + 30), ZoneOffset.UTC);
    UUID rejectedOperationId = UUID.randomUUID();
    byte[] sourcePayload = "{}".getBytes(StandardCharsets.UTF_8);
    byte[] bundlePayload = "{}".getBytes(StandardCharsets.UTF_8);
    byte[] candidate = new byte[] {1};

    // This row satisfies the table CHECKs. The INSERT guard must still reject a direct terminal
    // state before it can bypass the protected response readback transition.
    assertThatThrownBy(
            () ->
                context
                    .dsl()
                    .execute(
                        "INSERT INTO account_control_ui_issuance_operations "
                            + "(request_id, operation_id, token_jti, account_uuid, tenant_uuid, "
                            + "caller_workload, caller_context_id, request_mac_key_id, request_digest, "
                            + "claims_payload, source_payload, bundle_payload, signer_receipt, "
                            + "issued_at_epoch_second, expires_at_epoch_second, recovery_expires_at, "
                            + "status, token_hash, pending_registry, active_registry, pending_receipt, "
                            + "committed_at) VALUES (?, ?, ?, ?, ?, 'test', ?, 'test-key', ?, ?, ?, ?, ?, ?, ?, "
                            + "?::timestamptz, 'COMMITTED', ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                        UUID.randomUUID(),
                        rejectedOperationId,
                        UUID.randomUUID(),
                        identity.accountUuid(),
                        identity.tenantUuid(),
                        UUID.randomUUID(),
                        "a".repeat(64),
                        candidate,
                        sourcePayload,
                        bundlePayload,
                        candidate,
                        issuedAt,
                        issuedAt + 300,
                        recoveryExpiresAt,
                        "b".repeat(64),
                        candidate,
                        candidate,
                        candidate))
        .isInstanceOf(DataAccessException.class);

    ControlUiIssuance committed =
        insertCommittedControlUiIssuance(context, identity, sourcePayload, bundlePayload);
    assertThat(
            context
                .dsl()
                .resultQuery(
                    "SELECT status FROM account_control_ui_issuance_operations WHERE operation_id = ?",
                    committed.operationId())
                .fetchOne(0, String.class))
        .isEqualTo("COMMITTED");
    assertThat(
            context
                .dsl()
                .resultQuery(
                    "SELECT count(*) FROM account_control_ui_response_envelopes WHERE operation_id = ?",
                    committed.operationId())
                .fetchOne(0, Long.class))
        .isEqualTo(1L);
  }

  @Test
  void publicationCompletenessRejectsMissingJsonValuesAndAcceptsMaximumHostedSourceKey() {
    TestContext context = context(null);
    TestIdentity identity = testIdentity(context);
    String boundary = "e".repeat(512);
    String sourceScope = "environment-boundary/" + boundary;
    String sourceKey = "HOSTED_TERMS:" + sourceScope;
    byte[] sourceEvidence = sourceEvidence("HOSTED_TERMS", sourceScope);
    assertThat(sourceKey.getBytes(StandardCharsets.UTF_8)).hasSize(546);
    context
        .dsl()
        .execute(
            "INSERT INTO account_draft_authorization_source_locks (source_key) VALUES (?)",
            sourceKey);

    String validSources = sourceJsonArray(List.of(sourceEvidence));
    String validPayload = publicationSourcePayload(identity, true, true, validSources);
    String validBundle = publicationBundle(true);
    String validRequest = UUID.randomUUID().toString();
    String validIntent = publicationIntent(identity, validRequest, true, true);
    exerciseSelectedPublication(
        context,
        identity,
        sourceEvidence,
        validPayload,
        validBundle,
        validRequest,
        validIntent,
        1,
        true);

    String missingFenceRequest = UUID.randomUUID().toString();
    exerciseSelectedPublication(
        context,
        identity,
        sourceEvidence,
        validPayload,
        publicationBundle(false),
        missingFenceRequest,
        publicationIntent(identity, missingFenceRequest, true, true),
        1,
        false);

    String missingAccountRequest = UUID.randomUUID().toString();
    exerciseSelectedPublication(
        context,
        identity,
        sourceEvidence,
        publicationSourcePayload(identity, false, true, validSources),
        validBundle,
        missingAccountRequest,
        publicationIntent(identity, missingAccountRequest, true, true),
        1,
        false);

    String missingTenantRequest = UUID.randomUUID().toString();
    exerciseSelectedPublication(
        context,
        identity,
        sourceEvidence,
        publicationSourcePayload(identity, true, false, validSources),
        validBundle,
        missingTenantRequest,
        publicationIntent(identity, missingTenantRequest, true, true),
        1,
        false);

    String missingCanonicalTenantRequest = UUID.randomUUID().toString();
    exerciseSelectedPublication(
        context,
        identity,
        sourceEvidence,
        validPayload,
        validBundle,
        missingCanonicalTenantRequest,
        publicationIntent(identity, missingCanonicalTenantRequest, false, true),
        1,
        false);

    String missingPublishRequest = UUID.randomUUID().toString();
    exerciseSelectedPublication(
        context,
        identity,
        sourceEvidence,
        validPayload,
        validBundle,
        missingPublishRequest,
        publicationIntent(identity, missingPublishRequest, true, false),
        1,
        false);

    String missingSourcesRequest = UUID.randomUUID().toString();
    exerciseSelectedPublication(
        context,
        identity,
        sourceEvidence,
        publicationSourcePayload(identity, true, true, null),
        validBundle,
        missingSourcesRequest,
        publicationIntent(identity, missingSourcesRequest, true, true),
        1,
        false);

    String nullSourceRequest = UUID.randomUUID().toString();
    exerciseSelectedPublication(
        context,
        identity,
        sourceEvidence,
        publicationSourcePayload(identity, true, true, "[null]"),
        validBundle,
        nullSourceRequest,
        publicationIntent(identity, nullSourceRequest, true, true),
        1,
        false);

    String mismatchedCountRequest = UUID.randomUUID().toString();
    exerciseSelectedPublication(
        context,
        identity,
        sourceEvidence,
        validPayload,
        validBundle,
        mismatchedCountRequest,
        publicationIntent(identity, mismatchedCountRequest, true, true),
        2,
        false);
  }

  @Test
  void v2PublicationSettlementAcceptsEmptyOptionalInputsButRequiresRoomInventory() {
    TestContext context = context(null);
    exerciseSelectedPublicationV2Settlement(context, testIdentity(context), 0, 1, true);
    exerciseSelectedPublicationV2Settlement(context, testIdentity(context), 0, 0, false);
    exerciseSelectedPublicationV2Settlement(context, testIdentity(context), 1, 1, false);
  }

  @Test
  void gameLogicSettlementAcceptsCompleteTerminalAndUsesCanonicalSourceLockOrder() {
    TestContext context = context(null);
    TestIdentity identity = testIdentity(context);
    UUID versionUuid = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    UUID fenceId = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    String namespace = "test";
    SourceEvidence supplementaryEvidence =
        new SourceEvidence(
            SourceKind.ISSUER,
            "\uD83D\uDE00",
            "1",
            "1",
            "test-only-source-checkpoint",
            "1",
            utf8("synthetic supplementary issuer evidence"));
    SourceEvidence bmpEvidence =
        new SourceEvidence(
            SourceKind.ISSUER,
            "\uE000",
            "1",
            "1",
            "test-only-source-checkpoint",
            "1",
            utf8("synthetic BMP issuer evidence"));
    byte[] supplementarySource = supplementaryEvidence.canonicalBytes();
    byte[] bmpSource = bmpEvidence.canonicalBytes();
    String supplementaryKey = supplementaryEvidence.key();
    String bmpKey = bmpEvidence.key();
    List<byte[]> sourceVector = List.of(supplementarySource, bmpSource);
    List<SourceEvidence> sourceEvidenceVector = List.of(supplementaryEvidence, bmpEvidence);
    String sourcePayload =
        publicationSourcePayload(identity, true, true, sourceJsonArray(sourceVector));
    String bundlePayload = publicationBundle(true);
    byte[] sourcePayloadBytes = sourcePayload.getBytes(StandardCharsets.UTF_8);
    byte[] bundlePayloadBytes = bundlePayload.getBytes(StandardCharsets.UTF_8);
    ControlUiIssuance issuance =
        insertCommittedControlUiIssuance(context, identity, sourcePayloadBytes, bundlePayloadBytes);
    DraftCommitBinding selectedCommit =
        DraftCommitBinding.create(
            new DraftCommitBinding.TargetProof(
                identity.tenantUuid(),
                versionUuid,
                1,
                "test-only-tenant",
                2,
                "test-only-tenant",
                "NEW_GAME_ROW"),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "test-only/base",
            List.of(
                new DraftCommitBinding.RevisionPayload(
                    "0",
                    UUID.randomUUID(),
                    DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                    "test-only-world-payload")),
            List.of(
                new DraftCommitBinding.AffectedUnit(
                    DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                    "region",
                    "test-region",
                    "aggregate",
                    "test-region",
                    "0")));
    DraftAuthorizationFenceBinding originalDraftBinding =
        new DraftAuthorizationFenceBinding(
            UUID.randomUUID(),
            selectedCommit.requestId(),
            selectedCommit.commitId(),
            UUID.randomUUID(),
            identity.accountUuid(),
            identity.tenantUuid(),
            versionUuid,
            selectedCommit.baseCommitId(),
            "0",
            selectedCommit.canonicalBytes(),
            selectedCommit.canonicalBytes(),
            selectedCommit.digest(),
            sourceEvidenceVector);
    String bindingJson = selectedCommit.canonicalJson();
    String snapshotJson =
        "{\"schema\":\"game-design-gameplay-rule-source-snapshot/v1\","
            + "\"bindingJson\":\""
            + bindingJson.replace("\\", "\\\\").replace("\"", "\\\"")
            + "\",\"bindingDigest\":\""
            + sha256(utf8(bindingJson))
            + "\",\"manifestJson\":\"{}\"}";
    byte[] snapshot = utf8(snapshotJson);
    byte[] intakeBinding =
        encodeFrames(
            List.of(
                utf8("account-game-logic-intake-authorization/v1"),
                utf8(operationId.toString()),
                utf8(fenceId.toString()),
                utf8(requestId.toString()),
                utf8(identity.accountUuid().toString()),
                snapshot,
                utf8("2"),
                supplementarySource,
                bmpSource));

    context
        .dsl()
        .execute(
            "INSERT INTO account_draft_authorization_source_locks (source_key) VALUES (?)",
            supplementaryKey);
    context
        .dsl()
        .execute(
            "INSERT INTO account_draft_authorization_source_locks (source_key) VALUES (?)", bmpKey);
    DraftAuthorizationFenceRepository draftFences =
        new DraftAuthorizationFenceRepository(context.dsl());
    context
        .transaction()
        .executeWithoutResult(
            status -> {
              draftFences.reserve(originalDraftBinding);
              draftFences.claimCommitOrder(originalDraftBinding);
              for (Owner owner : originalDraftBinding.requiredOwners()) {
                draftFences.recordOwnerReadback(
                    originalDraftBinding,
                    new OwnerReadback(
                        owner,
                        Outcome.COMMITTED,
                        originalDraftBinding.operationId(),
                        originalDraftBinding.commitId(),
                        originalDraftBinding.fenceId(),
                        originalDraftBinding.inputDigest(),
                        originalDraftBinding.canonicalBytes(),
                        utf8("test-only settled original owner fixture")));
              }
              assertThat(draftFences.readSettlement(originalDraftBinding))
                  .isEqualTo(DraftAuthorizationFenceRepository.Settlement.COMMITTED);
            });

    byte[] sourceReadScope =
        encodeFrames(
            List.of(
                utf8("account-game-logic-intake-source-read/v1"),
                utf8(namespace),
                utf8(operationId.toString()),
                utf8(fenceId.toString()),
                utf8(requestId.toString()),
                utf8(identity.accountUuid().toString()),
                selectedCommit.canonicalBytes(),
                utf8(sha256(selectedCommit.canonicalBytes())),
                utf8("spiffe://firemud/ns/" + namespace + "/sa/account-service"),
                utf8("GAME_LOGIC_INTAKE_SOURCE")));
    byte[] outboxCheckpoints = "[]".getBytes(StandardCharsets.UTF_8);
    context
        .transaction()
        .executeWithoutResult(
            status -> {
              context
                  .dsl()
                  .execute(
                      "INSERT INTO account_game_logic_intake_source_read_reservations "
                          + "(operation_id, fence_id, intake_request_id, actor_account_uuid, "
                          + "tenant_uuid, version_uuid, scope_bytes, scope_digest, "
                          + "issuance_operation_id, issuance_fence, source_payload, "
                          + "issuance_bundle, outbox_checkpoints) "
                          + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                      operationId,
                      fenceId,
                      requestId,
                      identity.accountUuid(),
                      identity.tenantUuid(),
                      versionUuid,
                      sourceReadScope,
                      sha256(sourceReadScope),
                      issuance.operationId(),
                      issuance.fence(),
                      sourcePayloadBytes,
                      bundlePayloadBytes,
                      outboxCheckpoints);
              context
                  .dsl()
                  .execute(
                      "INSERT INTO account_game_logic_intake_source_read_sources "
                          + "(operation_id, source_key, source_evidence) "
                          + "VALUES (?, ?, ?), (?, ?, ?)",
                      operationId,
                      supplementaryKey,
                      supplementarySource,
                      operationId,
                      bmpKey,
                      bmpSource);
            });
    context
        .transaction()
        .executeWithoutResult(
            status -> {
              context
                  .dsl()
                  .execute(
                      "INSERT INTO account_game_logic_intake_authorizations "
                          + "(operation_id, fence_id, actor_account_uuid, tenant_uuid, version_uuid, "
                          + "intake_request_id, source_digest, binding, issuance_operation_id, "
                          + "issuance_fence, source_payload, issuance_bundle, outbox_checkpoints) "
                          + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                      operationId,
                      fenceId,
                      identity.accountUuid(),
                      identity.tenantUuid(),
                      versionUuid,
                      requestId,
                      sha256(snapshot),
                      intakeBinding,
                      issuance.operationId(),
                      issuance.fence(),
                      sourcePayloadBytes,
                      bundlePayloadBytes,
                      outboxCheckpoints);
              context
                  .dsl()
                  .execute(
                      "INSERT INTO account_game_logic_intake_sources "
                          + "(operation_id, source_key, source_evidence) VALUES (?, ?, ?), (?, ?, ?)",
                      operationId,
                      supplementaryKey,
                      supplementarySource,
                      operationId,
                      bmpKey,
                      bmpSource);
            });

    var orderedKeys =
        context
            .dsl()
            .fetch(
                "SELECT source_key FROM account_game_logic_intake_sources WHERE operation_id = ? "
                    + "ORDER BY account_publication_authorization_source_sort_key(source_key)",
                operationId)
            .getValues(0, String.class);
    assertThat(orderedKeys).containsExactly(supplementaryKey, bmpKey);

    byte[] operationBinding =
        encodeFrames(
            List.of(
                utf8("game-logic-gameplay-rule-intake-operation/v1"),
                utf8(namespace),
                intakeBinding));
    byte[] terminal =
        encodeFrames(
            List.of(
                utf8("game-logic-gameplay-rule-intake-terminal/v1"),
                utf8("RETAINED"),
                operationBinding,
                intakeBinding,
                utf8("PRESENT"),
                snapshot,
                utf8("PRESENT"),
                utf8("{}")));
    byte[] receipt =
        encodeFrames(List.of(utf8("account-game-logic-intake-settlement/v1"), terminal));
    context
        .dsl()
        .execute(
            "INSERT INTO account_game_logic_intake_settlements "
                + "(operation_id, target_namespace, outcome, terminal_bytes, terminal_digest, "
                + "receipt_bytes, receipt_digest) VALUES (?, ?, 'RETAINED', ?, ?, ?, ?)",
            operationId,
            namespace,
            terminal,
            sha256(terminal),
            receipt,
            sha256(receipt));
    assertThat(
            context
                .dsl()
                .resultQuery(
                    "SELECT outcome FROM account_game_logic_intake_settlements WHERE operation_id = ?",
                    operationId)
                .fetchOne(0, String.class))
        .isEqualTo("RETAINED");

    for (String invalidSourcePayload :
        List.of(
            publicationSourcePayload(identity, true, true, null),
            publicationSourcePayload(identity, true, true, "null"),
            publicationSourcePayload(identity, true, true, "{}"),
            publicationSourcePayload(identity, true, true, "[]"),
            publicationSourcePayload(
                identity,
                true,
                true,
                sourceJsonArray(List.of(sourceEvidence("ISSUER", "mismatch")))))) {
      assertGameLogicSourceParticipationRejected(
          context, identity, versionUuid, selectedCommit, namespace, invalidSourcePayload);
    }
  }

  private static void exerciseSelectedPublicationV2Settlement(
      TestContext context,
      TestIdentity identity,
      int regionRowCount,
      int roomRowCount,
      boolean accepted) {
    UUID versionUuid = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    UUID fenceId = UUID.randomUUID();
    UUID publishRequestId = UUID.randomUUID();
    UUID intakeRequestId = UUID.randomUUID();
    UUID applicationOperationId = operationId;
    UUID workflowId = UUID.randomUUID();
    DraftCommitBinding selectedCommit =
        DraftCommitBinding.create(
            new DraftCommitBinding.TargetProof(
                identity.tenantUuid(),
                versionUuid,
                1,
                "test-tenant",
                2,
                "test-tenant",
                "NEW_GAME_ROW"),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "test-only/base",
            List.of(
                new DraftCommitBinding.RevisionPayload(
                    "0",
                    UUID.randomUUID(),
                    DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                    "test-only world revision")),
            List.of(
                new DraftCommitBinding.AffectedUnit(
                    DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                    "region",
                    "test-region",
                    "aggregate",
                    "test-region",
                    "0")));
    String expectedTuples =
        "[{\"owner\":\"WORLD_MANAGEMENT\",\"aggregateType\":\"region\","
            + "\"aggregateId\":\"test-region\",\"scopeType\":\"aggregate\","
            + "\"scopeId\":\"test-region\",\"expectedEpoch\":\"0\"}]";
    String expectedEpochs =
        "[{\"aggregateType\":\"region\",\"aggregateId\":\"test-region\","
            + "\"scopeType\":\"aggregate\",\"scopeId\":\"test-region\","
            + "\"expectedEpoch\":\"0\",\"resultingEpoch\":\"1\"}]";
    String selectedIntent =
        jsonObject(
            "canonicalTenantId", jsonString(identity.tenantUuid().toString()),
            "canonicalVersionId", jsonString(versionUuid.toString()),
            "publishRequestId", jsonString(publishRequestId.toString()),
            "expectedVersionStateEpoch", jsonString("1"),
            "notes", jsonString(""),
            "selectedCommitRequestId", jsonString(selectedCommit.requestId().toString()),
            "selectedCommitId", jsonString(selectedCommit.commitId().toString()),
            "selectedCommitDigest", jsonString(selectedCommit.digest()));
    String selectedTarget =
        jsonObject(
            "canonicalTenantId", jsonString(identity.tenantUuid().toString()),
            "canonicalVersionId", jsonString(versionUuid.toString()),
            "gameDesignVersionRowId", jsonString("1"),
            "gameDesignVersionTenantKey", jsonString("test-tenant"),
            "sourceGameRowId", jsonString("2"),
            "sourceGameTenantKey", jsonString("test-tenant"),
            "sourceProvenanceKind", jsonString("NEW_GAME_ROW"));
    String synchronizedFence =
        jsonObject(
            "requestId", jsonString(selectedCommit.requestId().toString()),
            "commitId", jsonString(selectedCommit.commitId().toString()),
            "inputDigest", jsonString(selectedCommit.digest()),
            "resultVectorJson", jsonString("[]"),
            "createdAt", jsonString("test-only"));
    String selectedJson =
        jsonObject(
            "schemaVersion",
            jsonString("1"),
            "intent",
            selectedIntent,
            "target",
            selectedTarget,
            "selectedCommitBindingJson",
            jsonString(selectedCommit.canonicalJson()),
            "selectedCommitDigest",
            jsonString(selectedCommit.digest()),
            "synchronizedFence",
            synchronizedFence);
    byte[] selectedBytes = utf8(selectedJson);
    byte[] inputBytes =
        encodeFrames(
            List.of(
                utf8("account-publication-input/v1"),
                utf8(identity.accountUuid().toString()),
                selectedBytes,
                utf8(sha256(selectedBytes))));
    byte[] source = sourceEvidence("HOSTED_TERMS", "test-v2-publication/" + operationId);
    String sourceKey = "HOSTED_TERMS:test-v2-publication/" + operationId;
    String sourcePayload =
        publicationSourcePayload(identity, true, true, sourceJsonArray(List.of(source)));
    byte[] sourcePayloadBytes = utf8(sourcePayload);
    byte[] bundlePayloadBytes = utf8(publicationBundle(true));
    byte[] outboxCheckpoints = utf8("[]");
    byte[] accountBinding =
        encodeFrames(
            List.of(
                utf8("account-publication-authorization/v1"),
                utf8(operationId.toString()),
                utf8(fenceId.toString()),
                inputBytes,
                utf8("1"),
                source));
    ControlUiIssuance issuance =
        insertCommittedControlUiIssuance(context, identity, sourcePayloadBytes, bundlePayloadBytes);
    context
        .dsl()
        .execute(
            "INSERT INTO account_draft_authorization_source_locks (source_key) VALUES (?) "
                + "ON CONFLICT DO NOTHING",
            sourceKey);
    context
        .transaction()
        .executeWithoutResult(
            status -> {
              context
                  .dsl()
                  .execute(
                      "INSERT INTO account_selected_publication_authorizations "
                          + "(operation_id, fence_id, actor_account_uuid, tenant_uuid, "
                          + "publish_request_id, input_digest, binding, world_evidence, "
                          + "issuance_operation_id, issuance_fence, "
                          + "source_payload, issuance_bundle, "
                          + "outbox_checkpoints) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                      operationId,
                      fenceId,
                      identity.accountUuid(),
                      identity.tenantUuid(),
                      publishRequestId.toString(),
                      sha256(inputBytes),
                      accountBinding,
                      null,
                      issuance.operationId(),
                      issuance.fence(),
                      sourcePayloadBytes,
                      bundlePayloadBytes,
                      outboxCheckpoints);
              context
                  .dsl()
                  .execute(
                      "INSERT INTO account_selected_publication_sources "
                          + "(operation_id, source_key, source_evidence) VALUES (?, ?, ?)",
                      operationId,
                      sourceKey,
                      source);
            });

    String requestDigest = sha256(selectedBytes).substring("sha256:".length());
    String contentDigest = sha256(utf8("test-only content digest")).substring("sha256:".length());
    String graphDigest = sha256(utf8("test-only applied graph digest"));
    byte[] appliedOperation =
        encodeFrames(
            List.of(
                utf8("test-world-applied-operation/v1"), utf8(applicationOperationId.toString())));
    byte[] appliedBytes =
        utf8(
            jsonObject(
                "status",
                jsonString("APPLIED"),
                "operationBytesBase64",
                jsonString(Base64.getEncoder().encodeToString(appliedOperation)),
                "graphDigest",
                jsonString(graphDigest)));
    String selectorRequest =
        jsonObject(
            "targetNamespace", jsonString("test"),
            "canonicalTenantId", jsonString(identity.tenantUuid().toString()),
            "canonicalVersionId", jsonString(versionUuid.toString()),
            "intakeRequestId", jsonString(intakeRequestId.toString()),
            "publicationFence", jsonString(fenceId.toString()),
            "publicationRequestId", jsonString(publishRequestId.toString()),
            "requestDigest", jsonString(requestDigest),
            "versionStateEpoch", jsonString("1"),
            "publishWorkflowId", jsonString(workflowId.toString()),
            "appliedCommitId", jsonString(selectedCommit.commitId().toString()),
            "contentDigest", jsonString(contentDigest),
            "digestSchemaVersion", "1",
            "worldAffectedTuples", expectedTuples);
    String selectorJson =
        jsonObject(
            "schema", jsonString("world-published-start-location-evidence/v1"),
            "request", selectorRequest,
            "selectorReceiptBytesBase64",
                jsonString(Base64.getEncoder().encodeToString(utf8("test-only selector receipt"))),
            "originalAccountBindingBytesBase64",
                jsonString(Base64.getEncoder().encodeToString(accountBinding)),
            "appliedResultBytesBase64",
                jsonString(Base64.getEncoder().encodeToString(appliedBytes)));
    byte[] selectorBytes = utf8(selectorJson);
    String regionFamilyCount = familyCount("REGION", regionRowCount);
    String modelJson =
        jsonObject(
            "modelId",
            jsonString("WORLD_LOGICAL_ROOM_EXIT_ADJACENCY_V1"),
            "graphSchemaVersion",
            "2",
            "graphDigest",
            jsonString(graphDigest),
            "topologyResultDigest",
            jsonString(sha256(utf8("test-only topology digest"))),
            "familyCounts",
            "["
                + String.join(
                    ",",
                    List.of(
                        regionFamilyCount,
                        familyCount("ZONE", 0),
                        familyCount("ROOM", roomRowCount),
                        familyCount("ROOM_EXIT", 0),
                        familyCount("GENERATION_RULE", 0),
                        familyCount("WORLD_ENTITY_SPAWN_BINDING", 0)))
                + "]",
            "regionGeneratorInputs",
            "[]",
            "generationRuleFields",
            "[\"id\",\"name\",\"scopeType\",\"scopeId\",\"value\"]",
            "regionFields",
            "[\"id\",\"shardId\",\"name\",\"weather\",\"generationSeed\","
                + "\"generatorType\",\"generatorParams\",\"spacingMultiplier\"]",
            "zoneFields",
            "[\"id\",\"regionId\",\"name\"]",
            "roomFields",
            "[\"id\",\"zoneId\",\"name\",\"description\","
                + "\"nameLocalizedVariantsJson\",\"descriptionLocalizedVariantsJson\"]",
            "roomExitFields",
            "[\"id\",\"fromRoomId\",\"toRoomId\",\"direction\",\"cost\"]",
            "spawnBindingFields",
            "[\"id\",\"roomId\",\"entityTemplateType\",\"entityReference.kind\","
                + "\"entityReference.tenantId\",\"entityReference.versionId\","
                + "\"entityReference.templateId\",\"spawnCount\",\"respawnDelaySeconds\"]",
            "spawnBindingInputs",
            "[]",
            "generationRuleInputCount",
            "0",
            "spawnBindingCount",
            "0",
            "appliedEpochs",
            expectedEpochs);
    String inventoryJson =
        jsonObject(
            "schema", jsonString("world-selected-publication-artifact-inventory/v1"),
            "schemaVersion", "1",
            "completeness", jsonString("COMPLETE"),
            "ownerScope",
                jsonObject(
                    "targetNamespace", jsonString("test"),
                    "canonicalTenantId", jsonString(identity.tenantUuid().toString()),
                    "canonicalVersionId", jsonString(versionUuid.toString()),
                    "versionIdentityOperationId", jsonString(UUID.randomUUID().toString()),
                    "intakeRequestId", jsonString(intakeRequestId.toString()),
                    "intakeOperationId", jsonString(UUID.randomUUID().toString()),
                    "intakeRequestDigest", jsonString(sha256(utf8("intake request"))),
                    "sourceOperationId", jsonString(UUID.randomUUID().toString()),
                    "sourceEvidenceDigest", jsonString(sha256(utf8("source evidence"))),
                    "intakeReceiptDigest", jsonString(sha256(utf8("intake receipt")))),
            "selectedApplication",
                jsonObject(
                    "applicationOperationId", jsonString(applicationOperationId.toString()),
                    "applicationRequestId", jsonString(selectedCommit.requestId().toString()),
                    "appliedCommitId", jsonString(selectedCommit.commitId().toString()),
                    "bindingDigest", jsonString(selectedCommit.digest()),
                    "appliedResultDigest", jsonString(sha256(appliedBytes))),
            "accountOrder",
                jsonObject(
                    "operationId", jsonString(operationId.toString()),
                    "fenceId", jsonString(fenceId.toString()),
                    "actorAccountId", jsonString(identity.accountUuid().toString()),
                    "publicationRequestId", jsonString(publishRequestId.toString()),
                    "selectionDigest", jsonString(sha256(selectedBytes)),
                    "selectedCommitId", jsonString(selectedCommit.commitId().toString()),
                    "bindingDigest", jsonString(sha256(accountBinding))),
            "freeze",
                jsonObject(
                    "publicationFence", jsonString(fenceId.toString()),
                    "publicationRequestId", jsonString(publishRequestId.toString()),
                    "requestDigest", jsonString(requestDigest),
                    "versionStateEpoch", jsonString("1"),
                    "publishWorkflowId", jsonString(workflowId.toString())),
            "checkpoint",
                jsonObject(
                    "appliedCommitId", jsonString(selectedCommit.commitId().toString()),
                    "contentDigest", jsonString(contentDigest),
                    "digestSchemaVersion", "1"),
            "sourceModel", modelJson,
            "artifactDecisions",
                "[{\"artifactKind\":\"NAVMESH\",\"state\":\"NOT_REQUIRED\","
                    + "\"rule\":\"ROOM_LOGICAL_TOPOLOGY_HAS_NO_SPATIAL_NAVMESH_INPUT\"},"
                    + "{\"artifactKind\":\"PATH_GRAPH\",\"state\":\"NOT_REQUIRED\","
                    + "\"rule\":\"AUTHORED_ROOM_EXIT_EDGES_ARE_THE_SUPPORTED_TRAVERSAL_GRAPH\"}]");
    byte[] inventoryBytes = utf8(inventoryJson);
    byte[] publicationOperation =
        encodeFrames(
            List.of(
                utf8("game-design-publication-operation/v2"),
                accountBinding,
                selectorBytes,
                inventoryBytes,
                utf8(sha256(inventoryBytes))));
    byte[] terminal =
        encodeFrames(
            List.of(
                utf8("game-design-publication-terminal/v1"),
                publicationOperation,
                utf8("PUBLISHED"),
                utf8("test-only complete release content"),
                utf8("2")));
    byte[] receipt =
        encodeFrames(
            List.of(
                utf8("account-selected-publication-settlement/v1"),
                accountBinding,
                publicationOperation,
                terminal,
                utf8("PUBLISHED"),
                terminal));

    RuntimeException settlementFailure = null;
    try {
      context
          .dsl()
          .execute(
              "INSERT INTO account_selected_publication_settlements "
                  + "(operation_id, fence_id, account_binding, publication_operation, "
                  + "game_design_outcome, game_design_terminal, world_outcome, "
                  + "world_terminal, receipt) "
                  + "VALUES (?, ?, ?, ?, 'PUBLISHED', ?, 'PUBLISHED', ?, ?)",
              operationId,
              fenceId,
              accountBinding,
              publicationOperation,
              terminal,
              terminal,
              receipt);
    } catch (RuntimeException failure) {
      settlementFailure = failure;
    }
    if (accepted) {
      if (settlementFailure != null) {
        throw settlementFailure;
      }
      assertThat(
              context
                  .dsl()
                  .resultQuery(
                      "SELECT count(*) FROM account_selected_publication_settlements "
                          + "WHERE operation_id = ?",
                      operationId)
                  .fetchOne(0, Long.class))
          .isEqualTo(1L);
    } else {
      assertPostgresCheckViolation(
          settlementFailure, "invalid required family count must fail selected settlement");
    }
  }

  private static String familyCount(String family, int count) {
    return jsonObject("family", jsonString(family), "rowCount", Integer.toString(count));
  }

  private static void assertGameLogicSourceParticipationRejected(
      TestContext context,
      TestIdentity identity,
      UUID versionUuid,
      DraftCommitBinding selectedCommit,
      String namespace,
      String sourcePayload) {
    UUID operationId = UUID.randomUUID();
    UUID fenceId = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    byte[] sourcePayloadBytes = utf8(sourcePayload);
    byte[] bundlePayloadBytes = utf8(publicationBundle(true));
    ControlUiIssuance issuance =
        insertCommittedControlUiIssuance(context, identity, sourcePayloadBytes, bundlePayloadBytes);
    byte[] scopeBytes =
        encodeFrames(
            List.of(
                utf8("account-game-logic-intake-source-read/v1"),
                utf8(namespace),
                utf8(operationId.toString()),
                utf8(fenceId.toString()),
                utf8(requestId.toString()),
                utf8(identity.accountUuid().toString()),
                selectedCommit.canonicalBytes(),
                utf8(sha256(selectedCommit.canonicalBytes())),
                utf8("spiffe://firemud/ns/" + namespace + "/sa/account-service"),
                utf8("GAME_LOGIC_INTAKE_SOURCE")));
    RuntimeException reservationFailure = null;
    try {
      context
          .transaction()
          .executeWithoutResult(
              status ->
                  context
                      .dsl()
                      .execute(
                          "INSERT INTO account_game_logic_intake_source_read_reservations "
                              + "(operation_id, fence_id, intake_request_id, actor_account_uuid, "
                              + "tenant_uuid, version_uuid, scope_bytes, scope_digest, "
                              + "issuance_operation_id, issuance_fence, source_payload, "
                              + "issuance_bundle, outbox_checkpoints) "
                              + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                          operationId,
                          fenceId,
                          requestId,
                          identity.accountUuid(),
                          identity.tenantUuid(),
                          versionUuid,
                          scopeBytes,
                          sha256(scopeBytes),
                          issuance.operationId(),
                          issuance.fence(),
                          sourcePayloadBytes,
                          bundlePayloadBytes,
                          utf8("[]")));
    } catch (RuntimeException failure) {
      reservationFailure = failure;
    }
    assertPostgresCheckViolation(
        reservationFailure, "invalid preliminary source vector must fail at PostgreSQL commit");
  }

  private static void assertPostgresCheckViolation(RuntimeException failure, String reason) {
    assertThat(failure).as(reason).isNotNull();
    Throwable rootCause = failure;
    while (rootCause.getCause() != null) {
      rootCause = rootCause.getCause();
    }
    assertThat(rootCause).as(reason).isInstanceOf(SQLException.class);
    assertThat(((SQLException) rootCause).getSQLState()).as(reason).isEqualTo("23514");
  }

  private static String jsonObject(String... fields) {
    if (fields.length % 2 != 0) {
      throw new IllegalArgumentException("JSON object fields must be key/value pairs");
    }
    List<String> members = new ArrayList<>();
    for (int index = 0; index < fields.length; index += 2) {
      members.add(jsonString(fields[index]) + ":" + fields[index + 1]);
    }
    return "{" + String.join(",", members) + "}";
  }

  private static String jsonString(String value) {
    return "\""
        + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\b", "\\b")
            .replace("\f", "\\f")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        + "\"";
  }

  private static TestIdentity testIdentity(TestContext context) {
    Account account = account("player");
    context
        .transaction()
        .executeWithoutResult(status -> new AccountRepository(context.dsl()).save(account));

    UUID tenantUuid = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    String suffix = UUID.randomUUID().toString();
    context
        .dsl()
        .execute(
            "INSERT INTO account_fresh_tenant_identity_associations "
                + "(schema_version, target_namespace, creation_request_id, operation_id, "
                + "request_digest, canonical_tenant_id, source_game_row_id, source_game_tenant_key, "
                + "provenance_kind, evidence_digest) "
                + "VALUES (1, 'test', ?, ?, ?, ?, ?, ?, 'NEW_GAME_ROW', ?)",
            requestId,
            operationId,
            sha256(("request-" + suffix).getBytes(StandardCharsets.UTF_8)),
            tenantUuid,
            Math.max(1L, System.nanoTime() & Long.MAX_VALUE),
            "fixture-" + suffix.substring(0, 8),
            sha256(("evidence-" + suffix).getBytes(StandardCharsets.UTF_8)));
    return new TestIdentity(account.getAccountUuid(), tenantUuid);
  }

  private static ControlUiIssuance insertCommittedControlUiIssuance(
      TestContext context, TestIdentity identity, byte[] sourcePayload, byte[] bundlePayload) {
    UUID operationId = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    UUID tokenJti = UUID.randomUUID();
    long issuedAt = Instant.now().getEpochSecond();
    long expiresAt = issuedAt + 300;
    OffsetDateTime recoveryExpiresAt =
        OffsetDateTime.ofInstant(Instant.ofEpochSecond(issuedAt + 30), ZoneOffset.UTC);
    byte[] candidate = new byte[] {1, 2, 3};
    byte[] response = new byte[] {4, 5, 6};
    byte[] ownerBinding = new byte[] {7, 8, 9};
    String tokenHash = sha256(tokenJti.toString().getBytes(StandardCharsets.UTF_8)).substring(7);

    context
        .transaction()
        .executeWithoutResult(
            status -> {
              context
                  .dsl()
                  .execute(
                      "INSERT INTO account_control_ui_issuance_operations "
                          + "(request_id, operation_id, token_jti, account_uuid, tenant_uuid, "
                          + "caller_workload, caller_context_id, request_mac_key_id, request_digest, "
                          + "claims_payload, source_payload, bundle_payload, signer_receipt, "
                          + "issued_at_epoch_second, expires_at_epoch_second, recovery_expires_at, status) "
                          + "VALUES (?, ?, ?, ?, ?, 'test-workload', ?, 'test-key', ?, ?, ?, ?, ?, ?, ?, "
                          + "?::timestamptz, 'PREPARED')",
                      requestId,
                      operationId,
                      tokenJti,
                      identity.accountUuid(),
                      identity.tenantUuid(),
                      UUID.randomUUID(),
                      "a".repeat(64),
                      candidate,
                      sourcePayload,
                      bundlePayload,
                      candidate,
                      issuedAt,
                      expiresAt,
                      recoveryExpiresAt);
              context
                  .dsl()
                  .execute(
                      "UPDATE account_control_ui_issuance_operations "
                          + "SET status = 'CANDIDATE', token_hash = ?, pending_registry = ?, "
                          + "active_registry = ? WHERE operation_id = ?",
                      tokenHash,
                      candidate,
                      candidate,
                      operationId);
              context
                  .dsl()
                  .execute(
                      "INSERT INTO account_control_ui_response_envelopes "
                          + "(operation_id, encrypted_response, owner_binding, recovery_expires_at) "
                          + "VALUES (?, ?, ?, ?::timestamptz)",
                      operationId,
                      response,
                      ownerBinding,
                      recoveryExpiresAt);
              context
                  .dsl()
                  .execute(
                      "UPDATE account_control_ui_issuance_operations SET status = 'COMMITTED', "
                          + "pending_receipt = ?, committed_at = CURRENT_TIMESTAMP "
                          + "WHERE operation_id = ?",
                      response,
                      operationId);
            });
    return new ControlUiIssuance(operationId, 1L);
  }

  private static void exerciseSelectedPublication(
      TestContext context,
      TestIdentity identity,
      byte[] sourceEvidence,
      String sourcePayload,
      String bundlePayload,
      String publishRequestId,
      String inputIntent,
      int declaredSourceCount,
      boolean accepted) {
    byte[] sourcePayloadBytes = sourcePayload.getBytes(StandardCharsets.UTF_8);
    byte[] bundlePayloadBytes = bundlePayload.getBytes(StandardCharsets.UTF_8);
    ControlUiIssuance issuance =
        insertCommittedControlUiIssuance(context, identity, sourcePayloadBytes, bundlePayloadBytes);
    UUID operationId = UUID.randomUUID();
    UUID fenceId = UUID.randomUUID();
    byte[] input =
        encodeFrames(
            List.of(
                utf8("account-publication-input/v1"),
                utf8(identity.accountUuid().toString()),
                utf8(inputIntent)));
    byte[] binding =
        encodeFrames(
            List.of(
                utf8("account-publication-authorization/v1"),
                utf8(operationId.toString()),
                utf8(fenceId.toString()),
                input,
                utf8(Integer.toString(declaredSourceCount)),
                sourceEvidence));
    byte[] outboxCheckpoints = "[]".getBytes(StandardCharsets.UTF_8);
    byte[] evidenceCopy = sourceEvidence.clone();
    String sourceKey = "HOSTED_TERMS:environment-boundary/" + "e".repeat(512);

    context
        .dsl()
        .execute(
            "INSERT INTO account_draft_authorization_source_locks (source_key) VALUES (?) "
                + "ON CONFLICT DO NOTHING",
            sourceKey);
    try {
      context
          .transaction()
          .executeWithoutResult(
              status -> {
                context
                    .dsl()
                    .execute(
                        "INSERT INTO account_selected_publication_authorizations "
                            + "(operation_id, fence_id, actor_account_uuid, tenant_uuid, "
                            + "publish_request_id, input_digest, binding, world_evidence, "
                            + "issuance_operation_id, issuance_fence, "
                            + "source_payload, issuance_bundle, "
                            + "outbox_checkpoints) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        operationId,
                        fenceId,
                        identity.accountUuid(),
                        identity.tenantUuid(),
                        publishRequestId,
                        sha256(input),
                        binding,
                        new byte[] {1},
                        issuance.operationId(),
                        issuance.fence(),
                        sourcePayloadBytes,
                        bundlePayloadBytes,
                        outboxCheckpoints);
                context
                    .dsl()
                    .execute(
                        "INSERT INTO account_selected_publication_sources "
                            + "(operation_id, source_key, source_evidence) VALUES (?, ?, ?)",
                        operationId,
                        sourceKey,
                        evidenceCopy);
              });
      assertThat(accepted)
          .as("selected publication should satisfy its deferred completeness guard")
          .isTrue();
    } catch (RuntimeException failure) {
      if (accepted) {
        throw failure;
      }
      Throwable rootCause = failure;
      while (rootCause.getCause() != null) {
        rootCause = rootCause.getCause();
      }
      assertThat(rootCause)
          .as("missing or contradictory JSON completeness evidence must fail in PostgreSQL")
          .isInstanceOf(SQLException.class);
      assertThat(((SQLException) rootCause).getSQLState()).isEqualTo("23514");
    }
  }

  private static String publicationSourcePayload(
      TestIdentity identity, boolean includeAccount, boolean includeTenant, String sourcesJson) {
    List<String> fields = new ArrayList<>();
    if (includeAccount) {
      fields.add("\"accountId\":\"" + identity.accountUuid() + "\"");
    }
    if (includeTenant) {
      fields.add("\"tenantId\":\"" + identity.tenantUuid() + "\"");
    }
    if (sourcesJson != null) {
      fields.add("\"sources\":" + sourcesJson);
    }
    return "{" + String.join(",", fields) + "}";
  }

  private static String publicationBundle(boolean includeIssuanceFence) {
    return includeIssuanceFence
        ? "{\"issuanceFence\":1,\"outboxCheckpoints\":[]}"
        : "{\"outboxCheckpoints\":[]}";
  }

  private static String publicationIntent(
      TestIdentity identity,
      String publishRequestId,
      boolean includeTenant,
      boolean includeRequest) {
    List<String> fields = new ArrayList<>();
    if (includeTenant) {
      fields.add("\"canonicalTenantId\":\"" + identity.tenantUuid() + "\"");
    }
    if (includeRequest) {
      fields.add("\"publishRequestId\":\"" + publishRequestId + "\"");
    }
    return "{\"intent\":{" + String.join(",", fields) + "}}";
  }

  private static String sourceJsonArray(List<byte[]> sourceEvidence) {
    return "["
        + sourceEvidence.stream()
            .map(value -> "\"" + Base64.getEncoder().encodeToString(value) + "\"")
            .collect(java.util.stream.Collectors.joining(","))
        + "]";
  }

  private static byte[] sourceEvidence(String kind, String scope) {
    return encodeFrames(List.of(utf8("account-draft-source-evidence/v1"), utf8(kind), utf8(scope)));
  }

  private static byte[] encodeFrames(List<byte[]> frames) {
    ByteArrayOutputStream encoded = new ByteArrayOutputStream();
    for (byte[] frame : frames) {
      encoded.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(frame.length).array());
      encoded.writeBytes(frame);
    }
    return encoded.toByteArray();
  }

  private static byte[] utf8(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void draftFenceInspectionDoesNotLockOperationRowsAndOwnerWriteKeepsSourceLocks() {
    TestContext context = context(null);
    SourceEvidence source =
        new SourceEvidence(
            SourceKind.GLOBAL_ROLES,
            UUID.randomUUID().toString(),
            "1",
            "1",
            null,
            null,
            utf8("test-only draft fence inspection source"));
    DraftAuthorizationFenceBinding binding = draftBinding(source);
    DraftAuthorizationFenceRepository fences = new DraftAuthorizationFenceRepository(context.dsl());
    context.transaction().executeWithoutResult(status -> fences.reserve(binding));

    SourceEvidence changedSource =
        new SourceEvidence(
            source.kind(),
            source.scopeId(),
            source.generation(),
            source.sourceVersion(),
            source.checkpointStream(),
            source.checkpointSequence(),
            utf8("changed test-only source evidence"));
    DraftAuthorizationFenceBinding changedBinding =
        new DraftAuthorizationFenceBinding(
            binding.operationId(),
            binding.requestId(),
            binding.commitId(),
            binding.fenceId(),
            binding.actorAccountId(),
            binding.tenantId(),
            binding.versionId(),
            binding.baseCommitId(),
            binding.expectedDraftEpoch(),
            binding.gameDesignBinding(),
            binding.normalizedInput(),
            binding.inputDigest(),
            List.of(changedSource),
            binding.schemaVersion(),
            binding.requiredOwners());

    context
        .transaction()
        .executeWithoutResult(
            status -> {
              assertThat(fences.read(binding).ordering())
                  .isEqualTo(DraftAuthorizationFenceRepository.Ordering.RESERVED);
              assertThat(fences.readSettlement(binding))
                  .isEqualTo(DraftAuthorizationFenceRepository.Settlement.PENDING);
              assertThat(fences.readOriginalBinding(binding.operationId())).contains(binding);
              assertThat(fences.readOwnerResult(binding, Owner.GAME_DESIGN)).isEmpty();
              assertThat(fences.readUnresolvedOperations(null, 10))
                  .extracting(DraftAuthorizationFenceRepository.UnresolvedOperation::binding)
                  .containsExactly(binding);
              assertThatThrownBy(() -> fences.read(changedBinding))
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessageContaining("changed immutable Draft operation");

              // This separate connection can take the operation row while every inspection above
              // remains in its caller transaction; no timing or thread scheduling is involved.
              assertThat(lockFenceNowait(context, binding.operationId()))
                  .isEqualTo(binding.operationId());
            });

    context
        .transaction()
        .executeWithoutResult(
            status ->
                assertThat(fences.claimCommitOrder(binding).ordering())
                    .isEqualTo(DraftAuthorizationFenceRepository.Ordering.COMMIT_ORDER));

    OwnerReadback ownerReadback =
        new OwnerReadback(
            Owner.GAME_DESIGN,
            Outcome.COMMITTED,
            binding.operationId(),
            binding.commitId(),
            binding.fenceId(),
            binding.inputDigest(),
            binding.canonicalBytes(),
            utf8("test-only exact owner result"));
    context
        .transaction()
        .executeWithoutResult(
            status -> {
              fences.recordOwnerReadback(binding, ownerReadback);
              assertThat(fences.readOwnerResult(binding, Owner.GAME_DESIGN))
                  .hasValueSatisfying(
                      result -> assertThat(result.outcome()).isEqualTo(Outcome.COMMITTED));
              assertThatThrownBy(() -> lockSourceNowait(context, source.key()))
                  .isInstanceOf(DataAccessException.class);
              assertThatThrownBy(() -> lockFenceNowait(context, binding.operationId()))
                  .isInstanceOf(DataAccessException.class);
            });
  }

  @Test
  void hostedTermsDisclosureHandoffPersistsExactSourceAndFencesOnlyThatSource() {
    TestContext context = context(null);
    UUID hostedScopeId = UUID.randomUUID();
    UUID termsVersionId = UUID.randomUUID();
    byte[] document =
        "synthetic hosted-terms catalog fixture; not legal terms or a publication"
            .getBytes(StandardCharsets.UTF_8);
    HostedTermsCatalogVersion candidate =
        new HostedTermsCatalogVersion(
            termsVersionId,
            hostedScopeId,
            null,
            "TEST FIXTURE ONLY - no authenticated legal identity",
            1,
            document,
            HostedTermsEncoding.digest(document),
            1,
            1,
            HostedTermsCatalogVersion.Materiality.INITIAL,
            null,
            null,
            "test-only/no-publication",
            1,
            "test-only/no-notice",
            1,
            Instant.parse("2040-01-01T00:00:00Z"));
    HostedTermsRepository hostedTerms = new HostedTermsRepository(context.dsl());
    byte[][] persistedCatalogEvidence = new byte[1][];
    context
        .transaction()
        .executeWithoutResult(
            status -> {
              hostedTerms.ensureScope(hostedScopeId);
              hostedTerms.insertCandidate(candidate);
              persistedCatalogEvidence[0] =
                  HostedTermsEncoding.catalog(
                      hostedTerms.readCatalog(hostedScopeId, termsVersionId));
            });
    SourceEvidence exactSource =
        new SourceEvidence(
            SourceKind.HOSTED_TERMS,
            hostedScopeId.toString(),
            "1",
            "1",
            null,
            null,
            persistedCatalogEvidence[0]);
    assertThat(
            context
                .dsl()
                .resultQuery(
                    "SELECT count(*) FROM account_draft_authorization_source_locks "
                        + "WHERE source_key = ?",
                    exactSource.key())
                .fetchOne(0, Long.class))
        .as("the hosted-terms scope insert creates exactly one source lock")
        .isEqualTo(1L);
    byte[] exactSourceEvidence = exactSource.canonicalBytes();
    UUID handoffId = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    byte[] handoffBinding =
        "synthetic PREPARED disclosure intent only; no dispatch or legal authorization"
            .getBytes(StandardCharsets.UTF_8);
    OffsetDateTime effectiveAt =
        OffsetDateTime.ofInstant(Instant.parse("2040-02-01T00:00:00Z"), ZoneOffset.UTC);

    context
        .dsl()
        .execute(
            "INSERT INTO account_hosted_terms_disclosure_handoffs "
                + "(handoff_id, request_id, kind, source_key, predecessor_digest, "
                + "candidate_digest, effective_at, binding, binding_digest, status, "
                + "dispatch_attempts) VALUES (?, ?, 'CATALOG', ?, ?, ?, ?::timestamptz, ?, ?, "
                + "'PREPARED', 0)",
            handoffId,
            requestId,
            exactSource.key(),
            sha256("test-only predecessor".getBytes(StandardCharsets.UTF_8)),
            HostedTermsEncoding.digest(document),
            effectiveAt,
            handoffBinding,
            sha256(handoffBinding));

    byte[] directTerminalPayload = "direct terminal fixture".getBytes(StandardCharsets.UTF_8);
    assertThatThrownBy(
            () ->
                context
                    .dsl()
                    .execute(
                        "INSERT INTO account_hosted_terms_disclosure_handoffs "
                            + "(handoff_id, request_id, kind, source_key, predecessor_digest, "
                            + "candidate_digest, effective_at, binding, binding_digest, status, "
                            + "dispatch_attempts, result_outcome, result_payload, result_digest, "
                            + "result_recorded_at) VALUES (?, ?, 'CATALOG', ?, ?, ?, ?::timestamptz, ?, ?, "
                            + "'DISCLOSED', 1, 'DISCLOSED', ?, ?, CURRENT_TIMESTAMP)",
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        exactSource.key(),
                        sha256("direct predecessor".getBytes(StandardCharsets.UTF_8)),
                        HostedTermsEncoding.digest(document),
                        effectiveAt,
                        handoffBinding,
                        sha256(handoffBinding),
                        directTerminalPayload,
                        sha256(directTerminalPayload)))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                context
                    .dsl()
                    .execute(
                        "INSERT INTO account_hosted_terms_disclosure_handoffs "
                            + "(handoff_id, request_id, kind, source_key, predecessor_digest, "
                            + "candidate_digest, effective_at, binding, binding_digest, status, "
                            + "dispatch_attempts) VALUES (?, ?, 'CATALOG', ?, ?, ?, ?::timestamptz, ?, ?, "
                            + "'DISPATCH_AUTHORIZED', 1)",
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        exactSource.key(),
                        sha256("direct authorization predecessor".getBytes(StandardCharsets.UTF_8)),
                        HostedTermsEncoding.digest(document),
                        effectiveAt,
                        handoffBinding,
                        sha256(handoffBinding)))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                context
                    .dsl()
                    .execute(
                        "UPDATE account_hosted_terms_disclosure_handoffs "
                            + "SET status = 'DISPATCH_AUTHORIZED', dispatch_attempts = 1 "
                            + "WHERE handoff_id = ?",
                        handoffId))
        .isInstanceOf(DataAccessException.class);
    assertThat(
            context
                .dsl()
                .resultQuery(
                    "SELECT status FROM account_hosted_terms_disclosure_handoffs "
                        + "WHERE handoff_id = ?",
                    handoffId)
                .fetchOne(0, String.class))
        .isEqualTo("PREPARED");

    UUID unrelatedHandoffId = UUID.randomUUID();
    context
        .dsl()
        .execute(
            "INSERT INTO account_hosted_terms_disclosure_handoffs "
                + "(handoff_id, request_id, kind, source_key, predecessor_digest, "
                + "candidate_digest, effective_at, binding, binding_digest, status, "
                + "dispatch_attempts) VALUES (?, ?, 'CATALOG', ?, ?, ?, ?::timestamptz, ?, ?, "
                + "'PREPARED', 0)",
            unrelatedHandoffId,
            UUID.randomUUID(),
            exactSource.key(),
            sha256("unrelated predecessor".getBytes(StandardCharsets.UTF_8)),
            HostedTermsEncoding.digest(document),
            effectiveAt,
            handoffBinding,
            sha256(handoffBinding));
    context
        .dsl()
        .execute(
            "INSERT INTO account_hosted_terms_disclosure_sources "
                + "(handoff_id, source_key, source_evidence, source_evidence_digest) "
                + "VALUES (?, ?, ?, ?)",
            unrelatedHandoffId,
            exactSource.key(),
            exactSourceEvidence,
            sha256(exactSourceEvidence));
    assertThatThrownBy(
            () ->
                context
                    .dsl()
                    .execute(
                        "UPDATE account_hosted_terms_disclosure_handoffs "
                            + "SET status = 'DISPATCH_AUTHORIZED', dispatch_attempts = 1 "
                            + "WHERE handoff_id = ?",
                        handoffId))
        .isInstanceOf(DataAccessException.class);
    assertThat(
            context
                .dsl()
                .resultQuery(
                    "SELECT status FROM account_hosted_terms_disclosure_handoffs "
                        + "WHERE handoff_id = ?",
                    handoffId)
                .fetchOne(0, String.class))
        .isEqualTo("PREPARED");

    context
        .dsl()
        .execute(
            "INSERT INTO account_hosted_terms_disclosure_sources "
                + "(handoff_id, source_key, source_evidence, source_evidence_digest) "
                + "VALUES (?, ?, ?, ?)",
            handoffId,
            exactSource.key(),
            exactSourceEvidence,
            sha256(exactSourceEvidence));

    String maximumEnvironmentBoundary = "e".repeat(512);
    String maximumEnvironmentSourceKey =
        "HOSTED_TERMS:environment-boundary/" + maximumEnvironmentBoundary;
    assertThat(maximumEnvironmentSourceKey.getBytes(StandardCharsets.UTF_8)).hasSize(546);
    context
        .dsl()
        .execute(
            "INSERT INTO account_draft_authorization_source_locks (source_key) VALUES (?)",
            maximumEnvironmentSourceKey);
    UUID maximumKeyHandoffId = UUID.randomUUID();
    context
        .dsl()
        .execute(
            "INSERT INTO account_hosted_terms_disclosure_handoffs "
                + "(handoff_id, request_id, kind, source_key, predecessor_digest, candidate_digest, "
                + "effective_at, binding, binding_digest, status, dispatch_attempts) "
                + "VALUES (?, ?, 'ENVIRONMENT_BINDING', ?, ?, ?, ?::timestamptz, ?, ?, 'PREPARED', 0)",
            maximumKeyHandoffId,
            UUID.randomUUID(),
            maximumEnvironmentSourceKey,
            sha256("maximum environment predecessor".getBytes(StandardCharsets.UTF_8)),
            HostedTermsEncoding.digest(document),
            effectiveAt,
            handoffBinding,
            sha256(handoffBinding));
    context
        .dsl()
        .execute(
            "INSERT INTO account_hosted_terms_disclosure_sources "
                + "(handoff_id, source_key, source_evidence, source_evidence_digest) "
                + "VALUES (?, ?, ?, ?)",
            maximumKeyHandoffId,
            maximumEnvironmentSourceKey,
            exactSourceEvidence,
            sha256(exactSourceEvidence));
    String oversizedSourceKey = "é".repeat(1025);
    assertThat(oversizedSourceKey.getBytes(StandardCharsets.UTF_8)).hasSize(2050);
    assertThatThrownBy(
            () ->
                context
                    .dsl()
                    .execute(
                        "INSERT INTO account_draft_authorization_source_locks (source_key) VALUES (?)",
                        oversizedSourceKey))
        .isInstanceOf(DataAccessException.class);

    var persisted =
        Objects.requireNonNull(
            context
                .dsl()
                .fetchOne(
                    "SELECT h.*, s.source_evidence, s.source_evidence_digest "
                        + "FROM account_hosted_terms_disclosure_handoffs h "
                        + "JOIN account_hosted_terms_disclosure_sources s "
                        + "ON s.handoff_id = h.handoff_id WHERE h.handoff_id = ?",
                    handoffId));
    assertThat(persisted.get("request_id", UUID.class)).isEqualTo(requestId);
    assertThat(persisted.get("kind", String.class)).isEqualTo("CATALOG");
    assertThat(persisted.get("source_key", String.class)).isEqualTo(exactSource.key());
    assertThat(persisted.get("predecessor_digest", String.class))
        .isEqualTo(sha256("test-only predecessor".getBytes(StandardCharsets.UTF_8)));
    assertThat(persisted.get("candidate_digest", String.class))
        .isEqualTo(HostedTermsEncoding.digest(document));
    assertThat(persisted.get("effective_at", OffsetDateTime.class)).isEqualTo(effectiveAt);
    assertThat(persisted.get("binding", byte[].class)).containsExactly(handoffBinding);
    assertThat(persisted.get("binding_digest", String.class)).isEqualTo(sha256(handoffBinding));
    assertThat(persisted.get("status", String.class)).isEqualTo("PREPARED");
    assertThat(persisted.get("dispatch_attempts", Integer.class)).isZero();
    assertThat(persisted.get("source_evidence", byte[].class)).containsExactly(exactSourceEvidence);
    assertThat(persisted.get("source_evidence_digest", String.class))
        .isEqualTo(sha256(exactSourceEvidence));

    assertThatThrownBy(
            () ->
                context
                    .dsl()
                    .execute(
                        "UPDATE account_hosted_terms_disclosure_sources "
                            + "SET source_evidence = ? WHERE handoff_id = ? AND source_key = ?",
                        new byte[] {1},
                        handoffId,
                        exactSource.key()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                context
                    .dsl()
                    .execute(
                        "DELETE FROM account_hosted_terms_disclosure_sources "
                            + "WHERE handoff_id = ? AND source_key = ?",
                        handoffId,
                        exactSource.key()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                context
                    .dsl()
                    .execute(
                        "UPDATE account_hosted_terms_disclosure_handoffs SET status = 'AMBIGUOUS', "
                            + "dispatch_attempts = 1 WHERE handoff_id = ?",
                        handoffId))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                context
                    .dsl()
                    .execute(
                        "UPDATE account_hosted_terms_disclosure_handoffs "
                            + "SET binding = ? WHERE handoff_id = ?",
                        new byte[] {2},
                        handoffId))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                context
                    .dsl()
                    .execute(
                        "DELETE FROM account_hosted_terms_disclosure_handoffs WHERE handoff_id = ?",
                        handoffId))
        .isInstanceOf(DataAccessException.class);
    assertThat(
            context
                .dsl()
                .resultQuery(
                    "SELECT status FROM account_hosted_terms_disclosure_handoffs "
                        + "WHERE handoff_id = ?",
                    handoffId)
                .fetchOne(0, String.class))
        .isEqualTo("PREPARED");

    DraftAuthorizationFenceRepository fences = new DraftAuthorizationFenceRepository(context.dsl());
    DraftAuthorizationFenceBinding preparedCapture = draftBinding(exactSource);
    context.transaction().executeWithoutResult(status -> fences.reserve(preparedCapture));

    // This is only a persisted test fixture state for exercising the exact-read predicate. No
    // publisher is authenticated, no RPC/dispatch is performed, and no consumer is activated.
    context
        .dsl()
        .execute(
            "UPDATE account_hosted_terms_disclosure_handoffs SET status = 'DISPATCH_AUTHORIZED', "
                + "dispatch_attempts = 1 WHERE handoff_id = ?",
            handoffId);

    byte[] mismatchedDocument =
        "different synthetic hosted-terms candidate; not legal terms or a publication"
            .getBytes(StandardCharsets.UTF_8);
    HostedTermsCatalogVersion mismatchedCandidate =
        new HostedTermsCatalogVersion(
            UUID.randomUUID(),
            hostedScopeId,
            termsVersionId,
            candidate.operatorLegalIdentity(),
            candidate.operatorIdentityVersion(),
            mismatchedDocument,
            HostedTermsEncoding.digest(mismatchedDocument),
            2,
            1,
            HostedTermsCatalogVersion.Materiality.NONMATERIAL,
            "test-only/materiality-evidence",
            1L,
            "test-only/no-publication",
            2,
            "test-only/no-notice",
            2,
            Instant.parse("2041-01-01T00:00:00Z"));
    SourceEvidence mismatchedSource =
        new SourceEvidence(
            SourceKind.HOSTED_TERMS,
            hostedScopeId.toString(),
            "1",
            "2",
            null,
            null,
            HostedTermsEncoding.catalog(mismatchedCandidate));
    DraftAuthorizationFenceBinding mismatchedCapture = draftBinding(mismatchedSource);
    context.transaction().executeWithoutResult(status -> fences.reserve(mismatchedCapture));

    DraftAuthorizationFenceBinding exactCapture = draftBinding(exactSource);
    assertThatThrownBy(
            () ->
                context.transaction().executeWithoutResult(status -> fences.reserve(exactCapture)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Exact hosted terms source has authorized disclosure");
    assertThat(
            context
                .dsl()
                .resultQuery(
                    "SELECT count(*) FROM account_draft_authorization_fences WHERE operation_id = ?",
                    exactCapture.operationId())
                .fetchOne(0, Long.class))
        .isZero();

    assertThatThrownBy(
            () ->
                context.dsl().execute("TRUNCATE account_hosted_terms_disclosure_handoffs CASCADE"))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () -> context.dsl().execute("TRUNCATE account_hosted_terms_disclosure_sources"))
        .isInstanceOf(DataAccessException.class);
    assertThat(
            context
                .dsl()
                .resultQuery(
                    "SELECT count(*) FROM account_hosted_terms_disclosure_handoffs "
                        + "WHERE handoff_id = ?",
                    handoffId)
                .fetchOne(0, Long.class))
        .isEqualTo(1L);
    assertThat(
            context
                .dsl()
                .resultQuery(
                    "SELECT count(*) FROM account_hosted_terms_disclosure_sources "
                        + "WHERE handoff_id = ? AND source_key = ?",
                    handoffId,
                    exactSource.key())
                .fetchOne(0, Long.class))
        .isEqualTo(1L);

    context
        .dsl()
        .execute(
            "UPDATE account_hosted_terms_disclosure_handoffs SET status = 'AMBIGUOUS' "
                + "WHERE handoff_id = ?",
            handoffId);
    context
        .dsl()
        .execute(
            "UPDATE account_hosted_terms_disclosure_handoffs SET status = 'DISPATCH_AUTHORIZED', "
                + "dispatch_attempts = 2 WHERE handoff_id = ?",
            handoffId);
    byte[] disclosedPayload =
        "test-only terminal disclosure result".getBytes(StandardCharsets.UTF_8);
    byte[] mismatchedDigestPayload =
        "different test-only terminal disclosure result".getBytes(StandardCharsets.UTF_8);
    assertThatThrownBy(
            () ->
                context
                    .dsl()
                    .execute(
                        "UPDATE account_hosted_terms_disclosure_handoffs SET status = 'DISCLOSED', "
                            + "result_outcome = 'DISCLOSED', result_payload = ?, result_digest = ?, "
                            + "result_recorded_at = CURRENT_TIMESTAMP WHERE handoff_id = ?",
                        disclosedPayload,
                        sha256(mismatchedDigestPayload),
                        handoffId))
        .isInstanceOf(DataAccessException.class);
    assertThat(
            context
                .dsl()
                .resultQuery(
                    "SELECT status FROM account_hosted_terms_disclosure_handoffs "
                        + "WHERE handoff_id = ?",
                    handoffId)
                .fetchOne(0, String.class))
        .isEqualTo("DISPATCH_AUTHORIZED");

    context
        .dsl()
        .execute(
            "UPDATE account_hosted_terms_disclosure_handoffs SET status = 'DISCLOSED', "
                + "result_outcome = 'DISCLOSED', result_payload = ?, result_digest = ?, "
                + "result_recorded_at = CURRENT_TIMESTAMP WHERE handoff_id = ?",
            disclosedPayload,
            sha256(disclosedPayload),
            handoffId);
    assertThat(
            context
                .dsl()
                .resultQuery(
                    "SELECT status, dispatch_attempts FROM account_hosted_terms_disclosure_handoffs "
                        + "WHERE handoff_id = ?",
                    handoffId)
                .fetchOne())
        .satisfies(
            row -> {
              assertThat(row.get("status", String.class)).isEqualTo("DISCLOSED");
              assertThat(row.get("dispatch_attempts", Integer.class)).isEqualTo(2);
            });
  }

  @Test
  void v73RetainedAccountStaysUnenrolledWhileFreshRepositoryAccountGetsExactBirthSources() {
    TestContext context = context("73");
    String retainedSuffix = UUID.randomUUID().toString();
    Long retainedId =
        Objects.requireNonNull(
                context
                    .dsl()
                    .fetchOne(
                        "INSERT INTO accounts (username, email, password_hash, role) "
                            + "VALUES (?, ?, ?, 'player') RETURNING id",
                        "retained-" + retainedSuffix,
                        retainedSuffix + "@example.test",
                        "retained-password-" + retainedSuffix))
            .get(0, Long.class);
    UUID retainedUuid =
        Objects.requireNonNull(
            context
                .dsl()
                .resultQuery("SELECT account_uuid FROM accounts WHERE id = ?", retainedId)
                .fetchOne(0, UUID.class));

    migrate(context, null);
    assertThat(
            context
                .dsl()
                .resultQuery(
                    "SELECT version FROM flyway_schema_history "
                        + "WHERE success AND version = '98'")
                .fetchOne(0, String.class))
        .isEqualTo("98");
    for (String relation :
        new String[] {
          "account_hosted_terms_disclosure_handoffs", "account_hosted_terms_disclosure_sources"
        }) {
      assertThat(
              context
                  .dsl()
                  .resultQuery("SELECT to_regclass(?)::TEXT", relation)
                  .fetchOne(0, String.class))
          .as("V98 forward relation %s", relation)
          .isNotNull();
    }
    Account fresh = account(null);
    context
        .transaction()
        .executeWithoutResult(status -> new AccountRepository(context.dsl()).save(fresh));

    assertThat(count(context, "account_authority_source_records", "account_uuid", retainedUuid))
        .isZero();
    assertThat(count(context, "account_global_role_sources", "account_uuid", retainedUuid))
        .isZero();
    assertThat(count(context, "account_platform_restriction_births", "account_uuid", retainedUuid))
        .isZero();

    assertThat(
            count(
                context,
                "account_authority_source_records",
                "account_uuid",
                fresh.getAccountUuid()))
        .isEqualTo(1L);
    assertThat(
            context
                .dsl()
                .resultQuery(
                    "SELECT count(*) FROM account_global_role_sources "
                        + "WHERE account_uuid = ? AND global_role_source_version = 1 "
                        + "AND global_roles = ARRAY[]::TEXT[]",
                    fresh.getAccountUuid())
                .fetchOne(0, Long.class))
        .isEqualTo(1L);
    assertThat(
            context
                .dsl()
                .resultQuery(
                    "SELECT count(*) FROM account_platform_restriction_births "
                        + "WHERE account_uuid = ? AND revision = 1 AND enforcement_epoch = 1 "
                        + "AND source_kind = 'CATEGORY' AND restriction_state = 'NONRESTRICTED'",
                    fresh.getAccountUuid())
                .fetchOne(0, Long.class))
        .isEqualTo(2L);
    assertThat(
            context
                .dsl()
                .resultQuery(
                    "SELECT count(*) FROM account_platform_restriction_birth_outbox "
                        + "WHERE account_uuid = ? AND outbox_sequence = 1",
                    fresh.getAccountUuid())
                .fetchOne(0, Long.class))
        .isEqualTo(2L);
    assertThat(
            count(
                context,
                "account_platform_restriction_projections",
                "account_uuid",
                fresh.getAccountUuid()))
        .isEqualTo(2L);

    assertThatThrownBy(
            () ->
                context
                    .dsl()
                    .execute(
                        "UPDATE account_global_role_sources "
                            + "SET global_roles = ARRAY['moderator']::TEXT[] "
                            + "WHERE account_uuid = ?",
                        fresh.getAccountUuid()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                context
                    .dsl()
                    .execute(
                        "UPDATE account_platform_restriction_births "
                            + "SET payload_digest = repeat('0', 64) "
                            + "WHERE account_uuid = ? AND category = 'account_security_lock'",
                        fresh.getAccountUuid()))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void malformedDraftOwnerEvidenceCannotSettleOrRewriteItsSourceChange() {
    TestContext context = context(null);
    UUID operationId = UUID.randomUUID();
    UUID changeId = UUID.randomUUID();
    String sourceKey = "TEST_SOURCE:" + UUID.randomUUID();
    byte[] malformedBinding = "not-a-canonical-draft-fence".getBytes(StandardCharsets.UTF_8);

    context
        .dsl()
        .execute(
            "INSERT INTO account_draft_authorization_source_locks (source_key) VALUES (?)",
            sourceKey);
    context
        .dsl()
        .execute(
            "INSERT INTO account_draft_authorization_fences "
                + "(operation_id, request_id, commit_id, fence_id, binding, ordering) "
                + "VALUES (?, ?, ?, ?, ?, 'RESERVED')",
            operationId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            malformedBinding);
    context
        .dsl()
        .execute(
            "INSERT INTO account_draft_authorization_sources "
                + "(operation_id, source_key, source_evidence) VALUES (?, ?, ?)",
            operationId,
            sourceKey,
            "malformed-source-evidence".getBytes(StandardCharsets.UTF_8));
    context
        .dsl()
        .execute(
            "UPDATE account_draft_authorization_fences SET ordering = 'COMMIT_ORDER', "
                + "ordered_at = CURRENT_TIMESTAMP WHERE operation_id = ?",
            operationId);
    context
        .dsl()
        .execute(
            "INSERT INTO account_draft_authorization_source_changes (change_id, binding, status) "
                + "VALUES (?, ?, 'WAITING')",
            changeId,
            malformedBinding);
    context
        .dsl()
        .execute(
            "INSERT INTO account_draft_authorization_changed_scopes (change_id, source_key) "
                + "VALUES (?, ?)",
            changeId,
            sourceKey);

    assertThatThrownBy(
            () ->
                context
                    .dsl()
                    .execute(
                        "INSERT INTO account_draft_authorization_owner_readbacks "
                            + "(operation_id, owner, outcome, readback) "
                            + "VALUES (?, 'GAME_DESIGN', 'COMMITTED', ?)",
                        operationId,
                        new byte[] {1}))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                context
                    .dsl()
                    .execute(
                        "UPDATE account_draft_authorization_source_changes "
                            + "SET status = 'SOURCE_COMMITTED', committed_at = CURRENT_TIMESTAMP "
                            + "WHERE change_id = ?",
                        changeId))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                context
                    .dsl()
                    .execute(
                        "DELETE FROM account_draft_authorization_source_changes WHERE change_id = ?",
                        changeId))
        .isInstanceOf(DataAccessException.class);
    assertThat(
            context
                .dsl()
                .resultQuery(
                    "SELECT status FROM account_draft_authorization_source_changes WHERE change_id = ?",
                    changeId)
                .fetchOne(0, String.class))
        .isEqualTo("WAITING");
    assertThat(
            context
                .dsl()
                .resultQuery(
                    "SELECT count(*) FROM account_draft_authorization_owner_readbacks "
                        + "WHERE operation_id = ?",
                    operationId)
                .fetchOne(0, Long.class))
        .isZero();
  }

  @Test
  void nonoperativeTermsAndUnverifiedCreatorSourcesStayImmutableAndRejectContradictoryEvidence() {
    TestContext context = context(null);
    TestIdentity identity = testIdentity(context);

    UUID creatorPartyId = UUID.randomUUID();
    byte[] creatorPayload = "test-only unverified creator source".getBytes(StandardCharsets.UTF_8);
    context
        .dsl()
        .execute(
            "INSERT INTO account_individual_creator_party_sources "
                + "(creator_party_id, account_uuid, verification_status, identity_version, "
                + "source_version, source_payload, source_digest) "
                + "VALUES (?, ?, 'UNVERIFIED', 1, 1, ?, ?)",
            creatorPartyId,
            identity.accountUuid(),
            creatorPayload,
            sha256(creatorPayload));

    assertThatThrownBy(
            () ->
                context
                    .dsl()
                    .execute(
                        "UPDATE account_individual_creator_party_sources "
                            + "SET source_version = source_version WHERE creator_party_id = ?",
                        creatorPartyId))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                context
                    .dsl()
                    .execute(
                        "DELETE FROM account_individual_creator_party_sources "
                            + "WHERE creator_party_id = ?",
                        creatorPartyId))
        .isInstanceOf(DataAccessException.class);

    UUID missingTenantSource = UUID.randomUUID();
    byte[] creatorHistory = "no canonical tenant owner source".getBytes(StandardCharsets.UTF_8);
    assertThatThrownBy(
            () ->
                context
                    .dsl()
                    .execute(
                        "INSERT INTO account_tenant_creator_party_history "
                            + "(history_id, tenant_uuid, origin, creator_party_id, source_version, "
                            + "evidence_payload, evidence_digest) "
                            + "VALUES (?, ?, 'FRESH_INITIAL', ?, 1, ?, ?)",
                        UUID.randomUUID(),
                        missingTenantSource,
                        creatorPartyId,
                        creatorHistory,
                        sha256(creatorHistory)))
        .isInstanceOf(DataAccessException.class);
    assertThat(
            context
                .dsl()
                .resultQuery(
                    "SELECT count(*) FROM account_tenant_creator_party_history "
                        + "WHERE tenant_uuid = ?",
                    missingTenantSource)
                .fetchOne(0, Long.class))
        .isZero();

    UUID retainedHistoryId = UUID.randomUUID();
    byte[] retainedHistoryPayload =
        "test-only retained creator-party history".getBytes(StandardCharsets.UTF_8);
    context
        .dsl()
        .execute(
            "INSERT INTO account_tenant_creator_party_history "
                + "(history_id, tenant_uuid, origin, creator_party_id, source_version, "
                + "evidence_payload, evidence_digest) "
                + "VALUES (?, ?, 'RETAINED', ?, 1, ?, ?)",
            retainedHistoryId,
            identity.tenantUuid(),
            creatorPartyId,
            retainedHistoryPayload,
            sha256(retainedHistoryPayload));

    assertThatThrownBy(
            () ->
                context.dsl().execute("TRUNCATE account_individual_creator_party_sources CASCADE"))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () -> context.dsl().execute("TRUNCATE account_tenant_creator_party_history CASCADE"))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                context
                    .dsl()
                    .execute("TRUNCATE account_fresh_creator_party_association_operations"))
        .isInstanceOf(DataAccessException.class);
    assertThat(
            context
                .dsl()
                .resultQuery(
                    "SELECT count(*) FROM account_individual_creator_party_sources "
                        + "WHERE creator_party_id = ?",
                    creatorPartyId)
                .fetchOne(0, Long.class))
        .isEqualTo(1L);
    assertThat(
            context
                .dsl()
                .resultQuery(
                    "SELECT count(*) FROM account_tenant_creator_party_history "
                        + "WHERE history_id = ?",
                    retainedHistoryId)
                .fetchOne(0, Long.class))
        .isEqualTo(1L);
    assertThat(
            context
                .dsl()
                .resultQuery(
                    "SELECT count(*) FROM account_fresh_creator_party_association_operations")
                .fetchOne(0, Long.class))
        .isZero();

    UUID hostedScopeId = UUID.randomUUID();
    UUID termsVersionId = UUID.randomUUID();
    byte[] nonoperativeDocument =
        "synthetic test fixture; not legal terms or a publication".getBytes(StandardCharsets.UTF_8);
    byte[] versionPayload =
        "synthetic nonoperative catalog candidate".getBytes(StandardCharsets.UTF_8);
    context
        .dsl()
        .execute(
            "INSERT INTO account_hosted_terms_scopes (hosted_scope_id) VALUES (?)", hostedScopeId);
    context
        .dsl()
        .execute(
            "INSERT INTO account_hosted_terms_catalog_versions "
                + "(version_id, hosted_scope_id, predecessor_version_id, operator_legal_identity, "
                + "operator_identity_version, document_bytes, document_digest, source_version, "
                + "material_generation, materiality, publication_evidence_reference, "
                + "publication_evidence_version, notice_evidence_reference, notice_evidence_version, "
                + "effective_at, version_payload, version_digest) "
                + "VALUES (?, ?, NULL, 'TEST FIXTURE ONLY', 1, ?, ?, 1, 1, 'INITIAL', "
                + "'no-publication-fixture', 1, 'no-notice-fixture', 1, ?::timestamptz, ?, ?)",
            termsVersionId,
            hostedScopeId,
            nonoperativeDocument,
            sha256(nonoperativeDocument),
            OffsetDateTime.now(ZoneOffset.UTC).plusDays(1),
            versionPayload,
            sha256(versionPayload));

    assertThatThrownBy(
            () ->
                context
                    .dsl()
                    .execute(
                        "UPDATE account_hosted_terms_catalog_versions "
                            + "SET operator_legal_identity = 'changed fixture' WHERE version_id = ?",
                        termsVersionId))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                context
                    .dsl()
                    .execute(
                        "DELETE FROM account_hosted_terms_catalog_versions WHERE version_id = ?",
                        termsVersionId))
        .isInstanceOf(DataAccessException.class);
    assertThat(
            context
                .dsl()
                .resultQuery(
                    "SELECT current_version_id FROM account_hosted_terms_scopes "
                        + "WHERE hosted_scope_id = ?",
                    hostedScopeId)
                .fetchOne(0, UUID.class))
        .isNull();
    assertThat(
            context
                .dsl()
                .resultQuery("SELECT count(*) FROM account_hosted_terms_publication_operations")
                .fetchOne(0, Long.class))
        .isZero();

    byte[] affirmativeAction = "{}".getBytes(StandardCharsets.UTF_8);
    assertThatThrownBy(
            () ->
                context
                    .dsl()
                    .execute(
                        "INSERT INTO account_individual_hosted_terms_acceptances "
                            + "(evidence_id, action_request_id, creator_party_id, account_uuid, "
                            + "hosted_scope_id, terms_version_id, document_digest, "
                            + "operator_legal_identity, operator_identity_version, source_version, "
                            + "material_generation, individual_party_source, "
                            + "individual_party_source_digest, "
                            + "affirmative_action_evidence, affirmative_action_digest, accepted_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, 'TEST FIXTURE ONLY', 1, 1, 1, ?, ?, ?, ?, "
                            + "CURRENT_TIMESTAMP)",
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        creatorPartyId,
                        identity.accountUuid(),
                        hostedScopeId,
                        termsVersionId,
                        sha256(nonoperativeDocument),
                        creatorPayload,
                        sha256(creatorPayload),
                        affirmativeAction,
                        sha256(affirmativeAction)))
        .isInstanceOf(DataAccessException.class);
    assertThat(
            context
                .dsl()
                .resultQuery(
                    "SELECT count(*) FROM account_individual_hosted_terms_acceptances "
                        + "WHERE creator_party_id = ?",
                    creatorPartyId)
                .fetchOne(0, Long.class))
        .isZero();
  }

  @Test
  void syntheticHostedTermsAndEnvironmentEvidenceSurvivesTruncateAttempts() {
    TestContext context = context(null);
    TestIdentity identity = testIdentity(context);
    UUID hostedScopeId = UUID.randomUUID();
    UUID termsVersionId = UUID.randomUUID();
    UUID termsPublicationRequestId = UUID.randomUUID();
    UUID creatorPartyId = UUID.randomUUID();
    UUID acceptanceEvidenceId = UUID.randomUUID();
    UUID acceptanceActionRequestId = UUID.randomUUID();
    String environmentBoundary = "test-only/environment-boundary";
    UUID bindingPublicationRequestId = UUID.randomUUID();
    UUID bindingId = UUID.randomUUID();
    byte[] document =
        "synthetic test fixture only; not legal terms".getBytes(StandardCharsets.UTF_8);
    byte[] versionPayload =
        "synthetic hosted terms catalog payload".getBytes(StandardCharsets.UTF_8);
    byte[] termsPublicationPayload =
        "synthetic initial catalog publication request".getBytes(StandardCharsets.UTF_8);
    byte[] creatorSource =
        "synthetic verified creator-party evidence".getBytes(StandardCharsets.UTF_8);
    byte[] bindingPayload =
        "synthetic environment binding payload".getBytes(StandardCharsets.UTF_8);
    byte[] bindingPublicationPayload =
        "synthetic environment binding publication request".getBytes(StandardCharsets.UTF_8);
    OffsetDateTime effectiveAt = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1);
    String affirmativeActionJson =
        "{\"schema\":\"account-hosted-terms-affirmative-action/v1\","
            + "\"actionRequestId\":\""
            + acceptanceActionRequestId
            + "\",\"affirmative\":true,\"accountId\":\""
            + identity.accountUuid()
            + "\",\"creatorPartyId\":\""
            + creatorPartyId
            + "\",\"hostedScopeId\":\""
            + hostedScopeId
            + "\",\"shownVersionId\":\""
            + termsVersionId
            + "\",\"shownDocumentDigest\":\""
            + sha256(document)
            + "\",\"shownOperatorLegalIdentity\":\"TEST FIXTURE ONLY\","
            + "\"shownOperatorIdentityVersion\":\"1\"}";
    byte[] affirmativeAction = affirmativeActionJson.getBytes(StandardCharsets.UTF_8);

    // These isolated synthetic rows exercise the persisted guards only; no production writer or
    // runtime activation is involved.
    context
        .transaction()
        .executeWithoutResult(
            status -> {
              context
                  .dsl()
                  .execute(
                      "INSERT INTO account_hosted_terms_scopes (hosted_scope_id) VALUES (?)",
                      hostedScopeId);
              context
                  .dsl()
                  .execute(
                      "INSERT INTO account_hosted_terms_catalog_versions "
                          + "(version_id, hosted_scope_id, predecessor_version_id, "
                          + "operator_legal_identity, operator_identity_version, document_bytes, "
                          + "document_digest, source_version, material_generation, materiality, "
                          + "publication_evidence_reference, publication_evidence_version, "
                          + "notice_evidence_reference, notice_evidence_version, effective_at, "
                          + "version_payload, version_digest) "
                          + "VALUES (?, ?, NULL, 'TEST FIXTURE ONLY', 1, ?, ?, 1, 1, 'INITIAL', "
                          + "'test-only/publication', 1, 'test-only/notice', 1, ?::timestamptz, ?, ?)",
                      termsVersionId,
                      hostedScopeId,
                      document,
                      sha256(document),
                      effectiveAt,
                      versionPayload,
                      sha256(versionPayload));
              context
                  .dsl()
                  .execute(
                      "INSERT INTO account_hosted_terms_publication_operations "
                          + "(request_id, hosted_scope_id, request_payload, request_digest, status) "
                          + "VALUES (?, ?, ?, ?, 'RECEIVED')",
                      termsPublicationRequestId,
                      hostedScopeId,
                      termsPublicationPayload,
                      sha256(termsPublicationPayload));
              context
                  .dsl()
                  .execute(
                      "UPDATE account_hosted_terms_publication_operations "
                          + "SET status = 'SCHEDULED', candidate_version_id = ? "
                          + "WHERE request_id = ?",
                      termsVersionId,
                      termsPublicationRequestId);
              context
                  .dsl()
                  .execute(
                      "UPDATE account_hosted_terms_scopes SET current_version_id = ?, "
                          + "current_source_version = 1, current_material_generation = 1 "
                          + "WHERE hosted_scope_id = ?",
                      termsVersionId,
                      hostedScopeId);
              context
                  .dsl()
                  .execute(
                      "UPDATE account_hosted_terms_publication_operations SET status = 'COMMITTED', "
                          + "result_payload = ?, result_digest = ?, committed_at = CURRENT_TIMESTAMP "
                          + "WHERE request_id = ?",
                      versionPayload,
                      sha256(versionPayload),
                      termsPublicationRequestId);
              context
                  .dsl()
                  .execute(
                      "INSERT INTO account_individual_creator_party_sources "
                          + "(creator_party_id, account_uuid, verification_status, identity_version, "
                          + "policy_reference, policy_version, verification_evidence_reference, "
                          + "verification_evidence_version, source_version, source_payload, source_digest) "
                          + "VALUES (?, ?, 'VERIFIED', 1, 'test-only/policy', 1, "
                          + "'test-only/verification', 1, 1, ?, ?)",
                      creatorPartyId,
                      identity.accountUuid(),
                      creatorSource,
                      sha256(creatorSource));
              context
                  .dsl()
                  .execute(
                      "INSERT INTO account_individual_hosted_terms_acceptances "
                          + "(evidence_id, action_request_id, creator_party_id, account_uuid, "
                          + "hosted_scope_id, terms_version_id, document_digest, "
                          + "operator_legal_identity, operator_identity_version, source_version, "
                          + "material_generation, individual_party_source, "
                          + "individual_party_source_digest, affirmative_action_evidence, "
                          + "affirmative_action_digest, accepted_at) "
                          + "VALUES (?, ?, ?, ?, ?, ?, ?, 'TEST FIXTURE ONLY', 1, 1, 1, ?, ?, ?, ?, "
                          + "CURRENT_TIMESTAMP)",
                      acceptanceEvidenceId,
                      acceptanceActionRequestId,
                      creatorPartyId,
                      identity.accountUuid(),
                      hostedScopeId,
                      termsVersionId,
                      sha256(document),
                      creatorSource,
                      sha256(creatorSource),
                      affirmativeAction,
                      sha256(affirmativeAction));
              context
                  .dsl()
                  .execute(
                      "INSERT INTO account_hosted_terms_environment_binding_heads "
                          + "(environment_boundary) VALUES (?)",
                      environmentBoundary);
              context
                  .dsl()
                  .execute(
                      "INSERT INTO account_hosted_terms_environment_binding_publications "
                          + "(request_id, environment_boundary, request_payload, request_digest, status) "
                          + "VALUES (?, ?, ?, ?, 'RECEIVED')",
                      bindingPublicationRequestId,
                      environmentBoundary,
                      bindingPublicationPayload,
                      sha256(bindingPublicationPayload));
              context
                  .dsl()
                  .execute(
                      "INSERT INTO account_hosted_terms_environment_bindings "
                          + "(binding_id, publication_request_id, environment_boundary, hosted_scope_id, "
                          + "operator_legal_identity, operator_identity_version, catalog_version_id, "
                          + "catalog_source_version, authenticated_publisher_identity, "
                          + "publication_event_identity, publication_evidence_digest, "
                          + "predecessor_binding_id, predecessor_source_version, source_version, "
                          + "binding_payload, binding_digest) "
                          + "VALUES (?, ?, ?, ?, 'TEST FIXTURE ONLY', 1, ?, 1, 'test-only/publisher', "
                          + "'test-only/event', ?, NULL, NULL, 1, ?, ?)",
                      bindingId,
                      bindingPublicationRequestId,
                      environmentBoundary,
                      hostedScopeId,
                      termsVersionId,
                      sha256(bindingPublicationPayload),
                      bindingPayload,
                      sha256(bindingPayload));
              context
                  .dsl()
                  .execute(
                      "UPDATE account_hosted_terms_environment_binding_publications "
                          + "SET status = 'COMMITTED', candidate_binding_id = ?, result_payload = ?, "
                          + "result_digest = ?, committed_at = CURRENT_TIMESTAMP WHERE request_id = ?",
                      bindingId,
                      bindingPayload,
                      sha256(bindingPayload),
                      bindingPublicationRequestId);
              context
                  .dsl()
                  .execute(
                      "UPDATE account_hosted_terms_environment_binding_heads "
                          + "SET current_binding_id = ?, current_source_version = 1 "
                          + "WHERE environment_boundary = ?",
                      bindingId,
                      environmentBoundary);
            });

    assertTruncateRejected(context, "account_hosted_terms_catalog_versions");
    assertTruncateRejected(context, "account_hosted_terms_scopes");
    assertTruncateRejected(context, "account_individual_hosted_terms_acceptances");
    assertTruncateRejected(context, "account_hosted_terms_publication_operations");
    assertTruncateRejected(context, "account_hosted_terms_environment_bindings");
    assertTruncateRejected(context, "account_hosted_terms_environment_binding_publications");
    assertThat(count(context, "account_hosted_terms_scopes", "hosted_scope_id", hostedScopeId))
        .isEqualTo(1L);
    assertThat(
            count(context, "account_hosted_terms_catalog_versions", "version_id", termsVersionId))
        .isEqualTo(1L);
    assertThat(
            count(
                context,
                "account_individual_hosted_terms_acceptances",
                "evidence_id",
                acceptanceEvidenceId))
        .isEqualTo(1L);
    assertThat(
            count(
                context,
                "account_hosted_terms_publication_operations",
                "request_id",
                termsPublicationRequestId))
        .isEqualTo(1L);
    assertThat(count(context, "account_hosted_terms_environment_bindings", "binding_id", bindingId))
        .isEqualTo(1L);
    assertThat(
            count(
                context,
                "account_hosted_terms_environment_binding_publications",
                "request_id",
                bindingPublicationRequestId))
        .isEqualTo(1L);
  }

  @Test
  void creatorBootstrapWithoutAuthorizationSourceDeniesBeforeAnyOwnerWrite() {
    TestContext context = context(null);
    Account creator = account("player");
    context
        .transaction()
        .executeWithoutResult(status -> new AccountRepository(context.dsl()).save(creator));

    StaticListableBeanFactory beans = new StaticListableBeanFactory();
    ObjectProvider<AccountTenantCreationBootstrapAuthorizationSource> noAuthorizationSource =
        beans.getBeanProvider(AccountTenantCreationBootstrapAuthorizationSource.class);
    AccountTenantCreationBootstrapService service =
        new AccountTenantCreationBootstrapService(
            new AccountRepository(context.dsl()),
            mock(FreshTenantIdentityAssociationRepository.class),
            mock(AccountCreatorMembershipSourceReader.class),
            mock(AccountMembershipPairAuthorityRepository.class),
            mock(AccountTenantMembershipRepository.class),
            mock(AccountTenantMembershipRoleSnapshotRepository.class),
            mock(AccountAuthorityOutboxRepository.class),
            mock(AccountAuditOutboxRepository.class),
            new AccountTenantCreationBootstrapOperationRepository(context.dsl()),
            noAuthorizationSource);
    FreshTenantCreatorEvidence evidence = creatorEvidence(creator.getAccountUuid());

    assertThatThrownBy(
            () -> context.transaction().executeWithoutResult(status -> service.bootstrap(evidence)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("No current Account creator-authorization/source participant");
    assertThat(
            context
                .dsl()
                .resultQuery("SELECT count(*) FROM account_tenant_creation_bootstrap_operations")
                .fetchOne(0, Long.class))
        .isZero();
    assertThat(
            context
                .dsl()
                .resultQuery("SELECT count(*) FROM account_tenant_membership")
                .fetchOne(0, Long.class))
        .isZero();
    assertThat(
            context
                .dsl()
                .resultQuery("SELECT count(*) FROM account_authority_outbox_events")
                .fetchOne(0, Long.class))
        .isZero();
  }

  @Test
  void statementTruncateCannotRemovePasswordResetLogoutOrBootstrapEvidence() {
    TestContext context = context(null);
    TestIdentity identity = testIdentity(context);

    assertTruncateRejected(context, "account_password_reset_operation_receipts");
    assertTruncateRejected(context, "account_logout_all_operation_receipts");
    assertThat(
            context
                .dsl()
                .resultQuery("SELECT count(*) FROM account_password_reset_operation_receipts")
                .fetchOne(0, Long.class))
        .isZero();
    assertThat(
            context
                .dsl()
                .resultQuery("SELECT count(*) FROM account_logout_all_operation_receipts")
                .fetchOne(0, Long.class))
        .isZero();

    UUID requestId = UUID.randomUUID();
    UUID tenantUuid = UUID.randomUUID();
    UUID creationRequestId = UUID.randomUUID();
    UUID creationOperationId = UUID.randomUUID();
    byte[] creatorEvidence =
        "synthetic creator-bootstrap evidence".getBytes(StandardCharsets.UTF_8);
    byte[] sourceSnapshot = "synthetic tenant source snapshot".getBytes(StandardCharsets.UTF_8);
    byte[] requestPayload = "synthetic bootstrap request".getBytes(StandardCharsets.UTF_8);
    context
        .dsl()
        .execute(
            "INSERT INTO account_tenant_creation_bootstrap_operations "
                + "(request_id, schema_version, initiating_account_uuid, tenant_uuid, "
                + "creation_request_id, creation_operation_id, account_authorization_operation_id, "
                + "account_authorization_digest, creator_evidence_digest, creator_evidence_payload, "
                + "source_snapshot_payload, source_snapshot_digest, request_payload, request_digest, "
                + "baseline_membership_version, baseline_membership_authority_generation, "
                + "baseline_event_sequence, status) "
                + "VALUES (?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 1, 0, 'IN_PROGRESS')",
            requestId,
            identity.accountUuid(),
            tenantUuid,
            creationRequestId,
            creationOperationId,
            requestId,
            sha256("synthetic authorization".getBytes(StandardCharsets.UTF_8)),
            sha256(creatorEvidence),
            creatorEvidence,
            sourceSnapshot,
            sha256(sourceSnapshot),
            requestPayload,
            sha256(requestPayload));

    assertTruncateRejected(context, "account_tenant_creation_bootstrap_operations");
    assertThat(
            context
                .dsl()
                .resultQuery(
                    "SELECT count(*) FROM account_tenant_creation_bootstrap_operations "
                        + "WHERE request_id = ?",
                    requestId)
                .fetchOne(0, Long.class))
        .isEqualTo(1L);
  }

  private static FreshTenantCreatorEvidence creatorEvidence(UUID accountUuid) {
    UUID creationRequestId = UUID.randomUUID();
    UUID creationOperationId = UUID.randomUUID();
    UUID tenantUuid = UUID.randomUUID();
    String namespace = "test";
    String tenantKey = "fixture-tenant";
    String creationRequestDigest =
        GameTenantCreationDigest.requestDigest(
            namespace, creationRequestId, tenantKey, "test fixture", null);
    String creationEvidenceDigest =
        GameTenantCreationDigest.evidenceDigest(
            namespace,
            creationRequestId,
            creationOperationId,
            creationRequestDigest,
            tenantUuid,
            1L,
            tenantKey,
            "NEW_GAME_ROW");
    FreshTenantCreationEvidence creation =
        new FreshTenantCreationEvidence(
            1,
            namespace,
            creationRequestId,
            creationOperationId,
            creationRequestDigest,
            tenantUuid,
            1L,
            tenantKey,
            "NEW_GAME_ROW",
            creationEvidenceDigest);
    UUID authorizationOperationId = UUID.randomUUID();
    String authorizationDigest =
        GameTenantCreationDigest.requestDigest(
            namespace, UUID.randomUUID(), "fixture-authorization", "test fixture", null);
    String creatorDigest =
        FreshTenantCreatorDigest.evidenceDigest(
            1, creation, accountUuid, authorizationOperationId, authorizationDigest);
    return new FreshTenantCreatorEvidence(
        1, creation, accountUuid, authorizationOperationId, authorizationDigest, creatorDigest);
  }

  private static DraftAuthorizationFenceBinding draftBinding(SourceEvidence source) {
    DraftCommitBinding complete =
        DraftCommitBinding.create(
            new DraftCommitBinding.TargetProof(
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                "test-only-tenant",
                2,
                "test-only-tenant",
                "NEW_GAME_ROW"),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "test-only/base",
            List.of(
                new DraftCommitBinding.RevisionPayload(
                    "0",
                    UUID.randomUUID(),
                    DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                    "test-only-world-payload")),
            List.of(
                new DraftCommitBinding.AffectedUnit(
                    DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                    "region",
                    "test-region",
                    "aggregate",
                    "test-region",
                    "0")));
    return new DraftAuthorizationFenceBinding(
        UUID.randomUUID(),
        complete.requestId(),
        complete.commitId(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        complete.target().canonicalTenantId(),
        complete.target().canonicalVersionId(),
        complete.baseCommitId(),
        "0",
        complete.canonicalBytes(),
        complete.canonicalBytes(),
        complete.digest(),
        List.of(source));
  }

  private static Account account(String role) {
    String suffix = UUID.randomUUID().toString();
    Account account = new Account();
    account.setUsername("forward-source-" + suffix.replace("-", ""));
    account.setEmail(suffix.replace("-", "") + "@example.test");
    account.setPasswordHash("test-only-password-hash");
    account.setRole(role);
    account.setLifecycleState(AccountLifecycleState.ACTIVE);
    return account;
  }

  private static long count(TestContext context, String table, String column, UUID value) {
    return Objects.requireNonNull(
        context
            .dsl()
            .resultQuery("SELECT count(*) FROM " + table + " WHERE " + column + " = ?", value)
            .fetchOne(0, Long.class));
  }

  private static void assertTruncateRejected(TestContext context, String relation) {
    assertThatThrownBy(() -> context.dsl().execute("TRUNCATE " + relation + " CASCADE"))
        .as("TRUNCATE of immutable evidence relation %s", relation)
        .isInstanceOf(DataAccessException.class);
  }

  private static UUID lockFenceNowait(TestContext context, UUID operationId) {
    return independentTransaction(
        context,
        dsl ->
            dsl.resultQuery(
                    "SELECT operation_id FROM account_draft_authorization_fences "
                        + "WHERE operation_id = ? FOR UPDATE NOWAIT",
                    operationId)
                .fetchSingle(0, UUID.class));
  }

  private static String lockSourceNowait(TestContext context, String sourceKey) {
    return independentTransaction(
        context,
        dsl ->
            dsl.resultQuery(
                    "SELECT source_key FROM account_draft_authorization_source_locks "
                        + "WHERE source_key = ? FOR UPDATE NOWAIT",
                    sourceKey)
                .fetchSingle(0, String.class));
  }

  private static <T> T independentTransaction(
      TestContext context, java.util.function.Function<DSLContext, T> work) {
    try (Connection connection = POSTGRES.dataSource(context.schema()).getConnection()) {
      connection.setAutoCommit(false);
      try {
        T result = work.apply(DSL.using(connection, SQLDialect.POSTGRES));
        connection.commit();
        return result;
      } catch (RuntimeException | Error failure) {
        try {
          connection.rollback();
        } catch (SQLException rollbackFailure) {
          failure.addSuppressed(rollbackFailure);
        }
        throw failure;
      }
    } catch (SQLException failure) {
      throw new IllegalStateException("Independent PostgreSQL lock probe failed", failure);
    }
  }

  private static String sha256(byte[] value) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static TestContext context(String target) {
    String schema = "account_forward_source_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = POSTGRES.dataSource(schema);
    TestContext context =
        new TestContext(
            schema,
            dataSource,
            DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES),
            new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    migrate(context, target);
    return context;
  }

  private static void migrate(TestContext context, String target) {
    var configuration =
        Flyway.configure()
            .dataSource(context.dataSource())
            .schemas(context.schema())
            .defaultSchema(context.schema())
            .locations("classpath:db/migration")
            .placeholders(Map.of("serviceSchema", context.schema()));
    if (target != null) {
      configuration.target(target);
    }
    configuration.load().migrate();
  }

  private record TestContext(
      String schema,
      DriverManagerDataSource dataSource,
      DSLContext dsl,
      TransactionTemplate transaction) {}

  private record TestIdentity(UUID accountUuid, UUID tenantUuid) {}

  private record ControlUiIssuance(UUID operationId, long fence) {}
}
