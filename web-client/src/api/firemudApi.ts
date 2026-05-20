import {
  useMutation,
  useQuery,
  type UseMutationResult,
  type UseQueryResult,
} from '@tanstack/react-query';

export interface GreetingResponse {
  message: string;
}

export interface RunScriptRequest {
  name: string;
  definition: string;
}

export interface RunScriptResponse {
  output: string;
}

class ApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}

async function fetchJson<T>(
  input: RequestInfo | URL,
  init?: RequestInit
): Promise<T> {
  const response = await fetch(input, {
    headers: {
      'Content-Type': 'application/json',
      ...(init?.headers ?? {}),
    },
    ...init,
  });

  if (!response.ok) {
    throw new ApiError(
      response.status,
      `Request failed with status ${response.status}`
    );
  }

  return (await response.json()) as T;
}

export function useGreetingQuery(): UseQueryResult<GreetingResponse, ApiError> {
  return useQuery({
    queryKey: ['greeting'],
    queryFn: () => fetchJson<GreetingResponse>('/api/greeting'),
  });
}

export function useRunScriptMutation(): UseMutationResult<
  RunScriptResponse,
  ApiError,
  RunScriptRequest
> {
  return useMutation({
    mutationFn: (body) =>
      fetchJson<RunScriptResponse>('/api/scripts/run', {
        method: 'POST',
        body: JSON.stringify(body),
      }),
  });
}
