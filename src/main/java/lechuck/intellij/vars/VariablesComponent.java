package lechuck.intellij.vars;

import com.intellij.openapi.ui.LabeledComponent;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.ui.UserActivityProviderComponent;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

import javax.swing.event.ChangeListener;

public class VariablesComponent extends LabeledComponent<TextFieldWithBrowseButton>
        implements UserActivityProviderComponent {
    @NonNls public static final String VAR = "var";
    @NonNls public static final String NAME = "name";
    @NonNls public static final String VALUE = "value";

    private final VariablesTextFieldWithBrowseButton comp;

    public VariablesComponent() {
        super();
        comp = createBrowseComponent();
        setComponent(comp);
        setText("Variables");
    }

    @NotNull
    protected VariablesTextFieldWithBrowseButton createBrowseComponent() {
        return new VariablesTextFieldWithBrowseButton();
    }

    @NotNull
    public VariablesData getVarData() {
        return comp.getData();
    }

    public void setVarData(@NotNull VariablesData varData) {
        comp.setData(varData);
    }

    @Override
    public void addChangeListener(@NotNull final ChangeListener changeListener) {
        comp.addChangeListener(changeListener);
    }

    @Override
    public void removeChangeListener(@NotNull final ChangeListener changeListener) {
        comp.removeChangeListener(changeListener);
    }
}
