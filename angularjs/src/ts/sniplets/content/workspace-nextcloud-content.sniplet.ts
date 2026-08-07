import { AxiosError, AxiosResponse } from "axios";
import { angular, Behaviours, idiom as lang, Me, model, template, toasts, workspace } from "entcore";
import { Subscription } from "rxjs";
import { RootsConst } from "../../core/constants/roots.const";
import { ViewMode } from "../../core/enums/view-mode";
import { Draggable, SyncDocument } from "../../models";
import { NEXTCLOUD_APP } from "../../nextcloud.behaviours";
import { INextcloudService, nextcloudService } from "../../services";
import { NextcloudPreference, Preference } from "../../shared/services";
import { safeApply } from "../../utils/safe-apply.utils";
import { ToolbarSnipletViewModel } from "./workspace-nextcloud-toolbar.sniplet";
import { UploadFileSnipletViewModel } from "./workspace-nextcloud-upload-file.sniplet";
import { NextcloudViewIcons } from "./workspace-nextcloud-view-icons.sniplet";
import { NextcloudViewList } from "./workspace-nextcloud-view-list.sniplet";
import models = workspace.v2.models;


declare let window: any;

const nextcloudTree: string = 'nextcloud-folder-tree';

interface IViewModel {
    // util
    safeApply(): void;

    initDraggable(): void;

    onSelectContent(document: SyncDocument): void;

    onOpenContent(document: SyncDocument): void;

    getFile(document: SyncDocument): string;

    getPreviewUrl(document: SyncDocument): string;

    nextcloudUrl: string;
    isNextcloudUrlHidden: boolean;
    draggable: Draggable;
    lockDropzone: boolean;
    parentDocument: SyncDocument;
    documents: Array<SyncDocument>;
    selectedDocuments: Array<SyncDocument>;
    checkboxSelectAll: boolean;

    // drag & drop action
    moveDocument(element: any, document: SyncDocument): Promise<void>;

    // dropzone
    isDropzoneEnabled(): boolean;

    canDropOnFolder(): boolean;

    onCannotDropFile(): void;
}

class ViewModel implements IViewModel {
    private scope: any;
    private nextcloudService: INextcloudService;

    subscriptions: Subscription = new Subscription();

    nextcloudUrl: string;
    isNextcloudUrlHidden: boolean;

    draggable: Draggable;
    lockDropzone: boolean;

    orderField: string;
    orderDesc: boolean;

    parentDocument: SyncDocument;
    documents: Array<SyncDocument>;
    selectedDocuments: Array<SyncDocument>;
    isLoaded: boolean;
    checkboxSelectAll: boolean;
    nextcloudPreference: Preference;

    constructor(scope, nextcloudService: INextcloudService) {
        this.isLoaded = false;
        this.scope = scope;
        this.nextcloudService = nextcloudService;
        this.documents = [];
        this.parentDocument = null;
        this.nextcloudUrl = null;
        this.selectedDocuments = new Array<SyncDocument>();
        this.nextcloudPreference = new Preference();
        // fetch nextcloud url hidden state in order to hide or show the nextcloud url
        this.isNextcloudUrlHidden = false;
        nextcloudService.getIsNextcloudUrlHidden().then(isHidden => {
            this.isNextcloudUrlHidden = isHidden;
        }).catch((err: AxiosError) => {
            const message: string = "Error while attempting to fetch nextcloud url hidden state";
            console.error(message + err.message);
            this.isNextcloudUrlHidden = false;
        });
        // on init we first sync its main folder content
        Promise.all([this.initDocumentsContent(nextcloudService, scope),
            nextcloudService.getNextcloudUrl(), this.nextcloudPreference.init()])
            .then(([_, url]) => {
                this.changeViewMode(this.nextcloudPreference.viewMode);
                this.nextcloudUrl = url;
                this.isLoaded = true;
                safeApply(scope);
            })
            .catch((err: AxiosError) => {
                const message: string = "Error while attempting to init or fetch nextcloud url: ";
                console.error(message + err.message);
                this.isLoaded = true;
                safeApply(scope);
            });

        // on receive documents from folder-tree sniplet
        this.subscriptions.add(Behaviours.applicationsBehaviours[NEXTCLOUD_APP].nextcloudService
            .getDocumentsState()
            .subscribe((res: {parentDocument: SyncDocument, documents: Array<SyncDocument>}) => {
                if (res.documents && res.documents.length > 0) {
                    this.parentDocument = res.parentDocument;
                    this.documents = res.documents.filter((syncDocument: SyncDocument) => syncDocument.name != model.me.userId)
                        .sort(this.sortDocumentsByFolder);
                    this.orderDesc = false;
                    this.orderField = null;
                } else {
                    this.parentDocument = res.parentDocument;
                    this.documents = [];
                }
                this.isLoaded = true;
                safeApply(scope);
            }));

        this.initDraggable();

        scope.$parent.$on("$destroy", () => {
            this.parentDocument = null;
            this.nextcloudUrl = null;
            this.subscriptions.unsubscribe();
        });
    }

