import { useEffect, useMemo, useState } from "react";
import {
  ArrowRight,
  CheckCircle2,
  CircleAlert,
  LockKeyhole,
  Route,
  UserRoundCheck
} from "lucide-react";
import { NavLink } from "react-router-dom";
import { PageHeader } from "../components/ui";
import type { AccessUser } from "../types";
import {
  exceptionRules,
  productionOperations,
  productionLineGuides,
  productionStages,
  operatingRuleSummaries,
  roleGuides,
  type GuideLink
} from "./processGuideData";

function canOpen(user: AccessUser, link: GuideLink) {
  const permissions = Array.isArray(link.permission) ? link.permission : [link.permission];
  return permissions.some((permission) => user.permissions.includes(permission));
}

export function ProcessGuidePage({ user }: { user: AccessUser }) {
  const [selectedRoleCode, setSelectedRoleCode] = useState(user.primaryRole);
  const [selectedLineCode, setSelectedLineCode] = useState(productionLineGuides[0].code);

  useEffect(() => {
    setSelectedRoleCode(user.primaryRole);
  }, [user.primaryRole]);

  const selectedRole =
    roleGuides.find((role) => role.code === selectedRoleCode) ?? roleGuides[0];
  const myRoles = roleGuides.filter((role) => user.roles.includes(role.code));
  const myLinks = useMemo(() => {
    const links = myRoles.flatMap((role) => role.links).filter((link) => canOpen(user, link));
    return links.filter((link, index) => links.findIndex((item) => item.path === link.path) === index);
  }, [myRoles, user]);
  const selectedLine = productionLineGuides.find((line) => line.code === selectedLineCode) ?? productionLineGuides[0];

  return (
    <>
      <PageHeader
        title="生产流程指南"
        description="按角色查看职责、完成标准和下一步交接"
      />

      <section className="guide-personal" aria-labelledby="guide-current-user">
        <div className="guide-personal-copy">
          <span className="guide-section-icon" aria-hidden="true">
            <UserRoundCheck />
          </span>
          <div>
            <p className="guide-kicker" id="guide-current-user">
              当前账户
            </p>
            <h2>{user.name}</h2>
            <p>
              {user.employeeCode} · {user.unitName}
            </p>
            <div className="guide-role-tags" aria-label="当前账户角色">
              {myRoles.map((role) => (
                <span key={role.code}>{role.name}</span>
              ))}
            </div>
          </div>
        </div>
        <div className="guide-quick-actions" aria-label="我的常用入口">
          <strong>我的常用入口</strong>
          <div>
            {myLinks.map((link) => (
              <NavLink className="button button-secondary" key={link.path} to={link.path}>
                {link.label}
                <ArrowRight aria-hidden="true" />
              </NavLink>
            ))}
          </div>
        </div>
      </section>

      <section className="guide-section" aria-labelledby="complete-flow-title">
        <header className="guide-section-heading">
          <div>
            <span className="guide-section-icon" aria-hidden="true">
              <Route />
            </span>
            <div>
              <h2 id="complete-flow-title">订单到交付完整流程</h2>
              <p>每一步都明确责任人、完成标准和下一个接手岗位。</p>
            </div>
          </div>
        </header>
        <ol className="guide-flow">
          {productionStages.map((stage) => (
            <li key={stage.number}>
              <span className="guide-stage-number">{stage.number}</span>
              <div className="guide-stage-content">
                <div className="guide-stage-title">
                  <h3>{stage.title}</h3>
                  <span>{stage.owner}</span>
                </div>
                <p>{stage.action}</p>
                <dl>
                  <div>
                    <dt>完成标准</dt>
                    <dd>{stage.done}</dd>
                  </div>
                  <div>
                    <dt>下一步</dt>
                    <dd>{stage.next}</dd>
                  </div>
                </dl>
              </div>
            </li>
          ))}
        </ol>
      </section>

      <section className="guide-section" aria-labelledby="operations-title">
        <header className="guide-section-heading compact">
          <div>
            <span className="guide-section-icon" aria-hidden="true">
              <CheckCircle2 />
            </span>
            <div>
              <h2 id="operations-title">11 道生产工序</h2>
              <p>中温蜡按 11 道主工序流转；低温蜡取消自动制壳，砂型走外协路线。</p>
            </div>
          </div>
        </header>
        <ol className="guide-operations">
          {productionOperations.map((operation, index) => (
            <li key={operation}>
              <span>{String(index + 1).padStart(2, "0")}</span>
              <strong>{operation}</strong>
              {index < productionOperations.length - 1 && <ArrowRight aria-hidden="true" />}
            </li>
          ))}
        </ol>
        <div className="guide-worker-rule">
          <strong>一线员工固定动作</strong>
          <span>核对任务</span>
          <ArrowRight aria-hidden="true" />
          <span>阅读安全提示</span>
          <ArrowRight aria-hidden="true" />
          <span>开始作业</span>
          <ArrowRight aria-hidden="true" />
          <span>逐步执行 SOP</span>
          <ArrowRight aria-hidden="true" />
          <span>记录质量结果</span>
          <ArrowRight aria-hidden="true" />
          <span>完成并报工</span>
        </div>
      </section>

      <section className="guide-section" aria-labelledby="line-guide-title">
        <header className="guide-section-heading compact">
          <div>
            <span className="guide-section-icon" aria-hidden="true">
              <Route />
            </span>
            <div>
              <h2 id="line-guide-title">按生产线执行</h2>
              <p>切换后只看当前生产线的岗位、工序、使用功能和交接结果。</p>
            </div>
          </div>
        </header>
        <div className="guide-line-selector" role="tablist" aria-label="选择生产线">
          {productionLineGuides.map((line) => (
            <button key={line.code} type="button" role="tab" aria-selected={selectedLine.code === line.code} className={selectedLine.code === line.code ? "active" : ""} onClick={() => setSelectedLineCode(line.code)}>
              {line.name}
            </button>
          ))}
        </div>
        <div className="guide-line-summary">
          <strong>{selectedLine.name}</strong>
          <p>{selectedLine.summary}</p>
        </div>
        <div className="guide-line-steps">
          {selectedLine.steps.map((step, index) => (
            <article key={`${selectedLine.code}-${step.operation}`}>
              <span>{String(index + 1).padStart(2, "0")}</span>
              <div>
                <h3>{step.operation}</h3>
                <p>{step.action}</p>
              </div>
              <dl>
                <div><dt>责任角色</dt><dd>{step.owner}</dd></div>
                <div><dt>使用功能</dt><dd>{step.tool}</dd></div>
              </dl>
            </article>
          ))}
        </div>
      </section>

      <section className="guide-section" aria-labelledby="system-rules-title">
        <header className="guide-section-heading compact">
          <div>
            <span className="guide-section-icon" aria-hidden="true">
              <CheckCircle2 />
            </span>
            <div>
              <h2 id="system-rules-title">现场使用规则</h2>
              <p>把复杂规则放在系统里，把现场动作保留为核对、作业、报工和交接。</p>
            </div>
          </div>
        </header>
        <div className="guide-operating-rules">
          {operatingRuleSummaries.map((rule) => <article key={rule.title}><h3>{rule.title}</h3><p>{rule.detail}</p></article>)}
        </div>
      </section>

      <section className="guide-section" aria-labelledby="role-guide-title">
        <header className="guide-section-heading role-heading">
          <div>
            <span className="guide-section-icon" aria-hidden="true">
              <UserRoundCheck />
            </span>
            <div>
              <h2 id="role-guide-title">每个角色怎么使用</h2>
              <p>默认显示当前主角色，也可以查看其他岗位的交接要求。</p>
            </div>
          </div>
          <label className="guide-role-select">
            <span>查看角色</span>
            <select
              value={selectedRole.code}
              onChange={(event) => setSelectedRoleCode(event.target.value)}
            >
              {roleGuides.map((role) => (
                <option key={role.code} value={role.code}>
                  {role.name}
                </option>
              ))}
            </select>
          </label>
        </header>

        <div className="guide-role-detail">
          <div className="guide-role-summary">
            <p className="guide-kicker">岗位目标</p>
            <h3>{selectedRole.name}</h3>
            <p>{selectedRole.mission}</p>
            <dl>
              <div>
                <dt>每天先做</dt>
                <dd>{selectedRole.startOfDay}</dd>
              </div>
              <div>
                <dt>完成后交接</dt>
                <dd>{selectedRole.handoff}</dd>
              </div>
            </dl>
          </div>
          <div className="guide-role-steps">
            <h3>标准操作顺序</h3>
            <ol>
              {selectedRole.steps.map((step, index) => (
                <li key={step}>
                  <span>{index + 1}</span>
                  <p>{step}</p>
                </li>
              ))}
            </ol>
            <div className="guide-role-links">
              {selectedRole.links.map((link) =>
                canOpen(user, link) ? (
                  <NavLink className="button button-secondary" key={link.path} to={link.path}>
                    {link.label}
                    <ArrowRight aria-hidden="true" />
                  </NavLink>
                ) : (
                  <span className="guide-locked-link" key={link.path}>
                    <LockKeyhole aria-hidden="true" />
                    {link.label}
                    <small>当前账户无此权限</small>
                  </span>
                )
              )}
            </div>
          </div>
        </div>
      </section>

      <section className="guide-section" aria-labelledby="exception-title">
        <header className="guide-section-heading compact">
          <div>
            <span className="guide-section-icon warning" aria-hidden="true">
              <CircleAlert />
            </span>
            <div>
              <h2 id="exception-title">常见异常怎么处理</h2>
              <p>先停止错误扩散，再按业务单号和数量链定位责任节点。</p>
            </div>
          </div>
        </header>
        <div className="guide-exceptions">
          {exceptionRules.map((rule) => (
            <article key={rule.title}>
              <h3>{rule.title}</h3>
              <p>{rule.response}</p>
            </article>
          ))}
        </div>
      </section>
    </>
  );
}
