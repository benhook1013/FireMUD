package unit.net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicyEvidence;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicySetEvidence;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.gamesession.client.GameDesignPublishedRealmPolicyClient;
import net.firedevops.firemud.gamesession.entity.PublishedRealmCatalogSnapshot;
import net.firedevops.firemud.gamesession.repository.GameSessionRetainedTenantAssociationRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionRetainedTenantAssociationRepository.RetainedTenantAssociationIdentity;
import net.firedevops.firemud.gamesession.repository.InitialAdmissionBindCatalogRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

class PublishedRealmCatalogOwnerServiceTest {
  private static final String NAMESPACE = "firemud";
  private static final UUID CANONICAL_TENANT_ID =
      UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final long GAME_SESSION_TENANT_ID = 70123L;
  private static final long SOURCE_GAME_ROW_ID = 91L;
  private static final String SOURCE_GAME_TENANT_KEY = "game-design-tenant-91";
  private static final String PROVENANCE_KIND = "RETAINED_GAME_V29";
  private static final long VERSION_ID = 44L;
  private static final ObjectMapper JSON = new ObjectMapper();

  private final GameDesignPublishedRealmPolicyClient gameDesignClient =
      mock(GameDesignPublishedRealmPolicyClient.class);
  private final GameSessionRetainedTenantAssociationRepository associationRepository =
      mock(GameSessionRetainedTenantAssociationRepository.class);
  private final InitialAdmissionBindCatalogRepository catalogRepository =
      mock(InitialAdmissionBindCatalogRepository.class);
  private final PlatformTransactionManager transactionManager =
      mock(PlatformTransactionManager.class);
  private final PublishedRealmCatalogOwnerService service =
      new PublishedRealmCatalogOwnerService(
          gameDesignClient,
          associationRepository,
          catalogRepository,
          transactionManager,
          NAMESPACE);

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void rejectsInvocationInsideActualTransactionBeforeAnyOwnerRead() {
    TransactionSynchronizationManager.setActualTransactionActive(true);

    assertThatThrownBy(
            () -> service.materializePublishedSnapshot(CANONICAL_TENANT_ID, VERSION_ID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("before the local catalog transaction starts");

    verifyNoInteractions(
        gameDesignClient, associationRepository, catalogRepository, transactionManager);
  }

  @Test
  void missingOrContradictoryMinimalAssociationDeniesBeforeGameDesignRead() {
    when(associationRepository.readMinimalAssociation(NAMESPACE, CANONICAL_TENANT_ID))
        .thenReturn(Optional.empty())
        .thenReturn(
            Optional.of(
                association(
                    "other",
                    CANONICAL_TENANT_ID,
                    GAME_SESSION_TENANT_ID,
                    SOURCE_GAME_ROW_ID,
                    SOURCE_GAME_TENANT_KEY,
                    PROVENANCE_KIND)))
        .thenReturn(
            Optional.of(
                association(
                    NAMESPACE,
                    UUID.randomUUID(),
                    GAME_SESSION_TENANT_ID,
                    SOURCE_GAME_ROW_ID,
                    SOURCE_GAME_TENANT_KEY,
                    PROVENANCE_KIND)));

    for (int attempt = 0; attempt < 3; attempt++) {
      assertThatThrownBy(
              () -> service.materializePublishedSnapshot(CANONICAL_TENANT_ID, VERSION_ID))
          .isInstanceOf(IllegalStateException.class);
    }

    verify(associationRepository, times(3))
        .readMinimalAssociation(NAMESPACE, CANONICAL_TENANT_ID);
    verifyNoInteractions(gameDesignClient, catalogRepository, transactionManager);
  }

  @Test
  void rejectsPolicySetWithWrongSourceProvenanceBeforeOwnerTransaction() {
    RetainedTenantAssociationIdentity association =
        association(
            NAMESPACE,
            CANONICAL_TENANT_ID,
            GAME_SESSION_TENANT_ID,
            SOURCE_GAME_ROW_ID,
            SOURCE_GAME_TENANT_KEY,
            PROVENANCE_KIND);
    when(associationRepository.readMinimalAssociation(NAMESPACE, CANONICAL_TENANT_ID))
        .thenReturn(Optional.of(association));
    when(
            gameDesignClient.listPublishedRealmEntryPolicies(
                CANONICAL_TENANT_ID.toString(), VERSION_ID))
        .thenReturn(
            policySet(
                CANONICAL_TENANT_ID,
                SOURCE_GAME_ROW_ID + 1,
                SOURCE_GAME_TENANT_KEY,
                PROVENANCE_KIND));

    assertThatThrownBy(
            () -> service.materializePublishedSnapshot(CANONICAL_TENANT_ID, VERSION_ID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("PUBLISHED_REALM_CATALOG_SOURCE_MISMATCH");

    verify(gameDesignClient)
        .listPublishedRealmEntryPolicies(CANONICAL_TENANT_ID.toString(), VERSION_ID);
    verifyNoInteractions(catalogRepository, transactionManager);
  }

  @Test
  void readsBothOwnersBeforeStartingReadCommittedTransactionAndKeepsIdentityFieldsDistinct() {
    RetainedTenantAssociationIdentity association =
        association(
            NAMESPACE,
            CANONICAL_TENANT_ID,
            GAME_SESSION_TENANT_ID,
            SOURCE_GAME_ROW_ID,
            SOURCE_GAME_TENANT_KEY,
            PROVENANCE_KIND);
    PublishedRealmEntryPolicySetEvidence policySet =
        policySet(
            CANONICAL_TENANT_ID,
            SOURCE_GAME_ROW_ID,
            SOURCE_GAME_TENANT_KEY,
            PROVENANCE_KIND);
    PublishedRealmCatalogSnapshot snapshot = mock(PublishedRealmCatalogSnapshot.class);
    when(associationRepository.readMinimalAssociation(NAMESPACE, CANONICAL_TENANT_ID))
        .thenReturn(Optional.of(association));
    when(
            gameDesignClient.listPublishedRealmEntryPolicies(
                CANONICAL_TENANT_ID.toString(), VERSION_ID))
        .thenReturn(policySet);
    when(transactionManager.getTransaction(any(TransactionDefinition.class)))
        .thenReturn(new SimpleTransactionStatus());
    when(catalogRepository.materializePublishedSnapshot(
            NAMESPACE,
            GAME_SESSION_TENANT_ID,
            CANONICAL_TENANT_ID,
            SOURCE_GAME_ROW_ID,
            SOURCE_GAME_TENANT_KEY,
            PROVENANCE_KIND,
            policySet))
        .thenReturn(snapshot);

    assertThat(service.materializePublishedSnapshot(CANONICAL_TENANT_ID, VERSION_ID))
        .isSameAs(snapshot);

    InOrder ordered =
        inOrder(associationRepository, gameDesignClient, transactionManager, catalogRepository);
    ordered.verify(associationRepository).readMinimalAssociation(NAMESPACE, CANONICAL_TENANT_ID);
    ordered
        .verify(gameDesignClient)
        .listPublishedRealmEntryPolicies(CANONICAL_TENANT_ID.toString(), VERSION_ID);
    ArgumentCaptor<TransactionDefinition> definition =
        ArgumentCaptor.forClass(TransactionDefinition.class);
    ordered.verify(transactionManager).getTransaction(definition.capture());
    ordered
        .verify(catalogRepository)
        .materializePublishedSnapshot(
            NAMESPACE,
            GAME_SESSION_TENANT_ID,
            CANONICAL_TENANT_ID,
            SOURCE_GAME_ROW_ID,
            SOURCE_GAME_TENANT_KEY,
            PROVENANCE_KIND,
            policySet);
    ordered.verify(transactionManager).commit(any(TransactionStatus.class));
    assertThat(definition.getValue().getPropagationBehavior())
        .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    assertThat(definition.getValue().getIsolationLevel())
        .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
    assertThat(GAME_SESSION_TENANT_ID).isNotEqualTo(SOURCE_GAME_ROW_ID);
  }

  private static RetainedTenantAssociationIdentity association(
      String namespace,
      UUID canonicalTenantId,
      long gameSessionTenantId,
      long sourceGameRowId,
      String sourceGameTenantKey,
      String provenanceKind) {
    return new RetainedTenantAssociationIdentity(
        namespace,
        canonicalTenantId,
        gameSessionTenantId,
        sourceGameRowId,
        sourceGameTenantKey,
        provenanceKind);
  }

  private static PublishedRealmEntryPolicySetEvidence policySet(
      UUID canonicalTenantId,
      long sourceGameRowId,
      String sourceGameTenantKey,
      String provenanceKind) {
    int versionNumber = 3;
    String workflow = "publish:realm-catalog-owner-test";
    String manifest = "manifest-realm-catalog-owner-test";
    String releaseIdentity =
        PublishedRealmEntryPolicyEvidence.releaseBundleIdentity(
            canonicalTenantId, VERSION_ID, workflow, manifest, JSON);
    String policyJson =
        "{\"schemaVersion\":1,\"worldSlug\":\"earth\",\"worldDisplayName\":\"Earth\","
            + "\"realmSlug\":\"main\",\"realmDisplayName\":\"Main\",\"visible\":true,"
            + "\"publicProduction\":true,\"stateScope\":\"SHARED\","
            + "\"entryPolicy\":\"PRESEEDED_ONLY\"}";
    PublishedRealmEntryPolicyEvidence evidence =
        PublishedRealmEntryPolicyEvidence.create(
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            canonicalTenantId,
            provenanceKind,
            sourceGameRowId,
            sourceGameTenantKey,
            VERSION_ID,
            versionNumber,
            301L,
            releaseIdentity,
            workflow,
            manifest,
            RealmEntryPolicy.parse(policyJson, JSON),
            JSON);
    return PublishedRealmEntryPolicySetEvidence.create(
        canonicalTenantId,
        VERSION_ID,
        versionNumber,
        releaseIdentity,
        workflow,
        manifest,
        List.of(evidence),
        JSON);
  }
}
