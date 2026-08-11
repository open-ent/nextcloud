import http, { AxiosResponse } from 'axios';
import { ng, workspace } from 'entcore';
import { IDocumentResponse, SyncDocument } from "../models";
import models = workspace.v2.models;

export interface INextcloudService {
    openNextcloudLink(document: SyncDocument, nextcloudUrl: string): void;
    getNextcloudUrl(): Promise<string>;
    getIsNextcloudUrlHidden(): Promise<boolean>;
    listDocument(userid: string, path?: string): Promise<Array<SyncDocument>>;
    uploadDocuments(userid: string, files: Array<File>): Promise<AxiosResponse>;
    moveDocument(userid: string, path: string, destPath: string): Promise<AxiosResponse>;
    moveDocumentNextcloudToWorkspace(userid: string, paths: Array<string>, parentId?: string): Promise<AxiosResponse>;
    moveDocumentWorkspaceToCloud(userid: string, ids: Array<string>, cloudDocumentName?: string): Promise<AxiosResponse>;
    copyDocumentWorkspaceToCloud(userid: string, ids: Array<string>, cloudDocumentName?: string): Promise<AxiosResponse>;
    copyDocumentToWorkspace(userid: string, paths: Array<string>, parentId?: string): Promise<Array<models.Element>>;
    deleteDocuments(userid: string, path: Array<string>): Promise<AxiosResponse>;
    deleteTrash(userid: string): Promise<AxiosResponse>;
    getFile(userid: string, fileName: string, path: string, contentType: string, isFolder?: boolean, inline?: boolean): string;
    getFiles(userid: string, path: string, files: Array<string>): string;
    // Vignette/aperçu d'un fichier (image, pdf, vidéo…) — Content-Disposition: inline, adapté à un <img src>.
    getPreviewUrl(userid: string, fileId: number, width?: number, height?: number): string;
    createFolder(userid: string, folderPath: String): Promise<AxiosResponse>;
    // Édition bureautique en ligne (OnlyOffice) : renvoie une URL d'édition à token,
    // fabriquée côté connecteur avec le token per-user (aucune connexion NextCloud demandée).
    getEditUrl(userid: string, path: string): Promise<string>;
    // Partage NextCloud natif avec un autre utilisateur ENT : le fichier reste chez son propriétaire,
    // le destinataire y accède via son propre compte NextCloud. Une fois partagé, les deux utilisateurs
    // peuvent co-éditer le même fichier en temps réel via OnlyOffice (getEditUrl côté connecteur).
    shareWithUser(userid: string, path: string, targetUserId: string, targetDisplayName: string, permissions?: number): Promise<AxiosResponse>;
}

