package unit.net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionEvidence;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionWorldProof;
import net.firedevops.firemud.gamesession.repository.CanonicalHumanRuntimeCompletionRepository;
import net.firedevops.firemud.gamesession.repository.CanonicalInitialAdmissionIntentRepository;
import net.firedevops.firemud.gamesession.repository.CanonicalInitialAdmissionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class CanonicalHumanRuntimeCompletionRepositoryTest {
  @Test
  void exposesOnlyExplicitProtectedStartingCompletionEntryPoints() throws Exception {
    Method completion =
        CanonicalHumanRuntimeCompletionRepository.class.getMethod(
            "completeProtected",
            CanonicalInitialAdmissionRequest.class,
            AccountStartSessionAdmissionProtectionEvidence.class,
            CanonicalInitialAdmissionWorldProof.class);
    Method reservation =
        CanonicalInitialAdmissionRepository.class.getMethod(
            "reserveProtectedStartingForRuntimeCompletion",
            CanonicalInitialAdmissionRequest.class,
            AccountStartSessionAdmissionProtectionEvidence.class);
    assertThat(Modifier.isFinal(CanonicalHumanRuntimeCompletionRepository.class.getModifiers()))
        .isTrue();
    assertThat(completion.getAnnotation(Transactional.class)).isNull();
    assertThat(CanonicalHumanRuntimeCompletionRepository.class.getAnnotations()).isEmpty();
    assertReadCommitted(reservation);
    assertThat(
            CanonicalInitialAdmissionIntentRepository.class.getMethod(
                "reserveHumanStarting",
                net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.Request
                    .class,
                net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence.Request
                    .class))
        .isNotNull();
    assertThat(
            CanonicalInitialAdmissionIntentRepository.class.getMethod(
                "attachHumanStarting",
                net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.Request
                    .class,
                net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity
                    .class))
        .isNotNull();
  }

  @Test
  void refusesForeignWorkloadNamespaceBeforeDatabaseAccess() {
    var dsl = mock(org.jooq.DSLContext.class);
    var repository = new CanonicalHumanRuntimeCompletionRepository(dsl, "owner-ns");
    var request = mock(CanonicalInitialAdmissionRequest.class);
    when(request.targetNamespace()).thenReturn("foreign-ns");
    assertThatThrownBy(() -> repository.completeProtected(request, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("another workload namespace");
    assertThatThrownBy(() -> repository.read("foreign-ns", "request"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("another workload namespace");
    verifyNoInteractions(dsl);
  }

  @Test
  void manuallyComposedCompletionRefusesMissingOrReadOnlyTransactionBeforeDatabaseAccess() {
    var dsl = mock(org.jooq.DSLContext.class);
    var repository = new CanonicalHumanRuntimeCompletionRepository(dsl, "owner-ns");
    var request = mock(CanonicalInitialAdmissionRequest.class);
    when(request.targetNamespace()).thenReturn("owner-ns");
    var protection = mock(AccountStartSessionAdmissionProtectionEvidence.class);
    var worldProof = mock(CanonicalInitialAdmissionWorldProof.class);
    assertThatThrownBy(() -> repository.completeProtected(request, protection, worldProof))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("writable READ COMMITTED transaction");
    try {
      TransactionSynchronizationManager.setActualTransactionActive(true);
      TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
      assertThatThrownBy(() -> repository.completeProtected(request, protection, worldProof))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("writable READ COMMITTED transaction");
    } finally {
      TransactionSynchronizationManager.clear();
    }
    verifyNoInteractions(dsl);
  }

  private static void assertReadCommitted(Method method) {
    Transactional transaction = method.getAnnotation(Transactional.class);
    assertThat(transaction).isNotNull();
    assertThat(transaction.isolation()).isEqualTo(Isolation.READ_COMMITTED);
  }
}
