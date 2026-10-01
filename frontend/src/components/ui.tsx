import { useEffect, useRef, type FormEvent, type ReactNode } from "react";
import { AlertCircle, Inbox, LoaderCircle, X } from "lucide-react";
import { ApiError } from "../api";

export function PageHeader({
  title,
  description,
  action
}: {
  title: string;
  description?: string;
  action?: ReactNode;
}) {
  return (
    <header className="page-header">
      <div>
        <h1>{title}</h1>
        {description && <p>{description}</p>}
      </div>
      {action && <div className="page-actions">{action}</div>}
    </header>
  );
}

const statusLabels: Record<string, string> = {
	BLOCKED: "等待前工序",
  DRAFT: "草稿",
  SUBMITTED: "待审核",
  APPROVED: "工程已确认",
  RELEASED: "已投产",
  READY: "待派工",
  ASSIGNED: "已分派",
  IN_PROGRESS: "生产中",
  COMPLETED: "已完成",
  PASSED: "合格",
  REJECTED: "不合格",
  ACTIVE: "生效",
  INACTIVE: "停用",
  CANDIDATE: "待确认",
  CONFIRMED: "已确认",
  RECEIPT: "入库",
  ISSUE: "出库",
  ADJUSTMENT_IN: "调增",
  ADJUSTMENT_OUT: "调减",
  SENT: "已发出",
  RECEIVED: "已收货",
  PICKED: "已备货",
  SHIPPED: "运输中",
  DELIVERED: "已签收",
  CLOSED: "已关闭",
  CANCELLED: "已取消",
  PENDING: "待审批",
  PUBLISHED: "已发布",
  ROLLED_BACK: "已回退",
  AVAILABLE: "可用",
  OCCUPIED: "占用中",
  EXHAUSTED: "寿命耗尽",
  MOLD: "模具",
  CARRIER: "周转车",
  BOUND: "已绑定",
  UNBOUND: "待绑定",
  UNREAD: "未读",
  READ: "已读",
  PROCESSING: "处理中",
  RETRYING: "重试中",
  SUCCEEDED: "成功",
  FAILED: "失败",
  NORMAL: "普通",
  SAMPLE: "样品",
  URGENT: "急单",
  MID_TEMP_WAX: "中温蜡线",
  LOW_TEMP_WAX: "低温蜡线",
  SAND_OUTSOURCE: "砂型外协",
  CUSTOM_MOLD_REQUIRED: "需定制模具",
  REQUESTED: "待工程确认",
  ISSUED: "已领用",
  RETURNED: "已归还"
};

export function StatusBadge({ value }: { value?: string | null }) {
	const normalizedValue = typeof value === "string" && value.trim() ? value.trim() : "UNKNOWN";
  return (
    <span className={`status-badge status-${normalizedValue.toLowerCase()}`}>
      {statusLabels[normalizedValue] ?? (normalizedValue === "UNKNOWN" ? "状态待同步" : normalizedValue)}
    </span>
  );
}

export function LoadingState({ label = "正在加载" }: { label?: string }) {
  return (
    <div className="state-block" role="status">
      <LoaderCircle className="spin" aria-hidden="true" />
      <span>{label}</span>
    </div>
  );
}

export function EmptyState({
  title,
  description,
  action
}: {
  title: string;
  description: string;
  action?: ReactNode;
}) {
  return (
    <div className="state-block empty-state">
      <Inbox aria-hidden="true" />
      <strong>{title}</strong>
      <span>{description}</span>
      {action}
    </div>
  );
}

export function ErrorNotice({
  error,
  onRetry
}: {
  error: unknown;
  onRetry?: () => void;
}) {
  const message = error instanceof Error ? error.message : "加载失败";
  const details = error instanceof ApiError ? error.details : [];
  return (
    <div className="error-notice" role="alert">
      <AlertCircle aria-hidden="true" />
      <div>
        <strong>{message}</strong>
        {details.map((detail) => (
          <p key={`${detail.field}-${detail.message}`}>
            {detail.field}：{detail.message}
          </p>
        ))}
      </div>
      {onRetry && (
        <button className="button button-secondary" type="button" onClick={onRetry}>
          重试
        </button>
      )}
    </div>
  );
}

export function Modal({
  title,
  description,
  children,
  onClose,
  width = "medium"
}: {
  title: string;
  description?: string;
  children: ReactNode;
  onClose: () => void;
  width?: "small" | "medium" | "large";
}) {
  const dialogRef = useRef<HTMLElement>(null);
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;

  useEffect(() => {
    const dialog = dialogRef.current;
    const previousFocus = document.activeElement as HTMLElement | null;

    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") {
        event.preventDefault();
        onCloseRef.current();
        return;
      }
      if (event.key !== "Tab" || !dialog) return;

      const focusable = Array.from(
        dialog.querySelectorAll<HTMLElement>(
          'button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled), [href], [tabindex]:not([tabindex="-1"])'
        )
      );
      if (focusable.length === 0) {
        event.preventDefault();
        dialog.focus();
        return;
      }

      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    }

    document.addEventListener("keydown", handleKeyDown);
    return () => {
      document.removeEventListener("keydown", handleKeyDown);
      previousFocus?.focus();
    };
  }, []);

  return (
    <div className="modal-backdrop" role="presentation" onMouseDown={onClose}>
      <section
        ref={dialogRef}
        className={`modal modal-${width}`}
        role="dialog"
        aria-modal="true"
        aria-labelledby="modal-title"
        tabIndex={-1}
        onMouseDown={(event) => event.stopPropagation()}
      >
        <header className="modal-header">
          <div>
            <h2 id="modal-title">{title}</h2>
            {description && <p>{description}</p>}
          </div>
          <button className="icon-button" type="button" onClick={onClose} aria-label="关闭">
            <X aria-hidden="true" />
          </button>
        </header>
        <div className="modal-body">{children}</div>
      </section>
    </div>
  );
}

export function Field({
  label,
  required,
  hint,
  children
}: {
  label: string;
  required?: boolean;
  hint?: string;
  children: ReactNode;
}) {
  return (
    <label className="field">
      <span>
        {label}
        {required && <b aria-hidden="true"> *</b>}
      </span>
      {children}
      {hint && <small>{hint}</small>}
    </label>
  );
}

export function SubmitActions({
  pending,
  submitLabel,
  onCancel
}: {
  pending: boolean;
  submitLabel: string;
  onCancel: () => void;
}) {
  return (
    <footer className="form-actions">
      <button className="button button-secondary" type="button" onClick={onCancel}>
        取消
      </button>
      <button className="button button-primary" type="submit" disabled={pending}>
        {pending && <LoaderCircle className="spin" aria-hidden="true" />}
        {pending ? "正在提交" : submitLabel}
      </button>
    </footer>
  );
}

export function FormShell({
  children,
  onSubmit
}: {
  children: ReactNode;
  onSubmit: (event: FormEvent<HTMLFormElement>) => void | Promise<void>;
}) {
  return <form onSubmit={onSubmit}>{children}</form>;
}

export function formatDate(value: string | null | undefined, includeTime = false) {
  if (!value) return "—";
  return new Intl.DateTimeFormat("zh-CN", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    ...(includeTime
      ? { hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false }
      : {})
  }).format(new Date(value));
}

export function formatQuantity(value: number) {
  return new Intl.NumberFormat("zh-CN", { maximumFractionDigits: 3 }).format(value);
}
