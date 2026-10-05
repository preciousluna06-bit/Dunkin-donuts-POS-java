import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.stream.Stream;

/** Dunkin' style Point of Sale. Menu and pictures come from the "assets" folder. */
public class DunkinPOS extends JFrame {

    // ---------- Dunkin' palette ----------
    static final Color ORANGE = new Color(0xFF671F);
    static final Color PINK   = new Color(0xDA1884);
    static final Color BROWN  = new Color(0x502314);
    static final Color CREAM  = new Color(0xFFF6EA);
    static final Color WHITE  = Color.WHITE;
    static final Font  F_BOLD = new Font("Segoe UI", Font.BOLD, 15);
    static final Font  F_BIG  = new Font("Segoe UI", Font.BOLD, 26);

    static final Path ASSETS = Paths.get("assets", "ass-ets");
    static final Path CONFIG_DIR = Paths.get("assets");
    static final Path RECEIPTS = Paths.get("receipts");

    // ---------- data ----------
    record Item(String category, String name, double price, Path image) {}
    static class Line { Item item; int qty; Line(Item i, int q) { item = i; qty = q; } double total() { return item.price() * qty; } }

    final Properties cfg = new Properties();
    final LinkedHashMap<String, List<Item>> menu = new LinkedHashMap<>();
    final List<Line> cart = new ArrayList<>();
    final Map<Path, BufferedImage> imgCache = new HashMap<>();

    String cur = "₱";
    String cashier = "-";
    List<String> cashiers = new ArrayList<>();
    JLabel lblCashier;
    double vatRate = 12;

    // ---------- UI ----------
    JPanel itemGrid;
    JPanel tabBar;
    DefaultTableModel tableModel;
    JTable table;
    JLabel lblSub, lblVat, lblTotal, lblChange;
    JTextField txtCash, txtSearch;
    JLabel lblTitle;
    String activeCategory;
    final Map<String, JButton> tabButtons = new HashMap<>();

