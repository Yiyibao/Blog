create table ai_action_proposals (
    id uuid primary key,
    owner varchar(128) not null,
    task_id uuid references ai_tasks(id) on delete set null,
    action_type varchar(80) not null,
    target_type varchar(80),
    target_id varchar(128),
    target_version bigint,
    arguments jsonb not null,
    arguments_hash varchar(64) not null,
    nonce_hash varchar(64) not null,
    status varchar(24) not null,
    expires_at timestamp with time zone not null,
    version bigint not null default 0,
    created_at timestamp with time zone not null default now(),
    updated_at timestamp with time zone not null default now(),
    approved_at timestamp with time zone,
    approved_by varchar(128),
    rejected_at timestamp with time zone,
    rejected_by varchar(128),
    rejected_reason varchar(500),
    constraint ck_ai_action_proposal_type check (
        action_type ~ '^[a-z][a-z0-9_.-]{0,79}$'
        and lower(action_type) not like '%publish%'
        and lower(action_type) not like '%delete%'
        and lower(action_type) not like '%schedule%'
    ),
    constraint ck_ai_action_proposal_status check (
        status in ('PROPOSED', 'APPROVED', 'REJECTED', 'EXPIRED', 'CONFLICTED')
    )
);

create index idx_ai_action_proposals_owner_status
    on ai_action_proposals(owner, status, created_at desc);
create index idx_ai_action_proposals_task
    on ai_action_proposals(task_id, created_at desc);
