package abs.backend.java.debugging;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;

import javax.swing.AbstractCellEditor;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableCellRenderer;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.DefaultHighlighter.DefaultHighlightPainter;
import javax.swing.text.Highlighter;
import javax.swing.text.Highlighter.HighlightPainter;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeCellRenderer;
import javax.swing.tree.TreeModel;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;

import abs.backend.java.lib.runtime.ABSException;
import abs.backend.java.lib.types.ABSValue;
import abs.backend.java.observing.COGView;
import abs.backend.java.observing.FutView;
import abs.backend.java.observing.GuardView;
import abs.backend.java.observing.ObjectCreationObserver;
import abs.backend.java.observing.ObjectObserver;
import abs.backend.java.observing.ObjectView;
import abs.backend.java.observing.SystemObserver;
import abs.backend.java.observing.TaskObserver;
import abs.backend.java.observing.TaskView;
import abs.backend.java.utils.ColorUtils;
import abs.backend.java.utils.StringUtil;

public class GraphicalDebugger implements SystemObserver {
    final DebugWindow window;
    final DebugModel model;

    public GraphicalDebugger() {
        model = new DebugModel();
        window = new DebugWindow(model);
    }

    // only for ad-hoc testing
    public static void main(String[] args) {
        new GraphicalDebugger();
    }

    @Override
    public void systemStarted() {
    }

    @Override
    public void newCOGCreated(COGView cog, ObjectView initialObject) {
        model.cogCreated(cog, initialObject);
    }

}

interface DebugModelListener {
    void taskInfoChanged(TaskInfo line);

    void taskInfoAdded(TaskInfo line);

    void taskInfoRemoved(TaskInfo line);

    void cogCreated(COGInfo info);

    void cogChanged(COGInfo info);
}

class COGInfo {
    final COGView cog;
    final ObjectView initialObject;
    final List<TaskInfo> tasks = new ArrayList<TaskInfo>();

    COGInfo(COGView cog, ObjectView o) {
        this.cog = cog;
        this.initialObject = o;
    }

    public void addTask(TaskInfo task) {
        tasks.add(task);
    }

}

class DebugModel implements TaskObserver {
    final Map<TaskView, TaskInfo> taskToLineMap = new HashMap<TaskView, TaskInfo>();
    final Map<COGView, COGInfo> cogInfo = new HashMap<COGView, COGInfo>();
    final ArrayList<DebugModelListener> listener = new ArrayList<DebugModelListener>();
    final Set<TaskView> steppingTasks = new HashSet<TaskView>();

    public COGInfo getCOGInfo(COGView view) {
        return cogInfo.get(view);
    }

    public synchronized TaskInfo getTaskInfo(TaskView task) {
        return taskToLineMap.get(task);
    }

    public synchronized Semaphore getSema(TaskView info) {
        return taskToLineMap.get(info).stepSema;
    }

    public void stepTask(TaskView task) {
        getSema(task).release();
    }

    public void cogCreated(COGView cog, ObjectView initialObject) {
        cog.getScheduler().registerTaskObserver(this);
        ArrayList<DebugModelListener> localList;
        COGInfo info = new COGInfo(cog, initialObject);
        synchronized (this) {
            cogInfo.put(cog, info);
            localList = new ArrayList<DebugModelListener>(listener);
        }

        for (DebugModelListener l : localList) {
            l.cogCreated(info);
        }
    }

    public synchronized TaskInfo addInfoLine(TaskView task) {
        TaskInfo line = new TaskInfo(task);
        taskToLineMap.put(task, line);
        for (DebugModelListener l : listener) {
            l.taskInfoAdded(line);
        }
        return line;
    }

    public void updateInfoLine(TaskView task, TaskInfo line) {
        ArrayList<DebugModelListener> localList;
        synchronized (this) {
            taskToLineMap.put(task, line);
            localList = new ArrayList<DebugModelListener>(listener);
        }

        for (DebugModelListener l : localList) {
            l.taskInfoChanged(line);
        }
    }

    public synchronized void removeInfoLine(TaskView task) {
        TaskInfo line = taskToLineMap.remove(task);
        for (DebugModelListener l : listener) {
            l.taskInfoRemoved(line);
        }
    }

