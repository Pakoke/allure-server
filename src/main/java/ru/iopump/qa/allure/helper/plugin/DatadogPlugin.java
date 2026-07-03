package ru.iopump.qa.allure.helper.plugin;

import com.timgroup.statsd.NonBlockingStatsDClientBuilder;
import com.timgroup.statsd.StatsDClient;
import io.qameta.allure.core.LaunchResults;
import io.qameta.allure.entity.Label;
import io.qameta.allure.entity.Status;
import io.qameta.allure.entity.TestResult;
import lombok.extern.slf4j.Slf4j;
import ru.iopump.qa.allure.properties.DatadogProperties;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
public class DatadogPlugin implements AllureServerPlugin {

    @Override
    public String getName() {
        return "Datadog metrics";
    }

    @Override
    public boolean isEnabled(Context context) {
        return props(context).isEnabled();
    }

    @Override
    public void onGenerationStart(Collection<Path> resultsDirectories, Context context) {
    }

    @Override
    public void onGenerationFinish(Path reportDirectory, Collection<LaunchResults> launchResults, Context context) {
        var properties = props(context);
        var allResults = launchResults.stream()
            .flatMap(lr -> lr.getResults().stream())
            .toList();

        var reportPath = reportDirectory.getFileName().toString();
        var prefix = properties.getPrefix();
        var dryRun = properties.isDryRun();

        long total = allResults.size();
        long passed = countByStatus(allResults, Status.PASSED);
        long failed = countByStatus(allResults, Status.FAILED);
        long broken = countByStatus(allResults, Status.BROKEN);
        long skipped = countByStatus(allResults, Status.SKIPPED);
        long unknown = countByStatus(allResults, Status.UNKNOWN);
        double passRate = (total - skipped) > 0 ? (passed * 100.0) / (total - skipped) : 0;
        long durationMs = allResults.stream()
            .filter(tr -> tr.getTime() != null && tr.getTime().getDuration() != null)
            .mapToLong(tr -> tr.getTime().getDuration())
            .sum();

        String pathTag = "path:" + reportPath;
        String[] globalTags = buildGlobalTags(properties.getTags(), pathTag);

        if (dryRun) {
            logDryRun(prefix, total, passed, failed, broken, skipped, unknown, passRate, durationMs, globalTags);
            sendLabelMetrics(prefix, allResults, pathTag, globalTags, properties, null);
            return;
        }

        try (StatsDClient client = new NonBlockingStatsDClientBuilder()
            .prefix(prefix)
            .hostname(properties.getHost())
            .port(properties.getPort())
            .build()) {

            client.gauge("tests.total", total, globalTags);
            client.gauge("tests.passed", passed, withTag(globalTags, "status:passed"));
            client.gauge("tests.failed", failed, withTag(globalTags, "status:failed"));
            client.gauge("tests.broken", broken, withTag(globalTags, "status:broken"));
            client.gauge("tests.skipped", skipped, withTag(globalTags, "status:skipped"));
            client.gauge("tests.unknown", unknown, withTag(globalTags, "status:unknown"));
            client.gauge("tests.pass_rate", passRate, globalTags);
            client.gauge("tests.duration_ms", durationMs, globalTags);

            sendLabelMetrics(prefix, allResults, pathTag, globalTags, properties, client);

            log.info("[PLUGIN {}] Sent metrics: total={}, passed={}, failed={}, broken={}, skipped={}, unknown={}, pass_rate={}, duration_ms={}",
                getName(), total, passed, failed, broken, skipped, unknown, String.format("%.1f", passRate), durationMs);
        }
    }

    private void sendLabelMetrics(String prefix, List<TestResult> allResults, String pathTag,
                                  String[] globalTags, DatadogProperties properties, StatsDClient client) {
        sendGroupedMetric("suite", "tests.by_suite", allResults, pathTag, properties, client);
        sendGroupedMetric("feature", "tests.by_feature", allResults, pathTag, properties, client);
    }

    private void sendGroupedMetric(String labelName, String metricName, List<TestResult> allResults,
                                   String pathTag, DatadogProperties properties, StatsDClient client) {
        // Group by label value + status
        Map<String, Map<Status, Long>> grouped = allResults.stream()
            .flatMap(tr -> tr.getLabels().stream()
                .filter(l -> labelName.equals(l.getName()))
                .map(l -> Map.entry(l.getValue(), tr.getStatus())))
            .collect(Collectors.groupingBy(
                Map.Entry::getKey,
                Collectors.groupingBy(Map.Entry::getValue, Collectors.counting())
            ));

        grouped.forEach((labelValue, statusCounts) ->
            statusCounts.forEach((status, count) -> {
                String[] tags = {pathTag, labelName + ":" + labelValue, "status:" + status.name().toLowerCase()};
                String[] allTags = mergeTags(properties.getTags(), tags);
                if (client != null) {
                    client.gauge(metricName, count, allTags);
                } else {
                    log.info("[PLUGIN {} DRY-RUN] {}.{} = {} tags={}", getName(), properties.getPrefix(),
                        metricName, count, String.join(",", allTags));
                }
            })
        );
    }

    private static long countByStatus(List<TestResult> results, Status status) {
        return results.stream().filter(tr -> tr.getStatus() == status).count();
    }

    private static String[] buildGlobalTags(List<String> extraTags, String pathTag) {
        if (extraTags == null || extraTags.isEmpty()) {
            return new String[]{pathTag};
        }
        String[] tags = new String[extraTags.size() + 1];
        tags[0] = pathTag;
        for (int i = 0; i < extraTags.size(); i++) {
            tags[i + 1] = extraTags.get(i);
        }
        return tags;
    }

    private static String[] withTag(String[] base, String extra) {
        String[] result = new String[base.length + 1];
        System.arraycopy(base, 0, result, 0, base.length);
        result[base.length] = extra;
        return result;
    }

    private static String[] mergeTags(List<String> extraTags, String... baseTags) {
        if (extraTags == null || extraTags.isEmpty()) return baseTags;
        String[] result = new String[baseTags.length + extraTags.size()];
        System.arraycopy(baseTags, 0, result, 0, baseTags.length);
        for (int i = 0; i < extraTags.size(); i++) {
            result[baseTags.length + i] = extraTags.get(i);
        }
        return result;
    }

    private void logDryRun(String prefix, long total, long passed, long failed, long broken,
                           long skipped, long unknown, double passRate, long durationMs, String[] tags) {
        log.info("[PLUGIN {} DRY-RUN] {}.tests.total={} {}.tests.passed={} {}.tests.failed={} " +
                "{}.tests.broken={} {}.tests.skipped={} {}.tests.unknown={} {}.tests.pass_rate={} " +
                "{}.tests.duration_ms={} tags={}",
            getName(), prefix, total, prefix, passed, prefix, failed, prefix, broken,
            prefix, skipped, prefix, unknown, prefix, String.format("%.1f", passRate),
            prefix, durationMs, String.join(",", tags));
    }

    private static DatadogProperties props(Context context) {
        return context.beanFactory().getBean(DatadogProperties.class);
    }
}
