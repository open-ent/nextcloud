import { SxProps } from "@mui/material";

import { columnBoxStyle, flexStartBoxStyle } from "~/styles/boxStyles";
import { italic } from "~/styles/fontStyles";

export const shareStructuresContentStyle: SxProps = {
  ...columnBoxStyle,
  margin: "2rem",
  marginBottom: "1rem",
  gap: "1rem",
};

export const shareStructuresInputRowStyle: SxProps = {
  ...flexStartBoxStyle,
  gap: "1rem",
};

export const uaiInputStyle: SxProps = {
  width: "15rem",
  "& .MuiOutlinedInput-root": {
    border: "1px solid #EBEBEB",
    borderRadius: "0.5rem",
    padding: "0.5rem 1rem",
  },
  "& .MuiOutlinedInput-notchedOutline": {
    border: "none",
  },
  "& .MuiInputBase-input": {
    padding: "0rem 0rem",
  },
};

export const rulesListStyle: SxProps = {
  ...columnBoxStyle,
  gap: "0.5rem",
};

export const ruleRowStyle: SxProps = {
  ...flexStartBoxStyle,
  gap: "0.5rem",
  justifyContent: "space-between",
  padding: "0.5rem 1rem",
  border: "1px solid #EBEBEB",
  borderRadius: "0.5rem",
};

export const infoStyle: SxProps = {
  ...italic,
};

export const errorStyle: SxProps = {
  color: "#D32F2F",
};
