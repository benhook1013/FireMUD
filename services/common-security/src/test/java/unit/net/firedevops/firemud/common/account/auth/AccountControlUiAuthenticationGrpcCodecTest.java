package net.firedevops.firemud.common.account.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.UnknownFieldSet;
import java.util.UUID;
import net.firedevops.firemud.account.v1.AccountControlUiAuthLoginStatus;
import net.firedevops.firemud.common.account.auth.AccountControlUiAuthenticationGrpcCodec.Authenticated;
import net.firedevops.firemud.common.account.auth.AccountControlUiAuthenticationGrpcCodec.Outcome;
import net.firedevops.firemud.common.account.auth.AccountControlUiAuthenticationGrpcCodec.Request;
import net.firedevops.firemud.common.account.auth.AccountControlUiAuthenticationGrpcCodec.SourceContext;
import org.junit.jupiter.api.Test;

class AccountControlUiAuthenticationGrpcCodecTest {
  private static final UUID REQUEST_ID = uuid("0e70cae4-13c7-4597-a3c0-630315312574");
  private static final UUID CALLER_CONTEXT_ID = uuid("bca4c5ab-4c36-4f18-9517-e99a34f93021");
  private static final UUID ACCOUNT_ID = uuid("8d3ce19e-8c28-4dba-9f11-dfa2e96ace3c");
  private static final String NAMESPACE = "checkout-test";
  private static final String EMAIL = "operator@example.test";
  private static final String SECRET = "synthetic-primary-secret";
  private static final String TOKEN = "eyJhbGciOiJub25lIn0.eyJzdWIiOiJ4In0.c2lnbmF0dXJl";

  @Test
  void mapsOneExactBoundedRequestWithoutExposingSecretInDiagnostics() {
    Request request = request();

    var wire = AccountControlUiAuthenticationGrpcCodec.toRequest(request);
    var parsed = AccountControlUiAuthenticationGrpcCodec.parseRequest(wire);

    assertThat(parsed).isEqualTo(request);
    assertThat(request.toString()).doesNotContain(SECRET);
    assertThat(wire.getSecretList()).containsExactly(SECRET);
    assertThat(parsed.sourceContext().canonicalClientAddress()).isEqualTo("192.0.2.10");
    assertThat(parsed.sourceContext().toString()).doesNotContain("192.0.2.10");
  }

  @Test
  void enforcesAccountUtf16CharacterLimitsAndAcceptsBoundedUnicode() {
    String unicodeBoundaryEmail = "\u00e9".repeat(66) + "a".repeat(186) + "@x";
    String unicodeBoundarySecret = new String(Character.toChars(0x1F510)).repeat(512);
    assertThat(unicodeBoundaryEmail).hasSize(254);
    assertThat(unicodeBoundarySecret).hasSize(1024);
    Request boundary =
        new Request(
            REQUEST_ID,
            CALLER_CONTEXT_ID,
            NAMESPACE,
            unicodeBoundaryEmail,
            unicodeBoundarySecret,
            new SourceContext("192.0.2.10"));

    assertThat(
            AccountControlUiAuthenticationGrpcCodec.parseRequest(
                AccountControlUiAuthenticationGrpcCodec.toRequest(boundary)))
        .isEqualTo(boundary);

    var wire = AccountControlUiAuthenticationGrpcCodec.toRequest(request());
    assertRejected(wire.toBuilder().setEmail(0, "a".repeat(253) + "@x").build());
    assertRejected(wire.toBuilder().setEmail(0, "\u00e9".repeat(252) + "@x").build());
    assertRejected(wire.toBuilder().setSecret(0, "s".repeat(1025)).build());
  }

  @Test
  void mapsSuccessAndDenialWhileNeverEchoingPrimarySecret() {
    Request request = request();
    Authenticated authenticated = new Authenticated(ACCOUNT_ID, TOKEN, 1_900_000_000L);

    var success =
        AccountControlUiAuthenticationGrpcCodec.requireResponse(
            request, AccountControlUiAuthenticationGrpcCodec.toResponse(request, authenticated));
    var denied =
        AccountControlUiAuthenticationGrpcCodec.requireResponse(
            request, AccountControlUiAuthenticationGrpcCodec.toResponse(request, null));

    assertThat(success.outcome()).isEqualTo(Outcome.SUCCESS);
    assertThat(success.authenticated()).isEqualTo(authenticated);
    assertThat(denied.outcome()).isEqualTo(Outcome.DENIED);
    assertThat(denied.authenticated()).isNull();
    assertThat(
            AccountControlUiAuthenticationGrpcCodec.toResponse(request, authenticated).toString())
        .doesNotContain(SECRET);
  }

