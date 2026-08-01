import {SyncDocument} from "../../models";
import {model, toasts} from "entcore";
import {AxiosError} from "axios";
import {safeApply} from "../../utils/safe-apply.utils";
import {ToolbarShareSnipletViewModel} from "./workspace-nextcloud-toolbar-share.sniplet";
import {nextcloudService} from "../../services";

declare let window: any;

interface ILightboxViewModel {
    properties: boolean;
    delete: boolean;
    share: boolean;
}

interface IViewModel {
    lightbox: ILightboxViewModel;
    currentDocument: SyncDocument;

    hasOneDocumentSelected(selectedDocuments: Array<SyncDocument>): boolean;

    // download action
    downloadFiles(selectedDocuments: Array<SyncDocument>): void

    // properties action
    toggleRenameView(state: boolean, selectedDocuments: Array<SyncDocument>): void;
    toggleEdit(): void;
    isSelectedEditable(selectedDocuments: Array<SyncDocument>): boolean;
    renameDocument();

    // delete documents action
    toggleDeleteView(state: boolean): void;
    deleteDocuments();

    // share documents action (using class sniplet)
    share: any;
}

export class ToolbarSnipletViewModel implements IViewModel {
    private vm: any;
    private scope: any;

    lightbox: ILightboxViewModel;
    currentDocument: SyncDocument;

    // share documents action
    share: any;

    constructor(scope) {
        this.scope = scope;
        this.vm = scope.vm;
        this.lightbox = {
            properties: false,
            delete: false,
            share: false
        };
        this.currentDocument = null;
        this.share = new ToolbarShareSnipletViewModel(this);
    }
    isSelectedEditable(selectedDocuments: Array<SyncDocument>): boolean {
        return selectedDocuments.length > 0 && selectedDocuments[0].editable;
    }

    hasOneDocumentSelected(selectedDocuments: Array<SyncDocument>): boolean {
        const total: number = selectedDocuments ? selectedDocuments.length : 0;
        return total == 1;
    }

    toggleEdit(): void {
        if (this.vm.selectedDocuments.length > 0) {
            const document: SyncDocument = this.vm.selectedDocuments[0];
            // Édition bureautique en ligne : le connecteur fabrique une URL d'édition à token
            // (OnlyOffice via l'API Direct Editing du cœur) avec le token per-user — pas de connexion NextCloud.
            nextcloudService.getEditUrl(model.me.userId, document.path)
                .then((url: string) => window.open(url))
                .catch((err: AxiosError) => {
                    toasts.warning('nextcloud.edit.error');
                    console.error('[Nextcloud@ToolbarSnipletViewModel::toggleEdit] Failed to open online editor: ', err);
                });
        }
    }

    downloadFiles(selectedDocuments: Array<SyncDocument>): void {
        if (selectedDocuments.length === 1) {
            window.open(this.vm.nextcloudService.getFile(model.me.userId, selectedDocuments[0].name,
                selectedDocuments[0].path, selectedDocuments[0].contentType, selectedDocuments[0].isFolder))
        } else {
            const selectedDocumentsName: Array<string> = selectedDocuments.map((selectedDocuments: SyncDocument) => selectedDocuments.name);
            // if path parent is null (meaning is the parent folder sync), we return "/"
            const getPathParent: string = this.vm.parentDocument.path ? this.vm.parentDocument.path : "/";
            window.open(this.vm.nextcloudService.getFiles(model.me.userId, getPathParent, selectedDocumentsName));
        }
    }

    toggleRenameView(state: boolean, selectedDocuments?: Array<SyncDocument>): void {
        this.lightbox.properties = state;
        if (state && selectedDocuments) {
            this.currentDocument = Object.assign({}, selectedDocuments[0]);
        } else {
            this.currentDocument = null;
        }
    }

    renameDocument(): void {
        const oldDocumentToRename: SyncDocument = this.vm.selectedDocuments[0];
        if (oldDocumentToRename) {
            const targetDocument: string = (this.vm.parentDocument.path == null ? "" : this.vm.parentDocument.path) + "/" + encodeURIComponent(this.currentDocument.name);
            this.vm.nextcloudService.moveDocument(model.me.userId, oldDocumentToRename.path, targetDocument)
                .then(() => {
                    return this.vm.nextcloudService.listDocument(model.me.userId, this.vm.parentDocument.path ?
                        this.vm.parentDocument.path : null);
                })
                .then((syncDocuments: Array<SyncDocument>) => {
                    this.vm.documents = syncDocuments
                        .filter((syncDocument: SyncDocument) => syncDocument.path != this.vm.parentDocument.path)
                        .filter((syncDocument: SyncDocument) => syncDocument.name != model.me.userId);
                    this.toggleRenameView(false);
                    this.vm.updateTree();
                    this.vm.selectedDocuments = [];
                    safeApply(this.scope);
                })
                .catch((err: AxiosError) => {
                    const message: string = "Error while attempting to rename document from content";
                    console.error(`${message}${err.message}: ${this.getErrorMessage(err)}`);
                    this.toggleRenameView(false);
                    this.vm.selectedDocuments = [];
                    safeApply(this.scope);
                });
        }
    }

    getErrorMessage(err: AxiosError): string {
        if (err && err.response && err.response.data.message) {
            return err.response.data.message;
        } else {
            return "";
        }
    }

    toggleDeleteView(state: boolean): void {
        this.lightbox.delete = state;
    }

    deleteDocuments(): void {
        const paths: Array<string> = this.vm.selectedDocuments.map((selectedDocument: SyncDocument) => selectedDocument.path);
        const nextcloudTreeController = this.vm.getNextcloudTreeController();
        this.vm.nextcloudService.deleteDocuments(model.me.userId, paths)
            .then(() => this.vm.nextcloudService.deleteTrash(model.me.userId))
            .then(() => nextcloudTreeController.nextcloudUserService.getUserInfo(model.me.userId))
            .then(userInfos => {
                nextcloudTreeController.userInfo = userInfos;
                toasts.info("nextcloud.documents.deletion.confirmation");
                return this.vm.nextcloudService.listDocument(model.me.userId, this.vm.parentDocument.path ?
                    this.vm.parentDocument.path : null);
            })
            .then((syncDocuments: Array<SyncDocument>) => {
                this.vm.documents = syncDocuments
                    .filter((syncDocument: SyncDocument) => syncDocument.path != this.vm.parentDocument.path)
                    .filter((syncDocument: SyncDocument) => syncDocument.name != model.me.userId);
                this.toggleDeleteView(false);
                this.vm.selectedDocuments = [];
                this.vm.updateTree();
                safeApply(this.scope);
            })
            .catch((err: AxiosError) => {
                const message: string = "Error while attempting to delete documents from content";
                console.error(`${message}${err.message}: ${this.getErrorMessage(err)}`);
                this.toggleDeleteView(false);
                this.vm.selectedDocuments = [];
                safeApply(this.scope);
            });
    }
}