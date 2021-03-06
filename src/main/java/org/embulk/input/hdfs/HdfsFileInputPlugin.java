package org.embulk.input.hdfs;

import com.google.common.base.Function;
import com.google.common.collect.Lists;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PathNotFoundException;
import org.embulk.config.Config;
import org.embulk.config.ConfigDefault;
import org.embulk.config.ConfigDiff;
import org.embulk.config.ConfigInject;
import org.embulk.config.ConfigSource;
import org.embulk.config.Task;
import org.embulk.config.TaskReport;
import org.embulk.config.TaskSource;
import org.embulk.spi.BufferAllocator;
import org.embulk.spi.Exec;
import org.embulk.spi.FileInputPlugin;
import org.embulk.spi.TransactionalFileInput;
import org.embulk.spi.util.InputStreamTransactionalFileInput;
import org.jruby.embed.ScriptingContainer;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class HdfsFileInputPlugin implements FileInputPlugin
{
    private static final Logger logger = Exec.getLogger(HdfsFileInputPlugin.class);

    public interface PluginTask extends Task
    {
        @Config("config_files")
        @ConfigDefault("[]")
        public List<String> getConfigFiles();

        @Config("config")
        @ConfigDefault("{}")
        public Map<String, String> getConfig();

        @Config("path")
        public String getPath();

        @Config("rewind_seconds")
        @ConfigDefault("0")
        public int getRewindSeconds();

        @Config("partition")
        @ConfigDefault("true")
        public boolean getPartition();

        @Config("num_partitions") // this parameter is the approximate value.
        @ConfigDefault("-1")      // Default: Runtime.getRuntime().availableProcessors()
        public int getApproximateNumPartitions();

        public List<HdfsPartialFile> getFiles();
        public void setFiles(List<HdfsPartialFile> hdfsFiles);

        @ConfigInject
        public BufferAllocator getBufferAllocator();
    }

    @Override
    public ConfigDiff transaction(ConfigSource config, FileInputPlugin.Control control)
    {
        PluginTask task = config.loadConfig(PluginTask.class);

        // listing Files
        String pathString = strftime(task.getPath(), task.getRewindSeconds());
        try {
            List<String> originalFileList = buildFileList(getFs(task), pathString);

            if (originalFileList.isEmpty()) {
                throw new PathNotFoundException(pathString);
            }

            task.setFiles(allocateHdfsFilesToTasks(task, getFs(task), originalFileList));
            logger.info("Loading target files: {}", originalFileList);
        }
        catch (IOException e) {
            logger.error(e.getMessage());
            throw new RuntimeException(e);
        }

        // log the detail of partial files.
        for (HdfsPartialFile partialFile : task.getFiles()) {
            logger.info("target file: {}, start: {}, end: {}",
                    partialFile.getPath(), partialFile.getStart(), partialFile.getEnd());
        }

        // number of processors is same with number of targets
        int taskCount = task.getFiles().size();
        logger.info("task size: {}", taskCount);

        return resume(task.dump(), taskCount, control);
    }

    @Override
    public ConfigDiff resume(TaskSource taskSource,
                             int taskCount,
                             FileInputPlugin.Control control)
    {
        control.run(taskSource, taskCount);

        ConfigDiff configDiff = Exec.newConfigDiff();

        // usually, yo use last_path
        //if (task.getFiles().isEmpty()) {
        //    if (task.getLastPath().isPresent()) {
        //        configDiff.set("last_path", task.getLastPath().get());
        //    }
        //} else {
        //    List<String> files = new ArrayList<String>(task.getFiles());
        //    Collections.sort(files);
        //    configDiff.set("last_path", files.get(files.size() - 1));
        //}

        return configDiff;
    }

    @Override
    public void cleanup(TaskSource taskSource,
                        int taskCount,
                        List<TaskReport> successTaskReports)
    {
    }

    @Override
    public TransactionalFileInput open(TaskSource taskSource, int taskIndex)
    {
        final PluginTask task = taskSource.loadTask(PluginTask.class);

        InputStream input;
        try {
            input = openInputStream(task, task.getFiles().get(taskIndex));
        }
        catch (IOException e) {
            logger.error(e.getMessage());
            throw new RuntimeException(e);
        }

        return new InputStreamTransactionalFileInput(task.getBufferAllocator(), input) {
            @Override
            public void abort()
            { }

            @Override
            public TaskReport commit()
            {
                return Exec.newTaskReport();
            }
        };
    }

    private static HdfsPartialFileInputStream openInputStream(PluginTask task, HdfsPartialFile partialFile)
            throws IOException
    {
        FileSystem fs = getFs(task);
        InputStream original = fs.open(new Path(partialFile.getPath()));
        return new HdfsPartialFileInputStream(original, partialFile.getStart(), partialFile.getEnd());
    }

    private static FileSystem getFs(final PluginTask task)
            throws IOException
    {
        Configuration configuration = new Configuration();

        for (Object configFile : task.getConfigFiles()) {
            configuration.addResource(configFile.toString());
        }
        configuration.reloadConfiguration();

        for (Map.Entry<String, String> entry: task.getConfig().entrySet()) {
            configuration.set(entry.getKey(), entry.getValue());
        }

        return FileSystem.get(configuration);
    }

    private String strftime(final String raw, final int rewind_seconds)
    {
        ScriptingContainer jruby = new ScriptingContainer();
        Object resolved = jruby.runScriptlet(
                String.format("(Time.now - %s).strftime('%s')", String.valueOf(rewind_seconds), raw));
        return resolved.toString();
    }

    private List<String> buildFileList(final FileSystem fs, final String pathString)
            throws IOException
    {
        List<String> fileList = new ArrayList<>();
        Path rootPath = new Path(pathString);

        final FileStatus[] entries = fs.globStatus(rootPath);
        // `globStatus` does not throw PathNotFoundException.
        // return null instead.
        // see: https://github.com/apache/hadoop/blob/branch-2.7.0/hadoop-common-project/hadoop-common/src/main/java/org/apache/hadoop/fs/Globber.java#L286
        if (entries == null) {
            return fileList;
        }

        for (FileStatus entry : entries) {
            if (entry.isDirectory()) {
                fileList.addAll(lsr(fs, entry));
            }
            else {
                fileList.add(entry.getPath().toString());
            }
        }

        return fileList;
    }

    private List<String> lsr(final FileSystem fs, FileStatus status)
            throws IOException
    {
        List<String> fileList = new ArrayList<>();
        if (status.isDirectory()) {
            for (FileStatus entry : fs.listStatus(status.getPath())) {
                fileList.addAll(lsr(fs, entry));
            }
        }
        else {
            fileList.add(status.getPath().toString());
        }
        return fileList;
    }

    private List<HdfsPartialFile> allocateHdfsFilesToTasks(final PluginTask task, final FileSystem fs, final List<String> fileList)
            throws IOException
    {
        List<Path> pathList = Lists.transform(fileList, new Function<String, Path>()
        {
            @Nullable
            @Override
            public Path apply(@Nullable String input)
            {
                return new Path(input);
            }
        });

        int totalFileLength = 0;
        for (Path path : pathList) {
            totalFileLength += fs.getFileStatus(path).getLen();
        }

        // TODO: optimum allocation of resources
        int approximateNumPartitions =
                (task.getApproximateNumPartitions() <= 0) ? Runtime.getRuntime().availableProcessors() : task.getApproximateNumPartitions();
        int partitionSizeByOneTask = totalFileLength / approximateNumPartitions;

        List<HdfsPartialFile> hdfsPartialFiles = new ArrayList<>();
        for (Path path : pathList) {
            int fileLength = (int) fs.getFileStatus(path).getLen(); // declare `fileLength` here because this is used below.
            if (fileLength <= 0) {
                logger.info("Skip the 0 byte target file: {}", path);
                continue;
            }

            int numPartitions;
            if (path.toString().endsWith(".gz") || path.toString().endsWith(".bz2") || path.toString().endsWith(".lzo")) {
                numPartitions = 1;
            }
            else if (!task.getPartition()) {
                numPartitions = 1;
            }
            else {
                numPartitions = ((fileLength - 1) / partitionSizeByOneTask) + 1;
            }

            HdfsFilePartitioner partitioner = new HdfsFilePartitioner(fs, path, numPartitions);
            hdfsPartialFiles.addAll(partitioner.getHdfsPartialFiles());
        }

        return hdfsPartialFiles;
    }
}
