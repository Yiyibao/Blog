# 生产 V37 发布检查点（2026-07-31）

## 完成项

- 迁移前备份 `20260730T170135Z` 已写入本地与 COS；数据库 dump、附件归档和
  SHA-256 清单全部通过验证。
- 生产库从 Flyway V4 升级到 V37，共 36 条成功迁移；V20 为预留空号。
- 旧 `projects` 3 行和 `project_stack` 9 行已归档到只读归档表。
- 当前发布为 `/opt/yubai-blog/releases/release-20260731-bb13454`，
  `current` 已原子切换到该目录。
- 旧 JAR 和旧前端保存在
  `/opt/yubai-blog/releases/release-legacy-20260731-v4`。
- 附件已迁移到 `/opt/yubai-blog/shared/attachments`，应用与备份配置统一使用该路径。
- 前端生产构建从隔离 V37 实例生成 15 篇文章、24 个标签、6 个静态页面和
  2 个 noindex 页面。
- Nginx 已改为服务 `current/frontend/client`，并显式代理 `/rss.xml` 和
  `/actuator/`。
- 上线后备份 `20260730T172216Z` 已完成本地校验、COS 下载回传校验、
  `pg_restore --list` 和附件归档读取。
- PostgreSQL `log_min_duration_statement` 已热加载为 `500ms`。

## 验证结果

- 本地后端：649/649 测试通过。
- 本地前端：492/492 测试通过，类型检查和生产构建通过。
- 生产健康检查、首页、文章、菜谱、搜索、知识图谱、动态文章和标签页面通过。
- 管理 API 匿名访问返回 401；Prometheus 匿名访问返回 401。
- RSS、Sitemap、robots.txt 均由后端正确返回。
- `yubai-blog`、Nginx、PostgreSQL 和备份 timer 均为 active。
- 临时预检数据库、临时服务、预检目录和上传暂存文件均已清理。

## 迁移过程偏差

首次隔离启动时，systemd 临时单元的 `EnvironmentFile` 覆盖了命令行提供的
同名环境变量，候选程序实际连接生产库并完成了 V5–V37。程序随后因 8080
端口占用退出，未替换线上旧进程。迁移前备份已存在，生产健康和核心数据计数
随即复核通过。之后改用“先加载环境文件，再显式 export 临时变量”的启动方式，
在备份副本上重新完成了独立迁移与 API 验证。

隔离恢复还发现 `pg_restore --no-owner` 会使对象归恢复用户所有，导致应用角色
无法执行 Flyway DDL。恢复手册已补充 `--role=yubai_app` 要求。

## 回滚边界

- 受控回滚演练确认旧 V4 JAR 无法在 V37 schema 上启动，因为它仍要求已经移除的
  `project_stack` 表。
- 脚本已自动切回当前 release，健康检查和新 API 烟测通过。
- 因此从下一版本开始可以在兼容 schema 的 release 间做应用级回滚；若要退回 V4，
  必须同时恢复迁移前数据库备份 `20260730T170135Z`。

## 后续项

- `pg_stat_statements` 尚未安装；需要 PostgreSQL 重启，留到独立维护窗口。
- OpenCode 侧车尚未安装。主应用已取消对不存在 unit 的启动依赖，其余 AI 供应商
  和博客功能不受影响。
- 每季度继续随机选择一个 COS 历史批次做隔离恢复抽查。
- NB-9 演示数据分离、评论系统和完整附件回收站继续作为产品增强项，不纳入本次发布。
