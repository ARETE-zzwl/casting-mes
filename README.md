# Casting MES / 铸造生产管理系统

面向精密铸造现场的 MES 演示项目，覆盖中温蜡、低温蜡、砂型外协三条业务路线。提供中文管理页面与适配移动浏览器的员工工作台。

**默认启动仍为免登录本地演示；`secure` 配置提供管理员开通账号的真实登录，现有业务模块已完成本轮岗位鉴权验收。正式部署还需 HTTPS、实际账号范围及存量数据验收，不能直接暴露开发服务。** 验收范围见 [登录与业务授权](docs/AUTHORIZATION_2026-10-01.md)，部署边界见 [安全说明](SECURITY.md)。公开版本只包含源码，不含运行数据库、客户附件或密钥。

## 业务范围

- 客户、产品、模具关系，订单多产品行、审核与工程工艺卡版本。
- 工单与批次、分批派工、模具领还、报工、交接差异、推车及二维码。
- 射蜡、修蜡、组树、制壳、脱蜡、浇筑、脱壳、分割及可选后处理。
- 原材料、模具、成品仓库，外协、发运、签收与订单追溯。
- 产品/工序工价、月度计件及个人产出导出。
- 消息、车间看板、角色数据范围与打印；可选 AI 工艺草稿辅助。

## 本地启动

环境：JDK 21、Node.js 22、pnpm 11.9.0。新环境使用 Flyway 初始化本地 H2 数据库，无需导入业务快照。

```powershell
# 终端一，在 backend 目录运行。JAVA_HOME 指向本机 JDK 21。
cd backend
.\mvnw.cmd spring-boot:run

# 终端二
cd frontend
pnpm install --frozen-lockfile
pnpm dev
```

Linux/macOS 使用 `./mvnw spring-boot:run`。前端：http://127.0.0.1:5174 ，后端：http://127.0.0.1:8081 。后端请始终在 `backend` 目录启动，避免相对数据库/附件路径变化。

无需注册或密码，右上角切换演示角色。常用工号包括 A001 管理员、GM001 总经理、FD01 前台、E001 工程师、PM01 生产总管、K001 原材料仓管、M001 模具仓管、G001 成品仓管。基础角色由迁移初始化，业务订单可在页面建立。

以上仅适用于默认演示配置。启用真实账号、初始化管理员与验证权限的步骤见 [登录与业务授权](docs/AUTHORIZATION_2026-10-01.md)。不开放自行注册，不提供通用初始密码。

## 架构

```text
React / TypeScript / Vite
        | 同源代理 /api /uploads
Spring Boot 4.1 / Java 21
        | Spring Modulith 模块化单体
订单 -> 工程 -> 计划 -> 执行 -> 库存/交付
        |            |       |
        +------ 追溯/计件/通知 +
        | JDBC / JPA / Flyway
H2 本地数据库 + 本地附件目录
```

数据库支持本地 H2 与独立的 PostgreSQL 配置。Maven 构建从历史迁移生成 PostgreSQL 版本，保留原 H2 迁移校验和；PostgreSQL 环境需启用 `postgresql` profile，生产同时启用 `prod`。可信身份绑定、附件授权、备份与恢复参见 [运维说明](docs/OPERATIONS.md)。微信原生小程序、完整 OIDC 前端、消息中间件和对象存储不属于本目录已验证的交付内容。

## 测试与构建

```powershell
# 项目根目录，一次运行后端测试、前端测试、构建和依赖审计
.\scripts\verify.ps1
```

也可分别运行 `backend/mvnw verify`、`frontend: pnpm test`、`pnpm build`、`pnpm audit`，Java 运行时依赖审计使用 `scripts/audit-backend.ps1`。后端集成测试使用内存 H2 和禁用 AI 密钥的配置；架构测试检查模块依赖，路由覆盖测试要求新业务接口明确声明鉴权策略。

## 配置与数据

| 配置 | 默认/说明 |
| --- | --- |
| MES_SERVER_PORT | 8081，修改时需同步 Vite 代理 |
| MES_UPLOAD_DIR | `./data/uploads` |
| MES_EXPORT_MAX_ROWS | 50000，范围 1–100000；超限提示缩小筛选，不截断输出 |
| DEEPSEEK_API_KEY | 默认空，不配置不影响非 AI 业务 |
| DEEPSEEK_BASE_URL / DEEPSEEK_MODEL | 可配置兼容服务地址和模型，请按供应商配置 |

密钥使用环境变量；不要写入源码。AI 会发送所选产品/工艺文本至配置服务，启用前需确认数据范围。

本地工作目录的数据库、合同、订单附件、上传图片、日志、浏览器资料和构建缓存不属于源码。禁止将它们加入版本库。历史迁移不可随意覆盖；生产升级与恢复必须独立验证。

更多说明：[贡献指南](CONTRIBUTING.md)、[最新安全与恢复审查](docs/QUALITY_REVIEW_2026-10-01.md)、[首轮质量审查](docs/QUALITY_REVIEW_2026-09-30.md)。许可证采用 [Apache-2.0](LICENSE)。
