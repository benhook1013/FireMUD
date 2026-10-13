package unit.net.firedevops.firemud.accountservice.creatorparty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.creatorparty.CreatorPartyEncoding;
import net.firedevops.firemud.accountservice.creatorparty.IndividualCreatorPartyRepository;
import net.firedevops.firemud.accountservice.creatorparty.IndividualCreatorPartyRepository.InitialAssociationReadback;
import net.firedevops.firemud.accountservice.creatorparty.IndividualCreatorPartySource;
import net.firedevops.firemud.accountservice.creatorparty.IndividualCreatorPartySource.VerificationStatus;
import net.firedevops.firemud.accountservice.repository.AccountTenantCreationBootstrapOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantCreationBootstrapOperationRepository.StoredOperation;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.FreshTenantCreatorDigest;
import net.firedevops.firemud.common.tenant.FreshTenantCreatorEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Mock-backed owner-boundary predicates only; this is not PostgreSQL lock or trigger proof. */
class IndividualCreatorPartyRepositoryTest {
  @AfterEach
  void clearTransactionMarkers() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void preparationLocksOnlyTenantAndDerivesExistingCandidateIdentities() {
    Fixture fixture = new Fixture();
    beginWritableTransaction();
    var scope = fixture.repository.lockExistingInitialAssociationScope(fixture.tenantId);
    assertThat(scope.accountId()).isEqualTo(fixture.accountId);
    assertThat(scope.creatorPartyId()).isEqualTo(fixture.party.creatorPartyId());
    assertThat(scope.associationRequestId()).isEqualTo(fixture.associationRequestId);
    verifyNoInteractions(fixture.bootstraps, fixture.freshTenants);
    verify(fixture.dsl, never())
        .fetchOne(
            org.mockito.ArgumentMatchers.contains("account_individual_creator_party_sources"),
            any(Object[].class));
    fixture.assertNoWrites();
  }

  @Test
  void preparationRejectsMissingIdentityAndAbsentTransaction() {
    Fixture fixture = new Fixture();
    assertThatThrownBy(
            () -> fixture.repository.lockExistingInitialAssociationScope(fixture.tenantId))
        .isInstanceOf(IllegalStateException.class);
    beginWritableTransaction();
    fixture.associationByTenantFields.remove("initiating_account_uuid");
    assertThatThrownBy(
            () -> fixture.repository.lockExistingInitialAssociationScope(fixture.tenantId))
        .isInstanceOf(RuntimeException.class);
    verifyNoInteractions(fixture.bootstraps, fixture.freshTenants);
  }

  @Test
  void derivesAndVerifiesTheExactFreshInitialAssociationFromTenantOwnerRows() {
    Fixture fixture = new Fixture();

    InitialAssociationReadback readback = fixture.read();

    assertThat(readback.creatorEvidence()).isEqualTo(fixture.creatorEvidence);
    assertThat(readback.receipt())
        .isEqualTo(
            new IndividualCreatorPartyRepository.AssociationReceipt(
                fixture.associationRequestId,
                fixture.tenantId,
                fixture.party.creatorPartyId(),
                fixture.historyId,
                CreatorPartyEncoding.digest(fixture.resultPayload)));
    assertThat(readback.partySource()).isEqualTo(fixture.party);
    fixture.assertNoWrites();
  }

  @Test
  void missingAssociationFailsWithoutCreatingAReceiptOrHistory() {
    Fixture fixture = new Fixture();
    fixture.associationByTenant = null;

    fixture.rejects();
  }

  @Test
  void incompleteBootstrapAndAssociationIdentitiesFailClosed() {
    Fixture uncommittedBootstrap = new Fixture();
    uncommittedBootstrap.bootstrap = uncommittedBootstrap.withStatus("IN_PROGRESS");
    uncommittedBootstrap.whenBootstrapRead();
    uncommittedBootstrap.rejects();

    Fixture malformedAssociation = new Fixture();
    malformedAssociation.associationByTenantFields.remove("creator_party_id");
    malformedAssociation.rejects();
  }

