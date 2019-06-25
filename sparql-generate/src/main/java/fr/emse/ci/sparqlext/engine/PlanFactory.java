/*
 * Copyright 2016 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package fr.emse.ci.sparqlext.engine;

import org.apache.jena.query.SortCondition;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.core.VarExprList;
import org.apache.jena.sparql.expr.Expr;
import org.apache.jena.sparql.expr.ExprFunction;
import org.apache.jena.sparql.expr.ExprList;
import org.apache.jena.sparql.syntax.Element;
import fr.emse.ci.sparqlext.SPARQLExt;
import fr.emse.ci.sparqlext.SPARQLExtException;
import fr.emse.ci.sparqlext.function.library.FUN_Select_Call_Template;
import fr.emse.ci.sparqlext.query.SPARQLExtQuery;
import fr.emse.ci.sparqlext.syntax.ElementIterator;
import fr.emse.ci.sparqlext.syntax.ElementSource;
import fr.emse.ci.sparqlext.syntax.ElementSubExtQuery;
import java.util.ArrayList;
import java.util.List;
import org.apache.jena.sparql.core.Prologue;
import org.apache.jena.sparql.engine.binding.Binding;
import fr.emse.ci.sparqlext.lang.ParserSPARQLExt;
import fr.emse.ci.sparqlext.syntax.ElementGenerateTriplesBlock;
import java.util.Objects;
import org.apache.jena.graph.Node;
import org.apache.jena.sparql.syntax.ElementBind;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import fr.emse.ci.sparqlext.query.SPARQLExtQueryVisitor;
import fr.emse.ci.sparqlext.syntax.ElementBox;
import fr.emse.ci.sparqlext.syntax.ElementExpr;
import fr.emse.ci.sparqlext.syntax.ElementFormat;
import fr.emse.ci.sparqlext.syntax.ElementTGroup;
import fr.emse.ci.sparqlext.syntax.SPARQLExtElementVisitorBase;
import fr.emse.ci.sparqlext.utils.ST;
import java.util.UUID;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.QueryParseException;
import org.apache.jena.sparql.expr.E_Function;
import org.apache.jena.sparql.expr.E_Str;
import org.apache.jena.sparql.expr.ExprAggregator;
import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.expr.aggregate.AggGroupConcat;
import org.apache.jena.sparql.expr.aggregate.AggGroupConcatDistinct;
import org.apache.jena.sparql.expr.aggregate.Aggregator;
import org.apache.jena.sparql.expr.nodevalue.NodeValueString;
import org.apache.jena.sparql.syntax.ElementGroup;
import org.apache.jena.sparql.util.Context;

/**
 * A factory that creates a {@link RootPlan} from a SPARQL-Generate or
 * SPARQL-Template query. Then the {@code RootPlan} may be used to execute the
 * query.
 * <p>
 * A {@link SPARQLExtQuery} may be created from a string as follows:
 * <pre>{@code
 * String query;
 * SPARQLExtQuery query;
 *
 * Syntax syntax = SPARQLExt.SYNTAX;
 * query = (SPARQLExtQuery) QueryFactory.create(query, syntax);
 * }</pre>
 *
 * @author Maxime Lefrançois <maxime.lefrancois at emse.fr>
 */
public class PlanFactory {

    /**
     * The logger.
     */
    private static final Logger LOG = LoggerFactory.getLogger(PlanFactory.class);

    private PlanFactory() {

    }

    /**
     * A factory that creates a {@link RootPlan} from a SPARQL-Generate or
     * SPARQL-Template query.
     * <p>
     * A {@link SPARQLExtQuery} may be created from a string as follows:
     * <pre>{@code
     * String query;
     * SPARQLExtQuery query;
     *
     * Syntax syntax = SPARQLExt.syntaxSPARQLGenerate;
     * query = (SPARQLExtQuery) QueryFactory.create(query, syntax);
     * }</pre>
     *
     * @param query the SPARQL-Generate or SPARQL-Template Query.
     * @return the RootPlan that may be used to execute the SPARQL-Generate or
     * SPARQL-Template Query. query.
     */
    public static final RootPlan create(final SPARQLExtQuery query) {
        Objects.requireNonNull(query, "Query must not be null");
        return make(query, true);
    }

