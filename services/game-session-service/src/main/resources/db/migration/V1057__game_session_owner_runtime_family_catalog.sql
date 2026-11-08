-- Additive local source only. Preserve all payload, original operations, guards and sequences.
CREATE TABLE game_session_runtime_family_classification (
    family_id VARCHAR(127) PRIMARY KEY,
    relation_name VARCHAR(63) NOT NULL,
    component_name VARCHAR(63) NOT NULL DEFAULT '',
    family_role VARCHAR(32) NOT NULL CHECK (family_role IN
        ('RUNTIME_PAYLOAD','SOURCE','CONTROL','AUDIT','PROVENANCE','CATALOG')),
    state_class VARCHAR(16) NOT NULL CHECK (state_class IN ('UNKNOWN','UNRESOLVED','NOT_PAYLOAD')),
    fresh_disposition VARCHAR(48) NOT NULL CHECK (fresh_disposition IN
        ('STATE_CLASS_UNKNOWN_BLOCKING','UNSUPPORTED_SOURCE_BLOCKING','RUNTIME_EVIDENCE_BLOCKING',
         'SOURCE_ONLY','CONTROL_ONLY','AUDIT_ONLY','PROVENANCE_ONLY','CATALOG_ONLY')),
    retention_disposition VARCHAR(32) NOT NULL CHECK (retention_disposition IN
        ('OWNER_POLICY_RETAINED','UNRESOLVED_RETAINED','IMMUTABLE_RETAINED')),
    owner_consequence VARCHAR(1024) NOT NULL,
    UNIQUE (relation_name, component_name),
    CHECK (relation_name ~ '^[a-z][a-z0-9_]{0,62}$'),
    CHECK (component_name = '' OR component_name ~ '^[a-z][a-z0-9_]{0,62}$'),
    CHECK (family_id = relation_name || CASE WHEN component_name = '' THEN '' ELSE '.' || component_name END),
    CHECK (
        (fresh_disposition='STATE_CLASS_UNKNOWN_BLOCKING' AND family_role='RUNTIME_PAYLOAD' AND state_class='UNKNOWN')
        OR (fresh_disposition='UNSUPPORTED_SOURCE_BLOCKING' AND family_role='SOURCE' AND state_class='UNRESOLVED')
        OR (fresh_disposition='RUNTIME_EVIDENCE_BLOCKING' AND family_role IN ('CONTROL','AUDIT','PROVENANCE') AND state_class='NOT_PAYLOAD')
        OR (fresh_disposition='SOURCE_ONLY' AND family_role='SOURCE' AND state_class='NOT_PAYLOAD')
        OR (fresh_disposition='CONTROL_ONLY' AND family_role='CONTROL' AND state_class='NOT_PAYLOAD')
        OR (fresh_disposition='AUDIT_ONLY' AND family_role='AUDIT' AND state_class='NOT_PAYLOAD')
        OR (fresh_disposition='PROVENANCE_ONLY' AND family_role='PROVENANCE' AND state_class='NOT_PAYLOAD')
        OR (fresh_disposition='CATALOG_ONLY' AND family_role='CATALOG' AND state_class='NOT_PAYLOAD')
    ),
    CHECK (family_role <> 'CATALOG' OR retention_disposition='IMMUTABLE_RETAINED')
);

-- Known limitations only. These rows do not claim an exhaustive Redis key census.
CREATE TABLE game_session_runtime_catalog_non_sql_gap (
    gap_id VARCHAR(48) PRIMARY KEY CHECK (gap_id IN
        ('CURRENT_PIN_PROJECTION','ISSUER_AND_ACCOUNT_INDEX_CUSTODY','LEGACY_QUEUE_CLUSTER_SCOPE',
         'REGION_TIMELINE_AND_LEASE','SESSION_CONTROLLER_AND_REPLAY','VOLATILE_STAGE_RECOVERY')),
    disposition VARCHAR(32) NOT NULL CHECK (disposition IN ('UNRESOLVED_BLOCKING','UNSUPPORTED_BLOCKING')),
    owner_consequence VARCHAR(1024) NOT NULL
);

