import { FC } from "react";

import { Alert, Box, Button, Typography } from "@mui/material";
import { useTranslation } from "react-i18next";

import { alertStyle, consoleContentStyle, consoleTitleStyle } from "./style";
import { BwLimits } from "~/components/BwLimits";
import { ExcludedExtensions } from "~/components/ExcludedExtensions";
import { ShareStructures } from "~/components/ShareStructures";
import { NextcloudConsoleIcon } from "~/components/SVG/NextcloudConsoleIcon";
import { SyncFolder } from "~/components/SyncFolder";
import { useGlobalProvider } from "~/providers/GlobalProvider";
import {
  columnBoxStyle,
  flexEndBoxStyle,
  spaceBetweenBoxStyle,
} from "~/styles/boxStyles";

export const MainPage: FC = () => {
  const { t } = useTranslation("nextcloud");
  const {
    handleSubmitNewConfig,
    handleCancelNewConfig,
    disabledSave,
    showSuccessAlert,
    saveError,
    inputValues,
  } = useGlobalProvider();
  return (
    <Box>
      <Box sx={spaceBetweenBoxStyle}>
        <Box sx={columnBoxStyle}>
          <Box sx={consoleTitleStyle}>
            <NextcloudConsoleIcon />
            <Typography variant="h1">{t("nextcloud.console.title")}</Typography>
          </Box>
          <Typography variant="body2">
            {t("nextcloud.console.subtitle")}
          </Typography>
        </Box>
        <Box sx={flexEndBoxStyle}>
          {showSuccessAlert && (
            <Alert severity="success" sx={alertStyle}>
              {t("nextcloud.console.alert.save")}
            </Alert>
          )}
        </Box>
      </Box>
      <Box sx={{ ...consoleContentStyle }}>
        <Box sx={{ ...columnBoxStyle, gap: "2rem" }}>
          <SyncFolder />
          <BwLimits />
          <ExcludedExtensions />
          <ShareStructures />
        </Box>
        {(!inputValues.syncFolder ||
          inputValues.uploadLimit <= 0 ||
          inputValues.downloadLimit <= 0) && (
          <Alert severity="warning" sx={{ mb: 1 }}>
            {t("nextcloud.console.save.requires.folder")}
          </Alert>
        )}
        {saveError && (
          <Alert severity="error" sx={{ mb: 1 }}>
            {saveError}
          </Alert>
        )}
        <Box sx={{ ...flexEndBoxStyle, gap: "2rem" }}>
          <Button variant="outlined" onClick={handleCancelNewConfig}>
            {t("nextcloud.console.cancel")}
          </Button>
          <Button
            variant="contained"
            onClick={handleSubmitNewConfig}
            disabled={disabledSave}
          >
            {t("nextcloud.console.save")}
          </Button>
        </Box>
      </Box>
    </Box>
  );
};
