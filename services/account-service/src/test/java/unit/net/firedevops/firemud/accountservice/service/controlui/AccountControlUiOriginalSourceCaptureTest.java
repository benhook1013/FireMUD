package unit.net.firedevops.firemud.accountservice.service.controlui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.repository.AccountControlUiIssuanceOperation.OriginalCapture;
import net.firedevops.firemud.accountservice.service.AccountGenerationProjection;
import net.firedevops.firemud.accountservice.service.IssuerGenerationProjection;
import net.firedevops.firemud.accountservice.service.controlui.AccountControlUiOriginalSourceCapture;
import net.firedevops.firemud.common.account.authority.IssuerGenerationAuthorityEventV1Codec;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import org.junit.jupiter.api.Test;

class AccountControlUiOriginalSourceCaptureTest {
  private static final UUID ACCOUNT = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final String ISSUER = "firemud-account-service";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  @SuppressWarnings("unchecked")
  void exactIndependentLargeDecimalSourceCountersEventHistoryAndCheckpointsSurviveReadback()
      throws IOException {
    var event =
        IssuerGenerationAuthorityEventV1Codec.seal(
            Map.ofEntries(
                Map.entry("schemaVersion", IssuerGenerationAuthorityEventV1Codec.SCHEMA_VERSION),
                Map.entry("eventType", IssuerGenerationAuthorityEventV1Codec.EVENT_TYPE),
                Map.entry(
                    "eventId",
                    "account-issuer-authority-event-v1:22222222-2222-4222-8222-222222222222"),
                Map.entry("requestId", "22222222-2222-4222-8222-222222222222"),
                Map.entry("issuerId", ISSUER),
                Map.entry("sourceScope", "issuer/" + ISSUER),
                Map.entry("outboxStreamKey", "account:auth-authority:v1:issuer/" + ISSUER),
                Map.entry("outboxSequence", "9007199254740992"),
                Map.entry("issuerAuthGeneration", "9007199254740993"),
                Map.entry("sourceVersion", "9223372036854775809")));
    var issuer =
        new IssuerGenerationProjection(
            ISSUER,
            event.issuerAuthGeneration(),
            event.sourceVersion(),
            event.outboxStreamKey(),
            event.outboxSequence(),
            Optional.of(event.eventId()),
            Optional.of(event.eventDigest()),
            Optional.of(event.canonicalJson()));
    var authority = authority(issuer);
    var original = original(authority, fence());
    var readback = AccountControlUiOriginalSourceCapture.read(original);
    assertThat(readback.originalCapture()).isEqualTo(original);
    assertThat(readback.authorityTuple())
        .containsEntry("issuerAuthGeneration", "9007199254740993")
        .containsEntry("membershipAuthorityGeneration", Map.of())
        .containsEntry("tenantAuthorityGeneration", Map.of())
        .containsEntry("privateRealmGrantVersions", List.of());
    assertThat(readback.issuanceFence()).isEqualTo("9223372036854775808");
    assertThat(new String(readback.originalCapture().authorityCapture(), StandardCharsets.UTF_8))
        .contains("9223372036854775809", "9007199254740992", event.eventDigest(), event.eventId());
    assertThat(readback.toString()).contains("non-authorizing");
    ((Map<String, Object>) authority.get("authorityTuple"))
        .put("issuerAuthGeneration", "9007199254740992");
    assertThatThrownBy(
            () -> AccountControlUiOriginalSourceCapture.read(original(authority, fence())))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @SuppressWarnings("unchecked")
  void applicableAccountCutoffRetainsOriginalEventAndExactIndependentCheckpoint()
      throws IOException {
    String stream = "account:auth-authority:v1:account/" + ACCOUNT;
    var cutoff =
        Map.of(
            "accountAuthorityGeneration",
            "9007199254740993",
            "outboxStreamKey",
            stream,
            "outboxSequence",
            "9007199254740992");
    var event =
        net.firedevops.firemud.common.account.authority.PasswordResetAuthorityEventV1Codec.seal(
            Map.ofEntries(
                Map.entry(
                    "schemaVersion",
                    net.firedevops.firemud.common.account.authority
                        .PasswordResetAuthorityEventV1Codec.SCHEMA_VERSION),
                Map.entry(
                    "eventType",
                    net.firedevops.firemud.common.account.authority
                        .PasswordResetAuthorityEventV1Codec.EVENT_TYPE),
                Map.entry("eventId", "account-password-reset-event-v1:sample"),
                Map.entry("requestId", "account-password-reset-request-v1:sample"),
                Map.entry("accountId", ACCOUNT.toString()),
                Map.entry("sourceScope", "account/" + ACCOUNT),
                Map.entry("outboxStreamKey", stream),
                Map.entry("outboxSequence", "9007199254740992"),
                Map.entry("accountAuthorityGeneration", "9007199254740993"),
                Map.entry("sourceVersion", "9223372036854775809"),
                Map.entry("accountSecurityCutoff", cutoff)));
    var projection =
        new AccountGenerationProjection(
            ACCOUNT.toString(),
            "9007199254740993",
            "9223372036854775809",
            stream,
            "9007199254740992",
            Optional.of(new String(event.canonicalJsonUtf8(), StandardCharsets.UTF_8)));
    var authority = authority(baselineIssuer());
    authority.put("accountSource", projection.toJson());
    var tuple = (Map<String, Object>) authority.get("authorityTuple");
    tuple.put("accountAuthorityGeneration", "9007199254740993");
    tuple.put("accountSecurityCutoff", cutoff);
    assertThat(
            AccountControlUiOriginalSourceCapture.read(original(authority, fence()))
                .authorityTuple())
        .containsEntry("accountSecurityCutoff", cutoff);
    tuple.remove("accountSecurityCutoff");
    assertThatThrownBy(
            () -> AccountControlUiOriginalSourceCapture.read(original(authority, fence())))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void requiredEmptyShapesCanonicalBytesAndActualSourceCorrespondenceAreMandatory()
      throws IOException {
    var issuer = baselineIssuer();
    for (String field :
        List.of(
            "membershipVersion", "scopedRoles", "globalRoles", "issuerSource", "accountSource")) {
      var authority = authority(issuer);
      authority.remove(field);
      assertThatThrownBy(
              () -> AccountControlUiOriginalSourceCapture.read(original(authority, fence())))
          .isInstanceOf(IllegalArgumentException.class);
    }
    for (Object bad : List.of(List.of(), "1", 1L, Map.of("unexpected", "1"))) {
      var authority = authority(issuer);
      authority.put("membershipVersion", bad);
      assertThatThrownBy(
              () -> AccountControlUiOriginalSourceCapture.read(original(authority, fence())))
          .isInstanceOf(IllegalArgumentException.class);
    }
    for (Object bad : List.of("0", "01", "1e3", 1L, " 1", "-1")) {
      var fence = fence();
      fence.put("issuanceFence", bad);
      assertThatThrownBy(
              () -> AccountControlUiOriginalSourceCapture.read(original(authority(issuer), fence)))
          .isInstanceOf(IllegalArgumentException.class);
    }
    var original = original(authority(issuer), fence());
    assertThatThrownBy(
            () ->
                AccountControlUiOriginalSourceCapture.read(
                    new OriginalCapture(
                        1L,
                        AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT,
                        (" " + new String(original.authorityCapture(), StandardCharsets.UTF_8))
                            .getBytes(StandardCharsets.UTF_8),
                        original.issuanceFenceCapture())))
        .isInstanceOf(IllegalArgumentException.class);
    var wrongFence = fence();
    wrongFence.put("accountId", UUID.randomUUID().toString());
    assertThatThrownBy(
            () ->
                AccountControlUiOriginalSourceCapture.read(original(authority(issuer), wrongFence)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static IssuerGenerationProjection baselineIssuer() {
    return new IssuerGenerationProjection(
        ISSUER,
        "1",
        "1",
        "account:auth-authority:v1:issuer/" + ISSUER,
        "0",
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }

  private static Map<String, Object> authority(IssuerGenerationProjection issuer) {
    var account =
        new AccountGenerationProjection(
            ACCOUNT.toString(),
            "1",
            "1",
            "account:auth-authority:v1:account/" + ACCOUNT,
            "0",
            Optional.empty());
    Map<String, Object> tuple = new LinkedHashMap<>();
    tuple.put("issuerAuthGeneration", issuer.issuerAuthGeneration());
    tuple.put("accountAuthorityGeneration", "1");
    tuple.put("tenantAuthorityGeneration", Map.of());
    tuple.put("membershipAuthorityGeneration", Map.of());
    tuple.put("privateRealmGrantVersions", List.of());
    Map<String, Object> authority = new LinkedHashMap<>();
    authority.put("schemaVersion", "account-control-ui-original-source/v1");
    authority.put("profile", "control-ui");
    authority.put("audience", "control-ui");
    authority.put("accountId", ACCOUNT.toString());
    authority.put("authorityTuple", tuple);
    authority.put("membershipVersion", Map.of());
    authority.put("scopedRoles", Map.of());
    authority.put("globalRoles", List.of());
    authority.put("issuerSource", issuer.toJson());
    authority.put("accountSource", account.toJson());
    authority.put("globalRoleSourceVersion", "9007199254740993");
    return authority;
  }

  private static Map<String, Object> fence() {
    return new LinkedHashMap<>(
        Map.of(
            "schemaVersion",
            "account-control-ui-original-fence/v1",
            "accountId",
            ACCOUNT.toString(),
            "issuanceFence",
            "9223372036854775808",
            "sourceVersion",
            "9223372036854775809"));
  }

  private static OriginalCapture original(Map<String, Object> authority, Map<String, Object> fence)
      throws IOException {
    return new OriginalCapture(
        1L,
        AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT,
        Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(authority)),
        Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(fence)));
  }
}
