package unit.net.firedevops.firemud.accountservice.hostedterms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.accountservice.creatorparty.CreatorPartyEncoding;
import net.firedevops.firemud.accountservice.creatorparty.IndividualCreatorPartyRepository;
import net.firedevops.firemud.accountservice.creatorparty.IndividualCreatorPartySource;
import net.firedevops.firemud.accountservice.creatorparty.IndividualCreatorPartySource.VerificationStatus;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService;
import net.firedevops.firemud.accountservice.hostedterms.HostedTermsCatalogVersion;
import net.firedevops.firemud.accountservice.hostedterms.HostedTermsEncoding;
import net.firedevops.firemud.accountservice.hostedterms.HostedTermsEnvironmentBinding;
import net.firedevops.firemud.accountservice.hostedterms.HostedTermsEnvironmentBindingRepository;
import net.firedevops.firemud.accountservice.hostedterms.HostedTermsRepository;
import net.firedevops.firemud.accountservice.hostedterms.IndividualHostedTermsAcceptance;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unit proof uses explicit typed upstream authority fixtures, not caller-supplied authority DTOs.
 */
class AccountHostedTermsServiceTest {
  private static final Instant NOW = Instant.parse("2026-10-08T00:00:00Z");

  @Test
  void environmentCaptureIsServiceIssuedOutsideTheOwnerTransaction() {
    Fixture fixture = new Fixture();
    var boundaries = mock(AccountHostedTermsService.CurrentEnvironmentBoundaryAuthority.class);
    var bindings = mock(HostedTermsEnvironmentBindingRepository.class);
    AccountHostedTermsService service = fixture.environmentService(bindings, boundaries);
    var current = currentBoundary("production");
    when(boundaries.currentBoundary())
        .thenAnswer(
            ignored -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return current;
            });

    var captured = service.captureCurrentEnvironmentBoundary();
    assertThat(captured).isNotNull();
    verify(boundaries).currentBoundary();

