-- Atomic provider/day reservations prevent concurrent requests from all passing
-- a read-then-call budget check. ai_usage remains the immutable audit log.
create table ai_daily_budgets (
    provider_id bigint not null references ai_providers(id) on delete cascade,
    usage_date date not null,
    request_count integer not null default 0,
    reserved_tokens bigint not null default 0,
    prompt_tokens bigint not null default 0,
    completion_tokens bigint not null default 0,
    error_count integer not null default 0,
    total_latency_ms bigint not null default 0,
    estimated_cost_micros bigint not null default 0,
    updated_at timestamptz not null default now(),
    primary key (provider_id, usage_date),
    check (request_count >= 0 and reserved_tokens >= 0 and prompt_tokens >= 0
        and completion_tokens >= 0 and error_count >= 0)
);
