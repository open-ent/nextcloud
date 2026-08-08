import { emptySplitApi } from "./emptySplitApi.service";
import { DesktopConfig, StructureOverride } from "~/providers/GlobalProvider/types";

export const desktopConfigApi = emptySplitApi.injectEndpoints({
  endpoints: (builder) => ({
    getDesktopConfig: builder.query({
      query: () => `/desktop/config`,
      providesTags: ["desktopConfig"],
    }),
    updateDesktopConfig: builder.mutation({
      query: (newConfig: DesktopConfig) => ({
        url: `/desktop/config`,
        method: "PUT",
        body: newConfig,
      }),
      invalidatesTags: ["desktopConfig"],
    }),
    // Établissements administrés par l'utilisateur connecté (admin local) ou statut super-admin,
    // pour le sélecteur "national / mon établissement" de l'écran desktop.
    getMyStructures: builder.query({
      query: () => `/desktop/my-structures`,
    }),
    // Configuration effective (national fusionné avec la surcharge locale) pour un établissement,
    // avec un indicateur "overrides" par champ pour distinguer héritée/personnalisée.
    getStructureConfig: builder.query({
      query: (structureId: string) => `/desktop/config/structure/${structureId}`,
      providesTags: (result, error, structureId) => [{ type: "structureConfig", id: structureId }],
    }),
    updateStructureConfig: builder.mutation({
      query: ({ structureId, config }: { structureId: string; config: StructureOverride }) => ({
        url: `/desktop/config/structure/${structureId}`,
        method: "PUT",
        body: config,
      }),
      invalidatesTags: (result, error, { structureId }) => [{ type: "structureConfig", id: structureId }],
    }),
  }),
});

export const {
  useGetDesktopConfigQuery,
  useUpdateDesktopConfigMutation,
  useGetMyStructuresQuery,
  useGetStructureConfigQuery,
  useUpdateStructureConfigMutation,
} = desktopConfigApi;
