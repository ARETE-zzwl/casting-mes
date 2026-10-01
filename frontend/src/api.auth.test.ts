import { beforeEach, afterEach, expect, it, vi } from "vitest";

const fetchMock = vi.fn();
beforeEach(() => { vi.resetModules(); vi.stubGlobal("fetch", fetchMock); fetchMock.mockReset(); });
afterEach(() => vi.unstubAllGlobals());
const response = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

it("sends a CSRF token and credentials with login, without storing an auth token", async () => {
  const { api } = await import("./api");
  fetchMock.mockResolvedValueOnce(response({ token: "csrf-value", headerName: "X-XSRF-TOKEN" }))
    .mockResolvedValueOnce(response({ user: { employeeCode: "W001" }, mustChangePassword: false }));
  await api.auth.login({ employeeCode: "W001", password: "test-password-only" });
  expect(fetchMock.mock.calls[1][1]).toMatchObject({ credentials: "include", headers: { "X-XSRF-TOKEN": "csrf-value" } });
  expect(localStorage.getItem("token")).toBeNull();
});

it("handles Spring 401 responses and signals session expiration", async () => {
  const { api } = await import("./api");
  const expired = vi.fn();
  window.addEventListener("mes:session-expired", expired);
  try {
    fetchMock.mockResolvedValueOnce(response({ status: 401, error: "Unauthorized" }, 401));
    await expect(api.tasks.list()).rejects.toMatchObject({ status: 401, code: "REQUEST_FAILED" });
    expect(expired).toHaveBeenCalledOnce();
  } finally { window.removeEventListener("mes:session-expired", expired); }
});

it("invalidates a stale CSRF cache for the next explicit attempt, without replaying a write", async () => {
  const { api } = await import("./api");
  fetchMock.mockResolvedValueOnce(response({ token: "old", headerName: "X-XSRF-TOKEN" }))
    .mockResolvedValueOnce(response({}, 403));
  await expect(api.auth.logout()).rejects.toMatchObject({ status: 403 });
  expect(fetchMock).toHaveBeenCalledTimes(2);
  fetchMock.mockResolvedValueOnce(response({ token: "new", headerName: "X-XSRF-TOKEN" }))
    .mockResolvedValueOnce(new Response(null, { status: 204 }));
  await api.auth.logout();
  expect(fetchMock.mock.calls[3][1].headers["X-XSRF-TOKEN"]).toBe("new");
});
