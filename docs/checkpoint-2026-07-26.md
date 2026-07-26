# 项目检查点（2026-07-26）

## 本轮目标

检查当前项目的前后端测试、构建、依赖安全和维护状态，在不进行高风险升级或掩盖警告的前提下完成确定性修复。

## 已完成改动

### 前端测试

- 将归档页、分类列表页和分类详情页测试由 Web History 改为 Memory History。
- 为测试补齐组件实际生成的首页、文章、笔记、菜谱和分类路由。
- 等待测试路由完成初始化，消除 Vue Router 的无匹配路由警告。
- 保留原有页面状态、链接和分页断言。

### 后端安全测试与配置

- 为 `PostContentSanitizer` 增加 13 项单元测试，覆盖脚本、事件处理器、危险协议、允许标签和属性等清洗边界。
- 在 `backend/.env.example` 中补充 `APP_JWT_TTL=PT2H`，与应用默认配置及部署脚本保持一致。

### CI 维护

- 删除后端 CI 中重复的 `compile test-compile` 步骤；后续 `mvn test` 已包含主代码和测试代码编译。
- 保留 PostgreSQL 17 服务、测试日志上传和失败传播行为。

## 独立验证结果

- 前端单元测试：121/121 通过，未再输出 Vue Router 无匹配路由警告。
- 前端测试类型检查：通过。
- 前端生产构建：通过。
- 后端 `mvn --batch-mode clean test`：110/110 通过。
- `git diff --check`：通过。

## 已知未解决项

- `npm audit --audit-level=high` 仍报告 12 个高危传递依赖，根因是 `brace-expansion <=5.0.7`。
- 两条受影响链分别来自 `@vue/test-utils` 和 `vite-plugin-pwa`，均只用于测试或构建，不进入浏览器生产运行时代码。
- 当前上游依赖范围没有安全兼容的修复路径，因此未使用 `npm audit fix --force`、不兼容降级或未经验证的全局 override。
- `.github/workflows/ci.yml` 仍将前端审计作为强制步骤；在上游发布兼容修复前，合入 `main` 后该步骤预计仍会失败。
- KaTeX JavaScript 分块约 744 KB，但已通过笔记相关动态路由隔离，不进入其他公共页面的初始加载；未提高警告阈值掩盖问题。
- 本地 PostgreSQL 18.4 超出当前 Flyway 官方测试范围，CI 继续使用受支持的 PostgreSQL 17。
- Mockito 动态加载 Java Agent 的提示属于未来 JDK 兼容性预警，当前 Java 21 测试不受影响。

## 后续建议

1. 跟踪 `@vue/test-utils`、`vite-plugin-pwa` 和下游构建依赖的安全版本发布。
2. 上游修复可用后更新 lockfile，并重新运行完整前端测试、构建和审计。
3. 合入 `main` 前确认 CI 审计策略，避免将已知上游阻塞误判为本项目运行时漏洞。
