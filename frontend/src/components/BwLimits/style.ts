import { flexStartBoxStyle } from "~/styles/boxStyles";

export const inputStyle = {
  marginTop: "1rem",
  width: "10rem",
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

export const inputTitleStyle = {
  ...flexStartBoxStyle,
  gap: "0.5rem",
};

export const inputsContentStyle = {
  ...flexStartBoxStyle,
  margin: "2rem",
  gap: "6rem",
};

export const customColumnBoxStyle = {
  display: "flex",
  flexDirection: "column",
};

export const instructionsStyle = {
  fontStyle: "italic",
  display: "block",
  margin: "0 2rem 1rem",
};
