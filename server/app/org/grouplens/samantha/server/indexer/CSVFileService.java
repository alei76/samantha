package org.grouplens.samantha.server.indexer;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.Lists;
import org.grouplens.samantha.server.config.ConfigKey;
import play.Configuration;
import play.Logger;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Singleton
public class CSVFileService {
    private final int maxWriter;
    private final String separator;
    private final List<String> dataDirs;
    private final String dirPattern;
    private final Map<String, TreeMap<String, BufferedWriter>> activeFiles = new HashMap<>();
    private final Map<String, Map<String, List<String>>> activeSchemas = new HashMap<>();
    private final Map<String, Map<String, Lock>> activeLocks = new HashMap<>();
    private int curDirIdx = 0;

    @Inject
    private CSVFileService(Configuration configuration) {
        String sep = configuration.getString(ConfigKey.CSV_FILE_SERVICE_SEPARATOR.get());
        if (sep != null) {
            separator = sep;
        } else {
            separator = "\t";
        }
        dataDirs = configuration.getStringList(ConfigKey.CSV_FILE_SERVICE_DATA_DIRS.get());
        String pattern = configuration.getString(ConfigKey.CSV_FILE_SERVICE_DIR_PATTERN.get());
        if (pattern == null) {
            dirPattern = "/yyyy/MM/dd/";
        } else {
            dirPattern = pattern;
        }
        maxWriter = configuration.getInt(ConfigKey.CSV_FILE_SERVICE_MAX_WRITER.get());
    }

    private String pickDirectory(int idx, String type, int tstamp) {
        SimpleDateFormat dateFormat = new SimpleDateFormat(dirPattern);
        long ms = tstamp * 1000L;
        return dataDirs.get(idx) + "/" + type + dateFormat.format(new Date(ms));
    }

    private void freeResources(String type, int remain) {
        if (activeFiles.containsKey(type) && activeFiles.get(type).size() > remain) {
            Object[] keys = activeFiles.get(type).keySet().toArray();
            for (int i=0; i<keys.length - remain; i++) {
                try {
                    activeLocks.get(type).get(keys[i]).lock();
                    activeFiles.get(type).get(keys[i]).close();
                    activeFiles.get(type).remove(keys[i]);
                    activeSchemas.get(type).remove(keys[i]);
                } catch (IOException e) {
                    Logger.error(e.getMessage());
                } finally {
                    activeLocks.get(type).get(keys[i]).unlock();
                    activeLocks.get(type).remove(keys[i]);
                }
            }
        }
    }

    synchronized private String lockFile(String type, String directory, List<String> dataFields)
            throws IOException {
        if (!activeFiles.containsKey(type)) {
            activeFiles.put(type, new TreeMap<>());
            activeSchemas.put(type, new HashMap<>());
            activeLocks.put(type, new HashMap<>());
        }
        freeResources(type, maxWriter);
        Map<String, BufferedWriter> actFiles = activeFiles.get(type);
        Map<String, Lock> actLocks = activeLocks.get(type);
        Map<String, List<String>> actSchemas = activeSchemas.get(type);
        for (int i=0; i<Integer.MAX_VALUE; i++) {
            String file = directory + Integer.valueOf(i) + ".csv";
            if (actSchemas.containsKey(file)) {
                if (dataFields.equals(actSchemas.get(file))) {
                    actLocks.get(file).lock();
                    return file;
                }
            } else {
                BufferedWriter writer;
                try {
                    BufferedReader reader = new BufferedReader(new FileReader(file));
                    List<String> curFields = Lists.newArrayList(reader.readLine().split(separator));
                    reader.close();
                    if (!curFields.equals(dataFields)) {
                        continue;
                    } else {
                        writer = new BufferedWriter(new FileWriter(file, true));
                    }
                } catch (FileNotFoundException e) {
                    new File(directory).mkdirs();
                    writer = new BufferedWriter(new FileWriter(file, true));
                    IndexerUtilities.writeOutHeader(dataFields, writer, separator);
                }
                actFiles.put(file, writer);
                actSchemas.put(file, dataFields);
                ReentrantReadWriteLock rwl = new ReentrantReadWriteLock();
                Lock lock = rwl.writeLock();
                lock.lock();
                actLocks.put(file, lock);
                return file;
            }
        }
        throw new IOException("Can not find a good file to write in the directory.");
    }

    synchronized private void unlockFile(String type, String file) {
        if (activeLocks.get(type).containsKey(file)) {
            activeLocks.get(type).get(file).unlock();
        }
    }

    synchronized private List<String> getSchema(String type, String file) {
        return activeSchemas.get(type).get(file);
    }

    synchronized private BufferedWriter getWriter(String type, String file) {
        return activeFiles.get(type).get(file);
    }

    public void write(String type, JsonNode entity, List<String> dataFields, int tstamp) {
        for (int idx=curDirIdx; idx<dataDirs.size(); idx++) {
            String file = null;
            try {
                String directory = pickDirectory(idx, type, tstamp);
                file = lockFile(type, directory, dataFields);
                BufferedWriter writer = getWriter(type, file);
                List<String> curFields = getSchema(type, file);
                IndexerUtilities.writeOutJson(entity, curFields, writer, separator);
                break;
            } catch (IOException e) {
                curDirIdx = idx + 1;
            } finally {
                unlockFile(type, file);
            }
        }
    }

    synchronized private String getLastFileAndFreeResources(String type) {
        freeResources(type, 1);
        if (activeFiles.containsKey(type) && activeFiles.get(type).size() > 0) {
            return activeFiles.get(type).lastKey();
        } else {
            return null;
        }
    }

    public List<String> getFiles(String type, int beginTime, int endTime) {
        Set<String> files = new HashSet<>();
        //String last = getLastFileAndFreeResources(type);
        for (int idx=0; idx<dataDirs.size(); idx ++) {
            for (int i = beginTime; i <= endTime; i+=3600) {
                String dir = pickDirectory(idx, type, i);
                File folder = new File(dir);
                if (folder.isDirectory()) {
                    File[] list = folder.listFiles();
                    for (File file : list) {
                        String path = file.getAbsolutePath();
                        files.add(path);
                        /*
                        if (!last.equals(path)) {
                            files.add(path);
                        }
                        */
                    }
                }
            }
        }
        return Lists.newArrayList(files);
    }

    public String getSeparator() {
        return separator;
    }
}
