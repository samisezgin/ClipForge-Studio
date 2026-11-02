package com.clipforge.desktop.view;

import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.ListChangeListener;
import javafx.collections.MapChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.ObservableMap;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects listeners a view registers on long-lived controller state, so they can all be removed when the view is
 * replaced (e.g. after a language change). Without this, discarded views would keep reacting to every update.
 */
public final class ListenerBag {

    private final List<Runnable> removals = new ArrayList<>();

    public <T> void on(ObservableValue<T> value, ChangeListener<? super T> listener) {
        value.addListener(listener);
        removals.add(() -> value.removeListener(listener));
    }

    public <T> void on(ObservableList<T> list, ListChangeListener<? super T> listener) {
        list.addListener(listener);
        removals.add(() -> list.removeListener(listener));
    }

    public <K, V> void on(ObservableMap<K, V> map, MapChangeListener<? super K, ? super V> listener) {
        map.addListener(listener);
        removals.add(() -> map.removeListener(listener));
    }

    public void dispose() {
        removals.forEach(Runnable::run);
        removals.clear();
    }
}
