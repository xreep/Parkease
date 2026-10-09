-- Refunds of cancellations carry a notice too: the cancellation's own message covers the first attempt, and a refund
-- that failed first is announced when a retry gets it through.
ALTER TABLE refunds DROP CONSTRAINT refunds_notice_check;
ALTER TABLE refunds ADD CONSTRAINT refunds_notice_check CHECK (notice IN ('SLOT_LOST','BOOKING_CLOSED','CANCELLATION'));