  @Test
  void foreignInitiatorOrPartyCannotBeReadAsTheFreshCreatorAssociation() {
    Fixture foreignInitiator = new Fixture();
    foreignInitiator.associationByRequestFields.put("initiating_account_uuid", UUID.randomUUID());
    foreignInitiator.associationByTenantFields.put(
        "initiating_account_uuid",
        foreignInitiator.associationByRequestFields.get("initiating_account_uuid"));
    foreignInitiator.rejects();

    Fixture foreignParty = new Fixture();
    foreignParty.partyFields.put("account_uuid", UUID.randomUUID());
    foreignParty.rejects();
  }

  @Test
  void changedPartyBytesOrReceiptHistoryFailClosed() {
    Fixture changedParty = new Fixture();
    changedParty.partyFields.put("source_payload", new byte[] {8, 8});
    changedParty.rejects();

    Fixture changedReceipt = new Fixture();
    changedReceipt.associationByRequestFields.put("result_digest", "sha256:" + "f".repeat(64));
    changedReceipt.rejects();
  }

  @Test
  void retainedOrTombstonedHistoryCannotBeReinterpretedAsInitialAssociation() {
    Fixture retained = new Fixture();
    retained.historyFields.put("origin", "RETAINED");
    retained.rejects();

    Fixture tombstoned = new Fixture();
    tombstoned.historyFields.put("origin", "TOMBSTONE");
    tombstoned.rejects();

    Fixture multipleHistory = new Fixture();
    multipleHistory.historyCountFields.put("count", 2L);
    multipleHistory.rejects();
  }

  private static final class Fixture {
    private final DSLContext dsl = mock(DSLContext.class);
    private final FreshTenantIdentityAssociationRepository freshTenants =
        mock(FreshTenantIdentityAssociationRepository.class);
    private final AccountTenantCreationBootstrapOperationRepository bootstraps =
        mock(AccountTenantCreationBootstrapOperationRepository.class);
    private final UUID accountId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();
    private final UUID creatorBootstrapRequestId = UUID.randomUUID();
    private final UUID associationRequestId = UUID.randomUUID();
    private final UUID historyId = UUID.randomUUID();
    private final UUID creationRequestId = UUID.randomUUID();
    private final UUID creationOperationId = UUID.randomUUID();
    private final UUID accountAuthorizationOperationId = creatorBootstrapRequestId;
    private final String accountAuthorizationDigest = sha256('a');
    private final FreshTenantCreationEvidence creation = creation();
    private final FreshTenantCreatorEvidence creatorEvidence = creatorEvidence();
    private final IndividualCreatorPartySource party = party();
    private final byte[] requestPayload =
        CreatorPartyEncoding.request(associationRequestId, creatorEvidence, party);
    private final byte[] resultPayload = CreatorPartyEncoding.result(historyId, requestPayload);
    private final Map<String, Object> associationByTenantFields = new HashMap<>();
    private final Map<String, Object> associationByRequestFields = new HashMap<>();
    private final Map<String, Object> partyFields = new HashMap<>();
    private final Map<String, Object> historyFields = new HashMap<>();
    private final Map<String, Object> historyCountFields = new HashMap<>();
    private StoredOperation bootstrap;
    private StoredOperation currentBootstrap;
    private Record associationByTenant;
    private final IndividualCreatorPartyRepository repository;

