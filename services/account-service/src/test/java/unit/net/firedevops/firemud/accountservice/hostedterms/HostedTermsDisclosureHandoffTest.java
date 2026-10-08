package unit.net.firedevops.firemud.accountservice.hostedterms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.accountservice.hostedterms.HostedTermsDisclosureHandoff;
import net.firedevops.firemud.accountservice.hostedterms.HostedTermsDisclosureHandoff.DisclosureResult;
import net.firedevops.firemud.accountservice.hostedterms.HostedTermsDisclosureHandoff.Kind;
import net.firedevops.firemud.accountservice.hostedterms.HostedTermsDisclosureHandoff.Outcome;
import net.firedevops.firemud.accountservice.hostedterms.HostedTermsEncoding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import org.junit.jupiter.api.Test;

class HostedTermsDisclosureHandoffTest {
  @Test
  void canonicalBindingSortsCompleteSourceVectorAndCopiesEveryArray() {
    byte[] hostedEvidence = new byte[] {1, 2, 3};
    byte[] tenantEvidence = new byte[] {4, 5, 6};
    SourceEvidence hosted =
        new SourceEvidence(
            SourceKind.HOSTED_TERMS,
            UUID.randomUUID().toString(),
            null,
            "7",
            null,
            null,
            hostedEvidence);
    SourceEvidence tenant =
        new SourceEvidence(
            SourceKind.TENANT, UUID.randomUUID().toString(), "3", "8", null, null, tenantEvidence);
    hostedEvidence[0] = 99;
    tenantEvidence[0] = 99;
    byte[] authorityBytes = "test-only-authority-evidence".getBytes(StandardCharsets.UTF_8);
    HostedTermsDisclosureHandoff handoff = handoff(hosted, tenant, authorityBytes);
    authorityBytes[0] = 99;

    assertThat(handoff.sources().stream().map(SourceEvidence::key).toList())
        .containsExactly(hosted.key(), tenant.key());
    assertThat(handoff.sources().getFirst().evidence()).containsExactly(1, 2, 3);
    assertThat(handoff.authenticatedAuthorityEvidence())
        .containsExactly("test-only-authority-evidence".getBytes(StandardCharsets.UTF_8));

    byte[] returned = handoff.authenticatedAuthorityEvidence();
    returned[0] = 88;
    SourceEvidence returnedSource = handoff.sources().getFirst();
    byte[] returnedSourceBytes = returnedSource.evidence();
    returnedSourceBytes[0] = 77;
    assertThat(handoff.authenticatedAuthorityEvidence())
        .containsExactly("test-only-authority-evidence".getBytes(StandardCharsets.UTF_8));
    assertThat(handoff.sources().getFirst().evidence()).containsExactly(1, 2, 3);
    assertThat(HostedTermsDisclosureHandoff.fromStored(handoff.canonicalBytes()).canonicalBytes())
        .containsExactly(handoff.canonicalBytes());
  }

