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
package org.apache.hyracks.examples.text.client;

import java.io.File;

import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import org.apache.hyracks.api.client.HyracksConnection;
import org.apache.hyracks.api.client.IHyracksClientConnection;
import org.apache.hyracks.api.constraints.PartitionConstraintHelper;
import org.apache.hyracks.api.dataflow.IConnectorDescriptor;
import org.apache.hyracks.api.dataflow.IOperatorDescriptor;
import org.apache.hyracks.api.dataflow.value.IBinaryComparatorFactory;
import org.apache.hyracks.api.dataflow.value.IBinaryHashFunctionFactory;
import org.apache.hyracks.api.dataflow.value.ISerializerDeserializer;
import org.apache.hyracks.api.dataflow.value.RecordDescriptor;
import org.apache.hyracks.api.io.FileReference;
import org.apache.hyracks.api.job.JobId;
import org.apache.hyracks.api.job.JobSpecification;
import org.apache.hyracks.data.std.accessors.PointableBinaryComparatorFactory;
import org.apache.hyracks.data.std.accessors.PointableBinaryHashFunctionFactory;
import org.apache.hyracks.data.std.primitive.IntegerPointable;
import org.apache.hyracks.dataflow.common.data.marshalling.FloatSerializerDeserializer;
import org.apache.hyracks.dataflow.common.data.marshalling.IntegerSerializerDeserializer;
import org.apache.hyracks.dataflow.common.data.marshalling.UTF8StringSerializerDeserializer;
import org.apache.hyracks.dataflow.common.data.normalizers.IntegerNormalizedKeyComputerFactory;
import org.apache.hyracks.dataflow.common.data.parsers.FloatParserFactory;
import org.apache.hyracks.dataflow.common.data.parsers.IValueParserFactory;
import org.apache.hyracks.dataflow.common.data.parsers.IntegerParserFactory;
import org.apache.hyracks.dataflow.common.data.parsers.UTF8StringParserFactory;
import org.apache.hyracks.dataflow.common.data.partition.FieldHashPartitionComputerFactory;
import org.apache.hyracks.dataflow.std.base.AbstractOperatorDescriptor;
import org.apache.hyracks.dataflow.std.base.AbstractSingleActivityOperatorDescriptor;
import org.apache.hyracks.dataflow.std.connectors.MToNPartitioningConnectorDescriptor;
import org.apache.hyracks.dataflow.std.connectors.OneToOneConnectorDescriptor;
import org.apache.hyracks.dataflow.std.file.ConstantFileSplitProvider;
import org.apache.hyracks.dataflow.std.file.DelimitedDataTupleParserFactory;
import org.apache.hyracks.dataflow.std.file.FileScanOperatorDescriptor;
import org.apache.hyracks.dataflow.std.file.FileSplit;
import org.apache.hyracks.dataflow.std.file.FrameFileWriterOperatorDescriptor;
import org.apache.hyracks.dataflow.std.file.IFileSplitProvider;
import org.apache.hyracks.dataflow.std.file.PlainFileWriterOperatorDescriptor;
import org.apache.hyracks.dataflow.std.group.HashSpillableTableFactory;
import org.apache.hyracks.dataflow.std.group.IFieldAggregateDescriptorFactory;
import org.apache.hyracks.dataflow.std.group.aggregators.CountFieldAggregatorFactory;
import org.apache.hyracks.dataflow.std.group.aggregators.IntSumFieldAggregatorFactory;
import org.apache.hyracks.dataflow.std.group.aggregators.MultiFieldsAggregatorFactory;
import org.apache.hyracks.dataflow.std.group.hash.HashGroupOperatorDescriptor;
import org.apache.hyracks.dataflow.std.sort.ExternalSortOperatorDescriptor;

/**
 * The application client for the performance tests of the external hash group
 * operator.
 */
public class ExternalGroupClient {
    private static class Options {
        @Option(name = "-host", usage = "Hyracks Cluster Controller Host name", required = true)
        public String host;

        @Option(name = "-port", usage = "Hyracks Cluster Controller Port (default: 1098)")
        public int port = 1098;

        @Option(name = "-infile-splits", usage = "Comma separated list of file-splits for the input. A file-split is <node-name>:<path>", required = true)
        public String inFileSplits;

        @Option(name = "-outfile-splits", usage = "Comma separated list of file-splits for the output", required = true)
        public String outFileSplits;

        @Option(name = "-hashtable-size", usage = "Hash table size (default: 8191)", required = false)
        public int htSize = 8191;

