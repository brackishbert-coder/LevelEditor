package levelEditor;

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.event.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;
import javax.xml.parsers.*;
import org.w3c.dom.*;

import levelEditor.LevelEditorPaintTool.RectExport;

/**
 * Enhanced Level Editor with all requested features: - Grid overlay, zoom,
 * minimap, event markers - Sprite previews, color picker, delete blocks,
 * import/export - Layers (background/main/foreground), multiple levels,
 * properties, rotation
 */
public class LevelEditorPaintTool extends JFrame {
	private static final int DEFAULT_WIDTH = 800, DEFAULT_HEIGHT = 600, PIXEL_SIZE = 16;
	private static final Path TEMPLATE_DIR = Paths.get("/home/wes/eclipse-workspace/levelEditor",
			"/home/wes/eclipse-workspace/levelEditor/level_editor",
			"/home/wes/eclipse-workspace/levelEditor/templates");
	boolean showExportRects = false;
	List<RectExport> lastExportRects = Collections.emptyList();

	private double zoomLevel = 1.5;
	private boolean showGrid = true, showCollisionBounds = false, showEventMarkers = true;

	private JTabbedPane levelTabs;
	private JPanel colorPalette, minimapPanel, currentColorDisplay;
	private JLabel zoomLabel;
	private JComboBox<String> layerSelector;

	private List<LevelData> levels = new ArrayList<>();
	private int levelCounter = 1;
	private BlockType currentBlockType;
	private String currentEventType = "story";
	PlacedBlock selectedBlock = null;
	Point dragOffset = null;

	enum ToolMode {
		PAINT, ERASE_RECT, SELECT
	}

	levelEditor.LevelEditorPaintTool.LevelData.PlacedBlock findBlockAt(LevelData ld, int x, int y) {
		for (int i = ld.blocks.size() - 1; i >= 0; i--) {
			levelEditor.LevelEditorPaintTool.LevelData.PlacedBlock b = ld.blocks.get(i);
			if (x >= b.x && x < b.x + b.width && y >= b.y && y < b.y + b.height) {
				return b;
			}
		}
		return null;
	}

	ToolMode currentTool = ToolMode.PAINT;
	Rectangle eraseRect = null;

	static class RectExport {
		int x, y, width, height;
		BlockType type;
		Map<String, String> attributes = new LinkedHashMap<>();

		RectExport(int x, int y, int w, int h, BlockType t, Map<String, String> attrs) {
			this.x = x;
			this.y = y;
			this.width = w;
			this.height = h;
			this.type = t;
			this.attributes.putAll(attrs);
		}
	}

	static class LevelData {

		String name = "", description = "";
		Map<String, String> metadata = new HashMap<>();
		BufferedImage backgroundLayer, mainLayer, foregroundLayer;
		List<GameEvent> events = new ArrayList<>();
		List<Rect> cachedCollisionRects = new ArrayList<>();
		int width, height;
		JPanel canvasPanel;
		JScrollPane scrollPane;

		static class PlacedBlock {
			BlockType type;
			int x, y;
			int width = PIXEL_SIZE;
			int height = PIXEL_SIZE;
			int rotation = 0;
			String layer = "Main"; // ✅ ADD THIS

			Map<String, String> attributes = new LinkedHashMap<>();

			PlacedBlock(BlockType type, int x, int y) {
				this(type, x, y, "Main");
			}

			PlacedBlock(BlockType type, int x, int y, String layer) {
				this.type = type;
				this.x = x;
				this.y = y;
				this.layer = layer;
				this.rotation = type.rotation;
				this.attributes.putAll(type.childNodes);
			}
		}

		List<PlacedBlock> blocks = new ArrayList<>();

		LevelData(String n, int w, int h) {
			name = n;
			width = w;
			height = h;
			backgroundLayer = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
			mainLayer = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
			foregroundLayer = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
			clearAll();
		}

		void clearAll() {
			clear(backgroundLayer);
			clear(mainLayer);
			clear(foregroundLayer);
			Graphics2D g = mainLayer.createGraphics();
			g.setColor(Color.WHITE);
			g.fillRect(0, 0, width, height);
			g.dispose();
		}

		void clear(BufferedImage img) {
			Graphics2D g = img.createGraphics();
			g.setComposite(AlphaComposite.Clear);
			g.fillRect(0, 0, img.getWidth(), img.getHeight());
			g.dispose();
		}

		BufferedImage getLayer(String n) {
			return n.equals("Background") ? backgroundLayer : n.equals("Foreground") ? foregroundLayer : mainLayer;
		}
	}

	static class GameEvent {
		String type, name;
		int x, y;

		GameEvent(String t, String n, int x, int y) {
			type = t;
			name = n;
			this.x = x;
			this.y = y;
		}
	}

	static class Rect {
		int x, y, w, h;
		BlockType type;
	}

	static class PlacedBlock {
		BlockType type;
		int x, y;
		int width = PIXEL_SIZE;
		int height = PIXEL_SIZE;
		int rotation = 0;

		Map<String, String> attributes = new LinkedHashMap<>();

		PlacedBlock(BlockType type, int x, int y) {
			this.type = type;
			this.x = x;
			this.y = y;
			this.rotation = type.rotation;

			// copy defaults from BlockType template
			this.attributes.putAll(type.childNodes);
		}
	}

	public static class BlockType {
		private static final List<BlockType> REGISTRY = new ArrayList<>();
		public static final BlockType UNKNOWN = new BlockType("Unknown", "unknown", Color.PINK, null, 0, 0);
		public static final BlockType EMPTY = new BlockType("Empty", "empty", Color.WHITE, null, 0, 0);

		String displayName, xmlName, spritePath;
		Color canonicalColor;
		BufferedImage spriteImage;
		boolean custom = false;
		int rotation = 0;
		final Map<String, String> attributes = new LinkedHashMap<>();
		final Map<String, String> childNodes = new LinkedHashMap<>();
		public boolean solid;
		private int width;
		private int height;

		public BlockType(String dn, String xn, Color c, String sp, int width2, int hight) {
			displayName = dn;
			xmlName = xn;
			canonicalColor = c;
			spritePath = sp;
			width = width2;
			this.height = hight;
			loadSprite();
			initTemplate();
		}

		static List<Map<String, Object>> parseJsonArrayOfObjects(String json) {
			String s = json.trim();
			if (s.startsWith("["))
				s = s.substring(1);
			if (s.endsWith("]"))
				s = s.substring(0, s.length() - 1);

			List<Map<String, Object>> out = new ArrayList<>();

			int i = 0;
			while (i < s.length()) {
				int objStart = s.indexOf("{", i);
				if (objStart < 0)
					break;

				int objEnd = findMatchingBrace(s, objStart);
				if (objEnd < 0)
					break;

				String obj = s.substring(objStart, objEnd + 1);
				out.add(parseJsonObject(obj));

				i = objEnd + 1;
			}

			return out;
		}

		void loadSprite() {
			if (spritePath != null && !spritePath.isEmpty()) {
				try {
					spriteImage = ImageIO.read(new File(spritePath));
				} catch (Exception e) {
					spriteImage = null;
				}
			}
		}

		void initTemplate() {
			attributes.clear();
			childNodes.clear();
			attributes.put("type", xmlName);
			childNodes.put("width", "" + PIXEL_SIZE);
			childNodes.put("height", "" + PIXEL_SIZE);
			childNodes.put("scale", "100");
			childNodes.put("rotation", "0");
			if (spritePath != null)
				childNodes.put("sprite", spritePath);
		}

		public void setSpritePath(String sp) {
			spritePath = sp;
			loadSprite();
			if (sp != null && !sp.isEmpty())
				childNodes.put("sprite", sp);
			else
				childNodes.remove("sprite");
		}

		public void setColor(Color c) {
			canonicalColor = c;
		}

		public static void register(BlockType t) {
			REGISTRY.add(t);
		}

		public static List<BlockType> getRegistry() {
			return REGISTRY;
		}

		public static void remove(BlockType t) {
			REGISTRY.remove(t);
		}