    /**
     * A factory that creates a {@link RootPlan} for a SPARQL-Generate
     * sub-query. Only for queries inside the GENERATE clause.
     *
     * @param query the SPARQL-Generate query.
     * @return the RootPlan that may be used to execute the SPARQL-Generate
     * query.
     */
    public static final RootPlan createPlanForSubQuery(final SPARQLExtQuery query) {
        Objects.requireNonNull(query, "Query must not be null");
        return make(query, false);
    }

    /**
     * A factory that creates a {@link RootPlan} from a SPARQL-Generate or
     * SPARQL-Template query.
     *
     * @param queryStr the string representation of the SPARQL-Generate or
     * SPARQL-Template Query.
     * @return the RootPlan that may be used to execute the SPARQL-Generate or
     * SPARQL-Template Query. query.
     */
    public static final RootPlan create(final String queryStr) {
        return create(queryStr, null);
    }
    
    /**
     * A factory that creates a {@link RootPlan} from a SPARQL-Generate or
     * SPARQL-Template query.
     *
     * @param queryStr the string representation of the SPARQL-Generate or
     * SPARQL-Template Query.
     * @param base the base URI, if not set explicitly in the query string
     * @return the RootPlan that may be used to execute the SPARQL-Generate or
     * SPARQL-Template Query. query.
     */
    public static final RootPlan create(final String queryStr, String base) {
        Objects.requireNonNull(queryStr, "Parameter string must not be null");
        SPARQLExtQuery query;
        try {
            query = (SPARQLExtQuery) QueryFactory.create(queryStr, base,
                    SPARQLExt.SYNTAX);
        } catch (QueryParseException ex) {
            throw new SPARQLExtException(
                    "Error while parsing the query \n" + queryStr, ex);
        }
        LOG.trace("Creating plan for query: \n" + query);
        return create(query);
    }

    /**
     * Makes a {@code RootPlan} for a SPARQL-Generate or SPARQL-Template query.
     *
     * @param query the SPARQL-Generate or SPARQL-Template Query.
     * @return the RootPlan.
     */
    private static RootPlan make(final SPARQLExtQuery query,
            final boolean initial) {
        Objects.requireNonNull(query, "The query must not be null");
        LOG.trace("Making plan for query\n" + query);

        if (query.hasEmbeddedExpressions()) {
            LOG.debug("Query has embedded expressions. Will be normalized");
            String qs = query.toString();
            SPARQLExtQuery query2;
            if (query.isNamedSubQuery() || !query.hasName() && query.hasSignature()) {
                query2 = (SPARQLExtQuery) ParserSPARQLExt.parseSubQuery(query, qs);
            } else {
                query2 = (SPARQLExtQuery) QueryFactory.create(qs, SPARQLExt.SYNTAX);
            }
            query2.normalize();
            return make(query2, initial);
        }

        List<BindingsClausePlan> iteratorAndSourcePlans = new ArrayList<>();
        if (query.hasBindingClauses()) {
            for (Element el : query.getBindingClauses()) {
                BindingsClausePlan iteratorOrSourcePlan;
                if (el instanceof ElementIterator) {
                    ElementIterator elementIterator = (ElementIterator) el;
                    iteratorOrSourcePlan = makeIteratorPlan(elementIterator, query.isTemplateType());
                } else if (el instanceof ElementSource) {
                    ElementSource elementSource = (ElementSource) el;
                    iteratorOrSourcePlan = makeSourcePlan(elementSource);
                } else if (el instanceof ElementBind) {
                    ElementBind elementBind = (ElementBind) el;
                    iteratorOrSourcePlan = makeBindPlan(elementBind);
                } else {
                    throw new UnsupportedOperationException("should not reach"
                            + " this point");
                }
                iteratorAndSourcePlans.add(iteratorOrSourcePlan);
            }
        }
        SelectPlan selectPlan = makeSelectPlan(query);
        ExecutionPlan outputFormPlan = null;
        if (query.isNamedSubQuery()) {
            outputFormPlan = makeNamedSubQueryPlan(query);
        } else if (query.hasGenerateClause()) {
            outputFormPlan = makeGenerateFormPlan(query);
        }
        return new RootPlanImpl(
                query, iteratorAndSourcePlans,
                selectPlan, outputFormPlan, initial);
    }

