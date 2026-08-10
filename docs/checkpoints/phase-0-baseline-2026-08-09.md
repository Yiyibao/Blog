# 阶段 0：基线与冻结决策

日期：2026-08-09

## 冻结决策

- 当前路线图：`docs/project-audit-and-execution-plan-2026-08-09.md`。
- 生产部署：nginx + 单实例 Spring Boot + PostgreSQL。
- 单实例约束：限流、人机验证、登录失败与 TOTP 挑战迁移到共享状态前，禁止增加后端副本。
- 数据库版本：源码 Flyway 最新版本为 V47；生产实际版本必须由迁移前置检查读取，不在文档中猜测。
- 历史迁移只读；演示数据改由显式 `dev-seed` 流程维护。

## 初始基线

- 生产依赖审计：3 项（DOMPurify moderate、PostCSS moderate、nanoid high）。
- 已存在的 R-01 工作树变更已保留并纳入后续验证。
- nginx、systemd、备份/恢复与原子发布脚本已经存在，继续作为唯一生产路线。
- 本机有 PostgreSQL 服务；生产凭据与服务器操作不属于本地代码执行范围。

## 产物边界

`.gitignore` 已覆盖 `frontend/outputs/`、`frontend/vite-test*.log`、前后端构建目录、覆盖率和本地环境文件。既有未跟踪生成物未被删除。

最终测试、覆盖率、构建体积与审计结果在总体验收 checkpoint 中更新。
