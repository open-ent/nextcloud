import { FC } from "react";

import { Box, MenuItem, Select, SelectChangeEvent, Typography } from "@mui/material";
import { useTranslation } from "react-i18next";

import { useGlobalProvider } from "~/providers/GlobalProvider";
import { columnBoxStyle } from "~/styles/boxStyles";

// Un admin local qui gère PLUSIEURS établissements doit choisir lequel éditer (il arrive
// déjà sur le sien s'il n'en a qu'un — pas de sélecteur dans ce cas). Le super-admin ne
// choisit jamais d'établissement ici : il ne règle que le préfixe national.
export const StructureSelector: FC = () => {
  const { t } = useTranslation("nextcloud");
  const { isAdmc, myStructures, selectedStructureId, setSelectedStructureId } =
    useGlobalProvider();

  if (isAdmc || myStructures.length <= 1) {
    return null;
  }

  const handleChange = (event: SelectChangeEvent) => {
    setSelectedStructureId(event.target.value);
  };

  return (
    <Box sx={columnBoxStyle}>
      <Typography variant="body2">
        {t("nextcloud.console.structure.selector.label")}
      </Typography>
      <Select
        size="small"
        value={selectedStructureId ?? ""}
        onChange={handleChange}
      >
        {myStructures.map((structure) => (
          <MenuItem key={structure.id} value={structure.id}>
            {structure.name}
          </MenuItem>
        ))}
      </Select>
    </Box>
  );
};
