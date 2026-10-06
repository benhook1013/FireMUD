package unit.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.IssuanceFence;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeKind;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class AccountAuthoritySourceEvidenceRepositoryTest {
  private static final UUID ACCOUNT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");

  private final DSLContext dsl = mock(DSLContext.class);
  private final AccountAuthorityGenerationRepository generations =
      new AccountAuthorityGenerationRepository(dsl);
  private final AccountAuthorityOutboxRepository outbox = new AccountAuthorityOutboxRepository(dsl);
  private final AccountAuthoritySourceEvidenceRepository repository =
      new AccountAuthoritySourceEvidenceRepository(dsl, generations, outbox);

  @Test
  void sequenceZeroIsAnExplicitProvenanceBearingCheckpointWithoutSyntheticEventIdentity() {
    var checkpoint =
        new AccountAuthoritySourceEvidenceRepository.SourceCheckpoint(
            "account:auth-authority:v1:issuer/firemud-account-service",
            0L,
            Optional.empty(),
            Optional.empty());
    var issuer =
        new AccountAuthoritySourceEvidenceRepository.CurrentSourceEvidence(
            AuthorityScope.issuer("firemud-account-service"),
            1L,
            1L,
            null,
            checkpoint,
            Optional.empty(),
            "ISSUER_SCOPE_INSERT",
            null,
            null,
            1L,
            null);

    assertThat(issuer.checkpoint()).isEqualTo(checkpoint);
    assertThat(issuer.initializationProvenance()).isEqualTo("ISSUER_SCOPE_INSERT");
    assertThat(issuer.accountSecurityCutoff()).isEmpty();
  }

  @Test
  void rejectsPartialOrFabricatedSequenceZeroCheckpoints() {
    assertThatThrownBy(
            () ->
                new AccountAuthoritySourceEvidenceRepository.SourceCheckpoint(
                    "account:auth-authority:v1:issuer/firemud-account-service",
                    0L,
                    Optional.of("synthetic-event"),
                    Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AccountAuthoritySourceEvidenceRepository.SourceCheckpoint(
                    "account:auth-authority:v1:issuer/firemud-account-service",
                    1L,
                    Optional.empty(),
                    Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void accountSnapshotRequiresSameAccountFenceAndExactScopeKinds() {
    var fence = new IssuanceFence(ACCOUNT_ID, 1L, 1L);
    var issuer =
        new AccountAuthoritySourceEvidenceRepository.CurrentSourceEvidence(
            AuthorityScope.issuer("firemud-account-service"),
            1L,
            1L,
            null,
            zeroCheckpoint("issuer/firemud-account-service"),
            Optional.empty(),
            "ISSUER_SCOPE_INSERT",
            null,
            null,
            1L,
            null);
    var account =
        new AccountAuthoritySourceEvidenceRepository.CurrentSourceEvidence(
            AuthorityScope.account(ACCOUNT_ID),
            1L,
            1L,
            fence,
            zeroCheckpoint("account/" + ACCOUNT_ID),
            Optional.empty(),
            "ACCOUNT_REPOSITORY_INSERT",
            9L,
            "ACCOUNT_REPOSITORY_INSERT",
            1L,
            1L);

    assertThat(
            new AccountAuthoritySourceEvidenceRepository.IssuerAccountSourceSnapshot(
                issuer, account, fence))
        .satisfies(snapshot -> assertThat(snapshot.account().issuanceFence()).isEqualTo(fence));
    assertThatThrownBy(
            () ->
                new AccountAuthoritySourceEvidenceRepository.IssuerAccountSourceSnapshot(
                    account, account, fence))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AccountAuthoritySourceEvidenceRepository.IssuerAccountSourceSnapshot(
                    issuer, account, new IssuanceFence(UUID.randomUUID(), 1L, 1L)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void issuerEnrollmentRequiresValidIdentityAndMandatoryOwnerTransaction()
      throws ReflectiveOperationException {
    assertThatThrownBy(() -> repository.initializeIssuerIfAbsent(" "))
        .isInstanceOf(IllegalArgumentException.class);
    assertMandatory("initializeIssuerIfAbsent", String.class);
    assertMandatory("initializeFreshAccount", AccountRepository.FreshAccountInsert.class);
    assertMandatory("recordAccountUpdate", AccountRepository.AccountUpdateEvidence.class);

    verifyNoInteractions(dsl);
  }

  @Test
  void sourceEvidenceIsRestrictedToIssuerAndAccountScopes() {
    assertThat(ScopeKind.valueOf("ISSUER")).isEqualTo(ScopeKind.ISSUER);
    assertThat(ScopeKind.valueOf("ACCOUNT")).isEqualTo(ScopeKind.ACCOUNT);
    assertThatThrownBy(
            () ->
                new AccountAuthoritySourceEvidenceRepository.CurrentSourceEvidence(
                    AuthorityScope.account(ACCOUNT_ID),
                    0L,
                    1L,
                    new IssuanceFence(ACCOUNT_ID, 1L, 1L),
                    zeroCheckpoint("account/" + ACCOUNT_ID),
                    Optional.empty(),
                    "ACCOUNT_REPOSITORY_INSERT",
                    9L,
                    "ACCOUNT_REPOSITORY_INSERT",
                    1L,
                    1L))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static AccountAuthoritySourceEvidenceRepository.SourceCheckpoint zeroCheckpoint(
      String suffix) {
    return new AccountAuthoritySourceEvidenceRepository.SourceCheckpoint(
        "account:auth-authority:v1:" + suffix, 0L, Optional.empty(), Optional.empty());
  }

  private static void assertMandatory(String methodName, Class<?> parameterType)
      throws ReflectiveOperationException {
    Transactional transactional =
        AccountAuthoritySourceEvidenceRepository.class
            .getMethod(methodName, parameterType)
            .getAnnotation(Transactional.class);
    assertThat(transactional).isNotNull();
    assertThat(transactional.propagation()).isEqualTo(Propagation.MANDATORY);
  }
}
