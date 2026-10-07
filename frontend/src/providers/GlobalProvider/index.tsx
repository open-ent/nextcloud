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
  MyStructure,
  StructureConfigOverrides,
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
  const {
    useGetDesktopConfigQuery,
    useUpdateDesktopConfigMutation,
    useGetMyStructuresQuery,
    useGetStructureConfigQuery,
    useUpdateStructureConfigMutation,
  } = desktopConfigApi;

  const [selectedStructureId, setSelectedStructureId] = useState<string | null>(
    null,
  );

  const { data: myStructuresData } = useGetMyStructuresQuery(null);
  const isAdmc = !!myStructuresData?.isAdmc;
  const myStructures: MyStructure[] = myStructuresData?.structures ?? [];

  useEffect(() => {
    // Le super-admin ne choisit jamais d'établissement ici (il ne règle que le préfixe
    // national). Un admin local arrive déjà sur SON établissement : s'il n'en gère qu'un,
    // on le sélectionne directement, sans lui demander de choisir. Un sélecteur n'est
    // proposé (cf. StructureSelector) que s'il en gère plusieurs.
    if (!isAdmc && myStructures.length === 1 && !selectedStructureId) {
      setSelectedStructureId(myStructures[0].id);
    }
  }, [isAdmc, myStructures, selectedStructureId]);

  // Réglage national ou surcharge d'établissement, selon la sélection : un seul des deux
  // appels est actif à la fois (skip), l'autre garde son cache pour un aller-retour rapide.
  const { data: nationalData } = useGetDesktopConfigQuery(null, {
    skip: !!selectedStructureId,
  });
  const { data: structureData } = useGetStructureConfigQuery(
    selectedStructureId as string,
    {
      skip: !selectedStructureId,
    },
  );
  const data = selectedStructureId ? structureData : nationalData;
  const structureOverrides: StructureConfigOverrides | null =
    selectedStructureId && structureData?.overrides
      ? structureData.overrides
      : null;

  const [updateDesktopConfig] = useUpdateDesktopConfigMutation();
  const [updateStructureConfig] = useUpdateStructureConfigMutation();
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
      // La config d'établissement est partielle (pas de limites de bande passante à ce
      // niveau, cf. types.ts) : on complète avec les valeurs par défaut de l'écran pour que
      // les champs non concernés restent neutres/désactivés plutôt qu'undefined.
      const merged = { ...initialDesktopConfigValues, ...data };
      setDesktopConfigValues(merged);
      setInputValues(merged);
    }
  }, [data]);

  useEffect(() => {
    // En mode établissement, pas de bande passante à valider (réglage national uniquement) :
    // le backend exige un objet complet UNIQUEMENT pour le national (cf. handleSubmitNewConfig).
    const isValid = selectedStructureId
      ? true
      : !!inputValues.syncFolder &&
        inputValues.uploadLimit > 0 &&
        inputValues.downloadLimit > 0;
    const unchanged =
      JSON.stringify(inputValues) === JSON.stringify(desktopConfigValues);
    setDisabledSave(unchanged || !isValid);
  }, [inputValues, desktopConfigValues, selectedStructureId]);

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
      if (selectedStructureId) {
        await updateStructureConfig({
          structureId: selectedStructureId,
          config: {
            syncFolder: inputValues.syncFolder,
            excludedExtensions: inputValues.excludedExtensions,
            downloadLimit: inputValues.downloadLimit,
            uploadLimit: inputValues.uploadLimit,
          },
        }).unwrap();
      } else {
        await updateDesktopConfig(inputValues).unwrap();
      }
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
      isAdmc,
      myStructures,
      selectedStructureId,
      setSelectedStructureId,
      structureOverrides,
    }),
    [
      desktopConfigValues,
      inputValues,
      inputExtension,
      disabledSave,
      showSuccessAlert,
      saveError,
      isAdmc,
      myStructures,
      selectedStructureId,
      structureOverrides,
    ],
  );

  return (
    <GlobalProviderContext.Provider value={value}>
      {children}
    </GlobalProviderContext.Provider>
  );
};