        @Option(name = "-frame-size", usage = "Frame size (default: 32768)", required = false)
        public int frameSize = 32768;

        @Option(name = "-sortbuffer-size", usage = "Sort buffer size in frames (default: 512)", required = false)
        public int sbSize = 512;

        @Option(name = "-sort-output", usage = "Whether to sort the output (default: true)", required = false)
        public boolean sortOutput = false;

        @Option(name = "-out-plain", usage = "Whether to output plain text (default: true)", required = false)
        public boolean outPlain = true;

        @Option(name = "-algo", usage = "The algorithm to be used", required = true)
        public int algo;
    }

    /**
     * @param args
     */
    public static void main(String[] args) throws Exception {
        Options options = new Options();
        CmdLineParser parser = new CmdLineParser(options);
        parser.parseArgument(args);

        IHyracksClientConnection hcc = new HyracksConnection(options.host, options.port);

        JobSpecification job;

        for (int i = 0; i < 6; i++) {
            long start = System.currentTimeMillis();
            job = createJob(parseFileSplits(options.inFileSplits), parseFileSplits(options.outFileSplits, i),
                    options.htSize, options.sbSize, options.frameSize, options.sortOutput, options.algo,
                    options.outPlain);

            System.out.print(i + "\t" + (System.currentTimeMillis() - start));
            start = System.currentTimeMillis();
            JobId jobId = hcc.startJob(job);
            hcc.waitForCompletion(jobId);
            System.out.println("\t" + (System.currentTimeMillis() - start));
        }
    }

    private static FileSplit[] parseFileSplits(String fileSplits) {
        String[] splits = fileSplits.split(",");
        FileSplit[] fSplits = new FileSplit[splits.length];
        for (int i = 0; i < splits.length; ++i) {
            String s = splits[i].trim();
            int idx = s.indexOf(':');
            if (idx < 0) {
                throw new IllegalArgumentException("File split " + s + " not well formed");
            }
            fSplits[i] = new FileSplit(s.substring(0, idx), new FileReference(new File(s.substring(idx + 1))));
        }
        return fSplits;
    }

    private static FileSplit[] parseFileSplits(String fileSplits, int count) {
        String[] splits = fileSplits.split(",");
        FileSplit[] fSplits = new FileSplit[splits.length];
        for (int i = 0; i < splits.length; ++i) {
            String s = splits[i].trim();
            int idx = s.indexOf(':');
            if (idx < 0) {
                throw new IllegalArgumentException("File split " + s + " not well formed");
            }
            fSplits[i] = new FileSplit(s.substring(0, idx), new FileReference(new File(s.substring(idx + 1) + "_"
                    + count)));
        }
        return fSplits;
    }

