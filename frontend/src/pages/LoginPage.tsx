import { FormEvent, useState } from "react";
import { Factory, KeyRound, LogIn, ShieldCheck } from "lucide-react";
import { api, type AuthSession } from "../api";
import { ErrorNotice } from "../components/ui";

export function LoginPage({ onAuthenticated }: { onAuthenticated: () => Promise<unknown> }) {
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<unknown>(null);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    setSaving(true);
    setError(null);
    try {
      await api.auth.login({ employeeCode: String(form.get("employeeCode")), password: String(form.get("password")) });
      await onAuthenticated();
    } catch (caught) {
      setError(caught);
    } finally {
      setSaving(false);
    }
  }

  return (
    <main className="login-page">
      <section className="login-intro" aria-label="系统说明">
        <span className="brand-mark login-mark"><Factory aria-hidden="true" /></span>
        <p className="eyebrow">CASTING MES</p>
        <h1>铸造生产执行中心</h1>
        <p>订单、工艺、派工、报工与仓储数据均按账号和岗位权限受控访问。</p>
        <div className="login-assurance"><ShieldCheck aria-hidden="true" />账号仅由系统管理员初始化与分配权限</div>
      </section>
      <section className="login-panel" aria-label="系统登录">
        <div>
          <span className="eyebrow">账号登录</span>
          <h2>进入工作台</h2>
          <p>使用管理员分配的工号和密码登录。</p>
        </div>
        {error != null && <ErrorNotice error={error} />}
        <form onSubmit={submit} className="login-form">
          <label>工号<input name="employeeCode" autoComplete="username" autoFocus required placeholder="例如 A001" /></label>
          <label>密码<input name="password" type="password" autoComplete="current-password" required placeholder="输入登录密码" /></label>
          <button type="submit" className="button button-primary login-submit" disabled={saving}>
            <LogIn aria-hidden="true" />{saving ? "正在验证" : "登录系统"}
          </button>
        </form>
        <p className="login-note"><KeyRound aria-hidden="true" />首次登录或忘记密码，请联系系统管理员重置。</p>
      </section>
    </main>
  );
}

export function ChangePasswordPage({ session, onChanged, onCancel }: { session: AuthSession; onChanged: (next: AuthSession) => void; onCancel?: () => void }) {
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<unknown>(null);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const nextPassword = String(form.get("newPassword"));
    if (nextPassword !== String(form.get("confirmPassword"))) {
      setError(new Error("两次输入的新密码不一致"));
      return;
    }
    setSaving(true);
    setError(null);
    try {
      onChanged(await api.auth.changePassword({ currentPassword: String(form.get("currentPassword")), newPassword: nextPassword }));
    } catch (caught) {
      setError(caught);
    } finally {
      setSaving(false);
    }
  }

  return <main className="login-page password-change-page"><section className="login-panel" aria-label="修改密码">
    <div><h2>修改密码</h2><p>{session.user.name} · 新密码至少 12 个字符</p></div>
    {error != null && <ErrorNotice error={error} />}
    <form onSubmit={submit} className="login-form">
      <label>当前密码<input name="currentPassword" type="password" autoComplete="current-password" required /></label>
      <label>新密码<input name="newPassword" type="password" autoComplete="new-password" required minLength={12} /></label>
      <label>确认新密码<input name="confirmPassword" type="password" autoComplete="new-password" required minLength={12} /></label>
      <button type="submit" className="button button-primary login-submit" disabled={saving}><KeyRound aria-hidden="true" />{saving ? "正在保存" : "确认并进入系统"}</button>
      {onCancel && <button type="button" className="button" disabled={saving} onClick={onCancel}>取消</button>}
    </form>
  </section></main>;
}
