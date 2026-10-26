package unit.net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionEvidence;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionWorldProof;
import net.firedevops.firemud.gamesession.repository.CanonicalInitialAdmissionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

class CanonicalInitialAdmissionProtectedWriterTest {

  @Test
  void exposesOnlyExplicitProtectedReadCommittedMutationEntryPoints() throws Exception {
    Method reserve =
        CanonicalInitialAdmissionRepository.class.getMethod(
            "reserveProtected",
            CanonicalInitialAdmissionRequest.class,
            AccountStartSessionAdmissionProtectionEvidence.class);
    Method commit =
        CanonicalInitialAdmissionRepository.class.getMethod(
            "commitProtected",
            CanonicalInitialAdmissionRequest.class,
            AccountStartSessionAdmissionProtectionEvidence.class,
            CanonicalInitialAdmissionWorldProof.class);
    Method abort =
        CanonicalInitialAdmissionRepository.class.getMethod(
            "abortProtected",
            CanonicalInitialAdmissionRequest.class,
            AccountStartSessionAdmissionProtectionEvidence.class,
            String.class);

    assertReadCommitted(reserve);
    assertReadCommitted(commit);
    assertReadCommitted(abort);
    assertThat(commit.getParameterTypes())
        .containsExactly(
            CanonicalInitialAdmissionRequest.class,
            AccountStartSessionAdmissionProtectionEvidence.class,
            CanonicalInitialAdmissionWorldProof.class);
  }

  @Test
  void keepsLegacyHumanEntryPointsSeparateFromProtectedWriter() throws Exception {
    assertThat(
            CanonicalInitialAdmissionRepository.class.getMethod(
                "reserve", CanonicalInitialAdmissionRequest.class))
        .isNotEqualTo(
            CanonicalInitialAdmissionRepository.class.getMethod(
                "reserveProtected",
                CanonicalInitialAdmissionRequest.class,
                AccountStartSessionAdmissionProtectionEvidence.class));
    assertThat(
            CanonicalInitialAdmissionRepository.class.getMethod(
                "commit",
                CanonicalInitialAdmissionRequest.class,
                CanonicalInitialAdmissionWorldProof.class))
        .isNotEqualTo(
            CanonicalInitialAdmissionRepository.class.getMethod(
                "commitProtected",
                CanonicalInitialAdmissionRequest.class,
                AccountStartSessionAdmissionProtectionEvidence.class,
                CanonicalInitialAdmissionWorldProof.class));
    assertThat(
            CanonicalInitialAdmissionRepository.class.getMethod(
                "abort", CanonicalInitialAdmissionRequest.class, String.class))
        .isNotEqualTo(
            CanonicalInitialAdmissionRepository.class.getMethod(
                "abortProtected",
                CanonicalInitialAdmissionRequest.class,
                AccountStartSessionAdmissionProtectionEvidence.class,
                String.class));
  }

  private static void assertReadCommitted(Method method) {
    Transactional transaction = method.getAnnotation(Transactional.class);
    assertThat(transaction).isNotNull();
    assertThat(transaction.isolation()).isEqualTo(Isolation.READ_COMMITTED);
  }
}
