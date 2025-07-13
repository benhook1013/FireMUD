<!-- markdownlint-disable -->
# Protocol Documentation

<a name="top"></a>

## Table of Contents

- [account/v1/account_service.proto](#account_v1_account_service-proto)
  - [AuthenticateRequest](#account-v1-AuthenticateRequest)
  - [AuthenticateResponse](#account-v1-AuthenticateResponse)
  - [CreateAccountRequest](#account-v1-CreateAccountRequest)
  - [CreateAccountResponse](#account-v1-CreateAccountResponse)
  - [GetProfileRequest](#account-v1-GetProfileRequest)
  - [GetProfileResponse](#account-v1-GetProfileResponse)
  - [PingRequest](#account-v1-PingRequest)
  - [PingResponse](#account-v1-PingResponse)
  - [UpdateProfileRequest](#account-v1-UpdateProfileRequest)
  - [UpdateProfileResponse](#account-v1-UpdateProfileResponse)
  
  - [AccountService](#account-v1-AccountService)
  
- [account/v1/payment_service.proto](#account_v1_payment_service-proto)
  - [CreatePaymentIntentRequest](#account-v1-CreatePaymentIntentRequest)
  - [CreatePaymentIntentResponse](#account-v1-CreatePaymentIntentResponse)
  - [CreateSubscriptionRequest](#account-v1-CreateSubscriptionRequest)
  - [CreateSubscriptionResponse](#account-v1-CreateSubscriptionResponse)
  
  - [PaymentService](#account-v1-PaymentService)
  
- [automation-scripting/v1/automation_scripting_service.proto](#automation-scripting_v1_automation_scripting_service-proto)
  - [PingRequest](#automation_scripting-v1-PingRequest)
  - [PingResponse](#automation_scripting-v1-PingResponse)
  
  - [AutomationScriptingService](#automation_scripting-v1-AutomationScriptingService)
  
- [entity-management/v1/entity_management_service.proto](#entity-management_v1_entity_management_service-proto)
  - [CreateCharacterRequest](#entity_management-v1-CreateCharacterRequest)
  - [CreateCharacterResponse](#entity_management-v1-CreateCharacterResponse)
  - [PingRequest](#entity_management-v1-PingRequest)
  - [PingResponse](#entity_management-v1-PingResponse)
  - [QueryInventoryRequest](#entity_management-v1-QueryInventoryRequest)
  - [QueryInventoryResponse](#entity_management-v1-QueryInventoryResponse)
  - [UpdateEntityRequest](#entity_management-v1-UpdateEntityRequest)
  - [UpdateEntityResponse](#entity_management-v1-UpdateEntityResponse)
  
  - [EntityManagementService](#entity_management-v1-EntityManagementService)
  
- [game-design/v1/game_design_service.proto](#game-design_v1_game_design_service-proto)
  - [ListVersionsRequest](#gamedesign-v1-ListVersionsRequest)
  - [ListVersionsResponse](#gamedesign-v1-ListVersionsResponse)
  - [PingRequest](#gamedesign-v1-PingRequest)
  - [PingResponse](#gamedesign-v1-PingResponse)
  - [PublishVersionRequest](#gamedesign-v1-PublishVersionRequest)
  - [PublishVersionResponse](#gamedesign-v1-PublishVersionResponse)
  - [SaveRevisionRequest](#gamedesign-v1-SaveRevisionRequest)
  - [SaveRevisionResponse](#gamedesign-v1-SaveRevisionResponse)
  - [Version](#gamedesign-v1-Version)
  
  - [GameDesignService](#gamedesign-v1-GameDesignService)
  
- [game-logic/v1/game_logic_service.proto](#game-logic_v1_game_logic_service-proto)
  - [PingRequest](#game_logic-v1-PingRequest)
  - [PingResponse](#game_logic-v1-PingResponse)
  
  - [GameLogicService](#game_logic-v1-GameLogicService)
  
- [game-session/v1/game_session_service.proto](#game-session_v1_game_session_service-proto)
  - [EnqueueCommandRequest](#game_session-v1-EnqueueCommandRequest)
  - [EnqueueCommandResponse](#game_session-v1-EnqueueCommandResponse)
  - [PingRequest](#game_session-v1-PingRequest)
  - [PingResponse](#game_session-v1-PingResponse)
  - [QueryStateRequest](#game_session-v1-QueryStateRequest)
  - [QueryStateResponse](#game_session-v1-QueryStateResponse)
  - [StartSessionRequest](#game_session-v1-StartSessionRequest)
  - [StartSessionResponse](#game_session-v1-StartSessionResponse)
  
  - [GameSessionService](#game_session-v1-GameSessionService)
  
- [logging-admin/v1/logging_admin_service.proto](#logging-admin_v1_logging_admin_service-proto)
  - [ApplyModerationActionRequest](#logging_admin-v1-ApplyModerationActionRequest)
  - [ApplyModerationActionResponse](#logging_admin-v1-ApplyModerationActionResponse)
  - [PingRequest](#logging_admin-v1-PingRequest)
  - [PingResponse](#logging_admin-v1-PingResponse)
  - [QueryLogsRequest](#logging_admin-v1-QueryLogsRequest)
  - [QueryLogsResponse](#logging_admin-v1-QueryLogsResponse)
  
  - [LoggingAdminService](#logging_admin-v1-LoggingAdminService)
  
- [logging-admin/v1/report_service.proto](#logging-admin_v1_report_service-proto)
  - [CreateReportRequest](#logging_admin-v1-CreateReportRequest)
  - [CreateReportResponse](#logging_admin-v1-CreateReportResponse)
  
  - [ReportService](#logging_admin-v1-ReportService)
  
- [shared/v1/errors.proto](#shared_v1_errors-proto)
  - [ErrorDetail](#shared-v1-ErrorDetail)
  
- [social-groups/v1/social_groups_service.proto](#social-groups_v1_social_groups_service-proto)
  - [AddFriendRequest](#social_groups-v1-AddFriendRequest)
  - [AddFriendResponse](#social_groups-v1-AddFriendResponse)
  - [CreateGuildRequest](#social_groups-v1-CreateGuildRequest)
  - [CreateGuildResponse](#social_groups-v1-CreateGuildResponse)
  - [PingRequest](#social_groups-v1-PingRequest)
  - [PingResponse](#social_groups-v1-PingResponse)
  - [SendMessageRequest](#social_groups-v1-SendMessageRequest)
  - [SendMessageResponse](#social_groups-v1-SendMessageResponse)
  
  - [SocialGroupsService](#social_groups-v1-SocialGroupsService)
  
- [spring-cloud-gateway/v1/gateway_management_service.proto](#spring-cloud-gateway_v1_gateway_management_service-proto)
  - [PingRequest](#gateway-v1-PingRequest)
  - [PingResponse](#gateway-v1-PingResponse)
  - [RouteDefinition](#gateway-v1-RouteDefinition)
  - [RouteRequest](#gateway-v1-RouteRequest)
  - [RouteResponse](#gateway-v1-RouteResponse)
  
  - [GatewayManagementService](#gateway-v1-GatewayManagementService)
  
- [tcp-proxy/v1/tcp_proxy_service.proto](#tcp-proxy_v1_tcp_proxy_service-proto)
  - [PingRequest](#tcp_proxy-v1-PingRequest)
  - [PingResponse](#tcp_proxy-v1-PingResponse)
  
  - [TcpProxyService](#tcp_proxy-v1-TcpProxyService)
  
- [world-management/v1/world_management_service.proto](#world-management_v1_world_management_service-proto)
  - [GetRoomRequest](#world_management-v1-GetRoomRequest)
  - [GetRoomResponse](#world_management-v1-GetRoomResponse)
  - [PingRequest](#world_management-v1-PingRequest)
  - [PingResponse](#world_management-v1-PingResponse)
  - [UpdateWorldStateRequest](#world_management-v1-UpdateWorldStateRequest)
  - [UpdateWorldStateResponse](#world_management-v1-UpdateWorldStateResponse)
  
  - [WorldManagementService](#world_management-v1-WorldManagementService)
  
- [Scalar Value Types](#scalar-value-types)

<a name="account_v1_account_service-proto"></a>
<p align="right"><a href="#top">Top</a></p>

## account/v1/account_service.proto

<a name="account-v1-AuthenticateRequest"></a>

### AuthenticateRequest

| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| tenant_id | [string](#string) |  |  |
| username | [string](#string) |  |  |
| password | [string](#string) |  |  |

<a name="account-v1-AuthenticateResponse"></a>

### AuthenticateResponse

| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| auth_token | [string](#string) |  |  |
| error | [shared.v1.ErrorDetail](#shared-v1-ErrorDetail) |  |  |

<a name="account-v1-CreateAccountRequest"></a>

### CreateAccountRequest

| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| tenant_id | [string](#string) |  |  |
| username | [string](#string) |  |  |
| email | [string](#string) |  |  |
| password | [string](#string) |  |  |

<a name="account-v1-CreateAccountResponse"></a>

### CreateAccountResponse

| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| account_id | [string](#string) |  |  |
| error | [shared.v1.ErrorDetail](#shared-v1-ErrorDetail) |  |  |

<a name="account-v1-GetProfileRequest"></a>

### GetProfileRequest

| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| tenant_id | [string](#string) |  |  |
| account_id | [string](#string) |  |  |

<a name="account-v1-GetProfileResponse"></a>

### GetProfileResponse

| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| profile_json | [string](#string) |  |  |
| error | [shared.v1.ErrorDetail](#shared-v1-ErrorDetail) |  |  |

<a name="account-v1-PingRequest"></a>

### PingRequest

<a name="account-v1-PingResponse"></a>

### PingResponse

| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| message | [string](#string) |  |  |
| error | [shared.v1.ErrorDetail](#shared-v1-ErrorDetail) |  |  |

<a name="account-v1-UpdateProfileRequest"></a>

### UpdateProfileRequest

| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| tenant_id | [string](#string) |  |  |
| account_id | [string](#string) |  |  |
| profile_json | [string](#string) |  |  |

<a name="account-v1-UpdateProfileResponse"></a>

### UpdateProfileResponse

| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| success | [bool](#bool) |  |  |
| error | [shared.v1.ErrorDetail](#shared-v1-ErrorDetail) |  |  |

<a name="account-v1-AccountService"></a>

### AccountService

| Method Name | Request Type | Response Type | Description |
| ----------- | ------------ | ------------- | ------------|
| Ping | [PingRequest](#account-v1-PingRequest) | [PingResponse](#account-v1-PingResponse) |  |
| CreateAccount | [CreateAccountRequest](#account-v1-CreateAccountRequest) | [CreateAccountResponse](#account-v1-CreateAccountResponse) |  |
| Authenticate | [AuthenticateRequest](#account-v1-AuthenticateRequest) | [AuthenticateResponse](#account-v1-AuthenticateResponse) |  |
| GetProfile | [GetProfileRequest](#account-v1-GetProfileRequest) | [GetProfileResponse](#account-v1-GetProfileResponse) |  |
| UpdateProfile | [UpdateProfileRequest](#account-v1-UpdateProfileRequest) | [UpdateProfileResponse](#account-v1-UpdateProfileResponse) |  |

<a name="account_v1_payment_service-proto"></a>
<p align="right"><a href="#top">Top</a></p>

## account/v1/payment_service.proto

<a name="account-v1-CreatePaymentIntentRequest"></a>

### CreatePaymentIntentRequest

| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| tenant_id | [string](#string) |  |  |
| account_id | [string](#string) |  |  |
| amount_cents | [int64](#int64) |  |  |

<a name="account-v1-CreatePaymentIntentResponse"></a>

### CreatePaymentIntentResponse

| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| intent_id | [string](#string) |  |  |
| client_secret | [string](#string) |  |  |
| error | [shared.v1.ErrorDetail](#shared-v1-ErrorDetail) |  |  |

<a name="account-v1-CreateSubscriptionRequest"></a>

### CreateSubscriptionRequest

| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| tenant_id | [string](#string) |  |  |
| account_id | [string](#string) |  |  |
| plan_id | [string](#string) |  |  |

<a name="account-v1-CreateSubscriptionResponse"></a>

### CreateSubscriptionResponse

| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| subscription_id | [string](#string) |  |  |
| error | [shared.v1.ErrorDetail](#shared-v1-ErrorDetail) |  |  |

<a name="account-v1-PaymentService"></a>

### PaymentService

| Method Name | Request Type | Response Type | Description |
| ----------- | ------------ | ------------- | ------------|
| CreatePaymentIntent | [CreatePaymentIntentRequest](#account-v1-CreatePaymentIntentRequest) | [CreatePaymentIntentResponse](#account-v1-CreatePaymentIntentResponse) |  |
| CreateSubscription | [CreateSubscriptionRequest](#account-v1-CreateSubscriptionRequest) | [CreateSubscriptionResponse](#account-v1-CreateSubscriptionResponse) |  |

<a name="automation-scripting_v1_automation_scripting_service-proto"></a>
<p align="right"><a href="#top">Top</a></p>

## automation-scripting/v1/automation_scripting_service.proto

<a name="automation_scripting-v1-PingRequest"></a>

### PingRequest

<a name="automation_scripting-v1-PingResponse"></a>

### PingResponse

| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| message | [string](#string) |  |  |

<a name="automation_scripting-v1-AutomationScriptingService"></a>

### AutomationScriptingService

| Method Name | Request Type | Response Type | Description |
| ----------- | ------------ | ------------- | ------------|
| Ping | [PingRequest](#automation_scripting-v1-PingRequest) | [PingResponse](#automation_scripting-v1-PingResponse) |  |

<a name="entity-management_v1_entity_management_service-proto"></a>
<p align="right"><a href="#top">Top</a></p>

## entity-management/v1/entity_management_service.proto

<a name="entity_management-v1-CreateCharacterRequest"></a>

### CreateCharacterRequest

| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| tenant_id | [string](#string) |  |  |
| account_id | [string](#string) |  |  |
| name | [string](#string) |  |  |

<a name="entity_management-v1-CreateCharacterResponse"></a>

### CreateCharacterResponse

| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| character_id | [string](#string) |  |  |

<a name="entity_management-v1-PingRequest"></a>

### PingRequest

<a name="entity_management-v1-PingResponse"></a>

### PingResponse

| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| message | [string](#string) |  |  |

<a name="entity_management-v1-QueryInventoryRequest"></a>

### QueryInventoryRequest

| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| entity_id | [string](#string) |  |  |

<a name="entity_management-v1-QueryInventoryResponse"></a>

### QueryInventoryResponse

| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| item_ids | [string](#string) | repeated |  |

<a name="entity_management-v1-UpdateEntityRequest"></a>

### UpdateEntityRequest

| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| entity_id | [string](#string) |  |  |

<a name="entity_management-v1-UpdateEntityResponse"></a>

### UpdateEntityResponse

| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| success | [bool](#bool) |  |  |

<a name="entity_management-v1-EntityManagementService"></a>

### EntityManagementService

| Method Name | Request Type | Response Type | Description |
| ----------- | ------------ | ------------- | ------------|
| Ping | [PingRequest](#entity_management-v1-PingRequest) | [PingResponse](#entity_management-v1-PingResponse) |  |
| CreateCharacter | [CreateCharacterRequest](#entity_management-v1-CreateCharacterRequest) | [CreateCharacterResponse](#entity_management-v1-CreateCharacterResponse) |  |
| UpdateEntity | [UpdateEntityRequest](#entity_management-v1-UpdateEntityRequest) | [UpdateEntityResponse](#entity_management-v1-UpdateEntityResponse) |  |
| QueryInventory | [QueryInventoryRequest](#entity_management-v1-QueryInventoryRequest) | [QueryInventoryResponse](#entity_management-v1-QueryInventoryResponse) |  |

<a name="game-design_v1_game_design_service-proto"></a>
<p align="right"><a href="#top">Top</a></p>

## game-design/v1/game_design_service.proto

<a name="gamedesign-v1-ListVersionsRequest"></a>

### ListVersionsRequest

| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| tenant_id | [int64](#int64) |  |  |

<a name="gamedesign-v1-ListVersionsResponse"></a>

### ListVersionsResponse

| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| versions | [Version](#gamedesign-v1-Version) | repeated |  |

<a name="gamedesign-v1-PingRequest"></a>

### PingRequest

<a name="gamedesign-v1-PingResponse"></a>

### PingResponse

| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| message | [string](#string) |  |  |

<a name="gamedesign-v1-PublishVersionRequest"></a>

### PublishVersionRequest

| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| tenant_id | [int64](#int64) |  |  |
| notes | [string](#string) |  |  |

<a name="gamedesign-v1-PublishVersionResponse"></a>

### PublishVersionResponse

| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| version_id | [int64](#int64) |  |  |

<a name="gamedesign-v1-SaveRevisionRequest"></a>

### SaveRevisionRequest

| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| data | [string](#string) |  |  |
| tenant_id | [int64](#int64) |  |  |
| author_account_id | [int64](#int64) |  |  |

<a name="gamedesign-v1-SaveRevisionResponse"></a>

### SaveRevisionResponse

| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| revision_id | [int64](#int64) |  |  |

<a name="gamedesign-v1-Version"></a>

### Version

| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| id | [int64](#int64) |  |  |
| version_number | [int32](#int32) |  |  |
| notes | [string](#string) |  |  |

<a name="gamedesign-v1-GameDesignService"></a>

### GameDesignService

| Method Name | Request Type | Response Type | Description |
| ----------- | ------------ | ------------- | ------------|
| Ping | [PingRequest](#gamedesign-v1-PingRequest) | [PingResponse](#gamedesign-v1-PingResponse) |  |
| SaveRevision | [SaveRevisionRequest](#gamedesign-v1-SaveRevisionRequest) | [SaveRevisionResponse](#gamedesign-v1-SaveRevisionResponse) |  |
| PublishVersion | [PublishVersionRequest](#gamedesign-v1-PublishVersionRequest) | [PublishVersionResponse](#gamedesign-v1-PublishVersionResponse) |  |
| ListVersions | [ListVersionsRequest](#gamedesign-v1-ListVersionsRequest) | [ListVersionsResponse](#gamedesign-v1-ListVersionsResponse) |  |

<a name="game-logic_v1_game_logic_service-proto"></a>
<p align="right"><a href="#top">Top</a></p>

## game-logic/v1/game_logic_service.proto

<a name="game_logic-v1-PingRequest"></a>

### PingRequest

<a name="game_logic-v1-PingResponse"></a>

### PingResponse

| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| message | [string](#string) |  |  |

<a name="game_logic-v1-GameLogicService"></a>

### GameLogicService

| Method Name | Request Type | Response Type | Description |
| ----------- | ------------ | ------------- | ------------|
| Ping | [PingRequest](#game_logic-v1-PingRequest) | [PingResponse](#game_logic-v1-PingResponse) |  |

<a name="game-session_v1_game_session_service-proto"></a>
<p align="right"><a href="#top">Top</a></p>

## game-session/v1/game_session_service.proto

<a name="game_session-v1-EnqueueCommandRequest"></a>

### EnqueueCommandRequest

| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| session_id | [string](#string) |  |  |
| command | [string](#string) |  |  |

<a name="game_session-v1-EnqueueCommandResponse"></a>

### EnqueueCommandResponse

| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| accepted | [bool](#bool) |  |  |

<a name="game_session-v1-PingRequest"></a>

### PingRequest

<a name="game_session-v1-PingResponse"></a>

### PingResponse

| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| message | [string](#string) |  |  |

<a name="game_session-v1-QueryStateRequest"></a>

### QueryStateRequest

| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| session_id | [string](#string) |  |  |

<a name="game_session-v1-QueryStateResponse"></a>

### QueryStateResponse

| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| state_json | [string](#string) |  |  |

<a name="game_session-v1-StartSessionRequest"></a>

### StartSessionRequest

| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| tenant_id | [string](#string) |  |  |
| version_id | [string](#string) |  |  |

<a name="game_session-v1-StartSessionResponse"></a>

### StartSessionResponse

| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| session_id | [string](#string) |  |  |

<a name="game_session-v1-GameSessionService"></a>

### GameSessionService

| Method Name | Request Type | Response Type | Description |
| ----------- | ------------ | ------------- | ------------|
| Ping | [PingRequest](#game_session-v1-PingRequest) | [PingResponse](#game_session-v1-PingResponse) |  |
| StartSession | [StartSessionRequest](#game_session-v1-StartSessionRequest) | [StartSessionResponse](#game_session-v1-StartSessionResponse) |  |
| EnqueueCommand | [EnqueueCommandRequest](#game_session-v1-EnqueueCommandRequest) | [EnqueueCommandResponse](#game_session-v1-EnqueueCommandResponse) |  |
| QueryState | [QueryStateRequest](#game_session-v1-QueryStateRequest) | [QueryStateResponse](#game_session-v1-QueryStateResponse) |  |

<a name="logging-admin_v1_logging_admin_service-proto"></a>
<p align="right"><a href="#top">Top</a></p>

## logging-admin/v1/logging_admin_service.proto

<a name="logging_admin-v1-ApplyModerationActionRequest"></a>

### ApplyModerationActionRequest

| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| tenant_id | [string](#string) |  |  |
| account_id | [string](#string) |  |  |
| action | [string](#string) |  |  |
| reason | [string](#string) |  |  |

<a name="logging_admin-v1-ApplyModerationActionResponse"></a>

### ApplyModerationActionResponse

| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| success | [bool](#bool) |  |  |

<a name="logging_admin-v1-PingRequest"></a>

### PingRequest

<a name="logging_admin-v1-PingResponse"></a>

### PingResponse

| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| message | [string](#string) |  |  |

<a name="logging_admin-v1-QueryLogsRequest"></a>

### QueryLogsRequest

| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| tenant_id | [string](#string) |  |  |
| filter | [string](#string) |  |  |

<a name="logging_admin-v1-QueryLogsResponse"></a>

### QueryLogsResponse

| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| entries | [string](#string) | repeated |  |

<a name="logging_admin-v1-LoggingAdminService"></a>

### LoggingAdminService

| Method Name | Request Type | Response Type | Description |
| ----------- | ------------ | ------------- | ------------|
| Ping | [PingRequest](#logging_admin-v1-PingRequest) | [PingResponse](#logging_admin-v1-PingResponse) |  |
| QueryLogs | [QueryLogsRequest](#logging_admin-v1-QueryLogsRequest) | [QueryLogsResponse](#logging_admin-v1-QueryLogsResponse) |  |
| ApplyModerationAction | [ApplyModerationActionRequest](#logging_admin-v1-ApplyModerationActionRequest) | [ApplyModerationActionResponse](#logging_admin-v1-ApplyModerationActionResponse) |  |

<a name="logging-admin_v1_report_service-proto"></a>
<p align="right"><a href="#top">Top</a></p>

## logging-admin/v1/report_service.proto

<a name="logging_admin-v1-CreateReportRequest"></a>

### CreateReportRequest

| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| tenant_id | [string](#string) |  |  |
| reporter_account_id | [string](#string) |  |  |
| target_account_id | [string](#string) |  |  |
| type | [string](#string) |  |  |
| description | [string](#string) |  |  |

<a name="logging_admin-v1-CreateReportResponse"></a>

### CreateReportResponse

| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| report_id | [string](#string) |  |  |

<a name="logging_admin-v1-ReportService"></a>

### ReportService

| Method Name | Request Type | Response Type | Description |
| ----------- | ------------ | ------------- | ------------|
| CreateReport | [CreateReportRequest](#logging_admin-v1-CreateReportRequest) | [CreateReportResponse](#logging_admin-v1-CreateReportResponse) |  |

<a name="shared_v1_errors-proto"></a>
<p align="right"><a href="#top">Top</a></p>

## shared/v1/errors.proto

<a name="shared-v1-ErrorDetail"></a>

### ErrorDetail

| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| code | [string](#string) |  |  |
| message | [string](#string) |  |  |

<a name="social-groups_v1_social_groups_service-proto"></a>
<p align="right"><a href="#top">Top</a></p>

## social-groups/v1/social_groups_service.proto

<a name="social_groups-v1-AddFriendRequest"></a>

### AddFriendRequest

| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| tenant_id | [string](#string) |  |  |
| account_id | [string](#string) |  |  |
| friend_account_id | [string](#string) |  |  |

<a name="social_groups-v1-AddFriendResponse"></a>

### AddFriendResponse

| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| success | [bool](#bool) |  |  |

<a name="social_groups-v1-CreateGuildRequest"></a>

### CreateGuildRequest

| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| tenant_id | [string](#string) |  |  |
| owner_account_id | [string](#string) |  |  |
| name | [string](#string) |  |  |

<a name="social_groups-v1-CreateGuildResponse"></a>

### CreateGuildResponse

| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| guild_id | [string](#string) |  |  |

<a name="social_groups-v1-PingRequest"></a>

### PingRequest

<a name="social_groups-v1-PingResponse"></a>

### PingResponse

| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| message | [string](#string) |  |  |

<a name="social_groups-v1-SendMessageRequest"></a>

### SendMessageRequest

| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| tenant_id | [string](#string) |  |  |
| sender_id | [string](#string) |  |  |
| channel_id | [string](#string) |  |  |
| content | [string](#string) |  |  |
| type | [ChatType](#social_groups-v1-ChatType) |  |  |
| recipient_id | [string](#string) |  |  |
| guild_id | [string](#string) |  |  |
| city_id | [string](#string) |  |  |

<a name="social_groups-v1-SendMessageResponse"></a>

### SendMessageResponse

| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| success | [bool](#bool) |  |  |

<a name="social_groups-v1-ChatType"></a>

### ChatType

| Name | Number |
| ---- | ------ |
| CHAT_TYPE_UNSPECIFIED | 0 |
| CHAT_TYPE_SAY | 1 |
| CHAT_TYPE_TELL | 2 |
| CHAT_TYPE_GUILD | 3 |
| CHAT_TYPE_CITY | 4 |
| CHAT_TYPE_ACCOUNT | 5 |

<a name="social_groups-v1-SocialGroupsService"></a>

### SocialGroupsService

| Method Name | Request Type | Response Type | Description |
| ----------- | ------------ | ------------- | ------------|
| Ping | [PingRequest](#social_groups-v1-PingRequest) | [PingResponse](#social_groups-v1-PingResponse) |  |
| SendMessage | [SendMessageRequest](#social_groups-v1-SendMessageRequest) | [SendMessageResponse](#social_groups-v1-SendMessageResponse) |  |
| CreateGuild | [CreateGuildRequest](#social_groups-v1-CreateGuildRequest) | [CreateGuildResponse](#social_groups-v1-CreateGuildResponse) |  |
| AddFriend | [AddFriendRequest](#social_groups-v1-AddFriendRequest) | [AddFriendResponse](#social_groups-v1-AddFriendResponse) |  |

<a name="spring-cloud-gateway_v1_gateway_management_service-proto"></a>
<p align="right"><a href="#top">Top</a></p>

## spring-cloud-gateway/v1/gateway_management_service.proto

<a name="gateway-v1-PingRequest"></a>

### PingRequest

<a name="gateway-v1-PingResponse"></a>

### PingResponse

| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| message | [string](#string) |  |  |

<a name="gateway-v1-RouteDefinition"></a>

### RouteDefinition

Route configuration details for dynamic updates.

| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| route_id | [string](#string) |  | Unique identifier for the route |
| uri | [string](#string) |  | Target service URI |
| predicates | [string](#string) | repeated | Spring Cloud Gateway predicates |
| filters | [string](#string) | repeated | Additional filters to apply |

<a name="gateway-v1-RouteRequest"></a>

### RouteRequest

Request to operate on a specific route.

| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| route_id | [string](#string) |  | Identifier of the route to remove |

<a name="gateway-v1-RouteResponse"></a>

### RouteResponse

Standard response wrapper.

| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| success | [bool](#bool) |  |  |
| error | [shared.v1.ErrorDetail](#shared-v1-ErrorDetail) |  |  |

<a name="gateway-v1-GatewayManagementService"></a>

### GatewayManagementService

GatewayManagementService allows remote configuration of Spring Cloud Gateway routes.

| Method Name | Request Type | Response Type | Description |
| ----------- | ------------ | ------------- | ------------|
| Ping | [PingRequest](#gateway-v1-PingRequest) | [PingResponse](#gateway-v1-PingResponse) |  |
| UpsertRoute | [RouteDefinition](#gateway-v1-RouteDefinition) | [RouteResponse](#gateway-v1-RouteResponse) | Adds or updates a custom route for the gateway. |
| RemoveRoute | [RouteRequest](#gateway-v1-RouteRequest) | [RouteResponse](#gateway-v1-RouteResponse) | Removes a route by ID. |

<a name="tcp-proxy_v1_tcp_proxy_service-proto"></a>
<p align="right"><a href="#top">Top</a></p>

## tcp-proxy/v1/tcp_proxy_service.proto

<a name="tcp_proxy-v1-PingRequest"></a>

### PingRequest

<a name="tcp_proxy-v1-PingResponse"></a>

### PingResponse

| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| message | [string](#string) |  |  |

<a name="tcp_proxy-v1-TcpProxyService"></a>

### TcpProxyService

| Method Name | Request Type | Response Type | Description |
| ----------- | ------------ | ------------- | ------------|
| Ping | [PingRequest](#tcp_proxy-v1-PingRequest) | [PingResponse](#tcp_proxy-v1-PingResponse) |  |

<a name="world-management_v1_world_management_service-proto"></a>
<p align="right"><a href="#top">Top</a></p>

## world-management/v1/world_management_service.proto

<a name="world_management-v1-GetRoomRequest"></a>

### GetRoomRequest

| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| tenant_id | [string](#string) |  |  |
| room_id | [string](#string) |  |  |

<a name="world_management-v1-GetRoomResponse"></a>

### GetRoomResponse

| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| room_json | [string](#string) |  |  |

<a name="world_management-v1-PingRequest"></a>

### PingRequest

<a name="world_management-v1-PingResponse"></a>

### PingResponse

| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| message | [string](#string) |  |  |

<a name="world_management-v1-UpdateWorldStateRequest"></a>

### UpdateWorldStateRequest

| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| tenant_id | [string](#string) |  |  |

<a name="world_management-v1-UpdateWorldStateResponse"></a>

### UpdateWorldStateResponse

| Field | Type | Label | Description |
| ----- | ---- | ----- | ----------- |
| success | [bool](#bool) |  |  |

<a name="world_management-v1-WorldManagementService"></a>

### WorldManagementService

| Method Name | Request Type | Response Type | Description |
| ----------- | ------------ | ------------- | ------------|
| Ping | [PingRequest](#world_management-v1-PingRequest) | [PingResponse](#world_management-v1-PingResponse) |  |
| GetRoom | [GetRoomRequest](#world_management-v1-GetRoomRequest) | [GetRoomResponse](#world_management-v1-GetRoomResponse) |  |
| UpdateWorldState | [UpdateWorldStateRequest](#world_management-v1-UpdateWorldStateRequest) | [UpdateWorldStateResponse](#world_management-v1-UpdateWorldStateResponse) |  |

## Scalar Value Types

| .proto Type | Notes | C++ | Java | Python | Go | C# | PHP | Ruby |
| ----------- | ----- | --- | ---- | ------ | -- | -- | --- | ---- |
| <a name="double" /> double |  | double | double | float | float64 | double | float | Float |
| <a name="float" /> float |  | float | float | float | float32 | float | float | Float |
| <a name="int32" /> int32 | Uses variable-length encoding. Inefficient for encoding negative numbers – if your field is likely to have negative values, use sint32 instead. | int32 | int | int | int32 | int | integer | Bignum or Fixnum (as required) |
| <a name="int64" /> int64 | Uses variable-length encoding. Inefficient for encoding negative numbers – if your field is likely to have negative values, use sint64 instead. | int64 | long | int/long | int64 | long | integer/string | Bignum |
| <a name="uint32" /> uint32 | Uses variable-length encoding. | uint32 | int | int/long | uint32 | uint | integer | Bignum or Fixnum (as required) |
| <a name="uint64" /> uint64 | Uses variable-length encoding. | uint64 | long | int/long | uint64 | ulong | integer/string | Bignum or Fixnum (as required) |
| <a name="sint32" /> sint32 | Uses variable-length encoding. Signed int value. These more efficiently encode negative numbers than regular int32s. | int32 | int | int | int32 | int | integer | Bignum or Fixnum (as required) |
| <a name="sint64" /> sint64 | Uses variable-length encoding. Signed int value. These more efficiently encode negative numbers than regular int64s. | int64 | long | int/long | int64 | long | integer/string | Bignum |
| <a name="fixed32" /> fixed32 | Always four bytes. More efficient than uint32 if values are often greater than 2^28. | uint32 | int | int | uint32 | uint | integer | Bignum or Fixnum (as required) |
| <a name="fixed64" /> fixed64 | Always eight bytes. More efficient than uint64 if values are often greater than 2^56. | uint64 | long | int/long | uint64 | ulong | integer/string | Bignum |
| <a name="sfixed32" /> sfixed32 | Always four bytes. | int32 | int | int | int32 | int | integer | Bignum or Fixnum (as required) |
| <a name="sfixed64" /> sfixed64 | Always eight bytes. | int64 | long | int/long | int64 | long | integer/string | Bignum |
| <a name="bool" /> bool |  | bool | boolean | boolean | bool | bool | boolean | TrueClass/FalseClass |
| <a name="string" /> string | A string must always contain UTF-8 encoded or 7-bit ASCII text. | string | String | str/unicode | string | string | string | String (UTF-8) |
| <a name="bytes" /> bytes | May contain any arbitrary sequence of bytes. | string | ByteString | str | []byte | ByteString | string | String (ASCII-8BIT) |
