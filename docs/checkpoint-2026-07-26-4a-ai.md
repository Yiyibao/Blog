# 4A · AI 助手平台化检查点（2026-07-26，第一批：4A-1 / 4A-2）

执行依据：docs/optimization-plan-v4-2026-07-26.md 4A 节。应用户指令，4A 提前于阶段 1–3 执行；迁移号按实际下一版分配为 **V15**（计划台账中的 V21 为规划示意）。

## 已完成项

| 子步 | 内容 | 提交 |
| --- | --- | --- |
| — | v4 计划与三份 agent 提示词入库 | docs: v4 长期计划与三份 agent 提示词入库 |
| 4A-1 | 供应商注册表：V15 迁移（ai_providers + ai_usage）、AES-256-GCM 密钥加密（主密钥 APP_AI_MASTER_KEY）、SSRF 校验（https 强制/拒内网环回/本地端点 env 开关/链路本地永拒）、OpenAI 兼容客户端抽象（禁重定向、响应体积上限、双超时）、admin CRUD + 设默认 + 连通测试（密钥只写不回显、尾 4 位）、env 配置自动 seed、chat 请求可选 providerId/model | feat: AI 供应商注册表与多模型抽象（4A-1） |
| 4A-2 | SSE 流式：后端 /admin/ai/chat/stream（delta/done/error 事件、15s 心跳、X-Accel-Buffering: no 免改 nginx、虚拟线程执行器、校验错误建流前以 HTTP 错误返回）；前端 fetch+ReadableStream 手工解析（JWT 走 Authorization 头不进 URL）、AdminAiChat 流式渲染 + 停止生成按钮、中止保留已生成部分 | feat: AI 对话 SSE 流式输出（4A-2） |

## 契约变更（前端/验收 agent 必读）

- `POST /api/v1/admin/ai/chat` 请求体新增可选 `providerId`（number）与 `model`（string）；缺省走默认供应商 → env 回退。响应不变。
- 新增 `POST /api/v1/admin/ai/chat/stream`：SSE，事件 `delta`={content}、`done`={model,usage}、`error`={status,message}；限额校验错误在建流前以普通 HTTP 400 返回。
- 新增 `/api/v1/admin/ai/providers` 一组管理端点（GET/POST、PUT/{id}、DELETE/{id}、PUT/{id}/default、POST/{id}/test），详见 README「管理接口」。
- 响应中的密钥形态永远只有 `hasKey`（boolean）与 `keyTail`（尾 4 位或 null），任何接口不回显明文密钥。
- 新环境变量：`APP_AI_MASTER_KEY`（≥32 字符，注册表加密主密钥）、`APP_AI_ALLOW_LOCAL_ENDPOINTS`（默认 false）。`.env.example` 已更新。

## 验证结果

- 后端 `mvn test`：**203/203 通过**（阶段 0 基线 155 → 4A-1 后 194 → 4A-2 后 203）。云端 JDK 21 + PostgreSQL 16 + 本机 .m2 离线仓库（复用 _to_delete/ 中上一轮留存的 m2-org/m2-rest 分卷重组）。
- 新增后端测试 48 个：AiCrypto 7（往返/篡改/换主密钥/缺失 503/短密钥启动拒绝）、AiBaseUrlValidator 9（字面量 IP 全覆盖：公网 https 放行、http 拒、环回/私网/CGNAT 默认拒、allow-local 放开、链路本地与 0.0.0.0 永拒）、AiProviderService 11（加密入库、明文不回显、重名 409、留空保钥、默认顺延、seed 三态、连通失败作结果）、AiChatService 10（原服务层用例迁移 + 注册表解析路径）、OpenAiCompatibleClient 22（原 HTTP 用例全量迁移 + listModels + SSE 流式 6 例）、AdminAiController +3（流式事件、错误事件、建流前校验）、集成 +3（CRUD 全响应无明文密钥断言、SSRF 拒绝、未授权 401）。
- 原 DeepSeekChatServiceTest 17 个用例**逐条迁移**至 OpenAiCompatibleClientTest（HTTP 层 13）与 AiChatServiceTest（服务层 4），断言未弱化；DeepSeekChatService/Test 删除（文件移至 _to_delete/removed-4a1/，因 VM 无删除权限）。
- 前端改动（api/admin.ts streamAiChat、AdminAiChat.vue、AdminAiChat.test.ts 全量重写为流式 mock）：**云端无法运行 vitest（npm 源被网络策略 403），待本机验证**，命令见下。

## 待用户本机执行

1. `cd frontend && npm test && npm run test:typecheck && npm run build` —— 验证 4A-2 前端改动（AdminAiChat 测试已改为流式语义，含新增「停止生成」用例）。
2. 生成并配置主密钥（启用多供应商注册表所需）：`.env.properties` 加 `APP_AI_MASTER_KEY=<openssl rand -base64 48>`；如需本地 Ollama 再加 `APP_AI_ALLOW_LOCAL_ENDPOINTS=true`。
3. 后端本机复跑（可选，云端已 203/203）：`cd backend && mvn test`。
4. `_to_delete/` 目录已含本轮传输用分卷与被移除文件，确认无用后可整体删除。

## 说明与偏差

- 云端 npm/pypi/Maven Central 均被网络策略 403；Maven 依赖复用上一轮留存的 .m2 分卷离线运行，npm 无等效缓存（仓库 .npm-cache 为 Windows 平台产物，缺 Linux 二进制），故前端验证遵循阶段 0 先例移交本机。
- SSE 免改 nginx：后端对流式响应设置 `X-Accel-Buffering: no`，nginx 按响应头关闭缓冲；如后续实测有缓冲问题，再在 nginx 加专属 location（已在计划 3.4 备案）。
- 心跳 15s 注释帧防代理断连；上游读超时与 emitter 超时（requestTimeout+30s）构成空闲超时双层。
- git 提交在桌面 VM 内完成，VM 对 .git 锁文件无删除权限，锁文件按惯例移至 _to_delete/git-locks/。

## 下一步

4A-3 供应商管理 UI → 4A-4 侧边栏形态 → 4A-5 场景化动作 → 4A-6 用量与预算（后端表已随 V15 建好）。
