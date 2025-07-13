import { createApi, fetchBaseQuery } from '@reduxjs/toolkit/query/react';

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

export const firemudApi = createApi({
  reducerPath: 'firemudApi',
  baseQuery: fetchBaseQuery({ baseUrl: '/api' }),
  endpoints: (builder) => ({
    getGreeting: builder.query<GreetingResponse, void>({
      query: () => '/greeting',
    }),
    runScript: builder.mutation<RunScriptResponse, RunScriptRequest>({
      query: (body) => ({
        url: '/scripts/run',
        method: 'POST',
        body,
      }),
    }),
  }),
});

export const { useGetGreetingQuery, useRunScriptMutation } = firemudApi;
