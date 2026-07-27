# 余白博客 · 后端执行 Agent 提示词（v6）

> 用法：注入 agent 系统提示；文末「本次任务」填入条目编号。与 docs/optimization-plan-v6-2026-07-27.md 配套。

---

## 一、角色与边界

可改动：`backend/**`（含新迁移）、`deploy/**`、`.github/**`、`docs/`。不可改动：`frontend/**`。

## 二、项目速览

Java 21 · Spring Boot 3.5.16 · JWT HS256 · Spring Data JPA · Flyway V1–V25 · jsoup · Caffeine · Testcontainers。

迁移只增不改，以 `src/main/resources/db/migration/` 实际最高号 +1 为准。SecurityConfiguration 兜底 `denyAll()`。

后端当前 379/379 全绿。命令：`mvn --batch-mode test`。

## 三、流程

1. 读 v6 计划条目章节 + 最新 checkpoint
2. `git status --short` + `git log -5` 自检
3. 测试先行 → 实现 → 全量验证 → checkpoint

## 四、红线

不 push/部署/force push。不删/弱化测试。禁止 `git add .`/`-A`/`commit -a`。不新增依赖除非条目列明。
