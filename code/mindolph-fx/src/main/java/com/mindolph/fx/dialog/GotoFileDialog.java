package com.mindolph.fx.dialog;

import com.mindolph.base.FontIconManager;
import com.mindolph.base.constant.IconKey;
import com.mindolph.base.event.EventBus;
import com.mindolph.base.event.OpenFileEvent;
import com.mindolph.core.WorkspaceManager;
import com.mindolph.core.collection.Comparators;
import com.mindolph.core.meta.WorkspaceList;
import com.mindolph.core.meta.WorkspaceMeta;
import com.mindolph.core.model.FileMeta;
import com.mindolph.core.model.NodeData;
import com.mindolph.fx.IconBuilder;
import com.mindolph.fx.constant.IconName;
import com.mindolph.fx.control.FileFilterButtonGroup;
import com.mindolph.fx.util.DisplayUtils;
import com.mindolph.mfx.dialog.BaseDialogController;
import com.mindolph.mfx.dialog.CustomDialogBuilder;
import com.mindolph.mfx.dialog.DialogFactory;
import com.mindolph.mfx.preference.FxPreferences;
import com.mindolph.mfx.util.AsyncUtils;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.util.Callback;
import org.apache.commons.lang3.StringUtils;
import org.reactfx.Change;
import org.reactfx.EventStream;
import org.reactfx.EventStreams;
import org.reactfx.util.Tuple2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static com.mindolph.core.constant.SceneStatePrefs.MINDOLPH_NAVIGATE_KEYWORD;
import static com.mindolph.core.constant.SceneStatePrefs.MINDOLPH_NAVIGATE_OPTIONS;
import static com.mindolph.fx.control.FileFilterButtonGroup.FILE_OPTION_ALL;

/**
 * @author mindolph.com@gmail.com
 */
public class GotoFileDialog extends BaseDialogController<Void> {

    private final Logger log = LoggerFactory.getLogger(GotoFileDialog.class);

    @FXML
    private TextField textField;
    @FXML
    private ListView<FileMeta> listView;
    @FXML
    private FileFilterButtonGroup fileFilterButtonGroup;
    @FXML
    private ToggleButton tbSort;

    private WorkspaceList workspaceList;

    private final FxPreferences fxPreferences = FxPreferences.getInstance();

