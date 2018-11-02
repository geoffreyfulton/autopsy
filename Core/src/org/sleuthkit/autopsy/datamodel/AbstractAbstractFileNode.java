/*
 * Autopsy Forensic Browser
 *
 * Copyright 2011-2018 Basis Technology Corp.
 * Contact: carrier <at> sleuthkit <dot> org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.sleuthkit.autopsy.datamodel;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.openide.nodes.Children;
import org.openide.nodes.Sheet;
import org.openide.util.NbBundle;
import org.openide.util.WeakListeners;
import org.sleuthkit.autopsy.casemodule.Case;
import org.sleuthkit.autopsy.casemodule.NoCurrentCaseException;
import org.sleuthkit.autopsy.casemodule.events.CommentChangedEvent;
import org.sleuthkit.autopsy.casemodule.events.ContentTagAddedEvent;
import org.sleuthkit.autopsy.casemodule.events.ContentTagDeletedEvent;
import org.sleuthkit.autopsy.centralrepository.datamodel.CorrelationAttributeInstance;
import org.sleuthkit.autopsy.core.UserPreferences;
import org.sleuthkit.autopsy.corecomponents.DataResultViewerTable.Score;
import org.sleuthkit.autopsy.coreutils.Logger;
import static org.sleuthkit.autopsy.datamodel.Bundle.*;
import static org.sleuthkit.autopsy.datamodel.AbstractAbstractFileNode.AbstractFilePropertyType.*;
import org.sleuthkit.autopsy.datamodel.SCOAndTranslationTask.SCOResults;
import org.sleuthkit.autopsy.ingest.IngestManager;
import org.sleuthkit.autopsy.ingest.ModuleContentEvent;
import org.sleuthkit.datamodel.AbstractFile;
import org.sleuthkit.datamodel.Content;
import org.sleuthkit.datamodel.ContentTag;
import org.sleuthkit.datamodel.TskCoreException;

/**
 * An abstract node that encapsulates AbstractFile data
 *
 * @param <T> type of the AbstractFile to encapsulate
 */
public abstract class AbstractAbstractFileNode<T extends AbstractFile> extends AbstractContentNode<T> {

    private static final Logger logger = Logger.getLogger(AbstractAbstractFileNode.class.getName());
    @NbBundle.Messages("AbstractAbstractFileNode.addFileProperty.desc=no description")
    private static final String NO_DESCR = AbstractAbstractFileNode_addFileProperty_desc();

    private static final Set<Case.Events> CASE_EVENTS_OF_INTEREST = EnumSet.of(Case.Events.CURRENT_CASE,
            Case.Events.CONTENT_TAG_ADDED, Case.Events.CONTENT_TAG_DELETED, Case.Events.CR_COMMENT_CHANGED);

    private static final ExecutorService pool;
    private static final Integer MAX_POOL_SIZE = 10;

    /**
     * @param abstractFile file to wrap
     */
    AbstractAbstractFileNode(T abstractFile) {
        super(abstractFile);
        String ext = abstractFile.getNameExtension();
        if (StringUtils.isNotBlank(ext)) {
            ext = "." + ext;
            // If this is an archive file we will listen for ingest events
            // that will notify us when new content has been identified.
            if (FileTypeExtensions.getArchiveExtensions().contains(ext)) {
                IngestManager.getInstance().addIngestModuleEventListener(weakPcl);
            }
        }
        // Listen for case events so that we can detect when the case is closed
        // or when tags are added.
        Case.addEventTypeSubscriber(CASE_EVENTS_OF_INTEREST, weakPcl);
    }

    static {
        //Initialize this pool only once! This will be used by every instance of AAFN
        //to do their heavy duty SCO column and translation updates.
        pool = Executors.newFixedThreadPool(MAX_POOL_SIZE);
    }

    /**
     * The finalizer removes event listeners as the BlackboardArtifactNode is
     * being garbage collected. Yes, we know that finalizers are considered to
     * be "bad" but since the alternative also relies on garbage collection
     * being run and we know that finalize will be called when the object is
     * being GC'd it seems like this is a reasonable solution.
     *
     * @throws Throwable
     */
    @Override
    protected void finalize() throws Throwable {
        super.finalize();
        removeListeners();
    }

    private void removeListeners() {
        IngestManager.getInstance().removeIngestModuleEventListener(weakPcl);
        Case.removeEventTypeSubscriber(CASE_EVENTS_OF_INTEREST, weakPcl);
    }

