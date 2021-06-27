package lechuck.intellij;

import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileElement;
import com.intellij.openapi.vfs.VirtualFile;

public class TaskfileFileChooserDescriptor extends FileChooserDescriptor {
    public TaskfileFileChooserDescriptor() {
        super(true, false, false, false, false, false);

        setTitle("Select Taskfile");
    }

    @Override
    public boolean isFileVisible(VirtualFile file, boolean showHiddenFiles) {
        if (!showHiddenFiles && FileElement.isFileHidden(file)) {
            return false;
        }
        if (file.isDirectory()) {
            return true;
        }
        String ext = file.getExtension();
        if ("yaml".equalsIgnoreCase(ext) || "yml".equalsIgnoreCase(ext)) {
            return true;
        }

        return super.isFileVisible(file, showHiddenFiles);
    }

    @Override
    public boolean isFileSelectable(VirtualFile file) {
        return !file.isDirectory() && isFileVisible(file, true);
    }
}
