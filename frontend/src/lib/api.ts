/**
 * src/lib/api.ts
 * 백엔드 API 클라이언트.
 *
 * 인증은 httpOnly 쿠키로 처리되므로 credentials: "include"가 필수입니다.
 * (토큰을 localStorage에 두지 않습니다 — XSS로 새어 나갈 수 있습니다.)
 */

export const API_BASE =
  process.env.NEXT_PUBLIC_API_BASE ?? "http://localhost:8080";

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

export class UnauthorizedError extends ApiError {
  constructor() {
    super("로그인이 필요합니다.", 401);
    this.name = "UnauthorizedError";
  }
}

async function request<T>(
  path: string,
  init: RequestInit = {},
): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    ...init,
    credentials: "include",
    headers: {
      "Content-Type": "application/json",
      ...(init.headers ?? {}),
    },
  });

  if (response.status === 401) {
    throw new UnauthorizedError();
  }

  if (!response.ok) {
    let message = `요청 실패 (HTTP ${response.status})`;
    try {
      const body = await response.json();
      if (body?.message) {
        message = body.message;
      }
    } catch {
      // 본문이 JSON이 아니면 기본 메시지를 씁니다.
    }
    throw new ApiError(message, response.status);
  }

  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

export function apiGet<T>(path: string, signal?: AbortSignal): Promise<T> {
  return request<T>(path, { method: "GET", signal });
}

export function apiPost<T>(path: string, body?: unknown): Promise<T> {
  return request<T>(path, {
    method: "POST",
    body: body === undefined ? undefined : JSON.stringify(body),
  });
}

/** 쿼리스트링을 만듭니다. undefined/null 값은 제외합니다. */
export function query(params: Record<string, string | number | boolean | undefined | null>): string {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== "") {
      search.set(key, String(value));
    }
  });
  const text = search.toString();
  return text ? `?${text}` : "";
}

// ---------------------------------------------------------------- 인증
export const auth = {
  login: (password: string) => apiPost<{ ok: boolean }>("/api/auth/login", { password }),
  logout: () => apiPost<{ ok: boolean }>("/api/auth/logout"),
  session: () => apiGet<{ authenticated: boolean }>("/api/auth/session"),
};
