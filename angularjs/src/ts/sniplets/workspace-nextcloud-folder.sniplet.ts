import {FolderTreeProps, angular, template, Behaviours, workspace, model, idiom as lang, notify, Document, init} from "entcore";
import {Tree} from "entcore/types/src/ts/workspace/model";
import {safeApply} from "../utils/safe-apply.utils";
import {RootsConst} from "../core/constants/roots.const";
import {NEXTCLOUD_APP} from "../nextcloud.behaviours";
import models = workspace.v2.models;
import {WorkspaceEntcoreUtils} from "../utils/workspace-entcore.utils";
import {INextcloudService, nextcloudService} from "../services";
import {AxiosError, AxiosResponse} from "axios";
import {Draggable, SyncDocument} from "../models";
import {INextcloudUserService, nextcloudUserService} from "../services";
import {UserNextcloud} from "../models/nextcloud-user.model";
import {Subscription} from "rxjs";
import rights from "../rights";
import {NextcloudDocumentsUtils} from "../utils/nextcloud-documents.utils";
import {DocumentsType} from "../core/enums/documents-type";
import {FolderCreationModel} from "./folder/nextcloud-folder.sniplet";

declare let window: any;

// JQuery that fetches all folder element by its icons
const $folderTreeArrows: string = '#nextcloud-folder-tree i';
const $nextcloudFolder: string = '#nextcloud-folder-tree';

interface IViewModel {
    documents: Array<SyncDocument>;
    initTree(folder: Array<SyncDocument>): void;
    watchFolderState(): void;
    openDocument(folder: any): Promise<void>;
    setSwitchDisplayHandler(): void;

    userInfo: UserNextcloud;
    folderTree: FolderTreeProps;
    selectedFolder: models.Element;
    openedFolder: Array<models.Element>;

    // drag & drop actions
    initDraggable(): void;
    resolveDragTarget(event: DragEvent): Promise<void>;
    removeSelectedDocuments(): void;
    addDragEventListeners(): void;
    removeDragEventListeners(): void;
    addDragOverlays(): void;
    removeDragOverlays(): void;
    addDragFeedback(): void;
    removeDragFeedback(): void;
    droppable: Draggable;
    dragOverEventListeners: Map<HTMLElement, EventListener>;
    dragLeaveEventListeners: Map<HTMLElement, EventListener>;
}

class ViewModel implements IViewModel {
    private nextcloudService: INextcloudService;
    private nextcloudUserService: INextcloudUserService;
    private scope: any;

    userInfo: UserNextcloud;
    folderTree: FolderTreeProps;
    selectedFolder: models.Element;
    openedFolder: Array<models.Element> = [];
    droppable: Draggable;
    documents: Array<SyncDocument>;
    dragOverEventListeners: Map<HTMLElement, EventListener> = new Map<HTMLElement, EventListener>();
    dragLeaveEventListeners: Map<HTMLElement, EventListener> = new Map<HTMLElement, EventListener>();

    subscriptions: Subscription = new Subscription();

    constructor(scope, nextcloudService: INextcloudService, nextcloudUserService: INextcloudUserService) {
        this.scope = scope;
        this.nextcloudService = nextcloudService;
        this.nextcloudUserService = nextcloudUserService;
        this.userInfo = null;
        this.documents = [];
        this.folderTree = {};
        this.selectedFolder = null;
        this.openedFolder = [];

        // resolve user nextcloud && init tree
        this.nextcloudUserService.getUserInfo(model.me.userId)
            .then((userInfo: UserNextcloud) => {
                this.userInfo = userInfo;
                this.documents = [new SyncDocument().initParent()];
                this.initTree(this.documents);
                this.initDraggable();
                // Déplié par défaut : même appel que switchWorkspaceTreeHandler au changement d'onglet.
                this.folderTree.openFolder(this.documents[0]);
                safeApply(this.scope);
            })
            .catch((err: AxiosError) => {
                const message: string = "Error while attempting to fetch user info";
                console.error(message + err.message);
            });

        // on receive openFolder event
        this.subscriptions.add(Behaviours.applicationsBehaviours[NEXTCLOUD_APP].nextcloudService
            .getOpenedFolderDocument()
            .subscribe((document: SyncDocument) => {
                let getFolderContext: SyncDocument = this.folderTree.trees.find(f => f.fileId === document.fileId);
                this.folderTree.openFolder(getFolderContext ? getFolderContext : document);
            }));

        scope.$parent.$on("$destroy", () => {
            this.removeDragFeedback();
        });
    }



