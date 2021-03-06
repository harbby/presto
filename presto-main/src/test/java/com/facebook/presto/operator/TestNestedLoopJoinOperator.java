/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.operator;

import com.facebook.presto.RowPagesBuilder;
import com.facebook.presto.operator.NestedLoopBuildOperator.NestedLoopBuildOperatorFactory;
import com.facebook.presto.operator.NestedLoopJoinOperator.NestedLoopJoinOperatorFactory;
import com.facebook.presto.operator.NestedLoopJoinOperator.NestedLoopPageBuilder;
import com.facebook.presto.spi.Page;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.sql.planner.plan.PlanNodeId;
import com.facebook.presto.testing.MaterializedResult;
import com.facebook.presto.testing.TestingTaskContext;
import com.google.common.collect.ImmutableList;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

import static com.facebook.presto.RowPagesBuilder.rowPagesBuilder;
import static com.facebook.presto.SessionTestUtils.TEST_SESSION;
import static com.facebook.presto.operator.OperatorAssertion.assertOperatorEquals;
import static com.facebook.presto.operator.ValuesOperator.ValuesOperatorFactory;
import static com.facebook.presto.spi.type.BigintType.BIGINT;
import static com.facebook.presto.spi.type.VarcharType.VARCHAR;
import static com.facebook.presto.testing.MaterializedResult.resultBuilder;
import static com.google.common.collect.Iterables.concat;
import static io.airlift.concurrent.Threads.daemonThreadsNamed;
import static java.util.concurrent.Executors.newCachedThreadPool;
import static java.util.concurrent.Executors.newScheduledThreadPool;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

@Test(singleThreaded = true)
public class TestNestedLoopJoinOperator
{
    private ExecutorService executor;
    private ScheduledExecutorService scheduledExecutor;

    @BeforeClass
    public void setUp()
    {
        executor = newCachedThreadPool(daemonThreadsNamed("test-executor-%s"));
        scheduledExecutor = newScheduledThreadPool(2, daemonThreadsNamed("test-scheduledExecutor-%s"));
    }

    @AfterClass(alwaysRun = true)
    public void tearDown()
    {
        executor.shutdownNow();
        scheduledExecutor.shutdownNow();
    }

    @Test
    public void testNestedLoopJoin()
    {
        TaskContext taskContext = createTaskContext();
        // build
        RowPagesBuilder buildPages = rowPagesBuilder(ImmutableList.of(VARCHAR, BIGINT, BIGINT))
                .addSequencePage(3, 20, 30, 40);
        JoinBridgeDataManager<NestedLoopJoinPagesBridge> nestedLoopJoinPagesBridgeManager = buildPageSource(taskContext, buildPages);

        // probe
        RowPagesBuilder probePages = rowPagesBuilder(ImmutableList.of(VARCHAR, BIGINT, BIGINT));
        List<Page> probeInput = probePages
                .addSequencePage(2, 0, 1000, 2000)
                .build();
        NestedLoopJoinOperatorFactory joinOperatorFactory = new NestedLoopJoinOperatorFactory(3, new PlanNodeId("test"), nestedLoopJoinPagesBridgeManager);

        // expected
        MaterializedResult expected = resultBuilder(taskContext.getSession(), concat(probePages.getTypes(), buildPages.getTypes()))
                .row("0", 1000L, 2000L, "20", 30L, 40L)
                .row("0", 1000L, 2000L, "21", 31L, 41L)
                .row("0", 1000L, 2000L, "22", 32L, 42L)
                .row("1", 1001L, 2001L, "20", 30L, 40L)
                .row("1", 1001L, 2001L, "21", 31L, 41L)
                .row("1", 1001L, 2001L, "22", 32L, 42L)
                .build();

        assertOperatorEquals(joinOperatorFactory, taskContext.addPipelineContext(0, true, true, false).addDriverContext(), probeInput, expected);

        // Test probe pages has more rows
        buildPages = rowPagesBuilder(ImmutableList.of(VARCHAR, BIGINT, BIGINT))
                .addSequencePage(2, 20, 30, 40);
        nestedLoopJoinPagesBridgeManager = buildPageSource(taskContext, buildPages);
        joinOperatorFactory = new NestedLoopJoinOperatorFactory(3, new PlanNodeId("test"), nestedLoopJoinPagesBridgeManager);

        // probe
        probePages = rowPagesBuilder(ImmutableList.of(VARCHAR, BIGINT, BIGINT));
        probeInput = probePages
                .addSequencePage(3, 0, 1000, 2000)
                .build();

        // expected
        expected = resultBuilder(taskContext.getSession(), concat(probePages.getTypes(), buildPages.getTypes()))
                .row("0", 1000L, 2000L, "20", 30L, 40L)
                .row("1", 1001L, 2001L, "20", 30L, 40L)
                .row("2", 1002L, 2002L, "20", 30L, 40L)
                .row("0", 1000L, 2000L, "21", 31L, 41L)
                .row("1", 1001L, 2001L, "21", 31L, 41L)
                .row("2", 1002L, 2002L, "21", 31L, 41L)
                .build();

        assertOperatorEquals(joinOperatorFactory, taskContext.addPipelineContext(0, true, true, false).addDriverContext(), probeInput, expected);
    }

