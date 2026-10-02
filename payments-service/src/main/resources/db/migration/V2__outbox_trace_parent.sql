-- W3C traceparent of the span that wrote the row, so the relay can continue the same trace
-- when it publishes the event later. Nullable: rows written with no active span have none.
ALTER TABLE outbox_events ADD COLUMN trace_parent varchar(55);
