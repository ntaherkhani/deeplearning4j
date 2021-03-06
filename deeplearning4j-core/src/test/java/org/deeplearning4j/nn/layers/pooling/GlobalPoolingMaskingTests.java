package org.deeplearning4j.nn.layers.pooling;

import org.deeplearning4j.BaseDL4JTest;
import org.deeplearning4j.nn.conf.ConvolutionMode;
import org.deeplearning4j.nn.conf.MultiLayerConfiguration;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.distribution.NormalDistribution;
import org.deeplearning4j.nn.conf.layers.ConvolutionLayer;
import org.deeplearning4j.nn.conf.layers.GravesLSTM;
import org.deeplearning4j.nn.conf.layers.OutputLayer;
import org.deeplearning4j.nn.conf.layers.PoolingType;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.nn.weights.WeightInit;
import org.junit.Test;
import org.nd4j.linalg.activations.Activation;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.api.ops.impl.broadcast.BroadcastMulOp;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.indexing.NDArrayIndex;
import org.nd4j.linalg.learning.config.NoOp;
import org.nd4j.linalg.lossfunctions.LossFunctions;

import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/**
 * Created by Alex on 18/01/2017.
 */
public class GlobalPoolingMaskingTests extends BaseDL4JTest {

    @Test
    public void testMaskingRnn() {


        int timeSeriesLength = 5;
        int nIn = 5;
        int layerSize = 4;
        int nOut = 2;
        int[] minibatchSizes = new int[] {1, 3};

        for (int miniBatchSize : minibatchSizes) {

            MultiLayerConfiguration conf = new NeuralNetConfiguration.Builder()
                            .updater(new NoOp()).weightInit(WeightInit.DISTRIBUTION)
                            .dist(new NormalDistribution(0, 1.0)).seed(12345L).list()
                            .layer(0, new GravesLSTM.Builder().nIn(nIn).nOut(layerSize).activation(Activation.TANH)
                                            .build())
                            .layer(1, new org.deeplearning4j.nn.conf.layers.GlobalPoolingLayer.Builder()
                                            .poolingType(PoolingType.AVG).build())
                            .layer(2, new OutputLayer.Builder(LossFunctions.LossFunction.MCXENT)
                                            .activation(Activation.SOFTMAX).nIn(layerSize).nOut(nOut).build())
                            .pretrain(false).backprop(true).build();

            MultiLayerNetwork net = new MultiLayerNetwork(conf);
            net.init();

            Random r = new Random(12345L);
            INDArray input = Nd4j.rand(new int[] {miniBatchSize, nIn, timeSeriesLength}).subi(0.5);

            INDArray mask;
            if (miniBatchSize == 1) {
                mask = Nd4j.create(new double[] {1, 1, 1, 1, 0});
            } else {
                mask = Nd4j.create(new double[][] {{1, 1, 1, 1, 1}, {1, 1, 1, 1, 0}, {1, 1, 1, 0, 0}});
            }

            INDArray labels = Nd4j.zeros(miniBatchSize, nOut);
            for (int i = 0; i < miniBatchSize; i++) {
                int idx = r.nextInt(nOut);
                labels.putScalar(i, idx, 1.0);
            }

            net.setLayerMaskArrays(mask, null);
            INDArray outputMasked = net.output(input);

            net.clearLayerMaskArrays();

            for (int i = 0; i < miniBatchSize; i++) {
                INDArray maskRow = mask.getRow(i);
                int tsLength = maskRow.sumNumber().intValue();
                INDArray inputSubset = input.get(NDArrayIndex.interval(i, i, true), NDArrayIndex.all(),
                                NDArrayIndex.interval(0, tsLength));

                INDArray outSubset = net.output(inputSubset);
                INDArray outputMaskedSubset = outputMasked.getRow(i);

                assertEquals(outSubset, outputMaskedSubset);
            }
        }
    }

