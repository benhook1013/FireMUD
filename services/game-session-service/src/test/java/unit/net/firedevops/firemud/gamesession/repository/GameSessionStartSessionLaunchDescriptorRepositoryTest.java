package unit.net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionLaunchDescriptorRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionTemplateAssociationRepository;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

class GameSessionStartSessionLaunchDescriptorRepositoryTest {
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
