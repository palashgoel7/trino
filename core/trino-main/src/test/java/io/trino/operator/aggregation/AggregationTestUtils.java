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
package io.trino.operator.aggregation;

import com.google.common.primitives.Ints;
import io.trino.block.BlockAssertions;
import io.trino.metadata.TestingFunctionResolution;
import io.trino.operator.GroupByIdBlock;
import io.trino.spi.Page;
import io.trino.spi.block.Block;
import io.trino.spi.block.BlockBuilder;
import io.trino.spi.block.RunLengthEncodedBlock;
import io.trino.spi.type.BooleanType;
import io.trino.sql.analyzer.TypeSignatureProvider;
import io.trino.sql.tree.QualifiedName;
import org.apache.commons.math3.util.Precision;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.BiFunction;
import java.util.stream.IntStream;

import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.BooleanType.BOOLEAN;
import static java.lang.String.format;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.fail;

public final class AggregationTestUtils
{
    private AggregationTestUtils() {}

    public static void assertAggregation(TestingFunctionResolution functionResolution, QualifiedName name, List<TypeSignatureProvider> parameterTypes, Object expectedValue, Block... blocks)
    {
        assertAggregation(functionResolution, name, parameterTypes, expectedValue, new Page(blocks));
    }

    public static void assertAggregation(TestingFunctionResolution functionResolution, QualifiedName name, List<TypeSignatureProvider> parameterTypes, Object expectedValue, Page page)
    {
        BiFunction<Object, Object, Boolean> equalAssertion = makeValidityAssertion(expectedValue);

        assertAggregation(functionResolution, name, parameterTypes, equalAssertion, null, page, expectedValue);
    }

    public static BiFunction<Object, Object, Boolean> makeValidityAssertion(Object expectedValue)
    {
        if (expectedValue instanceof Double && !expectedValue.equals(Double.NaN)) {
            return (actual, expected) -> Precision.equals((double) actual, (double) expected, 1.0e-10);
        }
        if (expectedValue instanceof Float && !expectedValue.equals(Float.NaN)) {
            return (actual, expected) -> Precision.equals((float) actual, (float) expected, 1.0e-10f);
        }
        return Objects::equals;
    }

    public static void assertAggregation(TestingFunctionResolution functionResolution, QualifiedName name, List<TypeSignatureProvider> parameterTypes, BiFunction<Object, Object, Boolean> equalAssertion, String testDescription, Page page, Object expectedValue)
    {
        TestingAggregationFunction function = functionResolution.getAggregateFunction(name, parameterTypes);

        int positions = page.getPositionCount();
        for (int i = 1; i < page.getChannelCount(); i++) {
            assertEquals(positions, page.getBlock(i).getPositionCount(), "input blocks provided are not equal in position count");
        }
        if (positions == 0) {
            assertAggregationInternal(function, equalAssertion, testDescription, expectedValue);
        }
        else if (positions == 1) {
            assertAggregationInternal(function, equalAssertion, testDescription, expectedValue, page);
        }
        else {
            int split = positions / 2; // [0, split - 1] goes to first list of blocks; [split, positions - 1] goes to second list of blocks.
            Page page1 = page.getRegion(0, split);
            Page page2 = page.getRegion(split, positions - split);
            assertAggregationInternal(function, equalAssertion, testDescription, expectedValue, page1, page2);
        }
    }

    public static Block getIntermediateBlock(Accumulator accumulator)
    {
        BlockBuilder blockBuilder = accumulator.getIntermediateType().createBlockBuilder(null, 1000);
        accumulator.evaluateIntermediate(blockBuilder);
        return blockBuilder.build();
    }

    public static Block getIntermediateBlock(GroupedAccumulator accumulator)
    {
        BlockBuilder blockBuilder = accumulator.getIntermediateType().createBlockBuilder(null, 1000);
        accumulator.evaluateIntermediate(0, blockBuilder);
        return blockBuilder.build();
    }

    public static Block getFinalBlock(Accumulator accumulator)
    {
        BlockBuilder blockBuilder = accumulator.getFinalType().createBlockBuilder(null, 1000);
        accumulator.evaluateFinal(blockBuilder);
        return blockBuilder.build();
    }

    private static void assertAggregationInternal(
            TestingAggregationFunction function,
            BiFunction<Object, Object, Boolean> isEqual,
            String testDescription,
            Object expectedValue,
            Page... pages)
    {
        // This assertAggregation does not try to split up the page to test the correctness of combine function.
        // Do not use this directly. Always use the other assertAggregation.
        assertFunctionEquals(isEqual, testDescription, aggregation(function, pages), expectedValue);
        assertFunctionEquals(isEqual, testDescription, partialAggregation(function, pages), expectedValue);
        if (pages.length > 0) {
            assertFunctionEquals(isEqual, testDescription, groupedAggregation(isEqual, function, pages), expectedValue);
            assertFunctionEquals(isEqual, testDescription, groupedPartialAggregation(isEqual, function, pages), expectedValue);
            assertFunctionEquals(isEqual, testDescription, distinctAggregation(function, pages), expectedValue);
        }
    }

