import { FC, useState } from "react";

import CloseIcon from "@mui/icons-material/Close";
import { Box, Button, IconButton, TextField, Typography } from "@mui/material";
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
  useAddShareStructureMutation,
  useDeleteShareStructureMutation,
  useGetShareStructuresQuery,
} from "~/services/api/shareStructures.service";
import { flexStartBoxStyle } from "~/styles/boxStyles";

// Réglage propre au connecteur NextCloud (indépendant du modèle de communication générique
// entcore) : liste des paires d'établissements autorisés à apparaître dans le picker de partage.
export const ShareStructures: FC = () => {
  const { t } = useTranslation("nextcloud");
  const { data: rules = [] } = useGetShareStructuresQuery();
  const [addShareStructure, { isLoading: isAdding }] =
    useAddShareStructureMutation();
  const [deleteShareStructure] = useDeleteShareStructureMutation();

  const [uai, setUai] = useState("");
  const [error, setError] = useState("");

  const handleAdd = async () => {
    if (!uai.trim()) return;
    setError("");
    try {
      await addShareStructure({ targetUai: uai.trim() }).unwrap();
      setUai("");
    } catch (err: any) {
      setError(
        err?.data?.error || t("nextcloud.console.share.structures.error"),
      );
    }
  };

  const handleDelete = async (rule: ShareStructureRule) => {
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

        <Box sx={shareStructuresInputRowStyle}>
          <TextField
            variant="outlined"
            value={uai}
            onChange={(e) => setUai(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && handleAdd()}
            sx={uaiInputStyle}
            placeholder={t("nextcloud.console.share.structures.uai.placeholder")}
          />
          <Button
            variant="outlined"
            onClick={handleAdd}
            disabled={isAdding || !uai.trim()}
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
                  (rule.targetStructureUai ? ` (${rule.targetStructureUai})` : "")}
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
