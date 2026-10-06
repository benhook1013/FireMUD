package net.firedevops.firemud.accountservice.service.controlui;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.repository.AccountControlUiCurrentSourceRepository.CurrentSourceObservation;
import net.firedevops.firemud.accountservice.repository.AccountControlUiIssuanceOperation.OriginalCapture;
import net.firedevops.firemud.accountservice.service.AccountGenerationProjection;
import net.firedevops.firemud.accountservice.service.IssuerGenerationProjection;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;

/** Exact original unscoped owner-source bytes. This is not a completed authorization bundle. */
public final class AccountControlUiOriginalSourceCapture {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Set<String> FIELDS =
      Set.of(
          "schemaVersion",
          "profile",
          "audience",
          "accountId",
          "authorityTuple",
          "membershipVersion",
          "scopedRoles",
          "globalRoles",
          "issuerSource",
          "accountSource",
          "globalRoleSourceVersion");
  private final OriginalCapture capture;

  private AccountControlUiOriginalSourceCapture(OriginalCapture capture) {
    this.capture = Objects.requireNonNull(capture);
    Map<String, Object> authority = decode(capture.authorityCapture());
    Map<String, Object> fence = decode(capture.issuanceFenceCapture());
    if (!authority.keySet().equals(FIELDS)
        || !"account-control-ui-original-source/v1".equals(authority.get("schemaVersion"))
        || !"control-ui".equals(authority.get("profile"))
        || !"control-ui".equals(authority.get("audience"))
        || !Map.of().equals(authority.get("membershipVersion"))
        || !Map.of().equals(authority.get("scopedRoles"))
        || !List.of().equals(authority.get("globalRoles"))) throw invalid();
    var issuer = IssuerGenerationProjection.parse(text(authority, "issuerSource"));
    var account = AccountGenerationProjection.parse(text(authority, "accountSource"));
    positive(authority.get("globalRoleSourceVersion"));
    if (!account.accountId().equals(authority.get("accountId"))
        || !fence
            .keySet()
            .equals(Set.of("schemaVersion", "accountId", "issuanceFence", "sourceVersion"))
        || !"account-control-ui-original-fence/v1".equals(fence.get("schemaVersion"))
        || !account.accountId().equals(fence.get("accountId"))) throw invalid();
    positive(fence.get("issuanceFence"));
    positive(fence.get("sourceVersion"));
    if (!tuple(issuer, account).equals(authority.get("authorityTuple"))) throw invalid();
  }

  /** Called only after the existing-only reader has acquired the canonical issuer/Account locks. */
  public static AccountControlUiOriginalSourceCapture fromCurrent(
      CurrentSourceObservation source, Account account) {
    Objects.requireNonNull(source);
    Objects.requireNonNull(account);
    if (!source.accountId().equals(account.getAccountUuid())
        || account.getId() == null
        || account.getAccountUuidSourceNumericId() == null
        || !account.getId().equals(account.getAccountUuidSourceNumericId())
        || account.getAccountUuidProvenance() == null
        || !source.authority().tenants().isEmpty()
        || !source.authority().memberships().isEmpty()) throw invalid();
    var issuer = IssuerGenerationProjection.fromSource(source.issuerSource());
    var currentAccount = AccountGenerationProjection.fromSource(source.accountSource());
    Map<String, Object> authority = new LinkedHashMap<>();
    authority.put("schemaVersion", "account-control-ui-original-source/v1");
    authority.put("profile", "control-ui");
    authority.put("audience", "control-ui");
    authority.put("accountId", source.accountId().toString());
    authority.put("authorityTuple", tuple(issuer, currentAccount));
    authority.put("membershipVersion", Map.of());
    authority.put("scopedRoles", Map.of());
    authority.put("globalRoles", List.of());
    authority.put("issuerSource", issuer.toJson());
    authority.put("accountSource", currentAccount.toJson());
    authority.put("globalRoleSourceVersion", Long.toString(source.globalRoleSourceVersion()));
    var fence = source.authority().issuanceFence();
    return new AccountControlUiOriginalSourceCapture(
        new OriginalCapture(
            account.getId(),
            account.getAccountUuidProvenance(),
            encode(authority),
            encode(
                Map.of(
                    "schemaVersion", "account-control-ui-original-fence/v1",
                    "accountId", source.accountId().toString(),
                    "issuanceFence", Long.toString(fence.value()),
                    "sourceVersion", Long.toString(fence.sourceVersion())))));
  }

  public static AccountControlUiOriginalSourceCapture read(OriginalCapture capture) {
    return new AccountControlUiOriginalSourceCapture(capture);
  }

  public OriginalCapture originalCapture() {
    return capture;
  }

  public String accountUuid() {
    return text(decode(capture.authorityCapture()), "accountId");
  }

  public Map<String, Object> authorityTuple() {
    return tuple(
        IssuerGenerationProjection.parse(text(decode(capture.authorityCapture()), "issuerSource")),
        AccountGenerationProjection.parse(
            text(decode(capture.authorityCapture()), "accountSource")));
  }

  public String issuanceFence() {
    return text(decode(capture.issuanceFenceCapture()), "issuanceFence");
  }

  private static Map<String, Object> tuple(
      IssuerGenerationProjection issuer, AccountGenerationProjection account) {
    Map<String, Object> tuple = new LinkedHashMap<>();
    tuple.put("issuerAuthGeneration", issuer.issuerAuthGeneration());
    tuple.put("accountAuthorityGeneration", account.accountAuthorityGeneration());
    tuple.put("tenantAuthorityGeneration", Map.of());
    tuple.put("membershipAuthorityGeneration", Map.of());
    tuple.put("privateRealmGrantVersions", List.of());
    account
        .sourceEvent()
        .ifPresent(
            event -> {
              Map<String, Object> wire = decode(event.getBytes(StandardCharsets.UTF_8));
              if (wire.containsKey("accountSecurityCutoff"))
                tuple.put("accountSecurityCutoff", wire.get("accountSecurityCutoff"));
            });
    return java.util.Collections.unmodifiableMap(tuple);
  }

  private static byte[] encode(Map<String, Object> value) {
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
    } catch (IOException failure) {
      throw new IllegalStateException("Cannot encode original source", failure);
    }
  }

  private static Map<String, Object> decode(byte[] bytes) {
    try {
      String json = new String(bytes, StandardCharsets.UTF_8);
      if (!java.util.Arrays.equals(bytes, Rfc8785CanonicalJson.canonicalizeUtf8(json)))
        throw invalid();
      return JSON.readValue(bytes, new TypeReference<>() {});
    } catch (IOException failure) {
      throw new IllegalArgumentException("Invalid original source", failure);
    }
  }

  private static String text(Map<String, Object> source, String key) {
    Object value = source.get(key);
    if (!(value instanceof String text)) throw invalid();
    return text;
  }

  private static void positive(Object value) {
    if (!(value instanceof String text) || !text.matches("[1-9][0-9]*")) throw invalid();
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid original control-ui source capture");
  }

  @Override
  public String toString() {
    return "AccountControlUiOriginalSourceCapture[non-authorizing]";
  }
}
