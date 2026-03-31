package net.firedevops.firemud.gamelogic.service;

import io.grpc.StatusRuntimeException;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.grpc.GrpcAppErrors;
import net.firedevops.firemud.entitymanagement.v1.EntityManagementServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.EntityType;
import net.firedevops.firemud.entitymanagement.v1.ListRoomEntitiesRequest;
import net.firedevops.firemud.entitymanagement.v1.ListRoomEntitiesResponse;
import net.firedevops.firemud.entitymanagement.v1.RoomEntity;
import net.firedevops.firemud.gamelogic.v1.CommunicationPerception;
import net.firedevops.firemud.gamelogic.v1.CommunicationRecipientRole;
import net.firedevops.firemud.gamelogic.v1.CommunicationRecipientView;
import net.firedevops.firemud.gamelogic.v1.CommunicationType;
import net.firedevops.firemud.gamelogic.v1.SendCommunicationRequest;
import net.firedevops.firemud.gamelogic.v1.SendCommunicationResponse;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import net.firedevops.firemud.shared.v1.RoomInstanceRef;
import net.firedevops.firemud.socialgroups.v1.ChatType;
import net.firedevops.firemud.socialgroups.v1.SendMessageRequest;
import net.firedevops.firemud.socialgroups.v1.SendMessageResponse;
import net.firedevops.firemud.socialgroups.v1.SocialGroupsServiceGrpc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class CommunicationAggregationService {
  private static final Logger LOG = LoggerFactory.getLogger(CommunicationAggregationService.class);
  private static final int MAX_MESSAGE_LENGTH = 512;
  private static final String OBSERVER_METADATA_ONLY_FLAG = "observer_metadata_only";

  private final SocialGroupsServiceGrpc.SocialGroupsServiceBlockingStub socialStub;
  private final EntityManagementServiceGrpc.EntityManagementServiceBlockingStub entityStub;
  private final MeterRegistry meterRegistry;

  public SendCommunicationResponse send(SendCommunicationRequest request) {
    String normalizedText = normalizeText(request.getText());
    SendCommunicationResponse.Builder builder =
        SendCommunicationResponse.newBuilder()
            .setType(request.getType())
            .setMessage(normalizedText);

    if (normalizedText.isBlank()) {
      return errorResponse(builder, "INVALID_ARGUMENT", "Message must not be empty");
    }
    if (normalizedText.length() > MAX_MESSAGE_LENGTH) {
      return errorResponse(
          builder,
          "INVALID_ARGUMENT",
          "Message length exceeds " + MAX_MESSAGE_LENGTH + " characters");
    }

    ListRoomEntitiesResponse roomEntities = loadRoomEntities(request);
    if (roomEntities.hasError()) {
      return errorResponse(builder, roomEntities.getError(), "EntityManagementService");
    }

    CommunicationAudience audience = resolveAudience(request, roomEntities);
    if (!audience.valid()) {
      return errorResponse(builder, "INVALID_ARGUMENT", audience.errorMessage());
    }

    builder.addAllDeliveredTo(audience.deliveredTo());
    builder.addAllNpcEchoes(audience.npcEchoes());
    if (StringUtils.hasText(audience.actorView())) {
      builder.setActorView(audience.actorView());
    }
    builder.addAllRecipientViews(audience.recipientViews());

    SendMessageResponse socialResponse;
    try {
      socialResponse =
          socialStub.sendMessage(
              SendMessageRequest.newBuilder()
                  .setTenantId(request.getTenantId())
                  .setSenderId(resolveSenderId(request))
                  .setType(mapType(request.getType()))
                  .setContent(normalizedText)
                  .setRecipientId(audience.recipientId().orElse(""))
                  .build());
    } catch (StatusRuntimeException ex) {
      LOG.warn("SocialGroupsService unavailable for communication send", ex);
      return errorResponse(builder, ex, "SocialGroupsService");
    }

    if (!socialResponse.getSuccess()) {
      ErrorDetail error = socialResponse.hasError() ? socialResponse.getError() : genericError();
      GrpcAppErrors.countIfError(meterRegistry, error);
      return builder.setSuccess(false).setError(error).build();
    }

    return builder.setSuccess(true).build();
  }

  private ListRoomEntitiesResponse loadRoomEntities(SendCommunicationRequest request) {
    if (!requiresRoomAudience(request.getType())) {
      return ListRoomEntitiesResponse.getDefaultInstance();
    }
    try {
      return entityStub.listRoomEntities(
          ListRoomEntitiesRequest.newBuilder()
              .setTenantId(request.getTenantId())
              .setRoomInstance(resolveRoomInstance(request))
              .build());
    } catch (StatusRuntimeException ex) {
      LOG.warn("EntityManagementService unavailable for communication send", ex);
      ErrorDetail detail =
          GrpcAppErrors.error(
              meterRegistry,
              ex.getStatus().getCode().name(),
              "EntityManagementService: "
                  + Optional.ofNullable(ex.getStatus().getDescription()).orElse("unreachable"));
      return ListRoomEntitiesResponse.newBuilder().setError(detail).build();
    }
  }

  private CommunicationAudience resolveAudience(
      SendCommunicationRequest request, ListRoomEntitiesResponse roomEntities) {
    return switch (request.getType()) {
      case SAY -> buildSayAudience(request.getCharacterId(), roomEntities, request.getText());
      case WHISPER -> buildWhisperAudience(request, roomEntities);
      case TELL -> buildTellAudience(request);
      default -> CommunicationAudience.invalid("Unsupported communication type");
    };
  }

  private CommunicationAudience buildSayAudience(
      String speakerId, ListRoomEntitiesResponse roomEntities, String rawText) {
    String speakerName = findSpeakerName(speakerId, roomEntities);
    String actorView = "You say, \"" + normalizeText(rawText) + "\"";
    TreeSet<String> attendees =
        roomEntities.getEntitiesList().stream()
            .map(RoomEntity::getDisplayName)
            .filter(StringUtils::hasText)
            .map(String::trim)
            .filter(name -> !name.equals(speakerName))
            .collect(Collectors.toCollection(TreeSet::new));
    List<String> delivered = new ArrayList<>();
    delivered.add(speakerName);
    delivered.addAll(attendees);
    return new CommunicationAudience(
        delivered,
        buildNpcEchoes(roomEntities),
        Optional.empty(),
        actorView,
        List.of(
            recipientView(
                speakerId,
                speakerName,
                CommunicationRecipientRole.COMMUNICATION_RECIPIENT_ROLE_ACTOR,
                CommunicationPerception.COMMUNICATION_PERCEPTION_FULL_CONTENT,
                actorView)),
        null);
  }

  private CommunicationAudience buildWhisperAudience(
      SendCommunicationRequest request, ListRoomEntitiesResponse roomEntities) {
    String targetName = request.getTargetCharacterName();
    if (!StringUtils.hasText(targetName)) {
      return CommunicationAudience.invalid("Whisper target is required");
    }

    Optional<RoomEntity> maybeTarget =
        roomEntities.getEntitiesList().stream()
            .filter(entity -> entity.getEntityType() == EntityType.PLAYER)
            .filter(entity -> !entity.getEntityId().equals(request.getCharacterId()))
            .filter(entity -> entity.getDisplayName().equalsIgnoreCase(targetName.trim()))
            .findFirst();
    if (maybeTarget.isEmpty()) {
      return CommunicationAudience.invalid("Target not present in room: " + targetName);
    }

    RoomEntity targetEntity = maybeTarget.orElseThrow();
    String resolvedTargetName = maybeTarget.get().getDisplayName().trim();
    String speakerName = findSpeakerName(request.getCharacterId(), roomEntities);
    String actorView =
        "You whisper to " + resolvedTargetName + ", \"" + normalizeText(request.getText()) + "\"";
    String targetView =
        speakerName + " whispers to you, \"" + normalizeText(request.getText()) + "\"";
    String observerView = speakerName + " whispers something to " + resolvedTargetName + ".";
    List<CommunicationRecipientView> recipientViews = new ArrayList<>();
    recipientViews.add(
        recipientView(
            request.getCharacterId(),
            speakerName,
            CommunicationRecipientRole.COMMUNICATION_RECIPIENT_ROLE_ACTOR,
            CommunicationPerception.COMMUNICATION_PERCEPTION_FULL_CONTENT,
            actorView));
    recipientViews.add(
        recipientView(
            targetEntity.getEntityId(),
            resolvedTargetName,
            CommunicationRecipientRole.COMMUNICATION_RECIPIENT_ROLE_TARGET,
            CommunicationPerception.COMMUNICATION_PERCEPTION_FULL_CONTENT,
            targetView));
    recipientViews.addAll(
        resolveMetadataOnlyWhisperObservers(
            request.getCharacterId(), targetEntity.getEntityId(), roomEntities, observerView));
    return new CommunicationAudience(
        List.of(speakerName, resolvedTargetName),
        List.of(),
        Optional.of(targetEntity.getEntityId()),
        actorView,
        recipientViews,
        null);
  }

  private CommunicationAudience buildTellAudience(SendCommunicationRequest request) {
    if (!StringUtils.hasText(request.getTargetCharacterId())
        || !StringUtils.hasText(request.getTargetCharacterName())) {
      return CommunicationAudience.invalid("Tell target is required");
    }

    String speakerName =
        StringUtils.hasText(request.getSpeakerName())
            ? request.getSpeakerName().trim()
            : request.getCharacterId();
    String targetName = request.getTargetCharacterName().trim();
    String actorView = "You tell " + targetName + ", \"" + normalizeText(request.getText()) + "\"";
    String targetView = speakerName + " tells you, \"" + normalizeText(request.getText()) + "\"";
    return new CommunicationAudience(
        List.of(speakerName, targetName),
        List.of(),
        Optional.of(request.getTargetCharacterId()),
        actorView,
        List.of(
            recipientView(
                request.getCharacterId(),
                speakerName,
                CommunicationRecipientRole.COMMUNICATION_RECIPIENT_ROLE_ACTOR,
                CommunicationPerception.COMMUNICATION_PERCEPTION_FULL_CONTENT,
                actorView),
            recipientView(
                request.getTargetCharacterId(),
                targetName,
                CommunicationRecipientRole.COMMUNICATION_RECIPIENT_ROLE_TARGET,
                CommunicationPerception.COMMUNICATION_PERCEPTION_FULL_CONTENT,
                targetView)),
        null);
  }

  private List<CommunicationRecipientView> resolveMetadataOnlyWhisperObservers(
      String speakerId,
      String targetId,
      ListRoomEntitiesResponse roomEntities,
      String renderedText) {
    return roomEntities.getEntitiesList().stream()
        .filter(entity -> !entity.getEntityId().equals(speakerId))
        .filter(entity -> !entity.getEntityId().equals(targetId))
        .filter(
            entity ->
                entity.getStateFlagsList().stream().anyMatch(OBSERVER_METADATA_ONLY_FLAG::equals))
        .map(
            entity ->
                recipientView(
                    entity.getEntityId(),
                    entity.getDisplayName(),
                    CommunicationRecipientRole.COMMUNICATION_RECIPIENT_ROLE_OBSERVER,
                    CommunicationPerception.COMMUNICATION_PERCEPTION_METADATA_ONLY,
                    renderedText))
        .toList();
  }

  private CommunicationRecipientView recipientView(
      String recipientId,
      String recipientName,
      CommunicationRecipientRole role,
      CommunicationPerception perception,
      String renderedText) {
    return CommunicationRecipientView.newBuilder()
        .setRecipientId(recipientId)
        .setRecipientName(recipientName)
        .setRole(role)
        .setPerception(perception)
        .setRenderedText(renderedText)
        .build();
  }

  private List<String> buildNpcEchoes(ListRoomEntitiesResponse roomEntities) {
    return roomEntities.getEntitiesList().stream()
        .filter(entity -> entity.getEntityType() == EntityType.NPC)
        .map(RoomEntity::getDisplayName)
        .filter(StringUtils::hasText)
        .map(String::trim)
        .collect(Collectors.toCollection(TreeSet::new))
        .stream()
        .toList();
  }

  private String findSpeakerName(String speakerId, ListRoomEntitiesResponse roomEntities) {
    return roomEntities.getEntitiesList().stream()
        .filter(entity -> entity.getEntityId().equals(speakerId))
        .map(RoomEntity::getDisplayName)
        .filter(StringUtils::hasText)
        .map(String::trim)
        .findFirst()
        .orElseGet(() -> speakerId == null || speakerId.isBlank() ? "Unknown" : speakerId);
  }

  private String resolveSenderId(SendCommunicationRequest request) {
    return StringUtils.hasText(request.getAccountId())
        ? request.getAccountId()
        : request.getCharacterId();
  }

  private ChatType mapType(CommunicationType type) {
    return switch (type) {
      case SAY -> ChatType.CHAT_TYPE_SAY;
      case WHISPER -> ChatType.CHAT_TYPE_WHISPER;
      case TELL -> ChatType.CHAT_TYPE_TELL;
      default -> ChatType.CHAT_TYPE_UNSPECIFIED;
    };
  }

  private String normalizeText(String text) {
    return text == null ? "" : text.trim();
  }

  private SendCommunicationResponse errorResponse(
      SendCommunicationResponse.Builder builder, String code, String message) {
    ErrorDetail detail = GrpcAppErrors.error(meterRegistry, code, message);
    return builder.setSuccess(false).setError(detail).build();
  }

  private SendCommunicationResponse errorResponse(
      SendCommunicationResponse.Builder builder, StatusRuntimeException ex, String source) {
    String description =
        Optional.ofNullable(ex.getStatus().getDescription())
            .filter(s -> !s.isBlank())
            .orElse("unreachable");
    return errorResponse(builder, ex.getStatus().getCode().name(), source + ": " + description);
  }

  private SendCommunicationResponse errorResponse(
      SendCommunicationResponse.Builder builder, ErrorDetail detail, String source) {
    String code = detail.getCode();
    if (code == null || code.isBlank()) {
      code = "UNAVAILABLE";
    }
    String message =
        Optional.ofNullable(detail.getMessage()).filter(s -> !s.isBlank()).orElse("unreachable");
    return errorResponse(builder, code, source + ": " + message);
  }

  private ErrorDetail genericError() {
    return ErrorDetail.newBuilder()
        .setCode("UNAVAILABLE")
        .setMessage("Social service reported an unknown failure")
        .build();
  }

  private RoomInstanceRef resolveRoomInstance(SendCommunicationRequest request) {
    if (request.getRoomInstance().getRoomInstanceId().isBlank()) {
      throw io.grpc.Status.INVALID_ARGUMENT
          .withDescription("room_instance.room_instance_id is required")
          .asRuntimeException();
    }
    return request.getRoomInstance();
  }

  private boolean requiresRoomAudience(CommunicationType type) {
    return type == CommunicationType.SAY || type == CommunicationType.WHISPER;
  }

  private record CommunicationAudience(
      List<String> deliveredTo,
      List<String> npcEchoes,
      Optional<String> recipientId,
      String actorView,
      List<CommunicationRecipientView> recipientViews,
      String errorMessage) {

    static CommunicationAudience invalid(String errorMessage) {
      return new CommunicationAudience(
          List.of(), List.of(), Optional.empty(), null, List.of(), errorMessage);
    }

    boolean valid() {
      return errorMessage == null;
    }
  }
}
