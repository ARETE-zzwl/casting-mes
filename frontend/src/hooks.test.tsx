import { render, screen, act } from "@testing-library/react";
import { useAsyncData } from "./hooks";
import { describe, expect, it } from "vitest";

function Probe({ loader }: { loader: () => Promise<string> }) {
  const state = useAsyncData(loader, [loader]);
  return <span>{state.data ?? "loading"}</span>;
}

describe("useAsyncData", () => {
  it("drops an older response after a newer query starts", async () => {
    let resolveOld!: (value: string) => void;
    let resolveNew!: (value: string) => void;
    const oldLoader = () => new Promise<string>((resolve) => { resolveOld = resolve; });
    const newLoader = () => new Promise<string>((resolve) => { resolveNew = resolve; });
    const view = render(<Probe loader={oldLoader} />);
    view.rerender(<Probe loader={newLoader} />);
    await act(async () => { resolveNew("new result"); });
    expect(screen.getByText("new result")).toBeInTheDocument();
    await act(async () => { resolveOld("old result"); });
    expect(screen.queryByText("old result")).not.toBeInTheDocument();
    expect(screen.getByText("new result")).toBeInTheDocument();
  });
});
