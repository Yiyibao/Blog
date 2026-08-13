# 数据与发布安全基线

本基线自 M5（2026-08-13）生效。生产发布必须先通过不可变迁移清单、完整新鲜备份、恢复可读性、磁盘容量、Flyway 历史和兼容窗口检查；任一检查失败时不得切换 `current` symlink。

## 支持矩阵

| 组件 | 发布基线 | 验证范围 |
| --- | --- | --- |
| Java | Temurin 21 | 本地编译、CI、生产 systemd |
| Spring Boot | 3.5.16 | Maven 锁定版本 |
| Flyway | 11.7.2（Spring Boot 3.5.16 BOM） | V1–V62 fresh、V39–V62、V52–V62、V53–V62 |
| PostgreSQL | 17 | CI/Testcontainers 的正式发布基线 |
| PostgreSQL | 18 | 本机兼容验证；升级生产前仍需独立演练 |
| Node.js | 22 | CI 与生产前端构建 |

`docs/migrations/flyway-content-manifest.sha256` 冻结 V1–V62 的 SHA-256。历史迁移不得修改；新增迁移必须同时追加清单并更新 `deploy/release-compatibility.env`。CI 运行 `node scripts/verify-migration-manifest.mjs`，文件集合、内容或目标版本漂移都会失败。

## Fresh、upgrade 与 rollback

- 真正空 schema 首次启动时执行全部历史迁移，然后仅对该 fresh 安装清除历史演示文章、菜品、分类、音乐和语录，最终保留 V61 空内容基线。
- 已存在 `flyway_schema_history` 的数据库只升级结构，不清理业务数据。
- 非空但没有 Flyway 历史的 schema 会 fail closed，避免把未知数据库误判为 fresh。
- V34/V39 的历史 seed 和 V36 的重复 `add column if not exists` 保持只读；它们是历史事实，不通过改写 checksum 修复。
- V54–V62 为 expand-only：V53 的表和列全部保留，新引用可空或带默认值。代码可回滚到 schema 下限不低于 V53 的上一发行版，但数据库仍保持 V62；代码回滚不伪装成数据库回滚。

## 资源所有权与生命周期

- staged dish asset、dish import、AI upload 和 artifact 都按 owner 隔离，并执行 TTL、数量/字节配额、引用保留和定时清理。
- 同一 owner 的配额检查使用 PostgreSQL transaction advisory lock，防止并发请求同时越过配额。
- 清理计数和失败计数发布到 `blog.resources.cleanup` 与 `blog.resources.cleanup.failures` 指标。
- `resource_storage_audit` 标记 V51/V52 历史双存储、缺失存储和过期未引用资源；V55 的 `NOT VALID` 约束保留可审计历史行，同时拒绝新增非法双存储或无存储记录。

## 备份与恢复边界

每日备份批次包含：

- PostgreSQL custom dump：文章、附件/菜品元数据、AI files/artifacts/memories/tasks/events、provider 配置和知识索引元数据；
- shared storage tar：笔记附件、旧菜品文件、AI uploads、generated images 和 artifacts 的实际字节；
- `storage-inventory-<STAMP>.sha256`：shared storage 中每个文件的逐项 SHA-256；
- `SHA256SUMS-<STAMP>`：整个批次的提交标记；COS 最后上传该文件。

`scripts/restore-and-migrate-drill.ps1` 可恢复数据库与 storage archive，逐文件核对 inventory，并验证 V62、附件回收站、统一媒体元数据、AI 提案、AI budget、AI lifecycle 表、可恢复菜谱任务、知识索引和购物清单快照元数据。数据库 metadata 与 shared storage 必须作为同一批次恢复，不能只恢复其中一侧。

## 发布前置

`deploy/release-preflight.sh` 在上传 release 后、修改权限和切换 symlink 前执行。它使用新 JAR 的只读入口实际运行 Flyway `validate/info`，并强制检查：

- 最近完整备份不超过 26 小时；
- batch 清单、dump catalog、storage tar 和逐文件 inventory 可读；
- release 磁盘至少保留 1 GiB；
- Flyway 没有失败记录，当前 schema 不高于目标版本；
- release 声明为 expand-only，并包含明确的上一代代码 schema 下限；
- SSH host key 只能由 `DEPLOY_SSH_KNOWN_HOSTS` 固定指纹提供，发布期间禁止动态 `ssh-keyscan`。

检查失败发生在外部状态改变之前，因此当前生产 release 保持不变。