    @Test
    public void testCrossJoinWithNullProbe()
    {
        TaskContext taskContext = createTaskContext();

        // build
        List<Type> buildTypes = ImmutableList.of(VARCHAR);
        RowPagesBuilder buildPages = rowPagesBuilder(buildTypes)
                .row("a")
                .row("b");
        JoinBridgeDataManager<NestedLoopJoinPagesBridge> nestedLoopJoinPagesBridgeManager = buildPageSource(taskContext, buildPages);

        // probe
        List<Type> probeTypes = ImmutableList.of(VARCHAR);
        RowPagesBuilder probePages = rowPagesBuilder(probeTypes);
        List<Page> probeInput = probePages
                .row("A")
                .row((String) null)
                .row((String) null)
                .row("A")
                .row("B")
                .build();

        NestedLoopJoinOperatorFactory joinOperatorFactory = new NestedLoopJoinOperatorFactory(3, new PlanNodeId("test"), nestedLoopJoinPagesBridgeManager);

        // expected
        MaterializedResult expected = resultBuilder(taskContext.getSession(), concat(probeTypes, buildPages.getTypes()))
                .row("A", "a")
                .row(null, "a")
                .row(null, "a")
                .row("A", "a")
                .row("B", "a")
                .row("A", "b")
                .row(null, "b")
                .row(null, "b")
                .row("A", "b")
                .row("B", "b")
                .build();

        assertOperatorEquals(joinOperatorFactory, taskContext.addPipelineContext(0, true, true, false).addDriverContext(), probeInput, expected);
    }

    @Test
    public void testCrossJoinWithNullBuild()
    {
        TaskContext taskContext = createTaskContext();

        // build
        List<Type> buildTypes = ImmutableList.of(VARCHAR);
        RowPagesBuilder buildPages = rowPagesBuilder(buildTypes)
                .row("a")
                .row((String) null)
                .row((String) null)
                .row("a")
                .row("b");
        JoinBridgeDataManager<NestedLoopJoinPagesBridge> nestedLoopJoinPagesBridgeManager = buildPageSource(taskContext, buildPages);

        // probe
        List<Type> probeTypes = ImmutableList.of(VARCHAR);
        RowPagesBuilder probePages = rowPagesBuilder(probeTypes);
        List<Page> probeInput = probePages
                .row("A")
                .row("B")
                .build();
        NestedLoopJoinOperatorFactory joinOperatorFactory = new NestedLoopJoinOperatorFactory(3, new PlanNodeId("test"), nestedLoopJoinPagesBridgeManager);

        // expected
        MaterializedResult expected = resultBuilder(taskContext.getSession(), concat(probeTypes, buildPages.getTypes()))
                .row("A", "a")
                .row("A", null)
                .row("A", null)
                .row("A", "a")
                .row("A", "b")
                .row("B", "a")
                .row("B", null)
                .row("B", null)
                .row("B", "a")
                .row("B", "b")
                .build();

        assertOperatorEquals(joinOperatorFactory, taskContext.addPipelineContext(0, true, true, false).addDriverContext(), probeInput, expected);
    }

