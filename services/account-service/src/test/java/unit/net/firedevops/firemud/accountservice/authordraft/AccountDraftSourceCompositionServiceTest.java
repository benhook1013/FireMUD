package unit.net.firedevops.firemud.accountservice.authordraft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.authordraft.AccountDraftSourceCompositionService;
import net.firedevops.firemud.accountservice.creatorparty.CreatorPartyEncoding;
import net.firedevops.firemud.accountservice.creatorparty.IndividualCreatorPartyRepository;
import net.firedevops.firemud.accountservice.creatorparty.IndividualCreatorPartyRepository.AssociationReceipt;
import net.firedevops.firemud.accountservice.creatorparty.IndividualCreatorPartyRepository.InitialAssociationReadback;
import net.firedevops.firemud.accountservice.creatorparty.IndividualCreatorPartyRepository.InitialAssociationScope;
import net.firedevops.firemud.accountservice.creatorparty.IndividualCreatorPartySource;
import net.firedevops.firemud.accountservice.creatorparty.IndividualCreatorPartySource.VerificationStatus;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService;
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
import net.firedevops.firemud.accountservice.repository.AccountTenantCreationBootstrapOperationRepository.StoredOperation;
import net.firedevops.firemud.accountservice.service.AccountGenerationProjection;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer.CreatorControlCaptureSources;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer.OutboxCheckpointEntry;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer.OutboxSourceEvidence;
import net.firedevops.firemud.accountservice.service.AccountTenantCreationBootstrapService;
import net.firedevops.firemud.accountservice.service.IssuerGenerationProjection;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.FreshTenantCreatorDigest;
import net.firedevops.firemud.common.tenant.FreshTenantCreatorEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import org.jooq.ConnectionRunnable;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Mock owner-boundary composition proof; physical lock contention requires PostgreSQL proof. */
class AccountDraftSourceCompositionServiceTest {
  @AfterEach
  void clearTransaction() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void composesInOwnerOrderAndPreservesSequenceZeroBirthEvidence() throws Exception {
    Fixture f = new Fixture();
    var capture = f.read();
    assertThat(capture.membership()).isSameAs(f.membership);
    assertThat(capture.issuerAccount()).isSameAs(f.source);
    assertThat(capture.association()).isEqualTo(f.association);
    assertThat(capture.hostedTerms()).isSameAs(f.currentTerms);
    var order = inOrder(f.parties, f.memberships, f.upstream, f.terms);
    order.verify(f.parties).lockExistingInitialAssociationScope(f.tenant);
    order.verify(f.memberships).readExistingCreatorControlCaptureSources(f.account, f.tenant);
    order.verify(f.upstream).readCurrentIssuerAccountSources("firemud-account-service", f.account);
    order.verify(f.terms).requireCurrentnessInOwnerTransaction(f.environment, f.partyId);
    order.verify(f.parties).readExistingInitialAssociation(f.tenant);
    order.verifyNoMoreInteractions();
  }

  @Test
  void deniesMissingReadOnlyAutocommitAndWrongPhysicalIsolationBeforeSources() throws Exception {
    Fixture f = new Fixture();
    assertThatThrownBy(() -> f.service.readExistingSources(f.environment, f.tenant))
        .isInstanceOf(IllegalStateException.class);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
    assertThatThrownBy(() -> f.service.readExistingSources(f.environment, f.tenant))
        .isInstanceOf(IllegalStateException.class);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    when(f.connection.getAutoCommit()).thenReturn(true);
    f.rejects();
    when(f.connection.getAutoCommit()).thenReturn(false);
    when(f.connection.isReadOnly()).thenReturn(true);
    f.rejects();
    when(f.connection.isReadOnly()).thenReturn(false);
    when(f.connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_REPEATABLE_READ);
    f.rejects();
    verifyNoInteractions(f.parties, f.memberships, f.upstream, f.terms);
  }

