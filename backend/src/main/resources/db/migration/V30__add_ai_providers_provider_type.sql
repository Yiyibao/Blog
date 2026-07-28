alter table ai_providers
    add column provider_type varchar(30) not null default 'OPENAI_COMPATIBLE';
alter table ai_providers
    add constraint ai_providers_provider_type_check
        check (provider_type in ('OPENAI_COMPATIBLE', 'OPENCODE_SERVER'));
