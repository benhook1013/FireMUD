package net.firedevops.firemud.common.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DraftBaseReferenceTest {
  @Test
  void closedVersionedGrammarDistinguishesReceiptFromCommit() {
    String id = "abcde123-1234-5678-abcd-123456789abc";
    assertThat(DraftBaseReference.SCHEMA).isEqualTo("game-design-draft-base-reference/v1");
    var genesis = DraftBaseReference.parse("genesis:" + id);
    assertThat(genesis.kind()).isEqualTo(DraftBaseReference.Kind.GENESIS);
    assertThat(genesis.identity()).isEqualTo(UUID.fromString(id));
    assertThat(genesis.canonicalValue()).isEqualTo("genesis:" + id);
    var authored = DraftBaseReference.parse(id);
    assertThat(authored.kind()).isEqualTo(DraftBaseReference.Kind.AUTHORED_COMMIT);
    assertThat(authored.canonicalValue()).isEqualTo(id);
    assertThat(genesis).isNotEqualTo(authored);
  }

  @Test
  void rejectsEveryNoncanonicalOrUnknownForm() {
    String id = "abcde123-1234-5678-abcd-123456789abc";
    for (String value :
        List.of(
            "",
            " ",
            "genesis:",
            "GENESIS:" + id,
            "commit:" + id,
            id.toUpperCase(java.util.Locale.ROOT),
            "genesis:" + id.toUpperCase(java.util.Locale.ROOT),
            " " + id,
            id + "\n",
            "genesis:" + id + ":extra",
            "1-1-1-1-1",
            "00000000-0000-0000-0000-000000000000",
            "genesis:00000000-0000-0000-0000-000000000000")) {
      assertThatThrownBy(() -> DraftBaseReference.parse(value))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
