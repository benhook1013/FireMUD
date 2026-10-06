package net.firedevops.firemud.gamedesign.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.UnknownFieldSet;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.sql.SQLException;
import java.util.Base64;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.tenant.GameSessionTenantAssociationEvidence;
import net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.GameSessionTenantAssociationRepository;
import net.firedevops.firemud.gamedesign.repository.GameSessionTenantAssociationRepository.AssociationReceipt;
import net.firedevops.firemud.gamedesign.repository.GameTenantCreationRepository;
import net.firedevops.firemud.gamedesign.repository.GameTenantCreationReservationRepository;
import net.firedevops.firemud.gamedesign.v1.ResolveLegacyGameSessionTenantAssociationRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveLegacyGameSessionTenantAssociationResponse;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DataAccessResourceFailureException;

class GameSessionTenantAssociationGrpcTest {
  private static final UUID OPERATION = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID TENANT = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final String READ_REQUEST = "33333333-3333-4333-8333-333333333333";
  private static final String GAME_SESSION = "spiffe://firemud/ns/test/sa/game-session-service";
  private final GameSessionTenantAssociationRepository repository =
      mock(GameSessionTenantAssociationRepository.class);
  private final TenantIdentityGrpcService service =
      new TenantIdentityGrpcService(
          mock(GameRepository.class),
          mock(TenantAssociationMigrationService.class),
          mock(GameTenantCreationRepository.class),
          mock(GameTenantCreationReservationRepository.class),
          mock(GameAuthoredWorldSourceRepository.class),
          repository,
          "test");

  @Test
  void exactGameSessionPeerPrecedesSyntaxAndOwnerReads() {
    for (String peer :
        new String[] {
          null,
          "spiffe://firemud/ns/test/sa/account-service",
          "spiffe://firemud/ns/other/sa/game-session-service"
        }) {
      Observer observer =
          call(ResolveLegacyGameSessionTenantAssociationRequest.getDefaultInstance(), peer);
      assertThat(observer.error).isEqualTo(Status.Code.PERMISSION_DENIED);
      assertThat(observer.value).isNull();
      assertThat(observer.completed).isFalse();
    }
    verifyNoInteractions(repository);
  }

