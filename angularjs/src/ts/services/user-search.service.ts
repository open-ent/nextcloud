import http, { AxiosResponse } from 'axios';
import { ng } from 'entcore';
import { VisibleUser } from '../models';

export interface IUserSearchService {
    // Recherche parmi les utilisateurs ENT visibles par l'utilisateur courant (mêmes droits de
    // communication que la messagerie), via l'endpoint core /communication/visible/search déjà
    // utilisé par le module conversation. On ne garde que les résultats de type "User" : le
    // partage NextCloud natif se fait vers une personne, pas vers un groupe.
    searchVisibleUsers(query: string): Promise<Array<VisibleUser>>;
}

export const userSearchService: IUserSearchService = {
    searchVisibleUsers: async (query: string): Promise<Array<VisibleUser>> => {
        return http.get(`/communication/visible/search`, { params: { query } })
            .then((res: AxiosResponse) => (res.data || [])
                .filter((visible: any) => visible.type === 'User')
                .map((visible: any): VisibleUser => ({
                    id: visible.id,
                    displayName: visible.displayName,
                    profile: visible.profile
                })));
    }
};

export const UserSearchService = ng.service('UserSearchService', (): IUserSearchService => userSearchService);
