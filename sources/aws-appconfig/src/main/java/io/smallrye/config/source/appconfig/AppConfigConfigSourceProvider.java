package io.smallrye.config.source.appconfig;

import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

import org.eclipse.microprofile.config.spi.ConfigSource;
import org.eclipse.microprofile.config.spi.ConfigSourceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProviderChain;
import software.amazon.awssdk.auth.credentials.ContainerCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.InstanceProfileCredentialsProvider;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.http.apache.ProxyConfiguration;
import software.amazon.awssdk.services.appconfig.AppConfigClient;

public class AppConfigConfigSourceProvider implements ConfigSourceProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(AppConfigConfigSourceProvider.class);

    public static String CONFIG_PATH = "META-INF/app-config-settings.yaml";
    public static boolean ENABLE_LOCAL_AUTH = false;
    private final List<ConfigSource> configSources = new ArrayList<>();

    @Override
    public Iterable<ConfigSource> getConfigSources(ClassLoader forClassLoader) {
        try {

            final SdkHttpClient httpClient = ApacheHttpClient.builder()
                    .proxyConfiguration(ProxyConfiguration.builder()
                            .useSystemPropertyValues(true)
                            .build())
                    .build();

            AppConfigClient appConfigClient = AppConfigClient.builder().credentialsProvider(
                    AwsCredentialsProviderChain.builder()
                            .reuseLastProviderEnabled(true)
                            .credentialsProviders(
                                    ENABLE_LOCAL_AUTH ? DefaultCredentialsProvider.builder()
                                            .asyncCredentialUpdateEnabled(true)
                                            .build() : null,
                                    ContainerCredentialsProvider.builder()
                                            .asyncCredentialUpdateEnabled(true)
                                            .build(),
                                    InstanceProfileCredentialsProvider.builder()
                                            .asyncCredentialUpdateEnabled(true)
                                            .build())
                            .build())
                    .httpClient(httpClient)
                    .build();

            resolveConfiguration(forClassLoader, appConfigClient, CONFIG_PATH, AppConfigConfigSource.DEFAULT_ORDINAL);

            final String appEnvironment = resolveVariable("app", "env");
            if (appEnvironment != null) {
                final String path = "META-INF/app-config-settings-".concat(appEnvironment).concat(".yaml");
                resolveConfiguration(forClassLoader, appConfigClient, path, AppConfigConfigSource.DEFAULT_ORDINAL + 1);
            }

            if (configSources.isEmpty()) {
                LOGGER.warn("Could not resolve any AWS App Config config files. AppConfigConfigSource disabled.");
            }

        } catch (Exception e) {
            LOGGER.warn("AppConfigConfigSource disabled: ", e);
        }
        return configSources;
    }

    private void resolveConfiguration(ClassLoader forClassLoader, AppConfigClient appConfigClient, String location,
            int ordinal) {
        try {
            final Enumeration<URL> configFileUris = forClassLoader.getResources(location);

            while (configFileUris.hasMoreElements()) {
                final URL configFile = configFileUris.nextElement();
                configSources.add(new AppConfigConfigSource(appConfigClient, configFile, ordinal));
                LOGGER.info("Resolved configuration data from AWS AppConfig from {} with ordinal {}", location, ordinal);
            }
        } catch (Exception ex) {
            LOGGER.warn("Skipping AppConfig source from {}", location, ex);
        }
    }

    private static String resolveVariable(final String prefix, final String key) {
        final String envName = prefix.toUpperCase().concat("_").concat(key.toUpperCase());
        String value = System.getenv(envName);
        if (value == null) {
            final String propertyName = prefix.toLowerCase().concat(".").concat(key.toLowerCase());
            value = System.getProperty(propertyName);
            if (value != null) {
                LOGGER.info("Resolved system property {} with value {}", propertyName, value);
            } else {
                LOGGER.info("No system property or environment variable resolved");
            }
        } else {
            LOGGER.info("Resolved environment variable {} with value {}", envName, value);
        }
        return value;
    }

}
