package net.firedevops.firemud.accountservice.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import net.firedevops.firemud.accountservice.dto.AccountGameplayAdmissionLeaseOperation.State;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unregistered original storage-COMMIT executor; supplies no authentication or admission authority.
 *
 * <p>The opaque acknowledgement proves only successful synchronous COMMIT of this fresh transition.
 * It contains no clock bound and is neither a temporal confirmation nor a durable receipt. The
 * protected owner must separately observe database time before unchanged expiry, persist its exact
 * confirmation and prove the receipt's own durability. Lost acknowledgement cannot be reconstructed
 * by retrying this executor: a retained COMMITTED operation is rejected.
 */
final class AccountGameplayAdmissionOriginalCommitExecutor {
  private final DataSource dataSource;

  AccountGameplayAdmissionOriginalCommitExecutor(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource);
  }

  OriginalCommitAcknowledgement execute(
      AccountGameplayAdmissionLeaseEvidence evidence, UUID decisionId) {
    Objects.requireNonNull(evidence);
    Objects.requireNonNull(decisionId);
    var manager = new OriginalCommitTransactionManager(dataSource);
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()
        || TransactionSynchronizationManager.hasResource(
            Objects.requireNonNull(manager.getDataSource()))) {
      throw denied();
    }
    // Each invocation owns its manager, enlisted connection and acknowledgement slot.
    var transaction = new TransactionTemplate(manager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    transaction.setReadOnly(false);
    transaction.executeWithoutResult(
        ignored -> {
          if (!ignored.isNewTransaction()) throw denied();
          Connection connection = manager.enlistedConnection();
          // The repository cannot acquire another connection: its DSL uses this connection itself.
          var repository =
              new AccountGameplayAdmissionLeaseRepository(
                  DSL.using(connection, SQLDialect.POSTGRES));
          var prior =
              repository
                  .readExact(evidence)
                  .orElseThrow(AccountGameplayAdmissionOriginalCommitExecutor::denied);
          if (prior.state() != State.PENDING) throw denied();
          var committed = repository.recordCommitted(evidence, decisionId);
          if (committed.state() != State.COMMITTED
              || !decisionId.equals(committed.bindingDecisionId())
              || !evidence.canonicalJson().equals(committed.evidence().canonicalJson())
              || !evidence.sha256().equals(committed.evidence().sha256())) throw denied();
          manager.bindOriginal(connection, evidence, decisionId);
        });
    return Objects.requireNonNull(manager.acknowledgement);
  }

  /**
   * Non-serializable, immutable in-process capability; only the physical COMMIT path constructs it.
   */
  static final class OriginalCommitAcknowledgement {
    private final AccountGameplayAdmissionLeaseEvidence evidence;
    private final UUID decisionId;
    private final String finalizationXid;

    private OriginalCommitAcknowledgement(OriginalBinding binding) {
      evidence = binding.evidence();
      decisionId = binding.decisionId();
      finalizationXid = binding.finalizationXid();
    }

    AccountGameplayAdmissionLeaseEvidence evidence() {
      return evidence;
    }

    UUID decisionId() {
      return decisionId;
    }

    String finalizationXid() {
      return finalizationXid;
    }

    @Override
    public String toString() {
      return "OriginalCommitAcknowledgement[not a temporal confirmation]";
    }
  }

  private record OriginalBinding(
      AccountGameplayAdmissionLeaseEvidence evidence, UUID decisionId, String finalizationXid) {}

  private static final class OriginalCommitTransactionManager extends DataSourceTransactionManager {
    private static final long serialVersionUID = 1L;
    private transient Connection originalConnection;
    private transient OriginalBinding binding;
    private transient OriginalCommitAcknowledgement acknowledgement;

    private OriginalCommitTransactionManager(DataSource dataSource) {
      super(dataSource);
      // A rejected pre-COMMIT durability check must roll back before cleanup restores auto-commit.
      // A failed/ambiguous physical COMMIT still never produces an acknowledgement.
      setRollbackOnCommitFailure(true);
    }

    private Connection enlistedConnection() {
      DataSource source = Objects.requireNonNull(getDataSource());
      Object resource = TransactionSynchronizationManager.getResource(source);
      if (!(resource instanceof ConnectionHolder holder)) throw denied();
      return holder.getConnection();
    }

    private void bindOriginal(
        Connection connection, AccountGameplayAdmissionLeaseEvidence evidence, UUID decisionId) {
      if (originalConnection != null || binding != null || connection != enlistedConnection())
        throw denied();
      String sql =
          "SELECT finalization_xid, pg_current_xact_id_if_assigned()::text "
              + "FROM account_gameplay_admission_lease_operations "
              + "WHERE request_id = ? AND lease_id = ? AND evidence_sha256 = ? "
              + "AND binding_decision_id = ? AND status = 'COMMITTED' FOR UPDATE";
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setObject(1, UUID.fromString((String) evidence.carrier().get("requestId")));
        statement.setObject(2, UUID.fromString((String) evidence.carrier().get("leaseId")));
        statement.setString(3, evidence.sha256());
        statement.setObject(4, decisionId);
        try (ResultSet row = statement.executeQuery()) {
          if (!row.next()) throw denied();
          String finalizationXid = row.getString(1);
          if (finalizationXid == null
              || !finalizationXid.matches("[1-9][0-9]{0,19}")
              || !finalizationXid.equals(row.getString(2))
              || row.next()) throw denied();
          originalConnection = connection;
          binding = new OriginalBinding(evidence, decisionId, finalizationXid);
        }
      } catch (SQLException failure) {
        throw new TransactionSystemException(
            "Original Account finalization identity unavailable", failure);
      }
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
      if (!status.isNewTransaction()
          || binding == null
          || originalConnection == null
          || originalConnection != enlistedConnection()
          || acknowledgement != null) throw denied();
      try {
        Connection connection = originalConnection;
        if (connection.getAutoCommit()
            || connection.isReadOnly()
            || connection.getTransactionIsolation() != Connection.TRANSACTION_SERIALIZABLE
            || !"PostgreSQL".equals(connection.getMetaData().getDatabaseProductName()))
          throw denied();
        try (Statement statement = connection.createStatement()) {
          statement.execute("SET LOCAL synchronous_commit = on");
          try (ResultSet settings =
              statement.executeQuery(
                  "SELECT current_setting('server_version_num')::integer, pg_is_in_recovery(), "
                      + "current_setting('fsync'), current_setting('synchronous_commit'), "
                      + "current_setting('transaction_isolation'), current_setting('transaction_read_only')")) {
            if (!settings.next()) throw denied();
            int version = settings.getInt(1);
            if (version < 160000
                || version >= 170000
                || settings.getBoolean(2)
                || !"on".equals(settings.getString(3))
                || !"on".equals(settings.getString(4))
                || !"serializable".equals(settings.getString(5))
                || !"off".equals(settings.getString(6))
                || settings.next()) throw denied();
          }
        }
        // No callback or caller flag can mint this capability. An ambiguous JDBC outcome throws.
        connection.commit();
        acknowledgement = new OriginalCommitAcknowledgement(binding);
      } catch (SQLException failure) {
        throw new TransactionSystemException(
            "Original Account physical COMMIT unavailable", failure);
      }
    }
  }

  private static IllegalStateException denied() {
    return new IllegalStateException("Fresh owned Account original COMMIT required");
  }
}
