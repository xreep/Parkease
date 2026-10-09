-- Which email the driver gets when this refund goes through (null: none, the booking flow sent its own).
-- Kept on the row so a refund that failed first and succeeds on retry can still tell the driver.
ALTER TABLE refunds ADD COLUMN notice VARCHAR(20) CHECK (notice IN ('SLOT_LOST','BOOKING_CLOSED'));
