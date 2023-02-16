package com.mindolph.fx.view;

import com.igormaznitsa.mindmap.model.ExtraNote;
import com.igormaznitsa.mindmap.model.MindMap;
import com.mindolph.base.BaseView;
import com.mindolph.base.FontIconManager;
import com.mindolph.base.constant.IconKey;
import com.mindolph.base.control.TreeFinder;
import com.mindolph.base.control.TreeVisitor;
import com.mindolph.base.event.*;
import com.mindolph.core.ProjectService;
import com.mindolph.core.config.WorkspaceConfig;
import com.mindolph.core.constant.NodeType;
import com.mindolph.core.constant.SceneStatePrefs;
import com.mindolph.core.constant.Templates;
import com.mindolph.core.meta.WorkspaceList;
import com.mindolph.core.meta.WorkspaceMeta;
import com.mindolph.core.model.NodeData;
import com.mindolph.core.search.SearchParams;
import com.mindolph.core.search.SearchService;
import com.mindolph.core.template.Template;
import com.mindolph.core.util.FileNameUtils;
import com.mindolph.fx.IconBuilder;
import com.mindolph.fx.constant.IconName;
import com.mindolph.fx.dialog.FindInFilesDialog;
import com.mindolph.plantuml.PlantUmlTemplates;
import com.mindolph.fx.helper.SceneRestore;
import com.mindolph.fx.helper.TreeExpandRestoreListener;
import com.mindolph.mfx.dialog.DialogFactory;
import com.mindolph.mfx.dialog.impl.TextDialogBuilder;
import com.mindolph.mfx.preference.FxPreferences;
import com.mindolph.mfx.util.DesktopUtils;
import com.mindolph.mindmap.model.TopicNode;
import com.mindolph.mindmap.search.MindMapTextMatcher;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.filefilter.IOFileFilter;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swiftboot.collections.tree.Node;
import org.swiftboot.collections.tree.Tree;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static com.mindolph.core.constant.SceneStatePrefs.*;
import static com.mindolph.core.constant.SupportFileTypes.TYPE_MIND_MAP;

/**
 *
 * @author mindolph.com@gmail.com
 * @deprecated bug keep for reference
 */
