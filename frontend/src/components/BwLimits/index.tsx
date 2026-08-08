import { FC } from "react";

import { Box, TextField, Typography } from "@mui/material";
import { useTranslation } from "react-i18next";

import {
  customColumnBoxStyle,
  inputsContentStyle,
  inputStyle,
  inputTitleStyle,
  instructionsStyle,
} from "./style";
import { OverrideBadge } from "~/components/OverrideBadge";
import { useGlobalProvider } from "~/providers/GlobalProvider";
import { flexStartBoxStyle } from "~/styles/boxStyles";

export const BwLimits: FC = () => {
  const { t } = useTranslation("nextcloud");
  const {
    inputValues: { uploadLimit, downloadLimit },
    handleUploadLimitChange,
    handleDownloadLimitChange,
    selectedStructureId,
    structureOverrides,
  } = useGlobalProvider();

  return (
    <Box>
      <Typography variant="h2" sx={flexStartBoxStyle}>
        {t("nextcloud.console.bandwidth")}
      </Typography>
      {selectedStructureId && (
        <OverrideBadge
          isOverridden={!!(structureOverrides?.uploadLimit || structureOverrides?.downloadLimit)}
        />
      )}
      <Typography variant="body2" sx={instructionsStyle}>
        {t("nextcloud.console.bandwidth.instructions")}
      </Typography>
      <Box sx={inputsContentStyle}>
        <Box sx={customColumnBoxStyle}>
          <Box sx={inputTitleStyle}>
            <Typography variant="body1">
              {t("nextcloud.console.upload")}
            </Typography>
            <Typography variant="body2">
              {t("nextcloud.console.bandwidth.unit")}
            </Typography>
          </Box>
          <TextField
            id="outlined-basic"
            variant="outlined"
            value={uploadLimit}
            onChange={handleUploadLimitChange}
            sx={inputStyle}
          />
        </Box>
        <Box sx={customColumnBoxStyle}>
          <Box sx={inputTitleStyle}>
            <Typography variant="body1">
              {t("nextcloud.console.download")}
            </Typography>
            <Typography variant="body2">
              {t("nextcloud.console.bandwidth.unit")}
            </Typography>
          </Box>
          <TextField
            id="outlined-basic"
            variant="outlined"
            value={downloadLimit}
            onChange={handleDownloadLimitChange}
            sx={inputStyle}
          />
        </Box>
      </Box>
    </Box>
  );
};