    private async initDocumentsContent(nextcloudService: INextcloudService, scope): Promise<void> {
        let selectedFolderFromNextcloudTree: SyncDocument = this.getNextcloudTreeController()['selectedFolder'];
        await nextcloudService.listDocument(model.me.userId, selectedFolderFromNextcloudTree.path)
            .then((documents: Array<SyncDocument>) => {
                // will be called first time while constructor initializing
                // since it will syncing at the same time observable will receive its events, we check its length at the end
                if (!this.documents.length) {
                    this.documents = documents
                        .filter((syncDocument: SyncDocument) => syncDocument.path != selectedFolderFromNextcloudTree.path)
                        .filter((syncDocument: SyncDocument) => syncDocument.name != model.me.userId)
                        .sort(this.sortDocumentsByFolder);
                    this.parentDocument = new SyncDocument().initParent();
                }
                safeApply(scope);
            })
            .catch((err: AxiosError) => {
                const message: string = "Error while attempting to fetch documents children from content";
                console.error(message + err.message);
                return [];
            });
    }

    getNextcloudTreeController(): ng.IScope {
        return angular.element(document.getElementById(nextcloudTree)).scope()['vm'];
    }

    safeApply(): void {
        safeApply(this.scope);
    }

    updateTree(): void {
        const selectedFolderFromNextcloudTree: SyncDocument = this.getNextcloudTreeController()['selectedFolder'];
        this.updateFolderDocument(selectedFolderFromNextcloudTree);
        this.safeApply();
    }

    initDraggable(): void {
        // use this const to make it accessible to its folderTree inner context
        const viewModel: IViewModel = this;
        this.draggable = {
           dragConditionHandler(event: DragEvent, content?: any): boolean {
               return false;
           },
           dragDropHandler(event: DragEvent, content?: any): void {
           },
           async dragEndHandler(event: DragEvent, content?: any): Promise<void> {
               await viewModel.moveDocument(document.elementFromPoint(event.x, event.y), content);
               viewModel.lockDropzone = false;
               viewModel.safeApply();
           },
           dragStartHandler(event: DragEvent, content?: any): void {
               viewModel.lockDropzone = true;
               try {
                   event.dataTransfer.setData('application/json', JSON.stringify(content));
               } catch (e) {
                   event.dataTransfer.setData('Text', JSON.stringify(content));
               }
               Behaviours.applicationsBehaviours[NEXTCLOUD_APP].nextcloudService.setContentContext(content);
           },
           dropConditionHandler(event: DragEvent, content?: any): boolean {
               return true;
           }
        }
    }

    /**
     * compare function to sort documents by folder
     */
    private sortDocumentsByFolder (syncDocumentA: SyncDocument, syncDocumentB: SyncDocument): number {
        if (syncDocumentA.type === 'folder' && syncDocumentB.type === 'file') return -1;
        if (syncDocumentA.type === 'file' && syncDocumentB.type === 'folder') return 1;
        return 0;
    }

