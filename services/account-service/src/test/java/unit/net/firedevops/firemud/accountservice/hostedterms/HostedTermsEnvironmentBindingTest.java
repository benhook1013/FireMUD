package unit.net.firedevops.firemud.accountservice.hostedterms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import net.firedevops.firemud.accountservice.hostedterms.HostedTermsCatalogVersion;
import net.firedevops.firemud.accountservice.hostedterms.HostedTermsEncoding;
import net.firedevops.firemud.accountservice.hostedterms.HostedTermsEnvironmentBinding;
import net.firedevops.firemud.accountservice.hostedterms.HostedTermsEnvironmentBinding.PublicationEvidence;
import net.firedevops.firemud.accountservice.hostedterms.HostedTermsEnvironmentBindingEncoding;
import net.firedevops.firemud.accountservice.hostedterms.IndividualHostedTermsAcceptance;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import org.junit.jupiter.api.Test;

class HostedTermsEnvironmentBindingTest {
  @Test
  void canonicalPublicationBindsBoundaryScopeOperatorCatalogPublisherEventAndPredecessor() {
    UUID requestId = UUID.randomUUID();
    UUID scopeId = UUID.randomUUID();
    UUID catalogVersionId = UUID.randomUUID();
    UUID priorBinding = UUID.randomUUID();
    PublicationEvidence evidence =
        new PublicationEvidence(
            "official/production-cluster-a",
            scopeId,
            "Test Operator Legal Name",
            4,
            catalogVersionId,
            9,
            "environment-secrets-owner@production",
            "deployment-ref:event-37:traffic-open-12",
            priorBinding,
            2L);

    byte[] original = HostedTermsEnvironmentBindingEncoding.publication(requestId, evidence);
    assertThat(HostedTermsEnvironmentBindingEncoding.publication(requestId, evidence))
        .containsExactly(original);
    assertThat(HostedTermsEnvironmentBindingEncoding.publicationDigest(requestId, evidence))
        .isEqualTo(HostedTermsEncoding.digest(original));

    PublicationEvidence changed =
        new PublicationEvidence(
            evidence.environmentBoundary(),
            evidence.hostedScopeId(),
            evidence.operatorLegalIdentity(),
            evidence.operatorIdentityVersion(),
            evidence.catalogVersionId(),
            evidence.catalogSourceVersion(),
            evidence.authenticatedPublisherIdentity(),
            evidence.publicationEventIdentity(),
            UUID.randomUUID(),
            evidence.predecessorSourceVersion());
    assertThat(HostedTermsEnvironmentBindingEncoding.publication(requestId, changed))
        .isNotEqualTo(original);
    assertThat(HostedTermsEnvironmentBindingEncoding.publication(UUID.randomUUID(), evidence))
        .isNotEqualTo(original);
  }

  @Test
  void independentBindingCounterAndExactReceiptUseNamespacedHostedTermsSourceKey() {
    UUID requestId = UUID.randomUUID();
    PublicationEvidence evidence =
        new PublicationEvidence(
            "production",
            UUID.randomUUID(),
            "Test Operator",
            1,
            UUID.randomUUID(),
            7,
            "test-only-environment-owner",
            "test-only-publication-event-17",
            UUID.randomUUID(),
            8L);
    byte[] publication = HostedTermsEnvironmentBindingEncoding.publication(requestId, evidence);
    HostedTermsEnvironmentBinding binding =
        new HostedTermsEnvironmentBinding(
            UUID.randomUUID(),
            requestId,
            evidence.environmentBoundary(),
            evidence.hostedScopeId(),
            evidence.operatorLegalIdentity(),
            evidence.operatorIdentityVersion(),
            evidence.catalogVersionId(),
            evidence.catalogSourceVersion(),
            evidence.authenticatedPublisherIdentity(),
            evidence.publicationEventIdentity(),
            HostedTermsEncoding.digest(publication),
            evidence.predecessorBindingId(),
            evidence.predecessorSourceVersion(),
            9);

    var source = binding.sourceEvidence();
    assertThat(source.kind()).isEqualTo(SourceKind.HOSTED_TERMS);
    assertThat(source.scopeId()).isEqualTo("environment-boundary/production");
    assertThat(source.key()).isEqualTo("HOSTED_TERMS:environment-boundary/production");
    assertThat(source.scopeId()).isNotEqualTo(binding.hostedScopeId().toString());
    assertThat(source.generation()).isNull();
    assertThat(source.sourceVersion()).isEqualTo("9");
    assertThat(source.evidence())
        .containsExactly(HostedTermsEnvironmentBindingEncoding.receipt(binding));
    assertThat(HostedTermsEnvironmentBindingEncoding.receiptDigest(binding))
        .isEqualTo(HostedTermsEncoding.digest(source.evidence()));
    assertThat(binding.catalogSourceVersion()).isEqualTo(7);
    assertThat(binding.sourceVersion()).isEqualTo(9);
  }

