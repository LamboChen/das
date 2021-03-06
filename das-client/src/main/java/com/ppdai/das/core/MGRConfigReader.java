package com.ppdai.das.core;

import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.ppdai.das.core.enums.DatabaseCategory;
import com.ppdai.das.core.status.StatusManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * It launches backend threads to query MRG (MySQL Replication Group) info at interval,
 * to refresh master/slave databases configuration.
 *
 * Two read/write modes:
 * 1: Disable read/write splitting mode
 *    All read/write requests will be routed the single master, no slaves.
 * 2: Enable read/write splitting mode
 *    Write requests will routed to single master, read ones will be routed to slaves.
 *
 */
public class MGRConfigReader {

    private static final Logger logger = LoggerFactory.getLogger(MGRConfigReader.class);

    private DasConfigure dasConfigure;
    private Map<String, DatabaseSet> mgrDatabaseSetSnapshot = new ConcurrentHashMap<>();
    private ConcurrentHashMap<String, String> connectionString2Host = new ConcurrentHashMap<>();
    private static AtomicBoolean readWriteSplitting = new AtomicBoolean(false);

    private static final String PRIMARY = "PRIMARY";
    private static final String SECONDARY = "SECONDARY";
    private static final String ONLINE = "ONLINE";

    private static final String MGR_INFO =
            "SELECT  MEMBER_ID, MEMBER_HOST, MEMBER_PORT, MEMBER_STATE, " +
            "IF(global_status.VARIABLE_NAME IS NOT NULL, " +
            "'" + PRIMARY+"', " +
            "'" + SECONDARY +"') AS MEMBER_ROLE " +
            "FROM performance_schema.replication_group_members " +
            "LEFT JOIN performance_schema.global_status ON global_status.VARIABLE_NAME = 'group_replication_primary_member' " +
            "AND global_status.VARIABLE_VALUE = replication_group_members.MEMBER_ID;";

    private ScheduledExecutorService executer;

    public static void enableMGRReadWriteSplitting() {
        readWriteSplitting.set(true);
    }

    public MGRConfigReader(DasConfigure dasConfigure) {
        this.dasConfigure = dasConfigure;
        createExecutor(dasConfigure.getDatabaseSets().size());
    }

    private void createExecutor(int size) {
        if(executer == null) {
            executer = Executors.newScheduledThreadPool(size, r -> {
                Thread thread = new Thread(r, "MGRConfigReader@start: " + new Date());
                thread.setDaemon(true);
                return thread;
            });
        }
    }

    public void start() throws Exception {
        filterMGR();
        updateMGRInfo(true);
        logger.info("MGR mode: " + (!readWriteSplitting.get() ?  "non-" : "") + "Read/Write splitting");
        logger.info("MGR database sets: " + mgrDatabaseSetSnapshot.keySet());
        logger.info("Databases init status after MGR check: " + dasConfigure.getDatabaseSets());
        if (!mgrDatabaseSetSnapshot.isEmpty()) {
            executer.scheduleWithFixedDelay(() -> {
                try {
                    updateMGRInfo(false);
                } catch (Exception e) {
                    logger.error("Exception occurs when updateMGRInfo", e);
                }
            }, 3, 3, TimeUnit.SECONDS);
        }
    }

    static class MGRInfo {
        String id;
        String host;
        String state;
        String role;
        String name;

        MGRInfo(String id, String host, String state, String role) {
            this.id = id;
            this.host = host;
            this.state = state;
            this.role = role;
        }

        String getHost() {
            return host;
        }

        boolean isOnline() {
            return ONLINE.equals(state);
        }

        boolean isMaster() {
            return PRIMARY.equals(role);
        }

        boolean isOnlineMaster() {
            return isOnline() && isMaster();
        }
    }
    
    private void filterMGR() throws Exception {
        for(Map.Entry<String, DatabaseSet> setEnt : dasConfigure.getDatabaseSets().entrySet()) {
            DatabaseSet set = setEnt.getValue();
            if(set.getDatabaseCategory() != DatabaseCategory.MySql) {
                continue;
            }
            boolean isMGR = set.getDatabases().values().stream().anyMatch(db -> !mgrInfoDB(db.getConnectionString()).isEmpty());
            if(isMGR) {
                mgrDatabaseSetSnapshot.put(setEnt.getKey(), set.deepCopy(set.getDatabases()));
            }
        }
    }