    @Test
    public void testCrossJoinWithNullOnBothSides()
    {
        TaskContext taskContext = createTaskContext();

        // build
        List<Type> buildTypes = ImmutableList.of(VARCHAR);
        RowPagesBuilder buildPages = rowPagesBuilder(buildTypes)
                .row("a")
                .row((String) null)
                .row("b")
                .row("c")
                .row((String) null);
        JoinBridgeDataManager<NestedLoopJoinPagesBridge> nestedLoopJoinPagesBridgeManager = buildPageSource(taskContext, buildPages);

        // probe
        List<Type> probeTypes = ImmutableList.of(VARCHAR);
        RowPagesBuilder probePages = rowPagesBuilder(probeTypes);
        List<Page> probeInput = probePages
                .row("A")
                .row("B")
                .row((String) null)
                .row("C")
                .build();
        NestedLoopJoinOperatorFactory joinOperatorFactory = new NestedLoopJoinOperatorFactory(3, new PlanNodeId("test"), nestedLoopJoinPagesBridgeManager);

        // expected
        MaterializedResult expected = resultBuilder(taskContext.getSession(), concat(probeTypes, buildPages.getTypes()))
                .row("A", "a")
                .row("A", null)
                .row("A", "b")
                .row("A", "c")
                .row("A", null)
                .row("B", "a")
                .row("B", null)
                .row("B", "b")
                .row("B", "c")
                .row("B", null)
                .row(null, "a")
                .row(null, null)
                .row(null, "b")
                .row(null, "c")
                .row(null, null)
                .row("C", "a")
                .row("C", null)
                .row("C", "b")
                .row("C", "c")
                .row("C", null)
                .build();

        assertOperatorEquals(joinOperatorFactory, taskContext.addPipelineContext(0, true, true, false).addDriverContext(), probeInput, expected);
    }

    @Test
    public void testBuildMultiplePages()
    {
        TaskContext taskContext = createTaskContext();

        // build
        List<Type> buildTypes = ImmutableList.of(VARCHAR);
        RowPagesBuilder buildPages = rowPagesBuilder(buildTypes)
                .row("a")
                .pageBreak()
                .row((String) null)
                .row("b")
                .row("c")
                .pageBreak()
                .row("d");
        JoinBridgeDataManager<NestedLoopJoinPagesBridge> nestedLoopJoinPagesBridgeManager = buildPageSource(taskContext, buildPages);

        // probe
        List<Type> probeTypes = ImmutableList.of(VARCHAR);
        RowPagesBuilder probePages = rowPagesBuilder(probeTypes);
        List<Page> probeInput = probePages
                .row("A")
                .row("B")
                .build();
        NestedLoopJoinOperatorFactory joinOperatorFactory = new NestedLoopJoinOperatorFactory(3, new PlanNodeId("test"), nestedLoopJoinPagesBridgeManager);

        // expected
        MaterializedResult expected = resultBuilder(taskContext.getSession(), concat(probeTypes, buildPages.getTypes()))
                .row("A", "a")
                .row("B", "a")
                .row("A", null)
                .row("A", "b")
                .row("A", "c")
                .row("B", null)
                .row("B", "b")
                .row("B", "c")
                .row("A", "d")
                .row("B", "d")
                .build();

        assertOperatorEquals(joinOperatorFactory, taskContext.addPipelineContext(0, true, true, false).addDriverContext(), probeInput, expected);
    }

