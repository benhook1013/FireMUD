package net.firedevops.firemud.accountservice.authordraft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService.CapturedEnvironmentBoundary;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService.CurrentnessEvidence;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService.EnvironmentBoundCurrentness;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.CompositeSnapshot;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.IssuanceFence;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer.CreatorControlCaptureSources;
import org.junit.jupiter.api.Test;

class AccountControlUiAuthorityTest {
  @Test
  void retainsFenceSourceVersionIndependentlyFromActualFenceValue() {
    UUID account = UUID.randomUUID();
    var first = AccountControlUiAuthority.requireIssuanceFenceSource(member(account, 4L, 12L));
    var advanced = AccountControlUiAuthority.requireIssuanceFenceSource(member(account, 4L, 13L));

    assertThat(first.value()).isEqualTo(4L);
    assertThat(first.sourceVersion()).isEqualTo(12L);
    assertThat(advanced.value()).isEqualTo(first.value());
    assertThat(advanced.sourceVersion()).isEqualTo(13L);
  }

  @Test
  void rejectsFenceSourceThatDoesNotMatchTheAuthenticatedMemberValue() {
    UUID account = UUID.randomUUID();
    var member = member(account, 4L, 12L, 5L);

    assertThatThrownBy(() -> AccountControlUiAuthority.requireIssuanceFenceSource(member))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("issuance fence source");
  }

  @Test
  void typedIssuanceFenceRejectsNonPositiveSourceVersion() {
    assertThatThrownBy(() -> new IssuanceFence(UUID.randomUUID(), 4L, 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be positive");
  }

  private static CreatorControlCaptureSources member(UUID account, long fence, long sourceVersion) {
    return member(account, fence, sourceVersion, fence);
  }

  private static CreatorControlCaptureSources member(
      UUID account, long fence, long sourceVersion, long authenticatedMemberFence) {
    var issuerFence = new IssuanceFence(account, fence, sourceVersion);
    var issuer = new ScopeState(AuthorityScope.issuer("firemud-account-service"), 1L, 1L, null);
    var accountState = new ScopeState(AuthorityScope.account(account), 1L, 1L, issuerFence);
    var snapshot = new CompositeSnapshot(issuer, accountState, List.of(), List.of(), issuerFence);
    return new CreatorControlCaptureSources(
        account,
        UUID.randomUUID(),
        snapshot,
        Map.of(),
        null,
        Long.toString(authenticatedMemberFence),
        null,
        null,
        null,
        null,
        null,
        List.of(),
        List.of(),
        Instant.EPOCH);
  }

  @Test
  void explicitTypedNoDeadlineReadbackPermitsExistingCompositionToContinue() {
    var sources = mock(AccountDraftSourceCompositionService.class);
    var existing = mock(AccountDraftSourceCompositionService.ExistingSources.class);
    var hosted = mock(EnvironmentBoundCurrentness.class);
    var terms = mock(CurrentnessEvidence.class);
    var environment = mock(CapturedEnvironmentBoundary.class);
    UUID tenant = UUID.randomUUID();
    when(sources.readExistingSources(environment, tenant)).thenReturn(existing);
    when(existing.hostedTerms()).thenReturn(hosted);
    when(hosted.terms()).thenReturn(terms);
    when(terms.validUntil()).thenReturn(null);
    when(terms.disclosedDeadline()).thenReturn(null);
    // Stop at the next stage: this is not a fabricated successful source/provenance fixture.
    when(existing.membership())
        .thenThrow(new IllegalStateException("membership-stage-test-sentinel"));
    var authority = new AccountControlUiAuthority(sources);
    assertThatThrownBy(() -> authority.capture(UUID.randomUUID(), tenant, environment))
        .hasMessage("membership-stage-test-sentinel");
    assertThatThrownBy(() -> authority.captureInitial(tenant, environment))
        .hasMessage("membership-stage-test-sentinel");
  }

  @Test
  void bothCapturePathsDenyDeadlineQualifiedOwnerReadbackBeforeMembershipOrSourceCapture() {
    // Explicit owner service doubles: this tests the enforcement boundary, not source provenance.
    var sources = mock(AccountDraftSourceCompositionService.class);
    var existing = mock(AccountDraftSourceCompositionService.ExistingSources.class);
    var hosted = mock(EnvironmentBoundCurrentness.class);
    var terms = mock(CurrentnessEvidence.class);
    var environment = mock(CapturedEnvironmentBoundary.class);
    UUID tenant = UUID.randomUUID();
    when(sources.readExistingSources(environment, tenant)).thenReturn(existing);
    when(existing.hostedTerms()).thenReturn(hosted);
    when(hosted.terms()).thenReturn(terms);
    when(terms.validUntil()).thenReturn(Instant.parse("2099-01-01T00:00:00Z"));
    var authority = new AccountControlUiAuthority(sources);

    assertThatThrownBy(() -> authority.capture(UUID.randomUUID(), tenant, environment))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Deadline-qualified");
    assertThatThrownBy(() -> authority.captureInitial(tenant, environment))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Deadline-qualified");
    verify(existing, never()).membership();
    verify(hosted, never()).sourceEvidence();
  }

  @Test
  void absentTypedOwnerCurrentnessCannotMasqueradeAsNoDeadline() {
    var sources = mock(AccountDraftSourceCompositionService.class);
    var existing = mock(AccountDraftSourceCompositionService.ExistingSources.class);
    var hosted = mock(EnvironmentBoundCurrentness.class);
    var environment = mock(CapturedEnvironmentBoundary.class);
    UUID tenant = UUID.randomUUID();
    when(sources.readExistingSources(environment, tenant)).thenReturn(existing);
    when(existing.hostedTerms()).thenReturn(hosted);
    var authority = new AccountControlUiAuthority(sources);
    assertThatThrownBy(() -> authority.capture(UUID.randomUUID(), tenant, environment))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> authority.captureInitial(tenant, environment))
        .isInstanceOf(NullPointerException.class);
    verify(existing, never()).membership();
  }

  @Test
  void canonicalizationPreservesExactlyRepresentableNestedCounters() {
    var bytes =
        AccountControlUiAuthority.canonical(
            Map.of("tuple", List.of(Map.of("counter", 42L), Map.of("counter", 9007199254740992L))));
    assertThat(new String(bytes, StandardCharsets.UTF_8)).contains("9007199254740992", "42");
  }

  @Test
  void canonicalizationRejectsArithmeticChangeInsteadOfIssuingRoundedAuthority() {
    for (long counter : new long[] {9007199254740993L, Long.MAX_VALUE}) {
      assertThatThrownBy(
              () ->
                  AccountControlUiAuthority.canonical(
                      Map.of("tuple", List.of(Map.of("counter", counter)))))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
