package net.firedevops.firemud.common.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationBinding;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSourceReadScope;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.automation.sourceintake.AutomationEmptySelectedSourceIntakeReceipt;
import net.firedevops.firemud.common.automation.sourceintake.AutomationSelectedSourceIntakeCommandEvidence;
import net.firedevops.firemud.common.automation.sourceintake.AutomationSelectedSourceIntakeCommandGrpcCodec;
import net.firedevops.firemud.common.gamedesign.SelectedOwnerIntakeSourceContent;
import net.firedevops.firemud.common.gamedesign.SelectedOwnerIntakeSourceTestFixtures;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeEvidence.Acknowledgement;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeEvidence.OwnerFreezePhase;
import net.firedevops.firemud.worldmanagement.v1.BeginVersionPublicationFreezeResponse;
import org.junit.jupiter.api.Test;

class AutomationSelectedSourceIntakeCommandGrpcCodecTest {
  private static final UUID ACTOR = id("dddddddd-dddd-4ddd-8ddd-dddddddddddd");

  @Test
  void roundTripsOriginalAutomationAuthorizationAndCompleteWorldFreezeOnDistinctCorrelations() {
    var freeze = freeze();
    var binding =
        authorization(freeze.request().accountBinding().input().selection().selectedCommit());
    var first =
        AutomationSelectedSourceIntakeCommandEvidence.Request.create("test", binding, freeze);
    var retry =
        AutomationSelectedSourceIntakeCommandEvidence.Request.create("test", binding, freeze);

    var firstWire = AutomationSelectedSourceIntakeCommandGrpcCodec.toRequest(first);
    var retryWire = AutomationSelectedSourceIntakeCommandGrpcCodec.toRequest(retry);

    assertThat(first.transportRequestId()).isNotEqualTo(retry.transportRequestId());
    assertThat(first.transportRequestId())
        .isNotIn(
            binding.operationId(),
            binding.fenceId(),
            binding.intakeRequestId(),
            binding.selected().requestId(),
            binding.selected().commitId(),
            freeze.acknowledgement().intakeRequestId(),
            freeze.acknowledgement().publicationFence());
    assertThat(AutomationSelectedSourceIntakeCommandGrpcCodec.fromRequest(firstWire))
        .isEqualTo(first);
    assertThat(AutomationSelectedSourceIntakeCommandGrpcCodec.fromRequest(retryWire))
        .isEqualTo(retry);
    assertThat(firstWire.getOriginalIntakeAuthorizationBinding().toByteArray())
        .isEqualTo(binding.canonicalBytes());
    assertThat(firstWire.getIntakeAuthorizationDigest()).isEqualTo(binding.digest());
    assertThat(firstWire.getFreezeRequest())
        .isEqualTo(WorldSelectedDraftPublicationFreezeGrpcCodec.toRequest(freeze.request()));
    assertThat(firstWire.getFreezeAcknowledgement())
        .isEqualTo(
            WorldSelectedDraftPublicationFreezeGrpcCodec.toResponse(freeze.acknowledgement()));
    assertThat(firstWire.getSerializedSize())
        .isLessThanOrEqualTo(AutomationSelectedSourceIntakeCommandGrpcCodec.MAX_REQUEST_WIRE_BYTES);
  }

