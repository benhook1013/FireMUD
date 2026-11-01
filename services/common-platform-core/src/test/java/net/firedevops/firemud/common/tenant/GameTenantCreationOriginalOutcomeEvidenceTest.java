package net.firedevops.firemud.common.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.tenant.GameTenantCreationOriginalOutcomeEvidence.Binding;
import net.firedevops.firemud.common.tenant.GameTenantCreationOriginalOutcomeEvidence.Outcome;
import org.junit.jupiter.api.Test;

class GameTenantCreationOriginalOutcomeEvidenceTest {
  private static final String NAMESPACE = "fresh-test";
  private static final UUID REQUEST_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID ACCOUNT_OPERATION_ID =
      UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID ACCOUNT_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID CREATION_OPERATION_ID =
      UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID TENANT_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final String CREATION_DIGEST = "sha256:" + "a".repeat(64);
  private static final String ACCOUNT_DIGEST = "sha256:" + "b".repeat(64);

  @Test
  void canonicalBytesRoundTripBothClosedOutcomesAndBindCompleteReceipt() {
    for (GameTenantCreationOriginalOutcomeEvidence original :
        new GameTenantCreationOriginalOutcomeEvidence[] {created(), notCreated()}) {
      byte[] canonical = original.canonicalBytes();
      assertThat(GameTenantCreationOriginalOutcomeEvidence.fromStored(canonical))
          .isEqualTo(original);
      assertThat(original.evidenceDigest()).startsWith("sha256:").hasSize(71);
      assertThat(GameTenantCreationOriginalOutcomeEvidence.fromStored(canonical).evidenceDigest())
          .isEqualTo(original.evidenceDigest());
    }

    GameTenantCreationOriginalOutcomeEvidence created = created();
    GameTenantCreationOriginalOutcomeEvidence changedAccount =
        new GameTenantCreationOriginalOutcomeEvidence(
            1,
            new Binding(
                NAMESPACE,
                REQUEST_ID,
                ACCOUNT_OPERATION_ID,
                UUID.fromString("66666666-6666-4666-8666-666666666666"),
                CREATION_DIGEST,
                ACCOUNT_DIGEST),
            Outcome.CREATED,
            created.receipt());
    assertThat(changedAccount.canonicalBytes()).isNotEqualTo(created.canonicalBytes());
    assertThat(changedAccount.evidenceDigest()).isNotEqualTo(created.evidenceDigest());
  }

