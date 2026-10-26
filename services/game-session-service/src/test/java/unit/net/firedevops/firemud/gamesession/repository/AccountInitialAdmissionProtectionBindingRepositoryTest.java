package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionEvidence;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionRequest;
import net.firedevops.firemud.common.account.startsession.StartSessionAccountRedemptionProjection;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundleFixture;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Controlled row projections exercise the real reader; these are not persisted-owner custody. */
public class AccountInitialAdmissionProtectionBindingRepositoryTest {
  public static final String NAMESPACE = "world-runtime";
  public static final String OPERATION = "settlement-admission";
  public static final Instant TERMINAL = Instant.parse("2026-10-24T01:02:03.123456Z");

  @Test
  void completeProtectedCommitAndAbortReturnRetainedBytesWithoutReconstructingCallerEvidence() {
    for (String status : List.of("COMMITTED", "ABORTED")) {
      var protection = protection();
      var values = fields(protection, status);
      var result = repository(values).read(NAMESPACE, OPERATION).orElseThrow();
      assertThat(result.protection().canonicalBytes()).containsExactly(protection.canonicalBytes());
      assertThat(result.terminalProofDigest())
          .isEqualTo(
              values.get(
                  status.equals("COMMITTED") ? "commit_proof_digest" : "abort_proof_digest"));
    }
  }

  @Test
  void everyIndependentOwnerAssociationAndTerminalSubstitutionRefuses() {
    var p = protection();
    Map<String, Object> changes = new HashMap<>();
    for (String field :
        List.of(
            "intent_tuple",
            "launch_tuple",
            "original_tuple",
            "original_projection",
            "intent_hold",
            "intent_request")) {
      changes.put(field, new byte[] {1, 2});
    }
    for (String field :
        List.of(
            "retained_attempt",
            "retained_mutation",
            "original_owner_attempt_id",
            "original_owner_mutation_id",
            "original_tenant",
            "hold_id",
            "hold_fence")) {
      changes.put(field, UUID.randomUUID());
    }
    for (String field :
        List.of(
            "retained_fence",
            "original_owner_fence",
            "account_protection_fence",
            "pointer_id",
            "pointer_version",
            "audit_event_id")) {
      changes.put(field, 88L);
    }
    changes.put("retained_lease", OffsetDateTime.ofInstant(TERMINAL, ZoneOffset.UTC));
    changes.put("terminal_at", OffsetDateTime.ofInstant(TERMINAL.plusNanos(1000), ZoneOffset.UTC));
    changes.put("intent_state", "HOLD_ATTACHED");
    changes.put("intent_digest", "d".repeat(64));
    changes.put("intent_original_request", "another-original");
    changes.put("original_request", "another-original");
    changes.put("commit_proof_digest", "sha256:" + "f".repeat(64));
    changes.forEach(
        (field, replacement) -> {
          var values = fields(p, "COMMITTED");
          values.put(field, replacement);
          assertThatThrownBy(() -> repository(values).read(NAMESPACE, OPERATION))
              .as(field)
              .isInstanceOfAny(IllegalStateException.class, IllegalArgumentException.class);
        });
  }

  @Test
  void absentLegacyPartialAndNonterminalAssociationCannotBecomeSettlement() {
    var legacy = fields(protection(), "COMMITTED");
    for (String name :
        List.of(
            "account_protection_evidence_version",
            "account_protection_id",
            "account_protection_fence",
            "original_owner_attempt_id",
            "original_owner_mutation_id",
            "original_owner_fence",
            "account_protection_evidence_bytes")) {
      legacy.put(name, null);
    }
    assertThat(repository(legacy).read(NAMESPACE, OPERATION)).isEmpty();
    legacy.put("account_protection_id", UUID.randomUUID());
    assertThatThrownBy(() -> repository(legacy).read(NAMESPACE, OPERATION))
        .isInstanceOf(IllegalStateException.class);
    var pending = fields(protection(), "ABORTED");
    pending.put("status", "PENDING");
    assertThatThrownBy(() -> repository(pending).read(NAMESPACE, OPERATION))
        .isInstanceOf(IllegalStateException.class);
    var dsl = mock(DSLContext.class);
    assertThat(
            new AccountInitialAdmissionProtectionBindingRepository(dsl).read(NAMESPACE, OPERATION))
        .isEmpty();
  }