    public synchronized void registerListener(DebugModelListener l) {
        listener.add(l);
    }

    @Override
    public synchronized void taskCreated(TaskView task) {
        steppingTasks.add(task);

        task.registerTaskListener(this);
        TaskInfo info = addInfoLine(task);

        COGInfo cinfo = cogInfo.get(task.getCOG());
        cinfo.addTask(info);
        cogInfoChanged(cinfo);
    }

    private synchronized void cogInfoChanged(COGInfo info) {
        for (DebugModelListener l : listener) {
            l.cogChanged(info);
        }
    }

    @Override
    public synchronized void taskSuspended(TaskView task, GuardView guard) {
        updateTaskState(task, TaskState.SUSPENDED, guard, null);
    }

    @Override
    public synchronized void taskStarted(TaskView task) {
        updateTaskState(task, TaskState.RUNNING, null, null);
    }

    @Override
    public synchronized void taskDeadlocked(TaskView task) {
        updateTaskState(task, TaskState.DEADLOCKED, null, null);
    }

    @Override
    public synchronized void taskFinished(TaskView task) {
        TaskState newState = TaskState.FINISHED;

        if (task.hasException()) {
            ABSException e = task.getException();
            if (e.isAssertion()) {
                newState = TaskState.ASSERTION_FAILED;
            } else {
                newState = TaskState.EXCEPTION;
            }
        }

        updateTaskState(task, newState, null, null);
    }

    @Override
    public synchronized void taskBlockedOnFuture(TaskView task, FutView fut) {
        updateTaskState(task, TaskState.BLOCKED, null, fut);
    }

    @Override
    public void taskRunningAfterWaiting(TaskView task, FutView fut) {
        updateTaskState(task, TaskState.RUNNING, null, null);
    }

    @Override
    public void taskResumed(TaskView task, GuardView view) {
        updateTaskState(task, TaskState.RUNNING, null, null);
    }

    @Override
    public void taskReady(TaskView task) {
        updateTaskState(task, TaskState.READY, null, null);
    }

    private void updateTaskState(TaskView task, TaskState state, GuardView guard, FutView fut) {
        synchronized (this) {
            TaskInfo info = getTaskInfo(task);
            if (state == TaskState.RUNNING) {
                if (info.isStepping) {
                    steppingTasks.add(task);
                }
            } else {
                steppingTasks.remove(task);
            }
            info.state = state;
            info.waitingOnGuard = guard;
            info.blockedOnFuture = fut;
            updateInfoLine(task, info);
        }
    }

