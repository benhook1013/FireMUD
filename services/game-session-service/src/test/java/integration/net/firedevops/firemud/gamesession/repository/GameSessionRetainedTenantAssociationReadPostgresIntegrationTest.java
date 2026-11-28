package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshot;
import net.firedevops.firemud.test.TestContainerImages;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class GameSessionRetainedTenantAssociationReadPostgresIntegrationTest {
  private static final String SCHEMA = "gs_retained_association";
  private static final String RESTORED_DATABASE = "restored_gs_association";
  private static final String DUMP = "/tmp/gs-retained-association.dump";
  private static final String NAMESPACE = "gs-retained-history";
  private static final String SIGNER_ID = "retained-approval-fixture";
  private static final String APPROVED_BY = "Opérateur fixture";
  private static final String APPROVAL_REFERENCE = "retained-association-fixture";
  private static final String SIGNED_AT = "2026-01-03T00:00:00Z";
  private static final String SOURCE_KEY = "gamedesign-source-key";
  private static final long SOURCE_ROW_ID = 529;
  private static final long FIRST_TENANT_ID = 9_007_199_254_740_993L;
  private static final List<String> PROTECTED_TABLES =
      List.of(
          "game_session_retained_tenant_association",
          "game_session_retained_tenant_association_payload",
          "game_session_tenant_canonical_claim",
          "game_session_tenant_scope_reservation",
          "gameplay_admission_pointer",
          "game_instances");

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void restoresAndQualifiesOnlyTheOriginalSignedClaimForTheExactGameSessionTarget()
      throws IOException, InterruptedException, GeneralSecurityException {
    var keyGenerator = KeyPairGenerator.getInstance("Ed25519");
    var signer = keyGenerator.generateKeyPair();
    var foreignSigner = keyGenerator.generateKeyPair();
    DriverManagerDataSource source = dataSource(postgres.getDatabaseName());
    migrate(source, "12");
    DSLContext beforeBackup = DSL.using(source, SQLDialect.POSTGRES);
    Original valid =
        insertOriginal(
            beforeBackup, FIRST_TENANT_ID, signer.getPrivate(), 1, false, "NEW_GAME_ROW");
    Original foreign =
        insertOriginal(
            beforeBackup,
            FIRST_TENANT_ID + 1,
            foreignSigner.getPrivate(),
            1,
            false,
            "NEW_GAME_ROW");
    Original otherTarget =
        insertOriginal(
            beforeBackup, FIRST_TENANT_ID + 2, signer.getPrivate(), 2, false, "NEW_GAME_ROW");
    Original nonCanonical =
        insertOriginal(
            beforeBackup, FIRST_TENANT_ID + 3, signer.getPrivate(), 1, true, "NEW_GAME_ROW");
    Original retained =
        insertOriginal(
            beforeBackup, FIRST_TENANT_ID + 4, signer.getPrivate(), 1, false, "RETAINED_GAME_V29");
    List<String> originalRows = v12AssociationRows(beforeBackup);
    assertThat(originalRows).hasSize(5);

    restoreDatabase();
    DriverManagerDataSource restored = dataSource(RESTORED_DATABASE);
    DSLContext sql = DSL.using(restored, SQLDialect.POSTGRES);
    assertThat(v12AssociationRows(sql)).isEqualTo(originalRows);
    migrate(restored, "13");
    assertThat(v13AssociationRows(sql)).isEqualTo(originalRows);
    migrate(restored, null);
    assertThat(v13AssociationRows(sql)).isEqualTo(originalRows);
    assertThat(
            sql.fetchSingle(
                    "SELECT reservation_kind FROM game_session_tenant_canonical_claim "
                        + "WHERE target_namespace = ? AND canonical_tenant_id = ?",
                    NAMESPACE,
                    valid.canonicalTenantId())
                .get(0, String.class))
        .isEqualTo("LEGACY_OCCUPIED");

    var reader =
        new GameSessionRetainedTenantAssociationReadRepository(
            sql, NAMESPACE, Map.of(SIGNER_ID, signer.getPublic()));
    var noTrust = new GameSessionRetainedTenantAssociationReadRepository(sql, NAMESPACE, Map.of());
    var routesBeforeRead = physicalRows(sql);
    assertThat(reader.readCanonicalTenantId(NAMESPACE, valid.target()))
        .contains(valid.canonicalTenantId());
    assertThat(reader.readCanonicalTenantId(NAMESPACE, retained.target()))
        .contains(retained.canonicalTenantId());
    assertThat(reader.readCanonicalTenantId("foreign-namespace", valid.target())).isEmpty();
    assertThat(reader.readCanonicalTenantId(NAMESPACE, target(FIRST_TENANT_ID, 2))).isEmpty();
    assertThat(reader.readCanonicalTenantId(NAMESPACE, target(SOURCE_ROW_ID, 1))).isEmpty();
    assertThat(noTrust.readCanonicalTenantId(NAMESPACE, valid.target())).isEmpty();
    assertThat(reader.readCanonicalTenantId(NAMESPACE, foreign.target())).isEmpty();
    assertThat(reader.readCanonicalTenantId(NAMESPACE, otherTarget.target())).isEmpty();
    assertThat(reader.readCanonicalTenantId(NAMESPACE, nonCanonical.target())).isEmpty();
    assertThat(physicalRows(sql)).isEqualTo(routesBeforeRead);

    assertThat(
            sql.execute(
                "DELETE FROM game_session_retained_tenant_association_payload WHERE operation_id = ?",
                valid.operationId()))
        .isEqualTo(1);
    var afterPayloadExpiry = physicalRows(sql);
    assertThat(reader.readCanonicalTenantId(NAMESPACE, valid.target())).isEmpty();
    assertThat(physicalRows(sql)).isEqualTo(afterPayloadExpiry);
  }

  private static Original insertOriginal(
      DSLContext sql,
      long tenantId,
      PrivateKey signer,
      int signedCatalogRevision,
      boolean nonCanonical,
      String provenanceKind)
      throws GeneralSecurityException, IOException {
    GameplayAdmissionPointerSnapshot target = target(tenantId, 1);
    long pointerId = tenantId - FIRST_TENANT_ID + 201;
    long ownerAccountId = FIRST_TENANT_ID + 30;
    sql.execute(
        "INSERT INTO game_instances (id, tenant_id, runtime_version, owner_account_id, status) "
            + "VALUES (?, ?, '1.0.0', ?, 'RUNNING')",
        target.gameInstanceId(),
        tenantId,
        ownerAccountId);
    sql.execute(
        "INSERT INTO gameplay_tenant_shared_playable_state_namespace "
            + "(tenant_id, playable_state_namespace_id) VALUES (?, ?)",
        tenantId,
        target.playableStateNamespaceId());
    sql.execute(
        "INSERT INTO gameplay_admission_pointer (id, world_slug, world_display_name, realm_slug, "
            + "realm_display_name, tenant_id, game_instance_id, pointer_version, catalog_revision, "
            + "visible, requires_character_selection, public_production_realm, state_scope, "
            + "character_creation_policy, last_updated_by, last_update_reason, realm_id, "
            + "playable_state_namespace_id) "
            + "VALUES (?, ?, 'World', 'main', 'Main', ?, ?, 1, 1, true, false, true, "
            + "'SHARED', 'PRESEEDED_ONLY', 'fixture', 'original', ?, ?)",
        pointerId,
        target.worldSlug(),
        tenantId,
        target.gameInstanceId(),
        target.realmId(),
        target.playableStateNamespaceId());

    UUID canonicalTenantId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    UUID approvalId = UUID.randomUUID();
    String snapshotJson = snapshot(pointerId, target, ownerAccountId, signedCatalogRevision);
    if (nonCanonical) {
      snapshotJson = " " + snapshotJson;
    }
    String snapshotDigest =
        digest(frame("game-session/retained-tenant-identity-snapshot/v2", snapshotJson));
    byte[] manifest =
        frame(
            "game-design/game-session-retained-tenant-association/v1",
            "1",
            approvalId.toString(),
            NAMESPACE,
            SIGNER_ID,
            APPROVED_BY,
            APPROVAL_REFERENCE,
            SIGNED_AT,
            Long.toString(tenantId),
            canonicalTenantId.toString(),
            Long.toString(SOURCE_ROW_ID),
            SOURCE_KEY,
            provenanceKind,
            snapshotDigest);
    String manifestDigest = digest(manifest);
    Signature signature = Signature.getInstance("Ed25519");
    signature.initSign(signer);
    signature.update(manifest);
    String signatureText = Base64.getEncoder().encodeToString(signature.sign());
    String requestDigest =
        digest(
            frame(
                "game-session/retained-tenant-association-request/v1",
                NAMESPACE,
                requestId.toString(),
                manifestDigest,
                signatureText));
    String receiptDigest =
        digest(
            frame(
                "game-session/retained-tenant-association-receipt/v1",
                operationId.toString(),
                requestDigest,
                manifestDigest,
                snapshotDigest));
    sql.execute(
        "INSERT INTO game_session_retained_tenant_association "
            + "(operation_id, approval_schema_version, target_namespace, association_request_id, "
            + "request_digest, approval_operation_id, signer_key_id, approved_by, "
            + "approval_reference, signed_at, legacy_game_session_tenant_id, canonical_tenant_id, "
            + "source_game_row_id, source_game_tenant_key, provenance_kind, game_session_evidence_digest, "
            + "approval_manifest_digest, approval_signature, snapshot_canonical_json, "
            + "snapshot_evidence_digest, receipt_digest) "
            + "VALUES (?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        operationId,
        NAMESPACE,
        requestId,
        requestDigest,
        approvalId,
        SIGNER_ID,
        APPROVED_BY,
        APPROVAL_REFERENCE,
        SIGNED_AT,
        tenantId,
        canonicalTenantId,
        SOURCE_ROW_ID,
        SOURCE_KEY,
        provenanceKind,
        snapshotDigest,
        manifestDigest,
        signatureText,
        snapshotJson,
        snapshotDigest,
        receiptDigest);
    return new Original(canonicalTenantId, operationId, target);
  }

  private static GameplayAdmissionPointerSnapshot target(long tenantId, long catalogRevision) {
    return new GameplayAdmissionPointerSnapshot(
        "world-" + tenantId,
        "World",
        "main",
        "Main",
        tenantId,
        tenantId % 1_000 + 101,
        1,
        true,
        true,
        false,
        "SHARED",
        "PRESEEDED_ONLY",
        catalogRevision,
        new UUID(1, tenantId),
        new UUID(2, tenantId));
  }

  private static String snapshot(
      long pointerId,
      GameplayAdmissionPointerSnapshot target,
      long accountId,
      int signedCatalogRevision)
      throws IOException {
    String json =
        """
        {\
          "schemaVersion":2,\
          "targetNamespace":"%s",\
          "legacyGameSessionTenantId":"%d",\
          "instances":[{\
            "id":"%d","tenant_id":"%d","runtime_version":"1.0.0","script_patch_version":null,\
            "owner_account_id":"%d","owner_account_uuid":null,"status":"RUNNING","row_version":"0",\
            "game_template_id":null,"launch_descriptor_id":null,"version_id":null,\
            "release_bundle_id":null,"version_state_epoch":null,"generation_config_revision":null,\
            "remap_set_id":null,"script_patch_pinned_control_plane_request_id":null,"script_pin_epoch":null\
          }],\
          "pointers":[{\
            "id":"%d","tenant_id":"%d","game_instance_id":"%d","world_slug":"%s","realm_slug":"main",\
            "pointer_version":"1","catalog_revision":"%d","visible":true,\
            "requires_character_selection":false,"state_scope":"SHARED",\
            "character_creation_policy":"PRESEEDED_ONLY","public_production_realm":true,\
            "realm_id":"%s","playable_state_namespace_id":"%s"\
          }],\
          "sharedNamespaces":[{"tenant_id":"%d","playable_state_namespace_id":"%s"}],\
          "backfillIssues":[],"pointerEvents":[],"preparedUpgrades":[]\
        }\
        """
            .formatted(
                NAMESPACE,
                target.tenantId(),
                target.gameInstanceId(),
                target.tenantId(),
                accountId,
                pointerId,
                target.tenantId(),
                target.gameInstanceId(),
                target.worldSlug(),
                signedCatalogRevision,
                target.realmId(),
                target.playableStateNamespaceId(),
                target.tenantId(),
                target.playableStateNamespaceId());
    return new String(Rfc8785CanonicalJson.canonicalizeUtf8(json), StandardCharsets.UTF_8);
  }

  private static byte[] frame(String... values) {
    StringBuilder frame = new StringBuilder();
    for (String value : values) {
      frame.append(value.getBytes(StandardCharsets.UTF_8).length).append(':').append(value);
    }
    return frame.toString().getBytes(StandardCharsets.UTF_8);
  }

  private static String digest(byte[] bytes) throws GeneralSecurityException {
    return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private static DriverManagerDataSource dataSource(String database) {
    String originalUrl = postgres.getJdbcUrl();
    String jdbcUrl = originalUrl.substring(0, originalUrl.lastIndexOf('/') + 1) + database;
    var source =
        new DriverManagerDataSource(jdbcUrl, postgres.getUsername(), postgres.getPassword());
    source.setSchema(SCHEMA);
    return source;
  }

  private static void restoreDatabase() throws IOException, InterruptedException {
    String user = "--username=" + postgres.getUsername();
    String socket = "--host=/var/run/postgresql";
    assertNativeSuccess(
        "pg_dump",
        user,
        socket,
        "--schema=" + SCHEMA,
        "--format=custom",
        "--file=" + DUMP,
        postgres.getDatabaseName());
    assertNativeSuccess("createdb", user, socket, RESTORED_DATABASE);
    assertNativeSuccess(
        "pg_restore", user, socket, "--exit-on-error", "--dbname=" + RESTORED_DATABASE, DUMP);
  }

  private static void assertNativeSuccess(String... command)
      throws IOException, InterruptedException {
    var result = postgres.execInContainer(command);
    assertThat(result.getExitCode()).as("%s: %s", command[0], result.getStderr()).isZero();
  }

  private static void migrate(DriverManagerDataSource source, String target) {
    CanonicalGameplayIssuerTestFixtures.migrateSchema(
        source,
        SCHEMA,
        "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath(),
        target);
  }

  private static List<String> v12AssociationRows(DSLContext sql) {
    return sql.fetch(
            "SELECT to_jsonb(association)::text FROM game_session_retained_tenant_association "
                + "association ORDER BY association.legacy_game_session_tenant_id")
        .getValues(0, String.class);
  }

  private static List<String> v13AssociationRows(DSLContext sql) {
    return sql.fetch(
            """
            SELECT ((to_jsonb(association) - 'captured_at' - 'terminal_outcome')
                     || (to_jsonb(payload) - 'game_session_projection_digest' - 'created_at'))::text
              FROM game_session_retained_tenant_association association
              JOIN game_session_retained_tenant_association_payload payload USING (operation_id)
             ORDER BY association.legacy_game_session_tenant_id
            """)
        .getValues(0, String.class);
  }

  private static List<List<String>> physicalRows(DSLContext sql) {
    return PROTECTED_TABLES.stream()
        .map(
            table ->
                sql.fetch(
                        "SELECT to_jsonb(stored)::text || ':' || xmin::text FROM "
                            + table
                            + " stored ORDER BY 1")
                    .getValues(0, String.class))
        .toList();
  }

  private record Original(
      UUID canonicalTenantId, UUID operationId, GameplayAdmissionPointerSnapshot target) {}
}
