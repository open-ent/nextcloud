import { FC, useState } from "react";

import CloseIcon from "@mui/icons-material/Close";
import {
  Autocomplete,
  Box,
  Button,
  CircularProgress,
  IconButton,
  TextField,
  Typography,
} from "@mui/material";
import { useTranslation } from "react-i18next";

import {
  errorStyle,
  infoStyle,
  ruleRowStyle,
  rulesListStyle,
  shareStructuresContentStyle,
  shareStructuresInputRowStyle,
  uaiInputStyle,
} from "./style";
import {
  ShareStructureRule,
  StructureOption,
  useAddShareStructureMutation,
  useDeleteShareStructureMutation,
  useGetMyStructureQuery,
  useGetShareStructuresQuery,
  useLazySearchStructuresQuery,
} from "~/services/api/shareStructures.service";
import { flexStartBoxStyle } from "~/styles/boxStyles";

const SEARCH_MIN_LENGTH = 2;
const SEARCH_DEBOUNCE_MS = 300;

// Réglage propre au connecteur NextCloud (indépendant du modèle de communication générique
// entcore) : liste des paires d'établissements autorisés à apparaître dans le picker de partage.
export const ShareStructures: FC = () => {
  const { t } = useTranslation("nextcloud");
  const { data: rules = [] } = useGetShareStructuresQuery();
  const { data: myStructures = [] } = useGetMyStructureQuery();
  const [searchStructures, { data: options = [], isFetching: searching }] =
    useLazySearchStructuresQuery();
  const [addShareStructure, { isLoading: isAdding }] =
    useAddShareStructureMutation();
  const [deleteShareStructure] = useDeleteShareStructureMutation();

  const [selectedStructure, setSelectedStructure] =
    useState<StructureOption | null>(null);
  const [inputValue, setInputValue] = useState("");
  const [error, setError] = useState("");
  let searchTimeout: ReturnType<typeof setTimeout>;

  const handleInputChange = (value: string) => {
    setInputValue(value);
    clearTimeout(searchTimeout);
    if (value.trim().length < SEARCH_MIN_LENGTH) return;
    searchTimeout = setTimeout(
      () => searchStructures(value.trim()),
      SEARCH_DEBOUNCE_MS,
    );
  };

  const handleAdd = async () => {
    if (!selectedStructure) return;
    setError("");
    try {
      await addShareStructure({
        targetStructureId: selectedStructure.id,
      }).unwrap();
      setSelectedStructure(null);
      setInputValue("");
    } catch (err: any) {
      setError(
        err?.data?.error || t("nextcloud.console.share.structures.error"),
      );
    }
  };

  const handleDelete = async (rule: ShareStructureRule) => {
    const label = `${rule.structureName ?? rule.structureId} ↔ ${
      rule.targetStructureName ?? rule.targetStructureId
    }`;
    if (
      typeof window !== "undefined" &&
      !window.confirm(
        t("nextcloud.console.share.structures.delete.confirm", { label }),
      )
    ) {
      return;
    }
    await deleteShareStructure({
      structureId: rule.structureId,
      targetStructureId: rule.targetStructureId,
    });
  };

  return (
    <Box>
      <Typography variant="h2" sx={flexStartBoxStyle}>
        {t("nextcloud.console.share.structures.title")}
      </Typography>
      <Box sx={shareStructuresContentStyle} id="share-structures">
        <Typography variant="body2" sx={infoStyle}>
          {t("nextcloud.console.share.structures.subtitle")}
        </Typography>
        <Typography variant="body2" sx={infoStyle}>
          {t("nextcloud.console.share.structures.explanation")}
        </Typography>
        {myStructures.length > 0 && (
          <Typography variant="body2">
            {t("nextcloud.console.share.structures.my.structure")}{" "}
            <strong>
              {myStructures[0].name} ({myStructures[0].UAI})
            </strong>
          </Typography>
        )}

        <Box sx={shareStructuresInputRowStyle}>
          <Autocomplete
            sx={uaiInputStyle}
            options={options}
            filterOptions={(x) => x}
            loading={searching}
            value={selectedStructure}
            inputValue={inputValue}
            onInputChange={(_, value) => handleInputChange(value)}
            onChange={(_, value) => setSelectedStructure(value)}
            getOptionLabel={(opt) => `${opt.name} (${opt.UAI})`}
            isOptionEqualToValue={(opt, val) => opt.id === val.id}
            noOptionsText={
              inputValue.trim().length < SEARCH_MIN_LENGTH
                ? t("nextcloud.console.share.structures.search.hint")
                : t("nextcloud.console.share.structures.search.empty")
            }
            renderInput={(params) => (
              <TextField
                {...params}
                variant="outlined"
                placeholder={t(
                  "nextcloud.console.share.structures.uai.placeholder",
                )}
                InputProps={{
                  ...params.InputProps,
                  endAdornment: (
                    <>
                      {searching ? <CircularProgress size={16} /> : null}
                      {params.InputProps.endAdornment}
                    </>
                  ),
                }}
              />
            )}
          />
          <Button
            variant="outlined"
            onClick={handleAdd}
            disabled={isAdding || !selectedStructure}
          >
            {t("nextcloud.console.share.structures.add")}
          </Button>
        </Box>
        {error && (
          <Typography variant="body2" sx={errorStyle}>
            {error}
          </Typography>
        )}

        <Box sx={rulesListStyle}>
          {rules.length === 0 && (
            <Typography variant="body2" sx={infoStyle}>
              {t("nextcloud.console.share.structures.empty")}
            </Typography>
          )}
          {rules.map((rule) => (
            <Box key={rule._id} sx={ruleRowStyle}>
              <Typography variant="body1">
                {(rule.structureName || rule.structureId) +
                  (rule.structureUai ? ` (${rule.structureUai})` : "") +
                  " ↔ " +
                  (rule.targetStructureName || rule.targetStructureId) +
                  (rule.targetStructureUai
                    ? ` (${rule.targetStructureUai})`
                    : "")}
              </Typography>
              <IconButton onClick={() => handleDelete(rule)}>
                <CloseIcon />
              </IconButton>
            </Box>
          ))}
        </Box>
      </Box>
    </Box>
  );
};