    initTree(folder: Array<SyncDocument>): void {
        // use this const to make it accessible to its folderTree inner context
        const viewModel: IViewModel = this;

        // move nextcloud tree under workspace tree
        const nextcloudElement: HTMLElement = document.querySelector('[application="nextcloud"]').parentElement;
        if (nextcloudElement) {
            nextcloudElement.parentNode.appendChild(nextcloudElement);
        }

        this.folderTree = {
            cssTree: "folders-tree",
            get trees(): any | Array<Tree> {
                return folder;
            },
            isDisabled(folder: models.Element): boolean {
                return false;
            },
            isOpenedFolder(folder: models.Element): boolean {
                return viewModel.openedFolder.some((openFolder: models.Element) => openFolder === folder);
            },
            isSelectedFolder(folder: models.Element): boolean {
                return viewModel.selectedFolder === folder;
            },
            async openFolder(folder: models.Element): Promise<void> {
                viewModel.selectedFolder = folder;
                viewModel.setSwitchDisplayHandler();
                // create handler in case icon are only clicked
                viewModel.watchFolderState();
                if (!viewModel.openedFolder.some((openFolder: models.Element) => openFolder === folder)) {
                    viewModel.openedFolder = viewModel.openedFolder.filter((e: models.Element) => (<any> e).path != (<any> folder).path);
                    viewModel.openedFolder.push(folder);
                }
                // synchronize documents and send content to its other sniplet content
                await viewModel.openDocument(folder);

                // reset drag feedback by security
                viewModel.removeDragFeedback();
                // init drag over
                viewModel.addDragFeedback();
            },
        };
    }

    initDraggable(): void {
        const viewModel: IViewModel = this;
        this.droppable = {
            dragConditionHandler(event: DragEvent, content?: any): boolean {
                return false;
            },
            async dragDropHandler(event: DragEvent): Promise<void> {
                await viewModel.resolveDragTarget(event);
            },
            dragEndHandler(event: DragEvent, content?: any): void {},
            dragStartHandler(event: DragEvent, content?: any): void {},
            dropConditionHandler(event: DragEvent, content?: any): boolean {
                return false;
            }
        }
    }

    async resolveDragTarget(event: DragEvent): Promise<void> {
        // case drop concerns nextcloud
        if (Behaviours.applicationsBehaviours[NEXTCLOUD_APP].nextcloudService.getContentContext()) {
            // nextcloud context
        } else {
            // case drop concerns workspace but we need extra check
            const document: any = JSON.parse(event.dataTransfer.getData("application/json"));
            // check if it s a workspace document with its identifier and format file to proceed move to nextcloud
            if (document && (document._id && document.eType === DocumentsType.FILE || document.eType === DocumentsType.FOLDER)) {
                if (angular.element(event.target).scope().folder instanceof SyncDocument) {
                    const syncedDocument: SyncDocument = angular.element(event.target).scope().folder;
                    let selectedDocuments: Array<Document> = WorkspaceEntcoreUtils.workspaceScope()['documentList']['_documents'];
                    selectedDocuments = selectedDocuments.concat(WorkspaceEntcoreUtils.workspaceScope()['currentTree']['children']);
                    let documentToUpdate: Set<string> = new Set(selectedDocuments.filter((file: Document) => file.selected).map((file: Document) => file._id));
                    documentToUpdate.add(document._id);
                    nextcloudService.moveDocumentWorkspaceToCloud(model.me.userId, Array.from(documentToUpdate), syncedDocument.path)
                        .then((res: AxiosResponse) => {
                            // Le déplacement est un 200 global même si certains fichiers échouent
                            // individuellement (tableau data[].status==="ko") : sans cette
                            // vérification, une extension bloquée passait inaperçue côté UI.
                            NextcloudDocumentsUtils.notifyForbiddenExtensions(res);
                            WorkspaceEntcoreUtils.updateWorkspaceDocuments(
                                    WorkspaceEntcoreUtils.workspaceScope()['openedFolder']['folder']);
                            Behaviours.applicationsBehaviours[NEXTCLOUD_APP].nextcloudService.sendOpenFolderDocument(angular.element(event.target).scope().folder);
                            this.selectedFolder = null;
                            angular.element(event.target).scope().folder.classList.remove("selected");
                            }
                        )
                        .catch((err: AxiosError) => {
                            const message: string = "Error while attempting to fetch documents children ";
                            console.error(message + err.message);
                        });
                }
            }
        }
    }

