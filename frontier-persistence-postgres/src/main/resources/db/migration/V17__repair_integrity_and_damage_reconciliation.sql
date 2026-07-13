ALTER TABLE damage_journal ADD COLUMN mutation_state VARCHAR(24) NOT NULL DEFAULT 'APPLIED';
ALTER TABLE damage_journal ADD COLUMN generation INTEGER NOT NULL DEFAULT 1 CHECK(generation > 0);
ALTER TABLE damage_journal ADD COLUMN last_authorized_at TIMESTAMPTZ;
ALTER TABLE damage_journal ADD COLUMN archived_at TIMESTAMPTZ;
ALTER TABLE damage_journal ADD COLUMN rejection_reason TEXT;
ALTER TABLE damage_journal ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE repair_orders ADD COLUMN archived_at TIMESTAMPTZ;
DROP INDEX uq_breach_damage;

UPDATE damage_journal SET repair_state='REGISTERED' WHERE repair_state IN ('DAMAGED','UNPLANNED');
UPDATE damage_journal SET repair_state='RESERVED' WHERE repair_state='PLANNED';
UPDATE damage_journal SET repair_state='COMPLETED' WHERE repair_state='REPAIRED';
UPDATE repair_orders SET status='RESERVED' WHERE status='QUEUED';
UPDATE repair_orders SET status='REPAIRING' WHERE status='ACTIVE';

CREATE INDEX idx_damage_reconcile ON damage_journal(mutation_state,authorized_at)
  WHERE mutation_state='AUTHORIZED';
CREATE INDEX idx_repair_archive ON repair_orders(status,created_at)
  WHERE status='COMPLETED';
CREATE INDEX idx_breach_damage_generation ON breach_spends(damage_id,occurred_at)
  WHERE damage_id IS NOT NULL;
