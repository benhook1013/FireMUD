package net.firedevops.firemud.gamedesign.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import org.junit.jupiter.api.Test;

/** Original-byte wrapper proof with stipulated source values; no owner production or SQL proof. */
class GameDesignPublicationOperationHistoryTest {
  @Test
  void retainedV1KeepsOriginalBytesAndReadSelectorsWithoutCompleteInventoryAuthority()
      throws Exception {
    var fresh = fresh();
    var original = new ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(original, "game-design-publication-operation/v1");
    DraftAuthorizationFenceBinding.frame(original, fresh.account().canonicalBytes());
    DraftAuthorizationFenceBinding.frame(original, fresh.world().canonicalBytes());
    byte[] bytes = original.toByteArray();
    var historical = GameDesignPublicationOperation.fromStored(bytes);

    assertThat(historical.canonicalBytes()).containsExactly(bytes);
    assertThat(historical.account().canonicalBytes())
        .containsExactly(fresh.account().canonicalBytes());
    assertThat(historical.world().canonicalBytes()).containsExactly(fresh.world().canonicalBytes());
    assertThat(historical.workflowId()).isEqualTo(fresh.workflowId());
    assertThat(historical.tenantKey()).isEqualTo(fresh.tenantKey());
    assertThat(historical.versionId()).isEqualTo(fresh.versionId());
    assertThat(historical.selectionDigest()).isEqualTo(fresh.selectionDigest());
    assertThat(historical).isEqualTo(GameDesignPublicationOperation.fromStored(bytes));
    assertThat(historical.hashCode())
        .isEqualTo(GameDesignPublicationOperation.fromStored(bytes).hashCode());
    assertThat(historical).isNotEqualTo(fresh);
    assertThatThrownBy(historical::inventory)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("HISTORICAL_PUBLICATION_INVENTORY_UNAVAILABLE");
    bytes[0] ^= 1;
    assertThat(historical.canonicalBytes()).containsExactly(original.toByteArray());
  }

  @Test
  void freshV2RoundtripRequiresActualCompleteTypedInventory() throws Exception {
    var fresh = fresh();
    var read = GameDesignPublicationOperation.fromStored(fresh.canonicalBytes());
    assertThat(read.canonicalBytes()).containsExactly(fresh.canonicalBytes());
    assertThat(read.inventory().canonicalBytes())
        .containsExactly(fresh.inventory().canonicalBytes());
    assertThat(read).isEqualTo(fresh);
    assertThatThrownBy(
            () -> new GameDesignPublicationOperation(fresh.account(), fresh.world(), null))
        .isInstanceOf(NullPointerException.class);
  }

  private static GameDesignPublicationOperation fresh() throws Exception {
    return IsolatedPublicationOperationFixtures.fresh(
        new TargetProof(
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
            19L,
            "tenant-key",
            42L,
            "tenant-key",
            "NEW_GAME_ROW"));
  }
}