  @Test
  void failedMembershipPrerequisiteCannotReachUpstreamTermsOrFinalReadback() throws Exception {
    Fixture f = new Fixture();
    when(f.memberships.readExistingCreatorControlCaptureSources(f.account, f.tenant))
        .thenThrow(new IllegalStateException("Absent current membership"));
    f.rejects();
    verifyNoInteractions(f.upstream, f.terms);
    org.mockito.Mockito.verify(f.parties, org.mockito.Mockito.never())
        .readExistingInitialAssociation(any());
  }

  @Test
  void changedAccountSourceAndIssuanceFenceDenyBeforeTerms() throws Exception {
    Fixture changedSource = new Fixture();
    when(changedSource.issuer.generation()).thenReturn(2L);
    changedSource.rejects();
    verifyNoInteractions(changedSource.terms);
    Fixture changedFence = new Fixture();
    when(changedFence.source.issuanceFence())
        .thenReturn(new IssuanceFence(changedFence.account, 2L, 2L));
    changedFence.rejects();
    verifyNoInteractions(changedFence.terms);
  }

  @Test
  void foreignTermsOwnerDeniesBeforeAssociationReadback() throws Exception {
    Fixture f = new Fixture();
    when(f.currentness.accountId()).thenReturn(UUID.randomUUID());
    f.rejects();
    org.mockito.Mockito.verify(f.parties, org.mockito.Mockito.never())
        .readExistingInitialAssociation(any());
  }

  @Test
  void changedTenantDeniesBeforeUpstreamOrTerms() throws Exception {
    Fixture f = new Fixture();
    when(f.membership.tenantUuid()).thenReturn(UUID.randomUUID());
    f.rejects();
    verifyNoInteractions(f.upstream, f.terms);
  }

  @Test
  void comparesCollaboratorPositiveEventBytesWithoutClaimingCodecOrProducerProof()
      throws Exception {
    // Synthetic collaborator event: equality proof only, not a valid owner-event codec fixture.
    Fixture f = new Fixture();
    var authority = f.membership.authoritySnapshot();
    String stream = f.issuer.checkpoint().outboxStreamKey();
    String accountStream = f.accountSource.checkpoint().outboxStreamKey();
    String digest = "sha256:" + "b".repeat(64);
    when(f.issuer.generation()).thenReturn(2L);
    when(f.issuer.sourceVersion()).thenReturn(2L);
    when(f.issuer.checkpoint())
        .thenReturn(new SourceCheckpoint(stream, 1L, Optional.of("event"), Optional.of(digest)));
    when(f.membership.authoritySnapshot())
        .thenReturn(
            new CompositeSnapshot(
                new ScopeState(authority.issuer().scope(), 2L, 2L, null),
                authority.account(),
                authority.tenants(),
                authority.memberships(),
                authority.issuanceFence()));
    when(f.membership.outboxCheckpoints())
        .thenReturn(
            List.of(
                new OutboxCheckpointEntry(stream, "1"),
                new OutboxCheckpointEntry(accountStream, "0")));
    when(f.membership.outboxSourceEvidence())
        .thenReturn(
            List.of(new OutboxSourceEvidence(stream, "1", "event", digest, "{\"fixture\":1}")));
    when(f.source.canonicalIssuerProjection().sourceEvent())
        .thenReturn(Optional.of("{\"fixture\":1}"));
    assertThat(f.read().issuerAccount()).isSameAs(f.source);
    when(f.source.canonicalIssuerProjection().sourceEvent())
        .thenReturn(Optional.of("{\"fixture\":2}"));
    f.rejects();
  }

  @Test
  void changedAssociationHistoryAndExactPartySourceDeny() throws Exception {
    Fixture changedHistory = new Fixture();
    when(changedHistory.parties.readExistingInitialAssociation(changedHistory.tenant))
        .thenReturn(
            new InitialAssociationReadback(
                changedHistory.creator,
                new AssociationReceipt(
                    UUID.randomUUID(),
                    changedHistory.tenant,
                    changedHistory.partyId,
                    UUID.randomUUID(),
                    "retained-history"),
                changedHistory.party));
    changedHistory.rejects();
    Fixture changedParty = new Fixture();
    when(changedParty.currentTerms.sourceEvidence())
        .thenReturn(
            List.of(
                new SourceEvidence(
                    SourceKind.CREATOR_PARTY,
                    changedParty.partyId.toString(),
                    "1",
                    "1",
                    null,
                    null,
                    new byte[] {1})));
    changedParty.rejects();
  }

