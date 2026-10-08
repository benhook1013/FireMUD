package net.firedevops.firemud.common.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.io.IOException;
import java.util.UUID;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicyReadGrpcCodec;
import net.firedevops.firemud.gamedesign.v1.ListPublishedRealmEntryPoliciesResponse;
import net.firedevops.firemud.gamedesign.v1.ResolvePublishedRealmEntryPolicyResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Closed wire and complete-set proof over isolated publication fixture vectors. */
class PublishedRealmEntryPolicyReadGrpcCodecTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void listAndResolveRoundTripTheCompleteOrderedSealedSet() throws Exception {
    PublishedRealmEntryPolicySetEvidenceTest.Fixture fixture = fixture(true);
    var listRequest = listRequest(fixture, "99999999-9999-4999-8999-999999999999");
    var resolveRequest = resolveRequest(fixture, "99999999-9999-4999-8999-999999999999", "main");

    var listWire = PublishedRealmEntryPolicyReadGrpcCodec.toRequest(listRequest);
    var resolveWire = PublishedRealmEntryPolicyReadGrpcCodec.toRequest(resolveRequest);
    assertThat(PublishedRealmEntryPolicyReadGrpcCodec.fromRequest(listWire)).isEqualTo(listRequest);
    assertThat(PublishedRealmEntryPolicyReadGrpcCodec.fromRequest(resolveWire))
        .isEqualTo(resolveRequest);

    var listResponse =
        PublishedRealmEntryPolicyReadGrpcCodec.toResponse(listRequest, fixture.set());
    var resolveResponse =
        PublishedRealmEntryPolicyReadGrpcCodec.toResponse(resolveRequest, fixture.set());
    var listed = PublishedRealmEntryPolicyReadGrpcCodec.fromResponse(listRequest, listResponse);
    var resolved =
        PublishedRealmEntryPolicyReadGrpcCodec.fromResponse(resolveRequest, resolveResponse);

