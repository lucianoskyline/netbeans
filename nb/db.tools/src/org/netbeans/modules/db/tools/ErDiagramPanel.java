/*
 * Copyright 2026 Luciano Lopes.
 * Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 */
package org.netbeans.modules.db.tools;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.JComponent;
import javax.swing.UIManager;

/** Draws an {@link ErModel}: one box per table, a line per foreign key. Boxes can be dragged; Ctrl+wheel zooms. */
final class ErDiagramPanel extends JComponent {

    private static final int GAP = 70;
    private static final int PAD = 8;
    private static final int MARGIN = 30;

    private final ErModel model;
    private final Map<ErModel.Table, Rectangle> boxes = new LinkedHashMap<>();
    private double zoom = 1.0;
    private ErModel.Table dragged;
    private Point dragOffset;
    private Runnable zoomListener = () -> { };

    ErDiagramPanel(ErModel model) {
        this.model = model;
        setOpaque(true);
        layoutTables();
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                Point p = toModel(e.getPoint());
                List<ErModel.Table> order = new ArrayList<>(boxes.keySet());
                for (int i = order.size() - 1; i >= 0; i--) {
                    Rectangle r = boxes.get(order.get(i));
                    if (r.contains(p)) {
                        dragged = order.get(i);
                        dragOffset = new Point(p.x - r.x, p.y - r.y);
                        // bring to front
                        boxes.remove(dragged);
                        boxes.put(dragged, r);
                        repaint();
                        return;
                    }
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (dragged != null) {
                    Point p = toModel(e.getPoint());
                    Rectangle r = boxes.get(dragged);
                    r.setLocation(Math.max(0, p.x - dragOffset.x), Math.max(0, p.y - dragOffset.y));
                    revalidate();
                    repaint();
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                dragged = null;
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                if (e.isControlDown()) {
                    setZoom(zoom * (e.getWheelRotation() < 0 ? 1.1 : 1 / 1.1));
                } else if (getParent() != null) {
                    getParent().dispatchEvent(javax.swing.SwingUtilities.convertMouseEvent(ErDiagramPanel.this, e, getParent()));
                }
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
    }

    void setZoomListener(Runnable r) {
        zoomListener = r;
    }

    double getZoom() {
        return zoom;
    }

    void setZoom(double z) {
        zoom = Math.max(0.2, Math.min(3.0, z));
        revalidate();
        repaint();
        zoomListener.run();
    }

    /** Zoom so the whole diagram fits the visible area (never above 100%). */
    void fitTo(Dimension visible) {
        Dimension d = getPreferredSize();
        double w = d.width / zoom;
        double h = d.height / zoom;
        if (w > 0 && h > 0) {
            setZoom(Math.min(1.0, Math.min(visible.width / w, visible.height / h)));
        }
    }

    private Point toModel(Point p) {
        return new Point((int) (p.x / zoom), (int) (p.y / zoom));
    }

    private Font font() {
        Font f = UIManager.getFont("Label.font"); // NOI18N
        return f != null ? f : new Font(Font.SANS_SERIF, Font.PLAIN, 12);
    }

    /** Places tables in a grid, walking the FK graph so related tables end up next to each other. */
    private void layoutTables() {
        FontMetrics fm = getFontMetrics(font());
        Map<ErModel.Table, Set<ErModel.Table>> neighbours = new HashMap<>();
        for (ErModel.ForeignKey fk : model.foreignKeys) {
            neighbours.computeIfAbsent(fk.from, k -> new HashSet<>()).add(fk.to);
            neighbours.computeIfAbsent(fk.to, k -> new HashSet<>()).add(fk.from);
        }
        List<ErModel.Table> order = new ArrayList<>();
        Set<ErModel.Table> seen = new HashSet<>();
        List<ErModel.Table> byDegree = new ArrayList<>(model.tables.values());
        byDegree.sort((a, b) -> neighbours.getOrDefault(b, Set.of()).size() - neighbours.getOrDefault(a, Set.of()).size());
        for (ErModel.Table start : byDegree) {
            Deque<ErModel.Table> queue = new ArrayDeque<>();
            if (seen.add(start)) {
                queue.add(start);
            }
            while (!queue.isEmpty()) {
                ErModel.Table t = queue.poll();
                order.add(t);
                for (ErModel.Table n : neighbours.getOrDefault(t, Set.of())) {
                    if (seen.add(n)) {
                        queue.add(n);
                    }
                }
            }
        }
        int columns = Math.max(1, (int) Math.ceil(Math.sqrt(order.size() * 1.6)));
        int x = MARGIN;
        int y = MARGIN;
        int rowHeight = 0;
        int lineH = fm.getHeight() + 2;
        for (int i = 0; i < order.size(); i++) {
            ErModel.Table t = order.get(i);
            int w = fm.stringWidth(t.name) + 2 * PAD + 10;
            for (ErModel.Column c : t.columns) {
                w = Math.max(w, 2 * PAD + 24 + fm.stringWidth(c.name) + 16 + fm.stringWidth(c.type));
            }
            int h = lineH + 6 + t.columns.size() * lineH + PAD;
            if (i > 0 && i % columns == 0) {
                x = MARGIN;
                y += rowHeight + GAP;
                rowHeight = 0;
            }
            boxes.put(t, new Rectangle(x, y, w, h));
            x += w + GAP;
            rowHeight = Math.max(rowHeight, h);
        }
    }

    @Override
    public Dimension getPreferredSize() {
        int w = 0;
        int h = 0;
        for (Rectangle r : boxes.values()) {
            w = Math.max(w, r.x + r.width);
            h = Math.max(h, r.y + r.height);
        }
        // extra room on the right for the lanes that route around stacked boxes
        return new Dimension((int) ((w + MARGIN + 40) * zoom), (int) ((h + MARGIN) * zoom));
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setColor(background());
        g2.fillRect(0, 0, getWidth(), getHeight());
        g2.scale(zoom, zoom);
        paintDiagram(g2);
        g2.dispose();
    }

    BufferedImage toImage() {
        Dimension d = getPreferredSize();
        BufferedImage img = new BufferedImage((int) (d.width / zoom), (int) (d.height / zoom), BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = img.createGraphics();
        g2.setColor(background());
        g2.fillRect(0, 0, img.getWidth(), img.getHeight());
        paintDiagram(g2);
        g2.dispose();
        return img;
    }

    private static Color background() {
        Color c = UIManager.getColor("EditorPane.background"); // NOI18N
        return c != null ? c : Color.WHITE;
    }

    private static Color ui(String key, Color fallback) {
        Color c = UIManager.getColor(key);
        return c != null ? c : fallback;
    }

    private void paintDiagram(Graphics2D g2) {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Font font = font();
        FontMetrics fm = g2.getFontMetrics(font);
        int lineH = fm.getHeight() + 2;
        Color fg = ui("Label.foreground", Color.BLACK); // NOI18N
        Color muted = ui("Label.disabledForeground", Color.GRAY); // NOI18N
        Color border = ui("Component.borderColor", Color.LIGHT_GRAY); // NOI18N
        Color accent = ui("Component.accentColor", new Color(0x3574F0)); // NOI18N
        Color box = ui("Panel.background", Color.WHITE); // NOI18N
        Color header = mix(box, accent, 0.18f);
        Color pkColor = new Color(0xD9A343);
        Color fkColor = new Color(0x3592C4);

        g2.setStroke(new BasicStroke(1.4f));
        g2.setColor(mix(accent, box, 0.25f));
        int lanes = 0;
        for (ErModel.ForeignKey fk : model.foreignKeys) {
            Rectangle a = boxes.get(fk.from);
            Rectangle b = boxes.get(fk.to);
            if (a == null || b == null) {
                continue;
            }
            int row = 0;
            for (int i = 0; i < fk.from.columns.size(); i++) {
                if (!fk.fromColumns.isEmpty() && fk.from.columns.get(i).name.equalsIgnoreCase(fk.fromColumns.get(0))) {
                    row = i;
                    break;
                }
            }
            int ay = a.y + lineH + 6 + row * lineH + lineH / 2;
            double by = b.y + lineH / 2.0 + 3;
            if (fk.from == fk.to) {
                g2.drawArc(a.x + a.width - 20, a.y - 20, 40, 40, 270, 270);
                continue;
            }
            double[] path;
            if (a.x < b.getMaxX() && b.x < a.getMaxX()) {
                // stacked boxes: a straight connection would run underneath them, so go around on the right
                double laneX = Math.max(a.getMaxX(), b.getMaxX()) + 16 + (lanes++ % 4) * 6;
                path = new double[] {a.getMaxX(), ay, laneX, ay, laneX, by, b.getMaxX(), by};
            } else {
                boolean right = b.getCenterX() > a.getCenterX();
                double dir = right ? 1 : -1;
                Point2D from = new Point2D.Double(right ? a.getMaxX() : a.x, ay);
                Point2D to = new Point2D.Double(right ? b.x : b.getMaxX(), by);
                double midX = (from.getX() + to.getX()) / 2;
                path = new double[] {from.getX(), from.getY(), midX, from.getY(), midX, to.getY(), to.getX(), to.getY()};
                if (crossesOtherBox(path, a, b)) {
                    // go around: out of the source, up to a lane above both boxes, across, down into the target
                    double lane = Math.min(a.y, b.y) - 14 - (lanes++ % 4) * 5;
                    double outA = from.getX() + 14 * dir;
                    double outB = to.getX() - 14 * dir;
                    path = new double[] {from.getX(), from.getY(), outA, from.getY(), outA, lane, outB, lane,
                        outB, to.getY(), to.getX(), to.getY()};
                }
            }
            for (int p = 0; p + 3 < path.length; p += 2) {
                g2.draw(new Line2D.Double(path[p], path[p + 1], path[p + 2], path[p + 3]));
            }
            // crow's foot on the "many" side, a bar on the "one" side, oriented by the segment touching each box
            int n = path.length;
            double fromDir = Math.signum(path[2] - path[0]);
            double toDir = Math.signum(path[n - 2] - path[n - 4]);
            double fx = path[0];
            double fy = path[1];
            double tx = path[n - 2];
            double ty = path[n - 1];
            g2.draw(new Line2D.Double(fx, fy - 5, fx + 9 * fromDir, fy));
            g2.draw(new Line2D.Double(fx, fy + 5, fx + 9 * fromDir, fy));
            g2.draw(new Line2D.Double(tx - 6 * toDir, ty - 5, tx - 6 * toDir, ty + 5));
        }

        g2.setStroke(new BasicStroke(1f));
        for (Map.Entry<ErModel.Table, Rectangle> e : boxes.entrySet()) {
            ErModel.Table t = e.getKey();
            Rectangle r = e.getValue();
            g2.setColor(box);
            g2.fillRoundRect(r.x, r.y, r.width, r.height, 10, 10);
            g2.setColor(header);
            g2.fillRoundRect(r.x, r.y, r.width, lineH + 6, 10, 10);
            g2.fillRect(r.x, r.y + lineH, r.width, 6);
            g2.setColor(border);
            g2.drawRoundRect(r.x, r.y, r.width, r.height, 10, 10);
            g2.drawLine(r.x, r.y + lineH + 6, r.x + r.width, r.y + lineH + 6);

            g2.setFont(font.deriveFont(Font.BOLD));
            g2.setColor(fg);
            g2.drawString(t.name, r.x + PAD, r.y + 3 + fm.getAscent());
            g2.setFont(font);
            int y = r.y + lineH + 6;
            for (ErModel.Column c : t.columns) {
                int base = y + fm.getAscent() + 1;
                if (c.primaryKey || c.foreignKey) {
                    g2.setColor(c.primaryKey ? pkColor : fkColor);
                    g2.fillOval(r.x + PAD + 2, y + lineH / 2 - 4, 8, 8);
                }
                g2.setColor(fg);
                g2.setFont(c.primaryKey ? font.deriveFont(Font.BOLD) : font);
                g2.drawString(c.name, r.x + PAD + 18, base);
                g2.setFont(font);
                g2.setColor(muted);
                String type = c.nullable ? c.type : c.type + " *"; // NOI18N
                g2.drawString(type, r.x + r.width - PAD - fm.stringWidth(type), base);
                y += lineH;
            }
        }
    }

    private boolean crossesOtherBox(double[] path, Rectangle a, Rectangle b) {
        for (int p = 0; p + 3 < path.length; p += 2) {
            Line2D segment = new Line2D.Double(path[p], path[p + 1], path[p + 2], path[p + 3]);
            for (Rectangle r : boxes.values()) {
                if (r != a && r != b && segment.intersects(r)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Color mix(Color a, Color b, float amount) {
        return new Color(
                Math.round(a.getRed() + (b.getRed() - a.getRed()) * amount),
                Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * amount),
                Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * amount));
    }
}