    /**
     * Makes the plan for a SPARQL ITERATOR clause.
     *
     * @param elementIterator the SPARQL ITERATOR
     * @return -
     */
    static IteratorPlan makeIteratorPlan(
            final ElementIterator elementIterator,
            boolean sync)
            throws SPARQLExtException {
        Objects.requireNonNull(elementIterator, "The Iterator must not be null");

        List<Var> vars = elementIterator.getVars();
        Expr expr = elementIterator.getExpr();

        Objects.requireNonNull(vars, "The variables of the Iterator must not be null");
        Objects.requireNonNull(expr, "The Expr in the iterator must not be null");
        checkIsTrue(expr.isFunction(), "Iterator should be a function:"
                + " <iri>(...) AS ?var1 ?var2 ...");

        ExprFunction function = expr.getFunction();
        String iri = function.getFunctionIRI();
        ExprList exprList = new ExprList(function.getArgs());
        if (sync) {
            return new SyncIteratorPlan(iri, exprList, vars);
        } else {
            return new AsyncIteratorPlan(iri, exprList, vars);
        }
    }

    /**
     * Makes the plan for a SPARQL SOURCE clause.
     *
     * @param elementSource the SPARQL SOURCE
     * @return -
     */
    private static BindOrSourcePlan makeSourcePlan(
            final ElementSource elementSource) throws SPARQLExtException {
        Objects.requireNonNull(elementSource, "The Source must not be null");

        Node node = elementSource.getSource();
        Node accept = elementSource.getAccept();
        Var var = elementSource.getVar();

        Objects.requireNonNull(node, "The source must not be null");
        checkIsTrue(node.isURI() || node.isVariable(), "The source must be a"
                + " URI or a variable. Got " + node);
        // accept may be null
        checkIsTrue(accept == null || accept.isVariable() || accept.isURI(),
                "The accept must be null, a variable or a URI. Got " + accept);
        Objects.requireNonNull(var, "The variable must not be null.");

        return new SourcePlan(node, accept, var);
    }

    /**
     * Makes the plan for a SPARQL BIND clause.
     *
     * @param elementBind the SPARQL BIND
     * @return -
     */
    private static BindOrSourcePlan makeBindPlan(
            final ElementBind elementBind) throws SPARQLExtException {
        Objects.requireNonNull(elementBind, "The Bind element must not be null");

        Var var = elementBind.getVar();
        Expr expr = elementBind.getExpr();

        Objects.requireNonNull(var, "The source must not be null");
        Objects.requireNonNull(expr, "The expression must not be null.");

        return new BindPlan(expr, var);
    }

    /**
     * Makes the plan for a SPARQL {@code GENERATE ?source() } clause.
     *
     * @param query the query for which the plan is created.
     * @return -
     */
    private static NamedSubQueryPlan makeNamedSubQueryPlan(
            final SPARQLExtQuery query) throws SPARQLExtException {
        Objects.requireNonNull(query, "The query must not be null");
        checkIsTrue(query.isNamedSubQuery(), "Query was expected to be a named "
                + "sub query");
        return new NamedSubQueryPlan(query.getBaseURI(), query.getName(), query.getCallParameters());
    }

    /**
     * Makes the plan for a SPARQL {@code GENERATE {}} clause.
     *
     * @param query the query for which the plan is created.
     * @return -
     */
    private static ExecutionPlan makeGenerateFormPlan(
            final SPARQLExtQuery query) throws SPARQLExtException {
        Objects.requireNonNull(query, "The query must not be null");
        checkIsTrue(query.hasGenerateClause(), "Query was expected to be of"
                + " type GENERATE {...} ...");
        List<ExecutionPlan> subPlans = new ArrayList<>();
        for (Element elem : query.getGenerateClause()) {
            ExecutionPlan plan;
            if (elem instanceof ElementGenerateTriplesBlock) {
                ElementGenerateTriplesBlock sub
                        = (ElementGenerateTriplesBlock) elem;
                plan = new GenerateTriplesPlan(sub.getPattern());
            } else if (elem instanceof ElementSubExtQuery) {
                ElementSubExtQuery sub = (ElementSubExtQuery) elem;
                plan = make(sub.getQuery(), false);
            } else {
                throw new SPARQLExtException("should not reach this"
                        + " point");
            }
            subPlans.add(plan);
        }
        return new GenerateTemplatePlan(subPlans);
    }

