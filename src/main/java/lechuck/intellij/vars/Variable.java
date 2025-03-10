package lechuck.intellij.vars;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.util.NlsContexts;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class Variable implements PersistentStateComponent<Variable> {
    public String NAME;
    public String VALUE;

    public Variable(@NonNls String name, @NonNls String value) {
        NAME = name;
        VALUE = value;
    }

    public Variable() {
    }

    public void setName(String name) {
        NAME = name;
    }

    public void setValue(String value) {
        VALUE = value;
    }

    public String getName() {
        return NAME;
    }

    public String getValue() {
        return VALUE;
    }

    @Nullable
    public @NlsContexts.Tooltip String getDescription() {
        return null;
    }

    @Override
    public @Nullable Variable getState() {
        return this;
    }

    @Override
    public void loadState(@NotNull Variable state) {
        this.NAME = state.NAME;
        this.VALUE = state.VALUE;
    }
}
