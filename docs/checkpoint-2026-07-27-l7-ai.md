# L-7 登录人机验证 + AI 模块 review 加固检查点（2026-07-27）

执行依据：docs/optimization-plan-v5-2026-07-27.md 1.2 在途表（L-7 收尾三步）与 3.6 设计定稿；AI 加固为用户指令「检查 AI 模块的功能实现，完善以及优化」的对抗性 review 产出。

## 已完成项

| 项 | 内容 | 提交 |
| --- | --- | --- |
| L-7 | 三层人机验证按 v4 3.6 原设计实现：层 1 PoW 常开（GET /api/v1/auth/challenge，难度 4 可配，前端 Web Worker + 纯 TS 同步 SHA-256 解题，降级同步路径）；层 2 同 IP **或**同用户名 15 分钟失败≥3 → Java2D 图形码（160×56、字符集剔除 0/O/1/I/l、答案 SHA-256 哈希存储、MessageDigest.isEqual 恒定时间、大小写不敏感、「换一张」）；层 3 同 IP 失败≥10 → 冷却 30 分钟（登录与取码均 429 + Retry-After）。challenge 一次性（成败皆作废）、绑下发 IP、TTL 5 分钟；risk 升级后旧 POW challenge 作废；所有失败统一文案。配置 app.auth.challenge.* 六键（env 可覆盖，.env.example 已注释）。nginx 增 challenge_limit 15r/m burst 10 专属 location | feat: 后台登录三层人机验证（L-7） |
| AI 加固 | 高危 4 项：SSE 心跳单线程 static 池 → Spring 管理双线程调度器 + tryLock（慢客户端不再阻塞其他连接心跳）+ 发送失败自取消；工作线程 Future 在 emitter 超时/出错时 cancel(true)，parseSseStream 逐行查中断——客户端断开后停读上游止损 token；thinking 字段仅对 deepseek 域名下发、tool_choice 整体移除（OpenAI 未带 tools 时会 400，此前多供应商配置必然失败且被掩盖为 502）；testConnection 去掉只读事务（外呼最长 60s 占死 HikariCP）。中低危：V16 部分唯一索引防双默认 + setDefault 先 flush 清旧再设新；/test 端点 6r/m 限流；限额校验收敛 AiChatService.validateLimits 单一入口；后端 SSE 解析支持多行 data:（规范）；流式体积上限 8MB；前端 reader try/finally 释放、SSE 事件类型边界复位、展示层上限 20→100（修发送瞬间首条消息凭空消失）、删死代码 sendAiChat、文案去 DeepSeek 硬编码 | fix: AI 模块 review 加固（4A-1/4A-2 收尾） |

## 契约变更（前端/验收 agent 必读）

- **新增 `GET /api/v1/auth/challenge?username=`**（匿名可访问，Security 白名单显式放行）：返回 `{challengeId, type: "POW"|"IMAGE", salt, difficulty, captchaImage}`；IMAGE 时 captchaImage 为 data:image/png;base64 内联。应用层 15 次/分/IP 限流。
- **`POST /api/v1/auth/login` 请求体新增必填 `challengeId`、`nonce`，可选 `captchaAnswer`**——旧客户端无法登录，**前后端必须同批部署**。人机验证失败统一 400「人机验证未通过，请重新验证后再试」；冷却期 429 带 Retry-After 头。
- `POST /api/v1/admin/ai/providers/{id}/test` 增加 6 次/分/IP 限流（429）。
- 前端 `login(username, password, verification)` 第三参数为 `{challengeId, nonce, captchaAnswer?}`；新增 `fetchLoginChallenge(username?)`；`sendAiChat` 删除（无引用死代码，流式 `streamAiChat` 为唯一对话入口）。

## 迁移台账

- **V16 = ai_providers 唯一默认部分索引**（`CREATE UNIQUE INDEX ... ON ai_providers (is_default) WHERE is_default`）。按执行原则 4「V16+ 顺序分配、以执行时实际最高号为准」，v5 3.3 台账整体顺延一位：pg_trgm/复合索引 → **V17**，3A-1 双字段 → **V19**，以此类推。
- 部署注意：若线上 ai_providers 已存在多行 is_default=true 脏数据（正常流程不会产生），V16 迁移会失败，需先手工清理。

## 验证结果（2026-07-27 本机）

- 后端 `mvn test`：**239/239 通过**（v5 基线 233 → AI 加固 +6：thinking 条件化、deepseek 域名判定、多行 data、中断即停、服务层限额 ×2；L-7 的 24 例已计入基线）。集成测试连本机 PostgreSQL `yubai_blog_it`。
- 前端 `npm test`：**170/170 通过**（v5 基线 165 → +5：aiStreamParser.test.ts 首次真实覆盖 streamAiChat 解析器——事件分发、跨 chunk 拼行、事件复位、error 抛出与 reader 取消、401 清 session）；`npm run build` 通过（含 powWorker 打包）。
- L-7 验收自查对照 v4 3.6：过期/重放/错解/跨 IP/一次性/恒定时间/大小写不敏感/难度参数 → ChallengeServiceTest 13 例；无 challenge 400 / 重放 400 / 第 3 次失败升级 / 全要素成功 / 第 10 次冷却 429 / 成功清零 → 集成测试 4 例覆盖；v5 3.6 追加两条（冷却期 challenge 同 429、升级后旧 POW 作废）均有用例。

## 说明与偏差

- admin.ts 同时承载 L-7 与 AI 两批改动，按执行原则 9 以补丁分离方式拆分暂存（hunk 1 归 L-7，hunk 2–4 归 AI 加固；hash-object/update-index 手术）。
- admin.ts 的历史 blob（7acc1aa，07d25a7 引入、4483da3 还原）是**漏网 CRLF blob**（仓库 core.autocrlf=input、其余文件均 i/lf）。L-7 提交经 LF 归一后该文件回到仓库规范，故 git show 对该提交显示整文件行尾 diff（一次性代价，字节级实际差异仅 L-7 hunk 25 行，`git diff --ignore-cr-at-eol` 可验证）；这同时提前完成了 P2-7 对该文件的 renormalize。注意 MSYS 工具链（grep/sed/cat -A 经管道）会静默剥 CR，核验行尾要用 `git cat-file -s` 字节数或 `git ls-files --eol`。
- 本轮提交期间与并行会话发生一次暂存撞车（718ce7f 卷入本会话在途暂存、4483da3 已回退），印证执行原则 9；后续本会话改为「单命令链内 暂存→HEAD 校验→提交」的原子操作并核对 HEAD 未移动。
- AI review 遗留未修（已评估）：DNS rebinding（SSRF 校验仅保存时解析，根治需 IP pinning；单管理员自配供应商场景风险低，建议与 4A-6 同批评估）；keyTail 逐行解密与 setDefault 全表扫描（表为个位数行，不值得加列/绕一级缓存）。
- 4A-3（供应商管理 UI）与 4A-6（用量与日预算）按 v5 计划留待阶段 4，本轮未预支。

## 下一步

v5 1.6 待办：阶段 1 剩余（P1-3/4/5/6/8、L-12 等，注意迁移号顺延）→ 阶段 2（含 L-8/L-9/L-11）。
