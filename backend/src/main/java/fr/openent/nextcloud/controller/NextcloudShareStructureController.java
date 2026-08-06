package fr.openent.nextcloud.controller;

import fr.openent.nextcloud.core.constants.Field;
import fr.openent.nextcloud.core.constants.WorkflowRight;
import fr.openent.nextcloud.security.AdminShareStructures;
import fr.openent.nextcloud.service.ServiceFactory;
import fr.wseduc.rs.ApiDoc;
import fr.wseduc.rs.Delete;
import fr.wseduc.rs.Get;
import fr.wseduc.rs.Post;
import fr.wseduc.security.ActionType;
import fr.wseduc.security.SecuredAction;
import fr.wseduc.webutils.http.Renders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.entcore.common.controller.ControllerHelper;
import org.entcore.common.http.filter.ResourceFilter;
import org.entcore.common.mongodb.MongoDbResult;
import org.entcore.common.neo4j.Neo4jResult;
import org.entcore.common.user.UserInfos;
import org.entcore.common.user.UserUtils;
import org.entcore.common.utils.StringUtils;
import org.entcore.common.validation.StringValidation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Partage NextCloud inter-établissements : liste, propre au connecteur, des paires de structures
 * autorisées à se voir dans le picker de partage (indépendant du modèle de communication générique
 * d'entcore, pour ne pas impacter la messagerie ni les autres usages de la visibilité).
 */
public class NextcloudShareStructureController extends ControllerHelper {

    private final ServiceFactory serviceFactory;

    public NextcloudShareStructureController(ServiceFactory serviceFactory) {
        this.serviceFactory = serviceFactory;
    }

    @Get("/admin/share-structures")
    @ApiDoc("Liste les paires de structures autorisées au partage NextCloud")
    @SecuredAction(WorkflowRight.ADMIN_SHARE_STRUCTURES)
    public void listShareStructures(HttpServerRequest request) {
        UserUtils.getUserInfos(eb, request, user -> {
            JsonObject query;
            if (user.isADMC()) {
                query = new JsonObject();
            } else {
                JsonArray myStructures = new JsonArray(new ArrayList<>(user.getStructures()));
                query = new JsonObject().put("$or", new JsonArray()
                        .add(new JsonObject().put(Field.STRUCTUREID, new JsonObject().put("$in", myStructures)))
                        .add(new JsonObject().put(Field.TARGETSTRUCTUREID, new JsonObject().put("$in", myStructures))));
            }

            serviceFactory.mongoDb().find(Field.SHARE_STRUCTURES_COLLECTION, query, MongoDbResult.validResultsHandler(event -> {
                if (event.isLeft()) {
                    Renders.renderError(request, new JsonObject().put(Field.ERROR, "Failed to retrieve share structures"));
                    return;
                }
                enrichWithStructureNames(request, event.right().getValue());
            }));
        });
    }

    // Résout id -> {name, UAI} pour affichage, dans le connecteur lui-même (pas de dépendance
    // sur un endpoint admin du module directory que l'admin d'établissement n'a pas forcément).
    private void enrichWithStructureNames(HttpServerRequest request, JsonArray rules) {
        if (rules.isEmpty()) {
            Renders.renderJson(request, rules);
            return;
        }

        Set<String> ids = new HashSet<>();
        for (Object o : rules) {
            JsonObject rule = (JsonObject) o;
            ids.add(rule.getString(Field.STRUCTUREID));
            ids.add(rule.getString(Field.TARGETSTRUCTUREID));
        }

        String cypher = "MATCH (s:Structure) WHERE s.id IN {ids} RETURN s.id as id, s.name as name, s.UAI as UAI";
        JsonObject params = new JsonObject().put("ids", new JsonArray(new ArrayList<>(ids)));
        serviceFactory.neo4j().execute(cypher, params, Neo4jResult.validResultHandler(event -> {
            JsonObject namesById = new JsonObject();
            if (event.isRight()) {
                for (Object o : event.right().getValue()) {
                    JsonObject structure = (JsonObject) o;
                    namesById.put(structure.getString(Field.ID), structure);
                }
            }
            for (Object o : rules) {
                JsonObject rule = (JsonObject) o;
                JsonObject structure = namesById.getJsonObject(rule.getString(Field.STRUCTUREID));
                JsonObject targetStructure = namesById.getJsonObject(rule.getString(Field.TARGETSTRUCTUREID));
                if (structure != null) {
                    rule.put("structureName", structure.getString("name")).put("structureUai", structure.getString(Field.UAI));
                }
                if (targetStructure != null) {
                    rule.put("targetStructureName", targetStructure.getString("name")).put("targetStructureUai", targetStructure.getString(Field.UAI));
                }
            }
            Renders.renderJson(request, rules);
        }));
    }

    @Get("/admin/share-structures/my-structure")
    @ApiDoc("Renvoie l'établissement de l'utilisateur connecté (pour affichage dans le formulaire d'autorisation)")
    @ResourceFilter(AdminShareStructures.class)
    @SecuredAction(value = "", type = ActionType.RESOURCE)
    public void getMyStructure(HttpServerRequest request) {
        UserUtils.getUserInfos(eb, request, user -> {
            List<String> myStructures = user.getStructures();
            if (user.isADMC() || myStructures == null || myStructures.isEmpty()) {
                Renders.renderJson(request, new JsonArray());
                return;
            }

            String cypher = "MATCH (s:Structure) WHERE s.id IN {ids} RETURN s.id as id, s.name as name, s.UAI as UAI";
            JsonObject params = new JsonObject().put("ids", new JsonArray(new ArrayList<>(myStructures)));
            serviceFactory.neo4j().execute(cypher, params, Neo4jResult.validResultHandler(event -> {
                if (event.isLeft()) {
                    Renders.renderError(request, new JsonObject().put(Field.ERROR, "Failed to resolve your structure"));
                    return;
                }
                Renders.renderJson(request, event.right().getValue());
            }));
        });
    }

    @Get("/admin/share-structures/search-structures")
    @ApiDoc("Recherche un établissement par nom ou code UAI, pour le choix de la structure à autoriser")
    @ResourceFilter(AdminShareStructures.class)
    @SecuredAction(value = "", type = ActionType.RESOURCE)
    public void searchStructures(HttpServerRequest request) {
        String rawQuery = request.getParam(Field.QUERY);
        if (StringUtils.isEmpty(rawQuery) || rawQuery.trim().length() < 2) {
            Renders.renderJson(request, new JsonArray());
            return;
        }

        String cypher =
                "MATCH (s:Structure) " +
                        "WHERE toLower(s.name) CONTAINS toLower({search}) OR toLower(s.UAI) CONTAINS toLower({search}) " +
                        "RETURN s.id as id, s.name as name, s.UAI as UAI " +
                        "ORDER BY s.name " +
                        "LIMIT 20";
        JsonObject params = new JsonObject().put("search", rawQuery.trim());
        serviceFactory.neo4j().execute(cypher, params, Neo4jResult.validResultHandler(event -> {
            if (event.isLeft()) {
                Renders.renderError(request, new JsonObject().put(Field.ERROR, "Failed to search structures"));
                return;
            }
            Renders.renderJson(request, event.right().getValue());
        }));
    }

    @Get("/admin/share-structures/resolve")
    @ApiDoc("Résout une structure cible à partir de son code UAI")
    @ResourceFilter(AdminShareStructures.class)
    @SecuredAction(value = "", type = ActionType.RESOURCE)
    public void resolveStructureByUai(HttpServerRequest request) {
        String uai = request.getParam(Field.UAI);
        if (StringUtils.isEmpty(uai)) {
            Renders.renderJson(request, new JsonObject().put(Field.ERROR, "UAI is required"), 400);
            return;
        }

        String cypher = "MATCH (s:Structure {UAI: {uai}}) RETURN s.id as id, s.name as name, s.UAI as UAI LIMIT 1";
        JsonObject params = new JsonObject().put("uai", uai.trim().toUpperCase());
        serviceFactory.neo4j().execute(cypher, params, Neo4jResult.validResultHandler(event -> {
            if (event.isLeft()) {
                Renders.renderError(request, new JsonObject().put(Field.ERROR, "Failed to resolve UAI"));
                return;
            }
            JsonArray results = event.right().getValue();
            if (results.isEmpty()) {
                Renders.renderJson(request, new JsonObject().put(Field.ERROR, "No structure found for this UAI"), 404);
                return;
            }
            Renders.renderJson(request, results.getJsonObject(0));
        }));
    }

    @Post("/admin/share-structures")
    @ApiDoc("Autorise le partage NextCloud entre deux structures")
    @ResourceFilter(AdminShareStructures.class)
    @SecuredAction(value = "", type = ActionType.RESOURCE)
    public void addShareStructure(HttpServerRequest request) {
        request.bodyHandler(body -> {
            JsonObject payload;
            try {
                payload = body.toJsonObject();
            } catch (Exception e) {
                Renders.renderJson(request, new JsonObject().put(Field.ERROR, "Invalid JSON format"), 400);
                return;
            }

            String targetStructureId = payload.getString(Field.TARGETSTRUCTUREID);
            String targetUai = payload.getString(Field.TARGETUAI);
            String structureId = payload.getString(Field.STRUCTUREID);

            if (StringUtils.isEmpty(targetStructureId) && StringUtils.isEmpty(targetUai)) {
                Renders.renderJson(request, new JsonObject().put(Field.ERROR, "targetStructureId or targetUai is required"), 400);
                return;
            }

            if (StringUtils.isEmpty(targetStructureId)) {
                String cypher = "MATCH (s:Structure {UAI: {uai}}) RETURN s.id as id LIMIT 1";
                JsonObject params = new JsonObject().put("uai", targetUai.trim().toUpperCase());
                serviceFactory.neo4j().execute(cypher, params, Neo4jResult.validResultHandler(event -> {
                    if (event.isLeft() || event.right().getValue().isEmpty()) {
                        Renders.renderJson(request, new JsonObject().put(Field.ERROR, "No structure found for this UAI"), 404);
                        return;
                    }
                    String resolvedTargetId = event.right().getValue().getJsonObject(0).getString(Field.ID);
                    createShareStructure(request, structureId, resolvedTargetId);
                }));
            } else {
                createShareStructure(request, structureId, targetStructureId);
            }
        });
    }

    private void createShareStructure(HttpServerRequest request, String structureId, String targetStructureId) {
        UserUtils.getUserInfos(eb, request, user -> {
                String resolvedStructureId = structureId;
                if (!user.isADMC()) {
                    List<String> myStructures = user.getStructures();
                    if (resolvedStructureId == null) {
                        if (myStructures == null || myStructures.isEmpty()) {
                            Renders.renderJson(request, new JsonObject().put(Field.ERROR, "No structure to attach the rule to"), 400);
                            return;
                        }
                        resolvedStructureId = myStructures.get(0);
                    } else if (myStructures == null || !myStructures.contains(resolvedStructureId)) {
                        Renders.renderJson(request, new JsonObject().put(Field.ERROR, "Forbidden: structureId is not one of your own structures"), 403);
                        return;
                    }
                } else if (StringUtils.isEmpty(resolvedStructureId)) {
                    Renders.renderJson(request, new JsonObject().put(Field.ERROR, "structureId is required"), 400);
                    return;
                }

                if (resolvedStructureId.equals(targetStructureId)) {
                    Renders.renderJson(request, new JsonObject().put(Field.ERROR, "structureId and targetStructureId must differ"), 400);
                    return;
                }

                final String finalStructureId = resolvedStructureId;
                JsonObject existsQuery = new JsonObject().put("$or", new JsonArray()
                        .add(new JsonObject().put(Field.STRUCTUREID, finalStructureId).put(Field.TARGETSTRUCTUREID, targetStructureId))
                        .add(new JsonObject().put(Field.STRUCTUREID, targetStructureId).put(Field.TARGETSTRUCTUREID, finalStructureId)));

                serviceFactory.mongoDb().find(Field.SHARE_STRUCTURES_COLLECTION, existsQuery, MongoDbResult.validResultsHandler(event -> {
                    if (event.isLeft()) {
                        Renders.renderError(request, new JsonObject().put(Field.ERROR, "Failed to check existing rule"));
                        return;
                    }
                    if (!event.right().getValue().isEmpty()) {
                        Renders.renderJson(request, new JsonObject().put(Field.MESSAGE, "Rule already exists"));
                        return;
                    }

                    JsonObject document = new JsonObject()
                            .put(Field._ID, UUID.randomUUID().toString())
                            .put(Field.STRUCTUREID, finalStructureId)
                            .put(Field.TARGETSTRUCTUREID, targetStructureId)
                            .put(Field.CREATEDBY, user.getUserId())
                            .put(Field.CREATED, System.currentTimeMillis());

                    serviceFactory.mongoDb().insert(Field.SHARE_STRUCTURES_COLLECTION, document, MongoDbResult.validResultHandler(insertEvent -> {
                        if (insertEvent.isLeft()) {
                            Renders.renderError(request, new JsonObject().put(Field.ERROR, "Failed to save rule"));
                            return;
                        }
                        Renders.renderJson(request, document);
                    }));
                }));
        });
    }

    @Delete("/admin/share-structures")
    @ApiDoc("Retire l'autorisation de partage NextCloud entre deux structures")
    @ResourceFilter(AdminShareStructures.class)
    @SecuredAction(value = "", type = ActionType.RESOURCE)
    public void deleteShareStructure(HttpServerRequest request) {
        String structureId = request.getParam(Field.STRUCTUREID);
        String targetStructureId = request.getParam(Field.TARGETSTRUCTUREID);

        if (StringUtils.isEmpty(structureId) || StringUtils.isEmpty(targetStructureId)) {
            Renders.renderJson(request, new JsonObject().put(Field.ERROR, "structureId and targetStructureId are required"), 400);
            return;
        }

        UserUtils.getUserInfos(eb, request, user -> {
            if (!user.isADMC()) {
                List<String> myStructures = user.getStructures();
                if (myStructures == null || (!myStructures.contains(structureId) && !myStructures.contains(targetStructureId))) {
                    Renders.renderJson(request, new JsonObject().put(Field.ERROR, "Forbidden: neither structure is one of your own"), 403);
                    return;
                }
            }

            JsonObject query = new JsonObject().put("$or", new JsonArray()
                    .add(new JsonObject().put(Field.STRUCTUREID, structureId).put(Field.TARGETSTRUCTUREID, targetStructureId))
                    .add(new JsonObject().put(Field.STRUCTUREID, targetStructureId).put(Field.TARGETSTRUCTUREID, structureId)));

            serviceFactory.mongoDb().delete(Field.SHARE_STRUCTURES_COLLECTION, query, deleteEvent -> {
                Renders.renderJson(request, new JsonObject().put(Field.MESSAGE, "Rule removed"));
            });
        });
    }

    @Get("/share/search-users")
    @ApiDoc("Recherche des utilisateurs d'une structure autorisée au partage NextCloud (hors visibilité entcore)")
    @SecuredAction(value = "", type = ActionType.AUTHENTICATED)
    public void searchCrossStructureUsers(HttpServerRequest request) {
        String rawQuery = request.getParam(Field.QUERY);
        if (StringUtils.isEmpty(rawQuery)) {
            Renders.renderJson(request, new JsonArray());
            return;
        }

        UserUtils.getUserInfos(eb, request, user -> {
            List<String> myStructures = user.getStructures();
            if (myStructures == null || myStructures.isEmpty()) {
                Renders.renderJson(request, new JsonArray());
                return;
            }

            JsonArray myStructuresArray = new JsonArray(new ArrayList<>(myStructures));
            JsonObject mongoQuery = new JsonObject().put("$or", new JsonArray()
                    .add(new JsonObject().put(Field.STRUCTUREID, new JsonObject().put("$in", myStructuresArray)))
                    .add(new JsonObject().put(Field.TARGETSTRUCTUREID, new JsonObject().put("$in", myStructuresArray))));

            serviceFactory.mongoDb().find(Field.SHARE_STRUCTURES_COLLECTION, mongoQuery, MongoDbResult.validResultsHandler(event -> {
                if (event.isLeft()) {
                    Renders.renderError(request, new JsonObject().put(Field.ERROR, "Failed to resolve allowed structures"));
                    return;
                }

                Set<String> allowedTargets = new HashSet<>();
                for (Object o : event.right().getValue()) {
                    JsonObject rule = (JsonObject) o;
                    String structureId = rule.getString(Field.STRUCTUREID);
                    String targetStructureId = rule.getString(Field.TARGETSTRUCTUREID);
                    if (myStructures.contains(structureId) && !myStructures.contains(targetStructureId)) {
                        allowedTargets.add(targetStructureId);
                    }
                    if (myStructures.contains(targetStructureId) && !myStructures.contains(structureId)) {
                        allowedTargets.add(structureId);
                    }
                }

                if (allowedTargets.isEmpty()) {
                    Renders.renderJson(request, new JsonArray());
                    return;
                }

                String cypher =
                        "MATCH (u:User)-[:IN]->(pg:ProfileGroup)-[:DEPENDS]->(s:Structure) " +
                                "WHERE s.id IN {structureIds} " +
                                "AND u.id <> {userId} " +
                                "AND (NOT(HAS(u.blocked)) OR u.blocked = false) " +
                                "AND u.displayNameSearchField CONTAINS {search} " +
                                "RETURN DISTINCT u.id as id, u.displayName as displayName, HEAD(u.profiles) as profile " +
                                "ORDER BY u.displayName " +
                                "LIMIT 15";

                JsonObject params = new JsonObject()
                        .put("structureIds", new JsonArray(new ArrayList<>(allowedTargets)))
                        .put("userId", user.getUserId())
                        .put("search", StringValidation.sanitize(rawQuery));

                serviceFactory.neo4j().execute(cypher, params, Neo4jResult.validResultHandler(neo4jEvent -> {
                    if (neo4jEvent.isLeft()) {
                        Renders.renderError(request, new JsonObject().put(Field.ERROR, "Failed to search users"));
                        return;
                    }
                    Renders.renderJson(request, neo4jEvent.right().getValue());
                }));
            }));
        });
    }
}
