// Résultat d'utilisateur ENT renvoyé par l'endpoint core /communication/visible/search
// (mêmes droits de communication que la messagerie), filtré aux entrées de type "User".
export interface VisibleUser {
    id: string;
    displayName: string;
    profile?: string;
}