    /**
     * Create a SPARQL SELECT Query from a SPARQL-Ext Query. Hence one may rely
     * on the existing SPARQL engine to do most of the job.
     *
     * @param query the SPARQL-Generate query
     * @return the SPARQL SELECT Query.
     */
    private static SelectPlan makeSelectPlan(final SPARQLExtQuery query) {
        Objects.requireNonNull(query, "The query must not be null");
        final SPARQLExtQuery output = new SPARQLExtQuery();
        query.visit(new SPARQLExtQueryVisitor() {
            @Override
            public void startVisit(final Query query) {
            }

            @Override
            public void visitResultForm(final Query query) {
                output.setQuerySelectType();
            }

            @Override
            public void visitPrologue(final Prologue prologue) {
                output.setPrefixMapping(query.getPrefixMapping());
                String b = query.getBaseURI();
                output.setBaseURI(b);
            }

            @Override
            public void visitSelectResultForm(final Query q) {
                SPARQLExtQuery query = asSPARQLExtQuery(q);
                output.setName(query.getName());
                output.setSignature(query.getSignature());
                output.setCallParameters(query.getCallParameters());
                output.setDistinct(query.isDistinct());
                output.setReduced(query.isReduced());
                output.setQueryResultStar(query.isQueryResultStar());
                VarExprList project = query.getProject();
                project.forEachVar((v) -> {
                    output.addResultVar(v, project.getExpr(v));
                });
            }

            @Override
            public void visitConstructResultForm(final Query query) {
                throw new SPARQLExtException("should not reach this"
                        + " point");
            }

            @Override
            public void visitDescribeResultForm(final Query query) {
                throw new SPARQLExtException("should not reach this"
                        + " point");
            }

            @Override
            public void visitAskResultForm(final Query query) {
                throw new SPARQLExtException("should not reach this"
                        + " point");
            }

            @Override
            public void visitDatasetDecl(final Query q) {
                SPARQLExtQuery query = asSPARQLExtQuery(q);
                output.getFromClauses().addAll(query.getFromClauses());
            }

            @Override
            public void visitQueryPattern(final Query query) {
                if (query.getQueryPattern() != null) {
                    if (output.getQueryPattern() != null) {
                        ElementGroup group = new ElementGroup();
                        group.addElement(query.getQueryPattern());
                        group.addElement(output.getQueryPattern());
                        output.setQueryPattern(group);
                    } else {
                        Element el = query.getQueryPattern();
                        output.setQueryPattern(el);
                    }
                } else {
                    output.setQueryPattern(new ElementGroup());
                }
            }

            @Override
            public void visitBindingClauses(SPARQLExtQuery query) {
                if (query.isSelectType()) {
                    return;
                }
                if (query.hasAggregators() || query.hasGroupBy()) {
                    return;
                }
                for (Element el : query.getBindingClauses()) {
                    if (el instanceof ElementIterator) {
                        ElementIterator elementIterator = (ElementIterator) el;
                        output.addProjectVars(elementIterator.getVars());
                    } else if (el instanceof ElementSource) {
                        ElementSource elementSource = (ElementSource) el;
                        output.addResultVar(elementSource.getVar());
                    } else if (el instanceof ElementBind) {
                        ElementBind elementBind = (ElementBind) el;
                        output.addResultVar(elementBind.getVar());
                    } else {
                        throw new UnsupportedOperationException("should not reach"
                                + " this point");
                    }
                }
            }

            @Override
            public void visitGroupBy(final Query query) {
                if (query.hasGroupBy()) {
                    if (!query.getGroupBy().isEmpty()) {
                        VarExprList namedExprs = query.getGroupBy();
                        for (Var var : namedExprs.getVars()) {
                            Expr expr = namedExprs.getExpr(var);
                            if (expr != null) {
                                output.addGroupBy(var, expr);
                                if (!query.isSelectType()) {
                                    output.addResultVar(var);
                                }
                            } else {
                                output.addGroupBy(var.getVarName());
                                if (!query.isSelectType()) {
                                    output.addResultVar(var);
                                }
                            }
                        }
                    }
                }
            }

            @Override
            public void visitHaving(final Query query) {
                if (query.hasHaving()) {
                    for (Expr expr : query.getHavingExprs()) {
                        output.addHavingCondition(expr);
                    }
                }
            }

            @Override
            public void visitOrderBy(final Query query) {
                if (query.hasOrderBy()) {
                    for (SortCondition sc : query.getOrderBy()) {
                        output.addOrderBy(sc);
                    }
                }
            }

            @Override
            public void visitOffset(final Query query) {
                if (query.hasOffset()) {
                    output.setOffset(query.getOffset());
                }
            }

            @Override
            public void visitLimit(final Query query) {
                if (query.hasLimit()) {
                    output.setLimit(query.getLimit());
                }
            }

            @Override
            public void visitPostSelect(SPARQLExtQuery query) {
                if (query.isSelectType() || query.isTemplateType()) {
                    return;
                }
                int qtype = query.getQueryType();
                query.setQuerySelectType();
                if (!query.hasAggregators() && !query.hasGroupBy()) {
                    output.setQueryResultStar(true);
                    output.setResultVars();
                }
                if (query.hasPostSelect()) {
                    query.getPostSelect().forEachExpr((v, e) -> {
                        output.addResultVar(v, e);
                    });
                }
                output.setQueryResultStar(false);
                switch (qtype) {
                    case SPARQLExtQuery.QueryTypeGenerate:
                        query.setQueryGenerateType();
                        break;
                    case SPARQLExtQuery.QueryTypeTemplate:
                        query.setQueryTemplateType();
                        break;
                    case SPARQLExtQuery.QueryTypePerform:
                        query.setQueryPerformType();
                        break;
                    case SPARQLExtQuery.QueryTypeSelect:
                        query.setQuerySelectType();
                        break;
                    default:
                        LOG.warn("Did not recognize type. Was " + qtype + " " + query.toString());
                }
            }

            @Override
            public void visitValues(final Query query) {
                if (query.hasValues() && !query.hasAggregators()) {
                    List<Var> variables = query.getValuesVariables();
                    List<Binding> values = query.getValuesData();
                    output.setValuesDataBlock(variables, values);
                    output.addProjectVars(variables);
                }
            }

            @Override
            public void visitGenerateClause(SPARQLExtQuery query) {
            }

            @Override
            public void visitFunctionExpression(SPARQLExtQuery query) {
                Var var = Var.alloc("out");
                ElementBind bind = new ElementBind(var, query.getFunctionExpression());
                output.setQueryPattern(bind);
                output.addResultVar(var);
            }

            @Override
            public void visitTemplateClause(SPARQLExtQuery query) {
                final TProc tproc = new TProc();
                Var var = Var.alloc("out");
                List<Element> templateElements = query.getTemplateClause();
                if (templateElements.size() == 1) {
                    templateElements.get(0).visit(tproc);
                    output.addResultVar(var, tproc.result);
                } else {
                    List<Expr> texprs = new ArrayList<>();
                    for (int i = 0; i < templateElements.size(); i++) {
                        templateElements.get(i).visit(tproc);
                        Expr result = tproc.result;
                        if (result instanceof E_Function && ((E_Function) result).getFunctionIRI().equals(ST.concat)) {
                            texprs.addAll(((E_Function) result).getArgs());
                        } else {
                            texprs.add(result);
                        }
                    }
                    Expr concat = new E_Function(ST.concat, new ExprList(texprs));
                    output.addResultVar(var, concat);
                }
            }

            @Override
            public void visitPerformClause(SPARQLExtQuery query) {
            }

            @Override
            public void finishVisit(final Query query) {
                if (output.getProjectVars().isEmpty()) {
                    output.setQueryResultStar(true);
                }
            }

            @Override
            public void visitPragma(SPARQLExtQuery query) {
            }

        });
        LOG.trace("raw select query is " + output);
        Query newQuery = output.cloneQuery();
        if (newQuery.hasAggregators()) {
            if (query.hasSignature()) {
                query.getSignature().forEach(newQuery::addGroupBy);
            }
        }
        LOG.trace(String.format("Generated SELECT query\n%s", newQuery.toString()));
        return new SelectPlan(newQuery, query.isSelectType(), query.getSignature());
    }

