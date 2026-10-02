package net.firedevops.firemud.worldmanagement.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import net.firedevops.firemud.worldmanagement.entity.InitialAdmissionBindHold;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class InitialAdmissionBindHoldRepositoryTest {
  private static final String HOLD_ID = "00000000-0000-0000-0000-000000000003";

  @Test
  void lostReconciliationCasReturnsEmptyWithoutReadingConcurrentRowAsSuccess() {
    DSLContext dsl = Mockito.mock(DSLContext.class);
    when(dsl.execute(anyString(), any(Object[].class))).thenReturn(0);
    InitialAdmissionBindHoldRepository repository = new InitialAdmissionBindHoldRepository(dsl);

    var result = repository.markReconciliationRequired(hold(), "GS_OWNER_PENDING", Instant.now());

    assertFalse(result.isPresent());
    ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
    verify(dsl).execute(query.capture(), any(Object[].class));
    assertTrue(query.getValue().startsWith("UPDATE initial_admission_bind_hold SET"));
    verifyNoMoreInteractions(dsl);
  }

  @Test
  void reconciliationAttemptUpdatesTimestampAndRowVersionEvenForSameDiagnostic() {
    DSLContext dsl = Mockito.mock(DSLContext.class);
    when(dsl.execute(anyString(), Mockito.<Object[]>any())).thenReturn(0);
    InitialAdmissionBindHoldRepository repository = new InitialAdmissionBindHoldRepository(dsl);
    Instant attemptedAt = Instant.parse("2026-10-02T04:05:06Z");

    repository.markReconciliationRequired(hold(), "GS_OWNER_PENDING", attemptedAt);

    ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Object[]> bindings = ArgumentCaptor.forClass(Object[].class);
    verify(dsl).execute(query.capture(), bindings.capture());
    assertTrue(query.getValue().contains("updated_at = ?"));
    assertTrue(query.getValue().contains("row_version = row_version + 1"));
    assertEquals(LocalDateTime.ofInstant(attemptedAt, ZoneOffset.UTC), bindings.getValue()[1]);
  }

  private InitialAdmissionBindHold hold() {
    Instant now = Instant.parse("2026-10-02T00:00:00Z");
    return new InitialAdmissionBindHold(
        HOLD_ID,
        "00000000-0000-0000-0000-000000000004",
        42L,
        "00000000-0000-0000-0000-000000000001",
        "00000000-0000-0000-0000-000000000002",
        "SHARED",
        101L,
        11L,
        7L,
        "initial-admission-1",
        "a".repeat(64),
        true,
        1L,
        "RECONCILIATION_REQUIRED",
        now.plusSeconds(300),
        null,
        null,
        null,
        null,
        "GS_OWNER_PENDING",
        now,
        now,
        null,
        3L);
  }
}