		public static BlockType fromColor(Color c) {
			for (BlockType b : REGISTRY)
				if (colorEq(b.canonicalColor, c))
					return b;
			return c.getAlpha() == 0 || isWhite(c) ? EMPTY : UNKNOWN;
		}

		public static BlockType fromXml(String n) {
			for (BlockType b : REGISTRY)
				if (b.xmlName.equalsIgnoreCase(n))
					return b;
			return UNKNOWN;
		}

		static boolean colorEq(Color a, Color b) {
			return a.getRGB() == b.getRGB();
		}

		static boolean isWhite(Color c) {
			return c.getRed() == 255 && c.getGreen() == 255 && c.getBlue() == 255;
		}

		Path getTemplatePath() {
			return TEMPLATE_DIR.resolve(xmlName.replaceAll("[^a-zA-Z0-9_-]", "_") + ".json");
		}

		void save() throws IOException {
			Files.createDirectories(TEMPLATE_DIR);

			StringBuilder sb = new StringBuilder();
			sb.append("{");
			sb.append("\"displayName\":\"").append(esc(displayName)).append("\",");
			sb.append("\"xmlName\":\"").append(esc(xmlName)).append("\",");
			sb.append("\"color\":\"").append(String.format("#%02X%02X%02X", canonicalColor.getRed(),
					canonicalColor.getGreen(), canonicalColor.getBlue())).append("\",");
			sb.append("\"spritePath\":\"").append(esc(spritePath == null ? "" : spritePath)).append("\",");

			sb.append("\"childNodes\":").append(mapToJson(childNodes));
			sb.append("}");

			Files.write(getTemplatePath(), sb.toString().getBytes(StandardCharsets.UTF_8));
		}

		static String mapToJson(Map<String, String> map) {
			StringBuilder sb = new StringBuilder();
			sb.append("{");
			boolean first = true;
			for (Map.Entry<String, String> e : map.entrySet()) {
				if (!first)
					sb.append(",");
				sb.append("\"").append(esc(e.getKey())).append("\":\"").append(esc(e.getValue())).append("\"");
				first = false;
			}
			sb.append("}");
			return sb.toString();
		}

		static String asString(Object v, String def) {
			if (v == null)
				return def;
			String s = String.valueOf(v);
			return s == null ? def : s;
		}

		static Map<String, Object> parseJsonObject(String json) {
			json = json.trim();
			if (json.startsWith("{"))
				json = json.substring(1);
			if (json.endsWith("}"))
				json = json.substring(0, json.length() - 1);

			Map<String, Object> out = new LinkedHashMap<>();

			// Extract childNodes first (so commas inside it don't break top-level
			// splitting)
			int cnKey = json.indexOf("\"childNodes\"");
			String withoutChildNodes = json;

			if (cnKey >= 0) {
				int colon = json.indexOf(":", cnKey);
				int braceOpen = json.indexOf("{", colon);
				if (braceOpen >= 0) {
					int braceClose = findMatchingBrace(json, braceOpen);
					if (braceClose > braceOpen) {
						String cnBody = json.substring(braceOpen, braceClose + 1);
						out.put("childNodes", parseStringMap(cnBody));

						// remove that segment for top-level parsing
						withoutChildNodes = (json.substring(0, cnKey) + json.substring(braceClose + 1)).trim();
						withoutChildNodes = withoutChildNodes.replaceAll(",\\s*,", ",").replaceAll("^\\s*,", "")
								.replaceAll(",\\s*$", "");
					}
				}
			}

			for (String part : splitTopLevel(withoutChildNodes)) {
				if (part.isBlank())
					continue;
				String[] kv = part.split(":", 2);
				if (kv.length != 2)
					continue;

				String k = stripQuotes(kv[0].trim());
				String v = stripQuotes(kv[1].trim());
				out.put(unesc(k), unesc(v));
			}

			return out;
		}

		static Map<String, String> parseStringMap(String jsonObj) {
			String s = jsonObj.trim();
			if (s.startsWith("{"))
				s = s.substring(1);
			if (s.endsWith("}"))
				s = s.substring(0, s.length() - 1);

			Map<String, String> m = new LinkedHashMap<>();
			if (s.trim().isEmpty())
				return m;

			for (String part : splitTopLevel(s)) {
				if (part.isBlank())
					continue;
				String[] kv = part.split(":", 2);
				if (kv.length != 2)
					continue;

				String k = stripQuotes(kv[0].trim());
				String v = stripQuotes(kv[1].trim());
				m.put(unesc(k), unesc(v));
			}

			return m;
		}

		static int findMatchingBrace(String s, int openIndex) {
			int depth = 0;
			boolean inString = false;

			for (int i = openIndex; i < s.length(); i++) {
				char c = s.charAt(i);

				if (c == '"' && (i == 0 || s.charAt(i - 1) != '\\'))
					inString = !inString;
				if (inString)
					continue;

				if (c == '{')
					depth++;
				else if (c == '}') {
					depth--;
					if (depth == 0)
						return i;
				}
			}
			return -1;
		}

		static List<String> splitTopLevel(String s) {
			List<String> parts = new ArrayList<>();
			if (s == null)
				return parts;

			StringBuilder cur = new StringBuilder();
			boolean inString = false;
			int braceDepth = 0;

			for (int i = 0; i < s.length(); i++) {
				char c = s.charAt(i);

				if (c == '"' && (i == 0 || s.charAt(i - 1) != '\\'))
					inString = !inString;

				if (!inString) {
					if (c == '{')
						braceDepth++;
					else if (c == '}')
						braceDepth--;

					if (c == ',' && braceDepth == 0) {
						parts.add(cur.toString().trim());
						cur.setLength(0);
						continue;
					}
				}

				cur.append(c);
			}

			if (cur.length() > 0)
				parts.add(cur.toString().trim());
			return parts;
		}

		static String stripQuotes(String s) {
			s = s.trim();
			if (s.startsWith("\""))
				s = s.substring(1);
			if (s.endsWith("\""))
				s = s.substring(0, s.length() - 1);
			return s;
		}

		static BlockType loadFromTemplateFile(Path p) {
			try {
				String json = Files.readString(p, StandardCharsets.UTF_8).trim();
				Map<String, Object> root = parseJsonObject(json);

				String displayName = asString(root.get("displayName"), "");
				String xmlName = asString(root.get("xmlName"), "");
				String colorHex = asString(root.get("color"), "#FFFFFF");
				String spritePath = asString(root.get("spritePath"), "");

				if (xmlName.isEmpty()) {
					// fall back from filename if needed
					String fn = p.getFileName().toString();
					xmlName = fn.endsWith(".json") ? fn.substring(0, fn.length() - 5) : fn;
				}

				Color c = Color.decode(colorHex);

				BlockType bt = new BlockType(displayName.isEmpty() ? xmlName : displayName, xmlName, c,
						spritePath.isEmpty() ? null : spritePath, PIXEL_SIZE, PIXEL_SIZE);
				bt.custom = true;

				// Important: override template defaults with what was stored
				Object cn = root.get("childNodes");
				if (cn instanceof Map) {
					bt.childNodes.clear();
					@SuppressWarnings("unchecked")
					Map<String, String> m = (Map<String, String>) cn;
					bt.childNodes.putAll(m);
				}

				// Keep spritePath consistent with childNodes["sprite"]
				if (bt.spritePath != null && !bt.spritePath.isEmpty()) {
					bt.childNodes.put("sprite", bt.spritePath);
				}

				bt.loadSprite();
				return bt;

			} catch (Exception ex) {
				// If a template is corrupt, fail gracefully instead of crashing the editor
				return null;
			}
		}

		static String esc(String s) {
			return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
		}

		static String unesc(String s) {
			if (s == null)
				return "";
			return s.replace("\\\"", "\"").replace("\\\\", "\\");
		}

		public void setWidth(int x1) {
			width = x1;

		}

		public void setHeight(int y1) {
			height = y1;

		}
	}

