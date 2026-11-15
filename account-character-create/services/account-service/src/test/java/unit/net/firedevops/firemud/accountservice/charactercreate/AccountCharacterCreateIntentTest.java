package net.firedevops.firemud.accountservice.charactercreate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountCharacterCreateIntentTest {
  private final AccountCharacterCreateIntent original =
      AccountCharacterCreateTestActions.intent(
          "character-create-proof",
          UUID.randomUUID(),
          UUID.randomUUID(),
          Instant.parse("2099-01-01T00:00:00Z"));

  @Test
  void retainsExactOpaqueScopeWithoutParsingTrimmingOrChangingCase() {
    String scope = " Issued.Scope_Mixed-case-é-e\u0301-\uD83D\uDE80 ";
    var retained = withScope(scope);
    assertThat(retained.connectScopeId()).isEqualTo(scope);
    assertThat(retained.mutationDigest()).isEqualTo(original.mutationDigest());
    assertThat(retained.sameBinding(withScope(scope))).isTrue();
    assertThat(retained.sameBinding(withScope(scope.strip()))).isFalse();
    assertThat(retained.sameBinding(withScope(scope.replace("Mixed", "mixed")))).isFalse();
    assertThat(withScope("s".repeat(2048)).connectScopeId()).hasSize(2048);
  }

  @Test
  void rejectsAbsentBlankOversizedAndUnrepresentableScopes() {
    assertThatThrownBy(() -> withScope(null)).isInstanceOf(IllegalArgumentException.class);
    for (String scope :
        List.of("", " \t\n", "s".repeat(2049), "a" + (char) 0 + "b", "\uD800", "\uDC00")) {
      assertThatThrownBy(() -> withScope(scope)).isInstanceOf(IllegalArgumentException.class);
    }
  }

  private AccountCharacterCreateIntent withScope(String scope) {
    return new AccountCharacterCreateIntent(
        original.operationId(),
        original.createCharacterRequestId(),
        scope,
        original.accountId(),
        original.actionBytes(),
        original.mutationDigest(),
        original.originalSourceReadback());
  }
}