CREATE TABLE game_session_runtime_family_catalog_epoch (
    catalog_epoch BIGINT PRIMARY KEY CHECK (catalog_epoch > 0),
    catalog_bytes BYTEA NOT NULL CHECK (octet_length(catalog_bytes) BETWEEN 1 AND 2097152),
    catalog_digest VARCHAR(71) NOT NULL CHECK (catalog_digest = 'sha256:' || encode(sha256(catalog_bytes), 'hex')),
    installed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE game_session_runtime_family_catalog_snapshot (
    read_request_id UUID PRIMARY KEY CHECK (read_request_id <> '00000000-0000-0000-0000-000000000000'),
    target_namespace VARCHAR(63) NOT NULL CHECK (target_namespace ~ '^[a-z0-9]([-a-z0-9]*[a-z0-9])?$'),
    request_bytes BYTEA NOT NULL CHECK (octet_length(request_bytes) BETWEEN 1 AND 1024),
    request_digest VARCHAR(71) NOT NULL CHECK (request_digest = 'sha256:' || encode(sha256(request_bytes), 'hex')),
    snapshot_id UUID NOT NULL UNIQUE CHECK (snapshot_id <> '00000000-0000-0000-0000-000000000000'),
    catalog_epoch BIGINT NOT NULL REFERENCES game_session_runtime_family_catalog_epoch(catalog_epoch),
    catalog_digest VARCHAR(71) NOT NULL,
    evidence_bytes BYTEA NOT NULL CHECK (octet_length(evidence_bytes) BETWEEN 1 AND 2101248),
    evidence_digest VARCHAR(71) NOT NULL CHECK (evidence_digest = 'sha256:' || encode(sha256(evidence_bytes), 'hex')),
    retained_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

-- POSIX control-character behavior is PostgreSQL-only, not an H2 simulation.
-- [jooq ignore start]
ALTER TABLE game_session_runtime_family_classification ADD CONSTRAINT ck_gs_runtime_family_consequence
    CHECK (length(btrim(owner_consequence)) > 0 AND owner_consequence !~ '[[:cntrl:]]');
ALTER TABLE game_session_runtime_catalog_non_sql_gap ADD CONSTRAINT ck_gs_runtime_gap_consequence
    CHECK (length(btrim(owner_consequence)) > 0 AND owner_consequence !~ '[[:cntrl:]]');
-- [jooq ignore stop]

-- Explicit current dispositions, not a CREATE-only union or a prefix exclusion.
INSERT INTO game_session_runtime_family_classification
    (family_id,relation_name,family_role,state_class,fresh_disposition,retention_disposition,owner_consequence)
VALUES
    ('gameplay_command','gameplay_command','RUNTIME_PAYLOAD','UNKNOWN','STATE_CLASS_UNKNOWN_BLOCKING','UNRESOLVED_RETAINED',
     'Admitted command payload mixes original identity, declared effects and replay result. ADR0122 namespace partition/class and complete durable-first recovery are missing; no inherited command or default S3.'),
    ('remote_followup','remote_followup','RUNTIME_PAYLOAD','UNKNOWN','STATE_CLASS_UNKNOWN_BLOCKING','UNRESOLVED_RETAINED',
     'Pending remote work and payload retain exact origin/target/effect lineage. Complete namespace/pin/final-effect proof and owner replacement class are unresolved; never clone or discard uncertain work.'),
    ('game_manifest','game_manifest','SOURCE','UNRESOLVED','UNSUPPORTED_SOURCE_BLOCKING','UNRESOLVED_RETAINED',
     'Unsupported legacy unqualified version/description rows lack tenant and canonical version lineage. Not build or FRESH authority. Stop old writers and inventory/map retained data before any removal.');

INSERT INTO game_session_runtime_family_classification
    (family_id,relation_name,family_role,state_class,fresh_disposition,retention_disposition,owner_consequence)
SELECT relation_name,relation_name,family_role,'NOT_PAYLOAD','RUNTIME_EVIDENCE_BLOCKING','UNRESOLVED_RETAINED',consequence
FROM (VALUES
    ('game_instances','CONTROL','Concrete execution/status/version row, not durable playable namespace. Stable namespace/scope and genuine private runtime production remain missing; existing public SHARED launch is not private FRESH.'),
    ('feature_flag','CONTROL','Tenant runtime overrides remain operator control, not cloned build defaults or player payload. Genuine protected operator mutation and target-scoped applicability remain separate.'),
    ('script_pin_operation','CONTROL','Exact atomic pin request/result ledger remains owner authority and retained replay history, never inherited target chronology. Complete bounded rollout history and private runtime binding remain gaps.'),
    ('runtime_region_status','CONTROL','Legacy instance/region execution projection is not namespace identity. Retain its epoch/fence and unresolved work; no new timeline or reset inferred from catalog capture.'),
    ('game_session_region_runtime_status','CONTROL','Canonical region timeline/executor source retains current runtime and original protected completion guards. Private runtime/source qualification remains absent; do not allocate a region from schema evidence.'),
    ('game_session_region_timeline_preparation','PROVENANCE','Original non-admitting timeline initialization and actual lease/readback provenance, including created/preseeded origin, remain retained. INITIALIZING absence never permits a new timeline or private readiness.'),
    ('tick_batch','PROVENANCE','Durable batch and selected-work recovery manifest are not FRESH manifests. Namespace and evidence-qualified replay/abandon/re-drive remain incomplete; preserve all nonterminal and restore-reachable lineage under ADR0163.'),
    ('tick_effect','PROVENANCE','Original mutation identity and enclosing batch lineage survive reconciliation. No S3 or terminality follows from age, stale fence or technical failure; complete namespace/participant guards remain gaps.'),
    ('remote_command_coordinator','CONTROL','Durable result/timeout arbitration and origin/target identity are retained authority, not gameplay initialization. Namespace and complete serialization/late-result compensation proof remain unresolved.'),
    ('remote_followup_result','PROVENANCE','Retain original remote result/late-result/replay lineage and terminal immutability. This is not a new playtest result; complete namespace, timeout arbitration and compensation proof remain gaps.'),
    ('player_command_history','AUDIT','Private accepted input history is not copied to a playtest. Current per-instance rows lack namespace, policy generation, maximum-age and purge convergence; existing rows remain retained under owning privacy policy.'),
    ('resume_transcript_entry','AUDIT','Semantic reconnect context is distinct from input history and credentials. Namespace, durable resume episode/generation and logout invalidation remain incomplete; never clone or infer valid resume from retained text.'),
    ('prepared_version_upgrade','CONTROL','Retained version/remap/participant preparation is not complete namespace-qualified owner validation or mapping application. Preserve original outcome; no pointer mutation or cleanup follows.')
) AS dispositions(relation_name,family_role,consequence);

-- Each authority/control family is deliberately named, including installed Saga and old cohorts.
INSERT INTO game_session_runtime_family_classification
    (family_id,relation_name,family_role,state_class,fresh_disposition,retention_disposition,owner_consequence)
SELECT relation_name,relation_name,'CONTROL','NOT_PAYLOAD','CONTROL_ONLY','OWNER_POLICY_RETAINED',consequence
FROM (VALUES
    ('player_command_history_retention_sweep_state','Bounded sweep cursor only; preserve policy and holds. Catalog capture grants no cleanup or privacy-erasure authority.'),
    ('gameplay_tenant_shared_playable_state_namespace','Retained legacy tenant namespace assignment, not a caller-selected replacement namespace or complete canonical runtime binding.'),
    ('gameplay_admission_pointer_identity_backfill_issue','Retained unresolved identity repair issues block qualification; never treat a missing current route as safe removal.'),
    ('gameplay_initial_admission_bind_catalog','Original legacy initial-bind catalog provenance and retention guards remain separate from canonical private preparation.'),
    ('gameplay_initial_admission_bind_attempt','Original request/digest/hold terminal and replay guards remain unchanged. Absence/PENDING is never abort or private authorization.'),
    ('game_session_retained_tenant_association','Original legacy tenant association custody; no tenant allocation or namespace authority is inferred from numeric keys.'),
    ('game_session_retained_tenant_association_legal_hold','Retained legal hold is not gameplay state. Existing hold/release predicates cannot be bypassed by per-mode exclusion.'),
    ('gameplay_published_realm_catalog_identity','Original published realm identity allocation is control evidence; do not clone or reinterpret it as a private target.'),
    ('gameplay_published_tenant_shared_playable_state_namespace','Published shared namespace source remains immutable under its owner; no new namespace or scope is allocated here.'),
    ('game_session_canonical_realm_catalog','Unique canonical realm catalog authority. Includes V1056 private PREPARING projection and original public source; no private runtime, manifest or CLOSED readiness is implied.'),
    ('game_session_tenant_scope_reservation','Exact private numeric tenant-key reservation and old source guards remain retained. Numeric execution/source keys are not canonical tenant or playable namespace identity.'),
    ('game_session_tenant_canonical_claim','Retained canonical-to-private-key claim is owner custody, not caller authority or a runtime initializer.'),
    ('game_session_canonical_closed_admission_pointer_request','Immutable original CLOSED request/catalog/audit result belongs to the existing pointer owner. Public initial CLOSED is not private aggregation; no second pointer is introduced.'),
    ('game_session_canonical_initial_admission_attempt','Original protected/unprotected admission attempt and terminal guards stay retained; never fabricate Human StartSession or adopt a predecessor as new protection.'),
    ('game_session_canonical_initial_admission_intent','Original intent/World hold bytes and Account protection are retained before dispatch. No new actor, hold, credential or admission is issued.'),
    ('gameplay_admission_pointer','Existing canonical pointer plus legacy rows remain sole routing authority. Capture never changes pointerVersion/catalogRevision, opens admission or aggregates private evidence.'),
    ('game_session_start_session_operator_attempt','Original owner request, nonce, exact actor/reference/redemption and monotonic fence remain retained. Expired or uncertain claim cannot rearm or become FRESH authority.'),
    ('game_session_initial_gameplay_login_journal','Original LOGIN request/context/token witness/abandonment/disposal guards remain held. No credential, session, replay authority or live socket custody is copied.'),
    ('game_session_canonical_roster_selection','Original account/session/full-target selection, Entity nonce and immutable receipt remain retained. No actor allocation or manifest is inferred from roster attestation.'),
    ('game_session_canonical_roster_target_fences','Exact shared reference-admission/retirement fence and captured replay horizons remain authoritative. Age or empty queries never release references.'),
    ('game_session_canonical_roster_retirement_transfers','Original retirement/Entity ACK/confirmation lineage survives original removal through existing horizons and holds; catalog evidence grants no disposal.'),
    ('game_session_canonical_binding_inventory_clock','Monotonic inventory source revision is not a catalog epoch or permission to create bindings; retain existing lock order and writer exclusion.'),
    ('game_session_canonical_binding_generation','Namespace/controller generation allocation is retained authority, not copied sessions or playtestStateGeneration.'),
    ('game_session_canonical_gameplay_binding_inventory','Original controller binding/account/full-target and candidate/final receipts remain guarded. No active binding or credentials are cloned.'),
    ('game_session_canonical_binding_transition','Original NEW candidate, lease, irreversible abandonment, negative settlement and final receipt remain exact replay evidence, not FRESH initialization.'),
    ('game_session_canonical_binding_account_index_obligation','Original cross-slot account repair obligation and acknowledgement remain retained until existing closure/retention predicates hold.'),
    ('game_session_canonical_issuer_partition_reservation','Original issuer capacity reservation/fence remains immutable custody; no active session, release or retirement is implied.'),
    ('game_session_canonical_binding_issuer_index_obligation','Exact issuer repair/release/retirement obligation remains linked to original binding and current fence. No inherited index membership.'),
    ('game_session_canonical_binding_region_bridge_obligation','Original region bridge and created/preseeded source/abort lineage remain retained. No new placement, lease or admitting ACK is fabricated.'),
    ('game_session_canonical_binding_legacy_disposition','Explicit held legacy-cohort disposition remains required; empty SQL inventory alone is not complete Redis migration proof.'),
    ('game_session_canonical_account_coverage_control','Account-wide control/protection is not payload or Account authority generation. Existing current-operation and no-active-source predicates remain guarded.'),
    ('game_session_canonical_account_coverage_operation','Original account coverage operation/fence/cohort and readback remain retained; no release from elapsed time.'),
    ('game_session_canonical_account_coverage_snapshot_inventory','Frozen account inventory cohort is original-operation recovery evidence, not current session authority or runtime family enumeration.'),
    ('game_session_canonical_account_coverage_snapshot_transition','Frozen original transition cohort retains exact generations and target; no cloned controllers.'),
    ('game_session_canonical_account_coverage_snapshot_obligation','Frozen account obligation evidence survives supported replay/restore; no synthetic completion.'),
    ('game_session_canonical_account_coverage_snapshot_reservation','Frozen issuer reservation cohort remains protected by original account operation and fence.'),
    ('gs_canonical_account_coverage_snapshot_issuer_obligation','Actual V34.2 renamed issuer-obligation cohort retains original operation lineage; old CREATE spelling is not a live table.'),
    ('gs_canonical_account_coverage_snapshot_region_obligation','Actual V34.2 renamed region-obligation cohort remains retained; no initial region or private runtime is established.'),
    ('game_session_canonical_account_index_acknowledgement','Exact Account-purpose acknowledgement remains distinct from issuer binding-local ACK and final admitting CAS.'),
    ('game_session_canonical_legacy_migration_operation','Original durable migration operation/fence phases remain retained; no legacy source adoption, reset or gameplay enablement.'),
    ('game_session_canonical_legacy_migration_source_snapshot_entry','Frozen source-key cohort is non-authorizing recovery evidence. No secret values, new authority or complete Redis inventory is inferred.'),
    ('game_session_canonical_account_member_repair_control','Cross-slot account member repair ordering/counter remains owner-local control, not Account-owned identity or global admission.'),
    ('game_session_canonical_account_member_repair_operation','Original ADD_OR_RETAIN/physical ACK evidence remains linked to candidate; account member write alone never admits gameplay.'),
    ('game_session_canonical_account_admission_fence_intent','Original acquisition intent and protected source remain held before any CLOSED install. Historical receipt is not current fence coverage.'),
    ('game_session_canonical_account_admission_fence_intent_cohort_ro','Actual PostgreSQL-truncated V1027 cohort table retains original acquisition membership; no fabricated untruncated relation.'),
    ('game_session_canonical_account_admission_fence_intent_barrier_t','Actual PostgreSQL-truncated V1027 target table retains REQUIRED targets and original barrier evidence; absence never completes coverage.'),
    ('game_session_canonical_tenant_gameplay_tag','Opaque SQL-owned Redis slot mapping is topology only, not tenant authority, playable namespace or permission to read/write keys.'),
    ('game_session_canonical_account_closed_barrier_receipt','Exact original CLOSED physical receipt is historical AS-OF evidence, not current protected incarnation or account-wide release authority.'),
    ('game_session_canonical_issuer_layout_bootstrap_intent','Original finite issuer layout/cohort intent remains held; it is not installed anchor, current coverage or admission.'),
    ('game_session_canonical_issuer_layout_bootstrap_control','Issuer layout source control and exact initial operation remain retained separately from live admission control.'),
    ('game_session_canonical_issuer_layout_bootstrap_cohort_row','Frozen issuer cohort binds exact original inventory source; no static or empty registry completeness is inferred.'),
    ('game_session_canonical_issuer_partition_build_plan','Original partition plan and layout identity remain immutable recovery evidence; no physical Redis execution or anchor installation.'),
    ('game_session_canonical_issuer_partition_build_cohort_row','Partition cohort preserves exact original source row/digest and finite member enumeration, not runtime owner enrollment.'),
    ('game_session_canonical_issuer_partition_build_member_work','Original per-member physical work remains pending until native exact readback. No cloned active session or synthetic completion.'),
    ('game_session_canonical_issuer_partition_build_blocker','Retained unsupported/unresolved partition member blockers cannot be waived by omission or catalog classification.'),
    ('game_session_canonical_issuer_bootstrap_member_op_counter','Monotonic member-operation generation counter remains unchanged; not a playable generation or catalog epoch.'),
    ('game_session_canonical_issuer_bootstrap_member_operation','Original schema-v2 bootstrap member bytes and native receipt remain retained; shape-valid data is not current issuer authority.'),
    ('game_session_canonical_issuer_bootstrap_partition_proof','Exact finite physical partition proof is retained installation evidence; no later-commit currentness or environment custody is granted.'),
    ('game_session_canonical_issuer_bootstrap_coverage_counter','Original coverage allocator remains monotonic and retained; no new issuer coverage is manufactured.'),
    ('game_session_canonical_issuer_installed_anchor','Original locally installed issuer anchor requires independent current source/incarnation checks; not a generic authorization capability.'),
    ('game_session_canonical_issuer_binding_local_operation','Original ordinary NEW local issuer registration and current membership references remain retained; no default registration or active binding clone.'),
    ('game_session_canonical_issuer_binding_local_release_operation','Original exact source-linked terminal release receipt remains separate from physical retirement; catalog capture cannot release capacity.'),
    ('game_session_canonical_issuer_partition_mutation_head','Current partition revision/head is an execution fence with original lineage, not a second issuer authority or new catalog epoch.'),
    ('game_session_canonical_issuer_partition_mutation','Immutable expected/successor physical partition mutation lineage remains exact under retry and restore.'),
    ('game_session_canonical_issuer_binding_local_retirement','Original physical retirement retains exact release, capacity and partition fence; no new retention or cleanup permission.'),
    ('game_session_canonical_issuer_bootstrap_supersession','Original stale-bootstrap supersession preserves displaced source and proof; no silent anchor adoption.'),
    ('game_session_canonical_issuer_current_local_operation','Current membership reference is separate from retained operations. Removal requires exact existing retirement proof; no history deletion.'),
    ('game_session_canonical_issuer_binding_local_ack_gen_counter','Separate binding-local acknowledgement allocator remains exact and monotonic; not Account coverage or lifecycle generation.'),
    ('game_session_canonical_issuer_binding_local_acknowledgement','Exact original local ACK binds reservation, anchor, physical field set and currentness; no actor authority or FRESH result.'),
    ('game_session_canonical_issuer_admission_epoch_counter','Distinct live issuer barrier epoch allocator remains unchanged; no catalog epoch or reset authority.'),
    ('game_session_canonical_issuer_admission_control','Current issuer OPEN/cutoff control requires protected source/incarnation and original operation; schema evidence cannot open or release it.'),
    ('game_session_canonical_issuer_admission_operation','Original live issuer cutoff/initial-anchor operation retains exact checkpoint and frozen cohort; no synthetic currentness.'),
    ('game_session_canonical_issuer_admission_cohort_row','Exact frozen live-control cohort/digest is original authority evidence, not gameplay payload or global owner registry.'),
    ('game_session_canonical_issuer_admission_initializer','Original one-key tenant initializer retains exact OPEN payload before dispatch; preparation alone never proves installation.'),
    ('game_session_canonical_issuer_admission_initializer_receipt','Exact original OPEN readback receipt is retained as installation evidence, not current admission or stable-incarnation custody.'),
    ('game_session_canonical_issuer_admission_cutoff_target','Frozen REQUIRED/CLOSED cutoff target and original physical postimage remain protected; missing targets never imply completion.'),
    ('game_session_canonical_initial_account_barrier_initializer','Original LOGIN-bound INITIAL-only barrier source remains distinct from account-wide acquisition; no token, lease or finalization authority.'),
    ('game_session_canonical_initial_account_barrier_target','Original never-dispatched/dispatch-possible/OPEN-installed target retains protected observation and exact readback; no target retargeting.'),
    ('game_session_direct_text_authenticated_lobby_scope','Original pre-admission LOGIN-linked lobby scope retains verified witness, not credentials or live transport custody. Capacity and lawful release remain held.'),
    ('game_session_direct_text_authenticated_lobby_current','Current lobby UI selection cannot erase older uncertain JOIN originals or authorize tenant admission.'),
    ('game_session_direct_text_authenticated_lobby_join','Original frozen JOIN request/reply and uncertain dispatch remain immutable under existing parent abandonment/retention guards.'),
    ('game_session_initial_playtest_preparations','V1054 exact FRESH lifecycle/namespace/generation-one policy/build preparation remains PREPARING, non-admissible and retained; not a private runtime or manifest.'),
    ('game_session_private_playtest_selections','V1055 immutable selection CAS receipt retains original preparation and head revision; historical exact replay does not select a newer lifecycle.'),
    ('game_session_current_private_playtests','V1055 internal current-preparation head is not admission pointer or player catalog. PREPARING remains invisible, never CLOSED or ready.'),
    ('saga_instance','Installed adopter-local Saga control state remains under existing owner retention; not gameplay payload, Temporal lifecycle authority or FRESH enrollment.'),
    ('saga_step','Installed Saga step/recovery lineage follows its parent and supported retry/restore obligations; no cloning or cleanup permission.')
) AS dispositions(relation_name,consequence);

INSERT INTO game_session_runtime_family_classification
    (family_id,relation_name,family_role,state_class,fresh_disposition,retention_disposition,owner_consequence)
SELECT relation_name,relation_name,family_role,'NOT_PAYLOAD',fresh_disposition,'OWNER_POLICY_RETAINED',consequence
FROM (VALUES
    ('game_session_retained_tenant_association_payload','PROVENANCE','PROVENANCE_ONLY','Original retained identity-bearing payload remains under purpose/hold policy; not copied active authority or unbounded retention permission.'),
    ('gameplay_published_realm_catalog_snapshot','SOURCE','SOURCE_ONLY','Immutable complete published policy snapshot is source evidence, not runtime initialization or owner manifest.'),
    ('gameplay_published_realm_catalog_entry','SOURCE','SOURCE_ONLY','Exact retained published entry keeps original source lineage; no mutable runtime state or private admission is inferred.'),
    ('game_session_authored_world_tenant_source_binding','SOURCE','SOURCE_ONLY','Original canonical tenant/world source binding belongs to the authored intake owner, not runtime allocation.'),
    ('game_session_authored_world_source_intake','SOURCE','SOURCE_ONLY','Exact authenticated authored-world intake and receipt are retained upstream source evidence, not FRESH actor or materialization authority.'),
    ('game_session_canonical_launch_preparation','PROVENANCE','PROVENANCE_ONLY','Retained exact descriptor resolve/readback is evidence-only preparation; no runtime, activation, pointer or admission effects.'),
    ('game_session_fresh_tenant_association','PROVENANCE','PROVENANCE_ONLY','Original canonical-to-private tenant key association is retained custody; no tenant, runtime or namespace is allocated by this catalog.'),
    ('game_session_canonical_instance_launch','PROVENANCE','PROVENANCE_ONLY','Original complete launch/runtime association remains public SHARED-only. Do not relabel Human StartSession or fixture provenance as private FRESH execution.'),
    ('game_session_start_session_template_association_pin','SOURCE','SOURCE_ONLY','Original selected Game Design association pin prevents reselection under retry. It is not a new runtime authority or FRESH source operation.'),
    ('game_session_start_session_launch_descriptor_pin','SOURCE','SOURCE_ONLY','Original immutable descriptor outcome remains tied to exact owner claim and association; no private manifest or current authorization.'),
    ('gs_original_start_session_authored_actor_core_retention','SOURCE','SOURCE_ONLY','Original public authored-core result remains protected source lineage. No actor creation/assignment or private policy is inferred.'),
    ('game_session_canonical_human_runtime_completion','PROVENANCE','PROVENANCE_ONLY','Original protected STARTING-to-RUNNING row versions and Account protection remain immutable provenance, not first OPEN or private runtime creation.'),
    ('gameplay_admission_pointer_event','AUDIT','AUDIT_ONLY','Existing append-only pointer/catalog/attempt audit remains unchanged and retained. Source audit history is never cloned into active playtest authority.')
) AS dispositions(relation_name,family_role,fresh_disposition,consequence);

-- Meaningful mixed components remain separate from whole-table control/recovery disposition.
INSERT INTO game_session_runtime_family_classification
    (family_id,relation_name,component_name,family_role,state_class,fresh_disposition,retention_disposition,owner_consequence)
VALUES
    ('game_instances.script_patch_version','game_instances','script_patch_version','CONTROL','NOT_PAYLOAD','CONTROL_ONLY','OWNER_POLICY_RETAINED','Game Session exact pin tuple is authority, not Automation observation. UNPINNED requires all tuple fields absent; no pin is generated for census.'),
    ('game_instances.owner_account_id','game_instances','owner_account_id','PROVENANCE','NOT_PAYLOAD','PROVENANCE_ONLY','OWNER_POLICY_RETAINED','Legacy numeric owner projection is not Account identity, tenantAdmin authority or private playtest authorization.'),
    ('gameplay_command.declared_effects_json','gameplay_command','declared_effects_json','PROVENANCE','NOT_PAYLOAD','RUNTIME_EVIDENCE_BLOCKING','UNRESOLVED_RETAINED','Retained declared-effect snapshot is narrower than complete immutable root/participant/digest and namespace allocation proof; do not manufacture a manifest.'),
    ('gameplay_command.command_text','gameplay_command','command_text','RUNTIME_PAYLOAD','UNKNOWN','STATE_CLASS_UNKNOWN_BLOCKING','UNRESOLVED_RETAINED','Diagnostic/accepted command payload and correctness receipt have different ADR0163 minimization predicates; no blanket deletion or inheritance.'),
    ('tick_batch.selected_work_manifest_json','tick_batch','selected_work_manifest_json','PROVENANCE','NOT_PAYLOAD','RUNTIME_EVIDENCE_BLOCKING','UNRESOLVED_RETAINED','This selected-work recovery document is not a FRESH mode manifest or shared source snapshot fence.'),
    ('remote_followup.read_snapshot_token','remote_followup','read_snapshot_token','PROVENANCE','NOT_PAYLOAD','RUNTIME_EVIDENCE_BLOCKING','UNRESOLVED_RETAINED','Legacy owner read token is not complete handler input/causal-floor authority or cross-owner consistent snapshot.'),
    ('game_session_canonical_realm_catalog.private_preparation_request_id','game_session_canonical_realm_catalog','private_preparation_request_id','PROVENANCE','NOT_PAYLOAD','PROVENANCE_ONLY','OWNER_POLICY_RETAINED','Actual V1056 private catalog branch retains exact V1054 preparation, policy/build, new realm UUID and commit-time expiry guard. PREPARING is not runtime/CLOSED.'),
    ('game_session_canonical_binding_transition.admission_request_id','game_session_canonical_binding_transition','admission_request_id','CONTROL','NOT_PAYLOAD','CONTROL_ONLY','OWNER_POLICY_RETAINED','Original admission request/nonce/fence remains retained replay authority, not a mode-specific copied payload or new authorization.');

INSERT INTO game_session_runtime_family_classification
    (family_id,relation_name,family_role,state_class,fresh_disposition,retention_disposition,owner_consequence)
SELECT relation_name,relation_name,'CATALOG','NOT_PAYLOAD','CATALOG_ONLY','IMMUTABLE_RETAINED',consequence
FROM (VALUES
    ('game_session_runtime_family_classification','Exact explicit installed dispositions. Unknown class is blocking, not S3, owner enrollment or cleanup permission.'),
    ('game_session_runtime_catalog_non_sql_gap','Known non-SQL gaps only, not a complete Redis inventory or fabricated SQL session/tick/lease rows.'),
    ('game_session_runtime_family_catalog_epoch','Immutable monotonic local schema/classification/history seal, not a global registry revision or currentness through another commit.'),
    ('game_session_runtime_family_catalog_snapshot','Immutable exact request/namespace/snapshot/catalog bytes and digests; no TTL, gameplay allocation, FRESH manifest or actor authority.')
) AS dispositions(relation_name,consequence);

INSERT INTO game_session_runtime_catalog_non_sql_gap VALUES
    ('CURRENT_PIN_PROJECTION','UNRESOLVED_BLOCKING','Redis current runtime/script projections do not replace durable Game Session pin authority; final exact tuple and full remote propagation remain separate. SQL census proves no Redis currentness.'),
    ('ISSUER_AND_ACCOUNT_INDEX_CUSTODY','UNRESOLVED_BLOCKING','Manually composed SQL/Redis index, anchor, repair, release and retirement sources remain unregistered. Operational protected incarnation, Account source and complete index coverage/custody are not supplied.'),
    ('LEGACY_QUEUE_CLUSTER_SCOPE','UNRESOLVED_BLOCKING','Live gamesession:tick and sessionctx key builders/multi-key scripts lack target tagged cluster-safe owner registration and exact reset/readback proof. This is not a full Redis inventory.'),
    ('REGION_TIMELINE_AND_LEASE','UNRESOLVED_BLOCKING','Actual Redis timeline, tick metadata, executor lease and bridge state are outside SQL census. Original INITIALIZING ambiguity remains fenced; missing keys do not authorize reset/reinitialization.'),
    ('SESSION_CONTROLLER_AND_REPLAY','UNRESOLVED_BLOCKING','Actual session:game/sessionctx, controller/index, credential and token replay state must not be cloned. Namespace migration, hidden recovery, private expiry and current Account authority remain separately qualified.'),
    ('VOLATILE_STAGE_RECOVERY','UNSUPPORTED_BLOCKING','Live queue STAGED/pending Redis-first windows lack complete durable source-claim recovery. Missing batch/effect evidence is not proof of no application, target emptiness or safe S3 discard.');

-- PostgreSQL census/guards only; ordinary table DDL remains visible to jOOQ.
-- [jooq ignore start]
INSERT INTO game_session_runtime_family_classification
    (family_id,relation_name,family_role,state_class,fresh_disposition,retention_disposition,owner_consequence)
VALUES ('${flyway:table}','${flyway:table}','CONTROL','NOT_PAYLOAD','CONTROL_ONLY','OWNER_POLICY_RETAINED',
    'Actual configured Game Session Flyway history is retained installation evidence, not payload, build authority or runtime enrollment.');

CREATE FUNCTION gs_runtime_family_catalog_installed() RETURNS BOOLEAN
LANGUAGE SQL STABLE SET search_path = "${serviceSchema}", pg_catalog AS $$
    SELECT count(*)=1 AND coalesce(bool_and(type='SQL'
        AND script='V1057__game_session_owner_runtime_family_catalog.sql' AND success),false)
    FROM "${flyway:table}" WHERE version='1057';
$$;

CREATE FUNCTION gs_runtime_family_catalog_bytes(p_epoch BIGINT) RETURNS BYTEA
LANGUAGE plpgsql STABLE SET search_path = "${serviceSchema}", pg_catalog AS $$
DECLARE v_families JSONB; v_facts JSONB; v_gaps JSONB; v_bytes BYTEA;
BEGIN
    IF p_epoch IS NULL OR p_epoch<=0 THEN
        RAISE EXCEPTION 'Positive Game Session catalog epoch required' USING ERRCODE='23514';
    END IF;
    IF EXISTS (
        SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
        WHERE n.nspname='${serviceSchema}' AND c.relkind IN ('r','p','f')
        EXCEPT SELECT relation_name FROM game_session_runtime_family_classification WHERE component_name=''
    ) OR EXISTS (
        SELECT 1 FROM game_session_runtime_family_classification f
        LEFT JOIN pg_namespace n ON n.nspname='${serviceSchema}'
        LEFT JOIN pg_class c ON c.relnamespace=n.oid AND c.relname=f.relation_name AND c.relkind IN ('r','p','f')
        WHERE c.oid IS NULL OR (f.component_name<>'' AND NOT EXISTS (
            SELECT 1 FROM pg_attribute a WHERE a.attrelid=c.oid AND a.attname=f.component_name
            AND a.attnum>0 AND NOT a.attisdropped))
    ) THEN
        RAISE EXCEPTION 'Unclassified or changed Game Session schema family' USING ERRCODE='23514';
    END IF;
    SELECT jsonb_agg(jsonb_build_object('familyId',family_id,'relation',relation_name,'component',component_name,
        'role',family_role,'stateClass',state_class,'freshDisposition',fresh_disposition,
        'retentionDisposition',retention_disposition,'ownerConsequence',owner_consequence)
        ORDER BY family_id COLLATE "C") INTO v_families FROM game_session_runtime_family_classification;
    SELECT jsonb_agg(jsonb_build_object('id',gap_id,'disposition',disposition,'ownerConsequence',owner_consequence)
        ORDER BY gap_id COLLATE "C") INTO v_gaps FROM game_session_runtime_catalog_non_sql_gap;

    -- Definitions only, not rows, OIDs, sequence positions, statistics or installation timestamps.
    WITH relations AS (
        SELECT c.* FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='${serviceSchema}'
    ), definitions(kind,identity,definition) AS (
        SELECT 'TABLE',c.relname::TEXT,jsonb_build_array(c.relkind,c.relpersistence,
            c.relrowsecurity,c.relforcerowsecurity,c.reloptions,pg_get_partkeydef(c.oid))::TEXT
        FROM relations c WHERE c.relkind IN ('r','p','f')
        UNION ALL
        SELECT 'COLUMN',c.relname||'.'||a.attname,jsonb_build_array(a.attnum,
            format_type(a.atttypid,a.atttypmod),a.attnotnull,a.attidentity,a.attgenerated,
            pg_get_expr(d.adbin,d.adrelid),co.collname)::TEXT
        FROM relations c JOIN pg_attribute a ON a.attrelid=c.oid
        LEFT JOIN pg_attrdef d ON d.adrelid=c.oid AND d.adnum=a.attnum
        LEFT JOIN pg_collation co ON co.oid=a.attcollation
        WHERE c.relkind IN ('r','p','f','v','m') AND a.attnum>0 AND NOT a.attisdropped
        UNION ALL
        SELECT 'CONSTRAINT',c.relname||'.'||k.conname,
            jsonb_build_array(pg_get_constraintdef(k.oid,true),k.convalidated,k.condeferrable,k.condeferred)::TEXT
        FROM relations c JOIN pg_constraint k ON k.conrelid=c.oid
        UNION ALL
        SELECT 'INDEX',c.relname::TEXT,jsonb_build_array(pg_get_indexdef(c.oid),i.indisvalid,i.indisready)::TEXT
        FROM relations c JOIN pg_index i ON i.indexrelid=c.oid
        UNION ALL
        SELECT 'TRIGGER',c.relname||'.'||t.tgname,jsonb_build_array(pg_get_triggerdef(t.oid,true),t.tgenabled)::TEXT
        FROM relations c JOIN pg_trigger t ON t.tgrelid=c.oid WHERE NOT t.tgisinternal
        UNION ALL
        SELECT 'POLICY',c.relname||'.'||p.polname,jsonb_build_array(p.polcmd,p.polpermissive,
            (SELECT jsonb_agg(r.rolname ORDER BY r.rolname COLLATE "C") FROM pg_roles r WHERE r.oid=ANY(p.polroles)),
            pg_get_expr(p.polqual,p.polrelid),pg_get_expr(p.polwithcheck,p.polrelid))::TEXT
        FROM relations c JOIN pg_policy p ON p.polrelid=c.oid
        UNION ALL
        SELECT 'RULE',c.relname||'.'||r.rulename,pg_get_ruledef(r.oid,true)
        FROM relations c JOIN pg_rewrite r ON r.ev_class=c.oid
        UNION ALL
        SELECT 'ROUTINE',p.proname||'('||pg_get_function_identity_arguments(p.oid)||')',pg_get_functiondef(p.oid)
        FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
        WHERE n.nspname='${serviceSchema}' AND p.prokind IN ('f','p')
        UNION ALL
        SELECT 'SEQUENCE',c.relname::TEXT,(to_jsonb(s)-'seqrelid')::TEXT
        FROM relations c JOIN pg_sequence s ON s.seqrelid=c.oid
        UNION ALL
        SELECT 'VIEW',c.relname::TEXT,pg_get_viewdef(c.oid,true) FROM relations c WHERE c.relkind IN ('v','m')
        UNION ALL
        SELECT 'TYPE',t.typname::TEXT,jsonb_build_array(t.typtype,t.typnotnull,t.typdefault,
            (SELECT jsonb_agg(e.enumlabel ORDER BY e.enumsortorder) FROM pg_enum e WHERE e.enumtypid=t.oid))::TEXT
        FROM pg_type t JOIN pg_namespace n ON n.oid=t.typnamespace WHERE n.nspname='${serviceSchema}'
        UNION ALL
        SELECT 'MIGRATION',installed_rank::TEXT||':'||coalesce(version,'repeatable')||':'||script,
            jsonb_build_array(version,type,script,checksum,success)::TEXT
        FROM "${flyway:table}" WHERE version IS DISTINCT FROM '1057'
        UNION ALL
        -- Flyway writes our own history row after this transaction. Bind a stable installation
        -- marker and exact installed definitions, NOT an invented own-row checksum. installed()
        -- separately requires the later successful exact SQL script. Prior/later rows bind above.
        SELECT 'MIGRATION','catalog-installation:1057','V1057__game_session_owner_runtime_family_catalog.sql'
    )
    SELECT jsonb_agg(jsonb_build_object('kind',kind,'identity',identity,
        'definitionDigest','sha256:'||encode(sha256(convert_to(definition,'UTF8')),'hex'))
        ORDER BY (kind||'/'||identity) COLLATE "C") INTO v_facts FROM definitions;
    IF v_families IS NULL OR v_facts IS NULL OR v_gaps IS NULL
        OR jsonb_array_length(v_families)>512 OR jsonb_array_length(v_facts)>16384
        OR jsonb_array_length(v_gaps)>32 THEN
        RAISE EXCEPTION 'Complete Game Session catalog exceeds source bounds' USING ERRCODE='23514';
    END IF;
    v_bytes := convert_to(jsonb_build_object('schema','game-session-runtime-family-catalog/v1',
        'owner','game-session-service','catalogEpoch',p_epoch::TEXT,
        'families',v_families,'schemaFacts',v_facts,'nonSqlGaps',v_gaps)::TEXT,'UTF8');
    IF octet_length(v_bytes)>2097152 THEN
        RAISE EXCEPTION 'Complete Game Session catalog exceeds byte bound' USING ERRCODE='23514';
    END IF;
    RETURN v_bytes;
END;
$$;

CREATE FUNCTION gs_runtime_catalog_immutable() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Game Session runtime catalog evidence is immutable' USING ERRCODE='23514';
END;
$$;
CREATE TRIGGER gs_runtime_classification_retained BEFORE UPDATE OR DELETE OR TRUNCATE
ON game_session_runtime_family_classification FOR EACH STATEMENT EXECUTE FUNCTION gs_runtime_catalog_immutable();
CREATE TRIGGER gs_runtime_non_sql_gap_retained BEFORE UPDATE OR DELETE OR TRUNCATE
ON game_session_runtime_catalog_non_sql_gap FOR EACH STATEMENT EXECUTE FUNCTION gs_runtime_catalog_immutable();
CREATE TRIGGER gs_runtime_epoch_retained BEFORE UPDATE OR DELETE OR TRUNCATE
ON game_session_runtime_family_catalog_epoch FOR EACH STATEMENT EXECUTE FUNCTION gs_runtime_catalog_immutable();
CREATE TRIGGER gs_runtime_snapshot_retained BEFORE UPDATE OR DELETE OR TRUNCATE
ON game_session_runtime_family_catalog_snapshot FOR EACH STATEMENT EXECUTE FUNCTION gs_runtime_catalog_immutable();

CREATE FUNCTION gs_runtime_catalog_epoch_guard() RETURNS TRIGGER LANGUAGE plpgsql
SET search_path = "${serviceSchema}", pg_catalog AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended('${serviceSchema}:gs-runtime-catalog-epoch',0));
    IF NEW.catalog_epoch<>coalesce((SELECT max(catalog_epoch) FROM game_session_runtime_family_catalog_epoch),0)+1
        OR NEW.catalog_bytes<>gs_runtime_family_catalog_bytes(NEW.catalog_epoch) THEN
        RAISE EXCEPTION 'Game Session epoch must bind the exact classified installed schema' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER gs_runtime_epoch_exact BEFORE INSERT ON game_session_runtime_family_catalog_epoch
FOR EACH ROW EXECUTE FUNCTION gs_runtime_catalog_epoch_guard();

CREATE FUNCTION gs_runtime_catalog_snapshot_guard() RETURNS TRIGGER LANGUAGE plpgsql
SET search_path = "${serviceSchema}", pg_catalog AS $$
DECLARE v_epoch game_session_runtime_family_catalog_epoch%ROWTYPE; v_request BYTEA; v_evidence BYTEA;
BEGIN
    SELECT * INTO STRICT v_epoch FROM game_session_runtime_family_catalog_epoch ORDER BY catalog_epoch DESC LIMIT 1;
    IF NOT gs_runtime_family_catalog_installed()
        OR NEW.catalog_epoch<>v_epoch.catalog_epoch OR NEW.catalog_digest<>v_epoch.catalog_digest
        OR gs_runtime_family_catalog_bytes(v_epoch.catalog_epoch)<>v_epoch.catalog_bytes THEN
        RAISE EXCEPTION 'Game Session runtime catalog changed; new owner epoch required' USING ERRCODE='23514';
    END IF;
    -- Purpose-specific GCR1/GCE1 frames. Owner and epoch are bound inside exact catalog bytes.
    v_request := decode('47435231','hex') || uuid_send(NEW.read_request_id)
        || int4send(octet_length(convert_to(NEW.target_namespace,'UTF8'))) || convert_to(NEW.target_namespace,'UTF8');
    v_evidence := decode('47434531','hex') || int4send(octet_length(v_request)) || v_request
        || uuid_send(NEW.snapshot_id) || int4send(octet_length(convert_to(v_epoch.catalog_digest,'UTF8')))
        || convert_to(v_epoch.catalog_digest,'UTF8') || int4send(octet_length(v_epoch.catalog_bytes)) || v_epoch.catalog_bytes;
    IF NEW.request_bytes<>v_request OR NEW.evidence_bytes<>v_evidence THEN
        RAISE EXCEPTION 'Game Session snapshot must bind complete exact request and evidence' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER gs_runtime_snapshot_current BEFORE INSERT ON game_session_runtime_family_catalog_snapshot
FOR EACH ROW EXECUTE FUNCTION gs_runtime_catalog_snapshot_guard();
-- [jooq ignore stop]

-- PostgreSQL-only seal; deliberately no embedded pg_catalog simulation.
-- [jooq ignore start]
INSERT INTO game_session_runtime_family_catalog_epoch(catalog_epoch,catalog_bytes,catalog_digest)
SELECT 1,bytes,'sha256:'||encode(sha256(bytes),'hex')
FROM (SELECT gs_runtime_family_catalog_bytes(1) AS bytes) seal;
-- [jooq ignore stop]
