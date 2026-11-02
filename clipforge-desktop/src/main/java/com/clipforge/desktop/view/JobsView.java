package com.clipforge.desktop.view;

import atlantafx.base.theme.Styles;
import com.clipforge.core.compress.CompressState;
import com.clipforge.core.ffmpeg.Progress;
import com.clipforge.core.i18n.Messages;
import com.clipforge.core.project.JobRecord;
import com.clipforge.core.project.JobState;
import com.clipforge.core.project.JobType;
import com.clipforge.core.util.Formats;
import com.clipforge.core.util.OutputNaming;
import com.clipforge.desktop.AppController;
import javafx.animation.Animation;
import javafx.animation.PauseTransition;
import javafx.application.HostServices;
import javafx.collections.MapChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Job queue with per-job progress and queue controls (pause / stop now / resume). */
public final class JobsView extends VBox {

    private final AppController controller;
    private final HostServices host;
    private final ListView<JobRecord> list = new ListView<>();

    public JobsView(AppController controller, HostServices host, ListenerBag listeners) {
        this.controller = controller;
        this.host = host;
        setSpacing(6);
        setPadding(new Insets(8, 12, 8, 12));
        getStyleClass().add("jobs-view");

        Label title = new Label(Messages.get("jobs.title"));
        title.getStyleClass().add(Styles.TITLE_4);

        Button pause = new Button(Messages.get("jobs.pause"));
        pause.setTooltip(new Tooltip(Messages.get("jobs.pause.tip")));
        pause.setOnAction(e -> controller.pauseQueue());
        Button stop = new Button(Messages.get("jobs.stopNow"));
        stop.setTooltip(new Tooltip(Messages.get("jobs.stopNow.tip")));
        stop.getStyleClass().add(Styles.DANGER);
        stop.setOnAction(e -> controller.stopQueueNow());
        Button resume = new Button(Messages.get("jobs.resume"));
        resume.getStyleClass().add(Styles.SUCCESS);
        resume.setOnAction(e -> controller.resumeQueue());
        Button clear = new Button(Messages.get("jobs.clearFinished"));
        clear.setOnAction(e -> controller.clearFinishedJobs());

        pause.disableProperty().bind(controller.queueRunningProperty().not()
                .or(controller.queuePausedProperty()));
        stop.disableProperty().bind(controller.queueRunningProperty().not());
        resume.disableProperty().bind(controller.queuePausedProperty().not());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(8, title, spacer, pause, stop, resume, clear);
        header.setAlignment(Pos.CENTER_LEFT);

        list.setItems(controller.jobs());
        list.setCellFactory(l -> new JobCell());
        list.setPlaceholder(new Label(Messages.get("jobs.empty")));
        list.setPrefHeight(170);
        VBox.setVgrow(list, Priority.ALWAYS);

        // progress arrives several times per second: refresh the list at most ~4 times per second
        PauseTransition throttle = new PauseTransition(javafx.util.Duration.millis(250));
        throttle.setOnFinished(e -> list.refresh());
        listeners.on(controller.progress(), (MapChangeListener<String, Progress>) c -> {
            if (throttle.getStatus() != Animation.Status.RUNNING) {
                throttle.playFromStart();
            }
        });
        getChildren().addAll(header, list);
    }

    private final class JobCell extends ListCell<JobRecord> {
        private final Label name = new Label();
        private final Label state = new Label();
        private final Label detail = new Label();
        private final ProgressBar bar = new ProgressBar(0);
        private final Button cancel = new Button(Messages.get("common.cancel"));
        private final Button retry = new Button(Messages.get("jobs.retry"));
        private final Button open = new Button(Messages.get("jobs.openFolder"));
        private final Button watch = new Button(Messages.get("jobs.openOutput"));
        private final Label kind = new Label();
        private final VBox box;

        JobCell() {
            name.getStyleClass().add(Styles.TEXT_BOLD);
            detail.getStyleClass().addAll(Styles.TEXT_SMALL, Styles.TEXT_MUTED);
            bar.setMaxWidth(Double.MAX_VALUE);
            cancel.getStyleClass().addAll(Styles.SMALL, Styles.FLAT);
            retry.getStyleClass().addAll(Styles.SMALL, Styles.FLAT, Styles.ACCENT);
            open.getStyleClass().addAll(Styles.SMALL, Styles.FLAT);
            watch.getStyleClass().addAll(Styles.SMALL, Styles.FLAT);
            watch.setTooltip(new Tooltip(Messages.get("jobs.openOutput.tip")));
            retry.setTooltip(new Tooltip(Messages.get("jobs.retry.tip")));
            kind.getStyleClass().addAll(Styles.TEXT_SMALL, Styles.TEXT_MUTED);
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox top = new HBox(8, kind, name, state, spacer, watch, open, retry, cancel);
            top.setAlignment(Pos.CENTER_LEFT);
            box = new VBox(4, top, bar, detail);
        }

