-- A refund of a different provider payment than the one the booking is paid with (a second payment captured for the
-- same order is handed back in full); null for refunds of the booking's own payment.
ALTER TABLE refunds ADD COLUMN provider_payment_id VARCHAR(60);
-- Why the provider reported the refund as failed (webhook), for support.
ALTER TABLE refunds ADD COLUMN failure_reason VARCHAR(300);