  private static final class Fixture {
    private final UUID tenant = UUID.randomUUID();
    private final UUID account = UUID.randomUUID();
    private final UUID partyId = UUID.randomUUID();
    private final UUID request = UUID.randomUUID();
    private final UUID bootstrapId = UUID.randomUUID();
    private final DSLContext dsl = mock(DSLContext.class);
    private final Connection connection = mock(Connection.class);
    private final IndividualCreatorPartyRepository parties =
        mock(IndividualCreatorPartyRepository.class);
    private final AccountTenantCreationBootstrapService memberships =
        mock(AccountTenantCreationBootstrapService.class);
    private final AccountHostedTermsService terms = mock(AccountHostedTermsService.class);
    private final AccountAuthoritySourceEvidenceRepository upstream =
        mock(AccountAuthoritySourceEvidenceRepository.class);
    private final CapturedEnvironmentBoundary environment = mock(CapturedEnvironmentBoundary.class);
    private final CreatorControlCaptureSources membership =
        mock(CreatorControlCaptureSources.class);
    private final IssuerAccountSourceSnapshot source = mock(IssuerAccountSourceSnapshot.class);
    private final CurrentSourceEvidence issuer = mock(CurrentSourceEvidence.class);
    private final CurrentSourceEvidence accountSource = mock(CurrentSourceEvidence.class);
    private final EnvironmentBoundCurrentness currentTerms =
        mock(EnvironmentBoundCurrentness.class);
    private final CurrentnessEvidence currentness = mock(CurrentnessEvidence.class);
    private final IndividualCreatorPartySource party =
        new IndividualCreatorPartySource(
            partyId,
            account,
            VerificationStatus.VERIFIED,
            1L,
            "policy",
            1L,
            "verification",
            1L,
            1L);
    private final FreshTenantCreatorEvidence creator;
    private final InitialAssociationReadback association;
    private final AccountDraftSourceCompositionService service;

