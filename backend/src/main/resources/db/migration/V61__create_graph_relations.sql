create table graph_relations (
    id uuid primary key,
    source_id varchar(128) not null,
    target_id varchar(128) not null,
    relation_type varchar(64) not null,
    origin varchar(24) not null,
    created_by varchar(128) not null,
    created_at timestamp with time zone not null default now(),
    updated_at timestamp with time zone not null default now(),
    version bigint not null default 0,
    constraint ck_graph_relations_not_self check (source_id <> target_id),
    constraint ck_graph_relations_type check (relation_type ~ '^[a-z][a-z0-9_.-]{0,63}$'),
    constraint ck_graph_relations_origin check (origin in ('MANUAL', 'SYSTEM', 'AI_APPROVED')),
    constraint uq_graph_relations_identity unique (source_id, target_id, relation_type)
);

create index idx_graph_relations_source on graph_relations(source_id, created_at desc);
create index idx_graph_relations_target on graph_relations(target_id, created_at desc);

create table graph_relation_audits (
    id uuid primary key,
    relation_id uuid,
    source_id varchar(128) not null,
    target_id varchar(128) not null,
    relation_type varchar(64) not null,
    origin varchar(24) not null,
    action varchar(16) not null,
    actor varchar(128) not null,
    relation_version bigint not null,
    created_at timestamp with time zone not null default now(),
    constraint ck_graph_relation_audits_action check (action in ('CREATE', 'UPDATE', 'DELETE'))
);

create index idx_graph_relation_audits_relation
    on graph_relation_audits(relation_id, created_at desc);
