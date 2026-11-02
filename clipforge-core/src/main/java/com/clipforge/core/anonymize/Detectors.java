package com.clipforge.core.anonymize;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * License plate and face detection with ONNX Runtime (CPU). One instance is shared by all encoders; ONNX Runtime
 * sessions are thread-safe.
 * <ul>
 *   <li>Plates: open-image-models YOLOv9 "end2end" (NMS inside the model), RGB 0..1, letterbox padded with 114;
 *       output rows {@code [batch, x1, y1, x2, y2, class, score]} in model pixels.</li>
 *   <li>Faces: OpenCV Zoo YuNet 2023mar, BGR 0..255; per stride 8/16/32 the heads {@code cls}, {@code obj} (already
 *       sigmoid) and {@code bbox} (offsets and log sizes per grid cell), decoded like OpenCV's FaceDetectorYN.</li>
 * </ul>
 */
public final class Detectors implements AutoCloseable {

    static final int SIZE = 640;
    /** Low thresholds: for anonymising, a missed plate is worse than an extra blurred patch. */
    private static final double PLATE_THRESHOLD = 0.25;
    private static final double FACE_THRESHOLD = 0.45;
    private static final double NMS_IOU = 0.4;

    private final OrtEnvironment env = OrtEnvironment.getEnvironment();
    private final OrtSession plates;
    private final OrtSession faces;

    /** Extra plate tiles across the road band; 0 = whole frame only. */
    private final int tiles;

    /** @param plateModel or {@code null} to skip plates; @param faceModel or {@code null} to skip faces */
    public Detectors(Path plateModel, Path faceModel) throws IOException {
        this(plateModel, faceModel, 3);
    }

    public Detectors(Path plateModel, Path faceModel, int tiles) throws IOException {
        this.tiles = tiles;
        try {
            OrtSession.SessionOptions o = new OrtSession.SessionOptions();
            // half the cores: the decoder, the encoder and a second segment run at the same time
            o.setIntraOpNumThreads(Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
            o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            plates = plateModel == null ? null : env.createSession(plateModel.toString(), o);
            faces = faceModel == null ? null : env.createSession(faceModel.toString(), o);
        } catch (OrtException e) {
            throw new IOException("Detection models could not be loaded: " + e.getMessage(), e);
        }
    }

    /** Plates and faces in the frame, in frame pixels. */
    public List<Box> detect(YuvFrame frame) throws IOException {
        List<Box> all = new ArrayList<>();
        try {
            if (plates != null) {
                all.addAll(detectPlates(frame));
            }
            if (faces != null) {
                all.addAll(detectFaces(frame));
            }
        } catch (OrtException e) {
            throw new IOException("Detection failed: " + e.getMessage(), e);
        }
        return all;
    }

    /**
     * The whole frame, plus overlapping tiles across the band where plates usually are (below the horizon, above
     * the bonnet): in the whole 2304 px frame scaled to 640 a plate 20 m away has only a few pixels.
     */
    private List<Box> detectPlates(YuvFrame frame) throws OrtException {
        List<Box> out = new ArrayList<>(platesIn(frame, 0, 0, frame.width, frame.height));
        if (tiles > 0 && frame.width >= 1280) {
            int bandY = (int) (frame.height * 0.30);
            int bandH = (int) (frame.height * 0.62);
            int tileW = Math.min(frame.width, (int) Math.round(frame.width / (tiles * 0.8)));
            for (int i = 0; i < tiles; i++) {
                int x = tiles == 1 ? (frame.width - tileW) / 2 : (int) Math.round((frame.width - tileW) * i / (double) (tiles - 1));
                out.addAll(platesIn(frame, x, bandY, tileW, bandH));
            }
        }
        return nms(out); // a plate seen in the frame and in a tile counts once
    }

    private List<Box> platesIn(YuvFrame frame, int rx, int ry, int rw, int rh) throws OrtException {
        float[] chw = new float[3 * SIZE * SIZE];
        double f = frame.letterbox(chw, SIZE, false, 1f / 255, 114, rx, ry, rw, rh);
        String input = plates.getInputNames().iterator().next();
        try (OnnxTensor t = OnnxTensor.createTensor(env, FloatBuffer.wrap(chw), new long[]{1, 3, SIZE, SIZE});
             OrtSession.Result r = plates.run(Map.of(input, t))) {
            float[][] rows = (float[][]) r.get(0).getValue();
            List<Box> out = new ArrayList<>();
            for (float[] row : rows) {
                if (row.length < 7 || row[6] < PLATE_THRESHOLD) {
                    continue;
                }
                double x1 = rx + row[1] / f;
                double y1 = ry + row[2] / f;
                double x2 = rx + row[3] / f;
                double y2 = ry + row[4] / f;
                out.add(new Box(Box.Kind.PLATE, x1, y1, x2 - x1, y2 - y1, row[6]));
            }
            return out;
        }
    }

    private List<Box> detectFaces(YuvFrame frame) throws OrtException {
        float[] chw = new float[3 * SIZE * SIZE];
        double f = frame.letterbox(chw, SIZE, true, 1f, 0);
        String input = faces.getInputNames().iterator().next();
        List<Box> found = new ArrayList<>();
        try (OnnxTensor t = OnnxTensor.createTensor(env, FloatBuffer.wrap(chw), new long[]{1, 3, SIZE, SIZE});
             OrtSession.Result r = faces.run(Map.of(input, t))) {
            for (int stride : new int[]{8, 16, 32}) {
                float[][] cls = ((float[][][]) r.get("cls_" + stride).orElseThrow().getValue())[0];
                float[][] obj = ((float[][][]) r.get("obj_" + stride).orElseThrow().getValue())[0];
                float[][] bbox = ((float[][][]) r.get("bbox_" + stride).orElseThrow().getValue())[0];
                int cols = SIZE / stride;
                for (int i = 0; i < cls.length; i++) {
                    double c = Math.max(0, Math.min(1, cls[i][0]));
                    double o = Math.max(0, Math.min(1, obj[i][0]));
                    double score = Math.sqrt(c * o);
                    if (score < FACE_THRESHOLD) {
                        continue;
                    }
                    int row = i / cols;
                    int col = i % cols;
                    double cx = (col + bbox[i][0]) * stride;
                    double cy = (row + bbox[i][1]) * stride;
                    double w = Math.exp(bbox[i][2]) * stride;
                    double h = Math.exp(bbox[i][3]) * stride;
                    found.add(new Box(Box.Kind.FACE, (cx - w / 2) / f, (cy - h / 2) / f, w / f, h / f, score));
                }
            }
        }
        return nms(found);
    }

    static List<Box> nms(List<Box> boxes) {
        List<Box> sorted = new ArrayList<>(boxes);
        sorted.sort(Comparator.comparingDouble(Box::score).reversed());
        List<Box> kept = new ArrayList<>();
        for (Box b : sorted) {
            if (kept.stream().noneMatch(k -> k.iou(b) > NMS_IOU)) {
                kept.add(b);
            }
        }
        return kept;
    }

    @Override
    public void close() {
        try {
            if (plates != null) {
                plates.close();
            }
            if (faces != null) {
                faces.close();
            }
        } catch (OrtException ignored) {
            // closing only frees native memory
        }
    }
}
