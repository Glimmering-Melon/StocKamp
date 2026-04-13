-- Remove old columns and add transaction_date to journal_entries
ALTER TABLE journal_entries DROP COLUMN IF EXISTS price;
ALTER TABLE journal_entries DROP COLUMN IF EXISTS total_value;
ALTER TABLE journal_entries DROP COLUMN IF EXISTS emotion;
ALTER TABLE journal_entries DROP COLUMN IF EXISTS strategy;
ALTER TABLE journal_entries ADD COLUMN IF NOT EXISTS transaction_date DATE;
