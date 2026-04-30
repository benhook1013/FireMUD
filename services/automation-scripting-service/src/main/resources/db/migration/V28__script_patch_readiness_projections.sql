create table if not exists script_patch_readiness_projections (
  id bigserial primary key,
  tenant_id varchar(64) not null,
  script_patch_version varchar(128) not null,
  readiness_status varchar(64) not null,
  status_reason varchar(256) not null,
  superseded_by_script_patch_version varchar(128) not null default '',
  last_changed_at timestamptz not null default now(),
  row_version integer not null default 0,
  constraint uq_script_patch_readiness_projection_scope
    unique (tenant_id, script_patch_version)
);

create index if not exists idx_script_patch_readiness_projection_tenant_status
  on script_patch_readiness_projections (tenant_id, readiness_status, last_changed_at desc);
