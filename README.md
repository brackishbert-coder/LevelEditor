# LevelEditor

A standalone desktop **level-authoring tool** for *TBD*. Paint a world on a grid out of
typed blocks, drop in events, and export the result as XML that imports straight into
**trainingGround** — so you can design and iterate on game worlds without touching the engine.

It is a single-file Java **Swing** application (`levelEditor.LevelEditorPaintTool`,
~1,900 lines) with **no external dependencies** — just a JDK.

---

## What it does

The editor thinks in **blocks**. Every block is a *type* with:

- a **display name** (what you see in the palette),
- an **XML name** (the tag it exports as),
- a **canonical color** (how it's painted on the canvas),
- an optional **sprite path**, and
- a **size** (width / height).

You register the block types you need into a palette, then **paint** them onto the canvas
across named **layers**. When you export, the editor **merges your painted cells into the
smallest possible set of rectangles** (an optimal-rectangle pass), so a hand-painted region
becomes a handful of clean `<object>` entries instead of thousands of pixels.

On top of the geometry you place **events / nodes** — named points with coordinates and
custom attributes that the engine reads at runtime (triggers, spawns, story beats).

---

## Requirements

- A **JDK** (17+ recommended).
- **Maven** (optional — you can also compile the one source file by hand, or run it from Eclipse).
- No third-party libraries; it uses only the standard library (Swing/AWT, `javax.imageio`,
  `javax.xml`, `java.nio`).

---

## Build & run

### With Maven

```bash
git clone https://github.com/brackishbert-coder/LevelEditor
cd LevelEditor

mvn compile            # or: mvn package
java -cp target/classes levelEditor.LevelEditorPaintTool
```

### Without Maven (compile the single source directly)

```bash
javac -d out src/main/java/levelEditor/LevelEditorPaintTool.java
java -cp out levelEditor.LevelEditorPaintTool
```

### In Eclipse

The repo ships `.project` and `.classpath`, so you can **Import → Existing Projects /
Existing Maven Projects**, then run `LevelEditorPaintTool` as a *Java Application*.

---

## Using the editor

1. **Define block types.** Build your palette — display name, XML name, color, optional
   sprite, dimensions. Save reusable sets as **templates** (persisted as JSON in the
   `templates/` folder; load them later via *Edit / Save Template*).
2. **Paint.** Pick a block type and drag on the canvas. Work across **layers** (a default
   `Main` plus any you add), navigate with the **minimap**, zoom, and keep multiple levels in
   tabs.
3. **Place events.** Drop named event nodes at x/y positions and attach custom attributes.
4. **Export to XML** and import the file into **trainingGround**.

---

## Exported level format

The editor writes a `<level>` document: level metadata, then one optimized `<object>` per
painted rectangle, then an `<event>` per node. Roughly:

```xml
<level>
  <name>...</name>
  <description>...</description>
  <width>...</width>
  <height>...</height>

  <!-- one per merged rectangle of a block type -->
  <object type="...">
    <xpos>...</xpos>
    <ypos>...</ypos>
    <width>...</width>
    <height>...</height>
    <!-- plus any custom attributes you set on the block -->
  </object>

  <!-- one per placed event/node -->
  <event>
    <name>...</name>
    <x>...</x>
    <y>...</y>
  </event>
</level>
```

This is the format `trainingGround` consumes directly.

---

## Project layout

```
LevelEditor/
├── pom.xml                                  Maven project (groupId: the, artifactId: levelEditor)
├── src/main/java/levelEditor/
│   └── LevelEditorPaintTool.java            the entire editor (JFrame, painting, export)
├── templates/                               saved block-type templates (JSON)
├── .project / .classpath                    Eclipse project files
└── README.md
```

---

*Part of the broader THEVM / PCB / trainingGround ecosystem. The editor authors levels;
trainingGround runs them.*
