package unit.net.firedevops.firemud.accountservice.tenantcreation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.accountservice.tenantcreation.TenantCreationAuthorizationFenceBinding;
import net.firedevops.firemud.accountservice.tenantcreation.TenantCreationAuthorizationFenceBinding.Outcome;
import net.firedevops.firemud.accountservice.tenantcreation.TenantCreationAuthorizationFenceBinding.Owner;
import net.firedevops.firemud.accountservice.tenantcreation.TenantCreationAuthorizationFenceBinding.OwnerReadback;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import org.junit.jupiter.api.Test;

/** Synthetic correlation fixtures do not authenticate actors, current sources or owner results. */
class TenantCreationAuthorizationFenceBindingTest {
  @Test
  void closedVersionlessFamilyPinsBothOwnersAndOriginalProvenance() {
    var binding = binding();
    String framed = new String(binding.canonicalBytes(), StandardCharsets.UTF_8);
    assertThat(framed)
        .contains("CREATE_TENANT", "GAME_DESIGN", "ACCOUNT")
        .doesNotContain("WORLD", "DRAFT", "versionId");
    byte[] original = binding.canonicalBytes();
    assertThat(TenantCreationAuthorizationFenceBinding.fromStored(original).canonicalBytes())
        .containsExactly(original);
    binding.originalAuthorizationCapture()[0] = 99;
    binding.sources().getFirst().evidence()[0] = 98;
    assertThat(binding.canonicalBytes()).containsExactly(original);
    assertThatThrownBy(() -> binding.sources().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(
            () ->
                TenantCreationAuthorizationFenceBinding.fromStored(
                    java.util.Arrays.copyOf(original, original.length - 1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                TenantCreationAuthorizationFenceBinding.fromStored(
                    java.util.Arrays.copyOf(original, original.length + 1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void ownerOperationAndFullBindingAreExactEvenWhenResultIsUnchanged() {
    var binding = binding();
    new OwnerReadback(
            Owner.GAME_DESIGN,
            Outcome.COMMITTED,
            binding.creationOperationId(),
            binding.canonicalBytes(),
            new byte[] {1})
        .requireBinding(binding);
    new OwnerReadback(
            Owner.ACCOUNT,
            Outcome.DEFINITIVELY_ABORTED,
            binding.operationId(),
            binding.canonicalBytes(),
            new byte[] {2})
        .requireBinding(binding);
    assertThatThrownBy(
            () ->
                new OwnerReadback(
                        Owner.ACCOUNT,
                        Outcome.COMMITTED,
                        binding.creationOperationId(),
                        binding.canonicalBytes(),
                        new byte[] {1})
                    .requireBinding(binding))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new OwnerReadback(
                        Owner.GAME_DESIGN,
                        Outcome.COMMITTED,
                        binding.creationOperationId(),
                        new byte[] {1},
                        new byte[] {1})
                    .requireBinding(binding))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void requestDigestAndSourcesCannotBeOmittedOrAliased() {
    var b = binding();
    assertThatThrownBy(
            () ->
                new TenantCreationAuthorizationFenceBinding(
                    b.operationId(),
                    b.requestId(),
                    b.fenceId(),
                    b.actorAccountId(),
                    b.tenantId(),
                    b.creationOperationId(),
                    b.targetNamespace(),
                    b.sourceGameTenantKey(),
                    "changed",
                    b.description(),
                    b.creationRequestDigest(),
                    b.originalAuthorizationCapture(),
                    b.sources()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new TenantCreationAuthorizationFenceBinding(
                    b.operationId(),
                    b.requestId(),
                    b.fenceId(),
                    b.actorAccountId(),
                    b.tenantId(),
                    b.creationOperationId(),
                    b.targetNamespace(),
                    b.sourceGameTenantKey(),
                    b.name(),
                    b.description(),
                    b.creationRequestDigest(),
                    b.originalAuthorizationCapture(),
                    List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private TenantCreationAuthorizationFenceBinding binding() {
    UUID actor = UUID.randomUUID();
    UUID request = UUID.randomUUID();
    var source =
        new SourceEvidence(
            SourceKind.ACCOUNT,
            actor.toString(),
            "922337203685477580812345",
            "922337203685477580912345",
            "fixture-stream",
            "0",
            new byte[] {3});
    return new TenantCreationAuthorizationFenceBinding(
        UUID.randomUUID(),
        request,
        UUID.randomUUID(),
        actor,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "firemud",
        "key",
        "Name",
        null,
        GameTenantCreationDigest.requestDigest("firemud", request, "key", "Name", null),
        new byte[] {4},
        List.of(source));
  }
}
