import { createApi, fetchBaseQuery } from "@reduxjs/toolkit/query/react";

export const emptySplitApi = createApi({
  baseQuery: fetchBaseQuery({ baseUrl: "/nextcloud/" }),
  tagTypes: ["desktopConfig", "shareStructures"],
  endpoints: () => ({}),
});
