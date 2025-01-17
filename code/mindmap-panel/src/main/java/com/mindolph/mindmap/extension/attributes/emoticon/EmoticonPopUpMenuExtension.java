package com.mindolph.mindmap.extension.attributes.emoticon;

import com.mindolph.base.FontIconManager;
import com.mindolph.base.constant.IconKey;
import com.mindolph.mindmap.I18n;
import com.mindolph.mindmap.dialog.IconDialog;
import com.mindolph.mindmap.extension.ContextMenuSection;
import com.mindolph.mindmap.extension.api.BasePopupMenuItemExtension;
import com.mindolph.mindmap.extension.api.ExtensionContext;
import com.mindolph.mindmap.model.TopicNode;
import javafx.scene.control.MenuItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

public class EmoticonPopUpMenuExtension extends BasePopupMenuItemExtension {

    private final Logger log = LoggerFactory.getLogger(EmoticonPopUpMenuExtension.class);

    @Override
    public MenuItem makeMenuItem(ExtensionContext context, TopicNode activeTopic) {
        MenuItem menuItem = new MenuItem(I18n.getIns().getString("Emoticons.MenuTitle"), FontIconManager.getIns().getIcon(IconKey.EMOTICONS));
//        result.setToolTipText(BUNDLE.getString("Emoticons.MenuTooltip"));
        menuItem.setOnAction(e -> {
            IconDialog iconDialog = new IconDialog(getAttribute(activeTopic));
            String selectedName = iconDialog.showAndWait();
            if (selectedName != null) {
                log.debug("Selected icon: " + selectedName);
                boolean changed;
                if ("empty".equals(selectedName)) {
                    changed = setAttribute(null, context, activeTopic);
                }
                else {
                    changed = setAttribute(selectedName, context, activeTopic);
                }
                if (changed) {
                    context.doNotifyModelChanged(true);
                }
            }
            else {
                log.warn("No icon name selected");
            }
        });
        return menuItem;
    }

    private String getAttribute(TopicNode activeTopic) {
        String attribute = activeTopic.getAttribute(EmoticonVisualAttributeExtension.ATTR_KEY);
        if (attribute == null) {
            return "empty";
        }
        return attribute;
    }

    private boolean setAttribute(String iconName, ExtensionContext context, TopicNode activeTopic) {
        boolean changed = false;
        if (activeTopic != null) {
            String old = activeTopic.getAttribute(EmoticonVisualAttributeExtension.ATTR_KEY);
            if (!Objects.equals(old, iconName)) {
                activeTopic.setAttribute(EmoticonVisualAttributeExtension.ATTR_KEY, iconName);
                changed = true;
            }
        }
        for (TopicNode t : context.getSelectedTopics()) {
            String old = t.getAttribute(EmoticonVisualAttributeExtension.ATTR_KEY);
            if (!Objects.equals(old, iconName)) {
                t.setAttribute(EmoticonVisualAttributeExtension.ATTR_KEY, iconName);
                changed = true;
            }
        }
        return changed;
    }

    @Override
    public ContextMenuSection getSection() {
        return ContextMenuSection.EXTRAS;
    }

    @Override
    public boolean needsTopicUnderMouse() {
        return true;
    }

    @Override
    public boolean needsSelectedTopics() {
        return false;
    }

    @Override
    public int getOrder() {
        return EXT_EXTENSION_ORDER_BASE - 1;
    }

}
