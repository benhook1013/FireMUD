package net.firedevops.firemud.gamedesign.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.sql.SQLException;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.GameTenantCreationRepository;
import net.firedevops.firemud.gamedesign.repository.GameTenantIdentity;
import net.firedevops.firemud.gamedesign.service.impl.TenantAssociationMigrationService.ApprovedAssociation;
import net.firedevops.firemud.gamedesign.v1.ResolveFreshTenantCreationRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveFreshTenantCreationResponse;
import net.firedevops.firemud.gamedesign.v1.ResolveLegacyAccountTenantAssociationRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveLegacyAccountTenantAssociationResponse;
import net.firedevops.firemud.gamedesign.v1.ResolveLegacyGameTenantIdentityRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveLegacyGameTenantIdentityResponse;
import org.jooq.exception.DataAccessException;
import org.jooq.exception.TooManyRowsException;
import org.junit.jupiter.api.Test;

class TenantIdentityGrpcServiceTest {
  private static final String ACCOUNT_PEER = "spiffe://firemud/ns/test/sa/account-service";
  private static final String ACCOUNT_MIGRATOR_PEER =
      "spiffe://firemud/ns/test/sa/account-tenant-migrator";
  private static final String WRONG_NAMESPACE_MIGRATOR_PEER =
      "spiffe://firemud/ns/other/sa/account-tenant-migrator";
  private static final String WRONG_NAMESPACE_ACCOUNT_PEER =
      "spiffe://firemud/ns/other/sa/account-service";
  private static final String WRONG_PEER = "spiffe://firemud/ns/test/sa/game-session-service";
  private static final UUID CANONICAL_TENANT_ID =
      UUID.fromString("87426bb3-a733-43f0-9c8e-2e379cbdf7ec");
  private static final UUID FRESH_CREATION_REQUEST_ID =
      UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID FRESH_OPERATION_ID =
      UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID FRESH_CANONICAL_TENANT_ID =
      UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final String FRESH_SOURCE_GAME_TENANT_KEY = "fresh-owner-key-91";
  private static final String FRESH_REQUEST_DIGEST =
      GameTenantCreationDigest.requestDigest(
          "test", FRESH_CREATION_REQUEST_ID, FRESH_SOURCE_GAME_TENANT_KEY, "Fresh Realm", null);

  private final GameRepository repository = mock(GameRepository.class);
  private final TenantAssociationMigrationService associationService =
      mock(TenantAssociationMigrationService.class);
  private final GameTenantCreationRepository creationRepository =
      mock(GameTenantCreationRepository.class);
  private final TenantIdentityGrpcService service =
      new TenantIdentityGrpcService(repository, associationService, creationRepository, "test");

  @Test
  void approvedAccountAssociationReadBindsExactOwnerAndManifestEvidence() {
    when(associationService.findByLegacyAccountTenantId(41L))
        .thenReturn(Optional.of(approvedAssociation()));

    AssociationObserver observer = associationCall(41L, ACCOUNT_MIGRATOR_PEER);

    assertNull(observer.errorCode);
    assertTrue(observer.completed);
    assertNotNull(observer.value);
    assertEquals(41L, observer.value.getLegacyAccountTenantId());
    assertEquals("legacy-game-7", observer.value.getSourceLegacyGameTenantId());
    assertEquals(CANONICAL_TENANT_ID.toString(), observer.value.getCanonicalTenantId());
    assertEquals("sha256:" + "a".repeat(64), observer.value.getAccountEvidenceDigest());
    assertEquals("sha256:" + "b".repeat(64), observer.value.getManifestDigest());
    assertEquals(
        Base64.getEncoder().encodeToString(new byte[64]), observer.value.getManifestSignature());
    assertEquals("reviewed-change-123", observer.value.getApprovalReference());
    assertEquals(1, observer.value.getManifestSchemaVersion());
    verify(associationService).findByLegacyAccountTenantId(41L);
  }

  @Test
  void associationReadRejectsMissingPeerAndInvalidNumericKeyBeforeOwnerRead() {
    assertEquals(Status.Code.PERMISSION_DENIED, associationStatus(associationCall(41L, null)));
    assertEquals(
        Status.Code.PERMISSION_DENIED, associationStatus(associationCall(41L, WRONG_PEER)));
    assertEquals(
        Status.Code.PERMISSION_DENIED, associationStatus(associationCall(41L, ACCOUNT_PEER)));
    assertEquals(
        Status.Code.PERMISSION_DENIED,
        associationStatus(associationCall(41L, WRONG_NAMESPACE_MIGRATOR_PEER)));
    assertEquals(
        Status.Code.INVALID_ARGUMENT,
        associationStatus(associationCall(0L, ACCOUNT_MIGRATOR_PEER)));
    verifyNoInteractions(associationService);
  }

