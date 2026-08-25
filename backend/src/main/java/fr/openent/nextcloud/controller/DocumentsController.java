package fr.openent.nextcloud.controller;

import fr.openent.nextcloud.Nextcloud;
import fr.openent.nextcloud.core.constants.Field;
import fr.openent.nextcloud.helper.Attachment;
import fr.openent.nextcloud.helper.Metadata;
import fr.openent.nextcloud.helper.StringHelper;
import fr.openent.nextcloud.model.UserNextcloud;
import fr.openent.nextcloud.security.OwnerFilter;
import fr.openent.nextcloud.service.DocumentsService;
import fr.openent.nextcloud.service.ServiceFactory;
import fr.openent.nextcloud.service.UserService;
import fr.wseduc.rs.*;
import fr.wseduc.security.ActionType;
import fr.wseduc.security.SecuredAction;
import fr.wseduc.webutils.http.Renders;
import fr.wseduc.webutils.request.RequestUtils;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import org.entcore.common.controller.ControllerHelper;
import org.entcore.common.events.EventHelper;
import org.entcore.common.events.EventStore;
import org.entcore.common.events.EventStoreFactory;
import org.entcore.common.http.filter.ResourceFilter;
import org.entcore.common.storage.Storage;
import org.entcore.common.user.UserUtils;
import org.entcore.common.utils.StringUtils;

import java.util.List;

public class DocumentsController extends ControllerHelper {

    private final DocumentsService documentsService;
    private final UserService userService;
    private final Storage storage;
    private final EventHelper eventHelper;
    public static final String RESOURCE_DOC = "document";
    public static final String RESOURCE_FOLDER = "folder";
    // Whitelist stricte : "type" sert à construire un chemin de fichier template côté serveur
    // (template.<type>), ne jamais l'accepter tel quel sans validation.
    private static final List<String> ALLOWED_DOCUMENT_TYPES = List.of("docx", "xlsx", "pptx");
    private final EventBus eventBus;

    public DocumentsController(ServiceFactory serviceFactory) {
        this.documentsService = serviceFactory.documentsService();
        this.userService = serviceFactory.userService();
        this.storage = serviceFactory.storage();
        final EventStore eventStore = EventStoreFactory.getFactory().getEventStore(Nextcloud.class.getSimpleName());
        this.eventHelper = new EventHelper(eventStore);
        this.eventBus = serviceFactory.eventBus();

        initializeEventBusConsumers();
    }

    @Get("/files/user/:userid")
    @ApiDoc("API to list file/folder")
    @SecuredAction(value = "", type = ActionType.RESOURCE)
    @ResourceFilter(OwnerFilter.class)
    public void listFiles(HttpServerRequest request) {
        final String path = request.getParam(Field.PATH);
        UserUtils.getUserInfos(eb, request, user ->
                userService.getUserSession(user.getUserId())
                        .compose(userSession -> {
                            // Premier accès à la racine de l'espace synchronisé : on s'assure que le
                            // dossier propre à l'établissement de l'utilisateur existe déjà côté
                            // Nextcloud (best-effort, ne bloque jamais l'affichage de la liste).
                            if (StringUtils.isEmpty(path)) {
                                documentsService.ensureSyncFolderExists(Renders.getHost(request), userSession, user.getStructures());
                            }
                            return documentsService.listFiles(Renders.getHost(request), userSession, path);
                        })
                        .onSuccess(files -> {
                            renderJson(request, new JsonObject().put(Field.DATA, files));
                            if (StringUtils.isEmpty(path)) eventHelper.onAccess(request);
                        })
                        .onFailure(err -> renderError(request)));
    }

    @Get("/files/user/:userid/edit")
    @ApiDoc("API to get an online office-editing URL (Collabora/OnlyOffice) for a file, brokered with the per-user token")
    @SecuredAction(value = "", type = ActionType.RESOURCE)
    @ResourceFilter(OwnerFilter.class)
    public void getEditUrl(HttpServerRequest request) {
        final String path = request.getParam(Field.PATH);
        if (StringUtils.isEmpty(path)) {
            badRequest(request, "nextcloud.edit.path.missing");
            return;
        }
        UserUtils.getUserInfos(eb, request, user ->
                userService.getUserSession(user.getUserId())
                        .compose(userSession -> documentsService.getEditUrl(Renders.getHost(request), userSession, path))
                        .onSuccess(res -> renderJson(request, res))
                        .onFailure(err -> renderError(request)));
    }

