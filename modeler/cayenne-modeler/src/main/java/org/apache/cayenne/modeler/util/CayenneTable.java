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

package org.apache.cayenne.modeler.util;

import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.DragSource;

import javax.activation.DataHandler;
import javax.swing.DefaultCellEditor;
import javax.swing.DefaultListSelectionModel;
import javax.swing.DropMode;
import javax.swing.JComponent;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.TransferHandler;
import javax.swing.event.ChangeEvent;
import javax.swing.event.TableModelEvent;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableModel;

import org.apache.cayenne.modeler.Application;

/**
 * Common superclass of tables used in Cayenne. Contains some common configuration
 * settings and utility methods.
 *
 */
public class CayenneTable extends JTable {

    private static Color ALTERNATE_COLOR = new Color(243, 246, 250);
    
    private boolean isColumnWidthChanged;

    public CayenneTable() {
        super();
        this.setRowHeight(25);
        this.setRowMargin(0);
        this.setIntercellSpacing(new Dimension(0, 0));
        this.setShowGrid(false);

        setSelectionModel(new CayenneListSelectionModel());

        // Disable Sorting
        this.setRowSorter(null);

        // Drag and Drop
        final JTable table = this;
        this.setSelectionMode(DefaultListSelectionModel.SINGLE_SELECTION);
        this.setDragEnabled(true);
        this.setDropMode(DropMode.INSERT_ROWS);
        this.setTransferHandler(new TransferHandler() {

            private final DataFlavor localObjectFlavor = new DataFlavor(Integer.class, "Integer Row Index");

            @Override
            protected Transferable createTransferable(JComponent c) {
                return new DataHandler(table.getSelectedRow(), localObjectFlavor.getMimeType());
            }

            @Override
            public boolean canImport(TransferHandler.TransferSupport info) {
                boolean b = info.getComponent() == table && info.isDrop() && info.isDataFlavorSupported(localObjectFlavor);
                table.setCursor(b ? DragSource.DefaultMoveDrop : DragSource.DefaultMoveNoDrop);
                return b;
            }

            @Override
            public int getSourceActions(JComponent c) {
                return TransferHandler.MOVE;
            }

            @Override
            public boolean importData(TransferHandler.TransferSupport info) {
                JTable target = (JTable) info.getComponent();
                JTable.DropLocation dl = (JTable.DropLocation) info.getDropLocation();
                int index = dl.getRow();
                int max = table.getModel().getRowCount();
                if (index < 0 || index > max)
                    index = max;
                target.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
                try {
                    Integer rowFrom = (Integer) info.getTransferable().getTransferData(localObjectFlavor);
                    if (rowFrom != -1 && rowFrom != index) {
                        if (index > rowFrom)
                            index--;
                        ((CayenneTableModel) table.getModel()).moveRow(rowFrom, index);
                        target.getSelectionModel().addSelectionInterval(index, index);
                        return true;
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                return false;
            }

            @Override
            protected void exportDone(JComponent c, Transferable t, int act) {
                if (act == TransferHandler.MOVE) {
                    table.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
                }
            }

        });
    }

    @Override
    protected void createDefaultEditors() {
        super.createDefaultEditors();

        JTextField textField = new JTextField(20);
        final DefaultCellEditor textEditor = Application
                .getWidgetFactory()
                .createCellEditor(textField);
        textEditor.setClickCountToStart(1);

        setDefaultEditor(Object.class, textEditor);
        setDefaultEditor(String.class, textEditor);
    }

    /**
     * @return CayenneTableModel, or null if model can't be casted to CayenneTableModel.
     */
    public CayenneTableModel getCayenneModel() {
        TableModel model = getModel();
        if(model instanceof CayenneTableModel) {
            return (CayenneTableModel) model;
        }
        return null;
    }

    /**
     * Cancels editing of any cells that maybe currently edited. This method should be
     * called before updating any selections.
     */
    public void cancelEditing() {
        editingCanceled(new ChangeEvent(this));
    }

    public void select(Object row) {
        if (row == null) {
            return;
        }

        CayenneTableModel model = getCayenneModel();
        int ind = model.getObjectList().indexOf(row);

        if (ind >= 0) {
            getSelectionModel().setSelectionInterval(ind, ind);
        }
    }

    public void select(int index) {

        CayenneTableModel model = getCayenneModel();
        if (index >= model.getObjectList().size()) {
            index = model.getObjectList().size() - 1;
        }

        if (index >= 0) {
            getSelectionModel().setSelectionInterval(index, index);
        }
    }

    /**
     * Selects multiple rows at once. Fires not more than only one ListSelectionEvent
     */
    public void select(int[] rows) {
        ((CayenneListSelectionModel) getSelectionModel()).setSelection(rows);
    }

    @Override
    public void tableChanged(TableModelEvent e) {
        cancelEditing();
        super.tableChanged(e);
    }

    /**
     * ListSelectionModel for Cayenne table. Has a method to set multiple rows selection
     * at once.
     */
    class CayenneListSelectionModel extends DefaultListSelectionModel {

        boolean fireForbidden = false;

        /**
         * Selects selection on multiple rows at once. Fires no more than one
         * ListSelectionEvent
         */
        public void setSelection(int[] rows) {
            /**
             * First check if we must do anything at all
             */
            boolean selectionChanged = false;
            for (int row : rows) {
                if (!isRowSelected(row)) {
                    selectionChanged = true;
                    break;
                }
            }

            if (!selectionChanged) {
                for (int i = getMinSelectionIndex(); i < getMaxSelectionIndex(); i++) {
                    if (isSelectedIndex(i)) {
                        boolean inNewSelection = false;
                        for (int row : rows) {
                            if (row == i) {
                                inNewSelection = true;
                                break;
                            }
                        }

                        if (!inNewSelection) {
                            selectionChanged = true;
                            break;
                        }
                    }
                }
            }

            if (!selectionChanged) {
                return;
            }

            fireForbidden = true;

            clearSelection();
            for (int row : rows) {
                if (row >= 0 && row < getRowCount()) {
                    addRowSelectionInterval(row, row);
                }
            }

            fireForbidden = false;

            fireValueChanged(getValueIsAdjusting());
        }

        @Override
        protected void fireValueChanged(int firstIndex, int lastIndex, boolean isAdjusting) {
            if (!fireForbidden) {
                super.fireValueChanged(firstIndex, lastIndex, isAdjusting);
            }
        }
    }

    public void changeSelection(final int row, final int column, boolean toggle, boolean extend) {
        super.changeSelection(row, column, toggle, extend);
        startCellEditingOnTabPressed(row, column);
    }

    private void startCellEditingOnTabPressed(final int row, final int column) {
        if (isCellEditable(row, column)) {
            this.editCellAt(row, column);
            editorComp.requestFocus();
        }
    }

    public boolean getColumnWidthChanged() {
        return isColumnWidthChanged;
    }

    public void setColumnWidthChanged(boolean widthChanged) {
        isColumnWidthChanged = widthChanged;
    }

    @Override
    public Component prepareRenderer(TableCellRenderer renderer, int row, int column) {
        Component component = super.prepareRenderer(renderer, row, column);
        if (row % 2 == 0) {
            if (isCellSelected(row, column)) {
                component.setBackground(Color.BLUE);
            } else {
                component.setBackground(ALTERNATE_COLOR);
            }
        } else {
            if (isCellSelected(row, column)) {
                component.setBackground(Color.BLUE);
            } else {
                component.setBackground(Color.WHITE);
            }
        }
        return component;
    }
}