  @Test
  void aliasesOverflowNilAndUnknownFieldsDenyBeforeOwnerAccess() {
    for (String key : new String[] {"", "0", "01", "-1", "1.0", "9223372036854775808", " 41"}) {
      assertThat(
              call(request().toBuilder().setLegacyGameSessionTenantId(key).build(), GAME_SESSION)
                  .error)
          .isEqualTo(Status.Code.INVALID_ARGUMENT);
    }
    assertThat(
            call(
                    request().toBuilder()
                        .setOperationId("00000000-0000-0000-0000-000000000000")
                        .build(),
                    GAME_SESSION)
                .error)
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(
            call(
                    request().toBuilder()
                        .setRequestId("33333333-3333-4333-8333-33333333333")
                        .build(),
                    GAME_SESSION)
                .error)
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(
            call(
                    request().toBuilder()
                        .setUnknownFields(
                            UnknownFieldSet.newBuilder()
                                .addField(
                                    99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
                                .build())
                        .build(),
                    GAME_SESSION)
                .error)
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    verifyNoInteractions(repository);
  }

  @Test
  void exactReadCarriesCompleteImmutableManifestAndSeparateReadEcho() {
    GameSessionTenantAssociationEvidence evidence = evidence();
    String signature = Base64.getEncoder().encodeToString(new byte[64]);
    when(repository.read(OPERATION, TENANT, 41L, "test"))
        .thenReturn(
            Optional.of(new AssociationReceipt(evidence, signature, evidence.manifestDigest())));
    Observer observer = call(request(), GAME_SESSION);
    assertThat(observer.error).isNull();
    assertThat(observer.completed).isTrue();
    assertThat(observer.value.getRequestId()).isEqualTo(READ_REQUEST);
    var manifest = observer.value.getManifest();
    assertThat(manifest.getSchemaVersion()).isEqualTo(1);
    assertThat(manifest.getOperationId()).isEqualTo(OPERATION.toString());
    assertThat(manifest.getTargetNamespace()).isEqualTo("test");
    assertThat(manifest.getSignerKeyId()).isEqualTo(evidence.signerKeyId());
    assertThat(manifest.getApprovedBy()).isEqualTo(evidence.approvedBy());
    assertThat(manifest.getApprovalReference()).isEqualTo(evidence.approvalReference());
    assertThat(manifest.getSignedAt()).isEqualTo(evidence.signedAt());
    assertThat(manifest.getLegacyGameSessionTenantId()).isEqualTo("41");
    assertThat(manifest.getCanonicalTenantId()).isEqualTo(TENANT.toString());
    assertThat(manifest.getSourceGameRowId()).isEqualTo(evidence.sourceGameRowId());
    assertThat(manifest.getSourceGameTenantKey()).isEqualTo(evidence.sourceGameTenantKey());
    assertThat(manifest.getProvenanceKind()).isEqualTo(evidence.provenanceKind());
    assertThat(manifest.getGameSessionEvidenceDigest())
        .isEqualTo(evidence.gameSessionEvidenceDigest());
    assertThat(observer.value.getManifestDigest()).isEqualTo(evidence.manifestDigest());
    assertThat(observer.value.getEd25519Signature()).isEqualTo(signature);
  }

  @Test
  void absentContradictoryAndUnavailableEvidenceNeverReturnsPartialContent() {
    when(repository.read(OPERATION, TENANT, 41L, "test"))
        .thenReturn(Optional.empty())
        .thenThrow(new IllegalStateException("contradictory stored evidence"))
        .thenThrow(
            new DataAccessException(
                "private storage detail", new SQLException("private connection detail", "08006")));
    assertThat(call(request(), GAME_SESSION).error).isEqualTo(Status.Code.NOT_FOUND);
    assertThat(call(request(), GAME_SESSION).error).isEqualTo(Status.Code.FAILED_PRECONDITION);
    Observer unavailable = call(request(), GAME_SESSION);
    assertThat(unavailable.error).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(unavailable.description)
        .isEqualTo("Retained Game Session association could not be read");
    assertThat(unavailable.value).isNull();
    assertThat(unavailable.completed).isFalse();
  }

  @Test
  void connectionSqlStateFailureIsSanitizedUnavailableWithoutPartialContent() {
    when(repository.read(OPERATION, TENANT, 41L, "test"))
        .thenThrow(
            new DataAccessException(
                "private jOOQ detail",
                new IllegalStateException(
                    "private wrapper detail", new SQLException("private SQL detail", "08006"))));

    assertReadFailure(Status.Code.UNAVAILABLE);
  }

  @Test
  void permanentSqlStateFailureIsSanitizedInternalWithoutPartialContent() {
    when(repository.read(OPERATION, TENANT, 41L, "test"))
        .thenThrow(
            new DataAccessException(
                "private jOOQ detail", new SQLException("private SQL detail", "42000")));

    assertReadFailure(Status.Code.INTERNAL);
  }

  @Test
  void genericNonconnectionJooqFailureIsSanitizedInternalWithoutPartialContent() {
    when(repository.read(OPERATION, TENANT, 41L, "test"))
        .thenThrow(new DataAccessException("private jOOQ detail"));

    assertReadFailure(Status.Code.INTERNAL);
  }

  @Test
  void springResourceFailureIsSanitizedUnavailableWithoutPartialContent() {
    when(repository.read(OPERATION, TENANT, 41L, "test"))
        .thenThrow(new DataAccessResourceFailureException("private resource detail"));

    assertReadFailure(Status.Code.UNAVAILABLE);
  }

  private void assertReadFailure(Status.Code expectedCode) {
    Observer observer = call(request(), GAME_SESSION);
    assertThat(observer.error).isEqualTo(expectedCode);
    assertThat(observer.description)
        .isEqualTo("Retained Game Session association could not be read");
    assertThat(observer.value).isNull();
    assertThat(observer.completed).isFalse();
  }

  @Test
  void changedReadScopeDigestAndSignatureDenyWithoutPartialContent() {
    GameSessionTenantAssociationEvidence exact = evidence();
    String signature = Base64.getEncoder().encodeToString(new byte[64]);
    for (AssociationReceipt wrong :
        new AssociationReceipt[] {
          receipt(withScope(UUID.randomUUID(), TENANT, "test", "41"), signature),
          receipt(withScope(OPERATION, UUID.randomUUID(), "test", "41"), signature),
          receipt(withScope(OPERATION, TENANT, "other", "41"), signature),
          receipt(withScope(OPERATION, TENANT, "test", "42"), signature),
          new AssociationReceipt(exact, signature, "sha256:" + "b".repeat(64)),
          new AssociationReceipt(exact, "invalid-signature", exact.manifestDigest())
        }) {
      when(repository.read(OPERATION, TENANT, 41L, "test")).thenReturn(Optional.of(wrong));
      Observer observer = call(request(), GAME_SESSION);
      assertThat(observer.error).isEqualTo(Status.Code.FAILED_PRECONDITION);
      assertThat(observer.value).isNull();
      assertThat(observer.completed).isFalse();
    }
  }

  @Test
  void productionAndDefaultBearerBypassKeepExactProtectedSourceReadsAligned() throws Exception {
    Set<String> required =
        Set.of(
            "gamedesign.v1.TenantIdentityService/ResolveRuntimeTenantIdentity",
            "gamedesign.v1.TenantIdentityService/ResolveAuthoredWorldSource",
            "gamedesign.v1.TenantIdentityService/ResolveLegacyGameSessionTenantAssociation");
    for (String name : new String[] {"application.yml", "application-prod.yml"}) {
      Set<String> methods = new HashSet<>();
      for (PropertySource<?> source :
          new YamlPropertySourceLoader()
              .load(name, new FileSystemResource("src/main/resources/" + name))) {
        for (int index = 0; index < 20; index++) {
          Object method = source.getProperty("firemud.auth.grpc.public-methods[" + index + "]");
          if (method != null) {
            methods.add(method.toString());
          }
        }
      }
      assertThat(methods).containsAll(required);
    }
  }

  private ResolveLegacyGameSessionTenantAssociationRequest request() {
    return ResolveLegacyGameSessionTenantAssociationRequest.newBuilder()
        .setRequestId(READ_REQUEST)
        .setOperationId(OPERATION.toString())
        .setCanonicalTenantId(TENANT.toString())
        .setLegacyGameSessionTenantId("41")
        .build();
  }

  private GameSessionTenantAssociationEvidence evidence() {
    return new GameSessionTenantAssociationEvidence(
        1,
        OPERATION,
        "test",
        "owner-key",
        "owner",
        "approval-41",
        "2026-10-01T00:00:00Z",
        "41",
        TENANT,
        "7",
        "source-key",
        "RETAINED_GAME_V30",
        "sha256:" + "a".repeat(64));
  }

  private GameSessionTenantAssociationEvidence withScope(
      UUID operation, UUID tenant, String namespace, String legacyKey) {
    GameSessionTenantAssociationEvidence source = evidence();
    return new GameSessionTenantAssociationEvidence(
        source.schemaVersion(),
        operation,
        namespace,
        source.signerKeyId(),
        source.approvedBy(),
        source.approvalReference(),
        source.signedAt(),
        legacyKey,
        tenant,
        source.sourceGameRowId(),
        source.sourceGameTenantKey(),
        source.provenanceKind(),
        source.gameSessionEvidenceDigest());
  }

  private AssociationReceipt receipt(
      GameSessionTenantAssociationEvidence manifest, String signature) {
    return new AssociationReceipt(manifest, signature, manifest.manifestDigest());
  }

  private Observer call(ResolveLegacyGameSessionTenantAssociationRequest request, String peer) {
    Observer observer = new Observer();
    Context.current()
        .withValue(
            GrpcPeerIdentity.CONTEXT_KEY,
            peer == null ? null : GrpcPeerIdentity.parseUri(peer).orElseThrow())
        .run(() -> service.resolveLegacyGameSessionTenantAssociation(request, observer));
    return observer;
  }

  private static final class Observer
      implements StreamObserver<ResolveLegacyGameSessionTenantAssociationResponse> {
    private ResolveLegacyGameSessionTenantAssociationResponse value;
    private Status.Code error;
    private String description;
    private boolean completed;

    @Override
    public void onNext(ResolveLegacyGameSessionTenantAssociationResponse value) {
      this.value = value;
    }

    @Override
    public void onError(Throwable error) {
      Status status = Status.fromThrowable(error);
      this.error = status.getCode();
      this.description = status.getDescription();
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