	public LevelEditorPaintTool() {
		setTitle("Enhanced Level Editor");
		setDefaultCloseOperation(EXIT_ON_CLOSE);
		setLayout(new BorderLayout());

		try {
			Files.createDirectories(TEMPLATE_DIR);
		} catch (IOException e) {
		}

		initBlocks();
		currentBlockType = BlockType.EMPTY;

		levelTabs = new JTabbedPane();
		levelTabs.addChangeListener(e -> refresh());

		add(createToolbar(), BorderLayout.NORTH);
		add(levelTabs, BorderLayout.CENTER);
		add(createRightPanel(), BorderLayout.EAST);

		addNewLevel();
		setSize(1400, 900);
		setLocationRelativeTo(null);
	}

	static List<RectExport> mergeBlocksIntoRectangles(List<LevelData.PlacedBlock> blocks) {

		Map<Point, LevelData.PlacedBlock> grid = new HashMap<>();
		Set<Point> visited = new HashSet<>();
		List<RectExport> result = new ArrayList<>();

		// Build grid index
		for (LevelData.PlacedBlock b : blocks) {
			int gx = b.x / PIXEL_SIZE;
			int gy = b.y / PIXEL_SIZE;
			grid.put(new Point(gx, gy), b);
		}

		for (Point p : grid.keySet()) {
			if (visited.contains(p))
				continue;

			LevelData.PlacedBlock seed = grid.get(p);
			BlockType type = seed.type;

			int gx = p.x;
			int gy = p.y;

			// Expand width
			int w = 1;
			while (true) {
				Point np = new Point(gx + w, gy);
				LevelData.PlacedBlock nb = grid.get(np);
				if (nb == null || nb.type != type || visited.contains(np))
					break;
				w++;
			}

			// Expand height
			int h = 1;
			outer: while (true) {
				for (int dx = 0; dx < w; dx++) {
					Point np = new Point(gx + dx, gy + h);
					LevelData.PlacedBlock nb = grid.get(np);
					if (nb == null || nb.type != type || visited.contains(np))
						break outer;
				}
				h++;
			}

			// Mark visited
			for (int dx = 0; dx < w; dx++) {
				for (int dy = 0; dy < h; dy++) {
					visited.add(new Point(gx + dx, gy + dy));
				}
			}

			// Emit rectangle
			result.add(new RectExport(gx * PIXEL_SIZE, gy * PIXEL_SIZE, w * PIXEL_SIZE, h * PIXEL_SIZE, type,
					type.childNodes));
		}

		return result;
	}

