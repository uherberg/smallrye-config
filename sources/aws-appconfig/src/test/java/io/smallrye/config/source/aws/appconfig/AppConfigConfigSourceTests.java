package io.smallrye.config.source.aws.appconfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import mockit.*;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.appconfig.AppConfigClient;
import software.amazon.awssdk.services.appconfig.model.GetConfigurationRequest;
import software.amazon.awssdk.services.appconfig.model.GetConfigurationResponse;

public class AppConfigConfigSourceTests {
    private static final Logger LOG = LoggerFactory.getLogger(AppConfigConfigSourceTests.class);

    private static final String SERVER_HTTP_PORT_NAME = "server.httpPort";
    private static final String SERVER_HTTP_PORT = "8080";
    private static final String SERVER_HTTP_PORT_CHANGED = "1234";

    private static final String SERVER_HTTPS_PORT_NAME = "server.httpsPort";
    private static final String SERVER_HTTPS_PORT = "8443";
    private static final String SERVER_HTTPS_PORT_CHANGED = "1234";

    private static final String USERS_NAME = "users";
    private static final String USERS = "- principal: payments.iws.nonprod.srvc-dev\n" +
            "  roles:\n" +
            "    - inbox-read\n" +
            "    - outbox-write\n" +
            "- principal: payments.core.dev.iws\n" +
            "  roles:\n" +
            "    - inbox-read\n" +
            "    - outbox-write\n";
    private static final String USERS_CHANGED = "- principal: payments.iws.nonprod.srvc-dev\n" +
            "  roles:\n" +
            "    - outbox-write\n" +
            "- principal: payments.core.dev.iws\n" +
            "  roles:\n" +
            "    - outbox-write\n";

    private static String CONFIG_YAML;
    private static String CONFIG_YAML2;

    static {
        try {
            CONFIG_YAML = Utils.fileToString(Paths.get("./src/test/resources/application.yaml"));
            CONFIG_YAML2 = Utils.fileToString(Paths.get("./src/test/resources/application2.yaml"));
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
    public void retrieveParameters(@Mocked AppConfigClient appConfigClient) throws Exception {
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

        final File file = new File("./src/test/resources/META-INF/app-config-settings.yaml");
        final AppConfigConfigSource source = new AppConfigConfigSource(appConfigClient, file.toURI().toURL());
        assertEquals(SERVER_HTTP_PORT, source.getValue(SERVER_HTTP_PORT_NAME));
        assertEquals(SERVER_HTTPS_PORT, source.getValue(SERVER_HTTPS_PORT_NAME));
        assertEquals(USERS, source.getValue(USERS_NAME));
    }

    @Test
    public void testNoConfigFile(@Mocked AppConfigClient appConfigClient) throws Exception {
        assertThrows(RuntimeException.class, () -> {
            File file = new File("./src/test/resources/does-not-exist.yaml");
            AppConfigConfigSource source = new AppConfigConfigSource(appConfigClient, file.toURI().toURL());
        });
    }

    @Test
    public void testEmptyConfigFile(@Mocked AppConfigClient appConfigClient) throws Exception {
        assertThrows(RuntimeException.class, () -> {
            File file = new File("./src/test/resources/empty.yaml");
            AppConfigConfigSource source = new AppConfigConfigSource(appConfigClient, file.toURI().toURL());
        });
    }

    @Test
    public void retrieveParametersPeriodically(@Mocked AppConfigClient appConfigClient) throws Exception {
        final AtomicInteger latch = new AtomicInteger(2);
        final Object lockTest = new Object();

        new Expectations() {
            {
                appConfigClient.getConfiguration(withAny((GetConfigurationRequest) null));
                result = new Delegate() {
                    public GetConfigurationResponse delegate(final GetConfigurationRequest request) {
                        int latchValue = latch.decrementAndGet();
                        GetConfigurationResponse result = GetConfigurationResponse.builder()
                                .configurationVersion(latchValue <= 0 ? "2" : "1")
                                .contentType("YAML")
                                .content(SdkBytes.fromString(latchValue <= 0 ? CONFIG_YAML2 : CONFIG_YAML,
                                        Charset.defaultCharset()))
                                .build();

                        if (latchValue == 0) {
                            Thread unlockThread = new Thread(() -> {
                                synchronized (lockTest) {
                                    try {
                                        Thread.sleep(1);
                                    } catch (InterruptedException e) {
                                        LOG.warn("Request thread Interrupted!", e);
                                        Thread.currentThread().interrupt();
                                    }
                                    LOG.debug("releasing wait");
                                    lockTest.notifyAll();
                                }
                            });
                            unlockThread.start();
                        }

                        return result;
                    }
                };
                minTimes = 2;
            }
        };

        final File file = new File("./src/test/resources/META-INF/app-config-settings-periodically.yaml");
        final AppConfigConfigSource source = new AppConfigConfigSource(appConfigClient, file.toURI().toURL());

        assertEquals(SERVER_HTTP_PORT, source.getValue(SERVER_HTTP_PORT_NAME));
        assertEquals(SERVER_HTTPS_PORT, source.getValue(SERVER_HTTPS_PORT_NAME));
        assertEquals(USERS, source.getValue(USERS_NAME));

        synchronized (lockTest) {
            LOG.info("Waiting for thread...");
            lockTest.wait(10000L);
        }
        source.stopPeriodicFetching();

        synchronized (lockTest) {
            lockTest.wait(10L);
        }

        assertEquals(SERVER_HTTP_PORT_CHANGED, source.getValue(SERVER_HTTP_PORT_NAME));
        assertEquals(SERVER_HTTPS_PORT_CHANGED, source.getValue(SERVER_HTTPS_PORT_NAME));
        assertEquals(USERS_CHANGED, source.getValue(USERS_NAME));
    }
}
