package integration.net.firedevops.firemud.gamedesign.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.maintenance.TenantAssociationManifest;
import net.firedevops.firemud.gamedesign.maintenance.TenantAssociationManifest.Entry;
import net.firedevops.firemud.gamedesign.maintenance.TenantAssociationManifest.Signed;
import net.firedevops.firemud.gamedesign.maintenance.TenantAssociationManifestVerifier;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.service.impl.TenantAssociationMigrationService;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class TenantAssociationMigrationIntegrationTest {
  private static final String SCHEMA = "game_design_tenant_association_proof";
  private static final String ACCOUNT_DIGEST = "sha256:" + "a".repeat(64);
  private static final UUID OPERATION_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void approvedAssociationIsImmutableExactRetryableAndConflictsFailClosed() throws Exception {
    DriverManagerDataSource dataSource = dataSource();
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(SCHEMA)
        .defaultSchema(SCHEMA)
        .table("flyway_schema_history_game_design_service")
        .placeholders(Map.of("serviceSchema", SCHEMA))
        .locations("classpath:db/migration")
        .load()
        .migrate();

    try (Connection connection = dataSource.getConnection()) {
      try (Statement statement = connection.createStatement()) {
        statement.execute("SET search_path TO " + SCHEMA);
      }
      DSLContext dsl = DSL.using(connection, SQLDialect.POSTGRES);
      GameRepository repository = new GameRepository(dsl);
      Game game = new Game();
      game.setTenantId("legacy-game-7");
      game.setName("Approved game");
      Game saved = repository.save(game);
      KeyPair ownerKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
      Map<String, String> trustedKeys =
          Map.of(
              "game-design-owner-2026",
              Base64.getEncoder().encodeToString(ownerKey.getPublic().getEncoded()));
      Signed signed = signed(saved, OPERATION_ID, "legacy-game-7", ownerKey);
      String manifestDigest =
          TenantAssociationManifestVerifier.verify(signed, trustedKeys).manifestDigest();
      TenantAssociationMigrationService service =
          new TenantAssociationMigrationService(dsl, repository);

      var first =
          dsl.transactionResult(configuration -> service.apply(signed, trustedKeys, SCHEMA));
      assertThat(first).hasSize(1);
      assertThat(first.getFirst().legacyAccountTenantId()).isEqualTo(41L);
      assertThat(first.getFirst().canonicalTenantId()).isEqualTo(saved.getCanonicalTenantId());
      assertThat(first.getFirst().manifestDigest()).isEqualTo(manifestDigest);
      assertThat(first.getFirst().schemaVersion()).isEqualTo(1);
      assertThat(service.findByLegacyAccountTenantId(41L)).contains(first.getFirst());
      assertThat(service.findByLegacyAccountTenantId(42L)).isEmpty();

      var exactRetry =
          dsl.transactionResult(configuration -> service.apply(signed, trustedKeys, SCHEMA));
      assertThat(exactRetry).isEqualTo(first);
      assertThat(
              dsl.fetchCount(DSL.table(DSL.name("legacy_account_tenant_association_operations"))))
          .isEqualTo(1);
      assertThat(dsl.fetchCount(DSL.table(DSL.name("legacy_account_tenant_associations"))))
          .isEqualTo(1);

      Signed changedDigest =
          signed(
              saved,
              OPERATION_ID,
              "legacy-game-7",
              KeyPairGenerator.getInstance("Ed25519").generateKeyPair());
      assertThatThrownBy(
              () ->
                  dsl.transactionResult(
                      configuration -> service.apply(changedDigest, trustedKeys, SCHEMA)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("signature is invalid");
      TenantAssociationManifest changedApprovalManifest =
          new TenantAssociationManifest(
              1,
              OPERATION_ID,
              SCHEMA,
              "game-design-owner-2026",
              "different-owner@example.test",
              "reviewed-change-123",
              signed.manifest().signedAt(),
              signed.manifest().entries());
      Signed changedApproval = sign(changedApprovalManifest, ownerKey);
      assertThatThrownBy(
              () ->
                  dsl.transactionResult(
                      configuration -> service.apply(changedApproval, trustedKeys, SCHEMA)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("readback differs");
      assertThatThrownBy(
              () ->
                  dsl.transactionResult(
                      configuration -> service.apply(signed, trustedKeys, "other")))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("different namespace");

      Signed absentSource =
          signed(
              saved,
              UUID.fromString("33333333-3333-4333-8333-333333333333"),
              "not-a-game",
              ownerKey);
      assertThatThrownBy(
              () ->
                  dsl.transactionResult(
                      configuration -> service.apply(absentSource, trustedKeys, SCHEMA)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("source is absent");

      Signed duplicateAccountKey =
          signed(
              saved,
              UUID.fromString("44444444-4444-4444-8444-444444444444"),
              "legacy-game-7",
              ownerKey);
      assertThatThrownBy(
              () ->
                  dsl.transactionResult(
                      configuration -> service.apply(duplicateAccountKey, trustedKeys, SCHEMA)))
          .isInstanceOf(DataAccessException.class);
      assertThat(
              dsl.fetchCount(DSL.table(DSL.name("legacy_account_tenant_association_operations"))))
          .isEqualTo(1);

      assertThatThrownBy(
              () ->
                  dsl.execute(
                      "UPDATE legacy_account_tenant_associations SET "
                          + "account_evidence_digest = ? WHERE legacy_account_tenant_id = ?",
                      "sha256:" + "f".repeat(64),
                      41L))
          .isInstanceOf(DataAccessException.class)
          .hasStackTraceContaining("Approved legacy tenant association evidence is immutable");
    }
  }

  private DriverManagerDataSource dataSource() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    return dataSource;
  }

  private Signed signed(Game saved, UUID operationId, String sourceKey, KeyPair ownerKey)
      throws Exception {
    TenantAssociationManifest manifest =
        new TenantAssociationManifest(
            1,
            operationId,
            SCHEMA,
            "game-design-owner-2026",
            "owner@example.test",
            "reviewed-change-123",
            Instant.parse("2026-09-26T00:00:00Z"),
            List.of(
                new Entry(
                    41L, sourceKey, saved.getCanonicalTenantId(), saved.getId(), ACCOUNT_DIGEST)));
    return sign(manifest, ownerKey);
  }

  private Signed sign(TenantAssociationManifest manifest, KeyPair ownerKey) throws Exception {
    Signature signature = Signature.getInstance("Ed25519");
    signature.initSign(ownerKey.getPrivate());
    signature.update(TenantAssociationManifestVerifier.preimage(manifest));
    return new Signed(manifest, Base64.getEncoder().encodeToString(signature.sign()));
  }
}
