package net.firedevops.firemud.gamesession.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.UUID;
import net.firedevops.firemud.gamesession.entity.InitialAdmissionBindAttempt;
import net.firedevops.firemud.gamesession.entity.InitialAdmissionBindAttempt.Status;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

class InitialAdmissionBindAttemptRepositoryTest {
  @Test
  void attachHoldOnlyAttachesToPendingAttemptWithNoExistingHold() throws Exception {
    try (Connection connection =
        DriverManager.getConnection(
            "jdbc:h2:mem:initial-admission-attach-hold;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")) {
      DSLContext dsl = DSL.using(connection, SQLDialect.H2);
      createSchema(dsl);
      InitialAdmissionBindAttemptRepository repository =
          new InitialAdmissionBindAttemptRepository(dsl);

      InitialAdmissionBindAttempt pending =
          repository.insertPending(attempt("request-attached", UUID.randomUUID()));
      UUID firstHoldId = UUID.randomUUID();
      UUID firstHoldFence = UUID.randomUUID();
      Instant attachedAt = Instant.parse("2026-10-02T00:00:00Z");

      InitialAdmissionBindAttempt attached =
          repository.attachHold(pending, firstHoldId, firstHoldFence, attachedAt);

      assertEquals(firstHoldId, attached.holdId());
      assertEquals(firstHoldFence, attached.holdFence());
      assertEquals(attachedAt, attached.updatedAt());
      assertThrows(
          IllegalStateException.class,
          () -> repository.attachHold(pending, UUID.randomUUID(), UUID.randomUUID(), attachedAt));

      InitialAdmissionBindAttempt unchanged =
          repository.findByTenantAndRequestId(17L, "request-attached").orElseThrow();
      assertEquals(firstHoldId, unchanged.holdId());
      assertEquals(firstHoldFence, unchanged.holdFence());
      assertEquals(attachedAt, unchanged.updatedAt());

      InitialAdmissionBindAttempt secondPending =
          repository.insertPending(attempt("request-terminal", UUID.randomUUID()));
      Instant terminalAt = Instant.parse("2026-10-02T00:01:00Z");
      InitialAdmissionBindAttempt aborted = repository.markAborted(secondPending, terminalAt);

      assertThrows(
          IllegalStateException.class,
          () -> repository.attachHold(aborted, UUID.randomUUID(), UUID.randomUUID(), attachedAt));

      InitialAdmissionBindAttempt terminalReadback =
          repository.findByTenantAndRequestId(17L, "request-terminal").orElseThrow();
      assertEquals(Status.ABORTED, terminalReadback.status());
      assertNull(terminalReadback.holdId());
      assertNull(terminalReadback.holdFence());
      assertEquals(terminalAt, terminalReadback.terminalAt());
    }
  }

  private static InitialAdmissionBindAttempt attempt(String requestId, UUID realmId) {
    Instant createdAt = Instant.parse("2026-10-01T00:00:00Z");
    return new InitialAdmissionBindAttempt(
        UUID.randomUUID(),
        17L,
        requestId,
        "a".repeat(64),
        realmId,
        UUID.randomUUID(),
        "SHARED",
        true,
        1L,
        93L,
        44L,
        8L,
        null,
        null,
        Status.PENDING,
        null,
        null,
        createdAt,
        createdAt,
        null);
  }

  private static void createSchema(DSLContext dsl) {
    dsl.execute(
        """
        CREATE TABLE gameplay_initial_admission_bind_attempt (
          attempt_id UUID PRIMARY KEY,
          tenant_id BIGINT NOT NULL,
          initial_admission_request_id VARCHAR(128) NOT NULL,
          request_digest VARCHAR(64) NOT NULL,
          realm_id UUID NOT NULL,
          playable_state_namespace_id UUID NOT NULL,
          playable_state_scope VARCHAR(32) NOT NULL,
          expected_no_prior_pointer BOOLEAN NOT NULL,
          catalog_revision BIGINT NOT NULL,
          game_instance_id BIGINT NOT NULL,
          version_id BIGINT NOT NULL,
          active_lifecycle_epoch BIGINT NOT NULL,
          hold_id UUID,
          hold_fence UUID,
          status VARCHAR(16) NOT NULL,
          pointer_id BIGINT,
          audit_event_id BIGINT,
          created_at TIMESTAMP NOT NULL,
          updated_at TIMESTAMP NOT NULL,
          terminal_at TIMESTAMP,
          catalog_source_kind VARCHAR(16) NOT NULL DEFAULT 'V9_FIXTURE',
          fixture_catalog_realm_id UUID,
          published_target_namespace VARCHAR(63),
          canonical_tenant_id UUID,
          game_template_id BIGINT,
          launch_descriptor_id VARCHAR(128),
          release_bundle_id BIGINT,
          published_release_bundle_ref VARCHAR(200),
          version_state_epoch BIGINT,
          UNIQUE (tenant_id, initial_admission_request_id)
        )
        """);
  }
}
