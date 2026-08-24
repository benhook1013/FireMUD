#!/usr/bin/env python3
"""Validate the machine-readable authorization route matrix contract."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any

import yaml

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_MATRIX = (
    ROOT / "design/architecture/system-architecture-authz-route-matrix.yaml"
)
REQUIRED_WS_GAME_CHECKS = {
    "connect_token_single_use_consume",
    "replay_protection_available",
    "replay_admission_fence_match",
    "connect_scope_match",
}
REQUIRED_ISSUE_CONNECT_TOKEN_CHECKS = {
    "target_tenant_generation",
    "membership_generation",
    "membership_version",
    "replay_protection_available",
    "replay_admission_fence_match",
}
REQUIRED_PLAY_GENERATION_CHECKS = {
    "target_tenant_generation",
    "membership_generation",
    "membership_version",
}
JOIN_ROUTES_REQUIRING_POINTER_ERROR = {
    ("game-session-service", "JOIN"),
    ("account-service", "POST /auth/bootstrap/join"),
    ("account-service", "JoinPublicProductionMembership"),
}
REQUIRED_JOIN_PRE_MEMBERSHIP_CHECKS = {
    "public_production_visibility",
    "public_production_admission",
    "runtime_entitlements",
    "admission_pointer",
    "idempotency",
}
REQUIRED_DELEGATED_ENTITLEMENT_CHECKS = {
    "conditional_realm_access_grant",
    "grant_version",
    "issuer_generation",
    "account_generation",
}
REQUIRED_TRUSTED_PROXY_CHECKS = {"trusted_proxy_identity"}
REQUIRED_CONNECT_TOKEN_REVOKE_CHECKS = {"browser_origin", "csrf"}
REQUIRED_GAMEPLAY_ADMISSION_MODES = {
    "public_production_onboarding",
    "returning_membership",
    "grant_backed_private_or_playtest",
}
REQUIRED_GAMEPLAY_ADMISSION_SELECTOR_INPUTS = {
    "current_membership",
    "public_production_target",
    "realm_access_grant",
}
REQUIRED_GAMEPLAY_ADMISSION_COMMON_CHECKS = {
    "runtime_entitlements",
    "admission_pointer",
    "membership_generation",
}
REQUIRED_GAMEPLAY_ADMISSION_BRANCH_CHECKS = {
    "public_production_onboarding": {
        "membership",
        "public_production_admission",
        "realm_visibility",
    },
    "returning_membership": {
        "membership",
        "realm_visibility",
    },
    "grant_backed_private_or_playtest": {
        "membership",
        "conditional_realm_access_grant",
    },
}
REQUIRED_FIRST_PARTY_WS_APPLICABILITY = {
    "connection_mode": "first_party_web",
    "operation": "websocket_upgrade",
}
REQUIRED_REVOKE_APPLICABILITY = {
    "connection_mode": "first_party_web",
    "operation": "connect_token_cookie_revoke",
}
REQUIRED_REVOKE_GENERATION_APPLICABILITY = {
    "issuer_authority_generation_applies": False,
    "account_authority_generation_applies": False,
    "tenant_billing_authority_generation_applies": False,
    "membership_authority_generation_applies": False,
}
MEMBERSHIP_WRITER_ROUTE = (
    "account-service",
    "JoinPublicProductionMembership",
)
REQUIRED_MEMBERSHIP_WRITER_CHECKS = {
    "account_state_bootstrap_eligible",
    "pending_deletion_state",
}
GAMEPLAY_CONNECT_ISSUED_TOKEN_STATE = "none_bounded_single_use_replay_exception"
EXPLICIT_NO_JWT_ROUTES = {
    ("game-session-service", "LOGIN"),
    ("game-session-service", "LOGON"),
    ("game-session-service", "WORLDS_PUBLIC"),
    ("account-service", "AuthLogin"),
    ("account-service", "PlayerBootstrapLogin"),
    ("account-service", "POST /auth/pending-deletion/recovery/challenge"),
    ("account-service", "POST /auth/pending-deletion/recovery"),
}
REQUIRED_FIELD_PATTERN = re.compile(r"^[a-z][a-z0-9]*(?:_[a-z0-9]+)*$")
ROUTE_STATUS_VALUES = {
    "current_openapi_operator_surface",
    "target_not_currently_routable",
}
ROUTE_STATUS_OVERRIDE_VALUES = {
    "gate_wins_for_applies_to",
}
OPERATOR_MUTATION_SUPPORT_GATE_STATUS = "target_not_currently_routable"
TOKEN_ISSUER = "firemud-account-service"
MEMBERSHIP_GENERATION_APPLICABILITY_VALUES = {
    True,
    False,
    "conditional_by_operator_role",
}
CONDITIONAL_OPERATOR_MEMBERSHIP_SHAPE = {
    "tenant_role": True,
    "platformAdmin_global": False,
}
OPERATOR_INGRESS_ROUTES = {
    ("logging-admin-service", "POST /feature-flags/toggle"),
    ("logging-admin-service", "POST /moderation/actions"),
    ("logging-admin-service", "POST /tick-remediation/pause"),
    ("logging-admin-service", "POST /tick-remediation/resume"),
    ("logging-admin-service", "POST /admission-pointers"),
    ("logging-admin-service", "POST /admission-pointers/cutover"),
    ("logging-admin-service", "POST /admission-pointers/version-upgrades"),
}
ADMISSION_POINTER_MUTATION_ROUTES = {
    ("logging-admin-service", "POST /admission-pointers"),
    ("logging-admin-service", "POST /admission-pointers/cutover"),
}
GAME_SESSION_OPERATOR_ROUTES = {
    ("game-session-service", "POST /sessions"),
    ("game-session-service", "POST /sessions/{sessionId}/stop"),
    ("game-session-service", "POST /sessions/{sessionId}/restart"),
}
GAME_SESSION_OWNER_ROLE_REFRESH_ROUTES = {
    ("game-session-service", "POST /sessions/{sessionId}/refresh-roles"),
}
REQUIRED_SESSION_LIFECYCLE_GATE_ROUTES = {
    f"{service}/{route}" for service, route in GAME_SESSION_OPERATOR_ROUTES
}
CONDITIONAL_OPERATOR_ROUTES = OPERATOR_INGRESS_ROUTES | GAME_SESSION_OPERATOR_ROUTES
ACCOUNT_SUBJECT_BOUND_ROUTES = {
    ("account-service", "ExportAccount"),
    ("account-service", "DeleteAccount"),
}
SECURITY_LOCK_EXPORT_GENERATION_FIELDS = (
    "issuer_authority_generation_applies",
    "account_authority_generation_applies",
    "tenant_billing_authority_generation_applies",
    "membership_authority_generation_applies",
)
EXPORT_INITIATION_ROUTE = "POST /accounts/{accountId}/exports"
EXPORT_INITIATION_ROUTE_IDENTITY = ("account-service", EXPORT_INITIATION_ROUTE)
LEGACY_EXPORT_ROUTE_IDENTITY = ("account-service", "ExportAccount")
EXPORT_INITIATION_ACTION_FAMILY = "export_initiate"
EXPORT_INITIATION_REQUIRED_LIVE_CHECKS = {
    EXPORT_INITIATION_ACTION_FAMILY: {"export_availability"},
}
EXPORT_INITIATION_REQUIRED_CANONICAL_ERRORS = {
    "AUTH_UNAVAILABLE",
    "PERMISSION_DENIED",
    "IDEMPOTENCY_CONFLICT",
}
ACCOUNT_EXPORT_ROUTE_ACTION_FAMILIES = {
    EXPORT_INITIATION_ROUTE_IDENTITY: EXPORT_INITIATION_ACTION_FAMILY,
    (
        "account-service",
        "GET /accounts/{accountId}/exports/{exportId}",
    ): "export_status",
    (
        "account-service",
        "GET /accounts/{accountId}/exports/{exportId}/content",
    ): "export_content",
}
ACCOUNT_STATE_VOCABULARY = [
    "active",
    "security_locked",
    "deactivated_pending_delete",
]
ACCOUNT_EXPORT_BRANCH_CLASSIFICATIONS = {
    "active": "account_scoped",
    "security_locked": "security_lock_export_scoped",
    "deactivated_pending_delete": "pending_deletion_scoped",
}
ACCOUNT_EXPORT_BRANCHES = set(ACCOUNT_EXPORT_BRANCH_CLASSIFICATIONS.items())
ACCOUNT_EXPORT_APPLICABILITY_RULES = [
    {
        "service": identity[0],
        "route": identity[1],
        "action_family": action_family,
        "account_state_classifications": ACCOUNT_EXPORT_BRANCH_CLASSIFICATIONS,
    }
    for identity, action_family in ACCOUNT_EXPORT_ROUTE_ACTION_FAMILIES.items()
]
ACTIVE_ACCOUNT_EXPORT_OPERATION_BINDINGS = {
    "export_initiate": {
        "source": "authenticated_account_and_export_registry",
        "required": ["accountId", "exportId"],
        "comparison": "create_and_attach_exact",
        "mismatch": "PERMISSION_DENIED",
    },
    "export_status": {
        "source": "authenticated_account_and_export_registry",
        "required": ["accountId", "exportId"],
        "comparison": "exact",
        "mismatch": "PERMISSION_DENIED",
    },
    "export_content": {
        "source": "authenticated_account_and_export_registry",
        "required": ["accountId", "exportId"],
        "comparison": "exact_completed_operation",
        "mismatch": "PERMISSION_DENIED",
    },
}
ACTIVE_ACCOUNT_EXPORT_REQUIRED_LIVE_CHECKS = {
    "account_state_export_eligible",
    "current_account_generation",
    "issuer_generation",
}
EXPORT_CONTENT_REQUIRED_LIVE_CHECKS = {
    "recent_account_authentication",
    "export_availability",
}
SECURITY_LOCK_EXPORT_ACTION_FAMILIES = set(ACCOUNT_EXPORT_ROUTE_ACTION_FAMILIES.values())
SECURITY_LOCK_EXPORT_CONTENT_AUDIT_CONTRACT = (
    "required_account_recovery_case_export_job_and_outcome"
)
PENDING_DELETION_EXPORT_CONTENT_AUDIT_CONTRACT = (
    "required_account_deletion_workflow_export_job_and_outcome"
)
ACTIVE_ACCOUNT_EXPORT_CONTENT_AUDIT_CONTRACT = (
    "required_account_export_job_and_outcome"
)
RECOVERY_EXPORT_AUDITED_ACTION_FAMILIES = {
    "export_initiate",
    "export_content",
}
CHARACTER_ROUTE_CONTRACTS = {
    (
        "account-service",
        "GET /auth/bootstrap/worlds/{worldSlug}/realms/{realmSlug}/characters",
    ): {
        "character_operation": "existing_character_discovery",
        "selected_character_requirement": "none",
        "gameplay_binding": "not_performed",
        "final_play_binding_cas": "not_performed",
    },
    (
        "account-service",
        "POST /auth/bootstrap/worlds/{worldSlug}/realms/{realmSlug}/characters",
    ): {
        "character_operation": "character_creation",
        "selected_character_requirement": "none",
        "gameplay_binding": "not_performed",
        "final_play_binding_cas": "not_performed",
    },
}
SELECTED_CHARACTER_REQUIREMENT_VOCABULARY = ["none"]
CHARACTER_OPERATION_VOCABULARY = [
    "existing_character_discovery",
    "character_creation",
]
SECURITY_LOCK_EXPORT_CREDENTIAL_BINDING = {
    "source": "security_lock_export_credential_registry",
    "required": ["accountId", "recoveryCaseId"],
    "comparison": "exact",
    "mismatch": "AUTH_SESSION_REVOKED",
}
SECURITY_LOCK_EXPORT_OPERATION_BINDINGS = {
    "export_initiate": {
        "source": "security_lock_export_credential_and_export_registry",
        "required": ["accountId", "recoveryCaseId", "exportId"],
        "comparison": "create_and_attach_exact",
        "mismatch": "PERMISSION_DENIED",
    },
    "export_status": {
        "source": "security_lock_export_credential_and_export_registry",
        "required": ["accountId", "recoveryCaseId", "exportId"],
        "comparison": "exact",
        "mismatch": "PERMISSION_DENIED",
    },
    "export_content": {
        "source": "security_lock_export_credential_and_export_registry",
        "required": ["accountId", "recoveryCaseId", "exportId"],
        "comparison": "exact_completed_operation",
        "mismatch": "PERMISSION_DENIED",
    },
}
SECURITY_LOCK_EXPORT_CLASSIFICATION_OPERATION_BINDINGS = {
    action_family: {
        "required": binding["required"],
        "comparison": binding["comparison"],
    }
    for action_family, binding in SECURITY_LOCK_EXPORT_OPERATION_BINDINGS.items()
}
PENDING_DELETION_EXPORT_OPERATION_BINDINGS = {
    "export_initiate": {
        "source": "pending_deletion_credential_and_export_registry",
        "required": [
            "accountId",
            "deletionWorkflowId",
            "deletionWorkflowGeneration",
            "exportId",
        ],
        "comparison": "create_and_attach_exact",
        "mismatch": "PERMISSION_DENIED",
    },
    "export_status": {
        "source": "pending_deletion_credential_and_export_registry",
        "required": [
            "accountId",
            "deletionWorkflowId",
            "deletionWorkflowGeneration",
            "exportId",
        ],
        "comparison": "exact",
        "mismatch": "PERMISSION_DENIED",
    },
    "export_content": {
        "source": "pending_deletion_credential_and_export_registry",
        "required": [
            "accountId",
            "deletionWorkflowId",
            "deletionWorkflowGeneration",
            "exportId",
        ],
        "comparison": "exact_completed_operation",
        "mismatch": "PERMISSION_DENIED",
    },
}
CAPACITY_ADMISSION_ROUTE = ("account-service", "CommitTenantCapacityAdmission")
CAPACITY_DELTA_WIRE_CONTRACT = {
    "field": "capacityDelta",
    "presence": "explicit",
    "absent_encoding": "omitted",
    "present_zero_encoding": "explicit_zero",
    "boolean_zero_encoding": "rejected",
    "golden_vectors": {
        "absent": {
            "presence": "absent",
            "wire_value": "omitted",
        },
        "present_zero": {
            "presence": "present",
            "wire_value_type": "integer",
            "wire_value": 0,
        },
    },
}
OPERATOR_AUTHORIZATION_BRANCHES = {
    "tenant_role": {
        "membership_when_tenant_role",
        "membership_generation",
        "tenant_generation",
    },
    "platformAdmin_global": {
        "current_operator_roles",
        "current_global_role",
        "role_appropriate_assurance",
        "target_tenant_generation",
    },
}
HUMAN_OPERATOR_ISSUANCE_AUTHORIZATION_BRANCHES = {
    branch: checks | {"current_account_generation", "current_token_generation"}
    for branch, checks in OPERATOR_AUTHORIZATION_BRANCHES.items()
}
ACCOUNT_AUTHORIZATION_BRANCHES = {
    "self_service": {
        "target_subject_binding": "exact_caller_account_id",
        "required_live_checks": {"current_account_generation"},
    },
    "platformAdmin_override": {
        "target_subject_binding": "explicit_target_account_id",
        "required_live_checks": {
            "issuer_generation",
            "account_generation",
            "current_global_role",
            "role_appropriate_assurance",
        },
    },
}
REQUIRED_ROLE_ASSURANCE_ROLES = {"platformAdmin", "billingAdmin", "support"}
REQUIRED_TENANT_GENERATION_EXCEPTIONS = {
    "billing_safe_tenant": {
        "target_tenant_generation": False,
        "required_live_checks": {
            "issuer_generation",
            "account_generation",
            "membership_generation",
            "membership",
            "current_operator_roles",
        },
    },
    "cross_tenant_support_safe": {
        "target_tenant_generation": False,
        "required_live_checks": {
            "issuer_generation",
            "account_generation",
            "current_global_role",
            "role_appropriate_assurance",
        },
    },
    "cross_tenant_billing_safe": {
        "target_tenant_generation": False,
        "required_live_checks": {
            "issuer_generation",
            "account_generation",
            "current_global_role",
            "role_appropriate_assurance",
        },
    },
}
REQUIRED_NO_TARGET_TENANT_CLASSIFICATIONS = {
    "public": {
        "target_tenant_generation": False,
        "generation_behavior": "no_tenant_authority",
        "required_authority": set(),
        "target_tenant_generation_advance_behavior": "remains_valid",
    },
    "account_scoped": {
        "target_tenant_generation": False,
        "generation_behavior": "issuer_and_account_authority_only",
        "required_authority": {"issuer_generation", "account_generation"},
        "target_tenant_generation_advance_behavior": "remains_valid",
    },
    "caller_membership_scoped": {
        "target_tenant_generation": False,
        "generation_behavior": "caller_membership_authority_only",
        "required_authority": {"membership", "membership_generation"},
        "target_tenant_generation_advance_behavior": "remains_valid",
    },
    "player_bootstrap_tenant": {
        "target_tenant_generation": False,
        "generation_behavior": "membership_authority_when_route_requires_existing_membership",
        "required_authority": {"membership", "membership_generation"},
        "target_tenant_generation_advance_behavior": "route_declared",
    },
    "pre_tenant_discovery": {
        "target_tenant_generation": False,
        "generation_behavior": "no_tenant_or_membership_authority",
        "required_authority": set(),
        "target_tenant_generation_advance_behavior": "remains_valid",
    },
    "public_production_onboarding": {
        "target_tenant_generation": False,
        "generation_behavior": "membership_authority_after_membership_exists",
        "required_authority": {"membership", "membership_generation"},
        "target_tenant_generation_advance_behavior": "remains_valid",
    },
    "gameplay_admission": {
        "target_tenant_generation": False,
        "generation_behavior": "mode_selected_membership_and_grant_authority",
        "required_authority": set(),
        "target_tenant_generation_advance_behavior": "route_declared",
    },
    "internal_workload": {
        "target_tenant_generation": False,
        "generation_behavior": "route_declared_caller_and_target_authority",
        "required_authority": set(),
        "target_tenant_generation_advance_behavior": "route_declared",
    },
    "pending_deletion_scoped": {
        "target_tenant_generation": False,
        "generation_behavior": "pending_deletion_credential_only",
        "required_authority": {
            "pending_deletion_state",
            "pending_deletion_credential_registry",
        },
        "target_tenant_generation_advance_behavior": (
            "denied_by_pending_deletion_credential_contract"
        ),
    },
    "security_lock_export_scoped": {
        "target_tenant_generation": False,
        "generation_behavior": "security_lock_export_credential_only",
        "required_authority": {
            "security_lock_export_credential_registry",
            "security_lock_recovery_state",
            "security_lock_export_action_family",
        },
        "target_tenant_generation_advance_behavior": (
            "remains_bound_to_exact_recovery_export_lifecycle"
        ),
    },
}
REQUIRED_TENANT_AUTHORITY_CLASSIFICATIONS = {
    "tenant_regular",
    "cross_tenant_data_bearing",
}
NO_TARGET_TENANT_CLASSES_WITHOUT_ROUTE_SPECIFIC_TARGET_AUTHORITY = {
    "public",
    "account_scoped",
    "caller_membership_scoped",
    "player_bootstrap_tenant",
    "pre_tenant_discovery",
    "public_production_onboarding",
    "security_lock_export_scoped",
}
RECOVERY_EXPORT_GENERATION_CONTRACTS = {
    "pending_deletion_scoped": {
        "credential": "pending-deletion-access",
        "action_family_check": "pending_deletion_action_family",
        "audit_contract": PENDING_DELETION_EXPORT_CONTENT_AUDIT_CONTRACT,
    },
    "security_lock_export_scoped": {
        "credential": "security-lock-export",
        "action_family_check": "security_lock_export_action_family",
        "audit_contract": SECURITY_LOCK_EXPORT_CONTENT_AUDIT_CONTRACT,
    },
}
SECURITY_LOCK_EXPORT_NEGATIVE_PROOF = {
    "security_lock_export_recovery_case_mismatch_denied",
    "security_lock_export_export_job_mismatch_denied",
    "security_lock_export_action_family_mismatch_denied",
    "security_lock_export_remains_bound_after_target_tenant_generation_advance",
}
PENDING_DELETION_EXPORT_ACTION_FAMILIES = set(
    ACCOUNT_EXPORT_ROUTE_ACTION_FAMILIES.values()
)
PENDING_DELETION_ACTION_FAMILIES = {
    "deletion_status",
    "deletion_cancel",
    "billing_settlement",
} | PENDING_DELETION_EXPORT_ACTION_FAMILIES
ROUTES_WITH_EXPLICIT_TARGET_TENANT_AUTHORITY = {
    ("account-service", "IssueConnectToken"),
}
PROFILE_ROUTES = (
    ("account-service", "GET /tenants/{tenantId}/profiles/{accountId}"),
    ("account-service", "PUT /tenants/{tenantId}/profiles/{accountId}"),
)
PROFILE_TARGET_SUBJECT_BINDING = "exact_caller_account_id_for_every_role"
PRIVILEGED_OPERATOR_ROLE_ASSURANCE = "privileged_control_when_global_role"
PRIVILEGED_CONTROL_VALUES = {"required", "not_required", "establishes_window"}
AUTHORITY_GENERATION_VALUES = {"required", "omitted", "target_tenant_generation"}
PLATFORM_ADMIN_ROLE_ASSURANCE_ROUTE_IDENTITIES = {
    "account-service/IssueHumanOperatorAuthorizationReference",
}
OPERATOR_REFERENCE_ISSUANCE_REQUIRED_FIELDS = {
    ("account-service", "IssueHumanOperatorAuthorizationReference"): {
        "action_family",
        "action_family_schema_id",
        "action_family_schema_version",
    },
    ("account-service", "IssueAutomationOperatorAuthorizationReference"): {
        "automation_policy_id",
        "automation_policy_version",
        "tenant_scope",
        "action_family",
        "action_family_schema_id",
        "action_family_schema_version",
        "control_plane_request_id",
        "mutation_digest",
    },
}
AUTOMATION_OPERATOR_ISSUANCE_ROUTE_STATUS = "target_not_currently_routable"
AUTOMATION_OPERATOR_ISSUANCE_SCHEMA_SELECTOR_FIELDS = [
    "action_family",
    "action_family_schema_id",
    "action_family_schema_version",
]
AUTOMATION_OPERATOR_ISSUANCE_SCHEMA_MAPPING_REJECTIONS = {
    "unknown": "reject_before_issuance",
    "duplicate": "reject_before_issuance",
    "ambiguous": "reject_before_issuance",
    "mismatched": "reject_before_issuance",
    "moderation": "reject_before_issuance",
}
AUTOMATION_OPERATOR_ISSUANCE_MODERATION_IDENTITY_POLICY = {
    "status": "rejected_before_issuance",
    "request_identity": "dedicated_moderation_identity_required",
    "digest": "dedicated_moderation_digest_required",
    "control_plane_request_id": "correlation_only",
}
HUMAN_OPERATOR_ISSUANCE_COMMON_REQUIRED_FIELDS = {
    "action_family",
    "action_family_schema_id",
    "action_family_schema_version",
}
# Keep the tuple order aligned with the machine-readable branch-field contract.
# The set-shaped contracts below derive from this canonical ordered collection.
PLATFORM_ACCESS_BAN_FORBIDDEN_FIELDS = (
    "tenant_scope",
    "tenant_id",
    "target_tenant_generation",
    "membership_version_when_applicable",
)
HUMAN_OPERATOR_ISSUANCE_BRANCHES = {
    "non_moderation": {
        "selector": "action_category=absent",
        "scope": "tenant",
        "membership_authority_generation_applies": "conditional_by_operator_role",
        "membership_authority_generation_condition": {
            "tenant_role": True,
            "platformAdmin_global": False,
        },
        "global_platform_admin_reference_generation_binding": "target_tenant_generation",
        "global_platform_admin_membership_required": False,
        "required_fields": HUMAN_OPERATOR_ISSUANCE_COMMON_REQUIRED_FIELDS
        | {"tenant_scope"},
    },
    "tenant_restriction": {
        "selector": "action_category=tenant_restriction",
        "scope": "tenant",
        "membership_authority_generation_applies": "conditional_by_operator_role",
        "membership_authority_generation_condition": {
            "tenant_role": True,
            "platformAdmin_global": False,
        },
        "global_platform_admin_reference_generation_binding": "target_tenant_generation",
        "global_platform_admin_membership_required": False,
        "required_fields": HUMAN_OPERATOR_ISSUANCE_COMMON_REQUIRED_FIELDS
        | {"tenant_scope"},
    },
    "platform_access_ban": {
        "selector": "action_category=platform_access_ban",
        "scope": "account",
        "classification": "account_scoped",
        "target_subject_binding": "explicit_target_account_id",
        "role_assurance": PRIVILEGED_OPERATOR_ROLE_ASSURANCE,
        "roles": {"any_of": ["platformAdmin"]},
        "membership_authority_generation_applies": False,
        "global_platform_admin_membership_required": False,
        "tenant_billing_authority_generation_applies": False,
        "required_live_checks": {
            "current_account_generation",
            "current_token_generation",
            "issuer_generation",
            "account_generation",
            "current_operator_roles",
            "current_global_role",
            "role_appropriate_assurance",
        },
        "forbidden_live_checks": {
            "target_tenant_generation",
            "tenant_generation",
            "membership_when_tenant_role",
            "membership_generation",
        },
        "required_fields": HUMAN_OPERATOR_ISSUANCE_COMMON_REQUIRED_FIELDS
        | {"target_account_id"},
        "forbidden_fields": set(PLATFORM_ACCESS_BAN_FORBIDDEN_FIELDS),
    },
}
HUMAN_OPERATOR_ISSUANCE_SHARED_BRANCH_FIELDS = {
    "non_moderation": {
        "selector": "action_category=absent",
        "required": ["tenant_scope"],
        "optional": ["membership_version_when_applicable"],
    },
    "tenant_restriction": {
        "required": ["tenant_scope"],
        "optional": ["membership_version_when_applicable"],
    },
    "platform_access_ban": {
        "required": ["target_account_id"],
        "forbidden": list(PLATFORM_ACCESS_BAN_FORBIDDEN_FIELDS),
    },
}
OPERATOR_ISSUANCE_MODERATION_IDENTITY_KEYS = {
    "policy_intent": {
        "request_identity": "policy_intent_request_id",
        "digest": "mutation_digest",
        "key": [
            "policy_intent_request_id",
            "action_family_schema_id",
            "action_family_schema_version",
            "mutation_digest",
            "exact_scope",
            "authority_path_binding",
        ],
    },
    "owner_enforcement": {
        "request_identity": "owner_enforcement_request_id",
        "digest": "owner_enforcement_digest",
        "key": [
            "owner_enforcement_request_id",
            "action_family_schema_id",
            "action_family_schema_version",
            "owner_enforcement_digest",
            "exact_scope",
            "authority_path_binding",
        ],
    },
}
HUMAN_OPERATOR_ISSUANCE_IDENTITY_ALTERNATIVES = {
    "non_moderation": {
        "request_identity": "control_plane_request_id",
        "digest": "mutation_digest",
        "control_plane_request_id": "mutation_identity",
    },
    "moderation_policy_intent": {
        "request_identity": "policy_intent_request_id",
        "digest": "mutation_digest",
        "control_plane_request_id": "correlation_only",
    },
    "moderation_owner_enforcement": {
        "request_identity": "owner_enforcement_request_id",
        "digest": "owner_enforcement_digest",
        "control_plane_request_id": "correlation_only",
    },
}
HUMAN_OPERATOR_ISSUANCE_SELECTOR_KEY_FIELDS = [
    "action_family",
    "action_family_schema_id",
    "action_family_schema_version",
]
HUMAN_OPERATOR_ISSUANCE_SELECTOR_MAPPING_REJECTIONS = {
    "unknown": "reject_before_issuance",
    "duplicate": "reject_before_issuance",
    "ambiguous": "reject_before_issuance",
    "mismatched": "reject_before_issuance",
}
HUMAN_OPERATOR_ISSUANCE_SELECTOR_MAPPING_REF = (
    "operator_delegation.issuance_paths.human.bindings."
    "mutation_identity_selector.mapping"
)
HUMAN_OPERATOR_ISSUANCE_ROUTE_STATUS = "target_not_currently_routable"
AUTH_UNAVAILABLE = "AUTH_UNAVAILABLE"
UNAVAILABLE_AUTHORITY_ERROR_ALIASES = {
    "MEMBERSHIP_AUTH_UNAVAILABLE",
}
AUTH_UNAVAILABLE_REQUIRED_ROUTES = {
    ("account-service", "IssueConnectToken"),
    ("account-service", "CommitTenantCapacityAdmission"),
    ("account-service", "BillingArtifactsTenant"),
    ("account-service", "BillingArtifactsCrossTenant"),
}
REQUIRED_AUTHORITY_SEMANTICS = (
    "class_metadata_for_conditional_route_or_token_authority"
)
LOGGING_ADMIN_IDEMPOTENT_OPERATOR_ROUTES = {
    ("logging-admin-service", "POST /admission-pointers"),
    ("logging-admin-service", "POST /admission-pointers/cutover"),
    ("logging-admin-service", "POST /admission-pointers/version-upgrades"),
    ("logging-admin-service", "POST /feature-flags/toggle"),
    ("logging-admin-service", "POST /moderation/actions"),
    ("logging-admin-service", "POST /tick-remediation/pause"),
    ("logging-admin-service", "POST /tick-remediation/resume"),
}
MODERATION_ACTION_ROUTE = ("logging-admin-service", "POST /moderation/actions")
MODERATION_TENANT_ACTION_CATEGORIES = {
    "gameplay_ban",
    "chat_mute",
    "chat_ban",
}
MODERATION_PLATFORM_ACTION_CATEGORY = "platform_access_ban"
MODERATION_ACTION_BRANCHES = {
    "tenant_restriction": MODERATION_TENANT_ACTION_CATEGORIES,
    "platform_access_ban": {MODERATION_PLATFORM_ACTION_CATEGORY},
}
MODERATION_ACTION_REQUIRED_FIELDS = {
    "tenant_restriction": {
        "policy_intent_request_id",
        "control_plane_request_id",
        "actor_from_session",
        "reason",
        "tenant_id",
        "target_account_id",
        "action",
        "mutation_digest",
    },
    "platform_access_ban": {
        "policy_intent_request_id",
        "control_plane_request_id",
        "actor_from_session",
        "reason",
        "target_account_id",
        "action",
        "mutation_digest",
    },
}
MODERATION_ACTION_IDEMPOTENCY_CONTRACT = {
    "key": "policy_intent_request_id",
    "digest": "mutationDigest/v1",
    "digest_field": "mutation_digest",
    "exact_retry": "same_policy_intent_request_id_and_digest_returns_stored_outcome",
    "changed_digest": "IDEMPOTENCY_CONFLICT",
    "changed_request_id": "distinct_policy_intent_operation",
    "control_plane_request_id": "correlation_only",
    "owner_enforcement_request_id": "distinct_future_owner_command_identity",
}
MODERATION_PLATFORM_FORBIDDEN_ROUTE_FIELDS = set(
    PLATFORM_ACCESS_BAN_FORBIDDEN_FIELDS
)
MODERATION_POLICY_INTENT_COVERAGE_IDENTITY = (
    "moderation-policy-intent-local-redemption"
)
MODERATION_POLICY_INTENT_ROUTE_IDENTITIES = [
    "logging-admin-service/POST /moderation/actions",
    "logging-admin-service/ApplyModerationAction",
]
MODERATION_SUPPORT_GATE_REDEMPTION_REQUIREMENT = (
    "account_issued_authorization_reference_issuance_and_"
    "contract_declared_receiving_boundary_redemption"
)
MODERATION_ACTION_SELECTOR_MAPPING = {
    category: branch
    for branch, categories in MODERATION_ACTION_BRANCHES.items()
    for category in sorted(categories)
}
MODERATION_ACTION_SELECTOR_REJECTED_VALUES = {
    "account_security_lock",
    "ban",
    "account_ban",
    "account_security_ban",
}
MODERATION_ENFORCEMENT_OWNER_BY_CATEGORY = {
    "platform_access_ban": "account-service",
    "gameplay_ban": "game-session-service",
    "chat_mute": "social-groups-service",
    "chat_ban": "social-groups-service",
}
EXPECTED_ROUTE_CLASS_BRANCHES = {
    ("account_scoped", "platformAdmin_global"): {
        "scope": "account",
        "role": "platformAdmin",
        "generations": {
            "issuer": "required",
            "account": "required",
            "tenant": "omitted",
            "membership": "omitted",
        },
        "privileged_control": "required",
    },
    ("tenant_regular", "tenant_role"): {
        "scope": "tenant",
        "role": "route_declared_tenant_role",
        "generations": {
            "issuer": "required",
            "account": "required",
            "tenant": "required",
            "membership": "required",
        },
        "privileged_control": "not_required",
    },
    ("tenant_regular", "platformAdmin_global"): {
        "scope": "tenant",
        "role": "platformAdmin",
        "generations": {
            "issuer": "required",
            "account": "required",
            "tenant": "target_tenant_generation",
            "membership": "omitted",
        },
        "privileged_control": "required",
    },
    ("cross_tenant_data_bearing", "platformAdmin_global"): {
        "scope": "cross_tenant",
        "role": "platformAdmin",
        "generations": {
            "issuer": "required",
            "account": "required",
            "tenant": "target_tenant_generation",
            "membership": "omitted",
        },
        "privileged_control": "required",
    },
    ("billing_safe_tenant", "tenantAdmin"): {
        "scope": "tenant",
        "role": "tenantAdmin",
        "generations": {
            "issuer": "required",
            "account": "required",
            "tenant": "omitted",
            "membership": "required",
        },
        "privileged_control": "not_required",
    },
    ("cross_tenant_support_safe", "support_global"): {
        "scope": "cross_tenant",
        "role": "support",
        "generations": {
            "issuer": "required",
            "account": "required",
            "tenant": "omitted",
            "membership": "omitted",
        },
        "privileged_control": "not_required",
    },
    ("cross_tenant_support_safe", "platformAdmin_global"): {
        "scope": "cross_tenant",
        "role": "platformAdmin",
        "generations": {
            "issuer": "required",
            "account": "required",
            "tenant": "omitted",
            "membership": "omitted",
        },
        "privileged_control": "required",
    },
    ("cross_tenant_billing_safe", "billingAdmin_global"): {
        "scope": "cross_tenant",
        "role": "billingAdmin",
        "generations": {
            "issuer": "required",
            "account": "required",
            "tenant": "omitted",
            "membership": "omitted",
        },
        "privileged_control": "required",
    },
    ("cross_tenant_billing_safe", "platformAdmin_global"): {
        "scope": "cross_tenant",
        "role": "platformAdmin",
        "generations": {
            "issuer": "required",
            "account": "required",
            "tenant": "omitted",
            "membership": "omitted",
        },
        "privileged_control": "required",
    },
}
CANONICAL_OPERATOR_INGRESS = "logging-admin-service"
DIRECT_OWNER_ROUTE_POLICY = "deny_at_edge_and_migrate_to_logging_admin"
# These caches are created afresh for one parsed matrix document in
# validate_matrix_document. Do not reuse them across validation calls: their object
# identity keys and cached structural errors are meaningful only within that document.
# cached_required_fields must parse and cache the structural result before consumers
# use it, matching cached_live_checks; cache hits must not re-emit structural errors.
LiveChecksCache = dict[tuple[int, str], tuple[object, set[str]]]
RequiredFieldsCache = dict[tuple[int, str], tuple[object, list[str] | None]]


def canonical_route_components(service: Any, route: Any) -> tuple[str, str] | None:
    if (
        not isinstance(service, str)
        or not service.strip()
        or not isinstance(route, str)
        or not route.strip()
    ):
        return None
    return service.strip(), route.strip()


def route_set_key(route: dict[str, Any]) -> tuple[str, str] | None:
    return canonical_route_components(route.get("service"), route.get("route"))


def route_identity_from_route(route: dict[str, Any]) -> str | None:
    components = canonical_route_components(route.get("service"), route.get("route"))
    if components is None:
        return None
    service, route_name = components
    return f"{service}/{route_name}"


def route_key(route: dict[str, Any]) -> str | None:
    components = canonical_route_components(route.get("service"), route.get("route"))
    if components is None:
        return None
    service, name = components
    return f"{service}|{name}"


def route_label(route: dict[str, Any]) -> str:
    service = route.get("service") or "<unknown-service>"
    route_name = route.get("route") or "<unknown-route>"
    label = f"{service} {route_name}"
    if "applicability" not in route:
        return label
    try:
        discriminator = json.dumps(
            route.get("applicability"), sort_keys=True, separators=(",", ":")
        )
    except (TypeError, ValueError):
        discriminator = repr(route.get("applicability"))
    return f"{label} [variant={discriminator}]"


def append_unique_error(errors: list[str], message: str) -> None:
    if message not in errors:
        errors.append(message)


def string_list(value: Any, field: str, errors: list[str]) -> list[str]:
    if not isinstance(value, list) or any(not isinstance(item, str) for item in value):
        errors.append(f"{field} must be a list of strings")
        return []
    return value


def cached_live_checks(
    source: object,
    value: Any,
    field: str,
    errors: list[str],
    live_checks_cache: LiveChecksCache | None = None,
    cache_field: str | None = None,
) -> set[str]:
    semantic_field = cache_field or field
    if live_checks_cache is not None:
        cache_key = (id(source), semantic_field)
        cached = live_checks_cache.get(cache_key)
        if cached is not None and cached[0] is source:
            return set(cached[1])
    parsed_checks = set(string_list(value, field, errors))
    if live_checks_cache is not None:
        live_checks_cache[(id(source), semantic_field)] = (source, set(parsed_checks))
    return set(parsed_checks)


def cached_required_fields(
    source: object,
    value: Any,
    field: str,
    errors: list[str],
    required_fields_cache: RequiredFieldsCache | None = None,
    cache_field: str | None = None,
) -> list[str]:
    semantic_field = cache_field or field
    if required_fields_cache is not None:
        cache_key = (id(source), semantic_field)
        cached = required_fields_cache.get(cache_key)
        if cached is not None and cached[0] is source:
            return list(cached[1] or [])
    if not isinstance(value, list) or any(not isinstance(item, str) for item in value):
        errors.append(f"{field} must be a list of strings")
        parsed_fields: list[str] | None = None
    else:
        parsed_fields = list(value)
    if required_fields_cache is not None:
        required_fields_cache[(id(source), semantic_field)] = (source, parsed_fields)
    return list(parsed_fields or [])


def collect_live_checks(
    value: Any,
    field: str,
    errors: list[str],
    live_checks_cache: LiveChecksCache | None = None,
) -> list[str]:
    checks: list[str] = []
    if isinstance(value, dict):
        for key, child in value.items():
            child_field = f"{field}.{key}" if field else key
            if key == "required_live_checks":
                checks.extend(
                    cached_live_checks(
                        value,
                        child,
                        child_field,
                        errors,
                        live_checks_cache,
                        "required_live_checks",
                    )
                )
            else:
                checks.extend(
                    collect_live_checks(child, child_field, errors, live_checks_cache)
                )
    elif isinstance(value, list):
        for index, child in enumerate(value):
            checks.extend(
                collect_live_checks(
                    child, f"{field}[{index}]", errors, live_checks_cache
                )
            )
    return checks


def collect_auth_paths(value: Any) -> list[Any]:
    auth_paths: list[Any] = []
    if isinstance(value, dict):
        for key, child in value.items():
            if key == "auth_path":
                auth_paths.append(child)
            auth_paths.extend(collect_auth_paths(child))
    elif isinstance(value, list):
        for child in value:
            auth_paths.extend(collect_auth_paths(child))
    return auth_paths


def validate_auth_path_vocabulary(
    document: dict[str, Any], errors: list[str]
) -> set[str]:
    vocabulary = document.get("auth_path_vocabulary")
    if (
        not isinstance(vocabulary, list)
        or not vocabulary
        or any(not isinstance(item, str) for item in vocabulary)
    ):
        errors.append("auth_path_vocabulary must be a non-empty list of strings")
        allowed_auth_paths: set[str] = set()
    else:
        allowed_auth_paths = set(vocabulary)
        if len(allowed_auth_paths) != len(vocabulary):
            errors.append("auth_path_vocabulary must not contain duplicates")

    unknown_auth_paths = [
        auth_path
        for auth_path in collect_auth_paths(document)
        if not isinstance(auth_path, str) or auth_path not in allowed_auth_paths
    ]
    if unknown_auth_paths:
        errors.append(
            "auth_path contains values outside the closed vocabulary: "
            f"{unknown_auth_paths!r}"
        )
    return allowed_auth_paths


def validate_token_profiles(
    document: dict[str, Any], errors: list[str]
) -> dict[str, dict[str, str]]:
    raw_profiles = document.get("token_profiles")
    if not isinstance(raw_profiles, list):
        errors.append("token_profiles must be a list of mappings")
        return {}

    profiles: dict[str, dict[str, str]] = {}
    for index, raw_profile in enumerate(raw_profiles):
        label = f"token_profiles[{index}]"
        if not isinstance(raw_profile, dict):
            errors.append(f"{label} must be a mapping")
            continue
        values: dict[str, str] = {}
        for field in ("profile", "type", "issuer", "audience"):
            value = raw_profile.get(field)
            if not isinstance(value, str) or not value.strip():
                errors.append(f"{label}.{field} must be a non-empty string")
            else:
                values[field] = value
        profile = values.get("profile")
        if profile:
            if profile in profiles:
                errors.append(
                    f"token_profiles must not contain duplicate profile {profile!r}"
                )
            else:
                profiles[profile] = values
        if values.get("issuer") and values["issuer"] != TOKEN_ISSUER:
            errors.append(f"{label}.issuer must be {TOKEN_ISSUER!r}")
        if "kind" in raw_profile:
            errors.append(f"{label} must use type instead of legacy kind")
    return profiles


def canonicalize_role_assurance_route_identities(
    route_identities: Any, field: str, errors: list[str]
) -> list[str] | None:
    if route_identities is not None and (
        not isinstance(route_identities, list)
        or any(
            not isinstance(item, str) or not item.strip()
            for item in route_identities
        )
    ):
        errors.append(f"{field} must be a list of strings")
        return None
    if route_identities is None:
        return None

    canonical_route_identities: list[str] = []
    invalid_identity = False
    for index, identity in enumerate(route_identities):
        service, separator, route_name = identity.partition("/")
        components = (
            canonical_route_components(service, route_name) if separator else None
        )
        if components is None:
            errors.append(
                f"{field}[{index}] must be a non-empty service/route identity"
            )
            invalid_identity = True
            continue
        canonical_route_identities.append("/".join(components))
    return None if invalid_identity else canonical_route_identities


def validate_platform_admin_role_assurance_route_identities(
    field: str, canonical_route_identities: list[str] | None, errors: list[str]
) -> None:
    expected_route_identities = set(PLATFORM_ADMIN_ROLE_ASSURANCE_ROUTE_IDENTITIES)
    if (
        canonical_route_identities is None
        or len(canonical_route_identities) != len(set(canonical_route_identities))
        or set(canonical_route_identities) != expected_route_identities
    ):
        errors.append(
            f"{field} must equal {sorted(expected_route_identities)}"
        )


def validate_role_assurance_classifications(
    applies_to: dict[str, Any],
    allowed_classifications: set[str],
    label: str,
    errors: list[str],
) -> bool:
    route_classifications = applies_to.get("route_classifications")
    if not isinstance(route_classifications, list) or not route_classifications:
        errors.append(
            f"{label}.applies_to.route_classifications must be a non-empty list of strings"
        )
        return False
    if any(not isinstance(item, str) for item in route_classifications):
        errors.append(
            f"{label}.applies_to.route_classifications must be a list of strings"
        )
        return False
    unknown_classifications = sorted(
        set(route_classifications) - allowed_classifications
    )
    if unknown_classifications:
        errors.append(
            f"{label}.applies_to.route_classifications contains values outside "
            f"the classification vocabulary: {unknown_classifications}"
        )
    return True


def validate_role_assurance_applies_to(
    role: str,
    requirement: dict[str, Any],
    allowed_classifications: set[str],
    label: str,
    errors: list[str],
) -> None:
    legacy_keys = {"when", "when_scopes", "allowed_classifications"} & set(
        requirement
    )
    if legacy_keys:
        errors.append(
            f"{label} must use one applies_to shape; legacy keys remain: {sorted(legacy_keys)}"
        )

    applies_to = requirement.get("applies_to")
    if not isinstance(applies_to, dict):
        errors.append(f"{label}.applies_to must be a mapping")
        return

    if not validate_role_assurance_classifications(
        applies_to, allowed_classifications, label, errors
    ):
        return

    identity_field = f"{label}.applies_to.route_identities"
    route_identities = applies_to.get("route_identities")
    canonical_route_identities = canonicalize_role_assurance_route_identities(
        route_identities, identity_field, errors
    )
    if role == "platformAdmin":
        if route_identities is None or canonical_route_identities is not None:
            validate_platform_admin_role_assurance_route_identities(
                identity_field, canonical_route_identities, errors
            )
    elif route_identities is not None and canonical_route_identities is not None:
        errors.append(
            f"{identity_field} is only allowed for platformAdmin"
        )


def validate_role_assurance_requirement(
    role: str,
    requirement: Any,
    allowed_classifications: set[str],
    label: str,
    errors: list[str],
) -> None:
    if not isinstance(requirement, dict):
        errors.append(f"{label} must be a mapping")
        return
    validate_role_assurance_applies_to(
        role, requirement, allowed_classifications, label, errors
    )


def validate_role_assurance(document: dict[str, Any], errors: list[str]) -> set[str]:
    raw_assurance = document.get("role_assurance")
    if not isinstance(raw_assurance, dict):
        errors.append("role_assurance must be a mapping")
        return set()
    if not raw_assurance:
        errors.append("role_assurance must be a non-empty mapping")

    predicates: set[str] = set()
    for name, definition in raw_assurance.items():
        if not isinstance(name, str) or not name.strip():
            errors.append("role_assurance keys must be non-empty strings")
            continue
        if name == "vocabulary":
            errors.append(
                "role_assurance must use one canonical predicate mapping; vocabulary is not supported"
            )
            continue
        if not isinstance(definition, dict):
            errors.append(f"role_assurance.{name} must be a mapping")
            continue
        predicates.add(name)
    predicate = raw_assurance.get("privileged_control_when_global_role")
    if not isinstance(predicate, dict):
        errors.append(
            "role_assurance.privileged_control_when_global_role must be a mapping"
        )
        return predicates
    requirements = predicate.get("requirements")
    if not isinstance(requirements, dict):
        errors.append(
            "role_assurance.privileged_control_when_global_role.requirements must be a mapping"
        )
        return predicates
    unexpected_roles = sorted(
        set(requirements) - REQUIRED_ROLE_ASSURANCE_ROLES,
        key=str,
    )
    if unexpected_roles:
        errors.append(
            "role_assurance.privileged_control_when_global_role.requirements "
            f"contains unexpected role keys: {unexpected_roles}"
        )
    raw_classifications = document.get("classifications")
    allowed_classifications = {
        item
        for item in (
            raw_classifications if isinstance(raw_classifications, list) else []
        )
        if isinstance(item, str)
    }
    for role in sorted(REQUIRED_ROLE_ASSURANCE_ROLES):
        label = (
            f"role_assurance.privileged_control_when_global_role.requirements.{role}"
        )
        validate_role_assurance_requirement(
            role,
            requirements.get(role),
            allowed_classifications,
            label,
            errors,
        )
    return predicates


def validate_route_status_vocabulary(
    document: dict[str, Any], errors: list[str]
) -> set[str]:
    vocabulary = document.get("route_status_vocabulary")
    if (
        not isinstance(vocabulary, list)
        or not vocabulary
        or any(not isinstance(item, str) for item in vocabulary)
    ):
        errors.append("route_status_vocabulary must be a non-empty list of strings")
        allowed_statuses: set[str] = set()
    else:
        allowed_statuses = set(vocabulary)
        if len(allowed_statuses) != len(vocabulary):
            errors.append("route_status_vocabulary must not contain duplicates")
        if allowed_statuses != ROUTE_STATUS_VALUES:
            errors.append(
                "route_status_vocabulary must contain exactly "
                f"{sorted(ROUTE_STATUS_VALUES)}"
            )
    return allowed_statuses


def validate_route_statuses(
    routes: list[Any], allowed_statuses: set[str], errors: list[str]
) -> None:
    for index, route in enumerate(routes):
        if not isinstance(route, dict):
            continue
        status = route.get("route_status")
        if status is not None and status not in allowed_statuses:
            errors.append(
                f"routes[{index}] route_status must be one of {sorted(allowed_statuses)}"
            )
        implementation_status = route.get("implementation_status")
        if (
            isinstance(implementation_status, dict)
            and "target_only" in implementation_status
        ):
            errors.append(
                f"routes[{index}] must use route_status instead of implementation_status.target_only"
            )


def validate_required_fields(
    routes: list[Any],
    errors: list[str],
    required_fields_cache: RequiredFieldsCache | None = None,
) -> None:
    for index, route in enumerate(routes):
        if not isinstance(route, dict) or "required_fields" not in route:
            continue
        fields = cached_required_fields(
            route,
            route.get("required_fields"),
            f"routes[{index}] required_fields",
            errors,
            required_fields_cache,
            "required_fields",
        )
        invalid_fields = [
            field for field in fields if not REQUIRED_FIELD_PATTERN.fullmatch(field)
        ]
        if invalid_fields:
            errors.append(
                f"routes[{index}] required_fields must use snake_case: {invalid_fields}"
            )


def _route_class_branch_entries(
    document: dict[str, Any], errors: list[str]
) -> dict[tuple[str, str], dict[str, Any]] | None:
    raw_table = document.get("route_class_branch_table")
    if not isinstance(raw_table, list):
        errors.append("route_class_branch_table must be a list of mappings")
        return None

    actual: dict[tuple[str, str], dict[str, Any]] = {}
    for index, entry in enumerate(raw_table):
        label = f"route_class_branch_table[{index}]"
        if not isinstance(entry, dict):
            errors.append(f"{label} must be a mapping")
            continue
        classification = entry.get("classification")
        branch = entry.get("branch")
        if not isinstance(classification, str):
            errors.append(f"{label}.classification must be a string")
        if not isinstance(branch, str):
            errors.append(f"{label}.branch must be a string")
        if not isinstance(classification, str) or not isinstance(branch, str):
            continue
        key = (classification, branch)
        if key in actual:
            errors.append(f"{label} duplicates route-class branch {key!r}")
        else:
            actual[key] = entry
    return actual


def _validate_route_class_branch_generations(
    label: str,
    generations: Any,
    expected: dict[str, Any],
    errors: list[str],
) -> None:
    if not isinstance(generations, dict):
        errors.append(f"{label}.generations must be a mapping")
        return
    if set(generations) != set(expected["generations"]):
        errors.append(
            f"{label}.generations must declare exactly issuer/account/tenant/membership"
        )
        return
    for generation, expected_value in expected["generations"].items():
        actual_value = generations.get(generation)
        if actual_value not in AUTHORITY_GENERATION_VALUES:
            errors.append(
                f"{label}.generations.{generation} must be one of "
                f"{sorted(AUTHORITY_GENERATION_VALUES)}"
            )
        elif actual_value != expected_value:
            errors.append(
                f"{label}.generations.{generation} must be {expected_value!r}"
            )


def _validate_route_class_branch_entry(
    key: tuple[str, str],
    entry: dict[str, Any],
    expected: dict[str, Any],
    errors: list[str],
) -> None:
    label = f"route_class_branch_table {key[0]} {key[1]}"
    for field in ("scope", "role"):
        if entry.get(field) != expected[field]:
            errors.append(f"{label} must declare {field}={expected[field]!r}")
    privileged_control = entry.get("privileged_control")
    if privileged_control not in PRIVILEGED_CONTROL_VALUES:
        errors.append(
            f"{label}.privileged_control must be one of "
            f"{sorted(PRIVILEGED_CONTROL_VALUES)}"
        )
    elif privileged_control != expected["privileged_control"]:
        errors.append(
            f"{label} must declare "
            f"privileged_control={expected['privileged_control']!r}"
        )
    _validate_route_class_branch_generations(
        label, entry.get("generations"), expected, errors
    )


def validate_route_class_branch_table(
    document: dict[str, Any], errors: list[str]
) -> None:
    actual = _route_class_branch_entries(document, errors)
    if actual is None:
        return

    expected_keys = set(EXPECTED_ROUTE_CLASS_BRANCHES)
    if set(actual) != expected_keys:
        errors.append(
            "route_class_branch_table must contain exactly the canonical route-class "
            f"branches: {sorted(expected_keys)!r}"
        )

    for key, expected in EXPECTED_ROUTE_CLASS_BRANCHES.items():
        entry = actual.get(key)
        if entry is None:
            continue
        _validate_route_class_branch_entry(key, entry, expected, errors)


def validate_membership_policy(
    document: dict[str, Any],
    errors: list[str],
) -> None:
    policy = document.get("tenant_membership_policy")
    if not isinstance(policy, dict):
        errors.append("tenant_membership_policy must be a mapping")
        return
    if policy.get("tenant_owned_writes_require_existing_membership") is not True:
        errors.append(
            "tenant_membership_policy must require existing membership for tenant-owned writes"
        )
    exception = policy.get("public_production_join_exception")
    if not isinstance(exception, dict):
        errors.append(
            "tenant_membership_policy.public_production_join_exception must be a mapping"
        )
        return
    if exception.get("classification") != "public_production_onboarding":
        errors.append(
            "tenant_membership_policy public-production exception must use "
            "public_production_onboarding"
        )
    if exception.get("membership_creation") != "caller_bound_after_validation":
        errors.append(
            "tenant_membership_policy public-production exception must create "
            "caller-bound membership after validation"
        )
    checks = set(
        string_list(
            exception.get("required_pre_membership_checks"),
            "tenant_membership_policy.public_production_join_exception.required_pre_membership_checks",
            errors,
        )
    )
    if checks != REQUIRED_JOIN_PRE_MEMBERSHIP_CHECKS:
        errors.append(
            "tenant_membership_policy public-production exception has the wrong "
            "pre-membership checks"
        )
    raw_routes = exception.get("routes")
    expected_routes = set(JOIN_ROUTES_REQUIRING_POINTER_ERROR)
    actual_routes: set[tuple[str, str]] = set()
    if not isinstance(raw_routes, list):
        errors.append(
            "tenant_membership_policy.public_production_join_exception.routes "
            "must be a list of two-item lists of strings"
        )
    else:
        for index, item in enumerate(raw_routes):
            if (
                not isinstance(item, list)
                or len(item) != 2
                or any(not isinstance(value, str) for value in item)
            ):
                errors.append(
                    "tenant_membership_policy.public_production_join_exception.routes["
                    f"{index}] must be a two-item list of strings"
                )
                continue
            actual_routes.add((item[0], item[1]))
    if actual_routes != expected_routes:
        errors.append(
            "tenant_membership_policy public-production exception must enumerate "
            f"exactly {sorted(expected_routes)!r}"
        )


def validate_authority_evidence_policy(
    document: dict[str, Any], errors: list[str]
) -> None:
    policy = document.get("authority_evidence_policy")
    if not isinstance(policy, dict):
        errors.append("authority_evidence_policy must be a mapping")
        return
    fresh = policy.get("fail_closed_fresh_evidence")
    if not isinstance(fresh, dict):
        errors.append(
            "authority_evidence_policy.fail_closed_fresh_evidence must be a mapping"
        )
    else:
        applies_to = string_list(
            fresh.get("applies_to"),
            "authority_evidence_policy.fail_closed_fresh_evidence.applies_to",
            errors,
        )
        if set(applies_to) != {
            "admission",
            "play",
            "renewal",
            "reconnect",
            "resume",
            "protected_control_plane_mutation",
        }:
            errors.append(
                "authority_evidence_policy fresh evidence must cover admission, PLAY, "
                "renewal, reconnect, resume, and protected control-plane mutations"
            )
        if fresh.get("unreachable_or_timeout") != "AUTH_UNAVAILABLE":
            errors.append(
                "authority_evidence_policy unreachable or timed-out evidence "
                "must fail closed with AUTH_UNAVAILABLE"
            )
        if fresh.get("reachable_invalid_or_ambiguous") != "AUTH_SESSION_REVOKED":
            errors.append(
                "authority_evidence_policy reachable invalid or ambiguous evidence "
                "must fail closed with AUTH_SESSION_REVOKED"
            )
        if fresh.get("route_specific_canonical_errors_precedence") is not True:
            errors.append(
                "authority_evidence_policy fresh evidence must give route-specific "
                "canonical_errors precedence over the AUTH_SESSION_REVOKED fallback"
            )
    bound = policy.get("bound_ordinary_gameplay")
    if not isinstance(bound, dict):
        errors.append(
            "authority_evidence_policy.bound_ordinary_gameplay must be a mapping"
        )
    else:
        if bound.get("applies_to") != "already_bound_instance_runtime":
            errors.append(
                "authority_evidence_policy bound gameplay must be already_bound_instance_runtime"
            )
        if bound.get("pointer_authority_reread") is not False:
            errors.append(
                "authority_evidence_policy bound gameplay must not reread pointer authority"
            )
        required_fences = string_list(
            bound.get("required_fences"),
            "authority_evidence_policy.bound_ordinary_gameplay.required_fences",
            errors,
        )
        if set(required_fences) != {"bound_game_instance", "runtime_fence"}:
            errors.append(
                "authority_evidence_policy bound gameplay must require bound_game_instance and runtime_fence"
            )


def validate_elevation_bootstrap(
    document: dict[str, Any],
    routes: list[Any],
    errors: list[str],
    cardinality_errors: set[str] | None = None,
) -> None:
    contracts = document.get("elevation_contracts")
    privileged = (
        contracts.get("privileged_control") if isinstance(contracts, dict) else None
    )
    bootstrap = (
        privileged.get("bootstrap_exemption") if isinstance(privileged, dict) else None
    )
    expected_route = "account-service/EnterPrivilegedControlWindow"
    if not isinstance(bootstrap, dict):
        errors.append(
            "elevation_contracts.privileged_control.bootstrap_exemption must be a mapping"
        )
    else:
        if bootstrap.get("route") != expected_route:
            errors.append(
                "privileged_control bootstrap exemption must name EnterPrivilegedControlWindow"
            )
        if bootstrap.get("privileged_control") != "establishes_window":
            errors.append(
                "privileged_control bootstrap exemption must establish the window"
            )
        if bootstrap.get("requires_existing_window") is not False:
            errors.append(
                "privileged_control bootstrap exemption must not require an existing window"
            )

    route = resolve_unique_route(
        routes,
        "account-service",
        "EnterPrivilegedControlWindow",
        errors,
        cardinality_errors,
    )
    if route is not None:
        privileged_control = route.get("privileged_control")
        if "privileged_control" not in route or (
            privileged_control in PRIVILEGED_CONTROL_VALUES
            and privileged_control != "establishes_window"
        ):
            errors.append(
                "account-service EnterPrivilegedControlWindow must declare "
                "privileged_control=establishes_window"
            )
    for candidate_route in routes:
        if (
            not isinstance(candidate_route, dict)
            or "privileged_control" not in candidate_route
        ):
            continue
        if candidate_route.get("privileged_control") not in PRIVILEGED_CONTROL_VALUES:
            errors.append(
                f"{candidate_route.get('service')} {candidate_route.get('route')} "
                "privileged_control must be one of "
                f"{sorted(PRIVILEGED_CONTROL_VALUES)}"
            )


def validate_multi_profile_predicates(
    entry: dict[str, Any],
    label: str,
    profiles: list[str],
    token_profiles: dict[str, dict[str, str]],
    errors: list[str],
) -> None:
    if entry.get("token_audience") is not None:
        errors.append(
            f"{label} multi-profile routes must not declare scalar token_audience; "
            "use accepted_token_profile_audiences"
        )
    token_type = entry.get("token_type")
    token_issuer = entry.get("token_issuer")
    known_profiles = [token_profiles.get(profile_name) for profile_name in profiles]
    if not all(profile is not None for profile in known_profiles):
        return
    type_issuer_pairs = {
        (profile.get("type"), profile.get("issuer"))
        for profile in known_profiles
        if profile is not None
    }
    if len(type_issuer_pairs) > 1:
        for field, profile_key in (
            ("accepted_token_profile_types", "type"),
            ("accepted_token_profile_issuers", "issuer"),
        ):
            predicate_map = entry.get(field)
            if not isinstance(predicate_map, dict):
                errors.append(
                    f"{label} differing multi-profile predicates require {field}"
                )
                continue
            if set(predicate_map) != set(profiles):
                errors.append(
                    f"{label} {field} keys must equal accepted token profiles"
                )
                continue
            for profile_name in profiles:
                expected = token_profiles[profile_name].get(profile_key)
                if predicate_map.get(profile_name) != expected:
                    errors.append(
                        f"{label} {field} for {profile_name!r} must match token_profiles"
                    )
        if token_type is not None or token_issuer is not None:
            errors.append(
                f"{label} differing multi-profile predicates must use per-profile type/issuer maps"
            )
        return
    if token_type is None or token_issuer is None:
        errors.append(
            f"{label} multi-profile routes must declare token_type/token_issuer"
        )
        return

    token_predicates = (token_type, token_issuer)
    if token_predicates != next(iter(type_issuer_pairs)):
        errors.append(
            f"{label} multi-profile token predicates must match the shared token_type/token_issuer"
        )


def validate_recovery_export_generation(
    route: dict[str, Any],
    label: str,
    errors: list[str],
    checks: set[str] | None = None,
    live_checks_cache: LiveChecksCache | None = None,
) -> None:
    classification = route.get("classification")
    if not isinstance(classification, str):
        return
    contract = RECOVERY_EXPORT_GENERATION_CONTRACTS.get(classification)
    if contract is None:
        return
    if route.get("accepted_token_profiles") != []:
        errors.append(
            f"{label} {classification} routes must set "
            "accepted_token_profiles=[]"
        )
    credential = contract["credential"]
    if route.get("accepted_credentials") != [credential]:
        errors.append(
            f"{label} {classification} routes must accept only {credential}"
        )
    for field in SECURITY_LOCK_EXPORT_GENERATION_FIELDS:
        if route.get(field) is not False:
            errors.append(
                f"{label} {classification} routes must set "
                f"{field}=false"
            )
    if checks is None:
        checks = route_live_checks(route, label, errors, live_checks_cache)
    missing = (
        REQUIRED_NO_TARGET_TENANT_CLASSIFICATIONS[classification]["required_authority"]
        - checks
    )
    if missing:
        errors.append(
            f"{label} is missing no-target authority checks: {sorted(missing)}"
        )
    action_family_check = contract["action_family_check"]
    required_authority = REQUIRED_NO_TARGET_TENANT_CLASSIFICATIONS[classification][
        "required_authority"
    ]
    if (
        action_family_check not in required_authority
        and action_family_check not in (checks or set())
    ):
        append_unique_error(
            errors,
            f"{label} {route.get('action_family')} must require live check "
            f"{action_family_check}",
        )
    forbidden_checks = checks & {"tenant_generation", "target_tenant_generation"}
    if forbidden_checks:
        errors.append(
            f"{label} must not require tenant-generation checks for "
            f"{classification}: "
            f"{sorted(forbidden_checks)}"
        )
    if (
        route.get("action_family") in RECOVERY_EXPORT_AUDITED_ACTION_FAMILIES
        and route.get("audit_contract")
        != contract["audit_contract"]
    ):
        action_family = route.get("action_family")
        append_unique_error(
            errors,
            f"{label} {action_family} must declare audit_contract "
            f"{contract['audit_contract']}",
        )


def validate_declared_export_binding(
    route: dict[str, Any],
    label: str,
    field: str,
    expected: dict[str, Any],
    errors: list[str],
) -> None:
    if route.get(field) != expected:
        append_unique_error(
            errors,
            f"{label} {field} must declare exactly {expected}",
        )


def validate_export_bindings(routes: list[Any], errors: list[str]) -> None:
    for route in routes:
        if not isinstance(route, dict):
            continue
        classification = route.get("classification")
        action_family = route.get("action_family")
        label = route_label(route)
        if classification == "security_lock_export_scoped":
            operation_bindings = SECURITY_LOCK_EXPORT_OPERATION_BINDINGS
            validate_declared_export_binding(
                route,
                label,
                "credential_binding",
                SECURITY_LOCK_EXPORT_CREDENTIAL_BINDING,
                errors,
            )
        elif classification == "pending_deletion_scoped":
            operation_bindings = PENDING_DELETION_EXPORT_OPERATION_BINDINGS
        elif (
            classification == "account_scoped"
            and route_set_key(route) in ACCOUNT_EXPORT_ROUTE_ACTION_FAMILIES
            and applicability_value(route, "account_state", label, errors) == "active"
        ):
            operation_bindings = ACTIVE_ACCOUNT_EXPORT_OPERATION_BINDINGS
        else:
            continue

        expected_operation_binding = operation_bindings.get(action_family)
        if expected_operation_binding is not None:
            validate_declared_export_binding(
                route,
                label,
                "export_operation_binding",
                expected_operation_binding,
                errors,
            )


def validate_security_lock_export_classification_rule(
    document: dict[str, Any], errors: list[str]
) -> None:
    classification_rules = document.get("classification_rules")
    rule = (
        classification_rules.get("security_lock_export_scoped")
        if isinstance(classification_rules, dict)
        else None
    )
    binding = rule.get("export_operation_binding") if isinstance(rule, dict) else None
    if binding != SECURITY_LOCK_EXPORT_CLASSIFICATION_OPERATION_BINDINGS:
        append_unique_error(
            errors,
            "classification_rules.security_lock_export_scoped."
            "export_operation_binding must declare action-family-specific "
            f"bindings {SECURITY_LOCK_EXPORT_CLASSIFICATION_OPERATION_BINDINGS}",
        )


def validate_account_export_applicability(
    document: dict[str, Any], errors: list[str]
) -> None:
    if document.get("account_state_vocabulary") != ACCOUNT_STATE_VOCABULARY:
        append_unique_error(
            errors,
            "account_state_vocabulary must declare exactly "
            f"{ACCOUNT_STATE_VOCABULARY}",
        )

    applicability = document.get("account_export_applicability")
    if not isinstance(applicability, dict):
        append_unique_error(
            errors,
            "account_export_applicability must be a mapping",
        )
        return
    expected_metadata = {
        "coverage": "exhaustive",
        "overlap": "forbidden",
        "unmatched": "deny",
    }
    for field, expected in expected_metadata.items():
        if applicability.get(field) != expected:
            append_unique_error(
                errors,
                f"account_export_applicability.{field} must be {expected!r}",
            )
    actual_rules = applicability.get("rules")
    expected_rule_keys = sorted(
        json.dumps(rule, sort_keys=True, separators=(",", ":"))
        for rule in ACCOUNT_EXPORT_APPLICABILITY_RULES
    )
    actual_rule_keys = None
    try:
        if isinstance(actual_rules, list):
            actual_rule_keys = sorted(
                json.dumps(rule, sort_keys=True, separators=(",", ":"))
                for rule in actual_rules
            )
    except (TypeError, ValueError):
        actual_rule_keys = None
    if actual_rule_keys != expected_rule_keys:
        append_unique_error(
            errors,
            "account_export_applicability.rules must declare exactly the canonical "
            "export initiation, status, and content account-state rules",
        )


def validate_recovery_export_action_families(
    routes: list[Any],
    classification: str,
    expected_action_families: set[str],
    errors: list[str],
) -> list[Any]:
    action_families = [
        route.get("action_family")
        for route in routes
        if isinstance(route, dict)
        and route.get("classification") == classification
        and route_set_key(route) in ACCOUNT_EXPORT_ROUTE_ACTION_FAMILIES
    ]
    declared_action_families = {
        action_family
        for action_family in action_families
        if isinstance(action_family, str)
    }
    if (
        len(action_families) != len(expected_action_families)
        or len(declared_action_families) != len(action_families)
        or declared_action_families != expected_action_families
    ):
        append_unique_error(
            errors,
            f"{classification} routes must declare exactly action_family "
            f"set {sorted(expected_action_families)}",
        )
    return action_families


def validate_account_export_routes(
    routes: list[Any],
    errors: list[str],
    live_checks_cache: LiveChecksCache | None = None,
) -> None:
    matched_route = False
    observed_branches: dict[tuple[str, str], list[tuple[str, str]]] = {
        identity: [] for identity in ACCOUNT_EXPORT_ROUTE_ACTION_FAMILIES
    }
    for index, route in enumerate(routes):
        if not isinstance(route, dict):
            continue
        identity = route_set_key(route)
        expected_action_family = ACCOUNT_EXPORT_ROUTE_ACTION_FAMILIES.get(identity)
        if expected_action_family is None:
            continue
        if identity == EXPORT_INITIATION_ROUTE_IDENTITY:
            matched_route = True
        action_family = route.get("action_family")
        label = route_label(route)
        if action_family != expected_action_family:
            append_unique_error(
                errors,
                f"{label} must declare action_family {expected_action_family}",
            )
        account_state = applicability_value(route, "account_state", label, errors)
        expected_classification = ACCOUNT_EXPORT_BRANCH_CLASSIFICATIONS.get(
            account_state
        )
        if expected_classification is None:
            append_unique_error(
                errors,
                f"{label} must declare one of the canonical account export states: "
                f"{sorted(ACCOUNT_EXPORT_BRANCH_CLASSIFICATIONS)}",
            )
        elif route.get("classification") != expected_classification:
            append_unique_error(
                errors,
                f"{label} account_state={account_state!r} must use classification "
                f"{expected_classification}",
            )
        if isinstance(account_state, str) and isinstance(
            route.get("classification"), str
        ):
            observed_branches[identity].append(
                (account_state, route["classification"])
            )
        if (
            identity == EXPORT_INITIATION_ROUTE_IDENTITY
            and route.get("classification") == "account_scoped"
        ):
            validate_applicability(
                route, label, {"account_state": "active"}, errors
            )
        checks = None
        if (
            identity == EXPORT_INITIATION_ROUTE_IDENTITY
            or account_state == "active"
            or expected_action_family == "export_content"
        ):
            checks = route_live_checks(route, label, errors, live_checks_cache)
        if (
            account_state == "active"
            and expected_action_family in RECOVERY_EXPORT_AUDITED_ACTION_FAMILIES
            and route.get("audit_contract")
            != ACTIVE_ACCOUNT_EXPORT_CONTENT_AUDIT_CONTRACT
        ):
            audit_label = f"routes[{index}] {label}"
            if expected_action_family == "export_content":
                audit_error = (
                    f"{audit_label} active account export_content must declare audit_contract "
                    f"{ACTIVE_ACCOUNT_EXPORT_CONTENT_AUDIT_CONTRACT}"
                )
            else:
                audit_error = (
                    f"{audit_label} active account {expected_action_family} must declare "
                    f"audit_contract {ACTIVE_ACCOUNT_EXPORT_CONTENT_AUDIT_CONTRACT}"
                )
            append_unique_error(errors, audit_error)
        if identity == EXPORT_INITIATION_ROUTE_IDENTITY:
            required_checks = EXPORT_INITIATION_REQUIRED_LIVE_CHECKS[
                expected_action_family
            ]
            for required_check in sorted(required_checks - (checks or set())):
                append_unique_error(
                    errors,
                    f"{label} {expected_action_family} must require live check "
                    f"{required_check}",
                )
            canonical_errors = route.get("canonical_errors")
            outcomes = (
                canonical_errors.get("any_of")
                if isinstance(canonical_errors, dict)
                else None
            )
            if not isinstance(outcomes, list) or any(
                not isinstance(outcome, str) for outcome in outcomes
            ):
                outcomes = []
            for required_error in sorted(
                EXPORT_INITIATION_REQUIRED_CANONICAL_ERRORS
            ):
                if required_error not in outcomes:
                    append_unique_error(
                        errors,
                        f"{label} {expected_action_family} must declare "
                        f"{required_error}",
                    )
        if expected_action_family == "export_content":
            required_content_checks = set(EXPORT_CONTENT_REQUIRED_LIVE_CHECKS)
            if account_state != "active":
                required_content_checks.discard("recent_account_authentication")
            for required_check in sorted(required_content_checks - (checks or set())):
                append_unique_error(
                    errors,
                    f"{label} export_content must require live check "
                    f"{required_check}",
                )
        if account_state == "active":
            for required_check in sorted(
                ACTIVE_ACCOUNT_EXPORT_REQUIRED_LIVE_CHECKS - (checks or set())
            ):
                append_unique_error(
                    errors,
                    f"{label} {expected_action_family} must require live check "
                    f"{required_check}",
                )
            active_export_name = expected_action_family.removeprefix("export_")
            if route.get("subject_binding") != "caller_account_id":
                append_unique_error(
                    errors,
                    f"{label} active export {active_export_name} must bind to "
                    "caller_account_id",
                )
            if route.get("platform_admin_override") != "forbidden":
                append_unique_error(
                    errors,
                    f"{label} active export {active_export_name} must declare "
                    "platform_admin_override forbidden",
                )
            if "account_authorization_branches" in route:
                append_unique_error(
                    errors,
                    f"{label} active export {active_export_name} must not declare "
                    "account_authorization_branches",
                )
    for identity in ACCOUNT_EXPORT_ROUTE_ACTION_FAMILIES:
        if (
            len(observed_branches[identity]) != len(ACCOUNT_EXPORT_BRANCHES)
            or set(observed_branches[identity]) != ACCOUNT_EXPORT_BRANCHES
        ):
            append_unique_error(
                errors,
                f"{identity[0]} {identity[1]} must declare exactly the mutually "
                f"exclusive account export branches: {sorted(ACCOUNT_EXPORT_BRANCHES)}",
            )
    if not matched_route:
        append_unique_error(
            errors,
            "matrix must contain normalized export-initiation route identity "
            f"{EXPORT_INITIATION_ROUTE_IDENTITY[0]}/{EXPORT_INITIATION_ROUTE_IDENTITY[1]}",
        )
    for route in matching_routes(
        routes, LEGACY_EXPORT_ROUTE_IDENTITY[0], LEGACY_EXPORT_ROUTE_IDENTITY[1]
    ):
        label = route_label(route)
        if route.get("route_status") != "current_openapi_operator_surface":
            append_unique_error(
                errors,
                f"{label} legacy export route must declare route_status "
                "current_openapi_operator_surface",
            )
        if route.get("canonical_target_route") != EXPORT_INITIATION_ROUTE:
            append_unique_error(
                errors,
                f"{label} legacy export route must point to canonical target "
                f"{EXPORT_INITIATION_ROUTE}",
            )

    validate_recovery_export_action_families(
        routes, "security_lock_export_scoped", SECURITY_LOCK_EXPORT_ACTION_FAMILIES, errors
    )
    pending_deletion_action_families = validate_recovery_export_action_families(
        routes,
        "pending_deletion_scoped",
        PENDING_DELETION_EXPORT_ACTION_FAMILIES,
        errors,
    )
    declared_pending_action_families = [
        action_family
        for action_family in pending_deletion_action_families
        if isinstance(action_family, str)
    ]
    if len(declared_pending_action_families) != len(
        set(declared_pending_action_families)
    ):
        append_unique_error(
            errors,
            "pending_deletion_scoped account export routes must declare unique "
            "action_family values",
        )


def validate_character_routes(
    routes: list[Any],
    errors: list[str],
    cardinality_errors: set[str] | None = None,
) -> None:
    for (service, route_name), expected_contract in CHARACTER_ROUTE_CONTRACTS.items():
        route = resolve_unique_route(
            routes,
            service,
            route_name,
            errors,
            cardinality_errors,
        )
        if route is None:
            continue
        label = route_label(route)
        for field, expected_value in expected_contract.items():
            if route.get(field) != expected_value:
                append_unique_error(
                    errors,
                    f"{label} must declare {field}={expected_value}",
                )


def validate_selected_character_requirement_vocabulary(
    document: dict[str, Any], errors: list[str]
) -> None:
    if document.get("selected_character_requirement_vocabulary") != (
        SELECTED_CHARACTER_REQUIREMENT_VOCABULARY
    ):
        append_unique_error(
            errors,
            "selected_character_requirement_vocabulary must declare exactly "
            f"{SELECTED_CHARACTER_REQUIREMENT_VOCABULARY}",
        )


def validate_character_operation_vocabulary(
    document: dict[str, Any], errors: list[str]
) -> None:
    if document.get("character_operation_vocabulary") != (
        CHARACTER_OPERATION_VOCABULARY
    ):
        append_unique_error(
            errors,
            "character_operation_vocabulary must declare exactly "
            f"{CHARACTER_OPERATION_VOCABULARY}",
        )


def validate_read_only_reporting_routes(
    routes: list[Any], errors: list[str]
) -> None:
    for route in routes:
        if not isinstance(route, dict) or route.get("response_profile") != "billing_reporting":
            continue
        label = route_label(route)
        for field in ("mutation_contract", "provider_instrument_contract"):
            if field in route:
                append_unique_error(
                    errors,
                    f"{label} billing_reporting route must not declare {field}",
                )


def validate_capacity_admission_wire_contract(
    routes: list[Any],
    errors: list[str],
    cardinality_errors: set[str] | None = None,
) -> None:
    service, route_name = CAPACITY_ADMISSION_ROUTE
    route = resolve_unique_route(
        routes,
        service,
        route_name,
        errors,
        cardinality_errors,
    )
    if route is None:
        return
    label = route_label(route)
    contract = route.get("capacity_delta_wire_contract")
    present_zero_wire_type = None
    present_zero_wire_value = None
    if isinstance(contract, dict):
        golden_vectors = contract.get("golden_vectors")
        if isinstance(golden_vectors, dict):
            present_zero = golden_vectors.get("present_zero")
            if isinstance(present_zero, dict):
                present_zero_wire_type = present_zero.get("wire_value_type")
                present_zero_wire_value = present_zero.get("wire_value")
    # Check int and bool explicitly because Python treats False as equal to 0.
    if (
        contract != CAPACITY_DELTA_WIRE_CONTRACT
        or present_zero_wire_type != "integer"
        or not isinstance(present_zero_wire_value, int)
        or isinstance(present_zero_wire_value, bool)
        or present_zero_wire_value != 0
    ):
        append_unique_error(
            errors,
            f"{label} must declare explicit capacityDelta wire presence with "
            "distinct absent and present_zero golden vectors",
        )


def validate_membership_generation(
    route: dict[str, Any], label: str, errors: list[str]
) -> Any:
    value = route.get("membership_authority_generation_applies")
    valid_scalar = isinstance(value, (bool, str)) and (
        value in MEMBERSHIP_GENERATION_APPLICABILITY_VALUES
    )
    if value is not None and not valid_scalar:
        errors.append(
            f"{label} membership_authority_generation_applies must be one of "
            f"{sorted(MEMBERSHIP_GENERATION_APPLICABILITY_VALUES, key=str)}"
        )
    condition = route.get("membership_authority_generation_condition")
    if value == "conditional_by_operator_role":
        if condition != CONDITIONAL_OPERATOR_MEMBERSHIP_SHAPE:
            errors.append(
                f"{label} conditional membership generation requires "
                "tenant_role=true and platformAdmin_global=false"
            )
    elif condition is not None:
        errors.append(
            f"{label} declares membership_authority_generation_condition without "
            "a conditional membership-generation mode"
        )
    return value


def operator_authorization_branch_checks(
    route: dict[str, Any],
    label: str,
    errors: list[str],
    live_checks_cache: LiveChecksCache | None = None,
    expected_branches: dict[str, set[str]] | None = None,
) -> dict[str, list[set[str]]]:
    raw_branches = route.get("operator_authorization_branches")
    if not isinstance(raw_branches, list):
        errors.append(
            f"{label} operator_authorization_branches must be a list of mappings"
        )
        return {}

    branches: dict[str, list[set[str]]] = {}
    for index, raw_branch in enumerate(raw_branches):
        branch_label = f"{label} operator_authorization_branches[{index}]"
        if not isinstance(raw_branch, dict):
            errors.append(f"{branch_label} must be a mapping")
            continue
        branch = raw_branch.get("branch")
        if not isinstance(branch, str) or not branch.strip():
            errors.append(f"{branch_label}.branch must be a non-empty string")
            continue
        expected_branch_checks = expected_branches or OPERATOR_AUTHORIZATION_BRANCHES
        if branch not in expected_branch_checks:
            errors.append(
                f"{branch_label}.branch must be one of "
                f"{sorted(expected_branch_checks)}"
            )
        if branch in branches:
            errors.append(f"{branch_label} duplicates operator branch {branch!r}")
        checks = cached_live_checks(
            raw_branch,
            raw_branch.get("required_live_checks"),
            f"{branch_label}.required_live_checks",
            errors,
            live_checks_cache,
            "required_live_checks",
        )
        expected_checks = expected_branch_checks.get(branch)
        if expected_checks is not None and checks != expected_checks:
            errors.append(
                f"{branch_label}.required_live_checks must equal "
                f"{sorted(expected_checks)}"
            )
        branches.setdefault(branch, []).append(checks)

    expected_branch_names = set(expected_branches or OPERATOR_AUTHORIZATION_BRANCHES)
    if set(branches) != expected_branch_names:
        errors.append(
            f"{label} operator_authorization_branches must contain exactly "
            f"{sorted(expected_branch_names)}"
        )
    return branches


def _validate_conditional_operator_branch_checks(
    label: str,
    checks: set[str],
    branch_checks: dict[str, list[set[str]]],
    errors: list[str],
) -> None:
    tenant_branch_checks = set().union(*branch_checks.get("tenant_role", []))
    platform_admin_branch_checks = set().union(
        *branch_checks.get("platformAdmin_global", [])
    )
    branch_only_checks = set().union(*OPERATOR_AUTHORIZATION_BRANCHES.values())
    duplicated_checks = sorted(checks & branch_only_checks)
    if duplicated_checks:
        errors.append(
            f"{label} required_live_checks must not duplicate branch-qualified "
            f"checks: {duplicated_checks}"
        )
    if "membership_when_tenant_role" not in tenant_branch_checks:
        errors.append(
            f"{label} tenant-role branch must require membership_when_tenant_role"
        )
    if "membership_generation" not in tenant_branch_checks:
        errors.append(f"{label} tenant-role branch must require membership_generation")
    if "tenant_generation" not in tenant_branch_checks:
        errors.append(f"{label} operator route must require tenant_generation")
    if "target_tenant_generation" not in platform_admin_branch_checks:
        errors.append(f"{label} operator route must require target_tenant_generation")


def _validate_conditional_operator_platform_checks(
    label: str,
    branch_checks: dict[str, list[set[str]]],
    errors: list[str],
) -> None:
    platform_admin_branch_checks = set().union(
        *branch_checks.get("platformAdmin_global", [])
    )
    for required_check in ("current_global_role", "role_appropriate_assurance"):
        if required_check not in platform_admin_branch_checks:
            errors.append(
                f"{label} privileged operator route must require live check "
                f"{required_check}"
            )


def _validate_conditional_operator_route_metadata(
    route: dict[str, Any],
    label: str,
    checks: set[str],
    errors: list[str],
) -> None:
    if "current_operator_authorization" not in checks:
        errors.append(
            f"{label} operator route must require live check "
            "current_operator_authorization"
        )
    if (
        route_set_key(route) in ADMISSION_POINTER_MUTATION_ROUTES
        and "expected_pointer_version" not in checks
    ):
        errors.append(
            f"{label} admission-pointer mutation must require live check "
            "expected_pointer_version"
        )
    if route_set_key(route) in GAME_SESSION_OPERATOR_ROUTES:
        if route.get("canonical_external_ingress") != CANONICAL_OPERATOR_INGRESS:
            errors.append(
                f"{label} must declare canonical_external_ingress {CANONICAL_OPERATOR_INGRESS}"
            )
        if route.get("direct_owner_route_policy") != DIRECT_OWNER_ROUTE_POLICY:
            errors.append(
                f"{label} must declare direct_owner_route_policy {DIRECT_OWNER_ROUTE_POLICY}"
            )


def validate_conditional_operator_route(
    route: dict[str, Any],
    label: str,
    value: Any,
    errors: list[str],
    checks: set[str] | None = None,
    live_checks_cache: LiveChecksCache | None = None,
    branch_checks: dict[str, list[set[str]]] | None = None,
) -> None:
    route_key_value = route_set_key(route)
    if route_key_value not in CONDITIONAL_OPERATOR_ROUTES:
        return
    action_category = applicability_value(route, "action_category", label, errors)
    if (
        route_key_value == MODERATION_ACTION_ROUTE
        and action_category == MODERATION_PLATFORM_ACTION_CATEGORY
    ):
        # Dedicated moderation validators re-enforce authorization, role,
        # platform-admin, generation-flag, and forbidden-tenant requirements.
        return
    if value != "conditional_by_operator_role":
        errors.append(
            f"{label} operator ingress must use conditional_by_operator_role "
            "membership generation"
        )
    if route.get("global_platform_admin_membership_required") is not False:
        errors.append(
            f"{label} must set global_platform_admin_membership_required=false"
        )
    if checks is None:
        checks = route_live_checks(route, label, errors, live_checks_cache)
    if branch_checks is None:
        branch_checks = operator_authorization_branch_checks(
            route, label, errors, live_checks_cache
        )
    _validate_conditional_operator_branch_checks(
        label, checks, branch_checks, errors
    )
    if (
        route.get("global_platform_admin_reference_generation_binding")
        != "target_tenant_generation"
    ):
        errors.append(
            f"{label} must bind global platformAdmin operations to target_tenant_generation"
        )
    if route.get("role_assurance") != PRIVILEGED_OPERATOR_ROLE_ASSURANCE:
        errors.append(
            f"{label} operator route must declare role_assurance "
            f"{PRIVILEGED_OPERATOR_ROLE_ASSURANCE}"
        )
    _validate_conditional_operator_platform_checks(label, branch_checks, errors)
    _validate_conditional_operator_route_metadata(route, label, checks, errors)


def account_authorization_branch_checks(
    route: dict[str, Any],
    label: str,
    errors: list[str],
    live_checks_cache: LiveChecksCache | None = None,
) -> dict[str, list[set[str]]]:
    raw_branches = route.get("account_authorization_branches")
    if not isinstance(raw_branches, list):
        errors.append(
            f"{label} account_authorization_branches must be a list of mappings"
        )
        return {}

    branches: dict[str, list[set[str]]] = {}
    for index, raw_branch in enumerate(raw_branches):
        branch_label = f"{label} account_authorization_branches[{index}]"
        if not isinstance(raw_branch, dict):
            errors.append(f"{branch_label} must be a mapping")
            continue
        branch = raw_branch.get("branch")
        if not isinstance(branch, str) or not branch.strip():
            errors.append(f"{branch_label}.branch must be a non-empty string")
            continue
        if branch not in ACCOUNT_AUTHORIZATION_BRANCHES:
            errors.append(
                f"{branch_label}.branch must be one of "
                f"{sorted(ACCOUNT_AUTHORIZATION_BRANCHES)}"
            )
        if branch in branches:
            errors.append(f"{branch_label} duplicates account branch {branch!r}")

        expected = ACCOUNT_AUTHORIZATION_BRANCHES.get(branch)
        target_subject_binding = raw_branch.get("target_subject_binding")
        if (
            expected is not None
            and target_subject_binding != expected["target_subject_binding"]
        ):
            errors.append(
                f"{branch_label} ({branch}).target_subject_binding must be "
                f"{expected['target_subject_binding']!r}"
            )
        checks = cached_live_checks(
            raw_branch,
            raw_branch.get("required_live_checks"),
            f"{branch_label}.required_live_checks",
            errors,
            live_checks_cache,
            "required_live_checks",
        )
        if expected is not None and checks != expected["required_live_checks"]:
            errors.append(
                f"{branch_label} ({branch}).required_live_checks must equal "
                f"{sorted(expected['required_live_checks'])}"
            )
        branches.setdefault(branch, []).append(checks)

    expected_branches = set(ACCOUNT_AUTHORIZATION_BRANCHES)
    if set(branches) != expected_branches:
        errors.append(
            f"{label} account_authorization_branches must contain exactly "
            f"{sorted(expected_branches)}"
        )
    return branches


def validate_account_authorization_route(
    route: dict[str, Any],
    label: str,
    errors: list[str],
    checks: set[str] | None = None,
    live_checks_cache: LiveChecksCache | None = None,
) -> None:
    route_key_value = route_set_key(route)
    if route_key_value not in ACCOUNT_SUBJECT_BOUND_ROUTES:
        return
    if route.get("subject_binding") != "caller_account_id":
        errors.append(
            f"{label} account routes must bind self-service to caller_account_id"
        )
    if route.get("platform_admin_override") != "platformAdmin_only":
        errors.append(
            f"{label} account routes must declare platform_admin_override platformAdmin_only"
        )
    if route.get("role_assurance") != PRIVILEGED_OPERATOR_ROLE_ASSURANCE:
        errors.append(
            f"{label} account routes must declare role_assurance "
            f"{PRIVILEGED_OPERATOR_ROLE_ASSURANCE}"
        )
    if checks is None:
        checks = route_live_checks(route, label, errors, live_checks_cache)
    branch_checks = account_authorization_branch_checks(
        route, label, errors, live_checks_cache
    )
    branch_only_checks = set().union(
        *(
            branch["required_live_checks"]
            for branch in ACCOUNT_AUTHORIZATION_BRANCHES.values()
        )
    )
    duplicated_checks = sorted(checks & branch_only_checks)
    if duplicated_checks:
        errors.append(
            f"{label} required_live_checks must not duplicate branch-qualified "
            f"checks: {duplicated_checks}"
        )
    for branch_name, expected in ACCOUNT_AUTHORIZATION_BRANCHES.items():
        actual = set().union(*branch_checks.get(branch_name, []))
        missing = sorted(expected["required_live_checks"] - actual)
        if missing:
            errors.append(
                f"{label} {branch_name} branch is missing required live checks: "
                f"{missing}"
            )


def validate_token_fields(
    entry: dict[str, Any],
    label: str,
    profiles: list[str],
    token_profiles: dict[str, dict[str, str]],
    errors: list[str],
    reported_unknown_profiles: set[str] | None = None,
    allow_multi_profile: bool = False,
    allow_omitted_no_profile_predicates: bool = False,
) -> None:
    predicate_default = "none" if allow_omitted_no_profile_predicates else None
    token_predicates = (
        entry.get("token_type", predicate_default),
        entry.get("token_issuer", predicate_default),
        entry.get("token_audience", predicate_default),
    )
    if not profiles:
        if token_predicates != ("none", "none", "none"):
            errors.append(
                f"{label} must declare token_type/token_issuer/token_audience as none"
            )
        return
    if len(profiles) != 1:
        if allow_multi_profile and len(profiles) > 1:
            validate_multi_profile_predicates(
                entry,
                label,
                profiles,
                token_profiles,
                errors,
            )
            return
        errors.append(
            f"{label} must declare exactly one token profile per receiver policy"
        )
        return
    profile = token_profiles.get(profiles[0])
    if profile is None:
        if (
            reported_unknown_profiles is None
            or profiles[0] not in reported_unknown_profiles
        ):
            errors.append(f"{label} uses unknown token profiles: {[profiles[0]]}")
        return
    expected = (profile.get("type"), profile.get("issuer"), profile.get("audience"))
    if token_predicates != expected:
        errors.append(
            f"{label} token predicates must exactly match profile {profiles[0]!r}"
        )


def validate_route_profile_declaration(
    route: dict[str, Any],
    index: int,
    token_profiles: dict[str, dict[str, str]],
    errors: list[str],
) -> tuple[Any, list[str], list[str]]:
    profiles_value = route.get("accepted_token_profiles")
    if profiles_value is None:
        return (None, [], [])
    label = f"matrix.routes[{index}]"
    profiles = string_list(profiles_value, f"{label} accepted_token_profiles", errors)
    unknown_profiles = sorted(set(profiles) - set(token_profiles))
    if unknown_profiles:
        errors.append(f"{label} uses unknown token profiles: {unknown_profiles}")
    audience_map = route.get("accepted_token_profile_audiences")
    if len(profiles) > 1 and not isinstance(audience_map, dict):
        errors.append(
            f"{label} multi-profile receiver requires accepted_token_profile_audiences"
        )
    if isinstance(audience_map, dict):
        if set(audience_map) != set(profiles):
            errors.append(
                f"{label} accepted token audience keys must equal accepted profiles"
            )
        for profile_name in profiles:
            expected_audience = token_profiles.get(profile_name, {}).get("audience")
            if audience_map.get(profile_name) != expected_audience:
                errors.append(
                    f"{label} audience for {profile_name!r} must match token_profiles"
                )
    return (profiles_value, profiles, unknown_profiles)


def validate_caller_policies(
    caller_policies: Any,
    label: str,
    token_profiles: dict[str, dict[str, str]],
    errors: list[str],
) -> None:
    if not isinstance(caller_policies, list) or not caller_policies:
        errors.append(f"{label} caller_policies must be a non-empty list")
        return
    for policy_index, policy in enumerate(caller_policies):
        policy_label = f"{label} caller_policies[{policy_index}]"
        if not isinstance(policy, dict):
            errors.append(f"{policy_label} must be a mapping")
            continue
        caller = policy.get("caller")
        if not isinstance(caller, str) or not caller.strip():
            errors.append(f"{policy_label}.caller must be a non-empty string")
        mtls_identity = policy.get("mtls_identity")
        if (
            not isinstance(mtls_identity, str)
            or not mtls_identity.startswith("spiffe://")
            or "/sa/" not in mtls_identity
        ):
            errors.append(
                f"{policy_label}.mtls_identity must be a concrete spiffe:// identity"
            )
        if policy.get("method_policy") != "exact_declared_route":
            errors.append(
                f"{policy_label} must declare method_policy exact_declared_route"
            )
        policy_profiles_value = policy.get("accepted_token_profiles")
        policy_profiles = string_list(
            policy_profiles_value,
            f"{policy_label} accepted_token_profiles",
            errors,
        )
        if isinstance(policy_profiles_value, list) and all(
            isinstance(profile_name, str) for profile_name in policy_profiles_value
        ):
            validate_token_fields(
                policy, policy_label, policy_profiles, token_profiles, errors
            )


def validate_internal_route_callers(
    route: dict[str, Any],
    label: str,
    profiles_value: Any,
    profiles: list[str],
    unknown_profiles: list[str],
    token_profiles: dict[str, dict[str, str]],
    errors: list[str],
) -> None:
    allowed_callers = route.get("allowed_callers")
    mtls_callers = route.get("mtls_callers")
    for field, value in (
        ("allowed_callers", allowed_callers),
        ("mtls_callers", mtls_callers),
    ):
        values = value.get("any_of") if isinstance(value, dict) else None
        if (
            not isinstance(values, list)
            or not values
            or any(not isinstance(item, str) or not item.strip() for item in values)
        ):
            errors.append(f"{label} {field}.any_of must be a non-empty list of strings")
    mtls_values = mtls_callers.get("any_of") if isinstance(mtls_callers, dict) else None
    mtls_values_valid = (
        isinstance(mtls_values, list)
        and bool(mtls_values)
        and all(isinstance(item, str) and item.strip() for item in mtls_values)
    )
    if mtls_values_valid and any(
        not item.startswith("spiffe://") or "/sa/" not in item for item in mtls_values
    ):
        errors.append(
            f"{label} mtls_callers.any_of must contain concrete spiffe:// identities"
        )
    if route.get("method_policy") != "exact_declared_route":
        errors.append(f"{label} must declare method_policy exact_declared_route")
    if profiles_value is None or (
        isinstance(profiles_value, list)
        and all(isinstance(profile_name, str) for profile_name in profiles_value)
    ):
        validate_token_fields(
            route,
            label,
            profiles,
            token_profiles,
            errors,
            set(unknown_profiles),
            allow_multi_profile=True,
        )


def validate_receiver_predicates(
    routes: list[Any], token_profiles: dict[str, dict[str, str]], errors: list[str]
) -> None:
    for index, route in enumerate(routes):
        if not isinstance(route, dict):
            continue
        profiles_value, profiles, unknown_profiles = validate_route_profile_declaration(
            route, index, token_profiles, errors
        )
        if route.get("classification") != "internal_workload":
            if profiles_value is None or (
                isinstance(profiles_value, list)
                and all(
                    isinstance(profile_name, str)
                    for profile_name in profiles_value
                )
            ):
                validate_token_fields(
                    route,
                    f"matrix.routes[{index}]",
                    profiles,
                    token_profiles,
                    errors,
                    set(unknown_profiles),
                    allow_multi_profile=True,
                    allow_omitted_no_profile_predicates=profiles_value is None,
                )
            continue
        label = route_label(route)
        caller_policies = route.get("caller_policies")
        if caller_policies is not None:
            validate_caller_policies(caller_policies, label, token_profiles, errors)
            continue
        validate_internal_route_callers(
            route,
            label,
            profiles_value,
            profiles,
            unknown_profiles,
            token_profiles,
            errors,
        )


def validate_explicit_no_jwt_routes(routes: list[Any], errors: list[str]) -> None:
    for route in routes:
        if not isinstance(route, dict):
            continue
        key = route_set_key(route)
        if key not in EXPLICIT_NO_JWT_ROUTES:
            continue
        label = route_label(route)
        if route.get("accepted_token_profiles") != []:
            errors.append(f"{label} must explicitly declare accepted_token_profiles=[]")
        for field in ("token_type", "token_issuer", "token_audience"):
            if route.get(field) != "none":
                errors.append(f"{label} must explicitly declare {field}=none")


def validate_tenant_generation_allowlist(
    policy: dict[str, Any], errors: list[str]
) -> None:
    allowlist = policy.get("exception_allowlist")
    if not isinstance(allowlist, dict):
        errors.append("tenant_generation_policy.exception_allowlist must be a mapping")
        return
    if set(allowlist) != set(REQUIRED_TENANT_GENERATION_EXCEPTIONS):
        errors.append(
            "tenant_generation_policy.exception_allowlist must be exactly "
            "the closed route-class allowlist"
        )
    for classification, expected in REQUIRED_TENANT_GENERATION_EXCEPTIONS.items():
        entry = allowlist.get(classification)
        if not isinstance(entry, dict):
            errors.append(
                "tenant_generation_policy.exception_allowlist."
                f"{classification} must be a mapping"
            )
            continue
        if (
            entry.get("target_tenant_generation")
            is not expected["target_tenant_generation"]
        ):
            errors.append(
                f"tenant_generation_policy exception {classification} "
                "has the wrong target generation setting"
            )
        required_checks = set(
            string_list(
                entry.get("required_authority"),
                "tenant_generation_policy.exception_allowlist."
                f"{classification}.required_authority",
                errors,
            )
        )
        if required_checks != expected["required_live_checks"]:
            errors.append(
                f"tenant_generation_policy exception {classification} "
                "has the wrong required authority checks"
            )
        if (
            classification
            in (
                "cross_tenant_support_safe",
                "cross_tenant_billing_safe",
            )
            and entry.get("role_assurance_policy") != PRIVILEGED_OPERATOR_ROLE_ASSURANCE
        ):
            errors.append(
                f"tenant_generation_policy exception {classification} "
                f"must reference {PRIVILEGED_OPERATOR_ROLE_ASSURANCE}"
            )


def validate_no_target_tenant_classifications(
    policy: dict[str, Any], errors: list[str]
) -> None:
    classifications = policy.get("no_target_tenant_classifications")
    if not isinstance(classifications, dict):
        errors.append(
            "tenant_generation_policy.no_target_tenant_classifications must be a mapping"
        )
        return
    if set(classifications) != set(REQUIRED_NO_TARGET_TENANT_CLASSIFICATIONS):
        errors.append(
            "tenant_generation_policy.no_target_tenant_classifications must be exactly "
            "the closed no-target classification set"
        )
    for classification, expected in REQUIRED_NO_TARGET_TENANT_CLASSIFICATIONS.items():
        entry = classifications.get(classification)
        label = (
            "tenant_generation_policy.no_target_tenant_classifications."
            f"{classification}"
        )
        if not isinstance(entry, dict):
            errors.append(f"{label} must be a mapping")
            continue
        if (
            entry.get("target_tenant_generation")
            is not expected["target_tenant_generation"]
        ):
            errors.append(f"{label} must set target_tenant_generation=false")
        if entry.get("generation_behavior") != expected["generation_behavior"]:
            errors.append(f"{label} has the wrong generation_behavior")
        required_authority = set(
            string_list(
                entry.get("required_authority"), f"{label}.required_authority", errors
            )
        )
        if required_authority != expected["required_authority"]:
            errors.append(f"{label} has the wrong required authority metadata")
        justification = entry.get("contract_justification")
        if not isinstance(justification, str) or not justification.strip():
            errors.append(f"{label} must declare a bounded contract_justification")
        if (
            entry.get("target_tenant_generation_advance_behavior")
            != expected["target_tenant_generation_advance_behavior"]
        ):
            errors.append(
                f"{label} has the wrong target_tenant_generation_advance_behavior"
            )
        if classification == "pending_deletion_scoped":
            proof_contract = entry.get("negative_proof")
            proof = (
                proof_contract.get("required")
                if isinstance(proof_contract, dict)
                else None
            )
            if (
                not isinstance(proof, list)
                or not proof
                or any(not isinstance(item, str) or not item.strip() for item in proof)
            ):
                errors.append(f"{label} must declare non-empty negative_proof.required")
        elif classification == "security_lock_export_scoped":
            proof_contract = entry.get("negative_proof")
            proof = (
                proof_contract.get("required")
                if isinstance(proof_contract, dict)
                else None
            )
            if (
                not isinstance(proof, list)
                or any(not isinstance(item, str) for item in proof)
                or set(proof) != SECURITY_LOCK_EXPORT_NEGATIVE_PROOF
                or len(proof) != len(SECURITY_LOCK_EXPORT_NEGATIVE_PROOF)
            ):
                errors.append(
                    f"{label} must declare exactly the bounded security-lock export "
                    "negative proof requirements"
                )


def validate_tenant_generation_negative_proof(
    policy: dict[str, Any], errors: list[str]
) -> None:
    proof = policy.get("negative_proof")
    label = "tenant_generation_policy.negative_proof"
    if not isinstance(proof, dict):
        errors.append(f"{label} must be a mapping")
        return
    classifications = set(
        string_list(
            proof.get("tenant_authority_classifications"),
            f"{label}.tenant_authority_classifications",
            errors,
        )
    )
    if classifications != REQUIRED_TENANT_AUTHORITY_CLASSIFICATIONS:
        errors.append(
            f"{label}.tenant_authority_classifications must be exactly "
            "the closed tenant-authority classification set"
        )
    required = set(string_list(proof.get("required"), f"{label}.required", errors))
    if "non_allowlisted_route_denied_after_target_tenant_generation_advance" not in required:
        errors.append(
            f"{label}.required must retain the tenant-authority generation-advance proof"
        )


def validate_tenant_generation_exception_routes(
    routes: list[Any],
    errors: list[str],
    live_checks_cache: LiveChecksCache | None = None,
) -> None:
    for route in routes:
        if not isinstance(route, dict):
            continue
        classification = route.get("classification")
        if not isinstance(classification, str):
            continue
        expected = REQUIRED_TENANT_GENERATION_EXCEPTIONS.get(classification)
        if expected is None:
            continue
        label = route_label(route)
        if route.get("tenant_billing_authority_generation_applies") is not False:
            errors.append(
                f"{label} must explicitly disable tenant_billing_authority_generation_applies "
                f"for {classification}"
            )
        membership_generation = route.get("membership_authority_generation_applies")
        if classification == "billing_safe_tenant":
            if membership_generation is not True:
                errors.append(
                    f"{label} must require membership generation for {classification}"
                )
            roles = route.get("roles")
            role_values = roles.get("any_of") if isinstance(roles, dict) else None
            if role_values != ["tenantAdmin"]:
                errors.append(
                    f"{label} billing_safe_tenant roles.any_of must be ['tenantAdmin']"
                )
        elif membership_generation is not False:
            errors.append(
                f"{label} must explicitly disable membership generation "
                f"for {classification}"
            )
        checks = route_live_checks(route, label, errors, live_checks_cache)
        missing = sorted(expected["required_live_checks"] - checks)
        if missing:
            errors.append(f"{label} is missing route-class authority checks: {missing}")
        if classification in {
            "cross_tenant_support_safe",
            "cross_tenant_billing_safe",
        }:
            target_checks = checks & {
                "membership",
                "membership_generation",
                "tenant_generation",
                "target_tenant_generation",
            }
            if target_checks:
                errors.append(
                    f"{label} must not require target membership or tenant "
                    f"generation checks for {classification}: {sorted(target_checks)}"
                )


def validate_no_target_tenant_routes(
    routes: list[Any],
    errors: list[str],
    live_checks_cache: LiveChecksCache | None = None,
) -> None:
    matched_explicit_target_routes: set[tuple[str, str]] = set()
    for route in routes:
        if not isinstance(route, dict):
            continue
        classification = route.get("classification")
        if not isinstance(classification, str):
            continue
        route_key_value = route_set_key(route)
        if route_key_value in ROUTES_WITH_EXPLICIT_TARGET_TENANT_AUTHORITY:
            matched_explicit_target_routes.add(route_key_value)
            label = route_label(route)
            if classification != "player_bootstrap_tenant":
                errors.append(
                    f"{label} explicit target-tenant authority must use "
                    "classification player_bootstrap_tenant"
                )
            if route.get("tenant_authority_generation_applies") is not True:
                errors.append(
                    f"{label} explicit target-tenant authority must set "
                    "tenant_authority_generation_applies=true"
                )
            continue
        if classification not in NO_TARGET_TENANT_CLASSES_WITHOUT_ROUTE_SPECIFIC_TARGET_AUTHORITY:
            continue
        label = route_label(route)
        checks = (
            route_live_checks(route, label, errors, live_checks_cache)
            if "required_live_checks" in route
            else set()
        )
        forbidden_checks = checks & {"tenant_generation", "target_tenant_generation"}
        if forbidden_checks:
            errors.append(
                f"{label} must not require tenant-generation checks for no-target "
                f"classification {classification}: {sorted(forbidden_checks)}"
            )
    missing_explicit_target_routes = sorted(
        ROUTES_WITH_EXPLICIT_TARGET_TENANT_AUTHORITY - matched_explicit_target_routes
    )
    if missing_explicit_target_routes:
        errors.append(
            "explicit target-tenant authority routes must be declared exactly once: "
            f"{missing_explicit_target_routes}"
        )


def validate_tenant_generation_policy(
    document: dict[str, Any],
    routes: list[Any],
    errors: list[str],
    live_checks_cache: LiveChecksCache | None = None,
) -> None:
    policy = document.get("tenant_generation_policy")
    if not isinstance(policy, dict) or policy.get("applies_by_default") is not True:
        errors.append("tenant_generation_policy must enable applies_by_default")
    else:
        if policy.get("required_authority_semantics") != REQUIRED_AUTHORITY_SEMANTICS:
            errors.append(
                "tenant_generation_policy.required_authority_semantics must declare "
                f"{REQUIRED_AUTHORITY_SEMANTICS}"
            )
        validate_tenant_generation_allowlist(policy, errors)
        validate_no_target_tenant_classifications(policy, errors)
        validate_tenant_generation_negative_proof(policy, errors)
    validate_tenant_generation_exception_routes(routes, errors, live_checks_cache)
    validate_no_target_tenant_routes(routes, errors, live_checks_cache)


def validate_entitlement_contract(
    document: dict[str, Any],
    routes: list[Any],
    errors: list[str],
    cardinality_errors: set[str] | None = None,
) -> None:
    contract = document.get("entitlement_contract")
    if not isinstance(contract, dict):
        errors.append("entitlement_contract must be a mapping")
    else:
        if contract.get("owner") != "account-service":
            errors.append("entitlement_contract.owner must be account-service")
        if contract.get("binding") != "exact_tenant_id":
            errors.append("entitlement_contract.binding must be exact_tenant_id")
        if contract.get("cross_tenant_inheritance") != "forbidden":
            errors.append(
                "entitlement_contract.cross_tenant_inheritance must be forbidden"
            )
        if contract.get("account_wide_fallback") != "forbidden":
            errors.append(
                "entitlement_contract.account_wide_fallback must be forbidden"
            )
    route = resolve_unique_route(
        routes,
        "account-service",
        "GetTenantEntitlementsForRuntime",
        errors,
        cardinality_errors,
    )
    if route is None:
        return
    if route.get("entitlement_scope") != "account_owned_tenant_bound":
        errors.append(
            "GetTenantEntitlementsForRuntime must declare account_owned_tenant_bound entitlement_scope"
        )
    if route.get("cross_tenant_inheritance") != "forbidden":
        errors.append(
            "GetTenantEntitlementsForRuntime must forbid cross-tenant inheritance"
        )


def validate_role_assurance_references(
    routes: list[Any], predicates: set[str], errors: list[str]
) -> None:
    for index, route in enumerate(routes):
        if not isinstance(route, dict) or "role_assurance" not in route:
            continue
        assurance = route.get("role_assurance")
        if not isinstance(assurance, str) or assurance not in predicates:
            errors.append(
                f"routes[{index}] role_assurance must reference a declared predicate"
            )


def validate_role_assurance_route_identities(
    routes: list[Any], errors: list[str]
) -> None:
    for expected_identity in sorted(PLATFORM_ADMIN_ROLE_ASSURANCE_ROUTE_IDENTITIES):
        matches = [
            route
            for route in routes
            if isinstance(route, dict)
            and route_identity_from_route(route) == expected_identity
        ]
        if len(matches) != 1:
            errors.append(
                "role_assurance platformAdmin exact route identity must match exactly "
                f"one route: {expected_identity}"
            )
            continue
        route = matches[0]
        if route.get("classification") != "internal_workload":
            errors.append(
                "role_assurance platformAdmin exact route identity must classify "
                f"{expected_identity} as internal_workload"
            )
        if route.get("role_assurance") != PRIVILEGED_OPERATOR_ROLE_ASSURANCE:
            errors.append(
                "role_assurance platformAdmin exact route identity must declare "
                f"{expected_identity} with {PRIVILEGED_OPERATOR_ROLE_ASSURANCE}"
            )


def validate_operator_reference_issuance(
    routes: list[Any],
    errors: list[str],
    required_fields_cache: RequiredFieldsCache | None = None,
    cardinality_errors: set[str] | None = None,
) -> None:
    for (service, route_name), required_fields in (
        OPERATOR_REFERENCE_ISSUANCE_REQUIRED_FIELDS.items()
    ):
        route = resolve_unique_route(
            routes,
            service,
            route_name,
            errors,
            cardinality_errors,
        )
        if route is None:
            continue
        label = route_label(route)
        raw_fields = route.get("required_fields")
        raw_field_set = (
            {field for field in raw_fields if isinstance(field, str)}
            if isinstance(raw_fields, list)
            else set()
        )
        missing_fields = sorted(required_fields - raw_field_set)
        if missing_fields:
            errors.append(
                f"{label} required_fields must include operator-reference fields: "
                f"{missing_fields}"
            )
        if "required_fields" in route:
            cached_required_fields(
                route,
                raw_fields,
                f"{label} required_fields",
                errors,
                required_fields_cache,
                "required_fields",
            )


def validate_automation_operator_issuance(
    routes: list[Any],
    errors: list[str],
    cardinality_errors: set[str] | None = None,
) -> None:
    route = resolve_unique_route(
        routes,
        "account-service",
        "IssueAutomationOperatorAuthorizationReference",
        errors,
        cardinality_errors,
    )
    if route is None:
        return
    label = route_label(route)
    if route.get("route_status") != AUTOMATION_OPERATOR_ISSUANCE_ROUTE_STATUS:
        errors.append(
            f"{label} route_status must remain "
            f"{AUTOMATION_OPERATOR_ISSUANCE_ROUTE_STATUS!r} until a published "
            "non_moderation action-family schema pair exists"
        )
    if route.get("branch_selector") != "non_moderation_only":
        errors.append(f"{label} branch_selector must be 'non_moderation_only'")
    if route.get("action_category_policy") != "absent_only":
        errors.append(f"{label} action_category_policy must be 'absent_only'")

    mapping_label = f"{label} action_family_schema_mapping"
    mapping = route.get("action_family_schema_mapping")
    if not isinstance(mapping, dict):
        errors.append(f"{mapping_label} must be a mapping")
        return
    expected_mapping_fields = {
        "selector_fields",
        "entries",
        "entries_status",
        "rejections",
    }
    if set(mapping) != expected_mapping_fields:
        errors.append(
            f"{mapping_label} must contain exactly "
            f"{sorted(expected_mapping_fields)}"
        )
    if mapping.get("selector_fields") != AUTOMATION_OPERATOR_ISSUANCE_SCHEMA_SELECTOR_FIELDS:
        errors.append(
            f"{mapping_label}.selector_fields must equal "
            f"{AUTOMATION_OPERATOR_ISSUANCE_SCHEMA_SELECTOR_FIELDS!r}"
        )
    entries = mapping.get("entries")
    if not isinstance(entries, list):
        errors.append(f"{mapping_label}.entries must be a list")
    elif entries:
        errors.append(
            f"{mapping_label}.entries must remain empty while no action-family "
            "schema pairs are published"
        )
    if mapping.get("entries_status") != "no_published_action_family_schema_pairs":
        errors.append(
            f"{mapping_label}.entries_status must be "
            "'no_published_action_family_schema_pairs' while entries are empty"
        )
    if mapping.get("rejections") != AUTOMATION_OPERATOR_ISSUANCE_SCHEMA_MAPPING_REJECTIONS:
        errors.append(
            f"{mapping_label}.rejections must equal "
            f"{AUTOMATION_OPERATOR_ISSUANCE_SCHEMA_MAPPING_REJECTIONS!r}"
        )

    moderation_label = f"{label} moderation_identity_policy"
    if route.get("moderation_identity_policy") != AUTOMATION_OPERATOR_ISSUANCE_MODERATION_IDENTITY_POLICY:
        errors.append(
            f"{moderation_label} must require dedicated moderation identity and "
            "digest while treating control_plane_request_id as correlation-only"
        )


def validate_human_operator_issuance_branches(
    routes: list[Any],
    errors: list[str],
    cardinality_errors: set[str] | None = None,
    *,
    document: dict[str, Any],
) -> None:
    route = resolve_unique_route(
        routes,
        "account-service",
        "IssueHumanOperatorAuthorizationReference",
        errors,
        cardinality_errors,
    )
    if route is None:
        return
    label = route_label(route)
    if route.get("route_status") != HUMAN_OPERATOR_ISSUANCE_ROUTE_STATUS:
        errors.append(
            f"{label} route_status must remain "
            f"{HUMAN_OPERATOR_ISSUANCE_ROUTE_STATUS!r} while no action-family "
            "schema pairs are published"
        )
    if route.get("branch_selector") != "action_category_or_absence":
        errors.append(
            f"{label} branch_selector must be 'action_category_or_absence'"
        )
    precedence = route.get("conditional_branch_precedence")
    if not isinstance(precedence, dict) or precedence.get(
        "branch_fields_override_top_level"
    ) is not True:
        errors.append(
            f"{label} conditional_branch_precedence must override top-level fields"
        )

    identity_selector = route.get("mutation_identity_selector")
    identity_label = f"{label} mutation_identity_selector"
    if not isinstance(identity_selector, dict):
        errors.append(f"{identity_label} must be a mapping")
    else:
        if identity_selector.get("selector_fields") != HUMAN_OPERATOR_ISSUANCE_SELECTOR_KEY_FIELDS:
            errors.append(
                f"{identity_label}.selector_fields must equal "
                f"{HUMAN_OPERATOR_ISSUANCE_SELECTOR_KEY_FIELDS!r}"
            )
        if identity_selector.get("mapping_ref") != HUMAN_OPERATOR_ISSUANCE_SELECTOR_MAPPING_REF:
            errors.append(
                f"{identity_label}.mapping_ref must equal "
                f"{HUMAN_OPERATOR_ISSUANCE_SELECTOR_MAPPING_REF!r}"
            )
        alternatives = identity_selector.get("alternatives")
        expected_names = set(HUMAN_OPERATOR_ISSUANCE_IDENTITY_ALTERNATIVES)
        if not isinstance(alternatives, dict) or set(alternatives) != expected_names:
            errors.append(
                f"{identity_label}.alternatives must contain exactly "
                f"{sorted(expected_names)}"
            )
        elif isinstance(alternatives, dict):
            for name, expected in HUMAN_OPERATOR_ISSUANCE_IDENTITY_ALTERNATIVES.items():
                alternative_label = f"{identity_label}.alternatives.{name}"
                alternative = alternatives.get(name)
                if not isinstance(alternative, dict):
                    errors.append(f"{alternative_label} must be a mapping")
                    continue
                expected_fields = set(expected)
                if set(alternative) != expected_fields:
                    errors.append(
                        f"{alternative_label} must contain exactly "
                        f"{sorted(expected_fields)}"
                    )
                for field, expected_value in expected.items():
                    if alternative.get(field) != expected_value:
                        errors.append(
                            f"{alternative_label}.{field} must equal "
                            f"{expected_value!r}"
                        )

    validate_human_operator_issuance_selector_mapping_and_branches(document, errors)


def validate_human_operator_issuance_selector_mapping_and_branches(
    document: dict[str, Any], errors: list[str]
) -> None:
    label = (
        "operator_delegation.issuance_paths.human.bindings."
        "mutation_identity_selector.mapping"
    )
    delegation = document.get("operator_delegation")
    issuance_paths = (
        delegation.get("issuance_paths")
        if isinstance(delegation, dict)
        else None
    )
    human_path = (
        issuance_paths.get("human")
        if isinstance(issuance_paths, dict)
        else None
    )
    bindings = human_path.get("bindings") if isinstance(human_path, dict) else None
    identity_selector = (
        bindings.get("mutation_identity_selector")
        if isinstance(bindings, dict)
        else None
    )
    mapping = (
        identity_selector.get("mapping")
        if isinstance(identity_selector, dict)
        else None
    )
    if not isinstance(mapping, dict):
        errors.append(f"{label} must be a mapping")
        return

    expected_mapping_fields = {
        "key_fields",
        "entries",
        "entries_status",
        "rejections",
    }
    if set(mapping) != expected_mapping_fields:
        errors.append(
            f"{label} must contain exactly {sorted(expected_mapping_fields)}"
        )

    if mapping.get("key_fields") != HUMAN_OPERATOR_ISSUANCE_SELECTOR_KEY_FIELDS:
        errors.append(
            f"{label}.key_fields must equal "
            f"{HUMAN_OPERATOR_ISSUANCE_SELECTOR_KEY_FIELDS!r}"
        )
    if mapping.get("rejections") != HUMAN_OPERATOR_ISSUANCE_SELECTOR_MAPPING_REJECTIONS:
        errors.append(
            f"{label}.rejections must equal "
            f"{HUMAN_OPERATOR_ISSUANCE_SELECTOR_MAPPING_REJECTIONS!r}"
        )

    entries = mapping.get("entries")
    if not isinstance(entries, list):
        errors.append(f"{label}.entries must be a list")
        return
    if entries:
        errors.append(
            f"{label}.entries must remain empty while no action-family schema "
            "pairs are published"
        )
    elif mapping.get("entries_status") != "no_published_action_family_schema_pairs":
        errors.append(
            f"{label}.entries_status must be "
            "'no_published_action_family_schema_pairs' when entries are empty"
        )

    expected_entry_fields = {
        "action_family",
        "action_family_schema_id",
        "action_family_schema_version",
        "identity_alternative",
    }
    seen_keys: set[tuple[str, str, str]] = set()
    for index, entry in enumerate(entries):
        entry_label = f"{label}.entries[{index}]"
        if not isinstance(entry, dict):
            errors.append(f"{entry_label} must be a mapping")
            continue
        if set(entry) != expected_entry_fields:
            errors.append(
                f"{entry_label} must contain exactly "
                f"{sorted(expected_entry_fields)}"
            )
            continue
        values = tuple(entry.get(field) for field in HUMAN_OPERATOR_ISSUANCE_SELECTOR_KEY_FIELDS)
        if any(not isinstance(value, str) or not value for value in values):
            errors.append(
                f"{entry_label} key fields must be non-empty strings"
            )
            continue
        key = (values[0], values[1], values[2])
        if key in seen_keys:
            errors.append(
                f"{entry_label} duplicates selector key "
                f"{list(key)!r}"
            )
        seen_keys.add(key)
        if entry.get("identity_alternative") not in HUMAN_OPERATOR_ISSUANCE_IDENTITY_ALTERNATIVES:
            errors.append(
                f"{entry_label}.identity_alternative must name exactly one "
                "declared identity alternative"
            )

    routes = document.get("routes")
    route = resolve_unique_route(
        routes if isinstance(routes, list) else [],
        "account-service",
        "IssueHumanOperatorAuthorizationReference",
        errors,
    )
    if route is None:
        return
    label = route_label(route)
    branches = route.get("conditional_branches")
    if not isinstance(branches, dict):
        errors.append(f"{label} conditional_branches must be a mapping")
        return
    if "operator_authorization_branches" in route:
        errors.append(
            f"{label} operator_authorization_branches must be branch-specific "
            "under conditional_branches.tenant_restriction or conditional_branches.non_moderation"
        )
    expected_names = set(HUMAN_OPERATOR_ISSUANCE_BRANCHES)
    if set(branches) != expected_names:
        errors.append(
            f"{label} conditional_branches must contain exactly "
            f"{sorted(expected_names)}"
        )

    top_level_fields = route.get("required_fields")
    top_level_field_set = (
        {field for field in top_level_fields if isinstance(field, str)}
        if isinstance(top_level_fields, list)
        else set()
    )
    if "tenant_scope" in top_level_field_set:
        errors.append(
            f"{label} tenant_scope must be branch-specific, not a common required field"
        )

    for branch_name, expected in HUMAN_OPERATOR_ISSUANCE_BRANCHES.items():
        branch_label = f"{label} conditional_branches.{branch_name}"
        branch = branches.get(branch_name)
        if not isinstance(branch, dict):
            errors.append(f"{branch_label} must be a mapping")
            continue
        for field, expected_value in expected.items():
            if field in {"required_fields", "required_live_checks", "forbidden_live_checks", "forbidden_fields"}:
                continue
            if branch.get(field) != expected_value:
                errors.append(
                    f"{branch_label} must declare {field}={expected_value!r}"
                )
        for field in (
            "required_fields",
            "forbidden_fields",
            "required_live_checks",
            "forbidden_live_checks",
        ):
            if field not in expected:
                continue
            value = branch.get(field)
            if not isinstance(value, list) or any(
                not isinstance(item, str) for item in value
            ):
                errors.append(f"{branch_label}.{field} must be a list of strings")
                continue
            actual = set(value)
            if len(actual) != len(value):
                errors.append(f"{branch_label}.{field} must not contain duplicates")
            if actual != expected[field]:
                errors.append(
                    f"{branch_label}.{field} must equal {sorted(expected[field])}"
                )
        required_fields = branch.get("required_fields")
        forbidden_fields = branch.get("forbidden_fields", [])
        if isinstance(required_fields, list) and isinstance(forbidden_fields, list):
            overlap = sorted(set(required_fields) & set(forbidden_fields))
            if overlap:
                errors.append(
                    f"{branch_label} required_fields must not be forbidden: {overlap}"
                )
        required_checks = branch.get("required_live_checks", [])
        forbidden_checks = branch.get("forbidden_live_checks", [])
        if isinstance(required_checks, list) and isinstance(forbidden_checks, list):
            overlap = sorted(set(required_checks) & set(forbidden_checks))
            if overlap:
                errors.append(
                    f"{branch_label} required_live_checks must not be forbidden: {overlap}"
                )

    for tenant_branch_name in ("non_moderation", "tenant_restriction"):
        tenant_branch = branches.get(tenant_branch_name)
        if not isinstance(tenant_branch, dict):
            continue
        if "operator_authorization_branches" not in tenant_branch:
            errors.append(
                f"{label} {tenant_branch_name} branch must declare "
                "operator_authorization_branches"
            )
        else:
            operator_authorization_branch_checks(
                tenant_branch,
                f"{label} conditional_branches.{tenant_branch_name}",
                errors,
                expected_branches=HUMAN_OPERATOR_ISSUANCE_AUTHORIZATION_BRANCHES,
            )

    platform_branch = branches.get("platform_access_ban")
    if isinstance(platform_branch, dict):
        if "operator_authorization_branches" in platform_branch:
            errors.append(
                f"{label} platform_access_ban branch must not declare "
                "operator_authorization_branches"
            )
        forbidden_fields = platform_branch.get("forbidden_fields")
        if isinstance(forbidden_fields, list):
            common_conflicts = sorted(
                top_level_field_set & set(forbidden_fields)
            )
            if common_conflicts:
                errors.append(
                    f"{label} platform_access_ban branch forbids common fields: "
                    f"{common_conflicts}"
                )


def validate_human_operator_issuance_shared_branch_fields(
    document: dict[str, Any], errors: list[str]
) -> None:
    label = (
        "operator_delegation.issuance_paths.human.bindings.branch_fields"
    )
    delegation = document.get("operator_delegation")
    issuance_paths = (
        delegation.get("issuance_paths")
        if isinstance(delegation, dict)
        else None
    )
    human_path = (
        issuance_paths.get("human")
        if isinstance(issuance_paths, dict)
        else None
    )
    bindings = human_path.get("bindings") if isinstance(human_path, dict) else None
    branch_fields = (
        bindings.get("branch_fields") if isinstance(bindings, dict) else None
    )
    if not isinstance(branch_fields, dict):
        errors.append(f"{label} must be a mapping")
        return

    expected_names = set(HUMAN_OPERATOR_ISSUANCE_SHARED_BRANCH_FIELDS)
    if set(branch_fields) != expected_names:
        errors.append(
            f"{label} must contain exactly {sorted(expected_names)}"
        )

    for branch_name, expected in HUMAN_OPERATOR_ISSUANCE_SHARED_BRANCH_FIELDS.items():
        branch_label = f"{label}.{branch_name}"
        branch = branch_fields.get(branch_name)
        if not isinstance(branch, dict):
            errors.append(f"{branch_label} must be a mapping")
            continue
        if set(branch) != set(expected):
            errors.append(
                f"{branch_label} must contain exactly {sorted(expected)}"
            )
        for field, expected_value in expected.items():
            actual_value = branch.get(field)
            if isinstance(expected_value, list):
                if not isinstance(actual_value, list) or any(
                    not isinstance(item, str) for item in actual_value
                ):
                    errors.append(f"{branch_label}.{field} must be a list of strings")
                    continue
                if len(actual_value) != len(set(actual_value)):
                    errors.append(
                        f"{branch_label}.{field} must not contain duplicates"
                    )
                if actual_value != expected_value:
                    errors.append(
                        f"{branch_label}.{field} must equal {expected_value!r}"
                    )
            elif actual_value != expected_value:
                errors.append(
                    f"{branch_label}.{field} must equal {expected_value!r}"
                )


def validate_operator_issuance_identity_contract(
    document: dict[str, Any], errors: list[str]
) -> None:
    delegation = document.get("operator_delegation")
    issuance = delegation.get("issuance_idempotency") if isinstance(delegation, dict) else None
    label = "operator_delegation.issuance_idempotency"
    if not isinstance(issuance, dict):
        errors.append(f"{label} must be a mapping")
        return
    expected_non_moderation_key = [
        "control_plane_request_id",
        "action_family_schema_id",
        "action_family_schema_version",
        "mutation_digest",
        "exact_scope",
        "authority_path_binding",
    ]
    if issuance.get("non_moderation_key") != expected_non_moderation_key:
        errors.append(
            f"{label}.non_moderation_key must use control_plane_request_id "
            "as the mutation identity"
        )
    moderation_keys = issuance.get("moderation_keys")
    expected_names = set(OPERATOR_ISSUANCE_MODERATION_IDENTITY_KEYS)
    if not isinstance(moderation_keys, dict) or set(moderation_keys) != expected_names:
        errors.append(
            f"{label}.moderation_keys must contain exactly {sorted(expected_names)}"
        )
        return
    identities: set[str] = set()
    for name, expected in OPERATOR_ISSUANCE_MODERATION_IDENTITY_KEYS.items():
        branch_label = f"{label}.moderation_keys.{name}"
        branch = moderation_keys.get(name)
        if not isinstance(branch, dict):
            errors.append(f"{branch_label} must be a mapping")
            continue
        for field in ("request_identity", "digest", "key"):
            if branch.get(field) != expected[field]:
                errors.append(
                    f"{branch_label}.{field} must equal {expected[field]!r}"
                )
        if branch.get("control_plane_request_id") != "correlation_only":
            errors.append(
                f"{branch_label}.control_plane_request_id must be correlation_only"
            )
        request_identity = branch.get("request_identity")
        if isinstance(request_identity, str):
            identities.add(request_identity)
            key = branch.get("key")
            if isinstance(key, list) and "control_plane_request_id" in key:
                errors.append(
                    f"{branch_label}.key must not use correlation-only "
                    "control_plane_request_id"
                )
    if identities != {
        "policy_intent_request_id",
        "owner_enforcement_request_id",
    }:
        errors.append(
            f"{label}.moderation_keys must use exactly the dedicated moderation "
            "mutation identities"
        )


def validate_authority_unavailable_outcomes(
    routes: list[Any],
    errors: list[str],
    cardinality_errors: set[str] | None = None,
) -> None:
    for index, route in enumerate(routes):
        if not isinstance(route, dict):
            continue
        canonical_errors = route.get("canonical_errors")
        outcomes = (
            canonical_errors.get("any_of")
            if isinstance(canonical_errors, dict)
            else None
        )
        if not isinstance(outcomes, list) or any(
            not isinstance(outcome, str) for outcome in outcomes
        ):
            continue
        aliases = sorted(
            set(outcomes) & UNAVAILABLE_AUTHORITY_ERROR_ALIASES
        )
        if aliases:
            errors.append(
                f"routes[{index}] canonical_errors must use {AUTH_UNAVAILABLE} "
                f"instead of unavailable-authority aliases: {aliases}"
            )

    for service, route_name in sorted(AUTH_UNAVAILABLE_REQUIRED_ROUTES):
        route = resolve_unique_route(
            routes,
            service,
            route_name,
            errors,
            cardinality_errors,
        )
        if route is None:
            continue
        canonical_errors = route.get("canonical_errors")
        outcomes = (
            canonical_errors.get("any_of")
            if isinstance(canonical_errors, dict)
            else None
        )
        if not isinstance(outcomes, list) or AUTH_UNAVAILABLE not in outcomes:
            errors.append(
                f"{service} {route_name} must declare {AUTH_UNAVAILABLE} "
                "for unavailable authority"
            )


def validate_moderation_policy_contract(
    document: dict[str, Any], routes: list[Any], errors: list[str]
) -> None:
    contract = document.get("moderation_policy_intent_authorization")
    if not isinstance(contract, dict):
        errors.append(
            "moderation_policy_intent_authorization must be a mapping"
        )
        return

    if contract.get("coverage_identity") != MODERATION_POLICY_INTENT_COVERAGE_IDENTITY:
        errors.append(
            "moderation_policy_intent_authorization.coverage_identity must be "
            f"{MODERATION_POLICY_INTENT_COVERAGE_IDENTITY!r}"
        )
    if contract.get("route_identities") != MODERATION_POLICY_INTENT_ROUTE_IDENTITIES:
        errors.append(
            "moderation_policy_intent_authorization.route_identities must be "
            f"{MODERATION_POLICY_INTENT_ROUTE_IDENTITIES!r}"
        )
    if contract.get("request_identity") != "policy_intent_request_id":
        errors.append(
            "moderation_policy_intent_authorization.request_identity must be "
            "'policy_intent_request_id'"
        )
    if contract.get("digest") != "mutationDigest/v1":
        errors.append(
            "moderation_policy_intent_authorization.digest must be "
            "'mutationDigest/v1'"
        )
    if contract.get("digest_field") != "mutation_digest":
        errors.append(
            "moderation_policy_intent_authorization.digest_field must be "
            "'mutation_digest'"
        )

    authorization_reference = contract.get("authorization_reference")
    if not isinstance(authorization_reference, dict):
        errors.append(
            "moderation_policy_intent_authorization.authorization_reference "
            "must be a mapping"
        )
    else:
        if (
            authorization_reference.get("contract")
            != "account_issued_bounded_reference"
        ):
            errors.append(
                "moderation policy-intent authorization reference must use "
                "the account_issued_bounded_reference contract"
            )
        if (
            authorization_reference.get("raw_reference_persistence")
            != "forbidden_for_policy_intent"
        ):
            errors.append(
                "moderation policy-intent authorization reference must forbid "
                "raw reference persistence"
            )
        if authorization_reference.get("fingerprint_field") != "authorization_reference_fingerprint":
            errors.append(
                "moderation policy-intent authorization reference must use "
                "authorization_reference_fingerprint"
            )

    redemption = contract.get("redemption")
    if not isinstance(redemption, dict):
        errors.append(
            "moderation_policy_intent_authorization.redemption must be a mapping"
        )
    else:
        expected_redemption = {
            "receiving_boundary": "logging-admin-service",
            "exactly_once": True,
            "owner_redemption": "forbidden",
        }
        for field, expected in expected_redemption.items():
            if redemption.get(field) != expected:
                errors.append(
                    "moderation policy-intent redemption must declare "
                    f"{field}={expected!r}"
                )

    owner_command = contract.get("owner_enforcement_command")
    if not isinstance(owner_command, dict):
        errors.append(
            "moderation_policy_intent_authorization.owner_enforcement_command "
            "must be a mapping"
        )
    else:
        expected_owner_command = {
            "request_identity": "owner_enforcement_request_id",
            "digest": "owner_enforcement_digest",
            "authorization_reference": "owner_enforcement_authorization_reference",
            "fingerprint": "owner_enforcement_authorization_reference_fingerprint",
            "redeemed_by": "selected_enforcement_owner",
            "distinct_from_policy_intent": True,
        }
        for field, expected in expected_owner_command.items():
            if owner_command.get(field) != expected:
                errors.append(
                    "moderation owner-enforcement command must declare "
                    f"{field}={expected!r}"
                )

    selector = document.get("moderation_action_selector")
    if not isinstance(selector, dict):
        errors.append("moderation_action_selector must be a mapping")
    else:
        if selector.get("request_field") != "action":
            errors.append(
                "moderation_action_selector.request_field must be 'action'"
            )
        if selector.get("branch_field") != "action_category":
            errors.append(
                "moderation_action_selector.branch_field must be 'action_category'"
            )
        if selector.get("mapping") != MODERATION_ACTION_SELECTOR_MAPPING:
            errors.append(
                "moderation_action_selector.mapping must exactly map the closed "
                f"action set: {MODERATION_ACTION_SELECTOR_MAPPING!r}"
            )
        rejected_values = selector.get("rejected_values")
        if (
            not isinstance(rejected_values, list)
            or set(rejected_values) != MODERATION_ACTION_SELECTOR_REJECTED_VALUES
            or len(rejected_values) != len(set(rejected_values))
        ):
            errors.append(
                "moderation_action_selector.rejected_values must exactly reject "
                f"{sorted(MODERATION_ACTION_SELECTOR_REJECTED_VALUES)}"
            )
        if selector.get("unknown_values") != "reject":
            errors.append(
                "moderation_action_selector.unknown_values must be 'reject'"
            )
        if selector.get("rejection_phase") != "before_branch_authorization":
            errors.append(
                "moderation_action_selector.rejection_phase must be "
                "'before_branch_authorization'"
            )

    gate = document.get("operator_mutation_support_gate")
    gate_applies_to = gate.get("applies_to") if isinstance(gate, dict) else None
    if not isinstance(gate_applies_to, list):
        errors.append(
            "operator_mutation_support_gate.applies_to must include the "
            "moderation policy-intent authorization identities"
        )
    else:
        missing_gate_identities = sorted(
            set(MODERATION_POLICY_INTENT_ROUTE_IDENTITIES) - set(gate_applies_to)
        )
        if missing_gate_identities:
            errors.append(
                "operator mutation gate is missing moderation policy-intent "
                f"identities: {missing_gate_identities}"
            )
        required_before_enablement = gate.get("required_before_enablement")
        if (
            not isinstance(required_before_enablement, list)
            or MODERATION_SUPPORT_GATE_REDEMPTION_REQUIREMENT
            not in required_before_enablement
        ):
            errors.append(
                "operator mutation gate must require Account reference issuance "
                "plus contract-declared receiving-boundary redemption"
            )

    operator_delegation = document.get("operator_delegation")
    if not isinstance(operator_delegation, dict) or operator_delegation.get(
        "redeemed_by"
    ) != "contract_declared_receiving_boundary":
        errors.append(
            "operator_delegation.redeemed_by must be "
            "'contract_declared_receiving_boundary'"
        )

    moderation_routes = matching_routes(
        routes, MODERATION_ACTION_ROUTE[0], MODERATION_ACTION_ROUTE[1]
    )
    for route in moderation_routes:
        label = route_label(route)
        branch = applicability_value(route, "action_category", label, errors)
        if route.get("action_selector_branch") != branch:
            errors.append(
                f"{label} action_selector_branch must link its applicability "
                f"branch {branch!r}"
            )
        if (
            route.get("moderation_policy_intent_authorization_contract")
            != MODERATION_POLICY_INTENT_COVERAGE_IDENTITY
        ):
            errors.append(
                f"{label} must link moderation policy-intent authorization "
                f"coverage {MODERATION_POLICY_INTENT_COVERAGE_IDENTITY!r}"
            )

    coverage_drift = document.get("coverage_drift")
    moderation_drift: list[dict[str, Any]] = []
    if isinstance(coverage_drift, list):
        moderation_drift = [
            entry
            for entry in coverage_drift
            if isinstance(entry, dict)
            and entry.get("family") == "moderation-enforcement-owner-call"
        ]
    observed_owners: dict[str, str] = {}
    for entry in moderation_drift:
        category = entry.get("category")
        owner = entry.get("target_owner")
        if category in observed_owners:
            errors.append(
                "moderation-enforcement-owner-call coverage must not duplicate "
                f"category {category!r}"
            )
        elif isinstance(category, str) and isinstance(owner, str):
            observed_owners[category] = owner
        if entry.get("status") != "drift-found":
            errors.append(
                "moderation-enforcement-owner-call coverage must remain "
                "status drift-found"
            )
        if entry.get("target_ingress") != "logging-admin-service":
            errors.append(
                "moderation-enforcement-owner-call coverage must target "
                "logging-admin-service ingress"
            )
        if entry.get("current_routes") != []:
            errors.append(
                "moderation-enforcement-owner-call coverage must declare "
                "current_routes=[]"
            )
    if observed_owners != MODERATION_ENFORCEMENT_OWNER_BY_CATEGORY:
        errors.append(
            "moderation-enforcement-owner-call coverage must exactly map "
            f"categories to owners: {MODERATION_ENFORCEMENT_OWNER_BY_CATEGORY!r}"
        )


def validate_moderation_action_variant_contract(
    route: dict[str, Any],
    label: str,
    action_category: str,
    errors: list[str],
    live_checks_cache: LiveChecksCache | None = None,
) -> None:
    expected_fields = MODERATION_ACTION_REQUIRED_FIELDS[action_category]
    raw_fields = route.get("required_fields")
    field_set = (
        {field for field in raw_fields if isinstance(field, str)}
        if isinstance(raw_fields, list)
        else set()
    )
    if (
        not isinstance(raw_fields, list)
        or field_set != expected_fields
        or len(raw_fields) != len(field_set)
    ):
        append_unique_error(
            errors,
            f"{label} required_fields must exactly match the "
            f"{action_category!r} moderation action contract: "
            f"{sorted(expected_fields)}",
        )

    contract = route.get("idempotency_contract")
    if not isinstance(contract, dict):
        append_unique_error(
            errors,
            f"{label} idempotency_contract must declare the moderation action "
            "identity and digest contract",
        )
    else:
        for field, expected in MODERATION_ACTION_IDEMPOTENCY_CONTRACT.items():
            if contract.get(field) != expected:
                append_unique_error(
                    errors,
                    f"{label} idempotency_contract.{field} must be {expected!r}",
                )

    checks = route_live_checks(route, label, errors, live_checks_cache)
    if "current_operator_authorization" not in checks:
        append_unique_error(
            errors,
            f"{label} moderation action branch must require live check "
            "current_operator_authorization",
        )
    if route.get("role_assurance") != PRIVILEGED_OPERATOR_ROLE_ASSURANCE:
        append_unique_error(
            errors,
            f"{label} moderation action branch must declare role_assurance "
            f"{PRIVILEGED_OPERATOR_ROLE_ASSURANCE}",
        )

    if action_category != MODERATION_PLATFORM_ACTION_CATEGORY:
        return
    raw_forbidden_fields = route.get("forbidden_fields")
    forbidden_field_set = (
        {field for field in raw_forbidden_fields if isinstance(field, str)}
        if isinstance(raw_forbidden_fields, list)
        else set()
    )
    if (
        not isinstance(raw_forbidden_fields, list)
        or forbidden_field_set != MODERATION_PLATFORM_FORBIDDEN_ROUTE_FIELDS
        or len(raw_forbidden_fields) != len(forbidden_field_set)
    ):
        append_unique_error(
            errors,
            f"{label} platform_access_ban forbidden_fields must exactly match "
            f"{sorted(MODERATION_PLATFORM_FORBIDDEN_ROUTE_FIELDS)}",
        )
    forbidden_fields = field_set & MODERATION_PLATFORM_FORBIDDEN_ROUTE_FIELDS
    forbidden_fields.update(
        field
        for field in MODERATION_PLATFORM_FORBIDDEN_ROUTE_FIELDS
        if field in route
    )
    forbidden_fields.update(checks & MODERATION_PLATFORM_FORBIDDEN_ROUTE_FIELDS)
    if forbidden_fields:
        append_unique_error(
            errors,
            f"{label} platform_access_ban branch must forbid tenant "
            f"identity/scope/generation fields: {sorted(forbidden_fields)}",
        )


def validate_moderation_action_route_variants(
    routes: list[Any],
    errors: list[str],
    live_checks_cache: LiveChecksCache | None = None,
) -> None:
    """Keep moderation action categories on their one canonical auth branch."""

    matches = matching_routes(
        routes, MODERATION_ACTION_ROUTE[0], MODERATION_ACTION_ROUTE[1]
    )
    if len(matches) != len(MODERATION_ACTION_BRANCHES):
        append_unique_error(
            errors,
            "logging-admin-service POST /moderation/actions must declare exactly "
            "one tenant-restriction and one platform-access-ban action branch",
        )

    observed: dict[str, dict[str, Any]] = {}
    for route in matches:
        label = route_label(route)
        action_category = applicability_value(route, "action_category", label, errors)
        if action_category not in MODERATION_ACTION_BRANCHES:
            append_unique_error(
                errors,
                f"{label} must select one of the canonical moderation action "
                f"branches: {sorted(MODERATION_ACTION_BRANCHES)}",
            )
            continue
        if action_category in observed:
            append_unique_error(
                errors,
                f"{label} duplicates moderation action branch {action_category!r}",
            )
            continue
        observed[action_category] = route
        accepted_categories = route.get("accepted_action_categories")
        if (
            not isinstance(accepted_categories, list)
            or any(not isinstance(category, str) for category in accepted_categories)
            or set(accepted_categories) != MODERATION_ACTION_BRANCHES[action_category]
            or len(accepted_categories) != len(set(accepted_categories))
        ):
            append_unique_error(
                errors,
                f"{label} accepted_action_categories must exactly match the "
                f"{action_category!r} moderation action branch",
            )
        validate_moderation_action_variant_contract(
            route,
            label,
            action_category,
            errors,
            live_checks_cache,
        )

    if set(observed) != set(MODERATION_ACTION_BRANCHES):
        return

    tenant_route = observed["tenant_restriction"]
    tenant_label = route_label(tenant_route)
    if tenant_route.get("classification") != "tenant_regular":
        append_unique_error(
            errors,
            f"{tenant_label} tenant-restriction branch must use classification "
            "tenant_regular",
        )
    if tenant_route.get("scope") != "tenant":
        append_unique_error(
            errors,
            f"{tenant_label} tenant-restriction branch must use scope=tenant",
        )
    tenant_required_fields = tenant_route.get("required_fields")
    if (
        not isinstance(tenant_required_fields, list)
        or "tenant_id" not in tenant_required_fields
    ):
        append_unique_error(
            errors,
            f"{tenant_label} tenant-restriction branch required_fields must include tenant_id",
        )

    platform_route = observed["platform_access_ban"]
    platform_label = route_label(platform_route)
    if platform_route.get("classification") != "account_scoped":
        append_unique_error(
            errors,
            f"{platform_label} platform_access_ban branch must use classification "
            "account_scoped",
        )
    if platform_route.get("scope") != "account":
        append_unique_error(
            errors,
            f"{platform_label} platform_access_ban branch must use scope=account",
        )
    if platform_route.get("global_platform_admin_membership_required") is not False:
        append_unique_error(
            errors,
            f"{platform_label} platform_access_ban branch must set "
            "global_platform_admin_membership_required=false",
        )
    if platform_route.get("tenant_billing_authority_generation_applies") is not False:
        append_unique_error(
            errors,
            f"{platform_label} platform_access_ban branch must disable tenant "
            "billing authority generation",
        )
    if platform_route.get("membership_authority_generation_applies") is not False:
        append_unique_error(
            errors,
            f"{platform_label} platform_access_ban branch must disable membership "
            "authority generation",
        )
    if "membership_authority_generation_condition" in platform_route:
        append_unique_error(
            errors,
            f"{platform_label} platform_access_ban branch must not declare a "
            "membership authority-generation condition",
        )
    if "operator_authorization_branches" in platform_route:
        append_unique_error(
            errors,
            f"{platform_label} platform_access_ban branch must not declare "
            "operator_authorization_branches",
        )
    if platform_route.get("account_authority_generation_applies") is not True:
        append_unique_error(
            errors,
            f"{platform_label} platform_access_ban branch must apply account "
            "authority generation",
        )
    if platform_route.get("issuer_authority_generation_applies") is not True:
        append_unique_error(
            errors,
            f"{platform_label} platform_access_ban branch must apply issuer "
            "authority generation",
        )

    platform_checks = route_live_checks(
        platform_route, platform_label, errors, live_checks_cache
    )
    forbidden_checks = platform_checks & {
        "membership",
        "membership_generation",
        "tenant_generation",
        "target_tenant_generation",
    }
    if forbidden_checks:
        append_unique_error(
            errors,
            f"{platform_label} platform_access_ban branch must not require tenant "
            f"or membership checks: {sorted(forbidden_checks)}",
        )
    expected_platform_checks = {
        "current_operator_authorization",
        "issuer_generation",
        "account_generation",
        "current_global_role",
        "role_appropriate_assurance",
    }
    missing_platform_checks = expected_platform_checks - platform_checks
    unexpected_platform_checks = platform_checks - expected_platform_checks - forbidden_checks
    if missing_platform_checks or unexpected_platform_checks:
        append_unique_error(
            errors,
            f"{platform_label} platform_access_ban branch has missing or unexpected "
            "live checks after tenant/membership exclusions: "
            f"missing={sorted(missing_platform_checks)}, "
            f"unexpected={sorted(unexpected_platform_checks)}",
        )

    roles = platform_route.get("roles")
    if not isinstance(roles, dict) or roles.get("any_of") != ["platformAdmin"]:
        append_unique_error(
            errors,
            f"{platform_label} platform_access_ban branch must authorize only "
            "platformAdmin",
        )
    if platform_route.get("role_assurance") != PRIVILEGED_OPERATOR_ROLE_ASSURANCE:
        append_unique_error(
            errors,
            f"{platform_label} platform_access_ban branch must require "
            f"{PRIVILEGED_OPERATOR_ROLE_ASSURANCE}",
        )
    if platform_route.get("target_subject_binding") != "explicit_target_account_id":
        append_unique_error(
            errors,
            f"{platform_label} platform_access_ban branch must bind "
            "explicit_target_account_id",
        )
    if platform_route.get("route_status") != "target_not_currently_routable":
        append_unique_error(
            errors,
            f"{platform_label} platform_access_ban branch must declare "
            "route_status target_not_currently_routable",
        )
    if platform_route.get("platform_scope_action_contract") != {
        "category": MODERATION_PLATFORM_ACTION_CATEGORY,
        "status": "target_not_currently_routable",
        "required_scope": "platform",
        "required_operator_authorization_branch": "platformAdmin_global",
        "tenant_role_authorization": "forbidden",
        "target_subject": "global_account",
        "tenant_id": "forbidden",
    }:
        append_unique_error(
            errors,
            f"{platform_label} platform_access_ban branch must declare the "
            "platform-jurisdiction target-account contract",
        )


def validate_generation_applicability(
    routes: list[Any],
    errors: list[str],
    live_checks_cache: LiveChecksCache | None = None,
) -> None:
    for index, route in enumerate(routes):
        if not isinstance(route, dict):
            continue
        label = f"routes[{index}] {route_label(route)}"
        if "account_generation_applies" in route:
            errors.append(
                f"{label} must use account_authority_generation_applies instead of "
                "account_generation_applies"
            )
        account_generation = route.get("account_authority_generation_applies")
        if account_generation is not None and not isinstance(account_generation, bool):
            errors.append(
                f"{label} account_authority_generation_applies must be boolean"
            )
        route_key_value = route_set_key(route)
        checks = None
        if (
            route.get("classification") in (
                "pending_deletion_scoped",
                "security_lock_export_scoped",
            )
            or route_key_value in CONDITIONAL_OPERATOR_ROUTES
            or route_key_value in ACCOUNT_SUBJECT_BOUND_ROUTES
        ):
            checks = route_live_checks(route, label, errors, live_checks_cache)
        branch_checks = None
        if "operator_authorization_branches" in route:
            branch_checks = operator_authorization_branch_checks(
                route, label, errors, live_checks_cache
            )
        validate_recovery_export_generation(
            route, label, errors, checks, live_checks_cache
        )
        value = validate_membership_generation(route, label, errors)
        if value is True:
            if checks is None:
                checks = route_live_checks(route, label, errors, live_checks_cache)
            if "membership_generation" not in checks:
                errors.append(
                    f"{label} membership_authority_generation_applies=true requires "
                    "live check membership_generation"
                )
        has_admission_mode_selection = "admission_mode_selection" in route
        if has_admission_mode_selection:
            validate_admission_mode_selection(
                route, label, errors, live_checks_cache
            )
        validate_conditional_operator_route(
            route, label, value, errors, checks, live_checks_cache, branch_checks
        )
        validate_account_authorization_route(
            route, label, errors, checks, live_checks_cache
        )


def validate_profile_authority_routes(
    routes: list[Any],
    errors: list[str],
    live_checks_cache: LiveChecksCache | None = None,
    cardinality_errors: set[str] | None = None,
) -> None:
    for service, route_name in PROFILE_ROUTES:
        matches = matching_routes(routes, service, route_name)
        if not matches:
            resolve_unique_route(
                routes,
                service,
                route_name,
                errors,
                cardinality_errors,
            )
            continue
        for route in matches:
            label = route_label(route)
            if route.get("auth_path") != "control_ui_plus_current_tenant_role":
                append_unique_error(
                    errors,
                    f"{label} must declare auth_path control_ui_plus_current_tenant_role"
                )
            if route.get("method_policy") != "exact_declared_route":
                append_unique_error(
                    errors,
                    f"{label} must declare method_policy exact_declared_route",
                )
            if route.get("subject_binding") != "caller_account_id":
                append_unique_error(
                    errors,
                    f"{label} must declare subject_binding caller_account_id",
                )
            if "self_only_roles" in route:
                append_unique_error(errors, f"{label} must not declare self_only_roles")
            if route.get("target_subject_binding") != PROFILE_TARGET_SUBJECT_BINDING:
                append_unique_error(
                    errors,
                    f"{label} must declare target_subject_binding "
                    f"{PROFILE_TARGET_SUBJECT_BINDING}",
                )
            if route.get("platform_admin_override") != "forbidden":
                append_unique_error(
                    errors,
                    f"{label} must declare platform_admin_override forbidden",
                )
            if route.get("tenant_billing_authority_generation_applies") is not True:
                append_unique_error(
                    errors,
                    f"{label} must apply tenant billing authority generation",
                )
            if route.get("membership_authority_generation_applies") is not True:
                append_unique_error(
                    errors,
                    f"{label} must apply membership authority generation",
                )
            checks = route_live_checks(route, label, errors, live_checks_cache)
            for required_check in (
                "membership",
                "membership_generation",
                "tenant_generation",
            ):
                if required_check not in checks:
                    append_unique_error(
                        errors, f"{label} must require live check {required_check}"
                    )


def validate_idempotency_contract(
    route: dict[str, Any],
    label: str,
    errors: list[str],
    required_fields_cache: RequiredFieldsCache | None = None,
) -> None:
    required_fields = (
        set(
            cached_required_fields(
                route,
                route.get("required_fields"),
                f"{label} required_fields",
                errors,
                required_fields_cache,
                "required_fields",
            )
        )
        if "required_fields" in route
        else set()
    )
    if "mutation_digest" not in required_fields:
        errors.append(f"{label} must require mutation_digest for idempotency")
    canonical_errors = route.get("canonical_errors", {})
    any_of = (
        canonical_errors.get("any_of")
        if isinstance(canonical_errors, dict)
        else None
    )
    outcomes = string_list(any_of, f"{label} canonical_errors.any_of", errors)
    if "IDEMPOTENCY_CONFLICT" not in outcomes:
        errors.append(f"{label} must declare IDEMPOTENCY_CONFLICT")


def validate_logging_admin_idempotency(
    routes: list[Any],
    errors: list[str],
    cardinality_errors: set[str] | None = None,
    required_fields_cache: RequiredFieldsCache | None = None,
) -> None:
    for service, route_name in sorted(LOGGING_ADMIN_IDEMPOTENT_OPERATOR_ROUTES):
        if (service, route_name) == MODERATION_ACTION_ROUTE:
            matching = matching_routes(routes, service, route_name)
            if not matching:
                resolve_unique_route(
                    routes,
                    service,
                    route_name,
                    errors,
                    cardinality_errors,
                )
            routes_to_validate = matching
        else:
            route = resolve_unique_route(
                routes,
                service,
                route_name,
                errors,
                cardinality_errors,
            )
            routes_to_validate = [route] if route is not None else []
        for route in routes_to_validate:
            label = (
                route_label(route)
                if (service, route_name) == MODERATION_ACTION_ROUTE
                else f"{service} {route_name}"
            )
            validate_idempotency_contract(
                route,
                label,
                errors,
                required_fields_cache,
            )


def validate_refresh_roles_route(
    route: dict[str, Any],
    label: str,
    errors: list[str],
    live_checks_cache: LiveChecksCache | None,
    required_fields_cache: RequiredFieldsCache | None,
    expected_mutation_contract: str | None = None,
) -> None:
    if route.get("auth_path") != "exact_mtls_workload":
        errors.append(f"{label} must use exact_mtls_workload auth_path")
    if "operator_authorization_reference" in route:
        errors.append(
            f"{label} must not receive an operator authorization reference"
        )
    if "delegated_subject" in route:
        errors.append(f"{label} must not declare a delegated subject")
    checks = route_live_checks(route, label, errors, live_checks_cache)
    for required_check in ("current_session", "current_account_roles"):
        if required_check not in checks:
            errors.append(f"{label} must require live check {required_check}")
    if "current_operator_authorization" in checks:
        errors.append(f"{label} must not depend on current operator authorization")
    if (
        expected_mutation_contract is not None
        and route.get("mutation_contract") != expected_mutation_contract
    ):
        errors.append(f"{label} must use the owner role-refresh mutation contract")
    validate_idempotency_contract(
        route,
        label,
        errors,
        required_fields_cache,
    )


def validate_refresh_roles_routes(
    routes: list[Any],
    errors: list[str],
    live_checks_cache: LiveChecksCache | None = None,
    cardinality_errors: set[str] | None = None,
    required_fields_cache: RequiredFieldsCache | None = None,
) -> None:
    route_specs = (
        ("RefreshRoles", None),
        (
            "POST /sessions/{sessionId}/refresh-roles",
            "owner_atomic_idempotent_role_refresh_with_durable_result",
        ),
    )
    for route_name, expected_mutation_contract in route_specs:
        route = resolve_unique_route(
            routes,
            "game-session-service",
            route_name,
            errors,
            cardinality_errors,
        )
        if route is not None:
            validate_refresh_roles_route(
                route,
                f"game-session-service {route_name}",
                errors,
                live_checks_cache,
                required_fields_cache,
                expected_mutation_contract,
            )


def validate_known_drift(value: Any, field: str, errors: list[str]) -> None:
    if isinstance(value, dict):
        for key, child in value.items():
            child_field = f"{field}.{key}" if field else key
            if (
                key == "implementation_status"
                and isinstance(child, dict)
                and "known_drift" in child
            ):
                known_drift = child["known_drift"]
                if (
                    not isinstance(known_drift, list)
                    or not known_drift
                    or any(not isinstance(item, str) for item in known_drift)
                ):
                    errors.append(
                        f"{child_field}.known_drift must be a non-empty list of strings"
                    )
            validate_known_drift(child, child_field, errors)
    elif isinstance(value, list):
        for index, child in enumerate(value):
            validate_known_drift(child, f"{field}[{index}]", errors)


def route_identity(value: Any, field: str, errors: list[str]) -> str | None:
    if not isinstance(value, str) or not value.strip():
        errors.append(f"{field} must be a non-empty service/route identity")
        return None
    service, separator, route = value.partition("/")
    components = (
        canonical_route_components(service, route) if separator else None
    )
    if components is None:
        errors.append(f"{field} must be a non-empty service/route identity")
        return None
    return "/".join(components)


def validate_operator_mutation_support_gate_override_vocabulary(
    document: dict[str, Any], errors: list[str]
) -> set[str]:
    override_vocabulary = document.get("route_status_override_vocabulary")
    if (
        not isinstance(override_vocabulary, list)
        or not override_vocabulary
        or any(not isinstance(item, str) for item in override_vocabulary)
    ):
        errors.append(
            "route_status_override_vocabulary must be a non-empty list of strings"
        )
        allowed_overrides: set[str] = set()
    else:
        allowed_overrides = set(override_vocabulary)
        if len(allowed_overrides) != len(override_vocabulary):
            errors.append("route_status_override_vocabulary must not contain duplicates")
        if allowed_overrides != ROUTE_STATUS_OVERRIDE_VALUES:
            errors.append(
                "route_status_override_vocabulary must contain exactly "
                f"{sorted(ROUTE_STATUS_OVERRIDE_VALUES)}"
            )
    return allowed_overrides


def _operator_mutation_gate_identities(
    gate: dict[str, Any], errors: list[str]
) -> tuple[list[str], list[str]]:
    gate_identities: list[str] = []
    applies_to_identities: list[str] = []
    for field in ("applies_to", "live_exceptions"):
        values = string_list(
            gate.get(field), f"operator_mutation_support_gate.{field}", errors
        )
        for index, value in enumerate(values):
            identity = route_identity(
                value,
                f"operator_mutation_support_gate.{field}[{index}]",
                errors,
            )
            if identity is not None:
                gate_identities.append(identity)
                if field == "applies_to":
                    applies_to_identities.append(identity)
    return gate_identities, applies_to_identities


def _operator_mutation_gate_coverage_drift(
    gate: dict[str, Any], errors: list[str]
) -> list[str]:
    coverage_drift = gate.get("coverage_drift")
    if not isinstance(coverage_drift, list) or not coverage_drift:
        errors.append(
            "operator_mutation_support_gate.coverage_drift must be a non-empty list"
        )
        coverage_drift = []

    drift_identities: list[str] = []
    for index, entry in enumerate(coverage_drift):
        label = f"operator_mutation_support_gate.coverage_drift[{index}]"
        if not isinstance(entry, dict):
            errors.append(f"{label} must be a mapping")
            continue
        identity = route_identity(entry.get("identity"), f"{label}.identity", errors)
        if identity is not None:
            drift_identities.append(identity)
        if entry.get("status") != "drift-found":
            errors.append(f"{label}.status must be drift-found")
        note = entry.get("note")
        if not isinstance(note, str) or not note.strip():
            errors.append(f"{label}.note must be a non-empty string")
    return drift_identities


def _validate_operator_mutation_gate_identity_coverage(
    gate_identities: list[str],
    drift_identities: list[str],
    applies_to_identities: list[str],
    route_identities: set[str],
    errors: list[str],
) -> None:
    if len(set(gate_identities)) != len(gate_identities):
        errors.append(
            "operator_mutation_support_gate applies_to/live_exceptions must not duplicate identities"
        )
    if len(set(drift_identities)) != len(drift_identities):
        errors.append(
            "operator_mutation_support_gate.coverage_drift must not duplicate identities"
        )
    missing_required_routes = sorted(
        REQUIRED_SESSION_LIFECYCLE_GATE_ROUTES - set(applies_to_identities)
    )
    if missing_required_routes:
        errors.append(
            "operator_mutation_support_gate.applies_to is missing required current "
            f"session lifecycle routes: {missing_required_routes}"
        )
    for identity in sorted(
        set(gate_identities) - route_identities - set(drift_identities)
    ):
        errors.append(
            "operator_mutation_support_gate identity is neither a route nor explicit "
            f"coverage drift: {identity}"
        )
    for identity in sorted(set(drift_identities) - set(gate_identities)):
        errors.append(
            "operator_mutation_support_gate.coverage_drift identity is not listed in "
            f"applies_to/live_exceptions: {identity}"
        )


def _validate_operator_mutation_gate_route_status(
    applies_to_identities: list[str],
    routes_by_identity: dict[str, list[dict[str, Any]]],
    gate_status: Any,
    route_status_override: Any,
    errors: list[str],
) -> None:
    if (
        route_status_override == "gate_wins_for_applies_to"
        and gate_status == OPERATOR_MUTATION_SUPPORT_GATE_STATUS
    ):
        for identity in sorted(set(applies_to_identities)):
            for route in routes_by_identity.get(identity, []):
                if route.get("route_status") != gate_status:
                    errors.append(
                        "operator_mutation_support_gate.applies_to route "
                        f"{identity} must declare route_status {gate_status} "
                        "when route_status_override is gate_wins_for_applies_to"
                    )


def validate_operator_mutation_support_gate_coverage(
    gate: dict[str, Any],
    routes_by_identity: dict[str, list[dict[str, Any]]],
    route_identities: set[str],
    gate_status: Any,
    route_status_override: Any,
    errors: list[str],
) -> None:
    gate_identities, applies_to_identities = _operator_mutation_gate_identities(
        gate, errors
    )
    drift_identities = _operator_mutation_gate_coverage_drift(gate, errors)
    _validate_operator_mutation_gate_identity_coverage(
        gate_identities,
        drift_identities,
        applies_to_identities,
        route_identities,
        errors,
    )
    _validate_operator_mutation_gate_route_status(
        applies_to_identities,
        routes_by_identity,
        gate_status,
        route_status_override,
        errors,
    )


def validate_operator_mutation_support_gate(
    document: dict[str, Any], routes: list[Any], errors: list[str]
) -> None:
    allowed_overrides = validate_operator_mutation_support_gate_override_vocabulary(
        document, errors
    )

    gate = document.get("operator_mutation_support_gate")
    if not isinstance(gate, dict):
        errors.append("operator_mutation_support_gate must be a mapping")
        return

    route_status_override = gate.get("route_status_override")
    if route_status_override not in allowed_overrides:
        errors.append(
            "operator_mutation_support_gate.route_status_override must be one of "
            f"{sorted(allowed_overrides)}"
        )
    gate_status = gate.get("status")
    if gate_status != OPERATOR_MUTATION_SUPPORT_GATE_STATUS:
        errors.append(
            "operator_mutation_support_gate.status must be "
            f"{OPERATOR_MUTATION_SUPPORT_GATE_STATUS}"
        )

    routes_by_identity: dict[str, list[dict[str, Any]]] = {}
    for route in routes:
        if not isinstance(route, dict):
            continue
        identity = route_identity_from_route(route)
        if identity is not None:
            routes_by_identity.setdefault(identity, []).append(route)
    route_identities = set(routes_by_identity)
    validate_operator_mutation_support_gate_coverage(
        gate,
        routes_by_identity,
        route_identities,
        gate_status,
        route_status_override,
        errors,
    )


def matching_routes(
    routes: list[Any],
    service: str,
    route_name: str,
) -> list[dict[str, Any]]:
    components = canonical_route_components(service, route_name)
    if components is None:
        return []
    return [
        route
        for route in routes
        if isinstance(route, dict)
        and route_set_key(route) == components
    ]


def resolve_unique_route(
    routes: list[Any],
    service: str,
    route_name: str,
    errors: list[str],
    cardinality_errors: set[str] | None = None,
) -> dict[str, Any] | None:
    matches = matching_routes(routes, service, route_name)
    if len(matches) != 1:
        components = canonical_route_components(service, route_name)
        key = (
            f"{components[0]}|{components[1]}"
            if components is not None
            else f"{service}|{route_name}"
        )
        if cardinality_errors is None or key not in cardinality_errors:
            append_unique_error(
                errors,
                f"matrix must contain exactly one {key.replace('|', ' ')} route",
            )
            if cardinality_errors is not None:
                cardinality_errors.add(key)
        return None
    return matches[0]


def applicability_value(
    route: dict[str, Any], key: str, label: str, errors: list[str]
) -> Any:
    applicability = route.get("applicability")
    if not isinstance(applicability, dict):
        return None
    values = []
    if key in applicability:
        values.append(applicability[key])
    clauses = applicability.get("all_of", [])
    if isinstance(clauses, list):
        values.extend(
            clause[key]
            for clause in clauses
            if isinstance(clause, dict) and key in clause
        )
    if not values:
        return None
    if any(value != values[0] for value in values[1:]):
        append_unique_error(
            errors,
            f"{label} has conflicting applicability values for {key}: {values!r}"
        )
        return None
    return values[0]


def route_live_checks(
    route: dict[str, Any],
    label: str,
    errors: list[str],
    live_checks_cache: LiveChecksCache | None = None,
) -> set[str]:
    return cached_live_checks(
        route,
        route.get("required_live_checks"),
        f"{label} required_live_checks",
        errors,
        live_checks_cache,
        "required_live_checks",
    )


def validate_applicability(
    route: dict[str, Any], label: str, expected: dict[str, str], errors: list[str]
) -> None:
    for key, expected_value in expected.items():
        actual_value = applicability_value(route, key, label, errors)
        if actual_value != expected_value:
            append_unique_error(
                errors,
                f"{label} must declare applicability {key}={expected_value!r}"
            )


def validate_downstream_admission_contract(
    route: dict[str, Any],
    label: str,
    errors: list[str],
) -> None:
    contract = route.get("downstream_admission_contract")
    if not isinstance(contract, dict):
        errors.append(f"{label} must declare downstream_admission_contract")
        return
    if contract.get("owner") != "game-session-service":
        errors.append(
            f"{label} downstream_admission_contract must be owned by game-session-service"
        )
    if contract.get("tenant_billing_authority_generation_applies") is not False:
        errors.append(
            f"{label} downstream_admission_contract must disable tenant billing authority generation"
        )
    if contract.get("admission_mode_selection") != "required_fail_closed":
        errors.append(
            f"{label} downstream_admission_contract must require fail-closed admission mode selection"
        )
    selector_inputs = set(
        string_list(
            contract.get("selector_inputs"),
            f"{label} downstream_admission_contract.selector_inputs",
            errors,
        )
    )
    if selector_inputs != REQUIRED_GAMEPLAY_ADMISSION_SELECTOR_INPUTS:
        errors.append(
            f"{label} downstream_admission_contract must declare the exact admission selector inputs"
        )
    mode_branches = set(
        string_list(
            contract.get("required_mode_branches"),
            f"{label} downstream_admission_contract.required_mode_branches",
            errors,
        )
    )
    if mode_branches != REQUIRED_GAMEPLAY_ADMISSION_MODES:
        errors.append(
            f"{label} downstream_admission_contract must declare the exact admission mode branches"
        )


def validate_admission_mode_selection(
    route: dict[str, Any],
    label: str,
    errors: list[str],
    live_checks_cache: LiveChecksCache | None = None,
) -> None:
    selection = route.get("admission_mode_selection")
    if not isinstance(selection, dict):
        errors.append(f"{label} must declare admission_mode_selection")
        return

    route_checks = route_live_checks(route, label, errors, live_checks_cache)
    common_checks = cached_live_checks(
        selection,
        selection.get("required_live_checks"),
        f"{label} admission_mode_selection.required_live_checks",
        errors,
        live_checks_cache,
        "required_live_checks",
    )
    if common_checks != REQUIRED_GAMEPLAY_ADMISSION_COMMON_CHECKS:
        errors.append(
            f"{label} admission_mode_selection.required_live_checks must equal "
            f"{sorted(REQUIRED_GAMEPLAY_ADMISSION_COMMON_CHECKS)}"
        )
    missing_common_checks = sorted(common_checks - route_checks)
    if missing_common_checks:
        errors.append(
            f"{label} required_live_checks is missing common admission checks: "
            f"{missing_common_checks}"
        )
    if selection.get("required_live_checks_composition") != "common_plus_selected_branch":
        errors.append(
            f"{label} admission_mode_selection must declare "
            "required_live_checks_composition=common_plus_selected_branch"
        )

    branches = selection.get("branches")
    if not isinstance(branches, dict):
        errors.append(f"{label} admission_mode_selection.branches must be a mapping")
        return
    if set(branches) != set(REQUIRED_GAMEPLAY_ADMISSION_BRANCH_CHECKS):
        errors.append(
            f"{label} admission_mode_selection.branches must contain exactly "
            f"{sorted(REQUIRED_GAMEPLAY_ADMISSION_BRANCH_CHECKS)}"
        )
    for branch_name, expected_checks in REQUIRED_GAMEPLAY_ADMISSION_BRANCH_CHECKS.items():
        branch = branches.get(branch_name)
        branch_label = f"{label} admission_mode_selection.branches.{branch_name}"
        if not isinstance(branch, dict):
            errors.append(f"{branch_label} must be a mapping")
            continue
        checks = cached_live_checks(
            branch,
            branch.get("required_live_checks"),
            f"{branch_label}.required_live_checks",
            errors,
            live_checks_cache,
            "required_live_checks",
        )
        if checks != expected_checks:
            errors.append(
                f"{branch_label}.required_live_checks must equal "
                f"{sorted(expected_checks)}"
            )
        duplicated = sorted(common_checks & checks)
        if duplicated:
            errors.append(
                f"{branch_label}.required_live_checks must not duplicate common "
                f"admission checks: {duplicated}"
            )


def _ws_game_routes_by_mode(
    routes: list[Any], errors: list[str]
) -> tuple[list[dict[str, Any]], dict[str, list[dict[str, Any]]]]:
    ws_routes = matching_routes(routes, "spring-cloud-gateway", "/ws/game/**")
    by_mode: dict[str, list[dict[str, Any]]] = {}
    for route in ws_routes:
        mode = applicability_value(
            route, "connection_mode", route_label(route), errors
        )
        if isinstance(mode, str):
            by_mode.setdefault(mode, []).append(route)
    return ws_routes, by_mode


def _validate_ws_game_route_generation_fields(
    by_mode: dict[str, list[dict[str, Any]]], errors: list[str]
) -> None:
    for mode in ("first_party_web", "trusted_tcp_proxy"):
        route = by_mode[mode][0]
        label = f"/ws/game/** {mode}"
        for field in (
            "tenant_billing_authority_generation_applies",
            "membership_authority_generation_applies",
        ):
            if route.get(field) is not False:
                errors.append(
                    f"{label} must explicitly set {field}=false for downstream admission"
                )


def _validate_first_party_ws_game_route(
    route: dict[str, Any],
    errors: list[str],
    live_checks_cache: LiveChecksCache | None,
) -> None:
    label = "/ws/game/** first_party_web"
    validate_applicability(
        route,
        route_label(route),
        REQUIRED_FIRST_PARTY_WS_APPLICABILITY,
        errors,
    )
    missing_first_party = sorted(
        REQUIRED_WS_GAME_CHECKS
        - route_live_checks(route, label, errors, live_checks_cache)
    )
    if missing_first_party:
        errors.append(
            f"/ws/game/** is missing required live checks: {missing_first_party}"
        )
    handshake_classes = route.get("handshake_error_classes", {})
    outcomes = (
        handshake_classes.get("any_of", [])
        if isinstance(handshake_classes, dict)
        else []
    )
    if "POLICY_PRESSURE" not in outcomes:
        errors.append("/ws/game/** handshake outcomes must include POLICY_PRESSURE")
    if route.get("issued_token_state") != GAMEPLAY_CONNECT_ISSUED_TOKEN_STATE:
        errors.append(
            "/ws/game/** first_party_web must declare the bounded single-use "
            "gameplay-connect issued-token-state exception"
        )
    if route.get("issuer_authority_generation_applies") is not False:
        errors.append(
            "/ws/game/** first_party_web must explicitly disable "
            "issuer_authority_generation_applies"
        )
    if route.get("account_authority_generation_applies") is not False:
        errors.append(
            "/ws/game/** first_party_web must explicitly disable "
            "account_authority_generation_applies"
        )
    validate_downstream_admission_contract(route, label, errors)


def _validate_trusted_proxy_ws_game_route(
    route: dict[str, Any],
    errors: list[str],
    live_checks_cache: LiveChecksCache | None,
) -> None:
    missing_trusted_proxy = sorted(
        REQUIRED_TRUSTED_PROXY_CHECKS
        - route_live_checks(
            route,
            "/ws/game/** trusted_tcp_proxy",
            errors,
            live_checks_cache,
        )
    )
    if missing_trusted_proxy:
        errors.append(
            "/ws/game/** trusted_tcp_proxy is missing required live checks: "
            f"{missing_trusted_proxy}"
        )
    validate_downstream_admission_contract(
        route,
        "/ws/game/** trusted_tcp_proxy",
        errors,
    )


def _validate_ws_game_route_pair(
    routes: list[Any],
    errors: list[str],
    live_checks_cache: LiveChecksCache | None,
) -> None:
    ws_routes, by_mode = _ws_game_routes_by_mode(routes, errors)
    if len(ws_routes) != 2 or any(
        len(by_mode.get(mode, [])) != 1
        for mode in ("first_party_web", "trusted_tcp_proxy")
    ):
        errors.append(
            "matrix must contain exactly one first_party_web and one trusted_tcp_proxy "
            "spring-cloud-gateway /ws/game/** route"
        )
        return
    _validate_ws_game_route_generation_fields(by_mode, errors)
    _validate_first_party_ws_game_route(
        by_mode["first_party_web"][0], errors, live_checks_cache
    )
    _validate_trusted_proxy_ws_game_route(
        by_mode["trusted_tcp_proxy"][0], errors, live_checks_cache
    )


def _validate_ws_game_revoke_route(
    routes: list[Any],
    errors: list[str],
    live_checks_cache: LiveChecksCache | None,
    cardinality_errors: set[str] | None,
) -> None:

    revoke_route = resolve_unique_route(
        routes,
        "spring-cloud-gateway",
        "POST /ws/game/connect-token/revoke",
        errors,
        cardinality_errors,
    )
    if revoke_route is None:
        return
    label = route_label(revoke_route)
    if revoke_route.get("scope") != "public":
        errors.append(f"{label} must use scope public")
    if revoke_route.get("classification") != "public":
        errors.append(f"{label} must use classification public")
    revoke_checks = route_live_checks(
        revoke_route,
        "POST /ws/game/connect-token/revoke",
        errors,
        live_checks_cache,
    )
    missing_revoke = sorted(
        REQUIRED_CONNECT_TOKEN_REVOKE_CHECKS
        - revoke_checks
    )
    validate_applicability(
        revoke_route,
        route_label(revoke_route),
        REQUIRED_REVOKE_APPLICABILITY,
        errors,
    )
    if missing_revoke:
        errors.append(
            "POST /ws/game/connect-token/revoke is missing required live checks: "
            f"{missing_revoke}"
        )
    unexpected_revoke = sorted(revoke_checks - REQUIRED_CONNECT_TOKEN_REVOKE_CHECKS)
    if unexpected_revoke:
        errors.append(
            "POST /ws/game/connect-token/revoke must require only exact Origin and "
            "anti-CSRF live checks: "
            f"{unexpected_revoke}"
        )


def validate_ws_game_routes(
    routes: list[Any],
    errors: list[str],
    live_checks_cache: LiveChecksCache | None = None,
    cardinality_errors: set[str] | None = None,
) -> None:
    _validate_ws_game_route_pair(routes, errors, live_checks_cache)
    _validate_ws_game_revoke_route(
        routes, errors, live_checks_cache, cardinality_errors
    )


def validate_issue_connect_token(
    routes: list[Any],
    errors: list[str],
    live_checks_cache: LiveChecksCache | None = None,
    cardinality_errors: set[str] | None = None,
) -> None:
    issue_connect_route = resolve_unique_route(
        routes,
        "account-service",
        "IssueConnectToken",
        errors,
        cardinality_errors,
    )
    if issue_connect_route is None:
        return
    checks = route_live_checks(
        issue_connect_route, "IssueConnectToken", errors, live_checks_cache
    )
    for field in (
        "tenant_authority_generation_applies",
        "membership_authority_generation_applies",
        "membership_version_applies",
    ):
        if issue_connect_route.get(field) is not True:
            errors.append(f"IssueConnectToken must declare {field}=true")
    missing_checks = sorted(
        REQUIRED_ISSUE_CONNECT_TOKEN_CHECKS
        - checks
    )
    if missing_checks:
        errors.append(
            f"IssueConnectToken is missing required live checks: {missing_checks}"
        )


def validate_play_generation(
    routes: list[Any],
    errors: list[str],
    live_checks_cache: LiveChecksCache | None = None,
    cardinality_errors: set[str] | None = None,
) -> None:
    route = resolve_unique_route(
        routes,
        "game-session-service",
        "PLAY",
        errors,
        cardinality_errors,
    )
    if route is None:
        return
    label = "game-session-service PLAY"
    if route.get("classification") != "gameplay_admission":
        errors.append(f"{label} must use classification gameplay_admission")
    for field in (
        "tenant_authority_generation_applies",
        "membership_authority_generation_applies",
        "membership_version_applies",
    ):
        if route.get(field) is not True:
            errors.append(f"{label} must declare {field}=true")
    checks = route_live_checks(route, label, errors, live_checks_cache)
    missing_checks = sorted(REQUIRED_PLAY_GENERATION_CHECKS - checks)
    if missing_checks:
        errors.append(
            f"{label} is missing exact selected-tenant generation checks: "
            f"{missing_checks}"
        )


def validate_membership_writer_route(
    routes: list[Any],
    errors: list[str],
    live_checks_cache: LiveChecksCache | None = None,
    cardinality_errors: set[str] | None = None,
) -> None:
    service, name = MEMBERSHIP_WRITER_ROUTE
    route = resolve_unique_route(routes, service, name, errors, cardinality_errors)
    if route is None:
        return
    checks = route_live_checks(
        route,
        f"{service} {name}",
        errors,
        live_checks_cache,
    )
    missing_checks = sorted(REQUIRED_MEMBERSHIP_WRITER_CHECKS - checks)
    if missing_checks:
        errors.append(
            f"{service} {name} is missing membership-writer checks: {missing_checks}"
        )


def validate_connect_token_revoke_generation_applicability(
    routes: list[Any],
    errors: list[str],
    cardinality_errors: set[str] | None = None,
) -> None:
    service = "spring-cloud-gateway"
    name = "POST /ws/game/connect-token/revoke"
    route = resolve_unique_route(routes, service, name, errors, cardinality_errors)
    if route is None:
        return
    label = route_label(route)
    for field, expected in REQUIRED_REVOKE_GENERATION_APPLICABILITY.items():
        if route.get(field) is not expected:
            expected_yaml = (
                str(expected).lower() if isinstance(expected, bool) else expected
            )
            errors.append(f"{label} must explicitly set {field}={expected_yaml}")


def validate_join_routes(
    routes: list[Any],
    errors: list[str],
    live_checks_cache: LiveChecksCache | None = None,
    cardinality_errors: set[str] | None = None,
) -> None:
    for service, name in sorted(JOIN_ROUTES_REQUIRING_POINTER_ERROR):
        route = resolve_unique_route(routes, service, name, errors, cardinality_errors)
        if route is None:
            continue
        canonical_errors = route.get("canonical_errors", {})
        outcomes = (
            canonical_errors.get("any_of", [])
            if isinstance(canonical_errors, dict)
            else []
        )
        if "ADMISSION_POINTER_UNAVAILABLE" not in outcomes:
            errors.append(
                f"{service} {name} must declare ADMISSION_POINTER_UNAVAILABLE"
            )
        checks = route_live_checks(
            route,
            f"{service} {name}",
            errors,
            live_checks_cache,
        )
        missing_checks = sorted(REQUIRED_JOIN_PRE_MEMBERSHIP_CHECKS - checks)
        if missing_checks:
            errors.append(
                f"{service} {name} is missing pre-membership checks: {missing_checks}"
            )
        if route.get("tenant_billing_authority_generation_applies") is not False:
            errors.append(
                f"{service} {name} must disable tenant_billing_authority_generation_applies"
            )
        if route.get("membership_authority_generation_applies") is not False:
            errors.append(
                f"{service} {name} must disable membership_authority_generation_applies"
            )


def validate_delegated_entitlements(
    routes: list[Any],
    errors: list[str],
    live_checks_cache: LiveChecksCache | None = None,
    cardinality_errors: set[str] | None = None,
) -> None:
    entitlement_route = resolve_unique_route(
        routes,
        "account-service",
        "GetTenantEntitlementsForRuntime",
        errors,
        cardinality_errors,
    )
    if entitlement_route is None:
        return
    caller_policies = entitlement_route.get("caller_policies")
    if not isinstance(caller_policies, list):
        errors.append("GetTenantEntitlementsForRuntime caller_policies must be a list")
        return
    game_session_policies = [
        policy
        for policy in caller_policies
        if isinstance(policy, dict) and policy.get("caller") == "game-session-service"
    ]
    if len(game_session_policies) != 1:
        errors.append(
            "GetTenantEntitlementsForRuntime must contain exactly one game-session-service caller policy"
        )
        return
    missing_checks = sorted(
        REQUIRED_DELEGATED_ENTITLEMENT_CHECKS
        - route_live_checks(
            game_session_policies[0],
            "GetTenantEntitlementsForRuntime game-session",
            errors,
            live_checks_cache,
        )
    )
    if missing_checks:
        errors.append(
            "GetTenantEntitlementsForRuntime game-session policy is missing "
            f"required live checks: {missing_checks}"
        )


def validate_live_check_vocabulary(
    document: dict[str, Any],
    errors: list[str],
    live_checks_cache: LiveChecksCache | None = None,
) -> set[str]:
    vocabulary = document.get("required_live_check_vocabulary")
    if (
        not isinstance(vocabulary, list)
        or not vocabulary
        or any(not isinstance(item, str) for item in vocabulary)
    ):
        errors.append(
            "required_live_check_vocabulary must be a non-empty list of strings"
        )
        allowed_checks: set[str] = set()
    else:
        allowed_checks = set(vocabulary)
        if len(allowed_checks) != len(vocabulary):
            errors.append("required_live_check_vocabulary must not contain duplicates")

    live_checks = collect_live_checks(document, "matrix", errors, live_checks_cache)
    unknown_checks = sorted(
        {check for check in live_checks if check not in allowed_checks}
    )
    if unknown_checks:
        errors.append(
            f"required_live_checks contains values outside the closed vocabulary: {unknown_checks}"
        )
    return allowed_checks


def validate_route_variants(
    routes: list[Any], allowed_classifications: set[str], errors: list[str]
) -> set[str]:
    route_variants: dict[str, list[tuple[int, Any]]] = {}
    for index, route in enumerate(routes):
        if not isinstance(route, dict):
            errors.append(f"routes[{index}] must be a mapping")
            continue
        key = route_key(route)
        if key is None:
            errors.append(f"routes[{index}] must declare string service and route")
        else:
            route_variants.setdefault(key, []).append(
                (index, route.get("applicability"))
            )
        classification = route.get("classification")
        if not isinstance(classification, str):
            errors.append(f"routes[{index}] classification must be a string")
        elif classification not in allowed_classifications:
            errors.append(
                f"routes[{index}] uses unknown classification: {classification!r}"
            )

    for key, variants in route_variants.items():
        if len(variants) == 1:
            continue
        if any(applicability is None for _, applicability in variants):
            errors.append(
                f"duplicate route entries require explicit applicability: {key}"
            )
            continue
        serialized = []
        for index, applicability in variants:
            try:
                serialized.append(json.dumps(applicability, sort_keys=True))
            except (TypeError, ValueError) as exc:
                errors.append(
                    f"routes[{index}] duplicate route applicability must be "
                    f"JSON-serializable: {exc}"
                )
        if len(serialized) != len(set(serialized)):
            errors.append(f"duplicate route applicability: {key}")

    return set(route_variants)


def validate_matrix_document(path: Path) -> tuple[list[str], set[str]]:
    errors: list[str] = []
    try:
        document = yaml.safe_load(path.read_text(encoding="utf-8"))
    except (OSError, yaml.YAMLError) as error:
        return [f"cannot read matrix {path}: {error}"], set()

    if not isinstance(document, dict):
        return ["matrix root must be a mapping"], set()

    validate_auth_path_vocabulary(document, errors)
    token_profiles = validate_token_profiles(document, errors)
    role_assurance_predicates = validate_role_assurance(document, errors)
    validate_known_drift(document, "matrix", errors)
    # Keep both caches local to this validation call and therefore to this document.
    live_checks_cache: LiveChecksCache = {}
    required_fields_cache: RequiredFieldsCache = {}
    # Seed structural results before route validators reuse the cache.
    validate_live_check_vocabulary(document, errors, live_checks_cache)
    allowed_route_statuses = validate_route_status_vocabulary(document, errors)

    routes = document.get("routes")
    if not isinstance(routes, list):
        errors.append("routes must be a list")
        routes = []

    validate_operator_mutation_support_gate(document, routes, errors)

    classifications = string_list(
        document.get("classifications"), "classifications", errors
    )
    validate_route_class_branch_table(document, errors)
    validate_authority_evidence_policy(document, errors)
    route_keys = validate_route_variants(routes, set(classifications), errors)
    validate_moderation_policy_contract(document, routes, errors)
    validate_moderation_action_route_variants(routes, errors, live_checks_cache)
    validate_route_statuses(routes, allowed_route_statuses, errors)
    validate_required_fields(routes, errors, required_fields_cache)
    cardinality_errors: set[str] = set()
    validate_capacity_admission_wire_contract(
        routes,
        errors,
        cardinality_errors,
    )
    validate_authority_unavailable_outcomes(routes, errors, cardinality_errors)
    validate_operator_reference_issuance(
        routes,
        errors,
        required_fields_cache,
        cardinality_errors,
    )
    validate_automation_operator_issuance(routes, errors, cardinality_errors)
    validate_human_operator_issuance_branches(
        routes,
        errors,
        cardinality_errors,
        document=document,
    )
    validate_human_operator_issuance_shared_branch_fields(document, errors)
    validate_operator_issuance_identity_contract(document, errors)
    validate_generation_applicability(routes, errors, live_checks_cache)
    validate_account_export_applicability(document, errors)
    validate_account_export_routes(routes, errors, live_checks_cache)
    validate_export_bindings(routes, errors)
    validate_security_lock_export_classification_rule(document, errors)
    validate_character_routes(routes, errors, cardinality_errors)
    validate_character_operation_vocabulary(document, errors)
    validate_selected_character_requirement_vocabulary(document, errors)
    validate_read_only_reporting_routes(routes, errors)
    validate_profile_authority_routes(
        routes, errors, live_checks_cache, cardinality_errors
    )
    validate_refresh_roles_routes(
        routes,
        errors,
        live_checks_cache,
        cardinality_errors,
        required_fields_cache,
    )
    validate_logging_admin_idempotency(
        routes,
        errors,
        cardinality_errors,
        required_fields_cache,
    )
    validate_receiver_predicates(routes, token_profiles, errors)
    validate_explicit_no_jwt_routes(routes, errors)
    validate_role_assurance_references(routes, role_assurance_predicates, errors)
    validate_role_assurance_route_identities(routes, errors)
    validate_tenant_generation_policy(document, routes, errors, live_checks_cache)
    validate_membership_policy(document, errors)
    validate_elevation_bootstrap(document, routes, errors, cardinality_errors)
    validate_entitlement_contract(document, routes, errors, cardinality_errors)

    validate_ws_game_routes(routes, errors, live_checks_cache, cardinality_errors)
    validate_connect_token_revoke_generation_applicability(
        routes, errors, cardinality_errors
    )
    validate_play_generation(routes, errors, live_checks_cache, cardinality_errors)
    validate_issue_connect_token(routes, errors, live_checks_cache, cardinality_errors)
    validate_join_routes(routes, errors, live_checks_cache, cardinality_errors)
    validate_membership_writer_route(
        routes, errors, live_checks_cache, cardinality_errors
    )
    validate_delegated_entitlements(
        routes, errors, live_checks_cache, cardinality_errors
    )

    return errors, route_keys


def validate(matrix_path: Path = DEFAULT_MATRIX) -> list[str]:
    errors, _ = validate_matrix_document(matrix_path)
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--matrix", type=Path, default=DEFAULT_MATRIX)
    args = parser.parse_args()

    errors = validate(args.matrix)
    if errors:
        for error in errors:
            print(f"ERROR: {error}")
        return 1
    print("authorization route matrix validation passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