  @Test
  void associationReadFailsClosedForAbsentOrCorruptOwnerEvidence() {
    when(associationService.findByLegacyAccountTenantId(41L)).thenReturn(Optional.empty());
    when(associationService.findByLegacyAccountTenantId(42L))
        .thenThrow(new IllegalStateException("corrupt operation"));
    assertEquals(
        Status.Code.NOT_FOUND, associationStatus(associationCall(41L, ACCOUNT_MIGRATOR_PEER)));
    assertEquals(
        Status.Code.FAILED_PRECONDITION,
        associationStatus(associationCall(42L, ACCOUNT_MIGRATOR_PEER)));
  }

  @Test
  void exactAccountPeerReadsOwnerSourceAndProvenance() {
    when(repository.findTenantIdentityByLegacyTenantId("legacy-game-7"))
        .thenReturn(
            Optional.of(
                new GameTenantIdentity(
                    CANONICAL_TENANT_ID,
                    GameTenantIdentity.ProvenanceKind.RETAINED_GAME_V30,
                    7L,
                    "legacy-game-7")));
    TestObserver observer = call("legacy-game-7", ACCOUNT_PEER);

    assertNull(observer.errorCode);
    assertTrue(observer.completed);
    assertNotNull(observer.value);
    assertEquals(CANONICAL_TENANT_ID.toString(), observer.value.getCanonicalTenantId());
    assertEquals("legacy-game-7", observer.value.getSourceLegacyGameTenantId());
    assertEquals(7L, observer.value.getSourceGameRowId());
    assertEquals("RETAINED_GAME_V30", observer.value.getProvenanceKind());
    verify(repository).findTenantIdentityByLegacyTenantId("legacy-game-7");
  }

  @Test
  void wrongOrMissingPeerNeverReadsOwnerRows() {
    assertEquals(Status.Code.PERMISSION_DENIED, status(call("legacy-game-7", WRONG_PEER)));
    assertEquals(Status.Code.PERMISSION_DENIED, status(call("legacy-game-7", null)));
    assertEquals(
        Status.Code.PERMISSION_DENIED, status(call("legacy-game-7", ACCOUNT_MIGRATOR_PEER)));
    verifyNoInteractions(repository);
  }

  @Test
  void blankOrOverlongSourceKeyNeverReadsOwnerRows() {
    assertEquals(Status.Code.INVALID_ARGUMENT, status(call("", ACCOUNT_PEER)));
    assertEquals(Status.Code.INVALID_ARGUMENT, status(call("x".repeat(37), ACCOUNT_PEER)));
    verifyNoInteractions(repository);
  }

  @Test
  void unknownOrMismatchedSourceFailsClosed() {
    when(repository.findTenantIdentityByLegacyTenantId("unknown")).thenReturn(Optional.empty());
    when(repository.findTenantIdentityByLegacyTenantId("legacy-game-7"))
        .thenReturn(
            Optional.of(
                new GameTenantIdentity(
                    CANONICAL_TENANT_ID,
                    GameTenantIdentity.ProvenanceKind.RETAINED_GAME_V30,
                    7L,
                    "different-source")));

    TestObserver absent = call("unknown", ACCOUNT_PEER);
    TestObserver mismatched = call("legacy-game-7", ACCOUNT_PEER);
    assertEquals(Status.Code.NOT_FOUND, status(absent));
    assertEquals(Status.Code.FAILED_PRECONDITION, status(mismatched));
    assertNull(absent.value);
    assertNull(mismatched.value);
    assertFalse(absent.completed);
    assertFalse(mismatched.completed);
  }

  @Test
  void invalidStoredProvenanceFailsClosed() {
    when(repository.findTenantIdentityByLegacyTenantId("legacy-game-7"))
        .thenThrow(new IllegalStateException("corrupt source binding"));

    TestObserver observer = call("legacy-game-7", ACCOUNT_PEER);

    assertEquals(Status.Code.FAILED_PRECONDITION, status(observer));
    assertNull(observer.value);
  }