    public static void main(String[] args) {
        try { UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName()); } catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> new DunkinPOS().setVisible(true));
    }

    DunkinPOS() {
        super("Dunkin' POS");
        loadConfig();
        loadMenu();
        buildUI();
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1280, 800);
        setMinimumSize(new Dimension(1050, 650));
        setLocationRelativeTo(null);
        if (!menu.isEmpty()) selectCategory(menu.keySet().iterator().next());
        refreshTotals();
        SwingUtilities.invokeLater(this::chooseCashier);
    }

    // ================= loading =================
    void loadConfig() {
        Path p = CONFIG_DIR.resolve("config.properties");
        if (Files.exists(p)) {
            try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) { cfg.load(r); } catch (IOException ignored) {}
        }
        cur = cfg.getProperty("currency", cur);
        for (String c : cfg.getProperty("cashiers", cfg.getProperty("cashier", "Cashier")).split(",")) if (!c.trim().isEmpty()) cashiers.add(c.trim());
        cashier = cashiers.isEmpty() ? "Cashier" : cashiers.get(0);
        try { vatRate = Double.parseDouble(cfg.getProperty("vat.percent", "12")); } catch (NumberFormatException ignored) {}
    }

    void loadMenu() {
        double defPrice = 50;
        try { defPrice = Double.parseDouble(cfg.getProperty("default.price", "50")); } catch (NumberFormatException ignored) {}
        Set<Path> used = new HashSet<>();
        Map<String, String> labels = new HashMap<>();
        Path csv = ASSETS.resolve("menu.csv");
        if (Files.exists(csv)) {
            try {
                for (String line : Files.readAllLines(csv, StandardCharsets.UTF_8)) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    String[] f = line.split(",");
                    if (f.length < 3) continue;
                    String folder = f[0].trim(), cat = folder, name = f[1].trim();
                    int eqi = folder.indexOf('=');
                    if (eqi > 0) { cat = folder.substring(eqi + 1).trim(); folder = folder.substring(0, eqi).trim(); }
                    labels.put(folder, cat);
                    double price;
                    try { price = Double.parseDouble(f[2].trim()); } catch (NumberFormatException e) { continue; }
                    Path img = null;
                    if (f.length > 3 && !f[3].trim().isEmpty()) {
                        Path c = ASSETS.resolve(folder).resolve(f[3].trim());
                        if (Files.exists(c)) { img = c; used.add(c.toAbsolutePath()); }
                    }
                    menu.computeIfAbsent(cat, k -> new ArrayList<>()).add(new Item(cat, name, price, img));
                }
            } catch (IOException ignored) {}
        }
        // auto-discover pictures dropped in category folders
        if (Files.isDirectory(ASSETS)) {
            try (Stream<Path> dirs = Files.list(ASSETS)) {
                for (Path d : (Iterable<Path>) dirs.filter(Files::isDirectory).sorted()::iterator) {
                    String cat = labels.getOrDefault(d.getFileName().toString(), d.getFileName().toString());
                    try (Stream<Path> files = Files.list(d)) {
                        for (Path f : (Iterable<Path>) files.sorted()::iterator) {
                            String fn = f.getFileName().toString().toLowerCase();
                            if (!(fn.endsWith(".png") || fn.endsWith(".jpg") || fn.endsWith(".jpeg") || fn.endsWith(".gif"))) continue;
                            if (used.contains(f.toAbsolutePath())) continue;
                            String base = f.getFileName().toString().replaceAll("\\.[^.]+$", "").replaceAll("[_\\-]+", " ").trim();
                            menu.computeIfAbsent(cat, k -> new ArrayList<>()).add(new Item(cat, titleCase(base), defPrice, f));
                        }
                    }
                }
            } catch (IOException ignored) {}
        }
    }

    static String titleCase(String s) {
        StringBuilder sb = new StringBuilder();
        for (String w : s.split(" ")) if (!w.isEmpty()) sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');
        return sb.toString().trim();
    }

    BufferedImage image(Path p) {
        if (p == null) return null;
        return imgCache.computeIfAbsent(p, k -> { try { return ImageIO.read(k.toFile()); } catch (Exception e) { return null; } });
    }

    final Map<String, BufferedImage> thumbs = new HashMap<>();
    /** High-quality downscale (stepwise halving), cached per size. */
    BufferedImage thumb(Path p, int w, int h) {
        BufferedImage src = image(p);
        if (src == null) return null;
        return thumbs.computeIfAbsent(p + "@" + w + "x" + h, k -> {
            double s = Math.min((double) w / src.getWidth(), (double) h / src.getHeight());
            int tw = Math.max(1, (int) (src.getWidth() * s)), th = Math.max(1, (int) (src.getHeight() * s));
            BufferedImage cur = src;
            int cw = src.getWidth(), ch = src.getHeight();
            while (cw / 2 >= tw && ch / 2 >= th) { cw /= 2; ch /= 2; cur = scaleTo(cur, cw, ch); }
            return scaleTo(cur, tw, th);
        });
    }
    static BufferedImage scaleTo(BufferedImage s, int w, int h) {
        BufferedImage o = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = o.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(s, 0, 0, w, h, null); g.dispose();
        return o;
    }

    // ================= UI =================
    void buildUI() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(CREAM);
        setContentPane(root);

        root.add(buildHeader(), BorderLayout.NORTH);

        // left: category tabs + product grid
        JPanel left = new JPanel(new BorderLayout());
        left.setOpaque(false);
        int tabCols = (int) Math.ceil(menu.size() / 2.0);
        tabBar = new JPanel(new GridLayout(0, Math.max(1, tabCols), 8, 8));
        tabBar.setBackground(CREAM);
        tabBar.setBorder(new EmptyBorder(0, 12, 0, 12));
        for (String cat : menu.keySet()) {
            JButton b = pill(cat);
            b.addActionListener(e -> selectCategory(cat));
            tabButtons.put(cat, b);
            tabBar.add(b);
        }
        txtSearch = new JTextField();
        txtSearch.setFont(new Font("Segoe UI", Font.PLAIN, 16));
        txtSearch.putClientProperty("JTextField.placeholderText", "Search");
        txtSearch.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(0xFFD0B0), 2), new EmptyBorder(6, 12, 6, 12)));
        txtSearch.setToolTipText("Search all items");
        txtSearch.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { applySearch(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { applySearch(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { applySearch(); }
        });
        JLabel sl = new JLabel("Search:");
        sl.setFont(F_BOLD); sl.setForeground(BROWN);
        JPanel searchRow = new JPanel(new BorderLayout(10, 0));
        searchRow.setOpaque(false);
        searchRow.setBorder(new EmptyBorder(10, 12, 0, 12));
        searchRow.add(sl, BorderLayout.WEST); searchRow.add(txtSearch, BorderLayout.CENTER);
        JPanel top = new JPanel(new BorderLayout(0, 10));
        top.setBackground(CREAM);
        top.add(searchRow, BorderLayout.NORTH); top.add(tabBar, BorderLayout.CENTER);
        top.setBorder(new EmptyBorder(0, 0, 4, 0));
        left.add(top, BorderLayout.NORTH);

        itemGrid = new JPanel(new GridLayout(0, 4, 14, 14));
        itemGrid.setOpaque(false);
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setOpaque(false);
        wrap.setBorder(new EmptyBorder(6, 12, 12, 12));
        wrap.add(itemGrid, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(wrap);
        sp.setBorder(null);
        sp.getViewport().setBackground(CREAM);
        sp.getVerticalScrollBar().setUnitIncrement(24);
        left.add(sp, BorderLayout.CENTER);
        root.add(left, BorderLayout.CENTER);

        root.add(buildOrderPanel(), BorderLayout.EAST);
    }

    JPanel buildHeader() {
        JPanel h = new JPanel(new BorderLayout());
        h.setBackground(WHITE);
        h.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 5, 0, ORANGE), new EmptyBorder(8, 18, 8, 18)));

        Path logo = CONFIG_DIR.resolve("logo.png");
        JComponent brand;
        BufferedImage li = Files.exists(logo) ? image(logo) : null;
        if (li != null) {
            int hh = 52, ww = li.getWidth() * hh / li.getHeight();
            brand = new JLabel(new ImageIcon(li.getScaledInstance(ww, hh, Image.SCALE_SMOOTH)));
        } else {
            JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            p.setOpaque(false);
            JLabel a = new JLabel("DUNKIN'"); a.setForeground(ORANGE);
            JLabel b = new JLabel("DONUTS"); b.setForeground(PINK);
            Font f = new Font("Arial Rounded MT Bold", Font.BOLD, 34);
            if (!f.getFamily().startsWith("Arial Rounded")) f = new Font("Segoe UI Black", Font.BOLD, 34);
            a.setFont(f); b.setFont(f);
            p.add(a); p.add(b);
            brand = p;
        }
        h.add(brand, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 8));
        right.setOpaque(false);
        lblCashier = new JLabel();
        lblCashier.setFont(F_BOLD); lblCashier.setForeground(BROWN);
        updateCashierLabel();
        JButton sw = flat("Switch Cashier", PINK);
        sw.addActionListener(e -> chooseCashier());
        right.add(lblCashier); right.add(sw);
        h.add(right, BorderLayout.EAST);
        return h;
    }

    JPanel buildOrderPanel() {
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.setPreferredSize(new Dimension(410, 0));
        p.setBackground(WHITE);
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 3, 0, 0, PINK), new EmptyBorder(12, 14, 12, 14)));

        JLabel title = lblTitle = new JLabel("CURRENT ORDER");
        title.setFont(new Font("Segoe UI", Font.BOLD, 20));
        title.setForeground(PINK);
        p.add(title, BorderLayout.NORTH);

        tableModel = new DefaultTableModel(new String[]{"Qty", "Item", "Amount"}, 0) {
            public boolean isCellEditable(int r, int c) { return false; }
        };
        table = new JTable(tableModel);
        table.setRowHeight(30);
        table.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        table.setSelectionBackground(new Color(0xFFD9C2));
        table.setSelectionForeground(BROWN);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 1));
        table.getTableHeader().setReorderingAllowed(false);
        table.getTableHeader().setBackground(ORANGE);
        table.getTableHeader().setForeground(WHITE);
        table.getTableHeader().setFont(F_BOLD);
        table.getColumnModel().getColumn(0).setMaxWidth(48);
        table.getColumnModel().getColumn(2).setPreferredWidth(90);
        DefaultTableCellRenderer right = new DefaultTableCellRenderer();
        right.setHorizontalAlignment(SwingConstants.RIGHT);
        table.getColumnModel().getColumn(2).setCellRenderer(right);
        DefaultTableCellRenderer center = new DefaultTableCellRenderer();
        center.setHorizontalAlignment(SwingConstants.CENTER);
        table.getColumnModel().getColumn(0).setCellRenderer(center);
        table.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2) changeQty(); }
        });
        JScrollPane ts = new JScrollPane(table);
        ts.setBorder(BorderFactory.createLineBorder(new Color(0xEEDDCC)));
        ts.getViewport().setBackground(WHITE);

        JPanel rowBtns = new JPanel(new GridLayout(1, 5, 6, 0));
        rowBtns.setOpaque(false);
        JButton bm = flat("−", BROWN), bp = flat("+", BROWN);
        bm.setFont(new Font("Segoe UI", Font.BOLD, 20)); bp.setFont(bm.getFont());
        bm.addActionListener(e -> bump(-1)); bp.addActionListener(e -> bump(1));
        rowBtns.add(bm); rowBtns.add(bp);
        JButton bq = flat("Qty", BROWN), br = flat("Del", BROWN), bc = flat("Clear", BROWN);
        bq.addActionListener(e -> changeQty());
        br.addActionListener(e -> removeSelected());
        bc.addActionListener(e -> { if (!cart.isEmpty() && confirm("Clear the whole order?")) clearOrder(); });
        rowBtns.add(bq); rowBtns.add(br); rowBtns.add(bc);

        JPanel center2 = new JPanel(new BorderLayout(0, 6));
        center2.setOpaque(false);
        center2.add(ts, BorderLayout.CENTER);
        center2.add(rowBtns, BorderLayout.SOUTH);
        p.add(center2, BorderLayout.CENTER);

        // bottom: totals + payment
        JPanel bottom = new JPanel();
        bottom.setOpaque(false);
        bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));

        lblSub = new JLabel(); lblVat = new JLabel(); lblTotal = new JLabel();
        bottom.add(kv("Subtotal", lblSub, new Font("Segoe UI", Font.PLAIN, 15), BROWN));
        bottom.add(kv("VAT (" + trim(vatRate) + "%) incl.", lblVat, new Font("Segoe UI", Font.PLAIN, 15), BROWN));
        bottom.add(Box.createVerticalStrut(4));
        bottom.add(kv("TOTAL DUE", lblTotal, F_BIG, ORANGE));
        bottom.add(Box.createVerticalStrut(10));

        JLabel cl = new JLabel("Cash received");
        cl.setFont(F_BOLD); cl.setForeground(BROWN); cl.setAlignmentX(0);
        bottom.add(cl);
        txtCash = new JTextField();
        txtCash.setFont(new Font("Segoe UI", Font.BOLD, 24));
        txtCash.setHorizontalAlignment(SwingConstants.RIGHT);
        txtCash.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(PINK, 2), new EmptyBorder(4, 8, 4, 8)));
        txtCash.setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
        txtCash.setAlignmentX(0);
        txtCash.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { updateChange(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { updateChange(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { updateChange(); }
        });
        txtCash.addActionListener(e -> pay());
        bottom.add(txtCash);
        bottom.add(Box.createVerticalStrut(6));

        JPanel quick = new JPanel(new GridLayout(1, 5, 5, 0));
        quick.setOpaque(false);
        quick.setAlignmentX(0);
        quick.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        JButton exact = flat("Exact", ORANGE);
        exact.addActionListener(e -> { if (total() > 0) txtCash.setText(fmtPlain(total())); });
        quick.add(exact);
        for (int amt : new int[]{100, 200, 500, 1000}) {
            JButton b = flat(String.valueOf(amt), ORANGE);
            b.addActionListener(e -> txtCash.setText(String.valueOf(amt)));
            quick.add(b);
        }
        bottom.add(quick);
        bottom.add(Box.createVerticalStrut(8));

        lblChange = new JLabel("Change: " + money(0));
        lblChange.setFont(new Font("Segoe UI", Font.BOLD, 20));
        lblChange.setForeground(BROWN);
        lblChange.setAlignmentX(0);
        bottom.add(lblChange);
        bottom.add(Box.createVerticalStrut(8));

        JButton payBtn = flat("PAY  &  PRINT RECEIPT", PINK);
        payBtn.setFont(new Font("Segoe UI", Font.BOLD, 18));
        payBtn.setForeground(WHITE);
        payBtn.setAlignmentX(0);
        payBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 54));
        payBtn.setPreferredSize(new Dimension(100, 54));
        payBtn.addActionListener(e -> pay());
        bottom.add(payBtn);

        p.add(bottom, BorderLayout.SOUTH);
        return p;
    }

    JPanel kv(String k, JLabel v, Font f, Color c) {
        JPanel r = new JPanel(new BorderLayout());
        r.setOpaque(false);
        r.setAlignmentX(0);
        r.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        JLabel kl = new JLabel(k);
        kl.setFont(f); kl.setForeground(c); v.setFont(f); v.setForeground(c);
        r.add(kl, BorderLayout.WEST); r.add(v, BorderLayout.EAST);
        return r;
    }

    JButton pill(String text) {
        JButton b = new JButton(text) {
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                boolean sel = Boolean.TRUE.equals(getClientProperty("sel"));
                g2.setColor(sel ? PINK : WHITE);
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, getHeight(), getHeight());
                g2.setColor(sel ? PINK : ORANGE);
                g2.setStroke(new BasicStroke(2f));
                g2.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, getHeight(), getHeight());
                g2.dispose();
                setForeground(sel ? WHITE : ORANGE);
                super.paintComponent(g);
            }
        };
        b.setFont(new Font("Segoe UI", Font.BOLD, 16));
        b.setContentAreaFilled(false); b.setBorderPainted(false); b.setFocusPainted(false);
        b.setOpaque(false);
        b.setBorder(new EmptyBorder(8, 12, 8, 12));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }

    JButton flat(String text, Color bg) {
        JButton b = new JButton(text) {
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color c = getBackground();
                if (getModel().isPressed()) c = c.darker(); else if (getModel().isRollover()) c = c.brighter();
                g2.setColor(c);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 14, 14);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        b.setBackground(bg); b.setForeground(WHITE);
        b.setFont(new Font("Segoe UI", Font.BOLD, 14));
        b.setContentAreaFilled(false); b.setBorderPainted(false); b.setFocusPainted(false);
        b.setOpaque(false);
        b.setRolloverEnabled(true);
        b.setBorder(new EmptyBorder(6, 10, 6, 10));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }

    void updateCashierLabel() {
        lblCashier.setText("Cashier: " + cashier + "   |   " + cfg.getProperty("terminal", "POS-01"));
    }

    void chooseCashier() {
        if (cashiers.isEmpty()) return;
        JComboBox<String> box = new JComboBox<>(cashiers.toArray(new String[0]));
        box.setSelectedItem(cashier);
        box.setFont(new Font("Segoe UI", Font.BOLD, 18));
        JPanel p = new JPanel(new BorderLayout(0, 8));
        JLabel l = new JLabel("Who is the cashier?");
        l.setFont(F_BOLD);
        p.add(l, BorderLayout.NORTH); p.add(box, BorderLayout.CENTER);
        if (JOptionPane.showConfirmDialog(this, p, "Cashier", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            cashier = (String) box.getSelectedItem();
            updateCashierLabel();
        }
    }

    // ================= menu grid =================
    void selectCategory(String cat) {
        activeCategory = cat;
        if (txtSearch != null && !txtSearch.getText().isEmpty()) { txtSearch.setText(""); return; } // listener re-enters
        tabButtons.forEach((k, b) -> { b.putClientProperty("sel", k.equals(cat)); b.repaint(); });
        showItems(menu.getOrDefault(cat, List.of()));
    }

    void applySearch() {
        String q = txtSearch.getText().trim().toLowerCase();
        if (q.isEmpty()) { if (activeCategory != null) selectCategory(activeCategory); return; }
        tabButtons.values().forEach(b -> { b.putClientProperty("sel", false); b.repaint(); });
        List<Item> hits = new ArrayList<>();
        for (List<Item> l : menu.values()) for (Item it : l) if (it.name().toLowerCase().contains(q)) hits.add(it);
        showItems(hits);
    }

    void showItems(List<Item> items) {
        itemGrid.removeAll();
        for (Item it : items) itemGrid.add(new Card(it));
        for (int i = items.size(); i < 4; i++) { JPanel f = new JPanel(); f.setOpaque(false); f.setPreferredSize(new Dimension(190, 250)); itemGrid.add(f); }
        itemGrid.revalidate(); itemGrid.repaint();
    }

    /** +1 / -1 on the selected order line (removes the line at zero). */
    void bump(int d) {
        int r = table.getSelectedRow();
        if (r < 0) { if (cart.isEmpty()) return; r = cart.size() - 1; }
        Line l = cart.get(r);
        l.qty += d;
        if (l.qty <= 0) { cart.remove(r); refreshCart(); if (!cart.isEmpty()) { int n = Math.min(r, cart.size() - 1); table.setRowSelectionInterval(n, n); } return; }
        refreshCart();
        table.setRowSelectionInterval(r, r);
    }

    class Card extends JPanel {
        final Item item; boolean hover;
        Card(Item item) {
            this.item = item;
            setOpaque(false);
            setPreferredSize(new Dimension(190, 250));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                public void mouseExited(MouseEvent e) { hover = false; repaint(); }
                public void mouseClicked(MouseEvent e) { addItem(item); }
            });
        }
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            int w = getWidth(), h = getHeight();
            g2.setColor(new Color(0, 0, 0, 25));
            g2.fill(new RoundRectangle2D.Float(3, 5, w - 6, h - 6, 22, 22));
            g2.setColor(WHITE);
            g2.fill(new RoundRectangle2D.Float(0, 0, w - 4, h - 6, 22, 22));
            g2.setColor(hover ? PINK : new Color(0xFFD0B0));
            g2.setStroke(new BasicStroke(hover ? 3f : 2f));
            g2.draw(new RoundRectangle2D.Float(1, 1, w - 6, h - 8, 22, 22));

            // picture area
            int pw = w - 40, ph = 140, px = 18, py = 12;
            BufferedImage img = thumb(item.image(), pw, ph);
            if (img != null) {
                g2.drawImage(img, px + (pw - img.getWidth()) / 2, py + (ph - img.getHeight()) / 2, null);
            } else {
                drawPlaceholder(g2, px + pw / 2, py + ph / 2, 52, item.category());
            }

            // name (wrapped to 2 lines)
            g2.setColor(BROWN);
            g2.setFont(new Font("Segoe UI", Font.BOLD, 15));
            FontMetrics fm = g2.getFontMetrics();
            List<String> lines = wrap(item.name(), fm, w - 30, 2);
            int y = 182;
            for (String l : lines) { g2.drawString(l, (w - 4 - fm.stringWidth(l)) / 2, y); y += fm.getHeight() - 1; }

            // price tag
            String pr = money(item.price());
            g2.setFont(new Font("Segoe UI", Font.BOLD, 15));
            fm = g2.getFontMetrics();
            int tw = fm.stringWidth(pr) + 28;
            g2.setColor(ORANGE);
            g2.fillRoundRect((w - 4 - tw) / 2, h - 40, tw, 26, 26, 26);
            g2.setColor(WHITE);
            g2.drawString(pr, (w - 4 - fm.stringWidth(pr)) / 2, h - 22);
            g2.dispose();
        }
    }

    /** Pink-frosted donut shown when no picture was supplied yet. */
    static void drawPlaceholder(Graphics2D g, int cx, int cy, int r, String cat) {
        if (cat.startsWith("Add")) {
            g.setColor(PINK); g.fill(new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
            g.setColor(WHITE); g.fillRoundRect(cx - r / 2, cy - 5, r, 10, 6, 6); g.fillRoundRect(cx - 5, cy - r / 2, 10, r, 6, 6);
            return;
        }
        if (cat.startsWith("Boxes")) {
            g.setColor(new Color(0xF06EAA)); g.fillRoundRect(cx - r, cy - r / 2, 2 * r, r + r / 2, 10, 10);
            g.setColor(ORANGE); g.fillRoundRect(cx - r - 4, cy - r / 2 - 10, 2 * r + 8, 18, 8, 8);
            g.setColor(new Color(0xD9A066)); g.fillOval(cx - r / 2, cy - 2, r / 2, r / 2); g.fillOval(cx + 4, cy + 6, r / 2, r / 2);
            return;
        }
        g.setColor(new Color(0xD9A066));
        g.fill(new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
        g.setColor(new Color(0xF06EAA));
        g.fill(new Ellipse2D.Double(cx - r + 6, cy - r + 4, 2 * r - 12, 2 * r - 12));
        Random rnd = new Random(7);
        Color[] sp = {WHITE, new Color(0xFFD23F), new Color(0x4FC3F7), new Color(0x8BC34A)};
        for (int i = 0; i < 14; i++) {
            double a = rnd.nextDouble() * Math.PI * 2, d = (r * 0.42) + rnd.nextDouble() * (r * 0.4);
            int sx = (int) (cx + Math.cos(a) * d), sy = (int) (cy + Math.sin(a) * d);
            g.setColor(sp[i % sp.length]);
            g.fillRoundRect(sx - 4, sy - 1, 8, 3, 3, 3);
        }
        g.setColor(new Color(0xFFF6EA));
        g.fill(new Ellipse2D.Double(cx - r * 0.3, cy - r * 0.3, r * 0.6, r * 0.6));
    }

    static List<String> wrap(String s, FontMetrics fm, int maxW, int maxLines) {
        List<String> out = new ArrayList<>();
        String cur = "";
        for (String w : s.split(" ")) {
            String t = cur.isEmpty() ? w : cur + " " + w;
            if (fm.stringWidth(t) <= maxW || cur.isEmpty()) cur = t;
            else { out.add(cur); cur = w; }
        }
        if (!cur.isEmpty()) out.add(cur);
        if (out.size() > maxLines) {
            List<String> cut = new ArrayList<>(out.subList(0, maxLines));
            cut.set(maxLines - 1, cut.get(maxLines - 1) + "...");
            return cut;
        }
        return out;
    }

    // ================= cart logic =================
    void addItem(Item it) {
        Integer q = askQty(it, 1, "Add to order");
        if (q == null) return;
        for (Line l : cart) if (l.item == it) { l.qty += q; refreshCart(); return; }
        cart.add(new Line(it, q));
        refreshCart();
    }

    void changeQty() {
        int r = table.getSelectedRow();
        if (r < 0) { info("Select an item in the order first."); return; }
        Line l = cart.get(r);
        Integer q = askQty(l.item, l.qty, "Change quantity");
        if (q == null) return;
        l.qty = q;
        refreshCart();
        table.setRowSelectionInterval(r, r);
    }

    void removeSelected() {
        int r = table.getSelectedRow();
        if (r < 0) { info("Select an item in the order first."); return; }
        cart.remove(r);
        refreshCart();
    }

    void clearOrder() { cart.clear(); txtCash.setText(""); refreshCart(); }

    /** Quantity pop-up. Returns null if cancelled. */
    Integer askQty(Item it, int initial, String okText) {
        JDialog d = new JDialog(this, it.name(), true);
        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.setBackground(CREAM);
        p.setBorder(new EmptyBorder(16, 22, 16, 22));

        JLabel name = new JLabel(it.name(), SwingConstants.CENTER);
        name.setFont(new Font("Segoe UI", Font.BOLD, 20)); name.setForeground(PINK);
        JLabel price = new JLabel(money(it.price()) + " each", SwingConstants.CENTER);
        price.setFont(F_BOLD); price.setForeground(BROWN);
        JPanel top = new JPanel(new GridLayout(2, 1)); top.setOpaque(false);
        top.add(name); top.add(price);
        p.add(top, BorderLayout.NORTH);

        SpinnerNumberModel sm = new SpinnerNumberModel(initial, 1, 999, 1);
        JSpinner spin = new JSpinner(sm);
        spin.setFont(new Font("Segoe UI", Font.BOLD, 30));
        ((JSpinner.DefaultEditor) spin.getEditor()).getTextField().setHorizontalAlignment(SwingConstants.CENTER);
        spin.setPreferredSize(new Dimension(110, 54));
        JButton minus = flat("−", ORANGE), plus = flat("+", ORANGE);
        for (JButton b : new JButton[]{minus, plus}) { b.setFont(new Font("Segoe UI", Font.BOLD, 28)); b.setPreferredSize(new Dimension(58, 54)); }
        minus.addActionListener(e -> sm.setValue(Math.max(1, (int) sm.getNumber().intValue() - 1)));
        plus.addActionListener(e -> sm.setValue(Math.min(999, sm.getNumber().intValue() + 1)));
        JPanel mid = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 6)); mid.setOpaque(false);
        mid.add(minus); mid.add(spin); mid.add(plus);

        JLabel sub = new JLabel("", SwingConstants.CENTER);
        sub.setFont(new Font("Segoe UI", Font.BOLD, 18)); sub.setForeground(BROWN);
        Runnable upd = () -> sub.setText("Partial total: " + money(it.price() * sm.getNumber().intValue()));
        sm.addChangeListener(e -> upd.run()); upd.run();
        JPanel center = new JPanel(new BorderLayout()); center.setOpaque(false);
        center.add(mid, BorderLayout.CENTER); center.add(sub, BorderLayout.SOUTH);
        p.add(center, BorderLayout.CENTER);

        final Integer[] result = {null};
        JButton ok = flat(okText, PINK), cancel = flat("Cancel", BROWN);
        ok.setFont(new Font("Segoe UI", Font.BOLD, 16)); cancel.setFont(ok.getFont());
        ok.addActionListener(e -> {
            try { spin.commitEdit(); } catch (java.text.ParseException ignored) {}
            result[0] = sm.getNumber().intValue(); d.dispose();
        });
        cancel.addActionListener(e -> d.dispose());
        JPanel btns = new JPanel(new GridLayout(1, 2, 10, 0)); btns.setOpaque(false);
        btns.setPreferredSize(new Dimension(100, 44));
        btns.add(cancel); btns.add(ok);
        p.add(btns, BorderLayout.SOUTH);

        d.setContentPane(p);
        d.getRootPane().setDefaultButton(ok);
        d.pack();
        d.setSize(Math.max(d.getWidth(), 360), d.getHeight());
        d.setLocationRelativeTo(this);
        d.setVisible(true);
        return result[0];
    }

    void refreshCart() {
        int n = 0; for (Line l : cart) n += l.qty;
        lblTitle.setText(n == 0 ? "CURRENT ORDER" : "CURRENT ORDER  •  " + n + (n == 1 ? " item" : " items"));
        tableModel.setRowCount(0);
        for (Line l : cart) tableModel.addRow(new Object[]{l.qty, l.item.name(), money(l.total())});
        refreshTotals();
    }

    double total() { double t = 0; for (Line l : cart) t += l.total(); return round2(t); }
    double vat() { return round2(total() / (1 + vatRate / 100.0) * (vatRate / 100.0)); }
    static double round2(double v) { return Math.round(v * 100.0) / 100.0; }

    void refreshTotals() {
        lblSub.setText(money(total()));
        lblVat.setText(money(vat()));
        lblTotal.setText(money(total()));
        updateChange();
    }

    void updateChange() {
        if (lblChange == null) return;
        Double cash = parseCash();
        if (cash == null || cash < total()) { lblChange.setText("Change: " + money(0)); lblChange.setForeground(BROWN); }
        else { lblChange.setText("Change: " + money(cash - total())); lblChange.setForeground(new Color(0x2E7D32)); }
    }

    Double parseCash() {
        String s = txtCash.getText().trim().replace(",", "").replace(cur, "").trim();
        if (s.isEmpty()) return null;
        try { double v = Double.parseDouble(s); return Double.isFinite(v) ? v : null; } catch (NumberFormatException e) { return null; }
    }

    // ================= payment =================
    void pay() {
        if (cart.isEmpty()) { warn("The order is empty. Tap an item to add it."); return; }
        String raw = txtCash.getText().trim();
        if (raw.isEmpty()) { warn("Please enter the cash received."); txtCash.requestFocus(); return; }
        Double cash = parseCash();
        if (cash == null) { warn("Invalid amount. Enter numbers only (e.g. 500 or 500.00)."); txtCash.selectAll(); txtCash.requestFocus(); return; }
        if (cash <= 0) { warn("Cash received must be greater than zero."); return; }
        if (round2(cash) < total()) {
            warn("Insufficient payment.\nTotal due: " + money(total()) + "\nShort by: " + money(total() - cash));
            txtCash.selectAll(); txtCash.requestFocus(); return;
        }
        cash = round2(cash);
        double change = round2(cash - total());
        int no = nextReceiptNo();
        String receipt = buildReceipt(no, cash, change);
        try {
            Files.createDirectories(RECEIPTS);
            Files.writeString(RECEIPTS.resolve(String.format("%08d.txt", no)), receipt, StandardCharsets.UTF_8);
        } catch (IOException ignored) {}
        showReceipt(receipt);
        clearOrder();
    }

    int nextReceiptNo() {
        int max = 0;
        if (Files.isDirectory(RECEIPTS)) {
            try (Stream<Path> s = Files.list(RECEIPTS)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    String n = p.getFileName().toString().replaceAll("\\.txt$", "");
                    try { max = Math.max(max, Integer.parseInt(n)); } catch (NumberFormatException ignored) {}
                }
            } catch (IOException ignored) {}
        }
        return max + 1;
    }

    static final int W = 37;

    String buildReceipt(int no, double cash, double change) {
        StringBuilder sb = new StringBuilder();
        String eq = "=".repeat(W), dash = "-".repeat(W);
        LocalDateTime now = LocalDateTime.now();
        sb.append(eq).append('\n');
        for (String k : new String[]{"store.name", "store.address", "store.tel", "store.tin"})
            if (cfg.getProperty(k) != null) sb.append(center(cfg.getProperty(k))).append('\n');
        sb.append(eq).append('\n');
        sb.append(String.format("Receipt #: %08d%n", no));
        sb.append("Date: ").append(now.format(DateTimeFormatter.ofPattern("MMMM d, yyyy"))).append('\n');
        sb.append("Time: ").append(now.format(DateTimeFormatter.ofPattern("hh:mm a"))).append('\n');
        sb.append("Cashier: ").append(cashier).append('\n');
        sb.append("Terminal: ").append(cfg.getProperty("terminal", "POS-01")).append('\n');
        sb.append(dash).append('\n');
        sb.append(row("QTY  ITEM DESCRIPTION", "TOTAL")).append('\n');
        sb.append(dash).append('\n');
        for (Line l : cart) {
            String left = String.format("%-4d %s", l.qty, l.item.name());
            String right = money(l.total());
            int room = W - right.length() - 1;
            if (left.length() > room) {
                sb.append(left, 0, Math.min(left.length(), W)).append('\n');
                sb.append(row("", right)).append('\n');
            } else sb.append(row(left, right)).append('\n');
            if (l.qty > 1) sb.append("     @ ").append(money(l.item.price())).append(" each\n");
        }
        sb.append(dash).append('\n');
        sb.append(row("SUBTOTAL", money(total()))).append('\n');
        sb.append(row("VAT (" + trim(vatRate) + "%) Included", money(vat()))).append('\n');
        sb.append(row("TOTAL DUE", money(total()))).append('\n');
        sb.append(dash).append('\n');
        sb.append(row("CASH", money(cash))).append('\n');
        sb.append(row("CHANGE", money(change))).append('\n');
        sb.append(eq).append('\n');
        for (String k : new String[]{"footer1", "footer2"})
            if (cfg.getProperty(k) != null) sb.append(center(cfg.getProperty(k))).append('\n');
        sb.append(eq).append('\n');
        return sb.toString();
    }

    static String row(String l, String r) {
        int pad = Math.max(1, W - l.length() - r.length());
        return l + " ".repeat(pad) + r;
    }
    static String center(String s) {
        int pad = Math.max(0, (W - s.length()) / 2);
        return " ".repeat(pad) + s;
    }

    void showReceipt(String text) {
        JDialog d = new JDialog(this, "Receipt", true);
        JTextArea ta = new JTextArea(text);
        ta.setEditable(false);
        ta.setFont(new Font("Consolas", Font.PLAIN, 14));
        ta.setBackground(new Color(0xFFFEFA));
        ta.setForeground(Color.BLACK);
        ta.setBorder(new EmptyBorder(14, 18, 14, 18));
        JScrollPane sp = new JScrollPane(ta);
        sp.setBorder(BorderFactory.createLineBorder(ORANGE, 3));

        JButton done = flat("NEW ORDER", PINK);
        done.setFont(new Font("Segoe UI", Font.BOLD, 16));
        done.setPreferredSize(new Dimension(100, 46));
        done.addActionListener(e -> d.dispose());
        JButton print = flat("Print", ORANGE);
        print.setFont(done.getFont());
        print.addActionListener(e -> {
            try { ta.print(); } catch (Exception ex) { warn("Could not print: " + ex.getMessage()); }
        });
        JPanel b = new JPanel(new GridLayout(1, 2, 10, 0)); b.setOpaque(false);
        b.add(print); b.add(done);

        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBackground(CREAM);
        root.setBorder(new EmptyBorder(14, 14, 14, 14));
        root.add(sp, BorderLayout.CENTER);
        root.add(b, BorderLayout.SOUTH);
        d.setContentPane(root);
        d.getRootPane().setDefaultButton(done);
        d.setSize(470, 640);
        d.setLocationRelativeTo(this);
        d.setVisible(true);
    }

    // ================= helpers =================
    String money(double v) { return cur + " " + String.format("%,.2f", v); }
    static String fmtPlain(double v) { return String.format("%.2f", v); }
    static String trim(double v) { return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v); }
    void warn(String m) { JOptionPane.showMessageDialog(this, m, "Payment", JOptionPane.WARNING_MESSAGE); }
    void info(String m) { JOptionPane.showMessageDialog(this, m, "Dunkin' POS", JOptionPane.INFORMATION_MESSAGE); }
    boolean confirm(String m) { return JOptionPane.showConfirmDialog(this, m, "Confirm", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION; }
}