    boolean transactionWasActive = TransactionSynchronizationManager.isActualTransactionActive();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertThatThrownBy(service::captureCurrentEnvironmentBoundary)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("outside an Account transaction");
      assertThatThrownBy(() -> service.requireCurrentnessForCurrentEnvironment(fixture.partyId))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("outside an Account transaction");
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(transactionWasActive);
    }
    // Both rejected calls precede an additional external boundary lookup.
    verify(boundaries).currentBoundary();
  }

  @Test
  void synchronizationOnlyContextCannotCaptureTheExternalEnvironment() {
    Fixture fixture = new Fixture();
    var boundaries = mock(AccountHostedTermsService.CurrentEnvironmentBoundaryAuthority.class);
    AccountHostedTermsService service =
        fixture.environmentService(mock(HostedTermsEnvironmentBindingRepository.class), boundaries);
    boolean synchronizationWasActive = TransactionSynchronizationManager.isSynchronizationActive();
    if (!synchronizationWasActive) {
      TransactionSynchronizationManager.initSynchronization();
    }
    try {
      assertThatThrownBy(service::captureCurrentEnvironmentBoundary)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("outside an Account transaction");
    } finally {
      if (!synchronizationWasActive) {
        TransactionSynchronizationManager.clearSynchronization();
      }
    }
    verify(boundaries, never()).currentBoundary();
  }

  @Test
  void capturedEnvironmentReadsTheSameLockedSourcesWithoutCallingTheProviderInsideTransaction() {
    Fixture fixture = new Fixture();
    var boundaries = mock(AccountHostedTermsService.CurrentEnvironmentBoundaryAuthority.class);
    var bindings = mock(HostedTermsEnvironmentBindingRepository.class);
    AccountHostedTermsService service = fixture.environmentService(bindings, boundaries);
    var environment = currentBoundary("production");
    when(boundaries.currentBoundary())
        .thenAnswer(
            ignored -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return environment;
            });
    var captured = service.captureCurrentEnvironmentBoundary();

    IndividualCreatorPartySource party = party(fixture.partyId, fixture.accountId);
    HostedTermsCatalogVersion catalog =
        terms(fixture.scopeId, null, 1, 1, "terms-v1", NOW.minusSeconds(60));
    IndividualHostedTermsAcceptance acceptance =
        acceptance(action(fixture, catalog, true), party, catalog);
    byte[] publicationEvidence = "test-publication-evidence".getBytes();
    HostedTermsEnvironmentBinding binding =
        new HostedTermsEnvironmentBinding(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "production",
            fixture.scopeId,
            catalog.operatorLegalIdentity(),
            catalog.operatorIdentityVersion(),
            catalog.versionId(),
            catalog.sourceVersion(),
            "test-environment-owner",
            "test-publication-event",
            HostedTermsEncoding.digest(publicationEvidence),
            null,
            null,
            1);
    when(bindings.lockHead("production"))
        .thenReturn(
            new HostedTermsEnvironmentBindingRepository.HeadSnapshot("production", binding, null));
    when(fixture.repository.lockPartyAccount(fixture.partyId)).thenReturn(fixture.accountId);
    when(fixture.parties.readIndividualSource(fixture.partyId, fixture.accountId))
        .thenReturn(party);
    when(fixture.repository.lockScope(fixture.scopeId))
        .thenReturn(scope(fixture.scopeId, catalog, NOW, null));
    when(fixture.repository.readCurrentAcceptance(fixture.partyId, fixture.scopeId, catalog))
        .thenReturn(Optional.of(acceptance));

    boolean transactionWasActive = TransactionSynchronizationManager.isActualTransactionActive();
    boolean transactionWasReadOnly =
        TransactionSynchronizationManager.isCurrentTransactionReadOnly();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    try {
      var current = service.requireCurrentnessInOwnerTransaction(captured, fixture.partyId);
      assertThat(current.environment()).isSameAs(environment);
      assertThat(current.binding()).isSameAs(binding);
      assertThat(current.terms().acceptanceEvidenceId()).isEqualTo(acceptance.evidenceId());
      assertThat(current.terms().exactCurrentnessSource())
          .containsExactly(HostedTermsEncoding.currentnessSource(catalog, acceptance, null));
      assertThat(current.sourceEvidence()).hasSize(3);
      assertThat(current.sourceEvidence())
          .anySatisfy(
              source -> {
                assertThat(source.key()).isEqualTo(binding.sourceEvidence().key());
                assertThat(source.canonicalBytes())
                    .containsExactly(binding.sourceEvidence().canonicalBytes());
              })
          .anySatisfy(
              source -> {
                assertThat(source.kind()).isEqualTo(SourceKind.HOSTED_TERMS);
                assertThat(source.scopeId()).isEqualTo(fixture.scopeId.toString());
                assertThat(source.evidence())
                    .containsExactly(
                        HostedTermsEncoding.currentnessSource(catalog, acceptance, null));
              })
          .anySatisfy(
              source -> {
                assertThat(source.kind()).isEqualTo(SourceKind.CREATOR_PARTY);
                assertThat(source.scopeId()).isEqualTo(fixture.partyId.toString());
                assertThat(source.evidence()).containsExactly(CreatorPartyEncoding.party(party));
              });
    } finally {
      TransactionSynchronizationManager.setCurrentTransactionReadOnly(transactionWasReadOnly);
      TransactionSynchronizationManager.setActualTransactionActive(transactionWasActive);
    }
    verify(boundaries).currentBoundary();
    verify(bindings).lockHead("production");
  }

  @Test
  void ownerCurrentnessRejectsMissingForeignAndNonwritableTransactionBeforeSourceRead() {
    Fixture fixture = new Fixture();
    var boundaries = mock(AccountHostedTermsService.CurrentEnvironmentBoundaryAuthority.class);
    var bindings = mock(HostedTermsEnvironmentBindingRepository.class);
    AccountHostedTermsService service = fixture.environmentService(bindings, boundaries);
    AccountHostedTermsService other = fixture.environmentService(bindings, boundaries);
    when(boundaries.currentBoundary()).thenReturn(currentBoundary("production"));
    var captured = service.captureCurrentEnvironmentBoundary();

    assertThatThrownBy(() -> service.requireCurrentnessInOwnerTransaction(null, fixture.partyId))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> other.requireCurrentnessInOwnerTransaction(captured, fixture.partyId))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> service.requireCurrentnessInOwnerTransaction(captured, fixture.partyId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Writable Account owner transaction");

    boolean transactionWasActive = TransactionSynchronizationManager.isActualTransactionActive();
    boolean transactionWasReadOnly =
        TransactionSynchronizationManager.isCurrentTransactionReadOnly();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
    try {
      assertThatThrownBy(
              () -> service.requireCurrentnessInOwnerTransaction(captured, fixture.partyId))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Writable Account owner transaction");
    } finally {
      TransactionSynchronizationManager.setCurrentTransactionReadOnly(transactionWasReadOnly);
      TransactionSynchronizationManager.setActualTransactionActive(transactionWasActive);
    }
    verify(bindings, never()).lockHead(any());
  }

  @Test
  void unavailableEnvironmentObservationCannotProduceACapture() {
    Fixture fixture = new Fixture();
    var boundaries = mock(AccountHostedTermsService.CurrentEnvironmentBoundaryAuthority.class);
    AccountHostedTermsService service =
        fixture.environmentService(mock(HostedTermsEnvironmentBindingRepository.class), boundaries);
    assertThatThrownBy(service::captureCurrentEnvironmentBoundary)
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("authenticated current environment boundary");
  }

  private static HostedTermsEnvironmentBinding.CurrentEnvironmentBoundary currentBoundary(
      String boundary) {
    byte[] evidence =
        ("test-boundary:" + boundary).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    return new HostedTermsEnvironmentBinding.CurrentEnvironmentBoundary(
        boundary,
        "test-environment-owner",
        "test-observation",
        evidence,
        HostedTermsEncoding.digest(evidence));
  }

  @Test
  void affirmativeAcceptancePersistsExactShownCatalogAndReturnsOriginalReceipt() {
    Fixture fixture = new Fixture();
    IndividualCreatorPartySource party = party(fixture.partyId, fixture.accountId);
    HostedTermsCatalogVersion terms =
        terms(fixture.scopeId, null, 1, 1, "terms-v1", NOW.minusSeconds(60));
    AccountHostedTermsService.AcceptanceAction action = action(fixture, terms, true);
    IndividualHostedTermsAcceptance expected = acceptance(action, party, terms);
    fixture.acceptanceAuthority(action);
    when(fixture.parties.readIndividualSource(fixture.partyId, fixture.accountId))
        .thenReturn(party);
    when(fixture.repository.lockScope(fixture.scopeId))
        .thenReturn(scope(fixture.scopeId, terms, NOW, null));
    when(fixture.repository.readCatalog(fixture.scopeId, terms.versionId())).thenReturn(terms);
    when(fixture.repository.readAcceptanceByRequest(action.actionRequestId()))
        .thenReturn(Optional.empty());
    when(fixture.repository.insertAcceptance(
            any(UUID.class), any(), any(), any(), any(byte[].class)))
        .thenReturn(expected);

    assertThat(fixture.service.accept(action.actionRequestId())).isEqualTo(expected);
    verify(fixture.repository)
        .insertAcceptance(any(UUID.class), any(), any(), any(), any(byte[].class));
  }

  @Test
  void nonaffirmativeOrUnverifiedForeignPartyCannotWriteAcceptance() {
    Fixture fixture = new Fixture();
    HostedTermsCatalogVersion terms = terms(fixture.scopeId, null, 1, 1, "terms-v1", NOW);
    AccountHostedTermsService.AcceptanceAction no = action(fixture, terms, false);
    fixture.acceptanceAuthority(no);
    assertThatThrownBy(() -> fixture.service.accept(no.actionRequestId()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Affirmative");
    verify(fixture.repository, never())
        .insertAcceptance(any(UUID.class), any(), any(), any(), any(byte[].class));

    AccountHostedTermsService.AcceptanceAction foreign = action(fixture, terms, true);
    fixture.acceptanceAuthority(foreign);
    when(fixture.parties.readIndividualSource(fixture.partyId, fixture.accountId))
        .thenReturn(party(fixture.partyId, UUID.randomUUID()));
    assertThatThrownBy(() -> fixture.service.accept(foreign.actionRequestId()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("own locally verified");
    verify(fixture.repository, never())
        .insertAcceptance(any(UUID.class), any(), any(), any(), any(byte[].class));
  }

  @Test
  void changedShownDigestOperatorOrVersionIsDenied() {
    Fixture fixture = new Fixture();
    IndividualCreatorPartySource party = party(fixture.partyId, fixture.accountId);
    HostedTermsCatalogVersion stored = terms(fixture.scopeId, null, 1, 1, "terms-v1", NOW);
    HostedTermsCatalogVersion other = terms(fixture.scopeId, null, 1, 1, "other-terms", NOW);
    AccountHostedTermsService.AcceptanceAction action = action(fixture, stored, true);
    fixture.acceptanceAuthority(action);
    when(fixture.parties.readIndividualSource(fixture.partyId, fixture.accountId))
        .thenReturn(party);
    when(fixture.repository.lockScope(fixture.scopeId))
        .thenReturn(scope(fixture.scopeId, stored, NOW, null));
    when(fixture.repository.readCatalog(fixture.scopeId, stored.versionId())).thenReturn(other);

    assertThatThrownBy(() -> fixture.service.accept(action.actionRequestId()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("shown catalog");
    verify(fixture.repository, never())
        .insertAcceptance(any(UUID.class), any(), any(), any(), any(byte[].class));
  }

  @Test
  void changedOperatorIdentityOrVersionCannotBeAcceptedAsTheShownTerms() {
    Fixture fixture = new Fixture();
    IndividualCreatorPartySource party = party(fixture.partyId, fixture.accountId);
    HostedTermsCatalogVersion terms = terms(fixture.scopeId, null, 1, 1, "terms-v1", NOW);
    AccountHostedTermsService.AcceptanceAction base = action(fixture, terms, true);
    AccountHostedTermsService.AcceptanceAction changedName =
        new AccountHostedTermsService.AcceptanceAction(
            base.actionRequestId(),
            true,
            base.accountId(),
            base.creatorPartyId(),
            base.hostedScopeId(),
            base.shownVersionId(),
            base.shownDocumentDigest(),
            "Different Operator",
            base.shownOperatorIdentityVersion(),
            base.authenticatedActionReference(),
            base.authenticatedActionVersion(),
            base.actionCreatedAt());
    fixture.acceptanceAuthority(changedName);
    when(fixture.parties.readIndividualSource(fixture.partyId, fixture.accountId))
        .thenReturn(party);
    when(fixture.repository.lockScope(fixture.scopeId))
        .thenReturn(scope(fixture.scopeId, terms, NOW, null));
    when(fixture.repository.readCatalog(fixture.scopeId, terms.versionId())).thenReturn(terms);
    when(fixture.repository.readAcceptanceByRequest(base.actionRequestId()))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> fixture.service.accept(base.actionRequestId()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("shown catalog");

    AccountHostedTermsService.AcceptanceAction changedVersion =
        new AccountHostedTermsService.AcceptanceAction(
            UUID.randomUUID(),
            true,
            base.accountId(),
            base.creatorPartyId(),
            base.hostedScopeId(),
            base.shownVersionId(),
            base.shownDocumentDigest(),
            base.shownOperatorLegalIdentity(),
            2,
            base.authenticatedActionReference(),
            base.authenticatedActionVersion(),
            base.actionCreatedAt());
    fixture.acceptanceAuthority(changedVersion);
    when(fixture.repository.readAcceptanceByRequest(changedVersion.actionRequestId()))
        .thenReturn(Optional.empty());
    assertThatThrownBy(() -> fixture.service.accept(changedVersion.actionRequestId()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("shown catalog");
  }

  @Test
  void exactRetryRecoversOriginalEvidenceWithoutRewritingOrReplacingIt() {
    Fixture fixture = new Fixture();
    HostedTermsCatalogVersion terms = terms(fixture.scopeId, null, 1, 1, "terms-v1", NOW);
    AccountHostedTermsService.AcceptanceAction action = action(fixture, terms, true);
    IndividualHostedTermsAcceptance receipt =
        acceptance(action, party(fixture.partyId, fixture.accountId), terms);
    fixture.acceptanceAuthority(action);
    when(fixture.repository.readAcceptanceByRequest(action.actionRequestId()))
        .thenReturn(Optional.of(receipt));

    assertThat(fixture.service.accept(action.actionRequestId())).isEqualTo(receipt);
    verify(fixture.parties, never()).readIndividualSource(any(), any());
    verify(fixture.repository, never())
        .insertAcceptance(any(UUID.class), any(), any(), any(), any(byte[].class));
  }

  @Test
  void materialityAdvancesOnlyItsIndependentGenerationAndNonmaterialityPreservesIt() {
    Fixture material = new Fixture();
    HostedTermsCatalogVersion oldMaterial =
        terms(material.scopeId, null, 1, 1, "terms-v1", NOW.minusSeconds(1));
    HostedTermsRepository.PublicationOperation materialOperation =
        operation(
            material.requestId,
            material.scopeId,
            "intent",
            HostedTermsRepository.PublicationStatus.RECEIVED);
    when(material.repository.claimPublication(any(), any(), any())).thenReturn(materialOperation);
    when(material.repository.lockScope(material.scopeId))
        .thenReturn(scope(material.scopeId, oldMaterial, NOW, null));
    when(material.fences.requestSourceChange(any())).thenReturn(true);
    when(material.fences.sourceMutationPermitted(any())).thenReturn(true);
    material.publicationAuthority(
        publication(
            material.scopeId,
            "terms-v2",
            HostedTermsCatalogVersion.Materiality.MATERIAL,
            NOW.minusSeconds(1)));
    AccountHostedTermsService.PublicationResult materialResult =
        material.service.publish(material.requestId);
    assertThat(materialResult.status())
        .isEqualTo(HostedTermsRepository.PublicationStatus.COMMITTED);
    assertThat(materialResult.candidate().sourceVersion()).isEqualTo(2);
    assertThat(materialResult.candidate().materialGeneration()).isEqualTo(2);

    Fixture nonmaterial = new Fixture();
    HostedTermsCatalogVersion oldNonmaterial =
        terms(nonmaterial.scopeId, null, 1, 1, "terms-v1", NOW.minusSeconds(1));
    HostedTermsRepository.PublicationOperation nonmaterialOperation =
        operation(
            nonmaterial.requestId,
            nonmaterial.scopeId,
            "intent",
            HostedTermsRepository.PublicationStatus.RECEIVED);
    when(nonmaterial.repository.claimPublication(any(), any(), any()))
        .thenReturn(nonmaterialOperation);
    when(nonmaterial.repository.lockScope(nonmaterial.scopeId))
        .thenReturn(scope(nonmaterial.scopeId, oldNonmaterial, NOW, null));
    when(nonmaterial.fences.requestSourceChange(any())).thenReturn(true);
    when(nonmaterial.fences.sourceMutationPermitted(any())).thenReturn(true);
    nonmaterial.publicationAuthority(
        publication(
            nonmaterial.scopeId,
            "terms-v2",
            HostedTermsCatalogVersion.Materiality.NONMATERIAL,
            NOW.minusSeconds(1)));
    AccountHostedTermsService.PublicationResult nonmaterialResult =
        nonmaterial.service.publish(nonmaterial.requestId);
    assertThat(nonmaterialResult.candidate().sourceVersion()).isEqualTo(2);
    assertThat(nonmaterialResult.candidate().materialGeneration()).isEqualTo(1);
  }

  @Test
  void unresolvedOwnerSettlementKeepsPublicationPendingWithoutAdvancingCatalogHead() {
    Fixture fixture = new Fixture();
    HostedTermsCatalogVersion current =
        terms(fixture.scopeId, null, 1, 1, "terms-v1", NOW.minusSeconds(1));
    when(fixture.repository.claimPublication(any(), any(), any()))
        .thenReturn(
            operation(
                fixture.requestId,
                fixture.scopeId,
                "intent",
                HostedTermsRepository.PublicationStatus.RECEIVED));
    when(fixture.repository.lockScope(fixture.scopeId))
        .thenReturn(scope(fixture.scopeId, current, NOW, null));
    when(fixture.fences.requestSourceChange(any())).thenReturn(false);
    fixture.publicationAuthority(
        publication(
            fixture.scopeId,
            "terms-v2",
            HostedTermsCatalogVersion.Materiality.MATERIAL,
            NOW.minusSeconds(1)));

    AccountHostedTermsService.PublicationResult result = fixture.service.publish(fixture.requestId);
    assertThat(result.status())
        .isEqualTo(HostedTermsRepository.PublicationStatus.PENDING_OWNER_SETTLEMENT);
    verify(fixture.repository).markOwnerSettlementPending(any(), any(), any(byte[].class));
    verify(fixture.repository, never()).completePublication(any(), any(), any(), any());
  }

  @Test
  void dueDisclosedDeadlineDeniesOldCurrentnessWhileSourceSettlementIsPending() {
    Fixture fixture = new Fixture();
    IndividualCreatorPartySource party = party(fixture.partyId, fixture.accountId);
    HostedTermsCatalogVersion current =
        terms(fixture.scopeId, null, 1, 1, "terms-v1", NOW.minusSeconds(10));
    HostedTermsCatalogVersion next =
        terms(fixture.scopeId, current.versionId(), 2, 2, "terms-v2", NOW.minusSeconds(1));
    HostedTermsRepository.PublicationOperation pending =
        new HostedTermsRepository.PublicationOperation(
            fixture.requestId,
            fixture.scopeId,
            new byte[] {1},
            HostedTermsEncoding.digest(new byte[] {1}),
            HostedTermsRepository.PublicationStatus.PENDING_OWNER_SETTLEMENT,
            next.versionId(),
            new byte[] {2},
            null,
            null,
            null);
    when(fixture.repository.lockPartyAccount(fixture.partyId)).thenReturn(fixture.accountId);
    when(fixture.parties.readIndividualSource(fixture.partyId, fixture.accountId))
        .thenReturn(party);
    when(fixture.repository.lockScope(fixture.scopeId))
        .thenReturn(scope(fixture.scopeId, current, NOW, pending));
    when(fixture.repository.readCandidate(pending)).thenReturn(next);

    assertThatThrownBy(() -> fixture.service.requireCurrentness(fixture.scopeId, fixture.partyId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("effective date has arrived");
  }

  @Test
  void staleGenerationAndChangedOrUnavailableIdentityNeverReturnCurrentness() {
    Fixture fixture = new Fixture();
    IndividualCreatorPartySource acceptedParty = party(fixture.partyId, fixture.accountId);
    IndividualCreatorPartySource changedParty =
        new IndividualCreatorPartySource(
            fixture.partyId,
            fixture.accountId,
            VerificationStatus.VERIFIED,
            2,
            "test-policy-v2",
            2L,
            "test-verification-v2",
            2L,
            1);
    HostedTermsCatalogVersion current =
        terms(fixture.scopeId, UUID.randomUUID(), 2, 2, "terms-v2", NOW);
    IndividualHostedTermsAcceptance stale =
        acceptance(
            action(fixture, terms(fixture.scopeId, null, 1, 1, "terms-v1", NOW), true),
            acceptedParty,
            terms(fixture.scopeId, null, 1, 1, "terms-v1", NOW));
    IndividualHostedTermsAcceptance currentAcceptance =
        acceptance(action(fixture, current, true), acceptedParty, current);
    when(fixture.repository.lockPartyAccount(fixture.partyId)).thenReturn(fixture.accountId);
    when(fixture.parties.readIndividualSource(fixture.partyId, fixture.accountId))
        .thenReturn(acceptedParty, acceptedParty, changedParty)
        .thenThrow(new IllegalStateException("source unavailable"));
    when(fixture.repository.lockScope(fixture.scopeId))
        .thenReturn(scope(fixture.scopeId, current, NOW, null));
    when(fixture.repository.readCurrentAcceptance(fixture.partyId, fixture.scopeId, current))
        .thenReturn(Optional.empty(), Optional.of(stale), Optional.of(currentAcceptance));

    assertThatThrownBy(() -> fixture.service.requireCurrentness(fixture.scopeId, fixture.partyId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("No current affirmative");

    assertThatThrownBy(() -> fixture.service.requireCurrentness(fixture.scopeId, fixture.partyId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("stale or changed");

    assertThatThrownBy(() -> fixture.service.requireCurrentness(fixture.scopeId, fixture.partyId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("stale or changed");
    assertThatThrownBy(() -> fixture.service.requireCurrentness(fixture.scopeId, fixture.partyId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("source unavailable");
  }

  @Test
  void catalogAndAcceptanceEncodingsBindExactBytesWithoutCallerTimeAuthority() {
    Fixture fixture = new Fixture();
    byte[] document = new byte[] {0, 1, (byte) 0xff, 13, 10};
    HostedTermsCatalogVersion version = terms(fixture.scopeId, null, 1, 1, document, NOW);
    byte[] original = version.documentBytes();
    original[0] = 9;
    assertThat(version.documentBytes()).containsExactly(document);
    assertThat(version.documentDigest()).isEqualTo(HostedTermsEncoding.digest(document));
    assertThat(HostedTermsEncoding.catalog(version))
        .isEqualTo(HostedTermsEncoding.catalog(version));
    assertThatThrownBy(
            () ->
                new HostedTermsCatalogVersion(
                    UUID.randomUUID(),
                    fixture.scopeId,
                    null,
                    "Test Operator",
                    1,
                    document,
                    HostedTermsEncoding.digest("different".getBytes()),
                    1,
                    1,
                    HostedTermsCatalogVersion.Materiality.INITIAL,
                    null,
                    null,
                    "test-publication",
                    1,
                    "test-notice",
                    1,
                    NOW))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Document digest");
  }

  private static HostedTermsRepository.ScopeSnapshot scope(
      UUID id,
      HostedTermsCatalogVersion current,
      Instant now,
      HostedTermsRepository.PublicationOperation pending) {
    return new HostedTermsRepository.ScopeSnapshot(
        id, current, OffsetDateTime.ofInstant(now, ZoneOffset.UTC), pending);
  }

  private static HostedTermsRepository.PublicationOperation operation(
      UUID requestId,
      UUID scopeId,
      String request,
      HostedTermsRepository.PublicationStatus status) {
    byte[] payload = request.getBytes();
    return new HostedTermsRepository.PublicationOperation(
        requestId,
        scopeId,
        payload,
        HostedTermsEncoding.digest(payload),
        status,
        null,
        null,
        null,
        null,
        null);
  }

  private static HostedTermsCatalogVersion terms(
      UUID scope,
      UUID predecessor,
      long source,
      long generation,
      String document,
      Instant effectiveAt) {
    return terms(scope, predecessor, source, generation, document.getBytes(), effectiveAt);
  }

  private static HostedTermsCatalogVersion terms(
      UUID scope,
      UUID predecessor,
      long source,
      long generation,
      byte[] document,
      Instant effectiveAt) {
    return new HostedTermsCatalogVersion(
        UUID.randomUUID(),
        scope,
        predecessor,
        "Test Operator",
        1,
        document,
        HostedTermsEncoding.digest(document),
        source,
        generation,
        predecessor == null
            ? HostedTermsCatalogVersion.Materiality.INITIAL
            : generation == 2
                ? HostedTermsCatalogVersion.Materiality.MATERIAL
                : HostedTermsCatalogVersion.Materiality.NONMATERIAL,
        predecessor == null ? null : "test-materiality",
        predecessor == null ? null : 1L,
        "test-publication",
        1,
        "test-notice",
        1,
        effectiveAt);
  }

  private static IndividualCreatorPartySource party(UUID partyId, UUID accountId) {
    return new IndividualCreatorPartySource(
        partyId,
        accountId,
        VerificationStatus.VERIFIED,
        1,
        "test-policy",
        1L,
        "test-verification",
        1L,
        1);
  }

  private static AccountHostedTermsService.AcceptanceAction action(
      Fixture fixture, HostedTermsCatalogVersion terms, boolean affirmative) {
    return new AccountHostedTermsService.AcceptanceAction(
        UUID.randomUUID(),
        affirmative,
        fixture.accountId,
        fixture.partyId,
        fixture.scopeId,
        terms.versionId(),
        terms.documentDigest(),
        terms.operatorLegalIdentity(),
        terms.operatorIdentityVersion(),
        "test-authenticated-action",
        1,
        OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
  }

  private static AccountHostedTermsService.PublicationEvidence publication(
      UUID scope,
      String document,
      HostedTermsCatalogVersion.Materiality materiality,
      Instant effectiveAt) {
    return new AccountHostedTermsService.PublicationEvidence(
        scope,
        "Test Operator",
        1,
        document.getBytes(),
        materiality,
        materiality == HostedTermsCatalogVersion.Materiality.INITIAL ? null : "test-materiality",
        materiality == HostedTermsCatalogVersion.Materiality.INITIAL ? null : 1L,
        "test-publication",
        1,
        "test-notice",
        1,
        effectiveAt);
  }

  private static IndividualHostedTermsAcceptance acceptance(
      AccountHostedTermsService.AcceptanceAction action,
      IndividualCreatorPartySource party,
      HostedTermsCatalogVersion terms) {
    byte[] source = HostedTermsEncoding.individualParty(party);
    byte[] actionBytes = HostedTermsEncoding.affirmativeAction(action);
    return new IndividualHostedTermsAcceptance(
        UUID.randomUUID(),
        action.actionRequestId(),
        party.creatorPartyId(),
        party.accountId(),
        terms.hostedScopeId(),
        terms.versionId(),
        terms.documentDigest(),
        terms.operatorLegalIdentity(),
        terms.operatorIdentityVersion(),
        terms.sourceVersion(),
        terms.materialGeneration(),
        source,
        HostedTermsEncoding.digest(source),
        actionBytes,
        HostedTermsEncoding.digest(actionBytes),
        OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
  }

  private static final class Fixture {
    private final UUID requestId = UUID.randomUUID();
    private final UUID scopeId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();
    private final UUID partyId = UUID.randomUUID();
    private final HostedTermsRepository repository = mock(HostedTermsRepository.class);
    private final IndividualCreatorPartyRepository parties =
        mock(IndividualCreatorPartyRepository.class);
    private final DraftAuthorizationFenceRepository fences =
        mock(DraftAuthorizationFenceRepository.class);
    private final AccountHostedTermsService.OperatorPublicationAuthority publicationAuthority =
        mock(AccountHostedTermsService.OperatorPublicationAuthority.class);
    private final AccountHostedTermsService.IndividualAcceptanceAuthority acceptanceAuthority =
        mock(AccountHostedTermsService.IndividualAcceptanceAuthority.class);
    private final AccountHostedTermsService service =
        new AccountHostedTermsService(
            new NoopTransactionManager(),
            repository,
            parties,
            fences,
            publicationAuthority,
            acceptanceAuthority);

    private void acceptanceAuthority(AccountHostedTermsService.AcceptanceAction action) {
      when(acceptanceAuthority.resolve(action.actionRequestId())).thenReturn(action);
    }

    private void publicationAuthority(AccountHostedTermsService.PublicationEvidence evidence) {
      when(publicationAuthority.resolve(requestId)).thenReturn(evidence);
    }

    private AccountHostedTermsService environmentService(
        HostedTermsEnvironmentBindingRepository bindings,
        AccountHostedTermsService.CurrentEnvironmentBoundaryAuthority boundaries) {
      return new AccountHostedTermsService(
          new NoopTransactionManager(),
          repository,
          parties,
          fences,
          publicationAuthority,
          acceptanceAuthority,
          bindings,
          mock(AccountHostedTermsService.EnvironmentBindingPublicationAuthority.class),
          boundaries);
    }
  }

  private static final class NoopTransactionManager implements PlatformTransactionManager {
    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {}

    @Override
    public void rollback(TransactionStatus status) {}
  }
}
