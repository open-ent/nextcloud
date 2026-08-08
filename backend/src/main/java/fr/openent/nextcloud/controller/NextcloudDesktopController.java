package fr.openent.nextcloud.controller;

import fr.openent.nextcloud.core.constants.Field;
import fr.openent.nextcloud.core.constants.WorkflowRight;
import fr.openent.nextcloud.helper.DesktopConfigHelper;
import fr.openent.nextcloud.security.AdminDesktop;
import fr.openent.nextcloud.service.ServiceFactory;
import fr.wseduc.rs.ApiDoc;
import fr.wseduc.rs.Get;
import fr.wseduc.rs.Post;
import fr.wseduc.rs.Put;
import fr.wseduc.mongodb.MongoDb;
import fr.wseduc.security.ActionType;
import fr.wseduc.security.SecuredAction;
import fr.wseduc.webutils.http.Renders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;
import org.entcore.common.controller.ControllerHelper;
import org.entcore.common.http.filter.ResourceFilter;
import org.entcore.common.mongodb.MongoDbResult;
import org.entcore.common.user.UserUtils;

public class NextcloudDesktopController extends ControllerHelper {

    private final ServiceFactory serviceFactory;

    public NextcloudDesktopController(ServiceFactory serviceFactory) {
        this.serviceFactory = serviceFactory;
    }

    @Get("/desktop")
    @ApiDoc("Render admin console view")
    @SecuredAction(WorkflowRight.ADMIN_DESKTOP)
    public void view(HttpServerRequest request) {
        renderView(request, new JsonObject(), "index.html", null);
    }

    @Get("/desktop/config")
    @ApiDoc("Returns the requested Nextcloud custom Desktop configuration")
    @ResourceFilter(AdminDesktop.class)
    @SecuredAction(value="", type=ActionType.RESOURCE)
    public void getConfig(HttpServerRequest request) {
        JsonObject query = new JsonObject().put(Field._ID, Field.UNIQUEID);

        serviceFactory.mongoDb().findOne(Field.CONFIG, query, MongoDbResult.validResultHandler(event -> {
            if (event.isLeft()) {
                Renders.renderError(request, new JsonObject().put("error", "Failed to retrieve configuration"));
                return;
            }

            JsonObject config = event.right().getValue();
            boolean noConfigYet = (config == null || config.isEmpty());
            if (noConfigYet) {
                // Première visite de l'écran : rien n'est encore enregistré, mais on propose
                // d'office des valeurs de départ raisonnables plutôt que des champs vides
                // (cf. fr.openent.nextcloud.core.constants.Field#DEFAULT_*).
                Renders.renderJson(request, new JsonObject()
                        .put(Field.SYNCFOLDER, Field.DEFAULT_SYNC_FOLDER_PREFIX)
                        .put(Field.DOWNLOADLIMIT, Field.DEFAULT_BANDWIDTH_LIMIT)
                        .put(Field.UPLOADLIMIT, Field.DEFAULT_BANDWIDTH_LIMIT)
                        .put(Field.EXCLUDEDEXTENSIONS, new JsonArray(Field.DEFAULT_EXCLUDED_EXTENSIONS)));
                return;
            }

            // Un admin qui a délibérément vidé la liste (tableau [] enregistré) doit le rester :
            // on ne réinjecte les valeurs par défaut que si la clé n'a jamais été enregistrée.
            if (!config.containsKey(Field.EXCLUDEDEXTENSIONS)) {
                config.put(Field.EXCLUDEDEXTENSIONS, new JsonArray(Field.DEFAULT_EXCLUDED_EXTENSIONS));
            }

            if (!isValidConfig(config)) {
                Renders.renderJson(request, new JsonObject().put("error", "Invalid configuration"), 500);
                return;
            }
            Renders.renderJson(request, config);
        }));
    }

    @Put("/desktop/config")
    @ApiDoc("Updates the Nextcloud custom Desktop configuration")
    @SecuredAction(value="", type=ActionType.RESOURCE)
    @ResourceFilter(AdminDesktop.class)
    public void putConfig(HttpServerRequest request) {

        request.bodyHandler(body -> {
            JsonObject config;
            try {
                config = body.toJsonObject(); 
            } catch (Exception e) {
                Renders.renderJson(request, new JsonObject().put("error", "Invalid JSON format"), 400);
                return;
            }
            
            if (!isValidConfig(config)) {
                Renders.renderJson(request, new JsonObject().put("error", "Invalid configuration"), 400);
                return;
            }

            JsonObject query = new JsonObject().put(Field._ID, Field.UNIQUEID);
            config.remove(Field._ID);
            JsonObject update = new JsonObject().put("$set", config);
            // true is for upsert, false is for multi
            serviceFactory.mongoDb().update(Field.CONFIG, query, update, true, false, MongoDbResult.validResultHandler(event -> {
                if (event.isLeft()) {
                    Renders.renderError(request, new JsonObject().put("error", "Failed to save configuration"));
                    return;
                }

                Renders.renderJson(request, new JsonObject().put("message", "Configuration saved"));
            }));
        });
    }