    /**
     * Event signals to indicate the background tasks have completed processing.
     * Currently, we have two property tasks in the background:
     *
     * 1) Retreiving the translation of the file name 2) Getting the SCO column
     * properties from the databases
     */
    enum NodeSpecificEvents {
        TRANSLATION_AVAILABLE,
        DABABASE_CONTENT_AVAILABLE;
    }

    private final PropertyChangeListener pcl = (PropertyChangeEvent evt) -> {
        String eventType = evt.getPropertyName();

        // Is this a content changed event?
        if (eventType.equals(IngestManager.IngestModuleEvent.CONTENT_CHANGED.toString())) {
            if ((evt.getOldValue() instanceof ModuleContentEvent) == false) {
                return;
            }
            ModuleContentEvent moduleContentEvent = (ModuleContentEvent) evt.getOldValue();
            if ((moduleContentEvent.getSource() instanceof Content) == false) {
                return;
            }
            Content newContent = (Content) moduleContentEvent.getSource();

            // Does the event indicate that content has been added to *this* file?
            if (getContent().getId() == newContent.getId()) {
                // If so, refresh our children.
                try {
                    Children parentsChildren = getParentNode().getChildren();
                    // We only want to refresh our parents children if we are in the
                    // data sources branch of the tree. The parent nodes in other
                    // branches of the tree (e.g. File Types and Deleted Files) do
                    // not need to be refreshed.
                    if (parentsChildren instanceof ContentChildren) {
                        ((ContentChildren) parentsChildren).refreshChildren();
                        parentsChildren.getNodesCount();
                    }
                } catch (NullPointerException ex) {
                    // Skip
                }
            }
        } else if (eventType.equals(Case.Events.CURRENT_CASE.toString())) {
            if (evt.getNewValue() == null) {
                // case was closed. Remove listeners so that we don't get called with a stale case handle
                removeListeners();
            }
        } else if (eventType.equals(Case.Events.CONTENT_TAG_ADDED.toString())) {
            ContentTagAddedEvent event = (ContentTagAddedEvent) evt;
            if (event.getAddedTag().getContent().equals(content)) {
                //No need to do any asynchrony around these events, they are so infrequent
                //and user driven that we can just keep a simple blocking approach, where we
                //go out to the database ourselves!
                List<ContentTag> tags = FileNodeUtil.getContentTagsFromDatabase(content);
                Pair<Score, String> scorePropertyAndDescription
                        = FileNodeUtil.getScorePropertyAndDescription(content, tags);
                CorrelationAttributeInstance attribute = 
                    FileNodeUtil.getCorrelationAttributeInstance(content);
                updateProperty(
                        new ToggleableNodeProperty(
                                SCORE.toString(),
                                scorePropertyAndDescription.getRight(),
                                scorePropertyAndDescription.getLeft()),
                        new ToggleableNodeProperty(
                                COMMENT.toString(),
                                NO_DESCR,
                                FileNodeUtil.getCommentProperty(tags, attribute)) {
                });
            }
        } else if (eventType.equals(Case.Events.CONTENT_TAG_DELETED.toString())) {
            ContentTagDeletedEvent event = (ContentTagDeletedEvent) evt;
            if (event.getDeletedTagInfo().getContentID() == content.getId()) {
                //No need to do any asynchrony around these events, they are so infrequent
                //and user driven that we can just keep a simple blocking approach, where we
                //go out to the database ourselves!
                List<ContentTag> tags = FileNodeUtil.getContentTagsFromDatabase(content);
                Pair<Score, String> scorePropertyAndDescription
                        = FileNodeUtil.getScorePropertyAndDescription(content, tags);
                CorrelationAttributeInstance attribute = 
                    FileNodeUtil.getCorrelationAttributeInstance(content);
                updateProperty(
                        new ToggleableNodeProperty(
                                SCORE.toString(),
                                scorePropertyAndDescription.getRight(),
                                scorePropertyAndDescription.getLeft()),
                        new ToggleableNodeProperty(
                                COMMENT.toString(),
                                NO_DESCR,
                                FileNodeUtil.getCommentProperty(tags, attribute))
                );
            }
        } else if (eventType.equals(Case.Events.CR_COMMENT_CHANGED.toString())) {
            CommentChangedEvent event = (CommentChangedEvent) evt;
            if (event.getContentID() == content.getId()) {
                //No need to do any asynchrony around these events, they are so infrequent
                //and user driven that we can just keep a simple blocking approach, where we
                //go out to the database ourselves!
                List<ContentTag> tags = FileNodeUtil.getContentTagsFromDatabase(content);
                CorrelationAttributeInstance attribute
                        = FileNodeUtil.getCorrelationAttributeInstance(content);
                updateProperty(
                        new ToggleableNodeProperty(
                                COMMENT.toString(),
                                NO_DESCR,
                                FileNodeUtil.getCommentProperty(tags, attribute)));
            }
        } else if (eventType.equals(NodeSpecificEvents.TRANSLATION_AVAILABLE.toString())) {
            updateProperty(
                    new ToggleableNodeProperty(
                            TRANSLATION.toString(),
                            NO_DESCR,
                            evt.getNewValue()) {
                @Override
                public boolean isEnabled() {
                    return UserPreferences.displayTranslationFileNames();
                }
            });
        } else if (eventType.equals(NodeSpecificEvents.DABABASE_CONTENT_AVAILABLE.toString())) {
            SCOResults results = (SCOResults) evt.getNewValue();
            updateProperty(
                    new ToggleableNodeProperty(
                            SCORE.toString(),
                            results.getScoreDescription(),
                            results.getScore()),
                    new ToggleableNodeProperty(
                            COMMENT.toString(),
                            NO_DESCR,
                            results.getComment()),
                    new ToggleableNodeProperty(
                            OCCURRENCES.toString(),
                            results.getCountDescription(),
                            results.getCount()) {
                        @Override
                        public boolean isEnabled() {
                            return !UserPreferences.hideCentralRepoCommentsAndOccurrences();
                        }
            });
        }
    };
    /**
     * We pass a weak reference wrapper around the listener to the event
     * publisher. This allows Netbeans to delete the node when the user
     * navigates to another part of the tree (previously, nodes were not being
     * deleted because the event publisher was holding onto a strong reference
     * to the listener. We need to hold onto the weak reference here to support
     * unregistering of the listener in removeListeners() below.
     */
    private final PropertyChangeListener weakPcl = WeakListeners.propertyChange(pcl, null);

