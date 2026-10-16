package unit.net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.authoring.DraftCommitOrderReadClient;
import net.firedevops.firemud.common.authoring.DraftCommitOrderReadEvidence;
import net.firedevops.firemud.common.authoring.DraftCommitOrderReadGrpcCodec;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceEvidence.OwnerBinding;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftCommitOrderVerifier;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftGraphApplicationService.CommitOrderProof;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftTerminalOperation;
import org.junit.jupiter.api.Test;

/** Synthetic client outcomes exercise the unregistered adapter only; they are not Account proof. */
class WorldDraftCommitOrderVerifierTest {
  @Test
  void derivesExactOriginalBindingAndReturnsProofOnlyAfterClientHeldResponse() throws Exception {
    var operation = operation("test");
    var client = mock(DraftCommitOrderReadClient.class);
    when(client.read(any()))
        .thenAnswer(
            invocation -> {
              var request = (DraftCommitOrderReadEvidence.Request) invocation.getArgument(0);
              return DraftCommitOrderReadGrpcCodec.fromResponse(
                  request, DraftCommitOrderReadGrpcCodec.toHeldResponse(request));
            });
    var verifier = new WorldDraftCommitOrderVerifier(client, "test");

    CommitOrderProof proof = verifier.verifyHeldOriginalCommitOrder(operation);

    assertThat(proof).isNotNull();
    var request = org.mockito.ArgumentCaptor.forClass(DraftCommitOrderReadEvidence.Request.class);
    verify(client).read(request.capture());
    assertThat(request.getValue().targetNamespace()).isEqualTo("test");
    assertThat(request.getValue().originalAccountBinding())
        .containsExactly(operation.accountBindingBytes());
    assertThat(request.getValue().readRequestId())
        .isNotIn(
            operation.operationId(),
            operation.requestId(),
            operation.commitId(),
            operation.authorizationFenceId());
  }

  @Test
  void rejectsChangedReadRequestUnavailableClientAndWrongNamespaceWithoutProof() throws Exception {
    var operation = operation("test");
    var client = mock(DraftCommitOrderReadClient.class);
    var substituted =
        DraftCommitOrderReadEvidence.Request.create("test", operation.accountBindingBytes());
    when(client.read(any()))
        .thenReturn(
            DraftCommitOrderReadGrpcCodec.fromResponse(
                substituted, DraftCommitOrderReadGrpcCodec.toHeldResponse(substituted)));
    var verifier = new WorldDraftCommitOrderVerifier(client, "test");
    assertThatThrownBy(() -> verifier.verifyHeldOriginalCommitOrder(operation))
        .isInstanceOf(WorldDesignPublicationFenceRepository.ConflictException.class)
        .hasMessageContaining("changed the exact original");

    var offline = mock(DraftCommitOrderReadClient.class);
    when(offline.read(any())).thenThrow(new IllegalStateException("Account unavailable"));
    assertThatThrownBy(
            () ->
                new WorldDraftCommitOrderVerifier(offline, "test")
                    .verifyHeldOriginalCommitOrder(operation))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unavailable");

    var namespaceClient = mock(DraftCommitOrderReadClient.class);
    var otherNamespaceOperation = operation("other");
    assertThatThrownBy(
            () ->
                new WorldDraftCommitOrderVerifier(namespaceClient, "test")
                    .verifyHeldOriginalCommitOrder(otherNamespaceOperation))
        .isInstanceOf(WorldDesignPublicationFenceRepository.ConflictException.class)
        .hasMessageContaining("namespace");
    verify(namespaceClient, never()).read(any());
  }

  private static WorldDraftTerminalOperation operation(String namespace) throws Exception {
    UUID tenant = UUID.fromString("11111111-1111-4111-8111-111111111111");
    UUID version = UUID.fromString("22222222-2222-4222-8222-222222222222");
    UUID requestId = UUID.fromString("44444444-4444-4444-8444-444444444444");
    UUID commitId = UUID.fromString("55555555-5555-4555-8555-555555555555");
    UUID operationId = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    UUID fenceId = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    DraftCommitBinding draft =
        DraftCommitBinding.create(
            new TargetProof(tenant, version, 19L, "tenant-key", 42L, "tenant-key", "NEW_GAME_ROW"),
            requestId,
            commitId,
            "base-1",
            List.of(
                new RevisionPayload(
                    "0", UUID.randomUUID(), DraftCommitBinding.Owner.WORLD_MANAGEMENT, "world"),
                new RevisionPayload(
                    "1",
                    UUID.randomUUID(),
                    DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                    "design")),
            List.of(
                new AffectedUnit(
                    DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                    "REGION",
                    "region-1",
                    "AGGREGATE",
                    "region-1",
                    "0"),
                new AffectedUnit(
                    DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                    "VERSION",
                    version.toString(),
                    "AGGREGATE",
                    version.toString(),
                    "0")));
    byte[] draftBytes = draft.canonicalBytes();
    byte[] accountBytes =
        new DraftAuthorizationFenceBinding(
                operationId,
                requestId,
                commitId,
                fenceId,
                UUID.randomUUID(),
                tenant,
                version,
                "base-1",
                "0",
                draftBytes,
                draftBytes,
                draft.digest(),
                List.of(
                    new SourceEvidence(
                        SourceKind.GLOBAL_ROLES,
                        "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
                        null,
                        "1",
                        null,
                        null,
                        new byte[] {1})))
            .canonicalBytes();
    var owner =
        new OwnerBinding(
            namespace,
            tenant,
            version,
            UUID.randomUUID(),
            19L,
            UUID.randomUUID(),
            UUID.randomUUID(),
            digest("intake"),
            UUID.randomUUID(),
            digest("source"),
            digest("receipt"));
    return new WorldDraftTerminalOperation(
        operationId, requestId, commitId, fenceId, tenant, version, draft, owner, accountBytes);
  }

  private static String digest(String value) throws Exception {
    return "sha256:"
        + HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
  }
}
