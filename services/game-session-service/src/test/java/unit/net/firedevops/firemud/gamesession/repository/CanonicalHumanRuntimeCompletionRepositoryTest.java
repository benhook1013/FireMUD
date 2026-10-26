package unit.net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionEvidence;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionWorldProof;
import net.firedevops.firemud.gamesession.repository.CanonicalHumanRuntimeCompletionRepository;
import net.firedevops.firemud.gamesession.repository.CanonicalInitialAdmissionIntentRepository;
import net.firedevops.firemud.gamesession.repository.CanonicalInitialAdmissionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

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
    assertReadCommitted(completion);
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

  private static void assertReadCommitted(Method method) {
    Transactional transaction = method.getAnnotation(Transactional.class);
    assertThat(transaction).isNotNull();
    assertThat(transaction.isolation()).isEqualTo(Isolation.READ_COMMITTED);
  }
}