  @Test
  void rejectsOpenShapesAndReceiptSubstitution() {
    assertThatThrownBy(
            () ->
                new GameTenantCreationOriginalOutcomeEvidence(
                    1, binding(), Outcome.CREATED, Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("requires its exact V30 receipt");
    assertThatThrownBy(
            () ->
                new GameTenantCreationOriginalOutcomeEvidence(
                    1, binding(), Outcome.NOT_CREATED, Optional.of(receipt())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot contain a creation receipt");
    assertThatThrownBy(
            () ->
                new GameTenantCreationOriginalOutcomeEvidence(
                    1,
                    binding(),
                    Outcome.CREATED,
                    Optional.of(
                        new FreshTenantCreationEvidence(
                            1,
                            NAMESPACE,
                            REQUEST_ID,
                            CREATION_OPERATION_ID,
                            "sha256:" + "c".repeat(64),
                            TENANT_ID,
                            91L,
                            "fresh-tenant-01",
                            "NEW_GAME_ROW",
                            GameTenantCreationDigest.evidenceDigest(
                                NAMESPACE,
                                REQUEST_ID,
                                CREATION_OPERATION_ID,
                                "sha256:" + "c".repeat(64),
                                TENANT_ID,
                                91L,
                                "fresh-tenant-01",
                                "NEW_GAME_ROW")))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not match the original request binding");
    assertThatThrownBy(
            () ->
                new GameTenantCreationOriginalOutcomeEvidence(
                    2, binding(), Outcome.NOT_CREATED, Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("schema");
  }

  @Test
  void storedDecoderRejectsTruncationTrailingBytesUnknownSchemaAndOversize() {
    byte[] canonical = notCreated().canonicalBytes();
    assertThatThrownBy(
            () ->
                GameTenantCreationOriginalOutcomeEvidence.fromStored(
                    java.util.Arrays.copyOf(canonical, canonical.length - 1)))
        .isInstanceOf(IllegalArgumentException.class);

    byte[] trailing = java.util.Arrays.copyOf(canonical, canonical.length + 1);
    trailing[trailing.length - 1] = 1;
    assertThatThrownBy(() -> GameTenantCreationOriginalOutcomeEvidence.fromStored(trailing))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("trailing");

    byte[] unsupportedSchema = canonical.clone();
    int domainFrameLength =
        StandardCharsets.UTF_8.encode("game-tenant-creation-original-outcome/v1").remaining();
    ByteBuffer.wrap(unsupportedSchema).putInt(Integer.BYTES + domainFrameLength, 2);
    assertThatThrownBy(
            () -> GameTenantCreationOriginalOutcomeEvidence.fromStored(unsupportedSchema))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("schema");

    byte[] pending = replaceFrame(canonical, "NOT_CREATED", "PENDING");
    assertThatThrownBy(() -> GameTenantCreationOriginalOutcomeEvidence.fromStored(pending))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not terminal");

    byte[] invalidReceiptFlag = canonical.clone();
    invalidReceiptFlag[invalidReceiptFlag.length - 1] = 2;
    assertThatThrownBy(
            () -> GameTenantCreationOriginalOutcomeEvidence.fromStored(invalidReceiptFlag))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("receipt flag");

    assertThatThrownBy(() -> GameTenantCreationOriginalOutcomeEvidence.fromStored(new byte[8193]))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("byte bounds");
  }

  @Test
  void bindingRequiresCanonicalNamespaceNonnilUuidsAndLowercaseDigests() {
    assertThatThrownBy(
            () ->
                new Binding(
                    "Fresh-Test",
                    REQUEST_ID,
                    ACCOUNT_OPERATION_ID,
                    ACCOUNT_ID,
                    CREATION_DIGEST,
                    ACCOUNT_DIGEST))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new Binding(
                    NAMESPACE,
                    new UUID(0L, 0L),
                    ACCOUNT_OPERATION_ID,
                    ACCOUNT_ID,
                    CREATION_DIGEST,
                    ACCOUNT_DIGEST))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new Binding(
                    NAMESPACE,
                    REQUEST_ID,
                    ACCOUNT_OPERATION_ID,
                    ACCOUNT_ID,
                    "SHA256:" + "a".repeat(64),
                    ACCOUNT_DIGEST))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static GameTenantCreationOriginalOutcomeEvidence created() {
    return new GameTenantCreationOriginalOutcomeEvidence(
        1, binding(), Outcome.CREATED, Optional.of(receipt()));
  }

  private static GameTenantCreationOriginalOutcomeEvidence notCreated() {
    return new GameTenantCreationOriginalOutcomeEvidence(
        1, binding(), Outcome.NOT_CREATED, Optional.empty());
  }

  private static Binding binding() {
    return new Binding(
        NAMESPACE, REQUEST_ID, ACCOUNT_OPERATION_ID, ACCOUNT_ID, CREATION_DIGEST, ACCOUNT_DIGEST);
  }

  private static FreshTenantCreationEvidence receipt() {
    return new FreshTenantCreationEvidence(
        1,
        NAMESPACE,
        REQUEST_ID,
        CREATION_OPERATION_ID,
        CREATION_DIGEST,
        TENANT_ID,
        91L,
        "fresh-tenant-01",
        "NEW_GAME_ROW",
        GameTenantCreationDigest.evidenceDigest(
            NAMESPACE,
            REQUEST_ID,
            CREATION_OPERATION_ID,
            CREATION_DIGEST,
            TENANT_ID,
            91L,
            "fresh-tenant-01",
            "NEW_GAME_ROW"));
  }

  private static byte[] replaceFrame(byte[] canonical, String original, String replacement) {
    byte[] originalBytes = original.getBytes(StandardCharsets.UTF_8);
    byte[] replacementBytes = replacement.getBytes(StandardCharsets.UTF_8);
    int markerOffset = -1;
    for (int offset = 0; offset <= canonical.length - originalBytes.length; offset++) {
      boolean match = true;
      for (int index = 0; index < originalBytes.length; index++) {
        if (canonical[offset + index] != originalBytes[index]) {
          match = false;
          break;
        }
      }
      if (match) {
        markerOffset = offset;
        break;
      }
    }
    if (markerOffset < Integer.BYTES) {
      throw new IllegalArgumentException("Expected framed outcome is missing");
    }
    int frameOffset = markerOffset - Integer.BYTES;
    byte[] result = new byte[canonical.length - originalBytes.length + replacementBytes.length];
    System.arraycopy(canonical, 0, result, 0, frameOffset);
    ByteBuffer.wrap(result).putInt(frameOffset, replacementBytes.length);
    System.arraycopy(
        replacementBytes, 0, result, frameOffset + Integer.BYTES, replacementBytes.length);
    System.arraycopy(
        canonical,
        markerOffset + originalBytes.length,
        result,
        frameOffset + Integer.BYTES + replacementBytes.length,
        canonical.length - markerOffset - originalBytes.length);
    return result;
  }
}
