import { lazy, Suspense } from "react";
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { RouteErrorBoundary } from "./RouteErrorBoundary";

afterEach(() => { cleanup(); vi.restoreAllMocks(); });

it("keeps navigation usable when a route chunk fails and recovers on navigation", async () => {
  vi.spyOn(console, "error").mockImplementation(() => {});
  const BrokenPage = lazy(() => Promise.reject(new Error("chunk unavailable")));
  const { rerender } = render(<><nav>导航</nav><RouteErrorBoundary key="broken"><Suspense fallback="加载中"><BrokenPage /></Suspense></RouteErrorBoundary></>);
  expect(await screen.findByRole("alert")).toHaveTextContent("页面加载失败");
  expect(screen.getByRole("navigation")).toHaveTextContent("导航");
  expect(screen.getByRole("button", { name: "重试" })).toBeEnabled();
  rerender(<><nav>导航</nav><RouteErrorBoundary key="next"><h1>下一页</h1></RouteErrorBoundary></>);
  expect(screen.getByRole("heading", { name: "下一页" })).toBeInTheDocument();
  expect(screen.queryByRole("alert")).not.toBeInTheDocument();
});
