import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableRowSorter;
import javax.swing.RowFilter;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.NodeList;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;

public class Insomniakos extends JFrame {

    private static final String BASE_URL =
            "https://www.insomnia.gr";

    private static final String CLASSIFIEDS_URL =
            BASE_URL + "/classifieds/";

    private static final String SEARCH_URL =
            BASE_URL + "/classifieds/search/?q=";

    // Official classifieds categories used by the first-tab search.
    private static final String FULL_SYSTEMS_CATEGORY_URL =
            BASE_URL + "/classifieds/category/8-pliri-systimata/";
    private static final String LAPTOPS_CATEGORY_URL =
            BASE_URL + "/classifieds/category/4-laptops/";
    private static final String APPLE_CATEGORY_URL =
            BASE_URL + "/classifieds/category/2-apple/";
    private static final String MOBILE_CATEGORY_URL =
            BASE_URL + "/classifieds/category/3-kiniti-tilefonia/";
    // Insomnia exposes Tablets as a subcategory under this classifieds URL.
    private static final String TABLETS_CATEGORY_URL =
            BASE_URL + "/classifieds/category/78-diafora/";

    // Vendora.gr: κατηγορία «Σταθεροί Υπολογιστές» (id 2vdx1l).
    // Ο όρος αναζήτησης πάει στο ?q= (π.χ. ...html?q=rtx%203070).
    private static final String VENDORA_BASE =
            "https://vendora.gr";
    private static final String VENDORA_DESKTOPS_URL =
            VENDORA_BASE + "/browse/2vdx1l/statheri-ipologistes.html";
    // Γενική αναζήτηση: «Υπολογιστές, Tablets, Περιφερειακά» (εξαρτήματα, PC, laptop κλπ).
    private static final String VENDORA_GENERAL_URL =
            VENDORA_BASE + "/browse/7vwxzr/ipologistes-tablets-periferiaka.html";
    private static final int VENDORA_MAX_PAGES = 60;
    // Πλήρη PC: τουλάχιστον τόσα επαληθευμένα PC ψάχνονται ανά εξάρτημα, και τόσα κρατιούνται το πολύ.
    private static final int SYSTEMS_MIN = 3;
    private static final int SYSTEMS_KEEP = 5;
    private static final int SYSTEMS_BATCH = 6;
    private static final int SYSTEMS_MAX_OPEN = 24;
    // Ένα πλήρες PC κάτω από αυτή την τιμή (€) θεωρείται παράλογο (π.χ. 0.01 €, 50 €,
    // «τιμή συμφωνίας») και δεν εμφανίζεται ποτέ ως φθηνότερο PC στα tabs 3 και 4.
    private static final double MIN_PC_PRICE = 100.0;
    // Λιγότερα ταυτόχρονα αιτήματα για τη Vendora, για να μη μας κόψει το site.
    private static final int VENDORA_THREADS = 6;
    private static final long VENDORA_PAGE_DELAY_MS = 250L;

    // Vinted.gr (tabs 5 και 6). Κάθε σελίδα καταλόγου έχει έως 96 αγγελίες.
    private static final String VINTED_BASE =
            "https://www.vinted.gr";
    private static final String VINTED_CATALOG_URL =
            VINTED_BASE + "/catalog";
    private static final int VINTED_MAX_PAGES = 30;
    private static final int VINTED_THREADS = 4;
    // Ελάχιστη απόσταση ανάμεσα σε δύο αιτήματα προς το Vinted (όλα τα νήματα μαζί).
    private static final long VINTED_REQUEST_SPACING_MS = 350L;

    // Πηγές του tab «Έλεγχος τιμών».
    private static final int SRC_INSOMNIA = 0;
    private static final int SRC_VENDORA = 1;
    private static final int SRC_VINTED = 2;
    private static final int SRC_FACEBOOK = 3;

    private static final int TIMEOUT_MS = 20000;

    // Πόσα αιτήματα τρέχουν ταυτόχρονα (σελίδες κατηγορίας ΚΑΙ λεπτομέρειες αγγελιών μαζί).
    // Αν το site αρχίσει να μπλοκάρει, μείωσέ το (π.χ. 4).
    private static final int THREADS = 16;

    // The sold-price tab uses separate, rate-limited pools. Keeping these
    // smaller than the general search pool avoids Insomnia HTTP 429 bursts.
    private static final int SOLD_ROW_THREADS = 4;
    private static final int SOLD_DETAIL_THREADS = 8;
    private static final int SOLD_MAX_RETRIES = 4;
    private static final int SOLD_TIMEOUT_MS = 12000;
    private static final long SOLD_REQUEST_SPACING_MS = 220L;

    // Επαναπροσπάθειες ανά σελίδα σε περίπτωση σφάλματος.
    private static final int MAX_RETRIES = 2;

    // Ασφάλεια απέναντι σε λανθασμένη ανίχνευση αριθμού σελίδων.
    private static final int MAX_PAGES = 2000;

    private static final Pattern PAGE_IN_PATH =
            Pattern.compile("/page/(\\d+)");
    private static final Pattern PAGE_IN_QUERY =
            Pattern.compile("(?:^|&)page=(\\d+)");

    private final JTextField keywordField = new JTextField(16);
    private final JTextField minPriceField = new JTextField("10", 6);
    private final JTextField maxPriceField = new JTextField(6);
    // Comma/newline-separated terms excluded from the complete listing text.
    private final JTextField excludeKeywordsField = new JTextField(24);

    private final JComboBox<Category> categoryCombo =
            new JComboBox<>();

    private final JComboBox<String> conditionCombo =
            new JComboBox<>(new String[]{
                    "Όλες οι καταστάσεις",
                    "ΝΕΟ",
                    "ΜΕΤΑΧΕΙΡΙΣΜΕΝΟ"
            });

    private final JComboBox<String> transactionCombo =
            new JComboBox<>(new String[]{
                    "Πώληση",
                    "Ζήτηση",
                    "Όλα"
            });

    private final JComboBox<String> deviceCombo =
            new JComboBox<>(new String[]{
                    "Όλα (PC + Laptop)",
                    "Μόνο PC (χωρίς laptop)",
                    "Μόνο Laptop"
            });

    private final JComboBox<String> sortCombo =
            new JComboBox<>(new String[]{
                    "Τιμή: αύξουσα",
                    "Τιμή: φθίνουσα",
                    "Τίτλος: Α-Ω",
                    "Όπως βρέθηκαν"
            });

    private final JCheckBox deepSpecsBox =
            new JCheckBox("Specs από την αγγελία", true);

    private final JButton searchButton =
            new JButton("Αναζήτηση");

    private final JLabel statusLabel =
            new JLabel("Έτοιμο.");

    // Πάνελ πλήρους περιγραφής της επιλεγμένης αγγελίας.
    private final JLabel detailTitle =
            new JLabel("Επίλεξε μια αγγελία για να δεις την περιγραφή της.");
    private final JTextArea detailArea = new JTextArea();
    private final JButton loadFullButton =
            new JButton("Φόρτωση πλήρους περιγραφής");
    private final JButton openButton =
            new JButton("Άνοιγμα στον browser");
    private volatile Listing shownListing;

    private final ListingTableModel tableModel =
            new ListingTableModel();

    private final JTable resultsTable = new JTable(tableModel) {
        // Οι τιμές των specs κρατιούνται πλέον ολόκληρες· το tooltip δείχνει
        // το πλήρες κείμενο όταν το κελί είναι κομμένο.
        @Override
        public String getToolTipText(MouseEvent event) {
            int row = rowAtPoint(event.getPoint());
            int col = columnAtPoint(event.getPoint());

            if (row < 0 || col < 0) {
                return null;
            }

            Object value = getValueAt(row, col);

            if (value == null || value.toString().length() < 18) {
                return null;
            }

            String text = value.toString()
                    .replace("&", "&amp;")
                    .replace("<", "&lt;");

            return "<html><body style='width:380px'>" + text + "</body></html>";
        }

        @Override
        public Component prepareRenderer(javax.swing.table.TableCellRenderer renderer,
                                         int row, int column) {
            Component component = super.prepareRenderer(renderer, row, column);
            int modelRow = convertRowIndexToModel(row);
            Listing listing = tableModel.getListing(modelRow);
            if (!isRowSelected(row)) {
                component.setBackground(getBackground());
                component.setForeground(getForeground());
            }
            applyFlaggedRowBorder(component, this, isFlaggedListing(listing), row, column);
            return component;
        }
    };

    private final TableRowSorter<ListingTableModel> sorter =
            new TableRowSorter<>(tableModel);

    private SwingWorker<List<Listing>, String> activeWorker;
    private final AtomicInteger mainSearchGeneration = new AtomicInteger();

    // Διαδρομή του τελευταίου txt που αποθηκεύτηκε.
    private volatile String lastDumpPath = "";

    // Περίληψη της τελευταίας λήψης (σελίδες, αγγελίες).
    private volatile String lastSummary = "";

    public Insomniakos() {
        super("INSOMNIAKOS | Marketplace Intelligence");

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1600, 900);
        setMinimumSize(new Dimension(1280, 720));
        setLocationRelativeTo(null);
        setLayout(new BorderLayout(0, 0));
        getContentPane().setBackground(APP_BACKGROUND);

        buildInterface();
        configureTable();
        applySort();
        applyProfessionalTheme();
        add(buildApplicationHeader(), BorderLayout.NORTH);

        categoryCombo.addItem(Category.allCategories());

        keywordField.addActionListener(e -> search());
        searchButton.addActionListener(e -> search());

        conditionCombo.addActionListener(e -> applyFilters());
        transactionCombo.addActionListener(e -> applyFilters());
        deviceCombo.addActionListener(e -> applyFilters());
        sortCombo.addActionListener(e -> applySort());
        minPriceField.addActionListener(e -> applyFilters());
        maxPriceField.addActionListener(e -> applyFilters());
        addLiveTextListener(minPriceField, this::applyFilters);
        addLiveTextListener(maxPriceField, this::applyFilters);
        excludeKeywordsField.addActionListener(e -> applyFilters());
        excludeKeywordsField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { applyFilters(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { applyFilters(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { applyFilters(); }
        });

        loadCategories();
    }

    private void buildInterface() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(
                BorderFactory.createEmptyBorder(10, 10, 5, 10));

        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.fill = GridBagConstraints.HORIZONTAL;

        addField(panel, c, 0, "Αναζήτηση:", keywordField);
        addField(panel, c, 1, "Κατηγορία:", categoryCombo);
        addField(panel, c, 2, "Ελάχιστη τιμή:", minPriceField);
        addField(panel, c, 3, "Μέγιστη τιμή:", maxPriceField);
        addField(panel, c, 4, "Κατάσταση:", conditionCombo);
        addField(panel, c, 5, "Τύπος:", transactionCombo);
        addField(panel, c, 6, "Συσκευή:", deviceCombo);
        addField(panel, c, 7, "Ταξινόμηση:", sortCombo);

        c.gridx = 8;
        c.gridy = 1;
        c.weightx = 0;
        panel.add(deepSpecsBox, c);

        c.gridx = 9;
        c.gridy = 1;
        c.weightx = 0;
        panel.add(searchButton, c);

        JPanel excludePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        excludePanel.add(new JLabel("Αποκλεισμός λέξεων (με κόμμα):"));
        excludeKeywordsField.setToolTipText("π.χ. ζητηση, χαλασμενο, ανταλλαγη — ελέγχεται όλο το κείμενο κάθε αγγελίας");
        excludePanel.add(excludeKeywordsField);

        JPanel controls = new JPanel(new BorderLayout(0, 3));
        controls.add(panel, BorderLayout.CENTER);
        controls.add(excludePanel, BorderLayout.SOUTH);

        JPanel north = new JPanel(new BorderLayout());
        north.add(controls, BorderLayout.CENTER);

        statusLabel.setBorder(
                BorderFactory.createEmptyBorder(5, 10, 8, 10));

        north.add(statusLabel, BorderLayout.SOUTH);

        JPanel searchTab = new JPanel(new BorderLayout(5, 5));
        searchTab.add(north, BorderLayout.NORTH);

        JSplitPane split = new JSplitPane(
                JSplitPane.VERTICAL_SPLIT,
                new JScrollPane(resultsTable),
                buildDetailPanel());
        split.setResizeWeight(0.72);
        split.setContinuousLayout(true);

        searchTab.add(split, BorderLayout.CENTER);

        styleButton(searchButton, INSOMNIA_COLOR, INK);

        // Πάνω-πάνω μπάρα με το χρώμα του tab, ώστε να φαίνεται ποιο site ψάχνεις.
        JPanel insomniaTab = new JPanel(new BorderLayout());
        insomniaTab.add(banner("INSOMNIA.GR  ·  Αναζήτηση αγγελιών", INSOMNIA_COLOR), BorderLayout.NORTH);
        insomniaTab.add(searchTab, BorderLayout.CENTER);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Insomnia", insomniaTab);
        tabs.addTab("Vendora", new VendoraTab());
        tabs.addTab("Τιμές · Insomnia", new SoldPricesTab(SRC_INSOMNIA));
        tabs.addTab("Τιμές · Vendora", new SoldPricesTab(SRC_VENDORA));
        tabs.addTab("Vinted", new VintedTab());
        tabs.addTab("Τιμές · Vinted", new SoldPricesTab(SRC_VINTED));
        tabs.addTab("Facebook", new FacebookTab());
        tabs.addTab("Τιμές · Facebook", new SoldPricesTab(SRC_FACEBOOK));
        tabs.addTab("Όλες οι αγορές", new AllMarketsTab());
        colorTabs(tabs,
                new String[]{"Insomnia", "Vendora", "Τιμές · Insomnia", "Τιμές · Vendora",
                        "Vinted", "Τιμές · Vinted", "Facebook", "Τιμές · Facebook", "Όλες οι αγορές"},
                new Color[]{INSOMNIA_COLOR, VENDORA_COLOR, EXCEL_COLOR, VENDORA_EXCEL_COLOR,
                        VINTED_COLOR, VINTED_EXCEL_COLOR, FACEBOOK_COLOR, FACEBOOK_EXCEL_COLOR,
                        ALL_MARKETS_COLOR});

        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        add(tabs, BorderLayout.CENTER);
    }

    // Shared design tokens: a neutral corporate foundation with restrained platform accents.
    private static final Color APP_BACKGROUND = new Color(242, 245, 249);
    private static final Color SURFACE = Color.WHITE;
    private static final Color SURFACE_ALT = new Color(248, 250, 252);
    private static final Color BRAND_NAVY = new Color(20, 39, 63);
    private static final Color BRAND_NAVY_LIGHT = new Color(34, 57, 85);
    private static final Color PRIMARY = new Color(43, 91, 145);
    private static final Color PRIMARY_HOVER = new Color(32, 71, 116);
    private static final Color TEXT_PRIMARY = new Color(30, 41, 59);
    private static final Color TEXT_MUTED = new Color(100, 116, 139);
    private static final Color BORDER = new Color(220, 227, 236);
    private static final Color TABLE_HEADER = new Color(31, 51, 76);
    private static final Color TABLE_SELECTION = new Color(222, 235, 255);
    private static final Color DANGER = new Color(185, 55, 65);
    private static final Color FLAGGED_BACKGROUND = new Color(255, 249, 236);
    private static final Color FLAGGED_BORDER = new Color(190, 145, 69);
    private static final Font CORPORATE_FONT = new Font("Segoe UI", Font.PLAIN, 12);

    // Platform accents: recognizable brand hues, softened for a professional workspace.
    // Insomnia = warm amber, Vendora = cyan, Vinted = deep calypso teal, Facebook = blue.
    private static final Color INSOMNIA_COLOR = new Color(205, 151, 60);
    private static final Color VENDORA_COLOR = new Color(48, 157, 184);
    private static final Color EXCEL_COLOR = INSOMNIA_COLOR;
    private static final Color VENDORA_EXCEL_COLOR = VENDORA_COLOR;
    private static final Color VINTED_COLOR = new Color(0, 119, 130);
    private static final Color VINTED_EXCEL_COLOR = VINTED_COLOR;
    private static final Color FACEBOOK_COLOR = new Color(38, 111, 190);
    private static final Color FACEBOOK_EXCEL_COLOR = FACEBOOK_COLOR;
    private static final Color ALL_MARKETS_COLOR = new Color(104, 121, 143);
    private static final Color INK = new Color(30, 41, 59);

    private static Color mix(Color base, Color accent, double accentWeight) {
        double weight = Math.max(0.0, Math.min(1.0, accentWeight));
        int r = (int) Math.round(base.getRed() * (1.0 - weight) + accent.getRed() * weight);
        int g = (int) Math.round(base.getGreen() * (1.0 - weight) + accent.getGreen() * weight);
        int b = (int) Math.round(base.getBlue() * (1.0 - weight) + accent.getBlue() * weight);
        return new Color(r, g, b);
    }

    private static Color componentAccent(JComponent component, Color fallback) {
        Object value = component.getClientProperty("corporate.accent");
        return value instanceof Color ? (Color) value : fallback;
    }

    private static String toHtmlColor(Color color) {
        return String.format(Locale.ROOT, "#%02X%02X%02X",
                color.getRed(), color.getGreen(), color.getBlue());
    }

    private static Color platformColor(String platform) {
        if (platform == null) return ALL_MARKETS_COLOR;
        String value = platform.toLowerCase(Locale.ROOT);
        if (value.contains("insomnia")) return INSOMNIA_COLOR;
        if (value.contains("vendora")) return VENDORA_COLOR;
        if (value.contains("vinted")) return VINTED_COLOR;
        if (value.contains("facebook")) return FACEBOOK_COLOR;
        return ALL_MARKETS_COLOR;
    }

    private static JLabel banner(String text, Color sourceColor) {
        Color accent = sourceColor == null ? PRIMARY : sourceColor;
        JLabel label = new JLabel(text);
        label.setOpaque(true);
        label.setBackground(mix(SURFACE, accent, 0.075));
        label.setForeground(darken(accent, 0.42));
        label.setFont(CORPORATE_FONT.deriveFont(Font.BOLD, 12.5f));
        label.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 3, 0, 0, accent),
                BorderFactory.createEmptyBorder(10, 14, 10, 14)));
        return label;
    }

    private static Color lighten(Color c, double amount) {
        int r = (int) (c.getRed() + (255 - c.getRed()) * amount);
        int g = (int) (c.getGreen() + (255 - c.getGreen()) * amount);
        int b = (int) (c.getBlue() + (255 - c.getBlue()) * amount);
        return new Color(r, g, b);
    }

    private static Color darken(Color c, double amount) {
        int r = (int) (c.getRed() * (1 - amount));
        int g = (int) (c.getGreen() * (1 - amount));
        int b = (int) (c.getBlue() * (1 - amount));
        return new Color(Math.max(0, r), Math.max(0, g), Math.max(0, b));
    }

    /*
     * Το native look-and-feel (Windows/macOS) αγνοεί το setBackground των κουμπιών και των
     * επικεφαλίδων πίνακα, οπότε το λευκό κείμενο έμενε πάνω σε ανοιχτό φόντο. Εδώ ζωγραφίζουμε
     * εμείς το φόντο, ώστε το χρώμα να φαίνεται πάντα (και όχι μόνο μετά το πάτημα).
     */
    private static final class FlatButtonUI extends javax.swing.plaf.basic.BasicButtonUI {
        @Override
        public void update(Graphics graphics, JComponent component) {
            AbstractButton button = (AbstractButton) component;
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            String variant = String.valueOf(button.getClientProperty("corporate.variant"));
            boolean secondary = "secondary".equals(variant) || "danger".equals(variant);
            Color accent = button.getBackground() == null ? PRIMARY : button.getBackground();
            Color fill = accent;
            if (!button.isEnabled()) {
                fill = new Color(235, 239, 244);
            } else if (button.getModel().isPressed()) {
                fill = "danger".equals(variant) ? new Color(255, 239, 240)
                        : secondary ? new Color(232, 240, 251) : darken(accent, 0.17);
            } else if (button.getModel().isSelected() && secondary) {
                fill = new Color(232, 240, 251);
            } else if (button.getModel().isRollover()) {
                fill = secondary ? new Color(246, 249, 253) : darken(accent, 0.10);
            }
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, button.getWidth() - 1, button.getHeight() - 1, 10, 10);
            if (secondary) {
                g2.setColor("danger".equals(variant) ? new Color(239, 190, 194) : BORDER);
                g2.setStroke(new BasicStroke(1f));
                g2.drawRoundRect(0, 0, button.getWidth() - 1, button.getHeight() - 1, 10, 10);
            }
            g2.dispose();
            paint(graphics, component);
        }

        @Override
        protected void paintButtonPressed(Graphics g, AbstractButton b) {
            // Press feedback is drawn in update() so the button keeps its rounded shape.
        }

        @Override
        protected void paintText(Graphics g, AbstractButton b, Rectangle textRect, String text) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setFont(b.getFont());
            Color color = !b.isEnabled() ? new Color(145, 155, 168)
                    : b.getModel().isSelected() && "secondary".equals(String.valueOf(b.getClientProperty("corporate.variant")))
                    ? PRIMARY : b.getForeground();
            FontMetrics metrics = g2.getFontMetrics();
            int x = textRect.x + Math.max(0, (textRect.width - metrics.stringWidth(text)) / 2);
            int y = textRect.y + (textRect.height - metrics.getHeight()) / 2 + metrics.getAscent();
            g2.setColor(color);
            g2.drawString(text, x, y);
            g2.dispose();
        }
    }

    private static void styleButton(JButton button, Color accent, Color ignoredForeground) {
        Color sourceAccent = accent == null ? PRIMARY : accent;
        button.putClientProperty("corporate.variant", "primary");
        button.putClientProperty("corporate.accent", sourceAccent);
        button.setUI(new FlatButtonUI());
        button.setOpaque(false);
        button.setContentAreaFilled(false);
        button.setBorderPainted(false);
        button.setFocusPainted(false);
        button.setRolloverEnabled(true);
        // Darken each brand hue slightly so white button labels remain readable.
        button.setBackground(darken(sourceAccent, 0.17));
        button.setForeground(Color.WHITE);
        button.setFont(CORPORATE_FONT.deriveFont(Font.BOLD, 12f));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setBorder(BorderFactory.createEmptyBorder(9, 17, 9, 17));
        button.setMargin(new Insets(5, 13, 5, 13));
    }

    private static void styleSecondaryButton(JButton button, boolean destructive) {
        button.putClientProperty("corporate.variant", destructive ? "danger" : "secondary");
        button.setUI(new FlatButtonUI());
        button.setOpaque(false);
        button.setContentAreaFilled(false);
        button.setBorderPainted(false);
        button.setFocusPainted(false);
        button.setRolloverEnabled(true);
        button.setBackground(SURFACE);
        button.setForeground(destructive ? DANGER : BRAND_NAVY);
        button.setFont(CORPORATE_FONT.deriveFont(Font.BOLD, 11.5f));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setBorder(BorderFactory.createEmptyBorder(8, 14, 8, 14));
        button.setMargin(new Insets(5, 11, 5, 11));
    }

    private static void styleSecondaryToggle(JToggleButton button) {
        button.putClientProperty("corporate.variant", "secondary");
        button.setUI(new FlatButtonUI());
        button.setOpaque(false);
        button.setContentAreaFilled(false);
        button.setBorderPainted(false);
        button.setFocusPainted(false);
        button.setRolloverEnabled(true);
        button.setBackground(SURFACE);
        button.setForeground(BRAND_NAVY);
        button.setFont(CORPORATE_FONT.deriveFont(Font.BOLD, 11.5f));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setBorder(BorderFactory.createEmptyBorder(8, 14, 8, 14));
        button.setMargin(new Insets(5, 11, 5, 11));
    }

    private static void styleHeader(JTable table, Color accent, Color ignoredForeground) {
        Color sourceAccent = accent == null ? PRIMARY : accent;
        table.putClientProperty("corporate.accent", sourceAccent);
        javax.swing.table.JTableHeader header = table.getTableHeader();
        header.setBackground(TABLE_HEADER);
        header.setForeground(Color.WHITE);
        header.setFont(CORPORATE_FONT.deriveFont(Font.BOLD, 11.5f));
        header.setReorderingAllowed(false);
        header.setPreferredSize(new Dimension(header.getPreferredSize().width, 38));
        header.setDefaultRenderer(new javax.swing.table.DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value, boolean selected,
                                                           boolean focused, int row, int column) {
                JLabel label = (JLabel) super.getTableCellRendererComponent(t, value, false, false, row, column);
                String text = value == null ? "" : value.toString();
                if (t.getRowSorter() != null) {
                    int modelColumn = t.convertColumnIndexToModel(column);
                    for (RowSorter.SortKey key : t.getRowSorter().getSortKeys()) {
                        if (key.getColumn() == modelColumn) {
                            if (key.getSortOrder() == SortOrder.ASCENDING) text += "  ▲";
                            else if (key.getSortOrder() == SortOrder.DESCENDING) text += "  ▼";
                            break;
                        }
                    }
                }
                label.setText(text);
                label.setOpaque(true);
                label.setBackground(TABLE_HEADER);
                label.setForeground(Color.WHITE);
                label.setFont(CORPORATE_FONT.deriveFont(Font.BOLD, 11.5f));
                label.setHorizontalAlignment(SwingConstants.LEFT);
                label.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, 0, 3, 1,
                                mix(TABLE_HEADER, sourceAccent, 0.58)),
                        BorderFactory.createEmptyBorder(7, 10, 7, 10)));
                return label;
            }
        });
    }

    private JPanel buildApplicationHeader() {
        JPanel shell = new JPanel(new BorderLayout());
        shell.setBackground(BRAND_NAVY);
        shell.setBorder(BorderFactory.createEmptyBorder(13, 22, 13, 22));

        JPanel identity = new JPanel(new BorderLayout(13, 0));
        identity.setOpaque(false);
        JLabel monogram = new JLabel("IM", SwingConstants.CENTER);
        monogram.setOpaque(true);
        monogram.setBackground(PRIMARY);
        monogram.setForeground(Color.WHITE);
        monogram.setFont(CORPORATE_FONT.deriveFont(Font.BOLD, 15f));
        monogram.setPreferredSize(new Dimension(44, 44));
        monogram.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        JPanel titles = new JPanel();
        titles.setOpaque(false);
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
        JLabel name = new JLabel("INSOMNIAKOS");
        name.setForeground(Color.WHITE);
        name.setFont(CORPORATE_FONT.deriveFont(Font.BOLD, 20f));
        JLabel tagline = new JLabel("MARKETPLACE INTELLIGENCE  ·  PC HARDWARE");
        tagline.setForeground(new Color(183, 197, 215));
        tagline.setFont(CORPORATE_FONT.deriveFont(Font.PLAIN, 10.5f));
        titles.add(name);
        titles.add(Box.createVerticalStrut(3));
        titles.add(tagline);
        identity.add(monogram, BorderLayout.WEST);
        identity.add(titles, BorderLayout.CENTER);

        JPanel meta = new JPanel(new FlowLayout(FlowLayout.RIGHT, 9, 4));
        meta.setOpaque(false);
        JLabel workspace = new JLabel("  CORPORATE WORKSPACE  ");
        workspace.setOpaque(true);
        workspace.setBackground(new Color(36, 58, 85));
        workspace.setForeground(new Color(223, 233, 245));
        workspace.setFont(CORPORATE_FONT.deriveFont(Font.BOLD, 10f));
        workspace.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(67, 89, 116)),
                BorderFactory.createEmptyBorder(6, 4, 6, 4)));
        JLabel version = new JLabel("DESKTOP EDITION  ·  2026");
        version.setForeground(new Color(158, 175, 196));
        version.setFont(CORPORATE_FONT.deriveFont(Font.PLAIN, 10f));
        meta.add(workspace);
        meta.add(version);

        shell.add(identity, BorderLayout.WEST);
        shell.add(meta, BorderLayout.EAST);
        JPanel accent = new JPanel();
        accent.setBackground(PRIMARY);
        accent.setPreferredSize(new Dimension(1, 3));
        JPanel header = new JPanel(new BorderLayout());
        header.add(shell, BorderLayout.CENTER);
        header.add(accent, BorderLayout.SOUTH);
        return header;
    }

    private void applyProfessionalTheme() {
        UIManager.put("defaultFont", CORPORATE_FONT);
        UIManager.put("Panel.background", SURFACE);
        UIManager.put("Label.foreground", TEXT_PRIMARY);
        UIManager.put("TextField.background", SURFACE);
        UIManager.put("TextField.foreground", TEXT_PRIMARY);
        UIManager.put("ComboBox.background", SURFACE);
        UIManager.put("ComboBox.foreground", TEXT_PRIMARY);

        styleComponentTree(getContentPane());
        getContentPane().setBackground(APP_BACKGROUND);
        revalidate();
        repaint();
    }

    private void styleComponentTree(Component component) {
        if (component instanceof JComponent) {
            JComponent jc = (JComponent) component;
            Font current = jc.getFont();
            if (current != null) jc.setFont(new Font(CORPORATE_FONT.getFamily(), current.getStyle(), current.getSize()));
        }

        if (component instanceof JPanel) {
            JPanel panel = (JPanel) component;
            panel.setBackground(SURFACE);
        } else if (component instanceof JTabbedPane) {
            JTabbedPane tabs = (JTabbedPane) component;
            tabs.setBackground(APP_BACKGROUND);
            tabs.setForeground(TEXT_PRIMARY);
            tabs.setBorder(BorderFactory.createEmptyBorder());
        } else if (component instanceof JTable) {
            JTable table = (JTable) component;
            table.setFont(CORPORATE_FONT.deriveFont(Font.PLAIN, 12f));
            table.setForeground(TEXT_PRIMARY);
            table.setBackground(SURFACE);
            Color accent = componentAccent(table, PRIMARY);
            table.setSelectionBackground(mix(Color.WHITE, accent, 0.16));
            table.setSelectionForeground(BRAND_NAVY);
            table.setGridColor(new Color(231, 236, 242));
            table.setShowVerticalLines(false);
            table.setShowHorizontalLines(true);
            table.setIntercellSpacing(new Dimension(0, 1));
            table.setRowHeight(34);
            table.setFillsViewportHeight(true);
            table.getTableHeader().setPreferredSize(new Dimension(table.getTableHeader().getPreferredSize().width, 38));
            styleHeader(table, accent, Color.WHITE);
        } else if (component instanceof JScrollPane) {
            JScrollPane scroll = (JScrollPane) component;
            scroll.setBorder(BorderFactory.createLineBorder(BORDER));
            scroll.getViewport().setBackground(SURFACE);
            scroll.setBackground(SURFACE);
        } else if (component instanceof JSplitPane) {
            JSplitPane split = (JSplitPane) component;
            split.setBorder(BorderFactory.createEmptyBorder());
            split.setBackground(APP_BACKGROUND);
            split.setDividerSize(8);
            split.setContinuousLayout(true);
        } else if (component instanceof JTextField) {
            JTextField field = (JTextField) component;
            field.setFont(CORPORATE_FONT.deriveFont(Font.PLAIN, 12f));
            field.setForeground(TEXT_PRIMARY);
            field.setBackground(SURFACE);
            field.setCaretColor(PRIMARY);
            field.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(BORDER),
                    BorderFactory.createEmptyBorder(7, 10, 7, 10)));
            Dimension d = field.getPreferredSize();
            field.setPreferredSize(new Dimension(d.width, 35));
        } else if (component instanceof JComboBox) {
            JComboBox<?> combo = (JComboBox<?>) component;
            combo.setFont(CORPORATE_FONT.deriveFont(Font.PLAIN, 12f));
            combo.setForeground(TEXT_PRIMARY);
            combo.setBackground(SURFACE);
            combo.setBorder(BorderFactory.createLineBorder(BORDER));
            Dimension d = combo.getPreferredSize();
            combo.setPreferredSize(new Dimension(Math.max(100, d.width), 35));
        } else if (component instanceof JCheckBox) {
            JCheckBox check = (JCheckBox) component;
            check.setFont(CORPORATE_FONT.deriveFont(Font.PLAIN, 11.5f));
            Object platformAccent = check.getClientProperty("corporate.platformAccent");
            if (platformAccent instanceof Color) {
                Color accent = (Color) platformAccent;
                check.setForeground(darken(accent, 0.43));
                check.setBackground(mix(Color.WHITE, accent, 0.075));
                check.setOpaque(true);
                check.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(mix(Color.WHITE, accent, 0.24)),
                        BorderFactory.createEmptyBorder(4, 7, 4, 7)));
            } else {
                check.setForeground(TEXT_PRIMARY);
                check.setOpaque(false);
            }
            check.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        } else if (component instanceof JRadioButton) {
            JRadioButton radio = (JRadioButton) component;
            radio.setFont(CORPORATE_FONT.deriveFont(Font.PLAIN, 11.5f));
            radio.setForeground(TEXT_PRIMARY);
            radio.setOpaque(false);
        } else if (component instanceof JTextArea) {
            JTextArea area = (JTextArea) component;
            area.setFont(CORPORATE_FONT.deriveFont(Font.PLAIN, 12f));
            area.setForeground(TEXT_PRIMARY);
            area.setBackground(SURFACE);
            area.setCaretColor(PRIMARY);
            area.setMargin(new Insets(10, 12, 10, 12));
        } else if (component instanceof JEditorPane) {
            JEditorPane editor = (JEditorPane) component;
            editor.setFont(CORPORATE_FONT.deriveFont(Font.PLAIN, 12f));
            editor.setForeground(TEXT_PRIMARY);
            editor.setBackground(SURFACE);
        } else if (component instanceof JToggleButton) {
            styleSecondaryToggle((JToggleButton) component);
        } else if (component instanceof JButton) {
            JButton button = (JButton) component;
            String label = button.getText() == null ? "" : button.getText().toLowerCase(Locale.ROOT);
            if (label.contains("αναζήτηση") || label.contains("αναζητηση")
                    || label.contains("αναζήτηση παντού") || label.contains("έλεγχος όλων")) {
                styleButton(button, componentAccent(button, PRIMARY), Color.WHITE);
            } else {
                styleSecondaryButton(button, label.contains("ακύρωση") || label.contains("ακυρωση"));
            }
        } else if (component instanceof JLabel) {
            JLabel label = (JLabel) component;
            // The custom tab labels manage their own active/inactive colors.
            if (!Boolean.TRUE.equals(label.getClientProperty("corporate.tabLabel"))) {
                label.setForeground(TEXT_PRIMARY);
            }
        }

        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                styleComponentTree(child);
            }
        }
    }

    // Κείμενο λεπτομερειών -> HTML με πατήσιμους συνδέσμους.
    private static String detailToHtml(String text) {
        String esc = (text == null ? "" : text)
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        esc = Pattern.compile("(https?://[^\\s<>\"]+)").matcher(esc)
                .replaceAll("<a href=\"$1\">$1</a>");
        esc = esc.replace("\r", "").replace("\n", "<br>").replace("  ", "&nbsp; ");
        return "<html><body style=\"font-family:monospaced;font-size:12pt;margin:4px\">" + esc + "</body></html>";
    }

    // Minimal navigation: quiet source-color markers distinguish platforms without turning tabs into banners.
    private static void colorTabs(JTabbedPane tabs, String[] titles, Color[] accents) {
        tabs.setOpaque(true);
        tabs.setBackground(APP_BACKGROUND);
        tabs.setForeground(TEXT_PRIMARY);
        tabs.setBorder(BorderFactory.createEmptyBorder());
        tabs.setUI(new javax.swing.plaf.basic.BasicTabbedPaneUI() {
            @Override
            protected Insets getTabInsets(int placement, int index) {
                return new Insets(4, 2, 4, 2);
            }

            @Override
            protected Insets getContentBorderInsets(int placement) {
                return new Insets(0, 0, 0, 0);
            }

            @Override
            protected void paintTabBackground(Graphics g, int placement, int index,
                                              int x, int y, int w, int h, boolean selected) {
                g.setColor(APP_BACKGROUND);
                g.fillRect(x, y, w, h);
            }

            @Override
            protected void paintTabBorder(Graphics g, int placement, int index,
                                          int x, int y, int w, int h, boolean selected) {
                // Tab labels own their underline and padding.
            }

            @Override
            protected void paintContentBorder(Graphics g, int placement, int selectedIndex) {
                Insets insets = getContentBorderInsets(placement);
                int x = tabPane.getInsets().left + insets.left;
                int y = calculateTabAreaHeight(placement, runCount, maxTabHeight)
                        + tabPane.getInsets().top + insets.top;
                int w = tabPane.getWidth() - tabPane.getInsets().left - tabPane.getInsets().right
                        - insets.left - insets.right;
                int h = tabPane.getHeight() - y - tabPane.getInsets().bottom - insets.bottom;
                g.setColor(BORDER);
                g.drawLine(x, y, x + w, y);
            }
        });

        final JLabel[] labels = new JLabel[titles.length];
        final Color[] tabAccents = new Color[titles.length];
        for (int i = 0; i < titles.length; i++) {
            tabAccents[i] = accents != null && i < accents.length && accents[i] != null
                    ? accents[i] : PRIMARY;
            JLabel label = new JLabel("<html><span style='color:" + toHtmlColor(tabAccents[i])
                    + "'>●</span>&nbsp;&nbsp;" + titles[i] + "</html>", SwingConstants.CENTER);
            label.setOpaque(false);
            label.putClientProperty("corporate.tabLabel", Boolean.TRUE);
            label.setFont(CORPORATE_FONT.deriveFont(Font.PLAIN, 11.5f));
            label.setBorder(BorderFactory.createEmptyBorder(9, 12, 8, 12));
            label.setForeground(TEXT_MUTED);
            labels[i] = label;
            tabs.setTabComponentAt(i, label);
        }

        Runnable paint = () -> {
            int selected = tabs.getSelectedIndex();
            for (int i = 0; i < labels.length; i++) {
                boolean active = i == selected;
                Color accent = tabAccents[i];
                labels[i].setForeground(active ? darken(accent, 0.36) : TEXT_MUTED);
                labels[i].setFont(CORPORATE_FONT.deriveFont(active ? Font.BOLD : Font.PLAIN, 11.5f));
                labels[i].setBorder(active
                        ? BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, 0, 3, 0, accent),
                        BorderFactory.createEmptyBorder(9, 12, 5, 12))
                        : BorderFactory.createEmptyBorder(9, 12, 8, 12));
            }
        };
        tabs.addChangeListener(e -> paint.run());
        paint.run();
    }

    private static void addLiveTextListener(JTextField field, Runnable action) {
        field.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { action.run(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { action.run(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { action.run(); }
        });
    }

    private void addField(
            JPanel panel,
            GridBagConstraints c,
            int x,
            String label,
            JComponent component) {

        c.gridx = x;
        c.gridy = 0;
        c.weightx = 0;

        panel.add(new JLabel(label), c);

        c.gridx = x;
        c.gridy = 1;
        c.weightx = component instanceof JTextField ? 0.3 : 0.5;

        panel.add(component, c);
    }

    private void configureTable() {
        resultsTable.setRowSorter(sorter);
        resultsTable.setSelectionMode(
                ListSelectionModel.SINGLE_SELECTION);
        resultsTable.setAutoResizeMode(
                JTable.AUTO_RESIZE_OFF);

        // Οι αγγελίες χωρίς τιμή πάνε στο τέλος της αύξουσας ταξινόμησης.
        sorter.setComparator(2, (Comparator<Double>) (a, b) ->
                Double.compare(
                        a == null ? Double.MAX_VALUE : a,
                        b == null ? Double.MAX_VALUE : b));

        // Τίτλος, Περιγραφή, Τιμή, CPU, RAM, GPU, SSD, HDD,
        // Μητρική, PSU, Έξτρα, Πωλητής, Κατάσταση, Τύπος, Κατηγορία, URL, priority
        int[] widths = {
                260, 360, 80, 110, 140, 150, 110, 110, 180,
                200, 220, 120, 130, 80, 150, 350, 0
        };

        for (int i = 0; i < widths.length; i++) {
            resultsTable.getColumnModel()
                    .getColumn(i)
                    .setPreferredWidth(widths[i]);
        }
        // Η τελευταία στήλη χρησιμοποιείται μόνο για να κρατά τις ύποπτες
        // αγγελίες στο τέλος και δεν εμφανίζεται στον χρήστη.
        resultsTable.removeColumn(resultsTable.getColumnModel().getColumn(16));
        styleHeader(resultsTable, INSOMNIA_COLOR, INK);
        resultsTable.setSelectionBackground(mix(Color.WHITE, INSOMNIA_COLOR, 0.16));
        resultsTable.setSelectionForeground(BRAND_NAVY);

        resultsTable.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) {
                return;
            }

            int viewRow = resultsTable.getSelectedRow();

            showListing(viewRow < 0
                    ? null
                    : tableModel.getListing(
                    resultsTable.convertRowIndexToModel(viewRow)));
        });

        resultsTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 2) {
                    return;
                }

                int viewRow = resultsTable.getSelectedRow();

                if (viewRow < 0) {
                    return;
                }

                int modelRow =
                        resultsTable.convertRowIndexToModel(viewRow);

                Listing listing = tableModel.getListing(modelRow);

                if (listing != null) {
                    openUrl(listing.url);
                }
            }
        });
    }

    private void applySort() {
        List<RowSorter.SortKey> keys = new ArrayList<>();
        // 0=κανονική, 1=όλες οι flagged αγγελίες (λέξεις-κλειδιά ή πολλά CPU/GPU).
        keys.add(new RowSorter.SortKey(16, SortOrder.ASCENDING));

        switch (sortCombo.getSelectedIndex()) {
            case 0:
                keys.add(new RowSorter.SortKey(2, SortOrder.ASCENDING));
                break;
            case 1:
                keys.add(new RowSorter.SortKey(2, SortOrder.DESCENDING));
                break;
            case 2:
                keys.add(new RowSorter.SortKey(0, SortOrder.ASCENDING));
                break;
            default:
                break;
        }
        sorter.setSortKeys(keys);
    }

    // ---------------------------------------------------------
    // ΚΑΤΗΓΟΡΙΕΣ
    // ---------------------------------------------------------

    private void loadCategories() {
        searchButton.setEnabled(false);
        statusLabel.setText("Φόρτωση κατηγοριών...");

        SwingWorker<List<Category>, Void> worker =
                new SwingWorker<>() {

                    @Override
                    protected List<Category> doInBackground()
                            throws Exception {

                        Document doc = connect(CLASSIFIEDS_URL);

                        Elements links = doc.select(
                                "#elCatMenu1_menu a[href*='/classifieds/category/']");

                        if (links.isEmpty()) {
                            links = doc.select(
                                    "a[href*='/classifieds/category/']");
                        }

                        Map<String, Category> found = new LinkedHashMap<>();

                        for (Element link : links) {
                            String name = link.text().trim();
                            String url = link.absUrl("href");

                            if (name.isBlank() || url.isBlank()) {
                                continue;
                            }

                            if (!url.contains("/classifieds/category/")) {
                                continue;
                            }

                            if (normalize(name).contains(
                                    "olo to periexomeno se hardware")) {
                                continue;
                            }

                            url = removeQueryAndFragment(url);

                            found.putIfAbsent(
                                    url,
                                    new Category(name, url, false));
                        }

                        // These sections may be nested and absent from the top-level menu.
                        addRequestedSearchCategories(found);

                        return new ArrayList<>(found.values());
                    }

                    @Override
                    protected void done() {
                        try {
                            List<Category> loaded = get();

                            categoryCombo.removeAllItems();
                            categoryCombo.addItem(Category.allCategories());

                            for (Category category : loaded) {
                                categoryCombo.addItem(category);
                            }

                            statusLabel.setText(
                                    "Φορτώθηκαν " + loaded.size()
                                            + " κατηγορίες.");

                        } catch (Exception ex) {
                            addFallbackCategories();

                            statusLabel.setText(
                                    "Δεν φορτώθηκαν όλες οι κατηγορίες. "
                                            + "Προστέθηκαν οι βασικές.");
                        } finally {
                            searchButton.setEnabled(true);
                        }
                    }
                };

        worker.execute();
    }

    private void addFallbackCategories() {
        categoryCombo.removeAllItems();
        categoryCombo.addItem(Category.allCategories());

        Map<String, Category> requestedCategories = new LinkedHashMap<>();
        addRequestedSearchCategories(requestedCategories);

        categoryCombo.addItem(new Category(
                "Πλήρη Συστήματα",
                BASE_URL + "/classifieds/category/8-pliri-systimata/",
                false));

        categoryCombo.addItem(new Category(
                "Κάρτες Γραφικών",
                BASE_URL + "/classifieds/category/11-kartes-grafikon/",
                false));

        categoryCombo.addItem(new Category(
                "Επεξεργαστές",
                BASE_URL + "/classifieds/category/9-epexergastes/",
                false));

        categoryCombo.addItem(new Category(
                "Μητρικές",
                BASE_URL + "/classifieds/category/88-mitrikes/",
                false));

        categoryCombo.addItem(new Category(
                "Μνήμες",
                BASE_URL + "/classifieds/category/47-mnimes/",
                false));

        categoryCombo.addItem(new Category(
                "SSD",
                BASE_URL + "/classifieds/category/89-ssd/",
                false));

        for (Category category : requestedCategories.values()) {
            categoryCombo.addItem(category);
        }
    }

    private void addRequestedSearchCategories(Map<String, Category> categories) {
        categories.putIfAbsent(LAPTOPS_CATEGORY_URL,
                new Category("Laptops", LAPTOPS_CATEGORY_URL, false));
        categories.putIfAbsent(APPLE_CATEGORY_URL,
                new Category("Apple", APPLE_CATEGORY_URL, false));
        categories.putIfAbsent(MOBILE_CATEGORY_URL,
                new Category("Κινητή Τηλεφωνία", MOBILE_CATEGORY_URL, false));
        categories.putIfAbsent(TABLETS_CATEGORY_URL,
                new Category("Tablets", TABLETS_CATEGORY_URL, false));
    }

    // ---------------------------------------------------------
    // ΑΝΑΖΗΤΗΣΗ
    // ---------------------------------------------------------

    private void search() {
        String keyword = keywordField.getText().trim();

        if (keyword.isBlank()) {
            JOptionPane.showMessageDialog(
                    this,
                    "Γράψε έναν όρο αναζήτησης.");
            return;
        }

        Category selected =
                (Category) categoryCombo.getSelectedItem();

        if (selected == null) {
            selected = Category.allCategories();
        }

        if (activeWorker != null && !activeWorker.isDone()) {
            activeWorker.cancel(true);
        }

        final Category category = selected;
        final String term = keyword;
        final int runId = mainSearchGeneration.incrementAndGet();

        // Διαβάζονται εδώ (στο EDT) και χρησιμοποιούνται στο νήμα
        // της αναζήτησης.
        final boolean deep = deepSpecsBox.isSelected();
        final Double minPrice = parseOptionalPrice(minPriceField.getText());
        final Double maxPrice = parseOptionalPrice(maxPriceField.getText());

        lastSummary = "";
        lastDumpPath = "";

        tableModel.setListings(Collections.emptyList());
        showListing(null);

        searchButton.setEnabled(false);

        if (category.all) {
            statusLabel.setText(
                    "Εκτέλεση γενικής αναζήτησης...");
        } else {
            statusLabel.setText(
                    "Λήψη όλων των σελίδων της κατηγορίας...");
        }

        activeWorker = new SwingWorker<>() {

            @Override
            protected List<Listing> doInBackground()
                    throws Exception {

                java.util.function.Consumer<List<Listing>> emit = batch ->
                        SwingUtilities.invokeLater(() -> {
                            if (runId != mainSearchGeneration.get()) return;
                            tableModel.addListings(batch);
                            applyFilters();
                        });

                List<Listing> result;

                if (category.all) {
                    // Γενική αναζήτηση μέσω της μηχανής του site — εμφανίζει κάθε σελίδα αμέσως.
                    result = searchInsomniaAll(term, 100, Insomniakos.this::setStatus, emit);

                    // Άνοιγμα της κάθε αγγελίας για να διαβαστούν
                    // τα εξαρτήματα από το κείμενό της.
                    if (deep && !result.isEmpty()) {
                        enrichWithDetails(result, minPrice, maxPrice);
                    }
                } else {
                    // Κατηγορία: οι σελίδες και οι λεπτομέρειες των αγγελιών
                    // κατεβαίνουν ταυτόχρονα (pipeline), χωρίς αναμονή
                    // να τελειώσουν πρώτα όλες οι σελίδες.
                    result = searchEveryCategoryPage(
                            category, term, deep, minPrice, maxPrice, emit);
                }

                return result;
            }

            @Override
            protected void done() {
                try {
                    if (isCancelled()) {
                        return;
                    }

                    List<Listing> listings = get();

                    tableModel.setListings(listings);
                    applyFilters();

                    String extra = lastSummary.isEmpty()
                            ? ""
                            : " | " + lastSummary;

                    String dump = lastDumpPath.isEmpty()
                            ? ""
                            : " | Αρχείο: " + lastDumpPath;

                    statusLabel.setText(
                            statusLabel.getText() + extra + dump);

                    if (listings.isEmpty()) {
                        JOptionPane.showMessageDialog(
                                Insomniakos.this,
                                "Δεν βρέθηκαν αγγελίες που να "
                                        + "ταιριάζουν με τον όρο: " + term);
                    }

                } catch (Exception ex) {
                    if (!isCancelled()) {
                        statusLabel.setText("Σφάλμα αναζήτησης.");

                        JOptionPane.showMessageDialog(
                                Insomniakos.this,
                                "Σφάλμα:\n" + rootMessage(ex),
                                "Αναζήτηση",
                                JOptionPane.ERROR_MESSAGE);
                    }
                } finally {
                    searchButton.setEnabled(true);
                }
            }
        };

        activeWorker.execute();
    }

    // ---------------------------------------------------------
    // ΠΑΡΑΛΛΗΛΗ ΛΗΨΗ ΟΛΩΝ ΤΩΝ ΣΕΛΙΔΩΝ (Insomnia / Vendora / Vinted / Facebook)
    // ---------------------------------------------------------

    private interface DetailJob {
        void load(Listing l) throws Exception;
    }

    // Αρκεί να υπάρχουν στον τίτλο οι αριθμοί του όρου (π.χ. 1080), ακόμη κι αν λείπει το rtx/gtx.
    private boolean titleHasTermNumbers(String title, String term) {
        StringBuilder numbers = new StringBuilder();

        for (String tok : term.trim().split("[\\s\\-/]+")) {
            if (tok.matches(".*\\d.*") && !tok.matches("(?i)\\d+\\s?(gb|tb)")) {
                numbers.append(tok).append(' ');
            }
        }

        return numbers.length() > 0 && SoldTools.matchesKeyword(title, numbers.toString().trim());
    }

    private static class PlatformBatch {
        final String platform;
        final List<Listing> listings;
        final String note;

        PlatformBatch(String platform, List<Listing> listings, String note) {
            this.platform = platform;
            this.listings = listings;
            this.note = note == null ? "" : note;
        }
    }

    // Γενική αναζήτηση Insomnia: ΟΛΕΣ οι σελίδες αποτελεσμάτων (έως 100).
    private List<Listing> generalSearch(String keyword) throws Exception {
        return searchInsomniaAll(keyword, 100, this::setStatus);
    }

    /*
     * Η πρώτη σελίδα αποκαλύπτει την σελιδοποίηση· οι υπόλοιπες κατεβαίνουν
     * παράλληλα, σε "κύματα" THREADS σελίδων, μέχρι να μην προκύψει νέα αγγελία.
     */
    private List<Listing> searchInsomniaAll(String keyword, int maxPages,
                                            java.util.function.Consumer<String> progress) throws Exception {
        return searchInsomniaAll(keyword, maxPages, progress, null);
    }

    private List<Listing> searchInsomniaAll(String keyword, int maxPages,
                                            java.util.function.Consumer<String> progress,
                                            java.util.function.Consumer<List<Listing>> emit) throws Exception {
        final String base = SEARCH_URL + URLEncoder.encode(keyword, StandardCharsets.UTF_8);
        final String root = BASE_URL + "/classifieds/search/";
        Map<String, Listing> all = new LinkedHashMap<>();

        Document first = connectWithRetry(base);
        List<Listing> firstBatch = mergeListings(all, parseListings(first, "Γενική αναζήτηση"));
        if (emit != null && !firstBatch.isEmpty()) emit.accept(firstBatch);

        if (all.isEmpty()) {
            return new ArrayList<>();
        }

        PaginationInfo info = new PaginationInfo();
        info.update(findPaginationUrls(first, root));
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);

        try {
            int next = 2;

            while (next <= maxPages) {
                int limit = info.maxPage > 1 ? Math.min(maxPages, info.maxPage) : maxPages;

                if (next > limit) {
                    break;
                }

                int end = Math.min(next + THREADS - 1, limit);
                List<Future<Document>> futures = new ArrayList<>();

                for (int n = next; n <= end; n++) {
                    final String url = info.template != null
                            ? buildPageUrl(info.template, n)
                            : base + "&page=" + n;
                    futures.add(pool.submit(() -> connectWithRetry(url)));
                }

                int added = 0;

                for (Future<Document> f : futures) {
                    try {
                        Document d = f.get();
                        List<Listing> batch = mergeListings(all, parseListings(d, "Γενική αναζήτηση"));
                        added += batch.size();
                        if (emit != null && !batch.isEmpty()) emit.accept(batch);
                        info.update(findPaginationUrls(d, root));
                    } catch (ExecutionException ignored) {
                    }
                }

                progress.accept("Insomnia: σελίδες 1-" + end + " · " + all.size() + " αγγελίες...");

                if (added == 0) {
                    break;
                }

                next = end + 1;
            }
        } finally {
            pool.shutdownNow();
        }

        return new ArrayList<>(all.values());
    }

    private boolean isLastVendoraPage(Document doc) {
        Element next = doc.selectFirst("[data-next-page-url]");
        return next != null && next.attr("data-next-page-url").isBlank();
    }

    // Vendora: <κατηγορία>.html?q=<όρος>&page=N, σε παράλληλα κύματα σελίδων.
    private List<Listing> searchVendoraAll(String root, String keyword, String categoryName, int maxPages,
                                           java.util.function.Consumer<String> progress) throws Exception {
        return searchVendoraAll(root, keyword, categoryName, maxPages, progress, null);
    }

    private List<Listing> searchVendoraAll(String root, String keyword, String categoryName, int maxPages,
                                           java.util.function.Consumer<String> progress,
                                           java.util.function.Consumer<List<Listing>> emit) throws Exception {
        final String q = URLEncoder.encode(keyword, StandardCharsets.UTF_8).replace("+", "%20");
        final String base = root + "?q=" + q + "&page=";
        Map<String, Listing> all = new LinkedHashMap<>();

        Document first = connectWithRetry(base + 1);
        List<Listing> firstBatch = mergeListings(all, parseVendoraListings(first, categoryName));
        if (emit != null && !firstBatch.isEmpty()) emit.accept(firstBatch);

        if (all.isEmpty()) {
            return new ArrayList<>();
        }

        boolean ended = isLastVendoraPage(first);
        ExecutorService pool = Executors.newFixedThreadPool(VENDORA_THREADS);

        try {
            int next = 2;

            while (!ended && next <= maxPages) {
                int end = Math.min(next + VENDORA_THREADS - 1, maxPages);
                List<Future<Document>> futures = new ArrayList<>();

                for (int n = next; n <= end; n++) {
                    final String url = base + n;
                    futures.add(pool.submit(() -> connectWithRetry(url)));
                }

                int added = 0;

                for (Future<Document> f : futures) {
                    try {
                        Document d = f.get();
                        List<Listing> batch = mergeListings(all, parseVendoraListings(d, categoryName));
                        added += batch.size();
                        if (emit != null && !batch.isEmpty()) emit.accept(batch);
                        if (isLastVendoraPage(d)) ended = true;
                    } catch (ExecutionException ignored) {
                    }
                }

                progress.accept("Vendora: σελίδες 1-" + end + " · " + all.size() + " αγγελίες...");

                if (added == 0) {
                    break;
                }

                next = end + 1;
                Thread.sleep(VENDORA_PAGE_DELAY_MS);
            }
        } finally {
            pool.shutdownNow();
        }

        return new ArrayList<>(all.values());
    }

    // Vinted: η πρώτη σελίδα δίνει το πλήθος σελίδων, οι υπόλοιπες κατεβαίνουν όλες παράλληλα.
    private List<Listing> searchVintedAll(String keyword, List<String> filters, int maxPages, String categoryName,
                                          java.util.function.Consumer<String> progress) throws Exception {
        return searchVintedAll(keyword, filters, maxPages, categoryName, progress, null);
    }

    private List<Listing> searchVintedAll(String keyword, List<String> filters, int maxPages, String categoryName,
                                          java.util.function.Consumer<String> progress,
                                          java.util.function.Consumer<List<Listing>> emit) throws Exception {
        final String q = URLEncoder.encode(keyword, StandardCharsets.UTF_8).replace("+", "%20");
        final StringBuilder fq = new StringBuilder();

        for (String pair : filters) {
            fq.append('&').append(VintedTools.encodePair(pair));
        }

        final String pageBase = VINTED_CATALOG_URL + "?search_text=" + q + fq + "&page=";
        Map<String, Listing> all = new LinkedHashMap<>();

        VintedPage first = parseVintedPage(vintedConnect(pageBase + 1), categoryName);
        vintedTranslateTitles(first.listings);
        List<Listing> firstBatch = mergeListings(all, first.listings);
        if (emit != null && !firstBatch.isEmpty()) emit.accept(firstBatch);

        if (all.isEmpty()) {
            return new ArrayList<>();
        }

        int last = first.totalPages > 0 ? Math.min(first.totalPages, maxPages) : 1;

        if (last > 1) {
            ExecutorService pool = Executors.newFixedThreadPool(Math.min(VINTED_THREADS, last - 1));

            try {
                List<Future<VintedPage>> futures = new ArrayList<>();

                for (int n = 2; n <= last; n++) {
                    final int page = n;
                    futures.add(pool.submit(() -> {
                        VintedPage vp = parseVintedPage(vintedConnect(pageBase + page), categoryName);
                        vintedTranslateTitles(vp.listings);
                        return vp;
                    }));
                }

                int done = 1;

                for (Future<VintedPage> f : futures) {
                    try {
                        List<Listing> batch = mergeListings(all, f.get().listings);
                        if (emit != null && !batch.isEmpty()) emit.accept(batch);
                    } catch (ExecutionException ignored) {
                    }

                    done++;
                    progress.accept("Vinted: σελίδες " + done + "/" + last + " · " + all.size() + " αγγελίες...");
                }
            } finally {
                pool.shutdownNow();
            }
        }

        return new ArrayList<>(all.values());
    }

    // ---------------- Facebook Marketplace: πολλές αναζητήσεις αντί για μία σελίδα ----------------
    //
    // Το Facebook δίνει ~24 αγγελίες ανά αναζήτηση και η "επόμενη σελίδα" θέλει συνδεδεμένη συνεδρία
    // (GraphQL). Για να βγουν πολύ περισσότερες αγγελίες, η αναζήτηση σπάει σε ζώνες τιμής
    // (0-10, 10-20, 20-35 ... €) × ταξινομήσεις. Όποια ζώνη γεμίσει (≥ FB_PAGE_FULL αγγελίες)
    // σπάει περαιτέρω (μισή ζώνη + άλλη ταξινόμηση), μέχρι να εξαντληθεί ο προϋπολογισμός αιτημάτων.

    private static final int FB_PAGE_FULL = 18;
    private static final int FB_THREADS = 3;
    // Μέγιστος αριθμός αιτημάτων ανά αναζήτηση (≈1 αίτημα/δευτερόλεπτο).
    private static final int FB_BUDGET = 100;
    private static final double[] FB_BANDS = {0, 10, 20, 35, 50, 75, 100, 150, 200, 300, 500, 800, 1200, 2000, 5000};
    private static final String FB_NEWEST = "creation_time_descend";

    private static final class FbQuery {
        final String place;
        final double lo;
        final double hi;
        final String sort;

        FbQuery(String place, double lo, double hi, String sort) {
            this.place = place;
            this.lo = lo;
            this.hi = hi;
            this.sort = sort;
        }

        String key() {
            return place + "|" + lo + "|" + hi + "|" + sort;
        }
    }

    private static final class FbPage {
        final List<Listing> listings;
        final boolean loginWall;
        // Πόσες αγγελίες γύρισε πραγματικά η σελίδα (πριν αφαιρεθούν οι πωλημένες) και το εύρος τιμών της.
        final int raw;
        final double minSeen;
        final double maxSeen;

        FbPage(List<Listing> listings, boolean loginWall, int raw, double minSeen, double maxSeen) {
            this.listings = listings;
            this.loginWall = loginWall;
            this.raw = raw;
            this.minSeen = minSeen;
            this.maxSeen = maxSeen;
        }
    }

    private FbPage fetchFacebookQuery(FbQuery q, String term, List<String> fixed) throws Exception {
        List<String> params = new ArrayList<>(fixed);

        if (q.lo > 0) params.add("minPrice=" + VintedTools.plainNumber(q.lo));
        if (!Double.isInfinite(q.hi)) params.add("maxPrice=" + VintedTools.plainNumber(q.hi));
        params.add("sortBy=" + q.sort);

        String html = facebookFetch(FacebookTools.searchUrl(q.place, term, params));
        List<Listing> listings = facebookListings(html, "Facebook Marketplace");
        int raw = listings.size();
        double lowest = Double.POSITIVE_INFINITY;
        double highest = Double.NEGATIVE_INFINITY;

        for (Listing l : listings) {
            if (l.price != null) {
                lowest = Math.min(lowest, l.price);
                highest = Math.max(highest, l.price);
            }
        }

        // Οι πωλημένες αφαιρούνται μόνο όταν ο χρήστης δεν ζήτησε «Πωλημένα» (availability=...).
        boolean wantsAvailability = false;
        for (String p : fixed) {
            if (p.startsWith("availability=")) wantsAvailability = true;
        }
        if (!wantsAvailability) listings.removeIf(l -> l.sold);

        return new FbPage(listings, FacebookTools.looksLikeLoginWall(html), raw, lowest, highest);
    }

    private void fbRefine(FbQuery q, FbPage pg, Deque<FbQuery> queue, Set<String> queued) {
        List<FbQuery> more = new ArrayList<>();

        if (q.sort.equals("price_ascend")) {
            // «Σελιδοποίηση» με βάση την τιμή: η επόμενη αναζήτηση ξεκινά από την ακριβότερη τιμή που είδαμε.
            if (!Double.isInfinite(pg.maxSeen)) {
                double next = pg.maxSeen > q.lo ? pg.maxSeen : q.lo + 1;
                if (next < q.hi) more.add(new FbQuery(q.place, next, q.hi, "price_ascend"));
            }
        } else if (q.sort.equals("price_descend")) {
            if (!Double.isInfinite(pg.minSeen)) {
                double next = pg.minSeen < q.hi ? pg.minSeen : q.hi - 1;
                if (next > q.lo) more.add(new FbQuery(q.place, q.lo, next, "price_descend"));
            }
        } else {
            more.add(new FbQuery(q.place, q.lo, q.hi, "price_ascend"));
            more.add(new FbQuery(q.place, q.lo, q.hi, "price_descend"));

            double mid = Double.isInfinite(q.hi)
                    ? Math.max(q.lo * 2, 10000)
                    : Math.round((q.lo + q.hi) / 2.0);

            if (mid > q.lo && mid < q.hi) {
                more.add(new FbQuery(q.place, q.lo, mid, FB_NEWEST));
                more.add(new FbQuery(q.place, mid, q.hi, FB_NEWEST));
            }
        }

        for (FbQuery m : more) {
            if (queued.add(m.key())) {
                queue.add(m);
            }
        }
    }

    /*
     * places: ένα ή περισσότερα ID πόλεων / URL Marketplace (με κόμμα).
     * baseParams: φίλτρα Marketplace (radius, itemCondition, minPrice, maxPrice ...). Το sortBy αγνοείται.
     * budget: μέγιστος αριθμός αιτημάτων (το Facebook επιτρέπεται ~1 αίτημα/δευτερόλεπτο).
     * emit: καλείται με τις νέες αγγελίες μετά από κάθε κύμα (μπορεί να είναι null).
     */
    private PlatformBatch facebookWideSearch(String places, String term, List<String> baseParams, int budget,
                                             java.util.function.Consumer<String> progress,
                                             java.util.function.Consumer<List<Listing>> emit,
                                             boolean exact) throws Exception {
        List<String> fixed = new ArrayList<>();
        Double userMin = null;
        Double userMax = null;

        for (String p : baseParams) {
            if (p.startsWith("minPrice=")) userMin = parseOptionalPrice(p.substring(9));
            else if (p.startsWith("maxPrice=")) userMax = parseOptionalPrice(p.substring(9));
            else if (!p.startsWith("sortBy=")) fixed.add(p);
        }

        final double uMin = userMin == null ? 0 : userMin;
        final double uMax = userMax == null ? Double.POSITIVE_INFINITY : userMax;

        List<String> placeList = new ArrayList<>();

        for (String pl : (places == null ? "" : places).split("[,;\\n\\r]+")) {
            if (!pl.isBlank() && !placeList.contains(pl.trim())) placeList.add(pl.trim());
        }

        if (placeList.isEmpty()) {
            placeList.add(FacebookTools.DEFAULT_CITY);
        }

        Deque<FbQuery> queue = new ArrayDeque<>();
        Set<String> queued = new HashSet<>();

        for (String place : placeList) {
            for (int i = 0; i < FB_BANDS.length; i++) {
                double lo = FB_BANDS[i];
                double hi = i + 1 < FB_BANDS.length ? FB_BANDS[i + 1] : Double.POSITIVE_INFINITY;

                if (hi <= uMin || lo >= uMax) continue;

                FbQuery fq = new FbQuery(place, Math.max(lo, uMin), Math.min(hi, uMax), FB_NEWEST);

                if (queued.add(fq.key())) queue.add(fq);
            }
        }

        Map<String, Listing> all = new LinkedHashMap<>();
        int used = 0;
        int failed = 0;
        int consecutiveFails = 0;
        int staleWaves = 0;
        boolean loginWall = false;
        String firstError = "";
        ExecutorService pool = Executors.newFixedThreadPool(FB_THREADS);

        try {
            while (!queue.isEmpty() && used < budget) {
                List<FbQuery> wave = new ArrayList<>();

                while (!queue.isEmpty() && wave.size() < FB_THREADS * 2 && used + wave.size() < budget) {
                    wave.add(queue.poll());
                }

                List<Future<FbPage>> futures = new ArrayList<>();

                for (FbQuery q : wave) {
                    futures.add(pool.submit(() -> fetchFacebookQuery(q, term, fixed)));
                }

                used += wave.size();
                List<Listing> fresh = new ArrayList<>();

                for (int i = 0; i < wave.size(); i++) {
                    try {
                        FbPage pg = futures.get(i).get();
                        consecutiveFails = 0;
                        boolean full = pg.raw >= FB_PAGE_FULL;

                        if (pg.listings.isEmpty() && pg.loginWall) loginWall = true;

                        List<Listing> usable = new ArrayList<>(pg.listings);

                        if (exact) {
                            usable.removeIf(l -> !matchesAllWords(l.title, term) && !titleHasTermNumbers(l.title, term));
                        }

                        fresh.addAll(mergeListings(all, usable));

                        if (full) fbRefine(wave.get(i), pg, queue, queued);
                    } catch (ExecutionException ex) {
                        failed++;
                        consecutiveFails++;
                        if (firstError.isEmpty()) firstError = rootMessage(ex);
                    }
                }

                if (emit != null && !fresh.isEmpty()) {
                    emit.accept(fresh);
                }

                progress.accept("Facebook: αναζήτηση " + used + "/" + Math.min(budget, used + queue.size())
                        + " · " + all.size() + " αγγελίες...");

                if (all.isEmpty() && loginWall) break;
                if (consecutiveFails >= 4) break;

                // Αν τρία συνεχόμενα κύματα δεν έφεραν καμία νέα αγγελία, δεν έχει νόημα να συνεχίσουμε.
                staleWaves = fresh.isEmpty() ? staleWaves + 1 : 0;
                if (staleWaves >= 3) break;
            }
        } finally {
            pool.shutdownNow();
        }

        String note = used + " αναζητήσεις" + (failed > 0 ? ", " + failed + " απέτυχαν" : "");

        if (all.isEmpty()) {
            note = loginWall
                    ? "το Facebook ζητά σύνδεση — βάλε Cookie συνεδρίας"
                    : failed > 0 ? "το Facebook μπλόκαρε τα αιτήματα (" + firstError + ")"
                    : "καμία αγγελία";
        }

        return new PlatformBatch("Facebook", new ArrayList<>(all.values()), note);
    }

    private void setStatus(String message) {
        SwingUtilities.invokeLater(
                () -> statusLabel.setText(message)
        );
    }

    // ---------------------------------------------------------
    // ΛΕΠΤΟΜΕΡΕΙΕΣ ΑΓΓΕΛΙΑΣ (specs από το κείμενο)
    // ---------------------------------------------------------

    private boolean inRange(Listing l, Double min, Double max) {
        boolean ranged = min != null || max != null;

        if (ranged && l.price == null) {
            return false;
        }

        if (min != null && l.price != null && l.price < min) {
            return false;
        }

        return max == null || l.price == null || l.price <= max;
    }

    /*
     * Ανοίγει κάθε αγγελία (παράλληλα) και αναλύει το κείμενό της.
     * Χρησιμοποιείται τόσο στη γενική αναζήτηση όσο και στις
     * αναζητήσεις συγκεκριμένης κατηγορίας.
     */
    private void enrichWithDetails(
            List<Listing> listings,
            Double min,
            Double max) throws Exception {

        List<Listing> targets = new ArrayList<>();

        // Κάθε αγγελία εμπλουτίζεται μόνο μία φορά. Τα φίλτρα απλώς
        // κρύβουν/εμφανίζουν τις ίδιες Listing objects, χωρίς νέο download.
        for (Listing listing : listings) {
            if (!listing.detailsLoaded) {
                targets.add(listing);
            }
        }

        if (targets.isEmpty()) {
            return;
        }

        final int total = targets.size();
        final AtomicInteger done = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);

        try {
            List<Callable<Void>> tasks = new ArrayList<>();

            for (Listing l : targets) {
                tasks.add(() -> {
                    enrichOne(l);

                    setStatus("Λήψη λεπτομερειών: "
                            + done.incrementAndGet()
                            + " / " + total + "...");

                    return null;
                });
            }

            pool.invokeAll(tasks);

        } finally {
            pool.shutdownNow();
        }
    }

    // Κατεβάζει μία αγγελία και ενημερώνει περιγραφή + specs.
    // Αν αποτύχει, μένουν τα specs του τίτλου.
    private void enrichOne(Listing l) {
        try {
            fetchFullDescription(l);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
        }
    }

    private void fetchFullDescription(Listing l) throws Exception {
        // Αν έχει ήδη γίνει προσπάθεια, χρησιμοποίησε τα αποθηκευμένα
        // δεδομένα. Το applyFilters() δεν πρέπει να ξαναφορτώνει αγγελίες.
        if (l.detailsLoaded) {
            return;
        }

        Document doc = connectWithRetry(l.url);
        String body = extractBodyText(doc).trim();

        // Ορισμένες σελίδες δεν εκθέτουν την περιγραφή στους συνηθισμένους
        // selectors. Σε αυτή την περίπτωση κρατάμε το snippet της αρχικής
        // κάρτας αντί να αποτυγχάνει η αγγελία ή να χάνεται το αρχικό κείμενο.
        if (body.isBlank()) {
            body = l.description == null ? "" : l.description.trim();
        }

        l.fullDescription = body;
        if (!body.isBlank()) {
            l.description = body;
        }
        l.specs = SpecParser.parse(l.title, body.isBlank() ? l.description : body);

        if (!l.laptop && isLaptop(l.title + " " + body)) {
            l.laptop = true;
        }
        l.detailsLoaded = true;
    }

    /*
     * Βρίσκει το κείμενο της περιγραφής της αγγελίας.
     * Δοκιμάζονται με σειρά οι πιθανοί περιέχοντες, ώστε να μη
     * διαβάζονται μενού και "παρόμοιες αγγελίες".
     */
    private String extractBodyText(Document doc) {
        String[] selectors = {
                "[data-role=commentContent]",
                ".cFileDescription",
                ".classifieds-data-item-description",
                "[itemprop=description]",
                "[data-ipsEditor]",
                ".ipsType_richText",
                ".ipsType_normal.ipsType_richText",
                "[class*=description]"
        };

        for (String selector : selectors) {
            for (Element element : doc.select(selector)) {
                String text = element.text().trim();
                if (!text.isBlank() && text.length() > 12) {
                    // Ignore descriptions nested in navigation/related-listing areas.
                    if (element.closest("nav, header, footer, .ipsPagination, .ipsWidget") != null) {
                        continue;
                    }
                    String extracted = htmlToLines(element.html()).trim();
                    if (!extracted.isBlank()) return extracted;
                }
            }
        }

        // Fallback σε περιγραφές που είναι αποθηκευμένες μόνο στα meta tags.
        for (Element meta : doc.select("meta[property=og:description], meta[name=description], meta[name=twitter:description]")) {
            String content = meta.attr("content").trim();
            if (content.length() > 12) return content;
        }

        // Last resort: remove page chrome and extract remaining main-area text.
        Element main = doc.selectFirst("#ipsLayout_mainArea");
        if (main != null) {
            main.select("nav, header, footer, .ipsPagination, .ipsWidget, "
                    + ".ipsDataList, .insAdvertsList, .insSrCard, script, style").remove();
            String fallback = main.text().trim();
            if (!fallback.isBlank()) return fallback;
        }
        return "";
    }

    private String htmlToLines(String html) {
        String text = html
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(p|div|li|h[1-6]|tr|ul|ol)>", "\n")
                .replaceAll("(?s)<[^>]+>", " ");

        text = Parser.unescapeEntities(text, false);

        return text
                .replace('\u00A0', ' ')
                .replaceAll("[ \\t]+", " ");
    }

    // ---------------------------------------------------------
    // ΠΑΡΑΛΛΗΛΗ ΛΗΨΗ ΣΕΛΙΔΩΝ ΚΑΤΗΓΟΡΙΑΣ
    // ---------------------------------------------------------

    /*
     * Αποτέλεσμα μιας σελίδας: αγγελίες + σύνδεσμοι σελιδοποίησης.
     */
    private static class PageResult {
        final List<Listing> listings;
        final Set<String> pagination;
        final boolean hasItemLinks;

        PageResult(
                List<Listing> listings,
                Set<String> pagination,
                boolean hasItemLinks) {

            this.listings = listings;
            this.pagination = pagination;
            this.hasItemLinks = hasItemLinks;
        }
    }

    /*
     * Κρατά το μεγαλύτερο νούμερο σελίδας που έχει εμφανιστεί
     * και ένα URL-πρότυπο, ώστε να φτιάχνουμε απευθείας τα URL
     * των υπόλοιπων σελίδων χωρίς να τις ανακαλύπτουμε μία-μία.
     */
    private class PaginationInfo {
        volatile int maxPage = 1;
        volatile String template = null;

        void update(Set<String> urls) {
            for (String url : urls) {
                int number = pageNumberOf(url);

                if (number > maxPage && number <= MAX_PAGES) {
                    maxPage = number;
                    template = removeFragmentOnly(url);
                }
            }
        }
    }

    private Document connectWithRetry(String url) throws Exception {
        Exception last = null;

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                return connect(url);
            } catch (InterruptedException ie) {
                throw ie;
            } catch (Exception ex) {
                last = ex;

                if (attempt < MAX_RETRIES) {
                    Thread.sleep(700L * (attempt + 1));
                }
            }
        }

        throw last;
    }

    private PageResult fetchPage(
            String url,
            String categoryName,
            String categoryRoot) throws Exception {

        Document doc = connectWithRetry(url);

        boolean hasItemLinks =
                !doc.select("a[href*='/classifieds/item/']").isEmpty();

        return new PageResult(
                parseListings(doc, categoryName),
                findPaginationUrls(doc, categoryRoot),
                hasItemLinks);
    }

    private String buildPageUrl(String template, int number) {
        if (template.matches(".*/page/\\d+.*")) {
            return template.replaceFirst(
                    "/page/\\d+", "/page/" + number);
        }

        return template.replaceFirst(
                "([?&])page=\\d+", "$1page=" + number);
    }

    // Προσθέτει τις νέες αγγελίες και επιστρέφει ΜΟΝΟ όσες δεν υπήρχαν ήδη.
    private List<Listing> mergeListings(
            Map<String, Listing> all,
            List<Listing> listings) {

        List<Listing> added = new ArrayList<>();

        for (Listing listing : listings) {
            if (all.putIfAbsent(
                    removeQueryAndFragment(listing.url),
                    listing) == null) {
                added.add(listing);
            }
        }

        return added;
    }

    /*
     * 1. Κατεβάζει την πρώτη σελίδα και βρίσκει πόσες σελίδες υπάρχουν.
     * 2. Κάθε σελίδα που ανακαλύπτεται μπαίνει ΑΜΕΣΩΣ στην ουρά
     *    (δεν περιμένει να τελειώσει "γύρος" σελίδων).
     * 3. Κάθε αγγελία που ταιριάζει με τον όρο στέλνεται αμέσως για
     *    λήψη λεπτομερειών στο ίδιο pool, ενώ ακόμα κατεβαίνουν σελίδες.
     * 4. Στο τέλος αποθηκεύονται όλες οι αγγελίες σε txt.
     */
    private List<Listing> searchEveryCategoryPage(
            Category category,
            String keyword,
            boolean deep,
            Double min,
            Double max,
            java.util.function.Consumer<List<Listing>> emit) throws Exception {

        LinkedHashMap<String, Listing> all =
                new LinkedHashMap<>();

        final String categoryRoot =
                removeQueryAndFragment(category.url);

        Set<Integer> seenPageNumbers = new HashSet<>();
        seenPageNumbers.add(1);

        setStatus("Λήψη πρώτης σελίδας...");

        PageResult first;

        try {
            first = fetchPage(categoryRoot, category.name, categoryRoot);
        } catch (InterruptedException ie) {
            throw ie;
        } catch (Exception ex) {
            throw new IOException(
                    "Αποτυχία ανάγνωσης της σελίδας:\n"
                            + categoryRoot + "\n" + ex.getMessage(),
                    ex);
        }

        if (!first.hasItemLinks) {
            throw new IOException(
                    "Δεν εντοπίστηκαν σύνδεσμοι αγγελιών στη σελίδα: "
                            + categoryRoot
                            + "\nΤο site μπορεί να επέστρεψε διαφορετικό HTML "
                            + "ή να μπλόκαρε το αίτημα.");
        }

        final PaginationInfo info = new PaginationInfo();

        List<Listing> filtered = new ArrayList<>();

        final AtomicInteger pagesDone = new AtomicInteger(1);

        int pagesFetched = 1;
        int failedPages = 0;
        int pending = 0;

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        ExecutorCompletionService<PageResult> pages =
                new ExecutorCompletionService<>(pool);

        try {
            PageResult current = first;

            while (true) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException(
                            "Η αναζήτηση ακυρώθηκε.");
                }

                // Νέες αγγελίες αυτής της σελίδας εμφανίζονται αμέσως στον πίνακα.
                List<Listing> pageMatches = new ArrayList<>();
                for (Listing l : mergeListings(all, current.listings)) {
                    if (!matchesAllWords(l.title, keyword)) {
                        continue;
                    }
                    filtered.add(l);
                    pageMatches.add(l);
                }
                if (emit != null && !pageMatches.isEmpty()) emit.accept(pageMatches);

                // Νέες σελίδες που μόλις ανακαλύφθηκαν -> αμέσως στην ουρά.
                info.update(current.pagination);

                int limit = Math.min(info.maxPage, MAX_PAGES);

                for (int n = 2; n <= limit && info.template != null; n++) {
                    if (!seenPageNumbers.add(n)) {
                        continue;
                    }

                    final String pageUrl = buildPageUrl(info.template, n);

                    pages.submit(() -> fetchPage(
                            pageUrl, category.name, categoryRoot));

                    pending++;
                }

                if (pending == 0) {
                    break;
                }

                Future<PageResult> future = pages.take();
                pending--;

                try {
                    current = future.get();
                    pagesFetched++;
                    pagesDone.incrementAndGet();

                    setStatus("Σελίδες: " + pagesDone.get()
                            + " / " + info.maxPage + "...");

                } catch (ExecutionException ex) {
                    failedPages++;
                    current = new PageResult(
                            Collections.emptyList(),
                            Collections.emptySet(),
                            true);
                }
            }

        } finally {
            pool.shutdownNow();
        }

        // Χρησιμοποιούμε ΑΚΡΙΒΩΣ τον ίδιο μηχανισμό λεπτομερειών με
        // την επιλογή «Όλες οι Κατηγορίες»: πρώτα ολοκληρώνεται η
        // συλλογή των αποτελεσμάτων και μετά ανοίγει κάθε αγγελία.
        // Έτσι οι λήψεις περιγραφών δεν ανταγωνίζονται τις λήψεις σελίδων
        // κατηγορίας στο ίδιο thread pool.
        if (deep && !filtered.isEmpty()) {
            enrichWithDetails(filtered, min, max);
        }

        saveToTextFile(category, all.values());

        lastSummary = "Σελίδες: " + pagesFetched
                + (failedPages > 0
                ? " (απέτυχαν " + failedPages + ")"
                : "")
                + ", αγγελίες: " + all.size()
                + ", ταιριάζουν: " + filtered.size();

        return filtered;
    }

    /*
     * Επιστρέφει true αν ΟΛΕΣ οι λέξεις του όρου υπάρχουν
     * στο κείμενο (χωρίς τόνους / κεφαλαία, με διαχωρισμό
     * σε κενά, παύλες και κάθετους).
     */
    private boolean matchesAllWords(String text, String keyword) {
        String haystack = normalize(text);

        for (String word : normalize(keyword).split("[\\s\\-/]+")) {
            if (!word.isBlank() && !haystack.contains(word)) {
                return false;
            }
        }

        return true;
    }

    private void saveToTextFile(
            Category category,
            Collection<Listing> listings) {

        String fileName = "insomnia_"
                + category.name.replaceAll("[^\\p{L}\\p{N}]+", "_")
                + ".txt";

        File file = new File(fileName).getAbsoluteFile();

        try (PrintWriter out = new PrintWriter(
                new OutputStreamWriter(
                        new FileOutputStream(file),
                        StandardCharsets.UTF_8))) {

            for (Listing l : listings) {
                out.println("Τίτλος: " + l.title
                        + " | Τιμή: " + (l.price == null ? "-" : l.price + " €")
                        + " | Κατάσταση: " + l.condition
                        + " | Τύπος: " + l.transaction
                        + " | Πωλητής: " + l.seller
                        + " | Κατηγορία: " + l.category
                        + " | URL: " + l.url
                        + " | Περιγραφή: " + (l.fullDescription.isBlank()
                        ? l.description : l.fullDescription));
            }

            lastDumpPath = file.getPath();

        } catch (IOException ex) {
            lastDumpPath = "";
            ex.printStackTrace();
        }
    }

    private int pageNumberOf(String url) {
        try {
            URI uri = URI.create(url);

            String path = uri.getPath() == null ? "" : uri.getPath();
            Matcher pm = PAGE_IN_PATH.matcher(path);

            if (pm.find()) {
                return Integer.parseInt(pm.group(1));
            }

            String query = uri.getQuery() == null ? "" : uri.getQuery();
            Matcher qm = PAGE_IN_QUERY.matcher(query);

            if (qm.find()) {
                return Integer.parseInt(qm.group(1));
            }
        } catch (Exception ignored) {
        }

        return 1;
    }

    /*
     * Εντοπίζει τους συνδέσμους σελιδοποίησης της ίδιας κατηγορίας
     * (/page/N/ ή ?page=N ή κουμπί "Επόμενο").
     */
    private Set<String> findPaginationUrls(
            Document doc,
            String categoryRoot) {

        Set<String> results = new LinkedHashSet<>();

        String rootPath = pathWithoutTrailingSlash(
                URI.create(categoryRoot).getPath());

        Elements links = doc.select(
                ".ipsPagination a, "
                        + "a[rel=next], "
                        + "a[href*='/page/'], "
                        + "a[href*='page=']");

        if (links.isEmpty()) {
            links = doc.select("a[href]");
        }

        for (Element link : links) {
            String href = link.absUrl("href");

            if (href.isBlank()) {
                continue;
            }

            URI uri;

            try {
                uri = URI.create(href);
            } catch (Exception ex) {
                continue;
            }

            if (uri.getHost() == null
                    || !(uri.getHost().equalsIgnoreCase("www.insomnia.gr")
                    || uri.getHost().equalsIgnoreCase("insomnia.gr"))) {
                continue;
            }

            String path = uri.getPath();

            if (path == null) {
                continue;
            }

            String normalizedPath = pathWithoutTrailingSlash(path);

            // Η διαδρομή πρέπει να ανήκει στην ίδια κατηγορία.
            if (!normalizedPath.equals(rootPath)
                    && !normalizedPath.startsWith(rootPath + "/")) {
                continue;
            }

            String lowerPath = normalizedPath.toLowerCase(Locale.ROOT);
            String query = uri.getQuery() == null
                    ? ""
                    : uri.getQuery().toLowerCase(Locale.ROOT);

            boolean pathPagination =
                    lowerPath.matches(".*/page/\\d+");

            boolean queryPagination =
                    query.matches(".*(?:^|&)page=\\d+(?:&.*)?");

            String linkText = normalize(link.text());

            boolean nextLink =
                    link.hasClass("ipsPagination_next")
                            || link.attr("rel").equalsIgnoreCase("next")
                            || linkText.equals("επομενο")
                            || linkText.equals("next");

            if (pathPagination || queryPagination || nextLink) {
                results.add(href);
            }
        }

        return results;
    }

    // ---------------------------------------------------------
    // ΔΙΑΒΑΣΜΑ ΑΓΓΕΛΙΩΝ ΑΠΟ ΛΙΣΤΑ
    // ---------------------------------------------------------

    /*
     * Σε κάθε κάρτα υπάρχουν δύο σύνδεσμοι προς την αγγελία:
     * ο πρώτος είναι η εικόνα (χωρίς κείμενο) και ο δεύτερος ο τίτλος
     * μέσα στο <h4>. Γι' αυτό ο τίτλος αναζητείται με σειρά
     * προτεραιότητας και απορρίπτονται οι σύνδεσμοι χωρίς κείμενο.
     */
    private Element findTitleLink(Element card) {
        String[] selectors = {
                "h4 a[href*='/classifieds/item/']",
                "h3.insSrCard__title a[href*='/classifieds/item/']",
                "h3 a[href*='/classifieds/item/']",
                "h2 a[href*='/classifieds/item/']"
        };

        for (String selector : selectors) {
            for (Element link : card.select(selector)) {
                if (!link.text().isBlank()) {
                    return link;
                }
            }
        }

        for (Element link : card.select("a[href*='/classifieds/item/']")) {
            if (!link.text().isBlank()) {
                return link;
            }
        }

        return null;
    }

    private List<Listing> parseListings(
            Document doc,
            String categoryName) {

        List<Listing> results = new ArrayList<>();

        Elements cards = doc.select(
                "li.insAdvertsList, li.insSrCard, article.insSrCard, .insSrCard");

        if (cards.isEmpty()) {
            // Εφεδρική λύση: βρίσκουμε τους συνδέσμους αγγελιών
            // και παίρνουμε το κοντινότερο κοντέινερ.
            Elements itemLinks = doc.select("a[href*='/classifieds/item/']");
            Set<Element> parents = new LinkedHashSet<>();

            for (Element link : itemLinks) {
                Element container = link;

                for (int i = 0; i < 8 && container.parent() != null; i++) {
                    container = container.parent();

                    if (container.hasClass("insAdvertsList")
                            || container.hasClass("insSrCard")
                            || container.hasClass("ipsDataItem")
                            || container.tagName().equals("li")
                            || container.tagName().equals("article")) {
                        break;
                    }
                }

                parents.add(container);
            }

            cards = new Elements(parents);
        }

        Set<String> seenUrls = new HashSet<>();

        for (Element card : cards) {
            Element titleLink = findTitleLink(card);

            if (titleLink == null) {
                continue;
            }

            String title = titleLink.text().trim();
            String url = titleLink.absUrl("href").trim();

            if (url.isBlank()) {
                url = titleLink.attr("href").trim();

                if (url.startsWith("//")) {
                    url = "https:" + url;
                } else if (url.startsWith("/")) {
                    url = BASE_URL + url;
                }
            }

            if (title.isBlank() || url.isBlank()
                    || !url.contains("/classifieds/item/")) {
                continue;
            }

            url = removeQueryAndFragment(url);

            if (!seenUrls.add(url)) {
                continue;
            }

            Element snippetElement = card.selectFirst(
                    ".insSrCard__snippet, .classifieds-data-item-description, "
                            + ".ipsType_richText, .ipsTruncate");
            String snippet = snippetElement == null
                    ? ""
                    : snippetElement.text().trim();

            Element sellerElement = card.selectFirst(
                    "a.ipsUsername, .insSrCard__seller, "
                            + ".classifieds-data-item-author a[href*='/profile/']");
            String seller = sellerElement == null
                    ? ""
                    : sellerElement.text().trim();

            Element priceElement = card.selectFirst(
                    "p.cFilePrice span.cFilePrice, p.cFilePrice, "
                            + ".insSrCard__price, .classifieds-data-item-price, "
                            + ".ipsPrice, [itemprop=price], [data-price], [class*='price']");
            String priceText = priceElement == null
                    ? ""
                    : (priceElement.hasAttr("content") ? priceElement.attr("content")
                    : priceElement.hasAttr("data-price") ? priceElement.attr("data-price")
                    : priceElement.text().trim());
            Double price = parsePrice(priceText);

            Element conditionElement = card.selectFirst(
                    "li.insClassifiedsCondition span, "
                            + "span.insSrCard__pill, "
                            + ".insSrCard__pill");
            String condition = conditionElement == null
                    ? extractCondition(card)
                    : conditionElement.text().trim().toUpperCase(Locale.ROOT);

            String transaction = detectTransaction(card.text());

            boolean laptop = isLaptop(title + " " + snippet);

            // Αρχικά τα specs βγαίνουν μόνο από τον τίτλο.
            // Αν είναι ενεργό το "Specs από την αγγελία", θα
            // αντικατασταθούν από το πλήρες κείμενο της αγγελίας.
            Specs specs = SpecParser.parse(title, snippet);

            Element categoryElement = card.selectFirst("a[href*='/classifieds/category/']");
            String actualCategory = categoryElement == null || categoryElement.text().isBlank()
                    ? categoryName : categoryElement.text().trim();

            Listing listing = new Listing(
                    title,
                    price,
                    specs,
                    seller,
                    condition,
                    transaction,
                    actualCategory,
                    url,
                    laptop);
            listing.description = snippet;
            listing.flaggedKeywords = SpecParser.hasSuspiciousKeywords(title, snippet);
            results.add(listing);
        }

        return results;
    }

    // ---------------------------------------------------------
    // VENDORA.GR
    // ---------------------------------------------------------

    // Κάρτες αγγελιών της Vendora: <a class="card vCard card-product" href=".../items/ID/slug.html">
    private List<Listing> parseVendoraListings(Document doc, String categoryName) {
        List<Listing> results = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (Element card : doc.select("a.card-product[href*='/items/']")) {
            // Τα «σκελετοί» placeholders της σελίδας δεν είναι αγγελίες.
            if (card.hasClass("shimmer-loading")) {
                continue;
            }

            String url = cleanVendoraUrl(card.attr("href"));

            if (url.isEmpty() || !seen.add(url)) {
                continue;
            }

            Element titleElement = card.selectFirst("p.title .body-m, .title .body-m, .title");
            String title = titleElement == null ? "" : titleElement.text().trim();

            if (title.isEmpty()) {
                Element img = card.selectFirst("img[alt]");
                title = img == null ? "" : img.attr("alt").trim();
            }

            if (title.isEmpty()) {
                continue;
            }

            Element priceElement = card.selectFirst(".subtitle .label-l, .label-l");
            Double price = priceElement == null
                    ? null
                    : parsePrice(priceElement.text().trim());

            // Η κάρτα δεν έχει κατάσταση/πωλητή/περιγραφή· συμπληρώνονται
            // όταν ανοίξει η αγγελία (Specs από την αγγελία / φόρτωση πλήρους).
            String condition = normalize(title).contains("μεταχειρισ")
                    ? "ΜΕΤΑΧΕΙΡΙΣΜΕΝΟ" : "";

            Listing listing = new Listing(
                    title,
                    price,
                    SpecParser.parse(title, ""),
                    "",
                    condition,
                    "Πώληση",
                    categoryName,
                    url,
                    isLaptop(title));

            results.add(listing);
        }

        return results;
    }

    private String cleanVendoraUrl(String href) {
        String h = href == null ? "" : href.trim();

        // Αν το HTML έχει αποθηκευτεί από browser, στο href μπορεί να έχει
        // προστεθεί κενό + " <view-source:...>". Κρατάμε μέχρι το πρώτο κενό.
        int space = h.indexOf(' ');

        if (space > 0) {
            h = h.substring(0, space);
        }

        if (h.startsWith("//")) {
            h = "https:" + h;
        } else if (h.startsWith("/")) {
            h = VENDORA_BASE + h;
        }

        if (!h.contains("/items/")) {
            return "";
        }

        return removeQueryAndFragment(h);
    }

    // Ανοίγει μία αγγελία της Vendora και ενημερώνει περιγραφή, specs, κατάσταση, πωλητή.
    private void fetchVendoraDetails(Listing l) throws Exception {
        Document doc = connectWithRetry(l.url);
        String body = extractVendoraDescription(doc).trim();

        if (body.isBlank()) {
            body = l.description == null ? "" : l.description.trim();
        }

        l.fullDescription = body;

        if (!body.isBlank()) {
            l.description = body;
        }

        l.specs = SpecParser.parse(l.title, body);

        String condition = vendoraCondition(doc);

        if (!condition.isEmpty()) {
            l.condition = condition;
        }

        Element sellerElement = doc.selectFirst(".about-seller-user-details h2");

        if (sellerElement != null && !sellerElement.text().isBlank()) {
            l.seller = sellerElement.text().trim();
        }

        if (!l.laptop && isLaptop(l.title + " " + body)) {
            l.laptop = true;
        }

        l.flaggedKeywords = SpecParser.hasSuspiciousKeywords(l.title, body);
        l.detailsLoaded = true;
    }

    private String extractVendoraDescription(Document doc) {
        // 1) Το κείμενο της αγγελίας: <p id="translatable-value"> με <br /> ανά γραμμή.
        Element element = doc.selectFirst("#translatable-value");

        if (element != null) {
            String text = vendoraText(element.html());

            if (!text.isBlank()) {
                return text;
            }
        }

        // 2) Εφεδρικά: το description μέσα στο JSON-LD (schema.org Product).
        for (Element script : doc.select("script[type=application/ld+json]")) {
            String text = jsonStringField(script.data(), "description").trim();

            if (!text.isBlank()) {
                return text;
            }
        }

        return "";
    }

    private String vendoraText(String html) {
        return htmlToLines(html)
                .replace("\r", "")
                .replaceAll("[ \t]*\n[ \t]*", "\n")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
    }

    // Διαβάζει την πρώτη τιμή "key":"..." από JSON κείμενο, με αποκωδικοποίηση escapes.
    private String jsonStringField(String json, String key) {
        if (json == null) {
            return "";
        }

        String marker = "\"" + key + "\":\"";
        int start = json.indexOf(marker);

        if (start < 0) {
            return "";
        }

        StringBuilder sb = new StringBuilder();

        for (int i = start + marker.length(); i < json.length(); i++) {
            char c = json.charAt(i);

            if (c == '"') {
                break;
            }

            if (c == '\\' && i + 1 < json.length()) {
                char n = json.charAt(++i);

                switch (n) {
                    case 'n':
                        sb.append('\n');
                        break;
                    case 'r':
                        break;
                    case 't':
                        sb.append(' ');
                        break;
                    case 'u':
                        if (i + 4 < json.length()) {
                            try {
                                sb.append((char) Integer.parseInt(
                                        json.substring(i + 1, i + 5), 16));
                                i += 4;
                            } catch (NumberFormatException ex) {
                                sb.append('u');
                            }
                        }
                        break;
                    default:
                        sb.append(n); // \/ \" \\
                }
            } else {
                sb.append(c);
            }
        }

        return sb.toString();
    }

    private String vendoraCondition(Document doc) {
        String meta = doc.select("meta[property=product:condition]")
                .attr("content").trim().toLowerCase(Locale.ROOT);

        if (meta.equals("new")) {
            return "ΝΕΟ";
        }

        if (!meta.isEmpty()) {
            return "ΜΕΤΑΧΕΙΡΙΣΜΕΝΟ";
        }

        Element attr = doc.selectFirst(".ad-attributes-block");

        if (attr != null) {
            String n = normalize(attr.text());

            if (n.contains("μεταχειρισ")) {
                return "ΜΕΤΑΧΕΙΡΙΣΜΕΝΟ";
            }

            if (n.contains("καινουργιο") || n.startsWith("νεο")) {
                return "ΝΕΟ";
            }
        }

        return "";
    }

    private boolean isLaptop(String text) {
        String value = normalize(text);

        return value.contains("laptop")
                || value.contains("lap top")
                || value.contains("notebook")
                || value.contains("λαπτοπ");
    }

    // Μοντέλα / σειρές laptop που συχνά γράφονται χωρίς τη λέξη «laptop» στον τίτλο.
    private static final Pattern LAPTOP_MODEL = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(macbook|thinkpad|thinkbook|ideapad|zenbook|vivobook|expertbook|chromebook|ultrabook"
                    + "|elitebook|probook|zbook|latitude|victus|katana|helios|alienware\\s?[mx]\\d{2}|legion\\s?[5-9]\\w*"
                    + "|nitro\\s?[5-9]|surface\\s?laptop|xps\\s?1[3-7]|gram\\s?1[4-7]|13\\.3|15\\.6|17\\.3)(?![\\p{L}\\p{N}])");

    private boolean isLaptopLoose(String text) {
        if (isLaptop(text)) return true;

        String value = normalize(text);

        if (value.contains("φορητ") && value.contains("υπολογ")) return true;

        return LAPTOP_MODEL.matcher(value).find();
    }

    private Document connect(String url) throws Exception {
        return Jsoup.connect(url)
                .userAgent(
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                                + "AppleWebKit/537.36 (KHTML, like Gecko) "
                                + "Chrome/131.0 Safari/537.36")
                .header(
                        "Accept-Language",
                        "el-GR,el;q=0.9,en;q=0.8")
                .timeout(TIMEOUT_MS)
                .maxBodySize(0)
                .get();
    }

    private String extractCondition(Element card) {
        Elements pills = card.select("span.insSrCard__pill");

        for (Element pill : pills) {
            String value = normalize(pill.text());

            if (value.contains("metacheirismeno")
                    || value.contains("μεταχειρισμενο")) {
                return "ΜΕΤΑΧΕΙΡΙΣΜΕΝΟ";
            }

            if (value.equals("νεο")
                    || value.contains("καινουργιο")
                    || value.contains("neo")
                    || value.contains("kainourgio")) {
                return "ΝΕΟ";
            }
        }

        return "";
    }

    private String detectTransaction(String text) {
        String value = normalize(text);

        if (value.contains("ziteitai")
                || value.contains("zitisi")
                || value.contains("zitao")
                || value.contains("ζηταω")
                || value.contains("anazito")
                || value.contains("wanted")
                || value.contains("ζητειται")
                || value.contains("ζητηση")) {
            return "Ζήτηση";
        }

        return "Πώληση";
    }

    private Double parsePrice(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        String value = text
                .replace("€", "")
                .replace("EUR", "")
                .replace("\u00A0", "")
                .replace(" ", "")
                .trim();

        int comma = value.lastIndexOf(',');
        int dot = value.lastIndexOf('.');

        if (comma >= 0 && dot >= 0) {
            if (comma > dot) {
                value = value.replace(".", "");
                value = value.replace(',', '.');
            } else {
                value = value.replace(",", "");
            }
        } else if (comma >= 0) {
            String[] parts = value.split(",");
            if (parts.length == 2 && parts[1].length() == 3) {
                value = value.replace(",", ""); // 1,250 -> 1250
            } else {
                value = value.replace(',', '.');
            }
        } else if (dot >= 0) {
            String[] parts = value.split("\\.");

            if (parts.length == 2 && parts[1].length() == 3) {
                value = value.replace(".", "");
            }
        }

        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    // ---------------------------------------------------------
    // ΦΙΛΤΡΑ ΠΙΝΑΚΑ
    // ---------------------------------------------------------

    private void applyFilters() {
        String condition =
                (String) conditionCombo.getSelectedItem();

        String transaction =
                (String) transactionCombo.getSelectedItem();

        int device = deviceCombo.getSelectedIndex();

        Double min = parseOptionalPrice(minPriceField.getText());
        Double max = parseOptionalPrice(maxPriceField.getText());
        List<String> excludedTerms = new ArrayList<>();
        for (String term : excludeKeywordsField.getText().split("[,;\\n\\r]+")) {
            String normalized = normalize(term.trim());
            if (!normalized.isBlank()) excludedTerms.add(normalized);
        }

        sorter.setRowFilter(new RowFilter<>() {
            @Override
            public boolean include(
                    Entry<? extends ListingTableModel,
                            ? extends Integer> entry) {

                Listing listing =
                        tableModel.getListing(entry.getIdentifier());

                if (listing == null || isDamagedListing(listing)) {
                    return false;
                }

                // Exclusions are checked against all available listing text,
                // not just the title: description, seller, category, URL and specs.
                String searchableText = normalize(
                        listing.title + " " + listing.description + " "
                                + listing.fullDescription + " " + listing.seller + " "
                                + listing.category + " " + listing.url + " "
                                + listing.transaction + " " + listing.condition + " "
                                + listing.specs.cpu + " " + listing.specs.ram + " "
                                + listing.specs.gpu + " " + listing.specs.ssd + " "
                                + listing.specs.hdd + " " + listing.specs.mobo + " "
                                + listing.specs.psu + " " + listing.specs.extras);
                for (String excluded : excludedTerms) {
                    if (searchableText.contains(excluded)) return false;
                }

                if (min != null
                        && (listing.price == null
                        || listing.price < min)) {
                    return false;
                }

                if (max != null
                        && (listing.price == null
                        || listing.price > max)) {
                    return false;
                }

                if (condition != null
                        && !condition.equals("Όλες οι καταστάσεις")
                        && !listing.condition.equalsIgnoreCase(condition)) {
                    return false;
                }

                if (transaction != null
                        && !transaction.equals("Όλα")
                        && !listing.transaction.equalsIgnoreCase(transaction)) {
                    return false;
                }

                // 1 = μόνο PC (κόβουμε όσες αναφέρουν laptop)
                if (device == 1 && listing.laptop) {
                    return false;
                }

                // 2 = μόνο laptop
                if (device == 2 && !listing.laptop) {
                    return false;
                }

                return true;
            }
        });

        statusLabel.setText(
                "Εμφανίζονται " + resultsTable.getRowCount()
                        + " από " + tableModel.getRowCount()
                        + " αγγελίες.");
    }

    private Double parseOptionalPrice(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }

        return parsePrice(value);
    }

    // ---------------------------------------------------------
    // ΠΛΗΡΗΣ ΠΕΡΙΓΡΑΦΗ ΕΠΙΛΕΓΜΕΝΗΣ ΑΓΓΕΛΙΑΣ
    // ---------------------------------------------------------

    private JPanel buildDetailPanel() {
        detailArea.setEditable(false);
        detailArea.setLineWrap(true);
        detailArea.setWrapStyleWord(true);
        detailArea.setMargin(new Insets(6, 8, 6, 8));

        loadFullButton.setEnabled(false);
        openButton.setEnabled(false);

        loadFullButton.addActionListener(e -> loadFullDescription());
        openButton.addActionListener(e -> {
            Listing l = shownListing;

            if (l != null) {
                openUrl(l.url);
            }
        });

        JPanel buttons = new JPanel(
                new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(loadFullButton);
        buttons.add(openButton);

        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.setBorder(BorderFactory.createEmptyBorder(6, 10, 4, 10));
        top.add(detailTitle, BorderLayout.CENTER);
        top.add(buttons, BorderLayout.EAST);

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(top, BorderLayout.NORTH);
        panel.add(new JScrollPane(detailArea), BorderLayout.CENTER);
        panel.setPreferredSize(new Dimension(100, 220));

        return panel;
    }

    private void showListing(Listing l) {
        shownListing = l;

        if (l == null) {
            detailTitle.setText(
                    "Επίλεξε μια αγγελία για να δεις την περιγραφή της.");
            detailArea.setText("");
            loadFullButton.setEnabled(false);
            openButton.setEnabled(false);
            return;
        }

        detailTitle.setText(l.title
                + (l.price == null ? "" : "  —  " + l.price + " €"));

        if (!l.fullDescription.isEmpty()) {
            detailArea.setText(l.fullDescription);
        } else if (!l.description.isEmpty()) {
            detailArea.setText(l.description
                    + "\n\n[Σύντομη περιγραφή από τη λίστα — πάτα "
                    + "«Φόρτωση πλήρους περιγραφής» για ολόκληρη]");
        } else if (l.detailsLoaded) {
            detailArea.setText("Δεν βρέθηκε περιγραφή στη σελίδα της αγγελίας. "
                    + "Τα υπόλοιπα στοιχεία της αγγελίας διατηρούνται.");
        } else {
            detailArea.setText("(Δεν υπάρχει περιγραφή στη λίστα — πάτα "
                    + "«Φόρτωση πλήρους περιγραφής»)");
        }

        detailArea.setCaretPosition(0);
        loadFullButton.setEnabled(!l.detailsLoaded && l.fullDescription.isEmpty());
        openButton.setEnabled(true);
    }

    // Κατεβάζει ΜΟΝΟ την επιλεγμένη αγγελία και συμπληρώνει τα specs της.
    private void loadFullDescription() {
        final Listing l = shownListing;

        if (l == null) {
            return;
        }

        if (l.detailsLoaded) {
            showListing(l);
            return;
        }

        loadFullButton.setEnabled(false);
        detailArea.setText("Φόρτωση περιγραφής...");

        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                fetchFullDescription(l);
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    tableModel.refresh(l);

                    if (shownListing == l) {
                        showListing(l);
                    }
                } catch (Exception ex) {
                    if (shownListing == l) {
                        detailArea.setText("Σφάλμα φόρτωσης:\n"
                                + rootMessage(ex));
                        loadFullButton.setEnabled(true);
                    }
                }
            }
        }.execute();
    }

    // ---------------------------------------------------------
    // ΒΟΗΘΗΤΙΚΕΣ ΜΕΘΟΔΟΙ
    // ---------------------------------------------------------

    private String normalize(String text) {
        return SpecParser.norm(text);
    }

    // Damaged listings are suppressed in every live-search table, regardless of platform.
    private static final Pattern DAMAGED_ENGLISH_WORDS = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(?:broken|damaged|not\\s+working|non[- ]working)(?![\\p{L}\\p{N}])");

    private boolean isDamagedListing(Listing listing) {
        if (listing == null) return false;
        String text = normalize(listing.title + " " + listing.description + " "
                + listing.fullDescription);
        return text.contains("χαλασμεν") || DAMAGED_ENGLISH_WORDS.matcher(text).find();
    }

    private static boolean isFlaggedListing(Listing listing) {
        return listing != null && (listing.flaggedManySpecs || listing.flaggedKeywords);
    }

    // Paints only the outside of a flagged row with a dashed outline; it does not fill the row yellow.
    private static final class DashedRowCellBorder extends javax.swing.border.AbstractBorder {
        private final boolean leftEdge;
        private final boolean rightEdge;

        DashedRowCellBorder(boolean leftEdge, boolean rightEdge) {
            this.leftEdge = leftEdge;
            this.rightEdge = rightEdge;
        }

        @Override
        public Insets getBorderInsets(Component c) {
            return new Insets(2, leftEdge ? 2 : 0, 2, rightEdge ? 2 : 0);
        }

        @Override
        public Insets getBorderInsets(Component c, Insets insets) {
            insets.set(2, leftEdge ? 2 : 0, 2, rightEdge ? 2 : 0);
            return insets;
        }

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setColor(FLAGGED_BORDER);
            g2.setStroke(new BasicStroke(1.15f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                    10.0f, new float[]{4.0f, 3.5f}, 0.0f));
            int right = x + width - 1;
            int bottom = y + height - 1;
            g2.drawLine(x, y + 1, right, y + 1);
            g2.drawLine(x, bottom - 1, right, bottom - 1);
            if (leftEdge) g2.drawLine(x + 1, y + 1, x + 1, bottom - 1);
            if (rightEdge) g2.drawLine(right - 1, y + 1, right - 1, bottom - 1);
            g2.dispose();
        }
    }

    private static void applyFlaggedRowBorder(Component component, JTable table,
                                              boolean flagged, int viewRow, int viewColumn) {
        if (!flagged || !(component instanceof JComponent)) return;

        // Preserve the selected-row color; otherwise use a soft ivory tint as a quiet warning cue.
        if (!table.isRowSelected(viewRow)) {
            component.setBackground(FLAGGED_BACKGROUND);
        }
        ((JComponent) component).setBorder(new DashedRowCellBorder(
                viewColumn == 0, viewColumn == table.getColumnCount() - 1));
    }

    private String removeFragmentOnly(String url) {
        if (url == null) {
            return "";
        }

        int fragment = url.indexOf('#');
        return fragment >= 0 ? url.substring(0, fragment) : url;
    }

    private String removeQueryAndFragment(String url) {
        try {
            URI uri = URI.create(url);

            return new URI(
                    uri.getScheme(),
                    uri.getAuthority(),
                    uri.getPath(),
                    null,
                    null
            ).toString();
        } catch (Exception ex) {
            int q = url.indexOf('?');
            int h = url.indexOf('#');

            int end = url.length();

            if (q >= 0) end = Math.min(end, q);
            if (h >= 0) end = Math.min(end, h);

            return url.substring(0, end);
        }
    }

    private String pathWithoutTrailingSlash(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }

        String result = path;

        while (result.length() > 1 && result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }

        return result;
    }

    private void openUrl(String url) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(new URI(url));
            } else {
                JOptionPane.showMessageDialog(
                        this, "Σύνδεσμος:\n" + url);
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(
                    this,
                    "Δεν ήταν δυνατό το άνοιγμα:\n" + url);
        }
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;

        while (current.getCause() != null) {
            current = current.getCause();
        }

        return current.getMessage() == null
                ? current.getClass().getSimpleName()
                : current.getMessage();
    }

    // ---------------------------------------------------------
    // ΜΟΝΤΕΛΑ
    // ---------------------------------------------------------

    private static class Category {
        final String name;
        final String url;
        final boolean all;

        Category(String name, String url, boolean all) {
            this.name = name;
            this.url = url;
            this.all = all;
        }

        static Category allCategories() {
            return new Category(
                    "Όλες οι Κατηγορίες",
                    SEARCH_URL,
                    true);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static class Listing {
        // Στο Vinted ο τίτλος αντικαθίσταται από αυτόν της σελίδας της αγγελίας (στη γλώσσα του site).
        volatile String title;
        final Double price;
        final String transaction;
        final String category;
        final String url;

        // Αλλάζουν αφού ανοιχτεί η αγγελία (στη Vendora ο πωλητής και η
        // κατάσταση φαίνονται μόνο μέσα στη σελίδα της αγγελίας).
        volatile String seller;
        volatile String condition;
        volatile Specs specs;
        volatile boolean laptop;
        volatile String description = "";
        // Το πλήρες κείμενο της αγγελίας (άδειο μέχρι να φορτωθεί).
        volatile String fullDescription = "";
        // Cache flag: η αγγελία έχει ήδη εμπλουτιστεί, ώστε τα φίλτρα
        // να μη δημιουργούν δεύτερες λήψεις ή να αλλάζουν τα δεδομένα της.
        volatile boolean detailsLoaded = false;
        final boolean flaggedManySpecs;
        volatile boolean flaggedKeywords;

        // Μόνο για το Vinted.
        volatile String country = "";
        volatile String city = "";
        volatile String brand = "";
        volatile String posted = "";
        volatile String sellerId = "";
        volatile int favourites = -1;
        volatile Double totalPrice;
        // Μόνο για το Vinted: το αρχικό κείμενο πριν τη μετάφραση στα αγγλικά.
        volatile String origTitle = "";
        volatile String origDescription = "";

        // Μόνο για το Facebook Marketplace.
        volatile String location = "";
        volatile String delivery = "";
        volatile String conditionCode = "";
        volatile long createdAt;
        volatile boolean sold;

        Listing(
                String title,
                Double price,
                Specs specs,
                String seller,
                String condition,
                String transaction,
                String category,
                String url,
                boolean laptop) {

            this.title = title;
            this.price = price;
            this.specs = specs;
            this.seller = seller;
            this.condition = condition;
            this.transaction = transaction;
            this.category = category;
            this.url = url;
            this.laptop = laptop;
            this.flaggedManySpecs = SpecParser.hasMultipleCpuOrGpu(title);
            this.flaggedKeywords = SpecParser.hasSuspiciousKeywords(title);
        }
    }

    private static class ListingTableModel
            extends AbstractTableModel {

        private final String[] columns = {
                "Τίτλος",
                "Περιγραφή",
                "Τιμή (€)",
                "CPU",
                "RAM",
                "GPU",
                "SSD",
                "HDD",
                "Μητρική",
                "PSU",
                "Έξτρα",
                "Πωλητής",
                "Κατάσταση",
                "Τύπος",
                "Κατηγορία",
                "URL αγγελίας",
                "_priority"
        };

        private List<Listing> listings = new ArrayList<>();

        void setListings(List<Listing> values) {
            listings = new ArrayList<>(values);
            fireTableDataChanged();
        }

        void addListings(List<Listing> values) {
            if (values == null || values.isEmpty()) return;
            Set<String> seen = new HashSet<>();
            for (Listing existing : listings) seen.add(existing.url);
            int first = listings.size();
            for (Listing value : values) {
                if (value != null && seen.add(value.url)) listings.add(value);
            }
            if (listings.size() > first) fireTableRowsInserted(first, listings.size() - 1);
        }

        void refresh(Listing listing) {
            int index = listings.indexOf(listing);

            if (index >= 0) {
                fireTableRowsUpdated(index, index);
            }
        }

        Listing getListing(int row) {
            if (row < 0 || row >= listings.size()) {
                return null;
            }

            return listings.get(row);
        }

        @Override
        public int getRowCount() {
            return listings.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return column == 2 ? Double.class : (column == 16 ? Integer.class : String.class);
        }

        @Override
        public Object getValueAt(int row, int column) {
            Listing item = listings.get(row);
            Specs sp = item.specs;
            if (item.flaggedKeywords || item.flaggedManySpecs) {
                // Οι flagged αγγελίες κρατούν όλα τα πεδία τους· αλλάζουν μόνο
                // το priority και το κίτρινο χρώμα της γραμμής.
                if (column == 16) return 1;
            }

            if (item.flaggedKeywords) {
                // Ύποπτες/γενικές αγγελίες: κρατάμε τα στοιχεία της αγγελίας,
                // αλλά κρύβουμε τα κουτιά αναγνώρισης εξαρτημάτων.
                if (column >= 3 && column <= 10) return "";
                if (column == 16) return 1;
            }

            switch (column) {
                case 0: return item.title;
                case 1: return item.description;
                case 2: return item.price;
                case 3: return sp.cpu;
                case 4: return sp.ram;
                case 5: return sp.gpu;
                case 6: return sp.ssd;
                case 7: return sp.hdd;
                case 8: return sp.mobo;
                case 9: return sp.psu;
                case 10: return sp.extras;
                case 11: return item.seller;
                case 12: return item.condition;
                case 13: return item.transaction;
                case 14: return item.category;
                case 15: return item.url;
                case 16: return (item.flaggedKeywords || item.flaggedManySpecs) ? 1 : 0;
                default: return "";
            }
        }
    }

    // =========================================================
    // 2ο TAB: LIVE PRICE MONITOR (CPU / GPU / RAM)
    // =========================================================

    private static final String[] SYSTEM_WORDS = {
            "pc", "υπολογιστησ", "συστημα", "desktop", "laptop", "notebook", "πληρεσ"
    };

    private static class MarketListing {
        final Listing listing;
        volatile Double price;
        volatile int watchers = -1;
        volatile String fullDescription = "";
        volatile Specs specs;
        volatile boolean fullSystemsCategory;
        volatile boolean detailsLoaded;

        MarketListing(Listing listing) {
            this.listing = listing;
            this.price = listing.price;
            this.fullDescription = listing.description == null ? "" : listing.description;
            this.specs = listing.specs == null ? new Specs() : listing.specs;
            this.fullSystemsCategory = SpecParser.norm(listing.category).contains("plhri systhmata");
        }

        String searchableText() {
            return listing.title + " " + fullDescription;
        }
    }

    private static class SoldRow {
        final String sheet;
        final int excelRow;
        final String kind;
        String label;
        String excelMin;
        String excelMax;
        volatile String keyword;
        volatile Double min;
        volatile Double max;
        volatile int minWatchers = -1;
        volatile int maxWatchers = -1;
        volatile int count;
        volatile String status = "";
        volatile String detail = "";
        volatile MarketListing cheapest;
        volatile MarketListing mostExpensive;
        volatile MarketListing cheapestPc;
        // Τα φθηνότερα πλήρη PC της κατηγορίας πλήρων συστημάτων (φθηνότερο πρώτο).
        volatile List<MarketListing> cheapestPcs = new ArrayList<>();

        SoldRow(String sheet, int excelRow, String kind, String label,
                String keyword, String excelMin, String excelMax) {
            this.sheet = sheet;
            this.excelRow = excelRow;
            this.kind = kind;
            this.label = label;
            this.keyword = keyword;
            this.excelMin = excelMin == null ? "" : excelMin;
            this.excelMax = excelMax == null ? "" : excelMax;
        }

        String historyKey() {
            return SpecParser.norm(kind + "|" + keyword);
        }

        void reset() {
            min = null;
            max = null;
            minWatchers = -1;
            maxWatchers = -1;
            count = 0;
            status = "Σε αναμονή";
            detail = "";
            cheapest = null;
            mostExpensive = null;
            cheapestPc = null;
            cheapestPcs = new ArrayList<>();
        }

        void apply(List<MarketListing> offers, List<MarketListing> systems,
                   int found, int inspected, int errors, int missingPrice) {
            List<MarketListing> priced = new ArrayList<>();
            for (MarketListing hit : offers) {
                if (hit.price != null && hit.price > 0) priced.add(hit);
            }
            priced.sort(Comparator.comparingDouble(x -> x.price));

            count = offers.size();
            if (!priced.isEmpty()) {
                cheapest = priced.get(0);
                mostExpensive = priced.get(priced.size() - 1);
                min = cheapest.price;
                max = mostExpensive.price;
                minWatchers = cheapest.watchers;
                maxWatchers = mostExpensive.watchers;
            }

            // Πλήρη PC με παράλογη τιμή (π.χ. 0.01 €, 50 €) δεν εμφανίζονται ποτέ.
            systems.removeIf(x -> x.price == null || x.price <= 0 || x.price < MIN_PC_PRICE
                    || !x.fullSystemsCategory);
            systems.sort(Comparator.comparingDouble(x -> x.price));
            if (!systems.isEmpty()) {
                cheapestPc = systems.get(0);
                cheapestPcs = new ArrayList<>(systems.subList(0, Math.min(SYSTEMS_KEEP, systems.size())));
            }

            StringBuilder sb = new StringBuilder();
            sb.append("ΕΞΑΡΤΗΜΑ: ").append(label).append('\n')
                    .append("Αναζήτηση: ").append(keyword).append('\n')
                    .append("Αγγελίες που επέστρεψε η αναζήτηση: ").append(found).append('\n')
                    .append("Ταυτοποιημένες αγγελίες εξαρτήματος: ").append(count).append('\n')
                    .append("Αναλυτικές σελίδες που ελέγχθηκαν: ").append(inspected).append('\n')
                    .append("Εύρος τιμών: ")
                    .append(min == null ? "—" : String.format(Locale.ROOT, "%.2f €", min))
                    .append(" έως ")
                    .append(max == null ? "—" : String.format(Locale.ROOT, "%.2f €", max)).append('\n')
                    .append("Παρακολουθούν (φθηνότερη / ακριβότερη): ")
                    .append(minWatchers < 0 ? "—" : minWatchers)
                    .append(" / ").append(maxWatchers < 0 ? "—" : maxWatchers).append("\n\n");

            if (cheapest != null) {
                appendMarketDetail(sb, "ΦΘΗΝΟΤΕΡΗ ΑΓΓΕΛΙΑ", cheapest);
            }
            if (mostExpensive != null && mostExpensive != cheapest) {
                appendMarketDetail(sb, "ΑΚΡΙΒΟΤΕΡΗ ΑΓΓΕΛΙΑ", mostExpensive);
            }
            for (int i = 0; i < cheapestPcs.size(); i++) {
                String heading = "ΠΛΗΡΕΣ ΣΥΣΤΗΜΑ #" + (i + 1) + (i == 0 ? " (φθηνότερο)" : "");
                if (i == 0) appendMarketDetail(sb, heading, cheapestPcs.get(i));
                else appendMarketLink(sb, heading, cheapestPcs.get(i));
            }
            if (missingPrice > 0) sb.append("\nΧωρίς αναγνώσιμη τιμή: ").append(missingPrice);
            if (errors > 0) sb.append("\nΣφάλματα λήψης: ").append(errors);
            detail = sb.toString();

            status = "" + count + " αγγελίες"
                    + (min == null ? " · χωρίς τιμή" : " · " + String.format(Locale.ROOT, "%.2f–%.2f €", min, max))
                    + (cheapestPc == null ? " · χωρίς PC" : " · PC από " + String.format(Locale.ROOT, "%.2f €", cheapestPc.price)
                    + " (" + cheapestPcs.size() + ")")
                    + (errors > 0 ? " · σφάλματα " + errors : "");
        }

        private static void appendMarketLink(StringBuilder sb, String heading, MarketListing hit) {
            sb.append("--- ").append(heading).append(" ---\n")
                    .append("Τίτλος: ").append(hit.listing.title).append('\n')
                    .append("Τιμή: ").append(hit.price == null ? "—" : String.format(Locale.ROOT, "%.2f €", hit.price)).append('\n')
                    .append("URL: ").append(hit.listing.url).append("\n\n");
        }

        private static void appendMarketDetail(StringBuilder sb, String heading, MarketListing hit) {
            sb.append("--- ").append(heading).append(" ---\n")
                    .append("Τίτλος: ").append(hit.listing.title).append('\n')
                    .append("Τιμή: ").append(hit.price == null ? "—" : String.format(Locale.ROOT, "%.2f €", hit.price)).append('\n')
                    .append("Παρακολουθούν: ").append(hit.watchers < 0 ? "—" : hit.watchers).append('\n')
                    .append("Πωλητής: ").append(hit.listing.seller).append('\n')
                    .append(hit.listing.country.isEmpty() ? "" : "Χώρα: " + hit.listing.country + "\n")
                    .append("Κατηγορία: ").append(hit.listing.category).append('\n')
                    .append("CPU: ").append(hit.specs.cpu).append(" | RAM: ").append(hit.specs.ram)
                    .append(" | GPU: ").append(hit.specs.gpu).append('\n')
                    .append("SSD: ").append(hit.specs.ssd).append(" | HDD: ").append(hit.specs.hdd)
                    .append(" | Μητρική: ").append(hit.specs.mobo).append(" | PSU: ").append(hit.specs.psu).append('\n')
                    .append("URL: ").append(hit.listing.url).append('\n')
                    .append("Πλήρης περιγραφή:\n")
                    .append(hit.fullDescription.isBlank() ? "(Δεν διαβάστηκε πλήρης περιγραφή.)" : hit.fullDescription)
                    .append("\n\n");
        }
    }

    private static class SoldTableModel extends AbstractTableModel {
        private final String[] columns = {
                "Κατηγορία", "Γρ.", "Εξάρτημα", "Όροι αναζήτησης",
                "Excel Min", "Excel Max", "Νέο Min (€)", "Νέο Max (€)",
                "Παρακολουθούν Min", "Παρακολουθούν Max", "Αγγελίες", "Κατάσταση",
                "Link Min", "Link Max", "Link PC 1", "Link PC 2", "Link PC 3"
        };
        // Οι στήλες από εδώ και κάτω είναι πατήσιμοι σύνδεσμοι αγγελιών.
        static final int LINK_FIRST = 12;
        private List<SoldRow> rows = new ArrayList<>();

        void setRows(List<SoldRow> values) {
            rows = new ArrayList<>(values);
            fireTableDataChanged();
        }
        List<SoldRow> all() { return rows; }
        SoldRow get(int i) { return rows.get(i); }
        void refresh(SoldRow r) {
            int i = rows.indexOf(r);
            if (i >= 0) fireTableRowsUpdated(i, i);
        }
        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int c) { return columns[c]; }
        @Override public Class<?> getColumnClass(int c) {
            if (c == 1 || c == 8 || c == 9 || c == 10) return Integer.class;
            if (c == 6 || c == 7) return Double.class;
            return String.class;
        }
        @Override public boolean isCellEditable(int row, int col) { return col == 3; }
        @Override public void setValueAt(Object value, int row, int col) {
            if (col == 3 && value != null) {
                SoldRow r = rows.get(row);
                r.keyword = value.toString().trim();
                fireTableRowsUpdated(row, row);
            }
        }
        @Override public Object getValueAt(int row, int col) {
            SoldRow r = rows.get(row);
            switch (col) {
                case 0: return r.kind.toUpperCase(Locale.ROOT);
                case 1: return r.excelRow;
                case 2: return r.label;
                case 3: return r.keyword;
                case 4: return r.excelMin;
                case 5: return r.excelMax;
                case 6: return r.min;
                case 7: return r.max;
                case 8: return r.minWatchers < 0 ? null : r.minWatchers;
                case 9: return r.maxWatchers < 0 ? null : r.maxWatchers;
                case 10: return r.count;
                case 11: return r.status;
                case 12: return r.cheapest == null ? null : r.cheapest.listing.url;
                case 13: return r.mostExpensive == null ? null : r.mostExpensive.listing.url;
                case 14: return r.cheapestPcs.size() > 0 ? r.cheapestPcs.get(0).listing.url : null;
                case 15: return r.cheapestPcs.size() > 1 ? r.cheapestPcs.get(1).listing.url : null;
                case 16: return r.cheapestPcs.size() > 2 ? r.cheapestPcs.get(2).listing.url : null;
                default: return "";
            }
        }
    }

    private static class OfferRow {
        final String category, label, keyword, title, seller, url;
        final double previous, current;
        final int watchers;

        OfferRow(String category, String label, String keyword, double previous, double current,
                 int watchers, String title, String seller, String url) {
            this.category = category;
            this.label = label;
            this.keyword = keyword;
            this.previous = previous;
            this.current = current;
            this.watchers = watchers;
            this.title = title == null ? "" : title;
            this.seller = seller == null ? "" : seller;
            this.url = url == null ? "" : url;
        }

        double dropPercent() {
            return previous <= 0 ? 0 : (previous - current) * 100.0 / previous;
        }
    }

    private static class SoldParams {
        int pages = 2;
        List<String> excluded = new ArrayList<>();
        // Μόνο για το tab Vendora: κατηγορία αναζήτησης των εξαρτημάτων (τα πλήρη PC ψάχνονται
        // πάντα μόνο στην κατηγορία «Σταθεροί Υπολογιστές»).
        String vendoraRoot = "";
    }

    private class SoldPricesTab extends JPanel {
        // SRC_INSOMNIA = 3ο tab, SRC_VENDORA = 4ο tab, SRC_VINTED = 6ο tab, SRC_FACEBOOK = 8ο tab.
        private final boolean vendora;
        private final boolean vinted;
        private final boolean facebook;
        private final JTextField facebookCookieField = new JTextField(34);
        private final JTextField vendoraCategoryField = new JTextField(VENDORA_GENERAL_URL, 46);
        private final JTextField excelPathField = new JTextField(34);
        private final JComboBox<String> sheetCombo = new JComboBox<>(new String[]{"Όλα τα φύλλα"});
        private final JTextField pagesField = new JTextField("5", 3);
        private final JTextField excludeField = new JTextField(
                "laptop, notebook, sodimm, so-dimm, ζητω, ζητηση, ανταλλαγη", 34);
        private final JCheckBox ramSpeedBox = new JCheckBox("RAM: συμπερίληψη MHz", true);

        private final JButton loadButton = new JButton("Φόρτωση Excel");
        private final JButton runAllButton = new JButton("Έλεγχος όλων · 1 κλικ");
        private final JButton cancelButton = new JButton("Ακύρωση");
        private final JButton openOutputButton = new JButton("Άνοιγμα αποτελεσμάτων");
        // Αποθηκεύει σε νέο Excel τον πίνακα ΑΚΡΙΒΩΣ όπως φαίνεται στο παράθυρο
        // (ίδιες στήλες, ίδια σειρά, ίδια ταξινόμηση / φίλτρο φύλλου).
        private final JButton exportViewButton = new JButton("Λήψη Excel (όπως φαίνεται)");

        private final JLabel soldStatus = new JLabel("Επίλεξε το Excel με τις λίστες CPU / GPU / RAM.");
        private final JLabel outputPathLabel = new JLabel("Αποτελέσματα: θα δημιουργηθούν δίπλα στο αρχικό Excel.");
        private final SoldTableModel model = new SoldTableModel();
        private final JTable table = new JTable(model);
        private final TableRowSorter<SoldTableModel> rowSorter = new TableRowSorter<>(model);
        private final JEditorPane soldDetail = new JEditorPane();
        private SwingWorker<Void, Void> worker;

        private final Map<String, CompletableFuture<List<Listing>>> searchCache = new ConcurrentHashMap<>();
        private final Map<String, CompletableFuture<MarketListing>> marketDetailCache = new ConcurrentHashMap<>();
        private final Object fullSystemsIndexLock = new Object();
        // Αγγελίες της κατηγορίας «Πλήρη Συστήματα» του Insomnia, όπως φάνηκαν στο ευρετήριο.
        private final Map<String, Listing> fullSystemsListings = new ConcurrentHashMap<>();
        // Νέα Min της τρέχουσας εκτέλεσης (μόνο όσα χτύπησαν υπάρχον Min) -> tab «Προσφορές».
        private final List<OfferRow> runOffers = new ArrayList<>();
        private CompletableFuture<Set<String>> fullSystemsIndexFuture;
        private final AtomicBoolean debugSaved = new AtomicBoolean();
        private final Object soldPacingLock = new Object();
        private long nextSoldRequestNanos = 0L;
        private long soldRequestSpacingMs = SOLD_REQUEST_SPACING_MS;
        private final AtomicInteger soldSuccessStreak = new AtomicInteger();
        private volatile String fullSystemsIndexNote = "";

        private File monitorFile;
        private final Map<String, Double> previousMinima = new HashMap<>();
        private final List<Object[]> newLowHistory = new ArrayList<>();
        private final String[] historyHeaders = {
                "Ημερομηνία ελέγχου", "Κατηγορία", "Εξάρτημα", "Όροι αναζήτησης",
                "Προηγούμενο Min (€)", "Νέο Min (€)", "Μείωση (€)", "Παρακολουθούν",
                "Τίτλος αγγελίας", "CPU", "RAM", "GPU", "SSD", "HDD", "Μητρική", "PSU",
                "Έξτρα specs", "Πλήρης περιγραφή", "URL", "Είδος εγγραφής"
        };

        SoldPricesTab(int source) {
            super(new BorderLayout(10, 10));
            this.vendora = source == SRC_VENDORA;
            this.vinted = source == SRC_VINTED;
            this.facebook = source == SRC_FACEBOOK;
            if (facebook) {
                vendoraCategoryField.setText(FacebookTools.BASE + "/marketplace/" + FacebookTools.DEFAULT_CITY + "/search/");
                // Το Facebook δίνει μία σελίδα (~24 αγγελίες) ανά αναζήτηση.
                pagesField.setText("1");
                pagesField.setEnabled(false);
            }
            if (vinted) {
                vendoraCategoryField.setText(VINTED_CATALOG_URL);
                // 96 αγγελίες ανά σελίδα: 2 σελίδες ανά εξάρτημα αρκούν ως προεπιλογή.
                pagesField.setText("2");
            }
            setBorder(BorderFactory.createEmptyBorder(10, 12, 12, 12));
            File defaultWorkbook = new File("PC_times_mtx.xlsx");
            if (!defaultWorkbook.isFile()) {
                File suppliedWorkbook = new File("insomnia_sold_prices(1).xlsx");
                if (suppliedWorkbook.isFile()) defaultWorkbook = suppliedWorkbook;
            }
            excelPathField.setText(defaultWorkbook.getPath());
            excelPathField.setToolTipText("Το αρχικό Excel που περιέχει τις λίστες CPU, GPU και RAM.");
            pagesField.setToolTipText("Πόσες σελίδες αποτελεσμάτων θα ελέγχονται ανά εξάρτημα.");
            excludeField.setToolTipText("Όροι που αποκλείονται από τα αποτελέσματα.");

            JButton browse = new JButton("Επιλογή...");
            browse.addActionListener(e -> browseExcel());
            loadButton.addActionListener(e -> loadExcel());
            runAllButton.addActionListener(e -> runAll());
            cancelButton.addActionListener(e -> {
                if (worker != null) worker.cancel(true);
            });
            openOutputButton.addActionListener(e -> openOutputFile());
            exportViewButton.addActionListener(e -> exportViewToExcel());
            exportViewButton.setToolTipText("Αποθήκευση του πίνακα σε Excel όπως φαίνεται τώρα στο παράθυρο.");
            sheetCombo.addActionListener(e -> applySheetFilter());
            cancelButton.setEnabled(false);
            openOutputButton.setEnabled(false);
            exportViewButton.setEnabled(false);

            JLabel title = new JLabel(facebook
                    ? "Παρακολούθηση τιμών εξαρτημάτων · Facebook Marketplace"
                    : vinted
                    ? "Παρακολούθηση τιμών εξαρτημάτων · Vinted.gr"
                    : vendora
                    ? "Παρακολούθηση τιμών εξαρτημάτων · Vendora.gr"
                    : "Παρακολούθηση τιμών εξαρτημάτων");
            title.setFont(title.getFont().deriveFont(Font.BOLD, 19f));
            title.setForeground(new Color(30, 41, 59));
            JLabel subtitle = new JLabel(facebook
                    ? "CPU · GPU · RAM  |  Facebook Marketplace  |  ζωντανές αγγελίες και νέα χαμηλά"
                    : vinted
                    ? "CPU · GPU · RAM  |  Vinted.gr  |  ζωντανές αγγελίες και νέα χαμηλά"
                    : vendora
                    ? "CPU · GPU · RAM  |  Vendora.gr  |  ζωντανές αγγελίες και νέα χαμηλά"
                    : "CPU · GPU · RAM  |  Insomnia.gr  |  ζωντανές αγγελίες και νέα χαμηλά");
            vendoraCategoryField.setToolTipText("Σελίδα κατηγορίας της Vendora. Προεπιλογή: γενική αναζήτηση "
                    + "(Υπολογιστές, Tablets, Περιφερειακά). Κάθε όρος προστίθεται ως ?q=... "
                    + "Για μόνο πλήρη PC βάλε " + VENDORA_DESKTOPS_URL
                    + " (τότε τα Min/Max αφορούν πλήρη PC που περιέχουν το εξάρτημα).");
            if (facebook) {
                vendoraCategoryField.setToolTipText("ID πόλης ή URL αναζήτησης του Marketplace. Κάθε όρος προστίθεται ως ?query=... "
                        + "Φίλτρα του URL (minPrice, maxPrice, itemCondition, daysSinceListed, availability, deliveryMethod, "
                        + "sortBy, radius) εφαρμόζονται σε κάθε εξάρτημα. Π.χ. " + FacebookTools.BASE + "/marketplace/"
                        + FacebookTools.DEFAULT_CITY + "/search/?itemCondition=used_good&maxPrice=500");
                facebookCookieField.setToolTipText("Προαιρετικό: cookie συνεδρίας Facebook (name=value; name2=value2) "
                        + "όταν το Facebook ζητά σύνδεση.");
            }
            if (vinted) {
                vendoraCategoryField.setToolTipText("Σελίδα καταλόγου του Vinted. Κάθε όρος προστίθεται ως ?search_text=... "
                        + "Μπορείς να επικολλήσεις URL με φίλτρα (catalog[], status_ids[], brand_ids[], price_from ...): "
                        + "τα φίλτρα εφαρμόζονται σε κάθε εξάρτημα. Π.χ. " + VINTED_CATALOG_URL + "?catalog[]=3602");
            }
            subtitle.setForeground(new Color(100, 116, 139));

            JPanel intro = new JPanel();
            intro.setLayout(new BoxLayout(intro, BoxLayout.Y_AXIS));
            intro.setBackground(new Color(241, 245, 249));
            intro.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(226, 232, 240)),
                    BorderFactory.createEmptyBorder(12, 14, 12, 14)));
            intro.add(title);
            intro.add(Box.createVerticalStrut(4));
            intro.add(subtitle);

            JPanel fileRow = flow(new JLabel("Αρχείο Excel:"), excelPathField, browse, loadButton);
            JPanel optionsRow = flow(new JLabel("Φύλλο:"), sheetCombo,
                    new JLabel("Σελίδες ανά εξάρτημα:"), pagesField,
                    ramSpeedBox);
            JPanel filterRow = flow(new JLabel("Αποκλεισμός:"), excludeField);
            JPanel actions = flow(runAllButton, cancelButton, openOutputButton, exportViewButton);
            styleButton(runAllButton, theme(), INK);
            runAllButton.setFont(runAllButton.getFont().deriveFont(Font.BOLD, 13f));
            styleButton(exportViewButton, theme(), INK);
            cancelButton.setForeground(new Color(153, 27, 27));
            outputPathLabel.setForeground(new Color(71, 85, 105));
            soldStatus.setFont(soldStatus.getFont().deriveFont(Font.BOLD, 12f));
            soldStatus.setForeground(new Color(30, 64, 175));

            JPanel controls = new JPanel();
            controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
            controls.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(226, 232, 240)),
                    BorderFactory.createEmptyBorder(9, 10, 9, 10)));
            controls.add(fileRow);
            controls.add(optionsRow);
            controls.add(filterRow);
            if (vendora || vinted || facebook) {
                controls.add(flow(new JLabel(facebook ? "Πόλη / URL Marketplace (ID ή φίλτρα):"
                                : vinted ? "Κατάλογος Vinted (URL / φίλτρα):" : "Κατηγορία Vendora (URL):"),
                        vendoraCategoryField));
            }
            if (facebook) {
                controls.add(flow(new JLabel("Cookie Facebook (προαιρετικό):"), facebookCookieField));
            }
            controls.add(actions);
            controls.add(outputPathLabel);
            controls.add(soldStatus);

            JPanel north = new JPanel(new BorderLayout(0, 8));
            north.add(intro, BorderLayout.NORTH);
            north.add(controls, BorderLayout.CENTER);

            table.setRowSorter(rowSorter);
            table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
            table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            table.setToolTipText("Διπλό κλικ σε γραμμή για άνοιγμα της αγγελίας.");
            table.setRowHeight(27);
            table.setFont(table.getFont().deriveFont(12f));
            table.setShowVerticalLines(false);
            table.setGridColor(new Color(226, 232, 240));
            table.setFillsViewportHeight(true);
            table.getTableHeader().setReorderingAllowed(false);
            styleHeader(table, theme(), INK);
            table.setSelectionBackground(theme());
            table.setSelectionForeground(new Color(15, 23, 42));
            int[] widths = {75, 52, 250, 210, 82, 82, 92, 92, 125, 125, 72, 280, 125, 125, 125, 125, 125};
            for (int i = 0; i < widths.length; i++) {
                table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
            }
            rowSorter.setComparator(6, (Comparator<Double>) (a, b) ->
                    Double.compare(a == null ? Double.MAX_VALUE : a, b == null ? Double.MAX_VALUE : b));
            rowSorter.setComparator(7, (Comparator<Double>) (a, b) ->
                    Double.compare(a == null ? Double.MAX_VALUE : a, b == null ? Double.MAX_VALUE : b));
            // Default: components with the lowest observed market price first.
            rowSorter.setSortKeys(Collections.singletonList(
                    new RowSorter.SortKey(6, SortOrder.ASCENDING)));
            table.setDefaultRenderer(Object.class, new javax.swing.table.DefaultTableCellRenderer() {
                @Override
                public Component getTableCellRendererComponent(JTable t, Object value,
                                                               boolean selected, boolean focused, int row, int column) {
                    Component c = super.getTableCellRendererComponent(t, value, selected, focused, row, column);
                    if (!selected) {
                        c.setBackground(row % 2 == 0 ? Color.WHITE : new Color(248, 250, 252));
                        c.setForeground(new Color(30, 41, 59));
                    }
                    if (column == 6 && value != null) c.setFont(c.getFont().deriveFont(Font.BOLD));
                    setToolTipText(null);
                    if (t.convertColumnIndexToModel(column) >= SoldTableModel.LINK_FIRST && value != null) {
                        setText("<html><u>↗ Άνοιγμα αγγελίας</u></html>");
                        if (!selected) c.setForeground(new Color(29, 78, 216));
                        setToolTipText(value.toString());
                    }
                    return c;
                }
            });
            table.getSelectionModel().addListSelectionListener(e -> {
                if (!e.getValueIsAdjusting()) showDetail();
            });
            table.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    int viewRow = table.rowAtPoint(e.getPoint());
                    int viewCol = table.columnAtPoint(e.getPoint());
                    if (viewRow >= 0 && viewCol >= 0
                            && table.convertColumnIndexToModel(viewCol) >= SoldTableModel.LINK_FIRST) {
                        Object url = table.getValueAt(viewRow, viewCol);
                        if (url != null && !url.toString().isBlank()) openUrl(url.toString());
                        return;
                    }
                    if (e.getClickCount() == 2) openBestListing();
                }
            });
            table.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
                @Override public void mouseMoved(MouseEvent e) {
                    int viewRow = table.rowAtPoint(e.getPoint());
                    int viewCol = table.columnAtPoint(e.getPoint());
                    boolean link = viewRow >= 0 && viewCol >= 0
                            && table.convertColumnIndexToModel(viewCol) >= SoldTableModel.LINK_FIRST
                            && table.getValueAt(viewRow, viewCol) != null;
                    table.setCursor(Cursor.getPredefinedCursor(link ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
                }
            });

            soldDetail.setContentType("text/html");
            soldDetail.setEditable(false);
            soldDetail.setBackground(new Color(248, 250, 252));
            soldDetail.setMargin(new Insets(10, 12, 10, 12));
            soldDetail.addHyperlinkListener(ev -> {
                if (ev.getEventType() == javax.swing.event.HyperlinkEvent.EventType.ACTIVATED
                        && ev.getDescription() != null) {
                    openUrl(ev.getDescription());
                }
            });
            JScrollPane detailsScroll = new JScrollPane(soldDetail);
            detailsScroll.setBorder(BorderFactory.createTitledBorder(
                    BorderFactory.createLineBorder(new Color(226, 232, 240)), "Λεπτομέρειες επιλεγμένου εξαρτήματος"));

            JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                    new JScrollPane(table), detailsScroll);
            split.setResizeWeight(0.68);
            split.setContinuousLayout(true);

            JPanel northStack = new JPanel(new BorderLayout(0, 8));
            northStack.add(banner(facebook ? "FACEBOOK MARKETPLACE  ·  Έλεγχος τιμών Excel"
                            : vinted ? "VINTED.GR  ·  Έλεγχος τιμών Excel"
                            : vendora ? "VENDORA.GR  ·  Έλεγχος τιμών Excel" : "INSOMNIA.GR  ·  Έλεγχος τιμών Excel",
                    theme()), BorderLayout.NORTH);
            northStack.add(north, BorderLayout.CENTER);
            add(northStack, BorderLayout.NORTH);
            add(split, BorderLayout.CENTER);
        }

        // Insomnia = ό,τι δεν είναι Vendora ή Vinted (έχει κατηγορία πλήρων συστημάτων, Πώληση/Ζήτηση κλπ).
        private boolean insomnia() {
            return !vendora && !vinted && !facebook;
        }

        private Color theme() {
            if (facebook) return FACEBOOK_COLOR;
            if (vinted) return VINTED_COLOR;
            if (vendora) return VENDORA_COLOR;
            return INSOMNIA_COLOR;
        }

        private JPanel flow(Component... parts) {
            JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 7, 4));
            p.setOpaque(false);
            for (Component c : parts) p.add(c);
            return p;
        }

        private void setSoldStatus(String message) {
            SwingUtilities.invokeLater(() -> soldStatus.setText(message));
        }

        private void browseExcel() {
            JFileChooser chooser = new JFileChooser(new File(".").getAbsoluteFile());
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Excel (*.xlsx)", "xlsx"));
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                excelPathField.setText(chooser.getSelectedFile().getAbsolutePath());
                loadExcel();
            }
        }

        private void loadExcel() {
            File file = new File(excelPathField.getText().trim());
            if (!file.isFile()) {
                JOptionPane.showMessageDialog(this, "Δεν βρέθηκε το αρχείο:\n" + file.getAbsolutePath());
                return;
            }

            Map<String, List<XRow>> book;
            try {
                book = SoldTools.readXlsx(file);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Σφάλμα ανάγνωσης Excel:\n" + rootMessage(ex),
                        "Excel", JOptionPane.ERROR_MESSAGE);
                return;
            }

            List<SoldRow> rows = new ArrayList<>();
            Map<String, SoldRow> uniqueRows = new LinkedHashMap<>();
            sheetCombo.removeAllItems();
            sheetCombo.addItem("Όλα τα φύλλα");

            for (Map.Entry<String, List<XRow>> entry : book.entrySet()) {
                String sourceSheet = entry.getKey();
                List<XRow> data = entry.getValue();
                if (data.size() < 2) continue;

                XRow header = data.get(0);
                int minCol = findHeaderColumn(header, true);
                int maxCol = findHeaderColumn(header, false);
                boolean legacyReport = isLegacyPriceReport(header);
                String sheetKind = kindOf(sourceSheet);

                for (int i = 1; i < data.size(); i++) {
                    XRow x = data.get(i);
                    String kind = sheetKind;
                    String label;
                    String keyword;
                    String excelMin = minCol >= 0 ? x.get(minCol) : "";
                    String excelMax = maxCol >= 0 ? x.get(maxCol) : "";

                    if (legacyReport) {
                        label = x.get(2);
                        LegacyPart parsed = parseLegacyPartLabel(label);
                        kind = parsed.kind;
                        keyword = parsed.keyword;
                        if ((excelMin == null || excelMin.isBlank()) && !parsed.previousMin.isBlank()) {
                            excelMin = parsed.previousMin;
                        }
                        if (minCol < 0 && (excelMin == null || excelMin.isBlank())) excelMin = x.get(4);
                        if (maxCol < 0) excelMax = x.get(5);
                    } else {
                        if (kind.equals("other")) {
                            kind = inferKindFromRow(x);
                        }
                        if (kind.equals("other")) continue;
                        label = buildPartLabel(kind, x);
                        keyword = buildInputKeyword(kind, x);
                    }

                    if (!kind.matches("cpu|gpu|ram") || keyword.isBlank()) continue;
                    SoldRow incoming = new SoldRow(sourceSheet, x.excelRow, kind, label,
                            keyword, excelMin, excelMax);
                    SoldRow existing = uniqueRows.get(incoming.historyKey());
                    if (existing == null) uniqueRows.put(incoming.historyKey(), incoming);
                    else mergeImportedRow(existing, incoming);
                }
            }

            rows.addAll(uniqueRows.values());
            for (SoldRow r : rows) {
                String kindLabel = r.kind.toUpperCase(Locale.ROOT);
                if (!sheetComboHasItem(kindLabel)) sheetCombo.addItem(kindLabel);
            }
            model.setRows(rows);
            applySheetFilter();
            soldDetail.setText("");
            previousMinima.clear();
            newLowHistory.clear();
            monitorFile = buildMonitorFile(file);
            loadPreviousMonitor();
            outputPathLabel.setText("Αποτελέσματα: " + monitorFile.getAbsolutePath());
            openOutputButton.setEnabled(monitorFile.isFile());
            runAllButton.setEnabled(!rows.isEmpty());
            exportViewButton.setEnabled(!rows.isEmpty());

            if (rows.isEmpty()) {
                soldStatus.setText("Δεν βρέθηκαν CPU / GPU / RAM. Χρειάζονται φύλλα με αυτά τα ονόματα ή παλιό report με το πεδίο «Στοιχείο».");
            } else {
                Map<String, Integer> byKind = new LinkedHashMap<>();
                for (String kind : Arrays.asList("cpu", "gpu", "ram")) byKind.put(kind, 0);
                for (SoldRow row : rows) byKind.put(row.kind, byKind.getOrDefault(row.kind, 0) + 1);
                soldStatus.setText("Φορτώθηκαν " + rows.size() + " μοναδικά εξαρτήματα · CPU " + byKind.get("cpu")
                        + " · GPU " + byKind.get("gpu") + " · RAM " + byKind.get("ram")
                        + (byKind.get("cpu") == 0 ? " · Δεν υπάρχουν CPU στο επιλεγμένο Excel." : "")
                        + " Ο έλεγχος περνά από όλα τα εξαρτήματα, ανεξάρτητα από το φίλτρο.");
            }
        }

        private void mergeImportedRow(SoldRow existing, SoldRow incoming) {
            // The legacy report contains the same GPU/RAM list twice. Scan a model once,
            // but keep the richest label and any baseline min/max values from either copy.
            if (incoming.label != null && incoming.label.length() >
                    (existing.label == null ? 0 : existing.label.length())) {
                existing.label = incoming.label;
            }
            Double oldMin = parseOptionalPrice(existing.excelMin);
            Double newMin = parseOptionalPrice(incoming.excelMin);
            if (newMin != null && (oldMin == null || newMin < oldMin)) existing.excelMin = incoming.excelMin;
            Double oldMax = parseOptionalPrice(existing.excelMax);
            Double newMax = parseOptionalPrice(incoming.excelMax);
            if (newMax != null && (oldMax == null || newMax > oldMax)) existing.excelMax = incoming.excelMax;
        }

        private boolean sheetComboHasItem(String name) {
            for (int i = 0; i < sheetCombo.getItemCount(); i++) {
                if (name.equals(sheetCombo.getItemAt(i))) return true;
            }
            return false;
        }

        private int findHeaderColumn(XRow header, boolean min) {
            for (int c = 0; c < header.cells.length; c++) {
                String h = normalize(header.get(c));
                boolean price = h.contains("price") || h.contains("τιμ") || h.contains("min") || h.contains("max");
                if (price && (min ? h.contains("min") : h.contains("max"))) return c;
            }
            return -1;
        }

        private boolean isLegacyPriceReport(XRow header) {
            String a = normalize(header.get(0));
            String b = normalize(header.get(1));
            String c = normalize(header.get(2));
            return (a.equals("sheet") || a.contains("φυλλ"))
                    && (b.contains("excel") || b.contains("γραμμ"))
                    && (c.contains("στοιχει") || c.contains("part"));
        }

        private static class LegacyPart {
            final String kind;
            final String keyword;
            final String previousMin;
            LegacyPart(String kind, String keyword, String previousMin) {
                this.kind = kind;
                this.keyword = keyword;
                this.previousMin = previousMin == null ? "" : previousMin;
            }
        }

        private static LegacyPart parseLegacyPartLabel(String label) {
            if (label == null || label.isBlank()) return new LegacyPart("other", "", "");
            String[] parts = label.split("\\|");
            int kindAt = -1;
            String kind = "other";
            for (int i = 0; i < parts.length; i++) {
                String n = SpecParser.norm(parts[i]);
                if (n.matches(".*(?<![a-z])(?:cpu|επεξεργαστησ)(?![a-z]).*")) { kind = "cpu"; kindAt = i; break; }
                if (n.matches(".*(?<![a-z])(?:gpu|καρτα γραφικων)(?![a-z]).*")) { kind = "gpu"; kindAt = i; break; }
                if (n.matches(".*(?<![a-z])ram(?![a-z]).*")) { kind = "ram"; kindAt = i; break; }
            }
            if (kindAt < 0) return new LegacyPart("other", "", "");

            List<String> terms = new ArrayList<>();
            for (int i = kindAt + 1; i < parts.length; i++) {
                String v = parts[i].trim();
                if (v.isBlank()) continue;
                // Older exports include the original Excel row number after the type.
                if (i == kindAt + 1 && v.matches("\\d+")) continue;
                terms.add(v);
            }

            // In some legacy workbooks the last pipe-separated cell is the previous
            // minimum price (e.g. "GPU | AMD RX 570 | 50"). Keep it as a baseline,
            // never as a model-search token.
            String previousMin = "";
            if (terms.size() >= 2 && terms.get(terms.size() - 1).matches("(?i)(?:€\\s*)?\\d{1,5}(?:[.,]\\d{1,2})?\\s*(?:€|eur)?")) {
                String preceding = SpecParser.norm(String.join(" ", terms.subList(0, terms.size() - 1)));
                boolean hasModel = kind.equals("gpu")
                        ? (preceding.matches(".*(?:rtx|gtx|rx|radeon|quadro|arc|vega).*\\d+.*") || preceding.contains("radeon vii") || preceding.contains("titan"))
                        : kind.equals("cpu")
                        ? preceding.matches(".*(?:i[3579]|ryzen|xeon|pentium|celeron|athlon|core\\s*2).*\\d+.*")
                        : preceding.matches(".*(?:ddr[345]|\\d+\\s*gb).*\\d+.*");
                if (hasModel) previousMin = terms.remove(terms.size() - 1).replaceAll("(?i)€|eur", "").trim();
            }

            String keyword = String.join(" ", terms)
                    .replace('—', ' ').replace('–', ' ')
                    .replaceAll("(?i)\\bpc\\s+(?:gaming\\s+)?ram\\b", " ")
                    .replaceAll("(?i)\\b(?:cpu|gpu|ram)\\b", " ")
                    .replaceAll("\\s+", " ").trim();

            if (kind.equals("gpu")) {
                keyword = extractGpuSearchTerm(String.join(" ", terms));
            } else if (kind.equals("ram")) {
                keyword = keyword.replaceAll("(?i)\\bmhz\\b", " ")
                        .replaceAll("[—–-]+", " ").replaceAll("\\s+", " ").trim();
            }
            if (kind.equals("cpu")) {
                keyword = keyword.replaceAll("(?i)\\b(?:intel|amd|processor)\\b", " ")
                        .replaceAll("(?i)\\bcore(?=\\s*i[3579])", " ")
                        .replaceAll("\\s+", " ").trim();
            }
            return new LegacyPart(kind, keyword, previousMin);
        }

        private static String extractGpuSearchTerm(String source) {
            String value = source == null ? "" : source;
            Pattern model = Pattern.compile("(?i)(?:RX\\s*VEGA\\s*\\d{2}|RADEON\\s+VII|"
                    + "(?:RTX|GTX|GT|RX|QUADRO|ARC|RADEON(?:\\s+RX)?)\\s*"
                    + "\\d{3,4}(?:\\s*(?:TI|SUPER|XTX|XT|GRE)){0,2}"
                    + "(?:\\s*\\d{1,2}\\s?GB)?)");
            Matcher matcher = model.matcher(value);
            if (matcher.find()) return matcher.group().replaceAll("\\s+", " ").trim();

            return value.replaceAll("(?i)\\b(?:AMD|NVIDIA|POLARIS|RDNA\\s*[1-4]|"
                            + "TURING|AMPERE|BLACKWELL|PASCAL|MAXWELL|ADA\\s+LOVELACE|VEGA)\\b", " ")
                    .replaceAll("(?i)\\b(?:GPU|VGA)\\b", " ")
                    .replaceAll("[—–-]+", " ").replaceAll("\\s+", " ").trim();
        }

        private String inferKindFromRow(XRow row) {
            String joined = normalize(String.join(" ", row.cells));
            if (joined.contains("ram") || joined.contains("ddr")) return "ram";
            if (joined.contains("gpu") || joined.contains("rtx") || joined.contains("gtx") || joined.contains("radeon")) return "gpu";
            if (joined.contains("cpu") || joined.contains("ryzen") || joined.contains("xeon") || joined.matches(".*\\bi[3579]\\b.*")) return "cpu";
            return "other";
        }

        private String buildPartLabel(String kind, XRow row) {
            List<String> pieces = new ArrayList<>();
            int max = Math.min(4, row.cells.length);
            for (int c = 0; c < max; c++) {
                if (!row.get(c).isBlank()) pieces.add(row.get(c).trim());
            }
            if (pieces.isEmpty()) return kind.toUpperCase(Locale.ROOT);
            return String.join(" | ", pieces);
        }

        private String buildInputKeyword(String kind, XRow row) {
            if (kind.equals("ram")) {
                String cap = row.get(1).replaceAll("\\s+", "");
                String[] p = row.get(2).trim().split("[\\s\\-]+", 3);
                StringBuilder sb = new StringBuilder(cap);
                if (p.length > 0 && !p[0].isEmpty()) sb.append(' ').append(p[0]);
                if (ramSpeedBox.isSelected() && p.length > 1) sb.append(' ').append(p[1]);
                return sb.toString().trim();
            }
            if (kind.equals("gpu")) {
                String model = row.get(1).isBlank() ? row.get(0) : row.get(1);
                return model.replaceAll("\\s+", " ").trim();
            }
            String cpu = row.get(0)
                    .replaceAll("(?i)\\(r\\)|\\(tm\\)|™|®", " ")
                    .replaceAll("(?i)(?<![\\p{L}\\p{N}])(intel|amd|processor|cpu)(?![\\p{L}\\p{N}])", " ")
                    .replaceAll("(?i)(?<![\\p{L}\\p{N}])core(?=\\s+i[3579])", " ");
            return cpu.replace('-', ' ').replaceAll("\\s+", " ").trim();
        }

        private String kindOf(String sheet) {
            String s = normalize(sheet);
            if (s.contains("ram") || s.contains("μνημ") || s.contains("μνημεσ")) return "ram";
            if (s.contains("gpu") || s.contains("καρτεσ γραφικων") || s.contains("καρτα γραφικων")) return "gpu";
            if (s.contains("cpu") || s.contains("επεξεργαστεσ") || s.contains("επεξεργαστησ")) return "cpu";
            return "other";
        }

        private void applySheetFilter() {
            if (sheetCombo.getSelectedIndex() <= 0) {
                rowSorter.setRowFilter(null);
            } else {
                String sheet = (String) sheetCombo.getSelectedItem();
                rowSorter.setRowFilter(RowFilter.regexFilter("^" + Pattern.quote(sheet) + "$", 0));
            }
        }

        private void showDetail() {
            int viewRow = table.getSelectedRow();
            if (viewRow < 0) {
                soldDetail.setText("");
                return;
            }
            SoldRow r = model.get(table.convertRowIndexToModel(viewRow));
            soldDetail.setText(detailToHtml(r.detail.isBlank() ? r.status : r.detail));
            soldDetail.setCaretPosition(0);
        }

        private List<SoldRow> selectedRows() {
            List<SoldRow> selected = new ArrayList<>();
            for (int viewRow : table.getSelectedRows()) selected.add(model.get(table.convertRowIndexToModel(viewRow)));
            return selected;
        }

        private void openBestListing() {
            List<SoldRow> selected = selectedRows();
            if (selected.isEmpty()) return;
            SoldRow r = selected.get(0);
            MarketListing target = r.cheapest != null ? r.cheapest : r.cheapestPc;
            if (target != null) openUrl(target.listing.url);
        }

        private void openOutputFile() {
            try {
                if (monitorFile != null && monitorFile.isFile() && Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().open(monitorFile);
                }
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Δεν ήταν δυνατό το άνοιγμα του Excel:\n" + rootMessage(ex));
            }
        }

        /*
         * Εξαγωγή του πίνακα σε Excel ΑΚΡΙΒΩΣ όπως φαίνεται στο παράθυρο:
         * οι ίδιες στήλες (με την ίδια σειρά), οι ίδιες γραμμές με την ίδια ταξινόμηση
         * και το ίδιο φίλτρο φύλλου (ΟΛΑ / CPU / GPU / RAM). Οι στήλες «Link ...»
         * γίνονται πατήσιμοι σύνδεσμοι.
         */
        private void exportViewToExcel() {
            if (table.getRowCount() == 0) {
                JOptionPane.showMessageDialog(this, "Ο πίνακας δεν έχει γραμμές για εξαγωγή.");
                return;
            }

            String baseName = "insomnia_view";
            File folder = new File(".").getAbsoluteFile();
            if (monitorFile != null) {
                baseName = monitorFile.getName().replaceFirst("(?i)\\.xlsx$", "");
                if (monitorFile.getAbsoluteFile().getParentFile() != null) {
                    folder = monitorFile.getAbsoluteFile().getParentFile();
                }
            }
            String stamp = LocalDateTime.now(ZoneId.of("Europe/Athens")).toString()
                    .replace('T', '_').replace(':', '-');
            stamp = stamp.length() >= 16 ? stamp.substring(0, 16) : stamp;

            JFileChooser chooser = new JFileChooser(folder);
            chooser.setDialogTitle("Λήψη Excel (όπως φαίνεται στο παράθυρο)");
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Excel (*.xlsx)", "xlsx"));
            chooser.setSelectedFile(new File(folder, baseName + "_view_" + stamp + ".xlsx"));
            if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;

            File out = chooser.getSelectedFile();
            if (!out.getName().toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
                out = new File(out.getParentFile(), out.getName() + ".xlsx");
            }

            int cols = table.getColumnCount();
            String[] headers = new String[cols];
            for (int c = 0; c < cols; c++) headers[c] = table.getColumnName(c);

            List<Object[]> rows = new ArrayList<>();
            for (int r = 0; r < table.getRowCount(); r++) {
                Object[] row = new Object[cols];
                for (int c = 0; c < cols; c++) {
                    Object v = table.getValueAt(r, c);
                    if (v instanceof String && ((String) v).isBlank()) v = null;
                    row[c] = v;
                }
                rows.add(row);
            }

            String sheetName = sheetCombo.getSelectedIndex() <= 0
                    ? "Εξαρτήματα" : "Εξαρτήματα " + sheetCombo.getSelectedItem();
            sheetName = sheetName.replaceAll("[\\\\/?*\\[\\]:]", " ");
            if (sheetName.length() > 31) sheetName = sheetName.substring(0, 31);

            try {
                SoldTools.writeXlsxBook(out, Collections.singletonList(new XlsxSheet(sheetName, headers, rows)));
                soldStatus.setText("Αποθηκεύτηκε Excel (" + rows.size() + " γραμμές): " + out.getAbsolutePath());
                if (Desktop.isDesktopSupported()) {
                    try { Desktop.getDesktop().open(out); } catch (Exception ignored) { }
                }
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this, "Δεν ήταν δυνατή η αποθήκευση του Excel:\n" + rootMessage(ex),
                        "Excel", JOptionPane.ERROR_MESSAGE);
            }
        }

        private File buildMonitorFile(File input) {
            String name = input.getName().replaceFirst("(?i)\\.xlsx$", "");
            File parent = input.getAbsoluteFile().getParentFile();
            return new File(parent, name + (facebook ? "_facebook_monitor.xlsx" : vinted ? "_vinted_monitor.xlsx"
                    : vendora ? "_vendora_monitor.xlsx" : "_insomnia_monitor.xlsx"));
        }

        private void loadPreviousMonitor() {
            if (monitorFile == null || !monitorFile.isFile()) return;
            try {
                Map<String, List<XRow>> book = SoldTools.readXlsx(monitorFile);
                List<XRow> summary = book.get("Τιμές αγοράς");
                if (summary != null && !summary.isEmpty()) {
                    XRow h = summary.get(0);
                    int keyCol = findExactColumn(h, "key");
                    int minCol = findExactColumn(h, "all-time min (€)");
                    if (minCol < 0) minCol = findExactColumn(h, "min (€)");
                    for (int i = 1; i < summary.size(); i++) {
                        XRow row = summary.get(i);
                        if (keyCol >= 0 && minCol >= 0) {
                            Double value = parsePrice(row.get(minCol));
                            if (value != null) previousMinima.put(row.get(keyCol), value);
                        }
                    }
                }
                List<XRow> history = book.get("Νέες χαμηλές τιμές");
                if (history != null && !history.isEmpty()) {
                    for (int i = 1; i < history.size(); i++) {
                        XRow x = history.get(i);
                        Object[] old = new Object[historyHeaders.length];
                        for (int c = 0; c < old.length; c++) old[c] = valueOrNull(x.get(c));
                        newLowHistory.add(old);
                    }
                }
            } catch (Exception ex) {
                soldStatus.setText("Δεν φορτώθηκε η προηγούμενη ιστορία τιμών: " + rootMessage(ex));
            }
        }

        private int findExactColumn(XRow header, String name) {
            for (int c = 0; c < header.cells.length; c++) {
                if (normalize(header.get(c)).equals(normalize(name))) return c;
            }
            return -1;
        }

        private Object valueOrNull(String s) {
            if (s == null || s.isBlank()) return null;
            Double d = parsePrice(s);
            return d == null ? s : d;
        }

        private void runAll() {
            if (model.getRowCount() == 0) {
                loadExcel();
                if (model.getRowCount() == 0) return;
            }
            if (worker != null && !worker.isDone()) return;

            int pages = 5;
            try { pages = Integer.parseInt(pagesField.getText().trim()); }
            catch (NumberFormatException ignored) { }
            pages = Math.max(1, Math.min(pages, 10));
            final SoldParams p = new SoldParams();
            p.pages = pages;
            if (facebook) {
                String typed = vendoraCategoryField.getText().trim();
                if (typed.isEmpty()) typed = FacebookTools.DEFAULT_CITY;
                facebookCookie = facebookCookieField.getText().trim();
                p.vendoraRoot = typed;
            } else if (vinted) {
                setVintedEnglish(false);
                String typed = vendoraCategoryField.getText().trim();
                if (typed.isEmpty()) typed = VINTED_CATALOG_URL;
                if (!typed.startsWith(VINTED_BASE)) {
                    JOptionPane.showMessageDialog(this, "Το URL πρέπει να ξεκινά με " + VINTED_BASE);
                    return;
                }
                // Κρατιέται ολόκληρο (με τα φίλτρα του) — διαβάζεται από το searchVintedKeyword.
                p.vendoraRoot = typed;
            } else if (vendora) {
                String typed = vendoraCategoryField.getText().trim();
                if (typed.isEmpty()) typed = VENDORA_GENERAL_URL;
                if (!typed.startsWith(VENDORA_BASE)) {
                    JOptionPane.showMessageDialog(this, "Το URL πρέπει να ξεκινά με " + VENDORA_BASE);
                    return;
                }
                p.vendoraRoot = removeQueryAndFragment(typed);
            }
            for (String term : excludeField.getText().split("[,;\\n\\r]+")) {
                String n = normalize(term.trim());
                if (!n.isBlank()) p.excluded.add(n);
            }

            searchCache.clear();
            marketDetailCache.clear();
            synchronized (fullSystemsIndexLock) { fullSystemsIndexFuture = null; }
            fullSystemsListings.clear();
            runOffers.clear();
            fullSystemsIndexNote = "";
            debugSaved.set(false);
            for (SoldRow r : model.all()) {
                r.reset();
                model.refresh(r);
            }

            runAllButton.setEnabled(false);
            loadButton.setEnabled(false);
            cancelButton.setEnabled(true);
            openOutputButton.setEnabled(false);
            final int total = model.getRowCount();
            soldStatus.setText("Έλεγχος 0 / " + total + " εξαρτημάτων...");

            worker = new SwingWorker<Void, Void>() {
                @Override protected Void doInBackground() throws Exception {
                    ExecutorService rowPool = Executors.newFixedThreadPool(Math.min(facebook ? 1 : vinted ? 2 : vendora ? 3 : SOLD_ROW_THREADS, total));
                    ExecutorService detailPool = Executors.newFixedThreadPool(facebook ? 2 : vinted ? VINTED_THREADS
                            : vendora ? VENDORA_THREADS : SOLD_DETAIL_THREADS);
                    AtomicInteger done = new AtomicInteger();
                    try {
                        List<Callable<Void>> tasks = new ArrayList<>();
                        for (SoldRow r : model.all()) {
                            tasks.add(() -> {
                                try {
                                    processRow(r, p, detailPool);
                                } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                    r.status = "Ακυρώθηκε";
                                } catch (Exception ex) {
                                    r.status = "Σφάλμα: " + rootMessage(ex);
                                }
                                int n = done.incrementAndGet();
                                setSoldStatus("Έλεγχος " + n + " / " + total + " · " + r.kind.toUpperCase(Locale.ROOT) + " · " + r.label);
                                SwingUtilities.invokeLater(() -> model.refresh(r));
                                return null;
                            });
                        }
                        rowPool.invokeAll(tasks);
                    } finally {
                        rowPool.shutdownNow();
                        detailPool.shutdownNow();
                    }
                    return null;
                }

                @Override protected void done() {
                    runAllButton.setEnabled(true);
                    loadButton.setEnabled(true);
                    cancelButton.setEnabled(false);
                    try {
                        get();
                        saveMonitorWorkbook();
                        if (!runOffers.isEmpty()) openOffersTab(new ArrayList<>(runOffers));
                        int lows = newLowHistory.size();
                        soldStatus.setText("Ολοκληρώθηκε ο έλεγχος " + total + " εξαρτημάτων · " + lows
                                + " καταγραφές χαμηλών τιμών στο ιστορικό."
                                + (fullSystemsIndexNote.isBlank() ? "" : " · " + fullSystemsIndexNote));
                        openOutputButton.setEnabled(monitorFile != null && monitorFile.isFile());
                    } catch (CancellationException ex) {
                        soldStatus.setText("Ο έλεγχος ακυρώθηκε. Τα ήδη ελεγμένα αποτελέσματα παραμένουν στον πίνακα.");
                    } catch (Exception ex) {
                        soldStatus.setText("Σφάλμα ελέγχου / αποθήκευσης: " + rootMessage(ex));
                    }
                    showDetail();
                }
            };
            worker.execute();
        }

        /*
         * 1) ΕΞΑΡΤΗΜΑ: αναζήτηση σε όλο το site (Insomnia) ή στη γενική κατηγορία (Vendora),
         *    χωρίς τα πλήρη PC -> Min/Max τιμές του εξαρτήματος.
         * 2) ΠΛΗΡΗ PC: ΜΟΝΟ από την κατηγορία «Πλήρη Συστήματα» (Insomnia) ή «Σταθεροί
         *    Υπολογιστές» (Vendora) -> τα φθηνότερα PC που περιέχουν το εξάρτημα.
         */
        private void processRow(SoldRow r, SoldParams p, ExecutorService detailPool) throws Exception {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();

            List<Listing> found = facebook
                    ? searchFacebookKeyword(r.keyword, p.vendoraRoot, p)
                    : vinted
                    ? searchVintedKeyword(r.keyword, p.vendoraRoot, p)
                    : vendora
                    ? searchVendoraKeyword(r.keyword, p.vendoraRoot, p)
                    : searchKeyword(r.keyword, p);

            Set<String> systemUrls = Collections.emptySet();
            if (insomnia()) {
                try {
                    systemUrls = fullSystemsCategoryIndex();
                } catch (Exception indexError) {
                    fullSystemsIndexNote = "Δεν φορτώθηκε το ευρετήριο Πλήρη Συστήματα: " + rootMessage(indexError);
                    setSoldStatus("Προειδοποίηση: " + fullSystemsIndexNote);
                }
            }

            int skippedBulk = 0;
            List<MarketListing> candidates = new ArrayList<>();
            for (Listing l : found) {
                if (insomnia() && !"Πώληση".equals(l.transaction)) continue;
                // Τα πλήρη PC του Insomnia έχουν δική τους αναζήτηση (βήμα 2).
                if (insomnia() && systemUrls.contains(removeQueryAndFragment(l.url))) continue;
                // Το Facebook επιστρέφει και «κοντινά» αποτελέσματα (π.χ. RTX 3050 για RTX 3060).
                if (facebook && !SoldTools.matchesKeyword(l.title + " " + l.description, r.keyword)) continue;
                String cardText = normalize(l.title + " " + l.description);
                boolean excluded = false;
                for (String ex : p.excluded) if (cardText.contains(ex)) { excluded = true; break; }
                if (excluded) continue;
                // Ομαδικές / πολλαπλές αγγελίες (π.χ. «κάρτες από 5 έως 10 €») δεν μετράνε στα Min/Max.
                if (l.flaggedManySpecs || l.flaggedKeywords) { skippedBulk++; continue; }
                candidates.add(new MarketListing(l));
            }

            // Ανοίγουμε τις 10 φθηνότερες, τις 10 ακριβότερες και όσες δεν έχουν τιμή στην κάρτα.
            candidates.sort(Comparator.comparingDouble(x -> x.price == null ? Double.MAX_VALUE : x.price));
            Set<String> inspectUrls = new LinkedHashSet<>();
            int edge = Math.min(10, candidates.size());
            for (int i = 0; i < edge; i++) inspectUrls.add(candidates.get(i).listing.url);
            for (int i = Math.max(0, candidates.size() - edge); i < candidates.size(); i++) {
                inspectUrls.add(candidates.get(i).listing.url);
            }
            for (MarketListing hit : candidates) if (hit.price == null) inspectUrls.add(hit.listing.url);

            Map<String, Listing> targetsByUrl = new LinkedHashMap<>();
            for (MarketListing hit : candidates) {
                if (inspectUrls.contains(hit.listing.url)) targetsByUrl.put(hit.listing.url, hit.listing);
            }
            ExecutorCompletionService<MarketListing> completed = new ExecutorCompletionService<>(detailPool);
            for (Listing l : targetsByUrl.values()) completed.submit(() -> marketDetails(l));

            Map<String, MarketListing> inspected = new HashMap<>();
            int errors = 0;
            for (int i = 0; i < targetsByUrl.size(); i++) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                try {
                    MarketListing hit = completed.take().get();
                    inspected.put(hit.listing.url, hit);
                } catch (ExecutionException ex) {
                    errors++;
                }
            }

            List<MarketListing> partOffers = new ArrayList<>();
            int missingPrice = 0;
            for (MarketListing original : candidates) {
                MarketListing hit = inspected.getOrDefault(original.listing.url, original);
                boolean pageWasOpened = inspected.containsKey(hit.listing.url);
                // Ό,τι ανήκει στην κατηγορία πλήρων PC δεν είναι «εξάρτημα».
                if (hit.fullSystemsCategory || normalize(hit.listing.category).contains("plhri systhmata")) continue;

                String combined = hit.listing.title + " " + hit.fullDescription;
                if (pageWasOpened) {
                    if (SpecParser.hasSuspiciousKeywords(hit.listing.title, hit.fullDescription)) {
                        skippedBulk++;
                        continue;
                    }
                    String normalized = normalize(combined);
                    boolean excluded = false;
                    for (String ex : p.excluded) if (normalized.contains(ex)) { excluded = true; break; }
                    if (excluded) continue;
                }

                // Οι μη ανοιγμένες αγγελίες είναι ήδη ταιριάσματα πλήρους κειμένου του site·
                // για όσες ανοίχτηκαν ελέγχεται ο πλήρης τίτλος + περιγραφή.
                boolean exactMatch = !pageWasOpened || SoldTools.matchesKeyword(combined, r.keyword);
                String variantText = pageWasOpened ? combined : hit.listing.title + " " + hit.listing.description;
                if (!exactMatch || (r.kind.equals("gpu") && SoldTools.variantMismatch(variantText, r.keyword))) continue;
                if (hit.price == null || hit.price <= 0) { missingPrice++; continue; }
                if (isStandalonePart(r.kind, hit.listing.title, hit.fullDescription)) partOffers.add(hit);
            }
            partOffers.sort(Comparator.comparingDouble(x -> x.price));

            List<MarketListing> systems = findWholePcs(r, p, detailPool, systemUrls);
            r.apply(partOffers, systems, found.size(), inspected.size(), errors, missingPrice);
            if (skippedBulk > 0) {
                r.detail = r.detail + "\nΑποκλείστηκαν ως ομαδικές / πολλαπλές αγγελίες: " + skippedBulk;
            }
        }

        /*
         * Φθηνότερα πλήρη PC που περιέχουν το εξάρτημα, ΜΟΝΟ από την κατηγορία πλήρων PC.
         * Ανοίγονται κατά φθίνουσα φθηνότητα, σε παρτίδες, μέχρι να επαληθευτούν τουλάχιστον
         * SYSTEMS_MIN PC (ή να τελειώσουν οι υποψήφιες). Αγγελίες με παράλογη τιμή
         * (< MIN_PC_PRICE) παραλείπονται ώστε να μη χαλάνε το «φθηνότερο PC».
         */
        private List<MarketListing> findWholePcs(SoldRow r, SoldParams p, ExecutorService detailPool,
                                                 Set<String> systemUrls) throws Exception {
            // Το Vinted δεν έχει κατηγορία «πλήρη PC» με σταθερή δομή: δεν ψάχνονται φθηνότερα PC (Link PC 1-3 μένουν κενά).
            if (vinted || facebook) return new ArrayList<>();
            Map<String, Listing> pool = new LinkedHashMap<>();
            if (vendora) {
                for (Listing l : searchVendoraKeyword(r.keyword, removeQueryAndFragment(VENDORA_DESKTOPS_URL), p)) {
                    pool.putIfAbsent(removeQueryAndFragment(l.url), l);
                }
            } else {
                // α) αποτελέσματα αναζήτησης που ανήκουν στην κατηγορία
                for (Listing l : searchKeyword(r.keyword, p)) {
                    String url = removeQueryAndFragment(l.url);
                    if (systemUrls.contains(url)) pool.putIfAbsent(url, l);
                }
                // β) αγγελίες της κατηγορίας που ταιριάζουν ήδη από την κάρτα τους
                for (Map.Entry<String, Listing> e : fullSystemsListings.entrySet()) {
                    Listing l = e.getValue();
                    String text = l.title + " " + (l.description == null ? "" : l.description);
                    if (SoldTools.matchesKeyword(text, r.keyword)
                            && (!r.kind.equals("gpu") || !SoldTools.variantMismatch(text, r.keyword))) {
                        pool.putIfAbsent(e.getKey(), l);
                    }
                }
            }

            List<Listing> usable = new ArrayList<>();
            for (Listing l : pool.values()) {
                if (insomnia() && !"Πώληση".equals(l.transaction)) continue;
                // Παράλογη τιμή κάρτας (0.01 €, 50 € κλπ): δεν ανοίγεται καν.
                if (l.price != null && l.price < MIN_PC_PRICE) continue;
                String cardText = normalize(l.title + " " + l.description);
                boolean excluded = false;
                for (String ex : p.excluded) if (cardText.contains(ex)) { excluded = true; break; }
                if (excluded) continue;
                if (hasWord(cardText, "laptop") || hasWord(cardText, "notebook") || hasWord(cardText, "λαπτοπ")) continue;
                if (l.flaggedManySpecs || l.flaggedKeywords) continue;
                usable.add(l);
            }
            usable.sort(Comparator.comparingDouble(x -> x.price == null ? Double.MAX_VALUE : x.price));

            List<MarketListing> verified = new ArrayList<>();
            int opened = 0;
            int index = 0;
            while (index < usable.size() && verified.size() < SYSTEMS_MIN && opened < SYSTEMS_MAX_OPEN) {
                int to = Math.min(usable.size(), index + SYSTEMS_BATCH);
                ExecutorCompletionService<MarketListing> batch = new ExecutorCompletionService<>(detailPool);
                int submitted = 0;
                for (int i = index; i < to; i++) {
                    Listing l = usable.get(i);
                    batch.submit(() -> marketDetails(l));
                    submitted++;
                }
                index = to;
                opened += submitted;
                for (int i = 0; i < submitted; i++) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    try {
                        MarketListing hit = batch.take().get();
                        hit.fullSystemsCategory = true; // προέρχεται από την κατηγορία πλήρων PC
                        if (verifyWholePc(r, p, hit)) verified.add(hit);
                    } catch (ExecutionException ignored) {
                    }
                }
            }
            verified.sort(Comparator.comparingDouble(x -> x.price));
            if (verified.size() > SYSTEMS_KEEP) return new ArrayList<>(verified.subList(0, SYSTEMS_KEEP));
            return verified;
        }

        private boolean verifyWholePc(SoldRow r, SoldParams p, MarketListing hit) {
            // Παράλογες τιμές (π.χ. 0.01 €, 50 €) δεν είναι πραγματικά πλήρη PC.
            if (hit.price == null || hit.price <= 0 || hit.price < MIN_PC_PRICE) return false;
            String combined = hit.listing.title + " " + hit.fullDescription;
            String normalized = normalize(combined);
            for (String ex : p.excluded) if (normalized.contains(ex)) return false;
            if (hasWord(normalized, "laptop") || hasWord(normalized, "notebook") || hasWord(normalized, "λαπτοπ")) return false;
            if (SpecParser.hasSuspiciousKeywords(hit.listing.title, hit.fullDescription)) return false;
            if (!matchesSystemComponent(r.kind, r.keyword, hit)) return false;
            return !(r.kind.equals("gpu") && SoldTools.variantMismatch(combined, r.keyword));
        }

        private MarketListing pcAt(SoldRow r, int i) {
            List<MarketListing> list = r.cheapestPcs;
            return list != null && list.size() > i ? list.get(i) : null;
        }

        private Set<String> fullSystemsCategoryIndex() throws Exception {
            CompletableFuture<Set<String>> future;
            boolean build = false;
            synchronized (fullSystemsIndexLock) {
                future = fullSystemsIndexFuture;
                if (future == null) {
                    future = new CompletableFuture<>();
                    fullSystemsIndexFuture = future;
                    build = true;
                }
            }
            if (build) {
                try {
                    future.complete(fetchFullSystemsCategoryIndex());
                } catch (Exception ex) {
                    future.completeExceptionally(ex);
                }
            }
            try {
                return future.get();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw ie;
            } catch (ExecutionException ex) {
                Throwable cause = ex.getCause();
                if (cause instanceof Exception) throw (Exception) cause;
                throw new IOException("Αποτυχία ανάγνωσης της κατηγορίας Πλήρη Συστήματα", cause);
            }
        }

        private Set<String> fetchFullSystemsCategoryIndex() throws Exception {
            String root = removeQueryAndFragment(FULL_SYSTEMS_CATEGORY_URL);
            LinkedHashSet<String> urls = new LinkedHashSet<>();
            Document first = connectSold(root);
            addCategoryPageUrls(urls, parseListings(first, "Πλήρη Συστήματα"));

            Set<String> pagination = findPaginationUrls(first, root);
            int maxPage = 1;
            String template = null;
            for (String pageUrl : pagination) {
                int number = pageNumberOf(pageUrl);
                if (number > maxPage && number <= 100) {
                    maxPage = number;
                    template = removeFragmentOnly(pageUrl);
                }
            }

            int failedPages = 0;
            for (int page = 2; page <= maxPage; page++) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                String pageUrl = template == null ? root + "/page/" + page + "/" : buildPageUrl(template, page);
                try {
                    Document doc = connectSold(pageUrl);
                    addCategoryPageUrls(urls, parseListings(doc, "Πλήρη Συστήματα"));
                    for (String discovered : findPaginationUrls(doc, root)) {
                        int number = pageNumberOf(discovered);
                        if (number > maxPage && number <= 100) {
                            maxPage = number;
                            template = removeFragmentOnly(discovered);
                        }
                    }
                } catch (InterruptedException ie) {
                    throw ie;
                } catch (Exception ex) {
                    failedPages++;
                }
                if (page % 5 == 0 || page == maxPage) {
                    setSoldStatus("Ευρετήριο Πλήρη Συστήματα: σελίδα " + page + " / " + maxPage
                            + " · αγγελίες " + urls.size());
                }
            }
            if (urls.isEmpty()) throw new IOException("Δεν βρέθηκαν αγγελίες στην κατηγορία Πλήρη Συστήματα.");
            if (failedPages > 0) {
                fullSystemsIndexNote = "Μερικό ευρετήριο Πλήρη Συστήματα: " + urls.size()
                        + " αγγελίες · " + failedPages + " σελίδες απέτυχαν";
                setSoldStatus(fullSystemsIndexNote);
            }
            return Collections.unmodifiableSet(urls);
        }

        private void addCategoryPageUrls(Set<String> urls, List<Listing> listings) {
            for (Listing listing : listings) {
                if (listing.url != null && !listing.url.isBlank()) {
                    String clean = removeQueryAndFragment(listing.url);
                    urls.add(clean);
                    fullSystemsListings.putIfAbsent(clean, listing);
                }
            }
        }

        private boolean kindLooksLikeFullSystem(String kind, String title, String description) {
            String t = normalize((title == null ? "" : title) + " " + (description == null ? "" : description));
            // Laptops are not the complete desktop-PC comparison requested in this tab.
            if (hasWord(t, "laptop") || hasWord(t, "notebook") || hasWord(t, "λαπτοπ")) return false;
            for (String w : new String[]{"pc", "desktop", "workstation", "υπολογιστησ", "συστημα", "πληρεσ", "tower"}) {
                if (hasWord(t, w)) return true;
            }
            // Some listings describe a complete build without saying "PC" in the title.
            // Multiple independently recognized core parts make them worth category-checking.
            Specs parsed = SpecParser.parse(title == null ? "" : title, description == null ? "" : description);
            int signals = 0;
            if (!parsed.cpu.isBlank()) signals++;
            if (!parsed.gpu.isBlank()) signals++;
            if (!parsed.ram.isBlank()) signals++;
            if (!parsed.mobo.isBlank()) signals++;
            if (!parsed.ssd.isBlank() || !parsed.hdd.isBlank()) signals++;
            return signals >= 2;
        }

        private boolean matchesSystemComponent(String kind, String keyword, MarketListing hit) {
            String detail = hit.fullDescription == null ? "" : hit.fullDescription;
            String spec = "";
            if (kind.equals("cpu")) spec = hit.specs.cpu;
            else if (kind.equals("gpu")) spec = hit.specs.gpu;
            else if (kind.equals("ram")) spec = hit.specs.ram;
            return SoldTools.matchesKeyword(hit.listing.title + " " + detail, keyword)
                    || (!spec.isBlank() && SoldTools.matchesKeyword(spec, keyword));
        }

        private boolean isStandalonePart(String kind, String title, String description) {
            String t = normalize(title);
            if (kind.equals("cpu") || kind.equals("gpu")) {
                for (String word : SYSTEM_WORDS) if (hasWord(t, word)) return false;
            }
            Specs s = SpecParser.parse(title, description);
            switch (kind) {
                case "cpu":
                    return s.gpu.isBlank() && s.ram.isBlank() && s.ssd.isBlank()
                            && s.hdd.isBlank() && s.mobo.isBlank();
                case "gpu":
                    return s.cpu.isBlank() && s.ram.isBlank() && s.ssd.isBlank()
                            && s.hdd.isBlank() && s.mobo.isBlank();
                case "ram":
                    return s.cpu.isBlank() && s.gpu.isBlank() && s.ssd.isBlank()
                            && s.hdd.isBlank() && s.mobo.isBlank();
                default:
                    return false;
            }
        }

        private boolean hasWord(String text, String word) {
            return Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(word)
                    + "(?![\\p{L}\\p{N}])").matcher(text).find();
        }

        private List<Listing> searchKeyword(String keyword, SoldParams p) throws Exception {
            String key = normalize(keyword) + "|" + p.pages;
            CompletableFuture<List<Listing>> created = new CompletableFuture<>();
            CompletableFuture<List<Listing>> future = searchCache.putIfAbsent(key, created);
            if (future == null) {
                future = created;
                try {
                    Map<String, Listing> all = new LinkedHashMap<>();
                    String base = SEARCH_URL + URLEncoder.encode(keyword, StandardCharsets.UTF_8);
                    String pageTemplate = null;
                    for (int page = 1; page <= p.pages; page++) {
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                        String url = page == 1 ? base : (pageTemplate == null ? base + "&page=" + page : buildPageUrl(pageTemplate, page));
                        Document doc = connectSold(url);
                        int before = all.size();
                        for (Listing l : parseListings(doc, "Αναζήτηση")) {
                            all.putIfAbsent(removeQueryAndFragment(l.url), l);
                        }
                        int highest = pageTemplate == null ? 1 : pageNumberOf(pageTemplate);
                        for (String pageLink : findPaginationUrls(doc, base)) {
                            int n = pageNumberOf(pageLink);
                            if (n > highest) { highest = n; pageTemplate = removeFragmentOnly(pageLink); }
                        }
                        if (all.size() == before) break;
                    }
                    created.complete(Collections.unmodifiableList(new ArrayList<>(all.values())));
                } catch (Exception ex) {
                    created.completeExceptionally(ex);
                    searchCache.remove(key, created);
                }
            }
            try {
                return future.get();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw ie;
            } catch (ExecutionException ex) {
                Throwable cause = ex.getCause();
                if (cause instanceof Exception) throw (Exception) cause;
                throw new IOException("Αποτυχία αναζήτησης: " + keyword, cause);
            }
        }

        private MarketListing marketDetails(Listing listing) throws Exception {
            if (facebook) return facebookMarketDetails(listing);
            if (vinted) return vintedMarketDetails(listing);
            if (vendora) return vendoraMarketDetails(listing);
            CompletableFuture<MarketListing> created = new CompletableFuture<>();
            CompletableFuture<MarketListing> future = marketDetailCache.putIfAbsent(listing.url, created);
            if (future == null) {
                future = created;
                try {
                    Document doc = connectSold(listing.url);
                    String body = extractBodyText(doc).trim();
                    if (body.isBlank()) body = listing.description == null ? "" : listing.description;
                    listing.fullDescription = body;
                    listing.description = body;
                    listing.specs = SpecParser.parse(listing.title, body);
                    listing.detailsLoaded = true;

                    MarketListing hit = new MarketListing(listing);
                    Double pagePrice = hit.price == null ? extractDocumentPrice(doc) : null;
                    if (pagePrice != null && pagePrice > 0) hit.price = pagePrice;
                    hit.watchers = extractFollowerCount(doc);
                    hit.fullDescription = body;
                    hit.specs = listing.specs;
                    hit.fullSystemsCategory = isFullSystemsCategory(doc, listing.category);
                    hit.detailsLoaded = true;
                    created.complete(hit);
                } catch (Exception ex) {
                    created.completeExceptionally(ex);
                    marketDetailCache.remove(listing.url, created);
                }
            }
            try {
                return future.get();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw ie;
            } catch (ExecutionException ex) {
                Throwable cause = ex.getCause();
                if (cause instanceof Exception) throw (Exception) cause;
                throw new IOException("Αποτυχία ανάγνωσης αγγελίας: " + listing.url, cause);
            }
        }

        private boolean isFullSystemsCategory(Document doc, String fallbackCategory) {
            String expectedPath = pathWithoutTrailingSlash(URI.create(FULL_SYSTEMS_CATEGORY_URL).getPath());
            if (normalize(fallbackCategory).contains("plhri systhmata")) return true;
            for (Element a : doc.select("a[href*='/classifieds/category/']")) {
                String href = a.absUrl("href");
                String path = "";
                try { path = pathWithoutTrailingSlash(URI.create(href).getPath()); }
                catch (Exception ignored) { }
                String txt = normalize(a.text());
                if (path.equals(expectedPath) || path.endsWith("/8-pliri-systimata")
                        || txt.equals("plhri systhmata")) return true;
            }
            return false;
        }

        private int extractFollowerCount(Document doc) {
            String text = normalize(doc.body() == null ? "" : doc.body().text());
            Matcher m = Pattern.compile("(?<!\\d)(\\d{1,5})\\s*(?:παρακολουθουν|watching)\\b").matcher(text);
            if (m.find()) {
                try { return Integer.parseInt(m.group(1)); }
                catch (NumberFormatException ignored) { }
            }
            // Fallback for any page variant that places the count after the label.
            m = Pattern.compile("(?:παρακολουθουν|watching)\\s*:?\\s*(\\d{1,5})(?!\\d)").matcher(text);
            if (!m.find()) return -1;
            try { return Integer.parseInt(m.group(1)); }
            catch (NumberFormatException ex) { return -1; }
        }

        private void reserveSoldRequestSlot() throws InterruptedException {
            long waitNanos;
            synchronized (soldPacingLock) {
                long now = System.nanoTime();
                long slot = Math.max(now, nextSoldRequestNanos);
                nextSoldRequestNanos = slot + TimeUnit.MILLISECONDS.toNanos(soldRequestSpacingMs);
                waitNanos = slot - now;
            }
            if (waitNanos > 0) TimeUnit.NANOSECONDS.sleep(waitNanos);
        }

        private void pushSoldBackoff(long delayMs) {
            synchronized (soldPacingLock) {
                long retryAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMs);
                if (retryAt > nextSoldRequestNanos) nextSoldRequestNanos = retryAt;
            }
        }

        private long soldRetryDelay(org.jsoup.Connection.Response response, int attempt) {
            String retryAfter = response.header("Retry-After");
            if (retryAfter != null) {
                try { return Math.max(1000L, Math.min(30000L, Long.parseLong(retryAfter.trim()) * 1000L)); }
                catch (NumberFormatException ignored) { }
            }
            return Math.min(20000L, 1000L * (1L << Math.min(attempt, 4)));
        }

        private Document connectSold(String url) throws Exception {
            Exception last = null;
            for (int attempt = 0; attempt <= SOLD_MAX_RETRIES; attempt++) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                reserveSoldRequestSlot();
                try {
                    if (vinted) ensureVintedSession();
                    org.jsoup.Connection.Response response = Jsoup.connect(url)
                            .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36")
                            .header("Accept-Language", "el-GR,el;q=0.9,en;q=0.8")
                            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                            .referrer((vinted ? VINTED_BASE : vendora ? VENDORA_BASE : BASE_URL) + "/")
                            .cookies(vinted ? vintedCookies : Collections.<String, String>emptyMap())
                            .timeout(SOLD_TIMEOUT_MS)
                            .maxBodySize(0)
                            .ignoreHttpErrors(true)
                            .execute();
                    if (vinted) vintedCookies.putAll(response.cookies());
                    int status = response.statusCode();
                    if (status == 429 || status == 503) {
                        long delay = soldRetryDelay(response, attempt);
                        last = new IOException("HTTP " + status + " (rate limited): " + url);
                        synchronized (soldPacingLock) {
                            soldRequestSpacingMs = Math.min(1500L, Math.max(SOLD_REQUEST_SPACING_MS, soldRequestSpacingMs + 180L));
                        }
                        soldSuccessStreak.set(0);
                        pushSoldBackoff(delay);
                        if (attempt < SOLD_MAX_RETRIES) Thread.sleep(delay);
                        continue;
                    }
                    if (status >= 400) throw new IOException("HTTP " + status + " fetching " + url);
                    Document parsed = response.parse();
                    if (soldSuccessStreak.incrementAndGet() >= 30) {
                        synchronized (soldPacingLock) {
                            if (soldRequestSpacingMs > SOLD_REQUEST_SPACING_MS)
                                soldRequestSpacingMs = Math.max(SOLD_REQUEST_SPACING_MS, soldRequestSpacingMs - 20L);
                        }
                        soldSuccessStreak.set(0);
                    }
                    return parsed;
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ie;
                } catch (Exception ex) {
                    last = ex;
                    if (attempt >= SOLD_MAX_RETRIES) break;
                    String msg = String.valueOf(ex.getMessage());
                    if (msg.matches("(?is).*HTTP (400|401|403|404|410) .*")) throw ex;
                    Thread.sleep(400L * (attempt + 1));
                }
            }
            throw last == null ? new IOException("Αποτυχία λήψης: " + url) : last;
        }

        private Double extractDocumentPrice(Document doc) {
            String[] selectors = {
                    "meta[property='product:price:amount']", "meta[property='og:price:amount']",
                    "meta[itemprop=price]", "[itemprop=price]", "[data-price]", ".cFilePrice",
                    ".classifieds-data-item-price", ".ipsPrice", "[class*=price]"
            };
            for (String selector : selectors) {
                for (Element e : doc.select(selector)) {
                    if (e.closest("nav, header, footer, .ipsWidget, .ipsDataList, .insAdvertsList, .insSrCard") != null) continue;
                    String raw = e.hasAttr("content") ? e.attr("content")
                            : e.hasAttr("data-price") ? e.attr("data-price")
                            : e.hasAttr("value") ? e.attr("value") : e.text();
                    Double value = parsePriceFromText(raw);
                    if (value != null && value > 0 && value < 10000000) return value;
                }
            }
            Pattern jsonPrice = Pattern.compile("(?is)\\\"price\\\"\\s*:\\s*\\\"?([0-9][0-9.,]*)");
            for (Element script : doc.select("script[type=application/ld+json]")) {
                Matcher m = jsonPrice.matcher(script.data());
                if (m.find()) {
                    Double value = parsePrice(m.group(1));
                    if (value != null && value > 0 && value < 10000000) return value;
                }
            }
            Matcher money = Pattern.compile("(?i)(?<![0-9])([0-9][0-9.,\\s]{0,14})\\s*(?:€|EUR)")
                    .matcher(doc.body() == null ? "" : doc.body().text());
            if (money.find()) {
                Double value = parsePrice(money.group(1));
                if (value != null && value > 0 && value < 10000000) return value;
            }
            return null;
        }

        private Double parsePriceFromText(String raw) {
            if (raw == null || raw.isBlank()) return null;
            Double direct = parsePrice(raw);
            if (direct != null) return direct;
            Matcher m = Pattern.compile("(?i)(?<![0-9])([0-9][0-9.,\\s]{0,14})\\s*(?:€|EUR)").matcher(raw);
            return m.find() ? parsePrice(m.group(1)) : null;
        }

        // ------------------------------------------------------------
        // VENDORA.GR (4ο tab): ίδια δουλειά με το Insomnia, άλλη πηγή
        // ------------------------------------------------------------

        private List<Listing> searchVendoraKeyword(String keyword, String root, SoldParams p) throws Exception {
            String key = "vendora|" + root + "|" + normalize(keyword) + "|" + p.pages;
            CompletableFuture<List<Listing>> created = new CompletableFuture<>();
            CompletableFuture<List<Listing>> future = searchCache.putIfAbsent(key, created);
            if (future == null) {
                future = created;
                try {
                    Map<String, Listing> all = new LinkedHashMap<>();
                    String q = URLEncoder.encode(keyword, StandardCharsets.UTF_8).replace("+", "%20");
                    String categoryName = root.equals(removeQueryAndFragment(VENDORA_DESKTOPS_URL))
                            ? "Σταθεροί Υπολογιστές" : "Vendora";
                    for (int page = 1; page <= p.pages; page++) {
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                        String url = root + "?q=" + q + "&page=" + page;
                        Document doc = connectSold(url);
                        int before = all.size();
                        for (Listing l : parseVendoraListings(doc, categoryName)) {
                            all.putIfAbsent(removeQueryAndFragment(l.url), l);
                        }
                        if (all.size() == before) break;
                        Element next = doc.selectFirst("[data-next-page-url]");
                        if (next != null && next.attr("data-next-page-url").isBlank()) break;
                    }
                    created.complete(Collections.unmodifiableList(new ArrayList<>(all.values())));
                } catch (Exception ex) {
                    created.completeExceptionally(ex);
                    searchCache.remove(key, created);
                }
            }
            try {
                return future.get();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw ie;
            } catch (ExecutionException ex) {
                Throwable cause = ex.getCause();
                if (cause instanceof Exception) throw (Exception) cause;
                throw new IOException("Αποτυχία αναζήτησης στη Vendora: " + keyword, cause);
            }
        }

        private MarketListing vendoraMarketDetails(Listing listing) throws Exception {
            CompletableFuture<MarketListing> created = new CompletableFuture<>();
            CompletableFuture<MarketListing> future = marketDetailCache.putIfAbsent(listing.url, created);
            if (future == null) {
                future = created;
                try {
                    Document doc = connectSold(listing.url);
                    String body = extractVendoraDescription(doc).trim();
                    if (body.isBlank()) body = listing.description == null ? "" : listing.description;
                    listing.fullDescription = body;
                    listing.description = body;
                    listing.specs = SpecParser.parse(listing.title, body);
                    String condition = vendoraCondition(doc);
                    if (!condition.isEmpty()) listing.condition = condition;
                    Element sellerElement = doc.selectFirst(".about-seller-user-details h2");
                    if (sellerElement != null && !sellerElement.text().isBlank()) {
                        listing.seller = sellerElement.text().trim();
                    }
                    listing.detailsLoaded = true;

                    MarketListing hit = new MarketListing(listing);
                    if (hit.price == null) {
                        Double pagePrice = extractVendoraPagePrice(doc);
                        if (pagePrice != null && pagePrice > 0) hit.price = pagePrice;
                    }
                    // Η Vendora δεν δείχνει αριθμό «παρακολουθούν»: μένει κενό (—).
                    hit.watchers = -1;
                    hit.fullDescription = body;
                    hit.specs = listing.specs;
                    hit.detailsLoaded = true;
                    created.complete(hit);
                } catch (Exception ex) {
                    created.completeExceptionally(ex);
                    marketDetailCache.remove(listing.url, created);
                }
            }
            try {
                return future.get();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw ie;
            } catch (ExecutionException ex) {
                Throwable cause = ex.getCause();
                if (cause instanceof Exception) throw (Exception) cause;
                throw new IOException("Αποτυχία ανάγνωσης αγγελίας Vendora: " + listing.url, cause);
            }
        }

        // ------------------------------------------------------------
        // VINTED.GR (6ο tab): ίδια δουλειά με το Insomnia, άλλη πηγή
        // ------------------------------------------------------------

        /*
         * «typed» είναι το URL που γράφτηκε στο πεδίο κατηγορίας (προεπιλογή: https://www.vinted.gr/catalog).
         * Αν περιέχει φίλτρα (catalog[], status_ids[], brand_ids[], price_from ...) εφαρμόζονται σε κάθε όρο.
         */
        private List<Listing> searchVintedKeyword(String keyword, String typed, SoldParams p) throws Exception {
            String key = "vinted|" + typed + "|" + normalize(keyword) + "|" + p.pages;
            CompletableFuture<List<Listing>> created = new CompletableFuture<>();
            CompletableFuture<List<Listing>> future = searchCache.putIfAbsent(key, created);
            if (future == null) {
                future = created;
                try {
                    Map<String, Listing> all = new LinkedHashMap<>();
                    String q = URLEncoder.encode(keyword, StandardCharsets.UTF_8).replace("+", "%20");
                    StringBuilder filters = new StringBuilder();
                    for (String pair : VintedTools.filterPairs(typed)) {
                        filters.append('&').append(VintedTools.encodePair(pair));
                    }
                    for (int page = 1; page <= p.pages; page++) {
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                        String url = VINTED_CATALOG_URL + "?search_text=" + q + filters + "&page=" + page;
                        Document doc = connectSold(url);
                        VintedPage vp = parseVintedPage(doc, "Vinted");
                        int before = all.size();
                        for (Listing l : vp.listings) {
                            all.putIfAbsent(removeQueryAndFragment(l.url), l);
                        }
                        if (all.size() == before) break;
                        if (vp.totalPages > 0 && page >= vp.totalPages) break;
                    }
                    created.complete(Collections.unmodifiableList(new ArrayList<>(all.values())));
                } catch (Exception ex) {
                    created.completeExceptionally(ex);
                    searchCache.remove(key, created);
                }
            }
            try {
                return future.get();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw ie;
            } catch (ExecutionException ex) {
                Throwable cause = ex.getCause();
                if (cause instanceof Exception) throw (Exception) cause;
                throw new IOException("Αποτυχία αναζήτησης στο Vinted: " + keyword, cause);
            }
        }

        private MarketListing vintedMarketDetails(Listing listing) throws Exception {
            CompletableFuture<MarketListing> created = new CompletableFuture<>();
            CompletableFuture<MarketListing> future = marketDetailCache.putIfAbsent(listing.url, created);
            if (future == null) {
                future = created;
                try {
                    Document doc = connectSold(listing.url);
                    Double pagePrice = applyVintedDetails(listing, doc);

                    MarketListing hit = new MarketListing(listing);
                    if (hit.price == null && pagePrice != null && pagePrice > 0) hit.price = pagePrice;
                    // «Παρακολουθούν» = πόσοι έχουν την αγγελία στα αγαπημένα τους.
                    hit.watchers = listing.favourites;
                    hit.fullDescription = listing.fullDescription;
                    hit.specs = listing.specs;
                    hit.detailsLoaded = true;
                    created.complete(hit);
                } catch (Exception ex) {
                    created.completeExceptionally(ex);
                    marketDetailCache.remove(listing.url, created);
                }
            }
            try {
                return future.get();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw ie;
            } catch (ExecutionException ex) {
                Throwable cause = ex.getCause();
                if (cause instanceof Exception) throw (Exception) cause;
                throw new IOException("Αποτυχία ανάγνωσης αγγελίας Vinted: " + listing.url, cause);
            }
        }

        // ------------------------------------------------------------
        // FACEBOOK MARKETPLACE (8ο tab): ίδια δουλειά με το Insomnia, άλλη πηγή
        // ------------------------------------------------------------

        /*
         * «typed» είναι το URL / ID πόλης του πεδίου κατηγορίας (προεπιλογή: η πόλη του Marketplace).
         * Αν περιέχει φίλτρα (minPrice, maxPrice, itemCondition, daysSinceListed ...) εφαρμόζονται σε κάθε όρο.
         * Το Facebook δίνει ~24 αγγελίες ανά σελίδα αναζήτησης, οπότε διαβάζεται μία σελίδα ανά εξάρτημα.
         */
        private List<Listing> searchFacebookKeyword(String keyword, String typed, SoldParams p) throws Exception {
            String key = "facebook|" + typed + "|" + normalize(keyword);
            CompletableFuture<List<Listing>> created = new CompletableFuture<>();
            CompletableFuture<List<Listing>> future = searchCache.putIfAbsent(key, created);
            if (future == null) {
                future = created;
                try {
                    String url = FacebookTools.searchUrl(typed, keyword, FacebookTools.filterParams(typed));
                    String html = facebookFetch(url);
                    Map<String, Listing> all = new LinkedHashMap<>();
                    for (Listing l : facebookListings(html, "Facebook Marketplace")) {
                        all.putIfAbsent(removeQueryAndFragment(l.url), l);
                    }
                    if (all.isEmpty()) {
                        throw new IOException(FACEBOOK_BLOCKED_HINT);
                    }
                    created.complete(Collections.unmodifiableList(new ArrayList<>(all.values())));
                } catch (Exception ex) {
                    created.completeExceptionally(ex);
                    searchCache.remove(key, created);
                }
            }
            try {
                return future.get();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw ie;
            } catch (ExecutionException ex) {
                Throwable cause = ex.getCause();
                if (cause instanceof Exception) throw (Exception) cause;
                throw new IOException("Αποτυχία αναζήτησης στο Facebook: " + keyword, cause);
            }
        }

        private MarketListing facebookMarketDetails(Listing listing) throws Exception {
            CompletableFuture<MarketListing> created = new CompletableFuture<>();
            CompletableFuture<MarketListing> future = marketDetailCache.putIfAbsent(listing.url, created);
            if (future == null) {
                future = created;
                try {
                    Double pagePrice = fetchFacebookDetails(listing);

                    MarketListing hit = new MarketListing(listing);
                    if (hit.price == null && pagePrice != null && pagePrice > 0) hit.price = pagePrice;
                    hit.fullDescription = listing.fullDescription;
                    hit.specs = listing.specs;
                    hit.detailsLoaded = true;
                    created.complete(hit);
                } catch (Exception ex) {
                    created.completeExceptionally(ex);
                    marketDetailCache.remove(listing.url, created);
                }
            }
            try {
                return future.get();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw ie;
            } catch (ExecutionException ex) {
                Throwable cause = ex.getCause();
                if (cause instanceof Exception) throw (Exception) cause;
                throw new IOException("Αποτυχία ανάγνωσης αγγελίας Facebook: " + listing.url, cause);
            }
        }

        // Τιμή μόνο από δομημένα δεδομένα (meta / JSON-LD), ώστε να μη διαβαστεί
        // κατά λάθος η τιμή κάποιας «σχετικής» αγγελίας της σελίδας.
        private Double extractVendoraPagePrice(Document doc) {
            for (String selector : new String[]{"meta[property=product:price:amount]",
                    "meta[property=og:price:amount]", "meta[itemprop=price]"}) {
                Element e = doc.selectFirst(selector);
                if (e == null) continue;
                Double value = parsePriceFromText(e.attr("content"));
                if (value != null && value > 0 && value < 10000000) return value;
            }
            Pattern jsonPrice = Pattern.compile("(?is)\"price\"\\s*:\\s*\"?([0-9][0-9.,]*)");
            for (Element script : doc.select("script[type=application/ld+json]")) {
                Matcher m = jsonPrice.matcher(script.data());
                if (m.find()) {
                    Double value = parsePrice(m.group(1));
                    if (value != null && value > 0 && value < 10000000) return value;
                }
            }
            return null;
        }

        // ------------------------------------------------------------
        // Tab «Προσφορές»: μόνο τα εξαρτήματα που βρήκαν νέο Min στην τρέχουσα εκτέλεση
        // ------------------------------------------------------------

        private void openOffersTab(List<OfferRow> offers) {
            JTabbedPane tabs = (JTabbedPane) SwingUtilities.getAncestorOfClass(JTabbedPane.class, this);
            if (tabs == null || offers.isEmpty()) return;

            Color theme = theme();
            String site = facebook ? "Facebook" : vinted ? "Vinted" : vendora ? "Vendora" : "Insomnia";
            String time = LocalDateTime.now(ZoneId.of("Europe/Athens")).toLocalTime().toString().substring(0, 5);
            String title = "Προσφορές " + site + " · " + time + " (" + offers.size() + ")";

            JPanel panel = buildOffersPanel(offers, theme, site);
            tabs.addTab(title, panel);
            int index = tabs.indexOfComponent(panel);

            JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            header.setOpaque(true);
            header.setBackground(theme);
            header.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 8));
            JLabel text = new JLabel(title);
            text.setForeground(INK);
            text.setFont(text.getFont().deriveFont(Font.BOLD, 12f));
            JLabel close = new JLabel("✕");
            close.setForeground(INK);
            close.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            close.setToolTipText("Κλείσιμο");
            close.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) { tabs.remove(panel); }
            });
            header.add(text);
            header.add(close);
            tabs.setTabComponentAt(index, header);
            tabs.setSelectedComponent(panel);
        }

        private JPanel buildOffersPanel(List<OfferRow> source, Color theme, String site) {
            final List<OfferRow> rows = new ArrayList<>(source);
            rows.sort((x, y) -> Double.compare(y.dropPercent(), x.dropPercent()));

            final String[] cols = {"Κατηγορία", "Εξάρτημα", "Όροι αναζήτησης", "Προηγούμενο Min (€)",
                    "Νέο Min (€)", "Μείωση (€)", "Μείωση %", "Παρακολουθούν", "Τίτλος αγγελίας", "Πωλητής", "Σύνδεσμος"};
            final int linkCol = cols.length - 1;

            AbstractTableModel tm = new AbstractTableModel() {
                @Override public int getRowCount() { return rows.size(); }
                @Override public int getColumnCount() { return cols.length; }
                @Override public String getColumnName(int c) { return cols[c]; }
                @Override public Class<?> getColumnClass(int c) {
                    return (c >= 3 && c <= 5) ? Double.class : String.class;
                }
                @Override public Object getValueAt(int row, int c) {
                    OfferRow o = rows.get(row);
                    switch (c) {
                        case 0: return o.category;
                        case 1: return o.label;
                        case 2: return o.keyword;
                        case 3: return o.previous;
                        case 4: return o.current;
                        case 5: return o.previous - o.current;
                        case 6: return String.format(Locale.ROOT, "-%.1f%%", o.dropPercent());
                        case 7: return o.watchers < 0 ? "—" : String.valueOf(o.watchers);
                        case 8: return o.title;
                        case 9: return o.seller;
                        default: return o.url;
                    }
                }
            };

            JTable offersTable = new JTable(tm);
            offersTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
            offersTable.setRowHeight(27);
            offersTable.setFillsViewportHeight(true);
            offersTable.setShowVerticalLines(false);
            offersTable.setGridColor(new Color(226, 232, 240));
            offersTable.setSelectionBackground(theme);
            offersTable.setSelectionForeground(INK);
            offersTable.getTableHeader().setReorderingAllowed(false);
            styleHeader(offersTable, theme, INK);
            int[] widths = {75, 230, 190, 120, 100, 90, 85, 105, 330, 120, 150};
            for (int i = 0; i < widths.length; i++) offersTable.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);

            offersTable.setDefaultRenderer(Double.class, new javax.swing.table.DefaultTableCellRenderer() {
                @Override public Component getTableCellRendererComponent(JTable t, Object value, boolean sel,
                                                                         boolean foc, int row, int column) {
                    Component c = super.getTableCellRendererComponent(t, value, sel, foc, row, column);
                    setHorizontalAlignment(SwingConstants.RIGHT);
                    setText(value == null ? "" : String.format(Locale.ROOT, "%.2f €", ((Number) value).doubleValue()));
                    if (!sel) c.setBackground(row % 2 == 0 ? Color.WHITE : new Color(248, 250, 252));
                    if (column == 4) c.setFont(c.getFont().deriveFont(Font.BOLD));
                    return c;
                }
            });
            offersTable.setDefaultRenderer(Object.class, new javax.swing.table.DefaultTableCellRenderer() {
                @Override public Component getTableCellRendererComponent(JTable t, Object value, boolean sel,
                                                                         boolean foc, int row, int column) {
                    Component c = super.getTableCellRendererComponent(t, value, sel, foc, row, column);
                    setHorizontalAlignment(SwingConstants.LEFT);
                    setToolTipText(null);
                    if (!sel) {
                        c.setBackground(row % 2 == 0 ? Color.WHITE : new Color(248, 250, 252));
                        c.setForeground(INK);
                    }
                    if (column == linkCol && value != null && !value.toString().isBlank()) {
                        setText("<html><u>↗ Άνοιγμα προσφοράς</u></html>");
                        if (!sel) c.setForeground(new Color(29, 78, 216));
                        setToolTipText(value.toString());
                    }
                    return c;
                }
            });
            offersTable.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    int viewRow = offersTable.rowAtPoint(e.getPoint());
                    int viewCol = offersTable.columnAtPoint(e.getPoint());
                    if (viewRow < 0 || viewCol < 0) return;
                    if (viewCol == linkCol || e.getClickCount() == 2) {
                        Object url = offersTable.getValueAt(viewRow, linkCol);
                        if (url != null && !url.toString().isBlank()) openUrl(url.toString());
                    }
                }
            });
            offersTable.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
                @Override public void mouseMoved(MouseEvent e) {
                    int viewRow = offersTable.rowAtPoint(e.getPoint());
                    int viewCol = offersTable.columnAtPoint(e.getPoint());
                    offersTable.setCursor(Cursor.getPredefinedCursor(
                            viewRow >= 0 && viewCol == linkCol ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
                }
            });

            JLabel note = new JLabel("Μόνο εξαρτήματα που βρήκαν νέο Min χαμηλότερο από το προηγούμενο (Excel / ιστορικό). "
                    + "Κλικ στο «↗ Άνοιγμα προσφοράς» για την αγγελία.");
            note.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
            JPanel north = new JPanel(new BorderLayout());
            north.add(banner("ΠΡΟΣΦΟΡΕΣ  ·  νέα χαμηλά " + site + "  ·  " + rows.size(), theme), BorderLayout.NORTH);
            north.add(note, BorderLayout.CENTER);

            JPanel panel = new JPanel(new BorderLayout(0, 4));
            panel.add(north, BorderLayout.NORTH);
            panel.add(new JScrollPane(offersTable), BorderLayout.CENTER);
            return panel;
        }

        private void saveMonitorWorkbook() throws IOException {
            if (monitorFile == null) return;
            List<Object[]> summaryRows = new ArrayList<>();
            LocalDateTime checkedAt = LocalDateTime.now(ZoneId.of("Europe/Athens"));
            String timestamp = checkedAt.toString().replace('T', ' ');

            for (SoldRow r : model.all()) {
                String historyKey = r.historyKey();
                Double previous = previousMinima.get(historyKey);
                if (previous == null) previous = parseOptionalPrice(r.excelMin);
                Double allTimeMin = previous;
                if (r.min != null && (previous == null || r.min < previous - 0.009)) {
                    MarketListing hit = r.cheapest;
                    Specs sp = hit == null ? new Specs() : hit.specs;
                    double drop = previous == null ? 0 : previous - r.min;
                    String entryType = previous == null ? "Αρχική καταγραφή" : "ΝΕΟ ΧΑΜΗΛΟ";
                    newLowHistory.add(new Object[]{timestamp, r.kind.toUpperCase(Locale.ROOT), r.label,
                            r.keyword, previous, r.min, previous == null ? null : drop,
                            r.minWatchers < 0 ? null : r.minWatchers,
                            hit == null ? null : hit.listing.title,
                            sp.cpu, sp.ram, sp.gpu, sp.ssd, sp.hdd, sp.mobo, sp.psu, sp.extras,
                            hit == null ? null : hit.fullDescription,
                            hit == null ? null : hit.listing.url, entryType});
                    allTimeMin = r.min;
                    if (previous != null) {
                        runOffers.add(new OfferRow(r.kind.toUpperCase(Locale.ROOT), r.label, r.keyword,
                                previous, r.min, r.minWatchers,
                                hit == null ? "" : hit.listing.title,
                                hit == null ? "" : hit.listing.seller,
                                hit == null ? "" : hit.listing.url));
                    }
                }
                // Keep the historical low, not merely the last observed price. A temporary
                // price rise or a failed scan must never erase the previous record.
                if (allTimeMin != null) previousMinima.put(historyKey, allTimeMin);

                summaryRows.add(new Object[]{historyKey, r.sheet, r.excelRow, r.kind.toUpperCase(Locale.ROOT),
                        r.label, r.keyword, numOrText(r.excelMin), numOrText(r.excelMax),
                        r.min, r.max, r.minWatchers < 0 ? null : r.minWatchers,
                        r.maxWatchers < 0 ? null : r.maxWatchers, r.count, r.status,
                        r.cheapest == null ? null : r.cheapest.listing.title,
                        r.mostExpensive == null ? null : r.mostExpensive.listing.title,
                        r.cheapest == null ? null : r.cheapest.listing.url,
                        r.mostExpensive == null ? null : r.mostExpensive.listing.url,
                        allTimeMin,
                        pcAt(r, 0) == null ? null : pcAt(r, 0).listing.title,
                        pcAt(r, 0) == null ? null : pcAt(r, 0).price,
                        pcAt(r, 0) == null ? null : pcAt(r, 0).listing.url,
                        pcAt(r, 1) == null ? null : pcAt(r, 1).price,
                        pcAt(r, 1) == null ? null : pcAt(r, 1).listing.url,
                        pcAt(r, 2) == null ? null : pcAt(r, 2).price,
                        pcAt(r, 2) == null ? null : pcAt(r, 2).listing.url});
            }

            summaryRows.sort((a, b) -> {
                String ka = String.valueOf(a[3]) + String.valueOf(a[4]);
                String kb = String.valueOf(b[3]) + String.valueOf(b[4]);
                return ka.compareToIgnoreCase(kb);
            });

            List<Object[]> systemRows = new ArrayList<>();
            for (SoldRow r : model.all()) {
                for (int i = 0; i < r.cheapestPcs.size(); i++) {
                    MarketListing hit = r.cheapestPcs.get(i);
                    Specs sp = hit.specs;
                    systemRows.add(new Object[]{r.kind.toUpperCase(Locale.ROOT), r.label, r.keyword, i + 1,
                            hit.price, hit.watchers < 0 ? null : hit.watchers,
                            hit.listing.title, sp.cpu, sp.ram, sp.gpu, sp.ssd, sp.hdd, sp.mobo, sp.psu,
                            sp.extras, hit.fullDescription, hit.listing.url});
                }
            }
            systemRows.sort(Comparator
                    .comparing((Object[] a) -> String.valueOf(a[0]) + "|" + String.valueOf(a[1]), String.CASE_INSENSITIVE_ORDER)
                    .thenComparingInt(a -> ((Number) a[3]).intValue()));
            newLowHistory.sort(Comparator.comparingDouble(a -> a[5] instanceof Number ? ((Number) a[5]).doubleValue() : Double.MAX_VALUE));

            String[] summaryHeaders = {"Key", "Source sheet", "Excel row", "Category", "Part", "Search terms",
                    "Excel Min (€)", "Excel Max (€)", "Min (€)", "Max (€)", "Παρακολουθούν Min", "Παρακολουθούν Max",
                    "Matching ads", "Status", "Cheapest listing", "Most expensive listing", "Min URL", "Max URL", "All-time Min (€)",
                    "Cheapest PC listing", "Cheapest PC (€)", "Cheapest PC URL",
                    "PC 2 (€)", "PC 2 URL", "PC 3 (€)", "PC 3 URL"};
            String[] systemHeaders = {"Category", "Part", "Search terms", "PC rank", "PC price (€)", "Παρακολουθούν",
                    "PC listing", "CPU", "RAM", "GPU", "SSD", "HDD", "Motherboard", "PSU", "Other specs",
                    "Full description", "URL"};

            List<XlsxSheet> sheets = new ArrayList<>();
            sheets.add(new XlsxSheet("Τιμές αγοράς", summaryHeaders, summaryRows));
            sheets.add(new XlsxSheet("Πλήρη Συστήματα", systemHeaders, systemRows));
            sheets.add(new XlsxSheet("Νέες χαμηλές τιμές", historyHeaders, new ArrayList<>(newLowHistory)));
            SoldTools.writeXlsxBook(monitorFile, sheets);
        }

        private Object numOrText(String value) {
            if (value == null || value.isBlank()) return null;
            try { return Double.valueOf(value.trim()); }
            catch (NumberFormatException ex) { return value; }
        }
    }

    // =========================================================
    // TAB VENDORA.GR (ξεχωριστό από το Insomnia)
    // =========================================================

    private class VendoraTab extends JPanel {
        private final JTextField keywordBox = new JTextField(18);
        private final JTextField minBox = new JTextField("10", 6);
        private final JTextField maxBox = new JTextField(6);
        private final JTextField excludeBox = new JTextField(24);
        private final JTextField customUrlBox = new JTextField(34);
        private final JComboBox<Category> categoryBox = new JComboBox<>(new Category[]{
                new Category("Γενική αναζήτηση (Υπολογιστές, Tablets, Περιφερειακά)", VENDORA_GENERAL_URL, false),
                new Category("Σταθεροί Υπολογιστές", VENDORA_DESKTOPS_URL, false)
        });
        private final JComboBox<String> conditionBox = new JComboBox<>(new String[]{
                "Όλες οι καταστάσεις", "ΝΕΟ", "ΜΕΤΑΧΕΙΡΙΣΜΕΝΟ"
        });
        private final JComboBox<String> sortBox = new JComboBox<>(new String[]{
                "Τιμή: αύξουσα", "Τιμή: φθίνουσα", "Τίτλος: Α-Ω", "Όπως βρέθηκαν"
        });
        private final JCheckBox deepBox = new JCheckBox("Specs από την αγγελία", true);
        private final JButton searchBtn = new JButton("Αναζήτηση στη Vendora");
        private final JLabel status = new JLabel("Έτοιμο. Γράψε π.χ. rtx 3070 και πάτα Αναζήτηση.");

        private final JLabel detailTitle = new JLabel("Επίλεξε μια αγγελία για να δεις την περιγραφή της.");
        private final JTextArea detailArea = new JTextArea();
        private final JButton loadFullBtn = new JButton("Φόρτωση πλήρους περιγραφής");
        private final JButton openBtn = new JButton("Άνοιγμα στον browser");
        private volatile Listing shown;

        private final ListingTableModel model = new ListingTableModel();
        private final TableRowSorter<ListingTableModel> rowSorter = new TableRowSorter<>(model);
        private final JTable table = new JTable(model) {
            @Override
            public String getToolTipText(MouseEvent event) {
                int row = rowAtPoint(event.getPoint());
                int col = columnAtPoint(event.getPoint());
                if (row < 0 || col < 0) return null;
                Object value = getValueAt(row, col);
                if (value == null || value.toString().length() < 18) return null;
                String text = value.toString().replace("&", "&amp;").replace("<", "&lt;");
                return "<html><body style='width:380px'>" + text + "</body></html>";
            }

            @Override
            public Component prepareRenderer(javax.swing.table.TableCellRenderer renderer,
                                             int row, int column) {
                Component component = super.prepareRenderer(renderer, row, column);
                Listing listing = model.getListing(convertRowIndexToModel(row));
                if (!isRowSelected(row)) {
                    component.setBackground(getBackground());
                    component.setForeground(getForeground());
                }
                applyFlaggedRowBorder(component, this, isFlaggedListing(listing), row, column);
                return component;
            }
        };

        private SwingWorker<List<Listing>, Void> worker;
        private final AtomicInteger uiGeneration = new AtomicInteger();
        private volatile String summary = "";
        private volatile String dumpPath = "";

        VendoraTab() {
            super(new BorderLayout(5, 5));

            JPanel form = new JPanel(new GridBagLayout());
            form.setBorder(BorderFactory.createEmptyBorder(10, 10, 5, 10));
            GridBagConstraints c = new GridBagConstraints();
            c.insets = new Insets(4, 4, 4, 4);
            c.fill = GridBagConstraints.HORIZONTAL;

            field(form, c, 0, "Αναζήτηση:", keywordBox);
            field(form, c, 1, "Κατηγορία:", categoryBox);
            field(form, c, 2, "Ελάχιστη τιμή:", minBox);
            field(form, c, 3, "Μέγιστη τιμή:", maxBox);
            field(form, c, 4, "Κατάσταση:", conditionBox);
            field(form, c, 5, "Ταξινόμηση:", sortBox);

            c.gridx = 6; c.gridy = 1; c.weightx = 0;
            form.add(deepBox, c);
            c.gridx = 7; c.gridy = 1; c.weightx = 0;
            styleButton(searchBtn, VENDORA_COLOR, INK);
            form.add(searchBtn, c);

            JPanel extra = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            extra.add(new JLabel("Αποκλεισμός λέξεων (με κόμμα):"));
            excludeBox.setToolTipText("π.χ. ζητηση, χαλασμενο, ανταλλαγη — ελέγχεται όλο το κείμενο κάθε αγγελίας");
            extra.add(excludeBox);
            extra.add(new JLabel("   Άλλο URL κατηγορίας Vendora (προαιρετικό):"));
            customUrlBox.setToolTipText("π.χ. https://vendora.gr/browse/2vdx1l/statheri-ipologistes.html — αν μείνει κενό, χρησιμοποιείται η κατηγορία από τη λίστα");
            extra.add(customUrlBox);

            JPanel controls = new JPanel(new BorderLayout(0, 3));
            controls.add(form, BorderLayout.CENTER);
            controls.add(extra, BorderLayout.SOUTH);

            status.setBorder(BorderFactory.createEmptyBorder(5, 10, 8, 10));

            JPanel north = new JPanel(new BorderLayout());
            north.add(banner("VENDORA.GR  ·  Αναζήτηση αγγελιών", VENDORA_COLOR), BorderLayout.NORTH);
            north.add(controls, BorderLayout.CENTER);
            north.add(status, BorderLayout.SOUTH);

            configureTable();
            applySort();

            JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                    new JScrollPane(table), buildDetail());
            split.setResizeWeight(0.72);
            split.setContinuousLayout(true);

            add(north, BorderLayout.NORTH);
            add(split, BorderLayout.CENTER);

            keywordBox.addActionListener(e -> search());
            searchBtn.addActionListener(e -> search());
            conditionBox.addActionListener(e -> applyFilters());
            sortBox.addActionListener(e -> applySort());
            minBox.addActionListener(e -> applyFilters());
            maxBox.addActionListener(e -> applyFilters());
            addLiveTextListener(minBox, this::applyFilters);
            addLiveTextListener(maxBox, this::applyFilters);
            excludeBox.addActionListener(e -> applyFilters());
            excludeBox.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                public void insertUpdate(javax.swing.event.DocumentEvent e) { applyFilters(); }
                public void removeUpdate(javax.swing.event.DocumentEvent e) { applyFilters(); }
                public void changedUpdate(javax.swing.event.DocumentEvent e) { applyFilters(); }
            });
        }

        private void field(JPanel panel, GridBagConstraints c, int x, String label, JComponent comp) {
            c.gridx = x; c.gridy = 0; c.weightx = 0;
            panel.add(new JLabel(label), c);
            c.gridx = x; c.gridy = 1;
            c.weightx = comp instanceof JTextField ? 0.3 : 0.5;
            panel.add(comp, c);
        }

        private void setStatusLater(String message) {
            SwingUtilities.invokeLater(() -> status.setText(message));
        }

        private void configureTable() {
            table.setRowSorter(rowSorter);
            table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);

            rowSorter.setComparator(2, (Comparator<Double>) (a, b) ->
                    Double.compare(a == null ? Double.MAX_VALUE : a,
                            b == null ? Double.MAX_VALUE : b));

            int[] widths = {260, 360, 80, 110, 140, 150, 110, 110, 180,
                    200, 220, 120, 130, 80, 150, 350, 0};
            for (int i = 0; i < widths.length; i++) {
                table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
            }
            table.removeColumn(table.getColumnModel().getColumn(16));

            styleHeader(table, VENDORA_COLOR, INK);

            table.getSelectionModel().addListSelectionListener(e -> {
                if (e.getValueIsAdjusting()) return;
                int viewRow = table.getSelectedRow();
                show(viewRow < 0 ? null : model.getListing(table.convertRowIndexToModel(viewRow)));
            });

            table.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() != 2) return;
                    int viewRow = table.getSelectedRow();
                    if (viewRow < 0) return;
                    Listing l = model.getListing(table.convertRowIndexToModel(viewRow));
                    if (l != null) openUrl(l.url);
                }
            });
        }

        private void applySort() {
            List<RowSorter.SortKey> keys = new ArrayList<>();
            keys.add(new RowSorter.SortKey(16, SortOrder.ASCENDING));
            switch (sortBox.getSelectedIndex()) {
                case 0: keys.add(new RowSorter.SortKey(2, SortOrder.ASCENDING)); break;
                case 1: keys.add(new RowSorter.SortKey(2, SortOrder.DESCENDING)); break;
                case 2: keys.add(new RowSorter.SortKey(0, SortOrder.ASCENDING)); break;
                default: break;
            }
            rowSorter.setSortKeys(keys);
        }

        private void applyFilters() {
            final String condition = (String) conditionBox.getSelectedItem();
            final Double min = parseOptionalPrice(minBox.getText());
            final Double max = parseOptionalPrice(maxBox.getText());
            final List<String> excluded = new ArrayList<>();

            for (String term : excludeBox.getText().split("[,;\\n\\r]+")) {
                String n = normalize(term.trim());
                if (!n.isBlank()) excluded.add(n);
            }

            rowSorter.setRowFilter(new RowFilter<ListingTableModel, Integer>() {
                @Override
                public boolean include(Entry<? extends ListingTableModel, ? extends Integer> entry) {
                    Listing l = model.getListing(entry.getIdentifier());
                    if (l == null || isDamagedListing(l)) return false;

                    String text = normalize(l.title + " " + l.description + " "
                            + l.fullDescription + " " + l.seller + " " + l.category + " "
                            + l.url + " " + l.transaction + " " + l.condition + " "
                            + l.specs.cpu + " " + l.specs.ram + " " + l.specs.gpu + " "
                            + l.specs.ssd + " " + l.specs.hdd + " " + l.specs.mobo + " "
                            + l.specs.psu + " " + l.specs.extras);
                    for (String ex : excluded) {
                        if (text.contains(ex)) return false;
                    }

                    if (min != null && (l.price == null || l.price < min)) return false;
                    if (max != null && (l.price == null || l.price > max)) return false;

                    // Η κατάσταση της Vendora φαίνεται μόνο μέσα στην αγγελία.
                    // Όσες δεν έχουν ακόμα κατάσταση ΔΕΝ κρύβονται.
                    if (condition != null && !condition.equals("Όλες οι καταστάσεις")
                            && !l.condition.isEmpty()
                            && !l.condition.equalsIgnoreCase(condition)) {
                        return false;
                    }

                    return true;
                }
            });

            status.setText("Εμφανίζονται " + table.getRowCount()
                    + " από " + model.getRowCount() + " αγγελίες.");
        }

        // ---------------- Αναζήτηση ----------------

        private void search() {
            final String term = keywordBox.getText().trim();

            if (term.isBlank()) {
                JOptionPane.showMessageDialog(this, "Γράψε έναν όρο αναζήτησης.");
                return;
            }

            String custom = customUrlBox.getText().trim();
            Category selected = (Category) categoryBox.getSelectedItem();

            if (!custom.isEmpty()) {
                if (!custom.startsWith(VENDORA_BASE)) {
                    JOptionPane.showMessageDialog(this,
                            "Το URL πρέπει να ξεκινά με " + VENDORA_BASE);
                    return;
                }
                selected = new Category("Vendora", custom, false);
            }

            if (selected == null) {
                selected = new Category("Γενική αναζήτηση", VENDORA_GENERAL_URL, false);
            }

            if (worker != null && !worker.isDone()) {
                worker.cancel(true);
            }

            final Category category = selected;
            final boolean deep = deepBox.isSelected();
            final Double min = parseOptionalPrice(minBox.getText());
            final Double max = parseOptionalPrice(maxBox.getText());
            final int runId = uiGeneration.incrementAndGet();
            final java.util.function.Consumer<List<Listing>> emit = batch ->
                    SwingUtilities.invokeLater(() -> {
                        if (runId != uiGeneration.get()) return;
                        model.addListings(batch);
                        applyFilters();
                    });

            summary = "";
            dumpPath = "";
            model.setListings(Collections.emptyList());
            show(null);
            searchBtn.setEnabled(false);
            status.setText("Vendora: αναζήτηση «" + term + "»...");

            worker = new SwingWorker<List<Listing>, Void>() {
                @Override
                protected List<Listing> doInBackground() throws Exception {
                    return runSearch(category, term, deep, min, max, emit);
                }

                @Override
                protected void done() {
                    try {
                        if (isCancelled()) return;

                        List<Listing> listings = get();
                        model.setListings(listings);
                        applyFilters();

                        status.setText(status.getText()
                                + (summary.isEmpty() ? "" : " | " + summary)
                                + (dumpPath.isEmpty() ? "" : " | Αρχείο: " + dumpPath));

                        if (listings.isEmpty()) {
                            JOptionPane.showMessageDialog(VendoraTab.this,
                                    "Η Vendora δεν επέστρεψε αγγελίες για τον όρο: " + term);
                        }
                    } catch (Exception ex) {
                        if (!isCancelled()) {
                            status.setText("Σφάλμα αναζήτησης.");
                            JOptionPane.showMessageDialog(VendoraTab.this,
                                    "Σφάλμα:\n" + rootMessage(ex),
                                    "Vendora", JOptionPane.ERROR_MESSAGE);
                        }
                    } finally {
                        searchBtn.setEnabled(true);
                    }
                }
            };

            worker.execute();
        }

        /*
         * <κατηγορία>.html?q=<όρος>&page=N. Το φιλτράρισμα με τον όρο το κάνει
         * το site (ψάχνει και στην περιγραφή), γι' αυτό δεν ξαναφιλτράρουμε
         * με τον τίτλο. Οι σελίδες διαβάζονται μέχρι να μη βρεθεί νέα αγγελία.
         */
        private List<Listing> runSearch(
                Category category, String keyword, boolean deep,
                Double min, Double max,
                java.util.function.Consumer<List<Listing>> emit) throws Exception {

            final String root = removeQueryAndFragment(category.url);
            final String q = URLEncoder.encode(keyword, StandardCharsets.UTF_8)
                    .replace("+", "%20");

            List<Listing> result = searchVendoraAll(root, keyword, category.name, VENDORA_MAX_PAGES,
                    msg -> setStatusLater(msg), emit);

            if (deep && !result.isEmpty()) {
                List<Listing> toEnrich = new ArrayList<>();

                for (Listing l : result) {
                    if (inRange(l, min, max)) toEnrich.add(l);
                }

                enrich(toEnrich);
            }

            saveDump(category, result);

            summary = "Αγγελίες: " + result.size();

            return result;
        }

        private void enrich(List<Listing> listings) throws Exception {
            List<Listing> targets = new ArrayList<>();

            for (Listing l : listings) {
                if (!l.detailsLoaded) targets.add(l);
            }

            if (targets.isEmpty()) return;

            final int total = targets.size();
            final AtomicInteger done = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(VENDORA_THREADS);

            try {
                List<Callable<Void>> tasks = new ArrayList<>();

                for (Listing l : targets) {
                    tasks.add(() -> {
                        try {
                            fetchVendoraDetails(l);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        } catch (Exception ignored) {
                        }

                        setStatusLater("Vendora: λήψη λεπτομερειών "
                                + done.incrementAndGet() + " / " + total + "...");
                        return null;
                    });
                }

                pool.invokeAll(tasks);
            } finally {
                pool.shutdownNow();
            }
        }

        private void saveDump(Category category, Collection<Listing> listings) {
            File file = new File("vendora_"
                    + category.name.replaceAll("[^\\p{L}\\p{N}]+", "_") + ".txt").getAbsoluteFile();

            try (PrintWriter out = new PrintWriter(new OutputStreamWriter(
                    new FileOutputStream(file), StandardCharsets.UTF_8))) {

                for (Listing l : listings) {
                    out.println("Τίτλος: " + l.title
                            + " | Τιμή: " + (l.price == null ? "-" : l.price + " €")
                            + " | Κατάσταση: " + l.condition
                            + " | Πωλητής: " + l.seller
                            + " | URL: " + l.url
                            + " | Περιγραφή: " + (l.fullDescription.isBlank()
                            ? l.description : l.fullDescription).replace('\n', ' '));
                }

                dumpPath = file.getPath();
            } catch (IOException ex) {
                dumpPath = "";
            }
        }

        // ---------------- Πάνελ περιγραφής ----------------

        private JPanel buildDetail() {
            detailArea.setEditable(false);
            detailArea.setLineWrap(true);
            detailArea.setWrapStyleWord(true);
            detailArea.setMargin(new Insets(6, 8, 6, 8));

            loadFullBtn.setEnabled(false);
            openBtn.setEnabled(false);

            loadFullBtn.addActionListener(e -> loadFull());
            openBtn.addActionListener(e -> {
                Listing l = shown;
                if (l != null) openUrl(l.url);
            });

            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
            buttons.add(loadFullBtn);
            buttons.add(openBtn);

            JPanel top = new JPanel(new BorderLayout(8, 0));
            top.setBorder(BorderFactory.createEmptyBorder(6, 10, 4, 10));
            top.add(detailTitle, BorderLayout.CENTER);
            top.add(buttons, BorderLayout.EAST);

            JPanel panel = new JPanel(new BorderLayout());
            panel.add(top, BorderLayout.NORTH);
            panel.add(new JScrollPane(detailArea), BorderLayout.CENTER);
            panel.setPreferredSize(new Dimension(100, 220));
            return panel;
        }

        private void show(Listing l) {
            shown = l;

            if (l == null) {
                detailTitle.setText("Επίλεξε μια αγγελία για να δεις την περιγραφή της.");
                detailArea.setText("");
                loadFullBtn.setEnabled(false);
                openBtn.setEnabled(false);
                return;
            }

            detailTitle.setText(l.title + (l.price == null ? "" : "  —  " + l.price + " €"));

            if (!l.fullDescription.isEmpty()) {
                detailArea.setText(l.fullDescription);
            } else if (l.detailsLoaded) {
                detailArea.setText("Δεν βρέθηκε περιγραφή στη σελίδα της αγγελίας.");
            } else {
                detailArea.setText("(Η περιγραφή δεν έχει φορτωθεί — πάτα «Φόρτωση πλήρους περιγραφής»)");
            }

            detailArea.setCaretPosition(0);
            loadFullBtn.setEnabled(!l.detailsLoaded);
            openBtn.setEnabled(true);
        }

        private void loadFull() {
            final Listing l = shown;

            if (l == null) return;

            if (l.detailsLoaded) {
                show(l);
                return;
            }

            loadFullBtn.setEnabled(false);
            detailArea.setText("Φόρτωση περιγραφής...");

            new SwingWorker<Void, Void>() {
                @Override
                protected Void doInBackground() throws Exception {
                    fetchVendoraDetails(l);
                    return null;
                }

                @Override
                protected void done() {
                    try {
                        get();
                        model.refresh(l);
                        if (shown == l) show(l);
                    } catch (Exception ex) {
                        if (shown == l) {
                            detailArea.setText("Σφάλμα φόρτωσης:\n" + rootMessage(ex));
                            loadFullBtn.setEnabled(true);
                        }
                    }
                }
            }.execute();
        }
    }

    // =========================================================
    // VINTED.GR (tabs 5 και 6)
    // =========================================================

    private static final String VINTED_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36";

    // Cookies του Vinted (ανώνυμο session) κοινά για όλα τα αιτήματα των tabs 5 και 6.
    private final Map<String, String> vintedCookies = new ConcurrentHashMap<>();
    // Γλώσσα Vinted: true = αγγλικά (Accept-Language + μετάφραση ελληνικού κειμένου). Ορίζεται από το tab Vinted.
    private volatile boolean vintedEnglish = false;
    private final Object vintedSessionLock = new Object();
    private volatile boolean vintedSessionReady;
    private final Object vintedGate = new Object();
    private long vintedNextRequestNanos = 0L;
    // Χώρα ανά πωλητή (πολλές αγγελίες έχουν τον ίδιο πωλητή -> ένα αίτημα API αρκεί).
    private final Map<String, String> vintedSellerCountry = new ConcurrentHashMap<>();
    private volatile boolean vintedApiBlocked;

    // ---------------- Μετάφραση αγγελιών Vinted ----------------
    // Η διεπαφή του vinted.gr ζητείται στα ελληνικά. Οι περιγραφές, όμως, γράφονται από τους πωλητές
    // και μπορεί να είναι Ρουμανικά/Αγγλικά/άλλη γλώσσα. Στην προεπιλεγμένη λειτουργία μεταφράζονται στα ελληνικά.
    // Το προαιρετικό checkbox Αγγλικά αντιστρέφει τη μετάφραση (ελληνικά -> αγγλικά).
    // Αν η υπηρεσία μετάφρασης αποτύχει, κρατιέται το αρχικό κείμενο.

    private static final Pattern GREEK_LETTERS = Pattern.compile("[\\u0370-\\u03FF\\u1F00-\\u1FFF]");
    private static final Pattern LATIN_LETTERS = Pattern.compile("\\p{IsLatin}");
    private final Map<String, String> translationCache = new ConcurrentHashMap<>();
    private final Object translateGate = new Object();
    private long translateNextNanos = 0L;
    private final AtomicInteger translateFailures = new AtomicInteger();
    private volatile boolean translateBroken;

    private static boolean hasGreek(String s) {
        return s != null && GREEK_LETTERS.matcher(s).find();
    }

    private static boolean hasLatin(String s) {
        return s != null && LATIN_LETTERS.matcher(s).find();
    }

    // Greek is the default display language. In English mode, only Greek source text is translated.
    private boolean needsVintedTranslation(String s) {
        if (s == null || s.isBlank()) return false;
        return vintedEnglish ? hasGreek(s) : hasLatin(s) && !hasGreek(s);
    }

    private void resetTranslator() {
        translateFailures.set(0);
        translateBroken = false;
    }

    private static List<String> splitForTranslation(String text, int limit) {
        List<String> chunks = new ArrayList<>();
        StringBuilder cur = new StringBuilder();

        for (String line : text.split("\\R", -1)) {
            String rest = line;

            while (rest.length() > limit) {
                int cut = rest.lastIndexOf(' ', limit);
                if (cut < limit / 2) cut = limit;
                if (cur.length() > 0) {
                    chunks.add(cur.toString());
                    cur.setLength(0);
                }
                chunks.add(rest.substring(0, cut));
                rest = rest.substring(cut).trim();
            }

            if (cur.length() + rest.length() + 1 > limit && cur.length() > 0) {
                chunks.add(cur.toString());
                cur.setLength(0);
            }

            if (cur.length() > 0) cur.append('\n');
            cur.append(rest);
        }

        if (cur.length() > 0) chunks.add(cur.toString());
        return chunks;
    }

    private String translateChunk(String chunk) throws InterruptedException {
        for (int attempt = 0; attempt < 2; attempt++) {
            long wait;
            synchronized (translateGate) {
                long now = System.nanoTime();
                long at = Math.max(now, translateNextNanos);
                translateNextNanos = at + 250_000_000L;
                wait = at - now;
            }
            if (wait > 0) Thread.sleep(wait / 1_000_000L);

            try {
                org.jsoup.Connection.Response r = Jsoup.connect("https://translate.googleapis.com/translate_a/single")
                        .userAgent(VINTED_UA)
                        .data("client", "gtx")
                        .data("sl", "auto")
                        .data("tl", vintedEnglish ? "en" : "el")
                        .data("dt", "t")
                        .data("q", chunk)
                        .method(org.jsoup.Connection.Method.POST)
                        .timeout(TIMEOUT_MS)
                        .ignoreHttpErrors(true)
                        .ignoreContentType(true)
                        .execute();

                if (r.statusCode() == 200) {
                    Object root = VintedTools.parseAt(r.body(), 0);

                    if (root instanceof List && !((List<?>) root).isEmpty()
                            && ((List<?>) root).get(0) instanceof List) {
                        StringBuilder sb = new StringBuilder();

                        for (Object seg : (List<?>) ((List<?>) root).get(0)) {
                            if (seg instanceof List && !((List<?>) seg).isEmpty()
                                    && ((List<?>) seg).get(0) instanceof String) {
                                sb.append((String) ((List<?>) seg).get(0));
                            }
                        }

                        if (sb.length() > 0) {
                            translateFailures.set(0);
                            return sb.toString();
                        }
                    }
                }
            } catch (Exception ignored) {
            }

            Thread.sleep(600L);
        }

        if (translateFailures.incrementAndGet() >= 5) translateBroken = true;
        return null;
    }

    private String translateToSelectedLanguage(String text) {
        if (text == null || text.isBlank() || translateBroken) return text;

        String target = vintedEnglish ? "en" : "el";
        String cacheKey = target + ":" + text;
        String cached = translationCache.get(cacheKey);
        if (cached != null) return cached;

        try {
            StringBuilder out = new StringBuilder();

            for (String chunk : splitForTranslation(text, 1200)) {
                String part = needsVintedTranslation(chunk) ? translateChunk(chunk) : chunk;
                if (part == null) return text;
                if (out.length() > 0) out.append('\n');
                out.append(part);
            }

            String result = out.toString().trim();
            if (!result.isEmpty()) translationCache.put(cacheKey, result);
            return result;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return text;
        }
    }

    private static String vintedConditionEn(String condition) {
        String c = condition == null ? "" : condition.trim();

        switch (c) {
            case "Νέο με ετικέτες": return "New with tags";
            case "Νέο χωρίς ετικέτες": return "New without tags";
            case "Πολύ καλό": return "Very good";
            case "Καλό": return "Good";
            case "Ικανοποιητικό": return "Satisfactory";
            default: return c;
        }
    }

    // Μεταφράζει τους τίτλους μιας σελίδας αποτελεσμάτων ΠΡΙΝ μπουν στον πίνακα.
    // Οι τίτλοι στέλνονται σε ομάδες (ένας ανά γραμμή) ώστε η σελίδα να θέλει λίγα αιτήματα μετάφρασης·
    // αν η μετάφραση γυρίσει με διαφορετικό αριθμό γραμμών, ο τίτλος μεταφράζεται ξεχωριστά.
    private void vintedTranslateTitles(List<Listing> listings) {
        if (listings == null || listings.isEmpty() || translateBroken) return;

        List<Listing> pending = new ArrayList<>();
        for (Listing l : listings) {
            if (l.origTitle.isEmpty() && needsVintedTranslation(l.title)) pending.add(l);
        }

        int i = 0;
        while (i < pending.size() && !translateBroken && !Thread.currentThread().isInterrupted()) {
            List<Listing> group = new ArrayList<>();
            int chars = 0;
            while (i < pending.size() && (group.isEmpty() || chars + pending.get(i).title.length() < 1200)) {
                Listing l = pending.get(i++);
                group.add(l);
                chars += l.title.length() + 1;
            }

            String[] out = null;
            if (group.size() > 1) {
                StringBuilder joined = new StringBuilder();
                for (Listing l : group) {
                    if (joined.length() > 0) joined.append('\n');
                    joined.append(l.title.replace('\n', ' ').trim());
                }
                String key = (vintedEnglish ? "en" : "el") + ":#batch:" + joined;
                String t = translationCache.get(key);
                if (t == null) {
                    try {
                        t = translateChunk(joined.toString());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (t != null) translationCache.put(key, t);
                }
                if (t != null) {
                    String[] lines = t.split("\\R", -1);
                    if (lines.length == group.size()) out = lines;
                }
            }

            for (int k = 0; k < group.size(); k++) {
                Listing l = group.get(k);
                String t = out != null ? out[k].trim() : translateToSelectedLanguage(l.title);
                if (t != null && !t.isEmpty() && !t.equals(l.title)) {
                    l.origTitle = l.title;
                    l.title = t;
                }
            }
        }
    }

    // Μεταφράζει τίτλο / περιγραφή / κατάσταση / χώρα στη γλώσσα εμφάνισης.
    // Τα specs έχουν ήδη αναγνωριστεί από το αρχικό κείμενο και δεν ξαναϋπολογίζονται.
    private void vintedTranslate(Listing l) {
        if (l == null) return;

        if (l.origTitle.isEmpty() && needsVintedTranslation(l.title)) {
            String t = translateToSelectedLanguage(l.title);
            if (!t.equals(l.title)) {
                l.origTitle = l.title;
                l.title = t;
            }
        }

        String body = l.fullDescription.isBlank() ? l.description : l.fullDescription;

        if (l.origDescription.isEmpty() && needsVintedTranslation(body)) {
            String t = translateToSelectedLanguage(body);
            if (!t.equals(body)) {
                l.origDescription = body;
                if (!l.fullDescription.isBlank()) l.fullDescription = t;
                l.description = t;
            }
        }

        if (vintedEnglish) {
            l.condition = vintedConditionEn(l.condition);
        } else if (needsVintedTranslation(l.condition)) {
            l.condition = translateToSelectedLanguage(l.condition);
        }
        if (needsVintedTranslation(l.country)) l.country = translateToSelectedLanguage(l.country);
        if (needsVintedTranslation(l.city)) l.city = translateToSelectedLanguage(l.city);
    }

    private static final class VintedPage {
        final List<Listing> listings;
        final int totalPages;
        final int totalEntries;

        VintedPage(List<Listing> listings, int totalPages, int totalEntries) {
            this.listings = listings;
            this.totalPages = totalPages;
            this.totalEntries = totalEntries;
        }
    }

    // Ένα αίτημα ανά VINTED_REQUEST_SPACING_MS συνολικά, όσα νήματα κι αν τρέχουν.
    private void vintedPace() throws InterruptedException {
        long wait;
        synchronized (vintedGate) {
            long now = System.nanoTime();
            long at = Math.max(now, vintedNextRequestNanos);
            vintedNextRequestNanos = at + VINTED_REQUEST_SPACING_MS * 1_000_000L;
            wait = at - now;
        }
        if (wait > 0) {
            Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L));
        }
    }

    private String vintedLang() {
        return vintedEnglish ? "en-GB,en;q=0.9,el;q=0.4" : "el-GR,el;q=0.9,en;q=0.8";
    }

    private void setVintedEnglish(boolean english) {
        if (vintedEnglish == english && VintedTools.english == english) return;

        vintedEnglish = english;
        VintedTools.english = english;
        // Νέα συνεδρία, ώστε τα cookies της προηγούμενης γλώσσας να μην κρατήσουν τη σελίδα στα ελληνικά.
        vintedCookies.clear();
        vintedSessionReady = false;
    }

    private void ensureVintedSession() {
        if (vintedSessionReady) return;
        synchronized (vintedSessionLock) {
            if (vintedSessionReady) return;
            try {
                org.jsoup.Connection.Response r = Jsoup.connect(VINTED_BASE + "/")
                        .userAgent(VINTED_UA)
                        .header("Accept-Language", vintedLang())
                        .timeout(TIMEOUT_MS)
                        .ignoreHttpErrors(true)
                        .execute();
                vintedCookies.putAll(r.cookies());
            } catch (Exception ignored) {
            }
            vintedSessionReady = true;
        }
    }

    private org.jsoup.Connection.Response vintedExecute(String url, boolean json) throws Exception {
        ensureVintedSession();
        vintedPace();
        org.jsoup.Connection.Response r = Jsoup.connect(url)
                .userAgent(VINTED_UA)
                .header("Accept-Language", vintedLang())
                .header("Accept", json
                        ? "application/json, text/plain, */*"
                        : "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .referrer(VINTED_BASE + "/")
                .cookies(vintedCookies)
                .timeout(TIMEOUT_MS)
                .maxBodySize(0)
                .ignoreHttpErrors(true)
                .ignoreContentType(json)
                .execute();
        vintedCookies.putAll(r.cookies());
        return r;
    }

    private Document vintedConnect(String url) throws Exception {
        IOException last = null;

        for (int attempt = 0; attempt <= 3; attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }

            org.jsoup.Connection.Response r = vintedExecute(url, false);
            int status = r.statusCode();

            if (status == 429 || status == 503) {
                last = new IOException("HTTP " + status + " (το Vinted περιορίζει τα αιτήματα): " + url);
                Thread.sleep(1500L * (attempt + 1));
                continue;
            }

            if (status == 403) {
                throw new IOException("HTTP 403 — το Vinted μπλόκαρε το αίτημα (anti-bot). "
                        + "Δοκίμασε ξανά αργότερα ή άνοιξε το Vinted στον browser:\n" + url);
            }

            if (status >= 400) {
                throw new IOException("HTTP " + status + " fetching " + url);
            }

            return r.parse();
        }

        throw last != null ? last : new IOException("Αποτυχία λήψης: " + url);
    }

    // Ενώνει τα κομμάτια self.__next_f.push([1,"..."]) της σελίδας σε ένα κείμενο.
    private String vintedFlight(Document doc) {
        StringBuilder sb = new StringBuilder();

        for (Element script : doc.select("script")) {
            String data = script.data();

            if (data.startsWith("self.__next_f.push(")) {
                sb.append(VintedTools.decodeFlightChunk(data));
            }
        }

        return sb.toString();
    }

    private String cleanVintedUrl(String href) {
        String h = href == null ? "" : href.trim();

        // Αποθηκευμένο HTML από browser: κρατάμε μέχρι το πρώτο κενό (όπως στη Vendora).
        int space = h.indexOf(' ');

        if (space > 0) {
            h = h.substring(0, space);
        }

        if (h.startsWith("//")) {
            h = "https:" + h;
        } else if (h.startsWith("/")) {
            h = VINTED_BASE + h;
        }

        if (!h.contains("/items/")) {
            return "";
        }

        return removeQueryAndFragment(h);
    }

    private String vintedItemId(String url) {
        Matcher m = Pattern.compile("/items/(\\d+)").matcher(url == null ? "" : url);
        return m.find() ? m.group(1) : "";
    }

    private Listing vintedListing(VintedTools.Item it, String categoryName) {
        String url = cleanVintedUrl(it.url);

        if (url.isEmpty()) {
            return null;
        }

        Listing l = new Listing(
                it.title,
                it.price,
                SpecParser.parse(it.title, ""),
                "",
                it.condition,
                "Πώληση",
                categoryName,
                url,
                isLaptop(it.title));

        l.brand = it.brand;
        l.favourites = it.favourites;
        l.totalPrice = it.total;
        l.sellerId = it.userId;
        return l;
    }

    // Αγγελίες + αριθμός σελίδων από μία σελίδα αναζήτησης του Vinted.
    private VintedPage parseVintedPage(Document doc, String categoryName) {
        String flight = vintedFlight(doc);
        List<Listing> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (VintedTools.Item it : VintedTools.parseSearchItems(flight)) {
            Listing l = vintedListing(it, categoryName);

            if (l != null && seen.add(l.url)) {
                out.add(l);
            }
        }

        // Εφεδρικά: οι κάρτες της σελίδας (αν αλλάξει η δομή του JSON).
        if (out.isEmpty()) {
            for (Listing l : parseVintedCards(doc, categoryName)) {
                if (seen.add(l.url)) {
                    out.add(l);
                }
            }
        }

        int[] pagination = VintedTools.parsePagination(flight);
        return new VintedPage(out, pagination[0], pagination[1]);
    }

    private List<Listing> parseVintedCards(Document doc, String categoryName) {
        List<Listing> out = new ArrayList<>();

        for (Element a : doc.select("a[data-testid$=--overlay-link]")) {
            String url = cleanVintedUrl(a.attr("href"));

            if (url.isEmpty()) {
                continue;
            }

            String testId = a.attr("data-testid");
            String prefix = testId.substring(0, testId.length() - "--overlay-link".length());

            // title: «Τίτλος, Επωνυμία: X, Κατάσταση: Y, 175.00 €, 184.45 €»
            String composite = a.attr("title");
            int cut = composite.length();

            // Ελληνικά: «, Επωνυμία: X, Κατάσταση: Y» — αγγλικά: «, brand: X, condition: Y».
            Matcher marker = Pattern.compile("(?i),\\s*(Επωνυμία|Κατάσταση|Brand|Condition|Status)\\s*:")
                    .matcher(composite);

            if (marker.find() && marker.start() > 0) {
                cut = Math.min(cut, marker.start());
            }

            String title = composite.substring(0, cut).trim();

            if (title.isEmpty()) {
                continue;
            }

            Element priceEl = doc.selectFirst("[data-testid=\"" + prefix + "--price-text\"]");
            Element brandEl = doc.selectFirst("[data-testid=\"" + prefix + "--description-title\"]");
            Element condEl = doc.selectFirst("[data-testid=\"" + prefix + "--description-subtitle\"]");

            Listing l = new Listing(
                    title,
                    priceEl == null ? null : parsePrice(priceEl.text().trim()),
                    SpecParser.parse(title, ""),
                    "",
                    condEl == null ? "" : condEl.text().trim(),
                    "Πώληση",
                    categoryName,
                    url,
                    isLaptop(title));

            l.brand = brandEl == null ? "" : brandEl.text().trim();
            out.add(l);
        }

        return out;
    }

    /*
     * Περιγραφή: <div itemprop="description"> ... <span class="web_ui__Text__format"><span>κείμενο</span></span>
     * (αυτή που βλέπει ο χρήστης στη σελίδα, μεταφρασμένη). Εφεδρικά το JSON της σελίδας
     * και το JSON-LD (πρωτότυπη γλώσσα του πωλητή).
     */
    private String extractVintedDescription(Document doc, VintedTools.Detail d) {
        Element el = doc.selectFirst("[itemprop=description] span.web_ui__Text__format");

        if (el == null) {
            el = doc.selectFirst("[itemprop=description] .web_ui__Text__format");
        }

        if (el != null) {
            String text = vendoraText(el.html());

            if (!text.isBlank()) {
                return text;
            }
        }

        if (!d.description.isBlank()) {
            return d.description;
        }

        for (Element script : doc.select("script[type=application/ld+json]")) {
            String text = jsonStringField(script.data(), "description").trim();

            if (!text.isBlank()) {
                return text;
            }
        }

        return "";
    }

    // Η χώρα δεν υπάρχει στη σελίδα της αγγελίας όταν ο πωλητής κρύβει την τοποθεσία του.
    // Τότε ρωτάμε το API του Vinted (πρώτα τον χρήστη, μετά το είδος) — μία φορά ανά πωλητή.
    private String vintedCountryFromApi(Listing l) {
        if (vintedApiBlocked) {
            return "";
        }

        String itemId = vintedItemId(l.url);
        String key = l.sellerId.isEmpty() ? "item:" + itemId : "user:" + l.sellerId;
        String cached = vintedSellerCountry.get(key);

        if (cached != null) {
            return cached;
        }

        List<String> endpoints = new ArrayList<>();

        if (!l.sellerId.isEmpty()) {
            endpoints.add(VINTED_BASE + "/api/v2/users/" + l.sellerId);
        }

        if (!itemId.isEmpty()) {
            endpoints.add(VINTED_BASE + "/api/v2/items/" + itemId);
        }

        String found = "";

        for (String endpoint : endpoints) {
            try {
                org.jsoup.Connection.Response r = vintedExecute(endpoint, true);
                int status = r.statusCode();

                if (status == 401 || status == 403) {
                    vintedApiBlocked = true;
                    return "";
                }

                if (status != 200) {
                    continue;
                }

                found = VintedTools.countryFromApiJson(r.body());

                if (!found.isEmpty()) {
                    break;
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return "";
            } catch (Exception ignored) {
            }
        }

        vintedSellerCountry.put(key, found);
        return found;
    }

    /*
     * Γεμίζει μια αγγελία από τη σελίδα της: τίτλος και περιγραφή όπως φαίνονται στη σελίδα,
     * κατάσταση, επωνυμία, πωλητής, αγαπημένα, πότε αναρτήθηκε και ΧΩΡΑ.
     * Επιστρέφει την τιμή της σελίδας (για όσες κάρτες δεν είχαν τιμή).
     */
    private Double applyVintedDetails(Listing l, Document doc) {
        VintedTools.Detail d = VintedTools.parseItemPage(vintedFlight(doc));
        String body = extractVintedDescription(doc, d).trim();

        if (body.isBlank()) {
            body = l.description == null ? "" : l.description.trim();
        }

        l.fullDescription = body;

        if (!body.isBlank()) {
            l.description = body;
        }

        if (!d.title.isBlank()) {
            l.title = d.title;
        }

        l.specs = SpecParser.parse(l.title, body);

        if (!d.condition.isBlank()) l.condition = d.condition;
        if (!d.brand.isBlank()) l.brand = d.brand;
        if (!d.uploaded.isBlank()) l.posted = d.uploaded;
        if (d.favourites >= 0) l.favourites = d.favourites;
        if (l.sellerId.isEmpty()) l.sellerId = d.sellerId;
        if (!d.city.isBlank()) l.city = d.city;

        Element seller = doc.selectFirst("[data-testid=profile-username]");

        if (seller != null && !seller.text().isBlank()) {
            l.seller = seller.text().trim();
        }

        String country = d.country;

        if (country.isBlank()) {
            country = vintedCountryFromApi(l);
        }

        l.country = country;

        if (!l.laptop && isLaptop(l.title + " " + body)) {
            l.laptop = true;
        }

        l.flaggedKeywords = SpecParser.hasSuspiciousKeywords(l.title, body);
        l.detailsLoaded = true;

        Double price = null;

        try {
            if (!d.price.isEmpty()) {
                price = Double.parseDouble(d.price);
            }
        } catch (NumberFormatException ignored) {
        }

        return price;
    }

    private void fetchVintedDetails(Listing l) throws Exception {
        applyVintedDetails(l, vintedConnect(l.url));
    }

    private static class VintedTableModel extends AbstractTableModel {
        static final int COL_PRIORITY = 20;

        private final String[] columns = {
                "Τίτλος",
                "Περιγραφή",
                "Τιμή (€)",
                "Με χρέωση Vinted (€)",
                "Χώρα",
                "CPU",
                "RAM",
                "GPU",
                "SSD",
                "HDD",
                "Μητρική",
                "PSU",
                "Έξτρα",
                "Πωλητής",
                "Κατάσταση",
                "Επωνυμία",
                "Αγαπημένα",
                "Αναρτήθηκε",
                "Κατηγορία",
                "URL αγγελίας",
                "_priority"
        };

        private List<Listing> listings = new ArrayList<>();

        void setListings(List<Listing> values) {
            listings = new ArrayList<>(values);
            fireTableDataChanged();
        }

        void addListings(List<Listing> values) {
            if (values == null || values.isEmpty()) return;
            Set<String> seen = new HashSet<>();
            for (Listing existing : listings) seen.add(existing.url);
            int first = listings.size();
            for (Listing value : values) {
                if (value != null && seen.add(value.url)) listings.add(value);
            }
            if (listings.size() > first) fireTableRowsInserted(first, listings.size() - 1);
        }

        void refresh(Listing listing) {
            int index = listings.indexOf(listing);

            if (index >= 0) {
                fireTableRowsUpdated(index, index);
            }
        }

        List<Listing> all() {
            return listings;
        }

        Listing getListing(int row) {
            return row < 0 || row >= listings.size() ? null : listings.get(row);
        }

        @Override
        public int getRowCount() {
            return listings.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Class<?> getColumnClass(int column) {
            if (column == 2 || column == 3) return Double.class;
            if (column == 16 || column == COL_PRIORITY) return Integer.class;
            return String.class;
        }

        @Override
        public Object getValueAt(int row, int column) {
            Listing item = listings.get(row);
            Specs sp = item.specs;
            boolean flagged = item.flaggedKeywords || item.flaggedManySpecs;

            if (column == COL_PRIORITY) return flagged ? 1 : 0;

            // Ύποπτες/γενικές αγγελίες: κρύβουμε τα κουτιά αναγνώρισης εξαρτημάτων.
            if (item.flaggedKeywords && column >= 5 && column <= 12) return "";

            switch (column) {
                case 0: return item.title;
                case 1: return item.description;
                case 2: return item.price;
                case 3: return item.totalPrice;
                case 4: return item.country.isEmpty() ? "—" : item.country;
                case 5: return sp.cpu;
                case 6: return sp.ram;
                case 7: return sp.gpu;
                case 8: return sp.ssd;
                case 9: return sp.hdd;
                case 10: return sp.mobo;
                case 11: return sp.psu;
                case 12: return sp.extras;
                case 13: return item.seller;
                case 14: return item.condition;
                case 15: return item.brand;
                case 16: return item.favourites < 0 ? null : item.favourites;
                case 17: return item.posted;
                case 18: return item.category;
                case 19: return item.url;
                default: return "";
            }
        }
    }

    // =========================================================
    // TAB VINTED.GR — αναζήτηση με τα φίλτρα του Vinted
    // =========================================================

    private class VintedTab extends JPanel {
        private static final String ALL_COUNTRIES = "Όλες οι χώρες";
        private static final String UNKNOWN_COUNTRY = "Άγνωστη χώρα";

        // Κατάσταση όπως τη δείχνει το Vinted -> status_ids[] του URL.
        private final String[] statusNames = {
                "Νέο με ετικέτες", "Νέο χωρίς ετικέτες", "Πολύ καλό", "Καλό", "Ικανοποιητικό"
        };
        private final String[] statusIds = {"6", "1", "2", "3", "4"};
        private final String[] statusNamesEn = {"New with tags", "New without tags", "Very good", "Good", "Satisfactory"};
        private final JCheckBox[] statusBoxes = new JCheckBox[5];

        // Ταξινόμηση Vinted -> order= του URL (το τελευταίο είναι τοπικό, μόνο στον πίνακα).
        private final String[] sortLabels = {
                "Σχετικότητα", "Νεότερο πρώτα", "Τιμή: αύξουσα", "Τιμή: φθίνουσα", "Τίτλος: Α-Ω"
        };
        private final String[] sortOrders = {
                "relevance", "newest_first", "price_low_to_high", "price_high_to_low", ""
        };

        private final JTextField keywordBox = new JTextField(18);
        private final JTextField minBox = new JTextField("10", 6);
        private final JTextField maxBox = new JTextField(6);
        private final JTextField brandBox = new JTextField(12);
        private final JTextField pagesBox = new JTextField("5", 3);
        private final JTextField excludeBox = new JTextField(24);
        private final JTextField extraBox = new JTextField(34);
        private final JComboBox<Category> categoryBox = new JComboBox<>(new Category[]{
                new Category("Όλες οι κατηγορίες", "", true),
                new Category("Ηλεκτρονικά είδη", "2994", false),
                new Category("Υπολογιστές και αξεσουάρ", "3564", false),
                new Category("Ανταλλακτικά και εξαρτήματα υπολογιστών", "3582", false),
                new Category("Κάρτες γραφικών", "3602", false)
        });
        private final JComboBox<String> sortBox = new JComboBox<>(sortLabels);
        private final JComboBox<String> countryBox = new JComboBox<>(new String[]{ALL_COUNTRIES});
        private boolean refreshingCountries;
        private final JCheckBox deepBox = new JCheckBox("Λεπτομέρειες αγγελίας (περιγραφή, χώρα, specs)", true);
        private final JCheckBox englishBox = new JCheckBox("Αγγλικά (English)", false);
        private final JButton searchBtn = new JButton("Αναζήτηση στο Vinted");
        private final JLabel status = new JLabel("Έτοιμο. Γράψε π.χ. GTX 1080 και πάτα Αναζήτηση.");

        private final JLabel detailTitle = new JLabel("Επίλεξε μια αγγελία για να δεις την περιγραφή της.");
        private final JTextArea detailArea = new JTextArea();
        private final JButton loadFullBtn = new JButton("Φόρτωση πλήρους περιγραφής");
        private final JButton openBtn = new JButton("Άνοιγμα στον browser");
        private volatile Listing shown;

        private final VintedTableModel model = new VintedTableModel();
        private final TableRowSorter<VintedTableModel> rowSorter = new TableRowSorter<>(model);
        private final JTable table = new JTable(model) {
            @Override
            public String getToolTipText(MouseEvent event) {
                int row = rowAtPoint(event.getPoint());
                int col = columnAtPoint(event.getPoint());
                if (row < 0 || col < 0) return null;
                Object value = getValueAt(row, col);
                if (value == null || value.toString().length() < 18) return null;
                String text = value.toString().replace("&", "&amp;").replace("<", "&lt;");
                return "<html><body style='width:380px'>" + text + "</body></html>";
            }

            @Override
            public Component prepareRenderer(javax.swing.table.TableCellRenderer renderer,
                                             int row, int column) {
                Component component = super.prepareRenderer(renderer, row, column);
                Listing listing = model.getListing(convertRowIndexToModel(row));
                if (!isRowSelected(row)) {
                    component.setBackground(getBackground());
                    component.setForeground(getForeground());
                }
                applyFlaggedRowBorder(component, this, isFlaggedListing(listing), row, column);
                return component;
            }
        };

        private SwingWorker<List<Listing>, Void> worker;
        private final AtomicInteger uiGeneration = new AtomicInteger();
        private volatile String summary = "";
        private volatile String dumpPath = "";

        VintedTab() {
            super(new BorderLayout(5, 5));
            // Price ascending is the default (Vinted order=price_low_to_high).
            sortBox.setSelectedIndex(2);

            for (int i = 0; i < statusBoxes.length; i++) {
                statusBoxes[i] = new JCheckBox(statusNames[i]);
                statusBoxes[i].addActionListener(e -> applyFilters());
            }

            JPanel form = new JPanel(new GridBagLayout());
            form.setBorder(BorderFactory.createEmptyBorder(10, 10, 5, 10));
            GridBagConstraints c = new GridBagConstraints();
            c.insets = new Insets(4, 4, 4, 4);
            c.fill = GridBagConstraints.HORIZONTAL;

            field(form, c, 0, "Αναζήτηση:", keywordBox);
            field(form, c, 1, "Κατηγορία:", categoryBox);
            field(form, c, 2, "Ελάχιστη τιμή:", minBox);
            field(form, c, 3, "Μέγιστη τιμή:", maxBox);
            field(form, c, 4, "Επωνυμία:", brandBox);
            field(form, c, 5, "Χώρα:", countryBox);
            field(form, c, 6, "Ταξινόμηση:", sortBox);
            field(form, c, 7, "Σελίδες:", pagesBox);

            c.gridx = 8; c.gridy = 1; c.weightx = 0;
            form.add(deepBox, c);
            c.gridx = 9; c.gridy = 1; c.weightx = 0;
            form.add(englishBox, c);
            c.gridx = 10; c.gridy = 1; c.weightx = 0;
            styleButton(searchBtn, VINTED_COLOR, INK);
            form.add(searchBtn, c);

            englishBox.setToolTipText("Από προεπιλογή οι ξενόγλωσσες αγγελίες (π.χ. Ρουμανικά) μεταφράζονται στα ελληνικά. "
                    + "Ενεργοποίησέ το για αγγλική εμφάνιση και μετάφραση ελληνικών τίτλων/περιγραφών. "
                    + "Τα specs αναγνωρίζονται από το αρχικό κείμενο.");
            englishBox.addActionListener(e -> setVintedEnglish(englishBox.isSelected()));

            brandBox.setToolTipText("Φίλτρο στον πίνακα, π.χ. MSI, ASUS (με κόμμα). "
                    + "Για φίλτρο στο ίδιο το Vinted χρησιμοποίησε brand_ids[]=ID στο πεδίο «Επιπλέον φίλτρα».");
            countryBox.setToolTipText("Η χώρα φαίνεται μόνο μετά το άνοιγμα της αγγελίας (επιλογή «Λεπτομέρειες αγγελίας»).");
            pagesBox.setToolTipText("Πόσες σελίδες αποτελεσμάτων θα διαβαστούν (96 αγγελίες ανά σελίδα).");

            JPanel statusRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            statusRow.add(new JLabel("Κατάσταση:"));
            for (JCheckBox box : statusBoxes) statusRow.add(box);

            JPanel extra = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            extra.add(new JLabel("Αποκλεισμός λέξεων (με κόμμα):"));
            excludeBox.setToolTipText("π.χ. ζητηση, χαλασμενο, ανταλλαγη — ελέγχεται όλο το κείμενο κάθε αγγελίας");
            extra.add(excludeBox);
            extra.add(new JLabel("   Επιπλέον φίλτρα Vinted ή επικόλληση URL (προαιρετικό):"));
            extraBox.setToolTipText("π.χ. brand_ids[]=472684&color_ids[]=1  ή ολόκληρο URL από το Vinted — "
                    + "κρατιούνται μόνο τα φίλτρα (catalog, brand_ids, status_ids, color_ids, size_ids, material_ids, "
                    + "price_from, price_to, order)");
            extra.add(extraBox);

            JPanel rows = new JPanel(new GridLayout(2, 1, 0, 4));
            rows.add(statusRow);
            rows.add(extra);

            JPanel controls = new JPanel(new BorderLayout(0, 3));
            controls.add(form, BorderLayout.CENTER);
            controls.add(rows, BorderLayout.SOUTH);

            status.setBorder(BorderFactory.createEmptyBorder(5, 10, 8, 10));

            JPanel north = new JPanel(new BorderLayout());
            north.add(banner("VINTED.GR  ·  Αναζήτηση αγγελιών", VINTED_COLOR), BorderLayout.NORTH);
            north.add(controls, BorderLayout.CENTER);
            north.add(status, BorderLayout.SOUTH);

            configureTable();
            applySort();

            JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                    new JScrollPane(table), buildDetail());
            split.setResizeWeight(0.72);
            split.setContinuousLayout(true);

            add(north, BorderLayout.NORTH);
            add(split, BorderLayout.CENTER);

            keywordBox.addActionListener(e -> search());
            searchBtn.addActionListener(e -> search());
            sortBox.addActionListener(e -> applySort());
            countryBox.addActionListener(e -> {
                if (!refreshingCountries) applyFilters();
            });
            minBox.addActionListener(e -> applyFilters());
            maxBox.addActionListener(e -> applyFilters());
            addLiveTextListener(minBox, this::applyFilters);
            addLiveTextListener(maxBox, this::applyFilters);
            brandBox.addActionListener(e -> applyFilters());
            excludeBox.addActionListener(e -> applyFilters());
            javax.swing.event.DocumentListener live = new javax.swing.event.DocumentListener() {
                public void insertUpdate(javax.swing.event.DocumentEvent e) { applyFilters(); }
                public void removeUpdate(javax.swing.event.DocumentEvent e) { applyFilters(); }
                public void changedUpdate(javax.swing.event.DocumentEvent e) { applyFilters(); }
            };
            excludeBox.getDocument().addDocumentListener(live);
            brandBox.getDocument().addDocumentListener(live);
        }

        private void field(JPanel panel, GridBagConstraints c, int x, String label, JComponent comp) {
            c.gridx = x; c.gridy = 0; c.weightx = 0;
            panel.add(new JLabel(label), c);
            c.gridx = x; c.gridy = 1;
            c.weightx = comp instanceof JTextField ? 0.3 : 0.5;
            panel.add(comp, c);
        }

        private void setStatusLater(String message) {
            SwingUtilities.invokeLater(() -> status.setText(message));
        }

        private void configureTable() {
            table.setRowSorter(rowSorter);
            table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);

            Comparator<Double> priceOrder = (a, b) ->
                    Double.compare(a == null ? Double.MAX_VALUE : a, b == null ? Double.MAX_VALUE : b);
            rowSorter.setComparator(2, priceOrder);
            rowSorter.setComparator(3, priceOrder);

            int[] widths = {260, 360, 80, 120, 120, 140, 110, 150, 110, 110, 180, 120, 200,
                    120, 130, 120, 90, 130, 160, 350, 0};
            for (int i = 0; i < widths.length; i++) {
                table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
            }
            table.removeColumn(table.getColumnModel().getColumn(VintedTableModel.COL_PRIORITY));

            styleHeader(table, VINTED_COLOR, INK);

            table.getSelectionModel().addListSelectionListener(e -> {
                if (e.getValueIsAdjusting()) return;
                int viewRow = table.getSelectedRow();
                show(viewRow < 0 ? null : model.getListing(table.convertRowIndexToModel(viewRow)));
            });

            table.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() != 2) return;
                    int viewRow = table.getSelectedRow();
                    if (viewRow < 0) return;
                    Listing l = model.getListing(table.convertRowIndexToModel(viewRow));
                    if (l != null) openUrl(l.url);
                }
            });
        }

        private void applySort() {
            List<RowSorter.SortKey> keys = new ArrayList<>();
            keys.add(new RowSorter.SortKey(VintedTableModel.COL_PRIORITY, SortOrder.ASCENDING));
            switch (sortBox.getSelectedIndex()) {
                case 2: keys.add(new RowSorter.SortKey(2, SortOrder.ASCENDING)); break;
                case 3: keys.add(new RowSorter.SortKey(2, SortOrder.DESCENDING)); break;
                case 4: keys.add(new RowSorter.SortKey(0, SortOrder.ASCENDING)); break;
                default: break; // Σχετικότητα / Νεότερο: η σειρά που επέστρεψε το Vinted
            }
            rowSorter.setSortKeys(keys);
        }

        private List<String> selectedStatusNames() {
            List<String> names = new ArrayList<>();
            for (int i = 0; i < statusBoxes.length; i++) {
                if (statusBoxes[i].isSelected()) {
                    names.add(normalize(statusNames[i]));
                    names.add(normalize(statusNamesEn[i]));
                }
            }
            return names;
        }

        private void applyFilters() {
            final Double min = parseOptionalPrice(minBox.getText());
            final Double max = parseOptionalPrice(maxBox.getText());
            final List<String> wantedStatuses = selectedStatusNames();
            final String country = (String) countryBox.getSelectedItem();
            final List<String> excluded = new ArrayList<>();
            final List<String> brands = new ArrayList<>();

            for (String term : excludeBox.getText().split("[,;\\n\\r]+")) {
                String n = normalize(term.trim());
                if (!n.isBlank()) excluded.add(n);
            }

            for (String term : brandBox.getText().split("[,;\\n\\r]+")) {
                String n = normalize(term.trim());
                if (!n.isBlank()) brands.add(n);
            }

            rowSorter.setRowFilter(new RowFilter<VintedTableModel, Integer>() {
                @Override
                public boolean include(Entry<? extends VintedTableModel, ? extends Integer> entry) {
                    Listing l = model.getListing(entry.getIdentifier());
                    if (l == null || isDamagedListing(l)) return false;

                    String text = normalize(l.title + " " + l.description + " "
                            + l.fullDescription + " " + l.seller + " " + l.category + " "
                            + l.url + " " + l.condition + " " + l.brand + " " + l.country + " "
                            + l.origTitle + " " + l.origDescription + " "
                            + l.specs.cpu + " " + l.specs.ram + " " + l.specs.gpu + " "
                            + l.specs.ssd + " " + l.specs.hdd + " " + l.specs.mobo + " "
                            + l.specs.psu + " " + l.specs.extras);
                    for (String ex : excluded) {
                        if (text.contains(ex)) return false;
                    }

                    if (min != null && (l.price == null || l.price < min)) return false;
                    if (max != null && (l.price == null || l.price > max)) return false;

                    if (!brands.isEmpty()) {
                        String brandText = normalize(l.brand + " " + l.title);
                        boolean any = false;
                        for (String b : brands) {
                            if (brandText.contains(b)) { any = true; break; }
                        }
                        if (!any) return false;
                    }

                    if (!wantedStatuses.isEmpty() && !l.condition.isEmpty()
                            && !wantedStatuses.contains(normalize(l.condition))) {
                        return false;
                    }

                    if (country != null && !country.equals(ALL_COUNTRIES)) {
                        if (country.equals(UNKNOWN_COUNTRY)) {
                            if (!l.country.isEmpty()) return false;
                        } else if (!l.country.equalsIgnoreCase(country)) {
                            return false;
                        }
                    }

                    return true;
                }
            });

            status.setText("Εμφανίζονται " + table.getRowCount()
                    + " από " + model.getRowCount() + " αγγελίες.");
        }

        // Γεμίζει το φίλτρο «Χώρα» με τις χώρες που βρέθηκαν στις αγγελίες.
        private void refreshCountries() {
            TreeSet<String> found = new TreeSet<>();
            boolean unknown = false;

            for (Listing l : model.all()) {
                if (l.country.isEmpty()) unknown = true;
                else found.add(l.country);
            }

            Object selected = countryBox.getSelectedItem();
            refreshingCountries = true;

            try {
                DefaultComboBoxModel<String> m = new DefaultComboBoxModel<>();
                m.addElement(ALL_COUNTRIES);
                for (String name : found) m.addElement(name);
                if (unknown && !found.isEmpty()) m.addElement(UNKNOWN_COUNTRY);
                countryBox.setModel(m);

                if (selected != null && m.getIndexOf(selected) >= 0) {
                    countryBox.setSelectedItem(selected);
                } else {
                    countryBox.setSelectedIndex(0);
                }
            } finally {
                refreshingCountries = false;
            }
        }

        // ---------------- Αναζήτηση ----------------

        private void search() {
            final String term = keywordBox.getText().trim();

            if (term.isBlank()) {
                JOptionPane.showMessageDialog(this, "Γράψε έναν όρο αναζήτησης.");
                return;
            }

            if (worker != null && !worker.isDone()) {
                worker.cancel(true);
            }

            int pages = 5;
            try { pages = Integer.parseInt(pagesBox.getText().trim()); }
            catch (NumberFormatException ignored) { }
            final int maxPages = Math.max(1, Math.min(pages, VINTED_MAX_PAGES));

            final Category category = (Category) categoryBox.getSelectedItem();
            final boolean deep = deepBox.isSelected();
            final Double min = parseOptionalPrice(minBox.getText());
            final Double max = parseOptionalPrice(maxBox.getText());
            final List<String> filters = buildFilterPairs(category, min, max);
            final int runId = uiGeneration.incrementAndGet();
            final java.util.function.Consumer<List<Listing>> emit = batch ->
                    SwingUtilities.invokeLater(() -> {
                        if (runId != uiGeneration.get()) return;
                        model.addListings(batch);
                        refreshCountries();
                        applyFilters();
                    });

            setVintedEnglish(englishBox.isSelected());
            resetTranslator();
            summary = "";
            dumpPath = "";
            model.setListings(Collections.emptyList());
            refreshCountries();
            show(null);
            searchBtn.setEnabled(false);
            status.setText("Vinted: αναζήτηση «" + term + "»...");

            worker = new SwingWorker<List<Listing>, Void>() {
                @Override
                protected List<Listing> doInBackground() throws Exception {
                    return runSearch(term, category, filters, maxPages, deep, min, max, emit);
                }

                @Override
                protected void done() {
                    try {
                        if (isCancelled()) return;

                        List<Listing> listings = get();
                        model.setListings(listings);
                        refreshCountries();
                        applyFilters();

                        status.setText(status.getText()
                                + (summary.isEmpty() ? "" : " | " + summary)
                                + (dumpPath.isEmpty() ? "" : " | Αρχείο: " + dumpPath));

                        if (listings.isEmpty()) {
                            JOptionPane.showMessageDialog(VintedTab.this,
                                    "Το Vinted δεν επέστρεψε αγγελίες για τον όρο: " + term);
                        }
                    } catch (Exception ex) {
                        if (!isCancelled()) {
                            status.setText("Σφάλμα αναζήτησης.");
                            JOptionPane.showMessageDialog(VintedTab.this,
                                    "Σφάλμα:\n" + rootMessage(ex),
                                    "Vinted", JOptionPane.ERROR_MESSAGE);
                        }
                    } finally {
                        searchBtn.setEnabled(true);
                    }
                }
            };

            worker.execute();
        }

        // Τα φίλτρα της φόρμας -> παράμετροι URL του Vinted.
        private List<String> buildFilterPairs(Category category, Double min, Double max) {
            List<String> pairs = new ArrayList<>(VintedTools.filterPairs(extraBox.getText()));

            // Ό,τι έχει επιλεγεί στη φόρμα υπερισχύει όσων ήρθαν από το επικολλημένο URL.
            if (category != null && !category.all && !category.url.isEmpty()) {
                pairs.removeIf(p -> p.startsWith("catalog[]="));
                pairs.add("catalog[]=" + category.url);
            }

            if (min != null) {
                pairs.removeIf(p -> p.startsWith("price_from="));
                pairs.add("price_from=" + VintedTools.plainNumber(min));
            }

            if (max != null) {
                pairs.removeIf(p -> p.startsWith("price_to="));
                pairs.add("price_to=" + VintedTools.plainNumber(max));
            }

            if (min != null || max != null) {
                pairs.removeIf(p -> p.startsWith("currency="));
                pairs.add("currency=EUR");
            }

            boolean anyStatus = false;
            for (JCheckBox box : statusBoxes) anyStatus |= box.isSelected();
            if (anyStatus) {
                pairs.removeIf(p -> p.startsWith("status_ids[]="));
                for (int i = 0; i < statusBoxes.length; i++) {
                    if (statusBoxes[i].isSelected()) pairs.add("status_ids[]=" + statusIds[i]);
                }
            }

            String order = sortOrders[Math.max(0, sortBox.getSelectedIndex())];
            if (!order.isEmpty() && !order.equals("relevance")) {
                pairs.removeIf(p -> p.startsWith("order="));
                pairs.add("order=" + order);
            }

            return pairs;
        }

        /*
         * https://www.vinted.gr/catalog?search_text=<όρος>&<φίλτρα>&page=N
         * Κάθε σελίδα έχει έως 96 αγγελίες και το Vinted λέει πόσες σελίδες υπάρχουν.
         */
        // Όλες οι σελίδες παράλληλα (searchVintedAll). Οι λεπτομέρειες ανοίγουν μόνο αν είναι τσεκαρισμένο το «Λεπτομέρειες αγγελίας».
        private List<Listing> runSearch(
                String keyword, Category category, List<String> filters, int maxPages,
                boolean deep, Double min, Double max,
                java.util.function.Consumer<List<Listing>> emit) throws Exception {

            final String categoryName = category == null || category.all ? "Vinted" : category.name;
            List<Listing> result = searchVintedAll(keyword, filters, maxPages, categoryName,
                    msg -> setStatusLater(msg), emit);

            if (deep && !result.isEmpty()) {
                List<Listing> toEnrich = new ArrayList<>();

                for (Listing l : result) {
                    if (inRange(l, min, max)) toEnrich.add(l);
                }

                enrich(toEnrich);
            }

            if (!result.isEmpty()) {
                translateAll(result, min, max);
            }

            saveDump(categoryName, result);

            summary = "Αγγελίες: " + result.size()
                    + (vintedApiBlocked
                    ? " | Χώρα: το Vinted δεν την έδωσε (API μπλοκαρισμένο)" : "");

            return result;
        }

        // Μεταφράζει ξενόγλωσσους τίτλους / περιγραφές στα ελληνικά (ή στα αγγλικά αν ενεργοποιηθεί το checkbox).
        private void translateAll(List<Listing> listings, Double min, Double max) throws Exception {
            List<Listing> targets = new ArrayList<>();

            for (Listing l : listings) {
                if (inRange(l, min, max) && (needsVintedTranslation(l.title)
                        || needsVintedTranslation(l.fullDescription)
                        || needsVintedTranslation(l.description)
                        || needsVintedTranslation(l.country)
                        || needsVintedTranslation(l.city)
                        || needsVintedTranslation(l.condition))) {
                    targets.add(l);
                }
            }

            if (targets.isEmpty()) return;

            final int total = targets.size();
            final AtomicInteger done = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(3);

            try {
                List<Callable<Void>> tasks = new ArrayList<>();

                for (Listing l : targets) {
                    tasks.add(() -> {
                        vintedTranslate(l);
                        setStatusLater("Vinted: μετάφραση " + (vintedEnglish ? "στα αγγλικά " : "στα ελληνικά ")
                                + done.incrementAndGet() + " / " + total + "...");
                        return null;
                    });
                }

                pool.invokeAll(tasks);
            } finally {
                pool.shutdownNow();
            }

            if (translateBroken) {
                summary = summary + (summary.isEmpty() ? "" : " | ")
                        + "Η μετάφραση δεν ήταν διαθέσιμη (έμεινε το αρχικό κείμενο)";
            }
        }

        private void enrich(List<Listing> listings) throws Exception {
            List<Listing> targets = new ArrayList<>();

            for (Listing l : listings) {
                if (!l.detailsLoaded) targets.add(l);
            }

            if (targets.isEmpty()) return;

            final int total = targets.size();
            final AtomicInteger done = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(VINTED_THREADS);

            try {
                List<Callable<Void>> tasks = new ArrayList<>();

                for (Listing l : targets) {
                    tasks.add(() -> {
                        try {
                            fetchVintedDetails(l);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        } catch (Exception ignored) {
                        }

                        setStatusLater("Vinted: λήψη λεπτομερειών "
                                + done.incrementAndGet() + " / " + total + "...");
                        return null;
                    });
                }

                pool.invokeAll(tasks);
            } finally {
                pool.shutdownNow();
            }
        }

        private void saveDump(String categoryName, Collection<Listing> listings) {
            File file = new File("vinted_"
                    + categoryName.replaceAll("[^\\p{L}\\p{N}]+", "_") + ".txt").getAbsoluteFile();

            try (PrintWriter out = new PrintWriter(new OutputStreamWriter(
                    new FileOutputStream(file), StandardCharsets.UTF_8))) {

                for (Listing l : listings) {
                    out.println("Τίτλος: " + l.title
                            + " | Τιμή: " + (l.price == null ? "-" : l.price + " €")
                            + " | Χώρα: " + (l.country.isEmpty() ? "-" : l.country)
                            + " | Κατάσταση: " + l.condition
                            + " | Επωνυμία: " + l.brand
                            + " | Πωλητής: " + l.seller
                            + " | URL: " + l.url
                            + " | Περιγραφή: " + (l.fullDescription.isBlank()
                            ? l.description : l.fullDescription).replace('\n', ' '));
                }

                dumpPath = file.getPath();
            } catch (IOException ex) {
                dumpPath = "";
            }
        }

        // ---------------- Πάνελ περιγραφής ----------------

        private JPanel buildDetail() {
            detailArea.setEditable(false);
            detailArea.setLineWrap(true);
            detailArea.setWrapStyleWord(true);
            detailArea.setMargin(new Insets(6, 8, 6, 8));

            loadFullBtn.setEnabled(false);
            openBtn.setEnabled(false);

            loadFullBtn.addActionListener(e -> loadFull());
            openBtn.addActionListener(e -> {
                Listing l = shown;
                if (l != null) openUrl(l.url);
            });

            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
            buttons.add(loadFullBtn);
            buttons.add(openBtn);

            JPanel top = new JPanel(new BorderLayout(8, 0));
            top.setBorder(BorderFactory.createEmptyBorder(6, 10, 4, 10));
            top.add(detailTitle, BorderLayout.CENTER);
            top.add(buttons, BorderLayout.EAST);

            JPanel panel = new JPanel(new BorderLayout());
            panel.add(top, BorderLayout.NORTH);
            panel.add(new JScrollPane(detailArea), BorderLayout.CENTER);
            panel.setPreferredSize(new Dimension(100, 220));
            return panel;
        }

        private void show(Listing l) {
            shown = l;

            if (l == null) {
                detailTitle.setText("Επίλεξε μια αγγελία για να δεις την περιγραφή της.");
                detailArea.setText("");
                loadFullBtn.setEnabled(false);
                openBtn.setEnabled(false);
                return;
            }

            detailTitle.setText(l.title + (l.price == null ? "" : "  —  " + l.price + " €")
                    + (l.country.isEmpty() ? "" : "  ·  " + l.country));

            StringBuilder text = new StringBuilder();

            if (l.detailsLoaded) {
                text.append("Χώρα: ").append(l.country.isEmpty() ? "— (δεν εμφανίζεται από το Vinted)" : l.country);
                if (!l.city.isEmpty()) text.append("  ·  Πόλη: ").append(l.city);
                if (!l.brand.isEmpty()) text.append("  ·  Επωνυμία: ").append(l.brand);
                if (!l.condition.isEmpty()) text.append("  ·  Κατάσταση: ").append(l.condition);
                if (!l.posted.isEmpty()) text.append("  ·  Αναρτήθηκε: ").append(l.posted);
                if (l.favourites >= 0) text.append("  ·  Αγαπημένα: ").append(l.favourites);
                text.append("\n\n");
            }

            if (!l.fullDescription.isEmpty()) {
                text.append(l.fullDescription);
            } else if (l.detailsLoaded) {
                text.append("Δεν βρέθηκε περιγραφή στη σελίδα της αγγελίας.");
            } else {
                text.append("(Η περιγραφή δεν έχει φορτωθεί — πάτα «Φόρτωση πλήρους περιγραφής»)");
            }

            detailArea.setText(text.toString());
            detailArea.setCaretPosition(0);
            loadFullBtn.setEnabled(!l.detailsLoaded);
            openBtn.setEnabled(true);
        }

        private void loadFull() {
            final Listing l = shown;

            if (l == null) return;

            if (l.detailsLoaded) {
                show(l);
                return;
            }

            loadFullBtn.setEnabled(false);
            detailArea.setText("Φόρτωση περιγραφής...");
            setVintedEnglish(englishBox.isSelected());
            resetTranslator();

            new SwingWorker<Void, Void>() {
                @Override
                protected Void doInBackground() throws Exception {
                    fetchVintedDetails(l);
                    vintedTranslate(l);
                    return null;
                }

                @Override
                protected void done() {
                    try {
                        get();
                        model.refresh(l);
                        refreshCountries();
                        if (shown == l) show(l);
                    } catch (Exception ex) {
                        if (shown == l) {
                            detailArea.setText("Σφάλμα φόρτωσης:\n" + rootMessage(ex));
                            loadFullBtn.setEnabled(true);
                        }
                    }
                }
            }.execute();
        }
    }

    // =========================================================
    // TAB «ΟΛΕΣ ΟΙ ΑΓΟΡΕΣ» — Insomnia + Vendora + Vinted + Facebook Marketplace ταυτόχρονα
    // =========================================================

    private static class AllMarketRow {
        final Listing listing;
        final String platform;

        AllMarketRow(Listing listing, String platform) {
            this.listing = listing;
            this.platform = platform;
        }
    }

    // =========================================================
    // TAB «ΟΛΕΣ ΟΙ ΑΓΟΡΕΣ» — Insomnia + Vendora + Vinted + Facebook Marketplace ταυτόχρονα
    // =========================================================

    private static class AllMarketsModel extends AbstractTableModel {
        static final int COL_HIDE = 0;
        static final int COL_PLATFORM = 1;
        static final int COL_TITLE = 2;
        static final int COL_DESC = 3;
        static final int COL_PRICE = 4;
        static final int COL_CPU = 5;
        static final int COL_RAM = 6;
        static final int COL_GPU = 7;
        static final int COL_SSD = 8;
        static final int COL_HDD = 9;
        static final int COL_MOBO = 10;
        static final int COL_PSU = 11;
        static final int COL_EXTRAS = 12;
        static final int COL_LOCATION = 13;
        static final int COL_SELLER = 14;
        static final int COL_URL = 15;

        private final String[] columns = {
                "Απόκρυψη", "Πλατφόρμα", "Τίτλος", "Περιγραφή", "Τιμή (€)",
                "CPU", "RAM", "GPU", "SSD", "HDD", "Μητρική", "PSU", "Έξτρα",
                "Τοποθεσία", "Πωλητής", "URL"
        };
        private final List<AllMarketRow> rows = new ArrayList<>();

        void setRows(List<AllMarketRow> values) {
            rows.clear();
            rows.addAll(values);
            fireTableDataChanged();
        }

        // Προσθέτει αποτελέσματα όσο φτάνουν από τις πλατφόρμες (χωρίς να περιμένει να τελειώσουν όλες).
        void addRows(List<AllMarketRow> values) {
            if (values.isEmpty()) return;
            int first = rows.size();
            rows.addAll(values);
            fireTableRowsInserted(first, rows.size() - 1);
        }

        void refreshAll() {
            if (!rows.isEmpty()) fireTableRowsUpdated(0, rows.size() - 1);
        }

        AllMarketRow getRow(int i) {
            return i < 0 || i >= rows.size() ? null : rows.get(i);
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int c) { return columns[c]; }
        @Override public Class<?> getColumnClass(int c) { return c == COL_PRICE ? Double.class : String.class; }

        static String locationOf(Listing l) {
            if (!l.location.isEmpty()) return l.location;
            if (l.city.isEmpty()) return l.country;
            return l.city + (l.country.isEmpty() ? "" : ", " + l.country);
        }

        @Override
        public Object getValueAt(int r, int c) {
            AllMarketRow x = rows.get(r);
            Listing l = x.listing;
            Specs sp = l.specs == null ? new Specs() : l.specs;

            // Ύποπτες/γενικές αγγελίες: κρύβουμε τα αναγνωρισμένα specs όπως και στα υπόλοιπα tabs.
            if (l.flaggedKeywords && c >= COL_CPU && c <= COL_EXTRAS) {
                return "";
            }

            switch (c) {
                case COL_HIDE: return "👁";
                case COL_PLATFORM: return x.platform;
                case COL_TITLE: return l.title;
                case COL_DESC: return l.description == null || l.description.isBlank() ? l.fullDescription : l.description;
                case COL_PRICE: return l.price;
                case COL_CPU: return sp.cpu;
                case COL_RAM: return sp.ram;
                case COL_GPU: return sp.gpu;
                case COL_SSD: return sp.ssd;
                case COL_HDD: return sp.hdd;
                case COL_MOBO: return sp.mobo;
                case COL_PSU: return sp.psu;
                case COL_EXTRAS: return sp.extras;
                case COL_LOCATION: return locationOf(l);
                case COL_SELLER: return l.seller;
                case COL_URL: return l.url;
                default: return "";
            }
        }
    }

    private class AllMarketsTab extends JPanel {
        private final File hiddenStoreFile = new File("all_markets_hidden.properties").getAbsoluteFile();
        private final Properties hiddenStore = new Properties();
        private final Set<String> hiddenUrlsForCurrentSearch = Collections.synchronizedSet(new LinkedHashSet<>());
        private volatile String currentSearchKey = "";

        private final JTextField keyword = new JTextField(18);
        private final JTextField minPrice = new JTextField("", 6);
        private final JTextField maxPrice = new JTextField("", 6);
        private final JTextField excludeWords = new JTextField(16);
        private final JTextField includeWords = new JTextField(16);
        private final JTextField pagesBox = new JTextField("30", 4);
        private final JTextField fbPlacesBox = new JTextField(16);
        private final JTextField fbRadiusBox = new JTextField(4);
        private final JTextField fbBudgetBox = new JTextField(String.valueOf(FB_BUDGET), 4);
        private final JTextField fbCookieBox = new JTextField(22);
        private final JButton fbOpenBtn = new JButton("Facebook στον browser");
        private final JButton restoreHiddenBtn = new JButton("Εμφάνιση κρυμμένων (0)");
        // Πλατφόρμες αναζήτησης & άμεσου φιλτραρίσματος: όλες ενεργές από προεπιλογή.
        private final JCheckBox[] platformBoxes = {
                new JCheckBox("Insomnia", true), new JCheckBox("Vendora", true),
                new JCheckBox("Vinted", true), new JCheckBox("Facebook", true)};
        private final JLabel platformCount = new JLabel("4/4");
        private final JCheckBox loadDescBox = new JCheckBox("Φόρτωση περιγραφών", true);
        private final JToggleButton settingsBtn = new JToggleButton("⚙ Ρυθμίσεις");
        private final AtomicBoolean refreshPending = new AtomicBoolean();
        private boolean previewHasDetails;
        private final JComboBox<String> conditionFilter = new JComboBox<>(
                new String[]{"Όλες", "ΝΕΟ", "ΜΕΤΑΧΕΙΡΙΣΜΕΝΟ"});
        private final JComboBox<String> laptopFilter = new JComboBox<>(
                new String[]{"Όλα (PC + Laptop)", "Μόνο PC (χωρίς laptop)", "Μόνο Laptop"});
        private final JComboBox<String> transactionFilter = new JComboBox<>(
                new String[]{"Όλες", "Πώληση", "Ζήτηση"});
        private final JButton search = new JButton("Αναζήτηση παντού");
        private final JLabel status = new JLabel("Αναζήτηση σε Insomnia, Vendora, Vinted και Facebook (όλες οι σελίδες, παράλληλα).");
        private final AllMarketsModel model = new AllMarketsModel();
        private final JTable table = new JTable(model) {
            @Override
            public String getToolTipText(MouseEvent event) {
                int row = rowAtPoint(event.getPoint());
                int col = columnAtPoint(event.getPoint());
                if (row < 0 || col < 0) return null;
                int modelCol = convertColumnIndexToModel(col);
                if (modelCol == AllMarketsModel.COL_HIDE) {
                    return "Απόκρυψη αγγελίας για αυτή την αναζήτηση";
                }
                Object value = getValueAt(row, col);
                if (value == null || value.toString().length() < 18) return null;
                String text = value.toString().replace("&", "&amp;").replace("<", "&lt;");
                return "<html><body style='width:380px'>" + text + "</body></html>";
            }

            @Override
            public Component prepareRenderer(javax.swing.table.TableCellRenderer renderer,
                                             int row, int column) {
                Component component = super.prepareRenderer(renderer, row, column);
                AllMarketRow marketRow = model.getRow(convertRowIndexToModel(row));
                int modelCol = convertColumnIndexToModel(column);

                if (component instanceof JLabel) {
                    JLabel lbl = (JLabel) component;
                    if (modelCol == AllMarketsModel.COL_HIDE) {
                        lbl.setHorizontalAlignment(SwingConstants.CENTER);
                        lbl.setFont(lbl.getFont().deriveFont(Font.PLAIN, 14f));
                    } else if (modelCol == AllMarketsModel.COL_PRICE) {
                        lbl.setHorizontalAlignment(SwingConstants.RIGHT);
                    } else {
                        lbl.setHorizontalAlignment(SwingConstants.LEFT);
                    }
                }

                if (!isCellSelected(row, column)) {
                    Color source = marketRow == null ? ALL_MARKETS_COLOR : platformColor(marketRow.platform);
                    // Χρωματισμός γραμμής ανάλογα με την πλατφόρμα προέλευσης
                    Color baseBg = mix(SURFACE, source, row % 2 == 0 ? 0.14 : 0.21);
                    component.setBackground(baseBg);
                    component.setForeground(modelCol == AllMarketsModel.COL_PLATFORM
                            ? darken(source, 0.45)
                            : TEXT_PRIMARY);
                    if (modelCol == AllMarketsModel.COL_PLATFORM) {
                        component.setFont(component.getFont().deriveFont(Font.BOLD));
                    }
                }
                applyFlaggedRowBorder(component, this,
                        marketRow != null && isFlaggedListing(marketRow.listing), row, column);
                return component;
            }
        };
        private final TableRowSorter<AllMarketsModel> sorter = new TableRowSorter<>(model);
        private final JLabel previewTitle = new JLabel("Επίλεξε αγγελία για γρήγορη προεπισκόπηση");
        private final JTextArea preview = new JTextArea();
        private final JButton openListing = new JButton("Άνοιγμα αγγελίας στον browser");
        private SwingWorker<Integer, Void> worker;
        private AllMarketRow selectedRow;
        // Ο όρος της τελευταίας αναζήτησης· μόνο αγγελίες που τον περιέχουν εμφανίζονται στον πίνακα.
        private volatile Pattern searchedTermPattern;

        // Κάθε νέα αναζήτηση αυξάνει τον αριθμό· τα αποτελέσματα παλιάς αναζήτησης αγνοούνται.
        private final AtomicInteger generation = new AtomicInteger();
        private final Set<String> shownKeys = new HashSet<>();
        private final Map<String, String> progressMap = new ConcurrentHashMap<>();

        AllMarketsTab() {
            super(new BorderLayout(6, 6));
            loadHiddenStore();

            // ---- Γραμμή όρου ----
            JPanel termRow = new JPanel(new BorderLayout(8, 0));
            termRow.add(uiLabel("Όρος αναζήτησης", true), BorderLayout.WEST);
            termRow.add(keyword, BorderLayout.CENTER);
            styleButton(search, ALL_MARKETS_COLOR, INK);
            termRow.add(search, BorderLayout.EAST);
            termRow.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));

            // ---- Πού ψάχνουμε (και άμεσο φίλτρο πλατφόρμας) ----
            Color[] chipColors = {INSOMNIA_COLOR, VENDORA_COLOR, VINTED_COLOR, FACEBOOK_COLOR};
            JPanel searchRow = uiRow();
            searchRow.add(uiLabel("Αναζήτηση σε (άμεσο φίλτρο):", false));
            for (int i = 0; i < platformBoxes.length; i++) {
                JCheckBox box = platformBoxes[i];
                box.putClientProperty("corporate.platformAccent", chipColors[i]);
                box.setOpaque(true);
                box.setBackground(mix(Color.WHITE, chipColors[i], 0.075));
                box.setToolTipText("Άμεση εμφάνιση/απόκρυψη αγγελιών από " + box.getText()
                        + " & επιλογή για επόμενη αναζήτηση");
                box.addActionListener(e -> {
                    updatePlatformCount(box);
                    applyFilters();
                });
                searchRow.add(box);
            }
            platformCount.setToolTipText("Πόσες πλατφόρμες είναι ενεργές");
            searchRow.add(platformCount);
            searchRow.add(uiLabel("   Σελίδες/πλατφόρμα:", false));
            searchRow.add(pagesBox);
            searchRow.add(loadDescBox);
            searchRow.add(settingsBtn);

            // ---- Φίλτρα (εφαρμόζονται άμεσα) ----
            JPanel filterRow1 = uiRow();
            filterRow1.add(uiLabel("Τιμή €", false));
            filterRow1.add(minPrice);
            filterRow1.add(new JLabel("–"));
            filterRow1.add(maxPrice);
            filterRow1.add(uiLabel("   Συσκευή", false));
            filterRow1.add(laptopFilter);
            filterRow1.add(uiLabel("   Κατάσταση", false));
            filterRow1.add(conditionFilter);
            filterRow1.add(uiLabel("   Συναλλαγή", false));
            filterRow1.add(transactionFilter);

            JPanel filterRow2 = uiRow();
            filterRow2.add(uiLabel("Να περιέχει", false));
            filterRow2.add(includeWords);
            filterRow2.add(uiLabel("   Αποκλεισμός λέξεων", false));
            filterRow2.add(excludeWords);
            restoreHiddenBtn.setEnabled(false);
            restoreHiddenBtn.setToolTipText("Επαναφορά όλων των κρυμμένων αγγελιών για την τρέχουσα αναζήτηση");
            restoreHiddenBtn.addActionListener(e -> restoreHiddenForCurrentSearch());
            filterRow2.add(restoreHiddenBtn);

            // ---- Ρυθμίσεις Facebook (κρυφές μέχρι να πατηθεί το ⚙) ----
            JPanel settingsPanel = uiRow();
            settingsPanel.add(uiLabel("Πόλεις Facebook (όνομα ή ID, με κόμμα)", false));
            settingsPanel.add(fbPlacesBox);
            settingsPanel.add(uiLabel("Ακτίνα km", false));
            settingsPanel.add(fbRadiusBox);
            settingsPanel.add(uiLabel("Αναζητήσεις FB", false));
            settingsPanel.add(fbBudgetBox);
            settingsPanel.add(uiLabel("Cookie FB", false));
            settingsPanel.add(fbCookieBox);
            settingsPanel.add(fbOpenBtn);
            JPanel settingsCard = uiCard("Ρυθμίσεις Facebook", settingsPanel);
            settingsCard.setVisible(false);
            settingsBtn.addActionListener(e -> {
                settingsCard.setVisible(settingsBtn.isSelected());
                settingsCard.getParent().revalidate();
            });

            keyword.setToolTipText("Όρος αναζήτησης — ο ίδιος όρος σε όλες τις επιλεγμένες πλατφόρμες.");
            pagesBox.setToolTipText("Πόσες σελίδες αποτελεσμάτων διαβάζονται ανά πλατφόρμα (παράλληλα). Insomnia έως 100, Vendora έως "
                    + VENDORA_MAX_PAGES + ", Vinted έως " + VINTED_MAX_PAGES + ".");
            loadDescBox.setToolTipText("Μετά την αναζήτηση ανοίγει κάθε αγγελία (Insomnia, Vendora, Vinted, Facebook) και φορτώνει "
                    + "την περιγραφή και τα specs. Ο πίνακας ενημερώνεται καθώς φορτώνουν.");
            fbPlacesBox.setToolTipText("Όνομα πόλης (π.χ. Αθήνα), ID πόλης Marketplace ή URL. Πολλές πόλεις χωρισμένες με κόμμα. "
                    + "Κενό = προεπιλεγμένη πόλη.");
            fbRadiusBox.setToolTipText("Προαιρετικά: ακτίνα αναζήτησης σε km. Κενό = χωρίς παράμετρο (όπως στο tab Facebook).");
            fbBudgetBox.setToolTipText("Μέγιστος αριθμός αναζητήσεων στο Facebook. Κάθε αναζήτηση δίνει ~24 αγγελίες· "
                    + "σπάει σε ζώνες τιμής και ταξινομήσεις. Ρυθμός ~1 αίτημα/δευτερόλεπτο για να μη μπλοκάρει.");
            fbCookieBox.setToolTipText("Προαιρετικό: cookie συνεδρίας Facebook (name=value; name2=value2) όταν το Facebook ζητά σύνδεση.");
            laptopFilter.setToolTipText("Όλες οι αγγελίες, μόνο laptop ή μόνο PC χωρίς laptop/notebook.");
            includeWords.setToolTipText("Λέξεις χωρισμένες με κόμμα. Πρέπει να υπάρχουν ΟΛΕΣ στο κείμενο της αγγελίας.");
            excludeWords.setToolTipText("Λέξεις χωρισμένες με κόμμα. Αν υπάρχει ΕΣΤΩ ΜΙΑ, η αγγελία αποκλείεται.");

            JPanel controls = new JPanel();
            controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
            controls.setBorder(BorderFactory.createEmptyBorder(6, 8, 2, 8));
            termRow.setAlignmentX(Component.LEFT_ALIGNMENT);
            controls.add(termRow);
            controls.add(uiCard("Πλατφόρμες (αναζήτηση & άμεσο φίλτρο)", searchRow));
            controls.add(uiCard("Φίλτρα (άμεσα)", filterRow1, filterRow2));
            controls.add(settingsCard);

            status.setBorder(BorderFactory.createEmptyBorder(2, 10, 8, 10));

            JPanel north = new JPanel(new BorderLayout());
            north.add(banner("ΟΛΕΣ ΟΙ ΑΓΟΡΕΣ · Ενιαία αναζήτηση", ALL_MARKETS_COLOR), BorderLayout.NORTH);
            north.add(controls, BorderLayout.CENTER);
            north.add(status, BorderLayout.SOUTH);
            add(north, BorderLayout.NORTH);

            table.setRowSorter(sorter);
            table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
            sorter.setSortable(AllMarketsModel.COL_HIDE, false);
            sorter.setComparator(AllMarketsModel.COL_PRICE, (Comparator<Double>) (a, b) ->
                    Double.compare(a == null ? Double.MAX_VALUE : a, b == null ? Double.MAX_VALUE : b));
            sorter.setSortKeys(Collections.singletonList(new RowSorter.SortKey(AllMarketsModel.COL_PRICE, SortOrder.ASCENDING)));
            int[] widths = {
                    68, 95, 260, 340, 80,
                    110, 130, 140, 100, 100, 160, 160, 180,
                    140, 120, 320
            };
            for (int i = 0; i < widths.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
            table.setRowHeight(25);
            table.setFillsViewportHeight(true);
            styleHeader(table, ALL_MARKETS_COLOR, INK);

            preview.setEditable(false);
            preview.setLineWrap(true);
            preview.setWrapStyleWord(true);
            JPanel bottom = new JPanel(new BorderLayout(6, 4));
            bottom.setBorder(BorderFactory.createTitledBorder("Γρήγορη προεπισκόπηση"));
            bottom.add(previewTitle, BorderLayout.NORTH);
            bottom.add(new JScrollPane(preview), BorderLayout.CENTER);
            bottom.add(openListing, BorderLayout.SOUTH);
            openListing.setEnabled(false);
            openListing.addActionListener(e -> {
                if (selectedRow != null) openUrl(selectedRow.listing.url);
            });

            table.getSelectionModel().addListSelectionListener(e -> {
                if (e.getValueIsAdjusting()) return;
                int r = table.getSelectedRow();
                selectedRow = r < 0 ? null : model.getRow(table.convertRowIndexToModel(r));
                if (selectedRow == null) {
                    previewTitle.setText("Επίλεξε αγγελία για γρήγορη προεπισκόπηση");
                    preview.setText("");
                    openListing.setEnabled(false);
                } else {
                    Listing l = selectedRow.listing;
                    previewTitle.setText("[" + selectedRow.platform + "] " + l.title
                            + "  ·  " + (l.price == null ? "Χωρίς τιμή" : l.price + " €"));
                    previewHasDetails = l.detailsLoaded;
                    preview.setText(buildListingPreview(selectedRow));
                    preview.setCaretPosition(0);
                    openListing.setEnabled(true);
                }
            });
            table.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    int viewRow = table.rowAtPoint(e.getPoint());
                    int viewCol = table.columnAtPoint(e.getPoint());
                    if (viewRow < 0 || viewCol < 0) return;

                    int modelCol = table.convertColumnIndexToModel(viewCol);
                    AllMarketRow clickedRow = model.getRow(table.convertRowIndexToModel(viewRow));
                    if (clickedRow == null) return;

                    if (modelCol == AllMarketsModel.COL_HIDE) {
                        hideListingForCurrentSearch(clickedRow);
                        return;
                    }

                    if (e.getClickCount() == 2) {
                        openUrl(clickedRow.listing.url);
                    }
                }
            });
            table.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    int viewRow = table.rowAtPoint(e.getPoint());
                    int viewCol = table.columnAtPoint(e.getPoint());
                    boolean isEye = viewRow >= 0 && viewCol >= 0
                            && table.convertColumnIndexToModel(viewCol) == AllMarketsModel.COL_HIDE;
                    table.setCursor(Cursor.getPredefinedCursor(isEye ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
                }
            });

            JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(table), bottom);
            bottom.setMinimumSize(new Dimension(0, 145));
            split.setResizeWeight(0.78);
            split.setContinuousLayout(true);
            split.setOneTouchExpandable(true);
            add(split, BorderLayout.CENTER);

            search.addActionListener(e -> run());
            keyword.addActionListener(e -> run());
            fbOpenBtn.addActionListener(e -> {
                String term = keyword.getText().trim();
                String first = fbPlacesBox.getText().split("[,;\\n\\r]+")[0];
                openUrl(FacebookTools.searchUrl(first, term.isEmpty() ? "rtx" : term, new ArrayList<>()));
            });

            java.awt.event.ActionListener filterAction = e -> applyFilters();
            minPrice.addActionListener(filterAction);
            maxPrice.addActionListener(filterAction);
            conditionFilter.addActionListener(filterAction);
            laptopFilter.addActionListener(filterAction);
            transactionFilter.addActionListener(filterAction);
            includeWords.addActionListener(filterAction);
            excludeWords.addActionListener(filterAction);
            javax.swing.event.DocumentListener liveFilter = new javax.swing.event.DocumentListener() {
                public void insertUpdate(javax.swing.event.DocumentEvent e) { applyFilters(); }
                public void removeUpdate(javax.swing.event.DocumentEvent e) { applyFilters(); }
                public void changedUpdate(javax.swing.event.DocumentEvent e) { applyFilters(); }
            };
            minPrice.getDocument().addDocumentListener(liveFilter);
            maxPrice.getDocument().addDocumentListener(liveFilter);
            includeWords.getDocument().addDocumentListener(liveFilter);
            excludeWords.getDocument().addDocumentListener(liveFilter);
        }

        // ---------------- Αποθήκευση κρυμμένων αγγελιών ανά αναζήτηση ----------------

        private String searchKeyOf(String term) {
            return normalize(term).replaceAll("\\s+", " ").trim();
        }

        private String hiddenKeyOf(Listing l) {
            return removeQueryAndFragment(l.url == null ? "" : l.url.trim());
        }

        private void loadHiddenStore() {
            if (!hiddenStoreFile.isFile()) return;
            try (java.io.FileInputStream in = new java.io.FileInputStream(hiddenStoreFile)) {
                hiddenStore.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
            } catch (IOException ignored) {
            }
        }

        private void saveHiddenStore() {
            if (currentSearchKey.isEmpty()) return;
            synchronized (hiddenStore) {
                if (hiddenUrlsForCurrentSearch.isEmpty()) {
                    hiddenStore.remove(currentSearchKey);
                } else {
                    hiddenStore.setProperty(currentSearchKey, String.join("\n", hiddenUrlsForCurrentSearch));
                }
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(hiddenStoreFile)) {
                    hiddenStore.store(new OutputStreamWriter(out, StandardCharsets.UTF_8),
                            "Hidden listings per search in All Markets tab");
                } catch (IOException ignored) {
                }
            }
        }

        private void activateSearchHiddenSet(String term) {
            currentSearchKey = searchKeyOf(term);
            hiddenUrlsForCurrentSearch.clear();
            String saved = hiddenStore.getProperty(currentSearchKey, "");
            for (String line : saved.split("\\R")) {
                if (!line.isBlank()) hiddenUrlsForCurrentSearch.add(line.trim());
            }
            updateRestoreHiddenButton();
        }

        private void hideListingForCurrentSearch(AllMarketRow row) {
            if (row == null) return;
            String urlKey = hiddenKeyOf(row.listing);
            if (urlKey.isEmpty()) return;
            hiddenUrlsForCurrentSearch.add(urlKey);
            saveHiddenStore();
            updateRestoreHiddenButton();
            if (selectedRow == row) {
                table.clearSelection();
            }
            applyFilters();
        }

        private void restoreHiddenForCurrentSearch() {
            if (hiddenUrlsForCurrentSearch.isEmpty()) return;
            hiddenUrlsForCurrentSearch.clear();
            saveHiddenStore();
            updateRestoreHiddenButton();
            applyFilters();
        }

        private void updateRestoreHiddenButton() {
            int count = hiddenUrlsForCurrentSearch.size();
            restoreHiddenBtn.setText("Εμφάνιση κρυμμένων (" + count + ")");
            restoreHiddenBtn.setEnabled(count > 0);
        }

        private JLabel uiLabel(String text, boolean bold) {
            JLabel l = new JLabel(text);
            l.setForeground(new Color(70, 70, 85));
            if (bold) l.setFont(l.getFont().deriveFont(Font.BOLD));
            return l;
        }

        private JPanel uiRow() {
            return new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        }

        private JPanel uiCard(String title, JComponent... rows) {
            JPanel p = new JPanel();
            p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
            javax.swing.border.TitledBorder tb = BorderFactory.createTitledBorder(
                    BorderFactory.createLineBorder(new Color(205, 205, 215)), title);
            tb.setTitleFont(tb.getTitleFont().deriveFont(Font.BOLD, 11f));
            p.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createEmptyBorder(0, 0, 4, 0), tb));
            for (JComponent r : rows) {
                r.setAlignmentX(Component.LEFT_ALIGNMENT);
                p.add(r);
            }
            p.setAlignmentX(Component.LEFT_ALIGNMENT);
            return p;
        }

        private int selectedPlatforms() {
            int n = 0;
            for (JCheckBox b : platformBoxes) if (b.isSelected()) n++;
            return n;
        }

        // Τουλάχιστον μία πλατφόρμα πρέπει να μένει επιλεγμένη.
        private void updatePlatformCount(JCheckBox changed) {
            if (selectedPlatforms() == 0) changed.setSelected(true);
            platformCount.setText(selectedPlatforms() + "/" + platformBoxes.length);
        }

        private boolean isPlatformVisible(String platform) {
            if (platform == null) return true;
            for (JCheckBox box : platformBoxes) {
                if (box.getText().equalsIgnoreCase(platform.trim())) {
                    return box.isSelected();
                }
            }
            return true;
        }

        // Ενημερώνει τον πίνακα (το πολύ κάθε ~0,7 s) καθώς φορτώνουν οι περιγραφές.
        private void requestRefresh() {
            if (!refreshPending.compareAndSet(false, true)) return;
            SwingUtilities.invokeLater(() -> {
                javax.swing.Timer t = new javax.swing.Timer(700, e -> {
                    refreshPending.set(false);
                    model.refreshAll();
                    if (selectedRow != null && !previewHasDetails && selectedRow.listing.detailsLoaded) {
                        previewHasDetails = true;
                        preview.setText(buildListingPreview(selectedRow));
                        preview.setCaretPosition(0);
                    }
                });
                t.setRepeats(false);
                t.start();
            });
        }

        // Ανοίγει κάθε αγγελία της πλατφόρμας και φορτώνει περιγραφή + specs (μόνο όσες είναι στο εύρος τιμής).
        private void loadDetails(int gen, String platform, List<Listing> listings, Double min, Double max,
                                 int threads, DetailJob job) throws InterruptedException {
            List<Listing> targets = new ArrayList<>();

            for (Listing l : listings) {
                if (!l.detailsLoaded && inRange(l, min, max)) targets.add(l);
            }

            if (targets.isEmpty()) return;

            final int total = targets.size();
            final AtomicInteger done = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(threads, total)));

            try {
                List<Callable<Void>> tasks = new ArrayList<>();

                for (Listing l : targets) {
                    tasks.add(() -> {
                        if (gen == generation.get() && !Thread.currentThread().isInterrupted()) {
                            try {
                                job.load(l);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                            } catch (Exception ignored) {
                            }
                        }

                        progressFor(platform).accept(platform + ": περιγραφές " + done.incrementAndGet() + " / " + total);
                        requestRefresh();
                        return null;
                    });
                }

                pool.invokeAll(tasks);
            } finally {
                pool.shutdownNow();
            }
        }

        private String buildListingPreview(AllMarketRow row) {
            Listing l = row.listing;
            StringBuilder out = new StringBuilder();
            out.append("ΠΛΗΡΟΦΟΡΙΕΣ ΑΓΓΕΛΙΑΣ\n");
            out.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
            appendPreviewLine(out, "Πλατφόρμα", row.platform);
            appendPreviewLine(out, "Τίτλος", l.title);
            appendPreviewLine(out, "Τιμή", l.price == null ? "Δεν αναφέρεται" : l.price + " €");
            appendPreviewLine(out, "Τοποθεσία", AllMarketsModel.locationOf(l));
            appendPreviewLine(out, "Πωλητής", l.seller);
            appendPreviewLine(out, "Αναζητήθηκε/Αναρτήθηκε", l.posted);
            appendPreviewLine(out, "Κατηγορία", l.category);
            appendPreviewLine(out, "Τύπος συσκευής", l.laptop ? "Laptop" : "PC / μη laptop");
            appendPreviewLine(out, "URL", l.url);

            Specs specs = l.specs;
            if (specs != null) {
                out.append("\nΤΕΧΝΙΚΑ ΧΑΡΑΚΤΗΡΙΣΤΙΚΑ\n");
                out.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
                appendPreviewLine(out, "CPU", specs.cpu);
                appendPreviewLine(out, "GPU", specs.gpu);
                appendPreviewLine(out, "RAM", specs.ram);
                appendPreviewLine(out, "SSD", specs.ssd);
                appendPreviewLine(out, "HDD", specs.hdd);
                appendPreviewLine(out, "Μητρική", specs.mobo);
                appendPreviewLine(out, "Τροφοδοτικό", specs.psu);
                appendPreviewLine(out, "Επιπλέον", specs.extras);
            }

            String description = l.fullDescription != null && !l.fullDescription.isBlank()
                    ? l.fullDescription : l.description;
            out.append("\nΠΕΡΙΓΡΑΦΗ ΑΓΓΕΛΙΑΣ\n");
            out.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
            if (description == null || description.isBlank()) {
                out.append("Δεν έχει ληφθεί περιγραφή από την πλατφόρμα (άνοιξε την αγγελία για πλήρη κείμενο).\n");
            } else {
                out.append(description.trim()).append('\n');
            }
            return out.toString();
        }

        private void appendPreviewLine(StringBuilder out, String label, String value) {
            if (value != null && !value.isBlank()) {
                out.append(label).append(": ").append(value.trim()).append('\n');
            }
        }

        // Pattern που ταιριάζει τον όρο αναζήτησης ανεξάρτητα από κενά/παύλες ("GTX 1080", "GTX1080", "gtx-1080").
        private Pattern buildTermPattern(String term) {
            StringBuilder re = new StringBuilder("(?<![\\p{L}\\p{N}])");
            boolean first = true;
            for (String token : normalize(term).split("[^\\p{L}\\p{N}]+")) {
                if (token.isBlank()) continue;
                if (!first) re.append("[^\\p{L}\\p{N}]*");
                re.append(Pattern.quote(token));
                first = false;
            }
            return first ? null : Pattern.compile(re.toString());
        }

        private boolean containsSearchedTerm(Listing l, Pattern termPattern) {
            if (termPattern == null) return true;
            String content = normalize(l.title + " " + l.description + " " + l.fullDescription
                    + " " + l.origTitle + " " + l.origDescription);
            return termPattern.matcher(content).find();
        }

        private void applyFilters() {
            final Double min = parseOptionalPrice(minPrice.getText());
            final Double max = parseOptionalPrice(maxPrice.getText());
            final Pattern termPattern = searchedTermPattern;
            final boolean showInsomnia = platformBoxes[0].isSelected();
            final boolean showVendora = platformBoxes[1].isSelected();
            final boolean showVinted = platformBoxes[2].isSelected();
            final boolean showFacebook = platformBoxes[3].isSelected();
            final String condition = (String) conditionFilter.getSelectedItem();
            final String device = (String) laptopFilter.getSelectedItem();
            final String transaction = (String) transactionFilter.getSelectedItem();
            final List<String> included = new ArrayList<>();
            final List<String> excluded = new ArrayList<>();
            for (String x : includeWords.getText().split("[,;\\n\\r]+")) {
                if (!normalize(x.trim()).isBlank()) included.add(normalize(x.trim()));
            }
            for (String x : excludeWords.getText().split("[,;\\n\\r]+")) {
                if (!normalize(x.trim()).isBlank()) excluded.add(normalize(x.trim()));
            }

            sorter.setRowFilter(new RowFilter<AllMarketsModel, Integer>() {
                @Override
                public boolean include(Entry<? extends AllMarketsModel, ? extends Integer> e) {
                    AllMarketRow row = model.getRow(e.getIdentifier());
                    if (row == null) return false;
                    if ("Insomnia".equals(row.platform) && !showInsomnia) return false;
                    if ("Vendora".equals(row.platform) && !showVendora) return false;
                    if ("Vinted".equals(row.platform) && !showVinted) return false;
                    if ("Facebook".equals(row.platform) && !showFacebook) return false;

                    Listing l = row.listing;
                    if (isDamagedListing(l)) return false;
                    if (hiddenUrlsForCurrentSearch.contains(hiddenKeyOf(l))) return false;
                    if (!containsSearchedTerm(l, termPattern)) return false;
                    if (min != null && (l.price == null || l.price < min)) return false;
                    if (max != null && (l.price == null || l.price > max)) return false;
                    if (!"Όλες".equals(condition) && l.condition != null
                            && !l.condition.isBlank() && !l.condition.equalsIgnoreCase(condition)) return false;
                    if (!"Όλες".equals(transaction)
                            && (l.transaction == null || !l.transaction.equalsIgnoreCase(transaction))) return false;
                    String text = normalize(l.title + " " + l.description + " " + l.fullDescription + " " + l.seller
                            + " " + l.category + " " + l.condition + " " + l.transaction + " " + l.url + " "
                            + AllMarketsModel.locationOf(l) + " "
                            + l.specs.cpu + " " + l.specs.ram + " " + l.specs.gpu + " " + l.specs.ssd + " "
                            + l.specs.hdd + " " + l.specs.mobo + " " + l.specs.psu + " " + l.specs.extras);
                    boolean isLaptopListing = l.laptop || isLaptopLoose(l.title) || isLaptop(text);
                    if ("Μόνο Laptop".equals(device) && !isLaptopListing) return false;
                    if ("Μόνο PC (χωρίς laptop)".equals(device) && isLaptopListing) return false;
                    for (String x : included) if (!text.contains(x)) return false;
                    for (String x : excluded) if (text.contains(x)) return false;
                    return true;
                }
            });
            int hiddenCount = hiddenUrlsForCurrentSearch.size();
            status.setText("Εμφανίζονται " + table.getRowCount() + " από " + model.getRowCount()
                    + " αγγελίες" + (hiddenCount > 0 ? " (" + hiddenCount + " κρυμμένες)" : "")
                    + " · μόνο όσες περιέχουν τον όρο αναζήτησης · τιμή αύξουσα.");
        }

        private int parseIntOr(String text, int fallback) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ex) {
                return fallback;
            }
        }

        private java.util.function.Consumer<String> progressFor(String platform) {
            return msg -> {
                progressMap.put(platform, msg);
                renderProgress();
            };
        }

        private void renderProgress() {
            final String text = String.join("  ·  ", new TreeMap<>(progressMap).values());
            SwingUtilities.invokeLater(() -> status.setText(text));
        }

        // Προσθέτει στον πίνακα (στο EDT) μόνο όσες αγγελίες δεν έχουν ήδη εμφανιστεί.
        private void publishRows(int gen, String platform, List<Listing> listings) {
            if (listings.isEmpty()) return;
            final List<Listing> copy = new ArrayList<>(listings);
            SwingUtilities.invokeLater(() -> {
                if (gen != generation.get()) return;
                List<AllMarketRow> fresh = new ArrayList<>();
                for (Listing l : copy) {
                    if (shownKeys.add(platform + "|" + removeQueryAndFragment(l.url))) {
                        fresh.add(new AllMarketRow(l, platform));
                    }
                }
                model.addRows(fresh);
                applyFilters();
            });
        }

        private PlatformBatch safe(String platform, Callable<PlatformBatch> job) {
            try {
                return job.call();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return new PlatformBatch(platform, Collections.<Listing>emptyList(), "ακυρώθηκε");
            } catch (Exception ex) {
                return new PlatformBatch(platform, Collections.<Listing>emptyList(),
                        rootMessage(ex).replace('\n', ' '));
            }
        }

        private void run() {
            setVintedEnglish(false);
            resetTranslator();
            final String term = keyword.getText().trim();
            if (term.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Γράψε έναν όρο αναζήτησης.");
                return;
            }
            if (worker != null && !worker.isDone()) worker.cancel(true);

            activateSearchHiddenSet(term);
            searchedTermPattern = buildTermPattern(term);
            final int gen = generation.incrementAndGet();
            final int maxPages = Math.max(1, Math.min(parseIntOr(pagesBox.getText(), 30), 200));
            final int budget = Math.max(1, Math.min(parseIntOr(fbBudgetBox.getText(), 40), 200));
            final boolean doInsomnia = platformBoxes[0].isSelected();
            final boolean doVendora = platformBoxes[1].isSelected();
            final boolean doVinted = platformBoxes[2].isSelected();
            final boolean withFacebook = platformBoxes[3].isSelected();
            final int selected = selectedPlatforms();
            final boolean details = loadDescBox.isSelected();
            final String places = fbPlacesBox.getText().trim();
            final Double min = parseOptionalPrice(minPrice.getText());
            final Double max = parseOptionalPrice(maxPrice.getText());

            final List<String> fbParams = new ArrayList<>();
            final List<String> vintedFilters = new ArrayList<>();
            String radius = fbRadiusBox.getText().trim();
            if (radius.matches("\\d{1,4}")) fbParams.add("radius=" + radius);
            if (min != null) {
                fbParams.add("minPrice=" + VintedTools.plainNumber(min));
                vintedFilters.add("price_from=" + VintedTools.plainNumber(min));
            }
            if (max != null) {
                fbParams.add("maxPrice=" + VintedTools.plainNumber(max));
                vintedFilters.add("price_to=" + VintedTools.plainNumber(max));
            }
            if (min != null || max != null) vintedFilters.add("currency=EUR");
            facebookCookie = fbCookieBox.getText().trim();

            shownKeys.clear();
            progressMap.clear();
            model.setRows(Collections.emptyList());
            search.setEnabled(false);
            status.setText("Αναζήτηση σε " + selected + " πλατφόρμ" + (selected == 1 ? "α" : "ες") + "...");
            final long started = System.currentTimeMillis();

            worker = new SwingWorker<Integer, Void>() {
                private final List<String> notes = Collections.synchronizedList(new ArrayList<>());

                @Override
                protected Integer doInBackground() throws Exception {
                    ExecutorService markets = Executors.newFixedThreadPool(Math.max(1, selected));
                    ExecutorCompletionService<PlatformBatch> cs = new ExecutorCompletionService<>(markets);
                    int submitted = 0;

                    try {
                        if (doInsomnia) {
                            cs.submit(() -> safe("Insomnia", () -> {
                                List<Listing> found = searchInsomniaAll(term, Math.min(maxPages, 100),
                                        progressFor("Insomnia"), rows -> publishRows(gen, "Insomnia", rows));
                                publishRows(gen, "Insomnia", found);
                                if (details) loadDetails(gen, "Insomnia", found, min, max, THREADS,
                                        l -> fetchFullDescription(l));
                                return new PlatformBatch("Insomnia", found, "");
                            }));
                            submitted++;
                        }

                        if (doVendora) {
                            cs.submit(() -> safe("Vendora", () -> {
                                List<Listing> found = searchVendoraAll(removeQueryAndFragment(VENDORA_GENERAL_URL), term,
                                        "Vendora", Math.min(maxPages, VENDORA_MAX_PAGES), progressFor("Vendora"),
                                        rows -> publishRows(gen, "Vendora", rows));
                                publishRows(gen, "Vendora", found);
                                if (details) loadDetails(gen, "Vendora", found, min, max, 3,
                                        l -> fetchVendoraDetails(l));
                                return new PlatformBatch("Vendora", found, "");
                            }));
                            submitted++;
                        }

                        if (doVinted) {
                            cs.submit(() -> safe("Vinted", () -> {
                                List<Listing> found = searchVintedAll(term, vintedFilters,
                                        Math.min(maxPages, VINTED_MAX_PAGES), "Vinted", progressFor("Vinted"),
                                        rows -> publishRows(gen, "Vinted", rows));
                                publishRows(gen, "Vinted", found);
                                if (details) loadDetails(gen, "Vinted", found, min, max, VINTED_THREADS, l -> {
                                    fetchVintedDetails(l);
                                    vintedTranslate(l);
                                });
                                return new PlatformBatch("Vinted", found, "");
                            }));
                            submitted++;
                        }

                        if (withFacebook) {
                            cs.submit(() -> safe("Facebook", () -> {
                                PlatformBatch fb = facebookWideSearch(places, term, fbParams, budget,
                                        progressFor("Facebook"), rows -> publishRows(gen, "Facebook", rows), true);
                                publishRows(gen, "Facebook", fb.listings);
                                if (details) loadDetails(gen, "Facebook", fb.listings, min, max, 2,
                                        l -> fetchFacebookDetails(l));
                                return fb;
                            }));
                            submitted++;
                        }

                        for (int i = 0; i < submitted; i++) {
                            PlatformBatch b = cs.take().get();
                            notes.add(b.platform + ": " + b.listings.size()
                                    + (b.note.isEmpty() ? "" : " (" + b.note + ")"));
                            progressFor(b.platform).accept(b.platform + ": " + b.listings.size() + " ✓");
                        }
                    } finally {
                        markets.shutdownNow();
                    }
                    return submitted;
                }

                @Override
                protected void done() {
                    search.setEnabled(true);
                    if (isCancelled() || gen != generation.get()) return;
                    applyFilters();
                    long secs = Math.max(1, (System.currentTimeMillis() - started) / 1000);
                    int hiddenCount = hiddenUrlsForCurrentSearch.size();
                    status.setText("Εμφανίζονται " + table.getRowCount() + " από " + model.getRowCount()
                            + (hiddenCount > 0 ? " (" + hiddenCount + " κρυμμένες)" : "")
                            + " (" + secs + "s)  ·  " + String.join("  |  ", notes));
                }
            };
            worker.execute();
        }
    }
    // =========================================================
    // FACEBOOK MARKETPLACE — λήψη σελίδων (tabs 7 και 8)
    // =========================================================

    private static final String FACEBOOK_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36";
    // Το Facebook μπλοκάρει εύκολα· ένα αίτημα ανά ~1,2 δευτερόλεπτο συνολικά.
    private static final long FACEBOOK_REQUEST_SPACING_MS = 1200L;
    // Cookie συνεδρίας (προαιρετικό): κοινό για τα tabs 7 και 8.
    private static volatile String facebookCookie = "";
    private final Object facebookGate = new Object();
    private long facebookNextRequestNanos = 0L;

    private void facebookPace() throws InterruptedException {
        long wait;
        synchronized (facebookGate) {
            long now = System.nanoTime();
            long at = Math.max(now, facebookNextRequestNanos);
            facebookNextRequestNanos = at + FACEBOOK_REQUEST_SPACING_MS * 1_000_000L;
            wait = at - now;
        }
        if (wait > 0) {
            Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L));
        }
    }

    // «name=value; name2=value2» -> map για το jsoup.
    private Map<String, String> facebookCookies() {
        Map<String, String> out = new LinkedHashMap<>();
        for (String part : facebookCookie.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0) out.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
        }
        return out;
    }

    private String facebookFetch(String url) throws Exception {
        IOException last = null;

        for (int attempt = 0; attempt <= 2; attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }

            facebookPace();
            org.jsoup.Connection.Response r = Jsoup.connect(url)
                    .userAgent(FACEBOOK_UA)
                    .header("Accept-Language", "el-GR,el;q=0.9,en;q=0.8")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Upgrade-Insecure-Requests", "1")
                    .header("Sec-Fetch-Dest", "document")
                    .header("Sec-Fetch-Mode", "navigate")
                    .header("Sec-Fetch-Site", "none")
                    .header("Sec-Fetch-User", "?1")
                    .referrer(FacebookTools.BASE + "/")
                    .cookies(facebookCookies())
                    .timeout(TIMEOUT_MS)
                    .maxBodySize(0)
                    .ignoreHttpErrors(true)
                    .ignoreContentType(true)
                    .execute();
            int status = r.statusCode();

            if (status == 429 || status == 503) {
                last = new IOException("HTTP " + status + " (το Facebook περιορίζει τα αιτήματα): " + url);
                Thread.sleep(2500L * (attempt + 1));
                continue;
            }

            if (status >= 400) {
                throw new IOException("HTTP " + status + " — το Facebook δεν έδωσε τη σελίδα:\n" + url
                        + "\nΣυνήθως ζητά σύνδεση: βάλε Cookie ή χρησιμοποίησε «Άνοιγμα αποθηκευμένης σελίδας».");
            }

            return r.body();
        }

        throw last != null ? last : new IOException("Αποτυχία λήψης: " + url);
    }

    private Listing facebookListing(FacebookTools.Item it, String categoryName) {
        Listing l = new Listing(
                it.title,
                it.price,
                SpecParser.parse(it.title, ""),
                it.seller,
                it.condition,
                "Πώληση",
                categoryName,
                it.url,
                isLaptopLoose(it.title));

        l.location = it.city;
        l.createdAt = it.created;
        l.posted = FacebookTools.formatTime(it.created);
        l.delivery = it.delivery;
        l.sold = it.sold;
        l.conditionCode = it.conditionCode;
        return l;
    }

    private List<Listing> facebookListings(String html, String categoryName) {
        List<Listing> out = new ArrayList<>();

        for (FacebookTools.Item it : FacebookTools.parseSearch(html)) {
            out.add(facebookListing(it, categoryName));
        }

        return out;
    }

    // Γεμίζει μια αγγελία από τη σελίδα της (περιγραφή, κατάσταση, τοποθεσία, ώρα ανάρτησης).
    private Double applyFacebookDetails(Listing l, FacebookTools.Item d) {
        String body = d.description.trim();

        l.fullDescription = body;

        if (!body.isBlank()) {
            l.description = body;
        }

        if (!d.title.isBlank()) l.title = d.title;

        l.specs = SpecParser.parse(l.title, body);

        if (!d.condition.isBlank()) {
            l.condition = d.condition;
            l.conditionCode = d.conditionCode;
        }

        if (!d.city.isBlank() && l.location.isEmpty()) l.location = d.city;

        if (d.created > 0) {
            l.createdAt = d.created;
            l.posted = FacebookTools.formatTime(d.created);
        }

        if (!d.delivery.isBlank()) l.delivery = d.delivery;
        if (!d.seller.isBlank()) l.seller = d.seller;
        l.sold = d.sold;

        if (!l.laptop && isLaptopLoose(l.title + " " + body)) {
            l.laptop = true;
        }

        l.flaggedKeywords = SpecParser.hasSuspiciousKeywords(l.title, body);
        l.detailsLoaded = true;
        return d.price;
    }

    private Double fetchFacebookDetails(Listing l) throws Exception {
        String html = facebookFetch(l.url);
        FacebookTools.Item d = FacebookTools.parseDetail(html);

        if (d == null) {
            throw new IOException("Δεν βρέθηκαν δεδομένα αγγελίας (σύνδεση στο Facebook ή αλλαγή δομής): " + l.url);
        }

        return applyFacebookDetails(l, d);
    }

    private static final String FACEBOOK_BLOCKED_HINT =
            "Δεν βρέθηκαν αγγελίες από την ανώνυμη αναζήτηση. Το Facebook μπορεί να ζήτησε σύνδεση ή να περιόρισε τα αιτήματα.\n"
                    + "Το Cookie είναι προαιρετικό — συμπλήρωσέ το μόνο αν το Facebook δεν επιστρέφει αποτελέσματα χωρίς σύνδεση.\n"
                    + "Εναλλακτικά, άνοιξε τη σελίδα στον browser, αποθήκευσέ την (Ctrl+S) και φόρτωσέ την με "
                    + "«Άνοιγμα αποθηκευμένης σελίδας».";

    private static class FacebookTableModel extends AbstractTableModel {
        static final int COL_PRIORITY = 19;
        static final int COL_LOCATION = 3;
        static final int COL_POSTED = 16;

        private final String[] columns = {
                "Τίτλος",
                "Περιγραφή",
                "Τιμή (€)",
                "Τοποθεσία",
                "CPU",
                "RAM",
                "GPU",
                "SSD",
                "HDD",
                "Μητρική",
                "PSU",
                "Έξτρα",
                "Πωλητής",
                "Κατάσταση",
                "Παράδοση",
                "Διαθεσιμότητα",
                "Αναρτήθηκε",
                "Κατηγορία",
                "URL αγγελίας",
                "_priority"
        };

        private List<Listing> listings = new ArrayList<>();

        void setListings(List<Listing> values) {
            listings = new ArrayList<>(values);
            fireTableDataChanged();
        }

        void addListings(List<Listing> values) {
            if (values == null || values.isEmpty()) return;
            Set<String> seen = new HashSet<>();
            for (Listing existing : listings) seen.add(existing.url);
            int first = listings.size();
            for (Listing value : values) {
                if (value != null && seen.add(value.url)) listings.add(value);
            }
            if (listings.size() > first) fireTableRowsInserted(first, listings.size() - 1);
        }

        void refresh(Listing listing) {
            int index = listings.indexOf(listing);

            if (index >= 0) {
                fireTableRowsUpdated(index, index);
            }
        }

        List<Listing> all() {
            return listings;
        }

        Listing getListing(int row) {
            return row < 0 || row >= listings.size() ? null : listings.get(row);
        }

        @Override
        public int getRowCount() {
            return listings.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Class<?> getColumnClass(int column) {
            if (column == 2) return Double.class;
            if (column == COL_PRIORITY) return Integer.class;
            return String.class;
        }

        @Override
        public Object getValueAt(int row, int column) {
            Listing item = listings.get(row);
            Specs sp = item.specs;
            boolean flagged = item.flaggedKeywords || item.flaggedManySpecs;

            if (column == COL_PRIORITY) return flagged ? 1 : 0;

            // Ύποπτες/γενικές αγγελίες: κρύβουμε τα κουτιά αναγνώρισης εξαρτημάτων.
            if (item.flaggedKeywords && column >= 4 && column <= 11) return "";

            switch (column) {
                case 0: return item.title;
                case 1: return item.description;
                case 2: return item.price;
                case 3: return item.location.isEmpty() ? "—" : item.location;
                case 4: return sp.cpu;
                case 5: return sp.ram;
                case 6: return sp.gpu;
                case 7: return sp.ssd;
                case 8: return sp.hdd;
                case 9: return sp.mobo;
                case 10: return sp.psu;
                case 11: return sp.extras;
                case 12: return item.seller;
                case 13: return item.condition;
                case 14: return item.delivery;
                case 15: return item.sold ? "Πωλήθηκε" : "Διαθέσιμο";
                case 16: return item.posted;
                case 17: return item.category;
                case 18: return item.url;
                default: return "";
            }
        }
    }

    // =========================================================
    // TAB FACEBOOK MARKETPLACE — αναζήτηση με τα φίλτρα του Marketplace
    // =========================================================

    private class FacebookTab extends JPanel {
        private static final String ALL_LOCATIONS = "Όλες οι τοποθεσίες";
        private static final String UNKNOWN_LOCATION = "Άγνωστη τοποθεσία";

        // Φίλτρα όπως τα ορίζει το ίδιο το Marketplace (filter_key / value της σελίδας αναζήτησης).
        private final String[] conditionNames = {
                "Καινούριο", "Μεταχειρισμένο – σαν καινούριο", "Μεταχειρισμένο – καλή", "Μεταχειρισμένο – μέτρια"
        };
        private final String[] conditionCodes = {"new", "used_like_new", "used_good", "used_fair"};
        private final JCheckBox[] conditionBoxes = new JCheckBox[4];

        private final String[] daysLabels = {
                "Όλες οι ημερομηνίες", "Τελευταίες 24 ώρες", "Τελευταίες 7 ημέρες", "Τελευταίες 30 ημέρες"
        };
        private final String[] daysValues = {"", "1", "7", "30"};

        private final String[] availabilityLabels = {"Διαθέσιμα", "Πωλημένα", "Όλα"};
        // «Διαθέσιμα» είναι η προεπιλογή του Facebook, οπότε δεν στέλνεται παράμετρος.
        private final String[] availabilityValues = {"", "out%20of%20stock", ""};

        private final String[] deliveryLabels = {"Όλες", "Παραλαβή (local pick-up)", "Αποστολή (delivery)"};
        private final String[] deliveryValues = {"", "local_pick_up", "shipping"};

        private final String[] sellerLabels = {"Όλοι", "Προφίλ εμφανίζεται", "Προφίλ δεν εμφανίζεται"};

        // Προεπιλογή: ημερομηνία αύξουσα (παλαιότερα πρώτα). Το Facebook δεν έχει «παλαιότερα πρώτα»,
        // οπότε από τον server ζητάμε τα νεότερα και η σειρά αντιστρέφεται τοπικά στον πίνακα.
        private final String[] sortLabels = {
                "Ημερομηνία: αύξουσα (παλαιότερα πρώτα)",
                "Ημερομηνία: φθίνουσα (νεότερα πρώτα)",
                "Τιμή: αύξουσα",
                "Τιμή: φθίνουσα",
                "Τίτλος: Α-Ω",
                "Προτεινόμενα (σειρά Facebook)"
        };
        private final String[] sortParams = {
                "creation_time_descend", "creation_time_descend", "price_ascend", "price_descend", "", "best_match"
        };

        private final JTextField keywordBox = new JTextField("", 16);
        private final JTextField placeBox = new JTextField("", 14);
        private final JTextField minBox = new JTextField("10", 6);
        private final JTextField maxBox = new JTextField(6);
        private final JTextField radiusBox = new JTextField(4);
        private final JTextField excludeBox = new JTextField(24);
        private final JTextField cookieBox = new JTextField(30);
        private final JComboBox<String> daysBox = new JComboBox<>(daysLabels);
        private final JComboBox<String> availabilityBox = new JComboBox<>(availabilityLabels);
        private final JComboBox<String> deliveryBox = new JComboBox<>(deliveryLabels);
        private final JComboBox<String> sellerBox = new JComboBox<>(sellerLabels);
        private final JComboBox<String> deviceBox = new JComboBox<>(
                new String[]{"Όλα (PC + Laptop)", "Μόνο PC (χωρίς laptop)", "Μόνο Laptop"});
        private final JComboBox<String> sortBox = new JComboBox<>(sortLabels);
        private final JComboBox<String> locationBox = new JComboBox<>(new String[]{ALL_LOCATIONS});
        private boolean refreshingLocations;
        private boolean noLocationData;
        private volatile String lastCity = "";
        private final JCheckBox deepBox = new JCheckBox("Λεπτομέρειες αγγελίας (περιγραφή, κατάσταση, τοποθεσία)", true);
        private final JCheckBox exactBox = new JCheckBox("Μόνο αγγελίες που περιέχουν τον όρο", true);
        private final JCheckBox wideBox = new JCheckBox("Εκτεταμένη αναζήτηση (πολλές ζώνες τιμής)", true);
        private final JButton searchBtn = new JButton("Αναζήτηση στο Marketplace");
        private final JButton fileBtn = new JButton("Άνοιγμα αποθηκευμένης σελίδας...");
        private final JLabel status = new JLabel("Έτοιμο. Γράψε π.χ. rtx 3060 και πάτα Αναζήτηση.");

        private final JLabel detailTitle = new JLabel("Επίλεξε μια αγγελία για να δεις την περιγραφή της.");
        private final JTextArea detailArea = new JTextArea();
        private final JButton loadFullBtn = new JButton("Φόρτωση πλήρους περιγραφής");
        private final JButton openBtn = new JButton("Άνοιγμα στον browser");
        private volatile Listing shown;
        private volatile String lastTerm = "";

        private final FacebookTableModel model = new FacebookTableModel();
        private final TableRowSorter<FacebookTableModel> rowSorter = new TableRowSorter<>(model);
        private final JTable table = new JTable(model) {
            @Override
            public String getToolTipText(MouseEvent event) {
                int row = rowAtPoint(event.getPoint());
                int col = columnAtPoint(event.getPoint());
                if (row < 0 || col < 0) return null;
                Object value = getValueAt(row, col);
                if (value == null || value.toString().length() < 18) return null;
                String text = value.toString().replace("&", "&amp;").replace("<", "&lt;");
                return "<html><body style='width:380px'>" + text + "</body></html>";
            }

            @Override
            public Component prepareRenderer(javax.swing.table.TableCellRenderer renderer,
                                             int row, int column) {
                Component component = super.prepareRenderer(renderer, row, column);
                Listing listing = model.getListing(convertRowIndexToModel(row));
                if (!isRowSelected(row)) {
                    component.setBackground(getBackground());
                    component.setForeground(getForeground());
                }
                applyFlaggedRowBorder(component, this, isFlaggedListing(listing), row, column);
                return component;
            }
        };

        private SwingWorker<List<Listing>, Void> worker;
        private final AtomicInteger uiGeneration = new AtomicInteger();
        private volatile String summary = "";
        private volatile String dumpPath = "";

        FacebookTab() {
            super(new BorderLayout(5, 5));
            // Price ascending is the default (Facebook sortBy=price_ascend).
            sortBox.setSelectedIndex(2);

            for (int i = 0; i < conditionBoxes.length; i++) {
                conditionBoxes[i] = new JCheckBox(conditionNames[i]);
                conditionBoxes[i].addActionListener(e -> applyFilters());
            }

            JPanel form = new JPanel(new GridBagLayout());
            form.setBorder(BorderFactory.createEmptyBorder(10, 10, 5, 10));
            GridBagConstraints c = new GridBagConstraints();
            c.insets = new Insets(4, 4, 4, 4);
            c.fill = GridBagConstraints.HORIZONTAL;

            field(form, c, 0, "Αναζήτηση:", keywordBox);
            field(form, c, 1, "Πόλη (ID ή URL):", placeBox);
            field(form, c, 2, "Ελάχιστη τιμή:", minBox);
            field(form, c, 3, "Μέγιστη τιμή:", maxBox);
            field(form, c, 4, "Ημερομηνία ανάρτησης:", daysBox);
            field(form, c, 5, "Διαθεσιμότητα:", availabilityBox);
            field(form, c, 6, "Μέθοδος παράδοσης:", deliveryBox);
            field(form, c, 7, "Ακτίνα (km):", radiusBox);
            field(form, c, 8, "Ταξινόμηση:", sortBox);

            c.gridx = 9; c.gridy = 1; c.weightx = 0;
            form.add(deepBox, c);
            c.gridx = 10; c.gridy = 1; c.weightx = 0;
            styleButton(searchBtn, FACEBOOK_COLOR, INK);
            form.add(searchBtn, c);

            placeBox.setToolTipText("Όνομα πόλης (π.χ. Αθήνα, Θεσσαλονίκη, Patras), το ID της πόλης του Marketplace "
                    + "(π.χ. 105807349460179 = όπως στο URL facebook.com/marketplace/<ID>/search) ή ολόκληρο το URL αναζήτησης. "
                    + "Περισσότερες πόλεις: χωρισμένες με κόμμα.");
            radiusBox.setToolTipText("Ακτίνα γύρω από την πόλη σε km (στέλνεται ως radius=). Αν το Facebook την αγνοήσει, "
                    + "η τοποθεσία φιλτράρεται από το πεδίο «Τοποθεσία» παρακάτω.");
            sortBox.setToolTipText("Προεπιλογή: τιμή αύξουσα (φθηνότερες πρώτα). Η ταξινόμηση εφαρμόζεται και στα αποτελέσματα του Facebook.");
            exactBox.setToolTipText("Το Facebook επιστρέφει και «κοντινά» αποτελέσματα (π.χ. RTX 3050 για αναζήτηση RTX 3060). "
                    + "Κρατιούνται όσα έχουν τον όρο στον τίτλο ή την περιγραφή, ή (όταν δεν έχει φορτωθεί η περιγραφή) "
                    + "όσα έχουν στον τίτλο τον αριθμό του όρου (π.χ. 1080 → και «GTX 1080»).");
            cookieBox.setToolTipText("Προαιρετικό: cookie της συνεδρίας σου στο Facebook (name=value; name2=value2) "
                    + "όταν το Facebook ζητά σύνδεση. Κρατιέται μόνο όσο είναι ανοιχτό το πρόγραμμα.");

            JPanel conditionRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            conditionRow.add(new JLabel("Κατάσταση:"));
            for (JCheckBox box : conditionBoxes) conditionRow.add(box);
            conditionRow.add(new JLabel("   Τοποθεσία:"));
            conditionRow.add(locationBox);
            conditionRow.add(new JLabel("   Πωλητής:"));
            conditionRow.add(sellerBox);
            conditionRow.add(new JLabel("   Τύπος:"));
            conditionRow.add(deviceBox);
            deviceBox.setToolTipText("Φίλτρο laptop: ο τίτλος (και η περιγραφή, αν έχει φορτωθεί) ελέγχεται για "
                    + "«laptop», «notebook» και γνωστά μοντέλα (MacBook, ThinkPad, IdeaPad, ZenBook κλπ).");

            JPanel extra = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            extra.add(new JLabel("Αποκλεισμός λέξεων (με κόμμα):"));
            excludeBox.setToolTipText("π.χ. ζητηση, χαλασμενο, ανταλλαγη — ελέγχεται όλο το κείμενο κάθε αγγελίας");
            extra.add(excludeBox);
            extra.add(exactBox);
            extra.add(wideBox);
            extra.add(new JLabel("   Cookie (προαιρετικό):"));
            extra.add(cookieBox);
            extra.add(fileBtn);

            JPanel rows = new JPanel(new GridLayout(2, 1, 0, 4));
            rows.add(conditionRow);
            rows.add(extra);

            JPanel controls = new JPanel(new BorderLayout(0, 3));
            controls.add(form, BorderLayout.CENTER);
            controls.add(rows, BorderLayout.SOUTH);

            status.setBorder(BorderFactory.createEmptyBorder(5, 10, 8, 10));

            JPanel north = new JPanel(new BorderLayout());
            north.add(banner("FACEBOOK MARKETPLACE  ·  Αναζήτηση αγγελιών", FACEBOOK_COLOR), BorderLayout.NORTH);
            north.add(controls, BorderLayout.CENTER);
            north.add(status, BorderLayout.SOUTH);

            configureTable();
            applySort();

            JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                    new JScrollPane(table), buildDetail());
            split.setResizeWeight(0.72);
            split.setContinuousLayout(true);

            add(north, BorderLayout.NORTH);
            add(split, BorderLayout.CENTER);

            keywordBox.addActionListener(e -> search());
            searchBtn.addActionListener(e -> search());
            fileBtn.addActionListener(e -> openSavedPages());
            sortBox.addActionListener(e -> applySort());
            locationBox.addActionListener(e -> {
                if (!refreshingLocations) applyFilters();
            });
            daysBox.addActionListener(e -> applyFilters());
            availabilityBox.addActionListener(e -> applyFilters());
            deliveryBox.addActionListener(e -> applyFilters());
            sellerBox.addActionListener(e -> applyFilters());
            deviceBox.addActionListener(e -> applyFilters());
            exactBox.addActionListener(e -> applyFilters());
            minBox.addActionListener(e -> applyFilters());
            maxBox.addActionListener(e -> applyFilters());
            addLiveTextListener(minBox, this::applyFilters);
            addLiveTextListener(maxBox, this::applyFilters);
            excludeBox.addActionListener(e -> applyFilters());
            excludeBox.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                public void insertUpdate(javax.swing.event.DocumentEvent e) { applyFilters(); }
                public void removeUpdate(javax.swing.event.DocumentEvent e) { applyFilters(); }
                public void changedUpdate(javax.swing.event.DocumentEvent e) { applyFilters(); }
            });
        }

        private void field(JPanel panel, GridBagConstraints c, int x, String label, JComponent comp) {
            c.gridx = x; c.gridy = 0; c.weightx = 0;
            panel.add(new JLabel(label), c);
            c.gridx = x; c.gridy = 1;
            c.weightx = comp instanceof JTextField ? 0.3 : 0.5;
            panel.add(comp, c);
        }

        private void setStatusLater(String message) {
            SwingUtilities.invokeLater(() -> status.setText(message));
        }

        private void configureTable() {
            table.setRowSorter(rowSorter);
            table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);

            Comparator<Double> priceOrder = (a, b) ->
                    Double.compare(a == null ? Double.MAX_VALUE : a, b == null ? Double.MAX_VALUE : b);
            rowSorter.setComparator(2, priceOrder);
            // Ώρα ανάρτησης «yyyy-MM-dd HH:mm»: αλφαβητικά = χρονολογικά· οι άγνωστες πάνε στο τέλος (αύξουσα).
            rowSorter.setComparator(FacebookTableModel.COL_POSTED, (Comparator<String>) (a, b) -> {
                boolean ea = a == null || a.isEmpty();
                boolean eb = b == null || b.isEmpty();
                if (ea || eb) return ea == eb ? 0 : (ea ? 1 : -1);
                return a.compareTo(b);
            });

            int[] widths = {260, 360, 80, 140, 120, 120, 140, 110, 110, 150, 110, 180, 140,
                    170, 170, 100, 130, 130, 350, 0};
            for (int i = 0; i < widths.length; i++) {
                table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
            }
            table.removeColumn(table.getColumnModel().getColumn(FacebookTableModel.COL_PRIORITY));

            styleHeader(table, FACEBOOK_COLOR, INK);

            table.getSelectionModel().addListSelectionListener(e -> {
                if (e.getValueIsAdjusting()) return;
                int viewRow = table.getSelectedRow();
                show(viewRow < 0 ? null : model.getListing(table.convertRowIndexToModel(viewRow)));
            });

            table.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() != 2) return;
                    int viewRow = table.getSelectedRow();
                    if (viewRow < 0) return;
                    Listing l = model.getListing(table.convertRowIndexToModel(viewRow));
                    if (l != null) openUrl(l.url);
                }
            });
        }

        private void applySort() {
            List<RowSorter.SortKey> keys = new ArrayList<>();
            keys.add(new RowSorter.SortKey(FacebookTableModel.COL_PRIORITY, SortOrder.ASCENDING));
            switch (sortBox.getSelectedIndex()) {
                case 0: keys.add(new RowSorter.SortKey(FacebookTableModel.COL_POSTED, SortOrder.ASCENDING)); break;
                case 1: keys.add(new RowSorter.SortKey(FacebookTableModel.COL_POSTED, SortOrder.DESCENDING)); break;
                case 2: keys.add(new RowSorter.SortKey(2, SortOrder.ASCENDING)); break;
                case 3: keys.add(new RowSorter.SortKey(2, SortOrder.DESCENDING)); break;
                case 4: keys.add(new RowSorter.SortKey(0, SortOrder.ASCENDING)); break;
                default: break; // Προτεινόμενα: η σειρά που επέστρεψε το Facebook
            }
            rowSorter.setSortKeys(keys);
        }

        private List<String> selectedConditionCodes() {
            List<String> codes = new ArrayList<>();
            for (int i = 0; i < conditionBoxes.length; i++) {
                if (conditionBoxes[i].isSelected()) codes.add(conditionCodes[i]);
            }
            return codes;
        }

        // Τοπικά φίλτρα: δουλεύουν πάνω στις αγγελίες που έχουν ήδη βρεθεί (χωρίς νέα αναζήτηση).
        private void applyFilters() {
            final Double min = parseOptionalPrice(minBox.getText());
            final Double max = parseOptionalPrice(maxBox.getText());
            final List<String> wantedConditions = selectedConditionCodes();
            final String location = (String) locationBox.getSelectedItem();
            final int availability = availabilityBox.getSelectedIndex();
            final int delivery = deliveryBox.getSelectedIndex();
            final int seller = sellerBox.getSelectedIndex();
            final int device = deviceBox.getSelectedIndex();
            final int days = daysBox.getSelectedIndex() == 0 ? 0 : Integer.parseInt(daysValues[daysBox.getSelectedIndex()]);
            final long cutoff = days == 0 ? 0L : (System.currentTimeMillis() / 1000L) - days * 86400L;
            final boolean exact = exactBox.isSelected() && !lastTerm.isBlank();
            final String term = lastTerm;
            final List<String> excluded = new ArrayList<>();

            for (String t : excludeBox.getText().split("[,;\\n\\r]+")) {
                String n = normalize(t.trim());
                if (!n.isBlank()) excluded.add(n);
            }

            rowSorter.setRowFilter(new RowFilter<FacebookTableModel, Integer>() {
                @Override
                public boolean include(Entry<? extends FacebookTableModel, ? extends Integer> entry) {
                    Listing l = model.getListing(entry.getIdentifier());
                    if (l == null || isDamagedListing(l)) return false;

                    String text = normalize(l.title + " " + l.description + " "
                            + l.fullDescription + " " + l.seller + " " + l.category + " "
                            + l.url + " " + l.condition + " " + l.location + " "
                            + l.specs.cpu + " " + l.specs.ram + " " + l.specs.gpu + " "
                            + l.specs.ssd + " " + l.specs.hdd + " " + l.specs.mobo + " "
                            + l.specs.psu + " " + l.specs.extras);
                    for (String ex : excluded) {
                        if (text.contains(ex)) return false;
                    }

                    if (exact && !SoldTools.matchesKeyword(l.title + " " + l.description + " " + l.fullDescription, term)
                            && !titleMatchesLoose(l.title, term)) {
                        return false;
                    }

                    if (device != 0) {
                        boolean laptop = l.laptop || isLaptopLoose(l.title);
                        if (device == 2 && !laptop) return false;
                        if (device == 1 && laptop) return false;
                    }

                    if (min != null && (l.price == null || l.price < min)) return false;
                    if (max != null && (l.price == null || l.price > max)) return false;

                    // Η κατάσταση φαίνεται μόνο μέσα στη σελίδα της αγγελίας: όσες δεν έχουν φορτωθεί δεν κόβονται.
                    if (!wantedConditions.isEmpty() && !l.conditionCode.isEmpty()
                            && !wantedConditions.contains(l.conditionCode)) {
                        return false;
                    }

                    if (availability == 0 && l.sold) return false;
                    if (availability == 1 && !l.sold) return false;

                    if (delivery == 1 && !l.delivery.isEmpty() && !l.delivery.contains("Παραλαβή")) return false;
                    if (delivery == 2 && !l.delivery.contains("Αποστολή")) return false;

                    // Ο πωλητής φαίνεται στα δεδομένα μόνο για κανονικές αγγελίες χρηστών.
                    if (seller == 1 && l.seller.isEmpty()) return false;
                    if (seller == 2 && !l.seller.isEmpty()) return false;

                    if (cutoff > 0 && l.createdAt > 0 && l.createdAt < cutoff) return false;

                    if (location != null && !location.equals(ALL_LOCATIONS)) {
                        if (location.equals(UNKNOWN_LOCATION)) {
                            if (!l.location.isEmpty()) return false;
                        } else if (!FacebookTools.sameCity(l.location, location)) {
                            return false;
                        }
                    }

                    return true;
                }
            });

            status.setText("Εμφανίζονται " + table.getRowCount()
                    + " από " + model.getRowCount() + " αγγελίες."
                    + (noLocationData && !deepBox.isSelected()
                    ? " | Το Facebook δεν δίνει τοποθεσία στα αποτελέσματα αναζήτησης· τσέκαρε «Λεπτομέρειες αγγελίας» για να φορτωθεί."
                    : ""));
        }

        // Χαλαρός έλεγχος τίτλου: αρκεί να υπάρχουν οι αριθμοί του όρου (π.χ. 1080), ακόμη κι αν λείπει το rtx/gtx.
        private boolean titleMatchesLoose(String title, String term) {
            StringBuilder numbers = new StringBuilder();

            for (String tok : term.trim().split("[\\s\\-/]+")) {
                if (tok.matches(".*\\d.*") && !tok.matches("(?i)\\d+\\s?(gb|tb)")) {
                    numbers.append(tok).append(' ');
                }
            }

            return numbers.length() > 0 && SoldTools.matchesKeyword(title, numbers.toString().trim());
        }

        // Γεμίζει το φίλτρο «Τοποθεσία» με τις πόλεις που βρέθηκαν στις αγγελίες.
        private void refreshLocations() {
            // Ίδια πόλη με διαφορετική γραφή (Athens / Αθήνα) μπαίνει μία φορά.
            TreeMap<String, String> byKey = new TreeMap<>();
            boolean unknown = false;

            for (Listing l : model.all()) {
                if (l.location.isEmpty()) {
                    unknown = true;
                } else {
                    String key = FacebookTools.cityKey(l.location);
                    byKey.putIfAbsent(key.isEmpty() ? l.location : key, l.location);
                }
            }

            TreeSet<String> found = new TreeSet<>(byKey.values());
            noLocationData = !model.all().isEmpty() && found.isEmpty();

            Object selected = locationBox.getSelectedItem();
            refreshingLocations = true;

            try {
                DefaultComboBoxModel<String> m = new DefaultComboBoxModel<>();
                m.addElement(ALL_LOCATIONS);
                for (String name : found) m.addElement(name);
                if (unknown && !found.isEmpty()) m.addElement(UNKNOWN_LOCATION);
                locationBox.setModel(m);

                if (selected != null && m.getIndexOf(selected) >= 0) {
                    locationBox.setSelectedItem(selected);
                } else {
                    locationBox.setSelectedIndex(0);
                }
            } finally {
                refreshingLocations = false;
            }
        }

        // ---------------- Αναζήτηση ----------------

        // Τα φίλτρα της φόρμας -> παράμετροι URL του Marketplace.
        private List<String> buildParams() {
            List<String> pairs = new ArrayList<>();

            Double min = parseOptionalPrice(minBox.getText());
            Double max = parseOptionalPrice(maxBox.getText());
            if (min != null) pairs.add("minPrice=" + VintedTools.plainNumber(min));
            if (max != null) pairs.add("maxPrice=" + VintedTools.plainNumber(max));

            String days = daysValues[Math.max(0, daysBox.getSelectedIndex())];
            if (!days.isEmpty()) pairs.add("daysSinceListed=" + days);

            List<String> conditions = selectedConditionCodes();
            if (!conditions.isEmpty()) pairs.add("itemCondition=" + String.join(",", conditions));

            String availability = availabilityValues[Math.max(0, availabilityBox.getSelectedIndex())];
            if (!availability.isEmpty()) pairs.add("availability=" + availability);

            String delivery = deliveryValues[Math.max(0, deliveryBox.getSelectedIndex())];
            if (!delivery.isEmpty()) pairs.add("deliveryMethod=" + delivery);

            String radius = radiusBox.getText().trim();
            if (radius.matches("\\d{1,4}")) pairs.add("radius=" + radius);

            String sort = sortParams[Math.max(0, sortBox.getSelectedIndex())];
            if (!sort.isEmpty()) pairs.add("sortBy=" + sort);

            return pairs;
        }

        private void search() {
            final String term = keywordBox.getText().trim();

            if (term.isBlank()) {
                JOptionPane.showMessageDialog(this, "Γράψε έναν όρο αναζήτησης.");
                return;
            }

            if (worker != null && !worker.isDone()) {
                worker.cancel(true);
            }

            facebookCookie = cookieBox.getText().trim();
            final boolean deep = deepBox.isSelected();
            final String place = placeBox.getText();
            final List<String> params = buildParams();
            final boolean wide = wideBox.isSelected();
            final int runId = uiGeneration.incrementAndGet();
            final java.util.function.Consumer<List<Listing>> emit = batch ->
                    SwingUtilities.invokeLater(() -> {
                        if (runId != uiGeneration.get()) return;
                        model.addListings(batch);
                        refreshLocations();
                        applyFilters();
                    });

            lastCity = FacebookTools.cityId(place);
            lastTerm = term;
            summary = "";
            dumpPath = "";
            model.setListings(Collections.emptyList());
            refreshLocations();
            show(null);
            searchBtn.setEnabled(false);
            fileBtn.setEnabled(false);
            status.setText("Facebook: αναζήτηση «" + term + "»...");

            startWorker(() -> {
                List<Listing> found;

                if (wide) {
                    found = facebookWideSearch(place, term, params, FB_BUDGET,
                            msg -> setStatusLater(msg), emit, false).listings;
                } else {
                    String url = FacebookTools.searchUrl(place, term, params);
                    setStatusLater("Facebook: λήψη " + url);
                    found = facebookListings(facebookFetch(url), "Facebook Marketplace");
                    if (!found.isEmpty()) emit.accept(found);
                }

                if (found.isEmpty()) {
                    throw new IOException(FACEBOOK_BLOCKED_HINT);
                }

                return finishSearch(term, found, deep, "");
            });
        }

        // Φορτώνει μία ή περισσότερες αποθηκευμένες σελίδες αναζήτησης (Ctrl+S από τον browser).
        private void openSavedPages() {
            JFileChooser chooser = new JFileChooser();
            chooser.setMultiSelectionEnabled(true);
            chooser.setDialogTitle("Αποθηκευμένη σελίδα αναζήτησης Marketplace (.html / .txt)");

            if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
                return;
            }

            final File[] files = chooser.getSelectedFiles();
            uiGeneration.incrementAndGet();
            final String typed = keywordBox.getText().trim();
            final String term = typed.isBlank() ? "marketplace" : typed;
            final boolean deep = deepBox.isSelected();

            if (worker != null && !worker.isDone()) {
                worker.cancel(true);
            }

            facebookCookie = cookieBox.getText().trim();
            lastTerm = typed;
            summary = "";
            dumpPath = "";
            model.setListings(Collections.emptyList());
            refreshLocations();
            show(null);
            searchBtn.setEnabled(false);
            fileBtn.setEnabled(false);
            status.setText("Ανάγνωση αποθηκευμένων σελίδων...");

            startWorker(() -> {
                LinkedHashMap<String, Listing> all = new LinkedHashMap<>();

                for (File f : files) {
                    String html = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                    mergeListings(all, facebookListings(html, "Facebook Marketplace"));
                }

                if (all.isEmpty()) {
                    throw new IOException("Στα αρχεία δεν βρέθηκαν αγγελίες αναζήτησης Marketplace.\n"
                            + "Αποθήκευσε τη σελίδα αναζήτησης (όχι τη σελίδα μιας αγγελίας).");
                }

                return finishSearch(term, new ArrayList<>(all.values()), deep,
                        "Αρχεία: " + files.length);
            });
        }

        // Κοινό για αναζήτηση και φόρτωση αρχείων: τρέχει την εργασία και γεμίζει τον πίνακα.
        private void startWorker(final Callable<List<Listing>> job) {
            worker = new SwingWorker<List<Listing>, Void>() {
                @Override
                protected List<Listing> doInBackground() throws Exception {
                    return job.call();
                }

                @Override
                protected void done() {
                    try {
                        if (isCancelled()) return;

                        List<Listing> listings = get();
                        model.setListings(listings);
                        refreshLocations();
                        applyFilters();

                        status.setText(status.getText()
                                + (summary.isEmpty() ? "" : " | " + summary)
                                + (dumpPath.isEmpty() ? "" : " | Αρχείο: " + dumpPath));
                    } catch (Exception ex) {
                        if (!isCancelled()) {
                            status.setText("Σφάλμα αναζήτησης.");
                            JOptionPane.showMessageDialog(FacebookTab.this,
                                    "Σφάλμα:\n" + rootMessage(ex),
                                    "Facebook Marketplace", JOptionPane.ERROR_MESSAGE);
                        }
                    } finally {
                        searchBtn.setEnabled(true);
                        fileBtn.setEnabled(true);
                    }
                }
            };

            worker.execute();
        }

        private List<Listing> finishSearch(String term, List<Listing> found, boolean deep, String note) throws Exception {
            List<Listing> result = new ArrayList<>(found);
            int loaded = 0;

            if (deep && !result.isEmpty()) {
                loaded = enrich(result);
            }

            saveDump(term, result);

            summary = "Αγγελίες: " + result.size()
                    + (deep ? ", με λεπτομέρειες: " + loaded : "")
                    + (note.isEmpty() ? "" : " | " + note)
                    + (lastCity.isEmpty() ? "" : " | Πόλη: " + lastCity)
                    + " | Το Facebook δίνει ~24 αγγελίες ανά σελίδα αναζήτησης";

            return result;
        }

        // Επιστρέφει πόσες αγγελίες φόρτωσαν λεπτομέρειες.
        private int enrich(List<Listing> listings) throws Exception {
            List<Listing> targets = new ArrayList<>();

            for (Listing l : listings) {
                if (!l.detailsLoaded) targets.add(l);
            }

            if (targets.isEmpty()) return 0;

            final int total = targets.size();
            final AtomicInteger done = new AtomicInteger();
            final AtomicInteger ok = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(2);

            try {
                List<Callable<Void>> tasks = new ArrayList<>();

                for (Listing l : targets) {
                    tasks.add(() -> {
                        try {
                            fetchFacebookDetails(l);
                            ok.incrementAndGet();
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        } catch (Exception ignored) {
                        }

                        setStatusLater("Facebook: λήψη λεπτομερειών "
                                + done.incrementAndGet() + " / " + total + "...");
                        return null;
                    });
                }

                pool.invokeAll(tasks);
            } finally {
                pool.shutdownNow();
            }

            return ok.get();
        }

        private void saveDump(String term, Collection<Listing> listings) {
            File file = new File("facebook_"
                    + term.replaceAll("[^\\p{L}\\p{N}]+", "_") + ".txt").getAbsoluteFile();

            try (PrintWriter out = new PrintWriter(new OutputStreamWriter(
                    new FileOutputStream(file), StandardCharsets.UTF_8))) {

                for (Listing l : listings) {
                    out.println("Τίτλος: " + l.title
                            + " | Τιμή: " + (l.price == null ? "-" : l.price + " €")
                            + " | Τοποθεσία: " + (l.location.isEmpty() ? "-" : l.location)
                            + " | Αναρτήθηκε: " + (l.posted.isEmpty() ? "-" : l.posted)
                            + " | Κατάσταση: " + l.condition
                            + " | Πωλητής: " + l.seller
                            + " | URL: " + l.url
                            + " | Περιγραφή: " + (l.fullDescription.isBlank()
                            ? l.description : l.fullDescription).replace('\n', ' '));
                }

                dumpPath = file.getPath();
            } catch (IOException ex) {
                dumpPath = "";
            }
        }

        // ---------------- Πάνελ περιγραφής ----------------

        private JPanel buildDetail() {
            detailArea.setEditable(false);
            detailArea.setLineWrap(true);
            detailArea.setWrapStyleWord(true);
            detailArea.setMargin(new Insets(6, 8, 6, 8));

            loadFullBtn.setEnabled(false);
            openBtn.setEnabled(false);

            loadFullBtn.addActionListener(e -> loadFull());
            openBtn.addActionListener(e -> {
                Listing l = shown;
                if (l != null) openUrl(l.url);
            });

            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
            buttons.add(loadFullBtn);
            buttons.add(openBtn);

            JPanel top = new JPanel(new BorderLayout(8, 0));
            top.setBorder(BorderFactory.createEmptyBorder(6, 10, 4, 10));
            top.add(detailTitle, BorderLayout.CENTER);
            top.add(buttons, BorderLayout.EAST);

            JPanel panel = new JPanel(new BorderLayout());
            panel.add(top, BorderLayout.NORTH);
            panel.add(new JScrollPane(detailArea), BorderLayout.CENTER);
            panel.setPreferredSize(new Dimension(100, 220));
            return panel;
        }

        private void show(Listing l) {
            shown = l;

            if (l == null) {
                detailTitle.setText("Επίλεξε μια αγγελία για να δεις την περιγραφή της.");
                detailArea.setText("");
                loadFullBtn.setEnabled(false);
                openBtn.setEnabled(false);
                return;
            }

            detailTitle.setText(l.title + (l.price == null ? "" : "  —  " + l.price + " €")
                    + (l.location.isEmpty() ? "" : "  ·  " + l.location));

            StringBuilder text = new StringBuilder();
            text.append("Τοποθεσία: ").append(l.location.isEmpty() ? "— (δεν δίνεται από το Facebook)" : l.location);
            if (!l.condition.isEmpty()) text.append("  ·  Κατάσταση: ").append(l.condition);
            if (!l.posted.isEmpty()) text.append("  ·  Αναρτήθηκε: ").append(l.posted);
            if (!l.delivery.isEmpty()) text.append("  ·  Παράδοση: ").append(l.delivery);
            if (!l.seller.isEmpty()) text.append("  ·  Πωλητής: ").append(l.seller);
            text.append("\n\n");

            if (!l.fullDescription.isEmpty()) {
                text.append(l.fullDescription);
            } else if (l.detailsLoaded) {
                text.append("Δεν βρέθηκε περιγραφή στη σελίδα της αγγελίας.");
            } else {
                text.append("(Η περιγραφή δεν έχει φορτωθεί — πάτα «Φόρτωση πλήρους περιγραφής»)");
            }

            detailArea.setText(text.toString());
            detailArea.setCaretPosition(0);
            loadFullBtn.setEnabled(!l.detailsLoaded);
            openBtn.setEnabled(true);
        }

        private void loadFull() {
            final Listing l = shown;

            if (l == null) return;

            if (l.detailsLoaded) {
                show(l);
                return;
            }

            facebookCookie = cookieBox.getText().trim();
            loadFullBtn.setEnabled(false);
            detailArea.setText("Φόρτωση περιγραφής...");

            new SwingWorker<Void, Void>() {
                @Override
                protected Void doInBackground() throws Exception {
                    fetchFacebookDetails(l);
                    return null;
                }

                @Override
                protected void done() {
                    try {
                        get();
                        model.refresh(l);
                        refreshLocations();
                        if (shown == l) show(l);
                    } catch (Exception ex) {
                        if (shown == l) {
                            detailArea.setText("Σφάλμα φόρτωσης:\n" + rootMessage(ex));
                            loadFullBtn.setEnabled(true);
                        }
                    }
                }
            }.execute();
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(
                        UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
            }
            UIManager.put("defaultFont", CORPORATE_FONT);
            UIManager.put("Panel.background", SURFACE);
            UIManager.put("Label.foreground", TEXT_PRIMARY);

            new Insomniakos().setVisible(true);
        });
    }
}

// ==== SPEC PARSER ====

/*
 * Τα εξαρτήματα ενός υπολογιστή, όπως βγαίνουν από τον τίτλο
 * και το κείμενο της αγγελίας.
 */
final class Specs {
    String cpu = "";
    String ram = "";     // μέγεθος + τύπος + MHz
    String gpu = "";
    String ssd = "";
    String hdd = "";
    String mobo = "";
    String psu = "";
    String extras = "";  // ψύκτρα, οθόνη, ποντίκι, καλώδια...
}

/*
 * Αναγνώριση εξαρτημάτων με ελληνικές και αγγλικές λέξεις-κλειδιά.
 * Δεν χρησιμοποιεί Swing ή jsoup, ώστε να δοκιμάζεται μόνη της.
 */
final class SpecParser {

    private static final int CI =
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    private static final Pattern CPU = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(i[3579](?:[- ]?\\d{3,5}[a-z]?[0-9]{0,2}|\\s+\\d{1,2}\\s*(?:ης(?:\\s+γενιας)?|γενιας))"
                    + "|ryzen\\s?[3579](?:\\s?\\d{3,5}[a-z0-9]{0,3})?"
                    + "|xeon\\s?[a-z]?[\\w-]*\\d[\\w-]*"
                    + "|core\\s?2\\s?(?:duo|quad)(?:\\s?[a-z]?\\d{4})?"
                    + "|pentium(?:\\s?[a-z]?\\d{3,4}[a-z]?)?"
                    + "|celeron(?:\\s?[a-z]?\\d{3,4}[a-z]?)?"
                    + "|athlon(?:\\s?[a-z]?\\d{3,4}[a-z]?)?"
                    + "|fx[- ]?\\d{4})(?![\\p{L}\\p{N}])",
            CI);

    private static final Pattern GPU = Pattern.compile(
            "\\b((?:rx\\s?vega\\s?\\d{2}|radeon\\s?vii|"
                    + "(?:rtx|gtx|gt|rx|arc|quadro|radeon(?:\\s?rx)?)"
                    + "\\s?[a-z]?\\d{3,4}(?:\\s?(?:ti|super|xtx|xt|gre))?))\\b",
            CI);

    // Μητρικές: (προαιρετικά μάρκα/σειρά) + chipset + κατάληξη μοντέλου.
    // π.χ. "Biostar H310MHD PRO2", "MSI B450M PRO-VDH MAX", "TUF GAMING B450-PLUS II".
    // Το "TUF Gaming" / "PRO4" μόνα τους ΔΕΝ μετράνε (είναι και laptop / PSU).
    private static final Pattern MOBO = Pattern.compile(
            "(?<![\\p{L}\\p{N}])("
                    + "(?:(?:ASUS|MSI|GIGABYTE|ASROCK|BIOSTAR|ROG|TUF|PRIME|STRIX|MAG|MPG|MEG|AORUS|GAMING|PRO)\\s+)*"
                    + "(?:A[3-6]20|B[3-8][05]0|X[3-8][07]0|X299|X399|X79|X99|X58"
                    + "|[HBZQ][1-9]\\d{2}|[HBZ][5-9]\\d|G[34]1|P[3-5]\\d|W[45]80)"
                    + "[A-Z0-9]{0,6}"
                    + "(?:[-\\s](?:PRO\\d?|PLUS|GAMING|WIFI|AC|MAX|MORTAR|TOMAHAWK|ELITE|AORUS|STEEL|LEGEND"
                    + "|DS3H|D3H|DS2H?|HDV|HD|VDH|VDV|MK|II|V2|R2|D2|D3|K|E|A|P|D|M)){0,4}"
                    + ")(?![\\p{L}\\p{N}])", CI);
    private static final Pattern WATTAGE = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(\\d{3,4})\\s?W(?![\\p{L}])", CI);

    private static final Pattern OS = Pattern.compile(
            "\\b(?:windows|win|w)\\s?(7|10|11)\\b", CI);

    private static final Pattern MEMORY = Pattern.compile(
            "(?<![\\d.,])(\\d{1,4})\\s?(gb|tb)\\b"
                    + "(?:\\s?(ddr[2-5]|ram|ssd|hdd|nvme|m\\.?2|sata))?",
            CI);

    private static final Pattern SIZE = Pattern.compile(
            "(?<![\\d.,])(\\d{1,4})\\s?(gb|tb)\\b", CI);

    private static final Pattern SIZE_GB = Pattern.compile(
            "(?<![\\d.,])(\\d{1,3})\\s?gb\\b", CI);

    private static final Pattern DDR =
            Pattern.compile("(?<!g)ddr\\s?([2-5])", CI);

    private static final Pattern MHZ =
            Pattern.compile("(\\d{3,5})\\s?mhz", CI);

    private static final Pattern DDR_SPEED = Pattern.compile(
            "ddr[2-5][- ]\\s?(\\d{3,5})(?!\\d)(?!\\s?[gt]b)", CI);

    // 2x4GB, 2 x 4 GB
    private static final Pattern COUNT_X_SIZE = Pattern.compile(
            "(?<![\\w.])(\\d)\\s?[x×χ]\\s?(\\d{1,3})\\s?gb", CI);

    // 2X (χωρίς μέγεθος)
    private static final Pattern COUNT_X = Pattern.compile(
            "(?<![\\w.])(\\d)\\s?[x×χ](?![\\w])", CI);

    private static final String[] LABEL_STEMS = {
            "τροφοδοτικ", "psu", "μητρικ", "motherboard", "mobo", "mainboard",
            "επεξεργαστ", "cpu", "processor", "μνημ", "ram",
            "καρτα", "καρτες", "γραφικ", "gpu", "vga",
            "ssd", "hdd", "σκληρ", "δισκ", "ψυκτρ", "μοντελο", "model", "device", "μητρικη", "motherboard"
    };

    private SpecParser() {
    }

    // ------------------------------------------------------------------
    // Ομαδικές / πολλαπλές αγγελίες (lot, «μεγάλη γκάμα», «κάρτες από 5 έως 10 €»,
    // «πωλούνται επεξεργαστές», λίστες μοντέλων κλπ).
    // ------------------------------------------------------------------

    private static final String[] WANTED_WORDS = {
            "ζηταω", "ζητω", "ζητειται", "ζητουνται", "αγοραζω", "αγοραζουμε", "ψαχνω"
    };

    // Λέξεις/φράσεις που δείχνουν πολλά διαφορετικά προϊόντα στην ίδια αγγελία (κείμενο μέσω norm()).
    private static final Pattern BULK_PHRASE = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(?:"
                    + "γκαμα"
                    + "|(?:μεγαλη|ευρεια|πλουσια|μεγαλο)\\s+(?:ποικιλια|συλλογη|επιλογη)(?:\\s+σε)?"
                    + "|(?:σε\\s+)?πολλεσ\\s+επιλογεσ"
                    + "|μεγαλη\\s+ποικιλια\\s+σε"
                    + "|(?:πολλα|πολλεσ|πολλοι|διαφορα|διαφορεσ|διαφοροι|διαφορων|διαφορετικα|διαφορετικεσ|διαφορετικοι)"
                    + "\\s+(?:μοντελα|καρτεσ|επεξεργαστεσ|εξαρτηματα|υλικα|τεμαχια|κομματια|gpu|gpus|cpu|cpus"
                    + "|μητρικεσ|τροφοδοτικα|μνημεσ|προσφορεσ)"
                    + "|και\\s+αλλα\\s+(?:μοντελα|εξαρτηματα|καρτεσ|επεξεργαστεσ|υλικα|τεμαχια)"
                    + "|πωλουνται\\s+(?:πολλεσ\\s+)?(?:καρτεσ\\s+γραφικων|επεξεργαστεσ|μητρικεσ|μνημεσ|τροφοδοτικα)"
                    + "|διαθετουμε\\s+(?:μεγαλη\\s+)?(?:γκαμα|ποικιλια|συλλογη)"
                    + "|ολα\\s+τα\\s+μοντελα"
                    + "|οσα\\s+θελετε"
                    + "|ανα\\s+τεμαχιο"
                    + "|χονδρικη|χονδρικησ"
                    + "|lot|bulk|λοτ"
                    + "|λιστα|λιστεσ"
                    + "|(?:τιμεσ|ξεκιναν\\p{L}*|αρχιζουν)\\s+απο\\s+\\d"
                    + ")(?![\\p{L}\\p{N}])", CI);

    // Πληθυντικός εξαρτήματος στον ΤΙΤΛΟ (π.χ. «Κάρτες γραφικών ...», «Επεξεργαστές ...»).
    private static final Pattern PLURAL_HW_TITLE = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(?:επεξεργαστεσ|καρτεσ\\s+γραφικ(?:ων|ησ)|καρτεσ\\s+οθονησ|μητρικεσ|τροφοδοτικα"
                    + "|σκληροι\\s+δισκοι|gpus|cpus|graphics\\s+cards|video\\s+cards|processors|motherboards)"
                    + "(?![\\p{L}\\p{N}])", CI);

    // Ευρύτερη λίστα, μόνο σε συνδυασμό με ρήμα πώλησης ή εύρος τιμών.
    private static final Pattern PLURAL_HW_WIDE = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(?:επεξεργαστεσ|καρτεσ\\s+γραφικ(?:ων|ησ)|καρτεσ\\s+οθονησ|μητρικεσ|τροφοδοτικα"
                    + "|μνημεσ|ψυκτρεσ|σκληροι\\s+δισκοι|δισκοι|gpus|cpus|ssds|graphics\\s+cards|video\\s+cards"
                    + "|processors|motherboards)(?![\\p{L}\\p{N}])", CI);

    private static final Pattern SELL_VERB = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(?:πωλουνται|πωλουμε|πουλαμε|διατιθενται|προσφερονται"
                    + "|διαθεσιμεσ|διαθεσιμοι|διαθεσιμα)(?![\\p{L}\\p{N}])", CI);

    private static final Pattern QUANTITY = Pattern.compile(
            "(?<![\\p{L}\\p{N}.,])(\\d{1,3})\\s*(?:x\\s*)?(?:τεμαχια|τεμαχιων|τεμ|κομματια"
                    + "|καρτεσ(?:\\s+γραφικων)?|επεξεργαστεσ|μητρικεσ|τροφοδοτικα|gpus|cpus)(?![\\p{L}\\p{N}])", CI);

    // «από 5 έως 10 euro», «5-10 ευρώ».
    private static final Pattern PRICE_RANGE = Pattern.compile(
            "\\d+(?:[.,]\\d+)?\\s*(?:€|ευρω|euro|eur)?\\s*(?:εως|ως|μεχρι|-|–)\\s*\\d+(?:[.,]\\d+)?\\s*(?:€|ευρω|euro|eur)",
            CI);

    // «RTX 3060 / 3070», «GTX 1060, 1070 & 1080»: αριθμοί μοντέλων που ακολουθούν το πρώτο.
    private static final Pattern GPU_TAIL = Pattern.compile(
            "\\s*(?:/|,|&|\\+|και(?=\\s)|and(?=\\s)|ή(?=\\s))\\s*((?:rtx|gtx|gt|rx|arc)\\s?)?(\\d{3,4})"
                    + "(?:\\s?(ti|super|xtx|xt))?(?![\\p{L}\\p{N}])"
                    + "(?!\\s?(?:gb|tb|w|watt|mhz|ghz|hz|ευρω|euro|eur|€))", CI);

    private static final Pattern CPU_TAIL = Pattern.compile(
            "\\s*(?:/|,|&|\\+)\\s*(?:i[3579][- ]?)?(\\d{4,5}[a-z]?)(?![\\p{L}\\p{N}])"
                    + "(?!\\s?(?:gb|tb|w|mhz|ghz|ευρω|euro|eur|€))", CI);

    private static String gpuKey(String raw) {
        String k = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        String stripped = k.replaceFirst("^(?:rxvega|radeonvii|radeonrx|radeon|quadro|rtx|gtx|gt|rx|arc)", "");
        return stripped.isEmpty() ? k : stripped;
    }

    private static String cpuKey(String raw) {
        String k = raw.toLowerCase(Locale.ROOT).replaceAll("[\\s-]", "");
        String stripped = k.replaceFirst("^(?:i[3579]|ryzen[3579])", "");
        return stripped.isEmpty() ? k : stripped;
    }

    private static boolean looksLikeYear(String digits) {
        if (digits.length() != 4) return false;
        int n = Integer.parseInt(digits);
        return n >= 1990 && n <= 2035;
    }

    // Διαφορετικά μοντέλα GPU στο κείμενο (διπλές αναφορές στο ίδιο μοντέλο δεν μετράνε).
    static Set<String> gpuModels(String text) {
        Set<String> keys = new LinkedHashSet<>();
        if (text == null || text.isBlank()) return keys;
        Matcher m = GPU.matcher(text);
        while (m.find()) {
            keys.add(gpuKey(m.group(1)));
            int pos = m.end();
            while (pos < text.length()) {
                Matcher t = GPU_TAIL.matcher(text.substring(pos));
                if (!t.lookingAt()) break;
                boolean prefixed = t.group(1) != null;
                // Χωρίς πρόθεμα δεχόμαστε μόνο 4ψήφιο αριθμό που δεν είναι έτος (π.χ. «3060 / 3070»).
                if (!prefixed && (t.group(2).length() < 4 || looksLikeYear(t.group(2)))) break;
                keys.add(gpuKey(t.group(2) + (t.group(3) == null ? "" : t.group(3))));
                pos += t.end();
            }
        }
        return keys;
    }

    static Set<String> cpuModels(String text) {
        Set<String> keys = new LinkedHashSet<>();
        if (text == null || text.isBlank()) return keys;
        Matcher m = CPU.matcher(text);
        while (m.find()) {
            keys.add(cpuKey(m.group(1)));
            int pos = m.end();
            while (pos < text.length()) {
                Matcher t = CPU_TAIL.matcher(text.substring(pos));
                if (!t.lookingAt()) break;
                String digits = t.group(1).replaceAll("[a-zA-Z]+$", "");
                if (looksLikeYear(digits)) break;
                keys.add(cpuKey(t.group(1)));
                pos += t.end();
            }
        }
        // «ryzen 5» δεν είναι άλλο μοντέλο από «ryzen 5 3600»: κρατάμε μόνο τα πιο ειδικά.
        Set<String> result = new LinkedHashSet<>();
        for (String k : keys) {
            boolean prefixOfOther = false;
            for (String other : keys) {
                if (!other.equals(k) && other.startsWith(k)) { prefixOfOther = true; break; }
            }
            if (!prefixOfOther) result.add(k);
        }
        return result;
    }

    // Ο ΤΙΤΛΟΣ διαφημίζει περισσότερα από ένα διαφορετικά μοντέλα CPU ή GPU.
    static boolean hasMultipleCpuOrGpu(String title) {
        if (title == null || title.isBlank()) return false;
        return gpuModels(title).size() > 1 || cpuModels(title).size() > 1;
    }

    /*
     * Επιστρέφει τον λόγο για τον οποίο η αγγελία μοιάζει ομαδική / πολλαπλή
     * (lot, μεγάλη γκάμα, πολλά μοντέλα, εύρος τιμών κλπ), ή "" αν δεν μοιάζει.
     */
    static String bulkReason(String title, String body) {
        String safeTitle = title == null ? "" : title;
        String safeBody = body == null ? "" : body;
        String t = norm(safeTitle);
        String all = norm(safeTitle + " " + safeBody);
        if (all.isEmpty()) return "";

        if (BULK_PHRASE.matcher(all).find()) return "φράση ομαδικής αγγελίας";
        if (PLURAL_HW_TITLE.matcher(t).find()) return "πληθυντικός εξαρτήματος στον τίτλο";

        boolean plural = PLURAL_HW_WIDE.matcher(all).find();
        boolean verb = SELL_VERB.matcher(all).find();
        if (plural && verb) return "πωλούνται πολλά εξαρτήματα";
        if (plural && PRICE_RANGE.matcher(all).find()) return "εύρος τιμών για πολλά τεμάχια";

        Matcher q = QUANTITY.matcher(all);
        while (q.find()) {
            try {
                if (Integer.parseInt(q.group(1)) >= 3) return "μεγάλη ποσότητα τεμαχίων";
            } catch (NumberFormatException ignored) {
            }
        }

        String raw = safeTitle + " " + safeBody;
        int gpus = gpuModels(raw).size();
        int cpus = cpuModels(raw).size();
        if (verb && (gpus >= 2 || cpus >= 2)) return "λίστα διαφορετικών μοντέλων";
        if (gpus >= 3 || cpus >= 3) return "πολλά διαφορετικά μοντέλα";
        return "";
    }

    static boolean hasSuspiciousKeywords(String title, String body) {
        String all = norm((title == null ? "" : title) + " " + (body == null ? "" : body));
        if (all.isEmpty()) return false;
        for (String w : WANTED_WORDS) {
            if (containsWord(all, w)) return true;
        }
        return !bulkReason(title, body).isEmpty();
    }

    static boolean hasSuspiciousKeywords(String text) {
        return hasSuspiciousKeywords(text, "");
    }

    private static int countMatches(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        int count = 0;
        while (matcher.find()) count++;
        return count;
    }

    static String norm(String text) {
        if (text == null) {
            return "";
        }

        return Normalizer.normalize(
                        text.toLowerCase(Locale.ROOT),
                        Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace("ς", "σ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /*
     * title: ο τίτλος της αγγελίας
     * body:  το κείμενο της αγγελίας
     *
     * Τρεις τρόποι ανάγνωσης του κειμένου (με αυτή τη σειρά):
     *  1. ΜΕ ΕΤΙΚΕΤΑ  ("CPU: AMD Ryzen 7", "Μνήμη RAM: 16GB Dual-Channel"):
     *     κρατιέται ΟΛΟ ό,τι ακολουθεί την ετικέτα μέχρι να εμφανιστεί
     *     η επόμενη ετικέτα (ή να τελειώσει η γραμμή).
     *  2. ΧΩΡΙΣ ΕΤΙΚΕΤΑ  ("* 💻 Intel Core i7-10750H", "1. NVIDIA RTX 2060 (115W)"):
     *     η γραμμή ταξινομείται από το περιεχόμενό της και κρατιέται ολόκληρη.
     *  3. ΕΦΕΔΡΙΚΑ: αναγνώριση με regex από όλο το κείμενο.
     */
    static Specs parse(String title, String body) {
        Specs s = new Specs();

        List<String> ssd = new ArrayList<>();
        List<String> hdd = new ArrayList<>();
        Set<String> extras = new LinkedHashSet<>();

        String safeTitle = title == null ? "" : title;
        String safeBody = body == null ? "" : body;

        // ---- 1. Τίτλος ----
        Matcher cm = CPU.matcher(safeTitle);

        if (cm.find()) {
            s.cpu = cm.group(1).trim();
        }

        int gpuEnd = -1;
        Matcher gm = GPU.matcher(safeTitle);

        if (gm.find()) {
            s.gpu = gm.group(1).trim().toUpperCase(Locale.ROOT);
            gpuEnd = gm.end();
        }

        scanMemory(safeTitle, s, ssd, hdd, true, gpuEnd);

        // "2x8GB DDR4" -> 16GB (2x8GB), όχι 8GB.
        if (COUNT_X_SIZE.matcher(safeTitle).find()) {
            String titleRam = parseRam(safeTitle);

            if (!titleRam.isEmpty()) {
                s.ram = titleRam;
            }
        }

        if (!s.ram.isEmpty() && !s.ram.contains("MHz")) {
            String mhz = findMhz(safeTitle);

            if (!mhz.isEmpty()) {
                s.ram = s.ram + " " + mhz;
            }
        }

        addPeripherals(norm(safeTitle), extras);

        Matcher titleMobo = MOBO.matcher(safeTitle);
        if (titleMobo.find()) {
            s.mobo = titleMobo.group(1).trim().toUpperCase(Locale.ROOT);
        }

        String titleWatts = psuWatts(safeTitle);
        if (!titleWatts.isEmpty()) {
            s.psu = titleWatts;
        }

        // ---- 2. Γραμμές της αγγελίας ----
        // Ό,τι βρέθηκε ήδη στον τίτλο ΜΕΝΕΙ· το κείμενο συμπληρώνει μόνο
        // όσα ο τίτλος δεν έχει (καμία διπλή καταγραφή). Τιμές με "?"
        // είναι εικασίες του τίτλου και μπορούν να αντικατασταθούν.
        Seen seen = new Seen();
        seen.cpu = isFixed(s.cpu);
        seen.gpu = isFixed(s.gpu);
        seen.ram = isFixed(s.ram);
        seen.mobo = isFixed(s.mobo);
        seen.psu = isFixed(s.psu);

        boolean ssdFixed = ssd.stream().anyMatch(SpecParser::isFixed);
        boolean hddFixed = hdd.stream().anyMatch(SpecParser::isFixed);
        List<String> bodySsd = new ArrayList<>();
        List<String> bodyHdd = new ArrayList<>();
        boolean osLabelled = false;

        for (String raw : splitLines(safeBody)) {
            if (raw.isBlank()) {
                continue;
            }

            List<Hit> hits = findLabels(raw);

            if (hits.isEmpty()) {
                // Γραμμή χωρίς ετικέτα. Μπορεί να έχει πολλά τμήματα
                // ("i5 8400 | 16GB | 500GB SSD").
                for (String part : raw.split("\\s*[;|]\\s*|\\s+\\+\\s+")) {
                    classify(part, s, seen, bodySsd, bodyHdd, extras);
                }
                continue;
            }

            // Κείμενο πριν από την πρώτη ετικέτα (π.χ. "PC SPECS").
            String prefix = raw.substring(0, hits.get(0).start);
            if (!prefix.isBlank()) {
                addPeripherals(norm(prefix), extras);
            }

            for (int i = 0; i < hits.size(); i++) {
                Hit hit = hits.get(i);
                int end = i + 1 < hits.size()
                        ? hits.get(i + 1).start
                        : raw.length();

                String value = cleanValue(raw.substring(hit.valueStart, end));
                String nv = norm(value);

                if (hit.field.equals("os")) {
                    osLabelled = true;
                }

                if (value.isEmpty() || startsNegative(nv)) {
                    if (hit.field.equals("cas") && !value.isEmpty()) {
                        extras.add("no case");
                    }
                    continue;
                }

                switch (hit.field) {
                    case "cpu":
                    case "gpu":
                    case "ram":
                    case "mobo":
                    case "psu":
                        put(s, seen, hit.field, value);
                        break;
                    case "ssd":
                        addUnique(bodySsd, value);
                        break;
                    case "hdd":
                        addUnique(bodyHdd, value);
                        break;
                    case "storage":
                        addStorage(value, bodySsd, bodyHdd);
                        break;
                    case "cas":
                        if (!extras.contains("case")) {
                            extras.add("Case: " + value);
                        }
                        break;
                    case "cooler":
                        if (!extras.contains("cooler")
                                && !extras.contains("stock cooler")) {
                            extras.add("Cooler: " + value);
                        }
                        break;
                    case "display":
                        extras.add("Display: " + value);
                        break;
                    case "os":
                        extras.add("OS: " + value);
                        break;
                    case "model":
                        extras.add("Model: " + value);
                        break;
                    case "battery":
                        extras.add("Battery: " + value);
                        break;
                    case "keyboard":
                        extras.add("Keyboard: " + value);
                        break;
                    default: // "skip": τιμή, εγγύηση κλπ. -- απλώς κόβουν την προηγούμενη τιμή
                        break;
                }
            }
        }

        // Δίσκοι του κειμένου: μπαίνουν μόνο αν ο τίτλος δεν τους έχει ήδη.
        if (!ssdFixed && !bodySsd.isEmpty()) {
            ssd.clear();
            ssd.addAll(bodySsd);
        }

        if (!hddFixed && !bodyHdd.isEmpty()) {
            hdd.clear();
            hdd.addAll(bodyHdd);
        }

        // ---- 3. Εφεδρικά: όλα σε μία γραμμή, χωρίς ετικέτες ----
        if (s.cpu.isEmpty()) {
            Matcher m = CPU.matcher(safeBody);

            if (m.find()) {
                s.cpu = m.group(1).trim();
            }
        }

        if (s.gpu.isEmpty()) {
            Matcher m = GPU.matcher(safeBody);

            if (m.find()) {
                s.gpu = m.group(1).trim().toUpperCase(Locale.ROOT);
            }
        }

        if (s.mobo.isEmpty()) {
            Matcher m = MOBO.matcher(safeBody);

            if (m.find()) {
                s.mobo = m.group(1).trim().toUpperCase(Locale.ROOT);
            }
        }

        if (ssd.isEmpty() && hdd.isEmpty()) {
            scanMemory(safeBody, s, ssd, hdd, false, -1);
        }

        if (s.ram.isEmpty()) {
            for (String raw : splitLines(safeBody)) {
                String n = norm(raw);

                if (!isNegative(n)
                        && !isExtraRam(n)
                        && (DDR.matcher(raw).find() || containsWord(n, "ram"))) {

                    String ram = parseRam(raw);

                    if (!ram.isEmpty()) {
                        s.ram = ram;
                        break;
                    }
                }
            }
        }

        // ---- 4. Λειτουργικό ----
        if (!osLabelled) {
            Matcher om = OS.matcher(safeTitle + "\n" + safeBody);

            if (om.find()) {
                extras.add("W" + om.group(1));
            }
        }

        s.ssd = String.join(" + ", ssd);
        s.hdd = String.join(" + ", hdd);
        s.extras = String.join(", ", extras);

        return s;
    }

    // ---------------------------------------------------------
    // ΕΤΙΚΕΤΕΣ ("CPU:", "Μητρική:", "Case:" ...)
    // ---------------------------------------------------------

    /*
     * Όνομα πεδίου -> λέξεις που το δηλώνουν ως ετικέτα.
     * Μια ετικέτα μετράει ΜΟΝΟ αν ακολουθείται από ":" (προαιρετικά
     * με παρένθεση ανάμεσα, π.χ. "Επεξεργαστής (CPU):").
     * Οι ετικέτες "skip" δεν αποθηκεύονται· υπάρχουν ώστε η τιμή της
     * προηγούμενης ετικέτας να σταματά εκεί (π.χ. "Τιμή:", "Εγγύηση:").
     */
    private static final String[][] LABEL_DEFS = {
            {"cpu", "CPU|Processor|Επεξεργαστ\\p{L}*"},
            {"gpu", "GPU|VGA|Graphics(?:\\s+Card)?|Κ[άα]ρτ\\p{L}*\\s+Γραφικ\\p{L}*|Κ[άα]ρτ\\p{L}*\\s+Οθ[όο]νης"},
            {"ram", "Μν[ήη]μ\\p{L}*(?:\\s+RAM)?|RAM|Memory"},
            {"ssd", "SSD|NVMe"},
            {"hdd", "HDD|Hard\\s*Disk|Σκληρ\\p{L}*(?:\\s+Δ[ίι]σκ\\p{L}*)?"},
            {"storage", "Storage|Αποθηκευτικ\\p{L}*(?:\\s+Χ[ώω]ρ\\p{L}*)?|Δ[ίι]σκ\\p{L}*"},
            {"mobo", "Mobo|Motherboard|Mainboard|Μητρικ\\p{L}*"},
            {"psu", "PSU|Power\\s*Supply|Τροφοδοτικ\\p{L}*"},
            {"cas", "Case|Chassis|Κουτ[ίι]|Κ[άα]σα"},
            {"cooler", "CPU\\s+Cooler|Cooler|Ψ[ύυ]κτρα\\p{L}*"},
            {"display", "Display|Screen|Monitor|Οθ[όο]νη"},
            {"os", "OS|Operating\\s+System|Λειτουργικ\\p{L}*(?:\\s+Σ[ύυ]στημα)?"},
            {"model", "Model(?:\\s+Name)?|Device\\s+Model|Μοντ[έε]λο"},
            {"battery", "Battery|Μπαταρ[ίι]α"},
            {"keyboard", "Keyboard|Πληκτρολ[όο]γιο"},
            {"skip", "Price|Τιμ[ήη]|Warranty|Εγγ[υύ]ηση|Ports|Θ[ύυ]ρες|Connectivity|Συνδεσιμ\\p{L}*|"
                    + "Webcam|Κ[άα]μερα|Weight|Βάρος|Colou?r|Χρ[ώω]μα|Notes?|Σχ[όο]λια|Λοιπ[άα]|Extras?|Έξτρα"}
    };

    private static final Pattern LABEL = buildLabelPattern();

    private static Pattern buildLabelPattern() {
        StringBuilder sb = new StringBuilder("(?<![\\p{L}\\p{N}])(?:");

        for (int i = 0; i < LABEL_DEFS.length; i++) {
            if (i > 0) {
                sb.append('|');
            }

            sb.append("(?<").append(LABEL_DEFS[i][0]).append(">")
                    .append(LABEL_DEFS[i][1]).append(")");
        }

        sb.append(")\\s*(?:\\([^)]*\\)\\s*)?[:：]");

        return Pattern.compile(sb.toString(), CI);
    }

    private static final class Hit {
        final int start;       // αρχή της ετικέτας
        final int valueStart;  // εκεί που ξεκινά η τιμή (μετά το ":")
        final String field;

        Hit(int start, int valueStart, String field) {
            this.start = start;
            this.valueStart = valueStart;
            this.field = field;
        }
    }

    private static List<Hit> findLabels(String line) {
        List<Hit> hits = new ArrayList<>();
        Matcher m = LABEL.matcher(line);

        while (m.find()) {
            for (String[] def : LABEL_DEFS) {
                if (m.group(def[0]) != null) {
                    hits.add(new Hit(m.start(), m.end(), def[0]));
                    break;
                }
            }
        }

        return hits;
    }

    // Μια τιμή του τίτλου που υπάρχει και δεν είναι εικασία ("16GB?").
    private static boolean isFixed(String value) {
        return value != null && !value.isBlank() && !value.endsWith("?");
    }

    /* Ποια πεδία έχουν ήδη συμπληρωθεί από το κείμενο (το κείμενο υπερισχύει του τίτλου). */
    private static final class Seen {
        boolean cpu, gpu, ram, mobo, psu;
    }

    private static void put(Specs s, Seen seen, String field, String value) {
        if (value == null || value.isBlank()) {
            return;
        }

        switch (field) {
            case "cpu":
                if (!seen.cpu) { s.cpu = value; seen.cpu = true; }
                break;
            case "gpu":
                if (!seen.gpu) { s.gpu = value; seen.gpu = true; }
                break;
            case "ram":
                if (!seen.ram) { s.ram = value; seen.ram = true; }
                break;
            case "mobo":
                if (!seen.mobo) { s.mobo = value; seen.mobo = true; }
                break;
            case "psu":
                if (!seen.psu) { s.psu = value; seen.psu = true; }
                break;
            default:
                break;
        }
    }

    // Καθαρίζει μια τιμή: κόβει διαχωριστικά στις άκρες και τεράστια κείμενα.
    private static String cleanValue(String value) {
        String v = value == null ? "" : value.trim()
                .replaceAll("^[\\s,;:|\\-–—•·]+|[\\s,;:|\\-–—•·]+$", "")
                .replaceAll("\\s+", " ")
                .trim();

        return v.length() > 200 ? v.substring(0, 200).trim() + "…" : v;
    }

    // "δεν υπάρχει", "χωρίς", "όχι"... στην ΑΡΧΗ της τιμής.
    private static boolean startsNegative(String nv) {
        return nv.startsWith("δεν ") || nv.startsWith("χωρις")
                || nv.startsWith("οχι") || nv.startsWith("ουτε")
                || nv.equals("no") || nv.startsWith("no ")
                || nv.equals("n/a") || nv.equals("-");
    }

    // Αφαιρεί emoji / κουκκίδες / αρίθμηση από την αρχή της γραμμής.
    private static String stripDecor(String line) {
        String t = line == null ? "" : line;

        t = t.replaceFirst("^[^\\p{L}\\p{N}(]+", "");
        t = t.replaceFirst("^\\d+[.)]\\s+", "");
        t = t.replaceFirst("^[^\\p{L}\\p{N}(]+", "");

        return t.trim();
    }

    // Η γραμμή κρατιέται ολόκληρη αν είναι σύντομη ή αν το εξάρτημα
    // βρίσκεται στην αρχή της· αλλιώς (μακριά πρόταση) μόνο το κομμάτι που βρέθηκε.
    private static boolean wholeLineOk(String line, int matchStart) {
        return line.length() <= 100
                || (matchStart <= 20 && line.length() <= 200);
    }

    private static void addStorage(String value, List<String> ssd, List<String> hdd) {
        String n = norm(value);
        boolean s = has(n, "ssd", "nvme", "m.2");
        boolean h = has(n, "hdd", "σκληρ", "hard disk");

        if (s && h) {
            // "500GB SSD + 1TB HDD" -> χωρίζεται σε δύο δίσκους
            for (String part : value.split("\\s*(?:\\+|&|,)\\s*|\\s+και\\s+")) {
                String np = norm(part);
                boolean ph = has(np, "hdd", "σκληρ", "hard disk");
                boolean ps = has(np, "ssd", "nvme", "m.2");

                if (ph && !ps) {
                    addUnique(hdd, cleanValue(part));
                } else {
                    addUnique(ssd, cleanValue(part));
                }
            }
        } else if (h) {
            addUnique(hdd, value);
        } else {
            addUnique(ssd, value);
        }
    }

    /*
     * Γραμμή ΧΩΡΙΣ ετικέτα: ταξινομείται από το περιεχόμενο.
     * Αν η γραμμή περιέχει πάνω από ένα είδος εξαρτήματος
     * (π.χ. CPU + GPU μαζί) δεν μαντεύεται εδώ· τα βρίσκουν τα εφεδρικά.
     */
    private static void classify(
            String part,
            Specs s,
            Seen seen,
            List<String> ssd,
            List<String> hdd,
            Set<String> extras) {

        String line = stripDecor(part);

        if (line.isEmpty() || line.length() > 300) {
            return;
        }

        String n = norm(line);

        // "δεν υπάρχει κουτί", "χωρίς τροφοδοτικό"...
        if (isNegative(n)) {
            if (n.contains("κουτι") || containsWord(n, "case")) {
                extras.add("no case");
            }
            return;
        }

        addPeripherals(n, extras);

        if (isExtraRam(n)) {
            extras.add(describeExtraRam(line, n));
            return;
        }

        if (has(n, "τροφοδοτικ", "power supply") || containsWord(n, "psu")) {
            put(s, seen, "psu", valueOrLine(line));
            return;
        }

        if (has(n, "μητρικ", "motherboard", "mainboard") || containsWord(n, "mobo")) {
            put(s, seen, "mobo", valueOrLine(line));
            return;
        }

        Matcher cpuM = CPU.matcher(line);
        Matcher gpuM = GPU.matcher(line);
        Matcher mbM = MOBO.matcher(line);

        boolean cpu = cpuM.find();
        boolean gpu = gpuM.find();
        boolean ddr = DDR.matcher(line).find();
        boolean ssdW = has(n, "ssd", "nvme", "m.2");
        boolean hddW = has(n, "hdd", "σκληρ", "hard disk");
        boolean storW = ssdW || hddW
                || (has(n, "storage", "αποθηκευτικ")
                && !has(n, "total", "συνολικ"));
        boolean ram = ddr || containsWord(n, "ram") || has(n, "μνημ");
        boolean mb = mbM.find();

        int kinds = (cpu ? 1 : 0) + (gpu ? 1 : 0) + (ram ? 1 : 0)
                + (storW ? 1 : 0) + (mb ? 1 : 0);

        if (kinds != 1) {
            return;
        }

        if (cpu) {
            put(s, seen, "cpu", wholeLineOk(line, cpuM.start())
                    ? line : cpuM.group(1).trim());

        } else if (gpu) {
            put(s, seen, "gpu", wholeLineOk(line, gpuM.start())
                    ? line : gpuM.group(1).trim().toUpperCase(Locale.ROOT));

        } else if (ram) {
            put(s, seen, "ram", line.length() <= 100 ? line : parseRam(line));

        } else if (storW) {
            if (line.length() <= 100) {
                addStorage(line, ssd, hdd);
            }

        } else {
            put(s, seen, "mobo", wholeLineOk(line, mbM.start())
                    ? line : mbM.group(1).trim().toUpperCase(Locale.ROOT));
        }
    }

    // Ισχύς τροφοδοτικού από τον τίτλο. Κάτω από 300W δεν είναι τροφοδοτικό
    // (π.χ. "RTX 3060 130W" είναι η ισχύς της κάρτας γραφικών laptop).
    private static String psuWatts(String text) {
        Matcher m = WATTAGE.matcher(text);

        while (m.find()) {
            int w = Integer.parseInt(m.group(1));

            if (w >= 300 && w <= 2000) {
                return w + "W";
            }
        }

        return "";
    }

    // ---------------------------------------------------------
    // RAM
    // ---------------------------------------------------------

    /*
     * "2x4GB DDR3 1600MHz" -> "8GB (2x4GB) DDR3 1600MHz"
     * "2X ddr3"            -> "~8GB (2X) DDR3"   (το ~ σημαίνει εκτίμηση)
     * "8GB DDR4"           -> "8GB DDR4"
     */
    static String parseRam(String text) {
        String ddr = "";
        Matcher dm = DDR.matcher(text);

        if (dm.find()) {
            ddr = "DDR" + dm.group(1);
        }

        String size = "";

        Matcher cs = COUNT_X_SIZE.matcher(text);
        Matcher sg = SIZE_GB.matcher(text);
        Matcher cx = COUNT_X.matcher(text);

        if (cs.find()) {
            int count = Integer.parseInt(cs.group(1));
            int each = Integer.parseInt(cs.group(2));

            size = (count * each) + "GB (" + count + "x" + each + "GB)";

        } else if (sg.find()) {
            size = sg.group(1) + "GB";

        } else if (cx.find()) {
            int count = Integer.parseInt(cx.group(1));

            size = "~" + (count * defaultStick(ddr))
                    + "GB (" + count + "X)";
        }

        String mhz = findMhz(text);

        List<String> parts = new ArrayList<>();

        if (!size.isEmpty()) parts.add(size);
        if (!ddr.isEmpty()) parts.add(ddr);
        if (!mhz.isEmpty()) parts.add(mhz);

        String n = norm(text);
        if (n.contains("dual-channel") || n.contains("dual channel")
                || n.contains("dualchannel") || n.contains("διπλου καναλιου")) {
            parts.add("Dual-Channel");
        } else if (n.contains("single-channel") || n.contains("single channel")
                || n.contains("singlechannel") || n.contains("μονο καναλι")) {
            parts.add("Single-Channel");
        }

        return String.join(" ", parts);
    }

    private static int defaultStick(String ddr) {
        switch (ddr) {
            case "DDR2": return 2;
            case "DDR3": return 4;
            case "DDR4": return 8;
            case "DDR5": return 16;
            default: return 4;
        }
    }

    private static String findMhz(String text) {
        Matcher mm = MHZ.matcher(text);

        if (mm.find()) {
            return mm.group(1) + "MHz";
        }

        Matcher dm = DDR_SPEED.matcher(text);

        if (dm.find()) {
            return dm.group(1) + "MHz";
        }

        return "";
    }

    private static boolean isExtraRam(String n) {
        return has(n, "αλλες", "αλλεσ", "αλλα", "επιπλεον", "extra", "εξτρα")
                && (n.contains("μνημ")
                || containsWord(n, "ram")
                || n.contains("ddr"));
    }

    // "Υπάρχουν άλλες 3 κάρτες μνήμης και 2 4 gb και μια 2 gb"
    // -> "+3 extra RAM (4GB, 2GB)"
    private static String describeExtraRam(String line, String n) {
        String count = "";
        Matcher cm = Pattern.compile("(\\d+)\\s+(?:καρτ|μνημ)").matcher(n);

        if (cm.find()) {
            count = cm.group(1);
        }

        List<String> sizes = new ArrayList<>();
        Matcher sm = SIZE_GB.matcher(line);

        while (sm.find()) {
            sizes.add(sm.group(1) + "GB");
        }

        String result = count.isEmpty()
                ? "extra RAM"
                : "+" + count + " extra RAM";

        if (!sizes.isEmpty()) {
            result += " (" + String.join(", ", sizes) + ")";
        }

        return result;
    }

    // ---------------------------------------------------------
    // ΜΝΗΜΗ / ΔΙΣΚΟΙ ΑΠΟ ΜΕΓΕΘΗ (GB/TB)
    // ---------------------------------------------------------

    /*
     * guess=true (τίτλος): μέγεθος χωρίς ετικέτα μαντεύεται
     * και σημειώνεται με "?".
     * guess=false (κείμενο): μόνο μεγέθη με ρητή ετικέτα.
     */
    private static void scanMemory(
            String text,
            Specs s,
            List<String> ssd,
            List<String> hdd,
            boolean guess,
            int gpuEnd) {

        Matcher m = MEMORY.matcher(text);

        while (m.find()) {
            int number = Integer.parseInt(m.group(1));
            String unit = m.group(2).toUpperCase(Locale.ROOT);
            String label = m.group(3) == null
                    ? null
                    : m.group(3).toUpperCase(Locale.ROOT).replace(".", "");

            if (label == null) {
                String before = text.substring(
                                Math.max(0, m.start() - 8), m.start())
                        .toUpperCase(Locale.ROOT);

                if (before.contains("SSD")) {
                    label = "SSD";
                } else if (before.contains("HDD")) {
                    label = "HDD";
                } else if (before.contains("NVME")) {
                    label = "NVME";
                } else if (before.contains("RAM")) {
                    label = "RAM";
                }
            }

            // Recognize capacities when the storage label follows the brand,
            // e.g. "240GB Samsung SSD" or "1TB Seagate HDD".
            if (label == null) {
                int afterEnd = Math.min(text.length(), m.end() + 28);
                String after = norm(text.substring(m.end(), afterEnd));
                // "16GB 500GB SSD": το 500GB είναι ο δίσκος, όχι "μάρκα" του 16GB.
                boolean nextIsSize = after.matches("(?s)^\\s*\\d+\\s?(?:gb|tb)\\b.*");
                if (nextIsSize) {
                    // καμία ετικέτα
                } else if (after.matches("(?s)^\\s*[\\p{L}0-9._-]{1,18}\\s+(?:ssd|nvme|m\\.?2).*")) {
                    label = after.contains("nvme") ? "NVME" : "SSD";
                } else if (after.matches("(?s)^\\s*[\\p{L}0-9._-]{1,18}\\s+(?:hdd|hard disk).*")) {
                    label = "HDD";
                }
            }

            String size = number + unit;

            // Μνήμη κάρτας γραφικών (π.χ. "RTX 3060 12GB").
            if (label == null && guess && !s.gpu.isEmpty()
                    && gpuEnd >= 0
                    && m.start() >= gpuEnd
                    && m.start() - gpuEnd <= 3
                    // 16/32/64GB are commonly system RAM in PC titles.
                    // Prefer RAM unless the title explicitly labels VRAM.
                    && !(unit.equals("GB")
                    && (number == 16 || number == 32 || number == 64))) {

                s.gpu = s.gpu + " " + size;
                continue;
            }

            if (label != null
                    && (label.startsWith("DDR") || label.equals("RAM"))) {

                if (s.ram.isEmpty()) {
                    s.ram = label.equals("RAM") ? size : size + " " + label;
                }

            } else if (label != null) {
                switch (label) {
                    case "SSD":
                        addUnique(ssd, size);
                        break;
                    case "NVME":
                        addUnique(ssd, size + " NVMe");
                        break;
                    case "M2":
                        addUnique(ssd, size + " M.2");
                        break;
                    case "HDD":
                        addUnique(hdd, size);
                        break;
                    default: // SATA
                        if (unit.equals("TB") || number >= 750) {
                            addUnique(hdd, size);
                        } else {
                            addUnique(ssd, size);
                        }
                        break;
                }

            } else if (guess) {
                // Bare capacities in a title: common RAM sizes first.
                if (unit.equals("GB") &&
                        (number == 8 || number == 16 || number == 24 || number == 32
                                || number == 40 || number == 64)) {
                    if (s.ram.isEmpty()) s.ram = size + "?";
                } else if (unit.equals("TB") || number == 500 || number == 1000
                        || number == 2000 || number >= 750) {
                    addUnique(hdd, size + "?");
                } else if (number >= 120) {
                    addUnique(ssd, size + "?");
                } else if (s.ram.isEmpty()) {
                    s.ram = size + "?";
                }
            }
        }
    }

    private static String storageValue(String line, String n) {
        Matcher m = SIZE.matcher(line);

        if (!m.find()) {
            return valueOrLine(line);
        }

        String result = m.group(1) + m.group(2).toUpperCase(Locale.ROOT);

        if (n.contains("nvme")) {
            result += " NVMe";
        } else if (n.contains("m.2")) {
            result += " M.2";
        }

        return result;
    }

    // ---------------------------------------------------------
    // ΠΕΡΙΦΕΡΕΙΑΚΑ / ΕΞΤΡΑ
    // ---------------------------------------------------------

    private static boolean isCooler(String n) {
        return has(n, "ψυκτρ", "cooler", "heatsink");
    }

    private static void addPeripherals(String n, Set<String> extras) {
        if (isCooler(n)) {
            extras.add(has(n, "intel", "amd", "stock", "original", "box")
                    ? "stock cooler"
                    : "cooler");
        }

        if (n.contains("οθον") && !has(n, "καρτα οθον", "καρτες οθον")) {
            extras.add("monitor");
        }

        if (n.contains("ποντικ")) {
            extras.add("mouse");
        }

        if (n.contains("πληκτρολογ")) {
            extras.add("keyboard");
        }

        if (n.contains("κεραι")) {
            extras.add(has(n, "wifi", "wi-fi", "ασυρματ")
                    ? "wifi antenna"
                    : "antenna");
        } else if (has(n, "wifi", "wi-fi")) {
            extras.add("wifi");
        }

        if (n.contains("καλωδι")) {
            extras.add("cables");
        }

        if (n.contains("ηχει")) {
            extras.add("speakers");
        }

        if (has(n, "dvd", "cd-rom", "cdrom", "οπτικ")) {
            extras.add("optical drive");
        }

        if (has(n, "καμερα", "webcam", "web cam")) {
            extras.add("webcam");
        }

        if (containsWord(n, "ups")) {
            extras.add("UPS");
        }

        if (n.contains("ανεμιστηρ") || containsWord(n, "fan")) {
            extras.add("fan");
        }

        if (has(n, "καρτα δικτυου", "lan card")) {
            extras.add("network card");
        }

        if (n.contains("καρτα ηχου")) {
            extras.add("sound card");
        }

        if (n.contains("bluetooth")) {
            extras.add("bluetooth");
        }

        if (n.contains("κουτι") || containsWord(n, "case")
                || n.contains("τοιχωμα")) {
            extras.add("case");
        }
    }

    // ---------------------------------------------------------
    // ΒΟΗΘΗΤΙΚΑ
    // ---------------------------------------------------------

    private static boolean isNegative(String n) {
        return has(n, "δεν υπαρχει", "δεν υπαρχουν", "χωρις",
                "δεν περιλαμβανεται", "δεν εχει");
    }

    private static List<String> splitLines(String body) {
        List<String> result = new ArrayList<>();

        // Decode numeric HTML entities and remove Markdown emphasis so labels such
        // as **CPU:** and Μοντέλ&#x3BF;**:** are handled like ordinary text.
        String cleaned = decodeHtmlEntities(body == null ? "" : body)
                .replaceAll("\\*{1,3}", "")
                .replace("\u00a0", " ");

        // Οι ετικέτες (CPU:, Μνήμη RAM:, Case: ...) δεν κόβονται εδώ·
        // τις χειρίζεται το parse() ώστε η τιμή να κρατιέται ολόκληρη.
        for (String line : cleaned.split("\\r?\\n|•")) {
            if (!line.isBlank()) {
                result.add(line.trim());
            }
        }

        return result;
    }

    private static String decodeHtmlEntities(String text) {
        Matcher m = Pattern.compile("&#(?:x([0-9a-fA-F]+)|(\\d+));?").matcher(text);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            try {
                int codePoint = Integer.parseInt(m.group(1) != null ? m.group(1) : m.group(2),
                        m.group(1) != null ? 16 : 10);
                String replacement = new String(Character.toChars(codePoint));
                m.appendReplacement(out, Matcher.quoteReplacement(replacement));
            } catch (Exception ex) {
                m.appendReplacement(out, Matcher.quoteReplacement(m.group()));
            }
        }
        m.appendTail(out);
        return out.toString().replace("&nbsp;", " ").replace("&amp;", "&");
    }

    private static boolean has(String n, String... keys) {
        for (String key : keys) {
            if (n.contains(key)) {
                return true;
            }
        }

        return false;
    }

    private static boolean containsWord(String n, String word) {
        return Pattern.compile(
                        "(?<![\\p{L}\\p{N}])" + Pattern.quote(word)
                                + "(?![\\p{L}\\p{N}])")
                .matcher(n)
                .find();
    }

    private static void addUnique(List<String> list, String value) {
        if (value != null && !value.isBlank() && !list.contains(value)) {
            list.add(value);
        }
    }

    private static String valueOrLine(String line) {
        String cleaned = decodeHtmlEntities(line == null ? "" : line)
                .replaceAll("\\*{1,3}", "").trim();
        int colon = Math.max(cleaned.indexOf(':'), cleaned.indexOf('：'));
        if (colon >= 0 && colon + 1 < cleaned.length()) {
            String value = cleaned.substring(colon + 1).trim();
            return value.replaceAll("^[\\s,;\\-–]+|[\\s,;\\-–]+$", "").trim();
        }
        String value = stripLabels(cleaned);
        return value.isEmpty() ? cleaned : value;
    }

    /*
     * "Force sl x750eps (max 750w) Τροφοδοτικό" -> "Force sl x750eps (max 750w)"
     * Αφαιρεί τις λέξεις-ετικέτες (Τροφοδοτικό, Μητρική, Κάρτα Γραφικών...).
     */
    private static String stripLabels(String line) {
        StringBuilder sb = new StringBuilder();

        for (String token : line.trim().split("\\s+")) {
            String t = norm(token).replaceAll("[^\\p{L}\\p{N}.]", "");
            boolean label = false;

            for (String stem : LABEL_STEMS) {
                if (!t.isEmpty() && t.startsWith(stem)) {
                    label = true;
                    break;
                }
            }

            if (!label) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }

                sb.append(token);
            }
        }

        return sb.toString()
                .replaceAll("^[\\s,;:\\-–]+|[\\s,;:\\-–]+$", "")
                .trim();
    }
}

final class XRow {
    final int excelRow;
    final String[] cells;
    XRow(int excelRow, String[] cells) { this.excelRow = excelRow; this.cells = cells; }
    String get(int i) { return i >= 0 && i < cells.length && cells[i] != null ? cells[i] : ""; }
}

final class XlsxSheet {
    final String name;
    final String[] headers;
    final List<Object[]> rows;
    XlsxSheet(String name, String[] headers, List<Object[]> rows) {
        this.name = name;
        this.headers = headers;
        this.rows = rows;
    }
}

final class SoldTools {
    private SoldTools() {}

    static Map<String, List<XRow>> readXlsx(File file) throws Exception {
        Map<String, List<XRow>> result = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(file)) {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            List<String> shared = new ArrayList<>();
            ZipEntry ss = zip.getEntry("xl/sharedStrings.xml");
            if (ss != null) {
                org.w3c.dom.Document d = parseXml(dbf, zip, ss);
                NodeList sis = d.getElementsByTagName("si");
                for (int i = 0; i < sis.getLength(); i++) {
                    shared.add(textOfT((org.w3c.dom.Element) sis.item(i)));
                }
            }
            Map<String, String> targets = new HashMap<>();
            org.w3c.dom.Document rels = parseXml(dbf, zip, zip.getEntry("xl/_rels/workbook.xml.rels"));
            NodeList rl = rels.getElementsByTagName("Relationship");
            for (int i = 0; i < rl.getLength(); i++) {
                org.w3c.dom.Element e = (org.w3c.dom.Element) rl.item(i);
                targets.put(e.getAttribute("Id"), e.getAttribute("Target"));
            }
            org.w3c.dom.Document wb = parseXml(dbf, zip, zip.getEntry("xl/workbook.xml"));
            NodeList sheets = wb.getElementsByTagName("sheet");
            for (int i = 0; i < sheets.getLength(); i++) {
                org.w3c.dom.Element e = (org.w3c.dom.Element) sheets.item(i);
                String target = targets.get(e.getAttribute("r:id"));
                if (target == null) continue;
                String path = target.startsWith("/") ? target.substring(1) : "xl/" + target;
                ZipEntry se = zip.getEntry(path);
                if (se == null) continue;
                result.put(e.getAttribute("name"), readSheet(dbf, zip, se, shared));
            }
        }
        return result;
    }

    private static org.w3c.dom.Document parseXml(DocumentBuilderFactory dbf, ZipFile zip, ZipEntry entry) throws Exception {
        try (InputStream in = zip.getInputStream(entry)) {
            return dbf.newDocumentBuilder().parse(in);
        }
    }

    private static String textOfT(org.w3c.dom.Element e) {
        StringBuilder sb = new StringBuilder();
        NodeList ts = e.getElementsByTagName("t");
        for (int i = 0; i < ts.getLength(); i++) sb.append(ts.item(i).getTextContent());
        return sb.toString();
    }

    private static List<XRow> readSheet(DocumentBuilderFactory dbf, ZipFile zip, ZipEntry entry, List<String> shared) throws Exception {
        org.w3c.dom.Document d = parseXml(dbf, zip, entry);
        NodeList rows = d.getElementsByTagName("row");
        List<XRow> out = new ArrayList<>();
        for (int i = 0; i < rows.getLength(); i++) {
            org.w3c.dom.Element row = (org.w3c.dom.Element) rows.item(i);
            int rn;
            try { rn = Integer.parseInt(row.getAttribute("r")); } catch (Exception ex) { rn = i + 1; }
            TreeMap<Integer, String> cells = new TreeMap<>();
            NodeList cs = row.getElementsByTagName("c");
            for (int j = 0; j < cs.getLength(); j++) {
                org.w3c.dom.Element c = (org.w3c.dom.Element) cs.item(j);
                int col = colIndex(c.getAttribute("r"), j);
                String t = c.getAttribute("t");
                String v = "";
                if ("inlineStr".equals(t)) {
                    v = textOfT(c);
                } else {
                    NodeList vs = c.getElementsByTagName("v");
                    if (vs.getLength() > 0) v = vs.item(0).getTextContent().trim();
                    if ("s".equals(t) && !v.isEmpty()) {
                        int idx = Integer.parseInt(v);
                        v = idx >= 0 && idx < shared.size() ? shared.get(idx) : "";
                    } else if (t.isEmpty() || "n".equals(t)) {
                        v = fmtNum(v);
                    }
                }
                cells.put(col, v);
            }
            int max = cells.isEmpty() ? 0 : cells.lastKey() + 1;
            String[] arr = new String[max];
            Arrays.fill(arr, "");
            for (Map.Entry<Integer, String> e : cells.entrySet()) arr[e.getKey()] = e.getValue();
            out.add(new XRow(rn, arr));
        }
        return out;
    }

    private static String fmtNum(String v) {
        if (v.isEmpty()) return v;
        try {
            double x = Double.parseDouble(v);
            if (x == Math.rint(x) && Math.abs(x) < 1e15) return String.valueOf((long) x);
            return String.valueOf(x);
        } catch (NumberFormatException ex) {
            return v;
        }
    }

    private static int colIndex(String ref, int fallback) {
        int col = 0, n = 0;
        for (int i = 0; i < ref.length(); i++) {
            char ch = Character.toUpperCase(ref.charAt(i));
            if (ch < 'A' || ch > 'Z') break;
            col = col * 26 + (ch - 'A' + 1);
            n++;
        }
        return n == 0 ? fallback : col - 1;
    }

    static String buildKeyword(String sheet, XRow row, boolean ramSpeed) {
        String s = SpecParser.norm(sheet);
        if (s.contains("ram")) {
            String cap = row.get(1).replaceAll("\\s+", "");
            String[] p = row.get(2).trim().split("[\\s\\-]+");
            StringBuilder sb = new StringBuilder(cap);
            if (p.length > 0 && !p[0].isEmpty()) sb.append(' ').append(p[0]);
            if (ramSpeed && p.length > 1) sb.append(' ').append(p[1]);
            return sb.toString().trim();
        }
        if (s.contains("gpu") && !row.get(1).isBlank()) return row.get(1).trim();
        String a = row.get(0)
                .replaceAll("(?i)\\(r\\)|\\(tm\\)|™|®", " ")
                .replaceAll("(?i)(?<![\\p{L}\\p{N}])(intel|amd|processor|cpu)(?![\\p{L}\\p{N}])", " ")
                .replaceAll("(?i)(?<![\\p{L}\\p{N}])core(?=\\s+i[3579])", " ");
        return a.replace('-', ' ').replaceAll("\\s+", " ").trim();
    }

    static boolean matchesKeyword(String text, String keyword) {
        String h = SpecParser.norm(text);
        String k = SpecParser.norm(keyword);
        boolean any = false;
        for (String tok : k.split("[\\s\\-/]+")) {
            if (tok.isEmpty()) continue;
            any = true;
            String re;
            Matcher sz = Pattern.compile("(\\d+)(gb|tb)").matcher(tok);
            if (sz.matches()) {
                re = "(?<![\\p{L}\\p{N}.,])" + sz.group(1) + "\\s?" + sz.group(2) + "(?![\\p{L}])";
            } else if (tok.matches("\\p{N}+")) {
                re = "(?<![\\p{N}])" + tok + "(?![\\p{N}])(?!\\p{L}(?!\\p{L}))";
            } else if (tok.matches("\\p{L}+")) {
                re = "(?<![\\p{L}])" + Pattern.quote(tok) + "(?![\\p{L}])";
            } else {
                re = "(?<![\\p{L}\\p{N}])" + Pattern.quote(tok) + "(?![\\p{L}\\p{N}])";
            }
            if (!Pattern.compile(re).matcher(h).find()) return false;
        }
        return any;
    }

    static boolean variantMismatch(String text, String keyword) {
        // Compare a suffix only when it immediately follows the searched GPU model.
        // Looking for "Ti" / "XT" anywhere in a listing wrongly filtered e.g. a RAM ad
        // whose description happened to mention an unrelated RTX card.
        String h = SpecParser.norm(text);
        String k = SpecParser.norm(keyword);
        Matcher model = Pattern.compile("(?i)(?:rx\\s*vega\\s*\\d{2}|(?:rtx|gtx|gt|rx|quadro|arc)\\s*\\d{3,4})").matcher(k);
        if (!model.find()) return false;
        String modelName = model.group().replaceAll("\\s+", "\\\\s*");
        Pattern variant = Pattern.compile("(?i)(?<![\\p{L}\\p{N}])" + modelName
                + "[-\\s]*(ti|super|xtx|xt|gre)(?![\\p{L}])");
        Matcher found = variant.matcher(h);
        if (!found.find()) return false;
        String requestedTail = k.substring(model.end()).trim();
        return !requestedTail.matches("(?i)^(?:ti|super|xtx|xt|gre)(?:\\s.*)?$");
    }

    static LocalDate parseDate(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.isEmpty()) return null;
        ZoneId z = ZoneId.of("Europe/Athens");
        try { return OffsetDateTime.parse(s).atZoneSameInstant(z).toLocalDate(); } catch (Exception ignored) { }
        try { return LocalDateTime.parse(s).toLocalDate(); } catch (Exception ignored) { }
        try { if (s.length() >= 10) return LocalDate.parse(s.substring(0, 10)); } catch (Exception ignored) { }
        // Insomnia also renders dates as dd/MM/yyyy in Greek listing headers/status text.
        Matcher greek = Pattern.compile("(?<!\\d)(\\d{1,2})[./-](\\d{1,2})[./-](\\d{4})(?!\\d)").matcher(s);
        if (greek.find()) {
            try {
                return LocalDate.of(Integer.parseInt(greek.group(3)),
                        Integer.parseInt(greek.group(2)), Integer.parseInt(greek.group(1)));
            } catch (Exception ignored) { }
        }
        return null;
    }

    static void writeXlsxBook(File out, List<XlsxSheet> sheets) throws IOException {
        if (sheets == null || sheets.isEmpty()) throw new IOException("Το workbook δεν έχει φύλλα.");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(out))) {
            StringBuilder types = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
                    .append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
                    .append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
                    .append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
                    .append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
                    .append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
            for (int i = 0; i < sheets.size(); i++) {
                types.append("<Override PartName=\"/xl/worksheets/sheet").append(i + 1)
                        .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
            }
            types.append("</Types>");
            put(z, "[Content_Types].xml", types.toString());

            put(z, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                    + "</Relationships>");

            StringBuilder workbook = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
                    .append("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" ")
                    .append("xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">")
                    .append("<bookViews><workbookView activeTab=\"0\"/></bookViews><sheets>");
            StringBuilder rels = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
                    .append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
            for (int i = 0; i < sheets.size(); i++) {
                XlsxSheet sheet = sheets.get(i);
                workbook.append("<sheet name=\"").append(esc(sheet.name)).append("\" sheetId=\"")
                        .append(i + 1).append("\" r:id=\"rId").append(i + 1).append("\"/>");
                rels.append("<Relationship Id=\"rId").append(i + 1)
                        .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet")
                        .append(i + 1).append(".xml\"/>");
            }
            workbook.append("</sheets></workbook>");
            rels.append("<Relationship Id=\"rId").append(sheets.size() + 1)
                    .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>")
                    .append("</Relationships>");
            put(z, "xl/workbook.xml", workbook.toString());
            put(z, "xl/_rels/workbook.xml.rels", rels.toString());
            put(z, "xl/styles.xml", stylesXml());

            for (int i = 0; i < sheets.size(); i++) {
                put(z, "xl/worksheets/sheet" + (i + 1) + ".xml", worksheetXml(sheets.get(i)));
            }
        }
    }

    private static String stylesXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<numFmts count=\"1\"><numFmt numFmtId=\"164\" formatCode=\"#,##0.00 &quot;€&quot;\"/></numFmts>"
                + "<fonts count=\"3\"><font><sz val=\"11\"/><name val=\"Aptos\"/></font>"
                + "<font><b/><color rgb=\"FFFFFFFF\"/><sz val=\"11\"/><name val=\"Aptos\"/></font>"
                + "<font><u/><sz val=\"11\"/><color rgb=\"FF0563C1\"/><name val=\"Aptos\"/></font></fonts>"
                + "<fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill>"
                + "<fill><patternFill patternType=\"gray125\"/></fill>"
                + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF1E293B\"/><bgColor indexed=\"64\"/></patternFill></fill></fills>"
                + "<borders count=\"2\"><border><left/><right/><top/><bottom/><diagonal/></border>"
                + "<border><left style=\"thin\"><color rgb=\"FFE2E8F0\"/></left><right style=\"thin\"><color rgb=\"FFE2E8F0\"/></right>"
                + "<top style=\"thin\"><color rgb=\"FFE2E8F0\"/></top><bottom style=\"thin\"><color rgb=\"FFE2E8F0\"/></bottom><diagonal/></border></borders>"
                + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
                + "<cellXfs count=\"5\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
                + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\"><alignment vertical=\"center\" wrapText=\"1\"/></xf>"
                + "<xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyBorder=\"1\"/>"
                + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyBorder=\"1\"><alignment vertical=\"top\" wrapText=\"1\"/></xf>"
                + "<xf numFmtId=\"0\" fontId=\"2\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyBorder=\"1\"><alignment vertical=\"top\"/></xf>"
                + "</cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>";
    }

    private static String worksheetXml(XlsxSheet sheet) {
        int cols = sheet.headers.length;
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
                .append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
                .append("<sheetViews><sheetView showGridLines=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>")
                .append("<sheetFormatPr defaultRowHeight=\"19\"/><cols>");
        for (int c = 0; c < cols; c++) {
            String h = sheet.headers[c] == null ? "" : sheet.headers[c].toLowerCase(Locale.ROOT);
            double width = Math.max(12, Math.min(34, sheet.headers[c].length() + 4));
            if (h.contains("description") || h.contains("περιγραφ")) width = 62;
            else if (h.equals("url") || h.endsWith(" url") || h.contains("link")) width = 48;
            else if (h.contains("title") || h.contains("part") || h.contains("εξαρτημα")) width = 40;
            else if (h.contains("status") || h.contains("κατασταση")) width = 34;
            sb.append("<col min=\"").append(c + 1).append("\" max=\"").append(c + 1)
                    .append("\" width=\"").append(width).append("\" customWidth=\"1\"/>");
        }
        sb.append("</cols><sheetData>");
        appendStyledRow(sb, 1, sheet.headers, sheet.headers, true);
        int r = 2;
        for (Object[] row : sheet.rows) {
            appendStyledRow(sb, r++, row, sheet.headers, false);
        }
        sb.append("</sheetData>");
        int lastCol = Math.max(1, cols);
        String lastCell = colName(lastCol - 1) + Math.max(1, r - 1);
        sb.append("<autoFilter ref=\"A1:").append(lastCell).append("\"/>");
        sb.append("</worksheet>");
        return sb.toString();
    }

    private static boolean isHyperlinkText(String text) {
        return text.startsWith("http") && text.length() <= 250
                && text.indexOf('"') < 0 && text.indexOf(' ') < 0 && text.indexOf('\n') < 0;
    }

    private static void appendStyledRow(StringBuilder sb, int r, Object[] values,
                                        String[] headers, boolean header) {
        sb.append("<row r=\"").append(r).append("\" customHeight=\"1\" ht=\"")
                .append(header ? "28" : "30").append("\">");
        for (int c = 0; c < values.length; c++) {
            Object v = values[c];
            if (v == null) continue;
            String ref = colName(c) + r;
            if (v instanceof Number) {
                String h = c < headers.length ? headers[c] : "";
                int style = !header && h.contains("€") ? 2 : (!header ? 3 : 1);
                sb.append("<c r=\"").append(ref).append("\" s=\"").append(style).append("\"><v>")
                        .append(((Number) v).doubleValue()).append("</v></c>");
            } else if (!header && isHyperlinkText(String.valueOf(v))) {
                // Πατήσιμος σύνδεσμος (τύπος HYPERLINK, χωρίς επιπλέον αρχεία στο xlsx).
                String url = String.valueOf(v);
                sb.append("<c r=\"").append(ref).append("\" s=\"4\" t=\"str\"><f>HYPERLINK(&quot;")
                        .append(esc(url)).append("&quot;,&quot;").append(esc(url)).append("&quot;)</f><v>")
                        .append(esc(url)).append("</v></c>");
            } else {
                sb.append("<c r=\"").append(ref).append("\" s=\"").append(header ? 1 : 3)
                        .append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                        .append(esc(String.valueOf(v))).append("</t></is></c>");
            }
        }
        sb.append("</row>");
    }

    private static void appendRow(StringBuilder sb, int r, Object[] values) {
        sb.append("<row r=\"").append(r).append("\">");
        for (int c = 0; c < values.length; c++) {
            Object v = values[c];
            if (v == null) continue;
            String ref = colName(c) + r;
            if (v instanceof Number) {
                sb.append("<c r=\"").append(ref).append("\"><v>").append(((Number) v).doubleValue()).append("</v></c>");
            } else {
                sb.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                        .append(esc(String.valueOf(v))).append("</t></is></c>");
            }
        }
        sb.append("</row>");
    }

    private static String colName(int c) {
        StringBuilder sb = new StringBuilder();
        for (int n = c + 1; n > 0; n = (n - 1) / 26) sb.insert(0, (char) ('A' + (n - 1) % 26));
        return sb.toString();
    }

    private static void put(ZipOutputStream z, String name, String content) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        z.closeEntry();
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}

// ==== VINTED ====

/*
 * Καθαρή ανάλυση δεδομένων Vinted (χωρίς Swing / jsoup), ώστε να δοκιμάζεται μόνη της.
 *
 * Οι σελίδες του Vinted (Next.js) κρατούν όλα τα δεδομένα σε κομμάτια
 * self.__next_f.push([1,"..."]). Ενώνοντάς τα βγαίνει ένα κείμενο που περιέχει:
 *  - σελίδα αναζήτησης: "items":{"items":[{"id":..,"productItem":{...}}]} και "pagination":{...}
 *  - σελίδα αγγελίας:   "lines":[...] (τίτλος/κατάσταση/επωνυμία), "attributes":[...],
 *                       {"data":{"description":"..."}}, "user_info":[...], "favourite_count":N
 */
final class VintedTools {
    private VintedTools() {
    }

    // ISO -> ελληνικό όνομα χώρας (αν το Vinted δώσει μόνο κωδικό).
    private static final String[][] COUNTRIES = {
            {"GR", "Ελλάδα"}, {"CY", "Κύπρος"}, {"BG", "Βουλγαρία"}, {"RO", "Ρουμανία"},
            {"HR", "Κροατία"}, {"RS", "Σερβία"}, {"SI", "Σλοβενία"}, {"IT", "Ιταλία"},
            {"FR", "Γαλλία"}, {"DE", "Γερμανία"}, {"ES", "Ισπανία"}, {"PT", "Πορτογαλία"},
            {"NL", "Ολλανδία"}, {"BE", "Βέλγιο"}, {"LU", "Λουξεμβούργο"}, {"AT", "Αυστρία"},
            {"CZ", "Τσεχία"}, {"SK", "Σλοβακία"}, {"PL", "Πολωνία"}, {"LT", "Λιθουανία"},
            {"LV", "Λετονία"}, {"EE", "Εσθονία"}, {"HU", "Ουγγαρία"}, {"SE", "Σουηδία"},
            {"DK", "Δανία"}, {"FI", "Φινλανδία"}, {"IE", "Ιρλανδία"}, {"GB", "Ηνωμένο Βασίλειο"},
            {"UK", "Ηνωμένο Βασίλειο"}, {"US", "ΗΠΑ"}, {"CH", "Ελβετία"}, {"NO", "Νορβηγία"},
            {"MT", "Μάλτα"}, {"AL", "Αλβανία"}, {"MK", "Βόρεια Μακεδονία"}, {"TR", "Τουρκία"}
    };

    // true = αγγλικά ονόματα χωρών (ορίζεται από το tab Vinted).
    static volatile boolean english = false;

    static String countryName(String raw) {
        if (raw == null) return "";
        String v = raw.trim();
        if (v.length() == 2) {
            if (english) {
                String code = v.equalsIgnoreCase("UK") ? "GB" : v.toUpperCase(Locale.ROOT);
                String name = new Locale("", code).getDisplayCountry(Locale.ENGLISH);
                if (!name.isEmpty() && !name.equalsIgnoreCase(code)) return name;
            }
            for (String[] c : COUNTRIES) if (c[0].equalsIgnoreCase(v)) return c[1];
        }
        return v;
    }

    // ------------------------------------------------------------------
    // Flight data (το JSON μέσα στα script της σελίδας)
    // ------------------------------------------------------------------

    // Ένα script: self.__next_f.push([1,"...."]) -> το αποκωδικοποιημένο κείμενο.
    static String decodeFlightChunk(String scriptData) {
        if (scriptData == null) return "";
        int start = scriptData.indexOf("[1,\"");
        if (start < 0) return "";
        StringBuilder raw = new StringBuilder();
        for (int i = start + 4; i < scriptData.length(); i++) {
            char c = scriptData.charAt(i);
            if (c == '\\' && i + 1 < scriptData.length()) {
                raw.append(c).append(scriptData.charAt(++i));
                continue;
            }
            if (c == '"') break;
            raw.append(c);
        }
        return unescape(raw.toString());
    }

    static String unescape(String s) {
        if (s.indexOf('\\') < 0) return s;
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                out.append(c);
                continue;
            }
            char n = s.charAt(++i);
            switch (n) {
                case 'n': out.append('\n'); break;
                case 't': out.append('\t'); break;
                case 'r': out.append('\r'); break;
                case 'b': out.append('\b'); break;
                case 'f': out.append('\f'); break;
                case 'u':
                    if (i + 4 < s.length()) {
                        try {
                            out.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                            i += 4;
                            break;
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    out.append('u');
                    break;
                default: out.append(n);
            }
        }
        return out.toString();
    }

    // ------------------------------------------------------------------
    // Μικρός JSON parser (Map / List / String / Long / Double / Boolean / null)
    // ------------------------------------------------------------------

    static Object parseAt(String s, int pos) {
        try {
            return new Json(s, pos).value();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    // Διαβάζει το JSON που ξεκινά αμέσως μετά το marker (π.χ. "\"lines\":").
    static Object parseAfter(String text, String marker) {
        return parseAfter(text, marker, 0);
    }

    static Object parseAfter(String text, String marker, int from) {
        int i = text.indexOf(marker, from);
        if (i < 0) return null;
        return parseAt(text, i + marker.length());
    }

    private static final class Json {
        private final String s;
        private int p;

        Json(String s, int pos) {
            this.s = s;
            this.p = pos;
        }

        void ws() {
            while (p < s.length() && Character.isWhitespace(s.charAt(p))) p++;
        }

        Object value() {
            ws();
            char c = s.charAt(p);
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': p += 4; return Boolean.TRUE;
                case 'f': p += 5; return Boolean.FALSE;
                case 'n': p += 4; return null;
                default: return number();
            }
        }

        Map<String, Object> object() {
            Map<String, Object> map = new LinkedHashMap<>();
            p++; // {
            ws();
            if (s.charAt(p) == '}') { p++; return map; }
            while (true) {
                ws();
                String key = string();
                ws();
                if (s.charAt(p++) != ':') throw new IllegalStateException("':' expected");
                map.put(key, value());
                ws();
                char c = s.charAt(p++);
                if (c == '}') return map;
                if (c != ',') throw new IllegalStateException("',' expected");
            }
        }

        List<Object> array() {
            List<Object> list = new ArrayList<>();
            p++; // [
            ws();
            if (s.charAt(p) == ']') { p++; return list; }
            while (true) {
                list.add(value());
                ws();
                char c = s.charAt(p++);
                if (c == ']') return list;
                if (c != ',') throw new IllegalStateException("',' expected");
            }
        }

        String string() {
            if (s.charAt(p) != '"') throw new IllegalStateException("string expected");
            int start = ++p;
            boolean escaped = false;
            while (p < s.length()) {
                char c = s.charAt(p);
                if (c == '\\') { escaped = true; p += 2; continue; }
                if (c == '"') break;
                p++;
            }
            String raw = s.substring(start, p);
            p++; // closing quote
            return escaped ? unescape(raw) : raw;
        }

        Object number() {
            int start = p;
            while (p < s.length() && "+-0123456789.eE".indexOf(s.charAt(p)) >= 0) p++;
            String t = s.substring(start, p);
            if (t.isEmpty()) throw new IllegalStateException("value expected");
            try {
                return Long.parseLong(t);
            } catch (NumberFormatException ex) {
                return Double.parseDouble(t);
            }
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Collections.<String, Object>emptyMap();
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object o) {
        return o instanceof List ? (List<Object>) o : Collections.<Object>emptyList();
    }

    static String str(Object o) {
        if (o == null) return "";
        String v = o instanceof Double && ((Double) o) == Math.rint((Double) o) && Math.abs((Double) o) < 1e15
                ? Long.toString(((Double) o).longValue()) : String.valueOf(o);
        return "$undefined".equals(v) ? "" : v.trim();
    }

    static Double dbl(Object o) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        String v = str(o);
        if (v.isEmpty()) return null;
        try {
            return Double.parseDouble(v.replace(',', '.'));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Σελίδα αναζήτησης
    // ------------------------------------------------------------------

    static final class Item {
        String id = "", title = "", url = "", brand = "", condition = "", userId = "";
        Double price, total, fee;
        int favourites = -1;
        boolean business, promoted;
    }

    static List<Item> parseSearchItems(String flight) {
        List<Item> out = new ArrayList<>();
        Object parsed = parseAfter(flight, "\"items\":{\"items\":");
        for (Object o : list(parsed)) {
            Map<String, Object> entry = map(o);
            Map<String, Object> p = map(entry.get("productItem"));
            if (p.isEmpty()) continue;
            Item it = new Item();
            it.id = str(p.get("id"));
            it.title = str(p.get("title"));
            it.url = str(p.get("url"));
            if (it.url.isEmpty() || it.title.isEmpty()) continue;
            Object fav = p.get("favouriteCount");
            it.favourites = fav instanceof Number ? ((Number) fav).intValue() : -1;
            it.price = dbl(map(p.get("price")).get("amount"));
            Double discounted = dbl(map(p.get("priceWithDiscount")).get("amount"));
            if (discounted != null && discounted > 0) it.price = discounted;
            it.fee = dbl(map(p.get("serviceFee")).get("amount"));
            it.total = dbl(map(p.get("totalItemPrice")).get("amount"));
            Map<String, Object> user = map(p.get("user"));
            it.userId = str(user.get("id"));
            it.business = Boolean.TRUE.equals(user.get("isBusiness"));
            it.promoted = Boolean.TRUE.equals(p.get("isPromoted"));
            Map<String, Object> box = map(p.get("itemBox"));
            it.brand = str(box.get("firstLine"));
            it.condition = str(box.get("secondLine"));
            out.add(it);
        }
        return out;
    }

    // {total_pages, total_entries, per_page}. -1 όταν λείπουν.
    static int[] parsePagination(String flight) {
        int i = flight.indexOf("\"pagination\":{\"current_page\"");
        int[] r = {-1, -1, -1};
        if (i < 0) return r;
        Map<String, Object> m = map(parseAt(flight, i + "\"pagination\":".length()));
        r[0] = intOr(m.get("total_pages"), -1);
        r[1] = intOr(m.get("total_entries"), -1);
        r[2] = intOr(m.get("per_page"), -1);
        return r;
    }

    private static int intOr(Object o, int fallback) {
        return o instanceof Number ? ((Number) o).intValue() : fallback;
    }

    // ------------------------------------------------------------------
    // Σελίδα αγγελίας
    // ------------------------------------------------------------------

    static final class Detail {
        // Τίτλος όπως τον δείχνει η σελίδα (στη γλώσσα του site): lines[0] -> style "title".
        String title = "";
        String condition = "", brand = "", description = "", uploaded = "";
        // Τοποθεσία πωλητή όπως τη δίνει το Vinted (π.χ. "Ζάγκρεμπ, Κροατία"), αν είναι δημόσια.
        String location = "", country = "", city = "";
        String sellerId = "", price = "";
        int favourites = -1;
        Map<String, String> attributes = new LinkedHashMap<>();
    }

    static Detail parseItemPage(String flight) {
        Detail d = new Detail();

        // 1) summary: [{"elements":[{"style":"title","type":"text","value":"..."}],"max_lines":4},
        //              {"elements":[{"style":"body","type":"text","value":"Πολύ καλό"},
        //                           {"code":"summary_brand",...,"type":"navigational","value":"MSI"}],...}]
        int linesAt = flight.indexOf("\"lines\":[{\"elements\":[{\"style\":\"title\"");
        List<Object> lines = linesAt < 0
                ? Collections.<Object>emptyList()
                : list(parseAt(flight, linesAt + "\"lines\":".length()));
        for (int li = 0; li < lines.size(); li++) {
            List<Object> elements = list(map(lines.get(li)).get("elements"));
            StringBuilder text = new StringBuilder();
            for (Object eo : elements) {
                Map<String, Object> e = map(eo);
                String type = str(e.get("type"));
                String value = str(e.get("value"));
                if (value.isEmpty()) continue;
                if ("title".equals(str(e.get("style"))) && d.title.isEmpty()) {
                    d.title = value;
                } else if ("navigational".equals(type) && str(e.get("code")).contains("brand")) {
                    d.brand = value;
                } else if ("text".equals(type) && li > 0) {
                    if (text.length() > 0) text.append(' ');
                    text.append(value);
                }
            }
            if (li > 0 && d.condition.isEmpty() && text.length() > 0) d.condition = text.toString();
        }

        // 2) attributes: [{"code":"brand","data":{"title":"Επωνυμία","value":"MSI"}}, ...]
        int a = flight.indexOf("\"attributes\":[{\"code\":");
        if (a >= 0) {
            for (Object o : list(parseAt(flight, a + "\"attributes\":".length()))) {
                Map<String, Object> m = map(o);
                String code = str(m.get("code"));
                String value = str(map(m.get("data")).get("value"));
                if (!code.isEmpty() && !value.isEmpty()) d.attributes.put(code, value);
            }
            if (d.condition.isEmpty()) d.condition = d.attributes.getOrDefault("status", "");
            if (d.brand.isEmpty()) d.brand = d.attributes.getOrDefault("brand", "");
            d.uploaded = d.attributes.getOrDefault("upload_date", "");
            for (String key : new String[]{"location", "country", "city"}) {
                if (d.location.isEmpty() && d.attributes.containsKey(key)) d.location = d.attributes.get(key);
            }
        }

        // 3) Περιγραφή (όπως τη δείχνει η σελίδα, μεταφρασμένη αν χρειάζεται).
        int de = flight.indexOf("{\"data\":{\"description\":\"");
        if (de >= 0) {
            d.description = str(parseAt(flight, de + "{\"data\":{\"description\":".length()));
        }

        // 4) Πωλητής: user_info -> τοποθεσία (μόνο όταν ο πωλητής την εμφανίζει).
        int ui = flight.indexOf("\"user_info\":[");
        if (ui >= 0) {
            for (Object o : list(parseAt(flight, ui + "\"user_info\":".length()))) {
                Map<String, Object> m = map(o);
                String key = str(m.get("key"));
                String icon = str(m.get("icon"));
                if (key.contains("location") || icon.startsWith("Location") || icon.startsWith("Pin")) {
                    d.location = str(m.get("text"));
                    break;
                }
            }
        }
        splitLocation(d);

        // 5) Αριθμοί / τιμή / πωλητής.
        java.util.regex.Matcher fav = java.util.regex.Pattern
                .compile("\"favourite_count\":(\\d+)").matcher(flight);
        if (fav.find()) d.favourites = Integer.parseInt(fav.group(1));
        java.util.regex.Matcher price = java.util.regex.Pattern
                .compile("\"price\":\\{\"amount\":\"([0-9.]+)\",\"currency_code\"").matcher(flight);
        if (price.find()) d.price = price.group(1);
        java.util.regex.Matcher seller = java.util.regex.Pattern
                .compile("\"seller_id\":\"(\\d+)\"").matcher(flight);
        if (seller.find()) d.sellerId = seller.group(1);
        return d;
    }

    // "Ζάγκρεμπ, Κροατία" -> city / country (το τελευταίο κομμάτι είναι η χώρα).
    private static void splitLocation(Detail d) {
        if (d.location.isEmpty()) return;
        String[] parts = d.location.split("\\s*,\\s*");
        d.country = countryName(parts[parts.length - 1]);
        d.city = parts.length > 1 ? parts[0] : "";
    }

    // Χώρα από απάντηση του API (/api/v2/users/ID ή /api/v2/items/ID): πρώτα ο χρήστης, μετά το είδος.
    // Το Vinted δίνει country_title_local / country_title / country_iso_code (μόνο αν ο πωλητής
    // εμφανίζει την τοποθεσία του), ενώ το country_id είναι αριθμός και αγνοείται.
    static String countryFromApiJson(String json) {
        Map<String, Object> m = map(parseAt(json == null ? "" : json, firstBrace(json)));
        Map<String, Object> item = map(m.get("item"));
        List<Map<String, Object>> scopes = new ArrayList<>();
        scopes.add(map(m.get("user")));
        scopes.add(map(item.get("user")));
        scopes.add(item);
        scopes.add(m);
        String[] keys = {"country_title_local", "country_title", "country_iso_code", "country_code", "country"};
        for (Map<String, Object> scope : scopes) {
            for (String k : keys) {
                String v = str(scope.get(k));
                if (!v.isEmpty() && !v.matches("\\d+")) return countryName(v);
            }
        }
        return "";
    }

    private static int firstBrace(String s) {
        int i = s == null ? -1 : s.indexOf('{');
        return Math.max(i, 0);
    }

    // ------------------------------------------------------------------
    // Φίλτρα Vinted -> παράμετροι URL
    // ------------------------------------------------------------------

    // Παράμετροι που κρατιούνται όταν ο χρήστης επικολλά ολόκληρο URL του Vinted
    // (το search_id / time / page / search_text παραλείπονται).
    private static final Set<String> KEEP = new LinkedHashSet<>(Arrays.asList(
            "catalog[]", "brand_ids[]", "status_ids[]", "color_ids[]", "size_ids[]",
            "material_ids[]", "price_from", "price_to", "currency", "order"));

    // URL ή query string -> λίστα "κλειδί=τιμή" μόνο με φίλτρα Vinted.
    static List<String> filterPairs(String urlOrQuery) {
        List<String> out = new ArrayList<>();
        if (urlOrQuery == null || urlOrQuery.isBlank()) return out;
        String text = urlOrQuery.trim();
        int sp = text.indexOf(' ');
        if (sp > 0 && text.startsWith("http")) text = text.substring(0, sp);

        // /catalog/3602-graphics-cards -> catalog[]=3602
        java.util.regex.Matcher path = java.util.regex.Pattern.compile("/catalog/(\\d+)").matcher(text);
        if (path.find()) out.add("catalog[]=" + path.group(1));

        int q = text.indexOf('?');
        String query = q >= 0 ? text.substring(q + 1) : (text.startsWith("http") ? "" : text);
        int hash = query.indexOf('#');
        if (hash >= 0) query = query.substring(0, hash);
        for (String part : query.split("&")) {
            if (part.isBlank()) continue;
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String key = decode(part.substring(0, eq));
            String value = decode(part.substring(eq + 1));
            if (key.equals("catalog")) key = "catalog[]";
            if (!KEEP.contains(key) || value.isBlank()) continue;
            String pair = key + "=" + value;
            if (!out.contains(pair)) out.add(pair);
        }
        return out;
    }

    static String encodePair(String pair) {
        int eq = pair.indexOf('=');
        String key = pair.substring(0, eq).replace("[]", "%5B%5D");
        String value = java.net.URLEncoder.encode(pair.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8);
        return key + "=" + value;
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (RuntimeException ex) {
            return s.trim();
        }
    }

    // Ο αριθμός σε απλή μορφή για το URL (10 αντί για 10.0).
    static String plainNumber(double value) {
        return new java.math.BigDecimal(value).setScale(2, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }
}

// ==== FACEBOOK MARKETPLACE TOOLS ====

/*
 * Facebook Marketplace (tabs 7 και 8). Το HTML της σελίδας αναζήτησης / αγγελίας περιέχει τα δεδομένα
 * ως καθαρό JSON μέσα σε <script type="application/json">. Εδώ το διαβάζουμε χωρίς jsoup,
 * ώστε να δουλεύει και σε αποθηκευμένες σελίδες (Save as… από τον browser).
 */
final class FacebookTools {
    private FacebookTools() {
    }

    static final String BASE = "https://www.facebook.com";
    static final String DEFAULT_CITY = "105807349460179";

    static final class Item {
        String id = "", title = "", url = "", city = "", seller = "", description = "";
        String condition = "", conditionCode = "", delivery = "";
        Double price;
        long created;
        boolean sold;
        boolean detailed;
    }

    // ---------------- Μικρός JSON parser ----------------

    private static final class Json {
        private final String s;
        private int p;

        Json(String s) {
            this.s = s;
        }

        Object parse() {
            Object v = value();
            return v;
        }

        private void ws() {
            while (p < s.length() && Character.isWhitespace(s.charAt(p))) p++;
        }

        private Object value() {
            ws();
            char c = s.charAt(p);
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': p += 4; return Boolean.TRUE;
                case 'f': p += 5; return Boolean.FALSE;
                case 'n': p += 4; return null;
                default: return number();
            }
        }

        private Map<String, Object> object() {
            Map<String, Object> map = new LinkedHashMap<>();
            p++;
            ws();
            if (s.charAt(p) == '}') { p++; return map; }
            while (true) {
                ws();
                String key = string();
                ws();
                if (s.charAt(p++) != ':') throw new IllegalStateException("':' expected");
                map.put(key, value());
                ws();
                char c = s.charAt(p++);
                if (c == '}') return map;
                if (c != ',') throw new IllegalStateException("',' expected");
            }
        }

        private List<Object> array() {
            List<Object> list = new ArrayList<>();
            p++;
            ws();
            if (s.charAt(p) == ']') { p++; return list; }
            while (true) {
                list.add(value());
                ws();
                char c = s.charAt(p++);
                if (c == ']') return list;
                if (c != ',') throw new IllegalStateException("',' expected");
            }
        }

        private String string() {
            if (s.charAt(p) != '"') throw new IllegalStateException("string expected");
            int start = ++p;
            boolean escaped = false;
            while (p < s.length()) {
                char c = s.charAt(p);
                if (c == '\\') { escaped = true; p += 2; continue; }
                if (c == '"') break;
                p++;
            }
            String raw = s.substring(start, p);
            p++;
            return escaped ? unescape(raw) : raw;
        }

        private Object number() {
            int start = p;
            while (p < s.length() && "+-0123456789.eE".indexOf(s.charAt(p)) >= 0) p++;
            String t = s.substring(start, p);
            if (t.isEmpty()) throw new IllegalStateException("value expected");
            try {
                return Long.parseLong(t);
            } catch (NumberFormatException ex) {
                return Double.parseDouble(t);
            }
        }

        private static String unescape(String raw) {
            StringBuilder sb = new StringBuilder(raw.length());
            for (int i = 0; i < raw.length(); i++) {
                char c = raw.charAt(i);
                if (c != '\\' || i + 1 >= raw.length()) { sb.append(c); continue; }
                char n = raw.charAt(++i);
                switch (n) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u':
                        if (i + 4 < raw.length()) {
                            sb.append((char) Integer.parseInt(raw.substring(i + 1, i + 5), 16));
                            i += 4;
                        }
                        break;
                    default: sb.append(n);
                }
            }
            return sb.toString();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Collections.<String, Object>emptyMap();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o) {
        return o instanceof List ? (List<Object>) o : Collections.<Object>emptyList();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static Double dbl(Object o) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        String v = str(o);
        if (v.isEmpty()) return null;
        try {
            return Double.parseDouble(v.replace(',', '.'));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    // ---------------- Εξαγωγή JSON από το HTML ----------------

    private static final Pattern SCRIPT = Pattern.compile("(?is)<script[^>]*>(.*?)</script>");

    private static List<Object> jsonBlocks(String html, String mustContain) {
        List<Object> out = new ArrayList<>();
        if (html == null) return out;
        Matcher m = SCRIPT.matcher(html);
        while (m.find()) {
            String body = m.group(1);
            if (!body.contains(mustContain)) continue;
            String t = body.trim();
            if (t.isEmpty() || (t.charAt(0) != '{' && t.charAt(0) != '[')) continue;
            try {
                out.add(new Json(t).parse());
            } catch (RuntimeException ignored) {
                // Μπλοκ που δεν είναι έγκυρο JSON: το προσπερνάμε.
            }
        }
        return out;
    }

    // ---------------- Σελίδα αναζήτησης ----------------

    static List<Item> parseSearch(String html) {
        Map<String, Item> out = new LinkedHashMap<>();
        for (Object root : jsonBlocks(html, "marketplace_listing_title")) {
            collect(root, out);
        }
        return new ArrayList<>(out.values());
    }

    private static void collect(Object o, Map<String, Item> out) {
        if (o instanceof Map) {
            Map<String, Object> m = map(o);
            if (m.containsKey("marketplace_listing_title") && m.containsKey("listing_price")
                    && !str(m.get("id")).isEmpty()) {
                Item it = fromMap(m, str(m.get("id")));
                if (it != null) out.putIfAbsent(it.id, it);
            }
            for (Object v : m.values()) collect(v, out);
        } else if (o instanceof List) {
            for (Object v : list(o)) collect(v, out);
        }
    }

    private static Item fromMap(Map<String, Object> m, String id) {
        Item it = new Item();
        it.id = id;
        it.url = BASE + "/marketplace/item/" + id + "/";
        it.title = str(m.get("marketplace_listing_title"));
        if (it.title.isEmpty()) it.title = str(m.get("custom_title"));
        if (it.title.isEmpty()) return null;

        Double amount = dbl(map(m.get("listing_price")).get("amount"));
        it.price = amount != null && amount > 0 ? amount : null;

        Map<String, Object> geo = map(map(m.get("location")).get("reverse_geocode"));
        it.city = str(geo.get("city"));
        if (it.city.isEmpty()) it.city = str(map(geo.get("city_page")).get("display_name"));
        if (it.city.isEmpty()) it.city = str(map(m.get("location")).get("city"));
        if (it.city.isEmpty()) it.city = firstPart(str(map(m.get("location_text")).get("text")));
        if (it.city.isEmpty()) {
            for (Object sub : list(m.get("custom_sub_titles_with_rendering_flags"))) {
                String t = firstPart(str(map(sub).get("subtitle")));
                if (!t.isEmpty()) {
                    it.city = t;
                    break;
                }
            }
        }

        Object created = m.get("creation_time");
        if (created instanceof Number) it.created = ((Number) created).longValue();
        it.sold = Boolean.TRUE.equals(m.get("is_sold"));
        it.delivery = deliveryText(m.get("delivery_types"));
        it.seller = str(map(m.get("marketplace_listing_seller")).get("name"));
        applyCondition(it, m.get("attribute_data"));
        return it;
    }

    // «Athens, Greece» -> «Athens».
    private static String firstPart(String s) {
        if (s == null) return "";
        int comma = s.indexOf(',');
        return (comma > 0 ? s.substring(0, comma) : s).trim();
    }

    private static void applyCondition(Item it, Object attributes) {
        for (Object a : list(attributes)) {
            Map<String, Object> attr = map(a);
            if ("Condition".equalsIgnoreCase(str(attr.get("attribute_name")))) {
                it.conditionCode = str(attr.get("value"));
                it.condition = conditionName(it.conditionCode, str(attr.get("label")));
            }
        }
    }

    static String conditionName(String code, String fallback) {
        switch (code) {
            case "new": return "Καινούριο";
            case "used_like_new": return "Μεταχειρισμένο – σαν καινούριο";
            case "used_good": return "Μεταχειρισμένο – καλή";
            case "used_fair": return "Μεταχειρισμένο – μέτρια";
            default: return fallback;
        }
    }

    private static String deliveryText(Object types) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (Object t : list(types)) {
            switch (str(t)) {
                case "IN_PERSON": names.add("Παραλαβή"); break;
                case "PUBLIC_MEETUP": names.add("Συνάντηση"); break;
                case "DOOR_PICKUP": names.add("Παραλαβή από το σπίτι"); break;
                case "DOOR_DROPOFF": names.add("Παράδοση στο σπίτι"); break;
                case "SHIPPING_OFFSITE":
                case "SHIPPING": names.add("Αποστολή"); break;
                default: if (!str(t).isEmpty()) names.add(str(t));
            }
        }
        return String.join(", ", names);
    }

    // ---------------- Σελίδα αγγελίας ----------------

    /*
     * Η σελίδα αγγελίας: περιγραφή (redacted_description), τοποθεσία (location_text),
     * κατάσταση (attribute_data), ώρα ανάρτησης. Τίτλος: «Marketplace – <τίτλος>».
     */
    static Item parseDetail(String html) {
        for (Object root : jsonBlocks(html, "redacted_description")) {
            Map<String, Object> m = find(root, "redacted_description");
            if (m == null) continue;

            Item it = new Item();
            it.detailed = true;
            it.description = str(map(m.get("redacted_description")).get("text"));
            String loc = str(map(m.get("location_text")).get("text"));
            int comma = loc.indexOf(',');
            it.city = (comma > 0 ? loc.substring(0, comma) : loc).trim();
            it.title = str(m.get("marketplace_listing_title"));
            Double amount = dbl(map(m.get("listing_price")).get("amount"));
            it.price = amount != null && amount > 0 ? amount : null;
            Object created = m.get("creation_time");
            if (created instanceof Number) it.created = ((Number) created).longValue();
            it.sold = Boolean.TRUE.equals(m.get("is_sold"));
            it.delivery = deliveryText(m.get("delivery_types"));
            it.seller = str(map(m.get("marketplace_listing_seller")).get("name"));
            applyCondition(it, m.get("attribute_data"));
            if (it.title.isEmpty()) it.title = pageTitle(html);
            return it;
        }
        return null;
    }

    private static Map<String, Object> find(Object o, String key) {
        if (o instanceof Map) {
            Map<String, Object> m = map(o);
            if (m.get(key) instanceof Map) return m;
            for (Object v : m.values()) {
                Map<String, Object> r = find(v, key);
                if (r != null) return r;
            }
        } else if (o instanceof List) {
            for (Object v : list(o)) {
                Map<String, Object> r = find(v, key);
                if (r != null) return r;
            }
        }
        return null;
    }

    // <title>Marketplace – 🔥 GAMING PC RTX 3050 ...</title>
    static String pageTitle(String html) {
        Matcher m = Pattern.compile("(?is)<title[^>]*>(.*?)</title>").matcher(html == null ? "" : html);
        if (!m.find()) return "";
        String t = m.group(1).trim();
        for (String prefix : new String[]{"Marketplace – ", "Marketplace - ", "Marketplace – "}) {
            if (t.startsWith(prefix)) return t.substring(prefix.length()).trim();
        }
        return t.equals("Facebook") ? "" : t;
    }

    static boolean looksLikeLoginWall(String html) {
        if (html == null) return false;
        return html.contains("login_form") || html.contains("id=\"loginbutton\"")
                || html.contains("/login/?next=") || html.contains("checkpoint");
    }

    // Φίλτρα Marketplace που επιτρέπονται όταν επικολλάται ολόκληρο URL (tab 8).
    private static final Set<String> FILTER_KEYS = new LinkedHashSet<>(Arrays.asList(
            "minPrice", "maxPrice", "daysSinceListed", "itemCondition", "availability",
            "deliveryMethod", "sortBy", "radius"));

    static List<String> filterParams(String typedUrl) {
        List<String> out = new ArrayList<>();
        String t = typedUrl == null ? "" : typedUrl.trim();
        int q = t.indexOf('?');
        if (q < 0) return out;
        int hash = t.indexOf('#', q);
        String query = t.substring(q + 1, hash < 0 ? t.length() : hash);
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            String key = pair.substring(0, eq);
            if (FILTER_KEYS.contains(key) && eq + 1 < pair.length()) out.add(pair);
        }
        return out;
    }

    // ---------------- Χρόνος ----------------

    static String formatTime(long epochSeconds) {
        if (epochSeconds <= 0) return "";
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .format(java.time.Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.of("Europe/Athens")));
    }

    // ---------------- URL αναζήτησης ----------------

    /*
     * https://www.facebook.com/marketplace/<πόλη>/search/?query=...&minPrice=...&maxPrice=...
     * &daysSinceListed=7&itemCondition=new,used_like_new&availability=in%20stock
     * &deliveryMethod=local_pick_up&sortBy=price_ascend&radius=20
     * Το «place» δέχεται ID πόλης (π.χ. 105807349460179) ή ολόκληρο URL Marketplace.
     */
    static String searchUrl(String place, String query, List<String> params) {
        String city = cityId(place);
        StringBuilder sb = new StringBuilder(BASE).append("/marketplace/").append(city)
                .append("/search/?query=")
                .append(URLEncoder.encode(query, StandardCharsets.UTF_8).replace("+", "%20"));
        for (String pair : params) sb.append('&').append(pair);
        sb.append("&exact=false");
        return sb.toString();
    }

    static String cityId(String place) {
        String t = place == null ? "" : place.trim();
        Matcher m = Pattern.compile("/marketplace/([^/?#]+)").matcher(t);
        boolean fromUrl = m.find();
        String id = fromUrl ? m.group(1) : t;
        if (id.isEmpty() || id.equals("search") || id.equals("item")) return DEFAULT_CITY;
        // Όνομα πόλης (Αθήνα, Thessaloniki ...) -> το slug που δέχεται το Marketplace στο URL.
        if (!fromUrl && !id.matches("\\d+")) {
            String slug = citySlug(id);
            return slug.isEmpty() ? DEFAULT_CITY : slug;
        }
        return id;
    }

    // ---------------- Ονόματα πόλεων ----------------

    private static final Map<String, String> CITY_ALIASES = new HashMap<>();

    static {
        // {slug Marketplace, ονόματα χωρίς τόνους (ελληνικά / λατινικά)...}
        String[][] groups = {
                {"athens", "αθηνα", "athina", "athens"},
                {"thessaloniki", "θεσσαλονικη", "thessaloniki", "salonica", "saloniki"},
                {"patras", "πατρα", "patra", "patras"},
                {"heraklion", "ηρακλειο", "irakleio", "iraklio", "heraklion"},
                {"larissa", "λαρισα", "larisa", "larissa"},
                {"volos", "βολος", "volos"},
                {"ioannina", "ιωαννινα", "ioannina", "giannena"},
                {"chania", "χανια", "chania", "hania"},
                {"rhodes", "ροδος", "rodos", "rhodes"},
                {"kavala", "καβαλα", "kavala"},
                {"alexandroupoli", "αλεξανδρουπολη", "alexandroupoli", "alexandroupolis"},
                {"serres", "σερρες", "serres"},
                {"corfu", "κερκυρα", "kerkyra", "corfu"},
                {"kalamata", "καλαματα", "kalamata"},
                {"trikala", "τρικαλα", "trikala"},
                {"agrinio", "αγρινιο", "agrinio"},
                {"lamia", "λαμια", "lamia"},
                {"chalkida", "χαλκιδα", "chalkida", "chalcis"},
                {"katerini", "κατερινη", "katerini"},
                {"kozani", "κοζανη", "kozani"},
                {"veria", "βεροια", "veria"},
                {"xanthi", "ξανθη", "xanthi"},
                {"komotini", "κομοτηνη", "komotini"},
                {"drama", "δραμα", "drama"},
                {"rethymno", "ρεθυμνο", "rethymno", "rethimno"},
                {"piraeus", "πειραιας", "peiraias", "piraeus"}
        };
        for (String[] g : groups) {
            CITY_ALIASES.put(g[0], g[0]);
            for (int i = 1; i < g.length; i++) CITY_ALIASES.put(g[i], g[0]);
        }
    }

    // Πεζά, χωρίς τόνους, χωρίς σημεία στίξης.
    private static String plainKey(String s) {
        String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replace('ς', 'σ');
        return n.replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    private static String transliterate(String s) {
        String t = s.replace("ου", "ou");
        String from = "αβγδεζηθικλμνξοπρσςτυφχψω";
        String[] to = {"a", "v", "g", "d", "e", "z", "i", "th", "i", "k", "l", "m", "n", "x", "o", "p", "r", "s", "s",
                "t", "y", "f", "ch", "ps", "o"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            int idx = from.indexOf(t.charAt(i));
            sb.append(idx >= 0 ? to[idx] : String.valueOf(t.charAt(i)));
        }
        return sb.toString();
    }

    // Κοινή μορφή για σύγκριση πόλεων: Αθήνα / Athens / «Athens, Greece» -> «athens».
    static String cityKey(String name) {
        String first = firstPart(name);
        String pk = plainKey(first);
        if (pk.isEmpty()) return "";
        String alias = CITY_ALIASES.get(pk);
        return alias != null ? alias : transliterate(pk);
    }

    static String citySlug(String typed) {
        return cityKey(typed).replace(' ', '-');
    }

    static boolean sameCity(String a, String b) {
        String ka = cityKey(a);
        String kb = cityKey(b);
        if (ka.isEmpty() || kb.isEmpty()) return false;
        if (ka.equals(kb)) return true;
        return ka.length() >= 4 && kb.length() >= 4 && (ka.contains(kb) || kb.contains(ka));
    }
}