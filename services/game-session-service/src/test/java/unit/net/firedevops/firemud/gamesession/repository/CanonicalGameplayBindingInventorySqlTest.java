package net.firedevops.firemud.gamesession.repository;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAccountCoverageEvidence;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingIdentity;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingTransitionRequest;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingTransitionSnapshot;
import net.firedevops.firemud.gamesession.binding.CanonicalIssuerReservationFenceEvidence;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.invocation.Invocation;

class CanonicalGameplayBindingInventorySqlTest {
  private static final Pattern INSERT =
      Pattern.compile(
          "(?is)INSERT\\s+INTO\\s+([a-z0-9_]+)\\s*\\((.*?)\\)\\s*VALUES\\s*\\((.*?)\\)");
  private static final long RUNTIME_GAME_INSTANCE_ID = 918273645L;
  private static final BigInteger REGION_EPOCH = new BigInteger("76543210987654321");

  @Test
  void everyInventoryInsertHelperKeepsColumnsValuesAndBindingsAligned() throws Exception {
    DSLContext dsl = mock(DSLContext.class);
    Fixture fixture = fixture();

    Record coverageControl = mock(Record.class);
    Mockito.when(coverageControl.get("state", String.class))
        .thenReturn("NO_ACTIVE_ACCOUNT_WIDE_FLOW");
    Mockito.doReturn(coverageControl)
        .when(dsl)
        .fetchOne(Mockito.anyString(), Mockito.any(Object[].class));

    invoke("ensureGenerationRow", dsl, fixture.request().candidate());
    invoke("lockAccountCoverageEvidence", dsl, fixture.request().candidate().accountId());
    invoke("insertCandidate", dsl, fixture.request(), fixture.prepared(), fixture.partitionId());
    invoke("insertTransition", dsl, fixture.request(), fixture.prepared(), null);
    invoke("insertAccountObligations", dsl, fixture.request(), fixture.prepared(), null, null);

    Record activeBinding = mock(Record.class);
    UUID priorAccountId = UUID.fromString("814f3be2-c891-4a67-bdd1-d73a5c72d43e");
    UUID priorTenantId = UUID.fromString("245f3e61-b457-4ce2-b634-1d97a45fb37a");
    UUID priorFence = UUID.fromString("3c5b9d6f-0504-42f1-8d2c-f55d84a1bd33");
    Mockito.when(activeBinding.get("binding_ref", byte[].class)).thenReturn(new byte[] {4, 2, 7});
    Mockito.when(activeBinding.get("account_id", UUID.class)).thenReturn(priorAccountId);
    Mockito.when(activeBinding.get("tenant_id", UUID.class)).thenReturn(priorTenantId);
    Mockito.when(activeBinding.get("binding_generation", BigDecimal.class))
        .thenReturn(new BigDecimal("314159"));
    Mockito.when(activeBinding.get("account_index_fence", UUID.class)).thenReturn(priorFence);
    invoke(
        "insertAccountObligations",
        dsl,
        fixture.request(),
        fixture.prepared(),
        activeBinding,
        null);

    invoke(
        "insertReservation",
        dsl,
        fixture.request(),
        fixture.prepared(),
        fixture.partitionId(),
        fixture.prepared().inventoryRevision());
    invoke("insertIssuerObligation", dsl, fixture.prepared());
    invoke("insertRegionObligation", dsl, fixture.prepared());

    List<InsertCall> inserts = capturedInserts(dsl);
    assertEquals(10, inserts.size(), "all nine INSERT constructions and the repeated ADD branch");
    Map<String, List<BoundInsert>> byTable = new HashMap<>();
    for (InsertCall insert : inserts) {
      BoundInsert bound = validateShapeAndBind(insert);
      byTable.computeIfAbsent(bound.table(), ignored -> new ArrayList<>()).add(bound);
    }

    assertEquals(1, byTable.get("game_session_canonical_binding_generation").size());
    assertEquals(1, byTable.get("game_session_canonical_account_coverage_control").size());
    assertEquals(1, byTable.get("game_session_canonical_gameplay_binding_inventory").size());
    assertEquals(1, byTable.get("game_session_canonical_binding_transition").size());
    List<BoundInsert> accountObligations =
        byTable.get("game_session_canonical_binding_account_index_obligation");
    assertEquals(3, accountObligations.size());
    assertEquals("ADD_OR_RETAIN", accountObligations.get(0).literal("action"));
    assertEquals("ADD_OR_RETAIN", accountObligations.get(1).literal("action"));
    assertEquals("REMOVE", accountObligations.get(2).literal("action"));
    assertEquals(
        accountObligations.get(0).value("transition_id"),
        accountObligations.get(1).value("transition_id"));
    assertArrayEquals(
        (byte[]) accountObligations.get(0).value("binding_ref"),
        (byte[]) accountObligations.get(1).value("binding_ref"));
    assertEquals(
        accountObligations.get(0).value("account_id"),
        accountObligations.get(1).value("account_id"));
    assertEquals(
        accountObligations.get(0).value("tenant_id"), accountObligations.get(1).value("tenant_id"));
    assertEquals(
        accountObligations.get(0).value("binding_generation"),
        accountObligations.get(1).value("binding_generation"));
    assertEquals(
        accountObligations.get(0).value("account_index_fence"),
        accountObligations.get(1).value("account_index_fence"));
    assertEquals(
        accountObligations.get(0).value("inventory_revision"),
        accountObligations.get(1).value("inventory_revision"));
    assertTrue(accountObligations.get(0).isBoundNull("expected_prior_generation"));
    assertTrue(accountObligations.get(1).isBoundNull("expected_prior_generation"));
    BoundInsert removeObligation = accountObligations.get(2);
    assertEquals(priorAccountId, removeObligation.value("account_id"));
    assertEquals(priorTenantId, removeObligation.value("tenant_id"));
    assertArrayEquals(new byte[] {4, 2, 7}, (byte[]) removeObligation.value("binding_ref"));
    assertEquals(new BigDecimal("314159"), removeObligation.value("binding_generation"));
    assertEquals(new BigDecimal("314159"), removeObligation.value("expected_prior_generation"));
    assertEquals(priorFence, removeObligation.value("account_index_fence"));
    assertEquals(1, byTable.get("game_session_canonical_issuer_partition_reservation").size());
    assertEquals(1, byTable.get("game_session_canonical_binding_issuer_index_obligation").size());
    assertEquals(1, byTable.get("game_session_canonical_binding_region_bridge_obligation").size());

    BoundInsert candidate = byTable.get("game_session_canonical_gameplay_binding_inventory").get(0);
    assertEquals(RUNTIME_GAME_INSTANCE_ID, candidate.value("runtime_game_instance_id"));
    assertEquals(new BigDecimal(REGION_EPOCH), candidate.value("region_epoch"));
    assertEquals("CANDIDATE_PREPARED", candidate.literal("lifecycle"));

    BoundInsert reservation =
        byTable.get("game_session_canonical_issuer_partition_reservation").get(0);
    assertEquals(RUNTIME_GAME_INSTANCE_ID, reservation.value("runtime_game_instance_id"));
    assertEquals(new BigDecimal(REGION_EPOCH), reservation.value("region_epoch"));
    assertEquals("RESERVED", reservation.literal("lifecycle"));

    BoundInsert regionObligation =
        byTable.get("game_session_canonical_binding_region_bridge_obligation").get(0);
    assertEquals(RUNTIME_GAME_INSTANCE_ID, regionObligation.value("runtime_game_instance_id"));
    assertEquals(new BigDecimal(REGION_EPOCH), regionObligation.value("region_epoch"));
    assertEquals("REQUIRED", regionObligation.literal("status"));
  }