    async moveDocument(element: any, document: SyncDocument): Promise<void> {
        let selectedFolderFromNextcloudTree: SyncDocument = this.getNextcloudTreeController()['selectedFolder'];
        if (!selectedFolderFromNextcloudTree) {
            selectedFolderFromNextcloudTree = this.parentDocument;
        }
        let folderContent: any = angular.element(element).scope();
        // if interacted into trees(workspace or nextcloud)
        if (folderContent && folderContent.folder) {
            this.processMoveTree(folderContent, document, selectedFolderFromNextcloudTree);
        }
        // if interacted into nextcloud (folderContent as being te targeted path to move in)
        if (folderContent && folderContent.content instanceof SyncDocument && folderContent.content.isFolder) {
            // if interacted into nextcloud
            this.processMoveToNextcloud(document, folderContent.content, selectedFolderFromNextcloudTree);
        }
    }

    private processMoveTree(folderContent: any, document: SyncDocument, selectedFolderFromNextcloudTree: SyncDocument): void {
        if (folderContent.folder instanceof models.Element) {
            const nextcloudController: any = this.getNextcloudTreeController();
            const filesToMove: Set<SyncDocument> = new Set(this.selectedDocuments).add(document);
            const filesPath: Array<string> = Array.from(filesToMove).map((file: SyncDocument) => file.path);
            if (filesPath.length) {
                this.nextcloudService.moveDocumentNextcloudToWorkspace(model.me.userId, filesPath, folderContent.folder._id)
                    .then(() => nextcloudController.nextcloudUserService.getUserInfo(model.me.userId))
                    .then(userInfos => {
                        nextcloudController.userInfo = userInfos;
                        return nextcloudService.listDocument(model.me.userId, selectedFolderFromNextcloudTree.path ?
                            selectedFolderFromNextcloudTree.path : null);
                    })
                    .then((syncedDocument: Array<SyncDocument>) => {
                        this.documents = syncedDocument
                            .filter((syncDocument: SyncDocument) => syncDocument.path != selectedFolderFromNextcloudTree.path)
                            .filter((syncDocument: SyncDocument) => syncDocument.name != model.me.userId);
                        this.updateFolderDocument(selectedFolderFromNextcloudTree);
                        this.safeApply();
                    })
                    .catch((err: AxiosError) => {
                        const message: string = "Error while attempting to move nextcloud document to workspace " +
                            "or update nextcloud list";
                        console.error(message + err.message);
                    });
            }
        } else {
            this.processMoveToNextcloud(document, folderContent.folder, selectedFolderFromNextcloudTree)
        }
    }

    private async moveAllDocuments(document: SyncDocument, target: SyncDocument): Promise<AxiosResponse[]> {
        const promises: Array<Promise<AxiosResponse>> = [];
        this.selectedDocuments.push(document);
        const selectedSet: Set<SyncDocument> = new Set(this.selectedDocuments);
        selectedSet.forEach((doc: SyncDocument) => {
            if (doc.path != target.path) {
                promises.push(this.nextcloudService.moveDocument(model.me.userId, doc.path, (target.path != null ? target.path : "") + encodeURI(doc.name)));
            }
        });
        return await Promise.all<AxiosResponse>(promises);
    }

    private updateDocList(selectedFolderFromNextcloudTree: SyncDocument): void {
        this.selectedDocuments = [];
        nextcloudService.listDocument(model.me.userId, selectedFolderFromNextcloudTree.path ?
            selectedFolderFromNextcloudTree.path : null)
            .then((syncedDocument: Array<SyncDocument>) => {
                this.documents = syncedDocument
                    .filter((syncDocument: SyncDocument) => syncDocument.path != selectedFolderFromNextcloudTree.path)
                    .filter((syncDocument: SyncDocument) => syncDocument.name != model.me.userId);
                this.updateFolderDocument(selectedFolderFromNextcloudTree);
                this.safeApply();
            })
            .catch((err: AxiosError) => {
                const message: string = "Error while updating documents list";
                console.error(message + err.message);
            })
    }

    private processMoveToNextcloud(document: SyncDocument, target: SyncDocument, selectedFolderFromNextcloudTree: SyncDocument): void {
            this.moveAllDocuments(document, target)
            .then(() => this.updateDocList(selectedFolderFromNextcloudTree))
            .catch((err: AxiosError) => {
                this.updateDocList(selectedFolderFromNextcloudTree);
                const message: string = "Error while attempting to move nextcloud document to workspace " +
                    "or update nextcloud list";
                console.error(message + err.message);
            })
    }

