-- How many times the provider refund has been tried (the first try counts); the retry job gives up after 5.
ALTER TABLE refunds ADD COLUMN attempts INT NOT NULL DEFAULT 1;
