package lechuck.intellij.vars;

import com.google.common.collect.ImmutableMap;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.keymap.KeymapUtil;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.util.NlsContexts;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.UserActivityProviderComponent;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.event.DocumentEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class VariablesTextFieldWithBrowseButton extends TextFieldWithBrowseButton implements UserActivityProviderComponent {

    protected VariablesData myData = VariablesData.DEFAULT;
    private final List<ChangeListener> myListeners = ContainerUtil.createLockFreeCopyOnWriteList();

    public VariablesTextFieldWithBrowseButton() {
        super();
        addActionListener(e -> {
            setVars(VariablesTable.parseVarsFromText(getText()));
            createDialog().show();
        });
        getTextField().getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent e) {
                if (!StringUtil.equals(stringifyVars(myData), getText())) {
                    Map<String, String> textVars = VariablesTable.parseVarsFromText(getText());
                    myData = myData.with(textVars);
                    fireStateChanged();
                }
            }
        });
    }

    @NotNull
    protected VariablesDialog createDialog() {
        return new VariablesDialog(this);
    }

    /**
     * @return unmodifiable Map instance
     */
    @NotNull
    public Map<String, String> getVars() {
        return myData.getVars();
    }

    /**
     * @param vars Map instance containing variables
     *             (iteration order should be reliable user-specified, like {@link LinkedHashMap} or {@link ImmutableMap})
     */
    public void setVars(@NotNull Map<String, String> vars) {
        setData(myData.with(vars));
    }

    @NotNull
    public VariablesData getData() {
        return myData;
    }

    public void setData(@NotNull VariablesData data) {
        VariablesData oldData = myData;
        myData = data;
        setText(stringifyVars(data));
        if (!oldData.equals(data)) {
            fireStateChanged();
        }
    }

    @NotNull
    @Override
    protected Icon getDefaultIcon() {
        return AllIcons.General.InlineVariables;
    }

    @NotNull
    @Override
    protected Icon getHoveredIcon() {
        return AllIcons.General.InlineVariablesHover;
    }

    @NotNull
    protected String stringifyVars(@NotNull VariablesData varData) {
        if (varData.getVars().isEmpty()) {
            return "";
        }
        StringBuilder buf = new StringBuilder();
        for (Map.Entry<String, String> entry : varData.getVars().entrySet()) {
            if (!buf.isEmpty()) {
                buf.append(";");
            }
            buf.append(StringUtil.escapeChar(entry.getKey(), ';'))
                    .append("=")
                    .append(StringUtil.escapeChar(entry.getValue(), ';'));
        }
        return buf.toString();
    }

    @Override
    public void addChangeListener(@NotNull ChangeListener changeListener) {
        myListeners.add(changeListener);
    }

    @Override
    public void removeChangeListener(@NotNull ChangeListener changeListener) {
        myListeners.remove(changeListener);
    }

    private void fireStateChanged() {
        for (ChangeListener listener : myListeners) {
            listener.stateChanged(new ChangeEvent(this));
        }
    }

    protected static List<Variable> convertToVariables(Map<String, String> map) {
        return ContainerUtil.map(map.entrySet(),
                entry -> new Variable(entry.getKey(), entry.getValue()));
    }

    @Override
    protected @NotNull @NlsContexts.Tooltip String getIconTooltip() {
        return "Edit variables (" +
                KeymapUtil.getKeystrokeText(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK)) + ")";
    }
}