    private Fixture() throws Exception {
      when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
      doAnswer(
              invocation -> {
                invocation.<ConnectionRunnable>getArgument(0).run(connection);
                return null;
              })
          .when(dsl)
          .connection(any(ConnectionRunnable.class));
      UUID creationRequest = UUID.randomUUID();
      UUID operation = UUID.randomUUID();
      String digest =
          GameTenantCreationDigest.requestDigest("test", creationRequest, "game", "Game", null);
      var creation =
          new FreshTenantCreationEvidence(
              1,
              "test",
              creationRequest,
              operation,
              digest,
              tenant,
              1L,
              "game",
              "NEW_GAME_ROW",
              GameTenantCreationDigest.evidenceDigest(
                  "test", creationRequest, operation, digest, tenant, 1L, "game", "NEW_GAME_ROW"));
      String authDigest = "sha256:" + "a".repeat(64);
      creator =
          new FreshTenantCreatorEvidence(
              1,
              creation,
              account,
              bootstrapId,
              authDigest,
              FreshTenantCreatorDigest.evidenceDigest(
                  1, creation, account, bootstrapId, authDigest));
      association =
          new InitialAssociationReadback(
              creator,
              new AssociationReceipt(request, tenant, partyId, UUID.randomUUID(), "history"),
              party);
      when(parties.lockExistingInitialAssociationScope(tenant))
          .thenReturn(new InitialAssociationScope(tenant, request, account, partyId));
      when(parties.readExistingInitialAssociation(tenant)).thenReturn(association);
      when(memberships.readExistingCreatorControlCaptureSources(account, tenant))
          .thenReturn(membership);
      when(membership.accountUuid()).thenReturn(account);
      when(membership.tenantUuid()).thenReturn(tenant);
      when(membership.creationSource()).thenReturn(creation);
      var bootstrap = mock(StoredOperation.class);
      when(membership.bootstrapReceipt()).thenReturn(bootstrap);
      when(bootstrap.requestId()).thenReturn(bootstrapId);
      when(bootstrap.accountAuthorizationOperationId()).thenReturn(bootstrapId);
      when(bootstrap.accountAuthorizationDigest()).thenReturn(authDigest);
      when(bootstrap.creatorEvidenceDigest()).thenReturn(creator.evidenceDigest());
      when(bootstrap.creatorEvidencePayload()).thenReturn(CreatorPartyEncoding.creation(creator));
      var fence = new IssuanceFence(account, 1L, 1L);
      var issuerScope = AuthorityScope.issuer("firemud-account-service");
      var accountScope = AuthorityScope.account(account);
      when(membership.authoritySnapshot())
          .thenReturn(
              new CompositeSnapshot(
                  new ScopeState(issuerScope, 1L, 1L, null),
                  new ScopeState(accountScope, 1L, 1L, fence),
                  List.of(),
                  List.of(),
                  fence));
      when(membership.issuanceFence()).thenReturn("1");
      when(source.issuanceFence()).thenReturn(fence);
      when(source.issuer()).thenReturn(issuer);
      when(source.account()).thenReturn(accountSource);
      String issuerStream = "account:auth-authority:v1:issuer/firemud-account-service";
      String accountStream = "account:auth-authority:v1:account/" + account;
      configureSource(issuer, issuerScope, null, issuerStream);
      configureSource(accountSource, accountScope, fence, accountStream);
      when(membership.outboxCheckpoints())
          .thenReturn(
              List.of(
                  new OutboxCheckpointEntry(issuerStream, "0"),
                  new OutboxCheckpointEntry(accountStream, "0")));
      when(membership.outboxSourceEvidence()).thenReturn(List.of());
      var issuerProjection = mock(IssuerGenerationProjection.class);
      var accountProjection = mock(AccountGenerationProjection.class);
      when(issuerProjection.sourceEvent()).thenReturn(Optional.empty());
      when(accountProjection.sourceEvent()).thenReturn(Optional.empty());
      when(source.canonicalIssuerProjection()).thenReturn(issuerProjection);
      when(source.canonicalAccountProjection()).thenReturn(accountProjection);
      when(upstream.readCurrentIssuerAccountSources("firemud-account-service", account))
          .thenReturn(source);
      when(terms.requireCurrentnessInOwnerTransaction(environment, partyId))
          .thenReturn(currentTerms);
      when(currentTerms.terms()).thenReturn(currentness);
      when(currentness.accountId()).thenReturn(account);
      when(currentness.creatorPartyId()).thenReturn(partyId);
      when(currentTerms.sourceEvidence())
          .thenReturn(
              List.of(
                  new SourceEvidence(
                      SourceKind.CREATOR_PARTY,
                      partyId.toString(),
                      "1",
                      "1",
                      null,
                      null,
                      CreatorPartyEncoding.party(party))));
      service =
          new AccountDraftSourceCompositionService(dsl, parties, memberships, terms, upstream);
    }

    private void configureSource(
        CurrentSourceEvidence value, AuthorityScope scope, IssuanceFence fence, String stream) {
      when(value.scope()).thenReturn(scope);
      when(value.generation()).thenReturn(1L);
      when(value.sourceVersion()).thenReturn(1L);
      when(value.issuanceFence()).thenReturn(fence);
      when(value.checkpoint())
          .thenReturn(new SourceCheckpoint(stream, 0L, Optional.empty(), Optional.empty()));
    }

    private AccountDraftSourceCompositionService.ExistingSources read() {
      TransactionSynchronizationManager.setActualTransactionActive(true);
      return service.readExistingSources(environment, tenant);
    }

    private void rejects() {
      assertThatThrownBy(this::read).isInstanceOf(IllegalStateException.class);
    }
  }
}
