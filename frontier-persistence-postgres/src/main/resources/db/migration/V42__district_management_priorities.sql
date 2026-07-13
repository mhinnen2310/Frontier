ALTER TABLE districts
  ADD COLUMN production_priority SMALLINT NOT NULL DEFAULT 50 CHECK(production_priority BETWEEN 0 AND 100),
  ADD COLUMN repair_priority SMALLINT NOT NULL DEFAULT 50 CHECK(repair_priority BETWEEN 0 AND 100);