    private Fixture() {
      bootstrap = bootstrap("COMMITTED");
      currentBootstrap = bootstrap;
      associationByTenantFields.put("request_id", associationRequestId);
      associationByTenantFields.put("tenant_uuid", tenantId);
      associationByTenantFields.put("initiating_account_uuid", accountId);
      associationByTenantFields.put("creator_party_id", party.creatorPartyId());
      associationByTenantFields.put("creation_operation_id", creationOperationId);

      associationByRequestFields.put("request_id", associationRequestId);
      associationByRequestFields.put("tenant_uuid", tenantId);
      associationByRequestFields.put("initiating_account_uuid", accountId);
      associationByRequestFields.put("creator_party_id", party.creatorPartyId());
      associationByRequestFields.put("creation_operation_id", creationOperationId);
      associationByRequestFields.put(
          "creator_evidence_payload", CreatorPartyEncoding.creation(creatorEvidence));
      associationByRequestFields.put("party_source_payload", CreatorPartyEncoding.party(party));
      associationByRequestFields.put("request_payload", requestPayload);
      associationByRequestFields.put("request_digest", CreatorPartyEncoding.digest(requestPayload));
      associationByRequestFields.put("history_id", historyId);
      associationByRequestFields.put("result_payload", resultPayload);
      associationByRequestFields.put("result_digest", CreatorPartyEncoding.digest(resultPayload));
      associationByRequestFields.put(
          "committed_at", OffsetDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC));

      partyFields.put("creator_party_id", party.creatorPartyId());
      partyFields.put("account_uuid", accountId);
      partyFields.put("verification_status", VerificationStatus.VERIFIED.name());
      partyFields.put("identity_version", party.identityVersion());
      partyFields.put("policy_reference", party.policyReference());
      partyFields.put("policy_version", party.policyVersion());
      partyFields.put("verification_evidence_reference", party.verificationEvidenceReference());
      partyFields.put("verification_evidence_version", party.verificationEvidenceVersion());
      partyFields.put("source_version", party.sourceVersion());
      partyFields.put("source_payload", CreatorPartyEncoding.party(party));
      partyFields.put(
          "source_digest", CreatorPartyEncoding.digest(CreatorPartyEncoding.party(party)));

      historyFields.put("history_id", historyId);
      historyFields.put("origin", "FRESH_INITIAL");
      historyFields.put("tenant_uuid", tenantId);
      historyFields.put("creator_party_id", party.creatorPartyId());
      historyFields.put("source_version", 1L);
      historyFields.put("evidence_payload", resultPayload);
      historyFields.put("evidence_digest", CreatorPartyEncoding.digest(resultPayload));
      historyCountFields.put("count", 1L);

      associationByTenant = row(associationByTenantFields);
      when(freshTenants.read(tenantId)).thenReturn(Optional.of(creation));
      when(bootstraps.findRequestIdByTenantForUpdate(tenantId))
          .thenReturn(Optional.of(creatorBootstrapRequestId));
      when(bootstraps.findForUpdate(creatorBootstrapRequestId))
          .thenAnswer(ignored -> Optional.of(currentBootstrap));
      when(dsl.fetchOne(anyString(), any(Object[].class))).thenAnswer(this::fetch);
      when(dsl.fetchOne(anyString())).thenAnswer(this::fetch);
      repository = new IndividualCreatorPartyRepository(dsl, freshTenants, bootstraps);
    }

    private InitialAssociationReadback read() {
      beginWritableTransaction();
      try {
        return repository.readExistingInitialAssociation(tenantId);
      } finally {
        TransactionSynchronizationManager.clear();
      }
    }

    private void rejects() {
      beginWritableTransaction();
      try {
        assertThatThrownBy(() -> repository.readExistingInitialAssociation(tenantId))
            .isInstanceOf(IllegalStateException.class);
        assertNoWrites();
      } finally {
        TransactionSynchronizationManager.clear();
      }
    }

    private void assertNoWrites() {
      verify(dsl, never()).execute(anyString(), any(Object[].class));
    }

    private void whenBootstrapRead() {
      currentBootstrap = bootstrap;
      when(bootstraps.findForUpdate(creatorBootstrapRequestId))
          .thenAnswer(ignored -> Optional.of(currentBootstrap));
    }

    private StoredOperation withStatus(String status) {
      return bootstrap(status);
    }

