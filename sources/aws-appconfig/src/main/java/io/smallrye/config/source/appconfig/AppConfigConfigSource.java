package io.smallrye.config.source.appconfig;

import java.io.InputStream;
import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.eclipse.microprofile.config.spi.ConfigSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import io.smallrye.config.source.appconfig.internal.Config;
import software.amazon.awssdk.services.appconfig.AppConfigClient;
import software.amazon.awssdk.services.appconfig.model.GetConfigurationRequest;
import software.amazon.awssdk.services.appconfig.model.GetConfigurationResponse;

/**
 * <h1>AWS App Config Config Source</h1>
 *
 * This {@link ConfigSource} implementation reads selected values from a single value in AWS AppConfig
 * (stored as YAML or JSON). The utility looks for a file at 'META-INF/app-config-settings.yaml'.
 * This file is used to define the configuration for AWS App Config.
 *
 * This Config Source has a higher priority than the default 'microprofile-config.properties'.
 *
 *
 * <h2>Usage</h2>
 *
 * To use the AWS AppConfig Config Source, add the following to your Maven 'pom.xml':
 *
 * <pre>
 * &lt;dependency&gt;
 *     &lt;groupId&gt;io.smallrye.config&lt;/groupId&gt;
 *     &lt;artifactId&gt;smallrye-config-source-aws-appconfig&lt;/artifactId&gt;
 *     &lt;version&gt;{version}&lt;/version&gt;
 * &lt;/dependency&gt;
 * </pre>
 *
 * This Config Source will automatically register with your application.
 *
 *
 * <h2>Configuration</h2>
 *
 * The config source will execute a
 * <a href="https://docs.aws.amazon.com/cli/latest/reference/appconfig/get-configuration.html">get-configuration</a>
 * query and load the value, stored as YAML or JSON, into the Configuration.
 * For example, if you store the following YAML in AppConfig
 *
 * <pre>
 *     server:
 *         httpPort: 8080
 *         httpsPort: 8443
 * </pre>
 *
 *
 * Given the following settings file:
 *
 * <pre>
 *     ---
 *     configurationProfile: config.yaml
 *     application: my-app
 *     clientID: my-service
 *     environment: dev
 * </pre>
 *
 *
 * Will result in the following property names and values in the configuration object:
 *
 * <pre>
 * 	server.httpPort=8080
 * 	server.httpsPort=8443
 * </pre>
 *
 *
 * <h2>Periodic Downloading</h2>
 *
 * By default, the config values will only be retrieved once at application start-up.
 * However, this Config Source allows the refresh values periodically.
 * In order to enable this, add the following lines to the 'META-INF/app-config-settings.yaml' file:
 *
 * <pre>
 *     downloadPeriodically: true
 *     downloadInterval: PT10S
 * </pre>
 *
 * The `downloadInterval` is formatted using the
 * <a href="https://en.wikipedia.org/wiki/ISO_8601#Durations">ISO8601 notation</a>. If omitted, the default of
 * once every 10 minutes is used.
 */
public class AppConfigConfigSource implements ConfigSource {

    private static final Logger LOG = LoggerFactory.getLogger(AppConfigConfigSource.class);

    private final URL configFileUrl;

    private final AppConfigClient appConfigClient;

    private Map<String, String> properties = new HashMap<>();

    private final ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

    private final static Duration DEFAULT_UPDATE_INTERVAL = Duration.ofMinutes(10);

    private volatile boolean periodicFetching = true;

    private String configurationVersion;

    public final static int DEFAULT_ORDINAL = 920;
    private final int ordinal;

    AppConfigConfigSource(final AppConfigClient appConfigClient,
            final URL configFileUrl,
            final int ordinal) {
        this.appConfigClient = appConfigClient;
        this.configFileUrl = configFileUrl;
        this.ordinal = ordinal;
        mapper.registerModule(new JavaTimeModule());
        initConfiguration();
    }

    AppConfigConfigSource(final AppConfigClient appConfigClient,
            final URL configFileUrl) {
        this(appConfigClient, configFileUrl, DEFAULT_ORDINAL);
    }