    private void waitForClick(TaskView task) {
        try {
            System.out.println("Task " + task.getID() + " waiting for click...");
            if (getTaskInfo(task).isStepping)
                getSema(task).acquire();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void taskStep(TaskView task, String fileName, int line) {
        synchronized (this) {
            TaskInfo info = getTaskInfo(task);
            info.updateLine(line);
            info.updateFile(fileName);
            info.state = TaskState.RUNNING;
            updateInfoLine(task, info);
        }
        // waitForClick(task);
    }

    public synchronized void stepRandom() {
        stepTask(steppingTasks.iterator().next());
    }

    public synchronized void runTask(TaskView task) {
        steppingTasks.remove(task);
        getTaskInfo(task).isStepping = false;
        getSema(task).release();
    }

}

class Line {
    int startPos;
    int endPos;
}

enum TaskState {
    READY(ColorUtils.setSaturation(Color.YELLOW, 0.5f)), SUSPENDED(ColorUtils.setSaturation(Color.ORANGE, 0.5f)), RUNNING(
            ColorUtils.setSaturation(Color.GREEN, 0.5f)), FINISHED(Color.LIGHT_GRAY), DEADLOCKED(Color.red), ASSERTION_FAILED(
            ColorUtils.PSYCHEDELIC_PURPLE), EXCEPTION(ColorUtils.PSYCHEDELIC_PURPLE), BLOCKED(ColorUtils.setSaturation(
            Color.RED, 0.5f));
    public final Color color;

    TaskState(Color c) {
        this.color = c;
    }
}

class TaskInfo {
    public FutView blockedOnFuture;
    TaskView task;
    int currentLine;
    int previousLine;
    String previousFile;
    String currentFile;
    TaskState state = TaskState.READY;
    GuardView waitingOnGuard;
    Semaphore stepSema = new Semaphore(0);
    boolean isStepping = true;

    public TaskInfo(TaskView task) {
        this.task = task;
    }

    public String toString() {
        return "Task " + task.getID();
    }

    public void updateLine(int newLine) {
        previousLine = currentLine;
        currentLine = newLine;
    }

    public void updateFile(String fileName) {
        previousFile = currentFile;
        currentFile = fileName;
    }
}

class SourceView extends JPanel implements DebugModelListener {
    private static final long serialVersionUID = 1L;
    private final DebugModel model;
    final Highlighter highlighter;
    final Highlighter.HighlightPainter highlightPainter;
    final Highlighter taskHighlighter;

    JTextArea textArea;
    JTextArea lineArea;
    JTextArea taskArea;

    File file;

    private final Map<TaskState, DefaultHighlightPainter> highlightPainters = new HashMap<TaskState, DefaultHighlightPainter>();
    private final String fileName;

    SourceView(DebugModel model, String fileName) {
        this.file = new File(fileName);
        this.fileName = fileName;
        this.model = model;
        JPanel content = new JPanel();
        this.setLayout(new BorderLayout());
        this.add(new JScrollPane(content), BorderLayout.CENTER);

        content.setLayout(new BorderLayout());

        JPanel leftContent = new JPanel();
        leftContent.setLayout(new BorderLayout());
        content.add(leftContent, BorderLayout.WEST);

        createHighlightPainters();

        textArea = new JTextArea();
        lineArea = new JTextArea();
        taskArea = new JTextArea();
        lineArea.setBorder(BorderFactory.createLineBorder(Color.gray));
        textArea.setBorder(BorderFactory.createLineBorder(Color.gray));
        taskArea.setBorder(BorderFactory.createLineBorder(Color.gray));
        textArea.setSelectionColor(Color.LIGHT_GRAY);

        highlighter = new DefaultHighlighter();
        highlightPainter = new DefaultHighlighter.DefaultHighlightPainter(ColorUtils.setSatAndBright(Color.BLUE, 0.5f,
                1f));
        textArea.setHighlighter(highlighter);

        taskHighlighter = new DefaultHighlighter();
        taskArea.setHighlighter(taskHighlighter);

        content.add(textArea, BorderLayout.CENTER);
        leftContent.add(lineArea, BorderLayout.EAST);
        leftContent.add(taskArea, BorderLayout.WEST);

        fillArea();

        model.registerListener(this);
    }

    private void createHighlightPainters() {
        for (TaskState s : TaskState.values()) {
            highlightPainters.put(s, new DefaultHighlightPainter(s.color));
        }
    }

    private void fillArea() {
        try {
            int lineNo = 1;
            BufferedReader reader = new BufferedReader(new FileReader(file));
            while (reader.ready()) {
                String line = reader.readLine();
                if (line == null)
                    break;
                textArea.append(line + "\n");
                lineArea.append(lineNo + " \n");
                taskArea.append("       \n");
                taskLineInfo.add(new ArrayList<TaskInfo>(0));
                lineNo++;
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }

        textArea.setCaretPosition(0);
        textArea.moveCaretPosition(1);
        textArea.setEditable(false);
    }

    List<List<TaskInfo>> taskLineInfo = new ArrayList<List<TaskInfo>>();
    Map<TaskInfo, Object> highlightTags = new HashMap<TaskInfo, Object>();

    class Highlight {
        TaskInfo info;
        int start;
        int end;
    }

    public void updateTaskLine(int line) {
        highlighter.removeAllHighlights();
        // taskHighlighter.removeAllHighlights();
        String string = "";
        boolean first = true;
        List<TaskInfo> tasks = taskLineInfo.get(line - 1);
        List<Highlight> highlights = new ArrayList<Highlight>();
        try {
            int start = taskArea.getLineStartOffset(line - 1);
            for (TaskInfo info : tasks) {
                Object tag = highlightTags.remove(info);
                if (tag != null)
                    taskHighlighter.removeHighlight(tag);

                Highlight highlight = new Highlight();
                highlight.info = info;
                highlight.start = start;

                if (first)
                    first = false;
                else {
                    string += ", ";
                    highlight.start += 2;
                }
                String taskString = "T" + info.task.getID();
                string += taskString;
                highlight.end = highlight.start + taskString.length();
                highlights.add(highlight);
                start = highlight.end;
            }

            if (tasks.isEmpty())
                string = "        ";
            else
                string += " ->";
            setTaskLine(string, line);

            for (Highlight h : highlights) {
                highlightTags.put(h.info, taskHighlighter.addHighlight(h.start, h.end, getHighlighterPainter(h.info)));
            }

        } catch (BadLocationException e) {
            e.printStackTrace();
        }
    }

    private HighlightPainter getHighlighterPainter(TaskInfo info) {
        return highlightPainters.get(info.state);
    }

    private void highlightLine(TaskInfo line) {
        try {
            int start = textArea.getLineStartOffset(line.currentLine - 1);
            int end = textArea.getLineEndOffset(line.currentLine - 1);
            highlighter.addHighlight(start, end, highlightPainter);
            textArea.setCaretPosition(end);
        } catch (BadLocationException e) {
            e.printStackTrace();
        }
    }

    private void setTaskLine(String string, int line) {
        try {
            int start = taskArea.getLineStartOffset(line - 1);
            int end = taskArea.getLineEndOffset(line - 1);
            taskArea.replaceRange(string + " \n", start, end);
        } catch (BadLocationException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void taskInfoChanged(TaskInfo infoLine) {
        if (infoLine.previousLine > 0 && infoLine.previousFile == fileName) {
            taskLineInfo.get(infoLine.previousLine - 1).remove(infoLine);
            updateTaskLine(infoLine.previousLine);
        }

        if (infoLine.state == TaskState.FINISHED) {
            taskInfoRemoved(infoLine);
            textArea.select(0, 0);
            return;
        }

        if (infoLine.currentLine > 0 && infoLine.currentFile == fileName) {
            if (!taskLineInfo.get(infoLine.currentLine - 1).contains(infoLine)) {
                taskLineInfo.get(infoLine.currentLine - 1).add(infoLine);
            }
            updateTaskLine(infoLine.currentLine);
            highlightLine(infoLine);
        }

    }

    @Override
    public void taskInfoAdded(TaskInfo line) {
    }

    @Override
    public void taskInfoRemoved(TaskInfo line) {
        if (line.currentFile == fileName) {
            taskLineInfo.get(line.currentLine - 1).remove(line);
            updateTaskLine(line.currentLine);
        }
    }

    @Override
    public void cogCreated(COGInfo cog) {
    }

    @Override
    public void cogChanged(COGInfo info) {
    }
}

class TaskTable extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final int STEP_COLUMN = 9;
    private static int[] INITIAL_COLUMN_WIDTHS = { 15, 50, 50, 50, 50, 50, 10, 70, 10 };

    final JTable table;
    final TableModel tableModel;
    final DebugModel debugModel;

    private JScrollPane scrollPane;

    TaskTable(DebugModel debugModel) {
        this.tableModel = new TableModel();
        this.debugModel = debugModel;

        setLayout(new BorderLayout());

        table = new JTable(tableModel);

        for (int c = 0; c < tableModel.getColumnCount(); c++) {
            table.getColumnModel().getColumn(c).setCellRenderer(new StringRenderer());
            table.getColumnModel().getColumn(c).setPreferredWidth(INITIAL_COLUMN_WIDTHS[c]);
        }
        /*
         * StepBtnCellEditor e = new StepBtnCellEditor();
         * table.getColumnModel().getColumn(STEP_COLUMN).setCellEditor(e);
         * table.getColumnModel().getColumn(STEP_COLUMN).setCellRenderer(e);
         */

        scrollPane = new JScrollPane(table);
        add(scrollPane, BorderLayout.CENTER);
        setBorder(BorderFactory.createTitledBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5), "Tasks"));
        setPreferredSize(new Dimension(200, 200));

        debugModel.registerListener(SwingWrapperProxy.newInstance(tableModel, DebugModelListener.class));
    }

    class StringRenderer extends JLabel implements TableCellRenderer {

        public StringRenderer() {
            setOpaque(true);
            setFont(getFont().deriveFont(Font.PLAIN));

        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                boolean hasFocus, int row, int column) {
            TaskInfo info = tableModel.rows.get(row);

            setBackground(info.state.color);
            setText((String) value);
            return this;
        }
    }

    class StepBtnCellEditor extends AbstractCellEditor implements TableCellRenderer, TableCellEditor {

        class BtnPanel extends JPanel {
            JButton stepBtn;
            JButton runBtn;

            BtnPanel(final TaskInfo task) {
                stepBtn = new JButton("step");
                stepBtn.addActionListener(new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent arg0) {
                        System.out.println("Clicked Step Task " + task.task.getID());
                        stopCellEditing();
                        debugModel.stepTask(task.task);
                    }
                });

                runBtn = new JButton("run");
                runBtn.addActionListener(new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent arg0) {
                        System.out.println("Clicked Run Task " + task.task.getID());
                        stopCellEditing();
                        debugModel.runTask(task.task);
                    }
                });

                setLayout(new BoxLayout(this, BoxLayout.LINE_AXIS));
                add(runBtn);
                add(stepBtn);
            }

