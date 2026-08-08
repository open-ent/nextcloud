package fr.openent.nextcloud.helper;

import fr.openent.nextcloud.core.constants.Field;
import fr.wseduc.mongodb.MongoDb;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.entcore.common.mongodb.MongoDbResult;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Résolution de la configuration du client desktop Nextcloud (dossier synchronisé, extensions
 * bloquées), stockée dans la collection Field.CONFIG :
 * - un document national, _id = Field.UNIQUEID, réglé par un super-admin ;
 * - un document optionnel par établissement, _id = <structureId>, réglé par un admin local,
 *   qui ne contient que les champs explicitement surchargés.
 *
 * Précédence de résolution d'un champ : surcharge locale (établissement) > réglage national
 * > valeur par défaut codée en dur (Field.DEFAULT_EXCLUDED_EXTENSIONS / DEFAULT_SYNC_FOLDER).
 */
public class DesktopConfigHelper {

    private DesktopConfigHelper() {}

    public static Future<JsonObject> findConfig(MongoDb mongoDb, String id) {
        Promise<JsonObject> promise = Promise.promise();
        JsonObject query = new JsonObject().put(Field._ID, id);
        mongoDb.findOne(Field.CONFIG, query, MongoDbResult.validResultHandler(event -> {
            JsonObject config = event.isRight() ? event.right().getValue() : null;
            promise.complete(config == null || config.isEmpty() ? null : config);
        }));
        return promise.future();
    }

    /**
     * Extensions bloquées effectives pour un utilisateur, selon la précédence
     * local (1er établissement de la liste) > national > valeurs par défaut.
     */
    public static Future<List<String>> getExcludedExtensions(MongoDb mongoDb, List<String> userStructures) {
        String structureId = (userStructures != null && !userStructures.isEmpty()) ? userStructures.get(0) : null;

        Future<JsonObject> localConfig = structureId != null ? findConfig(mongoDb, structureId) : Future.succeededFuture(null);
        Future<JsonObject> nationalConfig = findConfig(mongoDb, Field.UNIQUEID);

        return Future.all(localConfig, nationalConfig).map(results -> {
            JsonObject local = results.resultAt(0);
            JsonObject national = results.resultAt(1);
            if (local != null && local.containsKey(Field.EXCLUDEDEXTENSIONS)) {
                return toStringList(local.getJsonArray(Field.EXCLUDEDEXTENSIONS));
            }
            if (national != null && national.containsKey(Field.EXCLUDEDEXTENSIONS)) {
                return toStringList(national.getJsonArray(Field.EXCLUDEDEXTENSIONS));
            }
            return Field.DEFAULT_EXCLUDED_EXTENSIONS;
        });
    }

    /**
     * Nom du dossier synchronisé effectif pour un utilisateur (précédence locale > national >
     * préfixe par défaut + UAI/nom), utilisé pour la création automatique du dossier côté
     * serveur Nextcloud à la première connexion (cf. DefaultDocumentsService#listFiles).
     */
    public static Future<String> getSyncFolderName(MongoDb mongoDb, List<String> userStructures,
                                                     String structureUai, String structureName) {
        String structureId = (userStructures != null && !userStructures.isEmpty()) ? userStructures.get(0) : null;

        Future<JsonObject> localConfig = structureId != null ? findConfig(mongoDb, structureId) : Future.succeededFuture(null);
        Future<JsonObject> nationalConfig = findConfig(mongoDb, Field.UNIQUEID);

        return Future.all(localConfig, nationalConfig).map(results -> {
            JsonObject local = results.resultAt(0);
            JsonObject national = results.resultAt(1);
            if (local != null && local.containsKey(Field.SYNCFOLDER)) {
                return local.getString(Field.SYNCFOLDER);
            }
            String prefix = national != null && national.containsKey(Field.SYNCFOLDER)
                    ? national.getString(Field.SYNCFOLDER) : Field.DEFAULT_SYNC_FOLDER_PREFIX;
            return prefix + structureSuffix(structureUai, structureName);
        });
    }