    /**
     * Checks that the test is true.
     *
     * @param test boolean to check.
     * @param msg message used to throw the exception if the boolean is false.
     * @throws IllegalArgumentException Thrown if the boolean is false.
     */
    private static void checkIsTrue(final boolean test, final String msg) {
        if (!test) {
            throw new IllegalArgumentException(msg);
        }
    }

    private static class TProc extends SPARQLExtElementVisitorBase {

        Expr result = null;

        @Override
        public void visit(ElementExpr el) {
            if (el.getExpr() instanceof NodeValueString) {
                result = el.getExpr();
            } else {
                result = new E_Str(el.getExpr());
            }
        }

        @Override
        public void visit(ElementBox el) {
            final List<Expr> texprs = new ArrayList<>();
            texprs.add(new E_Function(ST.incr, new ExprList()));
            List<Element> elements = el.getTExpressions();
            for (int i = 0; i < elements.size(); i++) {
                elements.get(i).visit(this);
                if (result instanceof E_Function && ((E_Function) result).getFunctionIRI().equals(ST.concat)) {
                    texprs.addAll(((E_Function) result).getArgs());
                } else {
                    texprs.add(result);
                }
            }
            texprs.add(new E_Function(ST.decr, new ExprList()));
            result = new E_Function(ST.concat, new ExprList(texprs));
        }

