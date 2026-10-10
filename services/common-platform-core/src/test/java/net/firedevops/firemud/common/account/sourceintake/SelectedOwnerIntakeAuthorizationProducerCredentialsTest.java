package net.firedevops.firemud.common.account.sourceintake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Metadata;
import net.firedevops.firemud.account.v1.AccountSelectedOwnerIntakeAuthorizationProducerServiceGrpc;
import org.junit.jupiter.api.Test;

class SelectedOwnerIntakeAuthorizationProducerCredentialsTest {
  private static final String SECRET = "original.creator.credential";

  @Test
  void acceptsOnlyTheAuthorizeSelectedOwnerIntakeMethod() {
    String allowedMethod =
        AccountSelectedOwnerIntakeAuthorizationProducerServiceGrpc
            .getAuthorizeSelectedOwnerIntakeMethod()
            .getFullMethodName();

    assertThat(SelectedOwnerIntakeAuthorizationProducerCredentials.allowedMethod(allowedMethod))
        .isTrue();
    assertThat(SelectedOwnerIntakeAuthorizationProducerCredentials.allowedMethod("other/Method"))
        .isFalse();
    assertThat(
            SelectedOwnerIntakeAuthorizationProducerCredentials.allowedMethod(
                "account.v1.OtherService/AuthorizeSelectedOwnerIntake"))
        .isFalse();

    var headers = new Metadata();
    headers.put(
        SelectedOwnerIntakeAuthorizationProducerCredentials.HEADER,
        SelectedOwnerIntakeAuthorizationProducerCredentials.Credential.of(SECRET));
    assertThat(
            SelectedOwnerIntakeAuthorizationProducerCredentials.readAuthenticated(
                    headers, allowedMethod)
                .value())
        .isEqualTo(SECRET);
    assertThatThrownBy(
            () ->
                SelectedOwnerIntakeAuthorizationProducerCredentials.readAuthenticated(
                    headers, "other/Method"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void requiresExactlyOneCredentialAndRedactsItsTextRepresentation() {
    var missing = new Metadata();
    var one = new Metadata();
    var credential = SelectedOwnerIntakeAuthorizationProducerCredentials.Credential.of(SECRET);
    one.put(SelectedOwnerIntakeAuthorizationProducerCredentials.HEADER, credential);
    var duplicate = new Metadata();
    duplicate.put(
        SelectedOwnerIntakeAuthorizationProducerCredentials.HEADER,
        SelectedOwnerIntakeAuthorizationProducerCredentials.Credential.of(SECRET));
    duplicate.put(
        SelectedOwnerIntakeAuthorizationProducerCredentials.HEADER,
        SelectedOwnerIntakeAuthorizationProducerCredentials.Credential.of("second.credential"));
    String method =
        AccountSelectedOwnerIntakeAuthorizationProducerServiceGrpc
            .getAuthorizeSelectedOwnerIntakeMethod()
            .getFullMethodName();

    assertThatThrownBy(
            () ->
                SelectedOwnerIntakeAuthorizationProducerCredentials.readAuthenticated(
                    missing, method))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                SelectedOwnerIntakeAuthorizationProducerCredentials.readAuthenticated(
                    duplicate, method))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(credential.toString()).doesNotContain(SECRET);
    assertThat(SelectedOwnerIntakeAuthorizationProducerCredentials.forAuthorize(SECRET).toString())
        .doesNotContain(SECRET);
  }

  @Test
  void rejectsBlankWhitespaceNonAsciiAndOversizedCredentialsWithoutTrimming() {
    for (String invalid :
        new String[] {
          null,
          "",
          " ",
          "has whitespace",
          "\ttab",
          "non-ascii-\u00e9",
          "x".repeat(SelectedOwnerIntakeAuthorizationProducerCredentials.MAX_CREDENTIAL_CHARS + 1)
        }) {
      assertThatThrownBy(
              () -> SelectedOwnerIntakeAuthorizationProducerCredentials.Credential.of(invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(
            SelectedOwnerIntakeAuthorizationProducerCredentials.Credential.of(
                    "x"
                        .repeat(
                            SelectedOwnerIntakeAuthorizationProducerCredentials
                                .MAX_CREDENTIAL_CHARS))
                .value())
        .hasSize(SelectedOwnerIntakeAuthorizationProducerCredentials.MAX_CREDENTIAL_CHARS);
  }
}
