package net.firedevops.firemud.recovery.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import net.firedevops.firemud.recovery.journal.InitialGameplayReleaseJournal;
import net.firedevops.firemud.recovery.journal.InitialGameplayReleaseTupleCodec;
import net.firedevops.firemud.recovery.journal.JooqInitialGameplayReleaseJournal;
import net.firedevops.firemud.recovery.service.DeniedInitialGameplayReleaseEffectPort;
import net.firedevops.firemud.recovery.service.DeniedInitialGameplayReleaseEvidenceProvider;
import net.firedevops.firemud.recovery.service.InitialGameplayReleaseController;
import net.firedevops.firemud.recovery.service.InitialGameplayReleaseEffectPort;
import net.firedevops.firemud.recovery.service.InitialGameplayReleaseEvidenceProvider;
import net.firedevops.firemud.recovery.service.OwnerBoundaryUnavailableException;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.DataSourceConnectionProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.jooq.autoconfigure.JooqAutoConfiguration;
import org.springframework.boot.jooq.autoconfigure.SpringTransactionProvider;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

class RecoveryControllerOwnerConfigurationTest {
  private ApplicationContextRunner defaults() {
    return new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(RecoveryControllerOwnerConfiguration.class))
        .withBean(DSLContext.class, () -> DSL.using(SQLDialect.POSTGRES))
        .withBean(
            InitialGameplayReleaseTupleCodec.class,
            () -> new InitialGameplayReleaseTupleCodec(JsonMapper.builder().build()));
  }

  @Test
  void defaultsRemainDeniedAndJournalBacked() {
    defaults()
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(InitialGameplayReleaseEvidenceProvider.class);
          assertThat(context.getBean(InitialGameplayReleaseEvidenceProvider.class))
              .isInstanceOf(DeniedInitialGameplayReleaseEvidenceProvider.class);
          assertThat(context.getBean(InitialGameplayReleaseEffectPort.class))
              .isInstanceOf(DeniedInitialGameplayReleaseEffectPort.class);
          assertThat(context.getBean(InitialGameplayReleaseJournal.class))
              .isInstanceOf(JooqInitialGameplayReleaseJournal.class);
          assertThat(context).hasSingleBean(InitialGameplayReleaseController.class);
        });
  }

  @Test
  void replacementAutoConfigurationPrecedesEveryFallback() {
    defaults()
        .withConfiguration(AutoConfigurations.of(Replacements.class))
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(InitialGameplayReleaseEvidenceProvider.class);
          assertThat(context).hasSingleBean(InitialGameplayReleaseEffectPort.class);
          assertThat(context).hasSingleBean(InitialGameplayReleaseJournal.class);
          assertThat(context).hasSingleBean(InitialGameplayReleaseController.class);
          assertThat(context).doesNotHaveBean("deniedInitialGameplayReleaseEvidenceProvider");
          assertThat(context).doesNotHaveBean("deniedInitialGameplayReleaseEffectPort");
          assertThat(context.getBean(InitialGameplayReleaseJournal.class))
              .isSameAs(context.getBean("replacementJournal"));
          assertThat(context.getBean(InitialGameplayReleaseController.class))
              .isSameAs(context.getBean("replacementController"));
        });
  }

  @Test
  void dedicatedDatasourceOwnsFlywayAndJooq() {
    new ApplicationContextRunner()
        .withInitializer(context -> {
          var sources = context.getEnvironment().getPropertySources();
          sources.remove(
              org.springframework.core.env.StandardEnvironment
                  .SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
          sources.remove(
              org.springframework.core.env.StandardEnvironment
                  .SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
          try {
            for (var source :
                new YamlPropertySourceLoader()
                    .load("recovery-service", new ClassPathResource("application.yml"))) {
              context.getEnvironment().getPropertySources().addLast(source);
            }
          } catch (IOException exception) {
            throw new IllegalStateException(exception);
          }
        })
        .withUserConfiguration(DatabaseWiring.class)
        .withPropertyValues(
            "FIREMUD_RECOVERY_CONTROL_DB_HOST=recovery-control.invalid",
            "FIREMUD_RECOVERY_CONTROL_DB_PORT=5544",
            "FIREMUD_RECOVERY_CONTROL_DB_USER=recovery",
            "FIREMUD_RECOVERY_CONTROL_DB_PASSWORD=test-only",
            "spring.jooq.sql-dialect=POSTGRES")
        // Wiring proof must not depend on a running PostgreSQL or apply migrations.
        .withBean(FlywayMigrationStrategy.class, () -> flyway -> {})
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(DataSource.class);
          HikariDataSource datasource = context.getBean(HikariDataSource.class);
          assertThat(datasource.getJdbcUrl())
              .isEqualTo(
                  "jdbc:postgresql://recovery-control.invalid:5544/firemud_recovery_control?logServerErrorDetail=false&preferQueryMode=extended");
          Flyway flyway = context.getBean(Flyway.class);
          assertThat(flyway.getConfiguration().getDataSource()).isSameAs(datasource);
          assertThat(flyway.getConfiguration().getLocations())
              .extracting(location -> location.getDescriptor())
              .containsExactly("classpath:db/recovery-control-migration");
          var provider =
              (DataSourceConnectionProvider)
                  context.getBean(DSLContext.class).configuration().connectionProvider();
          var proxy = (TransactionAwareDataSourceProxy) provider.dataSource();
          assertThat(proxy.getTargetDataSource()).isSameAs(datasource);
        });
  }

  @Test
  void configuredJooqTransactionIsRejectedBeforeOwnerEffectDispatch() throws SQLException {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.getAutoCommit()).thenReturn(true);
    InitialGameplayReleaseJournal journal = mock(InitialGameplayReleaseJournal.class);
    InitialGameplayReleaseEffectPort effectPort = mock(InitialGameplayReleaseEffectPort.class);
    InitialGameplayReleaseController controller =
        new InitialGameplayReleaseController(
            journal,
            new InitialGameplayReleaseTupleCodec(JsonMapper.builder().findAndAddModules().build()),
            mock(InitialGameplayReleaseEvidenceProvider.class),
            effectPort);

    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                DataSourceTransactionManagerAutoConfiguration.class, JooqAutoConfiguration.class))
        .withBean(DataSource.class, () -> dataSource)
        .withPropertyValues("spring.jooq.sql-dialect=POSTGRES")
        .run(context -> {
          assertThat(context).hasNotFailed();
          DSLContext dsl = context.getBean(DSLContext.class);
          assertThat(dsl.configuration().transactionProvider())
              .isInstanceOf(SpringTransactionProvider.class);
          var rollback = new IllegalStateException("rollback caller-owned jOOQ transaction");
          assertThatThrownBy(() ->
                  dsl.transactionResult(configuration -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                        .isTrue();
                    assertThatThrownBy(() -> controller.reconcileNextEffect(UUID.randomUUID()))
                        .isInstanceOf(OwnerBoundaryUnavailableException.class)
                        .hasMessageContaining("RECONCILIATION_REQUIRES_NO_CALLER_TRANSACTION");
                    throw rollback;
                  }))
              .satisfies(failure ->
                  assertThat(failure == rollback || failure.getCause() == rollback)
                      .as("the deliberate callback rollback exception is propagated")
                      .isTrue());
          verify(connection).rollback();
          verifyNoInteractions(journal, effectPort);
        });
  }

  @TestConfiguration(proxyBeanMethods = false)
  @ImportAutoConfiguration({
    DataSourceAutoConfiguration.class,
    FlywayAutoConfiguration.class,
    JooqAutoConfiguration.class
  })
  static class DatabaseWiring {}

  @TestComponent
  @AutoConfiguration
  static class Replacements {
    @Bean
    InitialGameplayReleaseEvidenceProvider replacementEvidence() {
      return mock(InitialGameplayReleaseEvidenceProvider.class);
    }

    @Bean
    InitialGameplayReleaseEffectPort replacementEffect() {
      return mock(InitialGameplayReleaseEffectPort.class);
    }

    @Bean
    InitialGameplayReleaseJournal replacementJournal() {
      return mock(InitialGameplayReleaseJournal.class);
    }

    @Bean
    InitialGameplayReleaseController replacementController() {
      return mock(InitialGameplayReleaseController.class);
    }
  }
}
