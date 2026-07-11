CREATE SEQUENCE player_command_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE player_command_history (
    id bigint NOT NULL DEFAULT nextval('player_command_history_id_seq'),
    tenant_id bigint NOT NULL,
    game_instance_id bigint NOT NULL,
    character_id bigint NOT NULL,
    command_text text NOT NULL,
    accepted_at timestamp without time zone NOT NULL,
    CONSTRAINT player_command_history_pkey PRIMARY KEY (id)
);

ALTER SEQUENCE player_command_history_id_seq OWNED BY player_command_history.id;

CREATE INDEX idx_player_command_history_scope_order
    ON player_command_history USING btree (tenant_id, game_instance_id, character_id, accepted_at, id);
