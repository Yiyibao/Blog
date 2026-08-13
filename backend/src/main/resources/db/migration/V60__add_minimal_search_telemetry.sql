CREATE TABLE search_query_events (
    id UUID PRIMARY KEY,
    query_hash VARCHAR(64) NOT NULL,
    scope VARCHAR(16) NOT NULL,
    result_count INTEGER NOT NULL CHECK (result_count >= 0),
    zero_result BOOLEAN NOT NULL,
    latency_ms INTEGER NOT NULL CHECK (latency_ms >= 0),
    clicked_position INTEGER CHECK (clicked_position IS NULL OR clicked_position > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT search_query_events_scope_check CHECK (scope IN ('PUBLIC', 'PRIVATE', 'AI'))
);

CREATE INDEX search_query_events_created_idx ON search_query_events (created_at);
CREATE INDEX search_query_events_hash_scope_idx ON search_query_events (query_hash, scope);
