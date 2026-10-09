package unit.net.firedevops.firemud.gamedesign.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.util.List;
import net.firedevops.firemud.gamedesign.config.RealmPolicyPublicationReadConfiguration;
import net.firedevops.firemud.gamedesign.publication.RealmPolicyPublicationGrpcService;
import net.firedevops.firemud.gamedesign.publication.RealmPolicyPublicationRepository;
import net.firedevops.firemud.gamedesign.publication.RealmPolicyPublicationService;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.FileSystemResource;
import org.springframework.transaction.PlatformTransactionManager;

class RealmPolicyPublicationReadConfigurationTest {
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(
              RealmPolicyPublicationReadConfiguration.class,
              RealmPolicyPublicationGrpcService.class)
          .withBean(DSLContext.class, () -> mock(DSLContext.class))
          .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class));

  @Test
  void ownerReadCompositionAndGrpcAdapterAreAbsentByDefault() {
    contextRunner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).doesNotHaveBean(RealmPolicyPublicationRepository.class);
          assertThat(context).doesNotHaveBean(RealmPolicyPublicationService.class);
          assertThat(context).doesNotHaveBean(RealmPolicyPublicationGrpcService.class);
        });
  }

  @Test
  void explicitEnablementComposesActualOwnerBeansAndGrpcAdapter() {
    contextRunner
        .withPropertyValues(
            "firemud.game-design.published-realm-entry-policy-read.enabled=true",
            "firemud.grpc.workload-namespace=firemud-test")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(RealmPolicyPublicationRepository.class);
              assertThat(context).hasSingleBean(RealmPolicyPublicationService.class);
              assertThat(context).hasSingleBean(RealmPolicyPublicationGrpcService.class);
            });
  }

  @Test
  void enabledCompositionFailsClosedForMissingOrInvalidNamespace() {
    contextRunner
        .withPropertyValues("firemud.game-design.published-realm-entry-policy-read.enabled=true")
        .run(context -> assertThat(context).hasFailed());

    contextRunner
        .withPropertyValues(
            "firemud.game-design.published-realm-entry-policy-read.enabled=true",
            "firemud.grpc.workload-namespace=Invalid_Namespace")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void baseAndProductionYamlBindOnlyTheExplicitPolicyReadMethods() throws IOException {
    assertConfiguredGrpcMethods("application.yml");
    assertConfiguredGrpcMethods("application-prod.yml");
  }

  private static void assertConfiguredGrpcMethods(String fileName) throws IOException {
    var propertySources =
        new YamlPropertySourceLoader()
            .load(fileName, new FileSystemResource("src/main/resources/" + fileName));
    new ApplicationContextRunner()
        .withInitializer(
            context ->
                propertySources.forEach(context.getEnvironment().getPropertySources()::addFirst))
        .run(
            context ->
                assertThat(
                        Binder.get(context.getEnvironment())
                            .bind("firemud.auth.grpc.public-methods", Bindable.listOf(String.class))
                            .orElse(List.of()))
                    .containsExactly(
                        "gamedesign.v1.TenantIdentityService/ResolveFreshTenantCreation",
                        "game_design.v1.PublishedRealmEntryPolicyService/ResolvePublishedRealmEntryPolicy",
                        "game_design.v1.PublishedRealmEntryPolicyService/ListPublishedRealmEntryPolicies",
                        "gamedesign.v1.GameDesignService/ResolveLaunchDescriptor",
                        "gamedesign.v1.GameDesignService/GetLaunchDescriptor",
                        "gamedesign.v1.GameDesignService/GetCompleteLaunchBinding"));
  }
}