    @Test
    public void testProbeMultiplePages()
    {
        TaskContext taskContext = createTaskContext();

        // build
        List<Type> buildTypes = ImmutableList.of(VARCHAR);
        RowPagesBuilder buildPages = rowPagesBuilder(buildTypes)
                .row("A")
                .row("B");
        JoinBridgeDataManager<NestedLoopJoinPagesBridge> nestedLoopJoinPagesBridgeManager = buildPageSource(taskContext, buildPages);

        // probe
        List<Type> probeTypes = ImmutableList.of(VARCHAR);
        RowPagesBuilder probePages = rowPagesBuilder(probeTypes);
        List<Page> probeInput = probePages
                .row("a")
                .pageBreak()
                .row((String) null)
                .row("b")
                .row("c")
                .pageBreak()
                .row("d")
                .build();
        NestedLoopJoinOperatorFactory joinOperatorFactory = new NestedLoopJoinOperatorFactory(3, new PlanNodeId("test"), nestedLoopJoinPagesBridgeManager);

        // expected
        MaterializedResult expected = resultBuilder(taskContext.getSession(), concat(probeTypes, buildPages.getTypes()))
                .row("a", "A")
                .row("a", "B")
                .row(null, "A")
                .row("b", "A")
                .row("c", "A")
                .row(null, "B")
                .row("b", "B")
                .row("c", "B")
                .row("d", "A")
                .row("d", "B")
                .build();

        assertOperatorEquals(joinOperatorFactory, taskContext.addPipelineContext(0, true, true, false).addDriverContext(), probeInput, expected);
    }

    @Test
    public void testProbeAndBuildMultiplePages()
    {
        TaskContext taskContext = createTaskContext();

        // build
        List<Type> buildTypes = ImmutableList.of(VARCHAR);
        RowPagesBuilder buildPages = rowPagesBuilder(buildTypes)
                .row("A")
                .row("B")
                .pageBreak()
                .row("C");
        JoinBridgeDataManager<NestedLoopJoinPagesBridge> nestedLoopJoinPagesBridgeManager = buildPageSource(taskContext, buildPages);

        // probe
        List<Type> probeTypes = ImmutableList.of(VARCHAR);
        RowPagesBuilder probePages = rowPagesBuilder(probeTypes);
        List<Page> probeInput = probePages
                .row("a")
                .pageBreak()
                .row((String) null)
                .row("b")
                .row("c")
                .pageBreak()
                .row("d")
                .build();
        NestedLoopJoinOperatorFactory joinOperatorFactory = new NestedLoopJoinOperatorFactory(3, new PlanNodeId("test"), nestedLoopJoinPagesBridgeManager);

        // expected
        MaterializedResult expected = resultBuilder(taskContext.getSession(), concat(probeTypes, buildPages.getTypes()))
                .row("a", "A")
                .row("a", "B")
                .row("a", "C")
                .row(null, "A")
                .row("b", "A")
                .row("c", "A")
                .row(null, "B")
                .row("b", "B")
                .row("c", "B")
                .row(null, "C")
                .row("b", "C")
                .row("c", "C")
                .row("d", "A")
                .row("d", "B")
                .row("d", "C")
                .build();

        assertOperatorEquals(joinOperatorFactory, taskContext.addPipelineContext(0, true, true, false).addDriverContext(), probeInput, expected);
    }

    @Test
    public void testEmptyProbePage()
    {
        TaskContext taskContext = createTaskContext();

        // build
        List<Type> buildTypes = ImmutableList.of(VARCHAR);
        RowPagesBuilder buildPages = rowPagesBuilder(buildTypes)
                .row("A")
                .row("B")
                .pageBreak()
                .row("C");
        JoinBridgeDataManager<NestedLoopJoinPagesBridge> nestedLoopJoinPagesBridgeManager = buildPageSource(taskContext, buildPages);

        // probe
        List<Type> probeTypes = ImmutableList.of(VARCHAR);
        RowPagesBuilder probePages = rowPagesBuilder(probeTypes);
        List<Page> probeInput = probePages
                .pageBreak()
                .build();
        NestedLoopJoinOperatorFactory joinOperatorFactory = new NestedLoopJoinOperatorFactory(3, new PlanNodeId("test"), nestedLoopJoinPagesBridgeManager);

        // expected
        MaterializedResult expected = resultBuilder(taskContext.getSession(), concat(probeTypes, buildPages.getTypes()))
                .build();

        assertOperatorEquals(joinOperatorFactory, taskContext.addPipelineContext(0, true, true, false).addDriverContext(), probeInput, expected);
    }

