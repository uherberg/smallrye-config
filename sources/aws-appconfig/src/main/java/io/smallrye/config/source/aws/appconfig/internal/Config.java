package io.smallrye.config.source.aws.appconfig.internal;

import java.time.Duration;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class Config {

    @JsonProperty("configurationProfile")
    private String configurationProfile;

    @JsonProperty("application")
    private String application;

    @JsonProperty("clientID")
    private String clientID;

    @JsonProperty("environment")
    private String environment;

    @JsonProperty("downloadPeriodically")
    private Boolean downloadPeriodically;

    @JsonProperty("downloadInterval")
    private Duration downloadInterval;

    @JsonCreator
    public Config(@JsonProperty("configurationProfile") String configurationProfile,
            @JsonProperty("application") String application,
            @JsonProperty("clientID") String clientID,
            @JsonProperty("environment") String environment,
            @JsonProperty("downloadPeriodically") Boolean downloadPeriodically,
            @JsonProperty("downloadInterval") Duration downloadInterval) {
        this.configurationProfile = configurationProfile;
        this.application = application;
        this.clientID = clientID;
        this.environment = environment;
        this.downloadPeriodically = downloadPeriodically;
        this.downloadInterval = downloadInterval;
    }

    public String getConfigurationProfile() {
        return configurationProfile;
    }

    public String getApplication() {
        return application;
    }

    public String getClientID() {
        return clientID;
    }

    public String getEnvironment() {
        return environment;
    }

    public Boolean getDownloadPeriodically() {
        return downloadPeriodically;
    }

    public Duration getDownloadInterval() {
        return downloadInterval;
    }
}
