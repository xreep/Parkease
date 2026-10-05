-- Public search only ever looks at approved listings; index just those rows.
DROP INDEX idx_listings_lat_lng;
CREATE INDEX idx_listings_approved_lat_lng ON parking_listings (lat, lng) WHERE status = 'APPROVED';

-- Availability checks filter a listing's blocks by end time, and a slot's blocks by slot.
CREATE INDEX idx_blocks_listing_end ON availability_blocks (listing_id, end_time);
CREATE INDEX idx_blocks_slot ON availability_blocks (slot_id);