    @Get("/files/user/:userid/file/:fileName/download")
    @ApiDoc("API to get or download file")
    @SecuredAction(value = "", type = ActionType.RESOURCE)
    @ResourceFilter(OwnerFilter.class)
    public void getFile(HttpServerRequest request) {
        String fileName = StringHelper.decodeUrlForNc(request.getParam(Field.FILENAME));
        String path = request.getParam(Field.PATH);
        String contentType = request.getParam(Field.CONTENTTYPE);
        boolean isFolder = Boolean.parseBoolean(request.getParam(Field.ISFOLDER));
        boolean inline = Boolean.parseBoolean(request.getParam(Field.INLINE));
        UserUtils.getUserInfos(eb, request, user ->
                userService.getUserSession(user.getUserId())
                        .compose(userSession -> {
                            if (isFolder) {
                                return documentsService.getFolder(Renders.getHost(request), userSession, path);
                            } else {
                                return documentsService.getFile(Renders.getHost(request), userSession, StringHelper.encodeUrlForNc(path));
                            }
                        })
                        .onSuccess(fileResponse -> {
                            HttpServerResponse resp = request.response();
                            if (isFolder) {
                                resp.putHeader("Content-Type", "application/octet-stream")
                                        .putHeader("Content-Disposition", "attachment; filename=\" "+ fileName +" .zip\"")
                                        .putHeader("Content-Description", "File Transfer")
                                        .putHeader("Content-Transfer-Encoding", "binary");
                            } else {
                                // inline : le navigateur affiche le fichier (PDF, image...) au lieu de le
                                // télécharger — utilisé par le clic "ouvrir" sur un document non éditable,
                                // par opposition au bouton "Télécharger" qui veut toujours "attachment".
                                String disposition = (inline ? "inline" : "attachment") + "; filename=" + fileName;
                                resp.putHeader("Content-type", contentType + "; charset=utf-8")
                                        .putHeader("Content-Disposition", disposition);
                            }
                            resp.end(fileResponse.body());
                        })
                        .onFailure(err -> renderError(request)));
    }

    @Get("/files/user/:userid/file/:fileId/preview")
    @ApiDoc("API to get a preview/thumbnail of a file (image, pdf, video…) generated by NextCloud")
    @SecuredAction(value = "", type = ActionType.RESOURCE)
    @ResourceFilter(OwnerFilter.class)
    public void getPreview(HttpServerRequest request) {
        String fileId = request.getParam(Field.FILEID);
        if (StringUtils.isEmpty(fileId)) {
            badRequest(request, "nextcloud.preview.fileid.missing");
            return;
        }
        int width = parsePreviewDimension(request.getParam(Field.WIDTH), 150);
        int height = parsePreviewDimension(request.getParam(Field.HEIGHT), 150);
        UserUtils.getUserInfos(eb, request, user ->
                userService.getUserSession(user.getUserId())
                        .compose(userSession -> documentsService.getPreview(Renders.getHost(request), userSession, Long.valueOf(fileId), width, height))
                        .onSuccess(previewResponse -> request.response()
                                .putHeader("Content-Type", "image/png")
                                .putHeader("Content-Disposition", "inline")
                                .putHeader("Cache-Control", "private, max-age=86400")
                                .end(previewResponse.body()))
                        .onFailure(err -> renderError(request)));
    }