    private static void assertFunctionEquals(BiFunction<Object, Object, Boolean> isEqual, String testDescription, Object actualValue, Object expectedValue)
    {
        if (!isEqual.apply(actualValue, expectedValue)) {
            StringBuilder sb = new StringBuilder();
            if (testDescription != null) {
                sb.append(format("Test: %s, ", testDescription));
            }
            sb.append(format("Expected: %s, actual: %s", expectedValue, actualValue));
            fail(sb.toString());
        }
    }

    private static Object distinctAggregation(TestingAggregationFunction function, Page... pages)
    {
        int parameterCount = function.getParameterCount();
        OptionalInt maskChannel = OptionalInt.of(pages[0].getChannelCount());
        // Execute normally
        Object aggregation = aggregation(function, createArgs(parameterCount), maskChannel, maskPages(true, pages));
        Page[] dupedPages = new Page[pages.length * 2];
        // Create two copies of each page with one of them masked off
        System.arraycopy(maskPages(true, pages), 0, dupedPages, 0, pages.length);
        System.arraycopy(maskPages(false, pages), 0, dupedPages, pages.length, pages.length);
        // Execute with masked pages and assure equal to normal execution
        Object aggregationWithDupes = aggregation(function, createArgs(parameterCount), maskChannel, dupedPages);

        assertEquals(aggregationWithDupes, aggregation, "Inconsistent results with mask");

        // Re-run the duplicated inputs with RLE masks
        System.arraycopy(maskPagesWithRle(true, pages), 0, dupedPages, 0, pages.length);
        System.arraycopy(maskPagesWithRle(false, pages), 0, dupedPages, pages.length, pages.length);
        Object aggregationWithRleMasks = aggregation(function, createArgs(parameterCount), maskChannel, dupedPages);

        assertEquals(aggregationWithRleMasks, aggregation, "Inconsistent results with RLE mask");

        return aggregation;
    }

    // Adds the mask as the last channel
    private static Page[] maskPagesWithRle(boolean maskValue, Page... pages)
    {
        Page[] maskedPages = new Page[pages.length];
        for (int i = 0; i < pages.length; i++) {
            Page page = pages[i];
            maskedPages[i] = page.appendColumn(new RunLengthEncodedBlock(BooleanType.createBlockForSingleNonNullValue(maskValue), page.getPositionCount()));
        }
        return maskedPages;
    }

    // Adds the mask as the last channel
    private static Page[] maskPages(boolean maskValue, Page... pages)
    {
        Page[] maskedPages = new Page[pages.length];
        for (int i = 0; i < pages.length; i++) {
            Page page = pages[i];
            BlockBuilder blockBuilder = BOOLEAN.createBlockBuilder(null, page.getPositionCount());
            for (int j = 0; j < page.getPositionCount(); j++) {
                BOOLEAN.writeBoolean(blockBuilder, maskValue);
            }
            maskedPages[i] = page.appendColumn(blockBuilder.build());
        }

        return maskedPages;
    }

    public static Object aggregation(TestingAggregationFunction function, Page... pages)
    {
        // execute with args in positions: arg0, arg1, arg2
        int parameterCount = function.getParameterCount();
        Object aggregation = aggregation(function, createArgs(parameterCount), OptionalInt.empty(), pages);

        // execute with args in reverse order: arg2, arg1, arg0
        if (parameterCount > 1) {
            Object aggregationWithOffset = aggregation(function, reverseArgs(parameterCount), OptionalInt.empty(), reverseColumns(pages));
            assertEquals(aggregationWithOffset, aggregation, "Inconsistent results with reversed channels");
        }

        // execute with args at an offset (and possibly reversed): null, null, null, arg2, arg1, arg0
        Object aggregationWithOffset = aggregation(function, offsetArgs(parameterCount, 3), OptionalInt.empty(), offsetColumns(pages, 3));
        assertEquals(aggregationWithOffset, aggregation, "Inconsistent results with channel offset");

        return aggregation;
    }

    private static Object aggregation(TestingAggregationFunction function, int[] args, OptionalInt maskChannel, Page... pages)
    {
        Accumulator aggregation = function.bind(Ints.asList(args), maskChannel.stream().boxed().findFirst()).createAccumulator();
        for (Page page : pages) {
            if (page.getPositionCount() > 0) {
                aggregation.addInput(page);
            }
        }

        Block block = getFinalBlock(aggregation);
        return BlockAssertions.getOnlyValue(function.getFinalType(), block);
    }

