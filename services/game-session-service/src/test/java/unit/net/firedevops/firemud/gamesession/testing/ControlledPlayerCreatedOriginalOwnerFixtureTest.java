package net.firedevops.firemud.gamesession.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class ControlledPlayerCreatedOriginalOwnerFixtureTest {
  @Test
  void eachCheckoutUsesTheSuppliedSchemaWithoutClosingTheBorrowedConnection() throws Exception {
    var source = mock(DataSource.class);
    var first = mock(Connection.class);
    var second = mock(Connection.class);
    when(source.getConnection()).thenReturn(first, second);
    var scoped =
        ControlledPlayerCreatedOriginalOwnerFixture.schemaScopedDataSource(
            source, "isolated_owner");

    assertThat(scoped.getConnection()).isSameAs(first);
    assertThat(scoped.getConnection()).isSameAs(second);
    verify(first).setSchema("isolated_owner");
    verify(second).setSchema("isolated_owner");
    verify(first, never()).close();
    verify(second, never()).close();
  }

  @Test
  void credentialedCheckoutUsesTheSameSchemaBoundary() throws Exception {
    var source = mock(DataSource.class);
    var connection = mock(Connection.class);
    when(source.getConnection("controlled-user", "controlled-password")).thenReturn(connection);
    var scoped =
        ControlledPlayerCreatedOriginalOwnerFixture.schemaScopedDataSource(
            source, "isolated_owner");

    assertThat(scoped.getConnection("controlled-user", "controlled-password")).isSameAs(connection);
    verify(connection).setSchema("isolated_owner");
  }

  @Test
  void aSchemaFailureClosesTheConnectionAndPreservesAnyCloseFailure() throws Exception {
    var source = mock(DataSource.class);
    var connection = mock(Connection.class);
    var schemaFailure = new SQLException("controlled schema refusal");
    var closeFailure = new SQLException("controlled close refusal");
    when(source.getConnection()).thenReturn(connection);
    doThrow(schemaFailure).when(connection).setSchema("isolated_owner");
    doThrow(closeFailure).when(connection).close();
    var scoped =
        ControlledPlayerCreatedOriginalOwnerFixture.schemaScopedDataSource(
            source, "isolated_owner");

    assertThatThrownBy(scoped::getConnection).isSameAs(schemaFailure);
    assertThat(schemaFailure.getSuppressed()).containsExactly(closeFailure);
    verify(connection).close();
  }
}
