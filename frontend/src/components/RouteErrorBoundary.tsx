import { Component, type ReactNode } from "react";
import { ErrorNotice } from "./ui";

export class RouteErrorBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false };

  static getDerivedStateFromError() {
    return { failed: true };
  }

  render() {
    if (this.state.failed) {
      return <ErrorNotice error={new Error("页面加载失败，请检查网络后重试")}
        onRetry={() => window.location.reload()} />;
    }
    return this.props.children;
  }
}
