import { FC, useEffect, useRef, useState } from "react";

import { Box, Checkbox, Tooltip, Typography } from "@mui/material";

import { chipStyle } from "./style";
import { useGlobalProvider } from "~/providers/GlobalProvider";
import { flexStartBoxStyle } from "~/styles/boxStyles";

export const DeletableChip: FC<{ extension: string }> = ({ extension }) => {
  const { handleRemoveExcludedExtension } = useGlobalProvider();

  const textRef = useRef<HTMLSpanElement | null>(null);
  const [isEllipsis, setIsEllipsis] = useState(false);

  useEffect(() => {
    const element = textRef.current;
    if (element) {
      setIsEllipsis(element.scrollWidth > element.clientWidth);
    }
  }, [extension]);

  // Coché = extension bloquée (présente dans excludedExtensions). Décocher la retire de la
  // liste (donc elle redevient autorisée) — même effet que l'ancien bouton de suppression,
  // mais une case à cocher rend plus explicite qu'on "laisse passer" un défaut préconfiguré
  // plutôt qu'on "supprime" une règle de sécurité.
  return (
    <Box
      sx={{
        ...flexStartBoxStyle,
        gap: ".5rem",
        width: "15rem",
        marginRight: "2rem",
      }}
    >
      <Checkbox
        checked={true}
        onChange={() => handleRemoveExcludedExtension(extension)}
      />
      <Tooltip title={isEllipsis ? extension : ""} followCursor={true}>
        <Typography variant="body1" sx={chipStyle} ref={textRef}>
          {extension}
        </Typography>
      </Tooltip>
    </Box>
  );
};
