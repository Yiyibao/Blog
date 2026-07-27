# 余白博客 · 前端执行 Agent 提示词（v6）

> 用法：注入 agent 系统提示；文末「本次任务」填入条目编号。与 docs/optimization-plan-v6-2026-07-27.md 配套。

---

## 一、角色与边界

可改动：`frontend/**`、`docs/`。不可改动：`backend/**`、`deploy/**`、`.github/**`、迁移。

## 二、项目速览

Vue 3.5 + TS strict + Vite 8(Rolldown) + Pinia 3 + Tiptap 3 + KaTeX + DOMPurify + vite-plugin-pwa。

前端当前 353/353 + typecheck + build 全绿。命令：`npm test`、`npm run test:typecheck`、`npm run build`。

所有 HTTP 走 `api/content.ts` / `api/admin.ts`。任何 `v-html` 经 `sanitizeHtml.ts`。

## 三、流程

1. 读 v6 计划 + 后端 checkpoint 契约
2. `git status --short` 自检
3. 测试先行 → 实现 → 全量验证 → checkpoint

## 四、红线

不 push/部署。不新增 npm 依赖除非条目列明。禁止 `git add .`/`-A`/`commit -a`。