  private static void invoke(String methodName, Object... arguments) throws Exception {
    Method method =
        Arrays.stream(CanonicalGameplayBindingInventoryRepository.class.getDeclaredMethods())
            .filter(candidate -> candidate.getName().equals(methodName))
            .filter(candidate -> candidate.getParameterCount() == arguments.length)
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing helper " + methodName));
    method.setAccessible(true);
    try {
      method.invoke(null, arguments);
    } catch (InvocationTargetException failure) {
      Throwable cause = failure.getCause();
      if (cause instanceof Exception exception) {
        throw exception;
      }
      throw failure;
    }
  }

  private static List<InsertCall> capturedInserts(DSLContext dsl) {
    List<InsertCall> inserts = new ArrayList<>();
    for (Invocation invocation : Mockito.mockingDetails(dsl).getInvocations()) {
      if (!invocation.getMethod().getName().equals("execute")) {
        continue;
      }
      Object[] arguments = invocation.getRawArguments();
      if (arguments.length == 2 && arguments[0] instanceof String sql) {
        Object[] bindings = (Object[]) arguments[1];
        inserts.add(new InsertCall(sql, bindings));
      }
    }
    return inserts;
  }

  private static BoundInsert validateShapeAndBind(InsertCall call) {
    Matcher matcher = INSERT.matcher(call.sql());
    assertTrue(matcher.find(), () -> "not a captured INSERT statement: " + call.sql());
    List<String> columns = splitSqlList(matcher.group(2));
    List<String> expressions = splitSqlList(matcher.group(3));
    assertEquals(columns.size(), expressions.size(), "column/value arity: " + call.sql());

    List<Object> bindings = Arrays.asList(call.bindings());
    Map<String, Object> boundValues = new HashMap<>();
    Map<String, String> literals = new HashMap<>();
    int bindIndex = 0;
    for (int index = 0; index < columns.size(); index++) {
      String expression = expressions.get(index).strip();
      String column = columns.get(index).strip();
      if (expression.equals("?")) {
        assertTrue(bindIndex < bindings.size(), "missing bound argument for " + column);
        boundValues.put(column, bindings.get(bindIndex++));
      } else {
        literals.put(column, expression.replaceAll("^'|'$", ""));
      }
    }
    assertEquals(bindings.size(), bindIndex, "placeholder/binding arity: " + call.sql());
    long placeholders = countPlaceholders(matcher.group(3));
    assertEquals(placeholders, bindings.size(), "SQL placeholder count: " + call.sql());
    return new BoundInsert(matcher.group(1), boundValues, literals);
  }

