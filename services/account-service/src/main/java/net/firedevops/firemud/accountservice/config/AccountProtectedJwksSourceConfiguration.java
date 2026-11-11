package net.firedevops.firemud.accountservice.config;

import net.firedevops.firemud.accountservice.repository.AccountJwtJwksPublicationRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository;
import net.firedevops.firemud.accountservice.service.session.AccountJwtJwksConfigMapClient;
import net.firedevops.firemud.accountservice.service.session.AccountJwtJwksTrustedSource;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Explicit-import owner of Account's single protected public-JWKS source graph. This lite bean
 * owner is not component-scanned and selects no gameplay, public-read or other consumer. Each
 * independently selected consumer imports this same owner; repeated imports share the graph.
 */
public class AccountProtectedJwksSourceConfiguration {
  private static final String JWKS_API_BINDING_PATH = "/etc/firemud/account-jwt-api/binding.json";

  @Bean
  AccountJwtJwksApiBinding accountPublicJwksApiBinding(
      AccountJwtSignerMaterializerTrustBinding materializerTrust,
      @Value("${firemud.account.public-jwks.api-binding-path:}") String bindingPath,
      @Value("${firemud.grpc.workload-namespace:}") String namespace) {
    if (!JWKS_API_BINDING_PATH.equals(bindingPath)
        || !GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw unavailable();
    }
    try {
      var api = new AccountJwtJwksApiBinding(true, bindingPath);
      var materializer = materializerTrust.current().orElseThrow();
      var current = api.current();
      if (!namespace.equals(materializer.namespace())
          || !namespace.equals(current.namespace())
          || !materializer.environmentId().equals(current.environmentId())
          || !materializer.clusterId().equals(current.clusterId())
          || !materializer
              .expectedClusterIncarnationUid()
              .equals(current.expectedClusterIncarnationUid())
          || !materializer.expectedNamespaceUid().equals(current.expectedNamespaceUid())) {
        throw unavailable();
      }
      // current() pins the protected API identity. Existing owners reread and compare both trust
      // sources on use; a later materializer namespace cannot replace this accepted API identity.
      return api;
    } catch (RuntimeException failure) {
      throw unavailable();
    }
  }

  @Bean
  AccountJwtJwksConfigMapClient accountPublicJwksClient(AccountJwtJwksApiBinding api) {
    return new AccountJwtJwksConfigMapClient(api);
  }

  @Bean
  AccountJwtJwksTrustedSource accountPublicTrustedJwks(
      AccountJwtJwksConfigMapClient client,
      AccountJwtSignerMaterializerTrustBinding trust,
      AccountJwtSignerDesiredStateRepository signers,
      AccountJwtJwksPublicationRepository publications,
      @Qualifier("transactionManager") PlatformTransactionManager transactions) {
    return new AccountJwtJwksTrustedSource(client, trust, signers, publications, transactions);
  }

  private static IllegalArgumentException unavailable() {
    return new IllegalArgumentException("Protected Account public JWKS configuration required");
  }
}
