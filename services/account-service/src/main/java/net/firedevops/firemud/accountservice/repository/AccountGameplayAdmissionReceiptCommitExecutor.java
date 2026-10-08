package net.firedevops.firemud.accountservice.repository;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import net.firedevops.firemud.accountservice.dto.AccountGameplayAdmissionCommitConfirmation;
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

/** Unregistered receipt persistence executor; supplies no admission or original clock authority. */
final class AccountGameplayAdmissionReceiptCommitExecutor {
  private final DataSource dataSource;

  AccountGameplayAdmissionReceiptCommitExecutor(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource);
  }

  AccountGameplayAdmissionCommitConfirmation confirm(
      AccountGameplayAdmissionLeaseEvidence evidence, UUID decisionId) {
    rejectAmbient();
    var creation = new ReceiptTransactionManager(dataSource);
    var created = execute(creation, evidence, decisionId, true);
    // execute returns only after this manager observed the concrete JDBC COMMIT successfully.
    rejectAmbient();
    var read = execute(new ReceiptTransactionManager(dataSource), evidence, decisionId, false);
    if (!created.equals(read)) throw denied();
    return read;
  }

  AccountGameplayAdmissionCommitConfirmation read(
      AccountGameplayAdmissionLeaseEvidence evidence, UUID decisionId) {
    rejectAmbient();
    return execute(new ReceiptTransactionManager(dataSource), evidence, decisionId, false);
  }

  private static AccountGameplayAdmissionCommitConfirmation execute(
      ReceiptTransactionManager manager,
      AccountGameplayAdmissionLeaseEvidence evidence,
      UUID decisionId,
      boolean create) {
    Objects.requireNonNull(evidence);
    Objects.requireNonNull(decisionId);
    var transaction = new TransactionTemplate(manager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    transaction.setReadOnly(false);
    var result =
        transaction.execute(
            status -> {
              if (!status.isNewTransaction()) throw denied();
              Connection connection = manager.bindConnection();
              var dsl = DSL.using(connection, SQLDialect.POSTGRES);
              var repository =
                  new AccountGameplayAdmissionCommitConfirmationRepository(
                      dsl, new AccountGameplayAdmissionLeaseRepository(dsl));
              var receipt =
                  Objects.requireNonNull(
                      create
                          ? repository.confirmCommitted(evidence, decisionId)
                          : repository.readCommitConfirmation(evidence, decisionId));
              if (!receipt.operation().evidence().canonicalJson().equals(evidence.canonicalJson())
                  || !receipt.operation().evidence().sha256().equals(evidence.sha256())
                  || !receipt.bindingDecisionId().equals(decisionId)) throw denied();
              return receipt;
            });
    if (!manager.committed) throw denied();
    return Objects.requireNonNull(result);
  }

  private void rejectAmbient() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()
        || TransactionSynchronizationManager.hasResource(dataSource)) throw denied();
  }

  private static final class ReceiptTransactionManager extends DataSourceTransactionManager {
    private static final long serialVersionUID = 1L;
    private transient Connection connection;
    private transient boolean committed;

    private ReceiptTransactionManager(DataSource dataSource) {
      super(dataSource);
      // A failed guard or uncertain COMMIT must roll back before restoring auto-commit.
      setRollbackOnCommitFailure(true);
    }

    private Connection enlistedConnection() {
      Object resource =
          TransactionSynchronizationManager.getResource(Objects.requireNonNull(getDataSource()));
      if (!(resource instanceof ConnectionHolder holder)) throw denied();
      return holder.getConnection();
    }

    private Connection bindConnection() {
      if (connection != null) throw denied();
      connection = enlistedConnection();
      return connection;
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
      if (!status.isNewTransaction()
          || connection == null
          || connection != enlistedConnection()
          || committed) throw denied();
      try {
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
        connection.commit();
        committed = true;
      } catch (SQLException failure) {
        throw new TransactionSystemException(
            "Account receipt physical COMMIT unavailable", failure);
      }
    }
  }

  private static IllegalStateException denied() {
    return new IllegalStateException("Fresh owned Account receipt COMMIT required");
  }
}
