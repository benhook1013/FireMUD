package unit.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountPlatformRestrictionBirthSource.Category;
import net.firedevops.firemud.accountservice.dto.AccountPlatformRestrictionMutationRequest.RestrictionState;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountPlatformRestrictionBirthRepository;
import net.firedevops.firemud.accountservice.repository.AccountPlatformRestrictionOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountPlatformRestrictionOperationRepository.CurrentProjection;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.Test;

class AccountPlatformRestrictionOperationRepositoryTest {
  @Test
  void nonrestrictedProjectionAllowsPendingDeletionButRestrictedMismatchFailsClosed() {
    DSLContext dsl = mock(DSLContext.class);
    Record account = mock(Record.class);
    when(dsl.fetchOne(
            "SELECT lifecycle_state FROM accounts WHERE account_uuid = ? FOR SHARE",
            UUID.fromString("68d408e1-ec07-4e0f-a59b-9bfc521365a0")))
        .thenReturn(account);
    when(account.get("lifecycle_state", String.class)).thenReturn("deactivated_pending_delete");
    UUID accountUuid = UUID.fromString("68d408e1-ec07-4e0f-a59b-9bfc521365a0");

    assertThatCode(
            () ->
                requireLifecycleProjection(
                    dsl,
                    projection(
                        accountUuid,
                        Category.ACCOUNT_SECURITY_LOCK,
                        RestrictionState.NONRESTRICTED)))
        .doesNotThrowAnyException();

    assertThatThrownBy(
            () ->
                requireLifecycleProjection(
                    dsl,
                    projection(
                        accountUuid, Category.ACCOUNT_SECURITY_LOCK, RestrictionState.RESTRICTED)))
        .isInstanceOf(
            AccountPlatformRestrictionOperationRepository.RestrictionProjectionUnavailableException
                .class);
  }

  @Test
  void missingAccountStillFailsClosedForRestrictionReadback() {
    DSLContext dsl = mock(DSLContext.class);
    UUID accountUuid = UUID.fromString("27c78e90-747b-4afd-9a2f-c48ad9cc826f");
    when(dsl.fetchOne(
            "SELECT lifecycle_state FROM accounts WHERE account_uuid = ? FOR SHARE", accountUuid))
        .thenReturn(null);

    assertThatThrownBy(
            () ->
                requireLifecycleProjection(
                    dsl,
                    projection(
                        accountUuid,
                        Category.ACCOUNT_SECURITY_LOCK,
                        RestrictionState.NONRESTRICTED)))
        .isInstanceOf(
            AccountPlatformRestrictionOperationRepository.RestrictionProjectionUnavailableException
                .class);
  }

  private static CurrentProjection projection(
      UUID accountUuid, Category category, RestrictionState state) {
    return new CurrentProjection(
        accountUuid, category, 2L, 2L, UUID.randomUUID(), state, UUID.randomUUID());
  }

  private static void requireLifecycleProjection(DSLContext dsl, CurrentProjection projection) {
    try {
      Method method =
          AccountPlatformRestrictionOperationRepository.class.getDeclaredMethod(
              "requireLifecycleProjection", DSLContext.class, CurrentProjection.class);
      method.setAccessible(true);
      method.invoke(null, dsl, projection);
    } catch (InvocationTargetException failure) {
      Throwable cause = failure.getCause();
      if (cause instanceof RuntimeException runtimeFailure) throw runtimeFailure;
      throw new AssertionError("Lifecycle projection check failed unexpectedly", cause);
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError("Lifecycle projection check could not be invoked", failure);
    }
  }

  @Test
  void correlationOnlyCommandRemainsDefaultDeniedBeforeOpeningOwnerStorage() {
    DSLContext dsl = mock(DSLContext.class);
    AccountAuthorityGenerationRepository generations =
        new AccountAuthorityGenerationRepository(dsl);
    AccountAuthorityOutboxRepository outbox = new AccountAuthorityOutboxRepository(dsl);
    AccountAuthoritySourceEvidenceRepository sourceEvidence =
        new AccountAuthoritySourceEvidenceRepository(dsl, generations, outbox);
    var repository =
        new AccountPlatformRestrictionOperationRepository(
            dsl,
            generations,
            outbox,
            sourceEvidence,
            new AccountPlatformRestrictionBirthRepository(dsl));

    assertThatThrownBy(() -> repository.commit(null))
        .isInstanceOf(
            AccountPlatformRestrictionOperationRepository.OwnerAuthorizationUnresolvedException
                .class)
        .hasMessageContaining("owner intent and authentication proof");

    verifyNoInteractions(dsl);
  }
}
