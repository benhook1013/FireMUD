package unit.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountPlatformRestrictionBirthRepository;
import net.firedevops.firemud.accountservice.repository.AccountPlatformRestrictionOperationRepository;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

class AccountPlatformRestrictionOperationRepositoryTest {
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