    private static JobSpecification createJob(FileSplit[] inSplits, FileSplit[] outSplits, int htSize, int sbSize,
            int frameSize, boolean sortOutput, int alg, boolean outPlain) {
        JobSpecification spec = new JobSpecification(frameSize);
        IFileSplitProvider splitsProvider = new ConstantFileSplitProvider(inSplits);

        RecordDescriptor inDesc = new RecordDescriptor(new ISerializerDeserializer[] {
                IntegerSerializerDeserializer.INSTANCE, IntegerSerializerDeserializer.INSTANCE,
                IntegerSerializerDeserializer.INSTANCE, IntegerSerializerDeserializer.INSTANCE,
                IntegerSerializerDeserializer.INSTANCE, FloatSerializerDeserializer.INSTANCE,
                FloatSerializerDeserializer.INSTANCE, FloatSerializerDeserializer.INSTANCE,
                UTF8StringSerializerDeserializer.INSTANCE, UTF8StringSerializerDeserializer.INSTANCE,
                UTF8StringSerializerDeserializer.INSTANCE, UTF8StringSerializerDeserializer.INSTANCE,
                UTF8StringSerializerDeserializer.INSTANCE, UTF8StringSerializerDeserializer.INSTANCE,
                UTF8StringSerializerDeserializer.INSTANCE, UTF8StringSerializerDeserializer.INSTANCE });

        FileScanOperatorDescriptor fileScanner = new FileScanOperatorDescriptor(spec, splitsProvider,
                new DelimitedDataTupleParserFactory(new IValueParserFactory[] { IntegerParserFactory.INSTANCE,
                        IntegerParserFactory.INSTANCE, IntegerParserFactory.INSTANCE, IntegerParserFactory.INSTANCE,
                        IntegerParserFactory.INSTANCE, FloatParserFactory.INSTANCE, FloatParserFactory.INSTANCE,
                        FloatParserFactory.INSTANCE, UTF8StringParserFactory.INSTANCE,
                        UTF8StringParserFactory.INSTANCE, UTF8StringParserFactory.INSTANCE,
                        UTF8StringParserFactory.INSTANCE, UTF8StringParserFactory.INSTANCE,
                        UTF8StringParserFactory.INSTANCE, UTF8StringParserFactory.INSTANCE,
                        UTF8StringParserFactory.INSTANCE, }, '|'), inDesc);

        createPartitionConstraint(spec, fileScanner, inSplits);

        // Output: each unique string with an integer count
        RecordDescriptor outDesc = new RecordDescriptor(new ISerializerDeserializer[] {
                IntegerSerializerDeserializer.INSTANCE,
                // IntegerSerializerDeserializer.INSTANCE,
                IntegerSerializerDeserializer.INSTANCE });

        // Specify the grouping key, which will be the string extracted during
        // the scan.
        int[] keys = new int[] { 0,
        // 1
        };

        AbstractOperatorDescriptor grouper;

        switch (alg) {
            case 0: // new external hash graph
                grouper = new org.apache.hyracks.dataflow.std.group.external.ExternalGroupOperatorDescriptor(spec,
                        keys, frameSize, new IBinaryComparatorFactory[] {
                        // PointableBinaryComparatorFactory.of(IntegerPointable.FACTORY),
                        PointableBinaryComparatorFactory.of(IntegerPointable.FACTORY) },
                        new IntegerNormalizedKeyComputerFactory(), new MultiFieldsAggregatorFactory(
                                new IFieldAggregateDescriptorFactory[] { new CountFieldAggregatorFactory(false) }),
                        new MultiFieldsAggregatorFactory(
                                new IFieldAggregateDescriptorFactory[] { new IntSumFieldAggregatorFactory(keys.length,
                                        false) }), outDesc, new HashSpillableTableFactory(
                                new FieldHashPartitionComputerFactory(keys, new IBinaryHashFunctionFactory[] {
                                // PointableBinaryHashFunctionFactory.of(IntegerPointable.FACTORY),
                                PointableBinaryHashFunctionFactory.of(IntegerPointable.FACTORY) }), htSize), false);

                createPartitionConstraint(spec, grouper, outSplits);

                // Connect scanner with the grouper
                IConnectorDescriptor scanGroupConnDef2 = new MToNPartitioningConnectorDescriptor(spec,
                        new FieldHashPartitionComputerFactory(keys, new IBinaryHashFunctionFactory[] {
                        // PointableBinaryHashFunctionFactory.of(IntegerPointable.FACTORY),
                        PointableBinaryHashFunctionFactory.of(IntegerPointable.FACTORY) }));
                spec.connect(scanGroupConnDef2, fileScanner, 0, grouper, 0);

                break;
            case 1: // External-sort + new-precluster
                ExternalSortOperatorDescriptor sorter2 = new ExternalSortOperatorDescriptor(spec, frameSize, keys,
                        new IBinaryComparatorFactory[] {
                        // PointableBinaryComparatorFactory.of(IntegerPointable.FACTORY),
                        PointableBinaryComparatorFactory.of(IntegerPointable.FACTORY) }, inDesc);
                createPartitionConstraint(spec, sorter2, inSplits);

                // Connect scan operator with the sorter
                IConnectorDescriptor scanSortConn2 = new MToNPartitioningConnectorDescriptor(spec,
                        new FieldHashPartitionComputerFactory(keys, new IBinaryHashFunctionFactory[] {
                        // PointableBinaryHashFunctionFactory.of(IntegerPointable.FACTORY),
                        PointableBinaryHashFunctionFactory.of(IntegerPointable.FACTORY) }));
                spec.connect(scanSortConn2, fileScanner, 0, sorter2, 0);

                grouper = new org.apache.hyracks.dataflow.std.group.preclustered.PreclusteredGroupOperatorDescriptor(
                        spec, keys, new IBinaryComparatorFactory[] {
                        // PointableBinaryComparatorFactory.of(IntegerPointable.FACTORY),
                        PointableBinaryComparatorFactory.of(IntegerPointable.FACTORY) },
                        new MultiFieldsAggregatorFactory(
                                new IFieldAggregateDescriptorFactory[] { new CountFieldAggregatorFactory(true) }),
                        outDesc);

                createPartitionConstraint(spec, grouper, outSplits);

                // Connect sorter with the pre-cluster
                OneToOneConnectorDescriptor sortGroupConn2 = new OneToOneConnectorDescriptor(spec);
                spec.connect(sortGroupConn2, sorter2, 0, grouper, 0);
                break;
            case 2: // Inmem
                grouper = new HashGroupOperatorDescriptor(spec, keys, new FieldHashPartitionComputerFactory(keys,
                        new IBinaryHashFunctionFactory[] {
                        // PointableBinaryHashFunctionFactory.of(IntegerPointable.FACTORY),
                        PointableBinaryHashFunctionFactory.of(IntegerPointable.FACTORY) }),
                        new IBinaryComparatorFactory[] {
                        // PointableBinaryComparatorFactory.of(IntegerPointable.FACTORY),
                        PointableBinaryComparatorFactory.of(IntegerPointable.FACTORY) },
                        new MultiFieldsAggregatorFactory(
                                new IFieldAggregateDescriptorFactory[] { new CountFieldAggregatorFactory(true) }),
                        outDesc, htSize);

                createPartitionConstraint(spec, grouper, outSplits);

                // Connect scanner with the grouper
                IConnectorDescriptor scanConn2 = new MToNPartitioningConnectorDescriptor(spec,
                        new FieldHashPartitionComputerFactory(keys, new IBinaryHashFunctionFactory[] {
                        // PointableBinaryHashFunctionFactory.of(IntegerPointable.FACTORY),
                        PointableBinaryHashFunctionFactory.of(IntegerPointable.FACTORY) }));
                spec.connect(scanConn2, fileScanner, 0, grouper, 0);
                break;
            default:
                grouper = new org.apache.hyracks.dataflow.std.group.external.ExternalGroupOperatorDescriptor(spec,
                        keys, frameSize, new IBinaryComparatorFactory[] {
                        // PointableBinaryComparatorFactory.of(IntegerPointable.FACTORY),
                        PointableBinaryComparatorFactory.of(IntegerPointable.FACTORY) },
                        new IntegerNormalizedKeyComputerFactory(), new MultiFieldsAggregatorFactory(
                                new IFieldAggregateDescriptorFactory[] { new CountFieldAggregatorFactory(false) }),
                        new MultiFieldsAggregatorFactory(
                                new IFieldAggregateDescriptorFactory[] { new IntSumFieldAggregatorFactory(keys.length,
                                        false) }), outDesc, new HashSpillableTableFactory(
                                new FieldHashPartitionComputerFactory(keys, new IBinaryHashFunctionFactory[] {
                                // PointableBinaryHashFunctionFactory.of(IntegerPointable.FACTORY),
                                PointableBinaryHashFunctionFactory.of(IntegerPointable.FACTORY) }), htSize), false);

                createPartitionConstraint(spec, grouper, outSplits);

                // Connect scanner with the grouper
                IConnectorDescriptor scanGroupConnDef = new MToNPartitioningConnectorDescriptor(spec,
                        new FieldHashPartitionComputerFactory(keys, new IBinaryHashFunctionFactory[] {
                        // PointableBinaryHashFunctionFactory.of(IntegerPointable.FACTORY),
                        PointableBinaryHashFunctionFactory.of(IntegerPointable.FACTORY) }));
                spec.connect(scanGroupConnDef, fileScanner, 0, grouper, 0);
        }

        IFileSplitProvider outSplitProvider = new ConstantFileSplitProvider(outSplits);

        AbstractSingleActivityOperatorDescriptor writer;

        if (outPlain)
            writer = new PlainFileWriterOperatorDescriptor(spec, outSplitProvider, "|");
        else
            writer = new FrameFileWriterOperatorDescriptor(spec, outSplitProvider);

        createPartitionConstraint(spec, writer, outSplits);

        IConnectorDescriptor groupOutConn = new OneToOneConnectorDescriptor(spec);
        spec.connect(groupOutConn, grouper, 0, writer, 0);

        spec.addRoot(writer);
        return spec;
    }

    private static void createPartitionConstraint(JobSpecification spec, IOperatorDescriptor op, FileSplit[] splits) {
        String[] parts = new String[splits.length];
        for (int i = 0; i < splits.length; ++i) {
            parts[i] = splits[i].getNodeName();
        }
        PartitionConstraintHelper.addAbsoluteLocationConstraint(spec, op, parts);
    }
}