	JPanel createToolbar() {
		JPanel tb = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 2));
		btn(tb, "New Level", e -> addNewLevel());
		btn(tb, "Save", e -> saveXML());
		btn(tb, "Load", e -> loadXML());
		btn(tb, "Clear Layer", e -> clearCurrentLayer());
		tb.add(new JSeparator(SwingConstants.VERTICAL));
		btn(tb, "Properties", e -> showProps());
		tb.add(new JSeparator(SwingConstants.VERTICAL));
		tb.add(new JLabel("Layer:"));
		layerSelector = new JComboBox<>(new String[] { "Background", "Main", "Foreground" });
		layerSelector.setSelectedItem("Main");
		layerSelector.addActionListener(e -> refresh());
		tb.add(layerSelector);
		tb.add(new JSeparator(SwingConstants.VERTICAL));
		btn(tb, "+", e -> zoom(0.25));
		btn(tb, "-", e -> zoom(-0.25));
		btn(tb, "100%", e -> setZoom(1.0));
		zoomLabel = new JLabel("100%");
		tb.add(zoomLabel);
		tb.add(new JSeparator(SwingConstants.VERTICAL));
		chk(tb, "Grid", showGrid, e -> {
			showGrid = ((JCheckBox) e.getSource()).isSelected();
			refresh();
		});
		chk(tb, "Collision", showCollisionBounds, e -> {
			showCollisionBounds = ((JCheckBox) e.getSource()).isSelected();
			refresh();
		});
		chk(tb, "Events", showEventMarkers, e -> {
			showEventMarkers = ((JCheckBox) e.getSource()).isSelected();
			refresh();
		});
		tb.add(new JSeparator(SwingConstants.VERTICAL));
		tb.add(new JLabel("Event:"));
		JComboBox<String> evType = new JComboBox<>(new String[] { "story", "LevelEnd", "checkpoint", "trigger" });
		evType.addActionListener(e -> currentEventType = (String) evType.getSelectedItem());
		tb.add(evType);
		tb.add(new JSeparator(SwingConstants.VERTICAL));
		btn(tb, "↻ 90°", e -> {
			currentBlockType.rotation = (currentBlockType.rotation + 90) % 360;
		});
		tb.add(new JSeparator(SwingConstants.VERTICAL));
		btn(tb, "New Block", e -> newBlockType());
		btn(tb, "Export Blocks", e -> exportBlocks());
		btn(tb, "Import Blocks", e -> importBlocks());
		tb.add(new JSeparator(SwingConstants.VERTICAL));
		tb.add(new JLabel("Current:"));
		currentColorDisplay = new JPanel();
		currentColorDisplay.setPreferredSize(new Dimension(30, 20));
		currentColorDisplay.setBorder(BorderFactory.createLineBorder(Color.BLACK));
		updateColor();
		tb.add(currentColorDisplay);
		return tb;
	}

	void btn(JPanel p, String t, ActionListener a) {
		JButton b = new JButton(t);
		b.addActionListener(a);
		p.add(b);
	}

	void chk(JPanel p, String t, boolean sel, ActionListener a) {
		JCheckBox c = new JCheckBox(t, sel);
		c.addActionListener(a);
		p.add(c);
	}

	JPanel createRightPanel() {
		JPanel right = new JPanel(new BorderLayout());
		colorPalette = new JPanel();
		colorPalette.setBorder(BorderFactory.createTitledBorder("Palette"));
		reloadPalette();
		JScrollPane ps = new JScrollPane(colorPalette);
		ps.setPreferredSize(new Dimension(250, 400));
		minimapPanel = new JPanel() {
			protected void paintComponent(Graphics g) {
				super.paintComponent(g);
				drawMinimap(g);
			}
		};
		minimapPanel.setBorder(BorderFactory.createTitledBorder("Mini-map"));
		minimapPanel.setPreferredSize(new Dimension(250, 200));
		minimapPanel.setBackground(Color.DARK_GRAY);
		right.add(ps, BorderLayout.CENTER);
		right.add(minimapPanel, BorderLayout.SOUTH);
		return right;
	}

	void reloadPalette() {
		colorPalette.removeAll();
		colorPalette.setLayout(new GridLayout(0, 1, 4, 4));
		for (BlockType bt : BlockType.getRegistry()) {
			JPanel item = new JPanel(new BorderLayout(4, 4));
			item.setBorder(BorderFactory.createLineBorder(Color.GRAY));
			item.setBackground(Color.WHITE);
			JPanel preview = new JPanel() {
				protected void paintComponent(Graphics g) {
					super.paintComponent(g);
					if (bt.spriteImage != null)
						g.drawImage(bt.spriteImage, 0, 0, getWidth(), getHeight(), null);
					else {
						g.setColor(bt.canonicalColor);
						g.fillRect(0, 0, getWidth(), getHeight());
					}
				}
			};
			preview.setPreferredSize(new Dimension(40, 40));
			preview.setBorder(BorderFactory.createLineBorder(Color.BLACK));
			JLabel lbl = new JLabel(bt.displayName);
			lbl.setFont(new Font("SansSerif", Font.BOLD, 11));
			item.add(preview, BorderLayout.WEST);
			item.add(lbl, BorderLayout.CENTER);
			item.addMouseListener(new MouseAdapter() {
				public void mousePressed(MouseEvent e) {
					if (SwingUtilities.isLeftMouseButton(e)) {
						currentBlockType = bt;
						updateColor();
					} else if (SwingUtilities.isRightMouseButton(e))
						showBlockMenu(bt, e.getComponent(), e.getX(), e.getY());
				}
			});
			colorPalette.add(item);
		}
		colorPalette.revalidate();
		colorPalette.repaint();
	}

	void showBlockMenu(BlockType bt, Component c, int x, int y) {
		JPopupMenu menu = new JPopupMenu();
		JMenuItem edit = new JMenuItem("Edit Template");
		edit.addActionListener(e -> editBlock(bt));
		menu.add(edit);
		JMenuItem changeColor = new JMenuItem("Change Color");
		changeColor.addActionListener(e -> {
			Color nc = JColorChooser.showDialog(this, "Pick Color", bt.canonicalColor);
			if (nc != null) {
				bt.setColor(nc);
				reloadPalette();
			}
		});
		menu.add(changeColor);
		JMenuItem changeSprite = new JMenuItem("Change Sprite");
		changeSprite.addActionListener(e -> {
			JFileChooser fc = new JFileChooser();
			fc.setFileFilter(new FileNameExtensionFilter("Images", "png", "jpg", "gif"));
			if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
				bt.setSpritePath(fc.getSelectedFile().getAbsolutePath());
				reloadPalette();
			}
		});
		menu.add(changeSprite);
		if (bt.custom && bt != BlockType.EMPTY && bt != BlockType.UNKNOWN) {
			menu.addSeparator();
			JMenuItem del = new JMenuItem("Delete Block Type");
			del.addActionListener(e -> {
				int res = JOptionPane.showConfirmDialog(this, "Delete '" + bt.displayName + "'?", "Confirm",
						JOptionPane.YES_NO_OPTION);
				if (res == JOptionPane.YES_OPTION) {
					BlockType.remove(bt);
					try {
						Files.deleteIfExists(bt.getTemplatePath());
					} catch (IOException ex) {
					}
					if (currentBlockType == bt)
						currentBlockType = BlockType.EMPTY;
					reloadPalette();
				}
			});
			menu.add(del);
		}
		menu.show(c, x, y);
	}

	void editBlock(BlockType bt) {
		JDialog d = new JDialog(this, "Edit Template: " + bt.displayName, true);
		d.setLayout(new BorderLayout(8, 8));

		// Basic info panel
		JPanel basicInfo = new JPanel(new GridLayout(0, 2, 4, 4));
		basicInfo.setBorder(BorderFactory.createTitledBorder("Basic Info"));
		basicInfo.add(new JLabel("Display Name:"));
		JTextField nameF = new JTextField(bt.displayName);
		basicInfo.add(nameF);
		basicInfo.add(new JLabel("XML Name:"));
		JTextField xmlF = new JTextField(bt.xmlName);
		basicInfo.add(xmlF);

		// Attributes panel
		JPanel attrsPanel = new JPanel(new GridBagLayout());
		attrsPanel.setBorder(BorderFactory.createTitledBorder("Attributes"));
		GridBagConstraints ac = new GridBagConstraints();
		ac.gridx = 0;
		ac.gridy = 0;
		ac.insets = new Insets(2, 2, 2, 2);
		ac.anchor = GridBagConstraints.WEST;

		Map<String, JTextField> attrFields = new LinkedHashMap<>();
		for (Map.Entry<String, String> e : bt.attributes.entrySet()) {
			attrsPanel.add(new JLabel(e.getKey() + ":"), ac);
			ac.gridx = 1;
			JTextField tf = new JTextField(e.getValue(), 20);
			attrsPanel.add(tf, ac);
			attrFields.put(e.getKey(), tf);

			// Live update
			tf.getDocument().addDocumentListener(new DocumentListener() {
				public void insertUpdate(DocumentEvent de) {
					bt.attributes.put(e.getKey(), tf.getText());
				}

				public void removeUpdate(DocumentEvent de) {
					bt.attributes.put(e.getKey(), tf.getText());
				}

				public void changedUpdate(DocumentEvent de) {
					bt.attributes.put(e.getKey(), tf.getText());
				}
			});

			ac.gridy++;
			ac.gridx = 0;
		}

		JButton addAttr = new JButton("Add Attribute");
		addAttr.addActionListener(a -> {
			String key = JOptionPane.showInputDialog(d, "Attribute name:");
			if (key != null && !key.trim().isEmpty() && !attrFields.containsKey(key)) {
				attrsPanel.add(new JLabel(key + ":"), ac);
				ac.gridx = 1;
				JTextField tf = new JTextField("", 20);
				attrsPanel.add(tf, ac);
				attrFields.put(key, tf);
				tf.getDocument().addDocumentListener(new DocumentListener() {
					public void insertUpdate(DocumentEvent de) {
						bt.attributes.put(key, tf.getText());
					}

					public void removeUpdate(DocumentEvent de) {
						bt.attributes.put(key, tf.getText());
					}

					public void changedUpdate(DocumentEvent de) {
						bt.attributes.put(key, tf.getText());
					}
				});
				ac.gridy++;
				ac.gridx = 0;
				attrsPanel.revalidate();
				attrsPanel.repaint();
			}
		});

		// Child nodes panel
		JPanel nodesPanel = new JPanel(new GridBagLayout());
		nodesPanel.setBorder(BorderFactory.createTitledBorder("Child Nodes"));
		GridBagConstraints nc = new GridBagConstraints();
		nc.gridx = 0;
		nc.gridy = 0;
		nc.insets = new Insets(2, 2, 2, 2);
		nc.anchor = GridBagConstraints.WEST;

		Map<String, JTextField> nodeFields = new LinkedHashMap<>();
		for (Map.Entry<String, String> e : bt.childNodes.entrySet()) {
			nodesPanel.add(new JLabel(e.getKey() + ":"), nc);
			nc.gridx = 1;
			JTextField tf = new JTextField(e.getValue(), 20);
			nodesPanel.add(tf, nc);
			nodeFields.put(e.getKey(), tf);

			// Live update
			tf.getDocument().addDocumentListener(new DocumentListener() {
				public void insertUpdate(DocumentEvent de) {
					bt.childNodes.put(e.getKey(), tf.getText());
				}

				public void removeUpdate(DocumentEvent de) {
					bt.childNodes.put(e.getKey(), tf.getText());
				}

				public void changedUpdate(DocumentEvent de) {
					bt.childNodes.put(e.getKey(), tf.getText());
				}
			});

			nc.gridy++;
			nc.gridx = 0;
		}

		JButton addNode = new JButton("Add Node");
		addNode.addActionListener(a -> {
			String key = JOptionPane.showInputDialog(d, "Node name:");
			if (key != null && !key.trim().isEmpty() && !nodeFields.containsKey(key)) {
				nodesPanel.add(new JLabel(key + ":"), nc);
				nc.gridx = 1;
				JTextField tf = new JTextField("", 20);
				nodesPanel.add(tf, nc);
				nodeFields.put(key, tf);
				tf.getDocument().addDocumentListener(new DocumentListener() {
					public void insertUpdate(DocumentEvent de) {
						bt.childNodes.put(key, tf.getText());
					}

					public void removeUpdate(DocumentEvent de) {
						bt.childNodes.put(key, tf.getText());
					}

					public void changedUpdate(DocumentEvent de) {
						bt.childNodes.put(key, tf.getText());
					}
				});
				nc.gridy++;
				nc.gridx = 0;
				nodesPanel.revalidate();
				nodesPanel.repaint();
			}
		});

		// Layout
		JPanel topPanel = new JPanel(new BorderLayout());
		topPanel.add(basicInfo, BorderLayout.NORTH);

		JPanel templatesPanel = new JPanel(new GridLayout(1, 2, 8, 8));
		templatesPanel.add(new JScrollPane(attrsPanel));
		templatesPanel.add(new JScrollPane(nodesPanel));
		topPanel.add(templatesPanel, BorderLayout.CENTER);

		d.add(topPanel, BorderLayout.CENTER);

		// Buttons
		JPanel btns = new JPanel();
		btns.add(addAttr);
		btns.add(addNode);
		JButton save = new JButton("Save Template");
		save.addActionListener(e -> {
			bt.displayName = nameF.getText();
			bt.xmlName = xmlF.getText();
			// Attributes and nodes already updated live
			try {
				bt.save();
				bt.custom = true;
			} catch (IOException ex) {
			}
			reloadPalette();
			d.dispose();
		});
		JButton cancel = new JButton("Cancel");
		cancel.addActionListener(e -> d.dispose());
		btns.add(save);
		btns.add(cancel);
		d.add(btns, BorderLayout.SOUTH);

		d.setSize(800, 600);
		d.setLocationRelativeTo(this);
		d.setVisible(true);
	}

	void rebuildRenderLayers(LevelData ld) {

		clearImage(ld.mainLayer);
		clearImage(ld.foregroundLayer);
		clearImage(ld.backgroundLayer);

		for (LevelData.PlacedBlock b : ld.blocks) {
			BufferedImage img = ld.getLayer(b.layer);

			Graphics2D g = img.createGraphics();
			g.setComposite(AlphaComposite.SrcOver);
			g.setColor(b.type.canonicalColor);
			g.fillRect(b.x, b.y, b.width, b.height);
			g.dispose();
		}
	}

	static void clearImage(BufferedImage img) {
		Graphics2D g = img.createGraphics();
		g.setComposite(AlphaComposite.Clear);
		g.fillRect(0, 0, img.getWidth(), img.getHeight());
		g.dispose();
	}

	void updateColor() {
		if (currentColorDisplay != null && currentBlockType != null)
			currentColorDisplay.setBackground(currentBlockType.canonicalColor);
	}

	void zoom(double delta) {
		setZoom(zoomLevel + delta);
	}

	void setZoom(double z) {
		zoomLevel = Math.max(0.25, Math.min(4.0, z));
		zoomLabel.setText((int) (zoomLevel * 100) + "%");
		LevelData ld = getCurrent();
		if (ld != null && ld.canvasPanel != null) {
			ld.canvasPanel.setPreferredSize(new Dimension((int) (ld.width * zoomLevel), (int) (ld.height * zoomLevel)));
			ld.canvasPanel.revalidate();
		}
		refresh();
	}

	void drawMinimap(Graphics g) {
		LevelData ld = getCurrent();
		if (ld == null)
			return;
		int w = minimapPanel.getWidth() - 20, h = minimapPanel.getHeight() - 20;
		double scale = Math.min(w / (double) ld.width, h / (double) ld.height);
		int dw = (int) (ld.width * scale), dh = (int) (ld.height * scale), ox = 10 + (w - dw) / 2,
				oy = 10 + (h - dh) / 2;
		g.drawImage(ld.backgroundLayer, ox, oy, dw, dh, null);
		g.drawImage(ld.mainLayer, ox, oy, dw, dh, null);
		g.drawImage(ld.foregroundLayer, ox, oy, dw, dh, null);
		g.setColor(Color.YELLOW);
		((Graphics2D) g).setStroke(new BasicStroke(2));
		g.drawRect(ox, oy, dw, dh);
	}

	void addNewLevel() {
		String name = "Level " + levelCounter++;
		LevelData ld = new LevelData(name, DEFAULT_WIDTH, DEFAULT_HEIGHT);
		levels.add(ld);
		ld.canvasPanel = new JPanel() {
			protected void paintComponent(Graphics g) {
				super.paintComponent(g);
				Graphics2D g2 = (Graphics2D) g;
				g2.scale(zoomLevel, zoomLevel);
				g2.drawImage(ld.backgroundLayer, 0, 0, null);
				g2.drawImage(ld.mainLayer, 0, 0, null);
				g2.drawImage(ld.foregroundLayer, 0, 0, null);
				if (showGrid) {
					g2.setColor(new Color(200, 200, 200, 100));
					for (int x = 0; x < ld.width; x += PIXEL_SIZE)
						g2.drawLine(x, 0, x, ld.height);
					for (int y = 0; y < ld.height; y += PIXEL_SIZE)
						g2.drawLine(0, y, ld.width, y);
				}
				if (showCollisionBounds) {
					g2.setColor(new Color(255, 0, 0, 150));
					g2.setStroke(new BasicStroke(2));
					for (Rect r : ld.cachedCollisionRects)
						g2.drawRect(r.x, r.y, r.w, r.h);
				}
				if (showEventMarkers) {
					for (GameEvent ev : ld.events) {
						g2.setColor(new Color(255, 255, 0, 200));
						g2.fillOval(ev.x - 8, ev.y - 8, 16, 16);
						g2.setColor(Color.BLACK);
						g2.drawOval(ev.x - 8, ev.y - 8, 16, 16);
						g2.setFont(new Font("Arial", Font.PLAIN, 10));
						g2.drawString(ev.name, ev.x + 10, ev.y);
					}
				}
			}
		};
		ld.canvasPanel.setPreferredSize(new Dimension((int) (ld.width * zoomLevel), (int) (ld.height * zoomLevel)));
		ld.canvasPanel.setBackground(Color.WHITE);
		MouseAdapter ma = new MouseAdapter() {
			int lx = -1, ly = -1;

			public void mousePressed(MouseEvent e) {
				int rx = (int) (e.getX() / zoomLevel), ry = (int) (e.getY() / zoomLevel);
				if (SwingUtilities.isRightMouseButton(e))
					placeEvent(ld, rx, ry);
				else if (SwingUtilities.isLeftMouseButton(e)) {
					paint(ld, rx, ry);
					lx = rx;
					ly = ry;
				}
			}

			public void mouseDragged(MouseEvent e) {
				if (SwingUtilities.isLeftMouseButton(e)) {
					int rx = (int) (e.getX() / zoomLevel), ry = (int) (e.getY() / zoomLevel);
					if (lx != -1)
						drawLine(ld, lx, ly, rx, ry);
					paint(ld, rx, ry);
					lx = rx;
					ly = ry;
				}
			}

			public void mouseReleased(MouseEvent e) {
				lx = -1;
				ly = -1;
				rebuildCollision(ld);
			}
		};
		ld.canvasPanel.addMouseListener(ma);
		ld.canvasPanel.addMouseMotionListener(ma);
		ld.scrollPane = new JScrollPane(ld.canvasPanel);
		levelTabs.addTab(name, ld.scrollPane);
		levelTabs.setSelectedIndex(levelTabs.getTabCount() - 1);
	}

	LevelData getCurrent() {
		int i = levelTabs.getSelectedIndex();
		return i >= 0 && i < levels.size() ? levels.get(i) : null;
	}

	void refresh() {
		LevelData ld = getCurrent();
		if (ld != null && ld.canvasPanel != null) {
			ld.canvasPanel.repaint();
			minimapPanel.repaint();
		}
	}

	void clearCurrentLayer() {
		LevelData ld = getCurrent();
		if (ld != null) {
			String layer = (String) layerSelector.getSelectedItem();
			ld.clear(ld.getLayer(layer));
			ld.blocks.clear();

			if (layer.equals("Main")) {
				Graphics2D g = ld.mainLayer.createGraphics();
				g.setColor(Color.WHITE);
				g.fillRect(0, 0, ld.width, ld.height);
				g.dispose();
			}
			refresh();
		}
	}

	void showProps() {
		LevelData ld = getCurrent();
		if (ld == null)
			return;
		JDialog d = new JDialog(this, "Level Properties", true);
		d.setLayout(new BorderLayout(8, 8));
		JPanel form = new JPanel(new GridLayout(0, 2, 4, 4));
		form.setBorder(new EmptyBorder(10, 10, 10, 10));
		form.add(new JLabel("Name:"));
		JTextField nameF = new JTextField(ld.name);
		form.add(nameF);
		form.add(new JLabel("Description:"));
		JTextField descF = new JTextField(ld.description);
		form.add(descF);
		form.add(new JLabel("Width:"));
		JTextField wF = new JTextField("" + ld.width);
		form.add(wF);
		form.add(new JLabel("Height:"));
		JTextField hF = new JTextField("" + ld.height);
		form.add(hF);
		d.add(form, BorderLayout.CENTER);
		JPanel btns = new JPanel();
		JButton save = new JButton("Save");
		save.addActionListener(e -> {
			ld.name = nameF.getText();
			ld.description = descF.getText();
			try {
				int nw = Integer.parseInt(wF.getText()), nh = Integer.parseInt(hF.getText());
				if (nw != ld.width || nh != ld.height)
					resizeLevel(ld, nw, nh);
			} catch (NumberFormatException ex) {
			}
			levelTabs.setTitleAt(levelTabs.getSelectedIndex(), ld.name);
			d.dispose();
		});
		JButton cancel = new JButton("Cancel");
		cancel.addActionListener(e -> d.dispose());
		btns.add(save);
		btns.add(cancel);
		d.add(btns, BorderLayout.SOUTH);
		d.pack();
		d.setLocationRelativeTo(this);
		d.setVisible(true);
	}

	void resizeLevel(LevelData ld, int nw, int nh) {

		ld.backgroundLayer = resizeLayer(ld.backgroundLayer, nw, nh);
		ld.mainLayer = resizeLayer(ld.mainLayer, nw, nh);
		ld.foregroundLayer = resizeLayer(ld.foregroundLayer, nw, nh);

		ld.width = nw;
		ld.height = nh;

		Graphics2D g = ld.mainLayer.createGraphics();
		g.setColor(Color.WHITE);
		g.fillRect(0, 0, nw, nh);
		g.drawImage(ld.mainLayer, 0, 0, null);
		g.dispose();
		g = ld.backgroundLayer.createGraphics();
		g.drawImage(ld.backgroundLayer, 0, 0, null);
		g.dispose();
		g = ld.foregroundLayer.createGraphics();
		g.drawImage(ld.foregroundLayer, 0, 0, null);
		g.dispose();

		ld.canvasPanel.setPreferredSize(new Dimension((int) (nw * zoomLevel), (int) (nh * zoomLevel)));
		ld.canvasPanel.revalidate();
		refresh();
	}

	private BufferedImage resizeLayer(BufferedImage src, int newW, int newH) {
		BufferedImage dst = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_ARGB);

		Graphics2D g = dst.createGraphics();

		// CRITICAL: explicitly clear to transparent
		g.setComposite(AlphaComposite.Clear);
		g.fillRect(0, 0, newW, newH);

		// Restore normal drawing
		g.setComposite(AlphaComposite.SrcOver);

		// Draw old image
		g.drawImage(src, 0, 0, null);

		g.dispose();
		return dst;
	}

	void paint(LevelData ld, int x, int y) {
		if (x < 0 || x >= ld.width || y < 0 || y >= ld.height)
			return;

		int gx = (x / PIXEL_SIZE) * PIXEL_SIZE;
		int gy = (y / PIXEL_SIZE) * PIXEL_SIZE;

//	    if (currentBlockType == BlockType.EMPTY)
//	        return;

		// Parse size from template child nodes
		int bw = parseInt(currentBlockType.childNodes.get("width"), PIXEL_SIZE);
		int bh = parseInt(currentBlockType.childNodes.get("height"), PIXEL_SIZE);

		// Snap size to grid
		bw = Math.max(PIXEL_SIZE, (bw / PIXEL_SIZE) * PIXEL_SIZE);
		bh = Math.max(PIXEL_SIZE, (bh / PIXEL_SIZE) * PIXEL_SIZE);

		LevelData.PlacedBlock pb = new LevelData.PlacedBlock(currentBlockType, gx, gy);
		pb.width = bw;
		pb.height = bh;

		ld.blocks.add(pb);
		rebuildRenderLayers(ld);

	}

	static int parseInt(String v, int def) {
		try {
			return Integer.parseInt(v);
		} catch (Exception e) {
			return def;
		}
	}

	void drawLine(LevelData ld, int x0, int y0, int x1, int y1) {
		int dx = Math.abs(x1 - x0), dy = Math.abs(y1 - y0);
		int sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1, err = dx - dy;
		while (true) {
			paint(ld, x0, y0);
			if (x0 == x1 && y0 == y1)
				break;
			int e2 = 2 * err;
			if (e2 > -dy) {
				err -= dy;
				x0 += sx;
			}
			if (e2 < dx) {
				err += dx;
				y0 += sy;
			}
		}
	}

	void placeEvent(LevelData ld, int x, int y) {
		String n = JOptionPane.showInputDialog(this, "Event name:", "Event", JOptionPane.QUESTION_MESSAGE);
		if (n != null && !n.trim().isEmpty()) {
			ld.events.add(new GameEvent(currentEventType, n.trim(), x, y));
			refresh();
		}
	}

	void rebuildCollision(LevelData ld) {
		ld.cachedCollisionRects.clear();

		int cols = ld.width / PIXEL_SIZE;
		int rows = ld.height / PIXEL_SIZE;

		boolean[][] solid = new boolean[rows][cols];

		BufferedImage img = ld.mainLayer;

		// Step 1: classify solid tiles
		for (int y = 0; y < rows; y++) {
			for (int x = 0; x < cols; x++) {
				int px = x * PIXEL_SIZE;
				int py = y * PIXEL_SIZE;

				Color c = new Color(img.getRGB(px, py), true);
				if (c.getAlpha() == 0)
					continue;

				BlockType bt = BlockType.fromColor(c);
				if (bt != null) {
					solid[y][x] = true;
				}
			}
		}

		boolean[][] consumed = new boolean[rows][cols];

		// Step 2: merge into rectangles
		for (int y = 0; y < rows; y++) {
			for (int x = 0; x < cols; x++) {
				if (!solid[y][x] || consumed[y][x])
					continue;

				int maxX = x;
				while (maxX + 1 < cols && solid[y][maxX + 1] && !consumed[y][maxX + 1]) {
					maxX++;
				}

				int maxY = y;
				boolean canGrow = true;
				while (canGrow && maxY + 1 < rows) {
					for (int tx = x; tx <= maxX; tx++) {
						if (!solid[maxY + 1][tx] || consumed[maxY + 1][tx]) {
							canGrow = false;
							break;
						}
					}
					if (canGrow)
						maxY++;
				}

				for (int ty = y; ty <= maxY; ty++) {
					for (int tx = x; tx <= maxX; tx++) {
						consumed[ty][tx] = true;
					}
				}

				Rect r = new Rect();
				r.x = x * PIXEL_SIZE;
				r.y = y * PIXEL_SIZE;
				r.w = (maxX - x + 1) * PIXEL_SIZE;
				r.h = (maxY - y + 1) * PIXEL_SIZE;

				ld.cachedCollisionRects.add(r);
			}
		}

		refresh();
	}

	void newBlockType() {
		String name = JOptionPane.showInputDialog(this, "Block name:");
		if (name == null || name.trim().isEmpty())
			return;
		Color c = JColorChooser.showDialog(this, "Pick Color", Color.GRAY);
		if (c == null)
			return;
		BlockType bt = new BlockType(name, name.toLowerCase().replace(" ", "_"), c, null, 8, 8);
		bt.custom = true;
		try {
			bt.save();
		} catch (IOException e) {
		}
		BlockType.register(bt);
		reloadPalette();
	}

	void exportBlocks() {
		JFileChooser fc = new JFileChooser();
		fc.setFileFilter(new FileNameExtensionFilter("JSON", "json"));
		if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION)
			return;

		try {
			StringBuilder json = new StringBuilder("[\n");
			boolean first = true;

			for (BlockType bt : BlockType.getRegistry()) {
				if (bt == BlockType.EMPTY || bt == BlockType.UNKNOWN)
					continue;

				if (!first)
					json.append(",\n");
				first = false;

				json.append("  {\n");
				json.append("    \"displayName\": \"").append(BlockType.esc(bt.displayName)).append("\",\n");
				json.append("    \"xmlName\": \"").append(BlockType.esc(bt.xmlName)).append("\",\n");
				json.append("    \"color\": \"").append(String.format("#%02X%02X%02X", bt.canonicalColor.getRed(),
						bt.canonicalColor.getGreen(), bt.canonicalColor.getBlue())).append("\",\n");

				json.append("    \"spritePath\": \"").append(BlockType.esc(bt.spritePath == null ? "" : bt.spritePath))
						.append("\",\n");

				json.append("    \"attributes\": ").append(BlockType.mapToJson(bt.attributes)).append(",\n");

				json.append("    \"childNodes\": ").append(BlockType.mapToJson(bt.childNodes)).append("\n");

				json.append("  }");
			}

			json.append("\n]");
			Files.write(fc.getSelectedFile().toPath(), json.toString().getBytes(StandardCharsets.UTF_8));
			JOptionPane.showMessageDialog(this, "Blocks exported.");

		} catch (IOException e) {
			JOptionPane.showMessageDialog(this, "Export failed: " + e.getMessage());
		}
	}

	void importBlocks() {
		JFileChooser fc = new JFileChooser();
		fc.setFileFilter(new FileNameExtensionFilter("JSON", "json"));
		if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION)
			return;

		try {
			String json = Files.readString(fc.getSelectedFile().toPath(), StandardCharsets.UTF_8);
			List<Map<String, Object>> list = BlockType.parseJsonArrayOfObjects(json);

			for (Map<String, Object> obj : list) {
				String displayName = BlockType.asString(obj.get("displayName"), "");
				String xmlName = BlockType.asString(obj.get("xmlName"), "");
				String colorHex = BlockType.asString(obj.get("color"), "#FFFFFF");
				String spritePath = BlockType.asString(obj.get("spritePath"), "");

				if (xmlName.isEmpty())
					continue;
				if (BlockType.fromXml(xmlName) != BlockType.UNKNOWN)
					continue;

				Color c = Color.decode(colorHex);
				BlockType bt = new BlockType(displayName, xmlName, c, spritePath.isEmpty() ? null : spritePath,
						PIXEL_SIZE, PIXEL_SIZE);

				bt.custom = true;

				Object attrs = obj.get("attributes");
				if (attrs instanceof Map) {
					bt.attributes.clear();
					bt.attributes.putAll((Map<String, String>) attrs);
				}

				Object nodes = obj.get("childNodes");
				if (nodes instanceof Map) {
					bt.childNodes.clear();
					bt.childNodes.putAll((Map<String, String>) nodes);
				}

				bt.loadSprite();
				BlockType.register(bt);
			}

			reloadPalette();
			JOptionPane.showMessageDialog(this, "Blocks imported.");

		} catch (Exception e) {
			JOptionPane.showMessageDialog(this, "Import failed: " + e.getMessage());
		}
	}

	void saveXML() {

		LevelData ld = getCurrent();
		if (ld == null)
			return;
		JFileChooser fc = new JFileChooser();
		fc.setFileFilter(new FileNameExtensionFilter("XML", "xml"));
		if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION)
			return;
		try {
			StringBuilder xml = new StringBuilder("<?xml version=\"1.0\"?>\n<level>\n");
			xml.append("  <levelstats>\n");
			xml.append("    <name>").append(ld.name).append("</name>\n");
			xml.append("    <description>").append(ld.description).append("</description>\n");
			xml.append("    <width>").append(ld.width).append("</width>\n");
			xml.append("    <height>").append(ld.height).append("</height>\n");
			xml.append("  </levelstats>\n\n");

			// Export objects from all layers
			// Export objects using merged rectangles (lossless)

			for (String layer : new String[] { "Background", "Main", "Foreground" }) {
			    BufferedImage img = ld.getLayer(layer);
				List<RectExport> optimalRectanglesFast = buildOptimalRectanglesFast(img, layer);
				for (RectExport rectExport : optimalRectanglesFast) {

					System.out.println("attribute: " + rectExport.attributes.size());
					xml.append("  <object type=\"").append(rectExport.type.xmlName).append("\" layer=\"").append(layer)
							.append("\">\n");

					xml.append("    <xpos>").append(rectExport.x).append("</xpos>\n");
					xml.append("    <ypos>").append(rectExport.y).append("</ypos>\n");
					xml.append("    <zpos>0</zpos>\n");
					xml.append("    <width>").append(rectExport.width).append("</width>\n");
					xml.append("    <height>").append(rectExport.height).append("</height>\n");

					for (Map.Entry<String, String> e : rectExport.attributes.entrySet()) {
						String key = e.getKey();
						if (key == null || key.isBlank())
							continue;

						// avoid duplicating core geometry tags
						if (key.equalsIgnoreCase("width") || key.equalsIgnoreCase("height")
								|| key.equalsIgnoreCase("xpos") || key.equalsIgnoreCase("ypos")
								|| key.equalsIgnoreCase("zpos")) {
							continue;
						}

						String val = e.getValue() == null ? "" : e.getValue();
						xml.append("    <").append(key).append(">").append(val).append("</").append(key).append(">\n");
					}

					xml.append("  </object>\n");
				}

			}

			for (GameEvent ev : ld.events) {
				xml.append("  <event type=\"").append(ev.type).append("\">\n");
				xml.append("    <name>").append(ev.name).append("</name>\n");
				xml.append("    <x>").append(ev.x).append("</x>\n");
				xml.append("    <y>").append(ev.y).append("</y>\n");
				xml.append("  </event>\n");
			}

			xml.append("</level>");
			Files.write(fc.getSelectedFile().toPath(), xml.toString().getBytes());
			JOptionPane.showMessageDialog(this, "Saved!");
		} catch (IOException e) {
			JOptionPane.showMessageDialog(this, "Error: " + e.getMessage());
		}
	}

	private List<RectExport> buildOptimalRectanglesFast(BufferedImage img, String layer) {
		final int imgW = img.getWidth();
		final int imgH = img.getHeight();

		final int tilesW = imgW / PIXEL_SIZE;
		final int tilesH = imgH / PIXEL_SIZE;

		// Pull pixel data once
		final int[] pixels = img.getRGB(0, 0, imgW, imgH, null, 0, imgW);

		// Visited tiles (BitSet is far more memory/cache efficient)
		BitSet used = new BitSet(tilesW * tilesH);

		List<RectExport> out = new ArrayList<>();

		for (int ty = 0; ty < tilesH; ty++) {
			int rowBase = ty * tilesW;

			for (int tx = 0; tx < tilesW; tx++) {
				int idx = rowBase + tx;
				if (used.get(idx))
					continue;

				int px = tx * PIXEL_SIZE;
				int py = ty * PIXEL_SIZE;

				int argb = pixels[py * imgW + px];
				if ((argb >>> 24) == 0)
					continue;

				BlockType bt = BlockType.fromColor(new Color(argb, true));
				if (bt == BlockType.EMPTY)
					continue;

				// Expand right
				int maxW = 1;
				while (tx + maxW < tilesW) {
					int nx = tx + maxW;
					int nIdx = rowBase + nx;
					if (used.get(nIdx))
						break;

					int nArgb = pixels[py * imgW + nx * PIXEL_SIZE];
					if (nArgb != argb)
						break;

					maxW++;
				}

				// Expand down
				int maxH = 1;
				outer: while (ty + maxH < tilesH) {
					int ny = ty + maxH;
					int nyBase = ny * tilesW;

					int py2 = ny * PIXEL_SIZE;
					for (int dx = 0; dx < maxW; dx++) {
						int checkIdx = nyBase + (tx + dx);
						if (used.get(checkIdx))
							break outer;

						int checkArgb = pixels[py2 * imgW + (tx + dx) * PIXEL_SIZE];
						if (checkArgb != argb)
							break outer;
					}

					maxH++;
				}

				// Mark used
				for (int dy = 0; dy < maxH; dy++) {
					int markBase = (ty + dy) * tilesW;
					for (int dx = 0; dx < maxW; dx++) {
						used.set(markBase + tx + dx);
					}
				}

				out.add(new RectExport(

						px, py, maxW * PIXEL_SIZE, maxH * PIXEL_SIZE, bt, bt.childNodes));
			}
		}

		return out;
	}

	void loadXML() {
		JFileChooser fc = new JFileChooser();
		fc.setFileFilter(new FileNameExtensionFilter("XML", "xml"));
		if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION)
			return;
		try {
			DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
			Document doc = dbf.newDocumentBuilder().parse(fc.getSelectedFile());
			doc.getDocumentElement().normalize();

			String name = "Loaded Level";
			int width = DEFAULT_WIDTH, height = DEFAULT_HEIGHT;

			NodeList stats = doc.getElementsByTagName("levelstats");
			if (stats.getLength() > 0) {
				Element s = (Element) stats.item(0);
				NodeList n = s.getElementsByTagName("name");
				if (n.getLength() > 0)
					name = n.item(0).getTextContent();
				n = s.getElementsByTagName("width");
				if (n.getLength() > 0)
					width = Integer.parseInt(n.item(0).getTextContent());
				n = s.getElementsByTagName("height");
				if (n.getLength() > 0)
					height = Integer.parseInt(n.item(0).getTextContent());
			}

			LevelData ld = new LevelData(name, width, height);

			NodeList objs = doc.getElementsByTagName("object");
			for (int i = 0; i < objs.getLength(); i++) {
				Element obj = (Element) objs.item(i);

				String typeName = obj.getAttribute("type");
				String layer = obj.hasAttribute("layer") ? obj.getAttribute("layer") : "Main";

				int x = getInt(obj, "xpos", 0);
				int y = getInt(obj, "ypos", 0);
				int w = getInt(obj, "width", PIXEL_SIZE);
				int h = getInt(obj, "height", PIXEL_SIZE);

				BlockType bt = BlockType.fromXml(typeName);
				if (bt == BlockType.UNKNOWN) {
					bt = new BlockType(typeName, typeName, Color.PINK, null, w, h);
					bt.custom = true;
					BlockType.register(bt);
				}

				LevelData.PlacedBlock pb = new LevelData.PlacedBlock(bt, x, y, layer);

				pb.width = w;
				pb.height = h;

				// Load child nodes as attributes
				NodeList children = obj.getChildNodes();
				for (int c = 0; c < children.getLength(); c++) {
					if (children.item(c) instanceof Element) {
						Element child = (Element) children.item(c);
						String tag = child.getTagName();
						if (!Set.of("xpos", "ypos", "zpos", "width", "height").contains(tag)) {
							pb.attributes.put(tag, child.getTextContent());
						}
					}
				}

				ld.blocks.add(pb);
			}

			NodeList evs = doc.getElementsByTagName("event");
			for (int i = 0; i < evs.getLength(); i++) {
				Element ev = (Element) evs.item(i);
				String type = ev.getAttribute("type");
				String evName = "";
				int x = 0, y = 0;
				NodeList nn = ev.getElementsByTagName("name");
				if (nn.getLength() > 0)
					evName = nn.item(0).getTextContent();
				nn = ev.getElementsByTagName("x");
				if (nn.getLength() > 0)
					x = Integer.parseInt(nn.item(0).getTextContent());
				nn = ev.getElementsByTagName("y");
				if (nn.getLength() > 0)
					y = Integer.parseInt(nn.item(0).getTextContent());
				ld.events.add(new GameEvent(type, evName, x, y));
			}

			ld.blocks.clear();

			repaint();

			levels.add(ld);
			ld.canvasPanel = createCanvasPanel(ld);
			ld.scrollPane = new JScrollPane(ld.canvasPanel);
			levelTabs.addTab(name, ld.scrollPane);
			levelTabs.setSelectedIndex(levelTabs.getTabCount() - 1);
			rebuildRenderLayers(ld);
			rebuildCollision(ld);
			refresh();

			reloadPalette();
			JOptionPane.showMessageDialog(this, "Loaded!");
		} catch (Exception e) {
			JOptionPane.showMessageDialog(this, "Error: " + e.getMessage());
		}
	}

	static int getInt(Element e, String tag, int def) {
		NodeList n = e.getElementsByTagName(tag);
		if (n.getLength() == 0)
			return def;
		try {
			return Integer.parseInt(n.item(0).getTextContent());
		} catch (Exception ex) {
			return def;
		}
	}

	private boolean colorsMatch(Color a, Color b) {
		return a.getRGB() == b.getRGB();
	}

	JPanel createCanvasPanel(LevelData ld) {
		JPanel cp = new JPanel() {

			public void mousePressed(MouseEvent e) {
				int rx = (int) (e.getX() / zoomLevel);
				int ry = (int) (e.getY() / zoomLevel);

				if (currentTool == ToolMode.ERASE_RECT) {
					eraseRect = new Rectangle(rx, ry, 0, 0);
				}
			}

			public void mouseDragged(MouseEvent e) {
				if (currentTool == ToolMode.ERASE_RECT && eraseRect != null) {
					int rx = (int) (e.getX() / zoomLevel);
					int ry = (int) (e.getY() / zoomLevel);
					eraseRect.width = rx - eraseRect.x;
					eraseRect.height = ry - eraseRect.y;
					refresh();
				}
			}

			public void mouseReleased(MouseEvent e) {
				if (currentTool == ToolMode.ERASE_RECT && eraseRect != null) {
					Rectangle r = eraseRect.getBounds();
					ld.blocks.removeIf(b -> r.intersects(b.x, b.y, b.width, b.height));
					eraseRect = null;
					rebuildRenderLayers(ld);
				}
			}

			protected void paintComponent(Graphics g) {
				super.paintComponent(g);
				Graphics2D g2 = (Graphics2D) g;
				g2.scale(zoomLevel, zoomLevel);
				g2.drawImage(ld.backgroundLayer, 0, 0, null);
				g2.drawImage(ld.mainLayer, 0, 0, null);
				g2.drawImage(ld.foregroundLayer, 0, 0, null);
				if (showGrid) {
					g2.setColor(new Color(200, 200, 200, 100));
					for (int x = 0; x < ld.width; x += PIXEL_SIZE)
						g2.drawLine(x, 0, x, ld.height);
					for (int y = 0; y < ld.height; y += PIXEL_SIZE)
						g2.drawLine(0, y, ld.width, y);
				}
				if (showCollisionBounds) {
					g2.setColor(new Color(255, 0, 0, 150));
					for (Rect r : ld.cachedCollisionRects)
						g2.drawRect(r.x, r.y, r.w, r.h);
				}
				if (showEventMarkers) {
					for (GameEvent ev : ld.events) {
						g2.setColor(new Color(255, 255, 0, 200));
						g2.fillOval(ev.x - 8, ev.y - 8, 16, 16);
						g2.setColor(Color.BLACK);
						g2.drawOval(ev.x - 8, ev.y - 8, 16, 16);
						g2.drawString(ev.name, ev.x + 10, ev.y);
					}
				}
				if (eraseRect != null) {
					g2.setColor(new Color(255, 0, 0, 80));
					g2.fill(eraseRect);
					g2.setColor(Color.RED);
					g2.draw(eraseRect);
				}

			}
		};
		cp.setPreferredSize(new Dimension((int) (ld.width * zoomLevel), (int) (ld.height * zoomLevel)));
		cp.setBackground(Color.WHITE);
		MouseAdapter ma = new MouseAdapter() {
			int lx = -1, ly = -1;

			public void mousePressed(MouseEvent e) {
				int rx = (int) (e.getX() / zoomLevel), ry = (int) (e.getY() / zoomLevel);
				if (SwingUtilities.isRightMouseButton(e))
					placeEvent(ld, rx, ry);
				else if (SwingUtilities.isLeftMouseButton(e)) {
					paint(ld, rx, ry);
					lx = rx;
					ly = ry;
				}
			}

			public void mouseDragged(MouseEvent e) {
				if (SwingUtilities.isLeftMouseButton(e)) {
					int rx = (int) (e.getX() / zoomLevel), ry = (int) (e.getY() / zoomLevel);
					if (lx != -1)
						drawLine(ld, lx, ly, rx, ry);
					paint(ld, rx, ry);
					lx = rx;
					ly = ry;
				}
			}

			public void mouseReleased(MouseEvent e) {
				lx = -1;
				ly = -1;
			}
		};
		cp.addMouseListener(ma);
		cp.addMouseMotionListener(ma);
		return cp;
	}

	void initBlocks() {
		BlockType.register(BlockType.EMPTY);
		BlockType.register(new BlockType("Wall", "wall", Color.GRAY, "", 8, 8));
		BlockType.register(new BlockType("Coin", "coin", Color.YELLOW, "", 8, 8));
		BlockType.register(new BlockType("Ground", "ground", Color.GREEN, "", 8, 8));
		BlockType.register(new BlockType("Platform", "platform", new Color(139, 69, 19), "", 8, 8));
		BlockType.register(new BlockType("Water", "water", Color.BLUE, "", 8, 8));
		BlockType.register(new BlockType("Enemy", "enemy", Color.RED, "", 8, 8));
		BlockType.register(new BlockType("Unknown", "unknown", Color.PINK, "", 8, 8));
	}

	public static void main(String[] args) {
		SwingUtilities.invokeLater(() -> new LevelEditorPaintTool().setVisible(true));
	}
}