  private static AccountInitialAdmissionProtectionBindingRepository repository(
      Map<String, Object> fields) {
    var dsl = mock(DSLContext.class);
    var row = record(fields);
    when(dsl.fetchOne(anyString(), eq(NAMESPACE), eq(OPERATION))).thenReturn(row);
    return new AccountInitialAdmissionProtectionBindingRepository(dsl);
  }

  public static Record record(Map<String, Object> fields) {
    Record row = mock(Record.class);
    when(row.get(anyString())).thenAnswer(call -> fields.get(call.getArgument(0)));
    when(row.get(anyString(), org.mockito.ArgumentMatchers.<Class<Object>>any()))
        .thenAnswer(call -> fields.get(call.getArgument(0)));
    return row;
  }

  public static Map<String, Object> fields(
      AccountStartSessionAdmissionProtectionEvidence p, String status) {
    var r = p.request();
    var h = r.worldAdmissionHoldIdentity();
    var w = h.request();
    Map<String, Object> f = new HashMap<>();
    f.put("account_protection_evidence_version", 1);
    f.put("account_protection_evidence_bytes", p.canonicalBytes());
    f.put("account_protection_id", p.accountProtectionId());
    f.put("account_protection_fence", p.accountProtectionFence());
    f.put("original_owner_attempt_id", r.gameSessionOwnerAttemptId());
    f.put("retained_attempt", r.gameSessionOwnerAttemptId());
    f.put("original_owner_mutation_id", r.gameSessionOwnerMutationId());
    f.put("retained_mutation", r.gameSessionOwnerMutationId());
    f.put("original_owner_fence", r.gameSessionOwnerFence());
    f.put("retained_fence", r.gameSessionOwnerFence());
    f.put("retained_lease", r.originalLeaseExpiresAt().atOffset(ZoneOffset.UTC));
    for (String field : List.of("intent_tuple", "original_tuple", "launch_tuple"))
      f.put(field, r.originalPostAuthorizationTupleBytes());
    f.put("original_projection", r.accountRedemptionProjectionBytes());
    f.put("intent_hold", h.canonicalBytes());
    f.put("intent_request", h.canonicalRequestBytes());
    f.put("intent_state", "TERMINAL");
    f.put("intent_digest", w.initialAdmissionRequestDigest());
    f.put("intent_original_request", r.originalTuple().controlPlaneRequestId());
    f.put("original_request", r.originalTuple().controlPlaneRequestId());
    f.put("original_tenant", w.canonicalTenantId());
    f.put("target_namespace", w.targetNamespace());
    f.put("canonical_tenant_id", w.canonicalTenantId());
    f.put("world_slug", w.worldSlug());
    f.put("realm_id", w.realmId());
    f.put("playable_state_namespace_id", w.playableStateNamespaceId());
    f.put("playable_state_scope", w.playableStateScope());
    f.put("canonical_game_instance_id", w.canonicalGameInstanceId());
    f.put("canonical_version_id", w.canonicalVersionId());
    f.put("active_lifecycle_epoch", w.activeLifecycleEpoch());
    f.put("expected_catalog_revision", w.expectedCatalogRevision());
    f.put("origin_kind", w.initialAdmissionOrigin().name());
    f.put("expected_prior_pointer_version", w.expectedPriorPointerVersion());
    f.put("initial_admission_request_id", w.initialAdmissionRequestId());
    f.put("request_digest", w.initialAdmissionRequestDigest());
    f.put("hold_id", h.holdId());
    f.put("hold_fence", h.holdFence());
    f.put("hold_binding_digest", h.holdBindingDigest());
    f.put("status", status);
    f.put("terminal_at", TERMINAL.atOffset(ZoneOffset.UTC));
    boolean commit = status.equals("COMMITTED");
    f.put("pointer_id", commit ? 7L : null);
    f.put("pointer_version", commit ? 2L : null);
    f.put("audit_event_id", commit ? 19L : null);
    f.put("abort_reason", commit ? null : "durable-fenced-abort");
    f.put(
        commit ? "commit_proof_digest" : "abort_proof_digest",
        AccountInitialAdmissionProtectionBindingRepository.terminalDigest(
            p.canonicalBytes(),
            w.initialAdmissionRequestDigest(),
            status,
            (Long) f.get("pointer_id"),
            (Long) f.get("pointer_version"),
            (Long) f.get("audit_event_id"),
            TERMINAL,
            (String) f.get("abort_reason")));
    return f;
  }

