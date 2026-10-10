package com.demo.agentfw.retrieval;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.huggingface.translator.CrossEncoderTranslator;
import ai.djl.inference.Predictor;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.TranslateException;
import ai.djl.util.StringPair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Cross-encoder reranking with DJL and ONNX Runtime. The query and each passage are fed together
 * through the model, which reads them jointly; that is slower than comparing embeddings but much
 * better at deciding whether a passage actually answers the question.
 *
 * <p>Pairs are scored one at a time: on the development machine 20 single calls took about 1.8 s
 * and one padded batch of 20 about 2.9 s, because a batch pads every pair to the longest one.
 *
 * <p>The predictor is not thread-safe, so calls are serialized. One user at a time is the target;
 * a small pool is the extension if that changes.
 */
@Component
@ConditionalOnProperty(prefix = "agentfw.rerank", name = "provider", havingValue = "djl", matchIfMissing = true)
public class CrossEncoderReranker implements Reranker, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CrossEncoderReranker.class);

    private final String modelName;
    private final int maxLength;
    private final HuggingFaceTokenizer untruncatedTokenizer;
    private final ZooModel<StringPair, float[]> model;
    private final Predictor<StringPair, float[]> predictor;

    @Autowired
    public CrossEncoderReranker(RerankProperties props) {
        this(props, ModelFiles::ensure);
    }

    /** Test seam: lets a test decide how missing files are obtained (it never uses the network). */
    @FunctionalInterface
    interface FileProvider {
        void ensure(Path dir, List<ModelFiles.RemoteFile> files);
    }

    CrossEncoderReranker(RerankProperties props, FileProvider files) {
        Path dir = Path.of(props.modelDir());
        ModelFiles.RemoteFile modelFile = ModelFiles.modelFile(props.modelFile());
        files.ensure(dir, List.of(modelFile, ModelFiles.TOKENIZER));

        this.maxLength = props.maxLength();
        this.modelName = "ms-marco-MiniLM-L-6-v2/" + modelFile.name();
        try {
            Path tokenizerFile = dir.resolve(ModelFiles.TOKENIZER.name());
            HuggingFaceTokenizer tokenizer = HuggingFaceTokenizer.builder()
                    .optTokenizerPath(tokenizerFile)
                    .optTruncation(true).optMaxLength(maxLength).optPadding(false).build();
            this.untruncatedTokenizer = HuggingFaceTokenizer.builder()
                    .optTokenizerPath(tokenizerFile).optTruncation(false).optPadding(false).build();
            CrossEncoderTranslator translator = CrossEncoderTranslator.builder(tokenizer)
                    .optIncludeTokenTypes(true).optSigmoid(false).build();
            Criteria<StringPair, float[]> criteria = Criteria.builder()
                    .setTypes(StringPair.class, float[].class)
                    .optModelPath(dir)
                    .optModelName(stripExtension(modelFile.name()))
                    .optEngine("OnnxRuntime")
                    .optTranslator(translator)
                    .build();
            this.model = criteria.loadModel();
            this.predictor = model.newPredictor();
        } catch (IOException | ai.djl.MalformedModelException
                 | ai.djl.repository.zoo.ModelNotFoundException e) {
            throw new IllegalStateException("Cannot load cross-encoder from " + dir + ": " + e.getMessage(), e);
        }
        log.info("Cross-encoder ready: {} (max {} tokens)", modelName, maxLength);
    }

    @Override
    public synchronized Result rerank(String query, List<String> passages) {
        float[] scores = new float[passages.size()];
        int truncated = 0;
        try {
            for (int i = 0; i < passages.size(); i++) {
                String passage = passages.get(i);
                if (untruncatedTokenizer.encode(query, passage).getIds().length > maxLength) {
                    truncated++;
                }
                scores[i] = predictor.predict(new StringPair(query, passage))[0];
            }
        } catch (TranslateException e) {
            throw new IllegalStateException("Cross-encoder scoring failed: " + e.getMessage(), e);
        }
        return new Result(scores, truncated);
    }

    @Override
    public String modelName() {
        return modelName;
    }

    @Override
    public void close() {   // Spring calls close() on AutoCloseable beans at shutdown
        predictor.close();
        model.close();
        untruncatedTokenizer.close();
    }

    private static String stripExtension(String fileName) {
        return fileName.substring(0, fileName.lastIndexOf('.'));
    }
}
