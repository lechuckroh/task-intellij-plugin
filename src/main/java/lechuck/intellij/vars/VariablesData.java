package lechuck.intellij.vars;

import com.google.common.collect.ImmutableMap;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Holds variables configuration:
 */
public final class VariablesData {
    public static final VariablesData DEFAULT = new VariablesData(Collections.emptyMap());

    private static final String VARS = "vars";
    private static final String VAR = VariablesComponent.VAR;
    private static final String NAME = VariablesComponent.NAME;
    private static final String VALUE = VariablesComponent.VALUE;

    private final Map<String, String> myVars;

    private VariablesData(@NotNull Map<String, String> vars) {
        // insertion order must be preserved - Map.copyOf cannot be used here
        myVars = vars.isEmpty() ? new HashMap<>() : Collections.unmodifiableMap(new LinkedHashMap<>(vars));
    }

    /**
     * @return immutable Map instance containing variables (iteration order is reliable user-specified)
     */
    @NotNull
    public Map<String, String> getVars() {
        return myVars;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        VariablesData data = (VariablesData) o;
        return myVars.equals(data.myVars);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = myVars.hashCode();
        result = prime * result;
        return result;
    }

    @Override
    public String toString() {
        return "vars=" + myVars;
    }

    @NotNull
    public static VariablesData readExternal(@NotNull Element element) {
        Element varsElement = element.getChild(VARS);
        if (varsElement == null) {
            return DEFAULT;
        }
        Map<String, String> vars = Collections.emptyMap();
        for (Element varElement : varsElement.getChildren(VAR)) {
            String varName = varElement.getAttributeValue(NAME);
            String varValue = varElement.getAttributeValue(VALUE);
            if (varName != null && varValue != null) {
                if (vars.isEmpty()) {
                    vars = new LinkedHashMap<>();
                }
                vars.put(varName, varValue);
            }
        }
        return create(vars);
    }

    public void writeExternal(@NotNull Element parent) {
        Element varsElement = new Element(VARS);
        for (Map.Entry<String, String> entry : myVars.entrySet()) {
            varsElement.addContent(new Element(VAR).setAttribute(NAME, entry.getKey()).setAttribute(VALUE, entry.getValue()));
        }
        parent.addContent(varsElement);
    }

    /**
     * @param vars Map instance containing variables
     *             (iteration order should be reliable user-specified, like {@link LinkedHashMap} or {@link ImmutableMap})
     */
    public static @NotNull VariablesData create(@NotNull Map<String, String> vars) {
        return new VariablesData(vars);
    }

    public @NotNull VariablesData with(@NotNull Map<String, String> vars) {
        return new VariablesData(vars);
    }
}
