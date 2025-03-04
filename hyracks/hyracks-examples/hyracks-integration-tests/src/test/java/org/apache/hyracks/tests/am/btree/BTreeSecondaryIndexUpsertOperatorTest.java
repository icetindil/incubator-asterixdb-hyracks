/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.hyracks.tests.am.btree;

import java.io.DataOutput;

import org.junit.Before;
import org.junit.Test;

import org.apache.hyracks.api.constraints.PartitionConstraintHelper;
import org.apache.hyracks.api.dataflow.IOperatorDescriptor;
import org.apache.hyracks.api.dataflow.value.ISerializerDeserializer;
import org.apache.hyracks.api.dataflow.value.RecordDescriptor;
import org.apache.hyracks.api.job.JobSpecification;
import org.apache.hyracks.dataflow.common.comm.io.ArrayTupleBuilder;
import org.apache.hyracks.dataflow.common.data.marshalling.UTF8StringSerializerDeserializer;
import org.apache.hyracks.dataflow.std.connectors.OneToOneConnectorDescriptor;
import org.apache.hyracks.dataflow.std.file.ConstantFileSplitProvider;
import org.apache.hyracks.dataflow.std.file.FileSplit;
import org.apache.hyracks.dataflow.std.file.IFileSplitProvider;
import org.apache.hyracks.dataflow.std.file.PlainFileWriterOperatorDescriptor;
import org.apache.hyracks.dataflow.std.misc.ConstantTupleSourceOperatorDescriptor;
import org.apache.hyracks.storage.am.btree.dataflow.BTreeSearchOperatorDescriptor;
import org.apache.hyracks.storage.am.common.dataflow.IIndexDataflowHelperFactory;
import org.apache.hyracks.storage.am.common.impls.NoOpOperationCallbackFactory;

public class BTreeSecondaryIndexUpsertOperatorTest extends AbstractBTreeOperatorTest {

    @Before
    public void setup() throws Exception {
        super.setup();
        createPrimaryIndex();
        loadPrimaryIndex();
        createSecondaryIndex();
        loadSecondaryIndex();
        insertPipeline(true);
    }

    @Test
    public void searchUpdatedSecondaryIndexTest() throws Exception {
        JobSpecification spec = new JobSpecification();

        // build tuple containing search keys (only use the first key as search
        // key)
        ArrayTupleBuilder tb = new ArrayTupleBuilder(secondaryKeyFieldCount);
        DataOutput dos = tb.getDataOutput();

        tb.reset();
        // low key
        UTF8StringSerializerDeserializer.INSTANCE.serialize("1998-07-21", dos);
        tb.addFieldEndOffset();
        // high key
        UTF8StringSerializerDeserializer.INSTANCE.serialize("2000-10-18", dos);
        tb.addFieldEndOffset();

        ISerializerDeserializer[] keyRecDescSers = { UTF8StringSerializerDeserializer.INSTANCE,
                UTF8StringSerializerDeserializer.INSTANCE };
        RecordDescriptor keyRecDesc = new RecordDescriptor(keyRecDescSers);

        ConstantTupleSourceOperatorDescriptor keyProviderOp = new ConstantTupleSourceOperatorDescriptor(spec,
                keyRecDesc, tb.getFieldEndOffsets(), tb.getByteArray(), tb.getSize());
        PartitionConstraintHelper.addAbsoluteLocationConstraint(spec, keyProviderOp, NC1_ID);

        int[] secondaryLowKeyFields = { 0 };
        int[] secondaryHighKeyFields = { 1 };

        // search secondary index
        BTreeSearchOperatorDescriptor secondaryBtreeSearchOp = new BTreeSearchOperatorDescriptor(spec,
                secondaryRecDesc, storageManager, lcManagerProvider, secondarySplitProvider, secondaryTypeTraits,
                secondaryComparatorFactories, secondaryBloomFilterKeyFields, secondaryLowKeyFields,
                secondaryHighKeyFields, true, true, dataflowHelperFactory, false, false, null,
                NoOpOperationCallbackFactory.INSTANCE, null, null);

        PartitionConstraintHelper.addAbsoluteLocationConstraint(spec, secondaryBtreeSearchOp, NC1_ID);

        // second field from the tuples coming from secondary index
        int[] primaryLowKeyFields = { 1 };
        // second field from the tuples coming from secondary index
        int[] primaryHighKeyFields = { 1 };

        // search primary index
        BTreeSearchOperatorDescriptor primaryBtreeSearchOp = new BTreeSearchOperatorDescriptor(spec, primaryRecDesc,
                storageManager, lcManagerProvider, primarySplitProvider, primaryTypeTraits, primaryComparatorFactories,
                primaryBloomFilterKeyFields, primaryLowKeyFields, primaryHighKeyFields, true, true,
                dataflowHelperFactory, false, false, null, NoOpOperationCallbackFactory.INSTANCE, null, null);
        PartitionConstraintHelper.addAbsoluteLocationConstraint(spec, primaryBtreeSearchOp, NC1_ID);

        IFileSplitProvider outSplits = new ConstantFileSplitProvider(new FileSplit[] { new FileSplit(NC1_ID,
                createTempFile().getAbsolutePath()) });
        IOperatorDescriptor printer = new PlainFileWriterOperatorDescriptor(spec, outSplits, ",");
        PartitionConstraintHelper.addAbsoluteLocationConstraint(spec, printer, NC1_ID);

        spec.connect(new OneToOneConnectorDescriptor(spec), keyProviderOp, 0, secondaryBtreeSearchOp, 0);
        spec.connect(new OneToOneConnectorDescriptor(spec), secondaryBtreeSearchOp, 0, primaryBtreeSearchOp, 0);
        spec.connect(new OneToOneConnectorDescriptor(spec), primaryBtreeSearchOp, 0, printer, 0);

        spec.addRoot(printer);
        runTest(spec);
    }

    @Override
    protected IIndexDataflowHelperFactory createDataFlowHelperFactory() {
        return ((BTreeOperatorTestHelper) testHelper).createDataFlowHelperFactory();
    }

    @Override
    public void cleanup() throws Exception {
        destroyPrimaryIndex();
        destroySecondaryIndex();
    }
}
