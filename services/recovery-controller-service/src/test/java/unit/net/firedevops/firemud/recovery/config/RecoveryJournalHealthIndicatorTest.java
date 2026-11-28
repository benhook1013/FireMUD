package net.firedevops.firemud.recovery.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.intThat;
import static org.mockito.Mockito.atLeast;
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
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.health.contributor.Status;
import org.springframework.test.util.ReflectionTestUtils;

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

    boolean preserveShorterTimeout = originalTimeout == expectedTimeout;
    var indicator =
        preserveShorterTimeout
            ? new RecoveryJournalHealthIndicator(source, TimeUnit.SECONDS.toNanos(15))
            : new RecoveryJournalHealthIndicator(source);
    try {
      var health = indicator.health();

      assertThat(health.getStatus()).isEqualTo(queryFails ? Status.DOWN : Status.UP);
      var sequence = inOrder(connection, statement);
      sequence
          .verify(connection)
          .setNetworkTimeout(
              any(),
              intThat(timeout ->
                  preserveShorterTimeout
                      ? timeout == expectedTimeout
                      : timeout > 0 && timeout <= expectedTimeout));
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
  void readinessTimesOutWhileBorrowingAndDoesNotQueueASecondBorrow() throws Exception {
    DataSource source = mock(DataSource.class);
    var acquiring = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var borrowFinished = new CountDownLatch(1);
    when(source.getConnection())
        .thenAnswer(ignored -> {
          acquiring.countDown();
          try {
            release.await(5, TimeUnit.SECONDS);
          } finally {
            borrowFinished.countDown();
          }
          throw new SQLException("fixture database unavailable");
        });

    var indicator = new RecoveryJournalHealthIndicator(source, TimeUnit.MILLISECONDS.toNanos(100));
    try {
      var first = new FutureTask<>(indicator::health);
      Thread.ofVirtual().start(first);
      assertThat(acquiring.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(first.get(2, TimeUnit.SECONDS).getStatus()).isEqualTo(Status.DOWN);
      assertThat(borrowFinished.getCount()).isEqualTo(1);
      assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
      var probes = (ThreadPoolExecutor) ReflectionTestUtils.getField(indicator, "probes");
      assertThat(probes).isNotNull();
      assertThat(probes.getTaskCount()).isEqualTo(1);
      verify(source, times(1)).getConnection();
    } finally {
      release.countDown();
      indicator.stopProbes();
    }
  }

  @Test
  void overlappingHealthCallersAwaitOneSuccessfulProbe() throws Exception {
    DataSource source = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet rows = mock(ResultSet.class);
    when(source.getConnection()).thenReturn(connection);
    when(connection.getAutoCommit()).thenReturn(true);
    when(connection.prepareStatement(anyString())).thenReturn(statement);
    var queryStarted = new CountDownLatch(1);
    var releaseQuery = new CountDownLatch(1);
    when(statement.executeQuery())
        .thenAnswer(ignored -> {
          queryStarted.countDown();
          releaseQuery.await();
          return rows;
        });
    when(rows.next()).thenReturn(true);
    when(rows.getBoolean(1)).thenReturn(true);

    var indicator = new RecoveryJournalHealthIndicator(source, TimeUnit.SECONDS.toNanos(15));
    try {
      var first = new FutureTask<>(indicator::health);
      Thread.ofVirtual().start(first);
      assertThat(queryStarted.await(5, TimeUnit.SECONDS)).isTrue();

      var second = new FutureTask<>(indicator::health);
      Thread secondThread = Thread.ofVirtual().unstarted(second);
      secondThread.start();
      awaitTimedWaiting(secondThread);
      verify(source, times(1)).getConnection();

      releaseQuery.countDown();
      assertThat(first.get(5, TimeUnit.SECONDS).getStatus()).isEqualTo(Status.UP);
      assertThat(second.get(5, TimeUnit.SECONDS).getStatus()).isEqualTo(Status.UP);
      verify(source, times(1)).getConnection();
    } finally {
      releaseQuery.countDown();
      indicator.stopProbes();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @ResourceLock("postgresql-driver-protocol-logger")
  void readinessChecksDriverTracingAgainAfterSuccessfulProbe(boolean enableTracingDuringProbe)
      throws SQLException {
    DataSource source = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet rows = mock(ResultSet.class);
    var logger = Logger.getLogger("org.postgresql.core.v3.QueryExecutorImpl");
    Level prior = logger.getLevel();
    var enableTracing = new AtomicBoolean(enableTracingDuringProbe);
    when(source.getConnection()).thenReturn(connection);
    when(connection.getAutoCommit()).thenReturn(true);
    when(connection.prepareStatement(anyString())).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(rows);
    when(rows.next()).thenReturn(true);
    when(rows.getBoolean(1))
        .thenAnswer(ignored -> {
          synchronized (enableTracing) {
            if (enableTracing.get()) {
              logger.setLevel(Level.FINEST);
            }
          }
          return true;
        });

    var indicator = new RecoveryJournalHealthIndicator(source);
    try {
      logger.setLevel(Level.INFO);
      assertThat(indicator.health().getStatus())
          .isEqualTo(enableTracingDuringProbe ? Status.DOWN : Status.UP);
      verify(connection).close();
    } finally {
      synchronized (enableTracing) {
        enableTracing.set(false);
        logger.setLevel(prior);
      }
      indicator.stopProbes();
    }
  }

  @Test
  void readinessRetainsBorrowThroughDelayedCleanupThenAcceptsFreshProbe() throws Exception {
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
    var closeFinished = new CountDownLatch(1);
    doAnswer(
            ignored -> {
              closing.countDown();
              if (!release.await(5, TimeUnit.SECONDS)) {
                throw new SQLException("fixture cleanup was not released");
              }
              closeFinished.countDown();
              return null;
            })
        .doNothing()
        .when(connection)
        .close();

    var indicator = new RecoveryJournalHealthIndicator(source, TimeUnit.MILLISECONDS.toNanos(100));
    try {
      var first = new FutureTask<>(indicator::health);
      Thread.ofVirtual().start(first);
      assertThat(closing.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(first.get(2, TimeUnit.SECONDS).getStatus()).isEqualTo(Status.DOWN);
      assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
      verify(source, times(1)).getConnection();

      release.countDown();
      assertThat(closeFinished.await(5, TimeUnit.SECONDS)).isTrue();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      Status status = indicator.health().getStatus();
      while (!Status.UP.equals(status) && System.nanoTime() < deadline) {
        Thread.sleep(10);
        status = indicator.health().getStatus();
      }
      assertThat(status).isEqualTo(Status.UP);
      verify(source, atLeast(2)).getConnection();
    } finally {
      release.countDown();
      indicator.stopProbes();
    }
  }

  private static void awaitTimedWaiting(Thread thread) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (thread.isAlive()
        && thread.getState() != Thread.State.TIMED_WAITING
        && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertThat(thread.getState()).isEqualTo(Thread.State.TIMED_WAITING);
  }
}
