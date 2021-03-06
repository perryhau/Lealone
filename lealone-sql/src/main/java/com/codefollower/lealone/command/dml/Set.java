/*
 * Copyright 2004-2011 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package com.codefollower.lealone.command.dml;

import java.text.Collator;

import com.codefollower.lealone.command.CommandInterface;
import com.codefollower.lealone.command.Prepared;
import com.codefollower.lealone.compress.Compressor;
import com.codefollower.lealone.constant.Constants;
import com.codefollower.lealone.constant.ErrorCode;
import com.codefollower.lealone.constant.SetTypes;
import com.codefollower.lealone.dbobject.Schema;
import com.codefollower.lealone.dbobject.Setting;
import com.codefollower.lealone.dbobject.table.Table;
import com.codefollower.lealone.engine.Database;
import com.codefollower.lealone.engine.Mode;
import com.codefollower.lealone.engine.Session;
import com.codefollower.lealone.expression.Expression;
import com.codefollower.lealone.expression.ValueExpression;
import com.codefollower.lealone.message.DbException;
import com.codefollower.lealone.result.ResultInterface;
import com.codefollower.lealone.tools.CompressTool;
import com.codefollower.lealone.util.StringUtils;
import com.codefollower.lealone.value.CompareMode;
import com.codefollower.lealone.value.ValueInt;

/**
 * This class represents the statement
 * SET
 */
public class Set extends Prepared {

    private final int type;
    private Expression expression;
    private String stringValue;
    private String[] stringValueList;

    public Set(Session session, int type) {
        super(session);
        this.type = type;
    }

    public void setString(String v) {
        this.stringValue = v;
    }

    public boolean isTransactional() {
        switch (type) {
        case SetTypes.CLUSTER:
        case SetTypes.VARIABLE:
        case SetTypes.QUERY_TIMEOUT:
        case SetTypes.LOCK_TIMEOUT:
        case SetTypes.TRACE_LEVEL_SYSTEM_OUT:
        case SetTypes.TRACE_LEVEL_FILE:
        case SetTypes.THROTTLE:
        case SetTypes.SCHEMA:
        case SetTypes.SCHEMA_SEARCH_PATH:
            return true;
        default:
        }
        return false;
    }

