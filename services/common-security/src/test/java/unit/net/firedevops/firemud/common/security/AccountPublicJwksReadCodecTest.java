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
          AccountPublicJwksReadCodec.request().toBuilder().setSchemaVersion(1).build(),
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
          valid.toBuilder().setSchemaVersion(1).build(),
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
  void closedDispositionAndEligibilityPresenceCannotBeConfusedWithSnapshotBytes() {
    var snapshot =
        AccountPublicJwksReadCodec.response(
            new AccountPublicJwksReadCodec.ReadResult(PIN, new byte[] {1}, false, true));
    assertThat(snapshot.getSchemaVersion()).isEqualTo(2);
    assertThat(snapshot.hasCommittedRecoveryEligible()).isTrue();
    assertThat(AccountPublicJwksReadCodec.result(snapshot).committedRecoveryEligible()).isTrue();
    var deny =
        AccountPublicJwksReadCodec.response(
            new AccountPublicJwksReadCodec.ReadResult(PIN, new byte[0], true, false));
    assertThat(deny.getPublicJwks()).isEmpty();
    assertThat(deny.hasCommittedRecoveryEligible()).isFalse();
    assertThat(AccountPublicJwksReadCodec.result(deny).sourceIdentity()).isEqualTo(PIN);
    assertThat(AccountPublicJwksReadCodec.result(deny).preparedDeny()).isTrue();
    assertThatThrownBy(() -> AccountPublicJwksReadCodec.snapshot(deny))
        .isInstanceOf(AccountPublicJwksReadCodec.InvalidFrameException.class);
    for (var invalid :
        new GetCurrentAccountPublicJwksResponse[] {
          snapshot.toBuilder().clearDisposition().build(),
          snapshot.toBuilder().setDispositionValue(99).build(),
          snapshot.toBuilder().clearCommittedRecoveryEligible().build(),
          deny.toBuilder().setCommittedRecoveryEligible(false).build(),
          deny.toBuilder().setCommittedRecoveryEligible(true).build(),
          deny.toBuilder().setPublicJwks(ByteString.copyFromUtf8("not a deny")).build(),
          deny.toBuilder().setUnknownFields(UNKNOWN).build(),
          deny.toBuilder().clearSourceIdentity().build(),
          deny.toBuilder()
              .setSourceIdentity(deny.getSourceIdentity().toBuilder().setUnknownFields(UNKNOWN))
              .build()
        }) {
      assertThatThrownBy(() -> AccountPublicJwksReadCodec.result(invalid))
          .isInstanceOf(AccountPublicJwksReadCodec.InvalidFrameException.class);
    }
  }

  @Test
  void localResultRejectsDenyWithBytesOrRecoveryClaimAndCopiesBytes() {
    for (var invalid :
        new Object[][] {
          {new byte[] {1}, true, false}, {new byte[0], true, true}, {new byte[0], false, false}
        }) {
      assertThatThrownBy(
              () ->
                  new AccountPublicJwksReadCodec.ReadResult(
                      PIN, (byte[]) invalid[0], (boolean) invalid[1], (boolean) invalid[2]))
          .isInstanceOf(AccountPublicJwksReadCodec.InvalidFrameException.class);
    }
    byte[] bytes = {1};
    var result = new AccountPublicJwksReadCodec.ReadResult(PIN, bytes, false, false);
    bytes[0] = 2;
    assertThat(result.jwksBytes()).containsExactly((byte) 1);
    result.jwksBytes()[0] = 3;
    assertThat(result.jwksBytes()).containsExactly((byte) 1);
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
