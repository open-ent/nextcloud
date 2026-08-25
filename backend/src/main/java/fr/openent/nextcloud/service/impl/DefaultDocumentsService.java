package fr.openent.nextcloud.service.impl;
import fr.openent.nextcloud.config.NextcloudConfig;
import fr.openent.nextcloud.core.constants.Field;

import fr.openent.nextcloud.core.enums.WorkspaceEventBusActions;
import fr.openent.nextcloud.core.enums.XmlnsAttr;
import fr.openent.nextcloud.helper.*;
import fr.openent.nextcloud.model.Document;
import fr.openent.nextcloud.model.NextcloudFolder;
import fr.openent.nextcloud.model.UserNextcloud;
import fr.openent.nextcloud.model.XmlnsOptions;
import fr.openent.nextcloud.model.UserNextcloud.TokenProvider;
import fr.openent.nextcloud.service.DocumentsService;
import fr.openent.nextcloud.service.ServiceFactory;

import fr.wseduc.webutils.http.Renders;
import io.vertx.core.*;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.file.OpenOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.codec.BodyCodec;
import org.entcore.common.bus.WorkspaceHelper;
import org.entcore.common.storage.Storage;
import org.entcore.common.user.UserInfos;
import org.entcore.common.utils.FileUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class DefaultDocumentsService implements DocumentsService {
    private static final Logger log = LoggerFactory.getLogger(DefaultDocumentsService.class);
    private final WebClient client;
    private final Map<String, NextcloudConfig> nextcloudConfigMapByHost;
    private final Storage storage;
    private final WorkspaceHelper workspaceHelper;
    private final EventBus eventBus;
    private final fr.wseduc.mongodb.MongoDb mongoDb;
    private final org.entcore.common.neo4j.Neo4j neo4j;
    private final Vertx vertx;

    private static final String DOWNLOAD_ENDPOINT = "/index.php/apps/files/ajax/download.php";


    public DefaultDocumentsService(ServiceFactory serviceFactory) {
        this.client = serviceFactory.webClient();
        this.nextcloudConfigMapByHost = serviceFactory.nextcloudConfigMapByHost();
        this.storage = serviceFactory.storage();
        this.workspaceHelper = serviceFactory.workspaceHelper();
        this.eventBus = serviceFactory.eventBus();
        this.mongoDb = serviceFactory.mongoDb();
        this.neo4j = serviceFactory.neo4j();
        this.vertx = serviceFactory.vertx();
    }

    private static String extensionOf(String filename) {
        int i = filename.lastIndexOf('.');
        return i < 0 || i == filename.length() - 1 ? "" : filename.substring(i + 1).toLowerCase();
    }

    /**
     * Rejette la promesse avec "extension.forbidden" si l'extension du fichier est exclue pour
     * l'établissement de l'utilisateur (précédence local > national > défaut, cf.
     * DesktopConfigHelper). userStructures peut être vide/null pour un utilisateur sans
     * établissement : seul le réglage national s'applique alors.
     * @return true si le fichier est autorisé (aucun rejet effectué)
     */
    private Future<Boolean> checkExtensionAllowed(String filename, List<String> userStructures) {
        Promise<Boolean> promise = Promise.promise();
        String extension = extensionOf(filename);
        DesktopConfigHelper.getExcludedExtensions(mongoDb, userStructures).onSuccess(excluded -> {
            if (excluded.contains(extension)) {
                promise.fail("extension.forbidden");
            } else {
                promise.complete(true);
            }
        }).onFailure(promise::fail);
        return promise.future();
    }

    /**
     * List files/folder
     *
     * @param host host
     * @param userSession   User Session
     * @param path   path of nextcloud's user
     * @return Future Instance of User from Nextcloud {@link JsonArray}
     */
    @Override
    public Future<JsonArray> listFiles(String host, UserNextcloud.TokenProvider userSession, String path) {
        Promise<JsonArray> promise = Promise.promise();
        parameterizedListFiles(host, userSession, path, responseAsync -> proceedListFiles(responseAsync, promise));
        return promise.future();
    }

    /**
     * Récupère une URL d'édition en ligne pour un fichier.
     * S'appuie sur l'API « Direct Editing » du cœur de NextCloud
     * ({@code POST /ocs/v2.php/apps/files/api/v1/directEditing/open}), authentifiée avec le token
     * per-user du connecteur — donc sans session NextCloud côté utilisateur. L'URL renvoyée ouvre
     * l'éditeur en s'appuyant sur le token, aucune connexion demandée.
     * Pas de {@code editorId} imposé : NextCloud choisit automatiquement l'éditeur enregistré pour
     * le type du fichier (Collabora ou OnlyOffice) — le choix se fait côté admin NextCloud
     * (Applications), pas dans ce connecteur.
     */
    @Override
    public Future<JsonObject> getEditUrl(String host, UserNextcloud.TokenProvider userSession, String path) {
        Promise<JsonObject> promise = Promise.promise();
        final NextcloudConfig nextcloudConfig = this.nextcloudConfigMapByHost.get(host);
        final JsonObject body = new JsonObject()
                .put("path", path.startsWith("/") ? path : "/" + path);
        this.client.postAbs(nextcloudConfig.host() + "/ocs/v2.php/apps/files/api/v1/directEditing/open?format=json")
                .basicAuthentication(userSession.userId(), userSession.token())
                .putHeader("OCS-APIRequest", "true")
                .as(BodyCodec.jsonObject())
                .sendJsonObject(body, responseAsync -> {
                    if (responseAsync.failed()) {
                        log.error("[Nextcloud@DefaultDocumentsService::getEditUrl] Failed to open direct editing session: ", responseAsync.cause());
                        promise.fail(responseAsync.cause().getMessage());
                        return;
                    }
                    final JsonObject data = responseAsync.result().body()
                            .getJsonObject("ocs", new JsonObject())
                            .getJsonObject(Field.DATA, new JsonObject());
                    final String url = data.getString("url");
                    if (url == null || url.isEmpty()) {
                        log.error("[Nextcloud@DefaultDocumentsService::getEditUrl] No edit url returned: " + responseAsync.result().body());
                        promise.fail("nextcloud.edit.url.unavailable");
                    } else {
                        promise.complete(new JsonObject().put("url", url));
                    }
                });
        return promise.future();
    }

    @Override
    public void parameterizedListFiles(String host, UserNextcloud.TokenProvider userSession, String path, Handler<AsyncResult<HttpResponse<String>>> handler) {
        final NextcloudConfig nextcloudConfig = this.nextcloudConfigMapByHost.get(host);
        this.client.requestAbs(HttpMethod.PROPFIND, nextcloudConfig.host() +
                nextcloudConfig.webdavEndpoint() + "/" + userSession.userId() + (path != null ? "/" + StringHelper.encodeUrlForNc(path) : "" ))
                .basicAuthentication(userSession.userId(), userSession.token())
                .as(BodyCodec.string(StandardCharsets.UTF_8.toString()))
                .sendBuffer(Buffer.buffer(getListFilesPropsBody()), handler);
    }

    /**
     * prepare PROPFIND body request (sent as XML stringify)
     *
     * @return XML as body string to process PROPFIND
     */
    private String getListFilesPropsBody() {
        Document.RequestBody requestBody = new Document.RequestBody();
        XmlnsOptions xmlnsOptions = new XmlnsOptions()
                .setWebDavTag(XmlnsAttr.D)
                .setNextcloudTag(XmlnsAttr.NC)
                .setOwnCloudTag(XmlnsAttr.OC);
        return XMLHelper.createXML(requestBody.toJSON(), Field.D_PROPFIND, xmlnsOptions);
    }

    /**
     * Proceed async event after HTTP PROPFIND (fetching files/folder) API endpoint has been sent
     *
     * @param   responseAsync   HttpResponse of string depending on its state {@link AsyncResult}
     * @param   promise         Promise that could be completed or fail sending {@link JsonArray}
     */
    private void proceedListFiles(AsyncResult<HttpResponse<String>> responseAsync, Promise<JsonArray> promise) {
        if (responseAsync.failed()) {
            String messageToFormat = "[Nextcloud@%s::listFiles] An error has occurred during fetching endpoint : %s";
            PromiseHelper.reject(log, messageToFormat, this.getClass().getSimpleName(), responseAsync, promise);
        } else {
            HttpResponse<String> response = responseAsync.result();
            // complete promise if 207.
            // Even if we fetch 404 status, as we consider the request has been fulfilled but has not found the document(s)
            // we still attempt to create an array (will be an empty one)
            if (response.statusCode() == 207 || response.statusCode() == 404) {
                JsonObject results = XMLHelper.toJsonObject(response.body());
                JsonArray responses = getResultMultiStatus(results);
                List<Document> documents = DocumentHelper.documents(responses);
                promise.complete(new JsonArray(DocumentHelper.toListJsonObject(documents).toString()));
            } else {
                String messageToFormat = "[Nextcloud@%s::listFiles] Response status is not a HTTP 207 : %s : %s";
                HttpResponseHelper.reject(log, messageToFormat, this.getClass().getSimpleName(), response, promise);
            }
        }
    }

    /**
     * Crée automatiquement, côté serveur Nextcloud, le dossier synchronisé de l'utilisateur
     * (nom résolu selon la précédence établissement > national > préfixe + UAI, cf.
     * DesktopConfigHelper) s'il n'existe pas déjà. Appelé au premier accès à la racine de
     * l'espace synchronisé (cf. DocumentsController#listFiles) ; best-effort, ne doit jamais
     * faire échouer l'affichage de la liste si la création échoue.
     */
    @Override
    public void ensureSyncFolderExists(String host, UserNextcloud.TokenProvider userSession, List<String> userStructures) {
        String structureId = (userStructures != null && !userStructures.isEmpty()) ? userStructures.get(0) : null;
        if (structureId == null) {
            resolveAndCreateSyncFolder(host, userSession, userStructures, null, null);
            return;
        }

        String cypher = "MATCH (s:Structure {id: {structureId}}) RETURN s.UAI as UAI, s.name as name LIMIT 1";
        JsonObject params = new JsonObject().put("structureId", structureId);
        neo4j.execute(cypher, params, org.entcore.common.neo4j.Neo4jResult.validResultHandler(event -> {
            JsonArray results = event.isRight() ? event.right().getValue() : new JsonArray();
            String uai = !results.isEmpty() ? results.getJsonObject(0).getString("UAI") : null;
            String name = !results.isEmpty() ? results.getJsonObject(0).getString("name") : null;
            resolveAndCreateSyncFolder(host, userSession, userStructures, uai, name);
        }));
    }

    private void resolveAndCreateSyncFolder(String host, UserNextcloud.TokenProvider userSession,
                                             List<String> userStructures, String uai, String name) {
        DesktopConfigHelper.getSyncFolderName(mongoDb, userStructures, uai, name)
                .onSuccess(folderName -> createFolderIfMissing(host, userSession, folderName))
                .onFailure(err -> log.warn("[Nextcloud@ensureSyncFolderExists] Failed to resolve sync folder name: " + err.getMessage()));
    }

    private void createFolderIfMissing(String host, UserNextcloud.TokenProvider userSession, String path) {
        final NextcloudConfig nextcloudConfig = this.nextcloudConfigMapByHost.get(host);
        this.client.requestAbs(HttpMethod.MKCOL, nextcloudConfig.host() + nextcloudConfig.webdavEndpoint() + "/" +
                        userSession.userId() + "/" + StringHelper.encodeUrlForNc(path))
                .basicAuthentication(userSession.userId(), userSession.token())
                .send(responseAsync -> {
                    if (responseAsync.failed()) {
                        log.warn("[Nextcloud@ensureSyncFolderExists] MKCOL request failed for " + path, responseAsync.cause());
                        return;
                    }
                    int status = responseAsync.result().statusCode();
                    // 201 : dossier créé. 405 : existe déjà (MKCOL sur une collection existante) — les
                    // deux sont des issues normales, pas une erreur à signaler.
                    if (status != 201 && status != 405) {
                        log.warn("[Nextcloud@ensureSyncFolderExists] Unexpected status " + status + " creating " + path);
                    }
                });
    }

    /**
     * Create a nextcloud folder
     * @param host host
     * @param userSession   The user session
     * @param path          The path on the new folder on the nextcloud server
     * @return              Promise with status of the creation
     */
    private Future<JsonObject> createFolder(String host, UserNextcloud.TokenProvider userSession, String path) {
        Promise<JsonObject> promise = Promise.promise();
        final NextcloudConfig nextcloudConfig = this.nextcloudConfigMapByHost.get(host);
        this.client.requestAbs(HttpMethod.MKCOL, nextcloudConfig.host() + nextcloudConfig.webdavEndpoint() + "/" +
                userSession.userId() + (path != null ? "/" + path : "" ))
                .basicAuthentication(userSession.userId(), userSession.token())
                .send(responseAsync -> {
                    if (responseAsync.result().statusCode() != 201) {
                        String messageToFormat = "[Nextcloud@%s::createFolder] Response status is not a HTTP 201 : %s : %s";
                        HttpResponseHelper.reject(log, messageToFormat, this.getClass().getSimpleName(), responseAsync.result(), promise);
                    } else {
                        promise.complete(new JsonObject().put(Field.STATUS, Field.OK));
                    }

                });
        return promise.future();
    }

    /**
     * method that will determine result multi status
     *
     * @param   results   results documents to parse
     * @return  list of response documents
     */
    private JsonArray getResultMultiStatus(JsonObject results) {
        JsonArray responses;
        try {
            JsonObject multiStatusObject = results.getJsonObject(Field.D_MULTISTATUS, new JsonObject());
            // if fetching D_RESPONSE value is JsonArray, it's an array of response, else it would be a JsonObject one
            if (multiStatusObject.getValue(Field.D_RESPONSE) instanceof JsonArray) {
                responses = multiStatusObject.getJsonArray(Field.D_RESPONSE, new JsonArray());
            } else {
                responses = new JsonArray();
                if (!multiStatusObject.getJsonObject(Field.D_RESPONSE, new JsonObject()).isEmpty()) {
                    responses.add(multiStatusObject.getJsonObject(Field.D_RESPONSE, new JsonObject()));
                }
            }
        } catch (ClassCastException e) {
            String message = String.format("[Nextcloud@%s::proceedListFiles] An error has occurred during attempting to fetch response data : %s, " +
                    "returning empty list", this.getClass().getSimpleName(), e.getMessage());
            log.error(message);
            responses = new JsonArray();
        }
        return responses;
    }

    @Override
    public Future<HttpResponse<Buffer>> getFile(String host, UserNextcloud.TokenProvider userSession, String path) {
        Promise<HttpResponse<Buffer>> promise = Promise.promise();
        final NextcloudConfig nextcloudConfig = this.nextcloudConfigMapByHost.get(host);
        this.client.getAbs(nextcloudConfig.host() + nextcloudConfig.webdavEndpoint() + "/" +
                        userSession.userId() + (path != null ? "/" + path : "" ))
                .basicAuthentication(userSession.userId(), userSession.token())
                .send(responseAsync -> proceedGetDocument(responseAsync, promise));
        return promise.future();
    }

    @Override
    public Future<JsonObject> shareWithUser(String host, UserNextcloud.TokenProvider userSession, String path, String targetUserId, int permissions) {
        Promise<JsonObject> promise = Promise.promise();
        final NextcloudConfig nextcloudConfig = this.nextcloudConfigMapByHost.get(host);
        final JsonObject body = new JsonObject()
                .put("path", path.startsWith("/") ? path : "/" + path)
                .put("shareType", 0) // 0 = partage vers un utilisateur (par opposition à un groupe/lien public)
                .put("shareWith", targetUserId)
                .put("permissions", permissions);
        this.client.postAbs(nextcloudConfig.host() + "/ocs/v2.php/apps/files_sharing/api/v1/shares?format=json")
                .basicAuthentication(userSession.userId(), userSession.token())
                .putHeader("OCS-APIRequest", "true")
                .as(BodyCodec.jsonObject())
                .sendJsonObject(body, responseAsync -> {
                    if (responseAsync.failed()) {
                        log.error("[Nextcloud@DefaultDocumentsService::shareWithUser] Failed to create share: ", responseAsync.cause());
                        promise.fail(responseAsync.cause().getMessage());
                        return;
                    }
                    final JsonObject ocs = responseAsync.result().body().getJsonObject("ocs", new JsonObject());
                    final int statusCode = ocs.getJsonObject("meta", new JsonObject()).getInteger("statuscode", 0);
                    if (statusCode != 200) {
                        String message = ocs.getJsonObject("meta", new JsonObject()).getString("message", "unknown error");
                        log.error("[Nextcloud@DefaultDocumentsService::shareWithUser] NextCloud refused the share : " + message);
                        promise.fail(message);
                    } else {
                        promise.complete(ocs.getJsonObject(Field.DATA, new JsonObject()));
                    }
                });
        return promise.future();
    }

    @Override
    public Future<HttpResponse<Buffer>> getPreview(String host, UserNextcloud.TokenProvider userSession, Number fileId, int width, int height) {
        Promise<HttpResponse<Buffer>> promise = Promise.promise();
        final NextcloudConfig nextcloudConfig = this.nextcloudConfigMapByHost.get(host);
        this.client.getAbs(nextcloudConfig.host() + "/index.php/core/preview")
                .addQueryParam("fileId", String.valueOf(fileId))
                .addQueryParam("x", String.valueOf(width))
                .addQueryParam("y", String.valueOf(height))
                .basicAuthentication(userSession.userId(), userSession.token())
                .send(responseAsync -> proceedGetDocument(responseAsync, promise));
        return promise.future();
    }

    /**
     * Proceed async event after HTTP get (get/downloading) file API endpoint has been sent
     *
     * @param   responseAsync   HttpResponse of string depending on its state {@link AsyncResult}
     * @param   promise         Promise that could be completed or fail sending {@link Buffer}
     */
    private void proceedGetDocument(AsyncResult<HttpResponse<Buffer>> responseAsync, Promise<HttpResponse<Buffer>> promise) {
        if (responseAsync.failed()) {
            String messageToFormat = "[Nextcloud@%s::proceedGetDocument] An error has occurred during fetching endpoint : %s";
            PromiseHelper.reject(log, messageToFormat, this.getClass().getSimpleName(), responseAsync, promise);
        } else {
            HttpResponse<Buffer> response = responseAsync.result();
            if (response.statusCode() != 200) {
                String messageToFormat = "[Nextcloud@%s::proceedGetDocument] Response status is not a HTTP 200 : %s : %s";
                HttpResponseHelper.reject(log, messageToFormat, this.getClass().getSimpleName(), response, promise);
            } else {
                promise.complete(response);
            }
        }
    }

    @Override
    public Future<HttpResponse<Buffer>> getFiles(String host, UserNextcloud.TokenProvider userSession, String path, List<String> files) {
        Promise<HttpResponse<Buffer>> promise = Promise.promise();
        final NextcloudConfig nextcloudConfig = this.nextcloudConfigMapByHost.get(host);
        this.client.getAbs(nextcloudConfig.host() + DOWNLOAD_ENDPOINT)
                .basicAuthentication(userSession.userId(), userSession.token())
                .addQueryParam(Field.DIR, path)
                .addQueryParam(Field.FILES, new JsonArray(files).toString())
                .send(responseAsync -> proceedGetDocument(responseAsync, promise));
        return promise.future();
    }

    @Override
    public Future<HttpResponse<Buffer>> getFolder(String host, UserNextcloud.TokenProvider userSession, String path) {
        Promise<HttpResponse<Buffer>> promise = Promise.promise();
        final NextcloudConfig nextcloudConfig = this.nextcloudConfigMapByHost.get(host);
        this.client.getAbs(nextcloudConfig.host() + DOWNLOAD_ENDPOINT)
                .basicAuthentication(userSession.userId(), userSession.token())
                .addQueryParam(Field.DIR, path)
                .send(responseAsync -> proceedGetDocument(responseAsync, promise));
        return promise.future();
    }


    @Override
    public Future<JsonObject> moveDocument(String host, UserNextcloud.TokenProvider userSession, String path, String destPath) {
        Promise<JsonObject> promise = Promise.promise();

        listFiles(host, userSession, destPath)
                .onSuccess(fileInfo -> {
                            if (fileInfo.isEmpty()) {
                                final NextcloudConfig nextcloudConfig = this.nextcloudConfigMapByHost.get(host);
                                String endpoint = nextcloudConfig.host() + nextcloudConfig.webdavEndpoint() + "/" + userSession.userId();
                                this.client.requestAbs(HttpMethod.MOVE, endpoint + "/" + StringHelper.encodeUrlForNc(path))
                                        .basicAuthentication(userSession.userId(), userSession.token())
                                        .putHeader(Field.DESTINATION, endpoint + "/" + StringHelper.encodeUrlForNc(destPath))
                                        .send(responseAsync -> this.onMoveDocumentHandler(responseAsync, promise));

                            } else {
                                promise.fail("nextcloud.file.already.exist");
                            }
                        })
                .onFailure(promise::fail);

        return promise.future();
    }

    /**
     * Proceed async event after HTTP MOVE documents API endpoint has been sent
     *
     * @param   responseAsync   HttpResponse of string depending on its state {@link AsyncResult}
     * @param   promise         Promise that could be completed or fail sending {@link JsonObject}
     */
    private void onMoveDocumentHandler(AsyncResult<HttpResponse<Buffer>> responseAsync, Promise<JsonObject> promise) {
        if (responseAsync.failed()) {
            String messageToFormat = "[Nextcloud@%s::onMoveDocumentHandler] An error has occurred during fetching endpoint : %s";
            PromiseHelper.reject(log, messageToFormat, this.getClass().getSimpleName(), responseAsync, promise);
        } else {
            HttpResponse<Buffer> response = responseAsync.result();
            if (response.statusCode() != 201) {
                String messageToFormat = "[Nextcloud@%s::onMoveDocumentHandler] Response status is not a HTTP 201 : %s : %s";
                HttpResponseHelper.reject(log, messageToFormat, this.getClass().getSimpleName(), response, promise);
            } else {
                promise.complete(new JsonObject().put(Field.STATUS, Field.OK));
            }
        }
    }

    @Override
    public Future<JsonObject> deleteDocuments(String host, UserNextcloud.TokenProvider userSession, List<String> paths) {
        Promise<JsonObject> promise = Promise.promise();

        Future<Void> current = Future.succeededFuture();

        for (String path : paths) {
            current = current.compose(v -> this.deleteDocument(host, userSession, path));
        }
        current
                .onSuccess(res -> promise.complete(new JsonObject().put(Field.STATUS, Field.OK)))
                .onFailure(err -> {
                    String messageToFormat = "[Nextcloud@%s::deleteDocuments] An error has occurred during deleting document(s): %s";
                    PromiseHelper.reject(log, messageToFormat, this.getClass().getSimpleName(), err, promise);
                });
        return promise.future();
    }

    /**
     * method that delete document / path of folder within document(s)
     *
     * @param host host
     * @param   userSession     User Session {@link UserNextcloud.TokenProvider}
     * @param   path            path to delete
     */
    private Future<Void> deleteDocument(String host, UserNextcloud.TokenProvider userSession, String path) {
        Promise<Void> promise = Promise.promise();
        final NextcloudConfig nextcloudConfig = this.nextcloudConfigMapByHost.get(host);
        this.client.deleteAbs(nextcloudConfig.host() + nextcloudConfig.webdavEndpoint() + "/" + userSession.userId() + "/" + path)
                .basicAuthentication(userSession.userId(), userSession.token())
                .send(responseAsync -> onDeleteDocumentHandler(responseAsync, promise));
        return promise.future();
    }

    @Override
    public Future<JsonObject> deleteDocumentsFromTrashbin(String host, TokenProvider userSession, List<String> paths) {
        Promise<JsonObject> promise = Promise.promise();

        Future<Void> current = Future.succeededFuture();

        for (String path : paths) {
            current = current
                    .compose(v -> this.deleteDocumentsFromTrashbin(host, userSession, path));
        }
        current
                .onSuccess(res -> promise.complete(new JsonObject().put(Field.STATUS, Field.OK)))
                .onFailure(err -> {
                    String messageToFormat = "[Nextcloud@%s::deleteDocumentsFromTrashbin] An error has occurred during deleting document(s) from trashbin: %s";
                    PromiseHelper.reject(log, messageToFormat, this.getClass().getSimpleName(), err, promise);
                });
        return promise.future();
    }

    private Future<Void> deleteDocumentsFromTrashbin(String host, TokenProvider userSession, String path) {
        Promise<Void> promise = Promise.promise();
        final NextcloudConfig nextcloudConfig = this.nextcloudConfigMapByHost.get(host);
        String url = nextcloudConfig.host() + nextcloudConfig.webdavEndpoint().replace(Field.FILES, "") + Field.TRASHBIN
                + "/" + userSession.userId() + "/" + path;
        this.client.deleteAbs(url)
                .basicAuthentication(userSession.userId(), userSession.token())
                .send(responseAsync -> onDeleteDocumentHandler(responseAsync, promise));
        return promise.future();
    }

    /**
     * Restore documents from trash
     * 
     * @param host        host
     * @param userSession User Session {@link UserNextcloud.TokenProvider}
     * @param paths       list of paths / documents to restore
     * @return
     */
    @Override
    public Future<Void> restoreDocuments(String host, UserNextcloud.TokenProvider userSession, List<String> paths) {
        Promise<Void> promise = Promise.promise();

        final NextcloudConfig nextcloudConfig = this.nextcloudConfigMapByHost.get(host);

        if (host == null || nextcloudConfig == null || userSession == null
                || userSession.userId() == null || userSession.token() == null) {
            String messageToFormat = "[Nextcloud@%s::restoreDocuments] Invalid input parameters.";
            PromiseHelper.reject(log, messageToFormat, this.getClass().getSimpleName(),
                    new Exception("Invalid input parameters."), promise);
            return promise.future();
        }

        if (paths == null || paths.isEmpty()) {
            String messageToFormat = "[Nextcloud@%s::restoreDocuments] No paths provided to restore.";
            PromiseHelper.reject(log, messageToFormat, this.getClass().getSimpleName(),
                    new Exception("No paths provided to restore."), promise);
            return promise.future();
        }

        String baseUrl = nextcloudConfig.host();
        String userId = userSession.userId();
        String authToken = userSession.token();

        List<Future<Void>> operations = new ArrayList<>();

        for (String trashPath : paths) {
            String filename = trashPath.substring(trashPath.lastIndexOf("/") + 1);
            String sourceUrl = baseUrl + "remote.php/dav/trashbin/" + userId + trashPath;
            String destinationUrl = baseUrl + "remote.php/dav/trashbin/" + userId + "/restore/" + filename;

            Promise<Void> operationPromise = Promise.promise();

            this.client.requestAbs(HttpMethod.MOVE, sourceUrl)
                    .putHeader("Destination", destinationUrl)
                    .basicAuthentication(userId, authToken)
                    .send(ar -> {
                        if (ar.failed()) {
                            String messageToFormat = "[Nextcloud@%s::restoreDocuments] Failed to restore document from: %s -> %s";
                            log.error(messageToFormat, this.getClass().getSimpleName(), sourceUrl, destinationUrl,
                                    ar.cause());
                            operationPromise.fail(ar.cause());
                        } else {
                            HttpResponse<Buffer> response = ar.result();
                            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                                log.debug("[Nextcloud@{}::restoreDocuments] Successfully restored from trash: {}",
                                        this.getClass().getSimpleName(), trashPath);
                                operationPromise.complete();
                            } else {
                                String error = String.format("Restore failed (%d): %s", response.statusCode(),
                                        response.statusMessage());
                                log.error("[Nextcloud@{}::restoreDocuments] {}", this.getClass().getSimpleName(),
                                        error);
                                operationPromise.fail(error);
                            }
                        }
                    });

            operations.add(operationPromise.future());
        }

        Future.all(operations)
                .onSuccess(v -> promise.complete())
                .onFailure(err -> {
                    String messageToFormat = "[Nextcloud@%s::restoreDocuments] One or more restore operations failed";
                    PromiseHelper.reject(log, messageToFormat, this.getClass().getSimpleName(), err, promise);
                });

        return promise.future();
    }


    /**
     * List files in the Nextcloud trash
     *
     * @param host        host
     * @param userSession User Session {@link UserNextcloud.TokenProvider}
     * @return Future with a JsonArray of trash entries
     */
    @Override
    public Future<JsonArray> listTrash(String host, UserNextcloud.TokenProvider userSession) {
        Promise<JsonArray> promise = Promise.promise();
        final NextcloudConfig nextcloudConfig = this.nextcloudConfigMapByHost.get(host);

        if (host == null
                || nextcloudConfig == null
                || userSession == null
                || userSession.userId() == null
                || userSession.token() == null) {
            String messageToFormat = "[Nextcloud@%s::listTrash] Invalid input parameters.";
            PromiseHelper.reject(log, messageToFormat, this.getClass().getSimpleName(),
                    new Exception("Invalid input parameters."), promise);
            return promise.future();
        }

        String url = nextcloudConfig.host() + "remote.php/dav/" + Field.TRASHBIN + "/" +
                userSession.userId() + "/" + Field.TRASH;

        this.client
                .requestAbs(HttpMethod.PROPFIND, url)
                .basicAuthentication(userSession.userId(), userSession.token())
                .send(responseAsync -> {
                    if (responseAsync.failed()) {
                        String messageToFormat = "[Nextcloud@%s::listTrash] An error has occurred during PROPFIND : %s";
                        PromiseHelper.reject(log, messageToFormat, this.getClass().getSimpleName(), responseAsync,
                                promise);
                        return;
                    }

                    HttpResponse<Buffer> response = responseAsync.result();
                    if (response.statusCode() != 207) {
                        String messageToFormat = "[Nextcloud@%s::listTrash] Response status is not 207 Multi-Status : %s : %s";
                        HttpResponseHelper.reject(log, messageToFormat, this.getClass().getSimpleName(), response,
                                promise);
                        return;
                    }

                    try {
                        JsonObject xml = XMLHelper.toJsonObject(response.bodyAsString());
                        JsonArray entries = getResultMultiStatus(xml);
                        JsonArray result = parseTrashEntries(entries);
                        promise.complete(result);
                    } catch (Exception e) {
                        String messageToFormat = "[Nextcloud@%s::listTrash] Failed to parse response : %s";
                        PromiseHelper.reject(log, messageToFormat, this.getClass().getSimpleName(), e, promise);
                    }
                });

        return promise.future();
    }

    /**
     * Parse trash entries from Nextcloud PROPFIND response
     *
     * @param entries JsonArray of entries
     * @return JsonArray of parsed trash documents
     */
    private JsonArray parseTrashEntries(JsonArray entries) {
        JsonArray result = new JsonArray();

        for (int i = 0; i < entries.size(); i++) {
            try {
                JsonObject entry = entries.getJsonObject(i);
                JsonObject propstat = entry.getJsonObject(Field.D_PROPSTAT);
                if (propstat == null)
                    continue;

                JsonObject prop = propstat.getJsonObject(Field.D_PROP);
                if (prop == null)
                    continue;

                String href = entry.getString(Field.D_HREF);
                if (href == null || href.endsWith("/trash/"))
                    continue;

                if (href.endsWith("/")) {
                    href = href.substring(0, href.length() - 1);
                }

                String name = href.substring(href.lastIndexOf("/") + 1).replaceFirst("\\.d\\d+$", "");

                boolean isFolder = prop.containsKey(Field.D_RESOURCETYPE)
                        && prop.getValue(Field.D_RESOURCETYPE) instanceof JsonObject
                        && prop.getJsonObject(Field.D_RESOURCETYPE).containsKey(Field.D_COLLECTION);

                JsonObject doc = new JsonObject()
                        .put(Field.PATH, href)
                        .put(Field.DISPLAYNAME, name)
                        .put(Field.ISFOLDER, isFolder)
                        .put(Field.OC_OWNER_DISPLAY_NAME, prop.getString(Field.OC_OWNER_DISPLAY_NAME, ""))
                        .put(Field.SIZE, prop.getLong(Field.D_GETCONTENTLENGTH, 0L))
                        .put(Field.D_GETLASTMODIFIED, prop.getString(Field.D_GETLASTMODIFIED, ""))
                        .put(Field.D_GETETAG, prop.getString(Field.D_GETETAG, ""))
                        .put(Field.D_GETCONTENTTYPE, prop.getString(Field.D_GETCONTENTTYPE, ""))
                        .put(Field.FILEID, prop.getString(Field.FILEID, ""));

                result.add(doc);
            } catch (Exception e) {
                String messageToFormat = "[Nextcloud@%s::parseTrashEntries] Error while parsing trash entry : %s";
                log.error(String.format(messageToFormat, this.getClass().getSimpleName(),
                        entries.getJsonObject(i).encode()), e);
            }
        }

        return result;
    }

    /**
     * method that delete trash
     * @param host host
     * @param   userSession     User Session {@link UserNextcloud.TokenProvider}
     */
    public Future<Void> deleteTrash(String host, UserNextcloud.TokenProvider userSession) {
        Promise<Void> promise = Promise.promise();
        final NextcloudConfig nextcloudConfig = this.nextcloudConfigMapByHost.get(host);
        this.client.deleteAbs(nextcloudConfig.host() + nextcloudConfig.webdavEndpoint().replace(Field.FILES, "") + Field.TRASHBIN + "/" + userSession.userId() + "/" + Field.TRASH)
                .basicAuthentication(userSession.userId(), userSession.token())
                .send(responseAsync -> onDeleteDocumentHandler(responseAsync, promise));
        return promise.future();
    }

    /**
     * Proceed async event after HTTP DELETE document API endpoint has been sent
     *
     * @param   responseAsync   HttpResponse of string depending on its state {@link AsyncResult}
     * @param   promise         Promise that could be completed or fail sending {@link JsonObject}
     */
    private void onDeleteDocumentHandler(AsyncResult<HttpResponse<Buffer>> responseAsync, Promise<Void> promise) {
        if (responseAsync.failed()) {
            String messageToFormat = "[Nextcloud@%s::onDeleteDocumentHandler] An error has occurred during fetching endpoint : %s";
            PromiseHelper.reject(log, messageToFormat, this.getClass().getSimpleName(), responseAsync, promise);
        } else {
            HttpResponse<Buffer> response = responseAsync.result();
            if (response.statusCode() != 204) {
                String messageToFormat = "[Nextcloud@%s::onDeleteDocumentHandler] Response status is not a HTTP 204 : %s : %s";
                HttpResponseHelper.reject(log, messageToFormat, this.getClass().getSimpleName(), response, promise);
            } else {
                promise.complete();
            }
        }
    }

    @Override
    public Future<JsonArray> uploadStreamedMultipleFiles(String headerCount, HttpServerRequest request, UserNextcloud.TokenProvider user, Vertx vertx, List<String> userStructures) {
        final String host = Renders.getHost(request);
        String path = request.getParam(Field.PATH);
        request.response().setChunked(true);
        request.setExpectMultipart(true);

        Promise<JsonArray> promise = Promise.promise();
        JsonArray listMetadata = new JsonArray();

        String totalFilesToUpload = request.getHeader(headerCount);
        AtomicBoolean responseSent = new AtomicBoolean(false);

        if (totalFilesToUpload == null || totalFilesToUpload.isEmpty() || Integer.parseInt(totalFilesToUpload) == 0) {
            promise.complete(new JsonArray());
            return promise.future();
        }

        AtomicInteger incrementFile = new AtomicInteger(0);

        request.exceptionHandler(event -> {
            String msg = "[Nextcloud@uploadStreamedMultipleFiles] HTTP request error: %s";
            PromiseHelper.reject(log, msg, this.getClass().getSimpleName(), event.getCause(), promise);
        });

        request.uploadHandler(upload -> {
            upload.pause();
            final JsonObject metadata = FileUtils.metadata(upload);
            final Attachment attachment = new Attachment(UUID.randomUUID().toString(), new Metadata(metadata));
            listMetadata.add(attachment.toJson());

            try {
                Path tmpFile = Files.createTempFile("nextcloud-", attachment.id());
                upload.handler(buffer -> {
                    try {
                        Files.write(tmpFile, buffer.getBytes(), StandardOpenOption.APPEND);
                    } catch (IOException e) {
                        String msg = "[Nextcloud@uploadStreamedMultipleFiles] Failed writing to tmp file: %s";
                        try { Files.deleteIfExists(tmpFile); } catch (IOException ignored) {}
                        PromiseHelper.reject(log, msg, this.getClass().getSimpleName(), e.getCause(), promise);
                    }
                });

                upload.endHandler(v -> {
                    uploadStreamedFile(host, user, attachment, path, tmpFile, vertx, userStructures)
                            .onSuccess(res -> {
                                try { Files.deleteIfExists(tmpFile); } catch (IOException ignored) {}
                                if (incrementFile.incrementAndGet() == Integer.parseInt(totalFilesToUpload) && !responseSent.get()) {
                                    responseSent.set(true);
                                    promise.complete(listMetadata);
                                }
                            })
                            .onFailure(th -> {
                                try { Files.deleteIfExists(tmpFile); } catch (IOException ignored) {}
                                String msg = "[Nextcloud@uploadStreamedMultipleFiles] Upload error: %s";
                                PromiseHelper.reject(log, msg, this.getClass().getSimpleName(), th, promise);
                            });
                });

                upload.resume();
            } catch (IOException e) {
                PromiseHelper.reject(log, "[Nextcloud@uploadStreamedMultipleFiles] Failed creating tmp file", this.getClass().getSimpleName(), e, promise);
            }
        });

        return promise.future();
    }

    private Future<JsonObject> uploadStreamedFile(String host, UserNextcloud.TokenProvider user, Attachment file, String path, Path tmpFile, Vertx vertx, List<String> userStructures) {
        Promise<JsonObject> promise = Promise.promise();
        String finalPath = (path != null ? path + "/" : "") + file.metadata().filename();

        checkExtensionAllowed(file.metadata().filename(), userStructures)
                .onSuccess(allowed -> uploadStreamedFileAllowed(host, user, file, finalPath, tmpFile, vertx)
                        .onSuccess(promise::complete)
                        .onFailure(promise::fail))
                .onFailure(err -> {
                    try { Files.deleteIfExists(tmpFile); } catch (IOException ignored) {}
                    promise.fail(err);
                });

        return promise.future();
    }

    private Future<JsonObject> uploadStreamedFileAllowed(String host, UserNextcloud.TokenProvider user, Attachment file, String finalPath, Path tmpFile, Vertx vertx) {
        Promise<JsonObject> promise = Promise.promise();

        this.getUniqueFileName(host, user, finalPath, 0)
                .onSuccess(filePath -> {
                    final NextcloudConfig nextcloudConfig = this.nextcloudConfigMapByHost.get(host);

                    vertx.fileSystem().open(tmpFile.toString(), new OpenOptions())
                            .onSuccess(asyncFile -> {
                                client.putAbs(nextcloudConfig.host() + nextcloudConfig.webdavEndpoint() + "/" +
                                                user.userId() + "/" + StringHelper.encodeUrlForNc(filePath))
                                        .basicAuthentication(user.userId(), user.token())
                                        .as(BodyCodec.jsonObject())
                                        .sendStream(asyncFile, ar -> {
                                            try { Files.deleteIfExists(tmpFile); } catch (IOException ignored) {}
                                            if (ar.failed()) {
                                                String messageToFormat = "[Nextcloud@%s::uploadStreamedFile] Upload failed: %s";
                                                PromiseHelper.reject(log, messageToFormat, this.getClass().getSimpleName(), ar.cause(), promise);
                                            } else {
                                                promise.complete(new JsonObject()
                                                        .put(Field.NAME, file.metadata().filename())
                                                        .put(Field.STATUSCODE, ar.result().statusCode()));
                                            }
                                        });
                            })
                            .onFailure(err -> {
                                PromiseHelper.reject(log, "[Nextcloud@uploadStreamedFile] Failed ro read tmp file", this.getClass().getSimpleName(), err, promise);
                            });
                })
                .onFailure(err -> promise.complete(new JsonObject().put(Field.ERROR, err)));

        return promise.future();
    }

    /**
     * Upload multiple files to the nextcloud
     * @param host host
     * @param userSession      User session
     * @param files            List of files to upload
     * @param path             Final path on Nextcloud
     * @return                 Future JsonArray with data on the uploaded files
     */
    @Override
    @Deprecated
    public Future<JsonArray> uploadFiles(String host, UserNextcloud.TokenProvider userSession, List<Attachment> files, String path) {
        Promise<JsonArray> promise = Promise.promise();
        Future<JsonObject> current = Future.succeededFuture();
        JsonArray stateUploadedFiles = new JsonArray();
        for (Attachment file : files) {
            current = current.compose(res -> {
                if (res != null) {
                    stateUploadedFiles.add(res);
                }
                return this.uploadFile(host, userSession, file, path, Boolean.TRUE);
            });
        }
        current
                .onSuccess(res -> {
                    stateUploadedFiles.add(res);
                    promise.complete(stateUploadedFiles);
                })
                .onFailure(err -> {
                    String messageToFormat = "[Nextcloud@%s::uploadFiles] An error has occurred during uploading files : %s";
                    PromiseHelper.reject(log, messageToFormat, this.getClass().getSimpleName(), err, promise);
                });
        return promise.future();
    }

    /**
     * Return JsonObject with error code, status and name of the file.
     * @param futureRes Future with the result of the operation
     * @return JsonObject with error code, status and name of the file
     */
    private JsonObject answerJsonError(Map.Entry<String, Future<JsonObject>> futureRes) {
        return new JsonObject()
                .put(Field.NAME, futureRes.getKey())
                .put(Field.ERROR, futureRes.getValue().cause().getMessage())
                .put(Field.STATUS, Field.KO_LOWER);
    }

    /**
     * Create folder into workspace and copy every file from NC folder to new workspace folder.
     * @param host host
     * @param fileInfo      Information about the file
     * @param file          Path to the file on nc server
     * @param parentId      Identifier of the parent in the workspace (if you want to create the new folder under specific one)
     * @param user          User infos
     * @param userSession   Session infos
     * @return              Infos about the copy
     */
    Future<JsonObject> folderCopy(String host, JsonArray fileInfo, String file, String parentId, UserInfos user, UserNextcloud.TokenProvider userSession) {
        Promise<JsonObject> promise = Promise.promise();
        JsonObject folderData = new JsonObject();
        NextcloudFolder ncFolder = new NextcloudFolder(fileInfo);
        if (!ncFolder.isSet()) {
            String messageToFormat = "[Nextcloud@%s::folderCopy] Error while retrieve folder data : %s";
            PromiseHelper.reject(log, messageToFormat, this.getClass().getSimpleName(), new Exception("folder.info.retrieve.failed"), promise);
            return promise.future();
        }
        ncFolder.setPath(file);
        JsonObject action = new JsonObject()
                .put(Field.ACTION, WorkspaceEventBusActions.ADDFOLDER.action())
                .put(Field.NAME, ncFolder.getName())
                .put(Field.OWNER, user.getUserId())
                .put(Field.OWNERNAME, user.getUsername())
                .put(Field.PARENTFOLDERID, parentId);
        EventBusHelper.requestJsonObject(eventBus, action)
                .compose(folderInfos -> {
                    ncFolder.setWorkspaceId(folderInfos.getString(Field.UNDERSCORE_ID));
                    folderData.put(Field.DATA, folderInfos);
                    return copyDocumentToWorkspace(host, userSession, user, ncFolder.getFolderItemPath(), ncFolder.getWorkspaceId());
                })
                .onSuccess(resultFolderMove -> promise.complete(folderData.getJsonObject(Field.DATA)
                        .put(Field.NAME, ncFolder.getName())
                        .put(Field.ISFOLDER, Field.YES)
                        .put(Field.STATUS, Field.OK_LOWER)
                        .put(Field.DATA, resultFolderMove)))
                .onFailure(err -> {
                    String messageToFormat = "[Nextcloud@%s::folderCopy] Error while handling folder copy : %s";
                    PromiseHelper.reject(log, messageToFormat, this.getClass().getSimpleName(), err, promise);
                });
        return promise.future();
    }

    /**
     * Create folder into workspace and move every file from NC folder to new workspace folder.
     * @param host host
     * @param fileInfo      Information about the file
     * @param file          Path to the file on nc server
     * @param parentId      Identifier of the parent in the workspace (if you want to create the new folder under specific one)
     * @param user          User infos
     * @param userSession   Session infos
     * @return              Infos about the move
     */
    Future<JsonObject> folderMove(String host, JsonArray fileInfo, String file, String parentId, UserInfos user, UserNextcloud.TokenProvider userSession) {
        Promise<JsonObject> promise = Promise.promise();
        JsonObject result = new JsonObject();

        folderCopy(host, fileInfo, file, parentId, user, userSession)
                .compose(folderCopyInfos -> {
                    result.put(Field.RESULT, folderCopyInfos);
                    return deleteDocument(host, userSession, file);
                })
                .onSuccess(deleteStatus -> promise.complete(result.getJsonObject(Field.RESULT)))
                .onFailure(err -> {
                    String messageToFormat = "[Nextcloud@%s::folderMove] Error while handling folder move : %s";
                    PromiseHelper.reject(log, messageToFormat, this.getClass().getSimpleName(), err, promise);
                });

        return promise.future();
    }

    /**
     * Copy all the files listed in the filesPath from nextcloud to workspace.
     * @param host host
     * @param userSession       User session
     * @param user              User infos
     * @param filesPath         Path of all the files to move
     * @param parentId          Identifier of the previous folder if moving in a folder
     * @return                  Future list of JsonObject with infos about every file copied
     */
    public Future<List<JsonObject>> copyDocumentToWorkspace(String host, UserNextcloud.TokenProvider userSession,
                                                      UserInfos user,
                                                      List<String> filesPath,
                                                      String parentId) {
        Promise<List<JsonObject>> promise = Promise.promise();
        Future<JsonObject> current = Future.succeededFuture();
        Map<String, Future<JsonObject>> result = new HashMap<>();
        for (String file : filesPath) {
            current = current.compose(v -> {
                Future<JsonObject> future = copyToWorkspace(host, userSession, user, file.startsWith("/") ? file.substring(1) : file, parentId);
                Promise<JsonObject> succeedPromise = Promise.promise();
                future.onComplete(r -> {
                    result.put(file, future);
                    succeedPromise.complete();
                });
                return succeedPromise.future();
            });
        }
        current.onSuccess(v -> promise.complete(result.entrySet().stream().map(futureRes -> {
            if (futureRes.getValue().succeeded())
                return futureRes.getValue().result();
            else {
                return answerJsonError(futureRes);
            }

        }).collect(Collectors.toList())));
        return promise.future();
    }



    /**
     * Move all the files listed in the filesPath from nextcloud to workspace.
     * @param host host
     * @param userSession       User session
     * @param user              User infos
     * @param filesPath         Path of all the files to move
     * @param parentId          Identifier of the previous folder if moving in a folder
     * @return                  Future list of JsonObject with infos about every file moved
     */
    @Override
    public Future<List<JsonObject>> moveDocumentToWorkspace(String host, UserNextcloud.TokenProvider userSession,
                                                            UserInfos user,
                                                            List<String> filesPath,
                                                            String parentId) {
        Promise<List<JsonObject>> promise = Promise.promise();
        Future<JsonObject> current = Future.succeededFuture();
        Map<String, Future<JsonObject>> result = new HashMap<>();
        for (String file : filesPath) {
            current = current.compose(v -> {
                Future<JsonObject> future = moveToWorkspace(host, userSession, user, file.startsWith("/") ? file.substring(1) : file, parentId);
                Promise<JsonObject> succeedPromise = Promise.promise();
                future.onComplete(r -> {
                            result.put(file, future);
                            succeedPromise.complete();
                        });
                return succeedPromise.future();
            });

        }
        current.onSuccess(v -> promise.complete(result.entrySet().stream().map(futureRes -> {
                    if (futureRes.getValue().succeeded())
                        return futureRes.getValue().result();
                    else {
                        return answerJsonError(futureRes);
                    }

                }).collect(Collectors.toList())));
        return promise.future();
    }

    /** Copy one document from Nextcloud to Workspace
     * @param host host
     * @param userSession       User session
     * @param user              User infos
     * @param file              Path of the file you want to move
     * @param parentId          Identifier of the previous folder if moving in a folder
     * @return                  Future Json with the infos about the copy
     */
    private Future<JsonObject> copyToWorkspace(String host, UserNextcloud.TokenProvider userSession,
                                               UserInfos user,
                                               String file,
                                               String parentId) {
        Promise<JsonObject> promiseResult = Promise.promise();
        //The listFiles function here is called to gather data on one specific file.
        String decodedPath = StringHelper.decodeUrlForNc(file).replace(Field.ASCIISPACE, Field.PLUS_SIGN);
        listFiles(host, userSession, decodedPath)
                .onSuccess(fileInfo -> {
                    if (!fileInfo.isEmpty()) {
                        JsonObject resJson = fileInfo.getJsonObject(0);
                        if (!resJson.containsKey(Field.DISPLAYNAME) || resJson.getString(Field.DISPLAYNAME).equals("")
                                || !resJson.containsKey(Field.ISFOLDER)) {
                            String messageToFormat = "[Nextcloud@%s::copyToWorkspace] An error has occurred while retrieving data from file : %s";
                            PromiseHelper.reject(log, messageToFormat, this.getClass().getSimpleName(), new Exception("Missing data field in listFiles infos"), promiseResult);
                            return;
                        }
                        if (Boolean.FALSE.equals(fileInfo.getJsonObject(0).getBoolean(Field.ISFOLDER))) {
                            storeFileWorkspace(host, userSession, user, file, parentId).onComplete(fileInfos -> {
                                if (fileInfos.succeeded()) {
                                    promiseResult.complete(fileInfos.result());
                                }
                                else {
                                    promiseResult.fail(fileInfos.cause().getMessage());
                                }
                            });
                        } else {
                            folderCopy(host, fileInfo, file, parentId, user, userSession)
                                    .onSuccess(promiseResult::complete)
                                    .onFailure(err -> {
                                        String messageToFormat = "[Nextcloud@%s::copyToWorkspace] Error while handling folder copy : %s";
                                        PromiseHelper.reject(log, messageToFormat, this.getClass().getSimpleName(), err, promiseResult);
                                    });
                        }
                    } else {
                        promiseResult.fail("nextcloud.server.file.no.exist");
                    }

                })
                .onFailure(err -> {
                    String messageToFormat = "[Nextcloud@%s::copyToWorkspace] An error has occurred while retrieving data from file : %s";
                    PromiseHelper.reject(log, messageToFormat, this.getClass().getSimpleName(), err, promiseResult);
                });
        return promiseResult.future();

    }

    /** Move one document from Nextcloud to Workspace
     * @param host host
     * @param userSession       User session
     * @param user              User infos
     * @param file              Path of the file you want to move
     * @param parentId          Identifier of the previous folder if moving in a folder
     * @return                  Future Json with the infos about the move
     */
    private Future<JsonObject> moveToWorkspace(String host, UserNextcloud.TokenProvider userSession,
                                               UserInfos user,
                                               String file,
                                               String parentId) {
        Promise<JsonObject> promiseResult = Promise.promise();
        //The listFiles function here is called to gather data on one specific file.
        String decodedPath = StringHelper.decodeUrlForNc(file).replace(Field.ASCIISPACE, Field.PLUS_SIGN);
        listFiles(host, userSession, decodedPath)
                .onSuccess(fileInfo -> {
                    if (!fileInfo.isEmpty()) {
                        JsonObject resJson = fileInfo.getJsonObject(0);
                        if (!resJson.containsKey(Field.DISPLAYNAME) || resJson.getString(Field.DISPLAYNAME).equals("")
                                || !resJson.containsKey(Field.ISFOLDER)) {
                            String messageToFormat = "[Nextcloud@%s::moveToWorkspace] An error has occurred while retrieving data from file : %s";
                            PromiseHelper.reject(log, messageToFormat, this.getClass().getSimpleName(), new Exception("Missing data field in listFiles infos"), promiseResult);
                            return;
                        }
                        if (Boolean.FALSE.equals(fileInfo.getJsonObject(0).getBoolean(Field.ISFOLDER))) {
                            retrieveAndDeleteFile(host, userSession, user, file, parentId)
                                    .onComplete(fileInfos -> {
                                if (fileInfos.succeeded()) {
                                    promiseResult.complete(fileInfos.result());
                                } else {
                                    promiseResult.fail(fileInfos.cause().getMessage());
                                }
                            });
                        } else {
                            folderMove(host, fileInfo, file, parentId, user, userSession)
                                    .onSuccess(promiseResult::complete)
                                    .onFailure(err -> {
                                        String messageToFormat = "[Nextcloud@%s::moveToWorkspace] Error while handling folder move : %s";
                                        PromiseHelper.reject(log, messageToFormat, this.getClass().getSimpleName(), err, promiseResult);
                                    });
                        }
                    } else {
                        promiseResult.fail("nextcloud.server.file.no.exist");
                    }
                })
                .onFailure(err -> {
                    String messageToFormat = "[Nextcloud@%s::moveToWorkspace] An error has occurred while retrieving data from file : %s";
                    PromiseHelper.reject(log, messageToFormat, this.getClass().getSimpleName(), err, promiseResult);
                });
        return promiseResult.future();
    }



    /**
     * Copy a file from Nextcloud server to workspace and then delete it from nextcloud
     * If the storage succeed, delete the document from nextcloud, otherwise return a failed future.
     * @param host host
     * @param userSession       User session
     * @param user              User infos
     * @param filePath          Path of the file on nextcloud server
     * @param parentId          Identifier of the previous folder if moving in a folder
     * @return                  Future with the status of the action
     */
    private Future<JsonObject> retrieveAndDeleteFile(String host, UserNextcloud.TokenProvider userSession, UserInfos user, String filePath, String parentId) {
        Promise<JsonObject> promise = Promise.promise();
        Map<String, JsonObject> result = new HashMap<>();
        storeFileWorkspace(host, userSession, user, filePath, parentId)
                .compose(res -> {
                    result.put(Field.RESULT, res);
                    return deleteDocument(host, userSession, filePath);
                })
                .onSuccess(res -> promise.complete(result.get(Field.RESULT)))
                .onFailure(err -> {
                    String messageToFormat = "[Nextcloud@%s::retrieveAndDeleteFile] An error has occurred during retrieving" +
                            " and deleting document document(s) : %s";
                    PromiseHelper.reject(log, messageToFormat, this.getClass().getSimpleName(), err, promise);
                });
        return promise.future();
    }

    /**
     * Retrieve file from user's Nextcloud space and store it in his ENT workspace
     * @param host host
     * @param userSession       User session
     * @param user              User infos
     * @param filePath          Path of the file on nextcloud server
     * @param parentId          Identifier of the previous folder if moving in a folder
     * @return                  Future with the status of the action
     */
    private Future<JsonObject> storeFileWorkspace(String host, UserNextcloud.TokenProvider userSession, UserInfos user, String filePath, String parentId) {
        Promise<JsonObject> promise = Promise.promise();
        String[] splitPath = filePath.split("/");
        String fileName = splitPath[splitPath.length - 1];
        JsonObject[] fileInfos = new JsonObject[1];
        getFile(host, userSession, filePath)
                .compose(buffer ->
                        FileHelper.writeBuffer(storage, buffer.bodyAsBuffer(), buffer.headers().get(Field.CONTENT_TYPE_HEADER), fileName)
                )
                .compose(writeInfo -> {
                    writeInfo.put(Field.PARENTID, parentId);
                    return FileHelper.addFileReference(writeInfo, user, StringHelper.decodeUrlForNc(fileName), workspaceHelper);
                })
                .compose(resDoc -> {
                    fileInfos[0] = resDoc;
                    return moveUnderParent(workspaceHelper, user, parentId, resDoc);
                })
                .onSuccess(res -> promise.complete(fileInfos[0]))
                .onFailure(err -> {
                    String messageToFormat = "[Nextcloud@%s::storeFileWorkspace] Error while storing file : %s";
                    PromiseHelper.reject(log, messageToFormat, this.getClass().getSimpleName(), err, promise);
                });

        return promise.future();
    }

    /**
     * Check if the new file need to be moved under a parent folder and do it if the answer is yes
     * @param user          User infos
     * @param parentId      Identifier of the previous folder if moving in a folder
     * @return              Future with the status of the action
     */
    private Future<JsonObject> moveUnderParent(WorkspaceHelper workspaceHelper, UserInfos user, String parentId, JsonObject resDoc) {
        Promise<JsonObject> promise = Promise.promise();
        if (parentId != null) {
            if (!resDoc.containsKey(Field.UNDERSCORE_ID)) {
                String messageToFormat = "[Nextcloud@%s::moveUnderParent] An error has occurred during moving document(s) : %s";
                PromiseHelper.reject(log, messageToFormat, this.getClass().getSimpleName(), new Exception("Missing return ID"), promise);
                return promise.future();
            }
            moveDocumentUnderParent(workspaceHelper, resDoc.getString(Field.UNDERSCORE_ID), parentId, user)
                    .onSuccess(promise::complete)
                    .onFailure(res -> {
                        String messageToFormat = "[Nextcloud@%s::moveUnderParent] An error has occurred during moving document(s) : %s";
                        PromiseHelper.reject(log, messageToFormat, this.getClass().getSimpleName(), res, promise);
                    });
        } else {
            promise.complete(new JsonObject().put(Field.STATUS, resDoc.getString(Field.STATUS)));
        }
        return promise.future();
    }

    /**
     * Move a file under a folder in the workspace, the file and the folder need to exist
     * @param workspaceHelper   Help to manage properly workspace
     * @param id                File identifier
     * @param parentId          Parent folder Identifier
     * @param user              User infos
     * @return                  Future with the status of the move
     */
    private Future<JsonObject> moveDocumentUnderParent(WorkspaceHelper workspaceHelper, String id, String parentId, UserInfos user) {
        Promise<JsonObject> promise = Promise.promise();
        workspaceHelper.moveDocument(id, parentId, user, moveStatus -> {
                    if (moveStatus.failed()) {
                        String messageToFormat = "[Nextcloud@%s::moveDocumentUnderParent] Error while moving document under parent folder : %s";
                        PromiseHelper.reject(log, messageToFormat, FileHelper.class.getName(), new Exception("parent.folder.not.exist"), promise);
                    } else
                        promise.complete(new JsonObject().put(Field.STATUS, Field.OK));
                });
        return promise.future();
    }

    /**
     * upload file
     *  @param host host
     *  @param user              User session token
     *  @param file              Data about the file to upload
     *  @param path              Path where files will be uploaded on the nextcloud
     *  @param deleteFromStorage Delete file from local storage after upload
     */
    @Override
    public Future<JsonObject> uploadFile(String host, UserNextcloud.TokenProvider user, Attachment file, String path, Boolean deleteFromStorage) {
        //Final path on the nextcloud server
        String finalPath = (path != null ? path + "/" : "" ) + file.metadata().filename();
        Promise<JsonObject> promise = Promise.promise();
        this.getUniqueFileName(host, user, finalPath, 0) //check if the file currently exists on the nextcloud server
                .onSuccess(filePath -> {
                    final NextcloudConfig nextcloudConfig = this.nextcloudConfigMapByHost.get(host);
                    //Read the file on the vertx container, id is needed to locate it
                    storage.readFile(file.id(), res ->
                        this.client.putAbs(nextcloudConfig.host() + nextcloudConfig.webdavEndpoint() + "/" + user.userId() + "/" + StringHelper.encodeUrlForNc(filePath))
                                .basicAuthentication(user.userId(), user.token())
                                .as(BodyCodec.jsonObject())
                                .sendBuffer(res, responseAsync -> {
                                    if (responseAsync.failed()) {
                                        String messageToFormat = "[Nextcloud@%s::uploadFile] An error has occurred during uploading file : %s";
                                        PromiseHelper.reject(log, messageToFormat, this.getClass().getSimpleName(), responseAsync.cause(), promise);
                                    } else {
                                        promise.complete(new JsonObject()
                                                .put(Field.NAME, file.metadata().filename())
                                                .put(Field.STATUSCODE, responseAsync.result().statusCode()));
                                    }
                                }));
                    if (Boolean.TRUE.equals(deleteFromStorage))
                        storage.removeFile(file.id(), e -> {});
                })
                .onFailure(err -> {
                    promise.complete(new JsonObject().put(Field.ERROR, err));
                    storage.removeFile(file.id(), e -> {});
                });
        return promise.future();
    }

    /**
     * Copy all the documents listed in id idList from workspace to Nextcloud
     * @param host host
     * @param userSession   User session
     * @param user          User infos
     * @param idList        Identifier of all the documents to move
     * @param parentName    Name of the parent folder in nextcloud
     * @return              Future Json with all the status infos about the copy.
     */
    @Override
    public Future<JsonObject> copyDocumentsFromWorkspaceToNC(String host, UserNextcloud.TokenProvider userSession, UserInfos user, List<String> idList, String parentName) {
        Promise<JsonObject> promise = Promise.promise();
        JsonArray results = new JsonArray();
        Future<JsonObject> current = Future.succeededFuture();
        for (String id : idList) {
            current = current.compose(v -> completeResult(processDocumentCopy(host, userSession, user, id, parentName) ,id, results));
        }
        current.onSuccess(res -> {
                    promise.complete(new JsonObject().put(Field.DATA, results));
                })
                .onFailure(err -> {
                    String messageToFormat = "[Nextcloud@%s::copyDocumentsFromWorkspaceToNC] An error has occurred while copying documents : %s";
                    PromiseHelper.reject(log, messageToFormat, this.getClass().getSimpleName(), err, promise);
                });

        return promise.future();
    }

    /**
     * Complete result array with each status and data about document move / copy.
     * @param future    Future with the result of the specific move / copy
     * @param id        Identifier of the document.
     * @param results   Array with the result of all move / copy.
     * @return          Future JsonObject of the move / copy.
     */
    public Future<JsonObject> completeResult(Future<JsonObject> future, String id, JsonArray results) {
        Promise<JsonObject> promiseSucceed = Promise.promise();
        future
                .onSuccess(results::add)
                .onFailure(err -> {
                    results.add(new JsonObject()
                            .put(Field.ID, id)
                            .put(Field.STATUS, Field.KO_LOWER)
                            .put(Field.ERROR, err.getMessage()));
                })
                .onComplete(v -> promiseSucceed.complete());
        return promiseSucceed.future();
    }

    /**
     * Move all the documents listed in id idList from workspace to Nextcloud
     * @param host host
     * @param userSession   User session
     * @param user          User infos
     * @param idList        Identifier of all the documents to move
     * @param parentName    Name of the parent folder in nextcloud
     * @return              Future Json with all the status infos about the move.
     */
    @Override
    public Future<JsonObject> moveDocumentsFromWorkspaceToNC(String host, UserNextcloud.TokenProvider userSession, UserInfos user, List<String> idList, String parentName) {
        Promise<JsonObject> promise = Promise.promise();
        JsonArray results = new JsonArray();
        Future<JsonObject> current = Future.succeededFuture();
                    for (String id : idList) {
                        current = current.compose(v -> completeResult(processDocumentMove(host, userSession, user, id, parentName) ,id, results));
                    }
                    current.onSuccess(res -> {
                                promise.complete(new JsonObject().put(Field.DATA, results));
                            })
                            .onFailure(err -> {
                                String messageToFormat = "[Nextcloud@%s::moveDocumentsFromWorkspaceToNC] An error has occurred while moving documents : %s";
                                PromiseHelper.reject(log, messageToFormat, this.getClass().getSimpleName(), err, promise);
                            });
        return promise.future();
    }

    /**
     *  Handle document copy from workspace to nextcloud
     * @param host host
     * @param userSession   User session
     * @param user          User data
     * @param id            Identifier of the document
     * @param parentPath    The parent path in the nextcloud server
     * @return              Future with details about the copy
     */
    private Future<JsonObject> processDocumentCopy(String host, UserNextcloud.TokenProvider userSession, UserInfos user, String id, String parentPath) {
        Promise<JsonObject> promise = Promise.promise();
        JsonObject action = new JsonObject()
                .put(Field.ACTION, WorkspaceEventBusActions.GETDOCUMENT.action())
                .put(Field.ID, id);
        EventBusHelper.requestJsonObject(eventBus, action)
                .compose(document -> {
                    if (document.containsKey(Field.ETYPE) && document.getString(Field.ETYPE).equals(Field.FOLDER)) {
                        return processFolderCopy(host, userSession, user, document, parentPath);
                    } else {
                        return sendWorkspaceFileToNC(host, userSession, user, id, parentPath);
                    }
                })
                .onSuccess(promise::complete)
                .onFailure(err -> {
                    String messageToFormat = "[Nextcloud@%s::handleDocumentCopy] Error while copying document : %s";
                    PromiseHelper.reject(log, messageToFormat, FileHelper.class.getName(), err, promise);
                });
        return promise.future();
    }

    /**
     *  Handle document move from workspace to nextcloud
     * @param host host
     * @param userSession   User session
     * @param user          User data
     * @param id            Identifier of the document
     * @param parentPath    The parent path in the nextcloud server
     * @return              Future with details about the move
     */
    private Future<JsonObject> processDocumentMove(String host, UserNextcloud.TokenProvider userSession, UserInfos user, String id, String parentPath) {
        Promise<JsonObject> promise = Promise.promise();
        processDocumentCopy(host, userSession, user, id, parentPath)
                .onSuccess(res -> {
                    JsonObject delete =  new JsonObject()
                            .put(Field.ACTION, WorkspaceEventBusActions.DELETE.action())
                            .put(Field.ID, id)
                            .put(Field.USERID_CAPS, userSession.userId());
                    EventBusHelper.requestJsonArray(eventBus, delete);
                    promise.complete(res);
                })
                .onFailure(err -> {
                    String messageToFormat = "[Nextcloud@%s::handleDocumentMove] Error while moving document : %s";
                    PromiseHelper.reject(log, messageToFormat, FileHelper.class.getName(), err, promise);
                });
        return promise.future();
    }

    /**
     * Copy a folder from workspace to nextcloud
     *
     * @param host host
     * @param userSession User session
     * @param user        User data
     * @param document    Data about the moved document
     * @param parentPath  The parent path in the nextcloud server.
     * @return Future with the details of the copy
     */
    private Future<JsonObject> processFolderCopy(String host, UserNextcloud.TokenProvider userSession, UserInfos user, JsonObject document, String parentPath) {
        Promise<JsonObject> promise = Promise.promise();
        JsonObject folderData = new JsonObject();
        JsonObject action = new JsonObject()
                .put(Field.ACTION, WorkspaceEventBusActions.LIST.action())
                .put(Field.USERID_CAPS, userSession.userId())
                .put(Field.PARENTID, document.getString(Field.UNDERSCORE_ID));
        getUniqueFileName(host, userSession, (parentPath != null ? parentPath + "/" : "") + document.getString(Field.NAME), 0)
                .compose(path -> {
                    folderData.put(Field.PATH, path);
                    return createFolder(host, userSession, StringHelper.encodeUrlForNc(path.replace(Field.ASCIISPACE, " ")));
                })
                .compose(status -> EventBusHelper.requestJsonArray(eventBus, action))
                .compose(res ->
                        copyDocumentsFromWorkspaceToNC(host, userSession,
                                user,
                                res.stream().map(listItem -> ((JsonObject) listItem).getString(Field.UNDERSCORE_ID)).collect(Collectors.toList()),
                                folderData.getString(Field.PATH)))
                .onSuccess(res -> promise.complete(folderData
                        .put(Field.ETYPE, Field.FOLDER)
                        .put(Field.NAME, document.getString(Field.NAME))
                        .put(Field.STATUS, Field.OK)
                        .put(Field.DATA, res.getJsonArray(Field.DATA))))
                .onFailure(err -> {
                    String messageToFormat = "[Nextcloud@%s::handleFolderCopy] Error while handling folder creation : %s";
                    PromiseHelper.reject(log, messageToFormat, FileHelper.class.getName(), err, promise);
                });
        return promise.future();
    }

    /**
     * Recursively call nextcloud API to know if a file with the same name exists on nextcloud server, if the answer is yes,
     * call again this method with a number of copy after the initial name (e.g. name (1).txt).
     * @param host host
     * @param userSession       Session of the user.
     * @param path              The path of the file.
     * @param duplicateNumber   Number of previous call to this function.
     * @return                  A file name which is not already used on the nextcloud.
     */
    private Future<String> getUniqueFileName(String host, UserNextcloud.TokenProvider userSession, String path, int duplicateNumber) {
        Promise<String> promise = Promise.promise();
        if (path == null) {
            promise.complete(null);
            return promise.future();
        }
        String extension = "";
        String fileName = path;
        int i = path.lastIndexOf('.');
        if (i >= 0) {
            extension = path.substring(i);
            fileName = path.substring(0, i);
        }
        String finalPath = fileName + (duplicateNumber != 0 ? " (" + duplicateNumber + ")" : "") + extension;
        listFiles(host, userSession, finalPath)
                .onSuccess(filesData -> {
                    if (filesData.isEmpty())
                        promise.complete(finalPath);
                    else {
                        getUniqueFileName(host, userSession,  path, duplicateNumber + 1)
                                .onSuccess(promise::complete)
                                .onFailure(err -> {
                                    String messageToFormat = "[Nextcloud@%s::getUniqueFileName] Error while generating duplicate name : %s";
                                    PromiseHelper.reject(log, messageToFormat, this.getClass().getSimpleName(), err, promise);
                                });
                    }
                })
                .onFailure(err -> {
                    String messageToFormat = "[Nextcloud@%s::getUniqueFileName] Error while retrieving infos from Nextcloud : %s";
                    PromiseHelper.reject(log, messageToFormat, this.getClass().getSimpleName(), err, promise);
                });
        return promise.future();
    }

    /**
     * Retrieve a file from workspace and send it to Nextcloud.
     * @param host host
     * @param userSession   User session
     * @param id            Identifier of the file
     * @param parentName    Name of the parent folder in Nextcloud
     * @return              Future Json with result of the upload
     */
    private Future<JsonObject> sendWorkspaceFileToNC(String host, UserNextcloud.TokenProvider userSession, UserInfos user, String id, String parentName) {
        Promise<JsonObject> promise = Promise.promise();
        String finalPath = (parentName != null ? parentName + "/" : "" );

        workspaceHelper.readDocument(id, file -> {
            if (file != null) {
                String docName = file.getDocument().getString(Field.NAME);
                checkExtensionAllowed(docName, user.getStructures())
                        .onSuccess(allowed -> sendWorkspaceFileToNCAllowed(host, userSession, file, finalPath + docName, promise))
                        .onFailure(promise::fail);
            } else {
                String messageToFormat = "[Nextcloud@%s::sendWorkspaceFileToNC] An error has occurred during uploading file : %s";
                PromiseHelper.reject(log, messageToFormat, this.getClass().getSimpleName(), new Exception("file.not.found"), promise);
            }
        });

        return promise.future();
    }

    private void sendWorkspaceFileToNCAllowed(String host, UserNextcloud.TokenProvider userSession, WorkspaceHelper.Document file, String path, Promise<JsonObject> promise) {
        getUniqueFileName(host, userSession, StringHelper.encodeUrlForNc(path), 0)
                .onSuccess(name -> {
                    final NextcloudConfig nextcloudConfig = this.nextcloudConfigMapByHost.get(host);
                    this.client.putAbs(nextcloudConfig.host() + nextcloudConfig.webdavEndpoint() + "/" + userSession.userId() + "/" +
                                    name)
                            .basicAuthentication(userSession.userId(), userSession.token())
                            .sendBuffer(file.getData(), responseAsync -> {
                                if (responseAsync.failed()) {
                                    String messageToFormat = "[Nextcloud@%s::sendWorkspaceFileToNC] An error has occurred during uploading file : %s";
                                    PromiseHelper.reject(log, messageToFormat, this.getClass().getSimpleName(), responseAsync.cause(), promise);
                                } else {
                                    promise.complete(file.getDocument());
                                }
                            });
                })
                .onFailure(err -> {
                    String messageToFormat = "[Nextcloud@%s::sendWorkspaceFileToNC] Error while generating duplicate name : %s";
                    PromiseHelper.reject(log, messageToFormat, this.getClass().getSimpleName(), err, promise);
                });
    }

    /**
     * Create a new folder in the Nextcloud space
     * @param host host
     * @param userSession   User session
     * @param path          Path of the new folder in Nextcloud
     * @return              Future JsonObject with the status of the creation
     */
    public Future<JsonObject> createFolderNextcloud(String host, UserNextcloud.TokenProvider userSession, String path) {
        return createFolder(host, userSession, StringHelper.encodeUrlForNc(path.replace(Field.ASCIISPACE, " ")));
    }

    @Override
    public Future<JsonObject> createDocumentFromTemplate(String host, UserNextcloud.TokenProvider userSession, String type, String name, String path) {
        Promise<JsonObject> promise = Promise.promise();
        String templatePath = fr.wseduc.webutils.data.FileResolver.absolutePath("public/nextcloud-templates/template." + type);
        String filename = name + "." + type;
        String contentType;
        try {
            contentType = Files.probeContentType(java.nio.file.Paths.get(templatePath));
        } catch (IOException e) {
            log.error("[Nextcloud@createDocumentFromTemplate] Failed to read content type for type " + type, e);
            promise.fail(e);
            return promise.future();
        }
        this.vertx.fileSystem().readFile(templatePath, readEvent -> {
            if (readEvent.failed()) {
                log.error("[Nextcloud@createDocumentFromTemplate] Failed to read template file " + templatePath, readEvent.cause());
                promise.fail(readEvent.cause());
                return;
            }
            storage.writeBuffer(readEvent.result(), contentType, filename, storageResult -> {
                if (!"ok".equals(storageResult.getString(Field.STATUS))) {
                    promise.fail(storageResult.getString(Field.MESSAGE, "storage.write.failed"));
                    return;
                }
                Attachment attachment = new Attachment(storageResult.getString(Field._ID), new Metadata(storageResult.getJsonObject("metadata")));
                this.uploadFile(host, userSession, attachment, path, true)
                        .onSuccess(uploadResult -> promise.complete(new JsonObject()
                                .put(Field.NAME, filename)
                                .put(Field.PATH, (path != null && !path.isEmpty() ? path + "/" : "") + filename)))
                        .onFailure(promise::fail);
            });
        });
        return promise.future();
    }

}