    /**
     * Updates the values of the properties in the current property sheet with
     * the new properties being passed in! Only if that property exists in the
     * current sheet will it be applied. That way, we allow for subclasses to
     * add their own (or omit some!) properties and we will not accidentally
     * disrupt their UI.
     *
     * Race condition if not synchronized. Only one update should be applied at
     * a time. The timing of currSheetSet.getProperties() could result in
     * wrong/stale data being shown!
     *
     * @param newProps New file property instances to be updated in the current
     *                 sheet.
     */
    private synchronized void updateProperty(ToggleableNodeProperty... newProps) {

        //Refresh ONLY those properties in the sheet currently. Subclasses may have 
        //only added a subset of our properties or their own props! Let's keep their UI correct.
        Sheet currSheet = this.getSheet();
        Sheet.Set currSheetSet = currSheet.get(Sheet.PROPERTIES);
        Property<?>[] currProps = currSheetSet.getProperties();
        
        Map<String, ToggleableNodeProperty> newPropsMap = new HashMap<>();
        for(ToggleableNodeProperty property: newProps) {
            newPropsMap.put(property.getName(), property);
        }

        for (int i = 0; i < currProps.length; i++) {
            String currentPropertyName = currProps[i].getName();
            if (newPropsMap.containsKey(currentPropertyName) && 
                    newPropsMap.get(currentPropertyName).isEnabled()) {
                currProps[i] = newPropsMap.get(currentPropertyName);
            }
        }

        currSheetSet.put(currProps);
        currSheet.put(currSheetSet);

        //setSheet() will notify Netbeans to update this node in the UI!
        this.setSheet(currSheet);
    }

    /*
     * This is called when the node is first initialized. Any new updates or
     * changes happen by directly manipulating the sheet. That means we can fire
     * off background events everytime this method is called and not worry about
     * duplicated jobs!
     */
    @Override
    protected synchronized Sheet createSheet() {
        Sheet sheet = new Sheet();
        Sheet.Set sheetSet = Sheet.createPropertiesSet();
        sheet.put(sheetSet);

        //This will fire off fresh background tasks.
        List<ToggleableNodeProperty> newProperties = getProperties();

        //Add only the enabled properties to the sheet!
        for (ToggleableNodeProperty property : newProperties) {
            if (property.isEnabled()) {
                sheetSet.put(property);
            }
        }

        return sheet;
    }

