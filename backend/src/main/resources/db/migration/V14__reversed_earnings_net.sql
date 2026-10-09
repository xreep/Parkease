-- A reversed earning is worth nothing: earlier reversals left the net of the refunded booking in place.
UPDATE owner_earnings SET net = 0 WHERE status = 'REVERSED';
