/*
 * Copyright 2004-2011 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package com.codefollower.lealone.command.ddl;

import java.util.ArrayList;

import com.codefollower.lealone.command.CommandInterface;
import com.codefollower.lealone.command.dml.Query;
import com.codefollower.lealone.constant.Constants;
import com.codefollower.lealone.constant.ErrorCode;
import com.codefollower.lealone.dbobject.Schema;
import com.codefollower.lealone.dbobject.table.Table;
import com.codefollower.lealone.dbobject.table.TableView;
import com.codefollower.lealone.engine.Database;
import com.codefollower.lealone.engine.Session;
import com.codefollower.lealone.expression.Parameter;
import com.codefollower.lealone.message.DbException;

/**
 * This class represents the statement
 * CREATE VIEW
 */
public class CreateView extends SchemaCommand {

    private Query select;
    private String viewName;
    private boolean ifNotExists;
    private String selectSQL;
    private String[] columnNames;
    private String comment;
    private boolean orReplace;
    private boolean force;

    public CreateView(Session session, Schema schema) {
        super(session, schema);
    }

    public void setViewName(String name) {
        viewName = name;
    }

    public void setSelect(Query select) {
        this.select = select;
    }

    public void setIfNotExists(boolean ifNotExists) {
        this.ifNotExists = ifNotExists;
    }

    public void setSelectSQL(String selectSQL) {
        this.selectSQL = selectSQL;
    }

    public void setColumnNames(String[] cols) {
        this.columnNames = cols;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public void setOrReplace(boolean orReplace) {
        this.orReplace = orReplace;
    }

    public void setForce(boolean force) {
        this.force = force;
    }

    public int update() {
        session.commit(true);
        Database db = session.getDatabase();
        TableView view = null;
        Table old = getSchema().findTableOrView(session, viewName);
        if (old != null) {
            if (ifNotExists) {
                return 0;
            }
            if (!orReplace || !Table.VIEW.equals(old.getTableType())) {
                throw DbException.get(ErrorCode.VIEW_ALREADY_EXISTS_1, viewName);
            }
            view = (TableView) old;
        }
        int id = getObjectId();
        String querySQL;
        if (select == null) {
            querySQL = selectSQL;
        } else {
            ArrayList<Parameter> params = select.getParameters();
            if (params != null && params.size() > 0) {
                throw DbException.get(ErrorCode.FEATURE_NOT_SUPPORTED_1, "parameters in views");
            }
            querySQL = select.getPlanSQL();
        }
        Session sysSession = db.getSystemSession();
        try {
            if (view == null) {
                Schema schema = session.getDatabase().getSchema(session.getCurrentSchemaName());
                sysSession.setCurrentSchema(schema);
                view = new TableView(getSchema(), id, viewName, querySQL, null, columnNames, sysSession, false);
            } else {
                view.replace(querySQL, columnNames, sysSession, false, force);
            }
        } finally {
            sysSession.setCurrentSchema(db.getSchema(Constants.SCHEMA_MAIN));
        }
        if (comment != null) {
            view.setComment(comment);
        }
        if (old == null) {
            db.addSchemaObject(session, view);
        } else {
            db.update(session, view);
        }
        return 0;
    }

    public int getType() {
        return CommandInterface.CREATE_VIEW;
    }

}
