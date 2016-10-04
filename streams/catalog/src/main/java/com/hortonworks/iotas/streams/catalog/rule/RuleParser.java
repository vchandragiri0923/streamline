/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.hortonworks.iotas.streams.catalog.rule;

import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import com.hortonworks.iotas.common.QueryParam;
import com.hortonworks.iotas.streams.catalog.RuleInfo;
import com.hortonworks.iotas.streams.catalog.StreamInfo;
import com.hortonworks.iotas.streams.catalog.UDFInfo;
import com.hortonworks.iotas.streams.catalog.service.StreamCatalogService;
import com.hortonworks.iotas.streams.layout.component.Stream;
import com.hortonworks.iotas.streams.layout.component.rule.expression.*;
import com.hortonworks.iotas.streams.layout.component.rule.sql.ExpressionGenerator;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.*;
import org.apache.calcite.tools.FrameworkConfig;
import org.apache.calcite.tools.Frameworks;
import org.apache.calcite.tools.Planner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static com.hortonworks.iotas.streams.layout.component.rule.expression.FieldExpression.STAR;

public class RuleParser {
    private static final Logger LOG = LoggerFactory.getLogger(RuleParser.class);

    private final StreamCatalogService catalogService;
    private final RuleInfo ruleInfo;
    private List<Stream> streams;
    private Projection projection;
    private Condition condition;
    private GroupBy groupBy;
    private Having having;
    private final Map<String, Udf> catalogUdfs = new HashMap<>();
    // the udfs used in the rule
    private final Set<String> referredUdfs = new HashSet<>();

    public RuleParser(StreamCatalogService catalogService, RuleInfo ruleInfo) {
        this.catalogService = catalogService;
        this.ruleInfo = ruleInfo;
        for (UDFInfo udfInfo: catalogService.listUDFs()) {
            catalogUdfs.put(udfInfo.getName().toUpperCase(),
                    new Udf(udfInfo.getName(), udfInfo.getClassName(), udfInfo.getType()));
        }
    }

    public void parse() {
        try {
            SchemaPlus schema = Frameworks.createRootSchema(true);
            FrameworkConfig config = Frameworks.newConfigBuilder().defaultSchema(schema).build();
            Planner planner = Frameworks.getPlanner(config);
            SqlSelect sqlSelect = (SqlSelect) planner.parse(ruleInfo.getSql());
            // FROM
            streams = parseStreams(sqlSelect);
            // SELECT
            projection = parseProjection(sqlSelect);
            // WHERE
            condition = parseCondition(sqlSelect);
            // GROUP BY
            groupBy = parseGroupBy(sqlSelect);
            // HAVING
            having = parseHaving(sqlSelect);
        } catch (Exception ex) {
            LOG.error("Got Exception while parsing rule {}", ruleInfo.getSql());
            throw new RuntimeException(ex);
        }
    }

    public List<Stream> getStreams() {
        return streams;
    }

    public Projection getProjection() {
        return projection;
    }

    public Condition getCondition() {
        return condition;
    }

    public GroupBy getGroupBy() {
        return groupBy;
    }

    public Having getHaving() {
        return having;
    }

    private List<Stream> parseStreams(SqlSelect sqlSelect) throws Exception {
        List<Stream> streams = new ArrayList<>();
        SqlNode sqlFrom = sqlSelect.getFrom();
        LOG.debug("from = {}", sqlFrom);
        if (sqlFrom instanceof SqlJoin) {
            throw new IllegalArgumentException("Sql join is not yet supported");
        } else if (sqlFrom instanceof SqlIdentifier) {
            streams.add(getStream(((SqlIdentifier) sqlFrom).getSimple()));
        }
        LOG.debug("Streams {}", streams);
        return streams;
    }

    private Projection parseProjection(SqlSelect sqlSelect) {
        Projection projection;
        ExpressionGenerator exprGenerator = new ExpressionGenerator(streams, catalogUdfs);
        ExpressionList exprList = (ExpressionList) sqlSelect.getSelectList().accept(exprGenerator);
        if (exprList.getExpressions().size() == 1 && exprList.getExpressions().get(0) == STAR) {
            projection = null;
        } else {
            projection = new Projection(exprList.getExpressions());
        }
        referredUdfs.addAll(exprGenerator.getReferredUdfs());
        LOG.debug("Projection {}", projection);
        return projection;
    }

    private Condition parseCondition(SqlSelect sqlSelect) {
        Condition condition = null;
        SqlNode where = sqlSelect.getWhere();
        if (where != null) {
            ExpressionGenerator exprGenerator = new ExpressionGenerator(streams, catalogUdfs);
            condition = new Condition(where.accept(exprGenerator));
            referredUdfs.addAll(exprGenerator.getReferredUdfs());
        }
        LOG.debug("Condition {}", condition);
        return condition;
    }

    private GroupBy parseGroupBy(SqlSelect sqlSelect) {
        GroupBy groupBy = null;
        SqlNodeList sqlGroupBy = sqlSelect.getGroup();
        if (sqlGroupBy != null) {
            ExpressionGenerator exprGenerator = new ExpressionGenerator(streams, catalogUdfs);
            ExpressionList exprList = (ExpressionList) sqlGroupBy.accept(exprGenerator);
            groupBy = new GroupBy(exprList.getExpressions());
            referredUdfs.addAll(exprGenerator.getReferredUdfs());
        }
        LOG.debug("GroupBy {}", groupBy);
        return groupBy;
    }

    private Having parseHaving(SqlSelect sqlSelect) {
        Having having = null;
        SqlNode sqlHaving = sqlSelect.getHaving();
        if (sqlHaving != null) {
            ExpressionGenerator exprGenerator = new ExpressionGenerator(streams, catalogUdfs);
            having = new Having(sqlHaving.accept(exprGenerator));
            referredUdfs.addAll(exprGenerator.getReferredUdfs());
        }
        LOG.debug("Having {}", having);
        return having;
    }

    // stream assumed to be unique within a topology
    private Stream getStream(final String streamName) throws Exception {
        Collection<StreamInfo> streamInfos = Collections2.filter(getStreamInfos(),
                new Predicate<StreamInfo>() {
                    @Override
                    public boolean apply(StreamInfo input) {
                        return input.getStreamId().equalsIgnoreCase(streamName);
                    }
                });
        if (streamInfos.isEmpty()) {
            throw new IllegalArgumentException("Stream '" + streamName + "' does not exist");
        } else if (streamInfos.size() != 1) {
            throw new IllegalArgumentException("Stream '" + streamName + "' is not unique");
        } else {
            StreamInfo streamInfo = streamInfos.iterator().next();
            return new Stream(streamInfo.getStreamId(), streamInfo.getFields());
        }
    }

    private Collection<StreamInfo> getStreamInfos() throws Exception {
        return catalogService.listStreamInfos(ImmutableList.<QueryParam>builder()
                .add(new QueryParam(RuleInfo.TOPOLOGY_ID, ruleInfo.getTopologyId().toString()))
                .build());
    }

    public Set<String> getReferredUdfs() {
        return referredUdfs;
    }

    @Override
    public String toString() {
        return "RuleParser{" +
                "streams=" + streams +
                ", projection=" + projection +
                ", condition=" + condition +
                ", groupBy=" + groupBy +
                ", having=" + having +
                ", referredUdfs=" + referredUdfs +
                ", ruleInfo=" + ruleInfo +
                '}';
    }
}
