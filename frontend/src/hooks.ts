import { useCallback, useEffect, useRef, useState } from "react";

export function useAsyncData<T>(loader: () => Promise<T>, dependencies: unknown[] = []) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(true);
  const requestId = useRef(0);

  const reload = useCallback(async () => {
    const currentRequestId = ++requestId.current;
    setLoading(true);
    setError(null);
    try {
      const result = await loader();
      if (currentRequestId === requestId.current) setData(result);
    } catch (caught) {
      if (currentRequestId === requestId.current) setError(caught);
    } finally {
      if (currentRequestId === requestId.current) setLoading(false);
    }
  }, dependencies);

  useEffect(() => {
    void reload();
  }, [reload]);

  return { data, error, loading, reload, setData };
}
