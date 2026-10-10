-- The admin payment and refund lists sort by creation time and filter refunds by status; refunds are also looked up
-- per payment (the foreign key itself is not indexed). Payments only had their unique keys.
CREATE INDEX idx_payments_created_at ON payments (created_at);
CREATE INDEX idx_refunds_status_created ON refunds (status, created_at);
CREATE INDEX idx_refunds_payment ON refunds (payment_id);