            @Override
            public void setEnabled(boolean enabled) {
                super.setEnabled(enabled);
                runBtn.setEnabled(enabled);
                stepBtn.setEnabled(enabled);
            }
        }

        private List<BtnPanel> btns = new ArrayList<BtnPanel>();

        private BtnPanel getBtns(final int row) {
            final TaskInfo task = tableModel.rows.get(row);

            if (row >= btns.size()) {
                BtnPanel pnl = new BtnPanel(task);
                btns.add(pnl);
            }

            BtnPanel pnl = btns.get(row);
            pnl.setEnabled(task.state == TaskState.RUNNING);
            return pnl;
        }

        @Override
        public Object getCellEditorValue() {
            return "NOTHING";
        }

        @Override
        public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected, int row, int column) {
            return getBtns(row);
        }

        @Override
        public Component getTableCellRendererComponent(JTable arg0, Object value, boolean isSelected, boolean hasFocus,
                int row, int column) {
            return getBtns(row);
        }

    }

    class TableModel extends AbstractTableModel implements DebugModelListener {
        private static final long serialVersionUID = 1L;

        final List<TaskInfo> rows = new ArrayList<TaskInfo>();
        protected final String[] columnNames = new String[] { "Task ID", "Source", "Target", "Method", "State",
                "Condition", "COG", "Future" };

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            if (columnIndex != STEP_COLUMN)
                return false;
            TaskInfo info = rows.get(rowIndex);
            return info.state == TaskState.RUNNING;
        }

        @Override
        public String getColumnName(int column) {
            return columnNames[column];
        }

        @Override
        public int getColumnCount() {
            return columnNames.length;
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return String.class;
        }

        @Override
        public Object getValueAt(int row, int col) {
            TaskInfo line = rows.get(row);
            switch (col) {
            case 0: {
                return "" + line.task.getID();
            }
            case 1: {
                ObjectView source = line.task.getSource();
                if (source != null) {
                    return source.toString();
                }
                return "";

            }
            case 2: {
                return line.task.getTarget().toString();
            }
            case 3: {
                StringBuilder sb = new StringBuilder();
                sb.append(line.task.getMethodName());
                sb.append("(");
                sb.append(StringUtil.iterableToString(line.task.getArgs(), ", "));
                sb.append(")");
                return sb.toString();
            }
            case 4: {
                String res = line.state.toString();
                if (line.state == TaskState.EXCEPTION) {
                    res += " (" + line.task.getException().getName() + ")";
                }
                return res;
            }
            case 5:
                if (line.state == TaskState.SUSPENDED) {
                    if (line.waitingOnGuard != null) {
                        return line.waitingOnGuard.toABSString();
                    }
                } else if (line.blockedOnFuture != null) {
                    return "Fut " + line.blockedOnFuture.getID() + "?";

                }
                return "";
            case 6:
                return "" + line.task.getCOG().getID();
            case 7: {
                FutView fut = line.task.getFuture();
                if (fut.isResolved())
                    return "" + fut.getValue();
                else
                    return "<unresolved>";
            }
            case 8:
                return "NOTHING";
            }
            return "ERROR";
        }

        @Override
        public void taskInfoChanged(TaskInfo task) {
            int row = rows.indexOf(task);

            fireTableRowsUpdated(row, row);
            fireTableDataChanged();

        }

        @Override
        public void taskInfoAdded(TaskInfo task) {
            rows.add(task);
            fireTableRowsInserted(rows.size() - 1, rows.size() - 1);
            fireTableDataChanged();

        }

        @Override
        public void taskInfoRemoved(TaskInfo task) {
            int row = rows.indexOf(task);
            rows.remove(task);
            fireTableRowsDeleted(row, row);
        }

        @Override
        public void cogCreated(COGInfo cog) {
        }

        @Override
        public void cogChanged(COGInfo info) {
        }

    }
}

