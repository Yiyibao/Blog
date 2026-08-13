# BlogDemo 年度系统审计（截至 2026-08-13 验收批次）

> 这份文档是路线图 M12 的收口报告草案。路线图名义窗口为 2027-07；本次在 2026-08-13 对当前仓库提前完成可执行的本地/隔离验收。生产状态仍以外部发布门为准。

## 结论

结果：本地年度验收完成；Production GA 未开放。

本次通过的硬证据：

- Flyway manifest：61 个不可变迁移文件至 V62；隔离恢复从备份恢复 DB 与 storage archive，90 个文件逐项 SHA-256 校验，posts/dishes 数量不变，Flyway 保持 V62，query audit 通过；同一 restore drill 实测 elapsed 8.8 秒。
- 后端：`mvn -q verify` 通过；107 个 Surefire XML，817 tests，0 failures/errors/skipped。
- 前端：75 files / 835 tests 全部通过；coverage statements 61.98%、branches 55.37%、functions 51.70%、lines 64.68%。这低于年度目标 72/68/65/74%，因此不伪装为达标。
- 无障碍：公开关键页 Chromium 14/14、Firefox 9/9；认证 route audit 在 Chromium 与 Firefox 各 1/1（包含 6 个路由），整个隔离 full-stack suite 10/10；每条路径执行 Tab 聚焦检查，Axe WCAG 2A/2AA serious/critical 过滤结果为 0。范围覆盖公开站点、登录页、后台总览/笔记/AI/附件/媒体/图谱和厨房入口；`ApprovalCard` 在当前实现中不存在独立组件，使用 AI 提案/MemoryPanel/ArtifactCard 的实际页面与契约覆盖。
- 前端门禁：lint、format、typecheck、OpenAPI 类型无漂移、构建 budget、offline E2E 14/14、full-stack deterministic fake provider E2E 10/10 通过。构建产物为 49 JS、1,500,807 bytes、PWA precache 100 entries/970.87 KiB；code-highlight 约 815 KiB 的既有 chunk warning 仍记录但未越过预算。
- 依赖：`npm audit --omit=dev --audit-level=high` 0 vulnerabilities；Maven runtime dependency tree 未发现本批次变更的高风险项。gitleaks/syft/trivy/grype 本机不可用，故未把 secret/SBOM/container scan 写成已执行。

## M12 项目逐项核对

| 要求 | 本次状态 | 证据/限制 |
| --- | --- | --- |
| 隔离 DB、附件、菜品图、AI 文件/产物/记忆/任务/事件恢复 | 通过（本地） | `outputs/m12-annual-20260813/` 备份 batch；restore drill V62、90 hashes、metadata counts、deleted_at、AI lifecycle tables、query audit。 |
| knowledge index 恢复/损坏演练 | 部分 | 当前没有 `ai_knowledge_documents/chunks` 或独立 embedding index；知识检索实际使用 PostgreSQL/pg_trgm 内容搜索，随 DB 恢复并验证。不存在可诚实执行的独立索引损坏 drill。 |
| provider 不可用/429/5xx、队列积压 | 通过（隔离测试） | 既有 provider failure、reliability、bounded queue/concurrency/recovery/overload 测试；未对生产 provider 做断网。 |
| artifact 磁盘接近满、下载失败、DB pool 耗尽 | 部分 | artifact quota/integrity/download/delete 与 preflight/Hikari alert contracts 已通过；没有在本机填满磁盘或耗尽连接池的破坏性演练。 |
| 备份过期、部署健康失败、旧 JAR rollback | 通过（脚本/契约） | `migration-preflight.ps1`、`release-preflight.sh`、deploy workflow 静态契约覆盖 freshness/checksum/health/rollback；没有远端生产主机可执行真实回滚。 |
| 删除/忘记不再召回 | 通过（隔离测试） | memory forget 清 body 与 owner summary；文件/产物删除、storage cleanup 和 owner isolation 已有集成覆盖；无 embedding/index 派生副本。 |
| WCAG 2.2 AA 自动+键盘 | 通过（自动与键盘） | Chromium/Firefox Axe + Tab；修复 reveal opacity、后台/AI/媒体对比度、图谱按钮语义和 AI 组件标签。 |
| 读屏抽查 | 未执行 | 当前环境没有 NVDA/VoiceOver 交互会话；自动语义规则已纳入 Axe，但不能代替人工读屏。 |
| CWV/API/AI p95/慢查询/内存/SLA/token/cost/存储增长/CI | 部分 | 本地 build、已有 CWV smoke、query audit 与 SLO contracts 可复核；本次未连接生产指标系统，线上 p95、增长和真实成本保持未测。 |
| 依赖/豁免/TODO/owner 风险 | 部分 | npm high audit 为 0，支持矩阵已写入 release-safety；安全扫描工具缺失，未关闭所有仓库外部豁免。 |
| Redis/多实例/worker 决策 | 通过 | 真实生产阈值未提供且当前单实例约束有效；ADR-003 保留简单架构并写明触发条件。 |

## 删除与隐私审计

数据库是 AI memory/session/task/artifact metadata 的事实源，storage 是字节事实源；浏览器缓存和 provider 会话不作为恢复源。memory forget 将正文置空、使 owner session summary 失效；删除/过期流程清理受控字节并保留可审计状态。当前没有 embedding/index 派生层，因此审计结果不能声称完成不存在层的向量删除。

## 生产门与下一年优先级

生产部署仍需 GitHub Actions `production` Environment reviewer、`DEPLOY_HOST/USER/SSH_KEY/PATH/KNOWN_HOSTS`、真实备份 batch 和线上 health/SLO 数据。工作树中的 `.github/workflows/deploy-fast.yml` 与 `docs/deploy-fast.md` 是用户已有未跟踪内容，本批次没有纳入或修改。

下一年优先级：完成 NVDA/VoiceOver 人工读屏抽查；建立真实 CWV/API/AI/任务/成本/存储仪表板；补做隔离磁盘近满与连接池压力演练；补齐可用的 secret/SBOM/container scan 工具；当 ADR-003 触发时先外置共享状态再扩容。
