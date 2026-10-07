package fr.openent.nextcloud;

import fr.openent.nextcloud.config.NextcloudConfig;
import fr.openent.nextcloud.config.NextcloudConfigByHost;
import fr.openent.nextcloud.controller.DocumentsController;
import fr.openent.nextcloud.controller.NextcloudController;
import fr.openent.nextcloud.controller.UserController;
import fr.openent.nextcloud.controller.NextcloudDesktopController;
import fr.openent.nextcloud.controller.NextcloudShareStructureController;
import fr.openent.nextcloud.service.ServiceFactory;
import fr.wseduc.mongodb.MongoDb;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import org.entcore.common.http.BaseServer;
import org.entcore.common.neo4j.Neo4j;
import org.entcore.common.sql.Sql;
import org.entcore.common.storage.Storage;
import org.entcore.common.storage.StorageFactory;

import java.util.Map;

public class Nextcloud extends BaseServer {

	public static final int TIMEOUT_VALUE = 30000;
	public static final String DB_SCHEMA = "nextcloud";

	@Override
	public void start(Promise<Void> startPromise) throws Exception {
    final Promise<Void> promise = Promise.promise();
    super.start(promise);
    promise.future()
      .compose(e -> this.initNextcloud())
      .onComplete(startPromise);
  }

  public Future<Void> initNextcloud() {
		// Le bloc racine du module décrit déjà un NextCloud complet (nextcloud-host,
		// admin-credential, endpoint…) : il sert de configuration de repli pour tout host non
		// déclaré dans nextcloud-providers, cf. NextcloudConfigByHost.
		final NextcloudConfig defaultConfig = new NextcloudConfig(config);
		final NextcloudConfigByHost nextcloudConfigMapByHost = new NextcloudConfigByHost(defaultConfig);
		if (config.containsKey("nextcloud-providers")) {
			final JsonObject nexcloudProviders = config.getJsonObject("nextcloud-providers", new JsonObject());
			for (String host : nexcloudProviders.getMap().keySet()) {
				nextcloudConfigMapByHost.register(host, new NextcloudConfig(nexcloudProviders.getJsonObject(host, new JsonObject())));
			}
		} else {
			final String host = config.getString("host").split("//")[1];
			nextcloudConfigMapByHost.register(host, defaultConfig);
		}

		return StorageFactory.build(vertx, config)
      .compose(storageFactory -> {
        final Storage storage = storageFactory.getStorage();

        ServiceFactory serviceFactory = new ServiceFactory(vertx, storage, Neo4j.getInstance(), Sql.getInstance(),
          MongoDb.getInstance(), initWebClient(), nextcloudConfigMapByHost);

        addController(new NextcloudController(serviceFactory));
        addController(new UserController(serviceFactory));
        addController(new DocumentsController(serviceFactory));
        addController(new NextcloudDesktopController(serviceFactory));
        addController(new NextcloudShareStructureController(serviceFactory));
        return Future.succeededFuture();
      });
	}

	private WebClient initWebClient() {
		WebClientOptions options = new WebClientOptions()
				.setConnectTimeout(Nextcloud.TIMEOUT_VALUE);
		return WebClient.create(vertx, options);
	}

}
