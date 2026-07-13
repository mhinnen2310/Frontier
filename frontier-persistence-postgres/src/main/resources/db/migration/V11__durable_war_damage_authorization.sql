ALTER TABLE breach_spends ADD COLUMN actor_id UUID;
ALTER TABLE breach_spends ADD COLUMN damage_id UUID;
ALTER TABLE breach_spends ADD COLUMN effective_multiplier DOUBLE PRECISION NOT NULL DEFAULT 1.0;
ALTER TABLE damage_journal ADD COLUMN charged_breach_points INTEGER NOT NULL DEFAULT 0 CHECK(charged_breach_points >= 0);
ALTER TABLE damage_journal ADD COLUMN authorized_at TIMESTAMPTZ;
CREATE UNIQUE INDEX uq_breach_damage ON breach_spends(damage_id) WHERE damage_id IS NOT NULL;
CREATE INDEX idx_damage_position ON damage_journal(world_id,x,y,z,repair_state);
