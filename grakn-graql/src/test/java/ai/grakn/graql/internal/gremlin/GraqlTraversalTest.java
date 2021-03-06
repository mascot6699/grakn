/*
 * Grakn - A Distributed Semantic Database
 * Copyright (C) 2016  Grakn Labs Limited
 *
 * Grakn is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Grakn is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Grakn. If not, see <http://www.gnu.org/licenses/gpl.txt>.
 */

package ai.grakn.graql.internal.gremlin;

import ai.grakn.GraknGraph;
import ai.grakn.concept.ConceptId;
import ai.grakn.concept.Label;
import ai.grakn.concept.Role;
import ai.grakn.graql.Graql;
import ai.grakn.concept.RelationType;
import ai.grakn.graql.Pattern;
import ai.grakn.graql.Var;
import ai.grakn.graql.VarPattern;
import ai.grakn.graql.admin.Conjunction;
import ai.grakn.graql.admin.VarPatternAdmin;
import ai.grakn.graql.internal.gremlin.fragment.Fragment;
import ai.grakn.graql.internal.gremlin.fragment.Fragments;
import ai.grakn.util.CommonUtil;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import org.hamcrest.Matcher;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static ai.grakn.graql.Graql.and;
import static ai.grakn.graql.Graql.eq;
import static ai.grakn.graql.Graql.gt;
import static ai.grakn.graql.Graql.var;
import static ai.grakn.graql.internal.gremlin.GraqlMatchers.feature;
import static ai.grakn.graql.internal.gremlin.GraqlMatchers.satisfies;
import static ai.grakn.graql.internal.gremlin.fragment.Fragments.id;
import static ai.grakn.graql.internal.gremlin.fragment.Fragments.inIsa;
import static ai.grakn.graql.internal.gremlin.fragment.Fragments.inRelates;
import static ai.grakn.graql.internal.gremlin.fragment.Fragments.outIsa;
import static ai.grakn.graql.internal.gremlin.fragment.Fragments.outRelates;
import static ai.grakn.graql.internal.gremlin.fragment.Fragments.value;
import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;
import static org.hamcrest.CoreMatchers.anyOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class GraqlTraversalTest {

    private static final Var a = Graql.var("a");
    private static final Var b = Graql.var("b");
    private static final Var c = Graql.var("c");
    private static final Var x = Graql.var("x");
    private static final Var y = Graql.var("y");
    private static final Var z = Graql.var("z");
    private static final Var xx = Graql.var("xx");
    private static final Var yy = Graql.var("yy");
    private static final Var zz = Graql.var("zz");
    private static final Fragment xId = id(x, ConceptId.of("Titanic"));
    private static final Fragment xValue = value(x, eq("hello").admin());
    private static final Fragment yId = id(y, ConceptId.of("movie"));
    private static final Fragment xIsaY = outIsa(x, y);
    private static final Fragment yTypeOfX = inIsa(y, x);

    private static final GraqlTraversal fastIsaTraversal = traversal(yId, yTypeOfX);
    private static GraknGraph graph;

    @BeforeClass
    public static void setUp() {
        graph = mock(GraknGraph.class);

        // We have to mock out the `subTypes` call because the shortcut edge optimisation checks it

        Label wifeLabel = Label.of("wife");
        Role wife = mock(Role.class);

        when(graph.getOntologyConcept(wifeLabel)).thenAnswer(invocation -> {
            //noinspection unchecked
            when(wife.subs()).thenReturn((Collection) ImmutableSet.of(wife));
            when(wife.getLabel()).thenReturn(wifeLabel);
            return wife;
        });

        Label marriageLabel = Label.of("marriage");
        RelationType marriage = mock(RelationType.class);

        when(graph.getOntologyConcept(marriageLabel)).thenAnswer(invocation -> {
            //noinspection unchecked
            when(marriage.subs()).thenReturn((Collection) ImmutableSet.of(marriage));
            when(marriage.getLabel()).thenReturn(marriageLabel);
            return marriage;
        });
    }

    @Test
    public void testComplexityIndexVsIsa() {
        GraqlTraversal indexTraversal = traversal(xId);
        assertFaster(indexTraversal, fastIsaTraversal);
    }

    @Test
    public void testComplexityFastIsaVsSlowIsa() {
        GraqlTraversal slowIsaTraversal = traversal(xIsaY, yId);
        assertFaster(fastIsaTraversal, slowIsaTraversal);
    }

    @Test
    public void testComplexityConnectedVsDisconnected() {
        GraqlTraversal connectedDoubleIsa = traversal(xIsaY, outIsa(y, z));
        GraqlTraversal disconnectedDoubleIsa = traversal(xIsaY, inIsa(z, y));
        assertFaster(connectedDoubleIsa, disconnectedDoubleIsa);
    }

    @Test
    public void testGloballyOptimalIsFasterThanLocallyOptimal() {
        GraqlTraversal locallyOptimalSpecificInstance = traversal(yId, yTypeOfX, xId);
        GraqlTraversal globallyOptimalSpecificInstance = traversal(xId, xIsaY, yId);
        assertFaster(globallyOptimalSpecificInstance, locallyOptimalSpecificInstance);
    }

    @Test
    public void testRelatesFasterFromRoleType() {
        GraqlTraversal relatesFromRelationType = traversal(yId, outRelates(y, x), xId);
        GraqlTraversal relatesFromRoleType = traversal(xId, inRelates(x, y), yId);
        assertFaster(relatesFromRoleType, relatesFromRelationType);
    }

    @Test
    public void testResourceWithTypeFasterFromType() {
        GraqlTraversal fromInstance =
                traversal(outIsa(x, xx), id(xx, ConceptId.of("_")), inShortcut(x, z), outShortcut(z, y));
        GraqlTraversal fromType =
                traversal(id(xx, ConceptId.of("_")), inIsa(xx, x), inShortcut(x, z), outShortcut(z, y));
        assertFaster(fromType, fromInstance);
    }

    @Test
    public void valueFilteringIsBetterThanANonFilteringOperation() {
        GraqlTraversal valueFilterFirst = traversal(value(x, gt(1).admin()), inShortcut(x, b), outShortcut(b, y), outIsa(y, z));
        GraqlTraversal shortcutFirst = traversal(outIsa(y, z), inShortcut(y, b), outShortcut(b, x), value(x, gt(1).admin()));

        assertFaster(valueFilterFirst, shortcutFirst);
    }

    @Test
    public void testAllTraversalsSimpleQuery() {
        VarPattern pattern = x.id(ConceptId.of("Titanic")).isa(y.id(ConceptId.of("movie")));
        Set<GraqlTraversal> traversals = allGraqlTraversals(pattern).collect(toSet());

        assertEquals(12, traversals.size());

        Set<GraqlTraversal> expected = ImmutableSet.of(
                traversal(xId, xIsaY, yId),
                traversal(xId, yTypeOfX, yId),
                traversal(xId, yId, xIsaY),
                traversal(xId, yId, yTypeOfX),
                traversal(xIsaY, xId, yId),
                traversal(xIsaY, yId, xId),
                traversal(yTypeOfX, xId, yId),
                traversal(yTypeOfX, yId, xId),
                traversal(yId, xId, xIsaY),
                traversal(yId, xId, yTypeOfX),
                traversal(yId, xIsaY, xId),
                traversal(yId, yTypeOfX, xId)
        );

        assertEquals(expected, traversals);
    }

    @Test
    public void testOptimalShortQuery() {
        assertNearlyOptimal(x.isa(y.id(ConceptId.of("movie"))));
    }

    @Test
    public void testOptimalBothId() {
        assertNearlyOptimal(x.id(ConceptId.of("Titanic")).isa(y.id(ConceptId.of("movie"))));
    }

    @Test
    public void testOptimalByValue() {
        assertNearlyOptimal(x.val("hello").isa(y.id(ConceptId.of("movie"))));
    }

    @Test
    public void testOptimalAttachedResource() {
        assertNearlyOptimal(var()
                .rel(x.isa(y.id(ConceptId.of("movie"))))
                .rel(z.val("Titanic").isa(var("a").id(ConceptId.of("title")))));
    }

    @Ignore // TODO: This is now super-slow
    @Test
    public void makeSureTypeIsCheckedBeforeFollowingAShortcut() {
        assertNearlyOptimal(and(
                x.id(ConceptId.of("xid")),
                var().rel(x).rel(y),
                y.isa(b.label("person")),
                var().rel(y).rel(z)
        ));
    }

    @Test
    public void whenPlanningSimpleUnaryRelation_ApplyShortcutOptimisation() {
        VarPattern rel = var("x").rel("y");

        GraqlTraversal graqlTraversal = semiOptimal(rel);

        // I know this is horrible, unfortunately I can't think of a better way...
        // The issue is that some things we want to inspect are not public, mainly:
        // 1. The variable name assigned to the casting
        // 2. The shortcut fragment classes
        // Both of these things should not be made public if possible, so I see this regex as the lesser evil
        assertThat(graqlTraversal, anyOf(
                matches("\\{\\$x-\\[shortcut:\\$.*]->\\$y}"),
                matches("\\{\\$y<-\\[shortcut:\\$.*]-\\$x}")
        ));
    }

    @Test
    public void whenPlanningSimpleBinaryRelationQuery_ApplyShortcutOptimisation() {
        VarPattern rel = var("x").rel("y").rel("z");

        GraqlTraversal graqlTraversal = semiOptimal(rel);

        assertThat(graqlTraversal, anyOf(
                matches("\\{\\$x-\\[shortcut:\\$.*]->\\$.* \\$x-\\[shortcut:\\$.*]->\\$.* \\$.*\\[neq:\\$.*]}"),
                matches("\\{\\$.*<-\\[shortcut:\\$.*]-\\$x-\\[shortcut:\\$.*]->\\$.* \\$.*\\[neq:\\$.*]}")
        ));
    }

    @Test
    public void whenPlanningBinaryRelationQueryWithType_ApplyShortcutOptimisation() {
        VarPattern rel = var("x").rel("y").rel("z").isa("marriage");

        GraqlTraversal graqlTraversal = semiOptimal(rel);

        assertThat(graqlTraversal, anyOf(
                matches(".*\\$x-\\[shortcut:\\$.* rels:marriage]->\\$.* \\$x-\\[shortcut:\\$.* rels:marriage]->\\$.* \\$.*\\[neq:\\$.*].*"),
                matches(".*\\$.*<-\\[shortcut:\\$.* rels:marriage]-\\$x-\\[shortcut:\\$.* rels:marriage]->\\$.* \\$.*\\[neq:\\$.*].*")
        ));
    }

    @Test
    public void testShortcutOptimisationWithRoles() {
        VarPattern rel = var("x").rel("y").rel("wife", "z");

        GraqlTraversal graqlTraversal = semiOptimal(rel);

        assertThat(graqlTraversal, anyOf(
                matches(".*\\$x-\\[shortcut:\\$.* roles:wife]->\\$.* \\$x-\\[shortcut:\\$.*]->\\$.* \\$.*\\[neq:\\$.*].*"),
                matches(".*\\$.*<-\\[shortcut:\\$.* roles:wife]-\\$x-\\[shortcut:\\$.*]->\\$.* \\$.*\\[neq:\\$.*].*")
        ));
    }

    private static GraqlTraversal semiOptimal(Pattern pattern) {
        return GreedyTraversalPlan.createTraversal(pattern.admin(), graph);
    }

    private static GraqlTraversal traversal(Fragment... fragments) {
        return traversal(ImmutableList.copyOf(fragments));
    }

    @SafeVarargs
    private static GraqlTraversal traversal(ImmutableList<Fragment>... fragments) {
        ImmutableSet<ImmutableList<Fragment>> fragmentsSet = ImmutableSet.copyOf(fragments);
        return GraqlTraversal.create(fragmentsSet);
    }

    private static Stream<GraqlTraversal> allGraqlTraversals(Pattern pattern) {
        Collection<Conjunction<VarPatternAdmin>> patterns = pattern.admin().getDisjunctiveNormalForm().getPatterns();

        List<Set<List<Fragment>>> collect = patterns.stream()
                .map(conjunction -> new ConjunctionQuery(conjunction, graph))
                .map(ConjunctionQuery::allFragmentOrders)
                .collect(toList());

        Set<List<List<Fragment>>> lists = Sets.cartesianProduct(collect);

        return lists.stream()
                .map(Sets::newHashSet)
                .map(GraqlTraversalTest::createTraversal)
                .flatMap(CommonUtil::optionalToStream);
    }

    // Returns a traversal only if the fragment ordering is valid
    private static Optional<GraqlTraversal> createTraversal(Set<List<Fragment>> fragments) {

        // Make sure all dependencies are met
        for (List<Fragment> fragmentList : fragments) {
            Set<Var> visited = new HashSet<>();

            for (Fragment fragment : fragmentList) {
                if (!visited.containsAll(fragment.getDependencies())) {
                    return Optional.empty();
                }

                visited.addAll(fragment.getVariableNames());
            }
        }

        return Optional.of(GraqlTraversal.create(fragments));
    }

    private static Fragment outShortcut(Var relation, Var rolePlayer) {
        return Fragments.outShortcut(relation, a, rolePlayer, Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static Fragment inShortcut(Var rolePlayer, Var relation) {
        return Fragments.inShortcut(rolePlayer, c, relation, Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static void assertNearlyOptimal(Pattern pattern) {
        GraqlTraversal traversal = semiOptimal(pattern);

        //noinspection OptionalGetWithoutIsPresent
        GraqlTraversal globalOptimum = allGraqlTraversals(pattern).min(comparing(GraqlTraversal::getComplexity)).get();

        double globalComplexity = globalOptimum.getComplexity();
        double complexity = traversal.getComplexity();

        // We use logarithms because we are only concerned with orders of magnitude of complexity
        assertTrue(
                "Expected\n " +
                        complexity + ":\t" + traversal + "\nto be similar speed to\n " +
                        globalComplexity + ":\t" + globalOptimum,
                Math.log(complexity) < Math.log(globalComplexity) * 2
        );
    }

    private static void assertFaster(GraqlTraversal fast, GraqlTraversal slow) {
        double fastComplexity = fast.getComplexity();
        double slowComplexity = slow.getComplexity();
        boolean condition = fastComplexity < slowComplexity;

        assertTrue(
                "Expected\n" + fastComplexity + ":\t" + fast + "\nto be faster than\n" + slowComplexity + ":\t" + slow,
                condition
        );
    }

    private <T> Matcher<T> matches(String regex) {
        return feature(satisfies(string -> string.matches(regex)), "matching " + regex, Object::toString);
    }
}