public class ProjectView extends BaseView implements EventHandler<ActionEvent>,
        TreeExpandRestoreListener {

    private final Logger log = LoggerFactory.getLogger(ProjectView.class);

    private final FxPreferences fxPreferences = FxPreferences.getInstance();

    private final WorkspaceConfig projectConfig = new WorkspaceConfig();

    private final Comparator<TreeItem<NodeData>> SORTING_TREE_ITEMS = (o1, o2) -> {
        File f1 = o1.getValue().getFile();
        File f2 = o2.getValue().getFile();
        if (f1.isDirectory() && f2.isDirectory() || f1.isFile() && f2.isFile()) {
            return f1.getName().compareTo(f2.getName());
        }
        else if (f1.isDirectory()) {
            return -1;
        }
        else {
            return 1;
        }
    };

    @FXML
    private TreeView<NodeData> treeView;
    private final TreeItem<NodeData> rootItem; // root node is not visible

    private MenuItem miFolder;
    private MenuItem miMindMap;
    private MenuItem miTextFile;
    private Menu plantUmlMenu;
    private MenuItem miMarkdown;
    private MenuItem miRename;
    private MenuItem miClose;
    private MenuItem miReload;
    private MenuItem miClone;
    private MenuItem miDelete;
    private MenuItem miOpenInSystem;
    private MenuItem miFindFiles;
    private MenuItem miCollapseAll;

    // Event handlers that handle events from me.
    private OpenFileEventHandler openFileEventHandler;
    private SearchResultEventHandler searchEventHandler;
    private ExpandEventHandler expandEventHandler;
    private CollapseEventHandler collapseEventHandler;
    private WorkspaceClosedEventHandler workspaceClosedEventHandler;
    private FileRenamedEventHandler fileRenamedEventHandler;
    private FileChangedEventHandler fileChangedEventHandler;

    public ProjectView() {
        super("/view/workspace_view.fxml");
        log.info("Init project view");
        rootItem = new TreeItem<>(new NodeData("Hidden Root", null));
        rootItem.setExpanded(true);
        treeView.setRoot(rootItem);
        treeView.setShowRoot(false);

        treeView.setOnKeyPressed(event -> {
            log.debug("key pressed: " + event.getCode());
            if (event.getCode() == KeyCode.ENTER) {
                openSelectedFile();
                event.consume();
            }
        });

        treeView.setCellFactory(treeView -> {
            WorkspaceViewCell cell = new WorkspaceViewCell();
            // handle double-click to open file
            cell.setOnMouseClicked(mouseEvent -> {
                if (mouseEvent.getClickCount() == 2) {
                    this.openSelectedFile();
                }
            });
            cell.setOnMouseEntered(event -> {
                TreeItem<NodeData> treeItem = cell.getTreeItem();
                if (treeItem != null) {
                    log.trace("Install tooltip for " + treeItem);
                    NodeData data = treeItem.getValue();
                    cell.setTooltip(new Tooltip(data.getFile().getPath()));
                }
                else {
                    log.trace("Not tree item");
                    cell.setTooltip(null);
                }
            });
            cell.setDragFileEventHandler((files, target) -> {
                File toDir = target.getFile();
                if (!toDir.isDirectory() || !toDir.exists()) {
                    log.warn("Target dir doesn't exist: %s".formatted(toDir.getPath()));
                    return;
                }
                log.debug("Drop %d files to %s".formatted(files.size(), toDir.getPath()));
                for (NodeData fileData : files) {
                    if (fileData.isFile()) {
                        fileChangedEventHandler.onFileChanged(fileData);
                    }
                    File draggingFile = fileData.getFile();
                    // update the tree
                    TreeItem<NodeData> treeItemFile = findTreeItemByFile(draggingFile);
                    TreeItem<NodeData> treeItemFolder = findTreeItemByFile(toDir);
                    if (treeItemFile == null || treeItemFolder == null
                            || treeItemFile == treeItemFolder
                            || treeItemFile.getParent() == treeItemFolder) {
                        log.debug("Nothing to do");
                    }
                    else {
                        log.debug("Move tree item %s to %s".formatted(treeItemFile.getValue(), treeItemFolder.getValue()));
                        treeItemFile.getParent().getChildren().remove(treeItemFile);
                        treeItemFolder.getChildren().add(treeItemFile);

                        File newFilePath = new File(toDir, fileData.getName());
                        try {
                            if (fileData.isFile()) {
                                FileUtils.moveFile(draggingFile, newFilePath);
                                log.debug("File %s is moved".formatted(draggingFile.getName()));
                            }
                            else if (fileData.isFolder()) {
                                FileUtils.moveDirectory(draggingFile, newFilePath);
                                log.debug("Folder %s is moved".formatted(draggingFile.getName()));
                            }
                            fileData.setFile(newFilePath);
                            FXCollections.sort(treeItemFolder.getChildren(), SORTING_TREE_ITEMS);
                        } catch (Exception e) {
                            e.printStackTrace();
                            throw new RuntimeException("Move file failed");
                        }
                    }
                }
                treeView.refresh();
            });
            return cell;
        });
        treeView.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            log.debug("Selection changed: " + newValue);
            Optional<NodeData> selectedValue = ProjectView.this.getSelectedValue();
            EventBus.getIns().notifyMenuStateChange(EventBus.MenuTag.NEW_FILE,
                    selectedValue.isPresent()
                            && !selectedValue.get().isFile());
//            EventBus.getIns().notifyMenuStateChange(EventBus.MenuTag.OPEN_FILE, selectedValue.isPresent() && selectedValue.get() instanceof FileData);
        });
        EventBus.getIns().subscribeNewFileToWorkspace(file -> {
            TreeItem<NodeData> parentTreeItem = this.findTreeItemByFile(file.getParentFile());
            this.addFileAndSelect(parentTreeItem, new NodeData(file));
        });
        this.initTreeViewContextMenu();
        SearchService.getIns().registerMatcher(TYPE_MIND_MAP, new MindMapTextMatcher());
    }

    private void initTreeViewContextMenu() {
        // create context menu when right click on tree.
        ContextMenu contextMenu = new ContextMenu();
        contextMenu.getItems().add(new SeparatorMenuItem());// add to let it work.
        contextMenu.setOnShowing(event -> {
            this.createContextMenu(treeView.getSelectionModel().getSelectedItem(), contextMenu);
        });
        treeView.setContextMenu(contextMenu);
    }

    public void loadProjects(WorkspaceList projectList) {
        AtomicInteger count = new AtomicInteger();
        EventBus.getIns().subscribeWorkspaceLoaded(projectList.getSize(), projectDataTreeItem -> {
            rootItem.getChildren().add(projectDataTreeItem);
            log.debug("Add project node '%s' to root".formatted(projectDataTreeItem.getValue()));
            if (count.incrementAndGet() == projectList.getSize()) {
                EventBus.getIns().notifyWorkspacesRestored(); // notify that all projects loaded.
            }
            Platform.runLater(() -> treeView.requestFocus());
        });
        for (WorkspaceMeta workspaceMeta : projectList.getProjects()) {
            asyncCreateProjectSubTree(workspaceMeta);
        }
    }

    /**
     * Load a project sub-tree to the end of tree.
     *
     * @param workspaceMeta
     */
    public void loadProject(WorkspaceMeta workspaceMeta) {
        EventBus.getIns().subscribeWorkspaceLoaded(1, projectDataTreeItem -> {
            rootItem.getChildren().add(projectDataTreeItem);
            log.debug("Add project node '%s' to root".formatted(projectDataTreeItem.getValue()));
            Platform.runLater(() -> {
                treeView.requestFocus();
            });
        });
        asyncCreateProjectSubTree(workspaceMeta);
    }

    /**
     * Reload project sub-tree to specified position of tree.
     *
     * @param workspaceMeta
     * @param index       original index in siblings.
     */
    public void reloadProject(WorkspaceMeta workspaceMeta, int index) {
        EventBus.getIns().subscribeWorkspaceLoaded(1, projectDataTreeItem -> {
            rootItem.getChildren().add(index, projectDataTreeItem);
            List<String> treeExpandedList = fxPreferences.getPreference(SceneStatePrefs.MINDOLPH_TREE_EXPANDED_LIST, new ArrayList<>());
            onTreeExpandRestore(treeExpandedList);
            treeView.getSelectionModel().select(projectDataTreeItem);
            Platform.runLater(() -> treeView.requestFocus());
        });
        asyncCreateProjectSubTree(workspaceMeta);
    }

    /**
     * Reload folder and it's sub-tree to specified position of tree.
     *
     * @param folderData
     * @param index
     */
    public void reloadFolder(NodeData folderData, int index) {
        Tree tree = ProjectService.getInstance().loadDir(folderData.getFile(), projectConfig.makeFileFilter());
        TreeItem<NodeData> treeItem = new TreeItem<>(folderData);
        this.loadTreeNode(tree.getRootNode(), treeItem);
        TreeItem<NodeData> selectedTreeItem = getSelectedTreeItem();
        TreeItem<NodeData> parent = selectedTreeItem.getParent();
        treeItem.setExpanded(selectedTreeItem.isExpanded());
        parent.getChildren().remove(selectedTreeItem);
        parent.getChildren().add(index, treeItem);
        treeView.getSelectionModel().select(treeItem);
    }

    private void asyncCreateProjectSubTree(WorkspaceMeta workspaceMeta) {
        new Thread(() -> {
            log.debug("start a new thread to load project: %s".formatted(workspaceMeta.getBaseDirPath()));
            Tree tree = ProjectService.getInstance().loadProject(projectConfig, workspaceMeta);
            Node projectNode = tree.getRootNode();
            TreeItem<NodeData> projectItem = new TreeItem<>((NodeData) projectNode.getData());
            projectItem.expandedProperty().addListener((observable, oldValue, newValue) -> {
                if (!oldValue.equals(newValue)) {
                    onTreeItemExpandOrCollapsed(newValue, projectItem);
                }
            });
            Platform.runLater(() -> {
                this.loadTreeNode(projectNode, projectItem);
                log.debug("project loaded: " + workspaceMeta.getBaseDirPath());
                EventBus.getIns().notifyWorkspaceLoaded(projectItem);
            });
        }, "Project Load Thread").start();
    }

    /**
     * Load tree data recursively.
     *
     * @param parentNode
     * @param parent
     */
    private void loadTreeNode(Node parentNode, TreeItem<NodeData> parent) {
        for (Node childNode : parentNode.getChildren()) {
            NodeData nodeData = (NodeData) childNode.getData();
            if (nodeData.isFolder()) {
                TreeItem<NodeData> folderItem = this.addFolder(parent, nodeData);
                this.loadTreeNode(childNode, folderItem); // recursive
            }
            else if (nodeData.isFile()) {
                this.addFile(parent, nodeData);
            }
        }
    }

    public TreeItem<NodeData> addFolder(TreeItem<NodeData> parent, NodeData folderData) {
        TreeItem<NodeData> folderItem = new TreeItem<>(folderData);
        folderItem.expandedProperty().addListener((observable, oldValue, newValue) -> onTreeItemExpandOrCollapsed(newValue, folderItem));
        parent.getChildren().add(folderItem);
        FXCollections.sort(parent.getChildren(), SORTING_TREE_ITEMS);
        return folderItem;
    }

    public TreeItem<NodeData> addFileAndSelect(TreeItem<NodeData> parent, NodeData fileData) {
        TreeItem<NodeData> treeItem = this.addFile(parent, fileData);
        treeView.getSelectionModel().select(treeItem);
        return treeItem;
    }

    public TreeItem<NodeData> addFile(TreeItem<NodeData> parent, NodeData fileData) {
        TreeItem<NodeData> fileItem = new TreeItem<>(fileData);
        parent.getChildren().add(fileItem);
        FXCollections.sort(parent.getChildren(), SORTING_TREE_ITEMS);
        return fileItem;
    }

    private void openSelectedFile() {
        Optional<NodeData> selectedValue = getSelectedValue();
        if (selectedValue.isPresent()) {
            NodeData nodeObject = selectedValue.get();
            if (nodeObject.isFile()) {
                if (!nodeObject.getFile().exists()) {
                    DialogFactory.errDialog("File doesn't exist, it might be deleted or moved externally.");
                    removeTreeNode(nodeObject);
                    EventBus.getIns().notifyDeletedFile(nodeObject);
                    return;
                }
                log.info("Open file: " + nodeObject.getFile());
                openFileEventHandler.onOpenFile(nodeObject.getFile(), null, false);
            }
        }
    }

    private ContextMenu createContextMenu(TreeItem<NodeData> treeItem, ContextMenu contextMenu) {
        contextMenu.getItems().clear();
        if (treeItem != null) {
            NodeData nodeData = treeItem.getValue();
            boolean isProjectOrFolder = !nodeData.isFile();
            if (isProjectOrFolder) {
                Menu miNew = new Menu("New");
                miFolder = new MenuItem("Folder", new IconBuilder().name(IconName.FOLDER).build());
                miMindMap = new MenuItem("Mind Map(.mmd)", new IconBuilder().name(IconName.FILE_MMD).build());
                miMarkdown = new MenuItem("Markdown(.md)", new IconBuilder().name(IconName.FILE_MARKDOWN).build());
                plantUmlMenu = new Menu("PlantUML(.puml)", new IconBuilder().name(IconName.FILE_PUML).build());
                for (Template template : PlantUmlTemplates.getIns().getTemplates()) {
                    MenuItem mi = new MenuItem(template.getTitle());
                    mi.setUserData(template);
                    mi.setOnAction(this);
                    plantUmlMenu.getItems().add(mi);
                }
                miTextFile = new MenuItem("Text(.txt)", new IconBuilder().name(IconName.FILE_TXT).build());
                miFolder.setOnAction(this);
                miMindMap.setOnAction(this);
                miMarkdown.setOnAction(this);
                miTextFile.setOnAction(this);
                miNew.getItems().addAll(miFolder, miMindMap, miMarkdown, plantUmlMenu, miTextFile);
                contextMenu.getItems().add(miNew);
            }
            miRename = new MenuItem("Rename", FontIconManager.getIns().getIcon(IconKey.RENAME));
            miRename.setOnAction(this);
            contextMenu.getItems().addAll(miRename);
            if (isProjectOrFolder) {
                miReload = new MenuItem("Reload", FontIconManager.getIns().getIcon(IconKey.REFRESH));
                miReload.setOnAction(this);
                contextMenu.getItems().addAll(miReload);
            }
            if (nodeData.isWorkspace()) {
                miClose = new MenuItem("Close", FontIconManager.getIns().getIcon(IconKey.CLOSE));
                miClose.setOnAction(this);
                contextMenu.getItems().addAll(miClose);
            }
            else if (nodeData.isFile()) {
                miClone = new MenuItem("Clone", FontIconManager.getIns().getIcon(IconKey.CLONE));
                miClone.setOnAction(this);
                contextMenu.getItems().add(miClone);
            }
            miDelete = new MenuItem("Delete", FontIconManager.getIns().getIcon(IconKey.DELETE));
            miOpenInSystem = new MenuItem("Open in System", FontIconManager.getIns().getIcon(IconKey.SYSTEM));
            miCollapseAll = new MenuItem("Collapse All", FontIconManager.getIns().getIcon(IconKey.COLLAPSE_ALL));
            miDelete.setOnAction(this);
            miOpenInSystem.setOnAction(this);
            miCollapseAll.setOnAction(this);
            contextMenu.getItems().addAll(miDelete, miOpenInSystem, miCollapseAll);
            if (isProjectOrFolder) {
                miFindFiles = new MenuItem("Find in Files", FontIconManager.getIns().getIcon(IconKey.SEARCH));
                miFindFiles.setOnAction(this);
                contextMenu.getItems().addAll(new SeparatorMenuItem(), miFindFiles);
            }
            return contextMenu;
        }
        else {
            MenuItem miNewProject = new MenuItem("New Project");
            miNewProject.setOnAction(event -> EventBus.getIns().notify(NotificationType.NEW_WORKSPACE));
            contextMenu.getItems().add(miNewProject);
            return contextMenu;
        }
    }

    @Override
    public void onTreeExpandRestore(List<String> expandedNodes) {
        log.info("Restore tree expansion: ");
        this.expandTreeNodes(expandedNodes);
    }

    /**
     * Find and select a tree item by it's node data and expand it's path nodes.
     *
     * @param nodeData
     */
    public void selectByNodeData(NodeData nodeData) {
        if (nodeData != null) {
            log.debug("Select in tree: " + nodeData);
            TreeVisitor.dfsTraverse(rootItem, treeItem -> {
                NodeData value = treeItem.getValue();
                if (value.getFile().equals(nodeData.getFile())) {
                    log.debug("Found tree item to select");
                    treeItem.setExpanded(true);
                    treeView.getSelectionModel().select(treeItem);
                    return false;
                }
                return true;
            });
        }
    }

    public void scrollToSelected() {
        log.debug("Scroll to selected tree item");
        treeView.scrollTo(treeView.getSelectionModel().getSelectedIndex());
        treeView.refresh();
    }

    /**
     * Expand specified nodes in this project tree.
     *
     * @param expendedFileList
     */
    public void expandTreeNodes(List<String> expendedFileList) {
        TreeVisitor.dfsTraverse(rootItem, treeItem -> {
            if (treeItem.getValue().isFile()) {
                return null;
            }
            NodeData nodeData = treeItem.getValue();
            if (expendedFileList.contains(nodeData.getFile().getPath())) {
                treeItem.setExpanded(true);
            }
            return null;
        });
    }

    /**
     * Collapse node and all it's sub nodes.
     *
     * @param treeItem
     */
    public void collapseTreeNodes(TreeItem<NodeData> treeItem) {
        log.debug("Collapse all expanded nodes under " + treeItem);
        TreeVisitor.dfsTraverse(treeItem, item -> {
            if (item.isExpanded()) {
                log.debug("Expand node: " + item);
                item.setExpanded(false);
            }
            return null;
        });
        treeItem.setExpanded(false);
    }

    /**
     * Handle tree node expansion and collapse and call outer listener.
     *
     * @param expanded
     * @param treeItem
     */
    private void onTreeItemExpandOrCollapsed(Boolean expanded, TreeItem<NodeData> treeItem) {
        if (expanded) {
            expandEventHandler.onTreeItemExpanded(treeItem);
        }
        else {
            collapseEventHandler.onTreeItemCollapsed(treeItem);
        }
    }

    private boolean handlePlantumlCreation(MenuItem mi, File newFile) {
        Object userData = mi.getUserData();
        if (userData == null) return false;
        Template template = (Template) userData;
        try {
            String snippet = template.getContent().formatted(FilenameUtils.getBaseName(newFile.getName()));
            FileUtils.writeStringToFile(newFile, snippet, StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Handle events on node of the tree view.
     *
     * @param event
     */
    @Override
    public void handle(ActionEvent event) {
        MenuItem source = (MenuItem) event.getSource();
        TreeItem<NodeData> selectedTreeItem = getSelectedTreeItem();
        NodeData selectedData = selectedTreeItem.getValue();
        if (source == miFolder) {
            Dialog<String> dialog = new TextDialogBuilder()
                    .owner(DialogFactory.DEFAULT_WINDOW)
                    .title("New Folder Name")
                    .width(300)
                    .build();
            Optional<String> opt = dialog.showAndWait();
            if (opt.isPresent()) {
                String folderName = opt.get();
                if (selectedData.getFile().isDirectory()) {
                    File newDir = new File(selectedData.getFile(), folderName);
                    if (newDir.mkdir()) {
                        selectedTreeItem.setExpanded(true);
                        addFolder(selectedTreeItem, new NodeData(NodeType.FOLDER, newDir));
                        openFileEventHandler.onOpenFile(newDir, null, true);
                    }
                }
            }
        }
        else if (source == miMindMap || source == miTextFile || source == miMarkdown
                || (source.getParentMenu() != null && source.getParentMenu() == plantUmlMenu)) {
            log.debug("New %s File".formatted(source.getText()));
            log.debug("source: %s from %s".formatted(source.getText(), source.getParentMenu() == null ? "" : source.getParentMenu().getText()));
            Dialog<String> dialog = new TextDialogBuilder()
                    .owner(DialogFactory.DEFAULT_WINDOW)
                    .width(300)
                    .title("New %s Name".formatted(source.getText())).build();
            Optional<String> opt = dialog.showAndWait();
            if (opt.isPresent()) {
                String fileName = opt.get();

                if (selectedData.getFile().isDirectory()) {
                    File newFile = null;
                    if (source == miMindMap) {
                        newFile = createEmptyFile(fileName, selectedData, "mmd");
                        if (newFile != null) {
                            final MindMap<TopicNode> mindMap = new MindMap<>();
                            ExtraNote extraNote = new ExtraNote("Created on " + DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(LocalDateTime.now()));
                            TopicNode rootTopic = new TopicNode(mindMap, null, FilenameUtils.getBaseName(newFile.getPath()), extraNote);
                            mindMap.setRoot(rootTopic);
                            final String text;
                            try {
                                text = mindMap.write(new StringWriter()).toString();
                                FileUtils.writeStringToFile(newFile, text, StandardCharsets.UTF_8);
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                    else if (source == miTextFile) {
                        newFile = createEmptyFile(fileName, selectedData, "txt");
                    }
                    else if (source.getParentMenu() == plantUmlMenu) {
                        log.debug("Handle dynamic menu item: " + source.getText());
                        newFile = createEmptyFile(fileName, selectedData, "puml");
                        if (newFile != null) {
                            this.handlePlantumlCreation(source, newFile);
                        }
                    }
                    else if (source == miMarkdown) {
                        newFile = createEmptyFile(fileName, selectedData, "md");
                        if (newFile != null) {
                            String snippet = Templates.MARKDOWN_TEMPLATE.formatted(fileName);
                            try {
                                FileUtils.writeStringToFile(newFile, snippet, StandardCharsets.UTF_8);
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                    else {
                        log.warn("Not supported file type?");
                        return;
                    }
                    // add new file to tree view
                    if (newFile != null && newFile.exists()) {
                        selectedTreeItem.setExpanded(true);
                        addFile(selectedTreeItem, new NodeData(newFile));
                        openFileEventHandler.onOpenFile(newFile, null, false);
                    }
                }
            }
        }
        else if (source == miRename) {
            Optional<String> s = new TextDialogBuilder()
                    .owner(DialogFactory.DEFAULT_WINDOW)
                    .title("Rename %s".formatted(selectedData.getName()))
                    .content("Input a new name")
                    .text(FilenameUtils.getBaseName(selectedData.getName()))
                    .width(400)
                    .build().showAndWait();
            if (s.isPresent()) {
                String newName = s.get();
                File origFile = selectedData.getFile();
                if (selectedData.isFile()) {
                    newName = FileNameUtils.appendFileExtensionIfAbsent(newName, FilenameUtils.getExtension(origFile.getPath()));
                }
                File newNameFile = new File(origFile.getParentFile(), newName);
                if (newNameFile.exists()) {
                    DialogFactory.errDialog("file %s already exists".formatted(newName));
                }
                else {
                    if (origFile.renameTo(newNameFile)) {
                        log.debug("Rename file from %s to %s".formatted(origFile.getPath(), newNameFile));
                        if (selectedData.isFile()) {
                            selectedTreeItem.setValue(new NodeData(newNameFile));
                        }
                        else if (selectedData.isFolder()) {
                            NodeData newFolderData = new NodeData(NodeType.FOLDER, newNameFile);
                            int index = selectedTreeItem.getParent().getChildren().indexOf(selectedTreeItem);
                            this.reloadFolder(newFolderData, index);
                            selectedTreeItem.setValue(newFolderData);
                        }
                        treeView.refresh();
                        // remove old path from expanded list as well.
                        SceneRestore.getInstance().removeFromExpandedList(origFile.getPath());
                        fileRenamedEventHandler.onFileRenamed(selectedData, newNameFile);
                    }
                }
            }
        }
        else if (source == miClone) {
            if (selectedData != null) {
                if (selectedData.isFile()) {
                    File file = selectedData.getFile();
                    String cloneFileName = "%s_copy.%s".formatted(FilenameUtils.getBaseName(file.getName()), FilenameUtils.getExtension(file.getName()));
                    File cloneFile = new File(file.getParentFile(), cloneFileName);
                    if (cloneFile.exists()) {
                        DialogFactory.errDialog("File %s already exists".formatted(cloneFileName));
                        return;
                    }
                    try {
                        FileUtils.copyFile(file, cloneFile);
                    } catch (IOException e) {
                        e.printStackTrace();
                        DialogFactory.errDialog("Clone file failed: " + e.getLocalizedMessage());
                        return;
                    }
                    NodeData newFileData = new NodeData(cloneFile);
                    TreeItem<NodeData> folderItem = selectedTreeItem.getParent();
                    addFile(folderItem, newFileData);
                    treeView.refresh();
                }
            }
        }
        else if (source == miDelete) {
            if (selectedData != null) {
                try {
                    if (selectedData.getFile().isDirectory() && !FileUtils.isEmptyDirectory(selectedData.getFile())) {
                        DialogFactory.errDialog("You can not delete a folder with files.");
                        return;
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    return;
                }
                boolean needDelete = DialogFactory.yesNoConfirmDialog("Are you sure to delete %s".formatted(selectedData.getName()));
                if (needDelete) {
                    log.info("Delete file: %s".formatted(selectedData.getFile()));
                    try {
                        FileUtils.delete(selectedData.getFile());
                    } catch (IOException e) {
                        e.printStackTrace();
                        DialogFactory.errDialog("Delete file failed: " + e.getLocalizedMessage());
                        return;
                    }
                    // remove from recent list if exists
                    EventBus.getIns().notifyDeletedFile(selectedData);
                    removeTreeNode(selectedData);
                }
            }
        }
        else if (source == miClose) {
            if (selectedData != null) {
                if (selectedData.isWorkspace()) {
                    rootItem.getChildren().remove(selectedTreeItem);
                    workspaceClosedEventHandler.onWorkspaceClosed(selectedData);
                }
            }
        }
        else if (source == miReload) {
            if (selectedData != null) {
                if (selectedData.isWorkspace()) {
                    int originalIndex = rootItem.getChildren().indexOf(selectedTreeItem);
                    rootItem.getChildren().remove(selectedTreeItem);
                    WorkspaceMeta meta = new WorkspaceMeta(selectedData.getFile().getPath());
                    reloadProject(meta, originalIndex);
                }
                else if (selectedData.isFolder()) {
                    int index = selectedTreeItem.getParent().getChildren().indexOf(selectedTreeItem);
                    this.reloadFolder(selectedData, index);
                }
            }
        }
        else if (source == miFindFiles) {
            if (!selectedData.isFile()) {
                if (!selectedData.getFile().exists()) {
                    DialogFactory.errDialog("The project or folder you selected doesn't exist, probably be deleted externally.");
                }
                else {
                    SearchParams searchParams = new FindInFilesDialog(selectedData.getWorkspaceData().getFile(), selectedData.getFile()).showAndWait();
                    if (searchParams != null && StringUtils.isNotBlank(searchParams.getKeywords())) {
                        IOFileFilter searchFilter = projectConfig.makeFileFilter();
                        searchParams.setWorkspaceDir(selectedData.getWorkspaceData().getFile());
                        searchParams.setSearchInDir(selectedData.getFile());
                        searchParams.setSearchFilter(searchFilter);
                        fxPreferences.savePreference(MINDOLPH_FIND_FILES_KEYWORD, searchParams.getKeywords());
                        fxPreferences.savePreference(MINDOLPH_FIND_FILES_CASE_SENSITIVITY, searchParams.isCaseSensitive());
                        fxPreferences.savePreference(MINDOLPH_FIND_FILES_OPTIONS, searchParams.getFileTypeName());
                        searchEventHandler.onSearchStart(searchParams);
                    }
                }
            }
        }
        else if (source == miOpenInSystem) {
            if (selectedData != null) {
                log.info("Try to open file: " + selectedData.getFile());
                try {
                    DesktopUtils.openInSystem(selectedData.getFile(), false);
                } catch (Exception e) {
                    DialogFactory.warnDialog("Can't open this file in system");
                }
            }
        }
        else if (source == miCollapseAll) {
            collapseTreeNodes(treeView.getSelectionModel().getSelectedItem());
        }
    }

    /**
     * @param fileName
     * @param parentNodeData
     * @param extension
     * @return null if file can't be created.
     */
    private File createEmptyFile(String fileName, NodeData parentNodeData, String extension) {
        fileName = FileNameUtils.appendFileExtensionIfAbsent(fileName, extension);
        File newFile = new File(parentNodeData.getFile(), fileName);
        if (newFile.exists()) {
            DialogFactory.errDialog("File %s already existed!".formatted(fileName));
            return null;
        }
        try {
            FileUtils.touch(newFile);
        } catch (IOException e) {
            return null;
        }
        return newFile;
    }


    public TreeItem<NodeData> getSelectedTreeItem() {
        return treeView.getSelectionModel().getSelectedItem();
    }

    public Optional<NodeData> getSelectedValue() {
        TreeItem<NodeData> selectedItem = getSelectedTreeItem();
        if (selectedItem == null) {
            return Optional.empty();
        }
        else {
            return Optional.ofNullable(selectedItem.getValue());
        }
    }

    /**
     * Get the project node for selected node.
     *
     * @return
     * @deprecated not using but keep it
     */
    public TreeItem<NodeData> getSelectedProject() {
        TreeItem<NodeData> selectedItem = treeView.getSelectionModel().getSelectedItem();
        return findParentNodeWithDataType(selectedItem, NodeType.WORKSPACE);
    }

    /**
     * Find first parent TreeItem matches class type of data object for {@code treeItem} recursively.
     *
     * @param treeItem
     * @param nodeType data type to match
     * @return
     * @deprecated not using but keep it
     */
    private TreeItem<NodeData> findParentNodeWithDataType(TreeItem<NodeData> treeItem, NodeType nodeType) {
        if (treeItem.getValue().getNodeType() == nodeType) {
            return treeItem;
        }
        if (treeItem.getParent().getValue().getNodeType() == nodeType) {
            return treeItem.getParent();
        }
        else {
            return findParentNodeWithDataType(treeItem.getParent(), nodeType);
        }
    }

    /**
     * Find a tree item by searching exactly the path of file,
     * because it's faster than traversing the whole tree.
     *
     * @param file
     * @return
     */
    public TreeItem<NodeData> findTreeItemByFile(File file) {
        return TreeFinder.findTreeItemPathMatch(rootItem, treeItem -> {
            File nodeFile = treeItem.getValue().getFile();
            return rootItem == treeItem ||
                    file.getPath().startsWith(nodeFile.getPath());
        }, treeItem -> {
            if (treeItem == rootItem) {
                return false;
            }
            File nodeFile = treeItem.getValue().getFile();
            return nodeFile.equals(file);
        });
    }

    public void removeTreeNode(NodeData nodeData) {
        TreeItem<NodeData> selectedTreeItem = getSelectedTreeItem();
        if (selectedTreeItem.getValue() == nodeData) {
            selectedTreeItem.getParent().getChildren().remove(selectedTreeItem);
            treeView.refresh();
        }
    }

    @Override
    public void requestFocus() {
        treeView.requestFocus();
    }

    public void setExpandEventHandler(ExpandEventHandler expandEventHandler) {
        this.expandEventHandler = expandEventHandler;
    }

    public void setCollapseEventHandler(CollapseEventHandler collapseEventHandler) {
        this.collapseEventHandler = collapseEventHandler;
    }

    public void setOpenFileEventHandler(OpenFileEventHandler eventHandler) {
        this.openFileEventHandler = eventHandler;
    }

    public void setSearchEventHandler(SearchResultEventHandler searchEventHandler) {
        this.searchEventHandler = searchEventHandler;
    }

    public void setProjectClosedEventHandler(WorkspaceClosedEventHandler workspaceClosedEventHandler) {
        this.workspaceClosedEventHandler = workspaceClosedEventHandler;
    }

    public void setFileRenamedEventHandler(FileRenamedEventHandler fileRenamedEventHandler) {
        this.fileRenamedEventHandler = fileRenamedEventHandler;
    }

    public void setFileChangedEventHandler(FileChangedEventHandler fileChangedEventHandler) {
        this.fileChangedEventHandler = fileChangedEventHandler;
    }
}
