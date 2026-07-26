-- AI 模块加固：数据库层保证至多一行默认供应商。
-- setDefault/delete 的"清旧默认→设新默认"是两条语句，应用层非原子，
-- 并发请求可能留下双默认行，导致 findFirstByIsDefaultTrueAndEnabledTrue 结果不确定。
CREATE UNIQUE INDEX ai_providers_single_default_idx ON ai_providers (is_default) WHERE is_default;
