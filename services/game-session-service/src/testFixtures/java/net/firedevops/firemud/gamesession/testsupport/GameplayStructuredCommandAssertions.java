package net.firedevops.firemud.gamesession.testsupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Shared structured command-response assertions for FireMUD gameplay websocket suites. */
public final class GameplayStructuredCommandAssertions {
  private static final ObjectMapper JSON = new ObjectMapper();

  private GameplayStructuredCommandAssertions() {}

  public static JsonNode parseStructuredResponse(String payload) {
    try {
      return JSON.readTree(payload);
    } catch (Exception ex) {
      throw new AssertionError("Expected JSON payload but got: " + payload, ex);
    }
  }

  public static boolean isStructuredCommand(String payload, String commandType) {
    return isStructuredCommand(payload, commandType, null);
  }

  public static boolean isStructuredCommand(String payload, String commandType, String commandId) {
    return isStructuredCommand(payload, commandType, commandId, null);
  }

  public static boolean isStructuredCommand(
      String payload,
      String commandType,
      String commandId,
      String actionCategory,
      String... actionTags) {
    JsonNode json = parseStructuredResponse(payload);
    return "command_result".equals(json.path("eventType").asText())
        && commandType.equals(json.path("commandType").asText())
        && (commandId == null || commandId.equals(json.path("commandId").asText()))
        && (actionCategory == null || actionCategory.equals(json.path("actionCategory").asText()))
        && (actionTags.length == 0 || actualActionTags(json).equals(List.of(actionTags)));
  }

  public static JsonNode awaitStructuredCommand(
      GameplayWebSocketDriver client, int responseBaseline, String commandType) throws Exception {
    return awaitStructuredCommand(
        client, responseBaseline, commandType, null, client.waitTimeout());
  }

  public static JsonNode awaitStructuredCommand(
      GameplayWebSocketDriver client, int responseBaseline, String commandType, String commandId)
      throws Exception {
    return awaitStructuredCommand(
        client, responseBaseline, commandType, commandId, client.waitTimeout());
  }

  public static JsonNode awaitStructuredCommand(
      GameplayWebSocketDriver client, int responseBaseline, String commandType, Duration timeout)
      throws Exception {
    return awaitStructuredCommand(client, responseBaseline, commandType, null, timeout);
  }

  public static JsonNode awaitStructuredCommand(
      GameplayWebSocketDriver client,
      int responseBaseline,
      String commandType,
      String commandId,
      Duration timeout)
      throws Exception {
    long deadline = System.currentTimeMillis() + timeout.toMillis();
    while (System.currentTimeMillis() < deadline) {
      List<String> responses = client.responses();
      for (int index = responseBaseline; index < responses.size(); index++) {
        String payload = responses.get(index);
        if (isStructuredCommand(payload, commandType, commandId)) {
          return parseStructuredResponse(payload);
        }
      }
      Thread.sleep(50);
    }
    List<String> responses = client.responses();
    throw new AssertionError(
        "Expected structured "
            + commandType
            + (commandId == null ? "" : " commandId=" + commandId)
            + " response after baseline "
            + responseBaseline
            + ", got: "
            + responses);
  }

  public static JsonNode requirePayload(JsonNode envelope, String payloadType) {
    List<JsonNode> payloads = requirePayloads(envelope, payloadType);
    return payloads.get(0);
  }

  public static List<JsonNode> requirePayloads(JsonNode envelope, String payloadType) {
    List<JsonNode> payloads = new ArrayList<>();
    for (JsonNode output : envelope.path("outputs")) {
      if (payloadType.equals(output.path("payloadType").asText())) {
        payloads.add(output.path("payload"));
      }
    }
    if (payloads.isEmpty()) {
      throw new AssertionError("Missing payloadType=" + payloadType + " in envelope: " + envelope);
    }
    return payloads;
  }

  public static JsonNode awaitFirstPartyPlayerOutputPayload(
      GameplayWebSocketDriver client, int responseBaseline, String payloadType) throws Exception {
    return awaitFirstPartyPlayerOutputPayloads(client, responseBaseline, payloadType, 1).getFirst();
  }

  public static List<JsonNode> awaitFirstPartyPlayerOutputPayloads(
      GameplayWebSocketDriver client, int responseBaseline, String payloadType, int expectedCount)
      throws Exception {
    long deadline = System.currentTimeMillis() + client.waitTimeout().toMillis();
    while (System.currentTimeMillis() < deadline) {
      List<JsonNode> payloads = new ArrayList<>();
      List<String> responses = client.responses();
      for (String response : responses.subList(responseBaseline, responses.size())) {
        JsonNode envelope = parseStructuredResponse(response);
        if (!"player_output".equals(envelope.path("eventType").asText())) {
          continue;
        }
        for (JsonNode output : envelope.path("outputs")) {
          if (payloadType.equals(output.path("payloadType").asText())) {
            payloads.add(output.path("payload"));
          }
        }
      }
      if (payloads.size() >= expectedCount) {
        return List.copyOf(payloads);
      }
      Thread.sleep(50);
    }
    List<String> responses = client.responses();
    throw new AssertionError(
        "Expected "
            + expectedCount
            + " first-party player_output payload(s) of type="
            + payloadType
            + " after baseline "
            + responseBaseline
            + ", got: "
            + responses.subList(responseBaseline, responses.size()));
  }

  public static boolean containsKind(JsonNode envelope, String kind) {
    for (JsonNode output : envelope.path("outputs")) {
      if (kind.equals(output.path("kind").asText())) {
        return true;
      }
    }
    return false;
  }

  public static void requireStructuredCommand(
      JsonNode envelope, String commandType, String commandId) {
    requireStructuredCommand(envelope, commandType, commandId, null);
  }

  public static void requireStructuredCommand(
      JsonNode envelope,
      String commandType,
      String commandId,
      String actionCategory,
      String... actionTags) {
    if (!"command_result".equals(envelope.path("eventType").asText())) {
      throw new AssertionError("Expected command_result envelope but got: " + envelope);
    }
    if (!commandType.equals(envelope.path("commandType").asText())) {
      throw new AssertionError(
          "Expected commandType="
              + commandType
              + " but got: "
              + envelope.path("commandType").asText());
    }
    if (commandId != null && !commandId.equals(envelope.path("commandId").asText())) {
      throw new AssertionError(
          "Expected commandId=" + commandId + " but got: " + envelope.path("commandId").asText());
    }
    if (actionCategory != null
        && !actionCategory.equals(envelope.path("actionCategory").asText())) {
      throw new AssertionError(
          "Expected actionCategory="
              + actionCategory
              + " but got: "
              + envelope.path("actionCategory").asText());
    }
    if (actionTags.length > 0 && !actualActionTags(envelope).equals(List.of(actionTags))) {
      throw new AssertionError(
          "Expected actionTags=" + List.of(actionTags) + " but got: " + actualActionTags(envelope));
    }
  }

  private static List<String> actualActionTags(JsonNode envelope) {
    List<String> actual = new ArrayList<>();
    envelope.path("actionTags").forEach(tag -> actual.add(tag.asText()));
    return actual;
  }
}
