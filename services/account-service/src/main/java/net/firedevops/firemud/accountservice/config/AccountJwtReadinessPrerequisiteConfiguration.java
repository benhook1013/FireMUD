package net.firedevops.firemud.accountservice.config;

import net.firedevops.firemud.accountservice.repository.AccountJwtJwksPublicationRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository;
import net.firedevops.firemud.accountservice.service.session.AccountJwtJwksConfigMapClient;
import net.firedevops.firemud.accountservice.service.session.AccountJwtJwksTrustedSource;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Default-inactive, readiness-only prerequisite graph for Account's protected public-JWKS reads.
 *
 * <p>This configuration checks explicit selection only. The protected API and materializer bindings
 * remain responsible for validating their actual protected files whenever used.
 */
@Configuration(proxyBeanMethods = false)
@Conditional(
    AccountJwtReadinessPrerequisiteConfiguration.ProtectedReadinessConfigurationPresent.class)
@Lazy
public class AccountJwtReadinessPrerequisiteConfiguration {
  private static final String READINESS_VALIDATION_ENABLED =
      "firemud.account.jwt-readiness.validation.enabled";
  private static final String READINESS_PROBE_OWNER_ENABLED =
      "firemud.account.jwt-readiness.probe-owner.enabled";
  private static final String READINESS_POD_RECEIVER_ENABLED =
      "firemud.account.jwt-readiness.pod-receiver.enabled";
  private static final String JWKS_API_ENABLED = "firemud.account.jwt-jwks-api.enabled";
  private static final String JWKS_API_BINDING =
      "firemud.account.jwt-jwks-api.protected-binding-path";
  private static final String MATERIALIZATION_ENABLED =
      "firemud.account.jwt-signer.materialization.enabled";
  private static final String MATERIALIZER_BINDING =
      "firemud.account.jwt-signer.materialization.protected-binding-path";
  private static final String WORKLOAD_NAMESPACE = "firemud.grpc.workload-namespace";
  private static final String EXPECTED_JWKS_API_BINDING =
      "/etc/firemud/account-jwt-api/binding.json";
  private static final String EXPECTED_MATERIALIZER_BINDING =
      "/etc/firemud/account-jwt-materializer/binding.json";

  @Bean(name = "accountJwtReadinessJwksApiBinding")
  @Lazy
  public AccountJwtJwksApiBinding accountJwtReadinessJwksApiBinding() {
    return new AccountJwtJwksApiBinding(true, EXPECTED_JWKS_API_BINDING);
  }

  @Bean(name = "accountJwtReadinessJwksConfigMapClient")
  @Lazy
  public AccountJwtJwksConfigMapClient accountJwtReadinessJwksConfigMapClient(
      @Qualifier("accountJwtReadinessJwksApiBinding") AccountJwtJwksApiBinding apiBinding) {
    return new AccountJwtJwksConfigMapClient(apiBinding);
  }

  @Bean(name = "accountJwtReadinessTrustedJwksSource")
  @Lazy
  public AccountJwtJwksTrustedSource accountJwtReadinessTrustedJwksSource(
      @Qualifier("accountJwtReadinessJwksConfigMapClient")
          AccountJwtJwksConfigMapClient configMapClient,
      AccountJwtSignerMaterializerTrustBinding materializerTrustBinding,
      AccountJwtSignerDesiredStateRepository desiredStateRepository,
      AccountJwtJwksPublicationRepository publicationRepository,
      PlatformTransactionManager transactionManager) {
    return new AccountJwtJwksTrustedSource(
        configMapClient,
        materializerTrustBinding,
        desiredStateRepository,
        publicationRepository,
        transactionManager);
  }

  /** Checks readiness selection and exact protected-binding paths, not protected file contents. */
  public static final class ProtectedReadinessConfigurationPresent implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
      Environment environment = context.getEnvironment();
      boolean readinessEnabled =
          explicitlyEnabled(environment, READINESS_VALIDATION_ENABLED)
              || explicitlyEnabled(environment, READINESS_PROBE_OWNER_ENABLED)
              || explicitlyEnabled(environment, READINESS_POD_RECEIVER_ENABLED);
      if (!readinessEnabled
          || !explicitlyEnabled(environment, JWKS_API_ENABLED)
          || !EXPECTED_JWKS_API_BINDING.equals(environment.getProperty(JWKS_API_BINDING))
          || !explicitlyEnabled(environment, MATERIALIZATION_ENABLED)
          || !EXPECTED_MATERIALIZER_BINDING.equals(environment.getProperty(MATERIALIZER_BINDING))) {
        return false;
      }

      return GrpcPeerIdentity.isValidNamespace(environment.getProperty(WORKLOAD_NAMESPACE));
    }

    private static boolean explicitlyEnabled(Environment environment, String property) {
      return "true".equals(environment.getProperty(property));
    }
  }
}