    watchFolderState(): void {
        let $folders: JQuery = $($folderTreeArrows);
        $folders.off();
        // use this const to make it accessible to its callback $folder on click event inner context
        const viewModel: IViewModel = this;
        $folders.click(this.onClickFolder(viewModel));
    }

    async openDocument(document: any): Promise<void> {
        let syncDocuments: Array<SyncDocument> = await nextcloudService.listDocument(model.me.userId, document.path ? document.path : null)
            .catch((err: AxiosError) => {
                const message: string = "Error while attempting to fetch documents children ";
                console.error(message + err.message);
                return [];
        });
        // first filter applies only when we happen to fetch its own folder and the second applies on document only
        document.children = syncDocuments.filter(NextcloudDocumentsUtils.filterRemoveOwnDocument(document)).filter(NextcloudDocumentsUtils.filterDocumentOnly());
        safeApply(this.scope);
        Behaviours.applicationsBehaviours[NEXTCLOUD_APP].nextcloudService
            .sendDocuments({
                parentDocument: document.path ? document : new SyncDocument().initParent(),
                documents: syncDocuments.filter(NextcloudDocumentsUtils.filterRemoveOwnDocument(document))
            });
    }

    setSwitchDisplayHandler(): void {
        const viewModel: IViewModel = this;

        // case nextcloud folder tree is interacted
        // checking if listener does not exist in order to create one
        $($nextcloudFolder)
            .off('click.workspaceNextcloudHandler')
            .on('click.workspaceNextcloudHandler', this.switchWorkspaceTreeHandler());

        // case entcore workspace folder tree is interacted
        // we unbind its handler and rebind it in order to keep our list of workspace updated
        $(WorkspaceEntcoreUtils.$ENTCORE_WORKSPACE)
            .off('click.nextcloudHandler')
            .on('click.nextcloudHandler', this.switchNextcloudTreeHandler(viewModel));
    }

    /**
     * remove all the selected folders and documents of workspace
     */
    removeSelectedDocuments(): void {
        let selectedDocuments: Array<Document> = WorkspaceEntcoreUtils.workspaceScope()['openedFolder']['documents'];
        let folders: Array<Document> = WorkspaceEntcoreUtils.workspaceScope()['openedFolder']['folders'];
        if (selectedDocuments != null && folders != null) {
            selectedDocuments.forEach((doc: Document) => doc.selected = false);
            folders.forEach((fol: Document) => fol.selected = false);
        }
    }

    /**
     * add dragover/dragleave listeners
     */
    addDragEventListeners(): void {
        const folders: HTMLElement[] = Array.from(document.getElementsByTagName('folder-tree-inner')) as HTMLElement[];
        folders.forEach((element: HTMLElement) => {
            element.addEventListener('dragover', this.onDragOver(element));
            element.addEventListener('dragleave', this.onDragLeave(element));

            this.dragOverEventListeners.set(element, this.onDragOver(element));
            this.dragOverEventListeners.set(element, this.onDragLeave(element));
        });
    }

    /**
     * inject drag over overlays
     */
    addDragOverlays(): void {
        const folders: HTMLElement[] = Array.from(document.getElementsByTagName('folder-tree-inner')) as HTMLElement[];
        folders.forEach((element: HTMLElement, i: number) => {
            const span: HTMLElement = document.createElement('span');
            span.id = 'droptarget-' + i;
            span.className = 'highlight-title highlight-title-border ng-scope';
            const subSpan: HTMLElement = document.createElement('span');
            subSpan.className = 'count-badge ng-binding';
            span.appendChild(subSpan);

            const ul: Element = element.lastElementChild;
            if (ul.tagName === 'UL') {
                element.insertBefore(span, ul);
            } else {
                element.appendChild(span);
            }
            element.style.position = 'relative';
        })
    }

    /**
     * remove drag over overlay
     */
    removeDragOverlays(): void {
        const spans: HTMLElement[] = Array.from(document.querySelectorAll(`[id^="droptarget-"]`)) as HTMLElement[];
        spans.forEach((element: HTMLElement) => element.remove());
    }

