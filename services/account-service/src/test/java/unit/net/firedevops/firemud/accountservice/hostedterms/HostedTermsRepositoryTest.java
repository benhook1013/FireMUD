package unit.net.firedevops.firemud.accountservice.hostedterms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.accountservice.hostedterms.HostedTermsRepository;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class HostedTermsRepositoryTest {
  @Test
  void acceptanceReadbackRejectsAffirmativeActionEvidenceDigestMismatch() {
    byte[] actionEvidence = new byte[] {1, 2, 3};
    HostedTermsRepository repository = repository(row(actionEvidence, "sha256:" + "a".repeat(64)));

    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertThatThrownBy(() -> repository.readAcceptanceByRequest(UUID.randomUUID()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("digest");
    } finally {
      TransactionSynchronizationManager.clear();
    }
  }

  @Test
  void acceptanceReadbackAcceptsTheStoredAffirmativeActionDigest() {
    byte[] actionEvidence = new byte[] {1, 2, 3};
    String actionDigest = digest(actionEvidence);
    HostedTermsRepository repository = repository(row(actionEvidence, actionDigest));

    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      var acceptance = repository.readAcceptanceByRequest(UUID.randomUUID()).orElseThrow();
      assertThat(acceptance.affirmativeActionEvidence()).containsExactly(actionEvidence);
      assertThat(acceptance.affirmativeActionDigest()).isEqualTo(actionDigest);
    } finally {
      TransactionSynchronizationManager.clear();
    }
  }

  private HostedTermsRepository repository(Record acceptanceRow) {
    DSLContext dsl = mock(DSLContext.class);
    doReturn(acceptanceRow).when(dsl).fetchOne(anyString(), any(Object[].class));
    return new HostedTermsRepository(dsl);
  }

  private Record row(byte[] actionEvidence, String actionDigest) {
    byte[] partyEvidence = new byte[] {4, 5, 6};
    Map<String, Object> values = new HashMap<>();
    values.put("evidence_id", UUID.randomUUID());
    values.put("action_request_id", UUID.randomUUID());
    values.put("creator_party_id", UUID.randomUUID());
    values.put("account_uuid", UUID.randomUUID());
    values.put("hosted_scope_id", UUID.randomUUID());
    values.put("terms_version_id", UUID.randomUUID());
    values.put("document_digest", "sha256:" + "b".repeat(64));
    values.put("operator_legal_identity", "FireMUD Hosting LLC");
    values.put("operator_identity_version", 1L);
    values.put("source_version", 2L);
    values.put("material_generation", 1L);
    values.put("individual_party_source", partyEvidence);
    values.put("individual_party_source_digest", digest(partyEvidence));
    values.put("affirmative_action_evidence", actionEvidence);
    values.put("affirmative_action_digest", actionDigest);
    values.put("accepted_at", OffsetDateTime.now());
    return mock(
        Record.class,
        invocation -> {
          if (invocation.getMethod().getName().equals("get")
              && invocation.getArguments().length > 0) {
            return values.get(invocation.getArgument(0));
          }
          return null;
        });
  }

  private String digest(byte[] bytes) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError("SHA-256 must be available", impossible);
    }
  }
}