    public int update() {
        Database database = session.getDatabase();
        String name = SetTypes.getTypeName(type);
        switch (type) {
        case SetTypes.ALLOW_LITERALS: {
            session.getUser().checkAdmin();
            int value = getIntValue();
            if (value < 0 || value > 2) {
                throw DbException.getInvalidValueException("ALLOW_LITERALS", getIntValue());
            }
            database.setAllowLiterals(value);
            addOrUpdateSetting(name, null, value);
            break;
        }
        case SetTypes.CACHE_SIZE:
            if (getIntValue() < 0) {
                throw DbException.getInvalidValueException("CACHE_SIZE", getIntValue());
            }
            session.getUser().checkAdmin();
            database.setCacheSize(getIntValue());
            addOrUpdateSetting(name, null, getIntValue());
            break;
        case SetTypes.CLUSTER: {
            if (Constants.CLUSTERING_ENABLED.equals(stringValue)) {
                // this value is used when connecting
                // ignore, as the cluster setting is checked later
                break;
            }
            String value = StringUtils.quoteStringSQL(stringValue);
            if (!value.equals(database.getCluster()) && !value.equals(Constants.CLUSTERING_DISABLED)) {
                // anybody can disable the cluster
                // (if he can't access a cluster node)
                session.getUser().checkAdmin();
            }
            database.setCluster(value);
            // use the system session so that the current transaction
            // (if any) is not committed
            addOrUpdateSetting(database.getSystemSession(), name, value, 0);
            database.getSystemSession().commit(true);
            break;
        }
        case SetTypes.COLLATION: {
            session.getUser().checkAdmin();
            Table table = database.getFirstUserTable();
            if (table != null) {
                throw DbException.get(ErrorCode.COLLATION_CHANGE_WITH_DATA_TABLE_1, table.getSQL());
            }
            CompareMode compareMode;
            StringBuilder buff = new StringBuilder(stringValue);
            if (stringValue.equals(CompareMode.OFF)) {
                compareMode = CompareMode.getInstance(null, 0);
            } else {
                int strength = getIntValue();
                buff.append(" STRENGTH ");
                if (strength == Collator.IDENTICAL) {
                    buff.append("IDENTICAL");
                } else if (strength == Collator.PRIMARY) {
                    buff.append("PRIMARY");
                } else if (strength == Collator.SECONDARY) {
                    buff.append("SECONDARY");
                } else if (strength == Collator.TERTIARY) {
                    buff.append("TERTIARY");
                }
                compareMode = CompareMode.getInstance(stringValue, strength);
            }
            addOrUpdateSetting(name, buff.toString(), 0);
            database.setCompareMode(compareMode);
            break;
        }
        case SetTypes.COMPRESS_LOB: {
            session.getUser().checkAdmin();
            int algo = CompressTool.getCompressAlgorithm(stringValue);
            database.setLobCompressionAlgorithm(algo == Compressor.NO ? null : stringValue);
            addOrUpdateSetting(name, stringValue, 0);
            break;
        }
        case SetTypes.CREATE_BUILD: {
            session.getUser().checkAdmin();
            if (database.isStarting()) {
                // just ignore the command if not starting
                // this avoids problems when running recovery scripts
                int value = getIntValue();
                addOrUpdateSetting(name, null, value);
            }
            break;
        }
        case SetTypes.DATABASE_EVENT_LISTENER: {
            session.getUser().checkAdmin();
            database.setEventListenerClass(stringValue);
            break;
        }
        case SetTypes.DB_CLOSE_DELAY: {
            int x = getIntValue();
            if (x == -1) {
                // -1 is a special value for in-memory databases,
                // which means "keep the DB alive and use the same DB for all connections"
            } else if (x < 0) {
                throw DbException.getInvalidValueException("DB_CLOSE_DELAY", x);
            }
            session.getUser().checkAdmin();
            database.setCloseDelay(getIntValue());
            addOrUpdateSetting(name, null, getIntValue());
            break;
        }
        case SetTypes.DEFAULT_LOCK_TIMEOUT:
            if (getIntValue() < 0) {
                throw DbException.getInvalidValueException("DEFAULT_LOCK_TIMEOUT", getIntValue());
            }
            session.getUser().checkAdmin();
            addOrUpdateSetting(name, null, getIntValue());
            break;
        case SetTypes.DEFAULT_TABLE_TYPE:
            session.getUser().checkAdmin();
            database.setDefaultTableType(getIntValue());
            addOrUpdateSetting(name, null, getIntValue());
            break;
        case SetTypes.EXCLUSIVE: {
            session.getUser().checkAdmin();
            int value = getIntValue();
            switch (value) {
            case 0:
                database.setExclusiveSession(null, false);
                break;
            case 1:
                database.setExclusiveSession(session, false);
                break;
            case 2:
                database.setExclusiveSession(session, true);
                break;
            default:
                throw DbException.getInvalidValueException("EXCLUSIVE", value);
            }
            break;
        }
        case SetTypes.IGNORECASE:
            session.getUser().checkAdmin();
            database.setIgnoreCase(getIntValue() == 1);
            addOrUpdateSetting(name, null, getIntValue());
            break;
        case SetTypes.LOCK_MODE:
            session.getUser().checkAdmin();
            database.setLockMode(getIntValue());
            addOrUpdateSetting(name, null, getIntValue());
            break;
        case SetTypes.LOCK_TIMEOUT:
            if (getIntValue() < 0) {
                throw DbException.getInvalidValueException("LOCK_TIMEOUT", getIntValue());
            }
            session.setLockTimeout(getIntValue());
            break;
        case SetTypes.LOG: {
            int value = getIntValue();
            if (database.isPersistent() && value != database.getLogMode()) {
                session.getUser().checkAdmin();
                database.setLogMode(value);
            }
            break;
        }
        case SetTypes.MAX_LENGTH_INPLACE_LOB: {
            if (getIntValue() < 0) {
                throw DbException.getInvalidValueException("MAX_LENGTH_INPLACE_LOB", getIntValue());
            }
            session.getUser().checkAdmin();
            database.setMaxLengthInplaceLob(getIntValue());
            addOrUpdateSetting(name, null, getIntValue());
            break;
        }
        case SetTypes.MAX_LOG_SIZE:
            if (getIntValue() < 0) {
                throw DbException.getInvalidValueException("MAX_LOG_SIZE", getIntValue());
            }
            session.getUser().checkAdmin();
            database.setMaxLogSize((long) getIntValue() * 1024 * 1024);
            addOrUpdateSetting(name, null, getIntValue());
            break;
        case SetTypes.MAX_MEMORY_ROWS: {
            if (getIntValue() < 0) {
                throw DbException.getInvalidValueException("MAX_MEMORY_ROWS", getIntValue());
            }
            session.getUser().checkAdmin();
            database.setMaxMemoryRows(getIntValue());
            addOrUpdateSetting(name, null, getIntValue());
            break;
        }
        case SetTypes.MAX_MEMORY_UNDO: {
            if (getIntValue() < 0) {
                throw DbException.getInvalidValueException("MAX_MEMORY_UNDO", getIntValue());
            }
            session.getUser().checkAdmin();
            database.setMaxMemoryUndo(getIntValue());
            addOrUpdateSetting(name, null, getIntValue());
            break;
        }
        case SetTypes.MAX_OPERATION_MEMORY: {
            if (getIntValue() < 0) {
                throw DbException.getInvalidValueException("MAX_OPERATION_MEMORY", getIntValue());
            }
            session.getUser().checkAdmin();
            int value = getIntValue();
            database.setMaxOperationMemory(value);
            break;
        }
        case SetTypes.MODE:
            Mode mode = Mode.getInstance(stringValue);
            if (mode == null) {
                throw DbException.get(ErrorCode.UNKNOWN_MODE_1, stringValue);
            }
            if (database.getMode() != mode) {
                session.getUser().checkAdmin();
                database.setMode(mode);
            }
            break;
        case SetTypes.MULTI_THREADED: {
            session.getUser().checkAdmin();
            database.setMultiThreaded(getIntValue() == 1);
            break;
        }
        case SetTypes.MVCC: {
            if (database.isMultiVersion() != (getIntValue() == 1)) {
                throw DbException.get(ErrorCode.CANNOT_CHANGE_SETTING_WHEN_OPEN_1, "MVCC");
            }
            break;
        }
        case SetTypes.OPTIMIZE_REUSE_RESULTS: {
            session.getUser().checkAdmin();
            database.setOptimizeReuseResults(getIntValue() != 0);
            break;
        }
        case SetTypes.QUERY_TIMEOUT: {
            if (getIntValue() < 0) {
                throw DbException.getInvalidValueException("QUERY_TIMEOUT", getIntValue());
            }
            int value = getIntValue();
            session.setQueryTimeout(value);
            break;
        }
        case SetTypes.REDO_LOG_BINARY: {
            int value = getIntValue();
            session.setRedoLogBinary(value == 1);
            break;
        }
        case SetTypes.REFERENTIAL_INTEGRITY: {
            session.getUser().checkAdmin();
            int value = getIntValue();
            if (value < 0 || value > 1) {
                throw DbException.getInvalidValueException("REFERENTIAL_INTEGRITY", getIntValue());
            }
            database.setReferentialIntegrity(value == 1);
            break;
        }
        case SetTypes.SCHEMA: {
            Schema schema = database.getSchema(stringValue);
            session.setCurrentSchema(schema);
            break;
        }
        case SetTypes.SCHEMA_SEARCH_PATH: {
            session.setSchemaSearchPath(stringValueList);
            break;
        }
        case SetTypes.TRACE_LEVEL_FILE:
            session.getUser().checkAdmin();
            if (getCurrentObjectId() == 0) {
                // don't set the property when opening the database
                // this is for compatibility with older versions, because
                // this setting was persistent
                database.getTraceSystem().setLevelFile(getIntValue());
            }
            break;
        case SetTypes.TRACE_LEVEL_SYSTEM_OUT:
            session.getUser().checkAdmin();
            if (getCurrentObjectId() == 0) {
                // don't set the property when opening the database
                // this is for compatibility with older versions, because
                // this setting was persistent
                database.getTraceSystem().setLevelSystemOut(getIntValue());
            }
            break;
        case SetTypes.TRACE_MAX_FILE_SIZE: {
            if (getIntValue() < 0) {
                throw DbException.getInvalidValueException("TRACE_MAX_FILE_SIZE", getIntValue());
            }
            session.getUser().checkAdmin();
            int size = getIntValue() * 1024 * 1024;
            database.getTraceSystem().setMaxFileSize(size);
            addOrUpdateSetting(name, null, getIntValue());
            break;
        }
        case SetTypes.THROTTLE: {
            if (getIntValue() < 0) {
                throw DbException.getInvalidValueException("THROTTLE", getIntValue());
            }
            session.setThrottle(getIntValue());
            break;
        }
        case SetTypes.UNDO_LOG: {
            int value = getIntValue();
            if (value < 0 || value > 1) {
                throw DbException.getInvalidValueException("UNDO_LOG", getIntValue());
            }
            session.setUndoLogEnabled(value == 1);
            break;
        }
        case SetTypes.VARIABLE: {
            Expression expr = expression.optimize(session);
            session.setVariable(stringValue, expr.getValue(session));
            break;
        }
        case SetTypes.WRITE_DELAY: {
            if (getIntValue() < 0) {
                throw DbException.getInvalidValueException("WRITE_DELAY", getIntValue());
            }
            session.getUser().checkAdmin();
            database.setWriteDelay(getIntValue());
            addOrUpdateSetting(name, null, getIntValue());
            break;
        }
        case SetTypes.SERVER_TYPE: {
        	//do nothing
            break;
        }
        default:
            DbException.throwInternalError("type="+type);
        }
        // the meta data information has changed
        database.getNextModificationDataId();
        // query caches might be affected as well, for example
        // when changing the compatibility mode
        database.getNextModificationMetaId();
        return 0;
    }

