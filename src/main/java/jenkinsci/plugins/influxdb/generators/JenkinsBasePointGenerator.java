package jenkinsci.plugins.influxdb.generators;

import com.influxdb.client.write.Point;
import hudson.EnvVars;
import hudson.model.Cause;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.Cause.UserIdCause;
import hudson.tasks.test.AbstractTestResultAction;
import jenkinsci.plugins.influxdb.renderer.ProjectNameRenderer;
import org.apache.commons.lang3.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.StringJoiner;

import java.util.Objects;

import java.util.logging.Logger;
import java.util.logging.Level;

public class JenkinsBasePointGenerator extends AbstractPointGenerator {

    public static final String BUILD_TIME = "build_time";
    public static final String BUILD_STATUS_MESSAGE = "build_status_message";
    public static final String TIME_IN_QUEUE = "time_in_queue";
    public static final String BUILD_SCHEDULED_TIME = "build_scheduled_time";
    public static final String BUILD_EXEC_TIME = "build_exec_time";
    public static final String BUILD_MEASURED_TIME = "build_measured_time";

    /* BUILD_RESULT BUILD_RESULT_ORDINAL BUILD_IS_SUCCESSFUL - explanation
     * SUCCESS   0 true  - The build had no errors.
     * UNSTABLE  1 true  - The build had some errors but they were not fatal. For example, some tests failed.
     * FAILURE   2 false - The build had a fatal error.
     * NOT_BUILT 3 false - The module was not built.
     * ABORTED   4 false - The build was manually aborted.
     */
    public static final String BUILD_RESULT = "build_result";
    public static final String BUILD_RESULT_ORDINAL = "build_result_ordinal";
    public static final String BUILD_IS_SUCCESSFUL = "build_successful";

    public static final String BUILD_AGENT_NAME = "build_agent_name";
    public static final String BUILD_BRANCH_NAME = "build_branch_name";
    public static final String BUILD_CAUSER = "build_causer";
    public static final String BUILD_USER = "build_user";
    public static final String BUILD_CAUSE = "build_cause";

    public static final String PROJECT_BUILD_HEALTH = "project_build_health";
    public static final String PROJECT_LAST_SUCCESSFUL = "last_successful_build";
    public static final String PROJECT_LAST_STABLE = "last_stable_build";
    public static final String TESTS_FAILED = "tests_failed";
    public static final String TESTS_SKIPPED = "tests_skipped";
    public static final String TESTS_TOTAL = "tests_total";

    public static final String AGENT_LOG_PATTERN = "Running on ";

    public static final String SUMMED_TIME_IN_QUEUE = "summed_time_in_queue";
    public static final String NUM_SUBTASKS = "num_subtasks";

    public static final Integer MAX_SUBTASKS = 50;

    private final Run<?, ?> build;
    private final String customPrefix;
    private final String jenkinsEnvParameterField;
    private final String measurementName;
    private EnvVars env;
    private final ProjectNameRenderer projectNameRenderer;


    // (Run<?, ?> build, TaskListener listener, MeasurementRenderer projectNameRenderer, long timestamp, String jenkinsEnvParameterTag) {
    public JenkinsBasePointGenerator(Run<?, ?> build, TaskListener listener,
                                     ProjectNameRenderer projectNameRenderer,
                                     long timestamp, String jenkinsEnvParameterTag, String jenkinsEnvParameterField,
                                     String customPrefix, String measurementName, EnvVars env) {
        super(build, listener, projectNameRenderer, timestamp, jenkinsEnvParameterTag);
        this.build = build;
        this.customPrefix = customPrefix;
        this.jenkinsEnvParameterField = jenkinsEnvParameterField;
        this.measurementName = measurementName;
        this.env = env;
        this.projectNameRenderer = Objects.requireNonNull(projectNameRenderer);
    }

    public boolean hasReport() {
        return true;
    }