class COGTree extends JPanel {
    private final JTree tree;
    private final DebugModel debugModel;
    private final DefaultTreeModel treeModel;
    private final RootNode rootNode;

    COGTree(DebugModel model) {
        this.debugModel = model;
        this.rootNode = new RootNode();
        this.treeModel = new DefaultTreeModel(rootNode);

        setLayout(new BorderLayout());
        tree = new JTree(treeModel);
        tree.setCellRenderer(new COGNodeRenderer());
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);

        JScrollPane scrollPane = new JScrollPane(tree);
        add(scrollPane, BorderLayout.CENTER);
        setBorder(BorderFactory.createTitledBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5),
                "Concurrent Object Groups (COGs)"));
        setPreferredSize(new Dimension(400, 500));
        model.registerListener(SwingWrapperProxy.newInstance(rootNode, DebugModelListener.class));

    }

    private static final String OBJECTS = "Objects";
    private static final String TASKS = "Tasks";
    private static final String COGS = "COGs";

    class COGNodeRenderer implements TreeCellRenderer {

        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object node, boolean selected, boolean expanded,
                boolean leaf, int row, boolean hasFocus) {

            DefaultMutableTreeNode treeNode = (DefaultMutableTreeNode) node;
            Object value = treeNode.getUserObject();
            JLabel lbl;
            if (value instanceof COGInfo) {
                COGInfo cogInfo = (COGInfo) value;
                lbl = new JLabel("COG " + cogInfo.cog.getID() + " [" + cogInfo.initialObject + "]");
            } else if (value instanceof TaskInfo) {
                TaskInfo taskInfo = (TaskInfo) value;
                lbl = new JLabel("Task " + taskInfo.task.getID() + " (" + taskInfo.task.getTarget() + "."
                        + taskInfo.task.getMethodName() + ")");

                lbl.setBackground(taskInfo.state.color);
                lbl.setOpaque(true);

            } else if (value == OBJECTS || value == TASKS || value == COGS) {
                lbl = new JLabel(value + " (" + treeNode.getChildCount() + ")");
            } else if (value instanceof ObjectView) {
                ObjectView ov = (ObjectView) value;
                lbl = new JLabel(ov.toString());
            } else if (value instanceof String) {
                String fieldName = (String) value;
                DefaultMutableTreeNode objNode = (DefaultMutableTreeNode) treeNode.getParent();
                ObjectView v = (ObjectView) objNode.getUserObject();
                String fieldValue;
                try {
                    fieldValue = "" + v.getFieldValue(fieldName);
                } catch (NoSuchFieldException e) {
                    fieldValue = "ERROR";
                }
                lbl = new JLabel(fieldName + ":" + fieldValue);
            } else {
                lbl = new JLabel("???");
            }
            lbl.setFont(lbl.getFont().deriveFont(Font.PLAIN));
            return lbl;
        }

    }

    class RootNode extends DefaultMutableTreeNode implements DebugModelListener, ObjectCreationObserver {
        Map<COGView, DefaultMutableTreeNode> cogs = new HashMap<COGView, DefaultMutableTreeNode>();
        Map<TaskInfo, DefaultMutableTreeNode> tasks = new HashMap<TaskInfo, DefaultMutableTreeNode>();
        Map<ObjectView, DefaultMutableTreeNode> objects = new HashMap<ObjectView, DefaultMutableTreeNode>();

        @Override
        public void taskInfoChanged(TaskInfo line) {
            TreeNode node = tasks.get(line);
            treeModel.nodeChanged(node);

            DefaultMutableTreeNode cogNode = cogs.get(line.task.getCOG());
            TreeNode objectsNode = cogNode.getChildAt(0);
            for (int i = 0; i < objectsNode.getChildCount(); i++) {
                TreeNode objectNode = objectsNode.getChildAt(i);
                for (int j = 0; j < objectNode.getChildCount(); j++) {
                    treeModel.nodeChanged(objectNode.getChildAt(j));
                }
            }
        }

        @Override
        public void taskInfoAdded(TaskInfo line) {
            DefaultMutableTreeNode newNode = new DefaultMutableTreeNode(line);
            tasks.put(line, newNode);
            DefaultMutableTreeNode tasksNode = (DefaultMutableTreeNode) cogs.get(
                    debugModel.getCOGInfo(line.task.getCOG()).cog).getChildAt(1);
            treeModel.insertNodeInto(newNode, tasksNode, tasksNode.getChildCount());
            tree.scrollPathToVisible(new TreePath(newNode.getPath()));

        }

        @Override
        public void taskInfoRemoved(TaskInfo line) {
            // TODO Auto-generated method stub

        }

        @Override
        public void cogCreated(COGInfo info) {
            DefaultMutableTreeNode newNode = new DefaultMutableTreeNode(info);
            cogs.put(info.cog, newNode);
            this.add(newNode);
            treeModel.nodesWereInserted(this, new int[] { this.getChildCount() - 1 });

            treeModel.insertNodeInto(new DefaultMutableTreeNode(OBJECTS), newNode, 0);
            treeModel.insertNodeInto(new DefaultMutableTreeNode(TASKS), newNode, 1);
            addObjectNode(info.initialObject);

            info.cog.registerObjectCreationListener(SwingWrapperProxy.newInstance(this, ObjectCreationObserver.class));

        }

        @Override
        public void cogChanged(COGInfo info) {
        }

        @Override
        public void objectCreated(ObjectView o, boolean newCOG) {
            addObjectNode(o);
        }

        private void addObjectNode(ObjectView o) {
            DefaultMutableTreeNode objectsNode = (DefaultMutableTreeNode) cogs.get(o.getCOG()).getChildAt(0);
            DefaultMutableTreeNode newNode = new DefaultMutableTreeNode(o);
            treeModel.insertNodeInto(newNode, objectsNode, objectsNode.getChildCount());

            createFieldNodes(o, newNode);
        }

        private void createFieldNodes(ObjectView o, DefaultMutableTreeNode objectNode) {
            for (String fieldName : o.getFieldNames()) {
                DefaultMutableTreeNode fieldNode = new DefaultMutableTreeNode(fieldName);
                treeModel.insertNodeInto(fieldNode, objectNode, objectNode.getChildCount());

            }

        }

    }
}

