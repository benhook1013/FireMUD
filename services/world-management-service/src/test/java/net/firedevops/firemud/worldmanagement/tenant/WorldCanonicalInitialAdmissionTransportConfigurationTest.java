package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;

class WorldCanonicalInitialAdmissionTransportConfigurationTest {
  @TempDir Path directory;

  @Test
  void defaultAndExplicitFalseRegisterNoCanonicalReadOrHoldWriter() {
    runner()
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context)
                  .doesNotHaveBean(WorldCanonicalInitialAdmissionHoldGrpcService.class);
              assertThat(context)
                  .doesNotHaveBean(WorldCanonicalInstanceLifecycleReadGrpcService.class);
              assertThat(context)
                  .doesNotHaveBean(WorldCanonicalInitialAdmissionHoldRepository.class);
              assertThat(context)
                  .doesNotHaveBean(WorldCanonicalInitialAdmissionHoldTerminalGrpcService.class);
              assertThat(context)
                  .doesNotHaveBean(GameSessionCanonicalInitialAdmissionOwnerClient.class);
            });
    runner()
        .withPropertyValues("firemud.world.canonical-first-admission.transport-enabled=false")
        .run(
            context ->
                assertThat(context)
                    .doesNotHaveBean(WorldCanonicalInitialAdmissionHoldGrpcService.class));
  }

  @Test
  void explicitCompositionRequiresNamespaceAndFileBackedMutualTls() throws IOException {
    secureRunner()
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context)
                  .hasSingleBean(WorldCanonicalInitialAdmissionHoldGrpcService.class);
              assertThat(context)
                  .hasSingleBean(WorldCanonicalInstanceLifecycleReadGrpcService.class);
              assertThat(context)
                  .hasSingleBean(WorldCanonicalInitialAdmissionHoldTerminalGrpcService.class);
              assertThat(context)
                  .hasSingleBean(WorldCanonicalInitialAdmissionHoldFinalizationService.class);
              assertThat(context)
                  .doesNotHaveBean(GameSessionCanonicalInitialAdmissionOwnerClient.class);
              assertThatThrownBy(
                      () ->
                          context
                              .getBean(WorldCanonicalInitialAdmissionHoldFinalizationService.class)
                              .finalizeHold(
                                  mock(HoldIdentity.class),
                                  GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED))
                  .isInstanceOf(
                      WorldCanonicalInitialAdmissionHoldFinalizationService
                          .FinalizationDeniedException.class)
                  .hasMessageContaining("Authenticated Game Session");
              assertThat(context)
                  .doesNotHaveBean(WorldCanonicalInstanceActivationGrpcService.class);
              assertThat(context).doesNotHaveBean(WorldCanonicalPlayerLocationGrpcService.class);
            });
    secureRunner()
        .withPropertyValues("firemud.grpc.workload-namespace=")
        .run(context -> assertThat(context).hasFailed());
    secureRunner()
        .withPropertyValues("spring.grpc.server.ssl.enabled=false")
        .run(context -> assertThat(context).hasFailed());
    secureRunner()
        .withPropertyValues("spring.grpc.server.ssl.client-auth=NONE")
        .run(context -> assertThat(context).hasFailed());
    secureRunner()
        .withPropertyValues(
            "spring.ssl.bundle.pem.firemud-grpc.keystore.certificate=classpath:server.crt")
        .run(context -> assertThat(context).hasFailed());
    // Enabling a client without its protected dependencies cannot create permissive wiring.
    secureRunner()
        .withPropertyValues(
            "firemud.world.canonical-first-admission.owner-proof-client-enabled=true")
        .run(context -> assertThat(context).hasFailed());
  }

  private ApplicationContextRunner runner() {
    return new ApplicationContextRunner()
        .withUserConfiguration(
            WorldCanonicalInitialAdmissionTransportConfiguration.class,
            WorldCanonicalInitialAdmissionHoldGrpcService.class,
            WorldCanonicalInstanceLifecycleReadGrpcService.class,
            WorldCanonicalInitialAdmissionHoldTerminalGrpcService.class);
  }

  private ApplicationContextRunner secureRunner() throws IOException {
    // These files prove configuration gates only, not physical TLS or owner authentication.
    Path certificate = Files.writeString(directory.resolve("server.crt"), "fixture");
    Path key = Files.writeString(directory.resolve("server.key"), "fixture");
    Path ca = Files.writeString(directory.resolve("ca.crt"), "fixture");
    return runner()
        .withBean(DSLContext.class, () -> mock(DSLContext.class))
        .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
        .withPropertyValues(
            "firemud.world.canonical-first-admission.transport-enabled=true",
            "firemud.grpc.workload-namespace=world-hold-test",
            "spring.grpc.server.enabled=true",
            "spring.grpc.server.ssl.enabled=true",
            "spring.grpc.server.ssl.client-auth=REQUIRE",
            "spring.ssl.bundle.pem.firemud-grpc.keystore.certificate=" + certificate,
            "spring.ssl.bundle.pem.firemud-grpc.keystore.private-key=" + key,
            "spring.ssl.bundle.pem.firemud-grpc.truststore.certificate=" + ca);
  }
}
