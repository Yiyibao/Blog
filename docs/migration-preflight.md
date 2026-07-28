# 迁移预检与归档恢复

## 升级前预检（生产 V4 环境）

部署新版 JAR 前在数据库服务器执行：

```bash
# 确认 projects 表存在且有数据
psql -d yubai_blog -c "
SELECT count(*) AS project_rows FROM projects;
SELECT count(*) AS stack_rows FROM project_stack;
SELECT version FROM flyway_schema_history
  WHERE success ORDER BY installed_rank DESC LIMIT 1;
"
```

- 若 `project_rows > 0` 且当前 version 为 `4` 或 `5` … `7`，则 `ProjectArchiveService` 会在 Flyway 迁移前自动归档数据，**部署新 JAR 即可**，无需手工操作。
- 若 `project_rows > 0` 且 version 为 `8` 或更高，**数据已经丢失**——从最近备份恢复 `projects` / `project_stack`。
- 若 `project_rows = 0`，无事可做。

## 归档机制

`FlywayMigrationConfig` 注册了一个 `FlywayMigrationStrategy`，在 `flyway.migrate()` 之前调用 `ProjectArchiveService.archive()`：

1. 检查 `projects` 表是否存在
2. 若存在：创建 `projects_archived` / `project_stack_archived`（`if not exists`），逐行复制数据（`on conflict do nothing`）
3. 若不存在（V8+ 或全新安装）：无操作
4. 任何失败会抛出异常，阻止迁移继续

归档表在 V8 之后仍然保留，为审计和未来迁移提供安全网。

## 验证归档

```bash
psql -d yubai_blog -c "
SELECT id, title, year, status, archived_at
  FROM projects_archived ORDER BY id;
SELECT project_id, technology
  FROM project_stack_archived ORDER BY project_id, sort_order;
"
```

## 从备份恢复已丢失的项目数据

如果 V8 已经运行且数据丢失：

1. 从最近的 `pg_dump` 或 COS 备份恢复：
   ```bash
   pg_restore --dbname=yubai_blog --data-only \
     --table=projects --table=project_stack \
     /var/backups/yubai-blog/yubai_blog-<STAMP>.dump
   ```
2. 重新部署以便归档服务捕获这些行（先于 Flyway 迁移运行）。
3. 确认后执行 `DROP TABLE projects, project_stack;`（手工清理，因为 V8 不会再运行）。

## 恢复的数据流向

```
                        FlywayMigrationStrategy（本笔新增）
V1 创建项目表 ──────────────────────────────────────────► projects_archived
  │                                                      project_stack_archived
  │
  └──► V8 DROP TABLE projects / project_stack（已有，不改）
```

`projects_archived` 和 `project_stack_archived` 是只读归档表，应用代码不引用。它们为审计和未来迁移提供安全网。
