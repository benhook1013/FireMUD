package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Mocked database interactions verify comparison and guard ordering, not PostgreSQL execution. */
class AccountStartSessionAdmissionProtectionRepositoryTest {
  private static final UUID PROTECTION_ID = UUID.fromString("f0467d79-a879-4f4f-99f0-b2369d49bd9b");
  private static final long PROTECTION_FENCE = 71L;
  private static final byte[] REQUEST_BYTES = new byte[] {1, 2, 3};
  private static final UUID ACCOUNT_ID = UUID.fromString("8d5cb900-7900-4200-88ae-113ca0f7028f");

  @Test
  void sourceChildrenMustMatchTheFullCanonicalEvidenceBytes() {
    SourceEvidence expected = source("2", "17", "current source bytes");
    SourceEvidence changedMetadata = source("3", "17", "current source bytes");
    SourceEvidence changedBytes = source("2", "17", "substituted source bytes");

    AccountStartSessionAdmissionProtectionRepository.requireExactSources(
        List.of(expected), List.of(expected));
    assertThatThrownBy(
            () ->
                AccountStartSessionAdmissionProtectionRepository.requireExactSources(
                    List.of(expected), List.of(changedMetadata)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("conflicts");
    assertThatThrownBy(
            () ->
                AccountStartSessionAdmissionProtectionRepository.requireExactSources(
                    List.of(expected), List.of(changedBytes)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("conflicts");
  }

  @Test
  void databaseCurrentnessExpiryAfterLockWaitAbortsBeforeSourceReadback() {
    DSLContext dsl = mock(DSLContext.class);
    when(dsl.fetchOne(contains("account_ss_admission_read_current_exact"), any(Object[].class)))
        .thenThrow(new DataAccessException("original lease elapsed after row-lock wait") {});

    AccountStartSessionAdmissionProtectionRepository repository =
        new AccountStartSessionAdmissionProtectionRepository(dsl);

    assertThatThrownBy(
            () -> repository.assertCurrent(PROTECTION_ID, PROTECTION_FENCE, REQUEST_BYTES))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("elapsed after row-lock wait");

    ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
    verify(dsl, times(1))
        .fetchOne(contains("account_ss_admission_read_current_exact"), arguments.capture());
    assertThat(arguments.getValue())
        .containsExactly(PROTECTION_ID, PROTECTION_FENCE, REQUEST_BYTES);
    assertThat(arguments.getValue()[2]).isSameAs(REQUEST_BYTES);
  }

  private static SourceEvidence source(String generation, String sourceVersion, String bytes) {
    return new SourceEvidence(
        SourceKind.ACCOUNT,
        ACCOUNT_ID.toString(),
        generation,
        sourceVersion,
        "account/admission-protection",
        "17",
        bytes.getBytes(StandardCharsets.UTF_8));
  }
}