    private int getIntValue() {
        expression = expression.optimize(session);
        return expression.getValue(session).getInt();
    }

    public void setInt(int value) {
        this.expression = ValueExpression.get(ValueInt.get(value));
    }

    public void setExpression(Expression expression) {
        this.expression = expression;
    }

    private void addOrUpdateSetting(String name, String s, int v) {
        addOrUpdateSetting(session, name, s, v);
    }

    private void addOrUpdateSetting(Session session, String name, String s, int v) {
        Database database = session.getDatabase();
        if (database.isReadOnly()) {
            return;
        }
        Setting setting = database.findSetting(name);
        boolean addNew = false;
        if (setting == null) {
            addNew = true;
            int id = getObjectId();
            setting = new Setting(database, id, name);
        }
        if (s != null) {
            if (!addNew && setting.getStringValue().equals(s)) {
                return;
            }
            setting.setStringValue(s);
        } else {
            if (!addNew && setting.getIntValue() == v) {
                return;
            }
            setting.setIntValue(v);
        }
        if (addNew) {
            database.addDatabaseObject(session, setting);
        } else {
            database.update(session, setting);
        }
    }

    public boolean needRecompile() {
        return false;
    }

    public ResultInterface queryMeta() {
        return null;
    }

    public void setStringArray(String[] list) {
        this.stringValueList = list;
    }

    public int getType() {
        return CommandInterface.SET;
    }

}
