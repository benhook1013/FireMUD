package net.firedevops.firemud.recovery.config;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import net.firedevops.firemud.recovery.RecoveryControllerServiceApplication;
import net.firedevops.firemud.recovery.journal.InitialGameplayReleaseJournal;
import net.firedevops.firemud.recovery.journal.JooqInitialGameplayReleaseJournal;
import net.firedevops.firemud.recovery.service.DeniedInitialGameplayReleaseEffectPort;
import net.firedevops.firemud.recovery.service.DeniedInitialGameplayReleaseEvidenceProvider;
import net.firedevops.firemud.recovery.service.InitialGameplayReleaseController;
import net.firedevops.firemud.recovery.service.InitialGameplayReleaseEffectPort;
import net.firedevops.firemud.recovery.service.InitialGameplayReleaseEvidenceProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.util.ClassUtils;

@SpringBootTest(
    classes = RecoveryControllerServiceApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "FIREMUD_RECOVERY_CONTROL_DB_HOST=recovery-control.invalid",
      "FIREMUD_RECOVERY_CONTROL_DB_USER=recovery",
      "FIREMUD_RECOVERY_CONTROL_DB_PASSWORD=test-only",
      "spring.jooq.sql-dialect=POSTGRES",
      "spring.flyway.enabled=false",
      "spring.grpc.server.enabled=false",
      "spring.ssl.bundle.pem.firemud-grpc.keystore.certificate=classpath:certs/dev-cert.pem",
      "spring.ssl.bundle.pem.firemud-grpc.keystore.private-key=classpath:certs/dev-key.pem",
      "spring.ssl.bundle.pem.firemud-grpc.truststore.certificate=classpath:certs/dev-ca.pem"
    })
class RecoveryControllerPackagedWiringTest {
  @Autowired private ApplicationContext context;

  @Test
  void packagedApplicationDiscoversDeniedOwnerAndIsolatedJournalWithoutRedis() {
    assertThat(context.getBean(InitialGameplayReleaseEvidenceProvider.class))
        .isInstanceOf(DeniedInitialGameplayReleaseEvidenceProvider.class);
    assertThat(context.getBean(InitialGameplayReleaseEffectPort.class))
        .isInstanceOf(DeniedInitialGameplayReleaseEffectPort.class);
    assertThat(context.getBean(InitialGameplayReleaseJournal.class))
        .isInstanceOf(JooqInitialGameplayReleaseJournal.class);
    assertThat(context.getBeansOfType(InitialGameplayReleaseController.class)).hasSize(1);
    assertThat(context.getBeansOfType(DataSource.class)).hasSize(1);
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
