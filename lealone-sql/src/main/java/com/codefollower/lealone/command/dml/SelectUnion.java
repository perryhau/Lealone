/*
 * Copyright 2004-2011 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package com.codefollower.lealone.command.dml;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import com.codefollower.lealone.command.CommandInterface;
import com.codefollower.lealone.constant.ErrorCode;
import com.codefollower.lealone.constant.SysProperties;
import com.codefollower.lealone.dbobject.table.Column;
import com.codefollower.lealone.dbobject.table.ColumnResolver;
import com.codefollower.lealone.dbobject.table.Table;
import com.codefollower.lealone.dbobject.table.TableFilter;
import com.codefollower.lealone.engine.Session;
import com.codefollower.lealone.expression.Expression;
import com.codefollower.lealone.expression.ExpressionColumn;
import com.codefollower.lealone.expression.ExpressionVisitor;
import com.codefollower.lealone.expression.Parameter;
import com.codefollower.lealone.expression.ValueExpression;
import com.codefollower.lealone.message.DbException;
import com.codefollower.lealone.result.LocalResult;
import com.codefollower.lealone.result.ResultInterface;
import com.codefollower.lealone.result.ResultTarget;
import com.codefollower.lealone.result.SortOrder;
import com.codefollower.lealone.util.New;
import com.codefollower.lealone.util.StringUtils;
import com.codefollower.lealone.value.Value;
import com.codefollower.lealone.value.ValueInt;
import com.codefollower.lealone.value.ValueNull;

/**
 * Represents a union SELECT statement.
 */
public class SelectUnion extends Query {

    /**
     * The type of a UNION statement.
     */
    public static final int UNION = 0;

    /**
     * The type of a UNION ALL statement.
     */
    public static final int UNION_ALL = 1;

    /**
     * The type of an EXCEPT statement.
     */
    public static final int EXCEPT = 2;

    /**
     * The type of an INTERSECT statement.
     */
    public static final int INTERSECT = 3;

    private int unionType;
    private final Query left;
    private Query right;
    private ArrayList<Expression> expressions;
    private Expression[] expressionArray;
    private ArrayList<SelectOrderBy> orderList;
    private SortOrder sort;
    private boolean isPrepared, checkInit;
    private boolean isForUpdate;

    public SelectUnion(Session session, Query query) {
        super(session);
        this.left = query;
    }

    public void setUnionType(int type) {
        this.unionType = type;
    }

    public int getUnionType() {
        return unionType;
    }

    public void setRight(Query select) {
        right = select;
    }

    public Query getLeft() {
        return left;
    }

    public Query getRight() {
        return right;
    }

    public void setSQL(String sql) {
        this.sqlStatement = sql;
    }

    public void setOrder(ArrayList<SelectOrderBy> order) {
        orderList = order;
    }

    private Value[] convert(Value[] values, int columnCount) {
        for (int i = 0; i < columnCount; i++) {
            Expression e = expressions.get(i);
            values[i] = values[i].convertTo(e.getType());
        }
        return values;
    }

    public ResultInterface queryMeta() {
        int columnCount = left.getColumnCount();
        LocalResult result = new LocalResult(session, expressionArray, columnCount);
        result.done();
        return result;
    }

    public LocalResult getEmptyResult() {
        int columnCount = left.getColumnCount();
        return new LocalResult(session, expressionArray, columnCount);
    }