  @Test
  void exactDigestBindsEveryDisclosureFieldAndRejectsChangedOrMalformedInputs() {
    SourceEvidence source = hostedSource("source-v7");
    HostedTermsDisclosureHandoff original =
        new HostedTermsDisclosureHandoff(
            UUID.randomUUID(),
            UUID.randomUUID(),
            Kind.CATALOG,
            source.key(),
            HostedTermsEncoding.digest(new byte[] {1}),
            HostedTermsEncoding.digest(new byte[] {2}),
            Instant.parse("2026-10-08T00:00:00.123456Z"),
            List.of(source),
            "test-only-authority-evidence".getBytes(StandardCharsets.UTF_8));

    String originalDigest = HostedTermsEncoding.digest(original.canonicalBytes());
    assertThat(originalDigest)
        .isNotEqualTo(HostedTermsEncoding.digest(changed(original, "candidate").canonicalBytes()))
        .isNotEqualTo(HostedTermsEncoding.digest(changed(original, "effectiveAt").canonicalBytes()))
        .isNotEqualTo(HostedTermsEncoding.digest(changed(original, "requestId").canonicalBytes()))
        .isNotEqualTo(
            HostedTermsEncoding.digest(changed(original, "authorityEvidence").canonicalBytes()));
    assertThatThrownBy(
            () ->
                new HostedTermsDisclosureHandoff(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    Kind.CATALOG,
                    source.key(),
                    original.predecessorDigest(),
                    original.candidateDigest(),
                    Instant.parse("2026-10-08T00:00:00.123456789Z"),
                    List.of(source),
                    new byte[] {1}))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("microsecond");
    assertThatThrownBy(
            () ->
                new HostedTermsDisclosureHandoff(
                    original.handoffId(),
                    original.requestId(),
                    original.kind(),
                    original.sourceKey(),
                    original.predecessorDigest(),
                    original.candidateDigest(),
                    original.effectiveAt(),
                    List.of(source, source),
                    new byte[] {1}))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Distinct complete sorted source evidence");
    byte[] trailing =
        java.util.Arrays.copyOf(original.canonicalBytes(), original.canonicalBytes().length + 1);
    assertThatThrownBy(() -> HostedTermsDisclosureHandoff.fromStored(trailing))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void disclosureResultPreservesBindingDigestAndCopiesAuthenticatedBytes() {
    SourceEvidence source = hostedSource("source-result");
    HostedTermsDisclosureHandoff handoff = handoff(source, null, new byte[] {9});
    byte[] evidence = "test-only-authenticated-owner-readback".getBytes(StandardCharsets.UTF_8);
    DisclosureResult result =
        new DisclosureResult(
            handoff.handoffId(),
            handoff.requestId(),
            HostedTermsEncoding.digest(handoff.canonicalBytes()),
            Outcome.DISCLOSED,
            evidence);
    evidence[0] = 0;

    assertThat(result.authenticatedEvidence())
        .containsExactly("test-only-authenticated-owner-readback".getBytes(StandardCharsets.UTF_8));
    assertThat(DisclosureResult.fromStored(result.canonicalBytes()).canonicalBytes())
        .containsExactly(result.canonicalBytes());
    String changedBindingDigest = HostedTermsEncoding.digest(new byte[] {4});
    DisclosureResult changedBinding =
        new DisclosureResult(
            handoff.handoffId(),
            handoff.requestId(),
            changedBindingDigest,
            Outcome.DISCLOSED,
            new byte[] {1});
    assertThat(changedBinding.bindingDigest()).isEqualTo(changedBindingDigest);
    assertThat(changedBinding.canonicalBytes()).isNotEqualTo(result.canonicalBytes());
  }

  @Test
  void environmentBindingUsesItsIndependentNamespacedHostedTermsSource() {
    SourceEvidence boundary =
        new SourceEvidence(
            SourceKind.HOSTED_TERMS,
            "environment-boundary/production",
            null,
            "9",
            null,
            null,
            new byte[] {6, 7});
    HostedTermsDisclosureHandoff handoff =
        new HostedTermsDisclosureHandoff(
            UUID.randomUUID(),
            UUID.randomUUID(),
            Kind.ENVIRONMENT_BINDING,
            boundary.key(),
            HostedTermsEncoding.digest(new byte[] {4}),
            HostedTermsEncoding.digest(new byte[] {5}),
            Instant.parse("2026-10-08T00:00:00Z"),
            List.of(boundary),
            "test-only-authority-evidence".getBytes(StandardCharsets.UTF_8));
    assertThat(handoff.sourceKey()).isEqualTo("HOSTED_TERMS:environment-boundary/production");
    assertThat(HostedTermsDisclosureHandoff.fromStored(handoff.canonicalBytes()).kind())
        .isEqualTo(Kind.ENVIRONMENT_BINDING);
  }

  private static HostedTermsDisclosureHandoff handoff(
      SourceEvidence primary, SourceEvidence additional, byte[] authorityEvidence) {
    return new HostedTermsDisclosureHandoff(
        UUID.randomUUID(),
        UUID.randomUUID(),
        Kind.CATALOG,
        primary.key(),
        HostedTermsEncoding.digest(new byte[] {1}),
        HostedTermsEncoding.digest(new byte[] {2}),
        Instant.parse("2026-10-08T00:00:00.123456Z"),
        additional == null ? List.of(primary) : List.of(additional, primary),
        authorityEvidence);
  }

  private static SourceEvidence hostedSource(String scope) {
    return new SourceEvidence(
        SourceKind.HOSTED_TERMS,
        UUID.nameUUIDFromBytes(scope.getBytes(StandardCharsets.UTF_8)).toString(),
        null,
        "7",
        null,
        null,
        new byte[] {3, 4, 5});
  }

  private static HostedTermsDisclosureHandoff changed(
      HostedTermsDisclosureHandoff original, String field) {
    return new HostedTermsDisclosureHandoff(
        original.handoffId(),
        field.equals("requestId") ? UUID.randomUUID() : original.requestId(),
        original.kind(),
        original.sourceKey(),
        original.predecessorDigest(),
        field.equals("candidate")
            ? HostedTermsEncoding.digest(new byte[] {3})
            : original.candidateDigest(),
        field.equals("effectiveAt")
            ? original.effectiveAt().plusSeconds(1)
            : original.effectiveAt(),
        original.sources(),
        field.equals("authorityEvidence")
            ? new byte[] {42}
            : original.authenticatedAuthorityEvidence());
  }
}
