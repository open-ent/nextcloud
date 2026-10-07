import {model, notify, idiom as lang, template} from "entcore";
import {RootsConst} from "../../core/constants/roots.const";
import {SyncDocument, VisibleUser} from "../../models";
import {nextcloudService, userSearchService} from "../../services";

const SEARCH_MIN_LENGTH: number = 3;
const SEARCH_DEBOUNCE_MS: number = 300;

interface IViewModel {
    searchQuery: string;
    searchResults: Array<VisibleUser>;
    searching: boolean;
    selectedUser: VisibleUser;
    sharing: boolean;

    toggleShareNextcloudView(state: boolean, selectedDocuments?: Array<SyncDocument>): void;
    onSearchQueryChange(): void;
    selectUser(user: VisibleUser): void;
    resetSelectedUser(): void;
    onConfirmShareNextcloud(): Promise<void>;
    onCancelShareNextcloud(): void;
}

export class ToolbarShareNextcloudSnipletViewModel implements IViewModel {
    private vm: any;
    private scopeParent: any;
    private documentsToShare: Array<SyncDocument>;
    private searchTimeout: any;

    searchQuery: string;
    searchResults: Array<VisibleUser>;
    searching: boolean;
    selectedUser: VisibleUser;
    sharing: boolean;

    constructor(scopeParent: any) {
        this.scopeParent = scopeParent;
        this.vm = scopeParent.vm;
        this.resetState();
    }

    private resetState(): void {
        this.documentsToShare = [];
        this.searchQuery = "";
        this.searchResults = [];
        this.searching = false;
        this.selectedUser = null;
        this.sharing = false;
    }

    toggleShareNextcloudView(state: boolean, selectedDocuments?: Array<SyncDocument>): void {
        this.scopeParent.lightbox.shareNextcloud = state;
        if (state && selectedDocuments) {
            this.documentsToShare = selectedDocuments;
            const pathTemplate: string = `../../../${RootsConst.template}/behaviours/sniplet-nextcloud-content/toolbar/share-nextcloud/share-nextcloud-picker`;
            template.open('workspace-nextcloud-toolbar-share-nextcloud', pathTemplate);
        } else {
            template.close('workspace-nextcloud-toolbar-share-nextcloud');
            this.resetState();
        }
    }

    onSearchQueryChange(): void {
        this.selectedUser = null;
        clearTimeout(this.searchTimeout);
        const query: string = this.searchQuery ? this.searchQuery.trim() : "";
        if (query.length < SEARCH_MIN_LENGTH) {
            this.searchResults = [];
            this.searching = false;
            return;
        }
        this.searching = true;
        this.searchTimeout = setTimeout(() => {
            userSearchService.searchVisibleUsers(query)
                .then((users: Array<VisibleUser>) => {
                    this.searchResults = users.filter((user: VisibleUser) => user.id !== model.me.userId);
                    this.searching = false;
                    this.vm.safeApply();
                })
                .catch((err) => {
                    this.searching = false;
                    this.searchResults = [];
                    notify.error(lang.translate('nextcloud.share.native.search.error'));
                    console.error('[Nextcloud@ToolbarShareNextcloudSnipletViewModel::onSearchQueryChange] ', err);
                    this.vm.safeApply();
                });
        }, SEARCH_DEBOUNCE_MS);
    }

    selectUser(user: VisibleUser): void {
        this.selectedUser = user;
        this.searchResults = [];
        this.searchQuery = user.displayName;
    }

    resetSelectedUser(): void {
        this.selectedUser = null;
        this.searchQuery = "";
        this.searchResults = [];
    }

    async onConfirmShareNextcloud(): Promise<void> {
        if (!this.selectedUser || this.documentsToShare.length === 0) {
            return;
        }
        this.sharing = true;
        try {
            await Promise.all(this.documentsToShare.map((document: SyncDocument) =>
                nextcloudService.shareWithUser(model.me.userId, document.path, this.selectedUser.id, this.selectedUser.displayName)));
            notify.success(lang.translate('nextcloud.share.native.success'));
            this.vm.selectedDocuments = [];
            this.toggleShareNextcloudView(false);
        } catch (err) {
            notify.error(lang.translate('nextcloud.share.native.error'));
            console.error('[Nextcloud@ToolbarShareNextcloudSnipletViewModel::onConfirmShareNextcloud] ', err);
        } finally {
            this.sharing = false;
            this.vm.safeApply();
        }
    }

    onCancelShareNextcloud(): void {
        this.toggleShareNextcloudView(false);
    }
}
