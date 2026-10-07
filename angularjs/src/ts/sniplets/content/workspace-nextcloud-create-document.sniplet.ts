import { idiom as lang, model, notify } from "entcore";
import { AxiosError, AxiosResponse } from "axios";
import { safeApply } from "../../utils/safe-apply.utils";
import { SyncDocument } from "../../models";

declare let window: any;

const DOCUMENT_TYPES: Array<string> = ["docx", "xlsx", "pptx"];

interface ICreateDocument {
    type: string;
    name: string;
}

interface ILightboxViewModel {
    createDocument: boolean;
}

interface IViewModel {
    lightbox: ILightboxViewModel;
    documentTypeList: Array<string>;
    document: ICreateDocument;
    saving: boolean;

    toggleCreateDocumentView(state: boolean): void;
    createDocument(): void;
}

export class CreateDocumentSnipletViewModel implements IViewModel {
    private vm: any;
    private scope: any;

    lightbox: ILightboxViewModel;
    documentTypeList: Array<string> = DOCUMENT_TYPES;
    document: ICreateDocument;
    saving: boolean = false;

    constructor(scope) {
        this.scope = scope;
        this.vm = scope.vm;
        this.lightbox = {
            createDocument: false
        };
        this.initDocument();
    }

    private initDocument(): void {
        this.document = {
            type: DOCUMENT_TYPES[0],
            name: ""
        };
    }

    toggleCreateDocumentView(state: boolean): void {
        this.lightbox.createDocument = state;
        if (!state) {
            this.initDocument();
        }
    }

    createDocument(): void {
        if (!this.document.name || !this.document.name.trim()) {
            return;
        }
        this.saving = true;
        const selectedFolderFromNextcloudTree: SyncDocument = this.vm.getNextcloudTreeController()['selectedFolder'];
        const path: string = selectedFolderFromNextcloudTree && selectedFolderFromNextcloudTree.path ?
            selectedFolderFromNextcloudTree.path : null;

        this.vm.nextcloudService.createDocument(model.me.userId, this.document.type, this.document.name.trim(), path)
            .then((res: AxiosResponse) => {
                this.toggleCreateDocumentView(false);
                return this.vm.nextcloudService.listDocument(model.me.userId, path)
                    .then((syncDocuments: Array<SyncDocument>) => {
                        this.vm.documents = syncDocuments
                            .filter((syncDocument: SyncDocument) => syncDocument.path != path)
                            .filter((syncDocument: SyncDocument) => syncDocument.name != model.me.userId);
                        safeApply(this.scope);
                        return res;
                    });
            })
            .then((res: AxiosResponse) => this.vm.nextcloudService.getEditUrl(model.me.userId, res.data.path))
            .then((url: string) => {
                window.open(url);
                this.saving = false;
                safeApply(this.scope);
            })
            .catch((err: AxiosError) => {
                console.error("[Nextcloud@createDocument] Failed to create document: " + err.message);
                notify.error(lang.translate("nextcloud.create.document.error"));
                this.saving = false;
                safeApply(this.scope);
            });
    }
}