    private int parsePreviewDimension(String rawValue, int defaultValue) {
        try {
            return StringUtils.isEmpty(rawValue) ? defaultValue : Integer.parseInt(rawValue);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    @Post("/files/user/:userid/share")
    @ApiDoc("API to share a NextCloud file/folder with another ENT user (native NextCloud sharing, enabling " +
            "real-time coproduction via OnlyOffice once both users open it)")
    @SecuredAction(value = "", type = ActionType.RESOURCE)
    @ResourceFilter(OwnerFilter.class)
    public void shareWithUser(HttpServerRequest request) {
        RequestUtils.bodyToJson(request, body -> {
            String path = body.getString(Field.PATH);
            String targetUserId = body.getString(Field.TARGETUSERID);
            String targetDisplayName = body.getString(Field.TARGETDISPLAYNAME);
            int permissions = body.getInteger(Field.PERMISSIONS, 3); // défaut : lecture + écriture
            if (StringUtils.isEmpty(path) || StringUtils.isEmpty(targetUserId) || StringUtils.isEmpty(targetDisplayName)) {
                badRequest(request, "nextcloud.share.parameters.missing");
                return;
            }
            UserUtils.getUserInfos(eb, request, user -> {
                UserNextcloud.RequestBody targetUserBody = new UserNextcloud.RequestBody()
                        .setUserId(targetUserId)
                        .setDisplayName(targetDisplayName);
                // S'assure que le destinataire a bien un compte NextCloud (NextCloud refuse un partage
                // vers un compte inexistant) avant de créer le partage avec le token du propriétaire.
                userService.provideUserSession(Renders.getHost(request), targetUserBody)
                        .compose(v -> userService.getUserSession(user.getUserId()))
                        .compose(userSession -> documentsService.shareWithUser(Renders.getHost(request), userSession, path, targetUserId, permissions))
                        .onSuccess(res -> renderJson(request, res))
                        .onFailure(err -> renderError(request, new JsonObject().put(Field.ERROR, err.getMessage())));
            });
        });
    }

    @Get("/files/user/:userid/multiple/download")
    @ApiDoc("API to download multiple files")
    @SecuredAction(value = "", type = ActionType.RESOURCE)
    @ResourceFilter(OwnerFilter.class)
    public void downloadMultipleFile(HttpServerRequest request) {
        String path = request.getParam(Field.PATH);
        List<String> files = request.params().getAll(Field.FILE);
        if ((path != null && !path.isEmpty()) && (files != null && !files.isEmpty())) {
            UserUtils.getUserInfos(eb, request, user ->
                    userService.getUserSession(user.getUserId())
                            .compose(userSession -> documentsService.getFiles(Renders.getHost(request), userSession, path, files))
                            .onSuccess(fileResponse -> {
                                HttpServerResponse resp = request.response();
                                resp.putHeader("Content-Disposition", "attachment; filename=\"" + Field.ARCHIVE + ".zip\"");
                                resp.putHeader("Content-Type", "application/octet-stream");
                                resp.putHeader("Content-Description", "File Transfer");
                                resp.putHeader("Content-Transfer-Encoding", "binary");
                                resp.end(fileResponse.body());
                            })
                            .onFailure(err -> renderError(request)));
        } else {
            badRequest(request);
        }
    }

    @Put("/files/user/:userid/move")
    @ApiDoc("Move documents file")
    @SecuredAction(value = "", type = ActionType.RESOURCE)
    @ResourceFilter(OwnerFilter.class)
    public void moveDocuments(HttpServerRequest request) {
        String path = request.getParam(Field.PATH);
        String destPath = request.getParam(Field.DESTPATH);
        if ((path != null && !path.isEmpty()) && (destPath != null && !destPath.isEmpty())) {
            UserUtils.getUserInfos(eb, request, user ->
                    userService.getUserSession(user.getUserId())
                            .compose(userSession -> {
                                        return documentsService.moveDocument(Renders.getHost(request), userSession, path, destPath);
                                    }
                            )
                            .onSuccess(res -> renderJson(request, res))
                            .onFailure(err -> renderError(request, new JsonObject().put(Field.MESSAGE, err.getMessage()))));
        } else {
            badRequest(request);
        }
    }


    @Delete("/files/user/:userid/delete")
    @ApiDoc("delete documents API")
    @SecuredAction(value = "", type = ActionType.RESOURCE)
    @ResourceFilter(OwnerFilter.class)
    public void deleteDocuments(HttpServerRequest request) {
        List<String> paths = request.params().getAll(Field.PATH);
        if ((paths != null && !paths.isEmpty())) {
            UserUtils.getUserInfos(eb, request, user ->
                    userService.getUserSession(user.getUserId())
                            .compose(userSession -> documentsService.deleteDocuments(Renders.getHost(request), userSession, paths))
                            .onSuccess(res -> renderJson(request, res))
                            .onFailure(err -> renderError(request, new JsonObject().put(Field.MESSAGE, err.getMessage()))));
        } else {
            badRequest(request);
        }
    }

    @Delete("/files/user/:userid/trash/delete-documents")
    @ApiDoc("Delete documents from trash")
    @SecuredAction(value = "", type = ActionType.RESOURCE)
    @ResourceFilter(OwnerFilter.class)
    public void deleteDocumentsFromTrashbin(HttpServerRequest request) {
        List<String> paths = request.params().getAll(Field.PATH);
        if ((paths != null && !paths.isEmpty())) {
            UserUtils.getUserInfos(eb, request, user -> userService.getUserSession(user.getUserId())
                    .compose(userSession -> documentsService.deleteDocumentsFromTrashbin(Renders.getHost(request),
                            userSession, paths))
                    .onSuccess(res -> renderJson(request, res))
                    .onFailure(err -> renderError(request, new JsonObject().put(Field.MESSAGE, err.getMessage()))));
        } else {
            badRequest(request);
        }
    }

    @Put("/files/user/:userid/restore")
    @ApiDoc("Restore documents from trash")
    @SecuredAction(value = "", type = ActionType.RESOURCE)
    @ResourceFilter(OwnerFilter.class)
    public void restoreDocuments(HttpServerRequest request) {
        List<String> paths = request.params().getAll(Field.PATH);
        if ((paths != null && !paths.isEmpty())) {
            UserUtils.getUserInfos(
                    eb,
                    request,
                    user -> userService
                            .getUserSession(user.getUserId())
                            .compose(userSession -> documentsService.restoreDocuments(Renders.getHost(request),
                                    userSession,
                                    paths))
                            .onSuccess(res -> renderJson(request, new JsonObject().put(Field.STATUS, Field.OK)))
                            .onFailure(err -> renderError(request,
                                    new JsonObject().put(Field.MESSAGE, err.getMessage()))));
        } else {
            badRequest(request);
        }
    }

    @Get("/files/user/:userid/trash")
    @ApiDoc("API to list trash files")
    @SecuredAction(value = "", type = ActionType.RESOURCE)
    @ResourceFilter(OwnerFilter.class)
    public void listTrash(HttpServerRequest request) {
        UserUtils.getUserInfos(eb, request, user -> userService.getUserSession(user.getUserId())
                .compose(userSession -> documentsService.listTrash(Renders.getHost(request), userSession))
                .onSuccess(res -> renderJson(request, res))
                .onFailure(err -> renderError(request, new JsonObject().put(Field.MESSAGE, err.getMessage()))));
    }

    @Delete("/files/user/:userid/trash/delete")
    @ApiDoc("delete trash API")
    @SecuredAction(value = "", type = ActionType.RESOURCE)
    @ResourceFilter(OwnerFilter.class)
    public void deleteTrash(HttpServerRequest request) {
        UserUtils.getUserInfos(eb, request, user ->
                userService.getUserSession(user.getUserId())
                        .compose(userSession -> documentsService.deleteTrash(Renders.getHost(request), userSession))
                        .onSuccess(res -> renderJson(request, new JsonObject().put(Field.STATUS, Field.OK)))
                        .onFailure(err -> renderError(request, new JsonObject().put(Field.MESSAGE, err.getMessage()))));
    }

    @Put("/files/user/:userid/upload")
    @ApiDoc("Upload file")
    @SecuredAction(value = "", type = ActionType.RESOURCE)
    @ResourceFilter(OwnerFilter.class)
    public void uploadDocuments(HttpServerRequest request) {
        request.pause();
        UserUtils.getUserInfos(eb, request, user ->
                userService.getUserSession(user.getUserId())
                        .compose(userSession -> {
                            request.resume();
                            return documentsService.uploadStreamedMultipleFiles(Field.FILECOUNT, request, userSession, vertx, user.getStructures());
                        })
                        .onSuccess(res -> {
                            renderJson(request, res);
                            eventHelper.onCreateResource(request, RESOURCE_DOC);
                        })
                        .onFailure(err -> renderExtensionOrGenericError(request, err)));

    }

    // "extension.forbidden" doit être distinguable côté front pour afficher un message clair
    // à l'utilisateur (cf. DefaultDocumentsService#checkExtensionAllowed), pas juste une 500.
    private void renderExtensionOrGenericError(HttpServerRequest request, Throwable err) {
        if ("extension.forbidden".equals(err.getMessage())) {
            Renders.renderJson(request, new JsonObject().put(Field.ERROR, "extension.forbidden"), 403);
        } else {
            renderError(request, new JsonObject().put(Field.ERROR, err.getMessage()));
        }
    }

    @Put("/files/user/:userid/move/workspace")
    @ApiDoc("Move a file from Nextcloud to ENT workspace")
    @SecuredAction(value = "", type = ActionType.RESOURCE)
    @ResourceFilter(OwnerFilter.class)
    public void moveToWorkspace(HttpServerRequest request) {
        List<String> listFiles = request.params().getAll(Field.PATH);
        String parentId = request.params().get(Field.PARENTID);
        if (Boolean.FALSE.equals(listFiles.isEmpty()))
            UserUtils.getUserInfos(eb, request, user ->
                userService.getUserSession(user.getUserId())
                        .compose(userSession -> documentsService.moveDocumentToWorkspace(Renders.getHost(request), userSession, user, listFiles, parentId))
                        .onSuccess(res -> renderJson(request, new JsonObject().put(Field.DATA, res)))
                        .onFailure(err -> renderError(request, new JsonObject().put(Field.ERROR, err.getMessage()))));
        else
            badRequest(request);
    }

    @Put("/files/user/:userid/copy/workspace")
    @ApiDoc("Copy a file from Nextcloud to ENT workspace")
    @SecuredAction(value = "", type = ActionType.RESOURCE)
    @ResourceFilter(OwnerFilter.class)
    public void copyToWorkspace(HttpServerRequest request) {
        List<String> listFiles = request.params().getAll(Field.PATH);
        String parentId = request.params().get(Field.PARENTID);
        if (!listFiles.isEmpty())
            UserUtils.getUserInfos(eb, request, user ->
                userService.getUserSession(user.getUserId())
                        .compose(userSession -> documentsService.copyDocumentToWorkspace(Renders.getHost(request), userSession, user, listFiles, parentId))
                        .onSuccess(res -> renderJson(request, new JsonObject().put(Field.DATA, res)))
                        .onFailure(err -> renderError(request, new JsonObject().put(Field.ERROR, err.getMessage()))));
        else
            badRequest(request);
    }

    @Put("/files/user/:userid/workspace/move/cloud")
    @ApiDoc("Move a file from ENT workspace to cloud")
    @SecuredAction(value = "", type = ActionType.RESOURCE)
    @ResourceFilter(OwnerFilter.class)
    public void moveToCloud(HttpServerRequest request) {
        List<String> listFiles = request.params().getAll(Field.ID);
        String parentId = request.params().get(Field.PARENTNAME);
        if (!listFiles.isEmpty())
            UserUtils.getUserInfos(eb, request, user ->
                    userService.getUserSession(user.getUserId())
                            .compose(userSession -> documentsService.moveDocumentsFromWorkspaceToNC(Renders.getHost(request), userSession, user, listFiles, parentId))
                            .onSuccess(res -> {
                                renderJson(request, res);
                                eventHelper.onCreateResource(request, RESOURCE_DOC);
                            })
                            .onFailure(err -> renderError(request, new JsonObject().put(Field.ERROR, err.getMessage()))));
        else
            badRequest(request);
    }

    @Put("/files/user/:userid/workspace/copy/cloud")
    @ApiDoc("Copy a file from ENT workspace to cloud")
    @SecuredAction(value = "", type = ActionType.RESOURCE)
    @ResourceFilter(OwnerFilter.class)
    public void copyToCloud(HttpServerRequest request) {
        List<String> listFiles = request.params().getAll(Field.ID);
        String parentId = request.params().get(Field.PARENTNAME);
        if (!listFiles.isEmpty())
            UserUtils.getUserInfos(eb, request, user ->
                    userService.getUserSession(user.getUserId())
                            .compose(userSession -> documentsService.copyDocumentsFromWorkspaceToNC(Renders.getHost(request), userSession, user, listFiles, parentId))
                            .onSuccess(res -> renderJson(request, res))
                            .onFailure(err -> renderError(request, new JsonObject().put(Field.ERROR, err.getMessage()))));
        else
            badRequest(request);
    }

    @Post("/files/user/:userid/create/folder")
    @ApiDoc("Create a folder in Nextcloud")
    @SecuredAction(value = "", type = ActionType.RESOURCE)
    @ResourceFilter(OwnerFilter.class)
    public void createNewFolder(HttpServerRequest request) {
        String path = request.params().get(Field.PATH);
        if (!path.isEmpty())
            UserUtils.getUserInfos(eb, request, user ->
                    userService.getUserSession(user.getUserId())
                            .compose(userSession -> documentsService.createFolderNextcloud(Renders.getHost(request), userSession, path))
                            .onSuccess(res -> {
                                renderJson(request, res);
                                eventHelper.onCreateResource(request, RESOURCE_FOLDER);
                            })
                            .onFailure(err -> renderError(request, new JsonObject().put(Field.ERROR, err.getMessage()))));
        else
            badRequest(request);
    }

    @Post("/files/user/:userid/create/document")
    @ApiDoc("Create a blank office document (docx/xlsx/pptx) from a template in Nextcloud")
    @SecuredAction(value = "", type = ActionType.RESOURCE)
    @ResourceFilter(OwnerFilter.class)
    public void createNewDocument(HttpServerRequest request) {
        String type = request.params().get(Field.TYPE);
        String name = request.params().get(Field.NAME);
        String path = request.params().get(Field.PATH);
        if (StringUtils.isEmpty(type) || StringUtils.isEmpty(name) || !ALLOWED_DOCUMENT_TYPES.contains(type)) {
            badRequest(request);
            return;
        }
        UserUtils.getUserInfos(eb, request, user ->
                userService.getUserSession(user.getUserId())
                        .compose(userSession -> documentsService.createDocumentFromTemplate(Renders.getHost(request), userSession, type, name, path))
                        .onSuccess(res -> {
                            renderJson(request, res);
                            eventHelper.onCreateResource(request, RESOURCE_DOC);
                        })
                        .onFailure(err -> renderError(request, new JsonObject().put(Field.ERROR, err.getMessage()))));
    }

    private void initializeEventBusConsumers() {
        MessageConsumer<JsonObject> nextcloudRackEventConsumer = eventBus.consumer("nextcloud.rack.upload");

        nextcloudRackEventConsumer.handler(message -> {
            JsonObject body = message.body();

            String fileId = body.getString("fileId");
            JsonObject metadata = body.getJsonObject("metadata");
            String recipientUserId = body.getString("recipientId");
            String host = body.getString("host");

            Attachment attachment = new Attachment(fileId, new Metadata(metadata));

            userService.getUserSession(recipientUserId)
                    .compose(userSession -> documentsService.listFiles(
                            host, userSession, "CASIER")
                            .compose(files -> {
                                if (files.isEmpty()) {
                                    // Folder does not exist, create it
                                    return documentsService.createFolderNextcloud(
                                                    host, userSession,
                                            "CASIER");
                                } else {
                                    return io.vertx.core.Future.succeededFuture(new io.vertx.core.json.JsonObject());
                                }
                            })
                            .compose(result -> documentsService.uploadFile(
                                    host,
                                    userSession, attachment, "CASIER", Boolean.FALSE)))
                    .onSuccess(result -> {
                        message.reply(new JsonObject()
                                .put(Field.STATUS, Field.OK)
                                .put(Field.MESSAGE, "Upload succeeded"));
                    })
                    .onFailure(err -> {
                        message.fail(500, "Upload failed: " + err.getMessage());
                    });
        });
    }
}
