alter table recipe_extraction_jobs
    add column idempotency_key uuid not null default gen_random_uuid(),
    add column error_code varchar(64),
    add column lease_owner varchar(128),
    add column lease_until timestamp with time zone,
    add column heartbeat_at timestamp with time zone;

create unique index uq_recipe_extraction_jobs_idempotency
    on recipe_extraction_jobs(idempotency_key);

create index idx_recipe_extraction_jobs_recovery
    on recipe_extraction_jobs(status, lease_until, created_at)
    where status in ('QUEUED', 'RUNNING');

alter table recipe_extraction_jobs
    add constraint ck_recipe_extraction_jobs_status
    check (status in ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED'));

alter table recipe_extraction_jobs
    add constraint ck_recipe_extraction_jobs_attempts
    check (attempts between 0 and 3);
