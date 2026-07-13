ALTER TABLE breach_spends ADD COLUMN damage_generation INTEGER;

WITH ranked AS (
  SELECT id, row_number() OVER(PARTITION BY damage_id ORDER BY occurred_at,id)::integer generation
  FROM breach_spends
  WHERE damage_id IS NOT NULL
)
UPDATE breach_spends b
SET damage_generation=ranked.generation
FROM ranked
WHERE ranked.id=b.id;

ALTER TABLE breach_spends
  ADD CONSTRAINT breach_spend_damage_generation_positive
  CHECK(damage_generation IS NULL OR damage_generation > 0);

CREATE UNIQUE INDEX uq_breach_damage_generation
  ON breach_spends(damage_id,damage_generation)
  WHERE damage_id IS NOT NULL;

ALTER TABLE repair_orders ADD COLUMN completed_at TIMESTAMPTZ;
UPDATE repair_orders SET completed_at=created_at WHERE status IN ('COMPLETED','ARCHIVED');
CREATE INDEX idx_repair_completed_archive ON repair_orders(completed_at,id)
  WHERE status='COMPLETED';
