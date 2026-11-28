package net.firedevops.firemud.recovery.config;

import javax.sql.DataSource;
import net.firedevops.firemud.recovery.journal.InitialGameplayReleaseJournal;
import net.firedevops.firemud.recovery.journal.InitialGameplayReleaseTupleCodec;
import net.firedevops.firemud.recovery.journal.JooqInitialGameplayReleaseJournal;
import net.firedevops.firemud.recovery.service.DeniedInitialGameplayReleaseEffectPort;
import net.firedevops.firemud.recovery.service.DeniedInitialGameplayReleaseEvidenceProvider;
import net.firedevops.firemud.recovery.service.InitialGameplayReleaseController;
import net.firedevops.firemud.recovery.service.InitialGameplayReleaseEffectPort;
import net.firedevops.firemud.recovery.service.InitialGameplayReleaseEvidenceProvider;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.Location;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;
import org.jooq.DSLContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

@AutoConfiguration(afterName = "org.springframework.boot.jooq.autoconfigure.JooqAutoConfiguration")
@AutoConfigureOrder(Ordered.LOWEST_PRECEDENCE)
public class RecoveryControllerOwnerConfiguration {
  @Bean
  static BeanPostProcessor recoveryFlywayConfigurationGuard(
      ObjectProvider<DataSource> journalDataSource) {
    return new BeanPostProcessor() {
      @Override
      public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof Flyway flyway) {
          validateEffectiveFlywayConfiguration(flyway, journalDataSource.getObject());
        }
        return bean;
      }
    };
  }

  private static void validateEffectiveFlywayConfiguration(
      Flyway flyway, DataSource journalDataSource) {
    var configuration = flyway.getConfiguration();
    if (configuration.getDataSource() != journalDataSource) {
      throw new FlywayException("Recovery Flyway must use the journal datasource");
    }
    if (configuration.isGroup()) {
      throw new FlywayException("Recovery Flyway must commit each migration separately");
    }
    if (!configuration.isValidateOnMigrate()) {
      throw new FlywayException("Recovery Flyway must validate migrations before applying them");
    }
    if (configuration.isSkipExecutingMigrations()) {
      throw new FlywayException("Recovery Flyway must execute migrations before recording them");
    }
    Location[] locations = configuration.getLocations();
    if (locations == null
        || locations.length != 1
        || !"classpath:db/recovery-control-migration".equals(locations[0].getDescriptor())) {
      throw new FlywayException(
          "Recovery Flyway locations must contain only classpath:db/recovery-control-migration");
    }
    String[] schemas = configuration.getSchemas();
    if (schemas == null || schemas.length != 1 || !"recovery_control".equals(schemas[0])) {
      throw new FlywayException("Recovery Flyway schemas must contain only recovery_control");
    }
    if (!"recovery_control".equals(configuration.getDefaultSchema())) {
      throw new FlywayException("Recovery Flyway default schema must be recovery_control");
    }
    if (!"flyway_schema_history_recovery_control".equals(configuration.getTable())) {
      throw new FlywayException(
          "Recovery Flyway history table must be flyway_schema_history_recovery_control");
    }
    if (configuration.getConnectRetries() < 0) {
      throw new FlywayException("Recovery Flyway connect retries must be non-negative");
    }
    PostgreSQLConfigurationExtension postgres =
        flyway.getConfigurationExtension(PostgreSQLConfigurationExtension.class);
    if (!MigrationVersion.LATEST.equals(configuration.getTarget())) {
      throw new FlywayException("Recovery Flyway target must be latest");
    }
    if (postgres == null) {
      throw new FlywayException("Recovery Flyway PostgreSQL extension is unavailable");
    }
    // Session locking lets PostgreSQL commit migration DDL and its history on one connection.
    postgres.setTransactionalLock(false);
  }

  @Bean
  Callback recoveryTransactionalMigrationCallback() {
    return new RecoveryTransactionalMigrationCallback();
  }

  @Bean
  @ConditionalOnMissingBean(InitialGameplayReleaseEvidenceProvider.class)
  InitialGameplayReleaseEvidenceProvider deniedInitialGameplayReleaseEvidenceProvider() {
    return new DeniedInitialGameplayReleaseEvidenceProvider();
  }

  @Bean
  @ConditionalOnMissingBean(InitialGameplayReleaseEffectPort.class)
  InitialGameplayReleaseEffectPort deniedInitialGameplayReleaseEffectPort() {
    return new DeniedInitialGameplayReleaseEffectPort();
  }

  @Bean
  @ConditionalOnMissingBean(InitialGameplayReleaseJournal.class)
  InitialGameplayReleaseJournal initialGameplayReleaseJournal(
      DSLContext dsl, InitialGameplayReleaseTupleCodec tupleCodec) {
    return new JooqInitialGameplayReleaseJournal(dsl, tupleCodec);
  }

  @Bean
  @ConditionalOnMissingBean(InitialGameplayReleaseController.class)
  InitialGameplayReleaseController initialGameplayReleaseController(
      InitialGameplayReleaseJournal journal,
      InitialGameplayReleaseTupleCodec tupleCodec,
      InitialGameplayReleaseEvidenceProvider evidenceProvider,
      InitialGameplayReleaseEffectPort effectPort) {
    return new InitialGameplayReleaseController(journal, tupleCodec, evidenceProvider, effectPort);
  }
}
