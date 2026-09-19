package net.firedevops.firemud.common.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class PublicationDigestRequestBindingTest {
  @Test
  void fullVectorUsesFixedFieldsAndExactCanonicalGrammar() {
    PublicationDigestRequestBinding binding =
        PublicationDigestRequestBinding.full("tenant-alpha", "42", "request-7");

    assertThat(binding.scopeKind())
        .isEqualTo(PublicationDigestRequestBinding.ScopeKind.FULL_VERSION);
    assertThat(binding.scopeKindValue()).isEqualTo("FULL_VERSION");
    assertThat(binding.tenantId()).isEqualTo("tenant-alpha");
    assertThat(binding.versionId()).isEqualTo("42");
    assertThat(binding.baseVersionId()).isEmpty();
    assertThat(binding.scriptPatchVersion()).isEmpty();
    assertThat(binding.publishRequestId()).isEqualTo("request-7");
    assertThat(binding.derivedWorkflowIdentity())
        .isEqualTo("publish:tenant-alpha:publish-request:request-7");
    assertThat(binding.canonicalPreimageHex())
        .isEqualTo(
            "32373a7075626c69636174696f6e446967657374526571756573742f7631383a74656e616e74496431323a74656e616e742d616c706861393a73636f70654b696e6431323a46554c4c5f56455253494f4e393a76657273696f6e4964323a343231333a6261736556657273696f6e4964303a31383a736372697074506174636856657273696f6e303a31363a7075626c697368526571756573744964393a726571756573742d3732333a64657269766564576f726b666c6f774964656e7469747934363a7075626c6973683a74656e616e742d616c7068613a7075626c6973682d726571756573743a726571756573742d37");
    assertThat(binding.requestDigest())
        .isEqualTo("1e1b26b00b2340b9002ca6241b85b8eb7a2160922c4c9a3483f65701374f1a2a");
    binding.validateSupplied(binding.derivedWorkflowIdentity(), binding.requestDigest());
  }

  @Test
  void patchVectorPreservesNonAsciiAndDelimiterBearingPatchValue() {
    PublicationDigestRequestBinding binding =
        PublicationDigestRequestBinding.patch("ténant", "7", "patch:é:1", "req-é");

    assertThat(binding.scopeKind())
        .isEqualTo(PublicationDigestRequestBinding.ScopeKind.SCRIPT_PATCH);
    assertThat(binding.versionId()).isEmpty();
    assertThat(binding.baseVersionId()).isEqualTo("7");
    assertThat(binding.scriptPatchVersion()).isEqualTo("patch:é:1");
    assertThat(binding.derivedWorkflowIdentity())
        .isEqualTo("publish-script-patch:ténant:publish-request:req-é");
    assertThat(binding.requestDigest())
        .isEqualTo("718889dbc0c6891443d725a0443ad030d7fc18f1eb8028d65e47ea21bea2e0ed");
    binding.validateSupplied(binding.derivedWorkflowIdentity(), binding.requestDigest());
  }

  @Test
  void changedOrOmittedBindingEvidenceFailsClosed() {
    PublicationDigestRequestBinding binding =
        PublicationDigestRequestBinding.full("tenant", "1", "request");

    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                binding.validateSupplied(
                    "publish:tenant:publish-request:changed", binding.requestDigest()));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> binding.validateSupplied(binding.derivedWorkflowIdentity(), null));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                binding.validateSupplied(
                    binding.derivedWorkflowIdentity(), binding.requestDigest().toUpperCase()));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                binding.validateSupplied(
                    binding.derivedWorkflowIdentity(), binding.requestDigest().substring(1)));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> PublicationDigestRequestBinding.full("tenant", "", "request"));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> PublicationDigestRequestBinding.patch("tenant", "1", "", "request"));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> PublicationDigestRequestBinding.full("tenant", "1", null));
  }

  @Test
  void malformedScopeVersionAndIdentifiersAreRejected() {
    PublicationDigestRequestBinding.validatePublicationIdentity("tenant", "request");
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                PublicationDigestRequestBinding.validatePublicationIdentity(
                    "tenant", "request:id"));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                PublicationDigestRequestBinding.validatePublicationIdentity(" tenant", "request"));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> PublicationDigestRequestBinding.full("tenant", "0", "request"));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> PublicationDigestRequestBinding.full("tenant", "01", "request"));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> PublicationDigestRequestBinding.full("tenant", "+1", "request"));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> PublicationDigestRequestBinding.full("tenant", "1e3", "request"));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> PublicationDigestRequestBinding.full("tenant", "9223372036854775808", "request"));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> PublicationDigestRequestBinding.patch("tenant", "1", "patch", "request:id"));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> PublicationDigestRequestBinding.full("tenant:id", "1", "request"));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> PublicationDigestRequestBinding.full("tenant", "1", "request\uD800"));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> PublicationDigestRequestBinding.full(" tenant", "1", "request"));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> PublicationDigestRequestBinding.full("tenant", "1", "request "));
  }

  @Test
  void decomposedUnicodeIsRejectedBeforeWorkflowIdentityCanAlias() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> PublicationDigestRequestBinding.full("cafe\u0301", "1", "request"));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> PublicationDigestRequestBinding.patch("tenant", "1", "patche\u0301", "request"));
  }
}
