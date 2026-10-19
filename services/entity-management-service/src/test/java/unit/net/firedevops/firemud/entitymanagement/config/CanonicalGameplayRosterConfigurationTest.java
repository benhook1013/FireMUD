package unit.net.firedevops.firemud.entitymanagement.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.entitymanagement.client.AccountPreseededActorStagingEligibilityClient;
import net.firedevops.firemud.entitymanagement.client.AccountRuntimeIdentityClient;
import net.firedevops.firemud.entitymanagement.client.GameSessionCanonicalGameplayRosterOwnerReadClient;
import net.firedevops.firemud.entitymanagement.client.GameSessionPreseededActorAssignmentOwnerReadClient;
import net.firedevops.firemud.entitymanagement.config.CanonicalGameplayRosterConfiguration;
import net.firedevops.firemud.entitymanagement.config.PreseededActorAssignmentConfiguration;
import net.firedevops.firemud.entitymanagement.repository.CharacterRepository;
import net.firedevops.firemud.entitymanagement.security.CanonicalGameplayRosterPeerInterceptor;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterEntryPolicy;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterExecutionContext;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterOwnerEvidencePort;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterReadRequest;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterSelectedAssignmentService;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterService;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterTarget;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentReceiptService;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentService;
import net.firedevops.firemud.entitymanagement.service.impl.CanonicalGameplayRosterGrpcAdapter;
import net.firedevops.firemud.entitymanagement.service.impl.GameSessionCanonicalGameplayRosterOwnerEvidenceAdapter;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ConfigurationClassPostProcessor;
import org.springframework.core.env.MapPropertySource;

