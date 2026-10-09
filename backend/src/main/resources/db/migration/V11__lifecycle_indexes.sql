-- Partial indexes for the per-minute lifecycle, reminder and auto-reject sweeps (each matches its query's predicate).
CREATE INDEX idx_bookings_lifecycle_start ON bookings (start_time) WHERE status = 'CONFIRMED';
CREATE INDEX idx_bookings_lifecycle_end ON bookings (end_time) WHERE status IN ('CONFIRMED', 'ACTIVE');
CREATE INDEX idx_bookings_approval_start ON bookings (start_time) WHERE status = 'AWAITING_APPROVAL';
