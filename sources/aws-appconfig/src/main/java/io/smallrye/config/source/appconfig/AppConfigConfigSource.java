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
 * <p>
 * A {@link ConfigSource} implementation that reads values from AWS App Config
 * </p>
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