  private static List<String> splitSqlList(String sql) {
    List<String> values = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean inString = false;
    for (int index = 0; index < sql.length(); index++) {
      char character = sql.charAt(index);
      if (character == '\'' && (index + 1 >= sql.length() || sql.charAt(index + 1) != '\'')) {
        inString = !inString;
      } else if (character == '\'' && inString && index + 1 < sql.length()) {
        current.append(character).append(sql.charAt(++index));
        continue;
      }
      if (character == ',' && !inString) {
        values.add(current.toString().strip());
        current.setLength(0);
      } else {
        current.append(character);
      }
    }
    values.add(current.toString().strip());
    return values;
  }

  private static long countPlaceholders(String sql) {
    boolean inString = false;
    long count = 0;
    for (int index = 0; index < sql.length(); index++) {
      char character = sql.charAt(index);
      if (character == '\'' && (index + 1 >= sql.length() || sql.charAt(index + 1) != '\'')) {
        inString = !inString;
      } else if (character == '\'' && inString && index + 1 < sql.length()) {
        index++;
      } else if (character == '?' && !inString) {
        count++;
      }
    }
    return count;
  }

  private static Fixture fixture() {
    UUID accountId = UUID.fromString("0c3c3b79-a20c-4e16-9e46-33e366dca0a1");
    UUID tenantId = UUID.fromString("d0fe2aae-67ef-4cdf-b6d9-9be8f61ba6c3");
    UUID namespaceId = UUID.fromString("1ad8832a-b20f-4b0e-a2ec-8b1d7bfa6c9b");
    UUID gameInstanceId = UUID.fromString("a12c284b-808b-4e32-b9ee-7680c677c7f3");
    UUID transitionId = UUID.fromString("17803a16-4182-4f41-99c2-69e7e540836b");
    UUID reservationId = UUID.fromString("73e51bc6-a34a-47d4-8778-8755346145d2");
    UUID reservationFence = UUID.fromString("e5a00f67-0048-49e6-9138-b96260947a64");
    UUID accountFence = UUID.fromString("fca5bb6b-cebe-4dc3-b28e-4176b5e630d0");
    CanonicalGameplayBindingIdentity identity =
        new CanonicalGameplayBindingIdentity(
            accountId,
            tenantId,
            namespaceId,
            UUID.fromString("d2b930f4-2124-4d0a-a7cb-f468ebc101b9"),
            "SHARED",
            gameInstanceId,
            RUNTIME_GAME_INSTANCE_ID,
            "f0f46b01-57f6-47e8-a714-9fbcefe49b56",
            UUID.fromString("e375e81b-9c7a-40e5-b361-0f39bd06f4c1"),
            REGION_EPOCH,
            UUID.fromString("b8c9ff0c-48a3-4388-ae0a-03f4f5a3e721"),
            BigInteger.valueOf(43),
            BigInteger.valueOf(47),
            BigInteger.valueOf(53),
            BigInteger.valueOf(59));
    CanonicalIssuerReservationFenceEvidence reservationEvidence =
        new CanonicalIssuerReservationFenceEvidence(
            reservationId,
            reservationFence,
            UUID.fromString("14a57b1d-1179-4d6f-908d-67105b0564d3"),
            BigInteger.valueOf(61),
            BigInteger.valueOf(67),
            BigInteger.valueOf(71));
    CanonicalGameplayBindingTransitionRequest request =
        new CanonicalGameplayBindingTransitionRequest(
            transitionId, accountFence, identity, reservationEvidence, null, null);
    BigInteger bindingGeneration = BigInteger.valueOf(73);
    BigInteger inventoryRevision = BigInteger.valueOf(79);
    CanonicalGameplayBindingTransitionSnapshot prepared =
        new CanonicalGameplayBindingTransitionSnapshot(
            transitionId,
            identity,
            bindingGeneration,
            identity.bindingRef(),
            inventoryRevision,
            reservationId,
            reservationFence,
            accountFence,
            null,
            null,
            CanonicalGameplayBindingTransitionSnapshot.Status.PREPARED,
            CanonicalGameplayAccountCoverageEvidence.noActiveFlow());
    return new Fixture(
        request, prepared, identity.bindingRef().partitionId(identity.issuerIndexPartitionCount()));
  }

  private record Fixture(
      CanonicalGameplayBindingTransitionRequest request,
      CanonicalGameplayBindingTransitionSnapshot prepared,
      BigInteger partitionId) {}

  private record InsertCall(String sql, Object[] bindings) {}

  private record BoundInsert(
      String table, Map<String, Object> values, Map<String, String> literals) {
    Object value(String column) {
      Object result = values.get(column);
      assertNotNull(result, "missing bound column " + column + " in " + table);
      return result;
    }

    String literal(String column) {
      String result = literals.get(column);
      assertNotNull(result, "missing literal column " + column + " in " + table);
      return result;
    }

    boolean isBoundNull(String column) {
      return values.containsKey(column) && values.get(column) == null;
    }
  }
}
