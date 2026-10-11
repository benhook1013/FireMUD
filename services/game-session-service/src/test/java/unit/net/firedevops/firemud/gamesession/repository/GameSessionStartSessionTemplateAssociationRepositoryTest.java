package unit.net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.Association;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionTemplateAssociationRepository;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.invocation.Invocation;

class GameSessionStartSessionTemplateAssociationRepositoryTest {
  @Test
  void requiresTheExactOwnerClaimBeforeReading() {
    DSLContext dsl = mock(DSLContext.class);
    GameSessionStartSessionOperatorAttemptRepository attempts =
        mock(GameSessionStartSessionOperatorAttemptRepository.class);
    var repository = new GameSessionStartSessionTemplateAssociationRepository(dsl, attempts);

    assertThatThrownBy(
            () ->
                repository.findPinned(
                    (GameSessionStartSessionOperatorAttemptRepository.AttemptClaim) null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("claim");

    verifyNoInteractions(dsl);
    verifyNoInteractions(attempts);
  }

  @Test
  void requiresOpaqueContinuationBeforeAssociationRead() {
    DSLContext dsl = mock(DSLContext.class);
    GameSessionStartSessionOperatorAttemptRepository attempts =
        mock(GameSessionStartSessionOperatorAttemptRepository.class);
    var repository = new GameSessionStartSessionTemplateAssociationRepository(dsl, attempts);
    var continuation = (GameSessionStartSessionOperatorAttemptRepository.EvidenceContinuation) null;

    assertThatThrownBy(() -> repository.findPinned(continuation))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("continuation");

    verifyNoInteractions(dsl);
    verifyNoInteractions(attempts);
  }

  @Test
  void requiresTypedAssociationEvidenceBeforeOwnerReads() {
    DSLContext dsl = mock(DSLContext.class);
    GameSessionStartSessionOperatorAttemptRepository attempts =
        mock(GameSessionStartSessionOperatorAttemptRepository.class);
    var repository = new GameSessionStartSessionTemplateAssociationRepository(dsl, attempts);

    assertThatThrownBy(() -> repository.pinInitialOrValidateExactReplay(null, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("claim");
    assertThatThrownBy(
            () ->
                repository.pinInitialOrValidateExactReplay(
                    new GameSessionStartSessionOperatorAttemptRepository.AttemptClaim(
                        "world-runtime",
                        "operator-request",
                        java.util.UUID.fromString("02222222-2222-4222-8222-222222222222"),
                        java.util.UUID.fromString("03333333-3333-4333-8333-333333333333"),
                        java.util.UUID.fromString("04444444-4444-4444-8444-444444444444"),
                        7L),
                    null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("candidate");

    verifyNoInteractions(dsl);
    verifyNoInteractions(attempts);
  }

  @Test
  void initialAssociationInsertSelectBindsTheOriginalAuthorizationExpiry() throws Exception {
    DSLContext dsl = mock(DSLContext.class);
    var repository =
        new GameSessionStartSessionTemplateAssociationRepository(
            dsl, mock(GameSessionStartSessionOperatorAttemptRepository.class));
    UUID ownerAttemptId = UUID.fromString("02222222-2222-4222-8222-222222222222");
    UUID ownerMutationId = UUID.fromString("03333333-3333-4333-8333-333333333333");
    UUID claimOwnerId = UUID.fromString("04444444-4444-4444-8444-444444444444");
    UUID tenantId = UUID.fromString("05555555-5555-4555-8555-555555555555");
    var claim =
        new GameSessionStartSessionOperatorAttemptRepository.AttemptClaim(
            "world-runtime", "operator-request", ownerAttemptId, ownerMutationId, claimOwnerId, 7L);
    byte[] tuple = new byte[] {1, 2, 3};
    byte[] projection = new byte[] {4, 5, 6};
    Class<?> snapshotType = GameSessionStartSessionOperatorAttemptRepository.AttemptSnapshot.class;
    Constructor<?> snapshotConstructor =
        snapshotType.getDeclaredConstructor(
            String.class,
            String.class,
            UUID.class,
            UUID.class,
            UUID.class,
            long.class,
            String.class,
            byte[].class,
            byte[].class,
            Instant.class,
            Instant.class);
    snapshotConstructor.setAccessible(true);
    Object attempt =
        snapshotConstructor.newInstance(
            claim.targetNamespace(),
            claim.controlPlaneRequestId(),
            tenantId,
            ownerAttemptId,
            ownerMutationId,
            claim.ownerFence(),
            "OWNER_EXECUTION_PENDING",
            tuple,
            projection,
            Instant.parse("2026-10-10T12:00:00Z"),
            Instant.parse("2026-10-10T12:01:00Z"));

    Association association = mock(Association.class);
    when(association.templateId()).thenReturn(9L);
    when(association.canonicalVersionId())
        .thenReturn(UUID.fromString("06666666-6666-4666-8666-666666666666"));
    when(association.selectedCommitId())
        .thenReturn(UUID.fromString("07777777-7777-4777-8777-777777777777"));
    when(association.publishWorkflowId()).thenReturn("publish-workflow");
    when(association.publicationSelectionDigest()).thenReturn("selection-digest");
    when(association.associationDigest()).thenReturn("association-digest");
    when(association.intakeRequestId())
        .thenReturn(UUID.fromString("08888888-8888-4888-8888-888888888888"));
    when(association.worldOperationId())
        .thenReturn(UUID.fromString("09999999-9999-4999-8999-999999999999"));
    when(association.sourceOperationId())
        .thenReturn(UUID.fromString("0a999999-9999-4999-8999-999999999999"));
    when(association.sourceEvidenceDigest()).thenReturn("source-digest");

    Method insertInitialPin =
        GameSessionStartSessionTemplateAssociationRepository.class.getDeclaredMethod(
            "insertInitialPin",
            claim.getClass(),
            snapshotType,
            Association.class,
            byte[].class,
            byte[].class,
            String.class);
    insertInitialPin.setAccessible(true);
    byte[] requestWire = new byte[] {10, 11};
    byte[] responseWire = new byte[] {12, 13};
    String originalAuthorizationExpiresAt = "2026-10-10T12:00:30Z";
    insertInitialPin.invoke(
        repository,
        claim,
        attempt,
        association,
        requestWire,
        responseWire,
        originalAuthorizationExpiresAt);

    Invocation insertCall =
        Mockito.mockingDetails(dsl).getInvocations().stream()
            .filter(invocation -> invocation.getMethod().getName().equals("fetchOne"))
            .filter(invocation -> invocation.getRawArguments()[0] instanceof String sql)
            .filter(
                invocation ->
                    ((String) invocation.getRawArguments()[0])
                        .contains(
                            "INSERT INTO game_session_start_session_template_association_pin"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing initial association INSERT SELECT"));
    Object[] rawArguments = insertCall.getRawArguments();
    String sql = (String) rawArguments[0];
    Object[] bindings = (Object[]) rawArguments[1];

    assertThat(sql)
        .contains("SELECT attempt.target_namespace, attempt.control_plane_request_id")
        .contains("attempt.canonical_tenant_id = ?")
        .contains("attempt.owner_attempt_id = ? AND attempt.owner_mutation_id = ?")
        .contains("attempt.claim_owner_id = ? AND attempt.owner_fence = ?")
        .contains("attempt.phase_state = 'OWNER_EXECUTION_PENDING'")
        .contains("attempt.account_redemption_projection IS NOT NULL")
        .contains("attempt.post_authorization_execution_tuple = ?")
        .contains("attempt.account_redemption_projection = ?")
        .contains("?::timestamptz > clock_timestamp()")
        .contains("attempt.lease_expires_at > clock_timestamp()");
    assertThat(Arrays.stream(sql.split("\\?", -1)).count() - 1).isEqualTo(bindings.length);
    assertThat(bindings[2]).isEqualTo(requestWire);
    assertThat(bindings[3]).isEqualTo(responseWire);
    assertThat(bindings[16]).isEqualTo(claim.targetNamespace());
    assertThat(bindings[17]).isEqualTo(claim.controlPlaneRequestId());
    assertThat(bindings[18]).isEqualTo(tenantId);
    assertThat(bindings[19]).isEqualTo(ownerAttemptId);
    assertThat(bindings[20]).isEqualTo(ownerMutationId);
    assertThat(bindings[21]).isEqualTo(claimOwnerId);
    assertThat(bindings[22]).isEqualTo(claim.ownerFence());
    assertThat(bindings[23]).isEqualTo(tuple);
    assertThat(bindings[24]).isEqualTo(projection);
    assertThat(bindings[25]).isEqualTo(originalAuthorizationExpiresAt);
  }
}