        @Override
        public void visit(ElementFormat el) {
            final List<Expr> texprs = new ArrayList<>();
            texprs.add(el.getExpr().getExpr());
            el.getTExpressions().forEach((e) -> {
                e.visit(this);
                texprs.add(result);
            });
            result = new E_Function(ST.format, new ExprList(texprs));
        }

        @Override
        public void visit(ElementTGroup el) {
            String sep = el.getSeparator();
            final List<Expr> texprs = new ArrayList<>();
            el.getTExpressions().forEach((e) -> {
                e.visit(this);
                texprs.add(result);
            });
            final Expr concat = new E_Function(ST.concat, new ExprList(texprs));
            final Aggregator groupConcat;
            if (el.isDistinct()) {
                groupConcat = new AggGroupConcatDistinct(concat, sep);
            } else {
                groupConcat = new AggGroupConcat(concat, sep);
            }
            Var var = Var.alloc(UUID.randomUUID().toString().substring(0, 8));
            result = new ExprAggregator(var, groupConcat);
        }

        @Override
        public void visit(ElementSubExtQuery el) {
            SPARQLExtQuery query = el.getQuery();
            if (query.isNamedSubQuery()) {
                String qs = query.toString();
                SPARQLExtQuery query2 = (SPARQLExtQuery) ParserSPARQLExt.parseSubQuery(query, qs);
                query2.setQuerySelectType();
                query2.setQueryResultStar(true);
                query2.setName(null);
                query2.setCallParameters(null);
                if(query2.getQueryPattern()==null) {
                    query2.setQueryPattern(new ElementGroup());
                }
                NodeValue selectQuery = NodeValue.makeNode(query2.toString(), null, SPARQLExt.MEDIA_TYPE_URI);
                ExprList exprList = new ExprList(selectQuery);
                exprList.add(query.getName());
                exprList.addAll(query.getCallParameters());
                result = new E_Function(FUN_Select_Call_Template.URI, exprList);
            } else {
                NodeValue n = NodeValue.makeNode(query.toString(), null, SPARQLExt.MEDIA_TYPE_URI);
                result = new E_Function(ST.callTemplate, new ExprList(n));
            }
        }

    };

}
