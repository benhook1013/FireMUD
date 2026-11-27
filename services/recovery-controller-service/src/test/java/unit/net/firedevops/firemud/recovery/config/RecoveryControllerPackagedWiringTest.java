package net.firedevops.firemud.recovery.config;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import net.firedevops.firemud.common.config.DatabaseAutoConfiguration;
import net.firedevops.firemud.recovery.RecoveryControllerServiceApplication;
import net.firedevops.firemud.recovery.journal.InitialGameplayReleaseJournal;
import net.firedevops.firemud.recovery.journal.JooqInitialGameplayReleaseJournal;
import net.firedevops.firemud.recovery.service.DeniedInitialGameplayReleaseEffectPort;
import net.firedevops.firemud.recovery.service.DeniedInitialGameplayReleaseEvidenceProvider;
import net.firedevops.firemud.recovery.service.InitialGameplayReleaseController;
import net.firedevops.firemud.recovery.service.InitialGameplayReleaseEffectPort;
import net.firedevops.firemud.recovery.service.InitialGameplayReleaseEvidenceProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.util.ClassUtils;

class RecoveryControllerPackagedWiringTest {
  @Test
  void packagedApplicationDiscoversDeniedOwnerAndIsolatedJournalWithoutRedis() {
    var environment = new StandardEnvironment();
    // Isolate datasource settings before Boot runs the packaged environment guard.
    var propertySources = environment.getPropertySources();
    propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    propertySources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    try (var context =
        new SpringApplicationBuilder(RecoveryControllerServiceApplication.class)
            .environment(environment)
            .web(WebApplicationType.NONE)
            .run(
                "--spring.config.location=classpath:/application.yml",
                "--FIREMUD_RECOVERY_CONTROL_DB_HOST=recovery-control.invalid",
                "--FIREMUD_RECOVERY_CONTROL_DB_PORT=5432",
                "--FIREMUD_RECOVERY_CONTROL_DB_USER=recovery",
                "--FIREMUD_RECOVERY_CONTROL_DB_PASSWORD=test-only",
                "--firemud.auth.jwt-secret=fixture-only-4c5f8d93e7ad4f58a8d81235cb7521b0d8439508",
                "--spring.jooq.sql-dialect=POSTGRES",
                "--spring.flyway.enabled=false",
                "--spring.grpc.server.enabled=false",
                "--spring.ssl.bundle.pem.firemud-grpc.keystore.certificate=classpath:certs/dev-cert.pem",
                "--spring.ssl.bundle.pem.firemud-grpc.keystore.private-key=classpath:certs/dev-key.pem",
                "--spring.ssl.bundle.pem.firemud-grpc.truststore.certificate=classpath:certs/dev-ca.pem")) {
      assertThat(context.getBean(InitialGameplayReleaseEvidenceProvider.class))
          .isInstanceOf(DeniedInitialGameplayReleaseEvidenceProvider.class);
      assertThat(context.getBean(InitialGameplayReleaseEffectPort.class))
          .isInstanceOf(DeniedInitialGameplayReleaseEffectPort.class);
      assertThat(context.getBean(InitialGameplayReleaseJournal.class))
          .isInstanceOf(JooqInitialGameplayReleaseJournal.class);
      assertThat(context.getBeansOfType(InitialGameplayReleaseController.class)).hasSize(1);
      assertThat(context.getBeansOfType(DatabaseAutoConfiguration.class)).isEmpty();
      assertThat(context.getBeansOfType(DataSource.class)).hasSize(1);
      assertThat(new BeanWrapperImpl(context.getBean(DataSource.class)).getPropertyValue("jdbcUrl"))
          .isEqualTo(
              "jdbc:postgresql://recovery-control.invalid:5432/firemud_recovery_control?logServerErrorDetail=false");
      String redisConnectionFactory =
          "org.springframework.data.redis.connection.RedisConnectionFactory";
      ClassLoader loader = context.getClassLoader();
      // common-data-runtime puts Redis on the runtime classpath, not the test compile classpath.
      if (ClassUtils.isPresent(redisConnectionFactory, loader)) {
        assertThat(
                context.getBeansOfType(ClassUtils.resolveClassName(redisConnectionFactory, loader)))
            .isEmpty();
      }
    }
  }
}
