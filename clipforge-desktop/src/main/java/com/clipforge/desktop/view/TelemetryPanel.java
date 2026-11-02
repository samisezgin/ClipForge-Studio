package com.clipforge.desktop.view;

import atlantafx.base.theme.Styles;
import com.clipforge.core.i18n.Messages;
import com.clipforge.core.model.Clip;
import com.clipforge.core.telemetry.Telemetry;
import com.clipforge.core.telemetry.TelemetryExport;
import com.clipforge.core.telemetry.TelemetrySample;
import com.clipforge.desktop.AppController;
import javafx.application.HostServices;
import javafx.geometry.Insets;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

/**
 * Telemetry of the selected clips: what the camera recorded (GPS, speed or only its clock), an offline route
 * preview, a speed chart, GPX/CSV export and "show on map" (an HTML page with an OpenStreetMap map in the browser).
 */
public final class TelemetryPanel extends VBox {

    private final AppController controller;
    private final HostServices host;
    private final Label status = new Label();
    private final Canvas route = new Canvas(300, 200);
    private final LineChart<Number, Number> speed;
    private final Button read = new Button(Messages.get("telemetry.read"));
    private final Button gpx = new Button(Messages.get("telemetry.exportGpx"));
    private final Button csv = new Button(Messages.get("telemetry.exportCsv"));
    private final Button map = new Button(Messages.get("telemetry.map"));
    private Telemetry current = Telemetry.NONE;
    private Instant currentStart;
    private long generation;