    /**
     * remove dragover and dragleave event listeners
     */
    removeDragEventListeners (): void {
        this.dragOverEventListeners.forEach((listener: EventListener, element: HTMLElement) => element.removeEventListener('dragover', this.onDragOver(element)));
        this.dragOverEventListeners.clear()
        this.dragLeaveEventListeners.forEach((listener: EventListener, element: HTMLElement) => element.removeEventListener('dragleave', this.onDragLeave(element)));
        this.dragLeaveEventListeners.clear()
    }

    addDragFeedback(): void {
        this.addDragOverlays();
        this.addDragEventListeners();
    }

    removeDragFeedback(): void {
        this.removeDragOverlays();
        this.removeDragEventListeners();
    }

    private onDragLeave = (element: HTMLElement): EventListener => (event: Event): void => {
        event.preventDefault();
        event.stopPropagation();
        element.firstElementChild.classList.remove('droptarget');
    }

    private onDragOver = (element: HTMLElement): EventListener => (event: Event): void => {
        event.preventDefault();
        event.stopPropagation();
        element.firstElementChild.classList.add('droptarget');
    }

    /**
     * Remove workspace tree and use nextcloud tree instead.
     */
    private switchWorkspaceTreeHandler() {
        const viewModel: IViewModel = this;
        return function (): void {
            if (!viewModel.selectedFolder) {
                viewModel.folderTree.openFolder(viewModel.documents[0]);
            }
            const $workspaceFolderTree: JQuery = $(WorkspaceEntcoreUtils.$ENTCORE_WORKSPACE).find('li a');

            // using nextcloud content display
            template.open('documents', `../../../${RootsConst.template}/behaviours/workspace-nextcloud`);

            viewModel.removeSelectedDocuments();
            // clear all potential "selected" class workspace folder tree
            $workspaceFolderTree.each((index: number, element: Element): void => element.classList.remove("selected"));
            // hide workspace contents (search bar, menu, list of folder/files...) interactions
            WorkspaceEntcoreUtils.toggleWorkspaceContentDisplay(false);
        };
    }

    /**
     * Remove nextcloud tree and use workspace tree instead.
     */
    private switchNextcloudTreeHandler(viewModel: IViewModel) {
        return function (): void {
            let element: Element = arguments[0].target;
            let target: Element;
            if (element && element.tagName === 'A') {
                target = element;
            } else if (element && element.parentElement && element.parentElement.tagName === 'A') {
                target = element.parentElement;
            }

            if (target && viewModel.selectedFolder) {
                // go back to workspace content display
                // clear nextCloudTree interaction
                viewModel.selectedFolder = null;
                target.classList.add('selected');
                // update workspace folder content
                WorkspaceEntcoreUtils.updateWorkspaceDocuments(angular.element(target).scope().folder);
                //set the right openedFolder
                WorkspaceEntcoreUtils.workspaceScope()['openedFolder']['folder'] = angular.element(target).scope().folder;
                // display workspace contents (search bar, menu, list of folder/files...) interactions
                WorkspaceEntcoreUtils.toggleWorkspaceContentDisplay(true);
                // remove any content context cache
                Behaviours.applicationsBehaviours[NEXTCLOUD_APP].nextcloudService.setContentContext(null);
                template.open('documents', `icons`);
            }
        };
    }

    private onClickFolder(viewModel: IViewModel) {
        return function () {
            event.stopPropagation();
            const scope: any = angular.element(arguments[0].target).scope();
            const folder: models.Element = scope.folder;
            if (viewModel.openedFolder.some((openFolder: models.Element) => openFolder === folder)) {
                viewModel.openedFolder = viewModel.openedFolder.filter((openedFolder: models.Element) => openedFolder !== folder);
            } else {
                viewModel.openedFolder.push(folder);
            }
            safeApply(scope);
        };
    }
}

export const workspaceNextcloudFolder = {
    title: 'nextcloud.folder',
    public: false,
    that: null,
    controller: {
        init: function (): void {
            if (model.me.hasWorkflow(rights.workflow.access)) {
                lang.addBundle('/nextcloud/i18n', async () => {
                    await nextcloudUserService.resolveUser(model.me.userId);
                    this.vm = new ViewModel(this, nextcloudService, nextcloudUserService);
                    this.vm.folderCreation = new FolderCreationModel(this);
                });
            }
        }
    }
};