    public Point[] generate() {
        // Build is not finished when running with pipelines. Duration must be calculated manually
        long startTime = build.getTimeInMillis();
        long currTime = System.currentTimeMillis();
        long dt = currTime - startTime;
        // Build is not finished when running with pipelines. Set build status as unknown and ordinal
        // as something not predefined
        String result;
        int ordinal;
        Result buildResult = build.getResult();
        if (buildResult == null) {
            result = "?";
            ordinal = 5;
        } else {
            result = buildResult.toString();
            ordinal = buildResult.ordinal;
        }

        String[] buildCause = getCauseDatas();

        Point point = buildPoint(measurementName, customPrefix, build);

        point.addField(BUILD_TIME, build.getDuration() == 0 ? dt : build.getDuration())
            .addField(BUILD_SCHEDULED_TIME, build.getTimeInMillis())
            .addField(BUILD_EXEC_TIME, build.getStartTimeInMillis())
            .addField(BUILD_MEASURED_TIME, currTime)
            .addField(BUILD_STATUS_MESSAGE, build.getBuildStatusSummary().message)
            .addField(BUILD_RESULT, result)
            .addField(BUILD_RESULT_ORDINAL, ordinal)
            .addField(BUILD_IS_SUCCESSFUL, ordinal < 2)
            .addField(BUILD_AGENT_NAME, getNodeName())
            .addField(BUILD_BRANCH_NAME, getBuildEnv("BRANCH_NAME"))
            .addField(PROJECT_BUILD_HEALTH, build.getParent().getBuildHealth().getScore())
            .addField(PROJECT_LAST_SUCCESSFUL, getLastSuccessfulBuild())
            .addField(PROJECT_LAST_STABLE, getLastStableBuild())
            .addField(BUILD_CAUSER , getCauseShortDescription())
            .addField(BUILD_USER, buildCause[0])
            .addField(BUILD_CAUSE, buildCause[1])
            .addTag(BUILD_RESULT, result);

        if (hasTestResults(build)) {
            point.addField(TESTS_FAILED, build.getAction(AbstractTestResultAction.class).getFailCount());
            point.addField(TESTS_SKIPPED, build.getAction(AbstractTestResultAction.class).getSkipCount());
            point.addField(TESTS_TOTAL, build.getAction(AbstractTestResultAction.class).getTotalCount());
        }

        if (hasMetricsPlugin(build)) {
            java.util.logging.Logger LOGGER = java.util.logging.Logger.getLogger(this.getClass().getName());

            point.addField(TIME_IN_QUEUE, build.getAction(jenkins.metrics.impl.TimeInQueueAction.class).getQueuingDurationMillis());
            point.addField(SUMMED_TIME_IN_QUEUE, build.getAction(jenkins.metrics.impl.TimeInQueueAction.class).getQueuingTimeMillis());
            point.addField(NUM_SUBTASKS, build.getAction(jenkins.metrics.impl.TimeInQueueAction.class).getSubTaskCount());

            // Subtask Point Generator -- Used to check longest subtask wait time
            int subtask_count = 0;
            for (Map.Entry<String, Long> subtask :
                build.getAction(jenkins.metrics.impl.TimeInQueueAction.class).getSubTaskMap().entrySet()) {

                if (subtask_count > MAX_SUBTASKS) {
                    String projectName = projectNameRenderer.render(build);
                    String buildNumber = build.getDisplayName();
                    LOGGER.log(Level.WARNING, "Job " + projectName + " build number " + buildNumber +
                        " generated too many subtasks -- Number of subtasks exceeded MAX_SUBTASKS threshold of 50");
                    break;
                }

                point.addField(subtask.getKey(), subtask.getValue());
                subtask_count++;
            }
        }

        if (StringUtils.isNotBlank(jenkinsEnvParameterField)) {
            Properties fieldProperties = parsePropertiesString(jenkinsEnvParameterField);
            Map fieldMap = resolveEnvParameterAndTransformToMap(fieldProperties);
            point.addFields(fieldMap);
        }

        return new Point[] {point};
    }

    private String getBuildEnv(String buildEnv) {
        String s = env.get(buildEnv);
        return s == null ? "" : s;
    }

    private boolean hasTestResults(Run<?, ?> build) {
        return build.getAction(AbstractTestResultAction.class) != null;
    }

    private boolean hasMetricsPlugin(Run<?, ?> build) {
        try {
            return build.getAction(jenkins.metrics.impl.TimeInQueueAction.class) != null;
        } catch (NoClassDefFoundError e) {
            return false;
        }
    }

    private String getCauseShortDescription() {
        try {
            List<Cause> shortDescriptionList = build.getCauses();
            Cause shortDescription = shortDescriptionList.get(0);
            return shortDescription != null ? shortDescription.getShortDescription() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private String[] getCauseDatas() {
        String userCause = "";
        StringJoiner triggers = new StringJoiner(", ");
        try {
            for (Cause cause : build.getCauses()) {
                triggers.add(cause.getClass().getSimpleName());
                if (cause instanceof UserIdCause) {
                    userCause = ((UserIdCause) cause).getUserId();
                } else if (cause.getClass().getName().contains("GitlabWebhookCause")) {
                    userCause = build.getEnvironment(listener).get("gitlabUserUsername");
                }
            }
            return new String[] { userCause != null ? userCause : "", triggers.toString() };
        } catch (IOException | InterruptedException e) {
            return new String[] { "", "" };
        }
    }

    private int getLastSuccessfulBuild() {
        Run<?, ?> lastSuccessfulBuild = build.getParent().getLastSuccessfulBuild();
        return lastSuccessfulBuild != null ? lastSuccessfulBuild.getNumber() : 0;
    }

    private int getLastStableBuild() {
        Run<?, ?> lastStableBuild = build.getParent().getLastStableBuild();
        return lastStableBuild != null ? lastStableBuild.getNumber() : 0;
    }

    private String getNodeName() {
        String nodeName = getBuildEnv("NODE_NAME");
        if(StringUtils.isEmpty(nodeName)) {
            nodeName = getNodeNameFromLogs();
        }
        return nodeName;
    }

    /**
     * Retrieve agent name in the log of the build
     *
     * @return agent name
     */
    private String getNodeNameFromLogs() {
        String agentName = "";
        try (BufferedReader br = new BufferedReader(build.getLogReader())) {
            String line;
            String[] splitLine;
            while ((line = br.readLine()) != null) {
                if (line.startsWith(AGENT_LOG_PATTERN)) {
                    splitLine = line.split(" ");
                    agentName = splitLine.length >= 3 ? splitLine[2] : "";
                    break;
                }
            }
        } catch (IOException e) {}
        return agentName;
    }
}
