package net.firedevops.firemud.accountservice.maintenance;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import net.firedevops.firemud.accountservice.service.impl.LegacyTenantAssociationImportService;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

class LegacyTenantAssociationImportJobRunnerTest {
  @Test
  void failsBeforeOwnerEvidenceOrImportWhenSchedulingIsPresent() {
    Environment environment = mock(Environment.class);
    LegacyTenantAssociationImportService importService =
        mock(LegacyTenantAssociationImportService.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<ScheduledAnnotationBeanPostProcessor> schedulingPostProcessor =
        mock(ObjectProvider.class);
    ScheduledAnnotationBeanPostProcessor processor =
        mock(ScheduledAnnotationBeanPostProcessor.class);
    when(schedulingPostProcessor.getIfAvailable()).thenReturn(processor);

    LegacyTenantAssociationImportJobRunner runner =
        new LegacyTenantAssociationImportJobRunner(
            environment, importService, schedulingPostProcessor);

    assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("scheduling enabled");
    verifyNoInteractions(importService);
  }

  @Test
  void acceptsOnlyExactDedicatedMigratorCertificate() {
    assertThatCode(
            () ->
                LegacyTenantAssociationImportJobRunner.requireJobIdentity(
                    "dev", peer("dev", "account-tenant-migrator")))
        .doesNotThrowAnyException();
    for (GrpcPeerIdentity wrong :
        new GrpcPeerIdentity[] {
          null,
          peer("dev", "account-service"),
          peer("pr-12", "account-tenant-migrator"),
          peer("dev", "game-design-tenant-migrator")
        }) {
      assertThatThrownBy(
              () -> LegacyTenantAssociationImportJobRunner.requireJobIdentity("dev", wrong))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("wrong identity");
    }
  }

  private static GrpcPeerIdentity peer(String namespace, String service) {
    return GrpcPeerIdentity.parseUri("spiffe://firemud/ns/" + namespace + "/sa/" + service)
        .orElseThrow();
  }
}
