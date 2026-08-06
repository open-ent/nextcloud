import {
  ChangeEvent,
  createContext,
  FC,
  KeyboardEvent,
  useContext,
  useEffect,
  useMemo,
  useState,
} from "react";

import { desktopConfigApi } from "~/services/api/desktopConfig.service";
import {
  DesktopConfig,
  GlobalProviderContextType,
  GlobalProviderProps,
} from "./types";
import {
  initialDesktopConfigValues,
  processFolderPath,
  processInputValue,
} from "./utils";

const GlobalProviderContext = createContext<GlobalProviderContextType | null>(
  null,
);

export const useGlobalProvider = () => {
  const context = useContext(GlobalProviderContext);
  if (!context) {
    throw new Error("useGlobalProvider must be used within an GlobalProvider");
  }
  return context;
};

export const GlobalProvider: FC<GlobalProviderProps> = ({ children }) => {
  const { useGetDesktopConfigQuery, useUpdateDesktopConfigMutation } =
    desktopConfigApi;
  const { data } = useGetDesktopConfigQuery(null);
  const [updateDesktopConfig] = useUpdateDesktopConfigMutation();
  const [desktopConfigValues, setDesktopConfigValues] = useState<DesktopConfig>(
    initialDesktopConfigValues,
  );
  const [inputValues, setInputValues] = useState<DesktopConfig>(
    initialDesktopConfigValues,
  );
  const [inputExtension, setInputExtension] = useState<string>("");
  const [disabledSave, setDisabledSave] = useState<boolean>(true);
  const [showSuccessAlert, setShowSuccessAlert] = useState<boolean>(false);
  const [saveError, setSaveError] = useState<string | null>(null);

  useEffect(() => {
    if (data) {
      setDesktopConfigValues(data);
      setInputValues(data);
    }
  }, [data]);

  useEffect(() => {
    // Le backend exige TOUJOURS un objet complet et valide (dossier renseigné, bande
    // passante > 0 dans les deux sens) : sans ce contrôle, "Enregistrer" pouvait s'activer
    // sur une combinaison que le serveur refusait ensuite silencieusement (cf. handleSubmitNewConfig).
    const isValid =
      !!inputValues.syncFolder &&
      inputValues.uploadLimit > 0 &&
      inputValues.downloadLimit > 0;
    const unchanged = JSON.stringify(inputValues) === JSON.stringify(desktopConfigValues);
    setDisabledSave(unchanged || !isValid);
  }, [inputValues, desktopConfigValues]);

  const showSuccessAlertTimeout = () => {
    setShowSuccessAlert(true);
    setTimeout(() => {
      setShowSuccessAlert(false);
    }, 5000);
  };

  const handleSubmitNewConfig = async () => {
    setSaveError(null);
    try {
      // .unwrap() : sans lui, une erreur (ex. bande passante à 0, invalide côté backend)
      // était silencieusement ignorée et le message de succès s'affichait quand même.
      await updateDesktopConfig(inputValues).unwrap();
      setInputExtension("");
      showSuccessAlertTimeout();
    } catch (err: any) {
      setSaveError(
        err?.data?.error === "Invalid configuration"
          ? "Configuration invalide : la bande passante (émission/réception) doit être supérieure à 0."
          : "Une erreur est survenue, les paramètres n'ont pas été enregistrés.",
      );
    }
  };

  const handleCancelNewConfig = () => {
    setInputValues(desktopConfigValues);
    setInputExtension("");
  };

  const handleSyncFolderChange = (event: ChangeEvent<HTMLInputElement>) => {
    const value = event.target.value;
    const processedValue = processFolderPath(value);
    setInputValues((prev) => ({
      ...prev,
      syncFolder: processedValue,
    }));
  };

  const handleUploadLimitChange = (event: ChangeEvent<HTMLInputElement>) => {
    const value = event.target.value;
    const processedValue = processInputValue(value);

    if (processedValue) {
      setInputValues((prev) => ({
        ...prev,
        uploadLimit: parseInt(processedValue),
      }));
    }
  };

  const handleDownloadLimitChange = (event: ChangeEvent<HTMLInputElement>) => {
    const value = event.target.value;
    const processedValue = processInputValue(value);

    if (processedValue) {
      setInputValues((prev) => ({
        ...prev,
        downloadLimit: parseInt(processedValue),
      }));
    }
  };

  const handleExcludedExtensionsChange = (
    event: ChangeEvent<HTMLInputElement>,
  ) => {
    const value = event.target.value;
    const isValid = /^[.][a-zA-Z0-9]+$/.test(value) || value === ".";
    if (isValid) setInputExtension(value);
  };

  const handleAddExcludedExtensions = (
    event: KeyboardEvent<HTMLDivElement>,
  ) => {
    if (event.key === "Enter" && inputExtension !== ".") {
      setInputValues((prev) => ({
        ...prev,
        excludedExtensions: [...prev.excludedExtensions, inputExtension],
      }));
      setInputExtension(".");
    }
  };

  const handleRemoveExcludedExtension = (extension: string) => {
    setInputValues((prev) => ({
      ...prev,
      excludedExtensions: prev.excludedExtensions.filter(
        (excludedExtension) => excludedExtension !== extension,
      ),
    }));
  };

  const value = useMemo<GlobalProviderContextType>(
    () => ({
      desktopConfigValues,
      inputValues,
      inputExtension,
      setInputExtension,
      disabledSave,
      showSuccessAlert,
      saveError,
      setShowSuccessAlert,
      handleSubmitNewConfig,
      handleCancelNewConfig,
      handleSyncFolderChange,
      handleUploadLimitChange,
      handleDownloadLimitChange,
      handleExcludedExtensionsChange,
      handleAddExcludedExtensions,
      handleRemoveExcludedExtension,
    }),
    [
      desktopConfigValues,
      inputValues,
      inputExtension,
      disabledSave,
      showSuccessAlert,
      saveError,
    ],
  );

  return (
    <GlobalProviderContext.Provider value={value}>
      {children}
    </GlobalProviderContext.Provider>
  );
};
