-- Payout references typed by admins may be up to 100 characters (the column started out at 60).
ALTER TABLE owner_earnings ALTER COLUMN payout_reference TYPE VARCHAR(100);
