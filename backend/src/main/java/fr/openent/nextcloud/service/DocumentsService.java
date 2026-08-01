package fr.openent.nextcloud.service;

import fr.openent.nextcloud.helper.Attachment;
import fr.openent.nextcloud.model.UserNextcloud;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import org.entcore.common.user.UserInfos;

import java.util.List;

public interface DocumentsService {

    /**
     * List files/folder
     *
     * @param host host
     * @param userSession   User Session {@link UserNextcloud.TokenProvider}
     * @param path          path of nextcloud's user
     * @return  Future List of folder/list {@link JsonArray}
     */
    Future<JsonArray> listFiles(String host, UserNextcloud.TokenProvider userSession, String path);

    /**
     * Récupère une URL d'édition bureautique en ligne (OnlyOffice) pour un fichier, via l'API
     * « Direct Editing » du cœur de NextCloud. L'appel est authentifié avec le token per-user
     * que le connecteur détient déjà (aucune session NextCloud n'est demandée à l'utilisateur).
     *
     * @param host        host
     * @param userSession session utilisateur {@link UserNextcloud.TokenProvider}
     * @param path        chemin NextCloud du fichier à éditer (relatif à la racine de l'utilisateur)
     * @return Future contenant {@code {"url": "..."}} vers l'éditeur en ligne
     */
    Future<JsonObject> getEditUrl(String host, UserNextcloud.TokenProvider userSession, String path);

    /**
     * Call the list API with specified handler
     *
     * @param host host
     * @param userSession   User Session {@link UserNextcloud.TokenProvider}
     * @param path          path of nextcloud's user
     * @param handler       Specified handler
     * @return
     */
    void parameterizedListFiles(String host, UserNextcloud.TokenProvider userSession, String path, Handler<AsyncResult<HttpResponse<String>>> handler);

    /**
     * get/download file
     *
     * @param host host
     * @param userSession   User Session {@link UserNextcloud.TokenProvider}
     * @param path          path of nextcloud's user
     * @return  Future containing Buffer of file {@link Buffer}
     */
    Future<HttpResponse<Buffer>> getFile(String host, UserNextcloud.TokenProvider userSession, String path);

    /**
     * get/download folder
     *
     * @param host host
     * @param userSession   User Session {@link UserNextcloud.TokenProvider}
     * @param path          folder path of nextcloud's user
     * @return  Future containing Buffer of folder {@link Buffer}
     */
    Future<HttpResponse<Buffer>> getFolder(String host, UserNextcloud.TokenProvider userSession, String path);

    /**
     * download multiple files (.zip given)
     *
     * @param host host
     * @param userSession       User Session {@link UserNextcloud.TokenProvider}
     * @param path              path of nextcloud's user
     * @param files             List of wanted file to download
     * @return  Future containing Buffer of file {@link Buffer}
     */
    Future<HttpResponse<Buffer>> getFiles(String host, UserNextcloud.TokenProvider userSession, String path, List<String> files);

    /**
     * Move Document
     *
     * @param host host
     * @param userSession       User Session {@link UserNextcloud.TokenProvider}
     * @param path              path of nextcloud's user wanted to move
     * @param destPath          destination path
     * @return  Future JsonObject response of move document
     */
    Future<JsonObject> moveDocument(String host, UserNextcloud.TokenProvider userSession, String path, String destPath);

    /**
     * method that list files in trash
     * 
     * @param host        host
     * @param userSession User Session {@link UserNextcloud.TokenProvider}
     */
    Future<JsonArray> listTrash(String host, UserNextcloud.TokenProvider userSession);

    /**
     * method that delete trash
     * @param host host
     * @param   userSession     User Session {@link UserNextcloud.TokenProvider}
     */
    Future<Void> deleteTrash(String host, UserNextcloud.TokenProvider userSession);


        /**
         * delete documents
         *
         * @param host host
         * @param userSession   User Session {@link UserNextcloud.TokenProvider}
         * @param paths         list of paths / documents to delete
         */
    Future<JsonObject> deleteDocuments(String host, UserNextcloud.TokenProvider userSession, List<String> paths);

