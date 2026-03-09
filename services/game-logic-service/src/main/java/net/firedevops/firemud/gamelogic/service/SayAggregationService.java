package net.firedevops.firemud.gamelogic.service;

import io.grpc.StatusRuntimeException;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.entitymanagement.v1.EntityManagementServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.EntityType;
import net.firedevops.firemud.entitymanagement.v1.ListRoomEntitiesRequest;
import net.firedevops.firemud.entitymanagement.v1.ListRoomEntitiesResponse;
import net.firedevops.firemud.entitymanagement.v1.RoomEntity;
import net.firedevops.firemud.gamelogic.v1.BroadcastSayRequest;
import net.firedevops.firemud.gamelogic.v1.BroadcastSayResponse;
import net.firedevops.firemud.gamelogic.v1.ChatAlias;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import net.firedevops.firemud.shared.v1.RoomInstanceRef;
import net.firedevops.firemud.socialgroups.v1.ChatType;
import net.firedevops.firemud.socialgroups.v1.SendMessageRequest;
import net.firedevops.firemud.socialgroups.v1.SendMessageResponse;
import net.firedevops.firemud.socialgroups.v1.SocialGroupsServiceGrpc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SayAggregationService {
  private static final Logger LOG = LoggerFactory.getLogger(SayAggregationService.class);
  private static final int MAX_MESSAGE_LENGTH = 512;

  private final SocialGroupsServiceGrpc.SocialGroupsServiceBlockingStub socialStub;
  private final EntityManagementServiceGrpc.EntityManagementServiceBlockingStub entityStub;
  private final MeterRegistry meterRegistry;

  public BroadcastSayResponse broadcast(BroadcastSayRequest request) {
    String normalizedText = normalizeText(request.getText());
    BroadcastSayResponse.Builder builder =
        BroadcastSayResponse.newBuilder().setMessage(normalizedText);

    if (normalizedText.isBlank()) {
      return errorResponse(builder, "INVALID_ARGUMENT", "Message must not be empty");
    }
    if (normalizedText.length() > MAX_MESSAGE_LENGTH) {
      return errorResponse(
          builder,
          "INVALID_ARGUMENT",
          "Message length exceeds " + MAX_MESSAGE_LENGTH + " characters");
    }

    ListRoomEntitiesResponse roomEntities;
    try {
      roomEntities =
          entityStub.listRoomEntities(
              ListRoomEntitiesRequest.newBuilder()
                  .setTenantId(request.getTenantId())
                  .setRoomInstance(resolveRoomInstance(request))
                  .build());
    } catch (StatusRuntimeException ex) {
      LOG.warn("EntityManagementService unavailable for SAY broadcast", ex);
      return errorResponse(builder, ex, "EntityManagementService");
    }

    if (roomEntities.hasError()) {
      return errorResponse(builder, roomEntities.getError(), "EntityManagementService");
    }

    builder.addAllDeliveredTo(buildDeliveredTo(request.getPlayerId(), roomEntities));
    builder.addAllNpcEchoes(buildNpcEchoes(roomEntities));

    SendMessageResponse socialResponse;
    try {
      socialResponse =
          socialStub.sendMessage(
              SendMessageRequest.newBuilder()
                  .setTenantId(request.getTenantId())
                  .setSenderId(request.getPlayerId())
                  .setType(mapAlias(request.getAlias()))
                  .setContent(normalizedText)
                  .build());
    } catch (StatusRuntimeException ex) {
      LOG.warn("SocialGroupsService unavailable for SAY broadcast", ex);
      return errorResponse(builder, ex, "SocialGroupsService");
    }

    if (!socialResponse.getSuccess()) {
      ErrorDetail error = socialResponse.hasError() ? socialResponse.getError() : genericError();
      recordAppError(error.getCode());
      return builder.setSuccess(false).setError(error).build();
    }

    return builder.setSuccess(true).build();
  }

  private List<String> buildDeliveredTo(String speakerId, ListRoomEntitiesResponse roomEntities) {
    String speakerName = findSpeakerName(speakerId, roomEntities);
    TreeSet<String> attendees =
        roomEntities.getEntitiesList().stream()
            .map(RoomEntity::getDisplayName)
            .filter(name -> name != null && !name.isBlank())
            .map(String::trim)
            .filter(name -> !name.equals(speakerName))
            .collect(Collectors.toCollection(TreeSet::new));
    List<String> delivered = new ArrayList<>();
    delivered.add(speakerName);
    delivered.addAll(attendees);
    return delivered;
  }

  private List<String> buildNpcEchoes(ListRoomEntitiesResponse roomEntities) {
    return roomEntities.getEntitiesList().stream()
        .filter(entity -> entity.getEntityType() == EntityType.NPC)
        .map(RoomEntity::getDisplayName)
        .filter(name -> name != null && !name.isBlank())
        .map(String::trim)
        .collect(Collectors.toCollection(TreeSet::new))
        .stream()
        .collect(Collectors.toList());
  }

  private String findSpeakerName(String speakerId, ListRoomEntitiesResponse roomEntities) {
    Optional<String> match =
        roomEntities.getEntitiesList().stream()
            .filter(entity -> entity.getEntityId().equals(speakerId))
            .map(RoomEntity::getDisplayName)
            .filter(name -> name != null && !name.isBlank())
            .map(String::trim)
            .findFirst();
    return match.orElse(speakerId);
  }

  private ChatType mapAlias(ChatAlias alias) {
    return switch (alias) {
      case YELL -> ChatType.CHAT_TYPE_SAY;
      case WHISPER -> ChatType.CHAT_TYPE_SAY;
      case SAY -> ChatType.CHAT_TYPE_SAY;
      default -> ChatType.CHAT_TYPE_SAY;
    };
  }

  private String normalizeText(String text) {
    return text == null ? "" : text.trim();
  }

  private BroadcastSayResponse errorResponse(
      BroadcastSayResponse.Builder builder, String code, String message) {
    recordAppError(code);
    builder.setSuccess(false);
    builder.setError(ErrorDetail.newBuilder().setCode(code).setMessage(message).build());
    return builder.build();
  }

  private BroadcastSayResponse errorResponse(
      BroadcastSayResponse.Builder builder, StatusRuntimeException ex, String source) {
    String description =
        Optional.ofNullable(ex.getStatus().getDescription())
            .filter(s -> !s.isBlank())
            .orElse("unreachable");
    return errorResponse(builder, ex.getStatus().getCode().name(), source + ": " + description);
  }

  private BroadcastSayResponse errorResponse(
      BroadcastSayResponse.Builder builder, ErrorDetail detail, String source) {
    String code = detail.getCode();
    if (code == null || code.isBlank()) {
      code = "UNAVAILABLE";
    }
    String message =
        Optional.ofNullable(detail.getMessage()).filter(s -> !s.isBlank()).orElse("unreachable");
    return errorResponse(builder, code, source + ": " + message);
  }

  private void recordAppError(String code) {
    if (code == null || code.isBlank()) {
      code = "UNAVAILABLE";
    }
    meterRegistry.counter("grpc.app_error", "code", code).increment();
  }

  private ErrorDetail genericError() {
    return ErrorDetail.newBuilder()
        .setCode("UNAVAILABLE")
        .setMessage("Social service reported an unknown failure")
        .build();
  }

  @SuppressWarnings("deprecation")
  private RoomInstanceRef resolveRoomInstance(BroadcastSayRequest request) {
    if (request.hasRoomInstance()) {
      return request.getRoomInstance();
    }
    return RoomInstanceRef.newBuilder()
        .setTenantId(request.getTenantId())
        .setRoomInstanceId(request.getRoomId())
        .build();
  }
}
