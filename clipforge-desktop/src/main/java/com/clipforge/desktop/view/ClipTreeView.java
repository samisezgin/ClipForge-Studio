package com.clipforge.desktop.view;

import com.clipforge.core.i18n.Messages;
import com.clipforge.core.model.Clip;
import com.clipforge.core.model.ClipStatus;
import com.clipforge.core.model.Session;
import com.clipforge.core.util.Formats;
import com.clipforge.desktop.AppController;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.ListChangeListener;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableCell;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableView;
import javafx.scene.control.skin.VirtualFlow;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Day ▸ Session ▸ Clip tree with tri-state selection. */
public final class ClipTreeView extends TreeTableView<ClipTreeView.Row> {

    /** A tree row: a day, a session or a clip. */
    public sealed interface Row permits DayRow, SessionRow, ClipRow {
    }

    public record DayRow(LocalDate day, List<Session> sessions) implements Row {
    }

    public record SessionRow(Session session) implements Row {
    }

    public record ClipRow(Clip clip) implements Row {
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final AppController controller;

    /** Keys of collapsed day/session rows; kept across rebuilds so a rescan does not reset the tree. */
    private final Set<String> collapsed = new HashSet<>();
    /** Sessions the user expanded although they would be collapsed by default. */
    private final Set<String> expanded = new HashSet<>();

    public ClipTreeView(AppController controller, ListenerBag listeners) {
        this.controller = controller;
        setShowRoot(false);
        setRoot(new TreeItem<>());
        setColumnResizePolicy(TreeTableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        setPlaceholder(new Label(Messages.get("clips.empty")));
        getStyleClass().add("clip-tree");

        TreeTableColumn<Row, Row> name = new TreeTableColumn<>(Messages.get("clips.col.name"));
        name.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().getValue()));
        name.setCellFactory(c -> new SelectCell());
        name.setPrefWidth(330);

        getColumns().add(name);
        getColumns().add(text(Messages.get("clips.col.start"), 90, this::startText));
        getColumns().add(text(Messages.get("clips.col.end"), 90, this::endText));
        getColumns().add(text(Messages.get("clips.col.duration"), 90, this::durationText));
        getColumns().add(text(Messages.get("clips.col.size"), 90, this::sizeText));
        getColumns().add(text(Messages.get("clips.col.info"), 200, this::infoText));

        listeners.on(controller.sessions(), (ListChangeListener<Session>) c -> rebuild());
        listeners.on(controller.selectionVersionProperty(), (o, a, b) -> refresh());
        rebuild();
    }

    private TreeTableColumn<Row, String> text(String title, double width, Function<Row, String> f) {
        TreeTableColumn<Row, String> col = new TreeTableColumn<>(title);
        col.setCellValueFactory(c -> new SimpleStringProperty(f.apply(c.getValue().getValue())));
        col.setPrefWidth(width);
        col.setSortable(false);
        return col;
    }

    private void rebuild() {
        Map<LocalDate, List<Session>> byDay = new LinkedHashMap<>();
        for (Session s : controller.sessions()) {
            byDay.computeIfAbsent(s.start().toLocalDate(), d -> new ArrayList<>()).add(s);
        }
        TreeItem<Row> root = new TreeItem<>();
        for (Map.Entry<LocalDate, List<Session>> e : byDay.entrySet()) {
            TreeItem<Row> day = new TreeItem<>(new DayRow(e.getKey(), e.getValue()));
            String dayKey = "d:" + e.getKey();
            day.setExpanded(!collapsed.contains(dayKey));
            track(day, dayKey, true);
            for (Session s : e.getValue()) {
                TreeItem<Row> si = new TreeItem<>(new SessionRow(s));
                String key = "s:" + s.id();
                boolean byDefault = s.clips().size() <= 3;
                si.setExpanded(byDefault ? !collapsed.contains(key) : expanded.contains(key));
                track(si, key, byDefault);
                for (Clip c : s.clips()) {
                    si.getChildren().add(new TreeItem<>(new ClipRow(c)));
                }
                day.getChildren().add(si);
            }
            root.getChildren().add(day);
        }
        // the list is rebuilt repeatedly while a scan fills it in: keep the user's scroll position
        int top = firstVisibleRow();
        setRoot(root);
        if (top > 0) {
            scrollTo(top);
        }
    }

    private int firstVisibleRow() {
        if (lookup(".virtual-flow") instanceof VirtualFlow<?> flow
                && flow.getFirstVisibleCell() != null) {
            return flow.getFirstVisibleCell().getIndex();
        }
        return 0;
    }

