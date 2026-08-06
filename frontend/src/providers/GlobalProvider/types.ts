import { ChangeEvent, KeyboardEvent, ReactNode } from "react";

export interface GlobalProviderContextType {
  desktopConfigValues: DesktopConfig;
  inputValues: DesktopConfig;
  inputExtension: string;
  disabledSave: boolean;
  showSuccessAlert: boolean;
  saveError: string | null;
  setShowSuccessAlert: (value: boolean) => void;
  setInputExtension: (extension: string) => void;
  handleSubmitNewConfig: () => void;
  handleCancelNewConfig: () => void;
  handleSyncFolderChange: (event: ChangeEvent<HTMLInputElement>) => void;
  handleUploadLimitChange: (event: ChangeEvent<HTMLInputElement>) => void;
  handleDownloadLimitChange: (event: ChangeEvent<HTMLInputElement>) => void;
  handleExcludedExtensionsChange: (
    event: ChangeEvent<HTMLInputElement>,
  ) => void;
  handleAddExcludedExtensions: (event: KeyboardEvent<HTMLDivElement>) => void;
  handleRemoveExcludedExtension: (extension: string) => void;
}

export interface GlobalProviderProps {
  children: ReactNode;
}

export type DesktopConfig = {
  downloadLimit: number;
  excludedExtensions: string[];
  syncFolder: string;
  uploadLimit: number;
};
