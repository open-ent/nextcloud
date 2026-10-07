import {model, notify, idiom as lang, SharePayload, template, workspace} from "entcore";
import {RootsConst} from "../../core/constants/roots.const";
import {SyncDocument} from "../../models";
import {WorkspaceEntcoreUtils} from "../../utils/workspace-entcore.utils";
import {NextcloudDocumentsUtils} from "../../utils/nextcloud-documents.utils";
import {nextcloudService} from "../../services";
import {setTimeout} from "core-js";
import {AxiosResponse} from "axios";
import models = workspace.v2.models;

interface IViewModel {
    copyingForShare: boolean;

    sharedElement: Array<any>;
    toggleShareView(state: boolean, selectedDocuments?: Array<SyncDocument>): void;
    onShareAndNotCopy(): void;
    onShareAndCopy(): void;

    onSubmitSharedElements(share: SharePayload): Promise<void>;
    onCancelShareElements(): Promise<void>;
}

export class ToolbarShareSnipletViewModel implements IViewModel {
    private vm: any;
    private scopeParent: any;

    copyingForShare: boolean;
    sharedElement: Array<any>;

    constructor(scopeParent: any) {
        this.scopeParent = scopeParent;
        this.vm = scopeParent.vm;
        this.sharedElement = [];
    }

    toggleShareView(state: boolean, selectedDocuments?: Array<SyncDocument>): void {
        this.scopeParent.lightbox.share = state;
        if (state && selectedDocuments) {
            this.copyingForShare = false;
            const pathTemplate: string = `../../../${RootsConst.template}/behaviours/sniplet-nextcloud-content/toolbar/share/share-documents-options`;
            template.open('workspace-nextcloud-toolbar-share', pathTemplate);
        } else {
            template.close('workspace-nextcloud-toolbar-share');
            this.copyingForShare = true;
        }
    }

    onShareAndNotCopy(): void {
        const paths: Array<string> = this.vm.selectedDocuments.map((document: SyncDocument) => document.path);
        this.vm.nextcloudService.moveDocumentNextcloudToWorkspace(model.me.userId, paths)
            .then(async (workspaceDocuments: Array<models.Element>) => {
                this.sharedElement = workspaceDocuments;
                this.vm.updateTree();
                const pathTemplate: string = `../../../${RootsConst.template}/behaviours/sniplet-nextcloud-content/toolbar/share/share`;
                this.vm.selectedDocuments = [];
                template.open('workspace-nextcloud-toolbar-share', pathTemplate);
                try {
                    this.vm.getNextcloudTreeController().userInfo = await this.vm.getNextcloudTreeController().nextcloudUserService.getUserInfo(model.me.userId);
                } catch (e) {
                    notify.error(lang.translate('error.user.info'));
                    console.error(e);
                }
                this.vm.safeApply();
            })
    }

    onShareAndCopy(): void {
        const paths: Array<string> = this.vm.selectedDocuments.map((document: SyncDocument) => document.path);
        this.vm.nextcloudService.copyDocumentToWorkspace(model.me.userId, paths)
            .then((workspaceDocuments: Array<models.Element>) => {
                this.sharedElement = workspaceDocuments;
                const pathTemplate: string = `../../../${RootsConst.template}/behaviours/sniplet-nextcloud-content/toolbar/share/share`;
                template.open('workspace-nextcloud-toolbar-share', pathTemplate);
            });
    }

    async onSubmitSharedElements(share: SharePayload): Promise<void> {
        this.toggleShareView(false);
        setTimeout(() => {
            WorkspaceEntcoreUtils.toggleWorkspaceContentDisplay(false);
            this.vm.safeApply();
        }, 500);
        this.sharedElement = [];
    }

    async onCancelShareElements(): Promise<void> {
        if (this.sharedElement.length) {
            try {
                const res: AxiosResponse = await nextcloudService.moveDocumentWorkspaceToCloud(model.me.userId, this.sharedElement.map(doc => doc._id), this.vm.parentDocument.path);
                NextcloudDocumentsUtils.notifyForbiddenExtensions(res);
                this.vm.updateTree();
                this.vm.safeApply();
            } catch (e) {
                console.error("Error while canceling share: " + e);
            }
            this.sharedElement = [];
        }
        this.toggleShareView(false);
    }
}