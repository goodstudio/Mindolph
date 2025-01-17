package com.mindolph.fx.view;

import com.mindolph.base.FontIconManager;
import com.mindolph.base.constant.IconKey;
import com.mindolph.base.event.EventBus;
import com.mindolph.base.event.OpenFileEvent;
import com.mindolph.core.model.NodeData;
import com.mindolph.core.search.SearchParams;
import com.mindolph.core.search.SearchService;
import com.mindolph.fx.IconBuilder;
import com.mindolph.fx.constant.IconName;
import com.mindolph.fx.control.FileFilterButtonGroup;
import com.mindolph.fx.util.DisplayUtils;
import com.mindolph.mfx.preference.FxPreferences;
import com.mindolph.mfx.util.AsyncUtils;
import com.mindolph.mfx.util.FxmlUtils;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.AnchorPane;
import javafx.util.Callback;
import org.apache.commons.io.filefilter.IOFileFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;

import static com.mindolph.core.constant.SceneStatePrefs.MINDOLPH_FIND_FILES_KEYWORD;

/**
 * Panel for "Find in Files".
 *
 * @author mindolph.com@gmail.com
 */
public class SearchResultPane extends AnchorPane {

    private final Logger log = LoggerFactory.getLogger(SearchResultPane.class);

    @FXML
    private Label label;

    @FXML
    private TextField tfKeywords;

    @FXML
    private ToggleButton tbCase;

    @FXML
    private Button btnSearch;

    @FXML
    private TreeView<File> treeView;
    @FXML
    private FileFilterButtonGroup fileFilterButtonGroup;
    @FXML
    private ProgressIndicator progressIndicator;

    private final TreeItem<File> rootItem;

    private SearchParams searchParams;

    private List<File> foundFiles;

    public SearchResultPane() {
        FxmlUtils.loadUri("/view/search_result_pane.fxml", this);
        rootItem = new TreeItem<>(null);
        rootItem.setExpanded(true);
        treeView.setRoot(rootItem);
        treeView.setShowRoot(false);
        treeView.setOnMouseClicked(mouseEvent -> {
            if (mouseEvent.getClickCount() == 2) {
                TreeItem<File> selectedItem = treeView.getSelectionModel().getSelectedItem();
                File file = selectedItem.getValue();
                EventBus.getIns().notifyOpenFile(new OpenFileEvent(file, true, searchParams));
            }
        });
        treeView.setOnKeyPressed(keyEvent -> {
            if (keyEvent.getCode() == KeyCode.ENTER) {
                TreeItem<File> selectedItem = treeView.getSelectionModel().getSelectedItem();
                File file = selectedItem.getValue();
                EventBus.getIns().notifyOpenFile(new OpenFileEvent(file, true, searchParams));
            }
        });
        treeView.setCellFactory(new Callback<>() {
            @Override
            public TreeCell<File> call(TreeView<File> param) {
                return new TreeCell<>() {
                    @Override
                    protected void updateItem(File item, boolean empty) {
                        super.updateItem(item, empty);
                        if (item != null) {
                            setText(DisplayUtils.displayFile(searchParams.getWorkspaceDir(), item));
                            if (item.isFile()) {
                                setGraphic(new IconBuilder().fileData(new NodeData(item)).build());
                            }
                            else if (item.isDirectory()) {
                                setGraphic(new IconBuilder().name(IconName.FOLDER).build());
                            }
                        }
                        else {
                            setText(null);
                            setGraphic(null);
                        }
                    }
                };
            }
        });
        tfKeywords.textProperty().addListener((observableValue, s, t1) -> searchParams.setKeywords(t1));
        tfKeywords.setOnKeyPressed(keyEvent -> {
            if (keyEvent.getCode() == KeyCode.ENTER) {
                this.research();
            }
        });
        tbCase.setGraphic(FontIconManager.getIns().getIcon(IconKey.CASE_SENSITIVITY));
        btnSearch.setOnAction(event -> {
            this.research();
        });
        Platform.runLater(() -> {
            tfKeywords.requestFocus();
        });
    }

    public void init(SearchParams searchParams) {
        this.searchParams = searchParams;
        tfKeywords.setText(searchParams.getKeywords());
        tbCase.setSelected(searchParams.isCaseSensitive());
        fileFilterButtonGroup.setSelectedFileType(searchParams.getFileTypeName());
        // start searching
        this.research();
        // add listeners after search completed.
        tbCase.selectedProperty().addListener((observableValue, aBoolean, t1) -> {
            log.debug("Case sensitive option changed to: %s".formatted(t1));
            searchParams.setCaseSensitive(t1);
            this.research();
        });
        fileFilterButtonGroup.selectedFileTypeProperty().addListener((observableValue, s, fileTypeName) -> {
            log.debug("File type changed to: %s".formatted(fileTypeName));
            // may be filtering searched files instead of research again for file type switching TODO
            searchParams.setFileTypeName(fileFilterButtonGroup.getSelectedFileType());
            research();
        });
    }

    private void research() {
        log.debug("research()");
        progressIndicator.setVisible(true);
        String keyword = searchParams.getKeywords();
        IOFileFilter newFileFilter = searchParams.getSearchFilter();
        AsyncUtils.fxAsync(() -> {
            foundFiles = SearchService.getIns().searchInFilesIn(searchParams.getSearchInDir(), newFileFilter, searchParams);
        }, () -> {
            progressIndicator.setVisible(false);
            FxPreferences.getInstance().savePreference(MINDOLPH_FIND_FILES_KEYWORD, keyword);
            this.updateSearchResult();
        });
    }

    private void updateSearchResult() {
        label.setText("Found following files %d in folder %s".formatted(foundFiles.size(), searchParams.getSearchInDir()));
        rootItem.getChildren().clear();
        for (File file : foundFiles) {
            TreeItem<File> item = new TreeItem<>(file);
            item.setValue(file);
            rootItem.getChildren().add(item);
        }
    }

}