export const nextcloudService: INextcloudService = {
    openNextcloudLink: (document: SyncDocument, nextcloudUrl: string): void => {
        const url: string = document.path.includes("/") ? document.path.substring(0, document.path.lastIndexOf('/')) : "";
        const dir: string = url ? url : "/";
        window.open(`${nextcloudUrl}/index.php/apps/files?dir=${dir}&openfile=${document.fileId}`);
    },

    getNextcloudUrl: async (): Promise<string> => {
        return http.get(`/nextcloud/config/url`).then((res: AxiosResponse) => res.data.url);
    },

    getIsNextcloudUrlHidden: async (): Promise<boolean> => {
        return http.get(`/nextcloud/config/isNextcloudUrlHidden`).then((res: AxiosResponse) => res.data.isNextcloudUrlHidden);
    },

    getEditUrl: async (userid: string, path: string): Promise<string> => {
        // document.path provient du backend déjà percent-encodé (segments d'URL WebDAV bruts,
        // ex. "/Nextcloud%20Manual.pdf") : un simple encodeURIComponent() double l'encodage
        // (%20 -> %2520) et fait échouer la résolution du fichier côté NextCloud (500). On
        // normalise via decodeURIComponent() avant de ré-encoder pour garantir un seul niveau
        // d'encodage, que path soit déjà encodé ou non.
        const normalizedPath = decodeURIComponent(path);
        return http.get(`/nextcloud/files/user/${userid}/edit?path=${encodeURIComponent(normalizedPath)}`).then((res: AxiosResponse) => res.data.url);
    },

    shareWithUser: async (userid: string, path: string, targetUserId: string, targetDisplayName: string, permissions: number = 3): Promise<AxiosResponse> => {
        const normalizedPath = decodeURIComponent(path);
        return http.post(`/nextcloud/files/user/${userid}/share`, {
            path: normalizedPath,
            targetUserId,
            targetDisplayName,
            permissions,
        });
    },

    createFolder: async(userid: string, folderPath: String): Promise<AxiosResponse> => {
        const urlParam: string = folderPath ? `?path=${folderPath}` : '';
        return http.post(`/nextcloud/files/user/${userid}/create/folder${urlParam}`);
    },

    listDocument: async (userid: string, path?: string): Promise<Array<SyncDocument>> => {
        const urlParam: string = path ? `?path=${path}` : '';
        return http.get(`/nextcloud/files/user/${userid}${urlParam}`)
            .then((res: AxiosResponse) => res.data.data.map((document: IDocumentResponse) => new SyncDocument().build(document)));
    },

    uploadDocuments(userid: string, files: Array<File>, path?: string): Promise<AxiosResponse> {
        const urlParam: string = path ? `?path=${path}` : '';
        const formData: FormData = new FormData();
        const headers = {'headers': {'Content-type': 'multipart/form-data', 'File-Count': files.length}};
        files.forEach(file => {
            formData.append('fileToUpload[]', file);
        });
        return http.put(`/nextcloud/files/user/${userid}/upload${urlParam}`, formData, headers);
    },

    moveDocument: (userid: string, path: string, destPath: string): Promise<AxiosResponse> => {
        const urlParam: string = `?path=${path}&destPath=${destPath}`;
        return http.put(`/nextcloud/files/user/${userid}/move${urlParam}`);
    },

    moveDocumentNextcloudToWorkspace: (userid: string, paths: Array<string>, parentId?: string): Promise<AxiosResponse> => {
        let urlParams: URLSearchParams = new URLSearchParams();
        paths.forEach((path: string) => urlParams.append('path', path));
        const parentIdParam: string = parentId ? `&parentId=${parentId}` : '';
        return http.put(`/nextcloud/files/user/${userid}/move/workspace?${urlParams}${parentIdParam}`)
            .then((res: AxiosResponse) => res.data.data
                .filter(document => document._id)
                .map((document) => new models.Element(document)));
    },

    moveDocumentWorkspaceToCloud: (userid: string, ids: Array<string>, cloudDocumentName?: string): Promise<AxiosResponse> => {
        let urlParams: URLSearchParams = new URLSearchParams();
        ids.forEach((path: string) => urlParams.append('id', path));
        const parentDocumentNameParam: string = cloudDocumentName ? `&parentName=${cloudDocumentName}` : '';
        return http.put(`/nextcloud/files/user/${userid}/workspace/move/cloud?${urlParams}${parentDocumentNameParam}`);
    },

    // Copie (et non déplacement) de documents de l'espace doc ENT vers NextCloud :
    // le document reste dans le workspace ET une copie part dans le dossier NextCloud
    // choisi. Utilise l'endpoint backend copyDocumentsFromWorkspaceToNC (copy/cloud).
    copyDocumentWorkspaceToCloud: (userid: string, ids: Array<string>, cloudDocumentName?: string): Promise<AxiosResponse> => {
        let urlParams: URLSearchParams = new URLSearchParams();
        ids.forEach((path: string) => urlParams.append('id', path));
        const parentDocumentNameParam: string = cloudDocumentName ? `&parentName=${cloudDocumentName}` : '';
        return http.put(`/nextcloud/files/user/${userid}/workspace/copy/cloud?${urlParams}${parentDocumentNameParam}`);
    },

    copyDocumentToWorkspace(userid: string, paths: Array<string>, parentId?: string): Promise<Array<models.Element>> {
        let urlParams: URLSearchParams = new URLSearchParams();
        paths.forEach((path: string) => urlParams.append('path', path));
        const parentIdParam: string = parentId ? `&parentId=${parentId}` : '';
        return http.put(`/nextcloud/files/user/${userid}/copy/workspace?${urlParams}${parentIdParam}`)
            .then((res: AxiosResponse) => res.data.data
                .filter(document => document._id)
                .map((document) => new models.Element(document)));
    },

    deleteDocuments(userid: string, paths: Array<string>): Promise<AxiosResponse> {
        let urlParams: URLSearchParams = new URLSearchParams();
        paths.forEach((path: string) => {
            urlParams.append('path', path);
        });
        return http.delete(`/nextcloud/files/user/${userid}/delete?${urlParams}`);
    },

    deleteTrash(userid: string): Promise<AxiosResponse> {
        return http.delete(`/nextcloud/files/user/${userid}/trash/delete`);
    },

    getFile: (userid: string, fileName: string, path: string, contentType: string, isFolder: boolean = false, inline: boolean = false): string => {
        const pathParam: string = path ? `?path=${path}` : '';
        const contentTypeParam: string = path && contentType ? `&contentType=${contentType}` : '';
        const isFolderParam: string = pathParam ? `&isFolder=${isFolder}` : `?isFolder=${isFolder}`;
        const inlineParam: string = inline ? `&inline=true` : '';
        const urlParam: string = `${pathParam}${contentTypeParam}${isFolderParam}${inlineParam}`;
        return `/nextcloud/files/user/${userid}/file/${encodeURI(fileName)}/download${urlParam}`;
    },

    // Vignette/aperçu (image, pdf, vidéo…) généré par NextCloud, servi en Content-Disposition: inline
    // (contrairement à getFile ci-dessus qui force "attachment" — impropre à un <img src>).
    getPreviewUrl: (userid: string, fileId: number, width: number = 150, height: number = 150): string => {
        return `/nextcloud/files/user/${userid}/file/${fileId}/preview?width=${width}&height=${height}`;
    },

    getFiles: (userid: string, path: string, files: Array<string>): string => {
        const pathParam: string = `?path=${path}`;
        let filesParam: string = '';
        files.forEach((file: string) => {
            filesParam += `&file=${file}`;
        });
        const urlParam: string = `${pathParam}${filesParam}`;
        return `/nextcloud/files/user/${userid}/multiple/download${urlParam}`;
    },
};

export const NextcloudService = ng.service('NextcloudService', (): INextcloudService => nextcloudService);