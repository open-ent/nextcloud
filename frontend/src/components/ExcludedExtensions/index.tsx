import { FC } from "react";

import { Box, TextField, Typography } from "@mui/material";
import { useTranslation } from "react-i18next";

import {
  excludedContentStyle,
  excludedInputStyle,
  excludedListStyle,
  infoStyle,
  inputStyle,
} from "./style";
import { DeletableChip } from "../DeletableChip";
import { OverrideBadge } from "~/components/OverrideBadge";
import { useGlobalProvider } from "~/providers/GlobalProvider";
import { flexStartBoxStyle } from "~/styles/boxStyles";

export const ExcludedExtensions: FC = () => {
  const { t } = useTranslation("nextcloud");

  const {
    inputValues: { excludedExtensions },
    inputExtension,
    setInputExtension,
    handleExcludedExtensionsChange,
    handleAddExcludedExtensions,
    selectedStructureId,
    structureOverrides,
  } = useGlobalProvider();

  const handleFocus = () => {
    if (!inputExtension) setInputExtension(".");
  };

  const handleBlur = () => {
    if (inputExtension === ".") setInputExtension("");
  };

  return (
    <Box>
      <Typography variant="h2" sx={flexStartBoxStyle}>
        {t("nextcloud.console.excluded.extensions")}
      </Typography>
      {selectedStructureId && (
        <OverrideBadge isOverridden={!!structureOverrides?.excludedExtensions} />
      )}
      <Box sx={excludedContentStyle} id="excluded-extensions">
        <Box sx={inputStyle}>
          <TextField
            id="outlined-basic"
            variant="outlined"
            value={inputExtension}
            onChange={handleExcludedExtensionsChange}
            onKeyDown={handleAddExcludedExtensions}
            onFocus={handleFocus}
            onBlur={handleBlur}
            sx={excludedInputStyle}
            placeholder={"+ " + t("nextcloud.console.add")}
          />
          <Typography variant="body2" sx={infoStyle}>
            {t("nextcloud.console.excluded.extensions.instructions")}
          </Typography>
        </Box>
        <Box sx={excludedListStyle}>
          {[...excludedExtensions]
            .sort((a, b) => a.localeCompare(b)) // Sorts extensions in alphabetical order
            .map((extension, index) => (
              <DeletableChip
                key={`${extension}-${index}`}
                extension={extension}
              />
            ))}
        </Box>
      </Box>
    </Box>
  );
};