  @Test
  void initialBindingCannotInventPredecessorAndUpdatesAdvanceExactlyOnce() {
    UUID requestId = UUID.randomUUID();
    UUID priorBinding = UUID.randomUUID();
    UUID scopeId = UUID.randomUUID();
    UUID catalogId = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                new PublicationEvidence(
                    "production",
                    scopeId,
                    "Test Operator",
                    1,
                    catalogId,
                    1,
                    "test-owner",
                    "event-1",
                    null,
                    0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("present together");

    assertThatThrownBy(
            () ->
                new HostedTermsEnvironmentBinding(
                    UUID.randomUUID(),
                    requestId,
                    "production",
                    scopeId,
                    "Test Operator",
                    1,
                    catalogId,
                    1,
                    "test-owner",
                    "event-2",
                    HostedTermsEncoding.digest(new byte[] {1}),
                    priorBinding,
                    4L,
                    7))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact predecessor");
  }

  @Test
  void currentEnvironmentEvidenceRequiresExactOwnerDigest() {
    byte[] evidence = new byte[] {1, 2, 3};
    var current =
        new HostedTermsEnvironmentBinding.CurrentEnvironmentBoundary(
            "staging",
            "test-only-environment-owner",
            "test-only-current-boundary-read-1",
            evidence,
            HostedTermsEncoding.digest(evidence));
    byte[] returned = current.exactOwnerEvidence();
    returned[0] = 9;
    assertThat(current.exactOwnerEvidence()).containsExactly(evidence);

    assertThatThrownBy(
            () ->
                new HostedTermsEnvironmentBinding.CurrentEnvironmentBoundary(
                    "staging",
                    "test-only-environment-owner",
                    "test-only-current-boundary-read-2",
                    evidence,
                    HostedTermsEncoding.digest(new byte[] {8})))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("digest conflicts");
  }

  @Test
  void currentnessSourceRetainsExactScheduledCatalogAndDisclosedDeadline() {
    Instant currentAt = Instant.parse("2026-10-08T00:00:00Z");
    byte[] currentDocument = "current terms".getBytes(StandardCharsets.UTF_8);
    HostedTermsCatalogVersion current =
        new HostedTermsCatalogVersion(
            UUID.randomUUID(),
            UUID.randomUUID(),
            null,
            "Test Operator",
            1,
            currentDocument,
            HostedTermsEncoding.digest(currentDocument),
            1,
            1,
            HostedTermsCatalogVersion.Materiality.INITIAL,
            null,
            null,
            "current-publication",
            1,
            "current-notice",
            1,
            currentAt);
    byte[] partySource = new byte[] {11, 12};
    byte[] affirmativeAction = new byte[] {21, 22};
    IndividualHostedTermsAcceptance acceptance =
        new IndividualHostedTermsAcceptance(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            current.hostedScopeId(),
            current.versionId(),
            current.documentDigest(),
            current.operatorLegalIdentity(),
            current.operatorIdentityVersion(),
            current.sourceVersion(),
            current.materialGeneration(),
            partySource,
            HostedTermsEncoding.digest(partySource),
            affirmativeAction,
            HostedTermsEncoding.digest(affirmativeAction),
            OffsetDateTime.ofInstant(currentAt, ZoneOffset.UTC));

    Instant disclosedAt = Instant.parse("2026-10-09T00:00:00Z");
    byte[] nextDocument = "scheduled terms".getBytes(StandardCharsets.UTF_8);
    HostedTermsCatalogVersion deadline =
        new HostedTermsCatalogVersion(
            UUID.randomUUID(),
            current.hostedScopeId(),
            current.versionId(),
            current.operatorLegalIdentity(),
            current.operatorIdentityVersion(),
            nextDocument,
            HostedTermsEncoding.digest(nextDocument),
            2,
            1,
            HostedTermsCatalogVersion.Materiality.NONMATERIAL,
            "audited-nonmaterial-classification",
            1L,
            "scheduled-publication",
            2,
            "scheduled-notice",
            2,
            disclosedAt);

    byte[] exactDeadline = HostedTermsEncoding.catalog(deadline);
    byte[] source = HostedTermsEncoding.currentnessSource(current, acceptance, deadline);
    String sourceJson = new String(source, StandardCharsets.UTF_8);
    assertThat(sourceJson).contains("account-hosted-terms-currentness-source/v2");
    assertThat(sourceJson)
        .contains(Base64.getEncoder().encodeToString(exactDeadline))
        .contains(HostedTermsEncoding.digest(exactDeadline));
    assertThat(
            new String(
                HostedTermsEncoding.currentnessSource(current, acceptance), StandardCharsets.UTF_8))
        .contains("\"disclosedDeadlineCatalog\":null")
        .contains("\"disclosedDeadlineCatalogDigest\":null");

    HostedTermsCatalogVersion changedDeadline =
        new HostedTermsCatalogVersion(
            deadline.versionId(),
            deadline.hostedScopeId(),
            deadline.predecessorVersionId(),
            deadline.operatorLegalIdentity(),
            deadline.operatorIdentityVersion(),
            deadline.documentBytes(),
            deadline.documentDigest(),
            deadline.sourceVersion(),
            deadline.materialGeneration(),
            deadline.materiality(),
            deadline.materialityEvidenceReference(),
            deadline.materialityEvidenceVersion(),
            deadline.publicationEvidenceReference(),
            deadline.publicationEvidenceVersion(),
            deadline.noticeEvidenceReference(),
            deadline.noticeEvidenceVersion(),
            disclosedAt.plusSeconds(1));
    assertThat(HostedTermsEncoding.currentnessSource(current, acceptance, changedDeadline))
        .isNotEqualTo(source);

    HostedTermsCatalogVersion changedOperator =
        new HostedTermsCatalogVersion(
            deadline.versionId(),
            deadline.hostedScopeId(),
            deadline.predecessorVersionId(),
            "Different Operator",
            deadline.operatorIdentityVersion(),
            deadline.documentBytes(),
            deadline.documentDigest(),
            deadline.sourceVersion(),
            deadline.materialGeneration(),
            deadline.materiality(),
            deadline.materialityEvidenceReference(),
            deadline.materialityEvidenceVersion(),
            deadline.publicationEvidenceReference(),
            deadline.publicationEvidenceVersion(),
            deadline.noticeEvidenceReference(),
            deadline.noticeEvidenceVersion(),
            deadline.effectiveAt());
    assertThatThrownBy(
            () -> HostedTermsEncoding.currentnessSource(current, acceptance, changedOperator))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact immutable next catalog");
  }
}
