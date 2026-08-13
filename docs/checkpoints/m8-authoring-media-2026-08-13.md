# M8 创作预览、统一媒体与发布质量 checkpoint

日期：2026-08-13
状态：完成（代码与验证完成；生产部署仍按 M12 统一发布门执行）

## 完成项

- V57 为文章增加 JPA 乐观锁版本和 `post_preview_tokens`。预览票据使用 32 字节随机值，仅保存 SHA-256，默认 15 分钟、上限 60 分钟，绑定文章版本；公开预览返回 `private/no-store`、`noindex/nofollow/noarchive` 和 `nosniff`，猜测、过期、撤销、文章版本变化统一 fail closed。
- V58 将笔记附件、菜品图片、AI 图片和 AI artifact 统一映射为只读媒体库，保留来源、所有者、文件名、MIME、大小、alt、来源、许可、SHA-256、引用数、createdBy、状态、创建时间与受控 URL。笔记正文引用和 AI task-part 引用会阻止物理删除；回收站/过期状态保留在聚合视图中。
- 发布门统一检查标题、slug、摘要、正文、SEO 长度建议、正文/封面图片 alt、空链接和定时时间；错误阻断，建议项只作为 warning 返回。
- 文章更新携带版本号时拒绝过期写入，409 返回服务器快照和版本号；后台编辑器显示本地/服务器标题、摘要和正文差异，不执行静默 last-write-wins。
- V59 新增候选动作提案。提案绑定 owner、task、动作类型、参数哈希、目标版本、过期时间和随机 nonce；nonce 仅返回创建响应，数据库仅存 hash。审批/驳回可重放保护、版本检查和所有者隔离；AI 工具层明确拒绝 publish/delete/schedule，审批不直接调用发布或删除服务。

## 验证证据

- `mvn -q "-Dtest=BlogApiIntegrationTest" test`：通过，包含真实 MockMvc 的预览猜测/版本失效/撤销、发布检查、乐观锁 409、媒体库鉴权回归；本机 PostgreSQL 18.4 触发 Flyway “支持版本至 17”提示，CI 仍使用 PostgreSQL 17。
- `mvn -q "-Dtest=AiToolOrchestratorTest,AiActionProposalServiceTest,AiPlatformControllerTest,PostServiceTest,PostWorkflowServiceTest,PostRevisionServiceTest,PostPublicationChecksTest,PostPreviewServiceTest" test`：通过。
- `mvn -q spotless:check`：通过。
- `npm run test:typecheck`、`npm run lint`：通过。
- `npm test -- --run`：71 个测试文件、822 个测试通过。
- 相关前端 Prettier 检查通过；`node scripts/verify-migration-manifest.mjs` 验证 58 个迁移文件至 V59。
- `git diff --check`：通过。

## 发布边界

- M8 没有自动发布、没有调用真实付费 AI、没有执行生产部署。
- `SCHEMA_TARGET=59`，当前发布兼容窗口仍为 expand-only；生产部署须在 M12 的备份、恢复、告警、性能和可访问性门全部通过后执行。
