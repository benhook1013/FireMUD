package net.firedevops.firemud.common.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FreshTenantCreatorDigestTest {
  private static final UUID ACCOUNT_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID AUTHORIZATION_OPERATION_ID =
      UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final String AUTHORIZATION_DIGEST = "sha256:" + "a".repeat(64);

  @Test
  void usesTheDeclaredLengthFramedUtf8TupleIncludingTheMultibyteSourceKey() throws Exception {
    FreshTenantCreationEvidence creation = creationEvidence(42L, "legacy-α");
    List<String> segments =
        List.of(
            "game-design-tenant-creator-qualification/v1",
            "1",
            "1",
            "prod",
            creation.creationRequestId().toString(),
            creation.operationId().toString(),
            creation.requestDigest(),
            creation.canonicalTenantId().toString(),
            "42",
            "legacy-α",
            "NEW_GAME_ROW",
            creation.evidenceDigest(),
            ACCOUNT_ID.toString(),
            AUTHORIZATION_OPERATION_ID.toString(),
            AUTHORIZATION_DIGEST);

    assertThat("legacy-α".getBytes(StandardCharsets.UTF_8)).hasSize(9);
    assertThat(
            FreshTenantCreatorDigest.evidenceDigest(
                1, creation, ACCOUNT_ID, AUTHORIZATION_OPERATION_ID, AUTHORIZATION_DIGEST))
        .isEqualTo(lengthPrefixedSha256(segments));
  }

  @Test
  void bindsEveryCreationAndAccountAuthorizationIdentityComponent() {
    FreshTenantCreationEvidence creation = creationEvidence(42L, "legacy-α");
    String expected =
        FreshTenantCreatorDigest.evidenceDigest(
            1, creation, ACCOUNT_ID, AUTHORIZATION_OPERATION_ID, AUTHORIZATION_DIGEST);

    assertThat(
            FreshTenantCreatorDigest.evidenceDigest(
                1,
                creation,
                UUID.fromString("66666666-6666-4666-8666-666666666666"),
                AUTHORIZATION_OPERATION_ID,
                AUTHORIZATION_DIGEST))
        .isNotEqualTo(expected);
    assertThat(
            FreshTenantCreatorDigest.evidenceDigest(
                1,
                creation,
                ACCOUNT_ID,
                UUID.fromString("77777777-7777-4777-8777-777777777777"),
                AUTHORIZATION_DIGEST))
        .isNotEqualTo(expected);
    assertThat(
            FreshTenantCreatorDigest.evidenceDigest(
                1, creation, ACCOUNT_ID, AUTHORIZATION_OPERATION_ID, "sha256:" + "b".repeat(64)))
        .isNotEqualTo(expected);
    assertThat(
            FreshTenantCreatorDigest.evidenceDigest(
                1,
                creationEvidence(43L, "legacy-α"),
                ACCOUNT_ID,
                AUTHORIZATION_OPERATION_ID,
                AUTHORIZATION_DIGEST))
        .isNotEqualTo(expected);
    assertThat(
            FreshTenantCreatorDigest.evidenceDigest(
                1,
                creationEvidence(42L, "legacy-beta"),
                ACCOUNT_ID,
                AUTHORIZATION_OPERATION_ID,
                AUTHORIZATION_DIGEST))
        .isNotEqualTo(expected);
  }

  @Test
  void rejectsUnsupportedVersionNilIdentityAndMalformedDigest() {
    FreshTenantCreationEvidence creation = creationEvidence(42L, "legacy-α");

    assertThatThrownBy(
            () ->
                FreshTenantCreatorDigest.evidenceDigest(
                    2, creation, ACCOUNT_ID, AUTHORIZATION_OPERATION_ID, AUTHORIZATION_DIGEST))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                FreshTenantCreatorDigest.evidenceDigest(
                    1,
                    creation,
                    new UUID(0L, 0L),
                    AUTHORIZATION_OPERATION_ID,
                    AUTHORIZATION_DIGEST))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                FreshTenantCreatorDigest.evidenceDigest(
                    1, creation, ACCOUNT_ID, new UUID(0L, 0L), AUTHORIZATION_DIGEST))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                FreshTenantCreatorDigest.evidenceDigest(
                    1, creation, ACCOUNT_ID, AUTHORIZATION_OPERATION_ID, "not-a-digest"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static FreshTenantCreationEvidence creationEvidence(long rowId, String sourceKey) {
    UUID requestId = UUID.fromString("11111111-1111-4111-8111-111111111111");
    UUID operationId = UUID.fromString("22222222-2222-4222-8222-222222222222");
    UUID tenantId = UUID.fromString("33333333-3333-4333-8333-333333333333");
    String requestDigest = "sha256:" + "c".repeat(64);
    return new FreshTenantCreationEvidence(
        1,
        "prod",
        requestId,
        operationId,
        requestDigest,
        tenantId,
        rowId,
        sourceKey,
        "NEW_GAME_ROW",
        GameTenantCreationDigest.evidenceDigest(
            "prod",
            requestId,
            operationId,
            requestDigest,
            tenantId,
            rowId,
            sourceKey,
            "NEW_GAME_ROW"));
  }

  private static String lengthPrefixedSha256(List<String> segments) throws Exception {
    ByteArrayOutputStream preimage = new ByteArrayOutputStream();
    for (String segment : segments) {
      byte[] bytes = segment.getBytes(StandardCharsets.UTF_8);
      preimage.write(Integer.toString(bytes.length).getBytes(StandardCharsets.UTF_8));
      preimage.write(':');
      preimage.write(bytes);
    }
    return "sha256:"
        + HexFormat.of()
            .formatHex(MessageDigest.getInstance("SHA-256").digest(preimage.toByteArray()));
  }
}
