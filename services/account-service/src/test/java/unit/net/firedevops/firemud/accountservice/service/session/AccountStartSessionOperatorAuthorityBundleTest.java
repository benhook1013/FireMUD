package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import org.jooq.Record;
import org.junit.jupiter.api.Test;

class AccountStartSessionOperatorAuthorityBundleTest {
  private static final Instant ISSUED_AT = Instant.parse("2026-10-08T11:00:00Z");

  @Test
  void encodesExactHumanTenantArmAndKeepsOwnerLinearizationSeparate() {
    Fixture fixture = new Fixture(Map.of());
    var linearization = new AccountControlUiIssuanceRepository.OwnerLinearization("123456", 99L);
    var bundle =
        AccountStartSessionOperatorAuthorityBundle.create(
            fixture.tuple,
            fixture.source,
            fixture.issuance,
            UUID.randomUUID(),
            linearization,
            ISSUED_AT,
            ISSUED_AT.plusSeconds(60));
    Map<String, Object> evidence = object(bundle.jsonValue().get("issuanceEvidence"));
    var reference = bundle.referenceForSource(fixture.source, linearization);

    assertThat(bundle.jsonValue())
        .containsEntry("bundleVersion", "authorityEvidenceBundle/v1")
        .containsEntry("issuanceKind", "human_operator")
        .doesNotContainKey("sourceReference");
    assertThat(evidence)
        .containsEntry("evidenceType", "HumanAuthorityEvidence/v1")
        .containsEntry("actorAccountId", fixture.actorId.toString())
        .containsEntry("controlUiTokenJti", fixture.tokenJti.toString())
        .containsEntry("role", "tenantAdmin")
        .doesNotContainKeys("assurance", "globalRoles");
    assertThat(evidence.keySet())
        .containsExactlyInAnyOrder(
            "evidenceType",
            "actorAccountId",
            "controlUiTokenJti",
            "role",
            "accountGeneration",
            "tenantGeneration");
    assertThat(reference.asJsonValue())
        .containsEntry("bundleVersion", "authorityEvidenceBundle/v1")
        .containsEntry("sourceVersion", "7")
        .containsEntry("sourceFence", "99")
        .containsEntry("linearization", "123456");

    var decoded = AccountStartSessionOperatorAuthorityBundle.decode(bundle.canonicalBytes());
    decoded.requireTupleBinding(fixture.tuple);
    decoded.requireCurrent(
        fixture.source, fixture.issuance, fixture.tuple, reference, ISSUED_AT.plusSeconds(1));
  }

  @Test
  void rejectsAssuranceAdditionGlobalRoleSubstitutionAndTupleSubstitution() {
    Fixture assurance = new Fixture(Map.of("assurance", Map.of("level", "step-up")));
    assertThatThrownBy(() -> assurance.create())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("tenantAdmin");

    Fixture globalRole = new Fixture(Map.of("globalRoles", List.of("platformAdmin")));
    assertThatThrownBy(() -> globalRole.create())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("tenantAdmin");

    Fixture valid = new Fixture(Map.of());
    var bundle = valid.create();
    var substituted = tuple(UUID.randomUUID(), valid.tenantId, "different-request");
    assertThatThrownBy(() -> bundle.requireTupleBinding(substituted))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact StartSession tuple");
  }

