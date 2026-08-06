import { FC } from "react";

import { Box, TextField, Typography } from "@mui/material";
import { useTranslation } from "react-i18next";

import {
  syncFolderContentStyle,
  syncFolderInputPropsStyle,
  syncFolderInputStyle,
  syncFolderInstructionsStyle,
  syncFolderStyle,
} from "./style";
import { useGlobalProvider } from "~/providers/GlobalProvider";
import { columnBoxStyle, flexStartBoxStyle } from "~/styles/boxStyles";

export const SyncFolder: FC = () => {
  const { t } = useTranslation("nextcloud");
  const {
    inputValues: { syncFolder },
    handleSyncFolderChange,
  } = useGlobalProvider();

  return (
    <Box>
      <Typography variant="h2" sx={syncFolderStyle}>
        {t("nextcloud.console.sync.folder")}
      </Typography>
      <Box sx={syncFolderContentStyle}>
        <Box sx={flexStartBoxStyle}>
          <TextField
            id="outlined-basic"
            variant="outlined"
            value={syncFolder}
            onChange={handleSyncFolderChange}
            sx={syncFolderInputStyle}
            InputProps={{
              sx: { syncFolderInputPropsStyle },
            }}
          />
          <Box sx={columnBoxStyle}>
            <Typography variant="body2">
              {t("nextcloud.console.complete.path")}
            </Typography>
            <Typography variant="body1">
              {t("nextcloud.console.folder.root.location") + syncFolder}
            </Typography>
          </Box>
        </Box>
        <Typography variant="body2" sx={syncFolderInstructionsStyle}>
          {t("nextcloud.console.folder.location.instructions")}
        </Typography>
        <Typography variant="body2" sx={syncFolderInstructionsStyle}>
          {t("nextcloud.console.folder.os.note")}
        </Typography>
      </Box>
    </Box>
  );
};
