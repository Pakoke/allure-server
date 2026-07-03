package ru.iopump.qa.allure.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@Data
@ConfigurationProperties(prefix = "datadog")
public class DatadogProperties {

    private final boolean enabled;
    private final String host;
    private final int port;
    private final String prefix;
    private final List<String> tags;
    private final boolean dryRun;
}
