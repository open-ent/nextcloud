package fr.openent.nextcloud.controller;

import fr.openent.nextcloud.core.constants.Field;
import fr.openent.nextcloud.core.constants.WorkflowRight;
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
            if (config == null || config.isEmpty()) {
                Renders.renderJson(request, new JsonObject().put("error", "Configuration not found"), 404);
                return;
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
