package com.mindolph.fx.dialog;

import com.mindolph.base.FontIconManager;
import com.mindolph.base.constant.IconKey;
import com.mindolph.base.event.EventBus;
import com.mindolph.base.event.WorkspaceRenameEvent;
import com.mindolph.base.util.MindolphFileUtils;
import com.mindolph.core.WorkspaceManager;
import com.mindolph.core.meta.WorkspaceMeta;
import com.mindolph.fx.IconManager;
import com.mindolph.fx.constant.IconName;
import com.mindolph.fx.helper.SceneRestore;
import com.mindolph.mfx.dialog.DialogFactory;
import com.mindolph.mfx.dialog.impl.TextDialogBuilder;
import com.mindolph.mfx.preference.FxPreferences;
import com.mindolph.mfx.util.FxmlUtils;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.AnchorPane;
import org.apache.commons.io.FilenameUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URL;
import java.util.Optional;
import java.util.ResourceBundle;

import static com.mindolph.core.constant.SceneStatePrefs.MINDOLPH_ACTIVE_WORKSPACE;

/**
 * @author mindolph.com@gmail.com
 * @see WorkspaceDialog
 */
public class WorkspaceListCell extends ListCell<WorkspaceMeta> {

    @Override
    protected void updateItem(WorkspaceMeta item, boolean empty) {
        super.updateItem(item, empty);
        if (item != null) {
            FXMLLoader fxmlLoader = FxmlUtils.loadUri("/dialog/workspace_item.fxml", new WorkspaceListCell.ItemController(item));
            Node root = fxmlLoader.getRoot();
            setGraphic(root);
        }
        else {
            setText(null);
            setGraphic(null);
        }
    }


    private static class ItemController extends AnchorPane implements Initializable, EventHandler<ActionEvent> {
        private static final Logger log = LoggerFactory.getLogger(ItemController.class);
        @FXML
        ImageView imageView;
        @FXML
        Label lbWorkspaceName;
        @FXML
        Label lbWorkspacePath;
        @FXML
        Button btnActions;
        private WorkspaceMeta workspaceMeta;

        public ItemController(WorkspaceMeta workspaceMeta) {
            this.workspaceMeta = workspaceMeta;
        }

        public ItemController(WorkspaceMeta workspaceMeta, Node... children) {
            super(children);
            this.workspaceMeta = workspaceMeta;
        }

        ContextMenu contextMenu;
        MenuItem miRename;
        MenuItem miClose;
        MenuItem miOpenInSys;
        //        MenuItem miDelete;

        private void init() {
            lbWorkspaceName.setText(FilenameUtils.getBaseName(workspaceMeta.getBaseDirPath()));
            lbWorkspacePath.setText(workspaceMeta.getBaseDirPath());
            Image icon = IconManager.getInstance().getIcon(IconName.WORKSPACE);
            imageView.setImage(icon);

            btnActions.setGraphic(FontIconManager.getIns().getIcon(IconKey.GEAR));
            btnActions.setOnMouseClicked(event -> {
                if (contextMenu == null) {
                    contextMenu = new ContextMenu();
                }
                else {
                    contextMenu.getItems().clear();
                    contextMenu.hide();
                }
                miRename = new MenuItem("Rename", FontIconManager.getIns().getIcon(IconKey.RENAME));
                miClose = new MenuItem("Close", FontIconManager.getIns().getIcon(IconKey.CLOSE));
                miOpenInSys = new MenuItem("Open in System", FontIconManager.getIns().getIcon(IconKey.SYSTEM));
//                miDelete = new MenuItem("Delete", FontIconManager.getIns().getIcon(IconKey.DELETE));
                miRename.setOnAction(ItemController.this);
                miClose.setOnAction(ItemController.this);
                miOpenInSys.setOnAction(ItemController.this);
//                miDelete.setOnAction(ItemController.this);
                contextMenu.getItems().addAll(miRename, miClose, miOpenInSys);
                contextMenu.show(ItemController.this, event.getScreenX(), event.getScreenY());
            });
        }

        @Override
        public void initialize(URL location, ResourceBundle resources) {
            this.init();
        }

        @Override
        public void handle(ActionEvent event) {
            if (event.getSource() == miRename) {
                Optional<String> s = new TextDialogBuilder()
                        .owner(DialogFactory.DEFAULT_WINDOW)
                        .title("Rename %s".formatted(workspaceMeta.getName()))
                        .content("Input a workspace name")
                        .text(FilenameUtils.getBaseName(workspaceMeta.getName()))
                        .width(400)
                        .build().showAndWait();
                if (s.isPresent()) {
                    String newName = s.get();
                    File origFile = new File(workspaceMeta.getBaseDirPath());
                    File newNameFile = new File(origFile.getParentFile(), newName);
                    if (newNameFile.exists()) {
                        DialogFactory.errDialog("Folder %s already exists".formatted(newName));
                    }
                    else {
                        if (origFile.renameTo(newNameFile)) {
                            log.debug("Rename workspace from %s to %s".formatted(origFile.getPath(), newNameFile));
                            String activeWorkspacePath = FxPreferences.getInstance().getPreference(MINDOLPH_ACTIVE_WORKSPACE, String.class);
                            // reset active workspace if it has been renamed.
                            if (workspaceMeta.getBaseDirPath().equals(activeWorkspacePath)) {
                                FxPreferences.getInstance().savePreference(MINDOLPH_ACTIVE_WORKSPACE, newNameFile.getPath());
                            }
                            // reset scene and notify to update others.
                            WorkspaceMeta newWorkspaceMeta = WorkspaceManager.getIns().renameWorkspace(workspaceMeta, newNameFile);
                            SceneRestore.getInstance().saveScene(WorkspaceManager.getIns().getWorkspaceList());
                            EventBus.getIns().notifyWorkspaceRenamed(
                                    new WorkspaceRenameEvent(workspaceMeta, newWorkspaceMeta));
                        }
                    }
                }
            }
            else if (event.getSource() == miClose) {
                WorkspaceManager.getIns().getWorkspaceList().removeWorkspace(workspaceMeta);
                EventBus.getIns().notifyWorkspaceClosed(workspaceMeta);
            }
            else if (event.getSource() == miOpenInSys) {
                MindolphFileUtils.openFileInSystem(new File(workspaceMeta.getBaseDirPath()));
            }
        }
    }
}