    public static Object partialAggregation(TestingAggregationFunction function, Page... pages)
    {
        // execute with args in positions: arg0, arg1, arg2
        int parameterCount = function.getParameterCount();
        Object aggregation = partialAggregation(function, createArgs(parameterCount), pages);

        // execute with args in reverse order: arg2, arg1, arg0
        if (parameterCount > 1) {
            Object aggregationWithOffset = partialAggregation(function, reverseArgs(parameterCount), reverseColumns(pages));
            assertEquals(aggregationWithOffset, aggregation, "Inconsistent results with reversed channels");
        }

        // execute with args at an offset (and possibly reversed): null, null, null, arg2, arg1, arg0
        Object aggregationWithOffset = partialAggregation(function, offsetArgs(parameterCount, 3), offsetColumns(pages, 3));
        assertEquals(aggregationWithOffset, aggregation, "Inconsistent results with channel offset");

        return aggregation;
    }

    private static Object partialAggregation(TestingAggregationFunction function, int[] args, Page... pages)
    {
        AccumulatorFactory factory = function.bind(Ints.asList(args), Optional.empty());
        Accumulator finalAggregation = factory.createIntermediateAccumulator();

        // Test handling of empty intermediate blocks
        Accumulator emptyAggregation = factory.createAccumulator();
        Block emptyBlock = getIntermediateBlock(emptyAggregation);

        finalAggregation.addIntermediate(emptyBlock);

        for (Page page : pages) {
            Accumulator partialAggregation = factory.createAccumulator();
            if (page.getPositionCount() > 0) {
                partialAggregation.addInput(page);
            }
            Block partialBlock = getIntermediateBlock(partialAggregation);
            finalAggregation.addIntermediate(partialBlock);
        }

        finalAggregation.addIntermediate(emptyBlock);

        Block finalBlock = getFinalBlock(finalAggregation);
        return BlockAssertions.getOnlyValue(finalAggregation.getFinalType(), finalBlock);
    }

    public static Object groupedAggregation(TestingAggregationFunction function, Page page)
    {
        return groupedAggregation(Objects::equals, function, page);
    }

    private static Object groupedAggregation(BiFunction<Object, Object, Boolean> isEqual, TestingAggregationFunction function, Page... pages)
    {
        // execute with args in positions: arg0, arg1, arg2
        int parameterCount = function.getParameterCount();
        Object aggregation = groupedAggregation(function, createArgs(parameterCount), pages);

        // execute with args in reverse order: arg2, arg1, arg0
        if (parameterCount > 1) {
            Object aggregationWithOffset = groupedAggregation(function, reverseArgs(parameterCount), reverseColumns(pages));
            assertFunctionEquals(isEqual, "Inconsistent results with reversed channels", aggregationWithOffset, aggregation);
        }

        // execute with args at an offset (and possibly reversed): null, null, null, arg2, arg1, arg0
        Object aggregationWithOffset = groupedAggregation(function, offsetArgs(parameterCount, 3), offsetColumns(pages, 3));
        assertFunctionEquals(isEqual, "Consistent results with channel offset", aggregationWithOffset, aggregation);

        return aggregation;
    }

    public static Object groupedAggregation(TestingAggregationFunction function, int[] args, Page... pages)
    {
        GroupedAccumulator groupedAggregation = function.bind(Ints.asList(args), Optional.empty()).createGroupedAccumulator();
        for (Page page : pages) {
            groupedAggregation.addInput(createGroupByIdBlock(0, page.getPositionCount()), page);
        }
        Object groupValue = getGroupValue(groupedAggregation, 0);

        for (Page page : pages) {
            groupedAggregation.addInput(createGroupByIdBlock(4000, page.getPositionCount()), page);
        }
        Object largeGroupValue = getGroupValue(groupedAggregation, 4000);
        assertEquals(largeGroupValue, groupValue, "Inconsistent results with large group id");

        return groupValue;
    }

    private static Object groupedPartialAggregation(BiFunction<Object, Object, Boolean> isEqual, TestingAggregationFunction function, Page... pages)
    {
        // execute with args in positions: arg0, arg1, arg2
        int parameterCount = function.getParameterCount();
        Object aggregation = groupedPartialAggregation(function, createArgs(parameterCount), pages);

        // execute with args in reverse order: arg2, arg1, arg0
        if (parameterCount > 1) {
            Object aggregationWithOffset = groupedPartialAggregation(function, reverseArgs(parameterCount), reverseColumns(pages));
            assertFunctionEquals(isEqual, "Consistent results with reversed channels", aggregationWithOffset, aggregation);
        }

        // execute with args at an offset (and possibly reversed): null, null, null, arg2, arg1, arg0
        Object aggregationWithOffset = groupedPartialAggregation(function, offsetArgs(parameterCount, 3), offsetColumns(pages, 3));
        assertFunctionEquals(isEqual, "Consistent results with channel offset", aggregationWithOffset, aggregation);

        return aggregation;
    }

