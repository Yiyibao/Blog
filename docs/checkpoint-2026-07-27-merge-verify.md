# 合并验证检查点（2026-07-27 · 并行会话收敛 → main）

执行依据：用户 2026-07-27 指令「批准 v5 后暂缓开工，待并行会话完成后先对代码进行一次合并，确保 main 仓库代码正确」（已写入 v5 计划文首开工前置）。

## 合并范围

自上次 main 推进（4483da3）以来的并行会话产出与合并配套，共 4 个提交：

| 提交 | 内容 | 归属 |
| --- | --- | --- |
| 6136265 | L-7 后台登录三层人机验证（完整实现 + 24 后端用例 + 9 前端用例 + nginx challenge 限速） | 并行会话 |
| 90b36b2 | AI 模块 review 加固（4A-1/4A-2 收尾：SSE 心跳/中断止损/供应商兼容/testConnection 事务/V16 唯一默认索引等，后端 +6、前端 +5 用例） | 并行会话 |
| 75d66db | L-7 与 AI 加固检查点文档 | 并行会话 |
| 9960264 | 合并验证配套：package-lock 一致性修复 + v5 迁移台账顺延落档（pg_trgm→V17） | 本会话 |

## 验证结果（本会话独立复跑，隔离 worktree @ 9960264）

- 后端 `mvn --batch-mode test`：**239/239 通过，0 失败 0 错误**（surefire 汇总核对；集成测试连本机 yubai_blog_it）。
- 前端 `npm ci`：通过（**严格校验曾失败**——HEAD 上的 package-lock 存在 @emnapi 可选依赖失配，系此前 Windows 本机 `npm install` 裁剪所致；已重新生成并随 9960264 修复。此前 main 上 CI 的 frontend job `npm ci` 步骤预计为红，本次推送应恢复）。
- 前端 `npm test`：**170/170 通过**（18 文件）；`npm run test:typecheck` 通过；`npm run build` 通过（含 powWorker 打包与 PWA 产物）。
- 验证全程在 `git worktree` 隔离副本进行，主工作区零触碰；worktree 用后已清理。

## 推送记录

- `codex/blogdemo` 与 `main` 均 fast-forward 推进至 **9960264**（无合并提交，线性历史）。
- main 上一次短暂不一致（718ce7f 暂存撞车致 login 签名失配，约 2 分钟窗口）已由 4483da3 回退修复，本次推进后 main 为完整验证过的状态。

## 部署注意（重要，转自并行会话 checkpoint 并核实）

1. **前后端必须同批部署**：`POST /api/v1/auth/login` 请求体新增必填 `challengeId`/`nonce`——旧前端对新后端无法登录，反之新前端对旧后端同样不可用。
2. nginx 需同步更新（deploy/hxnf.top.nginx 新增 `challenge_limit` 15r/m 专属 location），上线时 `nginx -t && systemctl reload nginx`。
3. V16 迁移前置检查：若线上 ai_providers 存在多行 is_default=true 脏数据（正常流程不会产生），迁移会失败，需先手工清理。
4. CI 的 npm audit 高危阻塞（brace-expansion）仍是已知红项（P2-6 待做），与本轮无关。

## 新发现（只记录）

- 迁移台账再次占号顺延：V16 = ai_providers 唯一默认索引；v5 计划 3.3 表已更新（pg_trgm→V17 起整体后移），正文内联迁移号以 3.3 表为准。
- 并行会话 checkpoint 记录的 AI review 已评估未修项（DNS rebinding、keyTail 逐行解密）维持原判，待 4A-6 同批评估。

## 下一步

开工前置已满足：main = 全量验证通过的 9960264。可按 v5 阶段 1 剩余项启动：P1-3（读路径去重复消毒，最小）→ P1-4+NB-1（pg_trgm+复合索引，**V17**）→ P1-5/NB-5/L-12（缓存与投影同批）→ P1-6 → P1-8；前端线 NF-6/NF-7/NF-10/P1-7/L-3。用户侧待办：前置 ②（服务器 P0-8 口令轮换 + nginx 更新验证）仍未销账。
