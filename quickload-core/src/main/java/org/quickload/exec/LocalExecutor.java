package org.quickload.exec;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import javax.validation.constraints.NotNull;
import com.google.common.collect.ImmutableList;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.fasterxml.jackson.databind.JsonNode;
import org.quickload.config.Task;
import org.quickload.config.Config;
import org.quickload.config.ConfigSource;
import org.quickload.config.TaskSource;
import org.quickload.config.NextConfig;
import org.quickload.config.Report;
import org.quickload.config.FailedReport;
import org.quickload.record.Schema;
import org.quickload.spi.ExecControl;
import org.quickload.spi.ExecTask;
import org.quickload.spi.ExecConfig;
import org.quickload.spi.InputPlugin;
import org.quickload.spi.OutputPlugin;
import org.quickload.spi.PluginThread;
import org.quickload.spi.NoticeLogger;
import org.quickload.channel.PageChannel;

public class LocalExecutor
{
    private final Injector injector;
    private final ConfigSource systemConfig;

    public interface ExecutorTask
            extends Task
    {
        @Config("in")
        @NotNull
        public ConfigSource getInputConfig();

        // TODO
        @Config("out")
        @NotNull
        public ConfigSource getOutputConfig();

        public TaskSource getInputTask();
        public void setInputTask(TaskSource taskSource);

        public TaskSource getOutputTask();
        public void setOutputTask(TaskSource taskSource);
    }

    @Inject
    public LocalExecutor(Injector injector,
            @ForSystemConfig ConfigSource systemConfig)
    {
        this.injector = injector;
        this.systemConfig = systemConfig;
    }

    private static class TransactionContext
    {
        private List<Report> inputReports;
        private List<Report> outputReports;
        private NextConfig inputNextConfig;
        private NextConfig outputNextConfig;
        private List<NoticeLogger.Message> noticeMessages;
        private List<NoticeLogger.SkippedRecord> skippedRecords;

        public List<Report> getInputReports()
        {
            return inputReports;
        }

        public List<Report> getOutputReports()
        {
            return outputReports;
        }

        public void setInputReports(List<Report> inputReports)
        {
            this.inputReports = inputReports;
        }

        public void setOutputReports(List<Report> outputReports)
        {
            this.outputReports = outputReports;
        }

        public void setInputNextConfig(NextConfig inputNextConfig)
        {
            this.inputNextConfig = inputNextConfig;
        }

        public void setOutputNextConfig(NextConfig outputNextConfig)
        {
            this.outputNextConfig = outputNextConfig;
        }

        public NextConfig getInputNextConfig()
        {
            return inputNextConfig;
        }

        public NextConfig getOutputNextConfig()
        {
            return outputNextConfig;
        }

        public void setNoticeMessages(List<NoticeLogger.Message> noticeMessages)
        {
            this.noticeMessages = noticeMessages;
        }

        public void setSkippedRecords(List<NoticeLogger.SkippedRecord> skippedRecords)
        {
            this.skippedRecords = skippedRecords;
        }

        public List<NoticeLogger.Message> getNoticeMessages()
        {
            return noticeMessages;
        }

        public List<NoticeLogger.SkippedRecord> getSkippedRecords()
        {
            return skippedRecords;
        }
    }

    private static class ProcessContext
    {
        private final Report[] inputReports;
        private final Report[] outputReports;
        private final List<NoticeLogger.Message> noticeMessages;
        private final List<NoticeLogger.SkippedRecord> skippedRecords;

        public ProcessContext(int processorCount)
        {
            this.inputReports = new Report[processorCount];
            this.outputReports = new Report[processorCount];
            for (int i=0; i < processorCount; i++) {
                inputReports[i] = outputReports[i] = new Report();
            }
            this.noticeMessages = new ArrayList<NoticeLogger.Message>();
            this.skippedRecords = new ArrayList<NoticeLogger.SkippedRecord>();
        }

        public void setInputReport(int processorIndex, Report report)
        {
            inputReports[processorIndex] = report;
        }

        public void setOutputReport(int processorIndex, Report report)
        {
            outputReports[processorIndex] = report;
        }

        public void addNotices(NoticeLogger notice)
        {
            synchronized (noticeMessages) {
                notice.addAllMessagesTo(noticeMessages);
            }
            synchronized (skippedRecords) {
                notice.addAllSkippedRecordsTo(skippedRecords);
            }
        }

        public List<Report> getInputReports()
        {
            return ImmutableList.copyOf(inputReports);
        }

        public List<Report> getOutputReports()
        {
            return ImmutableList.copyOf(outputReports);
        }

