import {SyncDocument} from "../models";
import {Document, FolderTreeProps, model, workspace} from "entcore";
import {AxiosError} from "axios";
import {nextcloudService, nextcloudUserService} from "../services";
import {NextcloudDocumentsUtils} from "../utils/nextcloud-documents.utils";
import rights from "../rights";
import {WorkspaceEntcoreUtils} from "../utils/workspace-entcore.utils";
import {Element} from "entcore/types/src/ts/workspace/model";
import models = workspace.v2.models;
import service = workspace.v2.service;

interface IVirtualMediaLibraryScope {

    /**
     * document folder type
     */
    folders: Array<Document>;

    /**
     * any documents
     */
    documents: Array<Document>;

    /**
     * the current opened tree loaded from behaviours
     */
    openedTree: FolderTreeProps;

    /**
     * While initializing the service tree, this method will be implemented to any behaviours in order to enable the mediaLibraryService
     * @returns {boolean}
     */
    enableInitFolderTree(): boolean;

    /**
     * Init the folder tree service behaviour's ideal
     * **IMPORTANT**: Requires this method to populate {folders} and {documents} members
     */
    initFolderTree(): Promise<void>;

    /**
     * Open the folder (via tree or in the content view)
     * **IMPORTANT**: Requires this method to populate {folders} children and {documents} children members
     */
    openFolder(folder: Document): Promise<void>;


    /**
     * This method will execute the behaviour's action before its executes the media library scope {selectDocuments()}
     *
     * **IMPORTANT**: Requires this method to populate {folders} children and {documents} children members
     * @returns {Array<Document>} where all elements will be selected and assigned to media library's {documents} scope
     * before executing media library scope {selectDocuments()}
     *
     * @example
     * in one module's behaviour, we implement onSelectVirtualDocumentsBefore() on which we call an API service to duplicate the document and return
     * the wished documents to be assigned
     *
     * Another example would be to call an API or any other method
     * in the end, this shall return a "truth" document with truth id to be interacted for the {selectDocuments()}
     *
     * @param documents selected documents used to "materialize" into documents
     */
    onSelectVirtualDocumentsBefore(documents: Array<any>): Promise<Array<Document>>;

    /**
     * (optional) Allows clear copied documents (if you decided in your method {onSelectVirtualDocumentsBefore()})
     * to duplicate your selected document or entities and returns new documents within
     *
     * @param documents selected documents
     */
    clearCopiedDocumentsAfterSelect(documents: Array<Document>): Promise<void>;
}

export class MediaLibraryService implements IVirtualMediaLibraryScope {
    openedTree: any;
    folders: Array<Document>;
    documents: Array<Document>;

    constructor() {
        this.folders = [];
        this.documents = [];
    }

    enableInitFolderTree(): boolean {
        if (model.me.hasWorkflow(rights.workflow.access)) {
            nextcloudUserService.resolveUser(model.me.userId);
            this.registerNextcloudThumbUrlMapper();
            return true;
        } else {
            return false;
        }
    }

    private registerNextcloudThumbUrlMapper(): void {
        const nextcloudFunction = (element: Element) => {
            if (element.application === "nextcloud") {
                return nextcloudService.getFile(model.me.userId, <any>element.name, (<any>element).path, <any>element.contentType);
            }
        }
        models.Element.registerThumbUrlMapper(nextcloudFunction);
    }

    async initFolderTree(): Promise<void> {
        let syncDocuments: Array<SyncDocument> = await nextcloudService.listDocument(model.me.userId, null)
            .catch((err: AxiosError) => {
                const message: string = "Error while attempting to fetch documents children ";
                console.error(message + err.message);
                return [];
            });

        // populate folder content to media library behaviours
        this.folders = (<any>syncDocuments)
            .filter(NextcloudDocumentsUtils.filterRemoveNameFile())
            .filter(NextcloudDocumentsUtils.filterDocumentOnly());

        // populate file content to media library behaviours
        this.documents =  WorkspaceEntcoreUtils.toDocuments(
            (<any>syncDocuments)
                .filter(NextcloudDocumentsUtils.filterRemoveNameFile())
                .filter(NextcloudDocumentsUtils.filterFilesOnly())
        );
    }

    async openFolder(folder: models.Element): Promise<void> {
        let syncDocuments: Array<SyncDocument> = await nextcloudService.listDocument(model.me.userId, (<any>folder).path ? (<any>folder).path : null)
            .catch((err: AxiosError) => {
                const message: string = "Error while attempting to fetch documents children ";
                console.error(message + err.message);
                return [];
            });

        // first filter applies only when we happen to fetch its own folder and the second applies on document only
        (<any>this.folders) = syncDocuments
            .filter(NextcloudDocumentsUtils.filterRemoveOwnDocument((<any>folder)))
            .filter(NextcloudDocumentsUtils.filterDocumentOnly());

        (<any>this.documents) = WorkspaceEntcoreUtils.toDocuments(
            syncDocuments
                .filter(NextcloudDocumentsUtils.filterRemoveOwnDocument((<any>folder)))
                .filter(NextcloudDocumentsUtils.filterFilesOnly())
        );
    }

    async onSelectVirtualDocumentsBefore(documents: Array<any>): Promise<Array<Document>> {
        const paths: Array<string> = (documents as Array<SyncDocument>).map((doc: SyncDocument) => doc.path);
        return nextcloudService.copyDocumentToWorkspace(model.me.userId, paths)
            .then((res: Array<models.Element>) => res.map(d => new Document(d)));
    }

    async clearCopiedDocumentsAfterSelect(documents: Array<Document>): Promise<void> {
        if (documents && documents.length)
            service.deleteAll(documents);
    }

}