    private Object fetch(org.mockito.invocation.InvocationOnMock invocation) {
      String sql = invocation.getArgument(0);
      if (sql.equals("SHOW transaction_isolation")) {
        Record isolation = mock(Record.class);
        when(isolation.get(0, String.class)).thenReturn("read committed");
        return isolation;
      }
      if (sql.contains("account_canonical_tenant_identity_claims")) {
        return row(Map.of("identity_kind", "FRESH_GAME_DESIGN"));
      }
      if (sql.contains("account_fresh_creator_party_association_operations")
          && sql.contains("WHERE tenant_uuid = ?")) {
        return associationByTenant;
      }
      if (sql.contains("account_individual_creator_party_sources")) {
        return row(partyFields);
      }
      if (sql.contains("WHERE request_id = ?")) {
        return row(associationByRequestFields);
      }
      if (sql.contains("SELECT count(*) AS count FROM account_tenant_creator_party_history")) {
        return row(historyCountFields);
      }
      if (sql.contains("account_tenant_creator_party_history WHERE history_id = ?")) {
        return row(historyFields);
      }
      throw new AssertionError("Unexpected repository SQL: " + sql);
    }

    private FreshTenantCreationEvidence creation() {
      String requestDigest = sha256('c');
      return new FreshTenantCreationEvidence(
          1,
          "firemud-test",
          creationRequestId,
          creationOperationId,
          requestDigest,
          tenantId,
          1,
          "test-game",
          "NEW_GAME_ROW",
          GameTenantCreationDigest.evidenceDigest(
              "firemud-test",
              creationRequestId,
              creationOperationId,
              requestDigest,
              tenantId,
              1,
              "test-game",
              "NEW_GAME_ROW"));
    }

    private FreshTenantCreatorEvidence creatorEvidence() {
      return new FreshTenantCreatorEvidence(
          1,
          creation,
          accountId,
          accountAuthorizationOperationId,
          accountAuthorizationDigest,
          FreshTenantCreatorDigest.evidenceDigest(
              1, creation, accountId, accountAuthorizationOperationId, accountAuthorizationDigest));
    }

    private IndividualCreatorPartySource party() {
      return new IndividualCreatorPartySource(
          UUID.randomUUID(),
          accountId,
          VerificationStatus.VERIFIED,
          1,
          "test-policy",
          1L,
          "test-individual-verification",
          1L,
          1);
    }

    private StoredOperation bootstrap(String status) {
      return new StoredOperation(
          creatorBootstrapRequestId,
          accountId,
          tenantId,
          creationRequestId,
          creationOperationId,
          accountAuthorizationOperationId,
          accountAuthorizationDigest,
          creatorEvidence.evidenceDigest(),
          CreatorPartyEncoding.creation(creatorEvidence),
          new byte[] {1},
          sha256('s'),
          new byte[] {2},
          sha256('r'),
          1L,
          1L,
          0L,
          status,
          1L,
          2L,
          1L,
          "ACTIVE",
          false,
          "[\"tenantAdmin\"]".getBytes(StandardCharsets.UTF_8),
          "account:auth-authority:v1:membership/" + accountId + "/" + tenantId,
          UUID.randomUUID().toString(),
          1L,
          "test-event",
          sha256('e'),
          false,
          new byte[] {3},
          UUID.randomUUID(),
          "ACCOUNT_TENANT_CREATOR_BOOTSTRAPPED",
          Instant.EPOCH,
          sha256('a'),
          new byte[] {4},
          new byte[] {5},
          sha256('b'),
          Instant.EPOCH);
    }
  }

  private static Record row(Map<String, Object> fields) {
    Record record = mock(Record.class);
    when(record.get(anyString())).thenAnswer(invocation -> fields.get(invocation.getArgument(0)));
    doAnswer(invocation -> fields.get(invocation.getArgument(0)))
        .when(record)
        .<Object>get(anyString(), ArgumentMatchers.<Class<Object>>any());
    return record;
  }

  private static void beginWritableTransaction() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
  }

  private static String sha256(char repeated) {
    return "sha256:" + String.valueOf(repeated).repeat(64);
  }
}
