/*****************************************************************
 *   Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 ****************************************************************/

package org.apache.cayenne.modeler.editor.cgen;

import org.apache.cayenne.gen.CgenConfiguration;
import org.apache.cayenne.map.DataMap;
import org.apache.cayenne.map.Embeddable;
import org.apache.cayenne.map.ObjEntity;
import org.apache.cayenne.modeler.ProjectController;
import org.apache.cayenne.modeler.util.CayenneController;
import org.apache.cayenne.modeler.util.CellRenderers;
import org.apache.cayenne.validation.ValidationFailure;
import org.apache.cayenne.validation.ValidationResult;

import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.function.Predicate;

/**
 * A base superclass of a top controller for the code generator. Defines all common model
 * parts used in class generation.
 *
 */
public abstract class CodeGeneratorControllerBase extends CayenneController {

    public static final String SELECTED_PROPERTY = "selected";

    protected DataMap dataMap;
    protected ValidationResult validation;
    protected List<Object> classes;
    private Set<String> selectedEntities;
    private Set<String> selectedEmbeddables;
    private Set<String> isDataMapSelected;
    private Map<DataMap, Set<String>> selectedEntitiesForDataMap;
    private Map<DataMap, Set<String>> selectedEmbeddablesForDataMap;
    private Map<DataMap, Set<String>> selectedDataMaps;
    protected transient Object currentClass;
    protected ProjectController projectController;

    public CodeGeneratorControllerBase(CayenneController parent, ProjectController projectController) {
        super(parent);
        this.projectController = projectController;
        this.classes = new ArrayList<>();
        this.selectedEntitiesForDataMap = new HashMap<>();
        this.selectedEmbeddablesForDataMap = new HashMap<>();
        this.selectedDataMaps = new HashMap<>();
    }

    public void startup(DataMap dataMap){
        this.dataMap = dataMap;
        prepareClasses(dataMap);
    }

    private void prepareClasses(DataMap dataMap) {
        classes.clear();
        this.classes.add(dataMap);
        this.classes.addAll(dataMap.getObjEntities());
        this.classes.addAll(dataMap.getEmbeddables());
        initCollectionsForSelection(dataMap);
    }

    private void initCollectionsForSelection(DataMap dataMap) {
        selectedEntities = selectedEntitiesForDataMap.compute(dataMap, (key,value) ->
                value == null ? new HashSet<>() : value);
        selectedEmbeddables = selectedEmbeddablesForDataMap.compute(dataMap, (key, value) ->
                value == null ? new HashSet<>() : value);
        isDataMapSelected = selectedDataMaps.compute(dataMap, (key, value) ->
                value == null ? new HashSet<>() : value);
    }

    public List<Object> getClasses() {
        return classes;
    }

    public abstract Component getView();

    public void validate(GeneratorController validator) {

        ValidationResult validationBuffer = new ValidationResult();

        if (validator != null) {
            for (Object classObj : classes) {
                if (classObj instanceof ObjEntity) {
                    validator.validateEntity(
                            validationBuffer,
                            (ObjEntity) classObj,
                            false);
                }
                else if (classObj instanceof Embeddable) {
                    validator.validateEmbeddable(validationBuffer, (Embeddable) classObj);
                }
            }

        }

        this.validation = validationBuffer;
    }

    public boolean updateSelection(Predicate<Object> predicate) {
        boolean modified = false;

        for (Object classObj : classes) {
            boolean select = predicate.test(classObj);
            if (classObj instanceof ObjEntity) {

                if (select) {
                    if(selectedEntities.add(((ObjEntity) classObj).getName())) {
                        modified = true;
                    }
                }
                else {
                    if(selectedEntities.remove(((ObjEntity) classObj).getName())) {
                        modified = true;
                    }
                }
            }
            else if (classObj instanceof Embeddable) {
                if (select) {
                    if(selectedEmbeddables.add(((Embeddable) classObj).getClassName())) {
                        modified = true;
                    }
                }
                else {
                    if(selectedEmbeddables.remove(((Embeddable) classObj).getClassName())) {
                        modified = true;
                    }
                }
            } else if (classObj instanceof DataMap) {
                updateArtifactGenerationMode(classObj, select);
                if(select) {
                    if(isDataMapSelected.add(((DataMap) classObj).getName())) {
                        modified = true;
                    }
                } else {
                    if(isDataMapSelected.remove(((DataMap) classObj).getName())) {
                        modified = true;
                    }
                }
            }

        }

        if (modified) {
            firePropertyChange(SELECTED_PROPERTY, null, null);
        }

        return modified;
    }

    public List<Embeddable> getSelectedEmbeddables() {

        List<Embeddable> selected = new ArrayList<>(selectedEmbeddables.size());

        for (Object classObj : classes) {
            if (classObj instanceof Embeddable
                    && selectedEmbeddables.contains(((Embeddable) classObj)
                            .getClassName())) {
                selected.add((Embeddable) classObj);
            }
        }

        return selected;
    }

    public List<ObjEntity> getSelectedEntities() {
        List<ObjEntity> selected = new ArrayList<>(selectedEntities.size());
        for (Object classObj : classes) {
            if (classObj instanceof ObjEntity
                    && selectedEntities.contains(((ObjEntity) classObj).getName())) {
                selected.add(((ObjEntity) classObj));
            }
        }

        return selected;
    }