    @Get("/desktop/my-structures")
    @ApiDoc("Renvoie les établissements de l'utilisateur connecté, pour le sélecteur de l'écran desktop")
    @ResourceFilter(AdminDesktop.class)
    @SecuredAction(value="", type=ActionType.RESOURCE)
    public void getMyStructures(HttpServerRequest request) {
        UserUtils.getUserInfos(eb, request, user -> {
            java.util.List<String> myStructures = user.getStructures();
            if (myStructures == null || myStructures.isEmpty()) {
                Renders.renderJson(request, new JsonObject().put("isAdmc", user.isADMC()).put("structures", new JsonArray()));
                return;
            }

            String cypher = "MATCH (s:Structure) WHERE s.id IN {ids} RETURN s.id as id, s.name as name, s.UAI as UAI ORDER BY s.name";
            JsonObject params = new JsonObject().put("ids", new JsonArray(new java.util.ArrayList<>(myStructures)));
            serviceFactory.neo4j().execute(cypher, params, org.entcore.common.neo4j.Neo4jResult.validResultHandler(event -> {
                if (event.isLeft()) {
                    Renders.renderError(request, new JsonObject().put("error", "Failed to resolve your structures"));
                    return;
                }
                Renders.renderJson(request, new JsonObject().put("isAdmc", user.isADMC()).put("structures", event.right().getValue()));
            }));
        });
    }

    @Get("/desktop/config/structure/:structureid")
    @ApiDoc("Returns the effective (national + local override) Desktop configuration for a structure")
    @ResourceFilter(AdminDesktop.class)
    @SecuredAction(value="", type=ActionType.RESOURCE)
    public void getStructureConfig(HttpServerRequest request) {
        String structureId = request.getParam(Field.STRUCTUREID_PARAM);
        UserUtils.getUserInfos(eb, request, user -> {
            if (!user.isADMC() && (user.getStructures() == null || !user.getStructures().contains(structureId))) {
                Renders.renderJson(request, new JsonObject().put("error", "Forbidden: not your structure"), 403);
                return;
            }

            String cypher = "MATCH (s:Structure {id: {structureId}}) RETURN s.UAI as UAI, s.name as name LIMIT 1";
            JsonObject params = new JsonObject().put("structureId", structureId);
            serviceFactory.neo4j().execute(cypher, params, org.entcore.common.neo4j.Neo4jResult.validResultHandler(neo4jEvent -> {
                if (neo4jEvent.isLeft()) {
                    Renders.renderError(request, new JsonObject().put("error", "Failed to resolve structure"));
                    return;
                }
                JsonArray results = neo4jEvent.right().getValue();
                String uai = !results.isEmpty() ? results.getJsonObject(0).getString("UAI") : null;
                String name = !results.isEmpty() ? results.getJsonObject(0).getString("name") : null;

                DesktopConfigHelper.getEffectiveConfigForStructure(serviceFactory.mongoDb(), structureId, uai, name)
                        .onSuccess(config -> Renders.renderJson(request, config))
                        .onFailure(err -> Renders.renderError(request, new JsonObject().put("error", "Failed to retrieve configuration")));
            }));
        });
    }

    @Put("/desktop/config/structure/:structureid")
    @ApiDoc("Saves a local override (syncFolder/excludedExtensions) for a structure")
    @ResourceFilter(AdminDesktop.class)
    @SecuredAction(value="", type=ActionType.RESOURCE)
    public void putStructureConfig(HttpServerRequest request) {
        String structureId = request.getParam(Field.STRUCTUREID_PARAM);
        UserUtils.getUserInfos(eb, request, user -> {
            if (!user.isADMC() && (user.getStructures() == null || !user.getStructures().contains(structureId))) {
                Renders.renderJson(request, new JsonObject().put("error", "Forbidden: not your structure"), 403);
                return;
            }

            request.bodyHandler(body -> {
                JsonObject config;
                try {
                    config = body.toJsonObject();
                } catch (Exception e) {
                    Renders.renderJson(request, new JsonObject().put("error", "Invalid JSON format"), 400);
                    return;
                }

                if (!isValidStructureConfig(config)) {
                    Renders.renderJson(request, new JsonObject().put("error", "Invalid configuration"), 400);
                    return;
                }

                config.remove(Field._ID);
                JsonObject query = new JsonObject().put(Field._ID, structureId);
                JsonObject update = new JsonObject().put("$set", config);
                serviceFactory.mongoDb().update(Field.CONFIG, query, update, true, false, MongoDbResult.validResultHandler(event -> {
                    if (event.isLeft()) {
                        Renders.renderError(request, new JsonObject().put("error", "Failed to save configuration"));
                        return;
                    }
                    Renders.renderJson(request, new JsonObject().put("message", "Configuration saved"));
                }));
            });
        });
    }