        @Override
        protected void updateItem(JobRecord job, boolean empty) {
            super.updateItem(job, empty);
            if (empty || job == null) {
                setGraphic(null);
                return;
            }
            Path out = Path.of(job.output());
            name.setText(out.getFileName().toString());
            setTooltip(null);
            boolean compress = job.getType() == JobType.COMPRESS;
            kind.setText(Messages.get(compress ? "jobs.kind.compress" : "jobs.kind.merge"));
            // the growing output can be watched while compressing (video only until the final mux)
            Path growing = OutputNaming.partOf(out);
            JobState s = job.getState();
            state.setText(Messages.get("jobs.state." + s.name().toLowerCase()));
            state.getStyleClass().removeAll(Styles.SUCCESS, Styles.DANGER, Styles.WARNING, Styles.ACCENT);
            state.getStyleClass().add(switch (s) {
                case DONE -> Styles.SUCCESS;
                case FAILED -> Styles.DANGER;
                case PAUSED, CANCELLED -> Styles.WARNING;
                default -> Styles.ACCENT;
            });

            Progress p = controller.progress().get(job.getId());
            Duration total = Duration.ofMillis(job.totalDurationMs());
            switch (s) {
                case DONE -> {
                    bar.setProgress(1);
                    detail.setText(Messages.get("jobs.detail.done", Formats.duration(total),
                            Formats.size(sizeOf(out))));
                }
                case RUNNING -> {
                    bar.setProgress(p == null || p.fraction() < 0 ? ProgressBar.INDETERMINATE_PROGRESS : p.fraction());
                    if (compress && p != null && p.frames() < 0) {
                        bar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
                        detail.setText(Messages.get("jobs.detail.finishing"));
                    } else if (compress) {
                        CompressState st = job.getCompress();
                        detail.setText(p == null ? Messages.get("jobs.detail.starting")
                                : Messages.get("jobs.detail.compressing", Formats.duration(p.processed()),
                                Formats.duration(total), Formats.duration(Duration.ofMillis(controller.appendedMs(job))),
                                p.speed(), Formats.duration(p.eta()), Formats.size(st.getCommittedBytes())));
                    } else {
                        detail.setText(p == null ? Messages.get("jobs.detail.starting")
                                : Messages.get("jobs.detail.running", Formats.duration(p.processed()),
                                Formats.duration(total), p.speed(),
                                Formats.duration(p.eta()), Formats.size(p.outputBytes())));
                    }
                }
                case FAILED -> {
                    bar.setProgress(0);
                    String msg = Messages.get(job.getErrorKey() == null ? "error.unknown" : job.getErrorKey(),
                            job.getErrorArgs().toArray());
                    detail.setText(msg);
                    setTooltip(job.getErrorDetail() == null ? null : new Tooltip(job.getErrorDetail()));
                }
                default -> {
                    bar.setProgress(0);
                    if (compress && controller.appendedMs(job) > 0) {
                        detail.setText(Messages.get("jobs.detail.compressPaused",
                                Formats.duration(Duration.ofMillis(controller.appendedMs(job))),
                                Formats.duration(total), Formats.size(job.getCompress().getCommittedBytes())));
                    } else {
                        detail.setText(Messages.get("jobs.detail.inputs", job.inputCount(),
                                Formats.duration(total), Formats.size(job.totalBytes())));
                    }
                }
            }
            // what happened to the sources the user asked to delete
            String deleted = deletionText(job);
            if (deleted != null) {
                detail.setText(detail.getText() + " · " + deleted);
            }
            bar.setVisible(s == JobState.RUNNING);
            bar.setManaged(s == JobState.RUNNING);
            cancel.setVisible(s.unfinished());
            cancel.setManaged(s.unfinished());
            cancel.setOnAction(e -> controller.cancelJob(job));
            retry.setVisible(s == JobState.FAILED);
            retry.setManaged(s == JobState.FAILED);
            retry.setOnAction(e -> controller.retryJob(job));
            open.setVisible(s == JobState.DONE);
            open.setManaged(s == JobState.DONE);
            boolean canWatch = compress && (s == JobState.RUNNING || s == JobState.PAUSED) && Files.exists(growing);
            Path toWatch = s == JobState.DONE ? out : growing;
            watch.setVisible(canWatch || s == JobState.DONE);
            watch.setManaged(canWatch || s == JobState.DONE);
            watch.setOnAction(e -> {
                if (!Files.exists(toWatch)) {
                    controller.setStatus("status.fileMissing", toWatch.getFileName());
                } else if (host != null) {
                    host.showDocument(toWatch.toAbsolutePath().toUri().toString());
                }
            });
            open.setOnAction(e -> {
                if (host != null) {
                    host.showDocument(out.toAbsolutePath().getParent().toUri().toString());
                }
            });
            setGraphic(box);
        }

        private long sizeOf(Path p) {
            try {
                return Files.size(p);
            } catch (Exception e) {
                return -1;
            }
        }

        /** "12 source files moved to the Recycle Bin · 1 kept", or {@code null} if nothing was to be deleted. */
        private static String deletionText(JobRecord job) {
            if (job.getSourceDeletion() == null) {
                return null;
            }
            List<String> parts = new ArrayList<>();
            int deleted = job.getDeletedSources().size();
            if (deleted > 0) {
                parts.add(Messages.get(job.getSourceDeletion().permanent() ? "jobs.deleted.permanent" : "jobs.deleted",
                        deleted));
            }
            if (!job.getKeptSources().isEmpty()) {
                parts.add(Messages.get("jobs.kept", job.getKeptSources().size()));
            }
            if (job.getKeptPartial() != null) {
                parts.add(Messages.get("jobs.partialKept", Path.of(job.getKeptPartial()).getFileName()));
            }
            return parts.isEmpty() ? null : String.join(" · ", parts);
        }
    }
}