    /**
     * Returns the first encountered validation problem for an antity matching the name or
     * null if the entity is valid or the entity is not present.
     */
    public String getProblem(Object obj) {

        String name = null;
        
        if (obj instanceof ObjEntity) {
            name = ((ObjEntity) obj).getName();
        }
        else if (obj instanceof Embeddable) {
            name = ((Embeddable) obj).getClassName();
        }
        
        if (validation == null) {
            return null;
        }

        List failures = validation.getFailures(name);
        if (failures.isEmpty()) {
            return null;
        }

        return ((ValidationFailure) failures.get(0)).getDescription();
    }

    public boolean isSelected() {
        if (currentClass instanceof ObjEntity) {
            return selectedEntities
                    .contains(((ObjEntity) currentClass).getName());
        }
        if (currentClass instanceof Embeddable) {
            return selectedEmbeddables
                    .contains(((Embeddable) currentClass).getClassName());
        }
        if(currentClass instanceof DataMap) {
            return isDataMapSelected
                    .contains(((DataMap) currentClass).getName());
        }
        return false;
    }

    public void setSelected(boolean selectedFlag) {
        if (currentClass == null) {
            return;
        }
        if (currentClass instanceof ObjEntity) {
            if (selectedFlag) {
                if (selectedEntities.add(((ObjEntity) currentClass).getName())) {
                    firePropertyChange(SELECTED_PROPERTY, null, null);
                }
            } else {
                if (selectedEntities.remove(((ObjEntity) currentClass).getName())) {
                    firePropertyChange(SELECTED_PROPERTY, null, null);
                }
            }
        }
        if (currentClass instanceof Embeddable) {
            if (selectedFlag) {
                if (selectedEmbeddables.add(((Embeddable) currentClass).getClassName())) {
                    firePropertyChange(SELECTED_PROPERTY, null, null);
                }
            } else {
                if (selectedEmbeddables
                        .remove(((Embeddable) currentClass).getClassName())) {
                    firePropertyChange(SELECTED_PROPERTY, null, null);
                }
            }
        }
        if(currentClass instanceof DataMap) {
            updateArtifactGenerationMode(currentClass, selectedFlag);
            if(selectedFlag) {
                if(isDataMapSelected.add(dataMap.getName())) {
                    firePropertyChange(SELECTED_PROPERTY, null, null);
                }
            } else {
                if(isDataMapSelected
                        .remove(((DataMap) currentClass).getName())) {
                    firePropertyChange(SELECTED_PROPERTY, null, null);
                }
            }
        }
    }

    private void updateArtifactGenerationMode(Object classObj, boolean selected) {
        DataMap dataMap = (DataMap) classObj;
        CgenConfiguration cgenConfiguration = projectController.getApplication().getMetaData().get(dataMap, CgenConfiguration.class);
        if(selected) {
            cgenConfiguration.setArtifactsGenerationMode("all");
        } else {
            cgenConfiguration.setArtifactsGenerationMode("entity");
        }
    }

    public JLabel getItemName(Object obj) {
        String className;
        Icon icon;
        if (obj instanceof Embeddable) {
            className = ((Embeddable) obj).getClassName();
            icon = CellRenderers.iconForObject(new Embeddable());
        } else if(obj instanceof ObjEntity) {
            className = ((ObjEntity) obj).getName();
            icon = CellRenderers.iconForObject(new ObjEntity());
        } else {
            className = ((DataMap) obj).getName();
            icon = CellRenderers.iconForObject(new DataMap());
        }
        JLabel labelIcon = new JLabel();
        labelIcon.setIcon(icon);
        labelIcon.setVisible(true);
        labelIcon.setText(className);
        return labelIcon;
    }

    public void updateEntities(){
        DataMap map = getProjectController().getCurrentDataMap();
        CgenConfiguration cgenConfiguration = projectController.getApplication().getMetaData().get(map, CgenConfiguration.class);
        if(cgenConfiguration != null) {
            cgenConfiguration.resetCollections();
            for(ObjEntity entity: getSelectedEntities()) {
                if(!entity.isGeneric()) {
                    cgenConfiguration.loadEntity(entity.getName());
                }
            }
            for(Embeddable embeddable : getSelectedEmbeddables()) {
                cgenConfiguration.loadEmbeddable(embeddable.getClassName());
            }
        }
    }

    void addToSelectedEntities(DataMap dataMap, Collection<String> entities) {
        prepareClasses(dataMap);
        selectedEntities.addAll(entities);
        updateEntities();
    }

    void addToSelectedEmbeddables(DataMap dataMap, Collection<String> embeddables) {
        prepareClasses(dataMap);
        selectedEmbeddables.addAll(embeddables);
        updateEntities();
    }

    public int getSelectedEntitiesSize() {
        return selectedEntities != null ? selectedEntities.size() : 0;
    }

    public int getSelectedEmbeddablesSize() {
        return selectedEmbeddables != null ? selectedEmbeddables.size() : 0;
    }

    public boolean isDataMapSelected() {
        return isDataMapSelected != null && isDataMapSelected.size() == 1;
    }

    public DataMap getDataMap() {
        return dataMap;
    }

    public ProjectController getProjectController() {
        return projectController;
    }

    public Object getCurrentClass() {
        return currentClass;
    }

    public void setCurrentClass(Object currentClass) {
        this.currentClass = currentClass;
    }
}