  @Test
  void duplicateRetainedLegacyTenantKeyFailsAsPrecondition() {
    when(repository.findTenantIdentityByLegacyTenantId("legacy-game-7"))
        .thenThrow(new TooManyRowsException("duplicate retained tenant key"));

    TestObserver observer = call("legacy-game-7", ACCOUNT_PEER);

    assertEquals(Status.Code.FAILED_PRECONDITION, status(observer));
    assertNull(observer.value);
    assertFalse(observer.completed);
  }

  @Test
  void freshCreationReadRequiresExactAccountPeerBeforeOwnerRead() {
    assertEquals(
        Status.Code.PERMISSION_DENIED, freshCreationStatus(freshCreationCall("", "", null)));
    assertEquals(
        Status.Code.PERMISSION_DENIED, freshCreationStatus(freshCreationCall("", "", WRONG_PEER)));
    assertEquals(
        Status.Code.PERMISSION_DENIED,
        freshCreationStatus(freshCreationCall("", "", WRONG_NAMESPACE_ACCOUNT_PEER)));
    assertEquals(
        Status.Code.PERMISSION_DENIED,
        freshCreationStatus(freshCreationCall("", "", ACCOUNT_MIGRATOR_PEER)));
    assertEquals(
        Status.Code.PERMISSION_DENIED,
        freshCreationStatus(freshCreationCall("", "", WRONG_NAMESPACE_MIGRATOR_PEER)));
    verifyNoInteractions(creationRepository);
  }

  @Test
  void freshCreationReadRejectsEmptyAlternateAndNilIdsOrDigestsBeforeOwnerRead() {
    String digest = "sha256:" + "c".repeat(64);
    assertEquals(
        Status.Code.INVALID_ARGUMENT,
        freshCreationStatus(freshCreationCall("", digest, ACCOUNT_PEER)));
    assertEquals(
        Status.Code.INVALID_ARGUMENT,
        freshCreationStatus(
            freshCreationCall("87426BB3-A733-43F0-9C8E-2E379CBDF7EC", digest, ACCOUNT_PEER)));
    assertEquals(
        Status.Code.INVALID_ARGUMENT,
        freshCreationStatus(
            freshCreationCall("00000000-0000-0000-0000-000000000000", digest, ACCOUNT_PEER)));
    assertEquals(
        Status.Code.INVALID_ARGUMENT,
        freshCreationStatus(
            freshCreationCall("11111111-1111-4111-8111-111111111111", "", ACCOUNT_PEER)));
    assertEquals(
        Status.Code.INVALID_ARGUMENT,
        freshCreationStatus(
            freshCreationCall(
                "11111111-1111-4111-8111-111111111111", "SHA256:" + "c".repeat(64), ACCOUNT_PEER)));
    assertEquals(
        Status.Code.INVALID_ARGUMENT,
        freshCreationStatus(
            freshCreationCall(
                "11111111-1111-4111-8111-111111111111", "c".repeat(64), ACCOUNT_PEER)));
    verifyNoInteractions(creationRepository);
  }

  @Test
  void freshCreationReadReturnsNotFoundOnlyForExactAbsentRequest() {
    UUID requestId = UUID.fromString("11111111-1111-4111-8111-111111111111");
    String digest = "sha256:" + "c".repeat(64);
    when(creationRepository.read(requestId, "test")).thenReturn(Optional.empty());

    FreshCreationObserver observer = freshCreationCall(requestId.toString(), digest, ACCOUNT_PEER);

    assertEquals(Status.Code.NOT_FOUND, freshCreationStatus(observer));
    assertNull(observer.value);
    verify(creationRepository).read(requestId, "test");
  }