    private static Object groupedPartialAggregation(TestingAggregationFunction function, int[] args, Page... pages)
    {
        AccumulatorFactory factory = function.bind(Ints.asList(args), Optional.empty());
        GroupedAccumulator finalAggregation = factory.createGroupedIntermediateAccumulator();

        // Add an empty block to test the handling of empty intermediates
        GroupedAccumulator emptyAggregation = factory.createGroupedAccumulator();
        Block emptyBlock = getIntermediateBlock(emptyAggregation);

        finalAggregation.addIntermediate(createGroupByIdBlock(0, emptyBlock.getPositionCount()), emptyBlock);

        for (Page page : pages) {
            GroupedAccumulator partialAggregation = factory.createGroupedAccumulator();
            partialAggregation.addInput(createGroupByIdBlock(0, page.getPositionCount()), page);
            Block partialBlock = getIntermediateBlock(partialAggregation);
            finalAggregation.addIntermediate(createGroupByIdBlock(0, partialBlock.getPositionCount()), partialBlock);
        }

        finalAggregation.addIntermediate(createGroupByIdBlock(0, emptyBlock.getPositionCount()), emptyBlock);

        return getGroupValue(finalAggregation, 0);
    }

    public static GroupByIdBlock createGroupByIdBlock(int groupId, int positions)
    {
        BlockBuilder blockBuilder = BIGINT.createBlockBuilder(null, positions);
        for (int i = 0; i < positions; i++) {
            BIGINT.writeLong(blockBuilder, groupId);
        }
        return new GroupByIdBlock(groupId, blockBuilder.build());
    }

    static int[] createArgs(int parameterCount)
    {
        int[] args = new int[parameterCount];
        for (int i = 0; i < args.length; i++) {
            args[i] = i;
        }
        return args;
    }

    private static int[] reverseArgs(int parameterCount)
    {
        int[] args = createArgs(parameterCount);
        Collections.reverse(Ints.asList(args));
        return args;
    }

    private static int[] offsetArgs(int parameterCount, int offset)
    {
        int[] args = createArgs(parameterCount);
        for (int i = 0; i < args.length; i++) {
            args[i] += offset;
        }
        return args;
    }

    public static Page[] reverseColumns(Page[] pages)
    {
        Page[] newPages = new Page[pages.length];
        for (int i = 0; i < pages.length; i++) {
            Page page = pages[i];
            if (page.getPositionCount() == 0) {
                newPages[i] = page;
            }
            else {
                Block[] newBlocks = new Block[page.getChannelCount()];
                for (int channel = 0; channel < page.getChannelCount(); channel++) {
                    newBlocks[channel] = page.getBlock(page.getChannelCount() - channel - 1);
                }
                newPages[i] = new Page(page.getPositionCount(), newBlocks);
            }
        }
        return newPages;
    }

    public static Page[] offsetColumns(Page[] pages, int offset)
    {
        Page[] newPages = new Page[pages.length];
        for (int i = 0; i < pages.length; i++) {
            Page page = pages[i];
            Block[] newBlocks = new Block[page.getChannelCount() + offset];
            for (int channel = 0; channel < offset; channel++) {
                newBlocks[channel] = createNullRLEBlock(page.getPositionCount());
            }
            for (int channel = 0; channel < page.getChannelCount(); channel++) {
                newBlocks[channel + offset] = page.getBlock(channel);
            }
            newPages[i] = new Page(page.getPositionCount(), newBlocks);
        }
        return newPages;
    }

    private static RunLengthEncodedBlock createNullRLEBlock(int positionCount)
    {
        return (RunLengthEncodedBlock) RunLengthEncodedBlock.create(BOOLEAN, null, positionCount);
    }

    public static Object getGroupValue(GroupedAccumulator groupedAggregation, int groupId)
    {
        BlockBuilder out = groupedAggregation.getFinalType().createBlockBuilder(null, 1);
        groupedAggregation.evaluateFinal(groupId, out);
        return BlockAssertions.getOnlyValue(groupedAggregation.getFinalType(), out.build());
    }

    public static double[] constructDoublePrimitiveArray(int start, int length)
    {
        return IntStream.range(start, start + length).asDoubleStream().toArray();
    }
}
