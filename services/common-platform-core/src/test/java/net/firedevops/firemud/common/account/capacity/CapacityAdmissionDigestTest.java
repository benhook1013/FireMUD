package net.firedevops.firemud.common.account.capacity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Evidence;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Operation;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Owner;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Request;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Shape;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Shared vectors prove content encoding only, never a current Account or runtime grant. */
class CapacityAdmissionDigestTest {
  @Test
  void sharedCorpusFixesCompleteEvidenceAndAbsentZeroPositiveBoundaryPreimages() throws Exception {
    var resource = getClass().getResourceAsStream("/capacity/capacity-admission-v1-vectors.json");
    assertThat(resource).isNotNull();
    try (resource) {
      var corpus = JsonMapper.builder().build().readTree(resource);
      for (var vector : corpus.path("vectors")) {
        Long delta =
            vector.path("capacityDelta").isNull()
                ? null
                : TenantCapacityAdmission.decimal(vector.path("capacityDelta").asText());
        Request base = CapacityAdmissionTestFixture.worldRequest(delta);
        assertThat(Base64.getEncoder().encodeToString(base.evidence().canonicalBytes()))
            .isEqualTo(corpus.path("request").path("canonicalAccountEvidenceBase64").asText());
        Request action = base;
        if (vector.has("operation")) {
          var prior = vector.path("priorShape");
          var requested = vector.path("requestedShape");
          action =
              new Request(
                      base.identity(),
                      base.admissionAttemptId(),
                      base.capacityRearmFence(),
                      base.targetNamespace(),
                      base.context(),
                      Operation.valueOf(vector.path("operation").asText()),
                      new Shape(
                          TenantCapacityAdmission.decimal(prior.get(0).asText()),
                          TenantCapacityAdmission.decimal(prior.get(1).asText()),
                          TenantCapacityAdmission.decimal(prior.get(2).asText())),
                      new Shape(
                          TenantCapacityAdmission.decimal(requested.get(0).asText()),
                          TenantCapacityAdmission.decimal(requested.get(1).asText()),
                          TenantCapacityAdmission.decimal(requested.get(2).asText())),
                      base.evidence(),
                      delta,
                      null)
                  .withDigest();
        }
        assertThat(HexFormat.of().formatHex(CapacityAdmissionDigest.preimage(action)))
            .as(vector.path("name").asText())
            .isEqualTo(vector.path("preimageHex").asText());
        assertThat(CapacityAdmissionDigest.digest(action))
            .isEqualTo(vector.path("sha256").asText());
        Request exact = action;
        if (vector.path("commitAllowed").asBoolean()) exact.requireCommittable();
        else
          assertThatThrownBy(exact::requireCommittable)
              .isInstanceOf(IllegalArgumentException.class);
      }
    }
    assertThat(CapacityAdmissionTestFixture.worldRequest(null).mutationDigest())
        .isNotEqualTo(CapacityAdmissionTestFixture.worldRequest(0L).mutationDigest());
  }

  @Test
  void decimalWireGrammarRejectsNegativesOverflowAndMalformedSpellingsBeforeHash() {
    for (String value :
        List.of("9223372036854775808", "-1", "+1", "01", "1e0", "1.0", " 1", "1 ")) {
      assertThatThrownBy(() -> TenantCapacityAdmission.decimal(value))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(TenantCapacityAdmission.decimal("0")).isZero();
    assertThat(TenantCapacityAdmission.decimal("9223372036854775807")).isEqualTo(Long.MAX_VALUE);
    assertThatThrownBy(() -> assertThat(Math.addExact(Long.MAX_VALUE, 1)).isPositive())
        .isInstanceOf(ArithmeticException.class);
  }

  @Test
  void evidenceIsStrictCanonicalAndCompleteChangedFieldsAlterDigest() {
    Request original = CapacityAdmissionTestFixture.worldRequest(17L);
    String json = new String(original.evidence().canonicalBytes(), StandardCharsets.UTF_8);
    for (String changed :
        List.of(
            " " + json,
            json.replaceFirst("\"outboxSequence\":\"3\"", "\"outboxSequence\":\"4\""),
            "{\"bundleVersion\":\"1\"," + json.substring(1))) {
      assertThatThrownBy(
              () ->
                  Evidence.parse(changed.getBytes(StandardCharsets.UTF_8), Owner.WORLD_MANAGEMENT))
          .isInstanceOf(IllegalArgumentException.class);
    }
    Evidence changed =
        Evidence.parse(
            json.replace("\"entitlementVersion\":\"7\"", "\"entitlementVersion\":\"8\"")
                .getBytes(StandardCharsets.UTF_8),
            Owner.WORLD_MANAGEMENT);
    Request mutation =
        new Request(
                original.identity(),
                original.admissionAttemptId(),
                original.capacityRearmFence(),
                original.targetNamespace(),
                original.context(),
                original.operation(),
                original.priorShape(),
                original.requestedShape(),
                changed,
                original.capacityDelta(),
                null)
            .withDigest();
    assertThat(mutation.mutationDigest()).isNotEqualTo(original.mutationDigest());
    byte[] copy = changed.canonicalBytes();
    copy[0] = 0;
    assertThat(changed.canonicalBytes()[0]).isEqualTo((byte) '{');
  }

  @Test
  void zeroCannotHideAnyChangedExpandingShape() {
    Request original = CapacityAdmissionTestFixture.worldRequest(0L);
    Request zero =
        new Request(
                original.identity(),
                original.admissionAttemptId(),
                original.capacityRearmFence(),
                original.targetNamespace(),
                original.context(),
                Operation.NON_EXPANDING_RECOVERY,
                new Shape(0, 1, 0),
                new Shape(0, 1, 0),
                original.evidence(),
                0L,
                null)
            .withDigest();
    zero.requireCommittable();
    for (Shape shape : List.of(new Shape(1, 1, 0), new Shape(0, 2, 0), new Shape(0, 1, 1))) {
      var expanding =
          new Request(
                  zero.identity(),
                  zero.admissionAttemptId(),
                  zero.capacityRearmFence(),
                  zero.targetNamespace(),
                  zero.context(),
                  zero.operation(),
                  zero.priorShape(),
                  shape,
                  zero.evidence(),
                  0L,
                  null)
              .withDigest();
      assertThatThrownBy(expanding::requireCommittable)
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(expanding.mutationDigest()).isNotEqualTo(zero.mutationDigest());
    }
  }
}
