package fr.openent.nextcloud.config;

import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Résolution de la configuration NextCloud à partir du Host de la requête.
 *
 * <p>Les clés viennent de {@code nextcloud-providers} dans ent-core.yaml et la recherche se fait
 * sur le Host reçu ({@code Renders.getHost}). Deux écarts faisaient échouer cette recherche, avec
 * pour seul symptôme une {@link NullPointerException} chez l'appelant (500 sans message) :</p>
 *
 * <ul>
 *   <li>le <b>port standard</b> : une clé {@code www.example.fr:443} ne correspond jamais au Host
 *       envoyé par le navigateur ({@code www.example.fr}), qui omet 443 et 80 ;</li>
 *   <li>les <b>domaines non déclarés</b> : sur un déploiement multi-domaines, chaque nouvelle
 *       entrée DNS devait être ajoutée à {@code nextcloud-providers}, sans quoi le connecteur
 *       tombait pour ce domaine.</li>
 * </ul>
 *
 * <p>Cette map applique donc, dans l'ordre : correspondance exacte, correspondance sans le port
 * standard, puis <b>repli sur la configuration par défaut</b> (le bloc racine du module). Un
 * déploiement mono-NextCloud fonctionne ainsi sur tous ses domaines sans énumération.</p>
 */
public class NextcloudConfigByHost extends HashMap<String, NextcloudConfig> {

    private static final Logger log = LoggerFactory.getLogger(NextcloudConfigByHost.class);

    private final transient NextcloudConfig defaultConfig;
    /** Hosts déjà signalés, pour ne pas inonder le journal à chaque requête. */
    private final transient Set<String> reportedHosts = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public NextcloudConfigByHost(NextcloudConfig defaultConfig) {
        this.defaultConfig = defaultConfig;
    }

    /** Enregistre un fournisseur sous sa clé brute ET sous sa forme sans port standard. */
    public void register(String host, NextcloudConfig config) {
        if (host == null) {
            return;
        }
        final String normalized = normalize(host);
        super.put(normalized, config);
        final String withoutPort = withoutStandardPort(normalized);
        if (!withoutPort.equals(normalized)) {
            super.put(withoutPort, config);
        }
    }

    @Override
    public NextcloudConfig get(Object key) {
        if (!(key instanceof String)) {
            return defaultConfig;
        }
        final String host = normalize((String) key);

        NextcloudConfig config = super.get(host);
        if (config == null) {
            config = super.get(withoutStandardPort(host));
        }
        if (config != null) {
            return config;
        }

        if (defaultConfig == null) {
            log.error("[Nextcloud@NextcloudConfigByHost::get] No provider for host " + host
                    + " and no default configuration");
            return null;
        }
        if (reportedHosts.add(host)) {
            log.info("[Nextcloud@NextcloudConfigByHost::get] Host " + host
                    + " absent de nextcloud-providers, repli sur la configuration par défaut");
        }
        return defaultConfig;
    }

    private static String normalize(String host) {
        return host.trim().toLowerCase();
    }

    private static String withoutStandardPort(String host) {
        if (host.endsWith(":443")) {
            return host.substring(0, host.length() - 4);
        }
        if (host.endsWith(":80")) {
            return host.substring(0, host.length() - 3);
        }
        return host;
    }
}