    @NbBundle.Messages({"AbstractAbstractFileNode.nameColLbl=Name",
        "AbstractAbstractFileNode.translateFileName=Translated Name",
        "AbstractAbstractFileNode.createSheet.score.name=S",
        "AbstractAbstractFileNode.createSheet.comment.name=C",
        "AbstractAbstractFileNode.createSheet.count.name=O",
        "AbstractAbstractFileNode.locationColLbl=Location",
        "AbstractAbstractFileNode.modifiedTimeColLbl=Modified Time",
        "AbstractAbstractFileNode.changeTimeColLbl=Change Time",
        "AbstractAbstractFileNode.accessTimeColLbl=Access Time",
        "AbstractAbstractFileNode.createdTimeColLbl=Created Time",
        "AbstractAbstractFileNode.sizeColLbl=Size",
        "AbstractAbstractFileNode.flagsDirColLbl=Flags(Dir)",
        "AbstractAbstractFileNode.flagsMetaColLbl=Flags(Meta)",
        "AbstractAbstractFileNode.modeColLbl=Mode",
        "AbstractAbstractFileNode.useridColLbl=UserID",
        "AbstractAbstractFileNode.groupidColLbl=GroupID",
        "AbstractAbstractFileNode.metaAddrColLbl=Meta Addr.",
        "AbstractAbstractFileNode.attrAddrColLbl=Attr. Addr.",
        "AbstractAbstractFileNode.typeDirColLbl=Type(Dir)",
        "AbstractAbstractFileNode.typeMetaColLbl=Type(Meta)",
        "AbstractAbstractFileNode.knownColLbl=Known",
        "AbstractAbstractFileNode.md5HashColLbl=MD5 Hash",
        "AbstractAbstractFileNode.objectId=Object ID",
        "AbstractAbstractFileNode.mimeType=MIME Type",
        "AbstractAbstractFileNode.extensionColLbl=Extension"})
    public enum AbstractFilePropertyType {

        NAME(AbstractAbstractFileNode_nameColLbl()),
        TRANSLATION(AbstractAbstractFileNode_translateFileName()),
        SCORE(AbstractAbstractFileNode_createSheet_score_name()),
        COMMENT(AbstractAbstractFileNode_createSheet_comment_name()),
        OCCURRENCES(AbstractAbstractFileNode_createSheet_count_name()),
        LOCATION(AbstractAbstractFileNode_locationColLbl()),
        MOD_TIME(AbstractAbstractFileNode_modifiedTimeColLbl()),
        CHANGED_TIME(AbstractAbstractFileNode_changeTimeColLbl()),
        ACCESS_TIME(AbstractAbstractFileNode_accessTimeColLbl()),
        CREATED_TIME(AbstractAbstractFileNode_createdTimeColLbl()),
        SIZE(AbstractAbstractFileNode_sizeColLbl()),
        FLAGS_DIR(AbstractAbstractFileNode_flagsDirColLbl()),
        FLAGS_META(AbstractAbstractFileNode_flagsMetaColLbl()),
        MODE(AbstractAbstractFileNode_modeColLbl()),
        USER_ID(AbstractAbstractFileNode_useridColLbl()),
        GROUP_ID(AbstractAbstractFileNode_groupidColLbl()),
        META_ADDR(AbstractAbstractFileNode_metaAddrColLbl()),
        ATTR_ADDR(AbstractAbstractFileNode_attrAddrColLbl()),
        TYPE_DIR(AbstractAbstractFileNode_typeDirColLbl()),
        TYPE_META(AbstractAbstractFileNode_typeMetaColLbl()),
        KNOWN(AbstractAbstractFileNode_knownColLbl()),
        MD5HASH(AbstractAbstractFileNode_md5HashColLbl()),
        ObjectID(AbstractAbstractFileNode_objectId()),
        MIMETYPE(AbstractAbstractFileNode_mimeType()),
        EXTENSION(AbstractAbstractFileNode_extensionColLbl());

        final private String displayString;

        private AbstractFilePropertyType(String displayString) {
            this.displayString = displayString;
        }

        @Override
        public String toString() {
            return displayString;
        }
    }

