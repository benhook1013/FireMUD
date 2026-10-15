package net.firedevops.firemud.common.account.sourceintake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeWorldClosureAuthorizationReadEvidence.Request;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import org.junit.jupiter.api.Test;

/** Synthetic lookup evidence only; Account authentication establishes the HELD result. */
class SelectedOwnerIntakeWorldClosureAuthorizationReadEvidenceTest {
  private static final UUID READ = UUID.fromString("88888888-8888-4888-8888-888888888888");

  @Test
  void derivesWorldReaderAndClosedPurposeFromBothOriginalOwnerBindings() {
    for (Owner owner : List.of(Owner.ENTITY_MANAGEMENT, Owner.AUTOMATION_SCRIPTING)) {
      var binding = SelectedOwnerIntakeAuthorizationReadEvidenceTest.binding(owner);
      var request = Request.create("test", binding);

      assertThat(request.schemaVersion()).isEqualTo(1);
      assertThat(request.targetNamespace()).isEqualTo("test");
      assertThat(request.readRequestId())
          .isNotIn(binding.operationId(), binding.fenceId(), binding.intakeRequestId());
      assertThat(request.intendedReader())
          .isEqualTo("spiffe://firemud/ns/test/sa/world-management-service");
      assertThat(request.closureReadPurpose())
          .isEqualTo(
              owner == Owner.ENTITY_MANAGEMENT
                  ? "ENTITY_INTAKE_WORLD_CLOSURE_READ"
                  : "AUTOMATION_INTAKE_WORLD_CLOSURE_READ");
      assertThat(request.binding()).isSameAs(binding);
    }
  }

  @Test
  void rejectsInvalidNamespaceCorrelationVersionAndBindingNamespace() {
    var binding = SelectedOwnerIntakeAuthorizationReadEvidenceTest.binding(Owner.ENTITY_MANAGEMENT);
    assertThatThrownBy(() -> new Request(1, "not/a/namespace", READ, binding))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Request(2, "test", READ, binding))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Request(1, "other", READ, binding))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Request(1, "test", binding.operationId(), binding))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Request(1, "test", binding.fenceId(), binding))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Request(1, "test", binding.intakeRequestId(), binding))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Request(1, "test", new UUID(0, 0), binding))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Request(1, "test", READ, null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
