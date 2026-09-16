"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { apiGet, UnauthorizedError } from "@/lib/api";

interface State<T> {
  data: T | null;
  loading: boolean;
  error: string | null;
  unauthorized: boolean;
}

/**
 * 백엔드 GET 요청 훅.
 *
 * - 경로가 바뀌면 이전 요청을 취소합니다(느린 응답이 나중에 도착해 화면을
 *   되돌리는 문제를 막습니다).
 * - refreshMs를 주면 주기적으로 다시 읽습니다(구버전의 자동 새로고침).
 * - 401이면 로그인 화면으로 보낼 수 있도록 unauthorized 플래그를 세웁니다.
 */
export function useApi<T>(path: string | null, refreshMs = 0) {
  const [state, setState] = useState<State<T>>({
    data: null,
    loading: Boolean(path),
    error: null,
    unauthorized: false,
  });

  const controllerRef = useRef<AbortController | null>(null);

  const load = useCallback(async () => {
    if (!path) {
      setState({ data: null, loading: false, error: null, unauthorized: false });
      return;
    }

    controllerRef.current?.abort();
    const controller = new AbortController();
    controllerRef.current = controller;

    setState((previous) => ({ ...previous, loading: true, error: null }));

    try {
      const data = await apiGet<T>(path, controller.signal);
      setState({ data, loading: false, error: null, unauthorized: false });
    } catch (error) {
      if (controller.signal.aborted) {
        return;
      }
      if (error instanceof UnauthorizedError) {
        setState({ data: null, loading: false, error: null, unauthorized: true });
        return;
      }
      setState({
        data: null,
        loading: false,
        error: error instanceof Error ? error.message : "알 수 없는 오류",
        unauthorized: false,
      });
    }
  }, [path]);

  useEffect(() => {
    void load();
    return () => controllerRef.current?.abort();
  }, [load]);

  useEffect(() => {
    if (!refreshMs || refreshMs <= 0) {
      return;
    }
    const timer = setInterval(() => void load(), refreshMs);
    return () => clearInterval(timer);
  }, [refreshMs, load]);

  return { ...state, reload: load };
}