    private updateFolderDocument = (selectedFolderFromNextcloudTree: SyncDocument): void => {
        Behaviours.applicationsBehaviours[NEXTCLOUD_APP].nextcloudService.setContentContext(null);
        Behaviours.applicationsBehaviours[NEXTCLOUD_APP].nextcloudService.sendOpenFolderDocument(selectedFolderFromNextcloudTree);
    };

    onSelectContent(content: SyncDocument): void {
        this.selectedDocuments = this.documents.filter((document: SyncDocument) => document.selected);
    }

    onSelectAll(): void {
        this.documents.forEach(document => document.selected = this.checkboxSelectAll);
        this.selectedDocuments = this.documents.filter((document: SyncDocument) => document.selected);
    }

    isViewMode(mode: ViewMode): boolean {
        const pathTemplate = `../../../${RootsConst.template}/behaviours/sniplet-nextcloud-content/content/views/${mode}`;
        return template.contains('documents-content', pathTemplate);
    }


    async changeViewMode(mode: ViewMode): Promise<void> {
        let preference: NextcloudPreference = Me.preferences['nextcloud'];
        preference.viewMode = mode;
        await this.nextcloudPreference.updatePreference(preference);
        const pathTemplate = `../../../${RootsConst.template}/behaviours/sniplet-nextcloud-content/content/views/${mode}`;
        this.documents.forEach(document => document.selected = false);
        this.selectedDocuments = [];
        template.open('documents-content', pathTemplate);
    }

    onOpenContent(document: SyncDocument): void {
        if (document.isFolder) {
            Behaviours.applicationsBehaviours[NEXTCLOUD_APP].nextcloudService.sendOpenFolderDocument(document);
            // reset all selected documents switch we switch folder
            this.selectedDocuments = [];
        } else {
            if (document.editable) {
                // Fichier bureautique (doc/xls/ppt) : ouvrir l'éditeur en ligne (OnlyOffice via l'API Direct
                // Editing du cœur) via une URL à token fabriquée par le connecteur avec le token per-user —
                // sans login. Le PDF n'est PAS éditable via cette API (Nextcloud renvoie 403), cf isEditable().
                nextcloudService.getEditUrl(model.me.userId, document.path)
                    .then((url: string) => window.open(url))
                    .catch((err: AxiosError) => {
                        toasts.warning('nextcloud.edit.error');
                        console.error('[Nextcloud@onOpenContent] Failed to open online editor: ', err);
                    });
            } else {
                // inline=true : le navigateur affiche le fichier (PDF, image...) au lieu de le télécharger.
                window.open(this.getFile(document));
            }

        }
    }

    getFile(document: SyncDocument): string {
        return this.nextcloudService.getFile(model.me.userId, document.name, document.path, document.contentType, false, true);
    }

    getPreviewUrl(document: SyncDocument): string {
        return this.nextcloudService.getPreviewUrl(model.me.userId, document.fileId);
    }

    isDropzoneEnabled(): boolean {
        return !this.lockDropzone;
    }

    canDropOnFolder(): boolean {
        return true;
    }

    onCannotDropFile(): void {

    }
}

export const workspaceNextcloudContent = {
    title: 'nextcloud.content',
    public: false,
    that: null,
    controller: {
        init: async function (): Promise<void> {
            lang.addBundle('/nextcloud/i18n', () => {
                // exposé sur le scope (comme les contrôleurs core auth) pour permettre
                // [[lang.translate('...')]] dans les templates imbriqués, ex. un placeholder traduit
                this.lang = lang;
                this.vm = new ViewModel(this, nextcloudService);
                this.vm.toolbar = new ToolbarSnipletViewModel(this);
                this.vm.upload = new UploadFileSnipletViewModel(this);
                this.vm.viewList = new NextcloudViewList(this);
                this.vm.viewIcons = new NextcloudViewIcons(this);
            });
        },
    }
};