    /**
     * Creates a list of properties for this file node. Each property has its
     * own strategy for producing a value, its own description, name, and
     * ability to be disabled. The ToggleableNodeProperty abstract class
     * provides a wrapper for all of these characteristics. Additionally, with a
     * return value of a list, any children classes of this node may reorder or
     * omit any of these properties as they see fit for their use case.
     *
     * @return List of file properties associated with this file node's content.
     */
    List<ToggleableNodeProperty> getProperties() {
        List<ToggleableNodeProperty> properties = new ArrayList<>();
        properties.add(new ToggleableNodeProperty(
                NAME.toString(),
                NO_DESCR,
                FileNodeUtil.getContentDisplayName(content)));

        //Initialize dummy place holder properties! These obviously do no work
        //to get their property values, but at the bottom we kick off a background
        //task that promises to update these values.
        final String NO_OP = "";
        properties.add(new ToggleableNodeProperty(
                TRANSLATION.toString(),
                NO_DESCR,
                NO_OP) {
            @Override
            public boolean isEnabled() {
                return UserPreferences.displayTranslationFileNames();
            }
        });
        properties.add(new ToggleableNodeProperty(
                SCORE.toString(),
                NO_DESCR,
                NO_OP));
        properties.add(new ToggleableNodeProperty(
                COMMENT.toString(),
                NO_DESCR,
                NO_OP) {
        });

        properties.add(new ToggleableNodeProperty(
                OCCURRENCES.toString(),
                NO_DESCR,
                NO_OP) {
            @Override
            public boolean isEnabled() {
                return !UserPreferences.hideCentralRepoCommentsAndOccurrences();
            }
        });
        properties.add(new ToggleableNodeProperty(
                LOCATION.toString(),
                NO_DESCR,
                FileNodeUtil.getContentPath(content)));
        properties.add(new ToggleableNodeProperty(
                MOD_TIME.toString(),
                NO_DESCR,
                ContentUtils.getStringTime(content.getMtime(), content)));
        properties.add(new ToggleableNodeProperty(
                CHANGED_TIME.toString(),
                NO_DESCR,
                ContentUtils.getStringTime(content.getCtime(), content)));
        properties.add(new ToggleableNodeProperty(
                ACCESS_TIME.toString(),
                NO_DESCR,
                ContentUtils.getStringTime(content.getAtime(), content)));
        properties.add(new ToggleableNodeProperty(
                CREATED_TIME.toString(),
                NO_DESCR,
                ContentUtils.getStringTime(content.getCrtime(), content)));
        properties.add(new ToggleableNodeProperty(
                SIZE.toString(),
                NO_DESCR,
                StringUtils.defaultString(content.getMIMEType())));
        properties.add(new ToggleableNodeProperty(
                FLAGS_DIR.toString(),
                NO_DESCR,
                content.getSize()));
        properties.add(new ToggleableNodeProperty(
                FLAGS_META.toString(),
                NO_DESCR,
                content.getMetaFlagsAsString()));
        properties.add(new ToggleableNodeProperty(
                MODE.toString(),
                NO_DESCR,
                content.getModesAsString()));
        properties.add(new ToggleableNodeProperty(
                USER_ID.toString(),
                NO_DESCR,
                content.getUid()));
        properties.add(new ToggleableNodeProperty(
                GROUP_ID.toString(),
                NO_DESCR,
                content.getGid()));
        properties.add(new ToggleableNodeProperty(
                META_ADDR.toString(),
                NO_DESCR,
                content.getMetaAddr()));
        properties.add(new ToggleableNodeProperty(
                ATTR_ADDR.toString(),
                NO_DESCR,
                content.getAttrType().getValue() + "-" + content.getAttributeId()));
        properties.add(new ToggleableNodeProperty(
                TYPE_DIR.toString(),
                NO_DESCR,
                content.getDirType().getLabel()));
        properties.add(new ToggleableNodeProperty(
                TYPE_META.toString(),
                NO_DESCR,
                content.getMetaType().toString()));
        properties.add(new ToggleableNodeProperty(
                KNOWN.toString(),
                NO_DESCR,
                content.getKnown().getName()));
        properties.add(new ToggleableNodeProperty(
                MD5HASH.toString(),
                NO_DESCR,
                StringUtils.defaultString(content.getMd5Hash())));
        properties.add(new ToggleableNodeProperty(
                ObjectID.toString(),
                NO_DESCR,
                content.getId()));
        properties.add(new ToggleableNodeProperty(
                MIMETYPE.toString(),
                NO_DESCR,
                StringUtils.defaultString(content.getMIMEType())));
        properties.add(new ToggleableNodeProperty(
                EXTENSION.toString(),
                NO_DESCR,
                content.getNameExtension()));

        //Submit the database queries ASAP! We want updated SCO columns
        //without blocking the UI as soon as we can get it! Keep all weak references
        //so this task doesn't block the ability of this node to be GC'd.
        pool.submit(new SCOAndTranslationTask(new WeakReference<>(content), weakPcl));
        return properties;
    }

