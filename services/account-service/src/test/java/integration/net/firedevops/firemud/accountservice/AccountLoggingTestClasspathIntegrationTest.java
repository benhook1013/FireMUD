package integration.net.firedevops.firemud.accountservice;

import static org.assertj.core.api.Assertions.assertThat;

import net.firedevops.firemud.loggingadmin.StartSessionReservationMutationTestFixtures;
import net.firedevops.firemud.loggingadmin.service.impl.StartSessionReservationEvidenceGrpcService;
import org.junit.jupiter.api.Test;

/** Verifies Account can use Logging proof code without importing Logging's Flyway migrations. */
class AccountLoggingTestClasspathIntegrationTest {
  @Test
  void retainsLoggingProofClassesAndOnlyAccountMigrationResources() {
    assertThat(StartSessionReservationEvidenceGrpcService.class).isNotNull();
    assertThat(StartSessionReservationMutationTestFixtures.class).isNotNull();

    ClassLoader classLoader = getClass().getClassLoader();
    assertThat(classLoader.getResource("db/migration/V1__baseline.sql")).isNull();
    assertThat(classLoader.getResource("db/migration/V1__init.sql")).isNotNull();
  }
}
