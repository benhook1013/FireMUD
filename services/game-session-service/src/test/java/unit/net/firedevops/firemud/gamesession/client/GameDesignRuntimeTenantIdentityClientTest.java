package net.firedevops.firemud.gamesession.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.UnknownFieldSet;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.tenant.GameSessionTenantAssociationEvidence;
import net.firedevops.firemud.common.tenant.RuntimeTenantIdentityEvidence;
import net.firedevops.firemud.gamedesign.v1.GameSessionTenantAssociationManifestEvidence;
import net.firedevops.firemud.gamedesign.v1.ResolveAuthoredWorldSourceRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveAuthoredWorldSourceResponse;
import net.firedevops.firemud.gamedesign.v1.ResolveLegacyGameSessionTenantAssociationRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveLegacyGameSessionTenantAssociationResponse;
import net.firedevops.firemud.gamedesign.v1.ResolveRuntimeTenantIdentityRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveRuntimeTenantIdentityResponse;
import net.firedevops.firemud.gamedesign.v1.TenantIdentityServiceGrpc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class GameDesignRuntimeTenantIdentityClientTest {
  private static final String NAMESPACE = "test";
  private static final UUID TENANT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID REQUEST_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final String SOURCE_KEY = "game-design-tenant-91";
  private static final long SOURCE_ROW_ID = 91L;
  private static final UUID SOURCE_OPERATION_ID =
      UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID REGISTRATION_REQUEST_ID =
      UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final String LEGACY_GAME_SESSION_TENANT_ID = "23";
  // The client checks canonical Ed25519 signature shape, not signature trust or validity.
  private static final String SIGNATURE_SHAPE_FIXTURE =
      Base64.getEncoder().encodeToString(new byte[64]);
  private static final String ASSOCIATION_SOURCE_DIGEST = "sha256:" + "a".repeat(64);

  @Test
  void authoredSourceReadBindsCompleteReceiptAndSeparateReadIdentity() throws Exception {
    TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub stub = mockStub();
    when(stub.resolveAuthoredWorldSource(any())).thenReturn(authoredResponse());
    GameDesignRuntimeTenantIdentityClient client = newClient(stub);
    AuthoredWorldSourceEvidence first =
        client.resolveAuthoredWorldSource(
            TENANT_ID.toString(),
            SOURCE_OPERATION_ID.toString(),
            "world-one",
            REQUEST_ID.toString());
    AuthoredWorldSourceEvidence retry =
        client.resolveAuthoredWorldSource(
            TENANT_ID.toString(),
            SOURCE_OPERATION_ID.toString(),
            "world-one",
            REQUEST_ID.toString());
    assertThat(first).isEqualTo(retry);
    assertThat(first.registrationRequestId()).isEqualTo(REGISTRATION_REQUEST_ID);
    assertThat(first.tenantSlug()).isEqualTo("tenant-one");
    assertThat(first.worldDisplayName()).isEqualTo("Wörld");
    ArgumentCaptor<ResolveAuthoredWorldSourceRequest> captor =
        ArgumentCaptor.forClass(ResolveAuthoredWorldSourceRequest.class);
    verify(stub, times(2)).resolveAuthoredWorldSource(captor.capture());
    assertThat(captor.getAllValues())
        .allSatisfy(
            request -> {
              assertThat(request.getRequestId()).isEqualTo(REQUEST_ID.toString());
              assertThat(request.getOperationId()).isEqualTo(SOURCE_OPERATION_ID.toString());
              assertThat(request.getCanonicalTenantId()).isEqualTo(TENANT_ID.toString());
              assertThat(request.getWorldSlug()).isEqualTo("world-one");
            });
    verify(stub, never()).resolveRuntimeTenantIdentity(any());
    verify(stub, never()).resolveFreshTenantCreation(any());
    verify(stub, never()).resolveLegacyAccountTenantAssociation(any());
  }

  @Test
  void authoredSourceRejectsChangedEchoUnknownFieldsAndEveryChangedReceiptField() throws Exception {
    ResolveAuthoredWorldSourceResponse valid = authoredResponse();
    for (ResolveAuthoredWorldSourceResponse response :
        List.of(
            valid.toBuilder().setSchemaVersion(2).build(),
            valid.toBuilder().setTargetNamespace("other").build(),
            valid.toBuilder().setRequestId(REGISTRATION_REQUEST_ID.toString()).build(),
            valid.toBuilder().setRegistrationRequestId(REQUEST_ID.toString()).build(),
            valid.toBuilder().setOperationId(REQUEST_ID.toString()).build(),
            valid.toBuilder().setRequestDigest("sha256:" + "0".repeat(64)).build(),
            valid.toBuilder().setCanonicalTenantId(REQUEST_ID.toString()).build(),
            valid.toBuilder().setTenantSlug("tenant-other").build(),
            valid.toBuilder().setWorldSlug("world-other").build(),
            valid.toBuilder().setWorldDisplayName("World").build(),
            valid.toBuilder().setSourceGameRowId(92L).build(),
            valid.toBuilder().setSourceGameTenantKey("changed").build(),
            valid.toBuilder().setProvenanceKind("RETAINED_GAME_V30").build(),
            valid.toBuilder().setEvidenceDigest("sha256:" + "0".repeat(64)).build(),
            valid.toBuilder()
                .setUnknownFields(
                    UnknownFieldSet.newBuilder()
                        .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
                        .build())
                .build())) {
      TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub stub = mockStub();
      when(stub.resolveAuthoredWorldSource(any())).thenReturn(response);
      GameDesignRuntimeTenantIdentityClient client = newClient(stub);
      assertThatThrownBy(
              () ->
                  client.resolveAuthoredWorldSource(
                      TENANT_ID.toString(),
                      SOURCE_OPERATION_ID.toString(),
                      "world-one",
                      REQUEST_ID.toString()))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void authoredSourceRejectsCoherentReceiptsForAnotherNamespaceTenantOperationOrWorld()
      throws Exception {
    for (ResolveAuthoredWorldSourceResponse response :
        List.of(
            authoredResponse("other", TENANT_ID, SOURCE_OPERATION_ID, "world-one"),
            authoredResponse(NAMESPACE, REQUEST_ID, SOURCE_OPERATION_ID, "world-one"),
            authoredResponse(NAMESPACE, TENANT_ID, REQUEST_ID, "world-one"),
            authoredResponse(NAMESPACE, TENANT_ID, SOURCE_OPERATION_ID, "other-world"))) {
      TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub stub = mockStub();
      when(stub.resolveAuthoredWorldSource(any())).thenReturn(response);
      GameDesignRuntimeTenantIdentityClient client = newClient(stub);
      assertThatThrownBy(
              () ->
                  client.resolveAuthoredWorldSource(
                      TENANT_ID.toString(),
                      SOURCE_OPERATION_ID.toString(),
                      "world-one",
                      REQUEST_ID.toString()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("does not match the exact request");
    }
  }

  @Test
  void authoredSourceValidatesRequestBeforeStubAndPreservesUnavailableStatus() throws Exception {
    TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub stub = mockStub();
    GameDesignRuntimeTenantIdentityClient client = newClient(stub);
    assertThatThrownBy(
            () ->
                client.resolveAuthoredWorldSource(
                    "91", SOURCE_OPERATION_ID.toString(), "world-one", REQUEST_ID.toString()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                client.resolveAuthoredWorldSource(
                    TENANT_ID.toString(), "3-3-3-3-3", "world-one", REQUEST_ID.toString()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                client.resolveAuthoredWorldSource(
                    TENANT_ID.toString(),
                    SOURCE_OPERATION_ID.toString(),
                    "World",
                    REQUEST_ID.toString()))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(stub);
    StatusRuntimeException unavailable =
        Status.UNAVAILABLE.withDescription("owner unavailable").asRuntimeException();
    when(stub.resolveAuthoredWorldSource(any())).thenThrow(unavailable);
    assertThatThrownBy(
            () ->
                client.resolveAuthoredWorldSource(
                    TENANT_ID.toString(),
                    SOURCE_OPERATION_ID.toString(),
                    "world-one",
                    REQUEST_ID.toString()))
        .isSameAs(unavailable);
  }

  @Test
  void retainedAssociationReadReturnsCompleteImmutableReceiptAndRetriesExactly() throws Exception {
    TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub stub = mockStub();
    ResolveLegacyGameSessionTenantAssociationResponse response = associationResponse();
    when(stub.resolveLegacyGameSessionTenantAssociation(any()))
        .thenReturn(response)
        .thenReturn(response);
    GameDesignRuntimeTenantIdentityClient client = newClient(stub);

    GameDesignRuntimeTenantIdentityClient.LegacyGameSessionTenantAssociationReceipt first =
        client.resolveLegacyGameSessionTenantAssociation(
            TENANT_ID.toString(),
            SOURCE_OPERATION_ID.toString(),
            LEGACY_GAME_SESSION_TENANT_ID,
            REQUEST_ID.toString());
    GameDesignRuntimeTenantIdentityClient.LegacyGameSessionTenantAssociationReceipt retry =
        client.resolveLegacyGameSessionTenantAssociation(
            TENANT_ID.toString(),
            SOURCE_OPERATION_ID.toString(),
            LEGACY_GAME_SESSION_TENANT_ID,
            REQUEST_ID.toString());

    assertThat(first).isEqualTo(retry);
    assertThat(first.evidence()).isEqualTo(associationEvidence());
    assertThat(first.manifestDigest()).isEqualTo(associationEvidence().manifestDigest());
    assertThat(first.ed25519Signature()).isEqualTo(SIGNATURE_SHAPE_FIXTURE);
    ArgumentCaptor<ResolveLegacyGameSessionTenantAssociationRequest> requestCaptor =
        ArgumentCaptor.forClass(ResolveLegacyGameSessionTenantAssociationRequest.class);
    verify(stub, times(2)).withDeadlineAfter(5L, TimeUnit.SECONDS);
    verify(stub, times(2)).resolveLegacyGameSessionTenantAssociation(requestCaptor.capture());
    assertThat(requestCaptor.getAllValues())
        .allSatisfy(
            request -> {
              assertThat(request.getRequestId()).isEqualTo(REQUEST_ID.toString());
              assertThat(request.getOperationId()).isEqualTo(SOURCE_OPERATION_ID.toString());
              assertThat(request.getCanonicalTenantId()).isEqualTo(TENANT_ID.toString());
              assertThat(request.getLegacyGameSessionTenantId())
                  .isEqualTo(LEGACY_GAME_SESSION_TENANT_ID);
            });
    verify(stub, never()).resolveRuntimeTenantIdentity(any());
    verify(stub, never()).resolveAuthoredWorldSource(any());
    verify(stub, never()).resolveLegacyAccountTenantAssociation(any());
  }

  @Test
  void retainedAssociationRejectsAliasesNilAndBigintOverflowBeforeRpc() {
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    GameDesignRuntimeTenantIdentityClient client = newClientWithoutStub(channelFactory);
    List<String[]> invalidRequests =
        List.of(
            new String[] {
              "AB426BB3-A733-43F0-9C8E-2E379CBDF7EC",
              SOURCE_OPERATION_ID.toString(),
              "9",
              REQUEST_ID.toString()
            },
            new String[] {
              "00000000-0000-0000-0000-000000000000",
              SOURCE_OPERATION_ID.toString(),
              "9",
              REQUEST_ID.toString()
            },
            new String[] {
              TENANT_ID.toString(),
              "33333333-3333-4333-8333-33333333333",
              "9",
              REQUEST_ID.toString()
            },
            new String[] {
              TENANT_ID.toString(),
              "00000000-0000-0000-0000-000000000000",
              "9",
              REQUEST_ID.toString()
            },
            new String[] {
              TENANT_ID.toString(), SOURCE_OPERATION_ID.toString(), "0", REQUEST_ID.toString()
            },
            new String[] {
              TENANT_ID.toString(), SOURCE_OPERATION_ID.toString(), "09", REQUEST_ID.toString()
            },
            new String[] {
              TENANT_ID.toString(), SOURCE_OPERATION_ID.toString(), "+9", REQUEST_ID.toString()
            },
            new String[] {
              TENANT_ID.toString(),
              SOURCE_OPERATION_ID.toString(),
              "9223372036854775808",
              REQUEST_ID.toString()
            },
            new String[] {
              TENANT_ID.toString(),
              SOURCE_OPERATION_ID.toString(),
              "9",
              "22222222-2222-4222-8222-22222222222"
            },
            new String[] {
              TENANT_ID.toString(),
              SOURCE_OPERATION_ID.toString(),
              "9",
              "00000000-0000-0000-0000-000000000000"
            });

    for (String[] invalid : invalidRequests) {
      assertThatThrownBy(
              () ->
                  client.resolveLegacyGameSessionTenantAssociation(
                      invalid[0], invalid[1], invalid[2], invalid[3]))
          .isInstanceOf(IllegalArgumentException.class);
    }
    verifyNoInteractions(channelFactory);
  }

  @Test
  void retainedAssociationRejectsMissingManifestAndUnknownEnvelopeFields() throws Exception {
    assertAssociationRejected(
        ResolveLegacyGameSessionTenantAssociationResponse.newBuilder()
            .setRequestId(REQUEST_ID.toString())
            .build(),
        "has no manifest");
    assertAssociationRejected(
        associationResponse().toBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                    .build())
            .build(),
        "unsupported fields");
    GameSessionTenantAssociationManifestEvidence manifestWithUnknownField =
        associationResponse().getManifest().toBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                    .build())
            .build();
    assertAssociationRejected(
        associationResponse().toBuilder().setManifest(manifestWithUnknownField).build(),
        "manifest contains unsupported fields");
  }

  @Test
  void retainedAssociationRejectsEveryChangedManifestFieldAgainstOriginalDigest() throws Exception {
    ResolveLegacyGameSessionTenantAssociationResponse valid = associationResponse();
    GameSessionTenantAssociationManifestEvidence manifest = valid.getManifest();
    List<GameSessionTenantAssociationManifestEvidence> changedManifests =
        List.of(
            manifest.toBuilder().setOperationId(REQUEST_ID.toString()).build(),
            manifest.toBuilder().setTargetNamespace("other").build(),
            manifest.toBuilder().setSignerKeyId("other-signer").build(),
            manifest.toBuilder().setApprovedBy("other-approver").build(),
            manifest.toBuilder().setApprovalReference("other-reference").build(),
            manifest.toBuilder().setSignedAt("2026-09-30T12:01:00Z").build(),
            manifest.toBuilder().setLegacyGameSessionTenantId("24").build(),
            manifest.toBuilder().setCanonicalTenantId(REQUEST_ID.toString()).build(),
            manifest.toBuilder().setSourceGameRowId("92").build(),
            manifest.toBuilder().setSourceGameTenantKey("game-design-tenant-92").build(),
            manifest.toBuilder().setProvenanceKind("NEW_GAME_ROW").build(),
            manifest.toBuilder().setGameSessionEvidenceDigest("sha256:" + "b".repeat(64)).build());

    for (GameSessionTenantAssociationManifestEvidence changed : changedManifests) {
      assertAssociationRejected(valid.toBuilder().setManifest(changed).build(), "manifest digest");
    }
    assertAssociationRejected(
        valid.toBuilder().setManifest(manifest.toBuilder().setSchemaVersion(2).build()).build(),
        "manifest is invalid");
  }

  @Test
  void retainedAssociationRejectsChangedEchoAndRecomputedForeignRequestBindings() throws Exception {
    ResolveLegacyGameSessionTenantAssociationResponse valid = associationResponse();
    assertAssociationRejected(
        valid.toBuilder().setRequestId(SOURCE_OPERATION_ID.toString()).build(),
        "exact request echo");

    List<GameSessionTenantAssociationEvidence> foreignEvidence =
        List.of(
            associationEvidenceWith(
                REQUEST_ID.toString(), NAMESPACE, LEGACY_GAME_SESSION_TENANT_ID, TENANT_ID),
            associationEvidenceWith(
                SOURCE_OPERATION_ID.toString(), "other", LEGACY_GAME_SESSION_TENANT_ID, TENANT_ID),
            associationEvidenceWith(SOURCE_OPERATION_ID.toString(), NAMESPACE, "24", TENANT_ID),
            associationEvidenceWith(
                SOURCE_OPERATION_ID.toString(),
                NAMESPACE,
                LEGACY_GAME_SESSION_TENANT_ID,
                REQUEST_ID));
    for (GameSessionTenantAssociationEvidence evidence : foreignEvidence) {
      assertAssociationRejected(associationResponse(evidence), "exact request");
    }
  }

  @Test
  void retainedAssociationRejectsInvalidSignatureAndDigestAndPropagatesUnavailable()
      throws Exception {
    ResolveLegacyGameSessionTenantAssociationResponse valid = associationResponse();
    assertAssociationRejected(
        valid.toBuilder().setEd25519Signature("not-base64").build(), "signature is invalid");
    assertAssociationRejected(
        valid.toBuilder()
            .setEd25519Signature(
                SIGNATURE_SHAPE_FIXTURE.substring(0, SIGNATURE_SHAPE_FIXTURE.length() - 2))
            .build(),
        "signature is not canonical");
    assertAssociationRejected(
        valid.toBuilder()
            .setEd25519Signature(Base64.getEncoder().encodeToString(new byte[63]))
            .build(),
        "signature is not canonical");
    assertAssociationRejected(
        valid.toBuilder().setManifestDigest("sha256:" + "0".repeat(64)).build(), "manifest digest");

    TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub stub = mockStub();
    StatusRuntimeException unavailable =
        Status.UNAVAILABLE.withDescription("Game Design owner unavailable").asRuntimeException();
    when(stub.resolveLegacyGameSessionTenantAssociation(any())).thenThrow(unavailable);
    GameDesignRuntimeTenantIdentityClient client = newClient(stub);
    assertThatThrownBy(
            () ->
                client.resolveLegacyGameSessionTenantAssociation(
                    TENANT_ID.toString(),
                    SOURCE_OPERATION_ID.toString(),
                    LEGACY_GAME_SESSION_TENANT_ID,
                    REQUEST_ID.toString()))
        .isSameAs(unavailable);
    verify(stub).withDeadlineAfter(5L, TimeUnit.SECONDS);
  }

  @Test
  void retainedAssociationRejectsUseBeforeClientInitialization() {
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    GameDesignRuntimeTenantIdentityClient client = newClientWithoutStub(channelFactory);
    assertThatThrownBy(
            () ->
                client.resolveLegacyGameSessionTenantAssociation(
                    TENANT_ID.toString(),
                    SOURCE_OPERATION_ID.toString(),
                    LEGACY_GAME_SESSION_TENANT_ID,
                    REQUEST_ID.toString()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not initialized");
    verifyNoInteractions(channelFactory);
  }

  private static ResolveAuthoredWorldSourceResponse authoredResponse() {
    return authoredResponse(NAMESPACE, TENANT_ID, SOURCE_OPERATION_ID, "world-one");
  }

  private static ResolveAuthoredWorldSourceResponse authoredResponse(
      String namespace, UUID tenantId, UUID operationId, String worldSlug) {
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            namespace, REGISTRATION_REQUEST_ID, tenantId, "tenant-one", worldSlug, "Wörld");
    String evidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            namespace,
            REGISTRATION_REQUEST_ID,
            operationId,
            requestDigest,
            tenantId,
            "tenant-one",
            worldSlug,
            "Wörld",
            SOURCE_ROW_ID,
            SOURCE_KEY,
            "NEW_GAME_ROW");
    return ResolveAuthoredWorldSourceResponse.newBuilder()
        .setSchemaVersion(1)
        .setTargetNamespace(namespace)
        .setRequestId(REQUEST_ID.toString())
        .setRegistrationRequestId(REGISTRATION_REQUEST_ID.toString())
        .setOperationId(operationId.toString())
        .setRequestDigest(requestDigest)
        .setCanonicalTenantId(tenantId.toString())
        .setTenantSlug("tenant-one")
        .setWorldSlug(worldSlug)
        .setWorldDisplayName("Wörld")
        .setSourceGameRowId(SOURCE_ROW_ID)
        .setSourceGameTenantKey(SOURCE_KEY)
        .setProvenanceKind("NEW_GAME_ROW")
        .setEvidenceDigest(evidenceDigest)
        .build();
  }

  private static GameSessionTenantAssociationEvidence associationEvidence() {
    return associationEvidenceWith(
        SOURCE_OPERATION_ID.toString(), NAMESPACE, LEGACY_GAME_SESSION_TENANT_ID, TENANT_ID);
  }

  private static GameSessionTenantAssociationEvidence associationEvidenceWith(
      String operationId, String namespace, String retainedTenantKey, UUID canonicalTenantId) {
    return new GameSessionTenantAssociationEvidence(
        1,
        UUID.fromString(operationId),
        namespace,
        "owner-key-1",
        "owner@example.test",
        "approval-2026-09",
        "2026-09-30T12:00:00Z",
        retainedTenantKey,
        canonicalTenantId,
        "91",
        SOURCE_KEY,
        "RETAINED_GAME_V30",
        ASSOCIATION_SOURCE_DIGEST);
  }

  private static ResolveLegacyGameSessionTenantAssociationResponse associationResponse() {
    return associationResponse(associationEvidence());
  }

  private static ResolveLegacyGameSessionTenantAssociationResponse associationResponse(
      GameSessionTenantAssociationEvidence evidence) {
    GameSessionTenantAssociationManifestEvidence manifest =
        GameSessionTenantAssociationManifestEvidence.newBuilder()
            .setSchemaVersion(evidence.schemaVersion())
            .setOperationId(evidence.operationId().toString())
            .setTargetNamespace(evidence.targetNamespace())
            .setSignerKeyId(evidence.signerKeyId())
            .setApprovedBy(evidence.approvedBy())
            .setApprovalReference(evidence.approvalReference())
            .setSignedAt(evidence.signedAt())
            .setLegacyGameSessionTenantId(evidence.legacyGameSessionTenantId())
            .setCanonicalTenantId(evidence.canonicalTenantId().toString())
            .setSourceGameRowId(evidence.sourceGameRowId())
            .setSourceGameTenantKey(evidence.sourceGameTenantKey())
            .setProvenanceKind(evidence.provenanceKind())
            .setGameSessionEvidenceDigest(evidence.gameSessionEvidenceDigest())
            .build();
    return ResolveLegacyGameSessionTenantAssociationResponse.newBuilder()
        .setRequestId(REQUEST_ID.toString())
        .setManifest(manifest)
        .setManifestDigest(evidence.manifestDigest())
        .setEd25519Signature(SIGNATURE_SHAPE_FIXTURE)
        .build();
  }

  private static void assertAssociationRejected(
      ResolveLegacyGameSessionTenantAssociationResponse response, String message) throws Exception {
    TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub stub = mockStub();
    when(stub.resolveLegacyGameSessionTenantAssociation(any())).thenReturn(response);
    GameDesignRuntimeTenantIdentityClient client = newClient(stub);

    assertThatThrownBy(
            () ->
                client.resolveLegacyGameSessionTenantAssociation(
                    TENANT_ID.toString(),
                    SOURCE_OPERATION_ID.toString(),
                    LEGACY_GAME_SESSION_TENANT_ID,
                    REQUEST_ID.toString()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(message);
  }

  @Test
  void exactOwnerResponseIsTypedAndStableOnRetryForBothProvenanceKinds() throws Exception {
    TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub stub = mockStub();
    when(stub.resolveRuntimeTenantIdentity(any()))
        .thenReturn(validResponse("NEW_GAME_ROW"))
        .thenReturn(validResponse("NEW_GAME_ROW"))
        .thenReturn(validResponse("RETAINED_GAME_V30"))
        .thenReturn(validResponse("RETAINED_GAME_V30"));
    GameDesignRuntimeTenantIdentityClient client = newClient(stub);

    RuntimeTenantIdentityEvidence first =
        client.resolveRuntimeTenantIdentity(TENANT_ID.toString(), REQUEST_ID.toString());
    RuntimeTenantIdentityEvidence retry =
        client.resolveRuntimeTenantIdentity(TENANT_ID.toString(), REQUEST_ID.toString());

    assertThat(first).isEqualTo(retry);
    assertThat(first.schemaVersion()).isEqualTo(1);
    assertThat(first.targetNamespace()).isEqualTo(NAMESPACE);
    assertThat(first.requestId()).isEqualTo(REQUEST_ID);
    assertThat(first.canonicalTenantId()).isEqualTo(TENANT_ID);
    assertThat(first.sourceGameRowId()).isEqualTo(SOURCE_ROW_ID);
    assertThat(first.sourceGameTenantKey()).isEqualTo(SOURCE_KEY);
    assertThat(first.provenanceKind()).isEqualTo("NEW_GAME_ROW");

    ArgumentCaptor<ResolveRuntimeTenantIdentityRequest> requestCaptor =
        ArgumentCaptor.forClass(ResolveRuntimeTenantIdentityRequest.class);
    verify(stub, times(2)).withDeadlineAfter(5L, TimeUnit.SECONDS);
    verify(stub, times(2)).resolveRuntimeTenantIdentity(requestCaptor.capture());
    assertThat(requestCaptor.getAllValues())
        .allSatisfy(
            request -> {
              assertThat(request.getCanonicalTenantId()).isEqualTo(TENANT_ID.toString());
              assertThat(request.getRequestId()).isEqualTo(REQUEST_ID.toString());
            });
    verify(stub, never()).resolveLegacyAccountTenantAssociation(any());
    verify(stub, never()).resolveLegacyGameTenantIdentity(any());
    verify(stub, never()).resolveFreshTenantCreation(any());

    // A separate retained-row response remains typed owner provenance, not another authority.
    RuntimeTenantIdentityEvidence retained =
        client.resolveRuntimeTenantIdentity(TENANT_ID.toString(), REQUEST_ID.toString());
    RuntimeTenantIdentityEvidence retainedRetry =
        client.resolveRuntimeTenantIdentity(TENANT_ID.toString(), REQUEST_ID.toString());
    assertThat(retained).isEqualTo(retainedRetry);
    assertThat(retained.provenanceKind()).isEqualTo("RETAINED_GAME_V30");
    assertThat(retained.sourceGameRowId()).isEqualTo(SOURCE_ROW_ID);
    assertThat(retained.sourceGameTenantKey()).isEqualTo(SOURCE_KEY);
  }

  @Test
  void rejectsMalformedRequestsBeforeAnyStubOrChannelUse() {
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    GameDesignRuntimeTenantIdentityClient client = newClientWithoutStub(channelFactory);
    List<String[]> invalidRequests =
        List.of(
            new String[] {null, REQUEST_ID.toString()},
            new String[] {"", REQUEST_ID.toString()},
            new String[] {"AB426BB3-A733-43F0-9C8E-2E379CBDF7EC", REQUEST_ID.toString()},
            new String[] {"11111111-1111-4111-8111-11111111111", REQUEST_ID.toString()},
            new String[] {"11111111-1111-4111-8111-111111111111", ""},
            new String[] {TENANT_ID.toString(), "22222222-2222-4222-8222-22222222222"},
            new String[] {TENANT_ID.toString(), "00000000-0000-0000-0000-000000000000"});

    for (String[] invalid : invalidRequests) {
      assertThatThrownBy(() -> client.resolveRuntimeTenantIdentity(invalid[0], invalid[1]))
          .isInstanceOf(IllegalArgumentException.class);
    }
    verifyNoInteractions(channelFactory);
  }

  @Test
  void rejectsEveryResponseFieldThatDoesNotMatchTheExactRequestOrClosedSchema() throws Exception {
    ResolveRuntimeTenantIdentityResponse valid = validResponse("NEW_GAME_ROW");
    assertRejected(valid.toBuilder().setSchemaVersion(2).build());
    assertRejected(valid.toBuilder().setTargetNamespace("other").build());
    assertRejected(
        valid.toBuilder().setCanonicalTenantId("AB426BB3-A733-43F0-9C8E-2E379CBDF7EC").build());
    assertRejected(valid.toBuilder().setRequestId("33333333-3333-4333-8333-333333333333").build());
    assertRejected(
        valid.toBuilder().setCanonicalTenantId("33333333-3333-4333-8333-333333333333").build());
    assertRejected(valid.toBuilder().setSourceGameRowId(0L).build());
    assertRejected(valid.toBuilder().setSourceGameTenantKey("").build());
    assertRejected(valid.toBuilder().setProvenanceKind("ACCOUNT_ASSOCIATION").build());
    assertRejected(valid.toBuilder().setRequestId("22222222-2222-4222-8222-22222222222").build());
    assertRejected(
        valid.toBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                    .build())
            .build());
  }

  @Test
  void propagatesTransportFailureWithBoundedDeadline() throws Exception {
    TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub stub = mockStub();
    StatusRuntimeException unavailable =
        new StatusRuntimeException(Status.UNAVAILABLE.withDescription("Game Design unavailable"));
    when(stub.resolveRuntimeTenantIdentity(any())).thenThrow(unavailable);
    GameDesignRuntimeTenantIdentityClient client = newClient(stub);

    assertThatThrownBy(
            () -> client.resolveRuntimeTenantIdentity(TENANT_ID.toString(), REQUEST_ID.toString()))
        .isSameAs(unavailable);

    verify(stub).withDeadlineAfter(5L, TimeUnit.SECONDS);
  }

  @Test
  void rejectsPlaintextIncompleteAndClasspathTlsBeforeChannelCreation() {
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    CommonGrpcClientProperties plaintext = mtlsProperties();
    plaintext.setPlaintext(true);

    assertThatThrownBy(
            () ->
                new GameDesignRuntimeTenantIdentityClient(
                    new ServiceEndpointsProperties(), plaintext, channelFactory, NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mTLS");
    assertThatThrownBy(
            () ->
                new GameDesignRuntimeTenantIdentityClient(
                    new ServiceEndpointsProperties(), null, channelFactory, NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("TLS configuration is required");

    for (CommonGrpcClientProperties incomplete :
        List.of(
            incompleteMtlsProperties(null, "client.key", "server-ca.crt"),
            incompleteMtlsProperties("client.crt", null, "server-ca.crt"),
            incompleteMtlsProperties("client.crt", "client.key", null))) {
      assertThatThrownBy(
              () ->
                  new GameDesignRuntimeTenantIdentityClient(
                      new ServiceEndpointsProperties(), incomplete, channelFactory, NAMESPACE))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("certificate, key, and CA");
    }

    CommonGrpcClientProperties classpath = mtlsProperties();
    classpath.setCaCert("classpath:certs/ca.crt");
    assertThatThrownBy(
            () ->
                new GameDesignRuntimeTenantIdentityClient(
                    new ServiceEndpointsProperties(), classpath, channelFactory, NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("file-backed");
    verifyNoInteractions(channelFactory);
  }

  @Test
  void initUsesConfiguredGameDesignTargetAndFileBackedTls(@TempDir Path directory)
      throws Exception {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    endpoints.setGameDesignService("game-design.internal:6565");
    CommonGrpcClientProperties tls = mtlsProperties(directory);
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    ManagedChannel channel = mock(ManagedChannel.class);
    when(channelFactory.buildChannel(
            eq("game-design.internal:6565"),
            eq(6565),
            any(CommonGrpcClientProperties.class),
            eq(true)))
        .thenReturn(channel);
    GameDesignRuntimeTenantIdentityClient client =
        new GameDesignRuntimeTenantIdentityClient(endpoints, tls, channelFactory, NAMESPACE);
    try {
      client.init();
      verify(channelFactory)
          .buildChannel(
              eq("game-design.internal:6565"),
              eq(6565),
              any(CommonGrpcClientProperties.class),
              eq(true));
    } finally {
      client.close();
    }
  }

  private static void assertRejected(ResolveRuntimeTenantIdentityResponse response)
      throws Exception {
    TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub stub = mockStub();
    when(stub.resolveRuntimeTenantIdentity(any())).thenReturn(response);
    GameDesignRuntimeTenantIdentityClient client = newClient(stub);

    assertThatThrownBy(
            () -> client.resolveRuntimeTenantIdentity(TENANT_ID.toString(), REQUEST_ID.toString()))
        .isInstanceOf(IllegalStateException.class);
  }

  private static GameDesignRuntimeTenantIdentityClient newClient(
      TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub stub) throws Exception {
    GameDesignRuntimeTenantIdentityClient client =
        newClientWithoutStub(mock(GrpcChannelFactory.class));
    Field stubField = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("stub");
    stubField.setAccessible(true);
    stubField.set(client, stub);
    return client;
  }

  private static GameDesignRuntimeTenantIdentityClient newClientWithoutStub(
      GrpcChannelFactory channelFactory) {
    return new GameDesignRuntimeTenantIdentityClient(
        new ServiceEndpointsProperties(), mtlsProperties(), channelFactory, NAMESPACE);
  }

  private static TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub mockStub() {
    TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub stub =
        mock(TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    return stub;
  }

  private static ResolveRuntimeTenantIdentityResponse validResponse(String provenanceKind) {
    return ResolveRuntimeTenantIdentityResponse.newBuilder()
        .setSchemaVersion(1)
        .setTargetNamespace(NAMESPACE)
        .setRequestId(REQUEST_ID.toString())
        .setCanonicalTenantId(TENANT_ID.toString())
        .setSourceGameRowId(SOURCE_ROW_ID)
        .setSourceGameTenantKey(SOURCE_KEY)
        .setProvenanceKind(provenanceKind)
        .build();
  }

  private static CommonGrpcClientProperties mtlsProperties() {
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain("certs/game-session-client.crt");
    tls.setPrivateKey("certs/game-session-client.key");
    tls.setCaCert("certs/game-design-ca.crt");
    return tls;
  }

  private static CommonGrpcClientProperties mtlsProperties(Path directory) throws Exception {
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain(Files.createFile(directory.resolve("game-session-client.crt")).toString());
    tls.setPrivateKey(Files.createFile(directory.resolve("game-session-client.key")).toString());
    tls.setCaCert(Files.createFile(directory.resolve("game-design-ca.crt")).toString());
    return tls;
  }

  private static CommonGrpcClientProperties incompleteMtlsProperties(
      String certificate, String privateKey, String caCertificate) {
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain(certificate);
    tls.setPrivateKey(privateKey);
    tls.setCaCert(caCertificate);
    return tls;
  }
}
