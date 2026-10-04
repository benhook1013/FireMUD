package net.firedevops.firemud.accountservice.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import javax.sql.DataSource;
import net.firedevops.firemud.accountservice.service.impl.LegacyTenantAssociationImportService;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

class LegacyTenantAssociationImportJobRunnerTest {
  @Test
  void requiresFlywayDisabledAndExactDatabaseUserForEachMode() {
    assertThatCode(
            () ->
                LegacyTenantAssociationImportJobRunner.requireDatabaseAuthority(
                    "evidence", "firemud_account_tenant_evidence", false))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                LegacyTenantAssociationImportJobRunner.requireDatabaseAuthority(
                    "import", "firemud_account_tenant_import", false))
        .doesNotThrowAnyException();

    for (String[] wrongModeAndUser :
        new String[][] {
          {"evidence", "firemud_account_tenant_import"},
          {"import", "firemud_account_tenant_evidence"},
          {"evidence", "firemud"},
          {"import", "firemud_account_service"},
          {"evidence", null},
          {"import", ""},
          {"evidence", " "}
        }) {
      assertThatThrownBy(
              () ->
                  LegacyTenantAssociationImportJobRunner.requireDatabaseAuthority(
                      wrongModeAndUser[0], wrongModeAndUser[1], false))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Account tenant migration DB credential does not match the mode");
    }
    assertThatThrownBy(
            () ->
                LegacyTenantAssociationImportJobRunner.requireDatabaseAuthority(
                    "unsupported", "firemud_account_tenant_evidence", false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("unsupported Account tenant migration mode");
    assertThatThrownBy(
            () ->
                LegacyTenantAssociationImportJobRunner.requireDatabaseAuthority(
                    "evidence", "firemud_account_tenant_evidence", true))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Account tenant migration requires Flyway to be disabled");
  }

  @Test
  void readsAndClosesTheActualJdbcConnectionForDatabaseIdentity() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    DatabaseMetaData metadata = mock(DatabaseMetaData.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.getMetaData()).thenReturn(metadata);
    when(metadata.getUserName()).thenReturn("firemud_account_tenant_evidence");

    assertThat(runner(dataSource).connectedDatabaseUsername())
        .isEqualTo("firemud_account_tenant_evidence");
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
          .hasMessage("Account tenant migration DB connection did not identify its user");
      verify(connection).close();
    }

    DataSource unavailableDataSource = mock(DataSource.class);
    when(unavailableDataSource.getConnection())
        .thenThrow(new SQLException("sensitive driver credential diagnostic"));
    assertThatThrownBy(() -> runner(unavailableDataSource).connectedDatabaseUsername())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Account tenant migration DB connection could not be verified")
        .hasNoCause()
        .hasMessageNotContaining("sensitive driver credential diagnostic");

    DataSource missingMetadataDataSource = mock(DataSource.class);
    Connection missingMetadataConnection = mock(Connection.class);
    when(missingMetadataDataSource.getConnection()).thenReturn(missingMetadataConnection);
    when(missingMetadataConnection.getMetaData()).thenReturn(null);
    assertThatThrownBy(() -> runner(missingMetadataDataSource).connectedDatabaseUsername())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Account tenant migration DB connection is unverifiable");
    verify(missingMetadataConnection).close();

    DataSource unverifiableDataSource = mock(DataSource.class);
    Connection unverifiableConnection = mock(Connection.class);
    when(unverifiableDataSource.getConnection()).thenReturn(unverifiableConnection);
    when(unverifiableConnection.getMetaData())
        .thenThrow(new SQLException("sensitive metadata credential diagnostic"));
    assertThatThrownBy(() -> runner(unverifiableDataSource).connectedDatabaseUsername())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Account tenant migration DB connection could not be verified")
        .hasNoCause()
        .hasMessageNotContaining("sensitive metadata credential diagnostic");
    verify(unverifiableConnection).close();
  }

  @Test
  void failsBeforeOwnerEvidenceOrImportWhenSchedulingIsPresent() {
    Environment environment = mock(Environment.class);
    LegacyTenantAssociationImportService importService =
        mock(LegacyTenantAssociationImportService.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<ScheduledAnnotationBeanPostProcessor> schedulingPostProcessor =
        mock(ObjectProvider.class);
    ScheduledAnnotationBeanPostProcessor processor =
        mock(ScheduledAnnotationBeanPostProcessor.class);
    when(schedulingPostProcessor.getIfAvailable()).thenReturn(processor);

    LegacyTenantAssociationImportJobRunner runner =
        new LegacyTenantAssociationImportJobRunner(
            environment, importService, schedulingPostProcessor, mock(DataSource.class));

    assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("scheduling enabled");
    verifyNoInteractions(importService);
  }

  @Test
  void acceptsOnlyExactDedicatedMigratorCertificate() {
    assertThatCode(
            () ->
                LegacyTenantAssociationImportJobRunner.requireJobIdentity(
                    "dev", peer("dev", "account-tenant-migrator")))
        .doesNotThrowAnyException();
    for (GrpcPeerIdentity wrong :
        new GrpcPeerIdentity[] {
          null,
          peer("dev", "account-service"),
          peer("pr-12", "account-tenant-migrator"),
          peer("dev", "game-design-tenant-migrator")
        }) {
      assertThatThrownBy(
              () -> LegacyTenantAssociationImportJobRunner.requireJobIdentity("dev", wrong))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("wrong identity");
    }
  }

  private static GrpcPeerIdentity peer(String namespace, String service) {
    return GrpcPeerIdentity.parseUri("spiffe://firemud/ns/" + namespace + "/sa/" + service)
        .orElseThrow();
  }

  @SuppressWarnings("unchecked")
  private static LegacyTenantAssociationImportJobRunner runner(DataSource dataSource) {
    return new LegacyTenantAssociationImportJobRunner(
        mock(Environment.class),
        mock(LegacyTenantAssociationImportService.class),
        mock(ObjectProvider.class),
        dataSource);
  }
}
