package lechuck.intellij.vars;

import com.intellij.openapi.util.*;
import org.jdom.Element;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.Nullable;

public class Variable implements JDOMExternalizable, Cloneable {
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
    public void readExternal(Element element) throws InvalidDataException {
        DefaultJDOMExternalizer.readExternal(this, element);
    }

    @Override
    public void writeExternal(Element element) throws WriteExternalException {
        DefaultJDOMExternalizer.writeExternal(this, element);
    }

    @Override
    public Variable clone() {
        try {
            return (Variable) super.clone();
        } catch (CloneNotSupportedException e) {
            return null;
        }
    }
}
