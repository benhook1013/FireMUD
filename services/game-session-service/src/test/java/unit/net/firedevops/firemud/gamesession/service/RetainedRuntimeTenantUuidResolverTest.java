package unit.net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.gamesession.config.RetainedRuntimeTenantUuidResolverConfiguration;
import net.firedevops.firemud.gamesession.repository.GameSessionRetainedTenantAssociationRepository;
import net.firedevops.firemud.gamesession.service.RetainedRuntimeTenantUuidResolver;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

class RetainedRuntimeTenantUuidResolverTest {
  private static final String WORKLOAD_NAMESPACE = "gamesession-test";
  private static final UUID CANONICAL_TENANT_ID =
      UUID.fromString("7c958a3d-401e-47ee-8df8-351988b6ce26");

  private final GameSessionRetainedTenantAssociationRepository repository =
      mock(GameSessionRetainedTenantAssociationRepository.class);
  private final PlatformTransactionManager transactionManager = new ReadOnlyTransactionManager();

  @Test
  void resolvesOnlyTheConfiguredNamespaceAndExactPositiveRetainedKey() {
    when(repository.readCanonicalTenantIdByRetainedTenantKey(42L, WORKLOAD_NAMESPACE))
        .thenReturn(Optional.of(CANONICAL_TENANT_ID));
    RetainedRuntimeTenantUuidResolver resolver =
        new RetainedRuntimeTenantUuidResolver(repository, WORKLOAD_NAMESPACE, transactionManager);

    assertThat(resolver.resolveCanonicalTenantId(42L)).contains(CANONICAL_TENANT_ID);

    verify(repository).readCanonicalTenantIdByRetainedTenantKey(42L, WORKLOAD_NAMESPACE);
  }

  @Test
  void missingNamespaceOrNonPositiveRetainedKeyDoesNotQueryTheRepository() {
    RetainedRuntimeTenantUuidResolver missingNamespace =
        RetainedRuntimeTenantUuidResolver.denyOnly("");
    RetainedRuntimeTenantUuidResolver configured =
        new RetainedRuntimeTenantUuidResolver(repository, WORKLOAD_NAMESPACE, transactionManager);
    RetainedRuntimeTenantUuidResolver invalidNamespace =
        RetainedRuntimeTenantUuidResolver.denyOnly("Bad.Namespace");

    assertThat(missingNamespace.resolveCanonicalTenantId(42L)).isEmpty();
    assertThat(invalidNamespace.resolveCanonicalTenantId(42L)).isEmpty();
    assertThat(RetainedRuntimeTenantUuidResolver.denyOnly(null).resolveCanonicalTenantId(42L))
        .isEmpty();
    assertThat(configured.resolveCanonicalTenantId(0L)).isEmpty();
    assertThat(configured.resolveCanonicalTenantId(-1L)).isEmpty();

    verifyNoInteractions(repository);
  }

  @Test
  void configurationWithMissingOrInvalidNamespaceConstructsOnlyDenyOnlyResolvers() {
    RetainedRuntimeTenantUuidResolverConfiguration configuration =
        new RetainedRuntimeTenantUuidResolverConfiguration();

    RetainedRuntimeTenantUuidResolver missingNamespace =
        configuration.retainedRuntimeTenantUuidResolver(null, null, null, "");
    RetainedRuntimeTenantUuidResolver invalidNamespace =
        configuration.retainedRuntimeTenantUuidResolver(null, null, null, "Bad.Namespace");

    assertThat(missingNamespace.resolveCanonicalTenantId(42L)).isEmpty();
    assertThat(invalidNamespace.resolveCanonicalTenantId(42L)).isEmpty();
  }

  @Test
  void repositoryFailureAndMissingAssociationRemainUnavailable() {
    RetainedRuntimeTenantUuidResolver resolver =
        new RetainedRuntimeTenantUuidResolver(repository, WORKLOAD_NAMESPACE, transactionManager);
    when(repository.readCanonicalTenantIdByRetainedTenantKey(42L, WORKLOAD_NAMESPACE))
        .thenThrow(new IllegalStateException("malformed retained receipt"));
    when(repository.readCanonicalTenantIdByRetainedTenantKey(43L, WORKLOAD_NAMESPACE))
        .thenReturn(Optional.empty());

    assertThat(resolver.resolveCanonicalTenantId(42L)).isEmpty();
    assertThat(resolver.resolveCanonicalTenantId(43L)).isEmpty();
  }

  private static final class ReadOnlyTransactionManager implements PlatformTransactionManager {
    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
      TransactionDefinition effectiveDefinition =
          definition == null ? TransactionDefinition.withDefaults() : definition;
      assertThat(effectiveDefinition.getPropagationBehavior())
          .isEqualTo(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
      assertThat(effectiveDefinition.isReadOnly()).isTrue();
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {}

    @Override
    public void rollback(TransactionStatus status) {}
  }
}