    void initConfiguration() {

        if (configFileUrl != null && appConfigClient != null) {
            try (final InputStream in = configFileUrl.openStream()) {
                final Config config = mapper.readValue(in, Config.class);

                fetchConfiguration(config); // do it synchronously once to check for errors

                if (config.getDownloadPeriodically() != null && config.getDownloadPeriodically()) {
                    Duration updateInterval = (config.getDownloadInterval() != null) ? config.getDownloadInterval()
                            : DEFAULT_UPDATE_INTERVAL;

                    Thread periodicFetchThread = new Thread(() -> {
                        while (periodicFetching) {
                            try {
                                Thread.sleep(updateInterval.toMillis());
                                if (!periodicFetching) {
                                    return;
                                }
                                fetchConfiguration(config);
                            } catch (InterruptedException e) {
                                LOG.warn("Request thread Interrupted!", e);
                                Thread.currentThread().interrupt();
                            } catch (Exception ex) {
                                ex.printStackTrace();
                                LOG.error("Error fetching configuration: ", ex);
                            }
                        }
                    });
                    periodicFetchThread.start();
                }

            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        } else {
            LOG.info("Config file {} was not found, skipping App Config values", AppConfigConfigSourceProvider.CONFIG_PATH);
        }
    }

    public void stopPeriodicFetching() {
        periodicFetching = false;
    }

    private void fetchConfiguration(Config config) {
        GetConfigurationRequest request = GetConfigurationRequest
                .builder()
                .application(config.getApplication())
                .environment(config.getEnvironment())
                .configuration(config.getConfigurationProfile())
                .clientId(config.getClientID())
                .build();

        GetConfigurationResponse response = appConfigClient.getConfiguration(request);
        if (response.configurationVersion() != null && response.configurationVersion().equals(configurationVersion)) {
            // no new version... skip update
            return;
        }

        Load loader = new Load(LoadSettings.builder().build());

        HashMap<String, Object> localValues = new HashMap<>();

        Object object = loader.loadFromInputStream(response.content().asInputStream());

        if (object != null) {
            Map<String, Object> yamlAsMap = convertToMap(object);
            localValues.putAll(flatten(yamlAsMap));
        }

        this.properties = new HashMap(localValues);

        configurationVersion = response.configurationVersion();
        LOG.info("Downloaded new configuration version {}", configurationVersion);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> convertToMap(Object yamlDocument) {

        Map<String, Object> yamlMap = new LinkedHashMap<>();

        // Document is a text block
        if (!(yamlDocument instanceof Map)) {
            yamlMap.put("content", yamlDocument);
            return yamlMap;
        }

        for (Map.Entry<Object, Object> entry : ((Map<Object, Object>) yamlDocument).entrySet()) {
            Object value = entry.getValue();

            if (value instanceof Map) {
                value = convertToMap(value);
            } else if (value instanceof Collection) {
                ArrayList<Map<String, Object>> collection = new ArrayList<>();

                for (Object element : ((Collection) value)) {
                    collection.add(convertToMap(element));
                }

                value = collection;
            }

            yamlMap.put(entry.getKey().toString(), value);
        }
        return yamlMap;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> flatten(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : source.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map) {
                Map<String, Object> subMap = flatten((Map<String, Object>) value);

                for (Map.Entry<String, Object> childEntry : subMap.entrySet()) {
                    result.put(entry.getKey() + "." + childEntry.getKey(), childEntry.getValue());
                }
            } else if (value instanceof Collection) {
                StringBuilder joiner = new StringBuilder();
                String separator = "";

                for (Object element : ((Collection) value)) {
                    Map<String, Object> subMap = flatten(Collections.singletonMap(entry.getKey(), element));
                    joiner.append(separator)
                            .append(subMap.entrySet().iterator().next().getValue().toString());

                    separator = ",";
                }
                result.put(entry.getKey(), joiner.toString());
            } else {
                result.put(entry.getKey(), value.toString());
            }
        }
        return result;
    }

    @Override
    public Map<String, String> getProperties() {
        return Collections.unmodifiableMap(properties);
    }

    @Override
    public Set<String> getPropertyNames() {
        return properties.keySet();
    }

    @Override
    public int getOrdinal() {
        return ordinal;
    }

    @Override
    public String getValue(String propertyName) {
        return properties.get(propertyName);
    }

    @Override
    public String getName() {
        return "SmallRye App Config ConfigSource";
    }
}