class COGTable extends JPanel {
    private final DebugModel model;
    private final TableModel tableModel;
    private JTable table;
    private JScrollPane scrollPane;

    COGTable(DebugModel model) {
        this.model = model;
        this.tableModel = new TableModel();
        setLayout(new BorderLayout());
        table = new JTable(tableModel);
        scrollPane = new JScrollPane(table);
        add(scrollPane, BorderLayout.CENTER);
        setBorder(BorderFactory.createTitledBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5),
                "Concurrent Object Groups (COGs)"));
        setPreferredSize(new Dimension(500, 400));

        model.registerListener(tableModel);

    }

    class TableModel extends AbstractTableModel implements DebugModelListener {

        final List<COGInfo> rows = new ArrayList<COGInfo>();
        protected final String[] columnNames = new String[] { "COG ID", "Class", "Tasks" };
        protected final Class<?>[] columnClasses = new Class<?>[] { String.class, String.class, String.class };

        @Override
        public String getColumnName(int column) {
            return columnNames[column];
        }

        @Override
        public int getColumnCount() {
            return columnNames.length;
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return columnClasses[columnIndex];
        }

        @Override
        public Object getValueAt(int row, int col) {
            COGInfo line = rows.get(row);
            switch (col) {
            case 0:
                return line.cog.getID();
            case 1:
                return line.initialObject.getClassName();
            case 2: {
                boolean first = true;
                StringBuilder sb = new StringBuilder();
                for (TaskInfo t : line.tasks) {
                    if (first)
                        first = false;
                    else
                        sb.append(", ");
                    sb.append(t.task.getID());
                }
                return sb.toString();
            }
            }
            return "ERROR";
        }

        @Override
        public void taskInfoChanged(TaskInfo line) {
        }

        @Override
        public void taskInfoAdded(TaskInfo line) {
        }

        @Override
        public void taskInfoRemoved(TaskInfo line) {
        }

        @Override
        public void cogCreated(COGInfo info) {
            rows.add(info);
            fireTableRowsInserted(rows.size() - 1, rows.size() - 1);
        }

        @Override
        public void cogChanged(COGInfo info) {
            int row = rows.indexOf(info);
            fireTableRowsUpdated(row, row);
        }

    }

}

