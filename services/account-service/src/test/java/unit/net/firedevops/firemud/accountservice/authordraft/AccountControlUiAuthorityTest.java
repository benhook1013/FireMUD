package net.firedevops.firemud.accountservice.authordraft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountPlatformRestrictionBirthSource.Category;
import net.firedevops.firemud.accountservice.dto.AccountPlatformRestrictionMutationRequest.RestrictionState;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.entity.AccountLifecycleState;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService.CapturedEnvironmentBoundary;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService.CurrentnessEvidence;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService.EnvironmentBoundCurrentness;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.CompositeSnapshot;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.IssuanceFence;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.CurrentSourceEvidence;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.IssuerAccountSourceSnapshot;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.SourceCheckpoint;
import net.firedevops.firemud.accountservice.repository.AccountPlatformRestrictionOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountPlatformRestrictionOperationRepository.CurrentProjection;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.service.AccountAuthoritySourceReader.AccountSourceSnapshot;
import net.firedevops.firemud.accountservice.service.AccountGenerationProjection;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer.CreatorControlCaptureSources;
import net.firedevops.firemud.accountservice.service.IssuerGenerationProjection;
import org.jooq.ConnectionRunnable;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountControlUiAuthorityTest {
  @Test
  void accountCaptureRetainsOnlyLockedAccountIssuerRoleAndCurrentRestrictionSources()
      throws Exception {
    var context = new AccountCaptureContext();
    var snapshot = context.capture();

    assertThat(snapshot.scopeKind()).isEqualTo(AccountControlUiAuthority.ScopeKind.ACCOUNT);
    assertThat(snapshot.optionalTenantId()).isEmpty();
    assertThatThrownBy(snapshot::tenant).isInstanceOf(IllegalStateException.class);
    assertThat(snapshot.actor()).isEqualTo(context.id);
    assertThat(snapshot.scopedRoles()).isEmpty();
    assertThat(snapshot.globalRoles()).isEmpty();
    assertThat(snapshot.membershipVersion()).isEmpty();
    assertThat(snapshot.authorityTuple())
        .containsEntry("tenantAuthorityGeneration", Map.of())
        .containsEntry("membershipAuthorityGeneration", Map.of())
        .containsEntry("privateRealmGrantVersions", List.of());
    assertThat(snapshot.sources())
        .extracting(value -> value.kind().name())
        .containsExactly("ACCOUNT", "GLOBAL_ROLES", "ISSUER");
    assertThat(snapshot.sources().getFirst().evidence())
        .asString(StandardCharsets.UTF_8)
        .contains("ACCOUNT_SECURITY_LOCK", "PLATFORM_ACCESS_BAN", context.lockResult.toString());
    assertThat(snapshot.issuanceFence()).isEqualTo(4L);
    assertThat(snapshot.issuanceFenceSourceVersion()).isEqualTo(12L);
    assertThat(snapshot.evidence())
        .asString(StandardCharsets.UTF_8)
        .contains("\"scopeKind\":\"ACCOUNT\"");
    org.mockito.Mockito.verifyNoInteractions(context.drafts);
  }

  @Test
  void accountCaptureUsesActualVersionedRolesAndRestrictionResultsForRecapture() throws Exception {
    var context = new AccountCaptureContext();
    byte[] original = context.capture().evidence();
    when(context.roleRow.get("global_roles", String[].class)).thenReturn(new String[] {"support"});
    when(context.roleRow.get("global_role_source_version", Long.class)).thenReturn(8L);
    var roleChange = context.capture();
    assertThat(roleChange.globalRoles()).containsExactly("support");
    assertThat(roleChange.evidence()).isNotEqualTo(original);
    context.restriction(
        Category.ACCOUNT_SECURITY_LOCK, 3L, UUID.randomUUID(), RestrictionState.NONRESTRICTED);
    assertThat(context.capture().evidence()).isNotEqualTo(roleChange.evidence());
  }

  @Test
  void accountCaptureRefusesMissingOrRestrictedCurrentProjectionWithoutBirthFallback()
      throws Exception {
    var context = new AccountCaptureContext();
    when(context.restrictions.readCurrent(context.id, Category.PLATFORM_ACCESS_BAN))
        .thenThrow(new IllegalStateException("current-projection-unavailable"));
    assertThatThrownBy(context::capture).hasMessage("current-projection-unavailable");
    context.restriction(
        Category.PLATFORM_ACCESS_BAN, 2L, UUID.randomUUID(), RestrictionState.RESTRICTED);
    assertThatThrownBy(context::capture).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void accountCaptureRefusesAbsentRolesLockedLifecycleAndMismatchedIdentity() throws Exception {
    var context = new AccountCaptureContext();
    when(context.roleRow.get("global_roles", String[].class)).thenReturn(null);
    assertThatThrownBy(context::capture).isInstanceOf(IllegalStateException.class);
    context.account.setLifecycleState(AccountLifecycleState.SECURITY_LOCKED);
    assertThatThrownBy(context::capture).isInstanceOf(IllegalStateException.class);
    context.account.setLifecycleState(AccountLifecycleState.ACTIVE);
    context.account.setAccountUuidSourceNumericId(99L);
    assertThatThrownBy(context::capture).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void accountCaptureRequiresWritableOwnerTransactionBeforeReadingAccount() throws Exception {
    var context = new AccountCaptureContext();
    assertThatThrownBy(() -> context.authority.captureAccount(context.id))
        .isInstanceOf(IllegalStateException.class);
    org.mockito.Mockito.verifyNoInteractions(
        context.accounts, context.upstream, context.restrictions);
  }

  /** Controlled owner collaborators exercise composition only, not physical source authority. */
  private static final class AccountCaptureContext {
    final UUID id = UUID.randomUUID();
    final UUID lockResult = UUID.randomUUID();
    final Account account = new Account();
    final AccountRepository accounts = mock(AccountRepository.class);
    final AccountAuthoritySourceEvidenceRepository upstream =
        mock(AccountAuthoritySourceEvidenceRepository.class);
    final AccountPlatformRestrictionOperationRepository restrictions =
        mock(AccountPlatformRestrictionOperationRepository.class);
    final AccountDraftSourceCompositionService drafts =
        mock(AccountDraftSourceCompositionService.class);
    final DSLContext dsl = mock(DSLContext.class);
    final Record roleRow = mock(Record.class);
    final AccountControlUiAuthority authority;

    AccountCaptureContext() throws Exception {
      account.setId(7L);
      account.setAccountUuid(id);
      account.setAccountUuidSourceNumericId(7L);
      account.setAccountUuidProvenance(AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT);
      account.setLifecycleState(AccountLifecycleState.ACTIVE);
      when(accounts.findByAccountUuidForUpdate(id)).thenReturn(Optional.of(account));
      var fence = new IssuanceFence(id, 4L, 12L);
      var issuer =
          new CurrentSourceEvidence(
              AuthorityScope.issuer("firemud-account-service"),
              1L,
              1L,
              null,
              new SourceCheckpoint(
                  "account:auth-authority:v1:issuer/firemud-account-service",
                  0L,
                  Optional.empty(),
                  Optional.empty()),
              Optional.empty(),
              "ISSUER_SCOPE_INSERT",
              null,
              null,
              3L,
              null);
      var source =
          new CurrentSourceEvidence(
              AuthorityScope.account(id),
              1L,
              1L,
              fence,
              new SourceCheckpoint(
                  "account:auth-authority:v1:account/" + id,
                  0L,
                  Optional.empty(),
                  Optional.empty()),
              Optional.empty(),
              "ACCOUNT_REPOSITORY_INSERT",
              7L,
              "ACCOUNT_REPOSITORY_INSERT",
              3L,
              3L);
      var projection =
          AccountGenerationProjection.fromSource(
              new AccountSourceSnapshot(
                  id,
                  new ScopeState(AuthorityScope.account(id), 1L, 1L, fence),
                  source.checkpoint().outboxStreamKey(),
                  0L,
                  Optional.empty()));
      when(upstream.readCurrentIssuerAccountSources("firemud-account-service", id))
          .thenReturn(
              new IssuerAccountSourceSnapshot(
                  issuer,
                  source,
                  fence,
                  projection,
                  IssuerGenerationProjection.fromSource(issuer)));
      restriction(Category.ACCOUNT_SECURITY_LOCK, 2L, lockResult, RestrictionState.NONRESTRICTED);
      restriction(
          Category.PLATFORM_ACCESS_BAN, 2L, UUID.randomUUID(), RestrictionState.NONRESTRICTED);
      when(dsl.fetchOne(anyString(), eq(id))).thenReturn(roleRow);
      when(roleRow.get("account_uuid", UUID.class)).thenReturn(id);
      when(roleRow.get("account_uuid_source_numeric_id", Long.class)).thenReturn(7L);
      when(roleRow.get("account_uuid_provenance", String.class))
          .thenReturn("ACCOUNT_REPOSITORY_INSERT");
      when(roleRow.get("global_role_source_version", Long.class)).thenReturn(7L);
      when(roleRow.get("global_roles", String[].class)).thenReturn(new String[0]);
      var connection = mock(Connection.class);
      when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
      doAnswer(
              invocation -> {
                invocation.getArgument(0, ConnectionRunnable.class).run(connection);
                return null;
              })
          .when(dsl)
          .connection(org.mockito.ArgumentMatchers.any(ConnectionRunnable.class));
      authority = new AccountControlUiAuthority(drafts, accounts, upstream, restrictions, dsl);
    }

    void restriction(Category category, long revision, UUID result, RestrictionState state) {
      doReturn(
              new CurrentProjection(
                  id, category, revision, revision, result, state, UUID.randomUUID()))
          .when(restrictions)
          .readCurrent(id, category);
    }

    AccountControlUiAuthority.Snapshot capture() {
      TransactionSynchronizationManager.setActualTransactionActive(true);
      try {
        return authority.captureAccount(id);
      } finally {
        TransactionSynchronizationManager.clear();
      }
    }
  }

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
    var authority =
        new AccountControlUiAuthority(
            sources,
            mock(AccountRepository.class),
            mock(AccountAuthoritySourceEvidenceRepository.class),
            mock(AccountPlatformRestrictionOperationRepository.class),
            mock(DSLContext.class));
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
    var authority =
        new AccountControlUiAuthority(
            sources,
            mock(AccountRepository.class),
            mock(AccountAuthoritySourceEvidenceRepository.class),
            mock(AccountPlatformRestrictionOperationRepository.class),
            mock(DSLContext.class));

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
    var authority =
        new AccountControlUiAuthority(
            sources,
            mock(AccountRepository.class),
            mock(AccountAuthoritySourceEvidenceRepository.class),
            mock(AccountPlatformRestrictionOperationRepository.class),
            mock(DSLContext.class));
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
