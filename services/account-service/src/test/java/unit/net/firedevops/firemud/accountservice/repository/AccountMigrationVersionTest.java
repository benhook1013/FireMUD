package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

class AccountMigrationVersionTest {
  @Test
  void accountMigrationsHaveDistinctFlywayVersionsWithoutRequiringDocker() throws IOException {
    List<String> migrations;
    try (var files = Files.list(Path.of("src/main/resources/db/migration"))) {
      migrations =
          files
              .map(path -> Objects.requireNonNull(path.getFileName()).toString())
              .filter(name -> name.startsWith("V") && name.endsWith(".sql"))
              .sorted()
              .toList();
    }

    assertThat(migrations)
        .contains(
            "V28__account_audit_delivery_backoff.sql",
            "V35__account_connect_token_issuance_storage.sql",
            "V37__verified_audit_projection_evidence.sql",
            "V37.1__account_bare_login_exchange_storage.sql",
            "V38__account_join_reconciliation.sql",
            "V38.1__fresh_tenant_identity_associations.sql",
            "V39__fresh_uuid_membership_pair_authority.sql",
            "V39.1__migrate_empty_account_connect_storage_to_uuid.sql",
            "V40__account_membership_transition_receipts.sql",
            "V41__protect_account_membership_transition_receipts.sql",
            "V42__account_membership_left_transition_receipts.sql")
        .doesNotContain(
            "V28__account_join_reconciliation.sql",
            "V28__account_membership_transition_receipts.sql",
            "V28.1__account_membership_transition_receipts.sql",
            "V37__account_bare_login_exchange_storage.sql",
            "V38__fresh_tenant_identity_associations.sql",
            "V40__account_membership_left_transition_receipts.sql");
    assertThat(migrations.stream().map(AccountMigrationVersionTest::version).toList())
        .as("Flyway rejects two different migration descriptions with the same version")
        .doesNotHaveDuplicates();
    assertThat(version("V28__account_audit_delivery_backoff.sql"))
        .isEqualTo(version("V28__account_join_reconciliation.sql"));
    assertThat(version("V35__account_connect_token_issuance_storage.sql"))
        .isLessThan(version("V37.1__account_bare_login_exchange_storage.sql"));
    assertThat(version("V37__verified_audit_projection_evidence.sql"))
        .isLessThan(version("V37.1__account_bare_login_exchange_storage.sql"));
    assertThat(version("V37.1__account_bare_login_exchange_storage.sql"))
        .isLessThan(version("V38__account_join_reconciliation.sql"));
    assertThat(version("V38__account_join_reconciliation.sql"))
        .isLessThan(version("V38.1__fresh_tenant_identity_associations.sql"));
    assertThat(version("V38.1__fresh_tenant_identity_associations.sql"))
        .isLessThan(version("V39__fresh_uuid_membership_pair_authority.sql"));
    assertThat(version("V39__fresh_uuid_membership_pair_authority.sql"))
        .isLessThan(version("V39.1__migrate_empty_account_connect_storage_to_uuid.sql"));
    assertThat(version("V38__account_join_reconciliation.sql"))
        .isLessThan(MigrationVersion.fromVersion("39.1"));
    assertThat(version("V40__account_membership_transition_receipts.sql"))
        .isGreaterThan(version("V38__account_join_reconciliation.sql"))
        .isGreaterThan(MigrationVersion.fromVersion("39.1"));
    assertThat(version("V41__protect_account_membership_transition_receipts.sql"))
        .isGreaterThan(version("V40__account_membership_transition_receipts.sql"));
    assertThat(version("V42__account_membership_left_transition_receipts.sql"))
        .isGreaterThan(version("V40__account_membership_transition_receipts.sql"))
        .isGreaterThan(version("V41__protect_account_membership_transition_receipts.sql"));
  }

  @Test
  void receiptProtectionMigrationGuardsOnlyUpdatesAndDeletesWithoutDocker() throws IOException {
    String migration =
        Files.readString(
            Path.of(
                "src/main/resources/db/migration/"
                    + "V41__protect_account_membership_transition_receipts.sql"));

    assertThat(migration)
        .contains(
            "CREATE TRIGGER account_membership_transition_receipts_append_only",
            "BEFORE UPDATE OR DELETE ON account_membership_transition_receipts",
            "EXECUTE FUNCTION reject_account_membership_transition_receipt_mutation()")
        .doesNotContain(
            "BEFORE INSERT ON account_membership_transition_receipts",
            "INSERT OR UPDATE OR DELETE ON account_membership_transition_receipts",
            "ON account_membership_transition_receipt_stream_heads");
  }

  private static MigrationVersion version(String name) {
    int separator = name.indexOf("__");
    assertThat(separator).as("versioned migration naming: %s", name).isGreaterThan(1);
    return MigrationVersion.fromVersion(name.substring(1, separator));
  }
}