    @Test
    public void testMaskingCnnDim3_SingleExample() {
        //Test masking, where mask is along dimension 3

        int minibatch = 1;
        int depthIn = 2;
        int depthOut = 2;
        int nOut = 2;
        int height = 3;
        int width = 6;

        PoolingType[] poolingTypes =
                        new PoolingType[] {PoolingType.SUM, PoolingType.AVG, PoolingType.MAX, PoolingType.PNORM};

        for (PoolingType pt : poolingTypes) {
            MultiLayerConfiguration conf = new NeuralNetConfiguration.Builder().weightInit(WeightInit.XAVIER)
                            .convolutionMode(ConvolutionMode.Same).seed(12345L).list()
                            .layer(0, new ConvolutionLayer.Builder().nIn(depthIn).nOut(depthOut).kernelSize(height, 2)
                                            .stride(height, 1).activation(Activation.TANH).build())
                            .layer(1, new org.deeplearning4j.nn.conf.layers.GlobalPoolingLayer.Builder().poolingType(pt)
                                            .build())
                            .layer(2, new OutputLayer.Builder(LossFunctions.LossFunction.MCXENT)
                                            .activation(Activation.SOFTMAX).nIn(depthOut).nOut(nOut).build())
                            .pretrain(false).backprop(true).build();

            MultiLayerNetwork net = new MultiLayerNetwork(conf);
            net.init();

            INDArray inToBeMasked = Nd4j.rand(new int[] {minibatch, depthIn, height, width});

            //Shape for mask: [minibatch, width]
            INDArray maskArray = Nd4j.create(new double[] {1, 1, 1, 1, 1, 0});

            //Multiply the input by the mask array, to ensure the 0s in the mask correspond to 0s in the input vector
            // as would be the case in practice...
            Nd4j.getExecutioner().exec(new BroadcastMulOp(inToBeMasked, maskArray, inToBeMasked, 0, 3));


            net.setLayerMaskArrays(maskArray, null);

            INDArray outMasked = net.output(inToBeMasked);
            net.clearLayerMaskArrays();

            int numSteps = width - 1;
            INDArray subset = inToBeMasked.get(NDArrayIndex.interval(0, 0, true), NDArrayIndex.all(),
                            NDArrayIndex.all(), NDArrayIndex.interval(0, numSteps));
            assertArrayEquals(new int[] {1, depthIn, height, 5}, subset.shape());

            INDArray outSubset = net.output(subset);
            INDArray outMaskedSubset = outMasked.getRow(0);

            assertEquals(outSubset, outMaskedSubset);

            //Finally: check gradient calc for exceptions
            net.setLayerMaskArrays(maskArray, null);
            net.setInput(inToBeMasked);
            INDArray labels = Nd4j.create(new double[] {0, 1});
            net.setLabels(labels);

            net.computeGradientAndScore();
        }
    }

    @Test
    public void testMaskingCnnDim2_SingleExample() {
        //Test masking, where mask is along dimension 2

        int minibatch = 1;
        int depthIn = 2;
        int depthOut = 2;
        int nOut = 2;
        int height = 6;
        int width = 3;

        PoolingType[] poolingTypes =
                        new PoolingType[] {PoolingType.SUM, PoolingType.AVG, PoolingType.MAX, PoolingType.PNORM};

        for (PoolingType pt : poolingTypes) {
            MultiLayerConfiguration conf = new NeuralNetConfiguration.Builder().weightInit(WeightInit.XAVIER)
                            .convolutionMode(ConvolutionMode.Same).seed(12345L).list()
                            .layer(0, new ConvolutionLayer.Builder().nIn(depthIn).nOut(depthOut).kernelSize(2, width)
                                            .stride(1, width).activation(Activation.TANH).build())
                            .layer(1, new org.deeplearning4j.nn.conf.layers.GlobalPoolingLayer.Builder().poolingType(pt)
                                            .build())
                            .layer(2, new OutputLayer.Builder(LossFunctions.LossFunction.MCXENT)
                                            .activation(Activation.SOFTMAX).nIn(depthOut).nOut(nOut).build())
                            .pretrain(false).backprop(true).build();

            MultiLayerNetwork net = new MultiLayerNetwork(conf);
            net.init();

            INDArray inToBeMasked = Nd4j.rand(new int[] {minibatch, depthIn, height, width});

            //Shape for mask: [minibatch, width]
            INDArray maskArray = Nd4j.create(new double[] {1, 1, 1, 1, 1, 0});

            //Multiply the input by the mask array, to ensure the 0s in the mask correspond to 0s in the input vector
            // as would be the case in practice...
            Nd4j.getExecutioner().exec(new BroadcastMulOp(inToBeMasked, maskArray, inToBeMasked, 0, 2));


            net.setLayerMaskArrays(maskArray, null);

            INDArray outMasked = net.output(inToBeMasked);
            net.clearLayerMaskArrays();

            int numSteps = height - 1;
            INDArray subset = inToBeMasked.get(NDArrayIndex.interval(0, 0, true), NDArrayIndex.all(),
                            NDArrayIndex.interval(0, numSteps), NDArrayIndex.all());
            assertArrayEquals(new int[] {1, depthIn, 5, width}, subset.shape());

            INDArray outSubset = net.output(subset);
            INDArray outMaskedSubset = outMasked.getRow(0);

            assertEquals(outSubset, outMaskedSubset);

            //Finally: check gradient calc for exceptions
            net.setLayerMaskArrays(maskArray, null);
            net.setInput(inToBeMasked);
            INDArray labels = Nd4j.create(new double[] {0, 1});
            net.setLabels(labels);

            net.computeGradientAndScore();
        }
    }


