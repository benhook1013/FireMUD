package net.firedevops.firemud.gamedesign.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import javax.sql.DataSource;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.gamedesign.service.impl.TenantAssociationMigrationService;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

class TenantAssociationManifestJobRunnerTest {
  @Test
  void requiresFlywayDisabledAndExactDatabaseUserForEachMode() {
    assertThatCode(
            () ->
                TenantAssociationManifestJobRunner.requireDatabaseAuthority(
                    "preflight", "firemud_game_design_tenant_preflight", false))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                TenantAssociationManifestJobRunner.requireDatabaseAuthority(
                    "apply", "firemud_game_design_tenant_apply", false))
        .doesNotThrowAnyException();

    for (String[] wrongModeAndUser :
        new String[][] {
          {"preflight", "firemud_game_design_tenant_apply"},
          {"apply", "firemud_game_design_tenant_preflight"},
          {"preflight", "firemud"},
          {"apply", "firemud_game_design_service"},
          {"preflight", null},
          {"apply", ""},
          {"preflight", " "}
        }) {
      assertThatThrownBy(
              () ->
                  TenantAssociationManifestJobRunner.requireDatabaseAuthority(
                      wrongModeAndUser[0], wrongModeAndUser[1], false))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("tenant migration DB credential does not match the mode");
    }
    assertThatThrownBy(
            () ->
                TenantAssociationManifestJobRunner.requireDatabaseAuthority(
                    "unsupported", "firemud_game_design_tenant_preflight", false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("unsupported tenant migration Job mode");
    assertThatThrownBy(
            () ->
                TenantAssociationManifestJobRunner.requireDatabaseAuthority(
                    "preflight", "firemud_game_design_tenant_preflight", true))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("tenant migration requires Flyway to be disabled");
  }

  @Test
  void readsAndClosesTheActualJdbcConnectionForDatabaseIdentity() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    DatabaseMetaData metadata = mock(DatabaseMetaData.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.getMetaData()).thenReturn(metadata);
    when(metadata.getUserName()).thenReturn("firemud_game_design_tenant_preflight");

    assertThat(runner(dataSource).connectedDatabaseUsername())
        .isEqualTo("firemud_game_design_tenant_preflight");
    verify(connection).close();
  }

  @Test
  void rejectsMissingOrUnverifiableJdbcIdentityWithoutLeakingDriverDetails() throws Exception {
    for (String username : new String[] {null, "", " "}) {
      DataSource dataSource = mock(DataSource.class);
      Connection connection = mock(Connection.class);
      DatabaseMetaData metadata = mock(DatabaseMetaData.class);
      when(dataSource.getConnection()).thenReturn(connection);
      when(connection.getMetaData()).thenReturn(metadata);
      when(metadata.getUserName()).thenReturn(username);

      assertThatThrownBy(() -> runner(dataSource).connectedDatabaseUsername())
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("tenant migration DB connection did not identify its user");
      verify(connection).close();
    }

    DataSource unavailableDataSource = mock(DataSource.class);
    when(unavailableDataSource.getConnection())
        .thenThrow(new SQLException("sensitive driver credential diagnostic"));
    assertThatThrownBy(() -> runner(unavailableDataSource).connectedDatabaseUsername())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("tenant migration DB connection could not be verified")
        .hasNoCause()
        .hasMessageNotContaining("sensitive driver credential diagnostic");

    DataSource missingMetadataDataSource = mock(DataSource.class);
    Connection missingMetadataConnection = mock(Connection.class);
    when(missingMetadataDataSource.getConnection()).thenReturn(missingMetadataConnection);
    when(missingMetadataConnection.getMetaData()).thenReturn(null);
    assertThatThrownBy(() -> runner(missingMetadataDataSource).connectedDatabaseUsername())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("tenant migration DB connection is unverifiable");
    verify(missingMetadataConnection).close();

    DataSource unverifiableDataSource = mock(DataSource.class);
    Connection unverifiableConnection = mock(Connection.class);
    when(unverifiableDataSource.getConnection()).thenReturn(unverifiableConnection);
    when(unverifiableConnection.getMetaData())
        .thenThrow(new SQLException("sensitive metadata credential diagnostic"));
    assertThatThrownBy(() -> runner(unverifiableDataSource).connectedDatabaseUsername())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("tenant migration DB connection could not be verified")
        .hasNoCause()
        .hasMessageNotContaining("sensitive metadata credential diagnostic");
    verify(unverifiableConnection).close();
  }

  @Test
  void acceptsOnlyExactDedicatedMigratorCertificate() {
    assertThatCode(
            () ->
                TenantAssociationManifestJobRunner.requireJobIdentity(
                    "dev", peer("dev", "game-design-tenant-migrator")))
        .doesNotThrowAnyException();
    for (GrpcPeerIdentity wrong :
        new GrpcPeerIdentity[] {
          null,
          peer("dev", "game-design-service"),
          peer("pr-12", "game-design-tenant-migrator"),
          peer("dev", "account-tenant-migrator")
        }) {
      assertThatThrownBy(() -> TenantAssociationManifestJobRunner.requireJobIdentity("dev", wrong))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("wrong identity");
    }
  }

  private static GrpcPeerIdentity peer(String namespace, String service) {
    return GrpcPeerIdentity.parseUri("spiffe://firemud/ns/" + namespace + "/sa/" + service)
        .orElseThrow();
  }

  private static TenantAssociationManifestJobRunner runner(DataSource dataSource) {
    return new TenantAssociationManifestJobRunner(
        mock(Environment.class),
        new ObjectMapper(),
        mock(TenantAssociationMigrationService.class),
        dataSource);
  }
}
