import {SyncDocument} from "../models";
import {model, idiom as lang, notify} from "entcore";
import {AxiosResponse} from "axios";
import {DocumentRole} from "../core/enums/document-role";

export class NextcloudDocumentsUtils {
    /**
     * Le déplacement/copie vers Nextcloud répond toujours 200, même si certains fichiers sont
     * refusés individuellement (data[].status === "ko"). Sans cette vérification, une extension
     * bloquée par l'établissement passait inaperçue côté utilisateur (l'action semblait réussie).
     */
    static notifyForbiddenExtensions(res: AxiosResponse): void {
        const data: Array<any> = res && res.data && res.data.data;
        if (!data || !Array.isArray(data)) {
            return;
        }
        const hasForbiddenExtension: boolean = data.some((item: any) => item.status === "ko" && item.error === "extension.forbidden");
        if (hasForbiddenExtension) {
            notify.error(lang.translate('nextcloud.fail.upload.extension.forbidden'));
        }
    }
    static determineRole(contentType: string): DocumentRole {
        for (let role in DocumentRole) {
            if (contentType.includes(DocumentRole[role])) {
                return <DocumentRole>DocumentRole[role];
            }
        }
        return DocumentRole.UNKNOWN;
    }

    static filterRemoveNameFile(): (syncDocument: SyncDocument) => boolean {
        return (syncDocument: SyncDocument) => syncDocument.name !== model.me.userId;
    }

    static filterDocumentOnly(): (syncDocument: SyncDocument) => boolean {
        return (syncDocument: SyncDocument) => syncDocument.isFolder && syncDocument.name != model.me.userId;
    }

    static filterFilesOnly(): (syncDocument: SyncDocument) => boolean {
        return (syncDocument: SyncDocument) => !syncDocument.isFolder && syncDocument.name != model.me.userId;
    }

    static filterRemoveOwnDocument(document: SyncDocument): (syncDocument: SyncDocument) => boolean {
        return (syncDocument: SyncDocument) => syncDocument.path !== document.path;
    }

    static getExtension(filename: string): string {
        let words: Array<string> = filename.split(".");
        return words[words.length - 1];
    }
}