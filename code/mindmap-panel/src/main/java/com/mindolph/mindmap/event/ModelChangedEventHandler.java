package com.mindolph.mindmap.event;

/**
 * @author mindolph.com@gmail.com
 */
@FunctionalInterface
public interface ModelChangedEventHandler {

    void onModelChanged();
}