  @Test
  void rejectsMissingDuplicateUnknownAndOversizedRequestMembers() {
    var request = AccountControlUiAuthenticationGrpcCodec.toRequest(request());
    var unknown =
        UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
            .build();

    assertRejected(request.toBuilder().clearEmail().build());
    assertRejected(request.toBuilder().addSecret("duplicate").build());
    assertRejected(request.toBuilder().setUnknownFields(unknown).build());
    assertRejected(request.toBuilder().setSecret(0, "x".repeat(10_000)).build());
    assertRejected(
        request.toBuilder().setRequestId(0, REQUEST_ID.toString().toUpperCase()).build());
    assertRejected(request.toBuilder().setTargetNamespace(0, "../namespace").build());
    assertRejected(request.toBuilder().setEmail(0, "not-an-email").build());
    assertRejected(request.toBuilder().setSecret(0, "").build());
  }

  @Test
  void rejectsAbsentDuplicateMalformedAndWrongTransportSourceBeforeAuthentication() {
    var wire = AccountControlUiAuthenticationGrpcCodec.toRequest(request());
    assertRejected(wire.toBuilder().clearCanonicalClientAddress().build());
    assertRejected(wire.toBuilder().addCanonicalClientAddress("192.0.2.11").build());
    assertRejected(wire.toBuilder().clearTransportClass().build());
    assertRejected(wire.toBuilder().addTransportClassValue(1).build());
    assertRejected(wire.toBuilder().setTransportClassValue(0, 0).build());
    assertRejected(wire.toBuilder().setTransportClassValue(0, 99).build());
    for (String address :
        java.util.List.of(
            "",
            "localhost",
            "192.0.2.010",
            "192.0.2.256",
            "127.1",
            " 192.0.2.10",
            "192.0.2.10:443",
            "2001:DB8:0:0:0:0:0:1",
            "2001:db8::1",
            "[::1]",
            "fe80:0:0:0:0:0:0:1%eth0",
            "::ffff:192.0.2.10",
            "a".repeat(65))) {
      assertRejected(wire.toBuilder().setCanonicalClientAddress(0, address).build());
    }
    assertThatThrownBy(() -> new SourceContext(null)).isInstanceOf(IllegalArgumentException.class);
    assertThat(
            AccountControlUiAuthenticationGrpcCodec.parseRequest(
                    wire.toBuilder().setCanonicalClientAddress(0, "2001:db8:0:0:0:0:0:1").build())
                .sourceContext()
                .canonicalClientAddress())
        .isEqualTo("2001:db8:0:0:0:0:0:1");
  }

  @Test
  void rejectsResponseSubstitutionUnknownDuplicateAndContradictoryOutcomes() {
    Request request = request();
    var success =
        AccountControlUiAuthenticationGrpcCodec.toResponse(
            request, new Authenticated(ACCOUNT_ID, TOKEN, 1_900_000_000L));
    var denial = AccountControlUiAuthenticationGrpcCodec.toResponse(request, null);
    var unknown =
        UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
            .build();

    assertResponseRejected(
        success.toBuilder().setCallerContextId(0, ACCOUNT_ID.toString()).build());
    assertResponseRejected(success.toBuilder().addAuthToken(TOKEN).build());
    assertResponseRejected(success.toBuilder().setUnknownFields(unknown).build());
    assertResponseRejected(success.toBuilder().setAccountId(0, "not-a-uuid").build());
    assertResponseRejected(success.toBuilder().setAuthToken(0, "not-a-jwt").build());
    assertResponseRejected(success.toBuilder().setExpiresAtEpochSeconds(0, 0L).build());
    assertResponseRejected(denial.toBuilder().addAccountId(ACCOUNT_ID.toString()).build());
    assertResponseRejected(
        denial.toBuilder()
            .addStatus(AccountControlUiAuthLoginStatus.ACCOUNT_CONTROL_UI_AUTH_LOGIN_STATUS_DENIED)
            .build());
  }

  @Test
  void acceptsMaximumCompactTokenAndRejectsOneByteOverTheLimit() {
    String maxToken = "a." + "b".repeat(16_380) + ".c";
    String oversizedToken = "a." + "b".repeat(16_381) + ".c";

    assertThat(new Authenticated(ACCOUNT_ID, maxToken, 1_900_000_000L).authToken())
        .hasSize(AccountControlUiAuthenticationGrpcCodec.MAX_COMPACT_AUTH_TOKEN_BYTES);
    assertThatThrownBy(() -> new Authenticated(ACCOUNT_ID, oversizedToken, 1_900_000_000L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Exact bounded Control-UI authentication message required");
  }

  private static Request request() {
    return new Request(
        REQUEST_ID, CALLER_CONTEXT_ID, NAMESPACE, EMAIL, SECRET, new SourceContext("192.0.2.10"));
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private static void assertRejected(net.firedevops.firemud.account.v1.AuthLoginRequest request) {
    assertThatThrownBy(() -> AccountControlUiAuthenticationGrpcCodec.parseRequest(request))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static void assertResponseRejected(
      net.firedevops.firemud.account.v1.AuthLoginResponse response) {
    assertThatThrownBy(
            () -> AccountControlUiAuthenticationGrpcCodec.requireResponse(request(), response))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