class DebugWindow implements DebugModelListener {
    final JFrame frame;
    final JTabbedPane tabs;
    // final JButton nextStepBtn;
    final TaskTable controls;
    final COGTree cogTree;
    final DebugModel model;

    final Map<String, SourceView> windows = new HashMap<String, SourceView>();

    // private COGTable cogTable;

    DebugWindow(final DebugModel model) {
        this.model = model;
        frame = new JFrame("ABS Graphical Debugger");
        frame.setLayout(new BorderLayout());
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        tabs = new JTabbedPane();

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        frame.add(splitPane, BorderLayout.CENTER);
        splitPane.setRightComponent(tabs);

        /*
         * nextStepBtn = new JButton("Step Arbitrary Task");
         * 
         * nextStepBtn.addActionListener(new ActionListener() {
         * 
         * @Override public void actionPerformed(ActionEvent arg0) {
         * model.stepRandom(); } });
         * 
         * frame.add(nextStepBtn,BorderLayout.NORTH);
         */

        /*
         * cogTable = new COGTable(model);
         * leftSide.add(cogTable,BorderLayout.NORTH);
         */
        cogTree = new COGTree(model);
        splitPane.setLeftComponent(cogTree);

        controls = new TaskTable(model);

        frame.add(new JSplitPane(JSplitPane.VERTICAL_SPLIT, splitPane, controls));
        splitPane.setSize(new Dimension(1000, 600));

        frame.setBounds(400, 100, 1000, 800);
        frame.setVisible(true);
        model.registerListener(this);
    }

