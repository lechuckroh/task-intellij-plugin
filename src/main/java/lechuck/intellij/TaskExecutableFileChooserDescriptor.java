package lechuck.intellij;

import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileElement;
import com.intellij.openapi.vfs.VirtualFile;

public class TaskExecutableFileChooserDescriptor extends FileChooserDescriptor {
    public TaskExecutableFileChooserDescriptor() {
        super(true, false, false, false, false, false);

        //noinspection DialogTitleCapitalization
        setTitle("Select task executable");
    }

    @Override
    public boolean isFileVisible(VirtualFile file, boolean showHiddenFiles) {
        if (!showHiddenFiles && FileElement.isFileHidden(file)) {
            return false;
        }
        if (file.isDirectory()) {
            return true;
        }
        String filename = file.getName();
        if (filename.equalsIgnoreCase("task") || filename.equalsIgnoreCase("task.exe")) {
            return true;
        }

        return super.isFileVisible(file, showHiddenFiles);
    }

    @Override
    public boolean isFileSelectable(VirtualFile file) {
        return !file.isDirectory() && isFileVisible(file, true);
    }
}
