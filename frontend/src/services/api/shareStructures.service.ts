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

export const shareStructuresApi = emptySplitApi.injectEndpoints({
  endpoints: (builder) => ({
    getShareStructures: builder.query<ShareStructureRule[], void>({
      query: () => `/admin/share-structures`,
      providesTags: ["shareStructures"],
    }),
    resolveStructureByUai: builder.query<
      { id: string; name: string; UAI: string },
      string
    >({
      query: (uai: string) => `/admin/share-structures/resolve?UAI=${uai}`,
    }),
    addShareStructure: builder.mutation<
      unknown,
      { targetUai: string }
    >({
      query: (body) => ({
        url: `/admin/share-structures`,
        method: "POST",
        body,
      }),
      invalidatesTags: ["shareStructures"],
    }),
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
  useLazyResolveStructureByUaiQuery,
  useAddShareStructureMutation,
  useDeleteShareStructureMutation,
} = shareStructuresApi;