    @Test
    public void testMaskingCnnDim3() {
        //Test masking, where mask is along dimension 3

        int minibatch = 3;
        int depthIn = 3;
        int depthOut = 4;
        int nOut = 5;
        int height = 3;
        int width = 6;

        PoolingType[] poolingTypes =
                        new PoolingType[] {PoolingType.SUM, PoolingType.AVG, PoolingType.MAX, PoolingType.PNORM};

        for (PoolingType pt : poolingTypes) {
            MultiLayerConfiguration conf = new NeuralNetConfiguration.Builder().weightInit(WeightInit.XAVIER)
                            .convolutionMode(ConvolutionMode.Same).seed(12345L).list()
                            .layer(0, new ConvolutionLayer.Builder().nIn(depthIn).nOut(depthOut).kernelSize(height, 2)
                                            .stride(height, 1).activation(Activation.TANH).build())
                            .layer(1, new org.deeplearning4j.nn.conf.layers.GlobalPoolingLayer.Builder().poolingType(pt)
                                            .build())
                            .layer(2, new OutputLayer.Builder(LossFunctions.LossFunction.MCXENT)
                                            .activation(Activation.SOFTMAX).nIn(depthOut).nOut(nOut).build())
                            .pretrain(false).backprop(true).build();

            MultiLayerNetwork net = new MultiLayerNetwork(conf);
            net.init();

            INDArray inToBeMasked = Nd4j.rand(new int[] {minibatch, depthIn, height, width});

            //Shape for mask: [minibatch, width]
            INDArray maskArray =
                            Nd4j.create(new double[][] {{1, 1, 1, 1, 1, 1}, {1, 1, 1, 1, 1, 0}, {1, 1, 1, 1, 0, 0}});

            //Multiply the input by the mask array, to ensure the 0s in the mask correspond to 0s in the input vector
            // as would be the case in practice...
            Nd4j.getExecutioner().exec(new BroadcastMulOp(inToBeMasked, maskArray, inToBeMasked, 0, 3));


            net.setLayerMaskArrays(maskArray, null);

            INDArray outMasked = net.output(inToBeMasked);
            net.clearLayerMaskArrays();

            for (int i = 0; i < minibatch; i++) {
                System.out.println(i);
                int numSteps = width - i;
                INDArray subset = inToBeMasked.get(NDArrayIndex.interval(i, i, true), NDArrayIndex.all(),
                                NDArrayIndex.all(), NDArrayIndex.interval(0, numSteps));
                assertArrayEquals(new int[] {1, depthIn, height, width - i}, subset.shape());

                INDArray outSubset = net.output(subset);
                INDArray outMaskedSubset = outMasked.getRow(i);

                assertEquals(outSubset, outMaskedSubset);
            }
        }
    }


    @Test
    public void testMaskingCnnDim2() {
        //Test masking, where mask is along dimension 2

        int minibatch = 3;
        int depthIn = 3;
        int depthOut = 4;
        int nOut = 5;
        int height = 5;
        int width = 4;

        PoolingType[] poolingTypes =
                        new PoolingType[] {PoolingType.SUM, PoolingType.AVG, PoolingType.MAX, PoolingType.PNORM};

        for (PoolingType pt : poolingTypes) {
            MultiLayerConfiguration conf = new NeuralNetConfiguration.Builder().weightInit(WeightInit.XAVIER)
                            .convolutionMode(ConvolutionMode.Same).seed(12345L).list()
                            .layer(0, new ConvolutionLayer.Builder().nIn(depthIn).nOut(depthOut).kernelSize(2, width)
                                            .stride(1, width).activation(Activation.TANH).build())
                            .layer(1, new org.deeplearning4j.nn.conf.layers.GlobalPoolingLayer.Builder().poolingType(pt)
                                            .build())
                            .layer(2, new OutputLayer.Builder(LossFunctions.LossFunction.MCXENT)
                                            .activation(Activation.SOFTMAX).nIn(depthOut).nOut(nOut).build())
                            .pretrain(false).backprop(true).build();

            MultiLayerNetwork net = new MultiLayerNetwork(conf);
            net.init();

            INDArray inToBeMasked = Nd4j.rand(new int[] {minibatch, depthIn, height, width});

            //Shape for mask: [minibatch, width]
            INDArray maskArray = Nd4j.create(new double[][] {{1, 1, 1, 1, 1}, {1, 1, 1, 1, 0}, {1, 1, 1, 0, 0}});

            //Multiply the input by the mask array, to ensure the 0s in the mask correspond to 0s in the input vector
            // as would be the case in practice...
            Nd4j.getExecutioner().exec(new BroadcastMulOp(inToBeMasked, maskArray, inToBeMasked, 0, 2));


            net.setLayerMaskArrays(maskArray, null);

            INDArray outMasked = net.output(inToBeMasked);
            net.clearLayerMaskArrays();

            for (int i = 0; i < minibatch; i++) {
                System.out.println(i);
                int numSteps = height - i;
                INDArray subset = inToBeMasked.get(NDArrayIndex.interval(i, i, true), NDArrayIndex.all(),
                                NDArrayIndex.interval(0, numSteps), NDArrayIndex.all());
                assertArrayEquals(new int[] {1, depthIn, height - i, width}, subset.shape());

                INDArray outSubset = net.output(subset);
                INDArray outMaskedSubset = outMasked.getRow(i);

                assertEquals(outSubset, outMaskedSubset);
            }
        }
    }
}