    public TelemetryPanel(AppController controller, HostServices host, ListenerBag listeners) {
        this.controller = controller;
        this.host = host;
        setSpacing(10);
        setPadding(new Insets(12, 0, 0, 0));

        status.setWrapText(true);
        status.getStyleClass().addAll(Styles.TEXT_SMALL);
        NumberAxis x = new NumberAxis();
        x.setLabel(Messages.get("telemetry.axis.time"));
        NumberAxis y = new NumberAxis();
        y.setLabel(Messages.get("telemetry.axis.speed"));
        speed = new LineChart<>(x, y);
        speed.setCreateSymbols(false);
        speed.setLegendVisible(false);
        speed.setAnimated(false);
        speed.setPrefHeight(180);

        read.getStyleClass().add(Styles.ACCENT);
        read.setMaxWidth(Double.MAX_VALUE);
        read.setOnAction(e -> load());
        gpx.setOnAction(e -> export(true));
        csv.setOnAction(e -> export(false));
        map.setOnAction(e -> showMap());
        HBox exports = new HBox(6, gpx, csv, map);

        Label hint = new Label(Messages.get("telemetry.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().addAll(Styles.TEXT_SMALL, Styles.TEXT_MUTED);

        getChildren().addAll(read, status, route, speed, exports, hint);
        listeners.on(controller.selectionVersionProperty(), (o, a, b) -> clear());
        listeners.on(controller.sessions(), c -> clear());
        clear();
    }

    private void clear() {
        generation++;
        current = Telemetry.NONE;
        status.setText(Messages.get("telemetry.notRead"));
        drawRoute();
        speed.getData().clear();
        updateButtons();
    }

    private void updateButtons() {
        boolean positions = current.hasPositions();
        gpx.setDisable(!positions);
        map.setDisable(!positions);
        csv.setDisable(current.isEmpty());
        read.setDisable(controller.selectedClips().isEmpty() || controller.toolsProperty().get() == null);
    }

    private void load() {
        List<Clip> clips = controller.selectedClips();
        if (clips.isEmpty()) {
            return;
        }
        long gen = ++generation;
        status.setText(Messages.get("telemetry.reading", clips.size()));
        read.setDisable(true);
        controller.readTelemetry(clips, t -> {
            if (gen != generation) {
                return; // the selection changed meanwhile
            }
            current = t;
            currentStart = clips.getFirst().start().atZone(ZoneId.systemDefault()).toInstant();
            show();
        }, err -> {
            if (gen == generation) {
                status.setText(Messages.get("telemetry.failed", err));
                updateButtons();
            }
        });
    }

    private void show() {
        if (current.isEmpty()) {
            status.setText(Messages.get("telemetry.none"));
        } else if (!current.hasPositions()) {
            status.setText(Messages.get("telemetry.timeOnly", Messages.get("telemetry.source." + current.source()),
                    current.samples().size()));
        } else {
            status.setText(Messages.get("telemetry.positions", Messages.get("telemetry.source." + current.source()),
                    current.positionCount()));
        }
        drawRoute();
        speed.getData().clear();
        if (current.hasSpeed()) {
            XYChart.Series<Number, Number> s = new XYChart.Series<>();
            int step = Math.max(1, current.samples().size() / 2000); // keep the chart responsive
            for (int i = 0; i < current.samples().size(); i += step) {
                TelemetrySample t = current.samples().get(i);
                if (t.speedKmh() != null) {
                    s.getData().add(new XYChart.Data<>(t.offset().toMillis() / 60000.0, t.speedKmh()));
                }
            }
            speed.getData().add(s);
        }
        speed.setVisible(current.hasSpeed());
        speed.setManaged(current.hasSpeed());
        updateButtons();
    }

    /** Draws the route as a polyline in an equirectangular projection (works offline, no map tiles). */
    private void drawRoute() {
        GraphicsContext g = route.getGraphicsContext2D();
        g.clearRect(0, 0, route.getWidth(), route.getHeight());
        List<TelemetrySample> pts = current.samples().stream().filter(TelemetrySample::hasPosition).toList();
        boolean show = pts.size() >= 2;
        route.setVisible(show);
        route.setManaged(show);
        if (!show) {
            return;
        }
        double minLat = pts.stream().mapToDouble(TelemetrySample::latitude).min().orElse(0);
        double maxLat = pts.stream().mapToDouble(TelemetrySample::latitude).max().orElse(0);
        double minLon = pts.stream().mapToDouble(TelemetrySample::longitude).min().orElse(0);
        double maxLon = pts.stream().mapToDouble(TelemetrySample::longitude).max().orElse(0);
        double k = Math.cos(Math.toRadians((minLat + maxLat) / 2));
        double w = Math.max(1e-9, (maxLon - minLon) * k);
        double h = Math.max(1e-9, maxLat - minLat);
        double scale = Math.min((route.getWidth() - 20) / w, (route.getHeight() - 20) / h);
        g.setStroke(Color.web("#4493f8"));
        g.setLineWidth(2);
        g.beginPath();
        for (int i = 0; i < pts.size(); i++) {
            double px = 10 + (pts.get(i).longitude() - minLon) * k * scale;
            double py = route.getHeight() - 10 - (pts.get(i).latitude() - minLat) * scale;
            if (i == 0) {
                g.moveTo(px, py);
            } else {
                g.lineTo(px, py);
            }
        }
        g.stroke();
    }

    private void export(boolean asGpx) {
        FileChooser fc = new FileChooser();
        fc.setInitialFileName("telemetry." + (asGpx ? "gpx" : "csv"));
        fc.getExtensionFilters().add(asGpx ? new FileChooser.ExtensionFilter("GPX", "*.gpx")
                : new FileChooser.ExtensionFilter("CSV", "*.csv"));
        File f = fc.showSaveDialog(getScene().getWindow());
        if (f == null) {
            return;
        }
        try {
            int n = asGpx ? TelemetryExport.gpx(current, f.getName(), currentStart, f.toPath())
                    : TelemetryExport.csv(current, f.toPath());
            controller.setStatus("telemetry.exported", n, f.getName());
        } catch (IOException e) {
            controller.setStatus("error.io", e.getMessage());
        }
    }

    /** Writes a small HTML page with the route on an OpenStreetMap (Leaflet) map and opens it in the browser. */
    private void showMap() {
        try {
            Path html = Files.createTempFile("clipforge-route-", ".html");
            StringBuilder coords = new StringBuilder();
            current.samples().stream().filter(TelemetrySample::hasPosition).forEach(s -> coords
                    .append(String.format(Locale.ROOT, "[%.7f,%.7f],", s.latitude(), s.longitude())));
            String page = """
                    <!doctype html><html><head><meta charset="utf-8"><title>ClipForge route</title>
                    <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
                    <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
                    <style>html,body,#map{height:100%%;margin:0}</style></head><body><div id="map"></div><script>
                    var pts=[%s];var map=L.map('map');
                    L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,
                    attribution:'&copy; OpenStreetMap contributors'}).addTo(map);
                    var line=L.polyline(pts,{color:'#1f6feb',weight:4}).addTo(map);map.fitBounds(line.getBounds());
                    L.marker(pts[0]).addTo(map);L.marker(pts[pts.length-1]).addTo(map);
                    </script></body></html>
                    """.formatted(coords);
            Files.writeString(html, page, StandardCharsets.UTF_8);
            if (host != null) {
                host.showDocument(html.toUri().toString());
            }
        } catch (IOException e) {
            controller.setStatus("error.io", e.getMessage());
        }
    }
}