    public GotoFileDialog() {
        dialog = new CustomDialogBuilder<Void>()
                .owner(DialogFactory.DEFAULT_WINDOW)
                .title("Go to file")
                .fxmlUri("dialog/goto_file_dialog.fxml")
                .buttons(ButtonType.CLOSE)
                .icon(ButtonType.CLOSE, FontIconManager.getIns().getIcon(IconKey.CLOSE))
                .defaultValue(null)
                .resizable(true)
                .controller(this)
                .build();

        dialog.setOnShown(event -> Platform.runLater(() -> textField.requestFocus()));

        // merge events to trigger the search
        EventStream<Change<String>> textChanged = EventStreams.changesOf(textField.textProperty());
        EventStream<Change<String>> optionChanged = EventStreams.changesOf(fileFilterButtonGroup.selectedFileTypeProperty());
        EventStream<Tuple2<Change<String>, Change<String>>> combine = EventStreams.combine(textChanged, optionChanged);
        combine.pausable().reduceSuccessions((tuple2, tuple22) -> tuple22, Duration.ofMillis(400))
                .subscribe(tuple2 -> {
                    searchFiles(StringUtils.trim(textField.getText()), fileFilterButtonGroup.getSelectedFileType());
                });

        String lastKeyword = fxPreferences.getPreference(MINDOLPH_NAVIGATE_KEYWORD, String.class);
        textField.setText(lastKeyword);

        String fileTypeOption = fxPreferences.getPreference(MINDOLPH_NAVIGATE_OPTIONS, String.class, FILE_OPTION_ALL);
        fileFilterButtonGroup.setSelectedFileType(fileTypeOption);

        tbSort.setGraphic(FontIconManager.getIns().getIcon(IconKey.SORT));
        tbSort.setOnAction(event -> {
            searchFiles(StringUtils.trim(textField.getText()), fileFilterButtonGroup.getSelectedFileType());
        });

        textField.setOnKeyPressed(keyEvent -> {
            if (!listView.isFocused()) {
                if (keyEvent.getCode() == KeyCode.DOWN) {
                    listView.requestFocus();
                    listView.getSelectionModel().selectFirst();
                }
                else if (keyEvent.getCode() == KeyCode.UP) {
                    listView.requestFocus();
                    listView.getSelectionModel().selectLast();
                }
                listView.scrollTo(listView.getSelectionModel().getSelectedIndex());
            }
        });

        listView.setCellFactory(new Callback<>() {
            @Override
            public ListCell<FileMeta> call(ListView param) {
                ListCell<FileMeta> listCell = new ListCell<>() {
                    @Override
                    protected void updateItem(FileMeta item, boolean empty) {
                        super.updateItem(item, empty);
                        if (item != null) {
                            setText(DisplayUtils.displayFile(item.getWorkspaceDir(), item.getDirOrFile()));
                            if (item.getDirOrFile().isFile()) {
                                setGraphic(new IconBuilder().fileData(new NodeData(item.getDirOrFile())).size(32).build());
                            }
                            else if (item.getDirOrFile().isDirectory()) {
                                setGraphic(new IconBuilder().name(IconName.FOLDER).size(32).build());
                            }
                        }
                        else {
                            setText(null);
                            setGraphic(null);
                        }
                    }
                };
                listCell.setOnMouseClicked(event -> {
                    if (event.getClickCount() > 1) {
                        EventBus.getIns().notifyOpenFile(new OpenFileEvent(listCell.getItem().getDirOrFile(), true));
                        dialog.close();
                    }
                });
                return listCell;
            }
        });
        listView.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                event.consume();
                FileMeta selectedItem = listView.getSelectionModel().getSelectedItem();
                if (selectedItem != null) {
                    EventBus.getIns().notifyOpenFile(new OpenFileEvent(selectedItem.getDirOrFile(), true));
                    dialog.close();
                }
            }
            else if (event.getCode() == KeyCode.ESCAPE) {
                event.consume();
                dialog.close();
            }
        });
    }

    private void searchFiles(String keyword, String fileTypeName) {
        log.debug("Search files by keyword %s and extension %s".formatted(keyword, fileTypeName));
        if (StringUtils.isAlphanumeric(keyword) && keyword.length() == 1) {
            return;// 1 letter no searching
        }
        boolean listedInAlphabet = tbSort.isSelected();
        listView.getItems().clear();
        if (StringUtils.isNotBlank(keyword)) {
            AsyncUtils.fxAsync(() -> {
                List<File> allMatchFiles = new ArrayList<>();
                for (WorkspaceMeta workspace : WorkspaceManager.getIns().getWorkspaceList().getProjects()) {
                    File workspaceDir = new File(workspace.getBaseDirPath());
                    List<File> foundFiles = WorkspaceManager.getIns().findDirsAndFilesByKeyword(workspaceDir, keyword,
                            FILE_OPTION_ALL.equals(fileTypeName) ? null : fileTypeName);
                    log.debug("found %d from %s".formatted(foundFiles.size(), workspace.getBaseDirPath()));
                    allMatchFiles.addAll(foundFiles);
                }
                return allMatchFiles;
            }, allMatchFiles -> {
                if (!allMatchFiles.isEmpty()) {
                    if (listedInAlphabet) {
                        allMatchFiles.sort(Comparators.NAVIGATION_FILENAME_COMPARATOR);
                    }
                    else {
                        allMatchFiles.sort(Comparators.NAVIGATION_DEFAULT_COMPARATOR);
                    }
                    for (File foundFile : allMatchFiles) {
                        log.debug("Matched file: " + foundFile);
                        WorkspaceMeta workspaceMeta = WorkspaceManager.getIns().getWorkspaceList().matchByFilePath(foundFile.getPath());
                        listView.getItems().add(new FileMeta(new File(workspaceMeta.getBaseDirPath()), foundFile));
                    }
                    fxPreferences.savePreference(MINDOLPH_NAVIGATE_KEYWORD, keyword);
                    fxPreferences.savePreference(MINDOLPH_NAVIGATE_OPTIONS, fileTypeName);
                }
            });
        }
        listView.refresh();
    }

}
