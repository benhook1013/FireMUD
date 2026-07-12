CREATE SEQUENCE resume_transcript_entry_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE resume_transcript_entry (
    id bigint NOT NULL DEFAULT nextval('resume_transcript_entry_id_seq'),
    tenant_id bigint NOT NULL,
    game_instance_id bigint NOT NULL,
    character_id bigint NOT NULL,
    protocol_text text NOT NULL,
    line_count integer NOT NULL,
    byte_size integer NOT NULL,
    appended_at timestamp with time zone NOT NULL,
    output_kind character varying(64),
    replay_policy character varying(64),
    brief_render_policy character varying(64),
    payload_type character varying(128),
    payload_json text,
    CONSTRAINT resume_transcript_entry_pkey PRIMARY KEY (id)
);

ALTER SEQUENCE resume_transcript_entry_id_seq OWNED BY resume_transcript_entry.id;

CREATE INDEX idx_resume_transcript_entry_scope_order
    ON resume_transcript_entry USING btree (tenant_id, game_instance_id, character_id, appended_at, id);
