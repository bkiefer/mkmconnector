package de.dfki.drz.mkm.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.UIManager;
import javax.swing.plaf.FontUIResource;
import javax.swing.table.AbstractTableModel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;

public class ResultWindow extends JFrame {
  private static final long serialVersionUID = 1L;

  private static ResultWindow singleton = null;

  public static int DEFAULT_FONT_SIZE = 18;

  private JTable table;

  private ResultModel rm;

  //public JTextField queryInput;

  //private List<Listener<String>> _listeners = new ArrayList<>();

  //private RecentDialog hisDialog;
  //public JLabel _statusbar;

  //public void register(Listener<String> l) {
  //  _listeners.add(l);
  //}

  private static class ResultModel extends AbstractTableModel {
    private static final long serialVersionUID = 1L;

    private String[] slots = {
      "id", "sender", "addressee", "text", "fromTime", "toTime",
      "intent", "frame",
      "einheit", "auftrag", "mittel", "weg", "ziel"
    };

    private Map<String, Integer> slotIndex = new HashMap<>();

    List<Map<String, String>> table;

    public ResultModel() {
      int i = 0;
      for (String c: slots) {
        slotIndex.put(c, i++);
      }
      table = new ArrayList<>();
    }

    @Override
    public int getRowCount() { return table.size(); }

    @Override
    public int getColumnCount() { return slots.length; }

    @Override
    public String getColumnName(int column) {
      return slots[column];
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
      return table.get(rowIndex).get(slots[columnIndex]);
    }

    @Override
    public boolean isCellEditable(int row, int col) { return false; }

    public int getColumnIndex(String col) {
      return slotIndex.get(col);
    }

    String toDate(long l) {
      SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMddHHmmss");
      return dateFormat.format(l);
    }

    public void addResult(JsonNode node) {
      Map<String, String> row = new HashMap<>();
      for (String key: slots) {
        String value = "";
        if (key.contains("Time")) {
          if (node.has(key)) {
            value = toDate(node.get(key).asLong());
          } else {
            value = "0";
          }
        } else {
          if (node.has(key)) {
            JsonNode o = node.get(key);
            if (o instanceof TextNode) {
              value = ((TextNode)o).asText();
            } else if (o.isArray()) {
              List<String> stringList = new ArrayList<>();
              for (JsonNode element : o) {
                stringList.add(element.asText());
              }
              StringBuilder sb = new StringBuilder();
              for (String s: stringList) {
                sb.append(s).append(' ');
              }
              value = sb.substring(0, sb.length() - 1);
            }
          } else {
            value = "";
          }
        }
        row.put(key, value);
      }
      table.add(row);
      fireTableDataChanged();
    }

    /*
    public void sortByColumn(final int col) {
      Collections.sort(_qr.table.rows, new Comparator<List<String>>() {
        @Override
        public int compare(List<String> o1, List<String> o2) {
          return o1.get(col).compareTo(o2.get(col));
        }});
      this.fireTableStructureChanged();
    }
    */
  };

  public static void interactive () {
    javax.swing.SwingUtilities.invokeLater(new Runnable() {
      @Override
      public void run() {
        final ResultWindow qw = getResultWindow();
      }
    });
  }

  public void addResult(JsonNode n) {
    rm.addResult(n);
  }

  private void addModel() {
    rm = new ResultModel();
    table.setModel(rm);
    // Auto scroll to last row if data changes
    table.addComponentListener(new ComponentAdapter() {
      @Override
      public void componentResized(ComponentEvent e) {
        table.scrollRectToVisible(
            table.getCellRect(table.getRowCount()-1, 0, true));
      }
    });
    rm.fireTableStructureChanged();
    if (rm.getRowCount() > 0 && rm.getColumnCount() > 0) {
      Rectangle charBounds =
          table.getFont().getStringBounds("MOgjQf",
              ((Graphics2D) this.getGraphics()).getFontRenderContext()).getBounds();
      table.setRowHeight(charBounds.height + 2);
    }
    table.repaint();
  }

  private static JButton getIconButton(String name) {
    URL url = ResultWindow.class.getClassLoader()
        .getResource("icons/" + name + ".png");
    Icon icon = new ImageIcon(url);
    JButton button = new JButton(icon);
    button.setPreferredSize(new Dimension(32,32));
    button.setBorderPainted(false);
    button.setBorder(null);
    //button.setFocusable(false);
    button.setMargin(new Insets(0, 0, 0, 0));
    button.setContentAreaFilled(false);
    return button;
  }


  private JTable getResultTable() {
    table = new JTable();
    addModel();
    // listener to sort columns
    /*
    table.getTableHeader().addMouseListener(new MouseAdapter() {
      @Override
      public void mouseClicked(MouseEvent e) {
        int col = table.columnAtPoint(e.getPoint());
        // String name = table.getColumnName(col);
        QueryResultModel qrm = (QueryResultModel)table.getModel();
        //qrm.sortByColumn(col);
      }
    });

    table.addMouseListener(new MouseAdapter() {
      @Override
      public void mouseClicked(MouseEvent event) {
        int eventX = event.getX();
        int eventY = event.getY();
        if (event.getButton() == MouseEvent.BUTTON3) {
          JPopupMenu pop = new JPopupMenu();
          // save to file menu item
          JMenuItem saveToFile = new JMenuItem("Save to File");
          saveToFile.addActionListener(
              (e) -> QueryWindow.this.saveTableToFile(
                  ((QueryResultModel)table.getModel())));
          pop.add(saveToFile);
          // save to graph item
          JMenuItem saveToGraph = new JMenuItem("Save to Graph");
          saveToGraph.addActionListener(
              (e) -> QueryWindow.this.saveTableToGraph(
                  ((QueryResultModel)table.getModel())));
          pop.add(saveToGraph);
          saveToGraph.setEnabled(
              3 == ((QueryResultModel)table.getModel()).getColumnCount());
          pop.show(table, eventX, eventY);
        }
      }
    });
    */
    return table;
  }


  private static void setDefaultFont(int size) {
    FontUIResource font = new FontUIResource("'DejaVu Sans Mono", Font.PLAIN, size);
    for (Map.Entry<Object, Object> e :
      UIManager.getLookAndFeelDefaults().entrySet()) {
      try {
        String key = (String) e.getKey();
        if (key.endsWith(".font")) {
          UIManager.put(key, font);
        }
        // If you want to list them all.
        // System.out.println(e.getKey() + " " + e.getValue());
      } catch (ClassCastException ex) { /* so what. */ }
    }
  }


  private ResultWindow() {
    super("MKM interactive");
    // use native windowing system to position new frames
    this.setLocationByPlatform(true);
    // set preferred size
    this.setPreferredSize(new Dimension(800, 300));
    setDefaultFont(DEFAULT_FONT_SIZE);

    // set handler for closing operations
    setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    // this.addWindowListener(new Terminator());
    // create content panel and add it to the frame
    JPanel contentPane = new JPanel(new BorderLayout());
    this.setContentPane(contentPane);

    table = getResultTable();
    JScrollPane jsp = new JScrollPane(table);
    contentPane.add(jsp, BorderLayout.CENTER);

    // display the frame
    this.pack();
    this.setLocationRelativeTo(null);
    this.setVisible(true);
  }


  public static ResultWindow getResultWindow() {
    if (singleton == null)
      singleton = new ResultWindow();
    return singleton;
  }

}
