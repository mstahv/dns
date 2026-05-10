-- Idempotent schema migrations applied before Hibernate ddl-auto=update.
-- Existing deployments may have the competition table without the stage column.
ALTER TABLE IF EXISTS competition
    ADD COLUMN IF NOT EXISTS stage integer NOT NULL DEFAULT 1;
