package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import org.junit.jupiter.api.Test;

class GameSessionAuthoredWorldIntakeDigestTest {
  private static final UUID REQUEST = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID OPERATION = UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final String SOURCE_GAME_TENANT_KEY = "source-22222222";
  private static final String SOURCE_PROVENANCE_KIND = "NEW_GAME_ROW";

  @Test
  void emitsFixedVectorsBindingTheCompleteMultibyteOwnerReceipt() {
    var source = source("Café 🐉");
    String request = GameSessionAuthoredWorldIntakeDigest.requestDigest(REQUEST, source);
    assertThat(source.evidenceDigest())
        .isEqualTo("sha256:ea835c1f4fbae6206a7f870f509bbdc6b767415608f7be56a44dcd061f1116f7");
    assertThat(request)
        .isEqualTo("sha256:7b9ee59edffc076f301c1a47e73e45417dfdff99f2916e6043faa68bbb5aca28");
    assertThat(
            GameSessionAuthoredWorldIntakeDigest.receiptDigest(
                OPERATION, request, source.evidenceDigest()))
        .isEqualTo("sha256:4d1afd818098152d268f33d0ba7467bd1c0963700fa05ec170b1181100de93e9");
  }

  @Test
  void changedRequestSourceOrOperationCannotReplayTheSameLocalReceipt() {
    var source = source("Café 🐉");
    var changedSource = source("Café 🐲");
    String request = GameSessionAuthoredWorldIntakeDigest.requestDigest(REQUEST, source);
    String receipt =
        GameSessionAuthoredWorldIntakeDigest.receiptDigest(
            OPERATION, request, source.evidenceDigest());
    assertThat(GameSessionAuthoredWorldIntakeDigest.requestDigest(OPERATION, source))
        .isNotEqualTo(request);
    assertThat(GameSessionAuthoredWorldIntakeDigest.requestDigest(REQUEST, changedSource))
        .isNotEqualTo(request);
    assertThat(
            GameSessionAuthoredWorldIntakeDigest.receiptDigest(
                REQUEST, request, source.evidenceDigest()))
        .isNotEqualTo(receipt);
    assertThat(
            GameSessionAuthoredWorldIntakeDigest.receiptDigest(
                OPERATION,
                GameSessionAuthoredWorldIntakeDigest.requestDigest(OPERATION, source),
                source.evidenceDigest()))
        .isNotEqualTo(receipt);
    assertThat(
            GameSessionAuthoredWorldIntakeDigest.receiptDigest(
                OPERATION, request, changedSource.evidenceDigest()))
        .isNotEqualTo(receipt);
  }

  @Test
  void rejectsMissingNilOrMalformedOperationAndDigestInputs() {
    assertThatThrownBy(() -> GameSessionAuthoredWorldIntakeDigest.requestDigest(null, source("x")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> GameSessionAuthoredWorldIntakeDigest.requestDigest(new UUID(0, 0), source("x")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> GameSessionAuthoredWorldIntakeDigest.requestDigest(REQUEST, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () ->
                GameSessionAuthoredWorldIntakeDigest.receiptDigest(
                    new UUID(0, 0), source("x").requestDigest(), source("x").evidenceDigest()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> GameSessionAuthoredWorldIntakeDigest.receiptDigest(OPERATION, null, "sha256:x"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                GameSessionAuthoredWorldIntakeDigest.receiptDigest(
                    OPERATION, source("x").requestDigest(), "SHA256:" + "a".repeat(64)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static AuthoredWorldSourceEvidence source(String displayName) {
    var registration = UUID.fromString("11111111-1111-4111-8111-111111111111");
    var tenant = UUID.fromString("22222222-2222-4222-8222-222222222222");
    var operation = UUID.fromString("33333333-3333-4333-8333-333333333333");
    String request =
        AuthoredWorldSourceDigest.requestDigest(
            "firemud", registration, tenant, "north-star", "violet-wilds", displayName);
    String evidence =
        AuthoredWorldSourceDigest.evidenceDigest(
            "firemud",
            registration,
            operation,
            request,
            tenant,
            "north-star",
            "violet-wilds",
            displayName,
            42L,
            SOURCE_GAME_TENANT_KEY,
            SOURCE_PROVENANCE_KIND);
    return new AuthoredWorldSourceEvidence(
        1,
        "firemud",
        registration,
        operation,
        request,
        tenant,
        "north-star",
        "violet-wilds",
        displayName,
        42L,
        SOURCE_GAME_TENANT_KEY,
        SOURCE_PROVENANCE_KIND,
        evidence);
  }
}
