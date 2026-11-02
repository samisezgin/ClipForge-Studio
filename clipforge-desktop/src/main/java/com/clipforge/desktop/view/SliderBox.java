package com.clipforge.desktop.view;

import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyDoubleWrapper;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.util.StringConverter;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;

/**
 * A slider with a box for typing the exact value. Both stay in sync; typed values are rounded to the step and
 * clamped to the allowed range. The slider may cover only part of the range (e.g. 1x-10x while 100x can be typed);
 * beyond its end it rests at the maximum.
 */
final class SliderBox extends HBox {

    private final Slider slider = new Slider();
    private final Spinner<Double> box = new Spinner<>();
    private final SpinnerValueFactory.DoubleSpinnerValueFactory factory;
    private final ReadOnlyDoubleWrapper value = new ReadOnlyDoubleWrapper();
    private final double step;
    private boolean syncing;

    SliderBox(double min, double max, double sliderMax, double step, double initial) {
        super(8);
        this.step = step;
        factory = new SpinnerValueFactory.DoubleSpinnerValueFactory(min, max, initial, step);
        factory.setConverter(new Converter(step, value::get));
        box.setValueFactory(factory);
        box.setEditable(true);
        box.setPrefWidth(95);
        slider.setMin(min);
        slider.setMax(sliderMax);
        slider.setBlockIncrement(step);
        HBox.setHgrow(slider, Priority.ALWAYS);
        slider.setMaxWidth(Double.MAX_VALUE);
        getChildren().addAll(slider, box);

        slider.valueProperty().addListener((o, a, b) -> {
            if (!syncing) {
                set(b.doubleValue(), true);
            }
        });
        factory.valueProperty().addListener((o, a, b) -> {
            if (!syncing && b != null) {
                set(b, false);
            }
        });
        // typing commits on Enter and when the box loses focus; invalid text restores the current value
        box.focusedProperty().addListener((o, was, now) -> {
            if (!now) {
                commitText();
            }
        });
        set(initial, false);
    }

    private void commitText() {
        set(factory.getConverter().fromString(box.getEditor().getText()), false);
    }

    private void set(double v, boolean fromSlider) {
        double clamped = Math.max(factory.getMin(), Math.min(factory.getMax(), round(v)));
        syncing = true;
        try {
            if (fromSlider && slider.getValue() >= slider.getMax() && value.get() > slider.getMax()) {
                // the slider sits at its end while a larger typed value is in effect: keep the typed value
                return;
            }
            factory.setValue(clamped);
            box.getEditor().setText(factory.getConverter().toString(clamped));
            slider.setValue(Math.min(clamped, slider.getMax()));
            value.set(clamped);
        } finally {
            syncing = false;
        }
    }

    private double round(double v) {
        return Math.round(v / step) * step;
    }

    /** Changes the allowed range; the current value is clamped into it. */
    void setRange(double min, double max, double sliderMax) {
        syncing = true;
        try {
            factory.setMin(min);
            factory.setMax(max);
            slider.setMin(min);
            slider.setMax(sliderMax);
        } finally {
            syncing = false;
        }
        set(value.get(), false);
    }

    void setValue(double v) {
        set(v, false);
    }

    double getValue() {
        return value.get();
    }

    ReadOnlyDoubleProperty valueProperty() {
        return value.getReadOnlyProperty();
    }

    Slider slider() {
        return slider;
    }

    Spinner<Double> box() {
        return box;
    }

    /**
     * Shows whole numbers without decimals; accepts both "2.5" and "2,5". Text that is not a number gives the
     * current value back (the spinner must never receive {@code null}).
     */
    private static final class Converter extends StringConverter<Double> {
        private final DecimalFormat format;
        private final java.util.function.DoubleSupplier current;

        Converter(double step, java.util.function.DoubleSupplier current) {
            format = new DecimalFormat(step >= 1 ? "0" : "0.##", DecimalFormatSymbols.getInstance());
            this.current = current;
        }

        @Override
        public String toString(Double v) {
            return v == null ? "" : format.format(v);
        }

        @Override
        public Double fromString(String s) {
            if (s == null) {
                return current.getAsDouble();
            }
            String t = s.trim().replace("x", "").replace("×", "").replace(',', '.');
            try {
                double v = Double.parseDouble(t);
                return Double.isFinite(v) ? v : current.getAsDouble();
            } catch (NumberFormatException e) {
                return current.getAsDouble();
            }
        }
    }
}
