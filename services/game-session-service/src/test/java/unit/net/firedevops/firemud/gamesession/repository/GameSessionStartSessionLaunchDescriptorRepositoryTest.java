package unit.net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.UUID;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionLaunchDescriptorRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionTemplateAssociationRepository;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.invocation.Invocation;

class GameSessionStartSessionLaunchDescriptorRepositoryTest {
  @Test
  void claimPinInsertBindsOriginalAuthorizationExpiryAtDatabaseWriteBoundary() throws Exception {
    DSLContext dsl = mock(DSLContext.class);
    var repository =
        new GameSessionStartSessionLaunchDescriptorRepository(
            dsl, mock(GameSessionStartSessionTemplateAssociationRepository.class));
    var claim =
        new GameSessionStartSessionOperatorAttemptRepository.AttemptClaim(
            "world-runtime",
            "operator-request",
            UUID.fromString("02222222-2222-4222-8222-222222222222"),
            UUID.fromString("03333333-3333-4333-8333-333333333333"),
            UUID.fromString("04444444-4444-4444-8444-444444444444"),
            7L);
    byte[] requestWire = new byte[] {1, 2};
    byte[] responseWire = new byte[] {3, 4};
    String originalAuthorizationExpiresAt = "2026-10-10T12:00:00Z";

    Class<?> preparedType =
        Class.forName(
            GameSessionStartSessionLaunchDescriptorRepository.class.getName()
                + "$PreparedCandidate");
    Constructor<?> preparedConstructor =
        preparedType.getDeclaredConstructor(byte[].class, byte[].class);
    preparedConstructor.setAccessible(true);
    Object prepared = preparedConstructor.newInstance(requestWire, responseWire);
    Method insertClaimPin =
        GameSessionStartSessionLaunchDescriptorRepository.class.getDeclaredMethod(
            "insertClaimPin", claim.getClass(), preparedType, String.class);
    insertClaimPin.setAccessible(true);
    insertClaimPin.invoke(repository, claim, prepared, originalAuthorizationExpiresAt);

    Invocation insertCall =
        Mockito.mockingDetails(dsl).getInvocations().stream()
            .filter(invocation -> invocation.getMethod().getName().equals("fetchOne"))
            .filter(invocation -> invocation.getRawArguments()[0] instanceof String sql)
            .filter(
                invocation ->
                    ((String) invocation.getRawArguments()[0])
                        .contains("INSERT INTO game_session_start_session_launch_descriptor_pin"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing descriptor claim INSERT"));
    Object[] rawArguments = insertCall.getRawArguments();
    String sql = (String) rawArguments[0];
    Object[] bindings = (Object[]) rawArguments[1];

    assertThat(sql)
        .contains("attempt.lease_expires_at > clock_timestamp()")
        .contains("AND ?::timestamptz > clock_timestamp()")
        .contains(
            "association.post_authorization_execution_tuple = "
                + "attempt.post_authorization_execution_tuple");
    assertThat(Arrays.stream(sql.split("\\?", -1)).count() - 1).isEqualTo(bindings.length);
    assertThat(bindings[4]).isEqualTo(claim.targetNamespace());
    assertThat(bindings[5]).isEqualTo(claim.controlPlaneRequestId());
    assertThat(bindings[6]).isEqualTo(claim.ownerAttemptId());
    assertThat(bindings[7]).isEqualTo(claim.ownerMutationId());
    assertThat(bindings[8]).isEqualTo(claim.claimOwnerId());
    assertThat(bindings[9]).isEqualTo(claim.ownerFence());
    assertThat(bindings[10]).isEqualTo(originalAuthorizationExpiresAt);
  }

  @Test
  void continuationPinReadBindsTheLiveOwnerAndExactProjectionWithReferenceExpiry()
      throws Exception {
    DSLContext dsl = mock(DSLContext.class);
    var repository =
        new GameSessionStartSessionLaunchDescriptorRepository(
            dsl, mock(GameSessionStartSessionTemplateAssociationRepository.class));
    UUID ownerAttemptId = UUID.fromString("02222222-2222-4222-8222-222222222222");
    UUID ownerMutationId = UUID.fromString("03333333-3333-4333-8333-333333333333");
    UUID claimOwnerId = UUID.fromString("04444444-4444-4444-8444-444444444444");
    byte[] tuple = new byte[] {1, 2, 3};
    byte[] projection = new byte[] {4, 5, 6};
    Class<?> continuationType =
        GameSessionStartSessionOperatorAttemptRepository.EvidenceContinuation.class;
    Constructor<?> continuationConstructor =
        continuationType.getDeclaredConstructor(
            String.class,
            String.class,
            UUID.class,
            UUID.class,
            UUID.class,
            long.class,
            byte[].class,
            byte[].class);
    continuationConstructor.setAccessible(true);
    Object continuation =
        continuationConstructor.newInstance(
            "world-runtime",
            "operator-request",
            ownerAttemptId,
            ownerMutationId,
            claimOwnerId,
            7L,
            tuple,
            projection);
    String originalAuthorizationExpiresAt = "2026-10-10T12:00:00Z";
    Method selectPin =
        GameSessionStartSessionLaunchDescriptorRepository.class.getDeclaredMethod(
            "selectPin", continuationType, boolean.class, String.class);
    selectPin.setAccessible(true);
    selectPin.invoke(repository, continuation, true, originalAuthorizationExpiresAt);

    Invocation selectCall =
        Mockito.mockingDetails(dsl).getInvocations().stream()
            .filter(invocation -> invocation.getMethod().getName().equals("fetchOne"))
            .filter(invocation -> invocation.getRawArguments()[0] instanceof String sql)
            .filter(
                invocation ->
                    ((String) invocation.getRawArguments()[0])
                            .contains("SELECT descriptor_pin.* FROM ")
                        && ((String) invocation.getRawArguments()[0])
                            .contains("game_session_start_session_launch_descriptor_pin"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing continuation descriptor SELECT"));
    Object[] rawArguments = selectCall.getRawArguments();
    String sql = (String) rawArguments[0];
    Object[] bindings = (Object[]) rawArguments[1];

    assertThat(sql)
        .contains("attempt.owner_attempt_id = ? AND attempt.owner_mutation_id = ?")
        .contains("attempt.claim_owner_id = ? AND attempt.owner_fence = ?")
        .contains("attempt.phase_state = 'OWNER_EXECUTION_PENDING'")
        .contains("attempt.post_authorization_execution_tuple = ?")
        .contains("attempt.account_redemption_projection = ?")
        .contains("attempt.lease_expires_at > clock_timestamp()")
        .contains("?::timestamptz > clock_timestamp()")
        .contains("FOR UPDATE OF descriptor_pin");
    assertThat(Arrays.stream(sql.split("\\?", -1)).count() - 1).isEqualTo(bindings.length);
    assertThat(bindings[0]).isEqualTo("world-runtime");
    assertThat(bindings[1]).isEqualTo("operator-request");
    assertThat(bindings[2]).isEqualTo(ownerAttemptId);
    assertThat(bindings[3]).isEqualTo(ownerMutationId);
    assertThat(bindings[4]).isEqualTo(claimOwnerId);
    assertThat(bindings[5]).isEqualTo(7L);
    assertThat(bindings[6]).isEqualTo(tuple);
    assertThat(bindings[7]).isEqualTo(projection);
    assertThat(bindings[8]).isEqualTo(originalAuthorizationExpiresAt);
  }

  @Test
  void postReadContinuationRechecksOriginalReferenceExpiryUsingDatabaseClock() throws Exception {
    DSLContext dsl = mock(DSLContext.class);
    var repository =
        new GameSessionStartSessionLaunchDescriptorRepository(
            dsl, mock(GameSessionStartSessionTemplateAssociationRepository.class));
    String originalAuthorizationExpiresAt = "2026-10-10T12:00:00Z";
    Method recheck =
        GameSessionStartSessionLaunchDescriptorRepository.class.getDeclaredMethod(
            "isOriginalAuthorizationReferenceUnexpired", String.class);
    recheck.setAccessible(true);

    assertThat(recheck.invoke(repository, originalAuthorizationExpiresAt)).isEqualTo(false);

    Invocation checkCall =
        Mockito.mockingDetails(dsl).getInvocations().stream()
            .filter(invocation -> invocation.getMethod().getName().equals("fetchOne"))
            .filter(invocation -> invocation.getRawArguments()[0] instanceof String sql)
            .filter(
                invocation ->
                    ((String) invocation.getRawArguments()[0])
                        .equals("SELECT ?::timestamptz > clock_timestamp() AS unexpired"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing post-read authorization expiry check"));
    Object[] rawArguments = checkCall.getRawArguments();
    Object[] bindings = (Object[]) rawArguments[1];

    assertThat(bindings).containsExactly(originalAuthorizationExpiresAt);
  }

  @Test
  void requiresTheExactOwnerClaimBeforeReading() {
    DSLContext dsl = mock(DSLContext.class);
    GameSessionStartSessionTemplateAssociationRepository associations =
        mock(GameSessionStartSessionTemplateAssociationRepository.class);
    var repository = new GameSessionStartSessionLaunchDescriptorRepository(dsl, associations);

    assertThatThrownBy(
            () ->
                repository.findPinned(
                    (GameSessionStartSessionOperatorAttemptRepository.AttemptClaim) null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("claim");

    verifyNoInteractions(dsl);
    verifyNoInteractions(associations);
  }

  @Test
  void requiresTypedDescriptorEvidenceBeforeOwnerReads() {
    DSLContext dsl = mock(DSLContext.class);
    GameSessionStartSessionTemplateAssociationRepository associations =
        mock(GameSessionStartSessionTemplateAssociationRepository.class);
    var repository = new GameSessionStartSessionLaunchDescriptorRepository(dsl, associations);
    var claim =
        new GameSessionStartSessionOperatorAttemptRepository.AttemptClaim(
            "world-runtime",
            "operator-request",
            java.util.UUID.fromString("02222222-2222-4222-8222-222222222222"),
            java.util.UUID.fromString("03333333-3333-4333-8333-333333333333"),
            java.util.UUID.fromString("04444444-4444-4444-8444-444444444444"),
            7L);

    assertThatThrownBy(
            () ->
                repository.pin(
                    (GameSessionStartSessionOperatorAttemptRepository.AttemptClaim) null, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("claim");
    assertThatThrownBy(() -> repository.pin(claim, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("candidate");

    verifyNoInteractions(dsl);
    verifyNoInteractions(associations);
  }

  @Test
  void requiresOpaqueContinuationBeforeDescriptorReadsOrWrites() {
    DSLContext dsl = mock(DSLContext.class);
    GameSessionStartSessionTemplateAssociationRepository associations =
        mock(GameSessionStartSessionTemplateAssociationRepository.class);
    var repository = new GameSessionStartSessionLaunchDescriptorRepository(dsl, associations);
    var continuation = (GameSessionStartSessionOperatorAttemptRepository.EvidenceContinuation) null;

    assertThatThrownBy(() -> repository.findPinned(continuation))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("continuation");
    assertThatThrownBy(() -> repository.pin(continuation, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("continuation");

    verifyNoInteractions(dsl);
    verifyNoInteractions(associations);
  }
}