  @Test
  void freshCreationReadFailsClosedForPendingCorruptOrChangedSourceEvidence() {
    UUID pendingRequestId = UUID.fromString("77777777-7777-4777-8777-777777777777");
    UUID corruptRequestId = UUID.fromString("88888888-8888-4888-8888-888888888888");
    UUID changedSourceRequestId = UUID.fromString("99999999-9999-4999-8999-999999999999");
    when(creationRepository.read(pendingRequestId, "test"))
        .thenThrow(
            new GameTenantCreationRepository.InvalidCreationEvidenceException(
                "incomplete operation"));
    when(creationRepository.read(corruptRequestId, "test"))
        .thenThrow(
            new GameTenantCreationRepository.InvalidCreationEvidenceException("corrupt receipt"));
    when(creationRepository.read(changedSourceRequestId, "test"))
        .thenThrow(
            new GameTenantCreationRepository.InvalidCreationEvidenceException(
                "changed source tuple"));

    FreshCreationObserver pending =
        freshCreationCall(
            pendingRequestId.toString(),
            GameTenantCreationDigest.requestDigest(
                "test", pendingRequestId, FRESH_SOURCE_GAME_TENANT_KEY, "Pending", null),
            ACCOUNT_PEER);
    FreshCreationObserver corrupt =
        freshCreationCall(
            corruptRequestId.toString(),
            GameTenantCreationDigest.requestDigest(
                "test", corruptRequestId, FRESH_SOURCE_GAME_TENANT_KEY, "Corrupt", null),
            ACCOUNT_PEER);
    FreshCreationObserver changedSource =
        freshCreationCall(
            changedSourceRequestId.toString(),
            GameTenantCreationDigest.requestDigest(
                "test", changedSourceRequestId, FRESH_SOURCE_GAME_TENANT_KEY, "Changed", null),
            ACCOUNT_PEER);

    assertEquals(Status.Code.FAILED_PRECONDITION, freshCreationStatus(pending));
    assertEquals(Status.Code.FAILED_PRECONDITION, freshCreationStatus(corrupt));
    assertEquals(Status.Code.FAILED_PRECONDITION, freshCreationStatus(changedSource));
    assertNull(pending.value);
    assertNull(corrupt.value);
    assertNull(changedSource.value);
  }

  @Test
  void exactAccountPeerReadsFreshCreationReceiptAndReplaysTheSameResponse() {
    when(creationRepository.read(FRESH_CREATION_REQUEST_ID, "test"))
        .thenReturn(Optional.of(freshCreationReceipt()));

    FreshCreationObserver first =
        freshCreationCall(FRESH_CREATION_REQUEST_ID.toString(), FRESH_REQUEST_DIGEST, ACCOUNT_PEER);
    FreshCreationObserver retry =
        freshCreationCall(FRESH_CREATION_REQUEST_ID.toString(), FRESH_REQUEST_DIGEST, ACCOUNT_PEER);

    assertNull(first.errorCode);
    assertTrue(first.completed);
    assertNotNull(first.value);
    assertEquals(1, first.value.getSchemaVersion());
    assertEquals("test", first.value.getTargetNamespace());
    assertEquals(FRESH_CREATION_REQUEST_ID.toString(), first.value.getCreationRequestId());
    assertEquals(FRESH_OPERATION_ID.toString(), first.value.getOperationId());
    assertEquals(FRESH_REQUEST_DIGEST, first.value.getRequestDigest());
    assertEquals(FRESH_CANONICAL_TENANT_ID.toString(), first.value.getCanonicalTenantId());
    assertEquals(91L, first.value.getSourceGameRowId());
    assertEquals(FRESH_SOURCE_GAME_TENANT_KEY, first.value.getSourceGameTenantKey());
    assertEquals("NEW_GAME_ROW", first.value.getProvenanceKind());
    assertEquals(
        GameTenantCreationDigest.evidenceDigest(
            "test",
            FRESH_CREATION_REQUEST_ID,
            FRESH_OPERATION_ID,
            FRESH_REQUEST_DIGEST,
            FRESH_CANONICAL_TENANT_ID,
            91L,
            FRESH_SOURCE_GAME_TENANT_KEY,
            "NEW_GAME_ROW"),
        first.value.getEvidenceDigest());
    assertNull(retry.errorCode);
    assertTrue(retry.completed);
    assertEquals(first.value, retry.value);
    verify(creationRepository, org.mockito.Mockito.times(2))
        .read(FRESH_CREATION_REQUEST_ID, "test");
    verifyNoMoreInteractions(creationRepository);
  }

