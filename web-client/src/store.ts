import { configureStore } from '@reduxjs/toolkit';
import { firemudApi } from './api/firemudApi';

export const store = configureStore({
  reducer: {
    [firemudApi.reducerPath]: firemudApi.reducer,
  },
  middleware: (getDefaultMiddleware) =>
    getDefaultMiddleware().concat(firemudApi.middleware),
});

export type RootState = ReturnType<typeof store.getState>;
export type AppDispatch = typeof store.dispatch;