  @Test
  void rejectsUnknownFieldsOversizedWireAndChangedFreezeOrAuthorizationScope() {
    var freeze = freeze();
    var binding =
        authorization(freeze.request().accountBinding().input().selection().selectedCommit());
    var request =
        AutomationSelectedSourceIntakeCommandEvidence.Request.create("test", binding, freeze);
    var wire = AutomationSelectedSourceIntakeCommandGrpcCodec.toRequest(request);
    var unknown =
        UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
            .build();

    assertThatThrownBy(
            () ->
                AutomationSelectedSourceIntakeCommandGrpcCodec.fromRequest(
                    wire.toBuilder().setUnknownFields(unknown).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unknown");
    assertThatThrownBy(
            () ->
                AutomationSelectedSourceIntakeCommandGrpcCodec.fromRequest(
                    wire.toBuilder().setIntakeAuthorizationDigest("0".repeat(64)).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("digest");
    assertThatThrownBy(
            () ->
                AutomationSelectedSourceIntakeCommandGrpcCodec.fromRequest(
                    wire.toBuilder()
                        .setFreezeRequest(
                            wire.getFreezeRequest().toBuilder().setUnknownFields(unknown).build())
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AutomationSelectedSourceIntakeCommandGrpcCodec.fromRequest(
                    wire.toBuilder()
                        .setOriginalIntakeAuthorizationBinding(
                            ByteString.copyFrom(
                                new byte
                                    [AutomationSelectedSourceIntakeCommandGrpcCodec
                                            .MAX_REQUEST_WIRE_BYTES
                                        + 1]))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("wire budget");

    var otherNamespaceFreeze = freeze("other");
    assertThatThrownBy(
            () ->
                new AutomationSelectedSourceIntakeCommandEvidence.Request(
                    AutomationSelectedSourceIntakeCommandEvidence.Request.SCHEMA_VERSION,
                    "test",
                    id("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
                    binding,
                    otherNamespaceFreeze))
        .isInstanceOf(IllegalArgumentException.class);
    var wrongOwner =
        authorization(
            freeze.request().accountBinding().input().selection().selectedCommit(),
            Owner.ENTITY_MANAGEMENT);
    assertThatThrownBy(
            () ->
                AutomationSelectedSourceIntakeCommandEvidence.Request.create(
                    "test", wrongOwner, freeze))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void responseRequiresExactFullRequestEchoAndEncodesReceiptBytesAndDigest() {
    var freeze = freeze();
    var binding =
        authorization(freeze.request().accountBinding().input().selection().selectedCommit());
    var request =
        AutomationSelectedSourceIntakeCommandEvidence.Request.create("test", binding, freeze);
    var receipt = mock(AutomationEmptySelectedSourceIntakeReceipt.class);
    byte[] storedReceipt = {1, 2, 3};
    when(receipt.canonicalBytes()).thenReturn(storedReceipt);
    when(receipt.receiptDigest()).thenReturn("a".repeat(64));
    when(receipt.authorizationBindingBytes()).thenReturn(binding.canonicalBytes());
    when(receipt.authorizationBindingDigest()).thenReturn(binding.digest());
    var evidence = new AutomationSelectedSourceIntakeCommandEvidence(request, receipt);
    var response = AutomationSelectedSourceIntakeCommandGrpcCodec.toResponse(evidence);

    assertThat(response.getRequest())
        .isEqualTo(AutomationSelectedSourceIntakeCommandGrpcCodec.toRequest(request));
    assertThat(response.getCommittedEmptyReceipt().toByteArray()).isEqualTo(storedReceipt);
    assertThat(response.getReceiptDigest()).isEqualTo("a".repeat(64));

    var changedFreeze =
        response.toBuilder()
            .setRequest(
                response.getRequest().toBuilder()
                    .setFreezeAcknowledgement(
                        response.getRequest().getFreezeAcknowledgement().toBuilder()
                            .setContentDigest("b".repeat(64)))
                    .build())
            .build();
    assertThatThrownBy(
            () ->
                AutomationSelectedSourceIntakeCommandGrpcCodec.fromResponse(request, changedFreeze))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("changed");
    assertThatThrownBy(
            () ->
                AutomationSelectedSourceIntakeCommandGrpcCodec.fromResponse(
                    request,
                    response.toBuilder()
                        .setUnknownFields(
                            UnknownFieldSet.newBuilder()
                                .addField(
                                    99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
                                .build())
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unknown");
  }

  private static SelectedOwnerIntakeAuthorizationBinding authorization(
      DraftCommitBinding selected) {
    return authorization(selected, Owner.AUTOMATION_SCRIPTING);
  }

  private static SelectedOwnerIntakeAuthorizationBinding authorization(
      DraftCommitBinding selected, Owner owner) {
    var scope =
        new SelectedOwnerIntakeSourceReadScope(
            owner,
            "test",
            id("66666666-6666-4666-8666-666666666666"),
            id("77777777-7777-4777-8777-777777777777"),
            id("88888888-8888-4888-8888-888888888888"),
            ACTOR,
            selected);
    var contentBytes = new ByteArrayOutputStream();
    frame(contentBytes, SelectedOwnerIntakeSourceContent.DOMAIN);
    frame(contentBytes, scope.canonicalBytes());
    frame(contentBytes, scope.digest());
    for (String family :
        List.of(
            "COMMAND", "REALM_POLICY", "ASSET", "GAMEPLAY_RULE", "BRANDING", "TEMPLATE_CONFIG")) {
      byte[] snapshot = SelectedOwnerIntakeSourceTestFixtures.snapshot(family, selected);
      frame(contentBytes, family);
      frame(contentBytes, snapshot);
      frame(contentBytes, DraftAuthorizationFenceBinding.digest(snapshot));
    }
    byte[] canonicalContent = contentBytes.toByteArray();
    var content =
        SelectedOwnerIntakeSourceContent.fromStored(
            canonicalContent, scope, DraftAuthorizationFenceBinding.digest(canonicalContent));
    return new SelectedOwnerIntakeAuthorizationBinding(
        content,
        List.of(
            source(SourceKind.ACCOUNT, ACTOR.toString()),
            source(SourceKind.TENANT, selected.target().canonicalTenantId().toString()),
            source(SourceKind.MEMBERSHIP, ACTOR + "/" + selected.target().canonicalTenantId())));
  }

  private static SourceEvidence source(SourceKind kind, String scope) {
    return new SourceEvidence(kind, scope, null, "1", null, null, new byte[] {1});
  }

  private static WorldSelectedDraftPublicationFreezeEvidence freeze() {
    return freeze("test");
  }

  private static WorldSelectedDraftPublicationFreezeEvidence freeze(String namespace) {
    var original = WorldSelectedDraftPublicationFreezeGrpcCodecTest.request();
    if (!namespace.equals(original.targetNamespace())) {
      // The fixture's Account publication binding is namespace-independent; rebuild only its
      // request tuple through the same constructor used by the production value.
      original =
          WorldSelectedDraftPublicationFreezeEvidence.Request.create(
              namespace,
              original.canonicalTenantId(),
              original.canonicalVersionId(),
              original.publicationRequestId(),
              original.expectedVersionStateEpoch(),
              original.requestDigest(),
              original.accountBinding());
    }
    var acknowledgement =
        new Acknowledgement(
            original,
            id("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
            original.expectedVersionStateEpoch(),
            id("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
            OwnerFreezePhase.FROZEN,
            original.accountBinding().input().selection().selectedCommit().commitId().toString(),
            "a".repeat(64),
            3);
    BeginVersionPublicationFreezeResponse response =
        WorldSelectedDraftPublicationFreezeGrpcCodec.toResponse(acknowledgement);
    return WorldSelectedDraftPublicationFreezeGrpcCodec.fromResponse(original, response);
  }

  private static void frame(ByteArrayOutputStream output, String value) {
    DraftAuthorizationFenceBinding.frame(output, value);
  }

  private static void frame(ByteArrayOutputStream output, byte[] value) {
    DraftAuthorizationFenceBinding.frame(output, value);
  }

  private static UUID id(String value) {
    return UUID.fromString(value);
  }
}
