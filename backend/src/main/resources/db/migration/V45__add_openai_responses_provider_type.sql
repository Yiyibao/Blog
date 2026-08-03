alter table ai_providers
    drop constraint if exists ai_providers_provider_type_check;

alter table ai_providers
    add constraint ai_providers_provider_type_check
        check (provider_type in ('OPENAI_COMPATIBLE', 'OPENAI_RESPONSES', 'ANTHROPIC', 'OPENCODE_SERVER'));
