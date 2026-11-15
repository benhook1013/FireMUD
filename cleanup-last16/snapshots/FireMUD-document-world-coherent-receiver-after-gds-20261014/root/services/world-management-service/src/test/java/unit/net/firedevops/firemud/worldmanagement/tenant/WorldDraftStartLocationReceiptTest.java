package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import net.firedevops.firemud.common.world.RoomTemplateRef;
import net.firedevops.firemud.common.world.WorldDraftStartLocationEvidence;
import org.junit.jupiter.api.Test;

class WorldDraftStartLocationReceiptTest {
  @Test
  void normalConstructionRetainsConstructorValuesAndSharedCanonicalBytes() {
    var evidence =
        WorldDraftStartLocationEvidence.create(
            "test",
            uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
            uuid("44444444-4444-4444-8444-444444444444"),
            uuid("55555555-5555-4555-8555-555555555555"),
            uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
            "sha256:" + "0".repeat(64),
            "sha256:" + "1".repeat(64),
            new RoomTemplateRef(
                uuid("11111111-1111-4111-8111-111111111111"),
                uuid("22222222-2222-4222-8222-222222222222"),
                uuid("77777777-7777-4777-8777-777777777777")),
            "sha256:" + "2".repeat(64));

    var receipt =
        new WorldDraftStartLocationReceipt(
            evidence.targetNamespace(),
            evidence.operationId(),
            evidence.requestId(),
            evidence.commitId(),
            evidence.authorizationFenceId(),
            evidence.accountBindingDigest(),
            evidence.bindingDigest(),
            evidence.startLocation(),
            evidence.graphDigest(),
            evidence.receiptDigest());

    assertThat(receipt.canonicalBytes()).containsExactly(evidence.canonicalBytes());
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