    /**
     * Configuration effective pour l'écran d'un établissement : fusion national + surcharge
     * locale (la locale gagne), avec les valeurs par défaut en dernier recours. Le champ
     * "overrides" indique, pour chaque réglage, si la valeur affichée vient d'une surcharge
     * locale (true) ou est héritée du national/défaut (false) — pour l'affichage côté admin.
     *
     * Cas particulier du dossier synchronisé : le national/défaut est un PRÉFIXE
     * ("ENT_PARTAGE_UAI_" par défaut) — tant qu'aucune surcharge locale n'existe, la valeur
     * proposée est ce préfixe suivi de l'UAI de l'établissement (ou, à défaut d'UAI, de son
     * nom en majuscules avec underscores).
     */
    public static Future<JsonObject> getEffectiveConfigForStructure(MongoDb mongoDb, String structureId,
                                                                     String structureUai, String structureName) {
        Future<JsonObject> localConfig = findConfig(mongoDb, structureId);
        Future<JsonObject> nationalConfig = findConfig(mongoDb, Field.UNIQUEID);

        return Future.all(localConfig, nationalConfig).map(results -> {
            JsonObject local = results.resultAt(0);
            JsonObject national = results.resultAt(1);
            JsonObject effective = new JsonObject();

            boolean syncFolderOverridden = local != null && local.containsKey(Field.SYNCFOLDER);
            boolean extensionsOverridden = local != null && local.containsKey(Field.EXCLUDEDEXTENSIONS);
            boolean downloadOverridden = local != null && local.containsKey(Field.DOWNLOADLIMIT);
            boolean uploadOverridden = local != null && local.containsKey(Field.UPLOADLIMIT);

            String syncFolder;
            if (syncFolderOverridden) {
                syncFolder = local.getString(Field.SYNCFOLDER);
            } else {
                String prefix = national != null && national.containsKey(Field.SYNCFOLDER)
                        ? national.getString(Field.SYNCFOLDER) : Field.DEFAULT_SYNC_FOLDER_PREFIX;
                syncFolder = prefix + structureSuffix(structureUai, structureName);
            }
            effective.put(Field.SYNCFOLDER, syncFolder);
            effective.put(Field.EXCLUDEDEXTENSIONS,
                    extensionsOverridden ? local.getJsonArray(Field.EXCLUDEDEXTENSIONS)
                            : national != null && national.containsKey(Field.EXCLUDEDEXTENSIONS) ? national.getJsonArray(Field.EXCLUDEDEXTENSIONS)
                            : new JsonArray(Field.DEFAULT_EXCLUDED_EXTENSIONS));
            effective.put(Field.DOWNLOADLIMIT,
                    downloadOverridden ? local.getInteger(Field.DOWNLOADLIMIT)
                            : national != null && national.containsKey(Field.DOWNLOADLIMIT) ? national.getInteger(Field.DOWNLOADLIMIT)
                            : Field.DEFAULT_BANDWIDTH_LIMIT);
            effective.put(Field.UPLOADLIMIT,
                    uploadOverridden ? local.getInteger(Field.UPLOADLIMIT)
                            : national != null && national.containsKey(Field.UPLOADLIMIT) ? national.getInteger(Field.UPLOADLIMIT)
                            : Field.DEFAULT_BANDWIDTH_LIMIT);
            effective.put("overrides", new JsonObject()
                    .put(Field.SYNCFOLDER, syncFolderOverridden)
                    .put(Field.EXCLUDEDEXTENSIONS, extensionsOverridden)
                    .put(Field.DOWNLOADLIMIT, downloadOverridden)
                    .put(Field.UPLOADLIMIT, uploadOverridden));
            return effective;
        });
    }

    // UAI en majuscules si connu ; sinon le nom de l'entité (ex. une académie sans UAI) en
    // majuscules, espaces/tirets/caractères non alphanumériques remplacés par des underscores.
    private static String structureSuffix(String uai, String name) {
        if (uai != null && !uai.trim().isEmpty()) {
            return uai.trim().toUpperCase();
        }
        if (name == null) {
            return "";
        }
        return name.trim().toUpperCase().replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    private static List<String> toStringList(JsonArray array) {
        return array.stream().map(Object::toString).collect(Collectors.toList());
    }
}