  /** Complete stipulated upstream values only; no authenticated Account acquisition is implied. */
  public static AccountStartSessionAdmissionProtectionEvidence protection() {
    var tenant = UUID.fromString("9f8f06b4-36e5-4d11-9c2a-5adfd7f41531");
    var pre =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            "original-start-session",
            UUID.randomUUID(),
            new StartSessionOperatorAction(
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
                new StartSessionOperatorAction.Scope(tenant, NAMESPACE),
                new StartSessionOperatorAction.Target(91L, UUID.randomUUID()),
                StartSessionOperatorAction.ExpectedVersion.ABSENT,
                new StartSessionOperatorAction.Mutation(
                    StartSessionOperatorAction.ClientIp.absent()),
                "controlled settlement source"));
    var bundle =
        StartSessionAuthorityEvidenceBundleFixture.canonicalBytes(
            pre,
            new StartSessionAuthorityEvidenceBundleFixture.Values(
                UUID.randomUUID(),
                "sha256:" + "a".repeat(64),
                "17",
                "CURRENT",
                TERMINAL.minusSeconds(120).truncatedTo(java.time.temporal.ChronoUnit.MILLIS),
                TERMINAL.plusSeconds(120).truncatedTo(java.time.temporal.ChronoUnit.MILLIS),
                1,
                2,
                3,
                4,
                5,
                23,
                UUID.randomUUID(),
                "2",
                "3"));
    var tuple =
        StartSessionPostAuthorizationExecutionTuple.createHuman(
            pre,
            "spiffe://firemud/ns/" + NAMESPACE + "/sa/logging-admin-service",
            "arfp/v1/test-key/" + "b".repeat(64),
            UUID.randomUUID(),
            19L,
            bundle,
            new StartSessionAuthorityEvidenceBundle.BundleReference(
                StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION, "17", "23", "29"));
    var realm = UUID.randomUUID();
    var playable = UUID.randomUUID();
    var instance = UUID.randomUUID();
    var version = UUID.randomUUID();
    String digest =
        CanonicalInitialAdmissionRequest.computeRequestDigest(
            NAMESPACE,
            tenant,
            "earth",
            realm,
            playable,
            "SHARED",
            instance,
            version,
            3,
            12,
            CanonicalInitialAdmissionRequest.OriginKind.NO_PRIOR_POINTER,
            null,
            OPERATION);
    var hold =
        new WorldCanonicalInitialAdmissionHold.HoldIdentity(
            new WorldCanonicalInitialAdmissionHold.Request(
                NAMESPACE,
                tenant,
                "earth",
                realm,
                playable,
                "SHARED",
                instance,
                version,
                3,
                OPERATION,
                digest,
                WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.NO_PRIOR_POINTER,
                12,
                null),
            UUID.randomUUID(),
            UUID.randomUUID());
    var request =
        AccountStartSessionAdmissionProtectionRequest.create(
            tuple.canonicalBytes(),
            StartSessionAccountRedemptionProjection.fromOriginalTuple(tuple),
            UUID.randomUUID(),
            UUID.randomUUID(),
            7,
            TERMINAL.plusSeconds(60),
            UUID.randomUUID(),
            11,
            hold);
    try {
      byte[] capture =
          Rfc8785CanonicalJson.canonicalizeUtf8(
              JsonMapper.builder()
                  .build()
                  .writeValueAsString(
                      Map.of(
                          "schema",
                          AccountStartSessionAdmissionProtectionEvidence
                              .SOURCE_CAPTURE_REFERENCE_SCHEMA,
                          "controlPlaneRequestId",
                          tuple.controlPlaneRequestId(),
                          "capturedAt",
                          java.time.format.DateTimeFormatter.ofPattern(
                                  "uuuu-MM-dd'T'HH:mm:ss.SSS'Z'")
                              .withZone(ZoneOffset.UTC)
                              .format(TERMINAL.minusSeconds(30)),
                          "bundleReference",
                          Map.of(
                              "bundleVersion",
                              StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
                              "sourceVersion",
                              "17",
                              "sourceFence",
                              "23",
                              "linearization",
                              "29"),
                          "snapshotSha256",
                          "d".repeat(64))));
      return AccountStartSessionAdmissionProtectionEvidence.create(
          request,
          UUID.randomUUID(),
          13,
          capture,
          HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(capture)),
          List.of(
              new SourceEvidence(
                  SourceKind.ACCOUNT,
                  tenant.toString(),
                  "2",
                  "17",
                  null,
                  null,
                  "controlled-account-source".getBytes(StandardCharsets.UTF_8))));
    } catch (Exception impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