    @Test
    public void testEmptyBuildPage()
    {
        TaskContext taskContext = createTaskContext();

        // build
        List<Type> buildTypes = ImmutableList.of(VARCHAR);
        RowPagesBuilder buildPages = rowPagesBuilder(buildTypes)
                .pageBreak();
        JoinBridgeDataManager<NestedLoopJoinPagesBridge> nestedLoopJoinPagesBridgeManager = buildPageSource(taskContext, buildPages);

        // probe
        List<Type> probeTypes = ImmutableList.of(VARCHAR);
        RowPagesBuilder probePages = rowPagesBuilder(probeTypes);
        List<Page> probeInput = probePages
                .row("A")
                .row("B")
                .pageBreak()
                .build();
        NestedLoopJoinOperatorFactory joinOperatorFactory = new NestedLoopJoinOperatorFactory(3, new PlanNodeId("test"), nestedLoopJoinPagesBridgeManager);

        // expected
        MaterializedResult expected = resultBuilder(taskContext.getSession(), concat(probeTypes, buildPages.getTypes()))
                .build();

        assertOperatorEquals(joinOperatorFactory, taskContext.addPipelineContext(0, true, true, false).addDriverContext(), probeInput, expected);
    }

    @Test
    public void testCount()
    {
        // normal case
        Page buildPage = new Page(100);
        Page probePage = new Page(45);

        NestedLoopPageBuilder resultPageBuilder = new NestedLoopPageBuilder(probePage, buildPage);
        assertTrue(resultPageBuilder.hasNext(), "There should be at least one page.");

        long result = 0;
        while (resultPageBuilder.hasNext()) {
            result += resultPageBuilder.next().getPositionCount();
        }
        assertEquals(result, 4500);

        // force the product to be bigger than Integer.MAX_VALUE
        buildPage = new Page(Integer.MAX_VALUE - 10);
        resultPageBuilder = new NestedLoopPageBuilder(probePage, buildPage);

        result = 0;
        while (resultPageBuilder.hasNext()) {
            result += resultPageBuilder.next().getPositionCount();
        }
        assertEquals((Integer.MAX_VALUE - 10) * 45L, result);
    }

    private TaskContext createTaskContext()
    {
        return TestingTaskContext.createTaskContext(executor, scheduledExecutor, TEST_SESSION);
    }

    private static JoinBridgeDataManager<NestedLoopJoinPagesBridge> buildPageSource(TaskContext taskContext, RowPagesBuilder buildPages)
    {
        DriverContext driverContext = taskContext.addPipelineContext(0, true, true, false).addDriverContext();

        ValuesOperatorFactory valuesOperatorFactory = new ValuesOperatorFactory(0, new PlanNodeId("test"), buildPages.build());

        JoinBridgeDataManager<NestedLoopJoinPagesBridge> nestedLoopJoinPagesSupplierManager = JoinBridgeDataManager.nestedLoop(
                PipelineExecutionStrategy.UNGROUPED_EXECUTION,
                PipelineExecutionStrategy.UNGROUPED_EXECUTION,
                lifespan -> new NestedLoopJoinPagesSupplier(),
                buildPages.getTypes());
        NestedLoopBuildOperatorFactory nestedLoopBuildOperatorFactory = new NestedLoopBuildOperatorFactory(1, new PlanNodeId("test"), nestedLoopJoinPagesSupplierManager);

        Operator valuesOperator = valuesOperatorFactory.createOperator(driverContext);
        Operator nestedLoopBuildOperator = nestedLoopBuildOperatorFactory.createOperator(driverContext);
        Driver driver = Driver.createDriver(driverContext,
                valuesOperator,
                nestedLoopBuildOperator);

        valuesOperatorFactory.noMoreOperators();
        nestedLoopBuildOperatorFactory.noMoreOperators();

        while (nestedLoopBuildOperator.isBlocked().isDone()) {
            driver.process();
        }
        return nestedLoopJoinPagesSupplierManager;
    }
}
