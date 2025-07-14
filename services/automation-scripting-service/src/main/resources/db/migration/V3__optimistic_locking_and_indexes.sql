ALTER TABLE scripts ADD COLUMN row_version INT NOT NULL DEFAULT 0;
ALTER TABLE npc_memory ADD COLUMN row_version INT NOT NULL DEFAULT 0;
ALTER TABLE factions ADD COLUMN row_version INT NOT NULL DEFAULT 0;
ALTER TABLE faction_standing ADD COLUMN row_version INT NOT NULL DEFAULT 0;
ALTER TABLE npc_formations ADD COLUMN row_version INT NOT NULL DEFAULT 0;
ALTER TABLE npc_formation_member ADD COLUMN row_version INT NOT NULL DEFAULT 0;

CREATE INDEX idx_scripts_tenant_id ON scripts(tenant_id);
CREATE INDEX idx_npc_memory_tenant_id ON npc_memory(tenant_id);
CREATE INDEX idx_npc_memory_npc_id ON npc_memory(npc_id);
CREATE INDEX idx_factions_tenant_id ON factions(tenant_id);
CREATE INDEX idx_faction_standing_tenant_id ON faction_standing(tenant_id);
CREATE INDEX idx_faction_standing_faction_id ON faction_standing(faction_id);
CREATE INDEX idx_npc_formations_tenant_id ON npc_formations(tenant_id);
CREATE INDEX idx_npc_formations_leader_npc_id ON npc_formations(leader_npc_id);
CREATE INDEX idx_npc_formation_member_formation_id ON npc_formation_member(formation_id);
CREATE INDEX idx_npc_formation_member_npc_id ON npc_formation_member(npc_id);
