# M12 年度灾备、性能、可访问性与收口 checkpoint（2026-08-13）

## 状态

本地/隔离年度验收完成；Production GA 继续关闭。读屏人工抽查、生产指标和远端部署未在本环境虚构执行。

## 入口门与范围

- M1–M11 checkpoint 均已存在；M11 V62 已完成并通过。
- 最高迁移 V62，新增迁移：无。
- 本批次修改范围：前端关键路由 Axe/键盘审计、AI 组件语义标签、后台/AI/媒体对比度、图谱按钮语义、reveal 与 admin 页面可见性、审计文档。
- 用户已有未跟踪 `.github/workflows/deploy-fast.yml`、`docs/deploy-fast.md` 未纳入本批次。

## 恢复证据

批次目录：`outputs/m12-annual-20260813/`（本地忽略目录，不提交仓库）。

| 证据 | 结果 |
| --- | --- |
| `yubai_blog_v62.dump` | 195,528 bytes；manifest SHA-256 `a9868cd75236c708bcf24d03fc9a13af67a508b585ec7e989f0d982c4d6a7c95` |
| `attachments_v62.tar.gz` | 13,479 bytes；manifest SHA-256 `580577ecadcd510dddf279c6a17556d0c7330da9d8429a5671dc6c9b57d9c271` |
| `storage-inventory-v62.sha256` | 10,260 bytes；90 个文件逐项 SHA-256；manifest SHA-256 `4d63d9df2388d2b4f1b8e9abf8de126fa6e48036629c2ac25c8ce6ae9b7878de` |
| restore drill | `status=PASS`、Flyway `62->62`、posts `15->15`、dishes `20->20`、AI lifecycle tables `4`、deleted_at `1`、query audit 通过；另一次 `Measure-Command` elapsed `8.8s` |

知识索引特别说明：当前代码和 schema 没有独立 `ai_knowledge_documents/chunks` 或 embedding index；搜索使用 PostgreSQL pg_trgm/LIKE。故恢复验证覆盖数据库搜索结构，不执行不存在对象的“索引损坏”伪演练。

## 故障演练矩阵

| 场景 | 证据状态 |
| --- | --- |
| provider unavailable、429、5xx、超时 | reliability/provider failure tests；未访问真实 provider |
| queue backlog / concurrency / recovery | bounded semaphore、queue full、recovery/overload tests |
| artifact near-full / failed download | artifact quota、integrity、download/delete 与 release disk guard；未填满本机磁盘 |
| DB pool exhausted | Hikari alert/config contract；未进行破坏性耗尽 |
| stale backup | migration/release preflight freshness/checksum contract；restore batch 新鲜 |
| deployment health failure / old JAR rollback | `.github/workflows/deploy.yml` static contract；无生产远端主机 |

## 可访问性证据

- `frontend/e2e/key-pages-accessibility.spec.ts`：Chromium 9 路由 × 1 = 9；与既有公开/PWA 测试合计 offline Chromium 14/14。
- 同一关键页审计 Firefox 9/9。
- 认证 full-stack route audit：`/admin`、`/admin/notes`、`/admin/ai`、`/admin/attachments`、`/admin/media`、`/admin/graph`，Chromium 与 Firefox 各 1/1；整个隔离 full-stack suite 10/10；Axe WCAG2A/2AA serious/critical = 0；每路由 Tab 后有焦点。
- 已修复：admin 页面 reveal opacity、sidebar/footer/preview/stat 对比度、AI 辅助文字/状态色、笔记发布按钮/提示 code、媒体状态 badge、厨房 InspirationCard、GraphSidebar 非语义 button、AI Workspace/AttachmentTray/MemoryPanel/ArtifactCard 语义标签。
- 当前实现没有独立 ApprovalCard 组件；审批能力的现有提案与 MemoryPanel/ArtifactCard 页面进入实际扫描范围。
- NVDA/VoiceOver 人工读屏未执行。

## 全量质量门

- backend `mvn -q verify`：817 tests，0 failures/errors/skipped；107 Surefire XML。
- frontend Vitest：75 files / 835 tests 全通过；coverage 61.98/55.37/51.70/64.68（statement/branch/function/line），低于年度目标，保留为风险项。
- `npm run lint`、`format:check`、`test:typecheck`、`api:types:check`、`npm audit --omit=dev --audit-level=high`、`node scripts/verify-migration-manifest.mjs`：通过；audit 0 vulnerabilities，manifest 61 files to V62。
- `npm run build`：614 modules、49 JS、1,500,807 bytes、PWA 100 entries/970.87 KiB、预算通过；code-highlight chunk >500k warning 非阻断。
- full-stack deterministic fake provider：Chromium、Firefox、mobile online contract + authenticated audit 共 10/10；不调用真实外部模型。
- offline E2E：Chromium 14/14；key-page Firefox 9/9。

## 生产门、回滚与未完成项

- RTO：本地隔离恢复 8.8s；RPO：生产 release contract 要求完整备份 ≤26h、目标 ≤24h，但本次未观测生产备份系统。
- 生产部署 workflow 已具备 artifact、preflight、atomic symlink、health 60s 和 rollback；本次不连接生产，不执行 reload nginx/systemd。
- 生产 GA：NO，待 Environment reviewer、SSH secrets/known-host、真实备份和线上 SLO 数据。
- 安全工具 gitleaks/syft/trivy/grype 未安装，secret/SBOM/container scan 未完成。
- 读屏手工审计、磁盘近满、DB pool 耗尽、生产 API/AI p95、token/cost、存储增长和真实 CI 时长未测。

## 下一月/下一年

M12 之后不自动启动新月任务；Production GA 前应先完成以上外部门禁。下一优先级为读屏手工审计、生产指标接入、压力/容量演练和安全供应链工具安装。ADR-003 规定未达到阈值继续保留单实例；达到阈值先迁移共享状态，再评估 Redis/worker/多实例。
