import { emptySplitApi } from "./emptySplitApi.service";

export interface ShareStructureRule {
  _id: string;
  structureId: string;
  targetStructureId: string;
  structureName?: string;
  structureUai?: string;
  targetStructureName?: string;
  targetStructureUai?: string;
}

export interface StructureOption {
  id: string;
  name: string;
  UAI: string;
}

export const shareStructuresApi = emptySplitApi.injectEndpoints({
  endpoints: (builder) => ({
    getShareStructures: builder.query<ShareStructureRule[], void>({
      query: () => `/admin/share-structures`,
      providesTags: ["shareStructures"],
    }),
    getMyStructure: builder.query<StructureOption[], void>({
      query: () => `/admin/share-structures/my-structure`,
    }),
    searchStructures: builder.query<StructureOption[], string>({
      query: (query: string) =>
        `/admin/share-structures/search-structures?query=${encodeURIComponent(
          query,
        )}`,
    }),
    resolveStructureByUai: builder.query<
      { id: string; name: string; UAI: string },
      string
    >({
      query: (uai: string) => `/admin/share-structures/resolve?UAI=${uai}`,
    }),
    addShareStructure: builder.mutation<unknown, { targetStructureId: string }>(
      {
        query: (body) => ({
          url: `/admin/share-structures`,
          method: "POST",
          body,
        }),
        invalidatesTags: ["shareStructures"],
      },
    ),
    deleteShareStructure: builder.mutation<
      unknown,
      { structureId: string; targetStructureId: string }
    >({
      query: ({ structureId, targetStructureId }) => ({
        url: `/admin/share-structures?structureId=${structureId}&targetStructureId=${targetStructureId}`,
        method: "DELETE",
      }),
      invalidatesTags: ["shareStructures"],
    }),
  }),
});

export const {
  useGetShareStructuresQuery,
  useGetMyStructureQuery,
  useLazySearchStructuresQuery,
  useLazyResolveStructureByUaiQuery,
  useAddShareStructureMutation,
  useDeleteShareStructureMutation,
} = shareStructuresApi;
