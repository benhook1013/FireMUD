package net.firedevops.firemud.common.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.CallCredentials;
import io.grpc.Metadata;
import io.grpc.SecurityLevel;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.firedevops.firemud.account.v1.AccountSelectedPublicationOrderServiceGrpc;
import org.junit.jupiter.api.Test;

class AccountSelectedPublicationOrderCredentialsTest {
  private static final String SECRET = "original.secret.credential";
  private static final Metadata.Key<byte[]> RAW =
      Metadata.Key.of(
          AccountSelectedPublicationOrderCredentials.HEADER.name(),
          Metadata.BINARY_BYTE_MARSHALLER);

  @Test
  void carrierAndWrapperAreRedactedAndApplyOnlyToProtectedExactMethod() {
    var credentials = AccountSelectedPublicationOrderCredentials.forCall(SECRET);
    assertThat(credentials.toString()).doesNotContain(SECRET);
    assertThat(AccountSelectedPublicationOrderCredentials.Credential.of(SECRET).toString())
        .doesNotContain(SECRET);
    var info = mock(CallCredentials.RequestInfo.class);
    when(info.getSecurityLevel()).thenReturn(SecurityLevel.PRIVACY_AND_INTEGRITY);
    org.mockito.Mockito.doReturn(
            AccountSelectedPublicationOrderServiceGrpc.getAuthorizeSelectedPublicationMethod())
        .when(info)
        .getMethodDescriptor();
    var applier = mock(CallCredentials.MetadataApplier.class);
    credentials.applyRequestMetadata(info, Runnable::run, applier);
    var captured = org.mockito.ArgumentCaptor.forClass(Metadata.class);
    verify(applier).apply(captured.capture());
    assertThat(
            AccountSelectedPublicationOrderCredentials.readAuthenticated(captured.getValue())
                .value())
        .isEqualTo(SECRET);
    for (var level : List.of(SecurityLevel.NONE, SecurityLevel.INTEGRITY)) {
      when(info.getSecurityLevel()).thenReturn(level);
      var denied = mock(CallCredentials.MetadataApplier.class);
      credentials.applyRequestMetadata(info, Runnable::run, denied);
      verify(denied)
          .fail(
              org.mockito.ArgumentMatchers.argThat(
                  status -> status.getCode() == io.grpc.Status.Code.UNAUTHENTICATED));
    }
    when(info.getSecurityLevel()).thenReturn(SecurityLevel.PRIVACY_AND_INTEGRITY);
    org.mockito.Mockito.doReturn(
            AccountSelectedPublicationOrderServiceGrpc.getAuthorizeSelectedPublicationMethod()
                .toBuilder()
                .setFullMethodName("different/Method")
                .build())
        .when(info)
        .getMethodDescriptor();
    var denied = mock(CallCredentials.MetadataApplier.class);
    credentials.applyRequestMetadata(info, Runnable::run, denied);
    verify(denied).fail(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void
      authenticatedReadRejectsMissingDuplicateOversizedAndMalformedHeadersWithoutSecretDiagnostics() {
    var missing = new Metadata();
    var duplicate = new Metadata();
    duplicate.put(RAW, SECRET.getBytes(StandardCharsets.US_ASCII));
    duplicate.put(RAW, SECRET.getBytes(StandardCharsets.US_ASCII));
    for (var headers :
        List.of(
            missing,
            duplicate,
            raw(new byte[0]),
            raw(new byte[] {(byte) 128}),
            raw((SECRET + "\n").getBytes(StandardCharsets.US_ASCII)),
            raw(new byte[16385]))) {
      assertThatThrownBy(
              () -> AccountSelectedPublicationOrderCredentials.readAuthenticated(headers))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageNotContaining(SECRET)
          .hasNoCause();
    }
    var exact =
        AccountSelectedPublicationOrderCredentials.readAuthenticated(
            raw(SECRET.getBytes(StandardCharsets.US_ASCII)));
    assertThat(exact.value()).isEqualTo(SECRET);
    assertThat(exact.toString()).doesNotContain(SECRET);
  }

  private static Metadata raw(byte[] bytes) {
    var headers = new Metadata();
    headers.put(RAW, bytes);
    return headers;
  }
}