    assertThat(listed).isEqualTo(fixture.set());
    assertThat(resolved).isEqualTo(fixture.set());
    assertThat(listed.policyCount()).isEqualTo(2);
    assertThat(listed.policies()).containsExactlyElementsOf(fixture.set().policies());
    assertThat(listed.policySetDigest()).isEqualTo(fixture.set().policySetDigest());
    assertThat(listed.operationBytes()).isEqualTo(fixture.set().operationBytes());
    assertThat(listed.captureBytes()).isEqualTo(fixture.set().captureBytes());
    assertThat(listed.terminalEvidenceBytes()).isEqualTo(fixture.set().terminalEvidenceBytes());
  }

  @Test
  void requestsRejectUnknownFieldsUnsupportedSchemaAndNoncanonicalIdentityOrSelectors()
      throws Exception {
    PublishedRealmEntryPolicySetEvidenceTest.Fixture fixture = fixture(false);
    var validList =
        PublishedRealmEntryPolicyReadGrpcCodec.toRequest(
            listRequest(fixture, "99999999-9999-4999-8999-999999999999"));
    var validResolve =
        PublishedRealmEntryPolicyReadGrpcCodec.toRequest(
            resolveRequest(fixture, "99999999-9999-4999-8999-999999999999", "main"));
    var unknown =
        UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
            .build();

    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                PublishedRealmEntryPolicyReadGrpcCodec.fromRequest(
                    validList.toBuilder().setUnknownFields(unknown).build()));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                PublishedRealmEntryPolicyReadGrpcCodec.fromRequest(
                    validResolve.toBuilder().setSchemaVersion(2).build()));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                PublishedRealmEntryPolicyReadGrpcCodec.fromRequest(
                    validList.toBuilder()
                        .setReadRequestId("00000000-0000-0000-0000-000000000000")
                        .build()));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                PublishedRealmEntryPolicyReadGrpcCodec.fromRequest(
                    validList.toBuilder()
                        .setCanonicalTenantId("BBBBBBBB-BBBB-4BBB-8BBB-BBBBBBBBBBBB")
                        .build()));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                PublishedRealmEntryPolicyReadGrpcCodec.fromRequest(
                    validList.toBuilder().setTargetNamespace("Test").build()));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                PublishedRealmEntryPolicyReadGrpcCodec.fromRequest(
                    validResolve.toBuilder().setWorldSlug("Main").build()));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                PublishedRealmEntryPolicyReadGrpcCodec.fromRequest(
                    validResolve.toBuilder().setRealmSlug("bad--slug").build()));
  }

  @Test
  void responsesRejectChangedEchoTargetAndMissingSelector() throws Exception {
    PublishedRealmEntryPolicySetEvidenceTest.Fixture fixture = fixture(false);
    var request = listRequest(fixture, "99999999-9999-4999-8999-999999999999");
    var response = PublishedRealmEntryPolicyReadGrpcCodec.toResponse(request, fixture.set());

    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                PublishedRealmEntryPolicyReadGrpcCodec.fromResponse(
                    request,
                    response.toBuilder()
                        .setRequest(response.getRequest().toBuilder().setTargetNamespace("other"))
                        .build()));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                PublishedRealmEntryPolicyReadGrpcCodec.fromResponse(
                    request,
                    response.toBuilder()
                        .setRequest(
                            response.getRequest().toBuilder()
                                .setReadRequestId("88888888-8888-4888-8888-888888888888"))
                        .build()));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                PublishedRealmEntryPolicyReadGrpcCodec.fromResponse(
                    request,
                    response.toBuilder()
                        .setRequest(
                            response.getRequest().toBuilder()
                                .setCanonicalTenantId("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"))
                        .build()));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                PublishedRealmEntryPolicyReadGrpcCodec.fromResponse(
                    request,
                    response.toBuilder()
                        .setRequest(
                            response.getRequest().toBuilder()
                                .setCanonicalVersionId("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"))
                        .build()));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                PublishedRealmEntryPolicyReadGrpcCodec.fromResponse(
                    request,
                    response.toBuilder()
                        .setUnknownFields(
                            UnknownFieldSet.newBuilder()
                                .addField(
                                    99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
                                .build())
                        .build()));

    var wrongTarget =
        new PublishedRealmEntryPolicyReadGrpcCodec.ListRequest(
            request.targetNamespace(),
            request.readRequestId(),
            UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
            request.canonicalVersionId());
    var substitutedEvidence =
        ListPublishedRealmEntryPoliciesResponse.newBuilder()
            .setRequest(PublishedRealmEntryPolicyReadGrpcCodec.toRequest(wrongTarget))
            .setPolicySetEvidence(ByteString.copyFrom(fixture.set().canonicalBytes()))
            .build();
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                PublishedRealmEntryPolicyReadGrpcCodec.fromResponse(
                    wrongTarget, substitutedEvidence));

    var resolve = resolveRequest(fixture, "99999999-9999-4999-8999-999999999999", "missing");
    var missingSelectorResponse =
        ResolvePublishedRealmEntryPolicyResponse.newBuilder()
            .setRequest(PublishedRealmEntryPolicyReadGrpcCodec.toRequest(resolve))
            .setPolicySetEvidence(ByteString.copyFrom(fixture.set().canonicalBytes()))
            .build();
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                PublishedRealmEntryPolicyReadGrpcCodec.fromResponse(
                    resolve, missingSelectorResponse));

    var exactResolve = resolveRequest(fixture, "99999999-9999-4999-8999-999999999999", "main");
    var exactResolveResponse =
        PublishedRealmEntryPolicyReadGrpcCodec.toResponse(exactResolve, fixture.set());
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                PublishedRealmEntryPolicyReadGrpcCodec.fromResponse(
                    exactResolve,
                    exactResolveResponse.toBuilder()
                        .setRequest(
                            exactResolveResponse.getRequest().toBuilder().setRealmSlug("side"))
                        .build()));
  }

  @Test
  void completeCarrierRejectsCountOrderAndDigestSubstitutionAtTheWireBoundary() throws Exception {
    PublishedRealmEntryPolicySetEvidenceTest.Fixture fixture = fixture(true);
    var request = listRequest(fixture, "99999999-9999-4999-8999-999999999999");
    byte[] canonical = fixture.set().canonicalBytes();
    ObjectNode root = (ObjectNode) JSON.readTree(canonical);

    ObjectNode changedCount = root.deepCopy();
    changedCount.put("policyCount", fixture.set().policyCount() + 1);
    assertRejectedSet(request, changedCount);

    ObjectNode changedOrder = root.deepCopy();
    ArrayNode reversed = JSON.createArrayNode();
    ArrayNode policies = (ArrayNode) changedOrder.get("policies");
    for (int index = policies.size() - 1; index >= 0; index--) {
      reversed.add(policies.get(index));
    }
    changedOrder.set("policies", reversed);
    assertRejectedSet(request, changedOrder);

    ObjectNode changedDigest = root.deepCopy();
    changedDigest.put("policySetDigest", "sha256:" + "f".repeat(64));
    assertRejectedSet(request, changedDigest);
  }

  private static void assertRejectedSet(
      PublishedRealmEntryPolicyReadGrpcCodec.ListRequest request, ObjectNode carrier)
      throws IOException {
    byte[] changed = Rfc8785CanonicalJson.canonicalizeUtf8(carrier.toString());
    var response =
        ListPublishedRealmEntryPoliciesResponse.newBuilder()
            .setRequest(PublishedRealmEntryPolicyReadGrpcCodec.toRequest(request))
            .setPolicySetEvidence(ByteString.copyFrom(changed))
            .build();
    assertThatIllegalArgumentException()
        .isThrownBy(() -> PublishedRealmEntryPolicyReadGrpcCodec.fromResponse(request, response));
  }

  private static PublishedRealmEntryPolicyReadGrpcCodec.ListRequest listRequest(
      PublishedRealmEntryPolicySetEvidenceTest.Fixture fixture, String readId) {
    return new PublishedRealmEntryPolicyReadGrpcCodec.ListRequest(
        "test",
        UUID.fromString(readId),
        fixture.target().canonicalTenantId(),
        fixture.target().canonicalVersionId());
  }

  private static PublishedRealmEntryPolicyReadGrpcCodec.ResolveRequest resolveRequest(
      PublishedRealmEntryPolicySetEvidenceTest.Fixture fixture, String readId, String realmSlug) {
    return new PublishedRealmEntryPolicyReadGrpcCodec.ResolveRequest(
        "test",
        UUID.fromString(readId),
        fixture.target().canonicalTenantId(),
        fixture.target().canonicalVersionId(),
        "earth",
        realmSlug);
  }

  private static PublishedRealmEntryPolicySetEvidenceTest.Fixture fixture(boolean includeSide)
      throws Exception {
    return PublishedRealmEntryPolicySetEvidenceTest.fixture(includeSide);
  }
}
