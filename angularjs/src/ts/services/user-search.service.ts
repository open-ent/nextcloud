import http, { AxiosResponse } from 'axios';
import { ng } from 'entcore';
import { VisibleUser } from '../models';

export interface IUserSearchService {
    // Recherche parmi les utilisateurs ENT visibles par l'utilisateur courant (mêmes droits de
    // communication que la messagerie), via l'endpoint core /communication/visible/search déjà
    // utilisé par le module conversation. On ne garde que les résultats de type "User" : le
    // partage NextCloud natif se fait vers une personne, pas vers un groupe.
    // Complété par les utilisateurs des structures autorisées au partage NextCloud inter-établissements
    // (réglage propre au connecteur, indépendant du modèle de communication générique), via
    // /nextcloud/share/search-users.
    searchVisibleUsers(query: string): Promise<Array<VisibleUser>>;
}

export const userSearchService: IUserSearchService = {
    searchVisibleUsers: async (query: string): Promise<Array<VisibleUser>> => {
        const visibleUsers: Promise<Array<VisibleUser>> = http.get(`/communication/visible/search`, { params: { query } })
            .then((res: AxiosResponse) => (res.data || [])
                .filter((visible: any) => visible.type === 'User')
                .map((visible: any): VisibleUser => ({
                    id: visible.id,
                    displayName: visible.displayName,
                    profile: visible.profile
                })))
            .catch(() => []);

        const crossStructureUsers: Promise<Array<VisibleUser>> = http.get(`/nextcloud/share/search-users`, { params: { query } })
            .then((res: AxiosResponse) => (res.data || [])
                .map((user: any): VisibleUser => ({
                    id: user.id,
                    displayName: user.displayName,
                    profile: user.profile
                })))
            .catch(() => []);

        const [visible, crossStructure] = await Promise.all([visibleUsers, crossStructureUsers]);
        const merged: Array<VisibleUser> = [...visible];
        const knownIds: Set<string> = new Set(visible.map((user: VisibleUser) => user.id));
        crossStructure.forEach((user: VisibleUser) => {
            if (!knownIds.has(user.id)) {
                merged.push(user);
                knownIds.add(user.id);
            }
        });
        return merged;
    }
};

export const UserSearchService = ng.service('UserSearchService', (): IUserSearchService => userSearchService);