    protected LocalResult queryWithoutCache(int maxRows, ResultTarget target) {
        if (maxRows != 0) {
            // maxRows is set (maxRows 0 means no limit)
            int l;
            if (limitExpr == null) {
                l = -1;
            } else {
                Value v = limitExpr.getValue(session);
                l = v == ValueNull.INSTANCE ? -1 : v.getInt();
            }
            if (l < 0) {
                // for limitExpr, 0 means no rows, and -1 means no limit
                l = maxRows;
            } else {
                l = Math.min(l, maxRows);
            }
            limitExpr = ValueExpression.get(ValueInt.get(l));
        }
        if (session.getDatabase().getSettings().optimizeInsertFromSelect) {
            if (unionType == UNION_ALL && target != null) {
                if (sort == null && !distinct && maxRows == 0 && offsetExpr == null && limitExpr == null) {
                    left.query(0, target);
                    right.query(0, target);
                    return null;
                }
            }
        }
        int columnCount = left.getColumnCount();
        LocalResult result = new LocalResult(session, expressionArray, columnCount);
        if (sort != null) {
            result.setSortOrder(sort);
        }
        if (distinct) {
            left.setDistinct(true);
            right.setDistinct(true);
            result.setDistinct();
        }
        if (randomAccessResult) {
            result.setRandomAccess();
        }
        switch (unionType) {
        case UNION:
        case EXCEPT:
            left.setDistinct(true);
            right.setDistinct(true);
            result.setDistinct();
            break;
        case UNION_ALL:
            break;
        case INTERSECT:
            left.setDistinct(true);
            right.setDistinct(true);
            break;
        default:
            DbException.throwInternalError("type=" + unionType);
        }
        ResultInterface l = left.query(0);
        ResultInterface r = right.query(0);
        l.reset();
        r.reset();
        switch (unionType) {
        case UNION_ALL:
        case UNION: {
            while (l.next()) {
                result.addRow(convert(l.currentRow(), columnCount));
            }
            while (r.next()) {
                result.addRow(convert(r.currentRow(), columnCount));
            }
            break;
        }
        case EXCEPT: {
            while (l.next()) {
                result.addRow(convert(l.currentRow(), columnCount));
            }
            while (r.next()) {
                result.removeDistinct(convert(r.currentRow(), columnCount));
            }
            break;
        }
        case INTERSECT: {
            LocalResult temp = new LocalResult(session, expressionArray, columnCount);
            temp.setDistinct();
            temp.setRandomAccess();
            while (l.next()) {
                temp.addRow(convert(l.currentRow(), columnCount));
            }
            while (r.next()) {
                Value[] values = convert(r.currentRow(), columnCount);
                if (temp.containsDistinct(values)) {
                    result.addRow(values);
                }
            }
            break;
        }
        default:
            DbException.throwInternalError("type=" + unionType);
        }
        if (offsetExpr != null) {
            result.setOffset(offsetExpr.getValue(session).getInt());
        }
        if (limitExpr != null) {
            Value v = limitExpr.getValue(session);
            if (v != ValueNull.INSTANCE) {
                result.setLimit(v.getInt());
            }
        }
        result.done();
        if (target != null) {
            while (result.next()) {
                target.addRow(result.currentRow());
            }
            result.close();
            return null;
        }
        return result;
    }

    public void init() {
        if (SysProperties.CHECK && checkInit) {
            DbException.throwInternalError();
        }
        checkInit = true;
        left.init();
        right.init();
        int len = left.getColumnCount();
        if (len != right.getColumnCount()) {
            throw DbException.get(ErrorCode.COLUMN_COUNT_DOES_NOT_MATCH);
        }
        ArrayList<Expression> le = left.getExpressions();
        // set the expressions to get the right column count and names,
        // but can't validate at this time
        expressions = New.arrayList();
        for (int i = 0; i < len; i++) {
            Expression l = le.get(i);
            expressions.add(l);
        }
    }

    public void prepare() {
        if (isPrepared) {
            // sometimes a subquery is prepared twice (CREATE TABLE AS SELECT)
            return;
        }
        if (SysProperties.CHECK && !checkInit) {
            DbException.throwInternalError("not initialized");
        }
        isPrepared = true;
        left.prepare();
        right.prepare();
        int len = left.getColumnCount();
        // set the correct expressions now
        expressions = New.arrayList();
        ArrayList<Expression> le = left.getExpressions();
        ArrayList<Expression> re = right.getExpressions();
        for (int i = 0; i < len; i++) {
            Expression l = le.get(i);
            Expression r = re.get(i);
            int type = Value.getHigherOrder(l.getType(), r.getType());
            long prec = Math.max(l.getPrecision(), r.getPrecision());
            int scale = Math.max(l.getScale(), r.getScale());
            int displaySize = Math.max(l.getDisplaySize(), r.getDisplaySize());
            Column col = new Column(l.getAlias(), type, prec, scale, displaySize);
            Expression e = new ExpressionColumn(session.getDatabase(), col);
            expressions.add(e);
        }
        if (orderList != null) {
            initOrder(session, expressions, null, orderList, getColumnCount(), true, null);
            sort = prepareOrder(orderList, expressions.size());
            orderList = null;
        }
        expressionArray = new Expression[expressions.size()];
        expressions.toArray(expressionArray);
    }

