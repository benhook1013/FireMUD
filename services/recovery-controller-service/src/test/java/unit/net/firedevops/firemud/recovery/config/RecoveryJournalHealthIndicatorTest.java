package net.firedevops.firemud.recovery.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.intThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.health.contributor.Status;

class RecoveryJournalHealthIndicatorTest {
  @ParameterizedTest
  @CsvSource({"0,2000,false", "5000,2000,true", "750,750,false"})
  void readinessBoundsTheProbeAndRestoresTheBorrowedConnection(
      int originalTimeout, int expectedTimeout, boolean queryFails) throws SQLException {
    DataSource source = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet rows = mock(ResultSet.class);
    when(source.getConnection()).thenReturn(connection);
    when(connection.getAutoCommit()).thenReturn(true);
    when(connection.getNetworkTimeout()).thenReturn(originalTimeout);
    when(connection.prepareStatement(anyString())).thenReturn(statement);
    if (queryFails) {
      when(statement.executeQuery()).thenThrow(new SQLException("fixture database unavailable"));
    } else {
      when(statement.executeQuery()).thenReturn(rows);
      when(rows.next()).thenReturn(true);
      when(rows.getBoolean(1)).thenReturn(true);
    }

    var indicator = new RecoveryJournalHealthIndicator(source);
    try {
      var health = indicator.health();

      assertThat(health.getStatus()).isEqualTo(queryFails ? Status.DOWN : Status.UP);
      var sequence = inOrder(connection, statement);
      sequence
          .verify(connection)
          .setNetworkTimeout(any(), intThat(timeout -> timeout > 0 && timeout <= expectedTimeout));
      sequence.verify(connection).prepareStatement(anyString());
      sequence.verify(statement).setQueryTimeout(2);
      sequence.verify(statement).executeQuery();
      sequence.verify(connection).setNetworkTimeout(any(), eq(originalTimeout));
      sequence.verify(connection).close();
    } finally {
      indicator.stopProbes();
    }
  }

  @Test
  void readinessTimesOutWhileBorrowingAndDoesNotQueueASecondBorrow() throws SQLException {
    DataSource source = mock(DataSource.class);
    var acquiring = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var borrowFinished = new CountDownLatch(1);
    when(source.getConnection())
        .thenAnswer(ignored -> {
          acquiring.countDown();
          try {
            release.await(10, TimeUnit.SECONDS);
          } finally {
            borrowFinished.countDown();
          }
          throw new SQLException("fixture database unavailable");
        });

    var indicator = new RecoveryJournalHealthIndicator(source);
    try {
      long started = System.nanoTime();
      var health = indicator.health();
      assertThat(System.nanoTime() - started)
          .isBetween(TimeUnit.MILLISECONDS.toNanos(1500), TimeUnit.SECONDS.toNanos(4));
      assertThat(health.getStatus()).isEqualTo(Status.DOWN);
      assertThat(acquiring.getCount()).isZero();
      assertThat(borrowFinished.getCount()).isEqualTo(1);
      assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
      verify(source, times(1)).getConnection();
    } finally {
      release.countDown();
      indicator.stopProbes();
    }
  }

  @Test
  void readinessRejectsConcurrentBorrowDuringCleanupAndAcceptsSequentialProbes() throws Exception {
    DataSource source = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet rows = mock(ResultSet.class);
    when(source.getConnection()).thenReturn(connection);
    when(connection.getAutoCommit()).thenReturn(true);
    when(connection.prepareStatement(anyString())).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(rows);
    when(rows.next()).thenReturn(true);
    when(rows.getBoolean(1)).thenReturn(true);

    var closing = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    doAnswer(
            ignored -> {
              closing.countDown();
              if (!release.await(5, TimeUnit.SECONDS)) {
                throw new SQLException("fixture cleanup was not released");
              }
              return null;
            })
        .doNothing()
        .when(connection)
        .close();

    var indicator = new RecoveryJournalHealthIndicator(source);
    try {
      var first = new FutureTask<>(indicator::health);
      Thread.ofVirtual().start(first);
      assertThat(closing.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
      verify(source, times(1)).getConnection();

      release.countDown();
      assertThat(first.get(5, TimeUnit.SECONDS).getStatus()).isEqualTo(Status.UP);
      for (int probe = 0; probe < 20; probe++) {
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
      }
      verify(source, times(21)).getConnection();
    } finally {
      release.countDown();
      indicator.stopProbes();
    }
  }
}
