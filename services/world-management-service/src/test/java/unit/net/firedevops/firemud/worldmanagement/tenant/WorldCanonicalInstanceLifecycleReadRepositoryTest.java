package unit.net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.UUID;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceAssociationRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceLifecycleReadRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftTopologyInputGraph;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class WorldCanonicalInstanceLifecycleReadRepositoryTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
  }

  @Test
  void rejectsAmbientTransactionBeforeOpeningIndependentOwnerSnapshot() {
    var dsl = mock(DSLContext.class);
    var manager = mock(PlatformTransactionManager.class);
    var association = mock(WorldCanonicalInstanceAssociationRepository.class);
    var repository = new WorldCanonicalInstanceLifecycleReadRepository(dsl, manager, association);
    TransactionSynchronizationManager.setActualTransactionActive(true);

    assertThatThrownBy(() -> repository.read(request()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must not join an ambient transaction");
    verifyNoInteractions(dsl, manager, association);
  }

  @Test
  void acceptsOnlyClosedFrozenGraphProfilesAndSupportedInboundClosure() {
    assertThatCode(() -> validateFrozenGraphProfile(graphV2(), 2)).doesNotThrowAnyException();
    assertThatThrownBy(() -> validateFrozenGraphProfile(graphV2(), 3))
        .hasMessageContaining("missing or unsupported fields");
    assertThatCode(() -> validateFrozenGraphProfile(graphV3(), 3)).doesNotThrowAnyException();

    ObjectNode unsupportedClosure = graphV3();
    JsonNode familyCounts = unsupportedClosure.get("inboundSourceClosure").get("familyCounts");
    ((ObjectNode) familyCounts.get(0)).put("count", 1);
    assertThatThrownBy(() -> validateFrozenGraphProfile(unsupportedClosure, 3))
        .hasMessageContaining("outside the supported profile");
  }

  private static void validateFrozenGraphProfile(JsonNode graph, int expectedSchemaVersion) {
    try {
      Method validator =
          WorldCanonicalInstanceLifecycleReadRepository.class.getDeclaredMethod(
              "requireFrozenGraphProfile", JsonNode.class, int.class);
      validator.setAccessible(true);
      validator.invoke(null, graph, expectedSchemaVersion);
    } catch (InvocationTargetException failure) {
      if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
      throw new IllegalStateException("Frozen graph profile validation failed", failure.getCause());
    } catch (ReflectiveOperationException failure) {
      throw new IllegalStateException("Frozen graph profile validator is unavailable", failure);
    }
  }

  private static ObjectNode graphV2() {
    ObjectNode graph = JSON.createObjectNode();
    graph.put("schemaVersion", "2");
    graph.put("canonicalTenantId", "11111111-1111-4111-8111-111111111111");
    graph.put("canonicalVersionId", "22222222-2222-4222-8222-222222222222");
    graph.putArray("rows");
    return graph;
  }

  private static ObjectNode graphV3() {
    ObjectNode graph = graphV2();
    graph.put("schemaVersion", "3");
    ObjectNode closure = graph.putObject("inboundSourceClosure");
    closure.put("schemaVersion", 1);
    var familyCounts = closure.putArray("familyCounts");
    for (var family : WorldDraftTopologyInputGraph.INBOUND_SOURCE_FAMILY_ORDER) {
      ObjectNode familyCount = familyCounts.addObject();
      familyCount.put("family", family.name());
      familyCount.put("count", 0);
    }
    return graph;
  }

  private static WorldCanonicalInstanceLifecycleEvidence.Request request() {
    return new WorldCanonicalInstanceLifecycleEvidence.Request(
        1,
        UUID.fromString("11111111-1111-4111-8111-111111111111"),
        "test",
        UUID.fromString("22222222-2222-4222-8222-222222222222"),
        "starter-world",
        UUID.fromString("33333333-3333-4333-8333-333333333333"),
        UUID.fromString("44444444-4444-4444-8444-444444444444"),
        "SHARED",
        true,
        "control-request",
        UUID.fromString("55555555-5555-4555-8555-555555555555"),
        "sha256:" + "a".repeat(64),
        "sha256:" + "b".repeat(64),
        "sha256:" + "c".repeat(64));
  }
}