    /**
     * delete documents from trashbin
     * 
     * @param host        host
     * @param userSession User Session {@link UserNextcloud.TokenProvider}
     * @param paths       list of paths / documents to delete from trashbin
     * @return
     */
    Future<JsonObject> deleteDocumentsFromTrashbin(String host, UserNextcloud.TokenProvider userSession, List<String> paths);

    /**
     * Restore documents from trash
     * 
     * @param host        host
     * @param userSession User Session {@link UserNextcloud.TokenProvider}
     * @param paths       list of paths / documents to restore
     * @return
     */
    Future<Void> restoreDocuments(String host, UserNextcloud.TokenProvider userSession, List<String> paths);

    /**
     * upload file
     *  @param host host
     *  @param user              User session token
     *  @param file              Data about the file to upload
     *  @param path              Path where files will be uploaded on the nextcloud
     *  @param deleteFromStorage Delete file from local storage
     */
    Future<JsonObject> uploadFile(String host, UserNextcloud.TokenProvider user, Attachment file, String path,  Boolean deleteFromStorage);

    /**
     * upload files
     *  @param host host
     *  @param userSession      User session
     *  @param files            List of files to upload
     *  @param path             Final path on Nextcloud
     *  @return                 Future of uploaded files
     */
    Future<JsonArray> uploadFiles(String host, UserNextcloud.TokenProvider userSession, List<Attachment> files, String path);

    Future<JsonArray> uploadStreamedMultipleFiles(String headerCount, HttpServerRequest request, UserNextcloud.TokenProvider user, Vertx vertx);

    /**
     * Copy all the files listed in the filesPath from nextcloud to local.
     * @param host host
     * @param userSession       User session
     * @param user              User infos
     * @param filesPath         Path of all the files to move
     * @param parentId          Identifier of the previous folder if moving in a folder
     * @return                  Future list of JsonObject with infos about every file copied
     */
    Future<List<JsonObject>> copyDocumentToWorkspace(String host, UserNextcloud.TokenProvider userSession, UserInfos user, List<String> filesPath, String parentId);

    /**
     * Move all the files listed in the filesPath from nextcloud to local.
     * @param host host
     * @param userSession       User session
     * @param user              User infos
     * @param filesPath         Path of all the files to move
     * @param parentId          Identifier of the previous folder if moving in a folder
     * @return                  Future list of JsonObject with infos about every file moved
     */
    Future<List<JsonObject>> moveDocumentToWorkspace(String host, UserNextcloud.TokenProvider userSession, UserInfos user, List<String> filesPath, String parentId);

    /**
     * Move all the documents listed in id idList from workspace to Nextcloud
     * @param host host
     * @param userSession   User session
     * @param user          User infos
     * @param idList        Identifier of all the documents to move
     * @param parentName    Name of the parent folder in nextcloud
     * @return              Future Json with all the status infos about the move.
     */
    Future<JsonObject> moveDocumentsFromWorkspaceToNC(String host, UserNextcloud.TokenProvider userSession, UserInfos user, List<String> idList, String parentName);

    /**
     * Copy all the documents listed in id idList from workspace to Nextcloud
     * @param host host
     * @param userSession   User session
     * @param user          User infos
     * @param idList        Identifier of all the documents to move
     * @param parentName    Name of the parent folder in nextcloud
     * @return              Future Json with all the status infos about the copy.
     */
    Future<JsonObject> copyDocumentsFromWorkspaceToNC(String host, UserNextcloud.TokenProvider userSession, UserInfos user, List<String> idList, String parentName);

    /**
     * Create a new folder in the Nextcloud space
     * @param host host
     * @param userSession   User session
     * @param path          Path of the new folder in Nextcloud
     * @return              Future JsonObject with the status of the creation
     */
    Future<JsonObject> createFolderNextcloud(String host, UserNextcloud.TokenProvider userSession, String path);

}