class CanonicalGameplayRosterConfigurationTest {
  @Test
  void applicationComponentScanDoesNotRegisterPassiveSourcesEvenWhenFlagsAreTrue() {
    try (var context = new AnnotationConfigApplicationContext()) {
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new MapPropertySource(
                  "passive-source-test",
                  Map.<String, Object>of(
                      "firemud.canonical-gameplay-roster-owner-read.enabled",
                      "true",
                      "firemud.run-owned-preseeded-actor-assignment.enabled",
                      "true")));
      context.scan("net.firedevops.firemud.entitymanagement");
      processConfigurationClasses(context);

      Set<String> registeredTypeNames = registeredTypeNames(context);

      assertThat(
              context
                  .getEnvironment()
                  .getProperty("firemud.canonical-gameplay-roster-owner-read.enabled"))
          .isEqualTo("true");
      assertThat(
              context
                  .getEnvironment()
                  .getProperty("firemud.run-owned-preseeded-actor-assignment.enabled"))
          .isEqualTo("true");
      assertThat(registeredTypeNames).contains(CharacterRepository.class.getName());
      assertThat(registeredTypeNames)
          .doesNotContain(
              CanonicalGameplayRosterConfiguration.class.getName(),
              PreseededActorAssignmentConfiguration.class.getName(),
              CanonicalGameplayRosterService.class.getName(),
              CanonicalGameplayRosterSelectedAssignmentService.class.getName(),
              PreseededActorAssignmentService.class.getName(),
              PreseededActorAssignmentReceiptService.class.getName(),
              GameSessionCanonicalGameplayRosterOwnerReadClient.class.getName(),
              GameSessionPreseededActorAssignmentOwnerReadClient.class.getName(),
              AccountRuntimeIdentityClient.class.getName(),
              AccountPreseededActorStagingEligibilityClient.class.getName(),
              CanonicalGameplayRosterGrpcAdapter.class.getName(),
              CanonicalGameplayRosterPeerInterceptor.class.getName());
    }
  }

  @Test
  void beanFactoryReturnTypesAreDetectedWithoutInvokingTheFactoryMethod() {
    PassiveFactoryMethodPositiveControl.FACTORY_METHOD_INVOCATIONS.set(0);
    try (var context = new AnnotationConfigApplicationContext()) {
      context.register(PassiveFactoryMethodPositiveControl.class);
      processConfigurationClasses(context);

      assertThat(registeredTypeNames(context))
          .contains(CanonicalGameplayRosterService.class.getName());
      assertThat(PassiveFactoryMethodPositiveControl.FACTORY_METHOD_INVOCATIONS).hasValue(0);
    }
  }

  private static void processConfigurationClasses(AnnotationConfigApplicationContext context) {
    var processor = new ConfigurationClassPostProcessor();
    processor.setBeanClassLoader(context.getClassLoader());
    processor.setEnvironment(context.getEnvironment());
    processor.setResourceLoader(context);
    processor.postProcessBeanDefinitionRegistry(context.getDefaultListableBeanFactory());
    processor.postProcessBeanFactory(context.getBeanFactory());
  }

  private static Set<String> registeredTypeNames(AnnotationConfigApplicationContext context) {
    var beanFactory = context.getBeanFactory();
    return Arrays.stream(beanFactory.getBeanDefinitionNames())
        .flatMap(
            name -> {
              var definition = beanFactory.getBeanDefinition(name);
              var beanType = beanFactory.getType(name, false);
              return Arrays.stream(
                  new String[] {
                    definition.getBeanClassName(), beanType == null ? null : beanType.getName()
                  });
            })
        .filter(typeName -> typeName != null)
        .collect(Collectors.toSet());
  }

  @Configuration(proxyBeanMethods = false)
  static class PassiveFactoryMethodPositiveControl {
    private static final AtomicInteger FACTORY_METHOD_INVOCATIONS = new AtomicInteger();

    @Bean
    CanonicalGameplayRosterService guardedPassiveSource() {
      FACTORY_METHOD_INVOCATIONS.incrementAndGet();
      return null;
    }
  }

  @Test
  void manualCompositionUsesDefaultDeniedOwnerEvidence() {
    var configuration = new CanonicalGameplayRosterConfiguration();
    CharacterRepository repository = mock(CharacterRepository.class);
    CanonicalGameplayRosterOwnerEvidencePort ownerEvidencePort =
        configuration.unavailableCanonicalGameplayRosterOwnerEvidence();
    CanonicalGameplayRosterService service =
        configuration.canonicalGameplayRosterService(ownerEvidencePort, repository);

    assertThatThrownBy(() -> service.read(request(target())))
        .isInstanceOf(
            CanonicalGameplayRosterOwnerEvidencePort.OwnerEvidenceUnavailableException.class)
        .hasMessage("CANONICAL_GAMEPLAY_ROSTER_OWNER_EVIDENCE_UNAVAILABLE");
    verifyNoInteractions(repository);
  }

  @Test
  void explicitlyInvokedFactoriesCreateOnlyTheSuppliedOwnerReadComposition() {
    var configuration = new CanonicalGameplayRosterConfiguration();
    var channels = mock(GrpcChannelFactory.class);
    var manuallyCreatedClient =
        configuration.gameSessionCanonicalGameplayRosterOwnerReadClient(
            new ServiceEndpointsProperties(), tlsProperties(), channels, "gameplay");
    var suppliedClient = mock(GameSessionCanonicalGameplayRosterOwnerReadClient.class);
    var ownerEvidencePort =
        configuration.gameSessionCanonicalGameplayRosterOwnerEvidencePort(
            suppliedClient, "gameplay");
    var service =
        configuration.canonicalGameplayRosterService(
            ownerEvidencePort, mock(CharacterRepository.class));

    assertThat(manuallyCreatedClient)
        .isInstanceOf(GameSessionCanonicalGameplayRosterOwnerReadClient.class);
    assertThat(ownerEvidencePort)
        .isInstanceOf(GameSessionCanonicalGameplayRosterOwnerEvidenceAdapter.class);
    assertThat(service).isInstanceOf(CanonicalGameplayRosterService.class);
    verifyNoInteractions(channels);
  }

  private static CanonicalGameplayRosterReadRequest request(CanonicalGameplayRosterTarget target) {
    UUID requestUuid = UUID.randomUUID();
    UUID accountUuid = UUID.randomUUID();
    return new CanonicalGameplayRosterReadRequest(
        requestUuid,
        accountUuid,
        target,
        new CanonicalGameplayRosterExecutionContext(
            accountUuid,
            target.tenantUuid(),
            target.playableStateNamespaceId(),
            target.gameInstanceUuid(),
            null,
            UUID.randomUUID(),
            target.realmUuid(),
            requestUuid,
            target.playableStateScope()));
  }

  private static CanonicalGameplayRosterTarget target() {
    return new CanonicalGameplayRosterTarget(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "world",
        "realm",
        UUID.randomUUID(),
        3L,
        17L,
        29L,
        UUID.randomUUID(),
        "1".repeat(64),
        "release/test",
        "2".repeat(64),
        "3".repeat(64),
        UUID.randomUUID(),
        PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
        CanonicalGameplayRosterEntryPolicy.PRESEEDED_ONLY);
  }

  private static CommonGrpcClientProperties tlsProperties() {
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain("entity-client.crt");
    tls.setPrivateKey("entity-client.key");
    tls.setCaCert("game-session-ca.crt");
    return tls;
  }
}
