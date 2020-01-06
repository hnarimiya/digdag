package io.digdag.standards.operator;

import java.util.List;
import java.util.stream.Collectors;
import java.io.Writer;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.io.OutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import com.google.common.base.Optional;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.CharStreams;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.inject.Inject;
import io.digdag.spi.OperatorContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.digdag.spi.CommandExecutor;
import io.digdag.spi.CommandLogger;
import io.digdag.spi.TaskRequest;
import io.digdag.spi.TaskResult;
import io.digdag.spi.Operator;
import io.digdag.spi.OperatorFactory;
import io.digdag.client.config.Config;
import io.digdag.client.config.ConfigException;
import io.digdag.util.BaseOperator;
import static io.digdag.standards.operator.ShOperatorFactory.collectEnvironmentVariables;

public class PyOperatorFactory
        implements OperatorFactory
{
    private static Logger logger = LoggerFactory.getLogger(PyOperatorFactory.class);

    private final String runnerScript;

    {
        try (InputStreamReader reader = new InputStreamReader(
                    PyOperatorFactory.class.getResourceAsStream("/digdag/standards/py/runner.py"),
                    StandardCharsets.UTF_8)) {
            runnerScript = CharStreams.toString(reader);
        }
        catch (IOException ex) {
            throw Throwables.propagate(ex);
        }
    }

    private final CommandExecutor exec;
    private final CommandLogger clog;
    private final ObjectMapper mapper;

    @Inject
    public PyOperatorFactory(CommandExecutor exec, CommandLogger clog,
            ObjectMapper mapper)
    {
        this.exec = exec;
        this.clog = clog;
        this.mapper = mapper;
    }

    public String getType()
    {
        return "py";
    }

    @Override
    public Operator newOperator(OperatorContext context)
    {
        return new PyOperator(context);
    }

    private class PyOperator
            extends BaseOperator
    {
        public PyOperator(OperatorContext context)
        {
            super(context);
        }

        @Override
        public TaskResult runTask()
        {
            Config params = request.getConfig()
                .mergeDefault(request.getConfig().getNestedOrGetEmpty("py"))
                .merge(request.getLastStateParams());  // merge state parameters in addition to regular config

            Config data;
            try {
                data = runCode(params);
            }
            catch (IOException | InterruptedException ex) {
                throw Throwables.propagate(ex);
            }

            return TaskResult.defaultBuilder(request)
                .subtaskConfig(data.getNestedOrGetEmpty("subtask_config"))
                .exportParams(data.getNestedOrGetEmpty("export_params"))
                .storeParams(data.getNestedOrGetEmpty("store_params"))
                .build();
        }

        private Config runCode(Config params)
                throws IOException, InterruptedException
        {
            String inFile = workspace.createTempFile("digdag-py-in-", ".tmp");
            String outFile = workspace.createTempFile("digdag-py-out-", ".tmp");

            String script;
            List<String> args;

            if (params.has("_command")) {
                String command = params.get("_command", String.class);
                script = runnerScript;
                args = ImmutableList.of(command, inFile, outFile);
            }
            else {
                script = params.get("script", String.class);
                args = ImmutableList.of(inFile, outFile);
            }

            try (OutputStream fo = workspace.newOutputStream(inFile)) {
                mapper.writeValue(fo, ImmutableMap.of("params", params));
            }

            List<String> python;
            final JsonNode pythonJsonNode = params.getInternalObjectNode().get("python");
            if (pythonJsonNode == null) {
                python = ImmutableList.of("python");
            }
            else if (pythonJsonNode.isTextual()) {
                final String path = pythonJsonNode.asText();
                python = ImmutableList.of(path);
            }
            else if (pythonJsonNode.isArray()) {
                python = Arrays.asList(mapper.readValue(pythonJsonNode.traverse(), String[].class));
            }
            else {
                throw new ConfigException("Invalid python: " + pythonJsonNode.asText());
            }

            List<String> cmdline = ImmutableList.<String>builder()
                .addAll(python).add("-")  // script is fed from stdin
                .addAll(args)
                .build();

            logger.trace("Running py operator: {}", cmdline.stream().collect(Collectors.joining(" ")));

            ProcessBuilder pb = new ProcessBuilder(cmdline);
            pb.directory(workspace.getPath().toFile());
            pb.redirectErrorStream(true);

            // Set up process environment according to env config. This can also refer to secrets.
            Map<String, String> env = pb.environment();
            collectEnvironmentVariables(env, context.getPrivilegedVariables());

            Process p = exec.start(workspace.getPath(), request, pb);

            // feed script to stdin
            try (Writer writer = new BufferedWriter(new OutputStreamWriter(p.getOutputStream()))) {
                writer.write(script);
            }

            // copy stdout to System.out and logger
            clog.copyStdout(p, System.out);

            int ecode = p.waitFor();

            if (ecode != 0) {
                StringBuilder reason = new StringBuilder();
                reason.append("Python command failed with code ").append(ecode);

                try {
                    Config out = mapper.readValue(workspace.getFile(outFile), Config.class);
                    Config err = out.getNestedOrGetEmpty("error");
                    Optional<String> errClass = err.getOptional("class", String.class);
                    Optional<String> errMessage = err.getOptional("message", String.class);
                    List<String> errBacktrace = err.getListOrEmpty("backtrace", String.class);
                    if (errClass.isPresent()) {
                        reason.append(": from ").append(errClass.get());
                    }
                    if (errMessage.isPresent()) {
                        reason.append(": ").append(errMessage.get());
                    }
                    if (!errBacktrace.isEmpty()) {
                        reason.append("\n    ");
                        reason.append(String.join("    ", errBacktrace));
                    }
                }
                catch (JsonMappingException ex) {
                    // comes here if runner.py fails before writing outFile.
                }
                throw new RuntimeException(reason.toString());
            }

            return mapper.readValue(workspace.getFile(outFile), Config.class);
        }
    }
}