  @Test
  void strictDecoderRejectsTenantAssuranceAndGlobalBranchEvidence() {
    Fixture fixture = new Fixture(Map.of());
    var valid = fixture.create();
    Map<String, Object> withAssurance = new LinkedHashMap<>(valid.jsonValue());
    Map<String, Object> assuranceArm =
        new LinkedHashMap<>(object(withAssurance.get("issuanceEvidence")));
    assuranceArm.put("assurance", Map.of("level", "step-up"));
    withAssurance.put("issuanceEvidence", assuranceArm);
    assertThatThrownBy(
            () ->
                AccountStartSessionOperatorAuthorityBundle.decode(
                    AccountControlUiAuthority.canonical(withAssurance)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("missing or unsupported fields");

    Map<String, Object> globalArm = new LinkedHashMap<>(valid.jsonValue());
    Map<String, Object> substituted =
        new LinkedHashMap<>(object(globalArm.get("issuanceEvidence")));
    substituted.put("role", "platformAdmin");
    globalArm.put("issuanceEvidence", substituted);
    assertThatThrownBy(
            () ->
                AccountStartSessionOperatorAuthorityBundle.decode(
                    AccountControlUiAuthority.canonical(globalArm)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("tenantAdmin");
  }

  @Test
  void currentSourceMustRetainExactActorGenerationAndSelectedTenant() {
    Fixture fixture = new Fixture(Map.of());
    var linearization = new AccountControlUiIssuanceRepository.OwnerLinearization("123456", 99L);
    var bundle =
        AccountStartSessionOperatorAuthorityBundle.create(
            fixture.tuple,
            fixture.source,
            fixture.issuance,
            UUID.randomUUID(),
            linearization,
            ISSUED_AT,
            ISSUED_AT.plusSeconds(60));
    var substitutedSource =
        fixture.source(fixture.actorId, fixture.tenantId, 8L, new byte[] {8}, 2L);

    assertThatThrownBy(
            () ->
                bundle.requireCurrent(
                    substitutedSource,
                    fixture.issuance,
                    fixture.tuple,
                    bundle.referenceForSource(fixture.source, linearization),
                    ISSUED_AT.plusSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void sameAttemptReplayAfterEnvelopeHorizonRetainsOriginalSourceLinearization() {
    Fixture fixture = new Fixture(Map.of());
    var linearization = new AccountControlUiIssuanceRepository.OwnerLinearization("123456", 99L);
    var bundle =
        AccountStartSessionOperatorAuthorityBundle.create(
            fixture.tuple,
            fixture.source,
            fixture.issuance,
            UUID.randomUUID(),
            linearization,
            ISSUED_AT,
            ISSUED_AT.plusSeconds(60));
    var originalReference = bundle.referenceForSource(fixture.source, linearization);

    bundle.requireCurrentSnapshotForExactReplay(
        fixture.source, fixture.issuance, fixture.tuple, originalReference);

    var substitutedTransaction =
        new AccountStartSessionOperatorAuthorityBundle.BundleReference(
            originalReference.bundleVersion(),
            originalReference.sourceVersion(),
            originalReference.sourceFence(),
            "123457");
    assertThatThrownBy(
            () ->
                bundle.requireCurrentSnapshotForExactReplay(
                    fixture.source, fixture.issuance, fixture.tuple, substitutedTransaction))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("source differs");
  }

  private static StartSessionPreAuthorizationReservationTuple tuple(
      UUID actorId, UUID tenantId, String requestId) {
    var action =
        new StartSessionOperatorAction(
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
            new StartSessionOperatorAction.Scope(tenantId, "test"),
            new StartSessionOperatorAction.Target(1L, actorId),
            StartSessionOperatorAction.ExpectedVersion.ABSENT,
            new StartSessionOperatorAction.Mutation(StartSessionOperatorAction.ClientIp.absent()),
            "authority bundle test");
    return StartSessionPreAuthorizationReservationTuple.createHuman(requestId, actorId, action);
  }

  private static Map<String, Object> object(Object value) {
    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) value;
    return result;
  }

  private static final class Fixture {
    final UUID actorId = UUID.randomUUID();
    final UUID tenantId = UUID.randomUUID();
    final UUID tokenJti = UUID.randomUUID();
    final StartSessionPreAuthorizationReservationTuple tuple =
        tuple(actorId, tenantId, "request-" + UUID.randomUUID());
    final AccountControlUiAuthority.Snapshot source;
    final AccountControlUiIssuanceRepository.Stored issuance;

    Fixture(Map<String, Object> additionalClaims) {
      source = source(actorId, tenantId, 7L, new byte[] {7}, 1L);
      issuance = stored(actorId, tenantId, tokenJti, source, additionalClaims);
    }

    AccountStartSessionOperatorAuthorityBundle create() {
      return AccountStartSessionOperatorAuthorityBundle.create(
          tuple,
          source,
          issuance,
          UUID.randomUUID(),
          new AccountControlUiIssuanceRepository.OwnerLinearization("123456", 99L),
          ISSUED_AT,
          ISSUED_AT.plusSeconds(60));
    }

    AccountControlUiAuthority.Snapshot source(
        UUID actor, UUID tenant, long sourceVersion, byte[] evidence, long accountGeneration) {
      var snapshot = mock(AccountControlUiAuthority.Snapshot.class);
      Map<String, Object> authorityTuple =
          Map.of(
              "issuerAuthGeneration",
              1L,
              "accountAuthorityGeneration",
              accountGeneration,
              "tenantAuthorityGeneration",
              Map.of(tenant.toString(), 1L),
              "membershipAuthorityGeneration",
              Map.of(tenant.toString(), 1L),
              "privateRealmGrantVersions",
              List.of());
      when(snapshot.actor()).thenReturn(actor);
      when(snapshot.tenant()).thenReturn(tenant);
      when(snapshot.authorityTuple()).thenReturn(authorityTuple);
      when(snapshot.membershipVersion()).thenReturn(Map.of(tenant.toString(), 2L));
      when(snapshot.evidence()).thenReturn(evidence.clone());
      when(snapshot.sources())
          .thenReturn(
              List.of(
                  new SourceEvidence(
                      SourceKind.ACCOUNT,
                      actor.toString(),
                      Long.toString(accountGeneration),
                      Long.toString(sourceVersion),
                      null,
                      null,
                      evidence)));
      return snapshot;
    }
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static AccountControlUiIssuanceRepository.Stored stored(
      UUID actorId,
      UUID tenantId,
      UUID tokenJti,
      AccountControlUiAuthority.Snapshot source,
      Map<String, Object> additionalClaims) {
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("jti", tokenJti.toString());
    claims.put("sub", actorId.toString());
    claims.put("accountId", actorId.toString());
    claims.put("aud", "control-ui");
    claims.put("tokenGeneration", 1L);
    claims.put("authorityTuple", source.authorityTuple());
    claims.put("membershipVersion", source.membershipVersion());
    claims.put("scopedRoles", Map.of(tenantId.toString(), List.of("tenantAdmin")));
    claims.putAll(additionalClaims);

    Map<String, Object> rowValues =
        Map.ofEntries(
            Map.entry("request_id", UUID.randomUUID()),
            Map.entry("operation_id", UUID.randomUUID()),
            Map.entry("token_jti", tokenJti),
            Map.entry("account_uuid", actorId),
            Map.entry("tenant_uuid", tenantId),
            Map.entry("caller_context_id", UUID.randomUUID()),
            Map.entry("caller_workload", "spiffe://firemud/ns/test/sa/account-service"),
            Map.entry("request_mac_key_id", "test-key"),
            Map.entry("request_digest", "b".repeat(64)),
            Map.entry("status", "COMMITTED"),
            Map.entry("token_hash", "c".repeat(64)),
            Map.entry("claims_payload", AccountControlUiAuthority.canonical(claims)),
            Map.entry("source_payload", source.evidence()),
            Map.entry("bundle_payload", AccountControlUiAuthority.canonical(Map.of("version", 1))),
            Map.entry("signer_receipt", AccountControlUiAuthority.canonical(Map.of("version", 1))),
            Map.entry("pending_registry", new byte[] {1}),
            Map.entry("active_registry", new byte[] {2}),
            Map.entry("issued_at_epoch_second", ISSUED_AT.minusSeconds(1).getEpochSecond()),
            Map.entry("expires_at_epoch_second", ISSUED_AT.plusSeconds(300).getEpochSecond()),
            Map.entry(
                "recovery_expires_at",
                OffsetDateTime.ofInstant(ISSUED_AT.plusSeconds(300), ZoneOffset.UTC)));
    Record row = mock(Record.class);
    doAnswer(invocation -> rowValues.get(invocation.getArgument(0)))
        .when(row)
        .get(anyString(), any(Class.class));
    return new AccountControlUiIssuanceRepository.Stored(row);
  }
}
