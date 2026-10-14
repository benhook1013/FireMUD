package unit.net.firedevops.firemud.accountservice.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.InitialGameplayLoginDisposalResult;
import net.firedevops.firemud.accountservice.dto.InitialGameplayLoginDisposalResult.Disposition;
import net.firedevops.firemud.accountservice.dto.InitialGameplayLoginDisposalResult.ProofKind;
import org.junit.jupiter.api.Test;

class InitialGameplayLoginDisposalResultTest {
  @Test
  void acceptsOnlyExactNonAuthorizingDispositionProofPairs() {
    UUID request = UUID.randomUUID();
    UUID context = UUID.randomUUID();
    UUID operation = UUID.randomUUID();
    for (Disposition disposition : Disposition.values()) {
      for (ProofKind kind : ProofKind.values()) {
        boolean valid =
            (disposition == Disposition.PENDING && kind == ProofKind.NONE)
                || (disposition == Disposition.RETIRED && kind == ProofKind.PHYSICAL_TOKEN_DENIAL)
                || (disposition == Disposition.NO_TOKEN && kind == ProofKind.NO_ISSUANCE_BINDING);
        if (valid) {
          assertThat(
                  new InitialGameplayLoginDisposalResult(
                          request, context, operation, disposition, kind)
                      .proofKind())
              .isEqualTo(kind);
        } else {
          assertThatThrownBy(
                  () ->
                      new InitialGameplayLoginDisposalResult(
                          request, context, operation, disposition, kind))
              .isInstanceOf(IllegalArgumentException.class);
        }
      }
    }
    assertThatThrownBy(
            () ->
                new InitialGameplayLoginDisposalResult(
                    request, context, operation, Disposition.NO_TOKEN))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
