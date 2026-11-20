package net.firedevops.firemud.worldmanagement.config;

import java.nio.file.Path;
import net.firedevops.firemud.common.prev1.PreV1GateWaivers;
import net.firedevops.firemud.worldmanagement.data.PreV1ComposeRuntimeInstallationSource;
import net.firedevops.firemud.worldmanagement.tenant.WorldRuntimeInstallationRegistry;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Registers the World runtime installation for the canonical run-owned fixture under the pre-v1
 * gate waivers, so the fixture launch can freeze its required owners before PREPARING and ACTIVE.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression(
    "${FIREMUD_SMOKE_INITIAL_ADMISSION_FIXTURE_ENABLED:false}"
        + " and ${firemud.pre-v1.gate-waivers.enabled:false}")
public class PreV1RunOwnedFixtureInstallationConfiguration {

  @Bean
  WorldRuntimeInstallationRegistry runOwnedFixtureRuntimeInstallationRegistry(
      Environment environment,
      DSLContext dsl,
      PlatformTransactionManager transactionManager,
      @Value("${SERVICE_SCHEMA:public}") String schema,
      @Value("${firemud.grpc.workload-namespace:}") String namespace,
      @Value(
              "${FIREMUD_WORLD_RUNTIME_INSTALLATION_INVENTORY_PATH:"
                  + "/etc/firemud/world-runtime-installation/inventory.json}")
          String inventoryPath) {
    if (!PreV1GateWaivers.from(environment).enabled()) {
      throw new IllegalStateException("Compose runtime installation requires pre-v1 gate waivers");
    }
    return new WorldRuntimeInstallationRegistry(
        dsl,
        transactionManager,
        schema,
        namespace,
        new PreV1ComposeRuntimeInstallationSource(Path.of(inventoryPath)));
  }
}