  @Test
  void freshCreationReadRejectsChangedDigestAndMismatchedReceiptBindings() {
    UUID anotherRequestId = UUID.fromString("44444444-4444-4444-8444-444444444444");
    String changedDigest =
        GameTenantCreationDigest.requestDigest(
            "test", FRESH_CREATION_REQUEST_ID, FRESH_SOURCE_GAME_TENANT_KEY, "Changed Realm", null);
    String otherNamespaceDigest =
        GameTenantCreationDigest.requestDigest(
            "other", FRESH_CREATION_REQUEST_ID, FRESH_SOURCE_GAME_TENANT_KEY, "Fresh Realm", null);
    String otherRequestDigest =
        GameTenantCreationDigest.requestDigest(
            "test", anotherRequestId, FRESH_SOURCE_GAME_TENANT_KEY, "Fresh Realm", null);
    when(creationRepository.read(FRESH_CREATION_REQUEST_ID, "test"))
        .thenReturn(Optional.of(freshCreationReceipt()))
        .thenReturn(Optional.of(freshCreationReceipt("other", FRESH_CREATION_REQUEST_ID)))
        .thenReturn(Optional.of(freshCreationReceipt("test", anotherRequestId)));

    FreshCreationObserver changedExpectedDigest =
        freshCreationCall(FRESH_CREATION_REQUEST_ID.toString(), changedDigest, ACCOUNT_PEER);
    FreshCreationObserver otherNamespace =
        freshCreationCall(FRESH_CREATION_REQUEST_ID.toString(), otherNamespaceDigest, ACCOUNT_PEER);
    FreshCreationObserver otherRequest =
        freshCreationCall(FRESH_CREATION_REQUEST_ID.toString(), otherRequestDigest, ACCOUNT_PEER);
    assertEquals(Status.Code.FAILED_PRECONDITION, freshCreationStatus(changedExpectedDigest));
    assertEquals(Status.Code.FAILED_PRECONDITION, freshCreationStatus(otherNamespace));
    assertEquals(Status.Code.FAILED_PRECONDITION, freshCreationStatus(otherRequest));
    assertNull(changedExpectedDigest.value);
    assertNull(otherNamespace.value);
    assertNull(otherRequest.value);
  }

  @Test
  void freshCreationReadMapsConnectionSqlStateToUnavailableOnly() {
    UUID unavailableRequestId = UUID.fromString("55555555-5555-4555-8555-555555555555");
    UUID syntaxRequestId = UUID.fromString("66666666-6666-4666-8666-666666666666");
    String unavailableDigest =
        GameTenantCreationDigest.requestDigest(
            "test", unavailableRequestId, FRESH_SOURCE_GAME_TENANT_KEY, "Unavailable", null);
    String syntaxDigest =
        GameTenantCreationDigest.requestDigest(
            "test", syntaxRequestId, FRESH_SOURCE_GAME_TENANT_KEY, "Syntax", null);
    when(creationRepository.read(unavailableRequestId, "test"))
        .thenThrow(
            new DataAccessException(
                "connection failed", new SQLException("connection failed", "08006")));
    when(creationRepository.read(syntaxRequestId, "test"))
        .thenThrow(
            new DataAccessException("query failed", new SQLException("syntax error", "42601")));

    FreshCreationObserver unavailable =
        freshCreationCall(unavailableRequestId.toString(), unavailableDigest, ACCOUNT_PEER);
    FreshCreationObserver syntaxFailure =
        freshCreationCall(syntaxRequestId.toString(), syntaxDigest, ACCOUNT_PEER);

    assertEquals(Status.Code.UNAVAILABLE, freshCreationStatus(unavailable));
    assertEquals(Status.Code.INTERNAL, freshCreationStatus(syntaxFailure));
    assertNull(unavailable.value);
    assertNull(syntaxFailure.value);
  }

  private TestObserver call(String sourceKey, String peerUri) {
    TestObserver observer = new TestObserver();
    Context context = Context.current();
    if (peerUri != null) {
      context =
          context.withValue(
              GrpcPeerIdentity.CONTEXT_KEY, GrpcPeerIdentity.parseUri(peerUri).orElseThrow());
    }
    context.run(
        () ->
            service.resolveLegacyGameTenantIdentity(
                ResolveLegacyGameTenantIdentityRequest.newBuilder()
                    .setLegacyGameTenantId(sourceKey)
                    .build(),
                observer));
    return observer;
  }

  private AssociationObserver associationCall(long accountTenantId, String peerUri) {
    AssociationObserver observer = new AssociationObserver();
    Context context = Context.current();
    if (peerUri != null) {
      context =
          context.withValue(
              GrpcPeerIdentity.CONTEXT_KEY, GrpcPeerIdentity.parseUri(peerUri).orElseThrow());
    }
    context.run(
        () ->
            service.resolveLegacyAccountTenantAssociation(
                ResolveLegacyAccountTenantAssociationRequest.newBuilder()
                    .setLegacyAccountTenantId(accountTenantId)
                    .build(),
                observer));
    return observer;
  }

