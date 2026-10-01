# 运行、升级与恢复

## 适用范围

默认环境是绑定回环地址的免登录演示；`secure` 和 `prod` 使用可信身份与业务范围鉴权。现有模块已完成本轮鉴权回归，范围和证据见 [登录与业务授权](AUTHORIZATION_2026-10-01.md)。源代码和本机测试不替代实际工厂部署验收。

## 可信身份

使用 `prod` profile 时 JWT 必须由配置的 issuer 验签，且 `trusted_identity_binding` 中存在 issuer、subject 到有效员工的绑定。不要把用户可修改的显示名、邮件或客户端工号当作 subject。

绑定由受控 DBA 操作完成，当前没有开放自助绑定或注册 API。先审查员工的 MES 角色，再插入绑定。管理员需要同时满足 IdP 的 `mes.admin` scope 和 MES 的 `SYSTEM_ADMIN` 角色；停用员工立即阻止后续请求。不要把所有 IdP 用户默认绑定到 A001。

普通账号按岗位使用订单、派工、报工、工资、炉次、外协、扫码、纸质回填、仓库等接口，并受产线、工序、任务分派和仓库范围约束。管理员配置仍仅限管理员。`viewerCode` 是调用者，库存导出的 `operatorCode` 是筛选条件；主管代录的工资归属不能用调用者替换。

受控配置的附件除上传人/显式读者外，会按订单、产品、工序快照、报工、纸质单和流转关系自动授权。合同与生产图纸分开授权，改派撤销原员工的业务访问。DBA 可在 `attachment_reader(file_path, employee_code)` 中维护独立显式读者。没有元数据的旧文件不自动公开，也不因管理员角色绕过 ACL。

不依赖外部 IdP 的本地账号部署使用 `secure,postgresql`。只由管理员开通，首次初始化 A001 使用 `MES_BOOTSTRAP_ADMIN_PASSWORD` 环境变量，初始化后移除；已有密码不会被启动参数覆盖。正式 HTTPS 环境保留默认 Secure Cookie，`MES_COOKIE_SECURE=false` 只用于回环 HTTP 联调。

## PostgreSQL

配置 `MES_DATABASE_URL`、`MES_DATABASE_USERNAME`、`MES_DATABASE_PASSWORD`；生产还需 `MES_OIDC_ISSUER_URI`、`MES_ALLOWED_ORIGINS` 和与 IdP 对应的 `MES_OIDC_AUDIENCE`（默认 casting-mes）。启动 profile 为 `prod,postgresql`。不要将密码放在命令行或源码，使用部署环境密钥机制。audience 校验使用 [Spring Boot 官方资源服务器配置](https://docs.spring.io/spring-boot/reference/security/oauth2.html)。

`mvn package` 在构建目录生成 `db/postgresql`，仅做三项已知方言转换：CLOB 到 TEXT、random_uuid 到 gen_random_uuid、移除 ADD CONSTRAINT 的 IF NOT EXISTS。源迁移文件保持不变；Flyway 仍逐版本记录校验和。三个工艺参数 JPA 字段改用文本映射，并由 V95/V96 升级 H2 字段。

不要手改 target 内生成 SQL，不要用 Flyway repair 隐藏未知差异。未验收的其他 PostgreSQL 大版本或数据库类型不能直接视作兼容。H2 到 PostgreSQL 的存量业务导入不是这组迁移的能力。

## 备份

需要 PowerShell 7。停止所有应用实例、异步工作者及其他写入程序；脚本检查本机应用端口，但不能发现远端写入者。附件目录必须包含在 DataDirectory 中。自定义外部附件路径需先整合，不能漏备。

```powershell
./scripts/backup.ps1 -Mode H2 -DataDirectory /srv/mes/data -Destination /backup/mes-20261001 -ApplicationStopped
./scripts/restore.ps1 -BackupDirectory /backup/mes-20261001 -Destination /srv/mes/restored-data
```

PostgreSQL 使用对应版本客户端工具，通过 PGHOST、PGPORT、PGUSER 和受控 PGPASSFILE 提供连接配置：

```powershell
./scripts/backup.ps1 -Mode PostgreSQL -DataDirectory /srv/mes/data -Destination /backup/mes-pg-20261001 -ApplicationStopped -PgBin /usr/lib/postgresql/17/bin -Database mes
./scripts/restore.ps1 -BackupDirectory /backup/mes-pg-20261001 -Destination /srv/mes/restored-pg-data -PgBin /usr/lib/postgresql/17/bin -NewDatabase mes_restore_check
```

脚本不删除、不覆盖数据库。恢复先校验所有文件，再创建新数据库，单事务恢复 SQL。失败后保留新数据库供检查，不自动切换业务。`manifest.json` 最后写入，无该文件的备份视为未完成。SHA-256 可检测损坏，不代表备份的真实性或加密；备份介质仍需加密和访问控制，不要恢复不可信来源的数据库转储。

恢复后先核对 Flyway 版本、订单数、库存余额、工资明细和附件，再切换数据库及附件路径；保留原数据直至验收。代码回退不等于数据库降级，涉及不可逆迁移需用完整备份恢复。

## 异步导出

PENDING 和租约过期的 RUNNING 每 30 秒参与恢复。原任务号和幂等键不变，工作者原子领取、生成新执行令牌；过时工作者的完成/失败更新不会覆盖新任务结果。最多三次领取，持续中断后置 FAILED 并提示管理员。正常校验失败不自动无限重试。

租约 10 分钟，单 SQL 超时 120 秒，导出限制最多 100000 行。调整到更大数据量前必须重新验证内存和执行时间。程序重启后任务可能等待剩余租约到期，这是防止多实例重复执行的设计。

本轮已实际在 RUNNING 时终止后端并重启，过期任务恢复成功。测试库租约被明确提前过期以缩短等待，数据库锁仅作为演练阻塞条件，重启前已释放；不能把仍被数据库锁阻塞的工作者误判为恢复逻辑未执行。重启会使内存会话失效，用户重新登录，数据库中的账号密码继续有效。