    /**
     * Used by subclasses of AbstractAbstractFileNode to add the tags property
     * to their sheets.
     *
     * @param sheetSet the modifiable Sheet.Set returned by
     *                 Sheet.get(Sheet.PROPERTIES)
     *
     * @deprecated
     */
    @NbBundle.Messages("AbstractAbstractFileNode.tagsProperty.displayName=Tags")
    @Deprecated
    protected void addTagProperty(Sheet.Set sheetSet) {
        List<ContentTag> tags = new ArrayList<>();
        try {
            tags.addAll(Case.getCurrentCaseThrows().getServices().getTagsManager().getContentTagsByContent(content));
        } catch (TskCoreException | NoCurrentCaseException ex) {
            logger.log(Level.SEVERE, "Failed to get tags for content " + content.getName(), ex);
        }
        sheetSet.put(new NodeProperty<>("Tags", AbstractAbstractFileNode_tagsProperty_displayName(),
                NO_DESCR, tags.stream().map(t -> t.getName().getDisplayName())
                        .distinct()
                        .collect(Collectors.joining(", "))));
    }

    /**
     * Gets a comma-separated values list of the names of the hash sets
     * currently identified as including a given file.
     *
     * @param file The file.
     *
     * @return The CSV list of hash set names.
     *
     * @deprecated
     */
    @Deprecated
    protected static String getHashSetHitsCsvList(AbstractFile file) {
        try {
            return StringUtils.join(file.getHashSetNames(), ", ");
        } catch (TskCoreException tskCoreException) {
            logger.log(Level.WARNING, "Error getting hashset hits: ", tskCoreException); //NON-NLS
            return "";
        }
    }

    /**
     * Fill map with AbstractFile properties
     *
     * @param map     map with preserved ordering, where property names/values
     *                are put
     * @param content The content to get properties for.
     */
    @Deprecated
    static public void fillPropertyMap(Map<String, Object> map, AbstractFile content) {
        map.put(NAME.toString(), FileNodeUtil.getContentDisplayName(content));
        map.put(LOCATION.toString(), FileNodeUtil.getContentPath(content));
        map.put(MOD_TIME.toString(), ContentUtils.getStringTime(content.getMtime(), content));
        map.put(CHANGED_TIME.toString(), ContentUtils.getStringTime(content.getCtime(), content));
        map.put(ACCESS_TIME.toString(), ContentUtils.getStringTime(content.getAtime(), content));
        map.put(CREATED_TIME.toString(), ContentUtils.getStringTime(content.getCrtime(), content));
        map.put(SIZE.toString(), content.getSize());
        map.put(FLAGS_DIR.toString(), content.getDirFlagAsString());
        map.put(FLAGS_META.toString(), content.getMetaFlagsAsString());
        map.put(MODE.toString(), content.getModesAsString());
        map.put(USER_ID.toString(), content.getUid());
        map.put(GROUP_ID.toString(), content.getGid());
        map.put(META_ADDR.toString(), content.getMetaAddr());
        map.put(ATTR_ADDR.toString(), content.getAttrType().getValue() + "-" + content.getAttributeId());
        map.put(TYPE_DIR.toString(), content.getDirType().getLabel());
        map.put(TYPE_META.toString(), content.getMetaType().toString());
        map.put(KNOWN.toString(), content.getKnown().getName());
        map.put(MD5HASH.toString(), StringUtils.defaultString(content.getMd5Hash()));
        map.put(ObjectID.toString(), content.getId());
        map.put(MIMETYPE.toString(), StringUtils.defaultString(content.getMIMEType()));
        map.put(EXTENSION.toString(), content.getNameExtension());
    }
}