  private FreshCreationObserver freshCreationCall(
      String creationRequestId, String expectedRequestDigest, String peerUri) {
    FreshCreationObserver observer = new FreshCreationObserver();
    Context context = Context.current();
    if (peerUri != null) {
      context =
          context.withValue(
              GrpcPeerIdentity.CONTEXT_KEY, GrpcPeerIdentity.parseUri(peerUri).orElseThrow());
    }
    context.run(
        () ->
            service.resolveFreshTenantCreation(
                ResolveFreshTenantCreationRequest.newBuilder()
                    .setCreationRequestId(creationRequestId)
                    .setExpectedRequestDigest(expectedRequestDigest)
                    .build(),
                observer));
    return observer;
  }

  private ApprovedAssociation approvedAssociation() {
    return new ApprovedAssociation(
        41L,
        "legacy-game-7",
        CANONICAL_TENANT_ID,
        7L,
        "sha256:" + "a".repeat(64),
        UUID.fromString("11111111-1111-4111-8111-111111111111"),
        "sha256:" + "b".repeat(64),
        Base64.getEncoder().encodeToString(new byte[64]),
        "test",
        "game-design-owner-2026",
        "owner@example.test",
        "reviewed-change-123",
        "2026-09-26T00:00:00Z",
        1,
        1);
  }

  private FreshTenantCreationEvidence freshCreationReceipt() {
    return freshCreationReceipt("test", FRESH_CREATION_REQUEST_ID);
  }

  private FreshTenantCreationEvidence freshCreationReceipt(String targetNamespace, UUID requestId) {
    String requestDigest =
        GameTenantCreationDigest.requestDigest(
            targetNamespace, requestId, FRESH_SOURCE_GAME_TENANT_KEY, "Fresh Realm", null);
    return new FreshTenantCreationEvidence(
        1,
        targetNamespace,
        requestId,
        FRESH_OPERATION_ID,
        requestDigest,
        FRESH_CANONICAL_TENANT_ID,
        91L,
        FRESH_SOURCE_GAME_TENANT_KEY,
        "NEW_GAME_ROW",
        GameTenantCreationDigest.evidenceDigest(
            targetNamespace,
            requestId,
            FRESH_OPERATION_ID,
            requestDigest,
            FRESH_CANONICAL_TENANT_ID,
            91L,
            FRESH_SOURCE_GAME_TENANT_KEY,
            "NEW_GAME_ROW"));
  }

  private static Status.Code associationStatus(AssociationObserver observer) {
    assertNotNull(observer.errorCode);
    return observer.errorCode;
  }

  private static Status.Code freshCreationStatus(FreshCreationObserver observer) {
    assertNotNull(observer.errorCode);
    return observer.errorCode;
  }

  private static Status.Code status(TestObserver observer) {
    assertNotNull(observer.errorCode);
    return observer.errorCode;
  }

  private static final class TestObserver
      implements StreamObserver<ResolveLegacyGameTenantIdentityResponse> {
    private ResolveLegacyGameTenantIdentityResponse value;
    private Status.Code errorCode;
    private boolean completed;

    @Override
    public void onNext(ResolveLegacyGameTenantIdentityResponse response) {
      value = response;
    }

    @Override
    public void onError(Throwable failure) {
      errorCode = Status.fromThrowable(failure).getCode();
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }

  private static final class AssociationObserver
      implements StreamObserver<ResolveLegacyAccountTenantAssociationResponse> {
    private ResolveLegacyAccountTenantAssociationResponse value;
    private Status.Code errorCode;
    private boolean completed;

    @Override
    public void onNext(ResolveLegacyAccountTenantAssociationResponse response) {
      value = response;
    }

    @Override
    public void onError(Throwable failure) {
      errorCode = Status.fromThrowable(failure).getCode();
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }

  private static final class FreshCreationObserver
      implements StreamObserver<ResolveFreshTenantCreationResponse> {
    private ResolveFreshTenantCreationResponse value;
    private Status.Code errorCode;
    private boolean completed;

    @Override
    public void onNext(ResolveFreshTenantCreationResponse response) {
      value = response;
    }

    @Override
    public void onError(Throwable failure) {
      errorCode = Status.fromThrowable(failure).getCode();
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
