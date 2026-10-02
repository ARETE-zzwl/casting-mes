# 架构与并发审查记录

日期：2026-10-02。修改目录为 `casting-mes-open-source`；未改动原免登录测试版、运行数据库或客户附件。

## 已确认并修复

| 严重度 | 问题 | 修复与证据 |
| --- | --- | --- |
| 高 | 模具入库的进程内锁在数据库提交前释放，其他事务可再次选择同一位置；库位容量修改没有与入库互斥 | 数据库库位行锁贯穿事务；自动分配在等待后重查；容量缩减/停用采用相同锁。最初 6 项定向测试中 5 项失败，修复后通过，并追加通用登记竞争测试 |
| 高 | 通用资产登记可直接写入已满、停用或不存在的模具库位 | 与模具入库共用校验，规范化库位编码；无位置资产登记的旧兼容路径保留，不算完成库位入库 |
| 高 | 二维码绑定、发码、补打对外连接执行 `FOR UPDATE`，PostgreSQL 报错 | 改为仅锁标签基础表，统一资产再标签的顺序；真实 PostgreSQL 扫码入库、绑定、导出和补打用例通过 |
| 中 | PostgreSQL 持续集成中的外协和交付测试写死 H2 地址，造成覆盖范围误判 | 去掉两个测试类的数据库覆盖；加入二维码及库位用例；CI 若检测到 H2 连接日志则失败 |
| 中 | 大多数模块只有循环依赖检查，没有显式允许依赖列表 | 25 个模块均声明依赖边界；新增声明完整性测试，原循环依赖检查保留 |

补充 V99 `(asset_type, location_code)` 索引支持模具占用统计。没有修改 V1 至 V98 历史迁移。

原业务测试中把 `MOLD-01` 等仓库/临时名称冒充库位的资产夹具已去掉错误位置字段，其他业务断言未删除；库位行为由专门测试验证。演示月度模拟改用独立受管理库位，不再把不同模具都写到 `MOLD-01`。

## 验证范围

- 后端：57 个测试类、152 项测试通过，0 失败、0 错误、0 跳过；完整 `mvn verify` 与 JAR 构建通过，包含 3 项架构检查。
- PostgreSQL 17.11：9 个测试类、20 项测试通过；连接日志均指向 PostgreSQL，覆盖二维码、库位并发、库存检索/导出、砂型外协、多产品订单和完整交付等选定场景。
- 前端：16 个文件、46 项 Vitest 测试通过；TypeScript/Vite 构建通过。本轮没有修改界面，没有新做一轮全角色浏览器验收。
- 依赖审计：115 个 Java 运行时依赖的 OSV 扫描、前端 pnpm audit 均未发现当前已知漏洞；这不等同于业务代码无安全问题。
- 主包 801.91 KB、图表包 574.44 KB，保留体积警告，未通过放宽阈值隐藏问题。

新增库位回归覆盖：未提交入库与第二次手动入库竞争、自动分配等待后改选、通用登记与入库竞争、容量缩减、停用、通用登记绕过、扫码失败整单回滚。

PostgreSQL 不是“所有后端测试”的替代说法。此前 2026-10-01 记录中的 12 项 PostgreSQL 任务结果包含两个硬编码 H2 测试类，本轮明确纠正此覆盖盲点；既有浏览器 PostgreSQL 演练属于另一组证据。

本地证据保存在忽略目录 `artifacts/`：`mold-lock-red.log`、`mold-lock-green.log`、`review-20261002-postgresql.log`（复现 SQL 问题）、`review-20261002-postgresql-v2.log`（20 项通过）、`review-20261002-postgresql-final.log`、`review-20261002-backend-final.log` 和 `backend-audit.json`。这些日志不随公开源码发布。

## 数据升级注意

库位容量可大于 1，不能简单给 `resource_asset.location_code` 加唯一约束。当前保护来自共同遵守的事务锁协议。所有旧版本应用和外部写入工具必须先停用，再进行升级。

以下为只读盘点示例，不自动修正实物库位：

```sql
-- 超容量或已有模具却停用的位置。
select l.location_code, l.capacity, l.active, count(a.id) as allocated
from mold_storage_location l
left join resource_asset a on a.asset_type = 'MOLD' and a.location_code = l.location_code
group by l.location_code, l.capacity, l.active
having count(a.id) > l.capacity or (l.active = false and count(a.id) > 0);

-- 未分配位置或位置不在受管理目录的历史模具。
select a.asset_code, a.asset_name, a.location_code
from resource_asset a
left join mold_storage_location l on l.location_code = a.location_code
where a.asset_type = 'MOLD' and l.location_code is null;
```

未修改真实工厂数据，不能把自动测试通过理解为存量库位已盘点完毕。

## 架构判断

保持模块化单体，暂不引入微服务或消息队列。进一步拆解授权读模型、优化追溯批量查询、按岗位减少前端首包，应有各自测试和性能基线。本轮没有大范围搬动业务类，也没有宣称跨模块 SQL 已完全消除。

详见 [系统架构](ARCHITECTURE.md)，其中包含运行图、模块责任表、端口、生产数据链、事务、安全、升级边界及优化顺序。