    private Map<String, MGRInfo> mgrInfoMerged(DatabaseSet set, boolean isInit) {
        List<MGRInfo> list = new ArrayList<>();
        for (DataBase db : set.getDatabases().values()) {
            List<MGRInfo> mgrs = mgrInfoDB(db.getConnectionString());
            list.addAll(mgrs);
        }
        Map<String, List<MGRInfo>> group = list.stream().collect(Collectors.groupingBy(MGRInfo::getHost));
        Map<String, MGRInfo> result = new HashMap<>();
        for (Map.Entry<String, List<MGRInfo>> en : group.entrySet()) {
            List<Map.Entry<String, String>> physicals = connectionString2Host.entrySet().stream().filter(e -> e.getValue().equals(en.getKey())).collect(Collectors.toList());
            if(physicals.isEmpty() && isInit) {
                throw new IllegalArgumentException("MGR checking fail. [" + en.getKey() + "] is missing in MGR cluster,");
            } else {
                for(Map.Entry<String, String> e : physicals) {
                    result.put(e.getKey(), en.getValue().stream().findFirst().get());
                }
            }
        }

        return result;
    }

    private List<MGRInfo> mgrInfoDB(String connectionString) {
        List<MGRInfo> list = new ArrayList<>();
        try (Connection connection = dasConfigure.getConnectionLocator().getConnection(connectionString);
             Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(MGR_INFO)) {
            String url = connection.getMetaData().getURL();
            connectionString2Host.putIfAbsent(connectionString, url2Host(url));

            while (rs.next()) {
                String host = rs.getString("MEMBER_HOST");
                String id = rs.getString("MEMBER_ID");
                int port = rs.getInt("MEMBER_PORT");
                String state = rs.getString("MEMBER_STATE");
                String role = rs.getString("MEMBER_ROLE");

                list.add(new MGRInfo(id, host, state, role));
            }
        } catch (Exception e) {
            logger.error("Exception occurs when query MGR info.", e);
        }
        return list;
    }

    private String url2Host(String url) {
        if (Strings.isNullOrEmpty(url)) {
            return "";
        }
        List<String> split = Splitter.on("jdbc:mysql://").splitToList(url);
        if (split.size() > 1) {//For MySQL only
            return Splitter.on(":").splitToList(split.get(1)).get(0);
        }
        return "";
    }

    static abstract class MGRStatusHandler {
        static MGRStatusHandler create(boolean isReadWriteSplitting) {
            return isReadWriteSplitting ? new ReadWriteSplittingHandler() : new NonReadWriteSplittingHandler();
        }

        abstract DatabaseSet createDatabaseSet(DatabaseSet set, Map<String, MGRInfo> infos) throws Exception;
    }

    /**
     * Disable read/write splitting mode
     */
    private static class NonReadWriteSplittingHandler extends MGRStatusHandler {
        @Override
        DatabaseSet createDatabaseSet(DatabaseSet set, Map<String, MGRInfo> infos) throws Exception {
            Map<String, DataBase> newDBs = new HashMap<>();
            for(Map.Entry<String, DataBase> dbEnt : set.getDatabases().entrySet()) {
                DataBase db = dbEnt.getValue();
                MGRInfo info = infos.get(db.getConnectionString());
                if(info != null && info.isOnlineMaster()) {
                    newDBs.put(dbEnt.getKey(), db.deepCopy(true));
                }
            }
            return set.deepCopy(newDBs);
        }
    }

    /**
     * Enable read/write splitting mode
     */
    private static class ReadWriteSplittingHandler extends MGRStatusHandler {
        @Override
        DatabaseSet createDatabaseSet(DatabaseSet set, Map<String, MGRInfo> infos) throws Exception {
            Map<String, DataBase> newDBs = new HashMap<>();
            for(Map.Entry<String, DataBase> dbEnt : set.getDatabases().entrySet()) {
                DataBase db = dbEnt.getValue();
                MGRInfo info = infos.get(db.getConnectionString());
                if(info != null && info.isOnline()){
                    newDBs.put(dbEnt.getKey(), db.deepCopy(info.isMaster()));
                }
            }
            return set.deepCopy(newDBs);
        }
    }

    void updateMGRInfo(boolean isInit) throws Exception {
        for (Map.Entry<String, DatabaseSet> ent : mgrDatabaseSetSnapshot.entrySet()) {
            String setName = ent.getKey();
            DatabaseSet set = ent.getValue();

            Map<String, MGRInfo> infos = mgrInfoMerged(set, isInit);
            MGRStatusHandler handler = MGRStatusHandler.create(readWriteSplitting.get());
            DatabaseSet newSet = handler.createDatabaseSet(set, infos);
            DatabaseSet current = dasConfigure.getDatabaseSets().get(setName);
            //Replace databaseSet atomically if changed
            if(!current.equals(newSet)){
                dasConfigure.onDatabaseSetChanged(new DasConfigure.DatabaseSetChangeEvent(ImmutableMap.of(setName, newSet)));
                if(!isInit) {
                    for(String appId : DasConfigureFactory.getAppIds()) {
                        StatusManager.registerApplication(appId, dasConfigure);
                    }
                }
                logger.info("Database changes for MGR: " + setName);
            }
        }
    }

}
