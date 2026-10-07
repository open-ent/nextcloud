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

  // Réglage national vs par établissement (cf. écran /nextcloud/desktop) : selectedStructureId
  // null = on édite le réglage national ; sinon on édite la surcharge de cet établissement.
  isAdmc: boolean;
  myStructures: MyStructure[];
  selectedStructureId: string | null;
  setSelectedStructureId: (structureId: string | null) => void;
  structureOverrides: StructureConfigOverrides | null;
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

// Surcharge d'établissement : partielle, chaque établissement ne surcharge que ce qu'il
// souhaite (dossier, extensions, bande passante — celle-ci dépend de l'infrastructure propre
// à chaque établissement, donc réglable ici aussi, pas seulement au niveau national).
export type StructureOverride = {
  syncFolder?: string;
  excludedExtensions?: string[];
  downloadLimit?: number;
  uploadLimit?: number;
};

export type MyStructure = {
  id: string;
  name: string;
  UAI?: string;
};

// "overrides" indique si la valeur vient d'une surcharge locale (true) ou est héritée du
// national/défaut (false) — pour afficher "personnalisé" vs "hérité" dans l'écran d'un établissement.
export type StructureConfigOverrides = {
  syncFolder: boolean;
  excludedExtensions: boolean;
  downloadLimit: boolean;
  uploadLimit: boolean;
};