    private SourceView getSourceView(String fileName) {
        SourceView c = windows.get(fileName);
        if (c == null) {
            c = new SourceView(model, fileName);
            windows.put(fileName, c);
            tabs.addTab(new File(fileName).getName(), c);
        }
        return c;
    }

    @Override
    public void taskInfoChanged(TaskInfo line) {
        if (line.currentFile != null) {
            SourceView view = getSourceView(line.currentFile);
            tabs.setSelectedComponent(view);
        }
    }

    @Override
    public void taskInfoAdded(TaskInfo line) {
    }

    @Override
    public void taskInfoRemoved(TaskInfo line) {
    }

    @Override
    public void cogCreated(COGInfo cog) {
    }

    @Override
    public void cogChanged(COGInfo info) {
    }
}

class SwingWrapperProxy implements InvocationHandler {
    final Object target;

    public SwingWrapperProxy(Object target) {
        this.target = target;
    }

    @Override
    public synchronized Object invoke(final Object proxy, final Method method, final Object[] args) throws Throwable {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                try {
                    method.invoke(target, args);
                } catch (IllegalArgumentException e) {
                    e.printStackTrace();
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                } catch (InvocationTargetException e) {
                    e.printStackTrace();
                }
            }
        });

        return null;
    }

    public static <V> V newInstance(V target, Class<?> interfce) {
        return (V) Proxy.newProxyInstance(SwingWrapperProxy.class.getClassLoader(), new Class[] { interfce },
                new SwingWrapperProxy(target));
    }
}
