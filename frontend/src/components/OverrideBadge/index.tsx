import { FC } from "react";

import { Chip } from "@mui/material";
import { useTranslation } from "react-i18next";

// Indique, pour un champ de l'écran d'un établissement, si sa valeur vient d'une surcharge
// locale ("personnalisé") ou est héritée du national/défaut. Un Chip coloré plutôt qu'un texte
// discret : ce point avait été signalé illisible en petite légende grise.
export const OverrideBadge: FC<{ isOverridden: boolean }> = ({
  isOverridden,
}) => {
  const { t } = useTranslation("nextcloud");

  return (
    <Chip
      color={isOverridden ? "success" : "info"}
      label={t(
        isOverridden
          ? "nextcloud.console.structure.override.badge"
          : "nextcloud.console.structure.inherited.badge",
      )}
      sx={{
        mb: 1,
        fontWeight: 700,
        fontSize: "1.5rem",
        height: "auto",
        py: 1.5,
        px: 1,
        "& .MuiChip-label": { fontSize: "1.5rem", px: 1.5 },
      }}
    />
  );
};
