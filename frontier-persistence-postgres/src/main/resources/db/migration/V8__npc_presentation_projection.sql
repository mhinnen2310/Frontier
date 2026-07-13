ALTER TABLE workers ADD COLUMN presentation_entity_id UUID UNIQUE;
ALTER TABLE workers ADD COLUMN presentation_bound_at TIMESTAMPTZ;
ALTER TABLE workers ADD COLUMN happiness SMALLINT NOT NULL DEFAULT 70 CHECK(happiness BETWEEN 0 AND 100);
CREATE INDEX idx_worker_materialization ON workers(city_id,state,presentation_entity_id);
