package io.smallrye.config.source.appconfig;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Paths;

import org.eclipse.microprofile.config.spi.ConfigSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import mockit.Delegate;
import mockit.Expectations;
import mockit.MockUp;
import mockit.Mocked;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.appconfig.AppConfigClient;
import software.amazon.awssdk.services.appconfig.model.GetConfigurationRequest;
import software.amazon.awssdk.services.appconfig.model.GetConfigurationResponse;

public class AppConfigConfigSourceProviderTests {
    private static final Logger LOG = LoggerFactory.getLogger(AppConfigConfigSourceProviderTests.class);

    private static final String SERVER_HTTP_PORT_NAME = "server.httpPort";
    private static final String SERVER_HTTP_PORT = "8080";

    private static final String SERVER_HTTPS_PORT_NAME = "server.httpsPort";
    private static final String SERVER_HTTPS_PORT = "8443";

    private static final String USERS_NAME = "users";
    private static final String USERS = "- principal: payments.iws.nonprod.srvc-dev\n" +
            "  roles:\n" +
            "    - inbox-read\n" +
            "    - outbox-write\n" +
            "- principal: payments.core.dev.iws\n" +
            "  roles:\n" +
            "    - inbox-read\n" +
            "    - outbox-write\n";

    private static String CONFIG_YAML;

    static {
        try {
            CONFIG_YAML = Utils.fileToString(Paths.get("./src/test/resources/application.yaml"));
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    @BeforeEach
    void setup() {
        new MockUp<AppConfigClient>() {

        };
    }

    @Test
    public void retrieveParameters(@Mocked AppConfigClient appConfigClient) {
        new Expectations() {
            {
                appConfigClient.getConfiguration(withAny((GetConfigurationRequest) null));
                result = new Delegate() {
                    public GetConfigurationResponse delegate(final GetConfigurationRequest request) {
                        GetConfigurationResponse result = GetConfigurationResponse.builder()
                                .configurationVersion("latest")
                                .contentType("YAML")
                                .content(SdkBytes.fromString(CONFIG_YAML, Charset.defaultCharset()))
                                .build();
                        return result;
                    }
                };
                times = 1;
            }
        };

        AppConfigConfigSourceProvider.CONFIG_PATH = "META-INF/app-config-settings.yaml";
        AppConfigConfigSourceProvider.ENABLE_LOCAL_AUTH = true;
        final AppConfigConfigSourceProvider appConfigConfigSourceProvider = new AppConfigConfigSourceProvider();
        Iterable<ConfigSource> sourceIterable = appConfigConfigSourceProvider
                .getConfigSources(this.getClass().getClassLoader());
        ConfigSource source = sourceIterable.iterator().next();

        assertEquals(SERVER_HTTP_PORT, source.getValue(SERVER_HTTP_PORT_NAME));
        assertEquals(SERVER_HTTPS_PORT, source.getValue(SERVER_HTTPS_PORT_NAME));
        assertEquals(USERS, source.getValue(USERS_NAME));
    }
}