    /** Remembers the user's expand/collapse choice for a row across rebuilds. */
    private void track(TreeItem<Row> item, String key, boolean expandedByDefault) {
        item.expandedProperty().addListener((o, was, now) -> {
            if (expandedByDefault) {
                if (now) {
                    collapsed.remove(key);
                } else {
                    collapsed.add(key);
                }
            } else if (now) {
                expanded.add(key);
            } else {
                expanded.remove(key);
            }
        });
    }

    // ----------------------------------------------------------------- selection helpers

    private List<Clip> clipsOf(Row row) {
        return switch (row) {
            case DayRow d -> d.sessions().stream().flatMap(s -> s.clips().stream()).toList();
            case SessionRow s -> s.session().clips();
            case ClipRow c -> List.of(c.clip());
        };
    }

    private void setSelected(Row row, boolean value) {
        controller.setSelected(clipsOf(row), value);

    }

    /** {@code null} = indeterminate. */
    private Boolean selectionState(Row row) {
        List<Clip> clips = clipsOf(row).stream().filter(Clip::selectable).toList();
        if (clips.isEmpty()) {
            return false;
        }
        long n = clips.stream().filter(controller::isSelected).count();
        return n == 0 ? Boolean.FALSE : n == clips.size() ? Boolean.TRUE : null;
    }

    private final class SelectCell extends TreeTableCell<Row, Row> {
        private final CheckBox box = new CheckBox();
        private boolean updating;

        SelectCell() {
            box.setAllowIndeterminate(false);
            box.selectedProperty().addListener((o, was, now) -> {
                if (!updating && getItem() != null) {
                    setSelected(getItem(), now);
                }
            });
        }

        @Override
        protected void updateItem(Row row, boolean empty) {
            super.updateItem(row, empty);
            if (empty || row == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            updating = true;
            Boolean state = selectionState(row);
            box.setIndeterminate(state == null);
            box.setSelected(Boolean.TRUE.equals(state));
            box.setDisable(clipsOf(row).stream().noneMatch(Clip::selectable));
            updating = false;
            box.setText(label(row));
            getStyleClass().removeAll("row-day", "row-session", "row-clip", "row-disabled");
            getStyleClass().add(switch (row) {
                case DayRow d -> "row-day";
                case SessionRow s -> "row-session";
                case ClipRow c -> c.clip().selectable() ? "row-clip" : "row-disabled";
            });
            setText(null);
            setGraphic(box);
        }
    }

    // ----------------------------------------------------------------- column texts

    private String label(Row row) {
        return switch (row) {
            case DayRow d -> d.day().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)
                    .withLocale(Messages.current().locale()));
            case SessionRow s -> sessionLabel(s.session());
            case ClipRow c -> c.clip().fileName();
        };
    }

    private String sessionLabel(Session s) {
        String channel = s.channel() == null ? "" : " [" + s.channel() + "]";
        return Messages.get("clips.session", s.clips().size()) + channel;
    }

    private String startText(Row row) {
        return switch (row) {
            case DayRow d -> "";
            case SessionRow s -> s.session().start().format(TIME);
            case ClipRow c -> c.clip().start().format(TIME);
        };
    }

    private String endText(Row row) {
        return switch (row) {
            case DayRow d -> "";
            case SessionRow s -> s.session().end().format(TIME);
            case ClipRow c -> c.clip().end().format(TIME);
        };
    }

    private String durationText(Row row) {
        return Formats.duration(clipsOf(row).stream().map(Clip::duration).reduce(Duration.ZERO, Duration::plus));
    }

    private String sizeText(Row row) {
        return Formats.size(clipsOf(row).stream().mapToLong(Clip::size).sum());
    }

    private String infoText(Row row) {
        return switch (row) {
            case DayRow d -> Messages.get("clips.daySummary", d.sessions().size());
            case SessionRow s -> s.session().gapBefore() == null ? ""
                    : Messages.get("clips.gapBefore", Formats.duration(s.session().gapBefore()));
            case ClipRow c -> clipInfo(c.clip());
        };
    }

    private String clipInfo(Clip c) {
        if (c.status() == ClipStatus.COPYING) {
            return Messages.get("clips.status.copying");
        }
        if (c.status() == ClipStatus.UNREADABLE) {
            return Messages.get("clips.status.unreadable");
        }
        if (c.status() == ClipStatus.DUPLICATE) {
            return Messages.get("clips.status.duplicate");
        }
        var m = c.media();
        return m.videoCodec() + " " + m.resolution() + (m.hasAudio() ? " · " + m.audioCodec() : "");
    }
}