    public double getCost() {
        return left.getCost() + right.getCost();
    }

    public HashSet<Table> getTables() {
        HashSet<Table> set = left.getTables();
        set.addAll(right.getTables());
        return set;
    }

    public ArrayList<Expression> getExpressions() {
        return expressions;
    }

    public void setForUpdate(boolean forUpdate) {
        left.setForUpdate(forUpdate);
        right.setForUpdate(forUpdate);
        isForUpdate = forUpdate;
    }

    public int getColumnCount() {
        return left.getColumnCount();
    }

    public void mapColumns(ColumnResolver resolver, int level) {
        left.mapColumns(resolver, level);
        right.mapColumns(resolver, level);
    }

    public void setEvaluatable(TableFilter tableFilter, boolean b) {
        left.setEvaluatable(tableFilter, b);
        right.setEvaluatable(tableFilter, b);
    }

    public void addGlobalCondition(Parameter param, int columnId, int comparisonType) {
        addParameter(param);
        switch (unionType) {
        case UNION_ALL:
        case UNION:
        case INTERSECT: {
            left.addGlobalCondition(param, columnId, comparisonType);
            right.addGlobalCondition(param, columnId, comparisonType);
            break;
        }
        case EXCEPT: {
            left.addGlobalCondition(param, columnId, comparisonType);
            break;
        }
        default:
            DbException.throwInternalError("type=" + unionType);
        }
    }

    public String getPlanSQL() {
        StringBuilder buff = new StringBuilder();
        buff.append('(').append(left.getPlanSQL()).append(')');
        switch (unionType) {
        case UNION_ALL:
            buff.append("\nUNION ALL\n");
            break;
        case UNION:
            buff.append("\nUNION\n");
            break;
        case INTERSECT:
            buff.append("\nINTERSECT\n");
            break;
        case EXCEPT:
            buff.append("\nEXCEPT\n");
            break;
        default:
            DbException.throwInternalError("type=" + unionType);
        }
        buff.append('(').append(right.getPlanSQL()).append(')');
        Expression[] exprList = expressions.toArray(new Expression[expressions.size()]);
        if (sort != null) {
            buff.append("\nORDER BY ").append(sort.getSQL(exprList, exprList.length));
        }
        if (limitExpr != null) {
            buff.append("\nLIMIT ").append(StringUtils.unEnclose(limitExpr.getSQL()));
            if (offsetExpr != null) {
                buff.append("\nOFFSET ").append(StringUtils.unEnclose(offsetExpr.getSQL()));
            }
        }
        if (sampleSize != 0) {
            buff.append("\nSAMPLE_SIZE ").append(sampleSize);
        }
        if (isForUpdate) {
            buff.append("\nFOR UPDATE");
        }
        return buff.toString();
    }

    public LocalResult query(int limit, ResultTarget target) {
        // union doesn't always know the parameter list of the left and right queries
        return queryWithoutCache(limit, target);
    }

    public boolean isEverything(ExpressionVisitor visitor) {
        return left.isEverything(visitor) && right.isEverything(visitor);
    }

    public boolean isReadOnly() {
        return left.isReadOnly() && right.isReadOnly();
    }

    public void updateAggregate(Session s) {
        left.updateAggregate(s);
        right.updateAggregate(s);
    }

    public void fireBeforeSelectTriggers() {
        left.fireBeforeSelectTriggers();
        right.fireBeforeSelectTriggers();
    }

    public int getType() {
        return CommandInterface.SELECT;
    }

    public boolean allowGlobalConditions() {
        return left.allowGlobalConditions() && right.allowGlobalConditions();
    }

    @Override
    public List<TableFilter> getTopFilters() {
        List<TableFilter> filters = left.getTopFilters();
        filters.addAll(right.getTopFilters());
        return filters;
    }
}