    // Contrairement au national (isValidConfig), une surcharge d'établissement est partielle :
    // seuls les champs présents sont validés, aucun n'est obligatoire (chaque établissement
    // peut surcharger dossier/extensions/bande passante indépendamment, selon sa propre
    // infrastructure — même précédence école > national > défaut pour les trois).
    private boolean isValidStructureConfig(JsonObject config) {
        if (config.containsKey(Field.SYNCFOLDER) &&
                (!(config.getValue(Field.SYNCFOLDER) instanceof String) || config.getString(Field.SYNCFOLDER).isEmpty())) {
            return false;
        }
        if (config.containsKey(Field.DOWNLOADLIMIT) &&
                (!(config.getValue(Field.DOWNLOADLIMIT) instanceof Integer) || config.getInteger(Field.DOWNLOADLIMIT) <= 0)) {
            return false;
        }
        if (config.containsKey(Field.UPLOADLIMIT) &&
                (!(config.getValue(Field.UPLOADLIMIT) instanceof Integer) || config.getInteger(Field.UPLOADLIMIT) <= 0)) {
            return false;
        }
        if (config.containsKey(Field.EXCLUDEDEXTENSIONS)) {
            if (!(config.getValue(Field.EXCLUDEDEXTENSIONS) instanceof JsonArray)) {
                return false;
            }
            for (Object extension : config.getJsonArray(Field.EXCLUDEDEXTENSIONS)) {
                if (!(extension instanceof String)) {
                    return false;
                }
            }
        }
        return true;
    }

    @Post("/desktop/stat")
    @ApiDoc("Add stat from desktop client")
    @SecuredAction(value="", type=ActionType.RESOURCE)
    @ResourceFilter(AdminDesktop.class)
    public void postStat(HttpServerRequest request) {
        request.bodyHandler(body -> {
            JsonObject stat;
            try {
                stat = body.toJsonObject();
            } catch (Exception e) {
                Renders.renderJson(request, new JsonObject().put("error", "Invalid JSON format"), 400);
                return;
            }

            if (!stat.containsKey("category") || !stat.containsKey("user") ) {
                Renders.renderJson(request, new JsonObject().put("error", "Invalid configuration"), 400);
                return;
            }

            JsonObject query = stat;
            query.put("created", MongoDb.now());

            // true is for upsert, false is for multi
            serviceFactory.mongoDb().insert(Field.STAT_COLLECTION, query, MongoDbResult.validResultHandler(event -> {
                if (event.isLeft()) {
                    Renders.renderError(request, new JsonObject().put("error", "Failed to save stat"));
                    return;
                }

                Renders.renderJson(request, new JsonObject().put("message", "Stat saved"));
            }));
        });
    }

    private boolean isValidConfig(JsonObject config) {
        if (!config.containsKey(Field.DOWNLOADLIMIT) || !(config.getValue(Field.DOWNLOADLIMIT) instanceof Integer) || config.getInteger(Field.DOWNLOADLIMIT) <= 0) {
            return false;
        }
        if (!config.containsKey(Field.UPLOADLIMIT) || !(config.getValue(Field.UPLOADLIMIT) instanceof Integer) || config.getInteger(Field.UPLOADLIMIT) <= 0) {
            return false;
        }
        if (!config.containsKey(Field.SYNCFOLDER) || !(config.getValue(Field.SYNCFOLDER) instanceof String) || config.getString(Field.SYNCFOLDER).isEmpty()) {
            return false;
        }
        if (!config.containsKey(Field.EXCLUDEDEXTENSIONS) || !(config.getValue(Field.EXCLUDEDEXTENSIONS) instanceof JsonArray)) {
            return false;
        }
        for (Object extension : config.getJsonArray(Field.EXCLUDEDEXTENSIONS)) {
            if (!(extension instanceof String)) {
                return false;
            }
        }
        return true;
    }
}