        public List<NoticeLogger.Message> getNoticeMessages()
        {
            return noticeMessages;
        }

        public List<NoticeLogger.SkippedRecord> getSkippedRecords()
        {
            return skippedRecords;
        }
    }

    protected InputPlugin newInputPlugin(ExecTask exec, ExecutorTask task)
    {
        return exec.newPlugin(InputPlugin.class, task.getInputConfig().get("type"));
    }

    protected OutputPlugin newOutputPlugin(ExecTask exec, ExecutorTask task)
    {
        return exec.newPlugin(OutputPlugin.class, task.getOutputConfig().get("type"));
    }

    public ExecuteResult run(ConfigSource config)
    {
        try {
            return doRun(config);
        } catch (Throwable ex) {
            throw PluginExecutors.propagePluginExceptions(ex);
        }
    }

    private ExecuteResult doRun(ConfigSource config)
    {
        final ExecTask exec = PluginExecutors.newExecTask(injector, config);
        final ExecutorTask task = exec.loadConfig(config, ExecutorTask.class);

        final InputPlugin in = newInputPlugin(exec, task);
        final OutputPlugin out = newOutputPlugin(exec, task);

        final TransactionContext tranContext = new TransactionContext();

        // TODO create and use ExecTaskBuilder to set default values

        NextConfig inputNextConfig = in.runInputTransaction(exec, task.getInputConfig(), new ExecControl() {
            public List<Report> run(final TaskSource inputTask)
            {
                NextConfig outputNextConfig = out.runOutputTransaction(exec, task.getOutputConfig(), new ExecControl() {
                    public List<Report> run(final TaskSource outputTask)
                    {
                        task.setInputTask(inputTask);
                        task.setOutputTask(outputTask);

                        // TODO debug log; use logger
                        System.out.println("input: "+task.getInputTask());
                        System.out.println("output: "+task.getOutputTask());

                        ProcessContext procContext = new ProcessContext(exec.getProcessorCount());

                        process(exec, exec.dumpTask(task), procContext);
                        tranContext.setOutputReports(procContext.getOutputReports());
                        tranContext.setInputReports(procContext.getInputReports());
                        tranContext.setNoticeMessages(procContext.getNoticeMessages());
                        tranContext.setSkippedRecords(procContext.getSkippedRecords());

                        return tranContext.getOutputReports();
                    }
                });
                tranContext.setOutputNextConfig(outputNextConfig);
                return tranContext.getInputReports();
            }
        });
        tranContext.setInputNextConfig(inputNextConfig);

        return new ExecuteResult(
                tranContext.getInputNextConfig().setAll(tranContext.getOutputNextConfig()),
                tranContext.getNoticeMessages(),
                tranContext.getSkippedRecords());
    }

    private final void process(final ExecTask exec, final TaskSource taskSource, final ProcessContext procContext)
    {
        final ExecutorTask task = exec.loadTask(taskSource, ExecutorTask.class);
        final InputPlugin in = newInputPlugin(exec, task);
        final OutputPlugin out = newOutputPlugin(exec, task);

        // TODO multi-threading

        for (int i=0; i < exec.getProcessorCount(); i++) {
            final int processorIndex = i;

            PluginThread thread = null;
            Throwable error = null;
            try (final PageChannel channel = exec.newPageChannel()) {
                thread = exec.startPluginThread(new Runnable() {
                    public void run()
                    {
                        try {
                            // TODO return Report
                            Report report = out.runOutput(exec, task.getOutputTask(),
                                processorIndex, channel.getInput());
                            procContext.setOutputReport(processorIndex, report);
                        } catch (Throwable ex) {
                            procContext.setOutputReport(processorIndex, new FailedReport(ex));
                            throw ex;  // TODO error handling to propagate exceptions to runInputTransaction should be at the end of process() once multi-threaded
                        } finally {
                            channel.completeConsumer();
                        }
                    }
                });

                try {
                    Report report = in.runInput(exec, task.getInputTask(),
                            processorIndex, channel.getOutput());
                    channel.completeProducer();
                    thread.join();
                    channel.join();  // throws if channel is fully consumed
                    procContext.setInputReport(processorIndex, report);
                } catch (Throwable ex) {
                    procContext.setInputReport(processorIndex, new FailedReport(ex));
                    throw ex;  // TODO error handling to propagate exceptions to runInputTransactioat the end of process() once multi-threaded
                }

            } catch (Throwable ex) {
                error = ex;
            } finally {
                PluginThread.joinAndThrowNested(thread, error);
            }
        }

        // TODO create ExecTask for each thread and do this for each thread
        procContext.addNotices(exec.notice());
    }
}
