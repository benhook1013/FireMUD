package net.firedevops.firemud.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import net.firedevops.firemud.account.v1.GetCurrentAccountPublicJwksRequest;
import net.firedevops.firemud.account.v1.GetCurrentAccountPublicJwksResponse;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.PublicJwksSnapshot;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.SourceIdentity;
import org.junit.jupiter.api.Test;

class AccountPublicJwksReadCodecTest {
  private static final SourceIdentity PIN =
      new SourceIdentity(
          "prod",
          "cluster-a",
          "11111111-1111-4111-8111-111111111111",
          "firemud-prod",
          "22222222-2222-4222-8222-222222222222",
          "33333333-3333-4333-8333-333333333333",
          "api-7",
          "https://kubernetes.example:6443",
          "a".repeat(64));
  private static final UnknownFieldSet UNKNOWN =
      UnknownFieldSet.newBuilder()
          .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
          .build();

  @Test
  void repeatsActualNineFieldIdentityAndExactBytesWithDefensiveCopies() {
    byte[] bytes = "public snapshot bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    var response = AccountPublicJwksReadCodec.response(new PublicJwksSnapshot(PIN, bytes));
    bytes[0] = 0;
    var parsed = AccountPublicJwksReadCodec.snapshot(response);
    assertThat(parsed.sourceIdentity()).isEqualTo(PIN);
    assertThat(parsed.jwksBytes()).isEqualTo(response.getPublicJwks().toByteArray());
    byte[] copy = parsed.jwksBytes();
    copy[0] = 0;
    assertThat(parsed.jwksBytes()).isNotEqualTo(copy);
    AccountPublicJwksReadCodec.requireRequest(AccountPublicJwksReadCodec.request());
  }

  @Test
  void refusesUnversionedWrongVersionAndUnknownRequestSelectors() {
    for (var request :
        new GetCurrentAccountPublicJwksRequest[] {
          null,
          GetCurrentAccountPublicJwksRequest.getDefaultInstance(),
          AccountPublicJwksReadCodec.request().toBuilder().setSchemaVersion(2).build(),
          AccountPublicJwksReadCodec.request().toBuilder().setUnknownFields(UNKNOWN).build()
        }) {
      assertThatThrownBy(() -> AccountPublicJwksReadCodec.requireRequest(request))
          .isInstanceOf(AccountPublicJwksReadCodec.InvalidFrameException.class);
    }
  }

  @Test
  void refusesUnknownNestedFieldsMissingIdentityOrBytesAndInvalidIdentity() {
    var valid = AccountPublicJwksReadCodec.response(new PublicJwksSnapshot(PIN, new byte[] {1}));
    for (var response :
        new GetCurrentAccountPublicJwksResponse[] {
          null,
          GetCurrentAccountPublicJwksResponse.getDefaultInstance(),
          valid.toBuilder().setSchemaVersion(2).build(),
          valid.toBuilder().setUnknownFields(UNKNOWN).build(),
          valid.toBuilder()
              .setSourceIdentity(valid.getSourceIdentity().toBuilder().setUnknownFields(UNKNOWN))
              .build(),
          valid.toBuilder().clearSourceIdentity().build(),
          valid.toBuilder().clearPublicJwks().build(),
          valid.toBuilder()
              .setSourceIdentity(
                  valid.getSourceIdentity().toBuilder().setConfigMapUid("caller-label"))
              .build(),
          valid.toBuilder()
              .setPublicJwks(
                  ByteString.copyFrom(new byte[AccountPublicJwksCache.MAX_JWKS_BYTES + 1]))
              .build(),
          valid.toBuilder()
              .setSourceIdentity(
                  valid.getSourceIdentity().toBuilder()
                      .setBindingRevision(
                          "x".repeat(AccountPublicJwksReadCodec.MAX_RESPONSE_BYTES)))
              .build()
        }) {
      assertThatThrownBy(() -> AccountPublicJwksReadCodec.snapshot(response))
          .isInstanceOf(AccountPublicJwksReadCodec.InvalidFrameException.class);
    }
  }

  @Test
  void producerRefusesMissingEmptyOrOversizedPublicMaterial() {
    for (byte[] bytes :
        new byte[][] {null, new byte[0], new byte[AccountPublicJwksCache.MAX_JWKS_BYTES + 1]}) {
      assertThatThrownBy(
              () -> AccountPublicJwksReadCodec.response(new PublicJwksSnapshot(PIN, bytes)))
          .isInstanceOf(AccountPublicJwksReadCodec.InvalidFrameException.class);
    }
  }
}
